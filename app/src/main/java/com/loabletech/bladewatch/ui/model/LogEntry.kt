package com.overdrive.app.ui.model

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A single log entry for display in the logs panel.
 */
data class LogEntry(
    val timestamp: Long = System.currentTimeMillis(),
    val tag: String,
    val message: String,
    val level: LogLevel = LogLevel.INFO
) {
    val formattedTime: String
        get() = TIME_FORMAT.format(Date(timestamp))
    
    companion object {
        private val TIME_FORMAT = SimpleDateFormat("HH:mm:ss", Locale.US)
    }
}
