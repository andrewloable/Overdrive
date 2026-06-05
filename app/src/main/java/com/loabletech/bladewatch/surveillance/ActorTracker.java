package com.overdrive.app.surveillance;

import com.overdrive.app.ai.Detection;
import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.surveillance.Actor.ClassGroup;
import com.overdrive.app.surveillance.Actor.Proximity;
import com.overdrive.app.surveillance.Actor.Severity;
import com.overdrive.app.surveillance.Actor.Trend;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * ActorTracker — Persistent tracker that turns per-frame YOLO detections into
 * lifetime-aware {@link Actor} records.
 *
 * Design notes:
 *  - Sits on top of the existing motion+YOLO pipeline; does NOT replace it.
 *  - One tracker instance per surveillance engine; tracks across cameras.
 *  - Association: greedy IoU within the same quadrant; class-group must match.
 *    Cross-quadrant handoff is deferred to {@link CrossQuadrantTracker} which
 *    keeps doing what it does today — this tracker assigns its own actorIds and
 *    is independent.
 *  - Proximity is pixel-relative (no extrinsics). Calibrated thresholds on
 *    bbox-dim/quadrant-dim ratio.
 *  - Trend = sign of bbox-area change over the last {@code TREND_WINDOW} updates.
 *  - Static = bbox area + position stable for {@code STATIC_FRAMES_NEEDED}+ updates.
 *  - All inputs are in QUADRANT pixel coordinates (the same coordinate space
 *    SurveillanceEngineGpu uses today after foveated→quadrant scaling at lines
 *    1597–1598). The caller is responsible for any coordinate normalisation.
 */
public final class ActorTracker {

    private static final DaemonLogger logger = DaemonLogger.getInstance("ActorTracker");

    /** Active tracks live this long without an update before being pruned. */
    private static final long TRACK_TTL_MS = 5000;

    /** Hard upper bound on simultaneous tracks. */
    private static final int MAX_TRACKS = 32;

    /** IoU below this is not a match. */
    private static final float MATCH_IOU_MIN = 0.20f;

    /** History window for trend + static decision. */
    private static final int TREND_WINDOW = 6;

    /** How many consecutive stable observations classify "static" (persons + bikes). */
    private static final int STATIC_FRAMES_NEEDED = 8;

    /**
     * Vehicles get a much shorter static window. The classic failure to prevent:
     * a parked car that DetectionBaseline missed (e.g. arrived between event-end
     * baseline updates) reaches the Actor layer with a fresh track. With
     * STATIC_FRAMES_NEEDED=8 the Actor would be non-static for ~800ms and the
     * SeverityClassifier could escalate it to ALERT. 2 frames (~200ms at 10 fps)
     * means the second consecutive frame already classifies it as static and
     * caps severity at NOTICE — mirroring the intuition that a vehicle is only
     * a threat when it's *moving toward us*.
     */
    private static final int STATIC_FRAMES_NEEDED_VEHICLE = 2;

    /** Bbox-area drift below this counts as "stable" for static detection. */
    private static final float STATIC_AREA_DRIFT_FRAC = 0.10f;

    /** Bbox-centroid drift (pixels) below this counts as "stable" for static detection. */
    private static final int STATIC_CENTROID_DRIFT_PX = 10;

    // Proximity thresholds — pixel-relative ratios of bbox dim to quadrant dim
    // (quadrant = 320×240 in mosaic mode; foveated path is rescaled to quadrant first).
    private static final float PROX_VERY_CLOSE = 0.60f;
    private static final float PROX_CLOSE      = 0.35f;
    private static final float PROX_MID        = 0.15f;

    private long nextActorId = 1;
    private final List<Track> tracks = new ArrayList<>();
    private final long bornWallMs;

    public ActorTracker() {
        this.bornWallMs = System.currentTimeMillis();
    }

    /**
     * Process a batch of detections from one quadrant for one frame and return
     * the updated actor view (snapshot). Caller may pass an empty list to age
     * tracks without adding new observations.
     *
     * @param detections    YOLO detections, in QUADRANT pixel coords (top-left origin)
     * @param quadrant      Quadrant index 0..3 (front/right/rear/left)
     * @param quadrantW     Width of the coord space the bboxes live in (e.g. 320)
     * @param quadrantH     Height of the coord space the bboxes live in (e.g. 240)
     * @param recordingStartWallMs  Recording start wall-clock; pass 0 if not recording
     * @param wallNowMs     Wall-clock for this frame
     * @return Immutable list of all currently-active Actors (across all quadrants)
     */
    public synchronized List<Actor> update(List<Detection> detections,
                                           int quadrant,
                                           int quadrantW,
                                           int quadrantH,
                                           long recordingStartWallMs,
                                           long wallNowMs) {
        return update(detections, null, quadrant, quadrantW, quadrantH,
                      recordingStartWallMs, wallNowMs);
    }

    /**
     * Variant of {@link #update(List, int, int, int, long, long)} that accepts
     * a parallel array of cross-quadrant track ID hints (one per detection,
     * or {@code 0} for no hint).
     *
     * When a hint is present, the matching pass first tries to find an
     * existing Track with the same {@code xqTrackId} regardless of quadrant.
     * This fixes the "same physical person crosses front→right and gets two
     * actorIds" bug: the cross-quadrant tracker has already assigned a
     * persistent ID; we just bind the Actor to it.
     *
     * If no hinted match is found, falls back to the original per-quadrant +
     * IoU + class-group match. Detections without a hint use the legacy path.
     */
    public synchronized List<Actor> update(List<Detection> detections,
                                           int[] xqTrackIdHints,
                                           int quadrant,
                                           int quadrantW,
                                           int quadrantH,
                                           long recordingStartWallMs,
                                           long wallNowMs) {
        pruneStale(wallNowMs);

        if (detections != null && !detections.isEmpty()) {
            for (int i = 0; i < detections.size(); i++) {
                Detection d = detections.get(i);
                ClassGroup group = Actor.groupOf(d.getClassId());
                if (group == ClassGroup.UNKNOWN) continue;

                int hint = (xqTrackIdHints != null && i < xqTrackIdHints.length)
                        ? xqTrackIdHints[i] : 0;

                Track best = null;

                // Path A: cross-quadrant trackId match (any quadrant). This is
                // the primary identity signal — same xqTrackId means the
                // CrossQuadrantTracker says it's the same physical thing.
                if (hint != 0) {
                    for (Track t : tracks) {
                        if (t.classGroup == group && t.xqTrackId == hint) {
                            best = t;
                            break;
                        }
                    }
                }

                // Path B: per-quadrant IoU fallback (legacy behaviour). Only
                // runs when there's no hinted Track. We also gracefully bind
                // the cross-quadrant trackId to a same-quadrant IoU match if
                // both end up describing the same Track — keeps subsequent
                // frames stable.
                if (best == null) {
                    float bestIou = MATCH_IOU_MIN;
                    for (Track t : tracks) {
                        if (t.quadrant != quadrant) continue;
                        if (t.classGroup != group) continue;
                        float iou = iou(t.lastX, t.lastY, t.lastW, t.lastH,
                                        d.getX(), d.getY(), d.getW(), d.getH());
                        if (iou > bestIou) {
                            bestIou = iou;
                            best = t;
                        }
                    }
                }

                if (best == null) {
                    if (tracks.size() >= MAX_TRACKS) {
                        evictOldest(wallNowMs);
                    }
                    best = new Track(nextActorId++, group, quadrant);
                    tracks.add(best);
                }
                if (hint != 0 && best.xqTrackId == 0) {
                    best.xqTrackId = hint;
                }
                best.observe(d, quadrant, quadrantW, quadrantH, recordingStartWallMs, wallNowMs);
            }
        }

        // Build snapshot for callers
        List<Actor> snapshot = new ArrayList<>(tracks.size());
        for (Track t : tracks) {
            snapshot.add(t.toActor());
        }
        return snapshot;
    }

    /**
     * Reset tracker state (e.g. when a recording finishes or the user toggles
     * surveillance off).
     */
    public synchronized void reset() {
        tracks.clear();
        nextActorId = 1;
    }

    /** Read-only count of currently-active tracks. */
    public synchronized int activeTrackCount() {
        return tracks.size();
    }

    // ---------- internal -----------------------------------------------------

    private void pruneStale(long now) {
        Iterator<Track> it = tracks.iterator();
        while (it.hasNext()) {
            Track t = it.next();
            if (now - t.lastSeenWallMs > TRACK_TTL_MS) {
                it.remove();
            }
        }
    }

    private void evictOldest(long now) {
        Track oldest = null;
        for (Track t : tracks) {
            if (oldest == null || t.lastSeenWallMs < oldest.lastSeenWallMs) {
                oldest = t;
            }
        }
        if (oldest != null) tracks.remove(oldest);
    }

    private static float iou(int ax, int ay, int aw, int ah,
                             int bx, int by, int bw, int bh) {
        int x1 = Math.max(ax, bx);
        int y1 = Math.max(ay, by);
        int x2 = Math.min(ax + aw, bx + bw);
        int y2 = Math.min(ay + ah, by + bh);
        int interW = Math.max(0, x2 - x1);
        int interH = Math.max(0, y2 - y1);
        int inter = interW * interH;
        int union = aw * ah + bw * bh - inter;
        return union > 0 ? (float) inter / union : 0f;
    }

    /** Per-Actor mutable state. */
    private static final class Track {
        final long actorId;
        final ClassGroup classGroup;
        int quadrant;
        // Cross-quadrant track ID (from CrossQuadrantTracker). When non-zero,
        // this Actor is bound to a cross-camera identity that survives quadrant
        // boundaries. The merge hint in update() lets us look up an existing
        // Actor by xqTrackId regardless of which quadrant it currently lives
        // in — fixes the "person walks front→right gets two actorIds" bug.
        int xqTrackId = 0;

        long firstSeenWallMs = 0;
        long lastSeenWallMs = 0;
        long firstSeenRelMs = -1;
        long lastSeenRelMs = -1;

        int lastX, lastY, lastW, lastH;
        int lastQuadW = 0, lastQuadH = 0;
        int cameraMask = 0;

        // History for trend / static
        final float[] areaHistory = new float[TREND_WINDOW];
        final int[] cxHistory = new int[TREND_WINDOW];
        final int[] cyHistory = new int[TREND_WINDOW];
        int historyCount = 0;
        int stableFrames = 0;

        // Peak severity bookkeeping
        Severity peakSeverity = Severity.NOTICE;
        long peakSeverityWallMs = 0;
        long peakSeverityRelMs = -1;
        Proximity peakProximity = Proximity.UNKNOWN;
        float peakConfidence = 0f;
        int peakBboxX, peakBboxY, peakBboxW, peakBboxH;
        // Crop dimensions peakBbox was measured against — see Actor.peakBboxQuadW/H.
        int peakBboxQuadW, peakBboxQuadH;
        int peakCamera;

        // Dwell at current peak proximity
        long peakProxStartWallMs = 0;

        Track(long id, ClassGroup g, int quadrant) {
            this.actorId = id;
            this.classGroup = g;
            this.quadrant = quadrant;
            this.peakCamera = quadrant;
        }

        void observe(Detection d, int newQuadrant, int quadW, int quadH,
                     long recordingStartWallMs, long wallNowMs) {
            if (firstSeenWallMs == 0) {
                firstSeenWallMs = wallNowMs;
                if (recordingStartWallMs > 0) {
                    firstSeenRelMs = wallNowMs - recordingStartWallMs;
                }
            }
            lastSeenWallMs = wallNowMs;
            lastSeenRelMs = recordingStartWallMs > 0 ? wallNowMs - recordingStartWallMs : -1;

            quadrant = newQuadrant;
            cameraMask |= (1 << (newQuadrant & 0x03));
            lastQuadW = quadW;
            lastQuadH = quadH;

            int x = d.getX();
            int y = d.getY();
            int w = d.getW();
            int h = d.getH();

            float prevArea = lastW > 0 ? (float)(lastW * lastH) : 0f;
            float curArea = (float)(w * h);

            int cx = x + w / 2;
            int cy = y + h / 2;

            // Stability check (against previous observation, not full history)
            if (lastW > 0) {
                float drift = prevArea > 0 ? Math.abs(curArea - prevArea) / prevArea : 1f;
                int dCx = Math.abs(cx - (lastX + lastW / 2));
                int dCy = Math.abs(cy - (lastY + lastH / 2));
                if (drift < STATIC_AREA_DRIFT_FRAC
                        && dCx < STATIC_CENTROID_DRIFT_PX
                        && dCy < STATIC_CENTROID_DRIFT_PX) {
                    if (stableFrames < Integer.MAX_VALUE - 1) stableFrames++;
                } else {
                    stableFrames = 0;
                }
            }

            lastX = x; lastY = y; lastW = w; lastH = h;

            // Roll history
            int slot = historyCount % TREND_WINDOW;
            areaHistory[slot] = curArea;
            cxHistory[slot] = cx;
            cyHistory[slot] = cy;
            historyCount++;

            // Compute proximity from bbox dimension relative to quadrant dim.
            // For people use height (taller-than-wide); for vehicles use width.
            float ratio;
            if (classGroup == ClassGroup.VEHICLE) {
                ratio = quadW > 0 ? (float) w / quadW : 0f;
            } else {
                ratio = quadH > 0 ? (float) h / quadH : 0f;
            }
            Proximity prox = ratioToProximity(ratio);

            // Update peak proximity (smaller ordinal = closer)
            if (peakProximity == Proximity.UNKNOWN
                    || prox.ordinal() < peakProximity.ordinal()) {
                peakProximity = prox;
                peakProxStartWallMs = wallNowMs;
                // Refresh peakBbox + its crop space whenever proximity
                // upgrades (got closer). The thumbnail capture rule is
                // "the moment threat was highest", and a closer actor
                // is more threatening even if the severity tier hasn't
                // changed. Without this, ThumbnailBuffer would see a
                // score increase (proximity bumped) but the actor's
                // peakBbox would still be in the OLD frame's crop space
                // — and the bbox-vs-rgb alignment guard would refuse
                // to update the slot, leaving a stale crop on disk.
                peakBboxX = x; peakBboxY = y; peakBboxW = w; peakBboxH = h;
                peakBboxQuadW = quadW;
                peakBboxQuadH = quadH;
                // Without this, a person crossing front → right quadrant
                // whose proximity bumped but severity stayed at ALERT
                // would have peakBbox set to right-camera coords but
                // peakCamera stuck on front. ThumbnailBuffer.observe
                // gates on `a.peakCamera != camera` and would reject the
                // right-frame, leaving the hero stuck on the older,
                // less-close moment from the front camera.
                peakCamera = newQuadrant;
            } else if (prox == peakProximity) {
                // continue dwell
            } else {
                // moved further; reset dwell
                peakProxStartWallMs = wallNowMs;
            }

            long dwellMs = wallNowMs - peakProxStartWallMs;
            int staticThreshold = (classGroup == ClassGroup.VEHICLE)
                    ? STATIC_FRAMES_NEEDED_VEHICLE : STATIC_FRAMES_NEEDED;
            Severity sev = SeverityClassifier.classify(classGroup, prox, peakProximity,
                    computeTrend(), stableFrames >= staticThreshold, dwellMs);

            // Track peak severity moment for thumbnail capture
            boolean upgradeSev = sev.ordinal() > peakSeverity.ordinal();
            boolean tieBetterConf = sev == peakSeverity && d.getConfidence() > peakConfidence;
            if (upgradeSev || tieBetterConf) {
                peakSeverity = sev;
                peakSeverityWallMs = wallNowMs;
                peakSeverityRelMs = recordingStartWallMs > 0 ? wallNowMs - recordingStartWallMs : -1;
                peakConfidence = d.getConfidence();
                peakBboxX = x; peakBboxY = y; peakBboxW = w; peakBboxH = h;
                // Snapshot the crop dims THIS frame's bbox is in. Without
                // these, downstream consumers (ThumbnailBuffer, baseline
                // promotion) can't tell whether to interpret the bbox in
                // 320×240 mosaic or 640×640 foveated coords.
                peakBboxQuadW = quadW;
                peakBboxQuadH = quadH;
                peakCamera = newQuadrant;
            }
        }

        private Trend computeTrend() {
            if (historyCount < 2) return Trend.UNKNOWN;
            // newest is at slot (historyCount-1) % TREND_WINDOW, oldest at historyCount % TREND_WINDOW
            int newest = (historyCount - 1) % TREND_WINDOW;
            int oldest = historyCount >= TREND_WINDOW ? historyCount % TREND_WINDOW : 0;
            float a0 = areaHistory[oldest];
            float a1 = areaHistory[newest];
            if (a0 <= 0) return Trend.UNKNOWN;
            float change = (a1 - a0) / a0;
            // Direction sanity check — avoids false APPROACHING when a stationary
            // object's bbox is repeatedly reshaped by an occluder (e.g. a person
            // walking past a parked car). Real approach: bbox grows AND its
            // bottom edge drifts down (or its centroid drifts down for ground
            // objects). Occlusion noise: bbox grows but centroid jitters with
            // no net direction. We require coherent vertical motion >= 5 px.
            int dCy = cyHistory[newest] - cyHistory[oldest];
            if (change > 0.10f && dCy >= 5) return Trend.APPROACHING;
            if (change < -0.10f && dCy <= -5) return Trend.RECEDING;
            return Trend.STABLE;
        }

        private static Proximity ratioToProximity(float ratio) {
            if (ratio <= 0f) return Proximity.UNKNOWN;
            if (ratio >= PROX_VERY_CLOSE) return Proximity.VERY_CLOSE;
            if (ratio >= PROX_CLOSE)      return Proximity.CLOSE;
            if (ratio >= PROX_MID)        return Proximity.MID;
            return Proximity.FAR;
        }

        Actor toActor() {
            // current proximity = recompute from last frame so toActor is internally consistent
            float ratio;
            if (classGroup == ClassGroup.VEHICLE) {
                ratio = lastQuadW > 0 ? (float) lastW / lastQuadW : 0f;
            } else {
                ratio = lastQuadH > 0 ? (float) lastH / lastQuadH : 0f;
            }
            Proximity lastProx = ratioToProximity(ratio);
            int staticThreshold = (classGroup == ClassGroup.VEHICLE)
                    ? STATIC_FRAMES_NEEDED_VEHICLE : STATIC_FRAMES_NEEDED;
            return new Actor(actorId, classGroup,
                    firstSeenWallMs, lastSeenWallMs,
                    firstSeenRelMs, lastSeenRelMs,
                    cameraMask,
                    peakProximity, lastProx,
                    computeTrend(), stableFrames >= staticThreshold,
                    peakSeverity, peakSeverityWallMs, peakSeverityRelMs,
                    peakConfidence,
                    peakBboxX, peakBboxY, peakBboxW, peakBboxH,
                    peakBboxQuadW, peakBboxQuadH, peakCamera,
                    lastX, lastY, lastW, lastH);
        }
    }
}
