package com.overdrive.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.util.Log
import com.overdrive.app.services.DaemonKeepaliveService
import com.overdrive.app.ui.daemon.DaemonStartupManager
import com.overdrive.app.ui.util.PreferencesManager

/**
 * Handles boot and system events to start daemons.
 * 
 * Listens for:
 * - Boot completed events
 * - Screen/user events (including SCREEN_OFF via ScreenOffReceiver delegation)
 * - BYD ACC ON/OFF events
 * - WiFi/Network state changes
 * 
 * Starts DaemonKeepaliveService (foreground + sticky + wakelock) and
 * daemons via DaemonStartupManager.
 */
class BootReceiver : BroadcastReceiver() {
    
    companion object {
        const val TAG = "BootReceiver"
        
        @Volatile
        private var lastStartTime = 0L
        private const val MIN_RESTART_INTERVAL = 5000L // 5 seconds debounce
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return

        Log.d(TAG, "Received broadcast: $action")

        // Forward plug edges to ChargingDetector BEFORE the debounce gate.
        // The debounce only protects daemon restarts from thrashing; plug
        // edges must always reach the detector or a quick unplug-replug
        // sequence will lose the second connect event and the detector will
        // miss the start of the new charging session.
        when (action) {
            Intent.ACTION_POWER_CONNECTED -> {
                try {
                    com.overdrive.app.monitor.ChargingDetector.getInstance().onPowerConnected()
                } catch (e: Exception) {
                    Log.w(TAG, "ChargingDetector connect notify failed: ${e.message}")
                }
            }
            Intent.ACTION_POWER_DISCONNECTED -> {
                try {
                    com.overdrive.app.monitor.ChargingDetector.getInstance().onPowerDisconnected()
                } catch (e: Exception) {
                    Log.w(TAG, "ChargingDetector disconnect notify failed: ${e.message}")
                }
            }
        }

        // Debounce rapid restarts
        val now = System.currentTimeMillis()
        if (now - lastStartTime < MIN_RESTART_INTERVAL) {
            Log.d(TAG, "Debouncing restart (too soon)")
            return
        }
        
        // Initialize PreferencesManager if needed (for boot scenarios)
        // Uses device-encrypted storage so it works before user unlock
        try {
            if (!PreferencesManager.isInitialized()) {
                PreferencesManager.init(context.applicationContext)
            }
        } catch (e: Exception) {
            Log.w(TAG, "PreferencesManager init error: ${e.message}")
            // Continue anyway - core daemons can start without preferences
        }
        
        when (action) {
            // Boot events - start daemons and launch activity minimized.
            // Launching the activity keeps the app process alive (Android is less
            // likely to kill a process with a recent activity) and runs essential
            // initialization (storage, device ID, BYD whitelist). We immediately
            // move it to the back so the user sees their home screen, not OverDrive.
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.LOCKED_BOOT_COMPLETED",
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON" -> {
                startDaemons(context, action)
                try {
                    val launchIntent = Intent(context, com.overdrive.app.ui.MainActivity::class.java)
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    launchIntent.putExtra("minimize_on_start", true)
                    context.startActivity(launchIntent)
                    Log.d(TAG, "App launched minimized on boot")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to launch app: ${e.message}")
                }
            }
            
            // App update — DO NOT start daemons here. The old process's daemon
            // kill sequence may still be in flight, and the new MainActivity is
            // the sole orchestrator post-update: it runs UpdateLifecycle.hardResetDaemons
            // before DaemonStartupManager. Starting daemons here would race the
            // hard reset and resurrect old/zombie watchdogs (see /data/local/tmp/
            // overdrive_update_in_progress sentinel).
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                lastStartTime = System.currentTimeMillis()
                // Skip auto-relaunch in debug builds: Android Studio drives its own
                // launch after install, and racing it makes "Run" abort with the
                // app already in the foreground.
                if (com.overdrive.app.BuildConfig.DEBUG) {
                    Log.d(TAG, "MY_PACKAGE_REPLACED — debug build, skipping auto-relaunch (let IDE drive launch)")
                    return
                }
                try {
                    val launchIntent = Intent(context, com.overdrive.app.ui.MainActivity::class.java)
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    launchIntent.putExtra(
                        com.overdrive.app.updater.UpdateLifecycle.EXTRA_POST_UPDATE,
                        true,
                    )
                    context.startActivity(launchIntent)
                    Log.d(TAG, "MY_PACKAGE_REPLACED — relaunching MainActivity (post_update=true)")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to relaunch app: ${e.message}")
                }
            }
            
            // Screen/user events - start if not running
            Intent.ACTION_SCREEN_ON,
            Intent.ACTION_SCREEN_OFF,  // Delegated from ScreenOffReceiver
            Intent.ACTION_USER_PRESENT,
            "android.intent.action.USER_UNLOCKED" -> {
                startDaemons(context, action)
            }

            // Plug-in is also a wake-worthy event so the daemon comes
            // up to record the overnight charge session. (The detector
            // notification already fired above, before debounce.)
            Intent.ACTION_POWER_CONNECTED -> {
                startDaemons(context, action)
            }
            // POWER_DISCONNECTED: detector was notified before debounce.
            // No daemon restart needed.
            Intent.ACTION_POWER_DISCONNECTED -> {
                // intentionally no-op here
            }
            
            // BYD ACC ON events - start daemons
            "com.byd.action.ACC_ON",
            "com.byd.action.IGN_ON",
            "com.byd.accmode.ACC_MODE_CHANGED" -> {
                startDaemons(context, action)
            }
            
            // BYD ACC OFF - AccSentryDaemon handles sentry mode via bodywork listener
            "com.byd.action.ACC_OFF" -> {
                Log.d(TAG, "ACC OFF received - AccSentryDaemon handles sentry mode")
            }
            
            // WiFi/Network events - restart daemons if WiFi is enabled
            "android.net.wifi.STATE_CHANGE",
            "android.net.conn.CONNECTIVITY_CHANGE" -> {
                val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                if (wifiManager?.isWifiEnabled == true) {
                    startDaemons(context, action)
                }
            }
        }
    }
    
    private fun startDaemons(context: Context, trigger: String) {
        lastStartTime = System.currentTimeMillis()
        Log.d(TAG, "Starting daemons (trigger: $trigger)")
        
        try {
            // Start DaemonKeepaliveService (foreground + sticky + wakelock)
            DaemonKeepaliveService.start(context.applicationContext)

            // Also start daemons directly via DaemonStartupManager
            DaemonStartupManager.startOnBoot(context.applicationContext)

            // (Re-)seed out-of-process revival watchdog. Self-heals the alarm
            // chain if it was ever broken (force-stop, reboot, app data clear).
            ProcessRevivalReceiver.schedule(context.applicationContext)

            Log.d(TAG, "Daemon startup initiated successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start daemons: ${e.message}")
            e.printStackTrace()
        }
    }
}
