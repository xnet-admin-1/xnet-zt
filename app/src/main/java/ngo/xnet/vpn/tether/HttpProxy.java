package ngo.xnet.vpn.tether;

import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * HTTP proxy server supporting CONNECT tunneling and plain HTTP forwarding.
 * Serves WPAD/PAC file for auto-discovery by tethered clients.
 * Binds to tether interface only.
 */
public class HttpProxy {
    private static final String TAG = "HttpProxy";
    private static final int DEFAULT_PORT = 8080;
    private static final int BUFFER_SIZE = 16384;

    private final TetherBridge bridge;
    private volatile ServerSocket serverSocket;
    private volatile boolean running;
    private Thread acceptThread;
    private ExecutorService executor;
    private int port = DEFAULT_PORT;
    private InetAddress bindAddress;
    private final AtomicLong bytesTransferred = new AtomicLong();

    public HttpProxy(TetherBridge bridge) {
        this.bridge = bridge;
    }

    public void setPort(int port) { this.port = port; }

    public void start(InetAddress bindAddress) {
        if (running) return;
        this.bindAddress = bindAddress;
        running = true;
        executor = Executors.newCachedThreadPool();
        acceptThread = new Thread(() -> runServer(bindAddress), "HttpProxy");
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
            client.setSoTimeout(30000);
            BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream()));
            String requestLine = reader.readLine();
            if (requestLine == null) { client.close(); return; }

            String[] parts = requestLine.split(" ", 3);
            if (parts.length < 2) { client.close(); return; }

            String method = parts[0].toUpperCase();
            String target = parts[1];

            // Consume headers
            String line;
            String host = null;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                if (line.toLowerCase().startsWith("host:")) {
                    host = line.substring(5).trim();
                }
            }

            if (method.equals("CONNECT")) {
                handleConnect(client, target);
            } else if (target.equals("/wpad.dat") || target.equals("/proxy.pac")) {
                serveWpad(client);
            } else {
                handleForward(client, method, target, host);
            }
        } catch (Exception e) {
            Log.w(TAG, "Client error", e);
            try { client.close(); } catch (Exception ignored) {}
        }
    }

    private void handleConnect(Socket client, String target) throws Exception {
        String[] hp = target.split(":");
        String host = hp[0];
        int port = hp.length > 1 ? Integer.parseInt(hp[1]) : 443;

        Socket upstream = bridge.connectUpstream(InetAddress.getByName(host), port);

        // Send 200 Connection Established
        OutputStream out = client.getOutputStream();
        out.write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
        out.flush();

        // Relay bidirectionally
        relay(client, upstream);
    }

    private void handleForward(Socket client, String method, String target, String host) throws Exception {
        // Parse target URL for host:port
        String connectHost = host;
        int connectPort = 80;
        if (connectHost != null && connectHost.contains(":")) {
            String[] hp = connectHost.split(":");
            connectHost = hp[0];
            connectPort = Integer.parseInt(hp[1]);
        }
        if (connectHost == null) {
            sendError(client, 400, "Bad Request");
            return;
        }

        Socket upstream = bridge.connectUpstream(InetAddress.getByName(connectHost), connectPort);

        // Rewrite request line to relative path
        String path = target;
        if (target.startsWith("http://")) {
            int pathStart = target.indexOf('/', 7);
            path = pathStart >= 0 ? target.substring(pathStart) : "/";
        }

        OutputStream upOut = upstream.getOutputStream();
        String reqLine = method + " " + path + " HTTP/1.1\r\nHost: " + host + "\r\nConnection: close\r\n\r\n";
        upOut.write(reqLine.getBytes(StandardCharsets.US_ASCII));
        upOut.flush();

        // Relay response back to client
        relay(client, upstream);
    }

    private void serveWpad(Socket client) throws IOException {
        String addr = bindAddress != null ? bindAddress.getHostAddress() : "192.168.49.1";
        String pac = "function FindProxyForURL(url, host) {\n"
                + "  if (isPlainHostName(host) || host == \"localhost\") return \"DIRECT\";\n"
                + "  return \"PROXY " + addr + ":" + port + "; SOCKS5 " + addr + ":1080; DIRECT\";\n"
                + "}\n";
        byte[] body = pac.getBytes(StandardCharsets.UTF_8);
        String response = "HTTP/1.1 200 OK\r\n"
                + "Content-Type: application/x-ns-proxy-autoconfig\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n\r\n";
        OutputStream out = client.getOutputStream();
        out.write(response.getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
        client.close();
    }

    private void sendError(Socket client, int code, String msg) throws IOException {
        String response = "HTTP/1.1 " + code + " " + msg + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";
        client.getOutputStream().write(response.getBytes(StandardCharsets.US_ASCII));
        client.close();
    }

    private void relay(Socket client, Socket upstream) {
        Thread t1 = new Thread(() -> copy(client, upstream));
        Thread t2 = new Thread(() -> copy(upstream, client));
        t1.setDaemon(true); t2.setDaemon(true);
        t1.start(); t2.start();
        try { t1.join(); } catch (InterruptedException ignored) {}
        try { client.close(); } catch (Exception ignored) {}
        try { upstream.close(); } catch (Exception ignored) {}
    }

    private void copy(Socket from, Socket to) {
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

    public long getBytesTransferred() { return bytesTransferred.get(); }
    public boolean isRunning() { return running; }
    public int getPort() { return port; }
}
