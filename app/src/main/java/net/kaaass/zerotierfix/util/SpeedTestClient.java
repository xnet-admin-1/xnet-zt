package net.kaaass.zerotierfix.util;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

public class SpeedTestClient {
    public static final String SERVER = "10.121.21.117";
    public static final int PORT = 19980;
    private static final int TIMEOUT = 30000;
    private static final int BUF = 65536;
    private static final int UL_BYTES = 20 * 1024 * 1024;

    public interface Callback {
        void onLatency(double ms);
        void onDownload(double mbps);
        void onUpload(double mbps);
        void onError(String msg);
    }

    private static int readFully(InputStream in, byte[] b, int len) throws Exception {
        int off = 0;
        while (off < len) {
            int n = in.read(b, off, len - off);
            if (n <= 0) return off;
            off += n;
        }
        return off;
    }

    public static void run(Callback cb) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(SERVER, PORT), TIMEOUT);
            s.setSoTimeout(TIMEOUT);
            s.setTcpNoDelay(true);
            s.setReceiveBufferSize(2 * 1024 * 1024);
            s.setSendBufferSize(2 * 1024 * 1024);
            OutputStream out = s.getOutputStream();
            InputStream in = s.getInputStream();
            byte[] buf = new byte[BUF];

            // --- Latency ---
            double totalMs = 0;
            for (int i = 0; i < 5; i++) {
                long t0 = System.nanoTime();
                out.write('P'); out.flush();
                if (in.read() < 0) throw new Exception("ping failed");
                totalMs += (System.nanoTime() - t0) / 1e6;
            }
            cb.onLatency(totalMs / 5);

            // --- Download ---
            out.write('D'); out.flush();
            // Read 4-byte BE length header
            byte[] hdr = new byte[4];
            readFully(in, hdr, 4);
            int dlExpect = ((hdr[0]&0xFF)<<24)|((hdr[1]&0xFF)<<16)|((hdr[2]&0xFF)<<8)|(hdr[3]&0xFF);
            int dlTotal = 0;
            long t0 = System.nanoTime();
            while (dlTotal < dlExpect) {
                int want = Math.min(BUF, dlExpect - dlTotal);
                int n = in.read(buf, 0, want);
                if (n <= 0) break;
                dlTotal += n;
            }
            double dlSec = (System.nanoTime() - t0) / 1e9;
            cb.onDownload(dlSec > 0 ? (dlTotal * 8.0 / 1e6) / dlSec : 0);

            // --- Upload ---
            out.write('U'); out.flush();
            int sent = 0;
            t0 = System.nanoTime();
            while (sent < UL_BYTES) {
                int n = Math.min(BUF, UL_BYTES - sent);
                out.write(buf, 0, n);
                sent += n;
            }
            out.write("DONE".getBytes()); out.flush();
            readFully(in, hdr, 4);
            double ulSec = (System.nanoTime() - t0) / 1e9;
            cb.onUpload(ulSec > 0 ? (sent * 8.0 / 1e6) / ulSec : 0);

        } catch (Exception e) {
            cb.onError(e.getMessage() != null ? e.getMessage() : "Connection failed");
        }
    }
}
