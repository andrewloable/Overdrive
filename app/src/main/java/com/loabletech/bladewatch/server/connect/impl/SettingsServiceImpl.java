package net.bladewatch.app.server.connect.impl;

import net.bladewatch.app.server.LocaleManager;
import net.bladewatch.app.server.QualitySettingsApiHandler;
import net.bladewatch.app.server.connect.ConnectDispatcher;
import net.bladewatch.app.server.connect.ConnectException;
import net.bladewatch.app.server.connect.ConnectHandlerUtil;

import org.json.JSONObject;

/**
 * Connect protocol handler for bladewatch.v1.SettingsService.
 *
 * Routes:
 *   GetQuality     → GET  /api/settings/quality
 *   SetQuality     → POST /api/settings/quality
 *   GetAppearance  → GET  /api/settings/appearance
 *   SetAppearance  → POST /api/settings/appearance
 *   GetLocale      → GET  /api/i18n/lang  (via LocaleManager directly)
 *   SetLocale      → POST /api/i18n/lang  (via LocaleManager directly)
 */
public class SettingsServiceImpl {

    public void register(ConnectDispatcher dispatcher) {
        dispatcher.register("bladewatch.v1.SettingsService", "GetQuality", this::handleGetQuality);
        dispatcher.register("bladewatch.v1.SettingsService", "SetQuality", this::handleSetQuality);
        dispatcher.register("bladewatch.v1.SettingsService", "GetAppearance", this::handleGetAppearance);
        dispatcher.register("bladewatch.v1.SettingsService", "SetAppearance", this::handleSetAppearance);
        dispatcher.register("bladewatch.v1.SettingsService", "GetLocale", this::handleGetLocale);
        dispatcher.register("bladewatch.v1.SettingsService", "SetLocale", this::handleSetLocale);
    }

    private String handleGetQuality(String req) throws ConnectException {
        return ConnectHandlerUtil.captureString(out ->
                QualitySettingsApiHandler.handle("GET", "/api/settings/quality", null, out));
    }

    private String handleSetQuality(String req) throws ConnectException {
        return ConnectHandlerUtil.captureString(out ->
                QualitySettingsApiHandler.handle("POST", "/api/settings/quality", req, out));
    }

    private String handleGetAppearance(String req) throws ConnectException {
        return ConnectHandlerUtil.captureString(out ->
                QualitySettingsApiHandler.handle("GET", "/api/settings/appearance", null, out));
    }

    private String handleSetAppearance(String req) throws ConnectException {
        return ConnectHandlerUtil.captureString(out ->
                QualitySettingsApiHandler.handle("POST", "/api/settings/appearance", req, out));
    }

    private String handleGetLocale(String req) throws ConnectException {
        try {
            JSONObject resp = new JSONObject();
            resp.put("lang", LocaleManager.get());
            JSONObject supported = new JSONObject();
            for (String s : LocaleManager.SUPPORTED) supported.put(s, true);
            resp.put("supported", supported);
            return resp.toString();
        } catch (Exception e) {
            throw new ConnectException("internal", "Failed to get locale: " + e.getMessage());
        }
    }

    private String handleSetLocale(String req) throws ConnectException {
        try {
            String want = "";
            try {
                want = new JSONObject(req).optString("lang", "");
            } catch (Exception ignored) {}
            String resolved = LocaleManager.set(want);
            return "{\"lang\":\"" + resolved + "\"}";
        } catch (Exception e) {
            throw new ConnectException("internal", "Failed to set locale: " + e.getMessage());
        }
    }
}
