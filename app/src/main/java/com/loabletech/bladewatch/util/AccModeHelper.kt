package com.overdrive.app.util

import android.content.Context
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import com.overdrive.app.shell.HiddenApiBypass

/**
 * Helper to whitelist app for BYD ACC (Accessory) mode.
 * 
 * IMPORTANT: 
 * - AccModeManager whitelist requires DEVICE_ACC permission (signature-level)
 * - Only ADB shell (UID 2000) can call accmodemanager - use AdbDaemonLauncher.injectAccWhitelist()
 * - This class handles the data cache registration which has lower security requirements
 * 
 * Call HiddenApiBypass.bypass() before using this class!
 */
object AccModeHelper {
    private const val TAG = "AccModeHelper"
    
    /**
     * Register with BYD background data cache services using raw Binder transactions.
     */
    fun registerBgDataCache(context: Context): Boolean {
        if (!HiddenApiBypass.isBypassed()) {
            HiddenApiBypass.bypass()
        }
        
        val uid = try {
            context.packageManager.getApplicationInfo(context.packageName, 0).uid
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get UID: ${e.message}")
            return false
        }
        
        Log.d(TAG, "Registering with BYD data cache services (uid=$uid)...")
        
        var success = false
        
        success = tryBydDataCachedViaServiceManager(uid) || success
        success = tryBgDataCacheViaServiceManager(uid) || success
        
        return success
    }
    
    private fun tryBydDataCachedViaServiceManager(uid: Int): Boolean {
        return try {
            val sm = Class.forName("android.os.ServiceManager")
            val getService = sm.getMethod("getService", String::class.java)
            val binder = getService.invoke(null, "byd_datacached") as? IBinder
            
            if (binder == null) {
                Log.d(TAG, "byd_datacached service not found via ServiceManager")
                return false
            }
            
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken("android.os.IBYDDataCachedManager")
                data.writeString(uid.toString())
                data.writeInt(0)
                
                binder.transact(1, data, reply, 0)
                reply.readException()
                Log.d(TAG, "byd_datacached transaction success (uid=$uid)")
                true
            } finally {
                data.recycle()
                reply.recycle()
            }
        } catch (e: Exception) {
            Log.d(TAG, "byd_datacached via ServiceManager failed: ${e.message}")
            false
        }
    }
    
    private fun tryBgDataCacheViaServiceManager(uid: Int): Boolean {
        return try {
            val sm = Class.forName("android.os.ServiceManager")
            val getService = sm.getMethod("getService", String::class.java)
            val binder = getService.invoke(null, "bg_datacache") as? IBinder
            
            if (binder == null) {
                Log.d(TAG, "bg_datacache service not found via ServiceManager")
                return false
            }
            
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken("android.os.IAppOpsDataCachedManager")
                data.writeString(uid.toString())
                data.writeInt(0)
                
                binder.transact(1, data, reply, 0)
                reply.readException()
                Log.d(TAG, "bg_datacache transaction success (uid=$uid)")
                true
            } finally {
                data.recycle()
                reply.recycle()
            }
        } catch (e: Exception) {
            Log.d(TAG, "bg_datacache via ServiceManager failed: ${e.message}")
            false
        }
    }
    
    /**
     * Setup ACC mode - only registers with data cache.
     * 
     * IMPORTANT: Call AdbDaemonLauncher.injectAccWhitelist() separately for the 
     * accmodemanager whitelist (requires shell privileges).
     */
    fun setupAccMode(context: Context) {
        Log.d(TAG, "Setting up ACC mode data cache for ${context.packageName}...")
        
        val bgCacheSuccess = registerBgDataCache(context)
        
        Log.d(TAG, "ACC mode data cache setup complete - BgCache: $bgCacheSuccess")
        Log.d(TAG, "NOTE: AccModeManager whitelist is handled via ADB shell")
    }
}
