package com.overdrive.app.notifications;

import com.overdrive.app.surveillance.Actor;
import com.overdrive.app.surveillance.SurveillanceConfig;

/**
 * NotificationGate — Decides whether a recording's peak severity warrants a
 * push notification, given user-configured tier toggles.
 *
 * Single decision function so the rule lives in one place. Defaults match the
 * SurveillanceConfig defaults: NOTICE off, ALERT on, CRITICAL on.
 */
public final class NotificationGate {

    private NotificationGate() {}

    public static boolean shouldPush(Actor.Severity sev, SurveillanceConfig cfg) {
        if (cfg == null) {
            // Null config means "config not yet loaded" — never silently
            // drop a notification on the user, especially since the
            // start-stage banner already published unconditionally on
            // a null config. Suppressing the final stage would leave the
            // user with a stale "Recording in progress…" banner that
            // never gets replaced.
            return true;
        }
        if (sev == null) return true;  // unknown severity → don't drop
        switch (sev) {
            case CRITICAL: return cfg.isPushCritical();
            case ALERT:    return cfg.isPushAlerts();
            case NOTICE:   return cfg.isPushNotices();
            default:       return false;
        }
    }

    /**
     * Convenience: derive recording-level severity from a list of actors and
     * decide whether to push.
     */
    public static boolean shouldPush(java.util.List<Actor> actors, SurveillanceConfig cfg) {
        return shouldPush(maxSeverity(actors), cfg);
    }

    /**
     * Telegram-tier gate. Mirrors {@link #shouldPush(Actor.Severity, SurveillanceConfig)}
     * but reads the telegramNotices/Alerts/Critical toggles so a
     * Telegram-only user can mute low-severity events independently of push.
     * Same null-config-allows-through and unknown-severity-allows-through
     * guarantees as shouldPush.
     */
    public static boolean shouldTelegram(Actor.Severity sev, SurveillanceConfig cfg) {
        if (cfg == null) return true;
        if (sev == null) return true;
        switch (sev) {
            case CRITICAL: return cfg.isTelegramCritical();
            case ALERT:    return cfg.isTelegramAlerts();
            case NOTICE:   return cfg.isTelegramNotices();
            default:       return false;
        }
    }

    public static boolean shouldTelegram(java.util.List<Actor> actors, SurveillanceConfig cfg) {
        return shouldTelegram(maxSeverity(actors), cfg);
    }

    public static Actor.Severity maxSeverity(java.util.List<Actor> actors) {
        if (actors == null || actors.isEmpty()) return Actor.Severity.NOTICE;
        Actor.Severity max = Actor.Severity.NOTICE;
        for (Actor a : actors) {
            if (a.peakSeverity != null && a.peakSeverity.ordinal() > max.ordinal()) {
                max = a.peakSeverity;
            }
        }
        return max;
    }
}
