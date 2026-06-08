package net.bladewatch.app.server.connect;

/**
 * Carries a Connect protocol error code and message.
 *
 * Connect error codes:
 *   invalid_argument, not_found, permission_denied, unavailable,
 *   internal, unimplemented, unauthenticated, already_exists, resource_exhausted
 */
public class ConnectException extends Exception {

    private final String code;

    public ConnectException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
