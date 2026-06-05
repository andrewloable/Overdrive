package com.overdrive.app.notifications;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Loads {@code notifications-categories.json} from the APK assets.
 * Single source of truth for the registry — both server-side defaults
 * (severity floor, click URL fallback) and the {@code /api/notifications/categories}
 * endpoint hydrate from the same file.
 */
public final class CategoryRegistry {

    public static final class Entry {
        public final String id;
        public final String label;
        public final String group;
        public final boolean defaultEnabled;
        /** "info", "warn", "critical", or "auto" (use the event's own severity). */
        public final String severity;
        public final String defaultClickUrl;
        public final String note;
        /**
         * When true, this category delivers even during the user's configured
         * quiet hours. Reserved for events the user explicitly wants regardless
         * of time of day (e.g. charging complete — the whole point is to wake
         * them so they unplug). Defaults to false.
         */
        public final boolean bypassQuietHours;

        Entry(String id, String label, String group, boolean defaultEnabled,
              String severity, String defaultClickUrl, String note,
              boolean bypassQuietHours) {
            this.id = id;
            this.label = label;
            this.group = group;
            this.defaultEnabled = defaultEnabled;
            this.severity = severity;
            this.defaultClickUrl = defaultClickUrl;
            this.note = note;
            this.bypassQuietHours = bypassQuietHours;
        }
    }

    private final Map<String, Entry> byId;
    private final String rawJson;
    private final int version;

    private CategoryRegistry(Map<String, Entry> byId, String rawJson, int version) {
        this.byId = byId;
        this.rawJson = rawJson;
        this.version = version;
    }

    public Entry get(String id) { return byId.get(id); }
    public Map<String, Entry> all() { return Collections.unmodifiableMap(byId); }
    public String rawJson() { return rawJson; }
    public int version() { return version; }

    public static CategoryRegistry loadFromAssets(android.content.Context ctx) throws Exception {
        try (InputStream is = ctx.getAssets().open("notifications-categories.json")) {
            // is.available() is a hint, not a contract — InputStream.read may
            // return short, leaving trailing garbage that corrupts JSON parsing.
            // Read in a loop until EOF.
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            String json = bos.toString("UTF-8");
            return parse(json);
        }
    }

    public static CategoryRegistry parse(String json) throws Exception {
        JSONObject root = new JSONObject(json);
        int version = root.optInt("version", 1);
        JSONArray arr = root.getJSONArray("categories");
        Map<String, Entry> map = new LinkedHashMap<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject c = arr.getJSONObject(i);
            Entry e = new Entry(
                    c.getString("id"),
                    c.getString("label"),
                    c.optString("group", ""),
                    c.optBoolean("defaultEnabled", true),
                    c.optString("severity", "info"),
                    c.optString("defaultClickUrl", "/"),
                    c.optString("note", null),
                    c.optBoolean("bypassQuietHours", false)
            );
            // Duplicate IDs would silently overwrite (LinkedHashMap.put returns
            // the previous value); log + skip the second occurrence so config
            // typos surface early instead of subtly changing notification behaviour.
            if (map.containsKey(e.id)) {
                android.util.Log.w("CategoryRegistry",
                        "Duplicate category id '" + e.id + "' at index " + i + " — keeping first occurrence");
                continue;
            }
            map.put(e.id, e);
        }
        return new CategoryRegistry(map, json, version);
    }
}
