package ngo.xnet.vpn.util;

import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.VpnService;
import android.content.Context;
import android.util.Log;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class PortForwarder {
    private static final String TAG = "PortForwarder";
    private static final Map<String, List<ServerSocket>> activeForwards = new ConcurrentHashMap<>();
    private static VpnService vpn;
    private static Network tetherNet;

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
        tetherNet = findTetherNetwork(vpnService);
        RemoteLog.log(TAG, "tetherNet=" + tetherNet + " for " + targetHost);
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
                                if (tetherNet != null) {
                                    tetherNet.bindSocket(out);
                                } else if (vpn != null) {
                                    vpn.protect(out);
                                }
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

    private static Network findTetherNetwork(Context ctx) {
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            for (Network net : cm.getAllNetworks()) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(net);
                if (caps == null || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue;
                LinkProperties lp = cm.getLinkProperties(net);
                if (lp == null) continue;
                for (LinkAddress la : lp.getLinkAddresses()) {
                    InetAddress addr = la.getAddress();
                    if (addr instanceof Inet4Address) {
                        String ip = addr.getHostAddress();
                        if (!ip.startsWith("10.121.") && (ip.startsWith("10.") || ip.startsWith("192.168.") || ip.startsWith("172."))) {
                            return net;
                        }
                    }
                }
            }
        } catch (Exception e) { Log.w(TAG, "findTetherNetwork: " + e); }
        return null;
    }

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
