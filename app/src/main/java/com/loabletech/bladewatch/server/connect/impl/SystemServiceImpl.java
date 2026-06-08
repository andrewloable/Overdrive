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
}
