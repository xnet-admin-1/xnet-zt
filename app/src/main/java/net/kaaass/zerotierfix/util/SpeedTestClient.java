package net.kaaass.zerotierfix.util;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

public class SpeedTestClient {
    public static final String SERVER = "10.121.21.117";
    public static final int PORT = 19980;
    private static final int TIMEOUT = 15000;
    private static final int CHUNK = 64 * 1024;
    private static final int DL_EXPECT = 1024 * 1024;

    public interface Callback {
        void onLatency(double ms);
        void onDownload(double mbps);
        void onUpload(double mbps);
        void onError(String msg);
    }

    public static void run(Callback cb) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(SERVER, PORT), TIMEOUT);
            s.setSoTimeout(TIMEOUT);
            s.setTcpNoDelay(true);
            OutputStream out = s.getOutputStream();
            InputStream in = s.getInputStream();

            // Latency: 5 pings
            double totalMs = 0;
            for (int i = 0; i < 5; i++) {
                long t0 = System.nanoTime();
                out.write('P'); out.flush();
                in.read();
                totalMs += (System.nanoTime() - t0) / 1e6;
            }
            cb.onLatency(totalMs / 5);

            // Download
            out.write('D'); out.flush();
            byte[] buf = new byte[CHUNK];
            int total = 0;
            long t0 = System.nanoTime();
            while (total < DL_EXPECT) {
                int n = in.read(buf);
                if (n <= 0) break;
                total += n;
            }
            // Read trailing 4-byte count
            byte[] tail = new byte[4];
            int tr = 0;
            while (tr < 4) { int n = in.read(tail, tr, 4 - tr); if (n <= 0) break; tr += n; }
            double dlSec = (System.nanoTime() - t0) / 1e9;
            cb.onDownload((total * 8.0 / 1e6) / dlSec);

            // Upload: send 1MB
            int toSend = DL_EXPECT;
            out.write('U'); out.flush();
            byte[] data = new byte[CHUNK];
            int sent = 0;
            t0 = System.nanoTime();
            while (sent < toSend) {
                int n = Math.min(CHUNK, toSend - sent);
                out.write(data, 0, n);
                sent += n;
            }
            out.write("DONE".getBytes()); out.flush();
            // Read 4-byte count
            tr = 0;
            while (tr < 4) { int n = in.read(tail, tr, 4 - tr); if (n <= 0) break; tr += n; }
            double ulSec = (System.nanoTime() - t0) / 1e9;
            cb.onUpload((sent * 8.0 / 1e6) / ulSec);

        } catch (Exception e) {
            cb.onError(e.getMessage());
        }
    }
}
