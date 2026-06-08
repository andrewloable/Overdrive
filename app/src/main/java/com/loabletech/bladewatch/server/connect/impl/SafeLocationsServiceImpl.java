package net.bladewatch.app.server.connect.impl;

import net.bladewatch.app.server.SafeLocationApiHandler;
import net.bladewatch.app.server.connect.ConnectDispatcher;
import net.bladewatch.app.server.connect.ConnectException;
import net.bladewatch.app.server.connect.ConnectHandlerUtil;
import net.bladewatch.app.server.connect.ConnectResponse;

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

    private ConnectResponse handleListZones(String req, String clientIdentity) throws ConnectException {
        return ConnectHandlerUtil.captureString(out ->
                SafeLocationApiHandler.handle("GET", BASE, null, out));
    }

    private ConnectResponse handleAddZone(String req, String clientIdentity) throws ConnectException {
        return ConnectHandlerUtil.captureString(out ->
                SafeLocationApiHandler.handle("POST", BASE, req, out));
    }

    private ConnectResponse handleUpdateZone(String req, String clientIdentity) throws ConnectException {
        return ConnectHandlerUtil.captureString(out ->
                SafeLocationApiHandler.handle("PUT", BASE, req, out));
    }

    private ConnectResponse handleDeleteZone(String req, String clientIdentity) throws ConnectException {
        return ConnectHandlerUtil.captureString(out ->
                SafeLocationApiHandler.handle("DELETE", BASE, req, out));
    }

    private ConnectResponse handleToggle(String req, String clientIdentity) throws ConnectException {
        return ConnectHandlerUtil.captureString(out ->
                SafeLocationApiHandler.handle("POST", BASE + "/toggle", req, out));
    }
}
