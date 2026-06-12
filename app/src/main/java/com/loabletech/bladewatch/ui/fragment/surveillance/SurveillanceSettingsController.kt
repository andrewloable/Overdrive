package net.bladewatch.app.ui.fragment.surveillance

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.widget.SwitchCompat
import android.widget.TextView
import android.widget.Toast
import net.bladewatch.app.R
import net.bladewatch.app.ui.common.BladeTheme
import org.osmdroid.config.Configuration as OsmConfig
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

class SurveillanceSettingsController(private val context: Context) {

    private val root = FrameLayout(context)
    private val client = SurveillanceSettingsClient()
    private val theme = BladeTheme(context)

    private var activeTab = SurveillanceSettingsTab.GENERAL
    private lateinit var contentArea: LinearLayout
    private lateinit var tabBtns: Map<SurveillanceSettingsTab, TextView>

    // Editable state (copied from loaded config, mutated by UI)
    private var editEnabled = false
    private var editPreset = "OUTDOOR"
    private var editSensitivity = 3
    private var editDetectPerson = true
    private var editDetectCar = true
    private var editDetectBike = true
    private var editPreRecord = 5
    private var editPostRecord = 10
    private var editNightMode = false
    private var editAiEnabled = true
    private var editCameraFront = true
    private var editCameraRight = true
    private var editCameraRear = true
    private var editCameraLeft = true
    private var editDeterrent = "silent"
    private var editDeterrentCooldown = 60
    private var editStorageType = "INTERNAL"
    private var editStorageLimitMb = 500L
    private var storageMinLimitMb = 100L
    private var storageMaxLimitMb = 100000L
    private var storageMaxLimitMbSdCard = 100000L
    private var storageInternalTotalMb = 0L
    private var storageSdCardTotalMb = 0L

    // Format external drive state (Storage tab)
    private var formatConfirmPending = false
    private var formatRunning = false
    private var formatResultMessage: String? = null

    // Sync catalog state
    private var syncRunning = false
    private var syncResultMessage: String? = null

    private var loadedState: SurveillanceSettingsLoadState? = null
    private var safeLocFeatureEnabled = false
    private var safeZones = listOf<SafeZone>()
    private var currentLat = 0.0
    private var currentLng = 0.0

    private var mapView: MapView? = null
    private var loadedOnce = false

    init {
        OsmConfig.getInstance().userAgentValue = context.packageName
        buildView()
        loadData()
    }

    val view: View get() = root
    fun onResume() { if (loadedOnce) loadData(); mapView?.onResume() }
    fun onPause() { mapView?.onPause() }
    fun onDestroy() { mapView?.onDetach(); mapView = null }
    fun onConfigurationChanged() { applyTheme(); renderCurrentTab() }

    // ─────────────────────────── BUILD ───────────────────────────────────

    private fun buildView() {
        root.setBackgroundColor(bgColor())
        root.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        val outer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        val scroll = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            isFillViewport = true
        }
        contentArea = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        scroll.addView(contentArea)
        outer.addView(scroll)
        outer.addView(buildTabBar())
        root.addView(outer)
    }

    private fun buildTabBar(): LinearLayout {
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(surfaceColor())
            setPadding(dp(6), dp(8), dp(6), dp(8))
            gravity = Gravity.CENTER
        }
        val tabs = listOf(
            SurveillanceSettingsTab.GENERAL to "General",
            SurveillanceSettingsTab.DETECTION to "Detection",
            SurveillanceSettingsTab.RECORDING to "Recording",
            SurveillanceSettingsTab.STORAGE to "Storage",
            SurveillanceSettingsTab.ADVANCED to "Advanced",
        )
        val btnMap = mutableMapOf<SurveillanceSettingsTab, TextView>()
        tabs.forEach { (tab, label) ->
            val btn = TextView(context).apply {
                text = label; textSize = 11f; gravity = Gravity.CENTER
                setPadding(dp(4), dp(8), dp(4), dp(8))
                setOnClickListener { activeTab = tab; updateTabs(); renderCurrentTab() }
            }
            btnMap[tab] = btn
            bar.addView(btn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(dp(2), 0, dp(2), 0)
            })
        }
        tabBtns = btnMap
        updateTabs()
        return bar
    }

    private fun updateTabs() {
        tabBtns.forEach { (tab, btn) ->
            if (tab == activeTab) {
                btn.background = pill(accentColor()); btn.setTextColor(theme.onAccentColor())
            } else {
                btn.background = pill(inactivePillColor()); btn.setTextColor(mutedTextColor())
            }
        }
    }

    // ─────────────────────────── DATA ────────────────────────────────────

    private fun loadData() {
        root.post { contentArea.removeAllViews(); contentArea.addView(centeredText("Loading…", 15f)) }
        Thread({
            val config = client.fetchConfig()
            val status = client.fetchStatus()
            val storage = client.fetchStorage()
            val safeLoc = client.fetchSafeLocations()

            if (config != null) {
                editEnabled = config.enabled; editPreset = config.distancePreset
                editSensitivity = config.sensitivityLevel
                editDetectPerson = config.detectPerson; editDetectCar = config.detectCar; editDetectBike = config.detectBike
                editPreRecord = config.preRecordSeconds; editPostRecord = config.postRecordSeconds
                editNightMode = config.nightMode; editAiEnabled = config.aiEnabled
                editCameraFront = config.cameraFront; editCameraRight = config.cameraRight
                editCameraRear = config.cameraRear; editCameraLeft = config.cameraLeft
                editDeterrent = config.deterrentAction; editDeterrentCooldown = config.deterrentCooldownSeconds
            }
            if (storage != null) {
                editStorageType = storage.storageType; editStorageLimitMb = storage.limitMb
                storageMinLimitMb = storage.minLimitMb
                storageMaxLimitMb = storage.maxLimitMb
                storageMaxLimitMbSdCard = storage.maxLimitMbSdCard
                storageInternalTotalMb = storage.internalTotalMb
                storageSdCardTotalMb = storage.sdCardTotalMb
            }
            if (safeLoc != null) {
                safeLocFeatureEnabled = safeLoc.featureEnabled
                safeZones = safeLoc.zones
                currentLat = safeLoc.currentLat; currentLng = safeLoc.currentLng
            }

            loadedState = SurveillanceSettingsLoadState.Loaded(
                SurveillanceAllSettings(config, status, storage, safeLoc))
            root.post { renderCurrentTab(); loadedOnce = true }
        }, "SurvSettingsLoad").apply { isDaemon = true; start() }
    }

    // ─────────────────────────── RENDER ──────────────────────────────────

    private fun renderCurrentTab() {
        if (activeTab != SurveillanceSettingsTab.STORAGE) {
            formatConfirmPending = false; formatRunning = false; formatResultMessage = null
            syncRunning = false; syncResultMessage = null
        }
        applyTheme(); updateTabs()
        mapView?.let { root.post { it.onPause(); it.onDetach() } }
        mapView = null
        contentArea.removeAllViews()
        when (val state = loadedState) {
            null, SurveillanceSettingsLoadState.Loading -> contentArea.addView(centeredText("Loading…", 15f))
            is SurveillanceSettingsLoadState.Error -> contentArea.addView(centeredText("Error: ${state.message}", 13f))
            is SurveillanceSettingsLoadState.Loaded -> when (activeTab) {
                SurveillanceSettingsTab.GENERAL -> renderGeneral(state.settings)
                SurveillanceSettingsTab.DETECTION -> renderDetection()
                SurveillanceSettingsTab.RECORDING -> renderRecording()
                SurveillanceSettingsTab.STORAGE -> renderStorage(state.settings.storage)
                SurveillanceSettingsTab.ADVANCED -> renderAdvanced()
            }
        }
    }

    private fun applyTheme() { root.setBackgroundColor(bgColor()) }

    // ─────────────────────────── GENERAL ─────────────────────────────────

    private fun renderGeneral(s: SurveillanceAllSettings) {
        val card = makeCard()
        card.addView(sectionLabel("Surveillance Mode"))
        card.addView(spacer(dp(10)))

        // Enable toggle
        val toggleRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val toggleLabel = TextView(context).apply {
            text = "Enable Surveillance"; textSize = 14f
            setTextColor(theme.textColor())
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val sw = SwitchCompat(context).apply { isChecked = editEnabled }
        sw.setOnCheckedChangeListener { _, checked ->
            editEnabled = checked
            Thread({
                client.toggleSurveillance(checked)
                Thread.sleep(300)
                root.post { loadData() }
            }, "SurvToggle").apply { isDaemon = true; start() }
        }
        toggleRow.addView(toggleLabel); toggleRow.addView(sw)
        card.addView(toggleRow)
        card.addView(spacer(dp(12)))

        val status = s.status
        card.addView(infoRow("Status", if (status?.isRunning == true) "Running" else "Idle"))
        card.addView(spacer(dp(6)))
        card.addView(infoRow("Events Today", "${status?.eventsToday ?: 0}"))
        contentArea.addView(card)
    }

    // ─────────────────────────── DETECTION ───────────────────────────────

    private fun renderDetection() {
        // Safe Locations card
        val safeCard = makeCard()
        safeCard.addView(sectionLabel("Safe Locations"))
        safeCard.addView(spacer(dp(6)))
        safeCard.addView(TextView(context).apply {
            text = "Camera won't start when parked here"; textSize = 12f; setTextColor(mutedTextColor())
        })
        safeCard.addView(spacer(dp(10)))

        val toggleRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val featureLabel = TextView(context).apply {
            text = "Disable at Safe Locations"; textSize = 14f
            setTextColor(theme.textColor())
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val safeSwitch = SwitchCompat(context).apply { isChecked = safeLocFeatureEnabled }
        safeSwitch.setOnCheckedChangeListener { _, checked ->
            safeLocFeatureEnabled = checked
            Thread({ client.toggleSafeLocations(checked); root.post { loadData() } }, "SafeLocToggle").apply { isDaemon = true; start() }
        }
        toggleRow.addView(featureLabel); toggleRow.addView(safeSwitch)
        safeCard.addView(toggleRow)
        safeCard.addView(spacer(dp(10)))

        // OSMDroid Map
        if (currentLat != 0.0 || currentLng != 0.0 || safeZones.isNotEmpty()) {
            val mapContainer = FrameLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(200))
            }
            val mv = MapView(context).apply {
                setDestroyMode(false)
                setTileSource(TileSourceFactory.MAPNIK)
                setMultiTouchControls(true)
            }
            mapView = mv
            val mapLat = if (currentLat != 0.0) currentLat else safeZones.firstOrNull()?.lat ?: 0.0
            val mapLng = if (currentLng != 0.0) currentLng else safeZones.firstOrNull()?.lng ?: 0.0
            if (mapLat != 0.0 || mapLng != 0.0) {
                mv.controller.setZoom(15.0)
                mv.controller.setCenter(GeoPoint(mapLat, mapLng))
            }
            // Add markers for zones
            safeZones.forEach { zone ->
                val marker = Marker(mv).apply {
                    position = GeoPoint(zone.lat, zone.lng)
                    title = zone.name
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                }
                mv.overlays.add(marker)
            }
            // Current location marker
            if (currentLat != 0.0 || currentLng != 0.0) {
                val curMarker = Marker(mv).apply {
                    position = GeoPoint(currentLat, currentLng)
                    title = "Current Location"
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                }
                mv.overlays.add(curMarker)
            }
            mapContainer.addView(mv, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            safeCard.addView(mapContainer)
            safeCard.addView(spacer(dp(8)))
        }

        // Zone list
        if (safeZones.isNotEmpty()) {
            safeZones.forEach { zone ->
                val zoneRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(4), 0, dp(4))
                }
                val zoneText = TextView(context).apply {
                    text = "${zone.name}  (${zone.radiusM}m)"; textSize = 13f
                    setTextColor(theme.textColor())
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                val deleteBtn = TextView(context).apply {
                    text = "✕"; textSize = 16f; setTextColor(theme.errorColor())
                    setPadding(dp(8), dp(4), dp(4), dp(4))
                    setOnClickListener {
                        Thread({ client.deleteSafeZone(zone.id); root.post { loadData() } }, "SafeZoneDel").apply { isDaemon = true; start() }
                    }
                }
                zoneRow.addView(zoneText); zoneRow.addView(deleteBtn)
                safeCard.addView(zoneRow)
            }
        } else {
            safeCard.addView(TextView(context).apply {
                text = "No safe locations added yet"; textSize = 13f; setTextColor(mutedTextColor())
            })
        }
        safeCard.addView(spacer(dp(8)))

        // Add at current GPS location button
        if (currentLat != 0.0 || currentLng != 0.0) {
            safeCard.addView(makeActionBtn("Add Current Location as Safe Zone") {
                Thread({
                    client.addSafeZone("Safe Zone", currentLat, currentLng, 150)
                    root.post { loadData() }
                }, "SafeZoneAdd").apply { isDaemon = true; start() }
            })
        } else {
            safeCard.addView(TextView(context).apply {
                text = "GPS location not available"; textSize = 12f; setTextColor(mutedTextColor())
            })
        }

        contentArea.addView(safeCard)
        contentArea.addView(spacer(dp(12)))

        // Detection Settings card
        val detCard = makeCard()
        detCard.addView(sectionLabel("Detection Settings"))
        detCard.addView(spacer(dp(12)))

        detCard.addView(fieldLabel("Environment Preset"))
        detCard.addView(spacer(dp(6)))
        val presetRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("OUTDOOR", "GARAGE", "STREET", "CUSTOM").forEach { preset ->
            val btn = makeSegmentBtn(preset.lowercase().replaceFirstChar { it.uppercaseChar() }, editPreset == preset) {
                editPreset = preset; renderCurrentTab()
            }
            presetRow.addView(btn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(0, 0, dp(4), 0)
            })
        }
        detCard.addView(presetRow)
        detCard.addView(spacer(dp(12)))

        detCard.addView(fieldLabel("Sensitivity (1=strict, 5=sensitive): $editSensitivity"))
        detCard.addView(spacer(dp(6)))
        val sensRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        (1..5).forEach { level ->
            val btn = makeSegmentBtn("$level", editSensitivity == level) {
                editSensitivity = level; renderCurrentTab()
            }
            sensRow.addView(btn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(0, 0, dp(4), 0)
            })
        }
        detCard.addView(sensRow)
        detCard.addView(spacer(dp(12)))

        detCard.addView(fieldLabel("Detect Objects"))
        detCard.addView(spacer(dp(6)))
        listOf("Person" to { editDetectPerson }, "Car" to { editDetectCar }, "Bike" to { editDetectBike }).forEachIndexed { idx, (label, getter) ->
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val lv = TextView(context).apply {
                text = label; textSize = 13f; setTextColor(theme.textColor())
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val sw2 = SwitchCompat(context).apply {
                isChecked = getter()
                setOnCheckedChangeListener { _, checked ->
                    when (idx) { 0 -> editDetectPerson = checked; 1 -> editDetectCar = checked; 2 -> editDetectBike = checked }
                }
            }
            row.addView(lv); row.addView(sw2)
            detCard.addView(row)
        }

        contentArea.addView(detCard)
        contentArea.addView(spacer(dp(12)))
        contentArea.addView(buildApplyButton())
    }

    // ─────────────────────────── RECORDING ────────────────────────────────

    private fun renderRecording() {
        val card = makeCard()
        card.addView(sectionLabel("Event Recording"))
        card.addView(spacer(dp(12)))

        card.addView(fieldLabel("Pre-record (seconds before event): $editPreRecord"))
        card.addView(spacer(dp(6)))
        val preRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(2, 5, 10, 15).forEach { sec ->
            val btn = makeSegmentBtn("${sec}s", editPreRecord == sec) { editPreRecord = sec; renderCurrentTab() }
            preRow.addView(btn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, dp(6), 0) })
        }
        card.addView(preRow)
        card.addView(spacer(dp(12)))

        card.addView(fieldLabel("Post-record (seconds after event): $editPostRecord"))
        card.addView(spacer(dp(6)))
        val postRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(5, 10, 15, 20, 30).forEach { sec ->
            val btn = makeSegmentBtn("${sec}s", editPostRecord == sec) { editPostRecord = sec; renderCurrentTab() }
            postRow.addView(btn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, dp(6), 0) })
        }
        card.addView(postRow)

        contentArea.addView(card)
        contentArea.addView(spacer(dp(12)))
        contentArea.addView(buildApplyButton())
    }

    // ─────────────────────────── STORAGE ─────────────────────────────────

    private fun renderStorage(storage: SurveillanceStorageSettings?) {
        val card = makeCard()
        card.addView(sectionLabel("Surveillance Storage"))
        card.addView(spacer(dp(12)))

        val sdAvail = storage?.sdCardAvailable ?: false
        card.addView(fieldLabel("Storage Location"))
        card.addView(spacer(dp(4)))
        val locRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val intBtn = makeSegmentBtn("Internal", editStorageType == "INTERNAL") {
            editStorageType = "INTERNAL"; renderCurrentTab()
        }
        locRow.addView(intBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, dp(8), 0) })
        val sdBtn = makeSegmentBtn("SD Card${if (!sdAvail) " (N/A)" else ""}", editStorageType == "SD_CARD") {
            if (sdAvail) { editStorageType = "SD_CARD"; renderCurrentTab() }
        }
        sdBtn.isEnabled = sdAvail; if (!sdAvail) sdBtn.alpha = 0.5f
        locRow.addView(sdBtn)
        card.addView(locRow)
        card.addView(spacer(dp(12)))

        // Storage limit slider (auto-delete oldest when reached) — restored from
        // the pre-native-migration web UI, which had this MB-cap control.
        // Cap the slider at the total physical capacity of the selected volume;
        // fall back to the daemon's configured max if the size is unknown (0).
        val totalMb = if (editStorageType == "SD_CARD") storageSdCardTotalMb else storageInternalTotalMb
        val daemonMax = if (editStorageType == "SD_CARD") storageMaxLimitMbSdCard else storageMaxLimitMb
        val maxMb = (if (totalMb > 0L) totalMb else daemonMax).coerceAtLeast(storageMinLimitMb + 100L)
        val minMb = storageMinLimitMb.coerceAtLeast(100L)
        editStorageLimitMb = editStorageLimitMb.coerceIn(minMb, maxMb)
        card.addView(fieldLabel("Storage Limit — auto-deletes oldest when reached"))
        card.addView(spacer(dp(4)))
        val limitValue = TextView(context).apply {
            text = formatMb(editStorageLimitMb); textSize = 14f
            setTextColor(theme.textColor())
        }
        card.addView(limitValue)
        val seek = android.widget.SeekBar(context).apply {
            max = (((maxMb - minMb) / 100L).toInt()).coerceAtLeast(1)
            progress = (((editStorageLimitMb - minMb) / 100L).toInt()).coerceIn(0, max)
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: android.widget.SeekBar, p: Int, fromUser: Boolean) {
                    editStorageLimitMb = (minMb + p.toLong() * 100L).coerceIn(minMb, maxMb)
                    limitValue.text = formatMb(editStorageLimitMb)
                }
                override fun onStartTrackingTouch(sb: android.widget.SeekBar) {}
                override fun onStopTrackingTouch(sb: android.widget.SeekBar) {}
            })
        }
        card.addView(seek)
        card.addView(spacer(dp(12)))

        if (storage != null) {
            val sizeMb = storage.surveillanceSize / (1024.0 * 1024.0)
            card.addView(infoRow("Storage Usage", "%.1f MB used / ${storage.limitMb} MB limit".format(sizeMb)))
            card.addView(spacer(dp(4)))
            card.addView(infoRow("Files", "${storage.surveillanceCount} events"))
            if (storage.path.isNotBlank()) {
                card.addView(spacer(dp(4)))
                card.addView(infoRow("Path", storage.path))
            }
        }

        contentArea.addView(card)
        contentArea.addView(spacer(dp(12)))
        contentArea.addView(buildApplyButton())

        if (sdAvail) {
            contentArea.addView(spacer(dp(12)))
            contentArea.addView(buildFormatCard())
        }

        contentArea.addView(spacer(dp(12)))
        contentArea.addView(buildSyncCard())
    }

    // ─────────────────────────── FORMAT ──────────────────────────────────

    private fun buildFormatCard(): View {
        val card = makeCard()
        card.background = GradientDrawable().apply {
            setColor(theme.surfaceColor())
            cornerRadius = dp(8).toFloat()
            setStroke(dp(1), theme.warningColor())
        }
        card.addView(sectionLabel("Format External Drive"))
        card.addView(spacer(dp(6)))
        card.addView(fieldLabel("Permanently erases ALL data on the SD card or USB drive."))
        card.addView(spacer(dp(12)))

        when {
            formatResultMessage != null -> {
                val isError = formatResultMessage!!.startsWith("Error") || formatResultMessage!!.contains("failed")
                card.addView(TextView(context).apply {
                    text = formatResultMessage
                    textSize = 13f
                    setTextColor(if (isError) theme.errorColor() else theme.accentColor())
                })
                card.addView(spacer(dp(8)))
                card.addView(TextView(context).apply {
                    text = "Dismiss"
                    textSize = 13f; gravity = Gravity.CENTER
                    setTextColor(mutedTextColor())
                    setPadding(0, dp(4), 0, dp(4))
                    setOnClickListener { formatResultMessage = null; renderCurrentTab() }
                })
            }
            formatRunning -> {
                card.addView(centeredText("Formatting… please wait", 13f))
            }
            formatConfirmPending -> {
                card.addView(TextView(context).apply {
                    text = "Tap again — ALL data will be ERASED"
                    textSize = 13f; gravity = Gravity.CENTER
                    setTextColor(theme.onAccentColor())
                    background = pill(theme.errorColor())
                    setPadding(dp(16), dp(12), dp(16), dp(12))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    setOnClickListener { executeFormat() }
                })
                card.addView(spacer(dp(8)))
                card.addView(TextView(context).apply {
                    text = "Cancel"
                    textSize = 13f; gravity = Gravity.CENTER
                    setTextColor(mutedTextColor())
                    setPadding(0, dp(6), 0, dp(2))
                    setOnClickListener { formatConfirmPending = false; renderCurrentTab() }
                })
            }
            else -> {
                card.addView(TextView(context).apply {
                    text = "Format SD Card / USB"
                    textSize = 13f; gravity = Gravity.CENTER
                    setTextColor(theme.onAccentColor())
                    background = pill(theme.warningColor())
                    setPadding(dp(16), dp(12), dp(16), dp(12))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    setOnClickListener { formatConfirmPending = true; renderCurrentTab() }
                })
            }
        }
        return card
    }

    private fun executeFormat() {
        formatConfirmPending = false
        formatRunning = true
        renderCurrentTab()
        Thread({
            val volumes = client.listFormattableVolumes()
            val first = volumes.firstOrNull()
            if (first == null) {
                root.post {
                    formatRunning = false
                    formatResultMessage = "Error: No removable drive found"
                    renderCurrentTab()
                }
                return@Thread
            }
            val result = client.formatVolume(first.volumeId)
            root.post {
                formatRunning = false
                formatResultMessage = if (result.success) {
                    "Formatted successfully. New path: ${result.mountPath ?: "unknown"}"
                } else {
                    "Error: ${result.message}"
                }
                loadData()
            }
        }, "SurvFormatDrive").apply { isDaemon = true; start() }
    }

    // ─────────────────────────── SYNC ────────────────────────────────────

    private fun buildSyncCard(): View {
        val card = makeCard()
        card.addView(sectionLabel("Database Catalog"))
        card.addView(spacer(dp(6)))
        card.addView(fieldLabel("Reconcile the surveillance index with files on disk."))
        card.addView(spacer(dp(12)))
        when {
            syncResultMessage != null -> {
                val isError = !syncResultMessage!!.startsWith("Synced")
                card.addView(TextView(context).apply {
                    text = syncResultMessage
                    textSize = 13f
                    setTextColor(if (isError) theme.errorColor() else theme.accentColor())
                })
                card.addView(spacer(dp(8)))
                card.addView(TextView(context).apply {
                    text = "Dismiss"
                    textSize = 13f; gravity = Gravity.CENTER
                    setTextColor(mutedTextColor())
                    setPadding(0, dp(4), 0, dp(4))
                    setOnClickListener { syncResultMessage = null; renderCurrentTab() }
                })
            }
            syncRunning -> card.addView(centeredText("Syncing…", 13f))
            else -> card.addView(TextView(context).apply {
                text = "Sync Database"
                textSize = 13f; gravity = Gravity.CENTER
                setTextColor(theme.onAccentColor())
                background = pill(accentColor())
                setPadding(dp(16), dp(12), dp(16), dp(12))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                setOnClickListener { executeSyncDatabase() }
            })
        }
        return card
    }

    private fun executeSyncDatabase() {
        syncRunning = true
        renderCurrentTab()
        Thread({
            val result = client.syncCatalog()
            root.post {
                syncRunning = false
                syncResultMessage = result.message
                renderCurrentTab()
            }
        }, "SurvDbSync").apply { isDaemon = true; start() }
    }

    // ─────────────────────────── ADVANCED ─────────────────────────────────

    private fun renderAdvanced() {
        // Camera selection
        val camCard = makeCard()
        camCard.addView(sectionLabel("Camera Selection"))
        camCard.addView(spacer(dp(10)))
        listOf("Front" to { editCameraFront }, "Right" to { editCameraRight }, "Rear" to { editCameraRear }, "Left" to { editCameraLeft }).forEachIndexed { idx, (label, getter) ->
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(2), 0, dp(2)) }
            val lv = TextView(context).apply {
                text = label; textSize = 13f; setTextColor(theme.textColor())
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val sw = SwitchCompat(context).apply {
                isChecked = getter()
                setOnCheckedChangeListener { _, checked ->
                    when (idx) { 0 -> editCameraFront = checked; 1 -> editCameraRight = checked; 2 -> editCameraRear = checked; 3 -> editCameraLeft = checked }
                }
            }
            row.addView(lv); row.addView(sw)
            camCard.addView(row)
        }
        contentArea.addView(camCard)
        contentArea.addView(spacer(dp(12)))

        // AI & Deterrent
        val aiCard = makeCard()
        aiCard.addView(sectionLabel("AI & Deterrent"))
        aiCard.addView(spacer(dp(10)))

        val aiRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val aiLabel = TextView(context).apply {
            text = "AI Detection"; textSize = 14f; setTextColor(theme.textColor())
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val aiSw = SwitchCompat(context).apply {
            isChecked = editAiEnabled
            setOnCheckedChangeListener { _, checked -> editAiEnabled = checked }
        }
        aiRow.addView(aiLabel); aiRow.addView(aiSw)
        aiCard.addView(aiRow)
        aiCard.addView(spacer(dp(10)))

        val nightRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val nightLabel = TextView(context).apply {
            text = "Night Mode"; textSize = 14f; setTextColor(theme.textColor())
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val nightSw = SwitchCompat(context).apply {
            isChecked = editNightMode
            setOnCheckedChangeListener { _, checked -> editNightMode = checked }
        }
        nightRow.addView(nightLabel); nightRow.addView(nightSw)
        aiCard.addView(nightRow)
        aiCard.addView(spacer(dp(10)))

        aiCard.addView(fieldLabel("Deterrent Action"))
        aiCard.addView(spacer(dp(6)))
        val deterRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("silent", "horn", "flash").forEach { action ->
            val btn = makeSegmentBtn(action.replaceFirstChar { it.uppercaseChar() }, editDeterrent == action) {
                editDeterrent = action; renderCurrentTab()
            }
            deterRow.addView(btn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, dp(6), 0) })
        }
        aiCard.addView(deterRow)
        contentArea.addView(aiCard)
        contentArea.addView(spacer(dp(12)))
        contentArea.addView(buildApplyButton())
    }

    // ─────────────────────────── APPLY ────────────────────────────────────

    private fun buildApplyButton(): TextView = TextView(context).apply {
        text = "Apply Changes"; textSize = 14f; gravity = Gravity.CENTER
        setTextColor(theme.onAccentColor()); background = pill(accentColor())
        setPadding(dp(16), dp(12), dp(16), dp(12))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        setOnClickListener { applyChanges() }
    }

    private fun applyChanges() {
        Thread({
            val config = loadedState.let { state ->
                if (state is SurveillanceSettingsLoadState.Loaded) state.settings.config else null
            } ?: SurveillanceConfig(
                editEnabled, editPreset, editSensitivity,
                editDetectPerson, editDetectCar, editDetectBike,
                editPreRecord, editPostRecord, editNightMode, editAiEnabled, 0.4f,
                editCameraFront, editCameraRight, editCameraRear, editCameraLeft,
                editDeterrent, editDeterrentCooldown
            )
            val updated = config.copy(
                enabled = editEnabled,
                distancePreset = editPreset,
                sensitivityLevel = editSensitivity,
                detectPerson = editDetectPerson, detectCar = editDetectCar, detectBike = editDetectBike,
                preRecordSeconds = editPreRecord, postRecordSeconds = editPostRecord,
                nightMode = editNightMode, aiEnabled = editAiEnabled,
                cameraFront = editCameraFront, cameraRight = editCameraRight,
                cameraRear = editCameraRear, cameraLeft = editCameraLeft,
                deterrentAction = editDeterrent, deterrentCooldownSeconds = editDeterrentCooldown,
            )
            val result = when (activeTab) {
                SurveillanceSettingsTab.STORAGE -> client.saveStorage(editStorageType, editStorageLimitMb)
                else -> client.saveConfig(updated)
            }
            root.post {
                if (!result.ok) {
                    val msg = result.error?.takeIf { it.isNotEmpty() } ?: "Save failed"
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                }
                loadData()
            }
        }, "SurvSettingsSave").apply { isDaemon = true; start() }
    }

    // ─────────────────────────── HELPERS ─────────────────────────────────

    private fun isDark()            = theme.isDark()
    private fun bgColor()           = theme.bgColor()
    private fun surfaceColor()      = theme.surfaceColor()
    private fun accentColor()       = theme.accentColor()
    private fun inactivePillColor() = theme.pillBgColor()
    private fun mutedTextColor() = if (isDark()) theme.mutedColor() else theme.mutedColor()

    private fun pill(color: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(20).toFloat() }
    private fun makeCard() = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply { setColor(surfaceColor()); cornerRadius = dp(8).toFloat() }
        setPadding(dp(16), dp(12), dp(16), dp(12))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }
    private fun makeSegmentBtn(label: String, selected: Boolean, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = label; textSize = 12f; gravity = Gravity.CENTER
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = pill(if (selected) accentColor() else inactivePillColor())
            setTextColor(if (selected) theme.onAccentColor() else mutedTextColor())
            setOnClickListener { onClick() }
        }
    private fun makeActionBtn(label: String, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = label; textSize = 13f; gravity = Gravity.CENTER
            setTextColor(theme.onAccentColor()); background = pill(theme.accentColor())
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setOnClickListener { onClick() }
        }
    private fun sectionLabel(text: String) = TextView(context).apply {
        this.text = text; setTextAppearance(R.style.TextAppearance_BladeWatch_LabelLarge); setTextColor(theme.textColor())
    }
    private fun fieldLabel(text: String) = TextView(context).apply { this.text = text; textSize = 12f; setTextColor(mutedTextColor()) }
    private fun infoRow(label: String, value: String): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(TextView(context).apply { text = label; textSize = 13f; setTextColor(mutedTextColor()); layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) })
        addView(TextView(context).apply { text = value; textSize = 13f; setTextColor(theme.textColor()) })
    }
    private fun centeredText(text: String, size: Float) = TextView(context).apply {
        this.text = text; textSize = size; gravity = Gravity.CENTER; setTextColor(mutedTextColor())
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(32) }
    }
    private fun spacer(h: Int) = View(context).apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h) }
    private fun dp(v: Int) = (v * context.resources.displayMetrics.density + 0.5f).toInt()

    private fun formatMb(mb: Long): String =
        if (mb >= 1024) String.format("%.1f GB", mb / 1024.0) else "$mb MB"
}
