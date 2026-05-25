# 360 Camera Recording

This document explains how Overdrive records the BYD 360 camera feed in two situations:

- Surveillance mode, when ACC is off and sentry detection records motion events.
- Running-car mode, when ACC is on and the app records manually or through recording modes.

The important design point is that both paths use the same camera, GPU composition, and hardware encoder stack. The difference is how recording is triggered, where files are written, and whether the AI surveillance lane is active.

## Core Pipeline

The runtime pipeline is owned by `GpuSurveillancePipeline`.

```text
BYD panoramic camera
  -> PanoramicCameraGpu
  -> HardwareBuffer / GL_TEXTURE_EXTERNAL_OES
  -> GpuMosaicRecorder
  -> MediaCodec input Surface
  -> HardwareEventRecorderGpu
  -> MP4 file
```

The camera usually emits a `5120x960` panoramic strip. `GpuMosaicRecorder` renders that strip into a `2560x1920` encoder surface. The encoder uses Android `MediaCodec` with `COLOR_FormatSurface`, so frames stay on the GPU until MediaCodec produces H.264 or H.265 packets.

Surveillance adds one more branch:

```text
GL camera texture
  -> GpuDownscaler
  -> CPU-readable low-res mosaic
  -> SurveillanceEngineGpu
  -> native motion analysis / AI confirmation
```

The AI branch is only for detection. Disk recording remains the zero-copy GPU-to-encoder path.

## Camera Layout

The default 4-camera BYD strip is arranged horizontally:

```text
0.00-0.25  rear
0.25-0.50  left
0.50-0.75  right
0.75-1.00  front
```

The recording shader maps this strip into a 2x2 mosaic:

```text
+---------+---------+
| front   | right   |
+---------+---------+
| rear    | left    |
+---------+---------+
```

The same layout is used by surveillance detection. Quadrant names are:

- `Q0`: front
- `Q1`: right
- `Q2`: rear
- `Q3`: left

`GpuMosaicRecorder` also supports APA passthrough and a 3-camera layout for models whose panoramic output is not the standard 4-camera strip.

## Encoder Behavior

`HardwareEventRecorderGpu` owns the MediaCodec encoder and muxer. It is initialized for the mosaic dimensions, with runtime quality, FPS, bitrate, and codec settings loaded by the camera daemon.

Key behaviors:

- The encoder surface exists even before a file is being written.
- `GpuMosaicRecorder.drawFrame()` always renders camera frames into the encoder surface.
- While no file is active, encoded frames are retained in a circular pre-record buffer.
- When recording starts, `triggerEventRecording()` creates an MP4 muxer, queues buffered pre-record packets, and begins writing new packets.
- Files are first written as `.tmp` and renamed to `.mp4` only after final muxer close.
- Long recordings rotate into 2-minute segments.
- A drainer thread pulls MediaCodec output, and a disk writer queue protects the GL/camera path from slow storage.
- Backpressure protection skips render submissions if the encoder blocks too long, keeping the BYD camera HAL BufferQueue flowing.

The recorder also corrects timestamps with a moving-average time-base corrector. That smooths uneven camera frame timing before frames are submitted to MediaCodec.

## Surveillance Recording

Surveillance recording is ACC-off, event-driven recording.

1. `CameraDaemon` starts the GPU pipeline when surveillance gates allow it.
2. `GpuSurveillancePipeline.enableSurveillance()` enables `SurveillanceEngineGpu` and disables the telemetry overlay.
3. `PanoramicCameraGpu` keeps feeding the raw 360 texture to the recorder and the downscaler.
4. `GpuDownscaler` supplies reduced frames to `SurveillanceEngineGpu`.
5. Motion detection and AI confirmation decide when an event is real enough to record.
6. `SurveillanceEngineGpu.startRecording()` creates `event_yyyyMMdd_HHmmss.mp4`.
7. `GpuMosaicRecorder.triggerEventRecording()` starts the encoder file and flushes the pre-record buffer.
8. Continued motion extends the post-record window.
9. `SurveillanceEngineGpu.stopRecording()` closes the encoder file, flushes sidecar metadata, and allows storage cleanup.

Surveillance files are written under the active surveillance directory:

```text
/storage/emulated/0/Overdrive/surveillance
```

If SD-card storage is selected and mounted, `StorageManager` points the active surveillance directory at the SD-card `Overdrive/surveillance` path instead.

For long events, each 2-minute MP4 segment gets matching sidecar data and thumbnails. The segment listener updates the current event file so the final metadata belongs to the final segment, not only the first one.

## Running-Car Recording

Running-car recording is ACC-on recording. It uses the same `GpuSurveillancePipeline`, `GpuMosaicRecorder`, and `HardwareEventRecorderGpu`, but it is treated as normal recording rather than surveillance.

Normal recording can start from:

- Manual camera start through `CameraDaemon.startCamera(..., viewOnly=false)`.
- UI/client calls that route to `CameraDaemonClient.startRecording()`.
- `RecordingModeManager` when an ACC-on recording mode is active.
- `ProximityGuardController`, which records only when proximity thresholds are crossed.

Default normal recordings are written under:

```text
/storage/emulated/0/Overdrive/recordings
```

The default filename prefix is `cam`, producing files like:

```text
cam_yyyyMMdd_HHmmss.mp4
```

Proximity guard recordings use:

```text
/storage/emulated/0/Overdrive/proximity
proximity_yyyyMMdd_HHmmss.mp4
```

As with surveillance, SD-card configuration can redirect these active directories to the SD-card `Overdrive` folder.

## ACC-On Recording Modes

`RecordingModeManager` coordinates the car-running behavior.

| Mode | Behavior |
| --- | --- |
| `NONE` | Does not keep the recording pipeline running for ACC-on recording. |
| `CONTINUOUS` | Starts the pipeline and records whenever ACC is on. |
| `DRIVE_MODE` | Records when ACC is on and the gear is `D`, `R`, `N`, `S`, or `M`. `N` is included because BYD Auto Hold can report neutral at traffic lights. |
| `PROXIMITY_GUARD` | Starts the pipeline when gear is not `P`; records only when proximity radar events cross warning thresholds. |

When an ACC-on mode activates, the manager warms BYD AVC/HAL first if the pipeline is not already running. This avoids opening the panoramic camera before BYD's native camera stack is ready.

For `CONTINUOUS` and `DRIVE_MODE`, activation calls:

```text
pipeline.start(false)
pipeline.startRecording()
CameraDaemon.startAvcKeepAliveIfNeeded()
```

For `PROXIMITY_GUARD`, activation starts the pipeline without recording. `ProximityRecordingHandler` later calls:

```text
pipeline.startRecording(proximityDir, "proximity")
```

when a proximity event should become a clip.

## Mode Transitions

Surveillance and normal recording are mutually exclusive at the pipeline mode level.

When ACC turns on:

1. `CameraDaemon.onAccStateChanged(true)` stops surveillance scheduling paths.
2. If surveillance is active, `gpuPipeline.onAccOn()` stops the active event recording and flushes the encoder.
3. The pipeline disables surveillance.
4. The camera is reopened so BYD's native camera app can reacquire its expected camera slot.
5. `RecordingModeManager.onAccStateChanged(true)` activates the configured ACC-on recording mode if its gear gates pass.

When ACC turns off:

1. `RecordingModeManager.onAccStateChanged(false)` stops ACC-on recording.
2. It stops AVC keepalive and tears down the normal recording pipeline.
3. Surveillance startup is handled separately by `CameraDaemon`, after ACC-off gates, schedules, safe locations, and lock state are evaluated.

This separation prevents a normal driving clip from being mistaken for a surveillance event and prevents an active surveillance event from leaving a corrupt MP4 when the car starts.

## Live Viewing And Streaming Relationship

Live viewing is related but separate from disk recording.

The recording encoder writes MP4 files. Streaming uses separate pipeline components in `GpuSurveillancePipeline`, including `GpuStreamScaler`, a separate `HardwareEventRecorderGpu` stream encoder, and `WebSocketStreamServer`. This keeps browser or remote viewing pressure from directly controlling whether the disk recorder is writing a surveillance or normal clip.

Both paths still depend on the same underlying panoramic camera texture. Camera contention, BYD native camera startup, encoder backpressure, or HAL stalls can affect both viewing and recording because the camera source is shared.

## Reliability Guards

The recording implementation has several guards aimed at vehicle-camera reliability:

- Deferred recording start: if `startRecording()` is called before the encoder has produced its format, the request is stored and started when the first encoded format is available.
- Temp-file writes: recordings become visible as final MP4 files only after muxer close.
- Synchronous ACC-on finalization: surveillance recordings are closed before the car-running path takes over.
- Camera yield/reacquire callbacks: the camera can be released and reopened around BYD native camera contention.
- AVC keepalive: ACC-on recording keeps the BYD AVC camera stack warm while Overdrive is using the panoramic feed.
- Storage readiness checks: internal or SD-card directories are prepared before recording starts.
- Space reservation and cleanup: `StorageManager` reserves space and prunes old files after saved clips.
- Backpressure frame skipping: slow encoder/storage paths do not block the camera HAL indefinitely.

## Important Source Files

- `app/src/main/java/com/overdrive/app/surveillance/GpuSurveillancePipeline.java`
- `app/src/main/java/com/overdrive/app/camera/PanoramicCameraGpu.java`
- `app/src/main/java/com/overdrive/app/surveillance/GpuMosaicRecorder.java`
- `app/src/main/java/com/overdrive/app/surveillance/HardwareEventRecorderGpu.java`
- `app/src/main/java/com/overdrive/app/surveillance/SurveillanceEngineGpu.java`
- `app/src/main/java/com/overdrive/app/surveillance/GpuDownscaler.java`
- `app/src/main/java/com/overdrive/app/recording/RecordingModeManager.java`
- `app/src/main/java/com/overdrive/app/proximity/ProximityRecordingHandler.java`
- `app/src/main/java/com/overdrive/app/daemon/CameraDaemon.java`
- `app/src/main/java/com/overdrive/app/storage/StorageManager.java`
