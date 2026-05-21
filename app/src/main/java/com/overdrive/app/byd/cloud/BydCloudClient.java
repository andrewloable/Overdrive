package com.overdrive.app.byd.cloud;

import com.overdrive.app.byd.cloud.crypto.BangcleCodec;
import com.overdrive.app.byd.cloud.crypto.BydCryptoUtils;
import com.overdrive.app.logging.DaemonLogger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;

/**
 * High-level BYD cloud API client.
 * 
 * Handles login, vehicle list, control PIN verification, and remote commands.
 * 
 * Port of: pyBYD/src/pybyd/client.py (BydClient)
 * Also matches: Niek/BYD-re/client.js (login, remote control flow)
 */
public final class BydCloudClient {

    private static final String TAG = "BydCloudClient";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    private final BydCloudConfig config;
    private final BangcleCodec codec;
    private BydCloudTransport transport;
    private BydCloudSession session;
    private boolean commandsVerified = false;

    public BydCloudClient(BydCloudConfig config) {
        this.config = config;
        this.codec = new BangcleCodec();
    }

    /**
     * Initialize the codec by loading Bangcle tables.
     * Must be called before any API operations.
     */
    public void init(InputStream tablesStream) throws IOException {
        codec.loadTables(tablesStream);
        transport = new BydCloudTransport(config, codec);
    }

    /**
     * Check if the client is initialized and ready.
     */
    public boolean isReady() {
        return codec.isReady() && transport != null;
    }

    // ── Authentication ──────────────────────────────────────────────────

    /**
     * Login to BYD cloud API and obtain session tokens.
     * Synchronized so the connect path and the periodic refresh path can't
     * race — concurrent logins invalidate each other's tokens, which surfaces
     * as a "broker lookup 1005 → force re-login" loop in MQTT subscriber logs.
     */
    public synchronized void login() throws IOException {
        if (!isReady()) throw new IllegalStateException("Client not initialized");

        long nowMs = System.currentTimeMillis();
        JSONObject outer = buildLoginRequest(nowMs);
        JSONObject response = transport.postSecure("/app/account/login", outer);

        String code = response.optString("code", "");
        if (!"0".equals(code)) {
            String msg = response.optString("message", "Unknown error");
            // 1009 = server temporarily unavailable (rate limit or overload)
            // Don't throw a hard error — the session will be retried on next command
            if ("1009".equals(code)) {
                logger.warn("Login got server error 1009 — BYD cloud temporarily unavailable");
                throw new IOException("BYD cloud temporarily unavailable (1009)");
            }
            throw new IOException("Login failed: code=" + code + " message=" + msg);
        }

        String respondData = response.optString("respondData", "");
        JSONObject loginInner = BydCloudTransport.decryptRespondData(respondData, config.loginKey);
        JSONObject token = loginInner.optJSONObject("token");
        if (token == null) {
            throw new IOException("Login response missing token");
        }

        String userId = token.optString("userId", "");
        String signToken = token.optString("signToken", "");
        String encryToken = token.optString("encryToken", "");
        if (encryToken.isEmpty()) {
            encryToken = token.optString("encryptToken", "");
        }

        if (userId.isEmpty() || signToken.isEmpty() || encryToken.isEmpty()) {
            throw new IOException("Login response missing token fields");
        }

        session = new BydCloudSession(userId, signToken, encryToken);
        logger.info("Login succeeded: userId=***" + userId.substring(Math.max(0, userId.length() - 4)));
    }

    /**
     * Ensure we have a valid session, re-authenticating if needed.
     * Retries login once with a short backoff on transient server errors (1009).
     * Synchronized so the check-then-login is atomic — without this two
     * concurrent callers can both decide the session is expired and both
     * issue logins, invalidating the first caller's token.
     */
    public synchronized BydCloudSession ensureSession() throws IOException {
        if (session == null || session.isExpired()) {
            try {
                login();
            } catch (IOException e) {
                // On transient server error, retry once after a brief pause
                if (e.getMessage() != null && e.getMessage().contains("1009")) {
                    try { Thread.sleep(3000); } catch (InterruptedException ignored) {}
                    login();  // Second attempt — if this fails, propagate the exception
                } else {
                    throw e;
                }
            }
        }
        return session;
    }

    // ── Vehicle List ────────────────────────────────────────────────────

    /**
     * Fetch all vehicles and return the first VIN.
     */
    public String fetchFirstVin() throws IOException {
        String[] result = fetchFirstVinAndEnergyType();
        return result[0];
    }

    /**
     * Fetch all vehicles and return [VIN, energyType].
     */
    public String[] fetchFirstVinAndEnergyType() throws IOException {
        BydCloudSession s = ensureSession();
        long nowMs = System.currentTimeMillis();

        JSONObject inner = buildInner(nowMs);
        TokenEnvelope env = buildTokenOuterEnvelope(nowMs, s, inner);
        JSONObject response = transport.postSecure("/app/account/getAllListByUserId", env.outer);

        String code = response.optString("code", "");
        if (!"0".equals(code)) {
            throw new IOException("Vehicle list failed: code=" + code);
        }

        String respondDataHex = response.optString("respondData", "");
        if (respondDataHex.isEmpty()) {
            throw new IOException("Vehicle list: empty respondData");
        }

        String plain = BydCryptoUtils.aesDecryptUtf8(respondDataHex, env.contentKey);
        plain = plain.trim();

        JSONArray list = null;
        try {
            list = new JSONArray(plain);
        } catch (Exception e) {
            try {
                JSONObject obj = new JSONObject(plain);
                list = obj.optJSONArray("diLinkAutoInfoList");
            } catch (Exception e2) {
                throw new IOException("Could not parse vehicle list response");
            }
        }

        if (list == null || list.length() == 0) {
            throw new IOException("No vehicles found on account");
        }

        for (int i = 0; i < list.length(); i++) {
            JSONObject vehicle = list.optJSONObject(i);
            if (vehicle != null) {
                String vin = vehicle.optString("vin", "");
                if (!vin.isEmpty()) {
                    String energyType = vehicle.optString("energyType", "");
                    logger.info("Found vehicle: VIN=***" + vin.substring(Math.max(0, vin.length() - 4))
                            + " energyType=" + energyType);
                    return new String[]{vin, energyType};
                }
            }
        }

        throw new IOException("No vehicle with VIN found");
    }

    // ── Control PIN Verification ────────────────────────────────────────

    /**
     * Verify the control PIN. Must be called once before remote commands.
     */
    /** True once {@link #verifyControlPassword(String)} has succeeded this session. */
    public boolean isControlPasswordVerified() {
        return commandsVerified;
    }

    public void verifyControlPassword(String vin) throws IOException {
        // Idempotent — once we've verified the PIN this session, every subsequent
        // /control/remoteControl call piggybacks on the same verification flag.
        // Re-posting wastes a round-trip and adds 2-4s of latency to every command.
        if (commandsVerified) return;
        BydCloudSession s = ensureSession();
        long nowMs = System.currentTimeMillis();

        JSONObject inner = new JSONObject();
        try {
            inner.put("commandPwd", config.commandPwd);
            inner.put("deviceType", "0");
            inner.put("functionType", "remoteControl");
            inner.put("imeiMD5", config.imeiMd5);
            inner.put("networkType", "wifi");
            inner.put("random", BydCryptoUtils.randomHex16());
            inner.put("timeStamp", String.valueOf(nowMs));
            inner.put("version", config.appInnerVersion);
            inner.put("vin", vin);
        } catch (Exception e) {
            throw new IOException("Failed to build verify request", e);
        }

        TokenEnvelope env = buildTokenOuterEnvelope(nowMs, s, inner);
        JSONObject response = transport.postSecure(
                "/vehicle/vehicleswitch/verifyControlPassword", env.outer);

        String code = response.optString("code", "");
        if (!"0".equals(code)) {
            String msg = response.optString("message", "");
            throw new IOException("Control PIN verification failed: code=" + code + " " + msg);
        }

        commandsVerified = true;
        logger.info("Control PIN verified for VIN=***" + vin.substring(Math.max(0, vin.length() - 4)));
    }

    // ── Remote Commands ─────────────────────────────────────────────────

    /**
     * Flash the vehicle's lights.
     */
    public boolean flashLights(String vin) throws IOException {
        return executeRemoteCommand(vin, "FLASHLIGHTNOWHISTLE", true);
    }

    /**
     * Flash lights without waiting for result polling.
     */
    public boolean flashLightsNoWait(String vin) throws IOException {
        return executeRemoteCommand(vin, "FLASHLIGHTNOWHISTLE", false);
    }

    /**
     * Find car (horn + lights).
     */
    public boolean findCar(String vin) throws IOException {
        return executeRemoteCommand(vin, "FINDCAR", true);
    }

    /**
     * Find car without waiting for result polling.
     */
    public boolean findCarNoWait(String vin) throws IOException {
        return executeRemoteCommand(vin, "FINDCAR", false);
    }

    /**
     * Lock the vehicle.
     */
    public boolean lock(String vin) throws IOException {
        return executeRemoteCommand(vin, "LOCKDOOR", true);
    }

    /**
     * Unlock the vehicle.
     */
    public boolean unlock(String vin) throws IOException {
        return executeRemoteCommand(vin, "OPENDOOR", true);
    }

    /**
     * Start remote AC with target temp.
     * BYD OPENAIR: temperature applies to driver+copilot, cycle_mode=2 (auto),
     * remote_mode=4 (cool/heat auto), default time_span=3 (20 min).
     */
    public boolean startClimate(String vin, double tempCelsius) throws IOException {
        int t = (int) Math.round(Math.max(17, Math.min(33, tempCelsius)));
        JSONObject extra = new JSONObject();
        try {
            extra.put("temperature", String.valueOf(t));
            extra.put("copilot_temperature", String.valueOf(t));
            extra.put("cycle_mode", "2");
            extra.put("time_span", "3");
            extra.put("remote_mode", "4");
        } catch (Exception e) {
            throw new IOException("Failed to build OPENAIR params", e);
        }
        return executeRemoteCommand(vin, "OPENAIR", extra, true).success;
    }

    /**
     * Stop remote AC.
     */
    public boolean stopClimate(String vin) throws IOException {
        return executeRemoteCommand(vin, "CLOSEAIR", null, true).success;
    }

    /**
     * Close all four windows.
     */
    public boolean closeAllWindows(String vin) throws IOException {
        return executeRemoteCommand(vin, "CLOSEWINDOW", null, true).success;
    }

    /**
     * Toggle traction battery preconditioning heat.
     * BATTERYHEAT: batteryHeatSwitch=1 enables, 0 disables.
     */
    public boolean setBatteryHeat(String vin, boolean on) throws IOException {
        JSONObject extra = new JSONObject();
        try {
            extra.put("batteryHeatSwitch", on ? "1" : "0");
        } catch (Exception e) {
            throw new IOException("Failed to build BATTERYHEAT params", e);
        }
        return executeRemoteCommand(vin, "BATTERYHEAT", extra, true).success;
    }

    /**
     * Set seat heating/ventilation via cloud (commandType VENTILATIONHEATING).
     *
     * <p>BYD's cloud command requires sending the FULL seat-climate snapshot in
     * one POST — there's no "just change one seat" variant. We pass the current
     * state of all known seats (driver+passenger heat+vent), zero out unknown
     * surfaces (rear seats, steering wheel), and let the BMS apply the diff.
     *
     * <p>Level scale is INVERTED on the wire vs. our 0..2 UI convention:
     * <ul>
     *   <li>UI 0 (off) → wire 3</li>
     *   <li>UI 1 (low) → wire 2</li>
     *   <li>UI 2 (high) → wire 1</li>
     *   <li>0 = "not applicable / feature absent" — used for seats we don't track</li>
     * </ul>
     *
     * @param chairType "1"=driver changed, "2"=copilot, "5"=steering wheel
     */
    public boolean setSeatClimate(String vin, String chairType,
                                   int driverHeatUi, int driverVentUi,
                                   int passengerHeatUi, int passengerVentUi) throws IOException {
        JSONObject extra = new JSONObject();
        try {
            extra.put("chairType", chairType);
            extra.put("remoteMode", "1");
            extra.put("mainHeat", String.valueOf(uiToWireSeatLevel(driverHeatUi)));
            extra.put("mainVentilation", String.valueOf(uiToWireSeatLevel(driverVentUi)));
            extra.put("copilotHeat", String.valueOf(uiToWireSeatLevel(passengerHeatUi)));
            extra.put("copilotVentilation", String.valueOf(uiToWireSeatLevel(passengerVentUi)));
            // Rear + steering: 0 = not applicable (we don't track these locally)
            extra.put("lrSeatHeatState", "0");
            extra.put("lrSeatVentilationState", "0");
            extra.put("rrSeatHeatState", "0");
            extra.put("rrSeatVentilationState", "0");
            extra.put("steeringWheelHeatState", "3");
        } catch (Exception e) {
            throw new IOException("Failed to build VENTILATIONHEATING params", e);
        }
        logger.info("VENTILATIONHEATING request extra=" + extra.toString());
        return executeRemoteCommand(vin, "VENTILATIONHEATING", extra, true).success;
    }

    /** Translate UI level (0=off, 1=low, 2=high) to BYD wire level (3=off, 2=low, 1=high). */
    private static int uiToWireSeatLevel(int uiLevel) {
        switch (uiLevel) {
            case 1: return 2;  // low
            case 2: return 1;  // high
            case 0:
            default: return 3; // off
        }
    }

    // ── Smart Charging ──────────────────────────────────────────────────
    // Smart-charge endpoints are config writes, not /control/remoteControl
    // commands. They use the same token-envelope path as data fetches and do
    // NOT require commandPwd or polling. Port of pyBYD _api/smart_charging.

    /**
     * BYD response code 1001 has overloaded semantics — pyBYD documents this
     * in `_api/control.py:171-172`:
     *   - For data-fetch endpoints: "endpoint not supported on this region/account"
     *   - For write/command endpoints: "generic server-side rejection of the request"
     * We only treat 1001 as "unsupported" on read paths (homePage). On writes
     * (changeChargeStatue, saveOrUpdate, /control/remoteControl) it just means
     * the request was rejected — could be bad payload, missing pre-condition,
     * server-side state issue, etc. — and must surface as a normal failure.
     */
    private static final String CLOUD_CODE_ENDPOINT_NOT_SUPPORTED = "1001";

    /** Thrown only when a READ endpoint reports 1001 (genuine "not supported on this region/account"). */
    public static final class SmartChargeNotSupportedException extends IOException {
        public SmartChargeNotSupportedException(String msg) { super(msg); }
    }

    /**
     * Toggle smart charging on/off via cloud.
     * Endpoint: /control/smartCharge/changeChargeStatue
     * Field: smartChargeSwitch="1"|"0"
     */
    public boolean toggleSmartCharging(String vin, boolean enable) throws IOException {
        BydCloudSession s = ensureSession();
        long nowMs = System.currentTimeMillis();
        JSONObject inner = buildInner(nowMs);
        try {
            inner.put("vin", vin);
            inner.put("smartChargeSwitch", enable ? "1" : "0");
        } catch (Exception e) {
            throw new IOException("Failed to build smartCharge toggle request", e);
        }
        logger.info("smartCharge toggle request inner=" + redactVin(inner));
        TokenEnvelope env = buildTokenOuterEnvelope(nowMs, s, inner);
        JSONObject response = transport.postSecure("/control/smartCharge/changeChargeStatue", env.outer);
        String code = response.optString("code", "");
        if (!"0".equals(code)) {
            String msg = response.optString("message", "");
            String detail = decodeRespondDataSafe(response, env.contentKey);
            logger.warn("smartCharge toggle failed: code=" + code + " message=" + msg
                    + " respondData=" + detail + " fullResponse=" + response.toString());
            // 1001 on write endpoints = generic rejection, NOT "unsupported".
            return false;
        }
        SmartChargeCache.setEnabled(enable);
        return true;
    }

    /**
     * Best-effort decryption of `respondData` for diagnostic logging. Returns the
     * decrypted JSON as a string, or a placeholder describing why we couldn't
     * decode it. Never throws — used in failure paths where we already have an
     * error and just want extra context.
     */
    private static String decodeRespondDataSafe(JSONObject response, String contentKey) {
        try {
            String hex = response.optString("respondData", "");
            if (hex.isEmpty()) return "<empty>";
            return BydCryptoUtils.aesDecryptUtf8(hex, contentKey);
        } catch (Exception e) {
            return "<decrypt-failed:" + e.getMessage() + ">";
        }
    }

    /** Mirror an inner request for logging with VIN redacted. */
    private static JSONObject redactVin(JSONObject src) {
        try {
            JSONObject copy = new JSONObject(src.toString());
            if (copy.has("vin")) {
                String v = copy.optString("vin", "");
                if (v.length() > 4) copy.put("vin", "***" + v.substring(v.length() - 4));
            }
            return copy;
        } catch (Exception e) {
            return src;
        }
    }

    /**
     * Save the smart-charging schedule (window + repeat + on/off).
     * Endpoint: /control/smartCharge/saveOrUpdate
     *
     * <p>Wire payload mirrors pyBYD's {@code trigger_save_charging_schedule}:
     * <pre>
     *   startChargeTime: "HH:MM"
     *   endChargeTime:   "HH:MM" or sentinel "full"
     *   chargeWay:       "s" one-shot | "e" every day | "0,1,2,3,4" weekday list (Mon=0)
     *   status:          "1" enabled | "0" disabled
     *   timeZone:        "" (always empty)
     * </pre>
     *
     * <p>The save endpoint is asynchronous — a successful POST returns a
     * {@code requestSerial} that must be polled against
     * {@code /control/smartCharge/changeResult} until {@code res != 1}. This
     * helper drives the trigger leg and the polling loop, returning true only
     * once the cloud reports terminal success ({@code res == 2}).
     */
    public boolean saveChargingSchedule(String vin,
                                         String startChargeTime,
                                         String endChargeTime,
                                         String chargeWay,
                                         boolean enabled) throws IOException {
        if (vin == null || vin.isEmpty()) throw new IOException("vin required");
        if (startChargeTime == null || !startChargeTime.matches("\\d{2}:\\d{2}")) {
            throw new IOException("startChargeTime must be HH:MM, got: " + startChargeTime);
        }
        if (endChargeTime == null
                || (!endChargeTime.equals("full") && !endChargeTime.matches("\\d{2}:\\d{2}"))) {
            throw new IOException("endChargeTime must be HH:MM or 'full', got: " + endChargeTime);
        }
        if (chargeWay == null || chargeWay.isEmpty()) {
            throw new IOException("chargeWay required");
        }

        BydCloudSession s = ensureSession();
        long nowMs = System.currentTimeMillis();
        JSONObject inner = buildInner(nowMs);
        try {
            inner.put("vin", vin);
            inner.put("startChargeTime", startChargeTime);
            inner.put("endChargeTime", endChargeTime);
            inner.put("chargeWay", chargeWay);
            inner.put("status", enabled ? "1" : "0");
            inner.put("timeZone", "");
        } catch (Exception e) {
            throw new IOException("Failed to build smartCharge save request", e);
        }
        logger.info("smartCharge save request inner=" + redactVin(inner));
        TokenEnvelope env = buildTokenOuterEnvelope(nowMs, s, inner);
        JSONObject response = transport.postSecure("/control/smartCharge/saveOrUpdate", env.outer);
        String code = response.optString("code", "");
        if (!"0".equals(code)) {
            String msg = response.optString("message", "");
            String detail = decodeRespondDataSafe(response, env.contentKey);
            logger.warn("smartCharge save failed: code=" + code + " message=" + msg
                    + " respondData=" + detail + " fullResponse=" + response.toString());
            return false;
        }

        String requestSerial = extractRequestSerial(response, env.contentKey);
        if (requestSerial == null || requestSerial.isEmpty()) {
            logger.warn("smartCharge save: missing requestSerial — assuming immediate success");
            SmartChargeCache.setSchedule(startChargeTime, endChargeTime, chargeWay, enabled);
            return true;
        }

        boolean ok = pollSmartChargeResult(vin, requestSerial);
        if (ok) {
            SmartChargeCache.setSchedule(startChargeTime, endChargeTime, chargeWay, enabled);
        }
        return ok;
    }

    /**
     * Poll /control/smartCharge/changeResult until res != 1 (terminal).
     * Per pyBYD: res == 2 is success; any other terminal int is failure.
     */
    private boolean pollSmartChargeResult(String vin, String requestSerial) throws IOException {
        BydCloudSession s = ensureSession();
        // pyBYD's _trigger_and_poll loop isn't visible, but the BYD app
        // typically resolves changeResult within ~5–10s. 12 attempts × 1s
        // gives a 12s ceiling — comfortably above the observed window
        // without holding the HTTP handler too long.
        for (int attempt = 0; attempt < 12; attempt++) {
            long nowMs = System.currentTimeMillis();
            JSONObject inner = buildInner(nowMs);
            try {
                inner.put("vin", vin);
                inner.put("requestSerial", requestSerial);
            } catch (Exception e) {
                throw new IOException("Failed to build changeResult request", e);
            }
            TokenEnvelope env = buildTokenOuterEnvelope(nowMs, s, inner);
            JSONObject response = transport.postSecure("/control/smartCharge/changeResult", env.outer);
            String code = response.optString("code", "");
            if (!"0".equals(code)) {
                String msg = response.optString("message", "");
                String detail = decodeRespondDataSafe(response, env.contentKey);
                logger.warn("smartCharge changeResult failed: attempt=" + attempt
                        + " code=" + code + " message=" + msg + " respondData=" + detail);
                return false;
            }
            JSONObject decoded = decodeRespondData(response, env.contentKey);
            if (decoded == null) {
                logger.info("smartCharge changeResult: empty respondData attempt=" + attempt);
            } else {
                int res = decoded.optInt("res", -1);
                if (res != 1) {
                    boolean success = (res == 2);
                    logger.info("smartCharge changeResult terminal: res=" + res
                            + " success=" + success + " attempts=" + (attempt + 1));
                    return success;
                }
            }
            try { Thread.sleep(1000L); }
            catch (InterruptedException ie) { Thread.currentThread().interrupt(); return false; }
        }
        logger.warn("smartCharge changeResult: timed out after 12 polls (requestSerial=" + requestSerial + ")");
        return false;
    }

    /** Decrypt requestSerial from a saveOrUpdate / changeChargeStatue response. */
    private static String extractRequestSerial(JSONObject response, String contentKey) {
        JSONObject decoded = decodeRespondData(response, contentKey);
        if (decoded == null) return null;
        String rs = decoded.optString("requestSerial", "");
        return rs.isEmpty() ? null : rs;
    }

    /** Best-effort respondData decode → JSON. Returns null on failure. */
    private static JSONObject decodeRespondData(JSONObject response, String contentKey) {
        try {
            String hex = response.optString("respondData", "");
            if (hex.isEmpty()) return null;
            return BydCloudTransport.decryptRespondData(hex, contentKey);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Fetch the smart-charging home page (charging-state telemetry).
     * Endpoint: /control/smartCharge/homePage
     *
     * <p>Per pyBYD's ChargingStatus model, this returns charging telemetry only —
     * elecPercent (SoC), connectState, chargingState, fullHour, fullMinute,
     * waitStatus, time. It does NOT echo back the configured schedule fields or
     * smartChargeSwitch. Treat it as read-only telemetry; the schedule itself
     * has no documented read endpoint, so we mirror writes locally
     * (UnifiedConfigManager "chargingSchedule" section) for UI hydration.
     */
    public JSONObject fetchSmartChargingStatus(String vin) throws IOException {
        BydCloudSession s = ensureSession();
        long nowMs = System.currentTimeMillis();
        JSONObject inner = buildInner(nowMs);
        try { inner.put("vin", vin); }
        catch (Exception e) { throw new IOException("Failed to build smartCharge fetch request", e); }
        TokenEnvelope env = buildTokenOuterEnvelope(nowMs, s, inner);
        JSONObject response = transport.postSecure("/control/smartCharge/homePage", env.outer);
        String code = response.optString("code", "");
        if (!"0".equals(code)) {
            String msg = response.optString("message", "");
            String detail = decodeRespondDataSafe(response, env.contentKey);
            logger.warn("smartCharge fetch failed: code=" + code + " message=" + msg
                    + " respondData=" + detail);
            throw new IOException("smartCharge fetch failed: code=" + code + " " + msg);
        }
        String respondData = response.optString("respondData", "");
        if (respondData.isEmpty()) return new JSONObject();
        JSONObject decoded = BydCloudTransport.decryptRespondData(respondData, env.contentKey);
        // Log the keys (not values — could contain SoC/PII) so we can confirm
        // what fields BYD's homePage actually exposes for this account.
        try {
            java.util.Iterator<String> it = decoded.keys();
            StringBuilder keys = new StringBuilder();
            while (it.hasNext()) {
                if (keys.length() > 0) keys.append(",");
                keys.append(it.next());
            }
            logger.info("smartCharge homePage keys=[" + keys + "]");
        } catch (Exception ignored) {}
        return decoded;
    }

    /**
     * Result struct for the router-aware overload of executeRemoteCommand.
     * Surfaces the BYD response code so the router can distinguish failure
     * modes (rate-limit 6024, auth, generic failure) from a hard exception.
     */
    public static final class CloudCommandResult {
        public final boolean success;
        public final String code;       // BYD response code, "0" on success
        public final String message;    // BYD response message
        public CloudCommandResult(boolean success, String code, String message) {
            this.success = success;
            this.code = code != null ? code : "";
            this.message = message != null ? message : "";
        }
    }

    /**
     * Router-facing variant that exposes the BYD response code for failure
     * classification (e.g., 6024 = "previous command in progress" → caller
     * should NOT fall back to SDK).
     */
    public CloudCommandResult executeRemoteCommandWithCode(String vin, String commandType,
                                                           JSONObject extraParams,
                                                           boolean waitForResult) throws IOException {
        return executeRemoteCommand(vin, commandType, extraParams, waitForResult);
    }

    private boolean executeRemoteCommand(String vin, String commandType, boolean waitForResult) throws IOException {
        return executeRemoteCommand(vin, commandType, null, waitForResult).success;
    }

    private CloudCommandResult executeRemoteCommand(String vin, String commandType,
                                                    JSONObject extraParams,
                                                    boolean waitForResult) throws IOException {
        if (!commandsVerified) {
            throw new IOException("Control PIN not verified. Call verifyControlPassword() first.");
        }

        BydCloudSession s = ensureSession();
        long nowMs = System.currentTimeMillis();

        // Build remote control request
        JSONObject inner = new JSONObject();
        try {
            inner.put("commandPwd", config.commandPwd);
            inner.put("commandType", commandType);
            inner.put("deviceType", "0");
            inner.put("imeiMD5", config.imeiMd5);
            inner.put("networkType", "wifi");
            inner.put("random", BydCryptoUtils.randomHex16());
            inner.put("timeStamp", String.valueOf(nowMs));
            inner.put("version", config.appInnerVersion);
            inner.put("vin", vin);
            if (extraParams != null) {
                Iterator<String> keys = extraParams.keys();
                while (keys.hasNext()) {
                    String k = keys.next();
                    inner.put(k, extraParams.opt(k));
                }
            }
        } catch (Exception e) {
            throw new IOException("Failed to build command request", e);
        }

        TokenEnvelope env = buildTokenOuterEnvelope(nowMs, s, inner);
        JSONObject response = transport.postSecure("/control/remoteControl", env.outer);

        String code = response.optString("code", "");
        if (!"0".equals(code)) {
            String msg = response.optString("message", "");
            String detail = decodeRespondDataSafe(response, env.contentKey);
            logger.warn("Remote command " + commandType + " failed: code=" + code
                    + " message=" + msg + " respondData=" + detail);
            return new CloudCommandResult(false, code, msg);
        }

        // Extract requestSerial for polling
        String respondData = response.optString("respondData", "");
        String requestSerial = null;
        if (!respondData.isEmpty()) {
            try {
                JSONObject rd = BydCloudTransport.decryptRespondData(respondData, env.contentKey);
                requestSerial = rd.optString("requestSerial", null);
            } catch (Exception e) {
                logger.debug("Could not parse remoteControl respondData: " + e.getMessage());
            }
        }

        // Poll for result (up to 5 attempts) — only if caller wants to wait
        if (requestSerial != null && waitForResult) {
            boolean ok = pollRemoteControlResult(vin, requestSerial, commandType, s);
            return new CloudCommandResult(ok, "0", "");
        }

        logger.info("Remote command " + commandType + " dispatched" + (waitForResult ? " (no serial)" : " (fire-and-forget)"));
        return new CloudCommandResult(true, "0", "");
    }

    private boolean pollRemoteControlResult(String vin, String requestSerial,
                                            String commandType, BydCloudSession s) throws IOException {
        int consecutiveServerErrors = 0;
        
        for (int attempt = 1; attempt <= 5; attempt++) {
            try {
                // Use exponential backoff on server errors to avoid spamming
                long delay = consecutiveServerErrors > 0 
                    ? Math.min(2000L * (1L << consecutiveServerErrors), 10000L)  // 4s, 8s, 10s...
                    : 2000L;
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }

            long nowMs = System.currentTimeMillis();
            JSONObject inner = new JSONObject();
            try {
                // CRITICAL: The result poll must mirror the trigger request structure.
                // Per pyBYD reference (jkaberg/pyBYD _api/control.py), the poll uses
                // the same _build_control_inner as the trigger — including commandPwd
                // and commandType. Without these, the BYD cloud returns 1009.
                inner.put("commandPwd", config.commandPwd);
                inner.put("commandType", commandType);
                inner.put("deviceType", "0");
                inner.put("imeiMD5", config.imeiMd5);
                inner.put("networkType", "wifi");
                inner.put("random", BydCryptoUtils.randomHex16());
                inner.put("requestSerial", requestSerial);
                inner.put("timeStamp", String.valueOf(nowMs));
                inner.put("version", config.appInnerVersion);
                inner.put("vin", vin);
            } catch (Exception e) {
                continue;
            }

            TokenEnvelope env = buildTokenOuterEnvelope(nowMs, s, inner);
            try {
                JSONObject response = transport.postSecure("/control/remoteControlResult", env.outer);
                String code = response.optString("code", "");
                
                // Server-side errors — back off and retry
                if ("1008".equals(code) || "1009".equals(code)) {
                    consecutiveServerErrors++;
                    if (consecutiveServerErrors >= 3) {
                        logger.info("Remote command result polling stopped after " + 
                            consecutiveServerErrors + " server errors (code=" + code + 
                            ") — command was dispatched successfully");
                        return true;  // Optimistic: command was dispatched
                    }
                    continue;
                }
                
                consecutiveServerErrors = 0;
                
                if (!"0".equals(code)) continue;

                String rd = response.optString("respondData", "");
                if (rd.isEmpty()) continue;

                JSONObject result = BydCloudTransport.decryptRespondData(rd, env.contentKey);
                int controlState = result.optInt("controlState", 0);
                // 0=pending, 1=success, 2=failure
                if (controlState == 1) {
                    logger.info("Remote command succeeded (attempt " + attempt + ")");
                    return true;
                } else if (controlState == 2) {
                    logger.warn("Remote command failed (controlState=2)");
                    return false;
                }
                // controlState=0 → still pending, continue polling
            } catch (Exception e) {
                logger.debug("Poll attempt " + attempt + " failed: " + e.getMessage());
            }
        }

        logger.info("Remote command polling timed out — command may still execute");
        return true; // Optimistic: command was dispatched
    }

    // ── Vehicle Realtime Data ──────────────────────────────────────────

    /**
     * Fetch vehicle realtime data via request/poll pattern.
     * Wakes the T-Box and polls until data is ready (up to 10 attempts, 1.5s apart).
     */
    public JSONObject fetchVehicleRealtime(String vin) throws IOException {
        BydCloudSession s = ensureSession();
        long nowMs = System.currentTimeMillis();

        JSONObject inner = buildInner(nowMs);
        try {
            inner.put("energyType", "0");
            inner.put("tboxVersion", "3");
            inner.put("vin", vin);
        } catch (Exception e) {
            throw new IOException("Failed to build realtime request", e);
        }

        TokenEnvelope env = buildTokenOuterEnvelope(nowMs, s, inner);
        JSONObject response = transport.postSecure(
                "/vehicleInfo/vehicle/vehicleRealTimeRequest", env.outer);

        String code = response.optString("code", "");
        if (!"0".equals(code)) {
            throw new IOException("Realtime request failed: code=" + code);
        }

        String respondData = response.optString("respondData", "");
        JSONObject vehicleInfo = null;
        String requestSerial = null;

        if (!respondData.isEmpty()) {
            JSONObject decoded = BydCloudTransport.decryptRespondData(respondData, env.contentKey);
            requestSerial = decoded.optString("requestSerial", null);
            if (isRealtimeReady(decoded)) return decoded;
            vehicleInfo = decoded;
        }

        if (requestSerial == null || requestSerial.isEmpty()) return vehicleInfo;

        for (int attempt = 1; attempt <= 10; attempt++) {
            try { Thread.sleep(1500); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return vehicleInfo;
            }

            nowMs = System.currentTimeMillis();
            JSONObject pollInner = buildInner(nowMs);
            try {
                pollInner.put("energyType", "0");
                pollInner.put("tboxVersion", "3");
                pollInner.put("vin", vin);
                pollInner.put("requestSerial", requestSerial);
            } catch (Exception e) { continue; }

            TokenEnvelope pollEnv = buildTokenOuterEnvelope(nowMs, s, pollInner);
            try {
                JSONObject pollResp = transport.postSecure(
                        "/vehicleInfo/vehicle/vehicleRealTimeResult", pollEnv.outer);
                if (!"0".equals(pollResp.optString("code", ""))) continue;

                String pollData = pollResp.optString("respondData", "");
                if (pollData.isEmpty()) continue;

                JSONObject decoded = BydCloudTransport.decryptRespondData(pollData, pollEnv.contentKey);
                String newSerial = decoded.optString("requestSerial", null);
                if (newSerial != null && !newSerial.isEmpty()) requestSerial = newSerial;

                if (isRealtimeReady(decoded)) {
                    logger.info("Realtime data ready (attempt " + attempt + ")");
                    return decoded;
                }
                vehicleInfo = decoded;
            } catch (Exception e) {
                logger.debug("Realtime poll " + attempt + " failed: " + e.getMessage());
            }
        }

        return vehicleInfo;
    }

    private boolean isRealtimeReady(JSONObject vi) {
        if (vi == null) return false;
        if (vi.optInt("onlineState", -1) == 2) return false;
        // Lock fields are the highest-value signal for our use case — only
        // consider the response "ready" once at least one lock field is
        // populated.  Without this, an early response with telemetry but
        // no lock state would short-circuit the poll loop and we'd miss
        // the lock data that arrives ~1.5 s later.
        int lfLock = vi.optInt("leftFrontDoorLock", -1);
        int rfLock = vi.optInt("rightFrontDoorLock", -1);
        int lrLock = vi.optInt("leftRearDoorLock", -1);
        int rrLock = vi.optInt("rightRearDoorLock", -1);
        boolean hasLock = lfLock > 0 || rfLock > 0 || lrLock > 0 || rrLock > 0;
        if (!hasLock) return false;
        // Plus a sanity check that telemetry has also landed.
        if (vi.optDouble("leftFrontTirepressure", 0) > 0) return true;
        if (vi.optDouble("rightFrontTirepressure", 0) > 0) return true;
        if (vi.optLong("time", 0) > 0) return true;
        if (vi.optDouble("enduranceMileage", 0) > 0) return true;
        return false;
    }

    // ── Request Builders ────────────────────────────────────────────────

    /**
     * Fetch the EMQ MQTT broker hostname for real-time push subscription.
     */
    public String fetchEmqBrokerHost() throws IOException {
        BydCloudSession s = ensureSession();
        long nowMs = System.currentTimeMillis();

        JSONObject inner = buildInner(nowMs);
        TokenEnvelope env = buildTokenOuterEnvelope(nowMs, s, inner);
        JSONObject response = transport.postSecure("/app/emqAuth/getEmqBrokerIp", env.outer);

        String code = response.optString("code", "");
        if (!"0".equals(code)) {
            throw new IOException("Broker lookup failed: code=" + code
                    + " message=" + response.optString("message", ""));
        }

        String respondData = response.optString("respondData", "");
        if (respondData.isEmpty()) {
            throw new IOException("Broker lookup: empty respondData");
        }

        JSONObject decoded = BydCloudTransport.decryptRespondData(respondData, env.contentKey);
        // BYD API has a typo: "emqBorker" (sic) — check both spellings
        String broker = decoded.optString("emqBorker", "");
        if (broker.isEmpty()) broker = decoded.optString("emqBroker", "");
        if (broker.isEmpty()) {
            throw new IOException("Broker lookup response missing broker hostname");
        }

        logger.info("EMQ broker resolved: " + broker);
        return broker;
    }

    /**
     * Build MQTT credentials for connecting to BYD's EMQ broker.
     * Returns [clientId, username, password].
     */
    public String[] buildMqttCredentials() throws IOException {
        BydCloudSession s = ensureSession();
        String clientId = "oversea_" + config.imeiMd5.toUpperCase();
        String username = s.userId;
        long tsSeconds = System.currentTimeMillis() / 1000;
        String passwordBase = s.signToken + clientId + s.userId + tsSeconds;
        String password = tsSeconds + com.overdrive.app.byd.cloud.crypto.BydCryptoUtils.md5Hex(passwordBase);
        return new String[]{clientId, username, password};
    }

    /**
     * Get the MQTT topic for vehicle push messages.
     */
    public String getMqttTopic() throws IOException {
        BydCloudSession s = ensureSession();
        return "oversea/res/" + s.userId;
    }

    /**
     * Get the content key for decrypting MQTT messages.
     */
    public String getMqttDecryptKey() throws IOException {
        BydCloudSession s = ensureSession();
        return s.contentKey();
    }

    /**
     * Get the current session (for reconnection credential rebuilding).
     */
    public BydCloudSession getSession() {
        return session;
    }

    private JSONObject buildLoginRequest(long nowMs) {
        try {
            String random = BydCryptoUtils.randomHex16();
            String reqTimestamp = String.valueOf(nowMs);

            // Inner payload (device info)
            JSONObject inner = new JSONObject();
            inner.put("appInnerVersion", config.appInnerVersion);
            inner.put("appVersion", config.appVersion);
            inner.put("deviceName", "XIAOMIPOCO F1");
            inner.put("deviceType", "0");
            inner.put("imeiMD5", config.imeiMd5);
            inner.put("isAuto", "1");
            inner.put("mobileBrand", "XIAOMI");
            inner.put("mobileModel", "POCO F1");
            inner.put("networkType", "wifi");
            inner.put("osType", "15");
            inner.put("osVersion", "35");
            inner.put("random", random);
            inner.put("softType", "0");
            inner.put("timeStamp", reqTimestamp);
            inner.put("timeZone", "Asia/Kolkata");

            String encryData = BydCryptoUtils.aesEncryptHex(
                    inner.toString(), config.loginKey);

            // Sign fields = inner fields + outer context
            JSONObject signFields = new JSONObject(inner.toString());
            signFields.put("countryCode", config.countryCode);
            signFields.put("functionType", "pwdLogin");
            signFields.put("identifier", config.username);
            signFields.put("identifierType", "0");
            signFields.put("language", config.language);
            signFields.put("reqTimestamp", reqTimestamp);

            String sign = BydCryptoUtils.sha1Mixed(
                    BydCryptoUtils.buildSignString(signFields, config.signPassword));

            // Outer payload
            JSONObject outer = new JSONObject();
            outer.put("countryCode", config.countryCode);
            outer.put("encryData", encryData);
            outer.put("functionType", "pwdLogin");
            outer.put("identifier", config.username);
            outer.put("identifierType", "0");
            outer.put("imeiMD5", config.imeiMd5);
            outer.put("isAuto", "1");
            outer.put("language", config.language);
            outer.put("reqTimestamp", reqTimestamp);
            outer.put("sign", sign);
            outer.put("signKey", config.rawPassword);
            // Common device fields
            outer.put("ostype", "and");
            outer.put("imei", "BANGCLE01234");
            outer.put("mac", "00:00:00:00:00:00");
            outer.put("model", "POCO F1");
            outer.put("sdk", "35");
            outer.put("mod", "Xiaomi");
            outer.put("serviceTime", String.valueOf(System.currentTimeMillis()));

            outer.put("checkcode", BydCryptoUtils.computeCheckcode(outer));

            return outer;
        } catch (Exception e) {
            throw new RuntimeException("Failed to build login request", e);
        }
    }

    private JSONObject buildInner(long nowMs) {
        try {
            JSONObject inner = new JSONObject();
            inner.put("deviceType", "0");
            inner.put("imeiMD5", config.imeiMd5);
            inner.put("networkType", "wifi");
            inner.put("random", BydCryptoUtils.randomHex16());
            inner.put("timeStamp", String.valueOf(nowMs));
            inner.put("version", config.appInnerVersion);
            return inner;
        } catch (Exception e) {
            throw new RuntimeException("Failed to build inner payload", e);
        }
    }

    /** Envelope result: outer payload + content key for decrypting respondData. */
    private static final class TokenEnvelope {
        final JSONObject outer;
        final String contentKey;
        TokenEnvelope(JSONObject outer, String contentKey) {
            this.outer = outer;
            this.contentKey = contentKey;
        }
    }

    private TokenEnvelope buildTokenOuterEnvelope(long nowMs, BydCloudSession s, JSONObject inner) {
        try {
            String reqTimestamp = String.valueOf(nowMs);
            String contentKey = s.contentKey();
            String signKey = s.signKey();

            String encryData = BydCryptoUtils.aesEncryptHex(inner.toString(), contentKey);

            // Build sign fields: inner + outer context
            JSONObject signFields = new JSONObject(inner.toString());
            signFields.put("countryCode", config.countryCode);
            signFields.put("identifier", s.userId);
            signFields.put("imeiMD5", config.imeiMd5);
            signFields.put("language", config.language);
            signFields.put("reqTimestamp", reqTimestamp);

            String sign = BydCryptoUtils.sha1Mixed(
                    BydCryptoUtils.buildSignString(signFields, signKey));

            JSONObject outer = new JSONObject();
            outer.put("countryCode", config.countryCode);
            outer.put("encryData", encryData);
            outer.put("identifier", s.userId);
            outer.put("imeiMD5", config.imeiMd5);
            outer.put("language", config.language);
            outer.put("reqTimestamp", reqTimestamp);
            outer.put("sign", sign);
            // Common device fields
            outer.put("ostype", "and");
            outer.put("imei", "BANGCLE01234");
            outer.put("mac", "00:00:00:00:00:00");
            outer.put("model", "POCO F1");
            outer.put("sdk", "35");
            outer.put("mod", "Xiaomi");
            outer.put("serviceTime", String.valueOf(System.currentTimeMillis()));

            outer.put("checkcode", BydCryptoUtils.computeCheckcode(outer));

            return new TokenEnvelope(outer, contentKey);
        } catch (Exception e) {
            throw new RuntimeException("Failed to build token envelope", e);
        }
    }
}
