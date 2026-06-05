package com.overdrive.app.telegram.event;

import androidx.annotation.Nullable;

/**
 * Event emitted when motion is detected.
 */
public class MotionEvent extends SystemEvent {
    @Nullable private final String aiDetection;  // e.g., "person", "car", null for generic motion
    private final float confidence;
    
    public MotionEvent(@Nullable String aiDetection, float confidence) {
        super(EventType.MOTION);
        this.aiDetection = aiDetection;
        this.confidence = confidence;
    }
    
    @Nullable public String getAiDetection() { return aiDetection; }
    public float getConfidence() { return confidence; }
    
    @Override
    public String getMessage() {
        if (aiDetection != null) {
            return "🚨 " + capitalize(aiDetection) + " detected (" + Math.round(confidence * 100) + "%)";
        } else {
            return "👁 Motion detected";
        }
    }
    
    private String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
