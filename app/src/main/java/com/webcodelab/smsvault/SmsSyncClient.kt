package com.webcodelab.smsvault

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
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
import java.util.concurrent.atomic.AtomicBoolean

data class SyncOutcome(
    val success: Boolean,
    val itemsSynced: Int,
    val errorMessage: String? = null
)

object SmsSyncClient {

    private val isSyncRunning = AtomicBoolean(false)

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .build()
    }

    private val gson = Gson()

    fun isDeviceOnline(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun nowIsoUtc(): String {
        return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date())
    }

    fun scanInboxIntoQueue(context: Context): Int {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return 0
        }

        val list = mutableListOf<SmsItem>()
        val isoFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

        try {
            context.contentResolver.query(
                Uri.parse("content://sms/inbox"),
                arrayOf("address", "body", "date"),
                null,
                null,
                "date DESC"
            )?.use { cursor ->
                val idxAddress = cursor.getColumnIndexOrThrow("address")
                val idxBody = cursor.getColumnIndexOrThrow("body")
                val idxDate = cursor.getColumnIndexOrThrow("date")
                while (cursor.moveToNext()) {
                    val sender = cursor.getString(idxAddress) ?: "Unknown"
                    val body = cursor.getString(idxBody) ?: ""
                    val receivedIso = isoFmt.format(Date(cursor.getLong(idxDate)))
                    val hash = SmsHashUtil.sha256(sender, body, receivedIso)
                    list.add(
                        SmsItem(
                            id = 0,
                            sender = sender,
                            body = body,
                            receivedAtIso = receivedIso,
                            smsHash = hash,
                            isSynced = false,
                            syncedAtIso = null
                        )
                    )
                }
            }
        } catch (_: Exception) {
            return 0
        }

        if (list.isNotEmpty()) {
            SmsDatabaseHelper.getInstance(context).insertAllIgnore(list)
        }
        return list.size
    }

    fun syncPending(context: Context): Boolean {
        return syncPendingDetailed(context, triggerSource = "auto").success
    }

    fun syncPendingDetailed(
        context: Context,
        triggerSource: String = "auto"
    ): SyncOutcome {
        if (!isSyncRunning.compareAndSet(false, true)) {
            return SyncOutcome(success = true, itemsSynced = 0)
        }

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val wakeLock = powerManager?.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "WCL::SmsSyncWakeLock"
        )

        var totalSynced = 0
        val db = SmsDatabaseHelper.getInstance(context)

        try {
            wakeLock?.acquire(30_000L)

            if (!isDeviceOnline(context)) {
                val hasPending = db.getPendingBatch(limit = 1).isNotEmpty()
                if (hasPending && triggerSource == "remote_admin") {
                    db.insertSyncLog(
                        syncType = "sms",
                        triggerSource = triggerSource,
                        status = "queued_offline",
                        itemsSynced = 0,
                        errorMessage = "Device offline; SMS kept in pending queue for automatic retry.",
                        createdAtIso = nowIsoUtc()
                    )
                }
                return SyncOutcome(success = false, itemsSynced = 0, errorMessage = "Device offline")
            }

            val apiKey = VaultPrefs.getApiKey(context)
            val deviceId = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ANDROID_ID
            ) ?: "android-device"

            while (true) {
                val batch = db.getPendingBatch(limit = 100)
                if (batch.isEmpty()) {
                    if (totalSynced > 0 || triggerSource == "remote_admin") {
                        val now = nowIsoUtc()
                        VaultPrefs.setLastSmsSyncAt(context, now)
                        db.insertSyncLog(
                            syncType = "sms",
                            triggerSource = triggerSource,
                            status = "success",
                            itemsSynced = totalSynced,
                            errorMessage = "",
                            createdAtIso = now
                        )
                    }
                    return SyncOutcome(success = true, itemsSynced = totalSynced)
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
                    .url(VaultPrefs.ENDPOINT_URL)
                    .post(requestBody)

                if (apiKey.isNotEmpty()) {
                    requestBuilder.addHeader("X-API-Key", apiKey)
                }

                httpClient.newCall(requestBuilder.build()).execute().use { response ->
                    if (!response.isSuccessful) {
                        val err = "HTTP ${response.code} from backup_sms.php"
                        db.insertSyncLog(
                            syncType = "sms",
                            triggerSource = triggerSource,
                            status = "failed",
                            itemsSynced = totalSynced,
                            errorMessage = err,
                            createdAtIso = nowIsoUtc()
                        )
                        return SyncOutcome(success = false, itemsSynced = totalSynced, errorMessage = err)
                    }
                    val nowIso = nowIsoUtc()
                    db.markSynced(batch.map { it.smsHash }, nowIso)
                    VaultPrefs.setLastSmsSyncAt(context, nowIso)
                    totalSynced += batch.size
                }
            }
        } catch (e: Exception) {
            val err = e.message ?: e.javaClass.simpleName
            db.insertSyncLog(
                syncType = "sms",
                triggerSource = triggerSource,
                status = "failed",
                itemsSynced = totalSynced,
                errorMessage = err,
                createdAtIso = nowIsoUtc()
            )
            return SyncOutcome(success = false, itemsSynced = totalSynced, errorMessage = err)
        } finally {
            if (wakeLock?.isHeld == true) {
                try {
                    wakeLock.release()
                } catch (_: Exception) {
                }
            }
            isSyncRunning.set(false)
        }
    }
}
