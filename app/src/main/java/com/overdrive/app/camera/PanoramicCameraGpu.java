package com.overdrive.app.camera;

import android.graphics.SurfaceTexture;
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

/**
 * PanoramicCameraGpu - GPU Edition with Zero-Copy Pipeline.
 * 
 * This is the GPU-native version of PanoramicCamera that replaces ImageReader
 * with SurfaceTexture. Camera frames flow directly to GPU texture, enabling:
 * - Zero-copy recording (camera → GPU → encoder)
 * - Minimal AI readback (GPU downscales to 320x240)
 * - <10% total CPU usage
 * 
 * Architecture:
 * - Camera writes to GL_TEXTURE_EXTERNAL_OES via SurfaceTexture
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
    
    // SOTA: Full-matrix auto-probe — sweeps camera IDs 0-5 × surface modes 0-5
    // to find the first combination that produces panoramic image data.
    private boolean autoProbeCameras = false;
    // When true, skip frame-15/50 validation entirely (user manually set camera ID)
    private boolean skipFrameValidation = false;
    private int probeStartId = -1;  // Tracks where probe started for wrap-around detection
    private int probeNextCameraId = 0;    // Next camera ID to try
    private int probeNextSurfaceMode = 0; // Next surface mode to try
    
    // SOTA: Probe gate — blocks recording/streaming/AI until probe finds a working camera.
    // Without this, the encoder records BLACK frames and the stream shows garbage during probe.
    // Defaults to true (no gate) — only set to false when setAutoProbeCameras(true) is called.
    private volatile boolean probeComplete = true;
    
    // Track the last camera ID that delivered non-black data during probe.
    // If the probe exhausts all IDs without finding a verified strip, fall back
    // to this camera — it's better to record from a real camera than nothing.
    private int lastDataCameraId = -1;
    
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
    private SurfaceTexture cameraSurfaceTexture;
    private Surface cameraSurface;
    
    // Camera object (via reflection)
    private Object cameraObj;
    
    // Render loop
    private HandlerThread glThread;
    private Handler glHandler;
    private volatile boolean running = false;
    private final Object frameSync = new Object();
    
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
    // Adaptive AI frame skip: dynamically computed to deliver ~10 FPS to the
    // surveillance engine regardless of actual camera FPS. The V2 motion pipeline
    // is designed for 10 FPS (ring buffer N vs N-3 = 300ms, temporal decay rates,
    // loitering frame counts). Delivering too slow causes missed detections;
    // delivering too fast wastes CPU on the GPU readback path.
    //
    // Computed as: max(1, round(actualFps / targetAiFps))
    // At 30 FPS camera → skip=3 (10 FPS to sentry)
    // At 15 FPS camera → skip=2 (7.5 FPS to sentry)  
    // At 8 FPS camera  → skip=1 (8 FPS to sentry, sentry throttles to ~8)
    private int aiFrameSkip = 1;  // Start at 1, recalculated from actual FPS
    private static final float TARGET_AI_FPS = 10.0f;
    private long lastFrameTime = 0;
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
    
    // Flag to indicate camera restart is in progress — watchdog uses extended timeout
    private volatile boolean restartInProgress = false;
    
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
    private static final long STATS_INTERVAL_MS = 120000;  // Every 2 minutes
    
    // BINDER CAMERA BACKEND: Experimental — opens camera through bydcameramanager
    // service. Tested on Seal: camera opens but TX codes don't match firmware
    // (IBYDCameraService has user management only, no camera control).
    // AVMCamera is the correct path for camera control on current firmware.
    // Keep code for future firmware versions that may have different service API.
    private boolean useBinderBackend = false;
    private BinderCameraBackend binderBackend;
    private int targetFps = 15;  // Desired frame rate for binder backend
    
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
                    // Camera HAL error — trigger full restart cycle
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
        
        // Create camera texture (OES type for external camera)
        cameraTextureId = GlUtil.createExternalTexture();
        
        // Create SurfaceTexture from camera texture
        cameraSurfaceTexture = new SurfaceTexture(cameraTextureId);
        cameraSurfaceTexture.setDefaultBufferSize(width, height);
        cameraSurfaceTexture.setOnFrameAvailableListener(this::onFrameAvailable);
        
        // Create Surface for camera
        cameraSurface = new Surface(cameraSurfaceTexture);
        
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
     * Recreates the SurfaceTexture and Surface for camera switching.
     * 
     * The BYD AVMCamera HAL doesn't properly deliver frames to a Surface
     * that was previously connected to a different camera ID. After the first
     * frame, subsequent frames are never delivered, causing a frozen image.
     * Recreating the SurfaceTexture forces a clean connection to the new camera.
     */
    private void recreateCameraSurface() {
        logger.info("Recreating SurfaceTexture for camera switch...");
        
        // Release old surface and texture
        if (cameraSurface != null) {
            cameraSurface.release();
            cameraSurface = null;
        }
        if (cameraSurfaceTexture != null) {
            cameraSurfaceTexture.release();
            cameraSurfaceTexture = null;
        }
        
        // Recreate with same texture ID (OES texture is still valid)
        cameraSurfaceTexture = new SurfaceTexture(cameraTextureId);
        cameraSurfaceTexture.setDefaultBufferSize(width, height);
        cameraSurfaceTexture.setOnFrameAvailableListener(this::onFrameAvailable);
        cameraSurface = new Surface(cameraSurfaceTexture);
        
        logger.info("SurfaceTexture recreated for camera switch");
    }
    
    /**
     * Starts the BYD camera.
     * Uses binder backend (bydcameramanager service) when useBinderBackend is true,
     * otherwise falls back to direct AVMCamera reflection.
     */
    private void startCamera() throws Exception {
        // GATE: Don't open camera if yielded to native app via IBYDCameraUser callback
        if (cameraCoordinator != null && cameraCoordinator.isCameraYielded()) {
            logger.info("Camera yielded to native app — skipping open");
            cameraYielded = true;
            return;
        }

        int cameraId = cameraIdOverride >= 0 ? cameraIdOverride : PHYSICAL_CAMERA_ID;

        if (useBinderBackend) {
            startCameraViaBinderService(cameraId);
        } else {
            startCameraViaAvmReflection(cameraId);
        }

        cameraYielded = false;
        logger.info("Camera started (" + width + "x" + height + 
            ", id=" + cameraId + ", surfaceMode=" + cameraSurfaceMode + 
            ", backend=" + (useBinderBackend ? "binder" : "avmcamera") + ")");
        
        // Update coordinator with actual camera ID
        if (cameraCoordinator != null) {
            cameraCoordinator.setActiveCameraId(cameraId);
        }
    }

    /**
     * Opens camera through bydcameramanager/bmmcameraserver binder service.
     * The service manages buffer sharing with native apps, preventing glitches.
     * Also supports frame rate control via SET_FRAME_RATE transaction.
     */
    private void startCameraViaBinderService(int cameraId) throws Exception {
        if (binderBackend == null) {
            binderBackend = new BinderCameraBackend();
        }

        boolean started = binderBackend.startCamera(
            cameraId, cameraSurface, width, height, targetFps);

        if (!started) {
            logger.warn("Binder backend failed — falling back to AVMCamera reflection");
            startCameraViaAvmReflection(cameraId);
            return;
        }

        // No cameraObj in binder mode — the service owns the camera session
        cameraObj = null;
        logger.info("Camera opened via binder service (fps=" + targetFps + ")");
    }

    /**
     * Opens camera via direct AVMCamera reflection (original approach).
     * Notifies IBYDCameraService before opening so the service can arbitrate
     * with native apps (reverse camera, dashcam, AVM parking view).
     *
     * The BYD AVMCamera HAL supports multiple consumers simultaneously —
     * both our daemon and the native DVR can call open() + startPreview()
     * on the same camera. The key is timing: the AVC HAL warmup (launching
     * com.byd.avc before we open) ensures the HAL is initialized in
     * multi-consumer mode before we attach.
     */
    private void startCameraViaAvmReflection(int cameraId) throws Exception {
        // Notify camera service we're about to open
        if (cameraCoordinator != null) {
            cameraCoordinator.notifyPreOpenCamera();
        }

        Class<?> avmClass = Class.forName("android.hardware.AVMCamera");
        Constructor<?> constructor = avmClass.getDeclaredConstructor(int.class);
        constructor.setAccessible(true);
        cameraObj = constructor.newInstance(cameraId);
        
        Method mOpen = avmClass.getDeclaredMethod("open");
        mOpen.setAccessible(true);
        if (!(boolean) mOpen.invoke(cameraObj)) {
            throw new RuntimeException("Failed to open panoramic camera (id=" + cameraId + ")");
        }
        
        // Connect surface — mode 0 works on Seal, other models may need different mode
        Method mAddSurface = avmClass.getDeclaredMethod("addPreviewSurface", Surface.class, int.class);
        mAddSurface.setAccessible(true);
        mAddSurface.invoke(cameraObj, cameraSurface, cameraSurfaceMode);
        
        // Set FPS (may return false on some HAL versions — non-fatal)
        AvmCameraHelper.setCameraFps(cameraObj, targetFps);
        
        // Start preview — required for real frame data on BYD Seal HAL.
        // The HAL supports multiple consumers calling startPreview simultaneously.
        // The AVC warmup (com.byd.avc launch + 4s delay) ensures the native DVR
        // has already initialized before we reach here, preventing race conditions.
        Method mStart = avmClass.getDeclaredMethod("startPreview");
        mStart.setAccessible(true);
        mStart.invoke(cameraObj);
        logger.info("Camera opened (id=" + cameraId + ", targetFps=" + targetFps + ")");
    }
    
    /**
     * Called when a new camera frame is available.
     */
    private void onFrameAvailable(SurfaceTexture st) {
        synchronized (frameSync) {
            frameSync.notify();
        }
    }
    
    /**
     * Main render loop - distributes frames to recording and AI lanes.
     */
    private void renderLoop() {
        if (!running) {
            return;
        }

        try {
            // Wait for new frame (hardware sync)
            synchronized (frameSync) {
                try {
                    frameSync.wait(100);  // Timeout to check running flag
                } catch (InterruptedException e) {
                    // Continue
                }
            }

            if (!running) {
                return;
            }

            // Update watchdog heartbeat
            lastGlThreadHeartbeat = System.currentTimeMillis();

            // SOTA: Skip frame processing if camera is yielded to native app
            if (cameraYielded || cameraObj == null) {
                // GL thread stays alive but doesn't touch camera — waiting for re-acquire
                return;
            }

            // CRITICAL: Always consume camera texture FIRST to keep the camera HAL's
            // BufferQueue flowing. If we don't call updateTexImage() promptly, the HAL
            // buffer fills up and the BYD native camera app loses video signal.
            cameraSurfaceTexture.updateTexImage();
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
                    logger.info("Camera ID " + currentId + " probe: " + 
                        (hasData ? "HAS DATA" : "BLACK") +
                        " | resolution=" + width + "x" + height +
                        " | type=" + (isPanoramic ? "PANORAMIC" : "SINGLE") +
                        " | surfaceMode=" + cameraSurfaceMode);
                    
                    if (hasData && isPanoramic) {
                        // Track this camera as having real data (for fallback if strip check fails)
                        lastDataCameraId = currentId;
                        
                        // During auto-probe: accept the first camera with non-black panoramic data.
                        // The 5120x960 resolution IS the panoramic strip identifier on BYD — no other
                        // camera output uses this resolution with real image data. The luma-based
                        // strip check was producing false negatives in low-light/uniform scenes.
                        if (autoProbeCameras) {
                            logger.info("Auto-probe: SELECTED camera ID " + currentId + 
                                " (panoramic data confirmed, surfaceMode=" + cameraSurfaceMode + ")");
                            autoProbeCameras = false;
                            probeStartId = -1;
                            probeComplete = true;
                            lastDataCameraId = -1;
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
                        logger.warn("Frame 50 STILL BLACK for cam=" + currentId +
                            " — saved config is wrong, starting re-probe");
                        autoProbeCameras = true;
                        probeComplete = false;
                        probeNextCameraId = 0;
                        probeNextSurfaceMode = 0;
                        lastDataCameraId = -1;
                        advanceProbeToNext(currentId);
                    } else {
                        // Camera has non-black data at frame 50 — it's working.
                        // Persist as validated so next restart skips all frame checks.
                        int currentId = cameraIdOverride >= 0 ? cameraIdOverride : PHYSICAL_CAMERA_ID;
                        logger.info("Frame 50 recheck: camera ID " + currentId + " confirmed working");
                        probeComplete = true;
                        try {
                            org.json.JSONObject camCfg = new org.json.JSONObject();
                            camCfg.put("probedCameraId", currentId);
                            camCfg.put("probedSurfaceMode", cameraSurfaceMode);
                            camCfg.put("probedAndValidated", true);
                            com.overdrive.app.config.UnifiedConfigManager.updateSection("camera", camCfg);
                        } catch (Exception ignored) {}
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
                // Still probing — consume texture to keep HAL flowing but don't feed consumers.
                // Update heartbeat so watchdog doesn't kill us during probe.
                lastGlThreadHeartbeat = System.currentTimeMillis();
                if (running) {
                    glHandler.post(this::renderLoop);
                }
                return;
            }

            // PASS 1: Recording (Zero-Copy GPU Path)
            // SOTA: Always render to encoder (for pre-record circular buffer)
            GpuMosaicRecorder localRecorder = recorder;
            HardwareEventRecorderGpu localEncoder = encoder;
            if (localRecorder != null) {
                localRecorder.drawFrame(cameraTextureId);

                // CRITICAL: Drain encoder immediately after frame submission
                // This prevents eglSwapBuffers from blocking when encoder buffers fill up
                if (localEncoder != null) {
                    localEncoder.drainEncoder();
                }
                
                // RECOVERY: If encoder surface died (EGL_BAD_SURFACE after prolonged use),
                // reinitialize the encoder and reconnect the recorder
                if (localRecorder.needsReinit() && localEncoder != null) {
                    logger.warn("Encoder surface lost - reinitializing encoder...");
                    try {
                        recorder.releaseEncoderSurface();
                        encoder.release();
                        encoder.init();
                        recorder.init(eglCore, encoder);
                        recorder.clearReinitFlag();
                        logger.info("Encoder reinitialized successfully after surface loss");
                    } catch (Exception reinitEx) {
                        logger.error("Encoder reinit failed: " + reinitEx.getMessage());
                        // If reinit fails, force process restart — EGL context is likely corrupt
                        logger.error("CRITICAL: Encoder reinit failed, forcing process restart");
                        try { Thread.sleep(100); } catch (InterruptedException ignored) {}
                        System.exit(0);
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

            // PASS 2: AI Lane (Downscale & Readback at 2 FPS)
            // Run AI lane on every AI_FRAME_SKIP-th frame. The sentry's internal
            // throttle (100ms interval) and the frame skip already limit CPU usage.
            // Previous elapsedMs < 50 guard was too aggressive — at 8 FPS the recording
            // pass alone takes 50-80ms, causing the AI lane to NEVER run.
            long elapsedMs = (System.nanoTime() - loopStartNs) / 1_000_000;
            if (sentry != null && sentry.isActive() && downscaler != null) {
                if (frameCounter % aiFrameSkip == 0) {
                    try {
                        // SOTA: Use direct FBO readback on GL thread.
                        // The async readPixels path returns stale frames because the
                        // shared EGL context can't reliably read the camera's external
                        // OES texture. Direct readback on the GL thread that owns the
                        // texture guarantees fresh frame data.
                        byte[] smallFrame = downscaler.readPixelsDirect(cameraTextureId);
                        if (smallFrame != null) {
                            // Wire foveated cropper to sentry once (lazy init, GL thread safe)
                            if (foveatedCropper != null && foveatedCropper.isInitialized()
                                    && sentry.getFoveatedCropper() == null) {
                                sentry.setFoveatedCropper(foveatedCropper, cameraTextureId);
                            }
                            // Buffer is recycled inside processFrame's finally block
                            sentry.processFrame(smallFrame);
                        }
                    } catch (Exception e) {
                        // Log but don't crash - AI lane is non-critical
                        logger.warn("AI lane error: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
                    }
                }
            } else if (sentry != null && frameCounter % 300 == 0) {
                // Periodic diagnostic: log why AI lane is not running
                logger.info(String.format("AI lane gate: active=%b, downscaler=%b, elapsed=%dms",
                        sentry.isActive(), downscaler != null, elapsedMs));
            }

            // Log stats periodically (every 2 minutes, time-based)
            long now = System.currentTimeMillis();
            if (now - lastStatsTime >= STATS_INTERVAL_MS) {
                lastStatsTime = now;
                long elapsed = now - startTime;
                float fps = (frameCounter * 1000.0f) / elapsed;
                
                // SOTA: Adaptive AI frame skip — recalculate based on measured FPS.
                // Target ~10 FPS delivery to the V2 motion pipeline.
                int newSkip = Math.max(1, Math.round(fps / TARGET_AI_FPS));
                if (newSkip != aiFrameSkip) {
                    logger.info(String.format("Adaptive AI skip: %d → %d (camera=%.1f FPS, target=%.0f FPS, effective=%.1f FPS)",
                            aiFrameSkip, newSkip, fps, TARGET_AI_FPS, fps / newSkip));
                    aiFrameSkip = newSkip;
                }
                
                logger.info(String.format("Stats: %d frames, %.1f FPS (target=%d), uptime=%ds, aiSkip=%d",
                        frameCounter, fps, targetFps, elapsed / 1000, aiFrameSkip));
            }

        } catch (Exception e) {
            String msg = e.getMessage();
            if (msg == null) {
                msg = e.getClass().getSimpleName();
            }
            logger.error("Render loop error: " + msg, e);
        }

        // Schedule next frame
        if (running) {
            glHandler.post(this::renderLoop);
        }
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
        } else if (binderBackend != null && binderBackend.isCameraOpen()) {
            binderBackend.stopCamera();
            if (cameraCoordinator != null) {
                cameraCoordinator.resetEventCallbackState();
            }
        }
        
        // CRITICAL: Let the BYD camera HAL settle between close and next open.
        // Without this delay, rapid camera cycling overwhelms the HAL service
        // and triggers a system watchdog reboot.
        try { Thread.sleep(1500); } catch (InterruptedException ignored) {}
        
        // Probe camera IDs 0-5 with surface mode 0 (confirmed working on all models)
        boolean found = false;
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
            lastGlThreadHeartbeat = System.currentTimeMillis();
            
            // Recreate SurfaceTexture — HAL won't deliver continuous frames
            // to a Surface previously connected to a different camera/mode
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
            // If we found at least one camera with data during probe, switch back to it.
            // This prevents the "probe failed" state from leaving us on a black camera.
            if (lastDataCameraId >= 0 && lastDataCameraId != cameraIdOverride) {
                logger.info("Auto-probe: no verified strip found, falling back to camera ID " + 
                    lastDataCameraId + " (last known data source)");
                cameraIdOverride = lastDataCameraId;
                cameraSurfaceMode = 0;
                frameCounter = 0;
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
                    long effectiveTimeout = (firstFrameReceived && !restartInProgress)
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
        // accesses the camera's SurfaceTexture buffer queue via EGL. If we destroy
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
        } else if (binderBackend != null && binderBackend.isCameraOpen()) {
            binderBackend.stopCamera();
            if (cameraCoordinator != null) {
                cameraCoordinator.resetEventCallbackState();
                cameraCoordinator.notifyPosCloseCamera();
            }
            logger.info("Camera yielded (binder) — GL pipeline idle, waiting for onCloseCamera");
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
        logger.info("Restarting camera after error/stall...");
        restartInProgress = true;
        
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
            } else if (binderBackend != null && binderBackend.isCameraOpen()) {
                binderBackend.stopCamera();
                if (cameraCoordinator != null) {
                    cameraCoordinator.resetEventCallbackState();
                    cameraCoordinator.notifyPosCloseCamera();
                }
            }
            
            // Brief pause to let HAL settle
            Thread.sleep(500);
            
            // Update heartbeat so watchdog doesn't kill us during restart
            lastGlThreadHeartbeat = System.currentTimeMillis();
            
            // CRITICAL: Recreate SurfaceTexture before reopening camera.
            // The BYD HAL won't deliver continuous frames to a Surface that was
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
            restartInProgress = false;
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
        
        // Binder backend cleanup
        if (binderBackend != null) {
            binderBackend.stopCamera();
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

        logger.info("Reopening AVMCamera...");

        try {
            // Proper cleanup order via BydCameraCoordinator
            if (cameraObj != null) {
                BydCameraCoordinator.closeCamera(cameraObj, cameraSurfaceMode);
                cameraObj = null;
                if (cameraCoordinator != null) {
                    cameraCoordinator.resetEventCallbackState();
                }
                logger.info("Camera closed (proper cleanup)");
            }

            // If registered as camera user, the onCloseCamera callback handles reacquisition.
            // We just need to wait for the native app to claim and then release the camera.
            // The callback will fire onReacquireCamera → which calls restartCameraAfterError
            // or reopenCamera again. So we only need a simple delay here.
            if (cameraCoordinator != null && cameraCoordinator.isRegisteredAsUser()) {
                logger.info("Registered as camera user — waiting for onCloseCamera callback " +
                    "(native app will trigger reacquire)");
                // Minimum wait for native app to boot and claim camera
                Thread.sleep(3000);

                // If native app hasn't claimed it yet (no onPreOpenCamera fired),
                // just reopen — we're not contending
                if (!cameraCoordinator.isCameraYielded()) {
                    logger.info("No yield triggered — native app may not be running, reopening");
                    startCamera();
                    if (cameraCoordinator != null && cameraObj != null) {
                        cameraCoordinator.setupEventCallback(cameraObj);
                    }
                    logger.info("Camera reopened (no contention detected)");
                } else {
                    logger.info("Camera yielded via callback — waiting for onCloseCamera to reacquire");
                    // onCloseCamera → onCameraAvailable → onReacquireCamera will handle it
                }
                return;
            }

            // FALLBACK: No registerUser — use polling to detect native app
            logger.info("No registerUser — using polling fallback (maxWait=" + maxWaitMs + "ms)");
            long minWaitMs = 3000;
            Thread.sleep(minWaitMs);

            if (cameraCoordinator != null && cameraCoordinator.isRegistered()) {
                long deadline = System.currentTimeMillis() + (maxWaitMs - minWaitMs);
                boolean nativeAppDetected = false;

                while (System.currentTimeMillis() < deadline) {
                    if (cameraCoordinator.checkNativeAppActive()) {
                        nativeAppDetected = true;
                        logger.info("Native app claimed camera (polling) — waiting for release");
                        Thread.sleep(500);
                        break;
                    }
                    Thread.sleep(500);
                }

                if (!nativeAppDetected) {
                    logger.info("Native app not detected after polling — reopening");
                }
            } else {
                long remainingWait = maxWaitMs - minWaitMs;
                logger.info("No service available — fixed delay (" + remainingWait + "ms)");
                Thread.sleep(remainingWait);
            }

            startCamera();

            if (cameraCoordinator != null && cameraObj != null) {
                cameraCoordinator.setupEventCallback(cameraObj);
            }

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
                }
            } catch (Exception e2) {
                logger.error("Camera retry failed: " + e2.getMessage());
            }
        }
    }
    
    /**
     * Releases OpenGL resources.
     */
    private void releaseGl() {
        // Release foveated cropper before GL context is destroyed
        if (foveatedCropper != null) {
            foveatedCropper.release();
            foveatedCropper = null;
        }
        
        if (cameraSurfaceTexture != null) {
            cameraSurfaceTexture.release();
            cameraSurfaceTexture = null;
        }
        
        if (cameraSurface != null) {
            cameraSurface.release();
            cameraSurface = null;
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
     * Enables the binder camera backend (bydcameramanager service).
     * When enabled, camera is opened through the system service instead of
     * direct AVMCamera reflection. This prevents glitching native camera apps
     * and enables frame rate control.
     * Must be called before start().
     */
    public void setUseBinderBackend(boolean enabled) {
        this.useBinderBackend = enabled;
        logger.info("Binder camera backend: " + (enabled ? "ENABLED" : "DISABLED"));
    }
    
    /**
     * Whether the binder camera backend is enabled.
     */
    public boolean isUseBinderBackend() {
        return useBinderBackend;
    }
    
    /**
     * Sets the target frame rate for the binder camera backend.
     * Only effective when binder backend is enabled.
     * Must be called before start().
     * 
     * @param fps Desired frames per second (e.g., 15, 25)
     */
    public void setTargetFps(int fps) {
        this.targetFps = fps;
        logger.info("Target FPS set to: " + fps);
    }
    
    /**
     * Gets the target FPS setting.
     */
    public int getTargetFps() {
        return targetFps;
    }
    
    /**
     * Gets the binder camera backend instance (for diagnostics).
     * Returns null if binder backend is not enabled.
     */
    public BinderCameraBackend getBinderBackend() {
        return binderBackend;
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
            probeNextCameraId = 0;
            probeNextSurfaceMode = 0;
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
