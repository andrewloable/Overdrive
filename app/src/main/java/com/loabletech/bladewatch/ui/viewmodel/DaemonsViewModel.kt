package com.loabletech.bladewatch.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.loabletech.bladewatch.launcher.AdbDaemonLauncher
import com.loabletech.bladewatch.logging.LogManager
import com.loabletech.bladewatch.ui.daemon.*
import com.loabletech.bladewatch.ui.model.DaemonState
import com.loabletech.bladewatch.ui.model.DaemonStatus
import com.loabletech.bladewatch.ui.model.DaemonType
import com.loabletech.bladewatch.ui.model.SubprocessInfo
import com.loabletech.bladewatch.ui.model.parseUptimeToMillis

/**
 * ViewModel for managing daemon states.
 */
class DaemonsViewModel(app: Application) : AndroidViewModel(app) {
    
    private val adbLauncher = AdbDaemonLauncher(app)
    
    private val controllers: Map<DaemonType, DaemonController>
    
    private val _daemonStates = MutableLiveData<Map<DaemonType, DaemonState>>()
    val daemonStates: LiveData<Map<DaemonType, DaemonState>> = _daemonStates
    private val daemonStatesLock = Any()
    private var daemonStatesSnapshot: Map<DaemonType, DaemonState> = emptyMap()
    
    // Expose zrok controller for tunnel URL access
    val zrokController: ZrokController

    // Expose camera daemon controller for startup manager
    val cameraDaemonController: CameraDaemonController

    // Reference to startup manager (set by Activity after creation)
    private var startupManager: DaemonStartupManager? = null
    
    // Expose startup manager for preference saving
    val daemonStartupManager: DaemonStartupManager?
        get() = startupManager
    
    fun setStartupManager(manager: DaemonStartupManager) {
        startupManager = manager
    }
    
    init {
        zrokController = ZrokController(app, adbLauncher)
        cameraDaemonController = CameraDaemonController(app, adbLauncher)

        controllers = mapOf(
            DaemonType.CAMERA_DAEMON to cameraDaemonController,
            DaemonType.SENTRY_DAEMON to SentryDaemonController(adbLauncher),
            DaemonType.ACC_SENTRY_DAEMON to AccSentryDaemonController(adbLauncher),
            DaemonType.ZROK_TUNNEL to zrokController
        )
        
        // Initialize all states as stopped
        val initialStates = DaemonType.values().associateWith { DaemonState.stopped(it) }
        daemonStatesSnapshot = initialStates
        _daemonStates.value = initialStates
        
        // Refresh all statuses after a short delay to ensure ADB connection is ready
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            LogManager.getInstance().info("Daemons", "Initial daemon status refresh...")
            refreshAllStatuses(logResults = true)
        }, 1500)
        
        // Periodic refresh for tunnel daemons (every 30 seconds)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(object : Runnable {
            override fun run() {
                // Only refresh tunnel statuses periodically
                refreshDaemonStatus(DaemonType.ZROK_TUNNEL)
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(this, 30000)
            }
        }, 30000)
    }
    
    fun startDaemon(type: DaemonType) {
        val controller = controllers[type] ?: return
        
        // Clear user-stopped flag so health check can manage this daemon
        DaemonStartupManager.clearUserStopped(type)

        // For optional daemons, save the enabled state so they auto-start on app restart
        if (type in DaemonStartupManager.OPTIONAL_DAEMONS) {
            startupManager?.onDaemonToggled(type, true)
        }
        
        updateState(type, DaemonStatus.STARTING, "Starting...")
        
        controller.start(object : DaemonCallback {
            override fun onStatusChanged(status: DaemonStatus, message: String) {
                updateState(type, status, message)
            }
            
            override fun onError(error: String) {
                updateState(type, DaemonStatus.ERROR, error)
            }
        })
    }
    
    fun stopDaemon(type: DaemonType) {
        val controller = controllers[type] ?: return
        
        // Mark as user-stopped so health check doesn't auto-restart
        DaemonStartupManager.markUserStopped(type)
        
        updateState(type, DaemonStatus.STOPPING, "Stopping daemon and related processes...")
        
        // For optional daemons, save the disabled state so they don't auto-start on app restart
        if (type in DaemonStartupManager.OPTIONAL_DAEMONS) {
            startupManager?.onDaemonToggled(type, false)
        }
        
        controller.stop(object : DaemonCallback {
            override fun onStatusChanged(status: DaemonStatus, message: String) {
                updateState(type, status, message)
            }
            
            override fun onError(error: String) {
                // Stop failed - refresh actual status
                updateState(type, DaemonStatus.ERROR, "Stop failed: $error")
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    refreshDaemonStatus(type)
                }, 1000)
            }
        })
    }
    
    fun refreshDaemonStatus(type: DaemonType, logResult: Boolean = false) {
        val controller = controllers[type] ?: return
        
        // Special handling for Zrok - check token first
        if (type == DaemonType.ZROK_TUNNEL) {
            zrokController.hasEnableToken { hasToken ->
                if (!hasToken) {
                    // No token configured - show needs config state
                    updateZrokNeedsConfig("No token configured. Tap to set up.")
                    if (logResult) {
                        LogManager.getInstance().debug("Daemons", "${type.name}: No token configured")
                    }
                    return@hasEnableToken
                }
                
                // Token exists, proceed with normal status check
                doRefreshDaemonStatus(type, controller, logResult)
            }
            return
        }

        doRefreshDaemonStatus(type, controller, logResult)
    }
    
    private fun doRefreshDaemonStatus(type: DaemonType, controller: DaemonController, logResult: Boolean) {
        
        controller.isRunning { isRunning ->
            if (logResult) {
                LogManager.getInstance().debug("Daemons", "refreshDaemonStatus: ${type.name} isRunning=$isRunning")
            }
            
            if (isRunning) {
                // Get process uptime and subprocesses
                val processName = getProcessName(type)
                val subprocessPatterns = getSubprocessPatterns(type)
                
                adbLauncher.getProcessUptime(processName) { uptime ->
                    // Get subprocess info
                    adbLauncher.getSubprocesses(subprocessPatterns) { processes ->
                        val subprocesses = processes.map { p ->
                            SubprocessInfo(p.name, p.pid, p.uptime)
                        }
                        
                        // For zrok, also fetch the tunnel URL
                        if (type == DaemonType.ZROK_TUNNEL) {
                            zrokController.refreshTunnelUrl { url ->
                                val statusText = url ?: "Running"
                                updateStateWithSubprocesses(type, DaemonStatus.RUNNING, statusText, uptime, subprocesses)
                                if (logResult) {
                                    val uptimeStr = uptime?.let { " (uptime: $it)" } ?: ""
                                    LogManager.getInstance().info("Daemons", "${type.name}: Running$uptimeStr" + (url?.let { " - $it" } ?: ""))
                                    subprocesses.forEach { sp ->
                                        LogManager.getInstance().debug("Daemons", "  └─ ${sp.name} (PID: ${sp.pid}, uptime: ${sp.uptime})")
                                    }
                                }
                            }
                        } else {
                            updateStateWithSubprocesses(type, DaemonStatus.RUNNING, "Running", uptime, subprocesses)
                            if (logResult) {
                                val uptimeStr = uptime?.let { " (uptime: $it)" } ?: ""
                                LogManager.getInstance().info("Daemons", "${type.name}: Running$uptimeStr")
                                subprocesses.forEach { sp ->
                                    LogManager.getInstance().debug("Daemons", "  └─ ${sp.name} (PID: ${sp.pid}, uptime: ${sp.uptime})")
                                }
                            }
                        }
                    }
                }
            } else {
                updateState(type, DaemonStatus.STOPPED, "Not running")
                if (logResult) {
                    LogManager.getInstance().debug("Daemons", "${type.name}: Not running")
                }
            }
        }
    }
    
    private fun getProcessName(type: DaemonType): String {
        return when (type) {
            DaemonType.CAMERA_DAEMON -> "byd_cam_daemon"
            DaemonType.SENTRY_DAEMON -> "sentry_daemon"
            DaemonType.ACC_SENTRY_DAEMON -> "acc_sentry_daemon"
            DaemonType.ZROK_TUNNEL -> "zrok share"
        }
    }
    
    private fun getSubprocessPatterns(type: DaemonType): List<String> {
        return when (type) {
            DaemonType.CAMERA_DAEMON -> listOf("byd_cam_daemon", "ffmpeg", "mediamtx")
            DaemonType.SENTRY_DAEMON -> listOf("sentry_daemon")
            DaemonType.ACC_SENTRY_DAEMON -> listOf("acc_sentry_daemon")
            DaemonType.ZROK_TUNNEL -> listOf("zrok")
        }
    }
    
    private fun updateStateWithSubprocesses(
        type: DaemonType, 
        status: DaemonStatus, 
        message: String,
        uptime: String?,
        subprocesses: List<SubprocessInfo>
    ) {
        val startTime = uptime?.let { System.currentTimeMillis() - parseUptimeToMillis(it) }
        updateDaemonState(type, DaemonState(type, status, message, uptime, startTime, subprocesses))
    }
    
    fun refreshAllStatuses(logResults: Boolean = false) {
        if (logResults) {
            LogManager.getInstance().info("Daemons", "Checking daemon statuses...")
        }
        DaemonType.values().forEach { type ->
            refreshDaemonStatus(type, logResults)
        }
    }
    
    fun cleanupAll() {
        controllers.values.forEach { it.cleanup() }
        
        // Reset all states to stopped
        val stoppedStates = DaemonType.values().associateWith { DaemonState.stopped(it) }
        synchronized(daemonStatesLock) {
            daemonStatesSnapshot = stoppedStates
        }
        _daemonStates.postValue(stoppedStates)
    }
    
    private fun updateState(type: DaemonType, status: DaemonStatus, message: String) {
        updateDaemonState(type, DaemonState(type, status, message))
    }
    
    fun getState(type: DaemonType): DaemonState? = synchronized(daemonStatesLock) {
        daemonStatesSnapshot[type]
    }
    
    /**
     * Update Zrok state to indicate configuration is needed.
     */
    fun updateZrokNeedsConfig(message: String) {
        updateDaemonState(DaemonType.ZROK_TUNNEL, DaemonState.needsConfig(DaemonType.ZROK_TUNNEL, message))
    }

    private fun updateDaemonState(type: DaemonType, state: DaemonState) {
        val updatedStates = synchronized(daemonStatesLock) {
            val currentStates = daemonStatesSnapshot.toMutableMap()
            currentStates[type] = state
            daemonStatesSnapshot = currentStates.toMap()
            daemonStatesSnapshot
        }
        _daemonStates.postValue(updatedStates)
    }
    
    /**
     * Start Location Sidecar service via ADB (grants permissions first).
     * This reuses the existing adbLauncher to avoid multiple ADB auth popups.
     */
    fun startLocationSidecarService(callback: AdbDaemonLauncher.LaunchCallback) {
        adbLauncher.startLocationSidecarService(callback)
    }
    
    override fun onCleared() {
        super.onCleared()
        adbLauncher.closePersistentConnection()
    }
}
