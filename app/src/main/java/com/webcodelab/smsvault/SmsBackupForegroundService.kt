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
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

class SmsBackupForegroundService : Service() {

    private var workerThread: HandlerThread? = null
    private var workerHandler: Handler? = null
    private var smsObserver: ContentObserver? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private val periodicRunnable = object : Runnable {
        override fun run() {
            if (!VaultPrefs.isAutoBackupEnabled(applicationContext)) {
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

        val thread = HandlerThread("WclSmsBgSync").apply { start() }
        workerThread = thread
        val handler = Handler(thread.looper)
        workerHandler = handler

        registerSmsContentObserver(handler)
        registerNetworkCallback()

        handler.post(periodicRunnable)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!VaultPrefs.isAutoBackupEnabled(applicationContext)) {
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
        val notification = buildNotification("Auto-backup active (5s live sync)")
        val fgType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, fgType)
    }

    private fun runSyncCycle() {
        SmsSyncClient.scanInboxIntoQueue(applicationContext)
        SmsSyncClient.syncPending(applicationContext)
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
                handler.post { runSyncCycle() }
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
                "WCL Background SMS Sync",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps WCL SMS backup active in the background."
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
            if (!VaultPrefs.isAutoBackupEnabled(context)) return
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }

            val intent = Intent(context.applicationContext, SmsBackupForegroundService::class.java).apply {
                action = ACTION_TRIGGER_SYNC_NOW
            }
            try {
                ContextCompat.startForegroundService(context.applicationContext, intent)
            } catch (_: Exception) {
                // Fallback to WorkManager if OS restricts foreground launch from background
                SmsSyncWorker.enqueueConnectedSync(context.applicationContext)
            }
        }

        fun stop(context: Context) {
            context.applicationContext.stopService(
                Intent(context.applicationContext, SmsBackupForegroundService::class.java)
            )
        }
    }
}
