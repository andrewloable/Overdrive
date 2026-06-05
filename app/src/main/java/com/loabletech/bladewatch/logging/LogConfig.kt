package com.overdrive.app.logging

import android.content.Context
import java.io.File

/**
 * Configuration for LogManager.
 * 
 * App logs go to app's cache directory (accessible by app).
 * Daemon logs go to /data/local/tmp (accessible by ADB shell).
 * 
 * @param logDir Directory where log files are stored
 * @param retentionHours How long to keep log files before cleanup (hours)
 * @param cleanupIntervalHours How often to run cleanup checks (hours)
 * @param maxFileSizeMB Maximum size of a single log file before rotation (MB)
 * @param rotationCount Number of rotated log files to keep
 * @param enableConsoleLog Whether to log to Android Log (logcat)
 * @param enableFileLog Whether to write logs to file
 */
data class LogConfig(
    val logDir: String = "",
    val retentionHours: Int = 24,
    val cleanupIntervalHours: Int = 6,
    val maxFileSizeMB: Int = 5,
    val rotationCount: Int = 3,
    val enableConsoleLog: Boolean = true,
    val enableFileLog: Boolean = false
) {
    companion object {
        private var appLogDir: String? = null
        
        /**
         * Initialize with app context to get proper log directory.
         * Call this in Application.onCreate() before using LogManager.
         */
        fun init(context: Context) {
            val logsDir = File(context.cacheDir, "logs")
            if (!logsDir.exists()) {
                logsDir.mkdirs()
            }
            appLogDir = logsDir.absolutePath
        }
        
        /**
         * Get the app's log directory.
         */
        fun getAppLogDir(): String? = appLogDir
        
        /**
         * Default config with file logging enabled (requires init() first).
         */
        fun default(): LogConfig {
            val logDir = appLogDir
            return if (logDir != null) {
                LogConfig(
                    logDir = logDir,
                    enableConsoleLog = true,
                    enableFileLog = true
                )
            } else {
                // Fallback to console only if not initialized
                consoleOnly()
            }
        }
        
        /**
         * Console-only logging (no file writes).
         */
        fun consoleOnly() = LogConfig(
            logDir = "",
            enableConsoleLog = true,
            enableFileLog = false
        )
        
        /**
         * Daemon log directory (for processes running via ADB shell).
         * These have shell permissions and can write to /data/local/tmp.
         */
        const val DAEMON_LOG_DIR = "/data/local/tmp"
    }
    
    /**
     * Validate configuration values.
     */
    fun isValid(): Boolean {
        if (enableFileLog && logDir.isEmpty()) return false
        return retentionHours >= 1 &&
               cleanupIntervalHours >= 1 &&
               maxFileSizeMB >= 1 &&
               rotationCount >= 1
    }
}
