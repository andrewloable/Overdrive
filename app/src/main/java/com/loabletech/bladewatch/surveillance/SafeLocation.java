package com.overdrive.app.surveillance;

import org.json.JSONObject;
import java.util.UUID;

/**
 * Safe Location — a circular geofence zone where surveillance is suppressed.
 * When the car is parked inside any enabled safe zone, the camera pipeline
 * never starts, saving 100% of camera/GPU/encoder resources.
 */
public class SafeLocation {

    private String id;
    private String name;
    private double latitude;
    private double longitude;
    private int radiusMeters;  // 50-500m
    private boolean enabled;
    private long createdAt;

    public SafeLocation(String name, double latitude, double longitude, int radiusMeters) {
        this.id = UUID.randomUUID().toString().substring(0, 8);
        this.name = name;
        this.latitude = latitude;
        this.longitude = longitude;
        this.radiusMeters = Math.max(50, Math.min(500, radiusMeters));
        this.enabled = true;
        this.createdAt = System.currentTimeMillis();
    }

    /** Deserialize from JSON */
    public SafeLocation(JSONObject json) {
        this.id = json.optString("id", UUID.randomUUID().toString().substring(0, 8));
        this.name = json.optString("name", "Unnamed");
        this.latitude = json.optDouble("lat", 0.0);
        this.longitude = json.optDouble("lng", 0.0);
        this.radiusMeters = json.optInt("radiusM", 150);
        this.enabled = json.optBoolean("enabled", true);
        this.createdAt = json.optLong("createdAt", System.currentTimeMillis());
    }

    /** Serialize to JSON */
    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        try {
            json.put("id", id);
            json.put("name", name);
            json.put("lat", latitude);
            json.put("lng", longitude);
            json.put("radiusM", radiusMeters);
            json.put("enabled", enabled);
            json.put("createdAt", createdAt);
        } catch (Exception ignored) {}
        return json;
    }

    // Getters
    public String getId() { return id; }
    public String getName() { return name; }
    public double getLatitude() { return latitude; }
    public double getLongitude() { return longitude; }
    public int getRadiusMeters() { return radiusMeters; }
    public boolean isEnabled() { return enabled; }
    public long getCreatedAt() { return createdAt; }

    // Setters
    public void setName(String name) { this.name = name; }
    public void setLatitude(double lat) { this.latitude = lat; }
    public void setLongitude(double lng) { this.longitude = lng; }
    public void setRadiusMeters(int r) { this.radiusMeters = Math.max(50, Math.min(500, r)); }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
}
