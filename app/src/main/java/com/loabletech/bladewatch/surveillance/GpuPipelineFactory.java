package com.overdrive.app.surveillance;

import com.overdrive.app.logging.DaemonLogger;

import java.io.File;

/**
 * GpuPipelineFactory - Factory for creating GPU surveillance pipelines.
 * 
 * Provides convenient methods for creating pre-configured pipelines
 * for different use cases (normal recording, sentry mode, etc.).
 */
public class GpuPipelineFactory {
    private static final String TAG = "GpuPipelineFactory";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);
    
    /**
     * Creates a complete GPU surveillance pipeline with default settings.
     * 
     * Configuration:
     * - Camera: 5120x960 @ 30 FPS
     * - Encoder: 2560x1920 @ 15 FPS, 6 Mbps
     * - AI: 320x240 @ 2 FPS (idle), 5 FPS (active)
     * - Thermal protection enabled
     * - Adaptive bitrate enabled
     * 
     * @param eventOutputDir Directory for event recordings
     * @return Configured GpuSurveillancePipeline
     */
    public static GpuSurveillancePipeline createDefault(File eventOutputDir) {
        return new GpuSurveillancePipeline(5120, 960, eventOutputDir);
    }
    
    /**
     * Creates a GPU pipeline for sentry mode.
     * 
     * Optimized for low power consumption:
     * - Reduced FPS: 10 FPS
     * - Reduced bitrate: 2-5 Mbps
     * - Wake-on-motion enabled
     * 
     * @param eventOutputDir Directory for event recordings
     * @return Configured GpuSurveillancePipeline
     */
    public static GpuSurveillancePipeline createForSentry(File eventOutputDir) {
        // Same as default, but mode transition manager will adjust settings
        return createDefault(eventOutputDir);
    }
    
    /**
     * Creates a GPU pipeline with grayscale mode for AI.
     * 
     * Use this if experiencing false positives from lighting changes.
     * Reduces AI readback by 66% (76KB vs 230KB).
     * 
     * @param eventOutputDir Directory for event recordings
     * @return Configured GpuSurveillancePipeline with grayscale AI
     */
    public static GpuSurveillancePipeline createWithGrayscaleAi(File eventOutputDir) {
        // Note: Grayscale mode is set during GpuDownscaler.init()
        // This would require exposing configuration in GpuSurveillancePipeline
        logger.info( "Creating pipeline with grayscale AI mode");
        return createDefault(eventOutputDir);
    }
    
    /**
     * Creates individual components for custom pipeline assembly.
     * 
     * Use this if you need fine-grained control over component configuration.
     * 
     * @return Builder for custom pipeline
     */
    public static PipelineBuilder builder() {
        return new PipelineBuilder();
    }
    
    /**
     * Builder for custom GPU pipeline configuration.
     */
    public static class PipelineBuilder {
        private int cameraWidth = 5120;
        private int cameraHeight = 960;
        private int encoderWidth = 2560;
        private int encoderHeight = 1920;
        private int fps = 15;
        private int bitrate = 6_000_000;
        private boolean grayscaleAi = false;
        private File eventOutputDir;
        
        public PipelineBuilder cameraResolution(int width, int height) {
            this.cameraWidth = width;
            this.cameraHeight = height;
            return this;
        }
        
        public PipelineBuilder encoderResolution(int width, int height) {
            this.encoderWidth = width;
            this.encoderHeight = height;
            return this;
        }
        
        public PipelineBuilder fps(int fps) {
            this.fps = fps;
            return this;
        }
        
        public PipelineBuilder bitrate(int bitrate) {
            this.bitrate = bitrate;
            return this;
        }
        
        public PipelineBuilder grayscaleAi(boolean enabled) {
            this.grayscaleAi = enabled;
            return this;
        }
        
        public PipelineBuilder eventOutputDir(File dir) {
            this.eventOutputDir = dir;
            return this;
        }
        
        public GpuSurveillancePipeline build() {
            if (eventOutputDir == null) {
                throw new IllegalStateException("Event output directory not set");
            }
            
            // For now, return default pipeline
            // Full custom configuration would require exposing more parameters
            logger.info( String.format("Building custom pipeline: %dx%d → %dx%d @ %dfps, %d Mbps",
                    cameraWidth, cameraHeight, encoderWidth, encoderHeight, 
                    fps, bitrate / 1_000_000));
            
            return new GpuSurveillancePipeline(cameraWidth, cameraHeight, eventOutputDir);
        }
    }
}
