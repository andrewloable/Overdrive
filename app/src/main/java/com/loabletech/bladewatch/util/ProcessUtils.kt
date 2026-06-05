package com.overdrive.app.util

import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Process management utilities.
 */
object ProcessUtils {
    
    /**
     * Check if a process with given name is running.
     * @param processName Process name to search for
     * @return PID if running, null otherwise
     */
    fun findProcessByName(processName: String): Int? {
        return try {
            val process = Runtime.getRuntime().exec("ps -A")
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            
            reader.useLines { lines ->
                lines.forEach { line ->
                    if (line.contains(processName)) {
                        // Parse PID from ps output
                        val parts = line.trim().split(Regex("\\s+"))
                        if (parts.size >= 2) {
                            return parts[1].toIntOrNull()
                        }
                    }
                }
            }
            
            null
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Kill process by PID.
     */
    fun killProcess(pid: Int): Boolean {
        return try {
            val process = Runtime.getRuntime().exec("kill $pid")
            process.waitFor() == 0
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Check if process with PID is running.
     */
    fun isProcessRunning(pid: Int): Boolean {
        return try {
            val process = Runtime.getRuntime().exec("kill -0 $pid")
            process.waitFor() == 0
        } catch (e: Exception) {
            false
        }
    }
}
