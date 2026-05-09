package ngo.xnet.vpn.tether;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.util.Log;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Detects active tethering interfaces (USB/WiFi/BT/Ethernet) by enumerating
 * NetworkInterfaces for known tether interface name prefixes and private subnets.
 * Runs a polling thread since TetheringManager callbacks require system permissions.
 */
public class TetherDetector {
    private static final String TAG = "TetherDetector";
    private static final String[] USB_PREFIXES = {"rndis", "ncm", "usb"};
    private static final String[] WIFI_PREFIXES = {"wlan-hotspot", "ap0", "swlan"};
    private static final String[] BT_PREFIXES = {"bt-pan", "bnep"};
    private static final String[] ETH_PREFIXES = {"eth"};
    private static final long POLL_INTERVAL_MS = 3000;

    private final Context context;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private volatile Thread pollThread;
    private volatile boolean running;
    private final List<TetherInterface> activeInterfaces = new CopyOnWriteArrayList<>();

    public enum TetherType { USB, WIFI, BLUETOOTH, ETHERNET }

    public static class TetherInterface {
        public final String name;
        public final TetherType type;
        public final InetAddress address;
        public final int prefixLength;

        public TetherInterface(String name, TetherType type, InetAddress address, int prefixLength) {
            this.name = name;
            this.type = type;
            this.address = address;
            this.prefixLength = prefixLength;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof TetherInterface)) return false;
            return name.equals(((TetherInterface) o).name);
        }

        @Override
        public int hashCode() { return name.hashCode(); }
    }

    public interface Listener {
        void onTetherInterfacesChanged(List<TetherInterface> interfaces);
    }

    public TetherDetector(Context context) {
        this.context = context.getApplicationContext();
    }

    public void addListener(Listener listener) { listeners.add(listener); }
    public void removeListener(Listener listener) { listeners.remove(listener); }

    public List<TetherInterface> getActiveInterfaces() {
        return Collections.unmodifiableList(activeInterfaces);
    }

    public void start() {
        if (running) return;
        running = true;
        pollThread = new Thread(this::pollLoop, "TetherDetector");
        pollThread.setDaemon(true);
        pollThread.start();
        Log.i(TAG, "Started");
    }

    public void stop() {
        running = false;
        if (pollThread != null) {
            pollThread.interrupt();
            pollThread = null;
        }
        activeInterfaces.clear();
        Log.i(TAG, "Stopped");
    }

    private void pollLoop() {
        while (running && !Thread.interrupted()) {
            try {
                List<TetherInterface> detected = detectTetherInterfaces();
                if (!detected.equals(new ArrayList<>(activeInterfaces))) {
                    activeInterfaces.clear();
                    activeInterfaces.addAll(detected);
                    for (Listener l : listeners) {
                        try { l.onTetherInterfacesChanged(detected); }
                        catch (Exception e) { Log.w(TAG, "Listener error", e); }
                    }
                }
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                break;
            } catch (Exception e) {
                Log.w(TAG, "Poll error", e);
            }
        }
    }

    private List<TetherInterface> detectTetherInterfaces() {
        List<TetherInterface> result = new ArrayList<>();
        try {
            var interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) return result;
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (!ni.isUp()) continue;
                String name = ni.getName();
                TetherType type = classifyInterface(name);
                if (type == null) continue;

                // Find IPv4 address in private range
                var addrs = ni.getInterfaceAddresses();
                for (var ifAddr : addrs) {
                    InetAddress addr = ifAddr.getAddress();
                    if (addr instanceof Inet4Address && isPrivateAddress(addr)) {
                        result.add(new TetherInterface(name, type, addr, ifAddr.getNetworkPrefixLength()));
                        break;
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Error enumerating interfaces", e);
        }
        return result;
    }

    private static TetherType classifyInterface(String name) {
        for (String p : USB_PREFIXES) if (name.startsWith(p)) return TetherType.USB;
        for (String p : WIFI_PREFIXES) if (name.startsWith(p)) return TetherType.WIFI;
        for (String p : BT_PREFIXES) if (name.startsWith(p)) return TetherType.BLUETOOTH;
        for (String p : ETH_PREFIXES) if (name.startsWith(p)) return TetherType.ETHERNET;
        return null;
    }

    private static boolean isPrivateAddress(InetAddress addr) {
        byte[] b = addr.getAddress();
        int first = b[0] & 0xFF;
        int second = b[1] & 0xFF;
        return first == 10
                || (first == 172 && second >= 16 && second <= 31)
                || (first == 192 && second == 168);
    }
}
