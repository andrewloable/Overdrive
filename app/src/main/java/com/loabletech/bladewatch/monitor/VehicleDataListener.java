package com.overdrive.app.monitor;

/**
 * Listener interface for vehicle data updates.
 * 
 * Implement this interface to receive notifications when vehicle data changes.
 */
public interface VehicleDataListener {
    
    /**
     * Called when battery voltage level changes.
     */
    void onBatteryVoltageChanged(BatteryVoltageData data);
    
    /**
     * Called when battery power voltage changes.
     */
    void onBatteryPowerChanged(BatteryPowerData data);
    
    /**
     * Called when charging state changes.
     */
    void onChargingStateChanged(ChargingStateData data);
    
    /**
     * Called when charging power changes.
     */
    void onChargingPowerChanged(double powerKW);
    
    /**
     * Called when a monitor becomes unavailable.
     * 
     * @param monitorName The name of the monitor that failed
     * @param reason The reason for failure
     */
    void onDataUnavailable(String monitorName, String reason);
}
