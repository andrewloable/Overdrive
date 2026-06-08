package net.bladewatch.app.server.connect.impl;

import net.bladewatch.app.server.ExternalStorageApiHandler;
import net.bladewatch.app.server.FormatStorageApiHandler;
import net.bladewatch.app.server.QualitySettingsApiHandler;
import net.bladewatch.app.server.connect.ConnectDispatcher;
import net.bladewatch.app.server.connect.ConnectException;
import net.bladewatch.app.server.connect.ConnectHandlerUtil;
import net.bladewatch.app.server.connect.ConnectResponse;

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

    private ConnectResponse handleGetStorageSettings(String req, String clientIdentity) throws ConnectException {
        return ConnectHandlerUtil.captureString(out ->
                QualitySettingsApiHandler.handle("GET", "/api/settings/storage", null, out));
    }

    private ConnectResponse handleSetStorageSettings(String req, String clientIdentity) throws ConnectException {
        return ConnectHandlerUtil.captureString(out ->
                QualitySettingsApiHandler.handle("POST", "/api/settings/storage", req, out));
    }

    private ConnectResponse handleGetExternalStorage(String req, String clientIdentity) throws ConnectException {
        return ConnectHandlerUtil.captureString(out ->
                ExternalStorageApiHandler.handle("/api/storage/external", "GET", null, out));
    }

    private ConnectResponse handleSetExternalConfig(String req, String clientIdentity) throws ConnectException {
        return ConnectHandlerUtil.captureString(out ->
                ExternalStorageApiHandler.handle("/api/storage/external/config", "POST", req, out));
    }

    private ConnectResponse handleTriggerCleanup(String req, String clientIdentity) throws ConnectException {
        return ConnectHandlerUtil.captureString(out ->
                ExternalStorageApiHandler.handle("/api/storage/external/cleanup", "POST", req, out));
    }

    private ConnectResponse handlePreviewCleanup(String req, String clientIdentity) throws ConnectException {
        // Optional bytes_to_free (canonical JSON "bytesToFree"); 0/absent = handler default.
        long bytesToFree = 0L;
        if (req != null && !req.isEmpty()) {
            try {
                bytesToFree = new org.json.JSONObject(req).optLong("bytesToFree", 0L);
            } catch (Exception ignored) {}
        }
        final String path = bytesToFree > 0
                ? "/api/storage/external/preview?bytesToFree=" + bytesToFree
                : "/api/storage/external/preview";
        return ConnectHandlerUtil.captureString(out ->
                ExternalStorageApiHandler.handle(path, "GET", null, out));
    }

    private ConnectResponse handleRefreshExternalStorage(String req, String clientIdentity) throws ConnectException {
        return ConnectHandlerUtil.captureString(out ->
                ExternalStorageApiHandler.handle("/api/storage/external/refresh", "POST", req, out));
    }

    private ConnectResponse handleListFormatVolumes(String req, String clientIdentity) throws ConnectException {
        return ConnectHandlerUtil.captureString(out ->
                FormatStorageApiHandler.handle("/api/storage/format", "GET", null, out));
    }

    private ConnectResponse handleFormatVolume(String req, String clientIdentity) throws ConnectException {
        return ConnectHandlerUtil.captureString(out ->
                FormatStorageApiHandler.handle("/api/storage/format", "POST", req, out));
    }
}
