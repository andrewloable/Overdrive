package com.overdrive.app.surveillance;

import com.overdrive.app.daemon.CameraDaemon;
import com.overdrive.app.monitor.GpsMonitor;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * SafeLocationManager — Singleton geofence manager.
 *
 * Responsibilities:
 * 1. CRUD for safe location zones (max 10)
 * 2. Haversine distance check against current GPS
 * 3. Zone transition detection (enter/leave) with camera lifecycle control
 * 4. Persistence to config file (cross-UID accessible)
 *
 * GPS Integration:
 * - Called by GpsMonitor.updateFromIpc() on every location update (~2s)
 * - Caches result to avoid redundant Haversine math
 * - On zone transition: enables/disables surveillance via CameraDaemon
 *
 * Thread Safety:
 * - CopyOnWriteArrayList for zone list (reads >> writes)
 * - volatile for cached state
 */
public class SafeLocationManager {

    private static final String TAG = "SafeLocation";
    private static final String CONFIG_FILE = "/data/local/tmp/safe_locations.json";
    private static final int MAX_ZONES = 10;
    private static final double EARTH_RADIUS_M = 6_371_000.0;

    private static volatile SafeLocationManager instance;

    private final CopyOnWriteArrayList<SafeLocation> zones = new CopyOnWriteArrayList<>();
    private volatile boolean featureEnabled = true;
    private volatile boolean cachedInSafeZone = false;
    private volatile String cachedZoneName = null;
    private volatile double cachedDistanceM = Double.MAX_VALUE;

    private SafeLocationManager() {}

    public static SafeLocationManager getInstance() {
        if (instance == null) {
            synchronized (SafeLocationManager.class) {
                if (instance == null) {
                    instance = new SafeLocationManager();
                }
            }
        }
        return instance;
    }

    /** Load zones from config file. Call once at daemon startup. */
    public void init() {
        loadFromFile();
        CameraDaemon.log(TAG + ": Initialized with " + zones.size() + " zones, feature=" + featureEnabled);
    }

    // ========================================================================
    // ZONE CRUD
    // ========================================================================

    public SafeLocation addZone(String name, double lat, double lng, int radiusM) {
        if (zones.size() >= MAX_ZONES) {
            CameraDaemon.log(TAG + ": Max zones reached (" + MAX_ZONES + ")");
            return null;
        }
        SafeLocation zone = new SafeLocation(name, lat, lng, radiusM);
        zones.add(zone);
        saveToFile();
        // Re-evaluate immediately — maybe we just added a zone we're inside
        reevaluateZone();
        CameraDaemon.log(TAG + ": Added zone '" + name + "' at " + lat + "," + lng + " r=" + radiusM + "m");
        return zone;
    }

    public boolean updateZone(String id, JSONObject updates) {
        for (SafeLocation zone : zones) {
            if (zone.getId().equals(id)) {
                if (updates.has("name")) zone.setName(updates.optString("name"));
                if (updates.has("lat")) zone.setLatitude(updates.optDouble("lat"));
                if (updates.has("lng")) zone.setLongitude(updates.optDouble("lng"));
                if (updates.has("radiusM")) zone.setRadiusMeters(updates.optInt("radiusM"));
                if (updates.has("enabled")) zone.setEnabled(updates.optBoolean("enabled"));
                saveToFile();
                reevaluateZone();
                return true;
            }
        }
        return false;
    }

    public boolean removeZone(String id) {
        for (SafeLocation zone : zones) {
            if (zone.getId().equals(id)) {
                zones.remove(zone);
                saveToFile();
                reevaluateZone();
                CameraDaemon.log(TAG + ": Removed zone '" + zone.getName() + "'");
                return true;
            }
        }
        return false;
    }

    public List<SafeLocation> getZones() {
        return new ArrayList<>(zones);
    }

    public void setFeatureEnabled(boolean enabled) {
        this.featureEnabled = enabled;
        saveToFile();
        reevaluateZone();
        CameraDaemon.log(TAG + ": Feature " + (enabled ? "enabled" : "disabled"));
    }

    public boolean isFeatureEnabled() { return featureEnabled; }

    // ========================================================================
    // GEOFENCE CHECK — Haversine
    // ========================================================================

    /**
     * Check if current GPS position is inside any enabled safe zone.
     * Uses cached result — updated on every GPS tick via onLocationUpdate().
     */
    public boolean isInSafeZone() {
        return featureEnabled && cachedInSafeZone;
    }

    public String getCurrentZoneName() { return cachedZoneName; }
    public double getDistanceToNearestZone() { return cachedDistanceM; }

    /**
     * Called by GpsMonitor on every IPC location update (~2s).
     * Performs Haversine check and triggers zone transitions.
     */
    public void onLocationUpdate(double lat, double lng) {
        if (!featureEnabled || zones.isEmpty()) {
            if (cachedInSafeZone) {
                // Feature was disabled or all zones removed while in zone
                cachedInSafeZone = false;
                cachedZoneName = null;
                cachedDistanceM = Double.MAX_VALUE;
                onLeftSafeZone();
            }
            return;
        }

        boolean wasInZone = cachedInSafeZone;
        boolean nowInZone = false;
        String zoneName = null;
        double nearestDist = Double.MAX_VALUE;

        for (SafeLocation zone : zones) {
            if (!zone.isEnabled()) continue;

            double dist = haversine(lat, lng, zone.getLatitude(), zone.getLongitude());
            if (dist < nearestDist) {
                nearestDist = dist;
            }
            if (dist <= zone.getRadiusMeters()) {
                nowInZone = true;
                zoneName = zone.getName();
                nearestDist = dist;
                break;  // Inside at least one zone — that's enough
            }
        }

        cachedInSafeZone = nowInZone;
        cachedZoneName = zoneName;
        cachedDistanceM = nearestDist;

        // Zone transitions
        if (!wasInZone && nowInZone) {
            onEnteredSafeZone(zoneName);
        } else if (wasInZone && !nowInZone) {
            onLeftSafeZone();
        }
    }

    /** Force re-evaluation with current GPS (after zone add/remove/toggle). */
    private void reevaluateZone() {
        GpsMonitor gps = GpsMonitor.getInstance();
        if (gps.hasLocation()) {
            onLocationUpdate(gps.getLatitude(), gps.getLongitude());
        }
    }

    // ========================================================================
    // ZONE TRANSITIONS — Camera Lifecycle Control
    // ========================================================================

    private void onEnteredSafeZone(String zoneName) {
        CameraDaemon.log(TAG + ": ENTERED safe zone '" + zoneName + "' — suppressing surveillance");
        // Only act when the pipeline is actually in SURVEILLANCE mode. The
        // pipeline is shared with CONTINUOUS / DRIVE_MODE / PROXIMITY_GUARD
        // recording — driving home with ACC ON + CONTINUOUS recording would
        // otherwise have its recording torn down here.
        com.overdrive.app.surveillance.GpuSurveillancePipeline pipeline = CameraDaemon.getGpuPipeline();
        if (pipeline != null && pipeline.isSurveillanceMode()) {
            // Don't call disableSurveillance() through CameraDaemon — that clears
            // the user's preference. Just disable the sentry component and stop
            // the pipeline. The preference stays enabled so surveillance
            // auto-restarts when leaving the zone or on the next ACC OFF.
            pipeline.disableSurveillance();
            pipeline.stop();
            CameraDaemon.setSafeZoneSuppressed(true);
        } else {
            // Pipeline is busy with continuous / drive recording, or idle. Just
            // mark the suppression flag so when ACC eventually turns off and
            // would have armed sentry, CameraDaemon's ACC-OFF handler skips it.
            CameraDaemon.setSafeZoneSuppressed(true);
        }
    }

    private void onLeftSafeZone() {
        CameraDaemon.log(TAG + ": LEFT safe zone — resuming surveillance");
        if (CameraDaemon.isSafeZoneSuppressed()) {
            CameraDaemon.setSafeZoneSuppressed(false);
            // Check persisted config — only restart if user actually wants surveillance
            if (com.overdrive.app.config.UnifiedConfigManager.isSurveillanceEnabled()) {
                CameraDaemon.enableSurveillance();
            }
        }
    }

    // ========================================================================
    // HAVERSINE FORMULA
    // ========================================================================

    /**
     * Calculate great-circle distance between two GPS coordinates.
     * @return distance in meters
     */
    public static double haversine(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                 + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                 * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return EARTH_RADIUS_M * c;
    }

    // ========================================================================
    // PERSISTENCE
    // ========================================================================

    private void saveToFile() {
        try {
            JSONObject root = new JSONObject();
            root.put("enabled", featureEnabled);
            JSONArray arr = new JSONArray();
            for (SafeLocation z : zones) {
                arr.put(z.toJson());
            }
            root.put("zones", arr);

            File tmp = new File(CONFIG_FILE + ".tmp");
            try (FileWriter w = new FileWriter(tmp)) {
                w.write(root.toString(2));
            }
            File target = new File(CONFIG_FILE);
            if (!tmp.renameTo(target)) {
                // Fallback: direct write
                try (FileWriter w = new FileWriter(target)) {
                    w.write(root.toString(2));
                }
                tmp.delete();
            }
            target.setReadable(true, false);
            target.setWritable(true, false);
        } catch (Exception e) {
            CameraDaemon.log(TAG + ": Failed to save: " + e.getMessage());
        }
    }

    private void loadFromFile() {
        try {
            File file = new File(CONFIG_FILE);
            if (!file.exists()) return;

            StringBuilder sb = new StringBuilder();
            try (FileReader r = new FileReader(file)) {
                char[] buf = new char[4096];
                int n;
                while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
            }

            JSONObject root = new JSONObject(sb.toString());
            featureEnabled = root.optBoolean("enabled", true);
            JSONArray arr = root.optJSONArray("zones");
            if (arr != null) {
                zones.clear();
                for (int i = 0; i < arr.length() && i < MAX_ZONES; i++) {
                    zones.add(new SafeLocation(arr.getJSONObject(i)));
                }
            }
        } catch (Exception e) {
            CameraDaemon.log(TAG + ": Failed to load: " + e.getMessage());
        }
    }

    // ========================================================================
    // STATUS (for API responses)
    // ========================================================================

    public JSONObject getStatusJson() {
        JSONObject json = new JSONObject();
        try {
            json.put("featureEnabled", featureEnabled);
            json.put("inSafeZone", cachedInSafeZone);
            json.put("currentZone", cachedZoneName);
            json.put("nearestDistanceM", Math.round(cachedDistanceM));
            json.put("zoneCount", zones.size());

            GpsMonitor gps = GpsMonitor.getInstance();
            json.put("hasGps", gps.hasLocation());
            if (gps.hasLocation()) {
                json.put("lat", gps.getLatitude());
                json.put("lng", gps.getLongitude());
                json.put("accuracy", gps.getAccuracy());
            }

            JSONArray arr = new JSONArray();
            for (SafeLocation z : zones) arr.put(z.toJson());
            json.put("zones", arr);
        } catch (Exception ignored) {}
        return json;
    }
}
