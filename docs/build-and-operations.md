# Build and Operations

This file documents build inputs, native dependencies, tests, assets, updates, and repository operations.

## Project Layout

```text
settings.gradle.kts
gradle/libs.versions.toml
app/build.gradle.kts
app/src/main/AndroidManifest.xml
app/src/main/java/com/loabletech/bladewatch/
app/src/main/assets/
app/src/main/cpp/
proto/                       # buf workspace (.proto contracts + buf.gen.yaml)
web/                         # Angular 19 + Vite web UI (ConnectRPC client)
docs/
```

Gradle is a single Android module named `:app`. There is no root `build.gradle.kts`; all build logic, native-download tasks, and codegen/web tasks live in `app/build.gradle.kts`, with plugin and dependency versions pinned in `gradle/libs.versions.toml`.

The repository also contains two non-Gradle build inputs that feed the Android build:

- `proto/` — a buf v2 workspace holding the `bladewatch.v1` API contracts. `buf generate` produces Java protobuf message classes and Kotlin ConnectRPC service stubs into `app/src/main/java`, and TypeScript message classes into `web/src/gen`.
- `web/` — an Angular 19 single-page app built with Vite (`@analogjs/vite-plugin-angular`). Its build output is copied into the APK under `app/src/main/assets/web/angular/`.

## Toolchain

Important build settings:

- Android Gradle Plugin: `8.13.2`.
- Kotlin: `2.0.21`.
- Compile SDK: `36`.
- Minimum SDK: `25`.
- Target SDK: `25`.
- NDK: `26.1.10909125`.
- Java and Kotlin target: `11`.
- `applicationId` / `namespace`: `net.bladewatch.app` (the source package is `com.loabletech.bladewatch`).
- Version: `versionName = "1.0.0.0"`, `versionCode = 10000`.
- ABI split: `arm64-v8a` only, no universal APK. The debug output is `app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`.
- Native build: CMake `3.22.1`, `-std=c++17`.

Key dependency families:

- AndroidX core, appcompat, lifecycle, navigation, WorkManager.
- Material Components.
- Dadb (ADB client for daemon launching).
- OkHttp (used by the OTA updater).
- TensorFlow Lite, GPU delegate, GPU API, and support.
- Java-WebSocket (zero-latency H.264 streaming).
- Android security crypto.
- H2 database (pure-Java embedded trip storage).
- RTMP client (RootEncoder) for pushing to MediaMTX.
- ZXing core (QR generation).
- osmdroid (native Location-screen map tiles).
- Protobuf-java and ConnectRPC Kotlin runtime + OkHttp transport + Google-Java JSON ext.

The Vehicle hero renders via Three.js inside an embedded WebView (`app/src/main/assets/web/hero/hero.html`). A native Filament port was tried and removed — the BYD head unit's Adreno 610 GL driver crashes under continuous gltfio rendering, so Filament must not be reintroduced for the hero.

The web app (`web/`) pins Angular 19, Vite 6, ConnectRPC (`@connectrpc/connect` / `connect-web`), `@bufbuild/protobuf`, Leaflet (Location + Trips maps), `@ngx-translate` (i18n), and `qrcode`. Playwright is the e2e harness.

## Native Dependencies

Two Gradle tasks auto-download and verify native dependencies, and every `*CMake*` / `*ExternalNative*` task depends on both, so no manual download step is needed:

- `downloadOpenH264` — fetches Cisco's official OpenH264 `2.6.0` arm64 binary and the matching API headers.
- `downloadOpenCV` — fetches opencv-mobile `4.10.0` (tag `v31`) and copies the arm64-v8a static libs + headers.

Artifacts are verified with SHA-256 before use; a mismatch fails the build, and a changed checksum triggers a redownload. Native outputs are integrated through CMake. The OpenH264 `.so` directory is added to `jniLibs.srcDirs`.

TensorFlow Lite (runtime, GPU delegate, GPU API, support) is a normal Maven dependency, not a downloaded artifact.

Native source areas:

- `app/src/main/cpp/camera/`.
- `app/src/main/cpp/surveillance/`.
- `app/src/main/cpp/CMakeLists.txt`.

The Zrok tunnel binary is packaged as `libzrok.so` in `jniLibs/` and extracted at runtime.

## Embedded Assets

Important asset groups:

- Web UI under `app/src/main/assets/web/`. This contains:
  - `angular/` — the built Angular SPA (output of the `buildAngularWebUI` task).
  - `hero/hero.html` — the Three.js Vehicle hero loaded in an embedded WebView.
  - `i18n/` — the Angular client translation bundles (17 locales).
  - `shared/`, `local/`, `web/` — the legacy hand-written web UI assets.
- Server-side i18n under `app/src/main/assets/server-i18n/` (17 locales, used by the daemon for push/notification text).
- AI models under `app/src/main/assets/models/` (e.g. `yolo11n.tflite`).
- Zrok native binary packaged as a library in `jniLibs/`.

Runtime extraction paths:

- `/data/local/tmp/web`.
- `/data/local/tmp/overlay`.

The Gradle task `extractWebAssets` walks `app/src/main/assets/web/` and pushes every file to `/data/local/tmp/web` on the connected device for development iteration.

## Web UI and Proto Build Pipeline

The APK embeds an Angular 19 web app whose build is wired into the Gradle build:

- `buildAngularWebUI` — runs `npm run build` (Vite) in `web/`, then copies `web/dist` into `app/src/main/assets/web/angular/`. It is hooked into `preBuild`, so the Angular UI is compiled before any variant packages its assets. The task is gated by `onlyIf { npm --version succeeds }`: if Node/npm is not on `PATH`, the Angular build is skipped and whatever assets already sit in `app/src/main/assets/web/angular/` are packaged instead.
- `generateConnectProtos` — runs `buf generate` in `proto/`. This regenerates Java protobuf classes + Kotlin ConnectRPC stubs into `app/src/main/java` and TypeScript message classes into `web/src/gen`. Generated files are committed, so this task is optional and only needs to be run when a `.proto` changes. The web app exposes the same step as `npm run generate`.

The proto contracts live in `proto/bladewatch/v1/` and define 12 ConnectRPC services: Auth, Notifications, Recordings, SafeLocations, Settings, Storage, Stream, Surveillance, System, Trips, Update, and Vehicle.

Local web development can run the Vite dev server (`cd web && npm run dev`), which proxies `/bladewatch.v1`, `/api`, `/status`, and `/auth` to the daemon at `http://127.0.0.1:8080`.

## Build Commands

Common local commands:

```bash
./gradlew assembleDebug            # debug APK (runs preBuild → buildAngularWebUI first)
./gradlew assembleRelease          # release APK (needs signing env vars)
./gradlew test                     # Android JVM unit tests
./gradlew :app:extractWebAssets    # push web assets to /data/local/tmp/web
./gradlew generateConnectProtos    # regenerate Java/Kotlin/TS stubs from proto/
./gradlew buildAngularWebUI        # build the Angular SPA and copy it into assets
```

Web app (Angular) commands, run from `web/`:

```bash
npm install
npm run dev            # Vite dev server (proxies API to 127.0.0.1:8080)
npm run build          # production build into web/dist
npm run generate       # buf generate (same as ./gradlew generateConnectProtos)
npm run test:e2e       # Playwright e2e against a live device
```

On this repository, shell commands should be prefixed with `rtk` according to the local agent instructions:

```bash
rtk ./gradlew test
```

On PowerShell, invoking the wrapper may require:

```powershell
rtk powershell -NoProfile -Command ".\gradlew.bat test"
```

## Signing

Release signing is configured through Gradle and environment variables. Do not commit keystores, passwords, or signing config secrets.

## Tests

### Android unit tests

JVM unit tests live in `app/src/test/java/com/loabletech/bladewatch/` and run with `./gradlew test`:

- Auth: `AuthMiddlewareTest`, `AuthManagerTest`.
- Config / secrets: `SecretConfigStoreTest`, `SecretRedactorTest`.
- Connect server: `ConnectContentTypeNegotiationTest`, `ConnectWireParityTest`, `SurveillanceConfigTogglesTest`.
- Server handlers: `LightsAdasParserTest`, `ModelsApiHandlerValidationTest`.
- Vehicle: `TyreTierTest`, `VehicleClientCommandResultTest`, `VehicleClientMapTest`, `VehicleClientTyreMapTest`, `VehicleFormattersTest`, `VehicleI18nParityTest`.

Run a single class, e.g.:

```bash
./gradlew test --tests "com.loabletech.bladewatch.auth.AuthManagerTest"
```

### Web e2e tests

The Angular app has a Playwright suite under `web/e2e/` (`login.spec.ts`, `navigation.spec.ts`, `regression.spec.ts`, plus an `auth.setup.ts` that logs in once and persists the session). The suite targets a single live device over a tunnel: it runs serially (one worker) with retries, and reads its base URL + access code from a gitignored `web/e2e/.env` (see `e2e/.env.example`). Run with `cd web && npm run test:e2e`.

### Recommended checks after code changes

```bash
./gradlew test
./gradlew assembleDebug
```

For documentation-only changes, a full build may still be useful if build scripts or generated docs depend on source paths, but it is not strictly required to validate Markdown content.

## Update Flow

Update APIs support:

- Check.
- Preview.
- Confirmed install.
- Progress reporting.

Android package replacement is handled carefully:

1. `BootReceiver` receives package replacement.
2. It launches `MainActivity` with a post-update flag.
3. Daemon direct startup is skipped from the receiver.
4. Main app flow performs post-update reset and relaunch behavior.

This avoids stale daemon processes surviving an update in an inconsistent state.

## Issue Tracking

The project uses `bd` or beads for issue tracking.

Common commands:

```bash
bd ready --json
bd show <id> --json
bd update <id> --claim --json
bd close <id> --reason "Completed" --json
bd sync
```

Use beads for task tracking instead of Markdown TODOs or external issue lists.

## Session Completion Procedure

Project instructions require a completed session to:

1. File issues for remaining work.
2. Run quality gates if code changed.
3. Update issue status.
4. Pull and rebase.
5. Run `bd sync`.
6. Push to remote.
7. Verify `git status` is up to date with origin.
8. Hand off remaining context.

## Operational Files and Logs

Important runtime files:

- `/storage/emulated/0/BladeWatch/data/bladewatch_config.json` (persistent config; mirrored to `/data/local/tmp/bladewatch_config.json`).
- `/storage/emulated/0/BladeWatch/data/bladewatch_trips_h2.mv.db` (persistent trip database).
- `/data/local/tmp/bladewatch_secrets.json`.
- `/data/local/tmp/zrok.log`.
- `/storage/emulated/0/BladeWatch`.

Runtime files can contain secrets, tokens, tunnel URLs, or vehicle data. Treat pulled logs and configs as sensitive.

## Deployment Risks

- BYD firmware APIs can vary by region, model, and OTA version.
- Native camera and GPU behavior can vary across devices.
- Tunnel credentials are sensitive.
- LAN HTTP exposure is opt-in and should remain off by default.
- Vehicle control APIs can affect the physical car and should be tested conservatively. Cloud-backed actions (lock, unlock, flash, find-car, battery-heat, charging-schedule) are not supported and will return an error.

## Documentation Maintenance

When changing route handlers, daemon ports, config paths, startup timing, tunnel behavior, or storage paths, update the relevant file in `docs/`.

Suggested mapping:

- Runtime or lifecycle change: `architecture.md` and `daemons-and-processes.md`.
- HTTP route change: `http-api-reference.md`.
- Tunnel/network change: `networking-and-tunnels.md`.
- BYD local change: `byd-integrations.md`.
- Storage/config/media change: `data-flow-and-storage.md`.
- User-facing capability change: `features.md`.

## Source References

- Gradle namespace, SDK, version, ABI split, and signing: [build.gradle.kts:264](../app/build.gradle.kts#L264), [build.gradle.kts:268](../app/build.gradle.kts#L268), [build.gradle.kts:345](../app/build.gradle.kts#L345), [build.gradle.kts:256](../app/build.gradle.kts#L256).
- Dependencies (TFLite, ConnectRPC, osmdroid, H2, RTMP) and Filament-removed note: [build.gradle.kts:412](../app/build.gradle.kts#L412), [build.gradle.kts:444](../app/build.gradle.kts#L444), [build.gradle.kts:465](../app/build.gradle.kts#L465).
- Verified native downloads and asset extraction tasks: [build.gradle.kts:72](../app/build.gradle.kts#L72), [build.gradle.kts:124](../app/build.gradle.kts#L124), [build.gradle.kts:138](../app/build.gradle.kts#L138), [build.gradle.kts:226](../app/build.gradle.kts#L226).
- Angular web build and proto codegen tasks: [build.gradle.kts:487](../app/build.gradle.kts#L487), [build.gradle.kts:497](../app/build.gradle.kts#L497), [build.gradle.kts:520](../app/build.gradle.kts#L520), [buf.gen.yaml:1](../proto/buf.gen.yaml#L1), [package.json:5](../web/package.json#L5), [playwright.config.ts:17](../web/playwright.config.ts#L17).
- Plugin and library versions: [libs.versions.toml:1](../gradle/libs.versions.toml#L1).
- BYD stub compile/runtime behavior: [build.gradle.kts:413](../app/build.gradle.kts#L413), [IAccModeManager.java:5](../app/src/main/java/android/os/IAccModeManager.java#L5).
- Native build and hardening: [CMakeLists.txt:50](../app/src/main/cpp/CMakeLists.txt#L50), [CMakeLists.txt:98](../app/src/main/cpp/CMakeLists.txt#L98).
- Update APIs and post-update daemon reset: [UpdateApiHandler.java:43](../app/src/main/java/com/loabletech/bladewatch/server/UpdateApiHandler.java#L43), [BootReceiver.kt:24](../app/src/main/java/com/loabletech/bladewatch/receiver/BootReceiver.kt#L24), [DaemonStartupManager.kt:15](../app/src/main/java/com/loabletech/bladewatch/ui/daemon/DaemonStartupManager.kt#L15).
- Operational files, logs, config, and storage: [UnifiedConfigManager.kt:30](../app/src/main/java/com/loabletech/bladewatch/config/UnifiedConfigManager.kt#L30), [SecretConfigStore.kt:22](../app/src/main/java/com/loabletech/bladewatch/config/SecretConfigStore.kt#L22), [StorageManager.java:100](../app/src/main/java/com/loabletech/bladewatch/storage/StorageManager.java#L100), [DaemonLogger.java:382](../app/src/main/java/com/loabletech/bladewatch/logging/DaemonLogger.java#L382).
