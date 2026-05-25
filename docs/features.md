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
- Deterrent actions through BYD cloud commands.
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
- ABRP.
- MQTT.
- Trips.
- Telegram.
- BYD cloud.
- Notifications.
- Vehicle control.
- About and credits.
- PWA/service worker assets.
- Shared JavaScript and CSS.
- Vendor assets such as Three.js, Leaflet, and Draco.

The Android app embeds these pages through a local WebView pointed at `http://127.0.0.1:8080/`.

## Android Native UI

The Android UI provides:

- Material navigation rail shell.
- Dashboard screens.
- Recording and recording-library screens.
- Daemon status and control.
- Integrations.
- Diagnostics.
- Settings.
- Video playback.
- WebView-hosted web pages.

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

## BYD Cloud

Cloud support includes:

- Region and country mapping.
- BYD account login.
- Credential encryption.
- Bangcle table loading.
- Vehicle discovery.
- Control PIN verification.
- Remote real-time state request and polling.
- Remote control commands.
- MQTT v5 subscription to vehicle updates.
- Payload decryption and cloud snapshot merging.

Remote vehicle controls include lock, unlock, trunk/window actions, flash/find-car actions, climate, seats, lights, ADAS, battery heating, charging schedule, and charge cap APIs.

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
- State-of-health tracking.
- Parking delta.
- Charge session and last-charge tracking.
- Telemetry overlay config.

## Notifications and Push

Notification features include:

- Notification category APIs.
- Push subscription management.
- Push preference updates.
- Test push endpoint.
- Android notification channels and foreground service notifications.

## Telegram

Telegram integration is an optional daemon and web/API feature area. It can receive daemon state and tunnel URL notifications and can drive selected surveillance and app commands through IPC.

## MQTT and ABRP

MQTT support includes:

- Multiple connection definitions.
- Connection create, update, delete, and status APIs.
- Telemetry publishing surfaces.
- Proxy-aware connections through sing-box where needed.

ABRP support includes:

- Config APIs.
- Status APIs.
- Token deletion.
- Proxy-aware outbound connections.

## Remote Access

Remote access options include:

- Local loopback web server.
- Opt-in LAN HTTP.
- Cloudflared quick tunnel.
- Zrok public or reserved share.
- Tailscale userspace networking.

LAN HTTP is disabled by default. Tunnels are designed to front the authenticated local web server.

## Proxy and Network Bypass

sing-box support provides a local mixed proxy for outbound traffic that may otherwise be blocked on the vehicle head unit. The code supports using this proxy for Cloudflared, Zrok, Tailscale setup paths, MQTT, ABRP, and BYD cloud traffic.

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

- Recording and camera control: [CameraDaemon.java:677](../app/src/main/java/com/overdrive/app/daemon/CameraDaemon.java#L677), [GpuSurveillancePipeline.java:1194](../app/src/main/java/com/overdrive/app/surveillance/GpuSurveillancePipeline.java#L1194), [GpuMosaicRecorder.java:614](../app/src/main/java/com/overdrive/app/surveillance/GpuMosaicRecorder.java#L614), [RecordingsApiHandler.java:41](../app/src/main/java/com/overdrive/app/server/RecordingsApiHandler.java#L41).
- ACC-on recording modes: [RecordingModeManager.java:31](../app/src/main/java/com/overdrive/app/recording/RecordingModeManager.java#L31), [RecordingModeManager.java:533](../app/src/main/java/com/overdrive/app/recording/RecordingModeManager.java#L533), [ProximityRecordingHandler.java:49](../app/src/main/java/com/overdrive/app/proximity/ProximityRecordingHandler.java#L49).
- Surveillance and AI: [SurveillanceEngineGpu.java:22](../app/src/main/java/com/overdrive/app/surveillance/SurveillanceEngineGpu.java#L22), [MotionPipelineV2.java:14](../app/src/main/java/com/overdrive/app/surveillance/MotionPipelineV2.java#L14), [YoloDetector.kt:43](../app/src/main/java/com/overdrive/app/ai/YoloDetector.kt#L43), [SurveillanceConfig.java:9](../app/src/main/java/com/overdrive/app/surveillance/SurveillanceConfig.java#L9).
- Live streaming: [GpuSurveillancePipeline.java:30](../app/src/main/java/com/overdrive/app/surveillance/GpuSurveillancePipeline.java#L30), [WebSocketStreamServer.java:19](../app/src/main/java/com/overdrive/app/streaming/WebSocketStreamServer.java#L19), [HttpServer.java:538](../app/src/main/java/com/overdrive/app/server/HttpServer.java#L538).
- Embedded web UI and Android WebView: [HttpServer.java:49](../app/src/main/java/com/overdrive/app/server/HttpServer.java#L49), [WebViewFragment.kt:28](../app/src/main/java/com/overdrive/app/ui/fragment/WebViewFragment.kt#L28), [app/src/main/assets/web/shared/core.js:537](../app/src/main/assets/web/shared/core.js#L537).
- BYD telemetry and vehicle control: [BydDataCollector.java:20](../app/src/main/java/com/overdrive/app/byd/BydDataCollector.java#L20), [VehicleControlApiHandler.java:43](../app/src/main/java/com/overdrive/app/server/VehicleControlApiHandler.java#L43), [VehicleCommandRouter.java:37](../app/src/main/java/com/overdrive/app/byd/routing/VehicleCommandRouter.java#L37).
- BYD cloud: [BydCloudApiHandler.java:26](../app/src/main/java/com/overdrive/app/server/BydCloudApiHandler.java#L26), [BydCloudClient.java:22](../app/src/main/java/com/overdrive/app/byd/cloud/BydCloudClient.java#L22), [BydCloudDataProvider.java:14](../app/src/main/java/com/overdrive/app/byd/cloud/BydCloudDataProvider.java#L14), [BydCloudMqttSubscriber.java:31](../app/src/main/java/com/overdrive/app/byd/cloud/BydCloudMqttSubscriber.java#L31).
- Trips, notifications, MQTT, ABRP, updates, and diagnostics: [TripAnalyticsManager.java:23](../app/src/main/java/com/overdrive/app/trips/TripAnalyticsManager.java#L23), [TripApiHandler.java:35](../app/src/main/java/com/overdrive/app/trips/TripApiHandler.java#L35), [NotificationApiHandler.java:30](../app/src/main/java/com/overdrive/app/server/NotificationApiHandler.java#L30), [MqttApiHandler.java:25](../app/src/main/java/com/overdrive/app/server/MqttApiHandler.java#L25), [AbrpApiHandler.java:23](../app/src/main/java/com/overdrive/app/server/AbrpApiHandler.java#L23), [UpdateApiHandler.java:43](../app/src/main/java/com/overdrive/app/server/UpdateApiHandler.java#L43), [PerformanceApiHandler.java:30](../app/src/main/java/com/overdrive/app/server/PerformanceApiHandler.java#L30).
- Remote access and proxy features: [TunnelLauncher.kt:12](../app/src/main/java/com/overdrive/app/launcher/TunnelLauncher.kt#L12), [ZrokLauncher.kt:27](../app/src/main/java/com/overdrive/app/launcher/ZrokLauncher.kt#L27), [TailscaleLauncher.kt:11](../app/src/main/java/com/overdrive/app/launcher/TailscaleLauncher.kt#L11), [GlobalProxyDaemon.java:15](../app/src/main/java/com/overdrive/app/daemon/GlobalProxyDaemon.java#L15), [ProxyConfiguration.kt:29](../app/src/main/java/com/overdrive/app/daemon/proxy/ProxyConfiguration.kt#L29).
