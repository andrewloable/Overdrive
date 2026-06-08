package net.bladewatch.app.ui.daemon

import android.content.Context
import android.os.Handler
import android.os.Looper
import net.bladewatch.app.config.UnifiedConfigManager
import net.bladewatch.app.launcher.AdbDaemonLauncher
import net.bladewatch.app.launcher.AdbShellExecutor
import net.bladewatch.app.launcher.ZrokLauncher
import net.bladewatch.app.logging.LogManager
import net.bladewatch.app.ui.model.DaemonType
import net.bladewatch.app.ui.util.PreferencesManager
import net.bladewatch.app.ui.viewmodel.DaemonsViewModel

class DaemonStartupManager(
    private val context: Context,
    private val daemonsViewModel: DaemonsViewModel? = null
) {
    private val log = LogManager.getInstance()
    private val handler = Handler(Looper.getMainLooper())
    private val adbLauncher = AdbDaemonLauncher(context)

    private var initStartTime = 0L
    private fun elapsed() = System.currentTimeMillis() - initStartTime
    private fun logT(step: String) {
        if (!UnifiedConfigManager.isTimingLogsEnabled()) return
        val nowMs = System.currentTimeMillis()
        log.info(TAG, "[STARTUP +${elapsed()}ms @${nowMs}] $step")
    }

    companion object {
        private const val TAG = "DaemonStartup"
        private const val HEALTH_CHECK_INTERVAL_MS = 30_000L  // 30 seconds

        val CORE_DAEMONS: List<DaemonType> = listOf(
            DaemonType.CAMERA_DAEMON,
            DaemonType.SENTRY_DAEMON,
            DaemonType.ACC_SENTRY_DAEMON,
        )

        val OPTIONAL_DAEMONS: List<DaemonType> = listOf(
            DaemonType.ZROK_TUNNEL,
        )

        // Track intentional stops so health check doesn't fight the user
        val userStoppedDaemons = mutableSetOf<DaemonType>()

        fun markUserStopped(type: DaemonType) {
            userStoppedDaemons.add(type)
        }

        fun clearUserStopped(type: DaemonType) {
            userStoppedDaemons.remove(type)
        }

        // Keep strong reference to prevent GC during delayed startup
        @Volatile
        private var bootManager: DaemonStartupManager? = null
        
        @Volatile
        private var bootStarted = false

        fun startOnBoot(context: Context) {
            if (bootStarted) return
            bootStarted = true
            userStoppedDaemons.clear()
            val manager = DaemonStartupManager(context, null)
            bootManager = manager
            manager.initializeOnBoot()
        }
    }

    fun initializeOnAppLaunch() {
        initStartTime = System.currentTimeMillis()
        log.info(TAG, "=== Initializing daemon startup on app launch ===")
        log.info(TAG, "Waiting 45 seconds before starting daemons (system stabilization)...")
        logT("initializeOnAppLaunch begin")

        // Reset user-stopped flags on app launch (fresh start = auto-manage)
        userStoppedDaemons.clear()

        // Enable AccessibilityService keep-alive immediately (doesn't need delay)
        enableAccessibilityKeepAlive()
        logT("enableAccessibilityKeepAlive done")

        // Wait 45 seconds for system to fully stabilize before starting any daemons
        handler.postDelayed({
            logT("startCoreDaemons firing (expected +45000ms)")
            startCoreDaemons()
        }, 45000)
        handler.postDelayed({
            logT("startOptionalDaemons firing (expected +60000ms)")
            startOptionalDaemonsFromPreferences()
        }, 60000)

        // Start periodic health check after initial daemons have had time to start
        handler.postDelayed({
            logT("startDaemonHealthCheck firing (expected +90000ms)")
            startDaemonHealthCheck()
        }, 90000)
    }

    /**
     * Setup privileged shell (UID 1000) on app launch.
     * This enables system-level operations like granting permissions and running daemons as system user.
     */
    /*private fun setupPrivilegedShell(onComplete: () -> Unit) {
        PrivilegedShellSetup.init(context)
        
        // Check if already available
        if (PrivilegedShellSetup.isShellAvailable()) {
            log.info(TAG, "Privileged shell already available (UID 1000)")
            onComplete()
            return
        }
        
        log.info(TAG, "Setting up privileged shell...")
        PrivilegedShellSetup.setup(object : PrivilegedShellSetup.SetupCallback {
            override fun onSuccess() {
                log.info(TAG, "Privileged shell ready (UID 1000)")
                onComplete()
            }
            
            override fun onFailure(reason: String) {
                log.warn(TAG, "Privileged shell setup failed: $reason - continuing with normal startup")
                onComplete()
            }
            
            override fun onProgress(message: String) {
                log.debug(TAG, "Shell setup: $message")
            }
        })
    }*/

    private fun initializeOnBoot() {
        initStartTime = System.currentTimeMillis()
        log.info(TAG, "=== Initializing daemon startup on boot ===")
        log.info(TAG, "Waiting 45 seconds before starting daemons (system stabilization)...")
        logT("initializeOnBoot begin")

        // Reset user-stopped flags on boot
        userStoppedDaemons.clear()

        // Enable AccessibilityService keep-alive immediately on boot
        enableAccessibilityKeepAlive()
        logT("enableAccessibilityKeepAlive done")

        // Wait 45 seconds for system to fully stabilize before starting any daemons
        handler.postDelayed({
            logT("startCoreDaemonsViaAdb firing (expected +45000ms)")
            startCoreDaemonsViaAdb()
        }, 45000)
        handler.postDelayed({
            logT("startOptionalDaemonsViaAdb firing (expected +60000ms)")
            startOptionalDaemonsViaAdb()
        }, 60000)

        // Start periodic health check after initial daemons have had time to start
        handler.postDelayed({
            logT("startDaemonHealthCheck firing (expected +90000ms)")
            startDaemonHealthCheck()
        }, 90000)
    }


    fun checkAllDaemonStatuses() {
        log.info(TAG, "=== Checking all daemon statuses ===")
        daemonsViewModel?.let { vm ->
            DaemonType.values().forEach { type -> vm.refreshDaemonStatus(type, logResult = true) }
            // Camera daemon defaults to private stream mode. Public exposure is opt-in
            // via the zrok tunnel in the Daemons settings, not a global mode.
            log.info(TAG, "Syncing camera daemon stream mode to: private")
            vm.cameraDaemonController.setStreamMode("private")
        }
    }

    private fun startCoreDaemons() {
        val vm = daemonsViewModel ?: run {
            log.warn(TAG, "ViewModel not available, using ADB launcher")
            startCoreDaemonsViaAdb()
            return
        }
        log.info(TAG, "Starting core daemons (Camera first, then Sentry daemons)...")

        // Start Camera Daemon FIRST
        log.info(TAG, "Starting Camera Daemon...")
        logT("startDaemon(CAMERA_DAEMON) begin")
        vm.startDaemon(DaemonType.CAMERA_DAEMON)
        logT("startDaemon(CAMERA_DAEMON) dispatched")

        // Start Sentry Daemon after Camera Daemon has time to initialize
        handler.postDelayed({
            logT("startDaemon(SENTRY_DAEMON) firing (expected +5000ms after core start)")
            log.info(TAG, "Starting Sentry Daemon...")
            vm.startDaemon(DaemonType.SENTRY_DAEMON)
        }, 5000)

        // Start ACC Sentry Daemon last
        handler.postDelayed({
            logT("startDaemon(ACC_SENTRY_DAEMON) firing (expected +10000ms after core start)")
            log.info(TAG, "Starting ACC Sentry Daemon...")
            vm.startDaemon(DaemonType.ACC_SENTRY_DAEMON)
        }, 10000)
    }

    private fun startCoreDaemonsViaAdb() {
        log.info(TAG, "Starting core daemons via ADB (Camera first, then Sentry daemons)...")

        // Start Camera Daemon FIRST
        logT("isDaemonRunning(camera_daemon) check begin")
        adbLauncher.isDaemonRunning("camera_daemon") { running ->
            logT("isDaemonRunning(camera_daemon) result=$running")
            if (!running) {
                log.info(TAG, "Boot: Starting Camera Daemon...")
                val nativeLibDir = context.applicationInfo.nativeLibraryDir
                val outputDir = context.getExternalFilesDir(null)?.absolutePath ?: context.filesDir.absolutePath
                logT("launchDaemon(CameraDaemon) begin")
                adbLauncher.launchDaemon(outputDir, nativeLibDir, createTimedLogCallback("CameraDaemon"))
            } else {
                log.info(TAG, "Boot: Camera Daemon already running")
            }
        }

        // Start Sentry Daemon after Camera Daemon has time to initialize
        handler.postDelayed({
            logT("isSentryDaemonRunning check firing (expected +5000ms after core start)")
            adbLauncher.isSentryDaemonRunning { running ->
                logT("isSentryDaemonRunning result=$running")
                if (!running) {
                    log.info(TAG, "Boot: Starting Sentry Daemon...")
                    logT("launchSentryDaemon begin")
                    adbLauncher.launchSentryDaemon(createTimedLogCallback("SentryDaemon"))
                } else {
                    log.info(TAG, "Boot: Sentry Daemon already running")
                }
            }
        }, 5000)

        // Start ACC Sentry Daemon last
        handler.postDelayed({
            logT("isDaemonRunning(acc_sentry_daemon) check firing (expected +10000ms after core start)")
            adbLauncher.isDaemonRunning("acc_sentry_daemon") { running ->
                logT("isDaemonRunning(acc_sentry_daemon) result=$running")
                if (!running) {
                    log.info(TAG, "Boot: Starting ACC Sentry Daemon...")
                    logT("launchAccSentryDaemon begin")
                    adbLauncher.launchAccSentryDaemon(
                        onSuccess = {
                            logT("launchAccSentryDaemon onLaunched")
                            log.info(TAG, "Boot: ACC Sentry Daemon started")
                        },
                        onError = { error -> log.error(TAG, "Boot: ACC Sentry error: $error") }
                    )
                } else {
                    log.info(TAG, "Boot: ACC Sentry Daemon already running")
                }
            }
        }, 10000)
    }


    private fun startOptionalDaemonsFromPreferences() {
        val vm = daemonsViewModel ?: run {
            log.warn(TAG, "ViewModel not available, using ADB launcher")
            startOptionalDaemonsViaAdb()
            return
        }
        log.info(TAG, "Starting optional daemons from preferences...")

        startTunnelFromPreferences(vm)
    }

    private fun startTunnelFromPreferences(vm: DaemonsViewModel) {
        val zrokEnabled = PreferencesManager.isDaemonEnabled(DaemonType.ZROK_TUNNEL)

        if (zrokEnabled) {
            vm.zrokController.isRunning { isRunning ->
                if (isRunning) {
                    log.info(TAG, "Zrok already running, skipping start")
                } else {
                    log.info(TAG, "Starting Zrok (user enabled)...")
                    handler.post { vm.startDaemon(DaemonType.ZROK_TUNNEL) }
                }
            }
        } else {
            log.info(TAG, "No tunnel enabled by user")
        }
    }

    private fun startOptionalDaemonsViaAdb() {
        log.info(TAG, "Starting optional daemons via ADB...")
        try {
            if (PreferencesManager.isDaemonEnabled(DaemonType.ZROK_TUNNEL)) {
                log.info(TAG, "Boot: Starting Zrok...")
                startZrokOnBoot()
            }
        } catch (e: Exception) {
            log.error(TAG, "Error starting optional daemons: ${e.message}")
        }
    }
    
    /**
     * Start Zrok tunnel on boot using ZrokLauncher directly.
     */
    private fun startZrokOnBoot() {
        val adbShellExecutor = AdbShellExecutor(context)
        val zrokLauncher = ZrokLauncher(context, adbShellExecutor, log)
        
        zrokLauncher.launchZrok(object : ZrokLauncher.ZrokCallback {
            override fun onLog(message: String) {
                log.debug(TAG, "[Zrok Boot] $message")
            }
            
            override fun onTunnelUrl(url: String) {
                log.info(TAG, "Boot: Zrok URL: $url")
            }
            
            override fun onError(error: String) {
                log.error(TAG, "Boot: Zrok error: $error")
            }
        })
    }

    /**
     * Restart tunnel if enabled. When forceRestart=true, kills existing tunnel first
     * so it can pick up new settings.
     */
    private fun restartTunnelIfEnabled(vm: DaemonsViewModel, forceRestart: Boolean = false) {
        val zrokEnabled = PreferencesManager.isDaemonEnabled(DaemonType.ZROK_TUNNEL)

        if (zrokEnabled) {
            vm.zrokController.isRunning { isRunning ->
                if (isRunning && forceRestart) {
                    log.info(TAG, "Restarting Zrok to apply new settings...")
                    handler.post {
                        vm.stopDaemon(DaemonType.ZROK_TUNNEL)
                        handler.postDelayed({
                            log.info(TAG, "Starting Zrok with new settings")
                            vm.startDaemon(DaemonType.ZROK_TUNNEL)
                        }, 2000)
                    }
                } else if (!isRunning) {
                    log.info(TAG, "Starting Zrok (user enabled)")
                    handler.post { vm.startDaemon(DaemonType.ZROK_TUNNEL) }
                } else {
                    log.info(TAG, "Zrok already running, no restart needed")
                }
            }
        }
    }

    private fun startTunnelIfEnabled(vm: DaemonsViewModel) {
        restartTunnelIfEnabled(vm, forceRestart = false)
    }

    fun onDaemonToggled(type: DaemonType, enabled: Boolean) {
        if (type in OPTIONAL_DAEMONS) {
            val state = if (enabled) "ON" else "OFF"
            log.info(TAG, "User toggled ${type.displayName} to $state - saving preference")
            PreferencesManager.setDaemonEnabled(type, enabled)
        }
    }

    private fun createLogCallback(name: String): AdbDaemonLauncher.LaunchCallback {
        return object : AdbDaemonLauncher.LaunchCallback {
            override fun onLog(message: String) { log.debug(TAG, "[$name] $message") }
            override fun onLaunched() { log.info(TAG, "[$name] Started successfully") }
            override fun onError(error: String) { log.error(TAG, "[$name] Error: $error") }
        }
    }

    private fun createTimedLogCallback(name: String): AdbDaemonLauncher.LaunchCallback {
        return object : AdbDaemonLauncher.LaunchCallback {
            override fun onLog(message: String) { log.debug(TAG, "[$name] $message") }
            override fun onLaunched() {
                logT("[$name] onLaunched (daemon started successfully)")
                log.info(TAG, "[$name] Started successfully")
            }
            override fun onError(error: String) {
                logT("[$name] onError: $error")
                log.error(TAG, "[$name] Error: $error")
            }
        }
    }

    /**
     * Enable the KeepAliveAccessibilityService via ADB settings.
     * This gives the app the highest process priority — BYD's firmware
     * will not kill an active AccessibilityService even after 24+ hours.
     */
    private fun enableAccessibilityKeepAlive() {
        // Check if already running in-process first
        if (net.bladewatch.app.services.KeepAliveAccessibilityService.isRunning()) {
            log.info(TAG, "AccessibilityService already running")
            return
        }

        log.info(TAG, "Enabling AccessibilityService keep-alive via ADB...")
        val serviceLauncher = net.bladewatch.app.launcher.ServiceLauncher(
            context,
            net.bladewatch.app.launcher.AdbShellExecutor(context),
            log
        )
        serviceLauncher.enableAccessibilityKeepAlive(object : net.bladewatch.app.launcher.ServiceLauncher.LaunchCallback {
            override fun onLog(message: String) { log.debug(TAG, "[A11y] $message") }
            override fun onLaunched() { log.info(TAG, "AccessibilityService keep-alive enabled") }
            override fun onError(error: String) { log.warn(TAG, "AccessibilityService enable failed: $error (non-fatal)") }
        })
    }

    private var healthCheckRunning = false

    /**
     * Periodic health check: every 30s, verify all expected daemons are alive.
     * Core daemons are always restarted. Optional daemons only if user had them enabled.
     * Daemons intentionally stopped by the user are skipped.
     */
    private fun startDaemonHealthCheck() {
        if (healthCheckRunning) return
        healthCheckRunning = true
        log.info(TAG, "Daemon health check started (interval=${HEALTH_CHECK_INTERVAL_MS / 1000}s)")
        scheduleNextHealthCheck()
    }

    private fun scheduleNextHealthCheck() {
        handler.postDelayed({
            if (healthCheckRunning) {
                runHealthCheck()
                scheduleNextHealthCheck()
            }
        }, HEALTH_CHECK_INTERVAL_MS)
    }

    private fun runHealthCheck() {
        // Core daemons: always restart unless user explicitly stopped
        for (type in CORE_DAEMONS) {
            if (type in userStoppedDaemons) continue
            checkAndRelaunchDaemon(type)
        }

        // Optional daemons: only restart if user had them enabled in preferences
        for (type in OPTIONAL_DAEMONS) {
            if (type in userStoppedDaemons) continue
            if (!PreferencesManager.isDaemonEnabled(type)) continue
            checkAndRelaunchDaemon(type)
        }
    }

    private fun checkAndRelaunchDaemon(type: DaemonType) {
        adbLauncher.isDaemonRunning(type.processName) { isRunning ->
            if (!isRunning) {
                log.warn(TAG, "Health check: ${type.displayName} is DEAD — relaunching...")
                relaunchDaemon(type)
            }
        }
    }

    private fun relaunchDaemon(type: DaemonType) {
        val vm = daemonsViewModel
        if (vm != null) {
            handler.post { vm.startDaemon(type) }
        } else {
            // Fallback: ADB-only launch for when ViewModel is not available (boot path)
            when (type) {
                DaemonType.CAMERA_DAEMON -> {
                    val nativeLibDir = context.applicationInfo.nativeLibraryDir
                    val outputDir = context.getExternalFilesDir(null)?.absolutePath ?: context.filesDir.absolutePath
                    adbLauncher.launchDaemon(outputDir, nativeLibDir, createLogCallback("HealthCheck-Camera"))
                }
                DaemonType.SENTRY_DAEMON -> {
                    adbLauncher.launchSentryDaemon(createLogCallback("HealthCheck-Sentry"))
                }
                DaemonType.ACC_SENTRY_DAEMON -> {
                    adbLauncher.launchAccSentryDaemon(
                        onSuccess = { log.info(TAG, "HealthCheck: ACC Sentry restarted") },
                        onError = { e -> log.error(TAG, "HealthCheck: ACC Sentry restart failed: $e") }
                    )
                }
                else -> {
                    log.warn(TAG, "Health check: no ADB fallback for ${type.displayName}")
                }
            }
        }
    }

    fun cleanup() {
        healthCheckRunning = false
        handler.removeCallbacksAndMessages(null)
        adbLauncher.closePersistentConnection()
    }
}
