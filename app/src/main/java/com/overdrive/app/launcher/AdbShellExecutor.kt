package com.overdrive.app.launcher

import android.content.Context
import com.overdrive.app.logging.LogManager
import dadb.AdbKeyPair
import dadb.Dadb
import java.io.File
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Handles ADB shell command execution and connection management.
 */
class AdbShellExecutor(private val context: Context) {

    companion object {
        private const val TAG = "AdbShellExecutor"
        private const val ADB_PORT = 5555
        private const val ADB_KEY_FILE = "adbkey"
        private const val ADB_PUB_KEY_FILE = "adbkey.pub"
        
        @Volatile
        private var cachedKeyPair: AdbKeyPair? = null
        private val keyPairLock = Object()
        
        @Volatile
        private var sharedDadb: Dadb? = null
        private val sharedDadbLock = Object()
        
        // Auth state tracking
        private val isAuthPending = AtomicBoolean(false)
        private val wasAuthGranted = AtomicBoolean(false)
        private val pollingStarted = AtomicBoolean(false)
        
        @Volatile
        private var authCallback: AdbAuthCallback? = null
        
        // Dedicated polling executor (separate from command executor)
        private val pollingExecutor = Executors.newSingleThreadExecutor()
        
        fun setAuthCallback(callback: AdbAuthCallback?) {
            authCallback = callback
        }
        
        fun checkAndClearAuthGranted(): Boolean {
            return wasAuthGranted.getAndSet(false)
        }
        
        fun isAuthPending(): Boolean = isAuthPending.get()
    }
    
    interface AdbAuthCallback {
        fun onAuthPending()
        fun onAuthGranted()
        fun onAuthFailed(error: String)
    }
    
    private val executor = Executors.newSingleThreadExecutor()
    private val logger = LogManager.getInstance()
    
    interface ShellCallback {
        fun onSuccess(output: String)
        fun onError(error: String)
    }
    
    data class ShellResult(
        val exitCode: Int,
        val output: String
    )
    
    fun execute(command: String, callback: ShellCallback) {
        executor.execute {
            val redactedCommand = redactCommand(command)
            try {
                logger.debug(TAG, "Executing async: $redactedCommand")
                val dadb = getOrCreateConnection()
                val result = dadb.shell(command)
                
                if (result.exitCode == 0) {
                    callback.onSuccess(result.allOutput)
                } else {
                    callback.onError("Exit code ${result.exitCode}: ${result.allOutput}")
                }
            } catch (e: Exception) {
                logger.error(TAG, "Command execution failed: $redactedCommand", e)
                callback.onError("Execution failed: ${e.message}")
            }
        }
    }
    
    fun executeSync(command: String): ShellResult {
        logger.debug(TAG, "Executing sync: ${redactCommand(command)}")
        val dadb = getOrCreateConnection()
        val result = dadb.shell(command)
        return ShellResult(result.exitCode, result.allOutput)
    }

    private fun redactCommand(command: String): String {
        return command
            .replace(Regex("""(?i)(\bzrok\s+enable\s+)(\S+)"""), "$1<redacted>")
            .replace(Regex("""(?i)("cmd"\s*:\s*"secret_put"[^}]*"value"\s*:\s*")[^"]*"""), "$1<redacted>")
    }
    
    fun checkProcessRunning(processName: String): Int? {
        return try {
            val dadb = getOrCreateConnection()
            val result = dadb.shell("pgrep -f '$processName'")
            
            if (result.exitCode == 0 && result.allOutput.trim().isNotEmpty()) {
                result.allOutput.trim().lines().firstOrNull()?.toIntOrNull()
            } else {
                null
            }
        } catch (e: Exception) {
            logger.error(TAG, "Failed to check process: $processName", e)
            null
        }
    }
    
    fun killProcess(processName: String): Boolean {
        return try {
            val dadb = getOrCreateConnection()
            val result = dadb.shell("pkill -9 -f '$processName'")
            result.exitCode == 0
        } catch (e: Exception) {
            logger.error(TAG, "Failed to kill process: $processName", e)
            false
        }
    }
    
    fun getOrCreateConnection(): Dadb {
        synchronized(sharedDadbLock) {
            var dadb = sharedDadb
            if (dadb != null) {
                try {
                    val result = dadb.shell("echo ok")
                    if (result.exitCode == 0) {
                        if (isAuthPending.getAndSet(false)) {
                            wasAuthGranted.set(true)
                            logger.info(TAG, "ADB auth granted! Connection established.")
                            authCallback?.onAuthGranted()
                        }
                        return dadb
                    }
                } catch (e: Exception) {
                    logger.debug(TAG, "Existing ADB connection dead, reconnecting...")
                }
                try { dadb.close() } catch (e: Exception) {}
                sharedDadb = null
            }
            
            // Check if ADB port is even listening before trying to connect
            if (!isAdbPortOpen()) {
                logger.warn(TAG, "ADB port $ADB_PORT not open - ADB not enabled?")
                throw Exception("ADB port not open")
            }
            
            val adbKeyPair = getOrCreateAdbKeyPair()
            logger.info(TAG, "Attempting ADB connection...")
            
            // Start polling BEFORE we try to connect (on separate executor)
            if (!isAuthPending.get() && pollingStarted.compareAndSet(false, true)) {
                isAuthPending.set(true)
                authCallback?.onAuthPending()
                startAuthPollingInternal(adbKeyPair)
            }
            
            // Try quick connection with timeout wrapper
            dadb = tryConnectWithTimeout(adbKeyPair, 2000)
            
            if (dadb != null) {
                sharedDadb = dadb
                isAuthPending.set(false)
                wasAuthGranted.set(true)
                pollingStarted.set(false)
                logger.info(TAG, "ADB connection established successfully")
                authCallback?.onAuthGranted()
                return dadb
            } else {
                throw Exception("ADB auth pending - waiting for user to accept")
            }
        }
    }
    
    /**
     * Check if ADB port is open (quick TCP check).
     */
    private fun isAdbPortOpen(): Boolean {
        return try {
            Socket("127.0.0.1", ADB_PORT).use { true }
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Try to connect with a timeout. Returns null if times out (auth pending).
     */
    private fun tryConnectWithTimeout(keyPair: AdbKeyPair, timeoutMs: Long): Dadb? {
        var result: Dadb? = null
        var error: Exception? = null
        
        val connectThread = Thread {
            try {
                val dadb = Dadb.create("127.0.0.1", ADB_PORT, keyPair)
                val testResult = dadb.shell("echo ok")
                if (testResult.exitCode == 0) {
                    result = dadb
                } else {
                    dadb.close()
                }
            } catch (e: Exception) {
                error = e
            }
        }
        
        connectThread.start()
        connectThread.join(timeoutMs)
        
        if (connectThread.isAlive) {
            // Timed out - auth is pending
            logger.debug(TAG, "Connection timed out - auth likely pending")
            connectThread.interrupt()
            return null
        }
        
        error?.let { throw it }
        return result
    }
    
    /**
     * Background polling on dedicated executor.
     */
    private fun startAuthPollingInternal(keyPair: AdbKeyPair) {
        pollingExecutor.execute {
            logger.info(TAG, "=== AUTH POLLING STARTED ===")
            var attempts = 0
            val maxAttempts = 60
            
            while (isAuthPending.get() && attempts < maxAttempts) {
                attempts++
                
                try {
                    Thread.sleep(3000)
                } catch (e: InterruptedException) {
                    logger.debug(TAG, "Polling interrupted")
                    break
                }
                
                if (!isAuthPending.get()) {
                    logger.debug(TAG, "Auth no longer pending, stopping poll")
                    break
                }
                
                logger.info(TAG, "Auth poll attempt $attempts/$maxAttempts...")
                
                // Quick TCP check first
                if (!isAdbPortOpen()) {
                    logger.debug(TAG, "ADB port not open, skipping attempt")
                    continue
                }
                
                // Try connection with short timeout
                val testDadb = tryConnectWithTimeout(keyPair, 2000)
                
                if (testDadb != null) {
                    // Success!
                    synchronized(sharedDadbLock) {
                        try { sharedDadb?.close() } catch (ignored: Exception) {}
                        sharedDadb = testDadb
                    }
                    isAuthPending.set(false)
                    wasAuthGranted.set(true)
                    pollingStarted.set(false)
                    logger.info(TAG, "=== AUTH GRANTED VIA POLLING ===")
                    authCallback?.onAuthGranted()
                    break
                }
            }
            
            if (isAuthPending.get() && attempts >= maxAttempts) {
                logger.warn(TAG, "Auth polling timed out")
                isAuthPending.set(false)
                pollingStarted.set(false)
                authCallback?.onAuthFailed("Auth timeout - please grant ADB permission and restart app")
            }
        }
    }
    
    fun closeConnection() {
        synchronized(sharedDadbLock) {
            try {
                sharedDadb?.close()
                logger.info(TAG, "Closed ADB connection")
            } catch (e: Exception) {
                logger.error(TAG, "Error closing ADB connection", e)
            }
            sharedDadb = null
        }
    }
    
    private fun getOrCreateAdbKeyPair(): AdbKeyPair {
        cachedKeyPair?.let { return it }
        
        synchronized(keyPairLock) {
            cachedKeyPair?.let { return it }
            
            val keyDir = context.filesDir
            val privateKeyFile = File(keyDir, ADB_KEY_FILE)
            val publicKeyFile = File(keyDir, ADB_PUB_KEY_FILE)
            
            val keyPair = if (privateKeyFile.exists() && publicKeyFile.exists()) {
                try {
                    AdbKeyPair.read(privateKeyFile, publicKeyFile)
                } catch (e: Exception) {
                    logger.warn(TAG, "Failed to read existing keys: ${e.message}")
                    generateAndSaveKeyPair(privateKeyFile, publicKeyFile)
                }
            } else {
                logger.info(TAG, "Generating new ADB key pair")
                generateAndSaveKeyPair(privateKeyFile, publicKeyFile)
            }
            
            cachedKeyPair = keyPair
            return keyPair
        }
    }
    
    private fun generateAndSaveKeyPair(privateKeyFile: File, publicKeyFile: File): AdbKeyPair {
        AdbKeyPair.generate(privateKeyFile, publicKeyFile)
        return AdbKeyPair.read(privateKeyFile, publicKeyFile)
    }
}
