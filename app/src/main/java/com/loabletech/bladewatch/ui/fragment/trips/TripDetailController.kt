package net.bladewatch.app.ui.fragment.trips

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatDelegate
import net.bladewatch.app.ui.util.PreferencesManager
import org.osmdroid.config.Configuration as OsmConfig
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.TilesOverlay

/**
 * Full-screen trip detail panel shown when a trip row is tapped. Restores the
 * detail view that existed in the old WebView Trips screen: a route map
 * (OSMDroid, same tiles as the Location screen), a summary stat grid, and the
 * five driving-DNA score bars.
 *
 * The view is added on top of the Trips list inside [TripsController]; back
 * press closes it. Data is fetched off the main thread via [TripsClient].
 */
internal class TripDetailController(
    private val context: Context,
    private val client: TripsClient,
    private val onClose: () -> Unit,
) {
    private val root = FrameLayout(context)
    private val mapView = MapView(context)
    private val routeOverlay = Polyline(mapView)
    private val startMarker = Marker(mapView)
    private val endMarker = Marker(mapView)

    private lateinit var contentArea: LinearLayout
    private lateinit var mapStatusText: TextView

    private var config: TripsConfig? = null
    private var loadThread: Thread? = null
    private var destroyed = false

    init {
        OsmConfig.getInstance().userAgentValue = context.packageName
        mapView.setDestroyMode(false)
        buildView()
    }

    val view: View get() = root

    fun load(tripId: Long, config: TripsConfig?) {
        this.config = config
        setStatus("Loading trip…")
        val t = Thread({
            val detail = client.fetchTripDetail(tripId)
            val telemetry = if (detail != null) client.fetchTelemetry(tripId) else emptyList()
            if (destroyed) return@Thread
            root.post {
                if (destroyed) return@post
                if (detail == null) {
                    setStatus("Trip details unavailable")
                } else {
                    renderDetail(detail, telemetry)
                }
            }
        }, "TripDetailLoad").apply { isDaemon = true }
        loadThread = t
        t.start()
    }

    fun onResume() { mapView.onResume() }
    fun onPause() { mapView.onPause() }
    fun onConfigurationChanged() { applyMapAppearance() }
    fun onDestroy() {
        destroyed = true
        mapView.onDetach()
    }

    // ──────────────────────────────── BUILD ────────────────────────────────

    private fun buildView() {
        val isDark = isDark()
        root.setBackgroundColor(if (isDark) Color.parseColor("#121212") else Color.parseColor("#F5F5F5"))
        root.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        // Consume taps so they never fall through to the list underneath.
        root.isClickable = true

        val outer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        outer.addView(buildHeader())

        val scroll = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            isFillViewport = true
        }
        contentArea = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        scroll.addView(contentArea)
        outer.addView(scroll)
        root.addView(outer)
    }

    private fun buildHeader(): LinearLayout {
        val isDark = isDark()
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(if (isDark) Color.parseColor("#1E1E1E") else Color.WHITE)
            setPadding(dp(8), dp(8), dp(12), dp(8))
        }
        val backBtn = TextView(context).apply {
            text = "‹  Back"
            textSize = 15f
            setTextColor(Color.parseColor("#4CAF50"))
            setPadding(dp(8), dp(8), dp(12), dp(8))
            setOnClickListener { onClose() }
        }
        bar.addView(backBtn)
        return bar
    }

    private fun setStatus(message: String) {
        contentArea.removeAllViews()
        contentArea.addView(centeredText(message, 15f))
    }

    // ──────────────────────────────── RENDER ───────────────────────────────

    private fun renderDetail(trip: TripDetail, telemetry: List<TelemetryPoint>) {
        contentArea.removeAllViews()

        // Title + time range
        contentArea.addView(labelText(trip.formattedDateTitle, 18f, bold = true))
        contentArea.addView(smallText(trip.formattedTimeRange))
        contentArea.addView(spacer(dp(12)))

        // Route map
        contentArea.addView(buildMapCard())
        contentArea.addView(spacer(dp(12)))
        renderRoute(telemetry)

        // Summary stats
        contentArea.addView(buildSummaryCard(trip))
        contentArea.addView(spacer(dp(12)))

        // Score bars
        contentArea.addView(buildScoreCard(trip))
    }

    private fun buildMapCard(): FrameLayout {
        val isDark = isDark()
        val card = FrameLayout(context).apply {
            background = cardBackground(isDark)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(240))
            clipToOutline = true
        }

        mapView.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.setMultiTouchControls(true)
        mapView.isTilesScaledToDpi = true
        mapView.minZoomLevel = 3.0
        mapView.maxZoomLevel = 20.0
        // Allow the map to be panned/zoomed inside the ScrollView.
        mapView.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE ->
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    v.parent?.requestDisallowInterceptTouchEvent(false)
            }
            false
        }

        routeOverlay.outlinePaint.color = Color.parseColor("#6366F1")
        routeOverlay.outlinePaint.strokeWidth = dp(4).toFloat()
        routeOverlay.outlinePaint.isAntiAlias = true

        startMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        startMarker.icon = dotMarker(Color.parseColor("#22C55E"))
        startMarker.title = "Start"
        endMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        endMarker.icon = dotMarker(Color.parseColor("#EF4444"))
        endMarker.title = "End"

        applyMapAppearance()

        mapStatusText = centeredText("", 14f).apply {
            visibility = View.GONE
        }

        card.addView(mapView)
        card.addView(mapStatusText, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.CENTER })
        return card
    }

    private fun renderRoute(telemetry: List<TelemetryPoint>) {
        val points = telemetry.filter { it.hasGps }.map { GeoPoint(it.lat, it.lon) }
        mapView.overlays.clear()
        if (points.size < 2) {
            mapView.visibility = View.GONE
            mapStatusText.text = "No route data for this trip"
            mapStatusText.visibility = View.VISIBLE
            return
        }
        mapView.visibility = View.VISIBLE
        mapStatusText.visibility = View.GONE

        routeOverlay.setPoints(points)
        startMarker.position = points.first()
        endMarker.position = points.last()
        mapView.overlays.add(routeOverlay)
        mapView.overlays.add(startMarker)
        mapView.overlays.add(endMarker)

        val box = BoundingBox.fromGeoPoints(points)
        mapView.post {
            if (destroyed) return@post
            // padding factor > 1 leaves margin around the route
            mapView.zoomToBoundingBox(box.increaseByScale(1.4f), false)
            mapView.invalidate()
        }
    }

    private fun buildSummaryCard(trip: TripDetail): LinearLayout {
        val card = makeCard()
        card.addView(labelText("Trip Summary", 14f, bold = true))
        card.addView(spacer(dp(8)))

        val distUnit = config?.distanceUnit ?: "km"
        val distStr = if (distUnit == "mi") "%.1f mi".format(trip.distanceKm * 0.621371)
                      else "%.1f km".format(trip.distanceKm)
        val avgSpd = if (distUnit == "mi") "%.0f mph".format(trip.avgSpeedKmh * 0.621371)
                     else "%.0f km/h".format(trip.avgSpeedKmh)
        val maxSpd = if (distUnit == "mi") "%.0f mph".format(trip.maxSpeedKmh * 0.621371)
                     else "%.0f km/h".format(trip.maxSpeedKmh)
        val energyStr = if (trip.energyUsedKwh > 0) "%.1f kWh".format(trip.energyUsedKwh) else "--"
        val costStr = if (trip.tripCost > 0 && trip.currency.isNotBlank())
            "${trip.currency} %.2f".format(trip.tripCost) else "--"
        val socStr = "%.0f → %.0f%%".format(trip.socStart, trip.socEnd)
        val tempStr = if (trip.extTempC != 0.0) "%.0f°C".format(trip.extTempC) else "--"
        val elevStr = if (trip.elevationGainM > 0) "+%.0fm".format(trip.elevationGainM) else "--"

        val cells = listOf(
            "Distance" to distStr,
            "Duration" to trip.formattedDuration,
            "Energy" to energyStr,
            "Avg Speed" to avgSpd,
            "Max Speed" to maxSpd,
            "SoC" to socStr,
            "Cost" to costStr,
            "Ext Temp" to tempStr,
            "Elev Gain" to elevStr,
        )
        // 3-column grid
        var rowLayout: LinearLayout? = null
        cells.forEachIndexed { i, (label, value) ->
            if (i % 3 == 0) {
                rowLayout = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                card.addView(rowLayout)
            }
            rowLayout!!.addView(statBlock(label, value),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        return card
    }

    private fun buildScoreCard(trip: TripDetail): LinearLayout {
        val card = makeCard()
        card.addView(labelText("Driving Scores", 14f, bold = true))
        card.addView(spacer(dp(8)))
        listOf(
            "Anticipation" to trip.anticipationScore,
            "Smoothness" to trip.smoothnessScore,
            "Speed Discipline" to trip.speedDisciplineScore,
            "Efficiency" to trip.efficiencyScore,
            "Consistency" to trip.consistencyScore,
        ).forEach { (name, score) ->
            card.addView(buildScoreBar(name, score))
            card.addView(spacer(dp(6)))
        }
        return card
    }

    private fun buildScoreBar(name: String, score: Int): LinearLayout {
        val isDark = isDark()
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        val label = TextView(context).apply {
            text = name
            textSize = 13f
            setTextColor(if (isDark) Color.WHITE else Color.BLACK)
            layoutParams = LinearLayout.LayoutParams(dp(130), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val barBg = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(8), 1f)
            background = pillBackground(if (isDark) Color.parseColor("#2C2C2C") else Color.parseColor("#EEEEEE"))
        }
        val fraction = score.coerceIn(0, 100) / 100f
        val fill = View(context).apply {
            background = pillBackground(scoreColor(score))
            layoutParams = FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        barBg.addView(fill)
        barBg.viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                barBg.viewTreeObserver.removeOnGlobalLayoutListener(this)
                fill.layoutParams = FrameLayout.LayoutParams(
                    (fraction * barBg.width).toInt(), ViewGroup.LayoutParams.MATCH_PARENT)
                fill.requestLayout()
            }
        })
        val scoreLabel = TextView(context).apply {
            text = "$score"
            textSize = 12f
            setTextColor(if (isDark) Color.parseColor("#AAAAAA") else Color.parseColor("#666666"))
            setPadding(dp(6), 0, 0, 0)
        }
        row.addView(label); row.addView(barBg); row.addView(scoreLabel)
        return row
    }

    private fun scoreColor(score: Int): Int = when {
        score >= 70 -> Color.parseColor("#4CAF50")
        score >= 40 -> Color.parseColor("#F59E0B")
        else -> Color.parseColor("#EF4444")
    }

    // ──────────────────────────────── MAP THEME ────────────────────────────

    private fun applyMapAppearance() {
        val tilesOverlay = mapView.overlayManager.tilesOverlay ?: return
        if (isDark()) {
            tilesOverlay.setColorFilter(TilesOverlay.INVERT_COLORS)
            tilesOverlay.setLoadingBackgroundColor(Color.BLACK)
            tilesOverlay.setLoadingLineColor(Color.DKGRAY)
        } else {
            tilesOverlay.setColorFilter(null)
            tilesOverlay.setLoadingBackgroundColor(Color.rgb(216, 208, 208))
            tilesOverlay.setLoadingLineColor(Color.rgb(200, 192, 192))
        }
        mapView.invalidate()
    }

    private fun dotMarker(color: Int): BitmapDrawable {
        val density = context.resources.displayMetrics.density
        val size = (24 * density).toInt().coerceAtLeast(24)
        val center = size / 2f
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.style = Paint.Style.FILL
        paint.color = color
        canvas.drawCircle(center, center, size * 0.36f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.5f * density
        paint.color = Color.WHITE
        canvas.drawCircle(center, center, size * 0.36f, paint)
        return BitmapDrawable(context.resources, bitmap).apply { setBounds(0, 0, size, size) }
    }

    // ──────────────────────────────── HELPERS ──────────────────────────────

    private fun isDark(): Boolean {
        val mode = if (PreferencesManager.isInitialized()) PreferencesManager.getThemeMode()
                   else AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        return when (mode) {
            AppCompatDelegate.MODE_NIGHT_YES -> true
            AppCompatDelegate.MODE_NIGHT_NO -> false
            else -> {
                val night = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
                night == Configuration.UI_MODE_NIGHT_YES
            }
        }
    }

    private fun pillBackground(color: Int): GradientDrawable =
        GradientDrawable().apply { setColor(color); cornerRadius = dp(20).toFloat() }

    private fun cardBackground(isDark: Boolean): GradientDrawable =
        GradientDrawable().apply {
            setColor(if (isDark) Color.parseColor("#1E1E1E") else Color.WHITE)
            cornerRadius = dp(8).toFloat()
        }

    private fun makeCard(): LinearLayout {
        val isDark = isDark()
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBackground(isDark)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    private fun labelText(text: String, size: Float, bold: Boolean): TextView {
        val isDark = isDark()
        return TextView(context).apply {
            this.text = text; textSize = size
            setTextColor(if (isDark) Color.WHITE else Color.BLACK)
            if (bold) typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
    }

    private fun smallText(text: String): TextView {
        val isDark = isDark()
        return TextView(context).apply {
            this.text = text; textSize = 13f
            setTextColor(if (isDark) Color.parseColor("#AAAAAA") else Color.parseColor("#666666"))
        }
    }

    private fun centeredText(text: String, size: Float): TextView {
        val isDark = isDark()
        return TextView(context).apply {
            this.text = text; textSize = size; gravity = Gravity.CENTER
            setTextColor(if (isDark) Color.parseColor("#AAAAAA") else Color.parseColor("#666666"))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(32)
            }
        }
    }

    private fun statBlock(label: String, value: String): LinearLayout {
        val isDark = isDark()
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, dp(6))
            addView(TextView(context).apply {
                text = value; textSize = 16f; gravity = Gravity.CENTER
                setTextColor(if (isDark) Color.WHITE else Color.BLACK)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            })
            addView(TextView(context).apply {
                this.text = label; textSize = 11f; gravity = Gravity.CENTER
                setTextColor(if (isDark) Color.parseColor("#AAAAAA") else Color.parseColor("#666666"))
            })
        }
    }

    private fun spacer(h: Int): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h)
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()
}
