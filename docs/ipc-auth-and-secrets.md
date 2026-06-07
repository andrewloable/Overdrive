# IPC, Authentication & Secrets

How the **app process** (app UID, e.g. `u0_a85` / 10085) and the
**shell-launched daemons** (shell UID `2000`) authenticate to each other and
share secrets. This is the single most fragile seam in BladeWatch because the
two sides run under **different UIDs** and communicate only through files in
`/data/local/tmp` and loopback TCP. Get a file permission wrong and the symptom
is an opaque **"Camera unavailable" / "Auth unavailable"** in the UI with no
stack trace — so read this before touching auth, the IPC servers, or anything
that writes to `/data/local/tmp`.

## The UID split

| Process | UID | Can write `/data/local/tmp`? |
|---|---|---|
| CameraDaemon / Sentry daemons (`app_process`) | `shell` (2000) | yes |
| Android app (MainActivity, fragments) | app UID (e.g. 10085) | **no** (read-only, and only world/group-readable files) |

The app cannot write to `/data/local/tmp` and cannot read files that are mode
`600 shell`. Every cross-process file therefore has a deliberate permission.

## Cross-process files and their REQUIRED permissions

| File | Owner / mode | Why | Who reads it |
|---|---|---|---|
| `bladewatch_config.json` | `shell` `666` (world-rw) | non-secret config | app + daemon (direct) |
| `bladewatch_secrets.json` | `shell` `600` (shell-only) | device secret, tunnel tokens, cloud creds | **daemon only**; app fetches values over IPC |
| `bladewatch_ipc_token` | `shell` `644` (world-readable) | shared token that authenticates loopback IPC | **app + daemon** — app MUST be able to read it |

⚠️ **The `bladewatch_ipc_token` permission is load-bearing.** It is written by
`IpcTokenManager.generate()` ([IpcTokenManager.java](../app/src/main/java/com/loabletech/bladewatch/server/IpcTokenManager.java)).
A bare `new FileWriter(path)` creates it mode `600` (shell-only), which the app
cannot read — and then **every app→daemon IPC call fails silently**. The
generator explicitly `setReadable(true, false)` to make it `644`. Do not revert
that without an equivalent chmod.

## Secret-fetch flow (how the app gets a secret it cannot read)

```
App needs deviceSecret
  → SecretConfigBridge.getString("auth", "deviceSecret")
      → 1. directStore.getString(...)            // SecretConfigStore reads the file
      │      └─ FAILS for app UID (secrets.json is 600 shell-only)
      → 2. readViaIpc(...)                        // fallback
             → IpcTokenManager.getToken()         // reads bladewatch_ipc_token (644)  ← must be readable
             → CameraDaemonClient → 127.0.0.1:19876 (TcpCommandServer)
                   request: secret_get  + IPC token
             → TcpCommandServer.isValid(token)?   // IpcTokenManager.isValid()
                   ├─ no  → rejected → app gets null → "Auth unavailable"
                   └─ yes → returns deviceSecret
```

- `SecretConfigBridge` ([SecretConfigBridge.kt](../app/src/main/java/com/loabletech/bladewatch/config/SecretConfigBridge.kt)) tries the direct read first, then falls back to loopback IPC.
- `TcpCommandServer` (19876) and `SurveillanceIpcServer` (19877) require a valid IPC token on every request (added in the *IPC token management* change).
- The IPC token is the **bootstrap**: if the app can't read the token file, the entire fallback path is dead, so secrets/JWTs are unavailable.

## JWT + live-view flow

```
AuthManager.generateJwt()
  → loadFromConfig() → deviceSecret via SecretConfigBridge (above)
  → HMAC-SHA256(header.payload, deviceSecret)        // AuthManager.java
LiveStreamClient.runStream()                          // native live view
  → getJwt() ── null? → "Camera unavailable\nAuth unavailable"
  → POST http://127.0.0.1:8080/api/stream/enable   (Authorization: Bearer <jwt>)
  → POST /api/stream/view/<direction>
  → GET  /api/stream/quality                         (decoder width/height)
  → WebSocket ws://127.0.0.1:8080/ws?token=<jwt>
  → first binary frame = SPS+PPS (codec config), then H.264 NALs → MediaCodec → Surface
```

- HTTP/WebSocket server is on **8080**; the command/secret IPC server is on **19876**; surveillance IPC on **19877**. All bind to `127.0.0.1` (appear as `::ffff:127.0.0.1:<port>` in `/proc/net/tcp6`).
- `LiveStreamClient.getJwt()` returning null is reported as **"Auth unavailable"**; a `ConnectException` to 8080 is reported as **"Daemon not running"**.

## Failure modes → symptoms (debugging cheat sheet)

| Symptom | Likely cause | Check |
|---|---|---|
| "Camera unavailable / Auth unavailable" | app can't read `bladewatch_ipc_token` (mode 600) → IPC secret fetch fails | `ls -la /data/local/tmp/bladewatch_ipc_token` → must be `-rw-r--r--` |
| "Camera unavailable / Auth unavailable" | daemon rejecting IPC token | daemon log: no `Processing command: secret_get` arriving |
| "Camera unavailable / Daemon not running" | nothing listening on 8080 | `cat /proc/net/tcp6 \| grep 1F90` |
| CameraDaemon crashes on boot (`UnsatisfiedLinkError`) | JNI symbol names don't match the runtime package after a package rename | `grep -r Java_<pkg> app/src/main/cpp/` must match `applicationId` |
| "Another CameraDaemon instance is already running" | stale lock from a hung daemon | kill daemon + `rm /data/local/tmp/camera_daemon.lock` (see CLAUDE.md clean reinstall) |

## Rules for future changes (prevent regressions)

1. **Any file the app must read from `/data/local/tmp` must be world- or group-readable.** Never rely on a bare `FileWriter`/`FileOutputStream` for such files — they default to mode `600`. chmod (`setReadable(true, false)` or `Files.setPosixFilePermissions`) immediately after writing.
2. **Secrets the app must NOT read directly stay `600`** and are fetched over IPC (the token-gated `secret_get` path). Don't loosen `bladewatch_secrets.json`.
3. **The IPC token must be readable by the app** — it is the bootstrap for the whole secret/JWT chain.
4. **JNI symbol names must track `applicationId`.** A package rename (e.g. `com.loabletech.bladewatch` → `net.bladewatch.app`) requires renaming every `Java_<pkg>_…` symbol in `app/src/main/cpp/`, or the daemon dies with `UnsatisfiedLinkError` at startup and the camera is unavailable.
5. **Rebuild AND clean-reinstall after native or daemon changes** — shell-launched daemons survive an `install -r`; a stale daemon with the old `.so` keeps running. See the clean-reinstall block in `CLAUDE.md`.
