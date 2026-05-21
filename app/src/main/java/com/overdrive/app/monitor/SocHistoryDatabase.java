package com.overdrive.app.monitor;

import com.overdrive.app.logging.DaemonLogger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * SOTA SocHistoryDatabase - Uses H2 embedded database (100% pure Java).
 * 
 * H2 advantages over SQLite/SQLDroid:
 * - Zero native dependencies (no .so files, no UnsatisfiedLinkError)
 * - Zero Android framework dependency (no Context, no package verification)
 * - Full SQL support with SQLite compatibility mode
 * - Works perfectly for UID 2000 daemon processes
 */
public class SocHistoryDatabase {
    
    private static final String TAG = "SocHistoryDatabase";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);
    
    // H2 JDBC URL - file-based embedded database
    // FILE_LOCK=SOCKET uses socket-based locking (more reliable than file locks on Android)
    // AUTO_SERVER=TRUE allows multiple processes to connect via TCP fallback
    private static final String DB_PATH = "/data/local/tmp/overdrive_soc_h2";
    // DB_CLOSE_ON_EXIT=FALSE: we drive shutdown ourselves from CameraDaemon.shutdown().
    // Without it, H2's JVM shutdown hook runs concurrently with our explicit
    // stop() and our last in-flight 2-minute SOC tick, producing the
    // "Database is already closed" + "Could not save properties …lock.db"
    // pair that orphans the lock file across daemon restarts.
    //
    // AUTO_SERVER intentionally omitted — H2 throws
    // "AUTO_SERVER=TRUE && DB_CLOSE_ON_EXIT=FALSE is not supported" if both
    // are set. We're single-process anyway (only the camera daemon writes;
    // HTTP reads happen in the same JVM via NotificationApiHandler). The
    // FILE_LOCK=SOCKET is the actual cross-process safety net.
    private static final String JDBC_URL = "jdbc:h2:file:" + DB_PATH +
        ";FILE_LOCK=SOCKET;TRACE_LEVEL_FILE=0;DB_CLOSE_ON_EXIT=FALSE";
    
    // Table names
    private static final String TABLE_SOC = "soc_history";
    private static final String TABLE_CHARGING = "charging_sessions";
    private static final String TABLE_ACC_EVENTS = "acc_events";
    
    // Retention periods
    private static final long RETENTION_DAYS = 7;
    private static final long SAMPLE_INTERVAL_MS = 120_000;  // 2 minutes - SOTA interval for daemon recording
    
    // Singleton
    private static SocHistoryDatabase instance;
    private static final Object lock = new Object();
    
    // H2 Connection (kept open for performance)
    private Connection connection;
    
    private ScheduledExecutorService scheduler;
    private volatile boolean isRunning = false;
    private volatile boolean isInitialized = false;
    
    // Charging session tracking
    private boolean wasCharging = false;
    private long chargingStartTime = 0;
    private double chargingStartSoc = 0;
    
    // Last recorded values for deduplication
    private long lastRecordTime = 0;
    private double lastRecordedSoc = -1;
    private double lastRecordedKwh = -1;
    
    // SohEstimator reference (set externally)
    private volatile com.overdrive.app.abrp.SohEstimator sohEstimator;
    
    private SocHistoryDatabase() {
        // Load the H2 JDBC driver (pure Java - always works)
        try {
            Class.forName("org.h2.Driver");
            logger.info("H2 JDBC Driver loaded successfully");
        } catch (ClassNotFoundException e) {
            logger.error("H2 Driver not found! Check gradle dependencies.", e);
        } catch (Exception e) {
            logger.error("Failed to load H2 Driver: " + e.getMessage(), e);
        }
    }
    
    public static SocHistoryDatabase getInstance() {
        if (instance == null) {
            synchronized (lock) {
                if (instance == null) {
                    instance = new SocHistoryDatabase();
                }
            }
        }
        return instance;
    }
    
    // ==================== LIFECYCLE ====================
    
    public void init() {
        if (isInitialized) return;
        
        synchronized (lock) {
            if (isInitialized) return;  // Double-check after acquiring lock
            
            logger.info("Initializing H2 database at: " + DB_PATH);
            
            int maxRetries = 3;
            int retryDelayMs = 1000;
            
            for (int attempt = 1; attempt <= maxRetries; attempt++) {
                try {
                    // Open H2 connection (pure Java - no native code)
                    connection = DriverManager.getConnection(JDBC_URL, "sa", "");
                    logger.info("H2 connection established");
                    
                    // Tune H2 for embedded daemon use
                    try (Statement stmt = connection.createStatement()) {
                        stmt.execute("SET CACHE_SIZE 8192");  // 8MB cache
                    }
                    
                    // Create tables
                    createTables();
                    
                    isInitialized = true;
                    logger.info("SOC History Database initialized via H2 (Pure Java): " + DB_PATH);
                    return;  // Success - exit
                    
                } catch (Exception e) {
                    String msg = e.getMessage();
                    boolean isLockError = msg != null && (msg.contains("Locked by another process") || 
                        msg.contains("lock.db") || msg.contains("already in use"));
                    
                    if (isLockError && attempt < maxRetries) {
                        logger.warn("Database locked (attempt " + attempt + "/" + maxRetries + "), cleaning up stale locks...");
                        cleanupStaleLocks();
                        try {
                            Thread.sleep(retryDelayMs * attempt);  // Exponential backoff
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    } else {
                        logger.error("Failed to initialize SOC database: " + e.getClass().getName() + " - " + msg, e);
                        break;
                    }
                }
            }
        }
    }
    
    /**
     * Clean up stale lock files that may have been left by crashed processes.
     */
    private void cleanupStaleLocks() {
        try {
            java.io.File lockFile = new java.io.File(DB_PATH + ".lock.db");
            if (lockFile.exists()) {
                // Check if the lock file is stale (older than 5 minutes with no active process)
                long ageMs = System.currentTimeMillis() - lockFile.lastModified();
                if (ageMs > 5 * 60 * 1000) {  // 5 minutes
                    if (lockFile.delete()) {
                        logger.info("Deleted stale lock file (age: " + (ageMs / 1000) + "s)");
                    }
                }
            }
            
            // Also try to clean up trace files
            java.io.File traceFile = new java.io.File(DB_PATH + ".trace.db");
            if (traceFile.exists()) {
                traceFile.delete();
            }
        } catch (Exception e) {
            logger.debug("Lock cleanup failed: " + e.getMessage());
        }
    }
    
    private void createTables() throws Exception {
        try (Statement stmt = connection.createStatement()) {
            // SOC history table
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS " + TABLE_SOC + " (" +
                "id IDENTITY PRIMARY KEY," +
                "timestamp BIGINT NOT NULL," +
                "soc_percent REAL NOT NULL," +
                "is_charging INTEGER DEFAULT 0," +
                "charging_power_kw REAL DEFAULT 0," +
                "voltage_v REAL DEFAULT 0," +
                "range_km INTEGER DEFAULT 0," +
                "remaining_kwh REAL DEFAULT 0" +
                ");"
            );
            
            // Add remaining_kwh column if it doesn't exist (migration for existing DBs)
            try {
                stmt.execute("ALTER TABLE " + TABLE_SOC + " ADD COLUMN IF NOT EXISTS remaining_kwh REAL DEFAULT 0;");
            } catch (Exception ignored) {
                // Column may already exist
            }
            
            // Migration: add battery health columns
            String[] newColumns = {
                "hv_temp_high REAL DEFAULT -999",
                "hv_temp_low REAL DEFAULT -999",
                "hv_temp_avg REAL DEFAULT -999",
                "cell_volt_high REAL DEFAULT -999",
                "cell_volt_low REAL DEFAULT -999",
                "soh_percent REAL DEFAULT -999"
            };
            for (String col : newColumns) {
                try {
                    stmt.execute("ALTER TABLE " + TABLE_SOC + " ADD COLUMN IF NOT EXISTS " + col + ";");
                } catch (Exception ignored) {}
            }
            
            // Index for fast time-based queries
            stmt.execute(
                "CREATE INDEX IF NOT EXISTS idx_soc_timestamp ON " + TABLE_SOC + "(timestamp);"
            );
            
            // Charging sessions table
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS " + TABLE_CHARGING + " (" +
                "id IDENTITY PRIMARY KEY," +
                "start_time BIGINT NOT NULL," +
                "end_time BIGINT," +
                "start_soc REAL NOT NULL," +
                "end_soc REAL," +
                "energy_added_kwh REAL," +
                "peak_power_kw REAL" +
                ");"
            );

            stmt.execute(
                "CREATE INDEX IF NOT EXISTS idx_charging_start ON " + TABLE_CHARGING + "(start_time);"
            );

            // ACC events table — every ACC ON/OFF transition is logged here so
            // the dashboard "parking delta" insight can compute changes across
            // a real park-and-return cycle (not inferred from SOC sample gaps).
            // Snapshot fields are nullable: if BydDataCollector is not yet
            // initialized at the moment of the event we still record the
            // transition so future correlation is possible.
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS " + TABLE_ACC_EVENTS + " (" +
                "id IDENTITY PRIMARY KEY," +
                "timestamp BIGINT NOT NULL," +
                "event_type VARCHAR(8) NOT NULL," +    // 'ON' or 'OFF'
                "soc_percent REAL," +                  // nullable if read failed
                "remaining_kwh REAL," +                // nullable
                "voltage_v REAL," +                    // nullable
                "range_km INTEGER" +                   // nullable
                ");"
            );

            stmt.execute(
                "CREATE INDEX IF NOT EXISTS idx_acc_events_ts ON " + TABLE_ACC_EVENTS + "(timestamp DESC);"
            );

            logger.info("acc_events table ready (migration idempotent)");
        }
    }

    public void start() {
        if (isRunning) return;
        
        if (!isInitialized) {
            init();
        }
        
        if (!isInitialized) {
            logger.error("Cannot start SOC history - database init failed");
            return;
        }
        
        isRunning = true;
        
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "SocHistoryDB");
            t.setPriority(Thread.MIN_PRIORITY);
            // Set uncaught exception handler to prevent silent death
            t.setUncaughtExceptionHandler((thread, ex) -> {
                logger.error("Uncaught exception in SocHistoryDB thread: " + ex.getMessage(), ex);
            });
            return t;
        });
        
        // Record SOC every minute - wrap in Runnable that catches all exceptions
        scheduler.scheduleAtFixedRate(() -> {
            try {
                recordCurrentSoc();
            } catch (Throwable t) {
                // Catch everything including Errors to prevent scheduler death
                logger.error("Critical error in SOC recording task: " + t.getMessage(), 
                    t instanceof Exception ? (Exception) t : new Exception(t));
            }
        }, 0, SAMPLE_INTERVAL_MS, TimeUnit.MILLISECONDS);
        
        // Cleanup old data daily
        scheduler.scheduleAtFixedRate(() -> {
            try {
                cleanupOldData();
            } catch (Throwable t) {
                logger.error("Critical error in cleanup task: " + t.getMessage(),
                    t instanceof Exception ? (Exception) t : new Exception(t));
            }
        }, 1, 24, TimeUnit.HOURS);
        
        logger.info("SOC history recording started (interval: " + SAMPLE_INTERVAL_MS + "ms)");
    }
    
    public void stop() {
        isRunning = false;
        if (scheduler != null) {
            scheduler.shutdown();
            try {
                // Give an in-flight tick a moment to finish so we don't close
                // the connection out from under it. shutdownNow() interrupts
                // the worker but doesn't wait — and the H2 write isn't
                // interruptible, so the tick still hits the JDBC layer with
                // a closed connection.
                if (!scheduler.awaitTermination(2, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException ie) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
            scheduler = null;
        }

        if (connection != null) {
            try {
                connection.close();
            } catch (Exception ignored) {}
            connection = null;
        }
        isInitialized = false;

        logger.info("SOC history recording stopped");
    }
    
    private void reconnect() {
        // After stop() flips isRunning=false the connection is intentionally
        // closed. Re-opening here would re-acquire the lock file just before
        // the JVM exits, leaving an orphaned .lock.db that blocks the next
        // daemon start. Same defense in TripDatabase.reconnect.
        if (!isRunning) return;
        try {
            if (connection == null || connection.isClosed()) {
                connection = DriverManager.getConnection(JDBC_URL, "sa", "");
                logger.debug("H2 connection re-established");
            }
        } catch (Exception e) {
            logger.error("Failed to reconnect to H2", e);
        }
    }
    
    // ==================== DATA RECORDING ====================
    
    private void recordCurrentSoc() {
        // Wrap entire method in try-catch to prevent scheduler death
        try {
            // Bail out cleanly when stop() has already begun — otherwise we
            // race connection.close() and trip H2's "already closed" path,
            // which re-opens the DB on reconnect() and orphans the lock file.
            if (!isRunning) return;
            if (!isInitialized || connection == null) {
                logger.debug("SOC recording skipped: not initialized or no connection");
                reconnect();
                return;
            }
            
            VehicleDataMonitor monitor = VehicleDataMonitor.getInstance();
            if (monitor == null) {
                logger.debug("SOC recording skipped: VehicleDataMonitor not available");
                return;
            }
            
            BatterySocData socData = monitor.getBatterySoc();
            ChargingStateData chargingData = monitor.getChargingState();
            DrivingRangeData rangeData = monitor.getDrivingRange();
            BatteryPowerData powerData = monitor.getBatteryPower();
            
            if (socData == null) {
                logger.debug("SOC recording skipped: no SOC data available");
                return;
            }
            
            double soc = socData.socPercent;
            boolean isCharging = chargingData != null && 
                chargingData.status == ChargingStateData.ChargingStatus.CHARGING;
            double chargingPower = chargingData != null ? chargingData.chargingPowerKW : 0;
            double voltage = powerData != null ? powerData.voltageVolts : 0;
            int range = rangeData != null ? rangeData.elecRangeKm : 0;
            
            // SOTA: Get remaining battery power in kWh from BYDAutoPowerDevice
            double remainingKwh = 0;
            try {
                remainingKwh = monitor.getBatteryRemainPowerKwh();
            } catch (Exception e) {
                logger.debug("Failed to get remaining kWh: " + e.getMessage());
            }
            
            // Shape B: live formula drives SOH directly from this tick's
            // remainKwh + SOC. Feed RAW vd.remainKwh, never the synthesized
            // value from getBatteryRemainPowerKwh — the synthesizer falls
            // back to (soc/100 × nominal × currentSoh/100) on PHEV / bad-BMS
            // paths, which would loop currentSoh into itself and freeze
            // the formula at its initial seed forever.
            try {
                com.overdrive.app.abrp.SohEstimator sohEst = getSohEstimator();
                if (sohEst != null) {
                    if (sohEst.getNominalCapacityKwh() <= 0) {
                        sohEst.autoDetectCarModel(
                            com.overdrive.app.daemon.CameraDaemon.getAppContext());
                    }
                    if (sohEst.getNominalCapacityKwh() > 0 && !sohEst.hasEstimate()) {
                        sohEst.seedInitialEstimate();
                    }

                    double rawRemainKwh = Double.NaN;
                    double highCellV = Double.NaN;
                    try {
                        com.overdrive.app.byd.BydDataCollector col = com.overdrive.app.byd.BydDataCollector.getInstance();
                        if (col != null && col.isInitialized()) {
                            com.overdrive.app.byd.BydVehicleData vd = col.getData();
                            if (vd != null) {
                                if (!Double.isNaN(vd.remainKwh)) rawRemainKwh = vd.remainKwh;
                                if (!Double.isNaN(vd.highCellVoltage)) highCellV = vd.highCellVoltage;
                            }
                        }
                    } catch (Exception ignored) { /* leave NaN */ }

                    if (rawRemainKwh > 0 && soc > 0
                            && sohEst.getNominalCapacityKwh() > 0) {
                        double impliedCap = rawRemainKwh / (soc / 100.0);
                        double nominal = sohEst.getNominalCapacityKwh();
                        double ratio = impliedCap / nominal;
                        // Skip when raw BMS reads outside 50-150% of nominal —
                        // those are firmware-junk values, not real degradation.
                        if (ratio >= 0.5 && ratio <= 1.5) {
                            boolean atRest = isVehicleAtRest();
                            sohEst.updateFromEnergy(rawRemainKwh, soc, highCellV, atRest);
                        }
                    }
                }
            } catch (Exception e) {
                logger.debug("SOH update failed: " + e.getMessage());
            }
            
            // HV battery thermal data — from BydDataCollector (has real cell temps via Integer.TYPE)
            double hvTempHigh = -999, hvTempLow = -999, hvTempAvg = -999;
            double cellVoltHigh = -999, cellVoltLow = -999;
            try {
                com.overdrive.app.byd.BydDataCollector collector = com.overdrive.app.byd.BydDataCollector.getInstance();
                if (collector.isInitialized()) {
                    com.overdrive.app.byd.BydVehicleData vd = collector.getData();
                    if (vd != null) {
                        if (!Double.isNaN(vd.highCellTempC)) hvTempHigh = vd.highCellTempC;
                        if (!Double.isNaN(vd.lowCellTempC)) hvTempLow = vd.lowCellTempC;
                        if (!Double.isNaN(vd.avgCellTempC)) hvTempAvg = vd.avgCellTempC;
                        if (!Double.isNaN(vd.highCellVoltage)) cellVoltHigh = vd.highCellVoltage;
                        if (!Double.isNaN(vd.lowCellVoltage)) cellVoltLow = vd.lowCellVoltage;
                    }
                }
            } catch (Exception e) {
                logger.debug("Failed to get collector data: " + e.getMessage());
            }
            // Fallback to VehicleDataMonitor if collector didn't have temps
            if (hvTempHigh == -999 && hvTempLow == -999 && hvTempAvg == -999) {
                BatteryThermalData thermalData = monitor.getBatteryThermal();
                if (thermalData != null && thermalData.hasData()) {
                    if (!Double.isNaN(thermalData.highestTempC)) hvTempHigh = thermalData.highestTempC;
                    if (!Double.isNaN(thermalData.lowestTempC)) hvTempLow = thermalData.lowestTempC;
                    if (!Double.isNaN(thermalData.averageTempC)) hvTempAvg = thermalData.averageTempC;
                }
            }
            
            // SOH from SohEstimator (via AbrpTelemetryService)
            double sohPercent = -999;
            try {
                com.overdrive.app.abrp.SohEstimator sohEst = getSohEstimator();
                if (sohEst != null && sohEst.hasEstimate()) {
                    sohPercent = sohEst.getCurrentSoh();
                    logger.debug("SOH from estimator: " + String.format("%.1f", sohPercent) + "%");
                } else {
                    // Fallback: read from persisted file
                    logger.info("SOH estimator " + (sohEst == null ? "is null" : "has no estimate") + ", trying persisted file fallback");
                    java.io.File sohFile = new java.io.File("/data/local/tmp/abrp_soh_estimate.properties");
                    if (sohFile.exists()) {
                        java.util.Properties props = new java.util.Properties();
                        try (java.io.FileInputStream fis = new java.io.FileInputStream(sohFile)) {
                            props.load(fis);
                        }
                        String sohStr = props.getProperty("soh_percent");
                        if (sohStr != null) {
                            double soh = Double.parseDouble(sohStr);
                            if (soh > 0 && soh <= 100) {
                                sohPercent = soh;
                                logger.info("SOH from persisted file fallback: " + soh + "%");
                            }
                        }
                    } else {
                        logger.info("SOH persisted file not found at /data/local/tmp/abrp_soh_estimate.properties");
                    }
                }
            } catch (Exception e) {
                logger.debug("Failed to get SOH: " + e.getMessage());
            }
            
            long now = System.currentTimeMillis();

            // Record at least once every 10 minutes regardless of SOC change
            // This ensures continuous data even when parked (5x the 2-min interval)
            long maxInterval = SAMPLE_INTERVAL_MS * 5; // 10 minutes
            boolean forceRecord = (now - lastRecordTime) >= maxInterval;

            // Always record on charging-state transitions so the chart's charging
            // band and the charging_sessions table both see the start/end edges
            // even when SOC hasn't moved 0.5% yet (typical for the first minutes
            // of AC charging on a PHEV, and for any unplug while at 100%).
            boolean stateTransition = (isCharging != wasCharging);

            // BEV BMS reports remainKwh independently of SOC and can drift while
            // SOC stays in the same percent bucket — record those updates too.
            boolean kwhMoved = lastRecordedKwh >= 0 && remainingKwh > 0
                && Math.abs(remainingKwh - lastRecordedKwh) >= 0.5;

            // Skip only if nothing meaningful changed AND we recorded recently
            if (!forceRecord && !stateTransition && !kwhMoved
                    && lastRecordedSoc >= 0 && Math.abs(soc - lastRecordedSoc) < 0.5) {
                return;
            }
            
            // Check connection is still valid
            try {
                if (connection.isClosed()) {
                    logger.info("Connection closed, reconnecting...");
                    reconnect();
                    if (connection == null || connection.isClosed()) {
                        logger.error("Failed to reconnect to database");
                        return;
                    }
                }
            } catch (Exception e) {
                logger.error("Connection check failed", e);
                reconnect();
                return;
            }
            
            // Insert with all battery health columns
            String sql = "INSERT INTO " + TABLE_SOC + 
                " (timestamp, soc_percent, is_charging, charging_power_kw, voltage_v, range_km, remaining_kwh," +
                " hv_temp_high, hv_temp_low, hv_temp_avg, cell_volt_high, cell_volt_low, soh_percent) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?);";
            
            try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
                pstmt.setLong(1, now);
                pstmt.setDouble(2, soc);
                pstmt.setInt(3, isCharging ? 1 : 0);
                pstmt.setDouble(4, chargingPower);
                pstmt.setDouble(5, voltage);
                pstmt.setInt(6, range);
                pstmt.setDouble(7, remainingKwh);
                pstmt.setDouble(8, hvTempHigh);
                pstmt.setDouble(9, hvTempLow);
                pstmt.setDouble(10, hvTempAvg);
                pstmt.setDouble(11, cellVoltHigh);
                pstmt.setDouble(12, cellVoltLow);
                pstmt.setDouble(13, sohPercent);
                pstmt.executeUpdate();
            }
            
            lastRecordTime = now;
            lastRecordedSoc = soc;
            if (remainingKwh > 0) lastRecordedKwh = remainingKwh;

            logger.debug("Recorded SOC: " + soc + "% (charging: " + isCharging + ")");
            
            // Track charging sessions
            trackChargingSession(isCharging, soc, chargingPower, now);
            
        } catch (Exception e) {
            // Log but don't rethrow - scheduler must continue running
            logger.error("Failed to record SOC: " + e.getMessage(), e);
            try {
                reconnect();
            } catch (Exception re) {
                logger.error("Reconnect also failed: " + re.getMessage());
            }
        }
    }
    
    private void trackChargingSession(boolean isCharging, double soc, double power, long now) {
        if (!isInitialized || connection == null) return;
        
        try {
            if (isCharging && !wasCharging) {
                // Charging started
                chargingStartTime = now;
                chargingStartSoc = soc;
                
                String sql = "INSERT INTO " + TABLE_CHARGING + 
                    " (start_time, start_soc, peak_power_kw) VALUES (?, ?, ?);";
                
                try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
                    pstmt.setLong(1, now);
                    pstmt.setDouble(2, soc);
                    pstmt.setDouble(3, power);
                    pstmt.executeUpdate();
                }
                
                logger.info("Charging session started at " + soc + "%");
                
            } else if (!isCharging && wasCharging) {
                // Charging ended — compute energy added and update SOH
                double socDelta = soc - chargingStartSoc;
                
                // Compute energy added using nominal capacity if available,
                // otherwise fall back to rough estimate
                double energyAdded = 0;
                boolean isAcCharge = true; // Assume AC unless peak power > 20 kW
                double packTemp = 25.0;    // Default — updated below if available
                
                com.overdrive.app.abrp.SohEstimator sohEst = getSohEstimator();
                double nominalKwh = sohEst != null ? sohEst.getNominalCapacityKwh() : 0;
                
                if (nominalKwh > 0 && socDelta > 0) {
                    // Energy added ≈ socDelta% × nominalKwh.
                    //
                    // The previous version applied a 0.95 multiplier "to
                    // account for BYD hiding ~5% display reserve." That's an
                    // NMC convention; on BYD Blade LFP packs the displayed
                    // 0–100% range maps to ~100% of nominal usable energy,
                    // so the correction is double-counting and biased every
                    // calibration ~5% optimistic. updateFromCalibration() now
                    // applies the correct chemistry-aware scale internally.
                    energyAdded = (socDelta / 100.0) * nominalKwh;
                } else {
                    energyAdded = socDelta * 0.6; // Rough fallback
                }
                
                // Get battery temperature for calibration quality check
                try {
                    VehicleDataMonitor monitor = VehicleDataMonitor.getInstance();
                    BatteryThermalData thermal = monitor.getBatteryThermal();
                    if (thermal != null && thermal.hasData() && !Double.isNaN(thermal.averageTempC)) {
                        packTemp = thermal.averageTempC;
                    }
                } catch (Exception e) { /* use default */ }
                
                // Detect DC fast charging from peak power
                // AC charging is typically < 11 kW (single phase) or < 22 kW (three phase)
                if (power > 20) isAcCharge = false;
                
                String sql = "UPDATE " + TABLE_CHARGING + 
                    " SET end_time = ?, end_soc = ?, energy_added_kwh = ? " +
                    "WHERE start_time = ? AND end_time IS NULL;";
                
                try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
                    pstmt.setLong(1, now);
                    pstmt.setDouble(2, soc);
                    pstmt.setDouble(3, energyAdded);
                    pstmt.setLong(4, chargingStartTime);
                    pstmt.executeUpdate();
                }
                
                logger.info("Charging session ended at " + soc + "% (+" + 
                    String.format("%.1f", socDelta) + "%, ~" +
                    String.format("%.1f", energyAdded) + " kWh, " +
                    (isAcCharge ? "AC" : "DC") + ", " +
                    String.format("%.0f", packTemp) + "°C)");
                
                // Feed calibration data to SohEstimator for ongoing SOH tracking.
                // Pass the highest cell voltage observed at session end so
                // updateFromCalibration() can pick LFP vs NMC chemistry scale.
                if (sohEst != null && socDelta > 0 && energyAdded > 0) {
                    double highCellV = Double.NaN;
                    try {
                        com.overdrive.app.byd.BydDataCollector col =
                            com.overdrive.app.byd.BydDataCollector.getInstance();
                        if (col != null && col.isInitialized()) {
                            com.overdrive.app.byd.BydVehicleData vd = col.getData();
                            if (vd != null && !Double.isNaN(vd.highCellVoltage)) {
                                highCellV = vd.highCellVoltage;
                            }
                        }
                    } catch (Exception ignored) { /* keep NaN → defaults to LFP */ }
                    try {
                        sohEst.updateFromCalibration(energyAdded, socDelta, packTemp, isAcCharge, highCellV);
                    } catch (Exception e) {
                        logger.debug("SOH calibration update failed: " + e.getMessage());
                    }
                }
            }
            
            wasCharging = isCharging;
            
        } catch (Exception e) {
            logger.error("Failed to track charging session", e);
        }
    }


    // ==================== DATA RETRIEVAL ====================
    
    /**
     * Get SOC history for charting.
     * Uses time-based bucketing for efficient downsampling - larger windows = larger buckets.
     * Returns data in ASC order (oldest first) for time-series chart rendering.
     */
    public JSONArray getSocHistory(int hoursBack, int maxPoints) {
        JSONArray results = new JSONArray();
        
        if (!isInitialized || connection == null) {
            logger.debug("Database not initialized for getSocHistory");
            return results;
        }
        
        try {
            long now = System.currentTimeMillis();
            int hours = Math.min(hoursBack, 168);
            long startTime = now - (hours * 60 * 60 * 1000L);
            
            // Calculate bucket size based on time window
            // Goal: ~maxPoints buckets across the time range
            // Minimum bucket: 2 minutes (one sample), Maximum: 30 minutes for week view
            long timeRangeMs = hours * 60 * 60 * 1000L;
            long bucketMs = Math.max(120_000L, timeRangeMs / maxPoints); // At least 2 min
            bucketMs = Math.min(bucketMs, 30 * 60 * 1000L); // Cap at 30 min
            
            // Time-bucketed query - takes first sample from each bucket
            // Much more efficient than row numbering for large datasets
            String querySql = 
                "SELECT MIN(timestamp) as t, " +
                "  AVG(soc_percent) as soc, " +
                "  MAX(is_charging) as charging, " +
                "  AVG(CASE WHEN charging_power_kw > 0 THEN charging_power_kw END) as power, " +
                "  AVG(range_km) as range, " +
                "  AVG(CASE WHEN remaining_kwh > 0 THEN remaining_kwh END) as kwh, " +
                "  AVG(CASE WHEN voltage_v > 0 THEN voltage_v END) as volt, " +
                "  AVG(CASE WHEN hv_temp_avg > -999 THEN hv_temp_avg END) as temp, " +
                "  AVG(CASE WHEN soh_percent > 0 THEN soh_percent END) as soh " +
                "FROM " + TABLE_SOC + " " +
                "WHERE timestamp >= ? " +
                "GROUP BY (timestamp / ?) " +
                "ORDER BY t ASC " +
                "LIMIT ?;";
            
            try (PreparedStatement pstmt = connection.prepareStatement(querySql)) {
                pstmt.setLong(1, startTime);
                pstmt.setLong(2, bucketMs);
                pstmt.setInt(3, maxPoints);
                
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        JSONObject row = new JSONObject();
                        row.put("t", rs.getLong("t"));
                        row.put("soc", Math.round(rs.getDouble("soc") * 10) / 10.0); // 1 decimal
                        row.put("charging", rs.getInt("charging") == 1);
                        double power = rs.getDouble("power");
                        row.put("power", rs.wasNull() ? 0 : Math.round(power * 100) / 100.0);
                        row.put("range", (int) rs.getDouble("range"));
                        double kwh = rs.getDouble("kwh");
                        if (!rs.wasNull()) row.put("kwh", Math.round(kwh * 10) / 10.0);
                        double volt = rs.getDouble("volt");
                        if (!rs.wasNull() && volt > 0) row.put("volt", Math.round(volt * 100) / 100.0);
                        double temp = rs.getDouble("temp");
                        if (!rs.wasNull()) row.put("temp", Math.round(temp * 10) / 10.0);
                        double soh = rs.getDouble("soh");
                        if (!rs.wasNull() && soh > 0) row.put("soh", Math.round(soh * 10) / 10.0);
                        results.put(row);
                    }
                }
            }
            
        } catch (Exception e) {
            logger.error("Failed to get SOC history", e);
            reconnect();
        }
        
        return results;
    }
    
    /**
     * Get charging sessions.
     */
    public JSONArray getChargingSessions(int daysBack) {
        JSONArray results = new JSONArray();
        
        if (!isInitialized || connection == null) {
            return results;
        }
        
        try {
            long startTime = System.currentTimeMillis() - (daysBack * 24 * 60 * 60 * 1000L);
            
            String sql = "SELECT start_time as startTime, end_time as endTime, start_soc as startSoc, " +
                "end_soc as endSoc, energy_added_kwh as energyAdded, peak_power_kw as peakPower " +
                "FROM " + TABLE_CHARGING + " WHERE start_time >= ? ORDER BY start_time DESC;";
            
            try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
                pstmt.setLong(1, startTime);
                
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        JSONObject row = new JSONObject();
                        row.put("startTime", rs.getLong("startTime"));
                        row.put("endTime", rs.getLong("endTime"));
                        row.put("startSoc", rs.getDouble("startSoc"));
                        row.put("endSoc", rs.getDouble("endSoc"));
                        row.put("energyAdded", rs.getDouble("energyAdded"));
                        row.put("peakPower", rs.getDouble("peakPower"));
                        results.put(row);
                    }
                }
            }
            
        } catch (Exception e) {
            logger.error("Failed to get charging sessions", e);
            reconnect();
        }
        
        return results;
    }
    
    /**
     * Get SOC statistics.
     */
    public JSONObject getSocStats(int hoursBack) {
        JSONObject stats = new JSONObject();
        
        try {
            // Always get current SOC from VehicleDataMonitor
            VehicleDataMonitor monitor = VehicleDataMonitor.getInstance();
            BatterySocData currentSoc = monitor.getBatterySoc();
            if (currentSoc != null) {
                stats.put("currentSoc", currentSoc.socPercent);
                stats.put("isLow", currentSoc.isLow);
                stats.put("isCritical", currentSoc.isCritical);
            }
            
            if (!isInitialized || connection == null) {
                return stats;
            }
            
            long startTime = System.currentTimeMillis() - (hoursBack * 60 * 60 * 1000L);
            
            // Get min/max/avg/count
            String statsSql = "SELECT MIN(soc_percent), MAX(soc_percent), AVG(soc_percent), COUNT(*) " +
                "FROM " + TABLE_SOC + " WHERE timestamp >= ?;";
            
            try (PreparedStatement pstmt = connection.prepareStatement(statsSql)) {
                pstmt.setLong(1, startTime);
                
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        stats.put("minSoc", rs.getDouble(1));
                        stats.put("maxSoc", rs.getDouble(2));
                        stats.put("avgSoc", rs.getDouble(3));
                        stats.put("sampleCount", rs.getInt(4));
                    }
                }
            }
            
            // Get charging session count
            String chargingSql = "SELECT COUNT(*) FROM " + TABLE_CHARGING + " WHERE start_time >= ?;";
            
            try (PreparedStatement pstmt = connection.prepareStatement(chargingSql)) {
                pstmt.setLong(1, startTime);
                
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        stats.put("chargingSessions", rs.getInt(1));
                    }
                }
            }
            
        } catch (Exception e) {
            logger.error("Failed to get SOC stats", e);
        }
        
        return stats;
    }
    
    /**
     * Get full report for dashboard.
     * Always includes current SOC from VehicleDataMonitor even if no history exists.
     */
    public JSONObject getFullReport(int hoursBack, int maxPoints) {
        JSONObject report = new JSONObject();
        
        try {
            JSONArray history = getSocHistory(hoursBack, maxPoints);
            JSONObject stats = getSocStats(hoursBack);
            
            // Always ensure current SOC is available from live monitor
            VehicleDataMonitor monitor = VehicleDataMonitor.getInstance();
            BatterySocData currentSocData = monitor.getBatterySoc();
            DrivingRangeData rangeData = monitor.getDrivingRange();
            ChargingStateData chargingData = monitor.getChargingState();
            
            // Always append a live data point at the end so the "current" kWh/SOC
            // display is fresh from the monitor, not averaged from old DB records
            if (currentSocData != null) {
                JSONObject livePoint = new JSONObject();
                livePoint.put("t", System.currentTimeMillis());
                livePoint.put("soc", currentSocData.socPercent);
                livePoint.put("charging", chargingData != null && 
                    chargingData.status == ChargingStateData.ChargingStatus.CHARGING);
                livePoint.put("power", chargingData != null ? chargingData.chargingPowerKW : 0);
                livePoint.put("range", rangeData != null ? rangeData.elecRangeKm : 0);
                double liveKwh = monitor.getBatteryRemainPowerKwh();
                if (liveKwh > 0) livePoint.put("kwh", Math.round(liveKwh * 10) / 10.0);
                
                com.overdrive.app.abrp.SohEstimator sohEst = getSohEstimator();
                if (sohEst != null && sohEst.hasEstimate()) {
                    livePoint.put("soh", Math.round(sohEst.getCurrentSoh() * 10) / 10.0);
                }
                
                history.put(livePoint);
            }
            
            // Ensure stats has current SOC even if DB query returned nothing
            if (!stats.has("currentSoc") && currentSocData != null) {
                stats.put("currentSoc", currentSocData.socPercent);
                stats.put("isLow", currentSocData.isLow);
                stats.put("isCritical", currentSocData.isCritical);
            }
            
            report.put("history", history);
            report.put("stats", stats);
            report.put("chargingSessions", getChargingSessions(hoursBack / 24));
            report.put("hoursBack", hoursBack);
            report.put("maxPoints", maxPoints);
            report.put("timestamp", System.currentTimeMillis());
            
            // Add live data flag so frontend knows data is fresh
            report.put("hasLiveData", currentSocData != null);
            
        } catch (Exception e) {
            logger.error("Failed to create full report", e);
        }
        
        return report;
    }
    
    /**
     * Set the SohEstimator reference for recording SOH alongside battery data.
     */
    public void setSohEstimator(com.overdrive.app.abrp.SohEstimator estimator) {
        this.sohEstimator = estimator;
    }
    
    /**
     * Clean up old remaining_kwh records that have a stuck/stale value.
     * Called after PHEV capacity is correctly detected to fix historical data.
     * Updates records where remaining_kwh doesn't match SOC x nominal within 30%.
     */
    public void fixStaleRemainingKwh(double nominalCapacityKwh) {
        if (!isInitialized || connection == null || nominalCapacityKwh <= 0) return;
        try {
            // Update records where remaining_kwh deviates >30% from SOC-derived value
            String sql = "UPDATE " + TABLE_SOC + 
                " SET remaining_kwh = (soc_percent / 100.0) * ? " +
                "WHERE soc_percent > 0 AND remaining_kwh > 0 " +
                "AND ABS(remaining_kwh - (soc_percent / 100.0) * ?) / ((soc_percent / 100.0) * ?) > 0.30";
            try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
                pstmt.setDouble(1, nominalCapacityKwh);
                pstmt.setDouble(2, nominalCapacityKwh);
                pstmt.setDouble(3, nominalCapacityKwh);
                int updated = pstmt.executeUpdate();
                if (updated > 0) {
                    logger.info("Fixed " + updated + " stale remaining_kwh records (nominal=" + 
                        String.format("%.1f", nominalCapacityKwh) + " kWh)");
                }
            }
        } catch (Exception e) {
            logger.error("Failed to fix stale remaining_kwh: " + e.getMessage());
        }
    }
    
    public com.overdrive.app.abrp.SohEstimator getSohEstimator() {
        return sohEstimator;
    }

    /**
     * Conservative rest-state check used to gate the energy-based SOH source.
     *
     * "At rest" means: speed=0, gear in P, AC compressor off, not charging,
     * and (when available) cell voltage spread within 30 mV. Each individual
     * sample is OK to be missing; we treat missing data as "fail-safe not at
     * rest" because populating an active SOH from an indeterminate state is
     * worse than waiting for the next 2-minute tick to give us a clean read.
     *
     * Returns false if BydDataCollector isn't initialized or any of the
     * checks fail. Returns true only when every required signal positively
     * indicates rest.
     */
    private boolean isVehicleAtRest() {
        try {
            com.overdrive.app.byd.BydDataCollector col =
                com.overdrive.app.byd.BydDataCollector.getInstance();
            if (col == null || !col.isInitialized()) return false;

            com.overdrive.app.byd.BydVehicleData vd = col.getData();
            if (vd == null) return false;

            // Speed must be reported and effectively zero.
            if (Double.isNaN(vd.speedKmh) || vd.speedKmh > 0.5) return false;

            // Gear must be Park (1). UNAVAILABLE counts as "not confirmed."
            if (vd.gearMode != 1) return false;

            // Charging would inflate remainingKwh as the pack absorbs current.
            // chargingState convention: 0/1=idle/disconnected, 2+=charging.
            if (vd.chargingState >= 2) return false;

            // AC compressor on → measurable accessory load → reading drifts low.
            // acStartState: 1=on, 0=off, UNAVAILABLE=unknown. Treat unknown as off
            // (the BMS already accounts for the always-on 12V DC-DC drain).
            if (vd.acStartState == 1) return false;

            // Cell spread > 30 mV usually means the BMS is mid-balancing and
            // SOC isn't trustworthy. Skip the check if we don't have both
            // values — most BYD firmwares only expose min/max sample cells,
            // not a true pack-wide spread.
            if (!Double.isNaN(vd.highCellVoltage) && !Double.isNaN(vd.lowCellVoltage)) {
                double spread = vd.highCellVoltage - vd.lowCellVoltage;
                if (spread > 0.030) return false;
            }

            return true;
        } catch (Exception e) {
            logger.debug("isVehicleAtRest: probe failed (" + e.getMessage() + ")");
            return false;
        }
    }
    
    // ==================== BATTERY HEALTH QUERIES ====================
    
    /**
     * Get 12V battery voltage history for charting.
     */
    public JSONArray getBatteryVoltageHistory(int hoursBack, int maxPoints) {
        JSONArray results = new JSONArray();
        if (!isInitialized || connection == null) return results;
        
        try {
            long now = System.currentTimeMillis();
            int hours = Math.min(hoursBack, 168);
            long startTime = now - (hours * 60 * 60 * 1000L);
            long timeRangeMs = hours * 60 * 60 * 1000L;
            long bucketMs = Math.max(120_000L, timeRangeMs / maxPoints);
            
            String sql = 
                "SELECT MIN(timestamp) as t, AVG(voltage_v) as voltage, " +
                "  MAX(is_charging) as charging " +
                "FROM " + TABLE_SOC + " WHERE timestamp >= ? AND voltage_v > 0 " +
                "GROUP BY (timestamp / ?) ORDER BY t ASC LIMIT ?;";
            
            try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
                pstmt.setLong(1, startTime);
                pstmt.setLong(2, bucketMs);
                pstmt.setInt(3, maxPoints);
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        JSONObject row = new JSONObject();
                        row.put("t", rs.getLong("t"));
                        row.put("voltage", Math.round(rs.getDouble("voltage") * 100) / 100.0);
                        row.put("charging", rs.getInt("charging") == 1);
                        results.put(row);
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Failed to get voltage history", e);
            reconnect();
        }
        return results;
    }
    
    /**
     * Get HV battery thermal history for charting.
     */
    public JSONArray getThermalHistory(int hoursBack, int maxPoints) {
        JSONArray results = new JSONArray();
        if (!isInitialized || connection == null) return results;
        
        try {
            long now = System.currentTimeMillis();
            int hours = Math.min(hoursBack, 168);
            long startTime = now - (hours * 60 * 60 * 1000L);
            long timeRangeMs = hours * 60 * 60 * 1000L;
            long bucketMs = Math.max(120_000L, timeRangeMs / maxPoints);
            
            String sql = 
                "SELECT MIN(timestamp) as t, " +
                "  AVG(CASE WHEN hv_temp_high > -999 THEN hv_temp_high END) as temp_high, " +
                "  AVG(CASE WHEN hv_temp_low > -999 THEN hv_temp_low END) as temp_low, " +
                "  AVG(CASE WHEN hv_temp_avg > -999 THEN hv_temp_avg END) as temp_avg, " +
                "  MAX(is_charging) as charging " +
                "FROM " + TABLE_SOC + " WHERE timestamp >= ? " +
                "AND (hv_temp_high > -999 OR hv_temp_low > -999 OR hv_temp_avg > -999) " +
                "GROUP BY (timestamp / ?) ORDER BY t ASC LIMIT ?;";
            
            try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
                pstmt.setLong(1, startTime);
                pstmt.setLong(2, bucketMs);
                pstmt.setInt(3, maxPoints);
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        JSONObject row = new JSONObject();
                        row.put("t", rs.getLong("t"));
                        double h = rs.getDouble("temp_high");
                        boolean hNull = rs.wasNull();
                        double l = rs.getDouble("temp_low");
                        boolean lNull = rs.wasNull();
                        double a = rs.getDouble("temp_avg");
                        boolean aNull = rs.wasNull();
                        if (!hNull) row.put("high", Math.round(h * 10) / 10.0);
                        if (!lNull) row.put("low", Math.round(l * 10) / 10.0);
                        if (!aNull) row.put("avg", Math.round(a * 10) / 10.0);
                        row.put("charging", rs.getInt("charging") == 1);
                        results.put(row);
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Failed to get thermal history", e);
            reconnect();
        }
        return results;
    }
    
    /**
     * Get battery health report — current state + historical stats.
     */
    public JSONObject getBatteryHealthReport(int hoursBack, int maxPoints) {
        JSONObject report = new JSONObject();
        
        try {
            VehicleDataMonitor monitor = VehicleDataMonitor.getInstance();
            
            // Current live data
            JSONObject current = new JSONObject();
            
            BatteryPowerData powerData = monitor.getBatteryPower();
            if (powerData != null) {
                current.put("voltage12v", powerData.voltageVolts);
                current.put("voltageStatus", powerData.getHealthStatus());
            }
            
            BatterySocData socData = monitor.getBatterySoc();
            if (socData != null) {
                current.put("soc", socData.socPercent);
            }
            
            BatteryThermalData thermalData = monitor.getBatteryThermal();
            if (thermalData != null && thermalData.hasData()) {
                if (!Double.isNaN(thermalData.highestTempC)) current.put("tempHigh", thermalData.highestTempC);
                if (!Double.isNaN(thermalData.lowestTempC)) current.put("tempLow", thermalData.lowestTempC);
                if (!Double.isNaN(thermalData.averageTempC)) current.put("tempAvg", thermalData.averageTempC);
                if (!Double.isNaN(thermalData.deltaC)) current.put("tempDelta", thermalData.deltaC);
                current.put("thermalStatus", thermalData.getStatus());
            }
            
            com.overdrive.app.abrp.SohEstimator sohEst = getSohEstimator();
            if (sohEst != null && sohEst.hasEstimate()) {
                current.put("soh", Math.round(sohEst.getCurrentSoh() * 10) / 10.0);
                current.put("estimatedCapacityKwh", Math.round(sohEst.getEstimatedCapacityKwh() * 10) / 10.0);
                current.put("nominalCapacityKwh", sohEst.getNominalCapacityKwh());
            } else {
                // Fallback: read persisted SOH from file if estimator reference not wired yet
                logger.info("SOH estimator " + (sohEst == null ? "is null" : "has no estimate") + " for health report, trying persisted file fallback");
                try {
                    java.io.File sohFile = new java.io.File("/data/local/tmp/abrp_soh_estimate.properties");
                    if (sohFile.exists()) {
                        java.util.Properties props = new java.util.Properties();
                        try (java.io.FileInputStream fis = new java.io.FileInputStream(sohFile)) {
                            props.load(fis);
                        }
                        String sohStr = props.getProperty("soh_percent");
                        if (sohStr != null) {
                            double soh = Double.parseDouble(sohStr);
                            if (soh > 0 && soh <= 100) {
                                current.put("soh", Math.round(soh * 10) / 10.0);
                                logger.info("SOH from persisted file fallback (health report): " + soh + "%");
                            }
                        }
                    } else {
                        logger.info("SOH persisted file not found for health report");
                    }
                } catch (Exception e) {
                    logger.debug("Failed to read persisted SOH for health report: " + e.getMessage());
                }
            }
            
            double remainingKwh = monitor.getBatteryRemainPowerKwh();
            if (remainingKwh > 0) current.put("remainingKwh", Math.round(remainingKwh * 10) / 10.0);
            
            DrivingRangeData rangeData = monitor.getDrivingRange();
            if (rangeData != null) current.put("rangeKm", rangeData.elecRangeKm);
            
            report.put("current", current);
            
            // Historical data
            report.put("voltageHistory", getBatteryVoltageHistory(hoursBack, maxPoints));
            report.put("thermalHistory", getThermalHistory(hoursBack, maxPoints));
            
            // 12V voltage stats
            if (isInitialized && connection != null) {
                long startTime = System.currentTimeMillis() - (hoursBack * 60 * 60 * 1000L);
                String statsSql = "SELECT MIN(voltage_v), MAX(voltage_v), AVG(voltage_v) " +
                    "FROM " + TABLE_SOC + " WHERE timestamp >= ? AND voltage_v > 0;";
                try (PreparedStatement pstmt = connection.prepareStatement(statsSql)) {
                    pstmt.setLong(1, startTime);
                    try (ResultSet rs = pstmt.executeQuery()) {
                        if (rs.next()) {
                            JSONObject voltStats = new JSONObject();
                            voltStats.put("min", Math.round(rs.getDouble(1) * 100) / 100.0);
                            voltStats.put("max", Math.round(rs.getDouble(2) * 100) / 100.0);
                            voltStats.put("avg", Math.round(rs.getDouble(3) * 100) / 100.0);
                            report.put("voltageStats", voltStats);
                        }
                    }
                }
                
                // SOH history (last N samples where soh > 0)
                String sohSql = "SELECT MIN(timestamp) as t, AVG(soh_percent) as soh " +
                    "FROM " + TABLE_SOC + " WHERE timestamp >= ? AND soh_percent > 0 " +
                    "GROUP BY (timestamp / ?) ORDER BY t ASC LIMIT ?;";
                long sohBucketMs = Math.max(120_000L, (long)(hoursBack) * 60 * 60 * 1000L / maxPoints);
                JSONArray sohHistory = new JSONArray();
                try (PreparedStatement pstmt = connection.prepareStatement(sohSql)) {
                    pstmt.setLong(1, startTime);
                    pstmt.setLong(2, sohBucketMs);
                    pstmt.setInt(3, maxPoints);
                    try (ResultSet rs = pstmt.executeQuery()) {
                        while (rs.next()) {
                            JSONObject row = new JSONObject();
                            row.put("t", rs.getLong("t"));
                            row.put("soh", Math.round(rs.getDouble("soh") * 10) / 10.0);
                            sohHistory.put(row);
                        }
                    }
                }
                report.put("sohHistory", sohHistory);
            }
            
            report.put("hoursBack", hoursBack);
            report.put("timestamp", System.currentTimeMillis());
            
        } catch (Exception e) {
            logger.error("Failed to create battery health report", e);
        }
        
        return report;
    }
    
    // ==================== MAINTENANCE ====================

    /**
     * Wipes every row from soc_history and charging_sessions. Used by the
     * user-initiated "Reset Data" feature to clear SOC graphs and 12V history.
     * Returns total rows deleted, or -1 on failure. Tables remain so inserts
     * continue to work.
     */
    public long resetAll() {
        if (!isInitialized || connection == null) return -1;
        long total = 0;
        try (Statement stmt = connection.createStatement()) {
            int n1 = stmt.executeUpdate("DELETE FROM " + TABLE_SOC);
            int n2 = stmt.executeUpdate("DELETE FROM " + TABLE_CHARGING);
            int n3 = 0;
            try {
                n3 = stmt.executeUpdate("DELETE FROM " + TABLE_ACC_EVENTS);
            } catch (Exception ignored) {
                // Table may not exist on very old installs that haven't yet
                // run the migration — ignore so SOC/charging still wipe.
            }
            total = n1 + n2 + n3;
            logger.info("resetAll: cleared " + n1 + " from " + TABLE_SOC
                + ", " + n2 + " from " + TABLE_CHARGING
                + ", " + n3 + " from " + TABLE_ACC_EVENTS);
            return total;
        } catch (Exception e) {
            logger.error("resetAll failed", e);
            return -1;
        }
    }

    private void cleanupOldData() {
        if (!isInitialized || connection == null) return;
        
        try {
            long cutoff = System.currentTimeMillis() - (RETENTION_DAYS * 24 * 60 * 60 * 1000L);
            
            String deleteSocSql = "DELETE FROM " + TABLE_SOC + " WHERE timestamp < ?;";
            try (PreparedStatement pstmt = connection.prepareStatement(deleteSocSql)) {
                pstmt.setLong(1, cutoff);
                int deleted = pstmt.executeUpdate();
                if (deleted > 0) {
                    logger.info("Cleaned up " + deleted + " old SOC records");
                }
            }
            
            String deleteChargingSql = "DELETE FROM " + TABLE_CHARGING + " WHERE start_time < ?;";
            try (PreparedStatement pstmt = connection.prepareStatement(deleteChargingSql)) {
                pstmt.setLong(1, cutoff);
                pstmt.executeUpdate();
            }
            
        } catch (Exception e) {
            logger.error("Failed to cleanup old data", e);
        }
    }
    
    /**
     * Get database file size.
     */
    public long getDatabaseSize() {
        try {
            java.io.File dbFile = new java.io.File(DB_PATH + ".mv.db");
            return dbFile.exists() ? dbFile.length() : 0;
        } catch (Exception e) {
            return 0;
        }
    }
    
    /**
     * Get record count.
     */
    public int getRecordCount() {
        if (!isInitialized || connection == null) return 0;
        
        try {
            String sql = "SELECT COUNT(*) FROM " + TABLE_SOC + ";";
            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery(sql)) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        } catch (Exception e) {
            logger.error("Failed to get record count", e);
        }
        return 0;
    }
    
    public boolean isRunning() {
        return isRunning;
    }
    
    public boolean isAvailable() {
        return isInitialized && connection != null;
    }

    // ==================== ACC EVENTS ====================

    /**
     * Record a single ACC transition. Called synchronously from
     * CameraDaemon.onAccStateChanged() so the snapshot is captured BEFORE
     * the daemon tears down BydDataCollector.
     *
     * @param eventType "ON" or "OFF" (case-insensitive — normalized to upper).
     * @param data the BydVehicleData snapshot at the moment of the event.
     *             Pass null if the snapshot is unavailable; nullable fields
     *             will be persisted as SQL NULL.
     *
     * Best-effort: any exception is caught and logged; never propagates.
     */
    public void recordAccEvent(String eventType, com.overdrive.app.byd.BydVehicleData data) {
        try {
            if (eventType == null) return;
            String type = eventType.trim().toUpperCase();
            if (!"ON".equals(type) && !"OFF".equals(type)) return;

            if (!isAvailable()) {
                logger.debug("recordAccEvent skipped: DB not available (type=" + type + ")");
                return;
            }

            long now = System.currentTimeMillis();

            // Pull snapshot fields defensively. Use SQL NULL when the value
            // is missing or sentinel — never a fake zero.
            Double socPercent = null;
            Double remainingKwh = null;
            Double voltageV = null;
            Integer rangeKm = null;
            if (data != null) {
                if (!Double.isNaN(data.socPercent) && data.socPercent >= 0 && data.socPercent <= 100) {
                    socPercent = data.socPercent;
                }
                if (!Double.isNaN(data.remainKwh) && data.remainKwh > 0) {
                    remainingKwh = data.remainKwh;
                }
                if (!Double.isNaN(data.voltage12v) && data.voltage12v > 0) {
                    voltageV = data.voltage12v;
                }
                if (data.elecRangeKm != com.overdrive.app.byd.BydVehicleData.UNAVAILABLE
                        && data.elecRangeKm >= 0) {
                    rangeKm = data.elecRangeKm;
                }
            }

            String sql = "INSERT INTO " + TABLE_ACC_EVENTS +
                " (timestamp, event_type, soc_percent, remaining_kwh, voltage_v, range_km) " +
                "VALUES (?, ?, ?, ?, ?, ?);";

            try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
                pstmt.setLong(1, now);
                pstmt.setString(2, type);
                if (socPercent != null) pstmt.setDouble(3, socPercent);
                else pstmt.setNull(3, java.sql.Types.REAL);
                if (remainingKwh != null) pstmt.setDouble(4, remainingKwh);
                else pstmt.setNull(4, java.sql.Types.REAL);
                if (voltageV != null) pstmt.setDouble(5, voltageV);
                else pstmt.setNull(5, java.sql.Types.REAL);
                if (rangeKm != null) pstmt.setInt(6, rangeKm);
                else pstmt.setNull(6, java.sql.Types.INTEGER);
                pstmt.executeUpdate();
            }

            logger.debug("ACC event recorded: " + type +
                " soc=" + (socPercent == null ? "null" : socPercent) +
                " kWh=" + (remainingKwh == null ? "null" : remainingKwh));
        } catch (Exception e) {
            // Never propagate — must not break the daemon's ACC state machine.
            logger.error("recordAccEvent failed: " + e.getMessage(), e);
        }
    }

    /**
     * Compute the most recent completed park-and-return cycle.
     *
     * Algorithm (NO inference, only real events):
     *   1. Find the most recent OFF event in the table.
     *   2. Find the most recent ON event whose timestamp > that OFF's timestamp.
     *      (i.e. the matching return event).
     *   3. If both exist with usable SOC values, compute delta and return it.
     *   4. Anything else → return null.
     *
     * Edge cases (ALL return null, never fake data):
     *   - DB unavailable / not initialized.
     *   - No OFF events ever recorded (just installed, never parked yet).
     *   - Most recent OFF has no subsequent ON (currently parked — delta unknown).
     *   - Either bracket has soc_percent IS NULL or NaN.
     *   - soc_percent &lt; 0 or &gt; 100 on either bracket.
     *   - |deltaSoc| &gt; 100 (sanity floor for bad data).
     *   - The OFF was older than `maxAgeHours` hours ago (stale).
     *
     * Returned JSON shape on success:
     *   { offTs, onTs, idleMinutes, deltaSoc, deltaKwh?, isCharging }
     *   deltaKwh present only when both samples have remaining_kwh &gt; 0.
     *   isCharging=true if deltaSoc &gt; 0.5 (battery gained energy parked = plugged in).
     */
    public JSONObject getLastParkingDelta(int maxAgeHours) {
        // Edge case: DB unavailable / not initialized.
        if (!isAvailable()) return null;
        if (maxAgeHours <= 0) return null;
        try {
            // Step 1: find the most recent OFF event.
            long offTs;
            Double offSoc;
            Double offKwh;
            String offSql = "SELECT timestamp, soc_percent, remaining_kwh " +
                "FROM " + TABLE_ACC_EVENTS + " WHERE event_type = 'OFF' " +
                "ORDER BY timestamp DESC LIMIT 1";
            try (PreparedStatement pstmt = connection.prepareStatement(offSql)) {
                try (ResultSet rs = pstmt.executeQuery()) {
                    // Edge case: no OFF events ever recorded.
                    if (!rs.next()) return null;
                    offTs = rs.getLong(1);
                    double s = rs.getDouble(2);
                    offSoc = rs.wasNull() ? null : s;
                    double k = rs.getDouble(3);
                    offKwh = rs.wasNull() ? null : k;
                }
            }

            // Edge case: OFF older than maxAgeHours → stale, skip.
            long now = System.currentTimeMillis();
            long ageMs = now - offTs;
            long maxAgeMs = (long) maxAgeHours * 60L * 60L * 1000L;
            if (ageMs < 0 || ageMs > maxAgeMs) return null;

            // Step 2: find the most recent ON event after that OFF.
            long onTs;
            Double onSoc;
            Double onKwh;
            String onSql = "SELECT timestamp, soc_percent, remaining_kwh " +
                "FROM " + TABLE_ACC_EVENTS + " WHERE event_type = 'ON' AND timestamp > ? " +
                "ORDER BY timestamp DESC LIMIT 1";
            try (PreparedStatement pstmt = connection.prepareStatement(onSql)) {
                pstmt.setLong(1, offTs);
                try (ResultSet rs = pstmt.executeQuery()) {
                    // Edge case: most recent OFF has no subsequent ON
                    // (currently parked — delta unknown).
                    if (!rs.next()) return null;
                    onTs = rs.getLong(1);
                    double s = rs.getDouble(2);
                    onSoc = rs.wasNull() ? null : s;
                    double k = rs.getDouble(3);
                    onKwh = rs.wasNull() ? null : k;
                }
            }

            // Edge case: either bracket has soc_percent IS NULL or NaN.
            if (offSoc == null || onSoc == null) return null;
            if (Double.isNaN(offSoc) || Double.isNaN(onSoc)) return null;

            // Edge case: soc out of valid range on either bracket.
            if (offSoc < 0 || offSoc > 100) return null;
            if (onSoc < 0 || onSoc > 100) return null;

            double deltaSoc = onSoc - offSoc;

            // Edge case: |deltaSoc| > 100 sanity floor for bad data.
            if (Double.isNaN(deltaSoc) || Math.abs(deltaSoc) > 100) return null;

            // Edge case: onTs must be after offTs (already enforced by query
            // but defend against clock skew on the host).
            if (onTs <= offTs) return null;

            JSONObject out = new JSONObject();
            out.put("offTs", offTs);
            out.put("onTs", onTs);
            out.put("idleMinutes", (onTs - offTs) / 60_000L);
            out.put("deltaSoc", Math.round(deltaSoc * 10) / 10.0);

            // deltaKwh present only when both samples have remaining_kwh > 0.
            if (offKwh != null && onKwh != null
                    && !Double.isNaN(offKwh) && !Double.isNaN(onKwh)
                    && offKwh > 0 && onKwh > 0) {
                double deltaKwh = onKwh - offKwh;
                if (!Double.isNaN(deltaKwh) && Math.abs(deltaKwh) < 500) {
                    out.put("deltaKwh", Math.round(deltaKwh * 10) / 10.0);
                }
            }

            // isCharging: positive SOC delta > 0.5 means the pack gained
            // energy while parked — i.e. plugged in.
            out.put("isCharging", deltaSoc > 0.5);

            return out;
        } catch (Exception e) {
            logger.debug("getLastParkingDelta failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * Get the most recent completed charging session within the last `hoursBack`
     * hours. Returns null if none, if values are garbage, or if DB is closed.
     *
     * Returned JSON shape:
     *  { startTime, endTime, durationMinutes, energyAddedKwh, startSoc, endSoc }
     */
    public JSONObject getMostRecentCompletedChargingSession(int hoursBack) {
        if (!isAvailable()) return null;
        if (hoursBack <= 0) return null;
        try {
            long cutoff = System.currentTimeMillis() - (hoursBack * 60L * 60L * 1000L);
            String sql = "SELECT start_time, end_time, start_soc, end_soc, energy_added_kwh " +
                "FROM " + TABLE_CHARGING +
                " WHERE end_time IS NOT NULL AND start_time >= ? " +
                "ORDER BY end_time DESC LIMIT 1";
            try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
                pstmt.setLong(1, cutoff);
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (!rs.next()) return null;
                    long start = rs.getLong(1);
                    long end = rs.getLong(2);
                    double startSoc = rs.getDouble(3);
                    double endSoc = rs.getDouble(4);
                    double energy = rs.getDouble(5);
                    if (end <= start) return null;
                    if (Double.isNaN(energy) || energy <= 0 || energy > 500) return null;
                    long durationMin = (end - start) / 60_000L;
                    if (durationMin <= 0 || durationMin > 7 * 24 * 60) return null;
                    JSONObject out = new JSONObject();
                    out.put("startTime", start);
                    out.put("endTime", end);
                    out.put("durationMinutes", durationMin);
                    out.put("energyAddedKwh", Math.round(energy * 10) / 10.0);
                    out.put("startSoc", Math.round(startSoc * 10) / 10.0);
                    out.put("endSoc", Math.round(endSoc * 10) / 10.0);
                    return out;
                }
            }
        } catch (Exception e) {
            logger.debug("getMostRecentCompletedChargingSession failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * Compute the SOC change rate in %/hour from recent samples (last 10 minutes).
     * Returns a positive value if SOC is rising (charging), negative if falling,
     * or 0 if insufficient data, samples are too close together, or too old.
     */
    public double getSocChangeRatePerHour() {
        if (!isAvailable()) return 0;
        try {
            // Only use samples from the last 10 minutes to avoid stale cross-session data
            long cutoff = System.currentTimeMillis() - 10 * 60 * 1000;
            java.sql.PreparedStatement stmt = connection.prepareStatement(
                "SELECT timestamp, soc_percent FROM " + TABLE_SOC +
                " WHERE timestamp > ? ORDER BY timestamp DESC LIMIT 2");
            stmt.setLong(1, cutoff);
            java.sql.ResultSet rs = stmt.executeQuery();
            double soc1 = Double.NaN, soc2 = Double.NaN;
            long t1 = 0, t2 = 0;
            if (rs.next()) { t1 = rs.getLong(1); soc1 = rs.getDouble(2); }
            if (rs.next()) { t2 = rs.getLong(1); soc2 = rs.getDouble(2); }
            rs.close();
            stmt.close();

            if (Double.isNaN(soc1) || Double.isNaN(soc2)) return 0;
            long deltaMs = t1 - t2;
            if (deltaMs < 60_000) return 0;  // Need at least 60s between samples
            double deltaSoc = soc1 - soc2;
            if (Math.abs(deltaSoc) < 0.1) return 0;  // SOC hasn't changed meaningfully
            double deltaHours = deltaMs / 3_600_000.0;
            return deltaSoc / deltaHours;
        } catch (Exception e) {
            return 0;
        }
    }
}
