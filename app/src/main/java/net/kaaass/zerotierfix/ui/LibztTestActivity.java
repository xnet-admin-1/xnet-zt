package net.kaaass.zerotierfix.ui;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.LinearLayout;
import androidx.appcompat.app.AppCompatActivity;
import ngo.xnet.libzt.ZtSocket;

public class LibztTestActivity extends AppCompatActivity {
    private static final String TAG = "LibztTest";
    private static final long NWID = 0xd88e73ff30fdf2b0L;
    private TextView logView;
    private ScrollView scrollView;
    private Handler handler = new Handler(Looper.getMainLooper());
    private volatile String libztAddr = null;
    private volatile boolean online = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 32, 32, 32);

        Button btnLoopback = new Button(this);
        btnLoopback.setText("Phase 1: Loopback");
        btnLoopback.setOnClickListener(v -> { v.setEnabled(false); clearLog(); new Thread(this::runLoopback).start(); });
        root.addView(btnLoopback);

        Button btnP2p = new Button(this);
        btnP2p.setText("Phase 2: Talk to VPN node");
        btnP2p.setOnClickListener(v -> { v.setEnabled(false); clearLog(); new Thread(this::runP2P).start(); });
        root.addView(btnP2p);

        scrollView = new ScrollView(this);
        logView = new TextView(this);
        logView.setTextSize(12);
        scrollView.addView(logView);
        root.addView(scrollView, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));

        setContentView(root);
    }

    private void clearLog() {
        new java.io.File(getFilesDir(), "libzt-log.txt").delete();
        handler.post(() -> logView.setText(""));
    }

    private void log(String msg) {
        Log.i(TAG, msg);
        handler.post(() -> {
            logView.append(msg + "\n");
            scrollView.fullScroll(ScrollView.FOCUS_DOWN);
        });
        try {
            java.io.FileWriter fw = new java.io.FileWriter(new java.io.File(getFilesDir(), "libzt-log.txt"), true);
            fw.write(msg + "\n");
            fw.close();
        } catch (Exception ignored) {}
    }

    private void copyPlanet(String path) {
        java.io.File dest = new java.io.File(path, "planet");
        for (String name : new String[]{"mars", "planet"}) {
            java.io.File src = new java.io.File(getFilesDir(), name);
            if (src.exists()) {
                try {
                    if (dest.exists()) dest.delete();
                    java.nio.file.Files.copy(src.toPath(), dest.toPath());
                    log("Copied " + name + " as planet");
                    return;
                } catch (Exception e) { log("Copy failed: " + e); }
            }
        }
    }

    private boolean startLibzt() {
        if (online) return true; // already running
        String path = getFilesDir().getAbsolutePath() + "/libzt";
        // Clear stale state from previous runs
        java.io.File dir = new java.io.File(path);
        if (dir.exists()) {
            for (java.io.File f : dir.listFiles()) {
                if (!f.getName().equals("identity.public") && !f.getName().equals("identity.secret"))
                    f.delete();
            }
        }
        dir.mkdirs();
        copyPlanet(path);

        log("Starting libzt...");
        ZtSocket.stop(); // clean up any previous instance
        try { Thread.sleep(500); } catch (Exception e) {}
        int rc = ZtSocket.start(path, new ZtSocket.EventCallback() {
            @Override
            public void onEvent(int code) {
                String name;
                switch (code) {
                    case 0: name = "NODE_UP"; break;
                    case 2: name = "NODE_ONLINE"; online = true; break;
                    case 35: name = "NETWORK_OK"; online = true; break;
                    case 36: name = "ACCESS_DENIED"; break;
                    case 37: name = "READY_IP4"; online = true; break;
                    case 48: name = "STACK_UP"; break;
                    case 96: name = "PEER_P2P"; online = true; break;
                    case 97: name = "PEER_RELAY"; online = true; break;
                    default: name = "?" + code; break;
                }
                log("Event: " + name + " (" + code + ")");
            }
            @Override
            public void onAddress(String addr) {
                log("Address: " + addr);
                libztAddr = addr;
            }
        }, 29994);
        log("zts_start rc=" + rc);
        if (rc != 0) { log("Start failed"); return false; }

        log("Waiting for online...");
        for (int i = 0; i < 30 && !online; i++) {
            try { Thread.sleep(1000); } catch (Exception e) { break; }
        }
        if (!online) { log("TIMEOUT: not online"); return false; }

        log("Joining " + Long.toHexString(NWID) + "...");
        ZtSocket.join(NWID);
        log("Node ID: " + Long.toHexString(ZtSocket.getNodeId()));

        log("Waiting for address...");
        for (int i = 0; i < 60 && libztAddr == null; i++) {
            try { Thread.sleep(1000); } catch (Exception e) { break; }
        }
        if (libztAddr == null) { log("TIMEOUT: no address"); return false; }
        log("libzt address: " + libztAddr);
        return true;
    }

    private void runLoopback() {
        if (!startLibzt()) return;
        int port = 19999;
        log("\n--- Phase 1: Loopback ---");
        log("Server on " + libztAddr + ":" + port);

        new Thread(() -> {
            int srv = ZtSocket.socket(ZtSocket.AF_INET, ZtSocket.SOCK_STREAM, 0);
            ZtSocket.bind(srv, "0.0.0.0", port);
            ZtSocket.listen(srv, 1);
            int c = ZtSocket.accept(srv);
            byte[] data = ZtSocket.recv(c, 1024);
            if (data != null) {
                log("Server got: " + new String(data));
                ZtSocket.send(c, ("echo:" + new String(data)).getBytes());
            }
            ZtSocket.closeSocket(c);
            ZtSocket.closeSocket(srv);
        }).start();

        try { Thread.sleep(500); } catch (Exception e) {}

        int fd = ZtSocket.socket(ZtSocket.AF_INET, ZtSocket.SOCK_STREAM, 0);
        long t0 = System.currentTimeMillis();
        int rc = ZtSocket.connect(fd, libztAddr, port);
        log("connect rc=" + rc + " (" + (System.currentTimeMillis() - t0) + "ms)");
        ZtSocket.send(fd, "hello-loopback".getBytes());
        byte[] resp = ZtSocket.recv(fd, 1024);
        if (resp != null) {
            log("Got: " + new String(resp));
            log("=== LOOPBACK PASSED ===");
        } else {
            log("=== LOOPBACK FAILED ===");
        }
        ZtSocket.closeSocket(fd);
    }

    private void runP2P() {
        if (!startLibzt()) return;

        // Find the main VPN's ZT address
        String vpnAddr = null;
        try {
            java.util.Enumeration<java.net.NetworkInterface> nets = java.net.NetworkInterface.getNetworkInterfaces();
            while (nets.hasMoreElements()) {
                java.net.NetworkInterface ni = nets.nextElement();
                if (!ni.getName().startsWith("tun")) continue;
                java.util.Enumeration<java.net.InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    java.net.InetAddress a = addrs.nextElement();
                    if (a instanceof java.net.Inet4Address) {
                        vpnAddr = a.getHostAddress();
                        break;
                    }
                }
            }
        } catch (Exception e) { log("Error finding VPN addr: " + e); }

        if (vpnAddr == null) {
            log("No TUN interface found. Connect the VPN first, then run this test.");
            return;
        }
        final String vpn = vpnAddr;

        log("\n--- Phase 2: libzt → VPN node ---");
        log("libzt node: " + libztAddr + " (userspace)");
        log("VPN node:   " + vpn + " (TUN)");

        // Start a TCP server on the VPN address using regular Java sockets
        int port = 19998;
        new Thread(() -> {
            try {
                java.net.ServerSocket ss = new java.net.ServerSocket(port, 1, java.net.InetAddress.getByName(vpn));
                log("Java server listening on " + vpn + ":" + port);
                java.net.Socket c = ss.accept();
                log("Java server accepted connection from " + c.getRemoteSocketAddress());
                byte[] buf = new byte[1024];
                int n = c.getInputStream().read(buf);
                String msg = new String(buf, 0, n);
                log("Java server got: " + msg);
                c.getOutputStream().write(("reply:" + msg).getBytes());
                c.close();
                ss.close();
            } catch (Exception e) { log("Java server error: " + e); }
        }).start();

        try { Thread.sleep(500); } catch (Exception e) {}

        // Connect from libzt to the VPN node
        log("libzt connecting to " + vpn + ":" + port);
        int fd = ZtSocket.socket(ZtSocket.AF_INET, ZtSocket.SOCK_STREAM, 0);
        long t0 = System.currentTimeMillis();
        int rc = ZtSocket.connect(fd, vpn, port);
        long connTime = System.currentTimeMillis() - t0;
        log("connect rc=" + rc + " (" + connTime + "ms)");

        if (rc != 0) {
            log("=== P2P FAILED: connect error ===");
            ZtSocket.closeSocket(fd);
            return;
        }

        String testMsg = "hello-from-libzt-" + System.currentTimeMillis();
        t0 = System.currentTimeMillis();
        ZtSocket.send(fd, testMsg.getBytes());
        byte[] resp = ZtSocket.recv(fd, 1024);
        long rtt = System.currentTimeMillis() - t0;

        if (resp != null) {
            log("libzt got: " + new String(resp));
            log("P2P RTT: " + rtt + "ms (connect: " + connTime + "ms)");
            log("=== P2P TEST PASSED ===");
        } else {
            log("=== P2P FAILED: no response ===");
        }
        ZtSocket.closeSocket(fd);
    }
}
