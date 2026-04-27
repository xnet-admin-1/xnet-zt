/*
 * Copyright (c) 2025-present XNet Inc. Apache License 2.0
 *
 * xnet_tap.c — lwIP initialization, netif management, Ethernet frame bridging.
 * Clean-room implementation. Uses lwIP headers only (no system sockets).
 */
#include <stdbool.h>
#include <ZeroTierOne.h>
#include "xnet_internal.h"

#include <string.h>
#include <stdlib.h>
#include <pthread.h>

#include "lwip/init.h"
#include "lwip/tcpip.h"
#include "lwip/netif.h"
#include "lwip/etharp.h"
#include "lwip/pbuf.h"
#include "lwip/ip4_addr.h"
#include "lwip/ethip6.h"
#include "lwip/nd6.h"

extern ZT_Node *g_node;

/* ── lwIP init ───────────────────────────────────────────────── */
static pthread_mutex_t lwip_init_mtx = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t  lwip_init_cv  = PTHREAD_COND_INITIALIZER;
static int lwip_inited = 0;

static void tcpip_init_done(void *arg) {
    pthread_mutex_lock(&lwip_init_mtx);
    lwip_inited = 1;
    g_lwip_up = 1;
    pthread_cond_signal(&lwip_init_cv);
    pthread_mutex_unlock(&lwip_init_mtx);
    if (g_callback) g_callback(230 /* XNET_EV_STACK_UP */, NULL);
}

void xnet_lwip_init(void) {
    pthread_mutex_lock(&lwip_init_mtx);
    if (!lwip_inited) {
        tcpip_init(tcpip_init_done, NULL);
        while (!lwip_inited)
            pthread_cond_wait(&lwip_init_cv, &lwip_init_mtx);
    }
    pthread_mutex_unlock(&lwip_init_mtx);
}

void xnet_lwip_shutdown(void) {
    g_lwip_up = 0;
    if (g_callback) g_callback(231 /* XNET_EV_STACK_DOWN */, NULL);
}

/* ── MAC helpers ─────────────────────────────────────────────── */
static void mac_to_bytes(uint64_t mac, uint8_t out[6]) {
    out[0] = (mac >> 40) & 0xff; out[1] = (mac >> 32) & 0xff;
    out[2] = (mac >> 24) & 0xff; out[3] = (mac >> 16) & 0xff;
    out[4] = (mac >>  8) & 0xff; out[5] =  mac        & 0xff;
}

static uint64_t bytes_to_mac(const uint8_t b[6]) {
    return ((uint64_t)b[0] << 40) | ((uint64_t)b[1] << 32) |
           ((uint64_t)b[2] << 24) | ((uint64_t)b[3] << 16) |
           ((uint64_t)b[4] <<  8) |  (uint64_t)b[5];
}

/* ── Ethernet TX: lwIP → ZT wire ─────────────────────────────── */
static err_t netif_linkoutput(struct netif *nif, struct pbuf *p) {
    xnet_tap *tap = (xnet_tap *)nif->state;
    if (!tap || !g_node) return ERR_IF;

    uint8_t buf[2048];
    uint16_t total = p->tot_len;
    if (total > sizeof(buf) || total < 14) return ERR_BUF;
    pbuf_copy_partial(p, buf, total, 0);

    uint64_t dst = bytes_to_mac(buf);
    uint64_t src = bytes_to_mac(buf + 6);
    uint16_t etype = ((uint16_t)buf[12] << 8) | buf[13];

    ZT_Node_processVirtualNetworkFrame(g_node, NULL,
        0, tap->nwid, src, dst, etype, 0,
        buf + 14, total - 14, &(volatile int64_t){0});
    return ERR_OK;
}

/* ── Netif init callback ─────────────────────────────────────── */
static err_t netif_init_cb(struct netif *nif) {
    xnet_tap *tap = (xnet_tap *)nif->state;
    nif->name[0] = 'x'; nif->name[1] = 'n';
    nif->hwaddr_len = 6;
    mac_to_bytes(tap->mac, nif->hwaddr);
    nif->mtu = tap->mtu ? tap->mtu : 1400;
    nif->linkoutput = netif_linkoutput;
    nif->output = etharp_output;
#if LWIP_IPV6
    nif->output_ip6 = ethip6_output;
#endif
    nif->flags = NETIF_FLAG_BROADCAST | NETIF_FLAG_ETHARP |
                 NETIF_FLAG_ETHERNET | NETIF_FLAG_LINK_UP;
    netif_set_link_up(nif);
    netif_set_up(nif);
    return ERR_OK;
}

/* ── Setup netif for a tap ───────────────────────────────────── */
void xnet_tap_setup_netif(void *vtap, int family) {
    xnet_tap *tap = (xnet_tap *)vtap;
    if (tap->netif_up) return;

    LOCK_TCPIP_CORE();
    if (family == 2 /* AF_INET */ && tap->has_ip4) {
        ip4_addr_t ip, mask, gw;
        ip.addr = tap->ip4_addr;
        IP4_ADDR(&mask, 255, 255, 255, 0);
        IP4_ADDR(&gw, 0, 0, 0, 0);
        netif_add(&tap->netif, &ip, &mask, &gw, tap,
                  netif_init_cb, tcpip_input);
        netif_set_default(&tap->netif);
        tap->netif_up = 1;
    }
#if LWIP_IPV6
    if (family == 10 /* AF_INET6 */ && tap->has_ip6 && tap->netif_up) {
        ip6_addr_t ip6;
        memcpy(&ip6.addr, tap->ip6_addr, 16);
        s8_t idx = -1;
        netif_add_ip6_address(&tap->netif, &ip6, &idx);
        if (idx >= 0)
            netif_ip6_addr_set_state(&tap->netif, idx, IP6_ADDR_VALID);
        netif_create_ip6_linklocal_address(&tap->netif, 1);
    }
#endif
    UNLOCK_TCPIP_CORE();
}

/* ── Teardown netif ──────────────────────────────────────────── */
void xnet_tap_teardown(void *vtap) {
    xnet_tap *tap = (xnet_tap *)vtap;
    if (!tap->netif_up) return;
    LOCK_TCPIP_CORE();
    netif_set_down(&tap->netif);
    netif_set_link_down(&tap->netif);
    netif_remove(&tap->netif);
    UNLOCK_TCPIP_CORE();
    tap->netif_up = 0;
    tap->has_ip4 = 0;
    tap->has_ip6 = 0;
}

/* ── Ethernet RX: ZT wire → lwIP ─────────────────────────────── */
void xnet_tap_rx(void *vtap, uint64_t srcMac, uint64_t dstMac,
                 unsigned int etherType, const void *data, unsigned int len) {
    xnet_tap *tap = (xnet_tap *)vtap;
    if (!tap->netif_up || !g_lwip_up) return;

    uint16_t total = 14 + len;
    struct pbuf *p = pbuf_alloc(PBUF_RAW, total, PBUF_POOL);
    if (!p) return;

    uint8_t hdr[14];
    mac_to_bytes(dstMac, hdr);
    mac_to_bytes(srcMac, hdr + 6);
    hdr[12] = (etherType >> 8) & 0xff;
    hdr[13] = etherType & 0xff;

    pbuf_take_at(p, hdr, 14, 0);
    pbuf_take_at(p, data, len, 14);

    if (tap->netif.input(p, &tap->netif) != ERR_OK)
        pbuf_free(p);
}
