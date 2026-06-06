package net.bladewatch.app.ui.fragment.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.File

class LocationGpsController(
    private val context: Context,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val onStateChanged: (LocationUiState) -> Unit,
) {
    private val locationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val cache = LocationGpsCache(
        cacheFile = context.externalCacheDir?.resolve("bladewatch_gps_cache.json")
            ?: File(LocationGpsCache.DEFAULT_CACHE_FILE),
    )
    private val handler = Handler(Looper.getMainLooper())
    private var currentState: LocationUiState = LocationLoading
    private var permissionPrompted = false
    private var started = false

    private val staleCheck = Runnable {
        currentState = LocationStateReducer.refreshStaleness(currentState, nowMs())
        emit(currentState)
    }

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            if (!started) return
            val sample = location.toSample()
            cache.write(sample)
            currentState = LocationStateReducer.onLocationSample(currentState, sample)
            emit(currentState)
            scheduleStaleCheck()
        }

        override fun onProviderEnabled(provider: String) {
            if (!started) return
            stop()
            start()
        }

        override fun onProviderDisabled(provider: String) {
            if (!started) return
            currentState = LocationProviderDisabled
            emit(currentState)
        }
    }

    fun start() {
        if (!hasFineLocation()) {
            currentState = LocationStateReducer.permissionState(permissionPrompted)
            emit(currentState)
            return
        }
        val provider = selectedProvider() ?: run {
            currentState = LocationStateReducer.providerUnavailable()
            emit(currentState)
            return
        }
        if (started) stop()
        started = true
        currentState = LocationStateReducer.waitingForFix(currentState, nowMs())
        emit(currentState)
        Log.d(TAG, "start provider=$provider")
        runCatching {
            locationManager.requestLocationUpdates(
                provider, 1_000L, 0f, listener, Looper.getMainLooper()
            )
        }.onFailure {
            currentState = LocationError(reason = it.message)
            emit(currentState)
        }
    }

    fun stop() {
        started = false
        handler.removeCallbacks(staleCheck)
        runCatching { locationManager.removeUpdates(listener) }
        Log.d(TAG, "stop")
    }

    fun onPermissionPrompted() {
        permissionPrompted = true
    }

    fun onPermissionResult(granted: Boolean) {
        permissionPrompted = true
        if (granted) {
            permissionPrompted = false
            start()
        } else {
            currentState = LocationPermissionDenied
            emit(currentState)
        }
    }

    fun refresh() {
        currentState = LocationStateReducer.refreshStaleness(currentState, nowMs())
        emit(currentState)
    }

    private fun emit(state: LocationUiState) {
        Log.d(TAG, "state=${state.logLabel()}")
        onStateChanged(state)
    }

    private fun scheduleStaleCheck() {
        handler.removeCallbacks(staleCheck)
        handler.postDelayed(staleCheck, LocationStateReducer.STALE_THRESHOLD_MS)
    }

    private fun hasFineLocation(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun selectedProvider(): String? {
        val gpsOk = runCatching {
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        }.getOrDefault(false)
        if (gpsOk) return LocationManager.GPS_PROVIDER
        val netOk = runCatching {
            locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }.getOrDefault(false)
        return if (netOk) LocationManager.NETWORK_PROVIDER else null
    }

    private fun Location.toSample(): LocationCarGps =
        LocationCarGps(
            latitude = latitude,
            longitude = longitude,
            bearingDegrees = if (hasBearing()) bearing else null,
            speedMetersPerSecond = if (hasSpeed()) speed else null,
            accuracyMeters = if (hasAccuracy()) accuracy else null,
            altitudeMeters = if (hasAltitude()) altitude else null,
            provider = provider ?: "",
            timestampMs = if (time > 0L) time else nowMs(),
        )

    companion object {
        private const val TAG = "LocationGpsController"
    }
}
