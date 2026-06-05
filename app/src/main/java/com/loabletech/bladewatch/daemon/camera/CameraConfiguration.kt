package com.overdrive.app.daemon.camera

/**
 * Camera configuration constants.
 * 
 * Extracted from CameraDaemon for better separation of concerns.
 */
object CameraConfiguration {
    
    // Server ports
    const val TCP_PORT = 19876
    const val HTTP_PORT = 8080
    
    // Directories
    const val STREAM_DIR = "/data/local/tmp/cam_stream"
    const val APP_STREAM_DIR = "/storage/emulated/0/Android/data/com.overdrive.app/files/stream"
    const val DEFAULT_OUTPUT_DIR = "/sdcard/DCIM/BYDCam"
    
    // Recording config (full quality)
    const val PANO_WIDTH = 5120
    const val PANO_HEIGHT = 960
    const val VIEW_WIDTH = 1280
    const val VIEW_HEIGHT = 960
    const val FRAME_RATE = 25
    const val BITRATE = 4_000_000
    const val KEYFRAME_INTERVAL = 2
    const val SEGMENT_DURATION_MS = 2 * 60 * 1000L
    
    // Streaming config (SIM-optimized)
    const val STREAM_WIDTH = 640
    const val STREAM_HEIGHT = 480
    const val STREAM_JPEG_QUALITY = 40
    const val STREAM_INTERVAL_MS = 100L
    
    // Stream modes
    const val STREAM_MODE_PRIVATE = "private"  // Local MJPEG only
    const val STREAM_MODE_PUBLIC = "public"    // Public access via tunnel
    
    // VPS configuration - removed for open source release
}
