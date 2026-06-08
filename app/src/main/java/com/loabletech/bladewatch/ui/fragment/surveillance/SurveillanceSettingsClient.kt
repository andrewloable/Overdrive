package net.bladewatch.app.ui.fragment.surveillance

import com.connectrpc.ResponseMessage
import kotlinx.coroutines.runBlocking
import net.bladewatch.app.client.ConnectClientProvider
import net.bladewatch.app.grpc.v1.AddZoneRequest
import net.bladewatch.app.grpc.v1.DeleteZoneRequest
import net.bladewatch.app.grpc.v1.DisableSurveillanceRequest
import net.bladewatch.app.grpc.v1.EnableSurveillanceRequest
import net.bladewatch.app.grpc.v1.FormatVolumeRequest
import net.bladewatch.app.grpc.v1.GetStatsRequest
import net.bladewatch.app.grpc.v1.GetStorageSettingsRequest
import net.bladewatch.app.grpc.v1.GetSurveillanceConfigRequest
import net.bladewatch.app.grpc.v1.GetSurveillanceStatusRequest
import net.bladewatch.app.grpc.v1.ListFormatVolumesRequest
import net.bladewatch.app.grpc.v1.ListZonesRequest
import net.bladewatch.app.grpc.v1.SetStorageSettingsRequest
import net.bladewatch.app.grpc.v1.SetSurveillanceConfigRequest
import net.bladewatch.app.grpc.v1.SyncSurveillanceCatalogRequest
import net.bladewatch.app.grpc.v1.ToggleSafeLocationsRequest

internal class SurveillanceSettingsClient {

    fun fetchConfig(): SurveillanceConfig? = runBlocking {
        val resp = ConnectClientProvider.surveillanceService().getConfig(
            GetSurveillanceConfigRequest.newBuilder().build(), emptyMap()
        )
        if (resp !is ResponseMessage.Success || !resp.message.success) return@runBlocking null
        val cfg = resp.message.config
        SurveillanceConfig(
            enabled = cfg.enabled,
            environmentPreset = cfg.distancePreset.takeIf { it.isNotEmpty() } ?: "OUTDOOR",
            sensitivityLevel = cfg.sensitivity.takeIf { it > 0 } ?: 3,
            detectPerson = cfg.detectPerson,
            detectCar = cfg.detectCar,
            detectBike = cfg.detectBike,
            preRecordSeconds = cfg.preRecordSeconds.takeIf { it > 0 } ?: 5,
            postRecordSeconds = cfg.postRecordSeconds.takeIf { it > 0 } ?: 10,
            nightMode = cfg.nightMode,
            aiEnabled = cfg.aiEnabled,
            aiConfidence = cfg.aiConfidence.toFloat().takeIf { it > 0f } ?: 0.4f,
            cameraFront = cfg.cameraFront,
            cameraRight = cfg.cameraRight,
            cameraRear = cfg.cameraRear,
            cameraLeft = cfg.cameraLeft,
            deterrentAction = cfg.deterrentAction.takeIf { it.isNotEmpty() } ?: "silent",
            deterrentCooldownSeconds = cfg.deterrentCooldownSeconds.takeIf { it > 0 } ?: 60,
        )
    }

    fun fetchStatus(): SurveillanceStatus? = runBlocking {
        val statusResp = ConnectClientProvider.surveillanceService().getStatus(
            GetSurveillanceStatusRequest.newBuilder().build(), emptyMap()
        )
        val statsResp = ConnectClientProvider.recordingsService().getStats(
            GetStatsRequest.newBuilder().build(), emptyMap()
        )
        val isRunning = statusResp is ResponseMessage.Success &&
            (statusResp.message.pipelineRunning || statusResp.message.surveillanceActive)
        val eventsToday = if (statsResp is ResponseMessage.Success)
            statsResp.message.stats?.surveillanceCount ?: 0
        else 0
        SurveillanceStatus(isRunning = isRunning, eventsToday = eventsToday)
    }

    fun fetchStorage(): SurveillanceStorageSettings? = runBlocking {
        val resp = ConnectClientProvider.storageService().getStorageSettings(
            GetStorageSettingsRequest.newBuilder().build(), emptyMap()
        )
        if (resp !is ResponseMessage.Success) return@runBlocking null
        val m = resp.message
        SurveillanceStorageSettings(
            storageType = m.surveillanceStorageType.takeIf { it.isNotEmpty() } ?: "INTERNAL",
            limitMb = m.surveillanceLimitMb.takeIf { it > 0 } ?: 500L,
            surveillanceSize = m.surveillanceSizeBytes,
            surveillanceCount = m.surveillanceCount.toLong(),
            sdCardAvailable = m.sdCardAvailable,
            path = m.surveillancePath,
            minLimitMb = m.minLimitMb.takeIf { it > 0 } ?: 100L,
            maxLimitMb = m.maxLimitMb.takeIf { it > 0 } ?: 100000L,
            maxLimitMbSdCard = m.maxLimitMbSdCard.takeIf { it > 0 } ?: 100000L,
            internalTotalMb = m.internalTotalBytes / (1024L * 1024L),
            sdCardTotalMb = m.sdCardTotalBytes / (1024L * 1024L),
        )
    }

    fun fetchSafeLocations(): SafeLocationsState? = runBlocking {
        val resp = ConnectClientProvider.safeLocationsService().listZones(
            ListZonesRequest.newBuilder().build(), emptyMap()
        )
        if (resp !is ResponseMessage.Success) return@runBlocking null
        val m = resp.message
        val zones = m.zonesList.map { z ->
            SafeZone(id = z.id, name = z.name, lat = z.lat, lng = z.lng, radiusM = z.radiusM)
        }
        SafeLocationsState(
            featureEnabled = m.featureEnabled,
            zones = zones,
            hasGps = m.hasGps,
            currentLat = m.currentLat,
            currentLng = m.currentLng,
        )
    }

    fun saveConfig(config: SurveillanceConfig): Boolean = runBlocking {
        val protoConfig = net.bladewatch.app.grpc.v1.SurveillanceConfig.newBuilder()
            .setEnabled(config.enabled)
            .setSensitivity(config.sensitivityLevel)
            .setDistancePreset(config.environmentPreset)
            .setDetectPerson(config.detectPerson)
            .setDetectCar(config.detectCar)
            .setDetectBike(config.detectBike)
            .setPreRecordSeconds(config.preRecordSeconds)
            .setPostRecordSeconds(config.postRecordSeconds)
            .setNightMode(config.nightMode)
            .setAiEnabled(config.aiEnabled)
            .setAiConfidence(config.aiConfidence.toDouble())
            .setCameraFront(config.cameraFront)
            .setCameraRight(config.cameraRight)
            .setCameraRear(config.cameraRear)
            .setCameraLeft(config.cameraLeft)
            .setDeterrentAction(config.deterrentAction)
            .setDeterrentCooldownSeconds(config.deterrentCooldownSeconds)
            .build()
        val req = SetSurveillanceConfigRequest.newBuilder().setConfig(protoConfig).build()
        val resp = ConnectClientProvider.surveillanceService().setConfig(req, emptyMap())
        resp is ResponseMessage.Success && resp.message.success
    }

    fun toggleSurveillance(enable: Boolean): Boolean = runBlocking {
        // Check the message.success field (a 200 with success=false is a failure),
        // matching the sibling Connect calls in this client. The success check is
        // done inside each branch so the concrete response type is preserved
        // (a shared `val` would erase it to the common supertype).
        if (enable) {
            val resp = ConnectClientProvider.surveillanceService().enable(
                EnableSurveillanceRequest.newBuilder().build(), emptyMap()
            )
            resp is ResponseMessage.Success && resp.message.success
        } else {
            val resp = ConnectClientProvider.surveillanceService().disable(
                DisableSurveillanceRequest.newBuilder().build(), emptyMap()
            )
            resp is ResponseMessage.Success && resp.message.success
        }
    }

    fun saveStorage(storageType: String, limitMb: Long): Boolean = runBlocking {
        val req = SetStorageSettingsRequest.newBuilder()
            .setSurveillanceStorageType(storageType)
            .setSurveillanceLimitMb(limitMb)
            .build()
        val resp = ConnectClientProvider.storageService().setStorageSettings(req, emptyMap())
        resp is ResponseMessage.Success && resp.message.success
    }

    fun toggleSafeLocations(enabled: Boolean): Boolean = runBlocking {
        val req = ToggleSafeLocationsRequest.newBuilder()
            .setEnabled(enabled)
            .setEnabledSet(true)
            .build()
        val resp = ConnectClientProvider.safeLocationsService().toggle(req, emptyMap())
        resp is ResponseMessage.Success && resp.message.success
    }

    fun addSafeZone(name: String, lat: Double, lng: Double, radiusM: Int): SafeZone? = runBlocking {
        val req = AddZoneRequest.newBuilder()
            .setName(name)
            .setLat(lat)
            .setLng(lng)
            .setRadiusM(radiusM)
            .build()
        val resp = ConnectClientProvider.safeLocationsService().addZone(req, emptyMap())
        if (resp !is ResponseMessage.Success || !resp.message.success) return@runBlocking null
        val z = resp.message.zone ?: return@runBlocking null
        SafeZone(id = z.id, name = z.name, lat = z.lat, lng = z.lng, radiusM = z.radiusM)
    }

    fun deleteSafeZone(id: String): Boolean = runBlocking {
        val req = DeleteZoneRequest.newBuilder().setId(id).build()
        val resp = ConnectClientProvider.safeLocationsService().deleteZone(req, emptyMap())
        resp is ResponseMessage.Success && resp.message.success
    }

    fun listFormattableVolumes(): List<net.bladewatch.app.ui.fragment.recording.FormattableVolume> = runBlocking {
        val resp = ConnectClientProvider.storageService().listFormatVolumes(
            ListFormatVolumesRequest.newBuilder().build(), emptyMap()
        )
        if (resp !is ResponseMessage.Success) return@runBlocking emptyList()
        resp.message.volumesList
            .filter { it.mounted }
            .map { v ->
                net.bladewatch.app.ui.fragment.recording.FormattableVolume(
                    volumeId = v.volumeId,
                    uuid = v.uuid.takeIf { it.isNotEmpty() },
                    mountPath = v.mountPath.takeIf { it.isNotEmpty() },
                )
            }
    }

    fun syncCatalog(): net.bladewatch.app.ui.fragment.recording.SyncResult = runBlocking {
        val resp = ConnectClientProvider.longSurveillanceService().syncCatalog(
            SyncSurveillanceCatalogRequest.newBuilder().build(), emptyMap()
        )
        when (resp) {
            is ResponseMessage.Success -> {
                val m = resp.message
                if (m.success) {
                    net.bladewatch.app.ui.fragment.recording.SyncResult(
                        true, "Synced: +${m.added} -${m.removed}"
                    )
                } else {
                    val err = m.error
                    if (err == "sync_in_progress")
                        net.bladewatch.app.ui.fragment.recording.SyncResult(false, "Sync already in progress")
                    else
                        net.bladewatch.app.ui.fragment.recording.SyncResult(false, "Sync failed: $err")
                }
            }
            is ResponseMessage.Failure ->
                net.bladewatch.app.ui.fragment.recording.SyncResult(false, resp.cause.message ?: "Network error")
        }
    }

    fun formatVolume(volumeId: String): net.bladewatch.app.ui.fragment.recording.FormatDriveResult = runBlocking {
        val resp = ConnectClientProvider.storageService().formatVolume(
            FormatVolumeRequest.newBuilder().setVolumeId(volumeId).build(), emptyMap()
        )
        when (resp) {
            is ResponseMessage.Success -> {
                val m = resp.message
                net.bladewatch.app.ui.fragment.recording.FormatDriveResult(
                    success = m.success,
                    message = m.message.takeIf { it.isNotEmpty() } ?: m.error.takeIf { it.isNotEmpty() } ?: "Unknown result",
                    mountPath = m.mountPath.takeIf { it.isNotEmpty() },
                )
            }
            is ResponseMessage.Failure ->
                net.bladewatch.app.ui.fragment.recording.FormatDriveResult(false, resp.cause.message ?: "Network error", null)
        }
    }
}
