package net.bladewatch.app.ui.fragment.recording

enum class RecordingSettingsTab { STATUS, CAPTURE, QUALITY, STORAGE }

enum class RecordingMode(val value: String, val label: String, val description: String) {
    NONE("NONE", "None (Default)", "No recording — surveillance still works"),
    CONTINUOUS("CONTINUOUS", "Continuous", "Record all the time while driving"),
    DRIVE_MODE("DRIVE_MODE", "Drive Mode", "Record only when vehicle is moving"),
    PROXIMITY_GUARD("PROXIMITY_GUARD", "Proximity Guard", "Record when motion is detected"),
}

enum class RecordingQuality(val value: String) {
    ECONOMY("ECONOMY"), STANDARD("STANDARD"), HIGH("HIGH"), PREMIUM("PREMIUM"), MAX("MAX");
    companion object { fun fromValue(v: String) = values().find { it.value == v.uppercase() } ?: STANDARD }
}

// Per-file recording limit (segment rotation). Recordings are split into files
// of this length while driving. Must mirror the daemon's accepted values.
enum class RecordingLimit(val minutes: Int, val label: String) {
    ONE(1, "1 min"), FIVE(5, "5 min"), TEN(10, "10 min");
    companion object { fun fromMinutes(m: Int) = values().find { it.minutes == m } ?: FIVE }
}

data class RecordingStatus(
    val currentMode: String,
    val isRecording: Boolean,
    val normalTodayCount: Int,
    val proximityTodayCount: Int,
)

data class RecordingQualitySettings(
    val quality: RecordingQuality,
    val codec: String,
    val segmentMinutes: Int = 5,
)

data class RecordingStorageSettings(
    val storageType: String,
    val limitMb: Long,
    val recordingsSize: Long,
    val recordingsCount: Long,
    val sdCardAvailable: Boolean,
    val sdCardFreeFormatted: String,
    val internalFreeFormatted: String,
    val recordingsPath: String,
    val minLimitMb: Long = 100,
    val maxLimitMb: Long = 100000,
    val maxLimitMbSdCard: Long = 100000,
    // Total capacity (MB) of each volume — used to cap the limit slider at the
    // physical size of the selected storage location. 0 = unknown.
    val internalTotalMb: Long = 0,
    val sdCardTotalMb: Long = 0,
)

data class RecordingAllSettings(
    val status: RecordingStatus?,
    val quality: RecordingQualitySettings?,
    val storage: RecordingStorageSettings?,
    val currentMode: String,
)

sealed interface RecordingSettingsLoadState {
    data object Loading : RecordingSettingsLoadState
    data class Loaded(val settings: RecordingAllSettings) : RecordingSettingsLoadState
    data class Error(val message: String) : RecordingSettingsLoadState
}
