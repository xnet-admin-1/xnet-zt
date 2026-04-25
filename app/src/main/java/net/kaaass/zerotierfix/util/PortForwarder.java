package net.kaaass.zerotierfix.util;

import android.net.VpnService;
import android.util.Log;
import java.io.*;
import java.net.*;

public class PortForwarder {
    private static final String TAG = "PortForwarder";
    private static ServerSocket server;
    private static VpnService vpn;

    public static void start(int listenPort, String targetHost, int targetPort, VpnService vpnService) {
        if (server != null) return;
        vpn = vpnService;
        new Thread(() -> {
            try {
                server = new ServerSocket(listenPort);
                Log.w(TAG, "Forwarding :" + listenPort + " → " + targetHost + ":" + targetPort);
                RemoteLog.log(TAG, "Forwarding :" + listenPort + " → " + targetHost + ":" + targetPort);
                while (!Thread.interrupted()) {
                    Socket in = server.accept();
                    try {
                        Socket out = new Socket();
                        if (vpn != null) {
                            boolean ok = vpn.protect(out);
                            RemoteLog.log(TAG, "protect=" + ok);
                        }
                        out.connect(new InetSocketAddress(targetHost, targetPort), 5000);
                        RemoteLog.log(TAG, "connected to " + targetHost + ":" + targetPort);
                        pipe(in, out);
                        pipe(out, in);
                    } catch (Exception e) {
                        RemoteLog.log(TAG, "Forward failed: " + e.getMessage());
                        Log.w(TAG, "Forward failed: " + e.getMessage());
                        try { in.close(); } catch (Exception ignored) {}
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Server error: " + e.getMessage());
            }
        }, "PortForwarder").start();
    }

    private static void pipe(Socket from, Socket to) {
        new Thread(() -> {
            try {
                byte[] buf = new byte[8192];
                InputStream in = from.getInputStream();
                OutputStream out = to.getOutputStream();
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    out.flush();
                }
            } catch (Exception ignored) {
            } finally {
                try { from.close(); } catch (Exception ignored) {}
                try { to.close(); } catch (Exception ignored) {}
            }
        }, "pipe").start();
    }

    public static void stop() {
        try { if (server != null) server.close(); } catch (Exception ignored) {}
        server = null;
    }
}
