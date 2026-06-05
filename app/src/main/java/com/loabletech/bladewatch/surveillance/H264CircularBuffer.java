package com.overdrive.app.surveillance;

import android.media.MediaCodec;
import com.overdrive.app.logging.DaemonLogger;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * H264CircularBuffer - SOTA Zero-Allocation Edition.
 * 
 * Fixes video stutter by pooling ByteBuffers.
 * Eliminates 'ByteBuffer.allocateDirect' calls during recording.
 * 
 * Key optimizations:
 * - Pre-allocated buffer pool (no runtime allocations)
 * - Object recycling (zero GC pressure)
 * - Keyframe-aligned pruning (valid MP4 generation)
 * - Thread-safe operations
 */
public class H264CircularBuffer {
    private static final String TAG = "H264CircularBuffer";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);
    
    // MAX BUFFER SIZE: 256KB per packet (enough for 1080p @ 6Mbps I-frames)
    // At 6Mbps, 15fps: avg frame = 50KB, I-frame peak ~150KB
    // 256KB provides generous headroom for variable bitrate spikes
    private static final int MAX_PACKET_SIZE = 256 * 1024;
    
    // POOL CEILING: hard cap to bound peak memory regardless of fps. With
    // 30 fps × 5 s × 256KB = 38 MB worst-case, we allow up to 200 packets
    // (~50 MB peak). Pool sizing inside the ctor uses configured fps and
    // adds 25% headroom; this constant only kicks in if duration × fps
    // somehow exceeds the cap.
    private static final int POOL_CAPACITY = 200;
    /** Default fps used when caller doesn't specify. Conservative. */
    private static final int DEFAULT_FPS_HINT = 15;
    
    /**
     * Mutable Packet wrapper (reusable).
     */
    public static class Packet {
        public ByteBuffer data;  // Reusable container
        public final MediaCodec.BufferInfo info;
        public boolean isKeyFrame;
        
        public Packet(int capacity) {
            // Allocate ONCE during init
            this.data = ByteBuffer.allocateDirect(capacity);
            this.info = new MediaCodec.BufferInfo();
        }
        
        /**
         * Copies the source bytes into the pooled direct buffer.
         *
         * @return {@code true} on success, {@code false} if the source is
         *         larger than {@link #MAX_PACKET_SIZE} (caller MUST drop
         *         the packet — we never grow the buffer at runtime since
         *         the discarded direct buffer would only be reclaimed via
         *         the Cleaner / finalizer, leaking native heap until then).
         */
        public boolean copyFrom(ByteBuffer src, MediaCodec.BufferInfo srcInfo) {
            if (this.data.capacity() < srcInfo.size) {
                // Oversized I-frame spike. Drop rather than reallocate: the
                // old direct buffer would otherwise wait for the Cleaner.
                return false;
            }
            this.info.set(0, srcInfo.size, srcInfo.presentationTimeUs, srcInfo.flags);
            this.isKeyFrame = (srcInfo.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;

            this.data.clear();
            src.position(srcInfo.offset);
            src.limit(srcInfo.offset + srcInfo.size);
            this.data.put(src);
            this.data.flip();
            return true;
        }
    }
    
    // The active ring buffer
    private final ConcurrentLinkedDeque<Packet> buffer = new ConcurrentLinkedDeque<>();
    
    // The Object Pool (Recycler)
    private final ArrayBlockingQueue<Packet> pool;
    
    private final long maxDurationUs;
    private long currentDurationUs = 0;
    private int keyframeCount = 0;
    private int addCount = 0;  // Debug: track total adds
    private final int minKeyframes;  // Minimum keyframes to keep based on duration
    
    /**
     * Creates a circular buffer sized for the configured pre-record window.
     * Uses a default fps hint of 15. Prefer the (durationSeconds, fps) ctor
     * so pool capacity is correct for the actual encoder rate.
     */
    public H264CircularBuffer(int durationSeconds) {
        this(durationSeconds, DEFAULT_FPS_HINT);
    }

    /**
     * Creates a circular buffer sized for {@code durationSeconds × fps}
     * packets plus 25% headroom, capped at POOL_CAPACITY.
     *
     * @param durationSeconds Buffer duration in seconds (e.g., 5)
     * @param fps             Encoder fps used to size the pool. Pass the
     *                        encoder's KEY_FRAME_RATE so 30 fps recordings
     *                        don't exhaust the pool and trigger emergency
     *                        allocations that leak GC pressure under load.
     */
    public H264CircularBuffer(int durationSeconds, int fps) {
        this.maxDurationUs = durationSeconds * 1_000_000L;

        // Calculate minimum keyframes needed based on duration. With 2-second
        // I-frame interval, we need (duration / 2) + 1 keyframes; +1 margin.
        this.minKeyframes = (durationSeconds / 2) + 2;

        // Pool size = duration × fps × 1.25, clamped to POOL_CAPACITY.
        // Clamped fps to [10..30] so a bogus value can't blow up sizing.
        int safeFps = Math.max(10, Math.min(30, fps));
        int estimatedPackets = durationSeconds * safeFps;
        int poolSize = Math.min(estimatedPackets + (estimatedPackets / 4), POOL_CAPACITY);

        logger.info("Pre-allocating circular buffer pool (" + poolSize + " packets × "
                + (MAX_PACKET_SIZE / 1024) + "KB = "
                + (poolSize * MAX_PACKET_SIZE / 1024 / 1024) + "MB) for "
                + durationSeconds + "s @ " + safeFps + "fps...");
        pool = new ArrayBlockingQueue<>(poolSize);
        for (int i = 0; i < poolSize; i++) {
            pool.offer(new Packet(MAX_PACKET_SIZE));
        }
        logger.info("Buffer pool ready (" + durationSeconds + "s, minKeyframes="
                + minKeyframes + "). Zero-allocation mode active.");
    }

    /**
     * Adds a packet to the buffer using pooled allocation.
     * 
     * @param data Encoded H.264 data
     * @param info Buffer metadata
     */
    public synchronized void add(ByteBuffer data, MediaCodec.BufferInfo info) {
        // Borrow a packet from the pool (Instant - no allocation)
        Packet packet = pool.poll();

        if (packet == null) {
            // Pool empty? We are generating frames faster than pruning.
            // Force prune to recycle an old packet.
            if (!buffer.isEmpty()) {
                recyclePacket(buffer.removeFirst());
                packet = pool.poll();
            }

            // Still null? Drop this frame rather than emergency-allocating
            // a fresh direct ByteBuffer. An emergency packet can't be safely
            // returned to a full pool (recyclePacket would have to drop it,
            // leaking its 256KB direct buffer until the Cleaner runs). Pool
            // sizing already accounts for duration × fps × 1.25; sustained
            // exhaustion means the encoder is mis-paced, not a transient.
            if (packet == null) {
                logger.warn("Pool exhausted - dropping packet (size=" + info.size
                        + ", flags=" + info.flags + ")");
                return;
            }
        }

        // Copy data (Fast memcpy, no allocation). Returns false if the source
        // is larger than MAX_PACKET_SIZE — drop and recycle in that case.
        if (!packet.copyFrom(data, info)) {
            logger.warn("Dropping oversized packet (size=" + info.size
                    + " > " + MAX_PACKET_SIZE + ")");
            pool.offer(packet);  // Pool slot still owned by us; return it.
            return;
        }
        buffer.addLast(packet);

        if (packet.isKeyFrame) {
            keyframeCount++;
        }

        addCount++;
        
        // Update duration
        if (buffer.size() > 1) {
            currentDurationUs = buffer.getLast().info.presentationTimeUs - 
                              buffer.getFirst().info.presentationTimeUs;
        }
        
        // Debug: Log buffer state every 50 frames (~6 seconds at 8 FPS)
        if (addCount % 50 == 0) {
            logger.debug(String.format("Buffer state: %d packets, %.1f sec, %d keyframes, pool=%d free",
                    buffer.size(), currentDurationUs / 1_000_000.0, keyframeCount, pool.size()));
        }
        
        // Prune and Recycle old packets
        pruneOldPackets();
    }
    
    /**
     * Recycles a packet back to the pool. Pool capacity equals the number of
     * packets ever allocated (we never allocate beyond pool size; oversize /
     * exhaustion paths drop instead — see {@link #add}). offer() therefore
     * always succeeds.
     *
     * @param p Packet to recycle
     */
    private void recyclePacket(Packet p) {
        if (p != null) {
            if (p.isKeyFrame) {
                keyframeCount--;
            }
            p.data.clear();
            pool.offer(p);
        }
    }
    
    /**
     * Prunes old packets to maintain buffer duration limit.
     * 
     * CRITICAL: Keeps enough keyframes to maintain target duration.
     * minKeyframes is calculated based on configured pre-record duration.
     */
    private void pruneOldPackets() {
        while (currentDurationUs > maxDurationUs && buffer.size() > 1) {
            Packet first = buffer.getFirst();
            
            // Don't prune if we'd drop below minimum keyframes
            if (first.isKeyFrame && keyframeCount <= minKeyframes) {
                break;  // Keep this keyframe, we're at minimum
            }
            
            // Find the next keyframe in the buffer
            Packet nextKeyframe = null;
            for (Packet p : buffer) {
                if (p.isKeyFrame && p != first) {
                    nextKeyframe = p;
                    break;
                }
            }
            
            // Logic to keep Keyframe alignment
            if (first.isKeyFrame && nextKeyframe != null) {
                // Safe to remove - we have another keyframe
                recyclePacket(buffer.removeFirst());
            } else if (!first.isKeyFrame) {
                // Not a keyframe, safe to remove
                recyclePacket(buffer.removeFirst());
            } else {
                // This is the only keyframe, keep it even if over budget
                break;
            }
            
            // Recalculate duration
            if (buffer.size() > 1) {
                currentDurationUs = buffer.getLast().info.presentationTimeUs - 
                                  buffer.getFirst().info.presentationTimeUs;
            } else {
                currentDurationUs = 0;
                break;
            }
        }
    }
    
    /**
     * Returns all packets for flushing to file.
     * 
     * Ensures the returned list starts with a keyframe for valid MP4 generation.
     * NOTE: Packets are NOT removed from buffer - they will be recycled naturally
     * when they fall out of the time window.
     * 
     * @return List of packets starting with keyframe
     */
    public synchronized List<Packet> getPacketsForFlush() {
        List<Packet> result = new ArrayList<>();
        boolean foundKeyFrame = false;
        
        for (Packet p : buffer) {
            if (p.isKeyFrame) {
                foundKeyFrame = true;
            }
            if (foundKeyFrame) {
                result.add(p);
            }
        }
        
        logger.info(String.format("Flushing %d packets (%.1f sec, %d keyframes)", 
                result.size(), 
                result.isEmpty() ? 0 : (result.get(result.size()-1).info.presentationTimeUs - 
                                       result.get(0).info.presentationTimeUs) / 1_000_000.0,
                (int) result.stream().filter(p -> p.isKeyFrame).count()));
        
        return result;
    }
    
    /**
     * Clears the buffer and recycles all packets back to pool.
     */
    public synchronized void clear() {
        // Recycle EVERYTHING back to pool
        while (!buffer.isEmpty()) {
            recyclePacket(buffer.poll());
        }
        currentDurationUs = 0;
        keyframeCount = 0;
        logger.info("Buffer cleared (packets recycled to pool)");
    }
    
    /**
     * Gets current buffer statistics.
     * 
     * @return Human-readable stats string
     */
    public synchronized String getStats() {
        return String.format("Buffer: %d packets, %.1f sec, %d keyframes, pool=%d free", 
                buffer.size(), 
                currentDurationUs / 1_000_000.0,
                keyframeCount,
                pool.size());
    }
    
    /**
     * Gets the number of packets in buffer.
     * 
     * @return Packet count
     */
    public synchronized int size() {
        return buffer.size();
    }
    
    /**
     * Gets the current buffer duration in seconds.
     * 
     * @return Duration in seconds
     */
    public synchronized double getDurationSeconds() {
        return currentDurationUs / 1_000_000.0;
    }
    
    /**
     * Gets the maximum buffer duration in microseconds.
     * Used to check if buffer needs to be recreated on settings change.
     * 
     * @return Max duration in microseconds
     */
    public long getMaxDurationUs() {
        return maxDurationUs;
    }
    
    /**
     * Gets the number of free packets in the pool.
     * 
     * @return Free packet count
     */
    public int getPoolFreeCount() {
        return pool.size();
    }
}
