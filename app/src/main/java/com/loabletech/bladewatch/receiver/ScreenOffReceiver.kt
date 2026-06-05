package com.overdrive.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log

/**
 * Handles SCREEN_OFF events to ensure daemon survival during sleep.
 * 
 * NOTE: SCREEN_OFF cannot be registered in manifest - must be registered dynamically.
 * This receiver is registered in OverdriveApplication.
 * 
 * Delegates to BootReceiver to reuse the same daemon startup logic.
 */
class ScreenOffReceiver : BroadcastReceiver() {
    
    companion object {
        const val TAG = "ScreenOffReceiver"
        
        /**
         * Register this receiver dynamically.
         * Call from Application.onCreate().
         */
        fun register(context: Context): ScreenOffReceiver {
            val receiver = ScreenOffReceiver()
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
            }
            context.applicationContext.registerReceiver(receiver, filter)
            Log.i(TAG, "ScreenOffReceiver registered")
            return receiver
        }
        
        /**
         * Unregister this receiver.
         */
        fun unregister(context: Context, receiver: ScreenOffReceiver) {
            try {
                context.applicationContext.unregisterReceiver(receiver)
                Log.i(TAG, "ScreenOffReceiver unregistered")
            } catch (e: Exception) {
                Log.w(TAG, "Error unregistering: ${e.message}")
            }
        }
    }
    
    private val bootReceiver = BootReceiver()
    
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_SCREEN_OFF) return
        
        Log.d(TAG, "SCREEN_OFF detected - delegating to BootReceiver")
        
        // Delegate to BootReceiver which handles all the daemon startup logic
        bootReceiver.onReceive(context, intent)
    }
}
