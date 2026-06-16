package net.bladewatch.app.trips;

import net.bladewatch.app.config.UnifiedConfigManager;
import net.bladewatch.app.logging.DaemonLogger;
import org.json.JSONObject;

/**
 * Persistent configuration for Trip Analytics.
 *
 * Uses UnifiedConfigManager to store config in the "tripAnalytics" section
 * of /data/local/tmp/bladewatch_config.json. Storage settings (storageType,
 * storageLimitMb) are delegated to StorageManager — this class only manages
 * the enabled toggle.
 */
public class TripConfig {

    private static final String TAG = "TripConfig";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);
    private static final String SECTION = "tripAnalytics";

    private boolean enabled = true;
    private double electricityRate = 0;  // Cost per kWh
    private String currency = "";        // Currency symbol (₹, $, €, £)
    // Distance unit preference: "km" (default) or "mi".
    // When "mi", the backend applies MILES_TO_KM conversion on BYD SDK values
    // and the frontend converts km→miles for display. This setting overrides
    // the auto-detected getMileageUnit() from the instrument cluster.
    private String distanceUnit = "km";

    public TripConfig() {
        this.enabled = true;
    }

    /**
     * Load configuration from UnifiedConfigManager.
     *
     * @return true if the section was read successfully, false otherwise
     */
    public boolean load() {
        try {
            JSONObject section = UnifiedConfigManager.loadConfig().optJSONObject(SECTION);
            if (section != null) {
                // Trip analytics is always on — there is no user-facing off switch.
                // A stored enabled=false (from a legacy build or a stale SetConfig)
                // would otherwise make TripAnalyticsManager.init() skip
                // initComponents(), leaving the TripDatabase unopened so every read
                // endpoint returns "Trip database not available" and the dashboard /
                // Trips screen show nothing. Force it true and self-heal the
                // persisted config so the file stops contradicting runtime state.
                boolean stored = section.optBoolean("enabled", true);
                enabled = true;
                electricityRate = section.optDouble("electricityRate", 0);
                currency = section.optString("currency", "");
                distanceUnit = section.optString("distanceUnit", "km");
                logger.info("Config loaded: enabled=" + enabled + " rate=" + electricityRate + " " + currency + " unit=" + distanceUnit);
                if (!stored) {
                    logger.info("Stored enabled=false ignored — trip analytics is always on; self-healing config");
                    save();
                }
                return true;
            } else {
                logger.info("No tripAnalytics section in UnifiedConfigManager, using defaults");
                return false;
            }
        } catch (Exception e) {
            logger.error("Config load error: " + e.getMessage());
            enabled = true;
            return false;
        }
    }

    /**
     * Save current configuration to UnifiedConfigManager.
     *
     * @return true if the config was written successfully, false otherwise
     */
    public boolean save() {
        try {
            JSONObject section = new JSONObject();
            section.put("enabled", enabled);
            section.put("electricityRate", electricityRate);
            section.put("currency", currency);
            section.put("distanceUnit", distanceUnit);
            boolean success = UnifiedConfigManager.updateSection(SECTION, section);
            if (success) {
                logger.info("Config saved to UnifiedConfigManager: enabled=" + enabled);
            }
            return success;
        } catch (Exception e) {
            logger.error("Config save error: " + e.getMessage());
            return false;
        }
    }

    // ==================== GETTERS ====================

    public boolean isEnabled() {
        return enabled;
    }

    public double getElectricityRate() {
        return electricityRate;
    }

    public String getCurrency() {
        return currency;
    }

    public String getDistanceUnit() {
        return distanceUnit;
    }

    // ==================== SETTERS ====================

    public void setEnabled(boolean enabled) {
        // Trip analytics is always on; ignore attempts to disable it.
        this.enabled = true;
    }

    public void setElectricityRate(double rate) {
        this.electricityRate = rate;
    }

    public void setCurrency(String currency) {
        if (currency == null || currency.isEmpty()) { this.currency = ""; return; }
        // Reject HTML-injection chars; cap at 8 chars (enough for any symbol/code)
        if (currency.contains("<") || currency.contains(">") || currency.length() > 8) {
            this.currency = "";
            return;
        }
        this.currency = currency;
    }

    public void setDistanceUnit(String unit) {
        this.distanceUnit = ("mi".equals(unit)) ? "mi" : "km";
    }

    // ==================== UTILITY ====================

    /**
     * Serialize configuration to a JSONObject for API responses.
     */
    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        try {
            json.put("enabled", enabled);
            json.put("electricityRate", electricityRate);
            json.put("currency", currency);
            json.put("distanceUnit", distanceUnit);
        } catch (Exception e) {
            logger.error("toJson error: " + e.getMessage());
        }
        return json;
    }

    @Override
    public String toString() {
        return "TripConfig{enabled=" + enabled + '}';
    }
}
