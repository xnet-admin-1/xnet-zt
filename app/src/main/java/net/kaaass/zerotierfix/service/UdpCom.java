package net.kaaass.zerotierfix.service;

import android.util.Log;

import com.zerotier.sdk.Node;
import com.zerotier.sdk.PacketSender;
import com.zerotier.sdk.ResultCode;

import net.kaaass.zerotierfix.util.DebugLog;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;

// TODO: clear up
public class UdpCom implements PacketSender, Runnable {
    private static final String TAG = "UdpCom";
    private Node node;
    private final DatagramSocket svrSocket;
    private final ZeroTierOneService ztService;

    UdpCom(ZeroTierOneService zeroTierOneService, DatagramSocket datagramSocket) {
        this.svrSocket = datagramSocket;
        this.ztService = zeroTierOneService;
    }

    public void setNode(Node node2) {
        this.node = node2;
    }

    private final DatagramPacket sendPacket = new DatagramPacket(new byte[0], 0);

    @Override // com.zerotier.sdk.PacketSender
    public int onSendPacketRequested(long j, InetSocketAddress inetSocketAddress, byte[] bArr, int i) {
        if (this.svrSocket == null) {
            Log.e(TAG, "Attempted to send packet on a null socket");
            return -1;
        }
        try {
            sendPacket.setData(bArr, 0, bArr.length);
            sendPacket.setSocketAddress(inetSocketAddress);
            this.svrSocket.send(sendPacket);
            return 0;
        } catch (Exception unused) {
            return -1;
        }
    }

    public void run() {
        Log.d(TAG, "UDP Listen Thread Started.");
        try {
            long[] jArr = new long[1];
            byte[] bArr = new byte[16384];
            DatagramPacket datagramPacket = new DatagramPacket(bArr, 16384);
            while (!Thread.interrupted()) {
                jArr[0] = 0;
                datagramPacket.setLength(16384);
                try {
                    this.svrSocket.receive(datagramPacket);
                    int len = datagramPacket.getLength();
                    if (len > 0) {
                        byte[] bArr2 = new byte[len];
                        System.arraycopy(bArr, 0, bArr2, 0, len);
                        ResultCode processWirePacket = this.node.processWirePacket(System.currentTimeMillis(), -1, new InetSocketAddress(datagramPacket.getAddress(), datagramPacket.getPort()), bArr2, jArr);
                        if (processWirePacket != ResultCode.RESULT_OK) {
                            Log.e(TAG, "processWirePacket returned: " + processWirePacket.toString());
                            this.ztService.shutdown();
                        }
                        this.ztService.setNextBackgroundTaskDeadline(jArr[0]);
                    }
                } catch (SocketTimeoutException ignored) {
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        Log.d(TAG, "UDP Listen Thread Ended.");
    }
}
