package com.overdrive.app.surveillance;

import android.animation.ValueAnimator;
import com.overdrive.app.logging.DaemonLogger;

/**
 * AdaptiveBitrateController - Adjusts encoder bitrate based on motion score.
 * 
 * Dynamically adjusts video quality based on scene activity to optimize
 * storage usage. Static scenes use low bitrate (3 Mbps), high motion scenes
 * use high bitrate (8 Mbps).
 * 
 * Benefits:
 * - 2-3x storage savings on idle footage
 * - Maintains quality during important events
 * - Smooth transitions (500ms ramp) prevent encoder artifacts
 */
public class AdaptiveBitrateController {
    private static final String TAG = "AdaptiveBitrate";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);
    
    // Bitrate levels (in bps)
    private static final int BITRATE_LOW = 3_000_000;    // 3 Mbps - static scenes
    private static final int BITRATE_MEDIUM = 5_000_000; // 5 Mbps - moderate motion
    private static final int BITRATE_HIGH = 8_000_000;   // 8 Mbps - high motion
    
    // Motion score thresholds
    private static final float THRESHOLD_LOW = 0.10f;   // 10% motion
    private static final float THRESHOLD_HIGH = 0.50f;  // 50% motion
    
    // Ramp duration for smooth transitions
    private static final int RAMP_DURATION_MS = 500;
    
    // State
    private HardwareEventRecorderGpu encoder;
    private int currentBitrate;
    private int targetBitrate;
    private ValueAnimator bitrateAnimator;
    
    /**
     * Creates an adaptive bitrate controller.
     * 
     * @param encoder Hardware encoder to control
     * @param initialBitrate Initial bitrate in bps
     */
    public AdaptiveBitrateController(HardwareEventRecorderGpu encoder, int initialBitrate) {
        this.encoder = encoder;
        this.currentBitrate = initialBitrate;
        this.targetBitrate = initialBitrate;
    }
    
    /**
     * Updates bitrate based on motion score.
     * 
     * Call this periodically (e.g., every second) with the current motion score.
     * 
     * @param motionScore Motion score from 0.0 (static) to 1.0 (maximum motion)
     */
    public void updateBitrate(float motionScore) {
        // Determine target bitrate based on motion score
        int newTarget;
        if (motionScore < THRESHOLD_LOW) {
            newTarget = BITRATE_LOW;
        } else if (motionScore < THRESHOLD_HIGH) {
            newTarget = BITRATE_MEDIUM;
        } else {
            newTarget = BITRATE_HIGH;
        }
        
        // Only change if different from current target
        if (newTarget != targetBitrate) {
            targetBitrate = newTarget;
            rampToBitrate(newTarget);
        }
    }
    
    /**
     * Ramps bitrate smoothly to avoid encoder artifacts.
     * 
     * @param targetBitrate Target bitrate in bps
     */
    private void rampToBitrate(int targetBitrate) {
        // Cancel any ongoing animation
        if (bitrateAnimator != null && bitrateAnimator.isRunning()) {
            bitrateAnimator.cancel();
        }
        
        logger.info( String.format("Ramping bitrate: %d → %d Mbps (motion-based)",
                currentBitrate / 1_000_000, targetBitrate / 1_000_000));
        
        // Create animator for smooth transition
        bitrateAnimator = ValueAnimator.ofInt(currentBitrate, targetBitrate);
        bitrateAnimator.setDuration(RAMP_DURATION_MS);
        
        bitrateAnimator.addUpdateListener(animation -> {
            int animatedBitrate = (int) animation.getAnimatedValue();
            encoder.setBitrate(animatedBitrate);
            currentBitrate = animatedBitrate;
        });
        
        bitrateAnimator.start();
    }
    
    /**
     * Sets bitrate immediately without ramping.
     * 
     * @param bitrate Bitrate in bps
     */
    public void setImmediateBitrate(int bitrate) {
        if (bitrateAnimator != null && bitrateAnimator.isRunning()) {
            bitrateAnimator.cancel();
        }
        
        encoder.setBitrate(bitrate);
        currentBitrate = bitrate;
        targetBitrate = bitrate;
        
        logger.info( "Bitrate set immediately: " + (bitrate / 1_000_000) + " Mbps");
    }
    
    /**
     * Gets the current bitrate.
     * 
     * @return Current bitrate in bps
     */
    public int getCurrentBitrate() {
        return currentBitrate;
    }
    
    /**
     * Gets the target bitrate.
     * 
     * @return Target bitrate in bps
     */
    public int getTargetBitrate() {
        return targetBitrate;
    }
    
    /**
     * Releases resources.
     */
    public void release() {
        if (bitrateAnimator != null && bitrateAnimator.isRunning()) {
            bitrateAnimator.cancel();
        }
        bitrateAnimator = null;
    }
}
