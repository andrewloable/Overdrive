package net.bladewatch.app.ui.fragment.vehicle

enum class VehicleTab(val label: String) {
    SECURITY("Security"),
    TRUNK("Trunk"),
    CLIMATE("Climate"),
    SEATS("Seats"),
    WINDOWS("Windows"),
    LIGHTS("Lights"),
    ADAS("ADAS"),
    CHARGING("Charging"),
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

data class ChargingCapInfo(
    val enabled: Boolean = false,
    val percent: Int = 80,
    val supported: Boolean? = null,  // null = not yet probed
)

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
)
