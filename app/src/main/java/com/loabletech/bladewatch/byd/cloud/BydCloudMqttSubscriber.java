package com.overdrive.app.byd.cloud;

import com.overdrive.app.byd.cloud.crypto.BydCryptoUtils;
import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.mqtt.ProxyHelper;

import org.eclipse.paho.mqttv5.client.IMqttToken;
import org.eclipse.paho.mqttv5.client.MqttCallback;
import org.eclipse.paho.mqttv5.client.MqttClient;
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions;
import org.eclipse.paho.mqttv5.client.MqttDisconnectResponse;
import org.eclipse.paho.mqttv5.client.persist.MemoryPersistence;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.eclipse.paho.mqttv5.common.packet.MqttProperties;
import org.json.JSONObject;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Subscribes to BYD's EMQ MQTT broker for real-time vehicle state push.
 * Decrypts incoming messages and feeds them to BydCloudDataProvider.
 *
 * Uses MQTT v5 (paho.mqttv5) — BYD's EMQ broker accepts v3.1.1 connections
 * but only routes vehicleInfo push events to v5 subscribers. The reference
 * implementations (Niek/BYD-re, jkaberg/pyBYD) both use v5 explicitly.
 */
public final class BydCloudMqttSubscriber implements MqttCallback {

    private static final String TAG = "CloudMqttSub";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    private static final int BACKOFF_BASE_SECONDS = 5;
    private static final int BACKOFF_CAP_SECONDS = 300;
    private static final long SESSION_REFRESH_MS = 25 * 60 * 1000; // 25 min (before 30 min expiry)
    private static final long REAUTH_COOLDOWN_MS = 60 * 1000; // matches pyBYD _MQTT_REAUTH_COOLDOWN_S

    private final BydCloudClient client;
    private final BydCloudDataProvider dataProvider;

    private volatile MqttClient mqttClient;
    private volatile String decryptKey;
    private volatile String topic;
    private volatile boolean running = false;
    private volatile int consecutiveFailures = 0;
    private volatile long lastConnectAttemptMs = 0;
    private volatile long lastReauthAtMs = 0;
    // Decrypt failures since last successful decrypt.  We allow a few before
    // assuming the key is actually stale, since BYD's broker often delivers
    // a retained message at subscribe time that was encrypted with a prior
    // session's key — re-authing on every isolated failure is too aggressive.
    private volatile int consecutiveDecryptFailures = 0;
    private static final int DECRYPT_FAILURE_THRESHOLD = 3;
    private static final long DECRYPT_FAILURE_WINDOW_MS = 60 * 1000;
    private volatile long firstDecryptFailureAtMs = 0;

    private final AtomicBoolean connecting = new AtomicBoolean(false);
    private ScheduledExecutorService scheduler;

    public BydCloudMqttSubscriber(BydCloudClient client) {
        this.client = client;
        this.dataProvider = BydCloudDataProvider.getInstance();
    }

    public void start() {
        if (running) return;
        running = true;
        consecutiveFailures = 0;

        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "CloudMqttSub");
            t.setDaemon(true);
            return t;
        });

        scheduler.execute(this::connectAndSubscribe);

        // Session refresh timer
        scheduler.scheduleAtFixedRate(() -> {
            if (running) refreshSession();
        }, SESSION_REFRESH_MS, SESSION_REFRESH_MS, TimeUnit.MILLISECONDS);
    }

    public void stop() {
        running = false;
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        disconnectQuietly();
        dataProvider.reset();
    }

    public boolean isConnected() {
        return mqttClient != null && mqttClient.isConnected();
    }

    // ── Connection ──────────────────────────────────────────────────────

    private void connectAndSubscribe() {
        if (!running || !connecting.compareAndSet(false, true)) return;

        // Tracks how far we got so we can reset the backoff counter on
        // partial progress (e.g. broker resolved but TLS handshake failed —
        // those are transient and shouldn't escalate to 300s reconnect).
        boolean brokerResolved = false;

        try {
            lastConnectAttemptMs = System.currentTimeMillis();

            // Ensure we have a valid session
            client.ensureSession();

            // Discover broker
            String brokerHost = client.fetchEmqBrokerHost();
            brokerResolved = true;
            // Broker may already include port (e.g., "host:8883") — don't double-append
            String brokerUri;
            if (brokerHost.contains(":")) {
                brokerUri = "ssl://" + brokerHost;
            } else {
                brokerUri = "ssl://" + brokerHost + ":8883";
            }

            // Build credentials
            String[] creds = client.buildMqttCredentials();
            String clientId = creds[0];
            String username = creds[1];
            String password = creds[2];

            topic = client.getMqttTopic();
            decryptKey = client.getMqttDecryptKey();

            logger.info("Connecting to BYD EMQ: " + brokerUri + " topic=" + topic
                    + " proxy=" + ProxyHelper.isProxyAvailable());

            // Create Paho v5 client
            MqttClient mc = new MqttClient(brokerUri, clientId, new MemoryPersistence());
            mc.setCallback(this);

            MqttConnectionOptions opts = new MqttConnectionOptions();
            opts.setCleanStart(true);
            opts.setConnectionTimeout(15);
            opts.setKeepAliveInterval(60);
            opts.setAutomaticReconnect(false);
            opts.setUserName(username);
            opts.setPassword(password.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            // Be explicit on session expiry so the broker doesn't drop our
            // queued messages between reconnects.
            opts.setSessionExpiryInterval(0L);

            // SSL with proxy support — same pattern as MqttPublisherService.
            boolean proxyActive = ProxyHelper.isProxyAvailable();
            if (proxyActive) {
                opts.setSocketFactory(ProxyHelper.getProxiedSslSocketFactory(false));
            } else {
                // Clear any leftover system SOCKS properties
                System.clearProperty("socksProxyHost");
                System.clearProperty("socksProxyPort");
                opts.setSocketFactory(javax.net.ssl.SSLSocketFactory.getDefault());
            }

            mc.connect(opts);
            // v5 subscribe API — IMqttMessageListener takes (topic, msg).
            mc.subscribe(topic, 1, (t, msg) -> {
                logger.info("EMQ raw message: topic=" + t
                        + " bytes=" + (msg.getPayload() != null ? msg.getPayload().length : 0)
                        + " qos=" + msg.getQos() + " retained=" + msg.isRetained());
            });

            mqttClient = mc;
            consecutiveFailures = 0;
            dataProvider.setMqttConnected(true);
            logger.info("Connected and subscribed to BYD EMQ topic=" + topic);

        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : "";
            logger.warn("EMQ connect failed: " + msg);
            ProxyHelper.invalidateCache();

            // Classify the failure:
            //   - true auth failure: HTTP 401/403, "token expired", "Login failed"
            //   - transient broker/service error: 1005, 1008, 1009, "Unspecified error"
            //   - other (network, TLS) — also transient
            // Only true auth requires a forced re-login; transient errors should
            // back off, not churn login() calls (which invalidate any healthy
            // session held by other paths and creates a 1005 retry loop).
            boolean isAuthFailure =
                    msg.contains("token expired")
                    || msg.contains("Login failed")
                    || msg.contains(" 401 ") || msg.contains(" 403 ");
            boolean isTransientService = msg.contains("1005")
                    || msg.contains("1008")
                    || msg.contains("1009")
                    || msg.contains("Unspecified")
                    || msg.contains("Service error");

            if (isAuthFailure) {
                try {
                    logger.info("Forcing re-login due to genuine auth error...");
                    client.login();
                } catch (Exception loginErr) {
                    logger.warn("Re-login failed: " + loginErr.getMessage());
                }
            }

            disconnectQuietly();

            // Backoff strategy: if we made any forward progress (broker
            // resolved) the failure is downstream — TLS race, broker hiccup —
            // and we should retry quickly instead of ramping into a 5-minute
            // window. If we never resolved the broker, escalate normally.
            // Transient service errors (1005/1008/1009) get a fixed short
            // delay because they're an upstream BYD condition that's unlikely
            // to clear faster on exponential ramp.
            if (brokerResolved || isTransientService) {
                consecutiveFailures = 1; // reset, but keep at first-attempt
                scheduleReconnect(15);   // 15-second fixed retry
            } else {
                consecutiveFailures++;
                scheduleReconnect(0); // 0 = compute from consecutiveFailures
            }
        } finally {
            connecting.set(false);
        }
    }

    private void scheduleReconnect() {
        scheduleReconnect(0);
    }

    /**
     * @param fixedDelaySeconds if > 0, use this delay; if 0, compute from
     *                          consecutiveFailures with exponential backoff.
     */
    private void scheduleReconnect(long fixedDelaySeconds) {
        if (!running || scheduler == null || scheduler.isShutdown()) return;
        long delay = fixedDelaySeconds > 0
                ? fixedDelaySeconds
                : Math.min(
                        BACKOFF_BASE_SECONDS * (1L << Math.min(consecutiveFailures - 1, 10)),
                        BACKOFF_CAP_SECONDS);
        logger.info("Reconnecting in " + delay + "s (attempt " + consecutiveFailures + ")");
        try {
            scheduler.schedule(this::connectAndSubscribe, delay, TimeUnit.SECONDS);
        } catch (Exception ignored) {}
    }

    private void refreshSession() {
        if (!running) return;
        try {
            logger.info("Refreshing BYD cloud session...");
            disconnectQuietly();
            // Force a fresh login — ensureSession() is a no-op while the
            // current 30-min session hasn't expired, so the encryToken
            // (and therefore decryptKey) would never rotate.
            client.login();
            connectAndSubscribe();
        } catch (Exception e) {
            logger.warn("Session refresh failed: " + e.getMessage());
            scheduleReconnect();
        }
    }

    /**
     * Increment the decrypt-failure counter and only trigger a re-auth
     * once we've seen the threshold crossed within the failure window.
     * BYD's broker often delivers a retained message at subscribe time
     * encrypted with a prior session's key — re-authing on the first
     * isolated failure causes the connect-then-immediately-reconnect
     * loop visible in the logs. The threshold lets us absorb that.
     */
    private void recordDecryptFailure(String kind) {
        long now = System.currentTimeMillis();
        if (consecutiveDecryptFailures == 0) {
            firstDecryptFailureAtMs = now;
        } else if (now - firstDecryptFailureAtMs > DECRYPT_FAILURE_WINDOW_MS) {
            // Window expired — restart the count.
            consecutiveDecryptFailures = 0;
            firstDecryptFailureAtMs = now;
        }
        consecutiveDecryptFailures++;
        logger.debug("MQTT decrypt failed (" + kind + ") — count="
                + consecutiveDecryptFailures + "/" + DECRYPT_FAILURE_THRESHOLD);
        if (consecutiveDecryptFailures >= DECRYPT_FAILURE_THRESHOLD) {
            logger.warn("MQTT decrypt failed " + consecutiveDecryptFailures
                    + " times in window — assuming stale key, re-authing");
            consecutiveDecryptFailures = 0;
            firstDecryptFailureAtMs = 0;
            scheduleReauth();
        }
    }

    /**
     * Triggered when message decryption fails repeatedly — assume the
     * server-side key rotated and force a full re-login + reconnect.
     * Rate-limited to avoid login storms on truly malformed traffic.
     */
    private void scheduleReauth() {
        if (!running) return;
        long now = System.currentTimeMillis();
        if (now - lastReauthAtMs < REAUTH_COOLDOWN_MS) return;
        lastReauthAtMs = now;

        ScheduledExecutorService s = scheduler;
        if (s == null || s.isShutdown()) return;

        logger.info("MQTT decrypt failed — scheduling re-authentication");
        try {
            s.execute(() -> {
                if (!running) return;
                try {
                    disconnectQuietly();
                    client.login();
                    connectAndSubscribe();
                } catch (Exception e) {
                    logger.warn("MQTT re-auth failed: " + e.getMessage());
                    scheduleReconnect();
                }
            });
        } catch (Exception ignored) {}
    }

    private void disconnectQuietly() {
        MqttClient mc = mqttClient;
        mqttClient = null;
        dataProvider.setMqttConnected(false);
        if (mc != null) {
            try {
                if (mc.isConnected()) mc.disconnect(2000);
            } catch (Exception ignored) {}
            try { mc.close(); } catch (Exception ignored) {}
        }
        // Clean up JVM-level SOCKS proxy properties
        System.clearProperty("socksProxyHost");
        System.clearProperty("socksProxyPort");
    }

    // ── MqttCallback (v5) ───────────────────────────────────────────────

    @Override
    public void disconnected(MqttDisconnectResponse response) {
        dataProvider.setMqttConnected(false);
        String msg = response != null && response.getException() != null
                ? response.getException().getMessage()
                : (response != null ? response.getReasonString() : "unknown");
        logger.warn("EMQ disconnected: " + msg);
        consecutiveFailures++;
        ProxyHelper.invalidateCache();
        if (running) scheduleReconnect();
    }

    @Override
    public void mqttErrorOccurred(MqttException exception) {
        logger.warn("EMQ error: " + (exception != null ? exception.getMessage() : "unknown"));
    }

    @Override
    public void connectComplete(boolean reconnect, String serverURI) {
        logger.info("EMQ connectComplete: reconnect=" + reconnect + " uri=" + serverURI);
    }

    @Override
    public void authPacketArrived(int reasonCode, MqttProperties properties) {
        // Auth-packet flow not used by BYD — log for diagnostics only.
        logger.debug("EMQ authPacketArrived: reason=" + reasonCode);
    }

    @Override
    public void messageArrived(String topic, MqttMessage message) {
        byte[] payload = message.getPayload();
        // Always log arrival — this is the smoking gun for "are we even
        // getting messages from the broker?" investigations.
        logger.info("EMQ messageArrived: topic=" + topic
                + " bytes=" + (payload != null ? payload.length : 0)
                + " qos=" + message.getQos() + " retained=" + message.isRetained());
        if (payload == null || payload.length == 0) return;

        String encrypted = new String(payload, java.nio.charset.StandardCharsets.UTF_8).trim();
        if (encrypted.isEmpty()) return;

        // ── Decrypt ─────────────────────────────────────────────────────
        String decrypted;
        try {
            decrypted = BydCryptoUtils.aesDecryptUtf8(encrypted, decryptKey);
        } catch (Exception e) {
            // AES failure (BadPadding) or wrong key producing garbage UTF-8.
            // Treat as a *single* failure — only re-auth once we see the
            // failure threshold crossed in the failure window.  Isolated
            // failures (e.g. retained-message replay encrypted with the
            // prior session's key) shouldn't trigger a full re-login.
            recordDecryptFailure("AES");
            return;
        }

        JSONObject envelope;
        try {
            envelope = new JSONObject(decrypted);
        } catch (Exception e) {
            // Decrypted but not valid JSON — wrong-key symptom (random
            // bytes happened to satisfy PKCS#7 padding).  Counted into
            // the same failure threshold.
            recordDecryptFailure("JSON");
            return;
        }

        // Successful decrypt — reset the failure counter.
        consecutiveDecryptFailures = 0;
        firstDecryptFailureAtMs = 0;

        // ── Unwrap envelope ─────────────────────────────────────────────
        // BYD MQTT push shape: { event, vin, data: { uuid, respondData: {...} } }
        // (matches pyBYD _on_mqtt_event)
        String event = envelope.optString("event", "");
        JSONObject data = envelope.optJSONObject("data");
        JSONObject respondData = data != null ? data.optJSONObject("respondData") : null;
        if (respondData == null) respondData = envelope; // legacy / unwrapped fallback

        try {
            switch (event) {
                case "vehicleInfo":
                    dataProvider.updateFromVehicleInfo(respondData, null);
                    break;
                // Other event types (smartCharge, energyConsumption,
                // remoteControl) currently have no consumer — ignore quietly.
                default:
                    logger.debug("MQTT event ignored: event=" + event);
                    break;
            }
        } catch (Exception e) {
            logger.warn("MQTT dispatch failed: " + e.getMessage());
        }
    }

    @Override
    public void deliveryComplete(IMqttToken token) {
        // Subscriber only — no publishes
    }
}
