package net.bladewatch.app.server.connect.impl;

import net.bladewatch.app.daemon.CameraDaemon;
import net.bladewatch.app.server.connect.ConnectDispatcher;
import net.bladewatch.app.server.connect.ConnectException;
import net.bladewatch.app.server.connect.ConnectHandlerUtil;
import net.bladewatch.app.server.connect.ConnectResponse;
import net.bladewatch.app.trips.TripAnalyticsManager;
import net.bladewatch.app.trips.TripApiHandler;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Connect protocol handler for bladewatch.v1.TripsService.
 *
 * TripApiHandler is an instance method (not static), so this impl gets the
 * TripAnalyticsManager from CameraDaemon at call time (same pattern as HttpServer).
 *
 * Routes (TripApiHandler.handleRequest):
 *   ListTrips      → GET    /api/trips
 *   GetTrip        → GET    /api/trips/{id}
 *   DeleteTrip     → DELETE /api/trips/{id}
 *   GetSummary     → GET    /api/trips/summary
 *   GetDna         → GET    /api/trips/dna
 *   GetRange       → GET    /api/trips/range
 *   GetConfig      → GET    /api/trips/config
 *   SetConfig      → POST   /api/trips/config
 *   GetStorage     → GET    /api/trips/storage
 *   SetStorage     → POST   /api/trips/storage
 *   SyncTrips      → POST   /api/trips/sync
 *   GetTelemetry   → GET    /api/trips/{id}/telemetry
 *   GetSimilarTrips → GET   /api/trips/{id}/similar
 *   GetGpsTrace    → GET    /api/trips/{id}/gps
 */
public class TripsServiceImpl {

    private volatile TripApiHandler cachedHandler;

    public void register(ConnectDispatcher dispatcher) {
        dispatcher.register("bladewatch.v1.TripsService", "ListTrips",
                this::handleListTrips);
        dispatcher.register("bladewatch.v1.TripsService", "GetTrip",
                this::handleGetTrip);
        dispatcher.register("bladewatch.v1.TripsService", "DeleteTrip",
                this::handleDeleteTrip);
        dispatcher.register("bladewatch.v1.TripsService", "GetSummary",
                this::handleGetSummary);
        dispatcher.register("bladewatch.v1.TripsService", "GetDna",
                this::handleGetDna);
        dispatcher.register("bladewatch.v1.TripsService", "GetRange",
                this::handleGetRange);
        dispatcher.register("bladewatch.v1.TripsService", "GetConfig",
                this::handleGetConfig);
        dispatcher.register("bladewatch.v1.TripsService", "SetConfig",
                this::handleSetConfig);
        dispatcher.register("bladewatch.v1.TripsService", "GetStorage",
                this::handleGetStorage);
        dispatcher.register("bladewatch.v1.TripsService", "SetStorage",
                this::handleSetStorage);
        dispatcher.register("bladewatch.v1.TripsService", "SyncTrips",
                this::handleSyncTrips);
        dispatcher.register("bladewatch.v1.TripsService", "GetTelemetry",
                this::handleGetTelemetry);
        dispatcher.register("bladewatch.v1.TripsService", "GetSimilarTrips",
                this::handleGetSimilarTrips);
        dispatcher.register("bladewatch.v1.TripsService", "GetGpsTrace",
                this::handleGetGpsTrace);
    }

    /**
     * Run the REST handler and return its JSON body (after HTTP status check), so callers can
     * reshape the payload to match the proto wire shape before serialising.
     */
    private JSONObject invokeJson(String method, String uri, String body) throws ConnectException {
        TripAnalyticsManager tam = CameraDaemon.getTripAnalyticsManager();
        if (tam == null) {
            throw new ConnectException("unavailable", "Trip analytics not initialized");
        }
        if (cachedHandler == null) {
            cachedHandler = new TripApiHandler(tam);
        }
        JSONObject result = cachedHandler.handleRequest(uri, method, null, body);
        if (result == null) {
            throw new ConnectException("internal", "No response from TripApiHandler");
        }
        // Check HTTP-level status before removing the field — _status >= 400 is an error.
        int status = result.optInt("_status", 200);
        result.remove("_status");
        if (status >= 400) {
            String error = result.optString("error", result.optString("message", "Request failed"));
            String code = status == 404 ? "not_found"
                    : status == 410 ? "not_found"
                    : status == 400 ? "invalid_argument"
                    : "internal";
            throw new ConnectException(code, error);
        }
        return result;
    }

    private ConnectResponse invoke(String method, String uri, String body) throws ConnectException {
        return ConnectResponse.of(invokeJson(method, uri, body).toString());
    }

    /**
     * Wrap each element of a JSON array under {@code field} as a stringified-JSON blob, matching
     * proto messages that model the element as a single opaque {@code string <field>_json}.
     * e.g. telemetry [{t,s,..}] → [{"sampleJson":"{...}"}] so the Kotlin/TS client parses the blob.
     */
    private static void wrapArrayAsJsonBlob(JSONObject root, String arrayKey, String blobField)
            throws org.json.JSONException {
        JSONArray arr = root.optJSONArray(arrayKey);
        if (arr == null) return;
        JSONArray wrapped = new JSONArray();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject el = arr.optJSONObject(i);
            if (el == null) continue;
            wrapped.put(new JSONObject().put(blobField, el.toString()));
        }
        root.put(arrayKey, wrapped);
    }

    /**
     * Build a REST query string (?days=&limit=&offset=) from a request body, so Connect clients can
     * forward ListTrips/GetSummary/GetDna filters. TripApiHandler reads these only from the URI query
     * string (params==null path), never the body, so a path-only URI would always use the defaults
     * (days=7, limit=50, offset=0). Only fields the client actually set (non-zero) are appended.
     */
    private static String buildTripsQuery(String req, boolean withLimitOffset) {
        if (req == null || req.isEmpty()) return "";
        JSONObject r;
        try {
            r = new JSONObject(req);
        } catch (org.json.JSONException e) {
            return "";
        }
        StringBuilder q = new StringBuilder();
        int days = r.optInt("days", 0);
        if (days > 0) appendParam(q, "days", String.valueOf(days));
        if (withLimitOffset) {
            int limit = r.optInt("limit", 0);
            if (limit > 0) appendParam(q, "limit", String.valueOf(limit));
            int offset = r.optInt("offset", 0);
            if (offset > 0) appendParam(q, "offset", String.valueOf(offset));
        }
        return q.length() == 0 ? "" : "?" + q;
    }

    private static void appendParam(StringBuilder q, String key, String value) {
        if (value == null || value.isEmpty()) return;
        if (q.length() > 0) q.append('&');
        q.append(key).append('=').append(value);
    }

    private ConnectResponse handleListTrips(String req, String clientIdentity) throws ConnectException {
        return invoke("GET", "/api/trips" + buildTripsQuery(req, true), req);
    }

    private ConnectResponse handleGetTrip(String req, String clientIdentity) throws ConnectException {
        long id = ConnectHandlerUtil.requireLong(req, "id");
        JSONObject result = invokeJson("GET", "/api/trips/" + id, req);
        // TripApiHandler emits a FLAT trip object; the proto TripDetail expects a nested `summary`
        // (TripSummary) plus the DNA/elevation/micro_moments fields at the top level. Nest a copy of
        // the flat trip under `summary` — TripSummary parsing ignores the extra keys, and the
        // top-level TripDetail fields still parse from the flat trip.
        try {
            JSONObject trip = result.optJSONObject("trip");
            if (trip != null && !trip.has("summary")) {
                trip.put("summary", new JSONObject(trip.toString()));
            }
        } catch (org.json.JSONException e) {
            throw new ConnectException("internal", "An internal error occurred");
        }
        return ConnectResponse.of(result.toString());
    }

    private ConnectResponse handleDeleteTrip(String req, String clientIdentity) throws ConnectException {
        long id = ConnectHandlerUtil.requireLong(req, "id");
        return invoke("DELETE", "/api/trips/" + id, req);
    }

    private ConnectResponse handleGetSummary(String req, String clientIdentity) throws ConnectException {
        JSONObject result = invokeJson("GET", "/api/trips/summary" + buildTripsQuery(req, false), req);
        // proto WeeklyRollupEntry models each entry as a single string rollup_json blob.
        try {
            wrapArrayAsJsonBlob(result, "summary", "rollupJson");
        } catch (org.json.JSONException e) {
            throw new ConnectException("internal", "An internal error occurred");
        }
        return ConnectResponse.of(result.toString());
    }

    private ConnectResponse handleGetDna(String req, String clientIdentity) throws ConnectException {
        return invoke("GET", "/api/trips/dna" + buildTripsQuery(req, false), req);
    }

    private ConnectResponse handleGetRange(String req, String clientIdentity) throws ConnectException {
        JSONObject result = invokeJson("GET", "/api/trips/range", req);
        // proto GetRangeResponse models the estimate as a single string range_json blob; the handler
        // emits it as a nested `range` object.
        try {
            JSONObject range = result.optJSONObject("range");
            if (range != null) {
                result.put("rangeJson", range.toString());
                result.remove("range");
            }
        } catch (org.json.JSONException e) {
            throw new ConnectException("internal", "An internal error occurred");
        }
        return ConnectResponse.of(result.toString());
    }

    private ConnectResponse handleGetConfig(String req, String clientIdentity) throws ConnectException {
        return invoke("GET", "/api/trips/config", null);
    }

    private ConnectResponse handleSetConfig(String req, String clientIdentity) throws ConnectException {
        return invoke("POST", "/api/trips/config", req);
    }

    private ConnectResponse handleGetStorage(String req, String clientIdentity) throws ConnectException {
        return invoke("GET", "/api/trips/storage", null);
    }

    private ConnectResponse handleSetStorage(String req, String clientIdentity) throws ConnectException {
        return invoke("POST", "/api/trips/storage", req);
    }

    private ConnectResponse handleSyncTrips(String req, String clientIdentity) throws ConnectException {
        return invoke("POST", "/api/trips/sync", req);
    }

    private ConnectResponse handleGetTelemetry(String req, String clientIdentity) throws ConnectException {
        // proto GetTelemetryRequest.trip_id → json "tripId" (not "id").
        long id = ConnectHandlerUtil.requireLong(req, "tripId");
        JSONObject result = invokeJson("GET", "/api/trips/" + id + "/telemetry", req);
        // proto TelemetrySample models each sample as a single string sample_json blob.
        try {
            wrapArrayAsJsonBlob(result, "telemetry", "sampleJson");
        } catch (org.json.JSONException e) {
            throw new ConnectException("internal", "An internal error occurred");
        }
        return ConnectResponse.of(result.toString());
    }

    private ConnectResponse handleGetSimilarTrips(String req, String clientIdentity) throws ConnectException {
        // proto GetSimilarTripsRequest.trip_id → json "tripId" (not "id").
        long id = ConnectHandlerUtil.requireLong(req, "tripId");
        return invoke("GET", "/api/trips/" + id + "/similar", req);
    }

    private ConnectResponse handleGetGpsTrace(String req, String clientIdentity) throws ConnectException {
        // proto GetGpsTraceRequest.trip_id → json "tripId" (not "id").
        long id = ConnectHandlerUtil.requireLong(req, "tripId");
        JSONObject result = invokeJson("GET", "/api/trips/" + id + "/gps", req);
        // Handler emits gps as positional arrays [[lat,lon],...]; proto GpsPoint expects {lat,lon}.
        try {
            JSONArray gps = result.optJSONArray("gps");
            if (gps != null) {
                JSONArray pts = new JSONArray();
                for (int i = 0; i < gps.length(); i++) {
                    JSONArray p = gps.optJSONArray(i);
                    if (p == null || p.length() < 2) continue;
                    pts.put(new JSONObject().put("lat", p.optDouble(0)).put("lon", p.optDouble(1)));
                }
                result.put("gps", pts);
            }
        } catch (org.json.JSONException e) {
            throw new ConnectException("internal", "An internal error occurred");
        }
        return ConnectResponse.of(result.toString());
    }
}
