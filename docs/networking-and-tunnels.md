# Networking and Tunnels

BladeWatch exposes a local authenticated web server and can optionally front it with LAN access or a Zrok tunnel. Zrok is the only supported remote tunnel; no proxy layer sits between Zrok and the local server.

## Local Ports

| Port | Bind | Component | Purpose |
| --- | --- | --- | --- |
| `19876` | `127.0.0.1` | `TcpCommandServer` | JSON command IPC for camera daemon control and secret bridge |
| `19877` | `127.0.0.1` | `SurveillanceIpcServer` | JSON IPC for surveillance, GPS, and update actions |
| `8080` | `127.0.0.1` by default | `HttpServer` | Web UI, REST APIs, ConnectRPC (`/bladewatch.v1.*`), video, thumbnails, WebSocket streaming |

Both IPC servers (`19876`/`19877`) authenticate callers with the bootstrap token
in `/data/local/tmp/bladewatch_ipc_token` (`IpcTokenManager`). That file is
world-readable (`644`) on purpose so the app UID can authenticate to the
shell-UID daemon; the secret store it gates remains `600`. The IPC token is not
exposed over HTTP — see `docs/ipc-auth-and-secrets.md`.

## Embedded HTTP Server

The HTTP server serves (all on a single port so a Zrok tunnel can expose both
HTTP and WebSocket):

- The Angular SPA build (`/`, `/assets/*`, `/vendor/*`) plus legacy pages.
- Shared JavaScript, CSS, and i18n resources.
- Auth endpoints (`/auth/*`).
- The REST API (`/api/*`, `/status`, `/video/*`, `/thumb/*`, `/snapshot/*`).
- The ConnectRPC / gRPC-style API under the `/bladewatch.v1.*` route prefix,
  consumed by the Angular SPA. Unary calls use `application/json`, streaming
  uses `application/connect+json`; both require `Connect-Protocol-Version: 1`.
  The Connect handlers wrap the REST handlers, so they share the same auth and
  the same bind. See `docs/http-api-reference.md` for the full surface.
- WebSocket streaming on the `/ws` upgrade path.

Default bind:

```text
127.0.0.1:8080
```

LAN mode bind:

```text
0.0.0.0:8080
```

LAN mode is disabled by default and controlled by unified network config
(`UnifiedConfigManager.isLanHttpEnabled()`). `GET /status` echoes the active
bind (`httpBind`) and a warning when LAN HTTP is on.

## Authentication

`AuthMiddleware` protects the HTTP server.

Public paths (no auth at all):

- `/auth/status`, `/auth/token`, `/auth/logout`.
- `/login`, `/login.html`.
- `/manifest.json`, `/sw.js`, `/favicon.ico`.
- `/shared/*`, `/i18n/*` (prefixes).
- `/bladewatch.v1.AuthService/Login` — the Connect login RPC must be reachable
  before a session exists. All other `/bladewatch.v1.*` Connect calls are
  protected by the same middleware that guards REST.

Note: `/auth/*` paths are routed in `HttpServer.handleClient` before the auth
middleware runs, so they are always reachable regardless of the list above.

Protected requests require:

- Bearer JWT (`Authorization: Bearer <jwt>`), or
- `byd_session` cookie (HttpOnly JWT), or
- signed thumbnail token for specific `/thumb/*?t=<jws>` access.

Cookies set on successful `/auth/token`:

- `byd_session` — the JWT itself, **HttpOnly** (not readable by JS), used by the
  WebView and browser for authenticated requests.
- `byd_auth=1` — a non-HttpOnly hint cookie so client JS can tell it is logged
  in without exposing the JWT. Both expire together; logout clears both.

Release builds require JWT auth even from loopback clients because Android loopback is shared across apps. Debug builds can bypass loopback auth only when tunnel-forwarding headers are absent.

Tunnel-forwarding headers checked by the middleware include:

- `X-Forwarded-*`.
- `CF-*`.
- `X-Real-IP`.
- `Forwarded`.

## Device Token and JWT

The auth manager derives access from a device token shaped from device id and secret. It issues HMAC-SHA256 JWTs with a token epoch so all sessions can be invalidated.

Important behavior:

- JWTs are time-limited.
- Token epoch invalidation can revoke old tokens.
- Device secret belongs in the secret store.
- Tunnel URLs should never be shared without considering token exposure.

## WebSocket Streaming

The HTTP server handles WebSocket upgrades for live H.264 streaming.

Streaming behavior includes:

- Token query promotion for WebSocket auth.
- Cached SPS/PPS delivery.
- IDR frame request support.
- Fragmenting large frames into smaller chunks.
- Separate streaming encoder path from recording.

## Android WebView Networking

`WebViewFragment` loads local pages from:

```text
http://127.0.0.1:8080/<page>
```

It handles several BYD head-unit networking issues:

- Injects auth JWT cookie.
- Clears/restores WebView proxy state around local server access.
- Injects JavaScript that routes mutating API requests through `AndroidBridge.httpRequest`.
- Leaves normal GET navigation asynchronous.
- Bypasses proxy for local server requests.

## Zrok Tunnel

`ZrokLauncher` extracts and runs Zrok from the packaged `libzrok.so` native library. Zrok is the only remote tunnel; Cloudflared, Tailscale, and sing-box are not present in this codebase.

Runtime paths:

```text
/data/local/tmp/zrok
/data/local/tmp/zrok.log
/data/local/tmp/.zrok/environment.json
/data/local/tmp/.zrok/unique_name
```

Supported modes:

- Public ephemeral share.
- Reserved share with stable `https://<name>.share.zrok.io` URL.

Zrok runs directly against `http://127.0.0.1:8080` with no intermediate proxy layer.

Zrok tokens and identity data are secrets. Keep them in the secret store or Zrok runtime directory only.

## Remote Access Security Model

Recommended exposure order:

1. Loopback only.
2. Authenticated Zrok tunnel.
3. LAN mode only when needed and trusted.

Risk notes:

- LAN mode binds all interfaces and should remain disabled by default.
- Tunnel URLs should be treated as sensitive.
- Auth tokens must not be logged.
- Release builds intentionally protect loopback.

## Source References

- Local daemon and server ports: [CameraDaemon.java:51](../app/src/main/java/com/loabletech/bladewatch/daemon/CameraDaemon.java#L51), [CameraDaemon.java:243](../app/src/main/java/com/loabletech/bladewatch/daemon/CameraDaemon.java#L243), [CameraDaemon.java:381](../app/src/main/java/com/loabletech/bladewatch/daemon/CameraDaemon.java#L381).
- HTTP bind mode and LAN opt-in: [HttpServer.java:171](../app/src/main/java/com/loabletech/bladewatch/server/HttpServer.java#L171), [UnifiedConfigManager.kt:618](../app/src/main/java/com/loabletech/bladewatch/config/UnifiedConfigManager.kt#L618).
- Connect/gRPC dispatch and service registration: [HttpServer.java:568](../app/src/main/java/com/loabletech/bladewatch/server/HttpServer.java#L568), [ConnectDispatcher.java:72](../app/src/main/java/com/loabletech/bladewatch/server/connect/ConnectDispatcher.java#L72), [CameraDaemon.java:387](../app/src/main/java/com/loabletech/bladewatch/daemon/CameraDaemon.java#L387).
- Auth middleware, public paths, JWTs, and cookies: [AuthMiddleware.java:40](../app/src/main/java/com/loabletech/bladewatch/server/AuthMiddleware.java#L40), [AuthMiddleware.java:95](../app/src/main/java/com/loabletech/bladewatch/server/AuthMiddleware.java#L95), [AuthApiHandler.java:170](../app/src/main/java/com/loabletech/bladewatch/server/AuthApiHandler.java#L170), [AuthManager.java:446](../app/src/main/java/com/loabletech/bladewatch/auth/AuthManager.java#L446), [AuthManager.java:561](../app/src/main/java/com/loabletech/bladewatch/auth/AuthManager.java#L561).
- IPC token bootstrap: [IpcTokenManager.java:54](../app/src/main/java/com/loabletech/bladewatch/server/IpcTokenManager.java#L54).
- WebSocket streaming path: [HttpServer.java:344](../app/src/main/java/com/loabletech/bladewatch/server/HttpServer.java#L344), [HttpServer.java:1042](../app/src/main/java/com/loabletech/bladewatch/server/HttpServer.java#L1042), [WebSocketStreamServer.java:19](../app/src/main/java/com/loabletech/bladewatch/streaming/WebSocketStreamServer.java#L19).
- Android WebView proxy bypass and cookie injection: [WebViewFragment.kt:33](../app/src/main/java/com/loabletech/bladewatch/ui/fragment/WebViewFragment.kt#L33), [WebViewFragment.kt:336](../app/src/main/java/com/loabletech/bladewatch/ui/fragment/WebViewFragment.kt#L336), [WebViewFragment.kt:375](../app/src/main/java/com/loabletech/bladewatch/ui/fragment/WebViewFragment.kt#L375).
- Zrok launch path and tokens (only `libzrok.so` ships in `jniLibs/arm64-v8a/`): [ZrokLauncher.kt:27](../app/src/main/java/com/loabletech/bladewatch/launcher/ZrokLauncher.kt#L27), [ZrokLauncher.kt:36](../app/src/main/java/com/loabletech/bladewatch/launcher/ZrokLauncher.kt#L36), [ZrokLauncher.kt:338](../app/src/main/java/com/loabletech/bladewatch/launcher/ZrokLauncher.kt#L338), [ZrokLauncher.kt:425](../app/src/main/java/com/loabletech/bladewatch/launcher/ZrokLauncher.kt#L425).
