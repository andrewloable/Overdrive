package com.overdrive.app.updater;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.overdrive.app.BuildConfig;
import com.overdrive.app.launcher.AdbShellExecutor;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
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
    // Sentinels for the post-update handshake (see UpdateLifecycle).
    private static final String UPDATE_IN_PROGRESS_FILE = UpdateLifecycle.UPDATE_IN_PROGRESS_FILE;
    private static final String POST_UPDATE_FILE = UpdateLifecycle.POST_UPDATE_FILE;

    private final Context context;
    private volatile boolean cancelled = false;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    // Null in the daemon process — Looper.getMainLooper() returns null when no
    // thread has been designated as the main looper (the daemon's main() only
    // does Looper.prepare(), not prepareMainLooper()). Callbacks fall back to
    // inline execution; see runCallback().
    private final Handler mainHandler = resolveMainHandler();

    private static Handler resolveMainHandler() {
        Looper looper = Looper.getMainLooper();
        return looper != null ? new Handler(looper) : null;
    }

    private void runCallback(Runnable r) {
        if (mainHandler != null) mainHandler.post(r);
        else r.run();
    }
    private AdbShellExecutor adb; // Lazy — only created when install is triggered
    private com.overdrive.app.launcher.AdbDaemonLauncher adbLauncher; // For daemon management

    private String latestDownloadUrl;
    private String releaseNotes;
    private String remoteVersion;
    private String remoteUpdatedAt;
    private ReleaseManifest releaseManifest;

    private static final String EXPECTED_PACKAGE_NAME = "com.overdrive.app";
    private static final String RELEASE_MANIFEST_ASSET = "overdrive-update.json";

    private static final class ReleaseManifest {
        final String packageName;
        final String versionName;
        final long versionCode;
        final String apkAssetName;
        final String apkSha256;
        final long apkSize;
        final String signingCertSha256;
        final long minSupportedVersionCode;

        private ReleaseManifest(String packageName, String versionName, long versionCode,
                                String apkAssetName, String apkSha256, long apkSize,
                                String signingCertSha256, long minSupportedVersionCode) {
            this.packageName = packageName;
            this.versionName = versionName;
            this.versionCode = versionCode;
            this.apkAssetName = apkAssetName;
            this.apkSha256 = apkSha256;
            this.apkSize = apkSize;
            this.signingCertSha256 = signingCertSha256;
            this.minSupportedVersionCode = minSupportedVersionCode;
        }
    }

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
     * Run a shell command, picking the right executor for the current process.
     *
     * The app process (UID 10xxx) needs to elevate to UID 2000 to write
     * /data/local/tmp and call `pm install`, so it goes through the ADB-shell
     * tunnel ({@link com.overdrive.app.launcher.AdbDaemonLauncher}).
     *
     * The daemon process is ALREADY UID 2000 (it was launched via app_process
     * by the same ADB tunnel at startup), so it can — and must — execute
     * shell commands directly. Routing daemon-side calls through the ADB
     * tunnel fails on every BYD head unit because dadb tries to read the
     * app's adbkey at /data/user/0/com.overdrive.app/files/adbkey, and that
     * directory is mode 0700 owned by the app UID — UID 2000 can't open it
     * (EACCES). The user reported this as
     * "Install failed: Download failed: ERROR: Execution failed:
     *  /data/user/0/com.overdrive.app/files/adbkey: open failed:
     *  EACCES (Permission denied)".
     *
     * Direct exec is also faster (no socket round-trip per command).
     *
     * Callback semantics match {@link com.overdrive.app.launcher.AdbDaemonLauncher.LaunchCallback}:
     * onLog gets the combined stdout/stderr, then onLaunched fires on success
     * (exit 0) or onError on a non-zero exit / spawn failure.
     */
    private void runShell(String command,
                          com.overdrive.app.launcher.AdbDaemonLauncher.LaunchCallback callback) {
        if (android.os.Process.myUid() != 2000) {
            getAdbLauncher().executeShellCommand(command, callback);
            return;
        }
        // Daemon path — exec directly. Runs SYNCHRONOUSLY on the caller's
        // thread (don't bounce onto AppUpdater.executor — downloadAndInstall
        // already runs there and is single-threaded, so queueing more work
        // would deadlock against the wait() that follows each call site).
        // The synchronous callback fires before return, so the caller's
        // notify/wait pattern still works (the wait sees the done flag
        // already true and short-circuits).
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", command});
            StringBuilder out = new StringBuilder();
            java.io.BufferedReader stdout = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream()));
            java.io.BufferedReader stderr = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getErrorStream()));
            String line;
            while ((line = stdout.readLine()) != null) {
                out.append(line).append('\n');
            }
            while ((line = stderr.readLine()) != null) {
                out.append(line).append('\n');
            }
            int exit = p.waitFor();
            String combined = out.toString().trim();
            if (!combined.isEmpty()) callback.onLog(combined);
            if (exit == 0) {
                callback.onLaunched();
            } else {
                callback.onError("Exit " + exit + ": " + combined);
            }
        } catch (Exception e) {
            callback.onError("Execution failed: " + e.getMessage());
        }
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
            // Also age out a stale Telegram post-update hint older than 24h —
            // if Telegram never came back online to consume it, the user has
            // already noticed the URL change through other means and a "you
            // were just updated" message would be confusing days later.
            String cmd = "rm -f " + APK_PATH + "; " +
                    "find " + UpdateLifecycle.TELEGRAM_POST_UPDATE_HINT_FILE +
                    " -mmin +1440 -delete 2>/dev/null; echo done";
            runShell(cmd, new com.overdrive.app.launcher.AdbDaemonLauncher.LaunchCallback() {
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
            runCallback(() -> callback.onNoUpdate(getDisplayVersion(context)));
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
                        postError(callback, "No release assets found; cause: GitHub release has no downloadable files; fix: attach overdrive-update.json and the matching APK asset");
                        return;
                    }

                    JSONObject manifestAsset = findAssetByName(assets, RELEASE_MANIFEST_ASSET);
                    if (manifestAsset == null) {
                        postError(callback, "Release manifest missing; cause: no " + RELEASE_MANIFEST_ASSET + " asset was attached; fix: publish a manifest that names the APK, checksum, size, package, and signing cert");
                        return;
                    }

                    String manifestUrl = manifestAsset.optString("browser_download_url", "");
                    if (!isHttpsUrl(manifestUrl)) {
                        postError(callback, "Invalid release manifest URL; cause: manifest URL is not HTTPS; fix: republish the manifest over HTTPS only");
                        return;
                    }

                    String manifestBody = downloadText(client, manifestUrl);
                    releaseManifest = parseReleaseManifest(manifestBody);
                    if (releaseManifest == null) {
                        postError(callback, "Release manifest malformed; cause: required fields were missing or invalid; fix: ensure overdrive-update.json includes packageName, versionName, versionCode, apkAssetName, apkSha256, apkSize, signingCertSha256");
                        return;
                    }
                    if (!EXPECTED_PACKAGE_NAME.equals(releaseManifest.packageName)) {
                        postError(callback, "Release manifest package mismatch; cause: expected " + EXPECTED_PACKAGE_NAME + " but found " + releaseManifest.packageName + "; fix: publish a manifest for the OverDrive package");
                        return;
                    }

                    JSONObject apkAsset = findAssetByName(assets, releaseManifest.apkAssetName);
                    if (apkAsset == null) {
                        postError(callback, "Release APK missing; cause: manifest names " + releaseManifest.apkAssetName + " but that asset is not attached; fix: upload the APK under the manifest's apkAssetName");
                        return;
                    }

                    String apkUrl = apkAsset.optString("browser_download_url", "");
                    if (!isHttpsUrl(apkUrl)) {
                        postError(callback, "Invalid APK URL; cause: release asset is not HTTPS; fix: republish the APK over HTTPS only");
                        return;
                    }

                    String updatedAt = apkAsset.optString("updated_at", "");
                    latestDownloadUrl = apkUrl;
                    remoteUpdatedAt = updatedAt;

                    // Prefer the manifest version so the updater no longer
                    // guesses from the file name.
                    remoteVersion = releaseManifest.versionName != null && !releaseManifest.versionName.isEmpty()
                            ? releaseManifest.versionName
                            : extractVersion(releaseManifest.apkAssetName);
                    // Report what AppUpdater previously persisted so the
                    // user-facing "you're on version X" matches what
                    // SettingsAboutFragment shows. BuildConfig.VERSION_NAME
                    // is the gradle stub and unrelated to GitHub releases.
                    String currentVersion = getDisplayVersion(context);
                    long currentVersionCode = getInstalledVersionCode();
                    if (currentVersionCode >= 0 && releaseManifest.versionCode <= currentVersionCode) {
                        Log.i(TAG, "Skipping release versionCode=" + releaseManifest.versionCode +
                                " because installed versionCode=" + currentVersionCode);
                        runCallback(() -> callback.onNoUpdate(currentVersion));
                        return;
                    }

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
                        runCallback(() -> callback.onNoUpdate(currentVersion));
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
                            runCallback(() -> callback.onNoUpdate(currentVersion));
                            return;
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "Could not check install time: " + e.getMessage());
                    }

                    Log.i(TAG, "Channel: " + channel + ", Current: " + currentVersion +
                            ", Remote: " + remoteVersion + ", APK updated: " + updatedAt +
                            ", Last installed: " + lastInstalledTimestamp);

                    if (apkUpdated) {
                        runCallback(() -> callback.onUpdateAvailable(
                                currentVersion, remoteVersion, releaseNotes));
                    } else {
                        runCallback(() -> callback.onNoUpdate(currentVersion));
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
                runCallback(() -> callback.onDownloadProgress(-1)); // -1 = indeterminate

                String downloadCmd = buildDownloadCommand(latestDownloadUrl, APK_PATH);
                
                final boolean[] dlDone = {false};
                final String[] dlResult = {null};

                runShell(downloadCmd, new com.overdrive.app.launcher.AdbDaemonLauncher.LaunchCallback() {
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
                    runShell("rm -f " + APK_PATH, new com.overdrive.app.launcher.AdbDaemonLauncher.LaunchCallback() {
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

                runCallback(() -> callback.onDownloadProgress(100));

                // Step 2: Verify APK size via shell
                postProgress(callback, "Verifying download...");
                if (releaseManifest == null) {
                    runShell("rm -f " + APK_PATH, new com.overdrive.app.launcher.AdbDaemonLauncher.LaunchCallback() {
                        @Override public void onLog(String m) {}
                        @Override public void onLaunched() {}
                        @Override public void onError(String e) {}
                    });
                    postInstallError(callback, "Release manifest unavailable; cause: update check did not fetch overdrive-update.json; fix: rerun the update check so the manifest can be validated before install");
                    return;
                }

                final boolean[] szDone = {false};
                final String[] szResult = {null};
                runShell("stat -c%s " + APK_PATH + " 2>/dev/null || echo 0",
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
                if (fileSize <= 0) {
                    runShell("rm -f " + APK_PATH, new com.overdrive.app.launcher.AdbDaemonLauncher.LaunchCallback() {
                        @Override public void onLog(String m) {}
                        @Override public void onLaunched() {}
                        @Override public void onError(String e) {}
                    });
                    postInstallError(callback, "Invalid APK (size: " + fileSize + "); cause: download is missing or empty; fix: republish the release asset and try again");
                    return;
                }

                if (releaseManifest.apkSize > 0 && fileSize != releaseManifest.apkSize) {
                    runShell("rm -f " + APK_PATH, new com.overdrive.app.launcher.AdbDaemonLauncher.LaunchCallback() {
                        @Override public void onLog(String m) {}
                        @Override public void onLaunched() {}
                        @Override public void onError(String e) {}
                    });
                    postInstallError(callback, "APK size mismatch; cause: expected " + releaseManifest.apkSize + " bytes but downloaded " + fileSize + " bytes; fix: republish the exact APK named in the manifest");
                    return;
                }

                String downloadedSha256 = sha256Hex(new File(APK_PATH));
                if (downloadedSha256 == null || !downloadedSha256.equalsIgnoreCase(releaseManifest.apkSha256)) {
                    runShell("rm -f " + APK_PATH, new com.overdrive.app.launcher.AdbDaemonLauncher.LaunchCallback() {
                        @Override public void onLog(String m) {}
                        @Override public void onLaunched() {}
                        @Override public void onError(String e) {}
                    });
                    postInstallError(callback, "APK checksum mismatch; cause: downloaded SHA-256 did not match manifest; fix: republish the APK and manifest together");
                    return;
                }

                if (!verifyArchiveMetadata(new File(APK_PATH), releaseManifest)) {
                    runShell("rm -f " + APK_PATH, new com.overdrive.app.launcher.AdbDaemonLauncher.LaunchCallback() {
                        @Override public void onLog(String m) {}
                        @Override public void onLaunched() {}
                        @Override public void onError(String e) {}
                    });
                    postInstallError(callback, "APK metadata mismatch; cause: package name, versionCode, or signing cert did not match the manifest; fix: republish the APK with matching release metadata");
                    return;
                }

                // Step 3: Save update info BEFORE we touch any daemon (the daemon
                // process — if we're running inside it — is about to die, and the
                // app process gets killed by `pm install -r`; either way the
                // SharedPreferences write must happen first).
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit()
                        .putBoolean(PREF_JUST_UPDATED, true)
                        .putString(PREF_UPDATED_VERSION, remoteVersion)
                        .commit();
                persistVersionToFile(remoteVersion);
                saveLastUpdateTimestamp(remoteUpdatedAt);

                // Step 4 & 5: Stop daemons + install + relaunch. The control flow
                // splits here based on which process we're in:
                //
                //   App process (UID 10xxx) — the existing synchronous flow works.
                //   The app talks to UID 2000 over the dadb tunnel; that tunnel
                //   outlives our app process when `pm install -r` replaces it,
                //   and the same tunnel runs `am start` after the install lands.
                //
                //   Daemon process (UID 2000) — we ARE one of the processes the
                //   stop step kills, so we cannot supervise the install ourselves.
                //   Instead, write a self-contained install script to
                //   /data/local/tmp/ and kick it off detached (subshell + closed
                //   stdio so init reparents it), then return. The script runs
                //   on its own; our death is fine.
                if (android.os.Process.myUid() == 2000) {
                    postProgress(callback, "Stopping daemons & installing...");
                    runDetachedInstall(callback);
                    return;
                }

                postProgress(callback, "Stopping daemons...");
                stopAllDaemons();
                Thread.sleep(3000);

                postProgress(callback, "Installing...");
                final boolean[] done = {false};
                final String[] result = {null};

                String installCmd = "pm install -r " + APK_PATH +
                    "; rm -f " + APK_PATH +
                    "; sleep 2; am start -n com.overdrive.app/.ui.MainActivity" +
                    " --ez " + UpdateLifecycle.EXTRA_POST_UPDATE + " true";

                runShell(installCmd, new com.overdrive.app.launcher.AdbDaemonLauncher.LaunchCallback() {
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
                    // Wipe the post-update sentinels — install never landed, so
                    // there's nothing for the next launch to recover from.
                    runShell(
                            "rm -f " + UPDATE_IN_PROGRESS_FILE + " " + POST_UPDATE_FILE,
                            new com.overdrive.app.launcher.AdbDaemonLauncher.LaunchCallback() {
                                @Override public void onLog(String m) {}
                                @Override public void onLaunched() {}
                                @Override public void onError(String e) {}
                            });
                    postInstallError(callback, "Install failed: " + output);
                } else {
                    postProgress(callback, "✅ Update installed! Restarting...");
                    runCallback(callback::onSuccess);
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

    private String downloadText(OkHttpClient client, String url) throws Exception {
        Request request = new Request.Builder()
                .url(url)
                .header("Accept", "application/octet-stream")
                .build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IllegalStateException("HTTP " + response.code());
            }
            return response.body().string();
        }
    }

    private JSONObject findAssetByName(JSONArray assets, String name) {
        if (assets == null || name == null) return null;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.optJSONObject(i);
            if (asset != null && name.equals(asset.optString("name", ""))) {
                return asset;
            }
        }
        return null;
    }

    private boolean isHttpsUrl(String url) {
        return url != null && url.startsWith("https://");
    }

    private ReleaseManifest parseReleaseManifest(String body) {
        try {
            JSONObject json = new JSONObject(body);
            String packageName = json.optString("packageName", "");
            String versionName = json.optString("versionName", "");
            long versionCode = json.optLong("versionCode", -1);
            String apkAssetName = json.optString("apkAssetName", "");
            String apkSha256 = json.optString("apkSha256", "");
            long apkSize = json.optLong("apkSize", -1);
            String signingCertSha256 = json.optString("signingCertSha256", "");
            long minSupportedVersionCode = json.optLong("minSupportedVersionCode", 0);

            if (packageName.isEmpty() || versionName.isEmpty() || versionCode < 0 ||
                    apkAssetName.isEmpty() || apkSha256.isEmpty() || apkSize <= 0 ||
                    signingCertSha256.isEmpty()) {
                return null;
            }

            return new ReleaseManifest(
                    packageName,
                    versionName,
                    versionCode,
                    apkAssetName,
                    apkSha256,
                    apkSize,
                    signingCertSha256,
                    minSupportedVersionCode);
        } catch (Exception e) {
            return null;
        }
    }

    private String sha256Hex(File file) {
        if (file == null || !file.exists() || !file.canRead()) {
            return null;
        }
        try (FileInputStream in = new FileInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            return bytesToHex(digest.digest());
        } catch (Exception e) {
            return null;
        }
    }

    private boolean verifyArchiveMetadata(File apkFile, ReleaseManifest manifest) {
        if (apkFile == null || manifest == null) {
            return false;
        }
        try {
            PackageManager pm = context.getPackageManager();
            int flags = PackageManager.GET_SIGNING_CERTIFICATES;
            PackageInfo info = pm.getPackageArchiveInfo(apkFile.getAbsolutePath(), flags);
            if (info == null || info.packageName == null) {
                return false;
            }
            if (!manifest.packageName.equals(info.packageName)) {
                return false;
            }
            long versionCode = info.getLongVersionCode();
            if (manifest.versionCode >= 0 && versionCode != manifest.versionCode) {
                return false;
            }
            Signature[] signatures = extractArchiveSignatures(info);
            if (signatures == null || signatures.length == 0) {
                return false;
            }
            String certSha256 = bytesToHex(MessageDigest.getInstance("SHA-256")
                    .digest(signatures[0].toByteArray()));
            return manifest.signingCertSha256.equalsIgnoreCase(certSha256);
        } catch (Exception e) {
            Log.w(TAG, "Archive metadata verification failed: " + e.getMessage());
            return false;
        }
    }

    private Signature[] extractArchiveSignatures(PackageInfo info) {
        if (info == null) {
            return null;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && info.signingInfo != null) {
            if (info.signingInfo.hasMultipleSigners()) {
                return info.signingInfo.getApkContentsSigners();
            }
            return info.signingInfo.getSigningCertificateHistory();
        }
        return info.signatures;
    }

    private String bytesToHex(byte[] data) {
        if (data == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) {
            sb.append(String.format(java.util.Locale.US, "%02x", b));
        }
        return sb.toString();
    }

    /**
     * Daemon-process install path: write a self-contained install script and
     * fire it off detached (setsid + null fds) so it survives our death.
     *
     * Why a script instead of a single `runShell(...)`: this very process is
     * one of the things `pkill -9 -f byd_cam_daemon` will kill. If the kill
     * runs in our own shell we'd suicide before `pm install` ever fires. The
     * script is launched with setsid into a new session with closed std fds,
     * so the kernel doesn't reap it when the daemon dies.
     *
     * The script: kills watchdogs first (so they can't respawn the daemons
     * we're about to kill), kills daemons (including us), runs `pm install
     * -r`, then `am start` to relaunch the app. Same sequence the
     * synchronous app-process flow uses, just packaged so the caller can
     * exit before it runs.
     *
     * The webapp tracks success via /api/update/progress + the app coming
     * back online; the SharedPreferences `PREF_JUST_UPDATED` flag we wrote
     * in step 3 is what the new MainActivity reads to confirm.
     */
    private void runDetachedInstall(InstallCallback callback) {
        String scriptPath = "/data/local/tmp/overdrive_install.sh";
        String logPath = "/data/local/tmp/overdrive_install.log";

        StringBuilder script = new StringBuilder();
        script.append("#!/system/bin/sh\n");
        script.append("set +e\n");
        script.append("exec >").append(logPath).append(" 2>&1\n");
        script.append("echo \"[install] starting at $(date)\"\n");
        // Step 1: plant sentinels so the new MainActivity recovers correctly.
        script.append("echo 'update at '$(date) > ").append(UPDATE_IN_PROGRESS_FILE).append("\n");
        script.append("echo 'update at '$(date) > ").append(POST_UPDATE_FILE).append("\n");
        // Step 2: kill watchdogs FIRST so they can't respawn the daemons we're
        // about to kill in step 3. Mirrors DaemonLauncher.killDaemonViaAdb's
        // ordering — if the daemon is killed before its watchdog, the watchdog
        // observes the death and immediately relaunches it. Disable sentinel
        // is also planted so any watchdog we miss exits on its next iteration.
        script.append("echo 'disabled for update at '$(date) > /data/local/tmp/camera_daemon.disabled\n");
        script.append("pkill -9 -f 'start_cam_daemon' 2>/dev/null\n");
        script.append("pkill -9 -f 'start_acc_sentry' 2>/dev/null\n");
        script.append("rm -f /data/local/tmp/start_cam_daemon.sh /data/local/tmp/cam_watchdog.pid 2>/dev/null\n");
        script.append("rm -f /data/local/tmp/start_acc_sentry.sh /data/local/tmp/acc_sentry_daemon.lock 2>/dev/null\n");
        script.append("sleep 1\n");

        // Step 3: kill the daemons themselves. Mirrors DaemonLauncher per-daemon
        // UID-2000 recipes (see killDaemonViaAdb at DaemonLauncher.kt:1457):
        //
        //   - acc_sentry_daemon  → pkill -f 'acc_sentry' (broader pattern so
        //                          stragglers from start_acc_sentry also die)
        //   - byd_cam_daemon     → pkill -f start_cam_daemon (already done in
        //                          step 2) THEN pkill -f byd_cam_daemon. We're
        //                          one of the matches; the script survives
        //                          because the (...&) wrapper put it in a
        //                          separate process group reparented to init.
        //   - sentry_daemon, telegram_bot_daemon, sentry_proxy
        //                        → simple pkill -f + killall by exe name
        //   - cloudflared, zrok, tailscaled, sing-box (native binaries)
        //                        → killall -9 by exe name (matches argv[0]
        //                          basename more reliably than pkill -f does
        //                          across BYD toybox vintages)
        //
        // Each kill is `2>/dev/null` so a "no such process" exit code doesn't
        // abort the script (we have set +e but a stale errexit from a sourced
        // env would still be a risk).
        script.append("pkill -9 -f 'acc_sentry' 2>/dev/null\n");
        script.append("pkill -9 -f 'byd_cam_daemon' 2>/dev/null\n");
        script.append("killall -9 byd_cam_daemon 2>/dev/null\n");
        script.append("pkill -9 -f 'sentry_daemon' 2>/dev/null\n");
        script.append("pkill -9 -f 'telegram_bot_daemon' 2>/dev/null\n");
        script.append("pkill -9 -f 'sentry_proxy' 2>/dev/null\n");
        script.append("pkill -9 -f 'cloudflared' 2>/dev/null\n");
        script.append("killall -9 cloudflared 2>/dev/null\n");
        script.append("pkill -9 -f 'zrok' 2>/dev/null\n");
        script.append("killall -9 zrok 2>/dev/null\n");
        script.append("pkill -9 -f 'sing-box' 2>/dev/null\n");
        script.append("killall -9 sing-box 2>/dev/null\n");
        script.append("pkill -9 -f 'tailscaled' 2>/dev/null\n");
        script.append("killall -9 tailscaled 2>/dev/null\n");

        // Per-daemon lock files (mirrors DaemonLauncher's killDaemonViaAdb
        // cleanup) so the relaunched MainActivity's daemon supervisor doesn't
        // refuse to start because a stale lock looks alive.
        script.append("rm -f /data/local/tmp/camera_daemon.lock 2>/dev/null\n");
        script.append("rm -f /data/local/tmp/acc_sentry_daemon.lock 2>/dev/null\n");
        script.append("rm -f /data/local/tmp/telegram_bot_daemon.lock 2>/dev/null\n");
        script.append("rm -f /data/local/tmp/*_daemon.lock 2>/dev/null\n");
        script.append("rm -f /data/local/tmp/cam_watchdog.pid 2>/dev/null\n");
        // Clear the camera disable sentinel — we needed it set above so
        // any surviving watchdog exits, but the new MainActivity must not
        // see it on startup or it'll leave the camera daemon disabled.
        // POST_UPDATE_FILE / UPDATE_IN_PROGRESS_FILE stay in place; the new
        // process consumes them via UpdateLifecycle.
        script.append("rm -f /data/local/tmp/camera_daemon.disabled 2>/dev/null\n");
        script.append("sleep 2\n");
        // Step 4: install. `pm install -r` replaces the existing app (-r).
        // Stdout is captured into PM_OUT so step 4b can include the failure
        // reason in the progress JSON if `pm install` exits non-zero.
        script.append("echo \"[install] running pm install\"\n");
        script.append("PM_OUT=$(pm install -r ").append(APK_PATH).append(" 2>&1)\n");
        script.append("INSTALL_RC=$?\n");
        script.append("echo \"$PM_OUT\"\n");
        script.append("rm -f ").append(APK_PATH).append("\n");
        // Step 4b: on `pm install` failure, write phase=error to the progress
        // JSON so the webapp's poller surfaces the failure instead of sitting
        // in reconnect-mode forever waiting for an upgraded daemon that will
        // never appear. Same shape as UpdateApiHandler.writeProgress so the
        // /api/update/progress endpoint and update-flow.js consume it without
        // changes. We do this before `am start` so the relaunched app reports
        // the error in its own UI, and the failure write happens BEFORE the
        // sentinels are cleared (the new MainActivity reads PROGRESS_FILE on
        // start and can roll back PREF_JUST_UPDATED if it sees phase=error).
        // `pm install` Success path skips this block entirely; the daemon
        // restart that follows `am start` re-writes phase=installing/100 and
        // eventually a fresh daemon overwrites with idle.
        script.append("if [ \"$INSTALL_RC\" != \"0\" ]; then\n");
        // Escape special chars in PM_OUT for JSON. toybox lacks `jq`, but we
        // can substitute backslashes, double-quotes, and newlines via
        // parameter expansion (POSIX) — covers the ~99% case of pm install's
        // single-line failure messages like "Failure [INSTALL_PARSE_FAILED…]".
        // Strip ALL control chars first (`tr -d '\000-\037'`) — a stray tab
        // or stack-trace embedded NUL would produce technically-invalid JSON
        // and JSONObject would throw, swallowing the error in the consumer.
        script.append("  PM_ESC=$(printf %s \"$PM_OUT\" | tr -d '\\000-\\037' | ");
        script.append("sed 's/\\\\/\\\\\\\\/g;s/\"/\\\\\"/g')\n");
        script.append("  TS=$(($(date +%s) * 1000))\n");
        script.append("  printf '{\"phase\":\"error\",\"percent\":-1,");
        script.append("\"message\":\"Install failed\",\"error\":\"%s\",\"ts\":%s}' ");
        script.append("\"$PM_ESC\" \"$TS\" > /data/local/tmp/overdrive_update_progress.json\n");
        script.append("  echo \"[install] FAILED rc=$INSTALL_RC\"\n");
        // Clear the in-progress sentinel so the new MainActivity doesn't run a
        // post-update hard-reset for an install that never landed. Keep
        // POST_UPDATE_FILE — its presence on a still-old-version app is the
        // signal MainActivity uses to read PROGRESS_FILE and show the error.
        script.append("  rm -f ").append(UPDATE_IN_PROGRESS_FILE).append("\n");
        script.append("fi\n");
        // Step 5: relaunch. Runs in both success and failure cases so the user
        // gets the app back either way (with the new APK on success, or with
        // the old APK + an error toast on failure).
        script.append("sleep 2\n");
        script.append("am start -n com.overdrive.app/.ui.MainActivity --ez ");
        script.append(UpdateLifecycle.EXTRA_POST_UPDATE).append(" true\n");
        script.append("echo \"[install] done rc=$INSTALL_RC at $(date)\"\n");

        try {
            // Write the script via a Runtime.exec heredoc — this is the
            // daemon process, so we have direct write access to /data/local/tmp.
            try (java.io.FileWriter w = new java.io.FileWriter(scriptPath)) {
                w.write(script.toString());
            }
            new java.io.File(scriptPath).setExecutable(true, false);

            // Detach: a subshell with `& exit 0` reparents the install script
            // to init (PID 1), so SIGTERM/SIGHUP from the daemon's death don't
            // reach it. We don't rely on `setsid` — toybox builds on BYD ROMs
            // are inconsistent about which applets ship. The script itself
            // does `exec >log 2>&1` to close all stdio, and `</dev/null` here
            // closes stdin so nothing keeps the parent waiting.
            ProcessBuilder pb = new ProcessBuilder(
                    "sh", "-c",
                    "(sh " + scriptPath + " </dev/null >/dev/null 2>&1 &)");
            pb.redirectErrorStream(true);
            pb.start();

            postProgress(callback, "Installing...");
            // Don't onSuccess here — the daemon process itself is about to be
            // killed by the script. Just return and let the script + webapp
            // poller take it from here.
            runCallback(callback::onSuccess);
        } catch (Exception e) {
            Log.e(TAG, "Detached install failed: " + e.getMessage());
            postInstallError(callback, "Detached install failed: " + e.getMessage());
        }
    }

    private void cleanup(String path) {
        try {
            runShell("rm -f " + path, new com.overdrive.app.launcher.AdbDaemonLauncher.LaunchCallback() {
                @Override public void onLog(String m) {}
                @Override public void onLaunched() {}
                @Override public void onError(String e) {}
            });
        } catch (Exception ignored) {}
    }

    private void stopAllDaemons() {
        Log.i(TAG, "Stopping all daemons...");

        com.overdrive.app.launcher.AdbDaemonLauncher launcher = getAdbLauncher();

        // Step 0: Plant the post-update sentinels so the new process knows to
        // run a hard-reset before starting daemons (see UpdateLifecycle). The
        // BootReceiver path is intentionally inert on MY_PACKAGE_REPLACED, so
        // the new MainActivity is the sole daemon orchestrator after install.
        final boolean[] markerDone = {false};
        String markerCmd =
                "echo 'update at $(date)' > " + UPDATE_IN_PROGRESS_FILE + "; " +
                "echo 'update at $(date)' > " + POST_UPDATE_FILE + "; " +
                "echo done";
        runShell(markerCmd, new com.overdrive.app.launcher.AdbDaemonLauncher.LaunchCallback() {
            @Override public void onLog(String m) {}
            @Override public void onLaunched() {
                markerDone[0] = true;
                synchronized (markerDone) { markerDone.notify(); }
            }
            @Override public void onError(String e) {
                Log.w(TAG, "Sentinel write: " + e);
                markerDone[0] = true;
                synchronized (markerDone) { markerDone.notify(); }
            }
        });
        try {
            synchronized (markerDone) {
                if (!markerDone[0]) markerDone.wait(3000);
            }
        } catch (InterruptedException ignored) {}

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
        runShell(killWatchdogsCmd, new com.overdrive.app.launcher.AdbDaemonLauncher.LaunchCallback() {
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
                "telegram_bot_daemon", "sentry_proxy", "cloudflared", "zrok", "sing-box",
                "tailscaled"};
        
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
        // NOTE: we keep UPDATE_IN_PROGRESS_FILE / POST_UPDATE_FILE in place;
        // the new process clears them after its own hard-reset pass.
        Log.i(TAG, "Final sweep for remaining processes...");
        String finalSweepCmd =
                "pkill -9 -f 'start_cam_daemon' 2>/dev/null; " +
                "pkill -9 -f 'start_acc_sentry' 2>/dev/null; " +
                "pkill -9 -f 'byd_cam_daemon' 2>/dev/null; " +
                "pkill -9 -f 'cam_daemon' 2>/dev/null; " +
                "pkill -9 -f 'acc_sentry_daemon' 2>/dev/null; " +
                "pkill -9 -f 'sentry_daemon' 2>/dev/null; " +
                "pkill -9 -f 'telegram_bot_daemon' 2>/dev/null; " +
                "pkill -9 -f 'sentry_proxy' 2>/dev/null; " +
                "pkill -9 -f 'cloudflared' 2>/dev/null; " +
                "pkill -9 -f 'zrok' 2>/dev/null; " +
                "pkill -9 -f 'sing-box' 2>/dev/null; " +
                "pkill -9 -f 'tailscaled' 2>/dev/null; " +
                "killall -9 cloudflared 2>/dev/null; " +
                "killall -9 zrok 2>/dev/null; " +
                "killall -9 tailscaled 2>/dev/null; " +
                "killall -9 sing-box 2>/dev/null; " +
                "rm -f /data/local/tmp/*_daemon.lock 2>/dev/null; " +
                "rm -f /data/local/tmp/cam_watchdog.pid 2>/dev/null; " +
                "rm -f /data/local/tmp/start_cam_daemon.sh /data/local/tmp/start_acc_sentry.sh 2>/dev/null; " +
                // Clear camera_daemon.disabled but keep the post-update sentinels
                // so the new process can detect and act on them.
                "rm -f /data/local/tmp/camera_daemon.disabled 2>/dev/null; " +
                "echo done";
        
        final boolean[] sweepDone = {false};
        runShell(finalSweepCmd, new com.overdrive.app.launcher.AdbDaemonLauncher.LaunchCallback() {
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
            runShell("echo '" + timestamp + "' > " + UPDATE_TIMESTAMP_FILE,
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
            runShell("echo '" + version + "' > " + VERSION_FILE,
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
        runCallback(() -> cb.onError(msg));
    }
    private void postInstallError(InstallCallback cb, String msg) {
        runCallback(() -> cb.onError(msg));
    }
    private void postProgress(InstallCallback cb, String msg) {
        runCallback(() -> cb.onProgress(msg));
    }

    /**
     * Check if app was just updated and return the version string.
     * Clears the flag after reading so it only shows once.
     *
     * Callers MUST call {@link #consumeFailedUpdateError(Context)} first.
     * That method clears PREF_JUST_UPDATED on a failed install (the flag is
     * set before `runDetachedInstall` so it's true on BOTH success and
     * failure paths; the progress JSON is the authoritative outcome
     * signal). After that, this method only returns a non-null version
     * when the install actually succeeded.
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
     * Read and clear a failed-install error written by the daemon-side
     * install script. Returns the error message (e.g. "Failure
     * [INSTALL_PARSE_FAILED_NO_CERTIFICATES]") when the script's
     * `pm install` exited non-zero, or null when there's no failure to
     * report. Called on app launch — together with
     * {@link #consumeJustUpdatedVersion(Context)} — so the user sees a
     * concrete error toast instead of a misleading success message or
     * silence.
     *
     * Also clears PREF_JUST_UPDATED. The flag is set BEFORE the install
     * script runs (at line 471 in downloadAndInstall) so it's "true" on
     * BOTH success and failure paths; we use the progress JSON as the
     * authoritative success/failure signal. If we left the flag set on
     * failure, the immediately-following consumeJustUpdatedVersion call
     * would see PREF_JUST_UPDATED=true with no failure marker (because we
     * deleted it here) and would fire the misleading "Updated to vX" toast
     * alongside the error toast.
     */
    public static String consumeFailedUpdateError(Context context) {
        if (!hasFailedUpdateMarker()) return null;
        String err = null;
        try {
            java.io.File f = new java.io.File("/data/local/tmp/overdrive_update_progress.json");
            StringBuilder sb = new StringBuilder();
            try (java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(new java.io.FileInputStream(f)))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
            }
            JSONObject j = new JSONObject(sb.toString());
            err = j.optString("error", null);
            if (err == null || err.isEmpty()) err = j.optString("message", "Install failed");
        } catch (Exception ignored) {}
        // One-shot: clear the just-updated flag AND delete the progress file
        // and post-update sentinel so the next launch is clean. The retry
        // will write a fresh record.
        try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(PREF_JUST_UPDATED, false)
                    .remove(PREF_UPDATED_VERSION)
                    .apply();
        } catch (Exception ignored) {}
        try { new java.io.File("/data/local/tmp/overdrive_update_progress.json").delete(); } catch (Exception ignored) {}
        try { new java.io.File(POST_UPDATE_FILE).delete(); } catch (Exception ignored) {}
        return err;
    }

    /**
     * True when the progress JSON exists and reports phase=error. Used as a
     * gate so consumeJustUpdatedVersion doesn't toast "Updated to vX" for an
     * install that never landed.
     */
    private static boolean hasFailedUpdateMarker() {
        try {
            java.io.File f = new java.io.File("/data/local/tmp/overdrive_update_progress.json");
            if (!f.exists()) return false;
            StringBuilder sb = new StringBuilder();
            try (java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(new java.io.FileInputStream(f)))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
            }
            return new JSONObject(sb.toString()).optString("phase", "").equals("error");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Fetch the latest published version label for the current channel from
     * GitHub Releases (best-effort, non-blocking). Returns the same label
     * format as the on-device "display version" (e.g. "alpha-v6.1") so the
     * UI can swap one for the other without further parsing.
     *
     * Used by the Settings → About surface so the user sees the truth-source
     * version (what's published) rather than what was last installed locally.
     * Falls back through {@link #onError} when offline / proxy down.
     */
    public interface RemoteVersionCallback {
        void onResult(String version);
        void onError(String error);
    }

    public void fetchLatestReleaseVersion(RemoteVersionCallback callback) {
        String channel = BuildConfig.UPDATE_CHANNEL;
        if (channel == null || channel.isEmpty()) {
            runCallback(() -> callback.onError("No channel configured"));
            return;
        }
        executor.execute(() -> {
            try {
                String apiUrl = "https://api.github.com/repos/" + GITHUB_REPO +
                        "/releases/tags/" + channel;

                OkHttpClient client = buildClient(10, 10);
                Request request = new Request.Builder()
                        .url(apiUrl)
                        .header("Accept", "application/vnd.github.v3+json")
                        .build();

                try (Response response = client.newCall(request).execute()) {
                    if (!response.isSuccessful()) {
                        String err = "GitHub API HTTP " + response.code();
                        runCallback(() -> callback.onError(err));
                        return;
                    }
                    JSONObject release = new JSONObject(response.body().string());
                    JSONArray assets = release.optJSONArray("assets");
                    String label = null;
                    if (assets != null) {
                        for (int i = 0; i < assets.length(); i++) {
                            JSONObject asset = assets.getJSONObject(i);
                            String name = asset.optString("name", "");
                            if (name.endsWith(".apk")) {
                                label = extractVersion(name);
                                break;
                            }
                        }
                    }
                    if (label == null || label.isEmpty() || "unknown".equals(label)) {
                        // Fallback to the release tag_name when the asset name
                        // doesn't expose a parseable version.
                        label = release.optString("tag_name", "");
                    }
                    final String result = label;
                    runCallback(() -> {
                        if (result == null || result.isEmpty()) {
                            callback.onError("No version in release");
                        } else {
                            callback.onResult(result);
                        }
                    });
                }
            } catch (Exception e) {
                String err = e.getMessage() != null ? e.getMessage() : "Unknown error";
                runCallback(() -> callback.onError(err));
            }
        });
    }

    /**
     * Sentinel returned when neither the SharedPreferences entry nor the
     * version file has been populated yet — i.e. a fresh sideloaded install
     * that has never run an update check. We deliberately do NOT fall back
     * to BuildConfig.VERSION_NAME here because that value is the gradle
     * stub ("11.0") and is unrelated to the GitHub release the user is
     * actually running. "Manually Installed" makes the install path
     * explicit; once the user runs check-for-updates the real channel-
     * versioned string takes over.
     */
    public static final String DISPLAY_VERSION_FALLBACK = "Manually Installed";

    /**
     * Get the display version string (channel + version from APK name).
     * Returns {@link #DISPLAY_VERSION_FALLBACK} when no AppUpdater-tracked
     * version exists yet (fresh sideload before first update check).
     */
    public static String getDisplayVersion(Context context) {
        String stored = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(PREF_UPDATED_VERSION, null);
        if (stored != null && !stored.isEmpty()) {
            return stored;
        }
        // Try the file too — daemon-side updates may have written it without
        // the app-process SharedPreferences also being populated yet.
        return getDisplayVersionFromFile();
    }

    /**
     * Get the display version without requiring a Context.
     * Reads from the persisted version file (written by the updater after
     * a successful install). Returns {@link #DISPLAY_VERSION_FALLBACK} when
     * the file is missing or empty — used by the daemon process where
     * SharedPreferences may not be accessible.
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
        return DISPLAY_VERSION_FALLBACK;
    }

    private long getInstalledVersionCode() {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return info != null ? info.getLongVersionCode() : -1;
        } catch (Exception e) {
            Log.w(TAG, "Unable to read installed versionCode: " + e.getMessage());
            return -1;
        }
    }
}
