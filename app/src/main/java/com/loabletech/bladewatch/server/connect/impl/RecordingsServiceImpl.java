package net.bladewatch.app.server.connect.impl;

import net.bladewatch.app.server.RecordingsApiHandler;
import net.bladewatch.app.server.connect.ConnectDispatcher;
import net.bladewatch.app.server.connect.ConnectException;
import net.bladewatch.app.server.connect.ConnectHandlerUtil;

/**
 * Connect protocol handler for bladewatch.v1.RecordingsService.
 *
 * Routes:
 *   ListRecordings   → GET    /api/recordings
 *   GetDates         → GET    /api/recordings/dates
 *   GetStats         → GET    /api/recordings/stats
 *   DeleteRecording  → DELETE /api/recordings/{filename}
 *   BatchDelete      → POST   /api/recordings/batch-delete
 *   SyncCatalog      → POST   /api/recordings/sync
 *   GetInflightStatus → GET   /api/recordings/inflight
 *   GetEventTimeline → GET    /api/recordings/events
 */
public class RecordingsServiceImpl {

    public void register(ConnectDispatcher dispatcher) {
        dispatcher.register("bladewatch.v1.RecordingsService", "ListRecordings",
                this::handleListRecordings);
        dispatcher.register("bladewatch.v1.RecordingsService", "GetDates",
                this::handleGetDates);
        dispatcher.register("bladewatch.v1.RecordingsService", "GetStats",
                this::handleGetStats);
        dispatcher.register("bladewatch.v1.RecordingsService", "DeleteRecording",
                this::handleDeleteRecording);
        dispatcher.register("bladewatch.v1.RecordingsService", "BatchDelete",
                this::handleBatchDelete);
        dispatcher.register("bladewatch.v1.RecordingsService", "SyncCatalog",
                this::handleSyncCatalog);
        dispatcher.register("bladewatch.v1.RecordingsService", "GetInflightStatus",
                this::handleGetInflightStatus);
        dispatcher.register("bladewatch.v1.RecordingsService", "GetEventTimeline",
                this::handleGetEventTimeline);
    }

    private String handleListRecordings(String req) throws ConnectException {
        return ConnectHandlerUtil.captureString(out ->
                RecordingsApiHandler.handle("GET", "/api/recordings", null, out));
    }

    private String handleGetDates(String req) throws ConnectException {
        return ConnectHandlerUtil.captureString(out ->
                RecordingsApiHandler.handle("GET", "/api/recordings/dates", null, out));
    }

    private String handleGetStats(String req) throws ConnectException {
        return ConnectHandlerUtil.captureString(out ->
                RecordingsApiHandler.handle("GET", "/api/recordings/stats", null, out));
    }

    private String handleDeleteRecording(String req) throws ConnectException {
        try {
            org.json.JSONObject j = new org.json.JSONObject(req);
            String filename = j.optString("filename", "");
            return ConnectHandlerUtil.captureString(out ->
                    RecordingsApiHandler.handle("DELETE", "/api/recordings/" + filename, null, out));
        } catch (org.json.JSONException e) {
            throw new ConnectException("invalid_argument", "Invalid request JSON: " + e.getMessage());
        }
    }

    private String handleBatchDelete(String req) throws ConnectException {
        return ConnectHandlerUtil.captureString(out ->
                RecordingsApiHandler.handle("POST", "/api/recordings/batch-delete", req, out));
    }

    private String handleSyncCatalog(String req) throws ConnectException {
        return ConnectHandlerUtil.captureString(out ->
                RecordingsApiHandler.handle("POST", "/api/recordings/sync", req, out));
    }

    private String handleGetInflightStatus(String req) throws ConnectException {
        return ConnectHandlerUtil.captureString(out ->
                RecordingsApiHandler.handle("GET", "/api/recordings/inflight", null, out));
    }

    private String handleGetEventTimeline(String req) throws ConnectException {
        return ConnectHandlerUtil.captureString(out ->
                RecordingsApiHandler.handle("GET", "/api/recordings/events", null, out));
    }
}
