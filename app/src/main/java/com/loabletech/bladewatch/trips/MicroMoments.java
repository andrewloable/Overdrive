package com.overdrive.app.trips;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Container for micro-moment analysis extracted from trip telemetry.
 * Includes launch profiles, coast-before-brake events, and pedal smoothness windows.
 */
public class MicroMoments {

    // ==================== Inner Classes ====================

    /**
     * A launch event: speed transitions from 0 to >5 km/h.
     * Records the accel pedal curve for the first 10 seconds.
     */
    public static class LaunchProfile {
        public long startTime;
        public int peakAccelPercent;
        public int[] accelCurve;       // Accel pedal % samples over first 10s

        public LaunchProfile() {
            this.accelCurve = new int[0];
        }

        public JSONObject toJson() {
            JSONObject json = new JSONObject();
            try {
                json.put("startTime", startTime);
                json.put("peakAccelPercent", peakAccelPercent);
                JSONArray curve = new JSONArray();
                for (int v : accelCurve) {
                    curve.put(v);
                }
                json.put("accelCurve", curve);
            } catch (Exception e) {
                // ignore
            }
            return json;
        }

        public static LaunchProfile fromJson(JSONObject json) {
            LaunchProfile lp = new LaunchProfile();
            lp.startTime = json.optLong("startTime", 0);
            lp.peakAccelPercent = json.optInt("peakAccelPercent", 0);
            JSONArray curve = json.optJSONArray("accelCurve");
            if (curve != null) {
                lp.accelCurve = new int[curve.length()];
                for (int i = 0; i < curve.length(); i++) {
                    lp.accelCurve[i] = curve.optInt(i, 0);
                }
            }
            return lp;
        }
    }

    /**
     * A coast-before-brake event: accel pedal drops to 0, then brake rises above 0.
     * Records the coast gap duration and speed at brake application.
     */
    public static class CoastBrakeEvent {
        public long coastGapMs;
        public int speedAtBrake;

        public JSONObject toJson() {
            JSONObject json = new JSONObject();
            try {
                json.put("coastGapMs", coastGapMs);
                json.put("speedAtBrake", speedAtBrake);
            } catch (Exception e) {
                // ignore
            }
            return json;
        }

        public static CoastBrakeEvent fromJson(JSONObject json) {
            CoastBrakeEvent ev = new CoastBrakeEvent();
            ev.coastGapMs = json.optLong("coastGapMs", 0);
            ev.speedAtBrake = json.optInt("speedAtBrake", 0);
            return ev;
        }
    }

    /**
     * A pedal smoothness measurement window.
     * Records the standard deviation of accel pedal % over a 10-second window.
     */
    public static class PedalSmoothnessWindow {
        public long startTime;
        public double stdDev;

        public JSONObject toJson() {
            JSONObject json = new JSONObject();
            try {
                json.put("startTime", startTime);
                json.put("stdDev", stdDev);
            } catch (Exception e) {
                // ignore
            }
            return json;
        }

        public static PedalSmoothnessWindow fromJson(JSONObject json) {
            PedalSmoothnessWindow w = new PedalSmoothnessWindow();
            w.startTime = json.optLong("startTime", 0);
            w.stdDev = json.optDouble("stdDev", 0.0);
            return w;
        }
    }

    // ==================== Fields ====================

    public List<LaunchProfile> launches;
    public List<CoastBrakeEvent> coastBrakeEvents;
    public List<PedalSmoothnessWindow> smoothnessWindows;

    public MicroMoments() {
        this.launches = new ArrayList<>();
        this.coastBrakeEvents = new ArrayList<>();
        this.smoothnessWindows = new ArrayList<>();
    }

    // ==================== Aggregate Metrics ====================

    /**
     * Average peak accel pedal % across all launch profiles.
     */
    public double getAvgLaunchAggressiveness() {
        if (launches.isEmpty()) return 0.0;
        double sum = 0;
        for (LaunchProfile lp : launches) {
            sum += lp.peakAccelPercent;
        }
        return sum / launches.size();
    }

    /**
     * Average coast gap in seconds across all coast-brake events.
     */
    public double getAvgCoastGapSeconds() {
        if (coastBrakeEvents.isEmpty()) return 0.0;
        double sum = 0;
        for (CoastBrakeEvent ev : coastBrakeEvents) {
            sum += ev.coastGapMs;
        }
        return (sum / coastBrakeEvents.size()) / 1000.0;
    }

    /**
     * Average pedal smoothness (stddev) across all windows.
     */
    public double getAvgPedalSmoothness() {
        if (smoothnessWindows.isEmpty()) return 0.0;
        double sum = 0;
        for (PedalSmoothnessWindow w : smoothnessWindows) {
            sum += w.stdDev;
        }
        return sum / smoothnessWindows.size();
    }

    // ==================== Serialization ====================

    /**
     * Serialize to JSON.
     */
    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        try {
            JSONArray launchArr = new JSONArray();
            for (LaunchProfile lp : launches) {
                launchArr.put(lp.toJson());
            }
            json.put("launches", launchArr);

            JSONArray coastArr = new JSONArray();
            for (CoastBrakeEvent ev : coastBrakeEvents) {
                coastArr.put(ev.toJson());
            }
            json.put("coastBrakeEvents", coastArr);

            JSONArray smoothArr = new JSONArray();
            for (PedalSmoothnessWindow w : smoothnessWindows) {
                smoothArr.put(w.toJson());
            }
            json.put("smoothnessWindows", smoothArr);
        } catch (Exception e) {
            // ignore
        }
        return json;
    }

    /**
     * Deserialize from JSON string.
     */
    public static MicroMoments fromJson(String jsonStr) {
        MicroMoments mm = new MicroMoments();
        if (jsonStr == null || jsonStr.isEmpty()) return mm;
        try {
            JSONObject json = new JSONObject(jsonStr);

            JSONArray launchArr = json.optJSONArray("launches");
            if (launchArr != null) {
                for (int i = 0; i < launchArr.length(); i++) {
                    mm.launches.add(LaunchProfile.fromJson(launchArr.getJSONObject(i)));
                }
            }

            JSONArray coastArr = json.optJSONArray("coastBrakeEvents");
            if (coastArr != null) {
                for (int i = 0; i < coastArr.length(); i++) {
                    mm.coastBrakeEvents.add(CoastBrakeEvent.fromJson(coastArr.getJSONObject(i)));
                }
            }

            JSONArray smoothArr = json.optJSONArray("smoothnessWindows");
            if (smoothArr != null) {
                for (int i = 0; i < smoothArr.length(); i++) {
                    mm.smoothnessWindows.add(PedalSmoothnessWindow.fromJson(smoothArr.getJSONObject(i)));
                }
            }
        } catch (Exception e) {
            // Return empty MicroMoments on parse failure
        }
        return mm;
    }
}
