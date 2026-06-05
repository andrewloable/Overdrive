package com.overdrive.app.proximity;

import android.content.Context;

import com.overdrive.app.config.UnifiedConfigManager;
import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.surveillance.GpuSurveillancePipeline;

import org.json.JSONObject;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Proximity Guard Controller
 * 
 * Core state machine for Proximity Guard recording mode.
 * 
 * State transitions:
 * - IDLE: Mode disabled or ACC OFF
 * - MONITORING: Radar listeners active, waiting for trigger
 * - RECORDING: Active recording in progress
 * - POST_RECORD: Countdown timer after radar goes safe
 * 
 * Features:
 * - Smart continuation: If radar triggers during POST_RECORD, continues same recording
 * - Configurable pre/post buffers
 * - Automatic cleanup and resource management
 */
public class ProximityGuardController implements ProximityRadarMonitor.TriggerCallback {
    private static final DaemonLogger logger = DaemonLogger.getInstance("ProximityGuardController");
    
    /**
     * Controller states.
     */
    public enum State {
        IDLE,          // Not active
        MONITORING,    // Listening for radar triggers
        RECORDING,     // Active recording
        POST_RECORD    // Countdown after radar safe
    }
    
    private final Context context;
    private final GpuSurveillancePipeline pipeline;
    private final ProximityRadarMonitor radarMonitor;
    private final ProximityRecordingHandler recordingHandler;
    private ProximityGuardConfig config;
    
    private volatile State currentState = State.IDLE;
    private ScheduledFuture<?> postRecordTimer;
    private final ScheduledExecutorService scheduler;
    
    public ProximityGuardController(Context context, GpuSurveillancePipeline pipeline) {
        this.context = context;
        this.pipeline = pipeline;
        
        // Load config
        this.config = loadConfig();
        
        // Create components
        this.radarMonitor = new ProximityRadarMonitor(context, config.getTriggerLevel());
        this.radarMonitor.setCallback(this);
        
        this.recordingHandler = new ProximityRecordingHandler(pipeline);
        
        // Create scheduler for post-record timer
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ProximityPostRecordTimer");
            t.setDaemon(true);
            return t;
        });
        
        logger.info("ProximityGuardController initialized: " + config.toString());
    }
    
    /**
     * Start Proximity Guard mode.
     * Transitions from IDLE to MONITORING.
     * 
     * Note: The "enabled" state is controlled by RecordingModeManager's mode selection.
     * When mode is PROXIMITY_GUARD, this controller should start regardless of config.enabled.
     */
    public synchronized void start() {
        if (currentState != State.IDLE) {
            logger.warn("Cannot start - already in state: " + currentState);
            return;
        }
        
        // Reload config in case it changed (for trigger level, pre/post record settings)
        config = loadConfig();
        
        // SOTA: Don't check config.isEnabled() here - the mode selection in RecordingModeManager
        // is the source of truth for whether proximity guard should be active.
        // The config.enabled flag is deprecated/redundant.
        
        logger.info("Starting Proximity Guard mode...");
        transitionTo(State.MONITORING);
        radarMonitor.startListening();
    }
    
    /**
     * Stop Proximity Guard mode.
     * Transitions to IDLE.
     */
    public synchronized void stop() {
        logger.info("Stopping Proximity Guard mode (current state: " + currentState + ")");
        
        // Stop radar listener
        radarMonitor.stopListening();
        
        // Stop recording if active
        if (currentState == State.RECORDING || currentState == State.POST_RECORD) {
            cancelPostRecordTimer();
            recordingHandler.stopRecording();
        }
        
        transitionTo(State.IDLE);
    }
    
    /**
     * Get current state.
     */
    public State getCurrentState() {
        return currentState;
    }
    
    /**
     * Check if active (not IDLE).
     */
    public boolean isActive() {
        return currentState != State.IDLE;
    }
    
    // ==================== TRIGGER CALLBACKS ====================
    
    @Override
    public synchronized void onProximityTrigger(int area, int state, String level) {
        logger.info("onProximityTrigger: state=" + currentState + " area=" + area + " level=" + level);
        
        switch (currentState) {
            case MONITORING:
                // Start new recording
                transitionTo(State.RECORDING);
                recordingHandler.startRecording(level);
                break;
                
            case POST_RECORD:
                // Smart continuation - cancel timer and extend recording
                logger.info("Extending recording: radar triggered during post-record countdown");
                cancelPostRecordTimer();
                transitionTo(State.RECORDING);
                // Recording continues - same file, just reset the post-record timer when safe
                recordingHandler.extendRecording(level);
                break;
                
            case RECORDING:
                // Already recording - extend by resetting any pending timers
                logger.debug("Already recording, extending duration");
                recordingHandler.extendRecording(level);
                break;
                
            case IDLE:
                logger.warn("Received trigger while IDLE - should not happen");
                break;
        }
    }
    
    @Override
    public synchronized void onProximitySafe() {
        logger.info("onProximitySafe: state=" + currentState);
        
        if (currentState == State.RECORDING) {
            // Start post-record countdown
            transitionTo(State.POST_RECORD);
            startPostRecordTimer();
        }
    }
    
    // ==================== STATE MACHINE ====================
    
    private void transitionTo(State newState) {
        if (currentState == newState) {
            return;
        }
        
        logger.info("State transition: " + currentState + " -> " + newState);
        
        // Exit actions for old state
        switch (currentState) {
            case MONITORING:
                // No cleanup needed
                break;
            case RECORDING:
                // Recording will be stopped by caller
                break;
            case POST_RECORD:
                cancelPostRecordTimer();
                break;
            case IDLE:
                // No cleanup needed
                break;
        }
        
        currentState = newState;
        
        // Entry actions for new state
        switch (newState) {
            case IDLE:
                logger.info("Entered IDLE state");
                break;
            case MONITORING:
                logger.info("Entered MONITORING state - waiting for radar triggers");
                break;
            case RECORDING:
                logger.info("Entered RECORDING state");
                break;
            case POST_RECORD:
                logger.info("Entered POST_RECORD state - countdown started");
                break;
        }
    }
    
    // ==================== POST-RECORD TIMER ====================
    
    private void startPostRecordTimer() {
        cancelPostRecordTimer();  // Cancel any existing timer
        
        int postRecordSeconds = config.getPostRecordSeconds();
        logger.info("Starting post-record timer: " + postRecordSeconds + " seconds");
        
        postRecordTimer = scheduler.schedule(() -> {
            synchronized (this) {
                if (currentState == State.POST_RECORD) {
                    logger.info("Post-record timer expired - stopping recording");
                    recordingHandler.stopRecording();
                    transitionTo(State.MONITORING);
                }
            }
        }, postRecordSeconds, TimeUnit.SECONDS);
    }
    
    private void cancelPostRecordTimer() {
        if (postRecordTimer != null && !postRecordTimer.isDone()) {
            postRecordTimer.cancel(false);
            logger.debug("Post-record timer cancelled");
        }
        postRecordTimer = null;
    }
    
    // ==================== CONFIG ====================
    
    private ProximityGuardConfig loadConfig() {
        try {
            JSONObject proximityConfig = UnifiedConfigManager.getProximityGuard();
            return ProximityGuardConfig.fromConfig(proximityConfig);
        } catch (Exception e) {
            logger.error("Failed to load proximity config: " + e.getMessage());
            return ProximityGuardConfig.createDefault();
        }
    }
    
    /**
     * Reload configuration (call when config changes).
     */
    public synchronized void reloadConfig() {
        config = loadConfig();
        logger.info("Config reloaded: " + config.toString());
    }
    
    /**
     * Shutdown and cleanup resources.
     */
    public void shutdown() {
        stop();
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(2, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
        }
        logger.info("ProximityGuardController shutdown complete");
    }
}
