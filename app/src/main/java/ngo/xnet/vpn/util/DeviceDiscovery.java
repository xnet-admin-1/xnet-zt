package ngo.xnet.vpn.util;

import android.util.Log;
import java.io.*;
import java.net.*;
import java.util.*;

public class DeviceDiscovery {
    private static final String TAG = "DeviceDiscovery";

    public static class Device {
        public String ip;
        public String mac;
        public Device(String ip, String mac) { this.ip = ip; this.mac = mac; }
    }

    public static List<Device> findTetheredDevices() {
        List<Device> devices = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        Log.w(TAG, "Starting device discovery");

        readArp(devices, seen);
        Log.w(TAG, "After ARP: " + devices.size() + " devices");

        readIpNeigh(devices, seen);
        Log.w(TAG, "After ip neigh: " + devices.size() + " devices");

        scanInterfaces(devices, seen);
        Log.w(TAG, "After iface scan: " + devices.size() + " devices");

        return devices;
    }

    private static void readArp(List<Device> devices, Set<String> seen) {
        try (BufferedReader br = new BufferedReader(new FileReader("/proc/net/arp"))) {
            String line;
            br.readLine();
            while ((line = br.readLine()) != null) {
                String[] p = line.split("\\s+");
                if (p.length >= 6 && !"00:00:00:00:00:00".equals(p[3]) && !p[0].startsWith("10.121.")) {
                    if (seen.add(p[0])) devices.add(new Device(p[0], p[3]));
                }
            }
        } catch (Exception e) { Log.w(TAG, "ARP failed: " + e); }
    }

    private static void readIpNeigh(List<Device> devices, Set<String> seen) {
        try {
            Process proc = Runtime.getRuntime().exec("ip neigh");
            try (BufferedReader br = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) {
                    Log.w(TAG, "ip neigh: " + line);
                    String[] p = line.split("\\s+");
                    if (p.length >= 5 && !p[0].startsWith("10.121.") && !line.contains("FAILED")) {
                        String mac = "unknown";
                        for (int i = 0; i < p.length - 1; i++) {
                            if ("lladdr".equals(p[i])) { mac = p[i + 1]; break; }
                        }
                        if (seen.add(p[0])) devices.add(new Device(p[0], mac));
                    }
                }
            }
            proc.waitFor();
        } catch (Exception e) { Log.w(TAG, "ip neigh failed: " + e); }
    }

    private static void scanInterfaces(List<Device> devices, Set<String> seen) {
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            while (ifaces.hasMoreElements()) {
                NetworkInterface ni = ifaces.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                String name = ni.getName();
                Log.w(TAG, "Interface: " + name);
                // Tether interfaces
                if (!name.startsWith("ncm") && !name.startsWith("rndis") && !name.startsWith("usb")
                    && !name.startsWith("swlan") && !name.equals("wlan1") && !name.startsWith("ap"))
                    continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress addr = ia.getAddress();
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        String base = addr.getHostAddress();
                        String prefix = base.substring(0, base.lastIndexOf('.') + 1);
                        Log.w(TAG, "Scanning " + prefix + "* on " + name + " (self=" + base + ")");
                        for (int i = 1; i < 30; i++) {
                            String target = prefix + i;
                            if (target.equals(base)) continue;
                            try {
                                Socket s = new Socket();
                                // Protect from VPN so traffic goes through real interface
                                var svc = ngo.xnet.vpn.service.ZeroTierOneService.getInstance();
                                if (svc != null) svc.protect(s);
                                s.bind(new InetSocketAddress(base, 0));
                                s.connect(new InetSocketAddress(target, 22), 200);
                                s.close();
                                if (seen.add(target)) devices.add(new Device(target, "via " + name));
                                Log.w(TAG, "Found: " + target);
                            } catch (ConnectException e) {
                                if (seen.add(target)) devices.add(new Device(target, "via " + name));
                                Log.w(TAG, "Found (refused): " + target);
                            } catch (Exception ignored) {}
                        }
                    }
                }
            }
        } catch (Exception e) { Log.w(TAG, "iface scan failed: " + e); }
    }
}
