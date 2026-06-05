package com.overdrive.app.telegram.event;

import androidx.annotation.Nullable;

/**
 * Event emitted when a surveillance recording is finalized.
 */
public class VideoEvent extends SystemEvent {
    private final String filePath;
    @Nullable private final String aiDetection;  // e.g., "person", "car"
    private final int durationSeconds;
    
    public VideoEvent(String filePath, @Nullable String aiDetection, int durationSeconds) {
        super(EventType.VIDEO);
        this.filePath = filePath;
        this.aiDetection = aiDetection;
        this.durationSeconds = durationSeconds;
    }
    
    public String getFilePath() { return filePath; }
    @Nullable public String getAiDetection() { return aiDetection; }
    public int getDurationSeconds() { return durationSeconds; }
    
    @Override
    public String getMessage() {
        StringBuilder sb = new StringBuilder("🎬 Recording saved");
        if (aiDetection != null) {
            sb.append(" (").append(aiDetection).append(" detected)");
        }
        sb.append(" - ").append(durationSeconds).append("s");
        return sb.toString();
    }
}
