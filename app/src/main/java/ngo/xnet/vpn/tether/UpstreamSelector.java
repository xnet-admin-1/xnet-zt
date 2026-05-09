package ngo.xnet.vpn.tether;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.util.Log;

import java.net.DatagramSocket;
import java.net.Socket;

/**
 * Monitors ConnectivityManager for the default internet-capable network and provides
 * socket binding to that network. This bypasses Android's DUN APN selection by binding
 * upstream sockets directly to the default data network (e.g., rmnet19 with IPv4)
 * rather than the DUN interface (e.g., rmnet16 with IPv6-only).
 *
 * Modeled after TetherFi's AndroidSocketBinder pattern:
 * ConnectivityManager.requestNetwork() with NET_CAPABILITY_INTERNET keeps the
 * preferred network alive and provides a Network object for per-socket binding.
 */
public class UpstreamSelector {
    private static final String TAG = "UpstreamSelector";

    private final ConnectivityManager cm;
    private volatile Network currentNetwork;
    private volatile NetworkCapabilities currentCaps;
    private ConnectivityManager.NetworkCallback networkCallback;
    private Listener listener;

    public interface Listener {
        void onUpstreamChanged(Network network);
        void onUpstreamLost();
    }

    public UpstreamSelector(Context context) {
        this.cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
    }

    public void setListener(Listener listener) { this.listener = listener; }

    /**
     * Start monitoring for the best internet-capable network.
     * Prefers cellular to avoid WiFi conflicts when device is acting as hotspot.
     */
    public void start() {
        if (networkCallback != null) return;

        NetworkRequest request = new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
                .build();

        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                Log.i(TAG, "Upstream available: " + network);
                currentNetwork = network;
                if (listener != null) listener.onUpstreamChanged(network);
            }

            @Override
            public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                currentNetwork = network;
                currentCaps = caps;
            }

            @Override
            public void onLost(Network network) {
                Log.w(TAG, "Upstream lost: " + network);
                if (network.equals(currentNetwork)) {
                    currentNetwork = null;
                    currentCaps = null;
                    if (listener != null) listener.onUpstreamLost();
                }
            }
        };

        cm.requestNetwork(request, networkCallback);
        Log.i(TAG, "Started upstream monitoring");
    }

    /**
     * Start with explicit cellular preference (use when device is WiFi hotspot).
     */
    public void startCellularPreferred() {
        if (networkCallback != null) return;

        NetworkRequest request = new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .build();

        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                Log.i(TAG, "Cellular upstream available: " + network);
                currentNetwork = network;
                if (listener != null) listener.onUpstreamChanged(network);
            }

            @Override
            public void onLost(Network network) {
                Log.w(TAG, "Cellular upstream lost: " + network);
                if (network.equals(currentNetwork)) {
                    currentNetwork = null;
                    if (listener != null) listener.onUpstreamLost();
                }
            }
        };

        cm.requestNetwork(request, networkCallback);
        Log.i(TAG, "Started cellular-preferred upstream monitoring");
    }

    public void stop() {
        if (networkCallback != null) {
            try { cm.unregisterNetworkCallback(networkCallback); }
            catch (Exception e) { Log.w(TAG, "Unregister error", e); }
            networkCallback = null;
        }
        currentNetwork = null;
        currentCaps = null;
        Log.i(TAG, "Stopped");
    }

    /** Get the current upstream network, or null if unavailable. */
    public Network getNetwork() { return currentNetwork; }

    /** Bind a TCP socket to the upstream network (bypasses VPN and DUN). */
    public boolean bindSocket(Socket socket) {
        Network net = currentNetwork;
        if (net == null) return false;
        try {
            net.bindSocket(socket);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Failed to bind socket", e);
            return false;
        }
    }

    /** Bind a UDP socket to the upstream network. */
    public boolean bindSocket(DatagramSocket socket) {
        Network net = currentNetwork;
        if (net == null) return false;
        try {
            net.bindSocket(socket);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Failed to bind datagram socket", e);
            return false;
        }
    }

    /** Bind a file descriptor to the upstream network. */
    public boolean bindSocket(int fd) {
        Network net = currentNetwork;
        if (net == null) return false;
        try {
            cm.bindProcessToNetwork(net);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Failed to bind process to network", e);
            return false;
        }
    }

    public boolean hasUpstream() { return currentNetwork != null; }
}
