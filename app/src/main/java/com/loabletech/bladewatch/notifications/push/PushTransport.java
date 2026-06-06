package net.bladewatch.app.notifications.push;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * POSTs an encrypted Web Push payload to a subscription endpoint over a
 * direct connection.
 */
public final class PushTransport {

    public static final class Result {
        public final int status;
        public final String body;
        /** Server-suggested wait before retrying, in seconds. -1 = unspecified. */
        public final int retryAfterSeconds;

        public Result(int status, String body) {
            this(status, body, -1);
        }

        public Result(int status, String body, int retryAfterSeconds) {
            this.status = status;
            this.body = body;
            this.retryAfterSeconds = retryAfterSeconds;
        }

        public boolean expired() { return status == 404 || status == 410; }
        public boolean ok() { return status >= 200 && status < 300; }
        /** True for transient failures worth retrying (5xx, 408, 429). */
        public boolean transientFailure() {
            return status >= 500 || status == 408 || status == 429;
        }
    }

    private PushTransport() {}

    public static Result send(String endpoint, String vapidJwt, String vapidPubKeyB64Url,
                              byte[] aes128gcmBody, int ttlSeconds) throws Exception {
        URL url = new URL(endpoint);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(15_000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/octet-stream");
            conn.setRequestProperty("Content-Encoding", "aes128gcm");
            conn.setRequestProperty("TTL", String.valueOf(ttlSeconds));
            conn.setRequestProperty("Authorization",
                    "vapid t=" + vapidJwt + ", k=" + vapidPubKeyB64Url);
            conn.setFixedLengthStreamingMode(aes128gcmBody.length);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(aes128gcmBody);
            }

            int status = conn.getResponseCode();
            String responseBody = "";
            try {
                java.io.InputStream is = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
                if (is != null) {
                    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[1024];
                    int n;
                    while ((n = is.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        if (out.size() > 4096) break; // cap log size
                    }
                    responseBody = new String(out.toByteArray(), "UTF-8");
                }
            } catch (Exception ignored) {}

            int retryAfter = -1;
            String hdr = conn.getHeaderField("Retry-After");
            if (hdr != null) {
                try { retryAfter = Math.max(0, Integer.parseInt(hdr.trim())); }
                catch (NumberFormatException ignored) {}
            }
            return new Result(status, responseBody, retryAfter);
        } finally {
            conn.disconnect();
        }
    }
}
