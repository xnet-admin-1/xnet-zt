package ngo.xnet.vpn.tether;

import android.util.Log;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Userspace NAT engine for tethered traffic flowing through the VPN tunnel.
 * 
 * Responsibilities:
 * 1. Identify packets from tether subnets (192.168.42.0/24, 192.168.49.0/24, etc.)
 * 2. Fix TTL to 64 on outbound packets (carrier bypass — prevents TTL-based tether detection)
 * 3. Track connections for stats reporting
 * 4. Classify packets as tether vs mesh vs local
 */
public class NatEngine {
    private static final String TAG = "NatEngine";
    private static final int DEFAULT_TTL = 64;

    // IPv4 header offsets
    private static final int IPV4_TTL_OFFSET = 8;
    private static final int IPV4_CHECKSUM_OFFSET = 10;
    private static final int IPV4_SRC_OFFSET = 12;
    private static final int IPV4_DST_OFFSET = 16;
    private static final int IPV4_PROTOCOL_OFFSET = 9;

    private final TetherBridge bridge;
    private volatile boolean ttlFixEnabled = true;
    private final AtomicLong bytesForwarded = new AtomicLong();
    private final AtomicLong packetsForwarded = new AtomicLong();
    private final Map<Integer, ConnectionEntry> connectionTable = new ConcurrentHashMap<>();

    static class ConnectionEntry {
        final byte[] srcAddr;
        final byte[] dstAddr;
        final int srcPort;
        final int dstPort;
        final int protocol;
        long lastSeen;
        long bytesOut;
        long bytesIn;

        ConnectionEntry(byte[] src, byte[] dst, int srcPort, int dstPort, int protocol) {
            this.srcAddr = src;
            this.dstAddr = dst;
            this.srcPort = srcPort;
            this.dstPort = dstPort;
            this.protocol = protocol;
            this.lastSeen = System.currentTimeMillis();
        }
    }

    public NatEngine(TetherBridge bridge) {
        this.bridge = bridge;
    }

    public void setTtlFixEnabled(boolean enabled) { this.ttlFixEnabled = enabled; }
    public boolean isTtlFixEnabled() { return ttlFixEnabled; }

    /**
     * Process an outbound IPv4 packet from the TUN interface.
     * Returns true if this packet is from a tether subnet and was processed.
     */
    public boolean processOutbound(byte[] packet, int length) {
        if (length < 20) return false;
        int version = (packet[0] >> 4) & 0xF;
        if (version != 4) return false;

        // Extract source IP
        byte[] srcIp = new byte[4];
        System.arraycopy(packet, IPV4_SRC_OFFSET, srcIp, 0, 4);

        // Check if source is from a tether subnet
        if (!bridge.isTetherSubnetAddress(srcIp)) return false;

        // This is tethered traffic — fix TTL and track
        if (ttlFixEnabled) {
            fixTtl(packet);
        }

        trackConnection(packet, length, true);
        packetsForwarded.incrementAndGet();
        bytesForwarded.addAndGet(length);
        return true;
    }

    /**
     * Process an inbound IPv4 packet destined for a tether client.
     * Returns true if this packet is destined for a tether subnet.
     */
    public boolean processInbound(byte[] packet, int length) {
        if (length < 20) return false;
        int version = (packet[0] >> 4) & 0xF;
        if (version != 4) return false;

        byte[] dstIp = new byte[4];
        System.arraycopy(packet, IPV4_DST_OFFSET, dstIp, 0, 4);

        if (!bridge.isTetherSubnetAddress(dstIp)) return false;

        trackConnection(packet, length, false);
        bytesForwarded.addAndGet(length);
        return true;
    }

    /**
     * Fix TTL to 64 to prevent carrier tether detection.
     * Recalculates IPv4 header checksum after modification.
     */
    private void fixTtl(byte[] packet) {
        int oldTtl = packet[IPV4_TTL_OFFSET] & 0xFF;
        if (oldTtl == DEFAULT_TTL) return;

        packet[IPV4_TTL_OFFSET] = (byte) DEFAULT_TTL;
        // Incremental checksum update (RFC 1624)
        int oldChecksum = ((packet[IPV4_CHECKSUM_OFFSET] & 0xFF) << 8)
                | (packet[IPV4_CHECKSUM_OFFSET + 1] & 0xFF);
        int diff = oldTtl - DEFAULT_TTL;
        // TTL is in the high byte of its 16-bit word
        int newChecksum = oldChecksum + (diff << 8);
        // Fold carry
        newChecksum = (newChecksum & 0xFFFF) + (newChecksum >> 16);
        newChecksum = (newChecksum & 0xFFFF) + (newChecksum >> 16);
        packet[IPV4_CHECKSUM_OFFSET] = (byte) ((newChecksum >> 8) & 0xFF);
        packet[IPV4_CHECKSUM_OFFSET + 1] = (byte) (newChecksum & 0xFF);
    }

    private void trackConnection(byte[] packet, int length, boolean outbound) {
        int ihl = (packet[0] & 0x0F) * 4;
        if (length < ihl + 4) return;
        int protocol = packet[IPV4_PROTOCOL_OFFSET] & 0xFF;
        // Only track TCP(6) and UDP(17)
        if (protocol != 6 && protocol != 17) return;

        int srcPort = ((packet[ihl] & 0xFF) << 8) | (packet[ihl + 1] & 0xFF);
        int dstPort = ((packet[ihl + 2] & 0xFF) << 8) | (packet[ihl + 3] & 0xFF);

        int key = hashConnection(packet, ihl, srcPort, dstPort, protocol);
        ConnectionEntry entry = connectionTable.get(key);
        if (entry == null) {
            byte[] src = new byte[4], dst = new byte[4];
            System.arraycopy(packet, IPV4_SRC_OFFSET, src, 0, 4);
            System.arraycopy(packet, IPV4_DST_OFFSET, dst, 0, 4);
            entry = new ConnectionEntry(src, dst, srcPort, dstPort, protocol);
            connectionTable.put(key, entry);
        }
        entry.lastSeen = System.currentTimeMillis();
        if (outbound) entry.bytesOut += length;
        else entry.bytesIn += length;
    }

    private int hashConnection(byte[] packet, int ihl, int srcPort, int dstPort, int protocol) {
        int h = protocol;
        for (int i = IPV4_SRC_OFFSET; i < IPV4_SRC_OFFSET + 8; i++) h = h * 31 + packet[i];
        h = h * 31 + srcPort;
        h = h * 31 + dstPort;
        return h;
    }

    /** Evict stale connections (older than 5 minutes). */
    public void evictStale() {
        long cutoff = System.currentTimeMillis() - 300_000;
        connectionTable.entrySet().removeIf(e -> e.getValue().lastSeen < cutoff);
    }

    public long getBytesForwarded() { return bytesForwarded.get(); }
    public long getPacketsForwarded() { return packetsForwarded.get(); }
    public int getActiveConnections() { return connectionTable.size(); }

    public void reset() {
        bytesForwarded.set(0);
        packetsForwarded.set(0);
        connectionTable.clear();
    }
}
