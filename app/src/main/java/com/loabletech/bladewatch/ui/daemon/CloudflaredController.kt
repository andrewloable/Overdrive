package com.overdrive.app.ui.daemon

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.overdrive.app.launcher.AdbDaemonLauncher
import com.overdrive.app.ui.model.DaemonStatus
import com.overdrive.app.ui.model.DaemonType
import com.overdrive.app.ui.util.PreferencesManager

/**
 * Controller for the Cloudflared Tunnel.
 */
class CloudflaredController(
    private val adbLauncher: AdbDaemonLauncher
) : DaemonController {
    
    override val type = DaemonType.CLOUDFLARED_TUNNEL
    
    private val _tunnelUrl = MutableLiveData<String?>()
    val tunnelUrl: LiveData<String?> = _tunnelUrl
    
    override fun start(callback: DaemonCallback) {
        callback.onStatusChanged(DaemonStatus.STARTING, "Starting cloudflared tunnel...")
        
        adbLauncher.launchTunnel(object : AdbDaemonLauncher.TunnelCallback {
            override fun onLog(message: String) {
                callback.onStatusChanged(DaemonStatus.STARTING, message)
            }
            
            override fun onTunnelUrl(url: String) {
                _tunnelUrl.postValue(url)
                PreferencesManager.setLastTunnelUrl(url)
                callback.onStatusChanged(DaemonStatus.RUNNING, url)
            }
            
            override fun onError(error: String) {
                callback.onError(error)
            }
        })
    }
    
    override fun stop(callback: DaemonCallback) {
        callback.onStatusChanged(DaemonStatus.STOPPING, "Stopping cloudflared tunnel...")
        
        adbLauncher.stopTunnel(object : AdbDaemonLauncher.LaunchCallback {
            override fun onLog(message: String) {
                callback.onStatusChanged(DaemonStatus.STOPPING, message)
            }
            
            override fun onLaunched() {
                _tunnelUrl.postValue(null)
                callback.onStatusChanged(DaemonStatus.STOPPED, "Tunnel stopped")
            }
            
            override fun onError(error: String) {
                _tunnelUrl.postValue(null)
                callback.onError(error)
            }
        })
    }
    
    override fun isRunning(callback: (Boolean) -> Unit) {
        adbLauncher.isTunnelRunning(callback)
    }
    
    /**
     * Refresh the tunnel URL from log file (useful when daemon is already running).
     * Also tries to get the last saved URL from preferences if log doesn't have it.
     */
    fun refreshTunnelUrl(callback: ((String?) -> Unit)? = null) {
        adbLauncher.getTunnelUrl { url ->
            if (url != null) {
                _tunnelUrl.postValue(url)
                PreferencesManager.setLastTunnelUrl(url)
                callback?.invoke(url)
            } else {
                // Try to get last saved URL from preferences
                val lastUrl = PreferencesManager.getLastTunnelUrl()
                if (!lastUrl.isNullOrEmpty()) {
                    _tunnelUrl.postValue(lastUrl)
                    callback?.invoke(lastUrl)
                } else {
                    callback?.invoke(null)
                }
            }
        }
    }
    
    override fun cleanup() {
        // Use pkill directly - simpler and more reliable
        adbLauncher.executeShellCommand(
            "pkill -9 -f 'cloudflared'; echo done",
            object : AdbDaemonLauncher.LaunchCallback {
                override fun onLog(message: String) {}
                override fun onLaunched() {}
                override fun onError(error: String) {}
            }
        )
        _tunnelUrl.postValue(null)
    }
    
    /**
     * Get the current tunnel URL if available.
     */
    fun getTunnelUrl(): String? = _tunnelUrl.value
}
