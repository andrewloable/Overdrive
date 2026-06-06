package net.bladewatch.app.ui.fragment.recording

import net.bladewatch.app.auth.AuthManager
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL

internal class RecordingSettingsClient {

    fun fetchStatus(): RecordingStatus? {
        val jwt = getJwt() ?: return null
        val modeJson = httpGet("/api/recording/mode", jwt)
        val statsJson = httpGet("/api/recordings/stats", jwt)
        val mode = modeJson?.optString("mode", "UNKNOWN") ?: "UNKNOWN"
        val isRecording = modeJson?.optString("status") == "ok" && mode != "NONE"
        val normalToday = statsJson?.optInt("normalTodayCount", 0) ?: 0
        val proxToday = statsJson?.optInt("proximityTodayCount", 0) ?: 0
        return RecordingStatus(
            currentMode = mode,
            isRecording = isRecording,
            normalTodayCount = normalToday,
            proximityTodayCount = proxToday,
        )
    }

    fun fetchQuality(): RecordingQualitySettings? {
        val jwt = getJwt() ?: return null
        val json = httpGet("/api/settings/quality", jwt) ?: return null
        val q = json.optString("recordingQuality", json.optString("quality", "STANDARD"))
        val codec = json.optString("codec", "H264")
        return RecordingQualitySettings(
            quality = RecordingQuality.fromValue(q),
            codec = codec,
        )
    }

    fun fetchStorage(): RecordingStorageSettings? {
        val jwt = getJwt() ?: return null
        val json = httpGet("/api/settings/storage", jwt) ?: return null
        return RecordingStorageSettings(
            storageType = json.optString("recordingsStorageType", "INTERNAL"),
            limitMb = json.optLong("recordingsLimitMb", 500),
            recordingsSize = json.optLong("recordingsSize", 0),
            recordingsCount = json.optLong("recordingsCount", 0),
            sdCardAvailable = json.optBoolean("sdCardAvailable", false),
            sdCardFreeFormatted = json.optString("sdCardFreeFormatted", ""),
            internalFreeFormatted = json.optString("internalFreeFormatted", ""),
            recordingsPath = json.optString("recordingsPath", ""),
        )
    }

    fun saveMode(mode: String): Boolean {
        val jwt = getJwt() ?: return false
        val body = JSONObject().apply { put("mode", mode) }.toString()
        return httpPost("/api/recording/mode", body, jwt) == 200
    }

    fun saveQuality(quality: String, codec: String): Boolean {
        val jwt = getJwt() ?: return false
        val body = JSONObject().apply {
            put("recordingQuality", quality)
            put("codec", codec)
        }.toString()
        return httpPost("/api/settings/quality", body, jwt) == 200
    }

    fun saveStorage(storageType: String, limitMb: Long): Boolean {
        val jwt = getJwt() ?: return false
        val body = JSONObject().apply {
            put("recordingsStorageType", storageType)
            put("recordingsLimitMb", limitMb)
        }.toString()
        return httpPost("/api/settings/storage", body, jwt) == 200
    }

    private fun getJwt(): String? = runCatching {
        if (AuthManager.getState() == null) AuthManager.initialize()
        AuthManager.generateJwt()?.takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun httpGet(path: String, jwt: String): JSONObject? = runCatching {
        val conn = URL("http://127.0.0.1:8080$path").openConnection(Proxy.NO_PROXY) as HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("Authorization", "Bearer $jwt")
        conn.connectTimeout = 5_000; conn.readTimeout = 10_000
        if (conn.responseCode != 200) return null
        JSONObject(conn.inputStream.bufferedReader().readText())
    }.getOrNull()

    private fun httpPost(path: String, body: String, jwt: String): Int = runCatching {
        val conn = URL("http://127.0.0.1:8080$path").openConnection(Proxy.NO_PROXY) as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Authorization", "Bearer $jwt")
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true; conn.connectTimeout = 5_000; conn.readTimeout = 10_000
        conn.outputStream.use { it.write(body.toByteArray()) }
        conn.responseCode
    }.getOrDefault(-1)
}
