package net.bladewatch.app.ui.fragment.vehicle

import org.junit.Assert.assertEquals
import org.junit.Test

class TyreTierTest {

    private fun tyre(
        psi: Double? = 36.0,
        kPa: Int? = null,
        temperatureC: Int? = null,
        pressureState: Int = 0,
        airLeakState: Int = 0,
        signalState: Int = 0,
    ) = TyreInfo(psi, kPa, temperatureC, pressureState, airLeakState, signalState)

    // ─── MUTED ───────────────────────────────────────────────────────────────

    @Test fun `muted when psi is null`() {
        assertEquals(TyreTier.MUTED, VehicleFormatters.tyreTier(tyre(psi = null)))
    }

    @Test fun `muted when signal state is 1`() {
        assertEquals(TyreTier.MUTED, VehicleFormatters.tyreTier(tyre(signalState = 1)))
    }

    @Test fun `muted when signal state is 2`() {
        assertEquals(TyreTier.MUTED, VehicleFormatters.tyreTier(tyre(signalState = 2)))
    }

    // ─── ALERT ───────────────────────────────────────────────────────────────

    @Test fun `alert when airLeakState is 1 (slow leak)`() {
        assertEquals(TyreTier.ALERT, VehicleFormatters.tyreTier(tyre(psi = 36.0, airLeakState = 1)))
    }

    @Test fun `alert when airLeakState is 2 (fast leak)`() {
        assertEquals(TyreTier.ALERT, VehicleFormatters.tyreTier(tyre(psi = 36.0, airLeakState = 2)))
    }

    @Test fun `alert when psi below 22`() {
        assertEquals(TyreTier.ALERT, VehicleFormatters.tyreTier(tyre(psi = 21.9)))
    }

    @Test fun `alert at exactly psi 21`() {
        assertEquals(TyreTier.ALERT, VehicleFormatters.tyreTier(tyre(psi = 21.0)))
    }

    @Test fun `not alert at psi 22`() {
        val tier = VehicleFormatters.tyreTier(tyre(psi = 22.0))
        assert(tier != TyreTier.ALERT) { "Expected not ALERT, got $tier" }
    }

    // ─── WARN ────────────────────────────────────────────────────────────────

    @Test fun `warn when pressureState is 1 with normal psi`() {
        assertEquals(TyreTier.WARN, VehicleFormatters.tyreTier(tyre(psi = 36.0, pressureState = 1)))
    }

    @Test fun `warn when pressureState is 2 with normal psi`() {
        assertEquals(TyreTier.WARN, VehicleFormatters.tyreTier(tyre(psi = 36.0, pressureState = 2)))
    }

    // ─── CAUTION ─────────────────────────────────────────────────────────────

    @Test fun `caution when psi below 34`() {
        assertEquals(TyreTier.CAUTION, VehicleFormatters.tyreTier(tyre(psi = 30.0)))
    }

    @Test fun `caution at psi exactly 22 (lower boundary)`() {
        assertEquals(TyreTier.CAUTION, VehicleFormatters.tyreTier(tyre(psi = 22.0)))
    }

    @Test fun `caution when psi above 45`() {
        assertEquals(TyreTier.CAUTION, VehicleFormatters.tyreTier(tyre(psi = 46.0)))
    }

    @Test fun `caution at psi exactly 45_1 (just above normal)`() {
        assertEquals(TyreTier.CAUTION, VehicleFormatters.tyreTier(tyre(psi = 45.1)))
    }

    // ─── NORMAL ──────────────────────────────────────────────────────────────

    @Test fun `normal at psi 34 (lower boundary)`() {
        assertEquals(TyreTier.NORMAL, VehicleFormatters.tyreTier(tyre(psi = 34.0)))
    }

    @Test fun `normal at psi 36`() {
        assertEquals(TyreTier.NORMAL, VehicleFormatters.tyreTier(tyre(psi = 36.0)))
    }

    @Test fun `normal at psi 45 (upper boundary)`() {
        assertEquals(TyreTier.NORMAL, VehicleFormatters.tyreTier(tyre(psi = 45.0)))
    }
}
