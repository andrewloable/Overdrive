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
/storage/emulated/0/Android/data/net.bladewatch.app/files/bladewatch_secrets.json
```

Like the unified config, the secret store now lives under the user-visible
BladeWatch app-files tree so secrets survive uninstall/reinstall. A
`/data/local/tmp/bladewatch_secrets.json` legacy mirror is still best-effort
written for older hardcoded readers, and an existing legacy file is migrated to
the new path on first init.

`SecretConfigStore` stores secret sections such as auth device secret and tunnel tokens. The file is created with owner-only (`rw-------`) permissions. Direct writes are restricted to shell UID where practical; the Android app uses the TCP bridge when it cannot access the file directly.

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

### Storage Priority (Auto-Select on Startup)

On daemon startup, `StorageManager.applyAutoStoragePriority()` resolves which physical drive to use:

1. **SD card** — discovered via `sm list-volumes all` and a write probe under `/storage/<uuid>`. If a `BladeWatch/` folder exists it is preferred; if not, the folder is created.
2. **USB drive** — if no SD card, scans `/mnt/usb*` and `/storage/usb*` for the first writable directory and applies the same BladeWatch/ creation logic.
3. **Internal storage** (`/storage/emulated/0/BladeWatch`) — fallback when no external drive is found.

The resolved storage type is persisted to the unified config (`recordingsStorageType`, `surveillanceStorageType`, `tripsStorageType`). The scan runs unconditionally on every boot so inserting or removing a drive between reboots is always reflected. All three storage types (recordings, surveillance, trips) are set to the same device.

The SD-card watchdog (`startSdCardWatchdog`) starts after the priority scan and keeps the selected drive mounted during sentry mode.

Storage cleanup behavior includes:

- Default storage limit `500 MB` (per type: recordings, surveillance, proximity, trips).
- Minimum supported limit `100 MB`.
- Maximum limit is dynamic: the effective ceiling is the selected drive's physical free space when known. The static fallback ceiling is `2 TB` (`MAX_LIMIT_MB_INTERNAL` / `MAX_LIMIT_MB_SD_CARD`, both `2_000_000` MB). The proto exposes a separate `max_limit_mb_sd_card` field so the UI can clamp SD-card limits independently from internal.
- Periodic cleanup checks every `30 seconds`.
- Avoiding storage-directory switches while recording or surveillance is active.

### Format Storage API

`FormatStorageApiHandler` lets the UI wipe and re-mount a removable drive so a freshly inserted or full SD card / USB stick can be reused:

- `GET /api/storage/format` — `ListFormatVolumes`. Returns the removable public volumes visible to `StorageManager`: `{ success, volumes: [{ volumeId, uuid, mounted, mountPath }] }`. `mountPath` is `/storage/<uuid>`.
- `POST /api/storage/format` — `FormatVolume`. Body `{ "volumeId": "public:..." }`. Runs `sm unmount` → `sm format` → `sm mount` for the volume, polls up to ~10 seconds for the remount, then calls `StorageManager.discoverSdCard()` so the drive is re-picked-up.

Guardrails:

- Only removable `public:` volumes are accepted; internal emulated storage is rejected.
- The request is refused with HTTP 409 while a recording is active.

The proto contract is `StorageService.ListFormatVolumes` / `FormatVolume` in `proto/bladewatch/v1/storage.proto`.

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

GPU kernel cache:

```text
/data/local/tmp/tflite_gpu_cache
```

`YoloDetector` enables TFLite GPU kernel serialization (`GpuDelegateFactory.Options.setSerializationParams`) into this directory. The Adreno OpenCL backend otherwise recompiles all GPU kernels on every daemon start (~3–5s); serialization persists the compiled kernels so only the first-ever boot pays that cost. The cache key (`modelToken`) is a SHA-256 content hash of `yolo11n.tflite` plus the TFLite version, so a re-exported model or a runtime bump invalidates stale kernels automatically. The cache is OpenCL-only and silently no-ops on the OpenGL ES backend; a failure to create or write the directory falls back to a bare delegate without disabling GPU.

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

- All clip types (normal cam, event, proximity) — `StorageManager.onFileSaved()`
  at the single finalize convergence point. Every recording finalizes through
  `HardwareEventRecorderGpu` (the `tmp→final` rename), which calls `onFileSaved()`,
  which calls `MediaCatalogManager.indexRecording()`. The clip type is derived
  from the filename prefix (`cam_`, `event_`, `proximity_`).
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

- Camera-to-recording path: [PanoramicCameraGpu.java:39](../app/src/main/java/com/loabletech/bladewatch/camera/PanoramicCameraGpu.java#L39), [GpuMosaicRecorder.java:31](../app/src/main/java/com/loabletech/bladewatch/surveillance/GpuMosaicRecorder.java#L31), [HardwareEventRecorderGpu.java:56](../app/src/main/java/com/loabletech/bladewatch/surveillance/HardwareEventRecorderGpu.java#L56), [StorageManager.java:2234](../app/src/main/java/com/loabletech/bladewatch/storage/StorageManager.java#L2234).
- Live-stream path: [GpuSurveillancePipeline.java:30](../app/src/main/java/com/loabletech/bladewatch/surveillance/GpuSurveillancePipeline.java#L30), [WebSocketStreamServer.java:19](../app/src/main/java/com/loabletech/bladewatch/streaming/WebSocketStreamServer.java#L19), [HttpServer.java:538](../app/src/main/java/com/loabletech/bladewatch/server/HttpServer.java#L538).
- Surveillance-event path: [GpuDownscaler.java:51](../app/src/main/java/com/loabletech/bladewatch/surveillance/GpuDownscaler.java#L51), [SurveillanceEngineGpu.java:635](../app/src/main/java/com/loabletech/bladewatch/surveillance/SurveillanceEngineGpu.java#L635), [SurveillanceEngineGpu.java:3095](../app/src/main/java/com/loabletech/bladewatch/surveillance/SurveillanceEngineGpu.java#L3095).
- Web UI to daemon: [HttpServer.java:50](../app/src/main/java/com/loabletech/bladewatch/server/HttpServer.java#L50), [AuthMiddleware.java:135](../app/src/main/java/com/loabletech/bladewatch/server/AuthMiddleware.java#L135), [WebViewFragment.kt:228](../app/src/main/java/com/loabletech/bladewatch/ui/fragment/WebViewFragment.kt#L228).
- App TCP client to daemon: [CameraDaemonClient.java:24](../app/src/main/java/com/loabletech/bladewatch/client/CameraDaemonClient.java#L24), [TcpCommandServer.java:22](../app/src/main/java/com/loabletech/bladewatch/server/TcpCommandServer.java#L22), [CameraDaemon.java:53](../app/src/main/java/com/loabletech/bladewatch/daemon/CameraDaemon.java#L53).
- Location IPC: [LocationSidecarService.java:32](../app/src/main/java/com/loabletech/bladewatch/services/LocationSidecarService.java#L32), [SurveillanceIpcServer.java:23](../app/src/main/java/com/loabletech/bladewatch/server/SurveillanceIpcServer.java#L23), [CameraDaemon.java:383](../app/src/main/java/com/loabletech/bladewatch/daemon/CameraDaemon.java#L383).
- BYD local data flow: [BydDataCollector.java:20](../app/src/main/java/com/loabletech/bladewatch/byd/BydDataCollector.java#L20).
- Unified config, secrets, and auth identity: [UnifiedConfigManager.kt:30](../app/src/main/java/com/loabletech/bladewatch/config/UnifiedConfigManager.kt#L30), [SecretConfigStore.kt:22](../app/src/main/java/com/loabletech/bladewatch/config/SecretConfigStore.kt#L22), [AuthManager.java:50](../app/src/main/java/com/loabletech/bladewatch/auth/AuthManager.java#L50), [AuthManager.java:349](../app/src/main/java/com/loabletech/bladewatch/auth/AuthManager.java#L349).
- Media and SD-card storage: [StorageManager.java:100](../app/src/main/java/com/loabletech/bladewatch/storage/StorageManager.java#L100), [StorageManager.java:113](../app/src/main/java/com/loabletech/bladewatch/storage/StorageManager.java#L113), [StorageManager.java:918](../app/src/main/java/com/loabletech/bladewatch/storage/StorageManager.java#L918), [StorageManager.java:967](../app/src/main/java/com/loabletech/bladewatch/storage/StorageManager.java#L967), [StorageManager.java:2378](../app/src/main/java/com/loabletech/bladewatch/storage/StorageManager.java#L2378).
- Format storage API: [FormatStorageApiHandler.java:27](../app/src/main/java/com/loabletech/bladewatch/server/FormatStorageApiHandler.java#L27), [storage.proto:20](../proto/bladewatch/v1/storage.proto#L20).
- Media catalog and sync: [MediaCatalogManager.java:26](../app/src/main/java/com/loabletech/bladewatch/media/MediaCatalogManager.java#L26), [MediaCatalogManager.java:81](../app/src/main/java/com/loabletech/bladewatch/media/MediaCatalogManager.java#L81), [MediaCatalogManager.java:130](../app/src/main/java/com/loabletech/bladewatch/media/MediaCatalogManager.java#L130), [RecordingsApiHandler.java:185](../app/src/main/java/com/loabletech/bladewatch/server/RecordingsApiHandler.java#L185).
- Trip database and sync: [TripDatabase.java:19](../app/src/main/java/com/loabletech/bladewatch/trips/TripDatabase.java#L19), [TripDatabase.java:30](../app/src/main/java/com/loabletech/bladewatch/trips/TripDatabase.java#L30).
- Runtime assets and tunnel files: [build.gradle.kts:226](../app/build.gradle.kts#L226), [HttpServer.java:50](../app/src/main/java/com/loabletech/bladewatch/server/HttpServer.java#L50), [ZrokLauncher.kt:27](../app/src/main/java/com/loabletech/bladewatch/launcher/ZrokLauncher.kt#L27).
- Trips and notifications: [TripDetector.java:27](../app/src/main/java/com/loabletech/bladewatch/trips/TripDetector.java#L27), [TripAnalyticsManager.java:23](../app/src/main/java/com/loabletech/bladewatch/trips/TripAnalyticsManager.java#L23), [TripApiHandler.java:35](../app/src/main/java/com/loabletech/bladewatch/trips/TripApiHandler.java#L35), [NotificationApiHandler.java:31](../app/src/main/java/com/loabletech/bladewatch/server/NotificationApiHandler.java#L31).
