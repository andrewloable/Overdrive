# HTTP API Reference

The embedded HTTP server is implemented by `HttpServer` and route-specific handlers under `com.loabletech.bladewatch.server`. This file lists the route families and endpoints discovered in the codebase. Request and response schemas should be read from the corresponding handler classes before changing clients.

Base URL by default:

```text
http://127.0.0.1:8080
```

## Auth

Public or bootstrap endpoints:

- `GET /auth/status`.
- `POST /auth/token`.
- `POST /auth/logout`.
- `GET /login`.
- `GET /login.html`.

Most other routes require a JWT Bearer token or `byd_session` cookie.

## Static Web Routes

Primary page routes include:

- `GET /`.
- `GET /recording`.
- `GET /surveillance`.
- `GET /events`.
- `GET /performance`.
- `GET /trips`.
- `GET /vehicle-control`.
- `GET /notifications`.
- `GET /about`.

Static asset areas:

- `GET /shared/*`.
- `GET /local/*`.
- `GET /i18n/*`.
- `GET /manifest.json`.
- `GET /sw.js`.
- `GET /credits.json`.

i18n:

- `GET /api/i18n/lang`.

## Recording and Events

Handled by `RecordingsApiHandler`:

- `/api/recordings`.
- `/video/*`.
- `/thumb/*`.
- `/api/events/*`.
- `POST /api/recordings/sync` — reconcile the media catalog DB against the filesystem.
  Empty body. Returns `{success:true, added, updated, removed, total}` or
  `{success:false, error:"sync_in_progress"}` if a reconcile is already running.
- `GET /api/recording/mode` — returns `{status:"ok", mode}`.
- `POST /api/recording/mode` — body `{mode}` (e.g. `CONTINUOUS`/`EVENTS`/`OFF`);
  returns `{status:"ok", mode}`. Connect: `SettingsService.SetRecordingMode`
  (`{mode}` → `{success, mode}`), which calls `CameraDaemon.setRecordingMode`
  directly rather than shelling this inline route.

The server also supports snapshot-style routes such as:

- `/snapshot/{id}`.

## Surveillance

Handled by `SurveillanceApiHandler` and `SafeLocationApiHandler`:

- `GET /api/surveillance/config`.
- `POST /api/surveillance/config`.
- `GET /api/surveillance/status`.
- `POST /api/surveillance/enable`.
- `POST /api/surveillance/disable`.
- `GET /api/surveillance/heatmap`.
- `GET /api/surveillance/snapshot/{quadrant}`.
- `GET /api/surveillance/filterlog`.
- `/api/surveillance/safe-locations`.
- `POST /api/surveillance/sync` — alias for `POST /api/recordings/sync`; reconciles
  the shared media catalog for all clip types. Same response shape.

## Streaming

Handled by `StreamingApiHandler` and WebSocket upgrade paths:

- `/api/stream/*`.
- WebSocket upgrade routes used by the live stream client.

The implementation supports token query promotion for WebSocket auth.

## GPS

Handled by `GpsApiHandler`:

- `/api/gps/*`.

GPS also enters the daemon through `SurveillanceIpcServer` command `UPDATE_GPS`.

## Quality and Settings

Handled by `QualitySettingsApiHandler`:

- `GET /api/settings/quality`.
- `POST /api/settings/quality`.
- `GET /api/settings/storage`.
- `POST /api/settings/storage`.
- `GET /api/settings/unified`.
- `POST /api/settings/unified`.
- `GET /api/settings/telemetry-overlay`.
- `POST /api/settings/telemetry-overlay`.
- `GET /api/settings/appearance`.
- `POST /api/settings/appearance`.

## External Storage

Handled by `ExternalStorageApiHandler`:

- `GET /api/storage/external`.
- `POST /api/storage/external/config`.
- `POST /api/storage/external/cleanup`.
- `GET /api/storage/external/preview`.
- `POST /api/storage/external/refresh`.

## Trips

Handled by `TripApiHandler`:

- `GET /api/trips`.
- `GET /api/trips/{id}`.
- `DELETE /api/trips/{id}`.
- `GET /api/trips/{id}/telemetry`.
- `GET /api/trips/{id}/similar`.
- `GET /api/trips/{id}/gps`.
- `GET /api/trips/summary`.
- `GET /api/trips/dna`.
- `GET /api/trips/range`.
- `GET /api/trips/config`.
- `POST /api/trips/config`.
- `GET /api/trips/storage`.
- `POST /api/trips/storage`.
- `POST /api/trips/sync` — reconcile the trips DB against telemetry files on disk.
  Empty body. Returns `{success:true, added, removed, total}` or
  `{success:false, error:...}`.

## Audio Test

Handled by `AudioTestApiHandler`:

- `/api/audio/*`.

## Vehicle Control

Handled by `VehicleControlApiHandler`:

- `GET /api/vehicle/state`.
- `GET /api/vehicle/ac-diagnostics`.
- `GET /api/vehicle/seat-diagnostics`.
- `POST /api/vehicle/trunk`.
- `POST /api/vehicle/window`.
- `POST /api/vehicle/climate`.
- `POST /api/vehicle/seat`.
- `POST /api/vehicle/lights`.
- `POST /api/vehicle/adas`.
- `GET /api/vehicle/charge-cap`.
- `POST /api/vehicle/charge-cap`.

The following endpoints existed previously but are no longer supported. They return `NOT_SUPPORTED` or have been removed:

- `GET /api/vehicle/cloud-status` — removed (required BYD cloud).
- `GET /api/vehicle/cloud-lock` — removed (required BYD cloud MQTT lock source).
- `POST /api/vehicle/lock` — not supported (cloud-only action).
- `POST /api/vehicle/unlock` — not supported (cloud-only action).
- `POST /api/vehicle/flash` — not supported (cloud-only action).
- `POST /api/vehicle/find-car` — not supported (cloud-only action).
- `POST /api/vehicle/battery-heat` — not supported (cloud-only action).
- `GET /api/vehicle/charging-schedule` — not supported (cloud-only action).
- `POST /api/vehicle/charging-schedule` — not supported (cloud-only action).

All supported vehicle actions use local SDK paths only.

## Performance

Handled by `PerformanceApiHandler`:

- `GET /api/performance`.
- `GET /api/performance/history`.
- `GET /api/performance/full`.
- `POST /api/performance/connect`.
- `POST /api/performance/disconnect`.
- `POST /api/performance/heartbeat`.
- `POST /api/performance/start`.
- `POST /api/performance/stop`.
- `GET /api/performance/status`.
- `GET /api/performance/discover`.
- `GET /api/performance/parking-delta`.
- `GET /api/performance/last-charge`.
- SOC-related endpoints under `/api/performance/soc`.
- Battery-related endpoints under `/api/performance/battery`.
- `GET /api/performance/soh` — returns "not available"; SoH estimation has been removed.
- `POST /api/performance/soh/reset` — returns "not available".
- `GET /api/performance/soh/nominal` — returns BYD-local nominal capacity only.
- `POST /api/performance/soh/nominal`.
- `POST /api/performance/reset`.

## Models

Handled by `ModelsApiHandler`:

- `GET /api/models/list`.
- `POST /api/models/download?id=ID`.
- `GET /api/models/status?id=ID`.
- `GET /api/models/selected`.
- `POST /api/models/selected`.
- `GET /api/models/manifest`.
- `POST /api/models/manifest/refresh`.

## Updates

Handled by `UpdateApiHandler`:

- `GET /api/update/check`.
- `GET /api/update/preview`.
- `POST /api/update/install?confirm=true`.
- `GET /api/update/progress`.

## Notifications and Push

Handled by `NotificationApiHandler`:

- `GET /api/notifications/categories`.
- `POST /api/push/subscribe`.
- `POST /api/push/unsubscribe`.
- `GET /api/push/subscriptions`.
- `POST /api/push/preferences`.
- `POST /api/push/test`.

## Status and Control

General status and control routes include:

- `GET /status`.
- `POST /api/start/{id}`.

Additional command behavior may be implemented by the TCP command server rather than HTTP.

### System status field parity (by design)

`GET /status` (Connect: `SystemService.GetStatus`) intentionally drops several
REST-emitted fields because no Connect/Angular consumer reads them, so they are
not modelled in the proto (`proto/bladewatch/v1/system.proto`):

- `BatteryMonitor.getBatteryInfo()` emits `voltage`, `soc`, `lastUpdate`, but
  `BatteryInfo` carries only `level` — the dashboard reads `battery.level` (and
  the vehicle state-of-charge separately via the `soc`/`ChargingInfo` object,
  not the Android battery `soc`).
- `NetworkMonitor.getNetworkInfo()` emits `signal` (signal percent), but
  `NetworkInfo` has no signal field — no client surfaces it.
- The top-level `status: "ok"` string is cosmetic and intentionally dropped.

If a future client needs these, add the corresponding proto fields
(`battery: voltage/soc/last_update`, `network: signal_percent`) and regenerate
stubs (`cd proto && buf generate`). Tracked by BladeWatch-852m.

## Client Guidance

- Always authenticate before calling protected APIs.
- Use the local base URL from the Android app or tunnel URL from the Zrok launcher.
- Avoid assuming response schemas from this list alone.
- Do not send mutating calls from WebView through a proxy path; use the injected bridge pattern already implemented by the app.
- Prefer WebSocket streaming routes for live video rather than polling snapshots.

## Source References

- HTTP server route dispatch and static/websocket handling: [HttpServer.java:49](../app/src/main/java/com/loabletech/bladewatch/server/HttpServer.java#L49), [HttpServer.java:538](../app/src/main/java/com/loabletech/bladewatch/server/HttpServer.java#L538), [HttpServer.java:650](../app/src/main/java/com/loabletech/bladewatch/server/HttpServer.java#L650).
- Auth endpoints and middleware: [AuthApiHandler.java:26](../app/src/main/java/com/loabletech/bladewatch/server/AuthApiHandler.java#L26), [AuthApiHandler.java:155](../app/src/main/java/com/loabletech/bladewatch/server/AuthApiHandler.java#L155), [AuthMiddleware.java:133](../app/src/main/java/com/loabletech/bladewatch/server/AuthMiddleware.java#L133), [AuthManager.java:349](../app/src/main/java/com/loabletech/bladewatch/auth/AuthManager.java#L349), [AuthManager.java:463](../app/src/main/java/com/loabletech/bladewatch/auth/AuthManager.java#L463).
- Recording and event APIs: [RecordingsApiHandler.java:41](../app/src/main/java/com/loabletech/bladewatch/server/RecordingsApiHandler.java#L41), [RecordingsApiHandler.java:484](../app/src/main/java/com/loabletech/bladewatch/server/RecordingsApiHandler.java#L484), [CameraDaemon.java:677](../app/src/main/java/com/loabletech/bladewatch/daemon/CameraDaemon.java#L677).
- Surveillance APIs and IPC crossover: [SurveillanceApiHandler.java:22](../app/src/main/java/com/loabletech/bladewatch/server/SurveillanceApiHandler.java#L22), [SurveillanceApiHandler.java:855](../app/src/main/java/com/loabletech/bladewatch/server/SurveillanceApiHandler.java#L855), [SurveillanceIpcServer.java:22](../app/src/main/java/com/loabletech/bladewatch/server/SurveillanceIpcServer.java#L22), [CameraDaemon.java:1439](../app/src/main/java/com/loabletech/bladewatch/daemon/CameraDaemon.java#L1439).
- Streaming APIs: [WebSocketStreamServer.java:19](../app/src/main/java/com/loabletech/bladewatch/streaming/WebSocketStreamServer.java#L19), [GpuSurveillancePipeline.java:30](../app/src/main/java/com/loabletech/bladewatch/surveillance/GpuSurveillancePipeline.java#L30), [HttpServer.java:538](../app/src/main/java/com/loabletech/bladewatch/server/HttpServer.java#L538).
- GPS, quality/settings, storage: [GpsApiHandler.java:18](../app/src/main/java/com/loabletech/bladewatch/server/GpsApiHandler.java#L18), [ExternalStorageApiHandler.java:27](../app/src/main/java/com/loabletech/bladewatch/server/ExternalStorageApiHandler.java#L27), [UnifiedConfigManager.kt:30](../app/src/main/java/com/loabletech/bladewatch/config/UnifiedConfigManager.kt#L30).
- Trips, performance, models, updates, notifications: [TripApiHandler.java:35](../app/src/main/java/com/loabletech/bladewatch/trips/TripApiHandler.java#L35), [PerformanceApiHandler.java:30](../app/src/main/java/com/loabletech/bladewatch/server/PerformanceApiHandler.java#L30), [ModelsApiHandler.java:42](../app/src/main/java/com/loabletech/bladewatch/server/ModelsApiHandler.java#L42), [UpdateApiHandler.java:43](../app/src/main/java/com/loabletech/bladewatch/server/UpdateApiHandler.java#L43), [NotificationApiHandler.java:30](../app/src/main/java/com/loabletech/bladewatch/server/NotificationApiHandler.java#L30).
- Vehicle control APIs: [VehicleControlApiHandler.java:43](../app/src/main/java/com/loabletech/bladewatch/server/VehicleControlApiHandler.java#L43), [VehicleControlApiHandler.java:488](../app/src/main/java/com/loabletech/bladewatch/server/VehicleControlApiHandler.java#L488), [VehicleControlApiHandler.java:627](../app/src/main/java/com/loabletech/bladewatch/server/VehicleControlApiHandler.java#L627), [VehicleControlApiHandler.java:796](../app/src/main/java/com/loabletech/bladewatch/server/VehicleControlApiHandler.java#L796).
