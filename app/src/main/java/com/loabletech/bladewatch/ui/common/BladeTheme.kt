package net.bladewatch.app.ui.common

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import androidx.appcompat.app.AppCompatDelegate
import net.bladewatch.app.ui.util.PreferencesManager

/**
 * Single source of design tokens shared across all native screens.
 * Colour tokens match the web assets' CSS custom properties so the native
 * and WebView surfaces stay visually consistent when the theme changes.
 */
class BladeTheme(val context: Context) {

    fun isDark(): Boolean {
        val mode = if (PreferencesManager.isInitialized()) PreferencesManager.getThemeMode()
                   else AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        return when (mode) {
            AppCompatDelegate.MODE_NIGHT_YES -> true
            AppCompatDelegate.MODE_NIGHT_NO -> false
            else -> (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        }
    }

    fun bgColor()       = if (isDark()) Color.parseColor("#121212") else Color.parseColor("#F5F5F5")
    fun surfaceColor()  = if (isDark()) Color.parseColor("#1E1E1E") else Color.WHITE
    fun textColor()     = if (isDark()) Color.WHITE              else Color.parseColor("#212121")
    fun mutedColor()    = if (isDark()) Color.parseColor("#AAAAAA") else Color.parseColor("#666666")
    fun dividerColor()  = if (isDark()) Color.parseColor("#333333") else Color.parseColor("#E0E0E0")
    fun pillBgColor()   = if (isDark()) Color.parseColor("#2C2C2C") else Color.parseColor("#EEEEEE")
    fun inputBgColor()  = if (isDark()) Color.parseColor("#2C2C2C") else Color.parseColor("#F0F0F0")

    /** Accent green — not theme-dependent. */
    fun accentColor()   = Color.parseColor("#4CAF50")
    /** Semantic warning amber. */
    fun warningColor()  = Color.parseColor("#F59E0B")
    /** Semantic error red. */
    fun errorColor()    = Color.parseColor("#EF4444")

    fun dp(v: Int) = (v * context.resources.displayMetrics.density + 0.5f).toInt()
}
