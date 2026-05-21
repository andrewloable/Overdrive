package com.overdrive.app.surveillance;
import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.ai.YoloDetector;
import com.overdrive.app.telegram.TelegramNotifier;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SurveillanceEngineGpu - V2 Per-Quadrant Motion Detection Pipeline
 * 
 * Uses the V2 native pipeline for per-quadrant 6-stage motion detection
 * with staggered YOLO AI inference on active quadrants.
 */
public class SurveillanceEngineGpu {
    private static final String TAG = "SurveillanceEngineGpu";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);
    
    // Motion detection buffers
    private ByteBuffer currentFrame;
    private long lastMotionTime = 0;
    private long firstMotionTime = 0;  // When sustained motion started (for duration check)
    
    // SUSTAINED MOTION: Base minimum before any trigger (prevents single-frame noise).
    // For THREAT_HIGH (loitering confirmed), this is the only delay needed.
    // For THREAT_MEDIUM (approaching), the loitering time setting adds additional delay.
    private static final long SUSTAINED_MOTION_BASE_MS = 500;
    
    // Loitering time in ms — derived from user setting (1-10 seconds).
    // THREAT_MEDIUM must persist for this duration before triggering recording.
    // THREAT_HIGH triggers after SUSTAINED_MOTION_BASE_MS (loitering already confirmed by native pipeline).
    private long loiteringTimeMs = 3000;  // Default 3 seconds
    
    // MOTION THROTTLING: Process motion at 10 FPS max (saves 66% CPU vs 30 FPS)
    private static final long MOTION_PROCESS_INTERVAL_MS = 100;  // 10 FPS
    private long lastMotionProcessTime = 0;
    
    // ROI mask (null = full frame, otherwise byte array with 0/1 values)
    private byte[] roiMask = null;
    private int roiPixelCount = 0;  // Number of pixels in ROI (for normalization)
    
    // Reference to downscaler for buffer recycling
    private GpuDownscaler downscaler;
    
    // Reference to mosaic recorder for triggering recording
    private GpuMosaicRecorder recorder;
    
    // SOTA: Grid Motion Configuration
    // 640x480 / 32 = 20x15 grid. 32px blocks are ideal for human detection at distance.
    private static final int GRID_BLOCK_SIZE = 32;
    private static final int GRID_COLS = 640 / GRID_BLOCK_SIZE;  // 20
    private static final int GRID_ROWS = 480 / GRID_BLOCK_SIZE;  // 15
    private static final int TOTAL_BLOCKS = GRID_COLS * GRID_ROWS;  // 300
    
    // SIMPLIFIED: Frame-to-frame motion detection
    private int requiredActiveBlocks = 3;    // Need 3+ blocks changed to trigger
    
    // SOTA: Flash Immunity Level (0=OFF, 1=LOW, 2=MEDIUM, 3=HIGH)
    // Uses edge-based detection to ignore light flashes while detecting real motion
    private int flashImmunity = 2;  // Default: MEDIUM
    
    // SOTA: Unified configuration for motion detection, flash filtering, and distance estimation
    private SurveillanceConfig config = createDefaultConfig();
    
    /**
     * Creates default config with proper resolution for mosaic mode.
     * SOTA: Enables chroma filtering by default to ignore lighting changes.
     */
    private static SurveillanceConfig createDefaultConfig() {
        SurveillanceConfig cfg = new SurveillanceConfig(
            SurveillanceConfig.DistancePreset.MEDIUM,
            SurveillanceConfig.FlashMode.ADAPTIVE
        );
        // CRITICAL: Set resolution to match THUMBNAIL dimensions
        cfg.setResolution(THUMBNAIL_WIDTH, THUMBNAIL_HEIGHT);
        cfg.setIsMosaic(true);  // We use 2x2 mosaic layout
        cfg.setUseChroma(true); // SOTA: Enable chroma filtering to ignore lighting changes
        return cfg;
    }
    
    // Track active blocks for UI display
    private int lastActiveBlocksCount = 0;
    private int lastTemporalBlocksCount = 0;  // SOTA: Temporally consistent blocks
    private int lastMotionMinY = 0;           // SOTA: Top of motion bounding box
    private int lastMotionMaxY = 0;           // SOTA: Bottom of motion bounding box
    private float lastEstimatedDistance = 0;  // SOTA: Estimated distance in meters
    
    // Pre-record and post-record configuration (configurable via API)
    private long preRecordMs = 5000;    // 5 seconds before motion (default)
    private long postRecordMs = 10000;  // 10 seconds after motion (default)
    private long recordingStopTime = 0;  // When to stop recording (motion time + post-record)
    private long lastRecordingStopTime = 0;  // When last recording stopped (for cooldown)
    
    // DETERRENT FLASH SUPPRESSION: After the deterrent fires, suppress new motion triggers
    // for a window that covers the cloud API round-trip + flash sequence + ring buffer flush.
    // The BYD cloud flash_lights command has ~15s network latency (dispatch → poll → execute).
    // The lights then flash for 2-3 seconds. Total: 20 seconds from dispatch to scene stable.
    // This window prevents the deterrent's own light from triggering a second recording.
    //
    // WHY AI DOESN'T CATCH THIS (without the fix below):
    // The DetectionBaseline only filtered YOLO output for THREAT_LOW events. For MEDIUM/HIGH,
    // recording triggered purely from the motion pipeline. The deterrent light (and any
    // external light source) creates persistent edge differences that Stage 5 classifies as
    // THREAT_HIGH (loitering) or THREAT_MEDIUM (approaching). The fix extends the YOLO gate
    // to MEDIUM always, and to HIGH during the deterrent window.
    private static final long DETERRENT_SUPPRESSION_MS = 20000;
    private long deterrentFiredTime = 0;  // Timestamp when deterrent was last dispatched
    
    // YOLO CONFIRMATION GATE: Track when YOLO last confirmed a real threat object.
    // For THREAT_MEDIUM: recording requires YOLO confirmation within the motion sequence
    // (with 2-second timeout fallback if YOLO is unavailable/broken).
    // For THREAT_HIGH: only gated during deterrent window (loitering evidence is strong enough otherwise).
    // This makes AI-based background subtraction effective against ALL lighting artifacts,
    // not just the deterrent flash.
    private volatile long lastAiConfirmationTimeMs = 0;  // When YOLO last found a real object
    
    // Detection mode
    private boolean useObjectDetection = false;
    // FIX (A8/B3): volatile so the AI executor sees writes from the UI thread
    // without a torn read. The lambda still snapshots into a local before use.
    private volatile YoloDetector yoloDetector = null;
    // FIX (Bug B): retain context references so we can lazily re-init the YOLO detector
    // when the user re-enables object detection after disabling all classes.
    private android.content.Context yoloContext = null;
    private android.content.res.AssetManager yoloAssetManager = null;
    
    // Object detection filters (SOTA: Quadrant-relative height filter in YoloDetector)
    private float minObjectSize = 0.12f;  // 12% of QUADRANT height (~8m for person in 2x2 grid)
    private float aiConfidence = 0.25f;  // 25% confidence (lowered for debugging)
    // FIX (Bug B): tri-state semantics for classFilter:
    //   null            -> uninitialised, fall back to "all classes" defaults
    //   length == 0     -> user explicitly disabled all classes; YOLO must be skipped
    //   length >  0     -> only those COCO class IDs are kept
    private int[] classFilter = null;
    // Mirror of "should AI run at all" derived from classFilter; cheaper to read on hot path.
    private volatile boolean aiEnabled = true;
    
    // AI throttling - only run YOLO every 500ms to save CPU
    private long lastAiTimeMs = 0;
    private static final long AI_COOLDOWN_MS = 500;
    
    // --- SOTA FIX: Persistent Resources (Eliminates GC Stutter) ---
    // 1. Reusable Buffer: Prevents ~900KB allocation per frame
    // Per-thread scratch buffer for cropFromMosaic. Previously a single
    // shared byte[] was racy: the main render thread (processFrame, tracker
    // update) and the aiExecutor thread (baseline seed / lighting refresh /
    // post-suppression refresh) both call cropFromMosaic, and concurrent
    // System.arraycopy into the same buffer can produce torn rows. All
    // call sites that retain the result already defensive-copy, but the
    // arraycopy ITSELF is racy — torn rows feed YOLO garbage. ThreadLocal
    // gives each thread its own scratch with no synchronization overhead.
    private final ThreadLocal<byte[]> aiBufferTL = new ThreadLocal<>();
    // 2. Single Thread Executor: Prevents OS thread creation overhead.
    //    Runs at THREAD_PRIORITY_BACKGROUND so a 200-300ms CPU YOLO inference
    //    can't preempt the camera-frame producer or encoder-feed thread. On
    //    this device's NNAPI-SL stack, ~538 of ~546 model ops fall through to
    //    XNNPACK on CPU even when "NNAPI" is enabled, so YOLO is in practice
    //    a CPU-heavy task and must yield to higher-priority threads.
    private final ExecutorService aiExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(() -> {
            // Drop to Linux nice +10 so a 200-300ms CPU YOLO pass can't
            // preempt the camera/encoder feed. setThreadPriority works
            // regardless of how Java's Thread.priority maps internally.
            try {
                android.os.Process.setThreadPriority(
                        android.os.Process.THREAD_PRIORITY_BACKGROUND);
            } catch (Throwable ignored) {}
            r.run();
        }, "SentryAiExecutor");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    // 3. Atomic Flag for thread safety
    private final AtomicBoolean isAiRunning = new AtomicBoolean(false);
    // --- END SOTA FIX ---
    
    // State
    private volatile boolean active = false;
    private boolean inActiveMode = false;
    private boolean recording = false;
    
    // V2 Pipeline: Per-quadrant 6-stage motion detection
    private MotionPipelineV2 pipelineV2 = null;
    private MotionPipelineV2.Config pipelineV2Config = null;
    // Staggered YOLO: queue of quadrants to run AI on.
    // Bounded + de-duplicating: there are only 4 quadrants, so a bitset-backed
    // ArrayDeque guarantees the queue can never grow past 4 entries no matter
    // how many motion events fire. Without this bound, sustained 4-quadrant
    // motion would pile up dozens of pending inferences (the executor processes
    // at AI_COOLDOWN_MS = 500ms; bursts at 10 FPS add 4 per frame), every one
    // of which holds GPU/DSP cycles when it eventually runs and contributes to
    // the recording stutter. add() is idempotent for already-queued quadrants.
    private final java.util.ArrayDeque<Integer> aiQuadrantQueue = new java.util.ArrayDeque<>(4);
    private int aiQuadrantQueueMask = 0;  // bit q = quadrant q is in queue
    // P1 #12: lock guards the deque + mask as one unit. Callers come from
    // AiLaneWorker (processFrameV2) and the aiExecutor lambda (heartbeat /
    // post-suppression refresh paths); without this the deque could throw
    // ConcurrentModificationException and mask/deque could decohere.
    private final Object aiQuadrantQueueLock = new Object();

    private void aiQuadrantQueueAdd(int q) {
        if (q < 0 || q >= MotionPipelineV2.NUM_QUADRANTS) return;
        int bit = 1 << q;
        synchronized (aiQuadrantQueueLock) {
            if ((aiQuadrantQueueMask & bit) != 0) return;  // already queued
            aiQuadrantQueueMask |= bit;
            aiQuadrantQueue.addLast(q);
        }
    }

    private Integer aiQuadrantQueuePoll() {
        synchronized (aiQuadrantQueueLock) {
            Integer q = aiQuadrantQueue.pollFirst();
            if (q != null) aiQuadrantQueueMask &= ~(1 << q);
            return q;
        }
    }

    private void aiQuadrantQueueClear() {
        synchronized (aiQuadrantQueueLock) {
            aiQuadrantQueue.clear();
            aiQuadrantQueueMask = 0;
        }
    }

    private boolean aiQuadrantQueueIsEmpty() {
        synchronized (aiQuadrantQueueLock) {
            return aiQuadrantQueue.isEmpty();
        }
    }
    
    // Foveated AI cropping: high-res 640×640 crop from raw camera strip
    private FoveatedCropper foveatedCropper = null;
    private int cameraTextureId = -1;  // OES texture for foveated crop
    // GL thread handler — used to dispatch foveated crops back to GL thread
    // because crop() touches GL state (FBO bind, glReadPixels). With the
    // AI lane decoupled to AiLaneWorker, processFrame now runs off the GL
    // thread and cannot directly call cropper.crop() — we must hop to GL.
    private android.os.Handler glHandler = null;
    // Camera FPS used to size the GL-hop wait budget (one frame + slack).
    // Wired by PanoramicCameraGpu.setCameraTargetFps() at startup and on FPS
    // changes; the cropOnGlThread budget tracks it. 0 until wired — the
    // crop path falls back to a safe minimum so we never time out on cold start.
    private volatile int cameraTargetFps = 0;
    // Throttle for foveated GL-hop timeout warnings — log at most once per 5s
    // and aggregate the count so a busy GL thread doesn't spam.
    private long lastFoveatedTimeoutLogMs = 0;
    private long foveatedTimeoutCount = 0;
    
    // Cross-quadrant object tracker
    private final CrossQuadrantTracker crossQuadrantTracker = new CrossQuadrantTracker();

    // Actor-layer tracker — sits ON TOP of the existing YOLO + cross-quadrant
    // pipeline and emits Actor records carrying proximity / trend / severity for
    // the timeline + thumbnail + notification + UI layers. Does not affect motion
    // detection or recording trigger logic.
    private final ActorTracker actorTracker = new ActorTracker();
    // Snapshot of the most recent Actor list, for callers that read state.
    // CopyOnWrite to keep reads lock-free for UI / API threads.
    private volatile java.util.List<Actor> lastActors = java.util.Collections.emptyList();

    // Thumbnail capture buffer (Block C). Field declared here so the wiring point
    // in runAiOnQuadrant compiles even before Block C lands. Constructed/reset by
    // recording lifecycle handlers.
    private ThumbnailBuffer thumbnailBuffer = null;

    // FIX (B1/H-a): recording-generation counter. Bumped whenever a recording
    // ends and Actor/Thumbnail state is reset. The aiExecutor lambda captures
    // the value at scheduling time; on completion, it compares against the
    // current value and drops its writes (lastActors update, ThumbnailBuffer
    // observe, baseline promotion) if the generation has advanced. Without
    // this, a slow YOLO inference scheduled before stopRecording can repopulate
    // state for a recording that has already finished, polluting the next
    // recording's first frames.
    private final java.util.concurrent.atomic.AtomicLong recordingGeneration =
            new java.util.concurrent.atomic.AtomicLong(0);
    
    // Heartbeat cooldown: prevent NCC tracker from spamming YOLO on every frame
    // when the template match is failing. Without this, a bad template causes
    // needsYoloHeartbeat=true on every frame, turning YOLO into a continuous
    // 10 FPS detector and destroying the battery savings of decoupled tracking.
    private static final long HEARTBEAT_COOLDOWN_MS = 5000;  // Min 5s between heartbeats per quadrant
    private final long[] lastHeartbeatTimeMs = new long[MotionPipelineV2.NUM_QUADRANTS];
    
    // Auto-exposure state (C++ handles per-quadrant threshold scaling,
    // Java only handles global params like brightness suppression and shadow filter mode)
    
    // Filter debug log: ring buffer of recent filter decisions (max 100 entries)
    private static final int FILTER_LOG_CAPACITY = 100;
    private final String[] filterLog = new String[FILTER_LOG_CAPACITY];
    private int filterLogIndex = 0;
    private int filterLogCount = 0;
    private boolean filterDebugEnabled = false;
    
    // SOTA: Event timeline collector for JSON sidecar files
    private final EventTimelineCollector timelineCollector = new EventTimelineCollector();
    
    // SOTA: Detection baseline for filtering static objects from YOLO output.
    // Maintains a per-quadrant "living memory" of known scene objects so that
    // motion-triggered YOLO detections of parked cars, trash cans, etc. are
    // suppressed — only NEW or MOVED objects trigger recording.
    private final DetectionBaseline detectionBaseline = new DetectionBaseline();
    // Track whether baseline has been seeded (one-time on sentry enable)
    private volatile boolean baselineSeeded = false;
    // Track last YOLO detections per quadrant for event-end baseline update.
    // P1 #13: AtomicReferenceArray — written from aiExecutor lambda, read by
    // stopRecording() (recorder drainer thread) and reset by enable() / disable().
    // Plain array slot publication wasn't safe-published across threads. Readers
    // must tolerate null (they already do — null check before deref).
    private final java.util.concurrent.atomic.AtomicReferenceArray<java.util.List<com.overdrive.app.ai.Detection>> lastYoloDetections =
            new java.util.concurrent.atomic.AtomicReferenceArray<>(MotionPipelineV2.NUM_QUADRANTS);
    // Track which quadrant had the last event (for event-end baseline update)
    private int lastEventQuadrant = -1;
    
    // POST-SUPPRESSION BASELINE REFRESH: When brightness suppression fires (lighting change),
    // queue a baseline refresh for after the scene stabilizes. This keeps the baseline
    // synchronized with what YOLO can actually see under the current lighting conditions.
    // Without this, a streetlight turning on makes a previously-invisible car "appear" to
    // YOLO as a new object → false trigger. Cost: 1 inference per quadrant per lighting
    // event (5-10 per night = negligible).
    //
    // We track per-quadrant: when suppression was last active, and whether we've already
    // refreshed after it. The refresh runs STABILIZATION_FRAMES after suppression ends
    // (to let the ISP settle and avoid refreshing on a transitional frame).
    private static final int BASELINE_STABILIZATION_FRAMES = 15;  // 1.5s at 10 FPS
    private final int[] framesSinceSuppressionEnded = new int[MotionPipelineV2.NUM_QUADRANTS];
    private final boolean[] suppressionWasActive = new boolean[MotionPipelineV2.NUM_QUADRANTS];
    private final boolean[] baselineRefreshQueued = new boolean[MotionPipelineV2.NUM_QUADRANTS];
    
    // Output directory
    private File eventOutputDir;
    // volatile: read by main render thread (publishMotionFinal,
    // sendFinalTelegramNotification) and written by the encoder drainer
    // thread (segment listener at rotation time). Without this, the main
    // thread could observe a stale File reference.
    private volatile File currentEventFile;
    
    // Frame dimensions - SOTA: Increased to 640x480 for better AI detection
    // At 320x240 with quad view, each camera is 160x120 - too small for YOLO
    // At 640x480 with quad view, each camera is 320x240 - YOLO can detect people at 5m
    private static final int THUMBNAIL_WIDTH = 640;
    private static final int THUMBNAIL_HEIGHT = 480;
    private static final int BYTES_PER_PIXEL = 3;  // RGB
    private static final int FRAME_SIZE = THUMBNAIL_WIDTH * THUMBNAIL_HEIGHT * BYTES_PER_PIXEL;
    
    // Stats
    private int frameCount = 0;
    private int motionDetections = 0;
    
    // Cached latest mosaic frame for snapshot API (640×480 RGB)
    private volatile byte[] latestMosaicFrame = null;
    
    /**
     * Initializes the surveillance engine.
     * 
     * @param eventDir Directory for saving event recordings
     * @param downscaler GPU downscaler reference for buffer recycling
     */
    public void init(File eventDir, GpuDownscaler downscaler) {
        init(eventDir, downscaler, null, null);
    }
    
    /**
     * Initializes the surveillance engine with optional AssetManager for YOLO loading.
     * 
     * @param eventDir Directory for saving event recordings
     * @param downscaler GPU downscaler reference for buffer recycling
     * @param assetManager Android AssetManager for loading YOLO model (null = skip YOLO)
     */
    public void init(File eventDir, GpuDownscaler downscaler, android.content.res.AssetManager assetManager) {
        init(eventDir, downscaler, assetManager, null);
    }
    
    /**
     * Initializes the surveillance engine with Context for Java TFLite.
     * 
     * @param eventDir Directory for saving event recordings
     * @param downscaler GPU downscaler reference for buffer recycling
     * @param assetManager Android AssetManager (unused, kept for compatibility)
     * @param context Android Context for TFLite initialization
     */
    public void init(File eventDir, GpuDownscaler downscaler, android.content.res.AssetManager assetManager, android.content.Context context) {
        this.eventOutputDir = eventDir;
        this.downscaler = downscaler;
        // Retain for lazy YOLO re-init (Bug B fix path)
        this.yoloContext = context;
        this.yoloAssetManager = assetManager;
        // Construct thumbnail buffer once; it is reused across recordings (it
        // clears its slots itself at recording-stop).
        if (this.thumbnailBuffer == null) {
            this.thumbnailBuffer = new ThumbnailBuffer();
        }
        
        if (!eventDir.exists()) {
            eventDir.mkdirs();
        }
        
        // Allocate direct buffer for V2 pipeline JNI
        currentFrame = ByteBuffer.allocateDirect(FRAME_SIZE);
        currentFrame.order(ByteOrder.nativeOrder());
        
        // Detect available features
        try {
            // Initialize Java TFLite YOLO detector
            // Note: We don't have a full Context in daemon mode, but we can create one from AssetManager
            if (context != null) {
                try {
                    logger.info("Initializing Java TFLite YOLO detector...");
                    yoloDetector = new YoloDetector(context);
                    boolean yoloLoaded = yoloDetector.init();
                    
                    if (yoloLoaded) {
                        useObjectDetection = true;
                        logger.info("YOLO model loaded successfully - object detection enabled");
                        logger.info("GPU acceleration: " + (yoloDetector.isGpuEnabled() ? "ENABLED" : "disabled (CPU fallback)"));
                    } else {
                        logger.warn("Failed to load YOLO model");
                        useObjectDetection = false;
                        yoloDetector = null;
                    }
                } catch (Exception e) {
                    logger.error("Error initializing YOLO detector: " + e.getMessage(), e);
                    useObjectDetection = false;
                    yoloDetector = null;
                }
            } else if (assetManager != null) {
                // Daemon mode: Create minimal context from AssetManager
                try {
                    logger.info("Creating AssetContext for TFLite (daemon mode)...");
                    android.content.Context assetContext = new com.overdrive.app.ai.AssetContext(assetManager);
                    
                    yoloDetector = new YoloDetector(assetContext);
                    boolean yoloLoaded = yoloDetector.init();
                    
                    if (yoloLoaded) {
                        useObjectDetection = true;
                        logger.info("YOLO model loaded successfully - object detection enabled");
                        logger.info("GPU acceleration: " + (yoloDetector.isGpuEnabled() ? "ENABLED" : "disabled (CPU fallback)"));
                    } else {
                        logger.warn("Failed to load YOLO model");
                        useObjectDetection = false;
                        yoloDetector = null;
                    }
                } catch (Exception e) {
                    logger.error("Error creating AssetContext: " + e.getMessage(), e);
                    useObjectDetection = false;
                    yoloDetector = null;
                }
            } else {
                logger.info("No Context or AssetManager provided - object detection disabled");
                useObjectDetection = false;
            }
        } catch (UnsatisfiedLinkError e) {
            logger.warn("Native features not available: " + e.getMessage());
            useObjectDetection = false;
        }
        
        logger.info("Initialized surveillance engine (buffer=" + FRAME_SIZE + " bytes)");
        
        // Initialize V2 per-quadrant pipeline
        try {
            pipelineV2 = new MotionPipelineV2();
            if (pipelineV2.init()) {
                pipelineV2Config = new MotionPipelineV2.Config();
                pipelineV2Config.applyEnvironmentPreset("outdoor");  // Default preset
                pipelineV2.applyConfig(pipelineV2Config);
                logger.info("V2 per-quadrant pipeline initialized");
            } else {
                logger.error("V2 pipeline init failed");
                pipelineV2 = null;
            }
        } catch (Exception e) {
            logger.error("V2 pipeline not available: " + e.getMessage());
            pipelineV2 = null;
        }
    }
    
    /**
     * Sets the mosaic recorder for event recording.
     * 
     * @param recorder Mosaic recorder instance
     */
    public void setRecorder(GpuMosaicRecorder recorder) {
        this.recorder = recorder;
    }
    
    /**
     * Set the foveated cropper for high-res AI inference.
     * When set, YOLO runs on a 640×640 crop from the raw 5120×960 strip
     * instead of the 320×240 mosaic quadrant. Must be called from GL thread.
     *
     * @param cropper FoveatedCropper instance (initialized on GL thread)
     * @param textureId Camera OES texture ID for direct strip access
     */
    public void setFoveatedCropper(FoveatedCropper cropper, int textureId) {
        this.foveatedCropper = cropper;
        this.cameraTextureId = textureId;
        if (cropper != null && cropper.isInitialized()) {
            logger.info("Foveated AI cropping enabled (640×640 from raw strip)");
        }
    }

    /** GL handler for posting foveated crops back to the GL thread.
     *  Required when processFrame runs on AiLaneWorker. */
    public void setGlHandler(android.os.Handler glHandler) {
        this.glHandler = glHandler;
    }

    /** Camera target FPS — sizes the foveated GL-hop wait budget so the
     *  AI lane never times out on a normal-load render frame. */
    public void setCameraTargetFps(int fps) {
        if (fps > 0) this.cameraTargetFps = fps;
    }

    /**
     * Run foveatedCropper.crop on the GL thread and wait for result. Caller
     * may be on AiLaneWorker — this method bridges back to GL where
     * FBO/glReadPixels calls are valid.
     *
     * Returns null if the crop fails, the GL handler isn't set, or the call
     * times out. Timeout is sized to one full camera frame (1000/targetFps + 50ms
     * slack) so we tolerate the GL thread being mid-render without flooding logs.
     * Caller falls back to the mosaic crop on null.
     */
    private byte[] cropOnGlThread(int quadrant, float centroidX, float centroidY) {
        FoveatedCropper cropper = this.foveatedCropper;
        int texId = this.cameraTextureId;
        android.os.Handler h = this.glHandler;
        if (cropper == null || texId < 0) return null;
        // Fast path: if we're already on GL thread (handler == null OR handler's
        // looper == current looper), just call directly.
        if (h == null || h.getLooper().getThread() == Thread.currentThread()) {
            try {
                return cropper.crop(texId, quadrant, centroidX, centroidY);
            } catch (Throwable t) {
                logger.warn("Foveated crop (inline) failed: " + t.getMessage());
                return null;
            }
        }
        // Slow path: post to GL handler and wait. Budget = one camera frame at
        // the configured target FPS, plus a small slack for the readback itself.
        // At 15 fps that's ~115ms; at 30 fps ~85ms. The previous 50ms cap was
        // smaller than a single frame's render budget which guaranteed timeouts.
        // Floor at 80ms so we don't go shorter than a normal readback even if
        // FPS hasn't been wired yet (cold start before setCameraTargetFps).
        int fps = this.cameraTargetFps;
        long timeoutMs = fps > 0 ? Math.max(80, (1000L / fps) + 50) : 150;
        final byte[][] result = new byte[1][];
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        boolean posted = h.post(() -> {
            try {
                result[0] = cropper.crop(texId, quadrant, centroidX, centroidY);
            } catch (Throwable t) {
                logger.warn("Foveated crop (GL hop) failed: " + t.getMessage());
            } finally {
                latch.countDown();
            }
        });
        if (!posted) {
            // GL thread shutting down or handler invalid — fall back to mosaic.
            return null;
        }
        try {
            if (!latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                // Throttle the warn so a busy GL thread doesn't spam logs.
                long now = System.currentTimeMillis();
                if (now - lastFoveatedTimeoutLogMs > 5_000) {
                    lastFoveatedTimeoutLogMs = now;
                    foveatedTimeoutCount++;
                    logger.info("Foveated crop GL hop timed out (mosaic fallback active; "
                            + foveatedTimeoutCount + " timeouts since start)");
                }
                return null;
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return null;
        }
        return result[0];
    }
    
    /**
     * Get the current foveated cropper (for lazy-init check).
     */
    public FoveatedCropper getFoveatedCropper() {
        return foveatedCropper;
    }
    
    /**
     * SOTA: Updates the event output directory.
     * Called when storage type changes (internal <-> SD card) to ensure
     * events are saved to the correct location.
     * 
     * @param eventDir New directory for saving event recordings
     */
    public void setEventOutputDir(File eventDir) {
        this.eventOutputDir = eventDir;
        if (eventDir != null && !eventDir.exists()) {
            boolean created = eventDir.mkdirs();
            logger.info("Updated event output directory: " + eventDir.getAbsolutePath() + " (created=" + created + ")");
            if (created) {
                eventDir.setReadable(true, false);
                eventDir.setExecutable(true, false);
            }
        } else {
            logger.info("Updated event output directory: " + (eventDir != null ? eventDir.getAbsolutePath() : "null"));
        }
    }
    
    /**
     * Processes a frame from the GPU downscaler.
     * 
     * This is called at 2 FPS during idle mode. When motion is detected,
     * it can be called at 5 FPS for more responsive AI.
     * 
     * CRITICAL: This method receives a BORROWED buffer from the pool.
     * The buffer MUST be recycled in a finally block to prevent pool exhaustion.
     * If async AI is needed, the data must be copied before recycling.
     * 
     * @param smallRgbFrame 320x240 RGB frame from GPU (borrowed from pool)
     */
    public void processFrame(byte[] smallRgbFrame) {
        if (!active) {
            // Still need to recycle even if not active
            if (downscaler != null) {
                downscaler.recycleBuffer(smallRgbFrame);
            }
            return;
        }
        
        // RACE CONDITION FIX (belt-and-suspenders): If somehow active=true but ACC is ON,
        // auto-disable. This catches the case where enable() raced with ACC ON and the
        // disable path hasn't run yet.
        if (com.overdrive.app.monitor.AccMonitor.isAccOn()) {
            logger.warn("processFrame: ACC is ON but surveillance is active — auto-disabling");
            disable();
            if (downscaler != null) {
                downscaler.recycleBuffer(smallRgbFrame);
            }
            return;
        }
        
        if (smallRgbFrame == null || smallRgbFrame.length != FRAME_SIZE) {
            logger.warn( "Invalid frame size: " + (smallRgbFrame != null ? smallRgbFrame.length : 0));
            if (downscaler != null && smallRgbFrame != null) {
                downscaler.recycleBuffer(smallRgbFrame);
            }
            return;
        }
        
        try {
            frameCount++;
            long now = System.currentTimeMillis();
            
            // Cache latest frame for snapshot API (every 10th frame to reduce copies)
            if (frameCount % 10 == 0) {
                if (latestMosaicFrame == null || latestMosaicFrame.length != smallRgbFrame.length) {
                    latestMosaicFrame = new byte[smallRgbFrame.length];
                }
                System.arraycopy(smallRgbFrame, 0, latestMosaicFrame, 0, smallRgbFrame.length);
            }
            
            // Log frame count every 100 frames to confirm frames are arriving
            if (frameCount % 100 == 0) {
                logger.info("Surveillance frame #" + frameCount + " received");
            }
            
            // MOTION THROTTLING: Skip frames to achieve 10 FPS (saves 66% CPU)
            if (now - lastMotionProcessTime < MOTION_PROCESS_INTERVAL_MS) {
                return;
            }
            lastMotionProcessTime = now;
            
            if (pipelineV2 == null) {
                logger.warn("V2 pipeline not initialized — skipping frame");
                return;
            }
            
            processFrameV2(smallRgbFrame, now);
            
        } finally {
            // CRITICAL: Always recycle buffer back to pool
            // This MUST happen in finally block to prevent pool exhaustion
            if (downscaler != null) {
                downscaler.recycleBuffer(smallRgbFrame);
            }
        }
    }
    
    /**
     * V2 Pipeline: Per-quadrant 6-stage motion detection.
     */
    // Track peak threat level during a motion sequence (reset when sequence ends)
    private int peakThreatDuringSequence = 0;
    
    // Previous frame sample for Java-side motion diff check (independent of native pipeline)
    private int[] prevFrameSamples = null;
    private int[] prevDenseHash = null;
    
    private void processFrameV2(byte[] smallRgbFrame, long now) {
        // Copy frame data into a direct ByteBuffer for JNI
        currentFrame.clear();
        currentFrame.put(smallRgbFrame);
        currentFrame.flip();
        
        // DIAGNOSTIC: Every 100 frames, check frame validity and inter-frame diff.
        // Only in debug builds — this is pure development tooling.
        if (com.overdrive.app.BuildConfig.DEBUG && frameCount % 100 == 0) {
            // Sample 16 pixels spread across the frame
            int[] currentSamples = new int[16];
            int[][] sampleCoords = {
                {60, 80}, {60, 240}, {60, 400}, {60, 560},    // Row 1
                {180, 80}, {180, 240}, {180, 400}, {180, 560}, // Row 2
                {300, 80}, {300, 240}, {300, 400}, {300, 560}, // Row 3
                {420, 80}, {420, 240}, {420, 400}, {420, 560}  // Row 4
            };
            boolean allBlack = true;
            for (int i = 0; i < 16; i++) {
                int off = (sampleCoords[i][0] * THUMBNAIL_WIDTH + sampleCoords[i][1]) * 3;
                if (off + 2 < smallRgbFrame.length) {
                    int r = smallRgbFrame[off] & 0xFF;
                    int g = smallRgbFrame[off + 1] & 0xFF;
                    int b = smallRgbFrame[off + 2] & 0xFF;
                    currentSamples[i] = (r << 16) | (g << 8) | b;
                    if (r > 5 || g > 5 || b > 5) allBlack = false;
                }
            }
            
            // Compare with previous frame samples
            int maxDiff = 0;
            int changedSamples = 0;
            if (prevFrameSamples != null) {
                for (int i = 0; i < 16; i++) {
                    int r1 = (currentSamples[i] >> 16) & 0xFF;
                    int g1 = (currentSamples[i] >> 8) & 0xFF;
                    int b1 = currentSamples[i] & 0xFF;
                    int r2 = (prevFrameSamples[i] >> 16) & 0xFF;
                    int g2 = (prevFrameSamples[i] >> 8) & 0xFF;
                    int b2 = prevFrameSamples[i] & 0xFF;
                    int diff = Math.abs(r1 - r2) + Math.abs(g1 - g2) + Math.abs(b1 - b2);
                    if (diff > maxDiff) maxDiff = diff;
                    if (diff > 10) changedSamples++;
                }
            }
            prevFrameSamples = currentSamples;
            
            // Also compute a dense diff: scan every 20th pixel across the full frame
            int denseMaxDiff = 0;
            int denseChanged = 0;
            int denseSamples = 0;
            if (prevDenseHash != null) {
                for (int y = 0; y < THUMBNAIL_HEIGHT; y += 20) {
                    for (int x = 0; x < THUMBNAIL_WIDTH; x += 20) {
                        int off = (y * THUMBNAIL_WIDTH + x) * 3;
                        if (off + 2 < smallRgbFrame.length) {
                            int r = smallRgbFrame[off] & 0xFF;
                            int g = smallRgbFrame[off + 1] & 0xFF;
                            int b = smallRgbFrame[off + 2] & 0xFF;
                            int idx = denseSamples;
                            if (idx < prevDenseHash.length) {
                                int pr = (prevDenseHash[idx] >> 16) & 0xFF;
                                int pg = (prevDenseHash[idx] >> 8) & 0xFF;
                                int pb = prevDenseHash[idx] & 0xFF;
                                int diff = Math.abs(r - pr) + Math.abs(g - pg) + Math.abs(b - pb);
                                if (diff > denseMaxDiff) denseMaxDiff = diff;
                                if (diff > 30) denseChanged++;
                            }
                            denseSamples++;
                        }
                    }
                }
            }
            // Store dense samples for next comparison
            int totalDense = (THUMBNAIL_HEIGHT / 20) * (THUMBNAIL_WIDTH / 20);
            if (prevDenseHash == null || prevDenseHash.length != totalDense) {
                prevDenseHash = new int[totalDense];
            }
            int di = 0;
            for (int y = 0; y < THUMBNAIL_HEIGHT; y += 20) {
                for (int x = 0; x < THUMBNAIL_WIDTH; x += 20) {
                    int off = (y * THUMBNAIL_WIDTH + x) * 3;
                    if (off + 2 < smallRgbFrame.length && di < prevDenseHash.length) {
                        int r = smallRgbFrame[off] & 0xFF;
                        int g = smallRgbFrame[off + 1] & 0xFF;
                        int b = smallRgbFrame[off + 2] & 0xFF;
                        prevDenseHash[di++] = (r << 16) | (g << 8) | b;
                    }
                }
            }
            
            // Log sample pixels from each quadrant center
            int q0 = currentSamples[5];
            int q1 = currentSamples[6];
            int q2 = currentSamples[9];
            int q3 = currentSamples[10];
            
            logger.info(String.format("FRAME_DIAG #%d: %s | sparse: max=%d changed=%d/16 | dense: max=%d changed=%d/%d | Q0=(%d,%d,%d) Q1=(%d,%d,%d) Q2=(%d,%d,%d) Q3=(%d,%d,%d)",
                    frameCount,
                    allBlack ? "ALL_BLACK!" : "ok",
                    maxDiff, changedSamples,
                    denseMaxDiff, denseChanged, denseSamples,
                    (q0>>16)&0xFF, (q0>>8)&0xFF, q0&0xFF,
                    (q1>>16)&0xFF, (q1>>8)&0xFF, q1&0xFF,
                    (q2>>16)&0xFF, (q2>>8)&0xFF, q2&0xFF,
                    (q3>>16)&0xFF, (q3>>8)&0xFF, q3&0xFF));
        }
        
        // Run V2 pipeline (includes C++ Global Illumination Sync)
        MotionPipelineV2.QuadrantResult[] results = pipelineV2.processFrame(
                currentFrame, THUMBNAIL_WIDTH, THUMBNAIL_HEIGHT);
        
        // SOTA: Seed detection baseline once after camera warmup (frame 30 = ~3s at 10 FPS).
        // Runs YOLO on each quadrant to catalog what's already in the scene (parked cars,
        // trash cans, etc.) so future motion-triggered detections can filter them out.
        // Cost: 4 inferences, one-time. Runs on AI executor thread to avoid blocking motion pipeline.
        if (!baselineSeeded && frameCount == 30 && useObjectDetection && yoloDetector != null) {
            baselineSeeded = true;  // Set immediately to prevent re-entry
            final byte[] seedFrame = new byte[smallRgbFrame.length];
            System.arraycopy(smallRgbFrame, 0, seedFrame, 0, smallRgbFrame.length);
            aiExecutor.execute(() -> {
                // FIX (A8/B3): snapshot detector at lambda entry — see runAiOnQuadrant
                // for rationale. Toggling AI off via setObjectFilters between
                // submission and execution would otherwise NPE or crash native TFLite.
                final YoloDetector detectorSnap = yoloDetector;
                if (detectorSnap == null || !aiEnabled) {
                    logger.info("Baseline seed skipped (detector closed)");
                    return;
                }
                logger.info("Seeding detection baseline (frame 30)...");
                int qW = THUMBNAIL_WIDTH / 2;
                int qH = THUMBNAIL_HEIGHT / 2;
                for (int q = 0; q < MotionPipelineV2.NUM_QUADRANTS; q++) {
                    try {
                        byte[] quadCrop = cropFromMosaic(seedFrame, q, qW, qH);
                        if (quadCrop != null) {
                            java.util.List<com.overdrive.app.ai.Detection> dets =
                                    detectorSnap.detect(quadCrop, qW, qH, aiConfidence, true, true, false, true, minObjectSize);
                            detectionBaseline.seedFromDetections(q, dets, qW, qH);
                        }
                    } catch (Exception e) {
                        logger.warn("Baseline seed failed for Q" + q + ": " + e.getMessage());
                    }
                }
                logger.info("Detection baseline seeded for all quadrants");
            });
        }
        
        // Per-quadrant override post-filter. The native pipeline ran with the
        // aggregate (most-permissive) sensitivity/zone, so each quadrant's
        // result currently reflects the loosest gates. Walk the quadrants and
        // demote any result that wouldn't pass its own effective gates.
        applyQuadrantOverrides(results);

        // Check if any quadrant detected motion at MEDIUM or higher threat.
        int maxThreat = pipelineV2.getMaxThreatLevel();
        boolean anyMotion = maxThreat >= MotionPipelineV2.THREAT_MEDIUM;
        
        // SOTA: Tracker immunity from brightness suppression (Headlight Sweep Fix).
        // When a car's headlights sweep across the camera, the brightness suppression
        // stage kills ALL motion blocks in that quadrant. If a person is being tracked
        // in that quadrant, the motion sequence timer loses them and the recording
        // stops prematurely. Fix: if any quadrant is brightness-suppressed but the
        // NCC tracker has an active lock on it, keep anyMotion=true so the sequence
        // timer continues. The tracker's pixel-level lock is immune to global
        // brightness changes — it tracks texture, not absolute luminance.
        // FIX: Only person tracks (classId==0) get immunity. Vehicle tracks
        // (motorcycles, cars) should not override brightness suppression.
        if (!anyMotion) {
            for (int q = 0; q < MotionPipelineV2.NUM_QUADRANTS; q++) {
                if (results[q].brightnessSuppressed) {
                    try {
                        if (NativeMotion.trackerHasActiveTrack(q)) {
                            float[] trackBox = NativeMotion.trackerGetTrackBox(q);
                            if (trackBox != null && (int) trackBox[5] == 0) { // person only
                                anyMotion = true;
                                if (maxThreat < MotionPipelineV2.THREAT_MEDIUM) {
                                    maxThreat = MotionPipelineV2.THREAT_MEDIUM;
                                }
                                if (frameCount % 50 == 0) {
                                    logger.info("Headlight sweep immunity: Q" + q + 
                                            " [" + MotionPipelineV2.QUADRANT_NAMES[q] + 
                                            "] suppressed but tracker holds person lock");
                                }
                                break;
                            }
                        }
                    } catch (Exception ignored) {}
                }
            }
        }
        
        // --- Diagnostic: Log per-quadrant pipeline results every time motion is detected ---
        // This shows exactly what the pipeline saw and why it did/didn't trigger.
        if (anyMotion || filterDebugEnabled) {
            String[] threatNames = {"NONE", "LOW(pass)", "MEDIUM(approach)", "HIGH(loiter)"};
            int bestQ = pipelineV2.getHighestThreatQuadrant();
            
            for (int q = 0; q < MotionPipelineV2.NUM_QUADRANTS; q++) {
                MotionPipelineV2.QuadrantResult r = results[q];
                if (r.activeBlocks == 0 && !r.brightnessSuppressed) continue;
                
                String qName = MotionPipelineV2.QUADRANT_NAMES[q];
                
                // Convert centroid block coords to estimated distance in meters
                float estDistM = estimateDistanceFromCentroid(q, r.centroidY);
                String distStr = (r.componentSize > 0) ? String.format("~%.1fm", estDistM) : "n/a";
                
                // Zone cutoff in human terms
                int maxRow = pipelineV2Config != null ? pipelineV2Config.maxDistanceRow : 0;
                float zoneCutoffDist = estimateDistanceFromCentroid(q, maxRow);
                String zoneStr = config != null ? config.getDetectionZone() : "?";
                String zoneLimitStr = maxRow > 0 ? String.format("%s(<%s ~%.1fm)", zoneStr, 
                        maxRow == 4 ? "close" : maxRow == 2 ? "normal" : "extended", zoneCutoffDist) 
                        : zoneStr + "(no limit)";
                
                if (r.brightnessSuppressed) {
                    logger.debug(String.format(
                        "  [%s] BRIGHTNESS_SUPPRESSED luma=%.0f (light change detected)",
                        qName, r.meanLuma));
                } else if (r.shadowFiltered && !r.motionDetected) {
                    logger.debug(String.format(
                        "  [%s] SHADOW_FILTERED active=%d (shadow discrimination removed blocks)",
                        qName, r.activeBlocks));
                } else if (r.motionDetected) {
                    logger.info(String.format(
                        "  [%s] %s | dist=%s | blocks: active=%d confirmed=%d component=%d | zone=%s",
                        qName, threatNames[r.threatLevel], distStr,
                        r.activeBlocks, r.confirmedBlocks, r.componentSize, zoneLimitStr));
                } else if (r.activeBlocks > 0) {
                    // Motion was detected at block level but rejected by later stages
                    String reason;
                    if (r.confirmedBlocks == 0) {
                        reason = "not yet confirmed (need more frames)";
                    } else if (r.componentSize < (pipelineV2Config != null ? pipelineV2Config.minComponentSize : 1)) {
                        reason = String.format("component too small (%d blocks, need %d)", r.componentSize,
                                pipelineV2Config != null ? pipelineV2Config.minComponentSize : 1);
                    } else if (maxRow > 0 && r.centroidY < maxRow) {
                        reason = String.format("too far away (%s, zone limit ~%.1fm)", distStr, zoneCutoffDist);
                    } else if (r.confirmedBlocks < (pipelineV2Config != null ? pipelineV2Config.alarmBlockThreshold : 2)) {
                        reason = String.format("below alarm threshold (%d blocks, need %d)", r.confirmedBlocks,
                                pipelineV2Config != null ? pipelineV2Config.alarmBlockThreshold : 2);
                    } else {
                        reason = "passing motion (" + threatNames[r.threatLevel] + ", ignored)";
                    }
                    logger.debug(String.format(
                        "  [%s] REJECTED: %s | dist=%s active=%d confirmed=%d",
                        qName, reason, distStr, r.activeBlocks, r.confirmedBlocks));
                }
            }
        }
        
        // Update legacy tracking variables for compatibility
        if (anyMotion) {
            int bestQ = pipelineV2.getHighestThreatQuadrant();
            if (bestQ >= 0) {
                lastActiveBlocksCount = results[bestQ].activeBlocks;
                lastTemporalBlocksCount = results[bestQ].confirmedBlocks;
            }
        }
        
        if (anyMotion) {
            lastMotionTime = now;
            
            // Track peak threat across the entire motion sequence
            if (maxThreat > peakThreatDuringSequence) {
                peakThreatDuringSequence = maxThreat;
            }
            
            // Log motion to timeline — ALWAYS, even before recording starts.
            // The timeline collector's pre-trigger ring buffer captures events during
            // the approach phase. When recording triggers, these are flushed into the
            // active span array with timestamps aligned to the video's pre-record window.
            timelineCollector.onMotionDetected(lastActiveBlocksCount, pipelineV2.getActiveQuadrantMask());
            
            if (firstMotionTime == 0) {
                firstMotionTime = now;
                peakThreatDuringSequence = maxThreat;
                int bestQ = pipelineV2.getHighestThreatQuadrant();
                MotionPipelineV2.QuadrantResult bestR = bestQ >= 0 ? results[bestQ] : null;
                float estDist = bestQ >= 0 && bestR != null ? estimateDistanceFromCentroid(bestQ, bestR.centroidY) : -1;
                String threatStr = maxThreat >= MotionPipelineV2.THREAT_HIGH ? "HIGH(loiter)" : "MEDIUM(approach)";
                long needed = maxThreat >= MotionPipelineV2.THREAT_HIGH ? SUSTAINED_MOTION_BASE_MS : loiteringTimeMs;
                logger.info(String.format("Motion started: %s camera, threat=%s, dist=~%.1fm, need %.1fs sustained...",
                        bestQ >= 0 ? MotionPipelineV2.QUADRANT_NAMES[bestQ] : "?",
                        threatStr, estDist, needed / 1000.0));
            }
            
            long motionDuration = now - firstMotionTime;
            
            // Use peak threat for duration requirement (not just current frame).
            // This prevents a brief MEDIUM→NONE→MEDIUM flicker from resetting the clock.
            int effectiveThreat = peakThreatDuringSequence;
            
            // Determine required sustained motion based on threat level:
            // - THREAT_HIGH (loitering): Only need base delay (500ms).
            // - THREAT_MEDIUM (approaching): Require loitering time setting.
            long requiredDuration = (effectiveThreat >= MotionPipelineV2.THREAT_HIGH)
                    ? SUSTAINED_MOTION_BASE_MS
                    : loiteringTimeMs;
            
            // --- Diagnostic: Log sustained motion progress ---
            if (motionDuration > 0 && motionDuration < requiredDuration) {
                // Log every second while waiting
                if (motionDuration % 1000 < MOTION_PROCESS_INTERVAL_MS) {
                    String[] threatNames = {"NONE", "LOW(pass)", "MEDIUM(approach)", "HIGH(loiter)"};
                    int bestQ = pipelineV2.getHighestThreatQuadrant();
                    MotionPipelineV2.QuadrantResult bestR = bestQ >= 0 ? results[bestQ] : null;
                    float estDist = bestQ >= 0 && bestR != null ? estimateDistanceFromCentroid(bestQ, bestR.centroidY) : -1;
                    logger.info(String.format("Motion building: %.1fs / %.1fs | threat=%s | dist=~%.1fm | loiterSetting=%ds",
                            motionDuration / 1000.0, requiredDuration / 1000.0,
                            threatNames[maxThreat], estDist, (int)(loiteringTimeMs / 1000)));
                }
            }
            
            // FIX: Early AI initialization — queue YOLO on active quadrants as soon as
            // motion is detected, not after the loitering timer expires. This lets the
            // EventTimelineCollector build contextual history (person vs car vs bike)
            // BEFORE the MP4 write is triggered. Without this, if someone leaves the
            // frame right at the trigger threshold, YOLO runs on an empty frame and the
            // JSON sidecar records a generic "motion" event instead of classifying it.
            if (useObjectDetection && !isAiRunning.get() && aiQuadrantQueueIsEmpty()) {
                int bestQ = pipelineV2.getHighestThreatQuadrant();
                if (bestQ >= 0) aiQuadrantQueueAdd(bestQ);
                for (int q = 0; q < MotionPipelineV2.NUM_QUADRANTS; q++) {
                    if (q != bestQ && results[q].motionDetected) {
                        aiQuadrantQueueAdd(q);
                    }
                }
                // Kick off AI immediately if cooldown allows
                if (!aiQuadrantQueueIsEmpty() && (System.currentTimeMillis() - lastAiTimeMs) >= AI_COOLDOWN_MS) {
                    runAiOnQuadrant(smallRgbFrame, aiQuadrantQueuePoll());
                }
            }
            
            if (motionDuration >= requiredDuration) {
                inActiveMode = true;
                
                // Filter debug log
                if (filterDebugEnabled) {
                    int bestQ = pipelineV2.getHighestThreatQuadrant();
                    String qName = bestQ >= 0 ? MotionPipelineV2.QUADRANT_NAMES[bestQ] : "?";
                    String[] threatNames = {"NONE", "LOW", "MEDIUM", "HIGH"};
                    MotionPipelineV2.QuadrantResult r = bestQ >= 0 ? results[bestQ] : null;
                    float estDist = bestQ >= 0 && r != null ? estimateDistanceFromCentroid(bestQ, r.centroidY) : -1;
                    addFilterLogEntry(String.format("[%s] TRIGGER: %s threat=%s dist=~%.1fm active=%d confirmed=%d component=%d sustained=%.1fs",
                            new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date(now)),
                            qName, threatNames[maxThreat], estDist,
                            r != null ? r.activeBlocks : 0, r != null ? r.confirmedBlocks : 0,
                            r != null ? r.componentSize : 0, motionDuration / 1000.0));
                }
                
                if (!recording) {
                    // AI CONFIRMATION GATE: For THREAT_MEDIUM, require YOLO to have confirmed
                    // a real object during this motion sequence before committing a recording.
                    // This prevents lighting artifacts (streetlights, porch lights, deterrent
                    // flashes, slow headlight sweeps) from triggering false recordings.
                    //
                    // For THREAT_HIGH (loitering), only gate during the deterrent window —
                    // loitering is confirmed by 10+ seconds of centroid analysis, which is
                    // strong enough evidence on its own. But the deterrent's own light creates
                    // a static centroid that mimics loitering, so we gate it there.
                    //
                    // TIMEOUT FALLBACK: If YOLO hasn't confirmed within 2 seconds past the
                    // required sustained duration, let it through anyway. This handles:
                    // - YOLO model not loaded (useObjectDetection=false)
                    // - YOLO busy on another quadrant (AI cooldown)
                    // - Object too small/dark for YOLO but real (motion evidence sufficient)
                    //
                    // The 2-second grace is safe because YOLO gets queued at motion start
                    // (early AI init). If it hasn't confirmed in 5+ seconds of motion, the
                    // object is genuinely undetectable by YOLO and motion evidence alone
                    // must be trusted.
                    boolean deterrentActive = deterrentFiredTime > 0 
                            && (now - deterrentFiredTime) < DETERRENT_SUPPRESSION_MS;
                    boolean aiRecentlyConfirmed = lastAiConfirmationTimeMs >= firstMotionTime;  // Confirmed during THIS sequence
                    boolean aiAvailable = useObjectDetection && yoloDetector != null;
                    long timePastRequired = motionDuration - requiredDuration;  // How long past the trigger threshold
                    
                    // TIMEOUT FALLBACK: Let motion through if YOLO hasn't confirmed in time.
                    // BUT: If brightness suppression fired during this motion sequence, the
                    // motion is likely a lighting artifact. In that case, extend the timeout
                    // to the full deterrent window (5s) — persistent lights (streetlights)
                    // create motion that lasts indefinitely, so a short timeout would let
                    // them through. If it's a real person in changing light, YOLO WILL see
                    // them within 5 seconds (multiple inference opportunities).
                    boolean brightnessEventDuringSequence = false;
                    for (int q = 0; q < MotionPipelineV2.NUM_QUADRANTS; q++) {
                        if (suppressionWasActive[q] || results[q].brightnessSuppressed) {
                            brightnessEventDuringSequence = true;
                            break;
                        }
                    }
                    long timeoutMs = brightnessEventDuringSequence ? DETERRENT_SUPPRESSION_MS : 2000;
                    boolean timeoutExpired = timePastRequired > timeoutMs;
                    
                    boolean shouldSuppress = false;
                    if (aiAvailable && !aiRecentlyConfirmed && !timeoutExpired) {
                        if (effectiveThreat <= MotionPipelineV2.THREAT_MEDIUM) {
                            // MEDIUM: always require AI confirmation (with timeout fallback)
                            shouldSuppress = true;
                        } else if (deterrentActive) {
                            // HIGH during deterrent: require AI confirmation (deterrent mimics loitering)
                            shouldSuppress = true;
                        }
                    }
                    // NO-YOLO DETERRENT FALLBACK: When object detection is not available
                    // (daemon mode without Context/AssetManager), the AI gate can't function.
                    // But we still know when OUR OWN deterrent fired. Use a pure time-based
                    // suppression: block new recordings for the full deterrent window after
                    // we fired the lights. This prevents the exact scenario from the logs:
                    // deterrent fires → light flash → motion re-triggers → second recording.
                    // Without YOLO there's no way to confirm "is this a real person or just
                    // our own lights?" so we err on the side of suppressing false positives.
                    // Real threats that arrive during the 5s window will still be caught
                    // because the first recording's post-record (10s) covers the gap.
                    if (!aiAvailable && deterrentActive && !recording) {
                        shouldSuppress = true;
                        if (frameCount % 50 == 0) {
                            logger.debug(String.format(
                                "No-YOLO deterrent guard: suppressing (deterrent %.1fs ago, no AI available)",
                                (now - deterrentFiredTime) / 1000.0));
                        }
                        firstMotionTime = 0;
                        peakThreatDuringSequence = 0;
                    }
                    
                    if (shouldSuppress) {
                        if (frameCount % 50 == 0) {
                            String[] tNames = {"NONE", "LOW", "MEDIUM", "HIGH"};
                            logger.debug(String.format(
                                "AI gate holding: threat=%s, motion=%.1fs, grace=%.1fs remaining, deterrent=%s, brightnessEvent=%s",
                                tNames[effectiveThreat], motionDuration / 1000.0,
                                Math.max(0, timeoutMs - timePastRequired) / 1000.0,
                                deterrentActive ? "active" : "inactive",
                                brightnessEventDuringSequence ? "yes" : "no"));
                        }
                        // Don't reset firstMotionTime — let the timer keep running.
                        // When YOLO confirms (or timeout expires), the trigger fires immediately.
                    }
                    // SOTA: Event stitching — if new motion appears shortly after the last
                    // recording stopped, start a new recording immediately. The previous
                    // recentlyStoppedRecording cooldown blocked new recordings for the entire
                    // postRecordMs window after a stop, causing missed events when someone
                    // lingered near the car. The 3-second sustained motion requirement already
                    // prevents rapid-fire false triggers, so the cooldown is unnecessary.
                    else {
                        motionDetections++;
                        int bestQ = pipelineV2.getHighestThreatQuadrant();
                        // If no quadrant has motion (e.g., tracker held through flash),
                        // fall back to the quadrant with an active tracker lock
                        if (bestQ < 0) {
                            for (int tq = 0; tq < MotionPipelineV2.NUM_QUADRANTS; tq++) {
                                try {
                                    if (NativeMotion.trackerHasActiveTrack(tq)) {
                                        bestQ = tq;
                                        break;
                                    }
                                } catch (Exception ignored) {}
                            }
                        }
                        String qName = bestQ >= 0 ? MotionPipelineV2.QUADRANT_NAMES[bestQ] : "?";
                        String triggerSource = (pipelineV2.getMaxThreatLevel() >= MotionPipelineV2.THREAT_MEDIUM) 
                                ? "motion" : "tracker";
                        String[] threatNames = {"NONE", "LOW(pass)", "MEDIUM(approach)", "HIGH(loiter)"};
                        MotionPipelineV2.QuadrantResult bestResult = bestQ >= 0 ? results[bestQ] : null;
                        
                        // Estimate distance from centroid position
                        float estDist = bestQ >= 0 && bestResult != null ? 
                                estimateDistanceFromCentroid(bestQ, bestResult.centroidY) : -1;
                        String distStr = estDist > 0 ? String.format("~%.1fm", estDist) : "unknown";
                        
                        String detectionZone = config != null ? config.getDetectionZone() : "?";
                        int sensitivityLevel = config != null ? config.getSensitivityLevel() : -1;
                        int loiteringSec = config != null ? config.getLoiteringTimeSeconds() : -1;
                        int maxRow = pipelineV2Config != null ? pipelineV2Config.maxDistanceRow : 0;
                        float zoneLimitDist = bestQ >= 0 ? estimateDistanceFromCentroid(bestQ, maxRow) : -1;
                        
                        logger.info(String.format(
                            ">>> RECORDING TRIGGERED <<<\n" +
                            "  Camera: %s | Threat: %s | Distance: %s | Sustained: %.1fs | Source: %s\n" +
                            "  Blocks: active=%d, confirmed=%d, component=%d\n" +
                            "  Settings: sensitivity=%d, zone=%s (limit %s), loiterTime=%ds\n" +
                            "  Why: threat %s >= MEDIUM ✓, duration %.1fs >= %.1fs ✓, distance %s within zone ✓",
                            qName, threatNames[maxThreat], distStr, motionDuration / 1000.0, triggerSource,
                            bestResult != null ? bestResult.activeBlocks : 0,
                            bestResult != null ? bestResult.confirmedBlocks : 0,
                            bestResult != null ? bestResult.componentSize : 0,
                            sensitivityLevel, detectionZone,
                            maxRow > 0 ? String.format("~%.1fm", zoneLimitDist) : "none",
                            loiteringSec,
                            threatNames[maxThreat], motionDuration / 1000.0, requiredDuration / 1000.0,
                            distStr));
                        
                        recordingStopTime = now + postRecordMs;
                        startRecording();
                        
                        try {
                            String videoFilename = currentEventFile != null ? currentEventFile.getName() : null;
                            sendRichMotionNotifications(videoFilename);
                            publishMotionNotification(videoFilename);
                        } catch (Exception e) {
                            logger.warn("Failed to send motion notification: " + e.getMessage());
                        }

                        // SOTA: Fire BYD cloud deterrent (flash lights / find car)
                        // Runs on background thread, never blocks surveillance pipeline
                        try {
                            com.overdrive.app.byd.cloud.BydCloudDeterrent.getInstance().onMotionDetected();
                            deterrentFiredTime = now;  // Track when deterrent was dispatched
                        } catch (Exception e) {
                            logger.debug("Deterrent dispatch failed: " + e.getMessage());
                        }
                    }
                } else {
                    // Already recording — extend recording timer on continued motion.
                    // Any quadrant with MEDIUM+ threat extends the recording.
                    long newStopTime = now + postRecordMs;
                    if (newStopTime > recordingStopTime) {
                        recordingStopTime = newStopTime;
                    }
                    
                    // SOTA: Recurring deterrent — re-trigger while motion continues.
                    // The cooldown inside BydCloudDeterrent prevents spamming (default 15s).
                    try {
                        com.overdrive.app.byd.cloud.BydCloudDeterrent.getInstance().onMotionDetected();
                        deterrentFiredTime = now;  // Track latest deterrent dispatch
                    } catch (Exception e) {
                        // Fail silently — never block surveillance
                    }
                    
                    // Also run YOLO on new quadrants that have motion (even if different from original)
                    if (useObjectDetection && !isAiRunning.get()) {
                        for (int q = 0; q < MotionPipelineV2.NUM_QUADRANTS; q++) {
                            if (results[q].motionDetected && results[q].threatLevel >= MotionPipelineV2.THREAT_MEDIUM) {
                                aiQuadrantQueueAdd(q);  // dedups internally
                            }
                        }
                        // FIX: Check cooldown before consuming queue item
                        if (!aiQuadrantQueueIsEmpty() && (System.currentTimeMillis() - lastAiTimeMs) >= AI_COOLDOWN_MS) {
                            runAiOnQuadrant(smallRgbFrame, aiQuadrantQueuePoll());
                        }
                    }
                }

                // Staggered YOLO: queue active quadrants for AI detection
                if (useObjectDetection && !isAiRunning.get()) {
                    aiQuadrantQueueClear();
                    // Add quadrants sorted by threat level (highest first)
                    int bestQ = pipelineV2.getHighestThreatQuadrant();
                    if (bestQ >= 0) aiQuadrantQueueAdd(bestQ);
                    for (int q = 0; q < MotionPipelineV2.NUM_QUADRANTS; q++) {
                        if (q != bestQ && results[q].motionDetected) {
                            aiQuadrantQueueAdd(q);
                        }
                    }
                    // FIX: Check cooldown before consuming queue item
                    if (!aiQuadrantQueueIsEmpty() && (System.currentTimeMillis() - lastAiTimeMs) >= AI_COOLDOWN_MS) {
                        runAiOnQuadrant(smallRgbFrame, aiQuadrantQueuePoll());
                    }
                }
            }
        } else {
            // No motion detected on this frame (all quadrants below MEDIUM threat).
            // Don't immediately end the sequence — allow gaps up to 2 seconds.
            // A person walking past creates motion bursts with brief gaps as they
            // move between quadrants or between block boundaries. A 500ms gap was
            // too tight and caused the sequence to reset before reaching the trigger.
            if (!recording) {
                long timeSinceLastMotion = now - lastMotionTime;
                
                // SOTA: Extend gap tolerance during cross-quadrant transit.
                // When a person walks from the left camera to the rear camera, there's
                // a brief gap where neither camera has MEDIUM+ threat (left is decaying,
                // rear hasn't confirmed yet). Without this fix, the 2-second timeout
                // resets firstMotionTime, and the rear camera starts a fresh sequence
                // from zero — the person's total approach time is never accumulated.
                //
                // If the texture tracker has an active track, we know an object is still
                // physically present. Extend the gap tolerance to 4 seconds to bridge
                // the cross-quadrant handoff. Also check for any quadrant with active
                // blocks (even below MEDIUM threat) as a secondary signal.
                //
                // FIX: Also extend tolerance if YOLO is currently running or queued.
                // The tracker is started inside the async YOLO lambda. If motion drops
                // before the lambda executes, trackerHasActiveTrack returns false even
                // though YOLO is about to classify the person and start a track.
                boolean trackerActive = false;
                boolean anyLowActivity = false;
                boolean aiPending = isAiRunning.get() || !aiQuadrantQueueIsEmpty();
                // SOTA: If YOLO confirmed a person during this motion sequence, extend
                // gap tolerance. The person may have briefly moved between block boundaries
                // or between quadrants, causing motion to drop below MEDIUM. But YOLO
                // already verified they're real — don't kill the sequence prematurely.
                boolean aiConfirmedDuringSequence = (firstMotionTime > 0) 
                        && (lastAiConfirmationTimeMs >= firstMotionTime);
                for (int q = 0; q < MotionPipelineV2.NUM_QUADRANTS; q++) {
                    try {
                        if (NativeMotion.trackerHasActiveTrack(q)) {
                            float[] trackBox = NativeMotion.trackerGetTrackBox(q);
                            if (trackBox != null && (int) trackBox[5] == 0) { // person only
                                trackerActive = true;
                            }
                        }
                    } catch (Exception ignored) {}
                    if (results[q].activeBlocks > 0) anyLowActivity = true;
                }
                long gapTolerance = (trackerActive || anyLowActivity || aiPending || aiConfirmedDuringSequence) ? 4000 : 2000;
                
                // DEFERRED TRIGGER: If the motion duration already exceeded the threshold
                // during this sequence AND YOLO confirmed a real person, trigger NOW even
                // though the current frame has no MEDIUM+ motion. This catches the case
                // where the person is still present (YOLO confirmed) but motion blocks
                // briefly dipped below MEDIUM on the exact frame where duration crossed
                // the threshold. Without this, the sequence dies at gap tolerance expiry
                // even though all conditions were met.
                if (firstMotionTime != 0 && !recording && aiConfirmedDuringSequence) {
                    long motionDuration = lastMotionTime - firstMotionTime;
                    long requiredMs = (peakThreatDuringSequence >= MotionPipelineV2.THREAT_HIGH)
                            ? SUSTAINED_MOTION_BASE_MS : loiteringTimeMs;
                    if (motionDuration >= requiredMs && peakThreatDuringSequence >= MotionPipelineV2.THREAT_MEDIUM) {
                        // All conditions met: duration exceeded, threat was MEDIUM+, YOLO confirmed person
                        logger.info(String.format("DEFERRED TRIGGER: motion=%.1fs >= %.1fs, AI confirmed, triggering from gap phase",
                                motionDuration / 1000.0, requiredMs / 1000.0));
                        inActiveMode = true;
                        motionDetections++;
                        int bestQ = pipelineV2.getHighestThreatQuadrant();
                        if (bestQ < 0) {
                            // No quadrant has motion right now — use the last known active quadrant
                            for (int tq = 0; tq < MotionPipelineV2.NUM_QUADRANTS; tq++) {
                                try {
                                    if (NativeMotion.trackerHasActiveTrack(tq)) { bestQ = tq; break; }
                                } catch (Exception ignored) {}
                            }
                        }
                        recordingStopTime = now + postRecordMs;
                        startRecording();
                        try {
                            String videoFilename = currentEventFile != null ? currentEventFile.getName() : null;
                            sendRichMotionNotifications(videoFilename);
                            publishMotionNotification(videoFilename);
                        } catch (Exception e) {
                            logger.warn("Failed to send motion notification: " + e.getMessage());
                        }
                        try {
                            com.overdrive.app.byd.cloud.BydCloudDeterrent.getInstance().onMotionDetected();
                            deterrentFiredTime = now;
                        } catch (Exception e) {
                            logger.debug("Deterrent dispatch failed: " + e.getMessage());
                        }
                    }
                }

                if (firstMotionTime != 0 && timeSinceLastMotion > gapTolerance) {
                    // Motion sequence ended without triggering
                    long motionDuration = lastMotionTime - firstMotionTime;
                    if (motionDuration > 200) {
                        String[] threatNames = {"NONE", "LOW(pass)", "MEDIUM(approach)", "HIGH(loiter)"};
                        long requiredMs = (peakThreatDuringSequence >= MotionPipelineV2.THREAT_HIGH)
                                ? SUSTAINED_MOTION_BASE_MS : loiteringTimeMs;
                        logger.info(String.format("Motion ended WITHOUT trigger: lasted=%.1fs, peakThreat=%s, required=%.1fs, gapTolerance=%.1fs%s",
                                motionDuration / 1000.0, threatNames[peakThreatDuringSequence],
                                requiredMs / 1000.0, gapTolerance / 1000.0,
                                trackerActive ? " (tracker was active)" : ""));
                    }
                    firstMotionTime = 0;
                    peakThreatDuringSequence = 0;
                }
            }
        }
        
        // Post-record check: stop recording when no motion for postRecordMs.
        // SOTA: Also check ANY quadrant for activity (not just MEDIUM+ threat).
        // A person standing still near the car produces minimal block changes but
        // is still a valid reason to keep recording. Use a lower threshold:
        // any quadrant with confirmedBlocks > 0 counts as "activity" for post-record.
        if (recording && now >= recordingStopTime && recordingStopTime > 0) {
            // Check if any quadrant has residual activity (even below MEDIUM threat)
            boolean anyActivity = false;
            for (int q = 0; q < MotionPipelineV2.NUM_QUADRANTS; q++) {
                if (results[q].confirmedBlocks > 0 || results[q].activeBlocks > 0) {
                    anyActivity = true;
                    break;
                }
            }
            
            // SOTA: Also check texture tracker — a person standing still produces
            // zero motion blocks but the NCC tracker holds a lock on their pixel texture.
            // This is the "Static Foreground Victory" — recording stays alive as long as
            // the tracked object is present, even with zero motion pipeline activity.
            // FIX: Only person tracks (classId==0) can hold recording open.
            // Parked vehicles (motorcycles, cars) would otherwise keep recording
            // indefinitely via the "hitchhiker" pattern.
            boolean trackerHolding = false;
            for (int q = 0; q < MotionPipelineV2.NUM_QUADRANTS; q++) {
                try {
                    if (NativeMotion.trackerHasActiveTrack(q)) {
                        float[] trackBox = NativeMotion.trackerGetTrackBox(q);
                        if (trackBox != null && (int) trackBox[5] == 0) { // class 0 = person only
                            trackerHolding = true;
                            break;
                        }
                    }
                } catch (Exception ignored) {}
            }
            
            if (anyActivity || trackerHolding) {
                // Still some activity or tracker holding — extend recording
                recordingStopTime = now + postRecordMs;
                if (trackerHolding && !anyActivity && frameCount % 100 == 0) {
                    logger.info("Post-record extended by texture tracker (no motion, object still present)");
                }
            } else {
                long timeSinceLastMotion = now - lastMotionTime;
                if (timeSinceLastMotion >= postRecordMs) {
                    logger.info(String.format("V2 post-record complete — stopping (no motion for %.1fs)",
                            timeSinceLastMotion / 1000.0));
                    stopRecording();
                    recordingStopTime = 0;
                    firstMotionTime = 0;
                    peakThreatDuringSequence = 0;
                }
            }
        }
        
        // SOTA: Update texture tracker on every frame (runs NCC template matching).
        // This is the core of the decoupled tracking — YOLO sleeps, NCC tracks.
        // Also handles YOLO heartbeat: when NCC confidence drops below 0.60 or
        // 3 seconds have elapsed, the tracker requests YOLO re-verification.
        if (recording) {
            for (int q = 0; q < MotionPipelineV2.NUM_QUADRANTS; q++) {
                try {
                    if (NativeMotion.trackerHasActiveTrack(q)) {
                        // Feed the quadrant crop to the tracker
                        int qW = THUMBNAIL_WIDTH / 2;
                        int qH = THUMBNAIL_HEIGHT / 2;
                        byte[] quadCrop = cropFromMosaic(smallRgbFrame, q, qW, qH);
                        if (quadCrop != null) {
                            NativeMotion.trackerUpdate(quadCrop, qW, qH, q, now);
                        }
                        
                        // Check if tracker wants YOLO heartbeat (NCC score dropped or timer expired).
                        // FIX: Enforce a hard 2-second cooldown per quadrant to prevent heartbeat spam.
                        // Without this, a failing NCC tracker fires needsYoloHeartbeat=true on every
                        // single frame, turning YOLO into a 10 FPS continuous detector and destroying
                        // the battery savings of the decoupled architecture.
                        if (NativeMotion.trackerNeedsYoloHeartbeat(q)) {
                            long timeSinceLastHeartbeat = now - lastHeartbeatTimeMs[q];
                            if (timeSinceLastHeartbeat >= HEARTBEAT_COOLDOWN_MS
                                    && useObjectDetection && !isAiRunning.get()) {
                                aiQuadrantQueueAdd(q);  // dedups internally
                                lastHeartbeatTimeMs[q] = now;
                                logger.info("Tracker heartbeat: waking YOLO for Q" + q +
                                        " [" + MotionPipelineV2.QUADRANT_NAMES[q] + "]");
                            }
                        }
                    }
                } catch (Exception e) {
                    // Tracker not available — continue without it
                }
            }
        }
        
        // Process staggered YOLO queue (one per frame)
        // FIX: Check cooldown BEFORE polling the queue. Previously, poll() consumed
        // the quadrant, then runAiOnQuadrant's internal cooldown check rejected it —
        // permanently vaporizing that quadrant's AI pass.
        if (useObjectDetection && !isAiRunning.get() && !aiQuadrantQueueIsEmpty()) {
            if ((System.currentTimeMillis() - lastAiTimeMs) >= AI_COOLDOWN_MS) {
                runAiOnQuadrant(smallRgbFrame, aiQuadrantQueuePoll());
            }
        }
        
        // Periodic stats
        if (frameCount % 500 == 0) {
            logger.info(String.format("V2 stats: frames=%d, motions=%d, recording=%b",
                    frameCount, motionDetections, recording));
            // Log per-quadrant status for debugging
            for (int q = 0; q < MotionPipelineV2.NUM_QUADRANTS; q++) {
                MotionPipelineV2.QuadrantResult r = results[q];
                String status = r.brightnessSuppressed ? "SUPPRESSED" : 
                    (r.motionDetected ? "MOTION(t=" + r.threatLevel + ")" : "quiet");
                logger.info(String.format("  Q%d[%s]: %s active=%d confirmed=%d component=%d luma=%.0f",
                        q, MotionPipelineV2.QUADRANT_NAMES[q], status,
                        r.activeBlocks, r.confirmedBlocks, r.componentSize, r.meanLuma));
            }
            
            // SOTA: Auto day/night mode switch based on ambient light.
            // The BYD's camera ISP boosts ISO at night, pushing mean luma to ~75-85.
            // Daytime luma sits at ~115-130. The threshold of 95 cleanly splits the two.
            // Night mode relaxes edge/shadow thresholds to handle ISO noise and headlights.
            float avgLuma = 0;
            int lumaCount = 0;
            for (int q = 0; q < MotionPipelineV2.NUM_QUADRANTS; q++) {
                if (results[q].meanLuma > 0) {
                    avgLuma += results[q].meanLuma;
                    lumaCount++;
                }
            }
            if (lumaCount > 0) {
                avgLuma /= lumaCount;
                
                // Java-level auto-exposure: simple day/night switch for global params.
                // Threshold scaling is handled per-quadrant in C++ with relative multipliers.
                boolean shouldBeNight = avgLuma < 90.0f;
                boolean currentlyNight = isNightMode();
                
                if (shouldBeNight != currentlyNight && pipelineV2Config != null) {
                    // Restore base global params from user's preset
                    String preset = config != null ? config.getEnvironmentPreset() : "outdoor";
                    MotionPipelineV2.Config tempCfg = new MotionPipelineV2.Config();
                    tempCfg.applyEnvironmentPreset(preset);
                    
                    pipelineV2Config.brightnessShiftThreshold = tempCfg.brightnessShiftThreshold;
                    pipelineV2Config.brightnessSuppressionFrames = tempCfg.brightnessSuppressionFrames;
                    pipelineV2Config.shadowFilterMode = tempCfg.shadowFilterMode;
                    pipelineV2Config.chromaRatioTolerance = tempCfg.chromaRatioTolerance;
                    pipelineV2Config.shadowPixelFraction = tempCfg.shadowPixelFraction;
                    pipelineV2Config.oscillationThreshold = tempCfg.oscillationThreshold;
                    
                    if (shouldBeNight) {
                        pipelineV2Config.brightnessShiftThreshold = 0.35f;
                        pipelineV2Config.brightnessSuppressionFrames = 8;
                        pipelineV2Config.shadowFilterMode = 1;  // LIGHT
                        pipelineV2Config.chromaRatioTolerance = 0.25f;
                        pipelineV2Config.shadowPixelFraction = 0.7f;
                        pipelineV2Config.oscillationThreshold = 4;
                        setNightMode(true);
                        logger.info(String.format("Auto NIGHT mode (avgLuma=%.0f < 95)", avgLuma));
                    } else {
                        setNightMode(false);
                        logger.info(String.format("Auto NORMAL mode (avgLuma=%.0f >= 95)", avgLuma));
                    }
                    pipelineV2.applyConfig(pipelineV2Config);
                    
                    // SOTA: Refresh detection baseline on lighting transition.
                    // Dawn/dusk changes affect detector confidence scores and object
                    // appearance. Refresh all quadrants to keep baseline accurate.
                    // Cost: 4 inferences, happens 2-3 times per night.
                    // Runs on AI executor thread to avoid blocking motion pipeline.
                    if (baselineSeeded && useObjectDetection && yoloDetector != null) {
                        logger.info("Queuing detection baseline refresh (lighting transition)...");
                        final byte[] frameSnapshot = new byte[smallRgbFrame.length];
                        System.arraycopy(smallRgbFrame, 0, frameSnapshot, 0, smallRgbFrame.length);
                        aiExecutor.execute(() -> {
                            // FIX (A8/B3): snapshot detector at lambda entry.
                            final YoloDetector detectorSnap = yoloDetector;
                            if (detectorSnap == null || !aiEnabled) {
                                logger.info("Lighting-transition baseline refresh skipped (detector closed)");
                                return;
                            }
                            logger.info("Refreshing detection baseline (lighting transition)...");
                            int qW = THUMBNAIL_WIDTH / 2;
                            int qH = THUMBNAIL_HEIGHT / 2;
                            for (int qr = 0; qr < MotionPipelineV2.NUM_QUADRANTS; qr++) {
                                try {
                                    byte[] quadCrop = cropFromMosaic(frameSnapshot, qr, qW, qH);
                                    if (quadCrop != null) {
                                        java.util.List<com.overdrive.app.ai.Detection> dets =
                                                detectorSnap.detect(quadCrop, qW, qH, aiConfidence, true, true, false, true, minObjectSize);
                                        detectionBaseline.refreshQuadrant(qr, dets, qW, qH);
                                    }
                                } catch (Exception e) {
                                    logger.warn("Baseline refresh failed for Q" + qr + ": " + e.getMessage());
                                }
                            }
                        });
                    }
                }
            }
            
            // Log suppressed quadrants for debug
            if (filterDebugEnabled) {
                for (int q = 0; q < MotionPipelineV2.NUM_QUADRANTS; q++) {
                    if (results[q].brightnessSuppressed) {
                        addFilterLogEntry(String.format("[%s] SUPPRESSED: %s (brightness shift, luma=%.0f)",
                                new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date(now)),
                                MotionPipelineV2.QUADRANT_NAMES[q], results[q].meanLuma));
                    }
                }
            }
            
            // POST-SUPPRESSION BASELINE REFRESH: Track per-quadrant suppression state.
            // When suppression ends and the scene stabilizes (15 frames later), run YOLO
            // once to update the baseline with what's currently visible. This prevents
            // the "4 objects in day, 3 visible at night" mismatch from causing false triggers.
            if (baselineSeeded && useObjectDetection && yoloDetector != null) {
                for (int q = 0; q < MotionPipelineV2.NUM_QUADRANTS; q++) {
                    if (results[q].brightnessSuppressed) {
                        // Suppression is active — mark it and reset the stabilization counter
                        suppressionWasActive[q] = true;
                        framesSinceSuppressionEnded[q] = 0;
                        baselineRefreshQueued[q] = false;
                    } else if (suppressionWasActive[q]) {
                        // Suppression just ended — start counting stabilization frames
                        framesSinceSuppressionEnded[q]++;
                        
                        if (framesSinceSuppressionEnded[q] >= BASELINE_STABILIZATION_FRAMES) {
                            // Scene has stabilized — always clear the flag to prevent
                            // permanently extending the AI gate timeout on future sequences.
                            suppressionWasActive[q] = false;
                            
                            if (!baselineRefreshQueued[q] && !recording) {
                                // Queue a baseline refresh for this quadrant.
                                // Don't refresh during active recording (the person is still there,
                                // we don't want to accidentally add them to baseline).
                                baselineRefreshQueued[q] = true;
                            
                                final int qToRefresh = q;
                                final byte[] frameSnapshot = new byte[smallRgbFrame.length];
                                System.arraycopy(smallRgbFrame, 0, frameSnapshot, 0, smallRgbFrame.length);
                            
                                aiExecutor.execute(() -> {
                                    // FIX (A8/B3): snapshot detector at lambda entry.
                                    final YoloDetector detectorSnap = yoloDetector;
                                    if (detectorSnap == null || !aiEnabled) {
                                        logger.debug("Post-suppression baseline refresh skipped (detector closed)");
                                        return;
                                    }
                                    try {
                                        int qW = THUMBNAIL_WIDTH / 2;
                                        int qH = THUMBNAIL_HEIGHT / 2;
                                        byte[] quadCrop = cropFromMosaic(frameSnapshot, qToRefresh, qW, qH);
                                        if (quadCrop != null) {
                                            java.util.List<com.overdrive.app.ai.Detection> dets =
                                                    detectorSnap.detect(quadCrop, qW, qH, aiConfidence, true, true, false, true, minObjectSize);
                                            detectionBaseline.refreshQuadrant(qToRefresh, dets, qW, qH);
                                            logger.debug("Post-suppression baseline refresh Q" + qToRefresh +
                                                    " [" + MotionPipelineV2.QUADRANT_NAMES[qToRefresh] + "]: " +
                                                    (dets != null ? dets.size() : 0) + " detections");
                                        }
                                    } catch (Exception e) {
                                        logger.warn("Post-suppression baseline refresh failed Q" + qToRefresh + ": " + e.getMessage());
                                    }
                                });
                            }
                        }
                    }
                }
            }
        }
    }
    
    /**
     * Run YOLO on a single quadrant (cropped from the mosaic).
     */
    private void runAiOnQuadrant(byte[] mosaicRgb, int quadrant) {
        // FIX (Bug B): respect user's class toggles. Empty classFilter = sentinel for
        // "all classes disabled" — skip YOLO entirely. Saves ~50-80ms per quadrant per
        // wake event and frees the TFLite interpreter.
        if (!useObjectDetection) return;
        if (!aiEnabled || (classFilter != null && classFilter.length == 0)) return;
        if (yoloDetector == null) return;
        if (isAiRunning.get()) return;
        
        long now = System.currentTimeMillis();
        if ((now - lastAiTimeMs) < AI_COOLDOWN_MS) return;
        lastAiTimeMs = now;
        
        // Determine crop dimensions and data source.
        // If foveated cropper is available, extract a 640×640 window from the raw
        // 5120×960 strip centered on the motion centroid. This gives YOLO ~4× more
        // pixels per object compared to the 320×240 mosaic quadrant.
        final int qW;
        final int qH;
        final byte[] cropData;
        
        MotionPipelineV2.QuadrantResult motionResult = pipelineV2 != null ? pipelineV2.getResults()[quadrant] : null;
        
        // For heartbeat runs, the person may be stationary (zero motion blocks).
        // Use the tracker's last known position as the centroid for foveated crop
        // instead of requiring active motion blocks from the V2 pipeline.
        boolean heartbeatHasTrackerPos = false;
        float trackerCentroidX = 0, trackerCentroidY = 0;
        try {
            float[] trackBox = NativeMotion.trackerGetTrackBox(quadrant);
            if (trackBox != null && trackBox[6] > 0) {  // trackBox[6] = active flag
                // Convert tracker bbox (pixel coords) to block coords for foveated crop
                trackerCentroidX = (trackBox[0] + trackBox[2] / 2.0f) / 32.0f;
                trackerCentroidY = (trackBox[1] + trackBox[3] / 2.0f) / 32.0f;
                heartbeatHasTrackerPos = true;
            }
        } catch (Exception ignored) {}
        
        if (foveatedCropper != null && foveatedCropper.isInitialized() && cameraTextureId >= 0
                && ((motionResult != null && motionResult.componentSize > 0) || heartbeatHasTrackerPos)) {
            // Foveated path: 640×640 from raw strip. crop() touches GL state
            // (FBO bind, glReadPixels) and must run on the GL thread. Since
            // processFrame runs on AiLaneWorker, we hop to GL and wait
            // synchronously. Round-trip is ~1-5ms so this doesn't meaningfully
            // affect the AI lane budget.
            float centroidX = (motionResult != null && motionResult.componentSize > 0)
                    ? motionResult.centroidX : trackerCentroidX;
            float centroidY = (motionResult != null && motionResult.componentSize > 0)
                    ? motionResult.centroidY : trackerCentroidY;
            byte[] foveatedRgb = cropOnGlThread(quadrant, centroidX, centroidY);
            if (foveatedRgb != null) {
                qW = FoveatedCropper.CROP_SIZE;
                qH = FoveatedCropper.CROP_SIZE;
                // Must copy — foveatedCropper reuses its internal buffer
                cropData = new byte[foveatedRgb.length];
                System.arraycopy(foveatedRgb, 0, cropData, 0, foveatedRgb.length);
            } else {
                // Foveated crop failed — fall back to mosaic crop. Copy because
                // cropFromMosaic returns a pointer to the shared aiBuffer which
                // gets overwritten by subsequent main-thread tracker updates and
                // mosaicQuadCrop captures (line ~1591) before the aiExecutor
                // lambda runs. Without this copy, the lambda's YOLO inference +
                // ThumbnailBuffer.observe see a stale or wrong-quadrant frame —
                // the visible symptom being the bbox drawn on the hero image
                // landing on the wrong part of the thumbnail.
                qW = THUMBNAIL_WIDTH / 2;
                qH = THUMBNAIL_HEIGHT / 2;
                byte[] mosaicShared = cropFromMosaic(mosaicRgb, quadrant, qW, qH);
                cropData = new byte[mosaicShared.length];
                System.arraycopy(mosaicShared, 0, cropData, 0, mosaicShared.length);
            }
        } else {
            // Legacy path: 320×240 from mosaic. See note above — must copy.
            qW = THUMBNAIL_WIDTH / 2;
            qH = THUMBNAIL_HEIGHT / 2;
            byte[] mosaicShared = cropFromMosaic(mosaicRgb, quadrant, qW, qH);
            cropData = new byte[mosaicShared.length];
            System.arraycopy(mosaicShared, 0, cropData, 0, mosaicShared.length);
        }
        
        if (cropData == null) return;
        
        isAiRunning.set(true);
        final int qIdx = quadrant;
        
        // FIX: Snapshot block confidences on the main thread BEFORE dispatching to aiExecutor.
        // The live pipelineV2.getResults() array is mutated by the JNI backend on every frame.
        // By the time the aiExecutor thread runs (150-300ms later), the main loop has processed
        // 2-3 new frames. If the person briefly stopped, confirmedBlocks will be 0 on the new
        // frame, and a valid YOLO detection gets thrown away because it doesn't overlap with
        // the "current" empty motion mask. Deep-copy the confidence array now.
        final float[] blockConfSnapshot = new float[MotionPipelineV2.TOTAL_BLOCKS];
        final int snapshotConfirmedBlocks;
        if (pipelineV2 != null) {
            MotionPipelineV2.QuadrantResult snapResult = pipelineV2.getResults()[qIdx];
            System.arraycopy(snapResult.blockConfidence, 0, blockConfSnapshot, 0, MotionPipelineV2.TOTAL_BLOCKS);
            snapshotConfirmedBlocks = snapResult.confirmedBlocks;
        } else {
            snapshotConfirmedBlocks = 0;
        }
        
        final boolean usedFoveated = (qW == FoveatedCropper.CROP_SIZE);
        
        // Capture whether this YOLO run is a heartbeat verification BEFORE the lambda.
        // The C++ tracker's needsYoloVerification flag is mutated by trackerUpdate() on
        // every frame. By the time the aiExecutor lambda runs (100-200ms later), the flag
        // state may have changed, causing the lambda to confirm the wrong quadrant.
        boolean heartbeatCheck = false;
        try {
            heartbeatCheck = NativeMotion.trackerNeedsYoloHeartbeat(quadrant)
                    && NativeMotion.trackerHasActiveTrack(quadrant);
        } catch (Exception e) {
            // Tracker not available
        }
        final boolean isHeartbeatRun = heartbeatCheck;

        // FIX (B1/H-a): capture the recording generation NOW. The lambda below
        // will check it on completion and skip cross-recording writes if the
        // generation has advanced (i.e. stopRecording fired in the meantime).
        final long generationAtSchedule = recordingGeneration.get();
        
        // Capture mosaic quadrant crop for the texture tracker (always 320×240).
        // The tracker needs the mosaic-scale image regardless of whether YOLO used foveated.
        final byte[] mosaicQuadCrop;
        {
            int mqW = THUMBNAIL_WIDTH / 2;
            int mqH = THUMBNAIL_HEIGHT / 2;
            byte[] tmp = cropFromMosaic(mosaicRgb, quadrant, mqW, mqH);
            if (tmp != null) {
                mosaicQuadCrop = new byte[tmp.length];
                System.arraycopy(tmp, 0, mosaicQuadCrop, 0, tmp.length);
            } else {
                mosaicQuadCrop = null;
            }
        }
        
        aiExecutor.execute(() -> {
            try {
                // FIX (A8/B3): Snapshot the detector reference at lambda entry.
                // The line-1450 guard runs on the calling thread BEFORE this
                // lambda is scheduled. Between scheduling and execution, the UI
                // thread can call setObjectFilters() which closes the
                // interpreter and nulls yoloDetector. Without this snapshot,
                // the .detect() call below would NPE on a null field — or
                // worse, race against close() and crash the native interpreter.
                final YoloDetector detectorSnap = yoloDetector;
                if (detectorSnap == null || !aiEnabled) {
                    isAiRunning.set(false);
                    return;
                }

                boolean detectPerson = true, detectCar = true, detectBike = true;
                if (classFilter != null && classFilter.length > 0) {
                    detectPerson = false; detectCar = false; detectBike = false;
                    for (int cls : classFilter) {
                        if (cls == 0) detectPerson = true;
                        if (cls == 2 || cls == 5 || cls == 7) detectCar = true;
                        if (cls == 1 || cls == 3) detectBike = true;
                    }
                }
                
                java.util.List<com.overdrive.app.ai.Detection> detections = detectorSnap.detect(
                        cropData, qW, qH, aiConfidence, detectPerson, detectCar, false, detectBike, minObjectSize);
                
                // Track how many motion-filtered detections we found (accessible outside the block
                // for the teardown gate that kills zombie tracks when YOLO returns empty)
                int motionFilteredCount = 0;
                
                if (detections != null && !detections.isEmpty()) {
                    // Filter detections: only keep objects that overlap with active motion blocks.
                    // A static parked car detected by YOLO should be ignored if no motion blocks
                    // overlap with it. Only the moving person (whose bounding box overlaps with
                    // active motion blocks) should be reported to the timeline.
                    // FIX: Use the snapshot taken on the main thread, NOT the live pipeline results.
                    // The live results have been mutated by 2-3 frames by now.
                    
                    java.util.List<com.overdrive.app.ai.Detection> motionFiltered = new java.util.ArrayList<>();
                    for (com.overdrive.app.ai.Detection det : detections) {
                        int classId = det.getClassId();
                        
                        // Respect user's class filter settings.
                        // Only keep detections for classes the user has enabled.
                        if (classFilter != null && classFilter.length > 0) {
                            boolean classAllowed = false;
                            for (int allowedCls : classFilter) {
                                if (classId == allowedCls) {
                                    classAllowed = true;
                                    break;
                                }
                            }
                            if (!classAllowed) continue;
                        } else {
                            // No filter set — only allow known relevant classes
                            if (classId != 0 && classId != 1 && classId != 2 && 
                                classId != 3 && classId != 5 && classId != 7) continue;
                        }
                        
                        // Check if detection overlaps with any confirmed motion blocks
                        // using the SNAPSHOT taken on the main thread.
                        // When using foveated crop (640×640), detection coords are in a different
                        // coordinate space than the block grid (320×240 with 32px blocks).
                        // Scale detection coords to match the block grid.
                        //
                        // FIX: During a heartbeat run, BYPASS the spatial filter entirely.
                        // The whole point of the heartbeat is to verify a STATIONARY person
                        // who has zero motion blocks. If we require motion-block overlap,
                        // the heartbeat will always fail for stationary objects, the teardown
                        // gate will kill the track, and the recording will stop even though
                        // the person is still standing right there.
                        // Spatial filter: check if detection overlaps with active motion blocks.
                        // Heartbeat bypass: persons (class 0) skip the spatial check because
                        // a stationary person has zero motion blocks but is a real threat.
                        // Vehicles during heartbeat still require motion blocks — a parked car
                        // is never a threat and will hold the recording open forever otherwise.
                        boolean passesFilter = false;
                        
                        if (isHeartbeatRun && classId == 0) {
                            // Heartbeat + person: bypass spatial filter
                            passesFilter = true;
                        } else if (snapshotConfirmedBlocks > 0) {
                            // Normal path: require overlap with active motion blocks
                            float scaleX = usedFoveated ? (320.0f / FoveatedCropper.CROP_SIZE) : 1.0f;
                            float scaleY = usedFoveated ? (240.0f / FoveatedCropper.CROP_SIZE) : 1.0f;
                            int detLeft = (int)(det.getX() * scaleX);
                            int detTop = (int)(det.getY() * scaleY);
                            int detRight = (int)((det.getX() + det.getW()) * scaleX);
                            int detBottom = (int)((det.getY() + det.getH()) * scaleY);
                            
                            for (int bi = 0; bi < MotionPipelineV2.TOTAL_BLOCKS; bi++) {
                                if (blockConfSnapshot[bi] < 0.5f) continue;
                                
                                int bx = (bi % MotionPipelineV2.GRID_COLS) * 32;
                                int by = (bi / MotionPipelineV2.GRID_COLS) * 32;
                                int bRight = bx + 32;
                                int bBottom = by + 32;
                                
                                if (detLeft < bRight && detRight > bx && detTop < bBottom && detBottom > by) {
                                    passesFilter = true;
                                    break;
                                }
                            }
                        } else {
                            // No motion data available — keep all detections (fallback)
                            passesFilter = true;
                        }
                        
                        if (passesFilter) {
                            motionFiltered.add(det);
                        }
                    }
                    
                    int relevantCount = motionFiltered.size();
                    motionFilteredCount = relevantCount;
                    
                    if (relevantCount > 0) {
                        // SOTA: Record person detections for spatial veto baseline tracking.
                        // This must happen BEFORE baseline filtering so the person positions
                        // are available for the spatial veto check during event-end update.
                        int qWNorm = usedFoveated ? FoveatedCropper.CROP_SIZE : (THUMBNAIL_WIDTH / 2);
                        int qHNorm = usedFoveated ? FoveatedCropper.CROP_SIZE : (THUMBNAIL_HEIGHT / 2);
                        for (com.overdrive.app.ai.Detection det : motionFiltered) {
                            if (det.getClassId() == 0) {  // person
                                detectionBaseline.recordPersonDetection(qIdx, det, qWNorm, qHNorm);
                            }
                        }
                        
                        // SOTA: Filter detections against baseline — suppress known static objects.
                        // Only NEW or MOVED objects pass through. This eliminates false recordings
                        // from shadows/headlights that trigger motion near parked cars or trash cans.
                        // Skip baseline filtering for person detections (class 0) — a person is
                        // never a legitimate static background object for a sentry system.
                        java.util.List<com.overdrive.app.ai.Detection> baselineFiltered = new java.util.ArrayList<>();
                        int baselineSuppressed = 0;
                        for (com.overdrive.app.ai.Detection det : motionFiltered) {
                            if (det.getClassId() == 0) {
                                // Person — always pass through, never check baseline
                                baselineFiltered.add(det);
                            } else if (detectionBaseline.isInBaseline(det, qIdx, qWNorm, qHNorm)) {
                                // Known static object — suppress
                                baselineSuppressed++;
                            } else {
                                // New or moved non-person object — pass through
                                baselineFiltered.add(det);
                            }
                        }
                        
                        if (baselineSuppressed > 0) {
                            logger.info("Baseline filter Q" + qIdx + ": " + baselineSuppressed + 
                                    " static objects suppressed, " + baselineFiltered.size() + " new/moved pass");
                        }
                        
                        // Store last detections for event-end baseline update (use unfiltered
                        // motionFiltered list — baseline update needs to see ALL objects including
                        // those that were suppressed, so it can maintain/update their entries)
                        lastYoloDetections.set(qIdx, new java.util.ArrayList<>(motionFiltered));
                        lastEventQuadrant = qIdx;
                        
                        // THREAT-LEVEL DECISION MATRIX (AI background subtraction gate):
                        //
                        // THREAT_LOW:    Require YOLO to find a non-baseline object. If all
                        //                detections are known static objects → suppress entirely.
                        //
                        // THREAT_MEDIUM: Require YOLO to find a non-baseline object. This prevents
                        //                lighting artifacts (streetlights, porch lights, slow
                        //                headlight sweeps below brightness threshold) from triggering
                        //                recordings. These create persistent edge differences that
                        //                Stage 5 classifies as "approaching" because the centroid
                        //                drifts slightly as shadows shift.
                        //
                        // THREAT_HIGH:   Always record — loitering is confirmed by centroid analysis
                        //                over loiteringFrames (10+ seconds). If something physically
                        //                moves in the same spot for that long, it's real regardless
                        //                of what YOLO thinks. YOLO at 320×240 can miss small/dark
                        //                targets that the motion pipeline detects fine.
                        //
                        // Safety: The motion pipeline already queues YOLO at the START of motion
                        // (early AI init, line ~752). By the time MEDIUM's 3-second sustained
                        // timer expires, YOLO has had 2.7+ seconds to run. If baselineFiltered
                        // is empty, it means YOLO ran and found nothing real — the "motion" is
                        // a lighting artifact.
                        int currentThreat = pipelineV2 != null ? pipelineV2.getMaxThreatLevel() : MotionPipelineV2.THREAT_MEDIUM;
                        if (currentThreat <= MotionPipelineV2.THREAT_MEDIUM && baselineFiltered.isEmpty()) {
                            // THREAT_LOW or MEDIUM + all detections are known static objects → suppress
                            String[] tNames = {"NONE", "LOW", "MEDIUM", "HIGH"};
                            logger.info("AI gate: " + tNames[currentThreat] + " + no new objects → suppressing for Q" + qIdx);
                            relevantCount = 0;
                            motionFilteredCount = 0;
                        } else {
                            // Use baseline-filtered detections for downstream processing
                            motionFiltered = baselineFiltered;
                            relevantCount = motionFiltered.size();
                            motionFilteredCount = relevantCount;
                        }
                    }
                    
                    if (relevantCount > 0) {
                        // YOLO confirmed a real object — update AI confirmation timestamp.
                        // This is used by the deterrent flash guard to allow recording
                        // even during the suppression window if YOLO sees a real threat.
                        lastAiConfirmationTimeMs = System.currentTimeMillis();
                        
                        long timeSinceMotion = System.currentTimeMillis() - lastMotionTime;
                        if (timeSinceMotion < 2000) {
                            lastMotionTime = System.currentTimeMillis();
                        }
                        
                        boolean hasActiveMotion = timeSinceMotion < 2000;
                        // Always send to timeline — pre-trigger ring buffer captures
                        // events before recording starts for the JSON sidecar.
                        timelineCollector.onAiDetection(motionFiltered, hasActiveMotion, 1 << qIdx);
                        
                        // Cross-quadrant tracking REQUIRES bboxes in 320×240 quadrant
                        // pixel space — its centroid threshold (dist < 120) and edge
                        // margin (48 px) are hardcoded against Q_WIDTH=320 / Q_HEIGHT=240
                        // (CrossQuadrantTracker.java:59-60). When foveated, rescale a
                        // separate copy for CQT only; the tracker needs centroids, not
                        // bboxes, and rescaling distorts that minimally.
                        java.util.List<com.overdrive.app.ai.Detection> cqtDetections;
                        if (usedFoveated) {
                            cqtDetections = new java.util.ArrayList<>(motionFiltered.size());
                            float scaleToQuad = 320.0f / FoveatedCropper.CROP_SIZE;  // 0.5
                            for (com.overdrive.app.ai.Detection det : motionFiltered) {
                                cqtDetections.add(new com.overdrive.app.ai.Detection(
                                        det.getClassId(),
                                        det.getConfidence(),
                                        (int)(det.getX() * scaleToQuad),
                                        (int)(det.getY() * scaleToQuad),
                                        (int)(det.getW() * scaleToQuad),
                                        (int)(det.getH() * scaleToQuad)
                                ));
                            }
                        } else {
                            cqtDetections = motionFiltered;
                        }

                        java.util.List<CrossQuadrantTracker.TrackResult> tracked =
                                crossQuadrantTracker.processDetections(cqtDetections, qIdx);

                        // ActorTracker + ThumbnailBuffer want bboxes in cropData's
                        // NATIVE coord space so the bbox-vs-rgb pair stays coherent
                        // when ThumbnailBuffer draws the box on the hero JPEG. In
                        // foveated mode that's 640×640 (motionFiltered's native space);
                        // in mosaic mode, 320×240 (also motionFiltered's native space).
                        // Either way, motionFiltered is what we want — NOT cqtDetections,
                        // which is forced to 320 for CQT's hardcoded thresholds.
                        java.util.List<com.overdrive.app.ai.Detection> trackableDetections = motionFiltered;

                        // Build a parallel array of cross-quadrant track IDs to
                        // hand to ActorTracker. This binds the per-quadrant
                        // ActorTracker to the cross-camera identity assigned by
                        // CrossQuadrantTracker, so a person walking front→right
                        // doesn't get two actorIds. The arrays line up by index
                        // because processDetections returns one TrackResult per
                        // input Detection in order — and motionFiltered + cqtDetections
                        // share the same iteration order so indices stay aligned.
                        int[] xqTrackIds = new int[trackableDetections.size()];
                        for (int ti = 0; ti < tracked.size() && ti < xqTrackIds.length; ti++) {
                            xqTrackIds[ti] = tracked.get(ti).trackId;
                        }

                        // Actor layer: convert YOLO detections (in cropData's native
                        // coord space) into persistent Actor records. Snapshot is
                        // published as lastActors for downstream consumers (timeline,
                        // thumbnails, notifications, UI). Recording-relative timestamps
                        // require the recording start time which we look up from the
                        // recorder.
                        try {
                            // FIX (B1/H-a): if stopRecording bumped the generation
                            // while we were running, our writes belong to a
                            // recording that's already finalised. Skip them so
                            // we don't pollute the *next* recording's state.
                            if (recordingGeneration.get() != generationAtSchedule) {
                                logger.debug("AI lambda completed after recording stop (gen "
                                        + generationAtSchedule + " vs " + recordingGeneration.get()
                                        + ") — skipping Actor/Thumbnail writes");
                            } else {
                            long recordingStartWall = (timelineCollector != null && timelineCollector.isCollecting())
                                    ? timelineCollector.getRecordingStartTimeMs() : 0L;
                            // Pass the ACTUAL crop dims (qW × qH) used for this YOLO
                            // run. trackableDetections (motionFiltered) is in qW × qH
                            // pixel space, so the proximity ratio (bboxH / quadH) is
                            // self-consistent. ThumbnailBuffer also receives qW × qH
                            // and the same bbox space — bbox draws on the right pixels.
                            java.util.List<Actor> actorSnapshot = actorTracker.update(
                                    trackableDetections,
                                    xqTrackIds,
                                    qIdx,
                                    qW,
                                    qH,
                                    recordingStartWall,
                                    System.currentTimeMillis());
                            lastActors = actorSnapshot;
                            // Forward to thumbnail buffer so it can capture the peak-severity frame.
                            // Block C wires this; safe no-op if buffer not yet attached.
                            if (thumbnailBuffer != null && cropData != null) {
                                thumbnailBuffer.observe(actorSnapshot, cropData, qW, qH, qIdx);
                            }
                            // Mid-event baseline promotion: if the Actor layer
                            // has classified a vehicle / non-person actor as
                            // static, promote it into the baseline now so the
                            // *next* motion event suppresses it without waiting
                            // for stopRecording → updateFromEventEnd. Closes
                            // the loop with the Actor tracker's "static" flag.
                            for (Actor a : actorSnapshot) {
                                if (!a.isStatic) continue;
                                if (a.classGroup == Actor.ClassGroup.PERSON
                                        || a.classGroup == Actor.ClassGroup.ANIMAL
                                        || a.classGroup == Actor.ClassGroup.UNKNOWN) continue;
                                int cocoCls;
                                switch (a.classGroup) {
                                    case VEHICLE: cocoCls = 2; break;  // car
                                    case BIKE:    cocoCls = 1; break;  // bicycle
                                    default: continue;
                                }
                                // Baseline promotion only on mosaic frames.
                                // The DetectionBaseline normalizes coords to
                                // [0,1] of the QUADRANT for stable cross-event
                                // comparison. Foveated crops are a moving 640×640
                                // window centered on motion centroid, so their
                                // normalized coords don't refer to a stable
                                // physical region — promoting a foveated bbox
                                // would seed an entry that nothing in the next
                                // event can possibly match. Mosaic frames are
                                // the full quadrant downscaled, which is the
                                // stable reference baseline expects.
                                if (usedFoveated) continue;
                                detectionBaseline.promoteStaticActor(qIdx, cocoCls,
                                        a.lastBboxX, a.lastBboxY, a.lastBboxW, a.lastBboxH,
                                        qW, qH);
                            }
                            }  // close else { generation guard
                        } catch (Exception aEx) {
                            logger.warn("ActorTracker.update failed: " + aEx.getMessage());
                        }
                        
                        // SOTA: Start/refresh texture tracker on the highest-confidence detection.
                        // YOLO's job is done — the NCC tracker takes over frame-by-frame
                        // tracking. YOLO only wakes up again on heartbeat or NCC score drop.
                        if (!trackableDetections.isEmpty() && mosaicQuadCrop != null) {
                            com.overdrive.app.ai.Detection best = trackableDetections.get(0);
                            for (com.overdrive.app.ai.Detection d : trackableDetections) {
                                if (d.getConfidence() > best.getConfidence()) best = d;
                            }
                            try {
                                // FIX: Use the pre-captured isHeartbeatRun flag, NOT a live
                                // call to trackerNeedsYoloHeartbeat(). The live flag is mutated
                                // by trackerUpdate() on the main thread between when we queued
                                // this quadrant and when the lambda executes (100-200ms later).
                                if (isHeartbeatRun) {
                                    // SEMANTIC LOCK: The track was born with a specific classId.
                                    // If YOLO now sees a different class, the tracker has morphed
                                    // onto a background object (e.g., person → parked car).
                                    // Reject the heartbeat and let the track die.
                                    float[] trackBox = NativeMotion.trackerGetTrackBox(qIdx);
                                    int trackClassId = (trackBox != null) ? (int) trackBox[5] : -1;
                                    
                                    if (trackClassId >= 0 && best.getClassId() != trackClassId
                                            && best.getConfidence() > 0.70f) {
                                        // Semantic mismatch with high confidence — tracker morphed
                                        // onto a different object (e.g., person → parked car).
                                        // Require >70% confidence to avoid killing tracks on
                                        // low-confidence misclassifications (person torso → bus).
                                        logger.info("Semantic mismatch: track Q" + qIdx + 
                                                " born as class " + trackClassId + 
                                                " but YOLO sees class " + best.getClassId() + 
                                                " @" + String.format("%.0f%%", best.getConfidence() * 100) +
                                                " — killing track");
                                        NativeMotion.trackerDropTrack(qIdx);
                                        NativeMotion.trackerConfirmHeartbeat(qIdx, System.currentTimeMillis());
                                    } else {
                                        // Class matches — refresh the template
                                        NativeMotion.trackerRefreshTemplate(
                                                mosaicQuadCrop, THUMBNAIL_WIDTH / 2, THUMBNAIL_HEIGHT / 2,
                                                qIdx,
                                                best.getX(), best.getY(), best.getW(), best.getH(),
                                                System.currentTimeMillis());
                                        NativeMotion.trackerConfirmHeartbeat(qIdx, System.currentTimeMillis());
                                        logger.info("Tracker heartbeat confirmed: refreshed template for Q" + qIdx +
                                                " [" + MotionPipelineV2.QUADRANT_NAMES[qIdx] + "]");
                                    }
                                } else {
                                    // First detection: start a new track
                                    NativeMotion.trackerStartTrack(
                                            mosaicQuadCrop, THUMBNAIL_WIDTH / 2, THUMBNAIL_HEIGHT / 2,
                                            qIdx, best.getClassId(),
                                            best.getX(), best.getY(), best.getW(), best.getH(),
                                            System.currentTimeMillis());
                                }
                            } catch (Exception e) {
                                logger.warn("Tracker start/refresh failed: " + e.getMessage());
                            }
                        } else if (trackableDetections.isEmpty() && isHeartbeatRun) {
                            // (Moved to teardown gate outside the detections block)
                        }
                        
                        String qName = MotionPipelineV2.QUADRANT_NAMES[qIdx];
                        String cropMode = usedFoveated ? "foveated 640×640" : "mosaic 320×240";
                        logger.info(String.format("V2 AI [%s] (%s): %d objects (motion-filtered from %d), %d tracks",
                                qName, cropMode, relevantCount, detections.size(),
                                crossQuadrantTracker.getActiveTrackCount()));
                    }
                }
                
                // TEARDOWN GATE: When YOLO returns 0 objects during a heartbeat,
                // the object has left the scene. Kill the zombie track immediately.
                // Previously this was nested inside the `if (!detections.isEmpty())` block,
                // so it never executed when YOLO returned empty — the track stayed alive
                // forever, spamming heartbeats on every frame.
                if (isHeartbeatRun && (detections == null || detections.isEmpty()
                        || motionFilteredCount == 0)) {
                    try {
                        if (NativeMotion.trackerHasActiveTrack(qIdx)) {
                            NativeMotion.trackerDropTrack(qIdx);
                            NativeMotion.trackerConfirmHeartbeat(qIdx, System.currentTimeMillis());
                            logger.info("Tracker teardown: YOLO heartbeat found nothing, killed track Q" + qIdx +
                                    " [" + MotionPipelineV2.QUADRANT_NAMES[qIdx] + "]");
                        }
                    } catch (Exception ignored) {}
                }
            } catch (Exception e) {
                logger.error("V2 AI detection error (Q" + qIdx + ")", e);
            } finally {
                isAiRunning.set(false);
            }
        });
    }
    
    /**
     * Crop a quadrant from the 640×480 mosaic into the reusable aiBuffer.
     * Legacy path used when foveated cropper is not available.
     */
    private byte[] cropFromMosaic(byte[] mosaicRgb, int quadrant, int qW, int qH) {
        int startX = (quadrant % 2) * qW;
        int startY = (quadrant / 2) * qH;

        int cropSize = qW * qH * BYTES_PER_PIXEL;
        byte[] aiBuffer = aiBufferTL.get();
        if (aiBuffer == null || aiBuffer.length != cropSize) {
            aiBuffer = new byte[cropSize];
            aiBufferTL.set(aiBuffer);
        }

        for (int y = 0; y < qH; y++) {
            int srcOffset = ((startY + y) * THUMBNAIL_WIDTH + startX) * BYTES_PER_PIXEL;
            int dstOffset = y * qW * BYTES_PER_PIXEL;
            System.arraycopy(mosaicRgb, srcOffset, aiBuffer, dstOffset, qW * BYTES_PER_PIXEL);
        }
        return aiBuffer;
    }
    
    /**
     * Sets the Region of Interest (ROI) mask for motion detection.
     * 
     * @param mask Byte array (320×240) where 1 = check motion, 0 = ignore
     *             Pass null to use entire frame (default)
     */
    public void setRoiMask(byte[] mask) {
        if (mask != null && mask.length != THUMBNAIL_WIDTH * THUMBNAIL_HEIGHT) {
            logger.error("Invalid ROI mask size: " + mask.length + 
                       " (expected " + (THUMBNAIL_WIDTH * THUMBNAIL_HEIGHT) + ")");
            return;
        }
        
        this.roiMask = mask;
        
        // Count pixels in ROI for normalization
        if (mask != null) {
            roiPixelCount = 0;
            for (byte b : mask) {
                if (b != 0) roiPixelCount++;
            }
            logger.info("ROI mask set: " + roiPixelCount + " pixels (" + 
                      (roiPixelCount * 100 / (THUMBNAIL_WIDTH * THUMBNAIL_HEIGHT)) + "%)");
        } else {
            roiPixelCount = THUMBNAIL_WIDTH * THUMBNAIL_HEIGHT;
            logger.info("ROI mask cleared (using full frame)");
        }
    }
    
    /**
     * Sets ROI from polygon points (normalized 0.0-1.0 coordinates).
     * 
     * @param points Array of [x, y] pairs defining polygon vertices
     */
    public void setRoiFromPolygon(float[][] points) {
        if (points == null || points.length < 3) {
            setRoiMask(null);  // Clear ROI
            return;
        }
        
        // Create mask by rasterizing polygon
        byte[] mask = new byte[THUMBNAIL_WIDTH * THUMBNAIL_HEIGHT];
        
        for (int y = 0; y < THUMBNAIL_HEIGHT; y++) {
            for (int x = 0; x < THUMBNAIL_WIDTH; x++) {
                float nx = (float) x / THUMBNAIL_WIDTH;
                float ny = (float) y / THUMBNAIL_HEIGHT;
                
                // Point-in-polygon test (ray casting algorithm)
                if (isPointInPolygon(nx, ny, points)) {
                    mask[y * THUMBNAIL_WIDTH + x] = 1;
                }
            }
        }
        
        setRoiMask(mask);
    }
    
    /**
     * Point-in-polygon test using ray casting.
     */
    private boolean isPointInPolygon(float x, float y, float[][] polygon) {
        boolean inside = false;
        int n = polygon.length;
        
        for (int i = 0, j = n - 1; i < n; j = i++) {
            float xi = polygon[i][0], yi = polygon[i][1];
            float xj = polygon[j][0], yj = polygon[j][1];
            
            if (((yi > y) != (yj > y)) && (x < (xj - xi) * (y - yi) / (yj - yi) + xi)) {
                inside = !inside;
            }
        }
        
        return inside;
    }
    
    // ========================================================================
    // Per-Quadrant ROI (Region of Interest)
    // ========================================================================
    
    /**
     * Applies a polygon ROI to a specific quadrant.
     * Converts the polygon (normalized 0-1 coords) to a 10×7 block mask
     * and passes it to the C++ pipeline via JNI.
     *
     * @param quadrant Quadrant index (0-3)
     * @param polygon  Array of [x, y] vertex pairs in normalized coords (0.0-1.0)
     */
    public void applyQuadrantRoi(int quadrant, float[][] polygon) {
        if (quadrant < 0 || quadrant >= MotionPipelineV2.NUM_QUADRANTS) return;
        if (polygon == null || polygon.length < 3) {
            clearQuadrantRoi(quadrant);
            return;
        }
        
        // Convert polygon to 10×7 block mask.
        // For each block, check if its center is inside the polygon.
        byte[] blockMask = new byte[MotionPipelineV2.TOTAL_BLOCKS];
        int enabledCount = 0;
        
        for (int by = 0; by < MotionPipelineV2.GRID_ROWS; by++) {
            for (int bx = 0; bx < MotionPipelineV2.GRID_COLS; bx++) {
                int blockIdx = by * MotionPipelineV2.GRID_COLS + bx;
                // Block center in normalized coordinates
                float cx = (bx + 0.5f) / MotionPipelineV2.GRID_COLS;
                float cy = (by + 0.5f) / MotionPipelineV2.GRID_ROWS;
                
                if (isPointInPolygon(cx, cy, polygon)) {
                    blockMask[blockIdx] = 1;
                    enabledCount++;
                }
            }
        }
        
        try {
            NativeMotion.setQuadrantRoi(quadrant, blockMask);
            logger.info("ROI applied to Q" + quadrant + " [" + 
                    MotionPipelineV2.QUADRANT_NAMES[quadrant] + "]: " + 
                    enabledCount + "/" + MotionPipelineV2.TOTAL_BLOCKS + " blocks enabled");
        } catch (Exception e) {
            logger.warn("Failed to apply ROI to Q" + quadrant + ": " + e.getMessage());
        }
    }
    
    /**
     * Clears the ROI for a specific quadrant (all blocks enabled).
     */
    public void clearQuadrantRoi(int quadrant) {
        if (quadrant < 0 || quadrant >= MotionPipelineV2.NUM_QUADRANTS) return;
        try {
            NativeMotion.setQuadrantRoi(quadrant, null);
            logger.info("ROI cleared for Q" + quadrant + " [" + 
                    MotionPipelineV2.QUADRANT_NAMES[quadrant] + "] (all blocks enabled)");
        } catch (Exception e) {
            logger.warn("Failed to clear ROI for Q" + quadrant + ": " + e.getMessage());
        }
    }
    
    /**
     * Sets the SOTA surveillance configuration.
     * 
     * @param config Configuration object with distance preset, flash mode, and camera calibration
     */
    public void setConfig(SurveillanceConfig config) {
        this.config = config;

        // Sync legacy fields for backward compatibility
        this.flashImmunity = config.getFlashImmunity();
        this.requiredActiveBlocks = config.getRequiredBlocks();
        this.minObjectSize = config.getMinObjectSize();
        this.aiConfidence = config.getAiConfidence();
        this.preRecordMs = config.getPreRecordSeconds() * 1000L;
        this.postRecordMs = config.getPostRecordSeconds() * 1000L;

        // FIX (Bug A): propagate the loaded pre-record duration to the encoder's
        // circular buffer. Without this the encoder retains its hardcoded 5s
        // allocation from init() until the user re-saves the setting, which makes
        // the persisted value look like it was reset.
        if (recorder != null && recorder.getEncoder() != null) {
            try {
                recorder.getEncoder().setPreRecordDuration(config.getPreRecordSeconds());
            } catch (Exception e) {
                logger.warn("Failed to propagate pre-record duration to encoder: " + e.getMessage());
            }
        }
        
        // Sync loitering time for Java-side sustained motion enforcement
        this.loiteringTimeMs = config.getLoiteringTimeSeconds() * 1000L;
        
        // Update frame dimensions in config for distance estimation
        config.setResolution(THUMBNAIL_WIDTH, THUMBNAIL_HEIGHT);
        config.setIsMosaic(true);  // We use 2x2 mosaic layout
        
        // Apply object detection filters from saved config.
        // This rebuilds the classFilter array so YOLO respects detectPerson/detectCar/detectBike.
        setObjectFilters(config.getMinObjectSize(), config.getAiConfidence(),
                config.isDetectPerson(), config.isDetectCar(), config.isDetectBike());
        
        // Apply V2 pipeline settings from loaded config.
        // Order matters: environment preset sets all defaults, then sensitivity and
        // detection zone override their specific parameters, then loitering and cameras.
        if (pipelineV2Config != null && pipelineV2 != null) {
            pipelineV2Config.applyEnvironmentPreset(config.getEnvironmentPreset());

            // The native pipeline runs once with a single config. To honor
            // per-quadrant overrides we feed the *most-permissive* aggregate
            // (highest sensitivity, widest detection zone) to native, then
            // demote per-quadrant in Java via applyQuadrantOverrides().
            int aggSens = config.getSensitivityLevel();
            String aggZone = config.getDetectionZone();
            for (int q = 0; q < 4; q++) {
                aggSens = Math.max(aggSens, config.getEffectiveSensitivityLevel(q));
                if ("extended".equals(config.getEffectiveDetectionZone(q))) {
                    aggZone = "extended";
                } else if ("normal".equals(config.getEffectiveDetectionZone(q))
                        && "close".equals(aggZone)) {
                    aggZone = "normal";
                }
            }
            pipelineV2Config.applySensitivity(aggSens);
            pipelineV2Config.applyDetectionZone(aggZone);
            pipelineV2Config.loiteringFrames = config.getLoiteringTimeSeconds() * 10;
            // Apply saved shadow filter mode (after preset, so user override takes precedence)
            pipelineV2Config.shadowFilterMode = config.getShadowFilterMode();
            boolean[] cameras = config.getCameraEnabled();
            for (int i = 0; i < 4; i++) {
                pipelineV2Config.quadrantEnabled[i] = cameras[i];
            }
            pipelineV2.applyConfig(pipelineV2Config);
            logger.info(String.format("V2 pipeline config applied: env=%s, sens=%d (agg=%d), zone=%s (agg=%s), loiter=%ds, cameras=[%b,%b,%b,%b]",
                    config.getEnvironmentPreset(), config.getSensitivityLevel(), aggSens,
                    config.getDetectionZone(), aggZone,
                    config.getLoiteringTimeSeconds(), cameras[0], cameras[1], cameras[2], cameras[3]));
        }
        
        // Apply filter debug setting
        this.filterDebugEnabled = config.isFilterDebugLogEnabled();
        
        // Apply per-quadrant ROI from config (if surveillance is active, apply immediately)
        if (active) {
            for (int q = 0; q < MotionPipelineV2.NUM_QUADRANTS; q++) {
                if (config.isRoiEnabled(q) && config.getRoiPolygon(q) != null) {
                    applyQuadrantRoi(q, config.getRoiPolygon(q));
                } else {
                    clearQuadrantRoi(q);
                }
            }
        }
        
        logger.info("Config applied: " + config.toString());
    }
    
    /**
     * Gets the current SOTA configuration.
     * 
     * @return Current configuration
     */
    public SurveillanceConfig getConfig() {
        return config;
    }
    
    /**
     * Gets the last estimated distance to motion.
     * 
     * @return Distance in meters, or 0 if no motion detected
     */
    public float getLastEstimatedDistance() {
        return lastEstimatedDistance;
    }

    /**
     * Snapshot of currently-active Actors. Lock-free read suitable for UI / API
     * threads. May be empty if no detections have been observed yet.
     */
    public java.util.List<Actor> getLastActors() {
        return lastActors;
    }
    
    /**
     * Re-evaluate each quadrant's result against its effective (possibly
     * overridden) sensitivity / zone gates. The native pipeline already ran
     * with the most-permissive aggregate config, so we only ever demote — we
     * never falsely promote, so this can't synthesize motion that the native
     * stage didn't see.
     *
     * Demotion clears motionDetected, threatLevel, confirmedBlocks, and
     * componentSize. activeBlocks and per-block confidences are preserved for
     * diagnostics.
     */
    private void applyQuadrantOverrides(MotionPipelineV2.QuadrantResult[] results) {
        if (config == null || results == null) return;

        // Fast path: nothing overridden → skip entirely.
        boolean anyOverride = false;
        for (int q = 0; q < MotionPipelineV2.NUM_QUADRANTS; q++) {
            if (config.getQuadrantSensitivityOverride(q) != null
                    || config.getQuadrantDetectionZoneOverride(q) != null) {
                anyOverride = true;
                break;
            }
        }
        if (!anyOverride) return;

        for (int q = 0; q < MotionPipelineV2.NUM_QUADRANTS; q++) {
            MotionPipelineV2.QuadrantResult r = results[q];
            if (r == null || !r.motionDetected) continue;

            int effSens = config.getEffectiveSensitivityLevel(q);
            String effZone = config.getEffectiveDetectionZone(q);
            MotionPipelineV2.Config.GateThresholds gates =
                    MotionPipelineV2.Config.gatesForSensitivity(effSens);
            int maxRow = MotionPipelineV2.Config.maxDistanceRowForZone(effZone);

            // Recount confirmed blocks at this quadrant's stricter confidence threshold.
            int confirmedAtThreshold = 0;
            if (r.blockConfidence != null) {
                for (int i = 0; i < r.blockConfidence.length; i++) {
                    if (r.blockConfidence[i] >= gates.confidenceThreshold) confirmedAtThreshold++;
                }
            } else {
                confirmedAtThreshold = r.confirmedBlocks;
            }

            boolean failsAlarm = confirmedAtThreshold < gates.alarmBlockThreshold;
            boolean failsComponent = r.componentSize < gates.minComponentSize;
            boolean failsZone = (maxRow > 0) && (r.centroidY < maxRow);

            if (failsAlarm || failsComponent || failsZone) {
                r.motionDetected = false;
                r.threatLevel = MotionPipelineV2.THREAT_NONE;
                r.confirmedBlocks = confirmedAtThreshold;
                if (filterDebugEnabled) {
                    String reason = failsAlarm ? "alarm" : failsComponent ? "component" : "zone";
                    logger.debug(String.format(
                            "  [%s] OVERRIDE_DEMOTED reason=%s sens=%d zone=%s confirmed@%.2f=%d/%d component=%d/%d centroidRow=%.1f/cutoff=%d",
                            MotionPipelineV2.QUADRANT_NAMES[q], reason, effSens, effZone,
                            gates.confidenceThreshold, confirmedAtThreshold, gates.alarmBlockThreshold,
                            r.componentSize, gates.minComponentSize, r.centroidY, maxRow));
                }
            } else {
                // Pass: update confirmedBlocks to reflect the stricter count
                // so downstream consumers see consistent numbers.
                r.confirmedBlocks = confirmedAtThreshold;
            }
        }
    }

    /**
     * Estimate real-world distance from a centroid Y position in block coordinates.
     * 
     * The centroid Y is in block coordinates (0 = top row / far, GRID_ROWS-1 = bottom / close).
     * We convert to pixel coordinates in the full mosaic, then use SurveillanceConfig's
     * camera calibration to estimate distance in meters.
     * 
     * @param quadrant Quadrant index (0=front, 1=right, 2=rear, 3=left)
     * @param centroidBlockY Centroid Y in block coordinates (0-6)
     * @return Estimated distance in meters, or -1 if unavailable
     */
    private float estimateDistanceFromCentroid(int quadrant, float centroidBlockY) {
        if (config == null) return -1;
        
        // Convert block Y to pixel Y within the quadrant
        // Block size is 32px, centroid is center of the block cluster
        float pixelY = centroidBlockY * GRID_BLOCK_SIZE + (GRID_BLOCK_SIZE / 2.0f);
        
        // Convert quadrant-local pixel Y to global mosaic Y
        // Mosaic layout: top row = quadrants 0,1; bottom row = quadrants 2,3
        int quadrantOffsetY = (quadrant >= 2) ? (THUMBNAIL_HEIGHT / 2) : 0;
        int globalY = quadrantOffsetY + (int) pixelY;
        
        return config.estimateDistanceForQuadrant(quadrant, globalY);
    }
    
    /**
     * Gets the last temporal blocks count (blocks with temporal consistency).
     * 
     * @return Number of temporally consistent blocks
     */
    public int getLastTemporalBlocksCount() {
        return lastTemporalBlocksCount;
    }
    
    /**
     * Gets the last motion bounding box Y coordinates.
     * 
     * @return int array [minY, maxY] or null if no motion
     */
    public int[] getLastMotionBounds() {
        if (lastMotionMaxY > lastMotionMinY) {
            return new int[] { lastMotionMinY, lastMotionMaxY };
        }
        return null;
    }
    
    /**
     * Gets class name from COCO class ID.
     */
    private String getClassName(int classId) {
        switch (classId) {
            case 0: return "person";
            case 2: return "car";
            case 3: return "motorcycle";
            case 5: return "bus";
            case 7: return "truck";
            default: return "object_" + classId;
        }
    }
    
    /**
     * Sets object detection filters.
     * 
     * Also adjusts motion detection sensitivity based on minSize:
     * - Lower minSize (for distant objects) = lower motion sensitivity
     * - Higher minSize (for close objects) = higher motion sensitivity
     * 
     * @param minSize Minimum object size (0.0-1.0, fraction of frame area)
     * @param confidence Minimum confidence (0.0-1.0)
     * @param detectPerson Enable person detection
     * @param detectCar Enable car detection
     * @param detectBike Enable bike detection
     */
    public void setObjectFilters(float minSize, float confidence,
                                 boolean detectPerson, boolean detectCar, boolean detectBike) {
        this.minObjectSize = minSize;
        this.aiConfidence = confidence;

        // Build class filter for YOLO
        java.util.ArrayList<Integer> classes = new java.util.ArrayList<>();
        if (detectPerson) classes.add(0);  // COCO: person
        if (detectCar) {
            classes.add(2);  // COCO: car
            classes.add(5);  // COCO: bus
            classes.add(7);  // COCO: truck
        }
        if (detectBike) {
            classes.add(1);  // COCO: bicycle
            classes.add(3);  // COCO: motorcycle
        }

        // FIX (Bug B): empty list now means "user disabled all classes" — represented as
        // an empty array (sentinel) rather than null. The hot-path guard short-circuits
        // YOLO entirely. This also lets us unload the TFLite interpreter to reclaim
        // ~50MB of native memory until detection is re-enabled.
        boolean hadAi = this.aiEnabled;
        if (classes.isEmpty()) {
            classFilter = new int[0];
            this.aiEnabled = false;
        } else {
            classFilter = new int[classes.size()];
            for (int i = 0; i < classes.size(); i++) {
                classFilter[i] = classes.get(i);
            }
            this.aiEnabled = true;
        }

        // Unload YOLO when AI is now off; load lazily again on next call when re-enabled
        // (lazy re-init is handled where YoloDetector is constructed in init()).
        if (!aiEnabled && yoloDetector != null) {
            try {
                yoloDetector.close();
            } catch (Exception e) {
                logger.warn("YoloDetector close failed: " + e.getMessage());
            }
            yoloDetector = null;
            logger.info("YOLO detector closed: all object classes disabled by user");
        } else if (aiEnabled && !hadAi) {
            logger.info("Object detection re-enabled; YOLO will be reloaded on next inference");
        }

        logger.info(String.format("Object filters: minSize=%.1f%%, confidence=%.0f%%, aiEnabled=%s, classes=%s",
                minSize * 100, confidence * 100, aiEnabled, classes));

        // Lazily reload YOLO if it was previously closed and we now need it again
        if (aiEnabled && yoloDetector == null) {
            reloadYoloDetectorIfPossible();
        }
    }

    /**
     * (Bug B helper) Lazily re-initialise the YOLO detector after a previous unload.
     * Uses the same Context/AssetManager paths as the original init() so daemon and
     * regular runs both work. Idempotent and safe to call when AI is already loaded.
     */
    private void reloadYoloDetectorIfPossible() {
        if (yoloDetector != null) return;
        try {
            if (yoloContext != null) {
                yoloDetector = new YoloDetector(yoloContext);
            } else if (yoloAssetManager != null) {
                yoloDetector = new YoloDetector(new com.overdrive.app.ai.AssetContext(yoloAssetManager));
            } else {
                logger.warn("Cannot reload YOLO: no context/assetManager retained");
                return;
            }
            boolean ok = yoloDetector.init();
            if (ok) {
                useObjectDetection = true;
                logger.info("YOLO detector reloaded (object detection re-enabled)");
            } else {
                logger.warn("YOLO reload failed");
                yoloDetector = null;
            }
        } catch (Exception e) {
            logger.warn("YOLO reload threw: " + e.getMessage());
            yoloDetector = null;
        }
    }
    
    /**
     * Publish a motion notification onto the cross-cutting NotificationBus.
     *
     * <p>Filename is the not-yet-finalized {@code currentEventFile} name —
     * recording is still in progress when this fires. Delivering the push
     * immediately (rather than waiting for finalization) prioritizes alert
     * latency over tap-to-play polish; the events page will surface a
     * "still recording" state until the file closes.
     */
    /**
     * Send a Telegram motion notification enriched with the current Actor
     * snapshot. Falls back to a generic "motion" payload when no Actors are
     * known yet (e.g. recording started purely on motion before YOLO ran).
     * Honours the user's notification tier toggles (item 8).
     */
    private void sendRichMotionNotifications(String videoFilename) {
        // User opt-out: by default, Telegram only gets the recording-CLOSE
        // photo (sendFinalTelegramNotification, fired from stopRecording). The
        // start-stage text message is suppressed because the user-visible end
        // result is two messages back-to-back — same content, no replace
        // semantics in Telegram. Telegram-only users who want low-latency
        // pings can flip telegramSendStartPing on in Sentry settings.
        //
        // Treat null config as "default" (off). Without this, an early-startup
        // motion event before config has been wired would leak through with
        // legacy "always send" behaviour, contradicting the documented default.
        if (config == null || !config.isTelegramSendStartPing()) {
            return;
        }
        java.util.List<Actor> snap = lastActors;
        Actor.Severity peakSev = com.overdrive.app.notifications.NotificationGate.maxSeverity(snap);
        // Per-tier muting for the web push system happens device-side via
        // muted-categories. Telegram has its own subscription model; we still
        // pass severity so the daemon can format the message accordingly.
        // Static actors (parked cars next to ours) MUST NOT count or be picked
        // as the detection label — see publishMotionNotification for the same
        // reasoning.
        int persons = 0, vehicles = 0, bikes = 0, animals = 0;
        Actor.Proximity closest = null;
        String detectionLabel = "motion";
        Actor threat = null;
        for (Actor a : snap) {
            if (a.isStatic) continue;
            switch (a.classGroup) {
                case PERSON:  persons++;  break;
                case VEHICLE: vehicles++; break;
                case BIKE:    bikes++;    break;
                case ANIMAL:  animals++;  break;
                default: break;
            }
            if (closest == null || a.peakProximity.ordinal() < closest.ordinal()) {
                closest = a.peakProximity;
            }
            if (threat == null
                    || a.peakSeverity.ordinal() > threat.peakSeverity.ordinal()
                    || (a.peakSeverity == threat.peakSeverity
                        && classRank(a.classGroup) > classRank(threat.classGroup))) {
                threat = a;
            }
        }
        // camHint follows the threat actor so the title's "X at <camera>" phrase
        // names the camera that saw X, not whichever actor happened to be closest.
        String camHint = cameraNameFor(threat);
        float bestConf = threat != null ? threat.peakConfidence : 0f;
        if (threat != null) detectionLabel = Actor.groupLabel(threat.classGroup);
        // Telegram tier mute — mirrors the push tier toggles so a
        // Telegram-only user can keep CRITICAL/ALERT and silence NOTICE.
        if (!com.overdrive.app.notifications.NotificationGate.shouldTelegram(peakSev, config)) {
            logger.debug("Telegram start-stage suppressed by per-tier toggle (sev=" + peakSev + ")");
            return;
        }
        try {
            TelegramNotifier.notifyMotion(
                    detectionLabel,
                    bestConf > 0f ? bestConf : 1.0f,
                    videoFilename,
                    peakSev != null ? peakSev.name() : null,
                    persons, vehicles, bikes, animals,
                    closest != null ? closest.name() : null,
                    camHint);
        } catch (Throwable t) {
            logger.debug("Telegram notify failed: " + t.getMessage());
        }
    }

    /**
     * Final Telegram notification at recording-end. Computes the same actor
     * summary as {@link #sendRichMotionNotifications} but routes via
     * {@code notifyMotionFinalized} so the daemon sends a photo (with the hero
     * JPEG as the image and the threat summary as the caption) instead of a
     * text-only message. Falls back gracefully on the daemon side if the photo
     * can't be sent.
     */
    private void sendFinalTelegramNotification(String videoFilename, String heroPhotoPath) {
        java.util.List<Actor> snap = lastActors;
        Actor.Severity peakSev = com.overdrive.app.notifications.NotificationGate.maxSeverity(snap);
        int persons = 0, vehicles = 0, bikes = 0, animals = 0;
        Actor.Proximity closest = null;
        Actor threat = null;
        for (Actor a : snap) {
            if (a.isStatic) continue;
            switch (a.classGroup) {
                case PERSON:  persons++;  break;
                case VEHICLE: vehicles++; break;
                case BIKE:    bikes++;    break;
                case ANIMAL:  animals++;  break;
                default: break;
            }
            if (closest == null || a.peakProximity.ordinal() < closest.ordinal()) {
                closest = a.peakProximity;
            }
            if (threat == null
                    || a.peakSeverity.ordinal() > threat.peakSeverity.ordinal()
                    || (a.peakSeverity == threat.peakSeverity
                        && classRank(a.classGroup) > classRank(threat.classGroup))) {
                threat = a;
            }
        }
        // camHint follows the threat actor — see sendRichMotionNotifications.
        String camHint = cameraNameFor(threat);
        // Telegram tier mute — same gate as the start-stage notification so
        // both stages of the two-stage flow honour the same toggle.
        if (!com.overdrive.app.notifications.NotificationGate.shouldTelegram(peakSev, config)) {
            logger.debug("Telegram final-stage suppressed by per-tier toggle (sev=" + peakSev + ")");
            return;
        }
        try {
            TelegramNotifier.notifyMotionFinalized(
                    videoFilename,
                    heroPhotoPath,
                    peakSev != null ? peakSev.name() : null,
                    persons, vehicles, bikes, animals,
                    closest != null ? closest.name() : null,
                    camHint);
        } catch (Throwable t) {
            logger.debug("Telegram finalized notify failed: " + t.getMessage());
        }
    }

    /**
     * Rank a class group for "which actor is the threat in this scene". Higher
     * = more important to surface. Mirrors {@link ThumbnailBuffer}'s scoring so
     * the notification title agrees with the thumbnail.
     */
    private static int classRank(Actor.ClassGroup g) {
        if (g == null) return 0;
        switch (g) {
            case PERSON:  return 4;
            case BIKE:    return 3;
            case VEHICLE: return 2;
            case ANIMAL:  return 1;
            default:      return 0;
        }
    }

    private static String cameraNameFor(Actor a) {
        if (a == null) return null;
        if (a.peakCamera < 0 || a.peakCamera >= MotionPipelineV2.QUADRANT_NAMES.length) return null;
        return MotionPipelineV2.QUADRANT_NAMES[a.peakCamera];
    }

    /**
     * Stable per-event tag used by both the initial quick notification and the
     * finalized rich one. Same tag → OS replaces the first banner with the
     * second instead of stacking. Tag is derived from the filename so it's
     * unique per recording (a 1-minute dedupe window across recordings is no
     * longer needed since the tag is already event-scoped).
     */
    private static String notificationTagFor(String videoFilename) {
        if (videoFilename != null && !videoFilename.isEmpty()) {
            return "motion:" + videoFilename;
        }
        // Fallback when we don't yet have a filename — minute-bucket dedupe
        // (matches legacy behaviour).
        return "motion-" + (System.currentTimeMillis() / 60000L);
    }

    /**
     * Fallback hero JPEG: extract a keyframe from the MP4 itself when
     * ThumbnailBuffer didn't capture one. Saves to the same path
     * `<videoBase>.jpg` so the rest of the pipeline (sidecar reference,
     * Telegram sendPhoto, PWA push image) works unchanged. Atomic write
     * via .tmp + rename, world-readable so the Telegram daemon (different
     * UID) can read it.
     *
     * Idempotent and exception-safe — failure is logged but never thrown
     * to the caller. The notification path treats absence of the file as
     * "text-only", which is the correct degraded behaviour.
     */
    private void writeFallbackHeroFromMp4(File mp4File, File outFile) {
        if (mp4File == null || outFile == null) return;
        if (outFile.exists()) return;          // ThumbnailBuffer already wrote one
        if (!mp4File.exists() || mp4File.length() == 0) return;

        android.media.MediaMetadataRetriever mmr = null;
        try {
            mmr = new android.media.MediaMetadataRetriever();
            mmr.setDataSource(mp4File.getAbsolutePath());
            // Sample at ~1s in (or 0 if the clip is shorter) to skip the
            // black frame the encoder sometimes leads with.
            String dur = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION);
            long durMs = 0;
            try { if (dur != null) durMs = Long.parseLong(dur); } catch (Exception ignored) {}
            long sampleUs = durMs >= 1500 ? 1_000_000L : Math.max(0L, (durMs * 500L));
            android.graphics.Bitmap frame = mmr.getFrameAtTime(sampleUs,
                    android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (frame == null) {
                frame = mmr.getFrameAtTime(0, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            }
            if (frame == null) {
                logger.debug("Fallback hero: getFrameAtTime returned null for " + mp4File.getName());
                return;
            }
            File tmpFile = new File(outFile.getAbsolutePath() + ".tmp");
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(tmpFile)) {
                frame.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, fos);
                try { fos.getFD().sync(); } catch (Throwable ignored) {}
            } finally {
                frame.recycle();
            }
            try { tmpFile.setReadable(true, /*ownerOnly=*/false); } catch (Throwable ignored) {}
            if (!tmpFile.renameTo(outFile)) {
                outFile.delete();
                if (!tmpFile.renameTo(outFile)) {
                    tmpFile.delete();
                    logger.warn("Fallback hero rename failed for " + outFile.getName());
                    return;
                }
            }
            logger.info("Fallback hero (from mp4 keyframe): " + outFile.getName());
        } catch (Throwable t) {
            logger.debug("Fallback hero extraction failed for " + mp4File.getName()
                    + ": " + t.getMessage());
        } finally {
            if (mmr != null) {
                try { mmr.release(); } catch (Throwable ignored) {}
            }
        }
    }

    /**
     * Capitalised, human-readable proximity phrase used as the lead clause
     * in notification bodies. "VERY_CLOSE" → "Very close", etc.
     */
    private static String proximityPhrase(Actor.Proximity p) {
        if (p == null) return "";
        switch (p) {
            case VERY_CLOSE: return "Very close";
            case CLOSE:      return "Close";
            case MID:        return "Mid range";
            case FAR:        return "Far";
            default:         return "";
        }
    }

    /**
     * Pluralised count list for notification bodies.
     * (1, 0, 0, 0) → "1 person"
     * (2, 1, 0, 0) → "2 people, 1 vehicle"
     * (0, 2, 0, 1) → "2 vehicles, 1 animal"
     * Skips zero counts; uses proper plurals; drops the "× n" formatter.
     */
    private static String formatActorCounts(int persons, int vehicles, int bikes, int animals) {
        java.util.List<String> parts = new java.util.ArrayList<>(4);
        if (persons > 0)  parts.add(persons  + " " + (persons  == 1 ? "person"  : "people"));
        if (vehicles > 0) parts.add(vehicles + " " + (vehicles == 1 ? "vehicle" : "vehicles"));
        if (bikes > 0)    parts.add(bikes    + " " + (bikes    == 1 ? "bike"    : "bikes"));
        if (animals > 0)  parts.add(animals  + " " + (animals  == 1 ? "animal"  : "animals"));
        return String.join(", ", parts);
    }

    /**
     * Initial low-priority notification at the moment recording starts.
     *
     * Why we still publish at start: the user expects feedback "something just
     * happened" with low latency. The hero thumbnail isn't ready yet (it's
     * written by ThumbnailBuffer at recording-end), so this banner is text-
     * only and minimum-severity. The matching {@link #publishMotionFinal}
     * call after recording-end carries the rich title + thumbnail and uses
     * the SAME tag — so the OS replaces this quick banner instead of stacking.
     *
     * Routing: always to {@code surveillance.motion.notice} so users who only
     * want Alerts/Criticals at start can mute this tier and just receive the
     * final notification.
     */
    private void publishMotionNotification(String videoFilename) {
        try {
            // Honour the user's per-tier toggle. The start banner is always
            // routed to surveillance.motion.notice (final stage carries the
            // real severity), so it's gated by isPushNotices(). With the
            // default config (pushNotices=false) start banners are off and
            // the user only sees the rich final notification.
            if (config != null && !config.isPushNotices()) {
                return;
            }
            org.json.JSONObject data = new org.json.JSONObject();
            String url;
            if (videoFilename != null && !videoFilename.isEmpty()) {
                String enc = java.net.URLEncoder.encode(videoFilename, "UTF-8");
                data.put("filename", videoFilename);
                // Deliberately NOT setting data.snapshot here. At start time the
                // hero JPEG hasn't been written yet and /thumb/<mp4> will return
                // 202 or, worse, a mid-event MMR frame off the in-flight .tmp.
                // iOS Safari Web Push caches the resolved image bytes against
                // the tag on first paint and refuses to swap them when the
                // matching `final` push arrives — so leaving snapshot out keeps
                // the start banner intentionally text-only and lets the final
                // push install the real hero image cleanly.
                data.put("stage", "start");
                url = "/events.html?filter=sentry&file=" + enc;
            } else {
                url = "/events.html?filter=sentry";
            }

            String camHint = null;
            for (Actor a : lastActors) {
                if (a.peakCamera >= 0 && a.peakCamera < MotionPipelineV2.QUADRANT_NAMES.length) {
                    camHint = MotionPipelineV2.QUADRANT_NAMES[a.peakCamera];
                    break;
                }
            }
            String title = (camHint != null) ? "Motion at " + camHint : "Motion detected";
            String body = "Recording in progress";

            com.overdrive.app.notifications.NotificationBus.get().publish(
                    new com.overdrive.app.notifications.NotificationEvent(
                            "surveillance.motion.notice",
                            com.overdrive.app.notifications.NotificationEvent.Severity.INFO,
                            title,
                            body,
                            notificationTagFor(videoFilename),
                            url,
                            data));
        } catch (Throwable t) {
            logger.debug("publishMotionNotification (start) failed: " + t.getMessage());
        }
    }

    /**
     * Finalized rich notification fired from stopRecording AFTER the hero JPEG
     * has been written by ThumbnailBuffer. Uses the same tag as the initial
     * notification so the OS replaces the "Recording in progress…" banner
     * with the proper threat summary + image.
     *
     * Routes to the severity-appropriate subcategory ({@code .notice/.alert/.critical})
     * so per-tier muting works.
     */
    private void publishMotionFinal(String videoFilename, String heroJpegName) {
        try {
            // Snapshot the current Actor view — at this point the recording has
            // closed so lastActors holds the final state.
            java.util.List<Actor> snap = lastActors;
            Actor.Severity peakSev = com.overdrive.app.notifications.NotificationGate.maxSeverity(snap);
            // Per-tier gate (config-level): if the user has unchecked the push
            // toggle for this tier in surveillance.html, suppress the publish
            // entirely. Per-device subcategory muting still happens downstream
            // in PushSink for users who want to silence individual devices.
            if (!com.overdrive.app.notifications.NotificationGate.shouldPush(peakSev, config)) {
                logger.debug("publishMotionFinal suppressed by per-tier toggle (sev=" + peakSev + ")");
                return;
            }

            // Build per-class counts + closest proximity from snapshot.
            //
            // ONLY count non-static actors. The user's worry: two cars parked
            // next to ours while a person walks in. YOLO returns 3 detections;
            // the parked cars are flagged isStatic by the tracker; we exclude
            // them from counts so the notification reads "1 person near front
            // camera" — not "1 person, 2 vehicles".
            int persons = 0, vehicles = 0, bikes = 0, animals = 0;
            Actor.Proximity closest = null;
            // Threat actor = highest-severity, then best class rank
            // (person > bike > vehicle > animal).
            Actor threat = null;
            for (Actor a : snap) {
                if (a.isStatic) continue;
                switch (a.classGroup) {
                    case PERSON:  persons++;  break;
                    case VEHICLE: vehicles++; break;
                    case BIKE:    bikes++;    break;
                    case ANIMAL:  animals++;  break;
                    default: break;
                }
                if (closest == null || a.peakProximity.ordinal() < closest.ordinal()) {
                    closest = a.peakProximity;
                }
                if (threat == null
                        || a.peakSeverity.ordinal() > threat.peakSeverity.ordinal()
                        || (a.peakSeverity == threat.peakSeverity
                            && classRank(a.classGroup) > classRank(threat.classGroup))) {
                    threat = a;
                }
            }
            // camHint follows the threat actor so the title's "X at <camera>"
            // phrase names the camera that saw X, not whichever actor was closest.
            String camHint = cameraNameFor(threat);

            // ---- Title (severity tier + threat class + camera) ----
            // Format: "CRITICAL · Person at front" or "Alert · Vehicle at rear"
            // or plain "Motion at front" when AI didn't classify.
            String title;
            if (threat == null) {
                title = (camHint != null) ? "Motion at " + camHint : "Motion detected";
            } else {
                StringBuilder sb = new StringBuilder();
                if (peakSev == Actor.Severity.CRITICAL) sb.append("CRITICAL · ");
                else if (peakSev == Actor.Severity.ALERT) sb.append("Alert · ");
                String label = Actor.groupLabel(threat.classGroup);
                if (!label.isEmpty()) {
                    label = Character.toUpperCase(label.charAt(0)) + label.substring(1);
                }
                sb.append(label);
                if (camHint != null) sb.append(" at ").append(camHint);
                title = sb.toString();
            }

            // ---- Body (proximity phrase + counts when relevant) ----
            // Single actor: "Very close" / "Close" / "Mid range" / "Far".
            // Multiple actors: "Very close · 1 person, 2 vehicles".
            // When a hero JPEG was written, append "close-up view" so the user
            // knows the attached image is the foveated crop around the threat,
            // not a wide shot of the camera frame.
            String body;
            int totalActors = persons + vehicles + bikes + animals;
            boolean hasHero = heroJpegName != null && !heroJpegName.isEmpty();
            if (threat == null) {
                body = "Recording in progress";
            } else {
                StringBuilder sb = new StringBuilder();
                if (closest != null && closest != Actor.Proximity.UNKNOWN) {
                    sb.append(proximityPhrase(closest));
                }
                if (totalActors > 1) {
                    if (sb.length() > 0) sb.append(" · ");
                    sb.append(formatActorCounts(persons, vehicles, bikes, animals));
                }
                if (sb.length() == 0) sb.append("Motion detected");
                if (hasHero) sb.append(" · close-up view");
                body = sb.toString();
            }

            org.json.JSONObject data = new org.json.JSONObject();
            String url;
            if (videoFilename != null) {
                String enc = java.net.URLEncoder.encode(videoFilename, "UTF-8");
                data.put("filename", videoFilename);
                // Prefer the just-written hero JPEG for the OS banner image.
                // Falls back to /thumb/<mp4-name> which the server also resolves
                // to the hero sibling, but the explicit JPEG path skips a layer
                // of resolution and avoids any 202-while-generating window.
                String snapshotName = (heroJpegName != null && !heroJpegName.isEmpty())
                        ? heroJpegName : videoFilename;
                String encSnap = java.net.URLEncoder.encode(snapshotName, "UTF-8");
                // Carry a single-purpose signed token so Web Push service
                // workers / OS notification banners can fetch the thumbnail
                // without an Authorization header. 10 min TTL is plenty for
                // a banner that the user dismisses or taps within seconds.
                String thumbTok = com.overdrive.app.auth.AuthManager
                        .signThumbToken(snapshotName, 600L);
                String snapUrl = "/thumb/" + encSnap;
                if (thumbTok != null) snapUrl += "?t=" + thumbTok;
                data.put("snapshot", snapUrl);
                data.put("stage", "final");
                url = "/events.html?filter=sentry&file=" + enc;
            } else {
                url = "/events.html?filter=sentry";
            }
            // Surface the new metadata so the notification UI / SW can render it
            data.put("severity", peakSev.name());
            data.put("personCount", persons);
            data.put("vehicleCount", vehicles);
            data.put("bikeCount", bikes);
            data.put("animalCount", animals);
            if (closest != null && closest != Actor.Proximity.UNKNOWN) {
                data.put("closestProximity", closest.name());
            }
            if (camHint != null) data.put("camera", camHint);

            com.overdrive.app.notifications.NotificationEvent.Severity nsev;
            if (peakSev == Actor.Severity.CRITICAL) {
                nsev = com.overdrive.app.notifications.NotificationEvent.Severity.CRITICAL;
            } else if (peakSev == Actor.Severity.ALERT) {
                nsev = com.overdrive.app.notifications.NotificationEvent.Severity.WARN;
            } else {
                nsev = com.overdrive.app.notifications.NotificationEvent.Severity.INFO;
            }

            // Route to severity-specific subcategory so per-tier muting works
            // (item 8). Devices that have not yet learned the new IDs still
            // receive the parent "surveillance.motion" event below.
            String subCategory;
            if (peakSev == Actor.Severity.CRITICAL) subCategory = "surveillance.motion.critical";
            else if (peakSev == Actor.Severity.ALERT) subCategory = "surveillance.motion.alert";
            else subCategory = "surveillance.motion.notice";

            com.overdrive.app.notifications.NotificationBus.get().publish(
                    new com.overdrive.app.notifications.NotificationEvent(
                            subCategory,
                            nsev,
                            title,
                            body,
                            notificationTagFor(videoFilename),
                            url,
                            data));
        } catch (Throwable t) {
            logger.debug("publishMotionFinal failed: " + t.getMessage());
        }
    }

    /**
     * Starts recording an event with pre-record support.
     *
     * The encoder is always running and buffering frames. This method
     * triggers the flush of the pre-record buffer and starts writing to file.
     */
    private void startRecording() {
        if (recorder == null) {
            logger.error("Cannot start recording - recorder is null");
            return;
        }
        
        if (recording) {
            logger.debug("Already recording");
            return;
        }
        
        // SOTA: Storage cleanup happens off the trigger thread.
        // The periodic cleanup (StorageManager.startPeriodicCleanup, 30s cadence
        // with a 90%-of-limit threshold) is the steady-state mechanism. Doing
        // a synchronous scan + delete here was costing 100-200ms on the
        // motion-detection thread on near-full SD cards, which was the
        // dominant contributor to lag at motion onset. Two changes:
        //   1. SD-card mount check stays sync (cheap, microseconds, and we
        //      need a valid path before triggerEventRecording).
        //   2. ensureSurveillanceSpace fires on aiExecutor — a cheap noop if
        //      already under the limit, and worst case it runs in parallel
        //      with the recording itself. New recordings still write; old
        //      ones get pruned a beat later.
        com.overdrive.app.storage.StorageManager storageManager;
        try {
            storageManager = com.overdrive.app.storage.StorageManager.getInstance();
            if (storageManager.getSurveillanceStorageType() ==
                    com.overdrive.app.storage.StorageManager.StorageType.SD_CARD &&
                    !storageManager.isSdCardMounted()) {
                logger.warn("SD card unmounted before recording - attempting remount");
                if (!storageManager.ensureSdCardMounted(true)) {
                    logger.error("SD card remount failed - event may write to stale path");
                }
            }
        } catch (Exception e) {
            logger.warn("Storage mount check failed: " + e.getMessage());
            storageManager = null;
        }
        final com.overdrive.app.storage.StorageManager smRef = storageManager;
        if (smRef != null) {
            aiExecutor.execute(() -> {
                try { smRef.ensureSurveillanceSpace(50 * 1024 * 1024); }
                catch (Exception e) { logger.warn("Async storage cleanup failed: " + e.getMessage()); }
            });
        }
        
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        String fileName = "event_" + timestamp + ".mp4";
        currentEventFile = new File(eventOutputDir, fileName);
        
        logger.info("Triggering event recording: " + currentEventFile.getAbsolutePath());
        logger.info(String.format("Pre-record: %d sec, Post-record: %d sec", 
                preRecordMs / 1000, postRecordMs / 1000));
        
        // Trigger event recording (flushes pre-record buffer)
        recorder.triggerEventRecording(currentEventFile.getAbsolutePath(), postRecordMs);
        recording = true;

        // Per-segment hero / sidecar plumbing.
        // The recorder rotates .mp4 files at SEGMENT_DURATION_MS (2 min) so a
        // long event spans multiple files. Without this listener, the
        // ThumbnailBuffer + EventTimelineCollector would only flush against
        // the FIRST segment's filename at stopRecording — segments 2..N would
        // appear in the recordings library as plain MP4s with no badges, no
        // actor counts, no hero thumbnail. With this listener, each rotated
        // segment gets its own coherent (hero JPEG, per-actor JPEGs, JSON
        // sidecar) tied to its own filename. The metadata buffers are reset
        // on rotation so segment N+1 collects independent state.
        try {
            HardwareEventRecorderGpu enc = recorder.getEncoder();
            if (enc != null) {
                enc.setSegmentListener((closedSegment, newSegment) -> {
                    try {
                        if (closedSegment != null) {
                            flushSegmentMetadata(closedSegment);
                        }
                        // Update currentEventFile so stopRecording's flush
                        // attaches to the LAST segment, not the first.
                        if (newSegment != null) {
                            currentEventFile = newSegment;
                            // Restart the timeline collector for the new
                            // segment so relMs in its sidecar is measured
                            // from segment-N start, not event start.
                            // Use the no-preRing variant: the pre-trigger
                            // ring captured spans from BEFORE segment 1
                            // began; replaying them at each rotation would
                            // duplicate them across every segment's sidecar.
                            timelineCollector.startCollectingNoPreRing();
                        }
                    } catch (Exception ex) {
                        logger.warn("Per-segment flush failed: " + ex.getMessage());
                    }
                });
            }
        } catch (Exception e) {
            logger.warn("Could not register segment listener: " + e.getMessage());
        }
        
        // SOTA: Start timeline event collection for this recording.
        // Use the ACTUAL pre-record duration from the H.264 circular buffer, not the
        // configured preRecordMs. The circular buffer starts from the nearest keyframe,
        // which can be significantly longer than the configured pre-record window.
        // Example: configured preRecordMs=5000, but buffer flushed 14.1 sec of video.
        // If we use 5000ms as the origin, timeline events appear 9 seconds too early.
        long actualPreRecordMs = preRecordMs;
        try {
            HardwareEventRecorderGpu encoder = recorder.getEncoder();
            if (encoder != null) {
                long actual = encoder.getActualPreRecordDurationMs();
                if (actual > 0) {
                    actualPreRecordMs = actual;
                    logger.info("Timeline using actual pre-record duration: " + actual + "ms (configured: " + preRecordMs + "ms)");
                }
            }
        } catch (Exception e) {
            logger.warn("Could not get actual pre-record duration: " + e.getMessage());
        }
        timelineCollector.startCollecting(actualPreRecordMs);
        
        logger.info("Event recording triggered successfully");
    }
    
    /**
     * Stops recording an event with post-record support.
     */
    private void stopRecording() {
        if (recorder == null || !recording) {
            return;
        }
        
        // SOTA: Update detection baseline from the last YOLO detections of this event.
        // This is the event-driven baseline update — zero extra inferences.
        // Only updates the quadrant where the event happened.
        // P1 #13: snapshot the slot once via getAndSet() so a late aiExecutor
        // lambda writing the same slot can't corrupt the value mid-read.
        if (lastEventQuadrant >= 0) {
            java.util.List<com.overdrive.app.ai.Detection> snap =
                    lastYoloDetections.getAndSet(lastEventQuadrant, null);
            if (snap != null) {
                int qW = THUMBNAIL_WIDTH / 2;
                int qH = THUMBNAIL_HEIGHT / 2;
                detectionBaseline.updateFromEventEnd(lastEventQuadrant, snap, qW, qH);
            }
            lastEventQuadrant = -1;
        }
        
        // Stop immediately (post-record already handled by timeout). Synchronously
        // joins the encoder drainer thread, after which no further frames or
        // segment-rotation listener calls can fire.
        recorder.stopEventRecording(true, 0);
        recording = false;
        lastRecordingStopTime = System.currentTimeMillis();  // Track when we stopped

        // FIX (B1/H-a): bump the generation counter NOW — before the publish
        // path reads lastActors. Any aiExecutor lambda still in flight will,
        // when it completes, observe gen != generationAtSchedule and skip its
        // writes (Actor/Thumbnail/lastActors mutation). Without this fence,
        // a late lambda landing between the recorder stop and publishMotionFinal
        // could overwrite lastActors, making the notification mis-attribute
        // the event. The actorTracker.reset() / lastActors = empty / clear()
        // calls below run AFTER publish so the publish path sees the snapshot
        // that was current at recorder-stop time.
        recordingGeneration.incrementAndGet();

        if (currentEventFile != null && currentEventFile.exists()) {
            logger.info( String.format("Saved: %s (%d KB)",
                    currentEventFile.getName(), currentEventFile.length() / 1024));

            // Flush metadata for the FINAL segment. Earlier segments were
            // already flushed by the segment listener on each rotation.
            flushSegmentMetadata(currentEventFile);

            // Two-stage notification: replace the "Recording in progress…"
            // banner that fired at recording start with the rich threat
            // summary + hero image. Same notification tag → OS replaces, not
            // stacks. Telegram gets the equivalent rich path with photo.
            // Use the final segment's metadata for the user-facing notif —
            // earlier segments already published their own banners on close.
            String videoName = currentEventFile.getName();
            String heroSibling = videoName.replace(".mp4", ".jpg");
            File heroSiblingFile = new File(currentEventFile.getParentFile(), heroSibling);

            // If ThumbnailBuffer didn't write a YOLO-derived hero (no actor
            // ever classified during this event — e.g. very short motion-only
            // clips, or every actor was filtered out as static-NOTICE
            // background) fall back to a single keyframe extracted from the
            // recorded MP4 so Telegram and the PWA push always have an image
            // to show. Without this, the user gets a text-only "Motion
            // detected" alert with no preview, which looks broken.
            if (!heroSiblingFile.exists()) {
                writeFallbackHeroFromMp4(currentEventFile, heroSiblingFile);
            }

            String heroName = heroSiblingFile.exists() ? heroSibling : null;
            String heroPath = heroSiblingFile.exists() ? heroSiblingFile.getAbsolutePath() : null;
            try { publishMotionFinal(videoName, heroName); }
            catch (Throwable t) { logger.debug("publishMotionFinal threw: " + t.getMessage()); }
            try { sendFinalTelegramNotification(videoName, heroPath); }
            catch (Throwable t) { logger.debug("sendFinalTelegramNotification threw: " + t.getMessage()); }
        }

        // Detach the segment listener so a stale lambda from a previous event
        // can't reset state for the next event's segments.
        try {
            HardwareEventRecorderGpu enc = recorder.getEncoder();
            if (enc != null) enc.setSegmentListener(null);
        } catch (Exception ignored) {}

        currentEventFile = null;
        // Reset Actor state for the next event so IDs / dwell windows don't leak across recordings
        actorTracker.reset();
        lastActors = java.util.Collections.emptyList();
        if (thumbnailBuffer != null) thumbnailBuffer.clear();
        logger.info("Recording stopped, motion detection continues");
    }

    /**
     * Write hero JPEG + per-actor thumbnails + JSON timeline sidecar
     * alongside the given .mp4 segment. Used by both stopRecording (final
     * segment) and the rotation listener (intermediate segments). Resets
     * {@link #thumbnailBuffer} and {@link #timelineCollector} after writing
     * so the next segment starts with empty metadata buffers.
     *
     * Idempotent and exception-safe — failures are logged but never
     * thrown to the caller, since rotation can't be aborted by a hero
     * write failure.
     */
    private void flushSegmentMetadata(File segmentMp4) {
        if (segmentMp4 == null) return;

        // Renormalize per-actor peak timestamps against THIS segment's
        // timeline origin. Actor.peakSeverityRelMs was computed against
        // whichever segment's origin was current when the peak was hit;
        // if the peak fell in segment 1 and the actor is still alive in
        // segment 2, the raw relMs would point at a wall-time before
        // segment 2's start. Sidecar consumers (web events page,
        // VideoPlayerFragment) build scrub-to-event markers off this
        // field — without renormalization, the markers land outside the
        // segment's playable range.
        //
        // Strategy: if the peak's wall time falls inside this segment's
        // window [segmentStart, now], rewrite relMs against segmentStart.
        // Otherwise drop relMs (writers treat -1 as "field absent" and
        // omit it from the JSON). The actor is still emitted with all
        // its other peak fields (severity tier, proximity, class) so
        // badges and filters keep working.
        long segmentStartMs = timelineCollector.getRecordingStartTimeMs();
        java.util.List<Actor> rawActors = lastActors;
        java.util.List<Actor> segmentActors;
        if (rawActors == null || rawActors.isEmpty() || segmentStartMs <= 0) {
            segmentActors = rawActors;
        } else {
            segmentActors = new java.util.ArrayList<>(rawActors.size());
            for (Actor a : rawActors) {
                long renormalizedRelMs;
                if (a.peakSeverityWallMs > 0 && a.peakSeverityWallMs >= segmentStartMs) {
                    renormalizedRelMs = a.peakSeverityWallMs - segmentStartMs;
                } else {
                    renormalizedRelMs = -1L;  // peak fell outside this segment
                }
                if (renormalizedRelMs == a.peakSeverityRelMs) {
                    segmentActors.add(a);
                } else {
                    segmentActors.add(new Actor(
                            a.actorId, a.classGroup,
                            a.firstSeenWallMs, a.lastSeenWallMs,
                            a.firstSeenRelMs, a.lastSeenRelMs,
                            a.cameraMask,
                            a.peakProximity, a.lastProximity,
                            a.trend, a.isStatic,
                            a.peakSeverity, a.peakSeverityWallMs, renormalizedRelMs,
                            a.peakConfidence,
                            a.peakBboxX, a.peakBboxY, a.peakBboxW, a.peakBboxH,
                            a.peakBboxQuadW, a.peakBboxQuadH, a.peakCamera,
                            a.lastBboxX, a.lastBboxY, a.lastBboxW, a.lastBboxH));
                }
            }
        }

        File heroFile = null;
        if (thumbnailBuffer != null) {
            try {
                java.util.Map<Long, Long> relMap = new java.util.HashMap<>();
                if (segmentActors != null) {
                    for (Actor a : segmentActors) {
                        if (a.peakSeverityRelMs >= 0) {
                            relMap.put(a.actorId, a.peakSeverityRelMs);
                        }
                    }
                }
                heroFile = thumbnailBuffer.flushToDisk(segmentMp4, relMap);
                if (heroFile != null) {
                    logger.info("Hero thumbnail (" + segmentMp4.getName() + "): " + heroFile.getName());
                }
            } catch (Exception e) {
                logger.warn("Thumbnail flush failed for " + segmentMp4.getName() + ": " + e.getMessage());
            }
        }

        // Write timeline JSON sidecar alongside this segment.
        try {
            timelineCollector.stopAndWrite(segmentMp4, segmentActors,
                    heroFile != null ? heroFile.getName() : null);
        } catch (Exception e) {
            logger.warn("Timeline write failed for " + segmentMp4.getName() + ": " + e.getMessage());
        }

        // Reset the metadata buffers for the next segment. ThumbnailBuffer
        // already self-clears in flushToDisk; timelineCollector needs an
        // explicit reset before the rotation listener calls startCollecting
        // for the new segment. Don't clear lastActors — the next segment's
        // first frame should have continuity with the actors that crossed
        // the rotation boundary (so a person mid-loiter doesn't get a fresh
        // actorId on every rotation).
    }
    
    /**
     * Enables surveillance (starts monitoring).
     */
    public void enable() {
        // RACE CONDITION FIX (defense in depth): Final guard at the engine level.
        // If ACC is ON, refuse to enable. This catches any edge case where the
        // higher-level guards in CameraDaemon/AccSentryDaemon were bypassed.
        if (com.overdrive.app.monitor.AccMonitor.isAccOn()) {
            logger.warn(">>> Surveillance enable REJECTED at engine level — ACC is ON");
            return;
        }
        
        // Check if native library is loaded
        if (!NativeMotion.isLibraryLoaded()) {
            logger.error(">>> Cannot enable surveillance: NativeMotion library not loaded! Error: " + 
                NativeMotion.getLoadError());
            return;
        }
        
        logger.info("Enabling surveillance engine (pipelineV2=" + (pipelineV2 != null) + 
            ", pipelineV2init=" + (pipelineV2 != null && pipelineV2.isInitialized()) + ")");
        
        active = true;
        frameCount = 0;
        motionDetections = 0;
        firstMotionTime = 0;  // Reset sustained motion timer
        deterrentFiredTime = 0;  // Reset deterrent suppression
        lastAiConfirmationTimeMs = 0;  // Reset AI confirmation gate
        peakThreatDuringSequence = 0;
        
        // Reset post-suppression baseline refresh tracking
        for (int q = 0; q < MotionPipelineV2.NUM_QUADRANTS; q++) {
            framesSinceSuppressionEnded[q] = 0;
            suppressionWasActive[q] = false;
            baselineRefreshQueued[q] = false;
        }
        
        // Reset SOTA tracking variables
        lastTemporalBlocksCount = 0;
        lastMotionMinY = 0;
        lastMotionMaxY = 0;
        lastEstimatedDistance = 0;
        
        // SOTA: Notify StorageManager that surveillance is active (for periodic cleanup)
        try {
            com.overdrive.app.storage.StorageManager.getInstance().setSurveillanceActive(true);
        } catch (Exception e) {
            logger.warn("Could not set surveillance active state: " + e.getMessage());
        }
        
        // V2: Re-initialize pipeline for clean start
        if (pipelineV2 != null) {
            try {
                NativeMotion.initPipelineV2();
                logger.info("V2 pipeline reset for new surveillance session");
            } catch (Exception e) {
                logger.warn("V2 pipeline reset failed: " + e.getMessage());
            }
        }
        
        // Reset cross-quadrant tracker for clean session
        crossQuadrantTracker.reset();

        // Reset Actor layer too — fresh ID space for each session
        actorTracker.reset();
        lastActors = java.util.Collections.emptyList();
        
        // Reset detection baseline for clean session
        detectionBaseline.reset();
        baselineSeeded = false;
        for (int q = 0; q < MotionPipelineV2.NUM_QUADRANTS; q++) {
            lastYoloDetections.set(q, null);
        }
        lastEventQuadrant = -1;
        
        // Apply per-quadrant ROI from persisted config
        if (config != null) {
            for (int q = 0; q < MotionPipelineV2.NUM_QUADRANTS; q++) {
                if (config.isRoiEnabled(q) && config.getRoiPolygon(q) != null) {
                    applyQuadrantRoi(q, config.getRoiPolygon(q));
                } else {
                    // Also check for direct block masks in unified config
                    try {
                        org.json.JSONObject survCfg = com.overdrive.app.config.UnifiedConfigManager.getSurveillance();
                        String[] qKeys = {"Q0", "Q1", "Q2", "Q3"};
                        boolean roiEnabled = survCfg.optBoolean("roiEnabled_" + qKeys[q], false);
                        org.json.JSONArray blockArr = survCfg.optJSONArray("roiBlocks_" + qKeys[q]);
                        if (roiEnabled && blockArr != null && blockArr.length() == MotionPipelineV2.TOTAL_BLOCKS) {
                            byte[] blockMask = new byte[MotionPipelineV2.TOTAL_BLOCKS];
                            for (int i = 0; i < MotionPipelineV2.TOTAL_BLOCKS; i++) {
                                blockMask[i] = (byte)(blockArr.optInt(i, 1) != 0 ? 1 : 0);
                            }
                            NativeMotion.setQuadrantRoi(q, blockMask);
                            logger.info("ROI blocks loaded for Q" + q + " from persisted config");
                        } else {
                            clearQuadrantRoi(q);
                        }
                    } catch (Exception e) {
                        clearQuadrantRoi(q);
                    }
                }
            }
        }
        
        // Initialize native texture tracker (YOLO + NCC hybrid VOT)
        try {
            NativeMotion.initTracker();
            logger.info("Texture tracker initialized (YOLO + NCC hybrid)");
        } catch (Exception e) {
            logger.warn("Texture tracker init failed: " + e.getMessage());
        }
        
        logger.info("Surveillance enabled (V2 per-quadrant pipeline)");
    }
    
    /**
     * Disables surveillance (stops monitoring).
     */
    public void disable() {
        if (recording) {
            stopRecording();
        }
        active = false;
        inActiveMode = false;

        // P1 #15: cancel pending YOLO work and clear shared state BEFORE
        // resetting the baseline. Without this, an aiExecutor lambda already
        // mid-flight can still write lastActors / lastYoloDetections after
        // disable returns, polluting the next session's first frames.
        aiQuadrantQueueClear();
        for (int q = 0; q < MotionPipelineV2.NUM_QUADRANTS; q++) {
            lastYoloDetections.set(q, null);
        }
        // Brief drain so any inference already running observes active=false
        // and skips its writes. Bounded to 50ms — disable() is on the daemon
        // thread; we don't want to block the caller for a full inference.
        try {
            long drainDeadline = System.currentTimeMillis() + 50;
            while (isAiRunning.get() && System.currentTimeMillis() < drainDeadline) {
                Thread.sleep(5);
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }

        // SOTA: Notify StorageManager that surveillance is inactive
        try {
            com.overdrive.app.storage.StorageManager.getInstance().setSurveillanceActive(false);
        } catch (Exception e) {
            logger.warn("Could not set surveillance inactive state: " + e.getMessage());
        }

        // Reset detection baseline for clean session
        detectionBaseline.reset();
        baselineSeeded = false;

        logger.info("Surveillance disabled");
    }
    
    /**
     * Checks if surveillance is active.
     * 
     * @return true if active, false otherwise
     */
    public boolean isActive() {
        return active;
    }
    
    /**
     * Checks if currently recording.
     * 
     * @return true if recording, false otherwise
     */
    public boolean isRecording() {
        return recording;
    }
    
    /**
     * Checks if in active mode (heavy AI).
     * 
     * @return true if in active mode, false if idle
     */
    public boolean isInActiveMode() {
        return inActiveMode;
    }
    
    /**
     * Gets the current SAD threshold.
     * 
     * @return Threshold value (0.0-1.0)
     */
    public float getSadThreshold() {
        return config.getSensitivity();
    }
    
    /**
     * Gets the grid motion block sensitivity.
     * 
     * @return Sensitivity value (0.0-1.0, typically 0.04-0.10)
     */
    public float getBlockSensitivity() {
        return config.getSensitivity();
    }
    
    /**
     * Sets the grid motion block sensitivity.
     * Lower values detect more distant/subtle motion.
     * 
     * @param sensitivity Sensitivity value (0.01-0.20, default 0.04)
     */
    public void setBlockSensitivity(float sensitivity) {
        float clamped = Math.max(0.01f, Math.min(0.20f, sensitivity));
        config.setSensitivity(clamped);
        logger.info("Block sensitivity set to: " + clamped);
    }
    
    /**
     * Sets the unified motion sensitivity (0-100%).
     * 
     * This is the recommended API for controlling motion detection.
     * A single slider that intelligently adjusts:
     * - Density Threshold: How many pixels must change per block
     * - Alarm Threshold: How many blocks must trigger to start recording
     * 
     * Mapping:
     * - 0-30%:   LOW (large/close objects only)
     * - 31-60%:  MEDIUM (balanced, default)
     * - 61-80%:  HIGH (detects distant objects)
     * - 81-100%: VERY HIGH (any motion)
     * 
     * @param sensitivity 0-100 percentage
     */
    public void setUnifiedSensitivity(int sensitivity) {
        config.setUnifiedSensitivity(sensitivity);
        
        // Sync legacy fields for backward compatibility
        this.requiredActiveBlocks = config.getAlarmBlockThreshold();
        
        logger.info(String.format("Unified sensitivity set to: %d%% (alarm=%d blocks, density=%d pixels, shadow=%d)",
                sensitivity, config.getAlarmBlockThreshold(), config.getDensityThreshold(), config.getShadowThreshold()));
    }
    
    /**
     * Gets the unified motion sensitivity (0-100%).
     * 
     * @return Sensitivity percentage
     */
    public int getUnifiedSensitivity() {
        return config.getUnifiedSensitivity();
    }
    
    /**
     * Sets night mode (affects shadow threshold).
     * 
     * Night mode uses a higher shadow threshold (40 vs 25) to filter
     * out headlight reflections and other light artifacts.
     * 
     * @param enabled true for night mode
     */
    public void setNightMode(boolean enabled) {
        config.setNightMode(enabled);
        // Log the V2 pipeline's actual shadow threshold (not the legacy config's)
        int v2Shadow = pipelineV2Config != null ? pipelineV2Config.shadowThreshold : -1;
        int v2Filter = pipelineV2Config != null ? pipelineV2Config.shadowFilterMode : -1;
        logger.info("Night mode set to: " + enabled + 
                " (V2 shadow threshold=" + v2Shadow + ", filter=" + v2Filter + ")");
    }
    
    /**
     * Gets night mode state.
     * 
     * @return true if night mode is enabled
     */
    public boolean isNightMode() {
        return config.isNightMode();
    }
    
    /**
     * Gets the required active blocks threshold.
     * 
     * @return Number of blocks required to trigger motion
     */
    public int getRequiredActiveBlocks() {
        return requiredActiveBlocks;
    }
    
    /**
     * Sets the required active blocks threshold.
     * Lower values are more sensitive to small/distant motion.
     * 
     * @param blocks Number of blocks (1-10, default 2)
     */
    public void setRequiredActiveBlocks(int blocks) {
        this.requiredActiveBlocks = Math.max(1, Math.min(10, blocks));
        // Sync with SOTA config
        config.setRequiredBlocks(this.requiredActiveBlocks);
        logger.info("Required active blocks set to: " + this.requiredActiveBlocks);
    }
    
    /**
     * Gets the flash immunity level.
     * 
     * @return Flash immunity level (0=OFF, 1=LOW, 2=MEDIUM, 3=HIGH)
     */
    public int getFlashImmunity() {
        return flashImmunity;
    }
    
    /**
     * Gets the minimum object size for detection.
     * 
     * @return Minimum object size as fraction of frame (0.02 = 2% = ~15m, 0.20 = 20% = ~3m)
     */
    public float getMinObjectSize() {
        return minObjectSize;
    }
    
    /**
     * Sets the flash immunity level.
     * 
     * Uses edge-based detection to ignore light flashes (headlights, lightning, etc.)
     * while still detecting real object motion.
     * 
     * Levels:
     * - 0 = OFF: Legacy pixel differencing, sensitive to flashes
     * - 1 = LOW: Edge-based, some flash filtering
     * - 2 = MEDIUM: Edge-based + brightness normalization (default)
     * - 3 = HIGH: Edge-based + aggressive flash rejection
     * 
     * @param level Flash immunity level (0-3)
     */
    public void setFlashImmunity(int level) {
        this.flashImmunity = Math.max(0, Math.min(3, level));
        // Sync with SOTA config
        config.setFlashImmunity(this.flashImmunity);
        String[] levelNames = {"OFF", "LOW", "MEDIUM", "HIGH"};
        logger.info("Flash immunity set to: " + levelNames[this.flashImmunity] + " (" + this.flashImmunity + ")");
    }
    
    /**
     * Gets the total number of grid blocks.
     * 
     * @return Total blocks (300 for 640x480 with 32px blocks)
     */
    public int getTotalBlocks() {
        return TOTAL_BLOCKS;
    }
    
    /**
     * Gets the last active blocks count (for UI display).
     * 
     * @return Number of blocks that were active in the last frame
     */
    public int getLastActiveBlocksCount() {
        return lastActiveBlocksCount;
    }
    
    /**
     * Gets the baseline noise blocks count (deprecated - always returns 0).
     * 
     * @return Always 0 (baseline logic removed)
     */
    public int getBaselineNoiseBlocks() {
        return 0;  // Baseline logic removed for simplicity
    }
    
    /**
     * Sets the SAD threshold for motion detection.
     * 
     * @param threshold Threshold value (0.0-1.0, typically 0.05 for 5%)
     */
    public void setSadThreshold(float threshold) {
        config.setSensitivity(threshold);
        logger.info( "SAD threshold set to: " + threshold);
    }
    
    /**
     * Gets the pre-record duration in seconds.
     * 
     * @return Pre-record duration in seconds
     */
    public int getPreRecordSeconds() {
        return (int) (preRecordMs / 1000);
    }
    
    /**
     * Sets the pre-record duration.
     * 
     * @param seconds Duration in seconds (e.g., 10 for 10 seconds before motion)
     */
    public void setPreRecordSeconds(int seconds) {
        this.preRecordMs = seconds * 1000L;
        // Sync with SOTA config
        config.setPreRecordSeconds(seconds);
        logger.info("Pre-record duration set to: " + seconds + " seconds");
        
        // Update the circular buffer size in the recorder's encoder
        if (recorder != null && recorder.getEncoder() != null) {
            recorder.getEncoder().setPreRecordDuration(seconds);
        }
    }
    
    /**
     * Gets the post-record duration in seconds.
     * 
     * @return Post-record duration in seconds
     */
    public int getPostRecordSeconds() {
        return (int) (postRecordMs / 1000);
    }
    
    /**
     * Sets the post-record duration.
     * 
     * @param seconds Duration in seconds (e.g., 5 for 5 seconds after motion stops)
     */
    public void setPostRecordSeconds(int seconds) {
        this.postRecordMs = seconds * 1000L;
        // Sync with SOTA config
        config.setPostRecordSeconds(seconds);
        logger.info("Post-record duration set to: " + seconds + " seconds");
    }
    
    /**
     * Gets the frame count.
     * 
     * @return Total frames processed
     */
    public int getFrameCount() {
        return frameCount;
    }
    
    /**
     * Gets the motion detection count.
     * 
     * @return Total motion events detected
     */
    public int getMotionDetections() {
        return motionDetections;
    }
    
    /**
     * Gets the latest cached mosaic frame (640×480 RGB) for snapshot API.
     * Returns null if no frame has been cached yet.
     */
    public byte[] getLatestMosaicFrame() {
        return latestMosaicFrame;
    }
    
    /**
     * Releases all resources.
     */
    /**
     * Updates the V2 pipeline configuration.
     * Call this when user changes settings via IPC.
     */
    public void updateV2Config(MotionPipelineV2.Config newConfig) {
        if (pipelineV2 != null) {
            if (newConfig != null) {
                pipelineV2Config = newConfig;
            }
            if (pipelineV2Config != null) {
                pipelineV2.applyConfig(pipelineV2Config);
                logger.info("V2 pipeline config updated");
            }
        }
    }
    
    /**
     * Apply a V2 environment preset (outdoor/garage/street).
     */
    public void applyV2EnvironmentPreset(String preset) {
        if (pipelineV2Config != null) {
            pipelineV2Config.applyEnvironmentPreset(preset);
            if (pipelineV2 != null) {
                pipelineV2.applyConfig(pipelineV2Config);
            }
            logger.info("V2 environment preset applied: " + preset);
        }
    }
    
    /**
     * Apply V2 sensitivity level (1-5).
     */
    public void applyV2Sensitivity(int level) {
        if (pipelineV2Config != null) {
            pipelineV2Config.applySensitivity(level);
            if (pipelineV2 != null) {
                pipelineV2.applyConfig(pipelineV2Config);
            }
            logger.info("V2 sensitivity set to " + level);
        }
    }
    
    /**
     * Set V2 loitering time in seconds.
     */
    public void setV2LoiteringTime(int seconds) {
        if (pipelineV2Config != null) {
            pipelineV2Config.loiteringFrames = seconds * 10;  // 10 FPS
            if (pipelineV2 != null) {
                pipelineV2.applyConfig(pipelineV2Config);
            }
        }
        // Also update Java-side sustained motion threshold.
        // THREAT_MEDIUM must persist for this duration before triggering.
        this.loiteringTimeMs = seconds * 1000L;
        logger.info("V2 loitering time set to " + seconds + "s (native=" + (seconds * 10) + " frames, java=" + loiteringTimeMs + "ms)");
    }
    
    /**
     * Enable/disable a specific camera quadrant for V2 detection.
     * @param quadrant 0=front, 1=right, 2=left, 3=rear
     * @param enabled true to enable, false to disable
     */
    public void setV2QuadrantEnabled(int quadrant, boolean enabled) {
        if (pipelineV2Config != null && quadrant >= 0 && quadrant < 4) {
            pipelineV2Config.quadrantEnabled[quadrant] = enabled;
            if (pipelineV2 != null) {
                pipelineV2.applyConfig(pipelineV2Config);
            }
            logger.info("V2 quadrant " + MotionPipelineV2.QUADRANT_NAMES[quadrant] + 
                    " " + (enabled ? "enabled" : "disabled"));
        }
    }
    
    /**
     * Get V2 pipeline results (for heatmap overlay / debug).
     */
    public MotionPipelineV2.QuadrantResult[] getV2Results() {
        return pipelineV2 != null ? pipelineV2.getResults() : null;
    }
    
    /**
     * Set shadow filter mode for V2 pipeline.
     * @param mode 0=OFF, 1=LIGHT, 2=NORMAL, 3=AGGRESSIVE
     */
    public void setV2ShadowFilterMode(int mode) {
        if (pipelineV2Config != null && mode >= 0 && mode <= 3) {
            pipelineV2Config.shadowFilterMode = mode;
            if (pipelineV2 != null) {
                pipelineV2.applyConfig(pipelineV2Config);
            }
            String[] modeNames = {"OFF", "LIGHT", "NORMAL", "AGGRESSIVE"};
            logger.info("V2 shadow filter mode set to " + modeNames[mode]);
        }
    }
    
    /**
     * Get current shadow filter mode.
     * @return 0=OFF, 1=LIGHT, 2=NORMAL, 3=AGGRESSIVE
     */
    public int getV2ShadowFilterMode() {
        return pipelineV2Config != null ? pipelineV2Config.shadowFilterMode : 0;
    }
    
    /**
     * Enable/disable filter debug logging.
     */
    public void setFilterDebugEnabled(boolean enabled) {
        this.filterDebugEnabled = enabled;
        if (!enabled) {
            synchronized (filterLog) {
                filterLogCount = 0;
                filterLogIndex = 0;
            }
        }
        logger.info("Filter debug log " + (enabled ? "enabled" : "disabled"));
    }
    
    /**
     * Add an entry to the filter debug log ring buffer.
     */
    private void addFilterLogEntry(String entry) {
        if (!filterDebugEnabled) return;
        synchronized (filterLog) {
            filterLog[filterLogIndex] = entry;
            filterLogIndex = (filterLogIndex + 1) % FILTER_LOG_CAPACITY;
            if (filterLogCount < FILTER_LOG_CAPACITY) filterLogCount++;
        }
    }
    
    /**
     * Get recent filter log entries (newest first).
     */
    public String[] getFilterLogEntries() {
        synchronized (filterLog) {
            String[] entries = new String[filterLogCount];
            for (int i = 0; i < filterLogCount; i++) {
                int idx = (filterLogIndex - 1 - i + FILTER_LOG_CAPACITY) % FILTER_LOG_CAPACITY;
                entries[i] = filterLog[idx];
            }
            return entries;
        }
    }
    
    public void release() {
        disable();
        
        // SOTA FIX: Shutdown the executor
        aiExecutor.shutdownNow();
        
        // Clean up YOLO detector
        if (yoloDetector != null) {
            yoloDetector.close();
            yoloDetector = null;
        }
        
        currentFrame = null;
        // ThreadLocal: clear this thread's scratch. Other threads' entries
        // (aiExecutor, drainer) will be reclaimed when those threads exit
        // or the next allocation replaces them.
        aiBufferTL.remove();
        
        logger.info("Released");
    }
    
}
