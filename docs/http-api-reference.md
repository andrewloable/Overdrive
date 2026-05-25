# HTTP API Reference

The embedded HTTP server is implemented by `HttpServer` and route-specific handlers under `com.overdrive.app.server`. This file lists the route families and endpoints discovered in the codebase. Request and response schemas should be read from the corresponding handler classes before changing clients.

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
- `GET /abrp`.
- `GET /mqtt`.
- `GET /trips`.
- `GET /vehicle-control`.
- `GET /telegram`.
- `GET /byd-cloud`.
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
- `GET /api/settings/telegram-status`.

## External Storage

Handled by `ExternalStorageApiHandler`:

- `GET /api/storage/external`.
- `POST /api/storage/external/config`.
- `POST /api/storage/external/cleanup`.
- `GET /api/storage/external/preview`.
- `POST /api/storage/external/refresh`.

## ABRP

Handled by `AbrpApiHandler`:

- `GET /api/abrp/config`.
- `POST /api/abrp/config`.
- `GET /api/abrp/status`.
- `DELETE /api/abrp/token`.

## MQTT

Handled by `MqttApiHandler`:

- `GET /api/mqtt/connections`.
- `POST /api/mqtt/connections`.
- `PUT /api/mqtt/connections/{id}`.
- `DELETE /api/mqtt/connections/{id}`.
- `GET /api/mqtt/status`.
- `GET /api/mqtt/telemetry`.

## Telegram

Handled by `TelegramApiHandler`:

- `/api/telegram/*`.

Telegram also interacts with daemon IPC for commands and notifications.

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

## Audio Test

Handled by `AudioTestApiHandler`:

- `/api/audio/*`.

## Vehicle Control

Handled by `VehicleControlApiHandler`:

- `GET /api/vehicle/state`.
- `GET /api/vehicle/ac-diagnostics`.
- `GET /api/vehicle/seat-diagnostics`.
- `GET /api/vehicle/cloud-status`.
- `GET /api/vehicle/cloud-lock`.
- `POST /api/vehicle/lock`.
- `POST /api/vehicle/unlock`.
- `POST /api/vehicle/trunk`.
- `POST /api/vehicle/window`.
- `POST /api/vehicle/flash`.
- `POST /api/vehicle/find-car`.
- `POST /api/vehicle/climate`.
- `POST /api/vehicle/seat`.
- `POST /api/vehicle/lights`.
- `POST /api/vehicle/adas`.
- `POST /api/vehicle/battery-heat`.
- `GET /api/vehicle/charging-schedule`.
- `POST /api/vehicle/charging-schedule`.
- `GET /api/vehicle/charge-cap`.
- `POST /api/vehicle/charge-cap`.

Vehicle actions are implemented with cloud-first, cloud-only, or SDK-only strategies depending on action support.

## BYD Cloud

Handled by `BydCloudApiHandler`:

- `GET /api/bydcloud/status`.
- `POST /api/bydcloud/setup`.
- `POST /api/bydcloud/settings`.
- `POST /api/bydcloud/test`.
- `POST /api/bydcloud/clear`.

Setup validates region and country mapping, credentials, optional control PIN, Bangcle table loading, login, vehicle list retrieval, and PIN verification.

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
- `GET /api/performance/soh`.
- `POST /api/performance/soh/reset`.
- `GET /api/performance/soh/nominal`.
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

## Client Guidance

- Always authenticate before calling protected APIs.
- Use the local base URL from the Android app or tunnel URL from the tunnel launcher.
- Avoid assuming response schemas from this list alone.
- Do not send mutating calls from WebView through a proxy path; use the injected bridge pattern already implemented by the app.
- Prefer WebSocket streaming routes for live video rather than polling snapshots.

## Source References

- HTTP server route dispatch and static/websocket handling: [HttpServer.java:49](../app/src/main/java/com/overdrive/app/server/HttpServer.java#L49), [HttpServer.java:538](../app/src/main/java/com/overdrive/app/server/HttpServer.java#L538), [HttpServer.java:650](../app/src/main/java/com/overdrive/app/server/HttpServer.java#L650).
- Auth endpoints and middleware: [AuthApiHandler.java:26](../app/src/main/java/com/overdrive/app/server/AuthApiHandler.java#L26), [AuthApiHandler.java:155](../app/src/main/java/com/overdrive/app/server/AuthApiHandler.java#L155), [AuthMiddleware.java:133](../app/src/main/java/com/overdrive/app/server/AuthMiddleware.java#L133), [AuthManager.java:349](../app/src/main/java/com/overdrive/app/auth/AuthManager.java#L349), [AuthManager.java:463](../app/src/main/java/com/overdrive/app/auth/AuthManager.java#L463).
- Recording and event APIs: [RecordingsApiHandler.java:41](../app/src/main/java/com/overdrive/app/server/RecordingsApiHandler.java#L41), [RecordingsApiHandler.java:484](../app/src/main/java/com/overdrive/app/server/RecordingsApiHandler.java#L484), [CameraDaemon.java:677](../app/src/main/java/com/overdrive/app/daemon/CameraDaemon.java#L677).
- Surveillance APIs and IPC crossover: [SurveillanceApiHandler.java:22](../app/src/main/java/com/overdrive/app/server/SurveillanceApiHandler.java#L22), [SurveillanceApiHandler.java:855](../app/src/main/java/com/overdrive/app/server/SurveillanceApiHandler.java#L855), [SurveillanceIpcServer.java:22](../app/src/main/java/com/overdrive/app/server/SurveillanceIpcServer.java#L22), [CameraDaemon.java:1439](../app/src/main/java/com/overdrive/app/daemon/CameraDaemon.java#L1439).
- Streaming APIs: [WebSocketStreamServer.java:19](../app/src/main/java/com/overdrive/app/streaming/WebSocketStreamServer.java#L19), [GpuSurveillancePipeline.java:30](../app/src/main/java/com/overdrive/app/surveillance/GpuSurveillancePipeline.java#L30), [HttpServer.java:538](../app/src/main/java/com/overdrive/app/server/HttpServer.java#L538).
- GPS, quality/settings, storage, ABRP, MQTT, Telegram: [GpsApiHandler.java:18](../app/src/main/java/com/overdrive/app/server/GpsApiHandler.java#L18), [ExternalStorageApiHandler.java:27](../app/src/main/java/com/overdrive/app/server/ExternalStorageApiHandler.java#L27), [AbrpApiHandler.java:23](../app/src/main/java/com/overdrive/app/server/AbrpApiHandler.java#L23), [MqttApiHandler.java:25](../app/src/main/java/com/overdrive/app/server/MqttApiHandler.java#L25), [TelegramApiHandler.java:28](../app/src/main/java/com/overdrive/app/server/TelegramApiHandler.java#L28), [UnifiedConfigManager.kt:30](../app/src/main/java/com/overdrive/app/config/UnifiedConfigManager.kt#L30).
- Trips, performance, models, updates, notifications: [TripApiHandler.java:35](../app/src/main/java/com/overdrive/app/trips/TripApiHandler.java#L35), [PerformanceApiHandler.java:30](../app/src/main/java/com/overdrive/app/server/PerformanceApiHandler.java#L30), [ModelsApiHandler.java:42](../app/src/main/java/com/overdrive/app/server/ModelsApiHandler.java#L42), [UpdateApiHandler.java:43](../app/src/main/java/com/overdrive/app/server/UpdateApiHandler.java#L43), [NotificationApiHandler.java:30](../app/src/main/java/com/overdrive/app/server/NotificationApiHandler.java#L30).
- Vehicle control and BYD cloud APIs: [VehicleControlApiHandler.java:43](../app/src/main/java/com/overdrive/app/server/VehicleControlApiHandler.java#L43), [VehicleControlApiHandler.java:488](../app/src/main/java/com/overdrive/app/server/VehicleControlApiHandler.java#L488), [VehicleControlApiHandler.java:627](../app/src/main/java/com/overdrive/app/server/VehicleControlApiHandler.java#L627), [VehicleControlApiHandler.java:796](../app/src/main/java/com/overdrive/app/server/VehicleControlApiHandler.java#L796), [BydCloudApiHandler.java:26](../app/src/main/java/com/overdrive/app/server/BydCloudApiHandler.java#L26), [BydCloudApiHandler.java:307](../app/src/main/java/com/overdrive/app/server/BydCloudApiHandler.java#L307).
