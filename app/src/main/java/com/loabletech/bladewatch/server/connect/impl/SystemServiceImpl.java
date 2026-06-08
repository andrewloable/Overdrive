package net.bladewatch.app.server.connect.impl;

import net.bladewatch.app.server.AudioTestApiHandler;
import net.bladewatch.app.server.HttpServer;
import net.bladewatch.app.server.ModelsApiHandler;
import net.bladewatch.app.server.PerformanceApiHandler;
import net.bladewatch.app.server.connect.ConnectDispatcher;
import net.bladewatch.app.server.connect.ConnectException;
import net.bladewatch.app.server.connect.ConnectHandlerUtil;
import net.bladewatch.app.server.connect.ConnectResponse;

/**
 * Connect protocol handler for bladewatch.v1.SystemService.
 *
 * GetStatus requires the HttpServer instance (sendStatus is private/instance);
 * other methods use static handler calls.
 *
 * Routes:
 *   GetStatus     → GET  /status             (via HttpServer.serveStatus)
 *   GetPerformance → GET /api/performance    (PerformanceApiHandler)
 *   PlayAudioTest → POST /api/audio/test-avas (AudioTestApiHandler)
 *   ListModels    → GET  /api/models/list    (ModelsApiHandler)
 *   DownloadModel → POST /api/models/download (ModelsApiHandler)
 */
public class SystemServiceImpl {

    private final HttpServer httpServer;

    public SystemServiceImpl(HttpServer httpServer) {
        this.httpServer = httpServer;
    }

    public void register(ConnectDispatcher dispatcher) {
        dispatcher.register("bladewatch.v1.SystemService", "GetStatus",
                this::handleGetStatus);
        dispatcher.register("bladewatch.v1.SystemService", "GetPerformance",
                this::handleGetPerformance);
        dispatcher.register("bladewatch.v1.SystemService", "PlayAudioTest",
                this::handlePlayAudioTest);
        dispatcher.register("bladewatch.v1.SystemService", "ListModels",
                this::handleListModels);
        dispatcher.register("bladewatch.v1.SystemService", "DownloadModel",
                this::handleDownloadModel);
        dispatcher.register("bladewatch.v1.SystemService", "GetSohNominal",
                this::handleGetSohNominal);
        dispatcher.register("bladewatch.v1.SystemService", "SetSohNominal",
                this::handleSetSohNominal);
        dispatcher.register("bladewatch.v1.SystemService", "GetSohStatus",
                this::handleGetSohStatus);
        dispatcher.register("bladewatch.v1.SystemService", "ResetSoh",
                this::handleResetSoh);
        dispatcher.register("bladewatch.v1.SystemService", "ResetPerformance",
                this::handleResetPerformance);
        dispatcher.register("bladewatch.v1.SystemService", "GetParkingDelta",
                this::handleGetParkingDelta);
        dispatcher.register("bladewatch.v1.SystemService", "GetLastCharge",
                this::handleGetLastCharge);
        dispatcher.register("bladewatch.v1.SystemService", "GetSelectedModel",
                this::handleGetSelectedModel);
        dispatcher.register("bladewatch.v1.SystemService", "SetSelectedModel",
                this::handleSetSelectedModel);
        dispatcher.register("bladewatch.v1.SystemService", "GetModelsManifest",
                this::handleGetModelsManifest);
    }

    private ConnectResponse handleGetStatus(String req, String clientIdentity) throws ConnectException {
        return ConnectHandlerUtil.captureString(out -> httpServer.serveStatus(out));
    }

    private ConnectResponse handleGetPerformance(String req, String clientIdentity) throws ConnectException {
        // The REST handler returns the raw performance object; the proto + Angular consumer expect
        // GetPerformanceResponse{success, performance_json:"<stringified raw>"}. Wrap on the Connect
        // side only — the REST handler output is left untouched for the legacy web UI.
        ConnectResponse raw = ConnectHandlerUtil.captureString(out ->
                PerformanceApiHandler.handle("GET", "/api/performance", null, out));
        try {
            org.json.JSONObject wrapped = new org.json.JSONObject();
            wrapped.put("success", true);
            wrapped.put("performanceJson", raw.body);
            return ConnectResponse.of(wrapped.toString());
        } catch (Exception e) {
            throw new ConnectException("internal", "An internal error occurred");
        }
    }

    private ConnectResponse handlePlayAudioTest(String req, String clientIdentity) throws ConnectException {
        return ConnectHandlerUtil.captureString(out ->
                AudioTestApiHandler.handle("POST", "/api/audio/test-avas", req, out));
    }

    private ConnectResponse handleListModels(String req, String clientIdentity) throws ConnectException {
        return ConnectHandlerUtil.captureString(out ->
                ModelsApiHandler.handle("GET", "/api/models/list", null, out));
    }

    private ConnectResponse handleDownloadModel(String req, String clientIdentity) throws ConnectException {
        return ConnectHandlerUtil.captureString(out ->
                ModelsApiHandler.handle("POST", "/api/models/download", req, out));
    }

    private ConnectResponse handleGetSohNominal(String req, String clientIdentity) throws ConnectException {
        ConnectResponse raw = ConnectHandlerUtil.captureString(out ->
                PerformanceApiHandler.handle("GET", "/api/performance/soh/nominal", null, out));
        try {
            org.json.JSONObject json = new org.json.JSONObject(raw.body);
            org.json.JSONObject wrapped = new org.json.JSONObject();
            if (!json.isNull("nominalKwh")) {
                wrapped.put("nominalKwh", json.getDouble("nominalKwh"));
            }
            wrapped.put("nominalSource", json.optString("nominalSource", "unset"));
            return ConnectResponse.of(wrapped.toString());
        } catch (Exception e) {
            throw new ConnectException("internal", "An internal error occurred");
        }
    }

    private ConnectResponse handleSetSohNominal(String req, String clientIdentity) throws ConnectException {
        // Pass request JSON through — body may contain nominalKwh or null.
        final String body;
        String tmp = req;
        if (tmp != null) {
            try {
                org.json.JSONObject reqJson = new org.json.JSONObject(req);
                org.json.JSONObject restBody = new org.json.JSONObject();
                if (reqJson.has("nominalKwh")) {
                    restBody.put("nominalKwh", reqJson.isNull("nominalKwh") ? org.json.JSONObject.NULL : reqJson.getDouble("nominalKwh"));
                } else {
                    restBody.put("nominalKwh", org.json.JSONObject.NULL);
                }
                tmp = restBody.toString();
            } catch (Exception ignored) {}
        }
        body = tmp;
        return ConnectHandlerUtil.captureString(out ->
                PerformanceApiHandler.handle("POST", "/api/performance/soh/nominal", body, out));
    }

    private ConnectResponse handleGetSohStatus(String req, String clientIdentity) throws ConnectException {
        ConnectResponse raw = ConnectHandlerUtil.captureString(out ->
                PerformanceApiHandler.handle("GET", "/api/performance/soh", null, out));
        try {
            org.json.JSONObject json = new org.json.JSONObject(raw.body);
            org.json.JSONObject wrapped = new org.json.JSONObject();
            wrapped.put("success", json.optBoolean("success", false));
            wrapped.put("nominalCapacityKwh", json.optDouble("nominalCapacityKwh", 0.0));
            wrapped.put("nominalSource", json.optString("nominalSource", "unset"));
            wrapped.put("displaySoh", json.optDouble("displaySoh", 0.0));
            wrapped.put("displaySource", json.optString("displaySource", "unavailable"));
            if (json.has("error")) wrapped.put("error", json.optString("error", ""));
            return ConnectResponse.of(wrapped.toString());
        } catch (Exception e) {
            throw new ConnectException("internal", "An internal error occurred");
        }
    }

    private ConnectResponse handleResetSoh(String req, String clientIdentity) throws ConnectException {
        return ConnectHandlerUtil.captureString(out ->
                PerformanceApiHandler.handle("POST", "/api/performance/soh/reset", req, out));
    }

    private ConnectResponse handleResetPerformance(String req, String clientIdentity) throws ConnectException {
        // req is a JSON object with "categories" array field from the proto.
        ConnectResponse raw = ConnectHandlerUtil.captureString(out ->
                PerformanceApiHandler.handle("POST", "/api/performance/reset", req, out));
        try {
            org.json.JSONObject json = new org.json.JSONObject(raw.body);
            org.json.JSONObject wrapped = new org.json.JSONObject();
            wrapped.put("success", json.optBoolean("success", false));
            if (json.has("results")) {
                wrapped.put("resultsJson", json.getJSONObject("results").toString());
            }
            if (json.has("error")) wrapped.put("error", json.optString("error", ""));
            return ConnectResponse.of(wrapped.toString());
        } catch (Exception e) {
            throw new ConnectException("internal", "An internal error occurred");
        }
    }

    private ConnectResponse handleGetParkingDelta(String req, String clientIdentity) throws ConnectException {
        String path = "/api/performance/parking-delta";
        try {
            org.json.JSONObject reqJson = new org.json.JSONObject(req == null ? "{}" : req);
            int maxAgeHours = reqJson.optInt("maxAgeHours", 72);
            if (maxAgeHours > 0) path = path + "?maxAgeHours=" + maxAgeHours;
        } catch (Exception ignored) {}
        final String finalPath = path;
        ConnectResponse raw = ConnectHandlerUtil.captureString(out ->
                PerformanceApiHandler.handle("GET", finalPath, null, out));
        try {
            org.json.JSONObject json = new org.json.JSONObject(raw.body);
            org.json.JSONObject wrapped = new org.json.JSONObject();
            boolean available = !json.optBoolean("available", false) ? json.length() > 1 : json.optBoolean("available", false);
            wrapped.put("available", available);
            if (available) wrapped.put("rawJson", raw.body);
            return ConnectResponse.of(wrapped.toString());
        } catch (Exception e) {
            throw new ConnectException("internal", "An internal error occurred");
        }
    }

    private ConnectResponse handleGetLastCharge(String req, String clientIdentity) throws ConnectException {
        String path = "/api/performance/last-charge";
        try {
            org.json.JSONObject reqJson = new org.json.JSONObject(req == null ? "{}" : req);
            int hoursBack = reqJson.optInt("hoursBack", 24);
            if (hoursBack > 0) path = path + "?hoursBack=" + hoursBack;
        } catch (Exception ignored) {}
        final String finalPath = path;
        ConnectResponse raw = ConnectHandlerUtil.captureString(out ->
                PerformanceApiHandler.handle("GET", finalPath, null, out));
        try {
            org.json.JSONObject json = new org.json.JSONObject(raw.body);
            org.json.JSONObject wrapped = new org.json.JSONObject();
            boolean available = !json.optBoolean("available", false) ? json.length() > 1 : json.optBoolean("available", false);
            wrapped.put("available", available);
            if (available) wrapped.put("rawJson", raw.body);
            return ConnectResponse.of(wrapped.toString());
        } catch (Exception e) {
            throw new ConnectException("internal", "An internal error occurred");
        }
    }

    private ConnectResponse handleGetSelectedModel(String req, String clientIdentity) throws ConnectException {
        return ConnectHandlerUtil.captureString(out ->
                ModelsApiHandler.handle("GET", "/api/models/selected", null, out));
    }

    private ConnectResponse handleSetSelectedModel(String req, String clientIdentity) throws ConnectException {
        // req proto fields: modelId, color — pass through as-is (JSON keys match REST).
        return ConnectHandlerUtil.captureString(out ->
                ModelsApiHandler.handle("POST", "/api/models/selected", req, out));
    }

    private ConnectResponse handleGetModelsManifest(String req, String clientIdentity) throws ConnectException {
        ConnectResponse raw = ConnectHandlerUtil.captureString(out ->
                ModelsApiHandler.handle("GET", "/api/models/manifest", null, out));
        try {
            org.json.JSONObject wrapped = new org.json.JSONObject();
            wrapped.put("manifestJson", raw.body);
            return ConnectResponse.of(wrapped.toString());
        } catch (Exception e) {
            throw new ConnectException("internal", "An internal error occurred");
        }
    }
}
