package net.bladewatch.app.server.connect.impl;

import net.bladewatch.app.server.SurveillanceApiHandler;
import net.bladewatch.app.server.connect.ConnectDispatcher;
import net.bladewatch.app.server.connect.ConnectException;
import net.bladewatch.app.server.connect.ConnectHandlerUtil;
import net.bladewatch.app.server.connect.ConnectResponse;

/**
 * Connect protocol handler for bladewatch.v1.SurveillanceService.
 *
 * Routes (SurveillanceApiHandler):
 *   GetConfig    → GET  /api/surveillance/config
 *   SetConfig    → POST /api/surveillance/config
 *   GetStatus    → GET  /api/surveillance/status
 *   Enable       → POST /api/surveillance/enable
 *   Disable      → POST /api/surveillance/disable
 *   GetHeatmap   → GET  /api/surveillance/heatmap
 *   GetSnapshot  → GET  /api/surveillance/snapshot/{quadrant}
 *   GetFilterLog → GET  /api/surveillance/filterlog
 *   SyncCatalog  → POST /api/surveillance/sync
 */
public class SurveillanceServiceImpl {

    public void register(ConnectDispatcher dispatcher) {
        dispatcher.register("bladewatch.v1.SurveillanceService", "GetConfig",
                this::handleGetConfig);
        dispatcher.register("bladewatch.v1.SurveillanceService", "SetConfig",
                this::handleSetConfig);
        dispatcher.register("bladewatch.v1.SurveillanceService", "GetStatus",
                this::handleGetStatus);
        dispatcher.register("bladewatch.v1.SurveillanceService", "Enable",
                this::handleEnable);
        dispatcher.register("bladewatch.v1.SurveillanceService", "Disable",
                this::handleDisable);
        dispatcher.register("bladewatch.v1.SurveillanceService", "GetHeatmap",
                this::handleGetHeatmap);
        dispatcher.register("bladewatch.v1.SurveillanceService", "GetSnapshot",
                this::handleGetSnapshot);
        dispatcher.register("bladewatch.v1.SurveillanceService", "GetFilterLog",
                this::handleGetFilterLog);
        dispatcher.register("bladewatch.v1.SurveillanceService", "SyncCatalog",
                this::handleSyncCatalog);
    }

    private ConnectResponse handleGetConfig(String req, String clientIdentity) throws ConnectException {
        return ConnectHandlerUtil.captureString(out ->
                SurveillanceApiHandler.handle("GET", "/api/surveillance/config", null, out));
    }

    // Boolean config toggles the REST handler reads with has()-gating. The Connect
    // client sends a FULL config snapshot, but JsonFormat omits any bool that is
    // false, so the handler would never see (and never persist) a toggle turned OFF.
    // We re-add every boolean field of SurveillanceConfig as false when absent so OFF
    // sticks. Derived from the proto descriptor so a newly-added bool field is covered
    // automatically — no hand-maintained list to drift out of sync (BladeWatch-mvay).
    // Re-adding a bool the handler ignores (e.g. enabled/aiEnabled, which are owned by
    // the Enable/Disable RPCs) is inert, so deriving the full set is safe.
    static final java.util.List<String> CONFIG_BOOLEAN_TOGGLES = computeConfigBooleanToggles();

    private static java.util.List<String> computeConfigBooleanToggles() {
        java.util.List<String> names = new java.util.ArrayList<>();
        for (com.google.protobuf.Descriptors.FieldDescriptor f
                : net.bladewatch.app.grpc.v1.SurveillanceConfig.getDescriptor().getFields()) {
            if (f.getType() == com.google.protobuf.Descriptors.FieldDescriptor.Type.BOOL) {
                names.add(f.getJsonName());
            }
        }
        return java.util.Collections.unmodifiableList(names);
    }

    private ConnectResponse handleSetConfig(String req, String clientIdentity) throws ConnectException {
        // The Connect client wraps the payload as SetSurveillanceConfigRequest.config
        // → wire body {"config":{...}}, but the REST handler parses a FLAT body.
        // Unwrap the nested config, re-add omitted false toggles, and merge the camera
        // probe override fields (manualCameraId, clearManualCameraId) from the top-level
        // request before forwarding.
        String flatBody;
        try {
            org.json.JSONObject r = new org.json.JSONObject(req);
            org.json.JSONObject config = r.optJSONObject("config");
            if (config == null) {
                flatBody = req; // already flat (defensive) — forward unchanged
            } else {
                for (String key : CONFIG_BOOLEAN_TOGGLES) {
                    if (!config.has(key)) config.put(key, false);
                }
                // Merge camera probe override fields from the top-level request.
                if (r.has("manualCameraId") && !r.isNull("manualCameraId")) {
                    config.put("manualCameraId", r.getInt("manualCameraId"));
                }
                if (r.optBoolean("clearManualCameraId", false)) {
                    config.put("clearManualCameraId", true);
                }
                flatBody = config.toString();
            }
        } catch (org.json.JSONException e) {
            throw new ConnectException("invalid_argument", "Invalid surveillance config body");
        }
        final String body = flatBody;
        return ConnectHandlerUtil.captureString(out ->
                SurveillanceApiHandler.handle("POST", "/api/surveillance/config", body, out));
    }

    private ConnectResponse handleGetStatus(String req, String clientIdentity) throws ConnectException {
        // REST emits a NESTED {success, status:{initialized, enabled, active, ...}};
        // the proto GetSurveillanceStatusResponse is FLAT {pipeline_running,
        // surveillance_active, error}. Map status.active → pipelineRunning (the GPU
        // pipeline is running) and status.enabled → surveillanceActive (the user's
        // surveillance toggle).
        org.json.JSONObject body = ConnectHandlerUtil.capture(out ->
                SurveillanceApiHandler.handle("GET", "/api/surveillance/status", null, out));
        try {
            org.json.JSONObject status = body.optJSONObject("status");
            org.json.JSONObject flat = new org.json.JSONObject();
            if (status != null) {
                flat.put("pipelineRunning", status.optBoolean("active", false));
                flat.put("surveillanceActive", status.optBoolean("enabled", false));
            }
            return ConnectResponse.of(flat.toString());
        } catch (org.json.JSONException e) {
            throw new ConnectException("internal", "An internal error occurred");
        }
    }

    private ConnectResponse handleEnable(String req, String clientIdentity) throws ConnectException {
        return ConnectHandlerUtil.captureString(out ->
                SurveillanceApiHandler.handle("POST", "/api/surveillance/enable", req, out));
    }

    private ConnectResponse handleDisable(String req, String clientIdentity) throws ConnectException {
        return ConnectHandlerUtil.captureString(out ->
                SurveillanceApiHandler.handle("POST", "/api/surveillance/disable", req, out));
    }

    private ConnectResponse handleGetHeatmap(String req, String clientIdentity) throws ConnectException {
        return ConnectHandlerUtil.captureString(out ->
                SurveillanceApiHandler.handle("GET", "/api/surveillance/heatmap", null, out));
    }

    private ConnectResponse handleGetSnapshot(String req, String clientIdentity) throws ConnectException {
        int quadrant;
        try {
            quadrant = new org.json.JSONObject(req).optInt("quadrant", 0);
        } catch (Exception ignored) {
            quadrant = 0;
        }
        final int q = quadrant;
        // The snapshot endpoint returns raw JPEG bytes; encode as base64 in
        // the imageJpeg field so the Connect response stays application/connect+json.
        return ConnectHandlerUtil.captureBytes(out ->
                SurveillanceApiHandler.handle(
                        "GET", "/api/surveillance/snapshot/" + q, null, out),
                "imageJpeg");
    }

    private ConnectResponse handleGetFilterLog(String req, String clientIdentity) throws ConnectException {
        return ConnectHandlerUtil.captureString(out ->
                SurveillanceApiHandler.handle("GET", "/api/surveillance/filterlog", null, out));
    }

    private ConnectResponse handleSyncCatalog(String req, String clientIdentity) throws ConnectException {
        return ConnectHandlerUtil.captureString(out ->
                SurveillanceApiHandler.handle("POST", "/api/surveillance/sync", req, out));
    }
}
