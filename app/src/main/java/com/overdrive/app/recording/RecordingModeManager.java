package com.overdrive.app.recording;

import android.content.Context;

import com.overdrive.app.config.UnifiedConfigManager;
import com.overdrive.app.daemon.CameraDaemon;
import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.proximity.ProximityGuardController;
import com.overdrive.app.surveillance.GpuSurveillancePipeline;

import org.json.JSONObject;

/**
 * Recording Mode Manager
 * 
 * Coordinates all recording modes with mutual exclusivity.
 * 
 * Modes:
 * - NONE: No recording, pipeline stopped (DEFAULT)
 * - CONTINUOUS: Always recording when ACC ON
 * - DRIVE_MODE: Recording when in driving gears (D/R/S/M), stops in P/N
 * - PROXIMITY_GUARD: Radar-triggered recording in non-P gears (D/R/S/M/N), disabled in P
 * 
 * Features:
 * - Mutual exclusivity enforcement
 * - Proper cleanup when switching modes
 * - Resource management (stops pipeline when NONE)
 * - Gear state awareness for DRIVE_MODE and PROXIMITY_GUARD
 * - ACC state awareness for CONTINUOUS mode
 */
public class RecordingModeManager {
    private static final DaemonLogger logger = DaemonLogger.getInstance("RecordingModeManager");
    
    // Gear constants (from BYDAutoGearboxDevice)
    public static final int GEAR_P = 1;
    public static final int GEAR_R = 2;
    public static final int GEAR_N = 3;
    public static final int GEAR_D = 4;
    public static final int GEAR_M = 5;
    public static final int GEAR_S = 6;
    
    /**
     * Recording modes for ACC ON state.
     */
    public enum Mode {
        NONE,            // No recording - saves resources (DEFAULT)
        CONTINUOUS,      // Always recording when ACC ON
        DRIVE_MODE,      // Recording when driving (not in P gear)
        PROXIMITY_GUARD  // Recording on radar triggers when gear != P
    }
    
    private final Context context;
    private final GpuSurveillancePipeline pipeline;
    private final ProximityGuardController proximityController;
    
    private volatile Mode currentMode = Mode.NONE;  // Default: no recording
    private volatile boolean accIsOn = false;  // Default: ACC OFF — wait for AccSentryDaemon to confirm
    private volatile int currentGear = GEAR_P;  // Default: Park

    // True once we've received at least one real ACC state change IPC (vs. only
    // the constructor's hardware probe). Used to keep the "wasOn=" log field
    // honest: on the first IPC after boot, accIsOn may already be true from the
    // probe, but there was no prior IPC, so reporting "wasOn=true" is misleading.
    private volatile boolean accIpcSeen = false;

    // True if the current mode's pipeline/recording state is actually live.
    // Used to distinguish "ACC is on AND mode is running" from "ACC was set to
    // on but activation failed silently (pipeline init threw, etc.)".
    // Without this, a duplicate-event guard keyed only on accIsOn locks us out
    // of retrying activation on the next ACC ON IPC.
    private volatile boolean modeActive = false;
    
    public RecordingModeManager(Context context, GpuSurveillancePipeline pipeline) {
        this.context = context;
        this.pipeline = pipeline;
        this.proximityController = new ProximityGuardController(context, pipeline);
        
        // Load persisted mode from config
        loadPersistedMode();
        
        logger.info("RecordingModeManager initialized: mode=" + currentMode);
        
        // Sync ACC state from AccMonitor if it's already been set by AccSentryDaemon
        boolean monitorAccState = queryAccStateFromHardware();
        if (monitorAccState) {
            accIsOn = true;
            logger.info("ACC state from hardware: ON");
        }

        // Sync gear from GearMonitor if it has already started polling. Without
        // this the field stays at the GEAR_P default, and DRIVE_MODE /
        // PROXIMITY_GUARD auto-activate below silently no-ops if the daemon
        // restarted while the car was already in a driving gear. (GearMonitor
        // is started later in CameraDaemon init, so on cold start this often
        // returns GEAR_P regardless — that's fine; onGearChanged() will
        // activate the mode when GearMonitor delivers its first real gear.)
        try {
            com.overdrive.app.monitor.GearMonitor gm =
                com.overdrive.app.monitor.GearMonitor.getInstance();
            if (gm.isRunning()) {
                int gearNow = gm.getCurrentGear();
                if (gearNow != currentGear) {
                    logger.info("Constructor gear sync from GearMonitor: "
                        + gearToString(currentGear) + " -> " + gearToString(gearNow));
                    currentGear = gearNow;
                }
            }
        } catch (Exception e) {
            logger.debug("Constructor GearMonitor sync skipped: " + e.getMessage());
        }

        // Activate the loaded mode if conditions are met.
        // CONTINUOUS: activate immediately (accIsOn defaults to true)
        // DRIVE_MODE: activate if in driving gear
        // PROXIMITY_GUARD: activate if gear != P
        // NONE: no action needed
        //
        // CRITICAL: route through activateModeWithWarmup() instead of calling
        // activateMode() directly. After a hard reboot the BYD camera HAL has
        // not been poked by com.byd.avc yet, so opening the camera before
        // warmup leaves it in a wedged state where pipeline.start() fails
        // silently. The result was that CONTINUOUS recording never started
        // until the user cycled ACC OFF → ON (the IPC path runs warmup).
        if (currentMode == Mode.CONTINUOUS && accIsOn) {
            logger.info("Auto-activating CONTINUOUS mode on startup");
            activateModeWithWarmup(currentMode, "boot-auto-activate");
        } else if (currentMode == Mode.DRIVE_MODE && isDrivingGear(currentGear) && accIsOn) {
            logger.info("Auto-activating DRIVE_MODE on startup (gear=" + gearToString(currentGear) + ")");
            activateModeWithWarmup(currentMode, "boot-auto-activate");
        } else if (currentMode == Mode.PROXIMITY_GUARD && currentGear != GEAR_P && accIsOn) {
            logger.info("Auto-activating PROXIMITY_GUARD on startup (gear=" + gearToString(currentGear) + ")");
            activateModeWithWarmup(currentMode, "boot-auto-activate");
        }

        // Belt-and-suspenders re-sync. Catches all the cold-start failure
        // modes uniformly: GearMonitor not yet running, AccSentryDaemon hasn't
        // pushed initial state yet, pipeline init still in flight, IPC server
        // not yet listening when AccSentryDaemon tried to push, etc. Runs once
        // a few seconds after construction; idempotent if mode is already
        // active (modeActive guard in onAccStateChanged + onGearChanged).
        scheduleColdStartResync();
    }

    private void scheduleColdStartResync() {
        new Thread(() -> {
            try {
                Thread.sleep(COLD_START_RESYNC_DELAY_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
            try {
                resyncFromHardware("cold-start");
            } catch (Exception e) {
                logger.warn("Cold-start re-sync error: " + e.getMessage());
            }
        }, "RecordingModeResync").start();
    }

    /**
     * Re-query authoritative ACC + gear from hardware/monitors and re-drive
     * mode activation if state has drifted from what we currently believe.
     * Used both for cold-start re-sync and for any later resync hook.
     */
    public synchronized void resyncFromHardware(String reason) {
        boolean hwAcc = queryAccStateFromHardware();
        int hwGear = currentGear;
        try {
            com.overdrive.app.monitor.GearMonitor gm =
                com.overdrive.app.monitor.GearMonitor.getInstance();
            if (gm.isRunning()) {
                hwGear = gm.getCurrentGear();
            }
        } catch (Exception ignored) {
            // GearMonitor unavailable — keep our current value
        }

        boolean accChanged = hwAcc != accIsOn;
        boolean gearChanged = hwGear != currentGear;
        logger.info("Re-sync (" + reason + "): hwAcc=" + hwAcc + " accIsOn=" + accIsOn
            + ", hwGear=" + gearToString(hwGear) + " currentGear=" + gearToString(currentGear)
            + ", mode=" + currentMode + ", modeActive=" + modeActive);

        if (gearChanged) {
            // Route through the public handler so existing
            // activate/deactivate logic and modeActive bookkeeping run.
            onGearChanged(hwGear);
        }
        if (accChanged) {
            onAccStateChanged(hwAcc);
            return;
        }

        // ACC state unchanged but mode might have failed to start at construction.
        // Retry activation if conditions are met and modeActive is false. Use
        // the warmup path so a retried activation that follows a failed
        // cold-start (camera HAL wedged, pipeline.start() returned with
        // isRunning()==false) actually pokes com.byd.avc this time around.
        if (accIsOn && !modeActive) {
            if (currentMode == Mode.CONTINUOUS) {
                logger.info("Re-sync retry: activating CONTINUOUS");
                activateModeWithWarmup(currentMode, "resync-retry");
            } else if (currentMode == Mode.DRIVE_MODE && isDrivingGear(currentGear)) {
                logger.info("Re-sync retry: activating DRIVE_MODE (gear=" + gearToString(currentGear) + ")");
                activateModeWithWarmup(currentMode, "resync-retry");
            } else if (currentMode == Mode.PROXIMITY_GUARD && currentGear != GEAR_P) {
                logger.info("Re-sync retry: activating PROXIMITY_GUARD (gear=" + gearToString(currentGear) + ")");
                activateModeWithWarmup(currentMode, "resync-retry");
            }
        }
    }

    /**
     * Run the AVC HAL warmup on a background thread and then call
     * activateMode(mode) under the manager lock. Mirrors the warmup-then-
     * activate path used by onAccStateChanged() so cold-start auto-activation
     * doesn't race with com.byd.avc's HAL initialization.
     *
     * Skips warmup if the pipeline is already running (camera is open, no
     * need to poke com.byd.avc) — same heuristic the IPC path uses.
     */
    private void activateModeWithWarmup(final Mode mode, final String reason) {
        if (mode == Mode.NONE) {
            return;
        }
        new Thread(() -> {
            // Only warmup if pipeline isn't already running.
            if (!pipeline.isRunning()) {
                if (!CameraDaemon.ensureAvcWarmupStarted("RecordingModeManager.activateModeWithWarmup:" + reason)) {
                    logger.warn("AVC warmup interrupted (" + reason + ") — skipping mode activation");
                    return;
                }
            }
            synchronized (RecordingModeManager.this) {
                if (!accIsOn) {
                    logger.info("ACC turned OFF during warmup (" + reason + ") — skipping mode activation");
                    return;
                }
                int gearNow = currentGear;
                // Re-check gear gates against the live value, in case it changed
                // during the 4s warmup sleep.
                if (mode == Mode.DRIVE_MODE && !isDrivingGear(gearNow)) {
                    logger.info("DRIVE_MODE waiting for driving gear (current="
                        + gearToString(gearNow) + ") — " + reason);
                    return;
                }
                if (mode == Mode.PROXIMITY_GUARD && gearNow == GEAR_P) {
                    logger.info("PROXIMITY_GUARD waiting for gear != P — " + reason);
                    return;
                }
                if (modeActive && pipeline.isRunning() && pipeline.isNormalRecordingMode()) {
                    logger.info("Mode " + mode + " already active — skipping re-activation ("
                        + reason + ")");
                    return;
                }
                activateMode(mode);
            }
        }, "ModeWarmup-" + reason).start();
    }

    // 8s gives the constructor's warmup-then-activate (≈4s warmup + ~2s pipeline
    // init) time to finish before the resync second-guesses it. With the prior
    // 5s value the resync would frequently fire while warmup was still sleeping,
    // see modeActive=false, and queue a redundant retry.
    private static final long COLD_START_RESYNC_DELAY_MS = 8_000L;
    
    /**
     * Set recording mode.
     * Enforces mutual exclusivity by deactivating current mode before activating new.
     */
    public synchronized void setMode(Mode mode) {
        if (mode == currentMode) {
            logger.debug("Mode already set to: " + mode);
            return;
        }
        
        logger.info("Changing recording mode: " + currentMode + " -> " + mode);
        
        // Sync ACC state — query hardware directly for authoritative state
        boolean actualAccState = queryAccStateFromHardware();
        if (actualAccState != accIsOn) {
            logger.info("Syncing ACC state: " + accIsOn + " -> " + actualAccState);
            accIsOn = actualAccState;
        }
        
        // Sync gear state from GearMonitor (authoritative source)
        try {
            com.overdrive.app.monitor.GearMonitor gearMonitor = com.overdrive.app.monitor.GearMonitor.getInstance();
            if (gearMonitor.isRunning()) {
                int actualGear = gearMonitor.getCurrentGear();
                if (actualGear != currentGear) {
                    logger.info("Syncing gear from GearMonitor: " + gearToString(currentGear) + " -> " + gearToString(actualGear));
                    currentGear = actualGear;
                }
            }
        } catch (Exception e) {
            logger.warn("Could not sync gear: " + e.getMessage());
        }
        
        // Deactivate current mode
        deactivateMode(currentMode);
        
        // Update current mode
        Mode oldMode = currentMode;
        currentMode = mode;
        
        // Persist mode to config EARLY — before activation which might fail
        persistMode(mode);
        
        // Activate new mode based on appropriate trigger
        if (mode == Mode.DRIVE_MODE) {
            // DRIVE_MODE activates when in driving gears (D/R/S/M)
            if (isDrivingGear(currentGear)) {
                activateMode(mode);
            } else {
                logger.info("Gear is " + gearToString(currentGear) + " - DRIVE_MODE will activate when in D/R/S/M");
            }
        } else if (mode == Mode.PROXIMITY_GUARD) {
            // PROXIMITY_GUARD activates in all gears except P
            if (currentGear != GEAR_P) {
                activateMode(mode);
            } else {
                logger.info("Gear is P - PROXIMITY_GUARD will activate when gear changes");
            }
        } else if (accIsOn) {
            // CONTINUOUS and NONE activate when ACC is ON
            activateMode(mode);
        } else {
            logger.info("ACC is OFF - mode will activate when ACC turns ON");
        }
        
        logger.info("Recording mode changed: " + oldMode + " -> " + mode);
    }
    
    /**
     * Get current recording mode.
     */
    public Mode getCurrentMode() {
        return currentMode;
    }
    
    /**
     * Notify of ACC state change.
     * Activates/deactivates modes that depend on ACC state.
     */
    public synchronized void onAccStateChanged(boolean isOn) {
        // wasOn reflects "was ACC observed ON via a *prior IPC*?" — not the
        // hardware probe value seeded in the constructor. Without this guard,
        // the very first ACC IPC after boot logs "wasOn=true" (because the
        // probe set it) and triggers the "retrying activation" path, which is
        // misleading: there was no prior activation to retry.
        boolean wasOn = accIpcSeen && accIsOn;
        boolean firstIpc = !accIpcSeen;
        accIpcSeen = true;

        logger.info("ACC state changed: " + (isOn ? "ON" : "OFF") + " (mode=" + currentMode
            + ", wasOn=" + wasOn + (firstIpc ? " [first IPC after boot]" : "")
            + ", modeActive=" + modeActive + ")");

        accIsOn = isOn;

        if (isOn) {
            // Suppress only if the mode is genuinely already running. Keying
            // the guard purely on `wasOn` was wrong: if a prior activation
            // failed silently (constructor auto-activate threw, pipeline
            // wasn't ready, etc.), accIsOn was still true and the next real
            // ACC ON IPC was discarded as a "duplicate," leaving the user
            // with no recording until the next ACC OFF/ON cycle.
            if (wasOn && modeActive) {
                logger.debug("ACC already ON and mode active, ignoring duplicate notification");
                return;
            }
            if (wasOn && !modeActive) {
                logger.info("ACC was already ON but mode not active — retrying activation");
            }
            
            // ACC is ON — if pipeline is already running, keep it running.
            // No need to stop and restart — the camera is already open and the
            // mode will continue using it. Stopping causes a HAL teardown
            // (stopPreview + close) that disrupts the native DVR.
            // If surveillance was active, CameraDaemon.onAccStateChanged handles
            // disabling it separately.
            
            // Start AVC keep-alive and activate mode after warmup
            final Mode modeToActivate = currentMode;
        new Thread(() -> {
            // Only warmup if pipeline isn't already running
            // (if it's running, camera is already open — no need to poke com.byd.avc)
            if (!pipeline.isRunning()) {
                if (!CameraDaemon.ensureAvcWarmupStarted("RecordingModeManager.onAccStateChanged")) {
                    logger.warn("AVC warmup interrupted — skipping mode activation");
                    return;
                }
            }
                
                synchronized (RecordingModeManager.this) {
                    if (!accIsOn) {
                        logger.info("ACC turned OFF during reacquire delay — skipping mode activation");
                        return;
                    }
                    
                    // Use CURRENT gear, not the gear at ACC ON time — gear may have changed
                    // during the delay (e.g., P→D or D→P)
                    int gearNow = currentGear;
                    
                    if (modeToActivate == Mode.DRIVE_MODE && !isDrivingGear(gearNow)) {
                        logger.info("DRIVE_MODE waiting for driving gear (current=" + gearToString(gearNow) + ")");
                    } else if (modeToActivate == Mode.PROXIMITY_GUARD && gearNow == GEAR_P) {
                        logger.info("PROXIMITY_GUARD waiting for gear != P");
                    } else if (modeToActivate != Mode.NONE) {
                        // Skip only if the desired mode's recording is genuinely
                        // already running. Previously this checked
                        // pipeline.isRecording() alone, which returns true for
                        // SURVEILLANCE recordings still finalizing at the moment
                        // of ACC ON — causing CONTINUOUS/DRIVE_MODE to be skipped
                        // and never started until the next state transition.
                        if (modeActive && pipeline.isRunning() && pipeline.isNormalRecordingMode()) {
                            logger.info("Mode " + modeToActivate + " already active — skipping re-activation");
                        } else {
                            activateMode(modeToActivate);
                        }
                    }
                }
            }, "AccOnReacquire").start();
            
        } else if (!isOn) {
            // ACC turned OFF — always stop the pipeline regardless of mode.
            // Recording modes only operate when ACC is ON. Surveillance (if enabled)
            // will be started separately by CameraDaemon.onAccStateChanged.
            // pipeline.isRunning() guards against duplicate OFF events doing
            // unnecessary teardown work.
            CameraDaemon.stopAvcKeepAlive();
            if (pipeline.isRunning()) {
                pipeline.stopRecording();
                pipeline.stop();
            }
        }
    }
    
    /**
     * Notify of gear state change.
     * - DRIVE_MODE: activates on D/R/S/M, deactivates on P/N
     * - PROXIMITY_GUARD: activates on D/R/S/M/N, deactivates on P
     * 
     * @param gear The new gear position (GEAR_P, GEAR_R, GEAR_N, GEAR_D, etc.)
     */
    public synchronized void onGearChanged(int gear) {
        // Suppress no-op notifications — GearMonitor.start() calls onGearChanged()
        // once with the initial gear so the rest of the system gets primed, but
        // for RecordingModeManager that often matches the constructor default
        // and there's nothing to do. Logging it as a "P -> P" change is just noise.
        if (gear == currentGear) {
            return;
        }

        String gearName = gearToString(gear);
        logger.info("Gear changed: " + gearToString(currentGear) + " -> " + gearName + " (mode=" + currentMode + ")");

        int previousGear = currentGear;
        currentGear = gear;

        // Only DRIVE_MODE and PROXIMITY_GUARD respond to gear changes
        if (currentMode != Mode.DRIVE_MODE && currentMode != Mode.PROXIMITY_GUARD) {
            logger.debug("Mode " + currentMode + " does not respond to gear changes");
            return;
        }
        
        if (currentMode == Mode.DRIVE_MODE) {
            // DRIVE_MODE: record when driving (D/R/S/M) AND ACC is ON
            boolean wasDriving = isDrivingGear(previousGear);
            boolean nowDriving = isDrivingGear(gear);

            // Use modeActive (not just gear edge) so cold-start — where
            // GearMonitor's first real reading arrives after construction with
            // currentGear default GEAR_P — also activates DRIVE_MODE on the
            // first delivered driving gear, even though the "edge" condition
            // (wasDriving=false → nowDriving=true) only fires once.
            if (nowDriving && accIsOn && !modeActive) {
                logger.info("Driving gear with mode not yet active - activating DRIVE_MODE recording");
                // Route through warmup. If the user shifts D within the 4s
                // AVC warmup window after ACC ON, calling activateMode()
                // directly would open the camera before com.byd.avc finished
                // initializing the HAL → wedged camera, no recording. The
                // warmup helper short-circuits when the pipeline is already
                // running, so it's a no-op cost when not needed.
                activateModeWithWarmup(Mode.DRIVE_MODE, "gear-to-driving");
            } else if (!nowDriving && (wasDriving || modeActive)) {
                logger.info("Shifted to parked gear - deactivating DRIVE_MODE recording");
                deactivateMode(Mode.DRIVE_MODE);
            } else if (nowDriving && !accIsOn) {
                logger.info("Driving gear but ACC OFF - DRIVE_MODE will activate when ACC turns ON");
            }
        } else if (currentMode == Mode.PROXIMITY_GUARD) {
            // PROXIMITY_GUARD: active in all gears except P, only when ACC is ON
            boolean wasInP = (previousGear == GEAR_P);
            boolean nowInP = (gear == GEAR_P);

            if (!nowInP && accIsOn && !modeActive) {
                logger.info("Out of P with mode not yet active - activating PROXIMITY_GUARD");
                // Same rationale as DRIVE_MODE above — protect against the
                // user shifting out of P before the AVC HAL is warmed up.
                activateModeWithWarmup(Mode.PROXIMITY_GUARD, "gear-out-of-P");
            } else if (nowInP && (!wasInP || modeActive)) {
                logger.info("Shifted to P - deactivating PROXIMITY_GUARD");
                deactivateMode(Mode.PROXIMITY_GUARD);
            } else if (!nowInP && !accIsOn) {
                logger.info("Not in P but ACC OFF - PROXIMITY_GUARD will activate when ACC turns ON");
            }
        }
    }
    
    /**
     * Check if ACC is ON.
     */
    public boolean isAccOn() {
        return accIsOn;
    }
    
    /**
     * Get current gear position.
     */
    public int getCurrentGear() {
        return currentGear;
    }
    
    /**
     * Check if gear is a driving gear (D/R/S/M/N).
     * N is included because BYD Auto Hold reports N while the car is stopped at a
     * traffic light with the driver's foot off the brake. Excluding N would cause
     * DRIVE_MODE recording to stop/start on every Auto Hold engage/release cycle.
     * This matches TripDetector.isDrivingGear which also includes N.
     */
    public static boolean isDrivingGear(int gear) {
        return gear == GEAR_D || gear == GEAR_R || gear == GEAR_N || gear == GEAR_S || gear == GEAR_M;
    }
    
    /**
     * Check if gear is a parked gear (P only).
     * N is NOT parked — see isDrivingGear comment about Auto Hold.
     */
    public static boolean isParkedGear(int gear) {
        return gear == GEAR_P;
    }
    
    /**
     * Convert gear constant to string.
     */
    public static String gearToString(int gear) {
        switch (gear) {
            case GEAR_P: return "P";
            case GEAR_R: return "R";
            case GEAR_N: return "N";
            case GEAR_D: return "D";
            case GEAR_M: return "M";
            case GEAR_S: return "S";
            default: return "UNKNOWN(" + gear + ")";
        }
    }
    
    // ==================== MODE ACTIVATION ====================
    
    private void activateMode(Mode mode) {
        logger.info("Activating mode: " + mode);

        // SOTA: Stop any manual recording before activating a mode
        // This ensures mode-managed recording takes precedence over manual recording
        if (pipeline.isNormalRecordingMode()) {
            logger.info("Stopping manual recording before activating mode: " + mode);
            pipeline.stopRecording();
        }

        // If user changed cameraFps in config since the encoder was built, force
        // a clean stop here so the per-mode start() below runs through init() and
        // picks up the new FPS via loadTargetFps(). Without this, FPS changes
        // applied while the pipeline stayed alive across ACC OFF (sentry mode)
        // wouldn't reach the encoder until the next full app restart.
        //
        // Safe at this exact moment: ACC has just turned ON, surveillance was
        // already disabled by CameraDaemon.onAccOn() before this thread runs,
        // and CONTINUOUS/DRIVE_MODE recording hasn't started yet — there is no
        // active recording state to lose.
        if (mode != Mode.NONE && pipeline.isFpsConfigStale()) {
            logger.info("Camera FPS config changed — restarting pipeline to apply");
            pipeline.stop();
        }

        switch (mode) {
            case NONE:
                // Stop pipeline to save resources
                if (pipeline.isRunning()) {
                    logger.info("Stopping pipeline for NONE mode (resource saving)");
                    pipeline.stop();
                    CameraDaemon.stopAvcKeepAlive();
                }
                modeActive = false;
                break;
                
            case CONTINUOUS:
                // Start pipeline and recording
                try {
                    if (!pipeline.isRunning()) {
                        logger.info("Starting pipeline for CONTINUOUS mode");
                        pipeline.start(false);
                    }
                    // Pipeline.start() blocks ~2s for GL init. Recorder should be ready.
                    if (pipeline.isRunning() && !pipeline.isRecording()) {
                        pipeline.startRecording();
                    }
                    // Start AVC keep-alive (pipeline is now running with ACC ON)
                    CameraDaemon.startAvcKeepAliveIfNeeded();
                    modeActive = pipeline.isRunning();
                } catch (Exception e) {
                    logger.error("Failed to start CONTINUOUS mode: " + e.getMessage());
                    modeActive = false;
                }
                break;
                
            case DRIVE_MODE:
                // Start recording when driving (gear is D/R/S/M)
                try {
                    if (!pipeline.isRunning()) {
                        logger.info("Starting pipeline for DRIVE_MODE");
                        pipeline.start(false);
                    }
                    // Pipeline.start() blocks ~2s for GL init. Recorder should be ready.
                    if (pipeline.isRunning() && !pipeline.isRecording()) {
                        logger.info("Starting DRIVE_MODE recording");
                        pipeline.startRecording();
                    }
                    // Start AVC keep-alive (pipeline is now running with ACC ON)
                    CameraDaemon.startAvcKeepAliveIfNeeded();
                    modeActive = pipeline.isRunning();
                } catch (Exception e) {
                    logger.error("Failed to start DRIVE_MODE: " + e.getMessage());
                    modeActive = false;
                }
                break;

            case PROXIMITY_GUARD:
                // Start pipeline (without recording) and proximity controller
                try {
                    if (!pipeline.isRunning()) {
                        logger.info("Starting pipeline for PROXIMITY_GUARD mode");
                        pipeline.start(false);  // Don't auto-start recording
                    }
                    proximityController.start();
                    // Start AVC keep-alive (pipeline is now running with ACC ON)
                    CameraDaemon.startAvcKeepAliveIfNeeded();
                    modeActive = pipeline.isRunning();
                } catch (Exception e) {
                    logger.error("Failed to start PROXIMITY_GUARD mode: " + e.getMessage());
                    modeActive = false;
                }
                break;
        }
    }
    
    private void deactivateMode(Mode mode) {
        logger.info("Deactivating mode: " + mode);

        // Whatever was active is no longer active. Set this up front so the
        // duplicate-event guard in onAccStateChanged() will allow re-activation.
        modeActive = false;

        // Check if surveillance should be preserved — don't stop pipeline during ACC OFF
        // (surveillance/sentry mode needs the pipeline running)
        boolean keepPipelineRunning = !accIsOn;
        
        if (keepPipelineRunning) {
            logger.info("ACC is OFF — keeping pipeline running for surveillance");
        }
        
        switch (mode) {
            case NONE:
                // Already stopped
                break;
                
            case CONTINUOUS:
                // Stop recording but keep pipeline if ACC is OFF (surveillance running)
                pipeline.stopRecording();
                if (pipeline.isRunning() && !keepPipelineRunning) {
                    pipeline.stop();
                    CameraDaemon.stopAvcKeepAlive();
                }
                break;
                
            case DRIVE_MODE:
                // Stop recording only — keep pipeline alive for quick resume on next gear change.
                // Full pipeline teardown (camera/EGL/encoder release) makes restart unreliable
                // and slow. Only stop the pipeline on full ACC OFF (handled by onAccStateChanged).
                pipeline.stopRecording();
                break;
                
            case PROXIMITY_GUARD:
                // Stop proximity controller but keep pipeline if ACC is OFF (surveillance running)
                proximityController.stop();
                if (pipeline.isRunning() && !keepPipelineRunning) {
                    pipeline.stop();
                    CameraDaemon.stopAvcKeepAlive();
                }
                break;
        }
    }
    
    // ==================== CONFIG PERSISTENCE ====================
    
    /**
     * Query ACC state directly from BYD hardware.
     * Falls back to AccMonitor if hardware query fails.
     */
    private boolean queryAccStateFromHardware() {
        // Try direct hardware query via BYDAutoBodyworkDevice
        try {
            Class<?> deviceClass = Class.forName("android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice");
            java.lang.reflect.Method getInstance = deviceClass.getMethod("getInstance", android.content.Context.class);
            Object device = getInstance.invoke(null, context);
            if (device != null) {
                java.lang.reflect.Method getPowerLevel = deviceClass.getMethod("getPowerLevel");
                int level = (Integer) getPowerLevel.invoke(device);
                // Power levels: 0=OFF, 1=ACC, 2=ON, 3=START
                boolean isOn = level >= 2;
                logger.debug("Hardware power level: " + level + " (ACC " + (isOn ? "ON" : "OFF") + ")");
                return isOn;
            }
        } catch (Exception e) {
            logger.debug("Hardware ACC query failed: " + e.getMessage());
        }
        
        // Fallback to AccMonitor
        return com.overdrive.app.monitor.AccMonitor.isAccOn();
    }

    private void loadPersistedMode() {
        try {
            JSONObject recording = UnifiedConfigManager.getRecording();
            String modeStr = recording.optString("mode", "NONE");
            
            try {
                currentMode = Mode.valueOf(modeStr.toUpperCase());
                logger.info("Loaded persisted mode: " + currentMode);
            } catch (IllegalArgumentException e) {
                logger.warn("Invalid persisted mode: " + modeStr + ", using NONE");
                currentMode = Mode.NONE;
            }
        } catch (Exception e) {
            logger.error("Failed to load persisted mode: " + e.getMessage());
            currentMode = Mode.NONE;
        }
    }
    
    private void persistMode(Mode mode) {
        try {
            JSONObject recording = UnifiedConfigManager.getRecording();
            recording.put("mode", mode.name());
            UnifiedConfigManager.setRecording(recording);
            logger.debug("Persisted mode: " + mode);
        } catch (Exception e) {
            logger.error("Failed to persist mode: " + e.getMessage());
        }
    }
    
    /**
     * Reload configuration (call when config changes).
     */
    public synchronized void reloadConfig() {
        loadPersistedMode();
        if (proximityController != null) {
            proximityController.reloadConfig();
        }
        logger.info("Config reloaded: mode=" + currentMode);
    }
    
    /**
     * Shutdown and cleanup resources.
     */
    public void shutdown() {
        logger.info("Shutting down RecordingModeManager...");
        CameraDaemon.stopAvcKeepAlive();
        deactivateMode(currentMode);
        if (proximityController != null) {
            proximityController.shutdown();
        }
        logger.info("RecordingModeManager shutdown complete");
    }
}
