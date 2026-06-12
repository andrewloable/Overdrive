package net.bladewatch.app.ui.fragment.performance

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import net.bladewatch.app.R
import net.bladewatch.app.auth.AuthManager
import net.bladewatch.app.ui.util.PreferencesManager
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class PerformanceController(private val context: Context) {

    private val root = FrameLayout(context)
    private lateinit var contentArea: LinearLayout
    private var clientId: String? = null
    private val running = AtomicBoolean(false)
    private var scheduler: ScheduledExecutorService? = null
    private var latestData: JSONObject? = null

    init { buildView() }

    val view: View get() = root

    fun onResume() {
        if (running.compareAndSet(false, true)) {
            connectAndStart()
        }
    }

    fun onPause() {
        running.set(false)
        scheduler?.shutdownNow()
        scheduler = null
        val id = clientId
        clientId = null
        if (id != null) {
            Thread({ httpPost("/api/performance/disconnect", """{"clientId":"$id"}""") }, "PerfDisconnect").apply { isDaemon = true; start() }
        }
    }

    fun onDestroy() { onPause() }

    fun onConfigurationChanged() { applyTheme(); renderData(latestData) }

    // ─────────────────────────── BUILD ───────────────────────────────────

    private fun buildView() {
        root.setBackgroundColor(colorAttr(com.google.android.material.R.attr.colorSurface))
        root.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        val scroll = ScrollView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            isFillViewport = true
        }
        contentArea = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val p = dimen(R.dimen.page_padding_horizontal)
            setPadding(p, p, p, p)
        }
        scroll.addView(contentArea)
        root.addView(scroll)
        showPlaceholder("Connecting to performance monitor…")
    }

    // ─────────────────────────── POLLING ─────────────────────────────────

    private fun connectAndStart() {
        Thread({
            val connectResp = httpPostJson("/api/performance/connect", """{"clientId":"bladewatch-native-${System.currentTimeMillis()}"}""")
            clientId = connectResp?.optString("clientId")?.takeIf { it.isNotEmpty() }

            val sched = Executors.newSingleThreadScheduledExecutor()
            scheduler = sched
            sched.scheduleAtFixedRate({
                if (!running.get()) { sched.shutdownNow(); return@scheduleAtFixedRate }
                val data = httpGetJson("/api/performance") ?: return@scheduleAtFixedRate
                latestData = data
                root.post { if (running.get()) renderData(data) }
                val id = clientId
                if (id != null) httpPost("/api/performance/heartbeat", """{"clientId":"$id"}""")
            }, 0, 3, TimeUnit.SECONDS)
        }, "PerfConnect").apply { isDaemon = true; start() }
    }

    // ─────────────────────────── RENDER ──────────────────────────────────

    private fun applyTheme() {
        root.setBackgroundColor(colorAttr(com.google.android.material.R.attr.colorSurface))
    }

    private fun showPlaceholder(msg: String) {
        contentArea.removeAllViews()
        contentArea.addView(centeredText(msg))
    }

    private fun renderData(data: JSONObject?) {
        applyTheme()
        contentArea.removeAllViews()
        if (data == null || data.length() == 0) {
            contentArea.addView(centeredText("Performance data unavailable.\nMake sure the daemon is running."))
            return
        }

        contentArea.addView(sectionLabel("System Performance"))
        contentArea.addView(spacer(dp(12)))

        val cpu = data.optJSONObject("cpu")
        if (cpu != null) {
            val card = makeCard()
            card.addView(cardTitle("CPU"))
            card.addView(spacer(dp(8)))
            card.addView(metricBar("System Usage", cpu.optDouble("system").toFloat(), "%",
                colorAttr(androidx.appcompat.R.attr.colorPrimary)))
            card.addView(spacer(dp(6)))
            card.addView(metricBar("App Usage", cpu.optDouble("app").toFloat(), "%",
                colorAttr(com.google.android.material.R.attr.colorSecondary)))
            card.addView(spacer(dp(8)))
            val grid = makeGrid(
                "Frequency" to "${cpu.optInt("freqMhz")} MHz",
                "Temperature" to "${cpu.optDouble("tempC").let { if (it > 0) "%.1f°C".format(it) else "N/A" }}")
            card.addView(grid)
            contentArea.addView(card)
            contentArea.addView(spacer(dp(12)))
        }

        val mem = data.optJSONObject("memory")
        if (mem != null) {
            val card = makeCard()
            card.addView(cardTitle("Memory"))
            card.addView(spacer(dp(8)))
            card.addView(metricBar("Usage", mem.optDouble("usagePercent").toFloat(), "%",
                colorAttr(com.google.android.material.R.attr.colorTertiary)))
            card.addView(spacer(dp(8)))
            val card2 = makeGrid(
                "Total" to "%.0f MB".format(mem.optDouble("totalMb")),
                "Used" to "%.0f MB".format(mem.optDouble("usedMb")),
                "App" to "%.1f MB".format(mem.optDouble("appMb")))
            card.addView(card2)
            contentArea.addView(card)
            contentArea.addView(spacer(dp(12)))
        }

        val gpu = data.optJSONObject("gpu")
        if (gpu != null) {
            val card = makeCard()
            card.addView(cardTitle("GPU"))
            card.addView(spacer(dp(8)))
            card.addView(metricBar("Usage", gpu.optDouble("usage").toFloat(), "%",
                colorAttr(androidx.appcompat.R.attr.colorError)))
            card.addView(spacer(dp(8)))
            val grid = makeGrid(
                "Frequency" to "${gpu.optDouble("freqMhz").toInt()} MHz",
                "Temperature" to "${gpu.optDouble("tempC").let { if (it > 0) "%.1f°C".format(it) else "N/A" }}")
            card.addView(grid)
            contentArea.addView(card)
            contentArea.addView(spacer(dp(12)))
        }

        val app = data.optJSONObject("app")
        if (app != null) {
            val card = makeCard()
            card.addView(cardTitle("App Process"))
            card.addView(spacer(dp(8)))
            val grid = makeGrid(
                "Threads" to "${app.optInt("threadCount")}",
                "GC Cycles" to "${app.optInt("gcCount")}",
                "Open FDs" to "${app.optInt("openFds")}")
            card.addView(grid)
            contentArea.addView(card)
            contentArea.addView(spacer(dp(12)))
        }

        contentArea.addView(centeredText("Refreshing every 3 seconds"))
    }

    // ─────────────────────────── HTTP ────────────────────────────────────

    private fun getJwt(): String? = runCatching {
        if (AuthManager.getState() == null) AuthManager.initialize()
        AuthManager.generateJwt()?.takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun httpGetJson(path: String): JSONObject? = runCatching {
        val jwt = getJwt() ?: return null
        val conn = URL("http://127.0.0.1:8080$path").openConnection(Proxy.NO_PROXY) as HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("Authorization", "Bearer $jwt")
        conn.connectTimeout = 5_000; conn.readTimeout = 5_000
        if (conn.responseCode != 200) return null
        JSONObject(conn.inputStream.bufferedReader().readText())
    }.getOrNull()

    private fun httpPostJson(path: String, body: String): JSONObject? = runCatching {
        val jwt = getJwt() ?: return null
        val conn = URL("http://127.0.0.1:8080$path").openConnection(Proxy.NO_PROXY) as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Authorization", "Bearer $jwt")
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true; conn.connectTimeout = 5_000; conn.readTimeout = 5_000
        conn.outputStream.use { it.write(body.toByteArray()) }
        if (conn.responseCode !in 200..299) return null
        JSONObject(conn.inputStream.bufferedReader().readText())
    }.getOrNull()

    private fun httpPost(path: String, body: String): Int = runCatching {
        val jwt = getJwt() ?: return -1
        val conn = URL("http://127.0.0.1:8080$path").openConnection(Proxy.NO_PROXY) as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Authorization", "Bearer $jwt")
        if (body.isNotEmpty()) {
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
        }
        conn.connectTimeout = 3_000; conn.readTimeout = 3_000
        if (body.isNotEmpty()) conn.outputStream.use { it.write(body.toByteArray()) }
        conn.responseCode
    }.getOrDefault(-1)

    // ─────────────────────────── UI HELPERS ──────────────────────────────

    private fun metricBar(label: String, value: Float, suffix: String, color: Int): LinearLayout {
        val row = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val hdr = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        hdr.addView(TextView(context).apply {
            text = label
            setTextAppearance(R.style.TextAppearance_BladeWatch_LabelMedium)
            setTextColor(colorAttr(com.google.android.material.R.attr.colorOnSurfaceVariant))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        hdr.addView(TextView(context).apply {
            text = "${"%.1f".format(value)}$suffix"
            setTextAppearance(R.style.TextAppearance_BladeWatch_LabelMedium)
            setTextColor(colorAttr(com.google.android.material.R.attr.colorOnSurface))
        })
        row.addView(hdr)
        row.addView(spacer(dp(4)))
        val trackColor = colorAttr(com.google.android.material.R.attr.colorSurfaceContainerHighest)
        val barBg = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6))
            background = GradientDrawable().apply { setColor(trackColor); cornerRadius = dp(3).toFloat() }
        }
        val fraction = (value.coerceIn(0f, 100f) / 100f)
        val fillLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        val filledPart = View(context).apply {
            background = GradientDrawable().apply { setColor(color); cornerRadius = dp(3).toFloat() }
        }
        val emptyPart = View(context)
        val pct = fraction.coerceIn(0f, 1f)
        fillLayout.addView(filledPart, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, pct))
        fillLayout.addView(emptyPart, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, (1f - pct).coerceAtLeast(0.001f)))
        barBg.addView(fillLayout)
        row.addView(barBg)
        return row
    }

    private fun makeGrid(vararg pairs: Pair<String, String>): LinearLayout {
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        pairs.forEach { (label, value) ->
            val col = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            col.addView(TextView(context).apply {
                text = value
                setTextAppearance(R.style.TextAppearance_BladeWatch_TitleMedium)
                gravity = Gravity.CENTER
                setTextColor(colorAttr(com.google.android.material.R.attr.colorOnSurface))
            })
            col.addView(TextView(context).apply {
                this.text = label
                setTextAppearance(R.style.TextAppearance_BladeWatch_LabelMedium)
                gravity = Gravity.CENTER
                setTextColor(colorAttr(com.google.android.material.R.attr.colorOnSurfaceVariant))
            })
            row.addView(col)
        }
        return row
    }

    private fun makeCard() = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            setColor(colorAttr(com.google.android.material.R.attr.colorSurfaceContainer))
            cornerRadius = dimen(R.dimen.card_radius_sm).toFloat()
        }
        val pad = dimen(R.dimen.card_padding_standard)
        setPadding(pad, dp(12), pad, dp(12))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun cardTitle(text: String) = TextView(context).apply {
        this.text = text
        setTextAppearance(R.style.TextAppearance_BladeWatch_TitleMedium)
        setTextColor(colorAttr(com.google.android.material.R.attr.colorOnSurface))
    }

    private fun sectionLabel(text: String) = TextView(context).apply {
        this.text = text
        setTextAppearance(R.style.TextAppearance_BladeWatch_TitleLarge)
        setTextColor(colorAttr(com.google.android.material.R.attr.colorOnSurface))
    }

    private fun centeredText(text: String) = TextView(context).apply {
        this.text = text
        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
        gravity = Gravity.CENTER
        setTextColor(colorAttr(com.google.android.material.R.attr.colorOnSurfaceVariant))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(24) }
    }

    private fun colorAttr(attr: Int): Int {
        val ta = context.obtainStyledAttributes(intArrayOf(attr))
        return ta.getColor(0, Color.GRAY).also { ta.recycle() }
    }

    private fun dimen(resId: Int) = context.resources.getDimensionPixelSize(resId)
    private fun spacer(h: Int) = View(context).apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h) }
    private fun dp(v: Int) = (v * context.resources.displayMetrics.density + 0.5f).toInt()
}
