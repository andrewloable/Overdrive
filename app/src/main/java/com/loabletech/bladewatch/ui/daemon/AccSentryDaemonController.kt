package com.overdrive.app.ui.daemon

import com.overdrive.app.launcher.AdbDaemonLauncher
import com.overdrive.app.ui.model.DaemonStatus
import com.overdrive.app.ui.model.DaemonType

/**
 * Controller for AccSentryDaemon - handles ACC monitoring and screen control.
 * 
 * This daemon MUST run as UID 2000 (shell) for screen control to work.
 * It's separate from SentryDaemon (UID 1000) which handles system whitelisting.
 */
class AccSentryDaemonController(
    private val adbLauncher: AdbDaemonLauncher
) : DaemonController {
    
    override val type = DaemonType.ACC_SENTRY_DAEMON
    
    companion object {
        private const val PROCESS_NAME = "acc_sentry_daemon"
    }
    
    override fun start(callback: DaemonCallback) {
        callback.onStatusChanged(DaemonStatus.STARTING, "Launching AccSentryDaemon (UID 2000)...")
        
        adbLauncher.launchAccSentryDaemon(
            onSuccess = {
                callback.onStatusChanged(DaemonStatus.RUNNING, "Running as UID 2000")
            },
            onError = { error ->
                callback.onError(error)
            }
        )
    }
    
    override fun stop(callback: DaemonCallback) {
        callback.onStatusChanged(DaemonStatus.STOPPING, "Stopping...")
        
        // Use pkill -9 -f 'acc_sentry' to kill BOTH daemon AND watchdog script
        adbLauncher.executeShellCommand(
            "pkill -9 -f 'acc_sentry'; " +
            "rm -f /data/local/tmp/acc_sentry_daemon.lock 2>/dev/null; " +
            "rm -f /data/local/tmp/start_acc_sentry.sh 2>/dev/null; " +
            "echo done",
            object : AdbDaemonLauncher.LaunchCallback {
                override fun onLog(message: String) {}
                override fun onLaunched() {
                    callback.onStatusChanged(DaemonStatus.STOPPED, "Stopped")
                }
                override fun onError(error: String) {
                    // pkill returns error if no process - that's fine
                    callback.onStatusChanged(DaemonStatus.STOPPED, "Stopped")
                }
            }
        )
    }
    
    override fun isRunning(callback: (Boolean) -> Unit) {
        adbLauncher.executeShellCommand(
            "ps -A | grep $PROCESS_NAME | grep -v grep",
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
        // Use pkill -9 -f 'acc_sentry' to kill BOTH daemon AND watchdog script
        adbLauncher.executeShellCommand(
            "pkill -9 -f 'acc_sentry'; " +
            "rm -f /data/local/tmp/acc_sentry_daemon.lock 2>/dev/null; " +
            "rm -f /data/local/tmp/start_acc_sentry.sh 2>/dev/null; " +
            "echo done",
            object : AdbDaemonLauncher.LaunchCallback {
                override fun onLog(message: String) {}
                override fun onLaunched() {}
                override fun onError(error: String) {}
            }
        )
    }
}
