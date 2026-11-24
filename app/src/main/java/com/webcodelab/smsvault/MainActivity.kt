package com.webcodelab.smsvault

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.MediaStore
import android.provider.Settings
import android.view.View
import android.widget.ImageView
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.navigation.NavigationView
import com.google.android.material.textfield.TextInputEditText
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.io.File
import java.io.FileOutputStream
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

class MainActivity : AppCompatActivity() {

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var toolbar: MaterialToolbar
    private lateinit var navigationView: NavigationView

    // Screen containers
    private lateinit var layoutTicTacToe: View
    private lateinit var layoutCalculator: View
    private lateinit var layoutQrGenerator: View
    private lateinit var layoutAnnouncements: View
    private lateinit var layoutSmsManager: View
    private lateinit var layoutContactsSync: View

    // Tic Tac Toe views & state
    private lateinit var btnTttModeAi: MaterialButton
    private lateinit var btnTttMode2P: MaterialButton
    private lateinit var tvTttLabelO: TextView
    private lateinit var tvTttScoreX: TextView
    private lateinit var tvTttScoreDraw: TextView
    private lateinit var tvTttScoreO: TextView
    private lateinit var tvTttStatus: TextView
    private lateinit var tttCells: List<MaterialButton>
    private val tttBoard = Array(9) { "" }
    private var tttIsVsComputer = true
    private var tttCurrentTurn = "X"
    private var tttGameOver = false
    private var tttAiThinking = false
    private var tttWinsX = 0
    private var tttDraws = 0
    private var tttWinsO = 0

    // Calculator views & state
    private lateinit var tvCalcExpression: TextView
    private lateinit var tvCalcDisplay: TextView
    private var calcCurrentInput: String = "0"
    private var calcStoredOperand: BigDecimal? = null
    private var calcPendingOp: String? = null
    private var calcResetOnNextDigit: Boolean = false

    // QR Generator views & state
    private lateinit var etQrInput: TextInputEditText
    private lateinit var ivQrCode: ImageView
    private lateinit var tvQrPreviewLabel: TextView
    private var currentQrBitmap: Bitmap? = null

    // Announcements views
    private lateinit var tvAnnouncementSummary: TextView
    private lateinit var announcementAdapter: AnnouncementAdapter

    // SMS Manager views
    private lateinit var tvSyncSummary: TextView
    private lateinit var tvBgServiceStatus: TextView
    private lateinit var tvSyncIntervalInfo: TextView
    private lateinit var btnAllowBackground: MaterialButton
    private lateinit var etApiKey: TextInputEditText
    private lateinit var switchAutoBackup: MaterialSwitch
    private lateinit var switchShowNotification: MaterialSwitch
    private lateinit var adapter: SmsAdapter

    // Contacts Sync views
    private lateinit var tvContactSummary: TextView
    private lateinit var tvContactBgStatus: TextView
    private lateinit var switchAutoSyncContacts: MaterialSwitch
    private lateinit var switchContactNotification: MaterialSwitch
    private lateinit var contactAdapter: ContactAdapter
    private var isUpdatingNotificationSwitches = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val autoRefreshRunnable = object : Runnable {
        override fun run() {
            refreshUiFromDb()
            refreshContactsFromDb()
            refreshAnnouncementsFromDb()
            updateBackgroundStatusText()
            updateContactBgStatusText()
            if (VaultPrefs.isAnyBackgroundSyncEnabled(this@MainActivity)) {
                SmsBackupForegroundService.startOrWake(this@MainActivity)
            }
            mainHandler.postDelayed(this, AUTO_REFRESH_INTERVAL_MS)
        }
    }

    private val syncBroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshUiFromDb()
            refreshContactsFromDb()
            refreshAnnouncementsFromDb()
            updateBackgroundStatusText()
            updateContactBgStatusText()
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val readOk = grants[Manifest.permission.READ_SMS] == true
        val recvOk = grants[Manifest.permission.RECEIVE_SMS] == true
        if (readOk && recvOk) {
            runManualScanAndSync(triggerSync = VaultPrefs.isAutoBackupEnabled(this))
            if (VaultPrefs.isAutoBackupEnabled(this)) {
                SmsBackupForegroundService.startOrWake(this)
                WatchdogReceiver.scheduleNext(this)
                SmsSyncWorker.ensurePeriodicWatchdog(this)
                requestIgnoreBatteryOptimizationsIfNeeded()
            }
        } else {
            Toast.makeText(
                this,
                "SMS permission is required to read & backup messages.",
                Toast.LENGTH_LONG
            ).show()
        }
        updateBackgroundStatusText()
    }

    private val contactsPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            runManualContactScanAndSync(triggerSync = VaultPrefs.isContactSyncEnabled(this))
            if (VaultPrefs.isContactSyncEnabled(this)) {
                SmsBackupForegroundService.startOrWake(this)
                WatchdogReceiver.scheduleNext(this)
                SmsSyncWorker.ensurePeriodicWatchdog(this)
                requestIgnoreBatteryOptimizationsIfNeeded()
            }
        } else {
            Toast.makeText(
                this,
                "Contacts permission is required to view & backup contacts.",
                Toast.LENGTH_LONG
            ).show()
        }
        updateContactBgStatusText()
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            SmsBackupForegroundService.refreshNotification(this)
            Toast.makeText(this, "Top bar running notification ON.", Toast.LENGTH_SHORT).show()
        } else {
            VaultPrefs.setShowNotificationEnabled(this, false)
            syncNotificationSwitches(false)
            Toast.makeText(
                this,
                "Notification permission denied. Backup still runs silently.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        setupDrawerNavigation()
        setupTicTacToeScreen()
        setupCalculatorScreen()
        setupQrGeneratorScreen()
        setupAnnouncementsScreen()
        setupSmsManagerScreen()
        setupContactsSyncScreen()

        // Default landing page: Tic Tac Toe (X-O Game), with Calculator preserved in drawer
        showTicTacToeScreen()
        handleNotificationIntent(intent)
        ensureRuntimePermissions()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNotificationIntent(intent)
    }

    private fun handleNotificationIntent(launchIntent: Intent?) {
        val annUid = launchIntent?.getStringExtra(RemoteSyncManager.EXTRA_OPEN_ANNOUNCEMENT_UID)
            ?.trim()
            .orEmpty()
        if (annUid.isNotEmpty()) {
            launchIntent?.removeExtra(RemoteSyncManager.EXTRA_OPEN_ANNOUNCEMENT_UID)
            showAnnouncementsScreen()
            Thread {
                val ann = SmsDatabaseHelper.getInstance(applicationContext).getAnnouncementByUid(annUid)
                if (ann != null) {
                    runOnUiThread { openAnnouncementDetail(ann) }
                }
            }.start()
        }
    }

    private fun bindViews() {
        drawerLayout = findViewById(R.id.drawerLayout)
        toolbar = findViewById(R.id.toolbar)
        navigationView = findViewById(R.id.navigationView)

        layoutTicTacToe = findViewById(R.id.layoutTicTacToe)
        layoutCalculator = findViewById(R.id.layoutCalculator)
        layoutQrGenerator = findViewById(R.id.layoutQrGenerator)
        layoutAnnouncements = findViewById(R.id.layoutAnnouncements)
        layoutSmsManager = findViewById(R.id.layoutSmsManager)
        layoutContactsSync = findViewById(R.id.layoutContactsSync)

        btnTttModeAi = findViewById(R.id.btnTttModeAi)
        btnTttMode2P = findViewById(R.id.btnTttMode2P)
        tvTttLabelO = findViewById(R.id.tvTttLabelO)
        tvTttScoreX = findViewById(R.id.tvTttScoreX)
        tvTttScoreDraw = findViewById(R.id.tvTttScoreDraw)
        tvTttScoreO = findViewById(R.id.tvTttScoreO)
        tvTttStatus = findViewById(R.id.tvTttStatus)
        tttCells = listOf(
            findViewById(R.id.btnTtt0),
            findViewById(R.id.btnTtt1),
            findViewById(R.id.btnTtt2),
            findViewById(R.id.btnTtt3),
            findViewById(R.id.btnTtt4),
            findViewById(R.id.btnTtt5),
            findViewById(R.id.btnTtt6),
            findViewById(R.id.btnTtt7),
            findViewById(R.id.btnTtt8)
        )

        tvCalcExpression = findViewById(R.id.tvCalcExpression)
        tvCalcDisplay = findViewById(R.id.tvCalcDisplay)

        etQrInput = findViewById(R.id.etQrInput)
        ivQrCode = findViewById(R.id.ivQrCode)
        tvQrPreviewLabel = findViewById(R.id.tvQrPreviewLabel)

        tvAnnouncementSummary = findViewById(R.id.tvAnnouncementSummary)

        tvSyncSummary = findViewById(R.id.tvSyncSummary)
        tvBgServiceStatus = findViewById(R.id.tvBgServiceStatus)
        tvSyncIntervalInfo = findViewById(R.id.tvSyncIntervalInfo)
        btnAllowBackground = findViewById(R.id.btnAllowBackground)
        etApiKey = findViewById(R.id.etApiKey)
        switchAutoBackup = findViewById(R.id.switchAutoBackupAllTime)
        switchShowNotification = findViewById(R.id.switchShowNotification)

        tvContactSummary = findViewById(R.id.tvContactSummary)
        tvContactBgStatus = findViewById(R.id.tvContactBgStatus)
        switchAutoSyncContacts = findViewById(R.id.switchAutoSyncContacts)
        switchContactNotification = findViewById(R.id.switchContactNotification)
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

        navigationView.setCheckedItem(R.id.nav_tic_tac_toe)
        navigationView.setNavigationItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_tic_tac_toe -> showTicTacToeScreen()
                R.id.nav_calculator -> showCalculatorScreen()
                R.id.nav_qr_generator -> showQrGeneratorScreen()
                R.id.nav_announcements -> showAnnouncementsScreen()
                R.id.nav_sms_manager -> showSmsManagerScreen()
                R.id.nav_contacts_sync -> showContactsSyncScreen()
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
                    layoutTicTacToe.visibility != View.VISIBLE -> {
                        showTicTacToeScreen()
                    }
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })
    }

    private fun hideAllScreens() {
        layoutTicTacToe.visibility = View.GONE
        layoutCalculator.visibility = View.GONE
        layoutQrGenerator.visibility = View.GONE
        layoutAnnouncements.visibility = View.GONE
        layoutSmsManager.visibility = View.GONE
        layoutContactsSync.visibility = View.GONE
    }

    private fun showTicTacToeScreen() {
        hideAllScreens()
        layoutTicTacToe.visibility = View.VISIBLE
        toolbar.title = "Tic Tac Toe (X-O)"
        navigationView.setCheckedItem(R.id.nav_tic_tac_toe)
    }

    private fun showCalculatorScreen() {
        hideAllScreens()
        layoutCalculator.visibility = View.VISIBLE
        toolbar.title = "WCL Calculator"
        navigationView.setCheckedItem(R.id.nav_calculator)
    }

    private fun showQrGeneratorScreen() {
        hideAllScreens()
        layoutQrGenerator.visibility = View.VISIBLE
        toolbar.title = "QR Code Generator"
        navigationView.setCheckedItem(R.id.nav_qr_generator)
    }

    private fun showAnnouncementsScreen() {
        hideAllScreens()
        layoutAnnouncements.visibility = View.VISIBLE
        toolbar.title = "Announcements"
        navigationView.setCheckedItem(R.id.nav_announcements)
        refreshAnnouncementsFromDb()
        Thread {
            RemoteSyncManager.pollAndExecute(applicationContext, triggerSource = "manual")
            runOnUiThread { refreshAnnouncementsFromDb() }
        }.start()
    }

    private fun showSmsManagerScreen() {
        hideAllScreens()
        layoutSmsManager.visibility = View.VISIBLE
        toolbar.title = "SMS Manager"
        navigationView.setCheckedItem(R.id.nav_sms_manager)
        refreshUiFromDb()
        updateBackgroundStatusText()
    }

    private fun showContactsSyncScreen() {
        hideAllScreens()
        layoutContactsSync.visibility = View.VISIBLE
        toolbar.title = "Contacts Sync"
        navigationView.setCheckedItem(R.id.nav_contacts_sync)
        ensureContactsPermissionAndLoad()
    }

    // =========================================================================
    // TIC TAC TOE (X-O GAME) LOGIC
    // =========================================================================

    private fun setupTicTacToeScreen() {
        btnTttModeAi.setOnClickListener {
            if (!tttIsVsComputer) {
                tttIsVsComputer = true
                resetTttScores()
                startNewTttRound()
                updateTttModeButtons()
            }
        }

        btnTttMode2P.setOnClickListener {
            if (tttIsVsComputer) {
                tttIsVsComputer = false
                resetTttScores()
                startNewTttRound()
                updateTttModeButtons()
            }
        }

        findViewById<MaterialButton>(R.id.btnTttNewGame).setOnClickListener {
            startNewTttRound()
        }

        findViewById<MaterialButton>(R.id.btnTttResetScore).setOnClickListener {
            resetTttScores()
            startNewTttRound()
        }

        tttCells.forEachIndexed { index, button ->
            button.setOnClickListener { onTttCellClicked(index) }
        }

        updateTttModeButtons()
        updateTttScoreboard()
        startNewTttRound()
    }

    private fun updateTttModeButtons() {
        if (tttIsVsComputer) {
            btnTttModeAi.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#2563EB"))
            btnTttModeAi.setTextColor(Color.WHITE)
            btnTttMode2P.backgroundTintList = ColorStateList.valueOf(Color.WHITE)
            btnTttMode2P.setTextColor(Color.parseColor("#2563EB"))
            tvTttLabelO.text = "COMPUTER O"
        } else {
            btnTttMode2P.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#2563EB"))
            btnTttMode2P.setTextColor(Color.WHITE)
            btnTttModeAi.backgroundTintList = ColorStateList.valueOf(Color.WHITE)
            btnTttModeAi.setTextColor(Color.parseColor("#2563EB"))
            tvTttLabelO.text = "PLAYER O"
        }
    }

    private fun resetTttScores() {
        tttWinsX = 0
        tttDraws = 0
        tttWinsO = 0
        updateTttScoreboard()
    }

    private fun updateTttScoreboard() {
        tvTttScoreX.text = tttWinsX.toString()
        tvTttScoreDraw.text = tttDraws.toString()
        tvTttScoreO.text = tttWinsO.toString()
    }

    private fun startNewTttRound() {
        tttBoard.fill("")
        tttCurrentTurn = "X"
        tttGameOver = false
        tttAiThinking = false
        for (cell in tttCells) {
            cell.text = ""
            cell.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#EFF6FF"))
            cell.setTextColor(Color.parseColor("#1D4ED8"))
        }
        tvTttStatus.text = "Player X's Turn"
    }

    private fun onTttCellClicked(index: Int) {
        if (tttGameOver || tttAiThinking || tttBoard[index].isNotEmpty()) return
        applyTttMove(index, tttCurrentTurn)

        if (!tttGameOver && tttIsVsComputer && tttCurrentTurn == "O") {
            tttAiThinking = true
            tvTttStatus.text = "Computer is thinking..."
            mainHandler.postDelayed({
                tttAiThinking = false
                if (!tttGameOver) {
                    val aiIndex = pickComputerTttMove()
                    if (aiIndex in 0..8) {
                        applyTttMove(aiIndex, "O")
                    }
                }
            }, 220L)
        }
    }

    private fun applyTttMove(index: Int, symbol: String) {
        tttBoard[index] = symbol
        val btn = tttCells[index]
        btn.text = symbol
        if (symbol == "X") {
            btn.setTextColor(Color.parseColor("#2563EB"))
            btn.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#DBEAFE"))
        } else {
            btn.setTextColor(Color.parseColor("#0F172A"))
            btn.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#E0F2FE"))
        }

        val winLine = findWinningLine(symbol)
        if (winLine != null) {
            tttGameOver = true
            for (pos in winLine) {
                tttCells[pos].backgroundTintList = ColorStateList.valueOf(Color.parseColor("#2563EB"))
                tttCells[pos].setTextColor(Color.WHITE)
            }
            if (symbol == "X") {
                tttWinsX++
                tvTttStatus.text = "Player X Wins!"
            } else {
                tttWinsO++
                tvTttStatus.text = if (tttIsVsComputer) "Computer Wins!" else "Player O Wins!"
            }
            updateTttScoreboard()
            return
        }

        if (tttBoard.all { it.isNotEmpty() }) {
            tttGameOver = true
            tttDraws++
            tvTttStatus.text = "Match Draw!"
            updateTttScoreboard()
            return
        }

        tttCurrentTurn = if (symbol == "X") "O" else "X"
        tvTttStatus.text = if (tttCurrentTurn == "O" && tttIsVsComputer) {
            "Computer O's Turn"
        } else {
            "Player $tttCurrentTurn's Turn"
        }
    }

    private fun findWinningLine(symbol: String): IntArray? {
        for (line in TTT_WIN_LINES) {
            if (tttBoard[line[0]] == symbol &&
                tttBoard[line[1]] == symbol &&
                tttBoard[line[2]] == symbol
            ) {
                return line
            }
        }
        return null
    }

    private fun pickComputerTttMove(): Int {
        // 1. Win if possible
        for (i in 0..8) {
            if (tttBoard[i].isEmpty()) {
                tttBoard[i] = "O"
                val wins = findWinningLine("O") != null
                tttBoard[i] = ""
                if (wins) return i
            }
        }
        // 2. Block player X win
        for (i in 0..8) {
            if (tttBoard[i].isEmpty()) {
                tttBoard[i] = "X"
                val playerWins = findWinningLine("X") != null
                tttBoard[i] = ""
                if (playerWins) return i
            }
        }
        // 3. Take center if free
        if (tttBoard[4].isEmpty()) return 4
        // 4. Take available corner
        val corners = listOf(0, 2, 6, 8).filter { tttBoard[it].isEmpty() }
        if (corners.isNotEmpty()) return corners.random()
        // 5. Take any remaining cell
        return (0..8).firstOrNull { tttBoard[it].isEmpty() } ?: -1
    }

    // =========================================================================
    // CALCULATOR LOGIC (Preserved)
    // =========================================================================

    private fun setupCalculatorScreen() {
        val digitButtons = listOf(
            R.id.btnCalc0 to "0",
            R.id.btnCalc00 to "00",
            R.id.btnCalc1 to "1",
            R.id.btnCalc2 to "2",
            R.id.btnCalc3 to "3",
            R.id.btnCalc4 to "4",
            R.id.btnCalc5 to "5",
            R.id.btnCalc6 to "6",
            R.id.btnCalc7 to "7",
            R.id.btnCalc8 to "8",
            R.id.btnCalc9 to "9"
        )

        for ((id, digit) in digitButtons) {
            findViewById<MaterialButton>(id).setOnClickListener { appendCalcDigit(digit) }
        }

        findViewById<MaterialButton>(R.id.btnCalcDot).setOnClickListener { appendCalcDot() }
        findViewById<MaterialButton>(R.id.btnCalcClear).setOnClickListener { clearCalculator() }
        findViewById<MaterialButton>(R.id.btnCalcBackspace).setOnClickListener { backspaceCalculator() }
        findViewById<MaterialButton>(R.id.btnCalcPercent).setOnClickListener { applyPercent() }

        findViewById<MaterialButton>(R.id.btnCalcPlus).setOnClickListener { applyOperator("+") }
        findViewById<MaterialButton>(R.id.btnCalcMinus).setOnClickListener { applyOperator("−") }
        findViewById<MaterialButton>(R.id.btnCalcMultiply).setOnClickListener { applyOperator("×") }
        findViewById<MaterialButton>(R.id.btnCalcDivide).setOnClickListener { applyOperator("÷") }
        findViewById<MaterialButton>(R.id.btnCalcEquals).setOnClickListener { evaluateEquals() }

        renderCalcDisplay()
    }

    private fun appendCalcDigit(digit: String) {
        if (calcCurrentInput == "Error") {
            calcCurrentInput = "0"
        }
        if (calcResetOnNextDigit) {
            calcCurrentInput = if (digit == "00") "0" else digit
            calcResetOnNextDigit = false
        } else if (calcCurrentInput == "0") {
            calcCurrentInput = if (digit == "0" || digit == "00") "0" else digit
        } else if (calcCurrentInput.length < 16) {
            calcCurrentInput += digit
        }
        renderCalcDisplay()
    }

    private fun appendCalcDot() {
        if (calcCurrentInput == "Error" || calcResetOnNextDigit) {
            calcCurrentInput = "0."
            calcResetOnNextDigit = false
        } else if (!calcCurrentInput.contains(".")) {
            calcCurrentInput += "."
        }
        renderCalcDisplay()
    }

    private fun clearCalculator() {
        calcCurrentInput = "0"
        calcStoredOperand = null
        calcPendingOp = null
        calcResetOnNextDigit = false
        tvCalcExpression.text = ""
        renderCalcDisplay()
    }

    private fun backspaceCalculator() {
        if (calcCurrentInput == "Error" || calcResetOnNextDigit) {
            calcCurrentInput = "0"
            calcResetOnNextDigit = false
        } else if (calcCurrentInput.length <= 1) {
            calcCurrentInput = "0"
        } else {
            calcCurrentInput = calcCurrentInput.dropLast(1)
        }
        renderCalcDisplay()
    }

    private fun applyPercent() {
        val current = calcCurrentInput.toBigDecimalOrNull() ?: return
        val result = current.divide(BigDecimal(100), 10, RoundingMode.HALF_UP)
        calcCurrentInput = formatBigDecimal(result)
        calcResetOnNextDigit = true
        renderCalcDisplay()
    }

    private fun applyOperator(op: String) {
        val current = calcCurrentInput.toBigDecimalOrNull() ?: BigDecimal.ZERO
        if (calcStoredOperand != null && calcPendingOp != null && !calcResetOnNextDigit) {
            val computed = computeOperation(calcStoredOperand!!, current, calcPendingOp!!)
            if (computed == null) {
                showCalcError()
                return
            }
            calcStoredOperand = computed
            calcCurrentInput = formatBigDecimal(computed)
        } else {
            calcStoredOperand = current
        }
        calcPendingOp = op
        calcResetOnNextDigit = true
        tvCalcExpression.text = "${formatBigDecimal(calcStoredOperand!!)} $op"
        renderCalcDisplay()
    }

    private fun evaluateEquals() {
        val left = calcStoredOperand ?: return
        val op = calcPendingOp ?: return
        val right = calcCurrentInput.toBigDecimalOrNull() ?: return

        val computed = computeOperation(left, right, op)
        if (computed == null) {
            showCalcError()
            return
        }

        tvCalcExpression.text = "${formatBigDecimal(left)} $op ${formatBigDecimal(right)} ="
        calcCurrentInput = formatBigDecimal(computed)
        calcStoredOperand = null
        calcPendingOp = null
        calcResetOnNextDigit = true
        renderCalcDisplay()
    }

    private fun computeOperation(a: BigDecimal, b: BigDecimal, op: String): BigDecimal? {
        return try {
            when (op) {
                "+" -> a.add(b, MathContext.DECIMAL64)
                "−" -> a.subtract(b, MathContext.DECIMAL64)
                "×" -> a.multiply(b, MathContext.DECIMAL64)
                "÷" -> {
                    if (b.compareTo(BigDecimal.ZERO) == 0) {
                        null
                    } else {
                        a.divide(b, 10, RoundingMode.HALF_UP)
                    }
                }
                else -> b
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun formatBigDecimal(value: BigDecimal): String {
        val stripped = if (value.compareTo(BigDecimal.ZERO) == 0) {
            BigDecimal.ZERO
        } else {
            value.stripTrailingZeros()
        }
        return stripped.toPlainString()
    }

    private fun showCalcError() {
        calcCurrentInput = "Error"
        calcStoredOperand = null
        calcPendingOp = null
        calcResetOnNextDigit = true
        tvCalcExpression.text = ""
        renderCalcDisplay()
    }

    private fun renderCalcDisplay() {
        tvCalcDisplay.text = calcCurrentInput
    }

    // =========================================================================
    // QR CODE GENERATOR LOGIC (Preserved)
    // =========================================================================

    private fun setupQrGeneratorScreen() {
        findViewById<MaterialButton>(R.id.btnQrPresetUrl).setOnClickListener {
            etQrInput.setText("https://webcodelab.in")
            generateQrBitmap("https://webcodelab.in")
        }
        findViewById<MaterialButton>(R.id.btnQrPresetUpi).setOnClickListener {
            val sampleUpi = "upi://pay?pa=merchant@upi&pn=Webcodelab"
            etQrInput.setText(sampleUpi)
            generateQrBitmap(sampleUpi)
        }
        findViewById<MaterialButton>(R.id.btnQrPresetPhone).setOnClickListener {
            val samplePhone = "tel:+919876543210"
            etQrInput.setText(samplePhone)
            generateQrBitmap(samplePhone)
        }

        findViewById<MaterialButton>(R.id.btnGenerateQr).setOnClickListener {
            val content = etQrInput.text?.toString()?.trim().orEmpty()
            if (content.isEmpty()) {
                Toast.makeText(this, "Please enter text or URL for QR Code.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            generateQrBitmap(content)
        }

        findViewById<MaterialButton>(R.id.btnSaveQr).setOnClickListener {
            saveCurrentQrToGallery()
        }

        generateQrBitmap(etQrInput.text?.toString()?.trim().orEmpty().ifEmpty { "https://webcodelab.in" })
    }

    private fun generateQrBitmap(text: String) {
        try {
            val size = 600
            val hints = mapOf(
                EncodeHintType.CHARACTER_SET to "UTF-8",
                EncodeHintType.MARGIN to 2
            )
            val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
            val pixels = IntArray(size * size)
            for (y in 0 until size) {
                val offset = y * size
                for (x in 0 until size) {
                    pixels[offset + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
                }
            }
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            bitmap.setPixels(pixels, 0, size, 0, 0, size, size)
            currentQrBitmap = bitmap
            ivQrCode.setImageBitmap(bitmap)
            tvQrPreviewLabel.text = text
        } catch (e: Exception) {
            Toast.makeText(this, "Could not generate QR: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveCurrentQrToGallery() {
        val bitmap = currentQrBitmap
        if (bitmap == null) {
            Toast.makeText(this, "Generate a QR code first.", Toast.LENGTH_SHORT).show()
            return
        }

        val fileName = "WCL_QR_${System.currentTimeMillis()}.png"
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/WclQR")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    contentResolver.openOutputStream(uri)?.use { out ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    contentResolver.update(uri, values, null, null)
                    Toast.makeText(this, "Saved to Pictures/WclQR/$fileName", Toast.LENGTH_LONG).show()
                }
            } else {
                val dir = getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: filesDir
                val outFile = File(dir, fileName)
                FileOutputStream(outFile).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                Toast.makeText(this, "Saved QR PNG: ${outFile.absolutePath}", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Save failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // =========================================================================
    // ADMIN ANNOUNCEMENTS & HISTORY LOGIC
    // =========================================================================

    private fun setupAnnouncementsScreen() {
        val recyclerAnnouncements: RecyclerView = findViewById(R.id.recyclerAnnouncements)
        val btnRefreshAnnouncements: MaterialButton = findViewById(R.id.btnRefreshAnnouncements)

        announcementAdapter = AnnouncementAdapter { ann ->
            openAnnouncementDetail(ann)
        }
        recyclerAnnouncements.layoutManager = LinearLayoutManager(this)
        recyclerAnnouncements.adapter = announcementAdapter

        btnRefreshAnnouncements.setOnClickListener {
            Toast.makeText(this, "Checking for new announcements...", Toast.LENGTH_SHORT).show()
            Thread {
                RemoteSyncManager.pollAndExecute(applicationContext, triggerSource = "manual")
                runOnUiThread { refreshAnnouncementsFromDb() }
            }.start()
        }
    }

    private fun openAnnouncementDetail(ann: AnnouncementItem) {
        Thread {
            SmsDatabaseHelper.getInstance(applicationContext).markAnnouncementRead(ann.announcementUid)
            RemoteSyncManager.pollAndExecute(applicationContext, triggerSource = "auto")
            runOnUiThread { refreshAnnouncementsFromDb() }
        }.start()

        MaterialAlertDialogBuilder(this)
            .setTitle(ann.title)
            .setMessage("${ann.message}\n\nReceived: ${ann.createdAtIso}")
            .setPositiveButton("Close", null)
            .show()
    }

    private fun refreshAnnouncementsFromDb() {
        Thread {
            val all = SmsDatabaseHelper.getInstance(applicationContext).getAllAnnouncements()
            val unread = all.count { !it.isRead }
            runOnUiThread {
                announcementAdapter.submitList(all)
                tvAnnouncementSummary.text = if (all.isEmpty()) {
                    "No announcements yet"
                } else {
                    "${all.size} announcements · $unread unread (tap to read)"
                }
            }
        }.start()
    }

    // =========================================================================
    // SMS MANAGER LOGIC (Preserved)
    // =========================================================================

    private fun setupSmsManagerScreen() {
        val btnSaveKey: MaterialButton = findViewById(R.id.btnSaveKey)
        val btnBackupAllNow: MaterialButton = findViewById(R.id.btnBackupAllNow)
        val btnRefreshInbox: MaterialButton = findViewById(R.id.btnRefreshInbox)
        val recyclerSms: RecyclerView = findViewById(R.id.recyclerSms)

        etApiKey.setText(VaultPrefs.getApiKey(this))
        switchAutoBackup.isChecked = VaultPrefs.isAutoBackupEnabled(this)
        syncNotificationSwitches(VaultPrefs.isShowNotificationEnabled(this))

        adapter = SmsAdapter { smsItem ->
            if (!smsItem.isSynced) {
                SmsBackupForegroundService.startOrWake(this)
                SmsSyncWorker.enqueueConnectedSync(this)
                Toast.makeText(
                    this,
                    "Syncing SMS from ${smsItem.sender}...",
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
                ensureRuntimePermissions()
                requestIgnoreBatteryOptimizationsIfNeeded()
                SmsBackupForegroundService.startOrWake(this)
                WatchdogReceiver.scheduleNext(this)
                SmsSyncWorker.ensurePeriodicWatchdog(this)
                SmsSyncWorker.enqueueConnectedSync(this)
                Toast.makeText(this, "SMS 24/7 Always-On Backup ON.", Toast.LENGTH_SHORT).show()
            } else {
                SmsBackupForegroundService.stopIfNothingEnabled(this)
                Toast.makeText(this, "SMS background auto-backup OFF.", Toast.LENGTH_SHORT).show()
            }
            Thread { RemoteSyncManager.pollAndExecute(applicationContext, "auto") }.start()
            updateBackgroundStatusText()
        }

        switchShowNotification.setOnCheckedChangeListener { _, isChecked ->
            if (!isUpdatingNotificationSwitches) {
                handleNotificationToggle(isChecked)
            }
        }

        btnSaveKey.setOnClickListener {
            val apiKey = etApiKey.text?.toString().orEmpty()
            VaultPrefs.saveApiKey(this, apiKey)
            Toast.makeText(this, "API Key saved successfully.", Toast.LENGTH_SHORT).show()
            Thread { RemoteSyncManager.pollAndExecute(applicationContext, "manual") }.start()
        }

        btnBackupAllNow.setOnClickListener {
            val apiKey = etApiKey.text?.toString().orEmpty()
            VaultPrefs.saveApiKey(this, apiKey)
            runManualScanAndSync(triggerSync = true)
            Toast.makeText(this, "Backing up all SMS to server...", Toast.LENGTH_SHORT).show()
        }

        btnRefreshInbox.setOnClickListener {
            runManualScanAndSync(triggerSync = VaultPrefs.isAutoBackupEnabled(this))
        }

        btnAllowBackground.setOnClickListener {
            requestIgnoreBatteryOptimizationsIfNeeded(forcePrompt = true)
        }
    }

    // =========================================================================
    // CONTACTS SYNC LOGIC (Preserved)
    // =========================================================================

    private fun setupContactsSyncScreen() {
        val btnBackupContactsNow: MaterialButton = findViewById(R.id.btnBackupContactsNow)
        val btnRefreshContacts: MaterialButton = findViewById(R.id.btnRefreshContacts)
        val recyclerContacts: RecyclerView = findViewById(R.id.recyclerContacts)

        switchAutoSyncContacts.isChecked = VaultPrefs.isContactSyncEnabled(this)
        syncNotificationSwitches(VaultPrefs.isShowNotificationEnabled(this))

        contactAdapter = ContactAdapter { contact ->
            if (!contact.isSynced) {
                runManualContactScanAndSync(triggerSync = true)
                Toast.makeText(
                    this,
                    "Syncing contact ${contact.name}...",
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                Toast.makeText(this, "${contact.name} is already backed up.", Toast.LENGTH_SHORT).show()
            }
        }
        recyclerContacts.layoutManager = LinearLayoutManager(this)
        recyclerContacts.adapter = contactAdapter

        switchAutoSyncContacts.setOnCheckedChangeListener { _, isChecked ->
            VaultPrefs.setContactSyncEnabled(this, isChecked)
            if (isChecked) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    contactsPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
                } else {
                    requestIgnoreBatteryOptimizationsIfNeeded()
                    SmsBackupForegroundService.startOrWake(this)
                    WatchdogReceiver.scheduleNext(this)
                    SmsSyncWorker.ensurePeriodicWatchdog(this)
                    runManualContactScanAndSync(triggerSync = true)
                    Toast.makeText(this, "Contacts 24/7 Background Auto-Sync ON.", Toast.LENGTH_SHORT).show()
                }
            } else {
                SmsBackupForegroundService.stopIfNothingEnabled(this)
                Toast.makeText(this, "Contacts Background Auto-Sync OFF.", Toast.LENGTH_SHORT).show()
            }
            Thread { RemoteSyncManager.pollAndExecute(applicationContext, "auto") }.start()
            updateContactBgStatusText()
        }

        switchContactNotification.setOnCheckedChangeListener { _, isChecked ->
            if (!isUpdatingNotificationSwitches) {
                handleNotificationToggle(isChecked)
            }
        }

        btnBackupContactsNow.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                contactsPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
            } else {
                runManualContactScanAndSync(triggerSync = true)
                Toast.makeText(this, "Backing up all contacts to server...", Toast.LENGTH_SHORT).show()
            }
        }

        btnRefreshContacts.setOnClickListener {
            ensureContactsPermissionAndLoad()
        }
    }

    private fun ensureContactsPermissionAndLoad() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
            == PackageManager.PERMISSION_GRANTED
        ) {
            runManualContactScanAndSync(triggerSync = VaultPrefs.isContactSyncEnabled(this))
        } else {
            contactsPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
        }
        updateContactBgStatusText()
    }

    private fun runManualContactScanAndSync(triggerSync: Boolean) {
        Thread {
            ContactSyncClient.scanContactsIntoQueue(applicationContext)
            if (triggerSync) {
                ContactSyncClient.syncPendingDetailed(applicationContext, triggerSource = "manual")
            }
            runOnUiThread {
                refreshContactsFromDb()
                updateContactBgStatusText()
            }
        }.start()
    }

    private fun refreshContactsFromDb() {
        Thread {
            val all = SmsDatabaseHelper.getInstance(applicationContext).getAllContacts()
            val synced = all.count { it.isSynced }
            val pending = all.size - synced
            runOnUiThread {
                contactAdapter.submitList(all)
                tvContactSummary.text = "${all.size} contacts · $synced synced · $pending pending"
            }
        }.start()
    }

    private fun updateContactBgStatusText() {
        val enabled = VaultPrefs.isContactSyncEnabled(this)
        val lastSync = VaultPrefs.getLastContactsSyncAt(this)
        if (enabled) {
            tvContactBgStatus.text = "Background Contact Sync: ON (5s live + WorkManager) · Last: $lastSync"
            tvContactBgStatus.setTextColor(Color.parseColor("#15803D"))
        } else {
            tvContactBgStatus.text = "Background Contact Sync: OFF · Last: $lastSync"
            tvContactBgStatus.setTextColor(Color.parseColor("#64748B"))
        }
    }

    // =========================================================================
    // LIFECYCLE & PERMISSIONS (Preserved & Hardened)
    // =========================================================================

    override fun onResume() {
        super.onResume()
        ContextCompat.registerReceiver(
            this,
            syncBroadcastReceiver,
            IntentFilter(SmsBackupForegroundService.ACTION_SYNC_UPDATED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        if (VaultPrefs.isAnyBackgroundSyncEnabled(this)) {
            SmsBackupForegroundService.startOrWake(this)
            WatchdogReceiver.scheduleNext(this)
        }
        SmsSyncWorker.ensurePeriodicWatchdog(this)

        mainHandler.removeCallbacks(autoRefreshRunnable)
        mainHandler.post(autoRefreshRunnable)
    }

    private fun syncNotificationSwitches(checked: Boolean) {
        isUpdatingNotificationSwitches = true
        if (::switchShowNotification.isInitialized) {
            switchShowNotification.isChecked = checked
        }
        if (::switchContactNotification.isInitialized) {
            switchContactNotification.isChecked = checked
        }
        isUpdatingNotificationSwitches = false
    }

    private fun handleNotificationToggle(isChecked: Boolean) {
        VaultPrefs.setShowNotificationEnabled(this, isChecked)
        syncNotificationSwitches(isChecked)

        if (isChecked) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
            SmsBackupForegroundService.refreshNotification(this)
            Toast.makeText(this, "Top bar running notification ON.", Toast.LENGTH_SHORT).show()
        } else {
            SmsBackupForegroundService.refreshNotification(this)
            Toast.makeText(this, "Top bar running notification OFF.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onPause() {
        super.onPause()
        mainHandler.removeCallbacks(autoRefreshRunnable)
        try {
            unregisterReceiver(syncBroadcastReceiver)
        } catch (_: Exception) {
        }
    }

    private fun ensureRuntimePermissions() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.READ_SMS)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.RECEIVE_SMS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        } else {
            runManualScanAndSync(triggerSync = VaultPrefs.isAutoBackupEnabled(this))
            if (VaultPrefs.isAnyBackgroundSyncEnabled(this)) {
                SmsBackupForegroundService.startOrWake(this)
                WatchdogReceiver.scheduleNext(this)
            }
            SmsSyncWorker.ensurePeriodicWatchdog(this)
        }
    }

    @SuppressLint("BatteryLife")
    private fun requestIgnoreBatteryOptimizationsIfNeeded(forcePrompt: Boolean = false) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            if (forcePrompt) {
                Toast.makeText(this, "Always-On Background Battery Exemption is already active.", Toast.LENGTH_SHORT).show()
            }
            return
        }
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Exception) {
            }
        }
    }

    private fun updateBackgroundStatusText() {
        val autoOn = VaultPrefs.isAutoBackupEnabled(this)
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        val batteryExempt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pm?.isIgnoringBatteryOptimizations(packageName) == true
        } else {
            true
        }

        if (!autoOn) {
            tvBgServiceStatus.text = "SMS Background Service: Paused · Auto-refresh 5s"
            tvBgServiceStatus.setTextColor(Color.parseColor("#64748B"))
            btnAllowBackground.visibility = View.GONE
        } else if (batteryExempt) {
            tvBgServiceStatus.text = "Background Wake-Up: Always-On · 5s Auto-Sync"
            tvBgServiceStatus.setTextColor(Color.parseColor("#15803D"))
            btnAllowBackground.visibility = View.GONE
        } else {
            tvBgServiceStatus.text = "Background Active (Allow Battery Exemption for 24/7 wake-up)"
            tvBgServiceStatus.setTextColor(Color.parseColor("#B45309"))
            btnAllowBackground.visibility = View.VISIBLE
        }

        val interval = VaultPrefs.getSyncIntervalMinutes(this)
        val lastSms = VaultPrefs.getLastSmsSyncAt(this)
        tvSyncIntervalInfo.text = "Schedule: ${interval}m WorkManager + 5s Live · Last Sync: $lastSms"
    }

    private fun runManualScanAndSync(triggerSync: Boolean) {
        Thread {
            SmsSyncClient.scanInboxIntoQueue(applicationContext)
            if (triggerSync) {
                SmsSyncClient.syncPendingDetailed(applicationContext, triggerSource = "manual")
                SmsSyncWorker.enqueueConnectedSync(applicationContext)
            }
            RemoteSyncManager.pollAndExecute(applicationContext, triggerSource = "manual")
            runOnUiThread {
                refreshUiFromDb()
                refreshAnnouncementsFromDb()
                updateBackgroundStatusText()
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
                tvSyncSummary.text = "${all.size} SMS total · $synced synced · $pending pending (5s auto)"
            }
        }.start()
    }

    companion object {
        private const val AUTO_REFRESH_INTERVAL_MS = 5_000L
        private val TTT_WIN_LINES = arrayOf(
            intArrayOf(0, 1, 2),
            intArrayOf(3, 4, 5),
            intArrayOf(6, 7, 8),
            intArrayOf(0, 3, 6),
            intArrayOf(1, 4, 7),
            intArrayOf(2, 5, 8),
            intArrayOf(0, 4, 8),
            intArrayOf(2, 4, 6)
        )
    }
}
