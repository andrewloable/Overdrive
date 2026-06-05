package com.overdrive.app.server;

import com.overdrive.app.notifications.CategoryRegistry;
import com.overdrive.app.notifications.NotificationBus;
import com.overdrive.app.notifications.NotificationEvent;
import com.overdrive.app.notifications.push.PushSubscription;
import com.overdrive.app.notifications.push.SubscriptionStore;
import com.overdrive.app.notifications.push.VapidKeyStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;

/**
 * HTTP routes for the Web Push subsystem.
 *
 * Endpoints:
 * <ul>
 *   <li>GET  /api/notifications/categories — registry JSON + VAPID public key</li>
 *   <li>POST /api/push/subscribe           — register a phone subscription</li>
 *   <li>POST /api/push/unsubscribe         — remove this device's subscription</li>
 *   <li>GET  /api/push/subscriptions       — list registered devices for settings UI</li>
 *   <li>POST /api/push/preferences         — update muted categories / quiet hours</li>
 *   <li>POST /api/push/test                — fire a test notification to the requester</li>
 * </ul>
 *
 * <p>All routes require auth (handled by the caller before dispatch).
 */
public final class NotificationApiHandler {

    private static volatile CategoryRegistry registry;
    private static volatile SubscriptionStore subStore;
    private static volatile VapidKeyStore keyStore;

    /** Wire the dependencies once at daemon startup. */
    public static void init(CategoryRegistry r, SubscriptionStore s, VapidKeyStore k) {
        registry = r;
        subStore = s;
        keyStore = k;
    }

    public static boolean handle(String method, String path, String body, OutputStream out) throws Exception {
        if (registry == null || subStore == null || keyStore == null) {
            HttpResponse.sendError(out, 503, "Notifications not initialized");
            return true;
        }

        if (path.equals("/api/notifications/categories") && method.equals("GET")) {
            return getCategories(out);
        }
        if (path.equals("/api/push/subscribe") && method.equals("POST")) {
            return subscribe(body, out);
        }
        if (path.equals("/api/push/unsubscribe") && method.equals("POST")) {
            return unsubscribe(body, out);
        }
        if (path.equals("/api/push/subscriptions") && method.equals("GET")) {
            return listSubscriptions(out);
        }
        if (path.equals("/api/push/preferences") && method.equals("POST")) {
            return updatePreferences(body, out);
        }
        if (path.equals("/api/push/test") && method.equals("POST")) {
            return sendTest(body, out);
        }
        return false;
    }

    // ==================== HANDLERS ====================

    private static boolean getCategories(OutputStream out) throws Exception {
        JSONObject root = new JSONObject(registry.rawJson());
        root.put("vapidPublicKey", keyStore.publicKeyB64Url());
        HttpResponse.sendJson(out, root.toString());
        return true;
    }

    private static boolean subscribe(String body, OutputStream out) throws Exception {
        if (body == null || body.isEmpty()) {
            HttpResponse.sendError(out, 400, "missing body");
            return true;
        }
        try {
            JSONObject j = new JSONObject(body);
            String endpoint = j.getString("endpoint");
            JSONObject keys = j.getJSONObject("keys");
            byte[] p256dh = android.util.Base64.decode(keys.getString("p256dh"),
                    android.util.Base64.URL_SAFE | android.util.Base64.NO_PADDING | android.util.Base64.NO_WRAP);
            byte[] auth = android.util.Base64.decode(keys.getString("auth"),
                    android.util.Base64.URL_SAFE | android.util.Base64.NO_PADDING | android.util.Base64.NO_WRAP);
            String label = j.optString("label", null);

            String id = SubscriptionStore.idForEndpoint(endpoint);

            PushSubscription existing = subStore.get(id);
            PushSubscription sub;
            if (existing != null) {
                // Re-subscribe — keep prefs, refresh keys
                sub = new PushSubscription(id, endpoint, p256dh, auth,
                        label != null ? label : existing.label, existing.createdAt);
                sub.lastSeenAt = System.currentTimeMillis();
                sub.minSeverity = existing.minSeverity;
                sub.mutedCategories.addAll(existing.mutedCategories);
                sub.quietHours = existing.quietHours;
            } else {
                sub = new PushSubscription(id, endpoint, p256dh, auth, label,
                        System.currentTimeMillis());
            }
            subStore.put(sub);

            JSONObject resp = new JSONObject();
            resp.put("success", true);
            resp.put("id", id);
            HttpResponse.sendJson(out, resp.toString());
        } catch (Exception e) {
            HttpResponse.sendError(out, 400, "invalid subscription: " + e.getMessage());
        }
        return true;
    }

    private static boolean unsubscribe(String body, OutputStream out) throws Exception {
        if (body == null || body.isEmpty()) {
            HttpResponse.sendError(out, 400, "missing body");
            return true;
        }
        JSONObject j = new JSONObject(body);
        String id = j.optString("id", null);
        if (id == null) {
            String endpoint = j.optString("endpoint", null);
            if (endpoint != null) id = SubscriptionStore.idForEndpoint(endpoint);
        }
        if (id == null) {
            HttpResponse.sendError(out, 400, "id or endpoint required");
            return true;
        }
        boolean removed = subStore.remove(id);
        JSONObject resp = new JSONObject();
        resp.put("success", removed);
        HttpResponse.sendJson(out, resp.toString());
        return true;
    }

    private static boolean listSubscriptions(OutputStream out) throws Exception {
        JSONArray arr = new JSONArray();
        for (PushSubscription s : subStore.all()) {
            JSONObject j = new JSONObject();
            j.put("id", s.id);
            j.put("label", s.label == null ? "" : s.label);
            j.put("createdAt", s.createdAt);
            j.put("lastSeenAt", s.lastSeenAt);
            j.put("minSeverity", s.minSeverity.name().toLowerCase(java.util.Locale.US));
            JSONArray muted = new JSONArray();
            for (String c : s.mutedCategories) muted.put(c);
            j.put("mutedCategories", muted);
            if (s.quietHours != null) {
                JSONObject qh = new JSONObject();
                qh.put("startMin", s.quietHours.startMin);
                qh.put("endMin", s.quietHours.endMin);
                qh.put("allowCritical", s.quietHours.allowCritical);
                j.put("quietHours", qh);
            }
            arr.put(j);
        }
        JSONObject resp = new JSONObject();
        resp.put("success", true);
        resp.put("subscriptions", arr);
        HttpResponse.sendJson(out, resp.toString());
        return true;
    }

    private static boolean updatePreferences(String body, OutputStream out) throws Exception {
        if (body == null || body.isEmpty()) {
            HttpResponse.sendError(out, 400, "missing body");
            return true;
        }
        JSONObject j = new JSONObject(body);
        String id = j.getString("id");
        PushSubscription sub = subStore.get(id);
        if (sub == null) {
            HttpResponse.sendError(out, 404, "subscription not found");
            return true;
        }

        if (j.has("mutedCategories")) {
            JSONArray muted = j.getJSONArray("mutedCategories");
            sub.mutedCategories.clear();
            for (int i = 0; i < muted.length(); i++) {
                sub.mutedCategories.add(muted.getString(i));
            }
        }
        if (j.has("minSeverity")) {
            try {
                sub.minSeverity = NotificationEvent.Severity.valueOf(
                        j.getString("minSeverity").toUpperCase(java.util.Locale.US));
            } catch (Exception ignored) {}
        }
        if (j.has("quietHours")) {
            Object qhRaw = j.get("quietHours");
            if (qhRaw == JSONObject.NULL) {
                sub.quietHours = null;
            } else {
                JSONObject qh = (JSONObject) qhRaw;
                sub.quietHours = new PushSubscription.QuietHours(
                        qh.getInt("startMin"),
                        qh.getInt("endMin"),
                        qh.optBoolean("allowCritical", true));
            }
        }

        // re-persist — store mutates in place but file write is via put()
        subStore.put(sub);
        HttpResponse.sendJsonSuccess(out);
        return true;
    }

    private static boolean sendTest(String body, OutputStream out) throws Exception {
        String category = "surveillance.motion";
        String severityStr = "info";
        if (body != null && !body.isEmpty()) {
            try {
                JSONObject j = new JSONObject(body);
                category = j.optString("category", category);
                severityStr = j.optString("severity", severityStr);
            } catch (Exception ignored) {}
        }
        NotificationEvent.Severity severity;
        try {
            severity = NotificationEvent.Severity.valueOf(severityStr.toUpperCase(java.util.Locale.US));
        } catch (Exception e) {
            severity = NotificationEvent.Severity.INFO;
        }
        NotificationEvent event = new NotificationEvent(
                category,
                severity,
                "Test notification",
                "If you're seeing this, push delivery works.",
                "test-" + System.currentTimeMillis(),
                null,
                new JSONObject().put("test", true));
        NotificationBus.get().publish(event);
        HttpResponse.sendJsonSuccess(out);
        return true;
    }
}
