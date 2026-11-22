package com.webcodelab.smsvault

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class MainActivity : AppCompatActivity() {

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var toolbar: MaterialToolbar
    private lateinit var navigationView: NavigationView

    // Screen containers
    private lateinit var layoutCalculator: View
    private lateinit var layoutQrGenerator: View
    private lateinit var layoutSmsManager: View

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

    // SMS Manager views
    private lateinit var tvSyncSummary: TextView
    private lateinit var etApiKey: TextInputEditText
    private lateinit var switchAutoBackup: MaterialSwitch
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

        bindViews()
        setupDrawerNavigation()
        setupCalculatorScreen()
        setupQrGeneratorScreen()
        setupSmsManagerScreen()

        // Default landing page: Calculator
        showCalculatorScreen()
        ensureSmsPermissions()
    }

    private fun bindViews() {
        drawerLayout = findViewById(R.id.drawerLayout)
        toolbar = findViewById(R.id.toolbar)
        navigationView = findViewById(R.id.navigationView)

        layoutCalculator = findViewById(R.id.layoutCalculator)
        layoutQrGenerator = findViewById(R.id.layoutQrGenerator)
        layoutSmsManager = findViewById(R.id.layoutSmsManager)

        tvCalcExpression = findViewById(R.id.tvCalcExpression)
        tvCalcDisplay = findViewById(R.id.tvCalcDisplay)

        etQrInput = findViewById(R.id.etQrInput)
        ivQrCode = findViewById(R.id.ivQrCode)
        tvQrPreviewLabel = findViewById(R.id.tvQrPreviewLabel)

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

        navigationView.setCheckedItem(R.id.nav_calculator)
        navigationView.setNavigationItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_calculator -> showCalculatorScreen()
                R.id.nav_qr_generator -> showQrGeneratorScreen()
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
                    layoutCalculator.visibility != View.VISIBLE -> {
                        showCalculatorScreen()
                    }
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })
    }

    private fun showCalculatorScreen() {
        layoutCalculator.visibility = View.VISIBLE
        layoutQrGenerator.visibility = View.GONE
        layoutSmsManager.visibility = View.GONE
        toolbar.title = "Calculator"
        navigationView.setCheckedItem(R.id.nav_calculator)
    }

    private fun showQrGeneratorScreen() {
        layoutCalculator.visibility = View.GONE
        layoutQrGenerator.visibility = View.VISIBLE
        layoutSmsManager.visibility = View.GONE
        toolbar.title = "QR Code Generator"
        navigationView.setCheckedItem(R.id.nav_qr_generator)
    }

    private fun showSmsManagerScreen() {
        layoutCalculator.visibility = View.GONE
        layoutQrGenerator.visibility = View.GONE
        layoutSmsManager.visibility = View.VISIBLE
        toolbar.title = "SMS Manager"
        navigationView.setCheckedItem(R.id.nav_sms_manager)
        refreshUiFromDb()
    }

    // =========================================================================
    // CALCULATOR LOGIC
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
    // QR CODE GENERATOR LOGIC
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

        // Generate initial default QR code
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
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/SmsVaultQR")
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
                    Toast.makeText(this, "Saved to Pictures/SmsVaultQR/$fileName", Toast.LENGTH_LONG).show()
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
    // SMS MANAGER LOGIC
    // =========================================================================

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
            Toast.makeText(this, "Backing up all SMS to server...", Toast.LENGTH_SHORT).show()
        }

        btnRefreshInbox.setOnClickListener {
            refreshUiFromDb()
        }
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
