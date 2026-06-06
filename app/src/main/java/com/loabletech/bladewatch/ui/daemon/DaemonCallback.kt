package net.bladewatch.app.ui.daemon

import net.bladewatch.app.ui.model.DaemonStatus

/**
 * Callback interface for daemon operations.
 */
interface DaemonCallback {
    /**
     * Called when daemon status changes.
     */
    fun onStatusChanged(status: DaemonStatus, message: String)
    
    /**
     * Called when an error occurs.
     */
    fun onError(error: String)
}
