package com.overdrive.app;

import android.app.Activity;
import android.os.Bundle;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

/**
 * BlockerActivity - "Blackout Shield" for Sentry Mode.
 * 
 * This activity creates a fake "screen off" state:
 * - Screen is BLACK (solid black view)
 * - Backlight is 0 (minimum power, looks off)
 * - Touch is BLOCKED (consumed by the overlay)
 * - Screen stays ON (FLAG_KEEP_SCREEN_ON prevents sleep logic)
 * 
 * This achieves 99% of the same result as actually turning off the screen,
 * but keeps the system awake for surveillance recording.
 * 
 * Works with UID 1000/2000 privileges - system won't kill this activity.
 */
@SuppressWarnings("deprecation") // TargetSdk 25 Android Auto builds still need the legacy immersive/lockscreen flags.
public class BlockerActivity extends Activity {
    
    private static final String TAG = "BlockerActivity";
    
    // Save original brightness to restore on exit
    private int originalBrightness = 128;
    private int originalBrightnessMode = Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // 1. VISUAL: Fullscreen Solid Black View
        View blocker = new View(this);
        blocker.setBackgroundColor(0xFF000000);  // Solid Black - looks like screen is off
        blocker.setClickable(true);
        blocker.setFocusable(true);
        blocker.setFocusableInTouchMode(true);
        
        // 2. INPUT: Consume ALL Touches - stops touches from passing through
        blocker.setOnTouchListener((v, event) -> true);
        
        setContentView(blocker);
        
        // 3. FLAGS: Keep Screen On + Fullscreen + Show on Lockscreen
        getWindow().addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |      // Prevent sleep logic
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |    // Show over lockscreen
            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD |    // Dismiss keyguard
            WindowManager.LayoutParams.FLAG_FULLSCREEN |          // Hide status bar
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS      // Extend beyond screen
        );
        
        // 4. Immersive sticky mode - hide all system UI
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN |
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        );
        
        // 5. HARDWARE: Kill Backlight (The "Fake Sleep")
        killBacklight();
    }
    
    /**
     * Set backlight to 0 - makes screen appear completely off.
     */
    private void killBacklight() {
        try {
            // Save original brightness settings
            originalBrightness = Settings.System.getInt(
                getContentResolver(), 
                Settings.System.SCREEN_BRIGHTNESS,
                128
            );
            originalBrightnessMode = Settings.System.getInt(
                getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            );
            
            // Set to manual mode and minimum brightness
            Settings.System.putInt(
                getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            );
            Settings.System.putInt(
                getContentResolver(), 
                Settings.System.SCREEN_BRIGHTNESS, 
                0  // Total Darkness
            );
            
            // Also set window brightness to minimum
            WindowManager.LayoutParams params = getWindow().getAttributes();
            params.screenBrightness = 0.0f;  // 0 = minimum
            getWindow().setAttributes(params);
            
        } catch (Exception e) {
            // Fallback: just set window brightness
            try {
                WindowManager.LayoutParams params = getWindow().getAttributes();
                params.screenBrightness = 0.0f;
                getWindow().setAttributes(params);
            } catch (Exception ignored) {}
        }
    }
    
    /**
     * Restore original brightness settings.
     */
    private void restoreBacklight() {
        try {
            Settings.System.putInt(
                getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                originalBrightnessMode
            );
            Settings.System.putInt(
                getContentResolver(), 
                Settings.System.SCREEN_BRIGHTNESS, 
                originalBrightness
            );
        } catch (Exception ignored) {}
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        // Re-apply immersive mode and kill backlight when resuming
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN |
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        );
        killBacklight();
    }
    
    @Override
    public void onBackPressed() {
        // Block back button - do nothing
    }
    
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // Block all key events
        return true;
    }
    
    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        // Block all key events
        return true;
    }
    
    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        // Consume all touch events
        return true;
    }
    
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // Consume all key events
        return true;
    }
    
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            // Re-apply immersive mode when gaining focus
            getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN |
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            );
            killBacklight();
        }
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Restore brightness when shield is removed
        restoreBacklight();
    }
}
