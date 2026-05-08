package com.overdrive.app.camera;

import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.monitor.AccMonitor;

/**
 * AVC HAL Warmup — ensures the BYD camera HAL is initialized by com.byd.avc
 * BEFORE our daemon opens the camera.
 *
 * PROBLEM: When ACC turns ON, both our daemon and the native DVR (com.byd.cdr)
 * race to open the panoramic camera. If our daemon opens first, the HAL enters
 * a state where the native DVR can't attach its surface → "no video signal."
 *
 * SOLUTION:
 * 1. Launch com.byd.avc silently (the camera HAL initializer, NOT the DVR)
 * 2. Wait 4 seconds for the HAL to fully initialize in multi-consumer mode
 * 3. THEN open our camera as a secondary consumer
 *
 * Additionally, a 60-second keep-alive watchdog re-pokes com.byd.avc while
 * ACC is ON and the pipeline is running. BYD's system can kill the camera app
 * after inactivity, which destabilizes the HAL for all consumers.
 *
 * LIFECYCLE:
 * - start() when pipeline starts AND ACC is ON
 * - stop() when pipeline stops OR ACC goes OFF OR daemon shuts down
 */
public class AvcHalWarmup {

    private static final String TAG = "AvcHalWarmup";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    /** Time to wait after launching com.byd.avc before opening our camera. */
    private static final long HAL_WARMUP_DELAY_MS = 4000;

    /** Interval for keep-alive pokes to prevent system from killing com.byd.avc. */
    private static final long KEEP_ALIVE_INTERVAL_MS = 60_000;

    /** The am start command to silently launch com.byd.avc without bringing it to foreground. */
    private static final String[] AVC_LAUNCH_CMD = new String[]{
        "am", "start",
        "--user", "0",
        "-n", "com.byd.avc/.MainActivity",
        "-f", "0x10020000"  // FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_NO_ANIMATION
    };

    private volatile Thread keepAliveThread;
    private volatile boolean active = false;

    public AvcHalWarmup() {
    }

    // ==================== One-Shot Warmup ====================

    /**
     * Launches com.byd.avc and blocks for HAL_WARMUP_DELAY_MS.
     * Call this BEFORE opening the camera on ACC ON transitions.
     *
     * This is a blocking call — run it on a background thread.
     *
     * @return true if warmup completed, false if interrupted
     */
    public boolean warmupAndWait() {
        logger.info("Warming up camera HAL via com.byd.avc (waiting " +
            HAL_WARMUP_DELAY_MS + "ms)...");

        launchAvc();

        try {
            Thread.sleep(HAL_WARMUP_DELAY_MS);
            logger.info("HAL warmup complete — safe to open camera");
            return true;
        } catch (InterruptedException e) {
            logger.warn("HAL warmup interrupted");
            Thread.currentThread().interrupt();
            return false;
        }
    }

    // ==================== Keep-Alive Watchdog ====================

    /**
     * Starts the 60-second keep-alive watchdog.
     * Periodically re-launches com.byd.avc to prevent the system from killing it.
     *
     * Only runs while ACC is ON and pipeline is active.
     * Call this after the pipeline has started successfully.
     */
    public synchronized void startKeepAlive() {
        if (active) {
            logger.info("Keep-alive already running");
            return;
        }

        active = true;
        keepAliveThread = new Thread(() -> {
            logger.info("AVC keep-alive watchdog started (interval=" +
                KEEP_ALIVE_INTERVAL_MS / 1000 + "s)");

            while (active && !Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(KEEP_ALIVE_INTERVAL_MS);
                } catch (InterruptedException e) {
                    break;
                }

                // Double-check conditions before poking
                if (!active) break;
                if (!AccMonitor.isAccOn()) {
                    logger.info("ACC is OFF — stopping keep-alive");
                    break;
                }

                // Re-poke com.byd.avc to keep the camera HAL alive
                logger.info("Keep-alive: re-launching com.byd.avc");
                launchAvc();
            }

            logger.info("AVC keep-alive watchdog stopped");
        }, "AvcKeepAlive");

        keepAliveThread.setDaemon(true);
        keepAliveThread.start();
    }

    /**
     * Stops the keep-alive watchdog.
     * Call when pipeline stops, ACC goes OFF, or daemon shuts down.
     */
    public synchronized void stopKeepAlive() {
        if (!active) return;

        active = false;
        if (keepAliveThread != null) {
            keepAliveThread.interrupt();
            keepAliveThread = null;
        }
        logger.info("AVC keep-alive stopped");
    }

    /**
     * Whether the keep-alive watchdog is currently running.
     */
    public boolean isActive() {
        return active;
    }

    // ==================== Internal ====================

    /**
     * Silently launches com.byd.avc via am start.
     * Runs as UID 2000 (shell) — has permission to launch activities.
     * Uses FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_NO_ANIMATION to avoid
     * bringing it to the foreground or showing any visual disruption.
     */
    private void launchAvc() {
        try {
            Process process = Runtime.getRuntime().exec(AVC_LAUNCH_CMD);
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                logger.warn("am start com.byd.avc exited with code " + exitCode);
            }
        } catch (Exception e) {
            logger.warn("Failed to launch com.byd.avc: " + e.getMessage());
        }
    }
}
