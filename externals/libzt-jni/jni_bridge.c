/*
 * Copyright (c) 2025-present XNet Inc. Apache License 2.0
 * JNI bridge for libxnet.
 */
#include <jni.h>
#include <string.h>
#include <stdlib.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <android/log.h>
#include "xnet.h"

#define TAG "libxnet"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

static JavaVM *g_vm = NULL;
static jobject g_callback = NULL;
static jmethodID g_onEvent = NULL;
static jmethodID g_onAddress = NULL;
static char g_addr[64] = {0};
static uint64_t g_nwid = 0;

static void xnet_cb(int event, const void *arg) {
    LOGI("xnet event: %d", event);

    /* capture address */
    if (event == XNET_EV_ADDR_ADDED_IP4 && g_nwid) {
        struct sockaddr_storage ss;
        if (xnet_get_addr(g_nwid, AF_INET, &ss) == XNET_OK) {
            struct sockaddr_in *sin = (struct sockaddr_in *)&ss;
            inet_ntop(AF_INET, &sin->sin_addr, g_addr, sizeof(g_addr));
            LOGI("xnet address: %s", g_addr);
        }
    }

    if (!g_vm || !g_callback) return;
    JNIEnv *env;
    int attached = 0;
    if ((*g_vm)->GetEnv(g_vm, (void**)&env, JNI_VERSION_1_6) != JNI_OK) {
        (*g_vm)->AttachCurrentThread(g_vm, &env, NULL);
        attached = 1;
    }
    if (g_onEvent)
        (*env)->CallVoidMethod(env, g_callback, g_onEvent, (jint)event);
    if (event == XNET_EV_ADDR_ADDED_IP4 && g_addr[0] && g_onAddress) {
        jstring jaddr = (*env)->NewStringUTF(env, g_addr);
        (*env)->CallVoidMethod(env, g_callback, g_onAddress, jaddr);
    }
    if (attached) (*g_vm)->DetachCurrentThread(g_vm);
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    g_vm = vm;
    return JNI_VERSION_1_6;
}

JNIEXPORT jint JNICALL
Java_ngo_xnet_libzt_ZtSocket_start(JNIEnv *env, jclass clazz,
                                    jstring path, jobject callback, jint port) {
    g_callback = (*env)->NewGlobalRef(env, callback);
    jclass cls = (*env)->GetObjectClass(env, callback);
    g_onEvent = (*env)->GetMethodID(env, cls, "onEvent", "(I)V");
    g_onAddress = (*env)->GetMethodID(env, cls, "onAddress", "(Ljava/lang/String;)V");

    const char *cpath = (*env)->GetStringUTFChars(env, path, NULL);
    LOGI("xnet_start path=%s port=%d", cpath, (int)port);
    int rc = xnet_start(cpath, xnet_cb, (int)port);
    (*env)->ReleaseStringUTFChars(env, path, cpath);
    return rc;
}

JNIEXPORT jint JNICALL
Java_ngo_xnet_libzt_ZtSocket_stop(JNIEnv *env, jclass clazz) {
    return xnet_stop();
}

JNIEXPORT jint JNICALL
Java_ngo_xnet_libzt_ZtSocket_coreRunning(JNIEnv *env, jclass clazz) {
    return xnet_running();
}

JNIEXPORT jint JNICALL
Java_ngo_xnet_libzt_ZtSocket_join(JNIEnv *env, jclass clazz, jlong nwid) {
    g_nwid = (uint64_t)nwid;
    LOGI("xnet_join nwid=%llx", (unsigned long long)nwid);
    return xnet_join((uint64_t)nwid);
}

JNIEXPORT jint JNICALL
Java_ngo_xnet_libzt_ZtSocket_leave(JNIEnv *env, jclass clazz, jlong nwid) {
    return xnet_leave((uint64_t)nwid);
}

JNIEXPORT jlong JNICALL
Java_ngo_xnet_libzt_ZtSocket_getNodeId(JNIEnv *env, jclass clazz) {
    return (jlong)xnet_node_id();
}

JNIEXPORT jint JNICALL
Java_ngo_xnet_libzt_ZtSocket_hasAddress(JNIEnv *env, jclass clazz, jlong nwid) {
    return g_addr[0] != 0 ? 1 : 0;
}

JNIEXPORT jstring JNICALL
Java_ngo_xnet_libzt_ZtSocket_getAddress(JNIEnv *env, jclass clazz, jlong nwid) {
    return (*env)->NewStringUTF(env, g_addr);
}

JNIEXPORT jint JNICALL
Java_ngo_xnet_libzt_ZtSocket_socket(JNIEnv *env, jclass clazz,
                                     jint family, jint type, jint proto) {
    return xnet_socket(family, type, proto);
}

JNIEXPORT jint JNICALL
Java_ngo_xnet_libzt_ZtSocket_connect(JNIEnv *env, jclass clazz,
                                      jint fd, jstring addr, jint port) {
    const char *caddr = (*env)->GetStringUTFChars(env, addr, NULL);
    /* lwIP sockaddr_in layout: sin_len, sin_family, sin_port, sin_addr */
    uint8_t sa[16];
    memset(sa, 0, sizeof(sa));
    sa[0] = sizeof(sa);          /* sin_len */
    sa[1] = AF_INET;             /* sin_family */
    sa[2] = (port >> 8) & 0xff;  /* sin_port high */
    sa[3] = port & 0xff;         /* sin_port low */
    struct in_addr ia;
    inet_aton(caddr, &ia);
    memcpy(&sa[4], &ia, 4);
    (*env)->ReleaseStringUTFChars(env, addr, caddr);
    return xnet_connect(fd, (struct sockaddr *)sa, sizeof(sa));
}

JNIEXPORT jint JNICALL
Java_ngo_xnet_libzt_ZtSocket_bind(JNIEnv *env, jclass clazz,
                                   jint fd, jstring addr, jint port) {
    const char *caddr = (*env)->GetStringUTFChars(env, addr, NULL);
    uint8_t sa[16];
    memset(sa, 0, sizeof(sa));
    sa[0] = sizeof(sa);
    sa[1] = AF_INET;
    sa[2] = (port >> 8) & 0xff;
    sa[3] = port & 0xff;
    struct in_addr ia;
    inet_aton(caddr, &ia);
    memcpy(&sa[4], &ia, 4);
    (*env)->ReleaseStringUTFChars(env, addr, caddr);
    return xnet_bind(fd, (struct sockaddr *)sa, sizeof(sa));
}

JNIEXPORT jint JNICALL
Java_ngo_xnet_libzt_ZtSocket_listen(JNIEnv *env, jclass clazz, jint fd, jint backlog) {
    return xnet_listen(fd, backlog);
}

JNIEXPORT jint JNICALL
Java_ngo_xnet_libzt_ZtSocket_accept(JNIEnv *env, jclass clazz, jint fd) {
    struct sockaddr_in sa;
    socklen_t len = sizeof(sa);
    return xnet_accept(fd, (struct sockaddr *)&sa, &len);
}

JNIEXPORT jint JNICALL
Java_ngo_xnet_libzt_ZtSocket_send(JNIEnv *env, jclass clazz, jint fd, jbyteArray data) {
    jsize len = (*env)->GetArrayLength(env, data);
    jbyte *buf = (*env)->GetByteArrayElements(env, data, NULL);
    int rc = xnet_send(fd, buf, len, 0);
    (*env)->ReleaseByteArrayElements(env, data, buf, JNI_ABORT);
    return rc;
}

JNIEXPORT jbyteArray JNICALL
Java_ngo_xnet_libzt_ZtSocket_recv(JNIEnv *env, jclass clazz, jint fd, jint maxLen) {
    char *buf = (char *)malloc(maxLen);
    int n = xnet_recv(fd, buf, maxLen, 0);
    if (n <= 0) { free(buf); return NULL; }
    jbyteArray arr = (*env)->NewByteArray(env, n);
    (*env)->SetByteArrayRegion(env, arr, 0, n, (jbyte *)buf);
    free(buf);
    return arr;
}

JNIEXPORT jint JNICALL
Java_ngo_xnet_libzt_ZtSocket_closeSocket(JNIEnv *env, jclass clazz, jint fd) {
    return xnet_close(fd);
}

/* lwip sio stubs — not used on Android */
#include "lwip/sio.h"
sio_fd_t sio_open(u8_t devnum) { return NULL; }
u32_t sio_read(sio_fd_t fd, u8_t *data, u32_t len) { return 0; }
u32_t sio_tryread(sio_fd_t fd, u8_t *data, u32_t len) { return 0; }
void sio_send(u8_t c, sio_fd_t fd) {}
