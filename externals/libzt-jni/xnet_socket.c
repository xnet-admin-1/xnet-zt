/*
 * Copyright (c) 2025-present XNet Inc. Apache License 2.0
 *
 * xnet_socket.c — BSD socket API wrappers over lwIP.
 * Clean-room implementation.
 */
#include "xnet_internal.h"
#include "lwip/sockets.h"

/* error codes from xnet.h — inline to avoid header conflicts */
#define XNET_ERR_SERVICE   -2
#define XNET_ERR_ARG       -1

/* gate: return error if service not ready */
#define READY_CHECK() do { \
    if (!g_running || !g_lwip_up) return XNET_ERR_SERVICE; \
} while(0)

int xnet_socket(int domain, int type, int protocol) {
    READY_CHECK();
    return lwip_socket(domain, type, protocol);
}

int xnet_connect(int fd, const struct sockaddr *addr, socklen_t len) {
    READY_CHECK();
    if (!addr) return XNET_ERR_ARG;
    return lwip_connect(fd, addr, len);
}

int xnet_bind(int fd, const struct sockaddr *addr, socklen_t len) {
    READY_CHECK();
    if (!addr) return XNET_ERR_ARG;
    return lwip_bind(fd, addr, len);
}

int xnet_listen(int fd, int backlog) {
    READY_CHECK();
    return lwip_listen(fd, backlog);
}

int xnet_accept(int fd, struct sockaddr *addr, socklen_t *len) {
    READY_CHECK();
    return lwip_accept(fd, addr, len);
}

int xnet_send(int fd, const void *buf, size_t len, int flags) {
    READY_CHECK();
    if (!buf || len == 0) return XNET_ERR_ARG;
    return lwip_send(fd, buf, len, flags);
}

int xnet_sendto(int fd, const void *buf, size_t len, int flags,
                const struct sockaddr *addr, socklen_t addrlen) {
    READY_CHECK();
    if (!buf || len == 0) return XNET_ERR_ARG;
    return lwip_sendto(fd, buf, len, flags, addr, addrlen);
}

int xnet_recv(int fd, void *buf, size_t len, int flags) {
    READY_CHECK();
    if (!buf) return XNET_ERR_ARG;
    return lwip_recv(fd, buf, len, flags);
}

int xnet_recvfrom(int fd, void *buf, size_t len, int flags,
                  struct sockaddr *addr, socklen_t *addrlen) {
    READY_CHECK();
    if (!buf) return XNET_ERR_ARG;
    return lwip_recvfrom(fd, buf, len, flags, addr, addrlen);
}

int xnet_close(int fd) {
    return lwip_close(fd);
}

int xnet_setsockopt(int fd, int level, int optname,
                    const void *optval, socklen_t optlen) {
    READY_CHECK();
    return lwip_setsockopt(fd, level, optname, optval, optlen);
}

int xnet_getsockopt(int fd, int level, int optname,
                    void *optval, socklen_t *optlen) {
    READY_CHECK();
    return lwip_getsockopt(fd, level, optname, optval, optlen);
}

int xnet_shutdown(int fd, int how) {
    return lwip_shutdown(fd, how);
}

int xnet_fcntl(int fd, int cmd, int flags) {
    READY_CHECK();
    /* translate platform O_NONBLOCK to lwIP's value (1) */
    if (cmd == F_SETFL && (flags & 2048)) /* Linux O_NONBLOCK */
        flags = (flags & ~2048) | 1;
    return lwip_fcntl(fd, cmd, flags);
}
