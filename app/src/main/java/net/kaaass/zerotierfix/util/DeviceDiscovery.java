package net.kaaass.zerotierfix.util;

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
        // Read ARP table
        try (BufferedReader br = new BufferedReader(new FileReader("/proc/net/arp"))) {
            String line;
            br.readLine(); // skip header
            while ((line = br.readLine()) != null) {
                String[] parts = line.split("\\s+");
                if (parts.length >= 6 && !"00:00:00:00:00:00".equals(parts[3])) {
                    String ip = parts[0];
                    String mac = parts[3];
                    String iface = parts[5];
                    // Tether/hotspot interfaces: swlan0, wlan1, rndis0, usb0, ap0, etc.
                    if (!iface.equals("wlan0") && !ip.startsWith("10.121.")) {
                        devices.add(new Device(ip, mac));
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "ARP read failed: " + e);
        }
        // Also try neighbor scan on common tether subnets
        if (devices.isEmpty()) {
            for (String subnet : new String[]{"192.168.43.", "192.168.49.", "10.92.246."}) {
                try {
                    for (int i = 1; i < 30; i++) {
                        InetAddress.getByName(subnet + i).isReachable(100);
                    }
                } catch (Exception ignored) {}
            }
            // Re-read ARP after pings
            try (BufferedReader br = new BufferedReader(new FileReader("/proc/net/arp"))) {
                String line;
                br.readLine();
                while ((line = br.readLine()) != null) {
                    String[] parts = line.split("\\s+");
                    if (parts.length >= 6 && !"00:00:00:00:00:00".equals(parts[3])) {
                        String ip = parts[0];
                        String mac = parts[3];
                        String iface = parts[5];
                        if (!iface.equals("wlan0") && !ip.startsWith("10.121.")) {
                            boolean dup = false;
                            for (Device d : devices) if (d.ip.equals(ip)) { dup = true; break; }
                            if (!dup) devices.add(new Device(ip, mac));
                        }
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "ARP re-read failed: " + e);
            }
        }
        return devices;
    }
}
