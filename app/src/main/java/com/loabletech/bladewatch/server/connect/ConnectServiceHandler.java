package net.bladewatch.app.server.connect;

/**
 * Handler for one RPC method within a Connect service.
 *
 * <p>Receives the raw JSON request body and returns a {@link ConnectResponse}
 * that carries the JSON body and any extra HTTP headers (e.g. Set-Cookie) to
 * forward to the client.
 *
 * <p>Throw {@link ConnectException} to return a typed Connect error response.
 */
public interface ConnectServiceHandler {

    ConnectResponse handle(String requestJson, String clientIdentity) throws ConnectException;
}
