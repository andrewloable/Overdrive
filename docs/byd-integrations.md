# BYD Integrations

BladeWatch integrates with BYD vehicles through local BYD Android framework APIs available on the head unit. There is no cloud integration path; all vehicle data and controls use the local SDK only.

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

`VehicleControlApiHandler` exposes vehicle-control HTTP endpoints. All controls go through `VehicleCommandRouter` using the local BYD SDK only.

Actions supported via local SDK include:

- Climate.
- Windows.
- Seats.
- Trunk.
- Lights.
- ADAS.
- Charge cap.
- Diagnostics and state reads.

The following actions were previously supported through a cloud path that no longer exists. They now return `NOT_SUPPORTED`:

- Lock and unlock.
- Flash lights.
- Find car.
- Battery heat.
- Charging schedule.
- Smart charging.

`VehicleCommandRouter` routes all requests through SDK-only paths. Cloud-first and cloud-only strategies are no longer present.

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
- Vehicle control routing and handlers: [VehicleControlApiHandler.java:43](../app/src/main/java/com/loabletech/bladewatch/server/VehicleControlApiHandler.java#L43), [VehicleCommandRouter.java:37](../app/src/main/java/com/loabletech/bladewatch/byd/routing/VehicleCommandRouter.java#L37), [VehicleControlApiHandler.java:488](../app/src/main/java/com/loabletech/bladewatch/server/VehicleControlApiHandler.java#L488), [VehicleControlApiHandler.java:627](../app/src/main/java/com/loabletech/bladewatch/server/VehicleControlApiHandler.java#L627), [VehicleControlApiHandler.java:796](../app/src/main/java/com/loabletech/bladewatch/server/VehicleControlApiHandler.java#L796).
