package net.bladewatch.app.server.connect;

import org.junit.Assert;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * HTTP round-trip tests for the Connect content-type negotiation
 * (epic BladeWatch-y4gf; tasks BladeWatch-9fmd request-accept, BladeWatch-nxms
 * response-echo).
 *
 * <p>Regression context: the dispatcher used to require the STREAMING content
 * type "application/connect+json" and rejected everything else with HTTP 415.
 * But unary Connect RPCs (all the UI uses) send "application/json" — so every
 * call 415'd before reaching a handler. These tests lock the fix: unary
 * "application/json" is accepted and the response Content-Type echoes the
 * request form.
 *
 * <p>The dispatcher's error path builds an org.json.JSONObject, which is the
 * unmocked Android stub in plain JVM unit tests; so the negative (rejected)
 * case is asserted via the pure {@link ConnectDispatcher#isSupportedContentType}
 * helper rather than by driving the 415 response body. The success path uses no
 * org.json, so it is exercised end-to-end through {@link ConnectDispatcher#dispatch}.
 */
public class ConnectContentTypeNegotiationTest {

    // ==================== negotiation helpers (pure) ====================

    @Test
    public void acceptsUnaryJson() {
        Assert.assertTrue(ConnectDispatcher.isSupportedContentType("application/json"));
    }

    @Test
    public void acceptsStreamingConnectJson() {
        Assert.assertTrue(ConnectDispatcher.isSupportedContentType("application/connect+json"));
    }

    @Test
    public void acceptsJsonWithCharsetSuffixAndMixedCase() {
        Assert.assertTrue(ConnectDispatcher.isSupportedContentType("Application/JSON; charset=utf-8"));
        Assert.assertTrue(ConnectDispatcher.isSupportedContentType("application/connect+json; charset=utf-8"));
    }

    @Test
    public void rejectsUnsupportedAndNull() {
        // text/html -> 415 (rejected before the handler runs).
        Assert.assertFalse(ConnectDispatcher.isSupportedContentType("text/html"));
        Assert.assertFalse(ConnectDispatcher.isSupportedContentType("application/grpc"));
        Assert.assertFalse(ConnectDispatcher.isSupportedContentType(null));
    }

    @Test
    public void responseEchoesUnaryJson() {
        Assert.assertEquals("application/json",
                ConnectDispatcher.responseContentType("application/json"));
        Assert.assertEquals("application/json",
                ConnectDispatcher.responseContentType("application/json; charset=utf-8"));
    }

    @Test
    public void responseEchoesStreamingConnectJson() {
        Assert.assertEquals("application/connect+json",
                ConnectDispatcher.responseContentType("application/connect+json"));
    }

    @Test
    public void responseDefaultsToUnaryJsonForUnknownOrNull() {
        // An early error (before content-type validation) still needs a sane
        // unary response type, since that is what unary clients require.
        Assert.assertEquals("application/json", ConnectDispatcher.responseContentType(null));
        Assert.assertEquals("application/json", ConnectDispatcher.responseContentType("text/html"));
    }

    // ==================== in-process round-trip (success path) ====================

    private static final String SVC = "bladewatch.v1.TestService";
    private static final String PATH = "/bladewatch.v1.TestService/Ping";

    private static ConnectDispatcher dispatcherWithPing(boolean[] invokedFlag) {
        ConnectDispatcher d = new ConnectDispatcher();
        d.register(SVC, "Ping", (requestJson, clientIdentity) -> {
            invokedFlag[0] = true;
            return ConnectResponse.of("{\"pong\":true}");
        });
        return d;
    }

    private static String dispatch(ConnectDispatcher d, String contentType) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        d.dispatch("POST", PATH, "{}", contentType, "1", "test", out);
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    @Test
    public void unaryRequest_reachesHandler_andEchoesJsonContentType() {
        boolean[] invoked = {false};
        String resp = dispatch(dispatcherWithPing(invoked), "application/json");
        // Pre-fix this returned 415 and never reached the handler.
        Assert.assertTrue("unary application/json must reach the handler", invoked[0]);
        Assert.assertTrue("status 200 expected: " + resp, resp.startsWith("HTTP/1.1 200"));
        Assert.assertTrue("response Content-Type must echo application/json: " + resp,
                resp.toLowerCase().contains("content-type: application/json\r\n"));
        Assert.assertTrue("body forwarded verbatim: " + resp, resp.contains("{\"pong\":true}"));
    }

    @Test
    public void unaryRequest_withCharsetSuffix_reachesHandler() {
        boolean[] invoked = {false};
        String resp = dispatch(dispatcherWithPing(invoked), "application/json; charset=utf-8");
        Assert.assertTrue("charset suffix must not break acceptance", invoked[0]);
        Assert.assertTrue(resp.startsWith("HTTP/1.1 200"));
    }

    @Test
    public void streamingRequest_reachesHandler_andEchoesConnectJsonContentType() {
        boolean[] invoked = {false};
        String resp = dispatch(dispatcherWithPing(invoked), "application/connect+json");
        Assert.assertTrue(invoked[0]);
        Assert.assertTrue(resp.startsWith("HTTP/1.1 200"));
        Assert.assertTrue("streaming form must be echoed back: " + resp,
                resp.toLowerCase().contains("content-type: application/connect+json\r\n"));
    }
}
