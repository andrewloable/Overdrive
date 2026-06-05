package com.overdrive.app.ui.daemon

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.overdrive.app.launcher.AdbDaemonLauncher
import com.overdrive.app.ui.model.DaemonStatus
import com.overdrive.app.ui.model.DaemonType
import android.content.Context
import com.overdrive.app.launcher.AdbShellExecutor
import com.overdrive.app.launcher.TailscaleLauncher
import com.overdrive.app.logging.LogManager
import com.overdrive.app.mqtt.ProxyHelper

/**
 * Controller for the Tailscale Tunnel.
 *
 */
class TailscaleController(
    private val context: Context,
    private val adbLauncher: AdbDaemonLauncher
) : DaemonController {
    
    override val type = DaemonType.TAILSCALE_TUNNEL

    private val _tunnelUrl = MutableLiveData<String?>()
    val tunnelUrl: LiveData<String?> = _tunnelUrl

    // Lazy init tailscale launcher
    private val tailscaleLauncher by lazy {
        TailscaleLauncher(
            context,
            AdbShellExecutor(context),
            LogManager.getInstance()
        )
    }

    override fun start(callback: DaemonCallback) {
        callback.onStatusChanged(DaemonStatus.STARTING, "Starting tailscale daemon...")
        ProxyHelper.invalidateCache()

        tailscaleLauncher.launchTailscale(object : TailscaleLauncher.TailscaleCallback {
            override fun onLog(message: String) = callback.onStatusChanged(DaemonStatus.STARTING, message)

            override fun onTunnelUrl(url: String?) {
                _tunnelUrl.postValue(url)
                ProxyHelper.invalidateCache()
                callback.onStatusChanged(DaemonStatus.RUNNING, url ?: "")
            }

            override fun onError(error: String) = callback.onError(error)
        })
    }

    override fun stop(callback: DaemonCallback) {
        callback.onStatusChanged(DaemonStatus.STOPPING, "Stopping tailscale daemon...")

        tailscaleLauncher.stopTunnel(object : TailscaleLauncher.TailscaleCallback {
            override fun onLog(message: String) {
                callback.onStatusChanged(DaemonStatus.STOPPING, message)
            }

            override fun onTunnelUrl(url: String?) {
                _tunnelUrl.postValue(null)
                ProxyHelper.invalidateCache()
                callback.onStatusChanged(DaemonStatus.STOPPED, "Tailscale tunnel stopped")
            }

            override fun onError(error: String) {
                _tunnelUrl.postValue(null)
                ProxyHelper.invalidateCache()
                callback.onError(error)
            }
        })
    }

    override fun isRunning(callback: (Boolean) -> Unit) {
        tailscaleLauncher.isTunnelRunning(callback)
    }

    fun refreshTunnelUrl(callback: ((String?) -> Unit)? = null) {
        tailscaleLauncher.getTunnelUrl { url ->
            _tunnelUrl.postValue(url)
            callback?.invoke(url)
        }
    }

    fun generateLoginUrl(loginUrl: (String?) -> Unit) {
        tailscaleLauncher.generateLoginUrl(loginUrl)
    }

    fun needsLogin(callback: (Boolean) -> Unit) {
        tailscaleLauncher.needsLogin(callback)
    }

    fun saveProxySettings(enabled: Boolean, callback: ((Boolean?) -> Unit)? = null) {
        tailscaleLauncher.saveProxySettings(enabled, callback)
    }

    fun isProxyEnabled(callback: ((Boolean) -> Unit)) {
        tailscaleLauncher.isProxyEnabled(callback)
    }

    override fun cleanup() {
        // Use pkill -f for more reliable process killing (matches full command line)
        adbLauncher.executeShellCommand(
            "pkill -9 -f 'tailscaled'; echo done",
            object : AdbDaemonLauncher.LaunchCallback {
                override fun onLog(message: String) {}
                override fun onLaunched() {}
                override fun onError(error: String) {}
            }
        )
        _tunnelUrl.postValue(null)
        ProxyHelper.invalidateCache()
    }

    /**
     * Disable tailscale environment (full cleanup including state).
     */
    fun disableEnvironment(callback: DaemonCallback? = null) {
        tailscaleLauncher.disableEnvironment(object : TailscaleLauncher.TailscaleCallback {
            override fun onLog(message: String) {
                callback?.onStatusChanged(DaemonStatus.STOPPING, message)
            }

            override fun onTunnelUrl(url: String?) {
                _tunnelUrl.postValue(null)
                ProxyHelper.invalidateCache()
                callback?.onStatusChanged(DaemonStatus.STOPPED, "Environment disabled")
            }

            override fun onError(error: String) {
                callback?.onError(error)
            }
        })
    }
}
