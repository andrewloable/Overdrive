package com.overdrive.app.byd;

/**
 * Common constants for BYD SDK integration
 */
public final class BydConstants {
    
    private BydConstants() {} // Prevent instantiation
    
    // Power levels from BYDAutoBodyworkDevice
    public static final int POWER_LEVEL_OFF = 0;
    public static final int POWER_LEVEL_ACC = 1;
    public static final int POWER_LEVEL_ON = 2;
    
    public static String powerLevelToString(int level) {
        switch (level) {
            case POWER_LEVEL_OFF: return "OFF";
            case POWER_LEVEL_ACC: return "ACC";
            case POWER_LEVEL_ON: return "ON";
            default: return "UNKNOWN(" + level + ")";
        }
    }
}
