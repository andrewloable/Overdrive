package com.overdrive.app.telegram.model;

/**
 * Owner notification preferences.
 */
public class NotificationPreferences {
    private boolean criticalAlerts = true;
    private boolean connectivityUpdates = true;  // Tunnel URL changes
    private boolean motionText = true;
    private boolean videoUploads = false;  // Off by default (data usage)
    
    public NotificationPreferences() {}
    
    public NotificationPreferences(boolean criticalAlerts, boolean connectivityUpdates, 
                                   boolean motionText, boolean videoUploads) {
        this.criticalAlerts = criticalAlerts;
        this.connectivityUpdates = connectivityUpdates;
        this.motionText = motionText;
        this.videoUploads = videoUploads;
    }
    
    public boolean isCriticalAlerts() { return criticalAlerts; }
    public void setCriticalAlerts(boolean v) { criticalAlerts = v; }
    
    public boolean isConnectivityUpdates() { return connectivityUpdates; }
    public void setConnectivityUpdates(boolean v) { connectivityUpdates = v; }
    
    public boolean isMotionText() { return motionText; }
    public void setMotionText(boolean v) { motionText = v; }
    
    public boolean isVideoUploads() { return videoUploads; }
    public void setVideoUploads(boolean v) { videoUploads = v; }
}
