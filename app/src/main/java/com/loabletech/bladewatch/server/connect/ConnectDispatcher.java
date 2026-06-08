package net.bladewatch.app.server.connect;

import net.bladewatch.app.daemon.CameraDaemon;
import net.bladewatch.app.server.HttpResponse;

import org.json.JSONObject;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Routes incoming HTTP requests that use the Connect protocol
 * (content-type: application/connect+json) to registered service handlers.
 *
 * <p>Path format: /bladewatch.v1.{ServiceName}/{MethodName}
 *
 * <p>Registration example:
 * <pre>
 *   dispatcher.register("bladewatch.v1.AuthService", "Login", req -> { ... });
 * </pre>
 *
 * <p>Integration in HttpServer — add before other route checks:
 * <pre>
 *   if (path.startsWith("/bladewatch.v1.")) {
 *       connectDispatcher.dispatch(method, path, body, out);
 *       return;
 *   }
 * </pre>
 *
 * <p>Auth middleware runs before this dispatch — no JWT check needed here.
 */
public class ConnectDispatcher {

    private static final String CONTENT_TYPE_CONNECT = "application/connect+json";
    private static final String CONNECT_PROTOCOL_VERSION = "1";

    // Key: "ServiceFqn/MethodName" e.g. "bladewatch.v1.AuthService/Login"
    private final Map<String, ConnectServiceHandler> registry = new HashMap<>();

    /**
     * Register a handler for one RPC method.
     *
     * @param serviceFqn  Fully-qualified service name (e.g. "bladewatch.v1.AuthService").
     * @param methodName  RPC method name (e.g. "Login").
     * @param handler     Implementation to invoke.
     */
    public void register(String serviceFqn, String methodName, ConnectServiceHandler handler) {
        registry.put(serviceFqn + "/" + methodName, handler);
    }

    /**
     * Dispatch a Connect request.
     *
     * <p>Validates Connect headers, looks up the handler, and writes the response.
     * Always writes exactly one HTTP response — callers must not write another.
     *
     * @param method         HTTP method string.
     * @param path           Request path, e.g. "/bladewatch.v1.AuthService/Login".
     * @param body           Raw request body string (may be null or empty).
     * @param contentType    Value of the Content-Type header (may be null).
     * @param connectVersion Value of the Connect-Protocol-Version header (may be null).
     * @param out            OutputStream to write the HTTP response to.
     */
    public void dispatch(String method, String path, String body,
                         String contentType, String connectVersion,
                         String clientIdentity, OutputStream out) {
        try {
            // Only POST is valid for unary Connect calls.
            if (!"POST".equals(method)) {
                sendConnectError(out, 405, "unimplemented", "Connect only accepts POST");
                return;
            }

            // Require Connect-Protocol-Version: 1
            if (!CONNECT_PROTOCOL_VERSION.equals(connectVersion)) {
                sendConnectError(out, 400, "invalid_argument",
                        "Missing or wrong Connect-Protocol-Version header; expected \"1\"");
                return;
            }

            // Require Content-Type: application/connect+json
            if (contentType == null || !contentType.startsWith(CONTENT_TYPE_CONNECT)) {
                sendConnectError(out, 415, "invalid_argument",
                        "Content-Type must be application/connect+json");
                return;
            }

            // Resolve handler from path.  Path is like /bladewatch.v1.Svc/Method.
            String key = path.startsWith("/") ? path.substring(1) : path;
            ConnectServiceHandler handler = registry.get(key);
            if (handler == null) {
                // Extract the service name for a nicer error message.
                int slash = key.indexOf('/');
                String svcName = slash > 0 ? key.substring(0, slash) : key;
                sendConnectError(out, 404, "not_found",
                        "Service not registered: " + svcName);
                return;
            }

            String requestJson = (body == null || body.isEmpty()) ? "{}" : body;
            ConnectResponse response = handler.handle(requestJson, clientIdentity != null ? clientIdentity : "");

            sendConnectSuccess(out, response.body, response.extraHeaders);

        } catch (ConnectException e) {
            try {
                sendConnectError(out, connectCodeToHttpStatus(e.getCode()), e.getCode(), e.getMessage());
            } catch (Exception ignored) {}
        } catch (Exception e) {
            CameraDaemon.log("ConnectDispatcher: unexpected error: " + e);
            try {
                sendConnectError(out, 500, "internal", "An internal error occurred");
            } catch (Exception ignored) {}
        }
    }

    // ==================== HTTP response helpers ====================

    private void sendConnectSuccess(OutputStream out, String jsonBody,
            java.util.List<String> extraHeaders) throws Exception {
        byte[] body = jsonBody.getBytes(StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 200 OK\r\n")
          .append("Content-Type: application/connect+json\r\n")
          .append("Content-Length: ").append(body.length).append("\r\n")
          .append("Connection: close\r\n");
        if (extraHeaders != null) {
            for (String h : extraHeaders) sb.append(h).append("\r\n");
        }
        sb.append("\r\n");
        out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        out.write(body);
        out.flush();
    }

    private void sendConnectError(OutputStream out, int httpStatus, String code, String message)
            throws Exception {
        JSONObject err = new JSONObject();
        err.put("code", code);
        err.put("message", message != null ? message : "");
        byte[] body = err.toString().getBytes(StandardCharsets.UTF_8);
        String statusText = httpStatusText(httpStatus);
        String headers = "HTTP/1.1 " + httpStatus + " " + statusText + "\r\n"
                + "Content-Type: application/connect+json\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n"
                + "\r\n";
        out.write(headers.getBytes(StandardCharsets.UTF_8));
        out.write(body);
        out.flush();
    }

    /** Map Connect error codes to their canonical HTTP status codes. */
    private int connectCodeToHttpStatus(String code) {
        if (code == null) return 500;
        switch (code) {
            case "invalid_argument":    return 400;
            case "unauthenticated":     return 401;
            case "permission_denied":   return 403;
            case "not_found":           return 404;
            case "already_exists":      return 409;
            case "resource_exhausted":  return 429;
            case "unimplemented":       return 501;
            case "unavailable":         return 503;
            case "internal":            return 500;
            default:                    return 500;
        }
    }

    private String httpStatusText(int status) {
        switch (status) {
            case 200: return "OK";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 409: return "Conflict";
            case 415: return "Unsupported Media Type";
            case 429: return "Too Many Requests";
            case 500: return "Internal Server Error";
            case 501: return "Not Implemented";
            case 503: return "Service Unavailable";
            default:  return "Error";
        }
    }
}
