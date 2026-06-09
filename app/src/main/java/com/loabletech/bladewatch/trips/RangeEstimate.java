package net.bladewatch.app.trips;

import net.bladewatch.app.logging.DaemonLogger;

import org.json.JSONObject;

/**
 * Result of a personalized range prediction.
 * Contains the predicted range with confidence interval bounds,
 * the matched consumption bucket info, and the car's built-in range for comparison.
 */
public class RangeEstimate {

    private static final String TAG = "RangeEstimate";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    public double predictedRangeKm;
    public double lowerBoundKm;
    public double upperBoundKm;
    public String bucketKey;
    public int sampleCount;
    public int builtInRangeKm;

    /**
     * Serialize to JSON for API responses.
     */
    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        try {
            json.put("predictedRangeKm", predictedRangeKm);
            json.put("lowerBoundKm", lowerBoundKm);
            json.put("upperBoundKm", upperBoundKm);
            json.put("bucketKey", bucketKey != null ? bucketKey : "");
            json.put("sampleCount", sampleCount);
            json.put("builtInRangeKm", builtInRangeKm);
        } catch (Exception e) {
            logger.warn("RangeEstimate.toJson: failed to serialize: " + e.getMessage());
        }
        return json;
    }
}
