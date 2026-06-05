package com.overdrive.app.byd.bodywork;

import android.content.Context;
import android.hardware.bydauto.bodywork.AbsBYDAutoBodyworkListener;

import com.overdrive.app.byd.EventCallback;
import com.overdrive.app.byd.LogCallback;

import org.json.JSONObject;

import java.lang.reflect.Method;

/**
 * Manages BYD Bodywork device and events
 */
public class BodyworkManager {
    
    private final Context context;
    private final EventCallback eventCallback;
    private final LogCallback logCallback;
    
    private Object bodyworkDevice = null;
    private boolean registered = false;
    private int lastPowerLevel = -1;
    private int lastBatteryVoltageLevel = -1;
    private int lastBatteryPowerValue = -1;
    
    public BodyworkManager(Context context, EventCallback eventCallback, LogCallback logCallback) {
        this.context = context;
        this.eventCallback = eventCallback;
        this.logCallback = logCallback;
    }
    
    public void register() {
        try {
            log("Registering bodywork listener...");
            
            Class<?> bodyworkClass = Class.forName("android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice");
            Method getInstance = bodyworkClass.getMethod("getInstance", Context.class);
            bodyworkDevice = getInstance.invoke(null, context);
            
            if (bodyworkDevice == null) {
                log("ERROR: BYDAutoBodyworkDevice.getInstance() returned null");
                return;
            }
            log("Got bodywork device: " + bodyworkDevice);
            
            BodyworkListener listener = new BodyworkListener();
            
            Method registerListener = bodyworkClass.getMethod("registerListener", 
                Class.forName("android.hardware.bydauto.bodywork.AbsBYDAutoBodyworkListener"));
            registerListener.invoke(bodyworkDevice, listener);
            
            registered = true;
            log("Bodywork listener registered successfully");
            
            // Get initial states
            fetchInitialPowerLevel(bodyworkClass);
            fetchBatteryInfo(bodyworkClass);
            
        } catch (Exception e) {
            log("ERROR registering bodywork listener: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private void fetchInitialPowerLevel(Class<?> bodyworkClass) {
        try {
            Method getPowerLevel = bodyworkClass.getMethod("getPowerLevel");
            lastPowerLevel = (int) getPowerLevel.invoke(bodyworkDevice);
            log("Initial power level: " + BodyworkConstants.powerLevelToString(lastPowerLevel));
        } catch (Exception e) {
            log("Could not get initial power level: " + e.getMessage());
        }
    }
    
    private void fetchBatteryInfo(Class<?> bodyworkClass) {
        try {
            // Get battery voltage level (LOW/NORMAL/INVALID)
            Method getBatteryVoltageLevel = bodyworkClass.getMethod("getBatteryVoltageLevel");
            lastBatteryVoltageLevel = (int) getBatteryVoltageLevel.invoke(bodyworkDevice);
            log("Battery voltage level: " + BodyworkConstants.batteryLevelToString(lastBatteryVoltageLevel));
            
            // Get battery power value (0-255, represents 0-25.5V)
            Method getBatteryPowerValue = bodyworkClass.getMethod("getBatteryPowerValue");
            lastBatteryPowerValue = (int) getBatteryPowerValue.invoke(bodyworkDevice);
            double voltage = lastBatteryPowerValue / 10.0; // Convert to actual voltage
            log("Battery voltage: " + voltage + "V (raw: " + lastBatteryPowerValue + ")");
            
            // Broadcast initial battery state
            try {
                JSONObject event = new JSONObject();
                event.put("type", "batteryInfo");
                event.put("voltageLevel", lastBatteryVoltageLevel);
                event.put("voltageLevelName", BodyworkConstants.batteryLevelToString(lastBatteryVoltageLevel));
                event.put("powerValue", lastBatteryPowerValue);
                event.put("voltage", voltage);
                event.put("timestamp", System.currentTimeMillis());
                eventCallback.onEvent(event);
            } catch (Exception e) {
                log("Error broadcasting battery info: " + e.getMessage());
            }
            
        } catch (Exception e) {
            log("Could not get battery info: " + e.getMessage());
        }
    }
    
    /**
     * Refresh battery info and broadcast it
     */
    public void refreshBatteryInfo() {
        if (bodyworkDevice == null) return;
        try {
            Class<?> bodyworkClass = bodyworkDevice.getClass();
            fetchBatteryInfo(bodyworkClass);
        } catch (Exception e) {
            log("Error refreshing battery info: " + e.getMessage());
        }
    }
    
    public boolean isRegistered() {
        return registered;
    }
    
    public int getLastPowerLevel() {
        return lastPowerLevel;
    }
    
    public String getLastPowerLevelName() {
        return BodyworkConstants.powerLevelToString(lastPowerLevel);
    }
    
    public int getLastBatteryVoltageLevel() {
        return lastBatteryVoltageLevel;
    }
    
    public String getLastBatteryVoltageLevelName() {
        return BodyworkConstants.batteryLevelToString(lastBatteryVoltageLevel);
    }
    
    public int getLastBatteryPowerValue() {
        return lastBatteryPowerValue;
    }
    
    public double getLastBatteryVoltage() {
        return lastBatteryPowerValue / 10.0;
    }
    
    private void log(String message) {
        if (logCallback != null) logCallback.log("[Bodywork] " + message);
    }
    
    // ==================== LISTENER ====================
    
    private class BodyworkListener extends AbsBYDAutoBodyworkListener {
        
        @Override
        public void onPowerLevelChanged(int level) {
            log(">>> POWER LEVEL: " + BodyworkConstants.powerLevelToString(level) + 
                " (was: " + BodyworkConstants.powerLevelToString(lastPowerLevel) + ")");
            lastPowerLevel = level;
            
            try {
                JSONObject event = new JSONObject();
                event.put("type", "powerLevel");
                event.put("level", level);
                event.put("levelName", BodyworkConstants.powerLevelToString(level));
                event.put("timestamp", System.currentTimeMillis());
                eventCallback.onEvent(event);
            } catch (Exception e) {
                log("Error broadcasting power level event: " + e.getMessage());
            }
        }
        
        @Override
        public void onDoorStateChanged(int area, int state) {
            log(">>> DOOR: area=" + area + " state=" + (state == 1 ? "OPEN" : "CLOSED"));
            
            try {
                JSONObject event = new JSONObject();
                event.put("type", "door");
                event.put("area", area);
                event.put("state", state);
                event.put("open", state == BodyworkConstants.STATE_OPEN);
                event.put("timestamp", System.currentTimeMillis());
                eventCallback.onEvent(event);
            } catch (Exception e) {
                log("Error broadcasting door event: " + e.getMessage());
            }
        }
        
        @Override
        public void onWindowStateChanged(int area, int state) {
            log(">>> WINDOW: area=" + area + " state=" + (state == 1 ? "OPEN" : "CLOSED"));
            
            try {
                JSONObject event = new JSONObject();
                event.put("type", "window");
                event.put("area", area);
                event.put("state", state);
                event.put("open", state == BodyworkConstants.STATE_OPEN);
                event.put("timestamp", System.currentTimeMillis());
                eventCallback.onEvent(event);
            } catch (Exception e) {
                log("Error broadcasting window event: " + e.getMessage());
            }
        }
        
        @Override
        public void onAlarmStateChanged(int state) {
            log(">>> ALARM: " + (state == 1 ? "ON" : "OFF"));
            
            try {
                JSONObject event = new JSONObject();
                event.put("type", "alarm");
                event.put("state", state);
                event.put("active", state == BodyworkConstants.ALARM_ON);
                event.put("timestamp", System.currentTimeMillis());
                eventCallback.onEvent(event);
            } catch (Exception e) {
                log("Error broadcasting alarm event: " + e.getMessage());
            }
        }
        
        @Override
        public void onAutoSystemStateChanged(int state) {
            log(">>> SYSTEM STATE: " + state);
            
            try {
                JSONObject event = new JSONObject();
                event.put("type", "systemState");
                event.put("state", state);
                event.put("timestamp", System.currentTimeMillis());
                eventCallback.onEvent(event);
            } catch (Exception e) {
                log("Error broadcasting system state event: " + e.getMessage());
            }
        }
        
        @Override
        public void onBatteryVoltageLevelChanged(int level) {
            log(">>> BATTERY VOLTAGE: " + BodyworkConstants.batteryLevelToString(level));
            
            try {
                JSONObject event = new JSONObject();
                event.put("type", "batteryVoltage");
                event.put("level", level);
                event.put("levelName", BodyworkConstants.batteryLevelToString(level));
                event.put("timestamp", System.currentTimeMillis());
                eventCallback.onEvent(event);
            } catch (Exception e) {
                log("Error broadcasting battery voltage event: " + e.getMessage());
            }
        }
    }
}
