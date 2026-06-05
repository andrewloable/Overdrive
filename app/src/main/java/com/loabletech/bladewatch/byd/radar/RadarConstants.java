package com.overdrive.app.byd.radar;

/**
 * Constants for BYD Radar SDK
 */
public final class RadarConstants {
    
    private RadarConstants() {}
    
    // Radar probe states
    public static final int STATE_SAFE = 0;
    public static final int STATE_ABNORMAL = 1;
    public static final int STATE_GREEN = 2;
    public static final int STATE_YELLOW = 3;
    public static final int STATE_RED = 4;
    
    // Radar areas (8 sensors)
    public static final int AREA_LEFT_FRONT = 0;
    public static final int AREA_RIGHT_FRONT = 1;
    public static final int AREA_LEFT_REAR = 2;
    public static final int AREA_RIGHT_REAR = 3;
    public static final int AREA_LEFT = 4;
    public static final int AREA_RIGHT = 5;
    public static final int AREA_FRONT_LEFT_MID = 6;
    public static final int AREA_FRONT_RIGHT_MID = 7;
    
    public static final int SENSOR_COUNT = 8;
    
    public static final String[] AREA_NAMES = {
        "leftFront", "rightFront", "leftRear", "rightRear",
        "left", "right", "frontLeftMid", "frontRightMid"
    };
    
    public static String stateToString(int state) {
        switch (state) {
            case STATE_SAFE: return "SAFE";
            case STATE_ABNORMAL: return "ABNORMAL";
            case STATE_GREEN: return "GREEN";
            case STATE_YELLOW: return "YELLOW";
            case STATE_RED: return "RED";
            case -1: return "UNKNOWN";
            default: return "STATE(" + state + ")";
        }
    }
    
    public static String areaToString(int area) {
        switch (area) {
            case AREA_LEFT_FRONT: return "LEFT_FRONT";
            case AREA_RIGHT_FRONT: return "RIGHT_FRONT";
            case AREA_LEFT_REAR: return "LEFT_REAR";
            case AREA_RIGHT_REAR: return "RIGHT_REAR";
            case AREA_LEFT: return "LEFT";
            case AREA_RIGHT: return "RIGHT";
            case AREA_FRONT_LEFT_MID: return "FRONT_LEFT_MID";
            case AREA_FRONT_RIGHT_MID: return "FRONT_RIGHT_MID";
            default: return "AREA(" + area + ")";
        }
    }
}
