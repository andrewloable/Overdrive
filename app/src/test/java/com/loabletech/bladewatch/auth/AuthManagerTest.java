package com.overdrive.app.auth;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public class AuthManagerTest {

    @Before
    public void setUp() {
        AuthManager.clearTestState();
    }

    @After
    public void tearDown() {
        AuthManager.clearTestState();
    }

    @Test
    public void generatesValidJwtForCurrentSession() {
        AuthManager.AuthState state = makeState("byd-test", "secret123", 0);
        AuthManager.setTestState(state);

        String jwt = AuthManager.generateJwt();
        Assert.assertNotNull(jwt);

        AuthManager.JwtValidation validation = AuthManager.validateJwt(jwt);
        Assert.assertTrue(validation.valid);
        Assert.assertEquals("byd-test", validation.deviceId);
    }

    @Test
    public void rejectsExpiredJwt() throws Exception {
        AuthManager.AuthState state = makeState("byd-test", "secret123", 0);
        AuthManager.setTestState(state);

        String jwt = buildJwt(state.deviceId, state.deviceSecret, -60, state.tokenEpoch);
        AuthManager.JwtValidation validation = AuthManager.validateJwt(jwt);
        Assert.assertFalse(validation.valid);
        Assert.assertEquals("Token expired", validation.error);
    }

    @Test
    public void rejectsJwtFromOldSessionVersion() throws Exception {
        AuthManager.AuthState state = makeState("byd-test", "secret123", 2);
        AuthManager.setTestState(state);

        String jwt = buildJwt(state.deviceId, state.deviceSecret, 3600, 1);
        AuthManager.JwtValidation validation = AuthManager.validateJwt(jwt);
        Assert.assertFalse(validation.valid);
        Assert.assertEquals("Session rotated", validation.error);
    }

    @Test
    public void regenerateTokenInvalidatesPreviousJwt() throws Exception {
        AuthManager.AuthState state = makeState("byd-test", "secret123", 0);
        AuthManager.setTestState(state);

        String oldJwt = AuthManager.generateJwt();
        Assert.assertTrue(AuthManager.validateJwt(oldJwt).valid);

        String newToken = AuthManager.regenerateToken();
        Assert.assertNotNull(newToken);

        Assert.assertFalse(AuthManager.validateJwt(oldJwt).valid);
        String newJwt = AuthManager.generateJwt();
        Assert.assertTrue(AuthManager.validateJwt(newJwt).valid);
    }

    private AuthManager.AuthState makeState(String deviceId, String secret, long epoch) {
        AuthManager.AuthState state = new AuthManager.AuthState();
        state.deviceId = deviceId;
        state.deviceSecret = secret;
        state.tokenEpoch = epoch;
        state.lastAccess = 0;
        return state;
    }

    private String buildJwt(String deviceId, String secret, long expOffsetSeconds, long epoch) throws Exception {
        long now = System.currentTimeMillis() / 1000;
        String headerJson = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";
        String payloadJson = "{\"sub\":\"" + escapeJson(deviceId) + "\","
                + "\"iat\":" + now + ","
                + "\"exp\":" + (now + expOffsetSeconds) + ","
                + "\"ver\":" + epoch + "}";

        String headerB64 = base64UrlEncode(headerJson.getBytes(StandardCharsets.UTF_8));
        String payloadB64 = base64UrlEncode(payloadJson.getBytes(StandardCharsets.UTF_8));
        String content = headerB64 + "." + payloadB64;
        String sig = hmacSha256(content, secret);
        return content + "." + sig;
    }

    private String hmacSha256(String data, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        return base64UrlEncode(hash);
    }

    private String base64UrlEncode(byte[] data) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }

    private String escapeJson(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"");
    }
}
