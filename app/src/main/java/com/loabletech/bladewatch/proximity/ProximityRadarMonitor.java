package com.overdrive.app.proximity;

import android.content.Context;
import android.hardware.bydauto.radar.AbsBYDAutoRadarListener;

import com.overdrive.app.byd.radar.RadarConstants;
import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.proximity.ProximityGuardConfig.TriggerLevel;

import java.lang.reflect.Method;
import java.util.Arrays;

/**
 * Proximity Radar Monitor
 * 
 * Aggregates radar events from all 8 sensors and triggers callbacks
 * when proximity thresholds are crossed.
 * 
 * Features:
 * - Monitors all 8 BYD radar sensors
 * - Configurable trigger thresholds (RED only or YELLOW+RED)
 * - Event debouncing to prevent rapid oscillation
 * - Callback interface for state changes
 */
public class ProximityRadarMonitor {
    private static final DaemonLogger logger = DaemonLogger.getInstance("ProximityRadarMonitor");
    
    /**
     * Callback interface for proximity trigger events.
     */
    public interface TriggerCallback {
        /**
         * Called when proximity threshold is crossed (safe -> triggered).
         * 
         * @param area The radar area that triggered (0-7)
         * @param state The radar state (YELLOW or RED)
         * @param triggerLevel The highest trigger level detected ("YELLOW" or "RED")
         */
        void onProximityTrigger(int area, int state, String triggerLevel);
        
        /**
         * Called when all sensors return to safe state (triggered -> safe).
         */
        void onProximitySafe();
    }
    
    private final Context context;
    private final TriggerLevel triggerLevel;
    private TriggerCallback callback;
    
    // Radar device and listener
    private Object radarDevice = null;
    private RadarListener radarListener = null;
    private boolean listening = false;
    
    // Sensor state tracking
    private final int[] sensorStates = new int[RadarConstants.SENSOR_COUNT];
    private boolean isTriggered = false;
    
    // Debouncing
    private long lastTriggerTime = 0;
    private long lastSafeTime = 0;
    private static final long DEBOUNCE_MS = 500;  // 500ms debounce
    
    public ProximityRadarMonitor(Context context, TriggerLevel triggerLevel) {
        this.context = context;
        this.triggerLevel = triggerLevel;
        Arrays.fill(sensorStates, RadarConstants.STATE_SAFE);
    }
    
    /**
     * Set the trigger callback.
     */
    public void setCallback(TriggerCallback callback) {
        this.callback = callback;
    }
    
    /**
     * Start listening to radar events.
     */
    public void startListening() {
        if (listening) {
            logger.warn("Already listening to radar events");
            return;
        }
        
        try {
            logger.info("Starting radar listener...");
            
            // Get radar device instance
            Class<?> radarClass = Class.forName("android.hardware.bydauto.radar.BYDAutoRadarDevice");
            Method getInstance = radarClass.getMethod("getInstance", Context.class);
            radarDevice = getInstance.invoke(null, context);
            
            if (radarDevice == null) {
                logger.error("BYDAutoRadarDevice.getInstance() returned null");
                return;
            }
            
            // Create and register listener
            radarListener = new RadarListener();
            Method registerListener = radarClass.getMethod("registerListener", 
                Class.forName("android.hardware.bydauto.radar.AbsBYDAutoRadarListener"));
            registerListener.invoke(radarDevice, radarListener);
            
            listening = true;
            logger.info("Radar listener registered successfully");
            logger.info("Trigger configuration: level=" + triggerLevel + 
                       " (triggers on: " + getTriggerDescription() + ")");
            
            // Get initial states
            fetchInitialStates(radarClass);
            
        } catch (Exception e) {
            logger.error("Failed to start radar listener: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Stop listening to radar events.
     */
    public void stopListening() {
        if (!listening) {
            return;
        }
        
        try {
            if (radarDevice != null && radarListener != null) {
                Class<?> radarClass = radarDevice.getClass();
                Method unregisterListener = radarClass.getMethod("unregisterListener", 
                    Class.forName("android.hardware.bydauto.radar.AbsBYDAutoRadarListener"));
                unregisterListener.invoke(radarDevice, radarListener);
                logger.info("Radar listener unregistered");
            }
        } catch (Exception e) {
            logger.error("Failed to unregister radar listener: " + e.getMessage());
        } finally {
            listening = false;
            radarDevice = null;
            radarListener = null;
            Arrays.fill(sensorStates, RadarConstants.STATE_SAFE);
            isTriggered = false;
        }
    }
    
    /**
     * Check if currently listening.
     */
    public boolean isListening() {
        return listening;
    }
    
    /**
     * Get current sensor states.
     */
    public int[] getSensorStates() {
        return sensorStates.clone();
    }
    
    /**
     * Check if currently triggered.
     */
    public boolean isTriggered() {
        return isTriggered;
    }
    
    /**
     * Get human-readable description of what triggers recording.
     */
    private String getTriggerDescription() {
        if (triggerLevel == TriggerLevel.RED) {
            return "RED only";
        } else {
            return "YELLOW, RED";
        }
    }
    
    // ==================== PRIVATE METHODS ====================
    
    private void fetchInitialStates(Class<?> radarClass) {
        try {
            Method getAllStates = radarClass.getMethod("getAllRadarProbeStates");
            int[] states = (int[]) getAllStates.invoke(radarDevice);
            if (states != null) {
                for (int i = 0; i < Math.min(states.length, RadarConstants.SENSOR_COUNT); i++) {
                    sensorStates[i] = states[i];
                }
                logger.info("Initial radar states: " + Arrays.toString(states));
                
                // Check if already triggered
                checkTriggerCondition();
            }
        } catch (Exception e) {
            logger.warn("Could not get initial radar states: " + e.getMessage());
        }
    }
    
    /**
     * Handle radar state change event.
     */
    private void onRadarEvent(int area, int state) {
        if (area < 0 || area >= RadarConstants.SENSOR_COUNT) {
            logger.warn("Invalid radar area: " + area);
            return;
        }
        
        // Log all radar events for debugging
        logger.debug("Radar event: area=" + RadarConstants.areaToString(area) + 
                    " state=" + RadarConstants.stateToString(state));
        
        // Update sensor state
        sensorStates[area] = state;
        
        // Check trigger condition
        boolean wasTriggered = isTriggered;
        boolean nowTriggered = checkTriggerCondition();
        
        // Log state for debugging
        logger.debug("Trigger check: wasTriggered=" + wasTriggered + " nowTriggered=" + nowTriggered + 
                    " isTriggered=" + isTriggered);
        
        // Debounce state changes
        long now = System.currentTimeMillis();
        
        if (nowTriggered && !wasTriggered) {
            // Transition: safe -> triggered
            if (now - lastTriggerTime > DEBOUNCE_MS) {
                isTriggered = true;
                lastTriggerTime = now;
                
                String level = getHighestTriggerLevel();
                logger.info("PROXIMITY TRIGGER: area=" + RadarConstants.areaToString(area) + 
                           " state=" + RadarConstants.stateToString(state) + 
                           " level=" + level);
                
                if (callback != null) {
                    callback.onProximityTrigger(area, state, level);
                }
            } else {
                logger.debug("Trigger debounced (too soon after last trigger)");
            }
        } else if (!nowTriggered && wasTriggered) {
            // Transition: triggered -> safe
            if (now - lastSafeTime > DEBOUNCE_MS) {
                isTriggered = false;
                lastSafeTime = now;
                
                logger.info("PROXIMITY SAFE: all sensors returned to non-trigger state");
                
                if (callback != null) {
                    callback.onProximitySafe();
                }
            } else {
                logger.debug("Safe transition debounced (too soon after last safe)");
            }
        }
    }
    
    /**
     * Check if any sensor meets the trigger condition.
     * 
     * Note: ABNORMAL state (1) indicates sensor malfunction or ADAS shutdown (gear=P).
     * We don't trigger on ABNORMAL - it's expected when parking.
     */
    private boolean checkTriggerCondition() {
        for (int state : sensorStates) {
            if (triggerLevel == TriggerLevel.RED) {
                // Only trigger on RED
                if (state == RadarConstants.STATE_RED) {
                    return true;
                }
            } else if (triggerLevel == TriggerLevel.YELLOW_RED) {
                // Trigger on YELLOW or RED
                if (state == RadarConstants.STATE_YELLOW || state == RadarConstants.STATE_RED) {
                    return true;
                }
            }
        }
        return false;
    }
    
    /**
     * Get the highest trigger level currently detected.
     */
    private String getHighestTriggerLevel() {
        boolean hasRed = false;
        boolean hasYellow = false;
        
        for (int state : sensorStates) {
            if (state == RadarConstants.STATE_RED) {
                hasRed = true;
            } else if (state == RadarConstants.STATE_YELLOW) {
                hasYellow = true;
            }
        }
        
        // Priority: RED > YELLOW > SAFE
        if (hasRed) return "RED";
        if (hasYellow) return "YELLOW";
        return "SAFE";
    }
    
    // ==================== RADAR LISTENER ====================
    
    private class RadarListener extends AbsBYDAutoRadarListener {
        @Override
        public void onRadarProbeStateChanged(int area, int state) {
            onRadarEvent(area, state);
        }
        
        @Override
        public void onReverseRadarSwitchStateChanged(int state) {
            // Not used for proximity guard
        }
    }
}
