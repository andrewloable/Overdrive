package net.bladewatch.app.ui.fragment.vehicle

import net.bladewatch.app.grpc.v1.GetChargeCapResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VehicleClientMapTest {

    @Test
    fun `unset optional fields map to null`() {
        val resp = GetChargeCapResponse.newBuilder().build()
        val info = VehicleClient.mapChargeCap(resp)
        assertNull("supported unset must be null", info.supported)
        assertNull("enabled unset must be null", info.enabled)
        assertNull("percent unset must be null", info.percent)
    }

    @Test
    fun `supported true maps to true`() {
        val resp = GetChargeCapResponse.newBuilder().setSupported(true).build()
        val info = VehicleClient.mapChargeCap(resp)
        assertEquals(true, info.supported)
    }

    @Test
    fun `supported false maps to false`() {
        val resp = GetChargeCapResponse.newBuilder().setSupported(false).build()
        val info = VehicleClient.mapChargeCap(resp)
        assertEquals(false, info.supported)
    }

    @Test
    fun `enabled false maps to false not null`() {
        val resp = GetChargeCapResponse.newBuilder().setEnabled(false).build()
        val info = VehicleClient.mapChargeCap(resp)
        assertEquals(false, info.enabled)
    }

    @Test
    fun `percent is clamped to 50-100`() {
        val resp = GetChargeCapResponse.newBuilder().setPercent(30).build()
        val info = VehicleClient.mapChargeCap(resp)
        assertEquals(50, info.percent)
    }
}
