package net.bladewatch.app.server.connect.impl;

import net.bladewatch.app.server.AuthApiHandler;
import net.bladewatch.app.server.connect.ConnectDispatcher;
import net.bladewatch.app.server.connect.ConnectException;
import net.bladewatch.app.server.connect.ConnectHandlerUtil;
import net.bladewatch.app.server.connect.ConnectResponse;

/**
 * Connect protocol handler for bladewatch.v1.AuthService.
 *
 * Routes:
 *   /bladewatch.v1.AuthService/Login           → POST /auth/token
 *   /bladewatch.v1.AuthService/Logout          → POST /auth/logout
 *   /bladewatch.v1.AuthService/GetAuthStatus   → GET  /auth/status
 */
public class AuthServiceImpl {

    public void register(ConnectDispatcher dispatcher) {
        dispatcher.register("bladewatch.v1.AuthService", "Login", this::handleLogin);
        dispatcher.register("bladewatch.v1.AuthService", "Logout", this::handleLogout);
        dispatcher.register("bladewatch.v1.AuthService", "GetAuthStatus", this::handleGetAuthStatus);
    }

    private ConnectResponse handleLogin(String requestJson, String clientIdentity) throws ConnectException {
        // Use captureWithCookies so the byd_session Set-Cookie header set by
        // AuthApiHandler.handleTokenValidation is forwarded to the browser.
        // clientIdentity is the real client IP (X-Forwarded-For or socket) for per-IP rate limiting.
        return ConnectHandlerUtil.captureWithCookies(out ->
                AuthApiHandler.handle("POST", "/auth/token", requestJson, out, clientIdentity, false));
    }

    private ConnectResponse handleLogout(String requestJson, String clientIdentity) throws ConnectException {
        // Use captureWithCookies so the cookie-expiry Set-Cookie headers that
        // AuthApiHandler.handleLogout emits (to clear byd_session/byd_auth) are
        // forwarded to the browser — captureString would drop them.
        return ConnectHandlerUtil.captureWithCookies(out ->
                AuthApiHandler.handle("POST", "/auth/logout", "{}", out, null, false));
    }

    private ConnectResponse handleGetAuthStatus(String requestJson, String clientIdentity) throws ConnectException {
        return ConnectHandlerUtil.captureString(out ->
                AuthApiHandler.handle("GET", "/auth/status", null, out, null, false));
    }
}
