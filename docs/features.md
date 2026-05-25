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
