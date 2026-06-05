package com.overdrive.app.ui.daemon

import com.overdrive.app.launcher.AdbDaemonLauncher
import com.overdrive.app.ui.model.DaemonStatus
import com.overdrive.app.ui.model.DaemonType

/**
 * Controller for TelegramBotDaemon - handles Telegram bot polling and notifications.
 */
class TelegramDaemonController(
    private val adbLauncher: AdbDaemonLauncher
) : DaemonController {
    
    override val type = DaemonType.TELEGRAM_DAEMON
    
    companion object {
        private const val PROCESS_NAME = "telegram_bot_daemon"
    }
    
    override fun start(callback: DaemonCallback) {
        callback.onStatusChanged(DaemonStatus.STARTING, "Launching TelegramBotDaemon...")
        
        adbLauncher.launchTelegramDaemon(object : AdbDaemonLauncher.LaunchCallback {
            override fun onLog(message: String) {
                // Optional logging
            }
            
            override fun onLaunched() {
                callback.onStatusChanged(DaemonStatus.RUNNING, "Running")
            }
            
            override fun onError(error: String) {
                callback.onError(error)
            }
        })
    }
    
    override fun stop(callback: DaemonCallback) {
        callback.onStatusChanged(DaemonStatus.STOPPING, "Stopping...")
        
        adbLauncher.stopTelegramDaemon(object : AdbDaemonLauncher.LaunchCallback {
            override fun onLog(message: String) {}
            
            override fun onLaunched() {
                callback.onStatusChanged(DaemonStatus.STOPPED, "Stopped")
            }
            
            override fun onError(error: String) {
                callback.onStatusChanged(DaemonStatus.STOPPED, "Stopped")
            }
        })
    }
    
    override fun isRunning(callback: (Boolean) -> Unit) {
        adbLauncher.isDaemonRunning(PROCESS_NAME, callback)
    }
    
    override fun cleanup() {
        adbLauncher.stopTelegramDaemon(object : AdbDaemonLauncher.LaunchCallback {
            override fun onLog(message: String) {}
            override fun onLaunched() {}
            override fun onError(error: String) {}
        })
    }
}
