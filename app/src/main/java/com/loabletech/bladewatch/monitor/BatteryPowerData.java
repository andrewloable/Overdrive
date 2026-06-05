package com.overdrive.app.monitor;

/**
 * Data model for 12V battery power voltage.
 * 
 * Represents the actual battery voltage in volts as reported by BYDAutoOtaDevice.
 * Includes warning and critical thresholds for battery health monitoring.
 */
public class BatteryPowerData {
    
    // Voltage thresholds
    public static final double WARNING_THRESHOLD_VOLTS = 11.5;
    public static final double CRITICAL_THRESHOLD_VOLTS = 10.5;
    public static final double MIN_VALID_VOLTS = 9.0;
    public static final double MAX_VALID_VOLTS = 16.0;
    
    public final double voltageVolts;    // Actual voltage (9.0 - 16.0V typical)
    public final boolean isWarning;      // true if < 11.5V
    public final boolean isCritical;     // true if < 10.5V
    public final long timestamp;
    
    /**
     * Create battery power data from voltage reading.
     * 
     * @param voltageVolts The voltage in volts from BYDAutoOtaDevice
     */
    public BatteryPowerData(double voltageVolts) {
        this.voltageVolts = voltageVolts;
        this.isCritical = (voltageVolts < CRITICAL_THRESHOLD_VOLTS);
        this.isWarning = (voltageVolts < WARNING_THRESHOLD_VOLTS);
        this.timestamp = System.currentTimeMillis();
    }
    
    /**
     * Check if voltage is within valid range.
     */
    public boolean isValidRange() {
        return voltageVolts >= MIN_VALID_VOLTS && voltageVolts <= MAX_VALID_VOLTS;
    }
    
    /**
     * Get health status description.
     */
    public String getHealthStatus() {
        if (isCritical) {
            return "CRITICAL";
        } else if (isWarning) {
            return "WARNING";
        } else {
            return "NORMAL";
        }
    }
    
    @Override
    public String toString() {
        return "BatteryPowerData{" +
                "voltageVolts=" + voltageVolts +
                ", isWarning=" + isWarning +
                ", isCritical=" + isCritical +
                ", timestamp=" + timestamp +
                '}';
    }
}
