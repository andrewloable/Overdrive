# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**BladeWatch** is an advanced sentry mode / dashcam Android app for BYD vehicles with DiLink v3. It targets `arm64-v8a` only (BYD head units), runs on Android 10+ (API 29+), and deploys to the car's head unit via ADB.

This project was forked from "Overdrive" and rebranded to BladeWatch (package `com.loabletech.bladewatch`). The legacy BladeWatch app is kept at `/Volumes/mandark-1Tb/projects/loabletech/BladeWatch-Legacy` for reference only — do not modify it.

## Git Workflow

Do **not** run `git add`, `git commit`, or `git push` automatically. All commits are reviewed and made manually by the developer. Make code changes and stop — do not stage or commit them.

## Build Commands

```bash
# Debug build
./gradlew assembleDebug

# Release build (requires env vars: KEYSTORE_FILE, KEYSTORE_PASSWORD, KEY_PASSWORD, KEY_ALIAS)
./gradlew assembleRelease

# Run unit tests
./gradlew test

# Push web assets to connected device for development iteration
./gradlew :app:extractWebAssets
```

Native dependencies (OpenH264, opencv-mobile) are auto-downloaded by Gradle before any CMake/ExternalNative task. No manual download step needed.

## Architecture

BladeWatch is a hybrid Android + shell-daemon + embedded web app. The critical design split:

**Android app process** — UI shell only: `BladeWatchApplication`, `MainActivity`, fragments, WebView wrapper, boot/power receivers, `DaemonKeepaliveService`, `DaemonStartupManager`.

**Shell-launched daemon processes** — launched via `app_process` ADB shell, run outside Activity lifecycle:
- `CameraDaemon` — the central long-running process. Owns the camera/GPU pipeline, H.264/H.265 recording, WebSocket live streaming, HTTP API server (`127.0.0.1:8080`), TCP command server (`127.0.0.1:19876`), surveillance IPC server (`127.0.0.1:19877`), telemetry, trips, BYD cloud, MQTT.
- `SentryDaemon` / `AccSentryDaemon` — surveillance orchestration.
- Tunnel daemons — Zrok, Cloudflared, Tailscale (native `.so` binaries in `jniLibs/`).
- Telegram daemon, sing-box proxy daemon.

**Embedded web UI** — static JS/CSS/HTML under `app/src/main/assets/web/`, extracted to `/data/local/tmp/web` at runtime. Talks to CameraDaemon over HTTP. WebView injects auth cookies and a JS bridge for mutating calls to avoid proxy interference.

**BYD integrations** — local firmware APIs accessed via reflection (stubs in `android.hardware.*` and `android.os.*` compile against stubs; real classes loaded at runtime from boot classloader). BYD cloud via HTTPS + MQTT v5 with Bangcle white-box AES encryption.

### Startup Timing

Daemon launch is intentionally staggered to let the head unit settle: core daemons start ~45s after boot, optional daemons ~60s, health checks begin ~90s repeating every 30s. Camera daemon starts first; sentry daemons start behind it.

### Cross-Process Coordination

All cross-process config and secrets live under `/data/local/tmp`:
- Config: `/data/local/tmp/bladewatch_config.json` (owned by `UnifiedConfigManager`)
- Secrets: `/data/local/tmp/bladewatch_secrets.json` (owned by `SecretConfigStore`)
- Media: `/storage/emulated/0/BladeWatch/{recordings,surveillance,proximity,trips}`

The Android app cannot write secrets directly when running as app UID — it uses the TCP command bridge to have the daemon (shell UID) write them.

### Native Code

C++17 sources in `app/src/main/cpp/`:
- `camera/` — `HardwareBufferTextureBinder` (GPU texture binding)
- `surveillance/` — `motion_pipeline_v2`, `texture_tracker`, `native_motion` (OpenCV-based motion detection)
- CMakeLists.txt links OpenH264, opencv-mobile, and TensorFlow Lite GPU delegate

### Surveillance Pipeline

```
Camera frame → GPU downscale → native motion pipeline → per-quadrant state
  → optional TFLite YOLO11n gate → event decision
  → event recording + Telegram notification + optional BYD cloud deterrent
```

## Key Source Locations

- App entry: [BladeWatchApplication.kt](app/src/main/java/com/loabletech/bladewatch/BladeWatchApplication.kt), [MainActivity.kt](app/src/main/java/com/loabletech/bladewatch/ui/MainActivity.kt)
- Daemon launch: [DaemonStartupManager.kt](app/src/main/java/com/loabletech/bladewatch/ui/daemon/DaemonStartupManager.kt), [AdbDaemonLauncher.kt](app/src/main/java/com/loabletech/bladewatch/launcher/AdbDaemonLauncher.kt), [DaemonBootstrap.java](app/src/main/java/com/loabletech/bladewatch/daemon/DaemonBootstrap.java)
- Central daemon: [CameraDaemon.java](app/src/main/java/com/loabletech/bladewatch/daemon/CameraDaemon.java)
- HTTP server: [HttpServer.java](app/src/main/java/com/loabletech/bladewatch/server/HttpServer.java)
- Auth: [AuthManager.java](app/src/main/java/com/loabletech/bladewatch/auth/AuthManager.java), [AuthMiddleware.java](app/src/main/java/com/loabletech/bladewatch/server/AuthMiddleware.java)
- GPU pipeline: [GpuSurveillancePipeline.java](app/src/main/java/com/loabletech/bladewatch/surveillance/GpuSurveillancePipeline.java), [PanoramicCameraGpu.java](app/src/main/java/com/loabletech/bladewatch/camera/PanoramicCameraGpu.java)
- BYD local: [BydDataCollector.java](app/src/main/java/com/loabletech/bladewatch/byd/BydDataCollector.java)
- BYD cloud: [BydCloudClient.java](app/src/main/java/com/loabletech/bladewatch/byd/cloud/BydCloudClient.java), [BydCloudMqttSubscriber.java](app/src/main/java/com/loabletech/bladewatch/byd/cloud/BydCloudMqttSubscriber.java)
- Config: [UnifiedConfigManager.kt](app/src/main/java/com/loabletech/bladewatch/config/UnifiedConfigManager.kt), [SecretConfigStore.kt](app/src/main/java/com/loabletech/bladewatch/config/SecretConfigStore.kt)
- Web UI assets: [app/src/main/assets/web/](app/src/main/assets/web/)

## BYD SDK Stub Pattern

Classes in `android.hardware.*` and `android.os.*` are **compile-time stubs only**. At runtime on BYD devices, the real SDK classes are loaded by the boot classloader (higher priority). All manager code uses `Class.forName()` reflection — the stubs in the APK are never instantiated. Do not change this pattern.

## Logging

`DaemonLogConfig.java` controls log verbosity. The release build Gradle script auto-detects if any logging flags are `true` — if so, `proguard-rules-strip-logs.pro` is excluded and log calls survive R8. In production (all flags false), R8 strips all log calls from bytecode. Do not enable logging flags in commits intended for release.

## Testing

Unit tests are in `app/src/test/java/com/loabletech/bladewatch/`:
- `AuthMiddlewareTest`, `AuthManagerTest` — auth behavior
- `SecretConfigStoreTest` — secret storage
- `SecretRedactorTest` — log redaction

Run a single test class: `./gradlew test --tests "com.loabletech.bladewatch.auth.AuthManagerTest"`

## Shell Command Safety

Always use non-interactive flags for file operations — some shells alias `cp`/`mv`/`rm` to interactive mode which will hang:

```bash
cp -f source dest      # not: cp source dest
mv -f source dest      # not: mv source dest
rm -f file             # not: rm file
rm -rf directory       # not: rm -r directory
```

## Issue Tracking

Use `bd` (beads) for all task tracking. See `AGENTS.md` for the full workflow. Never use markdown TODOs or other tracking systems.

## Documentation Maintenance

When changing route handlers, daemon ports, config paths, startup timing, tunnel behavior, BYD cloud behavior, or storage paths, update the relevant file in `docs/`:
- Runtime/lifecycle changes → `architecture.md`, `daemons-and-processes.md`
- HTTP route changes → `http-api-reference.md`
- Tunnel/proxy/network changes → `networking-and-tunnels.md`
- BYD local or cloud changes → `byd-integrations.md`
- Storage/config/media changes → `data-flow-and-storage.md`
- User-facing changes → `features.md`

## Security Notes

- `/data/local/tmp/bladewatch_secrets.json` contains device tokens, tunnel tokens, cloud credentials. Never log or copy these values.
- LAN HTTP (`http://<car-ip>:8080`) is disabled by default and must remain opt-in. The server binds to `127.0.0.1` by default.
- Tunnel URLs are only safe when paired with JWT token auth.
- BYD cloud vehicle control APIs affect the physical car — test conservatively.
- VLESS proxy credentials use encrypted `Safe.s("...")` values — use `generate_safe_enc.py` to encrypt before committing.
