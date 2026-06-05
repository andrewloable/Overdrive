package com.overdrive.app.config

/**
 * Application-level configuration.
 */
data class AppConfig(
    val deviceId: String = "unknown",
    val outputDir: String = "/sdcard/DCIM/BYDCam",
    val streamMode: StreamMode = StreamMode.PRIVATE,
    val sentryModeEnabled: Boolean = false,
    val locationSidecarEnabled: Boolean = false
)

enum class StreamMode {
    PRIVATE,  // Local MJPEG only
    PUBLIC    // Reports to VPS
}
