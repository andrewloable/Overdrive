package com.overdrive.app.server;

import org.json.JSONObject;
import java.io.OutputStream;

/**
 * HTTP Response utilities - shared by all handlers.
 */
public class HttpResponse {
    
    public static void sendError(OutputStream out, int code, String message) throws Exception {
        String response = "HTTP/1.1 " + code + " " + message + "\r\n" +
                         "Content-Type: text/plain\r\n" +
                         "Connection: close\r\n\r\n" +
                         message;
        out.write(response.getBytes());
        out.flush();
    }

    public static void sendHtml(OutputStream out, String html) throws Exception {
        byte[] body = html.getBytes("UTF-8");
        String headers = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: text/html; charset=utf-8\r\n" +
                        "Content-Length: " + body.length + "\r\n" +
                        "Connection: close\r\n\r\n";
        out.write(headers.getBytes());
        out.write(body);
        out.flush();
    }

    public static void sendJson(OutputStream out, String json) throws Exception {
        byte[] body = json.getBytes("UTF-8");
        String headers = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: application/json\r\n" +
                        "Cache-Control: no-cache, no-store\r\n" +
                        "Content-Length: " + body.length + "\r\n" +
                        "Connection: close\r\n\r\n";
        out.write(headers.getBytes());
        out.write(body);
        out.flush();
    }
    
    public static void sendJsonSuccess(OutputStream out) throws Exception {
        sendJson(out, "{\"success\":true}");
    }
    
    /**
     * Send CORS preflight response for OPTIONS requests.
     * Browsers send OPTIONS before cross-origin POST/PUT/DELETE with JSON content-type.
     * Without this, the webapp (accessed via external URL/tunnel) cannot save settings.
     */
    public static void sendCorsPreflightResponse(OutputStream out) throws Exception {
        sendError(out, 403, "CORS preflight denied");
        out.flush();
    }
    
    public static void sendJsonError(OutputStream out, String error) throws Exception {
        JSONObject response = new JSONObject();
        response.put("success", false);
        response.put("error", error);
        sendJson(out, response.toString());
    }
    
    /**
     * Send 401 Unauthorized response with JSON body.
     */
    public static void sendUnauthorized(OutputStream out, String json) throws Exception {
        byte[] body = json.getBytes("UTF-8");
        String headers = "HTTP/1.1 401 Unauthorized\r\n" +
                        "Content-Type: application/json\r\n" +
                        "WWW-Authenticate: Bearer realm=\"BYD Champ\"\r\n" +
                        "Content-Length: " + body.length + "\r\n" +
                        "Connection: close\r\n\r\n";
        out.write(headers.getBytes());
        out.write(body);
        out.flush();
    }
    
    /**
     * Send 302 redirect response.
     */
    public static void sendRedirect(OutputStream out, String location) throws Exception {
        String response = "HTTP/1.1 302 Found\r\n" +
                         "Location: " + location + "\r\n" +
                         "Connection: close\r\n\r\n";
        out.write(response.getBytes());
        out.flush();
    }
    
    /**
     * Send JSON response with a single Set-Cookie header for JWT.
     */
    public static void sendJsonWithCookie(OutputStream out, String json, String cookieName, String cookieValue, int maxAgeSeconds) throws Exception {
        sendJsonWithCookie(out, json, cookieName, cookieValue, maxAgeSeconds, false);
    }

    public static void sendJsonWithCookie(OutputStream out, String json, String cookieName, String cookieValue, int maxAgeSeconds, boolean secure) throws Exception {
        sendJsonWithCookies(out, json, new String[] {
                buildCookie(cookieName, cookieValue, maxAgeSeconds, true, secure)
        });
    }

    public static void sendJsonWithCookies(OutputStream out, String json, String[] cookies) throws Exception {
        byte[] body = json.getBytes("UTF-8");
        String headers = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: application/json\r\n" +
                        buildSetCookieHeaders(cookies) +
                        "Content-Length: " + body.length + "\r\n" +
                        "Connection: close\r\n\r\n";
        out.write(headers.getBytes());
        out.write(body);
        out.flush();
    }

    private static String buildCookie(String cookieName, String cookieValue, int maxAgeSeconds, boolean httpOnly, boolean secure) {
        StringBuilder cookie = new StringBuilder();
        cookie.append(cookieName).append("=").append(cookieValue)
              .append("; Path=/; Max-Age=").append(maxAgeSeconds)
              .append("; SameSite=Lax");
        if (httpOnly) {
            cookie.append("; HttpOnly");
        }
        if (secure) {
            cookie.append("; Secure");
        }
        return cookie.toString();
    }

    private static String buildSetCookieHeaders(String[] cookies) {
        if (cookies == null || cookies.length == 0) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (String cookie : cookies) {
            if (cookie == null || cookie.isEmpty()) continue;
            out.append("Set-Cookie: ").append(cookie).append("\r\n");
        }
        return out.toString();
    }
    
    /**
     * Cache directive used for finalized event recordings. Filenames are
     * unique-per-event and the file is immutable once renamed from
     * .mp4.tmp, so a long max-age plus immutable lets the WebView's HTTP
     * cache satisfy repeat playback locally. ETag invalidates if the
     * underlying file is ever replaced.
     */
    private static final String VIDEO_CACHE_CONTROL = "private, max-age=86400, immutable";

    /**
     * Backwards-compat overload — callers that don't compute an ETag get the
     * old "no-cache" behaviour so any future /video/* caller (e.g. live
     * streams) opting out of caching just calls the no-ETag version.
     */
    public static void sendVideo(OutputStream out, java.io.File file) throws Exception {
        sendVideoInternal(out, file, null);
    }

    public static void sendVideo(OutputStream out, java.io.File file, String etag) throws Exception {
        sendVideoInternal(out, file, etag);
    }

    private static void sendVideoInternal(OutputStream out, java.io.File file, String etag) throws Exception {
        if (!file.exists()) {
            sendError(out, 404, "File not found");
            return;
        }

        StringBuilder headers = new StringBuilder();
        headers.append("HTTP/1.1 200 OK\r\n")
               .append("Content-Type: video/mp4\r\n")
               .append("Content-Length: ").append(file.length()).append("\r\n")
               .append("Accept-Ranges: bytes\r\n");
        if (etag != null) {
            headers.append("Cache-Control: ").append(VIDEO_CACHE_CONTROL).append("\r\n")
                   .append("ETag: ").append(etag).append("\r\n");
        } else {
            headers.append("Cache-Control: no-cache\r\n");
        }
        headers.append("Connection: close\r\n\r\n");
        out.write(headers.toString().getBytes());

        // Stream file in chunks
        try (java.io.FileInputStream fis = new java.io.FileInputStream(file)) {
            byte[] buffer = new byte[16384];
            int count;
            while ((count = fis.read(buffer)) != -1) {
                out.write(buffer, 0, count);
            }
        }
        out.flush();
    }

    public static void sendVideoRange(OutputStream out, java.io.File file, long start, long end) throws Exception {
        sendVideoRangeInternal(out, file, start, end, null);
    }

    public static void sendVideoRange(OutputStream out, java.io.File file, long start, long end, String etag) throws Exception {
        sendVideoRangeInternal(out, file, start, end, etag);
    }

    private static void sendVideoRangeInternal(OutputStream out, java.io.File file, long start, long end, String etag) throws Exception {
        if (!file.exists()) {
            sendError(out, 404, "File not found");
            return;
        }

        long fileLength = file.length();
        if (start < 0 || start >= fileLength) {
            sendError(out, 416, "Range Not Satisfiable");
            return;
        }
        if (end < 0 || end >= fileLength) {
            end = fileLength - 1;
        }
        if (end < start) {
            end = start;
        }
        long contentLength = end - start + 1;

        StringBuilder headers = new StringBuilder();
        headers.append("HTTP/1.1 206 Partial Content\r\n")
               .append("Content-Type: video/mp4\r\n")
               .append("Content-Length: ").append(contentLength).append("\r\n")
               .append("Content-Range: bytes ").append(start).append("-").append(end).append("/").append(fileLength).append("\r\n")
               .append("Accept-Ranges: bytes\r\n");
        if (etag != null) {
            headers.append("Cache-Control: ").append(VIDEO_CACHE_CONTROL).append("\r\n")
                   .append("ETag: ").append(etag).append("\r\n");
        } else {
            headers.append("Cache-Control: no-cache\r\n");
        }
        headers.append("Connection: close\r\n\r\n");
        out.write(headers.toString().getBytes());

        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file, "r")) {
            raf.seek(start);
            byte[] buffer = new byte[16384];
            long remaining = contentLength;
            while (remaining > 0) {
                int toRead = (int) Math.min(buffer.length, remaining);
                int read = raf.read(buffer, 0, toRead);
                if (read <= 0) break;
                out.write(buffer, 0, read);
                remaining -= read;
            }
        }
        out.flush();
    }

    /**
     * 304 Not Modified — no body. Echoes the ETag so the client knows the
     * cached entry is still authoritative. Cache-Control reaffirms the
     * caching policy in case the client previously saw no-cache.
     */
    public static void sendNotModified(OutputStream out, String etag) throws Exception {
        StringBuilder headers = new StringBuilder();
        headers.append("HTTP/1.1 304 Not Modified\r\n")
               .append("ETag: ").append(etag).append("\r\n")
               .append("Cache-Control: ").append(VIDEO_CACHE_CONTROL).append("\r\n")
               .append("Connection: close\r\n\r\n");
        out.write(headers.toString().getBytes());
        out.flush();
    }
    
    /**
     * Send an image file with caching headers.
     */
    public static void sendImage(OutputStream out, java.io.File file, String contentType) throws Exception {
        if (!file.exists()) {
            sendError(out, 404, "Image not found");
            return;
        }
        
        String headers = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: " + contentType + "\r\n" +
                        "Content-Length: " + file.length() + "\r\n" +
                        "Cache-Control: public, max-age=86400\r\n" +
                        "Connection: close\r\n\r\n";
        out.write(headers.getBytes());
        
        try (java.io.FileInputStream fis = new java.io.FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = fis.read(buffer)) != -1) {
                out.write(buffer, 0, count);
            }
        }
        out.flush();
    }
    
    /**
     * Send image bytes directly with caching headers.
     */
    public static void sendImageBytes(OutputStream out, byte[] data, String contentType) throws Exception {
        String headers = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: " + contentType + "\r\n" +
                        "Content-Length: " + data.length + "\r\n" +
                        "Cache-Control: public, max-age=86400\r\n" +
                        "Connection: close\r\n\r\n";
        out.write(headers.getBytes());
        out.write(data);
        out.flush();
    }
}
