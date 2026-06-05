package com.overdrive.app.launcher

import android.content.Context
import com.overdrive.app.logging.LogManager

/**
 * Launches Tailscale tunnel processes via ADB shell for remote access.
 * 
 * Uses AdbShellExecutor for shell operations.
 */
class TailscaleLauncher(
    private val context: Context,
    private val adbShellExecutor: AdbShellExecutor,
    private val logManager: LogManager
) {
    companion object {
        private const val TAG = "TailscaleLauncher"

        // Tailscale paths
        private const val TAILSCALE_HOME = "/data/local/tmp/.tailscale"
        private const val TAILSCALE_LOG = "$TAILSCALE_HOME/tailscale.log"
        private const val TAILSCALE_PATH = "$TAILSCALE_HOME/tailscale"
        private const val TAILSCALED_PATH = "$TAILSCALE_HOME/tailscaled"

        private const val TAILSCALE_COMMUNICATION_PORT = "8532"

        private const val TAILSCALE_PROXY_FILE = "$TAILSCALE_HOME/proxy_enabled"
        private const val TAILSCALE_PROXY_PORT = "8539"

        // Proxy settings for sing-box (socks5 for tailscale)
        private const val PROXY_HOST = "127.0.0.1"
        private const val PROXY_PORT = "8119"
    }

    interface TailscaleCallback {
        fun onLog(message: String)
        fun onTunnelUrl(url: String?)
        fun onError(error: String)
    }

    fun launchTailscale(callback: TailscaleCallback) {
        isTunnelRunning { isRunning ->
            if (isRunning) {
                getTunnelUrl { url ->
                    if (url != null) {
                        logManager.info(TAG, "Tailscale already running at $url")
                        callback.onLog("Tailscale already running at $url")
                        callback.onTunnelUrl(url)
                    } else {
                        logManager.error(TAG, "Failed to get tailscale url. Are you logged in?")
                        callback.onError("Failed to get tailscale url. Are you logged in?")
                        callback.onTunnelUrl(null)
                    }
                }
            } else {
                checkAndInstallTailscale(callback) {
                    isSingboxActive { active ->
                        isProxyEnabled { enableProxy ->
                            launchTailscaleDaemon(active, enableProxy, callback)
                        }
                    }
                }
            }
        }
    }

    fun launchTailscaleDaemon(useProxy: Boolean, enableProxy: Boolean, callback: TailscaleCallback) {
        val cmd = buildString {
            append("nohup sh -c '")

            if (useProxy) {
                val proxyUrl = "socks5://$PROXY_HOST:$PROXY_PORT"
                append("ALL_PROXY=$proxyUrl ")
                append("HTTP_PROXY=$proxyUrl ")
                append("HTTPS_PROXY=$proxyUrl ")
                append("NO_PROXY=localhost,127.0.0.1 ")
            }
            append(TAILSCALED_PATH)
            // Userspace networking required for android
            append(" --tun userspace-networking")
            // Where to store tailscale data
            append(" --statedir $TAILSCALE_HOME")
            // Communication port to listen to for tailscale commands
            append(" --socket 127.0.0.1:$TAILSCALE_COMMUNICATION_PORT")

            // Optionally start socks5 proxy to access other tailscale devices
            if (enableProxy) {
                append(" --socks5-server 127.0.0.1:$TAILSCALE_PROXY_PORT")
            }

            append("' > $TAILSCALE_LOG 2>&1 &")
        }
        adbShellExecutor.execute(
            command = cmd,
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    logManager.info(TAG, "Tailscale daemon started")
                    callback.onLog("Tailscale daemon started")
                    getTunnelUrl { url ->
                        callback.onLog("Connect to tailscale to access $url")
                        callback.onTunnelUrl(url)
                    }
                }

                override fun onError(error: String) {
                    logManager.error(TAG, "Failed to start tailscale daemon: $error")
                    callback.onError("Failed to start tailscale daemon: $error")
                }
            }
        )
    }

    private fun checkAndInstallTailscale(callback: TailscaleCallback, onComplete: () -> Unit) {
        adbShellExecutor.execute(
            command = "test -x $TAILSCALE_PATH && test -x $TAILSCALED_PATH",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    onComplete()
                }

                override fun onError(error: String) {
                    installTailscale(callback, onComplete)
                }
            }
        )
    }

    private fun installTailscale(callback: TailscaleCallback, onComplete: () -> Unit) {
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        val srcPath = "$nativeLibDir/libtailscale.so"

        callback.onLog("Installing tailscale...")

        adbShellExecutor.execute(
            command = "test -f $srcPath && mkdir -p $TAILSCALE_HOME && cp $srcPath $TAILSCALE_PATH && ln -s $TAILSCALE_PATH $TAILSCALED_PATH && chmod +x $TAILSCALE_PATH",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    callback.onLog("Tailscale installed")
                    onComplete()
                }

                override fun onError(error: String) {
                    logManager.error(TAG, "Failed to install tailscale: $error")
                    callback.onError("Failed to install tailscale: $error")
                }
            }
        )
    }

    fun generateLoginUrl(loginUrl: (String?) -> Unit) {
        launchTailscale(object : TailscaleCallback {
            override fun onLog(message: String) {}
            override fun onTunnelUrl(url: String?) {
                // The login command waits for login completion. Instead, call it with a 1ms timeout so we can get the login url from the status command
                runTailscaleCommand(
                    cmd = "login --hostname overdrive --timeout 1ms || echo done",
                    callback = object : AdbShellExecutor.ShellCallback {
                        override fun onSuccess(output: String) {
                            waitForLoginUrl(0, loginUrl)
                        }

                        override fun onError(error: String) {}
                    }
                )
            }
            override fun onError(error: String) {}
        })
    }

    fun waitForLoginUrl(attempt: Int, loginUrl: (String?) -> Unit) {
        if (attempt > 20) {
            loginUrl(null)
            return
        }

        Thread.sleep(2000)

        runTailscaleCommand(
            cmd = "status || echo done",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    val loginPattern = Regex("Log in at: (\\S+)", RegexOption.IGNORE_CASE)
                    val match = loginPattern.find(output)

                    if (match != null) {
                        val url = match.groupValues[1]
                        logManager.info(TAG, "Fetched login URL: $url")
                        loginUrl(url)
                    } else {
                        waitForLoginUrl(attempt + 1, loginUrl)
                    }
                }

                override fun onError(error: String) {}
            }
        )
    }

    fun needsLogin(callback: (Boolean) -> Unit) {
        isTunnelRunning { isRunning ->
            if (isRunning) {
                runTailscaleCommand(
                    cmd = "status || echo done",
                    callback = object : AdbShellExecutor.ShellCallback {
                        override fun onSuccess(output: String) {
                            if (output.contains("Logged out", ignoreCase = true)) {
                                callback(true)
                            } else if (output.contains("Tailscale is stopped", ignoreCase = true)) {
                                // This should never happen but can if the user runs tailscale down manually
                                callback(true)
                            } else {
                                callback(false)
                            }
                        }

                        override fun onError(error: String) {}
                    }
                )
            } else {
                callback(false)
            }
        }
    }

    fun runTailscaleCommand(cmd: String, callback: AdbShellExecutor.ShellCallback) {
        adbShellExecutor.execute(
            command = "$TAILSCALE_PATH --socket 127.0.0.1:$TAILSCALE_COMMUNICATION_PORT $cmd",
            callback = callback
        )
    }

    fun saveProxySettings(enabled: Boolean, callback: ((Boolean?) -> Unit)? = null) {
        isProxyEnabled { isEnabled ->
            if (enabled != isEnabled) {
                adbShellExecutor.execute(
                    command = "mkdir -p $TAILSCALE_HOME && echo $enabled > $TAILSCALE_PROXY_FILE && chmod 666 $TAILSCALE_PROXY_FILE",
                    callback = object : AdbShellExecutor.ShellCallback {
                        override fun onSuccess(output: String) {
                            logManager.info(TAG, "Proxy settings saved to $TAILSCALE_PROXY_FILE")
                            callback?.invoke(true)
                        }
                        override fun onError(error: String) {
                            logManager.info(TAG, "Proxy settings failed to save to $TAILSCALE_PROXY_FILE")
                            callback?.invoke(false)
                        }
                    }
                )
            } else {
                callback?.invoke(null)
            }
        }
    }

    fun isProxyEnabled(callback: ((Boolean) -> Unit)) {
        adbShellExecutor.execute(
            command = "cat $TAILSCALE_PROXY_FILE 2>/dev/null",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    val enabledText = output.trim()
                    if (enabledText == "true") {
                        callback(true)
                    } else {
                        callback(false)
                    }
                }
                override fun onError(error: String) {
                    callback(false)
                }
            }
        )
    }

    fun isTunnelRunning(callback: (Boolean) -> Unit) {
        adbShellExecutor.execute(
            command = "ps -A | grep tailscaled | grep -v grep",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    callback(output.trim().isNotEmpty())
                }

                override fun onError(error: String) {
                    callback(false)
                }
            }
        )
    }

    private fun isSingboxActive(callback: (Boolean) -> Unit) {
        adbShellExecutor.execute(
            command = "pgrep -f sing-box",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    callback(output.trim().isNotEmpty())
                }

                override fun onError(error: String) {
                    callback(false)
                }
            }
        )
    }

    fun stopTunnel(callback: TailscaleCallback) {
        logManager.info(TAG, "Stopping tailscale tunnel...")
        callback.onLog("Stopping tailscale tunnel...")

        adbShellExecutor.execute(
            command = "pkill 'tailscaled' 2>/dev/null; rm -f $TAILSCALE_LOG; echo stopped",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    logManager.info(TAG, "Tailscale tunnel stopped")
                    callback.onLog("Tailscale tunnel stopped")
                    callback.onTunnelUrl(null)
                }

                override fun onError(error: String) {
                    // Even on error, consider it stopped
                    logManager.info(TAG, "Tailscale tunnel stopped (with warning: $error)")
                    callback.onLog("Tailscale tunnel stopped")
                    callback.onTunnelUrl(null)
                }
            }
        )
    }

    fun getTunnelUrl(callback: (String?) -> Unit) {
        isTunnelRunning { isRunning ->
            needsLogin { needsLogin ->
                if (isRunning && !needsLogin) {
                    runTailscaleCommand(
                        cmd = "ip --1",
                        callback = object : AdbShellExecutor.ShellCallback {
                            override fun onSuccess(output: String) {
                                callback("http://${output.trim()}:8080")
                            }

                            override fun onError(error: String) {
                                callback(null)
                            }
                        }
                    )
                } else {
                    callback(null)
                }
            }
        }
    }

    /**
     * Disable tailscale environment (cleanup).
     * WARNING: This will not remove the device from the tailscale console but will disconnect
     */
    fun disableEnvironment(callback: TailscaleCallback? = null) {
        logManager.warn(TAG, "⚠️ Disabling tailscale environment - will need to login again!")
        callback?.onLog("⚠️ Disabling environment (will need login again)...")

        adbShellExecutor.execute(
            command = "pkill 'tailscaled' 2>/dev/null; rm -rf $TAILSCALE_HOME; echo done",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    logManager.info(TAG, "Tailscale environment disabled")
                    callback?.onLog("Environment disabled")
                    callback?.onTunnelUrl(null)
                }

                override fun onError(error: String) {}
            }
        )
    }
}
