package net.bladewatch.app.server.connect.impl;

import net.bladewatch.app.daemon.CameraDaemon;
import net.bladewatch.app.server.connect.ConnectDispatcher;
import net.bladewatch.app.server.connect.ConnectException;
import net.bladewatch.app.server.connect.ConnectHandlerUtil;
import net.bladewatch.app.server.connect.ConnectResponse;
import net.bladewatch.app.trips.TripAnalyticsManager;
import net.bladewatch.app.trips.TripApiHandler;

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

    private TripApiHandler cachedHandler;

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

    private ConnectResponse invoke(String method, String uri, String body) throws ConnectException {
        TripAnalyticsManager tam = CameraDaemon.getTripAnalyticsManager();
        if (tam == null) {
            throw new ConnectException("unavailable", "Trip analytics not initialized");
        }
        if (cachedHandler == null) {
            cachedHandler = new TripApiHandler(tam);
        }
        TripApiHandler handler = cachedHandler;
        JSONObject result = handler.handleRequest(uri, method, null, body);
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
        return ConnectResponse.of(result.toString());
    }

    private ConnectResponse handleListTrips(String req, String clientIdentity) throws ConnectException {
        return invoke("GET", "/api/trips", req);
    }

    private ConnectResponse handleGetTrip(String req, String clientIdentity) throws ConnectException {
        long id = ConnectHandlerUtil.requireLong(req, "id");
        return invoke("GET", "/api/trips/" + id, req);
    }

    private ConnectResponse handleDeleteTrip(String req, String clientIdentity) throws ConnectException {
        long id = ConnectHandlerUtil.requireLong(req, "id");
        return invoke("DELETE", "/api/trips/" + id, req);
    }

    private ConnectResponse handleGetSummary(String req, String clientIdentity) throws ConnectException {
        return invoke("GET", "/api/trips/summary", req);
    }

    private ConnectResponse handleGetDna(String req, String clientIdentity) throws ConnectException {
        return invoke("GET", "/api/trips/dna", req);
    }

    private ConnectResponse handleGetRange(String req, String clientIdentity) throws ConnectException {
        return invoke("GET", "/api/trips/range", req);
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
        long id = ConnectHandlerUtil.requireLong(req, "id");
        return invoke("GET", "/api/trips/" + id + "/telemetry", req);
    }

    private ConnectResponse handleGetSimilarTrips(String req, String clientIdentity) throws ConnectException {
        long id = ConnectHandlerUtil.requireLong(req, "id");
        return invoke("GET", "/api/trips/" + id + "/similar", req);
    }

    private ConnectResponse handleGetGpsTrace(String req, String clientIdentity) throws ConnectException {
        long id = ConnectHandlerUtil.requireLong(req, "id");
        return invoke("GET", "/api/trips/" + id + "/gps", req);
    }
}
