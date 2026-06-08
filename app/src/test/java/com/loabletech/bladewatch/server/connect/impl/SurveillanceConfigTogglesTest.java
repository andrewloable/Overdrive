package net.bladewatch.app.server.connect.impl;

import com.google.protobuf.Descriptors;

import org.junit.Assert;
import org.junit.Test;

import java.util.List;

/**
 * Guards SurveillanceService.SetConfig against the omitted-scalar persistence bug
 * (BladeWatch-mvay). The Connect client sends a full config snapshot, but JsonFormat
 * omits any bool that is false; the REST handler reads those toggles with has()-gating,
 * so an omitted false would silently keep the old (ON) value. SurveillanceServiceImpl
 * re-adds every boolean field as false when absent. This test locks that the re-add set
 * is derived from the proto descriptor and therefore covers EVERY bool field — including
 * any added later — so the list cannot drift out of sync.
 */
public class SurveillanceConfigTogglesTest {

    @Test
    public void togglesCoverEveryKnownSurveillanceConfigBool() {
        List<String> toggles = SurveillanceServiceImpl.CONFIG_BOOLEAN_TOGGLES;
        // The full set of bool json-names the snapshot can carry today.
        String[] expected = {
                "enabled", "aiEnabled", "detectPerson", "detectCar", "detectBike",
                "nightMode", "cameraFront", "cameraRight", "cameraRear", "cameraLeft"
        };
        for (String e : expected) {
            Assert.assertTrue(
                    "CONFIG_BOOLEAN_TOGGLES missing bool json-name \"" + e + "\" (have " + toggles + ")",
                    toggles.contains(e));
        }
    }

    @Test
    public void togglesEqualDescriptorBoolCount_soNewBoolsAreAutoCovered() {
        long boolCount = net.bladewatch.app.grpc.v1.SurveillanceConfig.getDescriptor().getFields()
                .stream()
                .filter(f -> f.getType() == Descriptors.FieldDescriptor.Type.BOOL)
                .count();
        Assert.assertEquals(
                "Toggle list must include every bool field of SurveillanceConfig; a new bool added "
                        + "to the proto must appear automatically (derive from the descriptor, do not hand-list).",
                boolCount, SurveillanceServiceImpl.CONFIG_BOOLEAN_TOGGLES.size());
    }

    @Test
    public void togglesUseCamelCaseJsonNames() {
        // The REST handler and the client both speak JsonFormat camelCase; the re-add
        // keys must match those, not the proto snake_case field names.
        Assert.assertTrue(SurveillanceServiceImpl.CONFIG_BOOLEAN_TOGGLES.contains("detectPerson"));
        Assert.assertFalse(SurveillanceServiceImpl.CONFIG_BOOLEAN_TOGGLES.contains("detect_person"));
    }
}
