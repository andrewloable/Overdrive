package com.overdrive.app.byd.cloud;

import com.overdrive.app.byd.cloud.crypto.CredentialCipher;
import com.overdrive.app.config.UnifiedConfigManager;

import org.json.JSONObject;

/**
 * BYD Cloud API configuration.
 * Reads credentials from the bydCloud section of UnifiedConfigManager.
 */
public final class BydCloudConfig {

    private static final String BASE_URL_PREFIX = "https://dilinkappoversea-";
    private static final String BASE_URL_SUFFIX = ".byd.auto";
    private static final String DEFAULT_REGION = "eu";  // Europe (default for most overseas)
    private static final String USER_AGENT = "okhttp/4.12.0";

    public final boolean enabled;
    public final String username;
    public final String loginKey;
    public final String signPassword;
    public final String commandPwd;
    public final String rawPassword;
    public final String vin;
    public final String countryCode;
    public final String language;
    public final String region;        // Server region: eu, in, sg, au, br, etc.
    public final String imeiMd5;
    public final String appInnerVersion;
    public final String appVersion;
    public final boolean cloudDataMerge; // Toggle: merge cloud telemetry into vehicle data
    public final String energyType;      // From vehicle list: PHEV/BEV identifier

    private BydCloudConfig(boolean enabled, String username, String loginKey,
                           String signPassword, String commandPwd, String rawPassword,
                           String vin, String countryCode, String language, String region,
                           boolean cloudDataMerge, String energyType) {
        this.enabled = enabled;
        this.username = username;
        this.loginKey = loginKey;
        this.signPassword = signPassword;
        this.commandPwd = commandPwd;
        this.rawPassword = rawPassword;
        this.vin = vin;
        this.countryCode = countryCode;
        this.language = language;
        this.region = (region != null && !region.isEmpty()) ? region : DEFAULT_REGION;
        this.cloudDataMerge = cloudDataMerge;
        this.energyType = energyType != null ? energyType : "";
        // Device fingerprint derived from username (matches Niek/BYD-re)
        this.imeiMd5 = (username != null && !username.isEmpty())
                ? com.overdrive.app.byd.cloud.crypto.BydCryptoUtils.md5Hex(username)
                : "00000000000000000000000000000000";
        this.appInnerVersion = "323";
        this.appVersion = "3.2.3";
    }

    /**
     * Load config from UnifiedConfigManager.
     * Handles legacy plaintext values transparently.
     */
    public static BydCloudConfig fromUnifiedConfig() {
        JSONObject config = UnifiedConfigManager.loadConfig();
        JSONObject bydCloud = config.optJSONObject("bydCloud");
        if (bydCloud == null) {
            return new BydCloudConfig(false, "", "", "", "", "", "", "NL", "en", DEFAULT_REGION, false, "");
        }

        String storedRawPassword = bydCloud.optString("rawPassword", "");
        String rawPassword = CredentialCipher.decrypt(storedRawPassword);

        // Migrate legacy plaintext to protected form on first read
        if (!storedRawPassword.isEmpty() && !CredentialCipher.isEncrypted(storedRawPassword)) {
            migrateRawPassword(bydCloud, rawPassword);
        }

        return new BydCloudConfig(
                bydCloud.optBoolean("enabled", false),
                bydCloud.optString("username", ""),
                bydCloud.optString("loginKey", ""),
                bydCloud.optString("signPassword", ""),
                bydCloud.optString("commandPwd", ""),
                rawPassword,
                bydCloud.optString("vin", ""),
                bydCloud.optString("countryCode", "NL"),
                bydCloud.optString("language", "en"),
                bydCloud.optString("region", DEFAULT_REGION),
                bydCloud.optBoolean("cloudDataMerge", false),
                bydCloud.optString("energyType", "")
        );
    }

    /**
     * Migrate a legacy plaintext value to protected form.
     */
    private static void migrateRawPassword(JSONObject bydCloud, String plainPassword) {
        try {
            String encrypted = CredentialCipher.encrypt(plainPassword);
            bydCloud.put("rawPassword", encrypted);
            UnifiedConfigManager.updateSection("bydCloud", bydCloud);
        } catch (Exception e) {
            // Best-effort — plaintext still works, will migrate on next save
        }
    }

    /**
     * Check if all required credentials are configured.
     */
    public boolean isConfigured() {
        return enabled
                && !username.isEmpty()
                && !loginKey.isEmpty()
                && !signPassword.isEmpty()
                && !commandPwd.isEmpty();
    }

    /**
     * Check if credentials have been verified (login + VIN + PIN all succeeded).
     */
    public boolean isVerified() {
        return isConfigured() && !vin.isEmpty();
    }

    public String getBaseUrl() {
        return BASE_URL_PREFIX + region + BASE_URL_SUFFIX;
    }

    public String getUserAgent() {
        return USER_AGENT;
    }

    /**
     * Save credentials to UnifiedConfigManager.
     */
    public static void saveCredentials(String username, String loginKey,
                                       String signPassword, String commandPwd,
                                       String rawPassword,
                                       String vin, String countryCode, String language,
                                       String region) {
        saveCredentials(username, loginKey, signPassword, commandPwd, rawPassword,
                vin, countryCode, language, region, "", false);
    }

    public static void saveCredentials(String username, String loginKey,
                                       String signPassword, String commandPwd,
                                       String rawPassword,
                                       String vin, String countryCode, String language,
                                       String region, String energyType,
                                       boolean cloudDataMerge) {
        JSONObject bydCloud = new JSONObject();
        try {
            bydCloud.put("enabled", true);
            bydCloud.put("username", username);
            bydCloud.put("loginKey", loginKey);
            bydCloud.put("signPassword", signPassword);
            bydCloud.put("commandPwd", commandPwd);
            bydCloud.put("rawPassword", CredentialCipher.encrypt(rawPassword));
            bydCloud.put("vin", vin);
            bydCloud.put("countryCode", countryCode);
            bydCloud.put("language", language);
            bydCloud.put("region", region);
            bydCloud.put("cloudDataMerge", cloudDataMerge);
            if (energyType != null && !energyType.isEmpty()) {
                bydCloud.put("energyType", energyType);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to build config JSON", e);
        }
        UnifiedConfigManager.updateSection("bydCloud", bydCloud);
    }

    /**
     * Clear stored credentials.
     */
    public static void clearCredentials() {
        JSONObject bydCloud = new JSONObject();
        try {
            bydCloud.put("enabled", false);
            bydCloud.put("username", "");
            bydCloud.put("loginKey", "");
            bydCloud.put("signPassword", "");
            bydCloud.put("commandPwd", "");
            bydCloud.put("rawPassword", "");
            bydCloud.put("vin", "");
        } catch (Exception ignored) {}
        UnifiedConfigManager.updateSection("bydCloud", bydCloud);
    }
}
