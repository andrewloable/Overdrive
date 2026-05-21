package com.overdrive.app.byd.routing;

import com.overdrive.app.byd.BydDataCollector;
import com.overdrive.app.byd.BydVehicleData;
import com.overdrive.app.byd.cloud.BydCloudClient;
import com.overdrive.app.byd.cloud.BydCloudConfig;
import com.overdrive.app.byd.cloud.BydCloudDataProvider;
import com.overdrive.app.config.UnifiedConfigManager;
import com.overdrive.app.logging.DaemonLogger;

import org.json.JSONObject;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Routes vehicle control commands between BYD cloud REST and the local SDK
 * (BydDataCollector). Each {@link VehicleCommand} declares a capability
 * matrix — which paths it has, which is preferred, what handshake the cloud
 * leg needs — and the router resolves the effective policy from the
 * declaration plus a per-command override under the {@code bydCloud.routePolicy}
 * config section. The result of every dispatch is a structured
 * {@link CommandResult} so callers can render a transparent
 * "sent via cloud" / "sent via direct connection" badge to the UI.
 *
 * <p>Cloud calls run on a single-thread executor with a 12 s budget so a
 * stalled BYD round-trip never blocks the HTTP request beyond {@link
 * #CLOUD_TIMEOUT_MS}. A per-router lock guarantees only one cloud command is
 * in flight at a time — concurrent /control/remoteControl posts trip BYD's
 * rate-limit (response code 6024).
 */
public final class VehicleCommandRouter {

    private static final String TAG = "VehicleCommandRouter";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    private static final long CLOUD_TIMEOUT_MS = 12_000L;
    private static final long CLOUD_TRUNK_UNLOCK_SETTLE_MS = 2_000L;

    /** BYD response code meaning "previous command in progress". */
    private static final String CLOUD_CODE_RATE_LIMITED = "6024";

    private static volatile VehicleCommandRouter instance;

    private final ExecutorService cloudExec;
    private final Object cloudLock = new Object();

    private VehicleCommandRouter() {
        cloudExec = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "VehicleCommandRouter-Cloud");
            t.setDaemon(true);
            return t;
        });
    }

    public static VehicleCommandRouter getInstance() {
        if (instance == null) {
            synchronized (VehicleCommandRouter.class) {
                if (instance == null) instance = new VehicleCommandRouter();
            }
        }
        return instance;
    }

    // ── Public types ────────────────────────────────────────────────────

    /**
     * What a command's declared default preference is when both paths are
     * available. The router may degrade CLOUD_FIRST → SDK if cloud is
     * unavailable, and may upgrade SDK_FIRST → cloud-only when no SDK path
     * exists. CLOUD_ONLY / SDK_ONLY commands skip the alternate path entirely.
     */
    public enum RoutePreference { CLOUD_FIRST, SDK_FIRST, CLOUD_ONLY, SDK_ONLY }

    /**
     * Per-leg capability — does the command have this path, and is it
     * required (no fallback) or optional (router may substitute).
     */
    public enum Capability { NONE, AVAILABLE, REQUIRED }

    /**
     * What the cloud leg needs from the cloud session before it'll dispatch.
     * Kept conservative by default — see {@link VehicleCommand#cloudHandshake()}.
     */
    public enum CloudHandshake {
        /** Stateless config write — only valid creds + VIN. */
        SESSION,
        /** /control/remoteControl — also wants an active MQTT or REST poller. */
        LIVE_CHANNEL
    }

    public enum Outcome { SUCCESS, FAILED, NOT_SUPPORTED, RATE_LIMITED, AUTH_REQUIRED }

    /**
     * Path actually executed. CLOUD_THEN_SDK = cloud tried, fell back to SDK.
     * SDK_THEN_CLOUD = SDK tried, fell back to cloud (rare; happens for
     * SDK_FIRST commands when the local primitive returns false).
     */
    public enum Path { CLOUD, SDK, CLOUD_THEN_SDK, SDK_THEN_CLOUD, NONE }

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
        public static CommandResult authRequired(String msg) {
            return new CommandResult(Outcome.AUTH_REQUIRED, Path.NONE, msg, 0, null);
        }
        public static CommandResult rateLimited(String msg, long latencyMs) {
            return new CommandResult(Outcome.RATE_LIMITED, Path.CLOUD, msg, latencyMs, null);
        }

        public String pathString() {
            switch (path) {
                case CLOUD: return "cloud";
                case SDK: return "local";
                case CLOUD_THEN_SDK: return "cloud-then-local";
                case SDK_THEN_CLOUD: return "local-then-cloud";
                default: return "none";
            }
        }
    }

    // ── Command base ────────────────────────────────────────────────────

    /**
     * Base class for vehicle commands. Subclasses declare the capability
     * matrix (cloud + SDK availability, default preference, cloud handshake)
     * and provide the per-path execution. The router never inspects subclass
     * types — everything flows through these declarations, so adding a new
     * command is "extend, declare capabilities, override the leg(s) you have."
     */
    public static abstract class VehicleCommand {
        public abstract String name();

        /**
         * Whether this command can use the cloud leg.
         * - NONE: no cloud path; SDK is the only option.
         * - AVAILABLE: cloud works; router may pick it or skip it.
         * - REQUIRED: cloud is the only path; missing cloud → AUTH_REQUIRED.
         */
        public Capability cloudCapability() { return Capability.NONE; }

        /**
         * Whether this command can use the SDK leg.
         * - NONE: no SDK path; cloud is the only option.
         * - AVAILABLE: SDK works; router may pick it or skip it.
         * - REQUIRED: SDK is the only path; missing SDK → NOT_SUPPORTED.
         */
        public Capability sdkCapability() { return Capability.NONE; }

        /**
         * What to try first when both legs are available. Ignored when one
         * leg is REQUIRED and the other is NONE.
         */
        public RoutePreference defaultPreference() { return RoutePreference.CLOUD_FIRST; }

        /**
         * Cloud handshake requirement.
         * /control/remoteControl posts (LOCKDOOR, OPENAIR, …) push their result
         * back through MQTT in the BYD app, but in this app we poll the result
         * over HTTP — so a credentials-only check is sufficient. Override to
         * LIVE_CHANNEL only if the command genuinely depends on an active push
         * channel (none of our current commands do).
         */
        public CloudHandshake cloudHandshake() { return CloudHandshake.SESSION; }

        /** /control/remoteControl needs the PIN; /control/smartCharge/* does not. */
        public boolean requiresControlPin() { return true; }

        /**
         * Latency-sensitive commands skip the cloud leg when the vehicle is
         * already awake (SDK is instant; cloud is 5–30 s) — find-car / flash.
         * Overridden for the find/flash commands; default is normal latency.
         */
        public boolean isLatencySensitive() { return false; }

        // ── Execution legs (override the ones you support) ──────────────

        /**
         * Run via cloud. Implementations either return {@link CloudOutcome}
         * with success/rateLimited, or throw if the path is unsupported.
         */
        public CloudOutcome executeViaCloud(BydCloudClient client, String vin) throws Exception {
            return CloudOutcome.unsupported();
        }

        /**
         * Run via SDK. Returns true on success, false on failure.
         */
        public boolean executeViaSdk(BydDataCollector collector) {
            return false;
        }

        // ── Convenience accessors derived from capability declarations ──

        public final boolean hasCloudPath() { return cloudCapability() != Capability.NONE; }
        public final boolean hasSdkPath() { return sdkCapability() != Capability.NONE; }
        public final boolean cloudRequired() { return cloudCapability() == Capability.REQUIRED; }
        public final boolean sdkRequired() { return sdkCapability() == Capability.REQUIRED; }
    }

    /** Cloud execution outcome — success, rate-limited (don't fall back), or unsupported. */
    public static final class CloudOutcome {
        public final boolean success;
        public final boolean rateLimited;
        public final boolean unsupported;
        private CloudOutcome(boolean s, boolean r, boolean u) {
            success = s; rateLimited = r; unsupported = u;
        }
        public static CloudOutcome success() { return new CloudOutcome(true, false, false); }
        public static CloudOutcome failed() { return new CloudOutcome(false, false, false); }
        public static CloudOutcome rateLimited() { return new CloudOutcome(false, true, false); }
        public static CloudOutcome unsupported() { return new CloudOutcome(false, false, true); }
    }

    // ── Concrete commands ───────────────────────────────────────────────
    // Each command's capability declarations form the routing contract. The
    // router never special-cases a command (except TrunkOpenCommand, which is
    // a composite). To add a new control: extend, declare, override legs.

    public static final class LockCommand extends VehicleCommand {
        public String name() { return "lock"; }
        public Capability cloudCapability() { return Capability.REQUIRED; }
        public Capability sdkCapability() { return Capability.NONE; }
        public RoutePreference defaultPreference() { return RoutePreference.CLOUD_ONLY; }
        public CloudOutcome executeViaCloud(BydCloudClient client, String vin) throws Exception {
            return remoteCommand(client, vin, "LOCKDOOR", null, true);
        }
    }

    public static final class UnlockCommand extends VehicleCommand {
        public String name() { return "unlock"; }
        public Capability cloudCapability() { return Capability.REQUIRED; }
        public Capability sdkCapability() { return Capability.NONE; }
        public RoutePreference defaultPreference() { return RoutePreference.CLOUD_ONLY; }
        public CloudOutcome executeViaCloud(BydCloudClient client, String vin) throws Exception {
            return remoteCommand(client, vin, "OPENDOOR", null, true);
        }
    }

    /** Horn + lights — BYD cloud only (no SDK FINDCAR primitive on this gen). */
    public static final class FindCarCommand extends VehicleCommand {
        public String name() { return "find-car"; }
        public Capability cloudCapability() { return Capability.REQUIRED; }
        public Capability sdkCapability() { return Capability.NONE; }
        public RoutePreference defaultPreference() { return RoutePreference.CLOUD_ONLY; }
        public boolean isLatencySensitive() { return true; }
        public CloudOutcome executeViaCloud(BydCloudClient client, String vin) throws Exception {
            return remoteCommand(client, vin, "FINDCAR", null, false);
        }
    }

    /** Lights-only flash — BYD cloud only (no SDK flash primitive on this gen). */
    public static final class FlashLightsCommand extends VehicleCommand {
        public String name() { return "flash"; }
        public Capability cloudCapability() { return Capability.REQUIRED; }
        public Capability sdkCapability() { return Capability.NONE; }
        public RoutePreference defaultPreference() { return RoutePreference.CLOUD_ONLY; }
        public boolean isLatencySensitive() { return true; }
        public CloudOutcome executeViaCloud(BydCloudClient client, String vin) throws Exception {
            return remoteCommand(client, vin, "FLASHLIGHTNOWHISTLE", null, false);
        }
    }

    public static final class ClimateOnCommand extends VehicleCommand {
        public final double tempCelsius;
        public ClimateOnCommand(double t) { this.tempCelsius = t; }
        public String name() { return "climate-on"; }
        public Capability cloudCapability() { return Capability.AVAILABLE; }
        public Capability sdkCapability() { return Capability.AVAILABLE; }
        public RoutePreference defaultPreference() { return RoutePreference.CLOUD_FIRST; }
        public CloudOutcome executeViaCloud(BydCloudClient client, String vin) throws Exception {
            int t = (int) Math.round(Math.max(17, Math.min(33, tempCelsius)));
            JSONObject extra = new JSONObject();
            extra.put("temperature", String.valueOf(t));
            extra.put("copilot_temperature", String.valueOf(t));
            extra.put("cycle_mode", "2");
            extra.put("time_span", "3");
            extra.put("remote_mode", "4");
            return remoteCommand(client, vin, "OPENAIR", extra, true);
        }
        public boolean executeViaSdk(BydDataCollector c) { return c.setAcPower(true); }
    }

    public static final class ClimateOffCommand extends VehicleCommand {
        public String name() { return "climate-off"; }
        public Capability cloudCapability() { return Capability.AVAILABLE; }
        public Capability sdkCapability() { return Capability.AVAILABLE; }
        public RoutePreference defaultPreference() { return RoutePreference.CLOUD_FIRST; }
        public CloudOutcome executeViaCloud(BydCloudClient client, String vin) throws Exception {
            return remoteCommand(client, vin, "CLOSEAIR", null, true);
        }
        public boolean executeViaSdk(BydDataCollector c) { return c.setAcPower(false); }
    }

    public static final class CloseAllWindowsCommand extends VehicleCommand {
        public String name() { return "windows-close-all"; }
        public Capability cloudCapability() { return Capability.AVAILABLE; }
        public Capability sdkCapability() { return Capability.AVAILABLE; }
        public RoutePreference defaultPreference() { return RoutePreference.CLOUD_FIRST; }
        public CloudOutcome executeViaCloud(BydCloudClient client, String vin) throws Exception {
            return remoteCommand(client, vin, "CLOSEWINDOW", null, true);
        }
        public boolean executeViaSdk(BydDataCollector c) {
            return c.setAllWindowsCommand(2); // 2 = close
        }
    }

    public static final class BatteryHeatCommand extends VehicleCommand {
        public final boolean enabled;
        public BatteryHeatCommand(boolean on) { this.enabled = on; }
        public String name() { return "battery-heat"; }
        public Capability cloudCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.CLOUD_ONLY; }
        public CloudOutcome executeViaCloud(BydCloudClient client, String vin) throws Exception {
            JSONObject extra = new JSONObject();
            extra.put("batteryHeatSwitch", enabled ? "1" : "0");
            return remoteCommand(client, vin, "BATTERYHEAT", extra, true);
        }
    }

    // ── Trunk: composite (cloud unlock + SDK tailgate) ──────────────────

    public static final class TrunkOpenCommand extends VehicleCommand {
        // Treated specially in execute() — see executeTrunkOpen().
        public String name() { return "trunk-open"; }
        public Capability cloudCapability() { return Capability.AVAILABLE; }
        public Capability sdkCapability() { return Capability.AVAILABLE; }
        public RoutePreference defaultPreference() { return RoutePreference.CLOUD_FIRST; }
    }

    public static final class TrunkCloseCommand extends VehicleCommand {
        public String name() { return "trunk-close"; }
        public Capability sdkCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_ONLY; }
        public boolean executeViaSdk(BydDataCollector c) { return c.closeTailgate(); }
    }

    public static final class TrunkStopCommand extends VehicleCommand {
        public String name() { return "trunk-stop"; }
        public Capability sdkCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_ONLY; }
        public boolean executeViaSdk(BydDataCollector c) { return c.stopTailgate(); }
    }

    // ── SDK-only commands ───────────────────────────────────────────────

    public static final class WindowMoveCommand extends VehicleCommand {
        public final int area; public final int action; public final Integer targetPercent;
        public WindowMoveCommand(int area, int action, Integer targetPercent) {
            this.area = area; this.action = action; this.targetPercent = targetPercent;
        }
        public String name() { return "window-move"; }
        public Capability sdkCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_ONLY; }
        public boolean executeViaSdk(BydDataCollector c) {
            if (targetPercent != null) return c.moveWindowToPercent(area, targetPercent);
            if (area == 0) return c.setAllWindowsCommand(action);
            return c.setWindowCommand(area, action);
        }
    }

    public static final class ClimateSetTempCommand extends VehicleCommand {
        public final double tempCelsius; public final int zone;
        public ClimateSetTempCommand(int zone, double t) { this.zone = zone; this.tempCelsius = t; }
        public String name() { return "climate-temp"; }
        public Capability sdkCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_ONLY; }
        public boolean executeViaSdk(BydDataCollector c) { return c.setAcTemperature(zone, tempCelsius); }
    }

    public static final class ClimateSetFanCommand extends VehicleCommand {
        public final int level;
        public ClimateSetFanCommand(int l) { this.level = l; }
        public String name() { return "climate-fan"; }
        public Capability sdkCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_ONLY; }
        public boolean executeViaSdk(BydDataCollector c) { return c.setAcFanLevel(level); }
    }

    /**
     * Seat heat / ventilation — cloud first (BYD VENTILATIONHEATING command),
     * with SDK fallback for cars that lack the cloud feature on their region/trim.
     *
     * <p>The cloud command is stateful: it requires the FULL snapshot of all
     * seat states. We track driver+passenger heat+vent locally; the constructor
     * captures the rest of the state at the moment the command is built so
     * unchanged seats retain their level.
     */
    public static final class SeatHeatCommand extends VehicleCommand {
        public final int position; public final int level;
        public final int driverHeat, driverVent, passengerHeat, passengerVent;
        public SeatHeatCommand(int p, int l, int dh, int dv, int ph, int pv) {
            this.position = p; this.level = l;
            this.driverHeat = dh; this.driverVent = dv;
            this.passengerHeat = ph; this.passengerVent = pv;
        }
        public String name() { return "seat-heat"; }
        public Capability cloudCapability() { return Capability.AVAILABLE; }
        public Capability sdkCapability() { return Capability.AVAILABLE; }
        public RoutePreference defaultPreference() { return RoutePreference.CLOUD_FIRST; }
        public CloudOutcome executeViaCloud(BydCloudClient client, String vin) throws Exception {
            String chairType = (position == 1) ? "1" : "2";
            boolean ok = client.setSeatClimate(vin, chairType,
                    driverHeat, driverVent, passengerHeat, passengerVent);
            return ok ? CloudOutcome.success() : CloudOutcome.failed();
        }
        public boolean executeViaSdk(BydDataCollector c) { return c.setSeatHeating(position, level); }
    }

    public static final class SeatVentCommand extends VehicleCommand {
        public final int position; public final int level;
        public final int driverHeat, driverVent, passengerHeat, passengerVent;
        public SeatVentCommand(int p, int l, int dh, int dv, int ph, int pv) {
            this.position = p; this.level = l;
            this.driverHeat = dh; this.driverVent = dv;
            this.passengerHeat = ph; this.passengerVent = pv;
        }
        public String name() { return "seat-vent"; }
        public Capability cloudCapability() { return Capability.AVAILABLE; }
        public Capability sdkCapability() { return Capability.AVAILABLE; }
        public RoutePreference defaultPreference() { return RoutePreference.CLOUD_FIRST; }
        public CloudOutcome executeViaCloud(BydCloudClient client, String vin) throws Exception {
            String chairType = (position == 1) ? "1" : "2";
            boolean ok = client.setSeatClimate(vin, chairType,
                    driverHeat, driverVent, passengerHeat, passengerVent);
            return ok ? CloudOutcome.success() : CloudOutcome.failed();
        }
        public boolean executeViaSdk(BydDataCollector c) { return c.setSeatVentilation(position, level); }
    }

    public static final class SeatMemoryCommand extends VehicleCommand {
        public final int position;
        public SeatMemoryCommand(int p) { this.position = p; }
        public String name() { return "seat-memory"; }
        public Capability sdkCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_ONLY; }
        public boolean executeViaSdk(BydDataCollector c) { return c.setSeatMemoryPosition(position); }
    }

    public static final class LightsCommand extends VehicleCommand {
        public final boolean drlOn;
        public LightsCommand(boolean on) { this.drlOn = on; }
        public String name() { return "lights"; }
        public Capability sdkCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_ONLY; }
        public boolean executeViaSdk(BydDataCollector c) { return c.setDayTimeLight(drlOn); }
    }

    public static final class AdasSpeedLimitWarningCommand extends VehicleCommand {
        public final boolean enabled;
        public AdasSpeedLimitWarningCommand(boolean on) { this.enabled = on; }
        public String name() { return "adas-slw"; }
        public Capability sdkCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_ONLY; }
        public boolean executeViaSdk(BydDataCollector c) { return c.setSpeedLimitWarning(enabled); }
    }

    /**
     * Smart-charging schedule — BYD cloud /control/smartCharge/saveOrUpdate.
     * Wire-compatible with pyBYD's trigger_save_charging_schedule.
     */
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
        public Capability cloudCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.CLOUD_ONLY; }
        public boolean requiresControlPin() { return false; }
        public CloudOutcome executeViaCloud(BydCloudClient client, String vin) throws Exception {
            boolean ok = client.saveChargingSchedule(vin, startChargeTime, endChargeTime, chargeWay, enabled);
            return ok ? CloudOutcome.success() : CloudOutcome.failed();
        }
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
        public Capability sdkCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_ONLY; }
        public boolean executeViaSdk(BydDataCollector c) { return c.setChargeCapPercent(percent); }
    }

    /** BEV charge cap on/off — BYDAutoChargingDevice.setChargeStopSwitchState. */
    public static final class ChargeCapToggleCommand extends VehicleCommand {
        public final boolean enabled;
        public ChargeCapToggleCommand(boolean on) { this.enabled = on; }
        public String name() { return "charge-cap-toggle"; }
        public Capability sdkCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_ONLY; }
        public boolean executeViaSdk(BydDataCollector c) { return c.setChargeCapEnabled(enabled); }
    }

    /** Smart-charge master switch — BYD cloud /control/smartCharge/changeChargeStatue. */
    public static final class SmartChargingToggleCommand extends VehicleCommand {
        public final boolean enabled;
        public SmartChargingToggleCommand(boolean on) { this.enabled = on; }
        public String name() { return "smart-charging-toggle"; }
        public Capability cloudCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.CLOUD_ONLY; }
        public boolean requiresControlPin() { return false; }
        public CloudOutcome executeViaCloud(BydCloudClient client, String vin) throws Exception {
            boolean ok = client.toggleSmartCharging(vin, enabled);
            return ok ? CloudOutcome.success() : CloudOutcome.failed();
        }
    }

    // ── Routing ─────────────────────────────────────────────────────────

    public CommandResult execute(VehicleCommand cmd) {
        if (cmd instanceof TrunkOpenCommand) {
            return executeTrunkOpen();
        }

        // No legs at all — nothing to do.
        if (!cmd.hasCloudPath() && !cmd.hasSdkPath()) {
            return CommandResult.notSupported(msg("not_supported"));
        }

        RoutePreference pref = resolveEffectivePreference(cmd);
        switch (pref) {
            case SDK_ONLY:
                return runSdkOnly(cmd);
            case CLOUD_ONLY:
                return runCloudOnly(cmd);
            case SDK_FIRST:
                return runSdkFirst(cmd);
            case CLOUD_FIRST:
            default:
                return runCloudFirst(cmd);
        }
    }

    /**
     * Resolve the command's effective preference, applying any per-command
     * config override under {@code bydCloud.routePolicy.<name>}, and clamping
     * the result to the command's declared capabilities (e.g., a CLOUD_FIRST
     * command with SDK NONE collapses to CLOUD_ONLY automatically).
     *
     * <p>Valid override values: {@code cloud_first}, {@code sdk_first},
     * {@code cloud_only}, {@code sdk_only}. Anything else is ignored.
     */
    private RoutePreference resolveEffectivePreference(VehicleCommand cmd) {
        RoutePreference pref = cmd.defaultPreference();
        RoutePreference override = readPolicyOverride(cmd.name());
        if (override != null) pref = override;

        // Clamp to the command's actual capabilities so a misconfigured override
        // (e.g., cloud_first on a SDK-only command) doesn't break dispatch.
        RoutePreference clamped;
        if (!cmd.hasCloudPath()) {
            clamped = cmd.hasSdkPath() ? RoutePreference.SDK_ONLY : pref;
        } else if (!cmd.hasSdkPath()) {
            clamped = RoutePreference.CLOUD_ONLY;
        } else if (cmd.cloudRequired() && !cmd.sdkRequired()) {
            clamped = RoutePreference.CLOUD_ONLY;
        } else if (cmd.sdkRequired() && !cmd.cloudRequired()) {
            clamped = RoutePreference.SDK_ONLY;
        } else {
            clamped = pref;
        }

        // Log when an override was actually rejected by the clamp — silent
        // rewrites would leave admins wondering why their override "didn't take".
        if (override != null && clamped != override) {
            logger.info("routePolicy['" + cmd.name() + "']=" + override
                    + " clamped to " + clamped + " (capabilities: cloud="
                    + cmd.cloudCapability() + " sdk=" + cmd.sdkCapability() + ")");
        }
        return clamped;
    }

    private RoutePreference readPolicyOverride(String commandName) {
        try {
            JSONObject root = UnifiedConfigManager.loadConfig();
            if (root == null) return null;
            JSONObject byd = root.optJSONObject("bydCloud");
            if (byd == null) return null;
            JSONObject policy = byd.optJSONObject("routePolicy");
            if (policy == null) return null;
            String raw = policy.optString(commandName, "");
            if (raw.isEmpty()) return null;
            switch (raw.toLowerCase()) {
                case "cloud_first": return RoutePreference.CLOUD_FIRST;
                case "sdk_first": return RoutePreference.SDK_FIRST;
                case "cloud_only": return RoutePreference.CLOUD_ONLY;
                case "sdk_only": return RoutePreference.SDK_ONLY;
                default:
                    logger.warn("Ignoring unknown routePolicy['" + commandName + "']='" + raw + "'");
                    return null;
            }
        } catch (Exception e) {
            return null;
        }
    }

    // ── Per-policy executors ────────────────────────────────────────────

    private CommandResult runSdkOnly(VehicleCommand cmd) {
        if (!cmd.hasSdkPath()) return CommandResult.notSupported(msg("not_supported"));
        long start = System.currentTimeMillis();
        SdkLeg leg = invokeSdk(cmd);
        long elapsed = System.currentTimeMillis() - start;
        if (leg.success) return CommandResult.success(Path.SDK, msg("local_sent"), elapsed);
        return CommandResult.failed(Path.SDK, msg("not_supported"), elapsed, leg.error);
    }

    private CommandResult runCloudOnly(VehicleCommand cmd) {
        if (!cmd.hasCloudPath()) return CommandResult.notSupported(msg("not_supported"));
        if (!cloudHandshakeSatisfied(cmd)) {
            return CommandResult.authRequired(msg("cloud_required"));
        }
        long start = System.currentTimeMillis();
        CloudCallResult cr = runCloudCall(cmd);
        long elapsed = System.currentTimeMillis() - start;
        return mapCloudOnlyResult(cr, elapsed);
    }

    private CommandResult runCloudFirst(VehicleCommand cmd) {
        long start = System.currentTimeMillis();

        // Latency-sensitive override: SDK is instant when the car is awake.
        if (cmd.isLatencySensitive() && isVehicleAwake() && cmd.hasSdkPath()) {
            SdkLeg leg = invokeSdk(cmd);
            if (leg.success) {
                return CommandResult.success(Path.SDK, msg("local_sent"),
                        System.currentTimeMillis() - start);
            }
            // SDK failed despite being awake — fall through to cloud.
        }

        if (cloudHandshakeSatisfied(cmd)) {
            CloudCallResult cr = runCloudCall(cmd);
            long elapsed = System.currentTimeMillis() - start;
            if (cr.outcome == CloudOutcomeKind.SUCCESS) {
                return CommandResult.success(Path.CLOUD, msg("cloud_sent"), elapsed);
            }
            // Rate-limit: don't fall back; the previous command is still
            // executing and the SDK would race it.
            if (cr.outcome == CloudOutcomeKind.RATE_LIMITED) {
                return CommandResult.rateLimited(msg("rate_limited"), elapsed);
            }
            // Cloud failed (timeout, HTTP, controlState=2). Try SDK.
            if (cmd.hasSdkPath()) {
                SdkLeg leg = invokeSdk(cmd);
                long elapsed2 = System.currentTimeMillis() - start;
                if (leg.success) return CommandResult.success(Path.CLOUD_THEN_SDK,
                        msg("cloud_unavailable_used_local"), elapsed2);
                return CommandResult.failed(Path.CLOUD_THEN_SDK,
                        msg("cloud_failed"), elapsed2, cr.error != null ? cr.error : leg.error);
            }
            return CommandResult.failed(Path.CLOUD, msg("cloud_failed"), elapsed, cr.error);
        }

        // Cloud unavailable; SDK fallback if possible.
        if (cmd.hasSdkPath()) {
            SdkLeg leg = invokeSdk(cmd);
            long elapsed = System.currentTimeMillis() - start;
            if (leg.success) return CommandResult.success(Path.SDK,
                    msg("cloud_offline_used_local"), elapsed);
            return CommandResult.failed(Path.SDK, msg("cloud_failed"), elapsed, leg.error);
        }
        return CommandResult.authRequired(msg("cloud_required"));
    }

    /**
     * SDK_FIRST mirror of CLOUD_FIRST — try the local primitive first; if it
     * fails (or the car is asleep and the call would no-op), fall through to
     * cloud. Reserved for commands where SDK is the canonical path but cloud
     * is a viable fallback (no current commands declare this; included for
     * the config-driven override path).
     */
    private CommandResult runSdkFirst(VehicleCommand cmd) {
        long start = System.currentTimeMillis();
        SdkLeg sdkLeg = null;
        if (cmd.hasSdkPath()) {
            sdkLeg = invokeSdk(cmd);
            if (sdkLeg.success) {
                return CommandResult.success(Path.SDK, msg("local_sent"),
                        System.currentTimeMillis() - start);
            }
        }
        // SDK failed or absent — try cloud.
        if (!cmd.hasCloudPath()) {
            // No cloud path. Whether SDK was attempted matters for the message:
            // if it ran and failed, surface that; otherwise it's truly unsupported.
            if (sdkLeg != null) {
                return CommandResult.failed(Path.SDK, msg("cloud_failed"),
                        System.currentTimeMillis() - start, sdkLeg.error);
            }
            return CommandResult.notSupported(msg("not_supported"));
        }
        if (!cloudHandshakeSatisfied(cmd)) {
            // SDK actually ran and returned false — don't blame missing cloud
            // creds for a local primitive that had its turn.
            if (sdkLeg != null) {
                return CommandResult.failed(Path.SDK, msg("cloud_failed"),
                        System.currentTimeMillis() - start, sdkLeg.error);
            }
            return CommandResult.authRequired(msg("cloud_required"));
        }
        CloudCallResult cr = runCloudCall(cmd);
        long elapsed = System.currentTimeMillis() - start;
        if (cr.outcome == CloudOutcomeKind.SUCCESS) {
            return CommandResult.success(Path.SDK_THEN_CLOUD,
                    msg("local_unavailable_used_cloud"), elapsed);
        }
        if (cr.outcome == CloudOutcomeKind.RATE_LIMITED) {
            return CommandResult.rateLimited(msg("rate_limited"), elapsed);
        }
        // BYD endpoint rejected the command shape — distinct from a transient
        // failure. Mirror runCloudOnly's UNSUPPORTED handling.
        if (cr.outcome == CloudOutcomeKind.UNSUPPORTED) {
            return CommandResult.notSupported(msg("not_supported"));
        }
        return CommandResult.failed(Path.SDK_THEN_CLOUD, msg("cloud_failed"), elapsed, cr.error);
    }

    private CommandResult mapCloudOnlyResult(CloudCallResult cr, long elapsed) {
        switch (cr.outcome) {
            case SUCCESS:      return CommandResult.success(Path.CLOUD, msg("cloud_sent"), elapsed);
            case RATE_LIMITED: return CommandResult.rateLimited(msg("rate_limited"), elapsed);
            case UNSUPPORTED:  return CommandResult.notSupported(msg("not_supported"));
            default:           return CommandResult.failed(Path.CLOUD, msg("cloud_failed"), elapsed, cr.error);
        }
    }

    /**
     * Trunk open: cloud unlock first (avoid alarm), then SDK tailgate motor.
     * If cloud unlock fails, do NOT fire the motor — the body controller will
     * trip the alarm if the tailgate opens while the car is locked.
     */
    private CommandResult executeTrunkOpen() {
        long start = System.currentTimeMillis();
        UnlockCommand unlock = new UnlockCommand();
        CommandResult unlockResult = execute(unlock);
        if (unlockResult.outcome != Outcome.SUCCESS) {
            return unlockResult;
        }

        try { Thread.sleep(CLOUD_TRUNK_UNLOCK_SETTLE_MS); }
        catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }

        try {
            boolean ok = BydDataCollector.getInstance().openTailgate();
            long elapsed = System.currentTimeMillis() - start;
            if (ok) return CommandResult.success(Path.CLOUD_THEN_SDK,
                    msg("local_sent"), elapsed);
            return CommandResult.failed(Path.CLOUD_THEN_SDK,
                    msg("not_supported"), elapsed, null);
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            return CommandResult.failed(Path.CLOUD_THEN_SDK,
                    msg("not_supported"), elapsed, e);
        }
    }

    // ── Cloud helpers ───────────────────────────────────────────────────

    private enum CloudOutcomeKind { SUCCESS, FAILED, RATE_LIMITED, UNSUPPORTED }
    private static final class CloudCallResult {
        final CloudOutcomeKind outcome;
        final Throwable error;
        CloudCallResult(CloudOutcomeKind o, Throwable e) { outcome = o; error = e; }
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

    private CloudCallResult runCloudCall(final VehicleCommand cmd) {
        // Serialize cloud commands so we never race two simultaneous BYD
        // remote-control posts from different HTTP threads.
        synchronized (cloudLock) {
            try {
                final BydCloudClient client = BydCloudDataProvider.getInstance().getSharedClient();
                if (client == null) {
                    return new CloudCallResult(CloudOutcomeKind.FAILED,
                            new IllegalStateException("cloud client unavailable"));
                }
                final String vin = BydCloudConfig.fromUnifiedConfig().vin;
                if (vin == null || vin.isEmpty()) {
                    return new CloudCallResult(CloudOutcomeKind.FAILED,
                            new IllegalStateException("VIN missing"));
                }
                Future<CloudOutcome> f = cloudExec.submit(new Callable<CloudOutcome>() {
                    public CloudOutcome call() throws Exception {
                        // /control/remoteControl commands require the PIN handshake;
                        // /control/smartCharge/* and similar config writes do not.
                        if (cmd.requiresControlPin()) {
                            client.verifyControlPassword(vin);
                        }
                        return cmd.executeViaCloud(client, vin);
                    }
                });
                CloudOutcome out = f.get(CLOUD_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                if (out.success) return new CloudCallResult(CloudOutcomeKind.SUCCESS, null);
                if (out.rateLimited) return new CloudCallResult(CloudOutcomeKind.RATE_LIMITED, null);
                if (out.unsupported) return new CloudCallResult(CloudOutcomeKind.UNSUPPORTED, null);
                return new CloudCallResult(CloudOutcomeKind.FAILED, null);
            } catch (TimeoutException te) {
                return new CloudCallResult(CloudOutcomeKind.FAILED, te);
            } catch (ExecutionException ee) {
                return new CloudCallResult(CloudOutcomeKind.FAILED, ee.getCause());
            } catch (Exception e) {
                return new CloudCallResult(CloudOutcomeKind.FAILED, e);
            }
        }
    }

    /**
     * Shared helper for /control/remoteControl commands — issues the POST and
     * maps the BYD response to a {@link CloudOutcome}, recognizing the 6024
     * rate-limit code so the caller can stop the cascade and not race a
     * still-executing command.
     */
    private static CloudOutcome remoteCommand(BydCloudClient client, String vin,
                                              String commandType, JSONObject extra,
                                              boolean waitForResult) throws Exception {
        BydCloudClient.CloudCommandResult r =
                client.executeRemoteCommandWithCode(vin, commandType, extra, waitForResult);
        if (r.success) return CloudOutcome.success();
        if (CLOUD_CODE_RATE_LIMITED.equals(r.code)) return CloudOutcome.rateLimited();
        return CloudOutcome.failed();
    }

    // ── Cloud handshake ─────────────────────────────────────────────────

    /**
     * Returns true iff the cloud leg has what it needs to dispatch. Honors
     * the per-command {@link CloudHandshake} declaration:
     * <ul>
     *   <li>{@code SESSION} — credentials + VIN verified (default).</li>
     *   <li>{@code LIVE_CHANNEL} — also need an active MQTT subscriber or
     *       REST poller (the {@code connected} bit on the data provider).</li>
     * </ul>
     * Most BYD remote-control commands work fine with just SESSION because
     * we poll {@code /control/remoteControlResult} over HTTP — there's no
     * live-channel dependency. Override LIVE_CHANNEL only if a command
     * genuinely needs a push subscription.
     */
    private boolean cloudHandshakeSatisfied(VehicleCommand cmd) {
        if (!cmd.hasCloudPath()) return false;
        try {
            BydCloudConfig cfg = BydCloudConfig.fromUnifiedConfig();
            if (!cfg.isVerified()) return false;
            if (cmd.cloudHandshake() == CloudHandshake.LIVE_CHANNEL) {
                JSONObject status = BydCloudDataProvider.getInstance().getStatusJson();
                return status != null && status.optBoolean("connected", false);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isVehicleAwake() {
        BydVehicleData d = BydDataCollector.getInstance().getData();
        return d != null && d.powerLevel != BydVehicleData.UNAVAILABLE && d.powerLevel >= 2;
    }

    // ── i18n key resolution ─────────────────────────────────────────────

    private static String msg(String key) {
        return com.overdrive.app.server.Messages.get("vehicle_control." + key);
    }
}
