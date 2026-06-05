package com.overdrive.app.proximity;

import com.overdrive.app.logging.DaemonLogger;

/**
 * Proximity Guard Configuration POJO
 * 
 * Immutable configuration for Proximity Guard recording mode.
 * Uses Builder pattern for construction.
 */
public class ProximityGuardConfig {
    private static final DaemonLogger logger = DaemonLogger.getInstance("ProximityGuardConfig");
    
    /**
     * Trigger sensitivity levels.
     */
    public enum TriggerLevel {
        RED,         // Only trigger on very close objects (0-0.5m)
        YELLOW_RED   // Trigger on medium-close objects (0-0.8m)
    }
    
    // Configuration fields
    private final boolean enabled;
    private final TriggerLevel triggerLevel;
    private final int preRecordSeconds;
    private final int postRecordSeconds;
    
    // Validation constants
    private static final int MIN_PRE_RECORD_SECONDS = 2;
    private static final int MAX_PRE_RECORD_SECONDS = 15;
    private static final int MIN_POST_RECORD_SECONDS = 5;
    private static final int MAX_POST_RECORD_SECONDS = 30;
    
    private ProximityGuardConfig(Builder builder) {
        this.enabled = builder.enabled;
        this.triggerLevel = builder.triggerLevel;
        this.preRecordSeconds = validatePreRecordSeconds(builder.preRecordSeconds);
        this.postRecordSeconds = validatePostRecordSeconds(builder.postRecordSeconds);
    }
    
    // ==================== GETTERS ====================
    
    public boolean isEnabled() {
        return enabled;
    }
    
    public TriggerLevel getTriggerLevel() {
        return triggerLevel;
    }
    
    public int getPreRecordSeconds() {
        return preRecordSeconds;
    }
    
    public int getPostRecordSeconds() {
        return postRecordSeconds;
    }
    
    // ==================== VALIDATION ====================
    
    private int validatePreRecordSeconds(int seconds) {
        if (seconds < MIN_PRE_RECORD_SECONDS) {
            logger.warn("preRecordSeconds " + seconds + " < min " + MIN_PRE_RECORD_SECONDS + ", clamping");
            return MIN_PRE_RECORD_SECONDS;
        }
        if (seconds > MAX_PRE_RECORD_SECONDS) {
            logger.warn("preRecordSeconds " + seconds + " > max " + MAX_PRE_RECORD_SECONDS + ", clamping");
            return MAX_PRE_RECORD_SECONDS;
        }
        return seconds;
    }
    
    private int validatePostRecordSeconds(int seconds) {
        if (seconds < MIN_POST_RECORD_SECONDS) {
            logger.warn("postRecordSeconds " + seconds + " < min " + MIN_POST_RECORD_SECONDS + ", clamping");
            return MIN_POST_RECORD_SECONDS;
        }
        if (seconds > MAX_POST_RECORD_SECONDS) {
            logger.warn("postRecordSeconds " + seconds + " > max " + MAX_POST_RECORD_SECONDS + ", clamping");
            return MAX_POST_RECORD_SECONDS;
        }
        return seconds;
    }
    
    // ==================== BUILDER ====================
    
    public static class Builder {
        // DEPRECATED: enabled flag is now controlled by RecordingModeManager.mode
        // Kept for backward compatibility but defaults to true
        private boolean enabled = true;
        // Default to YELLOW_RED for better sensitivity - triggers on medium-close objects
        private TriggerLevel triggerLevel = TriggerLevel.YELLOW_RED;
        private int preRecordSeconds = 5;
        private int postRecordSeconds = 10;
        
        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }
        
        public Builder triggerLevel(TriggerLevel triggerLevel) {
            this.triggerLevel = triggerLevel;
            return this;
        }
        
        public Builder triggerLevel(String triggerLevelStr) {
            try {
                this.triggerLevel = TriggerLevel.valueOf(triggerLevelStr.toUpperCase());
            } catch (IllegalArgumentException e) {
                logger.warn("Invalid trigger level: " + triggerLevelStr + ", using default RED");
                this.triggerLevel = TriggerLevel.RED;
            }
            return this;
        }
        
        public Builder preRecordSeconds(int preRecordSeconds) {
            this.preRecordSeconds = preRecordSeconds;
            return this;
        }
        
        public Builder postRecordSeconds(int postRecordSeconds) {
            this.postRecordSeconds = postRecordSeconds;
            return this;
        }
        
        public ProximityGuardConfig build() {
            return new ProximityGuardConfig(this);
        }
    }
    
    // ==================== FACTORY METHODS ====================
    
    /**
     * Create default configuration.
     */
    public static ProximityGuardConfig createDefault() {
        return new Builder().build();
    }
    
    /**
     * Create configuration from UnifiedConfigManager.
     */
    public static ProximityGuardConfig fromConfig(org.json.JSONObject config) {
        Builder builder = new Builder();
        
        if (config.has("enabled")) {
            builder.enabled(config.optBoolean("enabled", false));
        }
        
        if (config.has("triggerLevel")) {
            builder.triggerLevel(config.optString("triggerLevel", "RED"));
        }
        
        if (config.has("preRecordSeconds")) {
            builder.preRecordSeconds(config.optInt("preRecordSeconds", 5));
        }
        
        if (config.has("postRecordSeconds")) {
            builder.postRecordSeconds(config.optInt("postRecordSeconds", 10));
        }
        
        return builder.build();
    }
    
    // ==================== UTILITY ====================
    
    @Override
    public String toString() {
        return "ProximityGuardConfig{" +
                "enabled=" + enabled +
                ", triggerLevel=" + triggerLevel +
                ", preRecordSeconds=" + preRecordSeconds +
                ", postRecordSeconds=" + postRecordSeconds +
                '}';
    }
    
    /**
     * Get validation bounds for UI.
     */
    public static int getMinPreRecordSeconds() {
        return MIN_PRE_RECORD_SECONDS;
    }
    
    public static int getMaxPreRecordSeconds() {
        return MAX_PRE_RECORD_SECONDS;
    }
    
    public static int getMinPostRecordSeconds() {
        return MIN_POST_RECORD_SECONDS;
    }
    
    public static int getMaxPostRecordSeconds() {
        return MAX_POST_RECORD_SECONDS;
    }
}
