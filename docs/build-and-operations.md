# Build and Operations

This file documents build inputs, native dependencies, tests, assets, updates, and repository operations.

## Project Layout

```text
settings.gradle.kts
build.gradle.kts
gradle/libs.versions.toml
app/build.gradle.kts
app/src/main/AndroidManifest.xml
app/src/main/java/com/overdrive/app/
app/src/main/assets/
app/src/main/cpp/
docs/
```

The project is a single Android module named `:app`.

## Toolchain

Important build settings:

- Android Gradle Plugin: `8.13.2`.
- Kotlin: `2.0.21`.
- Compile SDK: `36`.
- Minimum SDK: `25`.
- Target SDK: `25`.
- Java and Kotlin target: `11`.
- ABI split: `arm64-v8a`.
- Native build: CMake.

Key dependency families:

- AndroidX core, appcompat, lifecycle, navigation, WorkManager.
- Material Components.
- Dadb.
- OkHttp.
- Eclipse Paho MQTT v3 and v5.
- TensorFlow Lite and GPU delegate.
- WebSocket support.
- Android security crypto.
- H2 database.
- RTMP client.

## Native Dependencies

Gradle tasks download and verify native dependencies:

- OpenH264.
- opencv-mobile.

Artifacts are verified with SHA-256 before use. Native outputs are integrated through CMake and copied into JNI library paths where needed.

Native source areas:

- `app/src/main/cpp/camera/`.
- `app/src/main/cpp/surveillance/`.
- `app/src/main/cpp/CMakeLists.txt`.

## Embedded Assets

Important asset groups:

- Web UI under `app/src/main/assets/web/`.
- AI models under `app/src/main/assets/models/`.
- Tunnel/proxy native binaries packaged as libraries.
- Bangcle tables used by BYD cloud crypto.

Runtime extraction paths:

- `/data/local/tmp/web`.
- `/data/local/tmp/overlay`.
- `/data/local/tmp/bangcle_tables.bin`.

The Gradle task `extractWebAssets` can push web assets to `/data/local/tmp/web` for development.

## Build Commands

Common local commands:

```bash
./gradlew assembleDebug
./gradlew assembleRelease
./gradlew test
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

Existing test areas include:

- `AuthMiddlewareTest`.
- `AuthManagerTest`.
- `SecretConfigStoreTest`.
- `SecretRedactorTest`.

Recommended checks after code changes:

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

- `/data/local/tmp/overdrive_config.json`.
- `/data/local/tmp/overdrive_secrets.json`.
- `/data/local/tmp/cloudflared.log`.
- `/data/local/tmp/zrok.log`.
- `/data/local/tmp/singbox.log`.
- `/storage/emulated/0/Overdrive`.

Runtime files can contain secrets, tokens, tunnel URLs, or vehicle data. Treat pulled logs and configs as sensitive.

## Deployment Risks

- BYD firmware APIs can vary by region, model, and OTA version.
- Native camera and GPU behavior can vary across devices.
- BYD cloud API behavior can change without repo changes.
- Tunnel and proxy credentials are sensitive.
- LAN HTTP exposure is opt-in and should remain off by default.
- Vehicle control APIs can affect the physical car and should be tested conservatively.

## Documentation Maintenance

When changing route handlers, daemon ports, config paths, startup timing, tunnel behavior, BYD cloud behavior, or storage paths, update the relevant file in `docs/`.

Suggested mapping:

- Runtime or lifecycle change: `architecture.md` and `daemons-and-processes.md`.
- HTTP route change: `http-api-reference.md`.
- Tunnel/proxy/network change: `networking-and-tunnels.md`.
- BYD local or cloud change: `byd-integrations.md`.
- Storage/config/media change: `data-flow-and-storage.md`.
- User-facing capability change: `features.md`.

## Source References

- Gradle module, SDK, namespace, ABI, and dependencies: [build.gradle.kts:276](../app/build.gradle.kts#L276), [build.gradle.kts:361](../app/build.gradle.kts#L361), [build.gradle.kts:452](../app/build.gradle.kts#L452), [build.gradle.kts:459](../app/build.gradle.kts#L459), [build.gradle.kts:481](../app/build.gradle.kts#L481).
- Verified native downloads and extraction tasks: [build.gradle.kts:8](../app/build.gradle.kts#L8), [build.gradle.kts:33](../app/build.gradle.kts#L33), [build.gradle.kts:63](../app/build.gradle.kts#L63), [build.gradle.kts:76](../app/build.gradle.kts#L76), [build.gradle.kts:225](../app/build.gradle.kts#L225), [build.gradle.kts:232](../app/build.gradle.kts#L232).
- BYD stub compile/runtime behavior: [build.gradle.kts:413](../app/build.gradle.kts#L413), [IAccModeManager.java:5](../app/src/main/java/android/os/IAccModeManager.java#L5).
- Native build and hardening: [CMakeLists.txt:50](../app/src/main/cpp/CMakeLists.txt#L50), [CMakeLists.txt:98](../app/src/main/cpp/CMakeLists.txt#L98).
- Update APIs and post-update daemon reset: [UpdateApiHandler.java:43](../app/src/main/java/com/overdrive/app/server/UpdateApiHandler.java#L43), [BootReceiver.kt:24](../app/src/main/java/com/overdrive/app/receiver/BootReceiver.kt#L24), [DaemonStartupManager.kt:15](../app/src/main/java/com/overdrive/app/ui/daemon/DaemonStartupManager.kt#L15).
- Operational files, logs, config, and storage: [UnifiedConfigManager.kt:30](../app/src/main/java/com/overdrive/app/config/UnifiedConfigManager.kt#L30), [SecretConfigStore.kt:22](../app/src/main/java/com/overdrive/app/config/SecretConfigStore.kt#L22), [StorageManager.java:100](../app/src/main/java/com/overdrive/app/storage/StorageManager.java#L100), [DaemonLogger.java:382](../app/src/main/java/com/overdrive/app/logging/DaemonLogger.java#L382).
