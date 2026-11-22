import type { CodeFileSpec, EndpointConfig } from '../types/sms';

function normalizeBaseUrl(raw: string): string {
  const trimmed = raw.trim().replace(/\/+$/, '');
  return trimmed || 'https://Webcodelab.in/WCL';
}

export function getPhpCodeFiles(config: EndpointConfig): CodeFileSpec[] {
  const baseUrl = normalizeBaseUrl(config.baseUrl);
  const apiKeyPlaceholder = config.apiKey.trim() || 'REPLACE_WITH_STRONG_RANDOM_API_KEY';

  return [
    {
      id: 'schema-sql',
      filename: 'schema.sql',
      language: 'sql',
      summary: 'Run once in cPanel phpMyAdmin to create the sms_backups table with duplicate protection.',
      content: `-- Run in cPanel -> phpMyAdmin inside your MySQL database
CREATE TABLE IF NOT EXISTS sms_backups (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  device_id VARCHAR(100) NOT NULL,
  sender VARCHAR(64) NOT NULL,
  message_body TEXT NOT NULL,
  received_at DATETIME NOT NULL,
  synced_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  sms_hash CHAR(64) NOT NULL,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uniq_device_hash (device_id, sms_hash),
  INDEX idx_device_received (device_id, received_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
`,
    },
    {
      id: 'db-php',
      filename: 'db.php',
      language: 'php',
      summary: `Upload to ${baseUrl}/db.php — Holds MySQL PDO connection and API key validation.`,
      content: `<?php
// Upload to: ${baseUrl}/db.php
// Keep file permissions at 640 or 600 on cPanel so credentials cannot be read externally.

declare(strict_types=1);

header('Content-Type: application/json; charset=utf-8');

// 1. cPanel MySQL Database Credentials (replace with your cPanel values)
define('DB_HOST', 'localhost');
define('DB_NAME', 'your_cpanel_dbname');
define('DB_USER', 'your_cpanel_dbuser');
define('DB_PASS', 'YOUR_STRONG_MYSQL_PASSWORD');

// 2. Shared App API Key (must match Android BuildConfig / app configuration)
define('SMS_SYNC_API_KEY', '${apiKeyPlaceholder}');

function getDbConnection(): PDO {
    static $pdo = null;
    if ($pdo instanceof PDO) {
        return $pdo;
    }

    $dsn = 'mysql:host=' . DB_HOST . ';dbname=' . DB_NAME . ';charset=utf8mb4';
    $options = [
        PDO::ATTR_ERRMODE            => PDO::ERRMODE_EXCEPTION,
        PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC,
        PDO::ATTR_EMULATE_PREPARES   => false,
    ];

    $pdo = new PDO($dsn, DB_USER, DB_PASS, $options);
    return $pdo;
}

function verifyApiKeyOrExit(): void {
    $providedKey = $_SERVER['HTTP_X_API_KEY'] ?? '';
    if ($providedKey === '' || !hash_equals(SMS_SYNC_API_KEY, $providedKey)) {
        http_response_code(401);
        echo json_encode([
            'status' => 'error',
            'message' => 'Unauthorized: invalid or missing X-API-Key header.'
        ]);
        exit;
    }
}
`,
    },
    {
      id: 'backup-sms-php',
      filename: 'backup_sms.php',
      language: 'php',
      summary: `Upload to ${baseUrl}/backup_sms.php — Receives offline/online SMS batches and inserts safely.`,
      content: `<?php
// Upload to: ${baseUrl}/backup_sms.php
// Accepts POST JSON: { "device_id": "...", "messages": [ { "sender": "...", "body": "...", "received_at": "...", "sms_hash": "..." } ] }

declare(strict_types=1);

require_once __DIR__ . '/db.php';

if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
    http_response_code(405);
    echo json_encode(['status' => 'error', 'message' => 'Only POST requests are allowed.']);
    exit;
}

verifyApiKeyOrExit();

$rawBody = file_get_contents('php://input');
$payload = json_decode($rawBody ?: '', true);

if (!is_array($payload)) {
    http_response_code(400);
    echo json_encode(['status' => 'error', 'message' => 'Invalid JSON payload.']);
    exit;
}

$deviceId = trim((string)($payload['device_id'] ?? ''));
$messages = $payload['messages'] ?? null;

if ($deviceId === '' || strlen($deviceId) > 100 || !is_array($messages) || count($messages) === 0) {
    http_response_code(400);
    echo json_encode(['status' => 'error', 'message' => 'Valid device_id and non-empty messages array required.']);
    exit;
}

if (count($messages) > 200) {
    http_response_code(400);
    echo json_encode(['status' => 'error', 'message' => 'Maximum 200 messages per batch.']);
    exit;
}

try {
    $pdo = getDbConnection();
    $pdo->beginTransaction();

    // INSERT IGNORE prevents duplicate inserts when offline retries re-send a batch
    $stmt = $pdo->prepare(
        'INSERT IGNORE INTO sms_backups (device_id, sender, message_body, received_at, synced_at, sms_hash)
         VALUES (:device_id, :sender, :message_body, :received_at, NOW(), :sms_hash)'
    );

    $inserted = 0;
    $skipped = 0;
    $syncedHashes = [];

    foreach ($messages as $item) {
        if (!is_array($item)) {
            continue;
        }
        $sender = mb_substr(trim((string)($item['sender'] ?? '')), 0, 64);
        $body = mb_substr((string)($item['body'] ?? ''), 0, 4000);
        $receivedRaw = trim((string)($item['received_at'] ?? ''));
        $smsHash = trim((string)($item['sms_hash'] ?? ''));

        if ($sender === '' || $body === '' || strlen($smsHash) !== 64) {
            continue;
        }

        $timestamp = strtotime($receivedRaw);
        $receivedSql = $timestamp !== false ? date('Y-m-d H:i:s', $timestamp) : date('Y-m-d H:i:s');

        $stmt->execute([
            ':device_id'    => $deviceId,
            ':sender'       => $sender,
            ':message_body' => $body,
            ':received_at'  => $receivedSql,
            ':sms_hash'     => $smsHash,
        ]);

        if ($stmt->rowCount() > 0) {
            $inserted++;
        } else {
            $skipped++;
        }
        $syncedHashes[] = $smsHash;
    }

    $pdo->commit();

    echo json_encode([
        'status'             => 'success',
        'inserted'           => $inserted,
        'skipped_duplicates' => $skipped,
        'synced_hashes'      => $syncedHashes,
        'server_time'        => gmdate('c'),
    ]);
} catch (Throwable $e) {
    if (isset($pdo) && $pdo->inTransaction()) {
        $pdo->rollBack();
    }
    error_log('SMS backup error: ' . $e->getMessage());
    http_response_code(500);
    echo json_encode(['status' => 'error', 'message' => 'Internal server error while saving SMS.']);
}
`,
    },
    {
      id: 'list-sms-php',
      filename: 'list_sms.php',
      language: 'php',
      summary: `Upload to ${baseUrl}/list_sms.php — Authenticated GET endpoint to fetch backed-up SMS from MySQL.`,
      content: `<?php
// Upload to: ${baseUrl}/list_sms.php

declare(strict_types=1);

require_once __DIR__ . '/db.php';

if ($_SERVER['REQUEST_METHOD'] !== 'GET') {
    http_response_code(405);
    echo json_encode(['status' => 'error', 'message' => 'Only GET requests are allowed.']);
    exit;
}

verifyApiKeyOrExit();

$deviceId = trim((string)($_GET['device_id'] ?? ''));
$limit = max(1, min(200, (int)($_GET['limit'] ?? 100)));

try {
    $pdo = getDbConnection();

    if ($deviceId !== '') {
        $stmt = $pdo->prepare(
            'SELECT id, device_id, sender, message_body, received_at, synced_at, sms_hash
             FROM sms_backups
             WHERE device_id = :device_id
             ORDER BY received_at DESC
             LIMIT :limit_rows'
        );
        $stmt->bindValue(':device_id', $deviceId, PDO::PARAM_STR);
        $stmt->bindValue(':limit_rows', $limit, PDO::PARAM_INT);
        $stmt->execute();
    } else {
        $stmt = $pdo->prepare(
            'SELECT id, device_id, sender, message_body, received_at, synced_at, sms_hash
             FROM sms_backups
             ORDER BY received_at DESC
             LIMIT :limit_rows'
        );
        $stmt->bindValue(':limit_rows', $limit, PDO::PARAM_INT);
        $stmt->execute();
    }

    echo json_encode([
        'status' => 'success',
        'records' => $stmt->fetchAll(),
    ]);
} catch (Throwable $e) {
    error_log('SMS list error: ' . $e->getMessage());
    http_response_code(500);
    echo json_encode(['status' => 'error', 'message' => 'Failed to read backed up SMS records.']);
}
`,
    },
  ];
}

export function getAndroidCodeFiles(config: EndpointConfig): CodeFileSpec[] {
  const baseUrl = normalizeBaseUrl(config.baseUrl);
  const backupEndpoint = `${baseUrl}/backup_sms.php`;

  return [
    {
      id: 'android-manifest',
      filename: 'AndroidManifest.xml',
      language: 'xml',
      summary: 'Declares SMS read/receive permissions and registers background SmsReceiver.',
      content: `<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-feature android:name="android.hardware.telephony" android:required="false" />

    <uses-permission android:name="android.permission.RECEIVE_SMS" />
    <uses-permission android:name="android.permission.READ_SMS" />
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />

    <application
        android:allowBackup="false"
        android:label="SMS Vault Sync"
        android:usesCleartextTraffic="false"
        android:theme="@style/Theme.Material3.DayNight.NoActionBar">

        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <!-- Fires automatically whenever a new SMS arrives on the phone -->
        <receiver
            android:name=".SmsReceiver"
            android:enabled="true"
            android:exported="true"
            android:permission="android.permission.BROADCAST_SMS">
            <intent-filter android:priority="999">
                <action android:name="android.provider.Telephony.SMS_RECEIVED" />
            </intent-filter>
        </receiver>

    </application>
</manifest>
`,
    },
    {
      id: 'build-gradle',
      filename: 'app/build.gradle.kts',
      language: 'kotlin',
      summary: 'Gradle dependencies for Room offline queue, WorkManager online auto-retry, and OkHttp.',
      content: `plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.webcodelab.smsvault"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.webcodelab.smsvault"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        // Inject server URL & API key securely from local.properties
        buildConfigField("String", "BACKUP_ENDPOINT", "\\"${backupEndpoint}\\"")
        buildConfigField("String", "SYNC_API_KEY", "\\"\${project.findProperty("SMS_SYNC_API_KEY") ?: ""}\\"")
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // WorkManager: guarantees offline -> online automatic backup
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    // Room SQLite: persistent offline queue when phone has no internet
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // OkHttp & Gson for PHP JSON API calls
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.11.0")
}
`,
    },
    {
      id: 'sms-local-store',
      filename: 'SmsLocalStore.kt',
      language: 'kotlin',
      summary: 'Room SQLite offline queue & SHA-256 hash utility so no SMS is ever lost offline.',
      content: `package com.webcodelab.smsvault

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import java.security.MessageDigest

@Entity(tableName = "sms_queue", indices = [Index(value = ["smsHash"], unique = true)])
data class SmsQueueEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sender: String,
    val body: String,
    val receivedAtIso: String,
    val smsHash: String,
    val isSynced: Boolean = false,
    val syncedAtIso: String? = null
)

@Dao
interface SmsQueueDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(item: SmsQueueEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAllIgnore(items: List<SmsQueueEntity>)

    @Query("SELECT * FROM sms_queue ORDER BY receivedAtIso DESC")
    fun observeAll(): Flow<List<SmsQueueEntity>>

    @Query("SELECT * FROM sms_queue WHERE isSynced = 0 ORDER BY receivedAtIso ASC LIMIT 100")
    suspend fun getPendingBatch(): List<SmsQueueEntity>

    @Query("UPDATE sms_queue SET isSynced = 1, syncedAtIso = :syncedAt WHERE smsHash IN (:hashes)")
    suspend fun markSynced(hashes: List<String>, syncedAt: String)
}

@Database(entities = [SmsQueueEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun smsDao(): SmsQueueDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "sms_vault.db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}

object SmsHashUtil {
    fun sha256(sender: String, body: String, receivedAtIso: String): String {
        val raw = "\${sender.trim()}|\${body.trim()}|\${receivedAtIso.trim()}"
        val bytes = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
`,
    },
    {
      id: 'sms-receiver',
      filename: 'SmsReceiver.kt',
      language: 'kotlin',
      summary: 'Captures incoming SMS immediately; syncs now if online or auto-queues until internet returns.',
      content: `package com.webcodelab.smsvault

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) return

        // Group multipart SMS from the same sender
        val grouped = linkedMapOf<String, Pair<Long, StringBuilder>>()
        for (sms in messages) {
            val sender = sms.displayOriginatingAddress ?: "Unknown"
            val entry = grouped.getOrPut(sender) { sms.timestampMillis to StringBuilder() }
            entry.second.append(sms.messageBody ?: "")
        }

        val pendingResult = goAsync()
        val appContext = context.applicationContext

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dao = AppDatabase.getInstance(appContext).smsDao()
                val isoFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }

                for ((sender, data) in grouped) {
                    val (timestampMillis, bodyBuilder) = data
                    val body = bodyBuilder.toString()
                    val receivedIso = isoFmt.format(Date(timestampMillis))
                    val hash = SmsHashUtil.sha256(sender, body, receivedIso)

                    dao.insertIgnore(
                        SmsQueueEntity(
                            sender = sender,
                            body = body,
                            receivedAtIso = receivedIso,
                            smsHash = hash,
                            isSynced = false
                        )
                    )
                }

                // If "Auto-Backup All Time" switch is ON, schedule immediate/online sync
                val prefs = appContext.getSharedPreferences("vault_prefs", Context.MODE_PRIVATE)
                if (prefs.getBoolean("auto_backup_all_time", true)) {
                    SmsSyncWorker.enqueueConnectedSync(appContext)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
`,
    },
    {
      id: 'sms-sync-worker',
      filename: 'SmsSyncWorker.kt',
      language: 'kotlin',
      summary: 'WorkManager worker with NetworkType.CONNECTED: runs instantly online, or automatically when phone reconnects.',
      content: `package com.webcodelab.smsvault

import android.content.Context
import android.provider.Settings
import androidx.work.*
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
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    override suspend fun doWork(): Result {
        val dao = AppDatabase.getInstance(applicationContext).smsDao()
        val deviceId = Settings.Secure.getString(
            applicationContext.contentResolver,
            Settings.Secure.ANDROID_ID
        ) ?: "android-device"

        while (true) {
            val batch = dao.getPendingBatch()
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

            val request = Request.Builder()
                .url(BuildConfig.BACKUP_ENDPOINT)
                .addHeader("X-API-Key", BuildConfig.SYNC_API_KEY)
                .post(requestBody)
                .build()

            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return Result.retry()
                    }
                    val nowIso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
                        .apply { timeZone = TimeZone.getTimeZone("UTC") }
                        .format(Date())
                    dao.markSynced(batch.map { it.smsHash }, nowIso)
                }
            } catch (e: Exception) {
                // Offline or timeout: WorkManager automatically retries once internet is back
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

            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }
}
`,
    },
    {
      id: 'main-activity',
      filename: 'MainActivity.kt',
      language: 'kotlin',
      summary: 'Displays all phone SMS, provides "Backup All Time" switch + "Backup All Now" button, and imports inbox.',
      content: `package com.webcodelab.smsvault

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.Switch
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class MainActivity : AppCompatActivity() {

    private val smsPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val granted = grants[Manifest.permission.READ_SMS] == true &&
                      grants[Manifest.permission.RECEIVE_SMS] == true
        if (granted) {
            importDeviceInboxToQueue()
        } else {
            Toast.makeText(this, "SMS permission is required to view & backup messages.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val prefs = getSharedPreferences("vault_prefs", Context.MODE_PRIVATE)
        val autoSwitch = findViewById<Switch>(R.id.switchAutoBackupAllTime)
        val backupNowBtn = findViewById<Button>(R.id.btnBackupAllNow)

        autoSwitch.isChecked = prefs.getBoolean("auto_backup_all_time", true)
        autoSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("auto_backup_all_time", isChecked).apply()
            if (isChecked) {
                SmsSyncWorker.enqueueConnectedSync(this)
            }
        }

        backupNowBtn.setOnClickListener {
            importDeviceInboxToQueue(forceSyncNow = true)
        }

        ensureSmsPermissions()
    }

    private fun ensureSmsPermissions() {
        val readOk = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
        val recvOk = ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED
        if (readOk && recvOk) {
            importDeviceInboxToQueue()
        } else {
            smsPermissionLauncher.launch(arrayOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS))
        }
    }

    private fun importDeviceInboxToQueue(forceSyncNow: Boolean = false) {
        lifecycleScope.launch {
            val entities = withContext(Dispatchers.IO) {
                val list = mutableListOf<SmsQueueEntity>()
                val isoFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
                contentResolver.query(
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
                            SmsQueueEntity(
                                sender = sender,
                                body = body,
                                receivedAtIso = receivedIso,
                                smsHash = hash,
                                isSynced = false
                            )
                        )
                    }
                }
                AppDatabase.getInstance(applicationContext).smsDao().insertAllIgnore(list)
                list.size
            }

            val prefs = getSharedPreferences("vault_prefs", Context.MODE_PRIVATE)
            if (forceSyncNow || prefs.getBoolean("auto_backup_all_time", true)) {
                SmsSyncWorker.enqueueConnectedSync(this@MainActivity)
                Toast.makeText(this@MainActivity, "Queued $entities SMS for MySQL backup.", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
`,
    },
  ];
}
