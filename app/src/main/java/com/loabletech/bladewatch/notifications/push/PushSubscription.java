package com.overdrive.app.notifications.push;

import com.overdrive.app.notifications.NotificationEvent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Set;

/**
 * Per-device Web Push subscription with user preferences.
 *
 * <p>Persisted as JSON; one entry per phone the user installed the PWA on.
 * Preferences (muted categories, severity floor, quiet hours) are scoped to
 * this subscription so each device can have its own notification settings.
 */
public final class PushSubscription {

    /** Stable identifier — derived from the endpoint URL hash to keep PII out of file names. */
    public final String id;
    public final String endpoint;
    /** P-256 public key from PushSubscription.toJSON().keys.p256dh, raw 65 bytes. */
    public final byte[] p256dh;
    /** 16-byte auth secret from PushSubscription.toJSON().keys.auth. */
    public final byte[] auth;
    /** User-visible label, e.g. "iPhone 11 Pro". May be null. */
    public final String label;
    public final long createdAt;

    public volatile long lastSeenAt;
    /** Categories the user has muted on this device. */
    public final Set<String> mutedCategories;
    /** Severity floor — events below this are dropped before encryption. */
    public volatile NotificationEvent.Severity minSeverity;
    /** Quiet hours window. May be null. */
    public volatile QuietHours quietHours;

    public PushSubscription(String id, String endpoint, byte[] p256dh, byte[] auth,
                            String label, long createdAt) {
        this.id = id;
        this.endpoint = endpoint;
        this.p256dh = p256dh;
        this.auth = auth;
        this.label = label;
        this.createdAt = createdAt;
        this.lastSeenAt = createdAt;
        this.mutedCategories = new HashSet<>();
        this.minSeverity = NotificationEvent.Severity.INFO;
        this.quietHours = null;
    }

    public boolean isMuted(String category) {
        if (mutedCategories.contains(category)) return true;
        // dotted-prefix mute: muting "vehicle.health" mutes "vehicle.health.tyre.leak" too
        for (String muted : mutedCategories) {
            if (muted.endsWith(".*")) {
                String prefix = muted.substring(0, muted.length() - 1);
                if (category.startsWith(prefix)) return true;
            }
        }
        return false;
    }

    public boolean inQuietHours(long now) {
        return quietHours != null && quietHours.contains(now);
    }

    public JSONObject toJson() {
        JSONObject j = new JSONObject();
        try {
            j.put("id", id);
            j.put("endpoint", endpoint);
            j.put("p256dh", android.util.Base64.encodeToString(p256dh,
                    android.util.Base64.URL_SAFE | android.util.Base64.NO_PADDING | android.util.Base64.NO_WRAP));
            j.put("auth", android.util.Base64.encodeToString(auth,
                    android.util.Base64.URL_SAFE | android.util.Base64.NO_PADDING | android.util.Base64.NO_WRAP));
            if (label != null) j.put("label", label);
            j.put("createdAt", createdAt);
            j.put("lastSeenAt", lastSeenAt);

            JSONArray muted = new JSONArray();
            for (String c : mutedCategories) muted.put(c);
            j.put("mutedCategories", muted);
            j.put("minSeverity", minSeverity.name().toLowerCase(java.util.Locale.US));

            if (quietHours != null) {
                JSONObject qh = new JSONObject();
                qh.put("startMin", quietHours.startMin);
                qh.put("endMin", quietHours.endMin);
                qh.put("allowCritical", quietHours.allowCritical);
                j.put("quietHours", qh);
            }
        } catch (Exception ignored) {}
        return j;
    }

    public static PushSubscription fromJson(JSONObject j) throws Exception {
        byte[] p256dh = android.util.Base64.decode(j.getString("p256dh"),
                android.util.Base64.URL_SAFE | android.util.Base64.NO_PADDING | android.util.Base64.NO_WRAP);
        byte[] auth = android.util.Base64.decode(j.getString("auth"),
                android.util.Base64.URL_SAFE | android.util.Base64.NO_PADDING | android.util.Base64.NO_WRAP);

        PushSubscription s = new PushSubscription(
                j.getString("id"),
                j.getString("endpoint"),
                p256dh,
                auth,
                j.optString("label", null),
                j.optLong("createdAt", System.currentTimeMillis())
        );
        s.lastSeenAt = j.optLong("lastSeenAt", s.createdAt);

        JSONArray muted = j.optJSONArray("mutedCategories");
        if (muted != null) {
            for (int i = 0; i < muted.length(); i++) s.mutedCategories.add(muted.getString(i));
        }

        String sev = j.optString("minSeverity", "info");
        try {
            s.minSeverity = NotificationEvent.Severity.valueOf(sev.toUpperCase(java.util.Locale.US));
        } catch (Exception ignored) {
            s.minSeverity = NotificationEvent.Severity.INFO;
        }

        JSONObject qh = j.optJSONObject("quietHours");
        if (qh != null) {
            s.quietHours = new QuietHours(
                    qh.getInt("startMin"),
                    qh.getInt("endMin"),
                    qh.optBoolean("allowCritical", true)
            );
        }

        return s;
    }

    public static final class QuietHours {
        public final int startMin; // minutes since local midnight
        public final int endMin;
        public final boolean allowCritical;

        public QuietHours(int startMin, int endMin, boolean allowCritical) {
            this.startMin = startMin;
            this.endMin = endMin;
            this.allowCritical = allowCritical;
        }

        public boolean contains(long epochMillis) {
            java.util.Calendar c = java.util.Calendar.getInstance();
            c.setTimeInMillis(epochMillis);
            int min = c.get(java.util.Calendar.HOUR_OF_DAY) * 60 + c.get(java.util.Calendar.MINUTE);
            if (startMin == endMin) return false;
            if (startMin < endMin) {
                return min >= startMin && min < endMin;
            } else {
                // wraps midnight
                return min >= startMin || min < endMin;
            }
        }
    }
}
