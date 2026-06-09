package net.bladewatch.app.server;

import net.bladewatch.app.config.UnifiedConfigManager;
import net.bladewatch.app.logging.DaemonLogger;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;

/**
 * Models API Handler — serves 3D vehicle models for the vehicle-control page.
 *
 * All models ship inside the APK (bundled: true in manifest.json) and are
 * extracted to WEB_ROOT at startup. No network downloads are performed.
 *
 * Endpoints:
 *   GET  /api/models/list              — manifest entries with status
 *   GET  /api/models/manifest          — bundled manifest JSON
 *   GET  /api/models/selected          — user's persisted model + color
 *   POST /api/models/selected          — save user's model + color selection
 *   POST /api/models/download?id=ID    — no-op stub (all models are bundled)
 *   GET  /api/models/status?id=ID      — always returns done (all bundled)
 *   POST /api/models/manifest/refresh  — no-op stub (no remote manifest)
 */
public class ModelsApiHandler {

    private static final String TAG = "ModelsApiHandler";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    public static final String MODELS_DIR = "/data/local/tmp/bladewatch/models";

    private static final String MANIFEST_BUNDLED_PATH = "/data/local/tmp/web/shared/models/manifest.json";

    public static boolean handle(String method, String path, String body, OutputStream out) throws Exception {
        String pathOnly = path.contains("?") ? path.substring(0, path.indexOf("?")) : path;

        if (pathOnly.equals("/api/models/list") && method.equals("GET")) {
            handleList(out);
            return true;
        }
        if (pathOnly.equals("/api/models/download")
                && (method.equals("POST") || method.equals("GET"))) {
            // All models are bundled in the APK — nothing to download.
            HttpResponse.sendJson(out, "{\"ok\":true,\"alreadyCached\":true}");
            return true;
        }
        if (pathOnly.equals("/api/models/status") && method.equals("GET")) {
            // All models are bundled — always report done.
            JSONObject response = new JSONObject();
            response.put("state", "done");
            response.put("percent", 100);
            response.put("downloaded", true);
            HttpResponse.sendJson(out, response.toString());
            return true;
        }
        if (pathOnly.equals("/api/models/selected") && method.equals("GET")) {
            handleGetSelected(out);
            return true;
        }
        if (pathOnly.equals("/api/models/selected") && method.equals("POST")) {
            handleSetSelected(out, body);
            return true;
        }
        if (pathOnly.equals("/api/models/manifest") && method.equals("GET")) {
            handleGetManifest(out);
            return true;
        }
        if (pathOnly.equals("/api/models/manifest/refresh") && method.equals("POST")) {
            // Manifest ships with the APK — no remote refresh needed.
            JSONObject manifest = readManifest();
            JSONObject response = new JSONObject();
            response.put("updated", false);
            response.put("stale", false);
            response.put("version", manifest != null ? manifest.optInt("version", 0) : 0);
            HttpResponse.sendJson(out, response.toString());
            return true;
        }
        return false;
    }

    private static void handleGetManifest(OutputStream out) throws Exception {
        JSONObject manifest = readManifest();
        if (manifest == null) {
            HttpResponse.sendJsonError(out, Messages.get("errors.models_no_manifest"));
            return;
        }
        HttpResponse.sendJson(out, manifest.toString());
    }

    private static void handleGetSelected(OutputStream out) throws Exception {
        JSONObject vehicle = UnifiedConfigManager.getVehicle();
        JSONObject manifest = readManifest();
        String defaultId = manifest != null ? manifest.optString("default", "destroyer") : "destroyer";
        String modelId = vehicle.optString("modelId", defaultId);
        if (manifest != null && findModel(manifest, modelId) == null) {
            modelId = defaultId;
        }
        JSONObject response = new JSONObject();
        response.put("modelId", modelId);
        response.put("color", vehicle.optString("color", "#E8E8EC"));
        HttpResponse.sendJson(out, response.toString());
    }

    private static void handleSetSelected(OutputStream out, String body) throws Exception {
        if (body == null || body.isEmpty()) {
            HttpResponse.sendJsonError(out, Messages.get("errors.models_empty_body"));
            return;
        }
        JSONObject incoming;
        try {
            incoming = (JSONObject) new JSONTokener(body).nextValue();
        } catch (Exception e) {
            logger.warn("Failed to parse incoming JSON body: " + e.getMessage());
            HttpResponse.sendJsonError(out, Messages.get("errors.models_invalid_json"));
            return;
        }
        JSONObject patch = new JSONObject();
        if (incoming.has("modelId")) {
            String id = incoming.optString("modelId");
            JSONObject manifest = readManifest();
            if (manifest != null && findModel(manifest, id) == null) {
                HttpResponse.sendJsonError(out, Messages.get("errors.models_unknown_id_with_id", id));
                return;
            }
            patch.put("modelId", id);
        }
        if (incoming.has("color")) {
            String color = incoming.optString("color", "");
            if (!color.matches("^#[0-9A-Fa-f]{6}$")) {
                HttpResponse.sendJsonError(out, Messages.get("errors.models_invalid_color"));
                return;
            }
            patch.put("color", color);
        }
        if (patch.length() == 0) {
            HttpResponse.sendJsonError(out, Messages.get("errors.models_nothing_to_update"));
            return;
        }
        boolean ok = UnifiedConfigManager.setVehicle(patch);
        if (!ok) {
            HttpResponse.sendJsonError(out, Messages.get("errors.models_persist_failed"));
            return;
        }
        HttpResponse.sendJson(out, "{\"ok\":true}");
    }

    /** Returns a GLB from the legacy download cache, used as fallback by HttpServer. */
    public static File cachedModelFile(String fileName) {
        if (fileName == null || fileName.contains("/") || fileName.contains("..")) return null;
        File f = new File(MODELS_DIR, fileName);
        return f.exists() && f.isFile() ? f : null;
    }

    /**
     * Look up the nominal pack capacity (kWh) for the user-selected model.
     * Used by SohEstimator as a capacity hint. Returns 0 on any failure.
     */
    public static double nominalKwhForSelectedModel() {
        try {
            String modelId = UnifiedConfigManager.getVehicle().optString("modelId", "");
            if (modelId.isEmpty()) return 0;
            JSONObject manifest = readManifest();
            if (manifest == null) return 0;
            JSONObject m = findModel(manifest, modelId);
            if (m == null) return 0;
            return m.optDouble("nominalKwh", 0);
        } catch (Throwable t) {
            logger.warn("nominalKwhForSelectedModel failed: " + t.getMessage());
            return 0;
        }
    }

    private static void handleList(OutputStream out) throws Exception {
        JSONObject manifest = readManifest();
        if (manifest == null) {
            HttpResponse.sendJsonError(out, Messages.get("errors.models_manifest_unavailable"));
            return;
        }
        JSONArray models = manifest.optJSONArray("models");
        if (models == null) models = new JSONArray();

        JSONArray result = new JSONArray();
        for (int i = 0; i < models.length(); i++) {
            JSONObject m = models.getJSONObject(i);
            JSONObject o = new JSONObject();
            o.put("id", m.optString("id"));
            o.put("name", m.optString("name", m.optString("id")));
            o.put("file", m.optString("file"));
            o.put("sizeBytes", m.optLong("sizeBytes", 0));
            o.put("bundled", true);
            o.put("downloaded", true);
            o.put("cachedSizeBytes", 0);
            result.put(o);
        }

        JSONObject response = new JSONObject();
        response.put("models", result);
        response.put("default", manifest.optString("default", "destroyer"));
        HttpResponse.sendJson(out, response.toString());
    }

    private static JSONObject readManifest() {
        return readManifestFile(new File(MANIFEST_BUNDLED_PATH));
    }

    private static JSONObject readManifestFile(File f) {
        if (!f.exists()) return null;
        try (FileInputStream fis = new FileInputStream(f)) {
            byte[] buf = new byte[(int) f.length()];
            int totalRead = 0;
            while (totalRead < buf.length) {
                int n = fis.read(buf, totalRead, buf.length - totalRead);
                if (n == -1) break;
                totalRead += n;
            }
            String json = new String(buf, 0, totalRead, "UTF-8");
            JSONObject parsed = (JSONObject) new JSONTokener(json).nextValue();
            if (!parsed.has("version") || parsed.optJSONArray("models") == null) {
                logger.warn(TAG + ": manifest at " + f.getAbsolutePath() + " missing required fields");
                return null;
            }
            return parsed;
        } catch (Exception e) {
            logger.warn(TAG + ": failed to read manifest at " + f.getAbsolutePath() + ": " + e.getMessage());
            return null;
        }
    }

    private static JSONObject findModel(JSONObject manifest, String id) {
        JSONArray arr = manifest.optJSONArray("models");
        if (arr == null) return null;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject m = arr.optJSONObject(i);
            if (m != null && id.equals(m.optString("id"))) return m;
        }
        return null;
    }

    private static String queryParam(String path, String key) {
        int q = path.indexOf('?');
        if (q < 0) return null;
        String query = path.substring(q + 1);
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) continue;
            if (key.equals(pair.substring(0, eq))) {
                try {
                    return java.net.URLDecoder.decode(pair.substring(eq + 1), "UTF-8");
                } catch (Exception e) {
                    logger.warn("Failed to URL-decode query parameter: " + e.getMessage());
                    return null;
                }
            }
        }
        return null;
    }
}
