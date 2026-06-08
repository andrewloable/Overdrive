package net.bladewatch.app.server.connect;

/**
 * Handler for one RPC method within a Connect service.
 *
 * <p>Receives the raw JSON request body and returns a JSON response string.
 * Throw {@link ConnectException} to return a typed Connect error response.
 */
public interface ConnectServiceHandler {

    /**
     * Handle one RPC call.
     *
     * @param requestJson UTF-8 JSON string from the request body (may be "{}").
     * @return JSON string to send as the 200 response body.
     * @throws ConnectException to return a Connect error envelope to the caller.
     */
    String handle(String requestJson) throws ConnectException;
}
