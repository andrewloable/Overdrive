package com.overdrive.app.monitor;

/**
 * Data model for battery State of Charge (SOC).
 * 
 * Represents the remaining battery power as a percentage (0-100%).
 * Source: BYDAutoStatisticDevice.getElecPercentageValue()
 */
public class BatterySocData {
    
    public static final double MIN_SOC = 0.0;
    public static final double MAX_SOC = 100.0;
    public static final double LOW_SOC_THRESHOLD = 20.0;
    public static final double CRITICAL_SOC_THRESHOLD = 10.0;
    
    public final double socPercent;      // 0-100%
    public final boolean isLow;          // true if < 20%
    public final boolean isCritical;     // true if < 10%
    public final long timestamp;
    
    /**
     * Create SOC data from percentage value.
     * 
     * @param socPercent The SOC percentage (0-100)
     */
    public BatterySocData(double socPercent) {
        this.socPercent = socPercent;
        this.isCritical = (socPercent < CRITICAL_SOC_THRESHOLD);
        this.isLow = (socPercent < LOW_SOC_THRESHOLD);
        this.timestamp = System.currentTimeMillis();
    }
    
    /**
     * Check if SOC is within valid range.
     */
    public boolean isValidRange() {
        return socPercent >= MIN_SOC && socPercent <= MAX_SOC;
    }
    
    /**
     * Get battery status description.
     */
    public String getStatus() {
        if (isCritical) {
            return "CRITICAL";
        } else if (isLow) {
            return "LOW";
        } else {
            return "NORMAL";
        }
    }
    
    @Override
    public String toString() {
        return "BatterySocData{" +
                "socPercent=" + socPercent +
                ", isLow=" + isLow +
                ", isCritical=" + isCritical +
                ", timestamp=" + timestamp +
                '}';
    }
}
