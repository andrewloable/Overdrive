package com.overdrive.app.launcher

import android.content.Context
import com.overdrive.app.logging.LogManager

/**
 * Facade for launching daemons, tunnels, and services via ADB shell.
 * 
 * This class delegates to specialized launchers:
 * - AdbShellExecutor: ADB connection and shell command execution
 * - DaemonLauncher: Daemon process launching (CameraDaemon, SentryDaemon, etc.)
 * - TunnelLauncher: Tunnel launching (Cloudflared)
 * - ServiceLauncher: Android service launching and permission configuration
 * 
 * Maintains backward compatibility with the original AdbDaemonLauncher API.
 */
class AdbDaemonLauncher(private val context: Context) {
    
    companion object {
        private const val TAG = "AdbDaemonLauncher"
        
        // Tunnel configuration (kept for backward compatibility)
        var tunnelType: String = "cloudflared"
        
        // Track last notified tunnel URL to avoid duplicate notifications
        @Volatile
        private var lastNotifiedTunnelUrl: String? = null
    }
    
    // Shared LogManager instance
    private val logManager = LogManager.getInstance()
    
    // Specialized launchers
    private val adbShellExecutor = AdbShellExecutor(context)
    private val daemonLauncher = DaemonLauncher(context, adbShellExecutor, logManager)
    private val tunnelLauncher = TunnelLauncher(context, adbShellExecutor, logManager)
    private val serviceLauncher = ServiceLauncher(context, adbShellExecutor, logManager)
    
    // ==================== CALLBACK INTERFACES ====================
    
    interface LaunchCallback {
        fun onLog(message: String)
        fun onLaunched()
        fun onError(error: String)
    }
    
    interface TunnelCallback {
        fun onLog(message: String)
        fun onTunnelUrl(url: String)
        fun onError(error: String)
    }
    
    // ==================== CONNECTION MANAGEMENT ====================
    
    /**
     * Close the persistent ADB connection.
     * Call this when the service is destroyed.
     */
    fun closePersistentConnection() {
        adbShellExecutor.closeConnection()
    }
    
    /**
     * Check if ADB is available on the expected port.
     */
    fun checkAdbAvailable(callback: (Boolean, String) -> Unit) {
        adbShellExecutor.execute(
            command = "echo ok",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    callback(true, "ADB available")
                }
                
                override fun onError(error: String) {
                    callback(false, "ADB not available: $error")
                }
            }
        )
    }
    
    // ==================== DAEMON LAUNCHING ====================
    
    /**
     * Launch the CameraDaemon via ADB loopback.
     */
    fun launchDaemon(outputDir: String, nativeLibDir: String, callback: LaunchCallback) {
        daemonLauncher.launchCameraDaemon(
            outputDir = outputDir,
            nativeLibDir = nativeLibDir,
            callback = callback.toDaemonCallback()
        )
    }
    
    /**
     * Launch the SentryDaemon via ADB shell.
     */
    fun launchSentryDaemon(callback: LaunchCallback) {
        daemonLauncher.launchSentryDaemon(callback.toDaemonCallback())
    }
    
    /**
     * Launch the AccSentryDaemon via ADB shell (UID 2000).
     * This daemon handles ACC monitoring and screen control.
     */
    fun launchAccSentryDaemon(onSuccess: () -> Unit, onError: (String) -> Unit) {
        daemonLauncher.launchAccSentryDaemon(object : DaemonLauncher.LaunchCallback {
            override fun onLog(message: String) {
                logManager.debug(TAG, message)
            }
            override fun onLaunched() {
                onSuccess()
            }
            override fun onError(error: String) {
                onError(error)
            }
        })
    }
    
    /**
     * Launch the TelegramBotDaemon via ADB shell.
     * This daemon handles Telegram bot polling and notifications.
     */
    fun launchTelegramDaemon(callback: LaunchCallback) {
        daemonLauncher.launchTelegramDaemon(callback.toDaemonCallback())
    }
    
    /**
     * Stop the TelegramBotDaemon.
     */
    fun stopTelegramDaemon(callback: LaunchCallback) {
        daemonLauncher.stopTelegramDaemon(callback.toDaemonCallback())
    }
    
    /**
     * Launch the proxy daemon via ADB shell.
     */
    fun launchProxyDaemon(callback: LaunchCallback) {
        daemonLauncher.launchProxyDaemon(callback.toDaemonCallback())
    }
    
    /**
     * Kill the daemon process via ADB.
     */
    fun killDaemon(callback: LaunchCallback) {
        daemonLauncher.killDaemon("byd_cam_daemon", callback.toDaemonCallback())
    }
    
    /**
     * Kill a specific daemon process by name via ADB.
     */
    fun killDaemon(processName: String, callback: LaunchCallback) {
        daemonLauncher.killDaemon(processName, callback.toDaemonCallback())
    }
    
    /**
     * Check if daemon process is running via ADB.
     */
    fun isDaemonRunning(callback: (Boolean) -> Unit) {
        daemonLauncher.isDaemonRunning("byd_cam_daemon", callback)
    }
    
    /**
     * Check if a specific daemon is running by process name.
     */
    fun isDaemonRunning(processName: String, callback: (Boolean) -> Unit) {
        daemonLauncher.isDaemonRunning(processName, callback)
    }
    
    /**
     * Get process uptime for a daemon.
     */
    fun getProcessUptime(processName: String, callback: (String?) -> Unit) {
        daemonLauncher.getProcessUptime(processName, callback)
    }
    
    /**
     * Get list of subprocesses for given patterns.
     */
    fun getSubprocesses(processPatterns: List<String>, callback: (List<DaemonLauncher.ProcessInfo>) -> Unit) {
        daemonLauncher.getSubprocesses(processPatterns, callback)
    }
    
    // ==================== TUNNEL LAUNCHING ====================
    
    /**
     * Launch tunnel based on configured tunnel type.
     * Automatically notifies TelegramBotDaemon when tunnel URL is established.
     */
    fun launchTunnel(callback: TunnelCallback) {
        // Currently only Cloudflared is supported
        tunnelLauncher.launchCloudflared(object : TunnelLauncher.TunnelCallback {
            override fun onLog(message: String) = callback.onLog(message)
            
            override fun onTunnelUrl(url: String) {
                // Notify Telegram daemon via IPC when tunnel URL is established
                // Only notify if URL is new/different to avoid duplicate messages
                if (url.isNotEmpty() && url != lastNotifiedTunnelUrl) {
                    lastNotifiedTunnelUrl = url
                    com.overdrive.app.telegram.TelegramNotifier.notifyTunnelUrl(url, true)
                }
                callback.onTunnelUrl(url)
            }
            
            override fun onError(error: String) = callback.onError(error)
        })
    }
    
    /**
     * Stop the tunnel.
     */
    fun stopTunnel(callback: LaunchCallback) {
        // Clear tracked URL so next tunnel start will notify
        lastNotifiedTunnelUrl = null
        
        tunnelLauncher.stopTunnel(object : TunnelLauncher.TunnelCallback {
            override fun onLog(message: String) = callback.onLog(message)
            override fun onTunnelUrl(url: String) = callback.onLaunched()
            override fun onError(error: String) = callback.onError(error)
        })
    }
    
    /**
     * Check if tunnel is running.
     */
    fun isTunnelRunning(callback: (Boolean) -> Unit) {
        tunnelLauncher.isTunnelRunning(callback)
    }
    
    /**
     * Get current tunnel URL from log file.
     */
    fun getTunnelUrl(callback: (String?) -> Unit) {
        tunnelLauncher.getTunnelUrl(callback)
    }
    
    // ==================== SERVICE LAUNCHING ====================
    
    /**
     * Start daemons via ADB shell.
     */
    fun startDaemonsViaShell(callback: LaunchCallback) {
        serviceLauncher.startDaemonsViaShell(callback.toServiceCallback())
    }
    
    /**
     * Configure and start the Location Sidecar Service via ADB shell.
     */
    fun startLocationSidecar(callback: LaunchCallback) {
        serviceLauncher.grantLocationPermissions(callback.toServiceCallback())
    }
    
    /**
     * Apply power settings to keep WiFi and system active.
     */
    fun applyPowerSettings() {
        serviceLauncher.applyPowerSettings(object : ServiceLauncher.LaunchCallback {
            override fun onLog(message: String) {}
            override fun onLaunched() {}
            override fun onError(error: String) {
                logManager.error(TAG, "Failed to apply power settings: $error")
            }
        })
    }
    
    /**
     * Send wake-up broadcasts to keep system active.
     */
    fun pokeMcuViaShell(callback: LaunchCallback? = null) {
        serviceLauncher.sendWakeUpBroadcasts(callback?.toServiceCallback())
    }
    
    /**
     * Turn screen off to reduce power consumption.
     */
    fun turnScreenOff(callback: LaunchCallback? = null) {
        serviceLauncher.turnScreenOff(callback?.toServiceCallback())
    }
    
    // ==================== ACC/WIFI MANAGEMENT ====================
    
    /**
     * Inject app into BYD ACC mode whitelist via ADB shell.
     */
    fun injectAccWhitelist(packageName: String, callback: LaunchCallback) {
        serviceLauncher.injectAccWhitelist(packageName, callback.toServiceCallback())
    }
    
    /**
     * Enable WiFi and prevent it from sleeping via shell commands.
     */
    fun ensureWifiEnabled(callback: LaunchCallback) {
        serviceLauncher.ensureWifiEnabled(callback.toServiceCallback())
    }
    
    // ==================== SENTRY DAEMON ====================
    
    /**
     * Check if Sentry Daemon is running.
     */
    fun isSentryDaemonRunning(callback: (Boolean) -> Unit) {
        daemonLauncher.isDaemonRunning("sentry_daemon", callback)
    }
    
    /**
     * Stop the Sentry Daemon.
     */
    fun stopSentryDaemon(callback: LaunchCallback) {
        daemonLauncher.killDaemon("sentry_daemon", callback.toDaemonCallback())
    }
    
    // ==================== SENTRY PROXY ====================
    
    /**
     * Launch the Sentry Proxy daemon (alias for launchProxyDaemon).
     */
    fun launchSentryProxy(callback: LaunchCallback) {
        daemonLauncher.launchProxyDaemon(callback.toDaemonCallback())
    }
    
    /**
     * Stop the Sentry Proxy and clear proxy settings.
     */
    fun stopSentryProxy(callback: LaunchCallback) {
        daemonLauncher.stopProxyDaemon(callback.toDaemonCallback())
    }
    
    /**
     * Check if Sentry Proxy is running.
     */
    fun isSentryProxyRunning(callback: (Boolean) -> Unit) {
        daemonLauncher.isProxyDaemonRunning(callback)
    }
    
    // ==================== LOCATION SIDECAR MANAGEMENT ====================
    
    /**
     * Stop the Location Sidecar Service.
     */
    fun stopLocationSidecar(callback: LaunchCallback) {
        serviceLauncher.stopLocationSidecarService(callback.toServiceCallback())
    }
    
    /**
     * Check if Location Sidecar is running.
     */
    fun isLocationSidecarRunning(callback: (Boolean) -> Unit) {
        serviceLauncher.isLocationSidecarRunning(callback)
    }
    
    // ==================== LOCATION SIDECAR SERVICE ====================
    
    /**
     * Configure Location permissions via ADB shell.
     * Grants location permissions, applies power settings, and injects ACC whitelist.
     */
    fun startLocationSidecarService(callback: LaunchCallback) {
        logManager.info(TAG, "Configuring LocationSidecarService permissions via ADB shell...")
        callback.onLog("Granting Location permissions...")
        
        // Step 1: Grant Location permissions
        serviceLauncher.grantLocationPermissions(object : ServiceLauncher.LaunchCallback {
            override fun onLog(message: String) = callback.onLog(message)
            
            override fun onLaunched() {
                callback.onLog("Applying power settings...")
                
                // Step 2: Apply power settings
                serviceLauncher.applyPowerSettings(object : ServiceLauncher.LaunchCallback {
                    override fun onLog(message: String) = callback.onLog(message)
                    
                    override fun onLaunched() {
                        callback.onLog("Injecting ACC whitelist...")
                        
                        // Step 3: Inject ACC whitelist
                        serviceLauncher.injectAccWhitelist(context.packageName, object : ServiceLauncher.LaunchCallback {
                            override fun onLog(message: String) = callback.onLog(message)
                            
                            override fun onLaunched() {
                                callback.onLog("Starting LocationSidecarService...")
                                
                                // Step 4: Start the service
                                serviceLauncher.startLocationSidecarService(object : ServiceLauncher.LaunchCallback {
                                    override fun onLog(message: String) = callback.onLog(message)
                                    
                                    override fun onLaunched() {
                                        logManager.info(TAG, "LocationSidecarService started successfully")
                                        callback.onLog("Location service started")
                                        callback.onLaunched()
                                    }
                                    
                                    override fun onError(error: String) {
                                        logManager.error(TAG, "LocationSidecarService start failed: $error")
                                        callback.onError("Location service start failed: $error")
                                    }
                                })
                            }
                            
                            override fun onError(error: String) {
                                // Continue even if ACC whitelist fails
                                logManager.warn(TAG, "ACC whitelist failed (continuing): $error")
                                serviceLauncher.startLocationSidecarService(callback.toServiceCallback())
                            }
                        })
                    }
                    
                    override fun onError(error: String) {
                        // Continue even if power settings fail
                        logManager.warn(TAG, "Power settings failed (continuing): $error")
                        serviceLauncher.startLocationSidecarService(callback.toServiceCallback())
                    }
                })
            }
            
            override fun onError(error: String) {
                callback.onError("Failed to grant Location permissions: $error")
            }
        })
    }
    
    /**
     * Stop LocationSidecarService.
     */
    fun stopLocationSidecarService(callback: LaunchCallback) {
        serviceLauncher.stopLocationSidecarService(callback.toServiceCallback())
    }
    
    // ==================== SINGBOX ====================
    
    /**
     * Launch sing-box proxy.
     */
    fun startSingbox(callback: LaunchCallback) {
        val singboxLauncher = SingboxLauncher(context, adbShellExecutor, logManager)
        singboxLauncher.launchSingbox(object : SingboxLauncher.SingboxCallback {
            override fun onLog(message: String) = callback.onLog(message)
            override fun onStarted() = callback.onLaunched()
            override fun onError(error: String) = callback.onError(error)
        })
    }
    
    /**
     * Check if sing-box is running.
     */
    fun isSingboxRunning(callback: (Boolean) -> Unit) {
        val singboxLauncher = SingboxLauncher(context, adbShellExecutor, logManager)
        singboxLauncher.isRunning(callback)
    }
    
    // ==================== SHELL EXECUTION ====================
    
    /**
     * Execute a shell command via ADB.
     */
    fun executeShellCommand(command: String, callback: LaunchCallback) {
        adbShellExecutor.execute(
            command = command,
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    callback.onLog(output)
                    callback.onLaunched()
                }
                
                override fun onError(error: String) {
                    callback.onError(error)
                }
            }
        )
    }
    
    // ==================== CALLBACK ADAPTERS ====================
    
    private fun LaunchCallback.toDaemonCallback() = object : DaemonLauncher.LaunchCallback {
        override fun onLog(message: String) = this@toDaemonCallback.onLog(message)
        override fun onLaunched() = this@toDaemonCallback.onLaunched()
        override fun onError(error: String) = this@toDaemonCallback.onError(error)
    }
    
    private fun TunnelCallback.toTunnelCallback() = object : TunnelLauncher.TunnelCallback {
        override fun onLog(message: String) = this@toTunnelCallback.onLog(message)
        override fun onTunnelUrl(url: String) = this@toTunnelCallback.onTunnelUrl(url)
        override fun onError(error: String) = this@toTunnelCallback.onError(error)
    }
    
    private fun LaunchCallback.toServiceCallback() = object : ServiceLauncher.LaunchCallback {
        override fun onLog(message: String) = this@toServiceCallback.onLog(message)
        override fun onLaunched() = this@toServiceCallback.onLaunched()
        override fun onError(error: String) = this@toServiceCallback.onError(error)
    }
}
