package com.loabletech.bladewatch.daemon.management

import com.loabletech.bladewatch.config.DaemonType

/**
 * Callback interface for daemon lifecycle events.
 */
interface DaemonCallback {
    fun onStarted(type: DaemonType)
    fun onStopped(type: DaemonType)
    fun onError(type: DaemonType, error: String)
    fun onLog(type: DaemonType, message: String)
}
