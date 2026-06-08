package net.bladewatch.app

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import net.bladewatch.app.logging.DebugAppLogger
import net.bladewatch.app.logging.LogConfig
import net.bladewatch.app.logging.LogManager
import net.bladewatch.app.server.LocaleManager
import net.bladewatch.app.services.DaemonKeepaliveService
// import net.bladewatch.app.shell.PrivilegedShellSetup
import net.bladewatch.app.ui.util.PreferencesManager

/**
 * Application class for BladeWatch.
 * Initializes global singletons before any Activity is created.
 */
class BladeWatchApplication : Application() {
    
    override fun onCreate() {
        super.onCreate()

        // Debug-only regression guard for BladeWatch-knyj: all gRPC/Connect client
        // calls (which block via runBlocking) must run off the main thread. The
        // platform already throws NetworkOnMainThreadException for main-thread
        // network, and every client call site is wrapped in a Thread/Executor; this
        // StrictMode policy surfaces any future regression loudly in logcat without
        // destabilizing the head unit (penaltyLog, never penaltyDeath).
        if (BuildConfig.DEBUG) {
            android.os.StrictMode.setThreadPolicy(
                android.os.StrictMode.ThreadPolicy.Builder()
                    .detectNetwork()
                    .penaltyLog()
                    .build()
            )
        }

        // Apply the user-picked locale before any Activity/Fragment is created.
        // Auto-mode (or unset) writes an empty list so AppCompat falls back to
        // Locale.getDefault() — i.e. the BYD head unit's system language.
        applyPersistedLocale()

        // Initialize LogConfig with app's cache directory for file logging
        LogConfig.init(this)

        // Some BYD WebView builds try to initialize Crashpad before creating
        // its parent cache directory and then emit noisy Chromium errors. Create
        // the path up front; WebView will still own the files inside it.
        runCatching { java.io.File(cacheDir, "WebView/Crashpad").mkdirs() }

        // Initialize LogManager with file logging enabled
        LogManager.getInstance(LogConfig.default())

        // Sync DebugAppLogger enabled state from persisted config (best-effort:
        // UnifiedConfigManager may not have loaded yet on first-ever boot, in
        // which case syncEnabled() leaves the logger disabled — correct default).
        DebugAppLogger.syncEnabled()

        // Install uncaught-exception handler.  Crashes are always written to
        // the debug log file regardless of the toggle so a crash report is
        // available even when verbose logging is off.
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            DebugAppLogger.logCrash(thread, throwable)
            previousHandler?.uncaughtException(thread, throwable)
        }

        // Register Activity lifecycle callbacks so debug logging captures every
        // Activity lifecycle method when the toggle is on.
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(a: Activity, b: Bundle?) =
                DebugAppLogger.logLifecycle(a.javaClass.simpleName, "onCreate")
            override fun onActivityStarted(a: Activity) =
                DebugAppLogger.logLifecycle(a.javaClass.simpleName, "onStart")
            override fun onActivityResumed(a: Activity) =
                DebugAppLogger.logLifecycle(a.javaClass.simpleName, "onResume")
            override fun onActivityPaused(a: Activity) =
                DebugAppLogger.logLifecycle(a.javaClass.simpleName, "onPause")
            override fun onActivityStopped(a: Activity) =
                DebugAppLogger.logLifecycle(a.javaClass.simpleName, "onStop")
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) =
                DebugAppLogger.logLifecycle(a.javaClass.simpleName, "onSaveInstanceState")
            override fun onActivityDestroyed(a: Activity) =
                DebugAppLogger.logLifecycle(a.javaClass.simpleName, "onDestroy")
        })

        // Initialize PreferencesManager before any ViewModel is created
        PreferencesManager.init(this)

        // Apply persisted theme mode (Auto / Light / Dark) before any Activity
        // is created so the first paint matches the user's choice.
        AppCompatDelegate.setDefaultNightMode(PreferencesManager.getThemeMode())
        
        // Privileged shell (UID 1000) DISABLED — causes BYD default dashcam
        // to show "no signal" by elevating app's camera priority via accmodemanager.
        // All daemons now run via ADB shell (UID 2000) which is sufficient.
        // PrivilegedShellSetup.init(this)
        // PrivilegedShellSetup.setup(...)

        // Start DaemonKeepaliveService - handles:
        // - Foreground service with START_STICKY
        // - PARTIAL_WAKE_LOCK to prevent CPU sleep
        // - SCREEN_OFF receiver registration
        // - Daemon startup
        DaemonKeepaliveService.start(this)
    }

    private fun applyPersistedLocale() {
        try {
            val raw = LocaleManager.getRaw()
            val locales = if (raw == null || raw == LocaleManager.AUTO_TAG) {
                LocaleListCompat.getEmptyLocaleList()
            } else {
                LocaleListCompat.forLanguageTags(raw)
            }
            AppCompatDelegate.setApplicationLocales(locales)
        } catch (e: Exception) {
            Log.w("BladeWatchApplication", "applyPersistedLocale failed: ${e.message}")
        }
    }
}
