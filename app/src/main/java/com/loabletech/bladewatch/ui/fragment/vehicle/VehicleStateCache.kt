package net.bladewatch.app.ui.fragment.vehicle

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Serializes/deserializes the last successful VehicleState to/from
 * `filesDir/vehicle_state.json`. Used to render a stale-but-instant first
 * paint on screen entry before the first live poll completes.
 *
 * All failures are swallowed — persistence is best-effort only.
 */
object VehicleStateCache {

    private const val FILE_NAME = "vehicle_state.json"

    fun save(context: Context, state: VehicleState) {
        try {
            File(context.filesDir, FILE_NAME).writeText(serialize(state).toString())
        } catch (_: Exception) { /* non-fatal */ }
    }

    fun load(context: Context): VehicleState? {
        return try {
            val file = File(context.filesDir, FILE_NAME)
            if (!file.exists()) return null
            deserialize(JSONObject(file.readText()))
        } catch (_: Exception) { null }
    }

    // ─── Serialization ───────────────────────────────────────────────────────

    private fun serialize(s: VehicleState): JSONObject = JSONObject().apply {
        put("doors", JSONObject().apply {
            put("overall", s.doors.overall)
            put("lf", s.doors.lf); put("rf", s.doors.rf)
            put("lr", s.doors.lr); put("rr", s.doors.rr)
        })
        put("windows", JSONObject().apply {
            put("lf", s.windows.lf); put("rf", s.windows.rf)
            put("lr", s.windows.lr); put("rr", s.windows.rr)
            put("sunroof", s.windows.sunroof); put("sunshade", s.windows.sunshade)
        })
        put("capabilities", JSONObject().apply {
            put("windows", JSONObject().apply {
                put("sunroof", s.capabilities.windows.sunroof)
                put("sunshade", s.capabilities.windows.sunshade)
            })
            put("seats", JSONObject().apply {
                put("driverHeat", s.capabilities.seats.driverHeat)
                put("passengerHeat", s.capabilities.seats.passengerHeat)
                put("driverCool", s.capabilities.seats.driverCool)
                put("passengerCool", s.capabilities.seats.passengerCool)
                put("driverMemoryRecall", s.capabilities.seats.driverMemoryRecall)
            })
        })
        put("battery", JSONObject().apply {
            put("soc", s.battery.soc); put("rangeKm", s.battery.rangeKm)
        })
        put("lights", JSONObject().put("dayTimeLight", s.lights.dayTimeLight))
        put("adas", JSONObject().put("speedLimitWarning", s.adas.speedLimitWarning))
        put("seats", JSONObject().apply {
            put("heat", JSONArray(s.seats.heat)); put("cool", JSONArray(s.seats.cool))
        })
        put("climate", JSONObject().apply {
            put("acOn", s.climate.acOn)
            put("setpointC", s.climate.setpointC)
            if (s.climate.insideTempC != null) put("insideTempC", s.climate.insideTempC)
            put("fanLevel", s.climate.fanLevel)
            put("maxCooling", s.climate.maxCooling)
        })
        put("tyres", JSONObject().apply {
            for ((key, t) in listOf("fl" to s.tyres.fl, "fr" to s.tyres.fr, "rl" to s.tyres.rl, "rr" to s.tyres.rr)) {
                put(key, JSONObject().apply {
                    if (t.psi != null) put("psi", t.psi)
                    if (t.kPa != null) put("kPa", t.kPa)
                    if (t.temperatureC != null) put("temperatureC", t.temperatureC)
                    put("pressureState", t.pressureState)
                    put("airLeakState", t.airLeakState)
                    put("signalState", t.signalState)
                })
            }
        })
        put("chargeCap", JSONObject().apply {
            if (s.chargeCap.enabled != null) put("enabled", s.chargeCap.enabled)
            if (s.chargeCap.percent != null) put("percent", s.chargeCap.percent)
            if (s.chargeCap.supported != null) put("supported", s.chargeCap.supported)
        })
    }

    // ─── Deserialization ─────────────────────────────────────────────────────

    private fun deserialize(j: JSONObject): VehicleState {
        val doors = j.optJSONObject("doors")?.let {
            DoorState(
                overall = it.optInt("overall", -1),
                lf = it.optInt("lf", -1), rf = it.optInt("rf", -1),
                lr = it.optInt("lr", -1), rr = it.optInt("rr", -1),
            )
        } ?: DoorState()

        val windows = j.optJSONObject("windows")?.let {
            WindowState(
                lf = it.optInt("lf", -1), rf = it.optInt("rf", -1),
                lr = it.optInt("lr", -1), rr = it.optInt("rr", -1),
                sunroof = it.optInt("sunroof", -1), sunshade = it.optInt("sunshade", -1),
            )
        } ?: WindowState()

        val caps = j.optJSONObject("capabilities")?.let { c ->
            val winCaps = c.optJSONObject("windows")?.let {
                WindowCapabilities(sunroof = it.optBoolean("sunroof"), sunshade = it.optBoolean("sunshade"))
            } ?: WindowCapabilities()
            val seatCaps = c.optJSONObject("seats")?.let {
                SeatCapabilities(
                    driverHeat = it.optBoolean("driverHeat"),
                    passengerHeat = it.optBoolean("passengerHeat"),
                    driverCool = it.optBoolean("driverCool"),
                    passengerCool = it.optBoolean("passengerCool"),
                    driverMemoryRecall = it.optBoolean("driverMemoryRecall"),
                )
            } ?: SeatCapabilities()
            VehicleCapabilities(windows = winCaps, seats = seatCaps)
        } ?: VehicleCapabilities()

        val battery = j.optJSONObject("battery")?.let {
            BatteryInfo(soc = it.optInt("soc"), rangeKm = it.optInt("rangeKm"))
        } ?: BatteryInfo()

        val lights = j.optJSONObject("lights")?.let {
            LightsInfo(dayTimeLight = it.optBoolean("dayTimeLight"))
        } ?: LightsInfo()

        val adas = j.optJSONObject("adas")?.let {
            AdasInfo(speedLimitWarning = it.optBoolean("speedLimitWarning"))
        } ?: AdasInfo()

        val seats = j.optJSONObject("seats")?.let { s ->
            val heat = (0 until (s.optJSONArray("heat")?.length() ?: 0)).map { s.getJSONArray("heat").getInt(it) }
            val cool = (0 until (s.optJSONArray("cool")?.length() ?: 0)).map { s.getJSONArray("cool").getInt(it) }
            SeatsInfo(heat = heat.ifEmpty { listOf(0, 0) }, cool = cool.ifEmpty { listOf(0, 0) })
        } ?: SeatsInfo()

        val climate = j.optJSONObject("climate")?.let {
            ClimateInfo(
                acOn = it.optBoolean("acOn"),
                setpointC = it.optInt("setpointC", 22).coerceIn(16, 35),
                insideTempC = if (it.has("insideTempC")) it.getDouble("insideTempC").toFloat() else null,
                fanLevel = it.optInt("fanLevel", 3).coerceIn(1, 7),
                maxCooling = it.optBoolean("maxCooling"),
            )
        } ?: ClimateInfo()

        val tyres = j.optJSONObject("tyres")?.let { t ->
            TyreSetInfo(
                fl = parseTyre(t.optJSONObject("fl")),
                fr = parseTyre(t.optJSONObject("fr")),
                rl = parseTyre(t.optJSONObject("rl")),
                rr = parseTyre(t.optJSONObject("rr")),
            )
        } ?: TyreSetInfo()

        val chargeCap = j.optJSONObject("chargeCap")?.let {
            ChargingCapInfo(
                enabled = if (it.has("enabled")) it.getBoolean("enabled") else null,
                percent = if (it.has("percent")) it.getInt("percent") else null,
                supported = if (it.has("supported")) it.getBoolean("supported") else null,
            )
        } ?: ChargingCapInfo()

        return VehicleState(
            doors = doors, windows = windows, capabilities = caps,
            battery = battery, lights = lights, adas = adas,
            seats = seats, climate = climate, tyres = tyres,
            chargeCap = chargeCap, loaded = true,
        )
    }

    private fun parseTyre(t: JSONObject?): TyreInfo {
        if (t == null) return TyreInfo(null, null, null)
        return TyreInfo(
            psi = if (t.has("psi")) t.getDouble("psi") else null,
            kPa = if (t.has("kPa")) t.getInt("kPa") else null,
            temperatureC = if (t.has("temperatureC")) t.getInt("temperatureC") else null,
            pressureState = t.optInt("pressureState"),
            airLeakState = t.optInt("airLeakState"),
            signalState = t.optInt("signalState"),
        )
    }
}
