package net.bladewatch.app.server.connect.impl;

import net.bladewatch.app.daemon.CameraDaemon;
import net.bladewatch.app.server.HttpResponse;
import net.bladewatch.app.server.connect.ConnectDispatcher;
import net.bladewatch.app.server.connect.ConnectException;
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

    private String invoke(String method, String uri, String body) throws ConnectException {
        TripAnalyticsManager tam = CameraDaemon.getTripAnalyticsManager();
        if (tam == null) {
            throw new ConnectException("unavailable", "Trip analytics not initialized");
        }
        TripApiHandler handler = new TripApiHandler(tam);
        JSONObject result = handler.handleRequest(uri, method, null, body);
        if (result == null) {
            throw new ConnectException("internal", "No response from TripApiHandler");
        }
        result.remove("_status");
        return result.toString();
    }

    private String handleListTrips(String req) throws ConnectException {
        return invoke("GET", "/api/trips", req);
    }

    private String handleGetTrip(String req) throws ConnectException {
        try {
            long id = new JSONObject(req).getLong("id");
            return invoke("GET", "/api/trips/" + id, req);
        } catch (Exception e) {
            throw new ConnectException("invalid_argument", "Missing or invalid trip id");
        }
    }

    private String handleDeleteTrip(String req) throws ConnectException {
        try {
            long id = new JSONObject(req).getLong("id");
            return invoke("DELETE", "/api/trips/" + id, req);
        } catch (Exception e) {
            throw new ConnectException("invalid_argument", "Missing or invalid trip id");
        }
    }

    private String handleGetSummary(String req) throws ConnectException {
        return invoke("GET", "/api/trips/summary", req);
    }

    private String handleGetDna(String req) throws ConnectException {
        return invoke("GET", "/api/trips/dna", req);
    }

    private String handleGetRange(String req) throws ConnectException {
        return invoke("GET", "/api/trips/range", req);
    }

    private String handleGetConfig(String req) throws ConnectException {
        return invoke("GET", "/api/trips/config", null);
    }

    private String handleSetConfig(String req) throws ConnectException {
        return invoke("POST", "/api/trips/config", req);
    }

    private String handleGetStorage(String req) throws ConnectException {
        return invoke("GET", "/api/trips/storage", null);
    }

    private String handleSetStorage(String req) throws ConnectException {
        return invoke("POST", "/api/trips/storage", req);
    }

    private String handleSyncTrips(String req) throws ConnectException {
        return invoke("POST", "/api/trips/sync", req);
    }

    private String handleGetTelemetry(String req) throws ConnectException {
        try {
            long id = new JSONObject(req).getLong("id");
            return invoke("GET", "/api/trips/" + id + "/telemetry", req);
        } catch (Exception e) {
            throw new ConnectException("invalid_argument", "Missing or invalid trip id");
        }
    }

    private String handleGetSimilarTrips(String req) throws ConnectException {
        try {
            long id = new JSONObject(req).getLong("id");
            return invoke("GET", "/api/trips/" + id + "/similar", req);
        } catch (Exception e) {
            throw new ConnectException("invalid_argument", "Missing or invalid trip id");
        }
    }

    private String handleGetGpsTrace(String req) throws ConnectException {
        try {
            long id = new JSONObject(req).getLong("id");
            return invoke("GET", "/api/trips/" + id + "/gps", req);
        } catch (Exception e) {
            throw new ConnectException("invalid_argument", "Missing or invalid trip id");
        }
    }
}
