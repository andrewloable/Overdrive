# Architecture

Overdrive is a hybrid Android, native, and web application. The installed Android app owns user interaction and lifecycle hooks, while privileged shell-launched daemon processes do long-running camera, recording, surveillance, networking, telemetry, and web-server work.

## High-Level Shape

```text
Android launcher UI
  -> MainActivity, fragments, WebView shell
  -> foreground services and boot receivers
  -> DaemonStartupManager
  -> ADB shell / app_process launchers
  -> CameraDaemon, SentryDaemon, AccSentryDaemon, Telegram daemon, tunnel daemons

CameraDaemon
  -> local TCP command server on 127.0.0.1:19876
  -> local HTTP/WebSocket server on 127.0.0.1:8080
  -> surveillance IPC server on 127.0.0.1:19877
  -> GPU camera and surveillance pipeline
  -> recording, streaming, telemetry, trips, notifications, MQTT, BYD cloud

Embedded web UI
  -> served from extracted APK assets under /data/local/tmp/web
  -> talks to CameraDaemon HTTP APIs
  -> uses WebSocket streaming for live H.264 frames

BYD integrations
  -> local BYD framework reflection and listeners
  -> BYD cloud HTTPS and MQTT v5
  -> vehicle state, diagnostics, control, and deterrent commands
```

## Build Modules

The repository is a single Android Gradle project:

- Root project: `Overdrive`.
- Android module: `:app`.
- Namespace and application id: `com.overdrive.app`.
- Minimum SDK: 25.
- Target SDK: 25.
- Compile SDK: 36.
- Native ABI split: `arm64-v8a`.
- Java and Kotlin target: 11.

The app uses AndroidX, Material, Navigation, lifecycle, WorkManager, Dadb, OkHttp, Eclipse Paho MQTT, TensorFlow Lite, H2, WebSocket support, and native CMake builds.

## Runtime Boundaries

### Android App Process

The ordinary Android app process hosts:

- `OverdriveApplication`.
- `MainActivity`.
- Native Android fragments and view models.
- In-app WebView wrapper for the embedded web UI.
- Boot, power, location, and process-revival receivers.
- Foreground services used to keep the system alive.
- Shell launch orchestration for daemon processes.

### Shell-Launched Daemon Processes

The daemon processes are launched with Android `app_process` or extracted native binaries. They run outside the normal Activity lifecycle and use shared config under `/data/local/tmp` so that app, daemons, and web server can coordinate.

Core daemon roles:

- Camera daemon: camera, recording, streaming, HTTP API, WebSocket, telemetry, storage, notifications, BYD cloud, MQTT, trips.
- Sentry daemon: surveillance mode orchestration.
- ACC sentry daemon: ACC-aware sentry behavior.
- Telegram daemon: bot integration and remote commands.
- Tunnel daemons: Cloudflared, Zrok, Tailscale.
- Proxy daemon: sing-box/VLESS proxy support.

### Native Libraries

Native code is used for camera texture binding, surveillance motion processing, OpenCV, OpenH264, and related performance-sensitive paths.

Important native areas:

- `app/src/main/cpp/camera/`.
- `app/src/main/cpp/surveillance/`.
- `app/src/main/cpp/CMakeLists.txt`.
- Downloaded OpenH264 and opencv-mobile artifacts handled by Gradle tasks.

## Startup Lifecycle

1. Android starts `OverdriveApplication`.
2. The application initializes logging, preferences, locale/theme, and starts `DaemonKeepaliveService`.
3. `MainActivity` initializes device identity, storage, BYD whitelist behavior, daemon startup management, WebView pages, and update checks.
4. `BootReceiver` handles boot, package replacement, screen, power, network, and BYD ACC events.
5. `DaemonKeepaliveService` runs as a sticky foreground service, holds a partial wake lock, and schedules process revival.
6. `DaemonStartupManager` delays launch to let the vehicle head unit settle, then starts core daemons and optional daemons.
7. `AdbDaemonLauncher` and lower launchers execute shell commands that start Java daemons or native tunnel binaries.

Core daemon timing is intentionally staggered:

- Core start is delayed around 45 seconds.
- Optional daemon start is delayed around 60 seconds.
- Health checks begin around 90 seconds and repeat every 30 seconds.
- Camera daemon starts first, then sentry and ACC sentry are delayed behind it.

## Main Components

### `OverdriveApplication`

Initializes global app concerns:

- Locale and theme.
- Logging.
- Preferences manager.
- Foreground keepalive service.

### `MainActivity`

Owns the Android shell:

- Material navigation rail.
- Fragment navigation.
- WebView entry points.
- Storage setup.
- Device ID initialization.
- BYD whitelist application.
- Daemon startup manager initialization.
- Location sidecar startup.
- Update checks.
- Post-update daemon reset behavior.
- Status overlay startup.

### `DaemonStartupManager`

Coordinates daemon launch, optional tunnel/proxy launch, health checks, and user-stopped daemon state. It treats camera, sentry, and ACC sentry as core daemons and treats sing-box, Cloudflared, Zrok, Tailscale, and Telegram as optional daemons.

### `AdbDaemonLauncher`

Facade over daemon and tunnel launchers. It starts camera, sentry, ACC sentry, Telegram, sing-box, Cloudflared, and related services through shell execution.

### `DaemonBootstrap`

The bootstrap entrypoint used by shell-launched Java daemons. It creates an Android context from low-level framework classes, hardcodes the package name, grants or bypasses permissions where possible, and invokes daemon main code.

### `CameraDaemon`

The central long-running daemon. It starts local command and web servers, initializes the camera/GPU pipeline, config, auth, storage, telemetry, trip analytics, BYD collection, cloud integration, MQTT, ABRP, notifications, and surveillance IPC.

### `HttpServer`

Embedded HTTP server for static web assets, REST APIs, auth endpoints, thumbnail/video serving, update APIs, and WebSocket live streaming.

### `GpuSurveillancePipeline`

Coordinates panoramic camera input, GPU scaling, recording, AI lane processing, surveillance state, adaptive bitrate, telemetry overlay, and streaming.

### `BydDataCollector`

The main local BYD telemetry collector. It discovers BYD framework devices through reflection, reads initial values, registers listeners, and maintains a thread-safe vehicle snapshot.

### `BydCloudClient` and `BydCloudMqttSubscriber`

Cloud integration layer for BYD account login, vehicle discovery, control commands, real-time state request/polling, MQTT credential discovery, MQTT subscription, payload decryption, and snapshot merging.

## Design Patterns

- Reflection is used heavily for BYD local APIs so the app can compile with stubs but run against the vehicle firmware classes.
- Shared JSON files under `/data/local/tmp` are used for cross-process config and secrets.
- Daemons expose local TCP/HTTP IPC rather than relying on Activity-bound Android services.
- The Android WebView bypasses proxy issues by injecting a bridge for mutating API calls while allowing normal GET navigation.
- Optional remote access is layered over the local web server through tunnels instead of exposing internet-facing server code directly.
- Surveillance and camera paths prioritize long-running stability over tight coupling with Android UI lifecycle.

## Major Risk Areas

- The app relies on privileged shell behavior, BYD firmware APIs, and Android head-unit quirks.
- `/data/local/tmp` config must be protected carefully because multiple processes use it.
- LAN mode exposes the embedded web server on all interfaces and must remain opt-in.
- Tunnel URLs are only safe when paired with token auth.
- BYD cloud APIs and Bangcle encryption behavior can change outside this repository.
- BYD local API listener behavior can crash certain firmware paths, so some listeners are intentionally skipped or isolated.
