package com.overdrive.app.daemon.sentry

import com.overdrive.app.logging.DaemonLogger
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Controls recording operations in sentry mode.
 * 
 * Manages camera daemon communication for starting/stopping recordings.
 * 
 * Extracted from SentryDaemon for better separation of concerns.
 */
class RecordingController {
    
    companion object {
        private val logger = DaemonLogger.getInstance("RecordingController")
        
        private const val CAMERA_DAEMON_PORT = 19876
    }
    
    /**
     * Start recording on a specific camera.
     */
    fun startRecording(cameraId: Int) {
        logger.info("Starting recording on camera $cameraId")
        sendCameraCommand("start", cameraId, viewOnly = false)
    }
    
    /**
     * Stop recording on a specific camera.
     */
    fun stopRecording(cameraId: Int) {
        logger.info("Stopping recording on camera $cameraId")
        sendCameraCommand("stop", cameraId)
    }
    
    /**
     * Start recording on all cameras.
     */
    fun startAllRecording() {
        logger.info("Starting recording on all cameras")
        for (i in 1..4) {
            startRecording(i)
        }
    }
    
    /**
     * Stop recording on all cameras.
     */
    fun stopAllRecording() {
        logger.info("Stopping recording on all cameras")
        for (i in 1..4) {
            stopRecording(i)
        }
    }
    
    /**
     * Start view-only mode (no recording) on a camera.
     */
    fun startViewOnly(cameraId: Int) {
        logger.info("Starting view-only on camera $cameraId")
        sendCameraCommand("start", cameraId, viewOnly = true)
    }
    
    /**
     * Send command to camera daemon.
     */
    private fun sendCameraCommand(action: String, cameraId: Int, viewOnly: Boolean = false) {
        try {
            val json = buildString {
                append("{")
                append("\"action\":\"$action\"")
                append(",\"camera\":$cameraId")
                if (action == "start") {
                    append(",\"viewOnly\":$viewOnly")
                }
                append("}")
            }
            
            // Send via TCP to camera daemon
            val result = execShell(
                "echo '$json' | nc -w 2 localhost $CAMERA_DAEMON_PORT 2>&1"
            )
            
            if (result.contains("error") || result.contains("refused")) {
                logger.warn("Camera command may have failed: $result")
            } else {
                logger.debug("Camera command sent: $json -> $result")
            }
        } catch (e: Exception) {
            logger.error("Failed to send camera command", e)
        }
    }
    
    /**
     * Check if camera daemon is running.
     */
    fun isCameraDaemonRunning(): Boolean {
        val result = execShell("ps -ef | grep byd_cam_daemon | grep -v grep")
        return result.isNotEmpty()
    }
    
    /**
     * Get recording status from camera daemon.
     */
    fun getRecordingStatus(): Map<Int, Boolean> {
        val status = mutableMapOf<Int, Boolean>()
        try {
            val json = "{\"action\":\"status\"}"
            val result = execShell(
                "echo '$json' | nc -w 2 localhost $CAMERA_DAEMON_PORT 2>&1"
            )
            
            // Parse response (simplified - assumes JSON response)
            for (i in 1..4) {
                status[i] = result.contains("\"cam$i\":true") || 
                           result.contains("\"recording\":[$i")
            }
        } catch (e: Exception) {
            logger.error("Failed to get recording status", e)
        }
        return status
    }
    
    /**
     * Execute shell command.
     */
    private fun execShell(cmd: String): String {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
            process.waitFor()
            
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                output.append(line).append("\n")
            }
            output.toString().trim()
        } catch (e: Exception) {
            "ERROR: ${e.message}"
        }
    }
}
