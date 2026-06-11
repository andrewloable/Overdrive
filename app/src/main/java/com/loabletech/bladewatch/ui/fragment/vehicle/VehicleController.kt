package net.bladewatch.app.ui.fragment.vehicle

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
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

    // ─── State ───────────────────────────────────────────────────────────────

    private var vehicleState = VehicleState()
    private var currentTab = VehicleTab.TRUNK
    private val client = VehicleClient()
    private val factory = VehicleViewFactory(context)

    // Seat local state — needed for full-state sends on every seat call
    private var driverHeat = 0; private var driverVent = 0
    private var passengerHeat = 0; private var passengerVent = 0

    // Climate local state for optimistic UI and max-cooling capture
    private var acOn = false; private var setpointC = 22; private var fanLevel = 3; private var maxCooling = false

    private val running = AtomicBoolean(false)
    private val inFlight = ConcurrentHashMap<String, Boolean>()
    private var pollExecutor: ScheduledExecutorService? = null
    private val lastClick = mutableMapOf<String, Long>()

    // In-place tab updater — populated each time renderTabContent() builds a new panel
    private var currentPanelUpdater: ((PanelContext) -> Unit)? = null

    // Hero region with tyre overlay
    private lateinit var tyreOverlay: TyreOverlay

    // Stale-state banner — visible when showing cached state before first live poll
    private var staleBanner: TextView? = null

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
    }

    fun onPause() {
        running.set(false)
        pollGeneration.incrementAndGet()
        pollExecutor?.shutdownNow()
        pollExecutor = null
        if (::tyreOverlay.isInitialized) tyreOverlay.cancelAnimations()
    }

    fun onDestroy() { onPause() }

    fun onConfigurationChanged() {
        rebuildAll()
    }

    // ─── Build ───────────────────────────────────────────────────────────────

    private fun buildView() {
        root.setBackgroundColor(factory.bgColor())
        root.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

        outerLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        statusArea = buildStatusCard()
        outerLayout.addView(statusArea)

        staleBanner = TextView(context).apply {
            text = context.getString(R.string.vehicle_stale_connecting)
            textSize = 11f; gravity = Gravity.CENTER
            setTextColor(factory.mutedColor())
            setPadding(factory.dp(8), factory.dp(4), factory.dp(8), factory.dp(4))
            visibility = View.GONE
        }
        outerLayout.addView(staleBanner)

        // Hero region: themed grid + car silhouette + tyre corner cards
        tyreOverlay = TyreOverlay(context, factory)
        outerLayout.addView(tyreOverlay.view)

        contentScroll = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            isFillViewport = true
        }
        contentArea = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(factory.dp(12), factory.dp(12), factory.dp(12), factory.dp(12))
        }
        contentScroll.addView(contentArea)
        outerLayout.addView(contentScroll)

        val tabRow = buildTabBar()
        outerLayout.addView(tabRow)

        root.addView(outerLayout)
    }

    private fun buildStatusCard(): LinearLayout {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(factory.surfaceColor())
            setPadding(factory.dp(16), factory.dp(12), factory.dp(16), factory.dp(12))
            gravity = Gravity.CENTER_VERTICAL
        }

        val lockSection = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val dot = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(factory.dp(10), factory.dp(10)).apply { marginEnd = factory.dp(6) }
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.GRAY) }
        }
        val lockTv = TextView(context).apply { text = "—"; textSize = 13f; setTextColor(factory.textColor()) }  // "—" is a placeholder, replaced on first poll
        lockStatusDot = dot; lockStatusText = lockTv
        lockSection.addView(dot); lockSection.addView(lockTv)
        card.addView(lockSection)

        card.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(factory.dp(1), factory.dp(24)).apply { marginStart = factory.dp(8); marginEnd = factory.dp(8) }
            setBackgroundColor(factory.dividerColor())
        })

        val battSection = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val battTv = TextView(context).apply { text = "—"; textSize = 15f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setTextColor(factory.textColor()) }
        val rangeTv = TextView(context).apply { text = context.getString(R.string.vehicle_range_label); textSize = 11f; gravity = Gravity.CENTER; setTextColor(factory.mutedColor()) }
        batteryText = battTv; rangeText = rangeTv
        battSection.addView(battTv); battSection.addView(rangeTv)
        card.addView(battSection)

        return card
    }

    private fun buildTabBar(): HorizontalScrollView {
        val scroll = HorizontalScrollView(context).apply {
            setBackgroundColor(factory.surfaceColor())
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
            background = GradientDrawable().apply {
                cornerRadius = factory.dp(16).toFloat()
                setColor(if (selected) factory.accentColor() else factory.pillBgColor())
            }
            setTextColor(if (selected) Color.WHITE else factory.textColor())
            setOnClickListener {
                currentTab = tab
                rebuildTabBar()
                renderTabContent()
            }
        }
    }

    // ─── Rebuild helpers ─────────────────────────────────────────────────────

    private fun rebuildAll() {
        if (::tyreOverlay.isInitialized) tyreOverlay.cancelAnimations()
        root.removeAllViews()
        lockStatusDot = null; lockStatusText = null; batteryText = null; rangeText = null
        staleBanner = null
        currentPanelUpdater = null
        buildView()
        applyStateToViews()
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
            VehicleTab.TRUNK    -> buildTrunkTab(ctx)
            VehicleTab.CLIMATE  -> buildClimateTab(ctx)
            VehicleTab.SEATS    -> buildSeatsTab(ctx)
            VehicleTab.WINDOWS  -> buildWindowsTab(ctx)
            VehicleTab.LIGHTS   -> buildLightsTab(ctx)
            VehicleTab.ADAS     -> buildAdasTab(ctx)
            VehicleTab.CHARGING -> buildChargingTab(ctx)
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
            batteryText?.text = "${state.battery.soc}%"
            rangeText?.text = "${state.battery.rangeKm} km"
        } else {
            batteryText?.text = "—"; rangeText?.text = context.getString(R.string.vehicle_range_label)
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
