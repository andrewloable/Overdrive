package com.overdrive.app.trips;

import org.json.JSONObject;

/**
 * Result of a personalized range prediction.
 * Contains the predicted range with confidence interval bounds,
 * the matched consumption bucket info, and the car's built-in range for comparison.
 */
public class RangeEstimate {

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
            // JSONObject.put only throws on null key
        }
        return json;
    }
}
