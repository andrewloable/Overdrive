package com.overdrive.app.ui.fragment.settings

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import com.overdrive.app.BuildConfig
import com.overdrive.app.R
import com.overdrive.app.ui.MainActivity
import com.overdrive.app.updater.AppUpdater
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Settings → About pane.
 *
 * Renders identity (brand, version, build), MIT license + GitHub source
 * deep-links, and the "Check for updates" action. Version is pulled from
 * [AppUpdater.getDisplayVersion] at runtime — that's the GitHub release
 * string written by the updater, falling back to "Manually Installed"
 * when no update has been performed yet.
 */
class SettingsAboutFragment : Fragment() {

    /** Off-thread loader for GitHub avatar fetches. Single thread is
     *  enough — at most ~10 contributors at a time, and a queue
     *  serializes their HTTPS calls without flooding the head unit. */
    private var avatarExecutor: ExecutorService? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_settings_about, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Show the locally-tracked installed version immediately so the row
        // never sits blank, then refresh in the background with the published
        // version on the configured channel from GitHub Releases — that's the
        // value the user actually wants to see ("am I current?"). Both calls
        // are routed through the AppUpdater so behavior matches the rest of
        // the update surface (proxy detection, channel, label format).
        val versionView = view.findViewById<TextView>(R.id.tvAboutVersion)
        versionView.text = AppUpdater.getDisplayVersion(requireContext())
        view.findViewById<TextView>(R.id.tvAboutBuild).text = BuildConfig.APPLICATION_ID

        AppUpdater(requireContext()).fetchLatestReleaseVersion(
            object : AppUpdater.RemoteVersionCallback {
                override fun onResult(version: String) {
                    if (!isAdded) return
                    if (version.isNotBlank()) {
                        versionView.text = version
                    }
                }
                override fun onError(error: String?) {
                    // Silent — the local fallback is already visible.
                }
            }
        )

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

    override fun onDestroyView() {
        super.onDestroyView()
        avatarExecutor?.shutdownNow()
        avatarExecutor = null
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

        // Avatar: start with the initial-circle fallback, then async-load
        // the GitHub profile picture (matches about.html behavior). The
        // CDN URL `https://github.com/<user>.png?size=72` 302-redirects to
        // the user's avatar; if it fails we keep the initial circle.
        val avatarSize = dp(36)
        val avatar = ImageView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(avatarSize, avatarSize)
            setImageDrawable(initialCircle(name))
        }
        row.addView(avatar)
        if (github != null) {
            loadGithubAvatar(github, avatar)
        }

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

    /**
     * Asynchronously load a GitHub avatar into [target]. Uses a tiny
     * disk cache under the app's cacheDir keyed by username so repeated
     * About-page visits don't re-fetch.
     *
     * On failure (no network, 404, parse error, fragment torn down) we
     * leave the initial-circle in place — same graceful behavior as
     * about.html's `error` handler.
     */
    private fun loadGithubAvatar(github: String, target: ImageView) {
        val ctx = context ?: return
        val safeName = github.replace(Regex("[^A-Za-z0-9_.-]"), "_")
        val cacheFile = File(ctx.cacheDir, "gh_avatar_$safeName.png")

        // Cached hit — decode synchronously off the main thread (cheap)
        // but still post the bitmap binding to the UI thread.
        val executor = avatarExecutor
            ?: Executors.newSingleThreadExecutor().also { avatarExecutor = it }

        executor.execute {
            try {
                val bmp = if (cacheFile.exists() && cacheFile.length() > 0L) {
                    BitmapFactory.decodeFile(cacheFile.absolutePath)
                } else {
                    fetchAvatarBitmap(github, cacheFile)
                } ?: return@execute

                mainHandler.post {
                    if (!isAdded || view == null) return@post
                    target.setImageDrawable(circularBitmap(bmp))
                }
            } catch (_: Throwable) {
                // Stay with initial circle.
            }
        }
    }

    /**
     * Fetch the avatar bitmap via HTTPS, follow the GitHub redirect,
     * persist to [cacheFile], and return the decoded bitmap. Any
     * exception means "no avatar available" and the caller falls
     * through to the initial circle.
     */
    private fun fetchAvatarBitmap(github: String, cacheFile: File): Bitmap? {
        val urlStr = "https://github.com/" +
            java.net.URLEncoder.encode(github, "UTF-8") +
            ".png?size=72"
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.connectTimeout = 5_000
        conn.readTimeout = 5_000
        conn.instanceFollowRedirects = true
        conn.requestMethod = "GET"
        try {
            if (conn.responseCode !in 200..299) return null
            conn.inputStream.use { input ->
                FileOutputStream(cacheFile).use { output ->
                    val buf = ByteArray(8192)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        output.write(buf, 0, n)
                    }
                }
            }
            return BitmapFactory.decodeFile(cacheFile.absolutePath)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Wrap a square bitmap in a circle-shaped drawable so the avatar
     * matches the initial-circle visual (no square photos in a list of
     * round chips).
     */
    private fun circularBitmap(src: Bitmap): Drawable {
        val sz = dp(36)
        return object : Drawable() {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                isFilterBitmap = true
            }
            private val matrix = android.graphics.Matrix()

            override fun onBoundsChange(bounds: android.graphics.Rect) {
                val scale = maxOf(
                    bounds.width().toFloat() / src.width,
                    bounds.height().toFloat() / src.height
                )
                matrix.reset()
                matrix.setScale(scale, scale)
                matrix.postTranslate(
                    bounds.left + (bounds.width() - src.width * scale) / 2f,
                    bounds.top + (bounds.height() - src.height * scale) / 2f
                )
                paint.shader.setLocalMatrix(matrix)
            }

            override fun draw(canvas: Canvas) {
                val cx = bounds.exactCenterX()
                val cy = bounds.exactCenterY()
                val r = minOf(bounds.width(), bounds.height()) / 2f
                canvas.drawCircle(cx, cy, r, paint)
            }
            override fun setAlpha(alpha: Int) { paint.alpha = alpha }
            override fun setColorFilter(cf: android.graphics.ColorFilter?) {
                paint.colorFilter = cf
            }
            @Deprecated("Deprecated in API 29")
            override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
            override fun getIntrinsicWidth(): Int = sz
            override fun getIntrinsicHeight(): Int = sz
        }
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

    /**
     * Open a URL in an external browser.
     *
     * The BYD head unit's launcher doesn't always advertise a default
     * browser. We try one direct handler resolution; if no app handles
     * https:// we skip straight to copying the URL to the clipboard +
     * showing a Toast.
     *
     * We deliberately do NOT use `Intent.createChooser` as a middle
     * step — on hosts with no browser installed Android still pops a
     * "No apps can perform this action" bottom sheet instead of falling
     * through to our handler, which is worse UX than a clean Toast.
     */
    private fun openExternal(url: String) {
        val ctx = context ?: return
        if (url.isBlank()) {
            Toast.makeText(ctx, R.string.settings_about_open_link_failed, Toast.LENGTH_SHORT).show()
            return
        }
        val uri = Uri.parse(url)

        val direct = Intent(Intent.ACTION_VIEW, uri).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (direct.resolveActivity(ctx.packageManager) != null) {
            try {
                ctx.startActivity(direct)
                return
            } catch (_: Exception) { /* fall through to clipboard */ }
        }

        // No browser available — copy + toast so the URL is at least
        // recoverable.
        try {
            val clip = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                as? android.content.ClipboardManager
            clip?.setPrimaryClip(android.content.ClipData.newPlainText("url", url))
            Toast.makeText(
                ctx,
                getString(R.string.settings_about_open_link_copied, url),
                Toast.LENGTH_LONG
            ).show()
        } catch (_: Exception) {
            Toast.makeText(ctx, url, Toast.LENGTH_LONG).show()
        }
    }
}
