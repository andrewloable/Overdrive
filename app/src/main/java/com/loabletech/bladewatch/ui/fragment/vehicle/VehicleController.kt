package net.bladewatch.app.ui.fragment.vehicle

import android.content.Context
import android.content.res.Configuration
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
import androidx.appcompat.app.AppCompatDelegate
import net.bladewatch.app.ui.util.PreferencesManager
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class VehicleController(private val context: Context) {

    // ─── State ───────────────────────────────────────────────────────────────

    private var vehicleState = VehicleState()
    private var currentTab = VehicleTab.SECURITY
    private val client = VehicleClient()

    // Seat local state — needed for full-state sends on every seat call
    private var driverHeat = 0; private var driverVent = 0
    private var passengerHeat = 0; private var passengerVent = 0

    // Climate local state for optimistic UI and max-cooling capture
    private var acOn = false; private var setpointC = 22; private var fanLevel = 3; private var maxCooling = false

    private val running = AtomicBoolean(false)
    private val inFlight = AtomicBoolean(false)
    private var pollExecutor: ScheduledExecutorService? = null
    private val lastClick = mutableMapOf<String, Long>()

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
        pollExecutor?.shutdownNow()
        pollExecutor = null
    }

    fun onDestroy() { onPause() }

    fun onConfigurationChanged() {
        rebuildAll()
    }

    // ─── Build ───────────────────────────────────────────────────────────────

    private fun buildView() {
        root.setBackgroundColor(bgColor())
        root.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

        outerLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        // Status card at top
        statusArea = buildStatusCard()
        outerLayout.addView(statusArea)

        // Scrollable content
        contentScroll = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            isFillViewport = true
        }
        contentArea = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        contentScroll.addView(contentArea)
        outerLayout.addView(contentScroll)

        // Tab bar at bottom
        val tabRow = buildTabBar()
        outerLayout.addView(tabRow)

        root.addView(outerLayout)
    }

    private fun buildStatusCard(): LinearLayout {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(surfaceColor())
            setPadding(dp(16), dp(12), dp(16), dp(12))
            gravity = Gravity.CENTER_VERTICAL
        }

        // Lock status
        val lockSection = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val dot = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(10), dp(10)).apply { marginEnd = dp(6) }
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.GRAY) }
        }
        val lockTv = TextView(context).apply { text = "Checking…"; textSize = 13f; setTextColor(textColor()) }
        lockStatusDot = dot; lockStatusText = lockTv
        lockSection.addView(dot)
        lockSection.addView(lockTv)
        card.addView(lockSection)

        // Divider
        card.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(1), dp(24)).apply { marginStart = dp(8); marginEnd = dp(8) }
            setBackgroundColor(dividerColor())
        })

        // Battery
        val battSection = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val battTv = TextView(context).apply { text = "—"; textSize = 15f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setTextColor(textColor()) }
        val rangeTv = TextView(context).apply { text = "Range"; textSize = 11f; gravity = Gravity.CENTER; setTextColor(mutedColor()) }
        batteryText = battTv; rangeText = rangeTv
        battSection.addView(battTv); battSection.addView(rangeTv)
        card.addView(battSection)

        return card
    }

    private fun buildTabBar(): HorizontalScrollView {
        val scroll = HorizontalScrollView(context).apply {
            setBackgroundColor(surfaceColor())
            isHorizontalScrollBarEnabled = false
        }
        tabBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
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
            text = tab.label
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(7), dp(14), dp(7))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6) }
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(if (selected) accentColor() else pillBgColor())
            }
            setTextColor(if (selected) Color.WHITE else textColor())
            setOnClickListener {
                currentTab = tab
                rebuildTabBar()
                renderTabContent()
            }
        }
    }

    // ─── Rebuild helpers ─────────────────────────────────────────────────────

    private fun rebuildAll() {
        root.removeAllViews()
        lockStatusDot = null; lockStatusText = null; batteryText = null; rangeText = null
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

    private fun renderTabContent() {
        contentArea.removeAllViews()
        when (currentTab) {
            VehicleTab.SECURITY -> contentArea.addView(buildSecurityTab())
            VehicleTab.TRUNK    -> contentArea.addView(buildTrunkTab())
            VehicleTab.CLIMATE  -> contentArea.addView(buildClimateTab())
            VehicleTab.SEATS    -> contentArea.addView(buildSeatsTab())
            VehicleTab.WINDOWS  -> contentArea.addView(buildWindowsTab())
            VehicleTab.LIGHTS   -> contentArea.addView(buildLightsTab())
            VehicleTab.ADAS     -> contentArea.addView(buildAdasTab())
            VehicleTab.CHARGING -> contentArea.addView(buildChargingTab())
        }
    }

    // ─── State application ───────────────────────────────────────────────────

    private fun applyStateToViews() {
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
        val (dotColor, lockLabel) = when (lockVal) {
            1 -> Pair(Color.parseColor("#4CAF50"), "Locked")
            2 -> Pair(Color.parseColor("#FF9800"), "Unlocked")
            else -> Pair(Color.GRAY, "Status unavailable")
        }
        (lockStatusDot?.background as? GradientDrawable)?.setColor(dotColor)
        lockStatusText?.text = lockLabel

        if (state.battery.soc > 0) {
            batteryText?.text = "${state.battery.soc}%"
            rangeText?.text = "${state.battery.rangeKm} km"
        } else {
            batteryText?.text = "—"; rangeText?.text = "Range"
        }

        // Rebuild tab bar (might show/hide Seats) and current tab
        rebuildTabBar()
        renderTabContent()
    }

    // ─── Tab: Security ───────────────────────────────────────────────────────

    private fun buildSecurityTab(): LinearLayout {
        val panel = makePanel()
        panel.addView(sectionTitle("Security"))
        panel.addView(spacer(dp(12)))
        val grid = makeTwoColGrid(
            buildActionButton("Lock", Color.parseColor("#4CAF50")) { doVehicleAction("lock") { client.lock() } },
            buildActionButton("Unlock", Color.parseColor("#FF9800")) { doVehicleAction("unlock") { client.unlock() } },
            buildActionButton("Flash Lights", Color.parseColor("#2196F3")) { doVehicleAction("flash") { client.flashLights() } },
            buildActionButton("Find Car", Color.parseColor("#9C27B0")) { doVehicleAction("find_car") { client.findCar() } },
        )
        panel.addView(grid)
        return panel
    }

    // ─── Tab: Trunk ──────────────────────────────────────────────────────────

    private fun buildTrunkTab(): LinearLayout {
        val panel = makePanel()
        panel.addView(sectionTitle("Trunk"))
        panel.addView(spacer(dp(8)))
        panel.addView(infoLabel("Opening the trunk will unlock the car first."))
        panel.addView(spacer(dp(12)))
        val grid = makeTwoColGrid(
            buildActionButton("Open Trunk", Color.parseColor("#FF9800")) { doVehicleAction("trunk_open") { client.trunkOpen() } },
            buildActionButton("Close Trunk", Color.parseColor("#607D8B")) { doVehicleAction("trunk_close") { client.trunkClose() } },
        )
        panel.addView(grid)
        return panel
    }

    // ─── Tab: Climate ────────────────────────────────────────────────────────

    private fun buildClimateTab(): LinearLayout {
        val panel = makePanel()
        panel.addView(sectionTitle("Climate"))
        panel.addView(spacer(dp(12)))

        // Inside temp if available
        vehicleState.climate.insideTempC?.let {
            panel.addView(centeredLabel("Inside: ${"%.1f".format(it)}°C"))
            panel.addView(spacer(dp(8)))
        }

        // AC on/off
        val acColor = if (acOn) Color.parseColor("#2196F3") else Color.parseColor("#607D8B")
        val acLabel = if (acOn) "AC On" else "AC Off"
        panel.addView(buildWideButton(acLabel, acColor) {
            if (debounce("ac_toggle")) {
                val nowOn = !acOn
                acOn = nowOn
                Thread({
                    val ok = if (nowOn) client.climateOn(setpointC) else client.climateOff()
                    root.post {
                        if (!ok) { acOn = !nowOn; toast("Climate control failed.") }
                        renderTabContent()
                    }
                }, "ClimateAC").apply { isDaemon = true; start() }
            }
        })
        panel.addView(spacer(dp(8)))

        // Max Cooling
        val maxColor = if (maxCooling) Color.parseColor("#F44336") else Color.parseColor("#607D8B")
        val maxLabel = if (maxCooling) "Max Cooling: ON" else "Max Cooling: OFF"
        panel.addView(buildWideButton(maxLabel, maxColor) {
            if (debounce("max_cooling")) {
                val capturedAcOn = acOn; val capturedTemp = setpointC; val capturedFan = fanLevel
                val nowMax = !maxCooling
                maxCooling = nowMax
                Thread({
                    val ok = client.climateMaxCooling(nowMax, capturedAcOn, capturedTemp, capturedFan)
                    root.post {
                        if (!ok) { maxCooling = !nowMax; toast("Max cooling failed.") }
                        else if (nowMax) {
                            // Optimistic: max cooling sets acOn=true, temp=17, fan=7
                            acOn = true; setpointC = 17; fanLevel = 7
                        }
                        renderTabContent()
                    }
                }, "ClimateMaxCool").apply { isDaemon = true; start() }
            }
        })
        panel.addView(spacer(dp(12)))

        // Temp stepper
        panel.addView(buildStepper(
            label = "Temperature",
            value = "${setpointC}°C",
            onMinus = {
                if (debounce("temp_minus") && setpointC > 17) {
                    setpointC--
                    val t = setpointC
                    Thread({ client.climateSetTemp(t); root.post { renderTabContent() } }, "ClimateTemp").apply { isDaemon = true; start() }
                    renderTabContent()
                }
            },
            onPlus = {
                if (debounce("temp_plus") && setpointC < 33) {
                    setpointC++
                    val t = setpointC
                    Thread({ client.climateSetTemp(t); root.post { renderTabContent() } }, "ClimateTemp").apply { isDaemon = true; start() }
                    renderTabContent()
                }
            }
        ))
        panel.addView(spacer(dp(8)))

        // Fan stepper
        panel.addView(buildStepper(
            label = "Fan Speed",
            value = "Level $fanLevel",
            onMinus = {
                if (debounce("fan_minus") && fanLevel > 1) {
                    fanLevel--
                    val f = fanLevel
                    Thread({ client.climateSetFan(f); root.post { renderTabContent() } }, "ClimateFan").apply { isDaemon = true; start() }
                    renderTabContent()
                }
            },
            onPlus = {
                if (debounce("fan_plus") && fanLevel < 7) {
                    fanLevel++
                    val f = fanLevel
                    Thread({ client.climateSetFan(f); root.post { renderTabContent() } }, "ClimateFan").apply { isDaemon = true; start() }
                    renderTabContent()
                }
            }
        ))
        return panel
    }

    // ─── Tab: Seats ──────────────────────────────────────────────────────────

    private fun buildSeatsTab(): LinearLayout {
        val panel = makePanel()
        panel.addView(sectionTitle("Seats"))
        panel.addView(spacer(dp(12)))
        val caps = vehicleState.capabilities.seats

        fun seatRow(title: String, heat: Int, cool: Int, hasHeat: Boolean, hasCool: Boolean, hasMemory: Boolean, position: Int): LinearLayout {
            val row = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            row.addView(labelText(title))
            row.addView(spacer(dp(6)))
            val btns = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            if (hasHeat) {
                btns.addView(buildCycleButton("Heat ${heatLabel(heat)}", accentColor()) {
                    if (debounce("seat_heat_$position")) {
                        val newLevel = (heat + 1) % 3
                        if (position == 1) { driverHeat = newLevel; if (newLevel > 0) driverVent = 0 }
                        else { passengerHeat = newLevel; if (newLevel > 0) passengerVent = 0 }
                        val dH = driverHeat; val dV = driverVent; val pH = passengerHeat; val pV = passengerVent
                        Thread({ client.seatSetHeat(position, newLevel, dH, dV, pH, pV); root.post { renderTabContent() } }, "SeatHeat").apply { isDaemon = true; start() }
                        renderTabContent()
                    }
                })
                btns.addView(spacer2(dp(8)))
            }
            if (hasCool) {
                btns.addView(buildCycleButton("Cool ${heatLabel(cool)}", Color.parseColor("#2196F3")) {
                    if (debounce("seat_cool_$position")) {
                        val newLevel = (cool + 1) % 3
                        if (position == 1) { driverVent = newLevel; if (newLevel > 0) driverHeat = 0 }
                        else { passengerVent = newLevel; if (newLevel > 0) passengerHeat = 0 }
                        val dH = driverHeat; val dV = driverVent; val pH = passengerHeat; val pV = passengerVent
                        Thread({ client.seatSetCool(position, newLevel, dH, dV, pH, pV); root.post { renderTabContent() } }, "SeatCool").apply { isDaemon = true; start() }
                        renderTabContent()
                    }
                })
            }
            if (hasMemory && position == 1) {
                btns.addView(spacer2(dp(8)))
                btns.addView(buildCycleButton("Pos 1", Color.parseColor("#9C27B0")) {
                    if (debounce("seat_mem1")) doVehicleAction("seat_mem1") { client.seatRecallPosition(1) }
                })
                btns.addView(spacer2(dp(4)))
                btns.addView(buildCycleButton("Pos 2", Color.parseColor("#9C27B0")) {
                    if (debounce("seat_mem2")) doVehicleAction("seat_mem2") { client.seatRecallPosition(2) }
                })
            }
            row.addView(btns)
            return row
        }

        if (caps.driverHeat || caps.driverCool || caps.driverMemoryRecall) {
            panel.addView(seatRow("Driver", driverHeat, driverVent, caps.driverHeat, caps.driverCool, caps.driverMemoryRecall, 1))
            panel.addView(spacer(dp(12)))
        }
        if (caps.passengerHeat || caps.passengerCool) {
            panel.addView(seatRow("Passenger", passengerHeat, passengerVent, caps.passengerHeat, caps.passengerCool, false, 2))
        }
        if (!caps.anyAvailable()) {
            panel.addView(centeredLabel("No seat controls available for this vehicle."))
        }
        return panel
    }

    // ─── Tab: Windows ────────────────────────────────────────────────────────

    private fun buildWindowsTab(): LinearLayout {
        val panel = makePanel()
        panel.addView(sectionTitle("Windows"))
        panel.addView(spacer(dp(12)))
        val w = vehicleState.windows
        val caps = vehicleState.capabilities.windows

        // Per-window rows
        fun windowRow(name: String, area: Int, current: Int) {
            val row = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            val suffix = if (current == -1) "" else " ($current%)"
            row.addView(labelText("$name$suffix"))
            row.addView(spacer(dp(4)))
            val btns = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            for (pct in listOf(0, 25, 50, 75, 100)) {
                val isActive = current == pct
                btns.addView(buildSmallPresetButton("$pct%", isActive) {
                    if (debounce("win_${area}_$pct")) {
                        Thread({ client.windowSetPercent(area, pct); root.post { renderTabContent() } }, "Win").apply { isDaemon = true; start() }
                    }
                })
                if (pct != 100) btns.addView(spacer2(dp(4)))
            }
            row.addView(btns)
            panel.addView(row)
            panel.addView(spacer(dp(10)))
        }

        windowRow("Front Left", 1, w.lf)
        windowRow("Front Right", 2, w.rf)
        windowRow("Rear Left", 3, w.lr)
        windowRow("Rear Right", 4, w.rr)

        // All Windows controls
        panel.addView(labelText("All Windows"))
        panel.addView(spacer(dp(4)))
        val allRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        allRow.addView(buildSmallPresetButton("Close", false) {
            if (debounce("win_all_close")) Thread({ client.windowsAllClose(); root.post { renderTabContent() } }, "WinAllClose").apply { isDaemon = true; start() }
        })
        allRow.addView(spacer2(dp(6)))
        val isVented = listOf(w.lf, w.rf, w.lr, w.rr).filter { it != -1 }.let { vals ->
            vals.isNotEmpty() && vals.all { it in 1..20 }
        }
        allRow.addView(buildSmallPresetButton(if (isVented) "Close Vent" else "Vent 12%", isVented) {
            if (debounce("win_vent")) {
                val target = if (isVented) 0 else 12
                Thread({ client.windowsAllVent(target); root.post { renderTabContent() } }, "WinVent").apply { isDaemon = true; start() }
            }
        })
        allRow.addView(spacer2(dp(6)))
        allRow.addView(buildSmallPresetButton("Open All", false) {
            if (debounce("win_all_open")) Thread({ client.windowsAllOpen(); root.post { renderTabContent() } }, "WinAllOpen").apply { isDaemon = true; start() }
        })
        panel.addView(allRow)

        // Sunroof/Sunshade (conditional)
        if (caps.sunroof) {
            panel.addView(spacer(dp(10)))
            windowRow("Sunroof", 5, w.sunroof)
        }
        if (caps.sunshade) {
            panel.addView(spacer(dp(4)))
            windowRow("Sunshade", 6, w.sunshade)
        }
        return panel
    }

    // ─── Tab: Lights ─────────────────────────────────────────────────────────

    private fun buildLightsTab(): LinearLayout {
        val panel = makePanel()
        panel.addView(sectionTitle("Lights"))
        panel.addView(spacer(dp(12)))
        val drl = vehicleState.lights.dayTimeLight
        panel.addView(buildToggleRow("Daytime Running Lights", drl) { enable ->
            if (debounce("drl")) {
                val prev = vehicleState.lights.dayTimeLight
                vehicleState = vehicleState.copy(lights = LightsInfo(dayTimeLight = enable))
                Thread({
                    val ok = client.setDrl(enable)
                    root.post {
                        if (!ok) { vehicleState = vehicleState.copy(lights = LightsInfo(dayTimeLight = prev)); toast("DRL control failed.") }
                        renderTabContent()
                    }
                }, "DRL").apply { isDaemon = true; start() }
                renderTabContent()
            }
        })
        return panel
    }

    // ─── Tab: ADAS ───────────────────────────────────────────────────────────

    private fun buildAdasTab(): LinearLayout {
        val panel = makePanel()
        panel.addView(sectionTitle("ADAS"))
        panel.addView(spacer(dp(12)))
        val slw = vehicleState.adas.speedLimitWarning
        panel.addView(buildToggleRow("Speed Limit Warning", slw) { enable ->
            if (debounce("slw")) {
                val prev = slw
                vehicleState = vehicleState.copy(adas = AdasInfo(speedLimitWarning = enable))
                Thread({
                    val ok = client.setSpeedLimitWarning(enable)
                    root.post {
                        if (!ok) { vehicleState = vehicleState.copy(adas = AdasInfo(speedLimitWarning = prev)); toast("ADAS control failed.") }
                        renderTabContent()
                    }
                }, "SLW").apply { isDaemon = true; start() }
                renderTabContent()
            }
        })
        return panel
    }

    // ─── Tab: Charging ───────────────────────────────────────────────────────

    private fun buildChargingTab(): LinearLayout {
        val panel = makePanel()
        panel.addView(sectionTitle("Charging"))
        panel.addView(spacer(dp(12)))

        val cap = vehicleState.chargeCap
        // Smart charging schedule not available (cloud not configured)
        panel.addView(infoLabel("Smart charging schedule requires cloud configuration (not currently available)."))
        panel.addView(spacer(dp(12)))

        if (cap.supported == false) {
            panel.addView(infoLabel("Charge cap is not supported by this vehicle."))
            return panel
        }

        panel.addView(labelText("Charge Limit"))
        panel.addView(spacer(dp(6)))
        panel.addView(buildToggleRow("Enable Charge Limit", cap.enabled) { enable ->
            if (debounce("cap_toggle")) {
                val prev = cap.enabled
                vehicleState = vehicleState.copy(chargeCap = cap.copy(enabled = enable))
                Thread({
                    val ok = client.setChargeCapEnabled(enable)
                    root.post {
                        if (!ok) { vehicleState = vehicleState.copy(chargeCap = vehicleState.chargeCap.copy(enabled = prev)); toast("Charge limit toggle failed.") }
                        renderTabContent()
                    }
                }, "CapToggle").apply { isDaemon = true; start() }
                renderTabContent()
            }
        })

        if (cap.enabled || cap.supported == null) {
            panel.addView(spacer(dp(8)))
            panel.addView(buildStepper(
                label = "Charge Limit",
                value = "${cap.percent}%",
                onMinus = {
                    if (debounce("cap_minus") && cap.percent > 50) {
                        val newPct = (cap.percent - 5).coerceAtLeast(50)
                        vehicleState = vehicleState.copy(chargeCap = cap.copy(percent = newPct))
                        Thread({ client.setChargeCapPercent(newPct); root.post { renderTabContent() } }, "CapPct").apply { isDaemon = true; start() }
                        renderTabContent()
                    }
                },
                onPlus = {
                    if (debounce("cap_plus") && cap.percent < 100) {
                        val newPct = (cap.percent + 5).coerceAtMost(100)
                        vehicleState = vehicleState.copy(chargeCap = cap.copy(percent = newPct))
                        Thread({ client.setChargeCapPercent(newPct); root.post { renderTabContent() } }, "CapPct").apply { isDaemon = true; start() }
                        renderTabContent()
                    }
                }
            ))
            panel.addView(spacer(dp(4)))
            panel.addView(infoLabel("Minimum 50%, maximum 100%"))
        }

        return panel
    }

    // ─── Polling ─────────────────────────────────────────────────────────────

    private fun startPolling() {
        Thread({
            // Initial charge cap probe
            client.fetchChargeCap()?.let { cap ->
                root.post { vehicleState = vehicleState.copy(chargeCap = cap) }
            }

            val sched = Executors.newSingleThreadScheduledExecutor()
            pollExecutor = sched
            var failCount = 0
            sched.scheduleAtFixedRate({
                if (!running.get()) { sched.shutdownNow(); return@scheduleAtFixedRate }
                val state = client.fetchState()
                if (state == null) {
                    failCount++
                    if (failCount >= 3) root.post {
                        if (running.get()) showErrorState("Vehicle data unavailable. Check daemon status.")
                    }
                    return@scheduleAtFixedRate
                }
                failCount = 0
                root.post {
                    if (running.get()) {
                        vehicleState = state.copy(chargeCap = vehicleState.chargeCap)
                        applyStateToViews()
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

    private fun doVehicleAction(key: String, action: () -> Boolean) {
        if (!debounce(key)) return
        if (!inFlight.compareAndSet(false, true)) return
        renderTabContent()
        Thread({
            val ok = action()
            inFlight.set(false)
            root.post {
                if (!ok) toast("Action failed. Check vehicle connection.")
                renderTabContent()
            }
        }, "VehicleAction-$key").apply { isDaemon = true; start() }
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

    // ─── UI component builders ───────────────────────────────────────────────

    private fun makePanel() = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun makeTwoColGrid(vararg views: View): LinearLayout {
        val grid = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        var i = 0
        while (i < views.size) {
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            views[i].layoutParams = lp
            row.addView(views[i])
            if (i + 1 < views.size) {
                row.addView(spacer2(dp(8)))
                views[i + 1].layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                row.addView(views[i + 1])
            }
            grid.addView(row)
            if (i + 2 < views.size) grid.addView(spacer(dp(8)))
            i += 2
        }
        return grid
    }

    private fun buildActionButton(label: String, color: Int, onClick: () -> Unit) = TextView(context).apply {
        text = label; textSize = 14f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        setPadding(dp(12), dp(14), dp(12), dp(14))
        background = GradientDrawable().apply { setColor(color); cornerRadius = dp(8).toFloat() }
        alpha = if (inFlight.get()) 0.5f else 1.0f
        setOnClickListener { onClick() }
    }

    private fun buildWideButton(label: String, color: Int, onClick: () -> Unit) = TextView(context).apply {
        text = label; textSize = 14f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        setPadding(dp(16), dp(14), dp(16), dp(14))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        background = GradientDrawable().apply { setColor(color); cornerRadius = dp(8).toFloat() }
        setOnClickListener { onClick() }
    }

    private fun buildSmallPresetButton(label: String, active: Boolean, onClick: () -> Unit) = TextView(context).apply {
        text = label; textSize = 11f; gravity = Gravity.CENTER
        setTextColor(if (active) Color.WHITE else textColor())
        setPadding(dp(8), dp(6), dp(8), dp(6))
        background = GradientDrawable().apply {
            cornerRadius = dp(6).toFloat()
            setColor(if (active) accentColor() else pillBgColor())
        }
        setOnClickListener { onClick() }
    }

    private fun buildCycleButton(label: String, color: Int, onClick: () -> Unit) = TextView(context).apply {
        text = label; textSize = 12f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        setPadding(dp(10), dp(8), dp(10), dp(8))
        background = GradientDrawable().apply { setColor(color); cornerRadius = dp(6).toFloat() }
        setOnClickListener { onClick() }
    }

    private fun buildStepper(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        row.addView(TextView(context).apply {
            text = label; textSize = 13f; setTextColor(textColor())
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(buildRoundButton("−", onMinus))
        row.addView(TextView(context).apply {
            text = value; textSize = 14f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setTextColor(textColor())
            layoutParams = LinearLayout.LayoutParams(dp(72), ViewGroup.LayoutParams.WRAP_CONTENT)
        })
        row.addView(buildRoundButton("+", onPlus))
        return row
    }

    private fun buildRoundButton(label: String, onClick: () -> Unit) = TextView(context).apply {
        text = label; textSize = 18f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
        layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(accentColor()) }
        setOnClickListener { onClick() }
    }

    private fun buildToggleRow(label: String, enabled: Boolean, onToggle: (Boolean) -> Unit): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply { setColor(surfaceColor()); cornerRadius = dp(8).toFloat() }
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        row.addView(TextView(context).apply {
            text = label; textSize = 14f; setTextColor(textColor())
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(buildTogglePill(enabled) { onToggle(!enabled) })
        return row
    }

    private fun buildTogglePill(enabled: Boolean, onClick: () -> Unit) = TextView(context).apply {
        text = if (enabled) "ON" else "OFF"
        textSize = 12f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        setPadding(dp(14), dp(6), dp(14), dp(6))
        background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(if (enabled) accentColor() else Color.parseColor("#607D8B"))
        }
        setOnClickListener { onClick() }
    }

    private fun sectionTitle(text: String) = TextView(context).apply {
        this.text = text; textSize = 17f; typeface = Typeface.DEFAULT_BOLD; setTextColor(textColor())
    }

    private fun labelText(text: String) = TextView(context).apply {
        this.text = text; textSize = 13f; setTextColor(mutedColor())
    }

    private fun centeredLabel(text: String) = TextView(context).apply {
        this.text = text; textSize = 13f; gravity = Gravity.CENTER; setTextColor(mutedColor())
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) }
    }

    private fun infoLabel(text: String) = TextView(context).apply {
        this.text = text; textSize = 12f; setTextColor(mutedColor())
        background = GradientDrawable().apply {
            setColor(if (isDark()) Color.parseColor("#1A2A1A") else Color.parseColor("#E8F5E9"))
            cornerRadius = dp(6).toFloat()
        }
        setPadding(dp(10), dp(8), dp(10), dp(8))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun spacer(h: Int) = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h)
    }

    private fun spacer2(w: Int) = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    // ─── Theme ───────────────────────────────────────────────────────────────

    private fun isDark(): Boolean {
        val mode = if (PreferencesManager.isInitialized()) PreferencesManager.getThemeMode()
                   else AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        return when (mode) {
            AppCompatDelegate.MODE_NIGHT_YES -> true
            AppCompatDelegate.MODE_NIGHT_NO -> false
            else -> (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        }
    }

    private fun bgColor() = if (isDark()) Color.parseColor("#121212") else Color.parseColor("#F5F5F5")
    private fun surfaceColor() = if (isDark()) Color.parseColor("#1E1E1E") else Color.WHITE
    private fun textColor() = if (isDark()) Color.WHITE else Color.parseColor("#212121")
    private fun mutedColor() = if (isDark()) Color.parseColor("#AAAAAA") else Color.parseColor("#666666")
    private fun dividerColor() = if (isDark()) Color.parseColor("#333333") else Color.parseColor("#E0E0E0")
    private fun pillBgColor() = if (isDark()) Color.parseColor("#2C2C2C") else Color.parseColor("#EEEEEE")
    private fun accentColor() = Color.parseColor("#4CAF50")

    private fun heatLabel(level: Int) = when (level) { 1 -> "(Low)"; 2 -> "(High)"; else -> "(Off)" }

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density + 0.5f).toInt()
}
