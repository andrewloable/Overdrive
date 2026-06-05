package com.overdrive.app.telegram.event;

/**
 * Critical system event requiring immediate attention.
 */
public class CriticalEvent extends SystemEvent {
    private final CriticalType criticalType;
    private final String details;
    
    public enum CriticalType {
        LOW_BATTERY("🔋 Low battery"),
        STORAGE_FULL("💾 Storage full"),
        DAEMON_CRASH("⚠️ Daemon crashed"),
        SYSTEM_ERROR("❌ System error"),
        SYSTEM_REBOOT("🔄 System reboot");
        
        private final String prefix;
        CriticalType(String prefix) { this.prefix = prefix; }
        public String getPrefix() { return prefix; }
    }
    
    public CriticalEvent(CriticalType criticalType, String details) {
        super(EventType.CRITICAL);
        this.criticalType = criticalType;
        this.details = details;
    }
    
    public CriticalType getCriticalType() { return criticalType; }
    public String getDetails() { return details; }
    
    @Override
    public String getMessage() {
        return criticalType.getPrefix() + ": " + details;
    }
}
