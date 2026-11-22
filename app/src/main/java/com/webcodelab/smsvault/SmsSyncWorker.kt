package com.webcodelab.smsvault

import android.content.Context
import android.provider.Settings
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.gson.Gson
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

class SmsSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    override suspend fun doWork(): Result {
        val db = SmsDatabaseHelper.getInstance(applicationContext)
        val endpointUrl = VaultPrefs.ENDPOINT_URL
        val apiKey = VaultPrefs.getApiKey(applicationContext)
        val deviceId = Settings.Secure.getString(
            applicationContext.contentResolver,
            Settings.Secure.ANDROID_ID
        ) ?: "android-device"

        while (true) {
            val batch = db.getPendingBatch(limit = 100)
            if (batch.isEmpty()) {
                return Result.success()
            }

            val payload = mapOf(
                "device_id" to deviceId,
                "messages" to batch.map {
                    mapOf(
                        "sender" to it.sender,
                        "body" to it.body,
                        "received_at" to it.receivedAtIso,
                        "sms_hash" to it.smsHash
                    )
                }
            )

            val requestBody = gson.toJson(payload)
                .toRequestBody("application/json; charset=utf-8".toMediaType())

            val requestBuilder = Request.Builder()
                .url(endpointUrl)
                .post(requestBody)

            if (apiKey.isNotEmpty()) {
                requestBuilder.addHeader("X-API-Key", apiKey)
            }

            try {
                client.newCall(requestBuilder.build()).execute().use { response ->
                    if (!response.isSuccessful) {
                        return Result.retry()
                    }
                    val nowIso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
                        .apply { timeZone = TimeZone.getTimeZone("UTC") }
                        .format(Date())
                    db.markSynced(batch.map { it.smsHash }, nowIso)
                }
            } catch (_: Exception) {
                return Result.retry()
            }
        }
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "sms_auto_backup_work"

        fun enqueueConnectedSync(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<SmsSyncWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }
}
