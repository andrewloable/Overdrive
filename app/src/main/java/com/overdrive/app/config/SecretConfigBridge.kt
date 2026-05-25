package com.overdrive.app.config

import com.overdrive.app.client.CameraDaemonClient
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Accessor that prefers direct file access when the current process owns the
 * daemon secret store, and falls back to localhost IPC when it does not.
 */
object SecretConfigBridge {

    private val directStore = SecretConfigStore()
    private val lock = Any()
    private val ipcExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "secret-config-ipc").apply { isDaemon = true }
    }

    @JvmStatic
    fun getString(section: String, key: String): String? = synchronized(lock) {
        try {
            if (directStore.canReadDirectly()) {
                return directStore.getString(section, key)
            }
        } catch (_: Exception) {
            // Fall through to IPC.
        }
        return readViaIpc(section, key)
    }

    @JvmStatic
    fun getLong(section: String, key: String, defaultValue: Long = 0L): Long = synchronized(lock) {
        try {
            if (directStore.canReadDirectly()) {
                return directStore.getLong(section, key, defaultValue)
            }
        } catch (_: Exception) {
            // Fall through to IPC.
        }
        return readLongViaIpc(section, key, defaultValue)
    }

    @JvmStatic
    fun getBoolean(section: String, key: String, defaultValue: Boolean = false): Boolean = synchronized(lock) {
        try {
            if (directStore.canReadDirectly()) {
                return directStore.getBoolean(section, key, defaultValue)
            }
        } catch (_: Exception) {
            // Fall through to IPC.
        }
        return readBooleanViaIpc(section, key, defaultValue)
    }

    @JvmStatic
    fun loadSection(section: String): JSONObject = synchronized(lock) {
        try {
            if (directStore.canReadDirectly()) {
                return directStore.loadSection(section)
            }
        } catch (_: Exception) {
            // Fall through to IPC.
        }
        return readSectionViaIpc(section)
    }

    @JvmStatic
    fun putString(section: String, key: String, value: String?): Boolean = synchronized(lock) {
        try {
            if (directStore.canWriteDirectly()) {
                if (directStore.putString(section, key, value)) return true
            }
        } catch (_: Exception) {
            // Fall through to IPC.
        }
        return writeViaIpc(section, key, value, "put")
    }

    @JvmStatic
    fun putLong(section: String, key: String, value: Long): Boolean = synchronized(lock) {
        try {
            if (directStore.canWriteDirectly()) {
                if (directStore.putLong(section, key, value)) return true
            }
        } catch (_: Exception) {
            // Fall through to IPC.
        }
        return writeViaIpc(section, key, value, "put")
    }

    @JvmStatic
    fun putBoolean(section: String, key: String, value: Boolean): Boolean = synchronized(lock) {
        try {
            if (directStore.canWriteDirectly()) {
                if (directStore.putBoolean(section, key, value)) return true
            }
        } catch (_: Exception) {
            // Fall through to IPC.
        }
        return writeViaIpc(section, key, value, "put")
    }

    @JvmStatic
    fun delete(section: String, key: String): Boolean = synchronized(lock) {
        try {
            if (directStore.canWriteDirectly()) {
                if (directStore.delete(section, key)) return true
            }
        } catch (_: Exception) {
            // Fall through to IPC.
        }
        return writeViaIpc(section, key, null, "delete")
    }

    private fun readViaIpc(section: String, key: String): String? {
        return runIpcBlocking(null) {
            readViaIpcOnCurrentThread(section, key)
        }
    }

    private fun readViaIpcOnCurrentThread(section: String, key: String): String? {
        val client = CameraDaemonClient()
        return try {
            if (!client.connect()) return null
            val cmd = JSONObject()
                .put("cmd", "secret_get")
                .put("section", section)
                .put("key", key)
            val response = client.sendCommand(cmd)
            if (!"ok".equals(response.optString("status"), ignoreCase = true)) return null
            val value = response.optString("value", "")
            if (value.isEmpty()) null else value
        } catch (_: Exception) {
            null
        } finally {
            client.disconnect()
        }
    }

    private fun readLongViaIpc(section: String, key: String, defaultValue: Long): Long {
        val value = readViaIpc(section, key) ?: return defaultValue
        return value.toLongOrNull() ?: defaultValue
    }

    private fun readBooleanViaIpc(section: String, key: String, defaultValue: Boolean): Boolean {
        val value = readViaIpc(section, key) ?: return defaultValue
        return when (value.lowercase()) {
            "true", "1", "yes", "on" -> true
            "false", "0", "no", "off" -> false
            else -> defaultValue
        }
    }

    private fun readSectionViaIpc(section: String): JSONObject {
        return runIpcBlocking(JSONObject()) {
            readSectionViaIpcOnCurrentThread(section)
        }
    }

    private fun readSectionViaIpcOnCurrentThread(section: String): JSONObject {
        val client = CameraDaemonClient()
        return try {
            if (!client.connect()) return JSONObject()
            val cmd = JSONObject()
                .put("cmd", "secret_get_section")
                .put("section", section)
            val response = client.sendCommand(cmd)
            if (!"ok".equals(response.optString("status"), ignoreCase = true)) return JSONObject()
            response.optJSONObject("section")?.let { JSONObject(it.toString()) } ?: JSONObject()
        } catch (_: Exception) {
            JSONObject()
        } finally {
            client.disconnect()
        }
    }

    private fun writeViaIpc(section: String, key: String, value: Any?, action: String): Boolean {
        return runIpcBlocking(false) {
            writeViaIpcOnCurrentThread(section, key, value, action)
        }
    }

    private fun writeViaIpcOnCurrentThread(section: String, key: String, value: Any?, action: String): Boolean {
        val command = JSONObject()
            .put("cmd", when (action) {
                "delete" -> "secret_delete"
                else -> "secret_put"
            })
            .put("section", section)
            .put("key", key)
        if (action != "delete" && value != null) {
            command.put("value", value)
        }

        var lastError: String? = null
        for (attempt in 0 until 3) {
            val client = CameraDaemonClient()
            try {
                if (!client.connect()) {
                    lastError = "connect failed"
                } else {
                    val response = client.sendCommand(command)
                    if ("ok".equals(response.optString("status"), ignoreCase = true)) {
                        return true
                    }
                    lastError = response.optString("message", "daemon returned error")
                }
            } catch (e: Exception) {
                lastError = e.message ?: e.javaClass.simpleName
            } finally {
                client.disconnect()
            }
            if (attempt < 2) {
                try {
                    Thread.sleep(150L * (attempt + 1))
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }
        Log.w("SecretConfigBridge", "IPC secret $action failed for $section.$key: ${lastError ?: "unknown"}")
        return false
    }

    private fun <T> runIpcBlocking(defaultValue: T, block: () -> T): T {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            return block()
        }

        return try {
            ipcExecutor.submit<T> { block() }.get(6, TimeUnit.SECONDS)
        } catch (e: Exception) {
            Log.w("SecretConfigBridge", "IPC secret operation failed on worker: ${e.message ?: e.javaClass.simpleName}")
            defaultValue
        }
    }
}
