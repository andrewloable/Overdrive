package com.overdrive.app.util

import android.content.Context
import android.content.Intent
import android.hardware.bydauto.power.BYDAutoPowerDevice
import android.os.IBinder
import android.os.Parcel
import android.os.PowerManager
import com.overdrive.app.logging.DaemonLogger

/**
 * Helper to keep the BYD MCU from cutting power to WiFi.
 * 
 * The MCU has a countdown timer (10-15 mins) after ignition off.
 * When it expires, it cuts power to WiFi hardware to save battery.
 * 
 * This helper "pokes" the MCU periodically to reset that timer,
 * keeping WiFi powered indefinitely.
 * 
 * Uses smart wake-up pattern:
 * - Check getMcuStatus() first
 * - Only call wakeUpMcu() if status != 1 (Active)
 * 
 * NOTE: This is for use within the main app context.
 * For standalone daemons (UID 2000), use BYDAutoPowerDevice directly.
 */
object PowerHelper {
    private const val TAG = "PowerHelper"
    private val logger = DaemonLogger.getInstance(TAG)
    
    // MCU Status codes
    const val MCU_STATUS_SLEEPING = 0
    const val MCU_STATUS_ACTIVE = 1      // No wake needed
    const val MCU_STATUS_ACC_OFF = 2
    const val MCU_STATUS_DEEP_SLEEP = 3
    
    private val BYD_POWER_SERVICES = listOf(
        "BYDMgmt",
        "byd_car_service",
        "autoservice",
        "accmodemanager"
    )
    
    // Track last MCU status for logging
    private var lastMcuStatus = -1
    
    /**
     * Smart MCU wake-up using BYDAutoPowerDevice.
     * Only wakes if MCU is not already active (status != 1).
     * 
     * @return true if MCU is active (either already was or successfully woken)
     */
    fun smartWakeUpMcu(context: Context): Boolean {
        return try {
            val powerDevice = BYDAutoPowerDevice.getInstance(context)
            val status = powerDevice.mcuStatus
            
            // Log status changes
            if (status != lastMcuStatus) {
                logger.info("MCU status changed: $lastMcuStatus -> $status (${getMcuStatusName(status)})")
                lastMcuStatus = status
            }
            
            when (status) {
                MCU_STATUS_ACTIVE -> {
                    // Already active, no action needed
                    true
                }
                MCU_STATUS_SLEEPING, MCU_STATUS_ACC_OFF, MCU_STATUS_DEEP_SLEEP -> {
                    // MCU sleeping, wake it up
                    logger.info("MCU sleeping (status=$status), forcing wake...")
                    val result = powerDevice.wakeUpMcu()
                    if (result == 0) {
                        logger.info("wakeUpMcu() succeeded")
                        true
                    } else {
                        logger.warn("wakeUpMcu() returned error: $result")
                        // Fall back to other methods
                        sendWakeUpSignal(context)
                    }
                }
                else -> {
                    // Unknown status, try wake anyway
                    logger.warn("Unknown MCU status: $status, attempting wake...")
                    powerDevice.wakeUpMcu()
                    sendWakeUpSignal(context)
                }
            }
        } catch (e: Exception) {
            logger.error("BYDAutoPowerDevice failed: ${e.message}, falling back to legacy methods")
            sendWakeUpSignal(context)
        }
    }
    
    /**
     * Get current MCU status.
     * @return status code or -1 if unavailable
     */
    fun getMcuStatus(context: Context): Int {
        return try {
            BYDAutoPowerDevice.getInstance(context).mcuStatus
        } catch (e: Exception) {
            -1
        }
    }
    
    /**
     * Check if MCU is currently active (no wake needed).
     */
    fun isMcuActive(context: Context): Boolean {
        return getMcuStatus(context) == MCU_STATUS_ACTIVE
    }
    
    private fun getMcuStatusName(status: Int): String {
        return when (status) {
            MCU_STATUS_SLEEPING -> "SLEEPING"
            MCU_STATUS_ACTIVE -> "ACTIVE"
            MCU_STATUS_ACC_OFF -> "ACC_OFF"
            MCU_STATUS_DEEP_SLEEP -> "DEEP_SLEEP"
            else -> "UNKNOWN"
        }
    }
    
    /**
     * Legacy wake-up signal (fallback when BYDAutoPowerDevice unavailable).
     * Sends signals to various BYD services to reset power-off countdown.
     */
    fun sendWakeUpSignal(context: Context): Boolean {
        var success = false
        
        success = tryBydPowerServices() || success
        success = tryPowerManagerWakeup(context) || success
        success = tryWakeUpBroadcast(context) || success
        success = tryAccModeKeepalive() || success
        
        return success
    }
    
    private fun tryBydPowerServices(): Boolean {
        var success = false
        
        try {
            val sm = Class.forName("android.os.ServiceManager")
            val getService = sm.getMethod("getService", String::class.java)
            
            for (serviceName in BYD_POWER_SERVICES) {
                try {
                    val binder = getService.invoke(null, serviceName) as? IBinder ?: continue
                    
                    for (code in 1..10) {
                        try {
                            val data = Parcel.obtain()
                            val reply = Parcel.obtain()
                            try {
                                data.writeInterfaceToken("android.os.I${serviceName.replaceFirstChar { it.uppercase() }}")
                                data.writeInt(1)
                                
                                val result = binder.transact(code, data, reply, 0)
                                if (result) {
                                    success = true
                                }
                            } finally {
                                data.recycle()
                                reply.recycle()
                            }
                        } catch (e: Exception) {
                            // Ignore
                        }
                    }
                } catch (e: Exception) {
                    // Service not available
                }
            }
        } catch (e: Exception) {
            logger.debug("BYD power services failed: ${e.message}")
        }
        
        return success
    }
    
    private fun tryPowerManagerWakeup(context: Context): Boolean {
        return try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            
            val wakeUpMethod = pm.javaClass.getMethod(
                "wakeUp",
                Long::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                String::class.java
            )
            wakeUpMethod.invoke(pm, System.currentTimeMillis(), 0, "com.overdrive.app:KEEP_ALIVE")
            true
        } catch (e: NoSuchMethodException) {
            try {
                val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                val wakeUpMethod = pm.javaClass.getMethod(
                    "wakeUp",
                    Long::class.javaPrimitiveType
                )
                wakeUpMethod.invoke(pm, System.currentTimeMillis())
                true
            } catch (e2: Exception) {
                false
            }
        } catch (e: Exception) {
            false
        }
    }
    
    private fun tryWakeUpBroadcast(context: Context): Boolean {
        return try {
            val wakeUpActions = listOf(
                "com.byd.action.WAKE_UP",
                "com.byd.action.KEEP_ALIVE",
                "com.byd.accmode.KEEP_ALIVE",
                "com.byd.action.USER_ACTIVITY",
                "com.byd.action.RESET_SLEEP_TIMER"
            )
            
            for (action in wakeUpActions) {
                try {
                    val intent = Intent(action)
                    intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                    context.sendBroadcast(intent)
                } catch (e: Exception) {
                    // Ignore
                }
            }
            true
        } catch (e: Exception) {
            false
        }
    }
    
    private fun tryAccModeKeepalive(): Boolean {
        return try {
            val sm = Class.forName("android.os.ServiceManager")
            val getService = sm.getMethod("getService", String::class.java)
            val binder = getService.invoke(null, "accmodemanager") as? IBinder ?: return false
            
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken("android.os.IAccModeManager")
                binder.transact(4, data, reply, 0)
                true
            } finally {
                data.recycle()
                reply.recycle()
            }
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Ping internet to keep WiFi radio in high-power state.
     */
    fun pingInternet(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec("ping -c 1 -W 1 8.8.8.8")
            val exitCode = process.waitFor()
            exitCode == 0
        } catch (e: Exception) {
            false
        }
    }
}
