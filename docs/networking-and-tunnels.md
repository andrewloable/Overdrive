# Networking and Tunnels

BladeWatch exposes a local authenticated web server and can optionally front it with LAN access or a Zrok tunnel. Zrok is the only supported remote tunnel; no proxy layer sits between Zrok and the local server.

## Local Ports

| Port | Bind | Component | Purpose |
| --- | --- | --- | --- |
| `19876` | `127.0.0.1` | `TcpCommandServer` | JSON command IPC for camera daemon control and secret bridge |
| `19877` | `127.0.0.1` | `SurveillanceIpcServer` | JSON IPC for surveillance, GPS, and update actions |
| `8080` | `127.0.0.1` by default | `HttpServer` | Web UI, REST APIs, video, thumbnails, WebSocket streaming |

## Embedded HTTP Server

The HTTP server serves:

- Static web UI pages.
- Shared JavaScript and CSS.
- i18n resources.
- Auth endpoints.
- REST APIs.
- Video and thumbnail resources.
- WebSocket streaming.

Default bind:

```text
127.0.0.1:8080
```

LAN mode bind:

```text
0.0.0.0:8080
```

LAN mode is disabled by default and controlled by unified network config.

## Authentication

`AuthMiddleware` protects the HTTP server.

Public paths include:

- `/auth/status`.
- `/auth/token`.
- `/auth/logout`.
- `/login`.
- `/login.html`.
- `/manifest.json`.
- `/sw.js`.
- `/shared/*`.
- `/i18n/*`.

Protected requests require:

- Bearer JWT, or
- `byd_session` cookie, or
- signed thumbnail token for specific `/thumb/*` access.

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

- Local daemon and server ports: [CameraDaemon.java:53](../app/src/main/java/com/loabletech/bladewatch/daemon/CameraDaemon.java#L53), [CameraDaemon.java:350](../app/src/main/java/com/loabletech/bladewatch/daemon/CameraDaemon.java#L350), [CameraConfiguration.kt:11](../app/src/main/java/com/loabletech/bladewatch/daemon/camera/CameraConfiguration.kt#L11).
- HTTP bind mode and LAN opt-in: [HttpServer.java:49](../app/src/main/java/com/loabletech/bladewatch/server/HttpServer.java#L49), [UnifiedConfigManager.kt:559](../app/src/main/java/com/loabletech/bladewatch/config/UnifiedConfigManager.kt#L559), [UnifiedConfigManager.kt:567](../app/src/main/java/com/loabletech/bladewatch/config/UnifiedConfigManager.kt#L567).
- Auth middleware, JWTs, and token state: [AuthManager.java:50](../app/src/main/java/com/loabletech/bladewatch/auth/AuthManager.java#L50), [AuthManager.java:349](../app/src/main/java/com/loabletech/bladewatch/auth/AuthManager.java#L349), [AuthManager.java:463](../app/src/main/java/com/loabletech/bladewatch/auth/AuthManager.java#L463), [AuthMiddleware.java:133](../app/src/main/java/com/loabletech/bladewatch/server/AuthMiddleware.java#L133), [AuthApiHandler.java:26](../app/src/main/java/com/loabletech/bladewatch/server/AuthApiHandler.java#L26).
- WebSocket streaming path: [WebSocketStreamServer.java:19](../app/src/main/java/com/loabletech/bladewatch/streaming/WebSocketStreamServer.java#L19), [GpuSurveillancePipeline.java:30](../app/src/main/java/com/loabletech/bladewatch/surveillance/GpuSurveillancePipeline.java#L30), [HttpServer.java:538](../app/src/main/java/com/loabletech/bladewatch/server/HttpServer.java#L538).
- Android WebView proxy bypass: [WebViewFragment.kt:28](../app/src/main/java/com/loabletech/bladewatch/ui/fragment/WebViewFragment.kt#L28), [WebViewFragment.kt:228](../app/src/main/java/com/loabletech/bladewatch/ui/fragment/WebViewFragment.kt#L228), [WebViewFragment.kt:374](../app/src/main/java/com/loabletech/bladewatch/ui/fragment/WebViewFragment.kt#L374).
- Zrok launch path and tokens: [ZrokLauncher.kt:27](../app/src/main/java/com/loabletech/bladewatch/launcher/ZrokLauncher.kt#L27), [ZrokLauncher.kt:466](../app/src/main/java/com/loabletech/bladewatch/launcher/ZrokLauncher.kt#L466), [ZrokLauncher.kt:1079](../app/src/main/java/com/loabletech/bladewatch/launcher/ZrokLauncher.kt#L1079), [UnifiedConfigManager.kt:800](../app/src/main/java/com/loabletech/bladewatch/config/UnifiedConfigManager.kt#L800).
