# BYD Integrations

Overdrive integrates with BYD vehicles through two main paths:

- Local BYD Android framework APIs available on the head unit.
- BYD cloud HTTPS and MQTT APIs.

The local path is used for low-latency telemetry and some SDK controls. The cloud path is used for account-backed state, remote controls, and deterrent actions where local APIs are unavailable or unreliable.

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

## BYD Cloud Setup

`BydCloudApiHandler` exposes setup and status APIs.

Setup flow:

1. Validate region and country mapping.
2. Validate username.
3. Validate password and optional control PIN.
4. Derive encrypted credentials.
5. Load Bangcle tables.
6. Log in through `BydCloudClient`.
7. Fetch vehicle list.
8. Verify control PIN when provided.
9. Store cloud settings and secrets.

Clear flow wipes credentials and disables cloud state.

## Cloud Transport

`BydCloudTransport` uses OkHttp and a Bangcle request envelope.

Responsibilities:

- Build target URL from configured region base URL and endpoint.
- Encode encrypted request envelope.
- Send JSON body containing the envelope.
- Keep cookies in memory for the session.
- Use the sing-box proxy when available.
- Decode encrypted response envelope.

## Cloud Client

`BydCloudClient` is the high-level cloud API client.

Capabilities:

- Login.
- Vehicle list retrieval.
- Control password verification.
- Remote control command execution.
- Real-time state request and polling.
- MQTT broker, credential, topic, and decrypt-key discovery.
- Synchronized session handling.
- Retry behavior for transient cloud responses.

The implementation is informed by pyBYD/Niek behavior but is implemented locally in this app.

## Cloud MQTT Subscriber

`BydCloudMqttSubscriber` subscribes to BYD cloud vehicle updates.

Key properties:

- Uses Eclipse Paho MQTT v5.
- Connects over SSL.
- Uses BYD-discovered credentials and topics.
- Supports proxy socket factories when sing-box is available.
- Refreshes sessions periodically.
- Uses reconnect backoff.
- Decrypts vehicle info payloads before publishing snapshots.

Paho MQTT v5 is used because MQTT v3 can connect in this environment but does not receive expected vehicle info pushes.

## Cloud Data Provider

`BydCloudDataProvider` and `VehicleCloudSnapshot` provide cloud-derived state to the rest of the app.

Cloud data can supplement local data for values such as:

- Lock state.
- Window state.
- State of charge.
- Cloud-only vehicle state.

Merging behavior is controlled by BYD cloud config.

## Vehicle Control API

`VehicleControlApiHandler` exposes vehicle-control HTTP endpoints.

Action strategy categories:

- Cloud-first: try cloud path when available, with local fallback where appropriate.
- Cloud-only: actions only supported by BYD cloud.
- SDK-only: actions controlled only through local BYD SDK paths.

Endpoint areas include:

- Lock and unlock.
- Trunk.
- Windows.
- Flash lights.
- Find car.
- Climate.
- Seats.
- Lights.
- ADAS.
- Battery heat.
- Charging schedule.
- Charge cap.
- Diagnostics and state reads.

## Deterrent Commands

`BydCloudDeterrent` can trigger cloud commands from surveillance events.

Behavior:

- Fire-and-forget commands.
- Supported actions include flash lights and find car.
- Cooldown and in-flight command suppression.
- Shared cloud client fallback.

This avoids blocking the surveillance pipeline on cloud command latency.

## Bangcle Crypto Assets

BYD cloud requests use Bangcle-related crypto code and tables:

- `BangcleCodec`.
- `BangcleTables`.
- `BangcleBlockCipher`.
- `BydCryptoUtils`.
- `CredentialCipher`.
- Runtime table path: `/data/local/tmp/bangcle_tables.bin`.

The HTTP daemon extracts Bangcle tables from app assets at startup.

## Safety and Maintenance Notes

- Local BYD APIs are firmware-dependent and must be treated as unstable.
- Reflection calls should keep per-device isolation.
- Avoid listener registration on known-crashing BYD APIs.
- Do not log credentials, PINs, cloud session tokens, MQTT credentials, decrypt keys, or Bangcle-derived secrets.
- Do not assume cloud APIs are stable.
- Test vehicle controls carefully; cloud commands can affect the physical car.
- Keep local SDK stubs compile-only.

## Source References

- BYD manifest permissions: [AndroidManifest.xml:35](../app/src/main/AndroidManifest.xml#L35), [AndroidManifest.xml:120](../app/src/main/AndroidManifest.xml#L120), [AndroidManifest.xml:193](../app/src/main/AndroidManifest.xml#L193).
- BYD SDK stub strategy and dependencies: [build.gradle.kts:413](../app/build.gradle.kts#L413), [build.gradle.kts:476](../app/build.gradle.kts#L476), [IAccModeManager.java:5](../app/src/main/java/android/os/IAccModeManager.java#L5).
- Local telemetry collector and reflection-based device access: [BydDataCollector.java:20](../app/src/main/java/com/overdrive/app/byd/BydDataCollector.java#L20), [BydDataCollector.java:247](../app/src/main/java/com/overdrive/app/byd/BydDataCollector.java#L247), [BydDataCollector.java:3907](../app/src/main/java/com/overdrive/app/byd/BydDataCollector.java#L3907).
- ACC, gear, and event plumbing: [BydConstants.java:10](../app/src/main/java/com/overdrive/app/byd/BydConstants.java#L10), [GearMonitor.java:132](../app/src/main/java/com/overdrive/app/monitor/GearMonitor.java#L132), [CameraDaemon.java:1905](../app/src/main/java/com/overdrive/app/daemon/CameraDaemon.java#L1905), [CameraDaemon.java:2206](../app/src/main/java/com/overdrive/app/daemon/CameraDaemon.java#L2206).
- Door lock and surveillance gating: [CameraDaemon.java:1764](../app/src/main/java/com/overdrive/app/daemon/CameraDaemon.java#L1764), [CameraDaemon.java:1439](../app/src/main/java/com/overdrive/app/daemon/CameraDaemon.java#L1439), [AccSentryDaemon.java:1900](../app/src/main/java/com/overdrive/app/daemon/AccSentryDaemon.java#L1900).
- BYD cloud config, setup, and client: [BydCloudConfig.java:13](../app/src/main/java/com/overdrive/app/byd/cloud/BydCloudConfig.java#L13), [BydCloudApiHandler.java:26](../app/src/main/java/com/overdrive/app/server/BydCloudApiHandler.java#L26), [BydCloudApiHandler.java:174](../app/src/main/java/com/overdrive/app/server/BydCloudApiHandler.java#L174), [BydCloudClient.java:22](../app/src/main/java/com/overdrive/app/byd/cloud/BydCloudClient.java#L22).
- BYD cloud MQTT and snapshot merge: [BydCloudMqttSubscriber.java:31](../app/src/main/java/com/overdrive/app/byd/cloud/BydCloudMqttSubscriber.java#L31), [BydCloudDataProvider.java:14](../app/src/main/java/com/overdrive/app/byd/cloud/BydCloudDataProvider.java#L14).
- Vehicle control routing and handlers: [VehicleControlApiHandler.java:43](../app/src/main/java/com/overdrive/app/server/VehicleControlApiHandler.java#L43), [VehicleCommandRouter.java:37](../app/src/main/java/com/overdrive/app/byd/routing/VehicleCommandRouter.java#L37), [VehicleControlApiHandler.java:488](../app/src/main/java/com/overdrive/app/server/VehicleControlApiHandler.java#L488), [VehicleControlApiHandler.java:627](../app/src/main/java/com/overdrive/app/server/VehicleControlApiHandler.java#L627), [VehicleControlApiHandler.java:796](../app/src/main/java/com/overdrive/app/server/VehicleControlApiHandler.java#L796).
- Deterrent commands and crypto assets: [BydCloudDeterrent.java:33](../app/src/main/java/com/overdrive/app/byd/cloud/BydCloudDeterrent.java#L33), [BydCryptoUtils.java:23](../app/src/main/java/com/overdrive/app/byd/cloud/crypto/BydCryptoUtils.java#L23), [BydCloudApiHandler.java:389](../app/src/main/java/com/overdrive/app/server/BydCloudApiHandler.java#L389).
