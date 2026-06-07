package net.bladewatch.app.ui.fragment.trips

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.widget.SwitchCompat
import android.widget.TextView
import androidx.appcompat.app.AppCompatDelegate
import net.bladewatch.app.ui.util.PreferencesManager
import java.util.Locale

class TripsController(private val context: Context) {

    private val root = FrameLayout(context)
    private val client = TripsClient()

    private var activeTab = TripsTab.TRIPS
    private var activeFilter = TripsDaysFilter.SEVEN

    // Tab bar views
    private lateinit var tripsTabBtn: TextView
    private lateinit var statsTabBtn: TextView
    private lateinit var storageTabBtn: TextView

    // Content pane
    private lateinit var contentScroll: ScrollView
    private lateinit var contentArea: LinearLayout

    // Loaded state cache
    private var loadedState: TripsLoadState? = null

    // Storage tab – mutable controls
    private var analyticsSwitch: SwitchCompat? = null
    private var rateInput: android.widget.EditText? = null
    private var currencyInput: android.widget.EditText? = null
    private var distanceUnitKmBtn: TextView? = null
    private var distanceUnitMiBtn: TextView? = null
    private var storageInternalBtn: TextView? = null
    private var storageSdBtn: TextView? = null

    // Working config for storage tab edits
    private var editConfig: TripsConfig? = null
    private var editStorage: TripsStorage? = null

    // Trip detail overlay (shown on top of the list when a row is tapped)
    private var detailController: TripDetailController? = null

    /**
     * Notified when the detail overlay opens (true) or closes (false) so the
     * host fragment can enable/disable its back-press interception.
     */
    var onDetailVisibilityChanged: ((Boolean) -> Unit)? = null

    // Selected values for storage tab (tracked explicitly to avoid GradientDrawable constantState comparison)
    private var selectedDistUnit = "km"
    private var selectedStorageType = "INTERNAL"
    private var loadedOnce = false

    // Sync catalog state
    private var syncRunning = false
    private var syncResultMessage: String? = null

    init {
        buildView()
        loadData()
    }

    val view: View get() = root

    fun onResume() {
        detailController?.onResume()
        if (loadedOnce && detailController == null) loadData()
    }
    fun onPause() { detailController?.onPause() }
    fun onDestroy() {
        detailController?.onDestroy()
        detailController = null
    }

    fun onConfigurationChanged() {
        applyTheme()
        renderCurrentTab()
        detailController?.onConfigurationChanged()
    }

    /** Returns true if a back press was consumed (detail overlay closed). */
    fun onBackPressed(): Boolean {
        if (detailController != null) {
            hideDetail()
            return true
        }
        return false
    }

    // ──────────────────────────────── BUILD ────────────────────────────────

    private fun buildView() {
        val isDark = isDark()
        val bg = if (isDark) Color.parseColor("#121212") else Color.parseColor("#F5F5F5")
        root.setBackgroundColor(bg)
        root.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

        val outer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        // Tab bar at bottom
        val tabBar = buildTabBar()

        // Content area
        contentScroll = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            isFillViewport = true
        }
        contentArea = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        contentScroll.addView(contentArea)

        outer.addView(contentScroll)
        outer.addView(tabBar)
        root.addView(outer)
    }

    private fun buildTabBar(): LinearLayout {
        val isDark = isDark()
        val tabBg = if (isDark) Color.parseColor("#1E1E1E") else Color.WHITE
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(tabBg)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            gravity = Gravity.CENTER
        }

        tripsTabBtn = makeTabButton("Trips", TripsTab.TRIPS)
        statsTabBtn = makeTabButton("Stats", TripsTab.STATS)
        storageTabBtn = makeTabButton("Storage", TripsTab.STORAGE)

        listOf(tripsTabBtn, statsTabBtn, storageTabBtn).forEach { btn ->
            bar.addView(btn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(dp(4), 0, dp(4), 0)
            })
        }
        updateTabSelection()
        return bar
    }

    private fun makeTabButton(label: String, tab: TripsTab): TextView {
        return TextView(context).apply {
            text = label
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setOnClickListener {
                activeTab = tab
                updateTabSelection()
                renderCurrentTab()
            }
        }
    }

    private fun updateTabSelection() {
        val isDark = isDark()
        listOf(tripsTabBtn to TripsTab.TRIPS, statsTabBtn to TripsTab.STATS, storageTabBtn to TripsTab.STORAGE)
            .forEach { (btn, tab) ->
                if (tab == activeTab) {
                    btn.background = pillBackground(Color.parseColor("#4CAF50"))
                    btn.setTextColor(Color.WHITE)
                } else {
                    btn.background = pillBackground(if (isDark) Color.parseColor("#2C2C2C") else Color.parseColor("#EEEEEE"))
                    btn.setTextColor(if (isDark) Color.parseColor("#AAAAAA") else Color.parseColor("#555555"))
                }
            }
    }

    // ──────────────────────────────── DATA ────────────────────────────────

    private fun loadData() {
        setLoadingState()
        Thread({
            try {
                val trips = client.fetchTrips(activeFilter.days)
                val summary = client.fetchSummary(activeFilter.days)
                val dna = client.fetchDna(30)
                val range = client.fetchRange()
                val config = client.fetchConfig()
                val storage = client.fetchStorage()
                editConfig = config
                editStorage = storage
                val state = TripsLoadState.Loaded(
                    trips = trips, summary = summary,
                    dna = dna, range = range, config = config, storage = storage
                )
                loadedState = state
                root.post { renderCurrentTab(); loadedOnce = true }
            } catch (e: Exception) {
                val err = TripsLoadState.Error(e.message ?: "Load failed")
                loadedState = err
                root.post { renderCurrentTab(); loadedOnce = true }
            }
        }, "TripsLoad").apply { isDaemon = true; start() }
    }

    private fun setLoadingState() {
        contentArea.removeAllViews()
        contentArea.addView(centeredText("Loading…", 16f))
    }

    // ──────────────────────────────── RENDER ────────────────────────────────

    private fun renderCurrentTab() {
        if (activeTab != TripsTab.STORAGE) {
            syncRunning = false; syncResultMessage = null
        }
        applyTheme()
        updateTabSelection()
        contentArea.removeAllViews()
        analyticsSwitch = null; rateInput = null; currencyInput = null
        distanceUnitKmBtn = null; distanceUnitMiBtn = null
        storageInternalBtn = null; storageSdBtn = null

        when (val state = loadedState) {
            null -> contentArea.addView(centeredText("Loading…", 16f))
            is TripsLoadState.Loading -> contentArea.addView(centeredText("Loading…", 16f))
            is TripsLoadState.Error -> contentArea.addView(centeredText("Error: ${state.message}", 14f))
            is TripsLoadState.Empty -> renderEmptyState()
            is TripsLoadState.Loaded -> when (activeTab) {
                TripsTab.TRIPS -> renderTripsTab(state)
                TripsTab.STATS -> renderStatsTab(state)
                TripsTab.STORAGE -> renderStorageTab(state)
            }
        }
    }

    private fun applyTheme() {
        val isDark = isDark()
        root.setBackgroundColor(if (isDark) Color.parseColor("#121212") else Color.parseColor("#F5F5F5"))
    }

    // ──────────────────────── TRIPS TAB ────────────────────────────────────

    private fun renderTripsTab(state: TripsLoadState.Loaded) {
        // Filter row
        contentArea.addView(buildFilterRow())
        contentArea.addView(spacer(dp(8)))

        // Period Summary card (from summary data, rolled up)
        val summary = state.summary
        if (summary != null) {
            contentArea.addView(buildSummaryCard(summary, state.config))
            contentArea.addView(spacer(dp(12)))
        }

        // Trips list or empty state
        if (state.trips.isEmpty()) {
            contentArea.addView(centeredText("No trips recorded yet", 15f))
        } else {
            state.trips.forEach { trip ->
                contentArea.addView(buildTripRow(trip, state.config))
                contentArea.addView(spacer(dp(4)))
            }
        }
    }

    private fun buildFilterRow(): HorizontalScrollView {
        val scroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 0)
        }
        TripsDaysFilter.values().forEach { filter ->
            val btn = makeFilterButton(filter)
            row.addView(btn, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, dp(8), 0)
            })
        }
        scroll.addView(row)
        return scroll
    }

    private fun makeFilterButton(filter: TripsDaysFilter): TextView {
        val selected = filter == activeFilter
        val isDark = isDark()
        return TextView(context).apply {
            text = filter.label
            textSize = 13f
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = pillBackground(
                if (selected) Color.parseColor("#4CAF50")
                else if (isDark) Color.parseColor("#2C2C2C") else Color.parseColor("#EEEEEE")
            )
            setTextColor(
                if (selected) Color.WHITE
                else if (isDark) Color.parseColor("#AAAAAA") else Color.parseColor("#555555")
            )
            setOnClickListener {
                activeFilter = filter
                loadData()
            }
        }
    }

    private fun buildSummaryCard(summary: TripsSummary, config: TripsConfig?): LinearLayout {
        val card = makeCard()
        card.addView(labelText("Period Summary"))
        card.addView(spacer(dp(8)))

        val grid = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val col1 = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
        val col2 = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
        val col3 = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }

        val distUnit = config?.distanceUnit ?: "km"
        val distDisplay = if (distUnit == "mi") {
            val mi = summary.totalDistanceKm * 0.621371
            "%.1f mi".format(mi)
        } else "%.1f km".format(summary.totalDistanceKm)

        col1.addView(statBlock("Trips", "${summary.tripCount}"))
        col1.addView(statBlock(distUnit.uppercase(), distDisplay))
        col2.addView(statBlock("Hours", summary.formattedHours))
        col2.addView(statBlock("Efficiency", "%.0f%%".format(summary.avgEfficiency)))
        col3.addView(statBlock("kWh", "%.1f".format(summary.totalEnergyKwh)))
        col3.addView(statBlock("kWh/100km", "%.2f".format(summary.avgEnergyPerKm * 100)))

        grid.addView(col1); grid.addView(col2); grid.addView(col3)
        card.addView(grid)
        return card
    }

    private fun buildTripRow(trip: TripItem, config: TripsConfig?): LinearLayout {
        val isDark = isDark()
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBackground(isDark)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            isClickable = true
            setOnClickListener { showDetail(trip.id, config) }
        }
        val row1 = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val dateView = TextView(context).apply {
            text = trip.formattedDate
            textSize = 13f
            setTextColor(if (isDark) Color.WHITE else Color.BLACK)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val scoreView = TextView(context).apply {
            text = if (trip.overallScore > 0) "Score: ${trip.overallScore}" else ""
            textSize = 12f
            setTextColor(Color.parseColor("#4CAF50"))
        }
        row1.addView(dateView); row1.addView(scoreView)
        card.addView(row1)

        val distUnit = config?.distanceUnit ?: "km"
        val dist = if (distUnit == "mi") "%.1f mi".format(trip.distanceKm * 0.621371)
                   else "%.1f km".format(trip.distanceKm)
        val row2 = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val detailText = "$dist  ·  ${trip.formattedDuration}"
        val detailView = TextView(context).apply {
            text = detailText
            textSize = 12f
            setTextColor(if (isDark) Color.parseColor("#AAAAAA") else Color.parseColor("#666666"))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        row2.addView(detailView)
        if (trip.tripCost > 0 && trip.currency.isNotBlank()) {
            val costView = TextView(context).apply {
                text = "${trip.currency} %.2f".format(trip.tripCost)
                textSize = 12f
                setTextColor(if (isDark) Color.parseColor("#AAAAAA") else Color.parseColor("#666666"))
            }
            row2.addView(costView)
        }
        card.addView(spacer(dp(4)))
        card.addView(row2)
        return card
    }

    // ──────────────────────── TRIP DETAIL OVERLAY ──────────────────────────

    private fun showDetail(tripId: Long, config: TripsConfig?) {
        if (detailController != null) return
        val controller = TripDetailController(context, client, onClose = { hideDetail() })
        detailController = controller
        root.addView(controller.view)
        controller.onResume()
        controller.load(tripId, config)
        onDetailVisibilityChanged?.invoke(true)
    }

    private fun hideDetail() {
        val controller = detailController ?: return
        controller.onPause()
        controller.onDestroy()
        root.removeView(controller.view)
        detailController = null
        onDetailVisibilityChanged?.invoke(false)
    }

    // ──────────────────────── STATS TAB ────────────────────────────────────

    private fun renderStatsTab(state: TripsLoadState.Loaded) {
        // Driver Score card
        val dna = state.dna
        val scoreCard = makeCard()
        scoreCard.addView(labelText("Driver Score"))
        scoreCard.addView(spacer(dp(8)))
        val overall = dna?.overall ?: 0
        // Score out of 700 = sum of 7 axes, but we have 5 with max 100 each = 500 max.
        // Display as out of 500 to match the 5-axis DNA.
        val scoreOut500 = dna?.let { (it.anticipation + it.smoothness + it.speedDiscipline + it.efficiency + it.consistency) } ?: 0
        scoreCard.addView(bigStatText("$scoreOut500 / 500"))
        scoreCard.addView(smallText("Overall: $overall / 100"))
        contentArea.addView(scoreCard)
        contentArea.addView(spacer(dp(12)))

        // Personalized Range
        val range = state.range
        val rangeCard = makeCard()
        rangeCard.addView(labelText("Personalized Range"))
        rangeCard.addView(spacer(dp(8)))
        if (range != null && range.estimatedKm > 0) {
            rangeCard.addView(bigStatText("%.0f km".format(range.estimatedKm)))
            if (range.builtInKm > 0) rangeCard.addView(smallText("BYD estimate: %.0f km".format(range.builtInKm)))
        } else {
            rangeCard.addView(smallText("Not enough data yet"))
        }
        contentArea.addView(rangeCard)
        contentArea.addView(spacer(dp(12)))

        // Driving DNA
        if (dna != null) {
            val dnaCard = makeCard()
            dnaCard.addView(labelText("Driving DNA"))
            dnaCard.addView(spacer(dp(8)))
            listOf(
                "Anticipation" to dna.anticipation,
                "Smoothness" to dna.smoothness,
                "Speed Discipline" to dna.speedDiscipline,
                "Efficiency" to dna.efficiency,
                "Consistency" to dna.consistency,
            ).forEach { (name, score) ->
                dnaCard.addView(buildDnaBar(name, score))
                dnaCard.addView(spacer(dp(6)))
            }
            contentArea.addView(dnaCard)
        }
    }

    private fun buildDnaBar(name: String, score: Int): LinearLayout {
        val isDark = isDark()
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
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
            background = pillBackground(Color.parseColor("#4CAF50"))
            layoutParams = FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        barBg.addView(fill)
        barBg.viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                barBg.viewTreeObserver.removeOnGlobalLayoutListener(this)
                fill.layoutParams = FrameLayout.LayoutParams(
                    (fraction * barBg.width).toInt(),
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
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

    // ──────────────────────── STORAGE TAB ────────────────────────────────────

    private fun renderStorageTab(state: TripsLoadState.Loaded) {
        val isDark = isDark()
        val cfg = state.config ?: editConfig
        val storage = state.storage ?: editStorage
        selectedDistUnit = cfg?.distanceUnit ?: "km"
        selectedStorageType = storage?.storageType ?: "INTERNAL"

        val card = makeCard()
        card.addView(labelText("Trip Storage"))
        card.addView(spacer(dp(12)))

        // Trip Analytics toggle
        val analyticsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val analyticsLabel = TextView(context).apply {
            text = "Trip Analytics"
            textSize = 14f
            setTextColor(if (isDark) Color.WHITE else Color.BLACK)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val sw = SwitchCompat(context).apply {
            isChecked = cfg?.enabled ?: false
        }
        analyticsSwitch = sw
        analyticsRow.addView(analyticsLabel); analyticsRow.addView(sw)
        card.addView(analyticsRow)
        card.addView(spacer(dp(12)))

        // Electricity Rate
        card.addView(fieldLabel("Electricity Rate"))
        card.addView(spacer(dp(4)))
        val rateRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val currEdit = android.widget.EditText(context).apply {
            setText(cfg?.currency ?: "USD")
            textSize = 14f
            setTextColor(if (isDark) Color.WHITE else Color.BLACK)
            setHintTextColor(if (isDark) Color.parseColor("#666666") else Color.parseColor("#AAAAAA"))
            background = inputBackground(isDark)
            setPadding(dp(8), dp(6), dp(8), dp(6))
            maxLines = 1
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            layoutParams = LinearLayout.LayoutParams(dp(70), ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, dp(8), 0) }
        }
        currencyInput = currEdit
        val rateEdit = android.widget.EditText(context).apply {
            setText("%.4f".format(cfg?.electricityRate ?: 0.0))
            textSize = 14f
            setTextColor(if (isDark) Color.WHITE else Color.BLACK)
            background = inputBackground(isDark)
            setPadding(dp(8), dp(6), dp(8), dp(6))
            maxLines = 1
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        rateInput = rateEdit
        rateRow.addView(currEdit); rateRow.addView(rateEdit)
        card.addView(rateRow)
        card.addView(spacer(dp(12)))

        // Distance Unit
        card.addView(fieldLabel("Distance Unit"))
        card.addView(spacer(dp(4)))
        val distUnit = cfg?.distanceUnit ?: "km"
        val distRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val kmBtn = makeSegmentButton("km", distUnit == "km") {
            selectedDistUnit = "km"
            distanceUnitKmBtn?.background = pillBackground(Color.parseColor("#4CAF50")); distanceUnitKmBtn?.setTextColor(Color.WHITE)
            distanceUnitMiBtn?.background = pillBackground(if (isDark()) Color.parseColor("#2C2C2C") else Color.parseColor("#EEEEEE"))
            distanceUnitMiBtn?.setTextColor(if (isDark()) Color.parseColor("#AAAAAA") else Color.parseColor("#555555"))
        }
        distanceUnitKmBtn = kmBtn
        val miBtn = makeSegmentButton("mi", distUnit == "mi") {
            selectedDistUnit = "mi"
            distanceUnitMiBtn?.background = pillBackground(Color.parseColor("#4CAF50")); distanceUnitMiBtn?.setTextColor(Color.WHITE)
            distanceUnitKmBtn?.background = pillBackground(if (isDark()) Color.parseColor("#2C2C2C") else Color.parseColor("#EEEEEE"))
            distanceUnitKmBtn?.setTextColor(if (isDark()) Color.parseColor("#AAAAAA") else Color.parseColor("#555555"))
        }
        distanceUnitMiBtn = miBtn
        distRow.addView(kmBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, dp(8), 0) })
        distRow.addView(miBtn)
        card.addView(distRow)
        card.addView(spacer(dp(12)))

        // Storage Location
        card.addView(fieldLabel("Storage Location"))
        card.addView(spacer(dp(4)))
        val storType = storage?.storageType ?: "INTERNAL"
        val locRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val intBtn = makeSegmentButton("Internal", storType == "INTERNAL") {
            selectedStorageType = "INTERNAL"
            storageInternalBtn?.background = pillBackground(Color.parseColor("#4CAF50")); storageInternalBtn?.setTextColor(Color.WHITE)
            storageSdBtn?.background = pillBackground(if (isDark()) Color.parseColor("#2C2C2C") else Color.parseColor("#EEEEEE"))
            storageSdBtn?.setTextColor(if (isDark()) Color.parseColor("#AAAAAA") else Color.parseColor("#555555"))
        }
        storageInternalBtn = intBtn
        val sdAvail = storage?.sdCardAvailable ?: false
        val sdBtn = makeSegmentButton("SD Card${if (!sdAvail) " (N/A)" else ""}", storType == "SD_CARD") {
            if (sdAvail) {
                selectedStorageType = "SD_CARD"
                storageSdBtn?.background = pillBackground(Color.parseColor("#4CAF50")); storageSdBtn?.setTextColor(Color.WHITE)
                storageInternalBtn?.background = pillBackground(if (isDark()) Color.parseColor("#2C2C2C") else Color.parseColor("#EEEEEE"))
                storageInternalBtn?.setTextColor(if (isDark()) Color.parseColor("#AAAAAA") else Color.parseColor("#555555"))
            }
        }
        sdBtn.isEnabled = sdAvail
        if (!sdAvail) sdBtn.alpha = 0.5f
        storageSdBtn = sdBtn
        locRow.addView(intBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, dp(8), 0) })
        locRow.addView(sdBtn)
        card.addView(locRow)
        card.addView(spacer(dp(8)))

        // Storage usage info
        if (storage != null) {
            val usageTv = TextView(context).apply {
                text = "${storage.usedMb} ${storage.usedUnit} used / ${storage.limitMb} MB limit  ·  ${storage.tripsCount} trips"
                textSize = 12f
                setTextColor(if (isDark) Color.parseColor("#AAAAAA") else Color.parseColor("#666666"))
            }
            card.addView(usageTv)
            card.addView(spacer(dp(4)))
            if (storage.storagePath.isNotBlank()) {
                card.addView(TextView(context).apply {
                    text = storage.storagePath
                    textSize = 11f
                    setTextColor(if (isDark) Color.parseColor("#777777") else Color.parseColor("#888888"))
                })
            }
        }

        card.addView(spacer(dp(12)))

        // Apply Changes button
        val applyBtn = TextView(context).apply {
            text = "Apply Changes"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = pillBackground(Color.parseColor("#4CAF50"))
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setOnClickListener { saveStorageSettings() }
        }
        card.addView(applyBtn)

        contentArea.addView(card)
        contentArea.addView(spacer(dp(12)))
        contentArea.addView(buildTripsSyncCard())
    }

    private fun buildTripsSyncCard(): android.view.View {
        val isDark = isDark()
        val card = makeCard()
        card.addView(labelText("Database Catalog"))
        card.addView(spacer(dp(6)))
        card.addView(TextView(context).apply {
            text = "Reconcile the trips index with telemetry files on disk."
            textSize = 12f
            setTextColor(if (isDark) Color.parseColor("#AAAAAA") else Color.parseColor("#666666"))
        })
        card.addView(spacer(dp(12)))
        when {
            syncResultMessage != null -> {
                val isError = !syncResultMessage!!.startsWith("Synced")
                card.addView(TextView(context).apply {
                    text = syncResultMessage
                    textSize = 13f
                    setTextColor(if (isError) Color.parseColor("#F44336") else Color.parseColor("#4CAF50"))
                })
                card.addView(spacer(dp(8)))
                card.addView(TextView(context).apply {
                    text = "Dismiss"
                    textSize = 13f; gravity = Gravity.CENTER
                    setTextColor(if (isDark) Color.parseColor("#AAAAAA") else Color.parseColor("#666666"))
                    setPadding(0, dp(4), 0, dp(4))
                    setOnClickListener { syncResultMessage = null; renderCurrentTab() }
                })
            }
            syncRunning -> card.addView(centeredText("Syncing…", 13f))
            else -> card.addView(TextView(context).apply {
                text = "Sync Database"
                textSize = 13f; gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                background = pillBackground(Color.parseColor("#4CAF50"))
                setPadding(dp(16), dp(12), dp(16), dp(12))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                setOnClickListener { executeTripsSyncDatabase() }
            })
        }
        return card
    }

    private fun executeTripsSyncDatabase() {
        syncRunning = true
        renderCurrentTab()
        Thread({
            val result = client.syncDatabase()
            root.post {
                syncRunning = false
                syncResultMessage = result.message
                renderCurrentTab()
            }
        }, "TripsDbSync").apply { isDaemon = true; start() }
    }

    private fun saveStorageSettings() {
        val enabled = analyticsSwitch?.isChecked ?: return
        val rateStr = rateInput?.text?.toString() ?: "0"
        val currency = currencyInput?.text?.toString() ?: "USD"
        val rate = rateStr.toDoubleOrNull() ?: 0.0
        val distUnit = selectedDistUnit
        val storType = selectedStorageType
        Thread({
            client.saveConfig(enabled, rate, currency, distUnit)
            val currentStorage = editStorage
            if (currentStorage != null) {
                client.saveStorage(storType, currentStorage.limitMb)
            }
            root.post { loadData() }
        }, "TripsSave").apply { isDaemon = true; start() }
    }

    // ──────────────────────── HELPERS ────────────────────────────────────────

    private fun renderEmptyState() {
        contentArea.addView(buildFilterRow())
        contentArea.addView(spacer(dp(24)))
        contentArea.addView(centeredText("No trips recorded yet", 16f))
    }

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

    private fun inputBackground(isDark: Boolean): GradientDrawable =
        GradientDrawable().apply {
            setColor(if (isDark) Color.parseColor("#2C2C2C") else Color.parseColor("#F0F0F0"))
            cornerRadius = dp(4).toFloat()
        }

    private fun makeCard(): LinearLayout {
        val isDark = isDark()
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBackground(isDark)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 0, 0)
            }
        }
    }

    private fun makeSegmentButton(label: String, selected: Boolean, onSelect: () -> Unit): TextView {
        val isDark = isDark()
        return TextView(context).apply {
            text = label; textSize = 13f; gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = pillBackground(if (selected) Color.parseColor("#4CAF50") else if (isDark) Color.parseColor("#2C2C2C") else Color.parseColor("#EEEEEE"))
            setTextColor(if (selected) Color.WHITE else if (isDark) Color.parseColor("#AAAAAA") else Color.parseColor("#555555"))
            setOnClickListener { onSelect() }
        }
    }

    private fun labelText(text: String): TextView {
        val isDark = isDark()
        return TextView(context).apply {
            this.text = text; textSize = 14f
            setTextColor(if (isDark) Color.WHITE else Color.BLACK)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
    }

    private fun fieldLabel(text: String): TextView {
        val isDark = isDark()
        return TextView(context).apply {
            this.text = text; textSize = 12f
            setTextColor(if (isDark) Color.parseColor("#AAAAAA") else Color.parseColor("#666666"))
        }
    }

    private fun bigStatText(text: String): TextView {
        val isDark = isDark()
        return TextView(context).apply {
            this.text = text; textSize = 28f; gravity = Gravity.CENTER
            setTextColor(if (isDark) Color.WHITE else Color.BLACK)
        }
    }

    private fun smallText(text: String): TextView {
        val isDark = isDark()
        return TextView(context).apply {
            this.text = text; textSize = 13f; gravity = Gravity.CENTER
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
            setPadding(0, dp(4), 0, dp(4))
            addView(TextView(context).apply {
                text = value; textSize = 18f; gravity = Gravity.CENTER
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
