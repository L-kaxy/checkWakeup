package dev.checkwakeup.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** The same bounded HTTP implementation is exercised by JVM integration tests. */
public final class ReportTransport {
    private static final java.util.concurrent.ScheduledExecutorService DEADLINES =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "report-deadline");
                thread.setDaemon(true);
                return thread;
            });
    public interface Observer {
        /** Register the connection for cancellation, or decline a stale configuration. */
        boolean opened(HttpURLConnection connection);
    }

    public static final class Response {
        public final int status;
        public final String body;
        public final String retryAfter;
        Response(int status, String body, String retryAfter) {
            this.status = status;
            this.body = body;
            this.retryAfter = retryAfter;
        }
    }

    private ReportTransport() {}

    public static Response post(String endpoint, String jwe, Observer observer) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        java.util.concurrent.ScheduledFuture<?> deadline = null;
        try {
            byte[] encrypted = jwe.getBytes(StandardCharsets.US_ASCII);
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(8_000);
            connection.setReadTimeout(10_000);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/jose");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Accept-Encoding", "identity");
            connection.setUseCaches(false);
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(encrypted.length);
            if (!observer.opened(connection)) throw new IOException("Cancelled configuration");
            // Also bound a peer that never reads our body or drip-feeds a response.
            deadline = DEADLINES.schedule(connection::disconnect, 30, java.util.concurrent.TimeUnit.SECONDS);
            try (java.io.OutputStream out = connection.getOutputStream()) { out.write(encrypted); }
            int status = connection.getResponseCode();
            return new Response(status, readLimited(status == 200
                    ? connection.getInputStream() : connection.getErrorStream()),
                    connection.getHeaderField("Retry-After"));
        } finally {
            if (deadline != null) deadline.cancel(false);
            connection.disconnect();
        }
    }

    private static String readLimited(InputStream stream) throws IOException {
        if (stream == null) return "";
        try (InputStream input = stream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int size;
            while ((size = input.read(buffer)) != -1) {
                if (out.size() + size > 65536) throw new IOException("Response too large");
                out.write(buffer, 0, size);
            }
            return out.toString("UTF-8");
        }
    }
}
