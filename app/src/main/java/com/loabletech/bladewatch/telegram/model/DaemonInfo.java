package com.overdrive.app.telegram.model;

/**
 * Information about a daemon process.
 */
public class DaemonInfo {
    private final String name;
    private final String displayName;
    private final DaemonStatus status;
    
    public DaemonInfo(String name, String displayName, DaemonStatus status) {
        this.name = name;
        this.displayName = displayName;
        this.status = status;
    }
    
    public String getName() { return name; }
    public String getDisplayName() { return displayName; }
    public DaemonStatus getStatus() { return status; }
    
    public String getStatusEmoji() {
        switch (status) {
            case RUNNING: return "🟢";
            case STOPPED: return "🔴";
            case STARTING: return "🟡";
            case ERROR: return "⚠️";
            default: return "❓";
        }
    }
}
