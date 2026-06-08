package net.bladewatch.app.ui.fragment.trips

import com.connectrpc.ResponseMessage
import kotlinx.coroutines.runBlocking
import net.bladewatch.app.client.ConnectClientProvider
import net.bladewatch.app.grpc.v1.GetConfigRequest
import net.bladewatch.app.grpc.v1.GetDnaRequest
import net.bladewatch.app.grpc.v1.GetRangeRequest
import net.bladewatch.app.grpc.v1.GetStorageRequest
import net.bladewatch.app.grpc.v1.GetSummaryRequest
import net.bladewatch.app.grpc.v1.GetTelemetryRequest
import net.bladewatch.app.grpc.v1.GetTripRequest
import net.bladewatch.app.grpc.v1.ListTripsRequest
import net.bladewatch.app.grpc.v1.SetConfigRequest
import net.bladewatch.app.grpc.v1.SetStorageRequest
import net.bladewatch.app.grpc.v1.SyncTripsRequest
import org.json.JSONObject

internal data class TripSyncResult(val success: Boolean, val message: String)

internal class TripsClient {

    fun fetchTrips(days: Int): List<TripItem> = runBlocking {
        val req = ListTripsRequest.newBuilder().setDays(days).setLimit(100).build()
        val resp = ConnectClientProvider.tripsService().listTrips(req, emptyMap())
        if (resp !is ResponseMessage.Success) return@runBlocking emptyList()
        resp.message.tripsList.map { t ->
            TripItem(
                id = t.id,
                startTime = t.startTime,
                endTime = t.endTime,
                distanceKm = t.distanceKm,
                durationSeconds = t.durationSeconds,
                overallScore = t.overallScore,
                tripCost = t.tripCost,
                currency = t.currency,
                energyKwh = 0.0, // proto TripSummary has energyPerKm, not energyUsedKwh
            )
        }
    }

    fun fetchTripDetail(tripId: Long): TripDetail? = runBlocking {
        val req = GetTripRequest.newBuilder().setId(tripId).build()
        val resp = ConnectClientProvider.tripsService().getTrip(req, emptyMap())
        if (resp !is ResponseMessage.Success) return@runBlocking null
        val t = resp.message.trip ?: return@runBlocking null
        val s = t.summary ?: return@runBlocking null
        TripDetail(
            id = s.id,
            startTime = s.startTime,
            endTime = s.endTime,
            distanceKm = s.distanceKm,
            durationSeconds = s.durationSeconds,
            avgSpeedKmh = s.avgSpeedKmh,
            maxSpeedKmh = s.maxSpeedKmh.toDouble(),
            socStart = s.socStart,
            socEnd = s.socEnd,
            energyUsedKwh = 0.0,
            efficiencySocPerKm = 0.0,
            currency = s.currency,
            tripCost = s.tripCost,
            gradientProfile = s.gradientProfile,
            elevationGainM = t.elevationGainM,
            elevationLossM = t.elevationLossM,
            extTempC = s.extTempC.toDouble(),
            anticipationScore = t.anticipationScore,
            smoothnessScore = t.smoothnessScore,
            speedDisciplineScore = t.speedDisciplineScore,
            efficiencyScore = t.efficiencyScore,
            consistencyScore = t.consistencyScore,
            overallScore = s.overallScore,
            telemetryFilePath = "",
        )
    }

    fun fetchTelemetry(tripId: Long): List<TelemetryPoint> = runBlocking {
        val req = GetTelemetryRequest.newBuilder().setTripId(tripId).build()
        val resp = ConnectClientProvider.tripsService().getTelemetry(req, emptyMap())
        if (resp !is ResponseMessage.Success) return@runBlocking emptyList()
        resp.message.telemetryList.mapNotNull { sample ->
            val json = runCatching { JSONObject(sample.sampleJson) }.getOrNull()
                ?: return@mapNotNull null
            TelemetryPoint(
                timestampMs = json.optLong("t", 0),
                speedKmh = json.optInt("s", 0),
                accelPercent = json.optInt("a", 0),
                brakePercent = json.optInt("b", 0),
                lat = json.optDouble("la", 0.0),
                lon = json.optDouble("lo", 0.0),
            )
        }
    }

    fun fetchSummary(days: Int): TripsSummary? = runBlocking {
        val req = GetSummaryRequest.newBuilder().setDays(days).build()
        val resp = ConnectClientProvider.tripsService().getSummary(req, emptyMap())
        if (resp !is ResponseMessage.Success) return@runBlocking null
        var tripCount = 0; var totalDist = 0.0; var totalDur = 0
        var totalEnergy = 0.0; var totalEfficiency = 0.0; var energyPerKmSum = 0.0
        for (entry in resp.message.summaryList) {
            val r = runCatching { JSONObject(entry.rollupJson) }.getOrNull() ?: continue
            tripCount += r.optInt("tripCount")
            totalDist += r.optDouble("totalDistanceKm", 0.0)
            totalDur += r.optInt("totalDurationSeconds")
            totalEnergy += r.optDouble("totalEnergyKwh", 0.0)
            totalEfficiency += r.optDouble("avgEfficiency", 0.0)
            energyPerKmSum += r.optDouble("avgEnergyPerKm", 0.0)
        }
        val count = resp.message.summaryList.size
        TripsSummary(
            tripCount = tripCount,
            totalDistanceKm = totalDist,
            totalDurationSeconds = totalDur,
            totalEnergyKwh = totalEnergy,
            avgEnergyPerKm = if (count > 0) energyPerKmSum / count else 0.0,
            avgEfficiency = if (count > 0) totalEfficiency / count else 0.0,
        )
    }

    fun fetchDna(days: Int): DnaScores? = runBlocking {
        val req = GetDnaRequest.newBuilder().setDays(days).build()
        val resp = ConnectClientProvider.tripsService().getDna(req, emptyMap())
        if (resp !is ResponseMessage.Success) return@runBlocking null
        val dna = resp.message.dna ?: return@runBlocking null
        DnaScores(
            anticipation = dna.anticipation,
            smoothness = dna.smoothness,
            speedDiscipline = dna.speedDiscipline,
            efficiency = dna.efficiency,
            consistency = dna.consistency,
            overall = dna.overall,
        )
    }

    fun fetchRange(): RangeEstimate? = runBlocking {
        val req = GetRangeRequest.newBuilder().build()
        val resp = ConnectClientProvider.tripsService().getRange(req, emptyMap())
        if (resp !is ResponseMessage.Success) return@runBlocking null
        val json = runCatching { JSONObject(resp.message.rangeJson) }.getOrNull()
            ?: return@runBlocking null
        val range = json.optJSONObject("range") ?: json
        // The daemon's RangeEstimate.toJson() emits predictedRangeKm/builtInRangeKm
        // (no estimatedKm or soc). Read the real keys.
        RangeEstimate(
            estimatedKm = range.optDouble("predictedRangeKm", 0.0),
            builtInKm = range.optDouble("builtInRangeKm", 0.0),
        )
    }

    fun fetchConfig(): TripsConfig? = runBlocking {
        val req = GetConfigRequest.newBuilder().build()
        val resp = ConnectClientProvider.tripsService().getConfig(req, emptyMap())
        if (resp !is ResponseMessage.Success) return@runBlocking null
        val cfg = resp.message.config ?: return@runBlocking null
        TripsConfig(
            enabled = cfg.enabled,
            electricityRate = cfg.electricityRate,
            currency = cfg.currency,
            distanceUnit = cfg.distanceUnit,
        )
    }

    fun saveConfig(enabled: Boolean, rate: Double, currency: String, distanceUnit: String): Boolean = runBlocking {
        val req = SetConfigRequest.newBuilder()
            .setEnabled(enabled)
            .setHasEnabled(true)
            .setElectricityRate(rate)
            .setHasElectricityRate(true)
            .setCurrency(currency)
            .setDistanceUnit(distanceUnit)
            .build()
        val resp = ConnectClientProvider.tripsService().setConfig(req, emptyMap())
        resp is ResponseMessage.Success && resp.message.success
    }

    fun fetchStorage(): TripsStorage? = runBlocking {
        val req = GetStorageRequest.newBuilder().build()
        val resp = ConnectClientProvider.tripsService().getStorage(req, emptyMap())
        if (resp !is ResponseMessage.Success) return@runBlocking null
        val s = resp.message.storage ?: return@runBlocking null
        TripsStorage(
            storageType = s.storageType,
            limitMb = s.limitMb,
            usedMb = s.usedMb,
            usedUnit = s.usedUnit,
            sdCardAvailable = s.sdCardAvailable,
            tripsCount = s.tripsCount,
            storagePath = s.storagePath,
        )
    }

    fun saveStorage(storageType: String, limitMb: Long): Boolean = runBlocking {
        val req = SetStorageRequest.newBuilder()
            .setStorageType(storageType)
            .setStorageLimitMb(limitMb)
            .setHasStorageLimitMb(true)
            .build()
        val resp = ConnectClientProvider.tripsService().setStorage(req, emptyMap())
        resp is ResponseMessage.Success && resp.message.success
    }

    fun syncDatabase(): TripSyncResult = runBlocking {
        val req = SyncTripsRequest.newBuilder().build()
        val resp = ConnectClientProvider.longTripsService().syncTrips(req, emptyMap())
        when (resp) {
            is ResponseMessage.Success -> {
                val m = resp.message
                if (m.success) {
                    val detail = "+${m.added} -${m.removed} (${m.total} total)"
                    TripSyncResult(true, "Synced successfully: $detail")
                } else {
                    TripSyncResult(false, m.error.takeIf { it.isNotEmpty() } ?: "Sync failed")
                }
            }
            is ResponseMessage.Failure -> TripSyncResult(false, resp.cause.message ?: "Network error")
        }
    }
}
