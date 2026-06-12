package net.bladewatch.app.ui.fragment.vehicle

import androidx.annotation.StringRes
import net.bladewatch.app.R

enum class VehicleTab(@StringRes val labelRes: Int) {
    TRUNK(R.string.vehicle_tab_trunk),
    CLIMATE(R.string.vehicle_tab_climate),
    SEATS(R.string.vehicle_tab_seats),
    WINDOWS(R.string.vehicle_tab_windows),
    LIGHTS(R.string.vehicle_tab_lights),
    ADAS(R.string.vehicle_tab_adas),
    CHARGING(R.string.vehicle_control_charging_tab),
}

// Door lock: 1=locked, 2=unlocked, -1=unknown
data class DoorState(
    val overall: Int = -1,
    val lf: Int = -1, val rf: Int = -1,
    val lr: Int = -1, val rr: Int = -1,
)

// Window: 0=closed, 100=fully open, -1=unknown
data class WindowState(
    val lf: Int = -1, val rf: Int = -1,
    val lr: Int = -1, val rr: Int = -1,
    val sunroof: Int = -1, val sunshade: Int = -1,
)

data class WindowCapabilities(val sunroof: Boolean = false, val sunshade: Boolean = false)

data class SeatCapabilities(
    val driverHeat: Boolean = false,
    val passengerHeat: Boolean = false,
    val driverCool: Boolean = false,
    val passengerCool: Boolean = false,
    val driverMemoryRecall: Boolean = false,
) {
    fun anyAvailable() = driverHeat || passengerHeat || driverCool || passengerCool || driverMemoryRecall
}

data class VehicleCapabilities(
    val windows: WindowCapabilities = WindowCapabilities(),
    val seats: SeatCapabilities = SeatCapabilities(),
)

data class BatteryInfo(val soc: Int = 0, val rangeKm: Int = 0)

data class LightsInfo(val dayTimeLight: Boolean = false)

data class AdasInfo(val speedLimitWarning: Boolean = false)

data class SeatsInfo(
    val heat: List<Int> = listOf(0, 0),   // [driver, passenger], 0-2
    val cool: List<Int> = listOf(0, 0),
)

data class ClimateInfo(
    val acOn: Boolean = false,
    val setpointC: Int = 22,
    val insideTempC: Float? = null,
    val fanLevel: Int = 3,
    val maxCooling: Boolean = false,
)

data class TyreInfo(
    val psi: Double?,
    val kPa: Int?,
    val temperatureC: Int?,
    val pressureState: Int = 0,
    val airLeakState: Int = 0,
    val signalState: Int = 0,
)

data class TyreSetInfo(
    val fl: TyreInfo = TyreInfo(null, null, null),
    val fr: TyreInfo = TyreInfo(null, null, null),
    val rl: TyreInfo = TyreInfo(null, null, null),
    val rr: TyreInfo = TyreInfo(null, null, null),
)

data class VehicleCommandResult(
    val ok: Boolean,
    val outcome: String?,
    val message: String?,
)

data class ChargingCapInfo(
    val enabled: Boolean? = null,   // null = not yet probed
    val percent: Int? = null,       // null = not yet probed
    val supported: Boolean? = null, // null = not yet probed
)

data class ModelEntry(val id: String, val name: String, val file: String, val bundled: Boolean)

data class VehicleAppearance(val modelId: String, val color: String)

data class VehicleState(
    val doors: DoorState = DoorState(),
    val windows: WindowState = WindowState(),
    val capabilities: VehicleCapabilities = VehicleCapabilities(),
    val battery: BatteryInfo = BatteryInfo(),
    val lights: LightsInfo = LightsInfo(),
    val adas: AdasInfo = AdasInfo(),
    val seats: SeatsInfo = SeatsInfo(),
    val climate: ClimateInfo = ClimateInfo(),
    val chargeCap: ChargingCapInfo = ChargingCapInfo(),
    val tyres: TyreSetInfo = TyreSetInfo(),
    val loaded: Boolean = false,
)
