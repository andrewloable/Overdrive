package net.bladewatch.app.server.connect;

import net.bladewatch.app.daemon.CameraDaemon;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Utility for capturing an existing REST handler's output and forwarding it as
 * a Connect response.
 *
 * <p>All existing API handlers write a full HTTP/1.1 response (status line +
 * headers + blank line + body) to their OutputStream. This helper splits the
 * HTTP framing from the payload.
 */
public final class ConnectHandlerUtil {

    private ConnectHandlerUtil() {}

    /** Functional interface for lambdas that write to an OutputStream. */
    public interface HandlerInvoker {
        void invoke(OutputStream out) throws Exception;
    }

    /** Parsed result of a captured REST handler response. */
    private static final class RawCapture {
        final byte[] bodyBytes;
        final String headersSection; // everything before the blank line
        final int httpStatus;        // parsed from the HTTP status line

        RawCapture(byte[] bodyBytes, String headersSection, int httpStatus) {
            this.bodyBytes = bodyBytes;
            this.headersSection = headersSection;
            this.httpStatus = httpStatus;
        }

        /** Extract all Set-Cookie header values from the response headers. */
        List<String> setCookies() {
            List<String> cookies = new ArrayList<>();
            for (String line : headersSection.split("\r\n")) {
                String lower = line.toLowerCase();
                if (lower.startsWith("set-cookie:")) {
                    cookies.add(line.substring("set-cookie:".length()).trim());
                }
            }
            return cookies;
        }
    }

    private static RawCapture captureRaw(HandlerInvoker invoker) throws ConnectException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(4096);
        try {
            invoker.invoke(baos);
            byte[] all = baos.toByteArray();
            // Find \r\n\r\n separator between headers and body
            for (int i = 0; i < all.length - 3; i++) {
                if (all[i] == '\r' && all[i + 1] == '\n'
                        && all[i + 2] == '\r' && all[i + 3] == '\n') {
                    String headers = new String(all, 0, i, StandardCharsets.UTF_8);
                    int status = parseHttpStatus(headers);
                    byte[] body = new byte[all.length - i - 4];
                    System.arraycopy(all, i + 4, body, 0, body.length);
                    return new RawCapture(body, headers, status);
                }
            }
            // No header separator — treat entire output as body
            return new RawCapture(all, "", 200);
        } catch (ConnectException e) {
            throw e;
        } catch (Exception e) {
            CameraDaemon.log("ConnectHandlerUtil: handler error: " + e);
            throw new ConnectException("internal", "An internal error occurred");
        }
    }

    /**
     * Capture a REST handler and return its JSON body as a {@link JSONObject}.
     * Non-object JSON (arrays, scalars) and empty bodies are tolerated: arrays
     * are wrapped in {@code {"items":[...]}}, other non-object values return
     * an empty object.
     *
     * @throws ConnectException with code "internal" on parse failures.
     */
    public static JSONObject capture(HandlerInvoker invoker) throws ConnectException {
        RawCapture raw = captureRaw(invoker);
        try {
            String json = new String(raw.bodyBytes, StandardCharsets.UTF_8).trim();
            if (json.isEmpty()) return new JSONObject();
            if (json.startsWith("{")) return new JSONObject(json);
            if (json.startsWith("[")) {
                JSONObject wrap = new JSONObject();
                wrap.put("items", new org.json.JSONArray(json));
                return wrap;
            }
            return new JSONObject();
        } catch (Exception e) {
            CameraDaemon.log("ConnectHandlerUtil: JSON parse error: " + e);
            throw new ConnectException("internal", "An internal error occurred");
        }
    }

    /**
     * Capture a REST handler and return its JSON body as a String, along with
     * any Set-Cookie headers from the wrapped response (for auth flows).
     * Throws {@link ConnectException} if the wrapped handler returns HTTP 4xx/5xx.
     */
    public static ConnectResponse captureWithCookies(HandlerInvoker invoker)
            throws ConnectException {
        RawCapture raw = captureRaw(invoker);
        if (raw.httpStatus >= 400) {
            String body = new String(raw.bodyBytes, StandardCharsets.UTF_8).trim();
            throw new ConnectException(
                    httpStatusToConnectCode(raw.httpStatus),
                    extractErrorMessage(body));
        }
        try {
            String body = new String(raw.bodyBytes, StandardCharsets.UTF_8).trim();
            if (body.isEmpty()) body = "{}";
            List<String> cookies = raw.setCookies();
            return cookies.isEmpty()
                    ? ConnectResponse.of(body)
                    : ConnectResponse.withCookies(body, cookies);
        } catch (Exception e) {
            CameraDaemon.log("ConnectHandlerUtil: cookie capture error: " + e);
            throw new ConnectException("internal", "An internal error occurred");
        }
    }

    /**
     * Capture a REST handler that returns binary data (e.g. image/jpeg) and
     * encode the bytes as a Base64 string in the given JSON field name.
     *
     * @param fieldName JSON key to store the Base64 bytes under.
     */
    public static ConnectResponse captureBytes(HandlerInvoker invoker, String fieldName)
            throws ConnectException {
        RawCapture raw = captureRaw(invoker);
        if (raw.bodyBytes.length == 0) {
            throw new ConnectException("not_found", "No data returned");
        }
        String b64 = Base64.getEncoder().encodeToString(raw.bodyBytes);
        JSONObject json = new JSONObject();
        try {
            json.put(fieldName, b64);
        } catch (Exception e) {
            throw new ConnectException("internal", "An internal error occurred");
        }
        return ConnectResponse.of(json.toString());
    }

    /**
     * Capture a REST handler and return its raw body as a ConnectResponse.
     * Throws {@link ConnectException} if the wrapped handler returns HTTP 4xx/5xx.
     */
    public static ConnectResponse captureString(HandlerInvoker invoker) throws ConnectException {
        RawCapture raw = captureRaw(invoker);
        if (raw.httpStatus >= 400) {
            String body = new String(raw.bodyBytes, StandardCharsets.UTF_8).trim();
            throw new ConnectException(
                    httpStatusToConnectCode(raw.httpStatus),
                    extractErrorMessage(body));
        }
        String body = new String(raw.bodyBytes, StandardCharsets.UTF_8).trim();
        if (body.isEmpty()) body = "{}";
        return ConnectResponse.of(body);
    }

    /**
     * Extract a required {@code long} field from a JSON request body.
     *
     * @throws ConnectException with code "invalid_argument" if the field is missing or unparseable.
     */
    public static long requireLong(String requestJson, String field) throws ConnectException {
        try {
            long v = new org.json.JSONObject(requestJson).getLong(field);
            return v;
        } catch (Exception e) {
            throw new ConnectException("invalid_argument", "Missing or invalid field: " + field);
        }
    }

    /**
     * Extract a required non-empty {@code String} field from a JSON request body.
     *
     * @throws ConnectException with code "invalid_argument" if the field is missing or blank.
     */
    public static String requireString(String requestJson, String field) throws ConnectException {
        try {
            String v = new org.json.JSONObject(requestJson).getString(field);
            if (v.isEmpty()) throw new Exception("empty");
            return v;
        } catch (ConnectException e) {
            throw e;
        } catch (Exception e) {
            throw new ConnectException("invalid_argument", "Missing or invalid field: " + field);
        }
    }

    private static int parseHttpStatus(String headers) {
        try {
            String statusLine = headers.split("\r\n")[0]; // e.g. "HTTP/1.1 404 Not Found"
            return Integer.parseInt(statusLine.split(" ")[1]);
        } catch (Exception e) {
            return 200;
        }
    }

    private static String httpStatusToConnectCode(int status) {
        switch (status) {
            case 400: return "invalid_argument";
            case 401: return "unauthenticated";
            case 403: return "permission_denied";
            case 404: return "not_found";
            case 409: return "already_exists";
            case 429: return "resource_exhausted";
            default:  return "internal";
        }
    }

    private static String extractErrorMessage(String body) {
        if (body == null || body.isEmpty()) return "Request failed";
        try {
            JSONObject obj = new JSONObject(body);
            String msg = obj.optString("error", null);
            if (msg == null) msg = obj.optString("message", null);
            if (msg != null && !msg.isEmpty()) return msg;
        } catch (Exception ignored) {
            CameraDaemon.log("extractErrorMessage: failed to parse body: " + ignored.getMessage());
        }
        return "Request failed";
    }
}
