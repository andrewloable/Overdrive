# Data Flow and Storage

Overdrive coordinates data across the Android app process, shell-launched Java daemons, native camera code, web assets, tunnel binaries, and BYD local/cloud sources. Most cross-process state is intentionally stored in files under `/data/local/tmp`.

## Primary Data Flows

### Camera to Recording

```text
BYD camera HAL / Android camera feed
  -> PanoramicCameraGpu
  -> GPU texture and mosaic pipeline
  -> GpuMosaicRecorder
  -> segmented video files
  -> StorageManager
  -> /storage/emulated/0/Overdrive/recordings or configured external storage
```

The camera pipeline uses GPU paths and native helpers to avoid expensive CPU copies where possible.

### Camera to Live Stream

```text
Camera frame
  -> GpuSurveillancePipeline
  -> stream scaler and encoder
  -> WebSocketStreamServer / HttpServer WebSocket upgrade
  -> browser client
```

Live streaming is separate from recording. The server handles H.264 headers, cached SPS/PPS, IDR requests, and frame fragmentation.

### Camera to Surveillance Event

```text
Camera frame
  -> GPU downscale
  -> native motion pipeline
  -> per-quadrant motion state
  -> optional TFLite YOLO gate
  -> surveillance decision
  -> event recording and notification
  -> optional BYD cloud deterrent command
```

Surveillance uses motion detection first and AI as a gated assist. Event windows include pre-event and post-event recording.

### Web UI to Daemon

```text
Browser or Android WebView
  -> http://127.0.0.1:8080
  -> AuthMiddleware
  -> HttpServer route handlers
  -> daemon managers, config, storage, camera, BYD, MQTT, trips
```

The Android WebView injects auth cookies and JavaScript bridge behavior so mutating API calls can bypass local proxy interference.

### Android App to Daemon

```text
Android UI or service
  -> CameraDaemonClient
  -> TCP JSON command on 127.0.0.1:19876
  -> CameraDaemon command handlers
```

The TCP command server provides control for recording, streaming, status, storage, auth invalidation, and secret/config bridge operations.

### Location Sidecar to Surveillance IPC

```text
Android LocationSidecarService
  -> GPS cache in app files
  -> TCP JSON command UPDATE_GPS on 127.0.0.1:19877
  -> SurveillanceIpcServer
  -> surveillance/trip/telemetry consumers
```

The sidecar sends GPS updates roughly every two seconds while running.

### BYD Local Telemetry

```text
BYD framework device classes
  -> reflection helpers and listener proxies
  -> BydDataCollector
  -> BydVehicleData snapshot
  -> telemetry, web APIs, trips, MQTT, ABRP, performance pages
```

The collector reads initial values, registers listeners, and polls at different intervals depending on ACC state.

### BYD Cloud Data

```text
BYD cloud HTTPS login and discovery
  -> BydCloudClient
  -> MQTT credential discovery
  -> BydCloudMqttSubscriber
  -> decrypted vehicleInfo pushes
  -> BydCloudDataProvider
  -> merged cloud snapshot
```

Cloud data can supplement local telemetry where configured.

## Configuration Files

### Unified Config

Main config path:

```text
/data/local/tmp/overdrive_config.json
```

`UnifiedConfigManager` is the main config source. It stores app and daemon settings for:

- Surveillance.
- Recording.
- Streaming.
- Telegram.
- Network.
- Proximity guard.
- Telemetry overlay.
- Trip analytics.
- Status overlay.
- BYD cloud.
- Vehicle appearance/model.
- Auth public state.

Writes use an atomic temporary-file-and-rename strategy where possible, with a direct-write fallback for app UID limitations in `/data/local/tmp`.

Legacy configs may be migrated from:

- `/data/local/tmp/sentry_config.json`.
- `/data/local/tmp/camera_settings.json`.
- `/data/data/com.android.providers.settings/sentry_config.json`.

### Secret Store

Main secret path:

```text
/data/local/tmp/overdrive_secrets.json
```

`SecretConfigStore` stores secret sections such as auth device secret, tunnel tokens, cloud credentials, and integration secrets. The intended permissions are owner-only. Direct writes are restricted to shell UID where practical; the Android app uses the TCP bridge when it cannot access the file directly.

Sensitive values must not be logged or copied into docs.

### Device Identity

The auth manager uses a device id and secret to derive local access tokens. Legacy identity state includes:

```text
/data/local/tmp/.overdrive_device_id
/data/local/tmp/.byd_auth.json
```

The current release auth model uses a JWT HMAC secret stored through the secret/config bridge.

## Media Storage

Main media base directory:

```text
/storage/emulated/0/Overdrive
```

Common subdirectories:

- `recordings`.
- `surveillance`.
- `proximity`.
- `trips`.

`StorageSetup` prepares the app-owned external storage directory and requests or grants storage permissions. `StorageManager` also detects external SD-card-style paths and can manage separate storage choices for recordings, surveillance, and trips.

Storage cleanup behavior includes:

- Default storage limit around `500 MB`.
- Minimum supported limit around `100 MB`.
- Maximum supported limit around `100 GB`.
- Periodic cleanup checks around every `30 seconds`.
- Avoiding storage-directory switches while recording or surveillance is active.

## Web Assets

Source assets:

```text
app/src/main/assets/web/
```

Runtime extracted assets:

```text
/data/local/tmp/web
/data/local/tmp/overlay
/data/local/tmp/bangcle_tables.bin
```

`HttpServer` extracts web, overlay, and Bangcle table assets when the daemon starts. Gradle also defines an `extractWebAssets` helper task that can push web assets to `/data/local/tmp/web` during development.

## Tunnel and Proxy Runtime Files

Cloudflared:

```text
/data/local/tmp/cloudflared
/data/local/tmp/cloudflared.log
```

Zrok:

```text
/data/local/tmp/zrok
/data/local/tmp/zrok.log
/data/local/tmp/.zrok/environment.json
/data/local/tmp/.zrok/unique_name
```

Tailscale:

```text
/data/local/tmp/.tailscale/tailscale
/data/local/tmp/.tailscale/tailscaled
```

sing-box:

```text
/data/local/tmp/sing-box
/data/local/tmp/singbox_config.json
/data/local/tmp/singbox.log
```

## Auth Data Flow

```text
Client requests /auth/token
  -> AuthApiHandler
  -> AuthManager validates device token
  -> JWT issued with token epoch
  -> client stores Bearer token or byd_session cookie
  -> AuthMiddleware validates future requests
```

Release builds require JWT auth even for loopback requests because Android loopback is shared. Debug loopback bypass exists only when tunnel-forwarding headers are absent.

Public paths are limited to auth bootstrap, login/static shell assets, manifest/service worker, shared assets, and i18n assets.

## Trip Data Flow

```text
Telemetry and GPS inputs
  -> trip analytics collectors
  -> trip storage
  -> TripApiHandler
  -> web trips pages and external integrations
```

Trip APIs expose lists, details, telemetry, similar trips, GPS traces, summary, driving DNA, range analytics, config, and storage management.

## Notification Data Flow

```text
Daemon or web event
  -> notification manager/API
  -> Android notification or push subscription target
  -> web notification state APIs
```

Notification APIs expose categories, push subscription management, preferences, and test delivery.

## Data Ownership Summary

- Android app owns user-visible lifecycle, permissions, UI navigation, WebView session setup, and foreground service lifecycles.
- Camera daemon owns camera state, recording state, HTTP APIs, auth enforcement, streaming, and most runtime telemetry.
- Shared files under `/data/local/tmp` allow app and daemons to coordinate.
- Media files live under `/storage/emulated/0/Overdrive` or configured external storage.
- BYD local data is read from firmware APIs and kept in memory snapshots.
- BYD cloud credentials and tunnel secrets belong in the secret store.

## Source References

- Camera-to-recording path: [PanoramicCameraGpu.java:39](../app/src/main/java/com/overdrive/app/camera/PanoramicCameraGpu.java#L39), [GpuMosaicRecorder.java:31](../app/src/main/java/com/overdrive/app/surveillance/GpuMosaicRecorder.java#L31), [HardwareEventRecorderGpu.java:58](../app/src/main/java/com/overdrive/app/surveillance/HardwareEventRecorderGpu.java#L58), [StorageManager.java:1921](../app/src/main/java/com/overdrive/app/storage/StorageManager.java#L1921).
- Live-stream path: [GpuSurveillancePipeline.java:30](../app/src/main/java/com/overdrive/app/surveillance/GpuSurveillancePipeline.java#L30), [WebSocketStreamServer.java:19](../app/src/main/java/com/overdrive/app/streaming/WebSocketStreamServer.java#L19), [HttpServer.java:538](../app/src/main/java/com/overdrive/app/server/HttpServer.java#L538).
- Surveillance-event path: [GpuDownscaler.java:51](../app/src/main/java/com/overdrive/app/surveillance/GpuDownscaler.java#L51), [SurveillanceEngineGpu.java:708](../app/src/main/java/com/overdrive/app/surveillance/SurveillanceEngineGpu.java#L708), [SurveillanceEngineGpu.java:3248](../app/src/main/java/com/overdrive/app/surveillance/SurveillanceEngineGpu.java#L3248), [BydCloudDeterrent.java:33](../app/src/main/java/com/overdrive/app/byd/cloud/BydCloudDeterrent.java#L33).
- Web UI to daemon: [HttpServer.java:49](../app/src/main/java/com/overdrive/app/server/HttpServer.java#L49), [AuthMiddleware.java:133](../app/src/main/java/com/overdrive/app/server/AuthMiddleware.java#L133), [WebViewFragment.kt:228](../app/src/main/java/com/overdrive/app/ui/fragment/WebViewFragment.kt#L228).
- App TCP client to daemon: [CameraDaemonClient.java:24](../app/src/main/java/com/overdrive/app/client/CameraDaemonClient.java#L24), [TcpCommandServer.java:22](../app/src/main/java/com/overdrive/app/server/TcpCommandServer.java#L22), [CameraDaemon.java:53](../app/src/main/java/com/overdrive/app/daemon/CameraDaemon.java#L53).
- Location IPC: [LocationSidecarService.java:32](../app/src/main/java/com/overdrive/app/services/LocationSidecarService.java#L32), [SurveillanceIpcServer.java:22](../app/src/main/java/com/overdrive/app/server/SurveillanceIpcServer.java#L22), [CameraDaemon.java:350](../app/src/main/java/com/overdrive/app/daemon/CameraDaemon.java#L350).
- BYD local and cloud data flows: [BydDataCollector.java:20](../app/src/main/java/com/overdrive/app/byd/BydDataCollector.java#L20), [BydCloudClient.java:22](../app/src/main/java/com/overdrive/app/byd/cloud/BydCloudClient.java#L22), [BydCloudMqttSubscriber.java:31](../app/src/main/java/com/overdrive/app/byd/cloud/BydCloudMqttSubscriber.java#L31), [BydCloudDataProvider.java:14](../app/src/main/java/com/overdrive/app/byd/cloud/BydCloudDataProvider.java#L14).
- Unified config, secrets, and auth identity: [UnifiedConfigManager.kt:30](../app/src/main/java/com/overdrive/app/config/UnifiedConfigManager.kt#L30), [SecretConfigStore.kt:22](../app/src/main/java/com/overdrive/app/config/SecretConfigStore.kt#L22), [AuthManager.java:50](../app/src/main/java/com/overdrive/app/auth/AuthManager.java#L50), [AuthManager.java:349](../app/src/main/java/com/overdrive/app/auth/AuthManager.java#L349).
- Media and SD-card storage: [StorageManager.java:100](../app/src/main/java/com/overdrive/app/storage/StorageManager.java#L100), [StorageManager.java:120](../app/src/main/java/com/overdrive/app/storage/StorageManager.java#L120), [StorageManager.java:404](../app/src/main/java/com/overdrive/app/storage/StorageManager.java#L404), [StorageManager.java:1671](../app/src/main/java/com/overdrive/app/storage/StorageManager.java#L1671), [StorageManager.java:1685](../app/src/main/java/com/overdrive/app/storage/StorageManager.java#L1685).
- Runtime assets and tunnel files: [build.gradle.kts:232](../app/build.gradle.kts#L232), [HttpServer.java:49](../app/src/main/java/com/overdrive/app/server/HttpServer.java#L49), [TunnelLauncher.kt:12](../app/src/main/java/com/overdrive/app/launcher/TunnelLauncher.kt#L12), [ZrokLauncher.kt:27](../app/src/main/java/com/overdrive/app/launcher/ZrokLauncher.kt#L27), [TailscaleLauncher.kt:11](../app/src/main/java/com/overdrive/app/launcher/TailscaleLauncher.kt#L11), [ProxyConfiguration.kt:29](../app/src/main/java/com/overdrive/app/daemon/proxy/ProxyConfiguration.kt#L29).
- Trips and notifications: [TripDetector.java:27](../app/src/main/java/com/overdrive/app/trips/TripDetector.java#L27), [TripAnalyticsManager.java:23](../app/src/main/java/com/overdrive/app/trips/TripAnalyticsManager.java#L23), [TripApiHandler.java:35](../app/src/main/java/com/overdrive/app/trips/TripApiHandler.java#L35), [NotificationApiHandler.java:30](../app/src/main/java/com/overdrive/app/server/NotificationApiHandler.java#L30).
