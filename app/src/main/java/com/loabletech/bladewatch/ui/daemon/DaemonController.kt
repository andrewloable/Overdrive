package com.overdrive.app.ui.daemon

import com.overdrive.app.ui.model.DaemonType

/**
 * Interface for controlling daemon processes.
 * Each daemon type has its own implementation.
 */
interface DaemonController {
    /**
     * The type of daemon this controller manages.
     */
    val type: DaemonType
    
    /**
     * Start the daemon.
     */
    fun start(callback: DaemonCallback)
    
    /**
     * Stop the daemon and clean up all resources.
     * This should:
     * - Kill the main daemon process
     * - Kill all child processes
     * - Restore any modified system settings
     * - Clear temporary files
     */
    fun stop(callback: DaemonCallback)
    
    /**
     * Check if the daemon is currently running.
     */
    fun isRunning(callback: (Boolean) -> Unit)
    
    /**
     * Perform full cleanup without callbacks.
     * Called during app shutdown or emergency cleanup.
     */
    fun cleanup()
}
