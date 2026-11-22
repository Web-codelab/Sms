package com.webcodelab.smsvault

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class MainActivity : AppCompatActivity() {

    private lateinit var tvSyncSummary: TextView
    private lateinit var etEndpointUrl: EditText
    private lateinit var etApiKey: EditText
    private lateinit var switchAutoBackup: SwitchCompat
    private lateinit var adapter: SmsAdapter

    private val smsPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val readOk = grants[Manifest.permission.READ_SMS] == true
        val recvOk = grants[Manifest.permission.RECEIVE_SMS] == true
        if (readOk && recvOk) {
            importDeviceInbox(triggerSync = VaultPrefs.isAutoBackupEnabled(this))
        } else {
            Toast.makeText(
                this,
                "SMS permission is required to read & backup messages.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvSyncSummary = findViewById(R.id.tvSyncSummary)
        etEndpointUrl = findViewById(R.id.etEndpointUrl)
        etApiKey = findViewById(R.id.etApiKey)
        switchAutoBackup = findViewById(R.id.switchAutoBackupAllTime)

        val btnSaveConfig: Button = findViewById(R.id.btnSaveConfig)
        val btnBackupAllNow: Button = findViewById(R.id.btnBackupAllNow)
        val btnRefreshInbox: Button = findViewById(R.id.btnRefreshInbox)
        val recyclerSms: RecyclerView = findViewById(R.id.recyclerSms)

        etEndpointUrl.setText(VaultPrefs.getEndpointUrl(this))
        etApiKey.setText(VaultPrefs.getApiKey(this))
        switchAutoBackup.isChecked = VaultPrefs.isAutoBackupEnabled(this)

        adapter = SmsAdapter { smsItem ->
            if (!smsItem.isSynced) {
                SmsSyncWorker.enqueueConnectedSync(this)
                Toast.makeText(this, "Queued SMS from ${smsItem.sender} for backup.", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Already backed up on server.", Toast.LENGTH_SHORT).show()
            }
        }
        recyclerSms.layoutManager = LinearLayoutManager(this)
        recyclerSms.adapter = adapter

        switchAutoBackup.setOnCheckedChangeListener { _, isChecked ->
            VaultPrefs.setAutoBackupEnabled(this, isChecked)
            if (isChecked) {
                SmsSyncWorker.enqueueConnectedSync(this)
            }
        }

        btnSaveConfig.setOnClickListener {
            VaultPrefs.saveServerConfig(
                this,
                etEndpointUrl.text.toString(),
                etApiKey.text.toString()
            )
            Toast.makeText(this, "Server configuration saved.", Toast.LENGTH_SHORT).show()
        }

        btnBackupAllNow.setOnClickListener {
            VaultPrefs.saveServerConfig(
                this,
                etEndpointUrl.text.toString(),
                etApiKey.text.toString()
            )
            importDeviceInbox(triggerSync = true)
        }

        btnRefreshInbox.setOnClickListener {
            refreshUiFromDb()
        }

        ensureSmsPermissions()
    }

    override fun onResume() {
        super.onResume()
        refreshUiFromDb()
    }

    private fun ensureSmsPermissions() {
        val readOk = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
        val recvOk = ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED
        if (readOk && recvOk) {
            importDeviceInbox(triggerSync = VaultPrefs.isAutoBackupEnabled(this))
        } else {
            smsPermissionLauncher.launch(
                arrayOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS)
            )
        }
    }

    private fun importDeviceInbox(triggerSync: Boolean) {
        Thread {
            val list = mutableListOf<SmsItem>()
            val isoFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }

            try {
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
                            SmsItem(
                                id = 0,
                                sender = sender,
                                body = body,
                                receivedAtIso = receivedIso,
                                smsHash = hash,
                                isSynced = false,
                                syncedAtIso = null
                            )
                        )
                    }
                }
            } catch (_: SecurityException) {
                // Permission denied
            }

            val db = SmsDatabaseHelper.getInstance(applicationContext)
            if (list.isNotEmpty()) {
                db.insertAllIgnore(list)
            }

            if (triggerSync) {
                SmsSyncWorker.enqueueConnectedSync(applicationContext)
            }

            runOnUiThread {
                refreshUiFromDb()
                if (triggerSync) {
                    Toast.makeText(
                        this,
                        "Sync scheduled (${list.size} inbox messages checked).",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }.start()
    }

    private fun refreshUiFromDb() {
        Thread {
            val all = SmsDatabaseHelper.getInstance(applicationContext).getAllMessages()
            val synced = all.count { it.isSynced }
            val pending = all.size - synced
            runOnUiThread {
                adapter.submitList(all)
                tvSyncSummary.text = "${all.size} SMS total · $synced synced · $pending pending"
            }
        }.start()
    }
}
