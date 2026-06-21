package net.bladewatch.app.server;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests for VehicleActionToken (uy93.5): issue and validate short-lived
 * HMAC-signed action tokens. Clock is injected via the test seam so tests
 * don't depend on wall-clock timing.
 */
public class VehicleActionTokenTest {

    private static final String SECRET = "device-secret-test-key-abc";
    private static final long BASE_TS = 1_700_000_000L; // arbitrary fixed epoch

    @Before
    public void setUp() {
        VehicleActionToken.clockOverrideForTest = BASE_TS;
    }

    @After
    public void tearDown() {
        VehicleActionToken.clockOverrideForTest = null;
    }

    @Test
    public void freshTokenIsValid() {
        String token = VehicleActionToken.issue(SECRET);
        Assert.assertTrue("fresh token must be valid", VehicleActionToken.validate(token, SECRET));
    }

    @Test
    public void tokenAtWindowBoundaryIsValid() {
        String token = VehicleActionToken.issue(SECRET);
        VehicleActionToken.clockOverrideForTest = BASE_TS + VehicleActionToken.WINDOW_SECONDS;
        Assert.assertTrue("token at boundary must be valid", VehicleActionToken.validate(token, SECRET));
    }

    @Test
    public void expiredTokenIsRejected() {
        String token = VehicleActionToken.issue(SECRET);
        VehicleActionToken.clockOverrideForTest = BASE_TS + VehicleActionToken.WINDOW_SECONDS + 1;
        Assert.assertFalse("expired token must be rejected", VehicleActionToken.validate(token, SECRET));
    }

    @Test
    public void futureTimestampIsRejected() {
        // A token with a future timestamp (e.g. issued from a clock-skewed node) is rejected.
        VehicleActionToken.clockOverrideForTest = BASE_TS + 5;
        String token = VehicleActionToken.issue(SECRET);
        // Now validate from earlier
        VehicleActionToken.clockOverrideForTest = BASE_TS;
        Assert.assertFalse("future-timestamped token must be rejected (age < 0)", VehicleActionToken.validate(token, SECRET));
    }

    @Test
    public void wrongSecretIsRejected() {
        String token = VehicleActionToken.issue(SECRET);
        Assert.assertFalse("wrong secret must be rejected", VehicleActionToken.validate(token, "different-secret"));
    }

    @Test
    public void tamperedSignatureIsRejected() {
        String token = VehicleActionToken.issue(SECRET);
        String tampered = token.substring(0, token.length() - 1) + (token.endsWith("A") ? "B" : "A");
        Assert.assertFalse("tampered signature must be rejected", VehicleActionToken.validate(tampered, SECRET));
    }

    @Test
    public void nullTokenIsRejected() {
        Assert.assertFalse(VehicleActionToken.validate(null, SECRET));
    }

    @Test
    public void emptyTokenIsRejected() {
        Assert.assertFalse(VehicleActionToken.validate("", SECRET));
    }

    @Test
    public void malformedTokenNoDotIsRejected() {
        Assert.assertFalse(VehicleActionToken.validate("nodothere", SECRET));
    }

    @Test
    public void tokenWithNonNumericTimestampIsRejected() {
        Assert.assertFalse(VehicleActionToken.validate("abc.somesig", SECRET));
    }

    @Test
    public void nullSecretIsRejected() {
        String token = VehicleActionToken.issue(SECRET);
        Assert.assertFalse(VehicleActionToken.validate(token, null));
    }

    @Test
    public void emptySecretIsRejected() {
        String token = VehicleActionToken.issue(SECRET);
        Assert.assertFalse(VehicleActionToken.validate(token, ""));
    }
}
