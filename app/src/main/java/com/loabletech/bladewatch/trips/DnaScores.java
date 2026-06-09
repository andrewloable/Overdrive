package net.bladewatch.app.trips;

import net.bladewatch.app.logging.DaemonLogger;

import org.json.JSONObject;

/**
 * Container for the five Driving DNA axis scores.
 * Each score is an integer in the range [0, 100] where 100 is optimal.
 */
public class DnaScores {

    private static final String TAG = "DnaScores";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    public int anticipation;
    public int smoothness;
    public int speedDiscipline;
    public int efficiency;
    public int consistency;

    /**
     * Compute the overall score as the average of all 5 axes.
     */
    public int getOverall() {
        return (anticipation + smoothness + speedDiscipline
                + efficiency + consistency) / 5;
    }

    /**
     * Serialize to JSON for API responses.
     */
    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        try {
            json.put("anticipation", anticipation);
            json.put("smoothness", smoothness);
            json.put("speedDiscipline", speedDiscipline);
            json.put("efficiency", efficiency);
            json.put("consistency", consistency);
            json.put("overall", getOverall());
        } catch (Exception e) {
            logger.warn("DnaScores.toJson: failed to serialize: " + e.getMessage());
        }
        return json;
    }
}
