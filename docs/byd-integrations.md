# BYD Integrations

BladeWatch integrates with BYD vehicles through local BYD Android framework APIs available on the head unit. There is no BYD cloud integration path in this codebase; all vehicle data and controls use the local SDK only. (A previous generation used BYD cloud over HTTPS + MQTT v5 with Bangcle white-box crypto; that code has been removed. Commands that only had a cloud implementation now resolve to `NOT_SUPPORTED` — see Vehicle Control below.)

## Android Manifest Permissions

The manifest declares a broad set of BYD and Android permissions. Major categories include:

- Camera, microphone, storage, internet, wake lock, notifications, boot, network, Wi-Fi, location, and overlay permissions.
- BYD AC permissions.
- BYD bodywork permissions.
- BYD instrument permissions.
- BYD engine permissions.
- BYD charging permissions.
- BYD BMS permissions.
- BYD statistic permissions.
- BYD speed permissions.
- BYD gearbox permissions.
- BYD lights permissions.
- BYD energy permissions.
- BYD tyre permissions.
- BYD radar permissions.
- BYD setting permissions.
- BYD door lock permissions.
- BYD safety belt permissions.
- BYD seat permissions.
- BYD sensor permissions.
- BYD PM2.5 permissions.
- BYD multimedia and audio permissions.
- BYD panorama and camera permissions.
- BYD OTA and power permissions.
- BYD ADAS, wiper, mirror, SRS, and security permissions.
- BYDACQUISITION and BYDDIAGNOSTIC permissions.

Many of these permissions are only meaningful on BYD firmware.

## Compile-Time Stubs

The source tree contains `android.hardware.bydauto.*` stubs so the app can compile outside a BYD head-unit environment.

Runtime behavior depends on real BYD framework classes loaded by the Android boot classloader. The stubs are compile-time placeholders and should not be treated as the source of runtime behavior.

## Reflection Helper

`BydDeviceHelper` centralizes local BYD reflection behavior.

Capabilities:

- Load device classes with `Class.forName`.
- Call `getInstance(Context)` factory methods.
- Invoke no-arg, integer, two-integer, and four-integer methods safely.
- Register listener proxies for `android.hardware.IBYDAutoListener`.
- Support typed listener subclasses for abstract listener classes.
- Log and isolate firmware-specific failures.

This pattern lets the app survive missing classes, changed methods, or firmware-specific behavior.

## Local Data Collector

`BydDataCollector` is the main local telemetry collector.

It initializes and reads from device areas including:

- Bodywork.
- Speed.
- Engine.
- Statistic.
- Energy.
- Tyre.
- Charging.
- Door lock.
- Instrument.
- OTA.
- Sensor.
- Gearbox.
- Safety belt.
- AC.
- Light.
- ADAS.
- Radar.
- Power.
- Setting.
- Multimedia.

The collector keeps a thread-safe `BydVehicleData` snapshot for other app systems.

## Polling and Listeners

The collector combines initial reads, polling, and listeners.

Observed behavior:

- Faster polling while ACC is on.
- Slower polling while ACC is off.
- Per-device failures are isolated.
- Listener registration is used for bodywork, charging, engine, door lock, and tyre areas where supported.
- Gearbox listener registration is intentionally avoided because a known BYD API path can crash under shell UID on some firmware.

Mileage conversion considers the instrument mileage unit so miles can be normalized to kilometers.

## Door Lock Semantics

The code contains an important conversion note:

- BYD SDK door lock values use one convention.
- The web API historically used another convention.

Do not change door lock mapping without checking both local SDK behavior and web client expectations.

## Vehicle Control

`VehicleControlApiHandler` exposes vehicle-control HTTP endpoints. All controls go through `VehicleCommandRouter`, which dispatches to the local BYD SDK (`BydDataCollector`) only. The contract is defined by `VehicleService` in `proto/bladewatch/v1/vehicle.proto`; the REST handler is the HTTP mapping of those RPCs.

### Command surface

`VehicleService` RPCs and their `/api/vehicle/*` mappings:

- State reads: `GetState` (`GET /api/vehicle/state`), `GetAcDiagnostics`, `GetSeatDiagnostics`.
- Climate: `SetClimate` (`POST /api/vehicle/climate`) — power on/off, set temperature, fan level, wind mode, and max-cooling. Max-cooling carries restore parameters so prior AC power/temp/fan state can be re-applied when it is turned off.
- Windows: `MoveWindow` (`POST /api/vehicle/window`) — per-window open/close by direction, or closed-loop positioning to a target percent. Window index `0` means all side windows; `1`–`4` are the four doors; `5`/`6` are sunroof/sunshade.
- Seats: `SetSeat` (`POST /api/vehicle/seat`) — per-seat heating and ventilation levels, and seat-memory position recall. The request also carries the full current seat state (driver/passenger heat and vent) so the SDK call is applied against a consistent snapshot.
- Lights/appearance: `SetLights` (`POST /api/vehicle/lights`) — daytime running light (DRL) on/off.
- ADAS: `SetAdas` (`POST /api/vehicle/adas`) — speed-limit-warning on/off.
- Trunk/tailgate: `Trunk` (`POST /api/vehicle/trunk`) — open, close, or stop the tailgate motor. Trunk open invokes the local tailgate motor directly with no remote-unlock pre-step, so a locked vehicle may decline the motor or trip the alarm.
- Charge cap (BEV): `GetChargeCap`/`SetChargeCap` (`/api/vehicle/charge-cap`) — `BYDAutoChargingDevice` stop-capacity percent (50–100%) and on/off switch. The collector probes the framework on first write and reports failure if the value does not stick.

The bodywork range and battery SOC, door/window/trunk/sunroof status, light/ADAS state, seat heat/cool levels, climate setpoint, and tyre pressures are all returned by `GetState` for the UI to render.

### Commands with no local primitive

The following actions were previously implemented through a BYD cloud path that no longer exists in this codebase. Their RPCs and request types are kept for API compatibility, but they have no local SDK path and resolve to `NOT_SUPPORTED`:

- Lock and unlock (`Lock`, `Unlock`).
- Flash lights (`Flash`).
- Find car (`FindCar`).
- Battery heat (`SetBatteryHeat`).
- Charging schedule (`GetChargingSchedule`/`SetChargingSchedule` — readback reports `supported=false` with reason `cloud_not_configured` so the UI hides the section).
- Smart charging master switch.

`VehicleCommandRouter` only exposes `Outcome.{SUCCESS, FAILED, NOT_SUPPORTED, ...}` and `Path.{SDK, NONE}`. Every dispatch returns a structured `CommandResult` whose `outcome`/`path` are surfaced in the JSON response so the UI can render a "sent via direct connection" (local) badge. Cloud-first and cloud-only routing strategies are no longer present.

## GPS / Location

GPS is not read from the BYD SDK. A separate `LocationSidecarService` runs under the app UID, obtains fixes from Android location providers, and pushes updates to the daemon over the surveillance IPC channel (port 19877). `GpsMonitor` (daemon side) receives `updateFromIpc(...)`, caches the last fix to `/data/local/tmp/gps_cache.json`, and feeds `SafeLocationManager` for geofence checks. It rejects `(0,0)` fixes and loads the cached fix on startup for immediate availability.

`GpsApiHandler` exposes the location HTTP surface, mapped from the `VehicleService` GPS RPCs:

- `GetGpsLocation` (`GET /api/gps`) — returns the location JSON (`GpsMonitor.getLocationJson()`: lat/lng, speed, heading, accuracy, altitude, timestamp) plus a Google Maps URL.
- `StartGps` (`POST /api/gps/start`) — starts the sidecar service.
- `StopGps` (`POST /api/gps/stop`) — stops GPS tracking.

The web Location page polls `GetGpsLocation` every few seconds and renders a Leaflet map with a heading-rotated car marker; it is a presentation layer over the same cached fix, not a separate location source.

## 3D Vehicle Hero

The Vehicle Control page shows a rotating 3D model of the car. This is a **web/GPU rendering of the vehicle, not a native BYD SDK feature** — it does not read or control the car. The shipped renderer is **Three.js r147** (with Draco-compressed GLB models under `web/shared/models/`, e.g. Seal, Seal U, Dolphin, Atto 3, Han, Tang, M6, Seagull, Destroyer), served from `web/hero/hero.html`.

It is rendered in a small embedded WebView (`VehicleHeroView`) inside the native vehicle fragment, with `TyreOverlay` floating tyre-pressure and control cards over the full-bleed car background. The native side drives it through an `AndroidHero`/`Hero` JS bridge (`loadModel`, `setColor`, `setRunning`); the selected model and body paint color are user-chosen vehicle-appearance settings, not live telemetry.

History note: an earlier change integrated a native **Filament** 3D engine for the hero, but the head unit's Adreno 610 GL driver crashes under sustained `gltfio` rendering, so the hero was reverted to the proven Three.js WebView stack. The public surface was kept identical to the Filament version. Do not describe the current hero as native Filament.

## Lock Detection for Surveillance

`CameraDaemon` waits for the vehicle to be locked before arming surveillance after ACC turns off. Lock state is determined using the local BYD device SDK door-lock listener and periodic local door-lock polling only. A force-arm timeout fires after roughly 60 seconds if no lock event arrives.

## Safety and Maintenance Notes

- Local BYD APIs are firmware-dependent and must be treated as unstable.
- Reflection calls should keep per-device isolation.
- Avoid listener registration on known-crashing BYD APIs.
- Do not log credentials or BYD-derived secrets.
- Keep local SDK stubs compile-only.

## Source References

- BYD manifest permissions: [AndroidManifest.xml:35](../app/src/main/AndroidManifest.xml#L35), [AndroidManifest.xml:120](../app/src/main/AndroidManifest.xml#L120), [AndroidManifest.xml:193](../app/src/main/AndroidManifest.xml#L193).
- BYD SDK stub strategy and dependencies: [build.gradle.kts:413](../app/build.gradle.kts#L413), [build.gradle.kts:476](../app/build.gradle.kts#L476), [IAccModeManager.java:5](../app/src/main/java/android/os/IAccModeManager.java#L5).
- Local telemetry collector and reflection-based device access: [BydDataCollector.java:20](../app/src/main/java/com/loabletech/bladewatch/byd/BydDataCollector.java#L20), [BydDataCollector.java:247](../app/src/main/java/com/loabletech/bladewatch/byd/BydDataCollector.java#L247), [BydDataCollector.java:3907](../app/src/main/java/com/loabletech/bladewatch/byd/BydDataCollector.java#L3907).
- ACC, gear, and event plumbing: [BydConstants.java:10](../app/src/main/java/com/loabletech/bladewatch/byd/BydConstants.java#L10), [GearMonitor.java:132](../app/src/main/java/com/loabletech/bladewatch/monitor/GearMonitor.java#L132), [CameraDaemon.java:1905](../app/src/main/java/com/loabletech/bladewatch/daemon/CameraDaemon.java#L1905), [CameraDaemon.java:2206](../app/src/main/java/com/loabletech/bladewatch/daemon/CameraDaemon.java#L2206).
- Door lock and surveillance gating: [CameraDaemon.java:1764](../app/src/main/java/com/loabletech/bladewatch/daemon/CameraDaemon.java#L1764), [CameraDaemon.java:1439](../app/src/main/java/com/loabletech/bladewatch/daemon/CameraDaemon.java#L1439), [AccSentryDaemon.java:1900](../app/src/main/java/com/loabletech/bladewatch/daemon/AccSentryDaemon.java#L1900).
- Vehicle control contract and routing: [vehicle.proto:32](../proto/bladewatch/v1/vehicle.proto#L32), [VehicleControlApiHandler.java:43](../app/src/main/java/com/loabletech/bladewatch/server/VehicleControlApiHandler.java#L43), [VehicleCommandRouter.java:17](../app/src/main/java/com/loabletech/bladewatch/byd/routing/VehicleCommandRouter.java#L17), [VehicleCommandRouter.java:315](../app/src/main/java/com/loabletech/bladewatch/byd/routing/VehicleCommandRouter.java#L315).
- Local SDK control primitives: [BydDataCollector.java:3839](../app/src/main/java/com/loabletech/bladewatch/byd/BydDataCollector.java#L3839), [BydDataCollector.java:4803](../app/src/main/java/com/loabletech/bladewatch/byd/BydDataCollector.java#L4803), [BydDataCollector.java:5064](../app/src/main/java/com/loabletech/bladewatch/byd/BydDataCollector.java#L5064).
- GPS / location: [vehicle.proto:51](../proto/bladewatch/v1/vehicle.proto#L51), [GpsApiHandler.java:18](../app/src/main/java/com/loabletech/bladewatch/server/GpsApiHandler.java#L18), [GpsApiHandler.java:24](../app/src/main/java/com/loabletech/bladewatch/server/GpsApiHandler.java#L24), [GpsMonitor.java:23](../app/src/main/java/com/loabletech/bladewatch/monitor/GpsMonitor.java#L23), [GpsMonitor.java:84](../app/src/main/java/com/loabletech/bladewatch/monitor/GpsMonitor.java#L84), [GpsMonitor.java:253](../app/src/main/java/com/loabletech/bladewatch/monitor/GpsMonitor.java#L253).
- 3D vehicle hero (web/native, Three.js — not Filament): [hero.html:15](../app/src/main/assets/web/hero/hero.html#L15), [hero.html:20](../app/src/main/assets/web/hero/hero.html#L20), [VehicleHeroView.kt:18](../app/src/main/java/com/loabletech/bladewatch/ui/fragment/vehicle/VehicleHeroView.kt#L18), [VehicleHeroView.kt:33](../app/src/main/java/com/loabletech/bladewatch/ui/fragment/vehicle/VehicleHeroView.kt#L33), [TyreOverlay.kt:33](../app/src/main/java/com/loabletech/bladewatch/ui/fragment/vehicle/TyreOverlay.kt#L33).
