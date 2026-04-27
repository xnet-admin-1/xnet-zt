/*
 * Copyright (c) 2025-present XNet Inc. Apache License 2.0
 * Internal header — not part of public API.
 * Only included by files that use lwIP (xnet_tap.c, xnet_socket.c).
 */
#ifndef XNET_INTERNAL_H
#define XNET_INTERNAL_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#include "lwip/netif.h"
#include "lwip/ip_addr.h"

#define MAX_TAP_ADDRS 4

/* ── Per-network virtual tap (lwIP-aware version) ────────────── */
typedef struct xnet_tap {
    uint64_t    nwid;
    uint64_t    mac;
    unsigned int mtu;
    int         has_ip4;
    int         has_ip6;
    /* Store IPs as raw bytes to avoid system/lwIP sockaddr conflicts */
    uint32_t    ip4_addr;       /* network byte order */
    uint8_t     ip6_addr[16];
    struct netif netif;
    int         netif_up;
} xnet_tap;

/* ── Globals (owned by xnet_service.c) ───────────────────────── */
extern volatile int   g_running;
extern volatile int   g_lwip_up;
extern volatile int   g_node_online;

/* ── Tap functions (xnet_tap.c) ──────────────────────────────── */
void xnet_tap_rx(void *tap, uint64_t srcMac, uint64_t dstMac,
                 unsigned int etherType, const void *data, unsigned int len);
void xnet_tap_setup_netif(void *tap, int family);
void xnet_tap_teardown(void *tap);

/* ── lwIP init/shutdown (xnet_tap.c) ─────────────────────────── */
void xnet_lwip_init(void);
void xnet_lwip_shutdown(void);

/* ── Event callback ──────────────────────────────────────────── */
typedef void (*xnet_event_fn)(int event_code, const void *arg);
extern xnet_event_fn g_callback;

#ifdef __cplusplus
}
#endif
#endif /* XNET_INTERNAL_H */
