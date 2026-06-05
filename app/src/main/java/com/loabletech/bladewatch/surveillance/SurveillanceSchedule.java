package com.overdrive.app.surveillance;

import com.overdrive.app.logging.DaemonLogger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * Surveillance time window scheduling.
 *
 * Allows users to define days and hours when surveillance should be active.
 * When enabled, surveillance only activates during configured time windows.
 * When disabled (default), surveillance follows existing behavior (always active
 * when ACC OFF + user enabled + not in safe zone).
 *
 * Overnight windows are supported: startHour > endHour spans midnight
 * (e.g., 22:00-06:00 means 10PM to 6AM next day).
 */
public class SurveillanceSchedule {
    private static final DaemonLogger logger = DaemonLogger.getInstance("SurvSchedule");

    private boolean enabled = false;
    private final List<Rule> rules = new ArrayList<>();

    /**
     * A single schedule rule: active on specified days during specified hours.
     */
    public static class Rule {
        public final int[] days;       // 0=Sunday, 1=Monday, ..., 6=Saturday
        public final int startHour;    // 0-23
        public final int startMin;     // 0-59
        public final int endHour;      // 0-23
        public final int endMin;       // 0-59

        public Rule(int[] days, int startHour, int startMin, int endHour, int endMin) {
            this.days = days;
            this.startHour = startHour;
            this.startMin = startMin;
            this.endHour = endHour;
            this.endMin = endMin;
        }

        /**
         * Check if the given day and time fall within this rule.
         * Handles overnight windows (startHour > endHour).
         */
        public boolean matches(int dayOfWeek, int hour, int minute) {
            // Check if day matches
            boolean dayMatch = false;
            for (int d : days) {
                if (d == dayOfWeek) { dayMatch = true; break; }
            }
            if (!dayMatch) return false;

            int nowMinutes = hour * 60 + minute;
            int startMinutes = startHour * 60 + startMin;
            int endMinutes = endHour * 60 + endMin;

            if (startMinutes <= endMinutes) {
                // Same-day window (e.g., 08:00-18:00)
                return nowMinutes >= startMinutes && nowMinutes < endMinutes;
            } else {
                // Overnight window (e.g., 22:00-06:00)
                // Active if: now >= start OR now < end
                return nowMinutes >= startMinutes || nowMinutes < endMinutes;
            }
        }

        public JSONObject toJson() {
            try {
                JSONObject obj = new JSONObject();
                JSONArray daysArr = new JSONArray();
                for (int d : days) daysArr.put(d);
                obj.put("days", daysArr);
                obj.put("startHour", startHour);
                obj.put("startMin", startMin);
                obj.put("endHour", endHour);
                obj.put("endMin", endMin);
                return obj;
            } catch (Exception e) {
                return new JSONObject();
            }
        }

        public static Rule fromJson(JSONObject obj) {
            try {
                JSONArray daysArr = obj.getJSONArray("days");
                if (daysArr.length() == 0) return null;  // No days selected
                int[] days = new int[daysArr.length()];
                for (int i = 0; i < daysArr.length(); i++) {
                    int d = daysArr.getInt(i);
                    if (d < 0 || d > 6) return null;  // Invalid day
                    days[i] = d;
                }
                int sh = obj.optInt("startHour", 0);
                int sm = obj.optInt("startMin", 0);
                int eh = obj.optInt("endHour", 23);
                int em = obj.optInt("endMin", 59);
                // Validate hour/minute ranges
                if (sh < 0 || sh > 23 || eh < 0 || eh > 23) return null;
                if (sm < 0 || sm > 59 || em < 0 || em > 59) return null;
                // Reject zero-length windows
                if (sh == eh && sm == em) return null;
                return new Rule(days, sh, sm, eh, em);
            } catch (Exception e) {
                return null;
            }
        }
    }

    // ==================== PUBLIC API ====================

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public List<Rule> getRules() { return rules; }

    /**
     * Check if surveillance should be active right now based on the schedule.
     * Returns true if:
     * - Schedule is disabled (always active), OR
     * - Schedule is enabled but no rules configured (always active — treat as "no restrictions"), OR
     * - Current day/time matches at least one rule
     */
    public boolean isActiveNow() {
        if (!enabled) return true;  // Schedule disabled = always active
        if (rules.isEmpty()) return true;  // Enabled but no rules = always active (no restrictions configured)

        Calendar cal = Calendar.getInstance();
        int dayOfWeek = cal.get(Calendar.DAY_OF_WEEK) - 1;  // Calendar.SUNDAY=1 → 0
        int hour = cal.get(Calendar.HOUR_OF_DAY);
        int minute = cal.get(Calendar.MINUTE);

        for (Rule rule : rules) {
            if (rule.matches(dayOfWeek, hour, minute)) {
                return true;
            }
        }
        return false;
    }

    // ==================== SERIALIZATION ====================

    public JSONObject toJson() {
        try {
            JSONObject obj = new JSONObject();
            obj.put("scheduleEnabled", enabled);
            JSONArray rulesArr = new JSONArray();
            for (Rule rule : rules) {
                rulesArr.put(rule.toJson());
            }
            obj.put("scheduleRules", rulesArr);
            return obj;
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    public void loadFromJson(JSONObject obj) {
        if (obj == null) return;
        enabled = obj.optBoolean("scheduleEnabled", false);
        rules.clear();
        JSONArray rulesArr = obj.optJSONArray("scheduleRules");
        if (rulesArr != null) {
            for (int i = 0; i < rulesArr.length(); i++) {
                Rule rule = Rule.fromJson(rulesArr.optJSONObject(i));
                if (rule != null) rules.add(rule);
            }
        }
    }

    /**
     * Human-readable summary for logging and UI.
     */
    public String getSummary() {
        if (!enabled) return "Disabled (always active)";
        if (rules.isEmpty()) return "Enabled, no restrictions (always active)";

        StringBuilder sb = new StringBuilder();
        String[] dayNames = {"Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"};
        for (int i = 0; i < rules.size(); i++) {
            if (i > 0) sb.append("; ");
            Rule r = rules.get(i);
            StringBuilder days = new StringBuilder();
            for (int j = 0; j < r.days.length; j++) {
                if (j > 0) days.append(",");
                if (r.days[j] >= 0 && r.days[j] < 7) days.append(dayNames[r.days[j]]);
            }
            sb.append(days).append(" ")
              .append(String.format("%02d:%02d", r.startHour, r.startMin))
              .append("-")
              .append(String.format("%02d:%02d", r.endHour, r.endMin));
        }
        return sb.toString();
    }
}
