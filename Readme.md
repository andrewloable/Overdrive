<p align="center">
  <img src="app/src/main/assets/web/shared/app-icon-ios.webp" width="120" alt="BladeWatch Logo">
</p>

<h1 align="center">BladeWatch</h1>

Free, open-source dashcam and sentry mode app built specifically for BYD vehicles with DiLink v3. All recordings and data stay on your device — no cloud, no accounts, no subscriptions. Optional remote viewing is direct, peer-to-peer through your own tunnel.

BladeWatch targets BYD DiLink v3 head units (`arm64-v8a`) and installs onto the car's head unit over ADB.

## Quick Start (Use Pre-built APK)

Download the latest APK from [GitHub Releases] and install it directly on your BYD head unit.

### 1. Prerequisites
- Ensure **Wireless ADB** is enabled on your device before launching the app.

### 2. Initial Configuration
1. **Authorize ADB:** On first launch, accept the ADB authentication prompt on your device screen.
2. **Background Persistence:** In Settings, ensure the **"Disable Autostart"** toggle is **unchecked**. This is critical for reliable background operation.

> ⚠️ **CRITICAL: Hard Reboot Required**
> After the first installation and initial run, you must hard reboot the device:
> Press and hold the **Volume Down** button for 5 seconds. Wait for the system to fully restart.
> This step is necessary to finalize the installation.

---

## Features

### Recording
- **Panoramic Dashcam** — Records the BYD 360° panoramic camera through a GPU mosaic pipeline (H.264/H.265), segmented into configurable clips with a recording library and calendar view for browsing and managing footage.
- **Proximity Recording (Market First)** — Uses BYD's 8 parking radar sensors to trigger recording only when objects approach the parked car. Configurable trigger levels, pre-event buffer, and 500ms debouncing.
- **Advanced Sentry Mode** — 24/7 surveillance with GPU motion detection, per-quadrant tracking, and an optional on-device AI object-recognition gate (TFLite YOLO11n). Supports safe-location zones, schedules, and pre/post-event windows.

### Vehicle & Driving
- **Vehicle Control** — Operate climate (AC, temperature, fan, max-cooling), windows (open/close and partial positioning), and seats (heat, ventilation, memory recall) directly from the app. Runs entirely through the local BYD SDK — no cloud account required.
- **3D Vehicle Hero** — An interactive 3D model of your car (Seal, Seal U, Dolphin, Atto 3, Han, Tang, and more) with a live state dashboard: doors, windows, battery SOC and range, per-tyre pressure, and climate.
- **Live Location** — Map view of the car's current position with a heading-rotated marker and a one-tap link out to Google Maps.
- **Trips & Analytics** — Trip history with route maps, telemetry, and driving insights.

### Monitoring & Remote Access
- **Real-time Performance Monitor** — CPU, GPU, memory usage, and battery voltage dashboard.
- **Diagnostics** — Network, storage, camera, and battery health checks.
- **Live Streaming** — Low-latency H.264 streaming over WebSocket with multiple view modes (all cameras, front, rear, left, right).
- **Remote Web App** — A full Angular web UI served by the on-device daemon, reachable from any browser through your tunnel. Token-protected.
- **Web Push Notifications** — Get surveillance event alerts pushed to your phone or desktop.
- **ADB Shell Runner** — Built-in terminal for running commands, checking processes, and viewing logs.
- **17 Languages** — Fully localized UI.

### Zrok Tunnel (Recommended)
Free, open-source tunneling with no bandwidth limits at `https://<your-share>.share.zrok.io`. Best for video streaming. Do not share the invite token or public share link unless you intend to expose the car UI.

**Quick Zrok setup:**
1. Sign up at [zrok.io](https://zrok.io)
2. Get your invite token from email
3. Enter token in BladeWatch settings
4. Done — tunnel URL is auto-generated

> Should work on all BYD vehicles with DiLink v3 and the panoramic camera system.

## Zrok Token Setup (Optional)

If you want to use Zrok tunneling for remote access, you need your own Zrok invite token:

1. Sign up at [zrok.io](https://zrok.io) and get your invite token from email.
2. Enter the token in the app: Daemons → Zrok settings.
3. If you are building from source, prefer the on-device settings flow instead of hardcoding the token into source.

## Building from Source

BladeWatch is a hybrid project: a native Android/Kotlin app plus an Angular web UI that the on-device daemon serves to remote browsers.

### Requirements
- Android SDK (`compileSdk 36`) and NDK
- JDK 11
- Node.js + npm (for the Angular web UI)
- [`buf`](https://buf.build) — optional, only needed to regenerate the protobuf / ConnectRPC stubs

### Build

```bash
# Debug build — also builds the Angular web UI and native libraries,
# then bundles them into an arm64-v8a APK.
./gradlew assembleDebug

# Output:
#   app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
```

The Gradle build orchestrates everything:
- `buildAngularWebUI` builds the Angular app under `web/` and copies the output into the APK assets (hooked into `preBuild`; requires npm).
- Native dependencies (OpenH264, opencv-mobile, TensorFlow Lite) are auto-downloaded and checksum-verified — no manual download step.
- `generateConnectProtos` regenerates Java + TypeScript stubs from `proto/bladewatch/v1/*.proto` (only needed when the API schemas change).

The app communicates with its embedded daemon over a REST API and a 1:1 ConnectRPC/gRPC layer on `127.0.0.1:8080`. For device install, daemon cleanup, and the full development workflow, see [`CLAUDE.md`](CLAUDE.md) and the [`docs/`](docs/) directory.

### Documentation
In-depth documentation lives in [`docs/`](docs/) — architecture, daemons and processes, IPC/auth/secrets, networking and tunnels, the HTTP API reference, BYD integrations, the surveillance pipeline, and storage.

## Privacy

- 100% local storage — all recordings saved on device
- No account required
- No cloud upload — remote viewing is direct via tunnels you control
- Open source — audit the code yourself

## Acknowledgments

- **3D BYD Vehicle Models** — The Vehicle Control page renders interactive 3D cars with [Three.js](https://threejs.org/), using base models from [ddiaz-design's BYD collection on Sketchfab](https://sketchfab.com/ddiaz-design/collections/byd-base-models-5bf92ab5f2be4ff6be5c3ac49f7099f3).

## License

Open source under MIT License. Your data stays on your device.
