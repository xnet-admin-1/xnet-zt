package ngo.xnet.vpn.util;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.LinkAddress;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.util.Log;
import java.io.*;
import java.net.*;
import java.util.*;

public class DeviceDiscovery {
    private static final String TAG = "DeviceDiscovery";
    // Common Android tether subnets when we can't detect the real one
    private static final String[] FALLBACK_SUBNETS = {
        "192.168.42.", "192.168.43.", "192.168.44.", "192.168.49.",
        "10.92.246.", "172.20.10."
    };

    public static class Device {
        public String ip;
        public String name;
        public Device(String ip, String name) { this.ip = ip; this.name = name; }
    }

    private static FileWriter logFile;
    private static Network tetherNetwork;

    private static void log(String msg) {
        Log.i(TAG, msg);
        RemoteLog.log(TAG, msg);
        try {
            if (logFile != null) { logFile.write(msg + "\n"); logFile.flush(); }
        } catch (Exception ignored) {}
    }

    public static List<Device> findTetheredDevices(Context ctx) {
        List<Device> devices = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        try {
            logFile = new FileWriter(new File(ctx.getFilesDir(), "discovery.log"));
        } catch (Exception ignored) {}

        log("=== scan start ===");

        // Find tether interface IPs via ConnectivityManager
        List<String> tetherIps = findTetherIps(ctx);
        log("Tether IPs: " + tetherIps);

        // If none found, try all known tether subnets
        if (tetherIps.isEmpty()) {
            log("No tether IPs from CM, trying Java NetworkInterface");
            tetherIps = findTetherIpsJava();
            log("Java tether IPs: " + tetherIps);
        }

        if (tetherIps.isEmpty()) {
            log("No tether IPs found, trying fallback subnets");
            for (String subnet : FALLBACK_SUBNETS) {
                tetherIps.add(subnet + "1"); // gateway is usually .1
            }
        }

        // Probe each tether subnet
        for (String selfIp : tetherIps) {
            probeSubnet(selfIp, devices, seen);
        }

        log("Total devices found: " + devices.size());
        try { if (logFile != null) logFile.close(); } catch (Exception ignored) {}
        return devices;
    }

    /** Use ConnectivityManager to find non-VPN, non-cellular local IPs */
    private static List<String> findTetherIps(Context ctx) {
        List<String> ips = new ArrayList<>();
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            for (Network net : cm.getAllNetworks()) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(net);
                if (caps == null) continue;
                // Skip VPN and internet-facing networks
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue;
                LinkProperties lp = cm.getLinkProperties(net);
                if (lp == null) continue;
                String iface = lp.getInterfaceName();
                log("CM network: " + iface + " caps=" + caps);
                for (LinkAddress la : lp.getLinkAddresses()) {
                    InetAddress addr = la.getAddress();
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        String ip = addr.getHostAddress();
                        // Tether IPs are private, non-ZT
                        if (!ip.startsWith("10.121.") && isPrivate(ip)) {
                            log("Tether candidate: " + ip + " on " + iface);
                            tetherNetwork = net;
                            ips.add(ip);
                        }
                    }
                }
            }
        } catch (Exception e) { log("CM scan failed: " + e); }
        return ips;
    }

    /** Fallback: Java NetworkInterface (limited on Android 11+) */
    private static List<String> findTetherIpsJava() {
        List<String> ips = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            while (ifaces.hasMoreElements()) {
                NetworkInterface ni = ifaces.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress addr = ia.getAddress();
                    if (addr instanceof Inet4Address) {
                        String ip = addr.getHostAddress();
                        if (!ip.startsWith("10.121.") && isPrivate(ip)) {
                            log("Java tether candidate: " + ip + " on " + ni.getName());
                            ips.add(ip);
                        }
                    }
                }
            }
        } catch (Exception e) { log("Java iface scan failed: " + e); }
        return ips;
    }

    private static boolean isPrivate(String ip) {
        return ip.startsWith("10.") || ip.startsWith("172.") || ip.startsWith("192.168.");
    }

    /** Parallel TCP connect probe to find live hosts */
    private static void probeSubnet(String selfIp, List<Device> devices, Set<String> seen) {
        String prefix = selfIp.substring(0, selfIp.lastIndexOf('.') + 1);
        log("Probing " + prefix + "* (self=" + selfIp + ")");

        // Find the Network object for ncm0 so we can bind sockets to it
        final Network tetherNet = tetherNetwork;

        List<Thread> threads = new ArrayList<>();
        for (int i = 1; i <= 30; i++) {
            String target = prefix + i;
            if (target.equals(selfIp)) continue;
            Thread t = new Thread(() -> {
                try {
                    Socket s = new Socket();
                    if (tetherNet != null) {
                        tetherNet.bindSocket(s);
                    } else {
                        var svc = ngo.xnet.vpn.service.ZeroTierOneService.getInstance();
                        if (svc != null) svc.protect(s);
                    }
                    s.connect(new InetSocketAddress(target, 22), 500);
                    s.close();
                    synchronized (devices) {
                        if (seen.add(target)) {
                            devices.add(new Device(target, "tethered"));
                            log("Found: " + target);
                        }
                    }
                } catch (ConnectException e) {
                    synchronized (devices) {
                        if (seen.add(target)) {
                            devices.add(new Device(target, "tethered"));
                            log("Found (refused): " + target);
                        }
                    }
                } catch (Exception e) {
                    log(target + ": " + e.getClass().getSimpleName());
                }
            });
            threads.add(t);
            t.start();
        }
        for (Thread t : threads) {
            try { t.join(3000); } catch (InterruptedException ignored) {}
        }
    }
}
