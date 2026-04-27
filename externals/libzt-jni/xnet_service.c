/*
 * Copyright (c) 2025-present XNet Inc. Apache License 2.0
 *
 * xnet_service.c — ZeroTier node lifecycle, I/O loop, state persistence.
 * Clean-room implementation using the ZT C API + POSIX sockets.
 *
 * This file uses SYSTEM sockets for UDP wire I/O. It does NOT include
 * lwIP headers to avoid type redefinition conflicts.
 */
#include <stdbool.h>
#include <ZeroTierOne.h>
#include "xnet.h"

#include <string.h>
#include <stdlib.h>
#include <stdio.h>
#include <unistd.h>
#include <errno.h>
#include <pthread.h>
#include <sys/time.h>
#include <sys/stat.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <fcntl.h>
#include <poll.h>

/* ── Tap struct (must match xnet_internal.h layout) ──────────── */
/* We can't include xnet_internal.h because it pulls in lwip/netif.h
   which conflicts with system socket headers. So we duplicate the
   struct and use extern functions. */
typedef struct xnet_tap_s {
    uint64_t    nwid;
    uint64_t    mac;
    unsigned int mtu;
    int         has_ip4;
    int         has_ip6;
    uint32_t    ip4_addr;       /* network byte order */
    uint8_t     ip6_addr[16];
    char        _netif_opaque[512]; /* must be >= sizeof(struct netif) */
    int         netif_up;
} xnet_tap_s;

/* Functions from xnet_tap.c */
extern void xnet_tap_rx(void *tap, uint64_t srcMac, uint64_t dstMac,
                        unsigned int etherType, const void *data, unsigned int len);
extern void xnet_tap_setup_netif(void *tap, int family);
extern void xnet_tap_teardown(void *tap);
extern void xnet_lwip_init(void);
extern void xnet_lwip_shutdown(void);

/* ── Globals ─────────────────────────────────────────────────── */
ZT_Node          *g_node       = NULL;
xnet_event_fn     g_callback   = NULL;
volatile int      g_running    = 0;
volatile int      g_lwip_up    = 0;
volatile int      g_node_online= 0;
char              g_home[256]  = {0};
int               g_port       = 9994;
static pthread_t  g_svc_thread;
static int        g_udp4       = -1;
static int        g_udp6       = -1;
static pthread_mutex_t g_lock  = PTHREAD_MUTEX_INITIALIZER;

/* ── Tap registry (max 4 networks) ───────────────────────────── */
#define MAX_TAPS 4
#define MAX_TAP_ADDRS 4
xnet_tap_s g_taps[MAX_TAPS];
int        g_tap_count = 0;

static xnet_tap_s *tap_find(uint64_t nwid) {
    for (int i = 0; i < g_tap_count; i++)
        if (g_taps[i].nwid == nwid) return &g_taps[i];
    return NULL;
}

/* Exported for xnet_tap.c via xnet_tap_find */
void *xnet_tap_find(uint64_t nwid) { return tap_find(nwid); }

/* ── Time helper ─────────────────────────────────────────────── */
static int64_t now_ms(void) {
    struct timeval tv;
    gettimeofday(&tv, NULL);
    return (int64_t)tv.tv_sec * 1000 + tv.tv_usec / 1000;
}

/* ── State persistence ───────────────────────────────────────── */
static void make_state_path(char *buf, size_t sz,
                            enum ZT_StateObjectType type, const uint64_t id[2]) {
    switch (type) {
    case ZT_STATE_OBJECT_IDENTITY_PUBLIC:
        snprintf(buf, sz, "%s/identity.public", g_home); break;
    case ZT_STATE_OBJECT_IDENTITY_SECRET:
        snprintf(buf, sz, "%s/identity.secret", g_home); break;
    case ZT_STATE_OBJECT_PLANET:
        snprintf(buf, sz, "%s/planet", g_home); break;
    case ZT_STATE_OBJECT_NETWORK_CONFIG:
        snprintf(buf, sz, "%s/networks.d/%016llx.conf", g_home,
                 (unsigned long long)id[0]); break;
    case ZT_STATE_OBJECT_PEER:
        snprintf(buf, sz, "%s/peers.d/%010llx.peer", g_home,
                 (unsigned long long)id[0]); break;
    default:
        buf[0] = 0; break;
    }
}

static void cb_state_put(ZT_Node *node, void *uptr, void *tptr,
                         enum ZT_StateObjectType type, const uint64_t id[2],
                         const void *data, int len) {
    char path[512];
    make_state_path(path, sizeof(path), type, id);
    if (!path[0]) return;
    char *slash = strrchr(path, '/');
    if (slash) { *slash = 0; mkdir(path, 0700); *slash = '/'; }
    FILE *f = fopen(path, "wb");
    if (f) { fwrite(data, 1, len, f); fclose(f); }
}

static int cb_state_get(ZT_Node *node, void *uptr, void *tptr,
                        enum ZT_StateObjectType type, const uint64_t id[2],
                        void *data, unsigned int maxlen) {
    char path[512];
    make_state_path(path, sizeof(path), type, id);
    if (!path[0]) return -1;
    if (type == ZT_STATE_OBJECT_PLANET) {
        char mars[512];
        snprintf(mars, sizeof(mars), "%s/mars", g_home);
        FILE *f = fopen(mars, "rb");
        if (f) { int n = fread(data, 1, maxlen, f); fclose(f); if (n > 0) return n; }
    }
    FILE *f = fopen(path, "rb");
    if (!f) return -1;
    int n = fread(data, 1, maxlen, f);
    fclose(f);
    return n > 0 ? n : -1;
}

/* ── Wire I/O ────────────────────────────────────────────────── */
static int cb_wire_send(ZT_Node *node, void *uptr, void *tptr,
                        int64_t localSocket, const struct sockaddr_storage *addr,
                        const void *data, unsigned int len, unsigned int ttl) {
    int fd = (((struct sockaddr *)addr)->sa_family == AF_INET6) ? g_udp6 : g_udp4;
    if (fd < 0) return -1;
    socklen_t alen = (((struct sockaddr *)addr)->sa_family == AF_INET6)
                     ? sizeof(struct sockaddr_in6) : sizeof(struct sockaddr_in);
    sendto(fd, data, len, 0, (const struct sockaddr *)addr, alen);
    return 0;
}

/* ── Virtual network frame from ZT wire → lwIP ──────────────── */
static void cb_vframe(ZT_Node *node, void *uptr, void *tptr,
                      uint64_t nwid, void **nuptr,
                      uint64_t srcMac, uint64_t dstMac,
                      unsigned int etherType, unsigned int vlanId,
                      const void *data, unsigned int len) {
    xnet_tap_s *tap = tap_find(nwid);
    if (tap) xnet_tap_rx(tap, srcMac, dstMac, etherType, data, len);
}

/* ── Network config changes ──────────────────────────────────── */
static int cb_vnet_config(ZT_Node *node, void *uptr, void *tptr,
                          uint64_t nwid, void **nuptr,
                          enum ZT_VirtualNetworkConfigOperation op,
                          const ZT_VirtualNetworkConfig *cfg) {
    pthread_mutex_lock(&g_lock);
    switch (op) {
    case ZT_VIRTUAL_NETWORK_CONFIG_OPERATION_UP:
    case ZT_VIRTUAL_NETWORK_CONFIG_OPERATION_CONFIG_UPDATE: {
        xnet_tap_s *tap = tap_find(nwid);
        if (!tap && g_tap_count < MAX_TAPS) {
            tap = &g_taps[g_tap_count++];
            memset(tap, 0, sizeof(*tap));
            tap->nwid = nwid;
        }
        if (tap && cfg) {
            tap->mac = cfg->mac;
            tap->mtu = cfg->mtu;
            for (unsigned i = 0; i < cfg->assignedAddressCount && i < MAX_TAP_ADDRS; i++) {
                const struct sockaddr *sa = (const struct sockaddr *)&cfg->assignedAddresses[i];
                if (sa->sa_family == AF_INET && !tap->has_ip4) {
                    const struct sockaddr_in *sin = (const struct sockaddr_in *)sa;
                    tap->ip4_addr = sin->sin_addr.s_addr;
                    tap->has_ip4 = 1;
                    xnet_tap_setup_netif(tap, AF_INET);
                    if (g_callback) g_callback(XNET_EV_ADDR_ADDED_IP4, NULL);
                    if (g_callback) g_callback(XNET_EV_NETWORK_READY_IP4, NULL);
                } else if (sa->sa_family == AF_INET6 && !tap->has_ip6) {
                    const struct sockaddr_in6 *sin6 = (const struct sockaddr_in6 *)sa;
                    memcpy(tap->ip6_addr, &sin6->sin6_addr, 16);
                    tap->has_ip6 = 1;
                    xnet_tap_setup_netif(tap, AF_INET6);
                    if (g_callback) g_callback(XNET_EV_ADDR_ADDED_IP6, NULL);
                }
            }
            if (cfg->status == ZT_NETWORK_STATUS_OK && g_callback)
                g_callback(XNET_EV_NETWORK_OK, NULL);
            else if (cfg->status == ZT_NETWORK_STATUS_ACCESS_DENIED && g_callback)
                g_callback(XNET_EV_NETWORK_DENIED, NULL);
            else if (cfg->status == ZT_NETWORK_STATUS_NOT_FOUND && g_callback)
                g_callback(XNET_EV_NETWORK_NOT_FOUND, NULL);
        }
        break;
    }
    case ZT_VIRTUAL_NETWORK_CONFIG_OPERATION_DOWN:
    case ZT_VIRTUAL_NETWORK_CONFIG_OPERATION_DESTROY: {
        xnet_tap_s *tap = tap_find(nwid);
        if (tap) {
            xnet_tap_teardown(tap);
            int idx = (int)(tap - g_taps);
            if (idx < g_tap_count - 1)
                g_taps[idx] = g_taps[g_tap_count - 1];
            g_tap_count--;
            if (g_callback) g_callback(XNET_EV_NETWORK_DOWN, NULL);
        }
        break;
    }
    default: break;
    }
    pthread_mutex_unlock(&g_lock);
    return 0;
}

/* ── Node events ─────────────────────────────────────────────── */
static void cb_event(ZT_Node *node, void *uptr, void *tptr,
                     enum ZT_Event event, const void *payload) {
    switch (event) {
    case ZT_EVENT_ONLINE:
        g_node_online = 1;
        if (g_callback) g_callback(XNET_EV_NODE_ONLINE, NULL);
        break;
    case ZT_EVENT_OFFLINE:
        g_node_online = 0;
        if (g_callback) g_callback(XNET_EV_NODE_OFFLINE, NULL);
        break;
    default: break;
    }
}

static int cb_path_check(ZT_Node *node, void *uptr, void *tptr,
                         uint64_t addr, int64_t localSocket,
                         const struct sockaddr_storage *sa) { return 1; }

static int cb_path_lookup(ZT_Node *node, void *uptr, void *tptr,
                          uint64_t addr, int family,
                          struct sockaddr_storage *result) { return 0; }

/* ── UDP socket setup ────────────────────────────────────────── */
static int bind_udp(int family, int port) {
    int fd = socket(family, SOCK_DGRAM, 0);
    if (fd < 0) return -1;
    int one = 1;
    setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one));
    fcntl(fd, F_SETFL, fcntl(fd, F_GETFL) | O_NONBLOCK);
    if (family == AF_INET) {
        struct sockaddr_in a = {0};
        a.sin_family = AF_INET;
        a.sin_port = htons(port);
        if (bind(fd, (struct sockaddr *)&a, sizeof(a)) < 0) { close(fd); return -1; }
    } else {
        struct sockaddr_in6 a = {0};
        a.sin6_family = AF_INET6;
        a.sin6_port = htons(port);
        if (bind(fd, (struct sockaddr *)&a, sizeof(a)) < 0) { close(fd); return -1; }
    }
    return fd;
}

/* ── Service I/O thread ──────────────────────────────────────── */
static void *svc_thread(void *arg) {
    struct ZT_Node_Callbacks cbs = {0};
    cbs.version = 0;
    cbs.statePutFunction = cb_state_put;
    cbs.stateGetFunction = cb_state_get;
    cbs.wirePacketSendFunction = cb_wire_send;
    cbs.virtualNetworkFrameFunction = cb_vframe;
    cbs.virtualNetworkConfigFunction = cb_vnet_config;
    cbs.eventCallback = cb_event;
    cbs.pathCheckFunction = cb_path_check;
    cbs.pathLookupFunction = cb_path_lookup;

    if (ZT_Node_new(&g_node, NULL, NULL, &cbs, now_ms()) != ZT_RESULT_OK) {
        g_running = 0;
        return NULL;
    }

    g_udp4 = bind_udp(AF_INET, g_port);
    g_udp6 = bind_udp(AF_INET6, g_port);

    volatile int64_t deadline = 0;
    uint8_t pkt[16384];

    while (g_running) {
        struct pollfd fds[2];
        int nfds = 0;
        if (g_udp4 >= 0) { fds[nfds].fd = g_udp4; fds[nfds].events = POLLIN; nfds++; }
        if (g_udp6 >= 0) { fds[nfds].fd = g_udp6; fds[nfds].events = POLLIN; nfds++; }

        int64_t wait = deadline - now_ms();
        if (wait < 1) wait = 1;
        if (wait > 1000) wait = 1000;
        poll(fds, nfds, (int)wait);

        for (int i = 0; i < nfds; i++) {
            if (!(fds[i].revents & POLLIN)) continue;
            struct sockaddr_storage from;
            socklen_t fromlen = sizeof(from);
            ssize_t n = recvfrom(fds[i].fd, pkt, sizeof(pkt), 0,
                                 (struct sockaddr *)&from, &fromlen);
            if (n > 0)
                ZT_Node_processWirePacket(g_node, NULL, now_ms(),
                                          0, &from, pkt, (unsigned int)n, &deadline);
        }
        ZT_Node_processBackgroundTasks(g_node, NULL, now_ms(), &deadline);
    }

    ZT_Node_delete(g_node);
    g_node = NULL;
    g_node_online = 0;
    if (g_udp4 >= 0) { close(g_udp4); g_udp4 = -1; }
    if (g_udp6 >= 0) { close(g_udp6); g_udp6 = -1; }
    return NULL;
}

/* ── Public API ──────────────────────────────────────────────── */
int xnet_start(const char *path, xnet_event_fn cb, int port) {
    if (!path || !cb) return XNET_ERR_ARG;
    if (g_running) return XNET_ERR_OP;

    strncpy(g_home, path, sizeof(g_home) - 1);
    g_callback = cb;
    g_port = port > 0 ? port : 9994;
    g_running = 1;

    mkdir(g_home, 0700);
    char sub[512];
    snprintf(sub, sizeof(sub), "%s/networks.d", g_home); mkdir(sub, 0700);
    snprintf(sub, sizeof(sub), "%s/peers.d", g_home); mkdir(sub, 0700);

    xnet_lwip_init();

    if (pthread_create(&g_svc_thread, NULL, svc_thread, NULL) != 0) {
        g_running = 0;
        return XNET_ERR_GENERAL;
    }
    return XNET_OK;
}

int xnet_stop(void) {
    if (!g_running) return XNET_ERR_OP;
    g_running = 0;
    pthread_join(g_svc_thread, NULL);
    xnet_lwip_shutdown();
    for (int i = 0; i < g_tap_count; i++)
        xnet_tap_teardown(&g_taps[i]);
    g_tap_count = 0;
    return XNET_OK;
}

int xnet_join(uint64_t nwid) {
    if (!g_node) return XNET_ERR_SERVICE;
    return ZT_Node_join(g_node, nwid, NULL, NULL) == ZT_RESULT_OK
           ? XNET_OK : XNET_ERR_GENERAL;
}

int xnet_leave(uint64_t nwid) {
    if (!g_node) return XNET_ERR_SERVICE;
    return ZT_Node_leave(g_node, nwid, NULL, NULL) == ZT_RESULT_OK
           ? XNET_OK : XNET_ERR_GENERAL;
}

uint64_t xnet_node_id(void) {
    return g_node ? ZT_Node_address(g_node) : 0;
}

int xnet_running(void) {
    return g_running && g_node_online && g_lwip_up;
}

int xnet_has_addr(uint64_t nwid, int family) {
    xnet_tap_s *tap = tap_find(nwid);
    if (!tap) return 0;
    return (family == AF_INET) ? tap->has_ip4 : tap->has_ip6;
}

int xnet_get_addr(uint64_t nwid, int family, struct sockaddr_storage *addr) {
    if (!addr) return XNET_ERR_ARG;
    xnet_tap_s *tap = tap_find(nwid);
    if (!tap) return XNET_ERR_GENERAL;
    if (family == AF_INET && tap->has_ip4) {
        struct sockaddr_in *sin = (struct sockaddr_in *)addr;
        memset(sin, 0, sizeof(*sin));
        sin->sin_family = AF_INET;
        sin->sin_addr.s_addr = tap->ip4_addr;
        return XNET_OK;
    }
    if (family == AF_INET6 && tap->has_ip6) {
        struct sockaddr_in6 *sin6 = (struct sockaddr_in6 *)addr;
        memset(sin6, 0, sizeof(*sin6));
        sin6->sin6_family = AF_INET6;
        memcpy(&sin6->sin6_addr, tap->ip6_addr, 16);
        return XNET_OK;
    }
    return XNET_ERR_GENERAL;
}
