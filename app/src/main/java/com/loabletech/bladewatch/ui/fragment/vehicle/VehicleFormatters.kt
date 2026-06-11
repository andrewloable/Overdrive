package net.bladewatch.app.ui.fragment.vehicle

enum class TyreTier { MUTED, ALERT, WARN, CAUTION, NORMAL }

object VehicleFormatters {

    private val WINDOW_PRESETS = listOf(0, 25, 50, 75, 100)

    /**
     * Returns the nearest window preset if the current value is within ±10 of it, else null.
     * Returns null for unknown (-1) or any negative value.
     */
    fun presetFor(current: Int): Int? {
        if (current < 0) return null
        val nearest = WINDOW_PRESETS.minByOrNull { kotlin.math.abs(it - current) } ?: return null
        return if (kotlin.math.abs(nearest - current) <= 10) nearest else null
    }

    /** Returns "–%" for unknown windows (-1), else "${v}%". */
    fun formatWindowPct(v: Int): String = if (v < 0) "–%" else "$v%"

    /** Returns "—" until loaded, then "${setpointC}°C". */
    fun formatTemp(setpointC: Int, loaded: Boolean): String = if (loaded) "${setpointC}°C" else "—"

    /** Returns "—" until loaded, then formats `fanLevelFormat` (e.g. "Level %1\$d") with the level. */
    fun formatFan(fanLevelFormat: String, level: Int, loaded: Boolean): String =
        if (loaded) String.format(fanLevelFormat, level) else "—"

    /**
     * Maps a TyreInfo to a visual tier. Priority order:
     *   MUTED   — no signal (signalState != 0) or no PSI data
     *   ALERT   — air leak detected (airLeakState >= 1) or PSI < 22 (flat)
     *   WARN    — TPMS hardware flagged pressure anomaly (pressureState >= 1)
     *   CAUTION — PSI out of normal range (< 34 or > 45) without hardware flag
     *   NORMAL  — PSI 34-45 and all sensors clear
     */
    fun tyreTier(t: TyreInfo): TyreTier = when {
        t.signalState != 0 || t.psi == null -> TyreTier.MUTED
        t.airLeakState >= 1 || t.psi < 22.0 -> TyreTier.ALERT
        t.pressureState >= 1                 -> TyreTier.WARN
        t.psi < 34.0 || t.psi > 45.0        -> TyreTier.CAUTION
        else                                 -> TyreTier.NORMAL
    }
}
