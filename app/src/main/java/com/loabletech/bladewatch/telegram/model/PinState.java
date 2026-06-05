package com.overdrive.app.telegram.model;

/**
 * PIN generation state with expiry tracking.
 */
public class PinState {
    private final String pin;
    private final long generatedAt;
    private final long expiresAt;
    
    private static final long PIN_VALIDITY_MS = 5 * 60 * 1000; // 5 minutes
    
    public PinState(String pin) {
        this.pin = pin;
        this.generatedAt = System.currentTimeMillis();
        this.expiresAt = generatedAt + PIN_VALIDITY_MS;
    }
    
    public String getPin() { return pin; }
    public long getGeneratedAt() { return generatedAt; }
    public long getExpiresAt() { return expiresAt; }
    
    public boolean isExpired() {
        return System.currentTimeMillis() > expiresAt;
    }
    
    public long getRemainingMs() {
        return Math.max(0, expiresAt - System.currentTimeMillis());
    }
}
