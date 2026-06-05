package com.overdrive.app.logging

import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Centralized logging manager for app-context code (Kotlin).
 * 
 * App logs are written to the app's cache directory.
 * Daemon logs (via DaemonLogger.java) go to /data/local/tmp.
 */
class LogManager private constructor(private var config: LogConfig) {
    
    interface LogListener {
        fun onLog(tag: String, message: String, level: LogLevel)
    }
    
    companion object {
        @Volatile
        private var instance: LogManager? = null
        
        @Volatile
        private var logListener: LogListener? = null
        
        private const val TAG = "LogManager"
        
        fun getInstance(config: LogConfig = LogConfig.default()): LogManager {
            return instance ?: synchronized(this) {
                instance ?: LogManager(config).also { instance = it }
            }
        }
        
        fun setLogListener(listener: LogListener?) {
            logListener = listener
        }
        
        fun getLogListener(): LogListener? = logListener
    }
    
    private val writers = ConcurrentHashMap<String, PrintWriter>()
    private val fileSizes = ConcurrentHashMap<String, Long>()
    private val writeLock = Any()
    private val timestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    
    fun log(tag: String, message: String, level: LogLevel = LogLevel.INFO) {
        val timestamp = timestampFormat.format(Date())
        val logLine = "[$timestamp] [${level.name}] [$tag] $message"
        
        if (config.enableConsoleLog) {
            when (level) {
                LogLevel.DEBUG -> Log.d(tag, message)
                LogLevel.INFO -> Log.i(tag, message)
                LogLevel.WARN -> Log.w(tag, message)
                LogLevel.ERROR -> Log.e(tag, message)
            }
        }
        
        try {
            logListener?.onLog(tag, message, level)
        } catch (e: Exception) {
            // Ignore listener errors
        }
        
        if (config.enableFileLog && config.logDir.isNotEmpty()) {
            writeToFile(tag, logLine)
        }
    }
    
    private fun writeToFile(tag: String, logLine: String) {
        synchronized(writeLock) {
            try {
                val writer = getOrCreateWriter(tag)
                writer.println(logLine)
                writer.flush()
                
                val currentSize = fileSizes.getOrDefault(tag, 0L) + logLine.length + 1
                fileSizes[tag] = currentSize
                
                if (currentSize >= config.maxFileSizeMB * 1024 * 1024) {
                    rotateLogFile(tag)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write log: ${e.message}")
            }
        }
    }
    
    private fun getOrCreateWriter(tag: String): PrintWriter {
        return writers.getOrPut(tag) {
            val logFile = File(config.logDir, "${tag.lowercase()}.log")
            logFile.parentFile?.mkdirs()
            
            if (logFile.exists()) {
                fileSizes[tag] = logFile.length()
            }
            
            PrintWriter(logFile.outputStream().bufferedWriter(), true)
        }
    }
    
    private fun rotateLogFile(tag: String) {
        try {
            writers[tag]?.close()
            writers.remove(tag)
            
            val logFile = File(config.logDir, "${tag.lowercase()}.log")
            
            for (i in config.rotationCount downTo 1) {
                val oldFile = File(config.logDir, "${tag.lowercase()}.log.$i")
                if (i == config.rotationCount) {
                    oldFile.delete()
                } else {
                    val newFile = File(config.logDir, "${tag.lowercase()}.log.${i + 1}")
                    oldFile.renameTo(newFile)
                }
            }
            
            val rotatedFile = File(config.logDir, "${tag.lowercase()}.log.1")
            logFile.renameTo(rotatedFile)
            
            fileSizes[tag] = 0L
            Log.i(TAG, "Rotated log file: ${tag.lowercase()}.log")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to rotate log file: ${e.message}")
        }
    }
    
    fun debug(tag: String, message: String) = log(tag, message, LogLevel.DEBUG)
    fun info(tag: String, message: String) = log(tag, message, LogLevel.INFO)
    fun warn(tag: String, message: String) = log(tag, message, LogLevel.WARN)
    
    fun error(tag: String, message: String, throwable: Throwable? = null) {
        val fullMessage = if (throwable != null) {
            "$message: ${throwable.message}"
        } else {
            message
        }
        log(tag, fullMessage, LogLevel.ERROR)
    }
    
    fun updateConfig(newConfig: LogConfig) {
        synchronized(writeLock) {
            if (newConfig.isValid()) {
                this.config = newConfig
            }
        }
    }
    
    fun getConfig(): LogConfig = config
    
    // Cleanup stats
    @Volatile
    private var cleanupStats = CleanupStats(0, 0, 0)
    
    /**
     * Get cleanup statistics.
     */
    fun getCleanupStats(): CleanupStats = cleanupStats
    
    /**
     * Update cleanup statistics (called by LogCleaner).
     */
    internal fun updateCleanupStats(stats: CleanupStats) {
        this.cleanupStats = stats
    }
    
    fun stop() {
        synchronized(writeLock) {
            writers.values.forEach { it.close() }
            writers.clear()
            fileSizes.clear()
        }
    }
}

data class CleanupStats(
    val lastCleanupTime: Long,
    val filesDeleted: Int,
    val spaceFreeKB: Long
)
