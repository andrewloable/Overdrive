package com.overdrive.app.daemon.management

import com.overdrive.app.config.DaemonType

/**
 * Represents the current state of a daemon.
 */
data class DaemonState(
    val type: DaemonType,
    val running: Boolean,
    val pid: Int? = null,
    val startTime: Long? = null,
    val autoStart: Boolean = false
)
