package net.bladewatch.app.ui.fragment.vehicle

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import net.bladewatch.app.R

data class PanelResult(val view: LinearLayout, val update: (PanelContext) -> Unit)

class PanelContext(
    val factory: VehicleViewFactory,
    val state: VehicleState,
    val acOn: Boolean, val setpointC: Int, val fanLevel: Int, val maxCooling: Boolean,
    val driverHeat: Int, val driverVent: Int, val passengerHeat: Int, val passengerVent: Int,
    val isPending: (String) -> Boolean,
    val debounce: (String) -> Boolean,
    val post: (() -> Unit) -> Unit,
    val toast: (String) -> Unit,
    val refresh: () -> Unit,
    val doAction: (String, View?, () -> VehicleCommandResult) -> Unit,
    val updateState: (VehicleState) -> Unit,
    val updateClimate: (acOn: Boolean, setpointC: Int, fanLevel: Int, maxCooling: Boolean) -> Unit,
    val updateSeats: (driverHeat: Int, driverVent: Int, passengerHeat: Int, passengerVent: Int) -> Unit,
    val client: VehicleClient,
)

// ─── Pending helpers ──────────────────────────────────────────────────────────

private fun applyPending(btn: TextView, pending: Boolean) {
    btn.alpha = if (pending) 0.5f else 1.0f
    btn.isClickable = !pending
}

// ─── Toggle pill helpers ─────────────────────────────────────────────────────

private fun updatePill(pill: TextView, enabled: Boolean, factory: VehicleViewFactory) {
    val stateStr = if (enabled) factory.context.getString(R.string.vehicle_toggle_on) else factory.context.getString(R.string.vehicle_toggle_off)
    pill.text = stateStr
    val labelTag = pill.tag as? String
    pill.contentDescription = if (labelTag != null) "$labelTag: $stateStr" else stateStr
    val gd = GradientDrawable().apply {
        cornerRadius = factory.dp(12).toFloat()
        setColor(if (enabled) factory.accentColor() else Color.parseColor("#607D8B"))
    }
    pill.background = android.graphics.drawable.RippleDrawable(
        android.content.res.ColorStateList.valueOf(Color.argb(60, 255, 255, 255)), gd, null)
}

// ─── Trunk ───────────────────────────────────────────────────────────────────

fun buildTrunkTab(ctx: PanelContext): PanelResult {
    val factory = ctx.factory
    val panel = factory.makePanel()
    val str = factory.context
    panel.addView(factory.sectionTitle(str.getString(R.string.vehicle_tab_trunk)))
    panel.addView(factory.spacer(factory.dp(8)))
    panel.addView(factory.infoLabel(str.getString(R.string.vehicle_trunk_info_open)))
    panel.addView(factory.spacer(factory.dp(12)))

    val openBtn = factory.buildActionButton(str.getString(R.string.vehicle_open_trunk), Color.parseColor("#FF9800"), ctx.isPending("trunk_open")) {
        ctx.doAction("trunk_open", null) { ctx.client.trunkOpen() }
    }
    val closeBtn = factory.buildActionButton(str.getString(R.string.vehicle_close_trunk), Color.parseColor("#607D8B"), ctx.isPending("trunk_close")) {
        ctx.doAction("trunk_close", null) { ctx.client.trunkClose() }
    }
    panel.addView(factory.makeTwoColGrid(openBtn, closeBtn))

    return PanelResult(panel) { newCtx ->
        applyPending(openBtn, newCtx.isPending("trunk_open"))
        applyPending(closeBtn, newCtx.isPending("trunk_close"))
    }
}

// ─── Climate ─────────────────────────────────────────────────────────────────

fun buildClimateTab(ctx: PanelContext): PanelResult {
    val factory = ctx.factory
    val str = factory.context
    val panel = factory.makePanel()

    // Inside-temp caption (compact, no section title — the tab already says Climate).
    val insideTempTv = factory.labelText(
        ctx.state.climate.insideTempC?.let { str.getString(R.string.vehicle_inside_temp_fmt, it) } ?: ""
    ).also { tv ->
        tv.visibility = if (ctx.state.climate.insideTempC != null) View.VISIBLE else View.GONE
        panel.addView(tv)
        panel.addView(factory.spacer(factory.dp(6)))
    }

    // AC button
    fun acColor(on: Boolean) = if (on) Color.parseColor("#2196F3") else Color.parseColor("#607D8B")
    val acBtn = factory.buildWideButton(if (ctx.acOn) str.getString(R.string.vehicle_ac_on) else str.getString(R.string.vehicle_ac_off), acColor(ctx.acOn)) {
        if (ctx.debounce("ac_toggle")) {
            val nowOn = !ctx.acOn
            ctx.updateClimate(nowOn, ctx.setpointC, ctx.fanLevel, ctx.maxCooling)
            Thread({
                val result = if (nowOn) ctx.client.climateOn(ctx.setpointC) else ctx.client.climateOff()
                ctx.post {
                    if (!result.ok) { ctx.updateClimate(!nowOn, ctx.setpointC, ctx.fanLevel, ctx.maxCooling); ctx.toast(result.message ?: ctx.factory.context.getString(R.string.vehicle_err_climate_control)) }
                    ctx.refresh()
                }
            }, "ClimateAC").apply { isDaemon = true; start() }
        }
    }
    // Max cooling button
    fun maxColor(on: Boolean) = if (on) Color.parseColor("#F44336") else Color.parseColor("#607D8B")
    val maxCoolBtn = factory.buildWideButton(
        if (ctx.maxCooling) str.getString(R.string.vehicle_max_cooling_on) else str.getString(R.string.vehicle_max_cooling_off), maxColor(ctx.maxCooling)
    ) {
        if (ctx.debounce("max_cooling")) {
            val capturedAcOn = ctx.acOn; val capturedTemp = ctx.setpointC; val capturedFan = ctx.fanLevel
            val nowMax = !ctx.maxCooling
            ctx.updateClimate(ctx.acOn, ctx.setpointC, ctx.fanLevel, nowMax)
            Thread({
                val result = ctx.client.climateMaxCooling(nowMax, capturedAcOn, capturedTemp, capturedFan)
                ctx.post {
                    if (!result.ok) { ctx.updateClimate(ctx.acOn, ctx.setpointC, ctx.fanLevel, !nowMax); ctx.toast(result.message ?: ctx.factory.context.getString(R.string.vehicle_err_max_cooling)) }
                    else if (nowMax) ctx.updateClimate(true, 17, 7, nowMax)
                    ctx.refresh()
                }
            }, "ClimateMaxCool").apply { isDaemon = true; start() }
        }
    }
    // Row 1: AC | Max Cooling side by side (uses the wide landscape width).
    panel.addView(factory.rowOf(acBtn, maxCoolBtn))
    panel.addView(factory.spacer(factory.dp(8)))

    val (tempRow, tempValueTv) = factory.buildStepperResult(
        label = str.getString(R.string.vehicle_temp_label),
        value = VehicleFormatters.formatTemp(ctx.setpointC, ctx.state.loaded),
        onMinus = {
            if (ctx.debounce("temp_minus") && ctx.setpointC > 17) {
                val newTemp = ctx.setpointC - 1
                ctx.updateClimate(ctx.acOn, newTemp, ctx.fanLevel, ctx.maxCooling)
                Thread({ ctx.client.climateSetTemp(newTemp); ctx.post { ctx.refresh() } }, "ClimateTemp").apply { isDaemon = true; start() }
                ctx.refresh()
            }
        },
        onPlus = {
            if (ctx.debounce("temp_plus") && ctx.setpointC < 33) {
                val newTemp = ctx.setpointC + 1
                ctx.updateClimate(ctx.acOn, newTemp, ctx.fanLevel, ctx.maxCooling)
                Thread({ ctx.client.climateSetTemp(newTemp); ctx.post { ctx.refresh() } }, "ClimateTemp").apply { isDaemon = true; start() }
                ctx.refresh()
            }
        }
    )
    val (fanRow, fanValueTv) = factory.buildStepperResult(
        label = str.getString(R.string.vehicle_fan_speed_label),
        value = VehicleFormatters.formatFan(str.getString(R.string.vehicle_fan_level), ctx.fanLevel, ctx.state.loaded),
        onMinus = {
            if (ctx.debounce("fan_minus") && ctx.fanLevel > 1) {
                val newFan = ctx.fanLevel - 1
                ctx.updateClimate(ctx.acOn, ctx.setpointC, newFan, ctx.maxCooling)
                Thread({ ctx.client.climateSetFan(newFan); ctx.post { ctx.refresh() } }, "ClimateFan").apply { isDaemon = true; start() }
                ctx.refresh()
            }
        },
        onPlus = {
            if (ctx.debounce("fan_plus") && ctx.fanLevel < 7) {
                val newFan = ctx.fanLevel + 1
                ctx.updateClimate(ctx.acOn, ctx.setpointC, newFan, ctx.maxCooling)
                Thread({ ctx.client.climateSetFan(newFan); ctx.post { ctx.refresh() } }, "ClimateFan").apply { isDaemon = true; start() }
                ctx.refresh()
            }
        }
    )
    // Row 2: temperature stepper | fan stepper, each in a faint glass cell.
    fun stepperCell(inner: View) = LinearLayout(factory.context).apply {
        background = factory.glassCell(14)
        setPadding(factory.dp(10), factory.dp(2), factory.dp(10), factory.dp(2))
        addView(inner, LinearLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    panel.addView(factory.rowOf(stepperCell(tempRow), stepperCell(fanRow)))

    return PanelResult(panel) { newCtx ->
        val newStr = factory.context
        // Inside temp
        val tempStr = newCtx.state.climate.insideTempC?.let { newStr.getString(R.string.vehicle_inside_temp_fmt, it) }
        insideTempTv.visibility = if (tempStr != null) View.VISIBLE else View.GONE
        if (tempStr != null) insideTempTv.text = tempStr
        // AC button
        acBtn.text = if (newCtx.acOn) newStr.getString(R.string.vehicle_ac_on) else newStr.getString(R.string.vehicle_ac_off)
        val acBase = GradientDrawable().apply { setColor(acColor(newCtx.acOn)); cornerRadius = factory.dp(8).toFloat() }
        acBtn.background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(Color.argb(60, 255, 255, 255)), acBase, null)
        // Max cooling button
        maxCoolBtn.text = if (newCtx.maxCooling) newStr.getString(R.string.vehicle_max_cooling_on) else newStr.getString(R.string.vehicle_max_cooling_off)
        val maxBase = GradientDrawable().apply { setColor(maxColor(newCtx.maxCooling)); cornerRadius = factory.dp(8).toFloat() }
        maxCoolBtn.background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(Color.argb(60, 255, 255, 255)), maxBase, null)
        // Stepper values
        tempValueTv.text = VehicleFormatters.formatTemp(newCtx.setpointC, newCtx.state.loaded)
        fanValueTv.text = VehicleFormatters.formatFan(newStr.getString(R.string.vehicle_fan_level), newCtx.fanLevel, newCtx.state.loaded)
    }
}

// ─── Seats ───────────────────────────────────────────────────────────────────

fun buildSeatsTab(ctx: PanelContext): PanelResult {
    val factory = ctx.factory
    val str = factory.context
    val panel = factory.makePanel()
    panel.addView(factory.sectionTitle(str.getString(R.string.vehicle_tab_seats)))
    panel.addView(factory.spacer(factory.dp(12)))
    val caps = ctx.state.capabilities.seats

    data class SeatRowRefs(val heatBtn: TextView?, val coolBtn: TextView?)
    val seatRefs = mutableMapOf<Int, SeatRowRefs>()

    fun seatRow(title: String, heat: Int, cool: Int, hasHeat: Boolean, hasCool: Boolean, hasMemory: Boolean, position: Int): LinearLayout {
        val row = LinearLayout(factory.context).apply { orientation = LinearLayout.VERTICAL }
        row.addView(factory.labelText(title))
        row.addView(factory.spacer(factory.dp(6)))
        val btns = LinearLayout(factory.context).apply { orientation = LinearLayout.HORIZONTAL }

        var heatBtnRef: TextView? = null
        var coolBtnRef: TextView? = null

        if (hasHeat) {
            val btn = factory.buildCycleButton(str.getString(R.string.vehicle_seat_heat_label, factory.heatLabel(heat)), factory.accentColor()) {
                if (ctx.debounce("seat_heat_$position")) {
                    val newLevel = (heat + 1) % 3
                    val newDriverHeat = if (position == 1) newLevel else ctx.driverHeat
                    val newDriverVent = if (position == 1 && newLevel > 0) 0 else ctx.driverVent
                    val newPassengerHeat = if (position == 2) newLevel else ctx.passengerHeat
                    val newPassengerVent = if (position == 2 && newLevel > 0) 0 else ctx.passengerVent
                    ctx.updateSeats(newDriverHeat, newDriverVent, newPassengerHeat, newPassengerVent)
                    val dH = newDriverHeat; val dV = newDriverVent; val pH = newPassengerHeat; val pV = newPassengerVent
                    Thread({ ctx.client.seatSetHeat(position, newLevel, dH, dV, pH, pV); ctx.post { ctx.refresh() } }, "SeatHeat").apply { isDaemon = true; start() }
                    ctx.refresh()
                }
            }
            heatBtnRef = btn
            btns.addView(btn)
            btns.addView(factory.spacer2(factory.dp(8)))
        }
        if (hasCool) {
            val btn = factory.buildCycleButton(str.getString(R.string.vehicle_seat_cool_label, factory.heatLabel(cool)), Color.parseColor("#2196F3")) {
                if (ctx.debounce("seat_cool_$position")) {
                    val newLevel = (cool + 1) % 3
                    val newDriverVent = if (position == 1) newLevel else ctx.driverVent
                    val newDriverHeat = if (position == 1 && newLevel > 0) 0 else ctx.driverHeat
                    val newPassengerVent = if (position == 2) newLevel else ctx.passengerVent
                    val newPassengerHeat = if (position == 2 && newLevel > 0) 0 else ctx.passengerHeat
                    ctx.updateSeats(newDriverHeat, newDriverVent, newPassengerHeat, newPassengerVent)
                    val dH = newDriverHeat; val dV = newDriverVent; val pH = newPassengerHeat; val pV = newPassengerVent
                    Thread({ ctx.client.seatSetCool(position, newLevel, dH, dV, pH, pV); ctx.post { ctx.refresh() } }, "SeatCool").apply { isDaemon = true; start() }
                    ctx.refresh()
                }
            }
            coolBtnRef = btn
            btns.addView(btn)
        }
        if (hasMemory && position == 1) {
            btns.addView(factory.spacer2(factory.dp(8)))
            btns.addView(factory.buildCycleButton(str.getString(R.string.vehicle_seat_pos_1), Color.parseColor("#9C27B0")) {
                if (ctx.debounce("seat_mem1")) ctx.doAction("seat_mem1", null) { ctx.client.seatRecallPosition(1) }
            })
            btns.addView(factory.spacer2(factory.dp(4)))
            btns.addView(factory.buildCycleButton(str.getString(R.string.vehicle_seat_pos_2), Color.parseColor("#9C27B0")) {
                if (ctx.debounce("seat_mem2")) ctx.doAction("seat_mem2", null) { ctx.client.seatRecallPosition(2) }
            })
        }
        row.addView(btns)
        seatRefs[position] = SeatRowRefs(heatBtnRef, coolBtnRef)
        return row
    }

    if (caps.driverHeat || caps.driverCool || caps.driverMemoryRecall) {
        panel.addView(seatRow(str.getString(R.string.vehicle_seat_driver), ctx.driverHeat, ctx.driverVent, caps.driverHeat, caps.driverCool, caps.driverMemoryRecall, 1))
        panel.addView(factory.spacer(factory.dp(12)))
    }
    if (caps.passengerHeat || caps.passengerCool) {
        panel.addView(seatRow(str.getString(R.string.vehicle_seat_passenger), ctx.passengerHeat, ctx.passengerVent, caps.passengerHeat, caps.passengerCool, false, 2))
    }
    if (!caps.anyAvailable()) {
        panel.addView(factory.centeredLabel(str.getString(R.string.vehicle_seat_no_controls)))
    }

    return PanelResult(panel) { newCtx ->
        val newStr = factory.context
        seatRefs[1]?.heatBtn?.text = newStr.getString(R.string.vehicle_seat_heat_label, factory.heatLabel(newCtx.driverHeat))
        seatRefs[1]?.coolBtn?.text = newStr.getString(R.string.vehicle_seat_cool_label, factory.heatLabel(newCtx.driverVent))
        seatRefs[2]?.heatBtn?.text = newStr.getString(R.string.vehicle_seat_heat_label, factory.heatLabel(newCtx.passengerHeat))
        seatRefs[2]?.coolBtn?.text = newStr.getString(R.string.vehicle_seat_cool_label, factory.heatLabel(newCtx.passengerVent))
    }
}

// ─── Windows ─────────────────────────────────────────────────────────────────

fun buildWindowsTab(ctx: PanelContext): PanelResult {
    val factory = ctx.factory
    val str = factory.context
    val panel = factory.makePanel()
    val w = ctx.state.windows
    val caps = ctx.state.capabilities.windows

    data class WindowRowRefs(val label: TextView, val presetBtns: Map<Int, TextView>)
    val rowRefs = mutableListOf<WindowRowRefs>()

    // One window control as a compact glass cell: name+% label over a row of
    // preset buttons. Returns the cell so callers can pack them into a grid.
    fun windowCell(name: String, area: Int, current: Int): View {
        val cell = LinearLayout(factory.context).apply {
            orientation = LinearLayout.VERTICAL
            background = factory.glassCell(14)
            setPadding(factory.dp(10), factory.dp(8), factory.dp(10), factory.dp(8))
        }
        val pctLabel = factory.labelText("$name (${VehicleFormatters.formatWindowPct(current)})")
        cell.addView(pctLabel)
        cell.addView(factory.spacer(factory.dp(4)))
        val btns = LinearLayout(factory.context).apply { orientation = LinearLayout.HORIZONTAL }
        val presetBtnRefs = mutableMapOf<Int, TextView>()
        for (pct in listOf(0, 25, 50, 75, 100)) {
            val snapTarget = VehicleFormatters.presetFor(current)
            val isActive = snapTarget == pct
            val btn = factory.buildSmallPresetButton("$pct%", isActive) {
                ctx.doAction("win_${area}_$pct", null) { ctx.client.windowSetPercent(area, pct) }
            }
            presetBtnRefs[pct] = btn
            btns.addView(btn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (pct != 0) marginStart = factory.dp(4)
            })
        }
        cell.addView(btns)
        rowRefs.add(WindowRowRefs(pctLabel, presetBtnRefs))
        return cell
    }

    // 2×2 grid: FL | FR over RL | RR — mirrors the car, halves the height.
    panel.addView(factory.rowOf(
        windowCell(str.getString(R.string.vehicle_window_front_left), 1, w.lf),
        windowCell(str.getString(R.string.vehicle_window_front_right), 2, w.rf)))
    panel.addView(factory.spacer(factory.dp(8)))
    panel.addView(factory.rowOf(
        windowCell(str.getString(R.string.vehicle_window_rear_left), 3, w.lr),
        windowCell(str.getString(R.string.vehicle_window_rear_right), 4, w.rr)))
    panel.addView(factory.spacer(factory.dp(8)))

    panel.addView(factory.labelText(str.getString(R.string.vehicle_all_windows)))
    panel.addView(factory.spacer(factory.dp(4)))
    val allRow = LinearLayout(factory.context).apply { orientation = LinearLayout.HORIZONTAL }
    allRow.addView(factory.buildSmallPresetButton(str.getString(R.string.vehicle_window_close), false) {
        ctx.doAction("win_all_close", null) { ctx.client.windowsAllClose() }
    })
    allRow.addView(factory.spacer2(factory.dp(6)))
    val isVented = listOf(w.lf, w.rf, w.lr, w.rr).filter { it != -1 }.let { vals ->
        vals.isNotEmpty() && vals.all { it in 1..20 }
    }
    allRow.addView(factory.buildSmallPresetButton(if (isVented) str.getString(R.string.vehicle_window_close_vent) else str.getString(R.string.vehicle_window_vent_12), isVented) {
        val target = if (isVented) 0 else 12
        ctx.doAction("win_vent", null) { ctx.client.windowsAllVent(target) }
    })
    allRow.addView(factory.spacer2(factory.dp(6)))
    allRow.addView(factory.buildSmallPresetButton(str.getString(R.string.vehicle_window_open_all), false) {
        ctx.doAction("win_all_open", null) { ctx.client.windowsAllOpen() }
    })
    panel.addView(allRow)

    // Sunroof / sunshade (when equipped) — side by side if both present.
    val extras = mutableListOf<View>()
    if (caps.sunroof) extras.add(windowCell(str.getString(R.string.vehicle_sunroof), 5, w.sunroof))
    if (caps.sunshade) extras.add(windowCell(str.getString(R.string.vehicle_sunshade), 6, w.sunshade))
    if (extras.isNotEmpty()) {
        panel.addView(factory.spacer(factory.dp(8)))
        panel.addView(if (extras.size == 2) factory.rowOf(extras[0], extras[1]) else extras[0])
    }

    return PanelResult(panel) { newCtx ->
        val newStr = factory.context
        val newW = newCtx.state.windows
        val newValues = listOf(newW.lf, newW.rf, newW.lr, newW.rr) +
                (if (caps.sunroof) listOf(newW.sunroof) else emptyList()) +
                (if (caps.sunshade) listOf(newW.sunshade) else emptyList())
        val names = listOf(
            newStr.getString(R.string.vehicle_window_front_left),
            newStr.getString(R.string.vehicle_window_front_right),
            newStr.getString(R.string.vehicle_window_rear_left),
            newStr.getString(R.string.vehicle_window_rear_right)
        ) +
                (if (caps.sunroof) listOf(newStr.getString(R.string.vehicle_sunroof)) else emptyList()) +
                (if (caps.sunshade) listOf(newStr.getString(R.string.vehicle_sunshade)) else emptyList())
        rowRefs.forEachIndexed { i, refs ->
            val v = newValues.getOrElse(i) { -1 }
            val n = names.getOrElse(i) { "" }
            refs.label.text = "$n (${VehicleFormatters.formatWindowPct(v)})"
            val snap = VehicleFormatters.presetFor(v)
            refs.presetBtns.forEach { (pct, btn) ->
                val active = snap == pct
                btn.setTextColor(if (active) android.graphics.Color.WHITE else factory.textColor())
                val base = GradientDrawable().apply {
                    cornerRadius = factory.dp(6).toFloat()
                    setColor(if (active) factory.accentColor() else factory.pillBgColor())
                }
                btn.background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(Color.argb(60, 255, 255, 255)), base, null)
            }
        }
        // pending states for bulk window ops
        applyPending(allRow.getChildAt(0) as? TextView ?: return@PanelResult, newCtx.isPending("win_all_close"))
        applyPending(allRow.getChildAt(2) as? TextView ?: return@PanelResult, newCtx.isPending("win_vent"))
        applyPending(allRow.getChildAt(4) as? TextView ?: return@PanelResult, newCtx.isPending("win_all_open"))
    }
}

// ─── Lights ──────────────────────────────────────────────────────────────────

fun buildLightsTab(ctx: PanelContext): PanelResult {
    val factory = ctx.factory
    val str = factory.context
    val panel = factory.makePanel()
    panel.addView(factory.sectionTitle(str.getString(R.string.vehicle_tab_lights)))
    panel.addView(factory.spacer(factory.dp(12)))

    val drl = ctx.state.lights.dayTimeLight
    val (rowView, pill) = factory.buildToggleRow(str.getString(R.string.vehicle_btn_drl_title), drl) { enable ->
        if (ctx.debounce("drl")) {
            val prev = ctx.state.lights.dayTimeLight
            ctx.updateState(ctx.state.copy(lights = LightsInfo(dayTimeLight = enable)))
            Thread({
                val result = ctx.client.setDrl(enable)
                ctx.post {
                    if (!result.ok) { ctx.updateState(ctx.state.copy(lights = LightsInfo(dayTimeLight = prev))); ctx.toast(result.message ?: ctx.factory.context.getString(R.string.vehicle_err_drl_control)) }
                    ctx.refresh()
                }
            }, "DRL").apply { isDaemon = true; start() }
            ctx.refresh()
        }
    }
    panel.addView(rowView)

    return PanelResult(panel) { newCtx ->
        updatePill(pill, newCtx.state.lights.dayTimeLight, factory)
    }
}

// ─── ADAS ─────────────────────────────────────────────────────────────────────

fun buildAdasTab(ctx: PanelContext): PanelResult {
    val factory = ctx.factory
    val str = factory.context
    val panel = factory.makePanel()
    panel.addView(factory.sectionTitle(str.getString(R.string.vehicle_tab_adas)))
    panel.addView(factory.spacer(factory.dp(12)))

    val slw = ctx.state.adas.speedLimitWarning
    val (rowView, pill) = factory.buildToggleRow(str.getString(R.string.vehicle_btn_slw_title), slw) { enable ->
        if (ctx.debounce("slw")) {
            val prev = slw
            ctx.updateState(ctx.state.copy(adas = AdasInfo(speedLimitWarning = enable)))
            Thread({
                val result = ctx.client.setSpeedLimitWarning(enable)
                ctx.post {
                    if (!result.ok) { ctx.updateState(ctx.state.copy(adas = AdasInfo(speedLimitWarning = prev))); ctx.toast(result.message ?: ctx.factory.context.getString(R.string.vehicle_err_slw_control)) }
                    ctx.refresh()
                }
            }, "SLW").apply { isDaemon = true; start() }
            ctx.refresh()
        }
    }
    panel.addView(rowView)

    return PanelResult(panel) { newCtx ->
        updatePill(pill, newCtx.state.adas.speedLimitWarning, factory)
    }
}

// ─── Charging ────────────────────────────────────────────────────────────────

fun buildChargingTab(ctx: PanelContext): PanelResult {
    val factory = ctx.factory
    val str = factory.context
    val panel = factory.makePanel()
    panel.addView(factory.sectionTitle(str.getString(R.string.vehicle_control_charging_tab)))
    panel.addView(factory.spacer(factory.dp(12)))

    val cap = ctx.state.chargeCap

    if (cap.supported == false) {
        panel.addView(factory.infoLabel(str.getString(R.string.vehicle_charge_cap_not_supported)))
        return PanelResult(panel) { _ -> /* nothing to update */ }
    }

    panel.addView(factory.labelText(str.getString(R.string.vehicle_charge_limit_label)))
    panel.addView(factory.spacer(factory.dp(6)))

    val (toggleRow, pill) = factory.buildToggleRow(str.getString(R.string.vehicle_enable_charge_limit), cap.enabled ?: false) { enable ->
        if (ctx.debounce("cap_toggle")) {
            val prev = cap.enabled
            ctx.updateState(ctx.state.copy(chargeCap = cap.copy(enabled = enable)))
            Thread({
                val result = ctx.client.setChargeCapEnabled(enable)
                ctx.post {
                    if (!result.ok) { ctx.updateState(ctx.state.copy(chargeCap = ctx.state.chargeCap.copy(enabled = prev))); ctx.toast(result.message ?: ctx.factory.context.getString(R.string.vehicle_err_charge_limit_toggle)) }
                    ctx.refresh()
                }
            }, "CapToggle").apply { isDaemon = true; start() }
            ctx.refresh()
        }
    }
    panel.addView(toggleRow)

    var stepperRow: LinearLayout? = null
    var stepperValueTv: TextView? = null
    val stepperContainer = LinearLayout(factory.context).apply {
        orientation = LinearLayout.VERTICAL
        visibility = if (cap.enabled == true || cap.supported == null) View.VISIBLE else View.GONE
    }
    if (cap.enabled == true || cap.supported == null) {
        val pct = cap.percent ?: 80
        val (row, valueTv) = factory.buildStepperResult(
            label = str.getString(R.string.vehicle_control_section_charge_cap),
            value = "$pct%",
            onMinus = {
                if (ctx.debounce("cap_minus") && pct > 50) {
                    val newPct = (pct - 5).coerceAtLeast(50)
                    ctx.updateState(ctx.state.copy(chargeCap = cap.copy(percent = newPct)))
                    Thread({ ctx.client.setChargeCapPercent(newPct); ctx.post { ctx.refresh() } }, "CapPct").apply { isDaemon = true; start() }
                    ctx.refresh()
                }
            },
            onPlus = {
                if (ctx.debounce("cap_plus") && pct < 100) {
                    val newPct = (pct + 5).coerceAtMost(100)
                    ctx.updateState(ctx.state.copy(chargeCap = cap.copy(percent = newPct)))
                    Thread({ ctx.client.setChargeCapPercent(newPct); ctx.post { ctx.refresh() } }, "CapPct").apply { isDaemon = true; start() }
                    ctx.refresh()
                }
            }
        )
        stepperRow = row; stepperValueTv = valueTv
        stepperContainer.addView(factory.spacer(factory.dp(8)))
        stepperContainer.addView(row)
        stepperContainer.addView(factory.spacer(factory.dp(4)))
        stepperContainer.addView(factory.infoLabel(str.getString(R.string.vehicle_charge_limit_range)))
    }
    panel.addView(stepperContainer)

    return PanelResult(panel) { newCtx ->
        val newCap = newCtx.state.chargeCap
        updatePill(pill, newCap.enabled ?: false, factory)
        val showStepper = newCap.enabled == true || newCap.supported == null
        stepperContainer.visibility = if (showStepper) View.VISIBLE else View.GONE
        stepperValueTv?.text = "${newCap.percent ?: 80}%"
    }
}
