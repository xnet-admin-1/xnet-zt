package ngo.xnet.vpn.tether;

import android.content.Context;
import android.net.Network;
import android.net.VpnService;
import android.util.Log;

import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.io.IOException;
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
    /** Upstream SOCKS5 proxy for TUNNEL mode (exit node). */
    private InetAddress upstreamSocksAddr;
    private int upstreamSocksPort;

    /** Set the upstream SOCKS5 proxy (ZT exit node) for TUNNEL mode. */
    public void setUpstreamSocks(InetAddress addr, int port) {
        this.upstreamSocksAddr = addr;
        this.upstreamSocksPort = port;
        RemoteLog.log(TAG, "Upstream SOCKS: " + addr.getHostAddress() + ":" + port);
    }

    /**
     * TUNNEL mode (route-via-ZT=true): ALL traffic goes through VPN tunnel unprotected.
     * BYPASS mode (route-via-ZT=false): split horizon — ZT subnets through tunnel,
     *              internet via protect+bind to upstream.
     */
    public Socket createUpstreamSocket() throws Exception {
        Socket socket = new Socket();
        if (socketMode == SocketMode.BYPASS) {
            protectSocket(socket);
            upstream.bindSocket(socket);
        }
        return socket;
    }

    /**
     * Create and connect a TCP socket to the given destination.
     * TUNNEL: chain through upstream SOCKS5 on exit node.
     * BYPASS: ZT subnets unprotected, everything else protect+bind.
     */
    public Socket connectUpstream(InetAddress host, int port) throws Exception {
        if (socketMode == SocketMode.TUNNEL && upstreamSocksAddr != null) {
            // Chain through exit node SOCKS5
            return connectViaSocks5(host, port);
        } else if (socketMode == SocketMode.BYPASS && !isZtSubnet(host)) {
            Socket socket = new Socket();
            protectSocket(socket);
            upstream.bindSocket(socket);
            socket.connect(new InetSocketAddress(host, port), 10000);
            return socket;
        } else {
            // Direct connection (ZT subnet in BYPASS mode, or fallback)
            Socket socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), 10000);
            return socket;
        }
    }

    /** Connect to destination via the upstream SOCKS5 proxy (RFC 1928). */
    private Socket connectViaSocks5(InetAddress host, int port) throws Exception {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(upstreamSocksAddr, upstreamSocksPort), 10000);

        var out = socket.getOutputStream();
        var in = socket.getInputStream();

        // SOCKS5 greeting: version=5, 1 method, no-auth
        out.write(new byte[]{0x05, 0x01, 0x00});
        out.flush();

        // Server response: version, method
        byte[] resp = new byte[2];
        readFully(in, resp);
        if (resp[0] != 0x05 || resp[1] != 0x00) {
            socket.close();
            throw new IOException("SOCKS5 auth rejected");
        }

        // CONNECT request
        byte[] addr = host.getAddress();
        byte[] req = new byte[4 + addr.length + 2];
        req[0] = 0x05; // version
        req[1] = 0x01; // CONNECT
        req[2] = 0x00; // reserved
        req[3] = (addr.length == 4) ? (byte) 0x01 : (byte) 0x04; // IPv4 or IPv6
        System.arraycopy(addr, 0, req, 4, addr.length);
        req[req.length - 2] = (byte) ((port >> 8) & 0xFF);
        req[req.length - 1] = (byte) (port & 0xFF);
        out.write(req);
        out.flush();

        // CONNECT response
        byte[] connResp = new byte[4];
        readFully(in, connResp);
        if (connResp[1] != 0x00) {
            socket.close();
            throw new IOException("SOCKS5 CONNECT failed: " + (connResp[1] & 0xFF));
        }
        // Skip bound address
        if (connResp[3] == 0x01) { in.skip(4 + 2); }
        else if (connResp[3] == 0x04) { in.skip(16 + 2); }
        else if (connResp[3] == 0x03) { int len = in.read(); in.skip(len + 2); }

        return socket;
    }

    private static void readFully(java.io.InputStream in, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n < 0) throw new IOException("EOF");
            off += n;
        }
    }

    /**
     * Create a UDP socket for proxy upstream.
     * TUNNEL: unprotected (through VPN). BYPASS: protect+bind.
     */
    public DatagramSocket createUpstreamDatagramSocket() throws Exception {
        DatagramSocket socket = new DatagramSocket(null);
        if (socketMode == SocketMode.BYPASS) {
            protectSocket(socket);
            upstream.bindSocket(socket);
        }
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
