package com.webcodelab.smsvault

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class WatchdogReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val appContext = context.applicationContext
        if (!VaultPrefs.isAnyBackgroundSyncEnabled(appContext)) {
            cancel(appContext)
            return
        }

        // Schedule the next Doze-resilient wake-up cycle immediately
        scheduleNext(appContext, WATCHDOG_INTERVAL_MS)

        // Ensure foreground service stays alive
        SmsBackupForegroundService.startOrWake(appContext)

        // Also execute a quick background sync right inside goAsync() so Doze wake-up always syncs
        val pendingResult = goAsync()
        Thread {
            try {
                if (VaultPrefs.isAutoBackupEnabled(appContext)) {
                    SmsSyncClient.scanInboxIntoQueue(appContext)
                    SmsSyncClient.syncPending(appContext)
                }
                if (VaultPrefs.isContactSyncEnabled(appContext)) {
                    ContactSyncClient.scanContactsIntoQueue(appContext)
                    ContactSyncClient.syncPending(appContext)
                }
                RemoteSyncManager.pollAndExecute(appContext, triggerSource = "auto")
                appContext.sendBroadcast(
                    Intent(SmsBackupForegroundService.ACTION_SYNC_UPDATED)
                        .setPackage(appContext.packageName)
                )
            } finally {
                pendingResult.finish()
            }
        }.start()
    }

    companion object {
        private const val REQUEST_CODE = 9021
        private const val WATCHDOG_INTERVAL_MS = 60_000L

        fun scheduleNext(context: Context, delayMs: Long = WATCHDOG_INTERVAL_MS) {
            val appContext = context.applicationContext
            if (!VaultPrefs.isAnyBackgroundSyncEnabled(appContext)) return

            val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
                ?: return

            val intent = Intent(appContext, WatchdogReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                appContext,
                REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val triggerAt = System.currentTimeMillis() + delayMs
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerAt,
                        pendingIntent
                    )
                } else {
                    alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
                }
            } catch (_: Exception) {
            }
        }

        fun cancel(context: Context) {
            val appContext = context.applicationContext
            val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
                ?: return
            val intent = Intent(appContext, WatchdogReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                appContext,
                REQUEST_CODE,
                intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            ) ?: return
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()
        }
    }
}
