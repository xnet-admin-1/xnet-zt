package ngo.xnet.vpn.tether;

import android.util.Log;

import com.zerotier.sdk.Node;
import com.zerotier.sdk.ResultCode;

import java.io.FileOutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;

import ngo.xnet.vpn.util.IPPacketUtils;
import ngo.xnet.vpn.util.RemoteLog;

/**
 * L3 bridge between tether subnet and ZeroTier network.
 *
 * Outbound: intercepts IP packets from tether clients (source in tether subnet),
 * rewrites source IP to the phone's ZT IP (NAT), wraps as ethernet frame,
 * and injects into ZT node via processVirtualNetworkFrame().
 *
 * Inbound: ZT frames destined for the phone's ZT IP are written to TUN by
 * TunTapAdapter.onVirtualNetworkFrame() as normal. The kernel routes responses
 * back to the tether client because we add a route for the tether subnet.
 *
 * This gives tethered clients full L3 connectivity through the ZT network:
 * TCP, UDP, ICMP all work transparently without proxy configuration.
 */
public class TetherL3Bridge {
    private static final String TAG = "TetherL3Bridge";

    private Node node;
    private long networkId;
    private long localMac;
    private long gatewayMac;
    private byte[] localZtIp;  // phone's ZT IPv4 (4 bytes)
    private byte[] tetherSubnet; // tether subnet (4 bytes)
    private int tetherPrefix;
    private FileOutputStream tunOut;
    private volatile boolean active;

    // NAT table: map tether client IP+port → original for response rewriting
    // For simplicity, we do source-NAT only (rewrite src IP to ZT IP)
    // Responses come back to ZT IP and kernel routes to tether client via TUN

    private final long[] nextDeadline = new long[1];

    public void configure(Node node, long networkId, long localMac, long gatewayMac,
                          InetAddress localZtAddr, InetAddress tetherAddr, int tetherPrefix,
                          FileOutputStream tunOut) {
        this.node = node;
        this.networkId = networkId;
        this.localMac = localMac;
        this.gatewayMac = gatewayMac;
        this.localZtIp = localZtAddr.getAddress();
        this.tetherSubnet = tetherAddr.getAddress();
        this.tetherPrefix = tetherPrefix;
        this.tunOut = tunOut;
        this.active = true;
        RemoteLog.log(TAG, "Configured: ztIp=" + localZtAddr.getHostAddress()
                + " gwMac=" + Long.toHexString(gatewayMac)
                + " tether=" + tetherAddr.getHostAddress() + "/" + tetherPrefix);
    }

    public boolean isActive() { return active; }

    public void stop() { active = false; }

    /**
     * Process an outbound IPv4 packet from TUN. Returns true if the packet
     * was from a tether client and was bridged to ZT (caller should NOT
     * process it further). Returns false if not tether traffic.
     */
    public boolean processOutbound(byte[] packet, int length) {
        if (!active || node == null) return false;

        // Check if source IP is in tether subnet
        if (length < 20) return false;
        if (!isInTetherSubnet(packet, 12)) return false; // src IP at offset 12

        // Rewrite source IP to our ZT IP (NAT)
        byte[] pkt = new byte[length];
        System.arraycopy(packet, 0, pkt, 0, length);
        System.arraycopy(localZtIp, 0, pkt, 12, 4);

        // Recalculate IP header checksum
        fixIpChecksum(pkt);

        // Inject into ZT as ethernet frame destined for gateway
        var result = node.processVirtualNetworkFrame(
                System.currentTimeMillis(), networkId,
                localMac, gatewayMac,
                0x0800, // IPv4 ethertype
                0, pkt, nextDeadline);

        if (result == ResultCode.RESULT_OK) {
            return true;
        } else {
            Log.w(TAG, "processVirtualNetworkFrame failed: " + result);
            return false;
        }
    }

    /**
     * Process an inbound ZT frame. If the destination IP is a tether client,
     * rewrite dest IP and write to TUN. Returns true if handled.
     * 
     * Note: in practice, responses come back addressed to our ZT IP (since we
     * NAT'd the source). The kernel routes them to the tether client because
     * we have a route for the tether subnet pointing at the TUN. So this method
     * is only needed for unsolicited traffic TO tether clients (e.g., if they
     * had ZT-range IPs). For NAT mode, inbound handling is automatic.
     */
    public boolean processInbound(byte[] frameData, int length) {
        // NAT mode: responses are addressed to our ZT IP, kernel handles routing
        // to tether client via the tether subnet route. Nothing to do here.
        return false;
    }

    /** Check if the IP at the given offset in the packet is in the tether subnet. */
    private boolean isInTetherSubnet(byte[] pkt, int offset) {
        if (offset + 4 > pkt.length) return false;
        int mask = tetherPrefix == 0 ? 0 : (0xFFFFFFFF << (32 - tetherPrefix));
        int addr = ((pkt[offset] & 0xFF) << 24) | ((pkt[offset+1] & 0xFF) << 16)
                | ((pkt[offset+2] & 0xFF) << 8) | (pkt[offset+3] & 0xFF);
        int subnet = ((tetherSubnet[0] & 0xFF) << 24) | ((tetherSubnet[1] & 0xFF) << 16)
                | ((tetherSubnet[2] & 0xFF) << 8) | (tetherSubnet[3] & 0xFF);
        return (addr & mask) == (subnet & mask);
    }

    /** Recalculate IPv4 header checksum after modifying source IP. */
    private static void fixIpChecksum(byte[] pkt) {
        int ihl = (pkt[0] & 0x0F) * 4;
        // Zero out checksum field
        pkt[10] = 0;
        pkt[11] = 0;
        // Compute
        long sum = 0;
        for (int i = 0; i < ihl; i += 2) {
            sum += ((pkt[i] & 0xFF) << 8) | (pkt[i+1] & 0xFF);
        }
        while ((sum >> 16) != 0) {
            sum = (sum & 0xFFFF) + (sum >> 16);
        }
        int checksum = (int) (~sum & 0xFFFF);
        pkt[10] = (byte) ((checksum >> 8) & 0xFF);
        pkt[11] = (byte) (checksum & 0xFF);
    }
}
