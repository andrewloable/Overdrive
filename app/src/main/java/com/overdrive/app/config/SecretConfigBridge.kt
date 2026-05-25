package com.overdrive.app.config

import com.overdrive.app.client.CameraDaemonClient
import org.json.JSONObject

/**
 * Accessor that prefers direct file access when the current process owns the
 * daemon secret store, and falls back to localhost IPC when it does not.
 */
object SecretConfigBridge {

    private val directStore = SecretConfigStore()
    private val lock = Any()

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
        val client = CameraDaemonClient()
        return try {
            if (!client.connect()) return false
            val cmd = JSONObject()
                .put("cmd", when (action) {
                    "delete" -> "secret_delete"
                    else -> "secret_put"
                })
                .put("section", section)
                .put("key", key)
            if (action != "delete" && value != null) {
                cmd.put("value", value)
            }
            val response = client.sendCommand(cmd)
            "ok".equals(response.optString("status"), ignoreCase = true)
        } catch (_: Exception) {
            false
        } finally {
            client.disconnect()
        }
    }
}
