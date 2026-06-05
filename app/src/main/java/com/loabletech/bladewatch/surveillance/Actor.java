package com.overdrive.app.surveillance;

/**
 * Actor — A persistent moving subject around the vehicle.
 *
 * One Actor represents one tracked entity (person, vehicle, bike, animal) across
 * multiple frames and possibly multiple cameras. Actors are produced by
 * {@link ActorTracker} from raw YOLO detections and consumed by:
 *  - {@link EventTimelineCollector}    for the per-recording JSON sidecar
 *  - {@link ThumbnailBuffer}          for picking the peak-severity frame
 *  - {@link SeverityClassifier}       for NOTICE/ALERT/CRITICAL gating
 *  - The notification + UI layers      for human-readable summaries
 *
 * No metric distance: everything that quantifies "how close" is a {@link Proximity}
 * band derived from bbox size in the camera frame, NOT calibrated extrinsics.
 * If extrinsics ever become available, swap the proximity classifier without
 * breaking any consumer.
 */
public final class Actor {

    /** Coarse class taxonomy collapsed from COCO so trackers/UIs deal with 5 things, not 80. */
    public enum ClassGroup {
        PERSON,
        VEHICLE,    // car / bus / truck
        BIKE,       // bicycle / motorcycle
        ANIMAL,
        UNKNOWN
    }

    /**
     * Proximity band — pure pixel-relative classification. Derived from bbox height
     * for persons, bbox width for vehicles. No reliance on camera mounting
     * intrinsics / extrinsics.
     */
    public enum Proximity {
        VERY_CLOSE,   // bbox >= 60% of crop dimension
        CLOSE,        // bbox 35–60%
        MID,          // bbox 15–35%
        FAR,          // bbox <  15%
        UNKNOWN
    }

    /** Trajectory trend over the last few frames (purely pixel-relative). */
    public enum Trend {
        APPROACHING,  // bbox area growing
        RECEDING,     // bbox area shrinking
        STABLE,       // change within noise
        UNKNOWN
    }

    /** Severity emitted by SeverityClassifier; mirrors three-tier gating. */
    public enum Severity {
        NOTICE,   // background / passing-by / static parked car
        ALERT,    // person near vehicle, vehicle approaching, etc.
        CRITICAL  // person at very-close, prolonged dwell at very-close, etc.
    }

    public final long actorId;
    public final ClassGroup classGroup;
    public final long firstSeenWallMs;
    public final long lastSeenWallMs;
    public final long firstSeenRelMs;     // relative to recording start (or -1 if pre-trigger)
    public final long lastSeenRelMs;
    public final int  cameraMask;          // bit per quadrant (0=front,1=right,2=rear,3=left)
    public final Proximity peakProximity;  // closest approach across lifetime
    public final Proximity lastProximity;  // most recent
    public final Trend trend;
    public final boolean isStatic;         // bbox area + position stable for >= STATIC_DWELL_FRAMES
    public final Severity peakSeverity;
    public final long peakSeverityWallMs;
    public final long peakSeverityRelMs;
    public final float peakConfidence;
    public final int peakBboxX;            // bbox at peak severity moment (in crop pixel coords)
    public final int peakBboxY;
    public final int peakBboxW;
    public final int peakBboxH;
    // Crop dimensions the peakBbox is measured against. The pipeline
    // alternates between mosaic (320×240) and foveated (640×640) crops
    // depending on whether the foveated cropper is wired up and whether
    // motion blocks are confirmed in the current frame. peakBbox alone
    // is meaningless without knowing which crop space it's in — readers
    // (ThumbnailBuffer, baseline promotion) MUST use these dims to scale
    // bboxes onto whatever frame they're drawing on. Without this the
    // hero JPEG draws the bbox over a different camera region than the
    // actor actually occupied.
    public final int peakBboxQuadW;
    public final int peakBboxQuadH;
    public final int peakCamera;           // quadrant where peak severity hit
    // Most recent observation — for "what does this actor look like NOW"
    // queries (mid-event baseline promotion, future distance estimation).
    // peakBbox describes the forensic / thumbnail moment; lastBbox the freshest.
    public final int lastBboxX;
    public final int lastBboxY;
    public final int lastBboxW;
    public final int lastBboxH;

    public Actor(long actorId, ClassGroup classGroup,
                 long firstSeenWallMs, long lastSeenWallMs,
                 long firstSeenRelMs, long lastSeenRelMs,
                 int cameraMask,
                 Proximity peakProximity, Proximity lastProximity,
                 Trend trend, boolean isStatic,
                 Severity peakSeverity, long peakSeverityWallMs, long peakSeverityRelMs,
                 float peakConfidence,
                 int peakBboxX, int peakBboxY, int peakBboxW, int peakBboxH,
                 int peakBboxQuadW, int peakBboxQuadH, int peakCamera,
                 int lastBboxX, int lastBboxY, int lastBboxW, int lastBboxH) {
        this.actorId = actorId;
        this.classGroup = classGroup;
        this.firstSeenWallMs = firstSeenWallMs;
        this.lastSeenWallMs = lastSeenWallMs;
        this.firstSeenRelMs = firstSeenRelMs;
        this.lastSeenRelMs = lastSeenRelMs;
        this.cameraMask = cameraMask;
        this.peakProximity = peakProximity;
        this.lastProximity = lastProximity;
        this.trend = trend;
        this.isStatic = isStatic;
        this.peakSeverity = peakSeverity;
        this.peakSeverityWallMs = peakSeverityWallMs;
        this.peakSeverityRelMs = peakSeverityRelMs;
        this.peakConfidence = peakConfidence;
        this.peakBboxX = peakBboxX;
        this.peakBboxY = peakBboxY;
        this.peakBboxW = peakBboxW;
        this.peakBboxH = peakBboxH;
        this.peakBboxQuadW = peakBboxQuadW;
        this.peakBboxQuadH = peakBboxQuadH;
        this.peakCamera = peakCamera;
        this.lastBboxX = lastBboxX;
        this.lastBboxY = lastBboxY;
        this.lastBboxW = lastBboxW;
        this.lastBboxH = lastBboxH;
    }

    /** Map a COCO class ID to a coarse group. */
    public static ClassGroup groupOf(int cocoClassId) {
        if (cocoClassId == 0) return ClassGroup.PERSON;
        if (cocoClassId == 2 || cocoClassId == 5 || cocoClassId == 7) return ClassGroup.VEHICLE;
        if (cocoClassId == 1 || cocoClassId == 3) return ClassGroup.BIKE;
        if (cocoClassId >= 14 && cocoClassId <= 23) return ClassGroup.ANIMAL;
        return ClassGroup.UNKNOWN;
    }

    public static String groupLabel(ClassGroup g) {
        switch (g) {
            case PERSON:  return "person";
            case VEHICLE: return "vehicle";
            case BIKE:    return "bike";
            case ANIMAL:  return "animal";
            default:      return "object";
        }
    }

    public static String proximityLabel(Proximity p) {
        switch (p) {
            case VERY_CLOSE: return "very close";
            case CLOSE:      return "close";
            case MID:        return "mid";
            case FAR:        return "far";
            default:         return "unknown";
        }
    }

    public static String severityLabel(Severity s) {
        switch (s) {
            case CRITICAL: return "CRITICAL";
            case ALERT:    return "ALERT";
            default:       return "NOTICE";
        }
    }
}
