package com.webcodelab.smsvault

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) return

        val grouped = linkedMapOf<String, Pair<Long, StringBuilder>>()
        for (sms in messages) {
            val sender = sms.displayOriginatingAddress ?: "Unknown"
            val entry = grouped.getOrPut(sender) { sms.timestampMillis to StringBuilder() }
            entry.second.append(sms.messageBody ?: "")
        }

        val pendingResult = goAsync()
        val appContext = context.applicationContext

        Thread {
            try {
                val db = SmsDatabaseHelper.getInstance(appContext)
                val isoFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }

                for ((sender, data) in grouped) {
                    val (timestampMillis, bodyBuilder) = data
                    val body = bodyBuilder.toString()
                    val receivedIso = isoFmt.format(Date(timestampMillis))
                    val hash = SmsHashUtil.sha256(sender, body, receivedIso)
                    db.insertIgnore(sender, body, receivedIso, hash)
                }

                if (VaultPrefs.isAutoBackupEnabled(appContext)) {
                    SmsSyncWorker.enqueueConnectedSync(appContext)
                }
            } finally {
                pendingResult.finish()
            }
        }.start()
    }
}
