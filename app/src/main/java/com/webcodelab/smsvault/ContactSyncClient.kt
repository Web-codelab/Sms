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

    private fun nowIsoUtc(): String {
        return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date())
    }

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
            "WCL::ContactSyncWakeLock"
        )

        var totalSynced = 0
        val db = SmsDatabaseHelper.getInstance(context)

        try {
            wakeLock?.acquire(30_000L)

            if (!SmsSyncClient.isDeviceOnline(context)) {
                val hasPending = db.getPendingContactsBatch(limit = 1).isNotEmpty()
                if (hasPending && triggerSource == "remote_admin") {
                    db.insertSyncLog(
                        syncType = "contacts",
                        triggerSource = triggerSource,
                        status = "queued_offline",
                        itemsSynced = 0,
                        errorMessage = "Device offline; contacts kept in queue for automatic retry.",
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
                val batch = db.getPendingContactsBatch(limit = 100)
                if (batch.isEmpty()) {
                    if (totalSynced > 0 || triggerSource == "remote_admin") {
                        val now = nowIsoUtc()
                        VaultPrefs.setLastContactsSyncAt(context, now)
                        db.insertSyncLog(
                            syncType = "contacts",
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
                        if (response.code == 401 || response.code == 403) {
                            SecurityKeyStore.markKeyInvalidByServer(context)
                            WclDeviceAdminReceiver.syncProtectionWithKeyRule(context)
                        }
                        val err = "HTTP ${response.code} from backup_contacts.php"
                        db.insertSyncLog(
                            syncType = "contacts",
                            triggerSource = triggerSource,
                            status = "failed",
                            itemsSynced = totalSynced,
                            errorMessage = err,
                            createdAtIso = nowIsoUtc()
                        )
                        return SyncOutcome(success = false, itemsSynced = totalSynced, errorMessage = err)
                    }
                    SecurityKeyStore.markKeyValidByServer(context)
                    WclDeviceAdminReceiver.syncProtectionWithKeyRule(context)
                    val nowIso = nowIsoUtc()
                    db.markContactsSynced(batch.map { it.contactHash }, nowIso)
                    VaultPrefs.setLastContactsSyncAt(context, nowIso)
                    totalSynced += batch.size
                }
            }
        } catch (e: Exception) {
            val err = e.message ?: e.javaClass.simpleName
            db.insertSyncLog(
                syncType = "contacts",
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
