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
    private static final long NWID = 0xd88e73ff30fdf2b0L; // your network
    private TextView logView;
    private ScrollView scrollView;
    private Handler handler = new Handler(Looper.getMainLooper());
    private String myAddr = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 32, 32, 32);

        Button btnStart = new Button(this);
        btnStart.setText("Start libzt + Loopback Test");
        root.addView(btnStart);

        scrollView = new ScrollView(this);
        logView = new TextView(this);
        logView.setTextSize(12);
        scrollView.addView(logView);
        root.addView(scrollView, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));

        setContentView(root);

        btnStart.setOnClickListener(v -> {
            btnStart.setEnabled(false);
            new Thread(this::runTest).start();
        });
    }

    private void log(String msg) {
        Log.i(TAG, msg);
        handler.post(() -> {
            logView.append(msg + "\n");
            scrollView.fullScroll(ScrollView.FOCUS_DOWN);
        });
    }

    private void runTest() {
        String path = getFilesDir().getAbsolutePath() + "/libzt";
        new java.io.File(path).mkdirs();

        // Copy planet file from main ZT data dir if it exists
        // App uses "mars" as custom planet
        java.io.File libztPlanet = new java.io.File(path, "planet");
        for (String name : new String[]{"mars", "planet"}) {
            java.io.File src = new java.io.File(getFilesDir(), name);
            if (src.exists()) {
                try {
                    if (libztPlanet.exists()) libztPlanet.delete();
                    java.nio.file.Files.copy(src.toPath(), libztPlanet.toPath());
                    log("Copied " + name + " as planet for libzt");
                    break;
                } catch (Exception e) {
                    log("Failed to copy " + name + ": " + e);
                }
            }
        }

        log("Starting libzt...");
        final boolean[] online = {false};
        int rc = ZtSocket.start(path, new ZtSocket.EventCallback() {
            @Override
            public void onEvent(int code) {
                String name;
                switch (code) {
                    case 0: name = "NODE_UP"; break;
                    case 2: name = "NODE_ONLINE"; online[0] = true; break;
                    case 34: name = "REQUESTING_CONFIG"; break;
                    case 35: name = "NETWORK_OK"; online[0] = true; break;
                    case 36: name = "ACCESS_DENIED"; break;
                    case 37: name = "READY_IP4"; online[0] = true; break;
                    case 48: name = "STACK_UP"; break;
                    case 96: name = "PEER_P2P"; online[0] = true; break;
                    case 97: name = "PEER_RELAY"; online[0] = true; break;
                    default: name = "?" + code; break;
                }
                log("Event: " + name + " (" + code + ")");
            }
            @Override
            public void onAddress(String addr) {
                log("Address: " + addr);
                myAddr = addr;
            }
        }, 9994);
        log("zts_start rc=" + rc);

        // Wait for node to come online
        log("Waiting for NODE_ONLINE...");
        for (int i = 0; i < 30 && !online[0]; i++) {
            try { Thread.sleep(1000); } catch (Exception e) { break; }
            if (i % 5 == 4) {
                long nodeId = ZtSocket.getNodeId();
                int running = ZtSocket.coreRunning();
                log("Still waiting... (" + (i+1) + "s) nodeId=" + Long.toHexString(nodeId) + " coreRunning=" + running);
            }
        }
        if (!online[0]) {
            long nodeId = ZtSocket.getNodeId();
            log("TIMEOUT: node never came online.");
            log("Node ID: " + Long.toHexString(nodeId));
            log("Make sure: 1) ZT VPN is disconnected  2) Internet works  3) Force-stopped app before install");
            return;
        }

        log("Joining network " + Long.toHexString(NWID) + "...");
        rc = ZtSocket.join(NWID);
        log("zts_join rc=" + rc);

        long nodeId = ZtSocket.getNodeId();
        log("Node ID: " + Long.toHexString(nodeId));
        log("Authorize this node on your ZT controller if needed");

        // Wait for address
        log("Waiting for address...");
        for (int i = 0; i < 60 && myAddr == null; i++) {
            try { Thread.sleep(1000); } catch (Exception e) { break; }
        }
        if (myAddr == null) {
            log("TIMEOUT: no address after 60s");
            return;
        }
        log("Got address: " + myAddr);

        // Loopback test: server + client on same node
        int port = 19999;
        log("Starting TCP server on " + myAddr + ":" + port);

        // Server thread
        new Thread(() -> {
            int srv = ZtSocket.socket(ZtSocket.AF_INET, ZtSocket.SOCK_STREAM, 0);
            log("Server socket fd=" + srv);
            int brc = ZtSocket.bind(srv, myAddr, port);
            log("bind rc=" + brc);
            int lrc = ZtSocket.listen(srv, 1);
            log("listen rc=" + lrc);
            log("Waiting for connection...");
            int client = ZtSocket.accept(srv);
            log("Accepted client fd=" + client);
            byte[] data = ZtSocket.recv(client, 1024);
            if (data != null) {
                String msg = new String(data);
                log("Server received: " + msg);
                ZtSocket.send(client, ("echo:" + msg).getBytes());
            }
            ZtSocket.closeSocket(client);
            ZtSocket.closeSocket(srv);
        }).start();

        try { Thread.sleep(500); } catch (Exception e) {}

        // Client
        log("Connecting to " + myAddr + ":" + port);
        int fd = ZtSocket.socket(ZtSocket.AF_INET, ZtSocket.SOCK_STREAM, 0);
        log("Client socket fd=" + fd);

        long t0 = System.currentTimeMillis();
        rc = ZtSocket.connect(fd, myAddr, port);
        log("connect rc=" + rc + " (" + (System.currentTimeMillis() - t0) + "ms)");

        String testMsg = "hello-libzt-" + System.currentTimeMillis();
        t0 = System.currentTimeMillis();
        ZtSocket.send(fd, testMsg.getBytes());
        byte[] resp = ZtSocket.recv(fd, 1024);
        long rtt = System.currentTimeMillis() - t0;

        if (resp != null) {
            log("Client received: " + new String(resp));
            log("Loopback RTT: " + rtt + "ms");
            log("=== LOOPBACK TEST PASSED ===");
        } else {
            log("=== LOOPBACK TEST FAILED - no response ===");
        }
        ZtSocket.closeSocket(fd);
    }
}
