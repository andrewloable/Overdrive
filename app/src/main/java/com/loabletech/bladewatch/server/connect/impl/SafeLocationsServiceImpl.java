package net.bladewatch.app.server.connect.impl;

import net.bladewatch.app.server.SafeLocationApiHandler;
import net.bladewatch.app.server.connect.ConnectDispatcher;
import net.bladewatch.app.server.connect.ConnectException;
import net.bladewatch.app.server.connect.ConnectHandlerUtil;

/**
 * Connect protocol handler for bladewatch.v1.SafeLocationsService.
 *
 * All routes use /api/surveillance/safe-locations (SafeLocationApiHandler):
 *   ListZones   → GET    /api/surveillance/safe-locations
 *   AddZone     → POST   /api/surveillance/safe-locations
 *   UpdateZone  → PUT    /api/surveillance/safe-locations  (id in request body)
 *   DeleteZone  → DELETE /api/surveillance/safe-locations  (id in request body)
 *   Toggle      → POST   /api/surveillance/safe-locations/toggle
 */
public class SafeLocationsServiceImpl {

    private static final String BASE = "/api/surveillance/safe-locations";

    public void register(ConnectDispatcher dispatcher) {
        dispatcher.register("bladewatch.v1.SafeLocationsService", "ListZones",
                this::handleListZones);
        dispatcher.register("bladewatch.v1.SafeLocationsService", "AddZone",
                this::handleAddZone);
        dispatcher.register("bladewatch.v1.SafeLocationsService", "UpdateZone",
                this::handleUpdateZone);
        dispatcher.register("bladewatch.v1.SafeLocationsService", "DeleteZone",
                this::handleDeleteZone);
        dispatcher.register("bladewatch.v1.SafeLocationsService", "Toggle",
                this::handleToggle);
    }

    private String handleListZones(String req) throws ConnectException {
        return ConnectHandlerUtil.captureString(out ->
                SafeLocationApiHandler.handle("GET", BASE, null, out));
    }

    private String handleAddZone(String req) throws ConnectException {
        return ConnectHandlerUtil.captureString(out ->
                SafeLocationApiHandler.handle("POST", BASE, req, out));
    }

    private String handleUpdateZone(String req) throws ConnectException {
        return ConnectHandlerUtil.captureString(out ->
                SafeLocationApiHandler.handle("PUT", BASE, req, out));
    }

    private String handleDeleteZone(String req) throws ConnectException {
        return ConnectHandlerUtil.captureString(out ->
                SafeLocationApiHandler.handle("DELETE", BASE, req, out));
    }

    private String handleToggle(String req) throws ConnectException {
        return ConnectHandlerUtil.captureString(out ->
                SafeLocationApiHandler.handle("POST", BASE + "/toggle", req, out));
    }
}
