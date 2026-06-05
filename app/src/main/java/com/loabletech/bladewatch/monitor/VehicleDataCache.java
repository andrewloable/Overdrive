package com.overdrive.app.monitor;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Thread-safe cache for vehicle data.
 * 
 * Uses AtomicReference for lock-free reads and atomic updates.
 * Tracks timestamps for staleness detection.
 */
public class VehicleDataCache {
    
    private final AtomicReference<BatteryVoltageData> batteryVoltage = new AtomicReference<>();
    private final AtomicReference<BatteryPowerData> batteryPower = new AtomicReference<>();
    private final AtomicReference<BatterySocData> batterySoc = new AtomicReference<>();
    private final AtomicReference<ChargingStateData> chargingState = new AtomicReference<>();
    
    // ==================== UPDATES ====================
    
    /**
     * Update battery voltage level data.
     */
    public void updateBatteryVoltage(BatteryVoltageData data) {
        batteryVoltage.set(data);
    }
    
    /**
     * Update battery power voltage data.
     */
    public void updateBatteryPower(BatteryPowerData data) {
        batteryPower.set(data);
    }
    
    /**
     * Update battery SOC data.
     */
    public void updateBatterySoc(BatterySocData data) {
        batterySoc.set(data);
    }
    
    /**
     * Update charging state data.
     */
    public void updateChargingState(ChargingStateData data) {
        chargingState.set(data);
    }
    
    // ==================== READS ====================
    
    /**
     * Get battery voltage level data (lock-free read).
     */
    public BatteryVoltageData getBatteryVoltage() {
        return batteryVoltage.get();
    }
    
    /**
     * Get battery power voltage data (lock-free read).
     */
    public BatteryPowerData getBatteryPower() {
        return batteryPower.get();
    }
    
    /**
     * Get battery SOC data (lock-free read).
     */
    public BatterySocData getBatterySoc() {
        return batterySoc.get();
    }
    
    /**
     * Get charging state data (lock-free read).
     */
    public ChargingStateData getChargingState() {
        return chargingState.get();
    }
    
    // ==================== STALENESS ====================
    
    /**
     * Check if any cached data is stale.
     * 
     * @param maxAgeMs Maximum age in milliseconds before data is considered stale
     * @return true if any data is older than maxAgeMs
     */
    public boolean isStale(long maxAgeMs) {
        long now = System.currentTimeMillis();
        
        BatteryVoltageData bv = batteryVoltage.get();
        if (bv != null && (now - bv.timestamp) > maxAgeMs) {
            return true;
        }
        
        BatteryPowerData bp = batteryPower.get();
        if (bp != null && (now - bp.timestamp) > maxAgeMs) {
            return true;
        }
        
        BatterySocData bs = batterySoc.get();
        if (bs != null && (now - bs.timestamp) > maxAgeMs) {
            return true;
        }
        
        ChargingStateData cs = chargingState.get();
        if (cs != null && (now - cs.timestamp) > maxAgeMs) {
            return true;
        }
        
        return false;
    }
    
    /**
     * Check if specific data type is stale.
     * 
     * @param dataType "batteryVoltage", "batteryPower", or "chargingState"
     * @param maxAgeMs Maximum age in milliseconds
     * @return true if the specified data is older than maxAgeMs
     */
    public boolean isStale(String dataType, long maxAgeMs) {
        long now = System.currentTimeMillis();
        
        switch (dataType) {
            case "batteryVoltage":
                BatteryVoltageData bv = batteryVoltage.get();
                return bv != null && (now - bv.timestamp) > maxAgeMs;
                
            case "batteryPower":
                BatteryPowerData bp = batteryPower.get();
                return bp != null && (now - bp.timestamp) > maxAgeMs;
                
            case "chargingState":
                ChargingStateData cs = chargingState.get();
                return cs != null && (now - cs.timestamp) > maxAgeMs;
                
            default:
                return false;
        }
    }
    
    /**
     * Get the oldest timestamp across all data.
     * 
     * @return The oldest timestamp, or 0 if no data
     */
    public long getOldestTimestamp() {
        long oldest = Long.MAX_VALUE;
        boolean hasData = false;
        
        BatteryVoltageData bv = batteryVoltage.get();
        if (bv != null) {
            oldest = Math.min(oldest, bv.timestamp);
            hasData = true;
        }
        
        BatteryPowerData bp = batteryPower.get();
        if (bp != null) {
            oldest = Math.min(oldest, bp.timestamp);
            hasData = true;
        }
        
        ChargingStateData cs = chargingState.get();
        if (cs != null) {
            oldest = Math.min(oldest, cs.timestamp);
            hasData = true;
        }
        
        return hasData ? oldest : 0;
    }
}
