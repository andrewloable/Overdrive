package net.bladewatch.app.server.connect.impl;

import net.bladewatch.app.server.AudioTestApiHandler;
import net.bladewatch.app.server.HttpServer;
import net.bladewatch.app.server.ModelsApiHandler;
import net.bladewatch.app.server.PerformanceApiHandler;
import net.bladewatch.app.server.connect.ConnectDispatcher;
import net.bladewatch.app.server.connect.ConnectException;
import net.bladewatch.app.server.connect.ConnectHandlerUtil;

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

    private String handleGetStatus(String req) throws ConnectException {
        return ConnectHandlerUtil.captureString(out -> httpServer.serveStatus(out));
    }

    private String handleGetPerformance(String req) throws ConnectException {
        return ConnectHandlerUtil.captureString(out ->
                PerformanceApiHandler.handle("GET", "/api/performance", null, out));
    }

    private String handlePlayAudioTest(String req) throws ConnectException {
        return ConnectHandlerUtil.captureString(out ->
                AudioTestApiHandler.handle("POST", "/api/audio/test-avas", req, out));
    }

    private String handleListModels(String req) throws ConnectException {
        return ConnectHandlerUtil.captureString(out ->
                ModelsApiHandler.handle("GET", "/api/models/list", null, out));
    }

    private String handleDownloadModel(String req) throws ConnectException {
        return ConnectHandlerUtil.captureString(out ->
                ModelsApiHandler.handle("POST", "/api/models/download", req, out));
    }
}
