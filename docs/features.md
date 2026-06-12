# Features

This file catalogs the main capabilities implemented in the repository.

## Recording

- Panoramic camera recording from BYD camera feeds.
- GPU mosaic recording pipeline.
- Multiple recording modes through unified config.
- Recording quality settings.
- Codec settings.
- Bitrate control.
- Segment-based recording.
- Recording library.
- Thumbnail and video serving through the embedded HTTP server.
- Storage selection and cleanup.
- External storage detection and configuration.

Default camera-related values found in code:

- Panoramic recording resolution: `5120x960`.
- View resolution: `1280x960`.
- Default frame rate: `25 fps`.
- Default recording bitrate: `4 Mbps`.
- Segment length: `2 minutes`.

## Surveillance and Sentry Mode

- Manual surveillance enable and disable.
- ACC-aware sentry behavior.
- GPU-based motion detection.
- Per-quadrant motion processing.
- AI-assisted object detection using TensorFlow Lite.
- YOLO model support from `assets/models/yolo11n.tflite`.
- Flash-immunity settings.
- Region-of-interest mask support.
- Pre-event and post-event recording windows.
- Loitering and sustained-motion logic.
- Surveillance heatmap and snapshots.
- Safe locations.
- Filter logging.

Default surveillance config includes:

- Surveillance disabled by default.
- Pre-recording window: `5 seconds`.
- Post-recording window: `10 seconds`.
- Motion block size: `32`.
- Required motion blocks: `3`.
- Sensitivity: `0.04`.
- AI confidence: `0.25`.
- Person and car detection enabled by default.
- Bike detection disabled by default.

## Live Streaming

- Local H.264 live stream over WebSocket.
- Single-port streaming on the embedded HTTP server.
- SPS/PPS caching.
- IDR frame request support.
- Fragmentation support for large frames.
- Separate streaming encoder path from recording.
- Streaming quality configuration.

## Embedded Web UI and PWA

The web UI is bundled under `app/src/main/assets/web/`, extracted by the daemon to `/data/local/tmp/web`, and served locally.

Pages and areas include:

- Dashboard.
- Recording.
- Surveillance.
- Events.
- Performance.
- Trips.
- Notifications.
- Vehicle control.
- About and credits.
- PWA/service worker assets.
- Shared JavaScript and CSS.
- Vendor assets such as Three.js, Leaflet, and Draco.

The Android app embeds these pages through a local WebView pointed at `http://127.0.0.1:8080/`.

## Android Native UI

The Android UI provides (Material 3 — see [UI/UX Design Language](ui-ux-design-language.md)):

- Material navigation rail shell.
- Dashboard screens.
- Recording and recording-library screens.
- Daemon status and control.
- Integrations.
- Diagnostics.
- Settings.
- Video playback.
- WebView-hosted web pages.
- Native vehicle view (7-tab layout with tyre pressure hero overlay).

## BYD Local Telemetry

The app reads local BYD framework data through reflection and listener registration.

Telemetry areas include:

- Bodywork.
- Speed.
- Engine.
- Statistic data.
- Energy.
- Tyres.
- Charging.
- Door locks.
- Instrument cluster values.
- OTA state.
- Sensors.
- Gearbox.
- Safety belts.
- Air conditioning.
- Lights.
- ADAS.
- Radar.
- Power.
- Settings.
- Multimedia.

The collector isolates failures by device type so one unavailable BYD API does not disable all telemetry.

## Local Vehicle Control

The Android app includes a native vehicle view implemented in `VehicleController` / `VehiclePanels`. It is a 7-tab layout built entirely from programmatic Android views (no XML inflation):

- **Trunk** — open/close, status indicator.
- **Climate** — AC on/off, max cooling toggle, temperature and fan speed.
- **Seats** — heat and ventilation level for driver/passenger; memory-recall positions where supported.
- **Windows** — per-window open/close/vent controls (LF, RF, LR, RR) and sunroof/sunshade where present.
- **Lights** — DRL toggle, speed-limit warning (ADAS).
- **ADAS** — speed limit warning toggle (shared entry point with Lights tab).
- **Charging** — charge cap percent and enable/disable.

The hero region above the tabs shows a tyre pressure overlay: a car silhouette with per-corner cards (FL/FR/RL/RR) colour-coded by pressure tier (NORMAL/CAUTION/WARN/ALERT/MUTED). Alert cards distinguish fast vs slow air-leak states.

The 3D car model viewer is not yet implemented in the native view (pending).

The native vehicle view supports 17 languages. String resources are generated from the web i18n JSON bundles by `tools/i18n/generate_vehicle_strings.py`. 25 keys are translated; the remaining 40 English-only strings fall back automatically.

All write actions route through `VehicleCommandRouter`. The following actions are not available in the native view because there is no local SDK path. They remain visible in the web-based vehicle-control page when BYD cloud is configured:

- Lock and unlock.
- Flash lights.
- Find car.
- Battery heat.
- Charging schedule.
- Smart charging.

Local SDK controls available in both native view and web UI:

- Climate.
- Windows.
- Seats.
- Trunk.
- Lights.
- ADAS.
- Charge cap.
- Diagnostics and state reads.

## Trips and Analytics

Trip functionality includes:

- Trip list and details.
- Telemetry history per trip.
- Similar trip lookup.
- GPS traces.
- Summary statistics.
- Driving DNA.
- Range analytics.
- Trip config.
- Trip storage management.

## Performance and Telemetry

Performance features include:

- Real-time performance status.
- Historical performance views.
- Connection and heartbeat APIs.
- Battery and SOC data.
- Parking delta.
- Charge session and last-charge tracking.
- Telemetry overlay config.

Battery state-of-health estimation is not available. The nominal battery capacity value from BYD local telemetry is accessible through the SOC nominal endpoint, but no SoH estimator runs.

## Notifications and Push

Notification features include:

- Notification category APIs.
- Web Push subscription management (PWA push).
- Push preference updates.
- Test push endpoint.
- Android notification channels and foreground service notifications.

Surveillance and proximity events deliver notifications through Web Push. There is no Telegram notification path.

## Remote Access

Remote access options include:

- Local loopback web server.
- Opt-in LAN HTTP.
- Zrok public or reserved share.

LAN HTTP is disabled by default. Zrok is designed to front the authenticated local web server directly with no intermediate proxy.

## Updates

The app includes update APIs for:

- Checking update metadata.
- Previewing available updates.
- Installing confirmed updates.
- Reporting install progress.
- Handling post-update daemon reset behavior.

## Diagnostics and Logs

Diagnostics exist across native Android UI, daemon state, HTTP APIs, and log files. The app includes daemon health checks, process revival, overlay status, and logging utilities.

## Source References

- Recording and camera control: [CameraDaemon.java:677](../app/src/main/java/com/loabletech/bladewatch/daemon/CameraDaemon.java#L677), [GpuSurveillancePipeline.java:1194](../app/src/main/java/com/loabletech/bladewatch/surveillance/GpuSurveillancePipeline.java#L1194), [GpuMosaicRecorder.java:614](../app/src/main/java/com/loabletech/bladewatch/surveillance/GpuMosaicRecorder.java#L614), [RecordingsApiHandler.java:41](../app/src/main/java/com/loabletech/bladewatch/server/RecordingsApiHandler.java#L41).
- ACC-on recording modes: [RecordingModeManager.java:31](../app/src/main/java/com/loabletech/bladewatch/recording/RecordingModeManager.java#L31), [RecordingModeManager.java:533](../app/src/main/java/com/loabletech/bladewatch/recording/RecordingModeManager.java#L533), [ProximityRecordingHandler.java:49](../app/src/main/java/com/loabletech/bladewatch/proximity/ProximityRecordingHandler.java#L49).
- Surveillance and AI: [SurveillanceEngineGpu.java:22](../app/src/main/java/com/loabletech/bladewatch/surveillance/SurveillanceEngineGpu.java#L22), [MotionPipelineV2.java:14](../app/src/main/java/com/loabletech/bladewatch/surveillance/MotionPipelineV2.java#L14), [YoloDetector.kt:43](../app/src/main/java/com/loabletech/bladewatch/ai/YoloDetector.kt#L43), [SurveillanceConfig.java:9](../app/src/main/java/com/loabletech/bladewatch/surveillance/SurveillanceConfig.java#L9).
- Live streaming: [GpuSurveillancePipeline.java:30](../app/src/main/java/com/loabletech/bladewatch/surveillance/GpuSurveillancePipeline.java#L30), [WebSocketStreamServer.java:19](../app/src/main/java/com/loabletech/bladewatch/streaming/WebSocketStreamServer.java#L19), [HttpServer.java:538](../app/src/main/java/com/loabletech/bladewatch/server/HttpServer.java#L538).
- Embedded web UI and Android WebView: [HttpServer.java:49](../app/src/main/java/com/loabletech/bladewatch/server/HttpServer.java#L49), [WebViewFragment.kt:28](../app/src/main/java/com/loabletech/bladewatch/ui/fragment/WebViewFragment.kt#L28), [app/src/main/assets/web/shared/core.js:537](../app/src/main/assets/web/shared/core.js#L537).
- BYD telemetry and vehicle control: [BydDataCollector.java:20](../app/src/main/java/com/loabletech/bladewatch/byd/BydDataCollector.java#L20), [VehicleControlApiHandler.java:43](../app/src/main/java/com/loabletech/bladewatch/server/VehicleControlApiHandler.java#L43), [VehicleCommandRouter.java:37](../app/src/main/java/com/loabletech/bladewatch/byd/routing/VehicleCommandRouter.java#L37).
- Trips, notifications, updates, and diagnostics: [TripAnalyticsManager.java:23](../app/src/main/java/com/loabletech/bladewatch/trips/TripAnalyticsManager.java#L23), [TripApiHandler.java:35](../app/src/main/java/com/loabletech/bladewatch/trips/TripApiHandler.java#L35), [NotificationApiHandler.java:30](../app/src/main/java/com/loabletech/bladewatch/server/NotificationApiHandler.java#L30), [UpdateApiHandler.java:43](../app/src/main/java/com/loabletech/bladewatch/server/UpdateApiHandler.java#L43), [PerformanceApiHandler.java:30](../app/src/main/java/com/loabletech/bladewatch/server/PerformanceApiHandler.java#L30).
- Remote access: [ZrokLauncher.kt:27](../app/src/main/java/com/loabletech/bladewatch/launcher/ZrokLauncher.kt#L27).
