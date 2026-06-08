package net.bladewatch.app.client

import com.connectrpc.extensions.GoogleJavaJSONStrategy
import com.connectrpc.impl.ProtocolClient
import com.connectrpc.ProtocolClientConfig
import com.connectrpc.ProtocolClientInterface
import com.connectrpc.okhttp.ConnectOkHttpClient
import com.connectrpc.protocols.NetworkProtocol
import net.bladewatch.app.auth.AuthManager
import net.bladewatch.app.daemon.CameraDaemon
import net.bladewatch.app.grpc.v1.AuthServiceClient
import net.bladewatch.app.grpc.v1.NotificationsServiceClient
import net.bladewatch.app.grpc.v1.RecordingsServiceClient
import net.bladewatch.app.grpc.v1.SafeLocationsServiceClient
import net.bladewatch.app.grpc.v1.SettingsServiceClient
import net.bladewatch.app.grpc.v1.StorageServiceClient
import net.bladewatch.app.grpc.v1.StreamServiceClient
import net.bladewatch.app.grpc.v1.SurveillanceServiceClient
import net.bladewatch.app.grpc.v1.SystemServiceClient
import net.bladewatch.app.grpc.v1.TripsServiceClient
import net.bladewatch.app.grpc.v1.UpdateServiceClient
import net.bladewatch.app.grpc.v1.VehicleServiceClient
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * Singleton ConnectRPC client provider for the Android app.
 *
 * Lazily initialises a single OkHttpClient + ProtocolClient. All service
 * accessors share the same transport. JWT caching mirrors DaemonHttpClient:
 * 4-minute window keyed to AuthManager.getStateVersion() so a rotated secret
 * triggers an immediate re-mint without waiting out the TTL.
 */
object ConnectClientProvider {

    private const val BASE_URL = "http://127.0.0.1:${CameraDaemon.HTTP_PORT}"
    private const val JWT_CACHE_TTL_MS = 4 * 60 * 1000L

    @Volatile private var cachedJwt: String? = null
    @Volatile private var cachedAt: Long = 0L
    @Volatile private var cachedStateVersion: Long = -1L

    private fun buildProtocolClient(readTimeoutSecs: Long): ProtocolClientInterface {
        val okHttpClient = OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(readTimeoutSecs, TimeUnit.SECONDS)
            .addInterceptor(jwtInterceptor)
            .build()
        return ProtocolClient(
            httpClient = ConnectOkHttpClient(okHttpClient),
            config = ProtocolClientConfig(
                host = BASE_URL,
                serializationStrategy = GoogleJavaJSONStrategy(),
                networkProtocol = NetworkProtocol.CONNECT,
            )
        )
    }

    private val protocolClient: ProtocolClientInterface by lazy { buildProtocolClient(10) }

    // Long-timeout client for slow operations: volume format (~30-60s), trips DB sync (~120s).
    private val longTimeoutClient: ProtocolClientInterface by lazy { buildProtocolClient(120) }

    private val jwtInterceptor = Interceptor { chain ->
        val jwt = currentJwt()
        val req = if (jwt != null) {
            chain.request().newBuilder()
                .header("Authorization", "Bearer $jwt")
                .build()
        } else {
            chain.request()
        }
        chain.proceed(req)
    }

    private fun currentJwt(): String? {
        val now = System.currentTimeMillis()
        val cur = cachedJwt
        val curVersion = AuthManager.getStateVersion()
        if (cur != null && (now - cachedAt) < JWT_CACHE_TTL_MS && cachedStateVersion == curVersion) return cur

        val fresh = try {
            if (AuthManager.getState() == null) AuthManager.initialize()
            AuthManager.generateJwt()
        } catch (e: Exception) {
            null
        }
        if (fresh != null) {
            cachedJwt = fresh
            cachedAt = now
            cachedStateVersion = AuthManager.getStateVersion()
        }
        return fresh
    }

    /** Clear the cached JWT — call when the device token is regenerated. */
    @JvmStatic
    fun invalidate() {
        cachedJwt = null
        cachedAt = 0L
        cachedStateVersion = -1L
    }

    private val authSvc by lazy { AuthServiceClient(protocolClient) }
    private val notificationsSvc by lazy { NotificationsServiceClient(protocolClient) }
    private val recordingsSvc by lazy { RecordingsServiceClient(protocolClient) }
    private val longRecordingsSvc by lazy { RecordingsServiceClient(longTimeoutClient) }
    private val safeLocationsSvc by lazy { SafeLocationsServiceClient(protocolClient) }
    private val settingsSvc by lazy { SettingsServiceClient(protocolClient) }
    private val storageSvc by lazy { StorageServiceClient(protocolClient) }
    private val longStorageSvc by lazy { StorageServiceClient(longTimeoutClient) }
    private val streamSvc by lazy { StreamServiceClient(protocolClient) }
    private val surveillanceSvc by lazy { SurveillanceServiceClient(protocolClient) }
    private val longSurveillanceSvc by lazy { SurveillanceServiceClient(longTimeoutClient) }
    private val systemSvc by lazy { SystemServiceClient(protocolClient) }
    private val tripsSvc by lazy { TripsServiceClient(protocolClient) }
    private val longTripsSvc by lazy { TripsServiceClient(longTimeoutClient) }
    private val updatesSvc by lazy { UpdateServiceClient(protocolClient) }
    private val vehicleSvc by lazy { VehicleServiceClient(protocolClient) }

    @JvmStatic fun authService(): AuthServiceClient = authSvc
    @JvmStatic fun notificationsService(): NotificationsServiceClient = notificationsSvc
    @JvmStatic fun recordingsService(): RecordingsServiceClient = recordingsSvc
    @JvmStatic fun longRecordingsService(): RecordingsServiceClient = longRecordingsSvc
    @JvmStatic fun safeLocationsService(): SafeLocationsServiceClient = safeLocationsSvc
    @JvmStatic fun settingsService(): SettingsServiceClient = settingsSvc
    @JvmStatic fun storageService(): StorageServiceClient = storageSvc
    @JvmStatic fun longStorageService(): StorageServiceClient = longStorageSvc
    @JvmStatic fun streamService(): StreamServiceClient = streamSvc
    @JvmStatic fun surveillanceService(): SurveillanceServiceClient = surveillanceSvc
    @JvmStatic fun longSurveillanceService(): SurveillanceServiceClient = longSurveillanceSvc
    @JvmStatic fun systemService(): SystemServiceClient = systemSvc
    @JvmStatic fun tripsService(): TripsServiceClient = tripsSvc
    @JvmStatic fun longTripsService(): TripsServiceClient = longTripsSvc
    @JvmStatic fun updatesService(): UpdateServiceClient = updatesSvc
    @JvmStatic fun vehicleService(): VehicleServiceClient = vehicleSvc
}
