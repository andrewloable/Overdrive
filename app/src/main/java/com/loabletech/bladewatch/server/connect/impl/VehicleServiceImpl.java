package net.bladewatch.app.server.connect.impl;

import net.bladewatch.app.server.GpsApiHandler;
import net.bladewatch.app.server.VehicleControlApiHandler;
import net.bladewatch.app.server.connect.ConnectDispatcher;
import net.bladewatch.app.server.connect.ConnectException;
import net.bladewatch.app.server.connect.ConnectHandlerUtil;
import net.bladewatch.app.server.connect.ConnectResponse;

/**
 * Connect protocol handler for bladewatch.v1.VehicleService.
 *
 * VehicleControlApiHandler routes (GET/POST /api/vehicle/*):
 *   GetState, GetAcDiagnostics, GetSeatDiagnostics, Lock, Unlock, Trunk,
 *   MoveWindow, Flash, FindCar, SetClimate, SetSeat, SetLights, SetAdas,
 *   SetBatteryHeat, GetChargingSchedule, SetChargingSchedule,
 *   GetChargeCap, SetChargeCap
 *
 * GpsApiHandler routes:
 *   GetGpsLocation → GET  /api/gps
 *   StartGps       → POST /api/gps/start
 *   StopGps        → POST /api/gps/stop
 */
public class VehicleServiceImpl {

    public void register(ConnectDispatcher dispatcher) {
        dispatcher.register("bladewatch.v1.VehicleService", "GetState",
                this::handleGetState);
        dispatcher.register("bladewatch.v1.VehicleService", "GetAcDiagnostics",
                this::handleGetAcDiagnostics);
        dispatcher.register("bladewatch.v1.VehicleService", "GetSeatDiagnostics",
                this::handleGetSeatDiagnostics);
        dispatcher.register("bladewatch.v1.VehicleService", "Lock", this::handleLock);
        dispatcher.register("bladewatch.v1.VehicleService", "Unlock", this::handleUnlock);
        dispatcher.register("bladewatch.v1.VehicleService", "Trunk", this::handleTrunk);
        dispatcher.register("bladewatch.v1.VehicleService", "MoveWindow",
                this::handleMoveWindow);
        dispatcher.register("bladewatch.v1.VehicleService", "Flash", this::handleFlash);
        dispatcher.register("bladewatch.v1.VehicleService", "FindCar", this::handleFindCar);
        dispatcher.register("bladewatch.v1.VehicleService", "SetClimate",
                this::handleSetClimate);
        dispatcher.register("bladewatch.v1.VehicleService", "SetSeat", this::handleSetSeat);
        dispatcher.register("bladewatch.v1.VehicleService", "SetLights",
                this::handleSetLights);
        dispatcher.register("bladewatch.v1.VehicleService", "SetAdas", this::handleSetAdas);
        dispatcher.register("bladewatch.v1.VehicleService", "SetBatteryHeat",
                this::handleSetBatteryHeat);
        dispatcher.register("bladewatch.v1.VehicleService", "GetChargingSchedule",
                this::handleGetChargingSchedule);
        dispatcher.register("bladewatch.v1.VehicleService", "SetChargingSchedule",
                this::handleSetChargingSchedule);
        dispatcher.register("bladewatch.v1.VehicleService", "GetChargeCap",
                this::handleGetChargeCap);
        dispatcher.register("bladewatch.v1.VehicleService", "SetChargeCap",
                this::handleSetChargeCap);
        dispatcher.register("bladewatch.v1.VehicleService", "GetGpsLocation",
                this::handleGetGpsLocation);
        dispatcher.register("bladewatch.v1.VehicleService", "StartGps",
                this::handleStartGps);
        dispatcher.register("bladewatch.v1.VehicleService", "StopGps",
                this::handleStopGps);
    }

    private ConnectResponse handleGetState(String req, String clientIdentity) throws ConnectException {
        return ConnectHandlerUtil.captureString(out ->
                VehicleControlApiHandler.handle("GET", "/api/vehicle/state", null, out));
    }

    private ConnectResponse handleGetAcDiagnostics(String req, String clientIdentity) throws ConnectException {
        return ConnectHandlerUtil.captureString(out ->
                VehicleControlApiHandler.handle("GET", "/api/vehicle/ac-diagnostics", null, out));
    }

    private ConnectResponse handleGetSeatDiagnostics(String req, String clientIdentity) throws ConnectException {
        return ConnectHandlerUtil.captureString(out ->
                VehicleControlApiHandler.handle("GET", "/api/vehicle/seat-diagnostics", null, out));
    }

    private ConnectResponse handleLock(String req, String clientIdentity) throws ConnectException {
        return ConnectHandlerUtil.captureString(out ->
                VehicleControlApiHandler.handle("POST", "/api/vehicle/lock", req, out));
    }

    private ConnectResponse handleUnlock(String req, String clientIdentity) throws ConnectException {
        return ConnectHandlerUtil.captureString(out ->
                VehicleControlApiHandler.handle("POST", "/api/vehicle/unlock", req, out));
    }

    private ConnectResponse handleTrunk(String req, String clientIdentity) throws ConnectException {
        return ConnectHandlerUtil.captureString(out ->
                VehicleControlApiHandler.handle("POST", "/api/vehicle/trunk", req, out));
    }

    private ConnectResponse handleMoveWindow(String req, String clientIdentity) throws ConnectException {
        return ConnectHandlerUtil.captureString(out ->
                VehicleControlApiHandler.handle("POST", "/api/vehicle/window", req, out));
    }

    private ConnectResponse handleFlash(String req, String clientIdentity) throws ConnectException {
        return ConnectHandlerUtil.captureString(out ->
                VehicleControlApiHandler.handle("POST", "/api/vehicle/flash", req, out));
    }

    private ConnectResponse handleFindCar(String req, String clientIdentity) throws ConnectException {
        return ConnectHandlerUtil.captureString(out ->
                VehicleControlApiHandler.handle("POST", "/api/vehicle/find-car", req, out));
    }

    private ConnectResponse handleSetClimate(String req, String clientIdentity) throws ConnectException {
        return ConnectHandlerUtil.captureString(out ->
                VehicleControlApiHandler.handle("POST", "/api/vehicle/climate", req, out));
    }

    private ConnectResponse handleSetSeat(String req, String clientIdentity) throws ConnectException {
        return ConnectHandlerUtil.captureString(out ->
                VehicleControlApiHandler.handle("POST", "/api/vehicle/seat", req, out));
    }

    private ConnectResponse handleSetLights(String req, String clientIdentity) throws ConnectException {
        return ConnectHandlerUtil.captureString(out ->
                VehicleControlApiHandler.handle("POST", "/api/vehicle/lights", req, out));
    }

    private ConnectResponse handleSetAdas(String req, String clientIdentity) throws ConnectException {
        return ConnectHandlerUtil.captureString(out ->
                VehicleControlApiHandler.handle("POST", "/api/vehicle/adas", req, out));
    }

    private ConnectResponse handleSetBatteryHeat(String req, String clientIdentity) throws ConnectException {
        return ConnectHandlerUtil.captureString(out ->
                VehicleControlApiHandler.handle("POST", "/api/vehicle/battery-heat", req, out));
    }

    private ConnectResponse handleGetChargingSchedule(String req, String clientIdentity) throws ConnectException {
        return ConnectHandlerUtil.captureString(out ->
                VehicleControlApiHandler.handle("GET", "/api/vehicle/charging-schedule", null, out));
    }

    private ConnectResponse handleSetChargingSchedule(String req, String clientIdentity) throws ConnectException {
        return ConnectHandlerUtil.captureString(out ->
                VehicleControlApiHandler.handle("POST", "/api/vehicle/charging-schedule", req, out));
    }

    private ConnectResponse handleGetChargeCap(String req, String clientIdentity) throws ConnectException {
        return ConnectHandlerUtil.captureString(out ->
                VehicleControlApiHandler.handle("GET", "/api/vehicle/charge-cap", null, out));
    }

    private ConnectResponse handleSetChargeCap(String req, String clientIdentity) throws ConnectException {
        return ConnectHandlerUtil.captureString(out ->
                VehicleControlApiHandler.handle("POST", "/api/vehicle/charge-cap", req, out));
    }

    private ConnectResponse handleGetGpsLocation(String req, String clientIdentity) throws ConnectException {
        return ConnectHandlerUtil.captureString(out ->
                GpsApiHandler.handle("GET", "/api/gps", null, out));
    }

    private ConnectResponse handleStartGps(String req, String clientIdentity) throws ConnectException {
        return ConnectHandlerUtil.captureString(out ->
                GpsApiHandler.handle("POST", "/api/gps/start", req, out));
    }

    private ConnectResponse handleStopGps(String req, String clientIdentity) throws ConnectException {
        return ConnectHandlerUtil.captureString(out ->
                GpsApiHandler.handle("POST", "/api/gps/stop", req, out));
    }
}
