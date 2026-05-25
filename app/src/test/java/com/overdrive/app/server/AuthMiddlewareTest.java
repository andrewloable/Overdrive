package com.overdrive.app.server;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;

public class AuthMiddlewareTest {

    @Before
    public void setUp() {
        AuthMiddleware.setLoopbackBypassOverride(null);
    }

    @After
    public void tearDown() {
        AuthMiddleware.setLoopbackBypassOverride(null);
    }

    @Test
    public void publicPathsRemainPublicWithoutAuth() throws Exception {
        Assert.assertTrue(checkPublic("/auth/token"));
        Assert.assertTrue(checkPublic("/auth/status"));
        Assert.assertTrue(checkPublic("/login.html"));
        Assert.assertTrue(checkPublic("/shared/app.js"));
        Assert.assertTrue(checkPublic("/i18n/en.json"));
    }

    @Test
    public void protectedApiWithoutJwtIsRejected() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        boolean allowed = AuthMiddleware.checkAuth(
                "/api/vehicle/unlock", null, null, out, null, false);
        Assert.assertFalse(allowed);
        Assert.assertTrue(out.toString("UTF-8").contains("401 Unauthorized"));
    }

    @Test
    public void loopbackWithoutJwtIsRejectedWhenBypassDisabled() throws Exception {
        AuthMiddleware.setLoopbackBypassOverride(Boolean.FALSE);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        boolean allowed = AuthMiddleware.checkAuth(
                "/api/vehicle/unlock", null, null, out,
                new InetSocketAddress("127.0.0.1", 8080), false);
        Assert.assertFalse(allowed);
        Assert.assertTrue(out.toString("UTF-8").contains("401 Unauthorized"));
    }

    @Test
    public void signedThumbPathStillAllowsTokenBasedAccess() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        boolean allowed = AuthMiddleware.checkAuth(
                "/thumb/event.jpg?t=invalid", null, null, out, null, false);
        Assert.assertFalse(allowed);
    }

    private boolean checkPublic(String path) throws Exception {
        return AuthMiddleware.checkAuth(path, null, null, new ByteArrayOutputStream(), null, false);
    }
}
