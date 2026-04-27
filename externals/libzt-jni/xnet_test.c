/*
 * Copyright (c) 2025-present XNet Inc. Apache License 2.0
 *
 * xnet_test.c — Unit tests for libxnet glue layer.
 * Tests API contracts, error handling, and state machine.
 * Does NOT require a live ZT network — tests the code paths, not connectivity.
 */
#include "xnet.h"
#include <stdio.h>
#include <string.h>
#include <assert.h>
#include <unistd.h>
#include <sys/stat.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>

/* Access service internals for tap registry tests */
extern volatile int g_running;
extern volatile int g_lwip_up;
extern volatile int g_node_online;

/* The tap struct as defined in xnet_service.c */
typedef struct {
    uint64_t    nwid;
    uint64_t    mac;
    unsigned int mtu;
    int         has_ip4;
    int         has_ip6;
    uint32_t    ip4_addr;
    uint8_t     ip6_addr[16];
    char        _netif_opaque[512];
    int         netif_up;
} test_tap_s;

extern test_tap_s g_taps[];
extern int g_tap_count;
extern void *xnet_tap_find(uint64_t nwid);

static int pass = 0, fail = 0;

#define TEST(name) static void test_##name(void)
#define RUN(name) do { \
    printf("  %-50s", #name); \
    test_##name(); \
    printf(" PASS\n"); pass++; \
} while(0)
#define ASSERT_EQ(a, b) do { \
    if ((a) != (b)) { \
        printf(" FAIL (line %d: %d != %d)\n", __LINE__, (int)(a), (int)(b)); \
        fail++; return; \
    } \
} while(0)
#define ASSERT_NE(a, b) do { \
    if ((a) == (b)) { \
        printf(" FAIL (line %d: %d == %d)\n", __LINE__, (int)(a), (int)(b)); \
        fail++; return; \
    } \
} while(0)
#define ASSERT_TRUE(x) ASSERT_NE((x), 0)
#define ASSERT_NULL(x) do { \
    if ((x) != NULL) { \
        printf(" FAIL (line %d: expected NULL)\n", __LINE__); \
        fail++; return; \
    } \
} while(0)

/* ── 1. API argument validation ──────────────────────────────── */

TEST(start_null_path) {
    ASSERT_EQ(xnet_start(NULL, (xnet_event_fn)1, 0), XNET_ERR_ARG);
}

TEST(start_null_callback) {
    ASSERT_EQ(xnet_start("/tmp/xnet_test", NULL, 0), XNET_ERR_ARG);
}

TEST(stop_when_not_running) {
    ASSERT_EQ(xnet_stop(), XNET_ERR_OP);
}

TEST(join_when_no_node) {
    ASSERT_EQ(xnet_join(0xdeadbeef), XNET_ERR_SERVICE);
}

TEST(leave_when_no_node) {
    ASSERT_EQ(xnet_leave(0xdeadbeef), XNET_ERR_SERVICE);
}

TEST(node_id_when_no_node) {
    ASSERT_EQ(xnet_node_id(), 0);
}

TEST(running_when_stopped) {
    ASSERT_EQ(xnet_running(), 0);
}

/* ── 2. Address query validation ─────────────────────────────── */

TEST(get_addr_null) {
    ASSERT_EQ(xnet_get_addr(0, AF_INET, NULL), XNET_ERR_ARG);
}

TEST(get_addr_no_tap) {
    struct sockaddr_storage ss;
    ASSERT_EQ(xnet_get_addr(0xdeadbeef, AF_INET, &ss), XNET_ERR_GENERAL);
}

TEST(has_addr_no_tap) {
    ASSERT_EQ(xnet_has_addr(0xdeadbeef, AF_INET), 0);
}

/* ── 3. Socket API when service not running ──────────────────── */

TEST(socket_not_running) {
    ASSERT_EQ(xnet_socket(AF_INET, SOCK_STREAM, 0), XNET_ERR_SERVICE);
}

TEST(connect_not_running) {
    struct sockaddr_in sa = {0};
    ASSERT_EQ(xnet_connect(0, (struct sockaddr *)&sa, sizeof(sa)), XNET_ERR_SERVICE);
}

TEST(bind_not_running) {
    struct sockaddr_in sa = {0};
    ASSERT_EQ(xnet_bind(0, (struct sockaddr *)&sa, sizeof(sa)), XNET_ERR_SERVICE);
}

TEST(listen_not_running) {
    ASSERT_EQ(xnet_listen(0, 5), XNET_ERR_SERVICE);
}

TEST(accept_not_running) {
    ASSERT_EQ(xnet_accept(0, NULL, NULL), XNET_ERR_SERVICE);
}

TEST(send_not_running) {
    char buf[] = "x";
    ASSERT_EQ(xnet_send(0, buf, 1, 0), XNET_ERR_SERVICE);
}

TEST(recv_not_running) {
    char buf[16];
    ASSERT_EQ(xnet_recv(0, buf, sizeof(buf), 0), XNET_ERR_SERVICE);
}

TEST(sendto_not_running) {
    char buf[] = "x";
    ASSERT_EQ(xnet_sendto(0, buf, 1, 0, NULL, 0), XNET_ERR_SERVICE);
}

TEST(recvfrom_not_running) {
    char buf[16];
    ASSERT_EQ(xnet_recvfrom(0, buf, sizeof(buf), 0, NULL, NULL), XNET_ERR_SERVICE);
}

TEST(setsockopt_not_running) {
    int val = 1;
    ASSERT_EQ(xnet_setsockopt(0, 0, 0, &val, sizeof(val)), XNET_ERR_SERVICE);
}

TEST(getsockopt_not_running) {
    int val; socklen_t len = sizeof(val);
    ASSERT_EQ(xnet_getsockopt(0, 0, 0, &val, &len), XNET_ERR_SERVICE);
}

TEST(fcntl_not_running) {
    ASSERT_EQ(xnet_fcntl(0, 0, 0), XNET_ERR_SERVICE);
}

/* ── 4. Socket API argument validation ───────────────────────── */
/* These test the arg checks that happen before the readiness check.
   Since service isn't running, we get XNET_ERR_SERVICE first for most,
   but connect/bind with NULL addr should return XNET_ERR_ARG... except
   the readiness check comes first. So we verify the service gate works. */

TEST(send_null_buf) {
    /* service not running → ERR_SERVICE takes precedence */
    ASSERT_EQ(xnet_send(0, NULL, 1, 0), XNET_ERR_SERVICE);
}

TEST(recv_null_buf) {
    ASSERT_EQ(xnet_recv(0, NULL, 16, 0), XNET_ERR_SERVICE);
}

/* ── 5. Tap registry ─────────────────────────────────────────── */

TEST(tap_find_empty) {
    ASSERT_NULL(xnet_tap_find(0xdeadbeef));
}

TEST(tap_find_after_add) {
    /* manually add a tap entry */
    int old_count = g_tap_count;
    g_taps[g_tap_count].nwid = 0x1234;
    g_tap_count++;
    xnet_tap *t = xnet_tap_find(0x1234);
    ASSERT_TRUE(t != NULL);
    ASSERT_EQ(t->nwid, 0x1234);
    /* cleanup */
    g_tap_count = old_count;
}

TEST(tap_find_miss) {
    int old_count = g_tap_count;
    g_taps[g_tap_count].nwid = 0x1234;
    g_tap_count++;
    ASSERT_NULL(xnet_tap_find(0x5678));
    g_tap_count = old_count;
}

/* ── 6. State machine ────────────────────────────────────────── */

TEST(double_stop) {
    ASSERT_EQ(xnet_stop(), XNET_ERR_OP);
    ASSERT_EQ(xnet_stop(), XNET_ERR_OP);
}

/* ── 7. Close works without service (no gate) ────────────────── */

TEST(close_bad_fd) {
    /* lwip_close on invalid fd returns -1, not XNET_ERR_SERVICE */
    int rc = xnet_close(9999);
    ASSERT_EQ(rc, -1);
}

TEST(shutdown_bad_fd) {
    int rc = xnet_shutdown(9999, 0);
    ASSERT_EQ(rc, -1);
}

/* ── 8. Address management on tap ────────────────────────────── */

TEST(has_addr_with_ip4) {
    int old_count = g_tap_count;
    memset(&g_taps[g_tap_count], 0, sizeof(g_taps[0]));
    g_taps[g_tap_count].nwid = 0xAAAA;
    g_taps[g_tap_count].has_ip4 = 1;
    g_tap_count++;
    ASSERT_EQ(xnet_has_addr(0xAAAA, AF_INET), 1);
    ASSERT_EQ(xnet_has_addr(0xAAAA, AF_INET6), 0);
    g_tap_count = old_count;
}

TEST(get_addr_with_ip4) {
    int old_count = g_tap_count;
    memset(&g_taps[g_tap_count], 0, sizeof(g_taps[0]));
    g_taps[g_tap_count].nwid = 0xBBBB;
    g_taps[g_tap_count].has_ip4 = 1;
    g_taps[g_tap_count].ip4_addr = htonl(0x0A0A0A01);
    g_tap_count++;
    struct sockaddr_storage ss;
    ASSERT_EQ(xnet_get_addr(0xBBBB, AF_INET, &ss), XNET_OK);
    struct sockaddr_in *sin = (struct sockaddr_in *)&ss;
    ASSERT_EQ(sin->sin_addr.s_addr, htonl(0x0A0A0A01));
    g_tap_count = old_count;
}

TEST(get_addr_wrong_family) {
    int old_count = g_tap_count;
    memset(&g_taps[g_tap_count], 0, sizeof(g_taps[0]));
    g_taps[g_tap_count].nwid = 0xCCCC;
    g_taps[g_tap_count].has_ip4 = 1;
    g_tap_count++;
    struct sockaddr_storage ss;
    ASSERT_EQ(xnet_get_addr(0xCCCC, AF_INET6, &ss), XNET_ERR_GENERAL);
    g_tap_count = old_count;
}

/* ── Main ────────────────────────────────────────────────────── */
int main(void) {
    printf("libxnet unit tests\n");
    printf("==================\n\n");

    printf("[API argument validation]\n");
    RUN(start_null_path);
    RUN(start_null_callback);
    RUN(stop_when_not_running);
    RUN(join_when_no_node);
    RUN(leave_when_no_node);
    RUN(node_id_when_no_node);
    RUN(running_when_stopped);

    printf("\n[Address query]\n");
    RUN(get_addr_null);
    RUN(get_addr_no_tap);
    RUN(has_addr_no_tap);

    printf("\n[Socket API - service not running]\n");
    RUN(socket_not_running);
    RUN(connect_not_running);
    RUN(bind_not_running);
    RUN(listen_not_running);
    RUN(accept_not_running);
    RUN(send_not_running);
    RUN(recv_not_running);
    RUN(sendto_not_running);
    RUN(recvfrom_not_running);
    RUN(setsockopt_not_running);
    RUN(getsockopt_not_running);
    RUN(fcntl_not_running);

    printf("\n[Socket API - arg validation]\n");
    RUN(send_null_buf);
    RUN(recv_null_buf);

    printf("\n[Tap registry]\n");
    RUN(tap_find_empty);
    RUN(tap_find_after_add);
    RUN(tap_find_miss);

    printf("\n[State machine]\n");
    RUN(double_stop);

    printf("\n[Close/shutdown without service]\n");
    RUN(close_bad_fd);
    RUN(shutdown_bad_fd);

    printf("\n[Address management]\n");
    RUN(has_addr_with_ip4);
    RUN(get_addr_with_ip4);
    RUN(get_addr_wrong_family);

    printf("\n==================\n");
    printf("Results: %d passed, %d failed\n", pass, fail);
    return fail > 0 ? 1 : 0;
}
