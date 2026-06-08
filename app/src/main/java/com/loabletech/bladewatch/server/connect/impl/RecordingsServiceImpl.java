package net.bladewatch.app.server.connect.impl;

import net.bladewatch.app.server.RecordingsApiHandler;
import net.bladewatch.app.server.connect.ConnectDispatcher;
import net.bladewatch.app.server.connect.ConnectException;
import net.bladewatch.app.server.connect.ConnectHandlerUtil;
import net.bladewatch.app.server.connect.ConnectResponse;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.LinkedHashSet;
import java.util.Locale;

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
 *   GetInflightStatus → GET   /api/recordings/inflight/{filename}
 *   GetEventTimeline → GET    /api/events/{filename}
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

    /**
     * Build the REST query string for /api/recordings from a ListRecordingsRequest JSON body, so
     * Connect clients can filter and paginate (the request fields were previously ignored).
     * Maps proto canonical names → REST query params: pageSize→pageSize, classFilter→class,
     * severityFilter→severity, proximityFilter→proximity. Values are passed raw to match the REST
     * parseQuery() (which does not URL-decode); the values are controlled enums/dates/int/comma-lists.
     */
    private static String buildListQuery(String req) {
        if (req == null || req.isEmpty()) return "";
        JSONObject r;
        try {
            r = new JSONObject(req);
        } catch (JSONException e) {
            return "";
        }
        StringBuilder q = new StringBuilder();
        appendParam(q, "type", r.optString("type", ""));
        appendParam(q, "date", r.optString("date", ""));
        int page = r.optInt("page", 0);
        if (page > 0) appendParam(q, "page", String.valueOf(page));
        int pageSize = r.optInt("pageSize", 0);
        if (pageSize > 0) appendParam(q, "pageSize", String.valueOf(pageSize));
        appendParam(q, "class", r.optString("classFilter", ""));
        appendParam(q, "severity", r.optString("severityFilter", ""));
        appendParam(q, "proximity", r.optString("proximityFilter", ""));
        return q.length() == 0 ? "" : "?" + q;
    }

    private static void appendParam(StringBuilder q, String key, String value) {
        if (value == null || value.isEmpty() || value.indexOf('&') >= 0 || value.indexOf('=') >= 0) {
            return;
        }
        if (q.length() > 0) q.append('&');
        q.append(key).append('=').append(value);
    }

    private ConnectResponse handleListRecordings(String req, String clientIdentity) throws ConnectException {
        final String path = "/api/recordings" + buildListQuery(req);
        ConnectResponse raw = ConnectHandlerUtil.captureString(out ->
                RecordingsApiHandler.handle("GET", path, null, out));
        // The handler emits an enriched `actors` array per recording; the proto RecordingEntry models
        // detected_classes as repeated string (consumed by the Angular events page). Derive
        // detectedClasses from the distinct actor class names so the proto field populates.
        try {
            JSONObject result = new JSONObject(raw.body);
            JSONArray recs = result.optJSONArray("recordings");
            if (recs != null) {
                for (int i = 0; i < recs.length(); i++) {
                    JSONObject rec = recs.optJSONObject(i);
                    if (rec == null || rec.has("detectedClasses")) continue;
                    JSONArray actors = rec.optJSONArray("actors");
                    if (actors == null) continue;
                    LinkedHashSet<String> classes = new LinkedHashSet<>();
                    for (int j = 0; j < actors.length(); j++) {
                        JSONObject a = actors.optJSONObject(j);
                        if (a == null) continue;
                        String c = a.optString("class", "").toLowerCase(Locale.US);
                        if (!c.isEmpty()) classes.add(c);
                    }
                    JSONArray dc = new JSONArray();
                    for (String c : classes) dc.put(c);
                    rec.put("detectedClasses", dc);
                }
            }
            return ConnectResponse.of(result.toString());
        } catch (JSONException e) {
            throw new ConnectException("internal", "An internal error occurred");
        }
    }

    private ConnectResponse handleGetDates(String req, String clientIdentity) throws ConnectException {
        return ConnectHandlerUtil.captureString(out ->
                RecordingsApiHandler.handle("GET", "/api/recordings/dates", null, out));
    }

    private ConnectResponse handleGetStats(String req, String clientIdentity) throws ConnectException {
        ConnectResponse raw = ConnectHandlerUtil.captureString(out ->
                RecordingsApiHandler.handle("GET", "/api/recordings/stats", null, out));
        // The handler emits a FLAT object (normalCount/sentrySize/...); the proto GetStatsResponse
        // expects a nested `stats` (RecordingStats) with normalised names. Reshape into the nested
        // shape (Kotlin reads stats.recordingsCount/etc; the Angular consumer reads stats.stats.*).
        try {
            JSONObject flat = new JSONObject(raw.body);
            JSONObject stats = new JSONObject();
            stats.put("recordingsSizeBytes", flat.optLong("normalSize"));
            stats.put("surveillanceSizeBytes", flat.optLong("sentrySize"));
            stats.put("proximitySizeBytes", flat.optLong("proximitySize"));
            stats.put("recordingsCount", flat.optInt("normalCount"));
            stats.put("surveillanceCount", flat.optInt("sentryCount"));
            stats.put("proximityCount", flat.optInt("proximityCount"));
            stats.put("totalSizeBytes", flat.optLong("totalSize"));
            stats.put("totalCount", flat.optInt("totalCount"));
            return ConnectResponse.of(new JSONObject().put("stats", stats).toString());
        } catch (JSONException e) {
            throw new ConnectException("internal", "An internal error occurred");
        }
    }

    private ConnectResponse handleDeleteRecording(String req, String clientIdentity) throws ConnectException {
        String filename = ConnectHandlerUtil.requireString(req, "filename");
        return ConnectHandlerUtil.captureString(out ->
                RecordingsApiHandler.handle("DELETE", "/api/recordings/" + filename, null, out));
    }

    private ConnectResponse handleBatchDelete(String req, String clientIdentity) throws ConnectException {
        return ConnectHandlerUtil.captureString(out ->
                RecordingsApiHandler.handle("POST", "/api/recordings/batch-delete", req, out));
    }

    private ConnectResponse handleSyncCatalog(String req, String clientIdentity) throws ConnectException {
        return ConnectHandlerUtil.captureString(out ->
                RecordingsApiHandler.handle("POST", "/api/recordings/sync", req, out));
    }

    private ConnectResponse handleGetInflightStatus(String req, String clientIdentity) throws ConnectException {
        String filename = ConnectHandlerUtil.requireString(req, "filename");
        return ConnectHandlerUtil.captureString(out ->
                RecordingsApiHandler.handle("GET", "/api/recordings/inflight/" + filename, null, out));
    }

    private ConnectResponse handleGetEventTimeline(String req, String clientIdentity) throws ConnectException {
        String filename = ConnectHandlerUtil.requireString(req, "filename");
        ConnectResponse raw = ConnectHandlerUtil.captureString(out ->
                RecordingsApiHandler.handle("GET", "/api/events/" + filename, null, out));
        // The handler returns the rich v3 sidecar object; the proto delivers it verbatim as the
        // timeline_json string blob so clients can parse it without a flattened schema.
        try {
            return ConnectResponse.of(new JSONObject().put("timelineJson", raw.body).toString());
        } catch (JSONException e) {
            throw new ConnectException("internal", "An internal error occurred");
        }
    }
}
