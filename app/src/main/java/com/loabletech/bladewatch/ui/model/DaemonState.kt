package com.overdrive.app.ui.model

/**
 * Information about a subprocess running under a daemon.
 */
data class SubprocessInfo(
    val name: String,
    val pid: Int,
    val uptime: String,
    val startTimeMillis: Long = System.currentTimeMillis() - parseUptimeToMillis(uptime)
)

/**
 * State of a daemon including status and metadata.
 */
data class DaemonState(
    val type: DaemonType,
    val status: DaemonStatus,
    val statusText: String = "",
    val uptime: String? = null,
    val startTimeMillis: Long? = null, // When the daemon started (for live uptime)
    val subprocesses: List<SubprocessInfo> = emptyList(),
    val lastUpdated: Long = System.currentTimeMillis(),
    val needsConfiguration: Boolean = false, // True if daemon requires setup (e.g., missing token)
    val configurationMessage: String? = null // Message to show when configuration is needed
) {
    companion object {
        fun stopped(type: DaemonType) = DaemonState(
            type = type,
            status = DaemonStatus.STOPPED,
            statusText = "Not running"
        )
        
        fun running(type: DaemonType, statusText: String = "Running", uptime: String? = null) = DaemonState(
            type = type,
            status = DaemonStatus.RUNNING,
            statusText = statusText,
            uptime = uptime,
            startTimeMillis = uptime?.let { System.currentTimeMillis() - parseUptimeToMillis(it) }
        )
        
        fun error(type: DaemonType, errorText: String) = DaemonState(
            type = type,
            status = DaemonStatus.ERROR,
            statusText = errorText
        )
        
        fun needsConfig(type: DaemonType, message: String) = DaemonState(
            type = type,
            status = DaemonStatus.STOPPED,
            statusText = message,
            needsConfiguration = true,
            configurationMessage = message
        )
    }
}

/**
 * Parse uptime string like "1h 23m 45s" or "5m 30s" to milliseconds.
 */
fun parseUptimeToMillis(uptime: String): Long {
    var totalMs = 0L
    val regex = Regex("(\\d+)([dhms])")
    regex.findAll(uptime.lowercase()).forEach { match ->
        val value = match.groupValues[1].toLongOrNull() ?: 0
        val unit = match.groupValues[2]
        totalMs += when (unit) {
            "d" -> value * 24 * 60 * 60 * 1000
            "h" -> value * 60 * 60 * 1000
            "m" -> value * 60 * 1000
            "s" -> value * 1000
            else -> 0
        }
    }
    return totalMs
}

/**
 * Format milliseconds to uptime string like "1h 23m 45s".
 */
fun formatUptimeFromMillis(millis: Long): String {
    val seconds = millis / 1000
    val days = seconds / 86400
    val hours = (seconds % 86400) / 3600
    val minutes = (seconds % 3600) / 60
    val secs = seconds % 60
    
    return buildString {
        if (days > 0) append("${days}d ")
        if (hours > 0 || days > 0) append("${hours}h ")
        if (minutes > 0 || hours > 0 || days > 0) append("${minutes}m ")
        append("${secs}s")
    }.trim()
}
