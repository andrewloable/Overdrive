package com.overdrive.app.telegram.event;

/**
 * Network connectivity change event.
 */
public class ConnectivityEvent extends SystemEvent {
    private final boolean connected;
    private final String networkType;  // "WiFi", "4G", "Ethernet"
    
    public ConnectivityEvent(boolean connected, String networkType) {
        super(EventType.CONNECTIVITY);
        this.connected = connected;
        this.networkType = networkType;
    }
    
    public boolean isConnected() { return connected; }
    public String getNetworkType() { return networkType; }
    
    @Override
    public String getMessage() {
        if (connected) {
            return "📶 Connected via " + networkType;
        } else {
            return "📵 Disconnected from " + networkType;
        }
    }
}
