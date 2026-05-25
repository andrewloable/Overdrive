# Surveillance Implementation

This document explains how Overdrive implements surveillance, also called sentry mode, from vehicle state changes through camera processing, motion classification, recording, notifications, deterrents, and APIs.

## Summary

Surveillance is implemented as an ACC-off parking workflow owned by `CameraDaemon`, `AccSentryDaemon`, and `GpuSurveillancePipeline`.

At a high level:

```text
Vehicle ACC state changes
  -> AccSentryDaemon detects ACC OFF or ACC ON
  -> SurveillanceIpcServer notifies CameraDaemon
  -> CameraDaemon applies user preference, safe-zone, schedule, and door-lock gates
  -> GpuSurveillancePipeline starts the camera and arms SurveillanceEngineGpu
  -> GPU downscaled frames feed MotionPipelineV2 at 10 FPS
  -> native per-quadrant motion pipeline classifies threats
  -> optional YOLO confirms objects and tracks actors
  -> event recording starts from the circular pre-record buffer
  -> post-record timer, tracker state, and residual motion decide when to stop
  -> sidecar metadata, thumbnails, push notifications, Telegram, and deterrents are emitted
```

Core source files:

- `app/src/main/java/com/overdrive/app/daemon/CameraDaemon.java`
- `app/src/main/java/com/overdrive/app/daemon/AccSentryDaemon.java`
- `app/src/main/java/com/overdrive/app/surveillance/GpuSurveillancePipeline.java`
- `app/src/main/java/com/overdrive/app/surveillance/SurveillanceEngineGpu.java`
- `app/src/main/java/com/overdrive/app/surveillance/MotionPipelineV2.java`
- `app/src/main/java/com/overdrive/app/surveillance/NativeMotion.java`
- `app/src/main/cpp/surveillance/motion_pipeline_v2.h`
- `app/src/main/cpp/surveillance/motion_pipeline_v2.cpp`
- `app/src/main/cpp/surveillance/texture_tracker.cpp`
- `app/src/main/java/com/overdrive/app/server/SurveillanceApiHandler.java`
- `app/src/main/java/com/overdrive/app/server/SurveillanceIpcServer.java`

## Runtime Components

### `AccSentryDaemon`

`AccSentryDaemon` runs as a shell-launched daemon and focuses on vehicle power state.

Responsibilities:

- Monitor ACC state through BYD bodywork/power APIs.
- Notify `CameraDaemon` when ACC turns off or on.
- Keep the system alive during ACC-off sentry mode.
- Manage low-voltage and charging maintenance behavior.
- Start Telegram daemon during sentry mode when configured.
- Disable surveillance on critical battery conditions.

It does not own motion detection. It tells `CameraDaemon` whether the vehicle is entering or leaving sentry conditions.

### `CameraDaemon`

`CameraDaemon` owns the central surveillance state machine.

Responsibilities:

- Initialize `GpuSurveillancePipeline`.
- Keep `AccMonitor` state synchronized from IPC and hardware probes.
- Start surveillance when ACC is off and all gates pass.
- Stop surveillance when ACC is on.
- Enforce safe locations and schedule windows.
- Gate arming on door lock.
- Expose surveillance status and config through HTTP and IPC.
- Coordinate storage, telemetry, trip, and recording-mode transitions.

### `GpuSurveillancePipeline`

`GpuSurveillancePipeline` owns the camera/GL/encoder pipeline shared by normal recording, surveillance, and streaming.

Responsibilities:

- Select and start the panoramic camera.
- Initialize GPU recorder, downscaler, and surveillance engine.
- Wire camera frames to `GpuMosaicRecorder`, `GpuDownscaler`, and `SurveillanceEngineGpu`.
- Switch between `IDLE`, `NORMAL_RECORDING`, and `SURVEILLANCE` modes.
- Keep a shared recorder for normal recording and surveillance event recording.
- Stop surveillance before manual recording starts.
- Reinitialize encoder safely when bitrate, FPS, or codec changes.

### `SurveillanceEngineGpu`

`SurveillanceEngineGpu` is the hot path for motion detection and event decisions.

Responsibilities:

- Accept downscaled RGB mosaic frames from the GPU path.
- Throttle motion processing to 10 FPS.
- Run native V2 per-quadrant motion detection.
- Track sustained motion and loitering duration.
- Run optional YOLO detection on active quadrants.
- Maintain cross-quadrant and texture trackers.
- Start and stop event recording.
- Emit notifications and Telegram messages.
- Trigger BYD cloud deterrents.
- Write event metadata and thumbnails.

### Native Motion Pipeline

`MotionPipelineV2` is a Java wrapper over native code in `motion_pipeline_v2.cpp`.

The native layer processes a `640x480` RGB 2x2 mosaic split into four quadrants:

- Q0: front.
- Q1: right.
- Q2: rear.
- Q3: left.

Each quadrant is `320x240` and uses a `10x7` block grid with `32px` blocks. There are 70 blocks per quadrant.

## Activation Flow

### ACC OFF

When ACC turns off:

1. `AccSentryDaemon` detects the transition and sends ACC state to `CameraDaemon`.
2. `CameraDaemon.onAccStateChanged(true)` handles the sentry entry.
3. Active drive/continuous recording is finalized first.
4. Telemetry and gear polling are stopped or reduced.
5. SD card storage is force-remounted if any configured storage target uses SD.
6. The persisted surveillance preference is checked.
7. Safe-location suppression is checked.
8. Schedule windows are checked.
9. The GPU pipeline is started if needed.
10. The pipeline is put in `SENTRY` recording mode.
11. Door-lock gate is registered.
12. The schedule checker starts for later time-window transitions.

### Door-Lock Gate

Surveillance is not armed immediately when ACC turns off. `CameraDaemon` waits for the vehicle to be locked so the owner exiting the car does not trigger motion.

Lock sources run in parallel:

- BYD cloud MQTT lock state.
- Local BYD device SDK door-lock listener.
- Periodic local door-lock polling.
- A force-arm timeout after roughly 60 seconds.

All sources converge through an idempotent `applyLockEvent()` path:

- Locked: call `enableSurveillance()`.
- Unlocked: call `disableSurveillance()`.
- ACC ON during any callback: ignore the lock event.

An ACC-on disarm watchdog also polls hardware state as a reverse fallback.

### ACC ON

When ACC turns on:

1. Schedule checker stops.
2. Door-lock gate listeners and polling are cleaned up.
3. Safe-zone suppression state is cleared.
4. Context-dependent components may be reinitialized.
5. Gear and telemetry systems resume.
6. If the pipeline was in surveillance mode, `gpuPipeline.onAccOn()` finalizes event recording and releases surveillance state.
7. The pipeline is stopped to save power unless another mode needs it.

## Safe Locations

`SafeLocationManager` implements geofence suppression.

Data path:

```text
LocationSidecarService
  -> UPDATE_GPS command on SurveillanceIpcServer
  -> GpsMonitor
  -> SafeLocationManager.onLocationUpdate()
  -> CameraDaemon suppression/resume
```

Safe locations are stored in:

```text
/data/local/tmp/safe_locations.json
```

Implementation details:

- Maximum zones: 10.
- Distance calculation: Haversine.
- GPS updates are expected roughly every two seconds while the sidecar is active.
- Entering a safe zone stops surveillance but preserves the user's enabled preference.
- Leaving a safe zone resumes surveillance if the persisted preference is still enabled.
- If ACC is off and the car is already in a safe zone, the camera does not open.

## Schedule Windows

`SurveillanceSchedule` stores optional day/time rules.

Behavior:

- Disabled schedule means always allowed.
- Enabled schedule with no rules also means always allowed.
- Rules can span midnight.
- Days are stored as `0=Sunday` through `6=Saturday`.
- `CameraDaemon` checks the schedule at ACC-off entry.
- A periodic checker runs during ACC-off sentry mode about every five minutes.
- If a window ends, surveillance stops.
- If a window starts and the user preference is enabled, surveillance starts unless safe-zone suppression applies.

## Camera and GPU Pipeline

Surveillance uses the same panoramic camera pipeline as recording.

Important design points:

- Camera input is handled by `PanoramicCameraGpu`.
- Camera output is consumed by the recorder, downscaler, and surveillance engine.
- The recorder uses GPU composition and hardware encoding.
- The downscaler produces the small RGB mosaic used for motion detection.
- The surveillance engine receives borrowed frame buffers and must recycle them in a `finally` block.
- Camera selection can use saved validated config, BMM discovery, default tuple, or auto-probe fallback.
- The pipeline avoids AVC keep-alive pokes during ACC-off surveillance because those can perturb camera FPS over time.

## Motion Detection Pipeline

Motion processing is capped at 10 FPS:

```text
small RGB mosaic frame
  -> direct ByteBuffer
  -> MotionPipelineV2.processFrame()
  -> native V2 pipeline
  -> per-quadrant results
```

Native V2 stages:

1. Global brightness check.
2. Per-block luma and edge analysis.
3. Temporal confidence accumulation/decay.
4. Spatial coherence through connected components.
5. Behavioral classification.
6. Result packing.

Threat levels:

- `THREAT_NONE`: no relevant motion.
- `THREAT_LOW`: passing motion.
- `THREAT_MEDIUM`: approaching motion.
- `THREAT_HIGH`: loitering motion.

Only `THREAT_MEDIUM` and higher are candidates for recording.

Important thresholds:

- Grid: 10 columns x 7 rows per quadrant.
- Block size: 32 pixels.
- Default sensitivity level: 3.
- Default detection zone: normal.
- Default loitering time: 3 seconds.
- Base sustained-motion requirement: 500 ms.
- Medium threat requires the configured loitering duration.
- High threat can trigger after the base sustained-motion period because native classification already indicates loitering.

## Brightness, Shadow, and Noise Filtering

The native pipeline includes multiple false-positive filters:

- Brightness shift suppression for light flashes and headlight sweeps.
- Slow baseline adaptation for gradual lighting drift.
- Luma/edge dual-gate activation.
- Shadow discrimination using chrominance preservation.
- Shadow block suppression.
- Oscillation filtering for rapidly flickering blocks such as tree shadows.
- Spatial connected-component checks.
- Environment presets for outdoor, garage, and street.
- Night and glare tuning paths.

The Java layer adds additional guards:

- Per-quadrant stricter post-filtering after native runs with the most permissive aggregate config.
- AI confirmation gates for medium threats.
- Special deterrent-window gating to avoid self-triggering on the app's own flash-lights command.
- Tracker immunity for person tracks during brightness suppression, so a tracked person is not lost during a headlight sweep.

## Detection Zones and Sensitivity

Detection zone filters map centroid position to distance relevance:

- `close`: bottom rows only, stricter for near-car motion.
- `normal`: default parking-range behavior.
- `extended`: all rows, no distance cutoff.

Sensitivity levels map to native gates:

- Level 1: stricter density, component, and confidence thresholds.
- Level 3: balanced default.
- Level 5: lowest thresholds for aggressive detection.

Per-quadrant overrides are supported. The native pipeline runs with the most permissive aggregate sensitivity and zone, then Java demotes results that do not satisfy each quadrant's effective stricter settings. This avoids running native detection four separate times.

## ROI

ROI support exists at quadrant level.

Flow:

```text
Web/API config
  -> SurveillanceConfig ROI polygon or block mask
  -> SurveillanceEngineGpu.applyQuadrantRoi()
  -> NativeMotion.setQuadrantRoi()
  -> native block mask
```

Implementation details:

- ROI polygons are normalized coordinates per quadrant.
- Polygons are rasterized into the native 10x7 block mask.
- A block is enabled if its center lies inside the polygon.
- Clearing ROI restores all blocks for that quadrant.
- Persisted direct block masks can also be loaded from unified config.

## AI Detection

YOLO detection is optional but integrated into event decisions.

Implementation details:

- Detector: `YoloDetector`.
- Model asset: `assets/models/yolo11n.tflite`.
- Runtime: TensorFlow Lite with GPU, NNAPI, or CPU fallback depending on availability.
- AI executor: single low-priority background thread.
- AI cooldown: 500 ms.
- Queue: bounded, de-duplicated quadrant queue with at most four entries.
- Class filters: person, vehicle, bike classes based on config.
- If all classes are disabled, YOLO is unloaded to reclaim native memory.

AI runs on selected active quadrants rather than every frame. The pipeline queues the highest-threat quadrant first, then other active quadrants.

YOLO is used for:

- Confirming medium-threat motion.
- Reducing lighting false positives.
- Creating actor records.
- Updating thumbnails and event metadata.
- Starting native texture tracks.
- Seeding and refreshing a static-scene detection baseline.

## Baseline and Static Object Filtering

On surveillance enable, the engine resets `DetectionBaseline`. After camera warmup, it seeds the baseline by running YOLO across quadrants so parked cars, trash cans, and other existing objects can be treated as background.

During and after events:

- Last YOLO detections update the baseline for the event quadrant.
- Static non-person actors can be promoted into baseline mid-event.
- Baseline refresh is skipped or delayed while recording is active.

This is designed to avoid repeated recordings for stationary objects that are visible before the motion event.

## Tracking

Tracking has two layers.

### Native Texture Tracker

Native tracking in `texture_tracker.cpp` uses template/NCC style tracking after YOLO starts a track.

It is used to:

- Keep recording alive while a person stands still and motion blocks drop to zero.
- Bridge cross-quadrant or block-boundary gaps.
- Request periodic YOLO heartbeat verification when confidence drops or the heartbeat interval expires.

Only person tracks are allowed to hold recording open or override brightness suppression.

### Actor Tracker

`ActorTracker` sits above YOLO and native tracking.

It emits actor records used by:

- Event timeline JSON.
- Hero thumbnails.
- Per-actor thumbnails.
- Notification severity.
- Telegram captions.
- UI badges and filters.

Actor state is reset between surveillance sessions and after event finalization so actor IDs and dwell windows do not leak across recordings.

## Recording Lifecycle

The hardware encoder continuously buffers frames. When motion is confirmed, surveillance triggers event recording.

Start flow:

1. Confirm medium/high threat and duration.
2. Run AI gate checks where applicable.
3. Set `recordingStopTime = now + postRecordMs`.
4. Call `startRecording()`.
5. Build `event_yyyyMMdd_HHmmss.mp4`.
6. Trigger `GpuMosaicRecorder.triggerEventRecording()`.
7. Flush pre-record buffer into the event file.
8. Start timeline collection using the actual encoder pre-record duration.
9. Emit initial notification paths.
10. Dispatch deterrent action if configured.

Stop flow:

1. Wait until post-record timeout.
2. Extend timeout if residual active/confirmed blocks remain.
3. Extend timeout if a person texture track remains active.
4. Stop event recording synchronously.
5. Flush segment metadata.
6. Write timeline sidecar.
7. Write hero and actor thumbnails.
8. Fall back to extracting a hero frame from MP4 when no YOLO hero exists.
9. Publish finalized push and Telegram notifications.
10. Reset actor and thumbnail state.

Long events can rotate into multiple MP4 segments. A segment listener flushes metadata for each closed segment so later segments keep thumbnails, actor counts, and sidecar data.

## Event Metadata

Surveillance events produce more than MP4 files.

Per-segment metadata includes:

- Timeline JSON sidecar.
- Motion spans.
- Actor records.
- Actor severity and proximity.
- Hero JPEG.
- Per-actor thumbnails.
- Notification identifiers.

The timeline collector uses actual pre-record duration from the encoder so motion timestamps align with the video frames, even when the H.264 circular buffer flushes more than the nominal pre-record window because of keyframe boundaries.

## Notifications and Telegram

Surveillance has two notification phases:

- Start phase: low-latency "recording in progress" style signal.
- Final phase: rich notification with threat summary and hero image.

Notification severity is derived from actor state when available. Telegram follows similar start/final behavior, with config flags for start pings and per-tier mute behavior.

If no actor data exists, notifications fall back to generic motion wording.

## BYD Cloud Deterrent

`BydCloudDeterrent` is called when motion is confirmed.

Supported actions:

- `silent`: default, no action.
- `flash_lights`: BYD cloud flash-lights command.
- `find_car`: horn/lights find-car command.

Design details:

- Fire-and-forget from the surveillance thread.
- Runs in a single low-priority background executor.
- Enforces cooldown.
- Prevents overlapping commands.
- Reuses BYD cloud shared client when available.
- Never throws back into the motion pipeline.

`SurveillanceEngineGpu` tracks the deterrent dispatch time and suppresses self-triggering during the expected light-flash window.

## HTTP APIs

`SurveillanceApiHandler` exposes:

- `GET /api/surveillance/config`.
- `POST /api/surveillance/config`.
- `GET /api/surveillance/status`.
- `POST /api/surveillance/enable`.
- `POST /api/surveillance/disable`.
- `GET /api/surveillance/heatmap`.
- `GET /api/surveillance/snapshot/{quadrant}`.
- `GET /api/surveillance/filterlog`.

`SafeLocationApiHandler` exposes:

- `GET /api/surveillance/safe-locations`.
- `POST /api/surveillance/safe-locations`.
- `PUT /api/surveillance/safe-locations?id=...`.
- `DELETE /api/surveillance/safe-locations?id=...`.
- `POST /api/surveillance/safe-locations/toggle`.

The API persists settings through `UnifiedConfigManager` and `SurveillanceConfigManager`, then applies changes to a running engine when one exists.

## IPC Commands

`SurveillanceIpcServer` listens on:

```text
127.0.0.1:19877
```

Relevant command areas:

- `START`.
- `STOP`.
- `STATUS`.
- `ENABLE_SURVEILLANCE`.
- `DISABLE_SURVEILLANCE`.
- `GET_CONFIG`.
- `SET_CONFIG`.
- `GET_STATUS`.
- `GET_ROI`.
- `SET_ROI`.
- `GET_SAFE_LOCATIONS`.
- `ADD_SAFE_LOCATION`.
- `UPDATE_SAFE_LOCATION`.
- `DELETE_SAFE_LOCATION`.
- `TOGGLE_SAFE_LOCATIONS`.
- `UPDATE_GPS`.

`ENABLE_SURVEILLANCE` persists the user preference. It does not blindly start motion detection if ACC is on.

## Configuration

Main persisted surveillance config lives under the `surveillance` section of:

```text
/data/local/tmp/overdrive_config.json
```

Default values include:

- `surveillanceEnabled`: false.
- `minObjectSize`: 0.08 in unified defaults, with runtime config defaults around 0.12 depending path.
- `aiConfidence`: 0.25.
- `flashImmunity`: 2.
- `detectPerson`: true.
- `detectCar`: true.
- `detectBike`: false.
- `preRecordSeconds`: 5.
- `postRecordSeconds`: 10.
- `blockSize`: 32.
- `requiredBlocks`: 3.
- `sensitivity`: 0.04.
- `deterrentAction`: silent.
- `deterrentCooldownSeconds`: 15.

Runtime `SurveillanceConfig` also tracks:

- Environment preset: outdoor, garage, street.
- Detection zone: close, normal, extended.
- Loitering time: 1 to 10 seconds.
- Sensitivity level: 1 to 5.
- Per-camera enable flags.
- Motion heatmap flag.
- Filter debug flag.
- Shadow filter mode.
- Per-quadrant sensitivity and zone overrides.
- Per-quadrant ROI polygons.
- Schedule rules.
- Notification and Telegram tier toggles.

## Storage

Surveillance recordings are written through `StorageManager`.

Main base directory:

```text
/storage/emulated/0/Overdrive/surveillance
```

Storage behavior:

- Surveillance can use internal storage or SD card.
- SD card mount is checked at event start.
- SD card can be force-remounted when ACC goes off.
- Cleanup is periodic and asynchronous rather than blocking the motion trigger thread.
- StorageManager is told when surveillance is active so cleanup policy can account for active recording.

## Guardrails

Important safety and race-condition guards:

- ACC-on check exists in `CameraDaemon.enableSurveillance()`.
- ACC-on check also exists inside `SurveillanceEngineGpu.enable()` and `processFrame()`.
- Pending ACC-off state is discarded if ACC becomes on before pipeline init completes.
- Door-lock callbacks are ignored if ACC is on.
- Safe zone suppression preserves preference but does not open the camera.
- Schedule suppression preserves preference but does not arm outside the window.
- Event recording is stopped before disabling surveillance.
- AI state writes use generation counters so late AI results cannot pollute finalized recordings.
- The AI queue is bounded to four quadrants.
- YOLO runs on a low-priority executor to avoid starving camera/encoder threads.
- Storage cleanup is moved off the trigger thread.
- Per-segment metadata is flushed during rotation so long events keep usable library metadata.

## Current Limitations

- Surveillance depends on BYD firmware ACC, door-lock, camera, and power behavior.
- Motion detection is tuned for a 2x2 360-camera mosaic and assumes the quadrant layout used by the GPU downscaler.
- AI is opportunistic and gated; motion remains the primary trigger source.
- BYD cloud deterrents require configured BYD cloud credentials and network availability.
- Safe locations depend on recent GPS data.
- Schedule checks are periodic after ACC-off entry, so window transitions are not instantaneous beyond explicit config-change enforcement.
- If the camera HAL fails or returns blank frames, the pipeline relies on camera validation and auto-probe fallback rather than surveillance-specific recovery.
