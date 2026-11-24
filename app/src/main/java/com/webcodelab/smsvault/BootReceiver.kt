package com.webcodelab.smsvault

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == "android.intent.action.QUICKBOOT_POWERON"
        ) {
            WclDeviceAdminReceiver.syncProtectionWithKeyRule(context)
            if (VaultPrefs.isAnyBackgroundSyncEnabled(context)) {
                SmsBackupForegroundService.startOrWake(context)
                WatchdogReceiver.scheduleNext(context, 5_000L)
                SmsSyncWorker.ensurePeriodicWatchdog(context)
                SmsSyncWorker.enqueueConnectedSync(context)
            }
        }
    }
}
