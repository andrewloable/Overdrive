package net.bladewatch.app.ui.fragment.location

import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate
import java.util.Locale
import net.bladewatch.app.ui.util.PreferencesManager

data class LocationCarGps(
    val latitude: Double,
    val longitude: Double,
    val bearingDegrees: Float? = null,
    val speedMetersPerSecond: Float? = null,
    val accuracyMeters: Float? = null,
    val altitudeMeters: Double? = null,
    val provider: String,
    val timestampMs: Long,
) {
    fun isUsable(): Boolean {
        if (latitude !in -90.0..90.0) return false
        if (longitude !in -180.0..180.0) return false
        return !(latitude == 0.0 && longitude == 0.0)
    }
}

sealed interface LocationUiState {
    val location: LocationCarGps?
}

data object LocationLoading : LocationUiState {
    override val location: LocationCarGps? = null
}

data object LocationPermissionMissing : LocationUiState {
    override val location: LocationCarGps? = null
}

data object LocationPermissionDenied : LocationUiState {
    override val location: LocationCarGps? = null
}

data object LocationProviderDisabled : LocationUiState {
    override val location: LocationCarGps? = null
}

data object LocationWaitingForFix : LocationUiState {
    override val location: LocationCarGps? = null
}

data class LocationFresh(override val location: LocationCarGps) : LocationUiState

data class LocationStale(override val location: LocationCarGps) : LocationUiState

data class LocationTileFailure(
    override val location: LocationCarGps? = null,
    val reason: String? = null,
) : LocationUiState

data class LocationError(
    override val location: LocationCarGps? = null,
    val reason: String? = null,
) : LocationUiState

data class LocationPanelModel(
    val title: String,
    val subtitle: String? = null,
    val actionLabel: String? = null,
    val showMap: Boolean,
    val compact: Boolean,
)

data class LocationMapViewportState(
    val followCar: Boolean = true,
    val firstLocationSeen: Boolean = false,
    val lastLocation: LocationCarGps? = null,
)

data class LocationMapUpdate(
    val viewportState: LocationMapViewportState,
    val centerMap: Boolean,
    val zoomLevel: Double? = null,
)

data class LocationMapAppearance(
    val useNightTiles: Boolean,
)

enum class LocationUiModePreference(val storedValue: String) {
    AUTO("auto"),
    LIGHT("light"),
    DARK("dark");

    companion object {
        fun fromStoredValue(value: String?): LocationUiModePreference =
            entries.firstOrNull { it.storedValue == value } ?: AUTO
    }
}

object LocationUiStateMapper {
    fun panelModel(state: LocationUiState): LocationPanelModel =
        when (state) {
            LocationLoading -> LocationPanelModel(
                title = "Loading map",
                showMap = false,
                compact = false,
            )
            LocationPermissionMissing -> LocationPanelModel(
                title = "Location permission required",
                actionLabel = "Grant",
                showMap = false,
                compact = false,
            )
            LocationPermissionDenied -> LocationPanelModel(
                title = "Permission denied",
                actionLabel = "Retry",
                showMap = false,
                compact = false,
            )
            LocationProviderDisabled -> LocationPanelModel(
                title = "GPS disabled",
                actionLabel = "Retry",
                showMap = false,
                compact = false,
            )
            LocationWaitingForFix -> LocationPanelModel(
                title = "Waiting for GPS fix",
                showMap = false,
                compact = false,
            )
            is LocationFresh -> LocationPanelModel(
                title = "Car location",
                subtitle = formatLocation(state.location),
                showMap = true,
                compact = true,
            )
            is LocationStale -> LocationPanelModel(
                title = "Location stale",
                subtitle = formatLocation(state.location),
                showMap = true,
                compact = true,
            )
            is LocationTileFailure -> LocationPanelModel(
                title = "Map unavailable",
                subtitle = state.reason ?: "Network unavailable",
                actionLabel = "Retry",
                showMap = state.location != null,
                compact = true,
            )
            is LocationError -> LocationPanelModel(
                title = "Location error",
                subtitle = state.reason,
                actionLabel = "Retry",
                showMap = state.location != null,
                compact = state.location != null,
            )
        }

    private fun formatLocation(location: LocationCarGps): String =
        String.format(Locale.US, "%.5f, %.5f", location.latitude, location.longitude)
}

object LocationStateReducer {
    const val STALE_THRESHOLD_MS: Long = 30_000L

    fun permissionState(prompted: Boolean): LocationUiState =
        if (prompted) LocationPermissionDenied else LocationPermissionMissing

    fun providerUnavailable(): LocationUiState = LocationProviderDisabled

    fun waitingForFix(current: LocationUiState, nowMs: Long): LocationUiState {
        val last = current.location ?: return LocationWaitingForFix
        return refreshStaleness(LocationFresh(last), nowMs)
    }

    fun onLocationSample(current: LocationUiState, sample: LocationCarGps): LocationUiState {
        if (!sample.isUsable()) return current
        return LocationFresh(sample)
    }

    fun refreshStaleness(current: LocationUiState, nowMs: Long): LocationUiState =
        when (current) {
            is LocationFresh -> if (nowMs - current.location.timestampMs >= STALE_THRESHOLD_MS) {
                LocationStale(current.location)
            } else {
                current
            }
            is LocationStale -> current
            else -> current
        }

    fun tileFailure(current: LocationUiState, reason: String): LocationUiState =
        when (current) {
            is LocationFresh -> LocationTileFailure(current.location, reason)
            is LocationStale -> LocationTileFailure(current.location, reason)
            is LocationTileFailure -> current.copy(reason = reason)
            else -> current
        }
}

object LocationMapReducer {
    fun onUserPan(state: LocationMapViewportState): LocationMapViewportState =
        state.copy(followCar = false)

    fun onValidLocation(
        state: LocationMapViewportState,
        location: LocationCarGps,
    ): LocationMapUpdate {
        val nextState = state.copy(firstLocationSeen = true, lastLocation = location)
        return LocationMapUpdate(
            viewportState = nextState,
            centerMap = state.followCar,
            zoomLevel = if (state.firstLocationSeen) null else 17.5,
        )
    }

    fun onFollowRequested(state: LocationMapViewportState): LocationMapViewportState =
        state.copy(followCar = true)
}

object LocationMapBearingMapper {
    fun markerRotation(bearingDegrees: Float?): Float {
        val bearing = bearingDegrees ?: return 0f
        val normalized = ((bearing % 360f) + 360f) % 360f
        return if (normalized == 0f) 0f else 360f - normalized
    }
}

object LocationAppearanceResolver {
    fun resolve(context: Context, uiModePreference: LocationUiModePreference): LocationMapAppearance =
        when (uiModePreference) {
            LocationUiModePreference.DARK -> LocationMapAppearance(useNightTiles = true)
            LocationUiModePreference.LIGHT -> LocationMapAppearance(useNightTiles = false)
            LocationUiModePreference.AUTO -> resolveAuto(context)
        }

    private fun resolveAuto(context: Context): LocationMapAppearance {
        val themeMode = if (PreferencesManager.isInitialized()) {
            PreferencesManager.getThemeMode()
        } else {
            AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        val useNight = when (themeMode) {
            AppCompatDelegate.MODE_NIGHT_YES -> true
            AppCompatDelegate.MODE_NIGHT_NO -> false
            else -> {
                val nightMode = context.resources.configuration.uiMode and
                    Configuration.UI_MODE_NIGHT_MASK
                nightMode == Configuration.UI_MODE_NIGHT_YES
            }
        }
        return LocationMapAppearance(useNightTiles = useNight)
    }
}

fun LocationUiState.logLabel(): String =
    when (this) {
        LocationLoading -> "loading"
        LocationPermissionMissing -> "permission_missing"
        LocationPermissionDenied -> "permission_denied"
        LocationProviderDisabled -> "provider_disabled"
        LocationWaitingForFix -> "waiting_for_fix"
        is LocationFresh -> "fresh"
        is LocationStale -> "stale"
        is LocationTileFailure -> "tile_failure"
        is LocationError -> "error"
    }
