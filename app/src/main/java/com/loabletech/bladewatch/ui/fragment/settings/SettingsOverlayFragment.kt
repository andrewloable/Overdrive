package com.overdrive.app.ui.fragment.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.google.android.material.switchmaterial.SwitchMaterial
import com.overdrive.app.R
import com.overdrive.app.config.UnifiedConfigManager
import org.json.JSONObject

/**
 * Settings → Status overlay pane.
 *
 * Two switches that gate the segments of the floating status pill
 * ([com.overdrive.app.overlay.StatusOverlayService]):
 *  - Camera/recording indicator (REC / PROX)
 *  - Trip indicator (TRIP)
 *
 * The flags live in [UnifiedConfigManager]'s `statusOverlay` section so
 * both the app UID and the daemon UID see the same value (SharedPreferences
 * are per-UID and would split-brain). The service polls the unified config
 * on every tick, so a flip here takes effect within the next poll interval
 * (≤3s with ACC on) without restarting the service.
 */
class SettingsOverlayFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_settings_overlay, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val swCamera = view.findViewById<SwitchMaterial>(R.id.swOverlayCamera) ?: return
        val swTrip = view.findViewById<SwitchMaterial>(R.id.swOverlayTrip) ?: return

        val cfg = UnifiedConfigManager.getStatusOverlay()
        swCamera.isChecked = cfg.optBoolean("cameraVisible", true)
        swTrip.isChecked = cfg.optBoolean("tripVisible", true)

        // Make the whole row clickable as well for forgiveness on a wide
        // head-unit (toggling via the row, not just the thumb, is the BYD
        // muscle memory).
        view.findViewById<View>(R.id.rowOverlayCamera).setOnClickListener {
            swCamera.isChecked = !swCamera.isChecked
        }
        view.findViewById<View>(R.id.rowOverlayTrip).setOnClickListener {
            swTrip.isChecked = !swTrip.isChecked
        }

        swCamera.setOnCheckedChangeListener { _, checked -> persist("cameraVisible", checked) }
        swTrip.setOnCheckedChangeListener { _, checked -> persist("tripVisible", checked) }
    }

    /**
     * Persist the flag and immediately nudge the overlay service so the
     * toggle takes effect now instead of on the next 3-10s poll tick.
     * StatusOverlayService.onStartCommand re-uses the existing instance
     * and cancels any in-flight delayed poll, firing one synchronously.
     */
    private fun persist(key: String, value: Boolean) {
        UnifiedConfigManager.setStatusOverlay(JSONObject().put(key, value))
        context?.let { com.overdrive.app.overlay.StatusOverlayService.startIfPermitted(it) }
    }
}
