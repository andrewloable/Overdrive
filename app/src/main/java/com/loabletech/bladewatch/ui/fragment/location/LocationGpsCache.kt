package net.bladewatch.app.ui.fragment.location

import java.io.File
import java.util.Locale

class LocationGpsCache(
    private val cacheFile: File = File(DEFAULT_CACHE_FILE),
) {
    fun write(location: LocationCarGps): Boolean =
        runCatching {
            if (!location.isUsable()) return false
            val json = location.toCacheJson()
            writeDirect(json)
        }.getOrDefault(false)

    private fun writeDirect(json: String): Boolean =
        runCatching {
            cacheFile.parentFile?.mkdirs()
            val temp = File(cacheFile.parentFile, "${cacheFile.name}.tmp")
            temp.writeText(json)
            if (!temp.renameTo(cacheFile)) {
                temp.copyTo(cacheFile, overwrite = true)
                temp.delete()
            }
            true
        }.getOrDefault(false)

    private fun LocationCarGps.toCacheJson(): String =
        buildString {
            append('{')
            append("\"lat\":").append(latitude.fmt())
            append(",\"lng\":").append(longitude.fmt())
            append(",\"time\":").append(timestampMs)
            speedMetersPerSecond?.let { append(",\"speed_mps\":").append(it.fmt()) }
            accuracyMeters?.let { append(",\"accuracy_m\":").append(it.fmt()) }
            altitudeMeters?.let { append(",\"altitude_m\":").append(it.fmt()) }
            bearingDegrees?.let { append(",\"bearing_deg\":").append(it.fmt()) }
            append('}')
        }

    private fun Double.fmt(): String = String.format(Locale.US, "%.8f", this)
    private fun Float.fmt(): String = String.format(Locale.US, "%.3f", this)

    companion object {
        const val DEFAULT_CACHE_FILE = "/data/local/tmp/bladewatch_gps_cache.json"
    }
}
