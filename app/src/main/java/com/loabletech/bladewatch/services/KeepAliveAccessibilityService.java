package com.overdrive.app.services;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Intent;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

import com.overdrive.app.ui.daemon.DaemonStartupManager;

/**
 * Minimal AccessibilityService that keeps the app process alive indefinitely.
 *
 * Android's OOM killer and OEM process killers (including BYD's DiLink firmware)
 * are hardcoded to never kill a process hosting an active AccessibilityService.
 * This gives our app the highest possible process priority — same tier as the
 * keyboard or phone call — preventing the 24-hour kill cycle on newer BYD firmware.
 *
 * The service itself is a no-op for accessibility events. Its sole purpose is
 * process keep-alive. The foreground notification provides user visibility.
 *
 * Enable via ADB (one-time):
 *   settings put secure enabled_accessibility_services com.overdrive.app/com.overdrive.app.services.KeepAliveAccessibilityService
 *   settings put secure accessibility_enabled 1
 */
public class KeepAliveAccessibilityService extends AccessibilityService {

    private static final String TAG = "KeepAliveA11y";

    private static KeepAliveAccessibilityService instance;

    public static boolean isRunning() {
        return instance != null;
    }

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        instance = this;

        Log.i(TAG, "AccessibilityService connected — process is now protected");

        // Minimal config — we don't need to observe any events
        AccessibilityServiceInfo info = getServiceInfo();
        if (info == null) {
            info = new AccessibilityServiceInfo();
        }
        info.eventTypes = 0; // No events
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.notificationTimeout = 5000;
        info.flags = 0;
        setServiceInfo(info);

        // No foreground notification needed — DaemonKeepaliveService already has one.
        // The AccessibilityService binding alone is enough to protect the process.

        // Ensure daemons are running (respawn if killed)
        try {
            DaemonStartupManager.Companion.startOnBoot(getApplicationContext());
        } catch (Exception e) {
            Log.w(TAG, "Daemon startup from A11y service: " + e.getMessage());
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // No-op — we don't process accessibility events
    }

    @Override
    public void onInterrupt() {
        // No-op
    }

    @Override
    public void onDestroy() {
        Log.w(TAG, "AccessibilityService destroyed — attempting restart");
        instance = null;

        // Self-restart: send broadcast to trigger re-enable
        try {
            Intent restartIntent = new Intent("com.overdrive.app.RESTART_ACCESSIBILITY");
            sendBroadcast(restartIntent);
        } catch (Exception e) {
            Log.e(TAG, "Restart broadcast failed: " + e.getMessage());
        }

        super.onDestroy();
    }
}
