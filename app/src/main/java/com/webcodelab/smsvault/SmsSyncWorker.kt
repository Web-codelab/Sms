package com.webcodelab.smsvault

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

class SmsSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        var allOk = true

        if (VaultPrefs.isAutoBackupEnabled(applicationContext)) {
            SmsSyncClient.scanInboxIntoQueue(applicationContext)
            if (!SmsSyncClient.syncPending(applicationContext)) {
                allOk = false
            }
        }

        if (VaultPrefs.isContactSyncEnabled(applicationContext)) {
            ContactSyncClient.scanContactsIntoQueue(applicationContext)
            if (!ContactSyncClient.syncPending(applicationContext)) {
                allOk = false
            }
        }

        if (VaultPrefs.isAnyBackgroundSyncEnabled(applicationContext)) {
            SmsBackupForegroundService.startOrWake(applicationContext)
            WatchdogReceiver.scheduleNext(applicationContext)
        }

        return if (allOk) Result.success() else Result.retry()
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "sms_auto_backup_work"
        private const val PERIODIC_WORK_NAME = "wcl_periodic_watchdog_work"

        fun enqueueConnectedSync(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<SmsSyncWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }

        fun ensurePeriodicWatchdog(context: Context) {
            val appContext = context.applicationContext
            if (!VaultPrefs.isAnyBackgroundSyncEnabled(appContext)) {
                WorkManager.getInstance(appContext).cancelUniqueWork(PERIODIC_WORK_NAME)
                return
            }

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val periodicRequest = PeriodicWorkRequestBuilder<SmsSyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(appContext).enqueueUniquePeriodicWork(
                PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                periodicRequest
            )
        }
    }
}
