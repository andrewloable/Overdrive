package net.bladewatch.app.util

import android.content.Context
import android.content.Intent
import net.bladewatch.app.logging.LogManager

/**
 * Broadcasts status updates to other components.
 * 
 * Extracted from RecordingService for better separation of concerns.
 */
class StatusBroadcaster(private val context: Context) {
    
    companion object {
        private const val TAG = "StatusBroadcaster"
        
        // Broadcast actions
        const val ACTION_STATUS_UPDATE = "net.bladewatch.app.STATUS_UPDATE"
        const val ACTION_RECORDING_STARTED = "net.bladewatch.app.RECORDING_STARTED"
        const val ACTION_RECORDING_STOPPED = "net.bladewatch.app.RECORDING_STOPPED"
        const val ACTION_DAEMON_STARTED = "net.bladewatch.app.DAEMON_STARTED"
        const val ACTION_DAEMON_STOPPED = "net.bladewatch.app.DAEMON_STOPPED"
        const val ACTION_ERROR = "net.bladewatch.app.ERROR"
        
        // Extras
        const val EXTRA_STATUS = "status"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_CAMERAS = "cameras"
        const val EXTRA_ERROR = "error"
    }
    
    private val logManager = LogManager.getInstance()
    
    /**
     * Broadcast a general status update.
     */
    fun broadcastStatus(status: String, message: String? = null) {
        val intent = Intent(ACTION_STATUS_UPDATE).apply {
            putExtra(EXTRA_STATUS, status)
            message?.let { putExtra(EXTRA_MESSAGE, it) }
        }
        context.sendBroadcast(intent)
        logManager.debug(TAG, "Status broadcast: $status")
    }
    
    /**
     * Broadcast recording started.
     */
    fun broadcastRecordingStarted(cameras: IntArray) {
        val intent = Intent(ACTION_RECORDING_STARTED).apply {
            putExtra(EXTRA_CAMERAS, cameras)
        }
        context.sendBroadcast(intent)
        logManager.info(TAG, "Recording started broadcast: cameras=${cameras.toList()}")
    }
    
    /**
     * Broadcast recording stopped.
     */
    fun broadcastRecordingStopped() {
        context.sendBroadcast(Intent(ACTION_RECORDING_STOPPED))
        logManager.info(TAG, "Recording stopped broadcast")
    }
    
    /**
     * Broadcast daemon started.
     */
    fun broadcastDaemonStarted() {
        context.sendBroadcast(Intent(ACTION_DAEMON_STARTED))
        logManager.info(TAG, "Daemon started broadcast")
    }
    
    /**
     * Broadcast daemon stopped.
     */
    fun broadcastDaemonStopped() {
        context.sendBroadcast(Intent(ACTION_DAEMON_STOPPED))
        logManager.info(TAG, "Daemon stopped broadcast")
    }
    
    /**
     * Broadcast an error.
     */
    fun broadcastError(error: String) {
        val intent = Intent(ACTION_ERROR).apply {
            putExtra(EXTRA_ERROR, error)
        }
        context.sendBroadcast(intent)
        logManager.error(TAG, "Error broadcast: $error")
    }
}
