package com.overdrive.app.abrp;

import com.overdrive.app.config.SecretConfigBridge;
import com.overdrive.app.logging.DaemonLogger;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Properties;

/**
 * Configuration manager for ABRP telemetry integration.
 *
 * Reads and writes ABRP settings from /data/local/tmp/abrp_config.properties.
 * Mirrors the pattern from TelegramBotDaemon.loadConfig().
 */
public class AbrpConfig {

    private static final String TAG = "AbrpConfig";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    private static final String CONFIG_PATH = "/data/local/tmp/abrp_config.properties";
    private static final String PROP_USER_TOKEN = "user_token";
    private static final String PROP_ENABLED = "enabled";
    private static final String PROP_CAR_MODEL = "car_model";
    private static final String PROP_UPLOAD_INTERVAL = "upload_interval_seconds";
    private static final String PROP_API_KEY = "api_key";

    private static final boolean DEFAULT_ENABLED = false;
    private static final int DEFAULT_UPLOAD_INTERVAL_SECONDS = 5;

    private String userToken;
    private boolean enabled;
    private String carModel;
    private int uploadIntervalSeconds;
    private String apiKey;

    public AbrpConfig() {
        this.userToken = null;
        this.enabled = DEFAULT_ENABLED;
        this.carModel = null;
        this.uploadIntervalSeconds = DEFAULT_UPLOAD_INTERVAL_SECONDS;
        this.apiKey = null;
    }

    /**
     * Load configuration from the properties file.
     *
     * @return true if the file was read successfully, false otherwise
     */
    public boolean load() {
        try {
            File configFile = new File(CONFIG_PATH);
            if (!configFile.exists()) {
                logger.info("Config file not found: " + CONFIG_PATH);
                return false;
            }

            Properties props = new Properties();
            try (FileInputStream fis = new FileInputStream(configFile)) {
                props.load(fis);
            }

            userToken = props.getProperty(PROP_USER_TOKEN);
            if (userToken != null && userToken.isEmpty()) {
                userToken = null;
            }

            String enabledStr = props.getProperty(PROP_ENABLED);
            enabled = enabledStr != null ? "true".equalsIgnoreCase(enabledStr) : DEFAULT_ENABLED;

            carModel = props.getProperty(PROP_CAR_MODEL);

            boolean migratedSecrets = false;
            userToken = SecretConfigBridge.getString("abrp", PROP_USER_TOKEN);
            if ((userToken == null || userToken.isEmpty()) && props.containsKey(PROP_USER_TOKEN)) {
                String legacyToken = props.getProperty(PROP_USER_TOKEN);
                if (legacyToken != null && !legacyToken.isEmpty()) {
                    userToken = legacyToken;
                    SecretConfigBridge.putString("abrp", PROP_USER_TOKEN, legacyToken);
                    migratedSecrets = true;
                }
            }

            apiKey = SecretConfigBridge.getString("abrp", PROP_API_KEY);
            if ((apiKey == null || apiKey.isEmpty()) && props.containsKey(PROP_API_KEY)) {
                String legacyApiKey = props.getProperty(PROP_API_KEY);
                if (legacyApiKey != null && !legacyApiKey.isEmpty()) {
                    apiKey = legacyApiKey;
                    SecretConfigBridge.putString("abrp", PROP_API_KEY, legacyApiKey);
                    migratedSecrets = true;
                }
            }

            String intervalStr = props.getProperty(PROP_UPLOAD_INTERVAL);
            if (intervalStr != null) {
                try {
                    uploadIntervalSeconds = Integer.parseInt(intervalStr);
                } catch (NumberFormatException e) {
                    logger.warn("Invalid upload_interval_seconds: " + intervalStr + ", using default");
                    uploadIntervalSeconds = DEFAULT_UPLOAD_INTERVAL_SECONDS;
                }
            } else {
                uploadIntervalSeconds = DEFAULT_UPLOAD_INTERVAL_SECONDS;
            }

            if (migratedSecrets) {
                save();
            }

            logger.info("Config loaded: token=" + (isConfigured() ? "***" + getMaskedToken() : "not set")
                    + ", enabled=" + enabled
                    + ", car_model=" + (carModel != null ? carModel : "not set")
                    + ", interval=" + uploadIntervalSeconds + "s");
            return true;
        } catch (Exception e) {
            logger.error("Config load error: " + e.getMessage());
            return false;
        }
    }

    /**
     * Save current configuration to the properties file.
     *
     * @return true if the file was written successfully, false otherwise
     */
    public boolean save() {
        try {
            Properties props = new Properties();
            props.setProperty(PROP_ENABLED, String.valueOf(enabled));
            if (carModel != null) {
                props.setProperty(PROP_CAR_MODEL, carModel);
            }
            props.setProperty(PROP_UPLOAD_INTERVAL, String.valueOf(uploadIntervalSeconds));

            File configFile = new File(CONFIG_PATH);
            try (FileOutputStream fos = new FileOutputStream(configFile)) {
                props.store(fos, "ABRP Configuration");
            }

            if (userToken == null || userToken.isEmpty()) {
                SecretConfigBridge.delete("abrp", PROP_USER_TOKEN);
            } else {
                SecretConfigBridge.putString("abrp", PROP_USER_TOKEN, userToken);
            }
            if (apiKey == null || apiKey.isEmpty()) {
                SecretConfigBridge.delete("abrp", PROP_API_KEY);
            } else {
                SecretConfigBridge.putString("abrp", PROP_API_KEY, apiKey);
            }

            logger.info("Config saved to " + CONFIG_PATH);
            return true;
        } catch (Exception e) {
            logger.error("Config save error: " + e.getMessage());
            return false;
        }
    }

    // ==================== GETTERS ====================

    public String getUserToken() {
        return userToken;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getCarModel() {
        return carModel;
    }

    public int getUploadIntervalSeconds() {
        return uploadIntervalSeconds;
    }

    public String getApiKey() {
        return apiKey;
    }

    /**
     * Returns true if a user token is configured (non-null and non-empty).
     */
    public boolean isConfigured() {
        return userToken != null && !userToken.isEmpty();
    }

    // ==================== SETTERS ====================

    public void setUserToken(String token) {
        this.userToken = token;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void setCarModel(String carModel) {
        this.carModel = carModel;
    }

    public void setUploadIntervalSeconds(int seconds) {
        this.uploadIntervalSeconds = seconds;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    /**
     * Remove the token and disable the service.
     */
    public void deleteToken() {
        this.userToken = null;
        this.enabled = false;
    }

    // ==================== UTILITY ====================

    /**
     * Returns a masked version of the token for display.
     * - Token >= 4 chars: "••••" + last 4 characters
     * - Token < 4 chars: "••••" + full token
     * - No token: empty string
     */
    public String getMaskedToken() {
        if (userToken == null || userToken.isEmpty()) {
            return "";
        }
        if (userToken.length() >= 4) {
            return "••••" + userToken.substring(userToken.length() - 4);
        }
        return "••••" + userToken;
    }

    /**
     * Serialize configuration to a JSONObject for IPC responses.
     * The token is masked in the output.
     */
    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        try {
            json.put(PROP_USER_TOKEN, getMaskedToken());
            json.put(PROP_ENABLED, enabled);
            json.put(PROP_CAR_MODEL, carModel != null ? carModel : "");
            json.put(PROP_UPLOAD_INTERVAL, uploadIntervalSeconds);
            json.put("configured", isConfigured());
        } catch (Exception e) {
            logger.error("toJson error: " + e.getMessage());
        }
        return json;
    }

    @Override
    public String toString() {
        return "AbrpConfig{" +
                "token=" + (isConfigured() ? getMaskedToken() : "not set") +
                ", enabled=" + enabled +
                ", carModel=" + carModel +
                ", interval=" + uploadIntervalSeconds + "s" +
                '}';
    }
}
