# Daemons and Processes

Overdrive is built around long-running processes that survive normal Android UI lifecycle changes. Android components start and supervise shell-launched daemons, while local TCP and HTTP servers provide control and data access.

## Android Components

### Application

`OverdriveApplication` initializes:

- Locale.
- Theme.
- Logging.
- Preferences.
- Foreground keepalive service startup.

### Activities

- `MainActivity`: launcher activity and main Material navigation shell.
- `BlockerActivity`: internal activity.
- `LocationStarterActivity`: internal activity.

### Receivers

- `BootReceiver`: handles boot, screen/user actions, power events, network changes, BYD ACC events, and package replacement.
- `ProcessRevivalReceiver`: internal explicit receiver used to revive processes.
- `LocationBootReceiver`: starts location-related behavior at boot.

### Foreground and Accessibility Services

- `DaemonKeepaliveService`: sticky foreground service, wake lock holder, daemon kickoff, process revival scheduling, status overlay coordination.
- `LocationSidecarService`: foreground location service that sends GPS to daemon IPC.
- `StatusOverlayService`: overlay status display.
- `KeepAliveAccessibilityService`: accessibility-backed keepalive support.

## Boot and Revival Behavior

`BootReceiver` responds to:

- Boot completion.
- Locked boot completion.
- Screen and user-present events.
- Power connected/disconnected.
- BYD ACC events.
- Network and Wi-Fi changes.
- Package replacement.

Normal boot behavior starts the keepalive service and schedules daemon startup. Package replacement is handled specially: the receiver launches `MainActivity` with a post-update flag and does not start daemons directly. This lets update recovery perform a cleaner daemon reset.

`DaemonKeepaliveService`:

- Starts in the foreground.
- Acquires a partial wake lock.
- Registers screen-off handling.
- Schedules process revival.
- Starts daemon launch after boot unless post-update reset is pending.
- Coordinates status overlay startup.

## Daemon Startup Manager

`DaemonStartupManager` is the main orchestrator.

Core daemons:

- `CAMERA_DAEMON`.
- `SENTRY_DAEMON`.
- `ACC_SENTRY_DAEMON`.

Optional daemons:

- `SINGBOX_PROXY`.
- `CLOUDFLARED_TUNNEL`.
- `ZROK_TUNNEL`.
- `TAILSCALE_TUNNEL`.
- `TELEGRAM_DAEMON`.

Startup timing:

- Core daemon startup is delayed around `45 seconds`.
- Optional daemon startup is delayed around `60 seconds`.
- Health checks begin around `90 seconds`.
- Health checks repeat around every `30 seconds`.
- Camera daemon starts before sentry and ACC sentry.

The manager tracks daemons intentionally stopped by the user so health checks do not immediately restart them.

## Shell Launch Layer

`AdbDaemonLauncher` coordinates:

- `AdbShellExecutor`.
- `DaemonLauncher`.
- `TunnelLauncher`.
- `ServiceLauncher`.

It can start:

- Camera daemon.
- Sentry daemon.
- ACC sentry daemon.
- Telegram bot daemon.
- Cloudflared tunnel.
- Zrok tunnel.
- Tailscale tunnel.
- sing-box proxy.
- Android sidecar services.

It also applies selected power, location, ACC whitelist, and Wi-Fi settings.

## Java Daemon Bootstrap

`DaemonBootstrap` is the `app_process` entrypoint used by Java daemons.

Responsibilities:

- Create an Android application context from low-level framework APIs.
- Use package `com.overdrive.app`.
- Grant or bypass permissions where possible.
- Wrap context permission checks so daemons can function from shell context.
- Dispatch into daemon entrypoints.

## Camera Daemon

`CameraDaemon` is the central daemon. It initializes:

- TCP command server on `127.0.0.1:19876`.
- HTTP server on `127.0.0.1:8080` by default.
- Surveillance IPC server on `127.0.0.1:19877`.
- ACC monitor.
- GPU camera and surveillance pipeline.
- Recording and streaming state.
- Unified config.
- Auth state.
- Storage manager.
- Web asset extraction.
- Native libraries.
- BYD data collector.
- BYD cloud subscriber.
- MQTT.
- ABRP.
- Trip analytics.
- Telemetry collector.
- Notifications.

The daemon uses an Android Looper and defensive retry handling around BYD listener paths because some firmware listeners can fail or crash unexpectedly.

## TCP Command Server

`TcpCommandServer` listens on:

```text
127.0.0.1:19876
```

It accepts JSON commands for local control. Known command areas include:

- Start and stop recording.
- Status and ping.
- Output path.
- Shutdown.
- Start and stop streaming.
- Quality and bitrate.
- Recording mode.
- Storage.
- Auth invalidation.
- Secret get, put, delete, and section operations.

`CameraDaemonClient` is the app-side client for this interface.

## Surveillance IPC Server

`SurveillanceIpcServer` listens on:

```text
127.0.0.1:19877
```

It accepts local JSON commands used by the app, Telegram daemon, location sidecar, update flows, and surveillance controllers. Known command areas include:

- Start, stop, and status.
- Enable and disable surveillance.
- GPS update.
- ABRP actions.
- MQTT actions.
- Update install actions.

The server uses a thread pool for concurrent local requests.

## HTTP Server

`HttpServer` listens on:

```text
127.0.0.1:8080
```

If LAN HTTP is explicitly enabled, it binds:

```text
0.0.0.0:8080
```

Responsibilities:

- Serve extracted web app assets.
- Serve static local and shared assets.
- Serve recording videos and thumbnails.
- Enforce auth middleware.
- Handle REST APIs.
- Handle WebSocket upgrades for streaming.
- Expose auth endpoints.
- Extract web and support assets from the APK.

## Location Sidecar Service

`LocationSidecarService` is an Android foreground service. It:

- Reads Android location updates.
- Caches GPS state in app files as `gps_cache.json`.
- Sends GPS JSON to `127.0.0.1:19877`.
- Uses the `UPDATE_GPS` surveillance IPC command.
- Sends updates roughly every two seconds while active.

## Tunnel and Proxy Processes

### Cloudflared

Extracted binary:

```text
/data/local/tmp/cloudflared
```

Runs a tunnel to:

```text
http://127.0.0.1:8080
```

Cloudflared log:

```text
/data/local/tmp/cloudflared.log
```

### Zrok

Extracted binary:

```text
/data/local/tmp/zrok
```

Supports public and reserved shares. It uses:

```text
/data/local/tmp/.zrok/environment.json
/data/local/tmp/.zrok/unique_name
/data/local/tmp/zrok.log
```

### Tailscale

Runtime directory:

```text
/data/local/tmp/.tailscale
```

Userspace socket:

```text
127.0.0.1:8532
```

Optional SOCKS5 proxy:

```text
127.0.0.1:8539
```

### sing-box

Binary and config:

```text
/data/local/tmp/sing-box
/data/local/tmp/singbox_config.json
```

Local mixed proxy:

```text
127.0.0.1:8119
```

The generated config may include VLESS Reality outbound settings. Do not publish actual proxy credentials.

## Process Interaction Summary

```text
BootReceiver / MainActivity
  -> DaemonKeepaliveService
  -> DaemonStartupManager
  -> AdbDaemonLauncher
  -> app_process Java daemons and extracted native binaries

Android UI
  -> TCP 19876 and WebView HTTP 8080

Location sidecar / Telegram / app helpers
  -> TCP 19877 surveillance IPC

Browser or tunnel client
  -> HTTP/WebSocket 8080

Camera daemon
  -> BYD local APIs, BYD cloud HTTPS/MQTT, storage, notifications, trips
```

## Source References

- Android components declared in manifest: [AndroidManifest.xml:207](../app/src/main/AndroidManifest.xml#L207), [AndroidManifest.xml:255](../app/src/main/AndroidManifest.xml#L255), [AndroidManifest.xml:306](../app/src/main/AndroidManifest.xml#L306), [AndroidManifest.xml:312](../app/src/main/AndroidManifest.xml#L312), [AndroidManifest.xml:327](../app/src/main/AndroidManifest.xml#L327).
- Application, activity, receivers, and foreground services: [OverdriveApplication.kt:18](../app/src/main/java/com/overdrive/app/OverdriveApplication.kt#L18), [MainActivity.kt:46](../app/src/main/java/com/overdrive/app/ui/MainActivity.kt#L46), [BootReceiver.kt:24](../app/src/main/java/com/overdrive/app/receiver/BootReceiver.kt#L24), [ProcessRevivalReceiver.kt:29](../app/src/main/java/com/overdrive/app/receiver/ProcessRevivalReceiver.kt#L29), [LocationBootReceiver.kt:14](../app/src/main/java/com/overdrive/app/receiver/LocationBootReceiver.kt#L14), [DaemonKeepaliveService.kt:30](../app/src/main/java/com/overdrive/app/services/DaemonKeepaliveService.kt#L30), [LocationSidecarService.java:32](../app/src/main/java/com/overdrive/app/services/LocationSidecarService.java#L32).
- Daemon startup and shell launch: [DaemonStartupManager.kt:15](../app/src/main/java/com/overdrive/app/ui/daemon/DaemonStartupManager.kt#L15), [DaemonStartupManager.kt:251](../app/src/main/java/com/overdrive/app/ui/daemon/DaemonStartupManager.kt#L251), [AdbDaemonLauncher.kt:17](../app/src/main/java/com/overdrive/app/launcher/AdbDaemonLauncher.kt#L17), [DaemonBootstrap.java:22](../app/src/main/java/com/overdrive/app/daemon/DaemonBootstrap.java#L22).
- Camera daemon ports and server setup: [CameraDaemon.java:53](../app/src/main/java/com/overdrive/app/daemon/CameraDaemon.java#L53), [CameraDaemon.java:350](../app/src/main/java/com/overdrive/app/daemon/CameraDaemon.java#L350), [TcpCommandServer.java:22](../app/src/main/java/com/overdrive/app/server/TcpCommandServer.java#L22), [SurveillanceIpcServer.java:22](../app/src/main/java/com/overdrive/app/server/SurveillanceIpcServer.java#L22), [HttpServer.java:49](../app/src/main/java/com/overdrive/app/server/HttpServer.java#L49).
- TCP and surveillance IPC commands: [CameraDaemonClient.java:169](../app/src/main/java/com/overdrive/app/client/CameraDaemonClient.java#L169), [TcpCommandServer.java:125](../app/src/main/java/com/overdrive/app/server/TcpCommandServer.java#L125), [TcpCommandServer.java:262](../app/src/main/java/com/overdrive/app/server/TcpCommandServer.java#L262), [SurveillanceIpcServer.java:136](../app/src/main/java/com/overdrive/app/server/SurveillanceIpcServer.java#L136), [SurveillanceIpcServer.java:540](../app/src/main/java/com/overdrive/app/server/SurveillanceIpcServer.java#L540).
- Location sidecar IPC: [LocationSidecarService.java:32](../app/src/main/java/com/overdrive/app/services/LocationSidecarService.java#L32), [AccSentryDaemon.java:2078](../app/src/main/java/com/overdrive/app/daemon/AccSentryDaemon.java#L2078).
- Tunnel and proxy processes: [TunnelLauncher.kt:12](../app/src/main/java/com/overdrive/app/launcher/TunnelLauncher.kt#L12), [ZrokLauncher.kt:27](../app/src/main/java/com/overdrive/app/launcher/ZrokLauncher.kt#L27), [ZrokLauncher.kt:1079](../app/src/main/java/com/overdrive/app/launcher/ZrokLauncher.kt#L1079), [TailscaleLauncher.kt:11](../app/src/main/java/com/overdrive/app/launcher/TailscaleLauncher.kt#L11), [GlobalProxyDaemon.java:15](../app/src/main/java/com/overdrive/app/daemon/GlobalProxyDaemon.java#L15), [ProxyConfiguration.kt:29](../app/src/main/java/com/overdrive/app/daemon/proxy/ProxyConfiguration.kt#L29).
