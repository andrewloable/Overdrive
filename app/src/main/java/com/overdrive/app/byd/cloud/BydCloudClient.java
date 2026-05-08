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
     */
    public void login() throws IOException {
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
     */
    public BydCloudSession ensureSession() throws IOException {
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
    public void verifyControlPassword(String vin) throws IOException {
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

    private boolean executeRemoteCommand(String vin, String commandType, boolean waitForResult) throws IOException {
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
        } catch (Exception e) {
            throw new IOException("Failed to build command request", e);
        }

        TokenEnvelope env = buildTokenOuterEnvelope(nowMs, s, inner);
        JSONObject response = transport.postSecure("/control/remoteControl", env.outer);

        String code = response.optString("code", "");
        if (!"0".equals(code)) {
            String msg = response.optString("message", "");
            logger.warn("Remote command " + commandType + " failed: code=" + code + " " + msg);
            return false;
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
            return pollRemoteControlResult(vin, requestSerial, commandType, s);
        }

        logger.info("Remote command " + commandType + " dispatched" + (waitForResult ? " (no serial)" : " (fire-and-forget)"));
        return true;
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
