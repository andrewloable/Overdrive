package com.overdrive.app.camera;

import org.json.JSONObject;

/**
 * Structured result of panoramic BYD camera discovery.
 *
 * Keep this richer than the legacy integer-only API so field debugging can
 * tell which BMM tag and layout actually selected the tuple.
 */
public final class PanoCameraDiscovery {

    public final int cameraId;
    public final int surfaceMode;
    public final int cameraLayout;
    public final String sourceTag;
    public final String method;
    public final String vehicleCamSort;

    public PanoCameraDiscovery(
            int cameraId,
            int surfaceMode,
            int cameraLayout,
            String sourceTag,
            String method,
            String vehicleCamSort) {
        this.cameraId = cameraId;
        this.surfaceMode = surfaceMode;
        this.cameraLayout = cameraLayout;
        this.sourceTag = sourceTag == null ? "" : sourceTag;
        this.method = method == null ? "" : method;
        this.vehicleCamSort = vehicleCamSort == null ? "" : vehicleCamSort;
    }

    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        try {
            // Persist the same field names used by the camera config/status
            // payloads so a single discovery object can be written through
            // without extra translation.
            json.put("probedCameraId", cameraId);
            json.put("probedSurfaceMode", surfaceMode);
            json.put("cameraLayout", cameraLayout);
            json.put("sourceBmmTag", sourceTag);
            json.put("discoveryMethod", method);
            json.put("vehicleCamSort", vehicleCamSort);
        } catch (Exception ignored) {
        }
        return json;
    }

    @Override
    public String toString() {
        return "PanoCameraDiscovery{id=" + cameraId
                + ", surfaceMode=" + surfaceMode
                + ", layout=" + cameraLayout
                + ", sourceTag='" + sourceTag + '\''
                + ", method='" + method + '\''
                + ", vehicleCamSort='" + vehicleCamSort + '\'' + '}';
    }
}
