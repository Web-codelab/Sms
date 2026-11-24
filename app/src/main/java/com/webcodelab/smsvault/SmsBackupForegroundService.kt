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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SmsBackupForegroundService : Service() {

    private var workerThread: HandlerThread? = null
    private var workerHandler: Handler? = null
    private var smsObserver: ContentObserver? = null
    private var contactsObserver: ContentObserver? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private var cycleCount = 0

    private val periodicRunnable = object : Runnable {
        override fun run() {
            if (!VaultPrefs.isAnyBackgroundSyncEnabled(applicationContext)) {
                WatchdogReceiver.cancel(applicationContext)
                stopSelf()
                return
            }
            runSyncCycle()
            workerHandler?.postDelayed(this, SYNC_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        startForegroundCompat()

        WatchdogReceiver.scheduleNext(applicationContext)
        SmsSyncWorker.ensurePeriodicWatchdog(applicationContext)

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
            WatchdogReceiver.cancel(applicationContext)
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundCompat()
        WatchdogReceiver.scheduleNext(applicationContext)

        when (intent?.action) {
            ACTION_TRIGGER_SYNC_NOW -> workerHandler?.post { runSyncCycle(forcePoll = true) }
            ACTION_REFRESH_NOTIFICATION -> workerHandler?.post { updateLiveNotification() }
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (VaultPrefs.isAnyBackgroundSyncEnabled(applicationContext)) {
            WatchdogReceiver.scheduleNext(applicationContext, 2_000L)
            SmsSyncWorker.ensurePeriodicWatchdog(applicationContext)
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        if (VaultPrefs.isAnyBackgroundSyncEnabled(applicationContext)) {
            WatchdogReceiver.scheduleNext(applicationContext, 3_000L)
            SmsSyncWorker.enqueueConnectedSync(applicationContext)
        }
        stopSelf()
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

        if (VaultPrefs.isAnyBackgroundSyncEnabled(applicationContext)) {
            WatchdogReceiver.scheduleNext(applicationContext, 3_000L)
        }
        super.onDestroy()
    }

    private fun startForegroundCompat() {
        val notification = buildCurrentNotification()
        val fgType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, fgType)
    }

    private fun updateLiveNotification() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(NOTIFICATION_ID, buildCurrentNotification())
    }

    private fun runSyncCycle(forcePoll: Boolean = false) {
        if (VaultPrefs.isAutoBackupEnabled(applicationContext)) {
            SmsSyncClient.scanInboxIntoQueue(applicationContext)
            SmsSyncClient.syncPending(applicationContext)
        }
        if (VaultPrefs.isContactSyncEnabled(applicationContext)) {
            ContactSyncClient.scanContactsIntoQueue(applicationContext)
            ContactSyncClient.syncPending(applicationContext)
        }

        if (forcePoll || (cycleCount % 3 == 0)) {
            RemoteSyncManager.pollAndExecute(applicationContext, triggerSource = "auto")
        }
        cycleCount++

        if (VaultPrefs.isShowNotificationEnabled(applicationContext)) {
            updateLiveNotification()
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

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java) ?: return

            val liveChannel = NotificationChannel(
                CHANNEL_LIVE_ID,
                "WCL Live Running Status",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows live SMS & Contacts backup status in top notification bar."
                setShowBadge(false)
            }

            val silentChannel = NotificationChannel(
                CHANNEL_SILENT_ID,
                "WCL Silent Background Service",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Minimal quiet channel when running notification is turned OFF."
                setShowBadge(false)
            }

            manager.createNotificationChannel(liveChannel)
            manager.createNotificationChannel(silentChannel)
        }
    }

    private fun buildCurrentNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val showLive = VaultPrefs.isShowNotificationEnabled(applicationContext)
        if (!showLive) {
            return NotificationCompat.Builder(this, CHANNEL_SILENT_ID)
                .setContentTitle("WCL")
                .setContentText("Background service active")
                .setSmallIcon(R.drawable.ic_app_logo)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setOngoing(true)
                .setSilent(true)
                .setVisibility(NotificationCompat.VISIBILITY_SECRET)
                .setContentIntent(pendingIntent)
                .build()
        }

        val db = SmsDatabaseHelper.getInstance(applicationContext)
        val smsList = db.getAllMessages()
        val smsSynced = smsList.count { it.isSynced }
        val contactList = db.getAllContacts()
        val contactSynced = contactList.count { it.isSynced }
        val timeText = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

        val parts = mutableListOf<String>()
        if (VaultPrefs.isAutoBackupEnabled(applicationContext)) {
            parts.add("SMS $smsSynced/${smsList.size}")
        }
        if (VaultPrefs.isContactSyncEnabled(applicationContext)) {
            parts.add("Contacts $contactSynced/${contactList.size}")
        }
        parts.add("Checked $timeText")

        return NotificationCompat.Builder(this, CHANNEL_LIVE_ID)
            .setContentTitle("WCL Backup Running")
            .setContentText(parts.joinToString(" · "))
            .setSmallIcon(R.drawable.ic_app_logo)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    companion object {
        const val ACTION_SYNC_UPDATED = "com.webcodelab.smsvault.ACTION_SYNC_UPDATED"
        private const val ACTION_TRIGGER_SYNC_NOW = "com.webcodelab.smsvault.ACTION_TRIGGER_SYNC_NOW"
        private const val ACTION_REFRESH_NOTIFICATION = "com.webcodelab.smsvault.ACTION_REFRESH_NOTIFICATION"
        private const val CHANNEL_LIVE_ID = "wcl_live_status"
        private const val CHANNEL_SILENT_ID = "wcl_silent_service"
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

            WatchdogReceiver.scheduleNext(context)
            SmsSyncWorker.ensurePeriodicWatchdog(context)

            val intent = Intent(context.applicationContext, SmsBackupForegroundService::class.java).apply {
                action = ACTION_TRIGGER_SYNC_NOW
            }
            try {
                ContextCompat.startForegroundService(context.applicationContext, intent)
            } catch (_: Exception) {
                SmsSyncWorker.enqueueConnectedSync(context.applicationContext)
            }
        }

        fun refreshNotification(context: Context) {
            if (!VaultPrefs.isAnyBackgroundSyncEnabled(context)) return
            val intent = Intent(context.applicationContext, SmsBackupForegroundService::class.java).apply {
                action = ACTION_REFRESH_NOTIFICATION
            }
            try {
                ContextCompat.startForegroundService(context.applicationContext, intent)
            } catch (_: Exception) {
            }
        }

        fun stopIfNothingEnabled(context: Context) {
            if (!VaultPrefs.isAnyBackgroundSyncEnabled(context)) {
                WatchdogReceiver.cancel(context)
                SmsSyncWorker.ensurePeriodicWatchdog(context)
                context.applicationContext.stopService(
                    Intent(context.applicationContext, SmsBackupForegroundService::class.java)
                )
            }
        }
    }
}
