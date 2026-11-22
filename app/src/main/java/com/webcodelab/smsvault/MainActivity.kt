package com.webcodelab.smsvault

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.CountDownTimer
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.navigation.NavigationView
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class MainActivity : AppCompatActivity() {

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var toolbar: MaterialToolbar
    private lateinit var navigationView: NavigationView

    // Screen containers
    private lateinit var layoutCountdown: View
    private lateinit var layoutSmsManager: View

    // Countdown views
    private lateinit var countdownProgress: CircularProgressIndicator
    private lateinit var tvCountdownTimer: TextView
    private lateinit var tvCountdownState: TextView
    private lateinit var tvCountdownSubtitle: TextView
    private lateinit var btnPauseResumeCountdown: MaterialButton
    private lateinit var tvCardSyncedCount: TextView
    private lateinit var tvCardPendingCount: TextView

    // SMS Manager views
    private lateinit var tvSyncSummary: TextView
    private lateinit var etApiKey: TextInputEditText
    private lateinit var switchAutoBackup: MaterialSwitch
    private lateinit var adapter: SmsAdapter

    private var countDownTimer: CountDownTimer? = null
    private var totalDurationMillis: Long = 15_000L
    private var remainingMillis: Long = 15_000L
    private var isCountdownPaused: Boolean = false

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

        bindViews()
        setupDrawerNavigation()
        setupCountdownScreen()
        setupSmsManagerScreen()

        // Default landing screen: Countdown View
        showCountdownScreen()
        ensureSmsPermissions()
    }

    private fun bindViews() {
        drawerLayout = findViewById(R.id.drawerLayout)
        toolbar = findViewById(R.id.toolbar)
        navigationView = findViewById(R.id.navigationView)

        layoutCountdown = findViewById(R.id.layoutCountdown)
        layoutSmsManager = findViewById(R.id.layoutSmsManager)

        countdownProgress = findViewById(R.id.countdownProgress)
        tvCountdownTimer = findViewById(R.id.tvCountdownTimer)
        tvCountdownState = findViewById(R.id.tvCountdownState)
        tvCountdownSubtitle = findViewById(R.id.tvCountdownSubtitle)
        btnPauseResumeCountdown = findViewById(R.id.btnPauseResumeCountdown)
        tvCardSyncedCount = findViewById(R.id.tvCardSyncedCount)
        tvCardPendingCount = findViewById(R.id.tvCardPendingCount)

        tvSyncSummary = findViewById(R.id.tvSyncSummary)
        etApiKey = findViewById(R.id.etApiKey)
        switchAutoBackup = findViewById(R.id.switchAutoBackupAllTime)
    }

    private fun setupDrawerNavigation() {
        setSupportActionBar(toolbar)
        val toggle = ActionBarDrawerToggle(
            this,
            drawerLayout,
            toolbar,
            R.string.nav_open,
            R.string.nav_close
        )
        drawerLayout.addDrawerListener(toggle)
        toggle.syncState()

        navigationView.setCheckedItem(R.id.nav_countdown)
        navigationView.setNavigationItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_countdown -> showCountdownScreen()
                R.id.nav_sms_manager -> showSmsManagerScreen()
            }
            drawerLayout.closeDrawer(GravityCompat.START)
            true
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    drawerLayout.isDrawerOpen(GravityCompat.START) -> {
                        drawerLayout.closeDrawer(GravityCompat.START)
                    }
                    layoutSmsManager.visibility == View.VISIBLE -> {
                        showCountdownScreen()
                    }
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })
    }

    private fun setupCountdownScreen() {
        val savedSeconds = VaultPrefs.getCountdownSeconds(this)
        totalDurationMillis = savedSeconds * 1000L
        remainingMillis = totalDurationMillis

        val btnPreset15: MaterialButton = findViewById(R.id.btnPreset15)
        val btnPreset30: MaterialButton = findViewById(R.id.btnPreset30)
        val btnPreset60: MaterialButton = findViewById(R.id.btnPreset60)
        val btnCountdownSyncNow: MaterialButton = findViewById(R.id.btnCountdownSyncNow)
        val btnOpenSmsManagerQuick: MaterialButton = findViewById(R.id.btnOpenSmsManagerQuick)

        btnPreset15.setOnClickListener { applyCountdownPreset(15) }
        btnPreset30.setOnClickListener { applyCountdownPreset(30) }
        btnPreset60.setOnClickListener { applyCountdownPreset(60) }

        btnPauseResumeCountdown.setOnClickListener {
            if (isCountdownPaused) {
                isCountdownPaused = false
                btnPauseResumeCountdown.text = "Pause"
                tvCountdownState.text = "ACTIVE"
                startCountdown(remainingMillis)
            } else {
                isCountdownPaused = true
                countDownTimer?.cancel()
                btnPauseResumeCountdown.text = "Resume"
                tvCountdownState.text = "PAUSED"
            }
        }

        btnCountdownSyncNow.setOnClickListener {
            importDeviceInbox(triggerSync = true)
            resetAndStartCountdown()
        }

        btnOpenSmsManagerQuick.setOnClickListener {
            showSmsManagerScreen()
        }
    }

    private fun setupSmsManagerScreen() {
        val btnSaveKey: MaterialButton = findViewById(R.id.btnSaveKey)
        val btnBackupAllNow: MaterialButton = findViewById(R.id.btnBackupAllNow)
        val btnRefreshInbox: MaterialButton = findViewById(R.id.btnRefreshInbox)
        val recyclerSms: RecyclerView = findViewById(R.id.recyclerSms)

        etApiKey.setText(VaultPrefs.getApiKey(this))
        switchAutoBackup.isChecked = VaultPrefs.isAutoBackupEnabled(this)

        adapter = SmsAdapter { smsItem ->
            if (!smsItem.isSynced) {
                SmsSyncWorker.enqueueConnectedSync(this)
                Toast.makeText(
                    this,
                    "Queued SMS from ${smsItem.sender} for backup.",
                    Toast.LENGTH_SHORT
                ).show()
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

        btnSaveKey.setOnClickListener {
            val apiKey = etApiKey.text?.toString().orEmpty()
            VaultPrefs.saveApiKey(this, apiKey)
            Toast.makeText(this, "API Key saved successfully.", Toast.LENGTH_SHORT).show()
        }

        btnBackupAllNow.setOnClickListener {
            val apiKey = etApiKey.text?.toString().orEmpty()
            VaultPrefs.saveApiKey(this, apiKey)
            importDeviceInbox(triggerSync = true)
        }

        btnRefreshInbox.setOnClickListener {
            refreshUiFromDb()
        }
    }

    private fun showCountdownScreen() {
        layoutCountdown.visibility = View.VISIBLE
        layoutSmsManager.visibility = View.GONE
        toolbar.title = "SMS Vault Sync"
        navigationView.setCheckedItem(R.id.nav_countdown)
        refreshUiFromDb()
    }

    private fun showSmsManagerScreen() {
        layoutCountdown.visibility = View.GONE
        layoutSmsManager.visibility = View.VISIBLE
        toolbar.title = "SMS Manager"
        navigationView.setCheckedItem(R.id.nav_sms_manager)
        refreshUiFromDb()
    }

    private fun applyCountdownPreset(seconds: Int) {
        VaultPrefs.setCountdownSeconds(this, seconds)
        totalDurationMillis = seconds * 1000L
        isCountdownPaused = false
        btnPauseResumeCountdown.text = "Pause"
        tvCountdownState.text = "ACTIVE"
        resetAndStartCountdown()
    }

    private fun resetAndStartCountdown() {
        remainingMillis = totalDurationMillis
        if (!isCountdownPaused) {
            startCountdown(remainingMillis)
        } else {
            updateCountdownUi(remainingMillis)
        }
    }

    private fun startCountdown(durationMillis: Long) {
        countDownTimer?.cancel()
        val safeDuration = durationMillis.coerceAtLeast(1000L)

        countDownTimer = object : CountDownTimer(safeDuration, 100L) {
            override fun onTick(millisUntilFinished: Long) {
                remainingMillis = millisUntilFinished
                updateCountdownUi(millisUntilFinished)
            }

            override fun onFinish() {
                remainingMillis = 0L
                updateCountdownUi(0L)
                tvCountdownSubtitle.text = "Sync cycle triggered — checking inbox..."
                importDeviceInbox(triggerSync = VaultPrefs.isAutoBackupEnabled(this@MainActivity))
                resetAndStartCountdown()
            }
        }.start()
    }

    private fun updateCountdownUi(millis: Long) {
        val totalSecs = ((millis + 999L) / 1000L).toInt().coerceAtLeast(0)
        val mins = totalSecs / 60
        val secs = totalSecs % 60
        tvCountdownTimer.text = String.format(Locale.US, "%02d:%02d", mins, secs)

        val progress = ((millis.toDouble() / totalDurationMillis.toDouble()) * 1000.0)
            .toInt()
            .coerceIn(0, 1000)
        countdownProgress.progress = progress

        if (!isCountdownPaused && millis > 0L) {
            tvCountdownSubtitle.text = "Next background sync cycle starts in"
        }
    }

    override fun onResume() {
        super.onResume()
        refreshUiFromDb()
        if (!isCountdownPaused) {
            startCountdown(remainingMillis)
        }
    }

    override fun onPause() {
        super.onPause()
        countDownTimer?.cancel()
    }

    override fun onDestroy() {
        super.onDestroy()
        countDownTimer?.cancel()
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
                tvCardSyncedCount.text = synced.toString()
                tvCardPendingCount.text = pending.toString()
            }
        }.start()
    }
}
