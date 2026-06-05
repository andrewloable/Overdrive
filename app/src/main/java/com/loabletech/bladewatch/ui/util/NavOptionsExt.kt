package com.overdrive.app.ui.util

import androidx.navigation.NavController
import androidx.navigation.NavOptions
import com.overdrive.app.R

/**
 * Shared NavOptions presets so every navigation call across the app uses
 * the same M3-Expressive motion vocabulary instead of accidentally falling
 * back to the platform default (no animation).
 *
 * Two presets:
 *  - [m3SharedAxisZ] — drill-down. Use for parent → child screens (e.g.
 *    Settings → Appearance, Integrations → Telegram, Recordings → Video).
 *  - [m3FadeThrough] — peer destinations. Already wired into MainActivity's
 *    rail navigation; exposed here for the rare callsite that needs it
 *    outside the rail (e.g. a tile tap on Dashboard that switches tabs).
 */
object NavOptionsExt {

    /**
     * Drill-down nav options. Incoming child grows from 0.85x with fade-up;
     * outgoing parent dollies backward (1.1x) with fade-out. Pop reverses.
     */
    fun m3SharedAxisZ(): NavOptions = NavOptions.Builder()
        .setLaunchSingleTop(true)
        .setEnterAnim(R.anim.m3_shared_z_enter)
        .setExitAnim(R.anim.m3_shared_z_exit)
        .setPopEnterAnim(R.anim.m3_shared_z_pop_enter)
        .setPopExitAnim(R.anim.m3_shared_z_pop_exit)
        .build()

    /**
     * Peer-destination nav options. Incoming destination fades up + scales
     * from 0.92x; outgoing fades out fast. Used by MainActivity's rail
     * navigation; exposed here for callers that need the same motion.
     */
    fun m3FadeThrough(): NavOptions = NavOptions.Builder()
        .setLaunchSingleTop(true)
        .setEnterAnim(R.anim.m3_fade_through_enter)
        .setExitAnim(R.anim.m3_fade_through_exit)
        .setPopEnterAnim(R.anim.m3_fade_through_enter)
        .setPopExitAnim(R.anim.m3_fade_through_exit)
        .build()
}

/** Convenience: navigate(destinationId) with M3 shared-axis-Z drill-down anims. */
fun NavController.navigateDrillDown(destinationId: Int, args: android.os.Bundle? = null) {
    try {
        navigate(destinationId, args, NavOptionsExt.m3SharedAxisZ())
    } catch (_: IllegalArgumentException) {
        // Destination not in current graph. Defensive only — callers should
        // resolve before invoking.
    }
}
