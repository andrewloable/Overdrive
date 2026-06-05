package com.overdrive.app.monitor;

/**
 * Event representing a vehicle data change.
 * 
 * Contains the type of change, current and previous values,
 * timestamp, and warning/error flags.
 */
public class VehicleDataEvent {
    
    public enum Type {
        BATTERY_VOLTAGE_CHANGED,
        BATTERY_POWER_CHANGED,
        CHARGING_STATE_CHANGED,
        CHARGING_POWER_CHANGED
    }
    
    public final Type type;
    public final Object currentValue;
    public final Object previousValue;
    public final long timestamp;
    public final boolean isWarning;
    public final boolean isError;
    
    /**
     * Create a vehicle data event.
     * 
     * @param type The type of change
     * @param currentValue The new value
     * @param previousValue The old value (may be null)
     * @param isWarning true if this is a warning condition
     * @param isError true if this is an error condition
     */
    public VehicleDataEvent(Type type, Object currentValue, Object previousValue, 
                           boolean isWarning, boolean isError) {
        this.type = type;
        this.currentValue = currentValue;
        this.previousValue = previousValue;
        this.timestamp = System.currentTimeMillis();
        this.isWarning = isWarning;
        this.isError = isError;
    }
    
    @Override
    public String toString() {
        return "VehicleDataEvent{" +
                "type=" + type +
                ", currentValue=" + currentValue +
                ", previousValue=" + previousValue +
                ", timestamp=" + timestamp +
                ", isWarning=" + isWarning +
                ", isError=" + isError +
                '}';
    }
}
