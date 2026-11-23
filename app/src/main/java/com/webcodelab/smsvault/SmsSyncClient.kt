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
        if (!isSyncRunning.compareAndSet(false, true)) {
            return true
        }

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val wakeLock = powerManager?.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "WCL::SmsSyncWakeLock"
        )

        try {
            wakeLock?.acquire(30_000L)

            if (!isDeviceOnline(context)) {
                return false
            }

            val db = SmsDatabaseHelper.getInstance(context)
            val apiKey = VaultPrefs.getApiKey(context)
            val deviceId = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ANDROID_ID
            ) ?: "android-device"

            while (true) {
                val batch = db.getPendingBatch(limit = 100)
                if (batch.isEmpty()) {
                    return true
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
                        return false
                    }
                    val nowIso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
                        .apply { timeZone = TimeZone.getTimeZone("UTC") }
                        .format(Date())
                    db.markSynced(batch.map { it.smsHash }, nowIso)
                }
            }
        } catch (_: Exception) {
            return false
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
