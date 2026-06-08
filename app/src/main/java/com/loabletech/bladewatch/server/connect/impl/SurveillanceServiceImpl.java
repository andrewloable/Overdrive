package net.bladewatch.app.server.connect.impl;

import net.bladewatch.app.server.SurveillanceApiHandler;
import net.bladewatch.app.server.connect.ConnectDispatcher;
import net.bladewatch.app.server.connect.ConnectException;
import net.bladewatch.app.server.connect.ConnectHandlerUtil;

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

    private String handleGetConfig(String req) throws ConnectException {
        return ConnectHandlerUtil.captureString(out ->
                SurveillanceApiHandler.handle("GET", "/api/surveillance/config", null, out));
    }

    private String handleSetConfig(String req) throws ConnectException {
        return ConnectHandlerUtil.captureString(out ->
                SurveillanceApiHandler.handle("POST", "/api/surveillance/config", req, out));
    }

    private String handleGetStatus(String req) throws ConnectException {
        return ConnectHandlerUtil.captureString(out ->
                SurveillanceApiHandler.handle("GET", "/api/surveillance/status", null, out));
    }

    private String handleEnable(String req) throws ConnectException {
        return ConnectHandlerUtil.captureString(out ->
                SurveillanceApiHandler.handle("POST", "/api/surveillance/enable", req, out));
    }

    private String handleDisable(String req) throws ConnectException {
        return ConnectHandlerUtil.captureString(out ->
                SurveillanceApiHandler.handle("POST", "/api/surveillance/disable", req, out));
    }

    private String handleGetHeatmap(String req) throws ConnectException {
        return ConnectHandlerUtil.captureString(out ->
                SurveillanceApiHandler.handle("GET", "/api/surveillance/heatmap", null, out));
    }

    private String handleGetSnapshot(String req) throws ConnectException {
        int quadrant;
        try {
            quadrant = new org.json.JSONObject(req).optInt("quadrant", 0);
        } catch (Exception ignored) {
            quadrant = 0;
        }
        final int q = quadrant;
        return ConnectHandlerUtil.captureString(out ->
                SurveillanceApiHandler.handle(
                        "GET", "/api/surveillance/snapshot/" + q, null, out));
    }

    private String handleGetFilterLog(String req) throws ConnectException {
        return ConnectHandlerUtil.captureString(out ->
                SurveillanceApiHandler.handle("GET", "/api/surveillance/filterlog", null, out));
    }

    private String handleSyncCatalog(String req) throws ConnectException {
        return ConnectHandlerUtil.captureString(out ->
                SurveillanceApiHandler.handle("POST", "/api/surveillance/sync", req, out));
    }
}
