package com.webcodelab.smsvault

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.provider.ContactsContract
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

class SmsBackupForegroundService : Service() {

    private var workerThread: HandlerThread? = null
    private var workerHandler: Handler? = null
    private var smsObserver: ContentObserver? = null
    private var contactsObserver: ContentObserver? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private val periodicRunnable = object : Runnable {
        override fun run() {
            if (!VaultPrefs.isAnyBackgroundSyncEnabled(applicationContext)) {
                stopSelf()
                return
            }
            runSyncCycle()
            workerHandler?.postDelayed(this, SYNC_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForegroundCompat()

        val thread = HandlerThread("WclBgSyncThread").apply { start() }
        workerThread = thread
        val handler = Handler(thread.looper)
        workerHandler = handler

        registerSmsContentObserver(handler)
        registerContactsContentObserver(handler)
        registerNetworkCallback()

        handler.post(periodicRunnable)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!VaultPrefs.isAnyBackgroundSyncEnabled(applicationContext)) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundCompat()

        if (intent?.action == ACTION_TRIGGER_SYNC_NOW) {
            workerHandler?.post { runSyncCycle() }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        workerHandler?.removeCallbacksAndMessages(null)
        smsObserver?.let {
            try {
                contentResolver.unregisterContentObserver(it)
            } catch (_: Exception) {
            }
        }
        contactsObserver?.let {
            try {
                contentResolver.unregisterContentObserver(it)
            } catch (_: Exception) {
            }
        }
        networkCallback?.let {
            try {
                val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                cm?.unregisterNetworkCallback(it)
            } catch (_: Exception) {
            }
        }
        workerThread?.quitSafely()
        super.onDestroy()
    }

    private fun startForegroundCompat() {
        val smsOn = VaultPrefs.isAutoBackupEnabled(applicationContext)
        val contactsOn = VaultPrefs.isContactSyncEnabled(applicationContext)
        val label = when {
            smsOn && contactsOn -> "SMS + Contacts auto-sync active (5s)"
            contactsOn -> "Contacts auto-sync active (5s)"
            else -> "SMS auto-backup active (5s)"
        }
        val notification = buildNotification(label)
        val fgType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, fgType)
    }

    private fun runSyncCycle() {
        if (VaultPrefs.isAutoBackupEnabled(applicationContext)) {
            SmsSyncClient.scanInboxIntoQueue(applicationContext)
            SmsSyncClient.syncPending(applicationContext)
        }
        if (VaultPrefs.isContactSyncEnabled(applicationContext)) {
            ContactSyncClient.scanContactsIntoQueue(applicationContext)
            ContactSyncClient.syncPending(applicationContext)
        }
        sendBroadcast(
            Intent(ACTION_SYNC_UPDATED).setPackage(packageName)
        )
    }

    private fun registerSmsContentObserver(handler: Handler) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                super.onChange(selfChange)
                if (VaultPrefs.isAutoBackupEnabled(applicationContext)) {
                    handler.post { runSyncCycle() }
                }
            }
        }
        smsObserver = observer
        try {
            contentResolver.registerContentObserver(
                Uri.parse("content://sms"),
                true,
                observer
            )
        } catch (_: Exception) {
        }
    }

    private fun registerContactsContentObserver(handler: Handler) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                super.onChange(selfChange)
                if (VaultPrefs.isContactSyncEnabled(applicationContext)) {
                    handler.post { runSyncCycle() }
                }
            }
        }
        contactsObserver = observer
        try {
            contentResolver.registerContentObserver(
                ContactsContract.Contacts.CONTENT_URI,
                true,
                observer
            )
        } catch (_: Exception) {
        }
    }

    private fun registerNetworkCallback() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                workerHandler?.post { runSyncCycle() }
            }
        }
        networkCallback = callback
        try {
            cm.registerNetworkCallback(request, callback)
        } catch (_: Exception) {
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "WCL Background Sync",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps WCL SMS and Contacts backup active in the background."
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("WCL Always-On Backup")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_app_logo)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    companion object {
        const val ACTION_SYNC_UPDATED = "com.webcodelab.smsvault.ACTION_SYNC_UPDATED"
        private const val ACTION_TRIGGER_SYNC_NOW = "com.webcodelab.smsvault.ACTION_TRIGGER_SYNC_NOW"
        private const val CHANNEL_ID = "wcl_backup_service"
        private const val NOTIFICATION_ID = 7001
        private const val SYNC_INTERVAL_MS = 5_000L

        fun startOrWake(context: Context) {
            if (!VaultPrefs.isAnyBackgroundSyncEnabled(context)) return

            val smsGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) ==
                PackageManager.PERMISSION_GRANTED
            val contactsGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
                PackageManager.PERMISSION_GRANTED

            val canRunSms = VaultPrefs.isAutoBackupEnabled(context) && smsGranted
            val canRunContacts = VaultPrefs.isContactSyncEnabled(context) && contactsGranted
            if (!canRunSms && !canRunContacts) return

            val intent = Intent(context.applicationContext, SmsBackupForegroundService::class.java).apply {
                action = ACTION_TRIGGER_SYNC_NOW
            }
            try {
                ContextCompat.startForegroundService(context.applicationContext, intent)
            } catch (_: Exception) {
                if (canRunSms) {
                    SmsSyncWorker.enqueueConnectedSync(context.applicationContext)
                }
            }
        }

        fun stopIfNothingEnabled(context: Context) {
            if (!VaultPrefs.isAnyBackgroundSyncEnabled(context)) {
                context.applicationContext.stopService(
                    Intent(context.applicationContext, SmsBackupForegroundService::class.java)
                )
            }
        }
    }
}
