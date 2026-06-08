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

    private val protocolClient: ProtocolClientInterface by lazy {
        val okHttpClient = OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .addInterceptor(jwtInterceptor)
            .build()
        ProtocolClient(
            httpClient = ConnectOkHttpClient(okHttpClient),
            config = ProtocolClientConfig(
                host = BASE_URL,
                serializationStrategy = GoogleJavaJSONStrategy(),
                networkProtocol = NetworkProtocol.CONNECT,
            )
        )
    }

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

    @JvmStatic fun authService(): AuthServiceClient = AuthServiceClient(protocolClient)
    @JvmStatic fun notificationsService(): NotificationsServiceClient = NotificationsServiceClient(protocolClient)
    @JvmStatic fun recordingsService(): RecordingsServiceClient = RecordingsServiceClient(protocolClient)
    @JvmStatic fun safeLocationsService(): SafeLocationsServiceClient = SafeLocationsServiceClient(protocolClient)
    @JvmStatic fun settingsService(): SettingsServiceClient = SettingsServiceClient(protocolClient)
    @JvmStatic fun storageService(): StorageServiceClient = StorageServiceClient(protocolClient)
    @JvmStatic fun streamService(): StreamServiceClient = StreamServiceClient(protocolClient)
    @JvmStatic fun surveillanceService(): SurveillanceServiceClient = SurveillanceServiceClient(protocolClient)
    @JvmStatic fun systemService(): SystemServiceClient = SystemServiceClient(protocolClient)
    @JvmStatic fun tripsService(): TripsServiceClient = TripsServiceClient(protocolClient)
    @JvmStatic fun updatesService(): UpdateServiceClient = UpdateServiceClient(protocolClient)
    @JvmStatic fun vehicleService(): VehicleServiceClient = VehicleServiceClient(protocolClient)
}
