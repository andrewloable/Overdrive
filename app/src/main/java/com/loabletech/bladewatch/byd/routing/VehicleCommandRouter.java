package com.loabletech.bladewatch.byd.routing;

import com.loabletech.bladewatch.byd.BydDataCollector;
import com.loabletech.bladewatch.logging.DaemonLogger;

/**
 * Routes vehicle control commands to the local BYD SDK ({@link BydDataCollector}).
 *
 * <p>Each {@link VehicleCommand} declares whether it has a local SDK path and
 * provides the per-command execution. Commands with no local primitive on this
 * platform (e.g. remote lock/unlock, find-car, flash, battery heat, smart
 * charging — historically cloud-only features) resolve to
 * {@link Outcome#NOT_SUPPORTED}. Every dispatch returns a structured
 * {@link CommandResult} so callers can render a "sent via direct connection"
 * badge to the UI.
 */
public final class VehicleCommandRouter {

    private static final String TAG = "VehicleCommandRouter";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    private static volatile VehicleCommandRouter instance;

    private VehicleCommandRouter() {}

    public static VehicleCommandRouter getInstance() {
        if (instance == null) {
            synchronized (VehicleCommandRouter.class) {
                if (instance == null) instance = new VehicleCommandRouter();
            }
        }
        return instance;
    }

    // ── Public types ────────────────────────────────────────────────────

    public enum Outcome { SUCCESS, FAILED, NOT_SUPPORTED, RATE_LIMITED, AUTH_REQUIRED }

    /** Path actually executed. */
    public enum Path { SDK, NONE }

    public static final class CommandResult {
        public final Outcome outcome;
        public final Path path;
        public final String displayMessage;
        public final long latencyMs;
        public final Throwable error;

        private CommandResult(Outcome outcome, Path path, String displayMessage,
                              long latencyMs, Throwable error) {
            this.outcome = outcome;
            this.path = path;
            this.displayMessage = displayMessage != null ? displayMessage : "";
            this.latencyMs = latencyMs;
            this.error = error;
        }

        public static CommandResult success(Path path, String msg, long latencyMs) {
            return new CommandResult(Outcome.SUCCESS, path, msg, latencyMs, null);
        }
        public static CommandResult failed(Path path, String msg, long latencyMs, Throwable t) {
            return new CommandResult(Outcome.FAILED, path, msg, latencyMs, t);
        }
        public static CommandResult notSupported(String msg) {
            return new CommandResult(Outcome.NOT_SUPPORTED, Path.NONE, msg, 0, null);
        }

        public String pathString() {
            switch (path) {
                case SDK: return "local";
                default: return "none";
            }
        }
    }

    // ── Command base ────────────────────────────────────────────────────

    /**
     * Base class for vehicle commands. Subclasses with a local primitive
     * override {@link #hasSdkPath()} to return true and implement
     * {@link #executeViaSdk(BydDataCollector)}. Commands without a local path
     * inherit the defaults and resolve to NOT_SUPPORTED.
     */
    public static abstract class VehicleCommand {
        public abstract String name();

        /** Whether this command has a local SDK primitive. */
        public boolean hasSdkPath() { return false; }

        /** Run via SDK. Returns true on success, false on failure. */
        public boolean executeViaSdk(BydDataCollector collector) { return false; }
    }

    // ── Concrete commands ───────────────────────────────────────────────
    // Commands that were historically cloud-only (no local primitive on this
    // generation) keep their class/constructor for API compatibility but have
    // no SDK path, so they resolve to NOT_SUPPORTED.

    public static final class LockCommand extends VehicleCommand {
        public String name() { return "lock"; }
    }

    public static final class UnlockCommand extends VehicleCommand {
        public String name() { return "unlock"; }
    }

    /** Horn + lights — no local FINDCAR primitive on this gen. */
    public static final class FindCarCommand extends VehicleCommand {
        public String name() { return "find-car"; }
    }

    /** Lights-only flash — no local flash primitive on this gen. */
    public static final class FlashLightsCommand extends VehicleCommand {
        public String name() { return "flash"; }
    }

    public static final class ClimateOnCommand extends VehicleCommand {
        public final double tempCelsius;
        public ClimateOnCommand(double t) { this.tempCelsius = t; }
        public String name() { return "climate-on"; }
        public boolean hasSdkPath() { return true; }
        public boolean executeViaSdk(BydDataCollector c) { return c.setAcPower(true); }
    }

    public static final class ClimateOffCommand extends VehicleCommand {
        public String name() { return "climate-off"; }
        public boolean hasSdkPath() { return true; }
        public boolean executeViaSdk(BydDataCollector c) { return c.setAcPower(false); }
    }

    public static final class CloseAllWindowsCommand extends VehicleCommand {
        public String name() { return "windows-close-all"; }
        public boolean hasSdkPath() { return true; }
        public boolean executeViaSdk(BydDataCollector c) {
            return c.setAllWindowsCommand(2); // 2 = close
        }
    }

    public static final class BatteryHeatCommand extends VehicleCommand {
        public final boolean enabled;
        public BatteryHeatCommand(boolean on) { this.enabled = on; }
        public String name() { return "battery-heat"; }
    }

    // ── Trunk ───────────────────────────────────────────────────────────

    public static final class TrunkOpenCommand extends VehicleCommand {
        // Treated specially in execute() — see executeTrunkOpen().
        public String name() { return "trunk-open"; }
    }

    public static final class TrunkCloseCommand extends VehicleCommand {
        public String name() { return "trunk-close"; }
        public boolean hasSdkPath() { return true; }
        public boolean executeViaSdk(BydDataCollector c) { return c.closeTailgate(); }
    }

    public static final class TrunkStopCommand extends VehicleCommand {
        public String name() { return "trunk-stop"; }
        public boolean hasSdkPath() { return true; }
        public boolean executeViaSdk(BydDataCollector c) { return c.stopTailgate(); }
    }

    // ── SDK commands ────────────────────────────────────────────────────

    public static final class WindowMoveCommand extends VehicleCommand {
        public final int area; public final int action; public final Integer targetPercent;
        public WindowMoveCommand(int area, int action, Integer targetPercent) {
            this.area = area; this.action = action; this.targetPercent = targetPercent;
        }
        public String name() { return "window-move"; }
        public boolean hasSdkPath() { return true; }
        public boolean executeViaSdk(BydDataCollector c) {
            if (targetPercent != null && area == 0) return c.moveSideWindowsToPercent(targetPercent);
            if (targetPercent != null) return c.moveWindowToPercent(area, targetPercent);
            if (area == 0) return c.setAllWindowsCommand(action);
            return c.setWindowCommand(area, action);
        }
    }

    public static final class ClimateSetTempCommand extends VehicleCommand {
        public final double tempCelsius; public final int zone;
        public ClimateSetTempCommand(int zone, double t) { this.zone = zone; this.tempCelsius = t; }
        public String name() { return "climate-temp"; }
        public boolean hasSdkPath() { return true; }
        public boolean executeViaSdk(BydDataCollector c) { return c.setAcTemperature(zone, tempCelsius); }
    }

    public static final class ClimateSetFanCommand extends VehicleCommand {
        public final int level;
        public ClimateSetFanCommand(int l) { this.level = l; }
        public String name() { return "climate-fan"; }
        public boolean hasSdkPath() { return true; }
        public boolean executeViaSdk(BydDataCollector c) { return c.setAcFanLevel(level); }
    }

    public static final class ClimateMaxCoolingCommand extends VehicleCommand {
        public final boolean enabled;
        public final boolean hasRestore;
        public final double restoreTempCelsius;
        public final int restoreFanLevel;
        public final boolean restorePowerOn;
        public ClimateMaxCoolingCommand(boolean enabled, boolean hasRestore, double restoreTempCelsius, int restoreFanLevel, boolean restorePowerOn) {
            this.enabled = enabled;
            this.hasRestore = hasRestore;
            this.restoreTempCelsius = restoreTempCelsius;
            this.restoreFanLevel = restoreFanLevel;
            this.restorePowerOn = restorePowerOn;
        }
        public String name() { return "climate-max-cooling"; }
        public boolean hasSdkPath() { return true; }
        public boolean executeViaSdk(BydDataCollector c) {
            return c.setMaxCooling(enabled, hasRestore, restoreTempCelsius, restoreFanLevel, restorePowerOn);
        }
    }

    /** Seat heat — local SDK primitive (position + level). */
    public static final class SeatHeatCommand extends VehicleCommand {
        public final int position; public final int level;
        public final int driverHeat, driverVent, passengerHeat, passengerVent;
        public SeatHeatCommand(int p, int l, int dh, int dv, int ph, int pv) {
            this.position = p; this.level = l;
            this.driverHeat = dh; this.driverVent = dv;
            this.passengerHeat = ph; this.passengerVent = pv;
        }
        public String name() { return "seat-heat"; }
        public boolean hasSdkPath() { return true; }
        public boolean executeViaSdk(BydDataCollector c) { return c.setSeatHeating(position, level); }
    }

    /** Seat ventilation — local SDK primitive (position + level). */
    public static final class SeatVentCommand extends VehicleCommand {
        public final int position; public final int level;
        public final int driverHeat, driverVent, passengerHeat, passengerVent;
        public SeatVentCommand(int p, int l, int dh, int dv, int ph, int pv) {
            this.position = p; this.level = l;
            this.driverHeat = dh; this.driverVent = dv;
            this.passengerHeat = ph; this.passengerVent = pv;
        }
        public String name() { return "seat-vent"; }
        public boolean hasSdkPath() { return true; }
        public boolean executeViaSdk(BydDataCollector c) { return c.setSeatVentilation(position, level); }
    }

    public static final class SeatMemoryCommand extends VehicleCommand {
        public final int position;
        public SeatMemoryCommand(int p) { this.position = p; }
        public String name() { return "seat-memory"; }
        public boolean hasSdkPath() { return true; }
        public boolean executeViaSdk(BydDataCollector c) { return c.setSeatMemoryPosition(position); }
    }

    public static final class LightsCommand extends VehicleCommand {
        public final boolean drlOn;
        public LightsCommand(boolean on) { this.drlOn = on; }
        public String name() { return "lights"; }
        public boolean hasSdkPath() { return true; }
        public boolean executeViaSdk(BydDataCollector c) { return c.setDayTimeLight(drlOn); }
    }

    public static final class AdasSpeedLimitWarningCommand extends VehicleCommand {
        public final boolean enabled;
        public AdasSpeedLimitWarningCommand(boolean on) { this.enabled = on; }
        public String name() { return "adas-slw"; }
        public boolean hasSdkPath() { return true; }
        public boolean executeViaSdk(BydDataCollector c) { return c.setSpeedLimitWarning(enabled); }
    }

    /** Smart-charging schedule was a cloud-only feature — no local primitive. */
    public static final class ChargeScheduleCommand extends VehicleCommand {
        public final String startChargeTime;
        public final String endChargeTime;
        public final String chargeWay;
        public final boolean enabled;
        public ChargeScheduleCommand(String start, String end, String chargeWay, boolean enabled) {
            this.startChargeTime = start;
            this.endChargeTime = end;
            this.chargeWay = chargeWay;
            this.enabled = enabled;
        }
        public String name() { return "charge-schedule"; }
    }

    /**
     * BEV charge cap — BYDAutoChargingDevice.setChargeStopCapacityState (50..100%).
     * Collector probes the framework on first write and reports false if the
     * value didn't stick (the documented Seal HAL behavior).
     */
    public static final class ChargeCapPercentCommand extends VehicleCommand {
        public final int percent;
        public ChargeCapPercentCommand(int p) { this.percent = p; }
        public String name() { return "charge-cap-percent"; }
        public boolean hasSdkPath() { return true; }
        public boolean executeViaSdk(BydDataCollector c) { return c.setChargeCapPercent(percent); }
    }

    /** BEV charge cap on/off — BYDAutoChargingDevice.setChargeStopSwitchState. */
    public static final class ChargeCapToggleCommand extends VehicleCommand {
        public final boolean enabled;
        public ChargeCapToggleCommand(boolean on) { this.enabled = on; }
        public String name() { return "charge-cap-toggle"; }
        public boolean hasSdkPath() { return true; }
        public boolean executeViaSdk(BydDataCollector c) { return c.setChargeCapEnabled(enabled); }
    }

    /** Smart-charge master switch was a cloud-only feature — no local primitive. */
    public static final class SmartChargingToggleCommand extends VehicleCommand {
        public final boolean enabled;
        public SmartChargingToggleCommand(boolean on) { this.enabled = on; }
        public String name() { return "smart-charging-toggle"; }
    }

    // ── Routing ─────────────────────────────────────────────────────────

    public CommandResult execute(VehicleCommand cmd) {
        if (cmd instanceof TrunkOpenCommand) {
            return executeTrunkOpen();
        }
        if (!cmd.hasSdkPath()) {
            return CommandResult.notSupported(msg("not_supported"));
        }
        long start = System.currentTimeMillis();
        SdkLeg leg = invokeSdk(cmd);
        long elapsed = System.currentTimeMillis() - start;
        if (leg.success) return CommandResult.success(Path.SDK, msg("local_sent"), elapsed);
        return CommandResult.failed(Path.SDK, msg("not_supported"), elapsed, leg.error);
    }

    /**
     * Trunk open via the local tailgate motor. The remote-unlock pre-step that
     * previously ran over the cloud is unavailable, so on locked vehicles the
     * body controller may decline the motor or trip the alarm — the local
     * primitive is invoked as-is.
     */
    private CommandResult executeTrunkOpen() {
        long start = System.currentTimeMillis();
        try {
            boolean ok = BydDataCollector.getInstance().openTailgate();
            long elapsed = System.currentTimeMillis() - start;
            if (ok) return CommandResult.success(Path.SDK, msg("local_sent"), elapsed);
            return CommandResult.failed(Path.SDK, msg("not_supported"), elapsed, null);
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            return CommandResult.failed(Path.SDK, msg("not_supported"), elapsed, e);
        }
    }

    private static final class SdkLeg {
        final boolean success;
        final Throwable error;
        SdkLeg(boolean s, Throwable e) { success = s; error = e; }
    }

    private SdkLeg invokeSdk(VehicleCommand cmd) {
        try {
            return new SdkLeg(cmd.executeViaSdk(BydDataCollector.getInstance()), null);
        } catch (Exception e) {
            logger.warn("SDK exec for " + cmd.name() + " threw: " + e.getMessage());
            return new SdkLeg(false, e);
        }
    }

    // ── i18n key resolution ─────────────────────────────────────────────

    private static String msg(String key) {
        return com.loabletech.bladewatch.server.Messages.get("vehicle_control." + key);
    }
}
