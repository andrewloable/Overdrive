package com.overdrive.app.server;

import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.telegram.config.UnifiedTelegramConfig;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Telegram bot configuration HTTP endpoints.
 *
 * Reads and writes the {@code telegram} section of {@code UnifiedConfigManager}
 * — the same section the native settings fragment and the daemon use. The
 * bot token is encrypted at rest via {@code CredentialCipher}; we decrypt
 * here only when we need to call out to {@code api.telegram.org}.
 *
 * Endpoints:
 *   GET  /api/telegram/status     — bot + owner state for the UI
 *   POST /api/telegram/token      — validate via Telegram getMe + persist
 *   POST /api/telegram/preferences — toggle video_uploads, etc.
 *   POST /api/telegram/pin        — generate 6-digit pairing PIN (10-min TTL)
 *   POST /api/telegram/owner/clear — strip owner_* keys (re-pair flow)
 *   POST /api/telegram/clear      — wipe token + bot info + owner
 */
public class TelegramApiHandler {

    private static final String TAG = "TelegramApi";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);
    private static final long PIN_TTL_MS = 10 * 60 * 1000L; // 10 minutes

    // SecureRandom (not Math.random()) — the PIN gates Telegram pairing,
    // which is the only authentication boundary between an attacker who
    // can reach the bot and our device. Use the same RNG flavor that
    // PairingManager uses for its in-memory PIN.
    private static final java.security.SecureRandom PIN_RNG =
            new java.security.SecureRandom();

    public static boolean handle(String method, String path, String body, OutputStream out) throws Exception {
        String cleanPath = path.contains("?") ? path.substring(0, path.indexOf("?")) : path;

        if (cleanPath.equals("/api/telegram/status") && method.equals("GET")) {
            handleStatus(out);
            return true;
        }
        if (cleanPath.equals("/api/telegram/token") && method.equals("POST")) {
            handleToken(out, body);
            return true;
        }
        if (cleanPath.equals("/api/telegram/preferences") && method.equals("POST")) {
            handlePreferences(out, body);
            return true;
        }
        if (cleanPath.equals("/api/telegram/pin") && method.equals("POST")) {
            handlePin(out);
            return true;
        }
        if (cleanPath.equals("/api/telegram/owner/clear") && method.equals("POST")) {
            handleOwnerClear(out);
            return true;
        }
        if (cleanPath.equals("/api/telegram/clear") && method.equals("POST")) {
            handleClear(out);
            return true;
        }
        return false;
    }

    // ────────────────────────────── Endpoints ──────────────────────────────

    /**
     * GET /api/telegram/status — combines token-presence + pairing state +
     * preferences into a single response so the web UI can paint without
     * three round trips.
     */
    private static void handleStatus(OutputStream out) throws Exception {
        // forceReload before the read so a token/owner write made by the
        // app process (different UID, different mtime tick) is reflected
        // in the web tab without waiting on cache eviction. The cost is
        // one re-parse of a small JSON file per status hit.
        com.overdrive.app.config.UnifiedConfigManager.forceReload();
        boolean configured = UnifiedTelegramConfig.hasBotToken();
        // Self-heal users who were left with orphan bot identity (and/or
        // owner) after a failed migration or partial clear: if there's
        // no token, neither bot info nor owner is meaningful — wipe them
        // so the UI doesn't show "Configured as @Foo" / "Paired with X"
        // alongside a Not-set-up state. Idempotent: same delta becomes a
        // no-op once cleaned.
        if (!configured) {
            UnifiedTelegramConfig.clearOrphanIdentityIfTokenMissing();
        }
        long ownerChatId = UnifiedTelegramConfig.getOwnerChatId();
        boolean paired = configured && ownerChatId > 0;

        JSONObject response = new JSONObject();
        response.put("success", true);
        response.put("configured", configured);
        response.put("paired", paired);
        response.put("enabled", configured && paired);
        // Don't echo the raw token back — the UI never needs it after first
        // validation. Length is enough to confirm the field is set.
        response.put("hasToken", configured);
        if (paired) {
            response.put("ownerChatId", ownerChatId);
            response.put("ownerUsername", UnifiedTelegramConfig.getOwnerUsername());
            response.put("ownerFirstName", UnifiedTelegramConfig.getOwnerFirstName());
        }
        // Cached bot info from last validation (so the UI can show
        // "Connected as @Foo" without an extra round trip to Telegram).
        // Only emit when a token is actually configured — orphan bot
        // identity (username/first_name without a token, possible after
        // a half-completed migration or a failed clear) confused users
        // who saw "Configured as @Foo" while the integration card said
        // "Not set up". Tying the emit to `configured` keeps the UI
        // consistent with hasBotToken().
        if (configured) {
            String botUsername = UnifiedTelegramConfig.getBotUsername();
            String botFirstName = UnifiedTelegramConfig.getBotFirstName();
            if (!botUsername.isEmpty()) response.put("botUsername", botUsername);
            if (!botFirstName.isEmpty()) response.put("botFirstName", botFirstName);
        }

        // Preferences — single source of truth in the unified config.
        response.put("videoUploads",        UnifiedTelegramConfig.isVideoUploads());
        response.put("autoStartAccOff",     UnifiedTelegramConfig.isAutoStartAccOff());
        response.put("criticalAlerts",      UnifiedTelegramConfig.isCriticalAlerts());
        response.put("connectivityUpdates", UnifiedTelegramConfig.isConnectivity());
        response.put("motionText",          UnifiedTelegramConfig.isMotionText());

        // Live pairing PIN (if a PIN flow is in-progress and unexpired).
        String pin = UnifiedTelegramConfig.getPairPin();
        long pinExpiry = UnifiedTelegramConfig.getPairPinExpiry();
        if (!pin.isEmpty() && pinExpiry > System.currentTimeMillis()) {
            response.put("pendingPin", pin);
            response.put("pendingPinExpiresAt", pinExpiry);
        }

        HttpResponse.sendJson(out, response.toString());
    }

    /**
     * POST /api/telegram/token — body { "token": "..." }. Validates via
     * https://api.telegram.org/bot&lt;token&gt;/getMe and persists on success.
     */
    private static void handleToken(OutputStream out, String body) throws Exception {
        JSONObject response = new JSONObject();
        try {
            JSONObject req = new JSONObject(body == null ? "{}" : body);
            String token = req.optString("token", "").trim();
            if (token.isEmpty()) {
                response.put("success", false);
                response.put("error", "Token is empty");
                HttpResponse.sendJson(out, response.toString());
                return;
            }
            // Quick format sanity — Telegram tokens are "<digits>:<base64ish>".
            if (!token.contains(":") || token.length() < 30) {
                response.put("success", false);
                response.put("error", "Invalid token format");
                HttpResponse.sendJson(out, response.toString());
                return;
            }

            JSONObject botInfo = telegramGetMe(token);
            if (botInfo == null) {
                response.put("success", false);
                response.put("error", "Telegram getMe failed — check token + network");
                HttpResponse.sendJson(out, response.toString());
                return;
            }

            UnifiedTelegramConfig.setBotToken(
                    token,
                    botInfo.optLong("id", -1),
                    botInfo.optString("username", ""),
                    botInfo.optString("first_name", "")
            );

            // Mirror the native settings flow: as soon as a valid token is
            // saved, start the bot daemon so the user can immediately send
            // `/pair <PIN>`. Without this, the web tab leaves them with
            // "configured but no process running" until they generate a PIN
            // (which the native flow would also have caught).
            try {
                boolean launched = com.overdrive.app.daemon.telegram
                        .TelegramDaemonLauncher.launchIfNotRunning();
                response.put("daemonLaunched", launched);
            } catch (Exception e) {
                // Don't fail the token-save just because the launch helper
                // tripped — the user can still trigger it manually from
                // the Daemons tab.
                logger.warn("daemon launch after token save failed: " + e.getMessage());
            }

            response.put("success", true);
            response.put("botInfo", botInfo);
            HttpResponse.sendJson(out, response.toString());
        } catch (Exception e) {
            response.put("success", false);
            response.put("error", e.getMessage());
            HttpResponse.sendJson(out, response.toString());
        }
    }

    /**
     * POST /api/telegram/preferences — body keys are partial: only fields
     * present in the body are written. Mirrors every toggle the native
     * TelegramSettingsFragment exposes:
     *   videoUploads        → videoUploads        (TelegramBotDaemon reads)
     *   autoStartAccOff     → autoStartAccOff     (AccSentryDaemon reads)
     *   criticalAlerts      → criticalAlerts
     *   connectivityUpdates → connectivity
     *   motionText          → motionText
     */
    private static void handlePreferences(OutputStream out, String body) throws Exception {
        JSONObject response = new JSONObject();
        try {
            JSONObject req = new JSONObject(body == null ? "{}" : body);
            String[][] map = new String[][]{
                { "videoUploads",        UnifiedTelegramConfig.K_VIDEO_UPLOADS },
                { "autoStartAccOff",     UnifiedTelegramConfig.K_AUTO_START },
                { "criticalAlerts",      UnifiedTelegramConfig.K_CRITICAL_ALERTS },
                { "connectivityUpdates", UnifiedTelegramConfig.K_CONNECTIVITY },
                { "motionText",          UnifiedTelegramConfig.K_MOTION_TEXT }
            };
            for (int i = 0; i < map.length; i++) {
                String jsonKey = map[i][0];
                String unifiedKey = map[i][1];
                if (req.has(jsonKey)) {
                    UnifiedTelegramConfig.setBoolean(unifiedKey, req.optBoolean(jsonKey, false));
                }
            }
            response.put("success", true);
            HttpResponse.sendJson(out, response.toString());
        } catch (Exception e) {
            response.put("success", false);
            response.put("error", e.getMessage());
            HttpResponse.sendJson(out, response.toString());
        }
    }

    /**
     * POST /api/telegram/pin — generate a fresh 6-digit pairing PIN with a
     * 10-minute TTL. Daemon's /pair handler validates against pairPin +
     * pairPinExpiry on the same unified config. Owner not set yet ⇒ user
     * sends "/pair &lt;PIN&gt;" to the bot; daemon writes owner_* on match.
     */
    private static void handlePin(OutputStream out) throws Exception {
        JSONObject response = new JSONObject();
        try {
            if (!UnifiedTelegramConfig.hasBotToken()) {
                response.put("success", false);
                response.put("error", "Set the bot token first");
                HttpResponse.sendJson(out, response.toString());
                return;
            }
            // Generate a 6-digit zero-padded PIN.
            int n = PIN_RNG.nextInt(1_000_000);
            String pin = String.format(java.util.Locale.US, "%06d", n);
            long expiry = System.currentTimeMillis() + PIN_TTL_MS;
            UnifiedTelegramConfig.setPairPin(pin, expiry);

            response.put("success", true);
            response.put("pin", pin);
            response.put("expiresAt", expiry);
            response.put("ttlSeconds", PIN_TTL_MS / 1000);
            HttpResponse.sendJson(out, response.toString());
        } catch (Exception e) {
            response.put("success", false);
            response.put("error", e.getMessage());
            HttpResponse.sendJson(out, response.toString());
        }
    }

    /** POST /api/telegram/owner/clear — unpair without losing the bot token. */
    private static void handleOwnerClear(OutputStream out) throws Exception {
        UnifiedTelegramConfig.clearOwner();

        JSONObject response = new JSONObject();
        response.put("success", true);
        HttpResponse.sendJson(out, response.toString());
    }

    /** POST /api/telegram/clear — full wipe (token + bot info + owner). */
    private static void handleClear(OutputStream out) throws Exception {
        UnifiedTelegramConfig.clearAll();

        JSONObject response = new JSONObject();
        response.put("success", true);
        HttpResponse.sendJson(out, response.toString());
    }

    // ────────────────────────────── Telegram API ──────────────────────────────

    /**
     * Hit https://api.telegram.org/bot&lt;token&gt;/getMe. Returns the parsed
     * {@code result} object on success, or null on any failure. 5-second
     * connect / read timeout so a flaky network doesn't hang the HTTP worker.
     */
    private static JSONObject telegramGetMe(String token) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL("https://api.telegram.org/bot" + token + "/getMe");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            int code = conn.getResponseCode();
            if (code != 200) {
                logger.warn("getMe HTTP " + code);
                return null;
            }
            java.io.InputStream in = conn.getInputStream();
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[2048];
            int n;
            while ((n = in.read(buf)) >= 0) baos.write(buf, 0, n);
            in.close();
            JSONObject json = new JSONObject(baos.toString("UTF-8"));
            if (!json.optBoolean("ok", false)) {
                logger.warn("getMe ok=false: " + json.optString("description", ""));
                return null;
            }
            return json.getJSONObject("result");
        } catch (Exception e) {
            logger.warn("getMe exception: " + e.getMessage());
            return null;
        } finally {
            if (conn != null) try { conn.disconnect(); } catch (Exception ignored) {}
        }
    }
}
