package net.bladewatch.app.ui.common

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import androidx.appcompat.app.AppCompatDelegate
import net.bladewatch.app.ui.util.PreferencesManager

/**
 * Single source of design tokens shared across all native screens.
 * Colors are resolved from the active M3 theme at call time, ensuring
 * correct light/dark values without hard-coded hex values.
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

    fun bgColor()       = colorAttr(com.google.android.material.R.attr.colorSurface)
    fun surfaceColor()  = colorAttr(com.google.android.material.R.attr.colorSurfaceContainer)
    fun textColor()     = colorAttr(com.google.android.material.R.attr.colorOnSurface)
    fun mutedColor()    = colorAttr(com.google.android.material.R.attr.colorOnSurfaceVariant)
    fun dividerColor()  = colorAttr(com.google.android.material.R.attr.colorOutlineVariant)
    fun pillBgColor()   = colorAttr(com.google.android.material.R.attr.colorSurfaceContainerHighest)
    fun inputBgColor()  = colorAttr(com.google.android.material.R.attr.colorSurfaceContainerHigh)

    fun accentColor()     = colorAttr(androidx.appcompat.R.attr.colorPrimary)
    fun onAccentColor()   = colorAttr(com.google.android.material.R.attr.colorOnPrimary)
    fun warningColor()    = colorAttr(com.google.android.material.R.attr.colorTertiary)
    fun onWarningColor()  = colorAttr(com.google.android.material.R.attr.colorOnTertiary)
    fun errorColor()      = colorAttr(androidx.appcompat.R.attr.colorError)
    fun onErrorColor()    = colorAttr(com.google.android.material.R.attr.colorOnError)

    fun dp(v: Int) = (v * context.resources.displayMetrics.density + 0.5f).toInt()

    private fun colorAttr(attr: Int): Int {
        val ta = context.obtainStyledAttributes(intArrayOf(attr))
        return ta.getColor(0, Color.GRAY).also { ta.recycle() }
    }
}
