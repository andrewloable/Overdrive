package net.bladewatch.app.trips;

import android.content.Context;

import net.bladewatch.app.logging.DaemonLogger;
import net.bladewatch.app.monitor.GearMonitor;
import net.bladewatch.app.monitor.VehicleDataMonitor;
import net.bladewatch.app.storage.StorageManager;
import net.bladewatch.app.telemetry.TelemetryDataCollector;

import java.io.File;
import java.util.List;

/**
 * Top-level coordinator for Trip Analytics & Driving DNA.
 * Single entry point for CameraDaemon integration.
 *
 * Lifecycle:
 *   CameraDaemon.main() → init(context, telemetryDataCollector)
 *   CameraDaemon.shutdown() → shutdown()
 *   GearMonitor callback → onGearChanged(newGear)
 */
public class TripAnalyticsManager {

    private static final DaemonLogger logger = DaemonLogger.getInstance("TripAnalyticsManager");

    private TripConfig config;
    private TripDatabase database;
    private TripDetector detector;
    private TripTelemetryRecorder recorder;
    private TripScoreEngine scoreEngine;
    private RangeEstimator rangeEstimator;

    private TelemetryDataCollector telemetryDataCollector;

    private volatile boolean enabled = false;
    private volatile boolean initialized = false;

    // ==================== LIFECYCLE ====================

    /**
     * Initialize trip analytics. Called from CameraDaemon.main().
     *
     * 1. Load TripConfig from properties file
     * 2. If enabled: initialize TripDatabase, TripDetector, TripTelemetryRecorder,
     *    TripScoreEngine, RangeEstimator
     * 3. Set TripDetector listener to handle trip start/end events
     * 4. Ensure StorageManager.getInstance().getTripsDir() exists
     * 5. Log initialization status
     */
    public void init(Context context, TelemetryDataCollector telemetryDataCollector) {
        this.telemetryDataCollector = telemetryDataCollector;

        // 1. Load config
        config = new TripConfig();
        config.load();

        // 4. Ensure trips directory exists
        File tripsDir = StorageManager.getInstance().getTripsDir();
        if (tripsDir != null && !tripsDir.exists()) {
            boolean created = tripsDir.mkdirs();
            logger.info("Trips directory created: " + tripsDir.getAbsolutePath()
                    + " (success=" + created + ")");
        }

        // 2. If enabled, initialize all components
        if (config.isEnabled()) {
            initComponents();
        }

        initialized = true;

        // 5. Log status
        logger.info("TripAnalyticsManager initialized — enabled=" + config.isEnabled());
    }

    /**
     * Shut down trip analytics. Called from CameraDaemon.shutdown().
     *
     * 1. Finalize active trip via TripDetector
     * 2. Close TripDatabase
     * 3. Log shutdown
     */
    public void shutdown() {
        logger.info("Shutting down TripAnalyticsManager");

        // 1. Finalize active trip
        if (detector != null) {
            detector.finalizeActiveTrip();
        }

        // 2. Close database
        if (database != null) {
            database.close();
        }

        enabled = false;
        initialized = false;

        // 3. Log shutdown
        logger.info("TripAnalyticsManager shut down");
    }

    // ==================== GEAR FORWARDING ====================

    /**
     * Forward gear change to TripDetector if enabled.
     * Called from CameraDaemon.onGearChanged().
     */
    public void onGearChanged(int newGear) {
        if (enabled && detector != null) {
            detector.onGearChanged(newGear);
        }
    }

    // ==================== ACC LIFECYCLE ====================

    /**
     * Called when ACC goes OFF (car powering down / entering sentry mode).
     * Finalizes any active trip immediately — the gear change to P may not
     * fire reliably during power-down, so this is a safety net.
     */
    public void onAccOff() {
        if (!enabled || detector == null) return;
        if (detector.isTripActive()) {
            logger.info("ACC OFF — finalizing active trip");
            detector.finalizeActiveTrip();
        }
    }

    /**
     * Called when ACC comes ON (car powering up).
     * Probe current gear and auto-start trip if already in a driving gear.
     * This handles the case where gear changed to D before the GearMonitor
     * listener was re-registered, or where the gear event was lost during
     * the ACC transition.
     */
    public void onAccOn() {
        if (!enabled) return;
        logger.info("ACC ON — trip detection ready (waiting for gear D/R)");

        // Safety net: probe current gear in case we missed the gear change event
        // during the ACC OFF→ON transition
        try {
            int currentGear = GearMonitor.getInstance().getCurrentGear();
            if (currentGear != GearMonitor.GEAR_P && detector != null && !detector.isTripActive()) {
                logger.info("ACC ON + gear already " + GearMonitor.gearToString(currentGear)
                        + " — auto-starting trip");
                detector.onGearChanged(currentGear);
            }
        } catch (Exception e) {
            logger.warn("ACC ON gear probe failed: " + e.getMessage());
        }
    }

    // ==================== RUNTIME CONFIG ====================

    /**
     * Enable or disable trip analytics at runtime.
     *
     * If disabling while a trip is active, finalize the trip first.
     * If enabling while gear != P, start trip detection immediately.
     */
    public void onConfigChanged(boolean newEnabled) {
        logger.info("onConfigChanged: " + enabled + " → " + newEnabled);

        // Trip analytics is always on — there is no off switch. Ignore any
        // request to disable it; if components somehow aren't up yet, bring
        // them up rather than tearing them down.
        if (!newEnabled) {
            logger.info("Ignoring disable request — trip analytics is always on");
            if (!enabled) {
                initComponents();
            }
            return;
        }

        if (newEnabled == enabled) {
            return; // No change
        }

        {
            // Enabling
            config.setEnabled(true);
            config.save();

            if (!enabled) {
                initComponents();
            }

            // If gear is not P, trigger trip detection
            int currentGear = GearMonitor.getInstance().getCurrentGear();
            if (currentGear != GearMonitor.GEAR_P && detector != null) {
                logger.info("Enabling while gear=" + GearMonitor.gearToString(currentGear)
                        + " — forwarding gear to detector");
                detector.onGearChanged(currentGear);
            }

            logger.info("Trip analytics enabled");
        }
    }

    // ==================== ACCESSORS ====================

    public TripDatabase getDatabase() {
        return database;
    }

    public RangeEstimator getRangeEstimator() {
        return rangeEstimator;
    }

    public TripConfig getConfig() {
        return config;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Check if a trip is currently being tracked (ACTIVE or PARK_PENDING).
     */
    public boolean isTripActive() {
        return enabled && detector != null && detector.isTripActive();
    }

    /**
     * Get the active trip record, or null if no trip is active.
     */
    public TripRecord getActiveTrip() {
        return (detector != null) ? detector.getActiveTrip() : null;
    }

    /**
     * Update the TelemetryDataCollector reference after late initialization.
     * Called by CameraDaemon once TelemetryDataCollector is ready (after GPU init delay).
     */
    public void setTelemetryDataCollector(TelemetryDataCollector collector) {
        this.telemetryDataCollector = collector;
        if (recorder != null) {
            recorder.setTelemetryDataCollector(collector);
        }
    }

    // ==================== SYNC / RECONCILE ====================

    /**
     * Manual trips sync: prune trips whose telemetry file is gone, then
     * re-index any orphan telemetry files into basic trip rows (start/end,
     * duration, distance + GPS endpoints derived from the samples). Per the
     * agreed scope this does NOT recompute DNA/efficiency scores or SoC — the
     * telemetry samples don't carry SoC, so those stay 0 for reconstructed
     * trips.
     *
     * <p>Synchronized for single-flight. The trip DB connection is otherwise
     * daemon-thread confined; this runs on an HTTP worker, the same concurrency
     * posture as the existing read endpoints.
     *
     * @return {success, added, removed, total} or {success:false, error:...}.
     */
    public synchronized org.json.JSONObject reconcileTrips() {
        java.util.Map<String, Object> m = new java.util.HashMap<>();
        if (database == null || !database.isAvailable()) {
            m.put("success", false);
            m.put("error", "trips_unavailable");
            return new org.json.JSONObject(m);
        }
        try {
            int removed = database.deleteTripsWithMissingTelemetry();

            java.util.Set<String> known = new java.util.HashSet<>(database.getAllTelemetryPaths());
            int added = 0;
            for (File dir : StorageManager.getInstance().getAllTripsDirs()) {
                if (dir == null || !dir.exists() || !dir.canRead()) continue;
                File[] files = dir.listFiles((d, name) -> name.endsWith(".jsonl.gz"));
                if (files == null) continue;
                for (File f : files) {
                    if (!f.canRead() || f.length() <= 0) continue;
                    if (known.contains(f.getAbsolutePath())) continue;
                    List<TelemetrySample> samples = TelemetryStore.readFromFile(f);
                    if (samples == null || samples.isEmpty()) continue;
                    if (samples.size() == 1) {
                        // A single-sample file cannot produce a valid trip (no distance, no duration).
                        // Skip the DB insert; leave the file on disk.
                        logger.warn("Skipping single-sample telemetry file: " + f.getName());
                        continue;
                    }
                    TripRecord t = buildBasicTrip(samples, f.getAbsolutePath());
                    if (database.insertTrip(t) > 0) {
                        added++;
                        known.add(f.getAbsolutePath());
                    }
                }
            }

            int total = database.getTripCount();
            m.put("success", true);
            m.put("added", added);
            m.put("removed", removed);
            m.put("total", total);
            logger.info("Trips sync: +" + added + " -" + removed + " (total=" + total + ")");
        } catch (Exception e) {
            logger.error("Trips reconcile failed", e);
            m.put("success", false);
            m.put("error", e.getMessage());
        }
        return new org.json.JSONObject(m);
    }

    /**
     * Reconstruct a minimal TripRecord from telemetry samples — start/end,
     * duration, GPS-derived distance and endpoints, speed stats. No scores or
     * SoC (not derivable from telemetry samples).
     */
    private TripRecord buildBasicTrip(List<TelemetrySample> samples, String telemetryPath) {
        TripRecord t = new TripRecord();
        TelemetrySample first = samples.get(0);
        TelemetrySample last = samples.get(samples.size() - 1);
        t.startTime = first.timestampMs;
        t.endTime = last.timestampMs;
        t.durationSeconds = (int) Math.max(0, (t.endTime - t.startTime) / 1000);

        double distanceKm = 0;
        double lastLat = 0, lastLon = 0;
        boolean hasLast = false;
        int maxSpeed = 0;
        long speedSum = 0, speedCount = 0;
        double startLat = 0, startLon = 0, endLat = 0, endLon = 0;
        boolean haveStart = false;
        for (TelemetrySample s : samples) {
            if (s.speedKmh > maxSpeed) maxSpeed = s.speedKmh;
            speedSum += s.speedKmh;
            speedCount++;
            if (s.lat != 0 && s.lon != 0) {
                if (!haveStart) { startLat = s.lat; startLon = s.lon; haveStart = true; }
                endLat = s.lat; endLon = s.lon;
                if (hasLast) {
                    double d = haversineKm(lastLat, lastLon, s.lat, s.lon);
                    if (d < 0.5) distanceKm += d;  // filter GPS jumps (matches recorder)
                }
                lastLat = s.lat; lastLon = s.lon; hasLast = true;
            }
        }
        t.distanceKm = distanceKm;
        t.maxSpeedKmh = maxSpeed;
        t.avgSpeedKmh = speedCount > 0 ? (double) speedSum / speedCount : 0;
        t.startLat = startLat;
        t.startLon = startLon;
        t.endLat = endLat;
        t.endLon = endLon;
        t.telemetryFilePath = telemetryPath;
        return t;
    }

    private static double haversineKm(double lat1, double lon1, double lat2, double lon2) {
        double R = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    // ==================== PRIVATE ====================

    /**
     * Initialize all trip analytics components and wire up the TripDetector listener.
     */
    private void initComponents() {
        // Database
        database = new TripDatabase();
        database.init();
        
        // Clean up orphaned trips from previous daemon crashes
        // (trips with no end_time that are older than 24 hours)
        try {
            long cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L;
            database.deleteOrphanedTrips(cutoff);
        } catch (Exception e) {
            logger.warn("Orphaned trip cleanup failed: " + e.getMessage());
        }
        
        // Backfill route_id for existing trips (idempotent — skips already-assigned trips)
        database.backfillRouteIds();

        // Detector
        detector = new TripDetector();
        detector.setListener(new TripDetector.TripListener() {
            @Override
            public void onTripStarted(TripRecord trip) {
                handleTripStarted(trip);
            }

            @Override
            public void onTripEnded(TripRecord trip) {
                handleTripEnded(trip);
            }

            @Override
            public void onTripDiscarded(TripRecord trip, String reason) {
                handleTripDiscarded(trip, reason);
            }

            @Override
            public double getRecordedDistanceKm() {
                return recorder != null ? recorder.getTotalDistanceKm() : 0;
            }
        });

        // Recorder
        recorder = new TripTelemetryRecorder(telemetryDataCollector);

        // Score engine
        scoreEngine = new TripScoreEngine();

        // Range estimator
        rangeEstimator = new RangeEstimator(database);

        enabled = true;
        logger.info("Trip analytics components initialized");
    }

    /**
     * Handle trip started event from TripDetector.
     * Start the TripTelemetryRecorder using startTime as the trip ID.
     */
    private void handleTripStarted(TripRecord trip) {
        logger.info("Trip started at " + trip.startTime);

        // Ensure TelemetryDataCollector is polling so we get fresh data
        // It may not be polling if no recording/overlay is active
        if (telemetryDataCollector != null) {
            try {
                telemetryDataCollector.startPolling();
                logger.info("TelemetryDataCollector polling ensured for trip recording");
            } catch (Exception e) {
                logger.warn("Failed to start TelemetryDataCollector polling: " + e.getMessage());
            }
        }

        if (recorder != null) {
            // Use startTime as the unique trip identifier for the recorder
            // (before DB insert gives us the auto-increment ID)
            recorder.startRecording(trip.startTime);
        }
    }

    /**
     * Handle trip ended event from TripDetector.
     *
     * 1. Stop recorder, get samples
     * 2. Compute scores via TripScoreEngine
     * 3. Populate TripRecord with recorder stats (maxSpeed, avgSpeed)
     * 4. Insert into TripDatabase
     * 5. Update rollups
     * 6. Update range estimator
     * 7. Update telemetry file path in the record
     */
    private void handleTripEnded(TripRecord trip) {
        logger.info("Trip ended — duration=" + trip.durationSeconds + "s, distance="
                + trip.distanceKm + "km");

        // Release telemetry polling ref (acquired in handleTripStarted)
        if (telemetryDataCollector != null) {
            telemetryDataCollector.stopPolling();
        }

        String telemetryPath = null;

        // 1. Stop recorder, get samples
        if (recorder != null) {
            telemetryPath = recorder.stopRecording();
            List<TelemetrySample> samples = recorder.getSamplesForScoring();

            // 2. Compute scores
            if (scoreEngine != null && samples != null && !samples.isEmpty()) {
                scoreEngine.computeSummary(trip, samples);

                // Compute consistency using recent trips from DB
                if (database != null) {
                    List<TripRecord> recentTrips = database.getTrips(30, 10);
                    // Use energyPerKm when available, fall back to efficiencySocPerKm
                    double currentEff = trip.energyPerKm > 0 ? trip.energyPerKm : trip.efficiencySocPerKm;
                    trip.consistencyScore = scoreEngine.computeConsistency(currentEff, recentTrips);
                }
            }

            // 3. Populate recorder stats
            trip.maxSpeedKmh = recorder.getMaxSpeedKmh();
            trip.avgSpeedKmh = recorder.getAvgSpeedKmh();
        }

        // Snapshot electricity rate and compute trip cost.
        // Use the BYD-local nominal pack capacity for energy calculation.
        if (config != null) {
            trip.electricityRate = config.getElectricityRate();
            trip.currency = config.getCurrency();

            double energyUsed = trip.getEnergyUsedKwh();

            // If BMS kWh readings weren't available, estimate from SoC delta
            // using the BYD-local nominal pack capacity. No SoH degradation
            // source is available, so the pack is treated as healthy.
            if (energyUsed <= 0 && trip.socStart > 0 && trip.socEnd > 0 && trip.socStart > trip.socEnd) {
                try {
                    double nominalKwh = VehicleDataMonitor.getInstance().getNominalCapacityKwh();
                    if (nominalKwh > 0) {
                        energyUsed = ((trip.socStart - trip.socEnd) / 100.0) * nominalKwh;
                        logger.info(String.format("Energy estimated from SoC: %.1f%% → %.1f%% = %.2f kWh (nominal=%.1f)",
                                trip.socStart, trip.socEnd, energyUsed, nominalKwh));
                    }
                } catch (Exception e) {
                    logger.warn("Nominal capacity not available for energy estimation: " + e.getMessage());
                }
            }

            // Store computed energy for the database (so future reads don't need to re-estimate)
            if (energyUsed > 0 && trip.distanceKm > 0) {
                trip.energyPerKm = energyUsed / trip.distanceKm;
            }
            
            if (energyUsed > 0 && trip.electricityRate > 0) {
                trip.tripCost = energyUsed * trip.electricityRate;
                logger.info(String.format("Trip cost: %.2f kWh × %s%.2f = %s%.2f",
                        energyUsed, trip.currency, trip.electricityRate, trip.currency, trip.tripCost));
            }
        }

        // Set telemetry file path (using startTime-based filename)
        trip.telemetryFilePath = telemetryPath;

        // 4. Insert into database
        if (database == null || !database.isAvailable()) {
            logger.error("CRITICAL: Trip database unavailable — trip NOT saved! distance=" + trip.distanceKm + "km");
        } else {
            long dbId = database.insertTrip(trip);

            if (dbId <= 0) {
                logger.error("CRITICAL: database.insertTrip returned " + dbId + " — trip NOT saved!");
            } else if (dbId > 0) {
                // After DB insert, rename telemetry file to use the DB ID
                // and update the record's telemetry file path
                String newPath = recorder != null
                        ? recorder.getTelemetryFilePath(dbId) : null;

                if (newPath != null && telemetryPath != null) {
                    File oldFile = new File(telemetryPath);
                    File newFile = new File(newPath);
                    if (oldFile.exists() && !oldFile.getAbsolutePath().equals(newFile.getAbsolutePath())) {
                        if (oldFile.renameTo(newFile)) {
                            trip.telemetryFilePath = newPath;
                            database.updateTrip(trip);
                            logger.info("Telemetry file renamed: " + oldFile.getName()
                                    + " → " + newFile.getName());
                        } else {
                            logger.warn("Failed to rename telemetry file to " + newFile.getName());
                        }
                    }
                }

                // 5. Update rollups
                database.updateWeeklyRollup(trip);
                database.updateMonthlyRollup(trip);

                // 6. Assign route_id for O(1) similar-trip lookups
                if (trip.startLat != 0 && trip.startLon != 0) {
                    long routeId = database.findOrCreateRoute(
                            trip.startLat, trip.startLon, trip.endLat, trip.endLon, trip.distanceKm);
                    if (routeId > 0) {
                        trip.routeId = routeId;
                        database.updateTrip(trip);
                        logger.info("Trip assigned to route " + routeId);
                    }
                }

                logger.info("Trip saved — id=" + dbId
                        + " scores=[A=" + trip.anticipationScore
                        + " S=" + trip.smoothnessScore
                        + " SD=" + trip.speedDisciplineScore
                        + " E=" + trip.efficiencyScore
                        + " C=" + trip.consistencyScore + "]");
            }
        }

        // 6. Update range estimator
        if (rangeEstimator != null) {
            rangeEstimator.onTripCompleted(trip);
        }
    }

    /**
     * Handle trip discarded event from TripDetector.
     * Stop recorder and clean up the telemetry file.
     */
    private void handleTripDiscarded(TripRecord trip, String reason) {
        logger.info("Trip discarded: " + reason);

        // Release telemetry polling ref (acquired in handleTripStarted)
        if (telemetryDataCollector != null) {
            telemetryDataCollector.stopPolling();
        }

        if (recorder != null) {
            String telemetryPath = recorder.stopRecording();

            // Clean up telemetry file
            if (telemetryPath != null) {
                File telemetryFile = new File(telemetryPath);
                if (telemetryFile.exists()) {
                    if (telemetryFile.delete()) {
                        logger.info("Discarded telemetry file: " + telemetryFile.getName());
                    } else {
                        logger.warn("Failed to delete discarded telemetry file: "
                                + telemetryFile.getName());
                    }
                }
            }
        }
    }
}
