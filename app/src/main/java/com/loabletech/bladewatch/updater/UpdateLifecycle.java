package com.overdrive.app.updater;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.overdrive.app.launcher.AdbDaemonLauncher;

import java.io.File;

/**
 * Coordinates the post-update launch path so the new app process always wins
 * over zombie daemons / watchdogs left behind by the previous install.
 *
 * The contract:
 *   - Old process (AppUpdater.stopAllDaemons) writes UPDATE_IN_PROGRESS_FILE
 *     and POST_UPDATE_FILE to /data/local/tmp before pm install.
 *   - New process (MainActivity) detects either sentinel + the post_update
 *     intent extra + PREF_JUST_UPDATED. If any is set, it runs hardResetDaemons
 *     before DaemonStartupManager so old daemons can't outlive the install.
 *   - hardResetDaemons clears the sentinels on completion.
 */
public final class UpdateLifecycle {

    private static final String TAG = "UpdateLifecycle";

    public static final String UPDATE_IN_PROGRESS_FILE = "/data/local/tmp/overdrive_update_in_progress";
    public static final String POST_UPDATE_FILE = "/data/local/tmp/overdrive_post_update";
    /**
     * One-shot marker read by TelegramBotDaemon's notifyTunnel handler so the
     * first post-update tunnel-URL message can include the new version (and a
     * "this is why your URL changed" hint) instead of the generic "URL changed"
     * copy. Contains the version string (e.g. "alpha-v11.4"). Deleted by the
     * daemon after consuming.
     */
    public static final String TELEGRAM_POST_UPDATE_HINT_FILE =
            "/data/local/tmp/overdrive_post_update_pending_telegram";

    public static final String EXTRA_POST_UPDATE = "post_update";

    private UpdateLifecycle() {}

    /** Detects whether this launch came right after a package install. */
    public static boolean isPostUpdateLaunch(Context ctx, Intent intent) {
        if (intent != null && intent.getBooleanExtra(EXTRA_POST_UPDATE, false)) return true;
        if (new File(POST_UPDATE_FILE).exists()) return true;
        if (new File(UPDATE_IN_PROGRESS_FILE).exists()) return true;
        try {
            return ctx.getSharedPreferences("app_updater", Context.MODE_PRIVATE)
                    .getBoolean("just_updated", false);
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * Hard-kill every known daemon + watchdog, wipe lock/sentinel files, then
     * invoke onComplete on the same thread that the underlying launcher uses.
     * Safe to call when no update is in progress — it's just a sweep.
     */
    public static void hardResetDaemons(Context ctx, Runnable onComplete) {
        Log.i(TAG, "post-update detected — hard-resetting daemons");
        long start = System.currentTimeMillis();
        AdbDaemonLauncher launcher = new AdbDaemonLauncher(ctx);

        // Single shell invocation — atomic from the daemon-watchdog perspective
        // (no chance for a half-killed watchdog to re-spawn between commands).
        // Process names are sourced from launcher constants:
        //   byd_cam_daemon       (DaemonLauncher.CAMERA_DAEMON_PROCESS)
        //   sentry_daemon        (DaemonLauncher.SENTRY_DAEMON_PROCESS)
        //   acc_sentry_daemon    (DaemonLauncher.ACC_SENTRY_DAEMON_PROCESS)
        //   sentry_proxy         (DaemonLauncher.PROXY_DAEMON_PROCESS)
        //   telegram_bot_daemon  (DaemonLauncher.TELEGRAM_DAEMON_PROCESS)
        //   tailscaled           (TailscaleLauncher line 308)
        //   cloudflared          (TunnelLauncher.CLOUDFLARED_PROCESS)
        //   zrok                 (ZrokLauncher.ZROK_PROCESS)
        //   sing-box             (SingboxLauncher)
        String cmd =
                // Sentinel first so any racing watchdog sees "disabled" and bails out
                "echo 'disabled by post-update reset' > /data/local/tmp/camera_daemon.disabled; " +
                // Watchdog shell scripts (kill before binaries so they can't respawn)
                "pkill -9 -f 'start_cam_daemon' 2>/dev/null; " +
                "pkill -9 -f 'start_acc_sentry' 2>/dev/null; " +
                // Native + app_process daemons (-f substring-matches the full command line)
                "pkill -9 -f 'byd_cam_daemon' 2>/dev/null; " +
                "pkill -9 -f 'cam_daemon' 2>/dev/null; " +           // defensive (catches any cam_daemon variant)
                "pkill -9 -f 'sentry_daemon' 2>/dev/null; " +         // also catches acc_sentry_daemon
                "pkill -9 -f 'acc_sentry_daemon' 2>/dev/null; " +
                "pkill -9 -f 'telegram_bot_daemon' 2>/dev/null; " +
                "pkill -9 -f 'sentry_proxy' 2>/dev/null; " +
                "pkill -9 -f 'cloudflared' 2>/dev/null; " +
                "pkill -9 -f 'zrok' 2>/dev/null; " +
                "pkill -9 -f 'sing-box' 2>/dev/null; " +
                "pkill -9 -f 'tailscaled' 2>/dev/null; " +            // tailscale daemon
                // killall as a backup for binaries whose argv[0] differs from -f match
                "killall -9 cloudflared 2>/dev/null; " +
                "killall -9 zrok 2>/dev/null; " +
                "killall -9 tailscaled 2>/dev/null; " +
                "killall -9 sing-box 2>/dev/null; " +
                // Lock + watchdog state
                "rm -f /data/local/tmp/*_daemon.lock 2>/dev/null; " +
                "rm -f /data/local/tmp/*_daemon.disabled 2>/dev/null; " +
                "rm -f /data/local/tmp/cam_watchdog.pid 2>/dev/null; " +
                "rm -f /data/local/tmp/start_cam_daemon.sh /data/local/tmp/start_acc_sentry.sh 2>/dev/null; " +
                // Sentinels — clear last so a crash leaves them behind to retry next launch
                "rm -f " + UPDATE_IN_PROGRESS_FILE + " " + POST_UPDATE_FILE + " 2>/dev/null; " +
                "echo done";

        launcher.executeShellCommand(cmd, new AdbDaemonLauncher.LaunchCallback() {
            @Override public void onLog(String m) {}
            @Override public void onLaunched() {
                long ms = System.currentTimeMillis() - start;
                Log.i(TAG, "hard reset complete in " + ms + "ms");
                // Brief settle so the OS reclaims PIDs before new daemons launch
                try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
                if (onComplete != null) onComplete.run();
            }
            @Override public void onError(String e) {
                Log.w(TAG, "hard reset error (continuing): " + e);
                try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
                if (onComplete != null) onComplete.run();
            }
        });
    }
}
