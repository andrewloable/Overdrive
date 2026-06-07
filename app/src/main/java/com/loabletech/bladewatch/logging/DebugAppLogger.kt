package net.bladewatch.app.logging

import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Persistent debug logger for the Android UI app process.
 *
 * When enabled (via the Developer debug logs toggle in Privacy & data settings),
 * logs Activity/Fragment lifecycle events and startup steps to a rolling file at
 * /storage/emulated/0/BladeWatch/data/debug_app.log.
 *
 * Crash capture is unconditional — uncaught exceptions always land in the file
 * so that a crash report is available regardless of the toggle state.
 *
 * File rotation: 5 MB per file, 3 rotations kept.
 */
object DebugAppLogger {

    private const val TAG = "DebugAppLogger"
    private const val LOG_FILE = "/storage/emulated/0/BladeWatch/data/debug_app.log"
    private const val MAX_SIZE_BYTES = 5L * 1024 * 1024
    private const val MAX_ROTATIONS = 3

    @Volatile
    private var enabled = false

    private val writeLock = Any()
    private val timestampFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    /**
     * Pull the enabled state from [net.bladewatch.app.config.UnifiedConfigManager].
     * Safe to call before UnifiedConfigManager is fully initialised — any exception
     * leaves the logger disabled.
     */
    fun syncEnabled() {
        enabled = try {
            net.bladewatch.app.config.UnifiedConfigManager.isDebugLogsEnabled()
        } catch (_: Throwable) {
            false
        }
    }

    /** Update the in-memory flag (called from the settings switch). */
    fun setEnabled(value: Boolean) {
        enabled = value
        if (value) log("DebugAppLogger", "Developer debug logging enabled")
    }

    fun isEnabled(): Boolean = enabled

    // ── Public log helpers ──────────────────────────────────────────────────

    fun log(tag: String, message: String, level: String = "INFO") {
        if (!enabled) return
        writeLine("[${ timestampFmt.format(Date()) }] [$level] [$tag] $message")
    }

    fun debug(tag: String, message: String) = log(tag, message, "DEBUG")
    fun warn(tag: String, message: String) = log(tag, message, "WARN")
    fun error(tag: String, message: String) = log(tag, message, "ERROR")

    /**
     * Log a lifecycle event: e.g. logLifecycle("MainActivity", "onCreate").
     * Convenience wrapper that bundles class name + method into one line.
     */
    fun logLifecycle(className: String, method: String, extra: String = "") {
        if (!enabled) return
        val msg = if (extra.isEmpty()) "$className.$method()" else "$className.$method() [$extra]"
        writeLine("[${timestampFmt.format(Date())}] [LIFECYCLE] [App] $msg")
    }

    /**
     * Log an uncaught exception to the file.
     * Unconditional — always writes even when the toggle is off.
     */
    fun logCrash(thread: Thread, throwable: Throwable) {
        val sb = StringBuilder()
        val ts = timestampFmt.format(Date())
        @Suppress("DEPRECATION")
        sb.appendLine("[$ts] [CRASH] [UncaughtException] Thread: ${thread.name} (${thread.id})")
        appendThrowable(sb, throwable)
        var cause = throwable.cause
        while (cause != null) {
            sb.append("Caused by: ")
            appendThrowable(sb, cause)
            cause = cause.cause
        }
        writeLine(sb.toString().trimEnd())
        Log.e(TAG, "Uncaught exception logged to $LOG_FILE")
    }

    // ── Private helpers ─────────────────────────────────────────────────────

    private fun appendThrowable(sb: StringBuilder, t: Throwable) {
        sb.appendLine("${t.javaClass.name}: ${t.message}")
        t.stackTrace.forEach { sb.appendLine("  at $it") }
    }

    private fun writeLine(line: String) {
        synchronized(writeLock) {
            try {
                val file = File(LOG_FILE)
                file.parentFile?.mkdirs()
                if (file.exists() && file.length() >= MAX_SIZE_BYTES) {
                    rotate(file)
                }
                FileOutputStream(file, true).use { fos ->
                    OutputStreamWriter(fos, Charsets.UTF_8).use { w ->
                        w.write(line)
                        w.write("\n")
                        w.flush()
                    }
                }
            } catch (_: Throwable) {
                // Never throw from a logger — silent fallback to logcat only.
                try { Log.w(TAG, "Failed to write debug log: $line") } catch (_: Throwable) {}
            }
        }
    }

    private fun rotate(file: File) {
        try {
            for (i in MAX_ROTATIONS downTo 1) {
                val old = File("$LOG_FILE.$i")
                when {
                    i == MAX_ROTATIONS -> old.delete()
                    old.exists() -> old.renameTo(File("$LOG_FILE.${i + 1}"))
                }
            }
            file.renameTo(File("$LOG_FILE.1"))
        } catch (_: Throwable) {}
    }
}
