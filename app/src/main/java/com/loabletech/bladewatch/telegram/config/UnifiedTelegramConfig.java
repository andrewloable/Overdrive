package com.overdrive.app.telegram.config;

import com.overdrive.app.config.SecretConfigBridge;
import com.overdrive.app.config.UnifiedConfigManager;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.util.Properties;

/**
 * Single source of truth for Telegram bot configuration.
 *
 * Public Telegram config lives in the unified JSON store. The bot token lives
 * in the daemon-owned secret store so it never has to be mirrored into the
 * public config file.
 *
 * Schema (all fields optional unless noted):
 * <pre>
 *   "telegram": {
 *     "botToken":        "secret-store",   // stored separately
 *     "botId":           0,
 *     "botUsername":     "",
 *     "botFirstName":    "",
 *     "ownerChatId":     -1,                // long; -1 = unpaired
 *     "ownerUsername":   "",
 *     "ownerFirstName":  "",
 *     "ownerPairedAt":   0,
 *     "pairPin":         "",                // 6-digit, short-lived
 *     "pairPinExpiry":   0,                 // ms epoch
 *     "videoUploads":    false,
 *     "autoStartAccOff": false,
 *     "criticalAlerts":  true,
 *     "connectivity":    false,
 *     "motionText":      true,
 *     "outputDir":       "",
 *     "apkPath":         ""
 *   }
 * </pre>
 *
 * Legacy migration: on first read after upgrade, any keys still living in
 * {@code /data/local/tmp/telegram_config.properties} (or the now-removed
 * {@code telegram_bot_prefs} / {@code telegram_owner_prefs} EncryptedSharedPrefs)
 * are imported. The properties file is then deleted so it can't drift again.
 * Migration is idempotent — guarded by the {@code _migrated} marker.
 */
public final class UnifiedTelegramConfig {

    public static final String SECTION = "telegram";

    // Field names — kept short and JSON-friendly.
    public static final String K_BOT_TOKEN        = "botToken";
    public static final String K_BOT_ID           = "botId";
    public static final String K_BOT_USERNAME     = "botUsername";
    public static final String K_BOT_FIRST_NAME   = "botFirstName";
    public static final String K_OWNER_CHAT_ID    = "ownerChatId";
    public static final String K_OWNER_USERNAME   = "ownerUsername";
    public static final String K_OWNER_FIRST_NAME = "ownerFirstName";
    public static final String K_OWNER_PAIRED_AT  = "ownerPairedAt";
    public static final String K_PAIR_PIN         = "pairPin";
    public static final String K_PAIR_PIN_EXPIRY  = "pairPinExpiry";
    public static final String K_VIDEO_UPLOADS    = "videoUploads";
    public static final String K_AUTO_START       = "autoStartAccOff";
    public static final String K_CRITICAL_ALERTS  = "criticalAlerts";
    public static final String K_CONNECTIVITY     = "connectivity";
    public static final String K_MOTION_TEXT      = "motionText";
    public static final String K_OUTPUT_DIR       = "outputDir";
    public static final String K_APK_PATH         = "apkPath";

    private static final String K_MIGRATED = "_migrated";
    private static final String LEGACY_PROPS_PATH =
            "/data/local/tmp/telegram_config.properties";

    /**
     * Per-process latch that suppresses repeated migration attempts within
     * one daemon/app lifetime. The persistent {@code _migrated} marker is
     * the canonical signal across restarts; this volatile is just a fast
     * path so every getter call doesn't re-stat the legacy file.
     *
     * Critically, this also stops a forever-loop when the legacy file
     * exists but is unreadable: the first call sets the latch, the
     * unreadable case bails out, and subsequent calls are O(1).
     */
    private static volatile boolean migrationCheckedThisProcess = false;

    private UnifiedTelegramConfig() {}

    // ──────────────────────────── Read ────────────────────────────

    /**
     * Snapshot of the current telegram section. Triggers a one-shot migration
     * from the legacy {@code .properties} file on first read after upgrade.
     */
    public static JSONObject load() {
        migrateLegacyIfNeeded();
        return UnifiedConfigManager.getTelegram();
    }

    /** Plain-text bot token (decrypted). Empty string when unset. */
    public static String getBotToken() {
        String token = SecretConfigBridge.getString(SECTION, K_BOT_TOKEN);
        return token == null ? "" : token;
    }

    public static boolean hasBotToken() {
        String t = getBotToken();
        return t != null && !t.isEmpty();
    }

    public static long getOwnerChatId() {
        return load().optLong(K_OWNER_CHAT_ID, -1);
    }

    public static boolean hasOwner() {
        return getOwnerChatId() > 0;
    }

    public static String getOwnerUsername() {
        return load().optString(K_OWNER_USERNAME, "");
    }

    public static String getOwnerFirstName() {
        return load().optString(K_OWNER_FIRST_NAME, "");
    }

    public static long getOwnerPairedAt() {
        return load().optLong(K_OWNER_PAIRED_AT, 0);
    }

    public static String getBotUsername() {
        return load().optString(K_BOT_USERNAME, "");
    }

    public static String getBotFirstName() {
        return load().optString(K_BOT_FIRST_NAME, "");
    }

    public static long getBotId() {
        return load().optLong(K_BOT_ID, -1);
    }

    public static String getPairPin() {
        return load().optString(K_PAIR_PIN, "");
    }

    public static long getPairPinExpiry() {
        return load().optLong(K_PAIR_PIN_EXPIRY, 0);
    }

    public static boolean isVideoUploads() {
        return load().optBoolean(K_VIDEO_UPLOADS, false);
    }

    public static boolean isAutoStartAccOff() {
        return load().optBoolean(K_AUTO_START, false);
    }

    public static boolean isCriticalAlerts() {
        return load().optBoolean(K_CRITICAL_ALERTS, true);
    }

    public static boolean isConnectivity() {
        return load().optBoolean(K_CONNECTIVITY, false);
    }

    public static boolean isMotionText() {
        return load().optBoolean(K_MOTION_TEXT, true);
    }

    public static String getOutputDir() {
        return load().optString(K_OUTPUT_DIR, "");
    }

    public static String getApkPath() {
        return load().optString(K_APK_PATH, "");
    }

    // ──────────────────────────── Write ────────────────────────────

    /**
     * Persist a token alongside its bot identity. Token is encrypted before
     * write; passing {@code null} or empty for {@code token} clears it (and
     * its bot identity).
     */
    public static boolean setBotToken(String token, long botId,
                                      String botUsername, String botFirstName) {
        JSONObject delta = new JSONObject();
        try {
            if (token == null || token.isEmpty()) {
                delta.put(K_BOT_ID, -1);
                delta.put(K_BOT_USERNAME, "");
                delta.put(K_BOT_FIRST_NAME, "");
            } else {
                delta.put(K_BOT_ID, botId);
                delta.put(K_BOT_USERNAME, botUsername == null ? "" : botUsername);
                delta.put(K_BOT_FIRST_NAME, botFirstName == null ? "" : botFirstName);
            }
        } catch (Exception e) {
            return false;
        }
        boolean secretOk = (token == null || token.isEmpty())
                ? SecretConfigBridge.delete(SECTION, K_BOT_TOKEN)
                : SecretConfigBridge.putString(SECTION, K_BOT_TOKEN, token);
        if (!secretOk) {
            return false;
        }
        return UnifiedConfigManager.updateSection(SECTION, delta);
    }

    /** Persist owner info. Pass {@code chatId <= 0} via {@link #clearOwner()}. */
    public static boolean setOwner(long chatId, String username, String firstName,
                                   long pairedAt) {
        JSONObject delta = new JSONObject();
        try {
            delta.put(K_OWNER_CHAT_ID, chatId);
            delta.put(K_OWNER_USERNAME, username == null ? "" : username);
            delta.put(K_OWNER_FIRST_NAME, firstName == null ? "" : firstName);
            delta.put(K_OWNER_PAIRED_AT, pairedAt);
        } catch (Exception e) {
            return false;
        }
        return UnifiedConfigManager.updateSection(SECTION, delta);
    }

    public static boolean clearOwner() {
        // Also nukes any in-flight pair PIN so a stale code can't bind a new owner.
        JSONObject delta = new JSONObject();
        try {
            delta.put(K_OWNER_CHAT_ID, -1);
            delta.put(K_OWNER_USERNAME, "");
            delta.put(K_OWNER_FIRST_NAME, "");
            delta.put(K_OWNER_PAIRED_AT, 0);
            delta.put(K_PAIR_PIN, "");
            delta.put(K_PAIR_PIN_EXPIRY, 0);
        } catch (Exception e) {
            return false;
        }
        return UnifiedConfigManager.updateSection(SECTION, delta);
    }

    public static boolean setPairPin(String pin, long expiryMs) {
        JSONObject delta = new JSONObject();
        try {
            delta.put(K_PAIR_PIN, pin == null ? "" : pin);
            delta.put(K_PAIR_PIN_EXPIRY, expiryMs);
        } catch (Exception e) {
            return false;
        }
        return UnifiedConfigManager.updateSection(SECTION, delta);
    }

    public static boolean clearPairPin() {
        return setPairPin("", 0);
    }

    /**
     * Idempotent self-heal: if there's no bot token, drop any leftover
     * bot identity, owner, and PIN. Without this, a user who landed in
     * the inconsistent "no token but bot info present" state (legacy
     * migration imported username/first_name without a token, or a
     * partial clear failed mid-write) sees the HTML page render bot
     * info while the integrations card simultaneously says "Not set
     * up" — confusing UX. Returns true if a write happened, false if
     * the section was already clean.
     */
    public static boolean clearOrphanIdentityIfTokenMissing() {
        JSONObject section = load();
        // hasBotToken would re-derive via the secret bridge — cheaper to check
        // the resolved token value directly here.
        String tokenPlain = getBotToken();
        if (tokenPlain != null && !tokenPlain.isEmpty()) return false;
        // Anything to clean?
        boolean dirty = section.optLong(K_BOT_ID, -1) > 0
                || !section.optString(K_BOT_TOKEN, "").isEmpty()
                || !section.optString(K_BOT_USERNAME, "").isEmpty()
                || !section.optString(K_BOT_FIRST_NAME, "").isEmpty()
                || section.optLong(K_OWNER_CHAT_ID, -1) > 0
                || !section.optString(K_OWNER_USERNAME, "").isEmpty()
                || !section.optString(K_OWNER_FIRST_NAME, "").isEmpty()
                || !section.optString(K_PAIR_PIN, "").isEmpty();
        if (!dirty) return false;
        JSONObject delta = new JSONObject();
        try {
            delta.put(K_BOT_ID, -1);
            delta.put(K_BOT_USERNAME, "");
            delta.put(K_BOT_FIRST_NAME, "");
            delta.put(K_OWNER_CHAT_ID, -1);
            delta.put(K_OWNER_USERNAME, "");
            delta.put(K_OWNER_FIRST_NAME, "");
            delta.put(K_OWNER_PAIRED_AT, 0);
            delta.put(K_PAIR_PIN, "");
            delta.put(K_PAIR_PIN_EXPIRY, 0);
        } catch (Exception e) {
            return false;
        }
        return UnifiedConfigManager.updateSection(SECTION, delta);
    }

    /**
     * Wipe everything — token, bot identity, owner, PIN. Notification
     * preferences are preserved (the user's intent for video_uploads etc.
     * outlives a token rotation).
     */
    public static boolean clearAll() {
        JSONObject delta = new JSONObject();
        try {
            delta.put(K_BOT_ID, -1);
            delta.put(K_BOT_USERNAME, "");
            delta.put(K_BOT_FIRST_NAME, "");
            delta.put(K_OWNER_CHAT_ID, -1);
            delta.put(K_OWNER_USERNAME, "");
            delta.put(K_OWNER_FIRST_NAME, "");
            delta.put(K_OWNER_PAIRED_AT, 0);
            delta.put(K_PAIR_PIN, "");
            delta.put(K_PAIR_PIN_EXPIRY, 0);
        } catch (Exception e) {
            return false;
        }
        boolean secretOk = SecretConfigBridge.delete(SECTION, K_BOT_TOKEN);
        if (!secretOk) {
            return false;
        }
        return UnifiedConfigManager.updateSection(SECTION, delta);
    }

    public static boolean setBoolean(String key, boolean value) {
        JSONObject delta = new JSONObject();
        try {
            delta.put(key, value);
        } catch (Exception e) {
            return false;
        }
        return UnifiedConfigManager.updateSection(SECTION, delta);
    }

    public static boolean setString(String key, String value) {
        JSONObject delta = new JSONObject();
        try {
            delta.put(key, value == null ? "" : value);
        } catch (Exception e) {
            return false;
        }
        return UnifiedConfigManager.updateSection(SECTION, delta);
    }

    public static boolean setLaunchPaths(String outputDir, String apkPath) {
        JSONObject delta = new JSONObject();
        try {
            if (outputDir != null) delta.put(K_OUTPUT_DIR, outputDir);
            if (apkPath != null)   delta.put(K_APK_PATH, apkPath);
        } catch (Exception e) {
            return false;
        }
        return UnifiedConfigManager.updateSection(SECTION, delta);
    }

    // ──────────────────────────── Migration ────────────────────────────

    /**
     * One-shot import of any state still sitting in
     * {@code /data/local/tmp/telegram_config.properties}. Idempotent — guarded
     * by a {@code _migrated} marker so re-running is a cheap no-op.
     *
     * The legacy properties file is removed on success. If removal fails (e.g.
     * the calling UID lacks delete permission), we still flip the marker so a
     * later read by the privileged UID can clean up.
     */
    public static synchronized void migrateLegacyIfNeeded() {
        if (migrationCheckedThisProcess) return;

        JSONObject section = UnifiedConfigManager.getTelegram();
        if (section.optBoolean(K_MIGRATED, false)) {
            migrationCheckedThisProcess = true;
            return;
        }

        File legacy = new File(LEGACY_PROPS_PATH);
        if (!legacy.exists()) {
            // Nothing to import. Don't fire a stampMigrated() write just to
            // record that fact — when the app UID can't write to
            // /data/local/tmp, that write fails anyway, and the in-process
            // latch below covers the common case (no legacy file, never
            // had one). On a fresh install the daemon will set the marker
            // when it does its own init.
            migrationCheckedThisProcess = true;
            return;
        }

        Properties props = new Properties();
        try (FileInputStream fis = new FileInputStream(legacy)) {
            props.load(fis);
        } catch (Exception e) {
            // Can't read this session — set the per-process latch so we
            // don't hammer the FS on every getter. Leave the persistent
            // marker unset so a future startup with working perms (e.g.
            // shell-UID daemon vs app-UID this run) still gets a shot.
            migrationCheckedThisProcess = true;
            return;
        }

        JSONObject delta = new JSONObject();
        try {
            String tok = props.getProperty("bot_token", "");
            if (!tok.isEmpty()) {
                // The legacy file holds plaintext; move it into the secret store.
                SecretConfigBridge.putString(SECTION, K_BOT_TOKEN, tok);
                // Bot identity is only meaningful when paired with a token.
                // Importing username/first_name without a token leaves the
                // status endpoint emitting orphan bot info ("Configured as
                // @Foo") even though hasBotToken() returns false — which
                // looks broken to the user. Gate identity import on a
                // present token.
                putIfPresent(props, delta, "bot_username", K_BOT_USERNAME);
                putIfPresent(props, delta, "bot_first_name", K_BOT_FIRST_NAME);
            }

            // The previous TelegramSettingsFragment (pre-refactor) wrote
            // owner_id while the daemon used owner_chat_id. Both could be
            // present from different code paths; prefer chat_id, fall back
            // to id, so users paired only via the native fragment don't
            // lose their owner on upgrade.
            String ownerStr = props.getProperty("owner_chat_id", "");
            if (ownerStr.isEmpty()) ownerStr = props.getProperty("owner_id", "");
            if (!ownerStr.isEmpty()) {
                try { delta.put(K_OWNER_CHAT_ID, Long.parseLong(ownerStr.trim())); }
                catch (NumberFormatException ignored) {}
            }
            putIfPresent(props, delta, "owner_username", K_OWNER_USERNAME);
            putIfPresent(props, delta, "owner_first_name", K_OWNER_FIRST_NAME);

            // A paired_at stamp wasn't written by the legacy daemon. Use
            // file mtime as a best-effort backstop so the UI doesn't show
            // "paired Jan 1 1970".
            if (delta.has(K_OWNER_CHAT_ID) && !section.has(K_OWNER_PAIRED_AT)) {
                delta.put(K_OWNER_PAIRED_AT, legacy.lastModified());
            }

            // PIN — only import if not yet expired, otherwise it's noise.
            String pin = props.getProperty("pair_pin", "");
            long pinExpiry = parseLongOr(props.getProperty("pair_pin_expiry", "0"), 0);
            if (!pin.isEmpty() && pinExpiry > System.currentTimeMillis()) {
                delta.put(K_PAIR_PIN, pin);
                delta.put(K_PAIR_PIN_EXPIRY, pinExpiry);
            }

            putBoolIfPresent(props, delta, "video_uploads",      K_VIDEO_UPLOADS);
            putBoolIfPresent(props, delta, "auto_start_acc_off", K_AUTO_START);
            putBoolIfPresent(props, delta, "critical_alerts",    K_CRITICAL_ALERTS);
            putBoolIfPresent(props, delta, "connectivity",       K_CONNECTIVITY);
            putBoolIfPresent(props, delta, "motion_text",        K_MOTION_TEXT);
            putIfPresent(props, delta, "output_dir", K_OUTPUT_DIR);
            putIfPresent(props, delta, "apk_path",   K_APK_PATH);

            delta.put(K_MIGRATED, true);
        } catch (Exception e) {
            return;
        }

        UnifiedConfigManager.updateSection(SECTION, delta);
        migrationCheckedThisProcess = true;

        // Best-effort delete; if we can't (permission), the marker still
        // suppresses re-import. The next privileged-UID startup will clean
        // up via the cleanup branch below.
        try { legacy.delete(); } catch (Exception ignored) {}
    }

    private static void stampMigrated() {
        JSONObject delta = new JSONObject();
        try { delta.put(K_MIGRATED, true); } catch (Exception ignored) {}
        UnifiedConfigManager.updateSection(SECTION, delta);
    }

    private static void putIfPresent(Properties src, JSONObject dst,
                                     String legacyKey, String unifiedKey)
            throws Exception {
        String v = src.getProperty(legacyKey, "");
        if (!v.isEmpty()) dst.put(unifiedKey, v);
    }

    private static void putBoolIfPresent(Properties src, JSONObject dst,
                                         String legacyKey, String unifiedKey)
            throws Exception {
        String v = src.getProperty(legacyKey);
        if (v == null) return;
        dst.put(unifiedKey, "true".equalsIgnoreCase(v.trim()));
    }

    private static long parseLongOr(String s, long fallback) {
        try { return Long.parseLong(s == null ? "" : s.trim()); }
        catch (Exception e) { return fallback; }
    }
}
