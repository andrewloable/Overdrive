package net.bladewatch.app.ui.fragment.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VehicleFormattersTest {

    // ─── presetFor ─────────────────────────────────────────────────────────────

    @Test fun `presetFor negative returns null`() { assertNull(VehicleFormatters.presetFor(-1)) }
    @Test fun `presetFor 0 snaps to 0`() { assertEquals(0, VehicleFormatters.presetFor(0)) }
    @Test fun `presetFor 10 snaps to 0`() { assertEquals(0, VehicleFormatters.presetFor(10)) }
    @Test fun `presetFor 14 returns null`() { assertNull(VehicleFormatters.presetFor(14)) }
    @Test fun `presetFor 15 snaps to 25`() { assertEquals(25, VehicleFormatters.presetFor(15)) }
    @Test fun `presetFor 24 snaps to 25`() { assertEquals(25, VehicleFormatters.presetFor(24)) }
    @Test fun `presetFor 35 snaps to 25`() { assertEquals(25, VehicleFormatters.presetFor(35)) }
    @Test fun `presetFor 47 snaps to 50`() { assertEquals(50, VehicleFormatters.presetFor(47)) }
    @Test fun `presetFor 90 snaps to 100`() { assertEquals(100, VehicleFormatters.presetFor(90)) }
    @Test fun `presetFor 100 snaps to 100`() { assertEquals(100, VehicleFormatters.presetFor(100)) }
    @Test fun `presetFor 36 returns null`() { assertNull(VehicleFormatters.presetFor(36)) }
    @Test fun `presetFor 64 returns null`() { assertNull(VehicleFormatters.presetFor(64)) }

    // ─── formatWindowPct ───────────────────────────────────────────────────────

    @Test fun `formatWindowPct negative is dash`() { assertEquals("–%", VehicleFormatters.formatWindowPct(-1)) }
    @Test fun `formatWindowPct 0 is 0 percent`() { assertEquals("0%", VehicleFormatters.formatWindowPct(0)) }
    @Test fun `formatWindowPct 50 is 50 percent`() { assertEquals("50%", VehicleFormatters.formatWindowPct(50)) }

    // ─── formatTemp ────────────────────────────────────────────────────────────

    @Test fun `formatTemp before load is dash`() { assertEquals("—", VehicleFormatters.formatTemp(22, loaded = false)) }
    @Test fun `formatTemp after load shows value`() { assertEquals("22°C", VehicleFormatters.formatTemp(22, loaded = true)) }

    // ─── formatFan ─────────────────────────────────────────────────────────────

    @Test fun `formatFan before load is dash`() { assertEquals("—", VehicleFormatters.formatFan("Level %1\$d", 3, loaded = false)) }
    @Test fun `formatFan after load shows level`() { assertEquals("Level 3", VehicleFormatters.formatFan("Level %1\$d", 3, loaded = true)) }
}
