package net.bladewatch.app.server.connect;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Carries the JSON body and any extra HTTP response headers (e.g. Set-Cookie)
 * that a Connect service handler wants the dispatcher to forward to the client.
 */
public final class ConnectResponse {

    public final String body;
    /** Additional "Name: Value" header strings to append to the HTTP response. */
    public final List<String> extraHeaders;

    private ConnectResponse(String body, List<String> extraHeaders) {
        this.body = body != null ? body : "{}";
        this.extraHeaders = extraHeaders != null
                ? Collections.unmodifiableList(extraHeaders) : Collections.emptyList();
    }

    /** Wrap a plain JSON body with no extra headers. */
    public static ConnectResponse of(String body) {
        return new ConnectResponse(body, null);
    }

    /** Wrap a JSON body and attach one or more Set-Cookie headers to forward. */
    public static ConnectResponse withCookies(String body, List<String> setCookieValues) {
        List<String> headers = new ArrayList<>();
        for (String v : setCookieValues) {
            headers.add("Set-Cookie: " + v);
        }
        return new ConnectResponse(body, headers);
    }
}
