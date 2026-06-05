package com.overdrive.app.daemon;

import android.os.Looper;

import com.overdrive.app.daemon.telegram.CommandContext;
import com.overdrive.app.daemon.telegram.CommandRouter;
import com.overdrive.app.logging.DaemonLogger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import com.overdrive.app.daemon.proxy.Safe;

/**
 * Telegram Bot Daemon - runs as shell user (UID 2000) via ADB shell.
 * 
 * Uses long polling (not webhooks) for NAT compatibility behind 4G.
 * Commands are handled by modular handlers in the telegram package.
 * 
 * IPC server on port 19877 accepts notification requests from app process.
 */
public class TelegramBotDaemon {
    
    private static final String TAG = "TelegramBotDaemon";
    private static DaemonLogger logger;
    
    // ==================== ENCRYPTED CONSTANTS (SOTA Java obfuscation) ====================
    // Decrypted at runtime via Safe.s() - AES-256-CBC with stack-based key reconstruction
    /** /data/local/tmp */
    private static String PATH_DATA_LOCAL_TMP() { return Safe.s("vuaMjrmBGBFh07qqnUuL8w=="); }
    /** /data/local/tmp/telegram_config.properties */
    private static String PATH_TELEGRAM_CONFIG() { return Safe.s("ZHx6IP38aGV/Q7iMCCcxzwQSn0P1N0jxHygc8N+4Ft+9mlR8XQ+WvEw0ktanrtNx"); }
    /** /data/local/tmp/tunnel_url.txt */
    private static String PATH_TELEGRAM_URL_FILE() { return Safe.s("ZHx6IP38aGV/Q7iMCCcxz/kVx51CDNRiQ/Mc5+npiPo="); }
    /** https://api.telegram.org/bot */
    private static String TELEGRAM_API_BASE() { return Safe.s("FS7R/5I0wopp0qBqyJXzvDKg6eI9UXmD/Oei3NbaaGQ="); }
    
    // Port allocations across daemons:
    //   19876 — CameraDaemon control TCP (Constants.kt)
    //   19877 — SurveillanceIpcServer (IPC for the surveillance engine)
    //   19878 — BydEventDaemon TCP push (vehicle door / charge / radar events)
    //   19879 — SentryDaemon control
    //   19880 — Telegram bot IPC (this daemon)
    // Was previously 19878 which collides with BydEventDaemon — whichever
    // daemon started second silently failed to bind. Moved to 19880.
    private static final int IPC_PORT = 19880;
    
    // Singleton lock (same pattern as CameraDaemon / AccSentryDaemon)
    private static final String LOCK_FILE = "/data/local/tmp/telegram_bot_daemon.lock";
    private static java.io.RandomAccessFile lockFileHandle;
    private static java.nio.channels.FileLock fileLock;
    
    private static volatile boolean running = true;
    private static final AtomicBoolean polling = new AtomicBoolean(false);
    
    private static String botToken;
    private static long ownerChatId = -1;
    private static boolean videoUploadsEnabled = false;  // Default to OFF - user must enable
    private static OkHttpClient httpClient;
    private static long lastUpdateId = 0;
    
    // Track processed update IDs to prevent duplicate processing
    private static final java.util.Set<Long> processedUpdateIds = 
        java.util.Collections.newSetFromMap(new java.util.LinkedHashMap<Long, Boolean>() {
            @Override
            protected boolean removeEldestEntry(java.util.Map.Entry<Long, Boolean> eldest) {
                return size() > 100; // Keep last 100 update IDs
            }
        });
    
    // Command router for modular command handling
    private static CommandRouter commandRouter;
    
    public static void main(String[] args) {
        int myUid = android.os.Process.myUid();
        int myPid = android.os.Process.myPid();
        
        // Configure DaemonLogger for daemon context (enable stdout for app_process)
        DaemonLogger.configure(DaemonLogger.Config.defaults()
            .withStdoutLog(true)
            .withFileLog(true)
            .withConsoleLog(true));
        
        logger = DaemonLogger.getInstance(TAG, PATH_DATA_LOCAL_TMP());
        
        log("=== Telegram Bot Daemon Starting ===");
        log("UID: " + myUid + " (expected: 2000 shell)");
        log("PID: " + myPid);
        
        // Kill any old instances using pkill -f (same pattern as DaemonLauncher.kt)
        killOldInstances(myPid);
        
        // CRITICAL: Acquire singleton lock - exit if another instance survived
        if (!acquireSingletonLock()) {
            log("ERROR: Another TelegramBotDaemon instance is already running. Exiting.");
            System.exit(1);
            return;
        }
        
        if (Looper.myLooper() == null) {
            Looper.prepare();
        }
        
        try {
            if (!loadConfig()) {
                log("FATAL: No bot token configured");
                return;
            }
            
            initHttpClient();
            initCommandRouter();
            startIpcServer();  // Start IPC server for app notifications
            startPolling();
            
            log("Daemon running, polling for updates...");
            Looper.loop();
            
        } catch (Exception e) {
            log("FATAL: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    // ==================== DUPLICATE INSTANCE CLEANUP ====================
    
    /**
     * Kill old daemon instances using pkill (same approach as DaemonLauncher.kt).
     * Excludes our own PID to avoid killing ourselves.
     * Also cleans up stale lock file left by SIGKILL'd processes.
     */
    private static void killOldInstances(int myPid) {
        try {
            // Find and kill other telegram_bot_daemon processes, excluding our own PID.
            // pkill -9 -f would match our own process too (command line contains the pattern),
            // so we use ps + grep + awk to filter by PID instead.
            String killCmd = "ps -A -o PID,ARGS | grep -F telegram_bot_daemon | grep -v grep | awk '{print $1}' | while read pid; do " +
                    "if [ \"$pid\" != \"" + myPid + "\" ]; then kill -9 $pid 2>/dev/null; fi; done";
            execShell(killCmd);
            Thread.sleep(500);
            
            // Clean up stale lock file (SIGKILL doesn't trigger shutdown hooks)
            new java.io.File(LOCK_FILE).delete();
            
            log("Old instance cleanup complete (my PID: " + myPid + ")");
        } catch (Exception e) {
            log("Error killing old instances: " + e.getMessage());
        }
    }
    
    // ==================== SINGLETON LOCK ====================
    
    /**
     * Acquire a file lock to ensure only one daemon instance runs at a time.
     * Same pattern as CameraDaemon / AccSentryDaemon.
     */
    private static boolean acquireSingletonLock() {
        try {
            java.io.File lockFileObj = new java.io.File(LOCK_FILE);
            lockFileHandle = new java.io.RandomAccessFile(lockFileObj, "rw");
            java.nio.channels.FileChannel channel = lockFileHandle.getChannel();
            
            // Try to acquire exclusive lock (non-blocking)
            fileLock = channel.tryLock();
            
            if (fileLock == null) {
                lockFileHandle.close();
                return false;
            }
            
            // Write our PID to the lock file for debugging
            lockFileHandle.setLength(0);
            lockFileHandle.writeBytes(String.valueOf(android.os.Process.myPid()));
            
            log("Acquired singleton lock (PID: " + android.os.Process.myPid() + ")");
            
            // Register shutdown hook to release lock on process termination
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                releaseSingletonLock();
            }));
            
            return true;
            
        } catch (java.nio.channels.OverlappingFileLockException e) {
            log("Lock already held by this process");
            return false;
        } catch (Exception e) {
            log("Failed to acquire singleton lock: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Release the singleton lock on shutdown.
     */
    private static void releaseSingletonLock() {
        try {
            if (fileLock != null) {
                fileLock.release();
                fileLock = null;
            }
            if (lockFileHandle != null) {
                lockFileHandle.close();
                lockFileHandle = null;
            }
            new java.io.File(LOCK_FILE).delete();
        } catch (Exception e) {
            log("Error releasing singleton lock: " + e.getMessage());
        }
    }
    
    private static void log(String msg) {
        if (logger != null) {
            logger.info(msg);
        }
        // Note: System.out.println is now handled by DaemonLogger when enableStdoutLog is true
    }
    
    // ==================== INITIALIZATION ====================
    
    private static boolean loadConfig() {
        try {
            // Ensure UnifiedConfigManager is initialized in this process —
            // the daemon is launched fresh via app_process and doesn't share
            // the app's static state. init() is a no-op when the file already
            // exists and triggers legacy migration if it doesn't.
            com.overdrive.app.config.UnifiedConfigManager.init();
            // Pull any state still in /data/local/tmp/telegram_config.properties
            // into the unified store. Idempotent — no-op after first call.
            com.overdrive.app.telegram.config.UnifiedTelegramConfig.migrateLegacyIfNeeded();

            botToken = com.overdrive.app.telegram.config.UnifiedTelegramConfig.getBotToken();
            if (botToken == null || botToken.isEmpty()) {
                log("bot_token not set in unified config");
                return false;
            }

            ownerChatId = com.overdrive.app.telegram.config.UnifiedTelegramConfig.getOwnerChatId();
            videoUploadsEnabled = com.overdrive.app.telegram.config.UnifiedTelegramConfig.isVideoUploads();

            log("Config loaded: token=***" + botToken.substring(Math.max(0, botToken.length() - 6)));
            log("Owner chat ID: " + (ownerChatId > 0 ? ownerChatId : "not set"));
            log("Video uploads: " + (videoUploadsEnabled ? "enabled" : "disabled"));

            return true;
        } catch (Exception e) {
            log("Config load error: " + e.getMessage());
            return false;
        }
    }

    /**
     * Re-read shared state from the unified config. Called once per long-poll
     * cycle so a token clear, owner unpair, or preference toggle made by the
     * app/web UI takes effect within a single poll interval rather than
     * requiring a daemon restart. forceReload bypasses the in-process mtime
     * cache so a write made by the OTHER UID is picked up immediately.
     *
     * Logs only on transitions to keep the log file readable — every poll
     * cycle would otherwise emit identical "token / owner / video_uploads"
     * lines forever.
     */
    private static void refreshConfigFromUnified() {
        try {
            com.overdrive.app.config.UnifiedConfigManager.forceReload();

            String newToken = com.overdrive.app.telegram.config.UnifiedTelegramConfig.getBotToken();
            long newOwner = com.overdrive.app.telegram.config.UnifiedTelegramConfig.getOwnerChatId();
            boolean newVideo = com.overdrive.app.telegram.config.UnifiedTelegramConfig.isVideoUploads();

            if (newToken != null && !newToken.isEmpty() && !newToken.equals(botToken)) {
                log("Bot token changed from unified config; updating");
                botToken = newToken;
            }
            if (newOwner != ownerChatId) {
                log("Owner changed: " + ownerChatId + " → " + (newOwner > 0 ? newOwner : "cleared"));
                ownerChatId = newOwner;
            }
            if (newVideo != videoUploadsEnabled) {
                log("Video uploads changed: " + videoUploadsEnabled + " → " + newVideo);
                videoUploadsEnabled = newVideo;
            }
        } catch (Exception e) {
            // Don't kill the poll loop on a config read blip.
            log("refreshConfigFromUnified error: " + e.getMessage());
        }
    }

    private static long lastProxyCheckTime = 0;
    private static boolean lastProxyState = false; // true = proxy was available

    private static void initHttpClient() {
        refreshHttpClient();
    }

    /**
     * Refresh HTTP client with current proxy settings.
     * Called on init and after connection failures to pick up proxy changes.
     */
    private static void refreshHttpClient() {
        lastProxyCheckTime = System.currentTimeMillis();

        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS);
        
        // Check for global proxy settings
        java.net.Proxy proxy = getGlobalProxy();
        boolean proxyAvailable = (proxy != null);

        if (proxyAvailable) {
            builder.proxy(proxy);
            if (!lastProxyState) {
                log("HTTP client switched to proxy: " + proxy.address());
            }
        } else {
            if (lastProxyState) {
                log("HTTP client switched to direct connection (proxy gone)");
            }
        }

        lastProxyState = proxyAvailable;
        httpClient = builder.build();
    }

    /**
     * Invalidate HTTP client so next request re-checks proxy.
     * Called on connection failures.
     */
    private static void onHttpFailure() {
        // Re-check proxy if last check was more than 10 seconds ago
        long elapsed = System.currentTimeMillis() - lastProxyCheckTime;
        if (elapsed > 10_000) {
            refreshHttpClient();
        }
    }
    
    /**
     * Get global HTTP proxy from Android settings.
     * Reads from: settings get global http_proxy (format: host:port)
     */
    private static java.net.Proxy getGlobalProxy() {
        try {
            String proxyStr = execShell("settings get global http_proxy 2>/dev/null");
            if (proxyStr == null || proxyStr.trim().isEmpty() || proxyStr.trim().equals("null") || proxyStr.trim().equals(":0")) {
                return null;
            }
            
            proxyStr = proxyStr.trim();
            String[] parts = proxyStr.split(":");
            if (parts.length != 2) {
                return null;
            }
            
            String host = parts[0];
            int port;
            try {
                port = Integer.parseInt(parts[1]);
            } catch (NumberFormatException e) {
                return null;
            }
            
            if (host.isEmpty() || port <= 0) {
                return null;
            }
            
            log("Found global proxy: " + host + ":" + port);
            return new java.net.Proxy(java.net.Proxy.Type.HTTP, new java.net.InetSocketAddress(host, port));
        } catch (Exception e) {
            log("Error reading proxy settings: " + e.getMessage());
            return null;
        }
    }
    
    private static void initCommandRouter() {
        commandRouter = new CommandRouter(new CommandContext() {
            @Override
            public boolean sendMessage(long chatId, String text) {
                return TelegramBotDaemon.sendMessage(chatId, text);
            }
            
            @Override
            public boolean sendMessageWithButtons(long chatId, String text, String[][][] buttons) {
                return TelegramBotDaemon.sendMessageWithButtons(chatId, text, buttons);
            }
            
            @Override
            public boolean sendVideo(long chatId, String videoPath, String caption) {
                return TelegramBotDaemon.sendVideo(chatId, videoPath, caption);
            }
            
            @Override
            public JSONObject sendIpcCommand(int port, JSONObject command) {
                return TelegramBotDaemon.sendIpcCommand(port, command);
            }

            @Override
            public JSONObject sendIpcCommand(int port, JSONObject command, int timeoutMs) {
                return TelegramBotDaemon.sendIpcCommand(port, command, timeoutMs);
            }


            @Override
            public String execShell(String command) {
                return TelegramBotDaemon.execShell(command);
            }

            @Override
            public boolean spawnDetached(String command) {
                return TelegramBotDaemon.spawnDetached(command);
            }

            @Override
            public void log(String message) {
                TelegramBotDaemon.log(message);
            }
        });
    }

    /**
     * Spawn a long-lived process detached. Mirrors AppUpdater.runDetachedInstall
     * (AppUpdater.java:741-745). Use this — never execShell — for any command
     * that backgrounds a daemon (app_process, cloudflared, zrok, tailscaled,
     * sing-box, watchdog scripts). execShell drains stdout to EOF, which
     * blocks forever when the grandchild keeps the inherited fd open.
     *
     * Returns true on successful spawn (the inner command may still fail —
     * we don't wait to find out), false if the parent shell couldn't start.
     */
    private static boolean spawnDetached(String command) {
        // (... &) wrapper backgrounds inside a subshell that exits in ms,
        // reparenting the grandchild to init.
        // </dev/null on the inner command + ProcessBuilder Redirects close
        // every stdio descriptor so no pipe is held open by the parent.
        String wrapped = "(" + command + " </dev/null &)";
        try {
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", wrapped);
            pb.redirectOutput(ProcessBuilder.Redirect.to(new java.io.File("/dev/null")));
            pb.redirectError(ProcessBuilder.Redirect.to(new java.io.File("/dev/null")));
            pb.start();
            return true;
        } catch (java.io.IOException e) {
            log("spawnDetached failed: " + e.getMessage());
            return false;
        }
    }
    
    // ==================== IPC SERVER ====================
    
    /**
     * Start IPC server to receive notification requests from app process.
     * Listens on localhost:19877 for JSON commands.
     */
    /**
     * Worker pool for IPC handlers. Sized at 2 — enough to overlap a slow
     * sendPhoto/sendVideo (now up to 30s on 429 retry) with a parallel
     * notifyMotion/notifyCritical from the engine. Without this, the accept
     * thread itself ran the handler inline and a single retrying call would
     * stall every other Telegram message during the burst that triggered
     * the rate limit. Daemon thread so it doesn't pin shutdown.
     */
    private static final java.util.concurrent.ExecutorService IPC_WORKERS =
            java.util.concurrent.Executors.newFixedThreadPool(2, r -> {
                Thread t = new Thread(r, "TelegramIPCWorker");
                t.setDaemon(true);
                return t;
            });

    private static void startIpcServer() {
        Thread ipcThread = new Thread(() -> {
            log("IPC server starting on port " + IPC_PORT);

            while (running) {
                ServerSocket serverSocket = null;
                try {
                    serverSocket = new ServerSocket(IPC_PORT, 5, InetAddress.getByName("127.0.0.1"));
                    serverSocket.setReuseAddress(true);
                    log("IPC server listening on 127.0.0.1:" + IPC_PORT);

                    while (running) {
                        try {
                            Socket client = serverSocket.accept();
                            // Dispatch off the accept thread so a slow handler
                            // (e.g. sendPhoto sleeping 30s on 429 retry) can't
                            // block subsequent IPC commands.
                            IPC_WORKERS.execute(() -> handleIpcClient(client));
                        } catch (Exception e) {
                            if (running) {
                                log("IPC accept error: " + e.getMessage());
                            }
                        }
                    }
                } catch (java.net.BindException e) {
                    log("IPC port " + IPC_PORT + " in use, retrying...");
                    try { Thread.sleep(5000); } catch (InterruptedException ignored) {}
                } catch (Exception e) {
                    log("IPC server error: " + e.getMessage());
                    try { Thread.sleep(3000); } catch (InterruptedException ignored) {}
                } finally {
                    if (serverSocket != null) {
                        try { serverSocket.close(); } catch (Exception ignored) {}
                    }
                }
            }
            
            log("IPC server stopped");
        }, "TelegramIPC");
        ipcThread.setDaemon(true);
        ipcThread.start();
    }
    
    private static void handleIpcClient(Socket client) {
        try {
            client.setSoTimeout(5000);
            BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream()));
            PrintWriter writer = new PrintWriter(client.getOutputStream(), true);
            
            String line = reader.readLine();
            if (line != null) {
                log("IPC received: " + line);
                JSONObject response = processIpcCommand(new JSONObject(line));
                writer.println(response.toString());
            }
        } catch (Exception e) {
            log("IPC client error: " + e.getMessage());
        } finally {
            try { client.close(); } catch (Exception ignored) {}
        }
    }
    
    private static JSONObject processIpcCommand(JSONObject cmd) {
        JSONObject response = new JSONObject();
        try {
            String action = cmd.optString("cmd", "");
            
            switch (action) {
                case "sendMessage":
                    long chatId = cmd.optLong("chatId", ownerChatId);
                    String text = cmd.optString("text", "");
                    if (chatId > 0 && !text.isEmpty()) {
                        boolean ok = sendMessage(chatId, text);
                        response.put("status", ok ? "ok" : "error");
                    } else {
                        response.put("status", "error");
                        response.put("message", "Missing chatId or text");
                    }
                    break;
                    
                case "sendVideo":
                    // Check if video uploads are enabled
                    if (!videoUploadsEnabled) {
                        log("Video upload skipped - auto-upload disabled in settings");
                        response.put("status", "skipped");
                        response.put("message", "Video uploads disabled");
                        break;
                    }
                    
                    long videoChatId = cmd.optLong("chatId", ownerChatId);
                    String videoPath = cmd.optString("path", "");
                    String caption = cmd.optString("caption", "");
                    if (videoChatId > 0 && !videoPath.isEmpty()) {
                        boolean ok = sendVideo(videoChatId, videoPath, caption);
                        response.put("status", ok ? "ok" : "error");
                    } else {
                        response.put("status", "error");
                        response.put("message", "Missing chatId or path");
                    }
                    break;
                    
                case "notifyTunnel":
                    String url = cmd.optString("url", "");
                    boolean isNew = cmd.optBoolean("isNew", true);
                    log("IPC notifyTunnel: url=" + url + ", isNew=" + isNew);
                    if (!url.isEmpty() && ownerChatId > 0) {
                        // Save URL to file for /url command
                        saveTunnelUrl(url);

                        // Check the post-update hint file. If present, the new
                        // URL was caused by an app update — frame the message
                        // accordingly and consume the hint so subsequent tunnel
                        // restarts go back to the generic copy. Hint contents:
                        // the new version string (e.g. "alpha-v11.4").
                        String postUpdateVersion = consumePostUpdateHint();

                        String msg;
                        if (postUpdateVersion != null) {
                            // Cloudflared free quick-tunnels rotate their URL on
                            // every restart (*.trycloudflare.com); zrok/tailscale/
                            // named cloudflared tunnels keep the same URL. Only
                            // call out the rotation when it actually happened.
                            boolean rotates = url.contains(".trycloudflare.com");
                            msg = "🔄 *Overdrive updated to " + postUpdateVersion + "*\n" +
                                  (rotates ? "New tunnel URL:\n" : "Tunnel back online:\n") + url;
                            if (rotates) {
                                msg += "\n\n_The cloudflared link rotates after every install._";
                            }
                        } else if (isNew) {
                            msg = "🌐 *Tunnel URL*\n" + url;
                        } else {
                            msg = "🔄 *Tunnel URL Changed*\n" + url;
                        }
                        boolean ok = sendMessage(ownerChatId, msg);
                        log("notifyTunnel message sent: " + ok +
                                (postUpdateVersion != null ? " (post-update)" : ""));
                        response.put("status", ok ? "ok" : "error");
                    } else {
                        response.put("status", "error");
                        response.put("message", "No URL or owner not set");
                    }
                    break;
                    
                case "notifyMotion":
                    if (ownerChatId > 0) {
                        String motionText = formatMotionMessage(cmd, /*finalized=*/false);
                        boolean ok = sendMessage(ownerChatId, motionText);
                        response.put("status", ok ? "ok" : "error");
                    } else {
                        response.put("status", "error");
                        response.put("message", "Owner not set");
                    }
                    break;

                case "notifyMotionFinalized":
                    // Recording closed and the hero JPEG has been written. Send a
                    // PHOTO (with rich caption) when the path is available; fall
                    // back to text-only if the photo upload fails or there's no
                    // hero — never silently drop.
                    if (ownerChatId > 0) {
                        String finalCaption = formatMotionMessage(cmd, /*finalized=*/true);
                        String heroPath = cmd.optString("heroPhotoPath", "");
                        boolean ok;
                        if (!heroPath.isEmpty() && new java.io.File(heroPath).exists()) {
                            ok = sendPhoto(ownerChatId, heroPath, finalCaption);
                            if (!ok) {
                                // Photo upload failed — fall back to text so the
                                // user still gets the alert.
                                ok = sendMessage(ownerChatId, finalCaption);
                            }
                        } else {
                            ok = sendMessage(ownerChatId, finalCaption);
                        }
                        response.put("status", ok ? "ok" : "error");
                    } else {
                        response.put("status", "error");
                        response.put("message", "Owner not set");
                    }
                    break;
                    
                case "notifyCritical":
                    String criticalType = cmd.optString("type", "");
                    String details = cmd.optString("details", "");
                    if (ownerChatId > 0) {
                        String msg = "⚠️ *Critical Alert*\n" + criticalType;
                        if (!details.isEmpty()) msg += "\n" + details;
                        boolean ok = sendMessage(ownerChatId, msg);
                        response.put("status", ok ? "ok" : "error");
                    } else {
                        response.put("status", "error");
                        response.put("message", "Owner not set");
                    }
                    break;
                    
                case "ping":
                    response.put("status", "ok");
                    response.put("ownerChatId", ownerChatId);
                    break;
                    
                default:
                    response.put("status", "error");
                    response.put("message", "Unknown command: " + action);
            }
        } catch (Exception e) {
            try {
                response.put("status", "error");
                response.put("message", e.getMessage());
            } catch (Exception ignored) {}
        }
        return response;
    }
    
    // ==================== LONG POLLING ====================
    
    private static void startPolling() {
        if (polling.getAndSet(true)) {
            log("Already polling");
            return;
        }
        
        Thread pollThread = new Thread(() -> {
            log("Polling thread started");
            
            // Skip old messages by getting latest update ID first
            flushOldUpdates();
            
            // Send greeting to owner if paired
            sendStartupGreeting();
            
            while (running && polling.get()) {
                try {
                    pollUpdates();
                } catch (Exception e) {
                    log("Poll error: " + e.getMessage());
                    onHttpFailure(); // Re-check proxy on connection failure
                    try { Thread.sleep(5000); } catch (InterruptedException ignored) {}
                }
            }
            
            log("Polling thread stopped");
        }, "TelegramPoll");
        pollThread.setDaemon(true);
        pollThread.start();
    }
    
    /**
     * Flush old updates by getting the latest update ID without processing.
     * This prevents the bot from responding to messages sent while it was offline.
     */
    private static void flushOldUpdates() {
        try {
            log("Flushing old updates...");
            
            // Get updates with offset -1 to get only the latest update
            String url = TELEGRAM_API_BASE() + botToken + "/getUpdates?timeout=1&offset=-1";
            
            Request request = new Request.Builder().url(url).get().build();
            
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    log("Flush HTTP error: " + response.code());
                    return;
                }
                
                String body = response.body() != null ? response.body().string() : "";
                JSONObject json = new JSONObject(body);
                
                if (!json.optBoolean("ok", false)) {
                    log("Flush API error: " + json.optString("description"));
                    return;
                }
                
                JSONArray updates = json.optJSONArray("result");
                if (updates != null && updates.length() > 0) {
                    // Get the last update ID and set our offset past it
                    JSONObject lastUpdate = updates.getJSONObject(updates.length() - 1);
                    lastUpdateId = lastUpdate.getLong("update_id");
                    log("Flushed old updates, starting from update_id: " + (lastUpdateId + 1));
                } else {
                    log("No old updates to flush");
                }
            }
        } catch (Exception e) {
            log("Flush error: " + e.getMessage());
        }
    }
    
    /**
     * Send a startup greeting message to the owner.
     */
    private static void sendStartupGreeting() {
        if (ownerChatId <= 0) {
            log("No owner paired, skipping startup greeting");
            return;
        }
        
        try {
            String greeting = "🤖 *Surveillance Bot Online*\n\n" +
                    "Bot daemon started and ready.\n" +
                    "Use /help for available commands.";
            
            String[][][] buttons = {
                {{"📊 Status", "cmd:/status"}, {"🤖 Daemons", "cmd:/daemons"}},
                {{"📹 Events", "cmd:/events"}, {"🌐 Tunnel URL", "cmd:/url"}}
            };
            
            boolean sent = sendMessageWithButtons(ownerChatId, greeting, buttons);
            log("Startup greeting sent: " + sent);
        } catch (Exception e) {
            log("Startup greeting error: " + e.getMessage());
        }
    }
    
    private static void pollUpdates() throws Exception {
        // Refresh shared state from the unified config every poll cycle so
        // a clear/unpair/preference-toggle made by the app or web UI takes
        // effect within one long-poll interval (~30s) rather than requiring
        // a daemon restart. forceReload() bypasses the in-process mtime
        // cache to pick up cross-UID writes immediately.
        refreshConfigFromUnified();

        if (botToken == null || botToken.isEmpty()) {
            // Token cleared from the UI — sleep instead of hitting the
            // Telegram API with an empty token (which 404s in a tight loop
            // and floods the log). The next refresh that finds a token
            // resumes polling automatically.
            try { Thread.sleep(5000); } catch (InterruptedException ignored) {}
            return;
        }

        String url = TELEGRAM_API_BASE() + botToken + "/getUpdates?timeout=30&offset=" + (lastUpdateId + 1);
        
        Request request = new Request.Builder().url(url).get().build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                log("Poll HTTP error: " + response.code());
                onHttpFailure();
                return;
            }
            
            String body = response.body() != null ? response.body().string() : "";
            JSONObject json = new JSONObject(body);
            
            if (!json.optBoolean("ok", false)) {
                log("Poll API error: " + json.optString("description"));
                return;
            }
            
            JSONArray updates = json.optJSONArray("result");
            if (updates == null || updates.length() == 0) return;
            
            for (int i = 0; i < updates.length(); i++) {
                JSONObject update = updates.getJSONObject(i);
                long updateId = update.getLong("update_id");
                
                // Skip if already processed (deduplication)
                if (processedUpdateIds.contains(updateId)) {
                    log("Skipping duplicate update: " + updateId);
                    continue;
                }
                processedUpdateIds.add(updateId);
                
                lastUpdateId = Math.max(lastUpdateId, updateId);
                processUpdate(update);
            }
        }
    }
    
    // ==================== UPDATE PROCESSING ====================
    
    private static void processUpdate(JSONObject update) {
        try {
            // Handle callback queries (button presses)
            JSONObject callbackQuery = update.optJSONObject("callback_query");
            if (callbackQuery != null) {
                processCallbackQuery(callbackQuery);
                return;
            }
            
            JSONObject message = update.optJSONObject("message");
            if (message == null) return;
            
            JSONObject chat = message.optJSONObject("chat");
            if (chat == null) return;
            
            long chatId = chat.getLong("id");
            String text = message.optString("text", "");
            
            JSONObject from = message.optJSONObject("from");
            String username = from != null ? from.optString("username", "") : "";
            String firstName = from != null ? from.optString("first_name", "") : "";
            
            log("Message from " + chatId + " (@" + username + "): " + text);
            
            // Handle /pair command (allowed even without owner)
            if (text.startsWith("/pair ")) {
                handlePairCommand(chatId, username, firstName, text.substring(6).trim());
                return;
            }
            
            // Owner-only commands
            if (ownerChatId <= 0) {
                sendMessage(chatId, "⚠️ No owner paired. Use /pair <PIN> to pair.");
                return;
            }
            
            if (chatId != ownerChatId) {
                log("Ignoring message from non-owner: " + chatId);
                return;
            }
            
            // Route commands via modular handlers
            if (text.startsWith("/")) {
                commandRouter.route(chatId, text);
            }
            
        } catch (Exception e) {
            log("Update processing error: " + e.getMessage());
        }
    }
    
    /**
     * Process callback query from inline keyboard button press.
     */
    private static void processCallbackQuery(JSONObject callbackQuery) {
        try {
            String callbackId = callbackQuery.getString("id");
            String data = callbackQuery.optString("data", "");
            
            JSONObject from = callbackQuery.optJSONObject("from");
            long userId = from != null ? from.getLong("id") : 0;
            
            log("Callback from " + userId + ": " + data);
            
            // Only allow owner
            if (userId != ownerChatId) {
                answerCallbackQuery(callbackId, "⚠️ Not authorized");
                return;
            }
            
            // Handle download callback: "dl:filename.mp4"
            if (data.startsWith("dl:")) {
                String filename = data.substring(3);
                answerCallbackQuery(callbackId, "📥 Downloading...");
                commandRouter.route(ownerChatId, "/download " + filename);
            }
            // Handle events pagination: "ev:hours:page"
            else if (data.startsWith("ev:")) {
                String[] parts = data.substring(3).split(":");
                if (parts.length == 2) {
                    answerCallbackQuery(callbackId, null);
                    commandRouter.route(ownerChatId, "/events " + parts[0] + " " + parts[1]);
                }
            }
            // Handle command shortcut: "cmd:/command"
            else if (data.startsWith("cmd:")) {
                String command = data.substring(4);
                answerCallbackQuery(callbackId, null);
                commandRouter.route(ownerChatId, command);
            }
            // Handle daemon control: "dm:name:action"
            else if (data.startsWith("dm:")) {
                String[] parts = data.substring(3).split(":");
                if (parts.length == 2) {
                    answerCallbackQuery(callbackId, "⏳ " + parts[1] + "ing...");
                    commandRouter.route(ownerChatId, "/daemon " + parts[0] + " " + parts[1]);
                }
            }
            else {
                answerCallbackQuery(callbackId, "Unknown action");
            }
            
        } catch (Exception e) {
            log("Callback processing error: " + e.getMessage());
        }
    }
    
    /**
     * Answer a callback query (acknowledge button press).
     */
    private static void answerCallbackQuery(String callbackId, String text) {
        try {
            String url = TELEGRAM_API_BASE() + botToken + "/answerCallbackQuery";
            
            JSONObject body = new JSONObject();
            body.put("callback_query_id", callbackId);
            if (text != null && !text.isEmpty()) {
                body.put("text", text);
            }
            
            Request request = new Request.Builder()
                    .url(url)
                    .post(RequestBody.create(body.toString(), MediaType.parse("application/json")))
                    .build();
            
            httpClient.newCall(request).execute().close();
        } catch (Exception e) {
            log("answerCallbackQuery error: " + e.getMessage());
        }
    }
    
    private static void handlePairCommand(long chatId, String username, String firstName, String pin) {
        if (ownerChatId > 0) {
            sendMessage(chatId, "❌ Already paired with another owner.");
            return;
        }
        
        if (pin.length() != 6 || !pin.matches("\\d+")) {
            sendMessage(chatId, "❌ Invalid PIN format. Enter 6-digit PIN from app.");
            return;
        }
        
        // Validate PIN against the one written by the app UI / web tab.
        // forceReload bypasses the in-process cache so a PIN that was just
        // written by the app process (different UID, different mtime tick)
        // is visible to us immediately.
        String expectedPin = "";
        long pinExpiry = 0;
        try {
            com.overdrive.app.config.UnifiedConfigManager.forceReload();
            expectedPin = com.overdrive.app.telegram.config.UnifiedTelegramConfig.getPairPin();
            pinExpiry = com.overdrive.app.telegram.config.UnifiedTelegramConfig.getPairPinExpiry();
        } catch (Exception e) {
            log("Error reading pair PIN from config: " + e.getMessage());
        }
        
        if (expectedPin == null || expectedPin.isEmpty()) {
            sendMessage(chatId, "❌ No PIN generated. Generate a PIN from the app first.");
            return;
        }
        
        if (System.currentTimeMillis() > pinExpiry) {
            sendMessage(chatId, "❌ PIN expired. Generate a new PIN from the app.");
            // Clear expired PIN from config
            clearPairPinFromConfig();
            return;
        }
        
        if (!pin.equals(expectedPin)) {
            sendMessage(chatId, "❌ Invalid PIN. Check the PIN shown in the app.");
            return;
        }
        
        // PIN valid — pair owner
        ownerChatId = chatId;
        saveOwnerToConfig(chatId, username, firstName);
        clearPairPinFromConfig();
        
        sendMessage(chatId, "✅ Paired successfully!\n\nWelcome, " + firstName + "!\nUse /help to see available commands.");
    }
    
    private static void clearPairPinFromConfig() {
        try {
            com.overdrive.app.telegram.config.UnifiedTelegramConfig.clearPairPin();
        } catch (Exception e) {
            log("Error clearing pair PIN: " + e.getMessage());
        }
    }
    
    // ==================== MESSAGING ====================
    
    private static boolean sendMessage(long chatId, String text) {
        try {
            String url = TELEGRAM_API_BASE() + botToken + "/sendMessage";
            
            JSONObject body = new JSONObject();
            body.put("chat_id", chatId);
            body.put("text", text);
            body.put("parse_mode", "Markdown");
            
            Request request = new Request.Builder()
                    .url(url)
                    .post(RequestBody.create(body.toString(), MediaType.parse("application/json")))
                    .build();
            
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    // Surface 4xx/5xx so silent failures (e.g. 400
                    // "can't parse entities" from Markdown content the
                    // bot helper didn't escape) stop being invisible.
                    String respBody = response.body() != null
                            ? response.body().string() : "";
                    log("sendMessage HTTP " + response.code() + ": " + respBody);
                    return false;
                }
                return true;
            }
        } catch (Exception e) {
            log("sendMessage error: " + e.getMessage());
            onHttpFailure();
            return false;
        }
    }
    
    /**
     * Send a message with inline keyboard buttons.
     * @param buttons Array of button rows, each row is array of [text, callbackData] pairs
     */
    public static boolean sendMessageWithButtons(long chatId, String text, String[][][] buttons) {
        try {
            String url = TELEGRAM_API_BASE() + botToken + "/sendMessage";
            
            JSONObject body = new JSONObject();
            body.put("chat_id", chatId);
            body.put("text", text);
            body.put("parse_mode", "Markdown");
            
            // Build inline keyboard
            JSONArray keyboard = new JSONArray();
            for (String[][] row : buttons) {
                JSONArray rowArray = new JSONArray();
                for (String[] button : row) {
                    JSONObject btn = new JSONObject();
                    btn.put("text", button[0]);
                    btn.put("callback_data", button[1]);
                    rowArray.put(btn);
                }
                keyboard.put(rowArray);
            }
            
            JSONObject replyMarkup = new JSONObject();
            replyMarkup.put("inline_keyboard", keyboard);
            body.put("reply_markup", replyMarkup);
            
            Request request = new Request.Builder()
                    .url(url)
                    .post(RequestBody.create(body.toString(), MediaType.parse("application/json")))
                    .build();
            
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String respBody = response.body() != null
                            ? response.body().string() : "";
                    log("sendMessageWithButtons HTTP " + response.code() + ": " + respBody);
                    return false;
                }
                return true;
            }
        } catch (Exception e) {
            log("sendMessageWithButtons error: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Send a photo to the chat with an optional caption (Markdown).
     * Used by the surveillance pipeline to ship the hero JPEG of the recording
     * with the threat summary as the caption — the Telegram analogue of the
     * PWA notification's hero image.
     */
    public static boolean sendPhoto(long chatId, String photoPath, String caption) {
        try {
            File photoFile = new File(photoPath);
            if (!photoFile.exists()) {
                log("Photo file not found: " + photoPath);
                return false;
            }

            String url = TELEGRAM_API_BASE() + botToken + "/sendPhoto";

            // Build the multipart request once; we may need to re-send on 429.
            // Reusing the builder is simplest because OkHttp consumes the body
            // exactly once per request, so we re-create the body on retry.
            for (int attempt = 0; attempt < 2; attempt++) {
                MultipartBody.Builder bodyBuilder = new MultipartBody.Builder()
                        .setType(MultipartBody.FORM)
                        .addFormDataPart("chat_id", String.valueOf(chatId))
                        .addFormDataPart("photo", photoFile.getName(),
                                RequestBody.create(photoFile, MediaType.parse("image/jpeg")));

                if (caption != null && !caption.isEmpty()) {
                    bodyBuilder.addFormDataPart("caption", caption);
                    bodyBuilder.addFormDataPart("parse_mode", "Markdown");
                }

                Request request = new Request.Builder()
                        .url(url)
                        .post(bodyBuilder.build())
                        .build();

                try (Response response = httpClient.newCall(request).execute()) {
                    if (response.isSuccessful()) {
                        log("Photo sent: " + photoPath);
                        return true;
                    }
                    if (response.code() == 429 && attempt == 0) {
                        // Telegram rate limit. Body carries {"parameters":{"retry_after":N}}
                        long sleepSec = parseRetryAfter(response, 1L);
                        log("sendPhoto 429 — sleeping " + sleepSec + "s before retry");
                        try { Thread.sleep(Math.min(sleepSec * 1000L, 30_000L)); }
                        catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            return false;
                        }
                        continue;  // retry
                    }
                    log("sendPhoto HTTP error: " + response.code());
                    return false;
                }
            }
            return false;
        } catch (Exception e) {
            log("sendPhoto error: " + e.getMessage());
            return false;
        }
    }

    /**
     * Parse Telegram's retry_after hint from a 429 response. The API returns
     * {@code {"ok":false,"error_code":429,"description":"Too Many Requests:
     * retry after 30","parameters":{"retry_after":30}}}. Falls back to the
     * provided default (seconds) if parsing fails.
     */
    private static long parseRetryAfter(Response response, long defaultSec) {
        try {
            String body = response.peekBody(1024).string();
            JSONObject json = new JSONObject(body);
            JSONObject params = json.optJSONObject("parameters");
            if (params != null && params.has("retry_after")) {
                return Math.max(1L, params.getLong("retry_after"));
            }
            // Some proxies expose Retry-After as a header instead.
            String hdr = response.header("Retry-After");
            if (hdr != null) {
                try { return Math.max(1L, Long.parseLong(hdr.trim())); } catch (NumberFormatException ignored) {}
            }
        } catch (Exception ignored) {}
        return defaultSec;
    }

    /**
     * Format a Telegram motion message from the IPC command's actor metadata.
     * Used by both notifyMotion (start) and notifyMotionFinalized (recording
     * close) so the wording is consistent. Uses Markdown.
     *
     * Output examples (final stage):
     *   🚨 *CRITICAL · Person at front*
     *   Very close
     *   📹 `event_20260514_223124.mp4`
     *
     *   🚨 *CRITICAL · Person at front*
     *   Very close · 1 person, 2 vehicles
     *   📹 `event_20260514_223124.mp4`
     *
     * Start stage:
     *   👁 *Motion at front*
     *   _Recording in progress_
     *   📹 `event_20260514_223124.mp4`
     */
    private static String formatMotionMessage(JSONObject cmd, boolean finalized) {
        String severity = cmd.optString("severity", "");
        String videoFilename = cmd.optString("videoFilename", "");
        String camera = cmd.optString("camera", "");
        String closestProximity = cmd.optString("closestProximity", "");
        int personCount  = cmd.optInt("personCount", 0);
        int vehicleCount = cmd.optInt("vehicleCount", 0);
        int bikeCount    = cmd.optInt("bikeCount", 0);
        int animalCount  = cmd.optInt("animalCount", 0);
        int totalActors  = personCount + vehicleCount + bikeCount + animalCount;

        StringBuilder msg = new StringBuilder();

        // ---- Title line: "<icon> *<TIER> · <Class> at <camera>*" ----
        boolean haveActorInfo = totalActors > 0;
        String primary = haveActorInfo
                ? chooseTelegramPrimary(personCount, vehicleCount, bikeCount, animalCount)
                : null;
        String tierIcon;
        String tierWord;
        if ("CRITICAL".equalsIgnoreCase(severity))    { tierIcon = "🚨"; tierWord = "CRITICAL"; }
        else if ("ALERT".equalsIgnoreCase(severity))  { tierIcon = "⚠️"; tierWord = "Alert"; }
        else                                          { tierIcon = "👁"; tierWord = null; }

        // Camera is the only free-form interpolation outside backticks. Today
        // it's enum-bounded ("front"/"right"/"rear"/"left") so safe, but
        // mdEscape it defensively so a future user-supplied label can't break
        // Markdown rendering with a stray *, _, `, or [.
        String safeCamera = camera.isEmpty() ? "" : mdEscape(camera);
        msg.append(tierIcon).append(" *");
        if (tierWord != null && haveActorInfo) {
            msg.append(tierWord).append(" · ").append(primary);
            if (!safeCamera.isEmpty()) msg.append(" at ").append(safeCamera);
        } else if (tierWord != null) {
            msg.append(tierWord);
            if (!safeCamera.isEmpty()) msg.append(" at ").append(safeCamera);
        } else if (haveActorInfo) {
            msg.append(primary);
            if (!safeCamera.isEmpty()) msg.append(" at ").append(safeCamera);
        } else if (!safeCamera.isEmpty()) {
            msg.append("Motion at ").append(safeCamera);
        } else {
            msg.append("Motion detected");
        }
        msg.append("*\n");

        // ---- Body line ----
        if (finalized) {
            String prox = proximityPhraseTelegram(closestProximity);
            StringBuilder body = new StringBuilder();
            if (!prox.isEmpty()) body.append(prox);
            if (totalActors > 1) {
                if (body.length() > 0) body.append(" · ");
                body.append(formatTelegramCounts(personCount, vehicleCount, bikeCount, animalCount));
            }
            if (body.length() > 0) {
                msg.append(body).append("\n");
            }
        } else {
            // Start-stage: minimal "in progress" line, mirrors the PWA
            // "Recording in progress" body.
            msg.append("_Recording in progress_\n");
        }

        // ---- Footer ----
        if (!videoFilename.isEmpty()) {
            msg.append("📹 `").append(videoFilename).append("`");
            if (!finalized) {
                msg.append("\n📥 `/download ").append(videoFilename).append("`");
            }
        }
        return msg.toString();
    }

    /**
     * Escape Markdown legacy parse_mode metacharacters in free-form text that
     * lands outside backticks. Underscores in filenames are the most common
     * offender (the .mp4 timestamp format itself is rich in {@code _}).
     * Preserves readability — no zero-width characters, just backslash-escapes.
     */
    private static String mdEscape(String s) {
        if (s == null || s.isEmpty()) return s == null ? "" : s;
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '_' || c == '*' || c == '`' || c == '[') b.append('\\');
            b.append(c);
        }
        return b.toString();
    }

    private static String chooseTelegramPrimary(int p, int v, int b, int a) {
        // Match the engine-side classRank: PERSON > BIKE > VEHICLE > ANIMAL.
        // Single-actor case shows just the class word; the body line carries
        // the count breakdown when there's more than one actor.
        if (p > 0) return "Person";
        if (b > 0) return "Bike";
        if (v > 0) return "Vehicle";
        if (a > 0) return "Animal";
        return "Motion";
    }

    /** Capitalised proximity phrase for the body's lead clause. */
    private static String proximityPhraseTelegram(String enumName) {
        if (enumName == null) return "";
        switch (enumName) {
            case "VERY_CLOSE": return "Very close";
            case "CLOSE":      return "Close";
            case "MID":        return "Mid range";
            case "FAR":        return "Far";
            default:           return "";
        }
    }

    /** Pluralised count list: "1 person, 2 vehicles" — drops "× n" formatter. */
    private static String formatTelegramCounts(int p, int v, int b, int a) {
        java.util.List<String> parts = new java.util.ArrayList<>(4);
        if (p > 0) parts.add(p + " " + (p == 1 ? "person"  : "people"));
        if (v > 0) parts.add(v + " " + (v == 1 ? "vehicle" : "vehicles"));
        if (b > 0) parts.add(b + " " + (b == 1 ? "bike"    : "bikes"));
        if (a > 0) parts.add(a + " " + (a == 1 ? "animal"  : "animals"));
        return String.join(", ", parts);
    }

    public static boolean sendVideo(long chatId, String videoPath, String caption) {
        try {
            File videoFile = new File(videoPath);
            if (!videoFile.exists()) {
                log("Video file not found: " + videoPath);
                return false;
            }

            String url = TELEGRAM_API_BASE() + botToken + "/sendVideo";

            for (int attempt = 0; attempt < 2; attempt++) {
                MultipartBody.Builder bodyBuilder = new MultipartBody.Builder()
                        .setType(MultipartBody.FORM)
                        .addFormDataPart("chat_id", String.valueOf(chatId))
                        .addFormDataPart("supports_streaming", "true")
                        .addFormDataPart("video", videoFile.getName(),
                                RequestBody.create(videoFile, MediaType.parse("video/mp4")));

                if (caption != null && !caption.isEmpty()) {
                    bodyBuilder.addFormDataPart("caption", caption);
                }

                Request request = new Request.Builder()
                        .url(url)
                        .post(bodyBuilder.build())
                        .build();

                try (Response response = httpClient.newCall(request).execute()) {
                    if (response.isSuccessful()) {
                        log("Video sent: " + videoPath);
                        return true;
                    }
                    if (response.code() == 429 && attempt == 0) {
                        long sleepSec = parseRetryAfter(response, 5L);
                        log("sendVideo 429 — sleeping " + sleepSec + "s before retry");
                        try { Thread.sleep(Math.min(sleepSec * 1000L, 60_000L)); }
                        catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            return false;
                        }
                        continue;
                    }
                    log("sendVideo HTTP error: " + response.code());
                    return false;
                }
            }
            return false;
        } catch (Exception e) {
            log("sendVideo error: " + e.getMessage());
            return false;
        }
    }
    
    // ==================== UTILITIES ====================
    
    private static JSONObject sendIpcCommand(int port, JSONObject command) {
        return sendIpcCommand(port, command, 5000);
    }

    private static JSONObject sendIpcCommand(int port, JSONObject command, int timeoutMs) {
        Socket socket = null;
        try {
            socket = new Socket("127.0.0.1", port);
            socket.setSoTimeout(timeoutMs);

            PrintWriter writer = new PrintWriter(socket.getOutputStream(), true);
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            writer.println(command.toString());
            String response = reader.readLine();

            return response != null ? new JSONObject(response) : null;
        } catch (Exception e) {
            log("IPC error (port " + port + "): " + e.getMessage());
            return null;
        } finally {
            if (socket != null) {
                try { socket.close(); } catch (Exception ignored) {}
            }
        }
    }
    
    /**
     * Bounded shell exec: 30 s ceiling on waitFor + an explicit destroyForcibly
     * on timeout. Use ONLY for short-lived commands (ps, pgrep, cat, pm path,
     * ls, echo, rm, kill). For long-lived spawns use {@link #spawnDetached}.
     *
     * The 30 s budget is generous for the read-only commands that actually
     * call this; the point is a hard ceiling so a hung binary or zombie pipe
     * can't permanently freeze the polling thread (which would lock /pair,
     * /events, and every other handler until reboot).
     */
    private static String execShell(String command) {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[]{"sh", "-c", command});
            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (output.length() > 0) output.append("\n");
                output.append(line);
            }
            reader.close();
            if (!p.waitFor(30, TimeUnit.SECONDS)) {
                log("Shell timeout after 30s, destroying: " + command);
                p.destroyForcibly();
                return null;
            }
            return output.toString();
        } catch (Exception e) {
            log("Shell error: " + e.getMessage());
            if (p != null) {
                try { p.destroyForcibly(); } catch (Exception ignored) {}
            }
            return null;
        }
    }
    
    private static void saveOwnerToConfig(long chatId, String username, String firstName) {
        try {
            com.overdrive.app.telegram.config.UnifiedTelegramConfig.setOwner(
                    chatId, username, firstName, System.currentTimeMillis());
            log("Owner saved to unified config: " + chatId);
        } catch (Exception e) {
            log("Save owner error: " + e.getMessage());
        }
    }
    
    private static void saveTunnelUrl(String url) {
        try {
            File urlFile = new File(PATH_TELEGRAM_URL_FILE());
            try (java.io.FileWriter fw = new java.io.FileWriter(urlFile)) {
                fw.write(url);
            }
            log("Tunnel URL saved to file: " + url);
        } catch (Exception e) {
            log("Save tunnel URL error: " + e.getMessage());
        }
    }

    /**
     * One-shot read of the post-update hint file. If present, returns the
     * trimmed version string and deletes the file so the next tunnel restart
     * uses the generic message. Returns null if the hint isn't there or the
     * read fails.
     *
     * Path is duplicated (not pulled from UpdateLifecycle) because this code
     * runs in the daemon process which loads classes lazily and we want to
     * avoid pulling the whole updater package transitively.
     */
    private static String consumePostUpdateHint() {
        File hint = new File("/data/local/tmp/overdrive_post_update_pending_telegram");
        if (!hint.exists()) return null;
        String version = null;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(hint)))) {
            String line = r.readLine();
            if (line != null) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) version = trimmed;
            }
        } catch (Exception e) {
            log("Post-update hint read error: " + e.getMessage());
        }
        // Delete unconditionally — even if the read failed we don't want a
        // stale hint to keep flagging unrelated tunnel restarts.
        try { hint.delete(); } catch (Exception ignored) {}
        return version;
    }
    
    // ==================== PUBLIC API FOR NOTIFICATIONS ====================
    
    public static long getOwnerChatId() {
        return ownerChatId;
    }
    
    public static String getBotToken() {
        return botToken;
    }
}
