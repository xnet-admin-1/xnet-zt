package ngo.xnet.vpn.service;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

import java.net.InetAddress;

import ngo.xnet.vpn.tether.DnsProxy;
import ngo.xnet.vpn.tether.HttpProxy;
import ngo.xnet.vpn.tether.SocksProxy;
import ngo.xnet.vpn.tether.TetherBridge;
import ngo.xnet.vpn.util.RemoteLog;

/**
 * Runs proxy servers in a separate process (:tether) so that their sockets
 * are routed through the VPN tunnel (the VPN service process is excluded
 * from its own tunnel by the Android kernel).
 */
public class TetherProxyService extends Service {
    private static final String TAG = "TetherProxySvc";

    private static void rlog(String msg) {
        Log.i(TAG, msg);
        RemoteLog.log(TAG, msg);
    }
    public static final String EXTRA_BIND_ADDR = "bind_addr";
    public static final String EXTRA_SOCKS_PORT = "socks_port";
    public static final String EXTRA_HTTP_PORT = "http_port";
    public static final String EXTRA_DNS_PORT = "dns_port";
    public static final String EXTRA_DOH_URL = "doh_url";
    public static final String EXTRA_SOCKET_MODE = "socket_mode";

    private SocksProxy socksProxy;
    private HttpProxy httpProxy;
    private DnsProxy dnsProxy;
    private TetherBridge bridge;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        RemoteLog.start(19982); // Port 19982 for :tether process (19981 is main process)

        String bindAddrStr = intent.getStringExtra(EXTRA_BIND_ADDR);
        int socksPort = intent.getIntExtra(EXTRA_SOCKS_PORT, 1080);
        int httpPort = intent.getIntExtra(EXTRA_HTTP_PORT, 8080);
        int dnsPort = intent.getIntExtra(EXTRA_DNS_PORT, 5353);
        String dohUrl = intent.getStringExtra(EXTRA_DOH_URL);
        String mode = intent.getStringExtra(EXTRA_SOCKET_MODE);

        try {
            InetAddress bindAddr = InetAddress.getByName(bindAddrStr);

            // Bridge in this process — TUNNEL mode means plain sockets (they'll go through VPN
            // because this process is NOT the VPN service process)
            bridge = new TetherBridge(this);
            if ("BYPASS".equals(mode)) {
                bridge.setSocketMode(TetherBridge.SocketMode.BYPASS);
                bridge.getUpstream().start();
            } else {
                bridge.setSocketMode(TetherBridge.SocketMode.TUNNEL);
            }

            socksProxy = new SocksProxy(bridge);
            socksProxy.setPort(socksPort);
            socksProxy.start(bindAddr);

            httpProxy = new HttpProxy(bridge);
            httpProxy.setPort(httpPort);
            httpProxy.start(bindAddr);

            dnsProxy = new DnsProxy(bridge);
            dnsProxy.setPort(dnsPort);
            if (dohUrl != null) dnsProxy.setDohUrl(dohUrl);
            dnsProxy.start(bindAddr);

            rlog("Proxies started on " + bindAddrStr
                    + " SOCKS:" + socksPort + " HTTP:" + httpPort + " DNS:" + dnsPort
                    + " mode=" + mode);

            // Test: check external IP to verify routing
            new Thread(() -> {
                try {
                    java.net.URL url = new java.net.URL("https://api.ipify.org");
                    java.net.HttpURLConnection c = (java.net.HttpURLConnection) url.openConnection();
                    c.setConnectTimeout(5000);
                    c.setReadTimeout(5000);
                    String ip = new String(c.getInputStream().readAllBytes()).trim();
                    c.disconnect();
                    rlog("External IP from :tether process: " + ip);
                } catch (Exception e) {
                    rlog("IP check failed: " + e.getMessage());
                }
            }).start();
        } catch (Exception e) {
            Log.e(TAG, "Failed to start", e);
            rlog("FAILED: " + e.getMessage());
        }

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (socksProxy != null) socksProxy.stop();
        if (httpProxy != null) httpProxy.stop();
        if (dnsProxy != null) dnsProxy.stop();
        if (bridge != null) bridge.stop();
        rlog("Stopped");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
