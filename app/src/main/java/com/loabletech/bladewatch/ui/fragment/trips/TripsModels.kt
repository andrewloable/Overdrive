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
    val soc: Double,
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
