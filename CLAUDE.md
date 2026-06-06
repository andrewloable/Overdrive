# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**BladeWatch** is an advanced sentry mode / dashcam Android app for BYD vehicles with DiLink v3. It targets `arm64-v8a` only (BYD head units), runs on Android 10+ (API 29+), and deploys to the car's head unit via ADB.

This project was forked from "Overdrive" and rebranded to BladeWatch (package `com.loabletech.bladewatch`). The legacy BladeWatch app is kept at `/Volumes/mandark-1Tb/projects/loabletech/BladeWatch-Legacy` for reference only — do not modify it.

## Git Workflow

Do **not** run `git add`, `git commit`, or `git push` automatically. All commits are reviewed and made manually by the developer. Make code changes and stop — do not stage or commit them.

## Device Connection

The test device (BYD head unit) is at **192.168.0.251:5555** over ADB TCP.

```bash
# Connect to device
adb connect 192.168.0.251:5555

# Verify connection
adb -s 192.168.0.251:5555 devices

# Always target this device explicitly when multiple devices may be listed
adb -s 192.168.0.251:5555 <command>
```

Always pass `-s 192.168.0.251:5555` to every `adb` command to avoid ambiguity if a USB device is also attached.

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

# Install debug APK to device (ABI split produces arm64-v8a-specific filename)
adb -s 192.168.0.251:5555 install -r app/build/outputs/apk/debug/app-arm64-v8a-debug.apk

# View live logcat (filter to BladeWatch tags)
adb -s 192.168.0.251:5555 logcat -s BladeWatch:V CameraDaemon:V SentryDaemon:V AccSentryDaemon:V

# Push and extract web assets directly to device
adb -s 192.168.0.251:5555 shell mkdir -p /data/local/tmp/web
adb -s 192.168.0.251:5555 push app/src/main/assets/web/. /data/local/tmp/web/
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

When creating a `bd` task, write the `--description` for a **low-context implementing agent that has none of your current context**. Put everything needed into the description: problem/context, exact file paths + class/function names + line numbers, step-by-step instructions, constraints (what NOT to change), and **always** a verifiable **acceptance criteria** checklist. **Always end the description with a warning to not make mistakes** — i.e. "Do not make mistakes. Read the referenced files fully before editing. Verify the build compiles and all acceptance criteria pass before closing. If anything is ambiguous, stop and ask rather than guessing." See the "Writing Task Descriptions (CRITICAL)" section in `AGENTS.md` for the required template.

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

<!-- rtk-instructions v2 -->
# RTK (Rust Token Killer) - Token-Optimized Commands

## Golden Rule

**Always prefix commands with `rtk`**. If RTK has a dedicated filter, it uses it. If not, it passes through unchanged. This means RTK is always safe to use.

**Important**: Even in command chains with `&&`, use `rtk`:
```bash
# ❌ Wrong
git add . && git commit -m "msg" && git push

# ✅ Correct
rtk git add . && rtk git commit -m "msg" && rtk git push
```

## RTK Commands by Workflow

### Build & Compile (80-90% savings)
```bash
rtk cargo build         # Cargo build output
rtk cargo check         # Cargo check output
rtk cargo clippy        # Clippy warnings grouped by file (80%)
rtk tsc                 # TypeScript errors grouped by file/code (83%)
rtk lint                # ESLint/Biome violations grouped (84%)
rtk prettier --check    # Files needing format only (70%)
rtk next build          # Next.js build with route metrics (87%)
```

### Test (60-99% savings)
```bash
rtk cargo test          # Cargo test failures only (90%)
rtk go test             # Go test failures only (90%)
rtk jest                # Jest failures only (99.5%)
rtk vitest              # Vitest failures only (99.5%)
rtk playwright test     # Playwright failures only (94%)
rtk pytest              # Python test failures only (90%)
rtk rake test           # Ruby test failures only (90%)
rtk rspec               # RSpec test failures only (60%)
rtk test <cmd>          # Generic test wrapper - failures only
```

### Git (59-80% savings)
```bash
rtk git status          # Compact status
rtk git log             # Compact log (works with all git flags)
rtk git diff            # Compact diff (80%)
rtk git show            # Compact show (80%)
rtk git add             # Ultra-compact confirmations (59%)
rtk git commit          # Ultra-compact confirmations (59%)
rtk git push            # Ultra-compact confirmations
rtk git pull            # Ultra-compact confirmations
rtk git branch          # Compact branch list
rtk git fetch           # Compact fetch
rtk git stash           # Compact stash
rtk git worktree        # Compact worktree
```

Note: Git passthrough works for ALL subcommands, even those not explicitly listed.

### GitHub (26-87% savings)
```bash
rtk gh pr view <num>    # Compact PR view (87%)
rtk gh pr checks        # Compact PR checks (79%)
rtk gh run list         # Compact workflow runs (82%)
rtk gh issue list       # Compact issue list (80%)
rtk gh api              # Compact API responses (26%)
```

### JavaScript/TypeScript Tooling (70-90% savings)
```bash
rtk pnpm list           # Compact dependency tree (70%)
rtk pnpm outdated       # Compact outdated packages (80%)
rtk pnpm install        # Compact install output (90%)
rtk npm run <script>    # Compact npm script output
rtk npx <cmd>           # Compact npx command output
rtk prisma              # Prisma without ASCII art (88%)
```

### Files & Search (60-75% savings)
```bash
rtk ls <path>           # Tree format, compact (65%)
rtk read <file>         # Code reading with filtering (60%)
rtk grep <pattern>      # Search grouped by file (75%)
rtk find <pattern>      # Find grouped by directory (70%)
```

### Analysis & Debug (70-90% savings)
```bash
rtk err <cmd>           # Filter errors only from any command
rtk log <file>          # Deduplicated logs with counts
rtk json <file>         # JSON structure without values
rtk deps                # Dependency overview
rtk env                 # Environment variables compact
rtk summary <cmd>       # Smart summary of command output
rtk diff                # Ultra-compact diffs
```

### Infrastructure (85% savings)
```bash
rtk docker ps           # Compact container list
rtk docker images       # Compact image list
rtk docker logs <c>     # Deduplicated logs
rtk kubectl get         # Compact resource list
rtk kubectl logs        # Deduplicated pod logs
```

### Network (65-70% savings)
```bash
rtk curl <url>          # Compact HTTP responses (70%)
rtk wget <url>          # Compact download output (65%)
```

### Meta Commands
```bash
rtk gain                # View token savings statistics
rtk gain --history      # View command history with savings
rtk discover            # Analyze Claude Code sessions for missed RTK usage
rtk proxy <cmd>         # Run command without filtering (for debugging)
rtk init                # Add RTK instructions to CLAUDE.md
rtk init --global       # Add RTK to ~/.claude/CLAUDE.md
```

## Token Savings Overview

| Category | Commands | Typical Savings |
|----------|----------|-----------------|
| Tests | vitest, playwright, cargo test | 90-99% |
| Build | next, tsc, lint, prettier | 70-87% |
| Git | status, log, diff, add, commit | 59-80% |
| GitHub | gh pr, gh run, gh issue | 26-87% |
| Package Managers | pnpm, npm, npx | 70-90% |
| Files | ls, read, grep, find | 60-75% |
| Infrastructure | docker, kubectl | 85% |
| Network | curl, wget | 65-70% |

Overall average: **60-90% token reduction** on common development operations.
<!-- /rtk-instructions -->

<!-- BEGIN BEADS INTEGRATION v:1 profile:minimal hash:7510c1e2 -->
## Beads Issue Tracker

This project uses **bd (beads)** for issue tracking. Run `bd prime` to see full workflow context and commands.

### Quick Reference

```bash
bd ready              # Find available work
bd show <id>          # View issue details
bd update <id> --claim  # Claim work
bd close <id>         # Complete work
```

### Rules

- Use `bd` for ALL task tracking — do NOT use TodoWrite, TaskCreate, or markdown TODO lists
- Run `bd prime` for detailed command reference and session close protocol
- Use `bd remember` for persistent knowledge — do NOT use MEMORY.md files

**Architecture in one line:** issues live in a local Dolt DB; sync uses `refs/dolt/data` on your git remote; `.beads/issues.jsonl` is a passive export. See https://github.com/gastownhall/beads/blob/main/docs/SYNC_CONCEPTS.md for details and anti-patterns.

## Session Completion

**When ending a work session**, you MUST complete ALL steps below. Work is NOT complete until `git push` succeeds.

**MANDATORY WORKFLOW:**

1. **File issues for remaining work** - Create issues for anything that needs follow-up
2. **Run quality gates** (if code changed) - Tests, linters, builds
3. **Update issue status** - Close finished work, update in-progress items
4. **PUSH TO REMOTE** - This is MANDATORY:
   ```bash
   git pull --rebase
   git push
   git status  # MUST show "up to date with origin"
   ```
5. **Clean up** - Clear stashes, prune remote branches
6. **Verify** - All changes committed AND pushed
7. **Hand off** - Provide context for next session

**CRITICAL RULES:**
- Work is NOT complete until `git push` succeeds
- NEVER stop before pushing - that leaves work stranded locally
- NEVER say "ready to push when you are" - YOU must push
- If push fails, resolve and retry until it succeeds
<!-- END BEADS INTEGRATION -->
