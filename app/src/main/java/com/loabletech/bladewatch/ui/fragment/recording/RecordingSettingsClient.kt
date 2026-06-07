package net.bladewatch.app.ui.fragment.recording

import net.bladewatch.app.auth.AuthManager
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL

internal data class FormattableVolume(val volumeId: String, val uuid: String?, val mountPath: String?)
internal data class FormatDriveResult(val success: Boolean, val message: String, val mountPath: String?)

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
        // Daemon GET returns the codec under "recordingCodec" (see
        // QualitySettingsApiHandler.sendQualitySettings). Reading "codec" here
        // always missed it and silently fell back to H264.
        val codec = json.optString("recordingCodec", json.optString("codec", "H264"))
        return RecordingQualitySettings(
            quality = RecordingQuality.fromValue(q),
            codec = codec,
            segmentMinutes = json.optInt("recordingSegmentMinutes", 5),
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
            minLimitMb = json.optLong("minLimitMb", 100),
            maxLimitMb = json.optLong("maxLimitMb", 100000),
            maxLimitMbSdCard = json.optLong("maxLimitMbSdCard", 100000),
            internalTotalMb = json.optLong("internalTotalSpace", 0) / (1024L * 1024L),
            sdCardTotalMb = json.optLong("sdCardTotalSpace", 0) / (1024L * 1024L),
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
            // Daemon POST handler reads "recordingCodec" (not "codec"), so
            // sending "codec" meant the codec change was silently dropped and
            // H265 never persisted.
            put("recordingCodec", codec)
        }.toString()
        return httpPost("/api/settings/quality", body, jwt) == 200
    }

    fun saveRecordingLimit(segmentMinutes: Int): Boolean {
        val jwt = getJwt() ?: return false
        val body = JSONObject().apply { put("recordingSegmentMinutes", segmentMinutes) }.toString()
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

    fun listFormattableVolumes(): List<FormattableVolume> {
        val jwt = getJwt() ?: return emptyList()
        val json = httpGet("/api/storage/format", jwt) ?: return emptyList()
        val arr = json.optJSONArray("volumes") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val v = arr.optJSONObject(i) ?: return@mapNotNull null
            if (!v.optBoolean("mounted", false)) return@mapNotNull null
            FormattableVolume(
                volumeId = v.optString("volumeId"),
                uuid = v.optString("uuid").takeIf { it != "null" && it.isNotEmpty() },
                mountPath = v.optString("mountPath").takeIf { it != "null" && it.isNotEmpty() }
            )
        }
    }

    fun formatVolume(volumeId: String): FormatDriveResult {
        val jwt = getJwt() ?: return FormatDriveResult(false, "Not authenticated", null)
        val body = JSONObject().apply { put("volumeId", volumeId) }.toString()
        return runCatching {
            val conn = java.net.URL("http://127.0.0.1:8080/api/storage/format")
                .openConnection(java.net.Proxy.NO_PROXY) as java.net.HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Authorization", "Bearer $jwt")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.connectTimeout = 5_000
            conn.readTimeout = 60_000  // format can take up to 30s
            conn.outputStream.use { it.write(body.toByteArray()) }
            val responseBody = try { conn.inputStream.bufferedReader().readText() }
                               catch (_: Exception) { conn.errorStream?.bufferedReader()?.readText() ?: "" }
            val json = JSONObject(responseBody)
            FormatDriveResult(
                success = json.optBoolean("success", false),
                message = json.optString("message", json.optString("error", "Unknown result")),
                mountPath = json.optString("mountPath").takeIf { it != "null" && it.isNotEmpty() }
            )
        }.getOrElse { e -> FormatDriveResult(false, e.message ?: "Network error", null) }
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
