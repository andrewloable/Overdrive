package net.bladewatch.app.ui.fragment.surveillance

import net.bladewatch.app.auth.AuthManager
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL

internal class SurveillanceSettingsClient {

    fun fetchConfig(): SurveillanceConfig? {
        val jwt = getJwt() ?: return null
        val json = httpGet("/api/surveillance/config", jwt) ?: return null
        val cfg = json.optJSONObject("config") ?: json
        return SurveillanceConfig(
            enabled = cfg.optBoolean("enabled", false),
            environmentPreset = cfg.optString("environmentPreset", "OUTDOOR"),
            sensitivityLevel = cfg.optInt("sensitivityLevel", cfg.optInt("sensitivity", 3)),
            detectPerson = cfg.optBoolean("detectPerson", true),
            detectCar = cfg.optBoolean("detectCar", true),
            detectBike = cfg.optBoolean("detectBike", true),
            preRecordSeconds = cfg.optInt("preRecordSeconds", 5),
            postRecordSeconds = cfg.optInt("postRecordSeconds", 10),
            nightMode = cfg.optBoolean("nightMode", false),
            aiEnabled = cfg.optBoolean("aiEnabled", true),
            aiConfidence = cfg.optDouble("aiConfidence", 0.4).toFloat(),
            cameraFront = cfg.optBoolean("cameraFront", true),
            cameraRight = cfg.optBoolean("cameraRight", true),
            cameraRear = cfg.optBoolean("cameraRear", true),
            cameraLeft = cfg.optBoolean("cameraLeft", true),
            deterrentAction = cfg.optString("deterrentAction", "silent"),
            deterrentCooldownSeconds = cfg.optInt("deterrentCooldownSeconds", 60),
        )
    }

    fun fetchStatus(): SurveillanceStatus? {
        val jwt = getJwt() ?: return null
        val statusJson = httpGet("/api/surveillance/status", jwt)
        val statsJson = httpGet("/api/recordings/stats", jwt)
        val isRunning = statusJson?.optBoolean("running", false) ?: false
        val eventsToday = statsJson?.optInt("sentryTodayCount", 0) ?: 0
        return SurveillanceStatus(isRunning = isRunning, eventsToday = eventsToday)
    }

    fun fetchStorage(): SurveillanceStorageSettings? {
        val jwt = getJwt() ?: return null
        val json = httpGet("/api/settings/storage", jwt) ?: return null
        return SurveillanceStorageSettings(
            storageType = json.optString("surveillanceStorageType", "INTERNAL"),
            limitMb = json.optLong("surveillanceLimitMb", 500),
            surveillanceSize = json.optLong("surveillanceSize", 0),
            surveillanceCount = json.optLong("surveillanceCount", 0),
            sdCardAvailable = json.optBoolean("sdCardAvailable", false),
            path = json.optString("surveillancePath", ""),
            minLimitMb = json.optLong("minLimitMb", 100),
            maxLimitMb = json.optLong("maxLimitMb", 100000),
            maxLimitMbSdCard = json.optLong("maxLimitMbSdCard", 100000),
            internalTotalMb = json.optLong("internalTotalSpace", 0) / (1024L * 1024L),
            sdCardTotalMb = json.optLong("sdCardTotalSpace", 0) / (1024L * 1024L),
        )
    }

    fun fetchSafeLocations(): SafeLocationsState? {
        val jwt = getJwt() ?: return null
        val json = httpGet("/api/surveillance/safe-locations", jwt) ?: return null
        val featureEnabled = json.optBoolean("featureEnabled", false)
        val hasGps = json.optBoolean("hasGps", false)
        val lat = json.optDouble("lat", 0.0)
        val lng = json.optDouble("lng", 0.0)
        val zonesArr = json.optJSONArray("zones")
        val zones = mutableListOf<SafeZone>()
        if (zonesArr != null) {
            for (i in 0 until zonesArr.length()) {
                val z = zonesArr.getJSONObject(i)
                zones.add(SafeZone(
                    id = z.optString("id"),
                    name = z.optString("name", "Unnamed"),
                    lat = z.optDouble("lat"),
                    lng = z.optDouble("lng"),
                    radiusM = z.optInt("radiusM", 150),
                ))
            }
        }
        return SafeLocationsState(featureEnabled, zones, hasGps, lat, lng)
    }

    fun saveConfig(config: SurveillanceConfig): Boolean {
        val jwt = getJwt() ?: return false
        val body = JSONObject().apply {
            put("enabled", config.enabled)
            put("environmentPreset", config.environmentPreset)
            put("sensitivityLevel", config.sensitivityLevel)
            put("detectPerson", config.detectPerson)
            put("detectCar", config.detectCar)
            put("detectBike", config.detectBike)
            put("preRecordSeconds", config.preRecordSeconds)
            put("postRecordSeconds", config.postRecordSeconds)
            put("nightMode", config.nightMode)
            put("aiEnabled", config.aiEnabled)
            put("aiConfidence", config.aiConfidence)
            put("cameraFront", config.cameraFront)
            put("cameraRight", config.cameraRight)
            put("cameraRear", config.cameraRear)
            put("cameraLeft", config.cameraLeft)
            put("deterrentAction", config.deterrentAction)
            put("deterrentCooldownSeconds", config.deterrentCooldownSeconds)
        }.toString()
        return httpPost("/api/surveillance/config", body, jwt) == 200
    }

    fun toggleSurveillance(enable: Boolean): Boolean {
        val jwt = getJwt() ?: return false
        val path = if (enable) "/api/surveillance/enable" else "/api/surveillance/disable"
        return httpPost(path, "", jwt) == 200
    }

    fun saveStorage(storageType: String, limitMb: Long): Boolean {
        val jwt = getJwt() ?: return false
        val body = JSONObject().apply {
            put("surveillanceStorageType", storageType)
            put("surveillanceLimitMb", limitMb)
        }.toString()
        return httpPost("/api/settings/storage", body, jwt) == 200
    }

    fun toggleSafeLocations(enabled: Boolean): Boolean {
        val jwt = getJwt() ?: return false
        val body = JSONObject().apply { put("enabled", enabled) }.toString()
        return httpPost("/api/surveillance/safe-locations/toggle", body, jwt) == 200
    }

    fun addSafeZone(name: String, lat: Double, lng: Double, radiusM: Int): SafeZone? {
        val jwt = getJwt() ?: return null
        val body = JSONObject().apply {
            put("name", name)
            put("lat", lat)
            put("lng", lng)
            put("radiusM", radiusM)
        }.toString()
        val resp = httpPostJson("/api/surveillance/safe-locations", body, jwt) ?: return null
        val z = resp.optJSONObject("zone") ?: return null
        return SafeZone(z.optString("id"), z.optString("name", "Unnamed"), z.optDouble("lat"), z.optDouble("lng"), z.optInt("radiusM", 150))
    }

    fun deleteSafeZone(id: String): Boolean {
        val jwt = getJwt() ?: return false
        return httpDelete("/api/surveillance/safe-locations?id=$id", jwt) in 200..299
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
        if (body.isNotEmpty()) {
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
        }
        conn.connectTimeout = 5_000; conn.readTimeout = 10_000
        if (body.isNotEmpty()) conn.outputStream.use { it.write(body.toByteArray()) }
        conn.responseCode
    }.getOrDefault(-1)

    private fun httpPostJson(path: String, body: String, jwt: String): JSONObject? = runCatching {
        val conn = URL("http://127.0.0.1:8080$path").openConnection(Proxy.NO_PROXY) as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Authorization", "Bearer $jwt")
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true; conn.connectTimeout = 5_000; conn.readTimeout = 10_000
        conn.outputStream.use { it.write(body.toByteArray()) }
        if (conn.responseCode !in 200..299) return null
        JSONObject(conn.inputStream.bufferedReader().readText())
    }.getOrNull()

    private fun httpDelete(path: String, jwt: String): Int = runCatching {
        val conn = URL("http://127.0.0.1:8080$path").openConnection(Proxy.NO_PROXY) as HttpURLConnection
        conn.requestMethod = "DELETE"
        conn.setRequestProperty("Authorization", "Bearer $jwt")
        conn.connectTimeout = 5_000; conn.readTimeout = 10_000
        conn.responseCode
    }.getOrDefault(-1)

    // ── Format external drive ── Shared with the Recording storage view; both
    // use the generic /api/storage/format endpoint (formats the physical SD/USB
    // drive, which both recordings and surveillance share). Reuses the
    // FormattableVolume / FormatDriveResult types from the recording package.
    fun listFormattableVolumes(): List<net.bladewatch.app.ui.fragment.recording.FormattableVolume> {
        val jwt = getJwt() ?: return emptyList()
        val json = httpGet("/api/storage/format", jwt) ?: return emptyList()
        val arr = json.optJSONArray("volumes") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val v = arr.optJSONObject(i) ?: return@mapNotNull null
            if (!v.optBoolean("mounted", false)) return@mapNotNull null
            net.bladewatch.app.ui.fragment.recording.FormattableVolume(
                volumeId = v.optString("volumeId"),
                uuid = v.optString("uuid").takeIf { it != "null" && it.isNotEmpty() },
                mountPath = v.optString("mountPath").takeIf { it != "null" && it.isNotEmpty() }
            )
        }
    }

    fun syncCatalog(): net.bladewatch.app.ui.fragment.recording.SyncResult {
        val jwt = getJwt()
            ?: return net.bladewatch.app.ui.fragment.recording.SyncResult(false, "Not authenticated")
        return runCatching {
            val conn = URL("http://127.0.0.1:8080/api/surveillance/sync")
                .openConnection(Proxy.NO_PROXY) as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Authorization", "Bearer $jwt")
            conn.connectTimeout = 5_000
            conn.readTimeout = 120_000
            val responseBody = try { conn.inputStream.bufferedReader().readText() }
                               catch (_: Exception) { conn.errorStream?.bufferedReader()?.readText() ?: "" }
            val json = JSONObject(responseBody)
            if (json.optBoolean("success", false)) {
                val added = json.optInt("added", 0)
                val updated = json.optInt("updated", 0)
                val removed = json.optInt("removed", 0)
                val total = json.optInt("total", 0)
                net.bladewatch.app.ui.fragment.recording.SyncResult(true, "Synced: +$added ~$updated -$removed ($total total)")
            } else {
                val error = json.optString("error", "Unknown error")
                if (error == "sync_in_progress") net.bladewatch.app.ui.fragment.recording.SyncResult(false, "Sync already in progress")
                else net.bladewatch.app.ui.fragment.recording.SyncResult(false, "Sync failed: $error")
            }
        }.getOrElse { e -> net.bladewatch.app.ui.fragment.recording.SyncResult(false, e.message ?: "Network error") }
    }

    fun formatVolume(volumeId: String): net.bladewatch.app.ui.fragment.recording.FormatDriveResult {
        val jwt = getJwt()
            ?: return net.bladewatch.app.ui.fragment.recording.FormatDriveResult(false, "Not authenticated", null)
        val body = JSONObject().apply { put("volumeId", volumeId) }.toString()
        return runCatching {
            val conn = URL("http://127.0.0.1:8080/api/storage/format")
                .openConnection(Proxy.NO_PROXY) as HttpURLConnection
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
            net.bladewatch.app.ui.fragment.recording.FormatDriveResult(
                success = json.optBoolean("success", false),
                message = json.optString("message", json.optString("error", "Unknown result")),
                mountPath = json.optString("mountPath").takeIf { it != "null" && it.isNotEmpty() }
            )
        }.getOrElse { e ->
            net.bladewatch.app.ui.fragment.recording.FormatDriveResult(false, e.message ?: "Network error", null)
        }
    }
}
