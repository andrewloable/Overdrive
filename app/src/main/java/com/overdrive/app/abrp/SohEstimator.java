package com.overdrive.app.abrp;

import com.overdrive.app.byd.BydVehicleData;
import com.overdrive.app.byd.BydDataCollector;
import com.overdrive.app.config.UnifiedConfigManager;
import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.monitor.BatterySocData;
import com.overdrive.app.monitor.VehicleDataMonitor;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.util.Properties;

/**
 * Shape B SOH estimator.
 *
 * Live SOH = 100 × (remainKwh / SOC%) ÷ nominalKwh.
 * Calibration is recorded as a SEPARATE displayed-only anchor (not blended).
 *
 * Nominal capacity precedence (read at init() and on every getStatus()):
 *   1. User-set kWh from UnifiedConfigManager.vehicle.nominalKwh
 *   2. Auto-detected kWh persisted to /data/local/tmp/abrp_soh_estimate.properties
 *   3. Auto-detection probes (BMS-Ah → SOC heuristic → model string → pack voltage)
 */
public class SohEstimator {

    private static final String TAG = "SohEstimator";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    private double nominalCapacityKwh = 0;
    private String nominalSource = "unset"; // "user" | "auto" | "unset"

    private static final String SOH_FILE = "/data/local/tmp/abrp_soh_estimate.properties";

    private static final String PROP_SOH_PERCENT = "soh_percent";
    private static final String PROP_LAST_UPDATED = "last_updated";
    private static final String PROP_NOMINAL_CAPACITY = "nominal_capacity_kwh";
    private static final String PROP_NOMINAL_SOURCE = "nominal_source";
    private static final String PROP_CALIBRATION_SOH = "calibration_soh";
    private static final String PROP_CALIBRATION_TIMESTAMP = "calibration_timestamp_ms";
    private static final String PROP_LIVE_HISTORY = "live_history";
    private static final String PROP_SCHEMA_VERSION = "schema_version";
    private static final int CURRENT_SCHEMA_VERSION = 2;

    private static final int LIVE_HISTORY_SIZE = 10;
    private final java.util.ArrayDeque<Double> liveHistory = new java.util.ArrayDeque<>(LIVE_HISTORY_SIZE);

    private double currentSoh = -1;
    private double calibrationSoh = -1;
    private long calibrationTimestampMs = 0;

    // True when fuel signals (getFuelPercentageValue / getFuelDrivingRangeValue)
    // are at BEV sentinels. Set by autoDetectCarModel before the SOC heuristic
    // runs so we can suppress the PHEV-kWh-bug detector on real BEVs whose
    // remainKwh happens to be numerically close to SOC% by coincidence.
    private boolean fuelSignalsLookBev = false;

    // Plausible BYD pack range. Seal 5 DM-i Dynamic uses an 8.3 kWh PHEV
    // pack; Tang EV is the upper production bound we currently support.
    private static final double MIN_PLAUSIBLE_KWH = 8.0;
    private static final double MAX_PLAUSIBLE_KWH = 120.0;

    // BYD Blade LFP reference cell voltage. 3.22 V derived from BYD's
    // published kWh / Ah / cellCount specs.
    private static final double BYD_BLADE_REFERENCE_CELL_VOLTAGE = 3.22;

    public void setNominalCapacityKwh(double capacityKwh) {
        synchronized (autoDetectLock) {
            if (capacityKwh >= MIN_PLAUSIBLE_KWH && capacityKwh <= MAX_PLAUSIBLE_KWH) {
                this.nominalCapacityKwh = capacityKwh;
                // Only mark "auto" if a user override isn't currently active. The
                // auto-detect path otherwise overwrites a user pick when it runs
                // after a config change.
                if (!"user".equals(nominalSource)) {
                    this.nominalSource = "auto";
                }
                logger.info("Nominal capacity set to " + capacityKwh + " KWh (source="
                    + nominalSource + ")");
                persistEstimate();
            } else {
                logger.warn("Rejecting implausible nominal capacity: " + capacityKwh
                    + " kWh (valid range: " + MIN_PLAUSIBLE_KWH + "-" + MAX_PLAUSIBLE_KWH + ")");
            }
        }
    }

    /**
     * User-driven override. Persists to UnifiedConfigManager so it survives
     * across daemon restarts AND across re-runs of autoDetectCarModel — only
     * clearUserNominal() can demote this back to "auto" / "unset".
     */
    public void setNominalCapacityKwhFromUser(double capacityKwh) {
        synchronized (autoDetectLock) {
            if (capacityKwh < MIN_PLAUSIBLE_KWH || capacityKwh > MAX_PLAUSIBLE_KWH) {
                logger.warn("Rejecting user nominal " + capacityKwh + " kWh — outside "
                    + MIN_PLAUSIBLE_KWH + "-" + MAX_PLAUSIBLE_KWH + " range");
                return;
            }
            this.nominalCapacityKwh = capacityKwh;
            this.nominalSource = "user";
            try {
                UnifiedConfigManager.updateValues("vehicle",
                    java.util.Collections.singletonMap("nominalKwh", (Object) capacityKwh));
            } catch (Throwable t) {
                logger.warn("Failed to persist user nominalKwh to UnifiedConfig: " + t.getMessage());
            }
            persistEstimate();
            logger.info("User-set nominal capacity: " + capacityKwh + " kWh");
        }
    }

    /**
     * Clear the user override. Drops the unified-config key and re-runs
     * auto-detection so the estimate falls back to whichever pack we can
     * identify from BMS / SOC / model / voltage.
     */
    public void clearUserNominal() {
        synchronized (autoDetectLock) {
            try {
                JSONObject vehicle = UnifiedConfigManager.getVehicle();
                if (vehicle.has("nominalKwh")) {
                    vehicle.remove("nominalKwh");
                    UnifiedConfigManager.setVehicle(vehicle);
                }
            } catch (Throwable t) {
                logger.warn("Failed to clear user nominalKwh: " + t.getMessage());
            }
            this.nominalCapacityKwh = 0;
            this.nominalSource = "unset";
            // persistEstimate() early-returns when both currentSoh and nominalCapacityKwh
            // are <= 0, so the stale keys would survive on disk. Strip them explicitly.
            try {
                File f = new File(SOH_FILE);
                if (f.exists()) {
                    Properties p = new Properties();
                    try (FileInputStream fis = new FileInputStream(f)) { p.load(fis); }
                    p.remove(PROP_NOMINAL_CAPACITY);
                    p.remove(PROP_NOMINAL_SOURCE);
                    try (FileOutputStream fos = new FileOutputStream(f)) { p.store(fos, "ABRP SOH Estimate"); }
                }
            } catch (Exception ignored) {}
            persistEstimate();
            try {
                android.content.Context ctx = com.overdrive.app.daemon.CameraDaemon.getAppContext();
                autoDetectCarModel(ctx);
            } catch (Throwable t) {
                logger.warn("Re-detect after clearUserNominal failed: " + t.getMessage());
            }
        }
    }

    public double getNominalCapacityKwh() {
        return nominalCapacityKwh;
    }

    public String getNominalSource() {
        return nominalSource;
    }

    /**
     * Detect capacity from pack voltage (called by BydDataCollector on first HV voltage event).
     * Skips entirely when the user has set a nominal explicitly OR a value has
     * already been detected — pack voltage is the least reliable source.
     */
    public void autoDetectFromPackVoltage(double packVoltage, BydVehicleData vd) {
        if (packVoltage < 200 || packVoltage > 900) return;
        if ("user".equals(nominalSource)) return;
        if (nominalCapacityKwh > 0) {
            logger.debug("Pack voltage " + String.format("%.1f", packVoltage) +
                "V ignored — capacity already detected: " + nominalCapacityKwh + " kWh");
            return;
        }
        double cellVoltage = 3.2;
        int cellCount = (int) Math.round(packVoltage / cellVoltage);
        double capacity = mapCellCountToCapacity(cellCount);
        if (capacity > 0) {
            setNominalCapacityKwh(capacity);
            logger.info("Pack Voltage Capacity: " + capacity + " kWh (voltage=" +
                String.format("%.1f", packVoltage) + "V, nominal cellV=3.2V" +
                ", cells≈" + cellCount + "s)");
        } else {
            logger.debug("Pack voltage " + String.format("%.1f", packVoltage) + "V → " +
                cellCount + " cells — no matching BYD pack");
        }
    }

    // ==================== AUTO-DETECT ====================

    private static boolean isSentinelInt(int v) {
        return v == 255 || v == 254
            || v == 511 || v == 1023
            || v == 2046 || v == 2047
            || v == 4095
            || v == 65534 || v == 65535;
    }
    private static boolean isSentinelInt(Object o) {
        return (o instanceof Number) && isSentinelInt(((Number) o).intValue());
    }

    private static String describeException(Throwable e) {
        if (e == null) return "null";
        String msg = e.getMessage();
        if (msg != null && !msg.trim().isEmpty()) {
            return e.getClass().getSimpleName() + ": " + msg;
        }
        Throwable cause = e.getCause();
        if (cause != null && cause.getMessage() != null && !cause.getMessage().trim().isEmpty()) {
            return e.getClass().getSimpleName() + " (cause: "
                + cause.getClass().getSimpleName() + ": " + cause.getMessage() + ")";
        }
        return e.getClass().getSimpleName() + " (no message)";
    }

    private void dumpPhevDiagnostics(android.content.Context context) {
        fuelSignalsLookBev = false;
        boolean fuelPctSentinel = false;
        boolean fuelRangeSentinel = false;
        boolean fuelPctProbed = false;
        boolean fuelRangeProbed = false;
        try {
            logger.info("=== POWERTRAIN DIAGNOSTICS ===");

            try {
                String model = (String) Class.forName("android.os.SystemProperties")
                    .getMethod("get", String.class, String.class)
                    .invoke(null, "ro.product.model", "");
                logger.info("[diag] ro.product.model = \"" + model + "\"");
            } catch (Exception e) {
                logger.info("[diag] ro.product.model: failed (" + describeException(e) + ")");
            }

            if (context == null) {
                logger.info("[diag] context==null — skipping HAL probes");
                logger.info("=== POWERTRAIN DIAGNOSTICS END ===");
                return;
            }

            try {
                Class<?> energyCls = Class.forName("android.hardware.bydauto.energy.BYDAutoEnergyDevice");
                Object energyDev = energyCls.getMethod("getInstance", android.content.Context.class)
                    .invoke(null, context);
                if (energyDev != null) {
                    try {
                        Object em = energyCls.getMethod("getEnergyMode").invoke(energyDev);
                        String hint;
                        if (Integer.valueOf(1).equals(em)) hint = " (commonly EV — not authoritative)";
                        else if (Integer.valueOf(3).equals(em)) hint = " (commonly HEV — not authoritative; observed on BEV too)";
                        else hint = " (unknown code)";
                        logger.info("[diag] BYDAutoEnergyDevice.getEnergyMode = " + em + hint);
                    } catch (Exception e) {
                        logger.info("[diag] getEnergyMode failed: " + describeException(e));
                    }
                    try {
                        Object om = energyCls.getMethod("getOperationMode").invoke(energyDev);
                        logger.info("[diag] BYDAutoEnergyDevice.getOperationMode = " + om);
                    } catch (Exception e) {
                        logger.info("[diag] getOperationMode failed: " + describeException(e));
                    }
                } else {
                    logger.info("[diag] BYDAutoEnergyDevice getInstance returned null");
                }
            } catch (ClassNotFoundException e) {
                logger.info("[diag] BYDAutoEnergyDevice not on this firmware");
            } catch (Exception e) {
                logger.info("[diag] BYDAutoEnergyDevice probe failed: " + describeException(e));
            }

            try {
                Class<?> chargingCls = Class.forName("android.hardware.bydauto.charging.BYDAutoChargingDevice");
                Object chargingDev = chargingCls.getMethod("getInstance", android.content.Context.class)
                    .invoke(null, context);
                if (chargingDev != null) {
                    try {
                        Object cc = chargingCls.getMethod("getChargingCapacity").invoke(chargingDev);
                        logger.info("[diag] BYDAutoChargingDevice.getChargingCapacity = " + cc
                            + " (not used — observed 0.0 on every probed vehicle)");
                    } catch (Exception e) {
                        logger.info("[diag] getChargingCapacity failed: " + describeException(e));
                    }
                    try {
                        Object ct = chargingCls.getMethod("getChargingType").invoke(chargingDev);
                        logger.info("[diag] BYDAutoChargingDevice.getChargingType = " + ct);
                    } catch (Exception e) {
                        logger.info("[diag] getChargingType failed: " + describeException(e));
                    }
                } else {
                    logger.info("[diag] BYDAutoChargingDevice getInstance returned null");
                }
            } catch (ClassNotFoundException e) {
                logger.info("[diag] BYDAutoChargingDevice not on this firmware");
            } catch (Exception e) {
                logger.info("[diag] BYDAutoChargingDevice probe failed: " + describeException(e));
            }

            try {
                Class<?> statCls = Class.forName("android.hardware.bydauto.statistic.BYDAutoStatisticDevice");
                Object statDev = statCls.getMethod("getInstance", android.content.Context.class)
                    .invoke(null, context);
                if (statDev != null) {
                    try {
                        Object fp = statCls.getMethod("getFuelPercentageValue").invoke(statDev);
                        fuelPctProbed = true;
                        String hint;
                        if (isSentinelInt(fp)) {
                            hint = " (sentinel — BEV / fuel unavailable)";
                            fuelPctSentinel = true;
                        } else if (fp instanceof Number) {
                            int v = ((Number) fp).intValue();
                            if (v >= 0 && v <= 100) hint = " (in 0..100 range — PHEV fuel level)";
                            else hint = " (out of expected 0..100 range — ignore)";
                        } else hint = "";
                        logger.info("[diag] BYDAutoStatisticDevice.getFuelPercentageValue = " + fp + hint);
                    } catch (Exception e) {
                        logger.info("[diag] getFuelPercentageValue failed: " + describeException(e));
                    }
                    try {
                        Object fr = statCls.getMethod("getFuelDrivingRangeValue").invoke(statDev);
                        fuelRangeProbed = true;
                        String hint;
                        if (isSentinelInt(fr)) {
                            hint = " (sentinel — BEV / range unavailable)";
                            fuelRangeSentinel = true;
                        } else if (fr instanceof Number) {
                            int v = ((Number) fr).intValue();
                            if (v > 0 && v < 1500) hint = " km (real PHEV fuel range)";
                            else hint = " (out of expected 0..1500 km range)";
                        } else hint = "";
                        logger.info("[diag] BYDAutoStatisticDevice.getFuelDrivingRangeValue = " + fr + hint);
                    } catch (Exception e) {
                        logger.info("[diag] getFuelDrivingRangeValue failed: " + describeException(e));
                    }
                    try {
                        Object sohi = statCls.getMethod("getStatisticBatteryHealthyIndex").invoke(statDev);
                        logger.info("[diag] BYDAutoStatisticDevice.getStatisticBatteryHealthyIndex = " + sohi);
                    } catch (Exception e) {
                        logger.info("[diag] getStatisticBatteryHealthyIndex failed: " + describeException(e));
                    }
                    try {
                        Object remPwr = statCls.getMethod("getRemainingBatteryPower").invoke(statDev);
                        logger.info("[diag] BYDAutoStatisticDevice.getRemainingBatteryPower = " + remPwr
                            + " (raw — divide by 10 if reported in 0.1 kWh units)");
                    } catch (Exception e) {
                        logger.info("[diag] getRemainingBatteryPower failed: " + describeException(e));
                    }
                } else {
                    logger.info("[diag] BYDAutoStatisticDevice getInstance returned null");
                }
            } catch (ClassNotFoundException e) {
                logger.info("[diag] BYDAutoStatisticDevice not on this firmware");
            } catch (Exception e) {
                logger.info("[diag] BYDAutoStatisticDevice probe failed: " + describeException(e));
            }

            try {
                Class<?> bodyCls = Class.forName("android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice");
                Object bodyDev = bodyCls.getMethod("getInstance", android.content.Context.class)
                    .invoke(null, context);
                if (bodyDev != null) {
                    try {
                        Object cap = bodyCls.getMethod("getBatteryCapacity").invoke(bodyDev);
                        int rawCap = (cap instanceof Number) ? ((Number) cap).intValue() : -1;
                        String semHint;
                        if (isSentinelInt(rawCap)) semHint = " (sentinel — unavailable)";
                        else if (rawCap >= 50 && rawCap <= 350) semHint = " (likely Ah rating)";
                        else if (rawCap > 350 && rawCap < 60000) semHint = " (likely 0.1 kWh units → " + (rawCap / 10.0) + " kWh)";
                        else semHint = " (unknown semantics)";
                        logger.info("[diag] BYDAutoBodyworkDevice.getBatteryCapacity = " + cap + semHint);
                    } catch (Exception e) {
                        logger.info("[diag] getBatteryCapacity failed: " + describeException(e));
                    }
                }
            } catch (ClassNotFoundException e) {
                logger.info("[diag] BYDAutoBodyworkDevice not on this firmware");
            } catch (Exception e) {
                logger.info("[diag] BYDAutoBodyworkDevice probe failed: " + describeException(e));
            }

            try {
                Class<?> pwrCls = Class.forName("android.hardware.bydauto.power.BYDAutoPowerDevice");
                Object pwrDev = pwrCls.getMethod("getInstance", android.content.Context.class)
                    .invoke(null, context);
                if (pwrDev != null) {
                    try {
                        Object rp = pwrCls.getMethod("getBatteryRemainPowerEV").invoke(pwrDev);
                        logger.info("[diag] BYDAutoPowerDevice.getBatteryRemainPowerEV = " + rp);
                    } catch (Exception e) {
                        logger.info("[diag] getBatteryRemainPowerEV failed: " + describeException(e));
                    }
                }
            } catch (ClassNotFoundException e) {
                logger.info("[diag] BYDAutoPowerDevice not on this firmware");
            } catch (Exception e) {
                logger.info("[diag] BYDAutoPowerDevice probe failed: " + describeException(e));
            }

            try {
                VehicleDataMonitor vdm = VehicleDataMonitor.getInstance();
                BydVehicleData vd = vdm != null ? vdm.getVd() : null;
                BatterySocData socData = vdm != null ? vdm.getBatterySoc() : null;
                double remKwh = vdm != null ? vdm.getBatteryRemainPowerKwh() : 0;
                logger.info("[diag] internal: socPercent="
                    + (socData != null ? socData.socPercent : "null")
                    + ", getBatteryRemainPowerKwh=" + remKwh
                    + ", vd.remainKwh=" + (vd != null ? vd.remainKwh : "null")
                    + ", vd.hvPackVoltage=" + (vd != null ? vd.hvPackVoltage : "null")
                    + ", vd.fuelPercent=" + (vd != null ? vd.fuelPercent : "null")
                    + ", currentNominalKwh=" + nominalCapacityKwh);
            } catch (Exception e) {
                logger.info("[diag] internal snapshot probe failed: " + describeException(e));
            }

            if (fuelPctProbed && fuelRangeProbed && fuelPctSentinel && fuelRangeSentinel) {
                fuelSignalsLookBev = true;
                logger.info("[diag] Inferred drivetrain: BEV (both fuel signals at sentinel — getEnergyType ignored)");
            }

            logger.info("=== POWERTRAIN DIAGNOSTICS END ===");
        } catch (Throwable t) {
            logger.warn("dumpPhevDiagnostics: unexpected error: " + describeException(t));
        }
    }

    private final Object autoDetectLock = new Object();

    public void autoDetectCarModel(android.content.Context context) {
        synchronized (autoDetectLock) {
            autoDetectCarModelInternal(context);
        }
    }

    private void autoDetectCarModelInternal(android.content.Context context) {
        // User override always wins — never let auto-detect demote it.
        if ("user".equals(nominalSource) && nominalCapacityKwh > 0) {
            logger.info("autoDetectCarModel skipped — user override active ("
                + nominalCapacityKwh + " kWh)");
            return;
        }

        // User-selected vehicle model (set via the model picker) maps to a
        // canonical pack capacity in the manifest. This sits between the
        // explicit user kWh override (above) and the SOC heuristic (below)
        // — it's stronger than heuristics because the user told us which
        // car they have, but weaker than an explicit kWh value because
        // model variants exist (Seal Standard 61.4 kWh vs Premium 82.5 kWh).
        try {
            double modelKwh = readModelNominalFromManifest();
            if (modelKwh >= MIN_PLAUSIBLE_KWH && modelKwh <= MAX_PLAUSIBLE_KWH) {
                nominalCapacityKwh = modelKwh;
                nominalSource = "user_model";
                logger.info("autoDetectCarModel: nominal " + modelKwh
                    + " kWh from user-selected model");
                return;
            }
        } catch (Throwable t) {
            logger.debug("Model-manifest nominalKwh lookup failed: " + t.getMessage());
        }

        if (context == null) {
            try {
                context = com.overdrive.app.daemon.CameraDaemon.getAppContext();
                if (context != null) {
                    logger.warn("autoDetectCarModel called with null context — recovered via CameraDaemon.getAppContext()");
                } else {
                    logger.warn("autoDetectCarModel: null context AND no app context available — HAL probes will be skipped");
                }
            } catch (Exception e) {
                logger.warn("autoDetectCarModel: failed to recover null context: " + describeException(e));
            }
        }

        dumpPhevDiagnostics(context);

        if (context != null) {
            double exactKwh = tryBmsExactCapacity(context);
            if (exactKwh > 0 && !contradictedBySocRatio(exactKwh)) {
                setNominalCapacityKwh(exactKwh);
                return;
            }
        }

        try {
            VehicleDataMonitor vdm = VehicleDataMonitor.getInstance();
            double remainingKwh = vdm.getBatteryRemainPowerKwh();
            BatterySocData socData = vdm.getBatterySoc();
            if (remainingKwh > 1.5 && socData != null && socData.socPercent >= 10) {
                double estimatedCapacity = remainingKwh / (socData.socPercent / 100.0);
                boolean likelyPhevKwhBug = !fuelSignalsLookBev
                        && Math.abs(remainingKwh - socData.socPercent) < 5.0;
                if (!fuelSignalsLookBev && !likelyPhevKwhBug
                        && remainingKwh > 40 && estimatedCapacity > 40
                        && nominalCapacityKwh <= 0) {
                    double socKwhRatio = remainingKwh / socData.socPercent;
                    if (socKwhRatio > 0.85 && socKwhRatio < 1.15) {
                        likelyPhevKwhBug = true;
                    }
                }
                if (likelyPhevKwhBug) {
                    logger.info("SOC heuristic skipped: remainKwh (" +
                        String.format("%.1f", remainingKwh) + ") ≈ socPercent (" +
                        String.format("%.1f", socData.socPercent) + ") — likely SOC-as-kWh firmware bug");
                } else if (nominalCapacityKwh > 0 && nominalCapacityKwh < 30 && estimatedCapacity > 40) {
                    logger.info("SOC heuristic skipped: estimated " + String.format("%.1f", estimatedCapacity) +
                        " kWh but nominal already detected as " + String.format("%.1f", nominalCapacityKwh) +
                        " kWh — PHEV remainKwh unreliable");
                } else {
                    double packV = Double.NaN;
                    BydVehicleData vd = vdm.getVd();
                    if (vd != null && !Double.isNaN(vd.hvPackVoltage)
                            && vd.hvPackVoltage > 200) {
                        packV = vd.hvPackVoltage;
                    }
                    double matched = matchNearestCapacity(
                        estimatedCapacity, packV, socData.socPercent);
                    if (matched > 0) {
                        setNominalCapacityKwh(matched);
                        double snapDelta = Math.abs(estimatedCapacity - matched);
                        boolean snapped = snapDelta > 0.5;
                        logger.info("SOC-derived nominal capacity: " + matched + " kWh"
                            + (snapped
                                ? " (estimated " + String.format("%.1f", estimatedCapacity)
                                  + " kWh, snapped to nearest known pack)"
                                : "")
                            + " [SOC=" + String.format("%.1f", socData.socPercent) + "%, remain="
                            + String.format("%.1f", remainingKwh) + " kWh]");
                        return;
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("SOC heuristic failed: " + e.getMessage());
        }

        try {
            String carType = (String) Class.forName("android.os.SystemProperties")
                .getMethod("get", String.class, String.class)
                .invoke(null, "ro.product.model", "");
            if (carType != null && !carType.isEmpty()) {
                double mapped = mapCarTypeToCapacity(carType);
                if (mapped > 0) {
                    setNominalCapacityKwh(mapped);
                    logger.info("Model-Mapped Capacity (" + carType + "): " + mapped + " kWh");
                    return;
                }
            }
        } catch (Exception e) { /* ignore */ }

        if (context != null) {
            double fuzzyKwh = tryBmsFuzzyCapacity(context);
            if (fuzzyKwh > 0 && !contradictedBySocRatio(fuzzyKwh)) {
                setNominalCapacityKwh(fuzzyKwh);
                return;
            }
        }

        try {
            VehicleDataMonitor vdm = VehicleDataMonitor.getInstance();
            BydVehicleData vd = vdm != null ? vdm.getVd() : null;
            if (vd != null && !Double.isNaN(vd.hvPackVoltage) && vd.hvPackVoltage > 200) {
                double voltage = vd.hvPackVoltage;
                double cellVoltage = 3.2;
                int cellCount = (int) Math.round(voltage / cellVoltage);
                double capacity = mapCellCountToCapacity(cellCount);
                if (capacity > 0) {
                    setNominalCapacityKwh(capacity);
                    logger.info("Pack Voltage Capacity: " + capacity + " kWh (voltage=" +
                        String.format("%.1f", voltage) + "V, nominal cellV=3.2V" +
                        ", cells≈" + cellCount + "s)");
                    return;
                }
            }
        } catch (Exception e) {
            logger.debug("Pack voltage capacity lookup failed: " + e.getMessage());
        }

        if (nominalCapacityKwh > 0 && contradictedBySocRatio(nominalCapacityKwh)) {
            logger.warn("Persisted nominal " + nominalCapacityKwh
                + " kWh contradicted by current SOC ratio — clearing for re-detection on next cycle");
            nominalCapacityKwh = 0;
            nominalSource = "unset";
            currentSoh = -1;
            persistEstimate();
        }

        logger.warn("Capacity detection failed" +
            (nominalCapacityKwh > 0 ? " — using previously saved capacity: " + nominalCapacityKwh + " kWh"
                                    : " — SOH estimation disabled until capacity is identified"));
    }

    /**
     * Look up the canonical nominal kWh for the user-selected vehicle model
     * from the bundled/cached manifest. 0 if no selection, no value, or
     * manifest unavailable. Delegated to ModelsApiHandler so the manifest
     * cache/precedence rules stay in one place.
     */
    private double readModelNominalFromManifest() {
        return com.overdrive.app.server.ModelsApiHandler.nominalKwhForSelectedModel();
    }

    private boolean contradictedBySocRatio(double bmsKwh) {
        try {
            VehicleDataMonitor vdm = VehicleDataMonitor.getInstance();
            if (vdm == null) return false;
            double remainKwh = vdm.getBatteryRemainPowerKwh();
            BatterySocData socData = vdm.getBatterySoc();
            if (socData == null) return false;
            double soc = socData.socPercent;
            if (remainKwh < 1.5 || soc < 10 || soc > 100) return false;
            double impliedKwh = remainKwh / (soc / 100.0);
            if (!fuelSignalsLookBev && Math.abs(remainKwh - soc) < 5.0) return false;
            double relativeDelta = Math.abs(impliedKwh - bmsKwh) / bmsKwh;
            if (relativeDelta > 0.25) {
                logger.warn("BMS exact-Ah result " + bmsKwh + " kWh contradicted by SOC ratio: "
                    + String.format("%.1f", impliedKwh) + " kWh (remain="
                    + String.format("%.1f", remainKwh) + ", SOC="
                    + String.format("%.0f", soc) + "%) — falling through to SOC heuristic");
                return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    private double tryBmsExactCapacity(android.content.Context context) {
        Integer rawOrNull = readBatteryCapacityRaw(context);
        if (rawOrNull == null) return 0;
        int raw = rawOrNull;
        if (raw > 1000 && raw < 60000) {
            double kwh = raw / 1000.0;
            if (kwh >= MIN_PLAUSIBLE_KWH && kwh <= MAX_PLAUSIBLE_KWH) {
                logger.info("BMS Capacity (exact, 0.001 kWh): " + kwh + " kWh (raw=" + raw + ")");
                return kwh;
            }
            return 0;
        }
        if (raw > 0 && raw <= 1000) {
            double kwh = mapAhToKwh(raw);
            if (kwh >= MIN_PLAUSIBLE_KWH && kwh <= MAX_PLAUSIBLE_KWH) {
                logger.info("BMS Capacity (exact, Ah=" + raw + "): " + kwh + " kWh");
                return kwh;
            }
        }
        return 0;
    }

    private double tryBmsFuzzyCapacity(android.content.Context context) {
        Integer rawOrNull = readBatteryCapacityRaw(context);
        if (rawOrNull == null) return 0;
        int raw = rawOrNull;
        if (raw <= 0 || raw > 1000) return 0;
        if (mapAhToKwh(raw) > 0) return 0;

        int snappedAh = nearestKnownAh(raw, 3);
        if (snappedAh <= 0) return 0;
        double kwh = mapAhToKwh(snappedAh);
        if (kwh < MIN_PLAUSIBLE_KWH || kwh > MAX_PLAUSIBLE_KWH) return 0;

        logger.info("BMS Capacity (fuzzy): " + kwh + " kWh (raw Ah=" + raw
            + " → snapped to " + snappedAh + " Ah)");
        return kwh;
    }

    private Integer readBatteryCapacityRaw(android.content.Context context) {
        try {
            Class<?> cls = Class.forName("android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice");
            Object device = cls.getMethod("getInstance", android.content.Context.class).invoke(null, context);
            if (device == null) return null;
            Method getBatteryCapacity = cls.getMethod("getBatteryCapacity");
            Number capNum = (Number) getBatteryCapacity.invoke(device);
            if (capNum == null) return null;
            int raw = capNum.intValue();
            if (raw <= 0 || raw == 255 || raw == 254 || raw == 65534 || raw == 65535) {
                return null;
            }
            return raw;
        } catch (Exception e) {
            logger.debug("readBatteryCapacityRaw failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * Compute live SOH from one tick of BMS data WITHOUT side effects.
     * Used by both updateFromEnergy() and any read-only consumer.
     */
    public double computeLiveSoh(double remainKwh, double socPercent, double highCellVoltage) {
        if (nominalCapacityKwh <= 0) return -1;
        if (socPercent <= 0 || socPercent > 100) return -1;
        if (remainKwh <= 0) return -1;
        double scale = displayToAbsoluteSocScale(highCellVoltage);
        double absSoc = scaleDisplaySoc(socPercent, scale);
        double impliedTotalCap = remainKwh / (absSoc / 100.0);
        double soh = (impliedTotalCap / nominalCapacityKwh) * 100.0;
        if (soh < 60.0) return 60.0;
        if (soh > 110.0) return 110.0;
        return soh;
    }

    /**
     * Seed an initial estimate immediately after capacity detection so the UI
     * isn't blank waiting for the first SocHistoryDatabase tick.
     */
    public void seedInitialEstimate() {
        if (hasEstimate()) return;
        if (nominalCapacityKwh <= 0) return;
        try {
            VehicleDataMonitor vdm = VehicleDataMonitor.getInstance();
            BatterySocData socData = vdm.getBatterySoc();
            BydVehicleData vd = vdm.getVd();
            // Read RAW vd.remainKwh, never getBatteryRemainPowerKwh() — the
            // helper synthesizes from currentSoh on PHEV/bad-BMS paths and
            // would seed SOH from itself (defaulting to 100% baseline).
            if (vd == null || Double.isNaN(vd.remainKwh) || vd.remainKwh <= 0) return;
            if (socData == null || socData.socPercent < 10 || socData.socPercent > 100) return;
            double rawRemainKwh = vd.remainKwh;
            double impliedCap = rawRemainKwh / (socData.socPercent / 100.0);
            double ratio = impliedCap / nominalCapacityKwh;
            // Junk BMS reading — refuse to seed rather than baking in nonsense.
            if (ratio < 0.5 || ratio > 1.5) return;
            double highCellV = Double.isNaN(vd.highCellVoltage) ? Double.NaN : vd.highCellVoltage;
            double soh = computeLiveSoh(rawRemainKwh, socData.socPercent, highCellV);
            if (soh > 0) {
                currentSoh = soh;
                persistEstimate();
                logger.info("Initial SOH seeded: " + String.format("%.1f", soh) + "%");
            }
        } catch (Exception e) {
            logger.debug("Initial SOH seed failed: " + e.getMessage());
        }
    }

    // ==================== LIFECYCLE ====================

    public void init() {
        // 1. User override from UnifiedConfigManager.
        try {
            JSONObject vehicle = UnifiedConfigManager.getVehicle();
            double userKwh = vehicle.optDouble("nominalKwh", 0);
            if (userKwh >= MIN_PLAUSIBLE_KWH && userKwh <= MAX_PLAUSIBLE_KWH) {
                nominalCapacityKwh = userKwh;
                nominalSource = "user";
                logger.info("Restored user nominal capacity: " + userKwh + " kWh");
            }
        } catch (Throwable t) {
            logger.debug("UnifiedConfig vehicle.nominalKwh read failed: " + t.getMessage());
        }

        // 2. Properties-file restore (auto-detected nominal + currentSoh + calibration).
        try {
            File sohFile = new File(SOH_FILE);
            if (!sohFile.exists()) return;

            Properties props = new Properties();
            try (FileInputStream fis = new FileInputStream(sohFile)) {
                props.load(fis);
            }

            int persistedVersion = 0;
            try {
                persistedVersion = Integer.parseInt(props.getProperty(PROP_SCHEMA_VERSION, "0"));
            } catch (NumberFormatException ignored) {
                persistedVersion = 0;
            }

            if (persistedVersion < CURRENT_SCHEMA_VERSION) {
                // Legacy file: preserve nominal so auto-detect doesn't restart cold,
                // but drop SOH state — its semantics may have shifted across versions.
                if (!"user".equals(nominalSource)) {
                    String capStr = props.getProperty(PROP_NOMINAL_CAPACITY);
                    if (capStr != null) {
                        try {
                            double savedCap = Double.parseDouble(capStr);
                            if (savedCap >= MIN_PLAUSIBLE_KWH && savedCap <= MAX_PLAUSIBLE_KWH) {
                                nominalCapacityKwh = savedCap;
                                // Legacy "user" sources are not authoritative — the
                                // source-of-truth for user overrides is now
                                // UnifiedConfigManager (read earlier). Downgrade to
                                // "auto" so a stray legacy "user" string can't pin
                                // a nominal that has no corresponding override.
                                String savedSrc = props.getProperty(PROP_NOMINAL_SOURCE);
                                nominalSource = (savedSrc != null && !savedSrc.isEmpty() && !"user".equals(savedSrc))
                                    ? savedSrc : "auto";
                            }
                        } catch (NumberFormatException ignored2) {}
                    }
                }
                currentSoh = -1;
                calibrationSoh = -1;
                calibrationTimestampMs = 0;
                liveHistory.clear();
                logger.info("SOH file migrated from legacy schema (v" + persistedVersion
                    + " → v" + CURRENT_SCHEMA_VERSION
                    + ") — currentSoh/calibration cleared, will re-seed from BMS data");
                // persistEstimate() short-circuits when every field is empty,
                // which would leave schema_version unwritten and re-trigger
                // migration every boot. Stamp the new schema unconditionally.
                writeSchemaStamp();
                return;
            }

            String sohStr = props.getProperty(PROP_SOH_PERCENT);
            if (sohStr != null) {
                double persistedSoh = Double.parseDouble(sohStr);
                if (persistedSoh >= 60 && persistedSoh <= 110) {
                    currentSoh = persistedSoh;
                    logger.info("Restored SOH: " + currentSoh + "%");
                } else {
                    logger.info("Discarding persisted SOH " + persistedSoh + " — out of valid range 60-110");
                    sohFile.delete();
                }
            }

            String calStr = props.getProperty(PROP_CALIBRATION_SOH);
            if (calStr != null) {
                double cal = Double.parseDouble(calStr);
                if (cal >= 60 && cal <= 110) {
                    calibrationSoh = cal;
                }
            }
            String calTsStr = props.getProperty(PROP_CALIBRATION_TIMESTAMP);
            if (calTsStr != null) {
                try {
                    calibrationTimestampMs = Long.parseLong(calTsStr);
                } catch (NumberFormatException ignored) {}
            }

            String liveHistStr = props.getProperty(PROP_LIVE_HISTORY);
            if (liveHistStr != null && !liveHistStr.isEmpty()) {
                try {
                    String[] parts = liveHistStr.split(",");
                    for (String p : parts) {
                        String trimmed = p.trim();
                        if (trimmed.isEmpty()) continue;
                        liveHistory.addLast(Double.parseDouble(trimmed));
                    }
                    while (liveHistory.size() > LIVE_HISTORY_SIZE) {
                        liveHistory.pollFirst();
                    }
                } catch (Exception ignored) {
                    liveHistory.clear();
                }
            }

            if (!"user".equals(nominalSource)) {
                String capStr = props.getProperty(PROP_NOMINAL_CAPACITY);
                if (capStr != null) {
                    double savedCap = Double.parseDouble(capStr);
                    if (savedCap >= MIN_PLAUSIBLE_KWH && savedCap <= MAX_PLAUSIBLE_KWH) {
                        nominalCapacityKwh = savedCap;
                        String savedSrc = props.getProperty(PROP_NOMINAL_SOURCE);
                        nominalSource = (savedSrc != null && !savedSrc.isEmpty()) ? savedSrc : "auto";
                        logger.info("Restored nominal capacity: " + savedCap + " kWh (source="
                            + nominalSource + ")");
                    } else if (savedCap > 0) {
                        logger.warn("Discarding persisted nominal " + savedCap
                            + " kWh — outside plausible range");
                        if (currentSoh > 0) {
                            currentSoh = -1;
                        }
                    }
                }
            }

            if (currentSoh > 0) {
                logger.info("SOH init complete: " + currentSoh + "%");
            }
        } catch (Exception e) {
            logger.error("Failed to load SOH: " + e.getMessage());
        }
    }

    // ==================== UPDATES ====================

    /**
     * Live update from one tick of BMS data. Direct assignment — no EMA, no
     * gating beyond the formula's plausibility clamps. The caller decides
     * whether the conditions are right to feed an update; we just compute.
     *
     * `atRest` is preserved for ABI compatibility but ignored.
     */
    public void updateFromEnergy(double remainingKwh, double displaySocPercent,
                                 double highCellVoltage, boolean atRest) {
        synchronized (autoDetectLock) {
            double soh = computeLiveSoh(remainingKwh, displaySocPercent, highCellVoltage);
            if (soh <= 0) return;
            liveHistory.addLast(soh);
            while (liveHistory.size() > LIVE_HISTORY_SIZE) {
                liveHistory.pollFirst();
            }
            currentSoh = median(liveHistory);
            persistEstimate();
        }
    }

    private static double median(java.util.Collection<Double> values) {
        if (values.isEmpty()) return -1;
        java.util.ArrayList<Double> sorted = new java.util.ArrayList<>(values);
        java.util.Collections.sort(sorted);
        int n = sorted.size();
        if (n % 2 == 1) return sorted.get(n / 2);
        return (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
    }

    public void updateFromCalibration(double energyEnteredBatteryKwh, double socDelta,
                                      double packTempCelsius, boolean isAcCharge) {
        updateFromCalibration(energyEnteredBatteryKwh, socDelta, packTempCelsius, isAcCharge, Double.NaN);
    }

    public void updateFromCalibration(double energyEnteredBatteryKwh, double socDelta,
                                      double packTempCelsius, boolean isAcCharge,
                                      double highCellVoltage) {
        synchronized (autoDetectLock) {
            if (nominalCapacityKwh <= 0) {
                logger.debug("Calibration rejected: nominal capacity not yet detected");
                return;
            }
            // DC charging is accepted now; cluster-displayed energy/SOC remains accurate enough for SOH math, the AC-only gate was over-cautious.
            if (packTempCelsius < 15.0 || packTempCelsius > 35.0) {
                logger.debug("Calibration rejected: Pack temperature (" +
                    String.format("%.1f", packTempCelsius) + "°C) outside optimal SOH window (15-35°C).");
                return;
            }
            if (socDelta < 25.0) {
                logger.debug("Calibration rejected: SOC delta " + String.format("%.1f", socDelta) +
                    "% < 25% minimum for LFP accuracy");
                return;
            }

            double scale = displayToAbsoluteSocScale(highCellVoltage);
            double absSocDelta = socDelta * scale;
            double actualCapacity = energyEnteredBatteryKwh / (absSocDelta / 100.0);
            double calibratedSoh = (actualCapacity / nominalCapacityKwh) * 100.0;

            if (calibratedSoh < 60.0 || calibratedSoh > 110.0) {
                logger.warn("Calibration SOH out of range: " + String.format("%.1f", calibratedSoh) + "% — rejected");
                return;
            }

            // Anchor only — never blends into currentSoh.
            calibrationSoh = calibratedSoh;
            calibrationTimestampMs = System.currentTimeMillis();
            persistEstimate();

            logger.info("Calibration anchor: " + String.format("%.1f", calibratedSoh) + "% (temp=" +
                String.format("%.1f", packTempCelsius) + "°C, " +
                String.format("%.1f", energyEnteredBatteryKwh) + " kWh / " +
                String.format("%.1f", socDelta) + "% display delta)");
        }
    }

    public void updateFromCalibration(double energyEnteredBatteryKwh, double socDelta) {
        updateFromCalibration(energyEnteredBatteryKwh, socDelta, 25.0, true);
    }

    // ==================== GETTERS ====================

    public double getCurrentSoh() { return currentSoh; }
    public double getCalibrationSoh() { return calibrationSoh; }
    public long getCalibrationTimestampMs() { return calibrationTimestampMs; }
    public boolean hasEstimate() { return currentSoh > 0; }

    private boolean isPhevVehicle() {
        try {
            return BydDataCollector.getInstance().isPhevVehicle();
        } catch (Throwable t) {
            // If the collector is not ready yet, fall back to pack size. All
            // supported BYD PHEV packs are below 30 kWh; BEVs are larger.
            return nominalCapacityKwh > 0 && nominalCapacityKwh < 30.0;
        }
    }

    /**
     * Returns the OEM battery-health index published by BYD's Statistic device,
     * or -1 when the current firmware/car does not expose a valid value.
     *
     * This is intentionally separate from currentSoh: the Shape B estimator is
     * capacity math from remain-kWh/SOC/nominal-kWh, while the OEM index is an
     * opaque dashboard readout that is useful for Diagnostics only.
     */
    public double getOemSohPercent() {
        try {
            // The recovered legacy signal is only trusted for the PHEV bug path.
            // BEVs continue to use the calculated Shape B estimate or calibration.
            if (!isPhevVehicle()) {
                return -1;
            }
            // Prefer a fresh direct Statistic-device read for the PHEV path.
            // The snapshot is still used as a fallback if a poll captured SOH
            // but the direct getter is temporarily unavailable.
            double directSoh = BydDataCollector.getInstance().readOemSohPercent();
            if (directSoh > 0) {
                return directSoh;
            }
            BydVehicleData vd = VehicleDataMonitor.getInstance().getVd();
            if (vd == null || Double.isNaN(vd.sohPercent)) return -1;
            double soh = vd.sohPercent;
            return (soh > 0 && soh <= 100.0) ? soh : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    public double getEstimatedCapacityKwh() {
        if (!hasEstimate()) return -1;
        return (currentSoh / 100.0) * nominalCapacityKwh;
    }

    // ==================== RESET ====================

    public void reset() {
        synchronized (autoDetectLock) {
            currentSoh = -1;
            calibrationSoh = -1;
            calibrationTimestampMs = 0;
            nominalCapacityKwh = 0;
            nominalSource = "unset";
            liveHistory.clear();

            File sohFile = new File(SOH_FILE);
            if (sohFile.exists()) {
                sohFile.delete();
            }

            // Restore the user's persisted nominal from UnifiedConfig. The
            // properties file got wiped above, but UnifiedConfig holds the
            // user's manual override — autoDetectCarModel checks the
            // in-memory nominalSource field, so without re-reading here it
            // falls through to heuristics and produces a different (often
            // wrong) capacity even though the user explicitly set one.
            try {
                JSONObject vehicle = UnifiedConfigManager.getVehicle();
                double userKwh = vehicle.optDouble("nominalKwh", 0);
                if (userKwh >= MIN_PLAUSIBLE_KWH && userKwh <= MAX_PLAUSIBLE_KWH) {
                    nominalCapacityKwh = userKwh;
                    nominalSource = "user";
                    logger.info("SOH estimation RESET — local data cleared, user nominal " +
                        userKwh + " kWh restored from UnifiedConfig.");
                    return;
                }
            } catch (Throwable t) {
                logger.debug("Reset: UnifiedConfig user nominal read failed: " + t.getMessage());
            }
            logger.info("SOH estimation RESET — local data cleared (no user nominal set).");
        }
    }

    // ==================== STATUS ====================

    public org.json.JSONObject getStatus() {
        org.json.JSONObject status = new org.json.JSONObject();
        try {
            status.put("soh", currentSoh > 0 ? Math.round(currentSoh * 10) / 10.0 : -1);
            status.put("nominalCapacityKwh", nominalCapacityKwh);
            double estCap = getEstimatedCapacityKwh();
            status.put("estimatedCapacityKwh", estCap > 0 ? Math.round(estCap * 10) / 10.0 : -1);
            status.put("hasEstimate", hasEstimate());
            status.put("nominalSource", nominalSource);
            status.put("isPhev", isPhevVehicle());

            org.json.JSONObject calibration = new org.json.JSONObject();
            calibration.put("soh", calibrationSoh > 0 ? Math.round(calibrationSoh * 10) / 10.0 : -1);
            calibration.put("timestampMs", calibrationTimestampMs);
            status.put("calibration", calibration);

            double displaySoh;
            String displaySource;
            double oemSoh = getOemSohPercent();
            if (currentSoh > 0) { displaySoh = currentSoh; displaySource = "live"; }
            else if (calibrationSoh > 0) { displaySoh = calibrationSoh; displaySource = "calibration"; }
            // Recovered legacy app code used BYD's StatisticBatteryHealthyIndex
            // for battery health. Keep it as a labeled diagnostics fallback
            // instead of a real estimate because the OEM value's semantics vary
            // by firmware and it is not derived from our capacity calculation.
            else if (oemSoh > 0) { displaySoh = oemSoh; displaySource = "oem"; }
            // PHEV-class models can expose a bogus raw remain-kWh signal that is
            // rejected for real SOH estimation. Keep hasEstimate=false, but give
            // Diagnostics a conservative nominal readout so the card is useful
            // instead of blank while we wait for a trusted calibration source.
            else if (nominalCapacityKwh > 0) { displaySoh = 100.0; displaySource = "nominal"; }
            else { displaySoh = -1; displaySource = "unavailable"; }
            status.put("displaySoh", displaySoh > 0 ? Math.round(displaySoh * 10) / 10.0 : -1);
            status.put("displaySource", displaySource);
            // Always include OEM availability so PHEV debugging can tell the
            // difference between "not a PHEV" and "PHEV but firmware did not
            // return StatisticBatteryHealthyIndex".
            status.put("oemSohAvailable", oemSoh > 0);
            status.put("oemSoh", oemSoh > 0 ? Math.round(oemSoh * 10) / 10.0 : -1);

            // Read fresh — model can change without touching SOH state.
            try {
                JSONObject vehicle = UnifiedConfigManager.getVehicle();
                String modelId = vehicle.optString("modelId", "");
                if (!modelId.isEmpty()) {
                    status.put("modelId", modelId);
                } else {
                    status.put("modelId", JSONObject.NULL);
                }
            } catch (Throwable t) {
                status.put("modelId", JSONObject.NULL);
            }

            File sohFile = new File(SOH_FILE);
            if (sohFile.exists()) {
                Properties props = new Properties();
                try (FileInputStream fis = new FileInputStream(sohFile)) {
                    props.load(fis);
                }
                String lastUpdated = props.getProperty(PROP_LAST_UPDATED);
                if (lastUpdated != null) {
                    status.put("lastUpdated", Long.parseLong(lastUpdated));
                }
            }
        } catch (Exception e) {
            logger.error("Failed to build SOH status: " + e.getMessage());
        }
        return status;
    }

    // ==================== PERSISTENCE ====================

    /**
     * Write a minimal properties file with just schema_version + last_updated.
     * Used by the migration path so the schema bump persists even when no
     * SOH/calibration/nominal data survived the migration.
     */
    private void writeSchemaStamp() {
        try {
            Properties props = new Properties();
            File f = new File(SOH_FILE);
            if (f.exists()) {
                try (FileInputStream fis = new FileInputStream(f)) { props.load(fis); }
            }
            props.setProperty(PROP_SCHEMA_VERSION, String.valueOf(CURRENT_SCHEMA_VERSION));
            props.setProperty(PROP_LAST_UPDATED, String.valueOf(System.currentTimeMillis()));
            // Drop legacy keys that no longer have meaning under v2 — currentSoh
            // and calibration were already cleared in memory and persistEstimate
            // would simply omit them on its next call, but on this path we may
            // never get to a normal persistEstimate before another reboot.
            props.remove(PROP_SOH_PERCENT);
            props.remove(PROP_CALIBRATION_SOH);
            props.remove(PROP_CALIBRATION_TIMESTAMP);
            props.remove(PROP_LIVE_HISTORY);
            if (nominalCapacityKwh > 0) {
                props.setProperty(PROP_NOMINAL_CAPACITY, String.valueOf(nominalCapacityKwh));
                props.setProperty(PROP_NOMINAL_SOURCE, nominalSource);
            }
            try (FileOutputStream fos = new FileOutputStream(SOH_FILE)) {
                props.store(fos, "ABRP SOH Estimate");
            }
            new File(SOH_FILE).setReadable(true, false);
        } catch (Exception e) {
            logger.error("Failed to stamp schema version: " + e.getMessage());
        }
    }

    private void persistEstimate() {
        synchronized (autoDetectLock) {
            if (currentSoh <= 0 && nominalCapacityKwh <= 0
                    && calibrationSoh <= 0 && calibrationTimestampMs <= 0) {
                return;
            }
            try {
                Properties props = new Properties();
                props.setProperty(PROP_SCHEMA_VERSION, String.valueOf(CURRENT_SCHEMA_VERSION));
                if (currentSoh > 0 && currentSoh <= 110) {
                    props.setProperty(PROP_SOH_PERCENT, String.valueOf(currentSoh));
                }
                props.setProperty(PROP_LAST_UPDATED, String.valueOf(System.currentTimeMillis()));
                if (nominalCapacityKwh > 0) {
                    props.setProperty(PROP_NOMINAL_CAPACITY, String.valueOf(nominalCapacityKwh));
                    props.setProperty(PROP_NOMINAL_SOURCE, nominalSource);
                }
                if (calibrationSoh > 0) {
                    props.setProperty(PROP_CALIBRATION_SOH, String.valueOf(calibrationSoh));
                }
                if (calibrationTimestampMs > 0) {
                    props.setProperty(PROP_CALIBRATION_TIMESTAMP, String.valueOf(calibrationTimestampMs));
                }
                if (!liveHistory.isEmpty()) {
                    StringBuilder sb = new StringBuilder();
                    boolean first = true;
                    for (Double v : liveHistory) {
                        if (!first) sb.append(',');
                        sb.append(v);
                        first = false;
                    }
                    props.setProperty(PROP_LIVE_HISTORY, sb.toString());
                }

                try (FileOutputStream fos = new FileOutputStream(SOH_FILE)) {
                    props.store(fos, "ABRP SOH Estimate");
                }
                new File(SOH_FILE).setReadable(true, false);
            } catch (Exception e) {
                logger.error("Failed to persist SOH: " + e.getMessage());
            }
        }
    }

    // ==================== CHEMISTRY-AWARE SCALES ====================

    private static double displayToAbsoluteSocScale(double highCellVoltage) {
        if (!Double.isNaN(highCellVoltage) && highCellVoltage >= 3.75) {
            return 0.95;
        }
        return 1.0;
    }

    private static double scaleDisplaySoc(double displaySoc, double scale) {
        if (scale >= 0.999) return displaySoc;
        return displaySoc * scale + (1.0 - scale) / 2.0 * 100.0;
    }

    // ==================== MAPPINGS ====================

    private static int nearestKnownAh(int rawAh, int toleranceAh) {
        int[] knownAh = {50, 56, 72, 75, 79, 80, 100, 110, 120, 135, 140,
                         150, 153, 157, 166, 170, 176, 180, 200};
        int best = 0;
        int bestDiff = Integer.MAX_VALUE;
        for (int i = 0; i < knownAh.length; i++) {
            int k = knownAh[i];
            int d = Math.abs(rawAh - k);

            int leftGap = (i == 0) ? Integer.MAX_VALUE : (k - knownAh[i - 1]);
            int rightGap = (i == knownAh.length - 1)
                ? Integer.MAX_VALUE
                : (knownAh[i + 1] - k);
            int halfGap = Math.min(leftGap, rightGap) / 2;
            int safeTolerance = Math.min(toleranceAh, halfGap);

            if (d <= safeTolerance && d < bestDiff) {
                bestDiff = d;
                best = k;
            }
        }
        return best;
    }

    private static double mapAhToKwh(int ah) {
        switch (ah) {
            case 150: return 60.48;
            case 153: return 82.56;
            case 157: return 61.44;
            case 140: return 71.8;
            case 170: return 87.0;
            case 166: return 85.44;
            case 120: return 44.9;
            case 135: return 60.48;
            case 100: return 38.0;
            case 80:  return 30.08;
            case 200: return 108.8;
            case 176: return 56.4;
            case 180: return 91.3;
            case 110: return 43.2;
            case 50:  return 18.3;
            case 56:  return 18.3;
            case 72:  return 26.6;
            case 75:  return 26.6;
            case 79:  return 26.6;
            default:  return 0;
        }
    }

    // E6 (71.7 kWh) intentionally omitted: legacy taxi model, virtually
    // indistinguishable from Seal U (71.8 kWh).
    private static final double[] KNOWN_PACK_KWH = {
        8.3, 18.3, 26.6, 30.08, 38.0, 43.2, 44.9, 56.4,
        60.48, 61.44, 71.8, 82.56, 85.44, 87.0, 91.3, 108.8
    };

    private static double matchNearestCapacity(double estimated) {
        return matchNearestCapacity(estimated, Double.NaN, Double.NaN);
    }

    private static double matchNearestCapacity(double estimated,
                                               double packVoltage,
                                               double socPercent) {
        double bestMatch = 0;
        double bestDiff = Double.MAX_VALUE;
        for (double k : KNOWN_PACK_KWH) {
            double diff = Math.abs(estimated - k);
            double tolerance = (k < 40 ? 0.20 : 0.10) * k;
            if (diff > tolerance) continue;
            if (!packVoltagePlausibleForPack(k, packVoltage, socPercent)) continue;
            if (diff < bestDiff) {
                bestDiff = diff;
                bestMatch = k;
            }
        }
        return bestMatch;
    }

    private static boolean packVoltagePlausibleForPack(double kwh,
                                                       double packVoltage,
                                                       double socPercent) {
        if (Double.isNaN(packVoltage) || packVoltage < 200) return true;
        if (Double.isNaN(socPercent) || socPercent < 5 || socPercent > 100) return true;
        int cellCount = cellCountForCapacity(kwh);
        if (cellCount <= 0) return true;
        double impliedCellV = packVoltage / cellCount;
        double minV = lfpMinCellVoltageAt(socPercent);
        double maxV = lfpMaxCellVoltageAt(socPercent);
        return impliedCellV >= minV && impliedCellV <= maxV;
    }

    private static double lfpMinCellVoltageAt(double socPercent) {
        if (socPercent >= 95) return 3.28;
        if (socPercent >= 80) return 3.18;
        if (socPercent >= 50) return 3.10;
        if (socPercent >= 30) return 3.00;
        if (socPercent >= 15) return 2.85;
        if (socPercent >= 5)  return 2.70;
        return 2.50;
    }

    private static double lfpMaxCellVoltageAt(double socPercent) {
        if (socPercent >= 95) return 3.55;
        if (socPercent >= 80) return 3.40;
        if (socPercent >= 50) return 3.30;
        if (socPercent >= 30) return 3.22;
        if (socPercent >= 15) return 3.18;
        if (socPercent >= 5)  return 3.10;
        return 3.00;
    }

    public static int cellCountForCapacity(double nominalKwh) {
        if (matches(nominalKwh, 60.48) || matches(nominalKwh, 60.4))  return 126;
        if (matches(nominalKwh, 61.44))                                return 128;
        if (matches(nominalKwh, 82.56) || matches(nominalKwh, 82.5))   return 172;
        if (matches(nominalKwh, 71.8))                                 return 138;
        if (matches(nominalKwh, 87.0))                                 return 166;
        if (matches(nominalKwh, 85.44))                                return 156;
        if (matches(nominalKwh, 91.3))                                 return 170;
        if (matches(nominalKwh, 108.8))                                return 192;
        if (matches(nominalKwh, 44.9))                                 return 104;
        if (matches(nominalKwh, 30.08))                                return 96;
        if (matches(nominalKwh, 38.0))                                 return 100;
        if (matches(nominalKwh, 43.2))                                 return 96;
        if (matches(nominalKwh, 56.4))                                 return 116;
        if (matches(nominalKwh, 18.3))                                 return 80;
        if (matches(nominalKwh, 26.6))                                 return 84;
        return 0;
    }

    private static boolean matches(double a, double b) {
        return Math.abs(a - b) < 0.5;
    }

    private static double mapCellCountToCapacity(int cellCount) {
        if (cellCount >= 82 && cellCount <= 86)   return 26.6;
        if (cellCount >= 94 && cellCount <= 98)   return 30.08;
        if (cellCount >= 102 && cellCount <= 106) return 44.9;
        if (cellCount >= 114 && cellCount <= 118) return 56.4;
        if (cellCount >= 136 && cellCount <= 140) return 71.8;
        if (cellCount >= 154 && cellCount <= 158) return 85.44;
        if (cellCount >= 164 && cellCount <= 168) return 87.0;
        if (cellCount >= 190 && cellCount <= 194) return 108.8;
        return 0;
    }

    private static double mapCarTypeToCapacity(String carType) {
        String ct = carType.toUpperCase();
        if (ct.contains("SEALION 6") || ct.contains("SEALION6") || ct.contains("SEA LION 6")) return 26.6;
        if (ct.contains("SEALION") || ct.contains("SEA LION")) return 91.3;
        if (ct.contains("SEAL U") || ct.contains("SEALU") || ct.contains("SEAL-U") || ct.contains("S7")) return 71.8;
        if (ct.contains("SEAL")) return 82.56;
        if (ct.contains("HAN") || ct.contains("DM-P")) return 85.44;
        if (ct.contains("TANG")) return 108.8;
        if (ct.contains("ATTO 3") || ct.contains("ATTO3") || ct.contains("YUAN PLUS")) return 60.48;
        if (ct.contains("ATTO 2") || ct.contains("ATTO2")) return 44.9;
        if (ct.contains("ATTO 1") || ct.contains("ATTO1")) return 30.08;
        if (ct.contains("YUAN PRO")) return 38.0;
        if (ct.contains("YUAN")) return 60.48;
        if (ct.contains("DOLPHIN MINI") || ct.contains("SEAGULL")) return 38.0;
        if (ct.contains("DOLPHIN")) return 44.9;
        if (ct.contains("SONG")) return 71.8;
        if (ct.contains("QIN")) return 56.4;
        return 0;
    }
}
