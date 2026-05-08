package com.overdrive.app.server;

import com.overdrive.app.byd.BydDataCollector;
import com.overdrive.app.byd.BydVehicleData;
import com.overdrive.app.byd.cloud.BydCloudClient;
import com.overdrive.app.byd.cloud.BydCloudConfig;
import com.overdrive.app.logging.DaemonLogger;

import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;

/**
 * API handler for the Vehicle Control page.
 * 
 * Endpoints:
 *   GET  /api/vehicle/state       — current door/window/trunk/lock state from BydVehicleData
 *   POST /api/vehicle/lock        — lock via BYD Cloud
 *   POST /api/vehicle/unlock      — unlock via BYD Cloud
 *   POST /api/vehicle/trunk       — open/close/stop trunk (local HAL)
 *   POST /api/vehicle/window      — window control (local HAL)
 *   POST /api/vehicle/flash       — flash lights via BYD Cloud
 *   GET  /api/vehicle/cloud-status — BYD Cloud connection status
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
            response.put("error", "Vehicle data not available");
            HttpResponse.sendJson(out, response.toString());
            return;
        }

        response.put("success", true);

        // Door lock status [1-7]: 1=locked, 2=unlocked, -1=unknown
        // Index: 0=LF, 1=RF, 2=LR, 3=RR, 4=trunk, 5=hood, 6=overall
        JSONObject doors = new JSONObject();
        if (data.doorLockStatus != null && data.doorLockStatus.length >= 7) {
            doors.put("lf", data.doorLockStatus[0]);
            doors.put("rf", data.doorLockStatus[1]);
            doors.put("lr", data.doorLockStatus[2]);
            doors.put("rr", data.doorLockStatus[3]);
            doors.put("trunk", data.doorLockStatus[4]);
            doors.put("hood", data.doorLockStatus[5]);
            doors.put("overall", data.doorLockStatus[6]);
        }
        response.put("doors", doors);

        // Window open percent [1-6]: 0=closed, 100=fully open, -1=unknown
        // Index: 0=LF, 1=RF, 2=LR, 3=RR, 4=sunroof, 5=rear
        JSONObject windows = new JSONObject();
        if (data.windowOpenPercent != null && data.windowOpenPercent.length >= 4) {
            windows.put("lf", data.windowOpenPercent[0]);
            windows.put("rf", data.windowOpenPercent[1]);
            windows.put("lr", data.windowOpenPercent[2]);
            windows.put("rr", data.windowOpenPercent[3]);
            if (data.windowOpenPercent.length >= 5) windows.put("sunroof", data.windowOpenPercent[4]);
            if (data.windowOpenPercent.length >= 6) windows.put("rear", data.windowOpenPercent[5]);
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
        response.put("lights", lights);

        // Climate
        JSONObject climate = new JSONObject();
        if (data.acStartState != BydVehicleData.UNAVAILABLE) climate.put("acOn", data.acStartState == 1);
        if (!Double.isNaN(data.insideTempC)) climate.put("insideTempC", data.insideTempC);
        if (data.acWindMode != BydVehicleData.UNAVAILABLE) climate.put("windMode", data.acWindMode);
        response.put("climate", climate);

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
     * Lock the car via BYD Cloud API.
     */
    private static void handleLock(OutputStream out) throws Exception {
        JSONObject response = new JSONObject();
        try {
            BydCloudClient client = getCloudClient();
            boolean success = client.lock(getVin());
            response.put("success", true);
            response.put("commandSuccess", success);
            response.put("action", "lock");
        } catch (Exception e) {
            logger.warn("Lock command failed: " + e.getMessage());
            response.put("success", false);
            response.put("error", e.getMessage());
        }
        HttpResponse.sendJson(out, response.toString());
    }

    /**
     * Unlock the car via BYD Cloud API.
     */
    private static void handleUnlock(OutputStream out) throws Exception {
        JSONObject response = new JSONObject();
        try {
            BydCloudClient client = getCloudClient();
            boolean success = client.unlock(getVin());
            response.put("success", true);
            response.put("commandSuccess", success);
            response.put("action", "unlock");
        } catch (Exception e) {
            logger.warn("Unlock command failed: " + e.getMessage());
            response.put("success", false);
            response.put("error", e.getMessage());
        }
        HttpResponse.sendJson(out, response.toString());
    }

    /**
     * Trunk control.
     * Open: Unlocks car first (via cloud) to avoid alarm, then opens trunk (local HAL).
     * Close: Locks car (via cloud) which also closes the trunk.
     * Body: { "action": "open" | "close" | "stop" }
     */
    private static void handleTrunk(OutputStream out, String body) throws Exception {
        JSONObject response = new JSONObject();
        try {
            String action = "open";
            if (body != null && !body.isEmpty()) {
                JSONObject req = new JSONObject(body);
                action = req.optString("action", "open");
            }

            BydDataCollector collector = BydDataCollector.getInstance();
            boolean success;
            switch (action) {
                case "close":
                    // Close trunk via local HAL first (direct motor command)
                    // Then lock the car via cloud as a secondary step
                    success = collector.closeTailgate();
                    logger.info("Trunk close: local HAL closeTailgate result=" + success);
                    
                    // Also lock the car via cloud (belt-and-suspenders approach)
                    // Some models close trunk on lock, others need the HAL command above
                    try {
                        BydCloudClient client = getCloudClient();
                        boolean locked = client.lock(getVin());
                        logger.info("Trunk close: cloud lock result=" + locked);
                        response.put("locked", locked);
                    } catch (Exception e) {
                        logger.debug("Trunk close: cloud lock skipped: " + e.getMessage());
                        // Not critical — local HAL close is the primary mechanism
                    }
                    break;
                case "stop":
                    success = collector.stopTailgate();
                    break;
                case "open":
                default:
                    // Unlock car first to avoid alarm, then open trunk
                    response.put("step", "unlocking");
                    try {
                        BydCloudClient client = getCloudClient();
                        boolean unlocked = client.unlock(getVin());
                        logger.info("Trunk open: unlocked car via cloud, result=" + unlocked);
                        response.put("unlocked", unlocked);
                        // Wait for unlock to take effect before opening trunk
                        Thread.sleep(2000);
                    } catch (Exception e) {
                        logger.warn("Trunk open: cloud unlock failed (proceeding anyway): " + e.getMessage());
                        response.put("unlockError", e.getMessage());
                    }
                    response.put("step", "opening");
                    success = collector.openTailgate();
                    break;
            }

            response.put("success", true);
            response.put("commandSuccess", success);
            response.put("action", action);
        } catch (Exception e) {
            logger.warn("Trunk command failed: " + e.getMessage());
            response.put("success", false);
            response.put("error", e.getMessage());
        }
        HttpResponse.sendJson(out, response.toString());
    }

    /**
     * Window control via local HAL (BydDataCollector).
     * Body: { "area": 1-4 (LF/RF/LR/RR) or 0 for all, "command": 1=open, 2=close, 3=stop }
     */
    private static void handleWindow(OutputStream out, String body) throws Exception {
        JSONObject response = new JSONObject();
        try {
            JSONObject req = new JSONObject(body);
            int area = req.optInt("area", 0);
            int command = req.optInt("command", 2); // default close

            BydDataCollector collector = BydDataCollector.getInstance();
            boolean success;
            if (area == 0) {
                success = collector.setAllWindowsCommand(command);
            } else {
                success = collector.setWindowCommand(area, command);
            }

            response.put("success", true);
            response.put("commandSuccess", success);
            response.put("area", area);
            response.put("command", command);
        } catch (Exception e) {
            logger.warn("Window command failed: " + e.getMessage());
            response.put("success", false);
            response.put("error", e.getMessage());
        }
        HttpResponse.sendJson(out, response.toString());
    }

    /**
     * Flash lights via BYD Cloud API.
     */
    private static void handleFlash(OutputStream out) throws Exception {
        JSONObject response = new JSONObject();
        try {
            BydCloudClient client = getCloudClient();
            boolean success = client.flashLightsNoWait(getVin());
            response.put("success", true);
            response.put("commandSuccess", success);
            response.put("action", "flash");
        } catch (Exception e) {
            logger.warn("Flash command failed: " + e.getMessage());
            response.put("success", false);
            response.put("error", e.getMessage());
        }
        HttpResponse.sendJson(out, response.toString());
    }

    /**
     * Climate control via local HAL.
     * Body: { "action": "power_on"|"power_off"|"set_temp"|"set_fan",
     *         "zone": 1|2, "temp": 17-33, "fan": 1-7 }
     */
    private static void handleClimate(OutputStream out, String body) throws Exception {
        JSONObject response = new JSONObject();
        try {
            JSONObject req = new JSONObject(body);
            String action = req.optString("action", "");
            BydDataCollector collector = BydDataCollector.getInstance();
            boolean success = false;

            switch (action) {
                case "power_on":
                    success = collector.setAcPower(true);
                    break;
                case "power_off":
                    success = collector.setAcPower(false);
                    break;
                case "set_temp":
                    int zone = req.optInt("zone", 1);
                    double temp = req.optDouble("temp", 22);
                    success = collector.setAcTemperature(zone, temp);
                    break;
                case "set_fan":
                    int fan = req.optInt("fan", 3);
                    success = collector.setAcFanLevel(fan);
                    break;
                default:
                    response.put("success", false);
                    response.put("error", "Unknown action: " + action);
                    HttpResponse.sendJson(out, response.toString());
                    return;
            }

            response.put("success", true);
            response.put("commandSuccess", success);
            response.put("action", action);
        } catch (Exception e) {
            logger.warn("Climate command failed: " + e.getMessage());
            response.put("success", false);
            response.put("error", e.getMessage());
        }
        HttpResponse.sendJson(out, response.toString());
    }

    /**
     * Seat heating/ventilation via local HAL.
     * Body: { "action": "heating"|"ventilation", "position": 1-4, "level": 0-3 }
     * Position: 1=driver, 2=passenger, 3=rear-left, 4=rear-right
     * Level: 0=off, 1=low, 2=medium, 3=high (clamped to 0-2 internally)
     */
    private static void handleSeat(OutputStream out, String body) throws Exception {
        JSONObject response = new JSONObject();
        try {
            JSONObject req = new JSONObject(body);
            String action = req.optString("action", "heating");
            int position = req.optInt("position", 1);
            int level = req.optInt("level", 0);
            BydDataCollector collector = BydDataCollector.getInstance();
            boolean success;

            if ("ventilation".equals(action)) {
                success = collector.setSeatVentilation(position, level);
            } else {
                success = collector.setSeatHeating(position, level);
            }

            response.put("success", true);
            response.put("commandSuccess", success);
            response.put("action", action);
            response.put("position", position);
            response.put("level", level);
        } catch (Exception e) {
            logger.warn("Seat command failed: " + e.getMessage());
            response.put("success", false);
            response.put("error", e.getMessage());
        }
        HttpResponse.sendJson(out, response.toString());
    }

    // ==================== HELPERS ====================

    private static String getVin() throws Exception {
        BydCloudConfig config = BydCloudConfig.fromUnifiedConfig();
        if (!config.isConfigured() || config.vin.isEmpty()) {
            throw new Exception("BYD Cloud not configured or VIN missing");
        }
        return config.vin;
    }

    private static BydCloudClient getCloudClient() throws Exception {
        BydCloudConfig config = BydCloudConfig.fromUnifiedConfig();
        if (!config.isConfigured()) {
            throw new Exception("BYD Cloud not configured. Set up credentials in Settings → BYD Cloud.");
        }

        BydCloudClient client = new BydCloudClient(config);

        // Load bangcle tables
        java.io.File tablesFile = new java.io.File("/data/local/tmp/bangcle_tables.bin");
        if (!tablesFile.exists()) {
            throw new Exception("Bangcle crypto tables not found. Reinstall the app.");
        }
        InputStream tablesStream = new java.io.FileInputStream(tablesFile);
        try {
            client.init(tablesStream);
        } finally {
            try { tablesStream.close(); } catch (Exception ignored) {}
        }

        client.login();
        client.verifyControlPassword(config.vin);
        return client;
    }
}
