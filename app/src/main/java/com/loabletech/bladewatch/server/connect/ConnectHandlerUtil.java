package net.bladewatch.app.server.connect;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;

/**
 * Utility for capturing an existing REST handler's output and extracting the
 * JSON body so it can be forwarded as a Connect response.
 *
 * <p>All existing API handlers write a full HTTP/1.1 response (status line +
 * headers + blank line + JSON body) to their OutputStream. This helper strips
 * the HTTP framing and returns the raw JSON payload.
 */
public final class ConnectHandlerUtil {

    private ConnectHandlerUtil() {}

    /** Functional interface for lambdas that write to an OutputStream. */
    public interface HandlerInvoker {
        void invoke(OutputStream out) throws Exception;
    }

    /**
     * Capture a REST handler's output and return the JSON body.
     *
     * @throws ConnectException with code "internal" on any failure.
     */
    public static JSONObject capture(HandlerInvoker invoker) throws ConnectException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(4096);
        try {
            invoker.invoke(baos);
            String raw = baos.toString("UTF-8");
            // HTTP response format: "HTTP/1.1 200 OK\r\n...\r\n\r\n{body}"
            int bodyStart = raw.indexOf("\r\n\r\n");
            String json = bodyStart >= 0 ? raw.substring(bodyStart + 4) : raw;
            return new JSONObject(json);
        } catch (ConnectException e) {
            throw e;
        } catch (Exception e) {
            throw new ConnectException("internal", "Handler failed: " + e.getMessage());
        }
    }

    /**
     * Capture a REST handler and return the raw JSON string body.
     */
    public static String captureString(HandlerInvoker invoker) throws ConnectException {
        return capture(invoker).toString();
    }
}
