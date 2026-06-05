package com.overdrive.app.service.components

import android.content.Context
import com.overdrive.app.launcher.AdbDaemonLauncher
import com.overdrive.app.logging.LogManager

/**
 * Coordinates tunnel lifecycle and URL management.
 * 
 * Extracted from RecordingService for better separation of concerns.
 */
class TunnelCoordinator(private val context: Context) {
    
    companion object {
        private const val TAG = "TunnelCoordinator"
    }
    
    private val logManager = LogManager.getInstance()
    private val adbLauncher = AdbDaemonLauncher(context)
    
    private var tunnelUrl: String? = null
    private var isTunnelStarting = false
    
    interface TunnelCallback {
        fun onTunnelStarted(url: String)
        fun onTunnelStopped()
        fun onTunnelError(error: String)
        fun onLog(message: String)
    }
    
    /**
     * Start the tunnel.
     */
    fun startTunnel(callback: TunnelCallback) {
        if (isTunnelStarting) {
            callback.onLog("Tunnel already starting...")
            return
        }
        
        if (tunnelUrl != null) {
            callback.onLog("Tunnel already running: $tunnelUrl")
            callback.onTunnelStarted(tunnelUrl!!)
            return
        }
        
        isTunnelStarting = true
        logManager.info(TAG, "Starting tunnel...")
        
        adbLauncher.launchTunnel(object : AdbDaemonLauncher.TunnelCallback {
            override fun onLog(message: String) {
                logManager.debug(TAG, message)
                callback.onLog(message)
            }
            
            override fun onTunnelUrl(url: String) {
                isTunnelStarting = false
                tunnelUrl = url
                logManager.info(TAG, "Tunnel established: $url")
                callback.onTunnelStarted(url)
            }
            
            override fun onError(error: String) {
                isTunnelStarting = false
                logManager.error(TAG, "Tunnel error: $error")
                callback.onTunnelError(error)
            }
        })
    }
    
    /**
     * Stop the tunnel.
     */
    fun stopTunnel(callback: TunnelCallback) {
        logManager.info(TAG, "Stopping tunnel...")
        
        adbLauncher.stopTunnel(object : AdbDaemonLauncher.LaunchCallback {
            override fun onLog(message: String) {
                logManager.debug(TAG, message)
                callback.onLog(message)
            }
            
            override fun onLaunched() {
                tunnelUrl = null
                logManager.info(TAG, "Tunnel stopped")
                callback.onTunnelStopped()
            }
            
            override fun onError(error: String) {
                logManager.error(TAG, "Error stopping tunnel: $error")
                callback.onTunnelError(error)
            }
        })
    }
    
    /**
     * Check if tunnel is running.
     */
    fun isTunnelRunning(callback: (Boolean) -> Unit) {
        adbLauncher.isTunnelRunning(callback)
    }
    
    /**
     * Get current tunnel URL.
     */
    fun getTunnelUrl(): String? = tunnelUrl
    
    /**
     * Set tunnel URL (for restoring state).
     */
    fun setTunnelUrl(url: String?) {
        tunnelUrl = url
    }
    
    /**
     * Close resources.
     */
    fun close() {
        adbLauncher.closePersistentConnection()
    }
}
