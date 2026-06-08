package net.bladewatch.app.ui.fragment.recording

import com.connectrpc.ResponseMessage
import kotlinx.coroutines.runBlocking
import net.bladewatch.app.auth.AuthManager
import net.bladewatch.app.client.ConnectClientProvider
import net.bladewatch.app.grpc.v1.FormatVolumeRequest
import net.bladewatch.app.grpc.v1.GetQualityRequest
import net.bladewatch.app.grpc.v1.GetStatsRequest
import net.bladewatch.app.grpc.v1.GetStatusRequest
import net.bladewatch.app.grpc.v1.GetStorageSettingsRequest
import net.bladewatch.app.grpc.v1.ListFormatVolumesRequest
import net.bladewatch.app.grpc.v1.SetQualityRequest
import net.bladewatch.app.grpc.v1.SetStorageSettingsRequest
import net.bladewatch.app.grpc.v1.SyncCatalogRequest
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL

internal data class FormattableVolume(val volumeId: String, val uuid: String?, val mountPath: String?)
internal data class FormatDriveResult(val success: Boolean, val message: String, val mountPath: String?)
internal data class SyncResult(val success: Boolean, val message: String)

internal class RecordingSettingsClient {

    fun fetchStatus(): RecordingStatus? = runBlocking {
        val statusResp = ConnectClientProvider.systemService().getStatus(
            GetStatusRequest.newBuilder().build(), emptyMap()
        )
        val statsResp = ConnectClientProvider.recordingsService().getStats(
            GetStatsRequest.newBuilder().build(), emptyMap()
        )
        val recStatus = if (statusResp is ResponseMessage.Success) statusResp.message.recordingStatus else null
        val mode = recStatus?.configuredMode?.takeIf { it.isNotEmpty() } ?: "UNKNOWN"
        val isRecording = recStatus?.isRecording ?: false
        val normalToday = if (statsResp is ResponseMessage.Success)
            statsResp.message.stats?.recordingsCount ?: 0
        else 0
        val proxToday = if (statsResp is ResponseMessage.Success)
            statsResp.message.stats?.proximityCount ?: 0
        else 0
        RecordingStatus(
            currentMode = mode,
            isRecording = isRecording,
            normalTodayCount = normalToday,
            proximityTodayCount = proxToday,
        )
    }

    fun fetchQuality(): RecordingQualitySettings? = runBlocking {
        val resp = ConnectClientProvider.settingsService().getQuality(
            GetQualityRequest.newBuilder().build(), emptyMap()
        )
        if (resp !is ResponseMessage.Success) return@runBlocking null
        val m = resp.message
        RecordingQualitySettings(
            quality = RecordingQuality.fromValue(m.recordingQuality.takeIf { it.isNotEmpty() } ?: "STANDARD"),
            codec = m.codec.takeIf { it.isNotEmpty() } ?: "H264",
            segmentMinutes = m.recordingSegmentMinutes.takeIf { it > 0 } ?: 5,
        )
    }

    fun fetchStorage(): RecordingStorageSettings? = runBlocking {
        val resp = ConnectClientProvider.storageService().getStorageSettings(
            GetStorageSettingsRequest.newBuilder().build(), emptyMap()
        )
        if (resp !is ResponseMessage.Success) return@runBlocking null
        val m = resp.message
        RecordingStorageSettings(
            storageType = m.recordingsStorageType.takeIf { it.isNotEmpty() } ?: "INTERNAL",
            limitMb = m.recordingsLimitMb.takeIf { it > 0 } ?: 500L,
            recordingsSize = m.recordingsSizeBytes,
            recordingsCount = m.recordingsCount.toLong(),
            sdCardAvailable = m.sdCardAvailable,
            sdCardFreeFormatted = m.sdCardFreeFormatted,
            internalFreeFormatted = m.internalFreeFormatted,
            recordingsPath = m.recordingsPath,
            minLimitMb = m.minLimitMb.takeIf { it > 0 } ?: 100L,
            maxLimitMb = m.maxLimitMb.takeIf { it > 0 } ?: 100000L,
            maxLimitMbSdCard = m.maxLimitMbSdCard.takeIf { it > 0 } ?: 100000L,
            internalTotalMb = m.internalTotalBytes / (1024L * 1024L),
            sdCardTotalMb = m.sdCardTotalBytes / (1024L * 1024L),
        )
    }

    fun saveMode(mode: String): Boolean {
        // No ConnectRPC endpoint for /api/recording/mode — fall back to HTTP.
        val jwt = runCatching {
            if (AuthManager.getState() == null) AuthManager.initialize()
            AuthManager.generateJwt()?.takeIf { it.isNotBlank() }
        }.getOrNull() ?: return false
        return runCatching {
            val conn = URL("http://127.0.0.1:8080/api/recording/mode")
                .openConnection(Proxy.NO_PROXY) as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Authorization", "Bearer $jwt")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true; conn.connectTimeout = 5_000; conn.readTimeout = 10_000
            conn.outputStream.use { it.write(JSONObject().apply { put("mode", mode) }.toString().toByteArray()) }
            conn.responseCode == 200
        }.getOrDefault(false)
    }

    fun saveQuality(quality: String, codec: String): Boolean = runBlocking {
        val req = SetQualityRequest.newBuilder()
            .setRecordingQuality(quality)
            .setCodec(codec)
            .build()
        val resp = ConnectClientProvider.settingsService().setQuality(req, emptyMap())
        resp is ResponseMessage.Success && resp.message.success
    }

    fun saveRecordingLimit(segmentMinutes: Int): Boolean = runBlocking {
        val req = SetQualityRequest.newBuilder()
            .setRecordingSegmentMinutes(segmentMinutes)
            .build()
        val resp = ConnectClientProvider.settingsService().setQuality(req, emptyMap())
        resp is ResponseMessage.Success && resp.message.success
    }

    fun saveStorage(storageType: String, limitMb: Long): Boolean = runBlocking {
        val req = SetStorageSettingsRequest.newBuilder()
            .setRecordingsStorageType(storageType)
            .setRecordingsLimitMb(limitMb)
            .build()
        val resp = ConnectClientProvider.storageService().setStorageSettings(req, emptyMap())
        resp is ResponseMessage.Success && resp.message.success
    }

    fun listFormattableVolumes(): List<FormattableVolume> = runBlocking {
        val resp = ConnectClientProvider.storageService().listFormatVolumes(
            ListFormatVolumesRequest.newBuilder().build(), emptyMap()
        )
        if (resp !is ResponseMessage.Success) return@runBlocking emptyList()
        resp.message.volumesList
            .filter { it.mounted }
            .map { v ->
                FormattableVolume(
                    volumeId = v.volumeId,
                    uuid = v.uuid.takeIf { it.isNotEmpty() },
                    mountPath = v.mountPath.takeIf { it.isNotEmpty() },
                )
            }
    }

    fun formatVolume(volumeId: String): FormatDriveResult = runBlocking {
        val resp = ConnectClientProvider.longStorageService().formatVolume(
            FormatVolumeRequest.newBuilder().setVolumeId(volumeId).build(), emptyMap()
        )
        when (resp) {
            is ResponseMessage.Success -> {
                val m = resp.message
                FormatDriveResult(
                    success = m.success,
                    message = m.message.takeIf { it.isNotEmpty() } ?: m.error.takeIf { it.isNotEmpty() } ?: "Unknown result",
                    mountPath = m.mountPath.takeIf { it.isNotEmpty() },
                )
            }
            is ResponseMessage.Failure ->
                FormatDriveResult(false, resp.cause.message ?: "Network error", null)
        }
    }

    fun syncCatalog(): SyncResult = runBlocking {
        val resp = ConnectClientProvider.longRecordingsService().syncCatalog(
            SyncCatalogRequest.newBuilder().build(), emptyMap()
        )
        when (resp) {
            is ResponseMessage.Success -> {
                val m = resp.message
                if (m.success) {
                    SyncResult(true, "Synced: +${m.added} -${m.removed}")
                } else {
                    val err = m.error
                    if (err == "sync_in_progress") SyncResult(false, "Sync already in progress")
                    else SyncResult(false, "Sync failed: $err")
                }
            }
            is ResponseMessage.Failure -> SyncResult(false, resp.cause.message ?: "Network error")
        }
    }
}
