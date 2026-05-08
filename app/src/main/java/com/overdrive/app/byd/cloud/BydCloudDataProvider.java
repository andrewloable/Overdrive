package com.overdrive.app.byd.cloud;

import com.overdrive.app.logging.DaemonLogger;

import org.json.JSONObject;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Singleton that owns the cloud vehicle data snapshot and notifies
 * listeners on lock state changes. Fed by BydCloudMqttSubscriber.
 */
public final class BydCloudDataProvider {

    private static final String TAG = "CloudDataProvider";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    private static volatile BydCloudDataProvider instance;

    private final AtomicReference<VehicleCloudSnapshot> snapshot = new AtomicReference<>();
    private final CopyOnWriteArrayList<CloudLockStateListener> lockListeners = new CopyOnWriteArrayList<>();

    // Track previous lock state to detect transitions
    private volatile boolean lastKnownLocked = false;
    private volatile boolean lastKnownValid = false;
    private volatile long totalMessagesReceived = 0;
    private volatile long lastMessageReceivedAt = 0;
    private volatile boolean mqttConnected = false;

    private BydCloudDataProvider() {}

    public static BydCloudDataProvider getInstance() {
        if (instance == null) {
            synchronized (BydCloudDataProvider.class) {
                if (instance == null) instance = new BydCloudDataProvider();
            }
        }
        return instance;
    }

    // ── Listener interface ──────────────────────────────────────────────

    public interface CloudLockStateListener {
        void onCloudLockStateChanged(boolean locked, long timestampMs);
    }

    public void addLockStateListener(CloudLockStateListener listener) {
        if (listener != null && !lockListeners.contains(listener)) {
            lockListeners.add(listener);
        }
    }

    public void removeLockStateListener(CloudLockStateListener listener) {
        lockListeners.remove(listener);
    }

    // ── Snapshot access ─────────────────────────────────────────────────

    public VehicleCloudSnapshot getSnapshot() {
        return snapshot.get();
    }

    public boolean isConnectionHealthy() {
        VehicleCloudSnapshot s = snapshot.get();
        return s != null && s.isConnectionHealthy();
    }

    public boolean isLockStateFresh() {
        VehicleCloudSnapshot s = snapshot.get();
        return s != null && s.isLockStateFresh() && s.hasValidLockState();
    }

    public boolean isTelemetryFresh() {
        VehicleCloudSnapshot s = snapshot.get();
        return s != null && s.isTelemetryFresh();
    }

    // ── Data ingestion ──────────────────────────────────────────────────

    /**
     * Called by BydCloudMqttSubscriber when a new vehicleInfo message arrives.
     * Parses the JSON, updates the snapshot, and fires lock state listeners
     * if the lock state changed.
     */
    public void updateFromVehicleInfo(JSONObject vehicleInfo) {
        updateFromVehicleInfo(vehicleInfo, null);
    }

    public void updateFromVehicleInfo(JSONObject vehicleInfo, JSONObject hvac) {
        if (vehicleInfo == null) return;

        VehicleCloudSnapshot prev = snapshot.get();
        VehicleCloudSnapshot next = VehicleCloudSnapshot.fromVehicleInfo(vehicleInfo, hvac).build();
        snapshot.set(next);
        totalMessagesReceived++;
        lastMessageReceivedAt = System.currentTimeMillis();

        // Detect lock state transitions
        if (next.hasValidLockState()) {
            boolean nowLocked = next.isAllLocked();
            boolean nowUnlocked = next.isAnyUnlocked();

            if (nowLocked && (!lastKnownValid || !lastKnownLocked)) {
                lastKnownLocked = true;
                lastKnownValid = true;
                logger.info("Cloud lock state: LOCKED");
                fireLockStateChanged(true, next.receivedAt);
            } else if (nowUnlocked && (!lastKnownValid || lastKnownLocked)) {
                lastKnownLocked = false;
                lastKnownValid = true;
                logger.info("Cloud lock state: UNLOCKED");
                fireLockStateChanged(false, next.receivedAt);
            }
        }
    }

    private void fireLockStateChanged(boolean locked, long timestampMs) {
        for (CloudLockStateListener listener : lockListeners) {
            try {
                listener.onCloudLockStateChanged(locked, timestampMs);
            } catch (Exception e) {
                logger.warn("Lock listener error: " + e.getMessage());
            }
        }
    }

    /**
     * Reset state (credential change, disconnect).
     */
    public void reset() {
        stopSubscriber();
        snapshot.set(null);
        lastKnownLocked = false;
        lastKnownValid = false;
        mqttConnected = false;
    }

    // ── Subscriber lifecycle ────────────────────────────────────────────

    private volatile BydCloudMqttSubscriber subscriber;

    /**
     * Start the MQTT subscriber if BYD Cloud credentials are configured and verified.
     * Safe to call multiple times — no-ops if already running.
     */
    public void startSubscriberIfConfigured() {
        if (subscriber != null) return;

        try {
            BydCloudConfig config = BydCloudConfig.fromUnifiedConfig();
            if (!config.isVerified()) return;

            BydCloudClient client = new BydCloudClient(config);

            // Load Bangcle tables (same pattern as BydCloudDeterrent)
            java.io.InputStream tablesStream = loadBangcleTables();
            if (tablesStream == null) {
                logger.warn("Cannot start cloud subscriber: Bangcle tables not available");
                return;
            }
            try {
                client.init(tablesStream);
            } finally {
                try { tablesStream.close(); } catch (Exception ignored) {}
            }

            subscriber = new BydCloudMqttSubscriber(client);
            subscriber.start();
            logger.info("Cloud MQTT subscriber started");

            // Start REST poller if toggle is on
            syncPollerState();
        } catch (Exception e) {
            logger.warn("Failed to start cloud subscriber: " + e.getMessage());
        }
    }

    public void stopSubscriber() {
        if (subscriber != null) {
            subscriber.stop();
            subscriber = null;
        }
        stopRealtimePoller();
        mqttConnected = false;
    }

    // ── REST Realtime Poller (toggle-gated) ─────────────────────────────

    private volatile java.util.concurrent.ScheduledExecutorService realtimePoller;
    private static final long POLL_INTERVAL_MS = 5 * 60 * 1000; // 5 minutes

    /**
     * Start or stop the REST realtime poller based on the cloudDataMerge toggle.
     * Called on toggle change and on subscriber start.
     */
    public void syncPollerState() {
        BydCloudConfig config = BydCloudConfig.fromUnifiedConfig();
        if (config.cloudDataMerge && config.isVerified()) {
            startRealtimePoller(config.vin);
        } else {
            stopRealtimePoller();
        }
    }

    private void startRealtimePoller(String vin) {
        if (realtimePoller != null) return; // already running
        if (vin == null || vin.isEmpty()) return;

        logger.info("Starting REST realtime poller (every 5 min)");
        realtimePoller = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "CloudRealtimePoll");
            t.setDaemon(true);
            return t;
        });

        // Initial fetch immediately, then every 5 minutes
        final String pollVin = vin;
        realtimePoller.scheduleAtFixedRate(() -> {
            try {
                BydCloudConfig cfg = BydCloudConfig.fromUnifiedConfig();
                if (!cfg.cloudDataMerge) {
                    logger.info("Cloud data merge disabled — stopping poller");
                    stopRealtimePoller();
                    return;
                }

                BydCloudClient client = getOrCreateClient();
                if (client == null) return;

                JSONObject vehicleInfo = client.fetchVehicleRealtime(pollVin);
                if (vehicleInfo != null) {
                    updateFromVehicleInfo(vehicleInfo);
                    logger.info("REST realtime poll: data updated");
                }
            } catch (Exception e) {
                logger.warn("REST realtime poll failed: " + e.getMessage());
            }
        }, 0, POLL_INTERVAL_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    private void stopRealtimePoller() {
        if (realtimePoller != null) {
            realtimePoller.shutdownNow();
            realtimePoller = null;
            logger.info("REST realtime poller stopped");
        }
    }

    private BydCloudClient getOrCreateClient() {
        // Reuse the subscriber's client if available
        if (subscriber != null && subscriber.isConnected()) {
            // The subscriber has a working client — but it's private.
            // Create a fresh one for polling.
        }

        try {
            BydCloudConfig config = BydCloudConfig.fromUnifiedConfig();
            if (!config.isVerified()) return null;

            BydCloudClient client = new BydCloudClient(config);
            java.io.InputStream tables = loadBangcleTables();
            if (tables == null) return null;
            try { client.init(tables); } finally { try { tables.close(); } catch (Exception ignored) {} }
            return client;
        } catch (Exception e) {
            logger.warn("Failed to create client for polling: " + e.getMessage());
            return null;
        }
    }

    private java.io.InputStream loadBangcleTables() {
        String path = "/data/local/tmp/bangcle_tables.bin";
        try {
            java.io.File f = new java.io.File(path);
            if (f.exists() && f.length() > 0) {
                return new java.io.FileInputStream(f);
            }
        } catch (Exception ignored) {}

        try {
            android.content.Context ctx = com.overdrive.app.daemon.DaemonBootstrap.getContext();
            if (ctx != null) {
                return ctx.getAssets().open("byd/bangcle_tables.bin");
            }
        } catch (Exception ignored) {}

        return null;
    }

    // ── MQTT connection state (set by subscriber) ───────────────────────

    public void setMqttConnected(boolean connected) {
        this.mqttConnected = connected;
    }

    public boolean isMqttConnected() {
        return mqttConnected;
    }

    public long getTotalMessagesReceived() {
        return totalMessagesReceived;
    }

    public long getLastMessageReceivedAt() {
        return lastMessageReceivedAt;
    }

    /**
     * Build a status JSON for the API response.
     */
    public JSONObject getStatusJson() {
        JSONObject status = new JSONObject();
        try {
            VehicleCloudSnapshot s = snapshot.get();
            boolean hasData = lastMessageReceivedAt > 0;
            boolean dataFresh = hasData && (System.currentTimeMillis() - lastMessageReceivedAt) < VehicleCloudSnapshot.TELEMETRY_MAX_AGE_MS;

            status.put("connected", mqttConnected || (realtimePoller != null));
            status.put("mqttConnected", mqttConnected);
            status.put("pollingActive", realtimePoller != null);
            status.put("totalMessages", totalMessagesReceived);

            if (hasData) {
                long ageSec = (System.currentTimeMillis() - lastMessageReceivedAt) / 1000;
                status.put("lastMessageAge", ageSec);
            } else {
                status.put("lastMessageAge", -1);
            }

            if (s != null && s.hasValidLockState()) {
                status.put("lockState", s.isAllLocked() ? "locked"
                        : s.isAnyUnlocked() ? "unlocked" : "unknown");
            } else {
                status.put("lockState", "unknown");
            }

            if (s != null) {
                status.put("onlineState", s.onlineState == VehicleCloudSnapshot.ONLINE ? "online"
                        : s.onlineState == VehicleCloudSnapshot.OFFLINE ? "offline" : "unknown");
                if (s.hasSoc()) status.put("socPercent", s.socPercent);
                if (s.hasChargingState()) {
                    switch (s.chargingState) {
                        case 0: status.put("chargingState", "not_charging"); break;
                        case 1: status.put("chargingState", "charging"); break;
                        case 15: status.put("chargingState", "not_charging"); break; // 15 is unreliable — does not reflect actual plug state
                        default: break; // don't report unknown states
                    }
                }
                if (s.hasElecRange()) status.put("rangeKm", s.elecRangeKm);
                if (s.hasInsideTemp()) status.put("insideTempC", s.insideTempC);
            }

            BydCloudConfig config = BydCloudConfig.fromUnifiedConfig();
            status.put("cloudDataMerge", config.cloudDataMerge);
        } catch (Exception ignored) {}
        return status;
    }
}
