package net.bladewatch.app.server;

import net.bladewatch.app.auth.AuthManager;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;

/**
 * Characterization tests — pins that a LEGITIMATE authenticated /api/vehicle/* request
 * currently PASSES AuthMiddleware, before uy93.5 adds a second factor. After uy93.5, these
 * same paths must still pass AuthMiddleware AND pass the second factor; this baseline
 * ensures the happy path isn't broken during the refactor.
 */
public class VehicleApiAuthTest {

    @Before
    public void setUp() {
        AuthManager.AuthState state = new AuthManager.AuthState();
        state.deviceId = "byd-test-device";
        state.deviceSecret = "test-secret-key-123";
        state.tokenEpoch = 0;
        state.lastAccess = 0;
        AuthManager.setTestState(state);
        AuthMiddleware.setLoopbackBypassOverride(null);
    }

    @After
    public void tearDown() {
        AuthManager.clearTestState();
        AuthMiddleware.setLoopbackBypassOverride(null);
    }

    // --- Authenticated /api/vehicle/* routes pass AuthMiddleware ---

    @Test
    public void vehicleUnlockWithValidJwtPassesAuth() throws Exception {
        String jwt = AuthManager.generateJwt();
        Assert.assertNotNull("JWT generation must succeed", jwt);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        boolean allowed = AuthMiddleware.checkAuth(
                "/api/vehicle/unlock", null, "Bearer " + jwt, out, null, false);
        Assert.assertTrue("Valid JWT must pass auth for /api/vehicle/unlock", allowed);
    }

    @Test
    public void vehicleLockWithValidJwtPassesAuth() throws Exception {
        String jwt = AuthManager.generateJwt();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        boolean allowed = AuthMiddleware.checkAuth(
                "/api/vehicle/lock", null, "Bearer " + jwt, out, null, false);
        Assert.assertTrue("Valid JWT must pass auth for /api/vehicle/lock", allowed);
    }

    @Test
    public void vehicleTrunkWithValidJwtPassesAuth() throws Exception {
        String jwt = AuthManager.generateJwt();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        boolean allowed = AuthMiddleware.checkAuth(
                "/api/vehicle/trunk", null, "Bearer " + jwt, out, null, false);
        Assert.assertTrue(allowed);
    }

    @Test
    public void vehicleFlashWithValidJwtPassesAuth() throws Exception {
        String jwt = AuthManager.generateJwt();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        boolean allowed = AuthMiddleware.checkAuth(
                "/api/vehicle/flash", null, "Bearer " + jwt, out, null, false);
        Assert.assertTrue(allowed);
    }

    @Test
    public void vehicleFindCarWithValidJwtPassesAuth() throws Exception {
        String jwt = AuthManager.generateJwt();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        boolean allowed = AuthMiddleware.checkAuth(
                "/api/vehicle/find-car", null, "Bearer " + jwt, out, null, false);
        Assert.assertTrue(allowed);
    }

    @Test
    public void vehicleBatteryHeatWithValidJwtPassesAuth() throws Exception {
        String jwt = AuthManager.generateJwt();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        boolean allowed = AuthMiddleware.checkAuth(
                "/api/vehicle/battery-heat", null, "Bearer " + jwt, out, null, false);
        Assert.assertTrue(allowed);
    }

    @Test
    public void vehicleWindowWithValidJwtPassesAuth() throws Exception {
        String jwt = AuthManager.generateJwt();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        boolean allowed = AuthMiddleware.checkAuth(
                "/api/vehicle/window", null, "Bearer " + jwt, out, null, false);
        Assert.assertTrue(allowed);
    }

    @Test
    public void vehicleLightsWithValidJwtPassesAuth() throws Exception {
        String jwt = AuthManager.generateJwt();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        boolean allowed = AuthMiddleware.checkAuth(
                "/api/vehicle/lights", null, "Bearer " + jwt, out, null, false);
        Assert.assertTrue(allowed);
    }

    @Test
    public void vehicleAdasWithValidJwtPassesAuth() throws Exception {
        String jwt = AuthManager.generateJwt();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        boolean allowed = AuthMiddleware.checkAuth(
                "/api/vehicle/adas", null, "Bearer " + jwt, out, null, false);
        Assert.assertTrue(allowed);
    }

    @Test
    public void vehicleChargingScheduleWithValidJwtPassesAuth() throws Exception {
        String jwt = AuthManager.generateJwt();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        boolean allowed = AuthMiddleware.checkAuth(
                "/api/vehicle/charging-schedule", null, "Bearer " + jwt, out, null, false);
        Assert.assertTrue(allowed);
    }

    @Test
    public void vehicleStateGetWithValidJwtPassesAuth() throws Exception {
        String jwt = AuthManager.generateJwt();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        boolean allowed = AuthMiddleware.checkAuth(
                "/api/vehicle/state", null, "Bearer " + jwt, out, null, false);
        Assert.assertTrue(allowed);
    }

    // --- JWT in cookie also passes (WebView loopback path) ---

    @Test
    public void vehicleUnlockWithJwtInCookiePassesAuth() throws Exception {
        String jwt = AuthManager.generateJwt();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        boolean allowed = AuthMiddleware.checkAuth(
                "/api/vehicle/unlock", "byd_session=" + jwt, null, out, null, false);
        Assert.assertTrue("JWT in cookie must pass auth (WebView path)", allowed);
    }

    // --- Reject cases still hold ---

    @Test
    public void vehicleUnlockWithoutJwtIsRejected() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        boolean allowed = AuthMiddleware.checkAuth(
                "/api/vehicle/unlock", null, null, out, null, false);
        Assert.assertFalse(allowed);
        Assert.assertTrue(out.toString("UTF-8").contains("401"));
    }

    @Test
    public void vehicleUnlockWithTamperedJwtIsRejected() throws Exception {
        String jwt = AuthManager.generateJwt();
        String tampered = jwt.substring(0, jwt.length() - 1) + "X";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        boolean allowed = AuthMiddleware.checkAuth(
                "/api/vehicle/unlock", null, "Bearer " + tampered, out, null, false);
        Assert.assertFalse(allowed);
    }

    // --- uy93.5: VehicleActionToken second factor is correctly scoped ---

    @Test
    public void actionTokenIssuedWithDeviceSecretIsValid() {
        // The issued token validates against the same device secret (positive path for LAN callers).
        String secret = "test-secret-key-123";
        String token = VehicleActionToken.issue(secret);
        Assert.assertTrue("freshly issued token must be valid", VehicleActionToken.validate(token, secret));
    }

    @Test
    public void actionTokenWithDifferentSecretIsRejected() {
        // A replayed/stolen token issued by a different secret is rejected.
        String token = VehicleActionToken.issue("attacker-secret");
        Assert.assertFalse("token from wrong secret must be rejected",
                VehicleActionToken.validate(token, "test-secret-key-123"));
    }

    @Test
    public void jwtAloneDoesNotValidateActionToken() {
        // A raw JWT string is not a valid action token (no timestamp.sig format).
        String jwt = AuthManager.generateJwt();
        Assert.assertFalse("JWT string must not pass as action token",
                VehicleActionToken.validate(jwt, "test-secret-key-123"));
    }
}
