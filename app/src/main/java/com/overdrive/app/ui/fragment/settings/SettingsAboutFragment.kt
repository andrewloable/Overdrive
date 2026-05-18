package com.overdrive.app.ui.fragment.settings

import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.overdrive.app.BuildConfig
import com.overdrive.app.R
import com.overdrive.app.ui.MainActivity
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Settings → About pane.
 *
 * Renders identity (brand, version, build), MIT license + GitHub source
 * deep-links, and the "Check for updates" action. Version is pulled from
 * [BuildConfig.VERSION_NAME] at runtime.
 */
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

        view.findViewById<View>(R.id.cardCheckUpdate).setOnClickListener {
            (activity as? MainActivity)?.invokeCheckForUpdates()
        }

        view.findViewById<View>(R.id.cardLicense).setOnClickListener {
            openExternal(getString(R.string.settings_about_license_url))
        }

        view.findViewById<View>(R.id.cardSource).setOnClickListener {
            openExternal(getString(R.string.settings_about_source_url))
        }

        // Tiered support actions — free → social → monetary.
        view.findViewById<View>(R.id.cardStar).setOnClickListener {
            openExternal(getString(R.string.settings_about_star_url))
        }

        view.findViewById<View>(R.id.cardShare).setOnClickListener {
            shareOverdrive()
        }

        view.findViewById<View>(R.id.cardSupport).setOnClickListener {
            openExternal(getString(R.string.settings_about_support_kofi_url))
        }

        populateThanks(view)
    }

    private fun populateThanks(root: View) {
        val contribContainer = root.findViewById<LinearLayout>(R.id.containerContributors)
        val supportContainer = root.findViewById<LinearLayout>(R.id.containerSupporters)

        val json = try {
            val raw = requireContext().assets.open("web/local/credits.json").use { input ->
                BufferedReader(InputStreamReader(input)).readText()
            }
            JSONObject(raw)
        } catch (e: Exception) {
            // Render empty-state on both lists so the user sees the localized
            // "List populates as people pitch in." copy instead of a blank card.
            renderRows(contribContainer, null, withGithub = true)
            renderRows(supportContainer, null, withGithub = false)
            return
        }

        renderRows(contribContainer, json.optJSONArray("contributors"), withGithub = true)
        renderRows(supportContainer, json.optJSONArray("supporters"), withGithub = false)
    }

    private fun renderRows(container: LinearLayout, arr: org.json.JSONArray?, withGithub: Boolean) {
        container.removeAllViews()
        if (arr == null || arr.length() == 0) {
            val empty = TextView(container.context).apply {
                text = getString(R.string.settings_about_thanks_empty)
                setTextColor(resolveAttr(android.R.attr.textColorSecondary))
                textSize = 13f
            }
            container.addView(empty)
            return
        }

        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val name = obj.optString("name").trim()
            if (name.isEmpty()) continue
            val github = if (withGithub) obj.optString("github").trim().ifEmpty { null } else null
            container.addView(buildRow(container, name, github))
        }
    }

    private fun buildRow(parent: LinearLayout, name: String, github: String?): View {
        val ctx = parent.context
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val padV = dp(10)
            setPadding(0, padV, 0, padV)
            isClickable = github != null
            isFocusable = github != null
            if (github != null) {
                val ta = ctx.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
                background = ta.getDrawable(0)
                ta.recycle()
                setOnClickListener { openExternal("https://github.com/$github") }
            }
        }

        val avatar = ImageView(ctx).apply {
            val sz = dp(36)
            layoutParams = LinearLayout.LayoutParams(sz, sz)
            setImageDrawable(initialCircle(name))
        }
        row.addView(avatar)

        val text = TextView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(14)
            }
            this.text = name
            setTextColor(resolveAttr(android.R.attr.textColorPrimary))
            textSize = 15f
        }
        row.addView(text)

        if (github != null) {
            val chev = ImageView(ctx).apply {
                val sz = dp(18)
                layoutParams = LinearLayout.LayoutParams(sz, sz)
                setImageResource(R.drawable.ic_chevron_right)
                imageTintList = android.content.res.ColorStateList.valueOf(resolveAttr(android.R.attr.textColorSecondary))
            }
            row.addView(chev)
        }
        return row
    }

    private fun initialCircle(name: String): Drawable {
        val initial = name.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar() ?: '?'
        val palette = intArrayOf(
            0xFF6366F1.toInt(), 0xFFEC4899.toInt(), 0xFF14B8A6.toInt(),
            0xFFF59E0B.toInt(), 0xFF8B5CF6.toInt(), 0xFF06B6D4.toInt(),
            0xFFEF4444.toInt(), 0xFF22C55E.toInt()
        )
        val color = palette[Math.floorMod(name.hashCode(), palette.size)]
        val sz = dp(36)

        return object : Drawable() {
            private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color
                style = Paint.Style.FILL
            }
            private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = Color.WHITE
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
                textSize = sz * 0.45f
            }

            override fun draw(canvas: Canvas) {
                val cx = bounds.exactCenterX()
                val cy = bounds.exactCenterY()
                val r = minOf(bounds.width(), bounds.height()) / 2f
                canvas.drawCircle(cx, cy, r, bgPaint)
                val baseline = cy - (textPaint.descent() + textPaint.ascent()) / 2f
                canvas.drawText(initial.toString(), cx, baseline, textPaint)
            }
            override fun setAlpha(alpha: Int) { bgPaint.alpha = alpha; textPaint.alpha = alpha }
            override fun setColorFilter(cf: android.graphics.ColorFilter?) {
                bgPaint.colorFilter = cf; textPaint.colorFilter = cf
            }
            @Deprecated("Deprecated in API 29")
            override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
            override fun getIntrinsicWidth(): Int = sz
            override fun getIntrinsicHeight(): Int = sz
        }
    }

    private fun resolveAttr(attr: Int): Int {
        val tv = TypedValue()
        requireContext().theme.resolveAttribute(attr, tv, true)
        return tv.data
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()

    /** Fire an Android share-chooser with a prefilled message + repo link. */
    private fun shareOverdrive() {
        val ctx = context ?: return
        try {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, getString(R.string.settings_about_support_share_message))
            }
            val chooser = Intent.createChooser(
                send,
                getString(R.string.settings_about_support_share_chooser)
            ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            ctx.startActivity(chooser)
        } catch (e: Exception) {
            Toast.makeText(ctx, getString(R.string.settings_about_support_share_message), Toast.LENGTH_LONG).show()
        }
    }

    /** Open a URL in the system browser. Falls back to a Toast if no
     *  browser is installed (rare on the head unit but possible). */
    private fun openExternal(url: String) {
        val ctx = context ?: return
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(ctx, url, Toast.LENGTH_LONG).show()
        }
    }
}
