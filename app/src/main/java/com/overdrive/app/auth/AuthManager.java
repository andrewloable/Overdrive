package com.overdrive.app.auth;

import com.overdrive.app.config.UnifiedConfigManager;
import com.overdrive.app.config.SecretConfigBridge;
import com.overdrive.app.daemon.CameraDaemon;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Authentication Manager for BYD Champ.
 *
 * Simple device token authentication - no external OAuth needed.
 * Works with any tunnel (Cloudflare, Zrok, etc.) since no origin validation required.
 *
 * Auth Flow:
 * 1. User enters device token (displayed in app)
 * 2. Token validated → JWT session created
 * 3. JWT used for subsequent requests (24 hour expiry)
 *
 * Security:
 * - Device token = deviceId + secret (e.g., byd-a1b2c3d4-x7k9m2p5)
 * - JWT signed with HMAC-SHA256 using device secret
 *
 * Persistence:
 * Public auth state (device ID, last access, token epoch) stays in the
 * unified config, while the device secret moves to the daemon-owned secret
 * store. App-side callers fall back to localhost IPC when the secret file
 * is not directly readable in-process.
 *
 * Existing devices: on first run after this change, if the unified
 * config has no auth section but the legacy {@code /data/local/tmp/.byd_auth.json}
 * exists, its contents are migrated in-place — so a device that was
 * already logged in keeps its secret and existing JWTs.
 *
 * The device ID file at {@code /data/local/tmp/.overdrive_device_id} is
 * still consulted (it's written by ADB shell during MainActivity startup),
 * so the deviceId remains stable across uninstalls even though the
 * unified config is wiped on factory reset.
 */
public class AuthManager {

    // Section name inside the unified config.
    private static final String CONFIG_SECTION = "auth";
    private static final String KEY_DEVICE_ID = "deviceId";
    private static final String KEY_DEVICE_SECRET = "deviceSecret";
    private static final String KEY_LAST_ACCESS = "lastAccess";

    // Legacy single-purpose auth file. Read-only at this point — kept
    // around purely so existing installs can be migrated forward into
    // the unified config without forcing the user to re-pair.
    private static final String LEGACY_AUTH_FILE = "/data/local/tmp/.byd_auth.json";

    // Device ID file — written via ADB shell from MainActivity, survives
    // app reinstall. Consulted only when the unified config has no
    // deviceId yet (cold-start before MainActivity has synced).
    private static final String DEVICE_ID_FILE = "/data/local/tmp/.overdrive_device_id";

    // JWT settings
    private static final long JWT_EXPIRY_MS = 24 * 60 * 60 * 1000L; // 24 hours
    private static final String JWT_ALGORITHM = "HS256";
    private static final String KEY_TOKEN_EPOCH = "tokenEpoch";

    // In-memory cache. UnifiedConfigManager already mtime-invalidates its
    // own cache, but a tiny per-instance cache lets us hand out the same
    // AuthState reference for repeated calls within a single request and
    // skips a JSON parse on the JWT validation hot path.
    private static volatile AuthState cachedState = null;
    private static volatile long cachedConfigMtime = 0;
    private static volatile AuthState testStateOverride = null;

    // Monotonic counter incremented every time cachedState is replaced.
    // Lets downstream JWT consumers (DaemonHttpClient, WebViewFragment cookie)
    // detect a swap and invalidate their own per-secret caches without
    // having to compare opaque secret material.
    private static volatile long stateVersion = 0;

    /**
     * Auth state persisted to the unified config.
     */
    public static class AuthState {
        public String deviceId;
        public String deviceSecret;      // Random secret for token generation
        public long lastAccess;          // Last successful auth timestamp
        public long tokenEpoch;          // Session rotation counter

        public String getDeviceToken() {
            return deviceId + "-" + deviceSecret;
        }

        /**
         * Get just the secret part (for display in app UI).
         * User combines with device ID shown on login page.
         */
        public String getSecret() {
            return deviceSecret;
        }

        public JSONObject toJson() {
            try {
                JSONObject json = new JSONObject();
                json.put(KEY_DEVICE_ID, deviceId);
                json.put(KEY_LAST_ACCESS, lastAccess);
                json.put(KEY_TOKEN_EPOCH, tokenEpoch);
                return json;
            } catch (Exception e) {
                return new JSONObject();
            }
        }

        public static AuthState fromJson(JSONObject json) {
            AuthState state = new AuthState();
            state.deviceId = json.optString(KEY_DEVICE_ID, "");
            state.deviceSecret = "";
            state.lastAccess = json.optLong(KEY_LAST_ACCESS, 0);
            state.tokenEpoch = json.optLong(KEY_TOKEN_EPOCH, 0);
            return state;
        }
    }

    /**
     * JWT validation result.
     */
    public static class JwtValidation {
        public boolean valid;
        public String deviceId;
        public String error;

        public static JwtValidation success(String deviceId) {
            JwtValidation v = new JwtValidation();
            v.valid = true;
            v.deviceId = deviceId;
            return v;
        }

        public static JwtValidation failure(String error) {
            JwtValidation v = new JwtValidation();
            v.valid = false;
            v.error = error;
            return v;
        }
    }

    // ==================== INITIALIZATION ====================

    /**
     * Initialize auth state. Creates device secret if not exists.
     * Call this on app/daemon startup.
     */
    public static synchronized AuthState initialize() {
        if (testStateOverride != null) {
            cachedState = testStateOverride;
            cachedConfigMtime = 0;
            stateVersion++;
            return cachedState;
        }
        AuthState state = loadFromConfig();

        // Migration: if unified config has no auth yet but the legacy
        // .byd_auth.json exists from a previous version, lift it forward
        // so existing devices don't lose their secret. We only attempt
        // the write when the unified config file is already there and
        // world-rw — which it is on any device that has run the daemon
        // at least once. On a brand-new install where the daemon hasn't
        // run yet, the write will silently fail; the daemon will perform
        // the migration on its first boot.
        if (state == null) {
            AuthState legacy = loadLegacyAuthFile();
            if (legacy != null && legacy.deviceSecret != null && !legacy.deviceSecret.isEmpty()) {
                if (state == null || legacy.deviceId != null) {
                    state = legacy;
                    if (state.deviceId == null || state.deviceId.isEmpty()) {
                        state.deviceId = loadDeviceId();
                    }
                    if (writeToConfig(state)) {
                        log("Migrated auth state from legacy file " + LEGACY_AUTH_FILE);
                    } else {
                        log("Legacy auth state held in-memory; will be migrated when unified config becomes writable");
                    }
                }
            }
        }

        // Still nothing? Mint a fresh state. Critical: only persist if
        // the write actually succeeds. On app-UID processes the unified
        // config file is unwritable until the daemon (UID 2000) creates
        // it with chmod 666. If the write fails we DO NOT cache the
        // generated secret — that would make the app sign JWTs with
        // a secret the daemon will never accept. Instead we return null
        // so callers (e.g. WebViewFragment) retry once the daemon has
        // booted and getState() can pull the canonical value.
        if (state == null || state.deviceSecret == null || state.deviceSecret.isEmpty()) {
            if (state == null) state = new AuthState();
            if (state.deviceId == null || state.deviceId.isEmpty()) {
                state.deviceId = loadDeviceId();
            }
            String candidateSecret = generateSecret(8);
            state.deviceSecret = candidateSecret;
            boolean persisted = writeToConfig(state);
            if (!persisted) {
                log("WARN: cannot persist auth secret (likely app UID before daemon boot) — will defer to daemon");
                // Do NOT cache. Returning null keeps callers in a
                // "retry later" loop instead of locking in a secret
                // that won't agree with whatever the daemon writes.
                cachedState = null;
                cachedConfigMtime = 0;
                return null;
            }
            log("Generated new device secret");
        }

        cachedState = state;
        cachedConfigMtime = UnifiedConfigManager.getLastModified();
        stateVersion++;
        log("Auth initialized. Device: " + state.deviceId);
        return state;
    }

    /**
     * Get current auth state.
     *
     * Reads from the unified config when its mtime has advanced beyond
     * the cached snapshot. UnifiedConfigManager already throttles its
     * own disk reads via the cached-config + mtime check, so this is
     * cheap to call on every JWT validation.
     */
    public static AuthState getState() {
        if (testStateOverride != null) {
            return testStateOverride;
        }
        AuthState cur = cachedState;
        long fileMtime = UnifiedConfigManager.getLastModified();
        if (cur != null && fileMtime != 0 && fileMtime == cachedConfigMtime) {
            return cur;
        }
        // Cache stale or unset — pull from config.
        synchronized (AuthManager.class) {
            // Re-check inside the lock.
            cur = cachedState;
            fileMtime = UnifiedConfigManager.getLastModified();
            if (cur != null && fileMtime != 0 && fileMtime == cachedConfigMtime) {
                return cur;
            }
            AuthState fresh = loadFromConfig();
            if (fresh != null && fresh.deviceSecret != null && !fresh.deviceSecret.isEmpty()) {
                if (cur == null
                        || !equalsSafe(cur.deviceSecret, fresh.deviceSecret)
                        || !equalsSafe(cur.deviceId, fresh.deviceId)) {
                    cachedState = fresh;
                    stateVersion++;
                    log("Auth state refreshed from unified config (mtime=" + fileMtime + ")");
                }
                cachedConfigMtime = fileMtime;
                return cachedState;
            }
            // Config has no auth yet — initialize (handles migration too).
            return initialize();
        }
    }

    private static boolean equalsSafe(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    // ==================== TOKEN VALIDATION ====================

    /**
     * Validate device token.
     * Token format: {deviceId}-{secret}
     */
    public static boolean validateDeviceToken(String token) {
        if (token == null || token.isEmpty()) {
            return false;
        }
        AuthState state = getState();
        if (state == null) {
            return false;
        }
        return token.equals(state.getDeviceToken());
    }

    /**
     * Regenerate device token (invalidates all sessions).
     *
     * Returns the new token on success, or {@code null} if persistence
     * failed (e.g. the unified config file disappeared between read and
     * write, or a cross-UID write race). On failure we deliberately
     * leave the previous cachedState in place — caching a phantom secret
     * here would re-introduce the exact divergence the unified-store
     * refactor was meant to eliminate, this time triggered by the user
     * pressing "Regenerate".
     */
    public static synchronized String regenerateToken() {
        AuthState state = getState();
        if (state == null) {
            state = new AuthState();
            state.deviceId = loadDeviceId();
        } else {
            // Don't mutate the cached AuthState in place — if the write
            // fails, callers reading cachedState concurrently would see
            // a half-applied secret. Snapshot fields onto a new object.
            AuthState fresh = new AuthState();
            fresh.deviceId = state.deviceId;
            fresh.lastAccess = state.lastAccess;
            fresh.tokenEpoch = state.tokenEpoch;
            state = fresh;
        }

        String candidate = generateSecret(8);
        state.deviceSecret = candidate;
        state.tokenEpoch = state.tokenEpoch + 1;

        if (testStateOverride != null) {
            testStateOverride = state;
            cachedState = state;
            stateVersion++;
            log("Token regenerated (test override)");
            return state.getDeviceToken();
        }

        boolean persisted = writeToConfig(state);
        if (!persisted) {
            log("ERROR: regenerateToken failed to persist new secret — keeping previous state");
            return null;
        }

        cachedState = state;
        cachedConfigMtime = UnifiedConfigManager.getLastModified();
        stateVersion++;

        log("Token regenerated");
        return state.getDeviceToken();
    }

    // ==================== JWT MANAGEMENT ====================

    /**
     * Generate a JWT session token.
     */
    public static String generateJwt() {
        AuthState state = getState();
        if (state == null) {
            return null;
        }

        try {
            long now = System.currentTimeMillis() / 1000;
            long exp = now + (JWT_EXPIRY_MS / 1000);
            String headerJson = "{\"alg\":\"" + JWT_ALGORITHM + "\",\"typ\":\"JWT\"}";
            String payloadJson = "{\"sub\":\"" + escapeJson(state.deviceId) + "\","
                    + "\"iat\":" + now + ","
                    + "\"exp\":" + exp + ","
                    + "\"ver\":" + state.tokenEpoch + "}";

            String headerB64 = base64UrlEncode(headerJson.getBytes(StandardCharsets.UTF_8));
            String payloadB64 = base64UrlEncode(payloadJson.getBytes(StandardCharsets.UTF_8));
            String content = headerB64 + "." + payloadB64;

            String signature = hmacSha256(content, state.deviceSecret);

            // We deliberately do NOT bump lastAccess on every JWT mint:
            // the previous implementation rewrote the auth file on every
            // call (~1Hz from /status polling) which thrashed the unified
            // config and bumped its mtime, defeating the mtime-based
            // cache. lastAccess wasn't read by anything load-bearing.
            return content + "." + signature;

        } catch (Exception e) {
            log("JWT generation error: " + e.getMessage());
            return null;
        }
    }

    /**
     * Mint a single-purpose thumb token for a given filename. Compact HS256
     * over the existing device secret with claims {@code sub=filename} and
     * {@code exp=now+ttlSec}. The token can be carried as a {@code ?t=}
     * query param so browsers fetching the thumbnail (Web Push notification
     * service worker, FCM image fetch, iOS WebKit notification body) don't
     * need to send Authorization headers — useful when the URL ends up in
     * the OS-level notification banner where headers are not configurable.
     */
    public static String signThumbToken(String filename, long ttlSec) {
        AuthState state = getState();
        if (state == null || filename == null) return null;
        try {
            long now = System.currentTimeMillis() / 1000;
            String headerJson = "{\"alg\":\"" + JWT_ALGORITHM + "\",\"typ\":\"THM\"}";
            String payloadJson = "{\"sub\":\"" + escapeJson(filename) + "\","
                    + "\"iat\":" + now + ","
                    + "\"exp\":" + (now + ttlSec) + "}";
            String headerB64 = base64UrlEncode(headerJson.getBytes(StandardCharsets.UTF_8));
            String payloadB64 = base64UrlEncode(payloadJson.getBytes(StandardCharsets.UTF_8));
            String content = headerB64 + "." + payloadB64;
            String signature = hmacSha256(content, state.deviceSecret);
            return content + "." + signature;
        } catch (Exception e) {
            log("Thumb token sign error: " + e.getMessage());
            return null;
        }
    }

    /**
     * Validate a thumb token against an expected filename. Returns true iff
     * signature matches the device secret, {@code typ=="THM"},
     * {@code sub==filename}, and {@code exp} is in the future.
     */
    public static boolean validateThumbToken(String filename, String token) {
        if (filename == null || token == null || token.isEmpty()) return false;
        AuthState state = getState();
        if (state == null) return false;
        String[] parts = token.split("\\.");
        if (parts.length != 3) return false;
        try {
            String content = parts[0] + "." + parts[1];
            String expectedSig = hmacSha256(content, state.deviceSecret);
            if (!expectedSig.equals(parts[2])) return false;
            String headerJson = new String(base64UrlDecode(parts[0]), StandardCharsets.UTF_8);
            if (!"THM".equals(extractJsonString(headerJson, "typ"))) return false;
            String payloadJson = new String(base64UrlDecode(parts[1]), StandardCharsets.UTF_8);
            if (!filename.equals(extractJsonString(payloadJson, "sub"))) return false;
            long exp = extractJsonLong(payloadJson, "exp", 0);
            return System.currentTimeMillis() / 1000 <= exp;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Invalidate cached auth state.
     * Called via IPC when app regenerates token.
     * Next JWT validation will reload from the unified config.
     */
    public static synchronized void invalidateCache() {
        cachedState = null;
        cachedConfigMtime = 0;
        stateVersion++;
        log("Auth cache invalidated - will reload on next validation");
    }

    /**
     * Monotonic counter that bumps every time the cached auth state is
     * replaced. Callers that cache JWTs derived from the state (so they
     * don't pay HMAC cost per request) can pin their cache entry to a
     * specific stateVersion and invalidate when this number moves on.
     */
    public static long getStateVersion() {
        return stateVersion;
    }

    /**
     * Validate a JWT and extract claims.
     */
    public static JwtValidation validateJwt(String jwt) {
        if (jwt == null || jwt.isEmpty()) {
            return JwtValidation.failure("No token provided");
        }

        if (jwt.startsWith("Bearer ")) {
            jwt = jwt.substring(7);
        }

        String[] parts = jwt.split("\\.");
        if (parts.length != 3) {
            return JwtValidation.failure("Invalid token format");
        }

        AuthState state = getState();
        if (state == null) {
            return JwtValidation.failure("Auth not initialized");
        }

        try {
            String content = parts[0] + "." + parts[1];
            String expectedSig = hmacSha256(content, state.deviceSecret);

            if (!expectedSig.equals(parts[2])) {
                log("JWT signature mismatch - token may have been regenerated");
                return JwtValidation.failure("Invalid signature");
            }

            String payloadJson = new String(base64UrlDecode(parts[1]), StandardCharsets.UTF_8);
            long exp = extractJsonLong(payloadJson, "exp", Long.MIN_VALUE);
            if (exp == Long.MIN_VALUE) {
                return JwtValidation.failure("Token validation error: missing exp");
            }
            if (System.currentTimeMillis() / 1000 > exp) {
                return JwtValidation.failure("Token expired");
            }

            long tokenEpoch = extractJsonLong(payloadJson, "ver", 0);
            if (tokenEpoch != state.tokenEpoch) {
                return JwtValidation.failure("Session rotated");
            }

            String tokenDeviceId = extractJsonString(payloadJson, "sub");
            if (tokenDeviceId == null || tokenDeviceId.isEmpty()) {
                return JwtValidation.failure("Token validation error: missing sub");
            }
            if (!tokenDeviceId.equals(state.deviceId)) {
                return JwtValidation.failure("Device mismatch");
            }

            return JwtValidation.success(tokenDeviceId);

        } catch (Exception e) {
            return JwtValidation.failure("Token validation error: " + e.getMessage());
        }
    }

    // ==================== UNIFIED CONFIG I/O ====================

    /**
     * Load auth state from the unified config. Returns null if the auth
     * section is absent or has no secret (treat as "not initialized").
     */
    private static AuthState loadFromConfig() {
        try {
            JSONObject all = UnifiedConfigManager.loadConfig();
            JSONObject section = all.optJSONObject(CONFIG_SECTION);
            String secret = SecretConfigBridge.getString(CONFIG_SECTION, KEY_DEVICE_SECRET);
            if (section == null) {
                if (secret == null || secret.isEmpty()) return null;
                AuthState state = new AuthState();
                state.deviceId = loadDeviceId();
                state.deviceSecret = secret;
                return state;
            }
            if (secret == null || secret.isEmpty()) return null;
            AuthState state = AuthState.fromJson(section);
            state.deviceSecret = secret;
            if (state.tokenEpoch < 0) state.tokenEpoch = 0;
            return state;
        } catch (Exception e) {
            log("Failed to load auth from unified config: " + e.getMessage());
            return null;
        }
    }

    /**
     * Persist auth state to the unified config. UnifiedConfigManager
     * handles the world-rw chmod and atomic-rename write, so both the
     * daemon (UID 2000) and the app process (UID 10xxx) see the new
     * value once the file has been created.
     *
     * Important caveat: UnifiedConfigManager.updateSection mutates its
     * in-memory cache in-place BEFORE the disk write, so when the disk
     * write fails (cross-UID, before the daemon has created the file)
     * the in-memory cache can retain a stale auth section. We force a
     * reload on failure so the next reader does not keep a phantom state.
     */
    private static boolean writeToConfig(AuthState state) {
        try {
            if (state.deviceSecret == null || state.deviceSecret.isEmpty()) {
                if (!SecretConfigBridge.delete(CONFIG_SECTION, KEY_DEVICE_SECRET)) {
                    return false;
                }
            } else if (!SecretConfigBridge.putString(CONFIG_SECTION, KEY_DEVICE_SECRET, state.deviceSecret)) {
                return false;
            }

            boolean ok = UnifiedConfigManager.updateSection(CONFIG_SECTION, state.toJson());
            if (ok) {
                cachedConfigMtime = UnifiedConfigManager.getLastModified();
                return true;
            }

            log("Failed to persist public auth state; rolling back secret write");
            if (state.deviceSecret == null || state.deviceSecret.isEmpty()) {
                SecretConfigBridge.putString(CONFIG_SECTION, KEY_DEVICE_SECRET, "");
            } else {
                SecretConfigBridge.delete(CONFIG_SECTION, KEY_DEVICE_SECRET);
            }
            UnifiedConfigManager.forceReload();
            return false;
        } catch (Exception e) {
            log("Failed to write auth to unified config: " + e.getMessage());
            try { UnifiedConfigManager.forceReload(); } catch (Exception ignored) {}
            return false;
        }
    }

    /**
     * Read the legacy single-purpose auth file. Used exactly once during
     * migration so devices that were paired before this change keep
     * their secret.
     */
    private static AuthState loadLegacyAuthFile() {
        try {
            File file = new File(LEGACY_AUTH_FILE);
            if (!file.exists() || !file.canRead()) return null;

            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
            }
            String content = sb.toString().trim();
            if (content.isEmpty()) return null;

            JSONObject json = new JSONObject(content);
            return AuthState.fromJson(json);
        } catch (Exception e) {
            log("Failed to read legacy auth file: " + e.getMessage());
            return null;
        }
    }

    private static String loadDeviceId() {
        try {
            File file = new File(DEVICE_ID_FILE);
            if (file.exists()) {
                try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                    String id = reader.readLine();
                    if (id != null && id.startsWith("byd-")) {
                        return id.trim();
                    }
                }
            }
        } catch (Exception e) {
            log("Error reading device ID file: " + e.getMessage());
        }
        // File doesn't exist yet — temp ID. MainActivity writes the real
        // file via ADB shell on app launch; the next reconcile in
        // getState() picks up the canonical ID from the unified config.
        String tempId = "byd-" + generateSecret(8);
        log("Device ID file not found, using temporary ID: " + tempId);
        return tempId;
    }

    // ==================== CRYPTO UTILS ====================

    private static String generateSecret(int length) {
        String chars = "abcdefghijklmnopqrstuvwxyz0123456789";
        SecureRandom random = new SecureRandom();
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(chars.charAt(random.nextInt(chars.length())));
        }
        return sb.toString();
    }

    private static String hmacSha256(String data, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        SecretKeySpec keySpec = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        mac.init(keySpec);
        byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        return base64UrlEncode(hash);
    }

    private static String base64UrlEncode(byte[] data) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }

    private static byte[] base64UrlDecode(String data) {
        return Base64.getUrlDecoder().decode(data);
    }

    private static String escapeJson(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String extractJsonString(String json, String key) {
        if (json == null || key == null) return null;
        String needle = "\"" + key + "\"";
        int keyIndex = json.indexOf(needle);
        if (keyIndex < 0) return null;
        int colon = json.indexOf(':', keyIndex + needle.length());
        if (colon < 0) return null;
        int start = colon + 1;
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) start++;
        if (start >= json.length() || json.charAt(start) != '"') return null;
        StringBuilder out = new StringBuilder();
        boolean escape = false;
        for (int i = start + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escape) {
                out.append(c);
                escape = false;
                continue;
            }
            if (c == '\\') {
                escape = true;
                continue;
            }
            if (c == '"') {
                return out.toString();
            }
            out.append(c);
        }
        return null;
    }

    private static long extractJsonLong(String json, String key, long defaultValue) {
        if (json == null || key == null) return defaultValue;
        String needle = "\"" + key + "\"";
        int keyIndex = json.indexOf(needle);
        if (keyIndex < 0) return defaultValue;
        int colon = json.indexOf(':', keyIndex + needle.length());
        if (colon < 0) return defaultValue;
        int start = colon + 1;
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) start++;
        int end = start;
        while (end < json.length()) {
            char c = json.charAt(end);
            if ((c >= '0' && c <= '9') || c == '-' || c == '+') {
                end++;
                continue;
            }
            break;
        }
        if (end == start) return defaultValue;
        try {
            return Long.parseLong(json.substring(start, end));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static void log(String message) {
        CameraDaemon.log("AUTH: " + message);
    }

    static void setTestState(AuthState state) {
        testStateOverride = state;
    }

    static void clearTestState() {
        testStateOverride = null;
    }

    public static long getJwtExpirySeconds() {
        return JWT_EXPIRY_MS / 1000L;
    }
}
