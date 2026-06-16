package net.bladewatch.app.server;

import net.bladewatch.app.auth.AuthManager;
import net.bladewatch.app.daemon.CameraDaemon;

import org.json.JSONObject;

import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * HTTP API handler for authentication endpoints.
 *
 * Endpoints:
 * - GET  /auth/status     - Check auth status (auth required — surfaces deviceId)
 * - POST /auth/token      - Validate device token and get JWT (rate-limited)
 * - POST /auth/logout     - Clear session
 *
 * Rate limiting on /auth/token: 10 attempts per minute per TCP peer, then 30s
 * per-identity lockout; plus a global cap (30 failures / 2 min → 5 min lockout)
 * that is immune to identity rotation. Identity is always the real TCP socket
 * address — never X-Forwarded-For (attacker-controlled).
 */
public class AuthApiHandler {

    // Per-identity rate-limit constants
    private static final int RATE_LIMIT_WINDOW_MS = 60_000;     // 1 minute window
    private static final int RATE_LIMIT_MAX_ATTEMPTS = 10;      // attempts per identity per window
    private static final long RATE_LIMIT_LOCKOUT_MS = 30_000;   // per-identity lockout duration

    // Global rate-limit: caps total failed attempts regardless of identity rotation
    private static final int GLOBAL_FAIL_THRESHOLD = 30;        // failed attempts across all IPs
    private static final long GLOBAL_LOCKOUT_MS = 300_000;      // 5-minute global lockout
    private static final long GLOBAL_WINDOW_MS = 120_000;       // 2-minute counting window

    // Bounded map size: prevents OOM from identity rotation (attacker flooding new IPs)
    private static final int MAX_IDENTITY_BUCKETS = 256;

    private static final ConcurrentHashMap<String, RateLimitBucket> rateLimits = new ConcurrentHashMap<>();

    // Global failure tracking (not per-identity — immune to rotation)
    private static final AtomicLong globalWindowStart = new AtomicLong(System.currentTimeMillis());
    private static final AtomicInteger globalFailCount = new AtomicInteger(0);
    private static volatile long globalLockoutUntil = 0L;

    private static class RateLimitBucket {
        final Deque<Long> attempts = new ArrayDeque<>();
        long lockedUntil = 0L;
    }

    /**
     * Handle auth API requests.
     */
    public static boolean handle(String method, String path, String body, OutputStream out) throws Exception {
        return handle(method, path, body, out, null, false);
    }

    /**
     * Handle auth API requests with rate-limit identity (X-Forwarded-For or socket).
     */
    public static boolean handle(String method, String path, String body, OutputStream out,
                                  String rateLimitIdentity, boolean secureCookie) throws Exception {

        if (path.equals("/auth/status") && method.equals("GET")) {
            return handleStatus(out, rateLimitIdentity);
        }

        if (path.equals("/auth/token") && method.equals("POST")) {
            // Rate-limit token validation to slow down brute-force attempts.
            // Identity is always the real TCP peer socket (set by HttpServer) —
            // never X-Forwarded-For, which is client-controlled.
            String idForLimit = (rateLimitIdentity != null && !rateLimitIdentity.isEmpty())
                ? rateLimitIdentity : "unknown";
            String rateError = checkRateLimit(idForLimit);
            if (rateError != null) {
                JSONObject resp = new JSONObject();
                resp.put("success", false);
                resp.put("error", rateError);
                HttpResponse.sendJson(out, resp.toString());
                return true;
            }
            return handleTokenValidation(body, out, idForLimit, secureCookie);
        }

        if (path.equals("/auth/logout") && method.equals("POST")) {
            return handleLogout(out, secureCookie);
        }

        return false;
    }

    /**
     * @return null if request may proceed, error string if rate limited.
     */
    private static String checkRateLimit(String identity) {
        long now = System.currentTimeMillis();

        // Global lockout check — immune to identity rotation
        if (globalLockoutUntil > now) {
            long secs = (globalLockoutUntil - now) / 1000 + 1;
            return Messages.get("errors.rate_limited_locked_for_seconds", secs);
        }

        // Per-identity bucket (bounded map — evict oldest if full)
        if (rateLimits.size() >= MAX_IDENTITY_BUCKETS && !rateLimits.containsKey(identity)) {
            String oldest = rateLimits.keys().nextElement();
            rateLimits.remove(oldest);
        }
        RateLimitBucket bucket = rateLimits.computeIfAbsent(identity, k -> new RateLimitBucket());
        synchronized (bucket) {
            if (bucket.lockedUntil > now) {
                long secs = (bucket.lockedUntil - now) / 1000 + 1;
                return Messages.get("errors.rate_limited_locked_for_seconds", secs);
            }
            // Drop attempts outside the per-identity window
            long windowStart = now - RATE_LIMIT_WINDOW_MS;
            while (!bucket.attempts.isEmpty() && bucket.attempts.peekFirst() < windowStart) {
                bucket.attempts.pollFirst();
            }
            if (bucket.attempts.size() >= RATE_LIMIT_MAX_ATTEMPTS) {
                bucket.lockedUntil = now + RATE_LIMIT_LOCKOUT_MS;
                bucket.attempts.clear();
                log("Rate limit exceeded for " + identity + " — locked for "
                    + (RATE_LIMIT_LOCKOUT_MS / 1000) + "s");
                return Messages.get("errors.rate_limited_locked_for_seconds", (RATE_LIMIT_LOCKOUT_MS / 1000));
            }
            bucket.attempts.addLast(now);
        }
        return null;
    }

    /**
     * Record a failed login attempt toward the global cap.
     * Call after a validation failure (not for rate-limit rejections).
     */
    static void recordGlobalFailure() {
        long now = System.currentTimeMillis();
        // Reset window if expired
        if (now - globalWindowStart.get() > GLOBAL_WINDOW_MS) {
            globalWindowStart.set(now);
            globalFailCount.set(0);
        }
        int fails = globalFailCount.incrementAndGet();
        if (fails >= GLOBAL_FAIL_THRESHOLD && globalLockoutUntil <= now) {
            globalLockoutUntil = now + GLOBAL_LOCKOUT_MS;
            log("Global brute-force threshold reached (" + fails + " failures) — global lockout for "
                + (GLOBAL_LOCKOUT_MS / 1000) + "s");
        }
    }

    /**
     * Reset the rate-limit bucket for an identity after a successful login.
     */
    private static void clearRateLimit(String identity) {
        if (identity != null) rateLimits.remove(identity);
    }
    
    /**
     * GET /auth/status
     * Returns device status. deviceId is only included for loopback callers
     * (WebView on the same device) — tunnel/LAN callers get status:ok only.
     */
    private static boolean handleStatus(OutputStream out, String identity) throws Exception {
        AuthManager.AuthState state = AuthManager.getState();

        JSONObject response = new JSONObject();
        response.put("status", "ok");

        // Only expose deviceId to loopback callers. Tunnel/LAN callers (non-loopback
        // socket address) must not receive it — it halves the brute-force search space.
        boolean isLoopback = identity != null && identity.contains("127.0.0.1");
        if (isLoopback && state != null) {
            response.put("deviceId", state.deviceId);
        }

        HttpResponse.sendJson(out, response.toString());
        return true;
    }
    
    /**
     * POST /auth/token
     * Validates device token and returns JWT session.
     */
    private static boolean handleTokenValidation(String body, OutputStream out, String rateLimitIdentity, boolean secureCookie) throws Exception {
        JSONObject response = new JSONObject();

        try {
            JSONObject request = new JSONObject(body);
            String token = request.optString("token", "");

            boolean valid = AuthManager.validateDeviceToken(token);

            if (valid) {
                // Successful login — wipe attempt counter so the user gets a
                // fresh 10-attempt budget on their next session.
                clearRateLimit(rateLimitIdentity);

                String jwt = AuthManager.generateJwt();
                AuthManager.AuthState state = AuthManager.getState();
                if (jwt == null || state == null) {
                    // Auth state was invalidated between validateDeviceToken
                    // and here (e.g. concurrent regenerateToken). Treat as
                    // a transient failure rather than NPEing on state.deviceId.
                    response.put("success", false);
                    response.put("error", Messages.get("errors.invalid_device_token"));
                    log("Auth state vanished mid-login — asking client to retry");
                } else {
                    response.put("success", true);
                    response.put("deviceId", state.deviceId);
                    response.put("expiresIn", AuthManager.getJwtExpirySeconds());

                    log("Token validated for device: " + state.deviceId);
                    String sessionCookie = buildCookie("byd_session", jwt, AuthManager.getJwtExpirySeconds(), true, secureCookie);
                    String hintCookie = buildCookie("byd_auth", "1", AuthManager.getJwtExpirySeconds(), false, secureCookie);
                    HttpResponse.sendJsonWithCookies(out, response.toString(), new String[] { sessionCookie, hintCookie });
                    return true;
                }
            } else {
                response.put("success", false);
                response.put("error", Messages.get("errors.invalid_device_token"));
                log("Invalid token attempt from " + rateLimitIdentity);
                recordGlobalFailure();
            }

        } catch (Exception e) {
            response.put("success", false);
            response.put("error", Messages.get("errors.invalid_request_with_detail", e.getMessage()));
        }

        HttpResponse.sendJson(out, response.toString());
        return true;
    }
    
    /**
     * POST /auth/logout
     * Logs out the user. Client should clear stored JWT.
     */
    private static boolean handleLogout(OutputStream out, boolean secureCookie) throws Exception {
        JSONObject response = new JSONObject();
        response.put("success", true);
        response.put("message", Messages.get("messages.logged_out"));

        String expiredSession = buildCookie("byd_session", "", 0, true, secureCookie);
        String expiredHint = buildCookie("byd_auth", "", 0, false, secureCookie);
        HttpResponse.sendJsonWithCookies(out, response.toString(), new String[] { expiredSession, expiredHint });
        return true;
    }

    private static String buildCookie(String name, String value, long maxAgeSeconds, boolean httpOnly, boolean secure) {
        StringBuilder cookie = new StringBuilder();
        cookie.append(name).append("=").append(value)
                .append("; Path=/; Max-Age=").append(maxAgeSeconds)
                .append("; SameSite=Lax");
        if (httpOnly) {
            cookie.append("; HttpOnly");
        }
        if (secure) {
            cookie.append("; Secure");
        }
        return cookie.toString();
    }
    
    private static void log(String message) {
        CameraDaemon.log("AUTH: " + message);
    }
}
