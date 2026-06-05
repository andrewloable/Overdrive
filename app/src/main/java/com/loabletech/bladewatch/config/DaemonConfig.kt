package com.overdrive.app.config

/**
 * Daemon-specific configuration.
 */
data class DaemonConfig(
    val type: DaemonType,
    val outputDir: String? = null,
    val nativeLibDir: String? = null,
    val port: Int? = null,
    val autoStart: Boolean = false,
    val retryAttempts: Int = 3,
    val retryDelayMs: Long = 5000
) {
    fun isValid(): Boolean {
        return retryAttempts >= 0 && retryDelayMs >= 0
    }
}

enum class DaemonType {
    CAMERA,
    SENTRY,
    BYD_EVENT
}
