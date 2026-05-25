# Overdrive Documentation

This directory is the project reference for the Overdrive Android app, its native daemons, embedded web UI, BYD integrations, tunnels, APIs, and operational workflows.

Overdrive is an Android application for BYD DiLink vehicles. The app coordinates Android UI screens, foreground services, privileged shell-launched daemons, camera and surveillance pipelines, local and remote web access, BYD vehicle telemetry, BYD cloud control, trip analytics, notifications, MQTT, and tunnel/proxy processes.

## Document Map

- [Architecture](architecture.md) describes the major modules, runtime boundaries, startup lifecycle, and component relationships.
- [Features](features.md) catalogs the user-facing and system-facing features implemented by the app.
- [Data Flow and Storage](data-flow-and-storage.md) explains where data comes from, how it moves between components, and where it is persisted.
- [Daemons and Processes](daemons-and-processes.md) documents Android components, app-process daemons, watchdogs, foreground services, and local IPC ports.
- [Networking and Tunnels](networking-and-tunnels.md) covers HTTP, WebSocket streaming, auth, LAN mode, Cloudflared, Zrok, Tailscale, sing-box, MQTT, and proxy behavior.
- [HTTP API Reference](http-api-reference.md) lists the embedded web API route families and known endpoints.
- [BYD Integrations](byd-integrations.md) explains local BYD hardware APIs, compile-time stubs, telemetry collection, cloud APIs, MQTT cloud updates, and remote vehicle controls.
- [Build and Operations](build-and-operations.md) covers build inputs, native dependencies, assets, tests, updates, issue tracking, and release/session procedures.

## Source Areas

- `app/src/main/java/com/overdrive/app/` contains Android app code, daemons, local servers, BYD integrations, telemetry, storage, and UI fragments.
- `app/src/main/assets/web/` contains the local web app and PWA assets served by the camera daemon.
- `app/src/main/assets/models/` contains AI model assets used by surveillance.
- `app/src/main/cpp/` contains native camera, surveillance, and OpenCV/OpenH264 build integration.
- `app/build.gradle.kts` defines Android, Kotlin, CMake, embedded native downloads, and asset extraction tasks.
- `docs/security-smoke-test.md` documents the security smoke-test plan that existed before this documentation set.

## Important Defaults

- Local daemon command TCP: `127.0.0.1:19876`.
- Surveillance IPC TCP: `127.0.0.1:19877`.
- Embedded web server: `127.0.0.1:8080` by default, or `0.0.0.0:8080` only when LAN HTTP is explicitly enabled.
- sing-box mixed proxy: `127.0.0.1:8119`.
- Tailscale userspace socket: `127.0.0.1:8532`.
- Tailscale SOCKS5 proxy: `127.0.0.1:8539`.
- Main shared config: `/data/local/tmp/overdrive_config.json`.
- Shared daemon secret store: `/data/local/tmp/overdrive_secrets.json`.
- Media base directory: `/storage/emulated/0/Overdrive`.

## Security Notes

The embedded web UI is token-protected in release builds, including loopback access. LAN HTTP is disabled by default. Tunnel URLs and auth tokens should be treated as secrets. Secret values embedded in local config, generated proxy config, BYD cloud credentials, MQTT credentials, tunnel tokens, and device auth secrets must not be copied into documentation or logs.
