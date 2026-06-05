package com.overdrive.app.camera;

import android.graphics.ImageFormat;
import android.hardware.HardwareBuffer;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;

import com.overdrive.app.logging.DaemonLogger;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * ImageReader FPS probe — verifies whether replacing the live pipeline's
 * SurfaceTexture consumer with an ImageReader unblocks the ~8.5 fps panoramic
 * throttle measured on DiLink50 5.0UI builds.
 *
 * Hypothesis (see CAMERA_FPS_INVESTIGATION.md): the SurfaceTexture path is
 * coupled to SurfaceFlinger's BufferQueue and gets throttled when the
 * consumer is off-screen (our 1×1 Pbuffer EGL surface). ImageReader is not
 * display-coupled, so the BYD HAL should emit at the panoramic sensor mode
 * ceiling (~14-16 fps verified via setPreviewCallback in the deleted
 * AvmMediaCodecFpsProbe).
 *
 * What this probe does:
 *   1. Open AVMCamera(1) via the constructor path (matches live pipeline).
 *   2. Create an ImageReader at 5120×960 with a HardwareBuffer-friendly
 *      format/usage and 3 frames of backpressure.
 *   3. addPreviewSurface(imageReader.getSurface(), 0) + startPreview.
 *   4. Count onImageAvailable callbacks for RECORD_MS. Acquire/close each
 *      Image immediately so the queue stays drained — we are measuring
 *      what the HAL pushes into the gralloc producer, NOT what a downstream
 *      consumer can keep up with.
 *   5. Report achievedFps and the EGL extension probe result for context.
 *
 * NO GL binding here, NO encoding — pure rate measurement. Run the probe,
 * compare achievedFps to the live pipeline's ~8.5 fps baseline:
 *   - achievedFps ≳ 14 fps → throttle is BufferQueue-side; ImageReader
 *     rewrite of the live pipeline is worth the effort.
 *   - achievedFps ≈ 8.5 fps → throttle is HAL-side; ImageReader buys
 *     nothing, abort the rewrite plan.
 *
 * Requires API 28+ for Image.getHardwareBuffer(); we don't actually CALL
 * getHardwareBuffer() in the probe (rate doesn't depend on it), but we
 * gate the whole probe on API 28+ since the rewrite would require it.
 *
 * NEVER throws — every cross-thread surface is wrapped. Safe to invoke
 * before initSurveillance() in CameraDaemon.
 */
public final class AvmImageReaderFpsProbe {

    private static final DaemonLogger logger =
        DaemonLogger.getInstance("AvmImageReaderFpsProbe");

    /** Camera id matching the live panoramic pipeline. */
    private static final int CAMERA_ID = 1;
    private static final int CAMERA_SURFACE_MODE = 0;
    private static final int IMAGE_WIDTH = 5120;
    private static final int IMAGE_HEIGHT = 960;
    /** Buffer pool size — 3 frames is what the doc / typical Camera2 examples use. */
    private static final int IMAGE_READER_MAX_IMAGES = 3;

    private static final long RECORD_MS = 30_000L;
    private static final long FIRST_FRAME_TIMEOUT_MS = 5_000L;

    private final File probeDir;

    public AvmImageReaderFpsProbe(File probeDir) {
        this.probeDir = probeDir;
    }

    public void run() {
        try {
            runUnsafe();
        } catch (Throwable t) {
            logger.error("ImageReader FPS probe top-level failure: "
                + t.getClass().getSimpleName() + ": " + t.getMessage(), t);
        }
    }

    private void runUnsafe() throws Exception {
        if (!probeDir.exists() && !probeDir.mkdirs()) {
            logger.error("Could not create probe dir: " + probeDir.getAbsolutePath());
            return;
        }
        logger.info("=== IMAGEREADER FPS PROBE START ===");
        logger.info("Output dir: " + probeDir.getAbsolutePath());
        logger.info("Android SDK_INT=" + Build.VERSION.SDK_INT);
        // minSdk=28 enforces Image.getHardwareBuffer availability.

        // Note: probeExtensionsNative() needs a current EGL display to be
        // useful. We call it without an EGL context so that it logs which
        // function pointers resolved at all — full extension list logging
        // happens when the live pipeline binds (or in a follow-up probe
        // that creates a temporary Pbuffer to query).
        try {
            String ext = HardwareBufferTextureBinder.probeExtensionsNative();
            logger.info("EGL/GL extension probe (no current display): " + ext);
        } catch (Throwable t) {
            logger.warn("EGL extension probe failed: " + t.getMessage());
        }

        Class<?> avmClass;
        try {
            avmClass = Class.forName("android.hardware.AVMCamera");
        } catch (ClassNotFoundException e) {
            logger.error("AVMCamera class unavailable — aborting probe");
            return;
        }

        // Dedicated handler thread for ImageReader callbacks — must NOT be
        // the daemon main thread, otherwise we deadlock the looper.
        HandlerThread cbThread = new HandlerThread("AvmImageReaderProbeCb");
        cbThread.start();
        Handler cbHandler = new Handler(cbThread.getLooper());

        Object cameraObj = null;
        ImageReader reader = null;
        Surface readerSurface = null;
        FrameCounter counter = new FrameCounter();
        try {
            // ── 1. Open camera (constructor path) ────────────────────────────
            Constructor<?> ctor = avmClass.getDeclaredConstructor(int.class);
            ctor.setAccessible(true);
            cameraObj = ctor.newInstance(CAMERA_ID);
            Method mOpen = avmClass.getDeclaredMethod("open");
            mOpen.setAccessible(true);
            Object openResult = mOpen.invoke(cameraObj);
            if (!(openResult instanceof Boolean) || !(Boolean) openResult) {
                logger.error("AVMCamera.open(" + CAMERA_ID
                    + ") returned " + openResult + " — aborting");
                return;
            }
            logger.info("Camera opened (id=" + CAMERA_ID + ")");

            // ── 2. Create ImageReader ────────────────────────────────────────
            // PRIVATE = opaque gralloc, optimal for zero-copy GPU sampling
            // (the eventual live-pipeline target). USAGE flags hint the
            // graphics allocator that we'll feed both the camera HAL and
            // the GPU sampler. Camera HAL on this build *might* reject
            // USAGE_GPU_SAMPLED_IMAGE alone — the rewrite section of the
            // doc calls this out as a probe-test risk. If the reader
            // construction fails, fall back to YUV_420_888 on retry.
            try {
                long usage = HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE
                           | HardwareBuffer.USAGE_CPU_READ_RARELY;
                reader = ImageReader.newInstance(
                    IMAGE_WIDTH, IMAGE_HEIGHT,
                    ImageFormat.PRIVATE,
                    IMAGE_READER_MAX_IMAGES,
                    usage);
                logger.info("ImageReader created PRIVATE "
                    + IMAGE_WIDTH + "x" + IMAGE_HEIGHT
                    + " usage=GPU_SAMPLED_IMAGE|CPU_READ_RARELY");
            } catch (Throwable t) {
                logger.warn("ImageReader PRIVATE/USAGE_GPU init failed: "
                    + t.getMessage() + " — retrying YUV_420_888");
                reader = ImageReader.newInstance(
                    IMAGE_WIDTH, IMAGE_HEIGHT,
                    ImageFormat.YUV_420_888,
                    IMAGE_READER_MAX_IMAGES);
                logger.info("ImageReader created YUV_420_888 fallback");
            }
            readerSurface = reader.getSurface();
            reader.setOnImageAvailableListener(counter, cbHandler);

            // ── 3. Attach to HAL and start ───────────────────────────────────
            Method mAddSurface = avmClass.getDeclaredMethod(
                "addPreviewSurface", Surface.class, int.class);
            mAddSurface.setAccessible(true);
            Object addRes = mAddSurface.invoke(cameraObj, readerSurface,
                CAMERA_SURFACE_MODE);
            logger.info("addPreviewSurface(reader, " + CAMERA_SURFACE_MODE
                + ") → " + addRes);

            Method mStart = avmClass.getDeclaredMethod("startPreview");
            mStart.setAccessible(true);
            Object startRes = mStart.invoke(cameraObj);
            logger.info("startPreview → " + startRes);

            // ── 4. Wait for frames and measure ───────────────────────────────
            long t0 = System.currentTimeMillis();
            long firstFrameDeadline = t0 + FIRST_FRAME_TIMEOUT_MS;
            long stopAt = -1;
            boolean firstSeen = false;
            while (true) {
                long now = System.currentTimeMillis();
                if (!firstSeen && counter.framesReceived > 0) {
                    firstSeen = true;
                    counter.firstFrameMs = now;
                    stopAt = now + RECORD_MS;
                    logger.info("FIRST frame at +" + (now - t0)
                        + "ms — measuring for " + (RECORD_MS / 1000) + "s");
                }
                if (firstSeen && now >= stopAt) break;
                if (!firstSeen && now >= firstFrameDeadline) {
                    logger.warn("No frames in " + FIRST_FRAME_TIMEOUT_MS
                        + "ms — declaring probe dead");
                    break;
                }
                try { Thread.sleep(200); }
                catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            // ── 5. Report ────────────────────────────────────────────────────
            int frames = counter.framesReceived;
            float achievedFps = 0f;
            if (firstSeen) {
                long dur = Math.max(1L, System.currentTimeMillis() - counter.firstFrameMs);
                achievedFps = frames * 1000f / dur;
            }
            logger.info(String.format(
                "── RESULT cam=%d %dx%d framesReceived=%d achievedFps=%.2f addSurface=%s start=%s",
                CAMERA_ID, IMAGE_WIDTH, IMAGE_HEIGHT,
                frames, achievedFps, addRes, startRes));
            if (achievedFps >= 12f) {
                logger.info("DECISION: ImageReader unblocks the throttle "
                    + "(achievedFps >= 12) — proceed with live-pipeline rewrite");
            } else if (achievedFps > 0) {
                logger.info("DECISION: ImageReader does NOT unblock the throttle "
                    + "(achievedFps < 12) — the clamp is HAL-side; abort rewrite plan");
            } else {
                logger.warn("DECISION: probe inconclusive — no frames received. "
                    + "Investigate addPreviewSurface compatibility with ImageReader.");
            }
        } finally {
            // Stop frame source first, then drain ImageReader, then close camera.
            if (cameraObj != null) {
                tryInvoke(avmClass, cameraObj, "stopPreview",
                    new Class<?>[0], new Object[0]);
            }
            if (reader != null) {
                try { reader.close(); } catch (Throwable ignored) {}
            }
            if (readerSurface != null) {
                try { readerSurface.release(); } catch (Throwable ignored) {}
            }
            if (cameraObj != null) {
                tryInvoke(avmClass, cameraObj, "close",
                    new Class<?>[0], new Object[0]);
            }
            cbThread.quitSafely();
        }
        logger.info("=== IMAGEREADER FPS PROBE COMPLETE ===");
    }

    private static void tryInvoke(Class<?> cls, Object instance, String name,
                                  Class<?>[] paramTypes, Object[] args) {
        try {
            Method m = cls.getDeclaredMethod(name, paramTypes);
            m.setAccessible(true);
            m.invoke(instance, args);
        } catch (Throwable ignored) {}
    }

    /** Simple frame counter — drains the ImageReader queue ASAP so we
     *  measure HAL emission rate, not consumer backpressure. */
    private static final class FrameCounter implements ImageReader.OnImageAvailableListener {
        volatile int framesReceived;
        volatile long firstFrameMs;
        volatile boolean loggedFirstFrame;
        volatile boolean loggedHwBufferProbe;

        @Override
        public void onImageAvailable(ImageReader r) {
            Image image = null;
            try {
                image = r.acquireLatestImage();
                if (image == null) return;
                framesReceived++;
                if (!loggedFirstFrame) {
                    loggedFirstFrame = true;
                    logger.info("first onImageAvailable: format=0x"
                        + Integer.toHexString(image.getFormat())
                        + " w=" + image.getWidth() + " h=" + image.getHeight()
                        + " timestamp=" + image.getTimestamp());
                }
                // Probe HardwareBuffer extraction once — confirms that
                // when we DO build the live integration, the bridge from
                // Image to AHardwareBuffer works on this build.
                if (!loggedHwBufferProbe) {
                    loggedHwBufferProbe = true;
                    HardwareBuffer hwb = null;
                    try {
                        hwb = image.getHardwareBuffer();
                        if (hwb != null) {
                            logger.info("HardwareBuffer extracted ok: format=0x"
                                + Integer.toHexString(hwb.getFormat())
                                + " usage=0x" + Long.toHexString(hwb.getUsage())
                                + " w=" + hwb.getWidth() + " h=" + hwb.getHeight());
                        } else {
                            logger.warn("Image.getHardwareBuffer() returned null");
                        }
                    } catch (Throwable t) {
                        logger.warn("Image.getHardwareBuffer() threw: "
                            + t.getMessage());
                    } finally {
                        if (hwb != null) {
                            try { hwb.close(); } catch (Throwable ignored) {}
                        }
                    }
                }
            } catch (Throwable t) {
                logger.warn("onImageAvailable error: " + t.getMessage());
            } finally {
                if (image != null) {
                    try { image.close(); } catch (Throwable ignored) {}
                }
            }
        }
    }
}
