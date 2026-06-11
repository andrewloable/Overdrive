package net.bladewatch.app.ui.fragment.recording

import com.connectrpc.ResponseMessage
import kotlinx.coroutines.runBlocking
import net.bladewatch.app.client.ConnectClientProvider
import net.bladewatch.app.ui.common.SaveResult
import net.bladewatch.app.grpc.v1.FormatVolumeRequest
import net.bladewatch.app.grpc.v1.GetQualityRequest
import net.bladewatch.app.grpc.v1.GetStatsRequest
import net.bladewatch.app.grpc.v1.GetStatusRequest
import net.bladewatch.app.grpc.v1.GetStorageSettingsRequest
import net.bladewatch.app.grpc.v1.ListFormatVolumesRequest
import net.bladewatch.app.grpc.v1.SetQualityRequest
import net.bladewatch.app.grpc.v1.SetRecordingModeRequest
import net.bladewatch.app.grpc.v1.SetStorageSettingsRequest
import net.bladewatch.app.grpc.v1.SyncCatalogRequest

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

    fun saveMode(mode: String): SaveResult = runBlocking {
        // Recording mode goes through the SettingsService.SetRecordingMode Connect
        // RPC like every other client call (BladeWatch-pg0s removed the former raw
        // loopback-HTTP bypass). Auth is handled by the shared ConnectClientProvider
        // interceptor, so no per-call JWT plumbing is needed here.
        val req = SetRecordingModeRequest.newBuilder().setMode(mode).build()
        when (val resp = ConnectClientProvider.settingsService().setRecordingMode(req, emptyMap())) {
            is ResponseMessage.Success -> {
                val m = resp.message
                SaveResult(m.success, m.error.takeIf { it.isNotEmpty() })
            }
            is ResponseMessage.Failure -> SaveResult(false, resp.cause.message ?: "Network error")
        }
    }

    fun saveQuality(quality: String, codec: String): SaveResult = runBlocking {
        val req = SetQualityRequest.newBuilder()
            .setRecordingQuality(quality)
            .setCodec(codec)
            .build()
        when (val resp = ConnectClientProvider.settingsService().setQuality(req, emptyMap())) {
            is ResponseMessage.Success -> {
                val m = resp.message
                val err = m.error.takeIf { it.isNotEmpty() } ?: m.message.takeIf { !m.success && it.isNotEmpty() }
                SaveResult(m.success, err)
            }
            is ResponseMessage.Failure -> SaveResult(false, resp.cause.message ?: "Network error")
        }
    }

    fun saveRecordingLimit(segmentMinutes: Int): SaveResult = runBlocking {
        val req = SetQualityRequest.newBuilder()
            .setRecordingSegmentMinutes(segmentMinutes)
            .build()
        when (val resp = ConnectClientProvider.settingsService().setQuality(req, emptyMap())) {
            is ResponseMessage.Success -> {
                val m = resp.message
                SaveResult(m.success, m.error.takeIf { it.isNotEmpty() })
            }
            is ResponseMessage.Failure -> SaveResult(false, resp.cause.message ?: "Network error")
        }
    }

    fun saveStorage(storageType: String, limitMb: Long): SaveResult = runBlocking {
        val req = SetStorageSettingsRequest.newBuilder()
            .setRecordingsStorageType(storageType)
            .setRecordingsLimitMb(limitMb)
            .build()
        when (val resp = ConnectClientProvider.storageService().setStorageSettings(req, emptyMap())) {
            is ResponseMessage.Success -> {
                val m = resp.message
                SaveResult(m.success, m.error.takeIf { it.isNotEmpty() })
            }
            is ResponseMessage.Failure -> SaveResult(false, resp.cause.message ?: "Network error")
        }
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
