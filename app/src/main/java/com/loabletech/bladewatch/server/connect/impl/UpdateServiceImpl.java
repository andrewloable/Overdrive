package net.bladewatch.app.server.connect.impl;

import net.bladewatch.app.server.UpdateApiHandler;
import net.bladewatch.app.server.connect.ConnectDispatcher;
import net.bladewatch.app.server.connect.ConnectException;
import net.bladewatch.app.server.connect.ConnectHandlerUtil;

/**
 * Connect protocol handler for bladewatch.v1.UpdateService.
 *
 * Routes:
 *   /bladewatch.v1.UpdateService/CheckUpdate    → GET  /api/update/check
 *   /bladewatch.v1.UpdateService/GetPreview     → GET  /api/update/preview
 *   /bladewatch.v1.UpdateService/InstallUpdate  → POST /api/update/install?confirm=true
 *   /bladewatch.v1.UpdateService/GetProgress    → GET  /api/update/progress
 */
public class UpdateServiceImpl {

    public void register(ConnectDispatcher dispatcher) {
        dispatcher.register("bladewatch.v1.UpdateService", "CheckUpdate", this::handleCheckUpdate);
        dispatcher.register("bladewatch.v1.UpdateService", "GetPreview", this::handleGetPreview);
        dispatcher.register("bladewatch.v1.UpdateService", "InstallUpdate", this::handleInstallUpdate);
        dispatcher.register("bladewatch.v1.UpdateService", "GetProgress", this::handleGetProgress);
    }

    private String handleCheckUpdate(String req) throws ConnectException {
        return ConnectHandlerUtil.captureString(out ->
                UpdateApiHandler.handle("GET", "/api/update/check", null, out));
    }

    private String handleGetPreview(String req) throws ConnectException {
        return ConnectHandlerUtil.captureString(out ->
                UpdateApiHandler.handle("GET", "/api/update/preview", null, out));
    }

    private String handleInstallUpdate(String req) throws ConnectException {
        return ConnectHandlerUtil.captureString(out ->
                UpdateApiHandler.handle("POST", "/api/update/install?confirm=true", req, out));
    }

    private String handleGetProgress(String req) throws ConnectException {
        return ConnectHandlerUtil.captureString(out ->
                UpdateApiHandler.handle("GET", "/api/update/progress", null, out));
    }
}
