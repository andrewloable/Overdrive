package com.overdrive.app.camera;

import android.os.Build;

import org.json.JSONObject;

import java.lang.reflect.Method;

/**
 * Snapshot of the current build/vehicle identity used to decide whether a
 * previously validated BYD camera tuple is still trustworthy.
 */
public final class CameraFirmwareInfo {

    public final String fingerprint;
    public final String buildDisplay;
    public final String buildIncremental;
    public final String roBuildIncremental;
    public final String device;
    public final String vehicleCamSort;

    public CameraFirmwareInfo(
            String fingerprint,
            String buildDisplay,
            String buildIncremental,
            String roBuildIncremental,
            String device,
            String vehicleCamSort) {
        this.fingerprint = normalize(fingerprint);
        this.buildDisplay = normalize(buildDisplay);
        this.buildIncremental = normalize(buildIncremental);
        this.roBuildIncremental = normalize(roBuildIncremental);
        this.device = normalize(device);
        this.vehicleCamSort = normalize(vehicleCamSort);
    }

    public static CameraFirmwareInfo current() {
        // Capture a snapshot of the current runtime build identity. This is
        // read-only metadata used to judge tuple trust, not a config writer.
        return new CameraFirmwareInfo(
                Build.FINGERPRINT,
                Build.DISPLAY,
                Build.VERSION.INCREMENTAL,
                readSystemProperty("ro.build.version.incremental"),
                Build.DEVICE,
                readSystemProperty("vehicle.config.cam_sort"));
    }

    public static String readSystemProperty(String key) {
        // Best-effort reflection only. These properties are useful for
        // camera tuple trust decisions, but the app must still work if the
        // hidden SystemProperties API is unavailable on a given build.
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Method get = sp.getMethod("get", String.class);
            Object value = get.invoke(null, key);
            return value != null ? value.toString() : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        try {
            json.put("firmwareFingerprint", fingerprint);
            json.put("buildDisplay", buildDisplay);
            json.put("buildIncremental", buildIncremental);
            json.put("roBuildIncremental", roBuildIncremental);
            json.put("productDevice", device);
            json.put("vehicleCamSort", vehicleCamSort);
        } catch (Exception ignored) {
        }
        return json;
    }

    public boolean matches(JSONObject cameraConfig) {
        if (cameraConfig == null) return false;
        // Treat the tuple as the unit of trust: every firmware signal must
        // match before a saved BYD camera selection is considered current.
        return eq(cameraConfig.optString("firmwareFingerprint", ""), fingerprint)
                && eq(cameraConfig.optString("buildDisplay", ""), buildDisplay)
                && eq(cameraConfig.optString("buildIncremental", ""), buildIncremental)
                && eq(cameraConfig.optString("roBuildIncremental", ""), roBuildIncremental)
                && eq(cameraConfig.optString("productDevice", ""), device)
                && eq(cameraConfig.optString("vehicleCamSort", ""), vehicleCamSort);
    }

    public boolean hasAnySignal() {
        return !fingerprint.isEmpty()
                || !buildDisplay.isEmpty()
                || !buildIncremental.isEmpty()
                || !roBuildIncremental.isEmpty()
                || !device.isEmpty()
                || !vehicleCamSort.isEmpty();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private static boolean eq(String a, String b) {
        return normalize(a).equals(normalize(b));
    }
}
