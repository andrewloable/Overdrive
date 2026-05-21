package com.overdrive.app.server;

import com.overdrive.app.byd.BydDataCollector;
import com.overdrive.app.byd.BydVehicleData;
import com.overdrive.app.byd.cloud.BydCloudConfig;
import com.overdrive.app.byd.routing.VehicleCommandRouter;
import com.overdrive.app.byd.routing.VehicleCommandRouter.CommandResult;
import com.overdrive.app.byd.routing.VehicleCommandRouter.VehicleCommand;
import com.overdrive.app.logging.DaemonLogger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;

/**
 * API handler for the Vehicle Control page. All write endpoints route
 * through {@link VehicleCommandRouter}, which decides per command whether to
 * attempt cloud first, fall back to SDK, or treat as cloud-only / SDK-only.
 *
 * Endpoints:
 *   GET  /api/vehicle/state         — current door/window/trunk/lock state
 *   GET  /api/vehicle/cloud-status  — BYD Cloud connection status
 *   GET  /api/vehicle/cloud-lock    — cached cloud lock state (REST refresh if stale)
 *   POST /api/vehicle/lock          — CLOUD_FIRST
 *   POST /api/vehicle/unlock        — CLOUD_FIRST
 *   POST /api/vehicle/trunk         — open=cloud-unlock+SDK, close/stop=SDK
 *   POST /api/vehicle/window        — area=0+close=CLOUD_FIRST, others SDK_ONLY
 *   POST /api/vehicle/flash         — CLOUD_FIRST (cloud-only on this gen)
 *   POST /api/vehicle/find-car      — CLOUD_FIRST (cloud-only on this gen)
 *   POST /api/vehicle/climate       — power=CLOUD_FIRST, set_temp/set_fan=SDK_ONLY
 *   POST /api/vehicle/seat          — SDK_ONLY
 *   POST /api/vehicle/lights        — SDK_ONLY
 *   POST /api/vehicle/adas          — SDK_ONLY
 *   POST /api/vehicle/battery-heat  — CLOUD_ONLY
 *   GET  /api/vehicle/charging-schedule  — local mirror { enabled, startChargeTime, endChargeTime, chargeWay }
 *   POST /api/vehicle/charging-schedule  — { startChargeTime, endChargeTime, chargeWay, enabled } CLOUD_ONLY
 *   GET  /api/vehicle/charge-cap         — { percent, enabled, supported } SDK_ONLY (BYDAutoChargingDevice.getChargeStopCapacityState)
 *   POST /api/vehicle/charge-cap         — { percent?, enabled? } SDK_ONLY
 */
public class VehicleControlApiHandler {

    private static final DaemonLogger logger = DaemonLogger.getInstance("VehicleControlApi");

    public static boolean handle(String method, String path, String body, OutputStream out) throws Exception {
        String cleanPath = path.contains("?") ? path.substring(0, path.indexOf("?")) : path;

        // GET /api/vehicle/state
        if (cleanPath.equals("/api/vehicle/state") && method.equals("GET")) {
            handleGetState(out);
            return true;
        }

        // GET /api/vehicle/cloud-status
        if (cleanPath.equals("/api/vehicle/cloud-status") && method.equals("GET")) {
            handleCloudStatus(out);
            return true;
        }

        // GET /api/vehicle/cloud-lock
        if (cleanPath.equals("/api/vehicle/cloud-lock") && method.equals("GET")) {
            handleCloudLock(out);
            return true;
        }

        // POST /api/vehicle/lock
        if (cleanPath.equals("/api/vehicle/lock") && method.equals("POST")) {
            handleLock(out);
            return true;
        }

        // POST /api/vehicle/unlock
        if (cleanPath.equals("/api/vehicle/unlock") && method.equals("POST")) {
            handleUnlock(out);
            return true;
        }

        // POST /api/vehicle/trunk
        if (cleanPath.equals("/api/vehicle/trunk") && method.equals("POST")) {
            handleTrunk(out, body);
            return true;
        }

        // POST /api/vehicle/window
        if (cleanPath.equals("/api/vehicle/window") && method.equals("POST")) {
            handleWindow(out, body);
            return true;
        }

        // POST /api/vehicle/flash
        if (cleanPath.equals("/api/vehicle/flash") && method.equals("POST")) {
            handleFlash(out);
            return true;
        }

        // POST /api/vehicle/climate
        if (cleanPath.equals("/api/vehicle/climate") && method.equals("POST")) {
            handleClimate(out, body);
            return true;
        }

        // POST /api/vehicle/seat
        if (cleanPath.equals("/api/vehicle/seat") && method.equals("POST")) {
            handleSeat(out, body);
            return true;
        }

        // POST /api/vehicle/lights
        if (cleanPath.equals("/api/vehicle/lights") && method.equals("POST")) {
            handleLights(out, body);
            return true;
        }

        // POST /api/vehicle/adas
        if (cleanPath.equals("/api/vehicle/adas") && method.equals("POST")) {
            handleAdas(out, body);
            return true;
        }

        // POST /api/vehicle/find-car
        if (cleanPath.equals("/api/vehicle/find-car") && method.equals("POST")) {
            handleFindCar(out);
            return true;
        }

        // POST /api/vehicle/battery-heat
        if (cleanPath.equals("/api/vehicle/battery-heat") && method.equals("POST")) {
            handleBatteryHeat(out, body);
            return true;
        }

        // GET /api/vehicle/charging-schedule
        if (cleanPath.equals("/api/vehicle/charging-schedule") && method.equals("GET")) {
            handleGetChargingSchedule(out);
            return true;
        }

        // POST /api/vehicle/charging-schedule
        if (cleanPath.equals("/api/vehicle/charging-schedule") && method.equals("POST")) {
            handleChargingSchedule(out, body);
            return true;
        }

        // GET /api/vehicle/charge-cap
        if (cleanPath.equals("/api/vehicle/charge-cap") && method.equals("GET")) {
            handleGetChargeCap(out);
            return true;
        }

        // POST /api/vehicle/charge-cap
        if (cleanPath.equals("/api/vehicle/charge-cap") && method.equals("POST")) {
            handleChargeCap(out, body);
            return true;
        }

        return false;
    }

    /**
     * Returns current vehicle state relevant to the control page:
     * doors, windows, trunk, lock status, SOC, range.
     */
    private static void handleGetState(OutputStream out) throws Exception {
        JSONObject response = new JSONObject();
        BydDataCollector collector = BydDataCollector.getInstance();
        BydVehicleData data = collector.getData();

        if (data == null) {
            response.put("success", false);
            response.put("error", Messages.get("errors.vehicle_data_unavailable"));
            HttpResponse.sendJson(out, response.toString());
            return;
        }

        response.put("success", true);

        // Door lock status: 1=locked, 2=unlocked, -1=unknown
        // Index: 0=LF, 1=RF, 2=LR, 3=RR, 4=trunk, 5=unused, 6=overall(derived)
        //
        // The BYDAutoDoorLockDevice service does not expose lock state to user-UID
        // processes on most BYD firmwares (returns INVALID(0) for every area).
        // So we overlay the BYD cloud snapshot's per-door lock fields here. If
        // both the SDK and cloud are unavailable, values stay at -1.
        // BYD bodywork SDK area numbering swaps L↔R on the FRONT axis vs the
        // physical doors: array index 0 (SDK "LEFT_FRONT") is physically
        // right-front, index 1 is left-front. The REAR axis on this car
        // matches the SDK declaration as-is — see DoorEventNotifier for the
        // open/close-event side of this mapping. The rear pair below is a
        // pre-existing assumption from this code path and has not yet been
        // field-verified for lock state; if a single-door bench test on a
        // real car shows rear lock state arriving with the same asymmetric
        // pattern, swap [2]↔[3] back to SDK order ([2]=lr, [3]=rr).
        JSONObject doors = new JSONObject();
        if (data.doorLockStatus != null && data.doorLockStatus.length >= 7) {
            doors.put("rf", data.doorLockStatus[0]);
            doors.put("lf", data.doorLockStatus[1]);
            doors.put("rr", data.doorLockStatus[2]);
            doors.put("lr", data.doorLockStatus[3]);
            doors.put("trunk", data.doorLockStatus[4]);
            doors.put("hood", data.doorLockStatus[5]);
            doors.put("overall", data.doorLockStatus[6]);
        }
        try {
            com.overdrive.app.byd.cloud.BydCloudDataProvider provider =
                    com.overdrive.app.byd.cloud.BydCloudDataProvider.getInstance();
            // Trigger an on-demand REST refresh if our cached snapshot is
            // stale. The call is internally rate-limited (30s cooldown) and
            // runs asynchronously; the *current* snapshot is used to render
            // this response, but the next request will see fresh data.
            new Thread(provider::refreshLockStateIfStale, "CloudLockRefresh").start();
            com.overdrive.app.byd.cloud.VehicleCloudSnapshot cs = provider.getSnapshot();
            if (cs != null && cs.hasValidLockState()) {
                // Cloud snapshot semantics:
                //   leftFrontDoorLock etc.: 1=UNLOCKED, 2=LOCKED (per pyBYD)
                // API contract semantics: 1=locked, 2=unlocked (inverted).
                int lf = cloudLockToApi(cs.leftFrontDoorLock);
                int rf = cloudLockToApi(cs.rightFrontDoorLock);
                int lr = cloudLockToApi(cs.leftRearDoorLock);
                int rr = cloudLockToApi(cs.rightRearDoorLock);
                if (lf != -1) doors.put("lf", lf);
                if (rf != -1) doors.put("rf", rf);
                if (lr != -1) doors.put("lr", lr);
                if (rr != -1) doors.put("rr", rr);
                int overall;
                if (cs.isAnyUnlocked()) overall = 2;
                else if (cs.isAllLocked()) overall = 1;
                else overall = -1;
                if (overall != -1) doors.put("overall", overall);
                doors.put("source", "cloud");
            }
        } catch (Exception e) {
            logger.debug("cloud-lock overlay failed: " + e.getMessage());
        }
        response.put("doors", doors);

        // Window open percent [1-6]: 0=closed, 100=fully open, -1=unknown
        // Index: 0=LF, 1=RF, 2=LR, 3=RR, 4=sunroof, 5=sunshade
        JSONObject windows = new JSONObject();
        if (data.windowOpenPercent != null && data.windowOpenPercent.length >= 4) {
            windows.put("lf", data.windowOpenPercent[0]);
            windows.put("rf", data.windowOpenPercent[1]);
            windows.put("lr", data.windowOpenPercent[2]);
            windows.put("rr", data.windowOpenPercent[3]);
            if (data.windowOpenPercent.length >= 5) windows.put("sunroof", data.windowOpenPercent[4]);
            if (data.windowOpenPercent.length >= 6) windows.put("sunshade", data.windowOpenPercent[5]);
        }
        response.put("windows", windows);

        // Trunk/tailgate status from extended bodywork
        JSONObject trunk = new JSONObject();
        // Back door status from feature ID (if available in toJson)
        // We use doorLockStatus[4] for trunk lock, and check body door status flags
        if (data.doorLockStatus != null && data.doorLockStatus.length >= 5) {
            trunk.put("lockStatus", data.doorLockStatus[4]);
        }
        response.put("trunk", trunk);

        // Sunroof
        JSONObject sunroof = new JSONObject();
        if (data.sunroofState != BydVehicleData.UNAVAILABLE) {
            sunroof.put("state", data.sunroofState);
        }
        if (data.sunroofPosition != BydVehicleData.UNAVAILABLE) {
            sunroof.put("position", data.sunroofPosition);
        }
        response.put("sunroof", sunroof);

        // Battery info for display
        JSONObject battery = new JSONObject();
        if (!Double.isNaN(data.socPercent)) battery.put("soc", data.socPercent);
        if (data.elecRangeKm != BydVehicleData.UNAVAILABLE) battery.put("rangeKm", data.elecRangeKm);
        if (data.bodyworkRangeKm != BydVehicleData.UNAVAILABLE) battery.put("bodyworkRangeKm", data.bodyworkRangeKm);
        response.put("battery", battery);

        // Lights
        JSONObject lights = new JSONObject();
        lights.put("lowBeam", data.lowBeam);
        lights.put("highBeam", data.highBeam);
        lights.put("hazard", data.hazard);
        lights.put("dayTimeLight", data.dayTimeLight);
        response.put("lights", lights);

        // ADAS
        JSONObject adas = new JSONObject();
        adas.put("speedLimitWarning", data.speedLimitWarning);
        response.put("adas", adas);

        // Seats — heating/cooling levels for driver/passenger ([0-2], 0=off)
        JSONObject seats = new JSONObject();
        if (data.seatHeat != null && data.seatHeat.length > 0) {
            JSONArray heat = new JSONArray();
            for (int v : data.seatHeat) heat.put(v);
            seats.put("heat", heat);
        }
        if (data.seatCool != null && data.seatCool.length > 0) {
            JSONArray cool = new JSONArray();
            for (int v : data.seatCool) cool.put(v);
            seats.put("cool", cool);
        }
        // ventilatedSeats: hardware capability. Cars without ventilated seats
        // (Atto 3 base, certain Seal trims) report hasFeature("SEAT_VENTILATING")=0
        // and the BYD cloud returns 1001 on VENTILATIONHEATING. JS uses this
        // to grey out the cool buttons.
        seats.put("ventilatedSupported", BydDataCollector.getInstance().isSeatVentilationSupported());
        response.put("seats", seats);

        // Climate — only report AC state if vehicle power is on (powerLevel >= 2)
        // Otherwise stale cached data shows AC on when car is actually off
        JSONObject climate = new JSONObject();
        boolean vehiclePoweredOn = (data.powerLevel != BydVehicleData.UNAVAILABLE && data.powerLevel >= 2);
        if (data.acStartState != BydVehicleData.UNAVAILABLE) {
            climate.put("acOn", vehiclePoweredOn && data.acStartState == 1);
        }
        if (!Double.isNaN(data.insideTempC)) climate.put("insideTempC", data.insideTempC);
        if (data.acWindMode != BydVehicleData.UNAVAILABLE) climate.put("windMode", data.acWindMode);
        if (data.acFanLevel != BydVehicleData.UNAVAILABLE && vehiclePoweredOn) climate.put("fanLevel", data.acFanLevel);
        response.put("climate", climate);

        // Tyres — per-corner pressure (kPa + PSI), temperature, and the three
        // independent state enums (pressure under/over, slow/fast leak, signal
        // lost). Indexed [FL, FR, RL, RR]. The web UI's tyre callouts read this
        // block directly; if any required source is missing the corner falls
        // back to {available:false} so the UI shows a grey "no signal" state.
        JSONObject tyres = new JSONObject();
        boolean anyTyreData = data.tyrePressure != null
                || data.tyrePressureState != null
                || data.tyreAirLeakState != null
                || data.tyreSignalState != null
                || data.tyreTemperature != null;
        if (anyTyreData) {
            String[] keys = { "fl", "fr", "rl", "rr" };
            for (int i = 0; i < keys.length; i++) {
                JSONObject t = new JSONObject();
                int kPa = (data.tyrePressure != null && i < data.tyrePressure.length)
                        ? data.tyrePressure[i] : BydVehicleData.UNAVAILABLE;
                if (kPa != BydVehicleData.UNAVAILABLE && kPa > 0) {
                    t.put("kPa", kPa);
                    // PSI = kPa * 0.1450377 (matches the AutoCommander
                    // UnitFormatter conversion). One decimal place is
                    // enough to distinguish ±3 kPa steps the BYD TPMS
                    // actually reports — integer rounding collapses
                    // 247/250/253 kPa all to 36 psi, hiding real change.
                    double psi = kPa * 0.1450377;
                    t.put("psi", Math.round(psi * 10.0) / 10.0);
                }
                if (data.tyreTemperature != null && i < data.tyreTemperature.length
                        && data.tyreTemperature[i] != BydVehicleData.UNAVAILABLE) {
                    t.put("temperatureC", data.tyreTemperature[i]);
                }
                if (data.tyrePressureState != null && i < data.tyrePressureState.length) {
                    t.put("pressureState", data.tyrePressureState[i]);
                }
                if (data.tyreAirLeakState != null && i < data.tyreAirLeakState.length) {
                    t.put("airLeakState", data.tyreAirLeakState[i]);
                }
                if (data.tyreSignalState != null && i < data.tyreSignalState.length) {
                    t.put("signalState", data.tyreSignalState[i]);
                }
                // Available = we got at least one valid pressure reading.
                t.put("available", t.has("kPa"));
                tyres.put(keys[i], t);
            }
            tyres.put("available", true);
        } else {
            tyres.put("available", false);
        }
        response.put("tyres", tyres);

        // Engine telemetry block was removed: the BYD Auto SDK's
        // engineCoolantLevel / oilLevel / waterTempC / gearMode feeds
        // were producing unreliable values on the test PHEV
        // (cold-engine sentinels, conflicting Engine vs Setting device
        // readings, raw 28/254 oil dipstick that AutoCommander itself
        // refuses to display). Don't reintroduce without verifying each
        // field against the cluster's own readout first.

        response.put("timestamp", data.timestamp);
        HttpResponse.sendJson(out, response.toString());
    }

    /**
     * Returns BYD Cloud connection status.
     */
    private static void handleCloudStatus(OutputStream out) throws Exception {
        JSONObject response = new JSONObject();
        BydCloudConfig config = BydCloudConfig.fromUnifiedConfig();
        response.put("success", true);
        response.put("configured", config.isConfigured());
        response.put("verified", config.isVerified());
        response.put("enabled", config.enabled);
        HttpResponse.sendJson(out, response.toString());
    }

    /**
     * Returns the cloud-derived lock state. Triggers a one-shot REST refresh
     * on the data-provider thread if MQTT data is stale or unavailable.
     * The refresh is rate-limited inside the provider to protect BYD's API.
     */
    private static void handleCloudLock(OutputStream out) throws Exception {
        com.overdrive.app.byd.cloud.BydCloudDataProvider provider =
                com.overdrive.app.byd.cloud.BydCloudDataProvider.getInstance();

        // Kick off the refresh in the background — don't block the HTTP
        // response on a BYD round-trip (REST + login can take seconds).
        // The provider applies its own staleness check + cooldown.
        new Thread(provider::refreshLockStateIfStale, "CloudLockRefresh").start();

        JSONObject response = new JSONObject();
        response.put("success", true);
        response.put("status", provider.getStatusJson());
        HttpResponse.sendJson(out, response.toString());
    }

    /**
     * Lock the car via the routing layer (cloud-first → SDK fallback).
     */
    private static void handleLock(OutputStream out) throws Exception {
        CommandResult r = VehicleCommandRouter.getInstance()
                .execute(new VehicleCommandRouter.LockCommand());
        logger.info("Lock: routed result=" + r.outcome + " path=" + r.path);
        HttpResponse.sendJson(out, routedResponse(r, "lock").toString());
    }

    /**
     * Unlock the car via the routing layer.
     */
    private static void handleUnlock(OutputStream out) throws Exception {
        CommandResult r = VehicleCommandRouter.getInstance()
                .execute(new VehicleCommandRouter.UnlockCommand());
        logger.info("Unlock: routed result=" + r.outcome + " path=" + r.path);
        HttpResponse.sendJson(out, routedResponse(r, "unlock").toString());
    }

    /**
     * Find car (horn + lights) — cloud-only on this BYD generation.
     */
    private static void handleFindCar(OutputStream out) throws Exception {
        CommandResult r = VehicleCommandRouter.getInstance()
                .execute(new VehicleCommandRouter.FindCarCommand());
        logger.info("FindCar: routed result=" + r.outcome + " path=" + r.path);
        HttpResponse.sendJson(out, routedResponse(r, "find-car").toString());
    }

    /**
     * Battery preconditioning heat — cloud-only.
     * Body: { "enabled": bool }
     */
    private static void handleBatteryHeat(OutputStream out, String body) throws Exception {
        boolean enabled = false;
        if (body != null && !body.isEmpty()) {
            try { enabled = new JSONObject(body).optBoolean("enabled", false); } catch (Exception ignored) {}
        }
        CommandResult r = VehicleCommandRouter.getInstance()
                .execute(new VehicleCommandRouter.BatteryHeatCommand(enabled));
        logger.info("BatteryHeat: routed result=" + r.outcome + " enabled=" + enabled);
        JSONObject resp = routedResponse(r, "battery-heat");
        try { resp.put("enabled", enabled); } catch (Exception ignored) {}
        HttpResponse.sendJson(out, resp.toString());
    }

    /**
     * Trunk control routed via the command router.
     * Open: cloud unlock → SDK tailgate (router enforces the safety: motor only fires on unlock SUCCESS).
     * Close / stop: SDK direct.
     * Body: { "action": "open" | "close" | "stop" }
     */
    private static void handleTrunk(OutputStream out, String body) throws Exception {
        String action = "open";
        if (body != null && !body.isEmpty()) {
            try { action = new JSONObject(body).optString("action", "open"); }
            catch (Exception ignored) {}
        }
        VehicleCommand cmd;
        if ("close".equals(action)) cmd = new VehicleCommandRouter.TrunkCloseCommand();
        else if ("stop".equals(action)) cmd = new VehicleCommandRouter.TrunkStopCommand();
        else cmd = new VehicleCommandRouter.TrunkOpenCommand();

        CommandResult r = VehicleCommandRouter.getInstance().execute(cmd);
        logger.info("Trunk: action=" + action + " routed result=" + r.outcome + " path=" + r.path);
        JSONObject resp = routedResponse(r, action);
        HttpResponse.sendJson(out, resp.toString());
    }

    /**
     * Window control routed through the command router.
     * Body: one of:
     *   { "area": 1-4 (LF/RF/LR/RR) or 0 for all, "command": 1=open, 2=close, 3=stop }
     *   { "area": 1-4,                              "targetPercent": 0..100 }
     *   { "area": 5-6, (Sunroof and Sunshade),      "targetPercent": 0..100 }
     *
     * area=0 + command=2 routes through CloseAllWindowsCommand (CLOUD_FIRST,
     * with cloud CLOSEWINDOW). All other paths are SDK_ONLY.
     */
    private static void handleWindow(OutputStream out, String body) throws Exception {
        JSONObject response = new JSONObject();
        try {
            JSONObject req = new JSONObject(body);
            int area = req.optInt("area", 0);

            // targetPercent → SDK closed-loop positioning
            if (req.has("targetPercent")) {
                if (area < 1 || area > 6) {
                    response.put("success", false);
                    response.put("error", Messages.get("errors.vehicle_window_target_requires_area"));
                    HttpResponse.sendJson(out, response.toString());
                    return;
                }
                int target = req.getInt("targetPercent");
                CommandResult r = VehicleCommandRouter.getInstance()
                        .execute(new VehicleCommandRouter.WindowMoveCommand(area, 0, target));
                logger.info("Window: area=" + areaName(area) + " target=" + target + "% " + r.outcome);
                JSONObject resp = routedResponse(r, "window-target");
                resp.put("area", area);
                resp.put("targetPercent", target);
                HttpResponse.sendJson(out, resp.toString());
                return;
            }

            int command = req.optInt("command", 2); // default close
            VehicleCommand cmd;
            // "Close all" gets the cloud CLOSEWINDOW path (works while car is asleep).
            if (area == 0 && command == 2) {
                cmd = new VehicleCommandRouter.CloseAllWindowsCommand();
            } else {
                cmd = new VehicleCommandRouter.WindowMoveCommand(area, command, null);
            }
            CommandResult r = VehicleCommandRouter.getInstance().execute(cmd);
            logger.info("Window: area=" + areaName(area) + " cmd=" + windowCmdName(command) + " " + r.outcome);
            JSONObject resp = routedResponse(r, "window");
            resp.put("area", area);
            resp.put("command", command);
            HttpResponse.sendJson(out, resp.toString());
        } catch (Exception e) {
            logger.warn("Window command failed: " + e.getMessage());
            response.put("success", false);
            response.put("error", e.getMessage());
            HttpResponse.sendJson(out, response.toString());
        }
    }

    /**
     * Flash lights routed via the router.
     */
    private static void handleFlash(OutputStream out) throws Exception {
        CommandResult r = VehicleCommandRouter.getInstance()
                .execute(new VehicleCommandRouter.FlashLightsCommand());
        logger.info("Flash: routed result=" + r.outcome + " path=" + r.path);
        HttpResponse.sendJson(out, routedResponse(r, "flash").toString());
    }

    /**
     * Climate control routed through the command router.
     * power_on / power_off → CLOUD_FIRST (OPENAIR / CLOSEAIR with SDK fallback).
     * set_temp / set_fan   → SDK_ONLY (no granular cloud command exposed).
     * Body: { "action": "power_on"|"power_off"|"set_temp"|"set_fan",
     *         "zone": 1|2, "temp": 17-33, "fan": 1-7 }
     */
    private static void handleClimate(OutputStream out, String body) throws Exception {
        JSONObject response = new JSONObject();
        try {
            JSONObject req = new JSONObject(body);
            String action = req.optString("action", "");
            VehicleCommand cmd;
            switch (action) {
                case "power_on": {
                    double t = req.optDouble("temp", 22);
                    cmd = new VehicleCommandRouter.ClimateOnCommand(t);
                    break;
                }
                case "power_off":
                    cmd = new VehicleCommandRouter.ClimateOffCommand();
                    break;
                case "set_temp": {
                    int zone = req.optInt("zone", 1);
                    double t = req.optDouble("temp", 22);
                    cmd = new VehicleCommandRouter.ClimateSetTempCommand(zone, t);
                    break;
                }
                case "set_fan": {
                    int fan = req.optInt("fan", 3);
                    cmd = new VehicleCommandRouter.ClimateSetFanCommand(fan);
                    break;
                }
                default:
                    logger.warn("Climate: unknown action '" + action + "'");
                    response.put("success", false);
                    response.put("error", Messages.get("errors.vehicle_unknown_action_with_action", action));
                    HttpResponse.sendJson(out, response.toString());
                    return;
            }
            CommandResult r = VehicleCommandRouter.getInstance().execute(cmd);
            logger.info("Climate: action=" + action + " " + r.outcome + " path=" + r.path);
            JSONObject resp = routedResponse(r, action);
            HttpResponse.sendJson(out, resp.toString());
        } catch (Exception e) {
            logger.warn("Climate command failed: " + e.getMessage());
            response.put("success", false);
            response.put("error", e.getMessage());
            HttpResponse.sendJson(out, response.toString());
        }
    }

    /**
     * Seat heating / ventilation / memory-recall — cloud-first (VENTILATIONHEATING)
     * with SDK fallback. The cloud command is stateful, so heat+vent commands need
     * the FULL state of driver+passenger seats. The JS keeps that state and sends
     * it on every seat command.
     *
     * Body: { "action": "heating"|"ventilation"|"position",
     *         "position": 1-4, "level": 0-3,
     *         "driverHeat": 0-2, "driverVent": 0-2,
     *         "passengerHeat": 0-2, "passengerVent": 0-2 }
     */
    private static void handleSeat(OutputStream out, String body) throws Exception {
        JSONObject response = new JSONObject();
        try {
            JSONObject req = new JSONObject(body);
            String action = req.optString("action", "heating");
            int position = req.optInt("position", 1);
            int level = req.optInt("level", 0);
            int dh = req.optInt("driverHeat", 0);
            int dv = req.optInt("driverVent", 0);
            int ph = req.optInt("passengerHeat", 0);
            int pv = req.optInt("passengerVent", 0);
            VehicleCommand cmd;
            if ("ventilation".equals(action)) {
                cmd = new VehicleCommandRouter.SeatVentCommand(position, level, dh, dv, ph, pv);
            } else if ("position".equals(action)) {
                cmd = new VehicleCommandRouter.SeatMemoryCommand(position);
            } else {
                cmd = new VehicleCommandRouter.SeatHeatCommand(position, level, dh, dv, ph, pv);
            }
            CommandResult r = VehicleCommandRouter.getInstance().execute(cmd);
            logger.info("Seat: action=" + action + " pos=" + seatPosName(position)
                    + " level=" + level + " " + r.outcome);
            JSONObject resp = routedResponse(r, action);
            resp.put("position", position);
            resp.put("level", level);
            HttpResponse.sendJson(out, resp.toString());
        } catch (Exception e) {
            logger.warn("Seat command failed: " + e.getMessage());
            response.put("success", false);
            response.put("error", e.getMessage());
            HttpResponse.sendJson(out, response.toString());
        }
    }

    /**
     * Light controls — SDK_ONLY routed.
     * Body: { "target": "dayTimeLight", "enable": true|false }
     */
    private static void handleLights(OutputStream out, String body) throws Exception {
        JSONObject response = new JSONObject();
        try {
            JSONObject req = new JSONObject(body);
            String target = req.optString("target", null);
            boolean enable = req.optBoolean("enable", true);
            if (!"dayTimeLight".equals(target)) {
                response.put("success", false);
                response.put("error", Messages.get("errors.vehicle_unsupported_target_with_target", target));
                HttpResponse.sendJson(out, response.toString());
                return;
            }
            CommandResult r = VehicleCommandRouter.getInstance()
                    .execute(new VehicleCommandRouter.LightsCommand(enable));
            logger.info("Lights: target=dayTimeLight enable=" + enable + " " + r.outcome);
            JSONObject resp = routedResponse(r, "lights");
            resp.put("target", target);
            resp.put("enable", enable);
            HttpResponse.sendJson(out, resp.toString());
        } catch (Exception e) {
            logger.warn("Light command failed: " + e.getMessage());
            response.put("success", false);
            response.put("error", e.getMessage());
            HttpResponse.sendJson(out, response.toString());
        }
    }

    /**
     * ADAS controls — SDK_ONLY routed.
     * Body: { "target": "speedLimitWarning", "enable": true|false }
     */
    private static void handleAdas(OutputStream out, String body) throws Exception {
        JSONObject response = new JSONObject();
        try {
            JSONObject req = new JSONObject(body);
            String target = req.optString("target", null);
            boolean enable = req.optBoolean("enable", true);
            if (!"speedLimitWarning".equals(target)) {
                response.put("success", false);
                response.put("error", Messages.get("errors.vehicle_unsupported_target_with_target", target));
                HttpResponse.sendJson(out, response.toString());
                return;
            }
            CommandResult r = VehicleCommandRouter.getInstance()
                    .execute(new VehicleCommandRouter.AdasSpeedLimitWarningCommand(enable));
            logger.info("Adas: target=speedLimitWarning enable=" + enable + " " + r.outcome);
            JSONObject resp = routedResponse(r, "adas");
            resp.put("target", target);
            resp.put("enable", enable);
            HttpResponse.sendJson(out, resp.toString());
        } catch (Exception e) {
            logger.warn("Adas command failed: " + e.getMessage());
            response.put("success", false);
            response.put("error", e.getMessage());
            HttpResponse.sendJson(out, response.toString());
        }
    }

    /**
     * Charging schedule — CLOUD_ONLY. Wraps BYD's saveOrUpdate (window + repeat)
     * and changeChargeStatue (master switch). Payload mirrors pyBYD:
     * <pre>
     *   { startChargeTime: "HH:MM",
     *     endChargeTime:   "HH:MM" | "full",
     *     chargeWay:       "s" | "e" | "0,1,2,3,4",
     *     enabled:         boolean }
     * </pre>
     * If only {@code enabled} is provided, the master toggle runs alone.
     */
    private static void handleChargingSchedule(OutputStream out, String body) throws Exception {
        JSONObject response = new JSONObject();
        try {
            JSONObject req = (body == null || body.isEmpty()) ? new JSONObject() : new JSONObject(body);
            boolean hasStart = req.has("startChargeTime");
            boolean hasEnd = req.has("endChargeTime");
            boolean hasWay = req.has("chargeWay");
            boolean hasEnabled = req.has("enabled");
            boolean scheduleFields = hasStart || hasEnd || hasWay;
            if (!scheduleFields && !hasEnabled) {
                response.put("success", false);
                response.put("error", Messages.get("errors.vehicle_unknown_action_with_action", "charging-schedule"));
                HttpResponse.sendJson(out, response.toString());
                return;
            }
            if (scheduleFields && !(hasStart && hasEnd && hasWay)) {
                response.put("success", false);
                response.put("error", "startChargeTime, endChargeTime, and chargeWay must be provided together");
                HttpResponse.sendJson(out, response.toString());
                return;
            }

            // Toggle-only request — just hit changeChargeStatue.
            if (!scheduleFields) {
                boolean enabled = req.getBoolean("enabled");
                CommandResult r = VehicleCommandRouter.getInstance()
                        .execute(new VehicleCommandRouter.SmartChargingToggleCommand(enabled));
                logger.info("ChargingSchedule: toggle enabled=" + enabled + " " + r.outcome);
                JSONObject resp = routedResponse(r, "smart-charging-toggle");
                resp.put("enabled", enabled);
                HttpResponse.sendJson(out, resp.toString());
                return;
            }

            // Full save — saveOrUpdate carries its own status, no pre-toggle needed.
            String start = req.getString("startChargeTime");
            String end = req.getString("endChargeTime");
            String way = req.getString("chargeWay");
            boolean enabled = hasEnabled ? req.getBoolean("enabled") : true;
            CommandResult r = VehicleCommandRouter.getInstance()
                    .execute(new VehicleCommandRouter.ChargeScheduleCommand(start, end, way, enabled));
            logger.info("ChargingSchedule: save start=" + start + " end=" + end
                    + " way=" + way + " enabled=" + enabled + " " + r.outcome);
            JSONObject resp = routedResponse(r, "charge-schedule");
            resp.put("startChargeTime", start);
            resp.put("endChargeTime", end);
            resp.put("chargeWay", way);
            resp.put("enabled", enabled);
            HttpResponse.sendJson(out, resp.toString());
        } catch (Exception e) {
            logger.warn("ChargingSchedule command failed: " + e.getMessage());
            response.put("success", false);
            response.put("error", e.getMessage());
            HttpResponse.sendJson(out, response.toString());
        }
    }

    /**
     * Charging-schedule state. BYD's smartCharge/homePage endpoint returns
     * telemetry only (no echo of the configured schedule), so our source of
     * truth is {@link SmartChargeCache}, a local mirror updated on every
     * successful saveOrUpdate / changeChargeStatue.
     */
    private static void handleGetChargingSchedule(OutputStream out) throws Exception {
        JSONObject resp = new JSONObject();
        try {
            BydCloudConfig cfg = BydCloudConfig.fromUnifiedConfig();
            if (!cfg.isConfigured() || cfg.vin == null || cfg.vin.isEmpty()) {
                resp.put("success", true);
                resp.put("supported", false);
                resp.put("reason", "cloud_not_configured");
                HttpResponse.sendJson(out, resp.toString());
                return;
            }
            com.overdrive.app.byd.cloud.BydCloudClient client =
                    com.overdrive.app.byd.cloud.BydCloudDataProvider.getInstance().getSharedClient();
            if (client == null) {
                resp.put("success", true);
                resp.put("supported", false);
                resp.put("reason", "cloud_client_unavailable");
                HttpResponse.sendJson(out, resp.toString());
                return;
            }
            Boolean enabled = com.overdrive.app.byd.cloud.SmartChargeCache.getEnabled();
            String start = com.overdrive.app.byd.cloud.SmartChargeCache.getStartChargeTime();
            String end = com.overdrive.app.byd.cloud.SmartChargeCache.getEndChargeTime();
            String way = com.overdrive.app.byd.cloud.SmartChargeCache.getChargeWay();
            resp.put("success", true);
            resp.put("supported", true);
            if (enabled == null) resp.put("enabled", JSONObject.NULL);
            else resp.put("enabled", enabled.booleanValue());
            resp.put("startChargeTime", start == null ? JSONObject.NULL : start);
            resp.put("endChargeTime", end == null ? JSONObject.NULL : end);
            resp.put("chargeWay", way == null ? JSONObject.NULL : way);
            logger.info("ChargingSchedule GET (local cache) → enabled=" + enabled
                    + " start=" + start + " end=" + end + " way=" + way);
        } catch (Exception e) {
            logger.warn("ChargingSchedule read failed: " + e.getMessage());
            resp.put("success", false);
            resp.put("error", e.getMessage());
        }
        HttpResponse.sendJson(out, resp.toString());
    }

    /**
     * BEV charge cap — SDK_ONLY via BYDAutoChargingDevice
     * setChargeStopCapacityState + setChargeStopSwitchState. The Seal HAL
     * historically reports getChargeStopSupportConfig=0; the collector probes
     * via write-then-read-back on the first successful POST and the GET
     * returns supported=false on no-op trims so the UI can hide the section.
     *
     * <p>Body: {@code { percent?: 50..100, enabled?: bool }}.
     * When both are present the toggle runs first so a freshly-enabled cap
     * picks up the new percent.
     */
    private static void handleChargeCap(OutputStream out, String body) throws Exception {
        JSONObject response = new JSONObject();
        try {
            JSONObject req = (body == null || body.isEmpty()) ? new JSONObject() : new JSONObject(body);
            boolean hasPercent = req.has("percent");
            boolean hasEnabled = req.has("enabled");
            if (!hasPercent && !hasEnabled) {
                response.put("success", false);
                response.put("error", Messages.get("errors.vehicle_unknown_action_with_action", "charge-cap"));
                HttpResponse.sendJson(out, response.toString());
                return;
            }

            CommandResult last = null;
            String action = null;

            if (hasEnabled) {
                boolean enabled = req.getBoolean("enabled");
                CommandResult r = VehicleCommandRouter.getInstance()
                        .execute(new VehicleCommandRouter.ChargeCapToggleCommand(enabled));
                logger.info("ChargeCap: toggle enabled=" + enabled + " " + r.outcome);
                last = r;
                action = "charge-cap-toggle";
                if (r.outcome != VehicleCommandRouter.Outcome.SUCCESS && hasPercent) {
                    JSONObject resp = routedResponse(r, action);
                    resp.put("enabled", enabled);
                    HttpResponse.sendJson(out, resp.toString());
                    return;
                }
            }

            if (hasPercent) {
                int percent = req.getInt("percent");
                if (percent < 50 || percent > 100) {
                    response.put("success", false);
                    response.put("error", "percent must be 50..100 (got " + percent + ")");
                    HttpResponse.sendJson(out, response.toString());
                    return;
                }
                CommandResult r = VehicleCommandRouter.getInstance()
                        .execute(new VehicleCommandRouter.ChargeCapPercentCommand(percent));
                logger.info("ChargeCap: percent=" + percent + " " + r.outcome);
                last = r;
                action = "charge-cap-percent";
            }

            JSONObject resp = routedResponse(last, action);
            if (hasPercent) resp.put("percent", req.getInt("percent"));
            if (hasEnabled) resp.put("enabled", req.getBoolean("enabled"));
            // Surface the probe result so the UI can hide on the next paint.
            Boolean supported = BydDataCollector.getInstance().isChargeCapSupported();
            if (supported != null) resp.put("supported", supported.booleanValue());
            HttpResponse.sendJson(out, resp.toString());
        } catch (Exception e) {
            logger.warn("ChargeCap command failed: " + e.getMessage());
            response.put("success", false);
            response.put("error", e.getMessage());
            HttpResponse.sendJson(out, response.toString());
        }
    }

    /**
     * BEV charge cap state — SDK reads. Returns last-known target percent and
     * on/off, plus a {@code supported} flag derived from the write-read-back
     * probe (null until the user has saved at least once).
     */
    private static void handleGetChargeCap(OutputStream out) throws Exception {
        JSONObject resp = new JSONObject();
        try {
            BydDataCollector collector = BydDataCollector.getInstance();
            int percent = collector.getChargeCapPercent();
            int enabled = collector.getChargeCapEnabled();
            Boolean supported = collector.isChargeCapSupported();
            resp.put("success", true);
            resp.put("percent", percent >= 0 ? percent : JSONObject.NULL);
            if (enabled == 0) resp.put("enabled", false);
            else if (enabled == 1) resp.put("enabled", true);
            else resp.put("enabled", JSONObject.NULL);
            // Tri-state: null = not yet probed (show optimistically),
            //           true/false = probe result from last write.
            if (supported == null) resp.put("supported", JSONObject.NULL);
            else resp.put("supported", supported.booleanValue());
            logger.info("ChargeCap GET → percent=" + percent + " enabled=" + enabled
                    + " supported=" + supported);
        } catch (Exception e) {
            logger.warn("ChargeCap read failed: " + e.getMessage());
            resp.put("success", false);
            resp.put("error", e.getMessage());
        }
        HttpResponse.sendJson(out, resp.toString());
    }

    // ==================== LOG HELPERS ====================

    private static String areaName(int area) {
        switch (area) {
            case 0: return "all";
            case 1: return "LF";
            case 2: return "RF";
            case 3: return "LR";
            case 4: return "RR";
            case 5: return "Sunroof";
            case 6: return "Sunshade";
            default: return "?(" + area + ")";
        }
    }

    private static String windowCmdName(int cmd) {
        switch (cmd) {
            case 1: return "open";
            case 2: return "close";
            case 3: return "stop";
            default: return "?(" + cmd + ")";
        }
    }

    private static String seatPosName(int pos) {
        switch (pos) {
            case 1: return "driver";
            case 2: return "passenger";
            case 3: return "rear-left";
            case 4: return "rear-right";
            default: return "?(" + pos + ")";
        }
    }

    // ==================== HELPERS ====================

    /**
     * Convert BYD cloud per-door lock value to API contract.
     *   pyBYD reports: 1=UNLOCKED, 2=LOCKED on each *DoorLock field.
     *   API contract publishes: 1=locked, 2=unlocked (inverted, historical).
     * VehicleCloudSnapshot.LOCK_UNAVAILABLE / LOCK_UNKNOWN both map to -1.
     */
    private static int cloudLockToApi(int cloud) {
        if (cloud == 2) return 1; // LOCKED
        if (cloud == 1) return 2; // UNLOCKED
        return -1;
    }

    /**
     * Build the response JSON shape the new vehicle-control UI expects:
     *   { success, path, latencyMs, message, action, outcome, commandSuccess }
     * — `success` is true on routed SUCCESS,
     * — `path` is "cloud" / "local" / "cloud-then-local" / "none",
     * — `message` is a localized user-facing string,
     * — `commandSuccess` mirrors `success` so legacy UI branches still work.
     */
    private static JSONObject routedResponse(CommandResult r, String action) {
        JSONObject resp = new JSONObject();
        try {
            boolean success = r.outcome == VehicleCommandRouter.Outcome.SUCCESS;
            resp.put("success", success);
            resp.put("commandSuccess", success);
            resp.put("path", r.pathString());
            resp.put("latencyMs", r.latencyMs);
            resp.put("message", r.displayMessage);
            resp.put("outcome", r.outcome.name().toLowerCase());
            resp.put("action", action);
            if (!success && r.error != null && r.error.getMessage() != null) {
                resp.put("error", r.error.getMessage());
            } else if (!success) {
                resp.put("error", r.displayMessage);
            }
        } catch (Exception ignored) {}
        return resp;
    }
}
