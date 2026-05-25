package com.overdrive.app.launcher

import android.content.Context
import com.overdrive.app.config.SecretConfigBridge
import com.overdrive.app.logging.LogManager

/**
 * Launches Zrok tunnel processes via ADB shell for remote access.
 * 
 * MODES:
 * 1. RESERVED MODE (Recommended): Uses a pre-reserved token for permanent URL
 *    - URL never changes: https://<unique-name>.share.zrok.io
 *    - Requires one-time setup: `zrok reserve public http://localhost:8080 --unique-name <name>`
 *    - Then use: `zrok share reserved <token>`
 * 
 * 2. PUBLIC MODE (Fallback): Creates random URL each time
 *    - URL changes on every restart
 *    - Uses: `zrok share public http://localhost:8080`
 * 
 * IMPORTANT: Zrok has a 5-device limit on the free tier!
 * - `zrok enable <token>` = Register device (LIMITED to 5 times total!)
 * - `zrok share` = Start tunnel (UNLIMITED restarts)
 * - `zrok reserve` = Reserve a permanent URL (counts as 1 share slot)
 * 
 * Uses AdbShellExecutor for shell operations.
 */
class ZrokLauncher(
    private val context: Context,
    private val adbShellExecutor: AdbShellExecutor,
    private val logManager: LogManager
) {
    companion object {
        private const val TAG = "ZrokLauncher"
        
        // Zrok paths
        private const val ZROK_TMP_PATH = "/data/local/tmp/zrok"
        private const val ZROK_LOG = "/data/local/tmp/zrok.log"
        private const val ZROK_HOME = "/data/local/tmp"
        
        // Identity file - THIS IS THE KEY FILE that proves device is enabled
        private const val ZROK_IDENTITY_FILE = "/data/local/tmp/.zrok/environment.json"
        
        // Unique name file - stores the generated unique name
        private const val ZROK_UNIQUE_NAME_FILE = "/data/local/tmp/.zrok/unique_name"
        
        // Process name for identification
        private const val ZROK_PROCESS = "zrok"
        
        // Default enable token - can be overridden via setEnableToken()
        // This is loaded from unified storage at runtime
        var zrokToken: String = ""
        
        // Flag to track if token has been loaded from storage
        private var tokenLoaded = false
        
        // Reserved share token (obtained from `zrok reserve` command)
        // Set this after running reserve command once
        var reservedShareToken: String? = null
        
        // Unique name for reserved URL (e.g., "overdrive1a2b3c" -> https://overdrive1a2b3c.share.zrok.io)
        // Generated automatically - must be lowercase alphanumeric only, 4-32 chars
        var uniqueName: String = "overdrive"
        
        // Prefix for unique name generation (no hyphens allowed!)
        private const val UNIQUE_NAME_PREFIX = "overdrive"
        
        // Proxy settings for sing-box (socks5 for zrok)
        private const val PROXY_HOST = "127.0.0.1"
        private const val PROXY_PORT = "8119"
        
        /**
         * Generate a unique name for this device.
         * Format: overdrive<6-char-random>
         * Must be lowercase alphanumeric only, 4-32 chars (zrok requirement).
         * Returns a NEW random value each time called.
         */
        fun generateUniqueName(context: Context): String {
            // Generate random 6-char alphanumeric string (lowercase only, no hyphens)
            val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
            val random = java.util.Random()
            val suffix = (1..6)
                .map { chars[random.nextInt(chars.length)] }
                .joinToString("")
            
            return "$UNIQUE_NAME_PREFIX$suffix"
        }
    }
    
    interface ZrokCallback {
        fun onLog(message: String)
        fun onTunnelUrl(url: String)
        fun onError(error: String)
    }
    
    /**
     * Launch Zrok tunnel via ADB shell.
     * 
     * Priority:
     * 1. If reservedShareToken is set, use reserved mode (permanent URL)
     * 2. Otherwise, use public mode (random URL)
     * 
     * NOTE: Zrok and Cloudflared are mutually exclusive - this will kill cloudflared first.
     */
    fun launchZrok(callback: ZrokCallback) {
        logManager.info(TAG, "Launching Zrok tunnel...")
        callback.onLog("Loading token...")
        
        // First ensure token is loaded from unified storage
        ensureTokenLoaded { hasToken ->
            if (!hasToken) {
                logManager.error(TAG, "No enable token configured!")
                callback.onError("❌ No Zrok token configured. Please set your token in Daemons settings.")
                return@ensureTokenLoaded
            }
            
            callback.onLog("Checking for existing tunnel...")
            
            // Kill cloudflared first (mutually exclusive)
            killCloudflaredIfRunning {
                // Then check if zrok tunnel is already running
                isTunnelRunning { isRunning ->
                    if (isRunning) {
                        // Tunnel already running, try to get existing URL
                        logManager.info(TAG, "Zrok already running, checking for URL...")
                        callback.onLog("Tunnel already running, getting URL...")
                        getTunnelUrl { existingUrl ->
                            if (existingUrl != null) {
                                logManager.info(TAG, "Reusing existing tunnel: $existingUrl")
                                callback.onLog("Reusing existing tunnel")
                                callback.onTunnelUrl(existingUrl)
                            } else {
                                // Running but no URL - wait for it
                                logManager.info(TAG, "Tunnel running but no URL yet, waiting...")
                                callback.onLog("Waiting for tunnel URL...")
                                waitForTunnelUrl(callback, 1)
                            }
                        }
                    } else {
                        // Not running, check if binary is installed
                        callback.onLog("Setting up zrok...")
                        checkAndInstallZrok(callback)
                    }
                }
            }
        }
    }
    
    /**
     * Launch Zrok in RESERVED mode with a specific token.
     * This gives you a permanent URL that never changes.
     * 
     * @param shareToken The reserved share token (from `zrok reserve` command)
     * @param permanentUrl The known permanent URL (e.g., https://byd-sentry-01.share.zrok.io)
     */
    fun launchZrokReserved(shareToken: String, permanentUrl: String, callback: ZrokCallback) {
        reservedShareToken = shareToken
        logManager.info(TAG, "Launching Zrok in RESERVED mode...")
        callback.onLog("Starting reserved tunnel...")
        
        // Kill cloudflared first (mutually exclusive)
        killCloudflaredIfRunning {
            isTunnelRunning { isRunning ->
                if (isRunning) {
                    logManager.info(TAG, "Zrok already running")
                    callback.onLog("Tunnel already running")
                    // Even on the fast path, reconcile against the running
                    // tunnel's actual URL — the zrok process from a previous
                    // boot may have bound a name that disagrees with the
                    // permanentUrl the app cached. Same drift case as the
                    // fresh-launch path above.
                    reconcileTunnelUrl(permanentUrl, attempt = 1) { actualUrl ->
                        callback.onTunnelUrl(actualUrl)
                    }
                } else {
                    callback.onLog("Setting up zrok...")
                    checkAndInstallZrokForReserved(shareToken, permanentUrl, callback)
                }
            }
        }
    }
    
    /**
     * Reserve a permanent URL (ONE-TIME setup).
     * Run this once to get a reserved share token.
     * 
     * @param customName Optional custom name. If null, uses auto-generated unique name.
     * @return The reserved share token via callback
     */
    fun reservePermanentUrl(customName: String? = null, callback: ZrokCallback) {
        // Use custom name or load/generate unique name
        if (customName != null) {
            uniqueName = customName
            saveUniqueName(customName)
            doReservePermanentUrl(callback)
        } else {
            loadSavedUniqueName { savedName ->
                if (savedName != null) {
                    uniqueName = savedName
                } else {
                    uniqueName = generateUniqueName(context)
                    saveUniqueName(uniqueName)
                }
                doReservePermanentUrl(callback)
            }
        }
    }
    
    private fun doReservePermanentUrl(callback: ZrokCallback) {
        logManager.info(TAG, "Reserving permanent URL with name: $uniqueName")
        callback.onLog("Reserving: https://$uniqueName.share.zrok.io")
        
        // First ensure device is enabled
        checkAndInstallZrok(object : ZrokCallback {
            override fun onLog(message: String) {
                callback.onLog(message)
            }
            
            override fun onTunnelUrl(url: String) {
                // After enable, run reserve command
                runReserveCommand(uniqueName, callback)
            }
            
            override fun onError(error: String) {
                callback.onError(error)
            }
        })
    }
    
    private fun runReserveCommand(uniqueName: String, callback: ZrokCallback) {
        // Check if sing-box proxy is running
        adbShellExecutor.execute(
            command = "pgrep -f sing-box",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    val useProxy = output.trim().isNotEmpty()
                    executeReserveCommand(uniqueName, useProxy, callback)
                }
                
                override fun onError(error: String) {
                    executeReserveCommand(uniqueName, false, callback)
                }
            }
        )
    }
    
    private fun executeReserveCommand(uniqueName: String, useProxy: Boolean, callback: ZrokCallback) {
        val cmd = buildString {
            append("HOME=$ZROK_HOME ")
            
            if (useProxy) {
                val proxyUrl = "socks5h://$PROXY_HOST:$PROXY_PORT"
                append("ALL_PROXY=$proxyUrl ")
                append("HTTP_PROXY=$proxyUrl ")
                append("HTTPS_PROXY=$proxyUrl ")
                append("NO_PROXY=localhost,127.0.0.1 ")
            }
            
            // Note: `zrok reserve` doesn't support --headless
            append("$ZROK_TMP_PATH reserve public http://localhost:8080 --unique-name $uniqueName 2>&1")
        }
        
        logManager.debug(TAG, "Executing reserve: $cmd")
        callback.onLog("Running reserve command...")
        
        adbShellExecutor.execute(
            command = cmd,
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    logManager.info(TAG, "Reserve output: $output")
                    
                    // Parse the reserved token from output
                    // Expected: [INFO] your reserved share token is 'abc-xyz-123'
                    val tokenPattern = Regex("token is '([^']+)'")
                    val match = tokenPattern.find(output)
                    
                    if (match != null) {
                        val token = match.groupValues[1]
                        logManager.info(TAG, "✅ Reserved token: $token")
                        callback.onLog("✅ Reserved! Token: $token")
                        callback.onLog("Permanent URL: https://$uniqueName.share.zrok.io")
                        
                        // Save token to file for persistence
                        saveReservedToken(token)
                        reservedShareToken = token
                        
                        // Return the permanent URL
                        callback.onTunnelUrl("https://$uniqueName.share.zrok.io")
                    } else if (output.contains("already reserved") || output.contains("exists")) {
                        callback.onLog("⚠️ Name already reserved. Use existing token.")
                        callback.onError("Name '$uniqueName' already reserved. Check saved token or use different name.")
                    } else {
                        callback.onError("Failed to reserve: $output")
                    }
                }
                
                override fun onError(error: String) {
                    logManager.error(TAG, "Reserve failed: $error")
                    callback.onError("Reserve failed: $error")
                }
            }
        )
    }
    
    fun saveReservedToken(token: String) {
        if (SecretConfigBridge.putString("zrok", "reservedToken", token)) {
            logManager.info(TAG, "Reserved token saved to secret store")
        } else {
            logManager.warn(TAG, "Failed to save reserved token to secret store")
        }
    }
    
    /**
     * Load saved reserved token from file.
     */
    fun loadReservedToken(callback: (String?) -> Unit) {
        val token = SecretConfigBridge.getString("zrok", "reservedToken")
        if (!token.isNullOrEmpty()) {
            reservedShareToken = token
            callback(token)
        } else {
            callback(null)
        }
    }
    
    private fun checkAndInstallZrokForReserved(shareToken: String, permanentUrl: String, callback: ZrokCallback) {
        adbShellExecutor.execute(
            command = "test -x $ZROK_TMP_PATH && echo yes || echo no",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    if (output.trim() == "yes") {
                        checkEnableAndLaunchReserved(shareToken, permanentUrl, callback)
                    } else {
                        installZrokThenReserved(shareToken, permanentUrl, callback)
                    }
                }
                
                override fun onError(error: String) {
                    installZrokThenReserved(shareToken, permanentUrl, callback)
                }
            }
        )
    }
    
    private fun installZrokThenReserved(shareToken: String, permanentUrl: String, callback: ZrokCallback) {
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        val srcPath = "$nativeLibDir/libzrok.so"
        
        callback.onLog("Installing zrok...")
        
        adbShellExecutor.execute(
            command = "test -f $srcPath && cp $srcPath $ZROK_TMP_PATH && chmod +x $ZROK_TMP_PATH && echo ok || echo fail",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    if (output.trim() == "ok") {
                        callback.onLog("zrok installed")
                        checkEnableAndLaunchReserved(shareToken, permanentUrl, callback)
                    } else {
                        callback.onError("Failed to install zrok")
                    }
                }
                
                override fun onError(error: String) {
                    callback.onError("Failed to install zrok: $error")
                }
            }
        )
    }
    
    private fun checkEnableAndLaunchReserved(shareToken: String, permanentUrl: String, callback: ZrokCallback) {
        callback.onLog("Checking zrok identity...")
        
        adbShellExecutor.execute(
            command = "test -f $ZROK_IDENTITY_FILE && echo yes || echo no",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    if (output.trim() == "yes") {
                        logManager.info(TAG, "✅ Device already enabled")
                        callback.onLog("✅ Device enabled")
                        launchZrokShareReserved(shareToken, permanentUrl, callback)
                    } else {
                        logManager.warn(TAG, "⚠️ Device not enabled. Enabling now...")
                        callback.onLog("⚠️ Registering device...")
                        enableZrokThenReserved(shareToken, permanentUrl, callback)
                    }
                }
                
                override fun onError(error: String) {
                    enableZrokThenReserved(shareToken, permanentUrl, callback)
                }
            }
        )
    }
    
    private fun enableZrokThenReserved(shareToken: String, permanentUrl: String, callback: ZrokCallback) {
        adbShellExecutor.execute(
            command = "pgrep -f sing-box",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    val useProxy = output.trim().isNotEmpty()
                    enableZrokWithConfigThenReserved(shareToken, permanentUrl, useProxy, callback)
                }
                
                override fun onError(error: String) {
                    enableZrokWithConfigThenReserved(shareToken, permanentUrl, false, callback)
                }
            }
        )
    }
    
    private fun enableZrokWithConfigThenReserved(shareToken: String, permanentUrl: String, useProxy: Boolean, callback: ZrokCallback) {
        // Check for token first
        if (zrokToken.isEmpty()) {
            logManager.error(TAG, "No enable token configured!")
            callback.onError("❌ No Zrok token configured. Please set your token in Daemons settings.")
            return
        }
        
        val cmd = buildString {
            append("HOME=$ZROK_HOME ")
            if (useProxy) {
                val proxyUrl = "socks5://$PROXY_HOST:$PROXY_PORT"
                append("ALL_PROXY=$proxyUrl HTTP_PROXY=$proxyUrl HTTPS_PROXY=$proxyUrl NO_PROXY=localhost,127.0.0.1 ")
            }
            append("$ZROK_TMP_PATH enable $zrokToken --headless 2>&1")
        }
        
        adbShellExecutor.execute(
            command = cmd,
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    logManager.info(TAG, "Enable output: $output")
                    Thread.sleep(500)
                    launchZrokShareReserved(shareToken, permanentUrl, callback)
                }
                
                override fun onError(error: String) {
                    callback.onError("Failed to enable zrok: $error")
                }
            }
        )
    }
    
    /**
     * Launch zrok share in RESERVED mode.
     * Uses: `zrok share reserved <token> --headless`
     * This is the recommended mode for permanent URLs.
     */
    private fun launchZrokShareReserved(shareToken: String, permanentUrl: String, callback: ZrokCallback) {
        callback.onLog("Starting reserved share...")
        
        adbShellExecutor.execute(
            command = "pgrep -f sing-box",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    val useProxy = output.trim().isNotEmpty()
                    startZrokShareReservedProcess(shareToken, permanentUrl, useProxy, callback)
                }
                
                override fun onError(error: String) {
                    startZrokShareReservedProcess(shareToken, permanentUrl, false, callback)
                }
            }
        )
    }
    
    private fun startZrokShareReservedProcess(shareToken: String, permanentUrl: String, useProxy: Boolean, callback: ZrokCallback) {
        // Clear old log
        adbShellExecutor.execute(
            command = "rm -f $ZROK_LOG",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    launchReservedProcess(shareToken, permanentUrl, useProxy, callback)
                }
                override fun onError(error: String) {
                    launchReservedProcess(shareToken, permanentUrl, useProxy, callback)
                }
            }
        )
    }
    
    private fun launchReservedProcess(shareToken: String, permanentUrl: String, useProxy: Boolean, callback: ZrokCallback) {
        val cmd = buildString {
            append("nohup sh -c '")
            append("HOME=$ZROK_HOME ")

            if (useProxy) {
                val proxyUrl = "socks5://$PROXY_HOST:$PROXY_PORT"
                append("ALL_PROXY=$proxyUrl ")
                append("HTTP_PROXY=$proxyUrl ")
                append("HTTPS_PROXY=$proxyUrl ")
                append("NO_PROXY=localhost,127.0.0.1 ")
            }

            // RESERVED mode: uses token instead of public
            append("$ZROK_TMP_PATH share reserved $shareToken --headless")
            append("' > $ZROK_LOG 2>&1 &")
        }

        logManager.debug(TAG, "Executing reserved share: $cmd")

        adbShellExecutor.execute(
            command = cmd,
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    logManager.info(TAG, "✅ Reserved tunnel started!")
                    callback.onLog("✅ Tunnel started!")
                    // The reserved token's URL is decided by zrok server-side, NOT
                    // by this client's local `uniqueName` field. If the local file
                    // ever drifted from the token's actual reserved name (manual
                    // shell `zrok reserve`, factory-reset that wiped the unique_name
                    // file but kept the token, cross-device token copy, etc.), the
                    // local `permanentUrl` would be a fiction. Read the log and
                    // reconcile the file with what zrok actually bound. Without
                    // this, HttpServer.isPwaOrigin() rejects /sw.js on the live
                    // tunnel because its hostname doesn't match unique_name file.
                    reconcileTunnelUrl(permanentUrl, attempt = 1) { actualUrl ->
                        callback.onLog("Permanent URL: $actualUrl")
                        callback.onTunnelUrl(actualUrl)
                    }
                }

                override fun onError(error: String) {
                    logManager.error(TAG, "Failed to launch reserved share: $error")
                    callback.onError("Launch failed: $error")
                }
            }
        )
    }

    /**
     * Dedicated scheduler for reconcile retries. Using a separate thread
     * means the inter-attempt wait does NOT hold the shared AdbShellExecutor
     * hostage — DaemonLauncher (which uses the same executor to start
     * CameraDaemon) can interleave its shell commands between reconcile
     * attempts. Single-thread is fine: only one reconcile flow runs at a time.
     */
    private val reconcileScheduler: java.util.concurrent.ScheduledExecutorService =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, "zrok-reconcile").apply { isDaemon = true }
            }

    /**
     * Read zrok's log to discover the actual tunnel URL it bound, then
     * reconcile the local {@code unique_name} file + in-memory state with
     * what zrok server-side resolved the reserved token to. Falls back to
     * the locally-computed {@code expectedUrl} after a bounded number of
     * polls if the log never emits a URL (e.g. a transient share failure
     * already surfaced as an error elsewhere).
     *
     * Calls {@code onUrl} with the URL the rest of the launcher should
     * propagate to its callback. Idempotent — writing the same name back
     * to the file is harmless.
     */
    private fun reconcileTunnelUrl(expectedUrl: String, attempt: Int, onUrl: (String) -> Unit) {
        // 10 attempts × ~1s each = ~10s to find the URL in the log. zrok
        // typically emits within 1-2s; the higher cap covers slow links.
        if (attempt > 10) {
            logManager.warn(TAG, "reconcileTunnelUrl: log never emitted URL after 10 attempts, " +
                    "falling back to local expected=$expectedUrl")
            onUrl(expectedUrl)
            return
        }
        // Wait 1s on the scheduler thread, THEN enqueue a fast grep on the
        // shared ADB executor. The shared executor is only held for the
        // grep itself (~50ms), not the inter-attempt wait. CameraDaemon's
        // launch shell commands queued on the same executor can interleave
        // between attempts without delay.
        reconcileScheduler.schedule({
            adbShellExecutor.execute(
                    "grep -oE 'https://[a-z0-9]+\\.share\\.zrok\\.io' $ZROK_LOG 2>/dev/null | head -1",
                    object : AdbShellExecutor.ShellCallback {
                        override fun onSuccess(output: String) {
                            val actualUrl = output.trim()
                            if (actualUrl.isEmpty() || !actualUrl.startsWith("https://")) {
                                reconcileTunnelUrl(expectedUrl, attempt + 1, onUrl)
                                return
                            }
                            // Extract the unique-name segment from "https://<name>.share.zrok.io".
                            val nameRegex = Regex("^https://([a-z0-9]+)\\.share\\.zrok\\.io$")
                            val nameMatch = nameRegex.find(actualUrl)
                            if (nameMatch == null) {
                                logManager.warn(TAG, "reconcileTunnelUrl: couldn't parse unique-name from $actualUrl")
                                onUrl(actualUrl)
                                return
                            }
                            val actualName = nameMatch.groupValues[1]
                            if (actualName != uniqueName) {
                                logManager.warn(TAG,
                                        "Reserved tunnel name drifted: expected=$uniqueName actual=$actualName " +
                                        "→ rewriting unique_name file. (Likely cause: token was reserved " +
                                        "with a different name than the local file remembers — manual zrok " +
                                        "reserve, cross-device token copy, or factory-reset of /data/local/tmp/.zrok.)")
                                uniqueName = actualName
                                saveUniqueName(actualName)
                            } else {
                                logManager.info(TAG, "Tunnel URL reconciled: $actualUrl (matches local unique_name)")
                            }
                            onUrl(actualUrl)
                        }
                        override fun onError(error: String) {
                            reconcileTunnelUrl(expectedUrl, attempt + 1, onUrl)
                        }
                    }
            )
        }, 1, java.util.concurrent.TimeUnit.SECONDS)
    }
    
    /**
     * Kill cloudflared if running (mutual exclusion).
     */
    private fun killCloudflaredIfRunning(onComplete: () -> Unit) {
        adbShellExecutor.execute(
            command = "pgrep -f cloudflared",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    if (output.trim().isNotEmpty()) {
                        logManager.info(TAG, "Killing cloudflared (mutually exclusive with zrok)")
                        adbShellExecutor.execute(
                            command = "killall -9 cloudflared 2>/dev/null; echo done",
                            callback = object : AdbShellExecutor.ShellCallback {
                                override fun onSuccess(o: String) {
                                    Thread.sleep(300)
                                    onComplete()
                                }
                                override fun onError(e: String) { onComplete() }
                            }
                        )
                    } else {
                        onComplete()
                    }
                }
                override fun onError(error: String) { onComplete() }
            }
        )
    }
    
    private fun checkAndInstallZrok(callback: ZrokCallback) {
        adbShellExecutor.execute(
            command = "test -x $ZROK_TMP_PATH && echo yes || echo no",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    if (output.trim() == "yes") {
                        checkEnableAndLaunch(callback)
                    } else {
                        installZrok(callback)
                    }
                }
                
                override fun onError(error: String) {
                    installZrok(callback)
                }
            }
        )
    }
    
    private fun installZrok(callback: ZrokCallback) {
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        val srcPath = "$nativeLibDir/libzrok.so"
        
        callback.onLog("Installing zrok...")
        
        // Check if source exists
        adbShellExecutor.execute(
            command = "test -f $srcPath && echo yes || echo no",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    if (output.trim() != "yes") {
                        logManager.error(TAG, "libzrok.so not found")
                        callback.onError("libzrok.so not found. Add it to jniLibs/arm64-v8a/")
                        return
                    }
                    
                    // Copy and make executable
                    adbShellExecutor.execute(
                        command = "cp $srcPath $ZROK_TMP_PATH && chmod +x $ZROK_TMP_PATH",
                        callback = object : AdbShellExecutor.ShellCallback {
                            override fun onSuccess(copyOutput: String) {
                                callback.onLog("zrok installed")
                                checkEnableAndLaunch(callback)
                            }
                            
                            override fun onError(error: String) {
                                logManager.error(TAG, "Failed to install zrok: $error")
                                callback.onError("Failed to install zrok: $error")
                            }
                        }
                    )
                }
                
                override fun onError(error: String) {
                    logManager.error(TAG, "Failed to check zrok source: $error")
                    callback.onError("Failed to check zrok source: $error")
                }
            }
        )
    }

    /**
     * CRITICAL: Check if device is already enabled before running `zrok enable`.
     * Also checks for reserved token to use reserved mode if available.
     * 
     * The free tier only allows 5 device registrations TOTAL.
     * We check for environment.json to avoid wasting registrations.
     */
    private fun checkEnableAndLaunch(callback: ZrokCallback) {
        callback.onLog("Checking zrok identity...")
        
        // First load saved unique name (if any)
        loadSavedUniqueName { savedName ->
            if (savedName != null) {
                uniqueName = savedName
                logManager.info(TAG, "Loaded saved unique name: $uniqueName")
            } else {
                // Generate new unique name for this device
                uniqueName = generateUniqueName(context)
                logManager.info(TAG, "Generated unique name: $uniqueName")
                saveUniqueName(uniqueName)
            }
            
            // Then check if we have a reserved token
            loadReservedToken { savedToken ->
                if (savedToken != null) {
                    logManager.info(TAG, "Found saved reserved token, using reserved mode")
                    callback.onLog("✅ Using reserved mode (permanent URL)")
                    val permanentUrl = "https://$uniqueName.share.zrok.io"
                    checkEnableAndLaunchReserved(savedToken, permanentUrl, callback)
                    return@loadReservedToken
                }
                
                // No reserved token, check if reservedShareToken is set programmatically
                if (reservedShareToken != null) {
                    logManager.info(TAG, "Using programmatic reserved token")
                    callback.onLog("✅ Using reserved mode (permanent URL)")
                    val permanentUrl = "https://$uniqueName.share.zrok.io"
                    checkEnableAndLaunchReserved(reservedShareToken!!, permanentUrl, callback)
                    return@loadReservedToken
                }
                
                // No reserved token, use public mode (random URL)
                logManager.info(TAG, "No reserved token, using public mode")
                checkEnableAndLaunchPublic(callback)
            }
        }
    }
    
    private fun loadSavedUniqueName(callback: (String?) -> Unit) {
        adbShellExecutor.execute(
            command = "cat $ZROK_UNIQUE_NAME_FILE 2>/dev/null",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    val name = output.trim()
                    // Must be lowercase alphanumeric, 4-32 chars, starting with "overdrive"
                    if (name.isNotEmpty() && !name.contains("No such file") && 
                        name.startsWith("overdrive") && name.matches(Regex("^[a-z0-9]{4,32}$"))) {
                        callback(name)
                    } else {
                        callback(null)
                    }
                }
                override fun onError(error: String) {
                    callback(null)
                }
            }
        )
    }
    
    private fun saveUniqueName(name: String) {
        adbShellExecutor.execute(
            command = "mkdir -p /data/local/tmp/.zrok && echo '$name' > $ZROK_UNIQUE_NAME_FILE",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    logManager.info(TAG, "Unique name saved: $name")
                }
                override fun onError(error: String) {
                    logManager.warn(TAG, "Failed to save unique name: $error")
                }
            }
        )
    }
    
    private fun checkEnableAndLaunchPublic(callback: ZrokCallback) {
        // Check for the SPECIFIC identity file, not just the directory
        adbShellExecutor.execute(
            command = "test -f $ZROK_IDENTITY_FILE && echo yes || echo no",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    if (output.trim() == "yes") {
                        // Device already enabled - now check if we need to reserve
                        logManager.info(TAG, "✅ Device already enabled (environment.json exists)")
                        callback.onLog("✅ Device already enabled")
                        checkReserveAndLaunch(callback)
                    } else {
                        // Need to enable - THIS COUNTS AGAINST THE 5-DEVICE LIMIT!
                        logManager.warn(TAG, "⚠️ Device not enabled. Will register now (uses 1 of 5 slots)")
                        callback.onLog("⚠️ Registering device (1 of 5 allowed)...")
                        enableZrokEnvironment(callback)
                    }
                }
                
                override fun onError(error: String) {
                    // Can't check - try to enable anyway
                    logManager.warn(TAG, "Cannot check identity file, attempting enable")
                    enableZrokEnvironment(callback)
                }
            }
        )
    }
    
    /**
     * Check if we have a reserved token. If not, reserve one automatically.
     * This is similar to the enable check - reserve once, use forever.
     */
    private fun checkReserveAndLaunch(callback: ZrokCallback) {
        callback.onLog("Checking for reserved URL...")
        
        loadReservedToken { token ->
            if (!token.isNullOrEmpty()) {
                logManager.info(TAG, "✅ Found reserved token, using permanent URL")
                callback.onLog("✅ Using permanent URL")
                reservedShareToken = token
                val permanentUrl = "https://$uniqueName.share.zrok.io"
                launchZrokShareReserved(token, permanentUrl, callback)
            } else {
                // No reserved token - need to reserve first (ONE TIME)
                logManager.info(TAG, "⚠️ No reserved token. Reserving permanent URL...")
                callback.onLog("⚠️ Reserving permanent URL (one-time setup)...")
                autoReserveAndLaunch(callback)
            }
        }
    }
    
    /**
     * Automatically reserve a permanent URL and then launch.
     * This runs `zrok reserve` once, saves the token, then uses `zrok share reserved`.
     */
    private fun autoReserveAndLaunch(callback: ZrokCallback) {
        callback.onLog("Reserving: https://$uniqueName.share.zrok.io")
        
        // Check if sing-box proxy is running
        adbShellExecutor.execute(
            command = "pgrep -f sing-box",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    val useProxy = output.trim().isNotEmpty()
                    executeAutoReserve(useProxy, callback, 0)
                }
                
                override fun onError(error: String) {
                    executeAutoReserve(false, callback, 0)
                }
            }
        )
    }
    
    private fun executeAutoReserve(useProxy: Boolean, callback: ZrokCallback, retryCount: Int = 0) {
        if (retryCount > 3) {
            logManager.error(TAG, "Reserve failed after 3 retries")
            callback.onError("Failed to reserve URL after multiple attempts")
            return
        }
        
        val cmd = buildString {
            // Use timeout to prevent hanging (30 seconds max)
            append("timeout 30 sh -c '")
            append("HOME=$ZROK_HOME ")
            
            if (useProxy) {
                val proxyUrl = "socks5://$PROXY_HOST:$PROXY_PORT"
                append("ALL_PROXY=$proxyUrl ")
                append("HTTP_PROXY=$proxyUrl ")
                append("HTTPS_PROXY=$proxyUrl ")
                append("NO_PROXY=localhost,127.0.0.1 ")
            }
            
            // Note: `zrok reserve` doesn't support --headless, only `zrok share` does
            append("$ZROK_TMP_PATH reserve public http://localhost:8080 --unique-name $uniqueName")
            append("' 2>&1")
        }
        
        logManager.debug(TAG, "Executing auto-reserve (attempt ${retryCount + 1}): $cmd")
        callback.onLog("Reserving URL (attempt ${retryCount + 1})...")
        
        adbShellExecutor.execute(
            command = cmd,
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    logManager.info(TAG, "Reserve output: $output")
                    
                    // Check for timeout
                    if (output.isEmpty() || output.contains("timeout")) {
                        logManager.warn(TAG, "Reserve command timed out, retrying...")
                        callback.onLog("⚠️ Timeout, retrying...")
                        executeAutoReserve(useProxy, callback, retryCount + 1)
                        return
                    }
                    
                    // Parse the reserved token from output
                    // Expected: [INFO] your reserved share token is 'abc-xyz-123'
                    val tokenPattern = Regex("token is '([^']+)'")
                    val match = tokenPattern.find(output)
                    
                    if (match != null) {
                        val token = match.groupValues[1]
                        logManager.info(TAG, "✅ Reserved! Token: $token")
                        callback.onLog("✅ Reserved! Permanent URL ready")
                        
                        // Save token for future use
                        saveReservedToken(token)
                        reservedShareToken = token
                        
                        // Now launch with reserved token
                        val permanentUrl = "https://$uniqueName.share.zrok.io"
                        launchZrokShareReserved(token, permanentUrl, callback)
                    } else if (output.contains("already reserved") || output.contains("exists") || output.contains("duplicate")) {
                        // Name already taken - generate new name and retry
                        logManager.warn(TAG, "Name '$uniqueName' already taken, generating new name...")
                        callback.onLog("⚠️ Name taken, trying new name...")
                        uniqueName = generateUniqueName(context)
                        saveUniqueName(uniqueName)
                        executeAutoReserve(useProxy, callback, 0) // Reset retry count for new name
                    } else if (output.contains("error") || output.contains("failed") || output.contains("ERROR")) {
                        logManager.error(TAG, "Reserve failed: $output")
                        callback.onError("Failed to reserve URL: $output")
                    } else {
                        // Unexpected output - try to extract token anyway or fail
                        logManager.warn(TAG, "Unexpected reserve output: $output")
                        callback.onError("Reserve failed: $output")
                    }
                }
                
                override fun onError(error: String) {
                    logManager.error(TAG, "Reserve command failed: $error")
                    // Check if it's a timeout or network issue - retry
                    if (error.contains("timeout") || error.contains("connection")) {
                        callback.onLog("⚠️ Connection issue, retrying...")
                        executeAutoReserve(useProxy, callback, retryCount + 1)
                    } else {
                        callback.onError("Reserve failed: $error")
                    }
                }
            }
        )
    }
    
    /**
     * Enable zrok environment with token.
     * Uses same proxy detection as cloudflared.
     */
    private fun enableZrokEnvironment(callback: ZrokCallback) {
        callback.onLog("Enabling zrok environment...")
        
        // Check if sing-box proxy is running (same as cloudflared)
        adbShellExecutor.execute(
            command = "pgrep -f sing-box",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    val useProxy = output.trim().isNotEmpty()
                    if (useProxy) {
                        logManager.info(TAG, "Sing-box detected, using socks5 proxy")
                    }
                    enableZrokWithConfig(callback, useProxy)
                }
                
                override fun onError(error: String) {
                    logManager.info(TAG, "Sing-box not running, enabling zrok without proxy")
                    enableZrokWithConfig(callback, false)
                }
            }
        )
    }
    
    private fun enableZrokWithConfig(callback: ZrokCallback, useProxy: Boolean) {
        // First ensure we have a token
        if (zrokToken.isEmpty()) {
            logManager.error(TAG, "No enable token configured!")
            callback.onError("❌ No Zrok token configured. Please set your token in Daemons settings.")
            return
        }
        
        val cmd = buildString {
            append("HOME=$ZROK_HOME ")
            
            if (useProxy) {
                // Zrok uses socks5 proxy (different from cloudflared's http proxy)
                val proxyUrl = "socks5://$PROXY_HOST:$PROXY_PORT"
                append("ALL_PROXY=$proxyUrl ")
                append("HTTP_PROXY=$proxyUrl ")
                append("HTTPS_PROXY=$proxyUrl ")
                append("NO_PROXY=localhost,127.0.0.1 ")
                callback.onLog("Using sing-box socks5 proxy...")
            } else {
                callback.onLog("Direct connection (no proxy)...")
            }
            
            append("$ZROK_TMP_PATH enable $zrokToken --headless 2>&1")
        }
        
        logManager.debug(TAG, "Executing enable: ${cmd.replace(zrokToken, "<redacted>")}")
        
        adbShellExecutor.execute(
            command = cmd,
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    logManager.info(TAG, "Zrok enable output: $output")
                    
                    // Check for errors
                    val lowerOutput = output.lowercase()
                    when {
                        lowerOutput.contains("already enabled") || 
                        lowerOutput.contains("environment already") -> {
                            callback.onLog("✅ Zrok already enabled")
                            // After enable, check for reserve
                            checkReserveAndLaunch(callback)
                        }
                        lowerOutput.contains("error") || lowerOutput.contains("failed") -> {
                            if (lowerOutput.contains("limit") || lowerOutput.contains("maximum")) {
                                callback.onError("❌ Device limit reached! You've used all 5 registrations.")
                            } else {
                                callback.onError("Failed to enable zrok: $output")
                            }
                        }
                        else -> {
                            callback.onLog("✅ Zrok environment enabled")
                            // Small delay before checking reserve
                            Thread.sleep(500)
                            // After enable, check for reserve
                            checkReserveAndLaunch(callback)
                        }
                    }
                }
                
                override fun onError(error: String) {
                    logManager.error(TAG, "Failed to enable zrok: $error")
                    callback.onError("Failed to enable zrok: $error")
                }
            }
        )
    }
    
    /**
     * Launch zrok share public.
     * This is SAFE to run unlimited times - it doesn't count against device limit.
     */
    private fun launchZrokShare(callback: ZrokCallback) {
        callback.onLog("Starting zrok share (unlimited restarts OK)...")
        
        // Check if sing-box proxy is running (same as cloudflared)
        adbShellExecutor.execute(
            command = "pgrep -f sing-box",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    val useProxy = output.trim().isNotEmpty()
                    launchZrokShareWithConfig(callback, useProxy)
                }
                
                override fun onError(error: String) {
                    logManager.info(TAG, "Sing-box not running, launching zrok without proxy")
                    launchZrokShareWithConfig(callback, false)
                }
            }
        )
    }
    
    private fun launchZrokShareWithConfig(callback: ZrokCallback, useProxy: Boolean) {
        // Clear old log first
        adbShellExecutor.execute(
            command = "rm -f $ZROK_LOG",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    startZrokShareProcess(callback, useProxy)
                }
                override fun onError(error: String) {
                    startZrokShareProcess(callback, useProxy)
                }
            }
        )
    }
    
    private fun startZrokShareProcess(callback: ZrokCallback, useProxy: Boolean) {
        val cmd = buildString {
            append("nohup sh -c '")
            append("HOME=$ZROK_HOME ")
            
            if (useProxy) {
                // Zrok uses socks5 proxy
                val proxyUrl = "socks5://$PROXY_HOST:$PROXY_PORT"
                append("ALL_PROXY=$proxyUrl ")
                append("HTTP_PROXY=$proxyUrl ")
                append("HTTPS_PROXY=$proxyUrl ")
                append("NO_PROXY=localhost,127.0.0.1 ")
            }
            
            append("$ZROK_TMP_PATH share public http://localhost:8080 --headless")
            append("' > $ZROK_LOG 2>&1 &")
        }
        
        logManager.debug(TAG, "Executing share: $cmd")
        
        adbShellExecutor.execute(
            command = cmd,
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    logManager.info(TAG, "Zrok share command sent")
                    callback.onLog("Waiting for tunnel URL...")
                    waitForTunnelUrl(callback, 1)
                }
                
                override fun onError(error: String) {
                    logManager.error(TAG, "Failed to launch zrok share: $error")
                    callback.onError("Launch failed: $error")
                }
            }
        )
    }


    private fun waitForTunnelUrl(callback: ZrokCallback, attempt: Int) {
        if (attempt > 30) {
            // Timeout - get final log
            adbShellExecutor.execute(
                command = "cat $ZROK_LOG 2>/dev/null",
                callback = object : AdbShellExecutor.ShellCallback {
                    override fun onSuccess(output: String) {
                        logManager.error(TAG, "Zrok timed out. Log: ${output.takeLast(500)}")
                        callback.onError("Failed to get URL. Log tail:\n${output.takeLast(500)}")
                    }
                    
                    override fun onError(error: String) {
                        callback.onError("Timed out waiting for tunnel URL")
                    }
                }
            )
            return
        }
        
        Thread.sleep(1000)
        
        adbShellExecutor.execute(
            command = "cat $ZROK_LOG 2>/dev/null",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(logContent: String) {
                    // Zrok URL pattern: https://xxx.share.zrok.io
                    val zrokUrlPattern = Regex("https://([a-z0-9]+)\\.share\\.zrok\\.io")
                    val match = zrokUrlPattern.find(logContent)
                    
                    if (match != null) {
                        val tunnelUrl = match.value
                        logManager.info(TAG, "Tunnel established: $tunnelUrl")
                        callback.onLog("Tunnel established: $tunnelUrl")
                        callback.onTunnelUrl(tunnelUrl)
                        return
                    }
                    
                    // Check for proxy errors
                    if (logContent.contains("proxyconnect") ||
                        (logContent.contains("proxy") && logContent.contains("refused"))) {
                        logManager.error(TAG, "Proxy error - is sing-box running?")
                        callback.onError("Proxy Error: Is sing-box running on port $PROXY_PORT?\n${logContent.takeLast(200)}")
                        return
                    }
                    
                    // Check for connection errors
                    if (logContent.contains("connection refused") || logContent.contains("dial tcp")) {
                        logManager.error(TAG, "Connection error: $logContent")
                        callback.onError("Zrok connection error: ${logContent.takeLast(300)}")
                        return
                    }
                    
                    // Check for token/identity errors
                    val lowerContent = logContent.lowercase()
                    if (lowerContent.contains("invalid") && lowerContent.contains("token")) {
                        logManager.error(TAG, "Invalid token: $logContent")
                        callback.onError("Invalid zrok token. Please check your token.")
                        return
                    }
                    
                    // Check for identity not found (need to re-enable)
                    if (lowerContent.contains("identity") && lowerContent.contains("not found")) {
                        logManager.error(TAG, "Identity not found - need to re-enable")
                        callback.onError("Identity not found. Device may need re-registration.")
                        return
                    }
                    
                    callback.onLog("Waiting... ($attempt/30)")
                    waitForTunnelUrl(callback, attempt + 1)
                }
                
                override fun onError(error: String) {
                    callback.onLog("Waiting... ($attempt/30)")
                    waitForTunnelUrl(callback, attempt + 1)
                }
            }
        )
    }
    
    /**
     * Stop the zrok tunnel.
     * Safe to call - doesn't affect device registration.
     */
    fun stopTunnel(callback: ZrokCallback) {
        logManager.info(TAG, "Stopping zrok tunnel...")
        callback.onLog("Stopping tunnel...")
        
        // Kill zrok process and clear the log file so stale URLs don't cause false positives
        adbShellExecutor.execute(
            command = "pkill -9 -f 'zrok' 2>/dev/null; rm -f $ZROK_LOG; echo stopped",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    logManager.info(TAG, "Zrok tunnel stopped")
                    callback.onLog("Tunnel stopped")
                    callback.onTunnelUrl("")
                }
                
                override fun onError(error: String) {
                    // Even on error, consider it stopped
                    logManager.info(TAG, "Zrok tunnel stopped (with warning: $error)")
                    callback.onLog("Tunnel stopped")
                    callback.onTunnelUrl("")
                }
            }
        )
    }
    
    /**
     * Check if zrok tunnel is running.
     */
    fun isTunnelRunning(callback: (Boolean) -> Unit) {
        // Check for actual zrok share process specifically
        // Look for process with 'zrok share' in command line to avoid false positives
        adbShellExecutor.execute(
            command = "ps -A -o ARGS 2>/dev/null | grep 'zrok share' | grep -v grep | head -1",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    val isRunning = output.trim().isNotEmpty() && output.contains("zrok share")
                    logManager.debug(TAG, "isTunnelRunning check: $isRunning (output: '${output.trim().take(50)}')")
                    callback(isRunning)
                }
                
                override fun onError(error: String) {
                    // grep returns exit code 1 when no match - that's expected when not running
                    logManager.debug(TAG, "isTunnelRunning: not running (grep found nothing)")
                    callback(false)
                }
            }
        )
    }
    
    /**
     * Get current tunnel URL from log file.
     * SOTA FIX: Only read last 50 lines to avoid loading entire log into memory.
     * The URL appears early in the log, but we use tail to limit memory usage.
     */
    fun getTunnelUrl(callback: (String?) -> Unit) {
        // Use grep to find URL directly instead of loading entire log
        // This eliminates the 85MB+ allocations from reading large log files
        adbShellExecutor.execute(
            command = "grep -o 'https://[a-z0-9]*\\.share\\.zrok\\.io' $ZROK_LOG 2>/dev/null | head -1",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    val url = output.trim()
                    if (url.isNotEmpty() && url.startsWith("https://")) {
                        logManager.info(TAG, "Found tunnel URL: $url")
                        callback(url)
                    } else {
                        logManager.debug(TAG, "No tunnel URL found in log")
                        callback(null)
                    }
                }
                
                override fun onError(error: String) {
                    logManager.warn(TAG, "Zrok log not found - tunnel may need restart")
                    callback(null)
                }
            }
        )
    }
    
    /**
     * Check if device is already enabled (has identity file).
     */
    fun isDeviceEnabled(callback: (Boolean) -> Unit) {
        adbShellExecutor.execute(
            command = "test -f $ZROK_IDENTITY_FILE && echo yes || echo no",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    callback(output.trim() == "yes")
                }
                
                override fun onError(error: String) {
                    callback(false)
                }
            }
        )
    }
    
    /**
     * Disable zrok environment (cleanup).
     * WARNING: This will require re-enabling which uses one of your 5 device slots!
     */
    fun disableEnvironment(callback: ZrokCallback? = null) {
        logManager.warn(TAG, "⚠️ Disabling zrok environment - will need to re-register!")
        callback?.onLog("⚠️ Disabling environment (will need re-registration)...")
        
        adbShellExecutor.execute(
            command = "HOME=$ZROK_HOME $ZROK_TMP_PATH disable 2>&1; rm -rf $ZROK_HOME/.zrok 2>/dev/null; echo done",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    logManager.info(TAG, "Zrok environment disabled")
                    callback?.onLog("Environment disabled")
                    callback?.onTunnelUrl("")
                }
                
                override fun onError(error: String) {
                    logManager.warn(TAG, "Failed to disable zrok: $error")
                    callback?.onError("Failed to disable: $error")
                }
            }
        )
    }
    
    // ==================== Enable Token Management ====================
    
    /**
     * Save enable token to the daemon-owned secret store.
     */
    fun saveEnableToken(token: String, callback: ((Boolean) -> Unit)? = null) {
        val trimmedToken = token.trim()
        if (trimmedToken.isEmpty()) {
            callback?.invoke(false)
            return
        }
        val ok = SecretConfigBridge.putString("zrok", "enableToken", trimmedToken)
        if (ok) {
            zrokToken = trimmedToken
            tokenLoaded = true
            logManager.info(TAG, "Enable token saved to secret store")
        } else {
            logManager.error(TAG, "Failed to save enable token to secret store")
        }
        callback?.invoke(ok)
    }
    
    /**
     * Load enable token from the daemon-owned secret store.
     * Returns the token via callback, or null if not found.
     */
    fun loadEnableToken(callback: (String?) -> Unit) {
        val token = SecretConfigBridge.getString("zrok", "enableToken")
        if (!token.isNullOrEmpty()) {
            zrokToken = token
            tokenLoaded = true
            logManager.info(TAG, "Enable token loaded from secret store")
            callback(token)
        } else {
            callback(null)
        }
    }
    
    /**
     * Delete enable token from unified storage AND wipe everything else
     * derived from the previous zrok account.
     *
     * The reserved_token and unique_name belong to the account whose enable
     * token we just deleted — leaving them on disk causes `share reserved`
     * to fail or, worse, causes the next start to auto-reserve a new name
     * under the new account and silently rotate the public URL. That breaks
     * every PWA install on every phone (push subscriptions are origin-bound).
     *
     * environment.json is the account identity; carrying it forward into a
     * different account is incoherent. Kill the entire ~/.zrok directory.
     */
    fun deleteEnableToken(callback: ((Boolean) -> Unit)? = null) {
        val secretOk = SecretConfigBridge.delete("zrok", "enableToken")
        val reservedOk = SecretConfigBridge.delete("zrok", "reservedToken")
        if (secretOk && reservedOk) {
            zrokToken = ""
            tokenLoaded = false
            reservedShareToken = null
            uniqueName = UNIQUE_NAME_PREFIX
        }

        adbShellExecutor.execute(
            command = "rm -rf $ZROK_HOME/.zrok 2>/dev/null; echo done",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    logManager.info(TAG, "Zrok state wiped (token, identity, reserved share, unique name)")
                    callback?.invoke(secretOk && reservedOk)
                }
                override fun onError(error: String) {
                    logManager.warn(TAG, "Failed to wipe zrok state: $error")
                    callback?.invoke(false)
                }
            }
        )
    }
    
    /**
     * Check if enable token exists in the secret store.
     */
    fun hasEnableToken(callback: (Boolean) -> Unit) {
        callback(!SecretConfigBridge.getString("zrok", "enableToken").isNullOrEmpty())
    }
    
    /**
     * Ensure enable token is loaded before operations.
     * Call this before launchZrok() to ensure token is available.
     */
    fun ensureTokenLoaded(callback: (Boolean) -> Unit) {
        if (tokenLoaded && zrokToken.isNotEmpty()) {
            callback(true)
            return
        }
        
        loadEnableToken { token ->
            callback(token != null)
        }
    }
}
