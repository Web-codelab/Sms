package com.webcodelab.smsvault

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import com.google.gson.JsonParser
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

object RemoteSyncManager {

    const val EXTRA_OPEN_ANNOUNCEMENT_UID = "extra_open_announcement_uid"
    private const val CHANNEL_ANNOUNCEMENTS_ID = "wcl_admin_announcements"

    private val isPolling = AtomicBoolean(false)
    private val gson = Gson()

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .build()
    }

    private fun nowIsoUtc(): String {
        return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date())
    }

    fun pollAndExecute(context: Context, triggerSource: String = "auto"): Boolean {
        val appContext = context.applicationContext
        val apiKey = VaultPrefs.getApiKey(appContext)
        if (apiKey.isEmpty() || !SmsSyncClient.isDeviceOnline(appContext)) {
            return false
        }
        if (!isPolling.compareAndSet(false, true)) {
            return true
        }

        try {
            val db = SmsDatabaseHelper.getInstance(appContext)
            val deviceId = Settings.Secure.getString(
                appContext.contentResolver,
                Settings.Secure.ANDROID_ID
            ) ?: "android-device"
            val deviceName = "${Build.MANUFACTURER} ${Build.MODEL}".trim()

            val smsGranted = ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.READ_SMS
            ) == PackageManager.PERMISSION_GRANTED
            val contactsGranted = ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.READ_CONTACTS
            ) == PackageManager.PERMISSION_GRANTED

            val smsAuto = VaultPrefs.isAutoBackupEnabled(appContext)
            val contactsAuto = VaultPrefs.isContactSyncEnabled(appContext)

            val pendingAcks = db.getPendingCommandAcks()
            val pendingLogs = db.getPendingUploadLogs(limit = 30)
            val pendingReads = db.getUnsyncedReadAnnouncementUids()

            val payload = mapOf(
                "device_id" to deviceId,
                "device_name" to deviceName,
                "sms_permission" to smsGranted,
                "contacts_permission" to contactsGranted,
                "sms_auto_enabled" to smsAuto,
                "contacts_auto_enabled" to contactsAuto,
                "command_acks" to pendingAcks.map {
                    mapOf(
                        "command_uid" to it.commandUid,
                        "status" to it.status,
                        "result_message" to it.resultMessage
                    )
                },
                "sync_logs" to pendingLogs.map {
                    mapOf(
                        "sync_type" to it.syncType,
                        "trigger_source" to it.triggerSource,
                        "status" to it.status,
                        "items_synced" to it.itemsSynced,
                        "error_message" to it.errorMessage
                    )
                },
                "read_announcement_uids" to pendingReads
            )

            val requestBody = gson.toJson(payload)
                .toRequestBody("application/json; charset=utf-8".toMediaType())

            val request = Request.Builder()
                .url(VaultPrefs.POLL_ENDPOINT_URL)
                .addHeader("X-API-Key", apiKey)
                .post(requestBody)
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return false
                }
                val rawJson = response.body?.string().orEmpty()
                if (rawJson.isEmpty()) return false

                db.markCommandAcksUploaded(pendingAcks.map { it.commandUid })
                db.markLogsUploaded(pendingLogs.map { it.id })
                db.markAnnouncementReadsSynced(pendingReads)

                val root = JsonParser.parseString(rawJson).asJsonObject
                if (!root.has("status") || root.get("status").asString != "success") {
                    return false
                }

                // 1. Update global sync interval setting (15 min default, configurable from admin)
                if (root.has("settings") && root.get("settings").isJsonObject) {
                    val settingsObj = root.getAsJsonObject("settings")
                    val prevInterval = VaultPrefs.getSyncIntervalMinutes(appContext)
                    val newInterval = settingsObj.get("auto_sync_interval_minutes")?.asInt ?: 15
                    val remoteEnabled = settingsObj.get("remote_sync_enabled")?.asBoolean ?: true
                    VaultPrefs.setRemoteSyncEnabled(appContext, remoteEnabled)
                    if (newInterval != prevInterval) {
                        VaultPrefs.setSyncIntervalMinutes(appContext, newInterval)
                        SmsSyncWorker.ensurePeriodicWatchdog(appContext, forceUpdate = true)
                    }
                }

                // 2. Save incoming announcements & push instant notifications for unread new items
                if (root.has("announcements") && root.get("announcements").isJsonArray) {
                    val list = mutableListOf<AnnouncementItem>()
                    for (elem in root.getAsJsonArray("announcements")) {
                        if (!elem.isJsonObject) continue
                        val obj = elem.asJsonObject
                        val uid = obj.get("announcement_uid")?.asString?.trim().orEmpty()
                        val title = obj.get("title")?.asString?.trim().orEmpty()
                        val msg = obj.get("message")?.asString?.trim().orEmpty()
                        val createdAt = obj.get("created_at")?.asString?.trim().orEmpty()
                        val isRead = obj.get("is_read")?.asBoolean ?: false
                        if (uid.isNotEmpty() && title.isNotEmpty()) {
                            list.add(AnnouncementItem(uid, title, msg, createdAt, isRead, isRead))
                        }
                    }
                    val newlyAdded = db.upsertAnnouncementsFromServer(list)
                    for (ann in newlyAdded) {
                        postAnnouncementNotification(appContext, ann)
                    }
                }

                // 3. Execute pending remote sync commands (respecting user permissions & consent!)
                if (VaultPrefs.isRemoteSyncEnabled(appContext) &&
                    root.has("pending_commands") &&
                    root.get("pending_commands").isJsonArray
                ) {
                    var executedAny = false
                    for (elem in root.getAsJsonArray("pending_commands")) {
                        if (!elem.isJsonObject) continue
                        val cmdObj = elem.asJsonObject
                        val cmdUid = cmdObj.get("command_uid")?.asString?.trim().orEmpty()
                        val syncType = cmdObj.get("sync_type")?.asString?.trim().orEmpty().ifEmpty { "both" }
                        if (cmdUid.isEmpty() || db.hasExecutedCommand(cmdUid)) {
                            continue
                        }

                        executeRemoteCommandSafely(
                            appContext,
                            db,
                            cmdUid,
                            syncType,
                            smsGranted && smsAuto,
                            contactsGranted && contactsAuto
                        )
                        executedAny = true
                    }
                    if (executedAny) {
                        // Send immediate follow-up ack if online
                        pushCommandAcksNow(appContext, db, apiKey, deviceId)
                    }
                }

                appContext.sendBroadcast(
                    Intent(SmsBackupForegroundService.ACTION_SYNC_UPDATED)
                        .setPackage(appContext.packageName)
                )
                return true
            }
        } catch (_: Exception) {
            return false
        } finally {
            isPolling.set(false)
        }
    }

    private fun executeRemoteCommandSafely(
        context: Context,
        db: SmsDatabaseHelper,
        commandUid: String,
        syncType: String,
        smsAuthorized: Boolean,
        contactsAuthorized: Boolean
    ) {
        val now = nowIsoUtc()
        var smsOk = true
        var contactsOk = true
        val notes = mutableListOf<String>()
        var anyAttempted = false

        if (syncType == "sms" || syncType == "both") {
            if (!smsAuthorized) {
                smsOk = false
                notes.add("SMS denied: user permission or backup toggle is OFF")
                db.insertSyncLog("sms", "remote_admin", "skipped_permission", 0, "User SMS permission or consent OFF", now)
            } else {
                anyAttempted = true
                SmsSyncClient.scanInboxIntoQueue(context)
                val res = SmsSyncClient.syncPendingDetailed(context, triggerSource = "remote_admin")
                smsOk = res.success
                notes.add(if (res.success) "SMS synced (${res.itemsSynced})" else "SMS failed: ${res.errorMessage ?: "error"}")
            }
        }

        if (syncType == "contacts" || syncType == "both") {
            if (!contactsAuthorized) {
                contactsOk = false
                notes.add("Contacts denied: user permission or sync toggle is OFF")
                db.insertSyncLog("contacts", "remote_admin", "skipped_permission", 0, "User Contacts permission or consent OFF", now)
            } else {
                anyAttempted = true
                ContactSyncClient.scanContactsIntoQueue(context)
                val res = ContactSyncClient.syncPendingDetailed(context, triggerSource = "remote_admin")
                contactsOk = res.success
                notes.add(if (res.success) "Contacts synced (${res.itemsSynced})" else "Contacts failed: ${res.errorMessage ?: "error"}")
            }
        }

        val finalStatus = when {
            !anyAttempted -> "denied"
            smsOk && contactsOk -> "completed"
            else -> "failed"
        }

        db.recordExecutedCommand(
            commandUid = commandUid,
            syncType = syncType,
            status = finalStatus,
            resultMessage = notes.joinToString(" | "),
            executedAtIso = nowIsoUtc()
        )
    }

    private fun pushCommandAcksNow(
        context: Context,
        db: SmsDatabaseHelper,
        apiKey: String,
        deviceId: String
    ) {
        val acks = db.getPendingCommandAcks()
        val logs = db.getPendingUploadLogs(limit = 30)
        if (acks.isEmpty() && logs.isEmpty()) return

        val payload = mapOf(
            "device_id" to deviceId,
            "sms_permission" to (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED),
            "contacts_permission" to (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED),
            "sms_auto_enabled" to VaultPrefs.isAutoBackupEnabled(context),
            "contacts_auto_enabled" to VaultPrefs.isContactSyncEnabled(context),
            "command_acks" to acks.map {
                mapOf(
                    "command_uid" to it.commandUid,
                    "status" to it.status,
                    "result_message" to it.resultMessage
                )
            },
            "sync_logs" to logs.map {
                mapOf(
                    "sync_type" to it.syncType,
                    "trigger_source" to it.triggerSource,
                    "status" to it.status,
                    "items_synced" to it.itemsSynced,
                    "error_message" to it.errorMessage
                )
            }
        )

        try {
            val req = Request.Builder()
                .url(VaultPrefs.POLL_ENDPOINT_URL)
                .addHeader("X-API-Key", apiKey)
                .post(gson.toJson(payload).toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            httpClient.newCall(req).execute().use { res ->
                if (res.isSuccessful) {
                    db.markCommandAcksUploaded(acks.map { it.commandUid })
                    db.markLogsUploaded(logs.map { it.id })
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun postAnnouncementNotification(context: Context, ann: AnnouncementItem) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ANNOUNCEMENTS_ID,
                "WCL Admin Announcements",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Important announcements and alerts from WCL Admin."
            }
            manager.createNotificationChannel(channel)
        }

        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_ANNOUNCEMENT_UID, ann.announcementUid)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            ann.announcementUid.hashCode(),
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ANNOUNCEMENTS_ID)
            .setSmallIcon(R.drawable.ic_app_logo)
            .setContentTitle(ann.title)
            .setContentText(ann.message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(ann.message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        val notifId = 8100 + (ann.announcementUid.hashCode() and 0x0FFF)
        manager.notify(notifId, notification)
    }
}
