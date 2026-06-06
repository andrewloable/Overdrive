package net.bladewatch.app.ui.fragment.vehicle

import net.bladewatch.app.auth.AuthManager
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL

class VehicleClient {

    // ─── State ───────────────────────────────────────────────────────────────

    fun fetchState(): VehicleState? {
        val j = httpGetJson("/api/vehicle/state") ?: return null
        if (!j.optBoolean("success", false)) return null
        return parseState(j)
    }

    fun fetchChargeCap(): ChargingCapInfo? {
        val j = httpGetJson("/api/vehicle/charge-cap") ?: return null
        if (!j.optBoolean("success", false)) return null
        val supported = if (j.isNull("supported")) null else j.optBoolean("supported")
        return ChargingCapInfo(
            enabled = j.optBoolean("enabled", false),
            percent = j.optInt("percent", 80).coerceIn(50, 100),
            supported = supported,
        )
    }

    // ─── Security ────────────────────────────────────────────────────────────

    fun lock(): Boolean = postSuccess("/api/vehicle/lock", null)
    fun unlock(): Boolean = postSuccess("/api/vehicle/unlock", null)
    fun flashLights(): Boolean = postSuccess("/api/vehicle/flash", null)
    fun findCar(): Boolean = postSuccess("/api/vehicle/find-car", null)

    // ─── Trunk ───────────────────────────────────────────────────────────────

    fun trunkOpen(): Boolean = postSuccess("/api/vehicle/trunk", JSONObject().put("action", "open"))
    fun trunkClose(): Boolean = postSuccess("/api/vehicle/trunk", JSONObject().put("action", "close"))

    // ─── Climate ─────────────────────────────────────────────────────────────

    fun climateOn(currentTemp: Int): Boolean =
        postSuccess("/api/vehicle/climate", JSONObject().put("action", "power_on").put("temp", currentTemp))

    fun climateOff(): Boolean =
        postSuccess("/api/vehicle/climate", JSONObject().put("action", "power_off"))

    fun climateSetTemp(temp: Int): Boolean {
        val clamped = temp.coerceIn(17, 33)
        return postSuccess("/api/vehicle/climate",
            JSONObject().put("action", "set_temp").put("zone", 1).put("temp", clamped))
    }

    fun climateSetFan(fan: Int): Boolean {
        val clamped = fan.coerceIn(1, 7)
        return postSuccess("/api/vehicle/climate",
            JSONObject().put("action", "set_fan").put("fan", clamped))
    }

    // Enabling max cooling: caller must supply the current state to use as restore point.
    fun climateMaxCooling(enable: Boolean, restoreAcOn: Boolean, restoreTemp: Int, restoreFan: Int): Boolean =
        postSuccess("/api/vehicle/climate", JSONObject()
            .put("action", "max_cooling")
            .put("enabled", enable)
            .put("hasRestore", enable)
            .put("restorePowerOn", restoreAcOn)
            .put("restoreTemp", restoreTemp.coerceIn(17, 33))
            .put("restoreFan", restoreFan.coerceIn(1, 7)))

    // ─── Seats ───────────────────────────────────────────────────────────────

    // position: 1=driver, 2=passenger
    // Full state (all four fields) must be sent on every seat call — cloud API is stateful.
    fun seatSetHeat(position: Int, newLevel: Int,
                    driverHeat: Int, driverVent: Int,
                    passengerHeat: Int, passengerVent: Int): Boolean =
        postSuccess("/api/vehicle/seat", JSONObject()
            .put("action", "heating")
            .put("position", position)
            .put("level", newLevel)
            .put("driverHeat", driverHeat)
            .put("driverVent", driverVent)
            .put("passengerHeat", passengerHeat)
            .put("passengerVent", passengerVent))

    fun seatSetCool(position: Int, newLevel: Int,
                    driverHeat: Int, driverVent: Int,
                    passengerHeat: Int, passengerVent: Int): Boolean =
        postSuccess("/api/vehicle/seat", JSONObject()
            .put("action", "ventilation")
            .put("position", position)
            .put("level", newLevel)
            .put("driverHeat", driverHeat)
            .put("driverVent", driverVent)
            .put("passengerHeat", passengerHeat)
            .put("passengerVent", passengerVent))

    fun seatRecallPosition(position: Int): Boolean =
        postSuccess("/api/vehicle/seat", JSONObject()
            .put("action", "position")
            .put("position", position))

    // ─── Windows ─────────────────────────────────────────────────────────────

    // area: 1=LF, 2=RF, 3=LR, 4=RR, 5=sunroof, 6=sunshade
    fun windowSetPercent(area: Int, targetPercent: Int): Boolean =
        postSuccess("/api/vehicle/window", JSONObject()
            .put("area", area)
            .put("targetPercent", targetPercent))

    // All windows open (command=1)
    fun windowsAllOpen(): Boolean =
        postSuccess("/api/vehicle/window", JSONObject().put("area", 0).put("command", 1))

    // All windows close (command=2)
    fun windowsAllClose(): Boolean =
        postSuccess("/api/vehicle/window", JSONObject().put("area", 0).put("command", 2))

    // All side windows to targetPercent (0=close, 12=vent)
    fun windowsAllVent(targetPercent: Int): Boolean =
        postSuccess("/api/vehicle/window", JSONObject().put("area", 0).put("targetPercent", targetPercent))

    // ─── Lights ──────────────────────────────────────────────────────────────

    fun setDrl(enable: Boolean): Boolean =
        postSuccess("/api/vehicle/lights", JSONObject().put("target", "dayTimeLight").put("enable", enable))

    // ─── ADAS ─────────────────────────────────────────────────────────────────

    fun setSpeedLimitWarning(enable: Boolean): Boolean =
        postSuccess("/api/vehicle/adas", JSONObject().put("target", "speedLimitWarning").put("enable", enable))

    // ─── Charging ────────────────────────────────────────────────────────────

    fun setChargeCapEnabled(enabled: Boolean): Boolean =
        postSuccess("/api/vehicle/charge-cap", JSONObject().put("enabled", enabled))

    fun setChargeCapPercent(percent: Int): Boolean {
        val clamped = percent.coerceIn(50, 100)
        return postSuccess("/api/vehicle/charge-cap", JSONObject().put("percent", clamped))
    }

    // ─── HTTP helpers ────────────────────────────────────────────────────────

    private fun postSuccess(path: String, body: JSONObject?): Boolean = runCatching {
        val jwt = getJwt() ?: return false
        val conn = URL("http://127.0.0.1:8080$path").openConnection(Proxy.NO_PROXY) as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Authorization", "Bearer $jwt")
        if (body != null) {
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
        }
        conn.connectTimeout = 8_000; conn.readTimeout = 8_000
        if (body != null) conn.outputStream.use { it.write(body.toString().toByteArray()) }
        if (conn.responseCode !in 200..299) return false
        val resp = JSONObject(conn.inputStream.bufferedReader().readText())
        resp.optBoolean("success", false)
    }.getOrDefault(false)

    private fun httpGetJson(path: String): JSONObject? = runCatching {
        val jwt = getJwt() ?: return null
        val conn = URL("http://127.0.0.1:8080$path").openConnection(Proxy.NO_PROXY) as HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("Authorization", "Bearer $jwt")
        conn.connectTimeout = 5_000; conn.readTimeout = 5_000
        if (conn.responseCode != 200) return null
        JSONObject(conn.inputStream.bufferedReader().readText())
    }.getOrNull()

    private fun getJwt(): String? = runCatching {
        if (AuthManager.getState() == null) AuthManager.initialize()
        AuthManager.generateJwt()?.takeIf { it.isNotBlank() }
    }.getOrNull()

    // ─── State parsing ───────────────────────────────────────────────────────

    private fun parseState(j: JSONObject): VehicleState {
        val doors = j.optJSONObject("doors")?.let {
            DoorState(
                overall = it.optInt("overall", -1),
                lf = it.optInt("lf", -1), rf = it.optInt("rf", -1),
                lr = it.optInt("lr", -1), rr = it.optInt("rr", -1),
            )
        } ?: DoorState()

        val windows = j.optJSONObject("windows")?.let {
            WindowState(
                lf = sanitizeWindow(it.optInt("lf", -1)),
                rf = sanitizeWindow(it.optInt("rf", -1)),
                lr = sanitizeWindow(it.optInt("lr", -1)),
                rr = sanitizeWindow(it.optInt("rr", -1)),
                sunroof = sanitizeWindow(it.optInt("sunroof", -1)),
                sunshade = sanitizeWindow(it.optInt("sunshade", -1)),
            )
        } ?: WindowState()

        val caps = j.optJSONObject("capabilities")?.let { c ->
            VehicleCapabilities(
                windows = c.optJSONObject("windows")?.let { w ->
                    WindowCapabilities(sunroof = w.optBoolean("sunroof"), sunshade = w.optBoolean("sunshade"))
                } ?: WindowCapabilities(),
                seats = c.optJSONObject("seats")?.let { s ->
                    SeatCapabilities(
                        driverHeat = s.optBoolean("driverHeat"),
                        passengerHeat = s.optBoolean("passengerHeat"),
                        driverCool = s.optBoolean("driverCool"),
                        passengerCool = s.optBoolean("passengerCool"),
                        driverMemoryRecall = s.optBoolean("driverMemoryRecall"),
                    )
                } ?: SeatCapabilities(),
            )
        } ?: VehicleCapabilities()

        val battery = j.optJSONObject("battery")?.let {
            BatteryInfo(soc = it.optInt("soc", 0), rangeKm = it.optInt("rangeKm", 0))
        } ?: BatteryInfo()

        val lights = j.optJSONObject("lights")?.let {
            LightsInfo(dayTimeLight = it.optBoolean("dayTimeLight", false))
        } ?: LightsInfo()

        val adas = j.optJSONObject("adas")?.let {
            AdasInfo(speedLimitWarning = it.optBoolean("speedLimitWarning", false))
        } ?: AdasInfo()

        val seats = j.optJSONObject("seats")?.let { s ->
            val heat = s.optJSONArray("heat")?.let { a -> listOf(a.optInt(0, 0), a.optInt(1, 0)) } ?: listOf(0, 0)
            val cool = s.optJSONArray("cool")?.let { a -> listOf(a.optInt(0, 0), a.optInt(1, 0)) } ?: listOf(0, 0)
            SeatsInfo(heat = heat, cool = cool)
        } ?: SeatsInfo()

        val climate = j.optJSONObject("climate")?.let { c ->
            val temp = if (c.has("setpointC")) c.optInt("setpointC", 22) else c.optInt("tempC", 22)
            val inside = if (c.has("insideTempC")) c.optDouble("insideTempC").takeIf { !it.isNaN() }?.toFloat() else null
            ClimateInfo(
                acOn = c.optBoolean("acOn", false),
                setpointC = temp.coerceIn(16, 35),
                insideTempC = inside,
                fanLevel = c.optInt("fanLevel", 3).coerceIn(1, 7),
                maxCooling = c.optBoolean("maxCooling", false),
            )
        } ?: ClimateInfo()

        return VehicleState(doors = doors, windows = windows, capabilities = caps,
            battery = battery, lights = lights, adas = adas, seats = seats, climate = climate)
    }

    // BYD firmware sometimes returns 255 for absent window devices; sanitize to -1
    private fun sanitizeWindow(v: Int) = if (v == 255 || v < -1) -1 else v
}
