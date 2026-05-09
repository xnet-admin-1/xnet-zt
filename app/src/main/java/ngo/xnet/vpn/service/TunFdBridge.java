package ngo.xnet.vpn.service;

import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

import ngo.xnet.vpn.util.RemoteLog;

/**
 * IPC bridge that passes the VPN TUN file descriptor to the :tether process.
 * The :tether process writes IP packets directly to the TUN fd, which the
 * ZeroTier node in the main process reads and routes through the mesh.
 *
 * This bypasses Android's UID-based VPN exclusion — writing to the TUN fd
 * is direct kernel injection, not subject to routing policy.
 */
public class TunFdBridge {
    private static final String TAG = "TunFdBridge";
    private static final String SOCKET_NAME = "ngo.xnet.vpn.tunfd";

    private LocalServerSocket server;
    private Thread acceptThread;
    private volatile boolean running;
    private ParcelFileDescriptor tunFd;

    /** Start the server in the VPN process. Passes TUN fd to connecting clients. */
    public void startServer(ParcelFileDescriptor tunFd) {
        this.tunFd = tunFd;
        if (running) return;
        running = true;
        try {
            server = new LocalServerSocket(SOCKET_NAME);
        } catch (IOException e) {
            Log.e(TAG, "Failed to create server socket", e);
            return;
        }
        acceptThread = new Thread(() -> {
            while (running) {
                try {
                    LocalSocket client = server.accept();
                    // Send the TUN fd to the client process
                    FileDescriptor[] fds = new FileDescriptor[]{tunFd.getFileDescriptor()};
                    client.setFileDescriptorsForSend(fds);
                    // Send a dummy byte to trigger fd transfer
                    client.getOutputStream().write(1);
                    client.getOutputStream().flush();
                    client.close();
                    RemoteLog.log(TAG, "TUN fd sent to client");
                } catch (Exception e) {
                    if (running) Log.w(TAG, "Accept error", e);
                }
            }
        }, "TunFdBridge");
        acceptThread.setDaemon(true);
        acceptThread.start();
        RemoteLog.log(TAG, "Server started");
    }

    public void stop() {
        running = false;
        try { if (server != null) server.close(); } catch (Exception ignored) {}
        if (acceptThread != null) acceptThread.interrupt();
    }

    /** Connect from the :tether process and receive the TUN fd. Returns read/write streams. */
    public static ParcelFileDescriptor receiveTunFd() {
        try {
            LocalSocket socket = new LocalSocket();
            socket.connect(new LocalSocketAddress(SOCKET_NAME));
            // Read dummy byte to receive fd
            socket.getInputStream().read();
            FileDescriptor[] fds = socket.getAncillaryFileDescriptors();
            socket.close();
            if (fds != null && fds.length > 0) {
                return ParcelFileDescriptor.dup(fds[0]);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to receive TUN fd", e);
        }
        return null;
    }
}
