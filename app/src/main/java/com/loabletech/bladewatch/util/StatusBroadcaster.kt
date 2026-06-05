package com.overdrive.app.util

import android.content.Context
import android.content.Intent
import com.overdrive.app.logging.LogManager

/**
 * Broadcasts status updates to other components.
 * 
 * Extracted from RecordingService for better separation of concerns.
 */
class StatusBroadcaster(private val context: Context) {
    
    companion object {
        private const val TAG = "StatusBroadcaster"
        
        // Broadcast actions
        const val ACTION_STATUS_UPDATE = "com.overdrive.app.STATUS_UPDATE"
        const val ACTION_RECORDING_STARTED = "com.overdrive.app.RECORDING_STARTED"
        const val ACTION_RECORDING_STOPPED = "com.overdrive.app.RECORDING_STOPPED"
        const val ACTION_TUNNEL_CONNECTED = "com.overdrive.app.TUNNEL_CONNECTED"
        const val ACTION_TUNNEL_DISCONNECTED = "com.overdrive.app.TUNNEL_DISCONNECTED"
        const val ACTION_DAEMON_STARTED = "com.overdrive.app.DAEMON_STARTED"
        const val ACTION_DAEMON_STOPPED = "com.overdrive.app.DAEMON_STOPPED"
        const val ACTION_ERROR = "com.overdrive.app.ERROR"
        
        // Extras
        const val EXTRA_STATUS = "status"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_CAMERAS = "cameras"
        const val EXTRA_TUNNEL_URL = "tunnel_url"
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
     * Broadcast tunnel connected.
     */
    fun broadcastTunnelConnected(tunnelUrl: String) {
        val intent = Intent(ACTION_TUNNEL_CONNECTED).apply {
            putExtra(EXTRA_TUNNEL_URL, tunnelUrl)
        }
        context.sendBroadcast(intent)
        logManager.info(TAG, "Tunnel connected broadcast: $tunnelUrl")
    }
    
    /**
     * Broadcast tunnel disconnected.
     */
    fun broadcastTunnelDisconnected() {
        context.sendBroadcast(Intent(ACTION_TUNNEL_DISCONNECTED))
        logManager.info(TAG, "Tunnel disconnected broadcast")
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
