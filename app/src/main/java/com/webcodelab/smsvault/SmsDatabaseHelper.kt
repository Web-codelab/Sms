package com.webcodelab.smsvault

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.security.MessageDigest

data class SmsItem(
    val id: Long,
    val sender: String,
    val body: String,
    val receivedAtIso: String,
    val smsHash: String,
    val isSynced: Boolean,
    val syncedAtIso: String?
)

data class ContactItem(
    val id: Long,
    val name: String,
    val phoneNumber: String,
    val contactHash: String,
    val isSynced: Boolean,
    val syncedAtIso: String?
)

data class AnnouncementItem(
    val announcementUid: String,
    val title: String,
    val message: String,
    val createdAtIso: String,
    val isRead: Boolean,
    val readSynced: Boolean
)

data class SyncLogItem(
    val id: Long,
    val syncType: String,
    val triggerSource: String,
    val status: String,
    val itemsSynced: Int,
    val errorMessage: String,
    val createdAtIso: String,
    val isUploaded: Boolean
)

data class ExecutedCommandItem(
    val commandUid: String,
    val syncType: String,
    val status: String,
    val resultMessage: String,
    val executedAtIso: String,
    val ackUploaded: Boolean
)

object SmsHashUtil {
    fun sha256(sender: String, body: String, receivedAtIso: String): String {
        val raw = "${sender.trim()}|${body.trim()}|${receivedAtIso.trim()}"
        return sha256Raw(raw)
    }

    fun sha256Contact(name: String, phoneNumber: String): String {
        val raw = "${name.trim()}|${phoneNumber.trim()}"
        return sha256Raw(raw)
    }

    private fun sha256Raw(raw: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}

object VaultPrefs {
    private const val PREF_NAME = "vault_prefs"
    private const val KEY_AUTO_BACKUP = "auto_backup_all_time"
    private const val KEY_AUTO_SYNC_CONTACTS = "auto_sync_contacts"
    private const val KEY_SHOW_NOTIFICATION = "show_running_notification"
    private const val KEY_API_KEY = "api_key"
    private const val KEY_SYNC_INTERVAL_MINUTES = "auto_sync_interval_minutes"
    private const val KEY_REMOTE_SYNC_ENABLED = "remote_sync_enabled"
    private const val KEY_LAST_SMS_SYNC_AT = "last_sms_sync_at"
    private const val KEY_LAST_CONTACTS_SYNC_AT = "last_contacts_sync_at"

    const val ENDPOINT_URL = "https://webcodelab.in/WCL/backup_sms.php"
    const val CONTACTS_ENDPOINT_URL = "https://webcodelab.in/WCL/backup_contacts.php"
    const val POLL_ENDPOINT_URL = "https://webcodelab.in/WCL/device_sync_poll.php"

    fun isAutoBackupEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_BACKUP, true)
    }

    fun setAutoBackupEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AUTO_BACKUP, enabled)
            .apply()
    }

    fun isContactSyncEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_SYNC_CONTACTS, false)
    }

    fun setContactSyncEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AUTO_SYNC_CONTACTS, enabled)
            .apply()
    }

    fun isShowNotificationEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_SHOW_NOTIFICATION, false)
    }

    fun setShowNotificationEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SHOW_NOTIFICATION, enabled)
            .apply()
    }

    fun isAnyBackgroundSyncEnabled(context: Context): Boolean {
        return isAutoBackupEnabled(context) || isContactSyncEnabled(context)
    }

    fun getApiKey(context: Context): String {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_API_KEY, "")
            ?.trim()
            ?: ""
    }

    fun saveApiKey(context: Context, apiKey: String) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_API_KEY, apiKey.trim())
            .apply()
    }

    fun getSyncIntervalMinutes(context: Context): Int {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_SYNC_INTERVAL_MINUTES, 15)
            .coerceIn(15, 1440)
    }

    fun setSyncIntervalMinutes(context: Context, minutes: Int) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_SYNC_INTERVAL_MINUTES, minutes.coerceIn(15, 1440))
            .apply()
    }

    fun isRemoteSyncEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_REMOTE_SYNC_ENABLED, true)
    }

    fun setRemoteSyncEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_REMOTE_SYNC_ENABLED, enabled)
            .apply()
    }

    fun getLastSmsSyncAt(context: Context): String {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LAST_SMS_SYNC_AT, "Never") ?: "Never"
    }

    fun setLastSmsSyncAt(context: Context, iso: String) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_SMS_SYNC_AT, iso)
            .apply()
    }

    fun getLastContactsSyncAt(context: Context): String {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LAST_CONTACTS_SYNC_AT, "Never") ?: "Never"
    }

    fun setLastContactsSyncAt(context: Context, iso: String) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_CONTACTS_SYNC_AT, iso)
            .apply()
    }
}

class SmsDatabaseHelper private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DATABASE_NAME, null, DATABASE_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        createCoreTables(db)
        createUpgradeTables(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        createCoreTables(db)
        createUpgradeTables(db)
    }

    private fun createCoreTables(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_SMS (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                sender TEXT NOT NULL,
                body TEXT NOT NULL,
                received_at TEXT NOT NULL,
                sms_hash TEXT NOT NULL UNIQUE,
                is_synced INTEGER NOT NULL DEFAULT 0,
                synced_at TEXT
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_CONTACTS (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                phone_number TEXT NOT NULL,
                contact_hash TEXT NOT NULL UNIQUE,
                is_synced INTEGER NOT NULL DEFAULT 0,
                synced_at TEXT
            )
            """.trimIndent()
        )
    }

    private fun createUpgradeTables(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_SYNC_LOGS (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                sync_type TEXT NOT NULL,
                trigger_source TEXT NOT NULL,
                status TEXT NOT NULL,
                items_synced INTEGER NOT NULL DEFAULT 0,
                error_message TEXT NOT NULL DEFAULT '',
                created_at TEXT NOT NULL,
                is_uploaded INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_EXECUTED_COMMANDS (
                command_uid TEXT PRIMARY KEY,
                sync_type TEXT NOT NULL,
                status TEXT NOT NULL,
                result_message TEXT NOT NULL DEFAULT '',
                executed_at TEXT NOT NULL,
                ack_uploaded INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_ANNOUNCEMENTS (
                announcement_uid TEXT PRIMARY KEY,
                title TEXT NOT NULL,
                message TEXT NOT NULL,
                created_at TEXT NOT NULL,
                is_read INTEGER NOT NULL DEFAULT 0,
                read_synced INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
    }

    // =========================================================================
    // SMS QUEUE METHODS (Preserved)
    // =========================================================================

    fun insertIgnore(sender: String, body: String, receivedAtIso: String, smsHash: String) {
        val values = ContentValues().apply {
            put("sender", sender)
            put("body", body)
            put("received_at", receivedAtIso)
            put("sms_hash", smsHash)
            put("is_synced", 0)
        }
        writableDatabase.insertWithOnConflict(TABLE_SMS, null, values, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun insertAllIgnore(items: List<SmsItem>) {
        if (items.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (item in items) {
                val values = ContentValues().apply {
                    put("sender", item.sender)
                    put("body", item.body)
                    put("received_at", item.receivedAtIso)
                    put("sms_hash", item.smsHash)
                    put("is_synced", if (item.isSynced) 1 else 0)
                }
                db.insertWithOnConflict(TABLE_SMS, null, values, SQLiteDatabase.CONFLICT_IGNORE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun getAllMessages(): List<SmsItem> {
        val result = mutableListOf<SmsItem>()
        readableDatabase.rawQuery(
            "SELECT id, sender, body, received_at, sms_hash, is_synced, synced_at FROM $TABLE_SMS ORDER BY received_at DESC",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result.add(
                    SmsItem(
                        id = cursor.getLong(0),
                        sender = cursor.getString(1),
                        body = cursor.getString(2),
                        receivedAtIso = cursor.getString(3),
                        smsHash = cursor.getString(4),
                        isSynced = cursor.getInt(5) == 1,
                        syncedAtIso = cursor.getString(6)
                    )
                )
            }
        }
        return result
    }

    fun getPendingBatch(limit: Int = 100): List<SmsItem> {
        val result = mutableListOf<SmsItem>()
        readableDatabase.rawQuery(
            "SELECT id, sender, body, received_at, sms_hash, is_synced, synced_at FROM $TABLE_SMS WHERE is_synced = 0 ORDER BY received_at ASC LIMIT ?",
            arrayOf(limit.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result.add(
                    SmsItem(
                        id = cursor.getLong(0),
                        sender = cursor.getString(1),
                        body = cursor.getString(2),
                        receivedAtIso = cursor.getString(3),
                        smsHash = cursor.getString(4),
                        isSynced = cursor.getInt(5) == 1,
                        syncedAtIso = cursor.getString(6)
                    )
                )
            }
        }
        return result
    }

    fun markSynced(hashes: List<String>, syncedAtIso: String) {
        if (hashes.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            val values = ContentValues().apply {
                put("is_synced", 1)
                put("synced_at", syncedAtIso)
            }
            for (hash in hashes) {
                db.update(TABLE_SMS, values, "sms_hash = ?", arrayOf(hash))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // =========================================================================
    // CONTACTS QUEUE METHODS (Preserved)
    // =========================================================================

    fun insertAllContactsIgnore(items: List<ContactItem>) {
        if (items.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (item in items) {
                val values = ContentValues().apply {
                    put("name", item.name)
                    put("phone_number", item.phoneNumber)
                    put("contact_hash", item.contactHash)
                    put("is_synced", if (item.isSynced) 1 else 0)
                }
                db.insertWithOnConflict(TABLE_CONTACTS, null, values, SQLiteDatabase.CONFLICT_IGNORE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun getAllContacts(): List<ContactItem> {
        val result = mutableListOf<ContactItem>()
        readableDatabase.rawQuery(
            "SELECT id, name, phone_number, contact_hash, is_synced, synced_at FROM $TABLE_CONTACTS ORDER BY name COLLATE NOCASE ASC",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result.add(
                    ContactItem(
                        id = cursor.getLong(0),
                        name = cursor.getString(1),
                        phoneNumber = cursor.getString(2),
                        contactHash = cursor.getString(3),
                        isSynced = cursor.getInt(4) == 1,
                        syncedAtIso = cursor.getString(5)
                    )
                )
            }
        }
        return result
    }

    fun getPendingContactsBatch(limit: Int = 100): List<ContactItem> {
        val result = mutableListOf<ContactItem>()
        readableDatabase.rawQuery(
            "SELECT id, name, phone_number, contact_hash, is_synced, synced_at FROM $TABLE_CONTACTS WHERE is_synced = 0 ORDER BY id ASC LIMIT ?",
            arrayOf(limit.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result.add(
                    ContactItem(
                        id = cursor.getLong(0),
                        name = cursor.getString(1),
                        phoneNumber = cursor.getString(2),
                        contactHash = cursor.getString(3),
                        isSynced = cursor.getInt(4) == 1,
                        syncedAtIso = cursor.getString(5)
                    )
                )
            }
        }
        return result
    }

    fun markContactsSynced(hashes: List<String>, syncedAtIso: String) {
        if (hashes.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            val values = ContentValues().apply {
                put("is_synced", 1)
                put("synced_at", syncedAtIso)
            }
            for (hash in hashes) {
                db.update(TABLE_CONTACTS, values, "contact_hash = ?", arrayOf(hash))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // =========================================================================
    // SYNC ERROR LOGS & REMOTE COMMANDS & ANNOUNCEMENTS
    // =========================================================================

    fun insertSyncLog(
        syncType: String,
        triggerSource: String,
        status: String,
        itemsSynced: Int,
        errorMessage: String,
        createdAtIso: String
    ) {
        val values = ContentValues().apply {
            put("sync_type", syncType)
            put("trigger_source", triggerSource)
            put("status", status)
            put("items_synced", itemsSynced)
            put("error_message", errorMessage.take(1000))
            put("created_at", createdAtIso)
            put("is_uploaded", 0)
        }
        writableDatabase.insert(TABLE_SYNC_LOGS, null, values)
        // Keep table compact (max 300 local entries)
        writableDatabase.execSQL(
            "DELETE FROM $TABLE_SYNC_LOGS WHERE id NOT IN (SELECT id FROM $TABLE_SYNC_LOGS ORDER BY id DESC LIMIT 300)"
        )
    }

    fun getPendingUploadLogs(limit: Int = 30): List<SyncLogItem> {
        val result = mutableListOf<SyncLogItem>()
        readableDatabase.rawQuery(
            "SELECT id, sync_type, trigger_source, status, items_synced, error_message, created_at, is_uploaded FROM $TABLE_SYNC_LOGS WHERE is_uploaded = 0 ORDER BY id ASC LIMIT ?",
            arrayOf(limit.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result.add(
                    SyncLogItem(
                        id = cursor.getLong(0),
                        syncType = cursor.getString(1),
                        triggerSource = cursor.getString(2),
                        status = cursor.getString(3),
                        itemsSynced = cursor.getInt(4),
                        errorMessage = cursor.getString(5),
                        createdAtIso = cursor.getString(6),
                        isUploaded = cursor.getInt(7) == 1
                    )
                )
            }
        }
        return result
    }

    fun getRecentErrorLogs(limit: Int = 10): List<SyncLogItem> {
        val result = mutableListOf<SyncLogItem>()
        readableDatabase.rawQuery(
            "SELECT id, sync_type, trigger_source, status, items_synced, error_message, created_at, is_uploaded FROM $TABLE_SYNC_LOGS ORDER BY id DESC LIMIT ?",
            arrayOf(limit.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result.add(
                    SyncLogItem(
                        id = cursor.getLong(0),
                        syncType = cursor.getString(1),
                        triggerSource = cursor.getString(2),
                        status = cursor.getString(3),
                        itemsSynced = cursor.getInt(4),
                        errorMessage = cursor.getString(5),
                        createdAtIso = cursor.getString(6),
                        isUploaded = cursor.getInt(7) == 1
                    )
                )
            }
        }
        return result
    }

    fun markLogsUploaded(ids: List<Long>) {
        if (ids.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            val values = ContentValues().apply { put("is_uploaded", 1) }
            for (id in ids) {
                db.update(TABLE_SYNC_LOGS, values, "id = ?", arrayOf(id.toString()))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun hasExecutedCommand(commandUid: String): Boolean {
        readableDatabase.rawQuery(
            "SELECT 1 FROM $TABLE_EXECUTED_COMMANDS WHERE command_uid = ? LIMIT 1",
            arrayOf(commandUid)
        ).use { cursor ->
            return cursor.moveToFirst()
        }
    }

    fun recordExecutedCommand(
        commandUid: String,
        syncType: String,
        status: String,
        resultMessage: String,
        executedAtIso: String
    ) {
        val values = ContentValues().apply {
            put("command_uid", commandUid)
            put("sync_type", syncType)
            put("status", status)
            put("result_message", resultMessage.take(500))
            put("executed_at", executedAtIso)
            put("ack_uploaded", 0)
        }
        writableDatabase.insertWithOnConflict(
            TABLE_EXECUTED_COMMANDS,
            null,
            values,
            SQLiteDatabase.CONFLICT_IGNORE
        )
    }

    fun getPendingCommandAcks(): List<ExecutedCommandItem> {
        val result = mutableListOf<ExecutedCommandItem>()
        readableDatabase.rawQuery(
            "SELECT command_uid, sync_type, status, result_message, executed_at, ack_uploaded FROM $TABLE_EXECUTED_COMMANDS WHERE ack_uploaded = 0",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result.add(
                    ExecutedCommandItem(
                        commandUid = cursor.getString(0),
                        syncType = cursor.getString(1),
                        status = cursor.getString(2),
                        resultMessage = cursor.getString(3),
                        executedAtIso = cursor.getString(4),
                        ackUploaded = cursor.getInt(5) == 1
                    )
                )
            }
        }
        return result
    }

    fun markCommandAcksUploaded(uids: List<String>) {
        if (uids.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            val values = ContentValues().apply { put("ack_uploaded", 1) }
            for (uid in uids) {
                db.update(TABLE_EXECUTED_COMMANDS, values, "command_uid = ?", arrayOf(uid))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun upsertAnnouncementsFromServer(items: List<AnnouncementItem>): List<AnnouncementItem> {
        val newlyInserted = mutableListOf<AnnouncementItem>()
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (item in items) {
                var exists = false
                db.rawQuery(
                    "SELECT is_read FROM $TABLE_ANNOUNCEMENTS WHERE announcement_uid = ? LIMIT 1",
                    arrayOf(item.announcementUid)
                ).use { c ->
                    if (c.moveToFirst()) {
                        exists = true
                    }
                }
                if (!exists) {
                    val values = ContentValues().apply {
                        put("announcement_uid", item.announcementUid)
                        put("title", item.title)
                        put("message", item.message)
                        put("created_at", item.createdAtIso)
                        put("is_read", if (item.isRead) 1 else 0)
                        put("read_synced", if (item.isRead) 1 else 0)
                    }
                    val rowId = db.insertWithOnConflict(
                        TABLE_ANNOUNCEMENTS,
                        null,
                        values,
                        SQLiteDatabase.CONFLICT_IGNORE
                    )
                    if (rowId != -1L && !item.isRead) {
                        newlyInserted.add(item)
                    }
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return newlyInserted
    }

    fun getAllAnnouncements(): List<AnnouncementItem> {
        val result = mutableListOf<AnnouncementItem>()
        readableDatabase.rawQuery(
            "SELECT announcement_uid, title, message, created_at, is_read, read_synced FROM $TABLE_ANNOUNCEMENTS ORDER BY created_at DESC",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result.add(
                    AnnouncementItem(
                        announcementUid = cursor.getString(0),
                        title = cursor.getString(1),
                        message = cursor.getString(2),
                        createdAtIso = cursor.getString(3),
                        isRead = cursor.getInt(4) == 1,
                        readSynced = cursor.getInt(5) == 1
                    )
                )
            }
        }
        return result
    }

    fun getAnnouncementByUid(uid: String): AnnouncementItem? {
        readableDatabase.rawQuery(
            "SELECT announcement_uid, title, message, created_at, is_read, read_synced FROM $TABLE_ANNOUNCEMENTS WHERE announcement_uid = ? LIMIT 1",
            arrayOf(uid)
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                return AnnouncementItem(
                    announcementUid = cursor.getString(0),
                    title = cursor.getString(1),
                    message = cursor.getString(2),
                    createdAtIso = cursor.getString(3),
                    isRead = cursor.getInt(4) == 1,
                    readSynced = cursor.getInt(5) == 1
                )
            }
        }
        return null
    }

    fun markAnnouncementRead(uid: String) {
        val values = ContentValues().apply { put("is_read", 1) }
        writableDatabase.update(TABLE_ANNOUNCEMENTS, values, "announcement_uid = ?", arrayOf(uid))
    }

    fun getUnsyncedReadAnnouncementUids(): List<String> {
        val result = mutableListOf<String>()
        readableDatabase.rawQuery(
            "SELECT announcement_uid FROM $TABLE_ANNOUNCEMENTS WHERE is_read = 1 AND read_synced = 0",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result.add(cursor.getString(0))
            }
        }
        return result
    }

    fun markAnnouncementReadsSynced(uids: List<String>) {
        if (uids.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            val values = ContentValues().apply { put("read_synced", 1) }
            for (uid in uids) {
                db.update(TABLE_ANNOUNCEMENTS, values, "announcement_uid = ?", arrayOf(uid))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    companion object {
        private const val DATABASE_NAME = "sms_vault.db"
        private const val DATABASE_VERSION = 3
        private const val TABLE_SMS = "sms_queue"
        private const val TABLE_CONTACTS = "contacts_queue"
        private const val TABLE_SYNC_LOGS = "sync_error_logs"
        private const val TABLE_EXECUTED_COMMANDS = "executed_commands"
        private const val TABLE_ANNOUNCEMENTS = "announcements"

        @Volatile
        private var instance: SmsDatabaseHelper? = null

        fun getInstance(context: Context): SmsDatabaseHelper {
            return instance ?: synchronized(this) {
                SmsDatabaseHelper(context).also { instance = it }
            }
        }
    }
}
