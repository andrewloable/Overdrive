# Architecture

BladeWatch is a hybrid Android, native, and web application. The installed Android app owns user interaction and lifecycle hooks, while privileged shell-launched daemon processes do long-running camera, recording, surveillance, networking, telemetry, and web-server work.

## High-Level Shape

```text
Android launcher UI (native shell)
  -> MainActivity, M3 navigation rail, native Kotlin fragments
  -> foreground services and boot receivers
  -> DaemonStartupManager
  -> ADB shell / app_process launchers
  -> CameraDaemon, SentryDaemon, AccSentryDaemon, Zrok tunnel daemon

CameraDaemon
  -> local TCP command server on 127.0.0.1:19876
  -> local HTTP/WebSocket server on 127.0.0.1:8080
  -> surveillance IPC server on 127.0.0.1:19877
  -> Connect protocol RPC dispatch at /bladewatch.v1.<Service>/<Method>
  -> GPU camera and surveillance pipeline
  -> recording, streaming, telemetry, trips, Web Push notifications

Embedded web UI (Angular 19 SPA)
  -> built with Vite + @analogjs/vite-plugin-angular (no angular.json)
  -> built into web/dist, copied into assets/web/angular, extracted to
     /data/local/tmp/web/angular and served by the daemon at /
  -> talks to CameraDaemon over ConnectRPC (@connectrpc/connect-web)
  -> uses WebSocket streaming for live H.264 frames
  -> used for remote browser / tunnel access (the in-app UI is native)

BYD integrations
  -> local BYD framework reflection and listeners
  -> vehicle state, diagnostics, and local SDK controls
```

## Build Modules

The repository is a single Android Gradle project:

- Root project: `BladeWatch`.
- Android module: `:app`.
- Namespace and application id: `net.bladewatch.app` (source dirs still live
  under `com/loabletech/bladewatch/` for historical reasons).
- Minimum SDK: 25.
- Target SDK: 25.
- Compile SDK: 36.
- Native ABI split: `arm64-v8a`.
- Java and Kotlin target: 11.

The app uses AndroidX, Material, Navigation, lifecycle, WorkManager, Dadb, OkHttp, TensorFlow Lite, H2, WebSocket support, and native CMake builds.

The embedded web UI is a separate Angular 19 project under `web/` (Vite +
`@analogjs/vite-plugin-angular`, ConnectRPC, Leaflet, `@ngx-translate`, qrcode).
The Gradle task `buildAngularWebUI` runs `npm run build` in `web/`, copies
`web/dist` into `app/src/main/assets/web/angular/`, and is hooked into `preBuild`
so the SPA is compiled before assets are packaged (skipped if `npm` is absent —
the committed `web/dist` is used instead). Protobuf service contracts live in
`proto/bladewatch/v1/*.proto`; `buf generate` emits TypeScript message classes
into `web/src/gen` and Java messages + Kotlin Connect stubs into the app source
tree.

## Runtime Boundaries

### Android App Process

The ordinary Android app process hosts:

- `BladeWatchApplication`.
- `MainActivity`.
- Native Android fragments and view models (the in-app UI is fully native —
  M3 navigation rail + Kotlin fragments wired through `nav_graph.xml`).
- A `WebViewFragment` host retained for the remote tunnel / browser path; it is
  no longer a nav destination.
- Boot, power, location, and process-revival receivers.
- Foreground services used to keep the system alive.
- Shell launch orchestration for daemon processes.

### Shell-Launched Daemon Processes

The daemon processes are launched with Android `app_process` or extracted native binaries. They run outside the normal Activity lifecycle and use shared config under `/data/local/tmp` so that app, daemons, and web server can coordinate.

Core daemon roles:

- Camera daemon: camera, recording, streaming, HTTP API, WebSocket, telemetry, storage, Web Push notifications, trips.
- Sentry daemon: surveillance mode orchestration.
- ACC sentry daemon: ACC-aware sentry behavior.
- Zrok tunnel daemon: optional remote access tunnel.

### Native Libraries

Native code is used for camera texture binding, surveillance motion processing, OpenCV, OpenH264, and related performance-sensitive paths.

Important native areas:

- `app/src/main/cpp/camera/`.
- `app/src/main/cpp/surveillance/`.
- `app/src/main/cpp/CMakeLists.txt`.
- Downloaded OpenH264 and opencv-mobile artifacts handled by Gradle tasks.
- `libzrok.so` packaged in `jniLibs/` for the Zrok tunnel.

## Startup Lifecycle

1. Android starts `BladeWatchApplication`.
2. The application initializes logging, preferences, locale/theme, and starts `DaemonKeepaliveService`.
3. `MainActivity` initializes device identity, storage, BYD whitelist behavior, daemon startup management, native fragment navigation, and update checks.
4. `BootReceiver` handles boot, package replacement, screen, power, network, and BYD ACC events.
5. `DaemonKeepaliveService` runs as a sticky foreground service, holds a partial wake lock, and schedules process revival.
6. `DaemonStartupManager` delays launch to let the vehicle head unit settle, then starts core daemons and the optional Zrok tunnel.
7. `AdbDaemonLauncher` and lower launchers execute shell commands that start Java daemons or the native Zrok binary.

Core daemon timing is intentionally staggered:

- Core start is delayed around 45 seconds.
- Optional daemon start is delayed around 60 seconds.
- Health checks begin around 90 seconds and repeat every 30 seconds.
- Camera daemon starts first, then sentry and ACC sentry are delayed behind it.

## Main Components

### `BladeWatchApplication`

Initializes global app concerns:

- Locale and theme.
- Logging.
- Preferences manager.
- Foreground keepalive service.

### `MainActivity`

Owns the Android shell:

- Material navigation rail (Material 3 — see [UI/UX Design Language](ui-ux-design-language.md)).
- Native fragment navigation via `nav_graph.xml`.
- Storage setup.
- Device ID initialization.
- BYD whitelist application.
- Daemon startup manager initialization.
- Location sidecar startup.
- Update checks.
- Post-update daemon reset behavior.
- Status overlay startup.

### `DaemonStartupManager`

Coordinates daemon launch, optional Zrok tunnel launch, health checks, and user-stopped daemon state. It treats camera, sentry, and ACC sentry as core daemons and treats Zrok as the optional tunnel daemon.

### `AdbDaemonLauncher`

Facade over daemon and tunnel launchers. It starts camera, sentry, ACC sentry, and Zrok through shell execution.

### `DaemonBootstrap`

The bootstrap entrypoint used by shell-launched Java daemons. It creates an Android context from low-level framework classes, hardcodes the package name, grants or bypasses permissions where possible, and invokes daemon main code.

### `CameraDaemon`

The central long-running daemon. It starts local command and web servers, initializes the camera/GPU pipeline, config, auth, storage, telemetry, trip analytics, BYD collection, Web Push notifications, and surveillance IPC.

### `HttpServer`

Embedded HTTP server. It extracts and serves the Angular SPA from
`/data/local/tmp/web/angular` (`index.html` at `/`, hashed chunks under
`/assets/` and `/vendor/`, with an SPA fallback that serves `index.html` for
unrecognised paths so the Angular router resolves them client-side), dispatches
ConnectRPC calls under `/bladewatch.v1.<Service>/<Method>` via `ConnectDispatcher`,
and still exposes the inline REST/camera APIs, auth endpoints, thumbnail/video
serving, i18n catalogs, update APIs, and WebSocket live streaming. The legacy
static pages remain available under `/legacy/` for regression testing.

### `GpuSurveillancePipeline`

Coordinates panoramic camera input, GPU scaling, recording, AI lane processing, surveillance state, adaptive bitrate, telemetry overlay, and streaming.

### `BydDataCollector`

The main local BYD telemetry collector. It discovers BYD framework devices through reflection, reads initial values, registers listeners, and maintains a thread-safe vehicle snapshot.

## Design Patterns

- Reflection is used heavily for BYD local APIs so the app can compile with stubs but run against the vehicle firmware classes.
- Shared JSON files under `/data/local/tmp` are used for cross-process config and secrets.
- Daemons expose local TCP/HTTP IPC rather than relying on Activity-bound Android services.
- The embedded web UI is an Angular 19 SPA that talks to the daemon over ConnectRPC; the in-app UI is native fragments, so the SPA primarily serves remote browser / tunnel clients.
- The retained `WebViewFragment` host bypasses proxy issues by injecting a bridge for mutating API calls while allowing normal GET navigation (used only on the tunnel/browser path).
- Optional remote access is layered over the local web server through the Zrok tunnel instead of exposing internet-facing server code directly.
- Surveillance and camera paths prioritize long-running stability over tight coupling with Android UI lifecycle.

## Major Risk Areas

- The app relies on privileged shell behavior, BYD firmware APIs, and Android head-unit quirks.
- `/data/local/tmp` config must be protected carefully because multiple processes use it.
- LAN mode exposes the embedded web server on all interfaces and must remain opt-in.
- Tunnel URLs are only safe when paired with token auth.
- BYD local API listener behavior can crash certain firmware paths, so some listeners are intentionally skipped or isolated.

## Source References

- Application startup: [BladeWatchApplication.kt:18](../app/src/main/java/com/loabletech/bladewatch/BladeWatchApplication.kt#L18), [MainActivity.kt:46](../app/src/main/java/com/loabletech/bladewatch/ui/MainActivity.kt#L46).
- Boot and foreground survival: [BootReceiver.kt:24](../app/src/main/java/com/loabletech/bladewatch/receiver/BootReceiver.kt#L24), [DaemonKeepaliveService.kt:30](../app/src/main/java/com/loabletech/bladewatch/services/DaemonKeepaliveService.kt#L30).
- Daemon orchestration and shell launch: [DaemonStartupManager.kt:15](../app/src/main/java/com/loabletech/bladewatch/ui/daemon/DaemonStartupManager.kt#L15), [AdbDaemonLauncher.kt:17](../app/src/main/java/com/loabletech/bladewatch/launcher/AdbDaemonLauncher.kt#L17), [DaemonBootstrap.java:22](../app/src/main/java/com/loabletech/bladewatch/daemon/DaemonBootstrap.java#L22).
- Camera daemon and local servers: [CameraDaemon.java:35](../app/src/main/java/com/loabletech/bladewatch/daemon/CameraDaemon.java#L35), [TcpCommandServer.java:22](../app/src/main/java/com/loabletech/bladewatch/server/TcpCommandServer.java#L22), [HttpServer.java:49](../app/src/main/java/com/loabletech/bladewatch/server/HttpServer.java#L49), [SurveillanceIpcServer.java:22](../app/src/main/java/com/loabletech/bladewatch/server/SurveillanceIpcServer.java#L22).
- Angular SPA serving and Connect dispatch: [HttpServer.java:426](../app/src/main/java/com/loabletech/bladewatch/server/HttpServer.java#L426) (SPA static assets), [HttpServer.java:547](../app/src/main/java/com/loabletech/bladewatch/server/HttpServer.java#L547) (SPA fallback), [HttpServer.java:568](../app/src/main/java/com/loabletech/bladewatch/server/HttpServer.java#L568) (Connect route), [ConnectDispatcher.java:36](../app/src/main/java/com/loabletech/bladewatch/server/connect/ConnectDispatcher.java#L36).
- Angular web UI build/copy: [build.gradle.kts:497](../app/build.gradle.kts#L497) (`buildAngularWebUI`), [web/package.json](../web/package.json), [web/vite.config.ts](../web/vite.config.ts), [web/src/app/app.config.ts](../web/src/app/app.config.ts), [web/src/app/core/connect/connect-clients.ts](../web/src/app/core/connect/connect-clients.ts).
- GPU surveillance and recording stack: [GpuSurveillancePipeline.java:24](../app/src/main/java/com/loabletech/bladewatch/surveillance/GpuSurveillancePipeline.java#L24), [PanoramicCameraGpu.java:39](../app/src/main/java/com/loabletech/bladewatch/camera/PanoramicCameraGpu.java#L39), [GpuMosaicRecorder.java:31](../app/src/main/java/com/loabletech/bladewatch/surveillance/GpuMosaicRecorder.java#L31), [HardwareEventRecorderGpu.java:58](../app/src/main/java/com/loabletech/bladewatch/surveillance/HardwareEventRecorderGpu.java#L58).
- BYD local integration: [BydDataCollector.java:20](../app/src/main/java/com/loabletech/bladewatch/byd/BydDataCollector.java#L20).
- Build and native boundaries: [build.gradle.kts:276](../app/build.gradle.kts#L276), [build.gradle.kts:413](../app/build.gradle.kts#L413), [CMakeLists.txt:50](../app/src/main/cpp/CMakeLists.txt#L50).
