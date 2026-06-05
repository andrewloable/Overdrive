package com.overdrive.app.notifications;

import com.overdrive.app.byd.BydDataCollector;
import com.overdrive.app.byd.BydVehicleData;
import com.overdrive.app.monitor.ChargingStateData;
import com.overdrive.app.server.Messages;

import org.json.JSONObject;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Publishes vehicle.charging.* notifications:
 * <ul>
 *   <li>{@code vehicle.charging.started} / {@code .stopped} — driven directly
 *       by {@link com.overdrive.app.monitor.ChargingDetector} fused-state
 *       edges. The detector already fuses BMS + Power.isCharging() + L3
 *       inference + plug edges with hysteresis (30s plug bias, 10s L1↔L2
 *       disagreement, 15s unplug override, 3-sample L3), so re-debouncing
 *       here is redundant and was the cause of silently-dropped sessions.</li>
 *   <li>{@code vehicle.charging.full} — once per session when SOC crosses
 *       {@link #FULL_SOC_THRESHOLD} (or plateaus near the top) while a
 *       session is active. Suppressed when the session began at or above
 *       the threshold (plugged-in-already-full).</li>
 *   <li>{@code vehicle.charging.fault} — every distinct breakdown transition
 *       on the BMS edge stream. Independent of session bookkeeping so a
 *       breakdown is always announced even if no session was active.</li>
 * </ul>
 *
 * <p>This notifier is purely a downstream consumer — it never mutates
 * {@code chargingState} or {@code chargingPowerKw}. ABRP, MQTT, and the
 * SOC-history graph all read the snapshot directly via {@code getData()}
 * and are unaffected by this code path.
 */
public final class ChargingEventNotifier {

    /**
     * Threshold for "full" notification. BYD's BMS reports SOC as a whole
     * integer, so any fractional threshold like 99.5 is unreachable below
     * 100. 99 is the earliest reachable signal that the pack is effectively
     * full and the user can unplug.
     */
    private static final double FULL_SOC_THRESHOLD = 99.0;

    /**
     * Plateau-based completion: if SOC hits this floor, rises substantially
     * from start, and then stays flat for {@link #PLATEAU_HOLD_MS}, treat
     * that as full. Catches the BYD pattern of plateauing at 99 during
     * balancing without ever quite hitting 100 before the user unplugs.
     */
    private static final double PLATEAU_SOC_FLOOR = 98.0;
    private static final long PLATEAU_HOLD_MS = 90_000L;

    /** Minimum SOC rise to call a plateau "complete" (filters short top-ups). */
    private static final double MIN_SOC_RISE_FOR_PLATEAU = 5.0;

    /** SOC polling cadence while a session is active. */
    private static final long SOC_POLL_INTERVAL_MS = 10_000L;

    private static volatile ChargingEventNotifier instance;

    private final com.overdrive.app.monitor.ChargingDetector.FusedStateListener fusedListener =
            (isCharging, source) -> onFusedEdge(isCharging, source);

    /**
     * Faults come from the raw BMS edge stream, independent of session
     * bookkeeping. A breakdown reported while we never opened a session
     * still warrants a notification.
     */
    private final BydDataCollector.ChargingStateListener faultListener =
            (prev, now) -> {
                if (statusOf(now) == ChargingStateData.ChargingStatus.ERROR) {
                    publishFault(now);
                }
            };

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "ChargingEventNotifier");
                t.setDaemon(true);
                return t;
            });

    private volatile boolean sessionActive = false;
    private volatile ScheduledFuture<?> socPoller;
    private volatile boolean fullFiredThisSession = false;
    private volatile double sessionStartSoc = Double.NaN;
    private volatile double sessionMaxSoc = Double.NaN;
    private volatile long plateauStartedAtMs = 0L;

    private ChargingEventNotifier() {}

    public static synchronized void start() {
        if (instance != null) return;
        ChargingEventNotifier n = new ChargingEventNotifier();
        // Single source of truth for session edges. Detector is already
        // fused and debounced; trust its verdict directly.
        com.overdrive.app.monitor.ChargingDetector detector =
                com.overdrive.app.monitor.ChargingDetector.getInstance();
        detector.addFusedStateListener(n.fusedListener);
        // Faults are independent — wire to raw BMS edges.
        BydDataCollector.getInstance().addChargingStateListener(n.faultListener);
        instance = n;

        // Boot-race replay. The detector does not re-emit current state on
        // subscribe; if BydDataCollector.init() has already driven fused
        // state to true (cable plugged at cold boot), the listener above
        // would otherwise miss the start edge entirely and the user would
        // only get a "stopped" later. Synthesise a single self-call so the
        // session opens and the SOC poller starts.
        if (detector.isCharging()) {
            n.onFusedEdge(true, "boot-replay");
        }
    }

    private void onFusedEdge(boolean isCharging, String source) {
        if (isCharging == sessionActive) return;
        sessionActive = isCharging;

        BydVehicleData snap = BydDataCollector.getInstance().getData();

        if (isCharging) {
            sessionStartSoc = (snap != null) ? snap.socPercent : Double.NaN;
            sessionMaxSoc = sessionStartSoc;
            plateauStartedAtMs = 0L;
            fullFiredThisSession = false;
            startSocPoller();
            int stateCode = (snap != null)
                    ? snap.chargingState
                    : ChargingStateData.CHARGING_BATTERY_STATE_CHARGING;
            publishStarted(stateCode);
        } else {
            stopSocPoller();
            int stateCode = (snap != null)
                    ? snap.chargingState
                    : ChargingStateData.CHARGING_BATTERY_STATE_IDLE;
            publishStopped(stateCode);
        }
    }

    private void startSocPoller() {
        stopSocPoller();
        socPoller = scheduler.scheduleWithFixedDelay(
                this::checkSocFull,
                0L, SOC_POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    private void stopSocPoller() {
        ScheduledFuture<?> f = socPoller;
        if (f != null) {
            f.cancel(false);
            socPoller = null;
        }
    }

    private void checkSocFull() {
        if (!sessionActive || fullFiredThisSession) return;
        BydVehicleData snap = BydDataCollector.getInstance().getData();
        if (snap == null) return;
        double soc = snap.socPercent;
        if (!isFinite(soc)) return;

        if (!isFinite(sessionMaxSoc) || soc > sessionMaxSoc) {
            sessionMaxSoc = soc;
        }

        long now = System.currentTimeMillis();
        if (soc >= PLATEAU_SOC_FLOOR) {
            if (plateauStartedAtMs == 0L) plateauStartedAtMs = now;
        } else {
            plateauStartedAtMs = 0L;
        }

        boolean startedFull = isFinite(sessionStartSoc)
                && sessionStartSoc >= FULL_SOC_THRESHOLD;

        if (soc >= FULL_SOC_THRESHOLD) {
            fullFiredThisSession = true;
            if (!startedFull) publishFull(soc);
            return;
        }

        if (plateauStartedAtMs != 0L
                && (now - plateauStartedAtMs) >= PLATEAU_HOLD_MS
                && !startedFull
                && isFinite(sessionStartSoc)
                && (soc - sessionStartSoc) >= MIN_SOC_RISE_FOR_PLATEAU) {
            fullFiredThisSession = true;
            publishFull(soc);
        }
    }

    private void publishStarted(int stateCode) {
        BydVehicleData snap = BydDataCollector.getInstance().getData();
        double powerKw = (snap != null) ? snap.chargingPowerKw : Double.NaN;
        double socPercent = (snap != null) ? snap.socPercent : Double.NaN;

        StringBuilder body = new StringBuilder();
        if (isFinite(powerKw) && Math.abs(powerKw) >= 0.1) {
            body.append(formatKw(powerKw)).append(" kW");
        }
        if (isFinite(socPercent)) {
            if (body.length() > 0) body.append(" • ");
            body.append((int) Math.round(socPercent)).append("%");
        }

        JSONObject data = new JSONObject();
        try {
            data.put("stateCode", stateCode);
            if (isFinite(powerKw)) data.put("powerKw", powerKw);
            if (isFinite(socPercent)) data.put("socPercent", socPercent);
        } catch (Exception ignored) {}

        publish(new NotificationEvent(
                "vehicle.charging.started",
                NotificationEvent.Severity.INFO,
                Messages.get("notifications.charging_started"),
                body.toString(),
                "charging-session",
                null,
                data));
    }

    private void publishStopped(int stateCode) {
        BydVehicleData snap = BydDataCollector.getInstance().getData();
        double socPercent = (snap != null) ? snap.socPercent : Double.NaN;

        String reason = stateLabel(stateCode);
        StringBuilder body = new StringBuilder(reason);
        if (isFinite(socPercent)) {
            body.append(" • ").append((int) Math.round(socPercent)).append("%");
        }

        JSONObject data = new JSONObject();
        try {
            data.put("stateCode", stateCode);
            data.put("stateName", reason);
            if (isFinite(socPercent)) data.put("socPercent", socPercent);
        } catch (Exception ignored) {}

        publish(new NotificationEvent(
                "vehicle.charging.stopped",
                NotificationEvent.Severity.INFO,
                Messages.get("notifications.charging_stopped"),
                body.toString(),
                "charging-session",
                null,
                data));
    }

    private void publishFull(double socPercent) {
        JSONObject data = new JSONObject();
        try {
            data.put("socPercent", socPercent);
            data.put("threshold", FULL_SOC_THRESHOLD);
        } catch (Exception ignored) {}

        publish(new NotificationEvent(
                "vehicle.charging.full",
                NotificationEvent.Severity.WARN,
                Messages.get("notifications.charging_complete"),
                Messages.get("notifications.battery_ready_to_unplug",
                        (int) Math.round(socPercent)),
                "charging-full",
                null,
                data));
    }

    private void publishFault(int stateCode) {
        String label = stateLabel(stateCode);

        JSONObject data = new JSONObject();
        try {
            data.put("stateCode", stateCode);
            data.put("stateName", label);
        } catch (Exception ignored) {}

        publish(new NotificationEvent(
                "vehicle.charging.fault",
                NotificationEvent.Severity.CRITICAL,
                Messages.get("notifications.charging_fault"),
                label,
                "charging-fault",
                null,
                data));
    }

    private static void publish(NotificationEvent event) {
        try { NotificationBus.get().publish(event); } catch (Throwable ignored) {}
    }

    private static ChargingStateData.ChargingStatus statusOf(int stateCode) {
        return new ChargingStateData(stateCode).status;
    }

    private static String stateLabel(int stateCode) {
        return new ChargingStateData(stateCode).stateName;
    }

    private static boolean isFinite(double v) {
        return !Double.isNaN(v) && !Double.isInfinite(v);
    }

    private static String formatKw(double kw) {
        return String.format(java.util.Locale.US, "%.1f", Math.abs(kw));
    }
}
