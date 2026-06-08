package net.bladewatch.app.server.connect.impl;

import net.bladewatch.app.server.StreamingApiHandler;
import net.bladewatch.app.server.connect.ConnectDispatcher;
import net.bladewatch.app.server.connect.ConnectException;
import net.bladewatch.app.server.connect.ConnectHandlerUtil;
import net.bladewatch.app.server.connect.ConnectResponse;

/**
 * Connect protocol handler for bladewatch.v1.StreamService.
 *
 * Routes (StreamingApiHandler):
 *   Enable       → POST /api/stream/enable
 *   Disable      → POST /api/stream/disable
 *   GetStatus    → GET  /api/stream/status
 *   GetQuality   → GET  /api/stream/quality
 *   SetQuality   → POST /api/stream/quality/{tier}   (tier extracted from request JSON)
 *   SetViewMode  → POST /api/stream/view/{mode}      (mode extracted from request JSON)
 *   GetViewMode  → GET  /api/stream/view
 */
public class StreamServiceImpl {

    public void register(ConnectDispatcher dispatcher) {
        dispatcher.register("bladewatch.v1.StreamService", "Enable", this::handleEnable);
        dispatcher.register("bladewatch.v1.StreamService", "Disable", this::handleDisable);
        dispatcher.register("bladewatch.v1.StreamService", "GetStatus", this::handleGetStatus);
        dispatcher.register("bladewatch.v1.StreamService", "GetQuality", this::handleGetQuality);
        dispatcher.register("bladewatch.v1.StreamService", "SetQuality", this::handleSetQuality);
        dispatcher.register("bladewatch.v1.StreamService", "SetViewMode",
                this::handleSetViewMode);
        dispatcher.register("bladewatch.v1.StreamService", "GetViewMode",
                this::handleGetViewMode);
    }

    private ConnectResponse handleEnable(String req, String clientIdentity) throws ConnectException {
        return ConnectHandlerUtil.captureString(out ->
                StreamingApiHandler.handle("POST", "/api/stream/enable", req, out));
    }

    private ConnectResponse handleDisable(String req, String clientIdentity) throws ConnectException {
        return ConnectHandlerUtil.captureString(out ->
                StreamingApiHandler.handle("POST", "/api/stream/disable", req, out));
    }

    private ConnectResponse handleGetStatus(String req, String clientIdentity) throws ConnectException {
        return ConnectHandlerUtil.captureString(out ->
                StreamingApiHandler.handle("GET", "/api/stream/status", null, out));
    }

    private ConnectResponse handleGetQuality(String req, String clientIdentity) throws ConnectException {
        return ConnectHandlerUtil.captureString(out ->
                StreamingApiHandler.handle("GET", "/api/stream/quality", null, out));
    }

    private ConnectResponse handleSetQuality(String req, String clientIdentity) throws ConnectException {
        String tier;
        try {
            tier = new org.json.JSONObject(req).optString("quality", "STANDARD").toUpperCase();
        } catch (Exception ignored) {
            tier = "STANDARD";
        }
        final String t = tier;
        return ConnectHandlerUtil.captureString(out ->
                StreamingApiHandler.handle("POST", "/api/stream/quality/" + t, req, out));
    }

    private ConnectResponse handleSetViewMode(String req, String clientIdentity) throws ConnectException {
        int viewMode;
        try {
            // Proto SetViewModeRequest.view_mode has no json_name, so the Connect
            // client serializes it to camelCase "viewMode". Accept "view_mode" as
            // a legacy fallback.
            org.json.JSONObject j = new org.json.JSONObject(req);
            viewMode = j.has("viewMode") ? j.optInt("viewMode", 0) : j.optInt("view_mode", 0);
        } catch (Exception ignored) {
            viewMode = 0;
        }
        final int vm = viewMode;
        return ConnectHandlerUtil.captureString(out ->
                StreamingApiHandler.handle("POST", "/api/stream/view/" + vm, req, out));
    }

    private ConnectResponse handleGetViewMode(String req, String clientIdentity) throws ConnectException {
        return ConnectHandlerUtil.captureString(out ->
                StreamingApiHandler.handle("GET", "/api/stream/view", null, out));
    }
}
