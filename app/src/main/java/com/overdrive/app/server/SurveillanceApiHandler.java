package com.overdrive.app.server;

import com.overdrive.app.daemon.CameraDaemon;
import com.overdrive.app.surveillance.GpuSurveillancePipeline;
import com.overdrive.app.surveillance.SurveillanceConfig;
import com.overdrive.app.surveillance.SurveillanceConfigManager;
import com.overdrive.app.surveillance.SurveillanceEngineGpu;
import com.overdrive.app.surveillance.MotionPipelineV2;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;

/**
 * Surveillance API Handler - manages surveillance configuration and status.
 * 
 * SOTA: Distance slider (1-5) controls minObjectSize for AI detection range.
 * SOTA: Sensitivity slider (1-5) controls requiredBlocks for motion detection.
 * Block size is LOCKED at 32 - never changes.
 */
public class SurveillanceApiHandler {
    
    private static final String UNIFIED_CONFIG_FILE = "/data/local/tmp/overdrive_config.json";
    
    /**
     * Handle surveillance API requests.
     * @return true if handled
     */
    public static boolean handle(String method, String path, String body, OutputStream out) throws Exception {
        // Strip query parameters for path matching
        String cleanPath = path.contains("?") ? path.substring(0, path.indexOf("?")) : path;
        
        if (cleanPath.equals("/api/surveillance/config") && method.equals("GET")) {
            sendConfig(out);
            return true;
        }
        if (cleanPath.equals("/api/surveillance/config") && method.equals("POST")) {
            handleConfigPost(out, body);
            return true;
        }
        if (cleanPath.equals("/api/surveillance/status")) {
            sendStatus(out);
            return true;
        }
        if (cleanPath.equals("/api/surveillance/enable")) {
            handleEnable(out);
            return true;
        }
        if (cleanPath.equals("/api/surveillance/disable")) {
            handleDisable(out);
            return true;
        }
        if (cleanPath.equals("/api/surveillance/heatmap")) {
            sendHeatmap(out);
            return true;
        }
        if (cleanPath.startsWith("/api/surveillance/snapshot/")) {
            try {
                int quadrant = Integer.parseInt(cleanPath.substring("/api/surveillance/snapshot/".length()));
                sendQuadrantSnapshot(out, quadrant);
            } catch (NumberFormatException e) {
                HttpResponse.sendJsonError(out, Messages.get("errors.surveillance_invalid_quadrant_id"));
            }
            return true;
        }
        if (cleanPath.equals("/api/surveillance/filterlog")) {
            sendFilterLog(out);
            return true;
        }
        if (cleanPath.equals("/api/surveillance/camera-preview")) {
            sendCameraPreview(path, out);
            return true;
        }
        if (cleanPath.equals("/api/surveillance/prepare-restart") && method.equals("POST")) {
            handlePrepareRestart(out);
            return true;
        }
        if (cleanPath.equals("/api/surveillance/abort-restart") && method.equals("POST")) {
            // Companion to prepare-restart: if the dialog's SIGKILL fails
            // and the daemon survives, this lets the client unstick the
            // shutdown latch so future preview requests work again without
            // needing a manual daemon restart.
            shutdownInProgress = false;
            CameraDaemon.log("abort-restart: shutdown latch cleared");
            HttpResponse.sendJsonSuccess(out);
            return true;
        }
        if (cleanPath.equals("/api/surveillance/screen-deterrent/image") && method.equals("POST")) {
            handleScreenDeterrentImageUpload(out, body);
            return true;
        }
        if (cleanPath.equals("/api/surveillance/screen-deterrent/image") && method.equals("GET")) {
            handleScreenDeterrentImageGet(out);
            return true;
        }
        return false;
    }

    /**
     * Only files inside this directory + with this filename prefix can be
     * served or deleted by the deterrent endpoints. Without this lock, an
     * attacker who could touch UCM (world-writable JSON) could redirect
     * screenDeterrentImagePath at /etc/* and have the web server stream or
     * delete arbitrary readable files.
     */
    private static final String SCREEN_DETERRENT_DIR = "/data/local/tmp/.overdrive";
    private static final String SCREEN_DETERRENT_PREFIX = "screen_deterrent_asset.";

    private static boolean isAllowedDeterrentPath(String path) {
        if (path == null || path.isEmpty()) return false;
        try {
            java.io.File f = new java.io.File(path).getCanonicalFile();
            java.io.File parent = f.getParentFile();
            java.io.File expected = new java.io.File(SCREEN_DETERRENT_DIR).getCanonicalFile();
            return parent != null
                && parent.equals(expected)
                && f.getName().startsWith(SCREEN_DETERRENT_PREFIX);
        } catch (Exception e) {
            return false;
        }
    }

    private static void handleScreenDeterrentImageGet(OutputStream out) throws Exception {
        String path = com.overdrive.app.config.UnifiedConfigManager.getSurveillance()
                .optString("screenDeterrentImagePath", "");
        if (path.isEmpty()) {
            HttpResponse.sendError(out, 404, "No deterrent image set");
            return;
        }
        if (!isAllowedDeterrentPath(path)) {
            HttpResponse.sendError(out, 403, "Forbidden");
            return;
        }
        java.io.File f = new java.io.File(path);
        if (!f.exists() || f.length() == 0) {
            HttpResponse.sendError(out, 404, "Deterrent image missing");
            return;
        }

        String contentType;
        String lower = path.toLowerCase();
        if (lower.endsWith(".gif")) contentType = "image/gif";
        else if (lower.endsWith(".webp")) contentType = "image/webp";
        else if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) contentType = "image/jpeg";
        else contentType = "image/png";

        // no-cache: the deterrent asset changes in place when the user
        // re-uploads. Without this, second-viewer clients (Telegram preview,
        // a shared-link tunnel viewer, etc.) keep the previous image cached
        // for 24h and the cache-bust query param only helps the uploader's
        // own browser session.
        HttpResponse.sendImage(out, f, contentType, "no-cache");
    }

    /**
     * Accept a base64-encoded image/GIF for the screen deterrent. JSON body:
     *   { "filename": "warning.gif", "dataBase64": "<base64>" }
     *
     * Persists to /data/local/tmp/.overdrive/screen_deterrent_asset.<ext>
     * world-readable so the daemon UID 2000 can decode it. Updates
     * surveillance.screenDeterrentImagePath in unified config on success.
     */
    private static void handleScreenDeterrentImageUpload(OutputStream out, String body) throws Exception {
        if (body == null || body.isEmpty()) {
            HttpResponse.sendJsonError(out, "Empty request body");
            return;
        }

        JSONObject req;
        try {
            req = new JSONObject(body);
        } catch (Exception e) {
            HttpResponse.sendJsonError(out, "Invalid JSON");
            return;
        }

        String filename = req.optString("filename", "image.png");
        String dataB64 = req.optString("dataBase64", "");
        if (dataB64.isEmpty()) {
            HttpResponse.sendJsonError(out, "Missing dataBase64");
            return;
        }

        // Strip optional "data:image/png;base64," prefix the browser may include.
        int comma = dataB64.indexOf(',');
        if (dataB64.startsWith("data:") && comma > 0) {
            dataB64 = dataB64.substring(comma + 1);
        }

        byte[] data;
        try {
            data = android.util.Base64.decode(dataB64, android.util.Base64.DEFAULT);
        } catch (Exception e) {
            HttpResponse.sendJsonError(out, "Invalid base64");
            return;
        }

        // Reject 0-byte uploads — they'd pass the path-restriction checks
        // later but produce a "current asset" preview that's broken.
        if (data.length == 0) {
            HttpResponse.sendJsonError(out, "Empty file");
            return;
        }
        // Cap at 8 MB — anything bigger is unlikely to be a deterrent asset
        // and the GIF Movie API can't handle huge files anyway.
        if (data.length > 8 * 1024 * 1024) {
            HttpResponse.sendJsonError(out, "File too large (max 8MB)");
            return;
        }

        // Determine extension from filename (default .png).
        String ext = "png";
        int dot = filename.lastIndexOf('.');
        if (dot > 0 && dot < filename.length() - 1) {
            String e = filename.substring(dot + 1).toLowerCase();
            if (e.equals("png") || e.equals("jpg") || e.equals("jpeg")
                    || e.equals("webp") || e.equals("gif")) {
                ext = e;
            }
        }

        java.io.File dir = new java.io.File("/data/local/tmp/.overdrive");
        if (!dir.exists()) {
            dir.mkdirs();
            try { dir.setReadable(true, false); dir.setExecutable(true, false); } catch (Exception ignored) {}
        }

        // Atomic write: stream into a .tmp sibling, fsync, then rename. If
        // anything fails (disk full, permission denied, IOException) we
        // bail before touching the previous asset, so the user doesn't lose
        // their working image to a botched upload.
        java.io.File outFile = new java.io.File(dir, "screen_deterrent_asset." + ext);
        java.io.File tmpFile = new java.io.File(dir, "screen_deterrent_asset." + ext + ".tmp");
        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(tmpFile)) {
            fos.write(data);
            fos.getFD().sync();
        } catch (Throwable t) {
            try { tmpFile.delete(); } catch (Exception ignored) {}
            HttpResponse.sendJsonError(out, "Write failed: " + t.getMessage());
            return;
        }
        try { tmpFile.setReadable(true, false); } catch (Exception ignored) {}

        // Stage successful — now clean up any previous assets and atomically
        // swap in the new one. The "latest only" invariant: one and only
        // one screen_deterrent_asset.<ext> file ever exists on disk after
        // an upload completes. We delete:
        //   1. Live asset files for OTHER extensions (only one can exist)
        //   2. Stale .tmp files for OTHER extensions (from previous crashed
        //      uploads — never cleaned up otherwise)
        // The same-extension live file is overwritten by tmpFile.renameTo()
        // below; our own .tmp is the source of the rename.
        for (String oldExt : new String[]{"png", "jpg", "jpeg", "webp", "gif"}) {
            if (oldExt.equals(ext)) continue;
            java.io.File oldFile = new java.io.File(dir, "screen_deterrent_asset." + oldExt);
            if (oldFile.exists()) {
                try { oldFile.delete(); } catch (Exception ignored) {}
            }
            java.io.File staleTmp = new java.io.File(dir, "screen_deterrent_asset." + oldExt + ".tmp");
            if (staleTmp.exists()) {
                try { staleTmp.delete(); } catch (Exception ignored) {}
            }
        }
        if (!tmpFile.renameTo(outFile)) {
            // Rename failed — try a copy fallback (e.g. cross-fs which can't
            // happen here but doesn't hurt to be defensive).
            try { tmpFile.delete(); } catch (Exception ignored) {}
            HttpResponse.sendJsonError(out, "Rename failed");
            return;
        }
        // World-readable so byd_cam_daemon (UID 2000) can read it.
        try { outFile.setReadable(true, false); } catch (Exception ignored) {}

        com.overdrive.app.config.UnifiedConfigManager.updateValues(
                "surveillance",
                java.util.Collections.singletonMap("screenDeterrentImagePath", outFile.getAbsolutePath()));

        CameraDaemon.log("Screen deterrent asset uploaded: " + outFile.getAbsolutePath()
                + " (" + data.length + " bytes)");

        JSONObject resp = new JSONObject();
        resp.put("success", true);
        resp.put("path", outFile.getAbsolutePath());
        resp.put("size", data.length);
        HttpResponse.sendJson(out, resp.toString());
    }
    
    private static void sendConfig(OutputStream out) throws Exception {
        GpuSurveillancePipeline gpuPipeline = CameraDaemon.getGpuPipeline();
        
        JSONObject response = new JSONObject();
        response.put("success", true);
        
        JSONObject config = new JSONObject();
        
        SurveillanceConfig sentryConfig = null;
        SurveillanceEngineGpu sentry = null;
        
        if (gpuPipeline != null && gpuPipeline.getSentry() != null) {
            sentry = gpuPipeline.getSentry();
            sentryConfig = sentry.getConfig();
        }
        
        if (sentryConfig == null) {
            try {
                SurveillanceConfigManager configManager = new SurveillanceConfigManager();
                if (configManager.configExists()) {
                    sentryConfig = configManager.loadConfig();
                }
            } catch (Exception e) {
                CameraDaemon.log("Failed to load config: " + e.getMessage());
            }
        }
        
        // Read persisted preference (not runtime state) for the UI toggle
        config.put("enabled", com.overdrive.app.config.UnifiedConfigManager.isSurveillanceEnabled());
        
        if (sentryConfig != null) {
            config.put("sadThreshold", sentry != null ? sentry.getSadThreshold() : 0.05f);
            config.put("preRecordSeconds", sentryConfig.getPreRecordSeconds());
            config.put("postRecordSeconds", sentryConfig.getPostRecordSeconds());
            config.put("totalBlocks", sentry != null ? sentry.getTotalBlocks() : 300);
            config.put("flashImmunity", sentryConfig.getFlashImmunity());
            config.put("aiEnabled", true);
            config.put("aiConfidence", sentryConfig.getAiConfidence());
            config.put("minObjectSize", sentryConfig.getMinObjectSize());
            config.put("detectPerson", sentryConfig.isDetectPerson());
            config.put("detectCar", sentryConfig.isDetectCar());
            config.put("detectBike", sentryConfig.isDetectBike());
            
            // SOTA: Distance preset and block settings
            config.put("distancePreset", sentryConfig.getDistancePreset().name());
            config.put("blockSize", sentryConfig.getBlockSize());
            config.put("maxDistanceM", sentryConfig.getMaxDistanceM());
            config.put("nightMode", sentryConfig.isNightMode());
            config.put("shadowThreshold", sentryConfig.getShadowThreshold());
            config.put("densityThreshold", sentryConfig.getDensityThreshold());
            config.put("alarmBlockThreshold", sentryConfig.getAlarmBlockThreshold());
            
            // SOTA: Return sensitivity as slider value (1-5) based on requiredBlocks
            int reqBlocks = sentryConfig.getRequiredBlocks();
            int sensitivityLevel;
            if (reqBlocks >= 4) {
                sensitivityLevel = 1;  // Strict
            } else if (reqBlocks == 3) {
                sensitivityLevel = 2;  // Conservative
            } else if (reqBlocks == 2) {
                sensitivityLevel = 3;  // Default
            } else {
                sensitivityLevel = 5;  // Aggressive
            }
            config.put("sensitivity", sensitivityLevel);
            
            // SOTA: Return distance as slider value (1-5) based on minObjectSize
            float minSize = sentryConfig.getMinObjectSize();
            int distanceLevel;
            if (minSize >= 0.22f) {
                distanceLevel = 1;  // ~3m (near)
            } else if (minSize >= 0.15f) {
                distanceLevel = 2;  // ~5m
            } else if (minSize >= 0.10f) {
                distanceLevel = 3;  // ~8m
            } else if (minSize >= 0.06f) {
                distanceLevel = 4;  // ~10m
            } else {
                distanceLevel = 5;  // ~15m (far)
            }
            config.put("distance", distanceLevel);
        } else {
            config.put("sadThreshold", 0.05f);
            config.put("sensitivity", 3);  // Default slider value
            config.put("distance", 3);     // Default slider value
            config.put("totalBlocks", 300);
            config.put("flashImmunity", 2);
            config.put("aiEnabled", true);
            config.put("aiConfidence", 0.4f);
            config.put("minObjectSize", 0.12f);
            config.put("detectPerson", true);
            config.put("detectCar", true);
            config.put("detectBike", true);
            config.put("preRecordSeconds", 5);
            config.put("postRecordSeconds", 10);
        }
        
        // Load recording settings from unified config. The new tier
        // (recordingQuality: ECONOMY/STANDARD/HIGH/PREMIUM/MAX) replaces
        // the legacy recordingBitrate string. Surveillance UI consumes
        // recordingQuality; recordingBitrate is no longer surfaced.
        try {
            JSONObject recording = com.overdrive.app.config.UnifiedConfigManager.getRecording();
            config.put("recordingQuality", recording.optString("recordingQuality",
                recording.optString("quality", "STANDARD")));
            config.put("recordingCodec", recording.optString("codec", "H264"));
        } catch (Exception e) {
            config.put("recordingQuality", "STANDARD");
            config.put("recordingCodec", "H264");
        }
        
        try {
            java.io.File unifiedFile = new java.io.File(UNIFIED_CONFIG_FILE);
            config.put("lastModified", unifiedFile.exists() ? unifiedFile.lastModified() : System.currentTimeMillis());
        } catch (Exception e) {
            config.put("lastModified", System.currentTimeMillis());
        }
        
        // SOTA: Safe Location status
        com.overdrive.app.surveillance.SafeLocationManager safeMgr =
            com.overdrive.app.surveillance.SafeLocationManager.getInstance();
        config.put("safeZoneSuppressed", CameraDaemon.isSafeZoneSuppressed());
        config.put("inSafeZone", safeMgr.isInSafeZone());
        config.put("safeZoneName", safeMgr.getCurrentZoneName());
        
        // SOTA: Deterrent action setting. forceReload because the daemon
        // process (byd_cam_daemon) writes screenDeterrentImagePath via the
        // upload endpoint; without forceReload the in-memory UCM cache here
        // can be stale until the next file mtime tick is observed.
        JSONObject survConfig = com.overdrive.app.config.UnifiedConfigManager
                .forceReload().optJSONObject("surveillance");
        if (survConfig == null) survConfig = new JSONObject();
        config.put("deterrentAction", survConfig.optString("deterrentAction", "silent"));
        config.put("deterrentCooldownSeconds", survConfig.optInt("deterrentCooldownSeconds", 60));
        config.put("screenDeterrentEnabled", survConfig.optBoolean("screenDeterrentEnabled", false));
        config.put("screenDeterrentDurationSeconds", survConfig.optInt("screenDeterrentDurationSeconds", 8));
        config.put("screenDeterrentMessage", survConfig.optString("screenDeterrentMessage", ""));
        // Verify the file actually exists before claiming hasImage=true.
        // Without this check, a stale UCM pointer (file deleted out-of-band)
        // makes the UI show a broken preview spinner forever.
        String imgPath = survConfig.optString("screenDeterrentImagePath", "");
        boolean hasImage = false;
        if (!imgPath.isEmpty()) {
            try {
                java.io.File f = new java.io.File(imgPath);
                hasImage = f.exists() && f.length() > 0;
            } catch (Exception ignored) {}
        }
        config.put("screenDeterrentImagePath", imgPath);
        config.put("screenDeterrentHasImage", hasImage);
        
        // SOTA: BYD Cloud connection status
        JSONObject bydCloud = com.overdrive.app.config.UnifiedConfigManager.getBydCloud();
        config.put("bydCloudEnabled", bydCloud.optBoolean("enabled", false));
        config.put("bydCloudUsername", bydCloud.optString("username", ""));
        config.put("bydCloudVin", bydCloud.optString("vin", ""));
        
        // V2 Pipeline settings
        if (sentryConfig != null) {
            config.put("environmentPreset", sentryConfig.getEnvironmentPreset());
            config.put("sensitivityLevel", sentryConfig.getSensitivityLevel());
            config.put("detectionZone", sentryConfig.getDetectionZone());
            config.put("loiteringTime", sentryConfig.getLoiteringTimeSeconds());
            boolean[] cameras = sentryConfig.getCameraEnabled();
            config.put("cameraFront", cameras[0]);
            config.put("cameraRight", cameras[1]);
            config.put("cameraRear", cameras[2]);
            config.put("cameraLeft", cameras[3]);
            config.put("motionHeatmap", sentryConfig.isMotionHeatmapEnabled());
            config.put("filterDebugLog", sentryConfig.isFilterDebugLogEnabled());
            config.put("telegramSendStartPing", sentryConfig.isTelegramSendStartPing());
            // Per-tier filter now lives in the telegram unified-config section
            // (see UnifiedTelegramConfig.K_TIER_*). Wire format on
            // /api/surveillance/config keeps the legacy key names so the web
            // UI doesn't need to know the storage moved.
            config.put("telegramNotices",
                    com.overdrive.app.telegram.config.UnifiedTelegramConfig.isTierNotices());
            config.put("telegramAlerts",
                    com.overdrive.app.telegram.config.UnifiedTelegramConfig.isTierAlerts());
            config.put("telegramCritical",
                    com.overdrive.app.telegram.config.UnifiedTelegramConfig.isTierCritical());
            config.put("shadowFilter", sentryConfig.getShadowFilterMode());

            // Per-quadrant overrides (sensitivity / detection zone). Each
            // entry is omitted when no override is set (= inherit global).
            org.json.JSONObject overrides = new org.json.JSONObject();
            String[] qKeysOv = {"Q0", "Q1", "Q2", "Q3"};
            for (int q = 0; q < 4; q++) {
                Integer sens = sentryConfig.getQuadrantSensitivityOverride(q);
                String zone = sentryConfig.getQuadrantDetectionZoneOverride(q);
                if (sens != null || zone != null) {
                    org.json.JSONObject perQ = new org.json.JSONObject();
                    if (sens != null) perQ.put("sensitivityLevel", sens.intValue());
                    if (zone != null) perQ.put("detectionZone", zone);
                    overrides.put(qKeysOv[q], perQ);
                }
            }
            config.put("quadrantOverrides", overrides);

            // ROI polygons and enabled flags
            org.json.JSONObject roiObj = new org.json.JSONObject();
            String[] qKeys = {"Q0", "Q1", "Q2", "Q3"};
            for (int q = 0; q < 4; q++) {
                // Always include polygon if it exists (even when disabled)
                float[][] poly = sentryConfig.getRoiPolygon(q);
                if (poly != null && poly.length >= 3) {
                    org.json.JSONArray polyArr = new org.json.JSONArray();
                    for (float[] vertex : poly) {
                        org.json.JSONObject pt = new org.json.JSONObject();
                        pt.put("x", vertex[0]);
                        pt.put("y", vertex[1]);
                        polyArr.put(pt);
                    }
                    roiObj.put(qKeys[q], polyArr);
                }
                // Per-quadrant block mask and enabled flag from unified config (source of truth)
                try {
                    org.json.JSONObject survCfg = com.overdrive.app.config.UnifiedConfigManager.getSurveillance();
                    org.json.JSONArray blocks = survCfg.optJSONArray("roiBlocks_" + qKeys[q]);
                    if (blocks != null) config.put("roiBlocks_" + qKeys[q], blocks);
                    // Read enabled flag from persisted config, not in-memory sentryConfig
                    if (survCfg.has("roiEnabled_" + qKeys[q])) {
                        config.put("roiEnabled_" + qKeys[q], survCfg.optBoolean("roiEnabled_" + qKeys[q], false));
                    } else {
                        config.put("roiEnabled_" + qKeys[q], sentryConfig.isRoiEnabled(q));
                    }
                } catch (Exception ignored) {
                    config.put("roiEnabled_" + qKeys[q], sentryConfig.isRoiEnabled(q));
                }
            }
            config.put("roiPolygons", roiObj);
            
            // Schedule — read from persisted config file (source of truth)
            try {
                org.json.JSONObject survCfg = com.overdrive.app.config.UnifiedConfigManager.getSurveillance();
                config.put("scheduleEnabled", survCfg.optBoolean("scheduleEnabled", false));
                org.json.JSONArray persistedRules = survCfg.optJSONArray("scheduleRules");
                if (persistedRules != null) {
                    config.put("scheduleRules", persistedRules);
                } else {
                    config.put("scheduleRules", new org.json.JSONArray());
                }
            } catch (Exception e) {
                // Fallback to in-memory if file read fails
                config.put("scheduleEnabled", sentryConfig.getSchedule().isEnabled());
                org.json.JSONArray schedRules = new org.json.JSONArray();
                for (com.overdrive.app.surveillance.SurveillanceSchedule.Rule rule : sentryConfig.getSchedule().getRules()) {
                    schedRules.put(rule.toJson());
                }
                config.put("scheduleRules", schedRules);
            }
            
            // Camera ID info
            try {
                org.json.JSONObject camCfg = com.overdrive.app.config.UnifiedConfigManager
                    .loadConfig().optJSONObject("camera");
                if (camCfg != null) {
                    config.put("cameraId", camCfg.optInt("probedCameraId", -1));
                    config.put("cameraManualOverride", camCfg.optBoolean("manualOverride", false));
                    config.put("cameraLayout", camCfg.optInt("cameraLayout", -1));
                    config.put("cameraArbitrationMode", camCfg.optString("arbitrationMode", "eventCallbackOnly"));
                    config.put("cameraReprobeOnNextRestart", camCfg.optBoolean("reprobeOnNextRestart", false));
                    config.put("cameraSourceBmmTag", camCfg.optString("sourceBmmTag", ""));
                    config.put("cameraDiscoveryMethod", camCfg.optString("discoveryMethod", ""));
                    config.put("cameraVehicleCamSort", camCfg.optString("vehicleCamSort", ""));
                    config.put("cameraFirmwareFingerprint", camCfg.optString("firmwareFingerprint", ""));
                    config.put("cameraValidationSignal", camCfg.optString("validationSignal", ""));
                    config.put("cameraLayoutConfidence", camCfg.optString("layoutConfidence", ""));
                }
            } catch (Exception ignored) {}
        } else {
            config.put("environmentPreset", "outdoor");
            config.put("sensitivityLevel", 3);
            config.put("detectionZone", "normal");
            config.put("loiteringTime", 3);
            config.put("cameraFront", true);
            config.put("cameraRight", true);
            config.put("cameraLeft", true);
            config.put("cameraRear", true);
            config.put("motionHeatmap", false);
            config.put("filterDebugLog", false);
            config.put("telegramSendStartPing", false);
            // Tier toggles live on the telegram unified-config section, so
            // they're available even when SurveillanceConfig isn't loaded.
            config.put("telegramNotices",
                    com.overdrive.app.telegram.config.UnifiedTelegramConfig.isTierNotices());
            config.put("telegramAlerts",
                    com.overdrive.app.telegram.config.UnifiedTelegramConfig.isTierAlerts());
            config.put("telegramCritical",
                    com.overdrive.app.telegram.config.UnifiedTelegramConfig.isTierCritical());
            config.put("shadowFilter", 2);
        }

        // Merge resolved camera profile summary so the diagnostics camera-
        // mapping dialog can populate role list, current mappings, and
        // preview candidates in a single round-trip. Failures are non-fatal —
        // the rest of the config response still ships.
        try {
            com.overdrive.app.camera.ResolvedCameraConfig resolvedCamera =
                com.overdrive.app.camera.CameraConfigResolver.resolve();
            JSONObject resolvedJson = com.overdrive.app.camera.CameraConfigResolver
                .resolvedSummaryJson(resolvedCamera);
            java.util.Iterator<String> keys = resolvedJson.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                config.put(key, resolvedJson.get(key));
            }
        } catch (Exception e) {
            CameraDaemon.log("Failed to resolve camera profile summary: " + e.getMessage());
        }

        response.put("config", config);
        HttpResponse.sendJson(out, response.toString());
    }
    
    private static void sendStatus(OutputStream out) throws Exception {
        JSONObject response = new JSONObject();
        response.put("success", true);
        
        java.util.Map<String, Object> statusMap = CameraDaemon.getSurveillanceStatus();
        JSONObject statusJson = new JSONObject(statusMap);
        response.put("status", statusJson);
        HttpResponse.sendJson(out, response.toString());
    }
    
    private static void handleConfigPost(OutputStream out, String body) throws Exception {
        GpuSurveillancePipeline gpuPipeline = CameraDaemon.getGpuPipeline();

        try {
            JSONObject configJson = new JSONObject(body);

            // ---- Camera profile selection (vehicle class) ----
            if (configJson.has("cameraProfile")
                    || configJson.optBoolean("clearCameraProfile", false)) {
                String requestedProfile = configJson.optBoolean("clearCameraProfile", false)
                    ? com.overdrive.app.camera.CameraProfiles.PROFILE_AUTO
                    : configJson.optString("cameraProfile",
                        com.overdrive.app.camera.CameraProfiles.PROFILE_AUTO);
                if (com.overdrive.app.camera.CameraConfigResolver
                        .saveCameraProfile(requestedProfile)) {
                    CameraDaemon.log("Camera profile saved: " + requestedProfile);
                }
            }

            // ---- Camera role → source mapping (diagnostics camera-mapping dialog) ----
            // Single-role write: { cameraRoleMapping: { role: "panoFront",
            //   source: { kind: "panoramicSlice", slice: "slice4" } } }
            //   - or { kind: "direct", cameraId: 2 }
            //   - or { kind: "panoramicVirtual", view: "front" }
            // Or clear: { cameraRoleMapping: { role: "panoFront", clear: true } }
            if (configJson.has("cameraRoleMapping")) {
                JSONObject mappingJson = configJson.optJSONObject("cameraRoleMapping");
                if (mappingJson == null) {
                    HttpResponse.sendJsonError(out, "Invalid cameraRoleMapping payload");
                    return;
                }
                com.overdrive.app.camera.CameraRole role =
                    com.overdrive.app.camera.CameraRole.fromKey(
                        mappingJson.optString("role", null));
                if (role == null) {
                    HttpResponse.sendJsonError(out,
                        "Unknown role: " + mappingJson.optString("role", "(missing)"));
                    return;
                }
                if (mappingJson.optBoolean("clear", false)) {
                    if (!com.overdrive.app.camera.CameraConfigResolver.clearRoleMapping(role)) {
                        HttpResponse.sendJsonError(out, "Failed to clear role mapping");
                        return;
                    }
                } else {
                    com.overdrive.app.camera.CameraSourceRef source =
                        com.overdrive.app.camera.CameraSourceRef.fromJson(
                            mappingJson.optJSONObject("source"));
                    if (source == null) {
                        HttpResponse.sendJsonError(out,
                            "Invalid or missing source for role " + role.getKey());
                        return;
                    }
                    if (!com.overdrive.app.camera.CameraConfigResolver
                            .saveRoleMapping(role, source)) {
                        HttpResponse.sendJsonError(out,
                            "Failed to save role mapping for " + role.getKey());
                        return;
                    }
                }
            }

            // ---- Bulk reset: revert all role mappings to profile defaults ----
            if (configJson.optBoolean("clearCameraRoleMappings", false)) {
                for (com.overdrive.app.camera.CameraRole role
                        : com.overdrive.app.camera.CameraRole.values()) {
                    com.overdrive.app.camera.CameraConfigResolver.clearRoleMapping(role);
                }
            }

            SurveillanceEngineGpu sentry = null;
            if (gpuPipeline != null) {
                sentry = gpuPipeline.getSentry();
            }
            
            SurveillanceConfig sentryConfig = null;
            if (sentry != null) {
                sentryConfig = sentry.getConfig();
            }
            if (sentryConfig == null) {
                try {
                    SurveillanceConfigManager configManager = new SurveillanceConfigManager();
                    sentryConfig = configManager.configExists() ? configManager.loadConfig() : new SurveillanceConfig();
                } catch (Exception e) {
                    sentryConfig = new SurveillanceConfig();
                }
            }
            
            boolean configChanged = false;

            if (configJson.has("reprobeCameraOnNextRestart")
                    && configJson.optBoolean("reprobeCameraOnNextRestart", false)) {
                try {
                    // Reprobe clears the trust chain but preserves manualOverride
                    // so a user can intentionally keep a hand-picked tuple.
                    org.json.JSONObject camCfg = new org.json.JSONObject();
                    camCfg.put("reprobeOnNextRestart", true);
                    camCfg.put("probedCameraId", -1);
                    camCfg.put("probedSurfaceMode", -1);
                    camCfg.put("cameraLayout", -1);
                    camCfg.put("probedAndValidated", false);
                    camCfg.put("fallbackFromProbe", false);
                    camCfg.put("sourceBmmTag", "");
                    camCfg.put("discoveryMethod", "");
                    camCfg.put("vehicleCamSort", "");
                    camCfg.put("firmwareFingerprint", "");
                    camCfg.put("buildDisplay", "");
                    camCfg.put("buildIncremental", "");
                    camCfg.put("roBuildIncremental", "");
                    camCfg.put("productDevice", "");
                    camCfg.put("validatedAtMs", 0);
                    camCfg.put("validatedFrameWidth", 0);
                    camCfg.put("validatedFrameHeight", 0);
                    camCfg.put("validationFrameCount", 0);
                    camCfg.put("validationSignal", "");
                    camCfg.put("stripConfidence", "");
                    camCfg.put("layoutConfidence", "");
                    camCfg.put("lastValidationFailure", "reprobe_requested");
                    camCfg.put("nativeProbeReport", "");
                    camCfg.put("nativeProbeReady", false);
                    camCfg.put("fpsSetCameraResult", "");
                    camCfg.put("fpsSetMediaCodecResult", "");
                    camCfg.put("lastCameraEvent", "");
                    com.overdrive.app.config.UnifiedConfigManager.updateSection("camera", camCfg);
                    CameraDaemon.log("Camera will reprobe on next restart (manual override preserved)");
                } catch (Exception e) {
                    CameraDaemon.log("Failed to mark camera for reprobe: " + e.getMessage());
                }
                configChanged = true;
            }
            
            if (sentry != null && configJson.has("sadThreshold")) {
                sentry.setSadThreshold((float) configJson.optDouble("sadThreshold", 0.05));
            }
            
            if (configJson.has("preRecordSeconds")) {
                int val = configJson.optInt("preRecordSeconds", 5);
                sentryConfig.setPreRecordSeconds(val);
                if (sentry != null) sentry.setPreRecordSeconds(val);
                configChanged = true;
            }
            
            if (configJson.has("postRecordSeconds")) {
                int val = configJson.optInt("postRecordSeconds", 10);
                sentryConfig.setPostRecordSeconds(val);
                if (sentry != null) sentry.setPostRecordSeconds(val);
                configChanged = true;
            }
            
            if (configJson.has("sensitivity")) {
                // SOTA: Handle sensitivity slider (1-5) - controls motion detection thresholds
                Object sensVal = configJson.opt("sensitivity");
                if (sensVal instanceof Number) {
                    int sensitivityLevel = ((Number) sensVal).intValue();
                    if (sensitivityLevel >= 1 && sensitivityLevel <= 5) {
                        // Map slider value to motion detection thresholds
                        // 1=Strict (req=4), 2=Conservative (req=3), 3=Default (req=2), 4=Sensitive (req=2), 5=Aggressive (req=1)
                        int requiredBlocks;
                        switch (sensitivityLevel) {
                            case 1: requiredBlocks = 4; break;
                            case 2: requiredBlocks = 3; break;
                            case 3: requiredBlocks = 2; break;
                            case 4: requiredBlocks = 2; break;
                            case 5: requiredBlocks = 1; break;
                            default: requiredBlocks = 2; break;
                        }
                        
                        int sensitivityPercent = sensitivityLevel * 20;
                        sentryConfig.setUnifiedSensitivity(sensitivityPercent);
                        sentryConfig.setRequiredBlocks(requiredBlocks);
                        
                        if (sentry != null) {
                            sentry.setUnifiedSensitivity(sensitivityPercent);
                            sentry.setRequiredActiveBlocks(requiredBlocks);
                        }
                        
                        configChanged = true;
                        CameraDaemon.log(String.format("Motion sensitivity set to level %d (%d%%, alarm=%d blocks)",
                            sensitivityLevel, sensitivityPercent, requiredBlocks));
                    }
                }
                // Legacy string sensitivity ("LOW"/"MEDIUM"/"HIGH") is no longer supported
            }
            
            // AI detection settings
            if (configJson.has("aiConfidence")) {
                float aiConf = (float) configJson.optDouble("aiConfidence", 0.4);
                sentryConfig.setAiConfidence(aiConf);
                configChanged = true;
            }
            if (configJson.has("minObjectSize")) {
                float minObjSize = (float) configJson.optDouble("minObjectSize", 0.12);
                sentryConfig.setMinObjectSize(minObjSize);
                configChanged = true;
            }
            if (configJson.has("detectPerson")) {
                sentryConfig.setDetectPerson(configJson.optBoolean("detectPerson", true));
                configChanged = true;
            }
            if (configJson.has("detectCar")) {
                sentryConfig.setDetectCar(configJson.optBoolean("detectCar", true));
                configChanged = true;
            }
            if (configJson.has("detectBike")) {
                sentryConfig.setDetectBike(configJson.optBoolean("detectBike", true));
                configChanged = true;
            }
            
            // Apply object filters to running engine
            if (sentry != null && configChanged) {
                sentry.setObjectFilters(
                    sentryConfig.getMinObjectSize(),
                    sentryConfig.getAiConfidence(),
                    sentryConfig.isDetectPerson(),
                    sentryConfig.isDetectCar(),
                    sentryConfig.isDetectBike()
                );
            }
            
            // Flash immunity setting
            if (configJson.has("flashImmunity")) {
                int val = configJson.optInt("flashImmunity", 2);
                sentryConfig.setFlashImmunity(val);
                if (sentry != null) sentry.setFlashImmunity(val);
                configChanged = true;
            }
            
            // SOTA: Deterrent action setting (silent / flash_lights / find_car)
            if (configJson.has("deterrentAction")) {
                String action = configJson.optString("deterrentAction", "silent");
                if ("silent".equals(action) || "flash_lights".equals(action) || "find_car".equals(action)) {
                    com.overdrive.app.config.UnifiedConfigManager.updateValues(
                            "surveillance", java.util.Collections.singletonMap("deterrentAction", action));
                    CameraDaemon.log("Deterrent action set to: " + action);
                    // Reset deterrent so it picks up new config
                    try {
                        com.overdrive.app.byd.cloud.BydCloudDeterrent.getInstance().reset();
                    } catch (Exception ignored) {}
                }
            }
            
            if (configJson.has("deterrentCooldownSeconds")) {
                int cooldown = configJson.optInt("deterrentCooldownSeconds", 60);
                if (cooldown >= 10 && cooldown <= 600) {
                    com.overdrive.app.config.UnifiedConfigManager.updateValues(
                            "surveillance", java.util.Collections.singletonMap("deterrentCooldownSeconds", cooldown));
                }
            }

            // SOTA: Screen deterrent (independent of cloud deterrent — both can be on)
            if (configJson.has("screenDeterrentEnabled")) {
                boolean enabled = configJson.optBoolean("screenDeterrentEnabled", false);
                com.overdrive.app.config.UnifiedConfigManager.updateValues(
                        "surveillance", java.util.Collections.singletonMap("screenDeterrentEnabled", enabled));
                CameraDaemon.log("Screen deterrent enabled: " + enabled);
                try {
                    com.overdrive.app.surveillance.ScreenDeterrent.getInstance().reset();
                } catch (Exception ignored) {}
            }

            if (configJson.has("screenDeterrentDurationSeconds")) {
                int dur = configJson.optInt("screenDeterrentDurationSeconds", 8);
                if (dur >= 3 && dur <= 30) {
                    com.overdrive.app.config.UnifiedConfigManager.updateValues(
                            "surveillance", java.util.Collections.singletonMap("screenDeterrentDurationSeconds", dur));
                }
            }

            if (configJson.has("screenDeterrentMessage")) {
                String msg = configJson.optString("screenDeterrentMessage", "");
                if (msg.length() > 120) msg = msg.substring(0, 120);
                com.overdrive.app.config.UnifiedConfigManager.updateValues(
                        "surveillance", java.util.Collections.singletonMap("screenDeterrentMessage", msg));
            }

            if (configJson.has("clearScreenDeterrentImage") && configJson.optBoolean("clearScreenDeterrentImage", false)) {
                String existing = com.overdrive.app.config.UnifiedConfigManager.getSurveillance()
                        .optString("screenDeterrentImagePath", "");
                // Defense in depth: delete EVERY known-shape file in the
                // deterrent dir, not just the path UCM points at. This way,
                // if a previous upload's .tmp leaked or UCM was out of sync
                // with disk (e.g. the user upgraded mid-upload), Clear
                // genuinely leaves no asset behind. Path-restriction prevents
                // any escape from the .overdrive directory.
                java.io.File dir = new java.io.File(SCREEN_DETERRENT_DIR);
                if (dir.isDirectory()) {
                    for (String oldExt : new String[]{"png", "jpg", "jpeg", "webp", "gif"}) {
                        java.io.File f = new java.io.File(dir, SCREEN_DETERRENT_PREFIX + oldExt);
                        if (f.exists() && isAllowedDeterrentPath(f.getAbsolutePath())) {
                            try { f.delete(); } catch (Exception ignored) {}
                        }
                        java.io.File t = new java.io.File(dir, SCREEN_DETERRENT_PREFIX + oldExt + ".tmp");
                        if (t.exists() && isAllowedDeterrentPath(t.getAbsolutePath())) {
                            try { t.delete(); } catch (Exception ignored) {}
                        }
                    }
                }
                // Also delete the path UCM points at, in case it's a different
                // shape than the prefix loop above caught (legacy / migration).
                if (!existing.isEmpty() && isAllowedDeterrentPath(existing)) {
                    try { new java.io.File(existing).delete(); } catch (Exception ignored) {}
                }
                com.overdrive.app.config.UnifiedConfigManager.updateValues(
                        "surveillance", java.util.Collections.singletonMap("screenDeterrentImagePath", ""));
                CameraDaemon.log("Screen deterrent image cleared");
            }
            
            // SOTA: Handle distance slider (1-5) - ONLY controls minObjectSize (AI detection range)
            // Motion sensitivity (requiredBlocks, densityThreshold) is handled separately
            if (configJson.has("distance") || configJson.has("distancePreset")) {
                String distanceStr = configJson.has("distance") ? 
                    configJson.optString("distance", "3") : 
                    configJson.optString("distancePreset", "MEDIUM");
                
                CameraDaemon.log("Distance field received: " + distanceStr);
                
                // Map distance to minObjectSize for AI detection
                float minObjSize;
                String distanceLabel;
                
                try {
                    int distanceValue = Integer.parseInt(distanceStr);
                    
                    if (distanceValue <= 5) {
                        // Slider index mapping (1-5):
                        // 1 = Close (~3m, 25%), 2 = Near (~5m, 18%), 3 = Medium (~8m, 12%), 
                        // 4 = Far (~10m, 8%), 5 = Very Far (~15m, 5%)
                        switch (distanceValue) {
                            case 1: minObjSize = 0.25f; distanceLabel = "CLOSE (~3m)"; break;
                            case 2: minObjSize = 0.18f; distanceLabel = "NEAR (~5m)"; break;
                            case 3: minObjSize = 0.12f; distanceLabel = "MEDIUM (~8m)"; break;
                            case 4: minObjSize = 0.08f; distanceLabel = "FAR (~10m)"; break;
                            case 5: minObjSize = 0.05f; distanceLabel = "VERY_FAR (~15m)"; break;
                            default: minObjSize = 0.12f; distanceLabel = "MEDIUM (~8m)"; break;
                        }
                        CameraDaemon.log("Distance slider index " + distanceValue + " mapped to: " + distanceLabel);
                    } else {
                        // Treat as actual distance in meters (6m+)
                        if (distanceValue <= 5) {
                            minObjSize = 0.18f; distanceLabel = "NEAR (~5m)";
                        } else if (distanceValue <= 8) {
                            minObjSize = 0.12f; distanceLabel = "MEDIUM (~8m)";
                        } else if (distanceValue <= 12) {
                            minObjSize = 0.08f; distanceLabel = "FAR (~10m)";
                        } else {
                            minObjSize = 0.05f; distanceLabel = "VERY_FAR (~15m)";
                        }
                        CameraDaemon.log("Distance " + distanceValue + "m mapped to: " + distanceLabel);
                    }
                } catch (NumberFormatException e) {
                    // Handle preset names (CLOSE, MEDIUM, FAR, VERY_FAR)
                    String presetName = distanceStr.toUpperCase();
                    switch (presetName) {
                        case "CLOSE": minObjSize = 0.25f; distanceLabel = "CLOSE (~3m)"; break;
                        case "NEAR": minObjSize = 0.18f; distanceLabel = "NEAR (~5m)"; break;
                        case "FAR": minObjSize = 0.08f; distanceLabel = "FAR (~10m)"; break;
                        case "VERY_FAR": minObjSize = 0.05f; distanceLabel = "VERY_FAR (~15m)"; break;
                        case "MEDIUM":
                        default: minObjSize = 0.12f; distanceLabel = "MEDIUM (~8m)"; break;
                    }
                    CameraDaemon.log("Distance preset name: " + distanceLabel);
                }
                
                // Only update minObjectSize - don't touch motion sensitivity settings
                sentryConfig.setMinObjectSize(minObjSize);
                configChanged = true;
                
                // Apply to running engine if available
                if (sentry != null) {
                    float confidence = sentryConfig.getAiConfidence();
                    boolean dPerson = sentryConfig.isDetectPerson();
                    boolean dCar = sentryConfig.isDetectCar();
                    boolean dBike = sentryConfig.isDetectBike();
                    sentry.setObjectFilters(minObjSize, confidence, dPerson, dCar, dBike);
                }
                
                CameraDaemon.log(String.format("Distance set: %s (minObjectSize=%.0f%%)",
                    distanceLabel, minObjSize * 100));
            } else {
                CameraDaemon.log("No distance field in request - using existing config");
            }
            
            // SOTA: Handle night mode toggle
            if (configJson.has("nightMode")) {
                boolean val = configJson.optBoolean("nightMode", false);
                sentryConfig.setNightMode(val);
                if (sentry != null) sentry.setNightMode(val);
                configChanged = true;
            }
            
            // V2 Motion Detection settings
            // These are persisted to SurveillanceConfig; sentry.setConfig() below re-applies
            // them to the live pipeline via pipelineV2Config.applyConfig().
            if (configJson.has("environmentPreset")) {
                String preset = configJson.optString("environmentPreset", "outdoor");
                sentryConfig.setEnvironmentPreset(preset);
                if (sentry != null) sentry.applyV2EnvironmentPreset(preset);
                configChanged = true;
            }
            if (configJson.has("sensitivityLevel")) {
                int level = configJson.optInt("sensitivityLevel", 3);
                sentryConfig.setSensitivityLevel(level);
                if (sentry != null) sentry.applyV2Sensitivity(level);
                configChanged = true;
            }
            if (configJson.has("detectionZone")) {
                String zone = configJson.optString("detectionZone", "normal");
                sentryConfig.setDetectionZone(zone);
                configChanged = true;
            }
            if (configJson.has("loiteringTime")) {
                int seconds = configJson.optInt("loiteringTime", 3);
                sentryConfig.setLoiteringTimeSeconds(seconds);
                if (sentry != null) sentry.setV2LoiteringTime(seconds);
                configChanged = true;
            }
            if (configJson.has("shadowFilter")) {
                int mode = configJson.optInt("shadowFilter", 2);
                sentryConfig.setShadowFilterMode(mode);
                if (sentry != null) sentry.setV2ShadowFilterMode(mode);
                configChanged = true;
            }
            if (configJson.has("cameraFront") || configJson.has("cameraRight") ||
                configJson.has("cameraLeft")  || configJson.has("cameraRear")) {
                boolean[] existing = sentryConfig.getCameraEnabled();
                boolean front = configJson.optBoolean("cameraFront", existing[0]);
                boolean right = configJson.optBoolean("cameraRight", existing[1]);
                boolean rear  = configJson.optBoolean("cameraRear",  existing[2]);
                boolean left  = configJson.optBoolean("cameraLeft",  existing[3]);
                sentryConfig.setCameraEnabled(0, front);
                sentryConfig.setCameraEnabled(1, right);
                sentryConfig.setCameraEnabled(2, rear);
                sentryConfig.setCameraEnabled(3, left);
                if (sentry != null) {
                    sentry.setV2QuadrantEnabled(0, front);
                    sentry.setV2QuadrantEnabled(1, right);
                    sentry.setV2QuadrantEnabled(2, rear);
                    sentry.setV2QuadrantEnabled(3, left);
                }
                configChanged = true;
            }
            if (configJson.has("quadrantOverrides")) {
                org.json.JSONObject overrides = configJson.optJSONObject("quadrantOverrides");
                String[] qKeysOv = {"Q0", "Q1", "Q2", "Q3"};
                for (int q = 0; q < 4; q++) {
                    org.json.JSONObject perQ = overrides != null
                            ? overrides.optJSONObject(qKeysOv[q]) : null;
                    if (perQ == null) {
                        sentryConfig.setQuadrantSensitivityOverride(q, null);
                        sentryConfig.setQuadrantDetectionZoneOverride(q, null);
                    } else {
                        sentryConfig.setQuadrantSensitivityOverride(q,
                                perQ.has("sensitivityLevel") ? perQ.optInt("sensitivityLevel", 3) : null);
                        sentryConfig.setQuadrantDetectionZoneOverride(q,
                                perQ.has("detectionZone") ? perQ.optString("detectionZone", null) : null);
                    }
                }
                configChanged = true;
            }
            if (configJson.has("motionHeatmap")) {
                sentryConfig.setMotionHeatmapEnabled(configJson.optBoolean("motionHeatmap", false));
                configChanged = true;
            }
            if (configJson.has("filterDebugLog")) {
                boolean val = configJson.optBoolean("filterDebugLog", false);
                sentryConfig.setFilterDebugLogEnabled(val);
                if (sentry != null) sentry.setFilterDebugEnabled(val);
                configChanged = true;
            }
            if (configJson.has("telegramSendStartPing")) {
                sentryConfig.setTelegramSendStartPing(
                        configJson.optBoolean("telegramSendStartPing", false));
                configChanged = true;
            }
            // Per-tier Telegram filter — persisted in the telegram section
            // of unified config so NotificationGate.shouldTelegram() picks
            // the new value up immediately via forceReload(), instead of
            // waiting for the next camera-daemon restart.
            if (configJson.has("telegramNotices")) {
                com.overdrive.app.telegram.config.UnifiedTelegramConfig.setBoolean(
                        com.overdrive.app.telegram.config.UnifiedTelegramConfig.K_TIER_NOTICES,
                        configJson.optBoolean("telegramNotices", false));
            }
            if (configJson.has("telegramAlerts")) {
                com.overdrive.app.telegram.config.UnifiedTelegramConfig.setBoolean(
                        com.overdrive.app.telegram.config.UnifiedTelegramConfig.K_TIER_ALERTS,
                        configJson.optBoolean("telegramAlerts", true));
            }
            if (configJson.has("telegramCritical")) {
                com.overdrive.app.telegram.config.UnifiedTelegramConfig.setBoolean(
                        com.overdrive.app.telegram.config.UnifiedTelegramConfig.K_TIER_CRITICAL,
                        configJson.optBoolean("telegramCritical", true));
            }
            
            // Per-quadrant ROI polygons
            if (configJson.has("roiPolygons")) {
                try {
                    org.json.JSONObject roiObj = configJson.getJSONObject("roiPolygons");
                    String[] quadrantKeys = {"Q0", "Q1", "Q2", "Q3"};
                    for (int q = 0; q < 4; q++) {
                        if (roiObj.has(quadrantKeys[q])) {
                            org.json.JSONArray polyArr = roiObj.optJSONArray(quadrantKeys[q]);
                            if (polyArr != null && polyArr.length() >= 3) {
                                float[][] polygon = new float[polyArr.length()][2];
                                for (int v = 0; v < polyArr.length(); v++) {
                                    org.json.JSONObject pt = polyArr.getJSONObject(v);
                                    polygon[v][0] = (float) pt.getDouble("x");
                                    polygon[v][1] = (float) pt.getDouble("y");
                                }
                                sentryConfig.setRoiPolygon(q, polygon);
                                // Only apply to C++ if ROI is enabled for this quadrant
                                if (sentryConfig.isRoiEnabled(q) && sentry != null) {
                                    sentry.applyQuadrantRoi(q, polygon);
                                }
                            } else if (polyArr == null) {
                                // Explicit null = clear polygon data
                                sentryConfig.clearRoi(q);
                                if (sentry != null) sentry.clearQuadrantRoi(q);
                            }
                        }
                    }
                    configChanged = true;
                } catch (Exception e) {
                    CameraDaemon.log("ROI parse error: " + e.getMessage());
                }
            }
            
            // Per-quadrant ROI enabled/disabled toggle (separate from polygon data)
            {
                String[] quadrantKeys = {"Q0", "Q1", "Q2", "Q3"};
                for (int q = 0; q < 4; q++) {
                    String enabledKey = "roiEnabled_" + quadrantKeys[q];
                    if (configJson.has(enabledKey)) {
                        boolean enabled = configJson.optBoolean(enabledKey, false);
                        if (enabled && sentryConfig.getRoiPolygon(q) != null) {
                            // Enable ROI — apply the persisted polygon to C++
                            sentryConfig.setRoiEnabled(q, true);
                            if (sentry != null) sentry.applyQuadrantRoi(q, sentryConfig.getRoiPolygon(q));
                        } else {
                            // Disable ROI — clear C++ mask but keep polygon in config
                            sentryConfig.setRoiEnabled(q, false);
                            if (sentry != null) sentry.clearQuadrantRoi(q);
                        }
                        configChanged = true;
                    }
                }
            }
            
            // Direct block mask per quadrant (from block-tap UI)
            // Accepts roiBlocks_Q0: [1,1,0,0,...] (70 elements, 1=active 0=inactive)
            {
                String[] quadrantKeys = {"Q0", "Q1", "Q2", "Q3"};
                for (int q = 0; q < 4; q++) {
                    String blocksKey = "roiBlocks_" + quadrantKeys[q];
                    if (configJson.has(blocksKey)) {
                        org.json.JSONArray arr = configJson.optJSONArray(blocksKey);
                        if (arr != null && arr.length() == 70) {
                            byte[] blockMask = new byte[70];
                            boolean anyActive = false;
                            for (int i = 0; i < 70; i++) {
                                blockMask[i] = (byte)(arr.optInt(i, 1) != 0 ? 1 : 0);
                                if (blockMask[i] != 0) anyActive = true;
                            }
                            if (anyActive) {
                                sentryConfig.setRoiEnabled(q, true);
                                // Store block mask as a synthetic polygon (not used, blocks are direct)
                                // Apply directly to C++ via JNI
                                try {
                                    com.overdrive.app.surveillance.NativeMotion.setQuadrantRoi(q, blockMask);
                                    CameraDaemon.log("ROI blocks applied to Q" + q + " via direct mask");
                                } catch (Exception e) {
                                    CameraDaemon.log("ROI blocks apply failed Q" + q + ": " + e.getMessage());
                                }
                            } else {
                                sentryConfig.setRoiEnabled(q, false);
                                if (sentry != null) sentry.clearQuadrantRoi(q);
                            }
                            // Persist the block array in unified config
                            try {
                                org.json.JSONObject survCfg = com.overdrive.app.config.UnifiedConfigManager.getSurveillance();
                                survCfg.put(blocksKey, arr);
                                survCfg.put("roiEnabled_" + quadrantKeys[q], anyActive);
                                com.overdrive.app.config.UnifiedConfigManager.setSurveillance(survCfg);
                            } catch (Exception e) {
                                CameraDaemon.log("ROI blocks persist failed: " + e.getMessage());
                            }
                            configChanged = true;
                        }
                    }
                }
            }
            
            // Surveillance schedule
            if (configJson.has("scheduleEnabled") || configJson.has("scheduleRules")) {
                try {
                    com.overdrive.app.surveillance.SurveillanceSchedule schedule = sentryConfig.getSchedule();
                    if (configJson.has("scheduleEnabled")) {
                        schedule.setEnabled(configJson.optBoolean("scheduleEnabled", false));
                    }
                    if (configJson.has("scheduleRules")) {
                        schedule.getRules().clear();
                        org.json.JSONArray rulesArr = configJson.getJSONArray("scheduleRules");
                        for (int i = 0; i < rulesArr.length(); i++) {
                            com.overdrive.app.surveillance.SurveillanceSchedule.Rule rule =
                                com.overdrive.app.surveillance.SurveillanceSchedule.Rule.fromJson(rulesArr.getJSONObject(i));
                            if (rule != null) schedule.getRules().add(rule);
                        }
                    }
                    // Persist schedule to unified config
                    org.json.JSONObject survConfig = com.overdrive.app.config.UnifiedConfigManager.getSurveillance();
                    org.json.JSONObject scheduleJson = schedule.toJson();
                    survConfig.put("scheduleEnabled", scheduleJson.optBoolean("scheduleEnabled", false));
                    survConfig.put("scheduleRules", scheduleJson.optJSONArray("scheduleRules"));
                    com.overdrive.app.config.UnifiedConfigManager.setSurveillance(survConfig);
                    CameraDaemon.log("Schedule updated: " + schedule.getSummary());
                    configChanged = true;
                    
                    // IMMEDIATE ENFORCEMENT: If surveillance is currently active and the
                    // new schedule says we're outside the window, stop it now. Don't wait
                    // for the 5-minute periodic checker.
                    // Conversely, if surveillance is inactive and the schedule now allows it,
                    // start it (respecting safe zone and other gates).
                    if (schedule.isEnabled()) {
                        boolean withinWindow = schedule.isActiveNow();
                        boolean currentlyActive = sentry != null && sentry.isActive();
                        
                        if (!withinWindow && currentlyActive) {
                            CameraDaemon.log("SCHEDULE: Immediately stopping surveillance (outside new schedule window)");
                            CameraDaemon.disableSurveillance();
                        } else if (withinWindow && !currentlyActive 
                                && !com.overdrive.app.monitor.AccMonitor.isAccOn()
                                && !CameraDaemon.isSafeZoneSuppressed()) {
                            CameraDaemon.log("SCHEDULE: Immediately enabling surveillance (within new schedule window)");
                            CameraDaemon.enableSurveillance();
                        }
                    } else {
                        // Schedule just disabled — if surveillance was suppressed by schedule,
                        // resume it now (respecting safe zone and ACC state)
                        boolean currentlyActive = sentry != null && sentry.isActive();
                        if (!currentlyActive 
                                && !com.overdrive.app.monitor.AccMonitor.isAccOn()
                                && !CameraDaemon.isSafeZoneSuppressed()
                                && com.overdrive.app.config.UnifiedConfigManager.isSurveillanceEnabled()) {
                            CameraDaemon.log("SCHEDULE: Disabled — resuming surveillance immediately");
                            CameraDaemon.enableSurveillance();
                        }
                    }
                } catch (Exception e) {
                    CameraDaemon.log("Schedule parse error: " + e.getMessage());
                }
            }

            if (configJson.has("cameraArbitrationMode")) {
                String mode = configJson.optString("cameraArbitrationMode", "eventCallbackOnly");
                // Accept only the three supported arbitration modes. Anything
                // else is ignored so we do not accidentally flip the camera
                // service into an unreviewed experiment state.
                if (!"polling".equals(mode)
                        && !"eventCallbackOnly".equals(mode)
                        && !"registeredUserExperiment".equals(mode)) {
                    CameraDaemon.log("Ignoring invalid cameraArbitrationMode: " + mode);
                } else {
                    try {
                        // Update the live coordinator if it already exists, then
                        // persist the same value so the next restart reuses it.
                        GpuSurveillancePipeline cameraPipeline = CameraDaemon.getGpuPipeline();
                        if (cameraPipeline != null && cameraPipeline.getCamera() != null) {
                            cameraPipeline.getCamera().setArbitrationMode(mode);
                        }
                        org.json.JSONObject camCfg = new org.json.JSONObject();
                        camCfg.put("arbitrationMode", mode);
                        com.overdrive.app.config.UnifiedConfigManager.updateSection("camera", camCfg);
                        CameraDaemon.log("Camera arbitration mode set to: " + mode);
                    } catch (Exception e) {
                        CameraDaemon.log("Failed to save camera arbitration mode: " + e.getMessage());
                    }
                    configChanged = true;
                }
            }

            // Manual camera ID override
            if (configJson.has("manualCameraId")) {
                int camId = configJson.optInt("manualCameraId", -1);
                if (camId >= 0 && camId <= 5) {
                    try {
                        // Manual override stores a full tuple immediately so the
                        // next restart can reuse the selection without probing.
                        int layout = configJson.has("cameraLayout")
                            ? configJson.optInt("cameraLayout", 0) : 0;
                        if (!configJson.has("cameraLayout")) {
                            CameraDaemon.log("Manual camera ID set without cameraLayout; assuming layout 0");
                        }
                        com.overdrive.app.camera.CameraFirmwareInfo firmware =
                            com.overdrive.app.camera.CameraFirmwareInfo.current();
                        org.json.JSONObject camCfg = new org.json.JSONObject();
                        camCfg.put("probedCameraId", camId);
                        camCfg.put("probedSurfaceMode", 0);
                        camCfg.put("cameraLayout", layout);
                        camCfg.put("probedAndValidated", true);
                        camCfg.put("manualOverride", true);
                        camCfg.put("fallbackFromProbe", false);
                        camCfg.put("reprobeOnNextRestart", false);
                        camCfg.put("sourceBmmTag", "manual");
                        camCfg.put("discoveryMethod", "manual");
                        camCfg.put("vehicleCamSort", firmware.vehicleCamSort);
                        camCfg.put("firmwareFingerprint", firmware.fingerprint);
                        camCfg.put("buildDisplay", firmware.buildDisplay);
                        camCfg.put("buildIncremental", firmware.buildIncremental);
                        camCfg.put("roBuildIncremental", firmware.roBuildIncremental);
                        camCfg.put("productDevice", firmware.device);
                        camCfg.put("validatedAtMs", 0);
                        camCfg.put("validatedFrameWidth", 0);
                        camCfg.put("validatedFrameHeight", 0);
                        camCfg.put("validationFrameCount", 0);
                        camCfg.put("validationSignal", "");
                        camCfg.put("stripConfidence", "");
                        camCfg.put("layoutConfidence", "");
                        camCfg.put("lastValidationFailure", "");
                        camCfg.put("nativeProbeReport", "");
                        camCfg.put("nativeProbeReady", false);
                        camCfg.put("fpsSetCameraResult", "");
                        camCfg.put("fpsSetMediaCodecResult", "");
                        camCfg.put("lastCameraEvent", "");
                        com.overdrive.app.config.UnifiedConfigManager.updateSection("camera", camCfg);
                        CameraDaemon.log("Manual camera ID set: " + camId + " (will take effect on next restart)");
                    } catch (Exception e) {
                        CameraDaemon.log("Failed to save manual camera ID: " + e.getMessage());
                    }
                    configChanged = true;
                }
            }
            if (configJson.has("clearManualCameraId")
                    && configJson.optBoolean("clearManualCameraId", false)) {
                try {
                    // Clear the whole selection tuple, not just the camera ID,
                    // so the next boot path rediscover from scratch instead of
                    // reusing stale camera metadata.
                    org.json.JSONObject camCfg = new org.json.JSONObject();
                    camCfg.put("probedCameraId", -1);
                    camCfg.put("probedSurfaceMode", -1);
                    camCfg.put("cameraLayout", -1);
                    camCfg.put("probedAndValidated", false);
                    camCfg.put("manualOverride", false);
                    camCfg.put("fallbackFromProbe", false);
                    camCfg.put("reprobeOnNextRestart", false);
                    camCfg.put("sourceBmmTag", "");
                    camCfg.put("discoveryMethod", "");
                    camCfg.put("vehicleCamSort", "");
                    camCfg.put("firmwareFingerprint", "");
                    camCfg.put("buildDisplay", "");
                    camCfg.put("buildIncremental", "");
                    camCfg.put("roBuildIncremental", "");
                    camCfg.put("productDevice", "");
                    camCfg.put("validationSignal", "");
                    camCfg.put("stripConfidence", "");
                    camCfg.put("layoutConfidence", "");
                    camCfg.put("lastValidationFailure", "");
                    camCfg.put("validatedAtMs", 0);
                    camCfg.put("validatedFrameWidth", 0);
                    camCfg.put("validatedFrameHeight", 0);
                    camCfg.put("validationFrameCount", 0);
                    camCfg.put("nativeProbeReport", "");
                    camCfg.put("nativeProbeReady", false);
                    camCfg.put("fpsSetCameraResult", "");
                    camCfg.put("fpsSetMediaCodecResult", "");
                    camCfg.put("lastCameraEvent", "");
                    com.overdrive.app.config.UnifiedConfigManager.updateSection("camera", camCfg);
                    CameraDaemon.log("Manual camera ID cleared — will auto-detect on next restart");
                } catch (Exception e) {
                    CameraDaemon.log("Failed to clear manual camera ID: " + e.getMessage());
                }
                configChanged = true;
            }

            if (configChanged) {
                try {
                    // Apply config to the running surveillance engine
                    if (sentry != null) sentry.setConfig(sentryConfig);
                } catch (Exception e) {
                    CameraDaemon.log("Failed to apply config: " + e.getMessage());
                }

                // Persist to disk so settings survive ACC OFF/ON (pipeline.stop()
                // sets initialized=false, and the next start() reloads config from
                // disk via SurveillanceConfigManager.loadConfig() — without this
                // save, every detection/recording field reverts to the last
                // persisted value on the next ACC cycle).
                try {
                    new SurveillanceConfigManager().saveConfig(sentryConfig);
                } catch (Exception e) {
                    CameraDaemon.log("Failed to persist surveillance config: " + e.getMessage());
                }
            }
            
            // Save recording settings (quality tier, codec) to unified config.
            // Accepts both the new `recordingQuality` (ECONOMY..MAX) and the
            // legacy `recordingBitrate` (LOW/MEDIUM/HIGH) for forward compat.
            boolean recordingChanged = false;
            if (configJson.has("recordingQuality") || configJson.has("recordingBitrate") || configJson.has("recordingCodec")) {
                try {
                    JSONObject recording = com.overdrive.app.config.UnifiedConfigManager.getRecording();
                    String appliedTier = null;
                    if (configJson.has("recordingQuality")) {
                        appliedTier = configJson.optString("recordingQuality", "STANDARD");
                    } else if (configJson.has("recordingBitrate")) {
                        // Legacy path: translate LOW/MEDIUM/HIGH → tier name
                        // and persist under the canonical key.
                        String bitrate = configJson.optString("recordingBitrate", "MEDIUM");
                        switch (bitrate.toUpperCase()) {
                            case "LOW":    appliedTier = "ECONOMY"; break;
                            case "MEDIUM": appliedTier = "STANDARD"; break;
                            case "HIGH":   appliedTier = "HIGH"; break;
                            default:       appliedTier = "STANDARD"; break;
                        }
                    }
                    if (appliedTier != null) {
                        recording.put("recordingQuality", appliedTier);
                        recording.put("quality", appliedTier);  // mirror for legacy readers
                        recording.remove("bitrate");  // drop stale LOW/MEDIUM/HIGH so cross-channel readers don't see drift
                        recordingChanged = true;
                        try {
                            CameraDaemon.setRecordingQuality(appliedTier);
                        } catch (Exception e) {
                            CameraDaemon.log("Failed to apply recordingQuality to pipeline: " + e.getMessage());
                        }
                    }
                    if (configJson.has("recordingCodec")) {
                        String codec = configJson.optString("recordingCodec", "H264");
                        recording.put("codec", codec);
                        recordingChanged = true;
                        // Apply to running pipeline (will take effect on next recording)
                        try {
                            CameraDaemon.setRecordingCodec(codec);
                        } catch (Exception e) {
                            CameraDaemon.log("Failed to apply codec to pipeline: " + e.getMessage());
                        }
                    }
                    if (recordingChanged) {
                        com.overdrive.app.config.UnifiedConfigManager.setRecording(recording);
                        CameraDaemon.log("Recording settings saved: recordingQuality="
                                + recording.optString("recordingQuality")
                                + ", codec=" + recording.optString("codec"));
                    }
                } catch (Exception e) {
                    CameraDaemon.log("Failed to save recording settings: " + e.getMessage());
                }
            }
            
            HttpResponse.sendJsonSuccess(out);
            
        } catch (Exception e) {
            CameraDaemon.log("Error applying surveillance config: " + e.getMessage());
            HttpResponse.sendJsonError(out, e.getMessage());
        }
    }
    
    private static void handleEnable(OutputStream out) throws Exception {
        // SOTA: Only persist the preference. Surveillance should only activate on ACC OFF.
        // Starting motion detection while driving wastes CPU/GPU and is meaningless.
        com.overdrive.app.config.UnifiedConfigManager.setSurveillanceEnabled(true);
        
        // Only actually start surveillance if ACC is currently OFF (sentry mode)
        boolean accIsOn = com.overdrive.app.monitor.AccMonitor.isAccOn();
        if (!accIsOn) {
            CameraDaemon.enableSurveillance();
        } else {
            CameraDaemon.log("Surveillance preference saved — will activate on next ACC OFF");
        }
        HttpResponse.sendJsonSuccess(out);
    }
    
    private static void handleDisable(OutputStream out) throws Exception {
        CameraDaemon.disableSurveillance();
        com.overdrive.app.config.UnifiedConfigManager.setSurveillanceEnabled(false);
        HttpResponse.sendJsonSuccess(out);
    }

    /**
     * Graceful pre-restart flush. Called by the camera-mapping dialog
     * BEFORE killing the daemon, so the running pipeline finalizes any
     * in-flight recording (writes the MP4 moov atom, flushes the H264
     * circular buffer, closes encoder + EGL surfaces) instead of being
     * SIGKILL'd mid-write.
     *
     * <p>Synchronous: blocks until pipeline.stop() returns. Bounded by the
     * pipeline's own teardown timeline (typically 1-2 s) — the dialog's
     * 4 s read timeout covers it.
     */
    private static void handlePrepareRestart(OutputStream out) throws Exception {
        // Mark shutdown so future cold-start requests fall through to
        // "Preview unavailable" instead of looping the dialog on 503.
        // Critical: must be set BEFORE we wait on coldStartInProgress —
        // otherwise a new sendCameraPreview can start another cold-start
        // immediately after the existing one releases the flag, and we'd
        // race in a circle.
        shutdownInProgress = true;
        // CAS: take ownership of the cold-start flag. If a panoramic-slice
        // preview kicked off a cold-start, wait briefly for it to finish
        // before stop() — running stop() concurrently with start() leaks
        // encoder/EGL.
        //
        // If cold-start is still in flight after 3 s, we ABANDON this
        // prepare-restart instead of force-taking the flag (which would
        // race the still-running start and corrupt the encoder anyway).
        // The dialog proceeds with kill+relaunch; the fresh JVM recovers
        // cleanly. Worst case: one corrupt MP4 from the kill, no worse
        // than racing a half-initialized pipeline.
        long deadline = System.currentTimeMillis() + 3000;
        boolean tookFlag = false;
        while (true) {
            if (coldStartInProgress.compareAndSet(false, true)) {
                tookFlag = true;
                break;
            }
            if (System.currentTimeMillis() > deadline) {
                CameraDaemon.log("prepare-restart: cold-start still in flight after 3s — "
                        + "abandoning graceful stop, dialog will SIGKILL anyway");
                break;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (!tookFlag) {
            HttpResponse.sendJsonSuccess(out);
            return;
        }
        try {
            GpuSurveillancePipeline pipeline = CameraDaemon.getGpuPipeline();
            if (pipeline != null && pipeline.isRunning()) {
                CameraDaemon.log("prepare-restart: stopping pipeline gracefully");
                pipeline.stop();
            }
        } catch (Exception e) {
            CameraDaemon.log("prepare-restart: pipeline.stop failed: " + e.getMessage());
        } finally {
            coldStartInProgress.set(false);
        }
        HttpResponse.sendJsonSuccess(out);
    }
    
    /**
     * Returns per-quadrant block confidence data for the motion heatmap overlay.
     * 
     * Response format:
     * {
     *   "quadrants": [
     *     { "id": 0, "name": "front", "enabled": true, "suppressed": false,
     *       "meanLuma": 85.3, "activeBlocks": 2, "confirmedBlocks": 1,
     *       "threatLevel": 2, "confidence": [0.0, 0.0, 0.3, 0.7, ...] },
     *     ...
     *   ],
     *   "gridCols": 10, "gridRows": 7
     * }
     */
    private static void sendHeatmap(OutputStream out) throws Exception {
        GpuSurveillancePipeline gpuPipeline = CameraDaemon.getGpuPipeline();
        
        JSONObject response = new JSONObject();
        response.put("gridCols", 10);
        response.put("gridRows", 7);
        
        // Include current stream view mode so the UI knows whether to draw
        // a 2x2 mosaic heatmap or a single full-frame quadrant heatmap.
        // 0=Mosaic, 1=Front, 2=Right, 3=Rear, 4=Left, -1=No stream
        int viewMode = -1;
        if (gpuPipeline != null) {
            if (gpuPipeline.isStreamingEnabled()) {
                viewMode = gpuPipeline.getStreamViewMode();
            }
            // If not streaming but surveillance is running, report the recording view.
            // Surveillance always records the mosaic, but the heatmap should show
            // all quadrants in a unified layout since there's no visible stream.
            if (viewMode < 0 && gpuPipeline.isSurveillanceMode()) {
                viewMode = 0;  // Mosaic (surveillance records all cameras)
            }
        }
        response.put("viewMode", viewMode);
        
        JSONArray quadrants = new JSONArray();
        String[] names = {"front", "right", "left", "rear"};
        
        SurveillanceEngineGpu sentry = (gpuPipeline != null) ? gpuPipeline.getSentry() : null;
        MotionPipelineV2.QuadrantResult[] results = (sentry != null) ? sentry.getV2Results() : null;
        
        for (int q = 0; q < 4; q++) {
            JSONObject qObj = new JSONObject();
            qObj.put("id", q);
            qObj.put("name", names[q]);
            
            if (results != null && results[q] != null) {
                qObj.put("enabled", true);
                qObj.put("suppressed", results[q].brightnessSuppressed);
                qObj.put("meanLuma", Math.round(results[q].meanLuma * 10) / 10.0);
                qObj.put("activeBlocks", results[q].activeBlocks);
                qObj.put("confirmedBlocks", results[q].confirmedBlocks);
                qObj.put("threatLevel", results[q].threatLevel);
                qObj.put("componentSize", results[q].componentSize);
                
                // Block confidence array (70 floats, rounded to 2 decimal places)
                JSONArray conf = new JSONArray();
                for (int i = 0; i < results[q].blockConfidence.length; i++) {
                    conf.put(Math.round(results[q].blockConfidence[i] * 100) / 100.0);
                }
                qObj.put("confidence", conf);
            } else {
                qObj.put("enabled", false);
                qObj.put("suppressed", false);
            }
            
            quadrants.put(qObj);
        }
        
        response.put("quadrants", quadrants);
        HttpResponse.sendJson(out, response.toString());
    }
    
    /**
     * Returns recent filter debug log entries.
     * Ring buffer of the last 100 filter decisions (newest first).
     */
    private static void sendFilterLog(OutputStream out) throws Exception {
        GpuSurveillancePipeline gpuPipeline = CameraDaemon.getGpuPipeline();
        SurveillanceEngineGpu sentry = (gpuPipeline != null) ? gpuPipeline.getSentry() : null;
        
        JSONObject response = new JSONObject();
        JSONArray entries = new JSONArray();
        
        if (sentry != null) {
            String[] logEntries = sentry.getFilterLogEntries();
            for (String entry : logEntries) {
                if (entry != null) entries.put(entry);
            }
        }
        
        response.put("entries", entries);
        response.put("count", entries.length());
        HttpResponse.sendJson(out, response.toString());
    }
    
    /**
     * Serves a JPEG snapshot of a specific camera quadrant for the ROI drawing UI.
     * 
     * Strategy:
     * 1. Try live mosaic frame from surveillance engine (available when sentry is running)
     * 2. Fall back to extracting a frame from the most recent event video on disk
     */
    private static void sendQuadrantSnapshot(OutputStream out, int quadrant) throws Exception {
        if (quadrant < 0 || quadrant > 3) {
            HttpResponse.sendJsonError(out, Messages.get("errors.surveillance_invalid_quadrant_with_id", quadrant));
            return;
        }
        
        // Try live mosaic frame first
        byte[] mosaicRgb = null;
        GpuSurveillancePipeline gpuPipeline = CameraDaemon.getGpuPipeline();
        if (gpuPipeline != null && gpuPipeline.getSentry() != null) {
            mosaicRgb = gpuPipeline.getSentry().getLatestMosaicFrame();
        }
        
        if (mosaicRgb != null) {
            // Live frame available — crop quadrant from 640x480 mosaic
            sendQuadrantFromMosaic(out, mosaicRgb, quadrant, 640, 480);
            return;
        }
        
        // Fallback: extract frame from most recent event video on disk
        android.graphics.Bitmap frameBitmap = getFrameFromLatestEvent();
        if (frameBitmap != null) {
            try {
                // Event videos are mosaic (all 4 cameras) — crop the quadrant
                int fullW = frameBitmap.getWidth();
                int fullH = frameBitmap.getHeight();
                int qW = fullW / 2;
                int qH = fullH / 2;
                int startX = (quadrant % 2) * qW;
                int startY = (quadrant / 2) * qH;
                
                android.graphics.Bitmap cropped = android.graphics.Bitmap.createBitmap(
                        frameBitmap, startX, startY, qW, qH);
                
                java.io.ByteArrayOutputStream jpegOut = new java.io.ByteArrayOutputStream();
                cropped.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, jpegOut);
                if (cropped != frameBitmap) cropped.recycle();
                frameBitmap.recycle();
                
                byte[] jpegBytes = jpegOut.toByteArray();
                String header = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: image/jpeg\r\n" +
                        "Content-Length: " + jpegBytes.length + "\r\n" +
                        "Cache-Control: no-cache\r\n" +
                        "Access-Control-Allow-Origin: *\r\n" +
                        "\r\n";
                out.write(header.getBytes());
                out.write(jpegBytes);
                out.flush();
            } catch (Exception e) {
                frameBitmap.recycle();
                HttpResponse.sendJsonError(out, Messages.get("errors.surveillance_event_frame_failed_with_detail", e.getMessage()));
            }
            return;
        }
        
        HttpResponse.sendJsonError(out, Messages.get("errors.surveillance_no_frame_available"));
    }
    
    /**
     * Crops a quadrant from a raw RGB mosaic byte array and sends as JPEG.
     */
    private static void sendQuadrantFromMosaic(OutputStream out, byte[] mosaicRgb, int quadrant, int mosaicW, int mosaicH) throws Exception {
        int qW = mosaicW / 2, qH = mosaicH / 2;
        int startX = (quadrant % 2) * qW;
        int startY = (quadrant / 2) * qH;
        
        int[] pixels = new int[qW * qH];
        for (int y = 0; y < qH; y++) {
            for (int x = 0; x < qW; x++) {
                int srcIdx = ((startY + y) * mosaicW + (startX + x)) * 3;
                if (srcIdx + 2 < mosaicRgb.length) {
                    int r = mosaicRgb[srcIdx] & 0xFF;
                    int g = mosaicRgb[srcIdx + 1] & 0xFF;
                    int b = mosaicRgb[srcIdx + 2] & 0xFF;
                    pixels[y * qW + x] = 0xFF000000 | (r << 16) | (g << 8) | b;
                }
            }
        }
        
        android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(
                pixels, qW, qH, android.graphics.Bitmap.Config.ARGB_8888);
        
        java.io.ByteArrayOutputStream jpegOut = new java.io.ByteArrayOutputStream();
        bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, jpegOut);
        bitmap.recycle();
        
        byte[] jpegBytes = jpegOut.toByteArray();
        String header = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: image/jpeg\r\n" +
                "Content-Length: " + jpegBytes.length + "\r\n" +
                "Cache-Control: no-cache\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "\r\n";
        out.write(header.getBytes());
        out.write(jpegBytes);
        out.flush();
    }
    
    /**
     * Extracts a frame from the most recent event video in the surveillance directory.
     * Returns a Bitmap or null if no events exist.
     */
    private static android.graphics.Bitmap getFrameFromLatestEvent() {
        try {
            com.overdrive.app.storage.StorageManager storage = com.overdrive.app.storage.StorageManager.getInstance();
            java.io.File survDir = storage.getSurveillanceDir();
            if (survDir == null || !survDir.exists()) return null;
            
            java.io.File[] events = survDir.listFiles((dir, name) -> 
                    name.startsWith("event_") && name.endsWith(".mp4"));
            if (events == null || events.length == 0) return null;
            
            // Sort by name descending (newest first — filenames contain timestamp)
            java.util.Arrays.sort(events, (a, b) -> b.getName().compareTo(a.getName()));
            
            // Try the most recent file (fall back to next if extraction fails).
            // Use the FileDescriptor overload — setDataSource(String) NPEs on the
            // headless daemon because ActivityThread.currentApplication() is null.
            for (int i = 0; i < Math.min(3, events.length); i++) {
                android.media.MediaMetadataRetriever retriever = new android.media.MediaMetadataRetriever();
                try (java.io.FileInputStream fis = new java.io.FileInputStream(events[i])) {
                    retriever.setDataSource(fis.getFD());
                    android.graphics.Bitmap frame = retriever.getFrameAtTime(
                            1000000, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                    if (frame == null) {
                        frame = retriever.getFrameAtTime(0, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                    }
                    if (frame != null) return frame;
                } catch (Exception e) {
                    // Try next file
                } finally {
                    try { retriever.release(); } catch (Exception ignored) {}
                }
            }
        } catch (Exception e) {
            // Storage not available
        }
        return null;
    }

    // ==================== Camera-preview cold-start single-flight ====================
    //
    // The camera-mapping dialog renders preview tiles in sequence (Prev/Next
    // navigation, ~2s polling cadence). Without coordination, each unmapped-
    // pipeline request would call gpuPipeline.start(false) + Thread.sleep on
    // the HTTP worker; multiple in-flight requests stacked starts and burned
    // 6+ seconds of HTTP-thread time per dialog open.
    //
    // Single-flight gate — first request triggers an async start on a
    // dedicated executor and returns 503 Retry-After=2s. Subsequent requests
    // also return 503 until the pipeline is up. No HTTP worker ever blocks on
    // a HAL warm-up.
    private static final java.util.concurrent.atomic.AtomicBoolean coldStartInProgress =
        new java.util.concurrent.atomic.AtomicBoolean(false);
    // Set by prepare-restart to mark the daemon as "shutting down for a
    // restart that the dialog is about to SIGKILL through". When true,
    // requestColdStartAsync returns false → sendCameraPreview falls through
    // to "Preview unavailable" instead of looping the dialog on 503 while
    // the daemon dies. The flag isn't cleared on this daemon (the kill is
    // imminent); the relaunched JVM resets it naturally.
    private static volatile boolean shutdownInProgress = false;
    private static final java.util.concurrent.ExecutorService coldStartExecutor =
        java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "CameraPreviewColdStart");
            t.setDaemon(true);
            return t;
        });

    private static boolean requestColdStartAsync(GpuSurveillancePipeline pipeline) {
        if (pipeline == null) return false;
        // If a shutdown is in flight, do not promise the dialog that
        // warming-up will eventually serve a frame — the daemon is about
        // to die. Tell sendCameraPreview to send "Preview unavailable" so
        // the dialog stops polling.
        if (shutdownInProgress) return false;
        // CAS: if another cold-start is already in flight, that's fine —
        // the existing executor task will finish and serve subsequent
        // requests. Tell caller to send 503 retry.
        if (!coldStartInProgress.compareAndSet(false, true)) return true;
        coldStartExecutor.execute(() -> {
            try {
                CameraDaemon.log("camera-preview: cold-starting pipeline (single-flight)");
                pipeline.start(false);
            } catch (Exception e) {
                CameraDaemon.log("camera-preview cold start failed: " + e.getMessage());
            } finally {
                coldStartInProgress.set(false);
            }
        });
        return true;
    }

    private static void sendWarmingUp(OutputStream out) throws Exception {
        String body = "{\"success\":false,\"error\":\"warming-up\",\"retryAfterMs\":2000}";
        byte[] payload = body.getBytes("UTF-8");
        String header = "HTTP/1.1 503 Service Unavailable\r\n" +
                "Content-Type: application/json\r\n" +
                "Content-Length: " + payload.length + "\r\n" +
                "Retry-After: 2\r\n" +
                "Cache-Control: no-cache\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Connection: close\r\n\r\n";
        out.write(header.getBytes());
        out.write(payload);
        out.flush();
    }

    /**
     * GET /api/surveillance/camera-preview?kind=&...
     *
     * Three preview kinds for the diagnostics camera-mapping dialog:
     *   kind=direct&cameraId=N[&width=W&height=H]   — direct AVMCamera open;
     *      refused when surveillance pipeline currently holds the same
     *      cameraId (multi-claim crashes the BYD HAL — event 1002).
     *   kind=panoramicSlice&slice=sliceN            — quadrant of the live
     *      mosaic JPEG published by SurveillanceEngineGpu. Zero camera open,
     *      zero GL work — volatile read + JPEG decode/crop on HTTP worker.
     *   kind=panoramic&view=front|right|rear|left   — same as panoramicSlice
     *      but keyed by virtual view (legacy view-mode mapping).
     *
     * On cold pipeline (proximity-guard mode, surveillance idle) the
     * panoramic kinds trigger a single-flight cold-start and return 503
     * Retry-After=2s. Direct kind never auto-starts the pipeline.
     */
    private static void sendCameraPreview(String path, OutputStream out) throws Exception {
        com.overdrive.app.camera.ResolvedCameraConfig resolvedCamera =
            com.overdrive.app.camera.CameraConfigResolver.resolve();
        String kind = getQueryParam(path, "kind");
        GpuSurveillancePipeline gpuPipeline = CameraDaemon.getGpuPipeline();
        byte[] jpegBytes = null;

        if ("direct".equalsIgnoreCase(kind)) {
            int cameraId = safeParseInt(getQueryParam(path, "cameraId"), -1);
            if (cameraId < 0 || cameraId > 5) {
                HttpResponse.sendJsonError(out, "Invalid direct camera ID");
                return;
            }
            int width = safeParseInt(getQueryParam(path, "width"),
                    resolvedCamera.getProfile().getDirectPreviewWidth());
            int height = safeParseInt(getQueryParam(path, "height"),
                    resolvedCamera.getProfile().getDirectPreviewHeight());

            // For the panoramic camera ID, ALWAYS route to the 2x2 mosaic
            // sampler — never call captureDirectPreviewJpeg. The BYD HAL
            // delivers a 5120×960 raw strip on this camera ID, and an
            // ImageReader sized to (1280×960) just squashes the strip
            // horizontally → wide compressed image, not the 2x2 mosaic
            // the user expects to see. The mosaic sampler renders the
            // proper 2560×1920 (Seal) / 2560×1440 (Tang) 2x2 grid via
            // a sync FBO on its own GL thread (recording-safe).
            //
            // Cold-start the pipeline if it's not running (fresh install /
            // proximity-guard mode before first dialog open).
            if (cameraId == resolvedCamera.getPanoCameraId() && gpuPipeline != null) {
                if (!gpuPipeline.isRunning()) {
                    if (requestColdStartAsync(gpuPipeline)) {
                        sendWarmingUp(out);
                        return;
                    }
                }
                if (gpuPipeline.getCamera() != null) {
                    jpegBytes = gpuPipeline.getCamera().sampleFullResMosaicJpeg();
                }
            } else {
                // Other direct cameras (front-facing, dashcam, cabin, etc.)
                // are single-feed and ImageReader-sizing works correctly.
                jpegBytes = com.overdrive.app.camera.CameraPreviewHelper
                    .captureDirectPreviewJpeg(cameraId, width, height);
            }
        } else if ("panoramicSlice".equalsIgnoreCase(kind)) {
            com.overdrive.app.camera.PanoramicSlice slice =
                com.overdrive.app.camera.PanoramicSlice.fromId(getQueryParam(path, "slice"));
            if (slice == null) {
                HttpResponse.sendJsonError(out, "Invalid panoramic slice");
                return;
            }
            jpegBytes = com.overdrive.app.camera.CameraPreviewHelper.capturePanoramicSliceJpeg(slice);
            if (jpegBytes == null && gpuPipeline != null) {
                if (!gpuPipeline.isRunning()) {
                    if (requestColdStartAsync(gpuPipeline)) {
                        sendWarmingUp(out);
                        return;
                    }
                }
                // Pipeline running, capturePanoramicSliceJpeg already tried
                // both engine-mosaic and high-res slice render via
                // CameraPreviewHelper. If both came back null the camera
                // texture isn't bound yet (cold HAL warmup) — tell dialog
                // to retry.
                if (jpegBytes == null) {
                    sendWarmingUp(out);
                    return;
                }
            }
        } else {
            com.overdrive.app.camera.CameraVirtualView view =
                com.overdrive.app.camera.CameraVirtualView.fromId(getQueryParam(path, "view"));
            if (view == null) {
                HttpResponse.sendJsonError(out, "Invalid panoramic view");
                return;
            }
            jpegBytes = com.overdrive.app.camera.CameraPreviewHelper.capturePanoramicViewJpeg(view);
            if (jpegBytes == null && gpuPipeline != null) {
                boolean cold = !gpuPipeline.isRunning();
                if (cold && requestColdStartAsync(gpuPipeline)) {
                    sendWarmingUp(out);
                    return;
                }
                if (!cold) {
                    sendWarmingUp(out);
                    return;
                }
            }
        }

        if (jpegBytes == null || jpegBytes.length == 0) {
            HttpResponse.sendJsonError(out, "Preview unavailable");
            return;
        }

        String header = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: image/jpeg\r\n" +
                "Content-Length: " + jpegBytes.length + "\r\n" +
                "Cache-Control: no-cache\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "\r\n";
        out.write(header.getBytes());
        out.write(jpegBytes);
        out.flush();
    }

    private static String getQueryParam(String path, String key) {
        if (path == null) return null;
        int queryStart = path.indexOf('?');
        if (queryStart < 0 || queryStart >= path.length() - 1) return null;
        String[] pairs = path.substring(queryStart + 1).split("&");
        for (String pair : pairs) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2 && key.equalsIgnoreCase(kv[0])) {
                try {
                    return java.net.URLDecoder.decode(kv[1], "UTF-8");
                } catch (java.io.UnsupportedEncodingException e) {
                    return kv[1];
                }
            }
        }
        return null;
    }

    private static int safeParseInt(String value, int defaultValue) {
        if (value == null) return defaultValue;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
