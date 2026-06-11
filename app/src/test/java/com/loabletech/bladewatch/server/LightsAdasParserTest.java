package net.bladewatch.app.server;

import net.bladewatch.app.server.VehicleControlApiHandler.ToggleRequestParse;

import org.json.JSONObject;
import org.junit.Assert;
import org.junit.Test;

/**
 * Unit tests for parseLightsRequest / parseAdasRequest pure static functions
 * (BladeWatch-f3yp). Verifies proto shape (action/on), legacy shape (target/enable),
 * absent-boolean error, and wrong-target error for both endpoints.
 */
public class LightsAdasParserTest {

    // ==================== Lights ====================

    @Test
    public void lights_protoShape_onTrue() throws Exception {
        JSONObject req = new JSONObject("{\"action\":\"dayTimeLight\",\"on\":true}");
        ToggleRequestParse p = VehicleControlApiHandler.parseLightsRequest(req);
        Assert.assertNull("should parse cleanly", p.error);
        Assert.assertEquals("dayTimeLight", p.target);
        Assert.assertTrue(p.enable);
    }

    @Test
    public void lights_protoShape_onFalse() throws Exception {
        // Proto omits false scalars, but if "on" key is present with false it must parse as false
        JSONObject req = new JSONObject("{\"action\":\"dayTimeLight\",\"on\":false}");
        ToggleRequestParse p = VehicleControlApiHandler.parseLightsRequest(req);
        Assert.assertNull("should parse cleanly", p.error);
        Assert.assertFalse("on=false must parse as disable", p.enable);
    }

    @Test
    public void lights_legacyShape_enableTrue() throws Exception {
        JSONObject req = new JSONObject("{\"target\":\"dayTimeLight\",\"enable\":true}");
        ToggleRequestParse p = VehicleControlApiHandler.parseLightsRequest(req);
        Assert.assertNull("legacy shape must parse cleanly", p.error);
        Assert.assertEquals("dayTimeLight", p.target);
        Assert.assertTrue(p.enable);
    }

    @Test
    public void lights_legacyShape_enableFalse() throws Exception {
        JSONObject req = new JSONObject("{\"target\":\"dayTimeLight\",\"enable\":false}");
        ToggleRequestParse p = VehicleControlApiHandler.parseLightsRequest(req);
        Assert.assertNull("legacy shape must parse cleanly", p.error);
        Assert.assertFalse(p.enable);
    }

    @Test
    public void lights_absentBoolean_returnsError() throws Exception {
        JSONObject req = new JSONObject("{\"action\":\"dayTimeLight\"}");
        ToggleRequestParse p = VehicleControlApiHandler.parseLightsRequest(req);
        Assert.assertNotNull("absent boolean must error", p.error);
    }

    @Test
    public void lights_wrongTarget_returnsError() throws Exception {
        JSONObject req = new JSONObject("{\"action\":\"flashLights\",\"on\":true}");
        ToggleRequestParse p = VehicleControlApiHandler.parseLightsRequest(req);
        Assert.assertNotNull("wrong target must error", p.error);
    }

    // ==================== ADAS ====================

    @Test
    public void adas_protoShape_onTrue() throws Exception {
        JSONObject req = new JSONObject("{\"action\":\"speedLimitWarning\",\"on\":true}");
        ToggleRequestParse p = VehicleControlApiHandler.parseAdasRequest(req);
        Assert.assertNull("should parse cleanly", p.error);
        Assert.assertEquals("speedLimitWarning", p.target);
        Assert.assertTrue(p.enable);
    }

    @Test
    public void adas_protoShape_onFalse() throws Exception {
        JSONObject req = new JSONObject("{\"action\":\"speedLimitWarning\",\"on\":false}");
        ToggleRequestParse p = VehicleControlApiHandler.parseAdasRequest(req);
        Assert.assertNull("should parse cleanly", p.error);
        Assert.assertFalse("on=false must parse as disable", p.enable);
    }

    @Test
    public void adas_legacyShape_enableTrue() throws Exception {
        JSONObject req = new JSONObject("{\"target\":\"speedLimitWarning\",\"enable\":true}");
        ToggleRequestParse p = VehicleControlApiHandler.parseAdasRequest(req);
        Assert.assertNull("legacy shape must parse cleanly", p.error);
        Assert.assertEquals("speedLimitWarning", p.target);
        Assert.assertTrue(p.enable);
    }

    @Test
    public void adas_legacyShape_enableFalse() throws Exception {
        JSONObject req = new JSONObject("{\"target\":\"speedLimitWarning\",\"enable\":false}");
        ToggleRequestParse p = VehicleControlApiHandler.parseAdasRequest(req);
        Assert.assertNull("legacy shape must parse cleanly", p.error);
        Assert.assertFalse(p.enable);
    }

    @Test
    public void adas_absentBoolean_returnsError() throws Exception {
        JSONObject req = new JSONObject("{\"action\":\"speedLimitWarning\"}");
        ToggleRequestParse p = VehicleControlApiHandler.parseAdasRequest(req);
        Assert.assertNotNull("absent boolean must error", p.error);
    }

    @Test
    public void adas_wrongTarget_returnsError() throws Exception {
        JSONObject req = new JSONObject("{\"action\":\"emergencyBrake\",\"on\":true}");
        ToggleRequestParse p = VehicleControlApiHandler.parseAdasRequest(req);
        Assert.assertNotNull("wrong target must error", p.error);
    }
}
