package net.bladewatch.app.ui.fragment.vehicle

import android.content.Context
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import net.bladewatch.app.R

class VehicleController(private val context: Context) {

    companion object {
        // Same presets as vehicle-control.js colorPresets (sRGB hex)
        val COLOR_PRESETS = listOf(
            "#E8E8EC" to "Aurora White",
            "#1A1A1E" to "Cosmos Black",
            "#1E3A5F" to "Atlantic Blue",
            "#1B4D3E" to "Deepsea Green",
            "#C8102E" to "Cherry Red",
            "#5C5C66" to "Storm Grey",
        )
        const val DEFAULT_COLOR = "#E8E8EC"
        // Seal 5 DM-i — shares destroyer.glb with Destroyer 05 in the manifest.
        const val DEFAULT_MODEL_ID = "seal5-dmi-dynamic"
        const val FALLBACK_MODEL_FILE = "destroyer.glb"
    }

    // ─── State ───────────────────────────────────────────────────────────────

    private var vehicleState = VehicleState()
    private var currentTab = VehicleTab.CLIMATE
    private val client = VehicleClient()
    private val factory = VehicleViewFactory(context)

    // Seat local state — needed for full-state sends on every seat call
    private var driverHeat = 0; private var driverVent = 0
    private var passengerHeat = 0; private var passengerVent = 0

    // Climate local state for optimistic UI and max-cooling capture
    private var acOn = false; private var setpointC = 22; private var fanLevel = 3; private var maxCooling = false

    private val running = AtomicBoolean(false)
    private val appearanceLoaded = AtomicBoolean(false)
    private val inFlight = ConcurrentHashMap<String, Boolean>()
    private var pollExecutor: ScheduledExecutorService? = null
    private val lastClick = mutableMapOf<String, Long>()

    // In-place tab updater — populated each time renderTabContent() builds a new panel
    private var currentPanelUpdater: ((PanelContext) -> Unit)? = null

    // Hero region with tyre overlay
    private lateinit var tyreOverlay: TyreOverlay

    // Stale-state banner — visible when showing cached state before first live poll
    private var staleBanner: TextView? = null

    // Appearance state — model list and current selection
    private var manifestModels: List<ModelEntry> = emptyList()
    private var selectedModelId: String = DEFAULT_MODEL_ID
    private var selectedColor: String = DEFAULT_COLOR
    private var colorSwatchViews: List<View> = emptyList()
    private var modelNameView: TextView? = null

    // Generation token: incremented on each startPolling so a stale executor can self-cancel
    private val pollGeneration = AtomicInteger(0)

    // Whether the current state is loaded from cache (not yet confirmed by a live poll)
    private var isStale = false

    // ─── Views ───────────────────────────────────────────────────────────────

    private val root = FrameLayout(context)
    private lateinit var outerLayout: LinearLayout
    private lateinit var statusArea: LinearLayout
    private lateinit var contentScroll: ScrollView
    private lateinit var contentArea: LinearLayout
    private lateinit var tabBar: LinearLayout

    // Status card view refs
    private var lockStatusDot: View? = null
    private var lockStatusText: TextView? = null
    private var batteryText: TextView? = null
    private var rangeText: TextView? = null

    init { buildView() }

    val view: View get() = root

    fun onResume() {
        if (running.compareAndSet(false, true)) {
            startPolling()
        }
        if (::tyreOverlay.isInitialized) tyreOverlay.heroResume()
        if (appearanceLoaded.compareAndSet(false, true)) {
            startAppearanceLoad()
        }
    }

    fun onPause() {
        running.set(false)
        pollGeneration.incrementAndGet()
        pollExecutor?.shutdownNow()
        pollExecutor = null
        if (::tyreOverlay.isInitialized) {
            tyreOverlay.cancelAnimations()
            tyreOverlay.heroPause()
        }
    }

    fun onDestroy() {
        onPause()
        appearanceLoaded.set(false)
        if (::tyreOverlay.isInitialized) tyreOverlay.heroDestroy()
    }

    fun onConfigurationChanged() {
        rebuildAll()
    }

    // ─── Build ───────────────────────────────────────────────────────────────

    private fun buildView() {
        // Light studio backdrop behind the full-bleed 3D car (the WebView canvas
        // is transparent, so this gradient is what shows around the model).
        root.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(factory.glassBgTop(), factory.glassBgBottom())
        )
        root.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

        // ── Layer 0: full-bleed 3D car + floating glass tyre cards ──────────────
        tyreOverlay = TyreOverlay(context, factory)
        root.addView(tyreOverlay.view, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // ── Layer 1: glass control overlay ──────────────────────────────────────
        outerLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            clipChildren = false
            clipToPadding = false
        }

        statusArea = buildStatusCard()
        outerLayout.addView(statusArea)

        staleBanner = TextView(context).apply {
            text = context.getString(R.string.vehicle_stale_connecting)
            textSize = 11f; gravity = Gravity.CENTER
            setTextColor(factory.glassMuted())
            setPadding(factory.dp(8), factory.dp(4), factory.dp(8), factory.dp(4))
            visibility = View.GONE
        }
        outerLayout.addView(staleBanner)

        // Transparent spacer — the 3D car fills whatever height the controls
        // panel doesn't need (weight 1 absorbs the slack), so the car is as big
        // as possible while the panel sizes to its content.
        outerLayout.addView(View(context), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // Bottom glass panel: appearance bar + tabs + tab content. Wraps to its
        // content height so the compact control grids show fully without scrolling.
        val bottomPanel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = factory.glassPanelRadii(20, 20, 0, 0)
            elevation = factory.dp(8).toFloat()
            clipToPadding = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        bottomPanel.addView(buildAppearanceBar())
        bottomPanel.addView(buildTabBar())

        // Scroll is a safety net for very tall tabs; compact grids normally fit
        // without it. Capped so it can never grow taller than the car area.
        contentScroll = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            isFillViewport = true
        }
        contentArea = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(factory.dp(12), factory.dp(8), factory.dp(12), factory.dp(12))
        }
        contentScroll.addView(contentArea)
        bottomPanel.addView(contentScroll)

        outerLayout.addView(bottomPanel)
        root.addView(outerLayout)
    }

    private fun buildStatusCard(): LinearLayout {
        // Top overlay row: a lock glass-pill on the left, a charge/range glass-pill
        // on the right, with the 3D car visible in the gap between them.
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(factory.dp(14), factory.dp(12), factory.dp(14), factory.dp(4))
            clipChildren = false
        }

        // Lock pill
        val lockPill = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = factory.glassPanel(16)
            elevation = factory.dp(3).toFloat()
            setPadding(factory.dp(12), factory.dp(7), factory.dp(14), factory.dp(7))
        }
        val dot = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(factory.dp(10), factory.dp(10)).apply { marginEnd = factory.dp(7) }
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.GRAY) }
        }
        val lockTv = TextView(context).apply { text = "—"; textSize = 13f; setTextColor(factory.glassText()) }
        lockStatusDot = dot; lockStatusText = lockTv
        lockPill.addView(dot); lockPill.addView(lockTv)
        row.addView(lockPill)

        // Spacer — car shows between the pills
        row.addView(View(context), LinearLayout.LayoutParams(0, factory.dp(1), 1f))

        // Charge / range pill
        val battPill = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            background = factory.glassPanel(16)
            elevation = factory.dp(3).toFloat()
            setPadding(factory.dp(14), factory.dp(6), factory.dp(14), factory.dp(6))
        }
        val battTv = TextView(context).apply {
            text = context.getString(R.string.vehicle_status_charge_unknown)
            textSize = 15f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.END
            setTextColor(factory.glassText())
        }
        val rangeTv = TextView(context).apply {
            text = context.getString(R.string.vehicle_status_range_unknown)
            textSize = 11f; gravity = Gravity.END; setTextColor(factory.glassMuted())
        }
        batteryText = battTv; rangeText = rangeTv
        battPill.addView(battTv); battPill.addView(rangeTv)
        row.addView(battPill)

        return row
    }

    private fun buildTabBar(): HorizontalScrollView {
        val scroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
        }
        tabBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(factory.dp(8), factory.dp(8), factory.dp(8), factory.dp(8))
        }
        for (tab in VehicleTab.entries) {
            tabBar.addView(buildTabPill(tab))
        }
        scroll.addView(tabBar)
        return scroll
    }

    private fun buildTabPill(tab: VehicleTab): TextView {
        val selected = tab == currentTab
        return TextView(context).apply {
            text = context.getString(tab.labelRes)
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(factory.dp(14), factory.dp(7), factory.dp(14), factory.dp(7))
            minimumHeight = factory.dp(44)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = factory.dp(6) }
            background = if (selected) GradientDrawable().apply {
                cornerRadius = factory.dp(16).toFloat(); setColor(factory.accentColor())
            } else factory.glassChip(16)
            setTextColor(if (selected) Color.WHITE else factory.glassText())
            setOnClickListener {
                currentTab = tab
                rebuildTabBar()
                renderTabContent()
            }
        }
    }

    // ─── Appearance bar ──────────────────────────────────────────────────────

    private fun buildAppearanceBar(): LinearLayout {
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(factory.dp(14), factory.dp(10), factory.dp(14), factory.dp(8))
        }

        // Color swatches
        val swatchRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val swatches = COLOR_PRESETS.map { (hex, name) ->
            buildColorSwatch(hex, name, hex == selectedColor)
        }
        colorSwatchViews = swatches
        swatches.forEach { v ->
            v.layoutParams = LinearLayout.LayoutParams(factory.dp(28), factory.dp(28)).apply { marginEnd = factory.dp(6) }
            swatchRow.addView(v)
        }
        // Custom color "+" button
        val customBtn = TextView(context).apply {
            text = "+"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(factory.glassText())
            layoutParams = LinearLayout.LayoutParams(factory.dp(28), factory.dp(28)).apply { marginEnd = factory.dp(6) }
            background = factory.glassChip(14).apply { shape = GradientDrawable.OVAL }
            contentDescription = context.getString(R.string.vehicle_appearance_custom_color)
            isFocusable = true; isClickable = true
            setOnClickListener { showCustomColorPicker() }
        }
        swatchRow.addView(customBtn)
        bar.addView(swatchRow)

        // Model name label (tappable when multiple models available)
        val modelTv = TextView(context).apply {
            text = "BYD Seal 5 DM-i Dynamic"   // default; replaced by manifest name on fetch
            textSize = 12f
            setTextColor(factory.glassMuted())
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        modelNameView = modelTv
        bar.addView(modelTv)

        return bar
    }

    private fun buildColorSwatch(hex: String, name: String, selected: Boolean): View {
        val swatch = View(context).apply {
            contentDescription = name
            isFocusable = true
            isClickable = true
        }
        applySwatchDrawable(swatch, hex, selected)
        swatch.setOnClickListener { onColorSelected(hex) }
        return swatch
    }

    private fun applySwatchDrawable(view: View, hex: String, selected: Boolean) {
        val color = try { Color.parseColor(hex) } catch (_: Throwable) { Color.WHITE }
        val fill = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
        if (selected) {
            val ring = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setStroke(factory.dp(2), factory.accentColor())
                setColor(Color.TRANSPARENT)
            }
            val layer = LayerDrawable(arrayOf(fill, ring))
            layer.setLayerInset(0, factory.dp(2), factory.dp(2), factory.dp(2), factory.dp(2))
            view.background = layer
        } else {
            view.background = fill
        }
    }

    private fun onColorSelected(hex: String) {
        if (hex == selectedColor) return
        selectedColor = hex
        // Refresh swatch selection rings
        colorSwatchViews.forEachIndexed { i, v ->
            applySwatchDrawable(v, COLOR_PRESETS[i].first, COLOR_PRESETS[i].first == hex)
        }
        tyreOverlay.heroApplyColor(hex)
        Thread({
            client.saveAppearance(null, hex)
        }, "VehicleAppearanceSave").apply { isDaemon = true; start() }
    }

    private fun startAppearanceLoad() {
        Thread({
            val appearance = client.fetchAppearance()
            val manifest = client.fetchManifest()
            root.post {
                manifestModels = manifest
                if (appearance != null) {
                    if (appearance.color.isNotBlank()) selectedColor = appearance.color
                    if (appearance.modelId.isNotBlank()) selectedModelId = appearance.modelId
                }
                // Refresh color swatch selection indicators
                colorSwatchViews.forEachIndexed { i, v ->
                    applySwatchDrawable(v, COLOR_PRESETS[i].first, COLOR_PRESETS[i].first == selectedColor)
                }
                // Resolve the saved selection to its GLB via the manifest `file`
                // field (several ids share one file, e.g. seal5-dmi-* → destroyer.glb).
                // heroLoadModel dedupes against the already-loaded default, so
                // this is safe to call unconditionally.
                val glbFile = manifest.firstOrNull { it.id == selectedModelId }?.file
                    ?: TyreOverlay.DEFAULT_FALLBACK_GLB
                tyreOverlay.heroLoadModel(glbFile)
                if (selectedColor.isNotBlank()) tyreOverlay.heroApplyColor(selectedColor)
                // Update model name label
                val entry = manifest.firstOrNull { it.id == selectedModelId }
                    ?: manifest.firstOrNull()
                if (entry != null) {
                    modelNameView?.text = entry.name
                    if (manifest.size > 1) {
                        modelNameView?.setOnClickListener { showModelPicker() }
                        modelNameView?.setTextColor(factory.accentColor())
                    }
                }
            }
        }, "VehicleAppearanceLoad").apply { isDaemon = true; start() }
    }

    private fun showModelPicker() {
        if (manifestModels.isEmpty()) return
        val names = manifestModels.map { it.name }.toTypedArray()
        val currentIdx = manifestModels.indexOfFirst { it.id == selectedModelId }.coerceAtLeast(0)
        AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.vehicle_appearance_model_title))
            .setSingleChoiceItems(names, currentIdx) { dialog, which ->
                dialog.dismiss()
                val chosen = manifestModels[which]
                if (chosen.id == selectedModelId) return@setSingleChoiceItems
                selectedModelId = chosen.id
                modelNameView?.text = chosen.name
                tyreOverlay.heroLoadModel(chosen.file)
                Thread({
                    client.saveAppearance(chosen.id, null)
                }, "VehicleModelSave").apply { isDaemon = true; start() }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showCustomColorPicker() {
        // Parse current color into R/G/B for initial slider positions
        val initColor = try { Color.parseColor(selectedColor) } catch (_: Throwable) { Color.WHITE }
        var r = Color.red(initColor)
        var g = Color.green(initColor)
        var b = Color.blue(initColor)

        val dp = factory::dp
        val previewSize = dp(40)

        val preview = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(previewSize, previewSize).apply {
                bottomMargin = dp(8)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.rgb(r, g, b))
            }
        }

        fun updatePreview() {
            (preview.background as? GradientDrawable)?.setColor(Color.rgb(r, g, b))
        }

        fun makeSliderRow(label: String, init: Int, onChanged: (Int) -> Unit): LinearLayout {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(6) }
            }
            row.addView(TextView(context).apply {
                text = label; textSize = 12f; setTextColor(factory.textColor())
                layoutParams = LinearLayout.LayoutParams(dp(16), ViewGroup.LayoutParams.WRAP_CONTENT)
            })
            val seek = SeekBar(context).apply {
                max = 255; progress = init
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar, v: Int, fromUser: Boolean) {
                        onChanged(v); updatePreview()
                    }
                    override fun onStartTrackingTouch(sb: SeekBar) {}
                    override fun onStopTrackingTouch(sb: SeekBar) {}
                })
            }
            row.addView(seek)
            return row
        }

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(8))
        }
        container.addView(preview)
        container.addView(makeSliderRow("R", r) { r = it })
        container.addView(makeSliderRow("G", g) { g = it })
        container.addView(makeSliderRow("B", b) { b = it })

        AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.vehicle_appearance_custom_color))
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val hex = String.format("#%02X%02X%02X", r, g, b)
                onColorSelected(hex)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ─── Rebuild helpers ─────────────────────────────────────────────────────

    private fun rebuildAll() {
        if (::tyreOverlay.isInitialized) {
            tyreOverlay.cancelAnimations()
            // buildView() creates a fresh TyreOverlay + Filament engine; tear
            // down the old one first so GL contexts don't accumulate.
            tyreOverlay.heroDestroy()
        }
        root.removeAllViews()
        lockStatusDot = null; lockStatusText = null; batteryText = null; rangeText = null
        staleBanner = null; colorSwatchViews = emptyList(); modelNameView = null
        currentPanelUpdater = null
        appearanceLoaded.set(false)
        buildView()
        applyStateToViews()
        if (running.get()) {
            tyreOverlay.heroResume()
            if (appearanceLoaded.compareAndSet(false, true)) startAppearanceLoad()
        }
    }

    private fun rebuildTabBar() {
        tabBar.removeAllViews()
        for (tab in VehicleTab.entries) {
            if (tab == VehicleTab.SEATS && !vehicleState.capabilities.seats.anyAvailable()) continue
            tabBar.addView(buildTabPill(tab))
        }
    }

    /** Full tree rebuild for the current tab. Called on tab switch, action start/end, and config change. */
    private fun renderTabContent() {
        contentArea.removeAllViews()
        val ctx = makePanelContext()
        val result = when (currentTab) {
            VehicleTab.CLIMATE  -> buildClimateTab(ctx)
            VehicleTab.SEATS    -> buildSeatsTab(ctx)
            VehicleTab.WINDOWS  -> buildWindowsTab(ctx)
        }
        contentArea.addView(result.view)
        currentPanelUpdater = result.update
    }

    /** In-place update of the current tab — does NOT rebuild the view tree. Called from the poll path. */
    private fun updateTabContent() {
        currentPanelUpdater?.invoke(makePanelContext())
    }

    private fun makePanelContext() = PanelContext(
        factory = factory,
        state = vehicleState,
        acOn = acOn, setpointC = setpointC, fanLevel = fanLevel, maxCooling = maxCooling,
        driverHeat = driverHeat, driverVent = driverVent,
        passengerHeat = passengerHeat, passengerVent = passengerVent,
        isPending = { key -> inFlight[key] == true },
        debounce = ::debounce,
        post = { fn -> root.post(fn) },
        toast = ::toast,
        refresh = ::renderTabContent,
        doAction = ::doVehicleAction,
        updateState = { s -> vehicleState = s },
        updateClimate = { ao, t, f, mc -> acOn = ao; setpointC = t; fanLevel = f; maxCooling = mc },
        updateSeats = { dH, dV, pH, pV -> driverHeat = dH; driverVent = dV; passengerHeat = pH; passengerVent = pV },
        client = client,
    )

    // ─── State application ───────────────────────────────────────────────────

    private fun applyStateToViews() {
        staleBanner?.visibility = if (isStale) View.VISIBLE else View.GONE
        val state = vehicleState
        // Sync seat local state from polled data
        driverHeat = state.seats.heat.getOrElse(0) { 0 }
        passengerHeat = state.seats.heat.getOrElse(1) { 0 }
        driverVent = state.seats.cool.getOrElse(0) { 0 }
        passengerVent = state.seats.cool.getOrElse(1) { 0 }
        // Sync climate local state
        acOn = state.climate.acOn; setpointC = state.climate.setpointC; fanLevel = state.climate.fanLevel; maxCooling = state.climate.maxCooling

        // Update status card
        val lockVal = state.doors.overall
        val (dotColor, lockLabel) = when {
            !state.loaded -> Pair(Color.GRAY, "—")
            lockVal == 1  -> Pair(Color.parseColor("#4CAF50"), context.getString(R.string.vehicle_locked))
            lockVal == 2  -> Pair(Color.parseColor("#FF9800"), context.getString(R.string.vehicle_unlocked))
            else          -> Pair(Color.GRAY, "—")
        }
        (lockStatusDot?.background as? GradientDrawable)?.setColor(dotColor)
        lockStatusText?.text = lockLabel

        if (state.battery.soc > 0) {
            batteryText?.text = context.getString(R.string.vehicle_status_charge_fmt, state.battery.soc)
            rangeText?.text = context.getString(R.string.vehicle_status_range_fmt, state.battery.rangeKm)
        } else {
            batteryText?.text = context.getString(R.string.vehicle_status_charge_unknown)
            rangeText?.text = context.getString(R.string.vehicle_status_range_unknown)
        }

        // Update tyre overlay in place
        tyreOverlay.update(state.tyres)

        // Rebuild tab bar if seat capability changes visibility
        rebuildTabBar()

        // In-place update of current tab — never rebuilds the view tree
        if (currentPanelUpdater != null) {
            updateTabContent()
        } else {
            // First paint: do a full build
            renderTabContent()
        }
    }

    // ─── Polling ─────────────────────────────────────────────────────────────

    private fun startPolling() {
        val myGen = pollGeneration.incrementAndGet()
        Thread({
            // Instant first paint: apply cached state before any network call.
            VehicleStateCache.load(context)?.let { cached ->
                root.post {
                    if (pollGeneration.get() == myGen) {
                        vehicleState = cached
                        isStale = true
                        applyStateToViews()
                    }
                }
            }

            // Initial charge cap probe
            try {
                client.fetchChargeCap()?.let { cap ->
                    root.post { vehicleState = vehicleState.copy(chargeCap = cap) }
                }
            } catch (_: Exception) { /* non-fatal; the cap tab shows unknowns */ }

            if (pollGeneration.get() != myGen) return@Thread

            val sched = Executors.newSingleThreadScheduledExecutor()
            pollExecutor = sched
            var failCount = 0
            sched.scheduleAtFixedRate({
                if (!running.get() || pollGeneration.get() != myGen) {
                    sched.shutdownNow()
                    return@scheduleAtFixedRate
                }
                try {
                    val state = client.fetchState()
                    if (state == null) {
                        failCount++
                        if (failCount >= 3) root.post {
                            if (running.get()) showErrorState(context.getString(R.string.vehicle_data_unavailable))
                        }
                        return@scheduleAtFixedRate
                    }
                    failCount = 0
                    val liveState = state.copy(chargeCap = vehicleState.chargeCap, loaded = true)
                    VehicleStateCache.save(context, liveState)
                    root.post {
                        if (running.get() && pollGeneration.get() == myGen) {
                            vehicleState = liveState
                            isStale = false
                            applyStateToViews()
                        }
                    }
                } catch (e: Exception) {
                    failCount++
                    android.util.Log.w("BladeWatch", "Poll tick threw: ${e.javaClass.simpleName}: ${e.message}")
                    if (failCount >= 3) root.post {
                        if (running.get()) showErrorState(context.getString(R.string.vehicle_data_unavailable))
                    }
                }
            }, 0, 3, TimeUnit.SECONDS)
        }, "VehiclePollInit").apply { isDaemon = true; start() }
    }

    private fun showErrorState(message: String) {
        (lockStatusDot?.background as? GradientDrawable)?.setColor(Color.GRAY)
        lockStatusText?.text = message
    }

    // ─── Action helper ───────────────────────────────────────────────────────

    private fun doVehicleAction(key: String, flashTarget: View?, action: () -> VehicleCommandResult) {
        if (!debounce(key)) return
        if (inFlight[key] == true) return
        inFlight[key] = true
        updateTabContent()  // in-place: show pending state without rebuilding
        Thread({
            val result = action()
            inFlight.remove(key)
            root.post {
                if (result.ok) {
                    flashTarget?.let { flashSuccess(it) }
                } else {
                    val msg = result.message ?: result.outcome ?: context.getString(R.string.vehicle_action_failed)
                    toast(msg)
                }
                renderTabContent()  // full rebuild after action completes to refresh click handlers
            }
        }, "VehicleAction-$key").apply { isDaemon = true; start() }
    }

    private fun flashSuccess(view: View) {
        view.animate()
            .alpha(0.4f).setDuration(120)
            .withEndAction { view.animate().alpha(1.0f).setDuration(180).start() }
            .start()
    }

    private fun debounce(key: String): Boolean {
        val now = System.currentTimeMillis()
        val last = lastClick[key] ?: 0L
        if (now - last < 600L) return false
        lastClick[key] = now
        return true
    }

    private fun toast(msg: String) {
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }
}
