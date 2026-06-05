package com.overdrive.app.surveillance;

import com.overdrive.app.surveillance.Actor.ClassGroup;
import com.overdrive.app.surveillance.Actor.Proximity;
import com.overdrive.app.surveillance.Actor.Severity;
import com.overdrive.app.surveillance.Actor.Trend;

/**
 * SeverityClassifier — Single source of truth for NOTICE / ALERT / CRITICAL.
 *
 * Rules are intentionally in one place so the CRITICAL definition can evolve
 * (g-sensor, audio, allowlist) without scattering edits across the codebase.
 *
 * No metric thresholds — proximity is pixel-relative.
 */
public final class SeverityClassifier {

    private SeverityClassifier() {}

    /**
     * Classify an in-flight tracker observation.
     *
     * @param classGroup     Coarse class
     * @param proximity      Last-frame proximity band
     * @param peakProximity  Closest approach across the actor's life
     * @param trend          Approach/recede/stable
     * @param isStatic       Tracker's "no motion / no growth" verdict
     * @param dwellMs        Time spent at proximity ≥ peakProximity (continuous)
     * @return chosen severity
     */
    public static Severity classify(ClassGroup classGroup,
                                    Proximity proximity,
                                    Proximity peakProximity,
                                    Trend trend,
                                    boolean isStatic,
                                    long dwellMs) {
        if (classGroup == ClassGroup.UNKNOWN || classGroup == ClassGroup.ANIMAL) {
            return Severity.NOTICE;
        }

        // SAFETY GATE — a static *non-person* actor never escalates above NOTICE.
        //
        // The classic FP this prevents: user walks past their parked car next to
        // two other parked cars; YOLO sees all three vehicles + the person, the
        // cars are "very close" and high-confidence, but they have NEVER MOVED.
        // Without this gate, the thumbnail picker chose the highest-confidence
        // VERY_CLOSE actor — a car — masking the actual moving person.
        //
        // CAREFUL: this rule must NOT apply to PERSON. A person standing
        // perfectly still at the driver door for 30 seconds hits isStatic=true
        // after ~800 ms but is exactly the threat we want CRITICAL to catch
        // (loitering / tampering). The per-class rules below correctly fire
        // CRITICAL on a static person at VERY_CLOSE.
        if (isStatic && classGroup != ClassGroup.PERSON) {
            return Severity.NOTICE;
        }

        // CRITICAL — physical threat hints (proximity + intent)
        if (classGroup == ClassGroup.PERSON) {
            // VERY_CLOSE is itself the threat signal; no dwell required.
            if (proximity == Proximity.VERY_CLOSE) {
                return Severity.CRITICAL;
            }
            if (proximity == Proximity.CLOSE) {
                return Severity.ALERT;
            }
            if (proximity == Proximity.MID && trend == Trend.APPROACHING) {
                return Severity.ALERT;
            }
        }

        if (classGroup == ClassGroup.VEHICLE) {
            // Vehicles only escalate when actually moving toward the camera.
            // Parked cars next to ours stay at NOTICE.
            if (trend == Trend.APPROACHING && proximity == Proximity.VERY_CLOSE) {
                return Severity.ALERT;
            }
            if (trend == Trend.APPROACHING && proximity == Proximity.CLOSE) {
                return Severity.ALERT;
            }
        }

        if (classGroup == ClassGroup.BIKE) {
            if (proximity == Proximity.VERY_CLOSE && trend != Trend.STABLE) {
                return Severity.ALERT;
            }
        }

        return Severity.NOTICE;
    }

    /** Pick the higher of two severities. */
    public static Severity max(Severity a, Severity b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.ordinal() >= b.ordinal() ? a : b;
    }
}
