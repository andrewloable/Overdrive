package com.overdrive.app.byd.cloud;

import com.overdrive.app.config.UnifiedConfigManager;
import com.overdrive.app.logging.DaemonLogger;

import org.json.JSONObject;

/**
 * Local mirror of the BYD cloud smart-charging schedule.
 *
 * <p>BYD's <code>/control/smartCharge/homePage</code> endpoint returns charging
 * telemetry only — it does NOT echo back the configured schedule fields or
 * <code>smartChargeSwitch</code>. Since there is no documented read endpoint
 * for the schedule itself, we mirror our own writes here so the UI can hydrate
 * across daemon restarts.
 *
 * <p>Schema matches pyBYD's saveOrUpdate inner payload: startChargeTime,
 * endChargeTime, chargeWay, status. Persisted under section "chargingSchedule".
 */
public final class SmartChargeCache {

    private static final String SECTION = "chargingSchedule";
    private static final DaemonLogger logger = DaemonLogger.getInstance("SmartChargeCache");

    private SmartChargeCache() {}

    public static Boolean getEnabled() {
        try {
            JSONObject section = UnifiedConfigManager.loadConfig().optJSONObject(SECTION);
            if (section == null || !section.has("enabled") || section.isNull("enabled")) return null;
            return Boolean.valueOf(section.getBoolean("enabled"));
        } catch (Exception e) {
            logger.info("getEnabled failed: " + e.getMessage());
            return null;
        }
    }

    /** Last-written start time as "HH:MM"; null if unknown. */
    public static String getStartChargeTime() { return readString("startChargeTime"); }

    /** Last-written end time as "HH:MM" or sentinel "full"; null if unknown. */
    public static String getEndChargeTime() { return readString("endChargeTime"); }

    /** Last-written chargeWay: "s" (one-shot), "e" (every day), or "0,1,2,3,4" (Mon=0). Null if unknown. */
    public static String getChargeWay() { return readString("chargeWay"); }

    public static void setEnabled(boolean enabled) {
        try {
            JSONObject section = currentSection();
            section.put("enabled", enabled);
            UnifiedConfigManager.updateSection(SECTION, section);
        } catch (Exception e) {
            logger.info("setEnabled persist failed: " + e.getMessage());
        }
    }

    public static void setSchedule(String startChargeTime, String endChargeTime,
                                    String chargeWay, boolean enabled) {
        try {
            JSONObject section = currentSection();
            section.put("startChargeTime", startChargeTime);
            section.put("endChargeTime", endChargeTime);
            section.put("chargeWay", chargeWay);
            section.put("enabled", enabled);
            UnifiedConfigManager.updateSection(SECTION, section);
        } catch (Exception e) {
            logger.info("setSchedule persist failed: " + e.getMessage());
        }
    }

    private static String readString(String key) {
        try {
            JSONObject section = UnifiedConfigManager.loadConfig().optJSONObject(SECTION);
            if (section == null) return null;
            String v = section.optString(key, null);
            return (v == null || v.isEmpty()) ? null : v;
        } catch (Exception e) {
            logger.info("read " + key + " failed: " + e.getMessage());
            return null;
        }
    }

    private static JSONObject currentSection() {
        JSONObject section = UnifiedConfigManager.loadConfig().optJSONObject(SECTION);
        return section != null ? section : new JSONObject();
    }
}
