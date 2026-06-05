package com.loabletech.bladewatch.daemon.management

import com.loabletech.bladewatch.config.DaemonType

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
