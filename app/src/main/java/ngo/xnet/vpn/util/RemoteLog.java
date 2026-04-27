package ngo.xnet.vpn.util;

import android.util.Log;
import java.io.*;
import java.net.*;
import java.util.UUID;

public class RemoteLog {
    private static final String TAG = "RemoteLog";
    private static final int MAX_LINES = 2000;
    private static final int PORT = 19981;

    private static final String[] lines = new String[MAX_LINES];
    private static int head = 0;
    private static int count = 0;
    private static String apiKey;
    private static ServerSocket server;

    public static synchronized void log(String tag, String msg) {
        String line = System.currentTimeMillis() + " " + tag + " " + msg;
        lines[head] = line;
        head = (head + 1) % MAX_LINES;
        if (count < MAX_LINES) count++;
    }

    private static synchronized String dump() {
        StringBuilder sb = new StringBuilder(count * 80);
        int start = count < MAX_LINES ? 0 : head;
        for (int i = 0; i < count; i++) {
            sb.append(lines[(start + i) % MAX_LINES]).append('\n');
        }
        return sb.toString();
    }

    public static synchronized String getKey() { return apiKey; }

    public static void start() {
        if (server != null) return;
        apiKey = UUID.randomUUID().toString().substring(0, 8);
        Log.w(TAG, "Remote log key: " + apiKey + " port: " + PORT);
        log(TAG, "Remote log started, key=" + apiKey);

        new Thread(() -> {
            try {
                server = new ServerSocket(PORT);
                while (!Thread.interrupted()) {
                    Socket s = server.accept();
                    try {
                        BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream()));
                        String reqLine = r.readLine();
                        if (reqLine == null) { s.close(); continue; }

                        // Parse key from query: GET /logs?key=xxx
                        String response;
                        if (reqLine.contains("/logs") && reqLine.contains("key=" + apiKey)) {
                            String body = dump();
                            response = "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: " + body.length() + "\r\n\r\n" + body;
                        } else if (reqLine.contains("/clear") && reqLine.contains("key=" + apiKey)) {
                            synchronized (RemoteLog.class) { count = 0; head = 0; }
                            response = "HTTP/1.1 200 OK\r\nContent-Length: 7\r\n\r\ncleared";
                        } else {
                            response = "HTTP/1.1 401 Unauthorized\r\nContent-Length: 12\r\n\r\nunauthorized";
                        }
                        s.getOutputStream().write(response.getBytes());
                        s.getOutputStream().flush();
                        s.close();
                    } catch (Exception e) {
                        try { s.close(); } catch (Exception ignored) {}
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Server error: " + e.getMessage());
            }
        }, "RemoteLog").start();
    }

    public static void stop() {
        try { if (server != null) server.close(); } catch (Exception ignored) {}
        server = null;
    }
}
