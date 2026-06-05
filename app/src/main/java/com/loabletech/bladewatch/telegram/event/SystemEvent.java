package com.overdrive.app.telegram.event;

/**
 * Base class for all system events that can trigger Telegram notifications.
 */
public abstract class SystemEvent {
    private final long timestamp;
    private final EventType type;
    
    public enum EventType {
        VIDEO,
        TUNNEL,
        MOTION,
        CRITICAL,
        CONNECTIVITY
    }
    
    protected SystemEvent(EventType type) {
        this.timestamp = System.currentTimeMillis();
        this.type = type;
    }
    
    public long getTimestamp() { return timestamp; }
    public EventType getType() { return type; }
    
    /**
     * Get human-readable message for this event.
     */
    public abstract String getMessage();
}
