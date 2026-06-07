package net.bladewatch.app.ui.fragment.trips

import net.bladewatch.app.auth.AuthManager
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL

internal data class TripSyncResult(val success: Boolean, val message: String)

internal class TripsClient {

    fun fetchTrips(days: Int): List<TripItem> {
        val jwt = getJwt() ?: return emptyList()
        val json = httpGet("/api/trips?days=$days&limit=100", jwt) ?: return emptyList()
        val arr = json.optJSONArray("trips") ?: return emptyList()
        val result = mutableListOf<TripItem>()
        for (i in 0 until arr.length()) {
            val t = arr.getJSONObject(i)
            result.add(
                TripItem(
                    id = t.optLong("id"),
                    startTime = t.optLong("startTime"),
                    endTime = t.optLong("endTime"),
                    distanceKm = t.optDouble("distanceKm", 0.0),
                    durationSeconds = t.optInt("durationSeconds", 0),
                    overallScore = t.optInt("overallScore", 0),
                    tripCost = t.optDouble("tripCost", 0.0),
                    currency = t.optString("currency", ""),
                    energyKwh = t.optDouble("energyUsedKwh", 0.0),
                )
            )
        }
        return result
    }

    fun fetchTripDetail(tripId: Long): TripDetail? {
        val jwt = getJwt() ?: return null
        val json = httpGet("/api/trips/$tripId", jwt) ?: return null
        val t = json.optJSONObject("trip") ?: return null
        return TripDetail(
            id = t.optLong("id"),
            startTime = t.optLong("startTime"),
            endTime = t.optLong("endTime"),
            distanceKm = t.optDouble("distanceKm", 0.0),
            durationSeconds = t.optInt("durationSeconds", 0),
            avgSpeedKmh = t.optDouble("avgSpeedKmh", 0.0),
            maxSpeedKmh = t.optDouble("maxSpeedKmh", 0.0),
            socStart = t.optDouble("socStart", 0.0),
            socEnd = t.optDouble("socEnd", 0.0),
            energyUsedKwh = t.optDouble("energyUsedKwh", 0.0),
            efficiencySocPerKm = t.optDouble("efficiencySocPerKm", 0.0),
            currency = t.optString("currency", ""),
            tripCost = t.optDouble("tripCost", 0.0),
            gradientProfile = t.optString("gradientProfile", ""),
            elevationGainM = t.optDouble("elevationGainM", 0.0),
            elevationLossM = t.optDouble("elevationLossM", 0.0),
            extTempC = t.optDouble("extTempC", 0.0),
            anticipationScore = t.optInt("anticipationScore", 0),
            smoothnessScore = t.optInt("smoothnessScore", 0),
            speedDisciplineScore = t.optInt("speedDisciplineScore", 0),
            efficiencyScore = t.optInt("efficiencyScore", 0),
            consistencyScore = t.optInt("consistencyScore", 0),
            overallScore = t.optInt("overallScore", 0),
            telemetryFilePath = t.optString("telemetryFilePath", ""),
        )
    }

    fun fetchTelemetry(tripId: Long): List<TelemetryPoint> {
        val jwt = getJwt() ?: return emptyList()
        val json = httpGet("/api/trips/$tripId/telemetry", jwt) ?: return emptyList()
        val arr = json.optJSONArray("telemetry") ?: return emptyList()
        val result = ArrayList<TelemetryPoint>(arr.length())
        for (i in 0 until arr.length()) {
            val s = arr.optJSONObject(i) ?: continue
            result.add(
                TelemetryPoint(
                    timestampMs = s.optLong("t", 0),
                    speedKmh = s.optInt("s", 0),
                    accelPercent = s.optInt("a", 0),
                    brakePercent = s.optInt("b", 0),
                    lat = s.optDouble("la", 0.0),
                    lon = s.optDouble("lo", 0.0),
                )
            )
        }
        return result
    }

    fun fetchSummary(days: Int): TripsSummary? {
        val jwt = getJwt() ?: return null
        val json = httpGet("/api/trips/summary?days=$days", jwt) ?: return null
        val arr = json.optJSONArray("summary") ?: return null
        var tripCount = 0; var totalDist = 0.0; var totalDur = 0
        var totalEnergy = 0.0; var totalEfficiency = 0.0; var energyPerKmSum = 0.0
        for (i in 0 until arr.length()) {
            val r = arr.getJSONObject(i)
            tripCount += r.optInt("tripCount")
            totalDist += r.optDouble("totalDistanceKm", 0.0)
            totalDur += r.optInt("totalDurationSeconds")
            totalEnergy += r.optDouble("totalEnergyKwh", 0.0)
            totalEfficiency += r.optDouble("avgEfficiency", 0.0)
            energyPerKmSum += r.optDouble("avgEnergyPerKm", 0.0)
        }
        val count = arr.length()
        return TripsSummary(
            tripCount = tripCount,
            totalDistanceKm = totalDist,
            totalDurationSeconds = totalDur,
            totalEnergyKwh = totalEnergy,
            avgEnergyPerKm = if (count > 0) energyPerKmSum / count else 0.0,
            avgEfficiency = if (count > 0) totalEfficiency / count else 0.0,
        )
    }

    fun fetchDna(days: Int): DnaScores? {
        val jwt = getJwt() ?: return null
        val json = httpGet("/api/trips/dna?days=$days", jwt) ?: return null
        val dna = json.optJSONObject("dna") ?: return null
        return DnaScores(
            anticipation = dna.optInt("anticipation"),
            smoothness = dna.optInt("smoothness"),
            speedDiscipline = dna.optInt("speedDiscipline"),
            efficiency = dna.optInt("efficiency"),
            consistency = dna.optInt("consistency"),
            overall = dna.optInt("overall"),
        )
    }

    fun fetchRange(): RangeEstimate? {
        val jwt = getJwt() ?: return null
        val json = httpGet("/api/trips/range", jwt) ?: return null
        val range = json.optJSONObject("range") ?: return null
        return RangeEstimate(
            estimatedKm = range.optDouble("estimatedKm", 0.0),
            builtInKm = range.optDouble("builtInRangeKm", 0.0),
            soc = range.optDouble("soc", 0.0),
        )
    }

    fun fetchConfig(): TripsConfig? {
        val jwt = getJwt() ?: return null
        val json = httpGet("/api/trips/config", jwt) ?: return null
        val cfg = json.optJSONObject("config") ?: return null
        return TripsConfig(
            enabled = cfg.optBoolean("enabled", false),
            electricityRate = cfg.optDouble("electricityRate", 0.0),
            currency = cfg.optString("currency", "USD"),
            distanceUnit = cfg.optString("distanceUnit", "km"),
        )
    }

    fun saveConfig(enabled: Boolean, rate: Double, currency: String, distanceUnit: String): Boolean {
        val jwt = getJwt() ?: return false
        val body = JSONObject().apply {
            put("enabled", enabled)
            put("electricityRate", rate)
            put("currency", currency)
            put("distanceUnit", distanceUnit)
        }.toString()
        return httpPost("/api/trips/config", body, jwt) == 200
    }

    fun fetchStorage(): TripsStorage? {
        val jwt = getJwt() ?: return null
        val json = httpGet("/api/trips/storage", jwt) ?: return null
        val s = json.optJSONObject("storage") ?: return null
        return TripsStorage(
            storageType = s.optString("storageType", "INTERNAL"),
            limitMb = s.optLong("limitMb", 500),
            usedMb = s.optDouble("usedMb", 0.0),
            usedUnit = s.optString("usedUnit", "MB"),
            sdCardAvailable = s.optBoolean("sdCardAvailable", false),
            tripsCount = s.optInt("tripsCount", 0),
            storagePath = s.optString("storagePath", ""),
        )
    }

    fun saveStorage(storageType: String, limitMb: Long): Boolean {
        val jwt = getJwt() ?: return false
        val body = JSONObject().apply {
            put("storageType", storageType)
            put("storageLimitMb", limitMb)
        }.toString()
        return httpPost("/api/trips/storage", body, jwt) == 200
    }

    fun syncDatabase(): TripSyncResult {
        val jwt = getJwt() ?: return TripSyncResult(false, "Not authenticated")
        return runCatching {
            val conn = URL("http://127.0.0.1:8080/api/trips/sync")
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
                val removed = json.optInt("removed", 0)
                val total = json.optInt("total", 0)
                TripSyncResult(true, "Synced: +$added -$removed ($total total)")
            } else {
                val error = json.optString("error", "Unknown error")
                TripSyncResult(false, "Sync failed: $error")
            }
        }.getOrElse { e -> TripSyncResult(false, e.message ?: "Network error") }
    }

    private fun getJwt(): String? = runCatching {
        if (AuthManager.getState() == null) AuthManager.initialize()
        AuthManager.generateJwt()?.takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun httpGet(path: String, jwt: String): JSONObject? = runCatching {
        val conn = URL("http://127.0.0.1:8080$path").openConnection(Proxy.NO_PROXY) as HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("Authorization", "Bearer $jwt")
        conn.connectTimeout = 5_000
        conn.readTimeout = 10_000
        if (conn.responseCode != 200) return null
        JSONObject(conn.inputStream.bufferedReader().readText())
    }.getOrNull()

    private fun httpPost(path: String, body: String, jwt: String): Int = runCatching {
        val conn = URL("http://127.0.0.1:8080$path").openConnection(Proxy.NO_PROXY) as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Authorization", "Bearer $jwt")
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        conn.connectTimeout = 5_000
        conn.readTimeout = 10_000
        conn.outputStream.use { it.write(body.toByteArray()) }
        conn.responseCode
    }.getOrDefault(-1)
}
