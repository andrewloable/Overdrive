# Daemons and Processes

BladeWatch is built around long-running processes that survive normal Android UI lifecycle changes. Android components start and supervise shell-launched daemons, while local TCP and HTTP servers provide control and data access.

## Android Components

### Application

`BladeWatchApplication` initializes:

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

- `ZROK_TUNNEL`.

Startup timing (measured from app launch / boot):

- Core daemon startup is delayed around `45 seconds` (system stabilization).
- Optional daemon startup is delayed around `60 seconds`.
- Health checks begin around `90 seconds`.
- Health checks repeat every `30 seconds` (`HEALTH_CHECK_INTERVAL_MS`).
- Within the core group, daemons are further staggered: Camera daemon first, Sentry daemon `+5 s`, ACC sentry daemon `+10 s`.

The manager tracks daemons intentionally stopped by the user (`userStoppedDaemons`) so health checks do not immediately restart them. The user-stopped set is cleared on each fresh app launch / boot.

The health check is stale-aware for the camera daemon: instead of a plain process-exists check it runs the full launch flow, which verifies **both** process existence (`ps`) and port responsiveness (`nc -z`), so a hung daemon that `ps` shows but won't accept connections is killed and relaunched, while transient ADB blips do not trigger false relaunches.

`DaemonKeepaliveService` is the boot entrypoint into the manager (`startOnBoot`), but it **skips** daemon startup when a post-update launch is in progress — in that case `MainActivity` is the sole orchestrator (it runs `UpdateLifecycle.hardResetDaemons` first, then `initializeOnAppLaunch`) to avoid racing two 45 s schedules and overlapping camera handles on the AVMCamera HAL.

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
- Zrok tunnel.
- Android sidecar services.

It also applies selected power, location, ACC whitelist, and Wi-Fi settings.

## Java Daemon Bootstrap

`DaemonBootstrap` is the `app_process` entrypoint used by Java daemons.

Responsibilities:

- Create an Android application context from low-level framework APIs.
- Use package `com.loabletech.bladewatch`.
- Grant or bypass permissions where possible.
- Wrap context permission checks so daemons can function from shell context.
- Dispatch into daemon entrypoints.

## Camera Daemon

`CameraDaemon` is the central daemon. Its startup sequence:

1. Singleton enforcement: a port-in-use probe (`anyPortInUse()`) plus a `FileLock` on `/data/local/tmp/camera_daemon.lock`. If either indicates a live instance, the new process exits ("Another CameraDaemon instance is already running").
2. Deletes any stale ready sentinel left by a previous crash.
3. Generates the shared IPC token via `IpcTokenManager.generate()` **before** starting servers (idempotent — reuses an existing well-formed token, see `ipc-auth-and-secrets.md`).
4. Starts the three local servers:
   - TCP command server on `127.0.0.1:19876`.
   - HTTP server on `127.0.0.1:8080` by default.
   - Surveillance IPC server on `127.0.0.1:19877`.
5. Initializes ACC monitor, GPU camera and surveillance pipeline, recording/streaming state, unified config, auth state, storage manager, web asset extraction, native libraries, BYD data collector, trip analytics, telemetry collector, and Web Push notifications.
6. Confirms `TCP_PORT` is actually accepting connections (polls up to 5s), then writes the ready sentinel.

The daemon uses an Android Looper and defensive retry handling around BYD listener paths because some firmware listeners can fail or crash unexpectedly.

### Ready sentinel and readiness probe

The daemon signals "startup complete" by writing its PID to a sentinel file:

```text
/data/local/tmp/camera_daemon.ready
```

It is written world-readable (`644`, via `setReadable(true, false)`) so the app UID can stat it. A stale sentinel left by a `kill -9`'d daemon is the reason readiness is **not** decided by the sentinel alone.

`DaemonReadinessChecker` (app-side, [DaemonReadinessChecker.java](../app/src/main/java/com/loabletech/bladewatch/client/DaemonReadinessChecker.java)) decides readiness with two checks:

- The sentinel exists and is non-empty, AND
- a short-lived TCP connect to `127.0.0.1:19876` (the command port) succeeds (`PROBE_TIMEOUT_MS = 1000`).

The TCP connect is the authoritative liveness signal — it is UID-independent and survives the head unit's `hidepid=2,gid=3009` `/proc` mount (the app UID cannot see the shell-owned daemon's `/proc` entry, so a `/proc/<pid>` check would always fail). A connect also catches the stale-sentinel case (a dead daemon refuses the connect).

`waitUntilReady(timeoutMs)` polls every 500 ms and logs progress every 5 s. It is used by `CameraDaemonClient.connect()` (60 s for cold-boot callers, 2 s for mid-session reconnects) and by `SecretConfigBridge` (30 s) before any IPC read/write.

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

Every connection is gated twice before any command runs: the connecting socket's owning UID must be trusted (`PeerCredentials.isTrusted`), and the first message must carry a valid IPC token (`IpcTokenManager.isValid`). See `ipc-auth-and-secrets.md`.

`CameraDaemonClient` is the app-side client for this interface. Before connecting it calls `DaemonReadinessChecker.waitUntilReady(...)` so it does not poll a not-yet-listening port, retries `connect()` up to 3× on `IOException` (connection refused / slow accept), and sends `IpcTokenManager.refreshToken()` (a fresh on-disk read, not the cache) as the first message so it never presents a token cached before the daemon's last (re)write.

## Surveillance IPC Server

`SurveillanceIpcServer` listens on:

```text
127.0.0.1:19877
```

It accepts local JSON commands used by the app, location sidecar, update flows, and surveillance controllers. Known command areas include:

- Start, stop, and status.
- Enable and disable surveillance.
- GPS update.
- Update install actions.

Like the TCP command server, each request is gated by both the caller-UID check (`PeerCredentials.isTrusted`) and a valid IPC token (`IpcTokenManager.isValid`).

The server uses a fixed thread pool (8 threads) for concurrent local requests.

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

## Zrok Tunnel Process

Zrok is the sole remote-access tunnel. It is extracted from the packaged `libzrok.so` native library and run as a subprocess.

Runtime paths:

```text
/data/local/tmp/zrok
/data/local/tmp/zrok.log
/data/local/tmp/.zrok/environment.json
/data/local/tmp/.zrok/unique_name
```

Zrok fronts the local HTTP server at `http://127.0.0.1:8080` and supports public ephemeral shares or reserved shares with a stable `https://<name>.share.zrok.io` URL. It runs directly with no intermediate proxy layer.

## Process Interaction Summary

```text
BootReceiver / MainActivity
  -> DaemonKeepaliveService
  -> DaemonStartupManager
  -> AdbDaemonLauncher
  -> app_process Java daemons and extracted Zrok native binary

Android UI
  -> TCP 19876 and WebView HTTP 8080

Location sidecar / app helpers
  -> TCP 19877 surveillance IPC

Browser or tunnel client
  -> HTTP/WebSocket 8080

Camera daemon
  -> BYD local APIs, storage, Web Push notifications, trips
```

## Source References

- Android components declared in manifest: [AndroidManifest.xml:207](../app/src/main/AndroidManifest.xml#L207), [AndroidManifest.xml:255](../app/src/main/AndroidManifest.xml#L255), [AndroidManifest.xml:306](../app/src/main/AndroidManifest.xml#L306), [AndroidManifest.xml:312](../app/src/main/AndroidManifest.xml#L312), [AndroidManifest.xml:327](../app/src/main/AndroidManifest.xml#L327).
- Application, activity, receivers, and foreground services: [BladeWatchApplication.kt:18](../app/src/main/java/com/loabletech/bladewatch/BladeWatchApplication.kt#L18), [MainActivity.kt:46](../app/src/main/java/com/loabletech/bladewatch/ui/MainActivity.kt#L46), [BootReceiver.kt:24](../app/src/main/java/com/loabletech/bladewatch/receiver/BootReceiver.kt#L24), [ProcessRevivalReceiver.kt:29](../app/src/main/java/com/loabletech/bladewatch/receiver/ProcessRevivalReceiver.kt#L29), [LocationBootReceiver.kt:14](../app/src/main/java/com/loabletech/bladewatch/receiver/LocationBootReceiver.kt#L14), [DaemonKeepaliveService.kt:30](../app/src/main/java/com/loabletech/bladewatch/services/DaemonKeepaliveService.kt#L30), [LocationSidecarService.java:32](../app/src/main/java/com/loabletech/bladewatch/services/LocationSidecarService.java#L32).
- Daemon startup and shell launch: [DaemonStartupManager.kt:15](../app/src/main/java/com/loabletech/bladewatch/ui/daemon/DaemonStartupManager.kt#L15), [DaemonStartupManager.kt:73](../app/src/main/java/com/loabletech/bladewatch/ui/daemon/DaemonStartupManager.kt#L73), [DaemonStartupManager.kt:418](../app/src/main/java/com/loabletech/bladewatch/ui/daemon/DaemonStartupManager.kt#L418), [DaemonKeepaliveService.kt:72](../app/src/main/java/com/loabletech/bladewatch/services/DaemonKeepaliveService.kt#L72), [AdbDaemonLauncher.kt:17](../app/src/main/java/com/loabletech/bladewatch/launcher/AdbDaemonLauncher.kt#L17), [DaemonBootstrap.java:22](../app/src/main/java/com/loabletech/bladewatch/daemon/DaemonBootstrap.java#L22).
- Camera daemon ports and server setup: [CameraDaemon.java:51](../app/src/main/java/com/loabletech/bladewatch/daemon/CameraDaemon.java#L51), [CameraDaemon.java:377](../app/src/main/java/com/loabletech/bladewatch/daemon/CameraDaemon.java#L377), [CameraDaemon.java:381](../app/src/main/java/com/loabletech/bladewatch/daemon/CameraDaemon.java#L381), [TcpCommandServer.java:22](../app/src/main/java/com/loabletech/bladewatch/server/TcpCommandServer.java#L22), [SurveillanceIpcServer.java:23](../app/src/main/java/com/loabletech/bladewatch/server/SurveillanceIpcServer.java#L23), [HttpServer.java:49](../app/src/main/java/com/loabletech/bladewatch/server/HttpServer.java#L49).
- Daemon readiness sentinel and probe: [CameraDaemon.java:242](../app/src/main/java/com/loabletech/bladewatch/daemon/CameraDaemon.java#L242), [CameraDaemon.java:633](../app/src/main/java/com/loabletech/bladewatch/daemon/CameraDaemon.java#L633), [DaemonReadinessChecker.java:33](../app/src/main/java/com/loabletech/bladewatch/client/DaemonReadinessChecker.java#L33), [DaemonReadinessChecker.java:59](../app/src/main/java/com/loabletech/bladewatch/client/DaemonReadinessChecker.java#L59).
- TCP and surveillance IPC commands: [CameraDaemonClient.java:61](../app/src/main/java/com/loabletech/bladewatch/client/CameraDaemonClient.java#L61), [TcpCommandServer.java:93](../app/src/main/java/com/loabletech/bladewatch/server/TcpCommandServer.java#L93), [TcpCommandServer.java:108](../app/src/main/java/com/loabletech/bladewatch/server/TcpCommandServer.java#L108), [SurveillanceIpcServer.java:75](../app/src/main/java/com/loabletech/bladewatch/server/SurveillanceIpcServer.java#L75), [SurveillanceIpcServer.java:107](../app/src/main/java/com/loabletech/bladewatch/server/SurveillanceIpcServer.java#L107).
- Location sidecar IPC: [LocationSidecarService.java:32](../app/src/main/java/com/loabletech/bladewatch/services/LocationSidecarService.java#L32), [AccSentryDaemon.java:2078](../app/src/main/java/com/loabletech/bladewatch/daemon/AccSentryDaemon.java#L2078).
- Zrok tunnel process: [TunnelLauncher.kt:12](../app/src/main/java/com/loabletech/bladewatch/launcher/TunnelLauncher.kt#L12), [ZrokLauncher.kt:27](../app/src/main/java/com/loabletech/bladewatch/launcher/ZrokLauncher.kt#L27), [ZrokLauncher.kt:1079](../app/src/main/java/com/loabletech/bladewatch/launcher/ZrokLauncher.kt#L1079).
