package net.bladewatch.app.ui.fragment.location

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.graphics.drawable.BitmapDrawable
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.TilesOverlay

class LocationMapController(
    private val context: Context,
    private val settingsStore: LocationSettingsStore = LocationSettingsStore(context),
) {
    private val root = FrameLayout(context)
    private val mapView = MapView(context)
    private val marker = Marker(mapView)
    private val banner = LinearLayout(context)
    private val bannerTitle = TextView(context)
    private val bannerSubtitle = TextView(context)
    private val bannerAction = Button(context)
    private val modeSelectorContainer = LinearLayout(context)
    private val autoModeButton = TextView(context)
    private val lightModeButton = TextView(context)
    private val darkModeButton = TextView(context)
    private val recenterButton = ImageButton(context)
    private val markerHotspot = View(context)

    private var bannerActionHandler: ((LocationUiState) -> Unit)? = null
    private var bannerState: LocationUiState? = null
    private var viewportState = LocationMapViewportState()
    private var lastLocation: LocationCarGps? = null
    private var movingProgrammatically = false
    private var mapVisible = false
    private var userTouchingMap = false
    private var appliedAppearance: LocationMapAppearance? = null

    init {
        Configuration.getInstance().userAgentValue = context.packageName
        mapView.setDestroyMode(false)
        buildView()
    }

    val view: View get() = root

    fun setBannerActionHandler(handler: (LocationUiState) -> Unit) {
        bannerActionHandler = handler
    }

    fun render(state: LocationUiState, networkAvailable: Boolean) {
        applyMapAppearance()
        val effectiveState = when (state) {
            is LocationFresh, is LocationStale -> {
                if (networkAvailable) state
                else LocationStateReducer.tileFailure(state, "Network unavailable")
            }
            else -> state
        }
        Log.d(TAG, "render=${effectiveState.logLabel()} network=$networkAvailable")
        val panel = LocationUiStateMapper.panelModel(effectiveState)
        bannerState = effectiveState
        mapVisible = panel.showMap
        mapView.visibility = if (panel.showMap) View.VISIBLE else View.GONE
        modeSelectorContainer.visibility = if (panel.showMap) View.VISIBLE else View.GONE
        renderBanner(panel)
        when (effectiveState) {
            is LocationFresh -> renderLocation(effectiveState.location, centerAllowed = true)
            is LocationStale -> renderLocation(effectiveState.location, centerAllowed = true)
            is LocationTileFailure -> effectiveState.location?.let { renderLocation(it, centerAllowed = false) }
            is LocationError -> effectiveState.location?.let { renderLocation(it, centerAllowed = false) }
            else -> Unit
        }
    }

    fun onResume() {
        applyMapAppearance()
        mapView.onResume()
    }

    fun onPause() {
        mapView.onPause()
    }

    fun onConfigurationChanged() {
        applyMapAppearance()
    }

    fun onDestroy() {
        mapView.onDetach()
    }

    fun onUserPan() {
        viewportState = LocationMapReducer.onUserPan(viewportState)
    }

    private fun buildView() {
        root.setBackgroundColor(Color.BLACK)
        mapView.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        mapView.setTileSource(TileSourceFactory.MAPNIK)
        applyMapAppearance()
        mapView.setMultiTouchControls(true)
        mapView.isTilesScaledToDpi = true
        mapView.minZoomLevel = 3.0
        mapView.maxZoomLevel = 21.0
        mapView.controller.setZoom(17.5)
        mapView.overlays.add(marker)
        marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        marker.icon = createCarMarker()

        markerHotspot.setBackgroundColor(Color.TRANSPARENT)
        markerHotspot.alpha = 0.01f
        markerHotspot.visibility = View.GONE

        mapView.addMapListener(object : org.osmdroid.events.MapListener {
            override fun onScroll(event: org.osmdroid.events.ScrollEvent?): Boolean {
                if (userTouchingMap && !movingProgrammatically) onUserPan()
                lastLocation?.let { updateMarkerHotspot(it) }
                return false
            }
            override fun onZoom(event: org.osmdroid.events.ZoomEvent?): Boolean {
                if (userTouchingMap && !movingProgrammatically) onUserPan()
                lastLocation?.let { updateMarkerHotspot(it) }
                return false
            }
        })
        mapView.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> userTouchingMap = true
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> userTouchingMap = false
            }
            false
        }

        root.addView(mapView)
        root.addView(markerHotspot, FrameLayout.LayoutParams(dp(132), dp(132)))
        root.addView(buildRecenterButton())
        root.addView(buildBanner())
        root.addView(buildModeSelector())
    }

    private fun buildRecenterButton(): View {
        recenterButton.setImageResource(android.R.drawable.ic_menu_mylocation)
        recenterButton.contentDescription = "Recenter on car"
        recenterButton.setColorFilter(Color.WHITE)
        recenterButton.setBackgroundColor(Color.parseColor("#CC101010"))
        recenterButton.scaleType = ImageView.ScaleType.CENTER_INSIDE
        recenterButton.visibility = View.GONE
        recenterButton.setOnClickListener { recenterOnCar() }
        val size = dp(56)
        val margin = dp(24)
        recenterButton.layoutParams = FrameLayout.LayoutParams(size, size).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            rightMargin = margin
            bottomMargin = margin + dp(96)
        }
        return recenterButton
    }

    private fun buildBanner(): View {
        banner.orientation = LinearLayout.VERTICAL
        banner.setBackgroundColor(Color.parseColor("#CC101010"))
        banner.setPadding(24, 20, 24, 20)

        bannerTitle.setTextColor(Color.WHITE)
        bannerTitle.textSize = 18f

        bannerSubtitle.setTextColor(Color.parseColor("#D0D0D0"))
        bannerSubtitle.textSize = 14f

        bannerAction.visibility = View.GONE
        bannerAction.setOnClickListener {
            bannerActionHandler?.invoke(bannerState ?: LocationLoading)
        }

        banner.addView(bannerTitle)
        banner.addView(bannerSubtitle)
        banner.addView(
            bannerAction,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = 16 },
        )

        val lp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        lp.gravity = Gravity.CENTER
        lp.leftMargin = 24
        lp.rightMargin = 24
        banner.layoutParams = lp
        return banner
    }

    private fun renderBanner(panel: LocationPanelModel) {
        bannerTitle.text = panel.title
        bannerSubtitle.text = panel.subtitle.orEmpty()
        bannerSubtitle.visibility = if (panel.subtitle.isNullOrBlank()) View.GONE else View.VISIBLE
        val actionLabel = panel.actionLabel
        bannerAction.text = actionLabel ?: ""
        bannerAction.visibility = if (actionLabel.isNullOrBlank()) View.GONE else View.VISIBLE
        val params = banner.layoutParams as FrameLayout.LayoutParams
        params.gravity = if (panel.showMap) Gravity.TOP or Gravity.START else Gravity.CENTER
        banner.layoutParams = params
        val pad = if (panel.compact) 18 else 24
        banner.setPadding(pad * 2, pad, pad * 2, pad)
        banner.visibility = View.VISIBLE
        renderRecenterButton()
    }

    private fun renderLocation(location: LocationCarGps, centerAllowed: Boolean) {
        val update = LocationMapReducer.onValidLocation(viewportState, location)
        viewportState = update.viewportState
        lastLocation = location
        val geoPoint = GeoPoint(location.latitude, location.longitude)
        marker.position = geoPoint
        marker.rotation = LocationMapBearingMapper.markerRotation(location.bearingDegrees)
        mapView.invalidate()
        mapView.post {
            if (centerAllowed && viewportState.followCar) {
                centerOn(location, update.zoomLevel)
            }
            updateMarkerHotspot(location)
            mapView.invalidate()
        }
        renderRecenterButton()
    }

    private fun recenterOnCar() {
        viewportState = LocationMapReducer.onFollowRequested(viewportState)
        viewportState.lastLocation?.let { centerOn(it, zoomLevel = null) }
        renderRecenterButton()
    }

    private fun centerOn(location: LocationCarGps, zoomLevel: Double?) {
        movingProgrammatically = true
        zoomLevel?.let { mapView.controller.setZoom(it) }
        mapView.controller.setCenter(GeoPoint(location.latitude, location.longitude))
        mapView.postDelayed({ movingProgrammatically = false }, 500L)
    }

    private fun renderRecenterButton() {
        recenterButton.visibility =
            if (mapVisible && viewportState.lastLocation != null && !viewportState.followCar) {
                View.VISIBLE
            } else {
                View.GONE
            }
    }

    private fun applyMapAppearance() {
        val appearance = LocationAppearanceResolver.resolve(
            context = context,
            uiModePreference = settingsStore.readUiModePreference(),
        )
        renderModeSelector()
        if (appliedAppearance?.useNightTiles == appearance.useNightTiles) {
            appliedAppearance = appearance
            return
        }
        val tilesOverlay = mapView.overlayManager.tilesOverlay ?: return
        if (appearance.useNightTiles) {
            tilesOverlay.setColorFilter(TilesOverlay.INVERT_COLORS)
            tilesOverlay.setLoadingBackgroundColor(Color.BLACK)
            tilesOverlay.setLoadingLineColor(Color.DKGRAY)
        } else {
            tilesOverlay.setColorFilter(null)
            tilesOverlay.setLoadingBackgroundColor(Color.rgb(216, 208, 208))
            tilesOverlay.setLoadingLineColor(Color.rgb(200, 192, 192))
        }
        appliedAppearance = appearance
        mapView.invalidate()
    }

    private fun buildModeSelector(): View {
        modeSelectorContainer.orientation = LinearLayout.HORIZONTAL
        modeSelectorContainer.gravity = Gravity.CENTER_VERTICAL
        modeSelectorContainer.setPadding(dp(6), dp(6), dp(6), dp(6))
        modeSelectorContainer.setBackgroundColor(Color.parseColor("#CC101010"))
        modeSelectorContainer.visibility = View.GONE

        configureModeButton(autoModeButton, "Auto") {
            settingsStore.setUiModePreference(LocationUiModePreference.AUTO)
            applyMapAppearance()
        }
        configureModeButton(lightModeButton, "Light") {
            settingsStore.setUiModePreference(LocationUiModePreference.LIGHT)
            applyMapAppearance()
        }
        configureModeButton(darkModeButton, "Dark") {
            settingsStore.setUiModePreference(LocationUiModePreference.DARK)
            applyMapAppearance()
        }

        modeSelectorContainer.addView(autoModeButton)
        modeSelectorContainer.addView(lightModeButton)
        modeSelectorContainer.addView(darkModeButton)
        modeSelectorContainer.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            topMargin = dp(24)
            rightMargin = dp(24)
        }
        renderModeSelector()
        return modeSelectorContainer
    }

    private fun configureModeButton(button: TextView, label: String, onClick: () -> Unit) {
        button.text = label
        button.gravity = Gravity.CENTER
        button.textSize = 13f
        button.minWidth = dp(62)
        button.minHeight = dp(40)
        button.setPadding(dp(10), 0, dp(10), 0)
        button.setOnClickListener { onClick() }
    }

    private fun renderModeSelector() {
        val pref = settingsStore.readUiModePreference()
        renderModeButton(autoModeButton, pref == LocationUiModePreference.AUTO)
        renderModeButton(lightModeButton, pref == LocationUiModePreference.LIGHT)
        renderModeButton(darkModeButton, pref == LocationUiModePreference.DARK)
    }

    private fun renderModeButton(button: TextView, selected: Boolean) {
        if (selected) {
            button.setTextColor(Color.parseColor("#151515"))
            button.setBackgroundColor(Color.parseColor("#EDEFEFEF"))
        } else {
            button.setTextColor(Color.WHITE)
            button.setBackgroundColor(Color.TRANSPARENT)
        }
    }

    private fun createCarMarker(): BitmapDrawable {
        val density = context.resources.displayMetrics.density
        val size = (88 * density).toInt().coerceAtLeast(88)
        val center = size / 2f
        val radius = size * 0.33f
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        paint.style = Paint.Style.FILL
        paint.color = Color.argb(150, 0, 0, 0)
        canvas.drawCircle(center, center, size * 0.43f, paint)

        paint.color = Color.parseColor("#005EFF")
        canvas.drawCircle(center, center, radius, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3.5f * density
        paint.color = Color.WHITE
        canvas.drawCircle(center, center, radius, paint)

        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        val arrow = Path().apply {
            moveTo(center, center - radius * 1.05f)
            lineTo(center + radius * 0.45f, center + radius * 0.2f)
            lineTo(center, center - radius * 0.05f)
            lineTo(center - radius * 0.45f, center + radius * 0.2f)
            close()
        }
        canvas.drawPath(arrow, paint)
        canvas.drawCircle(center, center, radius * 0.23f, paint)

        return BitmapDrawable(context.resources, bitmap).apply { setBounds(0, 0, size, size) }
    }

    private fun updateMarkerHotspot(location: LocationCarGps) {
        if (!mapVisible) {
            markerHotspot.visibility = View.GONE
            return
        }
        val point = mapView.projection.toPixels(
            GeoPoint(location.latitude, location.longitude), Point()
        )
        val hotspotSize = dp(132)
        markerHotspot.layoutParams = FrameLayout.LayoutParams(hotspotSize, hotspotSize)
        markerHotspot.x = point.x - hotspotSize / 2f
        markerHotspot.y = point.y - hotspotSize / 2f
        markerHotspot.visibility = View.VISIBLE
        markerHotspot.bringToFront()
        banner.bringToFront()
        modeSelectorContainer.bringToFront()
        recenterButton.bringToFront()
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "LocationMapController"
    }
}
