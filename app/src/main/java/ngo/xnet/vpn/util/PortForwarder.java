package ngo.xnet.vpn.util;

import android.net.VpnService;
import android.util.Log;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class PortForwarder {
    private static final String TAG = "PortForwarder";
    private static final Map<String, List<ServerSocket>> activeForwards = new ConcurrentHashMap<>();
    private static VpnService vpn;

    public static List<int[]> parsePorts(String spec) {
        List<int[]> ports = new ArrayList<>();
        if (spec == null || spec.trim().isEmpty()) return ports;
        for (String part : spec.split(",")) {
            part = part.trim();
            if (part.contains("-")) {
                String[] range = part.split("-", 2);
                int lo = Integer.parseInt(range[0].trim());
                int hi = Integer.parseInt(range[1].trim());
                for (int p = lo; p <= hi; p++) ports.add(new int[]{p, p});
            } else {
                int p = Integer.parseInt(part);
                ports.add(new int[]{p, p});
            }
        }
        return ports;
    }

    public static void startForDevice(String targetHost, String portSpec, VpnService vpnService) {
        stopForDevice(targetHost);
        vpn = vpnService;
        List<int[]> ports = parsePorts(portSpec);
        List<ServerSocket> servers = new ArrayList<>();
        for (int[] pp : ports) {
            int listenPort = pp[0];
            int targetPort = pp[1];
            try {
                ServerSocket ss = new ServerSocket(listenPort);
                servers.add(ss);
                Log.w(TAG, "Forwarding :" + listenPort + " → " + targetHost + ":" + targetPort);
                new Thread(() -> {
                    try {
                        while (!Thread.interrupted()) {
                            Socket in = ss.accept();
                            try {
                                Socket out = new Socket();
                                out.setReuseAddress(true);
                                out.bind(new InetSocketAddress(0));
                                if (vpn != null) vpn.protect(out);
                                out.connect(new InetSocketAddress(targetHost, targetPort), 5000);
                                out.setTcpNoDelay(true);
                                in.setTcpNoDelay(true);
                                pipe(in, out);
                                pipe(out, in);
                            } catch (Exception e) {
                                Log.w(TAG, "Forward failed: " + e.getMessage());
                                try { in.close(); } catch (Exception ignored) {}
                            }
                        }
                    } catch (Exception e) {
                        if (!ss.isClosed()) Log.e(TAG, "Server error: " + e.getMessage());
                    }
                }, "PF-" + targetHost + ":" + listenPort).start();
            } catch (Exception e) {
                Log.e(TAG, "Bind :" + listenPort + " failed: " + e.getMessage());
            }
        }
        activeForwards.put(targetHost, servers);
    }

    public static void stopForDevice(String targetHost) {
        List<ServerSocket> servers = activeForwards.remove(targetHost);
        if (servers != null) for (ServerSocket ss : servers) {
            try { ss.close(); } catch (Exception ignored) {}
        }
    }

    public static void stopAll() {
        for (String host : new ArrayList<>(activeForwards.keySet())) stopForDevice(host);
    }

    public static boolean isActive(String targetHost) {
        List<ServerSocket> servers = activeForwards.get(targetHost);
        return servers != null && !servers.isEmpty();
    }

    // Legacy single-target API
    public static void start(int listenPort, String targetHost, int targetPort, VpnService vpnService) {
        startForDevice(targetHost, String.valueOf(listenPort), vpnService);
    }

    public static void stop() { stopAll(); }

    private static void pipe(Socket from, Socket to) {
        new Thread(() -> {
            try {
                byte[] buf = new byte[8192];
                InputStream in = from.getInputStream();
                OutputStream out = to.getOutputStream();
                int n;
                while ((n = in.read(buf)) > 0) { out.write(buf, 0, n); out.flush(); }
            } catch (Exception ignored) {
            } finally {
                try { from.close(); } catch (Exception ignored) {}
                try { to.close(); } catch (Exception ignored) {}
            }
        }, "pipe").start();
    }
}
