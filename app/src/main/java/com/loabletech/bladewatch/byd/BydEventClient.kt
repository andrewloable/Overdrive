package com.overdrive.app.byd

import com.overdrive.app.logging.LogManager
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Client that connects to BydEventDaemon and dispatches events to listeners.
 * 
 * Usage:
 *   BydEventClient.addListener(myListener)
 *   BydEventClient.connect()
 */
object BydEventClient {
    
    private const val TAG = "BydEventClient"
    private const val DAEMON_HOST = "127.0.0.1"
    private const val DAEMON_PORT = 19878
    private const val RECONNECT_DELAY_MS = 5000L
    
    private val logger = LogManager.getInstance()
    private val executor = Executors.newSingleThreadExecutor()
    private val running = AtomicBoolean(false)
    private var socket: Socket? = null
    private var writer: PrintWriter? = null
    
    private val listeners = CopyOnWriteArrayList<EventListener>()
    
    private fun log(msg: String) {
        logger.info(TAG, msg)
    }
    
    // ==================== LISTENER INTERFACE ====================
    
    interface EventListener {
        /** Called when connected to daemon */
        fun onConnected() {}
        
        /** Called when disconnected from daemon */
        fun onDisconnected() {}
        
        /** Called when power level changes (OFF=0, ACC=1, ON=2) */
        fun onPowerLevelChanged(level: Int, levelName: String) {}
        
        /** Called when radar detects obstacle */
        fun onRadarEvent(area: Int, areaName: String, state: Int, stateName: String) {}
        
        /** Called when door state changes */
        fun onDoorEvent(area: Int, open: Boolean) {}
        
        /** Called when window state changes */
        fun onWindowEvent(area: Int, open: Boolean) {}
        
        /** Called when alarm triggers */
        fun onAlarmEvent(active: Boolean) {}
        
        /** Called when battery voltage level changes (LOW/NORMAL/INVALID) */
        fun onBatteryVoltageLevelChanged(level: Int, levelName: String) {}
        
        /** Called with battery info (voltage level + actual voltage) */
        fun onBatteryInfo(voltageLevel: Int, voltageLevelName: String, voltage: Double) {}
        
        /** Called for any event (raw JSON) */
        fun onRawEvent(event: JSONObject) {}
    }
    
    // ==================== PUBLIC API ====================
    
    fun addListener(listener: EventListener) {
        listeners.add(listener)
    }
    
    fun removeListener(listener: EventListener) {
        listeners.remove(listener)
    }
    
    fun connect() {
        if (running.getAndSet(true)) {
            log("Already running")
            return
        }
        executor.execute { connectionLoop() }
    }
    
    fun disconnect() {
        running.set(false)
        socket?.close()
    }
    
    fun isConnected(): Boolean = socket?.isConnected == true && !socket!!.isClosed
    
    /** Send a command to the daemon */
    fun sendCommand(cmd: String): Boolean {
        return try {
            writer?.println("""{"cmd":"$cmd"}""")
            true
        } catch (e: Exception) {
            log("Send failed: ${e.message}")
            false
        }
    }
    
    // ==================== CONNECTION LOOP ====================
    
    private fun connectionLoop() {
        log("Connection loop started")
        
        while (running.get()) {
            try {
                log("Connecting to daemon at $DAEMON_HOST:$DAEMON_PORT...")
                socket = Socket(DAEMON_HOST, DAEMON_PORT).apply {
                    soTimeout = 0 // No read timeout for event stream
                }
                writer = PrintWriter(socket!!.outputStream, true)
                val reader = BufferedReader(InputStreamReader(socket!!.inputStream))
                
                log("Connected to BydEventDaemon!")
                notifyConnected()
                
                // Read events
                var line: String? = null
                while (running.get() && reader.readLine().also { line = it } != null) {
                    try {
                        val event = JSONObject(line!!)
                        dispatchEvent(event)
                    } catch (e: Exception) {
                        log("Failed to parse event: $line - ${e.message}")
                    }
                }
                
            } catch (e: Exception) {
                log("Connection error: ${e.message}")
            } finally {
                socket?.close()
                socket = null
                writer = null
                notifyDisconnected()
            }
            
            // Reconnect after delay
            if (running.get()) {
                log("Reconnecting in ${RECONNECT_DELAY_MS}ms...")
                Thread.sleep(RECONNECT_DELAY_MS)
            }
        }
        
        log("Connection loop ended")
    }
    
    // ==================== EVENT DISPATCH ====================
    
    private fun dispatchEvent(event: JSONObject) {
        val type = event.optString("type", "")
        log("Event received: $type - $event")
        
        // Notify raw event
        listeners.forEach { it.onRawEvent(event) }
        
        // Dispatch typed events
        when (type) {
            "powerLevel" -> {
                val level = event.optInt("level", -1)
                val levelName = event.optString("levelName", "UNKNOWN")
                listeners.forEach { it.onPowerLevelChanged(level, levelName) }
            }
            
            "radar" -> {
                val area = event.optInt("area", -1)
                val areaName = event.optString("areaName", "")
                val state = event.optInt("state", -1)
                val stateName = event.optString("stateName", "")
                listeners.forEach { it.onRadarEvent(area, areaName, state, stateName) }
            }
            
            "door" -> {
                val area = event.optInt("area", -1)
                val open = event.optBoolean("open", false)
                listeners.forEach { it.onDoorEvent(area, open) }
            }
            
            "window" -> {
                val area = event.optInt("area", -1)
                val open = event.optBoolean("open", false)
                listeners.forEach { it.onWindowEvent(area, open) }
            }
            
            "alarm" -> {
                val active = event.optBoolean("active", false)
                listeners.forEach { it.onAlarmEvent(active) }
            }
            
            "state" -> {
                // Initial state on connect - contains powerLevel, radar states, and battery info
                val level = event.optInt("powerLevel", -1)
                val levelName = event.optString("powerLevelName", "UNKNOWN")
                if (level >= 0) {
                    listeners.forEach { it.onPowerLevelChanged(level, levelName) }
                }
                // Also dispatch battery info from initial state
                val batteryVoltageLevel = event.optInt("batteryVoltageLevel", -1)
                val batteryVoltageLevelName = event.optString("batteryVoltageLevelName", "UNKNOWN")
                val batteryVoltage = event.optDouble("batteryVoltage", 0.0)
                if (batteryVoltageLevel >= 0 || batteryVoltage > 0) {
                    listeners.forEach { it.onBatteryInfo(batteryVoltageLevel, batteryVoltageLevelName, batteryVoltage) }
                }
            }
            
            "batteryVoltage" -> {
                val level = event.optInt("level", -1)
                val levelName = event.optString("levelName", "UNKNOWN")
                listeners.forEach { it.onBatteryVoltageLevelChanged(level, levelName) }
            }
            
            "batteryInfo" -> {
                val voltageLevel = event.optInt("voltageLevel", -1)
                val voltageLevelName = event.optString("voltageLevelName", "UNKNOWN")
                val voltage = event.optDouble("voltage", 0.0)
                listeners.forEach { it.onBatteryInfo(voltageLevel, voltageLevelName, voltage) }
            }
        }
    }
    
    private fun notifyConnected() {
        listeners.forEach { it.onConnected() }
    }
    
    private fun notifyDisconnected() {
        listeners.forEach { it.onDisconnected() }
    }
}
