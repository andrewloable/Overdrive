package com.overdrive.app.ui.model

import android.net.Uri
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Represents a recorded video file.
 * SOTA: Supports both direct file access and MediaStore content URIs.
 */
data class RecordingFile(
    val file: File,
    val cameraId: Int,
    val timestamp: Long,
    val durationMs: Long,
    val sizeBytes: Long,
    val type: RecordingType,
    val contentUri: Uri? = null,  // SOTA: MediaStore content URI for cross-UID access
    // ---- v3 sidecar enrichment (item 7). Lazily populated; null on legacy clips. ----
    val peakSeverity: String? = null,    // "NOTICE" / "ALERT" / "CRITICAL"
    val peakProximity: String? = null,   // "VERY_CLOSE" / "CLOSE" / "MID" / "FAR"
    val personCount: Int = 0,
    val vehicleCount: Int = 0,
    val bikeCount: Int = 0,
    val animalCount: Int = 0,
    val heroThumbnailFile: File? = null,  // Sibling JPEG path or null
    val actorClasses: List<String> = emptyList()  // ["person","vehicle",...] for filtering
) {
    // Secondary constructor for MediaStore results
    constructor(
        file: File,
        name: String,
        sizeBytes: Long,
        timestamp: Long,
        durationMs: Long,
        type: RecordingType,
        contentUri: Uri?
    ) : this(
        file = file,
        cameraId = extractCameraId(name),
        timestamp = timestamp,
        durationMs = durationMs,
        sizeBytes = sizeBytes,
        type = type,
        contentUri = contentUri
    )
    
    val name: String get() = file.name
    val path: String get() = file.absolutePath
    
    val formattedDate: String
        get() = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()).format(Date(timestamp))
    
    val formattedTime: String
        get() = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
    
    val formattedDuration: String
        get() {
            val seconds = durationMs / 1000
            val hours = seconds / 3600
            val minutes = (seconds % 3600) / 60
            val secs = seconds % 60
            return if (hours > 0) {
                String.format("%d:%02d:%02d", hours, minutes, secs)
            } else {
                String.format("%d:%02d", minutes, secs)
            }
        }
    
    val formattedSize: String
        get() = when {
            sizeBytes >= 1_000_000_000 -> String.format("%.1f GB", sizeBytes / 1_000_000_000.0)
            sizeBytes >= 1_000_000 -> String.format("%.1f MB", sizeBytes / 1_000_000.0)
            sizeBytes >= 1_000 -> String.format("%.1f KB", sizeBytes / 1_000.0)
            else -> "$sizeBytes B"
        }
    
    enum class RecordingType {
        NORMAL,     // Regular recordings (cam_*.mp4)
        SENTRY,     // Sentry event recordings (event_*.mp4)
        PROXIMITY   // Proximity guard recordings (proximity_*.mp4)
    }
    
    companion object {
        // Parse filename like: cam1_20251224_132630.mp4, cam_20251224_132630.mp4,
        // or segmented continuations cam_20251224_132630_2.mp4. Mirrors the
        // server-side RecordingsApiHandler.CAM_PATTERN so both surfaces see
        // every clip on disk.
        private val CAM_FILENAME_PATTERN = Regex("""cam(\d+)?_(\d{8})_(\d{6})(?:_\d+)?\.mp4""")
        // event_20251224_132630.mp4 or event_20251224_132630_2.mp4
        private val EVENT_FILENAME_PATTERN = Regex("""event_(\d{8})_(\d{6})(?:_\d+)?\.mp4""")
        // proximity_20251224_132630.mp4 or proximity_20251224_132630_2.mp4
        private val PROXIMITY_FILENAME_PATTERN = Regex("""proximity_(\d{8})_(\d{6})(?:_\d+)?\.mp4""")

        /**
         * Parses the writer's `yyyyMMdd_HHmmss` filename stamp.
         *
         * Pinned to [Locale.US] for two reasons:
         *  1. The writer (GpuMosaicRecorder, SurveillanceEngineGpu, etc.) all
         *     format with Locale.US. Reading with `getDefault()` parsed those
         *     digits through whatever calendar the user's locale defaults to
         *     — Thai locale e.g. interprets the year as Buddhist Era and
         *     produced timestamps ~543 years off, dropping every clip out of
         *     "today" and breaking single-day filters.
         *  2. SimpleDateFormat is NOT thread-safe. The scanner runs from
         *     multiple workers (RecordingsFragment + RecordingLibraryFragment +
         *     DashboardInsight). A static-shared instance corrupts under
         *     concurrent parse() calls, returning null (→ mtime fallback) or
         *     wrong years intermittently — that was the "1 clip per day"
         *     symptom. Allocating per parse is far cheaper than the surrounding
         *     disk I/O.
         */
        private fun newDateFormat(): SimpleDateFormat =
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        
        fun fromFile(file: File, type: RecordingType = RecordingType.NORMAL): RecordingFile? {
            // Try type-specific parsing first
            val result = when (type) {
                RecordingType.NORMAL -> parseNormalRecording(file)
                RecordingType.SENTRY -> parseSentryRecording(file)
                RecordingType.PROXIMITY -> parseProximityRecording(file)
            }
            
            // If type-specific parsing failed, try fallback parsing
            // This handles any .mp4 file that doesn't match expected patterns
            return result ?: parseFallbackRecording(file, type)
        }
        
        /**
         * Fallback parser for .mp4 files that don't match expected patterns.
         * Uses file modification time as timestamp.
         */
        private fun parseFallbackRecording(file: File, type: RecordingType): RecordingFile? {
            if (!file.name.endsWith(".mp4")) return null
            
            return RecordingFile(
                file = file,
                cameraId = 0,
                timestamp = file.lastModified(),
                durationMs = 0,
                sizeBytes = file.length(),
                type = type
            )
        }
        
        private fun parseNormalRecording(file: File): RecordingFile? {
            val match = CAM_FILENAME_PATTERN.matchEntire(file.name) ?: return null
            val cameraId = match.groupValues[1].toIntOrNull() ?: 0  // 0 for mosaic recordings
            val dateStr = "${match.groupValues[2]}_${match.groupValues[3]}"
            val timestamp = try {
                newDateFormat().parse(dateStr)?.time ?: file.lastModified()
            } catch (e: Exception) {
                file.lastModified()
            }
            
            return RecordingFile(
                file = file,
                cameraId = cameraId,
                timestamp = timestamp,
                durationMs = 0, // Would need MediaMetadataRetriever to get actual duration
                sizeBytes = file.length(),
                type = RecordingType.NORMAL
            )
        }
        
        private fun parseSentryRecording(file: File): RecordingFile? {
            val match = EVENT_FILENAME_PATTERN.matchEntire(file.name) ?: return null
            val dateStr = "${match.groupValues[1]}_${match.groupValues[2]}"
            val timestamp = try {
                newDateFormat().parse(dateStr)?.time ?: file.lastModified()
            } catch (e: Exception) {
                file.lastModified()
            }
            
            return RecordingFile(
                file = file,
                cameraId = 0,  // Sentry events are mosaic recordings
                timestamp = timestamp,
                durationMs = 0,
                sizeBytes = file.length(),
                type = RecordingType.SENTRY
            )
        }
        
        private fun parseProximityRecording(file: File): RecordingFile? {
            val match = PROXIMITY_FILENAME_PATTERN.matchEntire(file.name) ?: return null
            val dateStr = "${match.groupValues[1]}_${match.groupValues[2]}"
            val timestamp = try {
                newDateFormat().parse(dateStr)?.time ?: file.lastModified()
            } catch (e: Exception) {
                file.lastModified()
            }
            
            return RecordingFile(
                file = file,
                cameraId = 0,  // Proximity events are mosaic recordings
                timestamp = timestamp,
                durationMs = 0,
                sizeBytes = file.length(),
                type = RecordingType.PROXIMITY
            )
        }
        
        /**
         * Extract camera ID from filename.
         */
        private fun extractCameraId(name: String): Int {
            val match = CAM_FILENAME_PATTERN.matchEntire(name)
            return match?.groupValues?.get(1)?.toIntOrNull() ?: 0
        }
    }
}
