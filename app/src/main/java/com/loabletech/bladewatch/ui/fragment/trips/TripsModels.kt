package net.bladewatch.app.ui.fragment.trips

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class TripsTab { TRIPS, STATS, STORAGE }

enum class TripsDaysFilter(val days: Int, val label: String) {
    SEVEN(7, "7 Days"),
    FOURTEEN(14, "14 Days"),
    THIRTY(30, "30 Days"),
}

data class TripItem(
    val id: Long,
    val startTime: Long,
    val endTime: Long,
    val distanceKm: Double,
    val durationSeconds: Int,
    val overallScore: Int,
    val tripCost: Double,
    val currency: String,
    val energyKwh: Double,
) {
    val formattedDate: String get() {
        val sdf = SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault())
        return sdf.format(Date(startTime))
    }
    val formattedDuration: String get() {
        val h = durationSeconds / 3600
        val m = (durationSeconds % 3600) / 60
        return if (h > 0) "${h}h ${m}m" else "${m}m"
    }
}

/**
 * Full single-trip detail returned by GET /api/trips/{id}. Mirrors the fields
 * the old WebView detail panel rendered (summary stats + 5-axis score bars).
 */
data class TripDetail(
    val id: Long,
    val startTime: Long,
    val endTime: Long,
    val distanceKm: Double,
    val durationSeconds: Int,
    val avgSpeedKmh: Double,
    val maxSpeedKmh: Double,
    val socStart: Double,
    val socEnd: Double,
    val energyUsedKwh: Double,
    val efficiencySocPerKm: Double,
    val currency: String,
    val tripCost: Double,
    val gradientProfile: String,
    val elevationGainM: Double,
    val elevationLossM: Double,
    val extTempC: Double,
    val anticipationScore: Int,
    val smoothnessScore: Int,
    val speedDisciplineScore: Int,
    val efficiencyScore: Int,
    val consistencyScore: Int,
    val overallScore: Int,
    val telemetryFilePath: String,
) {
    val formattedDateTitle: String get() {
        val sdf = SimpleDateFormat("EEEE, MMMM d", Locale.getDefault())
        return sdf.format(Date(startTime))
    }
    val formattedTimeRange: String get() {
        val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
        return "${sdf.format(Date(startTime))} – ${sdf.format(Date(endTime))}"
    }
    val formattedDuration: String get() {
        val h = durationSeconds / 3600
        val m = (durationSeconds % 3600) / 60
        return if (h > 0) "${h}h ${m}m" else "${m}m"
    }
    val socDelta: Double get() = socStart - socEnd
}

/**
 * One telemetry sample (compact keys from GET /api/trips/{id}/telemetry).
 * Used to draw the route polyline and read per-point speed.
 */
data class TelemetryPoint(
    val timestampMs: Long,
    val speedKmh: Int,
    val accelPercent: Int,
    val brakePercent: Int,
    val lat: Double,
    val lon: Double,
) {
    val hasGps: Boolean get() = lat != 0.0 && lon != 0.0
}

data class TripsSummary(
    val tripCount: Int,
    val totalDistanceKm: Double,
    val totalDurationSeconds: Int,
    val totalEnergyKwh: Double,
    val avgEnergyPerKm: Double,
    val avgEfficiency: Double,
) {
    val formattedHours: String get() {
        val h = totalDurationSeconds / 3600
        val m = (totalDurationSeconds % 3600) / 60
        return "${h}h ${m}m"
    }
}

data class DnaScores(
    val anticipation: Int,
    val smoothness: Int,
    val speedDiscipline: Int,
    val efficiency: Int,
    val consistency: Int,
    val overall: Int,
)

data class RangeEstimate(
    val estimatedKm: Double,
    val builtInKm: Double,
)

data class TripsConfig(
    val enabled: Boolean,
    val electricityRate: Double,
    val currency: String,
    val distanceUnit: String,
)

data class TripsStorage(
    val storageType: String,
    val limitMb: Long,
    val usedMb: Double,
    val usedUnit: String,
    val sdCardAvailable: Boolean,
    val tripsCount: Int,
    val storagePath: String,
)

sealed interface TripsLoadState {
    data object Loading : TripsLoadState
    data object Empty : TripsLoadState
    data class Loaded(
        val trips: List<TripItem>,
        val summary: TripsSummary?,
        val dna: DnaScores?,
        val range: RangeEstimate?,
        val config: TripsConfig?,
        val storage: TripsStorage?,
    ) : TripsLoadState
    data class Error(val message: String) : TripsLoadState
}
