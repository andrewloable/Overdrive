package net.bladewatch.app.server.connect.impl;

import net.bladewatch.app.server.AuthApiHandler;
import net.bladewatch.app.server.connect.ConnectDispatcher;
import net.bladewatch.app.server.connect.ConnectException;
import net.bladewatch.app.server.connect.ConnectHandlerUtil;

import org.json.JSONObject;

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

    private String handleLogin(String requestJson) throws ConnectException {
        return ConnectHandlerUtil.captureString(out ->
                AuthApiHandler.handle("POST", "/auth/token", requestJson, out, "connect", false));
    }

    private String handleLogout(String requestJson) throws ConnectException {
        return ConnectHandlerUtil.captureString(out ->
                AuthApiHandler.handle("POST", "/auth/logout", "{}", out, null, false));
    }

    private String handleGetAuthStatus(String requestJson) throws ConnectException {
        return ConnectHandlerUtil.captureString(out ->
                AuthApiHandler.handle("GET", "/auth/status", null, out, null, false));
    }
}
