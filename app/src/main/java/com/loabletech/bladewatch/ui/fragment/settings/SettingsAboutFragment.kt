package net.bladewatch.app.ui.fragment.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import net.bladewatch.app.BuildConfig
import net.bladewatch.app.R

/** Settings → About pane: brand identity, version, and license. */
class SettingsAboutFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_settings_about, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<TextView>(R.id.tvAboutVersion).text = BuildConfig.VERSION_NAME
        view.findViewById<TextView>(R.id.tvAboutBuild).text = BuildConfig.APPLICATION_ID

        view.findViewById<View>(R.id.cardLicense).setOnClickListener {
            showLicenseDialog()
        }

        // Re-show the first-launch Getting Started guide on demand.
        view.findViewById<View>(R.id.cardSetupGuide)?.setOnClickListener {
            context?.let { net.bladewatch.app.overlay.SetupGuideDialog.show(it) }
        }
    }

    private fun showLicenseDialog() {
        val ctx = context ?: return
        val text = try {
            ctx.assets.open("LICENSE.txt").bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            "MIT License\n\nCopyright (c) 2026 Loable Technologies"
        }
        val tv = TextView(ctx).apply {
            this.text = text
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
            val pad = dp(16)
            setPadding(pad, pad, pad, pad)
        }
        val scroll = ScrollView(ctx).apply { addView(tv) }
        MaterialAlertDialogBuilder(ctx)
            .setTitle(getString(R.string.settings_about_license_title))
            .setView(scroll)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()
}
