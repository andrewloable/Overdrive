package com.loabletech.bladewatch

import android.app.Application
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.loabletech.bladewatch.logging.LogConfig
import com.loabletech.bladewatch.logging.LogManager
import com.loabletech.bladewatch.server.LocaleManager
import com.loabletech.bladewatch.services.DaemonKeepaliveService
// import com.loabletech.bladewatch.shell.PrivilegedShellSetup
import com.loabletech.bladewatch.ui.util.PreferencesManager

/**
 * Application class for BladeWatch.
 * Initializes global singletons before any Activity is created.
 */
class BladeWatchApplication : Application() {
    
    override fun onCreate() {
        super.onCreate()

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
