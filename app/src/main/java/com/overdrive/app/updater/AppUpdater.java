package com.overdrive.app.updater;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.overdrive.app.BuildConfig;
import com.overdrive.app.launcher.AdbShellExecutor;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Checks GitHub Releases for app updates and handles download + silent install.
 *
 * Release model:
 * - Fixed tags: "alpha", "debug", "prod" (future)
 * - APK is replaced in-place on the same release
 * - Update detection: compare asset updated_at vs last installed timestamp
 * - Debug tag is ignored in release builds
 *
 * API: https://api.github.com/repos/yash-srivastava/Overdrive-release/releases/tags/{channel}
 */
public class AppUpdater {

    private static final String TAG = "AppUpdater";
    private static final String GITHUB_REPO = "yash-srivastava/Overdrive-release";
    private static final String PREFS_NAME = "app_updater";
    private static final String PREF_LAST_UPDATE_TIME = "last_update_timestamp";
    private static final String PREF_JUST_UPDATED = "just_updated";
    private static final String PREF_UPDATED_VERSION = "updated_version";
    // Also persist to filesystem (survives app reinstall, unlike SharedPreferences)
    private static final String UPDATE_TIMESTAMP_FILE = "/data/local/tmp/overdrive_update_timestamp";
    // Version file readable by daemon process (SharedPreferences are per-process)
    public static final String VERSION_FILE = "/data/local/tmp/overdrive_version";

    private final Context context;
    private volatile boolean cancelled = false;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private AdbShellExecutor adb; // Lazy — only created when install is triggered
    private com.overdrive.app.launcher.AdbDaemonLauncher adbLauncher; // For daemon management

    private String latestDownloadUrl;
    private String releaseNotes;
    private String remoteVersion;
    private String remoteUpdatedAt;

    /**
     * Build an OkHttpClient that auto-detects sing-box proxy on port 8119.
     */
    private static OkHttpClient buildClient(long connectTimeout, long readTimeout) {
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .connectTimeout(connectTimeout, TimeUnit.SECONDS)
                .readTimeout(readTimeout, TimeUnit.SECONDS)
                .followRedirects(true);

        // Probe for sing-box proxy
        boolean proxyAvailable = false;
        try {
            java.net.Socket probe = new java.net.Socket();
            probe.connect(new java.net.InetSocketAddress("127.0.0.1", 8119), 200);
            probe.close();
            proxyAvailable = true;
        } catch (Exception ignored) {}

        if (proxyAvailable) {
            builder.proxy(new java.net.Proxy(java.net.Proxy.Type.HTTP,
                    new java.net.InetSocketAddress("127.0.0.1", 8119)));
            Log.d(TAG, "Using sing-box proxy for update check");
        }

        return builder.build();
    }

    public interface UpdateCallback {
        void onUpdateAvailable(String currentVersion, String newVersion, String releaseNotes);
        void onNoUpdate(String currentVersion);
        void onError(String error);
    }

    public interface InstallCallback {
        void onProgress(String message);
        void onDownloadProgress(int percent);
        void onSuccess();
        void onError(String error);
    }

    public AppUpdater(Context context) {
        this.context = context;
        // Cleanup runs without ADB — just deletes from app's own external files dir
        cleanupLeftoverApk();
    }

    private AdbShellExecutor getAdb() {
        if (adb == null) {
            adb = new AdbShellExecutor(context);
        }
        return adb;
    }

    private com.overdrive.app.launcher.AdbDaemonLauncher getAdbLauncher() {
        if (adbLauncher == null) {
            adbLauncher = new com.overdrive.app.launcher.AdbDaemonLauncher(context);
        }
        return adbLauncher;
    }

    /**
     * Cancel an in-progress download/install.
     */
    public void cancel() {
        cancelled = true;
    }

    private static final String APK_PATH = "/data/local/tmp/overdrive_update.apk";

    private String getApkPath() {
        return APK_PATH;
    }

    private void cleanupLeftoverApk() {
        try {
            getAdbLauncher().executeShellCommand("rm -f " + APK_PATH, new com.overdrive.app.launcher.AdbDaemonLauncher.LaunchCallback() {
                @Override public void onLog(String m) {}
                @Override public void onLaunched() { Log.i(TAG, "Cleaned up leftover APK"); }
                @Override public void onError(String e) {}
            });
        } catch (Exception ignored) {}
    }

    /**
     * Check GitHub Releases for a newer APK on the configured channel.
     * Skips check if channel is empty (debug builds).
     */
    public void checkForUpdate(UpdateCallback callback) {
        String channel = BuildConfig.UPDATE_CHANNEL;
        if (channel == null || channel.isEmpty()) {
            mainHandler.post(() -> callback.onNoUpdate(BuildConfig.VERSION_NAME));
            return;
        }

        executor.execute(() -> {
            try {
                String apiUrl = "https://api.github.com/repos/" + GITHUB_REPO +
                        "/releases/tags/" + channel;

                OkHttpClient client = buildClient(15, 15);

                Request request = new Request.Builder()
                        .url(apiUrl)
                        .header("Accept", "application/vnd.github.v3+json")
                        .build();

                try (Response response = client.newCall(request).execute()) {
                    if (!response.isSuccessful()) {
                        postError(callback, "GitHub API error: HTTP " + response.code());
                        return;
                    }

                    String body = response.body().string();
                    JSONObject release = new JSONObject(body);

                    releaseNotes = release.optString("body", "Bug fixes and improvements.");

                    // Find the APK asset
                    JSONArray assets = release.optJSONArray("assets");
                    if (assets == null || assets.length() == 0) {
                        postError(callback, "No assets in release");
                        return;
                    }

                    String apkUrl = null;
                    String apkName = null;
                    String updatedAt = null;

                    for (int i = 0; i < assets.length(); i++) {
                        JSONObject asset = assets.getJSONObject(i);
                        String name = asset.optString("name", "");
                        if (name.endsWith(".apk")) {
                            apkUrl = asset.optString("browser_download_url", "");
                            apkName = name;
                            updatedAt = asset.optString("updated_at", "");
                            break;
                        }
                    }

                    if (apkUrl == null || apkUrl.isEmpty()) {
                        postError(callback, "No APK found in release");
                        return;
                    }

                    latestDownloadUrl = apkUrl;
                    remoteUpdatedAt = updatedAt;

                    // Extract version from APK filename
                    remoteVersion = extractVersion(apkName);
                    String currentVersion = BuildConfig.VERSION_NAME;

                    // Update detection: compare asset updated_at timestamp only.
                    // Version comparison is unreliable since versionName may not be bumped
                    // when the APK is replaced on the same release tag.
                    String lastInstalledTimestamp = getLastUpdateTimestamp();
                    boolean apkUpdated = !updatedAt.isEmpty() && !updatedAt.equals(lastInstalledTimestamp);

                    // First install or fresh Android Studio install: no stored timestamp
                    // or app was just reinstalled — save current and don't prompt
                    if (lastInstalledTimestamp.isEmpty()) {
                        saveLastUpdateTimestamp(updatedAt);
                        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                                .edit().putString(PREF_UPDATED_VERSION, remoteVersion).apply();
                        persistVersionToFile(remoteVersion);
                        Log.i(TAG, "First run — saved baseline timestamp: " + updatedAt + ", version: " + remoteVersion);
                        mainHandler.post(() -> callback.onNoUpdate(currentVersion));
                        return;
                    }

                    // Detect fresh install/deploy: if app's install time is more recent than
                    // the stored timestamp, this is a new deploy (Android Studio or manual install).
                    // Only suppress update prompt if the app was installed AFTER the remote APK was updated,
                    // meaning the user already has this version (e.g. via Android Studio sideload).
                    try {
                        long appInstallTime = context.getPackageManager()
                                .getPackageInfo(context.getPackageName(), 0).lastUpdateTime;
                        
                        // Parse the REMOTE asset timestamp (not the stored one)
                        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
                        sdf.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
                        long remoteAssetTime = 0;
                        try { remoteAssetTime = sdf.parse(updatedAt).getTime(); } catch (Exception ignored) {}
                        
                        // Parse the stored timestamp to detect if app was reinstalled since last check
                        long storedTime = 0;
                        try { storedTime = sdf.parse(lastInstalledTimestamp).getTime(); } catch (Exception ignored) {}
                        
                        // Fresh deploy: app was installed AFTER the remote APK was uploaded AND
                        // app was also installed after the last update check (i.e. a sideload happened)
                        if (appInstallTime > remoteAssetTime && appInstallTime > storedTime && apkUpdated) {
                            saveLastUpdateTimestamp(updatedAt);
                            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                                    .edit().putString(PREF_UPDATED_VERSION, remoteVersion).apply();
                            persistVersionToFile(remoteVersion);
                            Log.i(TAG, "Fresh deploy detected (app install " + appInstallTime + 
                                    " > remote asset " + remoteAssetTime + ") — updated baseline");
                            mainHandler.post(() -> callback.onNoUpdate(currentVersion));
                            return;
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "Could not check install time: " + e.getMessage());
                    }

                    Log.i(TAG, "Channel: " + channel + ", Current: " + currentVersion +
                            ", Remote: " + remoteVersion + ", APK updated: " + updatedAt +
                            ", Last installed: " + lastInstalledTimestamp);

                    if (apkUpdated) {
                        mainHandler.post(() -> callback.onUpdateAvailable(
                                currentVersion, remoteVersion, releaseNotes));
                    } else {
                        mainHandler.post(() -> callback.onNoUpdate(currentVersion));
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Update check failed: " + e.getMessage());
                postError(callback, e.getMessage());
            }
        });
    }

    /**
     * Download APK, then stop daemons, then install silently.
     * Download happens first so user can cancel before daemons are killed.
     */
    public void downloadAndInstall(InstallCallback callback) {
        cancelled = false;
        executor.execute(() -> {
            try {
                if (latestDownloadUrl == null) {
                    postInstallError(callback, "No download URL");
                    return;
                }

                // Step 1: Download APK via ADB shell (shell user can write to /data/local/tmp/)
                // Use app_process to run Java URL download as UID 2000
                postProgress(callback, "Downloading update...");
                mainHandler.post(() -> callback.onDownloadProgress(-1)); // -1 = indeterminate

                String downloadCmd = buildDownloadCommand(latestDownloadUrl, APK_PATH);
                
                final boolean[] dlDone = {false};
                final String[] dlResult = {null};

                getAdbLauncher().executeShellCommand(downloadCmd, new com.overdrive.app.launcher.AdbDaemonLauncher.LaunchCallback() {
                    @Override public void onLog(String message) {
                        dlResult[0] = message;
                    }
                    @Override public void onLaunched() {
                        dlDone[0] = true;
                        synchronized (dlDone) { dlDone.notify(); }
                    }
                    @Override public void onError(String error) {
                        dlResult[0] = "ERROR: " + error;
                        dlDone[0] = true;
                        synchronized (dlDone) { dlDone.notify(); }
                    }
                });

                // Wait for download (up to 5 minutes for large APKs)
                synchronized (dlDone) {
                    if (!dlDone[0]) dlDone.wait(300000);
                }

                if (cancelled) {
                    getAdbLauncher().executeShellCommand("rm -f " + APK_PATH, new com.overdrive.app.launcher.AdbDaemonLauncher.LaunchCallback() {
                        @Override public void onLog(String m) {}
                        @Override public void onLaunched() {}
                        @Override public void onError(String e) {}
                    });
                    postInstallError(callback, "Cancelled");
                    return;
                }

                String dlOutput = dlResult[0] != null ? dlResult[0] : "";
                if (dlOutput.startsWith("ERROR") || !dlOutput.contains("OK")) {
                    postInstallError(callback, "Download failed: " + dlOutput);
                    return;
                }

                mainHandler.post(() -> callback.onDownloadProgress(100));

                // Step 2: Verify APK size via shell
                postProgress(callback, "Verifying download...");
                final boolean[] szDone = {false};
                final String[] szResult = {null};
                getAdbLauncher().executeShellCommand("stat -c%s " + APK_PATH + " 2>/dev/null || echo 0",
                        new com.overdrive.app.launcher.AdbDaemonLauncher.LaunchCallback() {
                    @Override public void onLog(String message) { szResult[0] = message.trim(); }
                    @Override public void onLaunched() {
                        szDone[0] = true;
                        synchronized (szDone) { szDone.notify(); }
                    }
                    @Override public void onError(String error) {
                        szResult[0] = "0";
                        szDone[0] = true;
                        synchronized (szDone) { szDone.notify(); }
                    }
                });
                synchronized (szDone) {
                    if (!szDone[0]) szDone.wait(10000);
                }

                long fileSize = 0;
                try { fileSize = Long.parseLong(szResult[0].trim()); } catch (Exception ignored) {}
                if (fileSize < 1_000_000) {
                    getAdbLauncher().executeShellCommand("rm -f " + APK_PATH, new com.overdrive.app.launcher.AdbDaemonLauncher.LaunchCallback() {
                        @Override public void onLog(String m) {}
                        @Override public void onLaunched() {}
                        @Override public void onError(String e) {}
                    });
                    postInstallError(callback, "Invalid APK (size: " + fileSize + ")");
                    return;
                }

                // Step 3: Stop all daemons
                postProgress(callback, "Stopping daemons...");
                stopAllDaemons();
                Thread.sleep(3000);

                // Step 4: Save update info BEFORE install (process gets killed during pm install)
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit()
                        .putBoolean(PREF_JUST_UPDATED, true)
                        .putString(PREF_UPDATED_VERSION, remoteVersion)
                        .commit();
                persistVersionToFile(remoteVersion);
                saveLastUpdateTimestamp(remoteUpdatedAt);

                // Step 5: Install and relaunch
                postProgress(callback, "Installing...");
                final boolean[] done = {false};
                final String[] result = {null};

                String installCmd = "pm install -r -d " + APK_PATH +
                    "; rm -f " + APK_PATH +
                    "; sleep 2; am start -n com.overdrive.app/.ui.MainActivity";

                getAdbLauncher().executeShellCommand(installCmd, new com.overdrive.app.launcher.AdbDaemonLauncher.LaunchCallback() {
                    @Override public void onLog(String message) {
                        Log.i(TAG, "Install: " + message);
                        result[0] = message;
                    }
                    @Override public void onLaunched() {
                        done[0] = true;
                        synchronized (done) { done.notify(); }
                    }
                    @Override public void onError(String error) {
                        result[0] = "ERROR: " + error;
                        done[0] = true;
                        synchronized (done) { done.notify(); }
                    }
                });

                synchronized (done) {
                    if (!done[0]) done.wait(60000);
                }

                // If we reach here, install may have failed (process should be dead on success)
                String output = result[0] != null ? result[0] : "";
                if (!output.toLowerCase().contains("success")) {
                    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                            .edit()
                            .putBoolean(PREF_JUST_UPDATED, false)
                            .remove(PREF_UPDATED_VERSION)
                            .commit();
                    postInstallError(callback, "Install failed: " + output);
                } else {
                    postProgress(callback, "✅ Update installed! Restarting...");
                    mainHandler.post(callback::onSuccess);
                }
            } catch (Exception e) {
                Log.e(TAG, "Install error: " + e.getMessage());
                postInstallError(callback, e.getMessage());
            }
        });
    }

    /**
     * Build a shell command that downloads a URL to a file path.
     * Uses Java's URL class via a shell one-liner (no curl/wget dependency).
     */
    private String buildDownloadCommand(String url, String outputPath) {
        // Use shell heredoc with Java to download — runs as UID 2000 which can write to /data/local/tmp/
        return "sh -c 'java_url=\"" + url + "\"; " +
               "output=\"" + outputPath + "\"; " +
               "rm -f \"$output\"; " +
               // Use Android's built-in toybox wget if available, otherwise use content provider
               "if command -v wget >/dev/null 2>&1; then " +
               "  wget -q -O \"$output\" \"$java_url\" && echo OK; " +
               "elif command -v curl >/dev/null 2>&1; then " +
               "  curl -sL -o \"$output\" \"$java_url\" && echo OK; " +
               "else " +
               // Fallback: use am broadcast to trigger download from app process
               "  echo \"ERROR: No download tool available\"; " +
               "fi'";
    }

    private void cleanup(String path) {
        try {
            getAdbLauncher().executeShellCommand("rm -f " + path, new com.overdrive.app.launcher.AdbDaemonLauncher.LaunchCallback() {
                @Override public void onLog(String m) {}
                @Override public void onLaunched() {}
                @Override public void onError(String e) {}
            });
        } catch (Exception ignored) {}
    }

    private void stopAllDaemons() {
        Log.i(TAG, "Stopping all daemons...");
        
        com.overdrive.app.launcher.AdbDaemonLauncher launcher = getAdbLauncher();
        
        // Step 1: Kill ALL watchdog scripts and write sentinels FIRST.
        // This prevents watchdogs from respawning daemons between kills.
        // Must happen before any daemon kill — otherwise the watchdog sees the
        // daemon die and immediately relaunches it.
        Log.i(TAG, "Killing watchdog scripts and writing sentinels...");
        String killWatchdogsCmd =
                // Camera daemon: sentinel + watchdog + lock
                "echo 'disabled for update at $(date)' > /data/local/tmp/camera_daemon.disabled; " +
                "pkill -9 -f 'start_cam_daemon' 2>/dev/null; " +
                "rm -f /data/local/tmp/start_cam_daemon.sh /data/local/tmp/cam_watchdog.pid 2>/dev/null; " +
                // ACC sentry daemon: watchdog + lock
                "pkill -9 -f 'start_acc_sentry' 2>/dev/null; " +
                "rm -f /data/local/tmp/start_acc_sentry.sh /data/local/tmp/acc_sentry_daemon.lock 2>/dev/null; " +
                "echo done";
        
        final boolean[] wdDone = {false};
        launcher.executeShellCommand(killWatchdogsCmd, new com.overdrive.app.launcher.AdbDaemonLauncher.LaunchCallback() {
            @Override public void onLog(String m) {}
            @Override public void onLaunched() {
                Log.i(TAG, "Watchdog scripts killed");
                wdDone[0] = true;
                synchronized (wdDone) { wdDone.notify(); }
            }
            @Override public void onError(String e) {
                Log.w(TAG, "Watchdog kill: " + e);
                wdDone[0] = true;
                synchronized (wdDone) { wdDone.notify(); }
            }
        });
        try {
            synchronized (wdDone) {
                if (!wdDone[0]) wdDone.wait(5000);
            }
        } catch (InterruptedException ignored) {}
        
        // Brief pause to let watchdog processes fully exit before killing daemons
        try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
        
        // Step 2: Kill all daemon processes.
        // Watchdogs are already dead so nothing will respawn these.
        String[] daemons = {"acc_sentry_daemon", "byd_cam_daemon", "sentry_daemon", 
                "telegram_bot_daemon", "sentry_proxy", "cloudflared", "zrok", "sing-box"};
        
        for (String daemon : daemons) {
            final boolean[] done = {false};
            launcher.killDaemon(daemon, new com.overdrive.app.launcher.AdbDaemonLauncher.LaunchCallback() {
                @Override public void onLog(String m) {}
                @Override public void onLaunched() {
                    Log.i(TAG, "Stopped: " + daemon);
                    done[0] = true;
                    synchronized (done) { done.notify(); }
                }
                @Override public void onError(String e) {
                    Log.w(TAG, "Stop " + daemon + ": " + e);
                    done[0] = true;
                    synchronized (done) { done.notify(); }
                }
            });
            
            try {
                synchronized (done) {
                    if (!done[0]) done.wait(5000);
                }
            } catch (InterruptedException ignored) {}
        }
        
        // Step 3: Final sweep — catch any stragglers that slipped through.
        // This handles edge cases where a watchdog respawned a daemon in the
        // brief window between step 1 and step 2, or orphaned shell processes.
        Log.i(TAG, "Final sweep for remaining processes...");
        String finalSweepCmd =
                "pkill -9 -f 'start_cam_daemon' 2>/dev/null; " +
                "pkill -9 -f 'start_acc_sentry' 2>/dev/null; " +
                "pkill -9 -f 'byd_cam_daemon' 2>/dev/null; " +
                "pkill -9 -f 'acc_sentry_daemon' 2>/dev/null; " +
                "pkill -9 -f 'sentry_daemon' 2>/dev/null; " +
                "rm -f /data/local/tmp/camera_daemon.lock 2>/dev/null; " +
                "rm -f /data/local/tmp/acc_sentry_daemon.lock 2>/dev/null; " +
                // Clean up sentinel — the new app version's daemon launcher clears it
                // on startup anyway, but remove it here too in case the install fails
                // and the user manually restarts. Without this, the watchdog would
                // refuse to start the camera daemon.
                "rm -f /data/local/tmp/camera_daemon.disabled 2>/dev/null; " +
                "echo done";
        
        final boolean[] sweepDone = {false};
        launcher.executeShellCommand(finalSweepCmd, new com.overdrive.app.launcher.AdbDaemonLauncher.LaunchCallback() {
            @Override public void onLog(String m) {}
            @Override public void onLaunched() {
                sweepDone[0] = true;
                synchronized (sweepDone) { sweepDone.notify(); }
            }
            @Override public void onError(String e) {
                sweepDone[0] = true;
                synchronized (sweepDone) { sweepDone.notify(); }
            }
        });
        try {
            synchronized (sweepDone) {
                if (!sweepDone[0]) sweepDone.wait(5000);
            }
        } catch (InterruptedException ignored) {}
        
        Log.i(TAG, "All daemons and watchdogs stopped");
    }

    /**
     * Extract version from APK filename including channel.
     * "overdrive-release-alpha-v6.1.apk" → "alpha-v6.1"
     * "overdrive-release-prod-v2.0.1.apk" → "prod-v2.0.1"
     */
    static String extractVersion(String apkName) {
        if (apkName != null) {
            // Try to match channel-version pattern: alpha-v6.1, prod-v2.0
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("(alpha|debug|prod|beta)-v?(\\d+\\.\\d+(?:\\.\\d+)?)")
                    .matcher(apkName);
            if (m.find()) return m.group(1) + "-v" + m.group(2);

            // Fallback: just version number
            m = java.util.regex.Pattern.compile("v?(\\d+\\.\\d+(?:\\.\\d+)?)").matcher(apkName);
            if (m.find()) return "v" + m.group(1);
        }
        return "unknown";
    }

    static boolean isNewerVersion(String local, String remote) {
        try {
            String[] lp = local.split("\\.");
            String[] rp = remote.split("\\.");
            int len = Math.max(lp.length, rp.length);
            for (int i = 0; i < len; i++) {
                int l = i < lp.length ? Integer.parseInt(lp[i].replaceAll("[^0-9]", "")) : 0;
                int r = i < rp.length ? Integer.parseInt(rp[i].replaceAll("[^0-9]", "")) : 0;
                if (r > l) return true;
                if (r < l) return false;
            }
            return false;
        } catch (Exception e) {
            return !local.equals(remote);
        }
    }

    private String getLastUpdateTimestamp() {
        // Try SharedPreferences first (fast)
        String ts = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(PREF_LAST_UPDATE_TIME, "");
        if (!ts.isEmpty()) return ts;

        // Fall back to filesystem (survives app reinstall)
        try {
            File f = new File(UPDATE_TIMESTAMP_FILE);
            if (f.exists()) {
                java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(f));
                ts = r.readLine();
                r.close();
                if (ts != null && !ts.isEmpty()) {
                    // Sync back to SharedPreferences
                    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                            .edit().putString(PREF_LAST_UPDATE_TIME, ts).apply();
                    return ts;
                }
            }
        } catch (Exception ignored) {}
        return "";
    }

    private void saveLastUpdateTimestamp(String timestamp) {
        if (timestamp == null) return;
        // Use commit() (synchronous) — process may be killed right after
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(PREF_LAST_UPDATE_TIME, timestamp).commit();
        // Also save to filesystem via ADB shell (survives reinstall, app can't write /data/local/tmp directly)
        try {
            getAdbLauncher().executeShellCommand("echo '" + timestamp + "' > " + UPDATE_TIMESTAMP_FILE,
                    new com.overdrive.app.launcher.AdbDaemonLauncher.LaunchCallback() {
                @Override public void onLog(String m) {}
                @Override public void onLaunched() {}
                @Override public void onError(String error) {
                    Log.w(TAG, "Failed to save timestamp to file: " + error);
                }
            });
        } catch (Exception ignored) {}
    }

    /**
     * Persist version string to filesystem so the daemon process can read it.
     * SharedPreferences are per-process and may not be accessible from the daemon.
     */
    private void persistVersionToFile(String version) {
        if (version == null || version.isEmpty()) return;
        try {
            getAdbLauncher().executeShellCommand("echo '" + version + "' > " + VERSION_FILE,
                    new com.overdrive.app.launcher.AdbDaemonLauncher.LaunchCallback() {
                @Override public void onLog(String m) {}
                @Override public void onLaunched() {}
                @Override public void onError(String error) {
                    Log.w(TAG, "Failed to save version to file: " + error);
                }
            });
        } catch (Exception ignored) {}
    }

    private void postError(UpdateCallback cb, String msg) {
        mainHandler.post(() -> cb.onError(msg));
    }
    private void postInstallError(InstallCallback cb, String msg) {
        mainHandler.post(() -> cb.onError(msg));
    }
    private void postProgress(InstallCallback cb, String msg) {
        mainHandler.post(() -> cb.onProgress(msg));
    }

    /**
     * Check if app was just updated and return the version string.
     * Clears the flag after reading so it only shows once.
     */
    public static String consumeJustUpdatedVersion(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        if (prefs.getBoolean(PREF_JUST_UPDATED, false)) {
            String version = prefs.getString(PREF_UPDATED_VERSION, "");
            // Only clear the flag, keep the version for display
            prefs.edit()
                    .putBoolean(PREF_JUST_UPDATED, false)
                    .apply();
            return version;
        }
        return null;
    }

    /**
     * Get the display version string (channel + version from APK name).
     * Falls back to BuildConfig.VERSION_NAME if no remote version is known.
     */
    public static String getDisplayVersion(Context context) {
        String stored = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(PREF_UPDATED_VERSION, null);
        return stored != null ? stored : BuildConfig.VERSION_NAME;
    }

    /**
     * Get the display version without requiring a Context.
     * Reads from the persisted version file (written by the app process via ADB shell).
     * Falls back to BuildConfig.VERSION_NAME if the file doesn't exist.
     * Used by the daemon process (HttpServer) where SharedPreferences may not be accessible.
     */
    public static String getDisplayVersionFromFile() {
        try {
            java.io.File f = new java.io.File(VERSION_FILE);
            if (f.exists()) {
                java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(f));
                String version = reader.readLine();
                reader.close();
                if (version != null && !version.trim().isEmpty()) {
                    return version.trim();
                }
            }
        } catch (Exception ignored) {}
        return BuildConfig.VERSION_NAME;
    }
}
