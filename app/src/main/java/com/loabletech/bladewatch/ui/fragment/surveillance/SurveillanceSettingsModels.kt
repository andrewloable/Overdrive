package net.bladewatch.app.ui.fragment.surveillance

enum class SurveillanceSettingsTab { GENERAL, DETECTION, RECORDING, STORAGE, ADVANCED }

data class SafeZone(
    val id: String,
    val name: String,
    val lat: Double,
    val lng: Double,
    val radiusM: Int,
)

data class SurveillanceConfig(
    val enabled: Boolean,
    // Detection distance preset (proto: distance_preset / json: distancePreset).
    // Controls minObjectSize for AI range. NOT the same as the daemon's separate
    // environmentPreset (indoor/outdoor tuning) exposed by GET /api/surveillance endpoint.
    val distancePreset: String,
    val sensitivityLevel: Int,
    val detectPerson: Boolean,
    val detectCar: Boolean,
    val detectBike: Boolean,
    val preRecordSeconds: Int,
    val postRecordSeconds: Int,
    val nightMode: Boolean,
    val aiEnabled: Boolean,
    val aiConfidence: Float,
    val cameraFront: Boolean,
    val cameraRight: Boolean,
    val cameraRear: Boolean,
    val cameraLeft: Boolean,
    val deterrentAction: String,
    val deterrentCooldownSeconds: Int,
)

data class SurveillanceStatus(
    val isRunning: Boolean,
    val eventsToday: Int,
)

data class SurveillanceStorageSettings(
    val storageType: String,
    val limitMb: Long,
    val surveillanceSize: Long,
    val surveillanceCount: Long,
    val sdCardAvailable: Boolean,
    val path: String,
    val minLimitMb: Long = 100,
    val maxLimitMb: Long = 100000,
    val maxLimitMbSdCard: Long = 100000,
    // Total capacity (MB) of each volume — used to cap the limit slider at the
    // physical size of the selected storage location. 0 = unknown.
    val internalTotalMb: Long = 0,
    val sdCardTotalMb: Long = 0,
)

data class SafeLocationsState(
    val featureEnabled: Boolean,
    val zones: List<SafeZone>,
    val hasGps: Boolean,
    val currentLat: Double,
    val currentLng: Double,
)

data class SurveillanceAllSettings(
    val config: SurveillanceConfig?,
    val status: SurveillanceStatus?,
    val storage: SurveillanceStorageSettings?,
    val safeLocations: SafeLocationsState?,
)

sealed interface SurveillanceSettingsLoadState {
    data object Loading : SurveillanceSettingsLoadState
    data class Loaded(val settings: SurveillanceAllSettings) : SurveillanceSettingsLoadState
    data class Error(val message: String) : SurveillanceSettingsLoadState
}
