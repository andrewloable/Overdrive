package com.overdrive.app.byd.radar;

import android.content.Context;
import android.hardware.bydauto.radar.AbsBYDAutoRadarListener;

import com.overdrive.app.byd.EventCallback;
import com.overdrive.app.byd.LogCallback;

import org.json.JSONObject;

import java.lang.reflect.Method;
import java.util.Arrays;

/**
 * Manages BYD Radar device and events
 */
public class RadarManager {
    
    private final Context context;
    private final EventCallback eventCallback;
    private final LogCallback logCallback;
    
    private Object radarDevice = null;
    private boolean registered = false;
    private final int[] lastStates = new int[RadarConstants.SENSOR_COUNT];
    
    public RadarManager(Context context, EventCallback eventCallback, LogCallback logCallback) {
        this.context = context;
        this.eventCallback = eventCallback;
        this.logCallback = logCallback;
        Arrays.fill(lastStates, -1);
    }
    
    public void register() {
        try {
            log("Registering radar listener...");
            
            Class<?> radarClass = Class.forName("android.hardware.bydauto.radar.BYDAutoRadarDevice");
            Method getInstance = radarClass.getMethod("getInstance", Context.class);
            radarDevice = getInstance.invoke(null, context);
            
            if (radarDevice == null) {
                log("ERROR: BYDAutoRadarDevice.getInstance() returned null");
                return;
            }
            log("Got radar device: " + radarDevice);
            
            RadarListener listener = new RadarListener();
            
            Method registerListener = radarClass.getMethod("registerListener", 
                Class.forName("android.hardware.bydauto.radar.AbsBYDAutoRadarListener"));
            registerListener.invoke(radarDevice, listener);
            
            registered = true;
            log("Radar listener registered successfully");
            
            // Get initial states
            fetchInitialStates(radarClass);
            
        } catch (Exception e) {
            log("ERROR registering radar listener: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private void fetchInitialStates(Class<?> radarClass) {
        try {
            Method getAllStates = radarClass.getMethod("getAllRadarProbeStates");
            int[] states = (int[]) getAllStates.invoke(radarDevice);
            if (states != null) {
                for (int i = 0; i < Math.min(states.length, RadarConstants.SENSOR_COUNT); i++) {
                    lastStates[i] = states[i];
                }
                log("Initial radar states: " + Arrays.toString(states));
            }
        } catch (Exception e) {
            log("Could not get initial radar states: " + e.getMessage());
        }
    }
    
    public boolean isRegistered() {
        return registered;
    }
    
    public int[] getLastStates() {
        return lastStates.clone();
    }
    
    public JSONObject getStateAsJson() {
        JSONObject radar = new JSONObject();
        try {
            for (int i = 0; i < RadarConstants.SENSOR_COUNT; i++) {
                radar.put(RadarConstants.AREA_NAMES[i], RadarConstants.stateToString(lastStates[i]));
            }
        } catch (Exception e) {}
        return radar;
    }
    
    private void log(String message) {
        if (logCallback != null) logCallback.log("[Radar] " + message);
    }
    
    // ==================== LISTENER ====================
    
    private class RadarListener extends AbsBYDAutoRadarListener {
        @Override
        public void onRadarProbeStateChanged(int area, int state) {
            log(">>> RADAR: area=" + RadarConstants.areaToString(area) + 
                " state=" + RadarConstants.stateToString(state));
            
            if (area >= 0 && area < RadarConstants.SENSOR_COUNT) {
                lastStates[area] = state;
            }
            
            try {
                JSONObject event = new JSONObject();
                event.put("type", "radar");
                event.put("area", area);
                event.put("areaName", RadarConstants.areaToString(area));
                event.put("state", state);
                event.put("stateName", RadarConstants.stateToString(state));
                event.put("timestamp", System.currentTimeMillis());
                eventCallback.onEvent(event);
            } catch (Exception e) {
                log("Error broadcasting radar event: " + e.getMessage());
            }
        }
        
        @Override
        public void onReverseRadarSwitchStateChanged(int state) {
            log(">>> RADAR SWITCH: " + (state == 1 ? "ON" : "OFF"));
            
            try {
                JSONObject event = new JSONObject();
                event.put("type", "radarSwitch");
                event.put("state", state);
                event.put("enabled", state == 1);
                event.put("timestamp", System.currentTimeMillis());
                eventCallback.onEvent(event);
            } catch (Exception e) {
                log("Error broadcasting radar switch event: " + e.getMessage());
            }
        }
    }
}
