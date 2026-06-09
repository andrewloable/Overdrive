package net.bladewatch.app.trips;

import net.bladewatch.app.logging.DaemonLogger;

import org.json.JSONObject;

/**
 * Pre-aggregated monthly summary of trip data.
 * Keyed by year and month (1-12).
 */
public class MonthlyRollup {

    private static final String TAG = "MonthlyRollup";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    public int year;
    public int month;                  // 1-12
    public int tripCount;
    public double totalDistanceKm;
    public int totalDurationSeconds;
    public double avgEfficiency;
    public double totalEnergyKwh;
    public double totalCost;
    public double avgEnergyPerKm;
    public int avgAnticipation;
    public int avgSmoothness;
    public int avgSpeedDiscipline;
    public int avgEfficiencyScore;
    public int avgConsistency;

    /**
     * Serialize to JSON for API responses.
     */
    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        try {
            json.put("year", year);
            json.put("month", month);
            json.put("tripCount", tripCount);
            json.put("totalDistanceKm", totalDistanceKm);
            json.put("totalDurationSeconds", totalDurationSeconds);
            json.put("avgEfficiency", avgEfficiency);
            json.put("totalEnergyKwh", totalEnergyKwh);
            json.put("totalCost", totalCost);
            json.put("avgEnergyPerKm", avgEnergyPerKm);
            json.put("avgAnticipation", avgAnticipation);
            json.put("avgSmoothness", avgSmoothness);
            json.put("avgSpeedDiscipline", avgSpeedDiscipline);
            json.put("avgEfficiencyScore", avgEfficiencyScore);
            json.put("avgConsistency", avgConsistency);
        } catch (Exception e) {
            logger.warn("MonthlyRollup.toJson: failed to serialize: " + e.getMessage());
        }
        return json;
    }
}
