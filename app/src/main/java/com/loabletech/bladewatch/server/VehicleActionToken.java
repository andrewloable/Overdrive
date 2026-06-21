package net.bladewatch.app.server;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Short-lived action tokens for /api/vehicle/* POST endpoints (uy93.5).
 *
 * A valid bearer JWT alone over LAN cannot actuate the car: the caller must
 * also present a fresh action token bound to the device secret.
 *
 * The loopback (WebView) path is exempt: tokens are only required for
 * non-loopback (LAN/tunnel) callers in HttpServer.handleClient().
 *
 * Token format: "<unix_seconds>.<base64url_hmac_sha256>"
 * Lifetime: WINDOW_SECONDS from issuance.
 *
 * Tokens are single-second-granularity (a fresh call in the same wall-clock
 * second produces the same token). This is intentional: the JS can call
 * GET /api/vehicle/action-token and immediately POST the command — no race.
 */
public final class VehicleActionToken {

    static final int WINDOW_SECONDS = 30;
    private static final String HMAC_ALG = "HmacSHA256";

    // ponytail: test seam — null = real clock; non-null = injected epoch-seconds
    static volatile Long clockOverrideForTest = null;

    private VehicleActionToken() {}

    public static String issue(String deviceSecret) {
        long ts = nowSeconds();
        String tsPart = Long.toString(ts);
        return tsPart + "." + sign(tsPart, deviceSecret);
    }

    public static boolean validate(String token, String deviceSecret) {
        if (token == null || token.isEmpty() || deviceSecret == null || deviceSecret.isEmpty()) {
            return false;
        }
        int dot = token.indexOf('.');
        if (dot < 1 || dot == token.length() - 1) return false;
        String tsPart = token.substring(0, dot);
        String sigPart = token.substring(dot + 1);
        long ts;
        try {
            ts = Long.parseLong(tsPart);
        } catch (NumberFormatException e) {
            return false;
        }
        long age = nowSeconds() - ts;
        if (age < 0 || age > WINDOW_SECONDS) return false;
        String expected = sign(tsPart, deviceSecret);
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                sigPart.getBytes(StandardCharsets.UTF_8));
    }

    private static long nowSeconds() {
        Long override = clockOverrideForTest;
        return override != null ? override : (System.currentTimeMillis() / 1000L);
    }

    private static String sign(String data, String secret) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALG);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALG));
            byte[] bytes = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().withoutPadding().encodeToString(bytes);
        } catch (Exception e) {
            return "";
        }
    }
}
