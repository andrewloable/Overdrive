package com.overdrive.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.overdrive.app.services.LocationSidecarService

/**
 * Boot receiver to auto-start LocationSidecarService after device reboot.
 * Uses the same pattern as other boot receivers for consistent auto-start behavior.
 */
class LocationBootReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "LocationBootReceiver"
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        // Skip MY_PACKAGE_REPLACED — MainActivity starts LocationSidecarService on
        // every launch, and the post-update path needs UpdateLifecycle.hardResetDaemons
        // to run first. Keep cold-boot path so location works without UI.
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {

            Log.i(TAG, "Boot received, starting LocationSidecarService...")
            
            try {
                val serviceIntent = Intent(context, LocationSidecarService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
                Log.i(TAG, "LocationSidecarService start requested")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start LocationSidecarService: ${e.message}", e)
            }
        }
    }
}
