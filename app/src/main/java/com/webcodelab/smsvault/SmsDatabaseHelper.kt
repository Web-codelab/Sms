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

object SmsHashUtil {
    fun sha256(sender: String, body: String, receivedAtIso: String): String {
        val raw = "${sender.trim()}|${body.trim()}|${receivedAtIso.trim()}"
        val bytes = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}

object VaultPrefs {
    private const val PREF_NAME = "vault_prefs"
    private const val KEY_AUTO_BACKUP = "auto_backup_all_time"
    private const val KEY_API_KEY = "api_key"
    const val ENDPOINT_URL = "https://webcodelab.in/WCL/backup_sms.php"

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
}

class SmsDatabaseHelper(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DATABASE_NAME, null, DATABASE_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
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
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Preserve user offline queue across schema updates
    }

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

    companion object {
        private const val DATABASE_NAME = "sms_vault.db"
        private const val DATABASE_VERSION = 1
        private const val TABLE_SMS = "sms_queue"

        @Volatile
        private var instance: SmsDatabaseHelper? = null

        fun getInstance(context: Context): SmsDatabaseHelper {
            return instance ?: synchronized(this) {
                SmsDatabaseHelper(context).also { instance = it }
            }
        }
    }
}
