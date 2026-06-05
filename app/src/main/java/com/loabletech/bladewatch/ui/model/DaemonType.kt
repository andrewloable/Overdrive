package com.overdrive.app.ui.model

/**
 * Types of background daemons managed by the app.
 * Note: Location Sidecar is not included here as it auto-starts silently
 * and is managed by SentryDaemon, not shown in the UI.
 */
enum class DaemonType(val displayName: String, val processName: String) {
    CAMERA_DAEMON("Camera Daemon", "byd_cam_daemon"),
    SENTRY_DAEMON("Sentry Daemon", "sentry_daemon"),
    ACC_SENTRY_DAEMON("ACC Sentry", "acc_sentry_daemon"),
    SINGBOX_PROXY("Sing-box Proxy", "sing-box"),
    CLOUDFLARED_TUNNEL("Cloudflared Tunnel", "cloudflared"),
    ZROK_TUNNEL("Zrok Tunnel", "zrok"),
    TAILSCALE_TUNNEL("Tailscale Tunnel", "tailscaled"),
    TELEGRAM_DAEMON("Telegram Bot", "telegram_bot_daemon")
}
