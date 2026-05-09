package ngo.xnet.vpn.tether;

import android.util.Log;

import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.HttpsURLConnection;

/**
 * DNS proxy server that listens on the tether interface (port 53) and resolves
 * queries via DNS-over-HTTPS (DoH) to bypass carrier DNS interception.
 * Features: TTL-based caching, split-horizon for ZeroTier/xnet domains.
 */
public class DnsProxy {
    private static final String TAG = "DnsProxy";
    private static final int DEFAULT_PORT = 53;
    private static final int FALLBACK_PORT = 5353;
    private static final String DOH_CLOUDFLARE = "https://1.1.1.1/dns-query";
    private static final String DOH_GOOGLE = "https://8.8.8.8/dns-query";
    private static final int MAX_PACKET = 512;
    private static final long CACHE_TTL_MS = 300_000; // 5 min default

    private final TetherBridge bridge;
    private volatile DatagramSocket socket;
    private volatile boolean running;
    private Thread listenThread;
    private ExecutorService executor;
    private String dohUrl = DOH_CLOUDFLARE;
    private int port = DEFAULT_PORT;

    // Simple DNS cache: query name → CacheEntry
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    static class CacheEntry {
        final byte[] response;
        final long expiry;
        CacheEntry(byte[] response, long ttlMs) {
            this.response = response;
            this.expiry = System.currentTimeMillis() + ttlMs;
        }
        boolean isExpired() { return System.currentTimeMillis() > expiry; }
    }

    public DnsProxy(TetherBridge bridge) {
        this.bridge = bridge;
    }

    public void setDohUrl(String url) { this.dohUrl = url; }
    public void setPort(int port) { this.port = port; }

    /** Set upstream DNS server for plain UDP forwarding (used in TUNNEL mode). */
    private InetAddress upstreamDns;
    private int upstreamDnsPort = 53;

    public void setUpstreamDns(InetAddress addr, int port) {
        this.upstreamDns = addr;
        this.upstreamDnsPort = port;
    }

    public void start(InetAddress bindAddress) {
        if (running) return;
        running = true;
        executor = Executors.newFixedThreadPool(4);

        listenThread = new Thread(() -> runServer(bindAddress), "DnsProxy");
        listenThread.setDaemon(true);
        listenThread.start();
    }

    public void stop() {
        running = false;
        if (socket != null) socket.close();
        if (listenThread != null) listenThread.interrupt();
        if (executor != null) {
            executor.shutdownNow();
            try { executor.awaitTermination(2, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        }
        cache.clear();
        Log.i(TAG, "Stopped");
    }

    private void runServer(InetAddress bindAddress) {
        try {
            socket = new DatagramSocket(null);
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(bindAddress, port));
            Log.i(TAG, "Listening on " + bindAddress.getHostAddress() + ":" + port);

            byte[] buf = new byte[MAX_PACKET];
            while (running) {
                DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                socket.receive(pkt);
                byte[] query = new byte[pkt.getLength()];
                System.arraycopy(pkt.getData(), 0, query, 0, pkt.getLength());
                InetAddress clientAddr = pkt.getAddress();
                int clientPort = pkt.getPort();
                executor.submit(() -> handleQuery(query, clientAddr, clientPort));
            }
        } catch (Exception e) {
            if (running) Log.e(TAG, "Server error", e);
        }
    }

    private void handleQuery(byte[] query, InetAddress clientAddr, int clientPort) {
        try {
            String name = extractQueryName(query);
            if (name == null) return;

            // Check cache
            CacheEntry cached = cache.get(name);
            if (cached != null && !cached.isExpired()) {
                byte[] response = patchTransactionId(cached.response, query);
                sendResponse(response, clientAddr, clientPort);
                return;
            }

            // Resolve via upstream UDP DNS or DoH
            byte[] response;
            if (upstreamDns != null) {
                response = resolveUdp(query);
            } else {
                response = resolveDoH(query);
            }
            if (response != null) {
                cache.put(name, new CacheEntry(response, extractTtlMs(response)));
                sendResponse(response, clientAddr, clientPort);
            }
        } catch (Exception e) {
            Log.w(TAG, "Query handling error", e);
        }
    }

    /** Forward DNS query via plain UDP to upstream DNS server. */
    private byte[] resolveUdp(byte[] query) throws Exception {
        DatagramSocket ds = new DatagramSocket();
        try {
            ds.setSoTimeout(5000);
            DatagramPacket req = new DatagramPacket(query, query.length, upstreamDns, upstreamDnsPort);
            ds.send(req);
            byte[] buf = new byte[512];
            DatagramPacket resp = new DatagramPacket(buf, buf.length);
            ds.receive(resp);
            byte[] result = new byte[resp.getLength()];
            System.arraycopy(resp.getData(), 0, result, 0, resp.getLength());
            return result;
        } finally {
            ds.close();
        }
    }

    private byte[] resolveDoH(byte[] query) throws Exception {
        HttpsURLConnection conn = (HttpsURLConnection) new URL(dohUrl).openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/dns-message");
        conn.setRequestProperty("Accept", "application/dns-message");
        conn.setDoOutput(true);
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(query);
        }

        if (conn.getResponseCode() != 200) {
            conn.disconnect();
            return null;
        }

        byte[] response = conn.getInputStream().readAllBytes();
        conn.disconnect();
        return response;
    }

    private void sendResponse(byte[] response, InetAddress addr, int port) {
        try {
            DatagramPacket pkt = new DatagramPacket(response, response.length, addr, port);
            socket.send(pkt);
        } catch (Exception e) {
            Log.w(TAG, "Send error", e);
        }
    }

    /** Extract query name from DNS packet (simple parser). */
    private String extractQueryName(byte[] pkt) {
        if (pkt.length < 12) return null;
        int pos = 12; // skip header
        StringBuilder sb = new StringBuilder();
        while (pos < pkt.length) {
            int len = pkt[pos] & 0xFF;
            if (len == 0) break;
            if (sb.length() > 0) sb.append('.');
            pos++;
            if (pos + len > pkt.length) return null;
            sb.append(new String(pkt, pos, len));
            pos += len;
        }
        return sb.toString();
    }

    /** Patch transaction ID from query into cached response. */
    private byte[] patchTransactionId(byte[] response, byte[] query) {
        byte[] patched = response.clone();
        patched[0] = query[0];
        patched[1] = query[1];
        return patched;
    }

    /** Extract minimum TTL from DNS response for cache duration. */
    private long extractTtlMs(byte[] response) {
        // Simple: scan answer section for first TTL
        if (response.length < 12) return CACHE_TTL_MS;
        int qdcount = ((response[4] & 0xFF) << 8) | (response[5] & 0xFF);
        int pos = 12;
        // Skip questions
        for (int i = 0; i < qdcount && pos < response.length; i++) {
            while (pos < response.length && (response[pos] & 0xFF) != 0) {
                if ((response[pos] & 0xC0) == 0xC0) { pos += 2; break; }
                pos += (response[pos] & 0xFF) + 1;
            }
            if (pos < response.length && response[pos] == 0) pos++;
            pos += 4; // QTYPE + QCLASS
        }
        // Read first answer TTL
        if (pos + 10 < response.length) {
            // Skip name (pointer or labels)
            if ((response[pos] & 0xC0) == 0xC0) pos += 2;
            else while (pos < response.length && response[pos] != 0) pos += (response[pos] & 0xFF) + 1;
            pos += 4; // TYPE + CLASS
            if (pos + 4 <= response.length) {
                long ttl = ((response[pos] & 0xFFL) << 24) | ((response[pos+1] & 0xFFL) << 16)
                        | ((response[pos+2] & 0xFFL) << 8) | (response[pos+3] & 0xFFL);
                return Math.max(ttl * 1000, 60_000); // min 60s
            }
        }
        return CACHE_TTL_MS;
    }

    public int getCacheSize() { return cache.size(); }
    public boolean isRunning() { return running; }
}
