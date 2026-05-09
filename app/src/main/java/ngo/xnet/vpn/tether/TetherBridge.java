package ngo.xnet.vpn.tether;

import android.content.Context;
import android.net.Network;
import android.net.VpnService;
import android.util.Log;

import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SocketChannel;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import ngo.xnet.vpn.util.RemoteLog;

/**
 * Orchestrates tether services: detects tether interfaces, selects upstream network,
 * and provides socket creation/binding for forwarding tethered traffic to the internet
 * via the default data network (bypassing DUN).
 *
 * Integrates with VpnService.protect() to prevent routing loops.
 */
public class TetherBridge implements TetherDetector.Listener, UpstreamSelector.Listener {
    private static final String TAG = "TetherBridge";

    public enum State { IDLE, DETECTING, ACTIVE, ERROR }

    /** Socket routing mode for proxy upstream connections. */
    public enum SocketMode {
        /** Route through VPN tunnel (no protect, no bind) — default. */
        TUNNEL,
        /** Bypass VPN, bind to upstream network (protect + bind). */
        BYPASS
    }

    private final Context context;
    private final TetherDetector detector;
    private final UpstreamSelector upstream;
    private volatile VpnService vpnService;
    private volatile State state = State.IDLE;
    private volatile SocketMode socketMode = SocketMode.TUNNEL;
    private final List<StateListener> stateListeners = new CopyOnWriteArrayList<>();

    public interface StateListener {
        void onStateChanged(State newState, List<TetherDetector.TetherInterface> interfaces);
    }

    public TetherBridge(Context context) {
        this.context = context.getApplicationContext();
        this.detector = new TetherDetector(context);
        this.upstream = new UpstreamSelector(context);
        this.detector.addListener(this);
        this.upstream.setListener(this);
    }

    public void setVpnService(VpnService vpnService) {
        this.vpnService = vpnService;
    }

    public void setSocketMode(SocketMode mode) {
        this.socketMode = mode;
        RemoteLog.log(TAG, "SocketMode: " + mode);
    }

    public SocketMode getSocketMode() { return socketMode; }

    public void start() {
        if (state != State.IDLE) return;
        setState(State.DETECTING);
        detector.start();
        upstream.start();
        Log.i(TAG, "Started");
    }

    public void stop() {
        detector.stop();
        upstream.stop();
        setState(State.IDLE);
        Log.i(TAG, "Stopped");
    }

    public State getState() { return state; }
    public TetherDetector getDetector() { return detector; }
    public UpstreamSelector getUpstream() { return upstream; }

    public void addStateListener(StateListener l) { stateListeners.add(l); }
    public void removeStateListener(StateListener l) { stateListeners.remove(l); }

    // --- TetherDetector.Listener ---

    @Override
    public void onTetherInterfacesChanged(List<TetherDetector.TetherInterface> interfaces) {
        if (interfaces.isEmpty()) {
            if (state == State.ACTIVE) setState(State.DETECTING);
            Log.i(TAG, "No tether interfaces detected");
        } else {
            // If we have upstream, go active; otherwise wait
            if (upstream.hasUpstream()) {
                setState(State.ACTIVE);
            }
            for (var iface : interfaces) {
                Log.i(TAG, "Tether interface: " + iface.name + " (" + iface.type + ") " + iface.address.getHostAddress());
            }
        }
    }

    // --- UpstreamSelector.Listener ---

    @Override
    public void onUpstreamChanged(Network network) {
        Log.i(TAG, "Upstream network changed: " + network);
        if (!detector.getActiveInterfaces().isEmpty()) {
            setState(State.ACTIVE);
        }
    }

    @Override
    public void onUpstreamLost() {
        Log.w(TAG, "Upstream lost");
        if (state == State.ACTIVE) setState(State.ERROR);
    }

    // --- Socket creation for proxy/forwarding use ---

    /**
     * Create a TCP socket for proxy upstream.
     * TUNNEL mode: ZT subnet destinations go unprotected (through VPN/mesh),
     *              internet destinations use protect+bind (bypass VPN to cellular).
     * BYPASS mode: always protect + bind to upstream network.
     */
    public Socket createUpstreamSocket() throws Exception {
        // In both modes, protect+bind for internet. The distinction happens at connect time.
        Socket socket = new Socket();
        protectSocket(socket);
        upstream.bindSocket(socket);
        return socket;
    }

    /**
     * Create and connect a TCP socket to the given destination.
     * In TUNNEL mode, ZT subnet destinations use an unprotected socket (routed through mesh).
     */
    public Socket connectUpstream(InetAddress host, int port) throws Exception {
        Socket socket;
        if (socketMode == SocketMode.TUNNEL && isZtSubnet(host)) {
            // ZT mesh destination — don't protect, let VPN route to mesh peer
            socket = new Socket();
        } else {
            // Internet destination — protect + bind to upstream (bypass VPN)
            socket = new Socket();
            protectSocket(socket);
            upstream.bindSocket(socket);
        }
        socket.connect(new InetSocketAddress(host, port), 10000);
        return socket;
    }

    /**
     * Create a UDP socket for proxy upstream.
     */
    public DatagramSocket createUpstreamDatagramSocket() throws Exception {
        DatagramSocket socket = new DatagramSocket(null);
        protectSocket(socket);
        upstream.bindSocket(socket);
        return socket;
    }

    /** Check if an address belongs to a ZeroTier managed subnet. */
    private boolean isZtSubnet(InetAddress addr) {
        byte[] b = addr.getAddress();
        if (b == null || b.length != 4) return false;
        for (var entry : ztRoutes) {
            if (isInSubnet(b, entry.address, entry.prefix)) return true;
        }
        return false;
    }

    /** ZT route entries for split-horizon routing. */
    private final List<ZtRoute> ztRoutes = new CopyOnWriteArrayList<>();

    public static class ZtRoute {
        public final byte[] address;
        public final int prefix;
        public ZtRoute(byte[] address, int prefix) {
            this.address = address;
            this.prefix = prefix;
        }
    }

    /** Called by ZeroTierOneService when VPN routes are configured. */
    public void setZtRoutes(List<ZtRoute> routes) {
        ztRoutes.clear();
        ztRoutes.addAll(routes);
        RemoteLog.log(TAG, "ZT routes set: " + routes.size());
    }

    /**
     * Protect a socket from VPN routing loop using VpnService.protect().
     */
    private void protectSocket(Socket socket) {
        VpnService svc = vpnService;
        if (svc != null) svc.protect(socket);
    }

    private void protectSocket(DatagramSocket socket) {
        VpnService svc = vpnService;
        if (svc != null) svc.protect(socket);
    }

    /**
     * Check if a given IP belongs to a known tether subnet.
     */
    public boolean isTetherSubnetAddress(byte[] ipv4Bytes) {
        for (var iface : detector.getActiveInterfaces()) {
            if (isInSubnet(ipv4Bytes, iface.address.getAddress(), iface.prefixLength)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isInSubnet(byte[] addr, byte[] subnet, int prefix) {
        int mask = prefix == 0 ? 0 : (0xFFFFFFFF << (32 - prefix));
        int a = ByteBuffer.wrap(addr).getInt() & mask;
        int s = ByteBuffer.wrap(subnet).getInt() & mask;
        return a == s;
    }

    private void setState(State newState) {
        if (state == newState) return;
        state = newState;
        RemoteLog.log(TAG, "State: " + newState);
        var interfaces = detector.getActiveInterfaces();
        for (StateListener l : stateListeners) {
            try { l.onStateChanged(newState, interfaces); }
            catch (Exception e) { Log.w(TAG, "StateListener error", e); }
        }
    }

    /** Total bytes transferred across all proxy services. */
    public long getTotalBytesTransferred() {
        // Proxies are managed by ZeroTierOneService, not directly here.
        // This is a placeholder — actual aggregation done in service.
        return 0;
    }
}
