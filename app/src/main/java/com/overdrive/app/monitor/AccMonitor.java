package com.overdrive.app.monitor;

import com.overdrive.app.daemon.CameraDaemon;

/**
 * ACC Monitor - State holder for ACC status with direct hardware query.
 * 
 * ACC state detection is handled by AccSentryDaemon which:
 * 1. Uses BYDAutoBodyworkDevice listener for real ACC events
 * 2. Falls back to sys.accanim.status polling
 * 3. Sends IPC commands to SurveillanceEngine on port 19877
 * 
 * On CameraDaemon restart (e.g., after EGL crash), the ACC state is read
 * directly from BYDAutoBodyworkDevice.getPowerLevel() so the daemon can
 * re-enter sentry mode without depending on AccSentryDaemon IPC.
 */
public class AccMonitor {

    // Power levels from BYDAutoBodyworkDevice (same as AccSentryDaemon)
    private static final int POWER_LEVEL_OFF = 0;
    private static final int POWER_LEVEL_ACC = 1;
    private static final int POWER_LEVEL_ON = 2;

    private static volatile boolean inSentryMode = false;
    // Default to false (ACC off) - safer assumption until AccSentryDaemon confirms state
    // This prevents false "acc: true" in status when daemon restarts
    private static volatile boolean accOn = false;

    public static boolean isAccOn() {
        return accOn;
    }

    public static boolean isInSentryMode() {
        return inSentryMode;
    }

    /**
     * Called by SurveillanceEngine IPC when AccSentryDaemon sends ACC state.
     */
    public static void setAccState(boolean isAccOn) {
        accOn = isAccOn;
        inSentryMode = !isAccOn;
        CameraDaemon.log("ACC state updated via IPC: accOn=" + isAccOn + ", sentryMode=" + inSentryMode);
    }

    /**
     * Reads ACC state directly from BYDAutoBodyworkDevice hardware.
     * No dependency on AccSentryDaemon or file persistence.
     * 
     * @param context Android context for BYD device API
     * @return true if ACC is OFF (sentry mode should be active), false if ACC is ON or unknown
     */
    public static boolean probeAccState(android.content.Context context) {
        try {
            Class<?> deviceClass = Class.forName("android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice");
            java.lang.reflect.Method getInstance = deviceClass.getMethod("getInstance", android.content.Context.class);
            Object device = getInstance.invoke(null, context);

            if (device == null) {
                CameraDaemon.log("AccMonitor: BYDAutoBodyworkDevice.getInstance returned null");
                return false;
            }

            java.lang.reflect.Method getPowerLevel = deviceClass.getMethod("getPowerLevel");
            int level = (Integer) getPowerLevel.invoke(device);

            boolean isAccOn = level >= POWER_LEVEL_ON;
            accOn = isAccOn;
            inSentryMode = !isAccOn;

            String levelStr;
            switch (level) {
                case 0: levelStr = "OFF"; break;
                case 1: levelStr = "ACC"; break;
                case 2: levelStr = "ON"; break;
                case 3: levelStr = "OK"; break;
                default: levelStr = "UNKNOWN(" + level + ")"; break;
            }
            CameraDaemon.log("AccMonitor: hardware probe powerLevel=" + levelStr +
                " → accOn=" + isAccOn + ", sentryMode=" + inSentryMode);

            return !isAccOn;  // true if ACC is OFF
        } catch (Exception e) {
            CameraDaemon.log("AccMonitor: hardware probe failed: " + e.getMessage());
            return false;  // assume ACC ON (safe default — don't enter sentry on error)
        }
    }

    /**
     * No-op start method for backward compatibility with CameraDaemon.
     */
    public void start() {
        CameraDaemon.log("AccMonitor: passive mode (ACC detection by AccSentryDaemon)");
    }

    /**
     * No-op stop method for backward compatibility.
     */
    public void stop() {
        // Nothing to stop
    }
}
