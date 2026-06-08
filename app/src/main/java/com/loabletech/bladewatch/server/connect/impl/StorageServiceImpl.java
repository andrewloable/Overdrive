package net.bladewatch.app.server.connect.impl;

import net.bladewatch.app.server.ExternalStorageApiHandler;
import net.bladewatch.app.server.FormatStorageApiHandler;
import net.bladewatch.app.server.QualitySettingsApiHandler;
import net.bladewatch.app.server.connect.ConnectDispatcher;
import net.bladewatch.app.server.connect.ConnectException;
import net.bladewatch.app.server.connect.ConnectHandlerUtil;

/**
 * Connect protocol handler for bladewatch.v1.StorageService.
 *
 * Routes:
 *   GetStorageSettings     → GET  /api/settings/storage    (QualitySettingsApiHandler)
 *   SetStorageSettings     → POST /api/settings/storage    (QualitySettingsApiHandler)
 *   GetExternalStorage     → GET  /api/storage/external    (ExternalStorageApiHandler)
 *   SetExternalConfig      → POST /api/storage/external/config   (ExternalStorageApiHandler)
 *   TriggerCleanup         → POST /api/storage/external/cleanup  (ExternalStorageApiHandler)
 *   PreviewCleanup         → GET  /api/storage/external/preview  (ExternalStorageApiHandler)
 *   RefreshExternalStorage → POST /api/storage/external/refresh  (ExternalStorageApiHandler)
 *   ListFormatVolumes      → GET  /api/storage/format      (FormatStorageApiHandler)
 *   FormatVolume           → POST /api/storage/format      (FormatStorageApiHandler)
 *
 * Note: FormatStorageApiHandler and ExternalStorageApiHandler use reversed parameter
 * order: handle(path, method, body, out).
 */
public class StorageServiceImpl {

    public void register(ConnectDispatcher dispatcher) {
        dispatcher.register("bladewatch.v1.StorageService", "GetStorageSettings",
                this::handleGetStorageSettings);
        dispatcher.register("bladewatch.v1.StorageService", "SetStorageSettings",
                this::handleSetStorageSettings);
        dispatcher.register("bladewatch.v1.StorageService", "GetExternalStorage",
                this::handleGetExternalStorage);
        dispatcher.register("bladewatch.v1.StorageService", "SetExternalConfig",
                this::handleSetExternalConfig);
        dispatcher.register("bladewatch.v1.StorageService", "TriggerCleanup",
                this::handleTriggerCleanup);
        dispatcher.register("bladewatch.v1.StorageService", "PreviewCleanup",
                this::handlePreviewCleanup);
        dispatcher.register("bladewatch.v1.StorageService", "RefreshExternalStorage",
                this::handleRefreshExternalStorage);
        dispatcher.register("bladewatch.v1.StorageService", "ListFormatVolumes",
                this::handleListFormatVolumes);
        dispatcher.register("bladewatch.v1.StorageService", "FormatVolume",
                this::handleFormatVolume);
    }

    private String handleGetStorageSettings(String req) throws ConnectException {
        return ConnectHandlerUtil.captureString(out ->
                QualitySettingsApiHandler.handle("GET", "/api/settings/storage", null, out));
    }

    private String handleSetStorageSettings(String req) throws ConnectException {
        return ConnectHandlerUtil.captureString(out ->
                QualitySettingsApiHandler.handle("POST", "/api/settings/storage", req, out));
    }

    private String handleGetExternalStorage(String req) throws ConnectException {
        return ConnectHandlerUtil.captureString(out ->
                ExternalStorageApiHandler.handle("/api/storage/external", "GET", null, out));
    }

    private String handleSetExternalConfig(String req) throws ConnectException {
        return ConnectHandlerUtil.captureString(out ->
                ExternalStorageApiHandler.handle("/api/storage/external/config", "POST", req, out));
    }

    private String handleTriggerCleanup(String req) throws ConnectException {
        return ConnectHandlerUtil.captureString(out ->
                ExternalStorageApiHandler.handle("/api/storage/external/cleanup", "POST", req, out));
    }

    private String handlePreviewCleanup(String req) throws ConnectException {
        return ConnectHandlerUtil.captureString(out ->
                ExternalStorageApiHandler.handle("/api/storage/external/preview", "GET", null, out));
    }

    private String handleRefreshExternalStorage(String req) throws ConnectException {
        return ConnectHandlerUtil.captureString(out ->
                ExternalStorageApiHandler.handle("/api/storage/external/refresh", "POST", req, out));
    }

    private String handleListFormatVolumes(String req) throws ConnectException {
        return ConnectHandlerUtil.captureString(out ->
                FormatStorageApiHandler.handle("/api/storage/format", "GET", null, out));
    }

    private String handleFormatVolume(String req) throws ConnectException {
        return ConnectHandlerUtil.captureString(out ->
                FormatStorageApiHandler.handle("/api/storage/format", "POST", req, out));
    }
}
