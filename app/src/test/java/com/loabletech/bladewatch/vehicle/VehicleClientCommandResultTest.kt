package net.bladewatch.app.ui.fragment.vehicle

import net.bladewatch.app.grpc.v1.VehicleCommandResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class VehicleClientCommandResultTest {

    private fun resp(
        success: Boolean = true,
        outcome: String = "",
        message: String = "",
    ): VehicleCommandResponse = VehicleCommandResponse.newBuilder()
        .setSuccess(success)
        .setOutcome(outcome)
        .setMessage(message)
        .build()

    @Test
    fun `success true maps to ok=true`() {
        val result = VehicleClient.mapCommandResult(resp(success = true))
        assertTrue(result.ok)
    }

    @Test
    fun `success false maps to ok=false`() {
        val result = VehicleClient.mapCommandResult(resp(success = false))
        assertFalse(result.ok)
    }

    @Test
    fun `blank outcome maps to null`() {
        val result = VehicleClient.mapCommandResult(resp(outcome = ""))
        assertNull(result.outcome)
    }

    @Test
    fun `non-blank outcome is preserved`() {
        val result = VehicleClient.mapCommandResult(resp(outcome = "not_supported"))
        assertEquals("not_supported", result.outcome)
    }

    @Test
    fun `blank message maps to null`() {
        val result = VehicleClient.mapCommandResult(resp(message = ""))
        assertNull(result.message)
    }

    @Test
    fun `non-blank message is preserved`() {
        val result = VehicleClient.mapCommandResult(resp(message = "Trunk already open"))
        assertEquals("Trunk already open", result.message)
    }

    @Test
    fun `all fields populated maps correctly`() {
        val result = VehicleClient.mapCommandResult(resp(success = true, outcome = "success", message = "Done"))
        assertTrue(result.ok)
        assertEquals("success", result.outcome)
        assertEquals("Done", result.message)
    }
}
