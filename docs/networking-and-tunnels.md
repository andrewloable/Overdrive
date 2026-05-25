# Networking and Tunnels

Overdrive exposes a local authenticated web server and can optionally front it with LAN access, Cloudflared, Zrok, or Tailscale. Outbound networking can be routed through sing-box when the BYD head unit blocks direct access.

## Local Ports

| Port | Bind | Component | Purpose |
| --- | --- | --- | --- |
| `19876` | `127.0.0.1` | `TcpCommandServer` | JSON command IPC for camera daemon control and secret bridge |
| `19877` | `127.0.0.1` | `SurveillanceIpcServer` | JSON IPC for surveillance, GPS, update, Telegram, MQTT, ABRP |
| `8080` | `127.0.0.1` by default | `HttpServer` | Web UI, REST APIs, video, thumbnails, WebSocket streaming |
| `8119` | `127.0.0.1` | sing-box | Mixed HTTP/SOCKS-style outbound proxy |
| `8532` | `127.0.0.1` | Tailscale | Userspace Tailscale local socket |
| `8539` | `127.0.0.1` | Tailscale | Optional local SOCKS5 proxy |

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
- Routes map/CDN requests through sing-box proxy when available.

## Cloudflared Tunnel

`TunnelLauncher` extracts and runs Cloudflared from the packaged native library.

Runtime paths:

```text
/data/local/tmp/cloudflared
/data/local/tmp/cloudflared.log
```

The tunnel fronts:

```text
http://127.0.0.1:8080
```

It runs with IPv4 edge preference, HTTP/2 protocol, no auto-update, retries, and a grace period. The launcher parses `https://*.trycloudflare.com` URLs from logs and can notify Telegram.

Cloudflared and Zrok are treated as mutually exclusive by the launchers; starting one stops the other.

## Zrok Tunnel

`ZrokLauncher` extracts and runs Zrok from the packaged native library.

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

Zrok tokens and identity data are secrets. Keep them in the secret store or Zrok runtime directory only.

## Tailscale

`TailscaleLauncher` runs Tailscale in userspace networking mode.

Runtime paths and ports:

```text
/data/local/tmp/.tailscale/tailscale
/data/local/tmp/.tailscale/tailscaled
127.0.0.1:8532
127.0.0.1:8539
```

Capabilities:

- Generate login URL.
- Retrieve Tailscale IP.
- Provide optional local SOCKS5 proxy.
- Use sing-box as an outbound proxy when needed.

## sing-box Proxy

`SingboxLauncher` installs and runs sing-box.

Runtime paths:

```text
/data/local/tmp/sing-box
/data/local/tmp/singbox_config.json
/data/local/tmp/singbox.log
```

Local proxy:

```text
127.0.0.1:8119
```

The config uses a mixed inbound and a VLESS Reality outbound. Actual proxy credentials are sensitive and should not be committed, logged, or documented.

`GlobalProxyDaemon` appears to be an older or alternate daemon path for the same general proxy function.

## Proxy Consumers

`ProxyHelper` and launcher logic can route these outbound paths through the local proxy:

- BYD cloud HTTPS.
- BYD cloud MQTT.
- ABRP.
- Cloudflared.
- Zrok.
- Tailscale setup paths.
- WebView map/CDN requests.

## MQTT Networking

MQTT is used in two places:

- User-configured MQTT connections for telemetry.
- BYD cloud MQTT v5 subscription for vehicle cloud updates.

BYD cloud MQTT uses Eclipse Paho MQTT v5 because Paho v3 can connect but does not receive expected vehicle info pushes in this environment.

MQTT connections may use proxied socket factories when sing-box is available.

## Remote Access Security Model

Recommended exposure order:

1. Loopback only.
2. Tailscale private network.
3. Authenticated tunnel.
4. LAN mode only when needed and trusted.

Risk notes:

- LAN mode binds all interfaces and should remain disabled by default.
- Tunnel URLs should be treated as sensitive.
- Auth tokens must not be logged.
- Release builds intentionally protect loopback.
- If sing-box credentials are present in generated config, treat that file as secret.

## Source References

- Local daemon and server ports: [CameraDaemon.java:53](../app/src/main/java/com/overdrive/app/daemon/CameraDaemon.java#L53), [CameraDaemon.java:350](../app/src/main/java/com/overdrive/app/daemon/CameraDaemon.java#L350), [CameraConfiguration.kt:11](../app/src/main/java/com/overdrive/app/daemon/camera/CameraConfiguration.kt#L11).
- HTTP bind mode and LAN opt-in: [HttpServer.java:49](../app/src/main/java/com/overdrive/app/server/HttpServer.java#L49), [UnifiedConfigManager.kt:559](../app/src/main/java/com/overdrive/app/config/UnifiedConfigManager.kt#L559), [UnifiedConfigManager.kt:567](../app/src/main/java/com/overdrive/app/config/UnifiedConfigManager.kt#L567).
- Auth middleware, JWTs, and token state: [AuthManager.java:50](../app/src/main/java/com/overdrive/app/auth/AuthManager.java#L50), [AuthManager.java:349](../app/src/main/java/com/overdrive/app/auth/AuthManager.java#L349), [AuthManager.java:463](../app/src/main/java/com/overdrive/app/auth/AuthManager.java#L463), [AuthMiddleware.java:133](../app/src/main/java/com/overdrive/app/server/AuthMiddleware.java#L133), [AuthApiHandler.java:26](../app/src/main/java/com/overdrive/app/server/AuthApiHandler.java#L26).
- WebSocket streaming path: [WebSocketStreamServer.java:19](../app/src/main/java/com/overdrive/app/streaming/WebSocketStreamServer.java#L19), [GpuSurveillancePipeline.java:30](../app/src/main/java/com/overdrive/app/surveillance/GpuSurveillancePipeline.java#L30), [HttpServer.java:538](../app/src/main/java/com/overdrive/app/server/HttpServer.java#L538).
- Android WebView proxy bypass: [WebViewFragment.kt:28](../app/src/main/java/com/overdrive/app/ui/fragment/WebViewFragment.kt#L28), [WebViewFragment.kt:228](../app/src/main/java/com/overdrive/app/ui/fragment/WebViewFragment.kt#L228), [WebViewFragment.kt:374](../app/src/main/java/com/overdrive/app/ui/fragment/WebViewFragment.kt#L374).
- Cloudflared launch path: [TunnelLauncher.kt:12](../app/src/main/java/com/overdrive/app/launcher/TunnelLauncher.kt#L12), [AdbDaemonLauncher.kt:211](../app/src/main/java/com/overdrive/app/launcher/AdbDaemonLauncher.kt#L211).
- Zrok launch path and tokens: [ZrokLauncher.kt:27](../app/src/main/java/com/overdrive/app/launcher/ZrokLauncher.kt#L27), [ZrokLauncher.kt:466](../app/src/main/java/com/overdrive/app/launcher/ZrokLauncher.kt#L466), [ZrokLauncher.kt:1079](../app/src/main/java/com/overdrive/app/launcher/ZrokLauncher.kt#L1079), [UnifiedConfigManager.kt:800](../app/src/main/java/com/overdrive/app/config/UnifiedConfigManager.kt#L800).
- Tailscale launch path and ports: [TailscaleLauncher.kt:11](../app/src/main/java/com/overdrive/app/launcher/TailscaleLauncher.kt#L11), [TailscaleLauncher.kt:303](../app/src/main/java/com/overdrive/app/launcher/TailscaleLauncher.kt#L303).
- sing-box proxy generation and consumers: [GlobalProxyDaemon.java:15](../app/src/main/java/com/overdrive/app/daemon/GlobalProxyDaemon.java#L15), [ProxyConfiguration.kt:29](../app/src/main/java/com/overdrive/app/daemon/proxy/ProxyConfiguration.kt#L29), [ProxyConfiguration.kt:38](../app/src/main/java/com/overdrive/app/daemon/proxy/ProxyConfiguration.kt#L38), [AbrpTelemetryService.java:123](../app/src/main/java/com/overdrive/app/abrp/AbrpTelemetryService.java#L123), [WebViewFragment.kt:398](../app/src/main/java/com/overdrive/app/ui/fragment/WebViewFragment.kt#L398).
- MQTT networking and BYD cloud MQTT: [MqttPublisherService.java:27](../app/src/main/java/com/overdrive/app/mqtt/MqttPublisherService.java#L27), [MqttApiHandler.java:25](../app/src/main/java/com/overdrive/app/server/MqttApiHandler.java#L25), [BydCloudMqttSubscriber.java:31](../app/src/main/java/com/overdrive/app/byd/cloud/BydCloudMqttSubscriber.java#L31).
