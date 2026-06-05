package com.overdrive.app.ui.daemon

import com.overdrive.app.launcher.AdbDaemonLauncher
import com.overdrive.app.ui.model.DaemonStatus
import com.overdrive.app.ui.model.DaemonType

/**
 * Controller for the Sentry Daemon (SentryDaemon.java).
 * 
 * Kill methods:
 * 1. Control socket (port 19876) - clean shutdown
 * 2. PID file (/data/local/tmp/sentry_daemon.pid)
 * 3. pkill -f SentryDaemon - matches Java class name
 */
class SentryDaemonController(
    private val adbLauncher: AdbDaemonLauncher
) : DaemonController {
    
    override val type = DaemonType.SENTRY_DAEMON
    
    private fun getKillCommand(): String {
        return "echo 'STOP' | nc -w 1 127.0.0.1 19879 2>/dev/null; " +  // Port 19879 for SentryDaemon
               "if [ -f /data/local/tmp/sentry_daemon.pid ]; then " +
               "kill -9 \$(cat /data/local/tmp/sentry_daemon.pid) 2>/dev/null; " +
               "rm -f /data/local/tmp/sentry_daemon.pid; fi; " +
               "pkill -9 -f 'SentryDaemon' 2>/dev/null; " +
               "pkill -9 -f 'AccSentryDaemon' 2>/dev/null; " +
               "echo done"
    }
    
    private fun getCheckCommand(): String {
        return "pgrep -f 'SentryDaemon|AccSentryDaemon' | head -1"
    }
    
    override fun start(callback: DaemonCallback) {
        callback.onStatusChanged(DaemonStatus.STARTING, "Starting sentry daemon...")
        
        adbLauncher.launchSentryDaemon(object : AdbDaemonLauncher.LaunchCallback {
            override fun onLog(message: String) {
                callback.onStatusChanged(DaemonStatus.STARTING, message)
            }
            
            override fun onLaunched() {
                callback.onStatusChanged(DaemonStatus.RUNNING, "Sentry daemon running")
            }
            
            override fun onError(error: String) {
                callback.onError(error)
            }
        })
    }
    
    override fun stop(callback: DaemonCallback) {
        callback.onStatusChanged(DaemonStatus.STOPPING, "Stopping sentry daemon...")
        
        adbLauncher.executeShellCommand(
            getKillCommand(),
            object : AdbDaemonLauncher.LaunchCallback {
                override fun onLog(message: String) {}
                override fun onLaunched() {
                    callback.onStatusChanged(DaemonStatus.STOPPED, "Sentry daemon stopped")
                }
                override fun onError(error: String) {
                    callback.onStatusChanged(DaemonStatus.STOPPED, "Sentry daemon stopped")
                }
            }
        )
    }
    
    override fun isRunning(callback: (Boolean) -> Unit) {
        adbLauncher.executeShellCommand(
            getCheckCommand(),
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
            getKillCommand(),
            object : AdbDaemonLauncher.LaunchCallback {
                override fun onLog(message: String) {}
                override fun onLaunched() {}
                override fun onError(error: String) {}
            }
        )
    }
}
