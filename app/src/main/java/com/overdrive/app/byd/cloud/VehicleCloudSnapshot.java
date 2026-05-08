package com.overdrive.app.byd.cloud;

import org.json.JSONObject;

/**
 * Immutable snapshot of vehicle state received from BYD cloud MQTT push.
 * Thread-safe — created via Builder, read from any thread.
 */
public final class VehicleCloudSnapshot {

    // Staleness thresholds
    public static final long LOCK_STATE_MAX_AGE_MS = 5 * 60 * 1000;       // 5 min
    public static final long TELEMETRY_MAX_AGE_MS = 10 * 60 * 1000;       // 10 min
    public static final long CONNECTION_HEALTH_MAX_AGE_MS = 2 * 60 * 1000; // 2 min

    // BYD cloud sentinel values
    private static final int SENTINEL_INT = -1;
    private static final double SENTINEL_TEMP = -129.0;

    // Lock state enum (matches SDK: 1=UNLOCKED, 2=LOCKED)
    public static final int LOCK_UNKNOWN = -1;
    public static final int LOCK_UNAVAILABLE = 0;
    public static final int LOCK_UNLOCKED = 1;
    public static final int LOCK_LOCKED = 2;

    // Online state
    public static final int ONLINE_UNKNOWN = -1;
    public static final int ONLINE = 1;
    public static final int OFFLINE = 2;

    // ── Locks ───────────────────────────────────────────────────────────
    public final int leftFrontDoorLock;
    public final int rightFrontDoorLock;
    public final int leftRearDoorLock;
    public final int rightRearDoorLock;

    // ── Battery / Energy (mergeable) ────────────────────────────────────
    public final double socPercent;          // elecPercent
    public final int elecRangeKm;            // enduranceMileage / enduranceMileageV2
    public final int fuelRangeKm;            // oilEndurance (PHEV)
    public final double fuelPercent;         // oilPercent (PHEV)
    public final int totalMileageKm;         // totalMileage / totalMileageV2

    // ── Charging (mergeable) ────────────────────────────────────────────
    public final int chargingState;          // chargeState (cloud enum)
    public final int remainingHours;
    public final int remainingMinutes;

    // ── Temperature (mergeable) ─────────────────────────────────────────
    public final double insideTempC;         // tempInCar
    public final double outsideTempC;        // tempOutCar

    // ── Air Quality (mergeable) ─────────────────────────────────────────
    public final int pm25Inside;
    public final int pm25Outside;

    // ── Doors (snapshot only, not merged) ───────────────────────────────
    public final int leftFrontDoor;          // 0=CLOSED, 1=OPEN
    public final int rightFrontDoor;
    public final int leftRearDoor;
    public final int rightRearDoor;
    public final int trunkLid;

    // ── Windows (snapshot only, not merged) ─────────────────────────────
    public final int leftFrontWindow;        // 1=CLOSED, 2=OPEN
    public final int rightFrontWindow;
    public final int leftRearWindow;
    public final int rightRearWindow;
    public final int skylight;

    // ── Vehicle state (snapshot only) ───────────────────────────────────
    public final int onlineState;
    public final int vehicleState;
    public final int powerGear;

    // ── Timestamps ──────────────────────────────────────────────────────
    public final long vehicleInfoTimestamp;   // 'time' field from vehicleInfo (unix seconds)
    public final long receivedAt;             // System.currentTimeMillis() when processed

    private VehicleCloudSnapshot(Builder b) {
        this.leftFrontDoorLock = b.leftFrontDoorLock;
        this.rightFrontDoorLock = b.rightFrontDoorLock;
        this.leftRearDoorLock = b.leftRearDoorLock;
        this.rightRearDoorLock = b.rightRearDoorLock;
        this.socPercent = b.socPercent;
        this.elecRangeKm = b.elecRangeKm;
        this.fuelRangeKm = b.fuelRangeKm;
        this.fuelPercent = b.fuelPercent;
        this.totalMileageKm = b.totalMileageKm;
        this.chargingState = b.chargingState;
        this.remainingHours = b.remainingHours;
        this.remainingMinutes = b.remainingMinutes;
        this.insideTempC = b.insideTempC;
        this.outsideTempC = b.outsideTempC;
        this.pm25Inside = b.pm25Inside;
        this.pm25Outside = b.pm25Outside;
        this.leftFrontDoor = b.leftFrontDoor;
        this.rightFrontDoor = b.rightFrontDoor;
        this.leftRearDoor = b.leftRearDoor;
        this.rightRearDoor = b.rightRearDoor;
        this.trunkLid = b.trunkLid;
        this.leftFrontWindow = b.leftFrontWindow;
        this.rightFrontWindow = b.rightFrontWindow;
        this.leftRearWindow = b.leftRearWindow;
        this.rightRearWindow = b.rightRearWindow;
        this.skylight = b.skylight;
        this.onlineState = b.onlineState;
        this.vehicleState = b.vehicleState;
        this.powerGear = b.powerGear;
        this.vehicleInfoTimestamp = b.vehicleInfoTimestamp;
        this.receivedAt = b.receivedAt;
    }

    // ── Freshness checks ────────────────────────────────────────────────

    public boolean isLockStateFresh() {
        return (System.currentTimeMillis() - receivedAt) < LOCK_STATE_MAX_AGE_MS;
    }

    public boolean isTelemetryFresh() {
        return (System.currentTimeMillis() - receivedAt) < TELEMETRY_MAX_AGE_MS;
    }

    public boolean isConnectionHealthy() {
        return (System.currentTimeMillis() - receivedAt) < CONNECTION_HEALTH_MAX_AGE_MS;
    }

    public boolean isOnline() {
        return onlineState == ONLINE;
    }

    // ── Lock state helpers ──────────────────────────────────────────────

    public boolean isAllLocked() {
        return leftFrontDoorLock == LOCK_LOCKED
                && rightFrontDoorLock == LOCK_LOCKED
                && leftRearDoorLock == LOCK_LOCKED
                && rightRearDoorLock == LOCK_LOCKED;
    }

    public boolean isAnyUnlocked() {
        return leftFrontDoorLock == LOCK_UNLOCKED
                || rightFrontDoorLock == LOCK_UNLOCKED
                || leftRearDoorLock == LOCK_UNLOCKED
                || rightRearDoorLock == LOCK_UNLOCKED;
    }

    public boolean hasValidLockState() {
        return leftFrontDoorLock > LOCK_UNAVAILABLE
                || rightFrontDoorLock > LOCK_UNAVAILABLE
                || leftRearDoorLock > LOCK_UNAVAILABLE
                || rightRearDoorLock > LOCK_UNAVAILABLE;
    }

    // ── Mergeable field validity checks ─────────────────────────────────
    // Each check validates against BYD sentinels AND online state

    public boolean hasSoc() {
        return socPercent >= 0 && socPercent <= 100;
    }

    public boolean hasElecRange() {
        return elecRangeKm > 0 || (elecRangeKm == 0 && isOnline());
    }

    public boolean hasFuelRange() {
        return fuelRangeKm >= 0 && fuelRangeKm != SENTINEL_INT;
    }

    public boolean hasFuelPercent() {
        return fuelPercent >= 0 && fuelPercent != SENTINEL_INT;
    }

    public boolean hasTotalMileage() {
        return totalMileageKm > 0;
    }

    public boolean hasChargingState() {
        return chargingState != SENTINEL_INT;
    }

    public boolean hasRemainingHours() {
        return remainingHours >= 0;
    }

    public boolean hasRemainingMinutes() {
        return remainingMinutes >= 0;
    }

    public boolean hasInsideTemp() {
        return insideTempC > SENTINEL_TEMP && !(insideTempC == 0.0 && !isOnline());
    }

    public boolean hasOutsideTemp() {
        return !Double.isNaN(outsideTempC) && !(outsideTempC == 0.0 && !isOnline());
    }

    public boolean hasPm25Inside() {
        return pm25Inside > 0 || (pm25Inside == 0 && isOnline());
    }

    public boolean hasPm25Outside() {
        return pm25Outside > 0 || (pm25Outside == 0 && isOnline());
    }

    // ── Charging state translation ──────────────────────────────────────
    // Cloud: -1=UNKNOWN, 0=NOT_CHARGING, 1=CHARGING, 15=CONNECTED
    // SDK:   0=ready, 1=charging, 2=finished, 3=discharging

    public int getChargingStateAsSdk() {
        switch (chargingState) {
            case 0:  return 0; // NOT_CHARGING → ready
            case 1:  return 1; // CHARGING → charging
            case 15: return 0; // CONNECTED (not charging) → ready
            default: return -1;
        }
    }

    // ── Builder ─────────────────────────────────────────────────────────

    public static Builder fromVehicleInfo(JSONObject vi) {
        return fromVehicleInfo(vi, null);
    }

    /**
     * Parse a vehicleInfo JSON into a Builder.
     * Optionally merges HVAC fields from a separate JSON.
     * All sentinel values are preserved as-is — validity checks happen at read time.
     */
    public static Builder fromVehicleInfo(JSONObject vi, JSONObject hvac) {
        Builder b = new Builder();
        if (vi == null) return b;

        // Locks
        b.leftFrontDoorLock = vi.optInt("leftFrontDoorLock", LOCK_UNKNOWN);
        b.rightFrontDoorLock = vi.optInt("rightFrontDoorLock", LOCK_UNKNOWN);
        b.leftRearDoorLock = vi.optInt("leftRearDoorLock", LOCK_UNKNOWN);
        b.rightRearDoorLock = vi.optInt("rightRearDoorLock", LOCK_UNKNOWN);

        // Battery / Energy
        b.socPercent = vi.optDouble("elecPercent", SENTINEL_INT);
        int rangeV2 = vi.optInt("enduranceMileageV2", SENTINEL_INT);
        b.elecRangeKm = rangeV2 != SENTINEL_INT ? rangeV2 : vi.optInt("enduranceMileage", SENTINEL_INT);
        b.fuelRangeKm = vi.optInt("oilEndurance", SENTINEL_INT);
        b.fuelPercent = vi.optDouble("oilPercent", SENTINEL_INT);
        int mileV2 = vi.optInt("totalMileageV2", 0);
        b.totalMileageKm = mileV2 > 0 ? mileV2 : vi.optInt("totalMileage", 0);

        // Charging
        int cs = vi.optInt("chargeState", SENTINEL_INT);
        b.chargingState = cs != SENTINEL_INT ? cs : vi.optInt("chargingState", SENTINEL_INT);
        b.remainingHours = vi.optInt("remainingHours", SENTINEL_INT);
        b.remainingMinutes = vi.optInt("remainingMinutes", SENTINEL_INT);

        // Temperature — from vehicleInfo or HVAC
        b.insideTempC = vi.optDouble("tempInCar", SENTINEL_TEMP);
        b.outsideTempC = vi.optDouble("tempOutCar", Double.NaN);
        if (hvac != null) {
            double hvacIn = hvac.optDouble("tempInCar", SENTINEL_TEMP);
            if (hvacIn > SENTINEL_TEMP && b.insideTempC <= SENTINEL_TEMP) b.insideTempC = hvacIn;
            double hvacOut = hvac.optDouble("tempOutCar", Double.NaN);
            if (!Double.isNaN(hvacOut) && Double.isNaN(b.outsideTempC)) b.outsideTempC = hvacOut;
        }

        // Air quality
        b.pm25Inside = vi.optInt("pm", SENTINEL_INT);
        b.pm25Outside = vi.optInt("pm25StateOutCar", SENTINEL_INT);
        if (hvac != null) {
            if (b.pm25Inside == SENTINEL_INT) b.pm25Inside = hvac.optInt("pm", SENTINEL_INT);
            if (b.pm25Outside == SENTINEL_INT) b.pm25Outside = hvac.optInt("pm25StateOutCar", SENTINEL_INT);
        }

        // Doors
        b.leftFrontDoor = vi.optInt("leftFrontDoor", SENTINEL_INT);
        b.rightFrontDoor = vi.optInt("rightFrontDoor", SENTINEL_INT);
        b.leftRearDoor = vi.optInt("leftRearDoor", SENTINEL_INT);
        b.rightRearDoor = vi.optInt("rightRearDoor", SENTINEL_INT);
        b.trunkLid = vi.optInt("trunkLid", SENTINEL_INT);

        // Windows
        b.leftFrontWindow = vi.optInt("leftFrontWindow", SENTINEL_INT);
        b.rightFrontWindow = vi.optInt("rightFrontWindow", SENTINEL_INT);
        b.leftRearWindow = vi.optInt("leftRearWindow", SENTINEL_INT);
        b.rightRearWindow = vi.optInt("rightRearWindow", SENTINEL_INT);
        b.skylight = vi.optInt("skylight", SENTINEL_INT);

        // Vehicle state
        b.onlineState = vi.optInt("onlineState", ONLINE_UNKNOWN);
        b.vehicleState = vi.optInt("vehicleState", SENTINEL_INT);
        b.powerGear = vi.optInt("powerGear", SENTINEL_INT);

        // Timestamp
        long time = vi.optLong("time", 0);
        b.vehicleInfoTimestamp = time > 1_000_000_000L ? time : 0;
        b.receivedAt = System.currentTimeMillis();

        return b;
    }

    public static final class Builder {
        int leftFrontDoorLock = LOCK_UNKNOWN;
        int rightFrontDoorLock = LOCK_UNKNOWN;
        int leftRearDoorLock = LOCK_UNKNOWN;
        int rightRearDoorLock = LOCK_UNKNOWN;

        double socPercent = SENTINEL_INT;
        int elecRangeKm = SENTINEL_INT;
        int fuelRangeKm = SENTINEL_INT;
        double fuelPercent = SENTINEL_INT;
        int totalMileageKm = 0;

        int chargingState = SENTINEL_INT;
        int remainingHours = SENTINEL_INT;
        int remainingMinutes = SENTINEL_INT;

        double insideTempC = SENTINEL_TEMP;
        double outsideTempC = Double.NaN;

        int pm25Inside = SENTINEL_INT;
        int pm25Outside = SENTINEL_INT;

        int leftFrontDoor = SENTINEL_INT;
        int rightFrontDoor = SENTINEL_INT;
        int leftRearDoor = SENTINEL_INT;
        int rightRearDoor = SENTINEL_INT;
        int trunkLid = SENTINEL_INT;

        int leftFrontWindow = SENTINEL_INT;
        int rightFrontWindow = SENTINEL_INT;
        int leftRearWindow = SENTINEL_INT;
        int rightRearWindow = SENTINEL_INT;
        int skylight = SENTINEL_INT;

        int onlineState = ONLINE_UNKNOWN;
        int vehicleState = SENTINEL_INT;
        int powerGear = SENTINEL_INT;

        long vehicleInfoTimestamp = 0;
        long receivedAt = System.currentTimeMillis();

        public VehicleCloudSnapshot build() {
            return new VehicleCloudSnapshot(this);
        }
    }
}
