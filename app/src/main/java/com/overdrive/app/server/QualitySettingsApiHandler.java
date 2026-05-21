package com.overdrive.app.server;

import com.overdrive.app.daemon.CameraDaemon;
import com.overdrive.app.storage.StorageManager;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.OutputStream;

/**
 * Quality Settings API Handler - manages recording and streaming quality settings.
 * 
 * SOTA: Also handles storage limit settings via StorageManager.
 * 
 * Endpoints:
 * - GET /api/settings/quality - Get current quality settings
 * - POST /api/settings/quality - Update quality settings
 * - GET /api/settings/storage - Get storage limit settings
 * - POST /api/settings/storage - Update storage limit settings
 */
public class QualitySettingsApiHandler {
    
    // Stored quality settings
    // Single user-facing recording quality tier (ECONOMY/STANDARD/HIGH/PREMIUM/MAX).
    // Persisted in UnifiedConfigManager under recording.recordingQuality.
    // Default STANDARD on first load — legacy values reset per migration policy.
    private static String recordingQuality = "STANDARD";
    /** @deprecated mirrors recordingQuality; kept until persistence migration completes. */
    @Deprecated
    private static String recordingBitrate = "STANDARD";
    private static String recordingCodec = "H264";      // H264 or H265
    
    private static final String UNIFIED_CONFIG_FILE = "/data/local/tmp/overdrive_config.json";
    private static final String LEGACY_SETTINGS_FILE = "/data/local/tmp/camera_settings.json";
    
    /**
     * Handle quality settings API requests.
     * @return true if handled
     */
    public static boolean handle(String method, String path, String body, OutputStream out) throws Exception {
        if (path.equals("/api/settings/quality") && method.equals("GET")) {
            sendQualitySettings(out);
            return true;
        }
        if (path.equals("/api/settings/quality") && method.equals("POST")) {
            handleQualitySettingsPost(out, body);
            return true;
        }
        // SOTA: Storage limit settings
        if (path.equals("/api/settings/storage") && method.equals("GET")) {
            sendStorageSettings(out);
            return true;
        }
        if (path.equals("/api/settings/storage") && method.equals("POST")) {
            handleStorageSettingsPost(out, body);
            return true;
        }
        // SOTA: Unified config endpoint for cross-UID sync (proximityGuard, recording, streaming)
        if (path.equals("/api/settings/unified") && method.equals("GET")) {
            sendUnifiedConfig(out);
            return true;
        }
        if (path.equals("/api/settings/unified") && method.equals("POST")) {
            handleUnifiedConfigPost(out, body);
            return true;
        }
        // Telemetry overlay settings
        if (path.equals("/api/settings/telemetry-overlay") && method.equals("GET")) {
            sendTelemetryOverlaySettings(out);
            return true;
        }
        if (path.equals("/api/settings/telemetry-overlay") && method.equals("POST")) {
            handleTelemetryOverlayPost(out, body);
            return true;
        }
        // Web-shell appearance (theme picker shipped on every page).
        // Same UnifiedConfigManager-backed pattern as the rest of /api/settings.
        if (path.equals("/api/settings/appearance") && method.equals("GET")) {
            sendAppearance(out);
            return true;
        }
        if (path.equals("/api/settings/appearance") && method.equals("POST")) {
            handleAppearancePost(out, body);
            return true;
        }
        // Telegram bot status — used by the surveillance settings UI to
        // grey-out the per-tier filter toggles when the bot isn\'t paired,
        // so the user understands why the toggles do nothing instead of
        // silently configuring a feature that can never fire.
        if (path.equals("/api/settings/telegram-status") && method.equals("GET")) {
            sendTelegramStatus(out);
            return true;
        }
        return false;
    }

    /**
     * GET /api/settings/telegram-status — read /data/local/tmp/telegram_config.properties
     * and report whether the bot is configured (token present) and paired
     * (owner_chat_id > 0). Both must be true for any Telegram message to
     * actually leave the device. The web UI uses this to disable the tier
     * filter toggles + show a "pair Telegram first" hint.
     */
    private static void sendTelegramStatus(OutputStream out) throws Exception {
        boolean configured = false;
        boolean paired = false;
        try {
            configured = com.overdrive.app.telegram.config.UnifiedTelegramConfig.hasBotToken();
            paired = configured
                    && com.overdrive.app.telegram.config.UnifiedTelegramConfig.getOwnerChatId() > 0;
        } catch (Exception e) {
            // Treat any read failure as "not configured" — the UI will
            // grey out the toggles and the runtime gate (NotificationGate
            // → daemon "Owner not set") still backstops the user.
        }
        JSONObject response = new JSONObject();
        response.put("success", true);
        response.put("configured", configured);
        response.put("paired", paired);
        // `enabled` = the gate condition the engine effectively uses (token
        // present AND owner paired). Surface as a single field so the UI
        // doesn\'t have to re-compute the same logic.
        response.put("enabled", configured && paired);
        HttpResponse.sendJson(out, response.toString());
    }

    /**
     * GET /api/settings/appearance — return the saved theme + locale
     * preferences for the WEB UI (not the Android app). Defaults to
     * theme=dark / locale=auto so first-load matches the design system.
     *
     * Note: `locale` here is the web-only language pick. The Android
     * app's locale lives in LocaleManager and is round-tripped through
     * /api/i18n/lang. Keeping these endpoints separate is what stops
     * picking Hindi on the tunnel from also flipping the in-car app.
     */
    private static void sendAppearance(OutputStream out) throws Exception {
        JSONObject app = com.overdrive.app.config.UnifiedConfigManager.getAppearance();
        JSONObject response = new JSONObject();
        response.put("success", true);
        response.put("theme", app.optString("theme", "dark"));
        response.put("locale", app.optString("locale", "auto"));
        HttpResponse.sendJson(out, response.toString());
    }

    /**
     * POST /api/settings/appearance — body: { "theme": "dark"|"light"|"auto",
     *                                          "locale": "<bcp47>"|"auto" }.
     * Either field may be omitted (partial update). theme is validated to
     * one of three strings; locale is validated against LocaleManager
     * SUPPORTED set (with "auto" sentinel allowed). Persists into the
     * appearance section of the unified config, NOT into LocaleManager.
     */
    private static void handleAppearancePost(OutputStream out, String body) throws Exception {
        JSONObject response = new JSONObject();
        try {
            JSONObject req = new JSONObject(body == null ? "{}" : body);
            JSONObject app = new JSONObject();
            String theme = req.optString("theme", null);
            if (theme != null) {
                if (!"dark".equals(theme) && !"light".equals(theme) && !"auto".equals(theme)) {
                    response.put("success", false);
                    response.put("error", "theme must be one of: dark, light, auto");
                    HttpResponse.sendJson(out, response.toString());
                    return;
                }
                app.put("theme", theme);
            }
            String locale = req.optString("locale", null);
            if (locale != null) {
                if (!"auto".equals(locale) && !com.overdrive.app.server.LocaleManager.isSupported(locale)) {
                    response.put("success", false);
                    response.put("error", "locale must be 'auto' or one of the supported tags");
                    HttpResponse.sendJson(out, response.toString());
                    return;
                }
                app.put("locale", locale);
            }
            boolean ok = com.overdrive.app.config.UnifiedConfigManager.setAppearance(app);
            response.put("success", ok);
            if (theme != null)  response.put("theme", theme);
            if (locale != null) response.put("locale", locale);
        } catch (Exception e) {
            response.put("success", false);
            response.put("error", e.getMessage());
        }
        HttpResponse.sendJson(out, response.toString());
    }
    
    /**
     * Send storage limit settings.
     */
    private static void sendStorageSettings(OutputStream out) throws Exception {
        StorageManager storage = StorageManager.getInstance();
        
        // SOTA: Refresh SD card detection if not currently available
        // This handles the case where SD card was inserted after app start
        if (!storage.isSdCardAvailable()) {
            storage.refreshSdCard();
        }
        
        JSONObject response = new JSONObject();
        response.put("success", true);
        response.put("recordingsLimitMb", storage.getRecordingsLimitMb());
        response.put("surveillanceLimitMb", storage.getSurveillanceLimitMb());
        response.put("minLimitMb", StorageManager.getMinLimitMb());
        response.put("maxLimitMb", StorageManager.getMaxLimitMb());
        response.put("maxLimitMbSdCard", StorageManager.getMaxLimitMbSdCard());
        response.put("recordingsPath", storage.getRecordingsPath());
        response.put("surveillancePath", storage.getSurveillancePath());
        response.put("recordingsSize", storage.getRecordingsSize());
        response.put("surveillanceSize", storage.getSurveillanceSize());
        response.put("recordingsCount", storage.getRecordingsCount());
        response.put("surveillanceCount", storage.getSurveillanceCount());
        
        // SOTA: Storage type selection
        response.put("recordingsStorageType", storage.getRecordingsStorageType().name());
        response.put("surveillanceStorageType", storage.getSurveillanceStorageType().name());
        
        // SD card info
        response.put("sdCardAvailable", storage.isSdCardAvailable());
        response.put("sdCardPath", storage.getSdCardPath());
        if (storage.isSdCardAvailable()) {
            response.put("sdCardFreeSpace", storage.getSdCardFreeSpace());
            response.put("sdCardTotalSpace", storage.getSdCardTotalSpace());
            response.put("sdCardFreeFormatted", StorageManager.formatSize(storage.getSdCardFreeSpace()));
            response.put("sdCardTotalFormatted", StorageManager.formatSize(storage.getSdCardTotalSpace()));
        }
        
        // Internal storage info
        response.put("internalFreeSpace", storage.getInternalFreeSpace());
        response.put("internalTotalSpace", storage.getInternalTotalSpace());
        response.put("internalFreeFormatted", StorageManager.formatSize(storage.getInternalFreeSpace()));
        response.put("internalTotalFormatted", StorageManager.formatSize(storage.getInternalTotalSpace()));
        
        HttpResponse.sendJson(out, response.toString());
    }
    
    /**
     * Handle storage settings POST.
     */
    private static void handleStorageSettingsPost(OutputStream out, String body) throws Exception {
        try {
            JSONObject settings = new JSONObject(body);
            StorageManager storage = StorageManager.getInstance();
            
            // Handle storage type changes first (before limit changes)
            boolean storageTypeChanged = false;
            
            if (settings.has("recordingsStorageType")) {
                String typeStr = settings.getString("recordingsStorageType").toUpperCase();
                StorageManager.StorageType type = "SD_CARD".equals(typeStr) ? 
                    StorageManager.StorageType.SD_CARD : StorageManager.StorageType.INTERNAL;
                boolean success = storage.setRecordingsStorageType(type);
                if (success) {
                    storageTypeChanged = true;
                    CameraDaemon.log("Recordings storage type set to: " + type);
                } else {
                    CameraDaemon.log("Failed to set recordings storage type to SD_CARD - not available");
                }
            }
            
            if (settings.has("surveillanceStorageType")) {
                String typeStr = settings.getString("surveillanceStorageType").toUpperCase();
                StorageManager.StorageType type = "SD_CARD".equals(typeStr) ? 
                    StorageManager.StorageType.SD_CARD : StorageManager.StorageType.INTERNAL;
                boolean success = storage.setSurveillanceStorageType(type);
                if (success) {
                    storageTypeChanged = true;
                    CameraDaemon.log("Surveillance storage type set to: " + type);
                    
                    // Update running sentry engine's output directory to match new storage
                    try {
                        com.overdrive.app.surveillance.GpuSurveillancePipeline pipeline =
                            CameraDaemon.getGpuPipeline();
                        if (pipeline != null && pipeline.getSentry() != null) {
                            pipeline.getSentry().setEventOutputDir(storage.getSurveillanceDir());
                            CameraDaemon.log("Updated sentry output dir: " + 
                                storage.getSurveillanceDir().getAbsolutePath());
                        }
                    } catch (Exception e) {
                        CameraDaemon.log("Warning: could not update sentry output dir: " + e.getMessage());
                    }
                } else {
                    CameraDaemon.log("Failed to set surveillance storage type to SD_CARD - not available");
                }
            }
            
            // Calculate how much will be deleted before applying changes
            long recordingsToDelete = 0;
            long surveillanceToDelete = 0;
            int recordingsFilesToDelete = 0;
            int surveillanceFilesToDelete = 0;
            
            if (settings.has("recordingsLimitMb")) {
                long newLimit = settings.getLong("recordingsLimitMb");
                long currentSize = storage.getRecordingsSize();
                long newLimitBytes = newLimit * 1024 * 1024;
                if (currentSize > newLimitBytes) {
                    recordingsToDelete = currentSize - newLimitBytes;
                    // Estimate files to delete (rough estimate based on average file size)
                    int count = storage.getRecordingsCount();
                    if (count > 0) {
                        long avgSize = currentSize / count;
                        recordingsFilesToDelete = (int) Math.ceil((double) recordingsToDelete / avgSize);
                    }
                }
                storage.setRecordingsLimitMb(newLimit);
                CameraDaemon.log("Recordings limit set to: " + newLimit + " MB");
            }
            
            if (settings.has("surveillanceLimitMb")) {
                long newLimit = settings.getLong("surveillanceLimitMb");
                long currentSize = storage.getSurveillanceSize();
                long newLimitBytes = newLimit * 1024 * 1024;
                if (currentSize > newLimitBytes) {
                    surveillanceToDelete = currentSize - newLimitBytes;
                    // Estimate files to delete
                    int count = storage.getSurveillanceCount();
                    if (count > 0) {
                        long avgSize = currentSize / count;
                        surveillanceFilesToDelete = (int) Math.ceil((double) surveillanceToDelete / avgSize);
                    }
                }
                storage.setSurveillanceLimitMb(newLimit);
                CameraDaemon.log("Surveillance limit set to: " + newLimit + " MB");
            }
            
            // Run cleanup async to not block HTTP response
            new Thread(() -> {
                storage.runCleanup();
                CameraDaemon.log("Storage cleanup completed after limit change");
            }, "StorageLimitCleanup").start();
            
            JSONObject response = new JSONObject();
            response.put("success", true);
            response.put("recordingsLimitMb", storage.getRecordingsLimitMb());
            response.put("surveillanceLimitMb", storage.getSurveillanceLimitMb());
            response.put("recordingsStorageType", storage.getRecordingsStorageType().name());
            response.put("surveillanceStorageType", storage.getSurveillanceStorageType().name());
            response.put("recordingsPath", storage.getRecordingsPath());
            response.put("surveillancePath", storage.getSurveillancePath());
            
            // Include cleanup info in response
            if (recordingsToDelete > 0 || surveillanceToDelete > 0) {
                JSONObject cleanup = new JSONObject();
                if (recordingsToDelete > 0) {
                    cleanup.put("recordingsToDelete", StorageManager.formatSize(recordingsToDelete));
                    cleanup.put("recordingsFilesEstimate", recordingsFilesToDelete);
                }
                if (surveillanceToDelete > 0) {
                    cleanup.put("surveillanceToDelete", StorageManager.formatSize(surveillanceToDelete));
                    cleanup.put("surveillanceFilesEstimate", surveillanceFilesToDelete);
                }
                response.put("cleanup", cleanup);
                response.put("message", Messages.get("messages.quality_storage_settings_updated_cleanup"));
            } else if (storageTypeChanged) {
                response.put("message", Messages.get("messages.quality_storage_location_changed"));
            } else {
                response.put("message", Messages.get("messages.quality_storage_settings_updated"));
            }
            
            HttpResponse.sendJson(out, response.toString());
            
        } catch (Exception e) {
            CameraDaemon.log("Error setting storage limits: " + e.getMessage());
            HttpResponse.sendJsonError(out, e.getMessage());
        }
    }
    
    /**
     * Send full unified config for cross-UID sync.
     * Returns the entire config including proximityGuard, recording, streaming sections.
     */
    private static void sendUnifiedConfig(OutputStream out) throws Exception {
        JSONObject response = new JSONObject();
        response.put("success", true);
        
        try {
            File unifiedFile = new File(UNIFIED_CONFIG_FILE);
            if (unifiedFile.exists()) {
                BufferedReader reader = new BufferedReader(new FileReader(unifiedFile));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                reader.close();
                
                JSONObject config = new JSONObject(sb.toString());
                response.put("config", config);
                response.put("lastModified", unifiedFile.lastModified());
            } else {
                // Return default config structure
                JSONObject config = new JSONObject();
                config.put("version", 1);
                
                JSONObject recording = new JSONObject();
                recording.put("recordingQuality", recordingQuality);
                recording.put("quality", recordingQuality);  // legacy mirror
                recording.put("codec", recordingCodec);
                config.put("recording", recording);
                
                JSONObject streaming = new JSONObject();
                streaming.put("quality", StreamingApiHandler.getStreamingQuality());
                config.put("streaming", streaming);
                
                JSONObject proximityGuard = new JSONObject();
                proximityGuard.put("triggerLevel", "RED");
                proximityGuard.put("preRecordSeconds", 5);
                proximityGuard.put("postRecordSeconds", 10);
                config.put("proximityGuard", proximityGuard);
                
                response.put("config", config);
                response.put("lastModified", System.currentTimeMillis());
            }
        } catch (Exception e) {
            CameraDaemon.log("sendUnifiedConfig: Error reading config: " + e.getMessage());
            // Return minimal default
            JSONObject config = new JSONObject();
            JSONObject proximityGuard = new JSONObject();
            proximityGuard.put("triggerLevel", "RED");
            proximityGuard.put("preRecordSeconds", 5);
            proximityGuard.put("postRecordSeconds", 10);
            config.put("proximityGuard", proximityGuard);
            response.put("config", config);
        }
        
        HttpResponse.sendJson(out, response.toString());
    }
    
    /**
     * Handle unified config POST - updates a specific section.
     * Body format: { "section": "proximityGuard", "data": { ... } }
     */
    private static void handleUnifiedConfigPost(OutputStream out, String body) throws Exception {
        try {
            JSONObject request = new JSONObject(body);
            String section = request.optString("section", "");
            JSONObject data = request.optJSONObject("data");
            
            if (section.isEmpty() || data == null) {
                HttpResponse.sendJsonError(out, Messages.get("errors.quality_missing_section_or_data"));
                return;
            }
            
            File unifiedFile = new File(UNIFIED_CONFIG_FILE);
            JSONObject unified;
            
            if (unifiedFile.exists()) {
                BufferedReader reader = new BufferedReader(new FileReader(unifiedFile));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                reader.close();
                unified = new JSONObject(sb.toString());
            } else {
                unified = new JSONObject();
                unified.put("version", 1);
            }
            
            // SOTA: Merge into existing section (not replace) to preserve keys
            // that are set independently (e.g. surveillanceEnabled is set via toggle,
            // not via the detection settings form). Replacing would wipe it.
            JSONObject existingSection = unified.optJSONObject(section);
            if (existingSection == null) {
                existingSection = new JSONObject();
            }
            java.util.Iterator<String> keys = data.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                existingSection.put(key, data.get(key));
            }
            unified.put(section, existingSection);
            unified.put("lastModified", System.currentTimeMillis());
            
            // Write back
            FileWriter writer = new FileWriter(unifiedFile);
            writer.write(unified.toString(2));
            writer.close();
            
            unifiedFile.setReadable(true, false);
            unifiedFile.setWritable(true, false);
            
            CameraDaemon.log("Unified config section '" + section + "' updated");
            
            JSONObject response = new JSONObject();
            response.put("success", true);
            response.put("section", section);
            response.put("message", Messages.get("messages.quality_config_section_updated"));
            
            HttpResponse.sendJson(out, response.toString());
            
        } catch (Exception e) {
            CameraDaemon.log("Error updating unified config: " + e.getMessage());
            HttpResponse.sendJsonError(out, e.getMessage());
        }
    }

    private static void sendQualitySettings(OutputStream out) throws Exception {
        JSONObject response = new JSONObject();
        response.put("success", true);
        
        // Read from unified config for cross-UID sync
        String currentBitrate = recordingBitrate;
        String currentCodec = recordingCodec;
        String currentRecQuality = recordingQuality;
        String currentStreamQuality = StreamingApiHandler.getStreamingQuality();
        long lastModified = System.currentTimeMillis();
        
        try {
            File unifiedFile = new File(UNIFIED_CONFIG_FILE);
            if (unifiedFile.exists()) {
                lastModified = unifiedFile.lastModified();
                
                BufferedReader reader = new BufferedReader(new FileReader(unifiedFile));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                reader.close();
                
                JSONObject unified = new JSONObject(sb.toString());
                
                JSONObject recording = unified.optJSONObject("recording");
                if (recording != null) {
                    String fileCodec = recording.optString("codec", "");
                    if (fileCodec.equals("H264") || fileCodec.equals("H265")) {
                        currentCodec = fileCodec;
                        recordingCodec = fileCodec;
                    }

                    // Canonical tier first (recordingQuality → quality), then
                    // migrate legacy `bitrate` LOW/MEDIUM/HIGH as a final fallback.
                    // Old `quality` values (LOW/REDUCED/NORMAL) collapse to STANDARD.
                    String fileTier = recording.optString("recordingQuality",
                        recording.optString("quality", ""));
                    if (isKnownTier(fileTier)) {
                        currentRecQuality = fileTier;
                        recordingQuality = fileTier;
                        recordingBitrate = fileTier;
                    } else if (recording.has("bitrate")) {
                        String fileBitrate = recording.optString("bitrate", "").toUpperCase();
                        String tier;
                        switch (fileBitrate) {
                            case "LOW":    tier = "ECONOMY"; break;
                            case "MEDIUM": tier = "STANDARD"; break;
                            case "HIGH":   tier = "HIGH"; break;
                            default:       tier = ""; break;
                        }
                        if (!tier.isEmpty()) {
                            currentRecQuality = tier;
                            recordingQuality = tier;
                            recordingBitrate = tier;
                        }
                    } else if (!fileTier.isEmpty()) {
                        currentRecQuality = "STANDARD";
                        recordingQuality = "STANDARD";
                        recordingBitrate = "STANDARD";
                    }
                    currentBitrate = recordingBitrate;
                }
                
                JSONObject streaming = unified.optJSONObject("streaming");
                if (streaming != null) {
                    String fileStreamQuality = streaming.optString("quality", "");
                    if (!fileStreamQuality.isEmpty()) {
                        currentStreamQuality = fileStreamQuality;
                        StreamingApiHandler.setStreamingQuality(fileStreamQuality);
                    }
                }
            }
        } catch (Exception e) {
            CameraDaemon.log("sendQualitySettings: Could not read unified config: " + e.getMessage());
        }
        
        // Single user-facing recording quality tier. Bundles bitrate +
        // perceptual expectation. FPS and codec stay independent.
        // Migrate any legacy LOW/REDUCED/NORMAL value silently to STANDARD.
        String tierFromConfig;
        try {
            org.json.JSONObject recCfg = com.overdrive.app.config.UnifiedConfigManager
                .loadConfig().optJSONObject("recording");
            tierFromConfig = recCfg != null ? recCfg.optString("recordingQuality", null) : null;
        } catch (Exception e) {
            tierFromConfig = null;
        }
        com.overdrive.app.surveillance.GpuPipelineConfig.RecordingQuality activeTier =
            com.overdrive.app.surveillance.GpuPipelineConfig.RecordingQuality.fromString(tierFromConfig);

        response.put("recordingQuality", activeTier.name());
        response.put("streamingQuality", currentStreamQuality);
        response.put("recordingCodec", currentCodec);
        response.put("lastModified", lastModified);
        
        // Camera FPS setting
        int currentFps = 15;
        try {
            org.json.JSONObject cameraConfig = com.overdrive.app.config.UnifiedConfigManager
                .loadConfig().optJSONObject("camera");
            if (cameraConfig != null) {
                currentFps = cameraConfig.optInt("targetFps", 15);
            }
        } catch (Exception e) { /* use default */ }
        response.put("cameraFps", currentFps);

        // Surface measured FPS so the UI can show actualFps when HAL clamps
        // below requested (e.g., user picks 30, HAL emits ~26 panoramic on
        // this device). 0 means "not measured yet" — the renderLoop only
        // updates this every 2 minutes.
        try {
            com.overdrive.app.surveillance.GpuSurveillancePipeline pipeline =
                CameraDaemon.getGpuPipeline();
            float measured = (pipeline != null && pipeline.getCamera() != null)
                ? pipeline.getCamera().getMeasuredFps() : 0f;
            if (measured > 0f) {
                response.put("cameraFpsActual", Math.round(measured * 10) / 10.0);
                if (Math.abs(measured - currentFps) > 1.5f) {
                    response.put("cameraFpsClampNote",
                        "HAL emitting at ~" + Math.round(measured)
                            + " fps (requested " + currentFps + ")");
                }
            }
        } catch (Exception ignored) {}

        // Recording quality tiers — single user-facing knob.
        // Includes per-tier bitrate (resolved against current codec) and
        // size estimate so the UI can show "X MB/min, ~Y GB/hour".
        // Note: bitrate is bandwidth-per-second, FPS does not change file
        // size at fixed bitrate (higher fps just spreads bits over more
        // frames, reducing per-frame detail).
        com.overdrive.app.surveillance.GpuPipelineConfig.VideoCodec codecForEstimate =
            "H265".equalsIgnoreCase(currentCodec)
                ? com.overdrive.app.surveillance.GpuPipelineConfig.VideoCodec.H265
                : com.overdrive.app.surveillance.GpuPipelineConfig.VideoCodec.H264;

        JSONObject qualityInfo = new JSONObject();
        for (com.overdrive.app.surveillance.GpuPipelineConfig.RecordingQuality q :
                com.overdrive.app.surveillance.GpuPipelineConfig.RecordingQuality.values()) {
            JSONObject entry = new JSONObject();
            int br = q.getBitrateForCodec(codecForEstimate);
            entry.put("displayName", q.displayName);
            entry.put("bitrateBps", br);
            entry.put("bitrateMbps", Math.round(br / 100_000.0) / 10.0);
            entry.put("mbPerMinute", Math.round(q.estimateMbPerMinute(codecForEstimate) * 10) / 10.0);
            entry.put("gbPerHour", Math.round(q.estimateMbPerHour(codecForEstimate) / 102.4) / 10.0);
            // Perceptual equivalent at the user's current fps. Drops one tier
            // at 30 fps vs 15 fps because the encoder spreads bits over more
            // frames. UI should label this as approximate — native resolution
            // is fixed at 2560×1920 regardless of tier.
            entry.put("qualityEquivalent", q.getQualityEquivalent(codecForEstimate, currentFps));
            qualityInfo.put(q.name(), entry);
        }
        response.put("recordingQualityOptions", qualityInfo);
        response.put("nativeResolution", "2560×1920 mosaic · 4 × 1280×960 cameras");

        // Currently-active size estimate so the UI can render
        // "uses ~X GB/hour at your current settings" without iterating the
        // options dict. Recomputed from active tier + active codec each call.
        JSONObject activeEstimate = new JSONObject();
        double mbPerMin = activeTier.estimateMbPerMinute(codecForEstimate);
        activeEstimate.put("bitrateMbps", Math.round(activeTier.getBitrateForCodec(codecForEstimate) / 100_000.0) / 10.0);
        activeEstimate.put("mbPerMinute", Math.round(mbPerMin * 10) / 10.0);
        activeEstimate.put("mbPer2Min", Math.round(mbPerMin * 2 * 10) / 10.0);
        activeEstimate.put("gbPerHour", Math.round(mbPerMin * 60 / 102.4) / 10.0);
        // Minutes of recording per 1 GB of storage — easier to reason about
        // for a parked surveillance session than fractional GB/hr numbers.
        if (mbPerMin > 0) {
            activeEstimate.put("minutesPerGb", Math.round(1024.0 / mbPerMin));
        }
        activeEstimate.put("qualityEquivalent", activeTier.getQualityEquivalent(codecForEstimate, currentFps));
        response.put("activeRecordingEstimate", activeEstimate);
        
        // Add codec info for UI
        JSONObject codecInfo = new JSONObject();
        codecInfo.put("H264", "H.264/AVC (Compatible)");
        codecInfo.put("H265", "H.265/HEVC (50% smaller)");
        response.put("codecOptions", codecInfo);
        
        // Add FPS options for UI. Range 10..30 — clamped server-side by
        // GpuSurveillancePipeline.applyFpsChange. The HAL on this device
        // tops out around 26 fps panoramic, so 30 will clamp gracefully.
        JSONObject fpsInfo = new JSONObject();
        fpsInfo.put("10", "10 FPS (Low power)");
        fpsInfo.put("15", "15 FPS (Balanced)");
        fpsInfo.put("20", "20 FPS (Smooth)");
        fpsInfo.put("25", "25 FPS (High motion)");
        fpsInfo.put("30", "30 FPS (Max — HAL ceiling ~26)");
        response.put("fpsOptions", fpsInfo);
        
        HttpResponse.sendJson(out, response.toString());
    }

    private static void handleQualitySettingsPost(OutputStream out, String body) throws Exception {
        try {
            JSONObject settings = new JSONObject(body);
            
            if (settings.has("recordingQuality")) {
                String tier = settings.getString("recordingQuality").toUpperCase();
                if (tier.equals("ECONOMY") || tier.equals("STANDARD")
                        || tier.equals("HIGH") || tier.equals("PREMIUM")
                        || tier.equals("MAX")) {
                    recordingQuality = tier;
                    CameraDaemon.log("Recording quality set to: " + tier);
                    CameraDaemon.setRecordingQuality(tier);
                } else {
                    CameraDaemon.log("Rejecting recordingQuality=" + tier
                        + " — must be one of ECONOMY/STANDARD/HIGH/PREMIUM/MAX");
                }
            }

            if (settings.has("streamingQuality")) {
                String streamQuality = settings.getString("streamingQuality").toUpperCase();
                if (streamQuality.equals("ULTRA_LOW") || streamQuality.equals("LOW")
                        || streamQuality.equals("MEDIUM") || streamQuality.equals("HIGH")
                        || streamQuality.equals("ULTRA_HIGH") || streamQuality.equals("SMOOTH")
                        || streamQuality.equals("MAX")
                        || streamQuality.equals("LQ") || streamQuality.equals("HQ")) {
                    StreamingApiHandler.setStreamingQuality(streamQuality);
                    CameraDaemon.log("Streaming quality set to: " + streamQuality);
                    CameraDaemon.setStreamingQuality(streamQuality);
                }
            }

            // Legacy `recordingBitrate` key (LOW/MEDIUM/HIGH) — translate
            // to the new tier system. UI should send `recordingQuality`
            // directly going forward; this branch only catches old clients.
            if (settings.has("recordingBitrate") && !settings.has("recordingQuality")) {
                String legacy = settings.getString("recordingBitrate").toUpperCase();
                String tier;
                switch (legacy) {
                    case "LOW":    tier = "ECONOMY"; break;
                    case "MEDIUM": tier = "STANDARD"; break;
                    case "HIGH":   tier = "HIGH"; break;
                    default:       tier = "STANDARD"; break;
                }
                CameraDaemon.log("Legacy recordingBitrate=" + legacy + " → recordingQuality=" + tier);
                recordingQuality = tier;
                CameraDaemon.setRecordingQuality(tier);
            }
            
            if (settings.has("recordingCodec")) {
                String codec = settings.getString("recordingCodec").toUpperCase();
                if (codec.equals("H264") || codec.equals("H265")) {
                    recordingCodec = codec;
                    CameraDaemon.log("Recording codec set to: " + codec);
                    CameraDaemon.setRecordingCodec(codec);
                }
            }
            
            if (settings.has("cameraFps")) {
                int fps = settings.getInt("cameraFps");
                if (fps < 10 || fps > 30) {
                    CameraDaemon.log("Rejecting cameraFps=" + fps + " — out of range [10..30]");
                } else {
                    // applyFpsChange persists to UnifiedConfig, propagates to
                    // the camera, and reinitializes the encoder so KEY_FRAME_RATE
                    // matches. No restart required — change is live.
                    com.overdrive.app.surveillance.GpuSurveillancePipeline pipeline = CameraDaemon.getGpuPipeline();
                    if (pipeline != null) {
                        pipeline.applyFpsChange(fps);
                        CameraDaemon.log("Camera FPS applied: " + fps);
                    } else {
                        // Pipeline not yet created — persist so init() picks it up.
                        try {
                            org.json.JSONObject camCfg = com.overdrive.app.config.UnifiedConfigManager
                                .loadConfig().optJSONObject("camera");
                            if (camCfg == null) camCfg = new org.json.JSONObject();
                            camCfg.put("targetFps", fps);
                            com.overdrive.app.config.UnifiedConfigManager.updateSection("camera", camCfg);
                            CameraDaemon.log("Camera FPS saved (pipeline not ready): " + fps);
                        } catch (Exception e) {
                            CameraDaemon.log("Failed to save camera FPS: " + e.getMessage());
                        }
                    }
                }
            }
            
            JSONObject response = new JSONObject();
            response.put("success", true);
            response.put("recordingBitrate", recordingBitrate);
            response.put("recordingCodec", recordingCodec);
            response.put("note", recordingCodec.equals("H265") ?
                Messages.get("messages.quality_h265_note") : null);
            
            persistSettings();
            
            HttpResponse.sendJson(out, response.toString());
            
        } catch (Exception e) {
            CameraDaemon.log("Error setting quality: " + e.getMessage());
            HttpResponse.sendJsonError(out, e.getMessage());
        }
    }

    /**
     * Loads persisted settings from unified config file.
     * Called during HttpServer initialization.
     */
    public static void loadPersistedSettings() {
        // Try unified config first
        try {
            File unifiedFile = new File(UNIFIED_CONFIG_FILE);
            if (unifiedFile.exists()) {
                BufferedReader reader = new BufferedReader(new FileReader(unifiedFile));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                reader.close();
                
                JSONObject unified = new JSONObject(sb.toString());
                
                JSONObject recording = unified.optJSONObject("recording");
                if (recording != null) {
                    // Canonical tier: recordingQuality (ECONOMY..MAX). Fall back to
                    // legacy `quality` and finally legacy `bitrate` (LOW/MEDIUM/HIGH).
                    String tier = recording.optString("recordingQuality",
                            recording.optString("quality", ""));
                    if (isKnownTier(tier)) {
                        recordingQuality = tier;
                        recordingBitrate = tier;  // mirror — keep in sync
                        CameraDaemon.log("Restored recording tier from unified: " + tier);
                    } else if (recording.has("bitrate")) {
                        String legacyBitrate = recording.getString("bitrate").toUpperCase();
                        switch (legacyBitrate) {
                            case "LOW":    tier = "ECONOMY"; break;
                            case "MEDIUM": tier = "STANDARD"; break;
                            case "HIGH":   tier = "HIGH"; break;
                            default:       tier = ""; break;
                        }
                        if (!tier.isEmpty()) {
                            recordingQuality = tier;
                            recordingBitrate = tier;
                            CameraDaemon.log("Migrated legacy bitrate=" + legacyBitrate
                                    + " → recordingQuality=" + tier);
                        }
                    }
                    if (recording.has("codec")) {
                        String codec = recording.getString("codec");
                        if (codec.equals("H264") || codec.equals("H265")) {
                            recordingCodec = codec;
                            CameraDaemon.log("Restored recording codec from unified: " + codec);
                        }
                    }
                }
                
                JSONObject streaming = unified.optJSONObject("streaming");
                if (streaming != null && streaming.has("quality")) {
                    String quality = streaming.getString("quality");
                    StreamingApiHandler.setStreamingQuality(quality);
                    CameraDaemon.log("Restored streaming quality from unified: " + quality);
                }
                
                CameraDaemon.log("Settings loaded from unified config: " + UNIFIED_CONFIG_FILE);
                return;
            }
        } catch (Exception e) {
            CameraDaemon.log("Could not load from unified config: " + e.getMessage());
        }
        
        // Fallback to legacy settings file
        loadLegacySettings();
    }

    private static void loadLegacySettings() {
        try {
            File file = new File(LEGACY_SETTINGS_FILE);
            CameraDaemon.log("Loading settings from legacy: " + LEGACY_SETTINGS_FILE + " (exists=" + file.exists() + ")");
            if (file.exists()) {
                BufferedReader reader = new BufferedReader(new FileReader(file));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                reader.close();
                
                JSONObject settings = new JSONObject(sb.toString());
                
                // Canonical recordingQuality first; fall back to legacy bitrate.
                if (settings.has("recordingQuality")) {
                    String tier = settings.getString("recordingQuality").toUpperCase();
                    if (isKnownTier(tier)) {
                        recordingQuality = tier;
                        recordingBitrate = tier;
                    }
                } else if (settings.has("recordingBitrate")) {
                    String bitrate = settings.getString("recordingBitrate").toUpperCase();
                    String tier;
                    switch (bitrate) {
                        case "LOW":    tier = "ECONOMY"; break;
                        case "MEDIUM": tier = "STANDARD"; break;
                        case "HIGH":   tier = "HIGH"; break;
                        default:       tier = ""; break;
                    }
                    if (!tier.isEmpty()) {
                        recordingQuality = tier;
                        recordingBitrate = tier;
                    }
                }
                if (settings.has("recordingCodec")) {
                    String codec = settings.getString("recordingCodec");
                    if (codec.equals("H264") || codec.equals("H265")) {
                        recordingCodec = codec;
                    }
                }
                if (settings.has("streamingQuality")) {
                    String quality = settings.getString("streamingQuality");
                    StreamingApiHandler.setStreamingQuality(quality);
                }
                
                CameraDaemon.log("Settings loaded from legacy " + LEGACY_SETTINGS_FILE);
                // Migrate to unified config
                persistSettings();
            }
        } catch (Exception e) {
            CameraDaemon.log("Could not load legacy settings: " + e.getMessage());
        }
    }
    
    /**
     * Persists current settings to unified config file via UnifiedConfigManager.
     *
     * Routing through UCM (instead of doing direct file I/O) acquires the
     * UCM lock, gets the atomic-rename write semantics, and prevents this
     * write from racing with concurrent updateSection calls (e.g. a camera
     * probe persisting its findings at the same time the user clicks Save).
     */
    public static void persistSettings() {
        try {
            org.json.JSONObject recording = new org.json.JSONObject();
            // Canonical tier; `quality` is the legacy mirror. We deliberately
            // do NOT write `bitrate` (LOW/MEDIUM/HIGH) — that's the field
            // that historically drifted out of sync with the active tier.
            recording.put("recordingQuality", recordingQuality);
            recording.put("quality", recordingQuality);
            recording.put("codec", recordingCodec);
            com.overdrive.app.config.UnifiedConfigManager.updateSection("recording", recording);

            org.json.JSONObject streaming = new org.json.JSONObject();
            streaming.put("quality", StreamingApiHandler.getStreamingQuality());
            com.overdrive.app.config.UnifiedConfigManager.updateSection("streaming", streaming);

            CameraDaemon.log("Settings persisted via UnifiedConfigManager");
        } catch (Exception e) {
            CameraDaemon.log("Could not persist settings: " + e.getMessage());
        }
    }

    // Static getters for cross-component access
    public static String getRecordingQuality() { return recordingQuality; }
    public static String getRecordingBitrate() { return recordingBitrate; }
    public static String getRecordingCodec() { return recordingCodec; }
    
    // Static setters for app UI and IPC. recordingQuality accepts the new
    // tier names (ECONOMY..MAX); legacy names are migrated to STANDARD per
    // the migration policy so old IPC clients don't get silently swallowed.
    public static void setRecordingQuality(String quality) {
        if (quality == null) return;
        String tier;
        if (isKnownTier(quality)) {
            tier = quality.toUpperCase();
        } else {
            // Legacy LOW/REDUCED/NORMAL → STANDARD.
            tier = "STANDARD";
        }
        recordingQuality = tier;
        CameraDaemon.setRecordingQuality(tier);
        persistSettings();
    }

    /** @deprecated use setRecordingQuality with ECONOMY..MAX. */
    @Deprecated
    public static void setRecordingBitrate(String bitrate) {
        if (bitrate == null) return;
        String tier;
        switch (bitrate.toUpperCase()) {
            case "LOW":    tier = "ECONOMY"; break;
            case "MEDIUM": tier = "STANDARD"; break;
            case "HIGH":   tier = "HIGH"; break;
            default:       tier = "STANDARD"; break;
        }
        setRecordingQuality(tier);
    }

    /** Validates a tier name without depending on the enum class (this
     *  handler runs in the daemon process before the surveillance pipeline
     *  is built — keep the check string-based and cheap). */
    private static boolean isKnownTier(String s) {
        if (s == null) return false;
        switch (s.toUpperCase()) {
            case "ECONOMY":
            case "STANDARD":
            case "HIGH":
            case "PREMIUM":
            case "MAX":
                return true;
            default:
                return false;
        }
    }
    
    public static void setRecordingCodec(String codec) {
        if (codec.equals("H264") || codec.equals("H265")) {
            recordingCodec = codec;
            CameraDaemon.setRecordingCodec(codec);
            persistSettings();
        }
    }
    
    // Static setters for IPC server (updates variable only, no CameraDaemon call).
    // Accepts both canonical tier names (ECONOMY..MAX) and legacy LOW/MEDIUM/HIGH.
    public static void setRecordingBitrateStatic(String value) {
        if (value == null) return;
        String v = value.toUpperCase();
        String tier;
        switch (v) {
            case "LOW":    tier = "ECONOMY"; break;
            case "MEDIUM": tier = "STANDARD"; break;
            case "HIGH":
            case "ECONOMY":
            case "STANDARD":
            case "PREMIUM":
            case "MAX":    tier = v; break;
            default:       return;
        }
        recordingBitrate = tier;
        recordingQuality = tier;
    }
    
    public static void setRecordingCodecStatic(String codec) {
        if (codec.equals("H264") || codec.equals("H265")) {
            recordingCodec = codec;
        }
    }

    /**
     * Send telemetry overlay settings.
     */
    private static void sendTelemetryOverlaySettings(OutputStream out) throws Exception {
        JSONObject overlayConfig = com.overdrive.app.config.UnifiedConfigManager.getTelemetryOverlay();
        JSONObject response = new JSONObject();
        response.put("success", true);
        response.put("enabled", overlayConfig.optBoolean("enabled", false));
        HttpResponse.sendJson(out, response.toString());
    }

    /**
     * Handle telemetry overlay settings POST.
     */
    private static void handleTelemetryOverlayPost(OutputStream out, String body) throws Exception {
        try {
            JSONObject settings = new JSONObject(body);
            boolean enabled = settings.optBoolean("enabled", false);

            JSONObject overlayConfig = new JSONObject();
            overlayConfig.put("enabled", enabled);
            com.overdrive.app.config.UnifiedConfigManager.setTelemetryOverlay(overlayConfig);

            // Notify pipeline
            com.overdrive.app.surveillance.GpuSurveillancePipeline pipeline = CameraDaemon.getGpuPipeline();
            if (pipeline != null) {
                pipeline.setOverlayEnabled(enabled);
            }

            JSONObject response = new JSONObject();
            response.put("success", true);
            response.put("enabled", enabled);
            HttpResponse.sendJson(out, response.toString());
        } catch (Exception e) {
            CameraDaemon.log("Error setting telemetry overlay: " + e.getMessage());
            HttpResponse.sendJsonError(out, e.getMessage());
        }
    }
}
