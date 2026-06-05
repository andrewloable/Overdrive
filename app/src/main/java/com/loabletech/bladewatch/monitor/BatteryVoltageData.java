package com.overdrive.app.monitor;

/**
 * Data model for 12V battery voltage level status.
 * 
 * Represents the battery voltage level as reported by BYDAutoBodyworkDevice.
 * This is a status indicator (LOW/NORMAL/INVALID), not the actual voltage in volts.
 */
public class BatteryVoltageData {
    
    // Constants from BYDAutoBodyworkDevice
    public static final int BODYWORK_BATTERY_VOLTAGE_LEVEL_LOW = 0;
    public static final int BODYWORK_BATTERY_VOLTAGE_LEVEL_NORMAL = 1;
    public static final int BODYWORK_BATTERY_VOLTAGE_LEVEL_INVALID = 255;
    
    public final int level;              // 0=LOW, 1=NORMAL, 255=INVALID
    public final String levelName;       // "LOW", "NORMAL", "INVALID"
    public final boolean isWarning;      // true if level == LOW
    public final long timestamp;
    
    /**
     * Create battery voltage data from BYD API level code.
     * 
     * @param level The level code from BYDAutoBodyworkDevice (0=LOW, 1=NORMAL, 255=INVALID)
     */
    public BatteryVoltageData(int level) {
        this.level = level;
        this.levelName = interpretLevel(level);
        this.isWarning = (level == BODYWORK_BATTERY_VOLTAGE_LEVEL_LOW);
        this.timestamp = System.currentTimeMillis();
    }
    
    /**
     * Interpret level code to human-readable name.
     */
    private static String interpretLevel(int level) {
        switch (level) {
            case BODYWORK_BATTERY_VOLTAGE_LEVEL_LOW:
                return "LOW";
            case BODYWORK_BATTERY_VOLTAGE_LEVEL_NORMAL:
                return "NORMAL";
            case BODYWORK_BATTERY_VOLTAGE_LEVEL_INVALID:
                return "INVALID";
            default:
                // Unknown codes default to INVALID
                return "INVALID";
        }
    }
    
    @Override
    public String toString() {
        return "BatteryVoltageData{" +
                "level=" + level +
                ", levelName='" + levelName + '\'' +
                ", isWarning=" + isWarning +
                ", timestamp=" + timestamp +
                '}';
    }
}
