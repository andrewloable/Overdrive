package com.overdrive.app.ui.daemon

import com.overdrive.app.launcher.AdbDaemonLauncher
import com.overdrive.app.ui.model.DaemonStatus
import com.overdrive.app.ui.model.DaemonType

/**
 * Controller for the Sing-box Proxy.
 */
class SingboxController(
    private val adbLauncher: AdbDaemonLauncher
) : DaemonController {
    
    override val type = DaemonType.SINGBOX_PROXY
    
    override fun start(callback: DaemonCallback) {
        callback.onStatusChanged(DaemonStatus.STARTING, "Starting sing-box proxy...")
        
        adbLauncher.launchProxyDaemon(object : AdbDaemonLauncher.LaunchCallback {
            override fun onLog(message: String) {
                callback.onStatusChanged(DaemonStatus.STARTING, message)
            }
            
            override fun onLaunched() {
                callback.onStatusChanged(DaemonStatus.RUNNING, "Sing-box proxy running on port 8119")
            }
            
            override fun onError(error: String) {
                callback.onError(error)
            }
        })
    }
    
    override fun stop(callback: DaemonCallback) {
        callback.onStatusChanged(DaemonStatus.STOPPING, "Stopping sing-box proxy...")
        
        adbLauncher.executeShellCommand(
            "killall -9 sing-box 2>/dev/null; echo done",
            object : AdbDaemonLauncher.LaunchCallback {
                override fun onLog(message: String) {}
                override fun onLaunched() {
                    restoreProxySettings()
                    callback.onStatusChanged(DaemonStatus.STOPPED, "Sing-box proxy stopped")
                }
                override fun onError(error: String) {
                    restoreProxySettings()
                    callback.onStatusChanged(DaemonStatus.STOPPED, "Sing-box proxy stopped")
                }
            }
        )
    }
    
    override fun isRunning(callback: (Boolean) -> Unit) {
        adbLauncher.executeShellCommand(
            "ps -A | grep sing-box | grep -v grep",
            object : AdbDaemonLauncher.LaunchCallback {
                override fun onLog(message: String) {
                    callback(message.trim().isNotEmpty())
                }
                override fun onLaunched() {}
                override fun onError(error: String) {
                    callback(false)
                }
            }
        )
    }
    
    override fun cleanup() {
        adbLauncher.executeShellCommand(
            "killall -9 sing-box 2>/dev/null; echo done",
            object : AdbDaemonLauncher.LaunchCallback {
                override fun onLog(message: String) {}
                override fun onLaunched() {}
                override fun onError(error: String) {}
            }
        )
        restoreProxySettings()
    }
    
    private fun restoreProxySettings() {
        // Clear any system proxy settings that might have been set
        adbLauncher.executeShellCommand(
            "settings put global http_proxy :0 2>/dev/null || true",
            object : AdbDaemonLauncher.LaunchCallback {
                override fun onLog(message: String) {}
                override fun onLaunched() {}
                override fun onError(error: String) {}
            }
        )
    }
}
