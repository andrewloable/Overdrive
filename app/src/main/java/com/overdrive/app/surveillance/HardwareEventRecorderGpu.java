package com.overdrive.app.surveillance;

import android.media.MediaCodec;
import com.overdrive.app.logging.DaemonLogger;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Bundle;
import android.view.Surface;

import com.overdrive.app.telegram.TelegramNotifier;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * HardwareEventRecorderGpu - MediaCodec encoder with Surface input for GPU pipeline.
 *
 * This encoder receives frames directly from GPU via Surface, enabling
 * zero-copy recording. Configured for 2560x1920 @ 15 FPS with adaptive bitrate.
 *
 * Key features:
 * - COLOR_FormatSurface input (GPU → Encoder)
 * - Sync frame request on event detection
 * - Adaptive bitrate (3-8 Mbps)
 * - File rotation and corruption protection
 * - Stream splitting (H.264 output → Disk + Network simultaneously)
 *
 * <h3>Lock ordering (read this before adding any new lock or call site)</h3>
 * Three locks are used by this class plus its sibling {@code GpuMosaicRecorder}.
 * Always acquire them in this order; releasing in reverse is fine but never
 * acquire a higher-numbered lock while already holding a lower-numbered one
 * in reverse:
 * <ol>
 *   <li><b>{@code GpuMosaicRecorder.recordingLock}</b> — outermost. Wraps the
 *       wrapper-level {@code recording} flag and the inner call to
 *       {@code triggerEventRecording}.</li>
 *   <li><b>{@code startStopLock}</b> — encoder-level start/stop. Wraps
 *       {@link #triggerEventRecording} and the public stop entry points
 *       (so a start cannot interleave with a stop on a different thread).
 *       The drainer/disk-writer threads do NOT take this lock.</li>
 *   <li><b>{@code muxerLock}</b> — innermost. Serializes muxer field access
 *       (writeSampleData, addTrack, start, stop, release, reassign).</li>
 * </ol>
 * Violating the order risks a deadlock if any path ever tries to acquire
 * {@code startStopLock} while already holding {@code muxerLock}, or
 * {@code recordingLock} while already holding {@code startStopLock}. Today
 * no path does, and the lock-ordering invariant exists to keep it that way.
 *
 * <p>Background threads (drainer at {@link #drainerThread}, disk writer at
 * {@link #diskWriterThread}, segment-rotator running on the drainer) only
 * touch {@code muxerLock}. They observe state changes to the volatile
 * {@code isWritingToFile} / {@code muxerStarted} flags written by the
 * start/stop paths, and never try to acquire the higher-level locks.
 */
public class HardwareEventRecorderGpu {
    private static final String TAG = "HWEncoderGpu";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);
    
    /**
     * Callback interface for streaming H.264 packets.
     * Enables zero-overhead streaming by reusing encoder output.
     */
    public interface StreamCallback {
        /**
         * Called when SPS/PPS headers are available (codec config).
         * Must be sent to clients before any video frames.
         */
        void onSpsPps(ByteBuffer sps, ByteBuffer pps);
        
        /**
         * Called for each encoded H.264 frame.
         * 
         * @param h264Data Encoded frame data
         * @param info Buffer info (size, offset, timestamp, flags)
         */
        void onH264Packet(ByteBuffer h264Data, MediaCodec.BufferInfo info);
    }
    
    // Configuration
    private final int width;
    private final int height;
    private int fps;
    private int bitrate;
    private String codecMimeType = MediaFormat.MIMETYPE_VIDEO_AVC;  // Default H.264
    
    // Encoder
    private MediaCodec encoder;
    private Surface inputSurface;
    
    // Muxer
    // SOTA: All muxer operations (writeSampleData, addTrack, start, stop, release,
    // and reassignment of the `muxer` reference) MUST be performed while holding
    // muxerLock. This makes muxer access fully serial across the drainer thread,
    // disk writer thread, rotator (drainer), and the close caller. Without this
    // lock, a concurrent writeSampleData against a stopping muxer corrupts the
    // moov atom and leaves a sized-but-unplayable .mp4 on disk — exactly the
    // failure mode that triggered this rewrite.
    private final Object muxerLock = new Object();
    private volatile MediaMuxer muxer;
    private volatile int trackIndex = -1;
    private volatile boolean muxerStarted = false;
    // Set true by the disk writer when it gives up after repeated I/O failures
    // (typically SD card unmount). The current segment's mdat is broken at that
    // point — the close/rotate paths consult this flag and refuse to rename
    // tempFile -> outputPath, so the user never sees a half-written .mp4 with the
    // final extension. Reset whenever a new disk writer instance starts.
    private volatile boolean writerAbortedCorrupt = false;
    private MediaFormat savedFormat = null;  // Save format for reuse
    
    // Circular buffer for pre-record
    // SOTA: Static buffer shared across encoder instances to avoid 23MB reallocation on reinit
    private static H264CircularBuffer sharedPreRecordBuffer;
    private static final Object bufferLock = new Object();
    private H264CircularBuffer preRecordBuffer;  // Reference to shared buffer
    // Volatile + accessed only under startStopLock for read-modify-write safety.
    // Concurrent triggerEventRecording calls (e.g., RecordingModeManager + the
    // deferred-format listener thread firing in the same window) used to both
    // pass the `if (isWritingToFile)` check and build two muxers, leaving two
    // .mp4.tmp files on disk with timestamps milliseconds apart. The lock
    // closes that window.
    private volatile boolean isWritingToFile = false;
    private final Object startStopLock = new Object();
    private long postRecordStopTime = 0;
    
    // SOTA: Async pre-record flush queue (eliminates blocking on motion trigger)
    // Packets are queued here and written by drainEncoder() on the GL thread.
    // Bounded to prevent OOM under SD-card stalls — at 30 fps a stalled SD
    // card would otherwise let this grow without bound.
    private final ConcurrentLinkedQueue<H264CircularBuffer.Packet> pendingFlushQueue = new ConcurrentLinkedQueue<>();
    private volatile boolean flushInProgress = false;
    private volatile long actualPreRecordDurationMs = 0;  // Actual duration of flushed pre-record buffer

    // SOTA: Muxer write queue — decouples encoder dequeue from SD card I/O.
    // The encoder dequeue loop copies frame data and releases the encoder buffer
    // immediately, then pushes to this queue. A dedicated disk writer thread
    // polls the queue and writes to the muxer. This prevents SD card I/O stalls
    // (which can be 50-100ms during garbage collection) from blocking the encoder,
    // which would cause the GPU to stall and drop camera frames.
    //
    // Capacity reasoning: at worst-case 30 fps × ~256KB per packet, 300 entries
    // = ~75 MB ceiling. SD stalls beyond ~10 seconds at 30 fps drop oldest
    // non-keyframes (drop-policy in offerMuxerPacket). Daemon stays alive
    // instead of OOMing.
    private static final int MUXER_WRITE_QUEUE_CAPACITY = 300;

    private static class MuxerPacket {
        final ByteBuffer data;
        final MediaCodec.BufferInfo info;
        MuxerPacket(ByteBuffer src, MediaCodec.BufferInfo srcInfo) {
            // Deep copy — the encoder buffer is released immediately after
            data = ByteBuffer.allocateDirect(srcInfo.size);
            src.position(srcInfo.offset);
            src.limit(srcInfo.offset + srcInfo.size);
            data.put(src);
            data.flip();
            info = new MediaCodec.BufferInfo();
            info.set(0, srcInfo.size, srcInfo.presentationTimeUs, srcInfo.flags);
        }

        boolean isKeyFrame() {
            return (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
        }
    }
    // Use Deque for drop-oldest semantics. Bounded capacity prevents unbounded
    // growth under SD-card backpressure.
    private final java.util.concurrent.LinkedBlockingDeque<MuxerPacket> muxerWriteQueue =
        new java.util.concurrent.LinkedBlockingDeque<>(MUXER_WRITE_QUEUE_CAPACITY);
    private final java.util.concurrent.atomic.AtomicLong muxerDropCount =
        new java.util.concurrent.atomic.AtomicLong(0);

    /**
     * Add a packet to the muxer write queue. If the queue is full, drop the
     * oldest non-keyframe packet to make room. If the queue is full and
     * everything in it is a keyframe (extreme stall), drop the new packet
     * unless it's also a keyframe (in which case drop the oldest keyframe).
     *
     * Logged every 30 drops so a chronic SD-card stall is visible in the
     * field instead of silently corrupting recordings.
     */
    private void offerMuxerPacket(MuxerPacket packet) {
        if (muxerWriteQueue.offer(packet)) {
            return;
        }
        // Queue full. Walk from the head looking for a non-keyframe to drop.
        java.util.Iterator<MuxerPacket> it = muxerWriteQueue.iterator();
        boolean dropped = false;
        while (it.hasNext()) {
            MuxerPacket head = it.next();
            if (!head.isKeyFrame()) {
                it.remove();
                dropped = true;
                break;
            }
        }
        if (!dropped) {
            // All entries are keyframes — drop the oldest. This only happens
            // under multi-second SD stalls; the recording will have a gap
            // but the daemon stays alive.
            muxerWriteQueue.pollFirst();
        }
        // Now there's space.
        muxerWriteQueue.offer(packet);
        long n = muxerDropCount.incrementAndGet();
        if (n % 30 == 1) {
            logger.warn("Muxer write queue saturated — dropped " + n
                + " packets total. SD card likely stalled.");
        }
    }
    private volatile boolean diskWriterRunning = false;
    private Thread diskWriterThread;
    
    // SOTA: Background drainer thread (moves SD card I/O off GL thread)
    private volatile boolean drainerRunning = false;
    private Thread drainerThread;
    private static final int DRAIN_INTERVAL_MS = 16;  // ~60Hz cadence, matches frame arrival rate
    
    // SOTA: Flag to disable pre-record buffer for stream-only encoders
    private boolean usePreRecordBuffer = true;

    // FIX (Bug A): Initial pre-record buffer duration. Settable BEFORE init() so the
    // first allocation honours the user's saved value instead of the hardcoded 5s.
    // setPreRecordDuration() can still resize after init.
    private int preRecordDurationSeconds = 5;
    
    // Pre-allocated BufferInfo — reused every drain cycle to avoid per-frame allocation
    private final MediaCodec.BufferInfo reusableBufferInfo = new MediaCodec.BufferInfo();
    
    // Callback for when file is closed
    private Runnable fileClosedCallback;
    
    // Streaming
    private StreamCallback streamCallback;
    private boolean streamHeadersSent = false;
    
    // Recording state
    private boolean recording = false;
    private String outputPath;
    private File tempFile;
    private int recordedFrames = 0;
    private long firstFramePtsUs = -1;   // PTS of first frame written to muxer
    private long lastFramePtsUs = -1;    // PTS of last frame written to muxer
    
    // Segment rotation
    private long segmentStartTime = 0;
    private static final long SEGMENT_DURATION_MS = 2 * 60 * 1000;  // 2 minutes
    private int segmentNumber = 0;
    private String segmentBasePath = null;  // Base path for segment rotation (without .mp4)
    
    // Timing
    private long startTimeNs = 0;
    
    /**
     * Creates a GPU-compatible hardware encoder.
     * 
     * @param width Video width (typically 2560)
     * @param height Video height (typically 1920)
     * @param fps Frame rate (typically 15)
     * @param bitrate Bitrate in bps (typically 6-8 Mbps)
     */
    public HardwareEventRecorderGpu(int width, int height, int fps, int bitrate) {
        this.width = width;
        this.height = height;
        this.fps = fps;
        this.bitrate = bitrate;
    }
    
    /**
     * Creates a GPU-compatible hardware encoder with codec selection.
     * 
     * @param width Video width (typically 2560)
     * @param height Video height (typically 1920)
     * @param fps Frame rate (typically 15)
     * @param bitrate Bitrate in bps (typically 2-6 Mbps)
     * @param codecMimeType MIME type (MIMETYPE_VIDEO_AVC for H.264, MIMETYPE_VIDEO_HEVC for H.265)
     */
    public HardwareEventRecorderGpu(int width, int height, int fps, int bitrate, String codecMimeType) {
        this.width = width;
        this.height = height;
        this.fps = fps;
        this.bitrate = bitrate;
        this.codecMimeType = codecMimeType;
    }
    
    /**
     * Returns the configured frame rate (KEY_FRAME_RATE on the encoder format).
     * Used by the pipeline to detect FPS config drift.
     */
    public int getFps() {
        return fps;
    }

    /**
     * Sets the codec MIME type before initialization.
     * Must be called before init().
     *
     * @param mimeType MIMETYPE_VIDEO_AVC (H.264) or MIMETYPE_VIDEO_HEVC (H.265)
     */
    public void setCodecMimeType(String mimeType) {
        if (encoder != null) {
            logger.warn("Cannot change codec after initialization - restart required");
            return;
        }
        this.codecMimeType = mimeType;
        logger.info("Codec set to: " + (mimeType.equals(MediaFormat.MIMETYPE_VIDEO_HEVC) ? "H.265/HEVC" : "H.264/AVC"));
    }
    
    /**
     * Gets the current codec MIME type.
     */
    public String getCodecMimeType() {
        return codecMimeType;
    }
    
    /**
     * Checks if using H.265/HEVC codec.
     */
    public boolean isHevcCodec() {
        return MediaFormat.MIMETYPE_VIDEO_HEVC.equals(codecMimeType);
    }
    
    /**
     * Initializes the encoder with Surface input.
     * 
     * @throws Exception if initialization fails
     */
    public void init() throws Exception {
        logger.info( String.format("Initializing: %dx%d @ %dfps, %d Mbps, codec=%s",
                width, height, fps, bitrate / 1_000_000,
                codecMimeType.equals(MediaFormat.MIMETYPE_VIDEO_HEVC) ? "H.265" : "H.264"));
        
        // Create format with Surface input - use configured codec
        MediaFormat format = MediaFormat.createVideoFormat(codecMimeType, width, height);
        
        // CRITICAL: Use COLOR_FormatSurface for GPU input
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);  // I-frame every 2 seconds
        
        // Set max input size to prevent Qualcomm crashes
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, width * height * 3 / 2);
        
        // Low latency hints (optional)
        try {
            format.setInteger(MediaFormat.KEY_LATENCY, 0);
            format.setInteger(MediaFormat.KEY_PRIORITY, 0);
        } catch (Exception e) {
            // Ignore if not supported
        }
        
        // H.265 specific optimizations for Snapdragon 665
        if (codecMimeType.equals(MediaFormat.MIMETYPE_VIDEO_HEVC)) {
            try {
                // Use Main profile for better compatibility
                format.setInteger(MediaFormat.KEY_PROFILE, 
                        MediaCodecInfo.CodecProfileLevel.HEVCProfileMain);
                format.setInteger(MediaFormat.KEY_LEVEL,
                        MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel4);
                logger.info("H.265 profile set to Main/Level 4");
            } catch (Exception e) {
                logger.warn("Could not set H.265 profile: " + e.getMessage());
            }
        } else {
            // H.264: Use Baseline Profile for iOS Safari compatibility
            try {
                format.setInteger(MediaFormat.KEY_PROFILE,
                        MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline);
                format.setInteger(MediaFormat.KEY_LEVEL,
                        MediaCodecInfo.CodecProfileLevel.AVCLevel31);
                logger.info("H.264 profile set to Baseline/Level 3.1 (iOS compatible)");
            } catch (Exception e) {
                logger.warn("Could not set H.264 profile: " + e.getMessage());
            }
        }
        
        // CRITICAL: All MediaCodec operations can block if hardware encoder is stuck
        // Wrap each operation with a timeout to prevent daemon freeze
        final MediaFormat finalFormat = format;
        final String finalCodecMimeType = codecMimeType;
        
        // Create encoder with timeout
        logger.info("Creating MediaCodec encoder...");
        final MediaCodec[] encoderResult = {null};
        final Exception[] createError = {null};
        Thread createThread = new Thread(() -> {
            try {
                encoderResult[0] = MediaCodec.createEncoderByType(finalCodecMimeType);
            } catch (Exception e) {
                createError[0] = e;
            }
        }, "EncoderCreate");
        createThread.start();
        try {
            createThread.join(10000);
        } catch (InterruptedException e) {
            logger.warn("Encoder create interrupted");
        }
        if (createThread.isAlive()) {
            logger.error("MediaCodec.createEncoderByType TIMEOUT - hardware encoder stuck");
            createThread.interrupt();
            throw new RuntimeException("Encoder create timeout - try restarting mediaserver");
        }
        if (createError[0] != null) {
            throw createError[0];
        }
        encoder = encoderResult[0];
        // Confirm the negotiated codec name on this device, not just our intent.
        // If the device-side codec selection silently downgraded HEVC→AVC (rare,
        // but possible if the platform encoder list rejects HEVC for our params),
        // this log line surfaces it instead of leaving the user to guess from
        // file sizes.
        try {
            String negotiatedName = encoder.getName();
            logger.info("MediaCodec encoder created (codec=" + finalCodecMimeType
                    + ", impl=" + negotiatedName + ")");
        } catch (Exception ignored) {
            logger.info("MediaCodec encoder created");
        }
        
        // Configure encoder with timeout
        logger.info("Configuring encoder...");
        final boolean[] configDone = {false};
        final Exception[] configError = {null};
        Thread configThread = new Thread(() -> {
            try {
                encoder.configure(finalFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                configDone[0] = true;
            } catch (Exception e) {
                configError[0] = e;
            }
        }, "EncoderConfig");
        configThread.start();
        try {
            configThread.join(10000);
        } catch (InterruptedException e) {
            logger.warn("Encoder config interrupted");
        }
        if (!configDone[0]) {
            if (configThread.isAlive()) {
                logger.error("encoder.configure TIMEOUT - hardware encoder stuck");
                configThread.interrupt();
                try { encoder.release(); } catch (Exception e) {}
                encoder = null;
                throw new RuntimeException("Encoder configure timeout");
            }
            if (configError[0] != null) {
                throw configError[0];
            }
        }
        logger.info("Encoder configured");
        
        // Create input surface with timeout
        logger.info("Creating input surface...");
        final Surface[] surfaceResult = {null};
        final Exception[] surfaceError = {null};
        Thread surfaceThread = new Thread(() -> {
            try {
                surfaceResult[0] = encoder.createInputSurface();
            } catch (Exception e) {
                surfaceError[0] = e;
            }
        }, "EncoderSurface");
        surfaceThread.start();
        try {
            surfaceThread.join(10000);
        } catch (InterruptedException e) {
            logger.warn("Surface create interrupted");
        }
        if (surfaceResult[0] == null) {
            if (surfaceThread.isAlive()) {
                logger.error("createInputSurface TIMEOUT - hardware encoder stuck");
                surfaceThread.interrupt();
                try { encoder.release(); } catch (Exception e) {}
                encoder = null;
                throw new RuntimeException("Surface create timeout");
            }
            if (surfaceError[0] != null) {
                throw surfaceError[0];
            }
        }
        inputSurface = surfaceResult[0];
        logger.info("Input surface created");
        
        // Start encoder with timeout
        logger.info("Starting encoder...");
        final Exception[] startError = {null};
        final boolean[] startDone = {false};
        
        Thread startThread = new Thread(() -> {
            try {
                encoder.start();
                startDone[0] = true;
            } catch (Exception e) {
                startError[0] = e;
            }
        }, "EncoderStart");
        
        startThread.start();
        try {
            startThread.join(10000); // 10 second timeout
        } catch (InterruptedException e) {
            logger.warn("Encoder start interrupted");
        }
        
        if (!startDone[0]) {
            if (startThread.isAlive()) {
                logger.error("Encoder start TIMEOUT after 10s - hardware encoder may be stuck");
                startThread.interrupt();
                // Try to release the encoder
                try {
                    encoder.release();
                } catch (Exception e) {
                    // Ignore
                }
                encoder = null;
                inputSurface = null;
                throw new RuntimeException("Encoder start timeout - hardware encoder busy or stuck");
            }
            if (startError[0] != null) {
                throw startError[0];
            }
        }
        logger.info("Encoder started");
        
        // SOTA: Reuse shared buffer across encoder instances (avoids 23MB allocation on reinit)
        // Only allocate for encoders that use pre-record (not stream-only encoders)
        if (usePreRecordBuffer) {
            synchronized (bufferLock) {
                int desiredSec = Math.max(1, preRecordDurationSeconds);
                // Pass the encoder's fps so the buffer pool is sized for
                // duration × fps + headroom. Without this, switching to
                // 30 fps recording exhausts the pool (sized for 15 fps) and
                // triggers emergency allocations under sustained load.
                if (sharedPreRecordBuffer == null) {
                    logger.info("Allocating NEW pre-record buffer (" + desiredSec
                        + " sec @ " + fps + "fps)...");
                    sharedPreRecordBuffer = new H264CircularBuffer(desiredSec, fps);
                } else {
                    long desiredUs = desiredSec * 1_000_000L;
                    if (sharedPreRecordBuffer.getMaxDurationUs() != desiredUs) {
                        logger.info("Resizing existing pre-record buffer to "
                            + desiredSec + " sec @ " + fps + "fps");
                        sharedPreRecordBuffer = new H264CircularBuffer(desiredSec, fps);
                    } else {
                        logger.info("Reusing EXISTING pre-record buffer (Zero-Allocation)");
                        sharedPreRecordBuffer.clear();  // Clear old data but keep allocated memory
                    }
                }
                preRecordBuffer = sharedPreRecordBuffer;
            }
        } else {
            logger.info("Pre-record buffer disabled (stream-only mode)");
            preRecordBuffer = null;
        }

        // SOTA: Start background drainer thread (moves SD card I/O off GL thread)
        startDrainerThread();

        logger.info("Encoder initialized successfully"
                + (usePreRecordBuffer ? " (pre-record: " + Math.max(1, preRecordDurationSeconds) + " sec)" : " (stream-only)"));
    }
    
    /**
     * Updates the pre-record buffer size.
     * 
     * SOTA: Reuses existing buffer if same duration to avoid 23MB allocation.
     * Only recreates if duration actually changed.
     * 
     * @param durationSeconds New buffer duration in seconds
     */
    public void setPreRecordDuration(int durationSeconds) {
        int clamped = Math.max(1, durationSeconds);
        // FIX (Bug A): always remember the desired duration so that a later init()
        // (e.g. after pipeline reinit) allocates the correct size.
        this.preRecordDurationSeconds = clamped;
        synchronized (bufferLock) {
            if (sharedPreRecordBuffer != null) {
                // SOTA: Check if duration actually changed before reallocating
                long currentMaxDurationUs = clamped * 1_000_000L;
                // Only recreate if duration is different (avoid 23MB allocation on every settings change)
                if (sharedPreRecordBuffer.getMaxDurationUs() != currentMaxDurationUs) {
                    logger.info("Pre-record duration changed, recreating buffer: " + clamped + " seconds");
                    sharedPreRecordBuffer = new H264CircularBuffer(clamped, fps);
                    preRecordBuffer = sharedPreRecordBuffer;
                } else {
                    logger.info("Pre-record buffer already at " + clamped + " seconds, clearing only");
                    sharedPreRecordBuffer.clear();
                }
            }
        }
    }
    
    /**
     * Sets whether this encoder uses the pre-record buffer.
     * Should be set to false for stream-only encoders.
     * 
     * @param useBuffer true to use pre-record buffer, false for stream-only mode
     */
    public void setUsePreRecordBuffer(boolean useBuffer) {
        this.usePreRecordBuffer = useBuffer;
        if (!useBuffer) {
            logger.info("Pre-record buffer disabled (stream-only mode)");
        }
    }
    
    /**
     * Sets the streaming callback for H.264 packet distribution.
     * 
     * If the encoder has already output its format (SPS/PPS), the callback
     * will receive them immediately. This handles the case where a new
     * client connects after the encoder has already started.
     * 
     * @param callback Callback to receive H.264 packets
     */
    public void setStreamCallback(StreamCallback callback) {
        this.streamCallback = callback;
        this.streamHeadersSent = false;
        
        // If format already available, send SPS/PPS immediately
        // This handles late-joining clients after encoder has started
        if (callback != null && savedFormat != null) {
            try {
                ByteBuffer sps = savedFormat.getByteBuffer("csd-0");
                ByteBuffer pps = savedFormat.getByteBuffer("csd-1");
                if (sps != null && pps != null) {
                    callback.onSpsPps(sps.duplicate(), pps.duplicate());
                    streamHeadersSent = true;
                    logger.info("SPS/PPS sent immediately to new callback (late join)");
                }
            } catch (Exception e) {
                logger.error("Failed to send SPS/PPS to new callback", e);
            }
        }
        
        logger.info("Stream callback registered");
    }
    
    /**
     * Checks if the encoder format (SPS/PPS) is available.
     *
     * @return true if format is available, false otherwise
     */
    public boolean isFormatAvailable() {
        return savedFormat != null;
    }

    /**
     * One-shot listener invoked the first time the encoder publishes its
     * output format (SPS/PPS available). Set by GpuSurveillancePipeline so
     * deferred recordings can start as soon as the format is ready, without
     * waiting for the camera-probe callback that doesn't fire when probe
     * is disabled (validated camera config path on cold start).
     */
    public interface FormatAvailableListener {
        void onFormatAvailable();
    }

    private volatile FormatAvailableListener formatAvailableListener = null;

    /**
     * Listener fired when {@link #rotateSegment()} finalises an old segment
     * before opening a new one. Lets the surveillance engine flush hero
     * thumbnails + JSON sidecar against the segment's actual filename
     * (otherwise long events split across multiple .mp4 files would attach
     * all metadata to the FIRST segment, leaving subsequent segments as
     * unbadged plain MP4s in the recordings list).
     *
     * Fired AFTER the old segment is renamed from .tmp to its final .mp4
     * name. Safe to read the file from inside {@code onSegmentClosed}.
     * Fires on the encoder drainer thread; consumers should not block.
     */
    public interface SegmentListener {
        /**
         * @param closedSegment   the .mp4 file just renamed from .tmp,
         *                        or {@code null} if the rotation produced
         *                        no playable file (broken segment quarantined).
         * @param newSegment      the new .mp4 path (still pre-finalize, pending
         *                        bytes), so the engine knows what filename the
         *                        next stop / next rotation will land on.
         */
        void onSegmentClosed(java.io.File closedSegment, java.io.File newSegment);
    }

    private volatile SegmentListener segmentListener = null;

    public void setSegmentListener(SegmentListener listener) {
        this.segmentListener = listener;
    }

    public void setFormatAvailableListener(FormatAvailableListener listener) {
        this.formatAvailableListener = listener;
        // If format is already available when the listener is registered,
        // fire immediately so callers don't miss the edge.
        if (listener != null && savedFormat != null) {
            try { listener.onFormatAvailable(); }
            catch (Exception e) { logger.warn("FormatAvailableListener error: " + e.getMessage()); }
            this.formatAvailableListener = null;
        }
    }
    
    /**
     * Waits for the encoder format to become available.
     * 
     * @param timeoutMs Maximum time to wait in milliseconds
     * @return true if format became available, false if timeout
     */
    public boolean waitForFormat(long timeoutMs) {
        long startTime = System.currentTimeMillis();
        while (savedFormat == null) {
            if (System.currentTimeMillis() - startTime > timeoutMs) {
                return false;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                return false;
            }
        }
        return true;
    }
    
    /**
     * Removes the streaming callback.
     */
    public void clearStreamCallback() {
        this.streamCallback = null;
        this.streamHeadersSent = false;
        logger.info("Stream callback cleared");
    }
    
    /**
     * Gets the input surface for GPU rendering.
     * 
     * @return Surface that GPU should render to
     */
    public Surface getInputSurface() {
        return inputSurface;
    }
    
    /**
     * Triggers event recording with pre-record buffer flush.
     * 
     * SOTA: Non-blocking implementation. Pre-record packets are queued
     * and written by drainEncoder() on the GL thread, eliminating the
     * blocking I/O that caused video stutter on motion detection.
     * 
     * @param outputPath Path for the output MP4 file
     * @param postRecordDurationMs Post-record duration in milliseconds
     * @return true if started successfully, false otherwise
     */
    public boolean triggerEventRecording(String outputPath, long postRecordDurationMs) {
        // Hold startStopLock across the entire start path so two concurrent
        // callers can't both observe isWritingToFile == false and race ahead
        // to build two muxers. The work inside is dominated by a few mkdirs
        // and a MediaMuxer ctor (sub-100ms typically), so blocking another
        // start request for that long is acceptable — the alternative is the
        // duplicate-files-on-disk bug.
        synchronized (startStopLock) {
            if (isWritingToFile) {
                // Already recording, extend post-record duration
                postRecordStopTime = System.currentTimeMillis() + postRecordDurationMs;
                logger.info("Event extended - post-record timer reset to " + postRecordDurationMs + "ms");
                return true;
            }

        try {
            this.outputPath = outputPath;
            
            // Write to temp file during recording
            tempFile = new File(outputPath + ".tmp");
            
            // Ensure parent directory exists
            File parentDir = tempFile.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                boolean created = parentDir.mkdirs();
                if (!created && !parentDir.exists()) {
                    // Retry once after short delay (SD card may need time to be accessible)
                    try { Thread.sleep(100); } catch (InterruptedException ignored) {}
                    created = parentDir.mkdirs();
                }
                if (created) {
                    logger.info("Created parent directory: " + parentDir.getAbsolutePath());
                    parentDir.setReadable(true, false);
                    parentDir.setWritable(true, false);
                    parentDir.setExecutable(true, false);
                } else if (!parentDir.exists()) {
                    logger.error("Failed to create parent directory: " + parentDir.getAbsolutePath());
                    return false;
                }
                // Directory exists (either created or already existed) - continue
            }
            
            // SOTA: clear any stale per-segment state from the previous
            // recording before the new muxer goes live. Without this, leftover
            // PTS/frame counters from a prior run would mislead the duration
            // computation in closeEventRecording.
            recordedFrames = 0;
            firstFramePtsUs = -1;
            lastFramePtsUs = -1;
            writerAbortedCorrupt = false;

            // Create muxer. Hold muxerLock so the disk writer never observes a
            // half-constructed muxer (e.g., started but trackIndex still -1).
            boolean muxerOk = false;
            synchronized (muxerLock) {
                try {
                    muxer = new MediaMuxer(tempFile.getAbsolutePath(),
                            MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

                    // If we have a saved format, use it immediately
                    if (savedFormat != null) {
                        trackIndex = muxer.addTrack(savedFormat);
                        muxer.start();
                        muxerStarted = true;
                        logger.info("Muxer started with saved format (track=" + trackIndex + ")");
                    }
                    muxerOk = true;
                } catch (Exception e) {
                    logger.error("MediaMuxer setup failed", e);
                    if (muxer != null) {
                        try { muxer.release(); } catch (Exception ignored) {}
                        muxer = null;
                    }
                    muxerStarted = false;
                    trackIndex = -1;
                }
            }
            if (!muxerOk) {
                if (tempFile != null && tempFile.exists()) tempFile.delete();
                tempFile = null;
                return false;
            }

            if (savedFormat != null) {
                // SOTA: Queue pre-record packets for async flush (NON-BLOCKING!)
                // drainEncoder() will write these on the GL thread
                List<H264CircularBuffer.Packet> preRecordPackets = preRecordBuffer.getPacketsForFlush();
                double preRecordDuration = preRecordPackets.isEmpty() ? 0 :
                    (preRecordPackets.get(preRecordPackets.size()-1).info.presentationTimeUs -
                     preRecordPackets.get(0).info.presentationTimeUs) / 1_000_000.0;

                // Store actual pre-record duration for timeline alignment
                actualPreRecordDurationMs = (long)(preRecordDuration * 1000);

                // Add all packets to the flush queue (instant, no I/O)
                pendingFlushQueue.addAll(preRecordPackets);
                flushInProgress = true;

                logger.info(String.format("Queued %d pre-record packets (%.1f sec) for async flush",
                        preRecordPackets.size(), preRecordDuration));
            }

            // Reset state
            startTimeNs = System.nanoTime();
            segmentStartTime = System.currentTimeMillis();  // Enable segment rotation for long events
            segmentNumber = 0;
            segmentBasePath = outputPath.replaceAll("\\.mp4$", "");  // Store base path for segment rotation
            postRecordStopTime = System.currentTimeMillis() + postRecordDurationMs;

            isWritingToFile = true;
            recording = true;  // Keep for compatibility

            logger.info(String.format("Event recording started: %s (codec=%s, bitrate=%d Mbps, post-record=%dms)",
                tempFile.getName(),
                codecMimeType.equals(MediaFormat.MIMETYPE_VIDEO_HEVC) ? "H.265" : "H.264",
                bitrate / 1_000_000,
                postRecordDurationMs));
            return true;

        } catch (Exception e) {
            logger.error("Failed to trigger event recording", e);
            // Best-effort cleanup so a partial init doesn't leave a muxer alive
            // referencing a now-orphaned tmp file.
            synchronized (muxerLock) {
                if (muxer != null) {
                    try { muxer.release(); } catch (Exception ignored) {}
                    muxer = null;
                }
                muxerStarted = false;
                trackIndex = -1;
            }
            if (tempFile != null && tempFile.exists()) tempFile.delete();
            tempFile = null;
            isWritingToFile = false;
            recording = false;
            return false;
        }
        } // end synchronized (startStopLock)
    }

    /**
     * Legacy method for compatibility - redirects to triggerEventRecording.
     */
    public boolean startRecording(String outputPath) {
        return triggerEventRecording(outputPath, 5000);  // Default 5 sec post-record
    }
    
    /**
     * Stops recording immediately or schedules post-record stop.
     *
     * <p>Held under {@code startStopLock} for symmetry with
     * {@link #triggerEventRecording}: a start cannot race a stop. The check
     * outside the lock is a cheap volatile read so callers don't pay the lock
     * cost when there's nothing to stop. The check inside the lock is the
     * authoritative one.
     *
     * @param immediate If true, stops immediately. If false, does nothing (timeout handled by caller)
     * @param postRecordDurationMs Post-record duration (ignored, kept for API compatibility)
     */
    public void stopEventRecording(boolean immediate, long postRecordDurationMs) {
        if (!isWritingToFile) {
            return;
        }
        synchronized (startStopLock) {
            if (!isWritingToFile) {
                // Another thread already finalised between the volatile read
                // above and our acquisition of the lock — nothing to do.
                return;
            }
            if (immediate) {
                closeEventRecording();
            }
            // Note: Post-record timeout is now handled by SurveillanceEngineGpu
            // The encoder just writes frames until explicitly told to stop
        }
    }
    
    /**
     * Closes the current event recording and finalizes the file.
     */
    private void closeEventRecording() {
        // CRITICAL FIX: Do NOT set isWritingToFile=false yet!
        // The drainer thread checks isWritingToFile to decide whether to write
        // frames to the muxer. Setting it false first causes the drainer to
        // dequeue frames from the encoder but SKIP writing them — losing the
        // last segment's frames on shutdown.
        //
        // Correct order:
        //   1. Stop drainer thread (waits for current drain cycle to finish)
        //   2. Do one final synchronous drain WITH isWritingToFile still true
        //   3. THEN set isWritingToFile=false and close the muxer
        
        recording = false;
        
        // Step 1: Stop drainer thread BEFORE touching the muxer.
        // The drainer may be in the middle of muxer.writeSampleData() — 
        // calling muxer.stop() concurrently corrupts the MP4 (broken moov atom).
        stopDrainerThread();
        
        // Step 2: Final synchronous drain — flush any frames still queued in
        // the encoder's output buffer. isWritingToFile is still true so these
        // frames WILL be written to the muxer.
        // FIX: Drain in a loop until the encoder is truly empty. A single call
        // to drainEncoderInternal() may not get all frames if the encoder is still
        // processing the last few input buffers. Loop with a short sleep to give
        // the hardware encoder time to finish encoding in-flight frames.
        try {
            for (int drainPass = 0; drainPass < 5; drainPass++) {
                int framesBefore = recordedFrames;
                drainEncoderInternal();
                int framesWritten = recordedFrames - framesBefore;
                if (framesWritten == 0 && drainPass > 0) {
                    break;  // Encoder is empty
                }
                if (framesWritten > 0 && drainPass < 4) {
                    // More frames were available — give encoder a moment to finish any in-flight
                    try { Thread.sleep(20); } catch (InterruptedException ignored) {}
                }
            }
        } catch (Exception e) {
            logger.warn("Final drain before close failed: " + e.getMessage());
        }
        
        // Step 3 + 4: under muxerLock, flush remaining queued packets into the
        // still-live muxer, then stop+release. Tracking stopOk lets us refuse
        // to rename a file whose moov was never written — that file would be
        // sized, named .mp4, and unplayable.
        boolean stopOk = false;
        synchronized (muxerLock) {
            MuxerPacket packet;
            int flushed = 0;
            while ((packet = muxerWriteQueue.poll()) != null) {
                if (muxerStarted && muxer != null) {
                    try {
                        muxer.writeSampleData(trackIndex, packet.data, packet.info);
                        if (firstFramePtsUs < 0) firstFramePtsUs = packet.info.presentationTimeUs;
                        lastFramePtsUs = packet.info.presentationTimeUs;
                        recordedFrames++;
                        flushed++;
                    } catch (Exception e) {
                        logger.warn("Final flush write error: " + e.getMessage());
                        writerAbortedCorrupt = true;
                        break;
                    }
                }
            }
            if (flushed > 0) {
                logger.info("Final muxer queue flush: " + flushed + " frames written");
            }

            // No more writers can race us now — flag the writer state OFF before
            // touching muxer.stop(). isWritingToFile is also cleared under the
            // lock so the upcoming format-change handler can't reopen the muxer.
            isWritingToFile = false;

            // Stop muxer (may throw if no frames were written, or if the
            // underlying file descriptor was severed by an SD-card unmount).
            try {
                if (muxerStarted && muxer != null) {
                    muxer.stop();
                    stopOk = true;
                }
            } catch (Exception e) {
                logger.warn("Muxer stop error (may have had no frames): " + e.getMessage());
            } finally {
                muxerStarted = false;
            }

            try {
                if (muxer != null) {
                    muxer.release();
                }
            } catch (Exception e) {
                logger.warn("Muxer release error: " + e.getMessage());
            } finally {
                muxer = null;
                trackIndex = -1;
            }
        }

        // Rename temp to final, quarantine if broken, or delete if empty.
        // SOTA: never promote a tempFile to a final .mp4 unless the muxer
        // actually finalized — that's the single rule that prevents the
        // "60 MB file that won't play" symptom.
        boolean recordingBroken = !stopOk || writerAbortedCorrupt;
        if (tempFile != null && tempFile.exists()) {
            if (!recordingBroken && recordedFrames > 0 && tempFile.length() > 1024) {
                File finalFile = new File(outputPath);
                if (tempFile.renameTo(finalFile)) {
                    // Use actual PTS range for accurate duration (not recordedFrames/fps
                    // which is misleading when pre-record frames are included)
                    float durationSec = (firstFramePtsUs >= 0 && lastFramePtsUs > firstFramePtsUs)
                            ? (lastFramePtsUs - firstFramePtsUs) / 1_000_000.0f
                            : recordedFrames / (float) fps;
                    logger.info(String.format("Event saved: %s (segment %d, %d frames, %.1f sec, %d KB, codec=%s, bitrate=%d Mbps)",
                            finalFile.getName(), segmentNumber, recordedFrames, durationSec, finalFile.length() / 1024,
                            codecMimeType.equals(MediaFormat.MIMETYPE_VIDEO_HEVC) ? "H.265" : "H.264",
                            bitrate / 1_000_000));

                    // Make file visible to events page and UI app
                    try {
                        com.overdrive.app.storage.StorageManager.getInstance().onFileSaved(finalFile);
                    } catch (Exception e) {
                        logger.warn("onFileSaved error: " + e.getMessage());
                    }

                    try {
                        TelegramNotifier.notifyVideoRecorded(
                                finalFile.getAbsolutePath(), null, (int) durationSec);
                    } catch (Exception e) {
                        logger.warn("Failed to emit video notification: " + e.getMessage());
                    }
                } else {
                    logger.error("Failed to rename temp file — deleting orphan");
                    tempFile.delete();
                }
            } else if (recordingBroken) {
                // Quarantine: keep evidence under a sidecar extension so the
                // recordings UI's *.mp4 listing doesn't pick it up. An
                // operator can still find it on disk for diagnostics.
                File broken = new File(outputPath + ".broken");
                if (!tempFile.renameTo(broken)) {
                    logger.warn("Quarantine rename failed; deleting broken tmp: " + tempFile.getName());
                    tempFile.delete();
                } else {
                    logger.warn("Quarantined broken recording (stopOk=" + stopOk
                            + ", writerAborted=" + writerAbortedCorrupt
                            + ", " + (broken.length() / 1024) + " KB): " + broken.getName());
                }
            } else {
                // Empty / sub-1KB recording — drop it silently.
                logger.warn("Deleting empty/corrupt temp file: " + tempFile.getName() +
                        " (frames=" + recordedFrames + ", size=" + tempFile.length() + ")");
                tempFile.delete();
            }
        }
        
        // Reset state
        recordedFrames = 0;
        firstFramePtsUs = -1;
        lastFramePtsUs = -1;
        postRecordStopTime = 0;
        segmentStartTime = 0;
        segmentNumber = 0;
        segmentBasePath = null;
        
        // Restart drainer thread — encoder is still alive, just not writing to file.
        // Pre-record buffer and streaming still need draining.
        startDrainerThread();
        
        if (fileClosedCallback != null) {
            fileClosedCallback.run();
        }
    }
    
    /**
     * Legacy method for compatibility.
     */
    public void stopRecording() {
        stopEventRecording(true, 0);
    }
    
    /**
     * Sets callback for when file is closed.
     * 
     * @param callback Callback to run when file closes
     */
    public void setFileClosedCallback(Runnable callback) {
        this.fileClosedCallback = callback;
    }
    
    /**
     * Requests a sync frame (I-frame) immediately.
     * 
     * Used when an event is detected to ensure clean playback start.
     */
    public void requestSyncFrame() {
        if (encoder != null) {
            try {
                Bundle params = new Bundle();
                params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
                encoder.setParameters(params);
                logger.debug( "Sync frame requested");
            } catch (Exception e) {
                logger.error( "Failed to request sync frame", e);
            }
        }
    }
    
    /**
     * Changes the encoder bitrate dynamically.
     * 
     * @param newBitrate New bitrate in bps
     */
    public void setBitrate(int newBitrate) {
        if (encoder != null && newBitrate != bitrate) {
            try {
                Bundle params = new Bundle();
                params.putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, newBitrate);
                encoder.setParameters(params);
                
                this.bitrate = newBitrate;
                logger.info( "Bitrate changed to: " + (newBitrate / 1_000_000) + " Mbps");
            } catch (Exception e) {
                logger.error( "Failed to change bitrate", e);
            }
        }
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
     * Checks if currently writing to file.
     * 
     * @return true if actively writing to file, false otherwise
     */
    public boolean isWritingToFile() {
        return isWritingToFile;
    }
    
    /**
     * Gets the number of recorded frames.
     * 
     * @return Frame count
     */
    public int getRecordedFrames() {
        return recordedFrames;
    }
    
    /**
     * Get the actual duration of the pre-record buffer that was flushed.
     * This may be longer than the configured preRecordMs because the H.264
     * circular buffer starts from the nearest keyframe.
     */
    public long getActualPreRecordDurationMs() {
        return actualPreRecordDurationMs;
    }
    
    /**
     * Gets the current bitrate.
     * 
     * @return Bitrate in bps
     */
    public int getBitrate() {
        return bitrate;
    }
    
    /**
     * Releases all resources.
     */
    public void release() {
        // SOTA: Stop drainer thread first
        stopDrainerThread();
        
        if (recording) {
            stopRecording();
        }
        
        if (encoder != null) {
            // Note: by the time we reach release(), stopRecording() above
            // has already finalized any active recording (drained and stopped
            // the muxer). Final-frame-loss is handled there. We just stop
            // and release the codec.
            try {
                encoder.stop();
            } catch (Exception e) {
                logger.error( "Error stopping encoder", e);
            }

            try {
                encoder.release();
            } catch (Exception e) {
                logger.error( "Error releasing encoder", e);
            }

            encoder = null;
        }
        
        if (inputSurface != null) {
            inputSurface.release();
            inputSurface = null;
        }

        // Clear the shared pre-record buffer if THIS instance owned it. The
        // buffer is static + shared across encoder reinits to avoid 23MB
        // realloc, but stale packets from a previous codec/resolution must
        // not survive into the next event recording. Without this, a crash
        // path that skips stopRecording() leaves packets in the buffer that
        // the next instance will flush into its first event MP4.
        if (preRecordBuffer != null) {
            synchronized (bufferLock) {
                if (preRecordBuffer == sharedPreRecordBuffer) {
                    sharedPreRecordBuffer.clear();
                }
            }
            preRecordBuffer = null;
        }

        logger.info( "Released");
    }
    
    // ==================== SOTA: Background Drainer Thread ====================
    
    /**
     * Starts the background drainer thread.
     * This moves SD card I/O off the GL thread to prevent freezes.
     */
    private void startDrainerThread() {
        if (drainerRunning) {
            logger.warn("Drainer thread already running");
            return;
        }
        
        drainerRunning = true;
        drainerThread = new Thread(() -> {
            logger.info("Encoder drainer thread started");
            while (drainerRunning) {
                try {
                    // Drain the encoder (SD card I/O happens here, not on GL thread)
                    drainEncoderInternal();
                    
                    // Don't burn CPU - wait a tiny bit for new frames
                    Thread.sleep(DRAIN_INTERVAL_MS);
                } catch (InterruptedException e) {
                    break;
                } catch (Exception e) {
                    logger.error("Drainer error: " + e.getMessage());
                }
            }
            logger.info("Encoder drainer thread stopped");
        }, "GpuEncoderDrainer");
        
        drainerThread.setPriority(Thread.NORM_PRIORITY);
        drainerThread.start();
        
        // Start disk writer thread (handles muxer I/O separately from encoder dequeue)
        startDiskWriterThread();
    }
    
    /**
     * Stops the background drainer thread.
     */
    private void stopDrainerThread() {
        drainerRunning = false;
        if (drainerThread != null) {
            try {
                drainerThread.interrupt();
                // SOTA: 2 s join matches the disk writer's join. The drainer can
                // be inside a single drainEncoderInternal() pass that takes
                // 100+ ms under SD-card pressure; the old 500 ms ceiling let
                // the close path move on while the drainer was still pushing
                // packets to the queue, racing the muxer.stop() call.
                drainerThread.join(2000);
            } catch (InterruptedException e) {
                // Ignore
            }
            drainerThread = null;
        }

        // Stop disk writer after drainer (drainer may still be pushing to the queue)
        stopDiskWriterThread();
    }
    
    /**
     * FORTIFY FIX: Stops the drainer thread before camera close.
     * 
     * The drainer thread calls MediaCodec.dequeueOutputBuffer() which internally
     * accesses the camera's SurfaceTexture buffer queue. If the camera is closed
     * (destroying the native mutex) while the drainer is mid-dequeue, we get:
     *   FORTIFY: pthread_mutex_lock called on a destroyed mutex
     * 
     * This method stops the drainer and waits for it to fully exit before returning,
     * making it safe to close the camera afterwards.
     * 
     * Call restartDrainerAfterCameraClose() after the camera is reopened.
     */
    public void stopDrainerForCameraClose() {
        logger.info("Stopping drainer for camera close...");
        drainerRunning = false;
        if (drainerThread != null) {
            try {
                drainerThread.interrupt();
                drainerThread.join(1000);  // Wait up to 1 second for clean exit
                if (drainerThread.isAlive()) {
                    logger.warn("Drainer thread still alive after 1s — proceeding anyway");
                }
            } catch (InterruptedException e) {
                // Ignore
            }
            drainerThread = null;
        }
        logger.info("Drainer stopped for camera close");
    }
    
    /**
     * Restarts the drainer thread after camera has been reopened.
     * Call this after startCamera() succeeds.
     */
    public void restartDrainerAfterCameraClose() {
        if (!drainerRunning) {
            startDrainerThread();
            logger.info("Drainer restarted after camera reopen");
        }
    }
    
    // ==================== SOTA: Disk Writer Thread ====================
    
    /**
     * Starts the disk writer thread that polls the muxer write queue
     * and writes to the SD card. This decouples SD card I/O from the
     * encoder dequeue loop, preventing I/O stalls from dropping frames.
     */
    private void startDiskWriterThread() {
        if (diskWriterRunning) return;

        diskWriterRunning = true;
        // Each disk writer instance starts with a clean abort flag. The flag is
        // only set when this writer hits the unrecoverable failure threshold; the
        // close/rotate paths read it to decide whether to keep or quarantine the
        // current tempFile.
        writerAbortedCorrupt = false;
        // SD-unmount detection: if writes start failing repeatedly, the underlying
        // file descriptor is dead (typical when BYD/Android unmounts the SD card
        // mid-recording). The MP4's moov atom is written only on stopRecording, so
        // continuing to drain into a broken FD produces an unrecoverable corrupt
        // file. Track consecutive write failures and abort the recording cleanly
        // once we cross a threshold — at least the MP4 prefix on disk has the
        // partial frames already written, and the user gets a clear log instead
        // of a silent corruption.
        final int[] consecutiveWriteFailures = {0};
        final int writeFailureAbortThreshold = 5;
        diskWriterThread = new Thread(() -> {
            logger.info("Disk writer thread started");
            while (diskWriterRunning || !muxerWriteQueue.isEmpty()) {
                try {
                    MuxerPacket packet = muxerWriteQueue.poll();
                    if (packet != null) {
                        // SOTA: serialize against rotateSegment / closeEventRecording.
                        // Without this lock, a concurrent muxer.stop() corrupts the
                        // moov atom and produces a sized-but-unplayable .mp4.
                        synchronized (muxerLock) {
                            if (muxerStarted && muxer != null) {
                                muxer.writeSampleData(trackIndex, packet.data, packet.info);
                                if (firstFramePtsUs < 0) firstFramePtsUs = packet.info.presentationTimeUs;
                                lastFramePtsUs = packet.info.presentationTimeUs;
                                recordedFrames++;
                                consecutiveWriteFailures[0] = 0;
                            }
                        }
                    } else {
                        // Queue empty — sleep briefly to avoid busy-waiting
                        Thread.sleep(4);
                    }
                } catch (InterruptedException e) {
                    // Drain remaining packets before exiting. We deliberately do
                    // NOT write here: by the time the writer is interrupted, the
                    // close/rotate path is about to (or has already) called
                    // muxer.stop(), so any further writeSampleData would corrupt
                    // the moov. The close path drains the queue itself under the
                    // lock before stopping the muxer.
                    break;
                } catch (Exception e) {
                    consecutiveWriteFailures[0]++;
                    logger.error("Disk writer error (#" + consecutiveWriteFailures[0]
                        + "): " + e.getMessage());
                    if (consecutiveWriteFailures[0] >= writeFailureAbortThreshold) {
                        logger.error("Aborting recording: " + writeFailureAbortThreshold
                            + " consecutive write failures (likely SD card unmounted). "
                            + "Partial file at " + (tempFile != null ? tempFile.getAbsolutePath() : "unknown")
                            + " will not be playable.");
                        // Mark the current segment corrupt so the close/rotate
                        // path quarantines it rather than promoting tempFile to
                        // outputPath. The user must never see a final .mp4
                        // filename for a file whose moov was never written.
                        writerAbortedCorrupt = true;
                        // Clear queue so the writer loop exits promptly
                        muxerWriteQueue.clear();
                        diskWriterRunning = false;
                        // Don't call stopRecording() from here — that's a heavyweight
                        // operation that touches state owned by other threads. Just
                        // exit the writer; the main pipeline's existing watchdog or
                        // the next user action will trigger cleanup.
                        break;
                    }
                }
            }
            logger.info("Disk writer thread stopped");
        }, "GpuDiskWriter");
        
        // Lower priority than drainer — SD card I/O should never preempt encoder dequeue
        diskWriterThread.setPriority(Thread.MIN_PRIORITY + 1);
        diskWriterThread.start();
    }
    
    /**
     * Stops the disk writer thread, flushing any remaining packets.
     */
    private void stopDiskWriterThread() {
        diskWriterRunning = false;
        if (diskWriterThread != null) {
            try {
                diskWriterThread.interrupt();
                diskWriterThread.join(2000);  // Allow up to 2s for final flush
            } catch (InterruptedException e) {
                // Ignore
            }
            diskWriterThread = null;
        }
    }
    
    /**
     * Public drainEncoder() - now just a no-op since draining happens on background thread.
     * Kept for API compatibility with existing code that calls it.
     */
    public void drainEncoder() {
        // SOTA: Draining now happens on background thread, not GL thread
        // This method is kept for API compatibility but does nothing
    }
    
    /**
     * Internal drain method called by background thread.
     * Handles all encoder output and SD card I/O.
     */
    private void drainEncoderInternal() {
        if (encoder == null) {
            return;
        }
        
        // SOTA: Process queued pre-record packets first (flush all at once).
        // These packets are written to the SD card via the muxer, which does NOT
        // block the encoder's input surface. The original chunking was added to prevent
        // MediaCodec backpressure, but that was only an issue when flushing on the GL
        // thread. Now that draining happens on a background thread, writing all packets
        // in one pass is safe and ensures no PTS gap between pre-record and live frames.
        //
        // CRITICAL: Live frames must NOT be written until this flush completes.
        // The pre-record packets have older PTS values. If live frames (with current PTS)
        // are interleaved, the muxer sees non-monotonic timestamps and the MP4 is corrupt.
        if (flushInProgress && muxerStarted) {
            int flushedCount = 0;
            H264CircularBuffer.Packet queuedPacket;
            while ((queuedPacket = pendingFlushQueue.poll()) != null) {
                // Push pre-record packets to the muxer write queue (same path as live frames)
                offerMuxerPacket(new MuxerPacket(queuedPacket.data, queuedPacket.info));
                flushedCount++;
            }
            if (flushedCount > 0) {
                logger.info("Async flush complete: " + flushedCount + " pre-record frames queued for disk write");
            }
            flushInProgress = false;
        }
        
        // Check if segment rotation needed (only when actively writing to file).
        // SOTA: rotation requires a live disk writer + drainer; if either is
        // shutting down (e.g., we're inside the synchronous final drain in
        // closeEventRecording) the rotation logic would deadlock or produce a
        // stranded muxer. Skip in that case.
        if (isWritingToFile && segmentStartTime > 0 && drainerRunning && diskWriterRunning) {
            long elapsed = System.currentTimeMillis() - segmentStartTime;
            if (elapsed >= SEGMENT_DURATION_MS) {
                logger.info("Segment duration reached (" + (elapsed / 1000) + "s), rotating to new file...");
                rotateSegment();
            }
        }
        
        MediaCodec.BufferInfo bufferInfo = reusableBufferInfo;
        
        while (true) {
            int outputBufferIndex;
            try {
                outputBufferIndex = encoder.dequeueOutputBuffer(bufferInfo, 0);
            } catch (Exception e) {
                // Encoder may have been released
                break;
            }
            
            if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                break;  // No more output available
            } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                // Format changed - add track to muxer and send SPS/PPS to stream
                MediaFormat format = encoder.getOutputFormat();
                
                // Save format for reuse in subsequent recordings
                if (savedFormat == null) {
                    savedFormat = format;
                    String mime = format.getString(MediaFormat.KEY_MIME);
                    logger.info("Saved encoder format for reuse (codec=" +
                        (mime != null && mime.contains("hevc") ? "H.265" : "H.264") + ")");

                    // Notify any waiter (one-shot). This is the canonical
                    // moment isFormatAvailable() flips false→true.
                    FormatAvailableListener l = formatAvailableListener;
                    if (l != null) {
                        formatAvailableListener = null;
                        try { l.onFormatAvailable(); }
                        catch (Exception e) { logger.warn("FormatAvailableListener error: " + e.getMessage()); }
                    }
                }
                
                if (recording && !muxerStarted) {
                    synchronized (muxerLock) {
                        if (muxer != null && !muxerStarted) {
                            trackIndex = muxer.addTrack(format);
                            muxer.start();
                            muxerStarted = true;
                            logger.info("Muxer started (track=" + trackIndex + ")");
                        }
                    }
                }
                
                // Send SPS/PPS to streaming callback
                if (streamCallback != null && !streamHeadersSent) {
                    try {
                        ByteBuffer sps = format.getByteBuffer("csd-0");
                        ByteBuffer pps = format.getByteBuffer("csd-1");
                        if (sps != null && pps != null) {
                            streamCallback.onSpsPps(sps.duplicate(), pps.duplicate());
                            streamHeadersSent = true;
                            logger.info("SPS/PPS sent to stream");
                        }
                    } catch (Exception e) {
                        logger.error("Failed to send SPS/PPS", e);
                    }
                }
                
            } else if (outputBufferIndex >= 0) {
                // Got encoded data
                ByteBuffer outputBuffer = encoder.getOutputBuffer(outputBufferIndex);
                
                if (outputBuffer != null && bufferInfo.size > 0) {
                    // ALWAYS add to circular buffer (for pre-record) - unless stream-only mode
                    if (usePreRecordBuffer && preRecordBuffer != null) {
                        preRecordBuffer.add(outputBuffer, bufferInfo);
                    }
                    
                    // PATH A: Write to disk (if event recording active)
                    // SOTA: Don't write to muxer directly — push to the muxer write queue.
                    // The disk writer thread handles the actual SD card I/O, preventing
                    // I/O stalls from blocking the encoder dequeue loop.
                    if (isWritingToFile && muxerStarted && !flushInProgress) {
                        offerMuxerPacket(new MuxerPacket(outputBuffer, bufferInfo));
                    }
                    
                    // PATH B: Send to network (if streaming)
                    if (streamCallback != null && streamHeadersSent) {
                        try {
                            // Duplicate buffer to avoid interfering with muxer
                            ByteBuffer streamBuffer = outputBuffer.duplicate();
                            streamBuffer.position(bufferInfo.offset);
                            streamBuffer.limit(bufferInfo.offset + bufferInfo.size);
                            streamCallback.onH264Packet(streamBuffer, bufferInfo);
                        } catch (Exception e) {
                            logger.error("Stream callback error", e);
                        }
                    }
                }
                
                // Release output buffer
                encoder.releaseOutputBuffer(outputBufferIndex, false);
            }
        }
    }
    
    /**
     * Rotates to a new segment file.
     * 
     * Closes current file and starts new segment WITHOUT flushing pre-record buffer
     * (since we're continuing the same event, not starting a new one).
     */
    private void rotateSegment() {
        if (!isWritingToFile) {
            return;
        }

        logger.info("Rotating segment " + segmentNumber + " - closing current file");

        // SOTA: Capture the old segment's identity locally. Field-level state
        // (tempFile / outputPath / recordedFrames / firstFramePtsUs / lastFramePtsUs)
        // belongs to the *new* segment by the end of this method; we must not
        // let the new segment's stats mask whether the old one was finalized
        // cleanly.
        final File oldTemp = tempFile;
        final String oldOutputPath = outputPath;
        final int oldRecordedFrames = recordedFrames;
        final long oldFirstPtsUs = firstFramePtsUs;
        final long oldLastPtsUs = lastFramePtsUs;
        final int oldSegmentNumber = segmentNumber;

        // Step 1: stop the disk writer BEFORE touching the muxer.
        // Without this, the writer may be inside muxer.writeSampleData() when
        // we call muxer.stop(), corrupting the moov atom and leaving an
        // unplayable but final-named .mp4 on disk. stopDiskWriterThread() also
        // joins the thread, so by the time it returns no other thread is
        // racing us.
        stopDiskWriterThread();

        boolean stopOk = false;
        synchronized (muxerLock) {
            // Step 2: drain any packets that the writer left in the queue
            // straight into the still-live old muxer. Anything we drop here
            // turns into a gap in the segment.
            MuxerPacket pkt;
            int drained = 0;
            while ((pkt = muxerWriteQueue.poll()) != null) {
                if (muxerStarted && muxer != null) {
                    try {
                        muxer.writeSampleData(trackIndex, pkt.data, pkt.info);
                        if (firstFramePtsUs < 0) firstFramePtsUs = pkt.info.presentationTimeUs;
                        lastFramePtsUs = pkt.info.presentationTimeUs;
                        recordedFrames++;
                        drained++;
                    } catch (Exception e) {
                        logger.warn("Rotation flush error: " + e.getMessage());
                        writerAbortedCorrupt = true;
                        break;
                    }
                }
            }
            if (drained > 0) {
                logger.debug("Rotation drained " + drained + " queued frames into old segment");
            }

            // Step 3: stop the old muxer. Track success so we can quarantine
            // the file if anything goes wrong — a swallowed exception here
            // used to ship a sized-but-unplayable .mp4 to the user.
            try {
                if (muxerStarted && muxer != null) {
                    muxer.stop();
                    stopOk = true;
                }
            } catch (Exception e) {
                logger.warn("Muxer stop error during rotation: " + e.getMessage());
            } finally {
                muxerStarted = false;
            }

            try {
                if (muxer != null) {
                    muxer.release();
                }
            } catch (Exception e) {
                logger.warn("Muxer release error during rotation: " + e.getMessage());
            } finally {
                muxer = null;
                trackIndex = -1;
            }
        }

        // Step 4: finalize the old tempFile on disk — but ONLY rename to the
        // final extension if the muxer actually finalized cleanly. Otherwise
        // the file has no moov atom and would be a sized, named, unplayable
        // .mp4 — exactly the bug we're fixing. Quarantine instead.
        boolean segmentBroken = !stopOk || writerAbortedCorrupt;
        // Tracks the .mp4 produced by THIS rotation (or null if none usable).
        // Used to notify the SegmentListener after the new segment is open
        // so the engine can flush hero/sidecar against the right filename.
        File finalisedSegment = null;
        if (oldTemp != null && oldTemp.exists()) {
            if (!segmentBroken && oldRecordedFrames > 0 && oldTemp.length() > 1024) {
                File finalFile = new File(oldOutputPath);
                if (oldTemp.renameTo(finalFile)) {
                    finalisedSegment = finalFile;
                    float durationSec = (oldFirstPtsUs >= 0 && oldLastPtsUs > oldFirstPtsUs)
                            ? (oldLastPtsUs - oldFirstPtsUs) / 1_000_000.0f
                            : oldRecordedFrames / (float) fps;
                    logger.info(String.format("Segment %d saved: %s (%d frames, %.1f sec, %d KB)",
                            oldSegmentNumber, finalFile.getName(), oldRecordedFrames,
                            durationSec, finalFile.length() / 1024));
                    try {
                        com.overdrive.app.storage.StorageManager.getInstance().onFileSaved(finalFile);
                    } catch (Exception e) {
                        logger.warn("onFileSaved error: " + e.getMessage());
                    }
                } else {
                    logger.error("Failed to rename segment " + oldSegmentNumber + " — deleting orphan");
                    oldTemp.delete();
                }
            } else if (segmentBroken) {
                // Quarantine the broken segment under a .broken.mp4 sidecar so
                // an operator can tell *something* went wrong without it
                // showing up in the recordings UI as a real .mp4.
                File broken = new File(oldOutputPath + ".broken");
                if (!oldTemp.renameTo(broken)) {
                    logger.warn("Quarantine rename failed; deleting broken tmp: " + oldTemp.getName());
                    oldTemp.delete();
                } else {
                    logger.warn("Quarantined broken segment " + oldSegmentNumber
                            + " (stopOk=" + stopOk + ", writerAborted=" + writerAbortedCorrupt
                            + ", " + (broken.length() / 1024) + " KB): " + broken.getName());
                }
            } else {
                logger.warn("Deleting empty segment " + oldSegmentNumber + " tmp file");
                oldTemp.delete();
            }
        }

        // SOTA: if the SD card just died we should not optimistically open a new
        // muxer and pretend recording continues — every subsequent write would
        // fail and pile up another quarantined segment. Stop here; the next
        // user-driven start will trigger a fresh recorder init.
        if (writerAbortedCorrupt) {
            logger.warn("Writer aborted — abandoning rotation, recording stopped");
            isWritingToFile = false;
            recording = false;
            recordedFrames = 0;
            firstFramePtsUs = -1;
            lastFramePtsUs = -1;
            segmentStartTime = 0;
            segmentNumber = 0;
            segmentBasePath = null;
            return;
        }

        // Step 5: open the next segment. Each rotated segment gets a fresh
        // yyyyMMdd_HHmmss timestamp (no _1, _2 suffixes) so it matches the
        // naming convention used at recording start.
        segmentNumber++;
        String newPath = nextSegmentPath(segmentBasePath);

        try {
            this.outputPath = newPath;
            tempFile = new File(newPath + ".tmp");
            // Reset per-segment counters BEFORE the new muxer goes live so the
            // disk writer (restarted below) records frames against the new
            // file, not the old segment's totals.
            recordedFrames = 0;
            firstFramePtsUs = -1;
            lastFramePtsUs = -1;
            segmentStartTime = System.currentTimeMillis();

            synchronized (muxerLock) {
                muxer = new MediaMuxer(tempFile.getAbsolutePath(),
                        MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

                if (savedFormat != null) {
                    trackIndex = muxer.addTrack(savedFormat);
                    muxer.start();
                    muxerStarted = true;
                }
            }

            // Request a keyframe immediately so the new segment is independently
            // decodable from its first sample. Without this, the segment would
            // start with P-frames referencing a previous I-frame that lives in
            // the old (now closed) file, and players would render garbage until
            // the next 2-second I-frame interval.
            requestSyncFrame();

            logger.info("Segment " + segmentNumber + " started: " + tempFile.getName());

            // Notify the engine: old segment is finalised, new segment is open.
            // Engine flushes hero JPEG + per-actor thumbs + JSON sidecar against
            // closedSegment, then resets ThumbnailBuffer/EventTimelineCollector
            // so the next 2 minutes of metadata accumulates against newSegment.
            // Failing here would orphan the metadata, so swallow exceptions —
            // the new segment is already open and writing.
            SegmentListener listener = segmentListener;
            if (listener != null) {
                try {
                    listener.onSegmentClosed(finalisedSegment, new File(newPath));
                } catch (Throwable t) {
                    logger.warn("SegmentListener error: " + t.getMessage());
                }
            }

        } catch (Exception e) {
            logger.error("Failed to start new segment — stopping recording", e);
            // Clean up the failed tmp file
            if (tempFile != null && tempFile.exists()) {
                tempFile.delete();
            }
            synchronized (muxerLock) {
                if (muxer != null) {
                    try { muxer.release(); } catch (Exception ignored) {}
                    muxer = null;
                }
                muxerStarted = false;
                trackIndex = -1;
            }
            isWritingToFile = false;
            recording = false;
            return;
        }

        // Step 6: bring the disk writer back online. It will pick up frames
        // from the (now drained, then refilled by the drainer) queue and write
        // them to the new muxer.
        startDiskWriterThread();
    }
    
    /**
     * Flushes and closes muxer immediately.
     * 
     * Used when ACC state changes during recording to ensure
     * file is properly closed before shutdown.
     */
    public void flushAndClose() {
        if (recording) {
            logger.info( "Flushing and closing muxer (ACC state change)");
            stopRecording();
        }
    }
    
    // Track the current output file path for cleanup protection
    private static volatile String currentlyWritingPath = null;
    
    /**
     * Gets the path of the file currently being written to.
     * Used by cleanup to avoid deleting active files.
     */
    public String getCurrentOutputPath() {
        return outputPath;
    }

    /**
     * Build the path for the next rotated segment.
     *
     * <p>Input is the base path of the original recording (no extension), e.g.
     * {@code /sdcard/.../cam_20260513_140523}. The original filename is
     * {@code <prefix>_yyyyMMdd_HHmmss}; we drop the trailing timestamp and
     * append a fresh one so each segment is a self-describing
     * {@code <prefix>_yyyyMMdd_HHmmss.mp4}, never {@code _1}, {@code _2}, etc.
     *
     * <p>If the basename can't be parsed (unexpected format), falls back to
     * the legacy {@code <base>_<n>.mp4} naming so a single bad recording
     * doesn't lose its rotation.
     */
    private String nextSegmentPath(String basePath) {
        String fresh = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss",
                java.util.Locale.US).format(new java.util.Date());
        try {
            int slash = basePath.lastIndexOf('/');
            String dir = slash >= 0 ? basePath.substring(0, slash + 1) : "";
            String name = slash >= 0 ? basePath.substring(slash + 1) : basePath;
            // Strip the original _yyyyMMdd_HHmmss suffix (last two underscore
            // segments — date and time). Anything else is the prefix.
            int lastUnderscore = name.lastIndexOf('_');
            if (lastUnderscore > 0) {
                int prevUnderscore = name.lastIndexOf('_', lastUnderscore - 1);
                if (prevUnderscore > 0) {
                    String prefix = name.substring(0, prevUnderscore);
                    String candidate = dir + prefix + "_" + fresh + ".mp4";
                    // Same-second rotation (or pre-existing file) — disambiguate
                    // with a short suffix rather than overwriting.
                    if (new java.io.File(candidate).exists()
                            || candidate.equals(outputPath)) {
                        // Underscore (not dash) so the UI regexes in
                        // RecordingsApiHandler — CAM_PATTERN / EVENT_PATTERN /
                        // PROXIMITY_PATTERN, all `(?:_\d+)?` — accept the
                        // disambiguated filename. A dash made the segment
                        // invisible to the web UI, calendar, and storage stats.
                        candidate = dir + prefix + "_" + fresh + "_" + segmentNumber + ".mp4";
                    }
                    return candidate;
                }
            }
        } catch (Exception ignored) {}
        return basePath + "_" + segmentNumber + ".mp4";
    }
    
    /**
     * Implements loop recording by deleting oldest segments when storage is low.
     * 
     * CRITICAL: Protects files that are currently being written to prevent corruption.
     * 
     * @param directory Directory containing recordings
     * @param maxSizeBytes Maximum total size in bytes
     */
    public static void cleanupOldSegments(File directory, long maxSizeBytes) {
        cleanupOldSegments(directory, maxSizeBytes, null);
    }

    /**
     * Clean up orphaned .tmp files that were left behind by crashed recordings,
     * and reap *.broken quarantine sidecars produced by the close/rotate paths
     * when a muxer.stop() failed or the disk writer aborted. Files older than
     * 5 minutes are removed.
     */
    public static void cleanupOrphanedTmpFiles(File directory) {
        if (!directory.exists() || !directory.isDirectory()) return;

        File[] orphans = directory.listFiles((dir, name) ->
                name.endsWith(".tmp") || name.endsWith(".broken"));
        if (orphans == null) return;

        long now = System.currentTimeMillis();
        for (File f : orphans) {
            long age = now - f.lastModified();
            if (age > 5 * 60 * 1000) { // Older than 5 minutes
                long size = f.length();
                if (f.delete()) {
                    logger.info("Cleaned orphan: " + f.getName() + " (" + (size / 1024)
                            + " KB, age=" + (age / 1000) + "s)");
                }
            }
        }
    }
    
    /**
     * Implements loop recording by deleting oldest segments when storage is low.
     * 
     * CRITICAL: Protects files that are currently being written to prevent corruption.
     * 
     * @param directory Directory containing recordings
     * @param maxSizeBytes Maximum total size in bytes
     * @param activeRecorder Optional recorder to check for active file (null = no protection)
     */
    public static void cleanupOldSegments(File directory, long maxSizeBytes, HardwareEventRecorderGpu activeRecorder) {
        if (!directory.exists() || !directory.isDirectory()) {
            return;
        }
        
        File[] files = directory.listFiles((dir, name) -> name.endsWith(".mp4"));
        if (files == null || files.length == 0) {
            return;
        }
        
        // Get the currently active file path (if any)
        String activeFilePath = null;
        String activeTempPath = null;
        if (activeRecorder != null && activeRecorder.isWritingToFile()) {
            activeFilePath = activeRecorder.outputPath;
            if (activeRecorder.tempFile != null) {
                activeTempPath = activeRecorder.tempFile.getAbsolutePath();
            }
        }
        
        // Calculate total size (excluding active files)
        long totalSize = 0;
        for (File file : files) {
            // Skip files currently being written
            String filePath = file.getAbsolutePath();
            if (filePath.equals(activeFilePath) || filePath.equals(activeTempPath)) {
                logger.debug("Skipping active file in size calculation: " + file.getName());
                continue;
            }
            // Skip temp files (*.tmp) - they're being written
            if (file.getName().endsWith(".tmp")) {
                logger.debug("Skipping temp file in size calculation: " + file.getName());
                continue;
            }
            totalSize += file.length();
        }
        
        // Delete oldest files if over limit
        if (totalSize > maxSizeBytes) {
            // Sort by last modified (oldest first)
            java.util.Arrays.sort(files, (f1, f2) -> 
                Long.compare(f1.lastModified(), f2.lastModified()));
            
            for (File file : files) {
                if (totalSize <= maxSizeBytes) {
                    break;
                }
                
                String filePath = file.getAbsolutePath();
                
                // CRITICAL: Never delete the file currently being written
                if (filePath.equals(activeFilePath) || filePath.equals(activeTempPath)) {
                    logger.warn("Skipping deletion of active file: " + file.getName());
                    continue;
                }
                
                // Skip temp files - they're being written
                if (file.getName().endsWith(".tmp")) {
                    logger.warn("Skipping deletion of temp file: " + file.getName());
                    continue;
                }
                
                // Skip very recent files (less than 5 seconds old) - may still be finalizing
                long fileAge = System.currentTimeMillis() - file.lastModified();
                if (fileAge < 5000) {
                    logger.warn("Skipping deletion of recent file (age=" + fileAge + "ms): " + file.getName());
                    continue;
                }
                
                long fileSize = file.length();
                if (file.delete()) {
                    totalSize -= fileSize;
                    long sidecarBytes = deleteSegmentSidecars(file);
                    logger.info("Deleted old segment: " + file.getName() +
                            " (" + (fileSize / 1024) + " KB"
                            + (sidecarBytes > 0 ? ", +" + (sidecarBytes / 1024) + " KB sidecars" : "")
                            + ")");
                } else {
                    logger.warn("Failed to delete file: " + file.getName());
                }
            }
        }
    }

    /**
     * Removes the sidecar files that accompany an .mp4 segment: JSON event
     * timeline, v3 hero JPEG, and per-actor thumbnails {@code thumb_<base>_a*.jpg}.
     *
     * Without this, the loop-rotation deletion only frees the .mp4's bytes —
     * sidecars accumulate as orphans because future passes continue to skip
     * non-.mp4 files. Returns the freed bytes.
     */
    private static long deleteSegmentSidecars(File mp4File) {
        // Drop the API-handler cache entry for this segment so /api/recordings
        // doesn't keep returning a phantom row for a file that's been rotated.
        try {
            com.overdrive.app.server.RecordingsApiHandler.invalidateRecordingCache(
                    mp4File.getAbsolutePath());
        } catch (Throwable ignored) {}

        File parent = mp4File.getParentFile();
        if (parent == null) return 0L;
        String mp4Name = mp4File.getName();
        if (!mp4Name.endsWith(".mp4")) return 0L;
        String base = mp4Name.substring(0, mp4Name.length() - 4);
        long freed = 0L;

        File jsonFile = new File(parent, base + ".json");
        if (jsonFile.exists()) {
            long s = jsonFile.length();
            if (jsonFile.delete()) freed += s;
        }
        File heroFile = new File(parent, base + ".jpg");
        if (heroFile.exists()) {
            long s = heroFile.length();
            if (heroFile.delete()) freed += s;
        }
        // Anchor with "_a" so sibling segment thumbs (e.g. <base>_2's actor
        // thumbs at "thumb_<base>_2_a*.jpg") aren't swept when this segment
        // is rotated out. ThumbnailBuffer always writes "thumb_<base>_a<id>...".
        final String perActorPrefix = "thumb_" + base + "_a";
        File[] perActor = parent.listFiles((d, name) ->
                name.startsWith(perActorPrefix) && name.endsWith(".jpg"));
        if (perActor != null) {
            for (File f : perActor) {
                long s = f.length();
                if (f.delete()) freed += s;
            }
        }
        return freed;
    }
}
