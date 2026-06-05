package com.overdrive.app.receiver

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.overdrive.app.services.DaemonKeepaliveService

/**
 * Out-of-process revival watchdog.
 *
 * AlarmManager fires this receiver every [INTERVAL_MS] regardless of whether
 * the app process is alive — Android resurrects the process to deliver the
 * broadcast. The single job here is to (re)start DaemonKeepaliveService,
 * which in turn brings the daemon stack back up via DaemonStartupManager.
 *
 * MCU wake / WiFi-cut prevention / BMS sampling are all handled by
 * AccSentryDaemon's in-process loops once the process is alive again, so this
 * receiver does not duplicate any of that.
 *
 * Chain repair: every alarm fire re-arms the next one before doing any work,
 * so a crash mid-handler does not break the chain. A primary + backup pair
 * is scheduled at all times — if the OS drops the primary, the backup fires
 * [BACKUP_OFFSET_MS] later and re-seeds both.
 */
class ProcessRevivalReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        Log.i(TAG, "Revival alarm fired (data=${intent.dataString})")

        // Re-arm first so a crash below doesn't break the chain.
        try {
            schedule(appContext)
        } catch (e: Exception) {
            Log.w(TAG, "Re-arm failed: ${e.message}")
        }

        // Kick the keepalive service. Its onStartCommand calls
        // DaemonStartupManager.startOnBoot(), which on a freshly-revived
        // process actually runs (bootStarted was reset by process death).
        try {
            DaemonKeepaliveService.start(appContext)
        } catch (e: Exception) {
            Log.w(TAG, "DaemonKeepaliveService.start failed: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "ProcessRevival"
        private const val ACTION = "com.overdrive.app.action.PROCESS_REVIVAL"

        // 8 minutes — well inside the BYD MCU's ~10-15 min WiFi-cut budget.
        // AccSentryDaemon's in-process 10s loop dominates when the process is
        // alive, so this interval only matters during dead-process windows.
        private const val INTERVAL_MS = 8 * 60 * 1000L

        // Backup fires this much later than the primary. If the primary alarm
        // is dropped by the OS, the backup re-seeds the chain.
        private const val BACKUP_OFFSET_MS = 4 * 60 * 1000L

        private const val PRIMARY_REQUEST_CODE = 0xD001
        private const val BACKUP_REQUEST_CODE = 0xD002

        private fun buildPendingIntent(
            context: Context,
            requestCode: Int,
            tag: String,
            flags: Int,
        ): PendingIntent? {
            val intent = Intent(context, ProcessRevivalReceiver::class.java).apply {
                action = ACTION
                // Distinct data URIs so primary/backup PendingIntents don't
                // collide under PendingIntent equality rules.
                data = Uri.parse("overdrive://revival/$tag")
            }
            return PendingIntent.getBroadcast(context, requestCode, intent, flags)
        }

        /**
         * Schedule (or reschedule) the primary + backup revival alarms.
         * Safe to call repeatedly — FLAG_UPDATE_CURRENT replaces in place.
         */
        fun schedule(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            if (alarmManager == null) {
                Log.w(TAG, "AlarmManager unavailable")
                return
            }
            val now = System.currentTimeMillis()
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

            buildPendingIntent(context, PRIMARY_REQUEST_CODE, "primary", flags)?.let { pi ->
                try {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP, now + INTERVAL_MS, pi,
                    )
                } catch (e: SecurityException) {
                    // API 31+ without SCHEDULE_EXACT_ALARM — fall back to inexact.
                    Log.w(TAG, "Exact alarm denied, falling back to inexact: ${e.message}")
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP, now + INTERVAL_MS, pi,
                    )
                }
            }

            buildPendingIntent(context, BACKUP_REQUEST_CODE, "backup", flags)?.let { pi ->
                try {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP, now + INTERVAL_MS + BACKUP_OFFSET_MS, pi,
                    )
                } catch (e: SecurityException) {
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP, now + INTERVAL_MS + BACKUP_OFFSET_MS, pi,
                    )
                }
            }
        }
    }
}
