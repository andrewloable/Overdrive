package net.bladewatch.app.ui.fragment.location

import android.content.Context
import android.content.SharedPreferences

class LocationSettingsStore(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun readUiModePreference(): LocationUiModePreference =
        LocationUiModePreference.fromStoredValue(prefs.getString(KEY_UI_MODE, null))

    fun setUiModePreference(preference: LocationUiModePreference) {
        prefs.edit().putString(KEY_UI_MODE, preference.storedValue).apply()
    }

    companion object {
        private const val PREFS_NAME = "location_fragment_settings"
        private const val KEY_UI_MODE = "ui_mode"
    }
}
