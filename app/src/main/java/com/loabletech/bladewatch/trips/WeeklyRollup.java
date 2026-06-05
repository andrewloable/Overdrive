package com.overdrive.app.trips;

import org.json.JSONObject;

/**
 * Pre-aggregated weekly summary of trip data.
 * Keyed by ISO year and week number.
 */
public class WeeklyRollup {

    public int year;
    public int weekNumber;             // ISO week
    public int tripCount;
    public double totalDistanceKm;
    public int totalDurationSeconds;
    public double avgEfficiency;
    public double totalEnergyKwh;      // Sum of energyUsedKwh across trips
    public double totalCost;           // Sum of tripCost across trips
    public double avgEnergyPerKm;      // Average kWh/km
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
            json.put("weekNumber", weekNumber);
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
            // JSONObject.put only throws on null key
        }
        return json;
    }
}
