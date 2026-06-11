package net.bladewatch.app.ui.fragment.vehicle

import net.bladewatch.app.grpc.v1.TyrePressure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VehicleClientTyreMapTest {

    private fun tyrePressure(
        psi: Double = 0.0,
        kPa: Int = 0,
        tempC: Int = 0,
        pressureState: Int = 0,
        leakState: Int = 0,
        signalState: Int = 0,
    ): TyrePressure = TyrePressure.newBuilder()
        .setPsi(psi).setKPa(kPa).setTempC(tempC)
        .setPressureState(pressureState).setLeakState(leakState).setSignalState(signalState)
        .build()

    @Test fun `zero psi maps to null`() {
        val info = VehicleClient.mapTyre(tyrePressure(psi = 0.0))
        assertNull(info.psi)
    }

    @Test fun `non-zero psi is preserved`() {
        val info = VehicleClient.mapTyre(tyrePressure(psi = 35.5))
        assertEquals(35.5, info.psi!!, 0.001)
    }

    @Test fun `zero kPa maps to null`() {
        val info = VehicleClient.mapTyre(tyrePressure(kPa = 0))
        assertNull(info.kPa)
    }

    @Test fun `non-zero kPa is preserved`() {
        val info = VehicleClient.mapTyre(tyrePressure(kPa = 245))
        assertEquals(245, info.kPa)
    }

    @Test fun `zero tempC maps to null`() {
        val info = VehicleClient.mapTyre(tyrePressure(tempC = 0))
        assertNull(info.temperatureC)
    }

    @Test fun `non-zero tempC is preserved in temperatureC field`() {
        val info = VehicleClient.mapTyre(tyrePressure(tempC = 28))
        assertEquals(28, info.temperatureC)
    }

    @Test fun `pressureState is mapped`() {
        val info = VehicleClient.mapTyre(tyrePressure(pressureState = 1))
        assertEquals(1, info.pressureState)
    }

    @Test fun `leakState maps to airLeakState`() {
        val info = VehicleClient.mapTyre(tyrePressure(leakState = 2))
        assertEquals(2, info.airLeakState)
    }

    @Test fun `signalState is mapped`() {
        val info = VehicleClient.mapTyre(tyrePressure(signalState = 1))
        assertEquals(1, info.signalState)
    }
}
