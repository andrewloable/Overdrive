package com.overdrive.app.notifications;

import org.json.JSONObject;

/**
 * Canonical notification event. Emitted by sources (surveillance, tyre,
 * proximity, etc.) and consumed by sinks (PushSink, LogSink).
 *
 * <p>Category is a dotted string ("surveillance.motion",
 * "vehicle.health.tyre.leak"), not an enum, so future categories can be added
 * by registry config without code changes here.
 *
 * <p>Severity drives the UI rendering on the client (vibrate pattern,
 * requireInteraction, sound). For categories with {@code "severity": "auto"}
 * in the registry, the source should compute it per-event from the data.
 */
public final class NotificationEvent {

    public enum Severity { INFO, WARN, CRITICAL }

    public final String category;
    public final Severity severity;
    public final String title;
    public final String body;
    public final long timestamp;
    /** Server-side dedupe key. Two events with the same tag collapse on display. May be null. */
    public final String tag;
    /** Click target URL. If null, sink falls back to the registry's defaultClickUrl. */
    public final String clickUrl;
    /** Category-specific extras (filename, wheel index, kPa, etc.). Never null. */
    public final JSONObject data;

    public NotificationEvent(String category, Severity severity, String title, String body,
                             String tag, String clickUrl, JSONObject data) {
        if (category == null) throw new IllegalArgumentException("category required");
        if (severity == null) throw new IllegalArgumentException("severity required");
        if (title == null) throw new IllegalArgumentException("title required");
        this.category = category;
        this.severity = severity;
        this.title = title;
        this.body = body == null ? "" : body;
        this.timestamp = System.currentTimeMillis();
        this.tag = tag;
        this.clickUrl = clickUrl;
        this.data = data == null ? new JSONObject() : data;
    }

    /** Build the wire envelope sent inside the encrypted Web Push payload. */
    public JSONObject toPayloadJson() {
        JSONObject j = new JSONObject();
        try {
            j.put("v", 1);
            j.put("category", category);
            j.put("severity", severity.name().toLowerCase(java.util.Locale.US));
            j.put("title", title);
            j.put("body", body);
            j.put("ts", timestamp);
            if (tag != null) j.put("tag", tag);
            if (clickUrl != null) j.put("url", clickUrl);
            j.put("data", data);
        } catch (Exception ignored) {}
        return j;
    }
}
