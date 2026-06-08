package net.bladewatch.app.ui.fragment.vehicle

import com.connectrpc.ResponseMessage
import kotlinx.coroutines.runBlocking
import net.bladewatch.app.client.ConnectClientProvider
import net.bladewatch.app.grpc.v1.FindCarRequest
import net.bladewatch.app.grpc.v1.FlashRequest
import net.bladewatch.app.grpc.v1.GetChargeCapRequest
import net.bladewatch.app.grpc.v1.GetVehicleStateRequest
import net.bladewatch.app.grpc.v1.LockRequest
import net.bladewatch.app.grpc.v1.MoveWindowRequest
import net.bladewatch.app.grpc.v1.SetAdasRequest
import net.bladewatch.app.grpc.v1.SetChargeCapRequest
import net.bladewatch.app.grpc.v1.SetClimateRequest
import net.bladewatch.app.grpc.v1.SetLightsRequest
import net.bladewatch.app.grpc.v1.SetSeatRequest
import net.bladewatch.app.grpc.v1.TrunkRequest
import net.bladewatch.app.grpc.v1.UnlockRequest

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
        VehicleState(doors = doors, windows = windows, capabilities = caps,
            battery = battery, lights = lights, adas = adas, seats = seats, climate = climate)
    }

    fun fetchChargeCap(): ChargingCapInfo? = runBlocking {
        val resp = ConnectClientProvider.vehicleService().getChargeCap(
            GetChargeCapRequest.newBuilder().build(), emptyMap()
        )
        if (resp !is ResponseMessage.Success || !resp.message.success) return@runBlocking null
        val m = resp.message
        ChargingCapInfo(
            enabled = m.enabled,
            percent = m.percent.coerceIn(50, 100),
            supported = m.supported,
        )
    }

    // ─── Security ────────────────────────────────────────────────────────────

    fun lock(): Boolean = vehicleCommand {
        vehicleService().lock(LockRequest.newBuilder().build(), emptyMap())
    }

    fun unlock(): Boolean = vehicleCommand {
        vehicleService().unlock(UnlockRequest.newBuilder().build(), emptyMap())
    }

    fun flashLights(): Boolean = vehicleCommand {
        vehicleService().flash(FlashRequest.newBuilder().build(), emptyMap())
    }

    fun findCar(): Boolean = vehicleCommand {
        vehicleService().findCar(FindCarRequest.newBuilder().build(), emptyMap())
    }

    // ─── Trunk ───────────────────────────────────────────────────────────────

    fun trunkOpen(): Boolean = vehicleCommand {
        vehicleService().trunk(TrunkRequest.newBuilder().setAction("open").build(), emptyMap())
    }

    fun trunkClose(): Boolean = vehicleCommand {
        vehicleService().trunk(TrunkRequest.newBuilder().setAction("close").build(), emptyMap())
    }

    // ─── Climate ─────────────────────────────────────────────────────────────

    fun climateOn(currentTemp: Int): Boolean = vehicleCommand {
        vehicleService().setClimate(
            SetClimateRequest.newBuilder().setAction("power_on").setOn(true).setSetpointC(currentTemp.toDouble()).build(),
            emptyMap()
        )
    }

    fun climateOff(): Boolean = vehicleCommand {
        vehicleService().setClimate(
            SetClimateRequest.newBuilder().setAction("power_off").setOn(false).build(),
            emptyMap()
        )
    }

    fun climateSetTemp(temp: Int): Boolean = vehicleCommand {
        val clamped = temp.coerceIn(17, 33)
        vehicleService().setClimate(
            SetClimateRequest.newBuilder().setAction("set_temp").setSetpointC(clamped.toDouble()).build(),
            emptyMap()
        )
    }

    fun climateSetFan(fan: Int): Boolean = vehicleCommand {
        val clamped = fan.coerceIn(1, 7)
        vehicleService().setClimate(
            SetClimateRequest.newBuilder().setAction("set_fan").setFanLevel(clamped).build(),
            emptyMap()
        )
    }

    fun climateMaxCooling(enable: Boolean, restoreAcOn: Boolean, restoreTemp: Int, restoreFan: Int): Boolean = vehicleCommand {
        vehicleService().setClimate(
            SetClimateRequest.newBuilder()
                .setAction("max_cooling")
                .setMaxCooling(enable)
                .build(),
            emptyMap()
        )
    }

    // ─── Seats ───────────────────────────────────────────────────────────────

    fun seatSetHeat(position: Int, newLevel: Int,
                    driverHeat: Int, driverVent: Int,
                    passengerHeat: Int, passengerVent: Int): Boolean = vehicleCommand {
        vehicleService().setSeat(
            SetSeatRequest.newBuilder()
                .setSeatIndex(position)
                .setAction("heating")
                .setLevel(newLevel)
                .build(),
            emptyMap()
        )
    }

    fun seatSetCool(position: Int, newLevel: Int,
                    driverHeat: Int, driverVent: Int,
                    passengerHeat: Int, passengerVent: Int): Boolean = vehicleCommand {
        vehicleService().setSeat(
            SetSeatRequest.newBuilder()
                .setSeatIndex(position)
                .setAction("ventilation")
                .setLevel(newLevel)
                .build(),
            emptyMap()
        )
    }

    fun seatRecallPosition(position: Int): Boolean = vehicleCommand {
        vehicleService().setSeat(
            SetSeatRequest.newBuilder()
                .setSeatIndex(position)
                .setAction("position")
                .build(),
            emptyMap()
        )
    }

    // ─── Windows ─────────────────────────────────────────────────────────────

    fun windowSetPercent(area: Int, targetPercent: Int): Boolean = vehicleCommand {
        vehicleService().moveWindow(
            MoveWindowRequest.newBuilder()
                .setWindowIndex(area)
                .setTargetPercent(targetPercent)
                .build(),
            emptyMap()
        )
    }

    fun windowsAllOpen(): Boolean = vehicleCommand {
        vehicleService().moveWindow(
            MoveWindowRequest.newBuilder().setWindowIndex(0).setDirection("open").build(),
            emptyMap()
        )
    }

    fun windowsAllClose(): Boolean = vehicleCommand {
        vehicleService().moveWindow(
            MoveWindowRequest.newBuilder().setWindowIndex(0).setDirection("close").build(),
            emptyMap()
        )
    }

    fun windowsAllVent(targetPercent: Int): Boolean = vehicleCommand {
        vehicleService().moveWindow(
            MoveWindowRequest.newBuilder().setWindowIndex(0).setTargetPercent(targetPercent).build(),
            emptyMap()
        )
    }

    // ─── Lights ──────────────────────────────────────────────────────────────

    fun setDrl(enable: Boolean): Boolean = vehicleCommand {
        vehicleService().setLights(
            SetLightsRequest.newBuilder().setAction("dayTimeLight").setOn(enable).build(),
            emptyMap()
        )
    }

    // ─── ADAS ─────────────────────────────────────────────────────────────────

    fun setSpeedLimitWarning(enable: Boolean): Boolean = vehicleCommand {
        vehicleService().setAdas(
            SetAdasRequest.newBuilder().setAction("speedLimitWarning").setOn(enable).build(),
            emptyMap()
        )
    }

    // ─── Charging ────────────────────────────────────────────────────────────

    fun setChargeCapEnabled(enabled: Boolean): Boolean = vehicleCommand {
        vehicleService().setChargeCap(
            SetChargeCapRequest.newBuilder().setEnabled(enabled).build(),
            emptyMap()
        )
    }

    fun setChargeCapPercent(percent: Int): Boolean = vehicleCommand {
        vehicleService().setChargeCap(
            SetChargeCapRequest.newBuilder().setPercent(percent.coerceIn(50, 100)).build(),
            emptyMap()
        )
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun vehicleCommand(block: suspend ConnectClientProvider.() -> ResponseMessage<*>): Boolean = runBlocking {
        try {
            val resp = ConnectClientProvider.block()
            resp is ResponseMessage.Success
        } catch (_: Exception) {
            false
        }
    }

    private fun sanitizeWindow(v: Int) = if (v == 255 || v < -1) -1 else v
}
