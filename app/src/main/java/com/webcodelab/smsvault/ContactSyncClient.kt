package com.webcodelab.smsvault

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.PowerManager
import android.provider.ContactsContract
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

object ContactSyncClient {

    private val isSyncRunning = AtomicBoolean(false)

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .build()
    }

    private val gson = Gson()

    fun scanContactsIntoQueue(context: Context): Int {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return 0
        }

        val items = mutableListOf<ContactItem>()
        val seenHashes = hashSetOf<String>()

        try {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                null,
                null,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
            )?.use { cursor ->
                val idxName = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val idxNumber = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                if (idxName < 0 || idxNumber < 0) return 0

                while (cursor.moveToNext()) {
                    val name = cursor.getString(idxName)?.trim().orEmpty().ifEmpty { "Unknown" }
                    val rawNumber = cursor.getString(idxNumber)?.trim().orEmpty()
                    val cleanNumber = rawNumber.replace(Regex("\\s+"), " ").trim()
                    if (cleanNumber.isEmpty()) continue

                    val hash = SmsHashUtil.sha256Contact(name, cleanNumber)
                    if (seenHashes.add(hash)) {
                        items.add(
                            ContactItem(
                                id = 0,
                                name = name,
                                phoneNumber = cleanNumber,
                                contactHash = hash,
                                isSynced = false,
                                syncedAtIso = null
                            )
                        )
                    }
                }
            }
        } catch (_: Exception) {
            return 0
        }

        if (items.isNotEmpty()) {
            SmsDatabaseHelper.getInstance(context).insertAllContactsIgnore(items)
        }
        return items.size
    }

    fun syncPending(context: Context): Boolean {
        if (!isSyncRunning.compareAndSet(false, true)) {
            return true
        }

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val wakeLock = powerManager?.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "WCL::ContactSyncWakeLock"
        )

        try {
            wakeLock?.acquire(30_000L)

            if (!SmsSyncClient.isDeviceOnline(context)) {
                return false
            }

            val db = SmsDatabaseHelper.getInstance(context)
            val apiKey = VaultPrefs.getApiKey(context)
            val deviceId = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ANDROID_ID
            ) ?: "android-device"

            while (true) {
                val batch = db.getPendingContactsBatch(limit = 100)
                if (batch.isEmpty()) {
                    return true
                }

                val payload = mapOf(
                    "device_id" to deviceId,
                    "contacts" to batch.map {
                        mapOf(
                            "name" to it.name,
                            "phone_number" to it.phoneNumber,
                            "contact_hash" to it.contactHash
                        )
                    }
                )

                val requestBody = gson.toJson(payload)
                    .toRequestBody("application/json; charset=utf-8".toMediaType())

                val requestBuilder = Request.Builder()
                    .url(VaultPrefs.CONTACTS_ENDPOINT_URL)
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
                    db.markContactsSynced(batch.map { it.contactHash }, nowIso)
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
