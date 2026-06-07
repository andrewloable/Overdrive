package net.bladewatch.app.server;

import net.bladewatch.app.daemon.CameraDaemon;
import net.bladewatch.app.logging.DaemonLogger;
import net.bladewatch.app.server.IpcTokenManager;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * IPC server for surveillance configuration.
 * Listens on port 19877 for surveillance config commands from the app UI.
 * 
 * SOTA: Uses thread pool to prevent thread exhaustion under load.
 */
public class SurveillanceIpcServer implements Runnable {
    private static final String TAG = "SurveillanceIPC";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);
    
    private final int port;
    private ServerSocket serverSocket;
    private volatile boolean running = true;
    
    // SOTA FIX: Thread Pool to prevent server clogging
    // IPC server typically has fewer connections than HTTP, so 8 threads is sufficient
    private final ExecutorService threadPool = Executors.newFixedThreadPool(8);

    public SurveillanceIpcServer(int port) {
        this.port = port;
    }
    
    @Override
    public void run() {
        try {
            serverSocket = new ServerSocket(port, 50, java.net.InetAddress.getByName("127.0.0.1"));
            logger.info("Surveillance IPC server listening on 127.0.0.1:" + port);
            
            while (running) {
                try {
                    Socket client = serverSocket.accept();
                    // SOTA FIX: Offload to Thread Pool instead of spawning new thread
                    threadPool.execute(() -> handleClient(client));
                } catch (Exception e) {
                    if (running) {
                        logger.error("Error accepting client", e);
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Failed to start IPC server", e);
        }
    }
    
    private void handleClient(Socket client) {
        try {
            // Read timeout: a client that opens the socket and never sends a
            // newline would otherwise pin one of the worker threads forever.
            // Eight half-open sockets (a buggy/restarting daemon under load)
            // exhaust the pool and the IPC server stops accepting commands.
            // 5s is plenty for localhost JSON traffic.
            client.setSoTimeout(5000);

            // Defence in depth: the IPC token is world-readable (644) by design,
            // so verify the connecting process's UID before handling any command.
            // GET_VEHICLE_DATA (telemetry), UPDATE_GPS (GPS spoofing) and
            // INSTALL_UPDATE are sensitive — only root/system/shell-daemon and the
            // BladeWatch app may reach them. Reject everything else.
            int peerUid = PeerCredentials.resolvePeerUid(client);
            if (!PeerCredentials.isTrusted(peerUid)) {
                logger.warn("Surveillance IPC rejected untrusted peer uid=" + peerUid
                        + " from " + client.getRemoteSocketAddress());
                try { client.close(); } catch (Exception ignored) {}
                return;
            }

            BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()));
            PrintWriter out = new PrintWriter(client.getOutputStream(), true);

            String line = in.readLine();
            if (line != null) {
                JSONObject request = new JSONObject(line);
                JSONObject response = handleCommand(request);
                out.println(response.toString());
            }

            client.close();
        } catch (java.net.SocketTimeoutException ste) {
            logger.warn("IPC client read timeout — closing socket");
            try { client.close(); } catch (Exception ignored) {}
        } catch (Exception e) {
            logger.error("Error handling client", e);
            try { client.close(); } catch (Exception ignored) {}
        }
    }
    
    private JSONObject handleCommand(JSONObject request) {
        JSONObject response = new JSONObject();

        // Require shared-secret token on every request
        if (!IpcTokenManager.isValid(request.optString("token", ""))) {
            try {
                response.put("success", false);
                response.put("error", "Unauthorized");
            } catch (Exception ignored) {}
            return response;
        }

        try {
            String command = request.optString("command", "");
            
            switch (command) {
                // ==================== APP UI COMMANDS ====================
                // These commands are sent by the app UI for configuration
                
                case "ENABLE_SURVEILLANCE":
                    // Persist preference only — surveillance will auto-start on next ACC OFF
                    net.bladewatch.app.config.UnifiedConfigManager.setSurveillanceEnabled(true);
                    logger.info("Surveillance preference set to ENABLED (will activate on ACC OFF)");
                    response.put("success", true);
                    response.put("enabled", true);
                    break;
                    
                case "DISABLE_SURVEILLANCE":
                    // Persist preference and stop if currently running
                    net.bladewatch.app.config.UnifiedConfigManager.setSurveillanceEnabled(false);
                    CameraDaemon.disableSurveillance();
                    logger.info("Surveillance preference set to DISABLED and stopped");
                    response.put("success", true);
                    response.put("enabled", false);
                    break;
                
                case "GET_CONFIG":
                    response.put("success", true);
                    response.put("config", getDefaultConfig());
                    break;
                    
                case "SET_CONFIG":
                    JSONObject config = request.optJSONObject("config");
                    if (config != null) {
                        applyConfig(config);
                    }
                    response.put("success", true);
                    response.put("message", "Config updated");
                    break;
                    
                case "GET_STATUS":
                    response.put("success", true);
                    response.put("status", getSurveillanceStatus());
                    break;
                    
                // ==================== VEHICLE DATA COMMANDS ====================
                // These commands provide access to BYD vehicle telemetry
                
                case "GET_VEHICLE_DATA":
                    response.put("success", true);
                    response.put("data", getVehicleData());
                    break;
                    
                case "GET_BATTERY_VOLTAGE":
                    response.put("success", true);
                    response.put("data", getBatteryVoltageData());
                    break;
                    
                case "GET_BATTERY_POWER":
                    response.put("success", true);
                    response.put("data", getBatteryPowerData());
                    break;
                    
                case "GET_BATTERY_SOC":
                    response.put("success", true);
                    response.put("data", getBatterySocData());
                    break;
                    
                case "GET_CHARGING_STATE":
                    response.put("success", true);
                    response.put("data", getChargingStateData());
                    break;
                    
                case "GET_CHARGING_POWER":
                    response.put("success", true);
                    response.put("data", getChargingPowerData());
                    break;
                    
                case "GET_DRIVING_RANGE":
                    response.put("success", true);
                    response.put("data", getDrivingRangeData());
                    break;
                    
                case "GET_ROI":
                    response.put("success", true);
                    response.put("roi", getRoiData());
                    break;
                    
                case "SET_ROI":
                    JSONObject roiData = request.optJSONObject("roi");
                    if (roiData != null) {
                        applyRoi(roiData);
                    }
                    response.put("success", true);
                    response.put("message", "ROI updated");
                    break;
                
                // ==================== SAFE LOCATION COMMANDS ====================
                
                case "GET_SAFE_LOCATIONS":
                    response = net.bladewatch.app.surveillance.SafeLocationManager.getInstance().getStatusJson();
                    response.put("success", true);
                    break;
                    
                case "ADD_SAFE_LOCATION": {
                    JSONObject zoneData = request.optJSONObject("zone");
                    if (zoneData != null) {
                        net.bladewatch.app.surveillance.SafeLocation zone =
                            net.bladewatch.app.surveillance.SafeLocationManager.getInstance().addZone(
                                zoneData.optString("name", "Unnamed"),
                                zoneData.optDouble("lat", 0),
                                zoneData.optDouble("lng", 0),
                                zoneData.optInt("radiusM", 150));
                        response.put("success", zone != null);
                        if (zone != null) response.put("zone", zone.toJson());
                        else response.put("error", Messages.get("errors.zones_max_reached", 10));
                    } else {
                        response.put("success", false);
                        response.put("error", Messages.get("errors.zones_missing_data"));
                    }
                    break;
                }
                    
                case "UPDATE_SAFE_LOCATION": {
                    String zoneId = request.optString("id", null);
                    JSONObject updates = request.optJSONObject("updates");
                    if (zoneId != null && updates != null) {
                        boolean updated = net.bladewatch.app.surveillance.SafeLocationManager.getInstance()
                            .updateZone(zoneId, updates);
                        response.put("success", updated);
                    } else {
                        response.put("success", false);
                        response.put("error", Messages.get("errors.missing_id_or_updates"));
                    }
                    break;
                }
                    
                case "DELETE_SAFE_LOCATION": {
                    String zoneId = request.optString("id", null);
                    if (zoneId != null) {
                        boolean removed = net.bladewatch.app.surveillance.SafeLocationManager.getInstance()
                            .removeZone(zoneId);
                        response.put("success", removed);
                    } else {
                        response.put("success", false);
                        response.put("error", Messages.get("errors.missing_id"));
                    }
                    break;
                }
                    
                case "TOGGLE_SAFE_LOCATIONS": {
                    boolean enabled = request.optBoolean("enabled", true);
                    net.bladewatch.app.surveillance.SafeLocationManager.getInstance().setFeatureEnabled(enabled);
                    response.put("success", true);
                    response.put("enabled", enabled);
                    break;
                }
                
                // ==================== GPS SIDECAR COMMANDS ====================
                // GPS data from LocationSidecarService (app writes via IPC, daemon writes to file)
                
                case "UPDATE_GPS":
                    handleGpsUpdate(request);
                    response.put("success", true);
                    break;

                // ==================== TELEMETRY OVERLAY COMMANDS ====================

                case "SET_TELEMETRY_OVERLAY": {
                    boolean enabled = request.optBoolean("enabled", false);
                    JSONObject overlayConfig = new JSONObject();
                    overlayConfig.put("enabled", enabled);
                    net.bladewatch.app.config.UnifiedConfigManager.setTelemetryOverlay(overlayConfig);
                    // Notify pipeline
                    net.bladewatch.app.surveillance.GpuSurveillancePipeline pipeline = CameraDaemon.getGpuPipeline();
                    if (pipeline != null) {
                        pipeline.setOverlayEnabled(enabled);
                    }
                    response.put("success", true);
                    response.put("enabled", enabled);
                    break;
                }

                case "GET_TELEMETRY_OVERLAY": {
                    JSONObject overlayConfig = net.bladewatch.app.config.UnifiedConfigManager.getTelemetryOverlay();
                    response.put("success", true);
                    response.put("enabled", overlayConfig.optBoolean("enabled", false));
                    break;
                }

                // ==================== UPDATE COMMANDS ====================

                case "CHECK_UPDATE":
                    handleCheckUpdate(response);
                    break;

                case "INSTALL_UPDATE":
                    handleInstallUpdate(request, response);
                    break;

                default:
                    logger.warn("Unknown IPC command: " + command);
                    response.put("success", false);
                    response.put("error", Messages.get("errors.unknown_command", command));
            }
        } catch (Exception e) {
            logger.error("Error handling IPC command", e);
            try {
                response.put("success", false);
                response.put("error", e.getMessage());
            } catch (Exception ignored) {}
        }
        
        return response;
    }
    
    /**
     * Apply configuration changes to surveillance system.
     * Updates both the running engine (if available) AND persists to config file.
     * Config is ALWAYS persisted even if surveillance is not running.
     *
     * synchronized: the IPC server runs an 8-thread pool; without serialization,
     * two concurrent SET_CONFIG requests could each load + mutate + save their
     * own snapshot, losing the other's update.
     * Lock granularity is class-wide (CONFIG_LOCK) because the persisted file
     * is shared state, not instance state.
     */
    private static final Object CONFIG_LOCK = new Object();

    private void applyConfig(JSONObject config) {
        synchronized (CONFIG_LOCK) { applyConfigLocked(config); }
    }

    private void applyConfigLocked(JSONObject config) {
        try {
            net.bladewatch.app.surveillance.GpuSurveillancePipeline pipeline =
                CameraDaemon.getGpuPipeline();
            
            // Sentry may be null if surveillance is not running - that's OK
            net.bladewatch.app.surveillance.SurveillanceEngineGpu sentry = null;
            if (pipeline != null) {
                sentry = pipeline.getSentry();
            }
            
            // Get or create SurveillanceConfig for persistence
            // Even if sentry is null, we still want to persist the config
            net.bladewatch.app.surveillance.SurveillanceConfig sentryConfig = null;
            if (sentry != null) {
                sentryConfig = sentry.getConfig();
            }
            if (sentryConfig == null) {
                // Load from file or create new
                try {
                    net.bladewatch.app.surveillance.SurveillanceConfigManager configManager =
                        new net.bladewatch.app.surveillance.SurveillanceConfigManager();
                    if (configManager.configExists()) {
                        sentryConfig = configManager.loadConfig();
                        logger.info("Loaded existing config from file for update");
                    } else {
                        sentryConfig = new net.bladewatch.app.surveillance.SurveillanceConfig();
                        logger.info("Created new config for persistence");
                    }
                } catch (Exception e) {
                    sentryConfig = new net.bladewatch.app.surveillance.SurveillanceConfig();
                    logger.error("Failed to load config, using defaults", e);
                }
            }
            
            boolean configChanged = false;
            
            // Handle surveillance storage type change (INTERNAL or SD_CARD)
            if (config.has("surveillanceStorageType")) {
                String typeStr = config.getString("surveillanceStorageType").toUpperCase();
                net.bladewatch.app.storage.StorageManager storageManager =
                    net.bladewatch.app.storage.StorageManager.getInstance();
                net.bladewatch.app.storage.StorageManager.StorageType type =
                    "SD_CARD".equals(typeStr) ?
                        net.bladewatch.app.storage.StorageManager.StorageType.SD_CARD :
                        net.bladewatch.app.storage.StorageManager.StorageType.INTERNAL;
                boolean success = storageManager.setSurveillanceStorageType(type);
                if (success) {
                    logger.info("Surveillance storage type set to " + type + " via IPC");
                    // Update sentry's event output directory if running
                    if (sentry != null) {
                        sentry.setEventOutputDir(storageManager.getSurveillanceDir());
                        logger.info("Updated sentry output dir: " + storageManager.getSurveillanceDir().getAbsolutePath());
                    }
                } else {
                    logger.warn("Failed to set surveillance storage to SD_CARD - not available");
                }
            }
            
            // Handle surveillance storage limit change
            if (config.has("surveillanceLimitMb")) {
                long limitMb = config.getLong("surveillanceLimitMb");
                net.bladewatch.app.storage.StorageManager storageManager =
                    net.bladewatch.app.storage.StorageManager.getInstance();
                storageManager.setSurveillanceLimitMb(limitMb);
                logger.info("Surveillance limit set to " + storageManager.getSurveillanceLimitMb() + " MB via IPC");
                // Trigger async cleanup
                new Thread(() -> storageManager.ensureSurveillanceSpace(0), "SurvLimitCleanup").start();
            }
            
            // Check if enabled state changed
            if (config.has("enabled")) {
                boolean enabled = config.getBoolean("enabled");
                // Persist to unified config so ACC OFF respects user preference
                net.bladewatch.app.config.UnifiedConfigManager.setSurveillanceEnabled(enabled);
                if (enabled) {
                    // RACE CONDITION FIX: Only enable surveillance if ACC is actually OFF.
                    // AccSentryDaemon's retry loop may send this IPC after ACC turned ON.
                    if (!net.bladewatch.app.monitor.AccMonitor.isAccOn()) {
                        CameraDaemon.enableSurveillance();
                        logger.info("Surveillance enabled via IPC");
                    } else {
                        logger.info("Surveillance preference saved via IPC — but ACC is ON, not activating");
                    }
                } else {
                    CameraDaemon.disableSurveillance();
                    logger.info("Surveillance disabled via IPC");
                }
            }
            
            // Stop surveillance without persisting preference (battery protection, session stop)
            if (config.has("stopSurveillance") && config.getBoolean("stopSurveillance")) {
                CameraDaemon.disableSurveillance();
                logger.info("Surveillance stopped via IPC (preference preserved)");
            }
            
            // Handle ACC state if provided
            if (config.has("accOff")) {
                boolean accOff = config.getBoolean("accOff");
                CameraDaemon.onAccStateChanged(accOff);
                logger.info("ACC state changed via IPC: " + (accOff ? "OFF" : "ON"));
            }
            
            // Handle gear state if provided
            if (config.has("gear")) {
                int gear = config.getInt("gear");
                CameraDaemon.onGearChanged(gear);
                logger.info("Gear changed via IPC: " + net.bladewatch.app.recording.RecordingModeManager.gearToString(gear));
            }
            
            // Handle sensitivity setting (maps to minObjectSize)
            if (config.has("sensitivity")) {
                String sensitivity = config.optString("sensitivity", "MEDIUM").toUpperCase();
                float minSize;
                switch (sensitivity) {
                    case "LOW":
                        minSize = 0.02f;  // 2% - detect distant objects
                        break;
                    case "HIGH":
                        minSize = 0.15f;  // 15% - only close objects
                        break;
                    case "MEDIUM":
                    default:
                        minSize = 0.08f;  // 8% - balanced
                        break;
                }
                float confidence = (float) config.optDouble("aiConfidence", sentryConfig.getAiConfidence());
                boolean detectPerson = config.optBoolean("detectPerson", sentryConfig.isDetectPerson());
                boolean detectCar = config.optBoolean("detectCar", sentryConfig.isDetectCar());
                boolean detectBike = config.optBoolean("detectBike", sentryConfig.isDetectBike());
                
                // Apply to running engine if available
                if (sentry != null) {
                    sentry.setObjectFilters(minSize, confidence, detectPerson, detectCar, detectBike);
                }
                
                // Update config object for persistence
                sentryConfig.setMinObjectSize(minSize);
                sentryConfig.setAiConfidence(confidence);
                sentryConfig.setDetectPerson(detectPerson);
                sentryConfig.setDetectCar(detectCar);
                sentryConfig.setDetectBike(detectBike);
                configChanged = true;
                
                logger.info("Sensitivity set to " + sensitivity + " (minObjectSize=" + minSize + ")");
            }
            
            // Apply object detection filters (direct minObjectSize override)
            if (config.has("minObjectSize") || config.has("aiConfidence") || 
                config.has("detectPerson") || config.has("detectCar") || config.has("detectBike")) {
                
                float minSize = (float) config.optDouble("minObjectSize", sentryConfig.getMinObjectSize());
                float confidence = (float) config.optDouble("aiConfidence", sentryConfig.getAiConfidence());
                boolean detectPerson = config.optBoolean("detectPerson", sentryConfig.isDetectPerson());
                boolean detectCar = config.optBoolean("detectCar", sentryConfig.isDetectCar());
                boolean detectBike = config.optBoolean("detectBike", sentryConfig.isDetectBike());
                
                // Apply to running engine if available
                if (sentry != null) {
                    sentry.setObjectFilters(minSize, confidence, detectPerson, detectCar, detectBike);
                }
                
                // Update config object for persistence
                sentryConfig.setMinObjectSize(minSize);
                sentryConfig.setAiConfidence(confidence);
                sentryConfig.setDetectPerson(detectPerson);
                sentryConfig.setDetectCar(detectCar);
                sentryConfig.setDetectBike(detectBike);
                configChanged = true;
                
                logger.info("Object filters applied (sentry " + (sentry != null ? "running" : "not running") + ")");
            }
            
            // Handle pre/post record seconds
            if (config.has("preRecordSeconds") || config.has("preEventBufferSeconds")) {
                int preRecordSeconds = config.has("preRecordSeconds") 
                    ? config.optInt("preRecordSeconds", 5)
                    : config.optInt("preEventBufferSeconds", 5);
                
                // Apply to running engine if available
                if (sentry != null) {
                    sentry.setPreRecordSeconds(preRecordSeconds);
                }
                
                sentryConfig.setPreRecordSeconds(preRecordSeconds);
                configChanged = true;
                logger.info("Pre-record seconds set to: " + preRecordSeconds);
            }
            
            if (config.has("postRecordSeconds") || config.has("postEventBufferSeconds")) {
                int postRecordSeconds = config.has("postRecordSeconds")
                    ? config.optInt("postRecordSeconds", 10)
                    : config.optInt("postEventBufferSeconds", 10);
                
                // Apply to running engine if available
                if (sentry != null) {
                    sentry.setPostRecordSeconds(postRecordSeconds);
                }
                
                sentryConfig.setPostRecordSeconds(postRecordSeconds);
                configChanged = true;
                logger.info("Post-record seconds set to: " + postRecordSeconds);
            }
            
            // Handle recording quality / legacy bitrate setting.
            // Prefer the canonical `recordingQuality` (ECONOMY..MAX); accept the
            // legacy `bitrate` (LOW/MEDIUM/HIGH) only as a fallback.
            String tierIn = null;
            if (config.has("recordingQuality")) {
                tierIn = config.optString("recordingQuality", "").toUpperCase();
            } else if (config.has("bitrate")) {
                String legacy = config.optString("bitrate", "").toUpperCase();
                switch (legacy) {
                    case "LOW":    tierIn = "ECONOMY"; break;
                    case "MEDIUM": tierIn = "STANDARD"; break;
                    case "HIGH":   tierIn = "HIGH"; break;
                    default:       tierIn = null; break;
                }
            }
            if (tierIn != null && !tierIn.isEmpty()) {
                CameraDaemon.setRecordingQuality(tierIn);
                HttpServer.setRecordingBitrateStatic(tierIn);  // legacy alias setter; takes any string
                logger.info("Recording quality set to: " + tierIn);
            }
            
            // Handle codec setting
            if (config.has("codec")) {
                String codec = config.optString("codec", "H264").toUpperCase();
                if (codec.equals("H264") || codec.equals("H265")) {
                    CameraDaemon.setRecordingCodec(codec);
                    // Also update HttpServer's static setting for web UI sync
                    HttpServer.setRecordingCodecStatic(codec);
                    logger.info("Recording codec set to: " + codec);
                }
            }
            
            // Persist recording settings to file so web UI can read them
            if (config.has("recordingQuality") || config.has("bitrate") || config.has("codec")) {
                HttpServer.persistSettingsStatic();
            }
            
            // SOTA: Handle flash immunity setting (0=OFF, 1=LOW, 2=MEDIUM, 3=HIGH)
            if (config.has("flashImmunity")) {
                int flashImmunity = config.optInt("flashImmunity", 2);
                
                // Apply to running engine if available
                if (sentry != null) {
                    sentry.setFlashImmunity(flashImmunity);
                }
                
                sentryConfig.setFlashImmunity(flashImmunity);
                configChanged = true;
                logger.info("Flash immunity set to: " + flashImmunity);
            }
            
            // SOTA: Handle distance preset (1-5 slider value)
            // Distance ONLY controls minObjectSize (AI detection range)
            // Block size is LOCKED at 32. Motion sensitivity is handled separately.
            if (config.has("distance")) {
                int distance = config.optInt("distance", 3);
                
                // Map slider value to minObjectSize for AI detection
                // 1 = Close (~3m, 25%), 2 = Near (~5m, 18%), 3 = Medium (~8m, 12%), 
                // 4 = Far (~10m, 8%), 5 = Very Far (~15m, 5%)
                float minObjectSize;
                String distanceLabel;
                switch (distance) {
                    case 1: minObjectSize = 0.25f; distanceLabel = "CLOSE (~3m)"; break;
                    case 2: minObjectSize = 0.18f; distanceLabel = "NEAR (~5m)"; break;
                    case 3: minObjectSize = 0.12f; distanceLabel = "MEDIUM (~8m)"; break;
                    case 4: minObjectSize = 0.08f; distanceLabel = "FAR (~10m)"; break;
                    case 5: minObjectSize = 0.05f; distanceLabel = "VERY_FAR (~15m)"; break;
                    default: minObjectSize = 0.12f; distanceLabel = "MEDIUM (~8m)"; break;
                }
                
                // Only update minObjectSize - don't touch motion sensitivity settings
                sentryConfig.setMinObjectSize(minObjectSize);
                configChanged = true;
                
                // Apply to running engine if available
                if (sentry != null) {
                    float confidence = sentryConfig.getAiConfidence();
                    boolean detectPerson = sentryConfig.isDetectPerson();
                    boolean detectCar = sentryConfig.isDetectCar();
                    boolean detectBike = sentryConfig.isDetectBike();
                    sentry.setObjectFilters(minObjectSize, confidence, detectPerson, detectCar, detectBike);
                }
                
                logger.info(String.format("Distance set via IPC: %s (minObjectSize=%.0f%%)",
                    distanceLabel, minObjectSize * 100));
            }
            
            // SOTA: Handle motion sensitivity slider (1-5) - SEPARATE from distance
            // Controls requiredBlocks and densityThreshold for motion detection
            // Block size is LOCKED at 32
            if (config.has("sensitivity") && config.optInt("sensitivity", -1) >= 1 && config.optInt("sensitivity", -1) <= 5) {
                int sensitivityLevel = config.optInt("sensitivity", 3);
                
                // Map slider value to motion detection thresholds
                // Production table with block size locked at 32:
                // 1=Strict (req=4, density=48), 2=Conservative (req=3, density=40), 
                // 3=Default (req=2, density=32), 4=Sensitive (req=2, density=16), 5=Aggressive (req=1, density=12)
                int requiredBlocks;
                
                switch (sensitivityLevel) {
                    case 1:  // Strict - large objects only
                        requiredBlocks = 4;
                        break;
                    case 2:  // Conservative - solid objects
                        requiredBlocks = 3;
                        break;
                    case 3:  // Default - balanced
                        requiredBlocks = 2;
                        break;
                    case 4:  // Sensitive - catches motion quickly
                        requiredBlocks = 2;
                        break;
                    case 5:  // Aggressive - any motion
                        requiredBlocks = 1;
                        break;
                    default:
                        requiredBlocks = 2;
                        break;
                }
                
                // Convert slider 1-5 to percentage 20-100 for unified sensitivity
                int sensitivityPercent = sensitivityLevel * 20;
                
                // Update config for persistence
                sentryConfig.setRequiredBlocks(requiredBlocks);
                sentryConfig.setUnifiedSensitivity(sensitivityPercent);
                configChanged = true;
                
                // Apply to running engine if available
                if (sentry != null) {
                    sentry.setUnifiedSensitivity(sensitivityPercent);
                    sentry.setRequiredActiveBlocks(requiredBlocks);
                }
                
                logger.info(String.format("Motion sensitivity set to level %d (%d%%, alarm=%d blocks)",
                    sensitivityLevel, sensitivityPercent, requiredBlocks));
            }
            
            // Handle block sensitivity (grid motion detection) - skip if distance was set
            if (config.has("blockSensitivity") && !config.has("distance")) {
                float blockSens = (float) config.optDouble("blockSensitivity", 0.04);
                
                // Apply to running engine if available
                if (sentry != null) {
                    sentry.setBlockSensitivity(blockSens);
                }
                
                sentryConfig.setSensitivity(blockSens);
                configChanged = true;
                logger.info("Block sensitivity set to: " + blockSens);
            }
            
            // Handle required active blocks - skip if distance was set
            if (config.has("requiredActiveBlocks") && !config.has("distance")) {
                int reqBlocks = config.optInt("requiredActiveBlocks", 2);
                
                // Apply to running engine if available
                if (sentry != null) {
                    sentry.setRequiredActiveBlocks(reqBlocks);
                }
                
                sentryConfig.setRequiredBlocks(reqBlocks);
                configChanged = true;
                logger.info("Required active blocks set to: " + reqBlocks);
            }
            
            // Apply updated config to engine (if running) and ALWAYS persist to file
            if (configChanged) {
                // Apply config to running engine (syncs internal state like preRecordMs)
                if (sentry != null) {
                    sentry.setConfig(sentryConfig);
                }
                
                // ALWAYS persist to file - this is critical for config to survive restarts
                try {
                    net.bladewatch.app.surveillance.SurveillanceConfigManager configManager =
                        new net.bladewatch.app.surveillance.SurveillanceConfigManager();
                    configManager.saveConfig(sentryConfig);
                    logger.info("Surveillance config persisted to file (sentry " + 
                        (sentry != null ? "running" : "not running") + ")");
                } catch (Exception e) {
                    logger.error("Failed to persist surveillance config", e);
                }
            }
            
            // Note: minObjectHeight filter is now applied in C++ (yolo_detector.cpp)
            // Height filter (10% of frame) is applied BEFORE NMS in native code for efficiency
            
            // ========================================================================
            // V2 Pipeline Settings
            // ========================================================================
            
            // Environment preset (outdoor/garage/street) — sets slider defaults
            if (config.has("environmentPreset")) {
                String preset = config.optString("environmentPreset", "outdoor").toLowerCase();
                sentryConfig.setEnvironmentPreset(preset);
                if (sentry != null) {
                    sentry.applyV2EnvironmentPreset(preset);
                }
                configChanged = true;
                logger.info("V2 environment preset: " + preset);
            }
            
            // Sensitivity level (1-5)
            if (config.has("sensitivityLevel")) {
                int level = config.optInt("sensitivityLevel", 3);
                sentryConfig.setSensitivityLevel(level);
                if (sentry != null) {
                    sentry.applyV2Sensitivity(level);
                }
                configChanged = true;
                logger.info("V2 sensitivity level: " + level);
            }
            
            // Detection zone (close/normal/extended)
            if (config.has("detectionZone")) {
                String zone = config.optString("detectionZone", "normal").toLowerCase();
                sentryConfig.setDetectionZone(zone);
                configChanged = true;
                logger.info("V2 detection zone: " + zone);
            }
            
            // Loitering time (seconds)
            if (config.has("loiteringTime")) {
                int seconds = config.optInt("loiteringTime", 3);
                sentryConfig.setLoiteringTimeSeconds(seconds);
                if (sentry != null) {
                    sentry.setV2LoiteringTime(seconds);
                }
                configChanged = true;
                logger.info("V2 loitering time: " + seconds + "s");
            }
            
            // Shadow filter mode (0=OFF, 1=LIGHT, 2=NORMAL, 3=AGGRESSIVE)
            if (config.has("shadowFilter")) {
                int mode = config.optInt("shadowFilter", 2);
                sentryConfig.setShadowFilterMode(mode);
                if (sentry != null) {
                    sentry.setV2ShadowFilterMode(mode);
                }
                configChanged = true;
                String[] modeNames = {"OFF", "LIGHT", "NORMAL", "AGGRESSIVE"};
                logger.info("V2 shadow filter: " + (mode >= 0 && mode <= 3 ? modeNames[mode] : "invalid"));
            }
            
            // Per-camera enable/disable
            // Quadrant mapping: Q0=front, Q1=right, Q2=rear, Q3=left
            if (config.has("cameraFront")) {
                boolean enabled = config.optBoolean("cameraFront", true);
                sentryConfig.setCameraEnabled(0, enabled);
                if (sentry != null) sentry.setV2QuadrantEnabled(0, enabled);
                configChanged = true;
            }
            if (config.has("cameraRight")) {
                boolean enabled = config.optBoolean("cameraRight", true);
                sentryConfig.setCameraEnabled(1, enabled);
                if (sentry != null) sentry.setV2QuadrantEnabled(1, enabled);
                configChanged = true;
            }
            if (config.has("cameraRear")) {
                boolean enabled = config.optBoolean("cameraRear", true);
                sentryConfig.setCameraEnabled(2, enabled);
                if (sentry != null) sentry.setV2QuadrantEnabled(2, enabled);
                configChanged = true;
            }
            if (config.has("cameraLeft")) {
                boolean enabled = config.optBoolean("cameraLeft", true);
                sentryConfig.setCameraEnabled(3, enabled);
                if (sentry != null) sentry.setV2QuadrantEnabled(3, enabled);
                configChanged = true;
            }
            
            // Developer toggles
            if (config.has("motionHeatmap")) {
                sentryConfig.setMotionHeatmapEnabled(config.optBoolean("motionHeatmap", false));
                configChanged = true;
            }
            if (config.has("filterDebugLog")) {
                boolean debugEnabled = config.optBoolean("filterDebugLog", false);
                sentryConfig.setFilterDebugLogEnabled(debugEnabled);
                if (sentry != null) {
                    sentry.setFilterDebugEnabled(debugEnabled);
                }
                configChanged = true;
            }
        } catch (Exception e) {
            logger.error("Failed to apply config", e);
        }
    }
    
    /**
     * Apply ROI configuration to surveillance system.
     */
    private void applyRoi(JSONObject roiData) {
        try {
            net.bladewatch.app.surveillance.GpuSurveillancePipeline pipeline =
                CameraDaemon.getGpuPipeline();
            
            if (pipeline == null || pipeline.getSentry() == null) {
                logger.warn("Cannot apply ROI - surveillance not initialized");
                return;
            }
            
            // Parse polygon points
            org.json.JSONArray pointsArray = roiData.optJSONArray("points");
            if (pointsArray == null || pointsArray.length() < 3) {
                // Clear ROI
                pipeline.getSentry().setRoiMask(null);
                logger.info("ROI cleared");
                return;
            }
            
            // Convert to float array
            float[][] points = new float[pointsArray.length()][2];
            for (int i = 0; i < pointsArray.length(); i++) {
                org.json.JSONArray point = pointsArray.getJSONArray(i);
                points[i][0] = (float) point.getDouble(0);
                points[i][1] = (float) point.getDouble(1);
            }
            
            // Apply to surveillance engine
            pipeline.getSentry().setRoiFromPolygon(points);
            logger.info("ROI applied with " + points.length + " vertices");
            
        } catch (Exception e) {
            logger.error("Failed to apply ROI", e);
        }
    }
    
    /**
     * Get current ROI data.
     */
    private JSONObject getRoiData() {
        JSONObject roi = new JSONObject();
        try {
            // For now, return empty - would need to store ROI points
            roi.put("enabled", false);
            roi.put("points", new org.json.JSONArray());
        } catch (Exception e) {
            logger.error("Failed to get ROI data", e);
        }
        return roi;
    }
    
    private JSONObject getDefaultConfig() throws Exception {
        JSONObject config = new JSONObject();
        
        // Read persisted preference (not runtime state) for the UI toggle
        boolean enabled = net.bladewatch.app.config.UnifiedConfigManager.isSurveillanceEnabled();
        
        config.put("enabled", enabled);
        config.put("noiseThreshold", 0.0001);
        config.put("lightThreshold", 0.4);
        config.put("aiEnabled", true);
        config.put("scheduleEnabled", false);
        config.put("recordingQuality", CameraDaemon.getRecordingQuality());
        config.put("codec", CameraDaemon.getRecordingCodec());
        
        // Get actual values from sentry config if available
        net.bladewatch.app.surveillance.GpuSurveillancePipeline pipeline =
            CameraDaemon.getGpuPipeline();
        
        net.bladewatch.app.surveillance.SurveillanceConfig sentryConfig = null;
        
        if (pipeline != null && pipeline.getSentry() != null) {
            net.bladewatch.app.surveillance.SurveillanceEngineGpu sentry = pipeline.getSentry();
            sentryConfig = sentry.getConfig();
        }
        
        // If sentry not running, try to load from file
        if (sentryConfig == null) {
            try {
                net.bladewatch.app.surveillance.SurveillanceConfigManager configManager =
                    new net.bladewatch.app.surveillance.SurveillanceConfigManager();
                if (configManager.configExists()) {
                    sentryConfig = configManager.loadConfig();
                    logger.info("Loaded config from file for GET_CONFIG (sentry not running)");
                }
            } catch (Exception e) {
                logger.error("Failed to load config from file", e);
            }
        }
        
        if (sentryConfig != null) {
            // Read from config object
            config.put("minObjectSize", sentryConfig.getMinObjectSize());
            config.put("aiConfidence", sentryConfig.getAiConfidence());
            config.put("detectPerson", sentryConfig.isDetectPerson());
            config.put("detectCar", sentryConfig.isDetectCar());
            config.put("detectBike", sentryConfig.isDetectBike());
            config.put("flashImmunity", sentryConfig.getFlashImmunity());
            config.put("preEventBufferSeconds", sentryConfig.getPreRecordSeconds());
            config.put("postEventBufferSeconds", sentryConfig.getPostRecordSeconds());
            config.put("blockSensitivity", sentryConfig.getSensitivity());
            config.put("requiredActiveBlocks", sentryConfig.getRequiredBlocks());
            
            // SOTA: Return sensitivity as slider value (1-5) based on requiredBlocks
            // Maps: 1=Strict(req=4), 2=Conservative(req=3), 3=Default(req=2), 4=Sensitive(req=2,density=16), 5=Aggressive(req=1)
            int reqBlocks = sentryConfig.getRequiredBlocks();
            int sensitivityLevel;
            if (reqBlocks >= 4) {
                sensitivityLevel = 1;  // Strict
            } else if (reqBlocks == 3) {
                sensitivityLevel = 2;  // Conservative
            } else if (reqBlocks == 2) {
                sensitivityLevel = 3;  // Default (could be 3 or 4, default to 3)
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
            // Defaults when no config available
            config.put("sensitivity", 3);  // Default (slider value 1-5)
            config.put("distance", 3);     // ~8m (slider value 1-5)
            config.put("minObjectSize", 0.08);
            config.put("aiConfidence", 0.6);
            config.put("detectPerson", true);
            config.put("detectCar", true);
            config.put("detectBike", true);
            config.put("flashImmunity", 2);  // Default MEDIUM
            config.put("preEventBufferSeconds", 5);
            config.put("postEventBufferSeconds", 10);
            config.put("blockSensitivity", 0.04);
            config.put("requiredActiveBlocks", 2);
        }
        
        // SOTA: Add lastModified timestamp for web UI sync detection
        config.put("lastModified", net.bladewatch.app.config.UnifiedConfigManager.getLastModified());
        
        return config;
    }
    
    private JSONObject getSurveillanceStatus() throws Exception {
        JSONObject status = new JSONObject();
        
        // Read from persisted config (not in-memory flag)
        boolean enabled = net.bladewatch.app.config.UnifiedConfigManager.isSurveillanceEnabled();
        boolean active = CameraDaemon.isSurveillanceActive();
        
        status.put("enabled", enabled);
        status.put("active", active);
        status.put("recording", false);
        
        return status;
    }
    
    // ==================== VEHICLE DATA HELPERS ====================
    
    private JSONObject getVehicleData() throws Exception {
        net.bladewatch.app.monitor.VehicleDataMonitor monitor =
            net.bladewatch.app.monitor.VehicleDataMonitor.getInstance();
        return monitor.getAllData();
    }
    
    private JSONObject getBatteryVoltageData() throws Exception {
        net.bladewatch.app.monitor.VehicleDataMonitor monitor =
            net.bladewatch.app.monitor.VehicleDataMonitor.getInstance();
        net.bladewatch.app.monitor.BatteryVoltageData data = monitor.getBatteryVoltage();
        
        if (data == null) throw new Exception("Battery voltage data not available");
        
        JSONObject json = new JSONObject();
        json.put("level", data.level);
        json.put("levelName", data.levelName);
        json.put("isWarning", data.isWarning);
        json.put("timestamp", data.timestamp);
        return json;
    }
    
    private JSONObject getBatteryPowerData() throws Exception {
        net.bladewatch.app.monitor.VehicleDataMonitor monitor =
            net.bladewatch.app.monitor.VehicleDataMonitor.getInstance();
        net.bladewatch.app.monitor.BatteryPowerData data = monitor.getBatteryPower();
        
        if (data == null) throw new Exception("Battery power data not available");
        
        JSONObject json = new JSONObject();
        json.put("voltageVolts", data.voltageVolts);
        json.put("isWarning", data.isWarning);
        json.put("isCritical", data.isCritical);
        json.put("healthStatus", data.getHealthStatus());
        json.put("timestamp", data.timestamp);
        return json;
    }
    
    private JSONObject getBatterySocData() throws Exception {
        net.bladewatch.app.monitor.VehicleDataMonitor monitor =
            net.bladewatch.app.monitor.VehicleDataMonitor.getInstance();
        net.bladewatch.app.monitor.BatterySocData data = monitor.getBatterySoc();
        
        if (data == null) throw new Exception("Battery SOC data not available");
        
        JSONObject json = new JSONObject();
        json.put("socPercent", data.socPercent);
        json.put("isLow", data.isLow);
        json.put("isCritical", data.isCritical);
        json.put("status", data.getStatus());
        json.put("timestamp", data.timestamp);
        return json;
    }
    
    private JSONObject getChargingStateData() throws Exception {
        net.bladewatch.app.monitor.VehicleDataMonitor monitor =
            net.bladewatch.app.monitor.VehicleDataMonitor.getInstance();
        net.bladewatch.app.monitor.ChargingStateData data = monitor.getChargingState();
        
        if (data == null) throw new Exception("Charging state data not available");
        
        JSONObject json = new JSONObject();
        json.put("stateCode", data.stateCode);
        json.put("stateName", data.stateName);
        json.put("status", data.status.name());
        json.put("isError", data.isError);
        json.put("errorType", data.errorType);
        json.put("chargingPowerKW", data.chargingPowerKW);
        json.put("isDischarging", data.isDischarging);
        json.put("timestamp", data.timestamp);
        return json;
    }
    
    private JSONObject getChargingPowerData() throws Exception {
        net.bladewatch.app.monitor.VehicleDataMonitor monitor =
            net.bladewatch.app.monitor.VehicleDataMonitor.getInstance();
        net.bladewatch.app.monitor.ChargingStateData data = monitor.getChargingState();
        
        if (data == null) throw new Exception("Charging power data not available");
        
        JSONObject json = new JSONObject();
        json.put("chargingPowerKW", data.chargingPowerKW);
        json.put("isDischarging", data.isDischarging);
        json.put("timestamp", data.timestamp);
        return json;
    }
    
    private JSONObject getDrivingRangeData() throws Exception {
        net.bladewatch.app.monitor.VehicleDataMonitor monitor =
            net.bladewatch.app.monitor.VehicleDataMonitor.getInstance();
        net.bladewatch.app.monitor.DrivingRangeData data = monitor.getDrivingRange();
        
        if (data == null) throw new Exception("Driving range data not available");
        
        JSONObject json = new JSONObject();
        json.put("elecRangeKm", data.elecRangeKm);
        json.put("fuelRangeKm", data.fuelRangeKm);
        json.put("totalRangeKm", data.totalRangeKm);
        json.put("isLow", data.isLow);
        json.put("isCritical", data.isCritical);
        json.put("status", data.getStatus());
        json.put("isPureEV", data.isPureEV());
        if (data.hasFuelPercent()) {
            json.put("fuelPercent", data.fuelPercent);
        }
        json.put("timestamp", data.timestamp);
        return json;
    }
    
    // ==================== GPS SIDECAR HANDLER ====================
    
    /**
     * Handle GPS update from LocationSidecarService.
     * Directly updates GpsMonitor's cached values - no file needed.
     */
    private void handleGpsUpdate(JSONObject request) {
        try {
            double lat = request.optDouble("lat", 0.0);
            double lng = request.optDouble("lng", 0.0);
            float speed = (float) request.optDouble("speed", 0.0);
            float heading = (float) request.optDouble("heading", 0.0);
            float accuracy = (float) request.optDouble("accuracy", 0.0);
            double altitude = request.optDouble("altitude", 0.0);
            long time = request.optLong("time", System.currentTimeMillis());
            
            // Directly update GpsMonitor
            net.bladewatch.app.monitor.GpsMonitor.getInstance()
                .updateFromIpc(lat, lng, speed, heading, accuracy, time, altitude);
            
        } catch (Exception e) {
            logger.error("Failed to update GPS", e);
        }
    }

    // ==================== UPDATE HANDLERS ====================
    // Mirror UpdateApiHandler.handleCheck/handleInstall but invoked over IPC
    // (port 19877) so other-process daemons can drive an update without going
    // through the loopback-trusted HTTP path.

    private void handleCheckUpdate(JSONObject response) throws Exception {
        android.content.Context ctx = CameraDaemon.getAppContext();
        if (ctx == null) {
            response.put("success", false);
            response.put("error", "App context not ready");
            return;
        }
        net.bladewatch.app.updater.AppUpdater updater =
                new net.bladewatch.app.updater.AppUpdater(ctx);
        final Object lock = new Object();
        final boolean[] done = {false};
        final JSONObject[] resultRef = {null};
        updater.checkForUpdate(new net.bladewatch.app.updater.AppUpdater.UpdateCallback() {
            @Override public void onUpdateAvailable(String currentVersion, String newVersion, String releaseNotes) {
                JSONObject r = new JSONObject();
                try {
                    r.put("available", true);
                    r.put("currentVersion", currentVersion);
                    r.put("remoteVersion", newVersion);
                    r.put("releaseNotes", releaseNotes != null ? releaseNotes : "");
                } catch (Exception ignored) {}
                resultRef[0] = r;
                synchronized (lock) { done[0] = true; lock.notify(); }
            }
            @Override public void onNoUpdate(String currentVersion) {
                JSONObject r = new JSONObject();
                try {
                    r.put("available", false);
                    r.put("currentVersion", currentVersion);
                    r.put("remoteVersion", currentVersion);
                } catch (Exception ignored) {}
                resultRef[0] = r;
                synchronized (lock) { done[0] = true; lock.notify(); }
            }
            @Override public void onError(String error) {
                JSONObject r = new JSONObject();
                try {
                    r.put("available", false);
                    r.put("error", error != null ? error : "unknown");
                    r.put("currentVersion", net.bladewatch.app.updater.AppUpdater.getDisplayVersionFromFile());
                } catch (Exception ignored) {}
                resultRef[0] = r;
                synchronized (lock) { done[0] = true; lock.notify(); }
            }
        });
        try {
            synchronized (lock) {
                if (!done[0]) lock.wait(12_000);
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        if (resultRef[0] == null) {
            response.put("success", false);
            response.put("error", "Update check timed out");
            return;
        }
        // Merge result fields into response
        java.util.Iterator<String> keys = resultRef[0].keys();
        while (keys.hasNext()) {
            String k = keys.next();
            response.put(k, resultRef[0].opt(k));
        }
        // success reflects whether the check actually got a verdict — a
        // result that came back via onError carries an "error" field, so
        // surface that as success=false instead of leaving callers to
        // guess from a separate key.
        response.put("success", !resultRef[0].has("error"));
    }

    /**
     * Two-stage install:
     *   1. Sync check to populate latestDownloadUrl (so /install can't be used
     *      as a blind download trigger, mirroring UpdateApiHandler).
     *   2. Kick off downloadAndInstall on a background thread. The IPC reply
     *      returns immediately because the daemon dies mid-install.
     *
     * Progress reporting reuses /data/local/tmp/bladewatch_update_progress.json
     * via UpdateApiHandler-style writes so the webapp's polling path serves both.
     */
    private void handleInstallUpdate(JSONObject request, JSONObject response) throws Exception {
        android.content.Context ctx = CameraDaemon.getAppContext();
        if (ctx == null) {
            response.put("success", false);
            response.put("error", "App context not ready");
            return;
        }

        // Public-mode block mirrors UpdateApiHandler.handleInstall — the bot
        // is already owner-paired but this keeps the policy uniform.
        if (CameraDaemon.isPublicMode()) {
            response.put("success", false);
            response.put("error", "Update disabled in public mode");
            return;
        }

        net.bladewatch.app.updater.AppUpdater updater =
                new net.bladewatch.app.updater.AppUpdater(ctx);

        final Object lock = new Object();
        final boolean[] done = {false};
        final boolean[] available = {false};
        final String[] err = {null};
        final String[] versionRef = {null};

        updater.checkForUpdate(new net.bladewatch.app.updater.AppUpdater.UpdateCallback() {
            @Override public void onUpdateAvailable(String c, String n, String rn) {
                available[0] = true;
                versionRef[0] = n;
                synchronized (lock) { done[0] = true; lock.notify(); }
            }
            @Override public void onNoUpdate(String c) {
                synchronized (lock) { done[0] = true; lock.notify(); }
            }
            @Override public void onError(String e) {
                err[0] = e;
                synchronized (lock) { done[0] = true; lock.notify(); }
            }
        });
        try {
            synchronized (lock) {
                if (!done[0]) lock.wait(20_000);
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        if (err[0] != null) {
            response.put("success", false);
            response.put("error", "Pre-install check failed: " + err[0]);
            return;
        }
        if (!available[0]) {
            response.put("success", false);
            response.put("error", "No update available");
            return;
        }

        // Start install on a background thread. The IPC reply returns now
        // because the daemon dies mid-install; progress is reported via
        // /data/local/tmp/bladewatch_update_progress.json, which the webapp
        // tails.
        new Thread(() -> {
            try {
                writeInstallProgress("queued", 0, "Update queued", null);
                updater.downloadAndInstall(new net.bladewatch.app.updater.AppUpdater.InstallCallback() {
                    @Override public void onProgress(String message) {
                        String m = message == null ? "" : message;
                        String phase = "downloading";
                        if (m.contains("Verifying")) phase = "verifying";
                        else if (m.contains("Stopping daemons")) phase = "stopping_daemons";
                        else if (m.contains("Installing") || m.contains("installed")) phase = "installing";
                        writeInstallProgress(phase, -1, m, null);
                    }
                    @Override public void onDownloadProgress(int percent) {
                        writeInstallProgress("downloading", percent,
                                percent < 0 ? "Downloading…" : "Downloading " + percent + "%",
                                null);
                    }
                    @Override public void onSuccess() {
                        writeInstallProgress("installing", 100, "Update installed, restarting…", null);
                    }
                    @Override public void onError(String error) {
                        writeInstallProgress("error", -1, "Install failed", error);
                    }
                });
            } catch (Exception e) {
                writeInstallProgress("error", -1, "Install crashed", e.getMessage());
            }
        }, "Update-Install").start();

        response.put("success", true);
        response.put("status", "scheduled");
        response.put("remoteVersion", versionRef[0] != null ? versionRef[0] : "");
    }

    private static void writeInstallProgress(String phase, int percent, String message, String error) {
        JSONObject r = new JSONObject();
        try {
            r.put("phase", phase);
            r.put("percent", percent);
            r.put("message", message != null ? message : "");
            if (error != null) r.put("error", error);
            r.put("ts", System.currentTimeMillis());
        } catch (Exception ignored) {}
        try (java.io.FileWriter fw = new java.io.FileWriter(
                "/data/local/tmp/bladewatch_update_progress.json")) {
            fw.write(r.toString());
        } catch (Exception ignored) {}
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (Exception e) {
            logger.error("Error stopping IPC server", e);
        }
        // SOTA FIX: Kill all active connections immediately on shutdown
        threadPool.shutdownNow();
    }
}
