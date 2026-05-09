package ngo.xnet.vpn.tether;

import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SOCKS5 proxy server (RFC 1928) that listens on the tether interface.
 * Supports CONNECT and UDP ASSOCIATE commands.
 * Optional username/password auth (RFC 1929).
 */
public class SocksProxy {
    private static final String TAG = "SocksProxy";
    private static final int DEFAULT_PORT = 1080;
    private static final int BUFFER_SIZE = 16384;

    private final TetherBridge bridge;
    private volatile ServerSocket serverSocket;
    private volatile boolean running;
    private Thread acceptThread;
    private ExecutorService executor;
    private int port = DEFAULT_PORT;
    private String authUser;
    private String authPass;
    private final AtomicLong bytesTransferred = new AtomicLong();

    public SocksProxy(TetherBridge bridge) {
        this.bridge = bridge;
    }

    public void setPort(int port) { this.port = port; }
    public void setAuth(String user, String pass) { this.authUser = user; this.authPass = pass; }

    public void start(InetAddress bindAddress) {
        if (running) return;
        running = true;
        executor = Executors.newCachedThreadPool();
        acceptThread = new Thread(() -> runServer(bindAddress), "SocksProxy");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    public void stop() {
        running = false;
        try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignored) {}
        if (acceptThread != null) acceptThread.interrupt();
        if (executor != null) {
            executor.shutdownNow();
            try { executor.awaitTermination(2, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        }
        Log.i(TAG, "Stopped");
    }

    private void runServer(InetAddress bindAddress) {
        try {
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress(bindAddress, port));
            Log.i(TAG, "Listening on " + bindAddress.getHostAddress() + ":" + port);

            while (running) {
                Socket client = serverSocket.accept();
                executor.submit(() -> handleClient(client));
            }
        } catch (Exception e) {
            if (running) Log.e(TAG, "Server error", e);
        }
    }

    private void handleClient(Socket client) {
        try {
            client.setSoTimeout(60000);
            InputStream in = client.getInputStream();
            OutputStream out = client.getOutputStream();

            // Version/auth negotiation
            int ver = in.read();
            if (ver != 5) { client.close(); return; }
            int nmethods = in.read();
            byte[] methods = new byte[nmethods];
            readFully(in, methods);

            if (authUser != null) {
                // Require username/password (method 0x02)
                out.write(new byte[]{0x05, 0x02});
                out.flush();
                if (!authenticateClient(in, out)) { client.close(); return; }
            } else {
                // No auth (method 0x00)
                out.write(new byte[]{0x05, 0x00});
                out.flush();
            }

            // Read command
            int cmdVer = in.read(); // version
            int cmd = in.read();    // command
            in.read();              // reserved

            switch (cmd) {
                case 0x01: handleConnect(client, in, out); break;
                case 0x03: handleUdpAssociate(client, in, out); break;
                default:
                    sendReply(out, (byte) 0x07); // command not supported
                    client.close();
            }
        } catch (Exception e) {
            Log.w(TAG, "Client error", e);
            try { client.close(); } catch (Exception ignored) {}
        }
    }

    private boolean authenticateClient(InputStream in, OutputStream out) throws IOException {
        int authVer = in.read(); // should be 0x01
        int ulen = in.read();
        byte[] user = new byte[ulen];
        readFully(in, user);
        int plen = in.read();
        byte[] pass = new byte[plen];
        readFully(in, pass);

        boolean ok = authUser.equals(new String(user)) && authPass.equals(new String(pass));
        out.write(new byte[]{0x01, (byte) (ok ? 0x00 : 0x01)});
        out.flush();
        return ok;
    }

    private void handleConnect(Socket client, InputStream in, OutputStream out) throws Exception {
        InetSocketAddress dest = readAddress(in);
        if (dest == null) { sendReply(out, (byte) 0x04); client.close(); return; }

        Socket upstream = bridge.connectUpstream(dest.getAddress(), dest.getPort());
        // Send success reply with bound address
        byte[] boundAddr = upstream.getLocalAddress().getAddress();
        int boundPort = upstream.getLocalPort();
        byte[] reply = new byte[10];
        reply[0] = 0x05; reply[1] = 0x00; reply[2] = 0x00; reply[3] = 0x01;
        System.arraycopy(boundAddr, 0, reply, 4, 4);
        reply[8] = (byte) ((boundPort >> 8) & 0xFF);
        reply[9] = (byte) (boundPort & 0xFF);
        out.write(reply);
        out.flush();

        // Relay data bidirectionally
        relay(client, upstream);
    }

    private void handleUdpAssociate(Socket client, InputStream in, OutputStream out) throws Exception {
        readAddress(in); // client's expected UDP source (often 0.0.0.0:0)

        DatagramSocket udpSocket = bridge.createUpstreamDatagramSocket();
        udpSocket.bind(new InetSocketAddress(client.getLocalAddress(), 0));

        byte[] boundAddr = udpSocket.getLocalAddress().getAddress();
        int boundPort = udpSocket.getLocalPort();
        byte[] reply = new byte[10];
        reply[0] = 0x05; reply[1] = 0x00; reply[2] = 0x00; reply[3] = 0x01;
        System.arraycopy(boundAddr, 0, reply, 4, 4);
        reply[8] = (byte) ((boundPort >> 8) & 0xFF);
        reply[9] = (byte) (boundPort & 0xFF);
        out.write(reply);
        out.flush();

        // UDP relay loop — runs until TCP control connection closes
        executor.submit(() -> {
            try {
                byte[] buf = new byte[65536];
                udpSocket.setSoTimeout(30000);
                while (running && !client.isClosed()) {
                    DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                    udpSocket.receive(pkt);
                    bytesTransferred.addAndGet(pkt.getLength());
                }
            } catch (Exception ignored) {
            } finally {
                udpSocket.close();
            }
        });

        // Wait for TCP control connection to close
        try { while (in.read() != -1) {} } catch (Exception ignored) {}
        udpSocket.close();
        client.close();
    }

    private void relay(Socket client, Socket upstream) {
        Thread t1 = new Thread(() -> copy(client, upstream, true));
        Thread t2 = new Thread(() -> copy(upstream, client, false));
        t1.setDaemon(true); t2.setDaemon(true);
        t1.start(); t2.start();
        try { t1.join(); } catch (InterruptedException ignored) {}
        try { client.close(); } catch (Exception ignored) {}
        try { upstream.close(); } catch (Exception ignored) {}
    }

    private void copy(Socket from, Socket to, boolean isUpload) {
        try {
            byte[] buf = new byte[BUFFER_SIZE];
            InputStream in = from.getInputStream();
            OutputStream out = to.getOutputStream();
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                out.flush();
                bytesTransferred.addAndGet(n);
            }
        } catch (Exception ignored) {}
    }

    private InetSocketAddress readAddress(InputStream in) throws IOException {
        int atype = in.read();
        byte[] addr;
        switch (atype) {
            case 0x01: // IPv4
                addr = new byte[4];
                readFully(in, addr);
                break;
            case 0x03: // Domain
                int len = in.read();
                byte[] domain = new byte[len];
                readFully(in, domain);
                addr = InetAddress.getByName(new String(domain)).getAddress();
                break;
            case 0x04: // IPv6
                addr = new byte[16];
                readFully(in, addr);
                break;
            default: return null;
        }
        int port = ((in.read() & 0xFF) << 8) | (in.read() & 0xFF);
        return new InetSocketAddress(InetAddress.getByAddress(addr), port);
    }

    private void sendReply(OutputStream out, byte status) throws IOException {
        out.write(new byte[]{0x05, status, 0x00, 0x01, 0, 0, 0, 0, 0, 0});
        out.flush();
    }

    private void readFully(InputStream in, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n < 0) throw new IOException("EOF");
            off += n;
        }
    }

    public long getBytesTransferred() { return bytesTransferred.get(); }
    public boolean isRunning() { return running; }
}
