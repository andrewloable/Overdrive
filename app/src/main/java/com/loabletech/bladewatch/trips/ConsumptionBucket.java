package com.overdrive.app.trips;

/**
 * Aggregated consumption data for a specific condition combination.
 * Used by RangeEstimator for personalized range prediction.
 *
 * Bucket key format: "{speedProfile}_{tempBand}_{styleBracket}"
 * e.g., "suburban_mild_high"
 */
public class ConsumptionBucket {

    public String bucketKey;           // e.g., "suburban_mild_high"
    public int sampleCount;
    public double sumKwhPerKm;         // Running sum for computing mean
    public double sumSquaredKwhPerKm;  // Running sum of squares for computing stddev

    /**
     * Compute the mean consumption rate (kWh/km).
     */
    public double getMean() {
        if (sampleCount == 0) return 0.0;
        return sumKwhPerKm / sampleCount;
    }

    /**
     * Compute the standard deviation of consumption rate.
     * Uses the formula: sqrt(E[X^2] - (E[X])^2)
     */
    public double getStdDev() {
        if (sampleCount < 2) return 0.0;
        double mean = getMean();
        double variance = (sumSquaredKwhPerKm / sampleCount) - (mean * mean);
        // Guard against floating-point rounding producing tiny negative values
        if (variance < 0) variance = 0;
        return Math.sqrt(variance);
    }
}
