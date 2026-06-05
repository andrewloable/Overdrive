package com.overdrive.app.util

import android.content.Context
import android.widget.Toast

/**
 * Kotlin extension functions for common operations.
 */

/**
 * Show a toast message.
 */
fun Context.toast(message: String, duration: Int = Toast.LENGTH_SHORT) {
    Toast.makeText(this, message, duration).show()
}

/**
 * Safe string to int conversion.
 */
fun String.toIntOrDefault(default: Int): Int {
    return this.toIntOrNull() ?: default
}

/**
 * Safe string to long conversion.
 */
fun String.toLongOrDefault(default: Long): Long {
    return this.toLongOrNull() ?: default
}
