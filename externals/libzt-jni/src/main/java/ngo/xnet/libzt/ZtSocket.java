package ngo.xnet.libzt;

public class ZtSocket {
    static { System.loadLibrary("zt-jni"); }

    public interface EventCallback {
        void onEvent(int eventCode);
        void onAddress(String address);
    }

    // ZT event codes
    public static final int ZTS_EVENT_NODE_UP = 0;
    public static final int ZTS_EVENT_NODE_ONLINE = 2;
    public static final int ZTS_EVENT_NETWORK_ACCESS_DENIED = 36;
    public static final int ZTS_EVENT_NETWORK_READY_IP4 = 37;
    public static final int ZTS_EVENT_STACK_UP = 48;

    public static native int start(String path, EventCallback callback, int port);
    public static native int stop();
    public static native int coreRunning();
    public static native int join(long nwid);
    public static native int leave(long nwid);
    public static native long getNodeId();
    public static native int hasAddress(long nwid);
    public static native String getAddress(long nwid);
    public static native int socket(int family, int type, int protocol);
    public static native int connect(int fd, String addr, int port);
    public static native int bind(int fd, String addr, int port);
    public static native int listen(int fd, int backlog);
    public static native int accept(int fd);
    public static native int send(int fd, byte[] data);
    public static native byte[] recv(int fd, int maxLen);
    public static native int closeSocket(int fd);

    // Constants
    public static final int AF_INET = 2;
    public static final int SOCK_STREAM = 1;
    public static final int SOCK_DGRAM = 2;
}
