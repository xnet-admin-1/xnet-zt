/*
 * Copyright (c) 2025-present XNet Inc. Apache License 2.0
 *
 * libxnet — userspace ZeroTier + lwIP glue layer
 * Clean-room implementation, no GPL code.
 */
#ifndef XNET_H
#define XNET_H

#include <stdint.h>
#include <sys/socket.h>
#include <netinet/in.h>

#ifdef __cplusplus
extern "C" {
#endif

/* ── Error codes ─────────────────────────────────────────────── */
#define XNET_OK             0
#define XNET_ERR_ARG       -1
#define XNET_ERR_SERVICE   -2
#define XNET_ERR_OP        -3
#define XNET_ERR_GENERAL   -5

/* ── Event codes ─────────────────────────────────────────────── */
#define XNET_EV_NODE_ONLINE         200
#define XNET_EV_NODE_OFFLINE        201
#define XNET_EV_NETWORK_OK          210
#define XNET_EV_NETWORK_DENIED      211
#define XNET_EV_NETWORK_NOT_FOUND   212
#define XNET_EV_NETWORK_READY_IP4   213
#define XNET_EV_NETWORK_READY_IP6   214
#define XNET_EV_NETWORK_DOWN        215
#define XNET_EV_ADDR_ADDED_IP4      220
#define XNET_EV_ADDR_ADDED_IP6      221
#define XNET_EV_ADDR_REMOVED_IP4    222
#define XNET_EV_ADDR_REMOVED_IP6    223
#define XNET_EV_STACK_UP            230
#define XNET_EV_STACK_DOWN          231
#define XNET_EV_PEER_P2P            240
#define XNET_EV_PEER_RELAY          241

/* ── Callback ────────────────────────────────────────────────── */
typedef void (*xnet_event_fn)(int event_code, const void *arg);

/* ── Service lifecycle ───────────────────────────────────────── */
int  xnet_start(const char *path, xnet_event_fn cb, int port);
int  xnet_stop(void);
int  xnet_join(uint64_t nwid);
int  xnet_leave(uint64_t nwid);
uint64_t xnet_node_id(void);
int  xnet_running(void);

/* ── Address query ───────────────────────────────────────────── */
int  xnet_has_addr(uint64_t nwid, int family);
int  xnet_get_addr(uint64_t nwid, int family, struct sockaddr_storage *addr);

/* ── BSD socket API (over lwIP) ──────────────────────────────── */
int  xnet_socket(int domain, int type, int protocol);
int  xnet_connect(int fd, const struct sockaddr *addr, socklen_t len);
int  xnet_bind(int fd, const struct sockaddr *addr, socklen_t len);
int  xnet_listen(int fd, int backlog);
int  xnet_accept(int fd, struct sockaddr *addr, socklen_t *len);
int  xnet_send(int fd, const void *buf, size_t len, int flags);
int  xnet_sendto(int fd, const void *buf, size_t len, int flags,
                 const struct sockaddr *addr, socklen_t addrlen);
int  xnet_recv(int fd, void *buf, size_t len, int flags);
int  xnet_recvfrom(int fd, void *buf, size_t len, int flags,
                   struct sockaddr *addr, socklen_t *addrlen);
int  xnet_close(int fd);
int  xnet_setsockopt(int fd, int level, int optname,
                     const void *optval, socklen_t optlen);
int  xnet_getsockopt(int fd, int level, int optname,
                     void *optval, socklen_t *optlen);
int  xnet_shutdown(int fd, int how);
int  xnet_fcntl(int fd, int cmd, int flags);

#ifdef __cplusplus
}
#endif
#endif /* XNET_H */
