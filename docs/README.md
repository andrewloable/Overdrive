# BladeWatch Documentation

This directory is the project reference for the BladeWatch Android app, its native daemons, embedded web UI, BYD integrations, tunnels, APIs, and operational workflows.

BladeWatch is an Android application for BYD DiLink vehicles. The app coordinates Android UI screens, foreground services, privileged shell-launched daemons, camera and surveillance pipelines, local and remote web access, BYD vehicle telemetry, trip analytics, Web Push notifications, and the Zrok tunnel process.

## Document Map

- [Architecture](architecture.md) describes the major modules, runtime boundaries, startup lifecycle, and component relationships.
- [Features](features.md) catalogs the user-facing and system-facing features implemented by the app.
- [Data Flow and Storage](data-flow-and-storage.md) explains where data comes from, how it moves between components, and where it is persisted.
- [Daemons and Processes](daemons-and-processes.md) documents Android components, app-process daemons, watchdogs, foreground services, and local IPC ports.
- [IPC, Authentication & Secrets](ipc-auth-and-secrets.md) explains the app/daemon UID split, the IPC token bootstrap, the secret-fetch and JWT flows, the **required `/data/local/tmp` file permissions**, and the failure modes that surface as "Camera unavailable".
- [Networking and Tunnels](networking-and-tunnels.md) covers HTTP, WebSocket streaming, auth, LAN mode, Zrok, and remote access behavior.
- [HTTP API Reference](http-api-reference.md) lists the embedded web API route families and known endpoints.
- [BYD Integrations](byd-integrations.md) explains local BYD hardware APIs, compile-time stubs, telemetry collection, and local vehicle controls.
- [Surveillance Implementation](surveillance-implementation.md) documents sentry-mode activation, the GPU/native motion pipeline, AI confirmation, recording lifecycle, safe locations, schedules, APIs, and guardrails.
- [360 Camera Recording](360-camera-recording.md) explains how the shared 360 camera GPU/encoder stack records surveillance events and ACC-on driving clips.
- [Build and Operations](build-and-operations.md) covers build inputs, native dependencies, assets, tests, updates, issue tracking, and release/session procedures.

## Source Areas

- `app/src/main/java/com/loabletech/bladewatch/` contains Android app code, daemons, local servers, BYD integrations, telemetry, storage, and UI fragments.
- `app/src/main/assets/web/` contains the local web app and PWA assets served by the camera daemon.
- `app/src/main/assets/models/` contains AI model assets used by surveillance.
- `app/src/main/cpp/` contains native camera, surveillance, and OpenCV/OpenH264 build integration.
- `app/build.gradle.kts` defines Android, Kotlin, CMake, embedded native downloads, and asset extraction tasks.
- `docs/security-smoke-test.md` documents the security smoke-test plan that existed before this documentation set.

Each detailed document includes a `Source References` section. References use `filename:line` labels and GitHub-style line anchors so refactors can jump from documentation to the implementation point being described.

## Important Defaults

- Local daemon command TCP: `127.0.0.1:19876`.
- Surveillance IPC TCP: `127.0.0.1:19877`.
- Embedded web server: `127.0.0.1:8080` by default, or `0.0.0.0:8080` only when LAN HTTP is explicitly enabled.
- Main shared config: `/data/local/tmp/bladewatch_config.json`.
- Shared daemon secret store: `/data/local/tmp/bladewatch_secrets.json`.
- Media base directory: `/storage/emulated/0/BladeWatch`.

## Security Notes

The embedded web UI is token-protected in release builds, including loopback access. LAN HTTP is disabled by default. Tunnel URLs and auth tokens should be treated as secrets. Secret values embedded in local config, tunnel tokens, and device auth secrets must not be copied into documentation or logs.

## Source References

- Documentation map entry points: [CameraDaemon.java:35](../app/src/main/java/com/loabletech/bladewatch/daemon/CameraDaemon.java#L35), [HttpServer.java:49](../app/src/main/java/com/loabletech/bladewatch/server/HttpServer.java#L49), [GpuSurveillancePipeline.java:24](../app/src/main/java/com/loabletech/bladewatch/surveillance/GpuSurveillancePipeline.java#L24), [BydDataCollector.java:20](../app/src/main/java/com/loabletech/bladewatch/byd/BydDataCollector.java#L20), [StorageManager.java:100](../app/src/main/java/com/loabletech/bladewatch/storage/StorageManager.java#L100).
- Important defaults: [CameraDaemon.java:53](../app/src/main/java/com/loabletech/bladewatch/daemon/CameraDaemon.java#L53), [CameraDaemon.java:350](../app/src/main/java/com/loabletech/bladewatch/daemon/CameraDaemon.java#L350), [StorageManager.java:100](../app/src/main/java/com/loabletech/bladewatch/storage/StorageManager.java#L100).
- Auth and secret handling: [AuthManager.java:50](../app/src/main/java/com/loabletech/bladewatch/auth/AuthManager.java#L50), [AuthMiddleware.java:133](../app/src/main/java/com/loabletech/bladewatch/server/AuthMiddleware.java#L133), [SecretConfigStore.kt:22](../app/src/main/java/com/loabletech/bladewatch/config/SecretConfigStore.kt#L22), [UnifiedConfigManager.kt:559](../app/src/main/java/com/loabletech/bladewatch/config/UnifiedConfigManager.kt#L559).
