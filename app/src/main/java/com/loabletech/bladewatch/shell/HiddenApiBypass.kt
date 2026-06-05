package com.overdrive.app.shell

import android.util.Log
import java.lang.reflect.Method

/**
 * Bypasses Android's Hidden API restrictions using "Double Reflection" technique.
 * This allows access to internal system classes that are normally blocked.
 * 
 * Must be called as early as possible (Application.onCreate or Activity.onCreate).
 */
object HiddenApiBypass {
    private const val TAG = "HiddenApiBypass"
    
    @Volatile
    private var bypassed = false
    
    /**
     * Bypass hidden API restrictions.
     * Safe to call multiple times - will only execute once.
     */
    fun bypass(): Boolean {
        if (bypassed) {
            Log.d(TAG, "Already bypassed")
            return true
        }
        
        return try {
            // Double reflection technique:
            // 1. Get Class.forName and Class.getDeclaredMethod via reflection
            // 2. Use those to access VMRuntime.setHiddenApiExemptions
            // This works because the first level of reflection isn't blocked
            
            val classClass = Class::class.java
            val forName = classClass.getDeclaredMethod("forName", String::class.java)
            val getDeclaredMethod = classClass.getDeclaredMethod(
                "getDeclaredMethod",
                String::class.java,
                arrayOf<Class<*>>()::class.java
            )
            
            // Get VMRuntime class
            val vmRuntimeClass = forName.invoke(null, "dalvik.system.VMRuntime") as Class<*>
            
            // Get VMRuntime.getRuntime()
            val getRuntime = getDeclaredMethod.invoke(
                vmRuntimeClass,
                "getRuntime",
                null
            ) as Method
            
            // Get VMRuntime.setHiddenApiExemptions(String[])
            val setHiddenApiExemptions = getDeclaredMethod.invoke(
                vmRuntimeClass,
                "setHiddenApiExemptions",
                arrayOf(Array<String>::class.java)
            ) as Method
            
            // Get the runtime instance
            val vmRuntime = getRuntime.invoke(null)
            
            // Exempt ALL hidden APIs by passing "L" (matches all class signatures)
            // Pass String[] directly, not wrapped in another array
            val exemptions = arrayOf("L")
            setHiddenApiExemptions.invoke(vmRuntime, exemptions as Any)
            
            bypassed = true
            Log.d(TAG, "Hidden API restrictions bypassed successfully!")
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "Hidden API bypass failed: ${e.message}")
            e.printStackTrace()
            false
        }
    }
    
    /**
     * Check if bypass was successful.
     */
    fun isBypassed(): Boolean = bypassed
}
