package com.overdrive.app.camera;

import android.graphics.ImageFormat;
import android.hardware.HardwareBuffer;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;

import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.surveillance.GpuDownscaler;
import com.overdrive.app.surveillance.FoveatedCropper;
import com.overdrive.app.surveillance.GpuMosaicRecorder;
import com.overdrive.app.surveillance.HardwareEventRecorderGpu;
import com.overdrive.app.surveillance.SurveillanceEngineGpu;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * PanoramicCameraGpu - GPU Edition with Zero-Copy Pipeline.
 * 
 * This is the GPU-native version of PanoramicCamera that uses an
 * ImageReader-backed HardwareBuffer path. Camera frames flow directly to a
 * GPU external OES texture, enabling:
 * - Zero-copy recording (camera → GPU → encoder)
 * - Minimal AI readback (GPU downscales to 320x240)
 * - <10% total CPU usage
 * 
 * Architecture:
 * - Camera writes to GL_TEXTURE_EXTERNAL_OES via ImageReader + HardwareBuffer
 * - Render loop on dedicated GL thread distributes frames to:
 *   - Recording Lane: GpuMosaicRecorder (zero-copy to encoder)
 *   - AI Lane: GpuDownscaler (2 FPS readback for motion detection)
 */
public class PanoramicCameraGpu {
    private static final String TAG = "PanoramicCameraGpu";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);
    private static final int PHYSICAL_CAMERA_ID = 1;
    private static final int MAX_CAMERA_ID = 5;     // Probe camera IDs 0-5
    
    // AVMCamera surface mode — 0 works on Seal, Atto 1 may need different value
    // Set via setCameraSurfaceMode() before start() for per-model override
    private int cameraSurfaceMode = 0;
    
    // Camera ID override — set via setCameraId() before start()
    private int cameraIdOverride = -1;  // -1 = use default PHYSICAL_CAMERA_ID
    private volatile boolean manualOverrideActive = false;
    private volatile boolean fallbackFromProbe = false;
    
    // SOTA: Full-matrix auto-probe — sweeps camera IDs 0-5 × surface modes 0-5
    // to find the first combination that produces panoramic image data.
    private boolean autoProbeCameras = false;
    // When true, skip frame-15/50 validation entirely (user manually set camera ID)
    private boolean skipFrameValidation = false;
    private int probeStartId = -1;  // Tracks where probe started for wrap-around detection
    private int probeNextCameraId = 0;    // Next camera ID to try
    private int probeNextSurfaceMode = 0; // Next surface mode to try
    private boolean probeSurfaceModeMatrixActive = false;
    private int[] probeMatrixCameraIds = new int[0];
    private int probeMatrixCameraIndex = 0;
    
    // SOTA: Probe gate — blocks recording/streaming/AI until probe finds a working camera.
    // Without this, the encoder records BLACK frames and the stream shows garbage during probe.
    // Defaults to true (no gate) — only set to false when setAutoProbeCameras(true) is called.
    private volatile boolean probeComplete = true;
    
    // Track the last camera ID that delivered non-black data during probe.
    // If the probe exhausts all IDs without finding a verified strip, fall back
    // to this camera — it's better to record from a real camera than nothing.
    private int lastDataCameraId = -1;
    private volatile int cameraLayout = 0;
    private volatile String sourceBmmTag = "";
    private volatile String discoveryMethod = "";
    private volatile String vehicleCamSort = "";
    private volatile CameraFirmwareInfo firmwareInfo = CameraFirmwareInfo.current();
    private volatile String nativeProbeReport = "";
    private volatile boolean nativeProbeReady = false;
    private volatile String fpsSetCameraResult = "unknown";
    private volatile String fpsSetMediaCodecResult = "not_wired";
    private volatile long validatedAtMs = 0L;
    private volatile int validatedFrameWidth = 0;
    private volatile int validatedFrameHeight = 0;
    private volatile int validationFrameCount = 0;
    private volatile String validationSignal = "";
    private volatile String stripConfidence = "";
    private volatile String layoutConfidence = "";
    private volatile String quadrantVariance = "";
    private volatile String lastValidationFailure = "";
    private volatile String lastCameraEvent = "";
    private volatile String arbitrationMode = "eventCallbackOnly";
    
    // Callback when auto-probe discovers a working camera config
    public interface CameraProbeCallback {
        void onCameraFound(int cameraId, int surfaceMode);
    }
    private CameraProbeCallback probeCallback;
    
    // Camera dimensions
    private final int width;
    private final int height;
    
    // EGL and OpenGL
    private EGLCore eglCore;
    private android.opengl.EGLSurface dummySurface;  // Pbuffer for headless context
    private int cameraTextureId;
    // Camera consumer: ImageReader → AHardwareBuffer → EGLImage →
    // cameraTextureId. Bypasses SurfaceFlinger throttling that clamps the
    // Legacy SurfaceTexture consumer path drops to ~8.5 fps on DiLink50 5.0UI builds (verified by
    // AvmImageReaderFpsProbe → 26 fps panoramic). cameraSurface is what we
    // hand to AVMCamera.addPreviewSurface — sourced from ImageReader.getSurface().
    // minSdk=28 enforces Image.getHardwareBuffer availability.
    private ImageReader cameraImageReader;
    private Surface cameraSurface;
    // Dedicated handler for ImageReader.OnImageAvailableListener. MUST be
    // separate from glHandler — renderLoop blocks the GL thread on
    // frameSync.wait(), which would starve the listener if it ran on the
    // same looper. The callback hops to glHandler.post for the actual GL
    // bind work via onHalImageAvailable.
    private HandlerThread imageReaderThread;
    private Handler imageReaderHandler;
    
    // Camera object (via reflection).
    // volatile because reopenCamera() runs on the daemon thread and writes
    // cameraObj while the GL render thread reads it in renderLoop(). Without
    // volatile, the GL thread could observe a stale non-null cameraObj after
    // we've torn down the BYD HAL and block in updateTexImage() against a
    // dead BufferQueue (which is what was tripping the GL watchdog on
    // ACC OFF→ON transitions).
    private volatile Object cameraObj;
    
    // Render loop
    private HandlerThread glThread;
    private Handler glHandler;
    private volatile boolean running = false;
    private final Object frameSync = new Object();
    // State-backed signal between the HAL callback (onHalImageAvailable) and
    // the GL render loop. Plain notify()/wait() races: if the HAL fires while
    // the GL thread is mid-processing (not yet in wait()), the notification
    // is dropped and the GL thread blocks for up to 100 ms before the NEXT
    // HAL fire wakes it — capping effective FPS well below the HAL emission
    // rate. The pending flag closes the race: HAL sets it, GL skips wait()
    // when it's already set, and clears it before processing.
    private volatile boolean imagePending = false;
    
    // Consumers
    private GpuMosaicRecorder recorder;
    private HardwareEventRecorderGpu encoder;  // Direct encoder reference for draining
    private com.overdrive.app.streaming.GpuStreamScaler streamScaler;  // Stream scaler (optional)
    private HardwareEventRecorderGpu streamEncoder;  // Stream encoder (optional)
    private GpuDownscaler downscaler;
    private SurveillanceEngineGpu sentry;
    private FoveatedCropper foveatedCropper;  // High-res AI crop from raw strip
    
    // Frame timing
    private int frameCounter = 0;
    // AI lane is fully decoupled from the GL thread (AiLaneWorker). GL thread
    // produces downscaled frames at camera rate; worker consumes at its own
    // pace and drops frames when busy. V2 motion's internal 100ms throttle
    // (MOTION_PROCESS_INTERVAL_MS) keeps actual processing at ~10 fps so
    // there's no need for a separate frame-skip counter on the GL side.
    private com.overdrive.app.camera.AiLaneWorker aiLaneWorker;
    // Last measured camera FPS, computed in the 2-min Stats log. Surfaced
    // via getMeasuredFps() so the UI can show actualFps when it falls below
    // requested (HAL clamp; e.g. user requests 30, HAL emits ~26).
    private volatile float measuredFps = 0f;
    private long lastFrameTime = 0;
    private volatile long lastCameraStartTime = 0;
    private long startTime = 0;
    
    // Watchdog for GL thread hang detection
    private volatile long lastGlThreadHeartbeat = 0;
    private Thread watchdogThread;
    private static final long GL_THREAD_TIMEOUT_MS = 3000;
    // Extended timeout for initial camera warmup — the BYD panoramic camera HAL
    // can take several seconds to deliver the first frame. During this period the
    // GL thread is legitimately blocked on frameSync.wait(), not deadlocked.
    private static final long GL_THREAD_WARMUP_TIMEOUT_MS = 10000;
    private volatile boolean firstFrameReceived = false;
    
    // SOTA: BYD camera coordinator for cooperative sharing and error recovery
    private BydCameraCoordinator cameraCoordinator;
    private volatile boolean cameraYielded = false;
    
    // Camera health monitor — detects stalled frames and triggers recovery
    private static final long FRAME_STALL_THRESHOLD_MS = 4000;  // 4 seconds without frames (HAL issue)
    // When native app is active, use a longer threshold to avoid false yields
    // from transient CPU/IO load. The HAL needs time to settle into sharing mode.
    private static final long FRAME_STALL_CONTENTION_THRESHOLD_MS = 3000;
    // Require consecutive stalls before yielding — a single stall could be transient
    private static final int CONTENTION_STALL_COUNT_TO_YIELD = 2;
    private volatile int consecutiveContentionStalls = 0;
    
    // Flag to indicate camera restart is in progress — watchdog uses extended timeout.
    // P1 #11: AtomicBoolean so concurrent restartCameraAfterError + reopenCamera
    // calls can't both enter the restart path. Loser observes
    // compareAndSet(false,true)==false and returns; only the winner runs the
    // close/open sequence and is responsible for clearing the flag.
    private final AtomicBoolean restartInProgress = new AtomicBoolean(false);
    
    // SOTA: Pre-yield listener — pipeline registers this to finalize recordings before yield
    public interface CameraYieldListener {
        /** Called BEFORE camera is yielded. Finalize any active recording to prevent corruption. */
        void onPreYield();
        /** Called AFTER camera is re-acquired. Resume recording if needed. */
        void onPostReacquire();
    }
    private CameraYieldListener yieldListener;
    
    // CPU usage monitoring
    private long lastCpuCheckTime = 0;
    private static final long CPU_CHECK_INTERVAL_MS = 10000;  // Every 10 seconds
    
    // Stats logging (time-based, not frame-based)
    private long lastStatsTime = 0;
    private int lastStatsFrameCount = 0;
    private static final long STATS_INTERVAL_MS = 120000;  // Every 2 minutes

    // Per-stage timing diagnostic. Tracks the WORST frame in a 30 s window
    // and logs a single line per window so the contribution of each stage
    // (acquire / mosaicDraw / aiReadback / aiSubmit / swap) is visible
    // without log spam. Used to verify that readback-skip + drainer keep
    // each stage under budget.
    private static final long STAGE_TIMING_LOG_INTERVAL_MS = 30000;
    private long stageTimingWindowStartMs = 0;
    private long stageWorstTotalNs = 0;
    private long stageWorstAcquireNs = 0;
    private long stageWorstMosaicNs = 0;
    private long stageWorstAiReadbackNs = 0;
    private long stageWorstAiSubmitNs = 0;
    private int stageWindowFrames = 0;
    private int stageWindowAiReadbackSkips = 0;

    // AI readback throttle — frame-counter modulo, NOT wall-clock.
    // Wall-clock throttling is fragile when readback duration approaches the
    // interval: the GL thread spends ~117ms per frame (mosaic+swap+readback),
    // which guarantees `now - lastReadback >= 95ms` on every loop, so 100% of
    // frames trigger readback and the pipeline collapses to ~8 fps.
    // Frame-modulo couples AI rate directly to HAL emission rate. With HAL
    // emitting at ~26 fps (ImageReader path), every 3rd frame is ~8.6 AI fps,
    // matching V2 motion's 10 fps internal cadence. If HAL rate changes, AI
    // rate scales proportionally and the GL thread budget stays balanced.
    private static final int AI_READBACK_FRAME_MODULO = 3;

    private int targetFps = 15;  // Desired frame rate for camera
    
    /**
     * Creates a GPU-based panoramic camera.
     * 
     * @param width Camera width (typically 5120)
     * @param height Camera height (typically 960)
     */
    public PanoramicCameraGpu(int width, int height) {
        this.width = width;
        this.height = height;
    }
    
    /**
     * Sets the consumers for the camera frames.
     * 
     * @param recorder GPU mosaic recorder for zero-copy recording
     * @param downscaler GPU downscaler for AI lane
     * @param sentry Surveillance engine for motion detection
     */
    public void setConsumers(GpuMosaicRecorder recorder, GpuDownscaler downscaler,
                            SurveillanceEngineGpu sentry) {
        this.recorder = recorder;
        this.downscaler = downscaler;
        this.sentry = sentry;

        // Build the AI lane worker once consumers are wired. Recycler points
        // back to the downscaler's buffer pool so dropped frames are returned
        // immediately (no leak under sustained submit-while-busy).
        if (this.aiLaneWorker == null) {
            this.aiLaneWorker = new com.overdrive.app.camera.AiLaneWorker(frame -> {
                GpuDownscaler ds = this.downscaler;
                if (ds != null && frame != null) {
                    try {
                        ds.recycleBuffer(frame);
                    } catch (Throwable ignored) {}
                }
            });
        }
        this.aiLaneWorker.setSentry(sentry);
        // Sentry needs the GL handler so its foveated crops (which touch GL
        // state) can hop back to GL thread when called from AiLaneWorker.
        if (sentry != null && glHandler != null) {
            sentry.setGlHandler(glHandler);
        }
        if (sentry != null) {
            sentry.setCameraTargetFps(targetFps);
        }
    }
    
    /**
     * Starts the GPU camera pipeline.
     * 
     * @throws Exception if initialization fails
     */
    public void start() throws Exception {
        logger.info( "Starting GPU camera pipeline...");
        startTime = System.currentTimeMillis();
        
        // SOTA: Initialize BYD camera coordinator for cooperative sharing
        if (cameraCoordinator == null) {
            cameraCoordinator = new BydCameraCoordinator();
            cameraCoordinator.setArbitrationMode(arbitrationMode);
            cameraCoordinator.setYieldCallback(new BydCameraCoordinator.CameraYieldCallback() {
                @Override
                public void onYieldCamera() {
                    // Contention detected — yield on GL thread
                    logger.info("YIELD: Contention detected — releasing camera for native app");
                    cameraYielded = true;
                    if (glHandler != null) {
                        glHandler.post(() -> yieldCameraInternal());
                    }
                }

                @Override
                public void onReacquireCamera() {
                    // Native app released camera after contention yield — re-acquire
                    logger.info("REACQUIRE: Native app released camera — reopening");
                    cameraYielded = false;
                    if (glHandler != null) {
                        glHandler.post(() -> {
                            try {
                                startCamera();
                                if (cameraCoordinator != null && cameraObj != null) {
                                    cameraCoordinator.resetEventCallbackState();
                                    cameraCoordinator.setupEventCallback(cameraObj);
                                }
                                
                                // Restart encoder drainer thread — it was stopped during
                                // onPreYield → stopRecording → closeEventRecording.
                                // Without this, triggerEventRecording creates a muxer but
                                // no thread dequeues frames from the encoder to write them.
                                if (encoder != null) {
                                    encoder.restartDrainerAfterCameraClose();
                                }
                                
                                // SOTA: Notify pipeline to resume recording
                                if (yieldListener != null) {
                                    try {
                                        yieldListener.onPostReacquire();
                                        logger.info("Post-reacquire: recording resumed");
                                    } catch (Exception e) {
                                        logger.warn("Post-reacquire callback error: " + e.getMessage());
                                    }
                                }
                                
                                logger.info("Camera re-acquired after contention yield");
                            } catch (Exception e) {
                                logger.error("Failed to re-acquire camera: " + e.getMessage());
                            }
                        });
                    }
                }

                @Override
                public void onCameraError(int eventType) {
                    // Camera HAL error — but only restart if frames have actually stopped.
                    // On DiLink5.0, event 8 fires immediately after camera open (after event 1004)
                    // as a benign HAL lifecycle notification. Restarting on it causes an infinite loop.
                    // Guard: ignore error events within 3 seconds of camera start — the HAL is still
                    // settling. If it's a real error, the frame stall watchdog will catch it.
                    long timeSinceStart = System.currentTimeMillis() - lastCameraStartTime;
                    if (timeSinceStart < 3000) {
                        logger.warn("CAMERA ERROR: event=" + eventType + " — IGNORED (camera started " + 
                            timeSinceStart + "ms ago, waiting for frame stall watchdog)");
                        return;
                    }
                    logger.error("CAMERA ERROR: event=" + eventType + " — restarting camera");
                    if (glHandler != null) {
                        glHandler.post(() -> restartCameraAfterError());
                    }
                }
            });
            cameraCoordinator.register();
        }
        
        // Start GL thread
        glThread = new HandlerThread("GL-RenderLoop");
        glThread.start();
        glHandler = new Handler(glThread.getLooper());

        // Now that the GL handler exists, give it to the sentry so its
        // foveated crops can hop back to GL when called from AiLaneWorker.
        if (sentry != null) {
            sentry.setGlHandler(glHandler);
            sentry.setCameraTargetFps(targetFps);
        }

        // Initialize on GL thread
        glHandler.post(() -> {
            try {
                initializeGl();
                startCamera();
                
                // SOTA: Setup event callback for HAL error detection (-10086, 8)
                if (cameraCoordinator != null && cameraObj != null) {
                    cameraCoordinator.setupEventCallback(cameraObj);
                }
                
                running = true;
                
                // Start render loop
                glHandler.post(this::renderLoop);
                
                // Start watchdog
                startWatchdog();
                
                logger.info( "GPU camera pipeline started");
            } catch (Exception e) {
                logger.error( "Failed to start GPU pipeline", e);
                throw new RuntimeException(e);
            }
        });
    }
    
    /**
     * Initializes OpenGL context and textures.
     */
    private void initializeGl() {
        // Create EGL context
        eglCore = new EGLCore();
        
        // Create a dummy pbuffer surface and make it current
        // This is required before any OpenGL calls can be made
        dummySurface = eglCore.createPbufferSurface(1, 1);
        eglCore.makeCurrent(dummySurface);
        
        // Log GL info (now that context is current)
        GlUtil.logGlInfo();

        probeHardwareBufferBridge();

        // Create camera texture (OES type for external camera)
        cameraTextureId = GlUtil.createExternalTexture();

        // Create the ImageReader-backed camera consumer. Bypasses
        // SurfaceFlinger throttling that clamps the SurfaceTexture consumer
        // to ~8.5 fps on this device (verified by AvmImageReaderFpsProbe).
        createCameraImageReader();
        
        // Initialize GPU components now that EGL context exists
        if (recorder != null) {
            // Recorder needs to be initialized with EGLCore and encoder
            // This should be done by the caller after encoder is created
            logger.debug( "Recorder initialization deferred to caller");
        }
        
        if (downscaler != null) {
            downscaler.init();  // Default RGB mode
            logger.debug( "Downscaler initialized");
        }
        
        // Initialize foveated cropper for high-res AI crops
        foveatedCropper = new FoveatedCropper();
        foveatedCropper.init();
        
        logger.info( "OpenGL initialized (texture=" + cameraTextureId + ")");
    }
    
    /**
     * Initializes the recorder on the GL thread.
     * 
     * This must be called after the GL context is created and made current.
     * 
     * @param recorder GPU mosaic recorder to initialize
     * @param encoder Hardware encoder providing the input surface
     */
    public void initRecorderOnGlThread(GpuMosaicRecorder recorder, HardwareEventRecorderGpu encoder) {
        if (glHandler == null) {
            logger.error( "GL thread not started");
            return;
        }
        
        // Store encoder reference for draining in render loop
        this.encoder = encoder;
        
        glHandler.post(() -> {
            try {
                recorder.init(eglCore, encoder);
                logger.info( "Recorder initialized on GL thread");
                
                // Notify pipeline that recorder is ready
                if (recorderInitCallback != null) {
                    recorderInitCallback.run();
                }
            } catch (Exception e) {
                logger.error( "Failed to initialize recorder on GL thread", e);
            }
        });
    }
    
    // Callback for when recorder is initialized
    private Runnable recorderInitCallback;
    
    /**
     * Sets a callback to be invoked when the recorder is initialized.
     * 
     * @param callback Callback to run on GL thread after recorder init
     */
    public void setRecorderInitCallback(Runnable callback) {
        this.recorderInitCallback = callback;
    }
    
    /**
     * Initializes the stream scaler on the GL thread.
     * 
     * @param streamScaler GPU stream scaler to initialize
     * @param streamEncoder Hardware encoder for streaming
     */
    public void initStreamScalerOnGlThread(com.overdrive.app.streaming.GpuStreamScaler streamScaler,
                                          HardwareEventRecorderGpu streamEncoder) {
        if (glHandler == null) {
            logger.error("GL thread not started");
            return;
        }
        
        glHandler.post(() -> {
            try {
                streamScaler.init(eglCore, streamEncoder);
                logger.info("Stream scaler initialized on GL thread");
            } catch (Exception e) {
                logger.error("Failed to initialize stream scaler on GL thread", e);
            }
        });
    }
    
    /**
     * Gets the EGL core for initializing GPU components.
     * 
     * @return EGLCore instance (only valid after start() is called)
     */
    public EGLCore getEglCore() {
        return eglCore;
    }
    
    /**
     * Recreates the ImageReader consumer for camera switching.
     * 
     * The BYD AVMCamera HAL doesn't properly deliver frames to a Surface
     * that was previously connected to a different camera ID. After the first
     * frame, subsequent frames are never delivered, causing a frozen image.
     * Recreating the ImageReader consumer forces a clean connection to the new camera.
     */
    private void recreateCameraSurface() {
        logger.info("Recreating ImageReader consumer for camera switch...");
        releaseCameraConsumer();
        createCameraImageReader();
        logger.info("Camera consumer recreated for camera switch");
    }

    /** Build an ImageReader-backed consumer (zero-copy path).
     *  Frame handling:
     *    HAL → ImageReader producer (gralloc)
     *      → OnImageAvailableListener fires on imageReaderThread
     *        → acquireLatestImage / getHardwareBuffer
     *          → glHandler.post(bindHardwareBufferToTexture + notify frameSync)
     *  The listener MUST run on a thread separate from glHandler because
     *  renderLoop parks the GL thread on frameSync.wait(); a same-thread
     *  listener would starve and the HAL queue would back up, dropping
     *  frames the way we observed at boot (Stats: 0 frames). */
    private void createCameraImageReader() {
        if (imageReaderThread == null) {
            imageReaderThread = new HandlerThread("CamImageReaderCb");
            imageReaderThread.start();
            imageReaderHandler = new Handler(imageReaderThread.getLooper());
        }
        // Pool size 6 (vs the typical 3) absorbs GL-thread stalls during
        // surveillance heavy work (YOLO inference, foveated readback) without
        // throttling the HAL producer rate. At 5120×960 NV12 = 7.4 MB/buf,
        // pool=6 holds ~44 MB gralloc — well within Adreno 610 budget.
        // Pool=3 was throttling HAL emission to ~5.7 fps in surveillance mode
        // because GL frames occasionally hit 261ms (logged backpressure).
        // 6 buffers × 67ms (15 fps cycle) = 400ms slack vs 200ms.
        // PRIVATE = opaque gralloc, optimal for zero-copy GPU sampling.
        // USAGE_GPU_SAMPLED_IMAGE tells the gralloc allocator we want a
        // GPU-friendly memory layout.
        final int poolSize = 6;
        try {
            long usage = HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE;
            cameraImageReader = ImageReader.newInstance(
                width, height,
                ImageFormat.PRIVATE,
                poolSize,
                usage);
        } catch (Throwable t) {
            // Some BYD HAL builds may reject PRIVATE — fall back to YUV_420_888.
            logger.warn("ImageReader PRIVATE init failed: " + t.getMessage()
                + " — falling back to YUV_420_888");
            cameraImageReader = ImageReader.newInstance(
                width, height,
                ImageFormat.YUV_420_888,
                poolSize);
        }
        cameraImageReader.setOnImageAvailableListener(
            this::onHalImageAvailable, imageReaderHandler);
        cameraSurface = cameraImageReader.getSurface();
    }

    private void probeHardwareBufferBridge() {
        if (!com.overdrive.app.surveillance.NativeMotion.isLibraryLoaded()) {
            throw new IllegalStateException("libsurveillance not loaded before camera startup");
        }

        String report = HardwareBufferTextureBinder.probeExtensionsNative();
        nativeProbeReport = report != null ? report : "";
        nativeProbeReady = isHardwareBufferBridgeReady(nativeProbeReport);
        logger.info("HardwareBuffer bridge probe: " + nativeProbeReport);

        if (!nativeProbeReady) {
            throw new IllegalStateException("HardwareBuffer bridge missing required extensions: " + nativeProbeReport);
        }
    }

    private static boolean isHardwareBufferBridgeReady(String report) {
        if (report == null || report.isEmpty()) {
            return false;
        }
        String[] requiredTokens = new String[] {
            "fnsResolved=true",
            "ahbFromHwb=y",
            "EGL_KHR_image_base=y",
            "EGL_ANDROID_get_native_client_buffer=y",
            "EGL_ANDROID_image_native_buffer=y",
            "GL_OES_EGL_image_external=y",
            "currentDisplay=yes"
        };
        for (String token : requiredTokens) {
            if (!report.contains(token)) {
                return false;
            }
        }
        return true;
    }

    private void persistCameraConfigSnapshot(boolean validated, String failureReason) {
        try {
            org.json.JSONObject existingCam = com.overdrive.app.config.UnifiedConfigManager
                .loadConfig().optJSONObject("camera");
            int currentId = getCameraId();
            boolean existingManual = existingCam != null && existingCam.optBoolean("manualOverride", false);
            int existingId = existingCam != null ? existingCam.optInt("probedCameraId", -1) : -1;

            if (existingManual && existingId >= 0 && existingId != currentId && !manualOverrideActive) {
                logger.info("Skipping config write — manual override exists (saved=" + existingId +
                    ", running=" + currentId + ")");
                return;
            }

            org.json.JSONObject camCfg = new org.json.JSONObject();
            camCfg.put("probedCameraId", currentId);
            camCfg.put("probedSurfaceMode", cameraSurfaceMode);
            camCfg.put("cameraLayout", cameraLayout);
            camCfg.put("probedAndValidated", validated);
            camCfg.put("manualOverride", manualOverrideActive || existingManual);
            camCfg.put("fallbackFromProbe", fallbackFromProbe);
            camCfg.put("reprobeOnNextRestart", false);
            camCfg.put("sourceBmmTag", sourceBmmTag);
            camCfg.put("discoveryMethod", discoveryMethod);
            camCfg.put("vehicleCamSort", vehicleCamSort);
            camCfg.put("validatedAtMs", validatedAtMs);
            camCfg.put("validatedFrameWidth", validatedFrameWidth);
            camCfg.put("validatedFrameHeight", validatedFrameHeight);
            camCfg.put("validationSignal", validationSignal);
            camCfg.put("validationFrameCount", validationFrameCount);
            camCfg.put("stripConfidence", stripConfidence);
            camCfg.put("layoutConfidence", layoutConfidence);
            camCfg.put("quadrantVariance", quadrantVariance);
            camCfg.put("lastValidationFailure", failureReason == null ? lastValidationFailure : failureReason);
            camCfg.put("nativeProbeReport", nativeProbeReport);
            camCfg.put("nativeProbeReady", nativeProbeReady);
            camCfg.put("arbitrationMode", arbitrationMode);
            camCfg.put("fpsSetCameraResult", fpsSetCameraResult);
            camCfg.put("fpsSetMediaCodecResult", fpsSetMediaCodecResult);
            camCfg.put("lastCameraEvent", lastCameraEvent);
            if (firmwareInfo != null) {
                camCfg.put("firmwareFingerprint", firmwareInfo.fingerprint);
                camCfg.put("buildDisplay", firmwareInfo.buildDisplay);
                camCfg.put("buildIncremental", firmwareInfo.buildIncremental);
                camCfg.put("roBuildIncremental", firmwareInfo.roBuildIncremental);
                camCfg.put("productDevice", firmwareInfo.device);
                camCfg.put("vehicleCamSort", firmwareInfo.vehicleCamSort);
            }
            com.overdrive.app.config.UnifiedConfigManager.updateSection("camera", camCfg);
        } catch (Exception ex) {
            logger.warn("Failed to save camera config: " + ex.getMessage());
        }
    }

    public void persistCameraConfig(boolean validated, String failureReason) {
        persistCameraConfigSnapshot(validated, failureReason);
    }

    /** Idempotent teardown of whichever consumer is active. */
    private void releaseCameraConsumer() {
        // Release the held Image + HardwareBuffer FIRST so the gralloc slots
        // go back to the ImageReader pool before we close the reader.
        releasePreviousBoundImage();
        if (cameraSurface != null) {
            try { cameraSurface.release(); } catch (Throwable ignored) {}
            cameraSurface = null;
        }
        if (cameraImageReader != null) {
            try { cameraImageReader.close(); } catch (Throwable ignored) {}
            cameraImageReader = null;
        }
    }
    
    /**
     * Starts the BYD camera via AVMCamera reflection with multi-strategy fallback.
     * Tries constructor path first, then static factory for firmware compatibility.
     */
    private void startCamera() throws Exception {
        // GATE: Don't open camera if yielded to native app via IBYDCameraUser callback
        if (cameraCoordinator != null && cameraCoordinator.isCameraYielded()) {
            logger.info("Camera yielded to native app — skipping open");
            cameraYielded = true;
            return;
        }

        int cameraId = cameraIdOverride >= 0 ? cameraIdOverride : PHYSICAL_CAMERA_ID;

        startCameraViaAvmReflection(cameraId);

        cameraYielded = false;
        lastCameraStartTime = System.currentTimeMillis();
        logger.info("Camera started (" + width + "x" + height + 
            ", id=" + cameraId + ", surfaceMode=" + cameraSurfaceMode + ")");
        
        // Update coordinator with actual camera ID
        if (cameraCoordinator != null) {
            cameraCoordinator.setActiveCameraId(cameraId);
        }
    }

    /**
     * Opens camera via AVMCamera reflection.
     *
     * Strategy (mirrors DiPlus C4051a.m4446d() approach):
     *   1. Constructor: new AVMCamera(int) + .open() — required on this device.
     *      The static factory AVMCamera.open(int) returns null because
     *      BmmCameraInfo.isValidCamera() is empty (vehicle.config.cam_sort
     *      is unset on DiLink 5.0). The constructor bypasses that gate and
     *      is the only path that opens the camera at all.
     *   2. Static factory AVMCamera.open(int) — only if constructor is
     *      missing entirely (DiLink 6.0+ may remove it).
     *
     * See CAMERA_FPS_INVESTIGATION.md for the full rationale.
     *
     * After either path succeeds, addPreviewSurface + startPreview are called.
     *
     * Notifies IBYDCameraService before opening so the service can arbitrate
     * with native apps (reverse camera, dashcam, AVM parking view).
     */
    private void startCameraViaAvmReflection(int cameraId) throws Exception {
        // Notify camera service we're about to open
        if (cameraCoordinator != null) {
            cameraCoordinator.notifyPreOpenCamera();
        }

        Class<?> avmClass = Class.forName("android.hardware.AVMCamera");

        // === ATTEMPT 1: Constructor new AVMCamera(int) + .open() ===
        // Required on this firmware. The static factory would return null.
        try {
            Constructor<?> constructor = avmClass.getDeclaredConstructor(int.class);
            constructor.setAccessible(true);
            cameraObj = constructor.newInstance(cameraId);

            Method mOpen = avmClass.getDeclaredMethod("open");
            mOpen.setAccessible(true);
            if (!(boolean) mOpen.invoke(cameraObj)) {
                throw new RuntimeException("AVMCamera.open() returned false (id=" + cameraId + ")");
            }
            logger.info("Camera opened via constructor path (id=" + cameraId + ")");
        } catch (NoSuchMethodException e) {
            // Constructor with int param doesn't exist — fall back to static factory
            logger.info("AVMCamera(int) constructor not found — trying static factory");
            cameraObj = null;

            // === ATTEMPT 2: Static factory AVMCamera.open(cameraId) ===
            try {
                Method mStaticOpen = avmClass.getDeclaredMethod("open", int.class);
                mStaticOpen.setAccessible(true);
                cameraObj = mStaticOpen.invoke(null, cameraId);
                if (cameraObj != null) {
                    logger.info("Camera opened via static factory (id=" + cameraId + ")");
                } else {
                    logger.info("AVMCamera.open(" + cameraId + ") returned null — trying IDs 0-5");
                    for (int tryId = 0; tryId <= 5; tryId++) {
                        if (tryId == cameraId) continue;
                        cameraObj = mStaticOpen.invoke(null, tryId);
                        if (cameraObj != null) {
                            logger.info("Camera opened via static factory probe (id=" + tryId + ")");
                            cameraIdOverride = tryId;
                            break;
                        }
                    }
                }
                if (cameraObj == null) {
                    throw new RuntimeException("AVMCamera.open() returned null for all IDs 0-5");
                }
            } catch (NoSuchMethodException e2) {
                throw new RuntimeException(
                    "AVMCamera API not compatible: no constructor(int) and no static open(int). " +
                    "Available constructors: " + Arrays.toString(avmClass.getDeclaredConstructors()) +
                    ", methods: " + Arrays.toString(avmClass.getDeclaredMethods()), e2);
            }
        }
        
        // Set FPS BEFORE addPreviewSurface. On DiLink 3.x firmware the HAL
        // rejects setCameraFps once a preview surface is attached — even before
        // startPreview. Order must be open → setCameraFps → addPreviewSurface →
        // startPreview to match the BYD HAL state machine.
        boolean fpsOk = AvmCameraHelper.setCameraFps(cameraObj, targetFps);
        fpsSetCameraResult = fpsOk
            ? "startup:setCameraFps(" + targetFps + ")=ok"
            : "startup:setCameraFps(" + targetFps + ")=failed";
        // We intentionally stop at setCameraFps here. The encoder-owned
        // MediaCodec path is surfaced only as diagnostics until a real owner
        // passes the codec across the boundary.

        // Connect surface — mode 0 works on Seal, other models may need different mode
        Method mAddSurface = avmClass.getDeclaredMethod("addPreviewSurface", Surface.class, int.class);
        mAddSurface.setAccessible(true);
        mAddSurface.invoke(cameraObj, cameraSurface, cameraSurfaceMode);

        // Start preview — required for real frame data on BYD Seal HAL.
        // The HAL supports multiple consumers calling startPreview simultaneously.
        // The AVC warmup (com.byd.avc launch + 4s delay) ensures the native DVR
        // has already initialized before we reach here, preventing race conditions.
        Method mStart = avmClass.getDeclaredMethod("startPreview");
        mStart.setAccessible(true);
        mStart.invoke(cameraObj);
        logger.info("Camera started (id=" + cameraId + ", targetFps=" + targetFps + ")");
    }
    
    // Diagnostic counters for the ImageReader frame flow. Kept in place as
    // permanent instrumentation since the path crosses two threads + a
    // gralloc lifetime boundary; surfacing health via 2-min Stats line is
    // cheap and useful in field debugging.
    private volatile long irFireCount = 0;       // onHalImageAvailable invocations
    private volatile long irAcquireOkCount = 0;
    private volatile long irAcquireNullCount = 0;
    private volatile long irBindFailCount = 0;
    private volatile long lastIrDiagLogMs = 0;

    /**
     * Called when a new gralloc buffer is available from the HAL
     * (ImageReader path, API 28+). Runs on imageReaderThread (NOT glThread)
     * — we cannot do the EGLImage bind here because the EGL context lives
     * on the GL thread.
     *
     * Strategy: notify frameSync so renderLoop wakes up. renderLoop will
     * do acquireLatestImage + getHardwareBuffer + bind on the GL thread
     * where the EGL context is current. This mirrors the SurfaceTexture
     * path where the producer notifies and the consumer thread does
     * updateTexImage.
     */
    private void onHalImageAvailable(ImageReader r) {
        irFireCount++;
        synchronized (frameSync) {
            imagePending = true;
            frameSync.notify();
        }
    }

    /**
     * Acquires the latest gralloc buffer from cameraImageReader and binds it
     * to cameraTextureId. MUST be called from the GL thread (current EGL
     * context required for glEGLImageTargetTexture2DOES).
     *
     * Returns true if a frame was bound; false if no frame was ready or
     * the bind failed. acquireLatestImage drops older buffered frames if
     * the GL loop falls behind, matching SurfaceTexture's "always sample
     * latest" semantics.
     */
    // The Image and HardwareBuffer currently bound to cameraTextureId.
    // Held alive across GL render cycles — closing them returns the gralloc
    // slot to the ImageReader pool, which invalidates the EGLImage we bound
    // and causes the producer side to stall. Released only when the NEXT
    // bind succeeds (releasePreviousImage call inside consumeLatestImageAndBind),
    // so the texture always references a live gralloc buffer.
    //
    // THREAD-CONFINED to the GL thread (renderLoop). All reads and writes
    // happen inside consumeLatestImageAndBind / releasePreviousBoundImage,
    // which are only invoked from renderLoop. Do NOT access from the
    // ImageReader callback thread, watchdog, or any daemon thread — touching
    // these from another thread will leak the gralloc slot and stall the HAL.
    private Image currentBoundImage;             // @GuardedBy(GL thread)
    private HardwareBuffer currentBoundHwBuffer; // @GuardedBy(GL thread)

    private boolean consumeLatestImageAndBind() {
        ImageReader reader = cameraImageReader;
        if (reader == null) return false;
        Image image = null;
        HardwareBuffer hwBuffer = null;
        boolean transferredOwnership = false;
        try {
            image = reader.acquireLatestImage();
            if (image == null) {
                irAcquireNullCount++;
                return false;
            }
            irAcquireOkCount++;
            hwBuffer = image.getHardwareBuffer();
            if (hwBuffer == null) {
                logger.warn("Image.getHardwareBuffer() returned null — dropping frame");
                irBindFailCount++;
                return false;
            }
            boolean bound = HardwareBufferTextureBinder
                .bindHardwareBufferToTextureNative(hwBuffer, cameraTextureId);
            if (!bound) {
                logger.warn("bindHardwareBufferToTexture failed — dropping frame");
                irBindFailCount++;
                return false;
            }
            // Bind succeeded. NOW it's safe to release the previous image —
            // the texture is no longer pointing at it.
            releasePreviousBoundImage();
            // Transfer ownership of this image+hwBuffer into the held slots.
            currentBoundImage = image;
            currentBoundHwBuffer = hwBuffer;
            transferredOwnership = true;
            return true;
        } catch (Throwable t) {
            logger.warn("consumeLatestImageAndBind error: " + t.getMessage());
            irBindFailCount++;
            return false;
        } finally {
            // Only close locally if we did NOT transfer ownership to the
            // held slots. On the success path the held slots own the refs;
            // on failure paths we close immediately to release the slot.
            if (!transferredOwnership) {
                if (hwBuffer != null) {
                    try { hwBuffer.close(); } catch (Throwable ignored) {}
                }
                if (image != null) {
                    try { image.close(); } catch (Throwable ignored) {}
                }
            }
        }
    }

    private void releasePreviousBoundImage() {
        if (currentBoundHwBuffer != null) {
            try { currentBoundHwBuffer.close(); } catch (Throwable ignored) {}
            currentBoundHwBuffer = null;
        }
        if (currentBoundImage != null) {
            try { currentBoundImage.close(); } catch (Throwable ignored) {}
            currentBoundImage = null;
        }
    }

    /** Periodic diagnostic for the ImageReader path. Throttled to align with
     *  the 2-minute Stats log so it rides along instead of spamming. */
    private void maybeLogImageReaderDiag() {
        long now = System.currentTimeMillis();
        if (now - lastIrDiagLogMs < STATS_INTERVAL_MS) return;
        lastIrDiagLogMs = now;
        logger.info(String.format(
            "IR-diag: fire=%d acqOk=%d acqNull=%d bindFail=%d",
            irFireCount, irAcquireOkCount, irAcquireNullCount, irBindFailCount));
    }
    
    /**
     * Main render loop - distributes frames to recording and AI lanes.
     */
    private void renderLoop() {
        if (!running) {
            return;
        }

        try {
            // Wait for new frame (hardware sync). Skip the wait if the HAL
            // already signaled while we were processing the previous frame —
            // otherwise the unconditional wait() would miss that notify and
            // park us until the NEXT HAL fire, capping effective FPS.
            synchronized (frameSync) {
                if (!imagePending) {
                    try {
                        frameSync.wait(100);  // Timeout to check running flag
                    } catch (InterruptedException e) {
                        // Continue
                    }
                }
                imagePending = false;
            }

            if (!running) {
                return;
            }

            // Update watchdog heartbeat
            lastGlThreadHeartbeat = System.currentTimeMillis();
            maybeLogImageReaderDiag();

            // SOTA: Skip frame processing if camera is yielded to native app,
            // not yet open, or being torn down/reopened by the daemon thread
            // (reopenCamera/restartCameraAfterError). The restartInProgress
            // gate is essential — without it the GL thread can race the
            // daemon thread's close and block in updateTexImage() against a
            // dead BufferQueue, freezing the GL thread until the watchdog
            // kills the process.
            if (cameraYielded || cameraObj == null || restartInProgress.get()) {
                // GL thread stays alive but doesn't touch camera — waiting for re-acquire
                return;
            }

            // ImageReader path: acquireLatestImage + getHardwareBuffer +
            // glEGLImageTargetTexture2DOES binds the freshest gralloc buffer
            // to cameraTextureId. Runs on the GL thread (current EGL
            // context). If no new frame is ready (spurious wakeup or notify
            // race), return — the finally re-posts the loop and we wait again.
            if (cameraImageReader == null) {
                return;
            }
            // Per-stage timing — measure each phase of the GL frame so we can
            // attribute backpressure (logged at most once per 2 s, worst-case
            // frame only). nanoTime() is a monotonic call, ~50ns each.
            long stageT0 = System.nanoTime();
            if (!consumeLatestImageAndBind()) {
                return;
            }
            long stageAfterAcquireNs = System.nanoTime();
            frameCounter++;
            lastFrameTime = System.currentTimeMillis();
            firstFrameReceived = true;
            consecutiveContentionStalls = 0;  // Frames flowing — clear stall counter
            
            // SOTA: Full-matrix auto-probe at frame 15 (~2 sec).
            // Sweeps camera IDs 0-5 × surface modes 0-5 to find the first
            // combination that produces panoramic image data. Each combo gets
            // 15 frames to warm up before pixel readback.
            if (frameCounter == 15 && downscaler != null && !skipFrameValidation) {
                try {
                    byte[] probe = downscaler.readPixels(cameraTextureId, 8, 8);
                    boolean hasData = false;
                    if (probe != null) {
                        for (int i = 0; i < Math.min(probe.length, 192); i++) {
                            if ((probe[i] & 0xFF) > 10) { hasData = true; break; }
                        }
                    }
                    int currentId = cameraIdOverride >= 0 ? cameraIdOverride : PHYSICAL_CAMERA_ID;
                    boolean isPanoramic = width >= 5000;
                    boolean stripVerified = verifyPanoramicStrip(probe);
                    String stripConfidenceValue = stripVerified ? "high" : "low";
                    String layoutConfidenceValue = stripVerified
                        ? (cameraLayout == 1 ? "apa_candidate" : "high")
                        : (cameraLayout == 1 ? "unknown" : "low");
                    String quadrantVarianceValue = summarizeQuadrantVariance(probe);
                    setQuadrantVariance(quadrantVarianceValue);
                    logger.info("Camera ID " + currentId + " probe: " + 
                        (hasData ? "HAS DATA" : "BLACK") +
                        " | resolution=" + width + "x" + height +
                        " | type=" + (isPanoramic ? "PANORAMIC" : "SINGLE") +
                        " | surfaceMode=" + cameraSurfaceMode);
                    
                    if (hasData && isPanoramic) {
                        // Track this camera as having real data (for fallback if strip check fails)
                        lastDataCameraId = currentId;
                        recordValidationSnapshot(
                            stripVerified,
                            stripVerified ? "frame15_non_black_5120x960" : "frame15_non_black_low_layout_confidence",
                            stripConfidenceValue,
                            layoutConfidenceValue,
                            stripVerified ? "" : "frame15_low_layout_confidence",
                            width,
                            height,
                            frameCounter);
                        
                        // During auto-probe: accept the first camera with non-black panoramic data.
                        // The 5120x960 resolution IS the panoramic strip identifier on BYD — no other
                        // camera output uses this resolution with real image data. The luma-based
                        // strip check was producing false negatives in low-light/uniform scenes.
                        if (autoProbeCameras) {
                            logger.info("Auto-probe: SELECTED camera ID " + currentId + 
                                " (panoramic data confirmed, surfaceMode=" + cameraSurfaceMode +
                                ", stripVerified=" + stripVerified + ")");
                            autoProbeCameras = false;
                            probeStartId = -1;
                            probeComplete = true;
                            lastDataCameraId = -1;
                            persistCameraConfigSnapshot(stripVerified, stripVerified ? null : "frame15_low_layout_confidence");
                            logger.info("Probe complete — recording/streaming/AI lanes now active");
                            if (probeCallback != null) {
                                probeCallback.onCameraFound(currentId, cameraSurfaceMode);
                            }
                        } else {
                            // Not in auto-probe mode — this is the frame-15 check for a saved config.
                            // Camera has data at panoramic resolution — it's working correctly.
                            // No further validation needed (skipFrameValidation handles saved configs,
                            // but this path covers the default camera ID 1 on first boot).
                            probeComplete = true;
                            persistCameraConfigSnapshot(stripVerified, stripVerified ? null : "frame15_low_layout_confidence");
                        }
                    } else if (autoProbeCameras) {
                        // Advance to next combination in the matrix
                        advanceProbeToNext(currentId);
                    } else if (!hasData) {
                        // Saved config gave black frames at frame 15. This could be:
                        // 1. HAL warmup (normal — wait longer)
                        // 2. OEM dashcam contention (transient)
                        // 3. Genuinely wrong camera ID (BmmCameraInfo returned wrong value)
                        //
                        // Don't re-probe immediately (causes OEM dashcam "no signal").
                        // Instead, schedule a second check at frame 50 (~5s). If still black
                        // at that point, the saved config is genuinely wrong and we re-probe.
                        recordValidationSnapshot(
                            false,
                            "frame15_black",
                            "low",
                            cameraLayout == 1 ? "unknown" : "low",
                            "frame15_black",
                            width,
                            height,
                            frameCounter);
                        logger.warn("Frame 15 readback BLACK for cam=" + currentId +
                            ", surfaceMode=" + cameraSurfaceMode +
                            " — will recheck at frame 50 before deciding");
                    }
                } catch (Exception e) {
                    logger.warn("Camera probe failed: " + e.getMessage());
                }
            }
            
            // Frame 50 recheck (~5s): if frame 15 was black, verify again.
            // By frame 50 the HAL has definitely warmed up. If still black, the saved
            // config is genuinely wrong (BmmCameraInfo returned incorrect ID).
            // Only then trigger a re-probe — this is rare and justified.
            if (frameCounter == 50 && !autoProbeCameras && !skipFrameValidation && downscaler != null) {
                try {
                    byte[] probe = downscaler.readPixels(cameraTextureId, 8, 8);
                    boolean hasData = false;
                    if (probe != null) {
                        for (int i = 0; i < Math.min(probe.length, 192); i++) {
                            if ((probe[i] & 0xFF) > 10) { hasData = true; break; }
                        }
                    }
                    if (!hasData) {
                        int currentId = cameraIdOverride >= 0 ? cameraIdOverride : PHYSICAL_CAMERA_ID;
                        boolean stripVerified = verifyPanoramicStrip(probe);
                        String quadrantVarianceValue = summarizeQuadrantVariance(probe);
                        setQuadrantVariance(quadrantVarianceValue);
                        recordValidationSnapshot(
                            stripVerified,
                            stripVerified ? "frame50_non_black_5120x960" : "frame50_non_black_low_layout_confidence",
                            stripVerified ? "high" : "low",
                            stripVerified ? (cameraLayout == 1 ? "apa_candidate" : "high")
                                : (cameraLayout == 1 ? "unknown" : "low"),
                            stripVerified ? "" : "frame50_low_layout_confidence",
                            width,
                            height,
                            frameCounter);
                        logger.warn("Frame 50 STILL BLACK for cam=" + currentId +
                            " — saved config is wrong, starting re-probe");
                        persistCameraConfigSnapshot(false, "frame50_black");
                        setAutoProbeCameras(true);
                        probeComplete = false;
                        lastDataCameraId = -1;
                        advanceProbeToNext(currentId);
                    } else {
                        // Camera has non-black data at frame 50 — it's working.
                        // Persist as validated so next restart skips all frame checks.
                        // BUT: don't overwrite if user has a manual override set — they may have
                        // changed the camera ID in the UI and it hasn't taken effect yet.
                        int currentId = cameraIdOverride >= 0 ? cameraIdOverride : PHYSICAL_CAMERA_ID;
                        boolean stripVerified = verifyPanoramicStrip(probe);
                        String quadrantVarianceValue = summarizeQuadrantVariance(probe);
                        setQuadrantVariance(quadrantVarianceValue);
                        String stripConfidenceValue = stripVerified ? "high" : "low";
                        String layoutConfidenceValue = stripVerified
                            ? (cameraLayout == 1 ? "apa_candidate" : "high")
                            : (cameraLayout == 1 ? "unknown" : "low");
                        logger.info("Frame 50 recheck: camera ID " + currentId + " confirmed working");
                        probeComplete = true;
                        recordValidationSnapshot(
                            stripVerified,
                            stripVerified ? "frame50_non_black_5120x960" : "frame50_non_black_low_layout_confidence",
                            stripConfidenceValue,
                            layoutConfidenceValue,
                            stripVerified ? "" : "frame50_low_layout_confidence",
                            width,
                            height,
                            frameCounter);
                        persistCameraConfigSnapshot(stripVerified, stripVerified ? null : "frame50_low_layout_confidence");
                    }
                } catch (Exception e) {
                    logger.warn("Frame 50 recheck failed: " + e.getMessage());
                }
            }

            long loopStartNs = System.nanoTime();

            // SOTA: Gate all consumer passes until probe finds a working camera.
            // Without this, the encoder records BLACK frames, the stream shows garbage,
            // and the AI lane processes empty images during the probe sweep.
            if (!probeComplete) {
                // Still probing — don't feed consumers. Heartbeat already
                // updated above. Re-post handled by the finally block.
                return;
            }

            // PASS 1: Recording (Zero-Copy GPU Path)
            // SOTA: Always render to encoder (for pre-record circular buffer)
            GpuMosaicRecorder localRecorder = recorder;
            HardwareEventRecorderGpu localEncoder = encoder;
            long stageBeforeMosaicNs = System.nanoTime();
            if (localRecorder != null) {
                localRecorder.drawFrame(cameraTextureId);

                // CRITICAL: Drain encoder immediately after frame submission
                // This prevents eglSwapBuffers from blocking when encoder buffers fill up
                if (localEncoder != null) {
                    localEncoder.drainEncoder();
                }

                // RECOVERY: If encoder surface died (EGL_BAD_SURFACE after prolonged use),
                // reinitialize the encoder and reconnect the recorder.
                // P1 #9: keep using localRecorder/localEncoder captured above.
                // pipeline.stop() runs on the daemon thread and can null
                // this.recorder/this.encoder concurrently; re-reading the fields
                // here would NPE.
                if (localRecorder.needsReinit() && localEncoder != null) {
                    logger.warn("Encoder surface lost - reinitializing encoder...");
                    // Extend the GL watchdog window: encoder.release() joins
                    // the drainer (up to 2s) plus MediaCodec stop/release —
                    // the bare 3s GL timeout is not enough headroom.
                    // P1 #11: CAS so a concurrent reopenCamera (daemon thread)
                    // can't race; if another restart is already in flight,
                    // skip — it'll re-fire on the next frame.
                    if (!restartInProgress.compareAndSet(false, true)) {
                        return;
                    }
                    try {
                        // Full teardown of recorder GL resources. Without this,
                        // shader programs (programId, overlayProgramId) and the
                        // overlay texture (overlayTextureId) leak on every
                        // reinit, since recorder.init() only frees the encoder
                        // surface, not the programs/textures it then re-creates.
                        localRecorder.release();
                        localEncoder.release();
                        localEncoder.init();
                        localRecorder.init(eglCore, localEncoder);
                        localRecorder.clearReinitFlag();
                        logger.info("Encoder reinitialized successfully after surface loss");
                    } catch (Exception reinitEx) {
                        logger.error("Encoder reinit failed: " + reinitEx.getMessage());
                        // If reinit fails, force process restart — EGL context is likely corrupt
                        logger.error("CRITICAL: Encoder reinit failed, forcing process restart");
                        try { Thread.sleep(100); } catch (InterruptedException ignored) {}
                        System.exit(0);
                    } finally {
                        restartInProgress.set(false);
                    }
                }
            }

            // PASS 1B: Streaming (Parallel Zero-Copy GPU Path)
            // Only runs if streaming is enabled - uses separate encoder at lower resolution
            // Capture local refs to avoid NPE from concurrent pipeline shutdown
            com.overdrive.app.streaming.GpuStreamScaler localStreamScaler = streamScaler;
            HardwareEventRecorderGpu localStreamEncoder = streamEncoder;
            if (localStreamScaler != null && localStreamEncoder != null) {
                localStreamScaler.drawFrame(cameraTextureId);
                localStreamEncoder.drainEncoder();
            }

            // PASS 2: AI Lane (decoupled, async).
            //
            // GL thread: read pixels (~5-15ms) and post the byte[] to the AI
            // worker. Worker runs sentry.processFrame on its own thread —
            // V2 motion native (~30-100ms) and YOLO (which already dispatches
            // to its own aiExecutor inside SurveillanceEngineGpu) no longer
            // block the GL render loop.
            //
            // Drop-not-queue: if the worker is still processing the previous
            // frame, the new frame is recycled and dropped. V2 motion is
            // throttled to 10 fps internally so backlog has zero benefit.
            //
            // FRAME-COUNTER THROTTLE: AI readback runs once per
            // AI_READBACK_FRAME_MODULO frames the GL thread processes.
            // Wall-clock throttling collapses when readback duration approaches
            // the interval — the GL thread always finds the timer expired and
            // every frame triggers readback, capping the loop at ~8 fps.
            // Frame-modulo is immune: AI rate scales with HAL emission rate.
            //
            // Foveated cropper still runs on GL thread (inside processFrame's
            // call chain via setFoveatedCropper), but only when sentry
            // schedules it after motion detection — not every frame.
            long stageBeforeAiReadbackNs = System.nanoTime();
            long stageAfterAiReadbackNs = stageBeforeAiReadbackNs;
            long stageAfterAiSubmitNs = stageBeforeAiReadbackNs;
            if (sentry != null && sentry.isActive() && downscaler != null && aiLaneWorker != null) {
                boolean aiFrameTurn = (frameCounter % AI_READBACK_FRAME_MODULO == 0);
                if (!aiFrameTurn || aiLaneWorker.isBusy()) {
                    // Not this frame's turn, or the worker is still processing
                    // the previous frame. Skip readback — let GL thread fly
                    // through mosaic+swap to drain the ImageReader pool.
                    stageWindowAiReadbackSkips++;
                } else {
                    try {
                        byte[] smallFrame = downscaler.readPixelsDirect(cameraTextureId);
                        stageAfterAiReadbackNs = System.nanoTime();
                        if (smallFrame != null) {
                            // Lazy-wire foveated cropper once. GL thread safe.
                            if (foveatedCropper != null && foveatedCropper.isInitialized()
                                    && sentry.getFoveatedCropper() == null) {
                                sentry.setFoveatedCropper(foveatedCropper, cameraTextureId);
                            }
                            // submitFrame is non-blocking: queues if worker idle,
                            // recycles+drops if busy. Either way, GL thread
                            // continues to next camera frame immediately.
                            aiLaneWorker.submitFrame(smallFrame);
                        }
                        stageAfterAiSubmitNs = System.nanoTime();
                    } catch (Exception e) {
                        logger.warn("AI lane error: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
                    }
                }
            }

            // Per-stage timing roll-up. We track only the worst frame per
            // 2 s window to keep the log line bounded and the reasoning
            // simple — the worst frame is what crosses the encoder
            // backpressure threshold and triggers HAL throttling.
            long stageEndNs = System.nanoTime();
            long stageTotalNs    = stageEndNs - stageT0;
            long stageAcquireNs  = stageAfterAcquireNs - stageT0;
            long stageMosaicNs   = stageBeforeAiReadbackNs - stageBeforeMosaicNs;
            long stageReadbackNs = stageAfterAiReadbackNs  - stageBeforeAiReadbackNs;
            long stageSubmitNs   = stageAfterAiSubmitNs    - stageAfterAiReadbackNs;
            stageWindowFrames++;
            if (stageTotalNs > stageWorstTotalNs) {
                stageWorstTotalNs       = stageTotalNs;
                stageWorstAcquireNs     = stageAcquireNs;
                stageWorstMosaicNs      = stageMosaicNs;
                stageWorstAiReadbackNs  = stageReadbackNs;
                stageWorstAiSubmitNs    = stageSubmitNs;
            }
            long nowMs = System.currentTimeMillis();
            if (stageTimingWindowStartMs == 0) {
                stageTimingWindowStartMs = nowMs;
            } else if (nowMs - stageTimingWindowStartMs >= STAGE_TIMING_LOG_INTERVAL_MS) {
                logger.info(String.format(
                        "Stage(worst/2s): total=%dms acq=%dms mosaic+swap=%dms aiReadback=%dms aiSubmit=%dms (frames=%d, aiSkips=%d)",
                        stageWorstTotalNs / 1_000_000,
                        stageWorstAcquireNs / 1_000_000,
                        stageWorstMosaicNs / 1_000_000,
                        stageWorstAiReadbackNs / 1_000_000,
                        stageWorstAiSubmitNs / 1_000_000,
                        stageWindowFrames,
                        stageWindowAiReadbackSkips));
                stageWorstTotalNs = 0;
                stageWorstAcquireNs = 0;
                stageWorstMosaicNs = 0;
                stageWorstAiReadbackNs = 0;
                stageWorstAiSubmitNs = 0;
                stageWindowFrames = 0;
                stageWindowAiReadbackSkips = 0;
                stageTimingWindowStartMs = nowMs;
            }

            // Log stats periodically (every 2 minutes, time-based).
            // Reports the *windowed* FPS (frames since the last stats log) instead
            // of the lifetime average — otherwise a stall during one window drags
            // the running mean down forever and masks recovery in later windows.
            long now = System.currentTimeMillis();
            if (now - lastStatsTime >= STATS_INTERVAL_MS) {
                long windowMs = (lastStatsTime == 0) ? (now - startTime) : (now - lastStatsTime);
                int windowFrames = frameCounter - lastStatsFrameCount;
                float fps = windowMs > 0 ? (windowFrames * 1000.0f) / windowMs : 0f;
                measuredFps = fps;

                long aiProc = aiLaneWorker != null ? aiLaneWorker.getProcessedFrames() : 0;
                long aiDrop = aiLaneWorker != null ? aiLaneWorker.getDroppedFrames() : 0;
                long uptimeS = (now - startTime) / 1000;
                logger.info(String.format(
                        "Stats: %d frames (window), %.1f FPS (target=%d), uptime=%ds, aiProcessed=%d, aiDropped=%d",
                        windowFrames, fps, targetFps, uptimeS, aiProc, aiDrop));
                if (aiLaneWorker != null) {
                    aiLaneWorker.resetCounters();
                }

                lastStatsTime = now;
                lastStatsFrameCount = frameCounter;
            }

        } catch (Exception e) {
            String msg = e.getMessage();
            if (msg == null) {
                msg = e.getClass().getSimpleName();
            }
            logger.error("Render loop error: " + msg, e);
        } finally {
            // Schedule next frame in finally so any `return` inside the try
            // (e.g., consumeLatestImageAndBind() returning false on a frame
            // where no new image is ready) still re-posts the loop. Without
            // this, the GL thread stops iterating and the watchdog kills us.
            if (running) {
                glHandler.post(this::renderLoop);
            }
        }
    }

    private String summarizeQuadrantVariance(byte[] probe8x8) {
        // This is diagnostics only. It turns the 8x8 probe into a compact
        // summary so logs and persisted config can explain *why* a frame was
        // classified as low-confidence without storing the raw pixels.
        if (probe8x8 == null || probe8x8.length < 192) {
            return "";
        }
        int[] qLuma = new int[4];
        int[] qCnt = new int[4];
        int[] qMin = {255, 255, 255, 255};
        int[] qMax = {0, 0, 0, 0};
        int totalNonBlack = 0;
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                int idx = (y * 8 + x) * 3;
                int r = probe8x8[idx] & 0xFF, g = probe8x8[idx+1] & 0xFF, b = probe8x8[idx+2] & 0xFF;
                int luma = (r + g * 2 + b) / 4;
                int q = x / 2;
                qLuma[q] += luma;
                qCnt[q]++;
                if (luma < qMin[q]) qMin[q] = luma;
                if (luma > qMax[q]) qMax[q] = luma;
                if (luma > 10) totalNonBlack++;
            }
        }
        for (int q = 0; q < 4; q++) {
            if (qCnt[q] > 0) qLuma[q] /= qCnt[q];
        }
        return "Q0=" + qLuma[0] + "/" + (qMax[0] - qMin[0])
            + " Q1=" + qLuma[1] + "/" + (qMax[1] - qMin[1])
            + " Q2=" + qLuma[2] + "/" + (qMax[2] - qMin[2])
            + " Q3=" + qLuma[3] + "/" + (qMax[3] - qMin[3])
            + " nonBlack=" + totalNonBlack + "/64";
    }

    /**
     * Verifies that the camera is producing a real panoramic strip (4 distinct views)
     * rather than a single camera stretched or AVM bird's-eye view.
     *
     * A real panoramic strip has 4 cameras stitched side by side. Each quadrant shows
     * a different scene. We verify by reading pixel samples from each quadrant and
     * checking that they have significantly different luma values.
     *
     * Uses the downscaler's 8x8 readback. Columns 0-1=Q0, 2-3=Q1, 4-5=Q2, 6-7=Q3.
     */
    private boolean verifyPanoramicStrip(byte[] probe8x8) {
        if (probe8x8 == null || probe8x8.length < 192) return false;
        int[] qLuma = new int[4];
        int[] qCnt = new int[4];
        int[] qMin = {255, 255, 255, 255};
        int[] qMax = {0, 0, 0, 0};
        int totalNonBlack = 0;
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                int idx = (y * 8 + x) * 3;
                int r = probe8x8[idx] & 0xFF, g = probe8x8[idx+1] & 0xFF, b = probe8x8[idx+2] & 0xFF;
                int luma = (r + g*2 + b) / 4;
                int q = x / 2;
                qLuma[q] += luma; qCnt[q]++;
                if (luma < qMin[q]) qMin[q] = luma;
                if (luma > qMax[q]) qMax[q] = luma;
                if (luma > 10) totalNonBlack++;
            }
        }
        for (int q = 0; q < 4; q++) { if (qCnt[q] > 0) qLuma[q] /= qCnt[q]; }
        
        // Primary check: luma difference between quadrant pairs.
        // A real panoramic strip has 4 cameras showing different scenes.
        int diffPairs = 0;
        for (int i = 0; i < 4; i++) for (int j = i+1; j < 4; j++) if (Math.abs(qLuma[i]-qLuma[j]) > 15) diffPairs++;
        boolean isStrip = diffPairs >= 2;
        
        // Secondary check: if all quadrants have real (non-black) data with internal
        // variance, this is a real camera feed even if the scenes look similar.
        // This handles the common case of a parked car in a garage/at night where
        // all 4 cameras see similar dark scenes (low inter-quadrant difference)
        // but each quadrant still has texture/detail (intra-quadrant variance).
        if (!isStrip && totalNonBlack >= 48) {  // At least 75% of pixels are non-black
            int quadrantsWithVariance = 0;
            for (int q = 0; q < 4; q++) {
                // Each quadrant has internal texture (not a flat solid color)
                if (qMax[q] - qMin[q] >= 3) quadrantsWithVariance++;
            }
            // Accept if all quadrants have real data (non-black) and at least 3 have
            // internal variance. This distinguishes a real 4-camera feed from a
            // synthetic AVM bird's-eye view (which would have large inter-quadrant
            // differences) or a single stretched camera (which would have identical
            // min/max patterns across all quadrants).
            if (quadrantsWithVariance >= 3) {
                isStrip = true;
                logger.info("Strip accepted via secondary check: " + quadrantsWithVariance + 
                    " quadrants with variance, " + totalNonBlack + "/64 non-black pixels");
            }
        }
        
        logger.info("Strip check: Q0=" + qLuma[0] + " Q1=" + qLuma[1] + " Q2=" + qLuma[2] + " Q3=" + qLuma[3] +
                " diffPairs=" + diffPairs + " → " + (isStrip ? "STRIP" : "NOT_STRIP"));
        return isStrip;
    }

    /**
     * SOTA: Advance to the next camera ID during probe.
     * Surface mode 0 is confirmed working on all tested models — only probe camera IDs 0-5.
     * 
     * @param skipId Camera ID to skip (the one we just tested). -1 to start fresh.
     */
    private int[] buildSurfaceModeProbeCameraIds() {
        java.util.LinkedHashSet<Integer> ids = new java.util.LinkedHashSet<>();
        if (lastDataCameraId >= 0) {
            ids.add(lastDataCameraId);
        }
        if (probeStartId >= 0) {
            ids.add(probeStartId);
        }
        ids.add(cameraIdOverride >= 0 ? cameraIdOverride : PHYSICAL_CAMERA_ID);
        for (int i = 0; i <= MAX_CAMERA_ID; i++) {
            ids.add(i);
        }
        int[] ordered = new int[ids.size()];
        int idx = 0;
        for (Integer id : ids) {
            ordered[idx++] = id;
        }
        return ordered;
    }

    private boolean advanceProbeSurfaceModeMatrix(int skipId) {
        if (probeMatrixCameraIds == null || probeMatrixCameraIds.length == 0) {
            probeMatrixCameraIds = buildSurfaceModeProbeCameraIds();
            probeMatrixCameraIndex = 0;
        }

        // Sweep one surface mode at a time so we can preserve the same
        // cleanup/settle order across every candidate camera ID.
        while (probeNextSurfaceMode <= 5) {
            while (probeMatrixCameraIndex < probeMatrixCameraIds.length) {
                int tryId = probeMatrixCameraIds[probeMatrixCameraIndex++];
                if (tryId == skipId) {
                    continue;
                }

                logger.info("Auto-probe: trying camera ID " + tryId +
                    " [surfaceMode=" + probeNextSurfaceMode + "]");

                cameraIdOverride = tryId;
                cameraSurfaceMode = probeNextSurfaceMode;
                frameCounter = 0;
                lastStatsFrameCount = 0;
                lastGlThreadHeartbeat = System.currentTimeMillis();

                recreateCameraSurface();
                lastGlThreadHeartbeat = System.currentTimeMillis();

                try {
                    try { Thread.sleep(500); } catch (InterruptedException ignored) {}
                    startCamera();
                    if (cameraCoordinator != null && cameraObj != null) {
                        cameraCoordinator.setupEventCallback(cameraObj);
                    }
                    return true;
                } catch (Exception e) {
                    logger.info("Auto-probe: camera ID " + tryId +
                        " surfaceMode=" + probeNextSurfaceMode +
                        " failed to open: " + e.getMessage());
                    cameraObj = null;
                    try { Thread.sleep(500); } catch (InterruptedException ignored) {}
                }
            }

            probeMatrixCameraIndex = 0;
            probeNextSurfaceMode++;
        }

        return false;
    }

    private void advanceProbeToNext(int skipId) {
        // Close current camera cleanly
        if (cameraObj != null) {
            try {
                BydCameraCoordinator.closeCamera(cameraObj, cameraSurfaceMode);
            } catch (Exception closeEx) {
                logger.warn("Error closing camera for probe: " + closeEx.getMessage());
            }
            cameraObj = null;
            if (cameraCoordinator != null) {
                cameraCoordinator.resetEventCallbackState();
            }
        }
        
        // CRITICAL: Let the BYD camera HAL settle between close and next open.
        // Without this delay, rapid camera cycling overwhelms the HAL service
        // and triggers a system watchdog reboot.
        try { Thread.sleep(1500); } catch (InterruptedException ignored) {}
        
        boolean found = false;
        if (!probeSurfaceModeMatrixActive) {
            // Probe camera IDs 0-5 with surface mode 0 first.
            while (probeNextCameraId <= MAX_CAMERA_ID) {
                int tryId = probeNextCameraId;
                probeNextCameraId++;

                // Skip the ID we just tested
                if (tryId == skipId) {
                    continue;
                }

                logger.info("Auto-probe: trying camera ID " + tryId +
                    " [" + (tryId + 1) + "/" + (MAX_CAMERA_ID + 1) + "]");

                cameraIdOverride = tryId;
                cameraSurfaceMode = 0;  // Surface mode 0 confirmed working
                frameCounter = 0;
                lastStatsFrameCount = 0;
                lastGlThreadHeartbeat = System.currentTimeMillis();

                // Recreate the ImageReader consumer — HAL won't deliver continuous
                // frames to a surface previously connected to a different camera/mode.
                recreateCameraSurface();
                lastGlThreadHeartbeat = System.currentTimeMillis();

                try {
                    // Brief pause before opening next camera — HAL needs time to release resources
                    try { Thread.sleep(500); } catch (InterruptedException ignored) {}

                    startCamera();
                    // Setup event callback (only for AVMCamera path — binder service handles its own events)
                    if (cameraCoordinator != null && cameraObj != null) {
                        cameraCoordinator.setupEventCallback(cameraObj);
                    }
                    found = true;
                    break;
                } catch (Exception e) {
                    // Camera ID doesn't exist or can't open — skip to next
                    logger.info("Auto-probe: camera ID " + tryId + " failed to open: " + e.getMessage());
                    cameraObj = null;
                    // Delay before trying next combo to avoid HAL overload
                    try { Thread.sleep(500); } catch (InterruptedException ignored) {}
                    continue;
                }
            }

            if (!found) {
                probeSurfaceModeMatrixActive = true;
                probeMatrixCameraIds = buildSurfaceModeProbeCameraIds();
                probeMatrixCameraIndex = 0;
                probeNextSurfaceMode = 0;
                logger.warn("Auto-probe: ID-only probing exhausted; switching to surface-mode matrix");
                found = advanceProbeSurfaceModeMatrix(skipId);
            }
        } else {
            found = advanceProbeSurfaceModeMatrix(skipId);
        }
        
        if (!found) {
            // If we found at least one camera with data during probe, switch back to it.
            // This prevents the "probe failed" state from leaving us on a black camera.
            if (lastDataCameraId >= 0 && lastDataCameraId != cameraIdOverride) {
                logger.info("Auto-probe: no verified strip found, falling back to camera ID " + 
                    lastDataCameraId + " (last known data source)");
                cameraIdOverride = lastDataCameraId;
                cameraSurfaceMode = 0;
                frameCounter = 0;
                lastStatsFrameCount = 0;
                lastGlThreadHeartbeat = System.currentTimeMillis();
                recreateCameraSurface();
                lastGlThreadHeartbeat = System.currentTimeMillis();
                try {
                    Thread.sleep(500);
                    startCamera();
                    if (cameraCoordinator != null && cameraObj != null) {
                        cameraCoordinator.setupEventCallback(cameraObj);
                    }
                } catch (Exception e) {
                    logger.error("Fallback camera open failed: " + e.getMessage());
                }
                // Persist this as a fallback so next restart doesn't re-probe
                try {
                    org.json.JSONObject camCfg = new org.json.JSONObject();
                    camCfg.put("probedCameraId", lastDataCameraId);
                    camCfg.put("probedSurfaceMode", 0);
                    camCfg.put("probedAndValidated", true);
                    camCfg.put("fallbackFromProbe", true);
                    com.overdrive.app.config.UnifiedConfigManager.updateSection("camera", camCfg);
                    logger.info("Persisted fallback camera ID " + lastDataCameraId + " for next launch");
                } catch (Exception ex) {
                    logger.warn("Failed to persist fallback camera config: " + ex.getMessage());
                }
            } else {
                logger.error("Auto-probe: exhausted all " + 
                    (MAX_CAMERA_ID + 1) + 
                    " camera IDs — no working panoramic camera found");
            }
            autoProbeCameras = false;
            probeStartId = -1;
            lastDataCameraId = -1;
            probeSurfaceModeMatrixActive = false;
            probeMatrixCameraIds = new int[0];
            probeMatrixCameraIndex = 0;
            // Ungate consumers even on failure — better to record whatever we have
            // than to stay permanently blocked
            probeComplete = true;
            logger.warn("Probe complete (fallback mode) — unblocking consumers");
        }
    }

    /**
     * Starts the watchdog thread that monitors GL thread health.
     * 
     * If the GL thread hangs (e.g., eglSwapBuffers blocks), the watchdog
     * will call System.exit(0) to force a process restart, since EGL
     * contexts cannot be recovered from a blocked thread.
     */
    private void startWatchdog() {
        lastGlThreadHeartbeat = System.currentTimeMillis();
        firstFrameReceived = false;
        
        watchdogThread = new Thread(() -> {
            while (running) {
                try {
                    Thread.sleep(1000);  // Check every second
                    
                    long now = System.currentTimeMillis();
                    long timeSinceHeartbeat = now - lastGlThreadHeartbeat;
                    
                    // Use extended timeout until the first camera frame arrives.
                    // The BYD panoramic camera HAL can take 5-8 seconds to deliver
                    // the first frame after open. During this period the GL thread
                    // is blocked on frameSync.wait(100) which still updates the
                    // heartbeat, but if the HAL is slow to even accept the surface
                    // (e.g., I/O contention from MediaScanner broadcasts), the
                    // heartbeat can stall. Killing the process here just causes a
                    // restart loop that makes things worse.
                    // Also use extended timeout during camera restart — the GL thread
                    // is busy with close/reopen operations and heartbeat updates are
                    // interleaved but may not be frequent enough for the normal timeout.
                    long effectiveTimeout = (firstFrameReceived && !restartInProgress.get())
                            ? GL_THREAD_TIMEOUT_MS
                            : GL_THREAD_WARMUP_TIMEOUT_MS;
                    
                    if (timeSinceHeartbeat > effectiveTimeout) {
                        logger.error( "CRITICAL: GL thread blocked for " + timeSinceHeartbeat + 
                                "ms - forcing process restart" +
                                (firstFrameReceived ? "" : " (during camera warmup)"));
                        
                        // Try to flush logs before exit
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException ignored) {}
                        
                        // Exit code 0 triggers restart loop in DaemonLauncher wrapper.
                        // EGL contexts cannot be recovered from a blocked thread.
                        System.exit(0);
                    }
                    
                    // SOTA: Frame health monitor — detect stalled camera feed
                    // If GL thread is alive but no new frames for FRAME_STALL_THRESHOLD_MS,
                    // the camera HAL may be starved or dead.
                    // Decision is contention-aware: if native app is active, use longer
                    // threshold and require consecutive stalls before yielding.
                    if (!cameraYielded && lastFrameTime > 0 && 
                        timeSinceHeartbeat < GL_THREAD_TIMEOUT_MS) {
                        long timeSinceFrame = now - lastFrameTime;
                        
                        // Use longer threshold when native app is active — transient
                        // CPU/IO stalls shouldn't trigger a yield that interrupts recording
                        boolean nativeActive = cameraCoordinator != null && 
                            cameraCoordinator.isNativeAppActive();
                        long stallThreshold = nativeActive 
                            ? FRAME_STALL_CONTENTION_THRESHOLD_MS 
                            : FRAME_STALL_THRESHOLD_MS;
                        
                        if (timeSinceFrame > stallThreshold) {
                            logger.warn("FRAME STALL: No frames for " + timeSinceFrame + "ms" +
                                (nativeActive ? " (native app active)" : ""));
                            // Reset lastFrameTime to prevent repeated triggers
                            lastFrameTime = now;
                            
                            if (cameraCoordinator != null) {
                                if (nativeActive) {
                                    // Contention path: require consecutive stalls before yielding
                                    consecutiveContentionStalls++;
                                    if (consecutiveContentionStalls >= CONTENTION_STALL_COUNT_TO_YIELD) {
                                        logger.warn("Consecutive contention stalls: " + 
                                            consecutiveContentionStalls + " — yielding camera");
                                        consecutiveContentionStalls = 0;
                                        cameraCoordinator.onFrameStallDetected();
                                    } else {
                                        logger.info("Contention stall " + consecutiveContentionStalls + 
                                            "/" + CONTENTION_STALL_COUNT_TO_YIELD + 
                                            " — waiting for more evidence before yielding");
                                    }
                                } else {
                                    // No native app — this is a HAL issue, restart camera
                                    consecutiveContentionStalls = 0;
                                    logger.info("Frame stall is HAL issue — restarting camera");
                                    if (glHandler != null) {
                                        glHandler.post(() -> restartCameraAfterError());
                                    }
                                }
                            } else {
                                // No coordinator — just restart
                                if (glHandler != null) {
                                    glHandler.post(() -> restartCameraAfterError());
                                }
                            }
                        } else if (nativeActive && timeSinceFrame < 500) {
                            // Frames are flowing despite native app — reset stall counter
                            consecutiveContentionStalls = 0;
                        }
                    }
                    
                } catch (InterruptedException e) {
                    break;
                }
            }
        }, "GL-Watchdog");
        
        watchdogThread.setDaemon(true);
        watchdogThread.start();
        
        logger.info( "GL thread watchdog started (timeout=" + GL_THREAD_TIMEOUT_MS + "ms, " +
            "warmupTimeout=" + GL_THREAD_WARMUP_TIMEOUT_MS + "ms, " +
            "frameStall=" + FRAME_STALL_THRESHOLD_MS + "ms, " +
            "cameraId=" + (cameraIdOverride >= 0 ? cameraIdOverride : PHYSICAL_CAMERA_ID) + ", " +
            "probe=" + (autoProbeCameras ? "ACTIVE" : "OFF") + ")");
    }
    
    /**
     * SOTA: Yields the camera to the native BYD AVM app.
     * 
     * Called on GL thread when contention is detected (frame stall while native
     * app is active). Finalizes any active recording FIRST to prevent MP4 corruption,
     * then does a clean camera close.
     * 
     * The GL render loop continues running but skips frame processing while yielded.
     * Camera is re-acquired when onCloseCamera fires from IBYDCameraService.
     */
    private void yieldCameraInternal() {
        logger.info("Yielding camera to native AVM app...");
        
        // CRITICAL: Finalize active recording BEFORE closing camera.
        if (yieldListener != null) {
            try {
                yieldListener.onPreYield();
                logger.info("Pre-yield: recording finalized");
            } catch (Exception e) {
                logger.warn("Pre-yield callback error: " + e.getMessage());
            }
        }
        
        // Detach streaming components to stop drainer threads
        if (streamScaler != null || streamEncoder != null) {
            clearStreamingComponents();
        }
        
        // FORTIFY FIX: Stop encoder drainer threads BEFORE closing camera.
        // The drainer thread calls MediaCodec.dequeueOutputBuffer() which internally
        // accesses the camera's ImageReader / HardwareBuffer-backed EGL chain. If we destroy
        // the camera (and its native mutex) while the drainer is mid-dequeue,
        // we get: FORTIFY: pthread_mutex_lock called on a destroyed mutex
        if (encoder != null) {
            encoder.stopDrainerForCameraClose();
        }
        if (streamEncoder != null) {
            streamEncoder.stopDrainerForCameraClose();
        }
        
        if (cameraObj != null) {
            BydCameraCoordinator.closeCamera(cameraObj, cameraSurfaceMode);
            cameraObj = null;
            if (cameraCoordinator != null) {
                cameraCoordinator.resetEventCallbackState();
                cameraCoordinator.notifyPosCloseCamera();
            }
            logger.info("Camera yielded — GL pipeline idle, waiting for onCloseCamera");
        }
        
        // Restart drainer threads after camera is closed (for pre-record buffer)
        if (encoder != null) {
            encoder.restartDrainerAfterCameraClose();
        }
    }
    
    /**
     * SOTA: Restarts the camera after a HAL error event or frame stall.
     * 
     * Called on GL thread. Does a full close→reopen cycle with proper cleanup.
     * This is faster than the watchdog kill+restart because it doesn't require
     * a full process restart — just a camera reopen.
     */
    private void restartCameraAfterError() {
        // P1 #11: CAS — only one restart can be in flight. If reopenCamera
        // (daemon thread) is already restarting, return without touching the
        // flag so its finally{set(false)} doesn't get clobbered.
        if (!restartInProgress.compareAndSet(false, true)) {
            logger.info("Restart already in progress — skipping restartCameraAfterError");
            return;
        }
        logger.info("Restarting camera after error/stall...");

        try {
            // CRITICAL: Finalize active recording BEFORE closing camera.
            if (yieldListener != null) {
                try {
                    yieldListener.onPreYield();
                    logger.info("Pre-restart: recording finalized");
                } catch (Exception e) {
                    logger.warn("Pre-restart callback error: " + e.getMessage());
                }
            }
            
            // Detach streaming components
            if (streamScaler != null || streamEncoder != null) {
                clearStreamingComponents();
                logger.info("Pre-restart: streaming components detached");
            }
            
            // FORTIFY FIX: Stop encoder drainer threads BEFORE closing camera.
            if (encoder != null) {
                encoder.stopDrainerForCameraClose();
            }
            if (streamEncoder != null) {
                streamEncoder.stopDrainerForCameraClose();
            }
            
            // Close with proper cleanup + notify service
            if (cameraObj != null) {
                BydCameraCoordinator.closeCamera(cameraObj, cameraSurfaceMode);
                cameraObj = null;
                if (cameraCoordinator != null) {
                    cameraCoordinator.resetEventCallbackState();
                    cameraCoordinator.notifyPosCloseCamera();
                }
            }

            // Brief pause to let HAL settle
            Thread.sleep(500);
            
            // Update heartbeat so watchdog doesn't kill us during restart
            lastGlThreadHeartbeat = System.currentTimeMillis();
            
            // CRITICAL: Recreate the ImageReader consumer before reopening camera.
            // The BYD HAL won't deliver continuous frames to a surface that was
            // previously connected to a different camera instance — only the first
            // frame arrives, then the stream freezes. This matches the fix already
            // present in the auto-probe path in renderLoop().
            recreateCameraSurface();
            
            // Update heartbeat again after surface recreation
            lastGlThreadHeartbeat = System.currentTimeMillis();
            
            // CRITICAL FIX: Open camera on a separate thread with a timeout.
            // startCamera() calls into the BYD HAL which can block indefinitely
            // if the HAL is in a bad state. Running it on the GL thread causes
            // the watchdog to kill the process (GL heartbeat stops updating).
            // By opening on a worker thread, the GL thread stays alive and the
            // watchdog heartbeat keeps ticking. If the open times out, we let
            // the watchdog handle it on the next stall cycle instead of crash-looping.
            final boolean[] openSuccess = {false};
            final Exception[] openError = {null};
            Thread cameraOpenThread = new Thread(() -> {
                try {
                    startCamera();
                    openSuccess[0] = true;
                } catch (Exception e) {
                    openError[0] = e;
                }
            }, "CameraReopen");
            cameraOpenThread.start();
            
            // Wait up to 2 seconds for camera to open, updating heartbeat periodically
            long openStart = System.currentTimeMillis();
            long openTimeout = 2000;
            while (cameraOpenThread.isAlive() && 
                   (System.currentTimeMillis() - openStart) < openTimeout) {
                Thread.sleep(200);
                lastGlThreadHeartbeat = System.currentTimeMillis();
            }
            
            if (!openSuccess[0]) {
                if (cameraOpenThread.isAlive()) {
                    logger.warn("Camera open timed out after " + openTimeout + 
                        "ms — will retry on next stall cycle");
                    // Don't interrupt — let it finish in background, watchdog won't kill us
                    // because heartbeat is still updating
                    return;
                }
                if (openError[0] != null) {
                    throw openError[0];
                }
            }
            
            // Update heartbeat after successful open
            lastGlThreadHeartbeat = System.currentTimeMillis();
            
            // Restart encoder drainer now that camera is open again
            if (encoder != null) {
                encoder.restartDrainerAfterCameraClose();
            }
            
            // Re-register event callback
            if (cameraCoordinator != null && cameraObj != null) {
                cameraCoordinator.setupEventCallback(cameraObj);
            }
            
            // Resume recording/surveillance after camera restart
            if (yieldListener != null) {
                try {
                    yieldListener.onPostReacquire();
                    logger.info("Post-restart: recording/surveillance resumed");
                } catch (Exception e) {
                    logger.warn("Post-restart callback error: " + e.getMessage());
                }
            }
            
            logger.info("Camera restarted successfully after error");
            
        } catch (Exception e) {
            logger.error("Camera restart failed: " + e.getMessage());
            // If restart fails, the watchdog will eventually kill the process
            // but at least we won't crash-loop immediately
        } finally {
            restartInProgress.set(false);
        }
    }

    /**
     * Stops the GPU camera pipeline.
     */
    public void stop() {
        logger.info( "Stopping GPU camera pipeline...");
        running = false;
        
        // Stop watchdog
        if (watchdogThread != null) {
            watchdogThread.interrupt();
            watchdogThread = null;
        }
        
        // FORTIFY FIX: Stop encoder drainer threads BEFORE closing camera
        if (encoder != null) {
            encoder.stopDrainerForCameraClose();
        }
        if (streamEncoder != null) {
            streamEncoder.stopDrainerForCameraClose();
        }
        
        // Close camera with proper cleanup + notify service
        if (cameraObj != null) {
            BydCameraCoordinator.closeCamera(cameraObj, cameraSurfaceMode);
            cameraObj = null;
            if (cameraCoordinator != null) {
                cameraCoordinator.notifyPosCloseCamera();
            }
        }
        
        // Unregister from IBYDCameraService AFTER notifying posCloseCamera.
        // Must keep the service proxy alive until the close notification is sent,
        // otherwise the native camera app never receives the "camera released" signal
        // and hangs waiting for it.
        if (cameraCoordinator != null) {
            cameraCoordinator.unregister();
        }
        
        // Cleanup on GL thread
        if (glHandler != null) {
            glHandler.post(this::releaseGl);
        }
        
        // Stop GL thread
        if (glThread != null) {
            glThread.quitSafely();
            try {
                glThread.join(1000);
            } catch (InterruptedException e) {
                logger.warn( "GL thread join interrupted");
            }
            glThread = null;
        }
        
        logger.info( "GPU camera pipeline stopped");
    }
    /**
     * Releases and reopens the AVMCamera without tearing down the GL pipeline.
     *
     * This is needed during ACC OFF→ON transitions. The daemon holds the camera
     * open continuously (surveillance → recording mode), which prevents the BYD
     * native camera app from getting video frames. By briefly releasing the camera,
     * the native app can grab it, and when we reopen we get added as a secondary
     * consumer via addPreviewSurface.
     */
    /**
     * Releases and reopens the AVMCamera without tearing down the GL pipeline.
     * 
     * During ACC OFF→ON, the daemon holds the camera from surveillance mode.
     * The BYD native camera app starts on ACC ON but can't get frames.
     * Releasing briefly lets the native app grab the primary slot, then we
     * get added as secondary consumer via addPreviewSurface.
     */
    public void reopenCamera() {
        reopenCamera(15000);
    }

    public void reopenCamera(long maxWaitMs) {
        if (!running) {
            logger.warn("Cannot reopen camera - not running");
            return;
        }

        // P1 #11: CAS — only one restart can be in flight. If
        // restartCameraAfterError (GL thread) already owns the flag, return
        // without clobbering its finally{set(false)}.
        if (!restartInProgress.compareAndSet(false, true)) {
            logger.warn("Restart already in progress — skipping reopenCamera");
            return;
        }

        logger.info("Reopening AVMCamera...");

        // CRITICAL: Mark restart-in-progress BEFORE touching the camera so the
        // GL watchdog uses GL_THREAD_WARMUP_TIMEOUT_MS (10s) instead of the
        // normal 3s. Without this, the daemon thread's polling sleep + the
        // GL thread briefly blocking on updateTexImage() against a dying HAL
        // is enough to trip the watchdog and force a full process restart on
        // every ACC OFF→ON transition. See log: "GL thread blocked for 3492ms".

        try {
            // Proper cleanup order via BydCameraCoordinator.
            // Null cameraObj BEFORE closeCamera() so the GL renderLoop's
            // `cameraObj == null` short-circuit (line ~616) kicks in immediately
            // and stops calling updateTexImage() on a HAL that's being torn down.
            if (cameraObj != null) {
                Object toClose = cameraObj;
                cameraObj = null;
                BydCameraCoordinator.closeCamera(toClose, cameraSurfaceMode);
                if (cameraCoordinator != null) {
                    cameraCoordinator.resetEventCallbackState();
                }
                logger.info("Camera closed (proper cleanup)");
            }

            // Kick the GL heartbeat so the watchdog timer resets at the start of
            // the wait — close+log above can already have spent >1s.
            lastGlThreadHeartbeat = System.currentTimeMillis();

            // registerCameraUser is DISABLED — the event-driven branch below is
            // dead. Kept for reference; do NOT re-enable without re-validating
            // the IBYDCameraUser yield/reacquire path end-to-end.
            // if (cameraCoordinator != null && cameraCoordinator.isRegisteredAsUser()) {
            //     logger.info("Registered as camera user — waiting for onCloseCamera callback");
            //     Thread.sleep(3000);
            //     if (!cameraCoordinator.isCameraYielded()) {
            //         startCamera();
            //         if (cameraCoordinator != null && cameraObj != null) {
            //             cameraCoordinator.setupEventCallback(cameraObj);
            //         }
            //     }
            //     return;
            // }

            // Polling path — the only live path. Wait long enough for the BYD
            // native AVM app to claim the primary camera slot, then reopen as
            // secondary consumer. Sleeps in 500ms chunks so we can refresh the
            // GL watchdog heartbeat — otherwise a long single sleep on this
            // (daemon) thread can race the GL thread mid-updateTexImage and
            // make timeSinceHeartbeat exceed the threshold.
            logger.info("Polling fallback (maxWait=" + maxWaitMs + "ms)");
            final long minWaitMs = 3000;
            sleepWithHeartbeat(minWaitMs);

            if (cameraCoordinator != null && cameraCoordinator.isRegistered()) {
                long deadline = System.currentTimeMillis() + (maxWaitMs - minWaitMs);
                boolean nativeAppDetected = false;

                while (System.currentTimeMillis() < deadline) {
                    if (cameraCoordinator.checkNativeAppActive()) {
                        nativeAppDetected = true;
                        logger.info("Native app claimed camera (polling) — waiting for release");
                        sleepWithHeartbeat(500);
                        break;
                    }
                    sleepWithHeartbeat(500);
                }

                if (!nativeAppDetected) {
                    logger.info("Native app not detected after polling — reopening");
                }
            } else {
                long remainingWait = maxWaitMs - minWaitMs;
                logger.info("No service available — fixed delay (" + remainingWait + "ms)");
                sleepWithHeartbeat(remainingWait);
            }

            startCamera();

            if (cameraCoordinator != null && cameraObj != null) {
                cameraCoordinator.setupEventCallback(cameraObj);
            }

            // Reset heartbeat after a successful reopen so the next watchdog
            // tick measures from a known-good baseline.
            lastGlThreadHeartbeat = System.currentTimeMillis();
            logger.info("Camera reopened successfully");

        } catch (Exception e) {
            logger.error("Failed to reopen camera: " + e.getMessage(), e);
            try {
                if (cameraObj == null) {
                    logger.warn("Retry camera open...");
                    startCamera();
                    if (cameraCoordinator != null && cameraObj != null) {
                        cameraCoordinator.setupEventCallback(cameraObj);
                    }
                    lastGlThreadHeartbeat = System.currentTimeMillis();
                }
            } catch (Exception e2) {
                logger.error("Camera retry failed: " + e2.getMessage());
            }
        } finally {
            restartInProgress.set(false);
        }
    }

    /**
     * Sleeps for {@code totalMs} milliseconds in 250ms chunks, refreshing
     * the GL watchdog heartbeat each chunk. Used while the daemon thread is
     * waiting for the BYD HAL to settle so the watchdog doesn't kill the
     * process during the wait.
     */
    private void sleepWithHeartbeat(long totalMs) throws InterruptedException {
        final long step = 250;
        long remaining = totalMs;
        while (remaining > 0 && running) {
            long chunk = Math.min(step, remaining);
            Thread.sleep(chunk);
            lastGlThreadHeartbeat = System.currentTimeMillis();
            remaining -= chunk;
        }
    }
    
    /**
     * Releases OpenGL resources.
     */
    private void releaseGl() {
        // Shut down the AI worker FIRST so any in-flight processFrame
        // completes before we tear down the consumers it might still
        // reference. The worker's drain timeout caps this at ~2s.
        if (aiLaneWorker != null) {
            try { aiLaneWorker.shutdown(); } catch (Throwable ignored) {}
            aiLaneWorker = null;
        }

        // Release foveated cropper before GL context is destroyed
        if (foveatedCropper != null) {
            foveatedCropper.release();
            foveatedCropper = null;
        }

        // Releases whichever consumer is active.
        releaseCameraConsumer();

        // Tear down the ImageReader callback thread (full shutdown only —
        // recreateCameraSurface keeps it alive across camera re-attach).
        if (imageReaderThread != null) {
            try { imageReaderThread.quitSafely(); } catch (Throwable ignored) {}
            imageReaderThread = null;
            imageReaderHandler = null;
        }

        if (cameraTextureId != 0) {
            GlUtil.deleteTexture(cameraTextureId);
            cameraTextureId = 0;
        }
        
        if (dummySurface != null) {
            eglCore.destroySurface(dummySurface);
            dummySurface = null;
        }
        
        if (eglCore != null) {
            eglCore.release();
            eglCore = null;
        }
        
        logger.info( "OpenGL resources released");
    }
    
    /**
     * Sets streaming components for parallel GPU path.
     * 
     * @param streamScaler GPU stream scaler
     * @param streamEncoder Stream encoder
     */
    public void setStreamingComponents(com.overdrive.app.streaming.GpuStreamScaler streamScaler,
                                      HardwareEventRecorderGpu streamEncoder) {
        this.streamScaler = streamScaler;
        this.streamEncoder = streamEncoder;
    }
    
    /**
     * Clears streaming components (called when streaming is disabled).
     * This prevents the render loop from trying to use released surfaces.
     */
    public void clearStreamingComponents() {
        this.streamScaler = null;
        this.streamEncoder = null;
    }
    
    /**
     * Gets the GL thread handler for posting operations.
     * 
     * @return Handler for GL thread
     */
    public Handler getGlHandler() {
        return glHandler;
    }
    
    /**
     * Checks if the camera is running.
     * 
     * @return true if running, false otherwise
     */
    public boolean isRunning() {
        return running;
    }
    
    /**
     * Sets the AVMCamera surface mode for addPreviewSurface().
     * Must be called before start(). Default is 0 (works on Seal).
     * Atto 1 may need mode 1 for processed panoramic output.
     */
    public void setCameraSurfaceMode(int mode) {
        this.cameraSurfaceMode = mode;
        logger.info("Camera surface mode set to: " + mode);
    }
    
    /**
     * Gets the current camera surface mode.
     */
    public int getCameraSurfaceMode() {
        return cameraSurfaceMode;
    }
    
    /**
     * Gets the active camera ID (the one currently open or selected by probe).
     */
    public int getCameraId() {
        return cameraIdOverride >= 0 ? cameraIdOverride : PHYSICAL_CAMERA_ID;
    }
    
    /**
     * Sets the AVMCamera ID to use.
     * Must be called before start(). Default is 1 (works on Seal).
     * Dolphin/Atto 1 may need ID 0.
     */
    public void setCameraId(int id) {
        this.cameraIdOverride = id;
        logger.info("Camera ID override set to: " + id);
    }
    
    /**
     * Sets the target frame rate for the binder camera backend.
     * Only effective when binder backend is enabled.
     * Updates the target frame rate. If the camera is already open, also
     * pushes the new rate to the HAL via AvmCameraHelper.setCameraFps so
     * emission rate matches the encoder's KEY_FRAME_RATE without a full
     * camera reopen.
     *
     * @param fps Desired frames per second (range enforced by callers; this
     *            method just stores and applies)
     */
    public void setTargetFps(int fps) {
        this.targetFps = fps;
        logger.info("Target FPS set to: " + fps);
        // Keep the AI-lane GL-hop budget in sync with the new rate.
        if (sentry != null) {
            sentry.setCameraTargetFps(fps);
        }
        // If the camera is currently open, push the new rate to the HAL.
        // Returns false on devices where setCameraFps is rejected (e.g., the
        // BYD HAL when isValidCamera gate fails) — we log and continue; the
        // encoder reconfig will still produce the right KEY_FRAME_RATE.
        Object cam = cameraObj;
        if (cam != null) {
            try {
                boolean ok = AvmCameraHelper.setCameraFps(cam, fps);
                fpsSetCameraResult = ok
                    ? "live:setCameraFps(" + fps + ")=ok"
                    : "live:setCameraFps(" + fps + ")=failed";
            } catch (Throwable t) {
                fpsSetCameraResult = "live:setCameraFps(" + fps + ")=error:" + t.getClass().getSimpleName();
                logger.warn("Live setCameraFps failed: " + t.getMessage());
            }
        }
    }
    
    /**
     * Gets the target FPS setting.
     */
    public int getTargetFps() {
        return targetFps;
    }

    /**
     * Gets the most recently measured camera FPS (over the last 2-minute
     * stats window). Returns 0 if no stats window has elapsed yet. Use this
     * to surface to the UI when HAL clamps below the requested target —
     * e.g., user picks 30, HAL emits ~26 on this device.
     */
    public float getMeasuredFps() {
        return measuredFps;
    }
    /**
     * Enables auto-probe mode: tries camera IDs 0-5 at startup to find
     * the one that produces actual image data. Logs resolution and pixel
     * content for each ID. Auto-selects the first panoramic (5120-wide) camera
     * with non-black frames.
     */
    public void setAutoProbeCameras(boolean enabled) {
        this.autoProbeCameras = enabled;
        if (enabled) {
            probeComplete = false;
            probeStartId = getCameraId();
            probeNextCameraId = 0;
            probeNextSurfaceMode = 0;
            probeSurfaceModeMatrixActive = false;
            probeMatrixCameraIds = new int[0];
            probeMatrixCameraIndex = 0;
        } else {
            probeSurfaceModeMatrixActive = false;
        }
        logger.info("Camera auto-probe: " + (enabled ? "ENABLED" : "DISABLED"));
    }
    
    /**
     * When true, skip frame-15/50 validation. Used when user manually set camera ID.
     */
    public void setSkipFrameValidation(boolean skip) {
        this.skipFrameValidation = skip;
        if (skip) logger.info("Frame validation SKIPPED (manual camera override)");
    }

    public void setManualOverrideActive(boolean manualOverride) {
        this.manualOverrideActive = manualOverride;
    }

    public void setFallbackFromProbe(boolean fallbackFromProbe) {
        this.fallbackFromProbe = fallbackFromProbe;
    }

    public void setCameraLayout(int layout) {
        this.cameraLayout = layout;
    }

    public void setCameraSelectionMetadata(PanoCameraDiscovery discovery) {
        if (discovery == null) return;
        setCameraLayout(discovery.cameraLayout);
        this.sourceBmmTag = discovery.sourceTag;
        this.discoveryMethod = discovery.method;
        this.vehicleCamSort = discovery.vehicleCamSort;
        logger.info("Camera discovery metadata set: " + discovery);
    }

    public void setCameraSelectionMetadata(int layout, String sourceTag, String method, String camSort) {
        this.cameraLayout = layout;
        this.sourceBmmTag = sourceTag == null ? "" : sourceTag;
        this.discoveryMethod = method == null ? "" : method;
        this.vehicleCamSort = camSort == null ? "" : camSort;
    }

    public void setFirmwareInfo(CameraFirmwareInfo info) {
        this.firmwareInfo = info != null ? info : CameraFirmwareInfo.current();
    }

    public void setNativeProbeReport(String report, boolean ready) {
        this.nativeProbeReport = report == null ? "" : report;
        this.nativeProbeReady = ready;
    }

    public void setArbitrationMode(String arbitrationMode) {
        this.arbitrationMode = arbitrationMode == null || arbitrationMode.isEmpty()
            ? "eventCallbackOnly" : arbitrationMode;
        if (cameraCoordinator != null) {
            cameraCoordinator.setArbitrationMode(this.arbitrationMode);
        }
    }

    public void setFpsSetMediaCodecResult(String result) {
        // Honest boundary: the pipeline does not currently own the encoder
        // MediaCodec, so the diagnostic default is "not_wired" unless a
        // future owner explicitly threads a real codec reference through.
        this.fpsSetMediaCodecResult = result == null || result.isEmpty()
            ? "not_wired" : result;
    }

    public void setLastCameraEvent(String event) {
        this.lastCameraEvent = event == null ? "" : event;
    }

    public void setQuadrantVariance(String variance) {
        this.quadrantVariance = variance == null ? "" : variance;
    }

    public int getCameraLayout() {
        return cameraLayout;
    }

    public boolean isManualOverrideActive() {
        return manualOverrideActive;
    }

    public boolean isFallbackFromProbe() {
        return fallbackFromProbe;
    }

    public String getSourceBmmTag() {
        return sourceBmmTag;
    }

    public String getDiscoveryMethod() {
        return discoveryMethod;
    }

    public String getVehicleCamSort() {
        return vehicleCamSort;
    }

    public CameraFirmwareInfo getFirmwareInfo() {
        return firmwareInfo;
    }

    public String getNativeProbeReport() {
        return nativeProbeReport;
    }

    public boolean isNativeProbeReady() {
        return nativeProbeReady;
    }

    public String getFpsSetCameraResult() {
        return fpsSetCameraResult;
    }

    public String getFpsSetMediaCodecResult() {
        return fpsSetMediaCodecResult;
    }

    public long getValidatedAtMs() {
        return validatedAtMs;
    }

    public int getValidatedFrameWidth() {
        return validatedFrameWidth;
    }

    public int getValidatedFrameHeight() {
        return validatedFrameHeight;
    }

    public int getValidationFrameCount() {
        return validationFrameCount;
    }

    public String getValidationSignal() {
        return validationSignal;
    }

    public String getStripConfidence() {
        return stripConfidence;
    }

    public String getLayoutConfidence() {
        return layoutConfidence;
    }

    public String getQuadrantVariance() {
        return quadrantVariance;
    }

    public String getLastValidationFailure() {
        return lastValidationFailure;
    }

    public String getLastCameraEvent() {
        return lastCameraEvent;
    }

    public String getArbitrationMode() {
        return arbitrationMode;
    }

    public long getLastFrameAgeMs() {
        long last = lastFrameTime;
        if (last <= 0L) {
            return 0L;
        }
        return Math.max(0L, System.currentTimeMillis() - last);
    }

    public long getIrFireCount() {
        return irFireCount;
    }

    public long getIrAcquireOkCount() {
        return irAcquireOkCount;
    }

    public long getIrAcquireNullCount() {
        return irAcquireNullCount;
    }

    public long getIrBindFailCount() {
        return irBindFailCount;
    }

    public void recordValidationSnapshot(
            boolean validated,
            String signal,
            String stripConfidence,
            String layoutConfidence,
            String failureReason,
            int frameWidth,
            int frameHeight,
            int frameCount) {
        if (validated) {
            this.validatedAtMs = System.currentTimeMillis();
            this.validatedFrameWidth = frameWidth;
            this.validatedFrameHeight = frameHeight;
            this.validationFrameCount = frameCount;
        } else {
            this.validatedAtMs = 0L;
            this.validatedFrameWidth = 0;
            this.validatedFrameHeight = 0;
            this.validationFrameCount = 0;
        }
        this.validationSignal = signal == null ? "" : signal;
        this.stripConfidence = stripConfidence == null ? "" : stripConfidence;
        this.layoutConfidence = layoutConfidence == null ? "" : layoutConfidence;
        this.lastValidationFailure = failureReason == null ? "" : failureReason;
    }
    
    /**
     * Sets a callback to be notified when auto-probe discovers a working camera.
     * The pipeline can use this to persist the result for faster restarts.
     */
    public void setCameraProbeCallback(CameraProbeCallback callback) {
        this.probeCallback = callback;
    }
    
    /**
     * Gets the timestamp of the last frame.
     * 
     * @return Timestamp in milliseconds
     */
    public long getLastFrameTime() {
        return lastFrameTime;
    }
    
    /**
     * SOTA: Gets the BYD camera coordinator for status queries.
     */
    public BydCameraCoordinator getCameraCoordinator() {
        return cameraCoordinator;
    }
    
    /**
     * SOTA: Sets the yield listener for recording finalization during camera yield.
     * The pipeline registers this to ensure recordings are properly closed before
     * the camera is released, and resumed after re-acquisition.
     */
    public void setCameraYieldListener(CameraYieldListener listener) {
        this.yieldListener = listener;
    }
    
    /**
     * SOTA: Returns true if camera is currently yielded to native BYD app.
     */
    public boolean isCameraYielded() {
        return cameraYielded;
    }
    
    /**
     * Gets the total frame count.
     * 
     * @return Frame count
     */
    public int getFrameCount() {
        return frameCounter;
    }
    
    /**
     * Returns true when camera probe is complete and frames are valid for consumption.
     * During probe, recording/streaming/AI are gated to prevent encoding BLACK frames.
     */
    public boolean isProbeComplete() {
        return probeComplete;
    }
    
    /**
     * Gets the camera width.
     * 
     * @return Width in pixels
     */
    public int getWidth() {
        return width;
    }
    
    /**
     * Gets the camera height.
     * 
     * @return Height in pixels
     */
    public int getHeight() {
        return height;
    }
    
    /**
     * Gets the latest JPEG frame for a specific camera (for HTTP snapshot).
     * 
     * @param cameraId Camera ID (1-4)
     * @return JPEG byte array, or null if not available
     */
    public byte[] getLatestJpegFrame(int cameraId) {
        // This would need to be implemented by storing the latest extracted frame
        // For now, return null (MJPEG streaming handles this via callback)
        return null;
    }
    
    /**
     * Checks CPU usage and logs warning if exceeds threshold.
     * 
     * Provides breakdown by component to identify bottlenecks.
     */
    private void checkCpuUsage() {
        long now = System.currentTimeMillis();
        if (now - lastCpuCheckTime < CPU_CHECK_INTERVAL_MS) {
            return;
        }
        
        lastCpuCheckTime = now;
        
        try {
            // Read /proc/stat for total CPU time
            java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.FileReader("/proc/stat"));
            String line = reader.readLine();
            reader.close();
            
            // Parse CPU times
            String[] tokens = line.split("\\s+");
            long totalCpu = 0;
            for (int i = 1; i < tokens.length; i++) {
                totalCpu += Long.parseLong(tokens[i]);
            }
            
            // Read /proc/self/stat for process CPU time
            reader = new java.io.BufferedReader(
                    new java.io.FileReader("/proc/self/stat"));
            line = reader.readLine();
            reader.close();
            
            tokens = line.split("\\s+");
            long processCpu = Long.parseLong(tokens[13]) + Long.parseLong(tokens[14]);
            
            // Calculate CPU percentage (simplified)
            // Note: This is a rough estimate. For accurate measurement, use
            // Android Profiler or systrace.
            // Logging disabled to reduce log spam - uncomment for debugging
            // logger.debug( String.format("CPU check: process=%d, total=%d", processCpu, totalCpu));
            
        } catch (Exception e) {
            // Silent fail - CPU monitoring is optional
        }
    }
}
