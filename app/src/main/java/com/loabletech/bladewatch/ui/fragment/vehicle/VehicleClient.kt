package net.bladewatch.app.ui.fragment.vehicle

import android.util.Log
import com.connectrpc.ResponseMessage
import kotlinx.coroutines.runBlocking
import net.bladewatch.app.client.ConnectClientProvider
import net.bladewatch.app.grpc.v1.GetChargeCapRequest
import net.bladewatch.app.grpc.v1.GetVehicleStateRequest
import net.bladewatch.app.grpc.v1.VehicleCommandResponse
import net.bladewatch.app.grpc.v1.MoveWindowRequest
import net.bladewatch.app.grpc.v1.SetAdasRequest
import net.bladewatch.app.grpc.v1.SetChargeCapRequest
import net.bladewatch.app.grpc.v1.SetClimateRequest
import net.bladewatch.app.grpc.v1.SetLightsRequest
import net.bladewatch.app.grpc.v1.SetSeatRequest
import net.bladewatch.app.grpc.v1.TrunkRequest

class VehicleClient {

    // ─── State ───────────────────────────────────────────────────────────────

    fun fetchState(): VehicleState? = runBlocking {
        val resp = ConnectClientProvider.vehicleService().getState(
            GetVehicleStateRequest.newBuilder().build(), emptyMap()
        )
        if (resp !is ResponseMessage.Success || !resp.message.success) return@runBlocking null
        val m = resp.message
        val doors = DoorState(
            overall = m.doors.overall,
            lf = m.doors.lf, rf = m.doors.rf,
            lr = m.doors.lr, rr = m.doors.rr,
        )
        val windows = WindowState(
            lf = sanitizeWindow(m.windows.lf),
            rf = sanitizeWindow(m.windows.rf),
            lr = sanitizeWindow(m.windows.lr),
            rr = sanitizeWindow(m.windows.rr),
            sunroof = sanitizeWindow(m.windows.sunroof),
            sunshade = sanitizeWindow(m.windows.sunshade),
        )
        val caps = VehicleCapabilities(
            windows = WindowCapabilities(
                sunroof = m.capabilities.windows.sunroof,
                sunshade = m.capabilities.windows.sunshade,
            ),
            seats = SeatCapabilities(
                driverHeat = m.capabilities.seats.driverHeat,
                passengerHeat = m.capabilities.seats.passengerHeat,
                driverCool = m.capabilities.seats.driverCool,
                passengerCool = m.capabilities.seats.passengerCool,
                driverMemoryRecall = m.capabilities.seats.driverMemoryRecall,
            ),
        )
        val battery = BatteryInfo(soc = m.battery.soc.toInt(), rangeKm = m.battery.rangeKm)
        val lights = LightsInfo(dayTimeLight = m.lights.dayTimeLight)
        val adas = AdasInfo(speedLimitWarning = m.adas.speedLimitWarning)
        val seats = SeatsInfo(heat = m.seats.heatList.toList(), cool = m.seats.coolList.toList())
        val insideTemp = m.climate.insideTempC.takeIf { it != 0.0 }?.toFloat()
        val climate = ClimateInfo(
            acOn = m.climate.acOn,
            setpointC = m.climate.setpointC.toInt().coerceIn(16, 35),
            insideTempC = insideTemp,
            fanLevel = m.climate.fanLevel.coerceIn(1, 7),
            maxCooling = m.climate.maxCooling,
        )
        val tyres = TyreSetInfo(
            fl = mapTyre(m.tyres.fl),
            fr = mapTyre(m.tyres.fr),
            rl = mapTyre(m.tyres.rl),
            rr = mapTyre(m.tyres.rr),
        )
        VehicleState(doors = doors, windows = windows, capabilities = caps,
            battery = battery, lights = lights, adas = adas, seats = seats, climate = climate,
            tyres = tyres)
    }

    fun fetchChargeCap(): ChargingCapInfo? = runBlocking {
        val resp = ConnectClientProvider.vehicleService().getChargeCap(
            GetChargeCapRequest.newBuilder().build(), emptyMap()
        )
        if (resp !is ResponseMessage.Success || !resp.message.success) return@runBlocking null
        mapChargeCap(resp.message)
    }

    companion object {
        internal fun mapTyre(t: net.bladewatch.app.grpc.v1.TyrePressure): TyreInfo = TyreInfo(
            psi = t.psi.takeIf { it != 0.0 },
            kPa = t.kPa.takeIf { it != 0 },
            temperatureC = t.tempC.takeIf { it != 0 },
            pressureState = t.pressureState,
            airLeakState = t.leakState,
            signalState = t.signalState,
        )

        internal fun mapCommandResult(m: net.bladewatch.app.grpc.v1.VehicleCommandResponse): VehicleCommandResult = VehicleCommandResult(
            ok = m.success,
            outcome = m.outcome.takeIf { it.isNotBlank() },
            message = m.message.takeIf { it.isNotBlank() },
        )

        internal fun mapChargeCap(m: net.bladewatch.app.grpc.v1.GetChargeCapResponse): ChargingCapInfo = ChargingCapInfo(
            enabled = if (m.hasEnabled()) m.enabled else null,
            percent = if (m.hasPercent()) m.percent.coerceIn(50, 100) else null,
            supported = if (m.hasSupported()) m.supported else null,
        )
    }

    // ─── Trunk ───────────────────────────────────────────────────────────────

    fun trunkOpen(): VehicleCommandResult = vehicleCommand("trunk_open") {
        vehicleService().trunk(TrunkRequest.newBuilder().setAction("open").build(), emptyMap())
    }

    fun trunkClose(): VehicleCommandResult = vehicleCommand("trunk_close") {
        vehicleService().trunk(TrunkRequest.newBuilder().setAction("close").build(), emptyMap())
    }

    // ─── Climate ─────────────────────────────────────────────────────────────

    fun climateOn(currentTemp: Int): VehicleCommandResult = vehicleCommand("climate_on") {
        vehicleService().setClimate(
            SetClimateRequest.newBuilder().setAction("power_on").setOn(true).setSetpointC(currentTemp.toDouble()).build(),
            emptyMap()
        )
    }

    fun climateOff(): VehicleCommandResult = vehicleCommand("climate_off") {
        vehicleService().setClimate(
            SetClimateRequest.newBuilder().setAction("power_off").setOn(false).build(),
            emptyMap()
        )
    }

    fun climateSetTemp(temp: Int): VehicleCommandResult = vehicleCommand("climate_temp") {
        val clamped = temp.coerceIn(17, 33)
        vehicleService().setClimate(
            SetClimateRequest.newBuilder().setAction("set_temp").setSetpointC(clamped.toDouble()).build(),
            emptyMap()
        )
    }

    fun climateSetFan(fan: Int): VehicleCommandResult = vehicleCommand("climate_fan") {
        val clamped = fan.coerceIn(1, 7)
        vehicleService().setClimate(
            SetClimateRequest.newBuilder().setAction("set_fan").setFanLevel(clamped).build(),
            emptyMap()
        )
    }

    fun climateMaxCooling(enable: Boolean, restoreAcOn: Boolean, restoreTemp: Int, restoreFan: Int): VehicleCommandResult = vehicleCommand("climate_max_cool") {
        vehicleService().setClimate(
            SetClimateRequest.newBuilder()
                .setAction("max_cooling")
                .setMaxCooling(enable)
                .setRestoreAcOn(restoreAcOn)
                .setRestoreTempC(restoreTemp.toDouble())
                .setRestoreFanLevel(restoreFan)
                .build(),
            emptyMap()
        )
    }

    // ─── Seats ───────────────────────────────────────────────────────────────

    fun seatSetHeat(position: Int, newLevel: Int,
                    driverHeat: Int, driverVent: Int,
                    passengerHeat: Int, passengerVent: Int): VehicleCommandResult = vehicleCommand("seat_heat_$position") {
        vehicleService().setSeat(
            SetSeatRequest.newBuilder()
                .setSeatIndex(position)
                .setAction("heating")
                .setLevel(newLevel)
                .setDriverHeat(driverHeat)
                .setDriverVent(driverVent)
                .setPassengerHeat(passengerHeat)
                .setPassengerVent(passengerVent)
                .build(),
            emptyMap()
        )
    }

    fun seatSetCool(position: Int, newLevel: Int,
                    driverHeat: Int, driverVent: Int,
                    passengerHeat: Int, passengerVent: Int): VehicleCommandResult = vehicleCommand("seat_cool_$position") {
        vehicleService().setSeat(
            SetSeatRequest.newBuilder()
                .setSeatIndex(position)
                .setAction("ventilation")
                .setLevel(newLevel)
                .setDriverHeat(driverHeat)
                .setDriverVent(driverVent)
                .setPassengerHeat(passengerHeat)
                .setPassengerVent(passengerVent)
                .build(),
            emptyMap()
        )
    }

    fun seatRecallPosition(position: Int): VehicleCommandResult = vehicleCommand("seat_pos_$position") {
        vehicleService().setSeat(
            SetSeatRequest.newBuilder()
                .setSeatIndex(position)
                .setAction("position")
                .build(),
            emptyMap()
        )
    }

    // ─── Windows ─────────────────────────────────────────────────────────────

    fun windowSetPercent(area: Int, targetPercent: Int): VehicleCommandResult = vehicleCommand("win_${area}_$targetPercent") {
        vehicleService().moveWindow(
            MoveWindowRequest.newBuilder()
                .setWindowIndex(area)
                .setTargetPercent(targetPercent)
                .build(),
            emptyMap()
        )
    }

    fun windowsAllOpen(): VehicleCommandResult = vehicleCommand("win_all_open") {
        vehicleService().moveWindow(
            MoveWindowRequest.newBuilder().setWindowIndex(0).setDirection("open").build(),
            emptyMap()
        )
    }

    fun windowsAllClose(): VehicleCommandResult = vehicleCommand("win_all_close") {
        vehicleService().moveWindow(
            MoveWindowRequest.newBuilder().setWindowIndex(0).setDirection("close").build(),
            emptyMap()
        )
    }

    fun windowsAllVent(targetPercent: Int): VehicleCommandResult = vehicleCommand("win_vent") {
        vehicleService().moveWindow(
            MoveWindowRequest.newBuilder().setWindowIndex(0).setTargetPercent(targetPercent).build(),
            emptyMap()
        )
    }

    // ─── Lights ──────────────────────────────────────────────────────────────

    fun setDrl(enable: Boolean): VehicleCommandResult = vehicleCommand("drl") {
        vehicleService().setLights(
            SetLightsRequest.newBuilder().setAction("dayTimeLight").setOn(enable).build(),
            emptyMap()
        )
    }

    // ─── ADAS ─────────────────────────────────────────────────────────────────

    fun setSpeedLimitWarning(enable: Boolean): VehicleCommandResult = vehicleCommand("slw") {
        vehicleService().setAdas(
            SetAdasRequest.newBuilder().setAction("speedLimitWarning").setOn(enable).build(),
            emptyMap()
        )
    }

    // ─── Charging ────────────────────────────────────────────────────────────

    fun setChargeCapEnabled(enabled: Boolean): VehicleCommandResult = vehicleCommand("cap_toggle") {
        vehicleService().setChargeCap(
            SetChargeCapRequest.newBuilder().setEnabled(enabled).build(),
            emptyMap()
        )
    }

    fun setChargeCapPercent(percent: Int): VehicleCommandResult = vehicleCommand("cap_pct") {
        vehicleService().setChargeCap(
            SetChargeCapRequest.newBuilder().setPercent(percent.coerceIn(50, 100)).build(),
            emptyMap()
        )
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun vehicleCommand(action: String, block: suspend ConnectClientProvider.() -> ResponseMessage<VehicleCommandResponse>): VehicleCommandResult = runBlocking {
        try {
            val resp = ConnectClientProvider.block()
            if (resp is ResponseMessage.Success) mapCommandResult(resp.message)
            else VehicleCommandResult(ok = false, outcome = null, message = null)
        } catch (e: Exception) {
            Log.w("BladeWatch", "vehicleCommand($action): ${e.javaClass.simpleName}: ${e.message}")
            VehicleCommandResult(ok = false, outcome = null, message = null)
        }
    }

    private fun sanitizeWindow(v: Int) = if (v == 255 || v < -1) -1 else v
}
