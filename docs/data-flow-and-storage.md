# Data Flow and Storage

BladeWatch coordinates data across the Android app process, shell-launched Java daemons, native camera code, web assets, the Zrok tunnel binary, and BYD local sources. Most cross-process state is intentionally stored in files under `/data/local/tmp`.

## Primary Data Flows

### Camera to Recording

```text
BYD camera HAL / Android camera feed
  -> PanoramicCameraGpu
  -> GPU texture and mosaic pipeline
  -> GpuMosaicRecorder
  -> segmented video files
  -> StorageManager
  -> /storage/emulated/0/BladeWatch/recordings or configured external storage
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
  -> event recording and Web Push notification
```

Surveillance uses motion detection first and AI as a gated assist. Event windows include pre-event and post-event recording.

### Web UI to Daemon

```text
Browser or Android WebView
  -> http://127.0.0.1:8080
  -> AuthMiddleware
  -> HttpServer route handlers
  -> daemon managers, config, storage, camera, trips
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
  -> telemetry, web APIs, trips, performance pages
```

The collector reads initial values, registers listeners, and polls at different intervals depending on ACC state.

## Configuration Files

### Unified Config

Main config path:

```text
/storage/emulated/0/BladeWatch/data/bladewatch_config.json
```

This lives under the user-visible BladeWatch tree (not app-scoped external
files), so it survives app uninstall/reinstall and updates. A `/data/local/tmp/bladewatch_config.json`
mirror is still written for older hardcoded readers (`StorageManager`,
`SurveillanceConfigManager`). On first run after the relocation, a prior
unified config at `/storage/emulated/0/Android/data/net.bladewatch.app/files/bladewatch_config.json`
or the `/data/local/tmp` mirror is promoted to the new path rather than rebuilt.

`UnifiedConfigManager` is the main config source. It stores app and daemon settings for:

- Surveillance.
- Recording.
- Streaming.
- Network.
- Proximity guard.
- Telemetry overlay.
- Trip analytics.
- Status overlay.
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
/data/local/tmp/bladewatch_secrets.json
```

`SecretConfigStore` stores secret sections such as auth device secret and tunnel tokens. The intended permissions are owner-only. Direct writes are restricted to shell UID where practical; the Android app uses the TCP bridge when it cannot access the file directly.

Sensitive values must not be logged or copied into docs.

### Device Identity

The auth manager uses a device id and secret to derive local access tokens. Legacy identity state includes:

```text
/data/local/tmp/.bladewatch_device_id
/data/local/tmp/.byd_auth.json
```

The current release auth model uses a JWT HMAC secret stored through the secret/config bridge.

## Media Storage

Main media base directory:

```text
/storage/emulated/0/BladeWatch
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
```

`HttpServer` extracts web and overlay assets when the daemon starts. Gradle also defines an `extractWebAssets` helper task that can push web assets to `/data/local/tmp/web` during development.

## Tunnel Runtime Files

Zrok:

```text
/data/local/tmp/zrok
/data/local/tmp/zrok.log
/data/local/tmp/.zrok/environment.json
/data/local/tmp/.zrok/unique_name
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
  -> web trips pages
```

Trip APIs expose lists, details, telemetry, similar trips, GPS traces, summary, driving DNA, range analytics, config, and storage management.

Trip catalog, rollups, and consumption buckets are persisted in an H2 embedded
database under the user-visible BladeWatch tree so trip history survives app
uninstall/reinstall and updates:

```text
/storage/emulated/0/BladeWatch/data/bladewatch_trips_h2.mv.db
```

`TripDatabase` migrates an existing database from the old `/data/local/tmp/bladewatch_trips_h2.mv.db`
location once, on first init after the relocation. Trip telemetry sample files
remain under the `trips` media subdirectory and are governed by `StorageManager`.

A manual reconcile (`POST /api/trips/sync`) prunes trip rows whose telemetry
`.jsonl.gz` file is missing (legacy rows with no `telemetry_file_path` are
preserved), then re-indexes orphan telemetry files as minimal trip records
(start/end/duration/distance derived from GPS; SOC and driving-DNA scores set to 0
because telemetry samples carry no SOC field).

## Media Catalog (Recordings + Surveillance + Proximity)

Recordings, surveillance clips, and proximity clips are indexed in a second H2 database:

```text
/storage/emulated/0/BladeWatch/data/bladewatch_media_h2.mv.db
```

Owned by `MediaCatalogManager` (initialized by `CameraDaemon` alongside the trips
DB). The single `recordings` table stores one row per `.mp4` clip; the natural key
is the absolute file path. Indexes on `timestamp_ms` and `type` make the common
list-by-type-and-date queries fast.

### Live indexing

Clips are indexed the moment they are finalized:

- Normal cam recordings — `AvmByteCallbackProbe.promoteFile()` after the
  `tmp→final` rename.
- Event / proximity recordings — `StorageManager.onFileSaved()` at the finalize
  convergence point.
- Sidecar enrichment — `EventTimelineCollector.writeJsonSidecar()` triggers a
  second upsert (MERGE by path) so the row is enriched with actor/severity data as
  soon as the `.json` sidecar is written; the two-phase upsert is idempotent.

### DB-first reads with lazy auto-rebuild

`RecordingsApiHandler` reads from the DB for list, dates, and stats queries. If the
DB is empty (fresh install, DB wipe, or pre-existing clips), one reconcile fires
automatically (`MediaCatalogManager.ensureIndexedOnce`) so the UI is never blank.
The filesystem scan stays as both the rebuild mechanism and the fallback if the DB
is unavailable.

### Manual sync

`POST /api/recordings/sync` (and the alias `POST /api/surveillance/sync`) run a
full reconcile: scan all recording/surveillance/proximity directories, diff against
`getAllPathState()` (path→[size, mtime, sidecar-mtime]), add/update/remove as
needed. Returns `{success, added, updated, removed, total}`. A concurrent call
returns `{success:false, error:"sync_in_progress"}` without blocking.

## Notification Data Flow

```text
Daemon or surveillance event
  -> notification manager/API
  -> Web Push subscription target
  -> web notification state APIs
```

Notification APIs expose categories, push subscription management, preferences, and test delivery.

## Data Ownership Summary

- Android app owns user-visible lifecycle, permissions, UI navigation, WebView session setup, and foreground service lifecycles.
- Camera daemon owns camera state, recording state, HTTP APIs, auth enforcement, streaming, and most runtime telemetry.
- Shared files under `/data/local/tmp` allow app and daemons to coordinate.
- Media files live under `/storage/emulated/0/BladeWatch` or configured external storage.
- BYD local data is read from firmware APIs and kept in memory snapshots.
- Tunnel secrets belong in the secret store.

## Source References

- Camera-to-recording path: [PanoramicCameraGpu.java:39](../app/src/main/java/com/loabletech/bladewatch/camera/PanoramicCameraGpu.java#L39), [GpuMosaicRecorder.java:31](../app/src/main/java/com/loabletech/bladewatch/surveillance/GpuMosaicRecorder.java#L31), [HardwareEventRecorderGpu.java:58](../app/src/main/java/com/loabletech/bladewatch/surveillance/HardwareEventRecorderGpu.java#L58), [StorageManager.java:1921](../app/src/main/java/com/loabletech/bladewatch/storage/StorageManager.java#L1921).
- Live-stream path: [GpuSurveillancePipeline.java:30](../app/src/main/java/com/loabletech/bladewatch/surveillance/GpuSurveillancePipeline.java#L30), [WebSocketStreamServer.java:19](../app/src/main/java/com/loabletech/bladewatch/streaming/WebSocketStreamServer.java#L19), [HttpServer.java:538](../app/src/main/java/com/loabletech/bladewatch/server/HttpServer.java#L538).
- Surveillance-event path: [GpuDownscaler.java:51](../app/src/main/java/com/loabletech/bladewatch/surveillance/GpuDownscaler.java#L51), [SurveillanceEngineGpu.java:708](../app/src/main/java/com/loabletech/bladewatch/surveillance/SurveillanceEngineGpu.java#L708), [SurveillanceEngineGpu.java:3248](../app/src/main/java/com/loabletech/bladewatch/surveillance/SurveillanceEngineGpu.java#L3248).
- Web UI to daemon: [HttpServer.java:49](../app/src/main/java/com/loabletech/bladewatch/server/HttpServer.java#L49), [AuthMiddleware.java:133](../app/src/main/java/com/loabletech/bladewatch/server/AuthMiddleware.java#L133), [WebViewFragment.kt:228](../app/src/main/java/com/loabletech/bladewatch/ui/fragment/WebViewFragment.kt#L228).
- App TCP client to daemon: [CameraDaemonClient.java:24](../app/src/main/java/com/loabletech/bladewatch/client/CameraDaemonClient.java#L24), [TcpCommandServer.java:22](../app/src/main/java/com/loabletech/bladewatch/server/TcpCommandServer.java#L22), [CameraDaemon.java:53](../app/src/main/java/com/loabletech/bladewatch/daemon/CameraDaemon.java#L53).
- Location IPC: [LocationSidecarService.java:32](../app/src/main/java/com/loabletech/bladewatch/services/LocationSidecarService.java#L32), [SurveillanceIpcServer.java:22](../app/src/main/java/com/loabletech/bladewatch/server/SurveillanceIpcServer.java#L22), [CameraDaemon.java:350](../app/src/main/java/com/loabletech/bladewatch/daemon/CameraDaemon.java#L350).
- BYD local data flow: [BydDataCollector.java:20](../app/src/main/java/com/loabletech/bladewatch/byd/BydDataCollector.java#L20).
- Unified config, secrets, and auth identity: [UnifiedConfigManager.kt:30](../app/src/main/java/com/loabletech/bladewatch/config/UnifiedConfigManager.kt#L30), [SecretConfigStore.kt:22](../app/src/main/java/com/loabletech/bladewatch/config/SecretConfigStore.kt#L22), [AuthManager.java:50](../app/src/main/java/com/loabletech/bladewatch/auth/AuthManager.java#L50), [AuthManager.java:349](../app/src/main/java/com/loabletech/bladewatch/auth/AuthManager.java#L349).
- Media and SD-card storage: [StorageManager.java:100](../app/src/main/java/com/loabletech/bladewatch/storage/StorageManager.java#L100), [StorageManager.java:120](../app/src/main/java/com/loabletech/bladewatch/storage/StorageManager.java#L120), [StorageManager.java:404](../app/src/main/java/com/loabletech/bladewatch/storage/StorageManager.java#L404), [StorageManager.java:1671](../app/src/main/java/com/loabletech/bladewatch/storage/StorageManager.java#L1671), [StorageManager.java:1685](../app/src/main/java/com/loabletech/bladewatch/storage/StorageManager.java#L1685).
- Runtime assets and tunnel files: [build.gradle.kts:232](../app/build.gradle.kts#L232), [HttpServer.java:49](../app/src/main/java/com/loabletech/bladewatch/server/HttpServer.java#L49), [ZrokLauncher.kt:27](../app/src/main/java/com/loabletech/bladewatch/launcher/ZrokLauncher.kt#L27).
- Trips and notifications: [TripDetector.java:27](../app/src/main/java/com/loabletech/bladewatch/trips/TripDetector.java#L27), [TripAnalyticsManager.java:23](../app/src/main/java/com/loabletech/bladewatch/trips/TripAnalyticsManager.java#L23), [TripApiHandler.java:35](../app/src/main/java/com/loabletech/bladewatch/trips/TripApiHandler.java#L35), [NotificationApiHandler.java:30](../app/src/main/java/com/loabletech/bladewatch/server/NotificationApiHandler.java#L30).
