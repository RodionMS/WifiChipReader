package com.example.wifichipreader

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var analyzer: WifiAnalyzer
    private lateinit var tabHardware: Button
    private lateinit var tabScanner: Button
    private lateinit var tabDebug: Button
    private lateinit var btnSettings: Button
    private lateinit var btnShowHistory: Button
    private lateinit var btnClearHistory: Button
    private lateinit var layoutHardware: ScrollView
    private lateinit var layoutScanner: ScrollView
    private lateinit var layoutDebug: ScrollView

    // Новые элементы для вкладки сканера
    private lateinit var btnSubWifi: Button
    private lateinit var btnSubBt: Button
    private lateinit var containerWifi: LinearLayout
    private lateinit var containerBt: LinearLayout
    private var currentScannerTab = 0 // 0 = Wi-Fi, 1 = Bluetooth

    private lateinit var btnAnalyze: Button
    private lateinit var btnShowQR: Button
    private lateinit var btnExportReport: Button
    private lateinit var tvDebugConsole: TextView
    private lateinit var blockSystem: LinearLayout
    private lateinit var blockHardware: LinearLayout
    private lateinit var blockStandards: LinearLayout
    private lateinit var blockConnection: LinearLayout
    private lateinit var graphSignal: SignalGraphView
    private lateinit var tvConnectionDetails: TextView
    private lateinit var tvDeviceModel: TextView
    private lateinit var tvRam: TextView
    private lateinit var tvRom: TextView

    private var currentTabIndex = 0
    private var lastPingMs = -1L
    private var lastReport: WifiAnalyzer.HardwareReport? = null
    private var lastMemReport: WifiAnalyzer.MemoryReport? = null
    private var lastBtReport: WifiAnalyzer.BtReport? = null

    private var reportContentToSave: String = ""

    private lateinit var db: AppDatabase

    private val createDocumentLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) {
            try {
                contentResolver.openOutputStream(uri)?.use { outputStream ->
                    outputStream.write(reportContentToSave.toByteArray())
                }
                Toast.makeText(this, getString(R.string.export_success_path, uri.path), Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this, getString(R.string.export_error, e.message), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val dbLoggerHandler = Handler(Looper.getMainLooper())
    private val dbLoggerRunnable = object : Runnable {
        override fun run() {
            Thread {
                val stats = analyzer.getLiveStats()
                if (stats.hasConnection) {
                    val freshPing = analyzer.measurePing()
                    lastPingMs = freshPing
                    db.wifiLogDao().insertLog(WifiLog(timestamp = System.currentTimeMillis(), ssid = stats.ssid, rssi = stats.rssi, linkSpeed = stats.linkSpeed, pingMs = freshPing))
                }
            }.start()
            dbLoggerHandler.postDelayed(this, 15000)
        }
    }

    private val scanHandler = Handler(Looper.getMainLooper())
    private val scanRunnable = object : Runnable {
        override fun run() {
            runScannerUpdate()
            scanHandler.postDelayed(this, 3000) // Ускоренный таймер: раз в 3 секунды
        }
    }

    private val liveGraphHandler = Handler(Looper.getMainLooper())
    private val liveGraphRunnable = object : Runnable {
        override fun run() {
            if (currentTabIndex == 0) {
                val stats = analyzer.getLiveStats()
                if (stats.hasConnection) {
                    graphSignal.visibility = View.VISIBLE
                    graphSignal.addDataPoint(stats.rssi)
                    findViewById<ProgressBar>(R.id.pbQuality).progress = stats.qualityScore
                    val qColorId = if (stats.qualityScore > 75) R.color.accent_green else if (stats.qualityScore > 40) R.color.warn_yellow else R.color.error_red
                    findViewById<TextView>(R.id.tvQualityScore).apply {
                        text = getString(R.string.quality, stats.qualityScore)
                        setTextColor(ContextCompat.getColor(this@MainActivity, qColorId))
                    }
                    tvConnectionDetails.text = getString(R.string.label_details, stats.ssid, stats.securityType, stats.frequency.toString(), stats.linkSpeed.toString(), stats.rssi.toString(), if (lastPingMs >= 0) "$lastPingMs ms" else "N/A")
                } else {
                    graphSignal.visibility = View.GONE
                    findViewById<ProgressBar>(R.id.pbQuality).progress = 0
                    findViewById<TextView>(R.id.tvQualityScore).text = getString(R.string.no_conn)
                    tvConnectionDetails.text = getString(R.string.info_unavail)
                }
            }
            liveGraphHandler.postDelayed(this, 1000)
        }
    }

    override fun attachBaseContext(newBase: Context) {
        val prefs = newBase.getSharedPreferences("WifiPrefs", Context.MODE_PRIVATE)
        val langCode = prefs.getString("AppLang", "ru") ?: "ru"
        super.attachBaseContext(updateLocale(newBase, langCode))
    }

    private fun updateLocale(context: Context, languageCode: String): Context {
        val locale = Locale(languageCode)
        Locale.setDefault(locale)
        val config = context.resources.configuration
        config.setLocale(locale)
        return context.createConfigurationContext(config)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val prefs = getSharedPreferences("WifiPrefs", Context.MODE_PRIVATE)
        AppCompatDelegate.setDefaultNightMode(prefs.getInt("AppTheme", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM))

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        AppLog.messages.clear()
        analyzer = WifiAnalyzer(this)
        db = AppDatabase.getDatabase(this)

        initViews()
        requestPermissionsIfNeeded()
        setupTvFocusAnimations()
        setupTabs()
        setupTooltips()

        dbLoggerHandler.post(dbLoggerRunnable)

        btnSettings.setOnClickListener { showSettingsDialog() }
        btnAnalyze.setOnClickListener { runHardwareScan() }
        btnExportReport.setOnClickListener { showExportDialog() }
        btnShowQR.setOnClickListener { showQrDialog() }

        btnShowHistory.setOnClickListener { showDatabaseHistory() }
        btnClearHistory.setOnClickListener {
            Thread {
                db.wifiLogDao().clearHistory()
                runOnUiThread { Toast.makeText(this@MainActivity, getString(R.string.db_cleared), Toast.LENGTH_SHORT).show() }
            }.start()
        }

        btnSubWifi.setOnClickListener { switchScannerSubTab(0) }
        btnSubBt.setOnClickListener { switchScannerSubTab(1) }

        runHardwareScan()
    }

    private fun initViews() {
        tabHardware = findViewById(R.id.tabHardware)
        tabScanner = findViewById(R.id.tabScanner)
        tabDebug = findViewById(R.id.tabDebug)
        btnSettings = findViewById(R.id.btnSettings)
        btnShowHistory = findViewById(R.id.btnShowHistory)
        btnClearHistory = findViewById(R.id.btnClearHistory)
        layoutHardware = findViewById(R.id.layoutHardware)
        layoutScanner = findViewById(R.id.layoutScanner)
        layoutDebug = findViewById(R.id.layoutDebug)

        btnSubWifi = findViewById(R.id.btnSubWifi)
        btnSubBt = findViewById(R.id.btnSubBt)
        containerWifi = findViewById(R.id.containerWifi)
        containerBt = findViewById(R.id.containerBt)

        btnAnalyze = findViewById(R.id.btnAnalyze)
        btnShowQR = findViewById(R.id.btnShowQR)
        btnExportReport = findViewById(R.id.btnExportReport)
        tvDebugConsole = findViewById(R.id.tvDebugConsole)
        blockSystem = findViewById(R.id.blockSystem)
        blockHardware = findViewById(R.id.blockHardware)
        blockStandards = findViewById(R.id.blockStandards)
        blockConnection = findViewById(R.id.blockConnection)
        graphSignal = findViewById(R.id.graphSignal)
        tvConnectionDetails = findViewById(R.id.tvConnectionDetails)
        tvDeviceModel = findViewById(R.id.tvDeviceModel)
        tvRam = findViewById(R.id.tvRam)
        tvRom = findViewById(R.id.tvRom)
    }

    private fun switchScannerSubTab(index: Int) {
        currentScannerTab = index
        if (index == 0) {
            btnSubWifi.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.accent_blue))
            btnSubWifi.setTextColor(Color.BLACK)
            btnSubBt.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.btn_inactive))
            btnSubBt.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
            containerWifi.visibility = View.VISIBLE
            containerBt.visibility = View.GONE
            analyzer.stopBtDiscovery()
        } else {
            btnSubWifi.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.btn_inactive))
            btnSubWifi.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
            btnSubBt.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.accent_purple))
            btnSubBt.setTextColor(Color.WHITE)
            containerWifi.visibility = View.GONE
            containerBt.visibility = View.VISIBLE
            analyzer.startBtDiscovery()
        }
        scanHandler.removeCallbacks(scanRunnable)
        scanHandler.post(scanRunnable)
    }

    private fun showDatabaseHistory() {
        Thread {
            val logs = db.wifiLogDao().getLastLogs()
            runOnUiThread {
                if (logs.isEmpty()) {
                    Toast.makeText(this, getString(R.string.db_empty_wait), Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                val sb = StringBuilder()
                val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
                sb.append(String.format("%-10s | %-5s | %-6s | %s\n", getString(R.string.table_time), "RSSI", "PING", "SSID"))
                sb.append("------------------------------------------\n")
                for (log in logs) {
                    sb.append(String.format("%-10s | %-5d | %-6s | %s\n", dateFormat.format(Date(log.timestamp)), log.rssi, if (log.pingMs >= 0) "${log.pingMs}ms" else "N/A", log.ssid))
                }
                val textView = TextView(this).apply {
                    text = sb.toString()
                    textSize = 12f
                    setPadding(32, 32, 32, 32)
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent_green))
                    setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.bg_card))
                    typeface = android.graphics.Typeface.MONOSPACE
                }
                AlertDialog.Builder(this).setTitle(getString(R.string.btn_show_history)).setView(ScrollView(this).apply { addView(textView) }).setPositiveButton(getString(R.string.dialog_close)) { d, _ -> d.dismiss() }.show()
            }
        }.start()
    }

    private fun showSettingsDialog() {
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(50, 40, 50, 40) }
        var dialog: AlertDialog? = null
        layout.addView(TextView(this).apply { text = getString(R.string.language); textSize = 18f; setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary)); setPadding(0, 0, 0, 20) })
        val langGroup = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        langGroup.addView(Button(this).apply { text = "RU"; setOnClickListener { setAppLanguage("ru") } })
        langGroup.addView(Button(this).apply { text = "EN"; setOnClickListener { setAppLanguage("en") } })
        langGroup.addView(Button(this).apply { text = "中文"; setOnClickListener { setAppLanguage("zh") } })
        layout.addView(langGroup)
        layout.addView(TextView(this).apply { text = getString(R.string.theme); textSize = 18f; setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary)); setPadding(0, 40, 0, 20) })
        val themeGroup = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        themeGroup.addView(Button(this).apply { text = getString(R.string.theme_dark); setOnClickListener { setAppTheme(AppCompatDelegate.MODE_NIGHT_YES); dialog?.dismiss() } })
        themeGroup.addView(Button(this).apply { text = getString(R.string.theme_light); setOnClickListener { setAppTheme(AppCompatDelegate.MODE_NIGHT_NO); dialog?.dismiss() } })
        layout.addView(themeGroup)
        dialog = AlertDialog.Builder(this).setTitle(getString(R.string.settings)).setView(layout).setPositiveButton(getString(R.string.dialog_close)) { d, _ -> d.dismiss() }.show()
    }

    private fun setAppLanguage(langCode: String) {
        getSharedPreferences("WifiPrefs", Context.MODE_PRIVATE).edit().putString("AppLang", langCode).apply()
        recreate()
    }

    private fun setAppTheme(themeMode: Int) {
        getSharedPreferences("WifiPrefs", Context.MODE_PRIVATE).edit().putInt("AppTheme", themeMode).apply()
        AppCompatDelegate.setDefaultNightMode(themeMode)
    }

    private fun setupTooltips() {
        blockSystem.setOnClickListener { showInfoDialog(getString(R.string.tt_sys_title), getString(R.string.tt_sys_desc)) }
        blockHardware.setOnClickListener { showInfoDialog(getString(R.string.tt_hw_title), getString(R.string.tt_hw_desc)) }
        blockStandards.setOnClickListener { showInfoDialog(getString(R.string.tt_std_title), getString(R.string.tt_std_desc)) }
        blockConnection.setOnClickListener { showInfoDialog(getString(R.string.tt_conn_title), getString(R.string.tt_conn_desc)) }
    }

    private fun showInfoDialog(title: String, message: String) {
        AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton(getString(R.string.dialog_ok)) { d, _ -> d.dismiss() }.show()
    }

    private fun showQrDialog() {
        val report = lastReport
        val mem = lastMemReport
        val bt = lastBtReport

        if (report == null || mem == null || bt == null) {
            Toast.makeText(this, getString(R.string.qr_need_scan), Toast.LENGTH_SHORT).show()
            return
        }

        val prefs = getSharedPreferences("WifiPrefs", Context.MODE_PRIVATE)
        val hasWifi5 = report.is5GSupported || prefs.getBoolean("has_wifi5", false)
        val hasWifi6 = report.currentStandard == "WiFi 6" || report.is6GSupported || prefs.getBoolean("has_wifi6", false)

        val physJsonValue = if (mem.physicalRomGb < 0) "-1.0" else String.format(Locale.US, "%.1f", mem.physicalRomGb)
        val btJsonValue = if (bt.isAuthorized) "\"${bt.btVersion}\"" else "\"Unknown\""

        val json = """
            {
              "Device": "${Build.MANUFACTURER} ${Build.MODEL}",
              "Android": "${Build.VERSION.RELEASE}",
              "RAM_GB": ${String.format(Locale.US, "%.1f", mem.ramTotalGb)},
              "ROM_GB": ${String.format(Locale.US, "%.1f", mem.romTotalGb)},
              "PHYS_GB": $physJsonValue,
              "is5GHz": ${report.is5GSupported},
              "is6GHz": ${report.is6GSupported},
              "WiFi5_ac": $hasWifi5,
              "WiFi6_ax": $hasWifi6,
              "Standard": "${report.currentStandard}",
              "BT_Version": $btJsonValue
            }
        """.trimIndent()

        btnShowQR.isEnabled = false
        val originalText = btnShowQR.text
        btnShowQR.text = "..."

        Thread {
            try {
                val size = 350
                val bitMatrix = QRCodeWriter().encode(json, BarcodeFormat.QR_CODE, size, size)
                val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                for (x in 0 until size) {
                    for (y in 0 until size) {
                        bitmap.setPixel(x, y, if (bitMatrix.get(x, y)) Color.BLACK else Color.WHITE)
                    }
                }
                runOnUiThread {
                    btnShowQR.isEnabled = true
                    btnShowQR.text = originalText
                    val imageView = ImageView(this@MainActivity).apply { setImageBitmap(bitmap); scaleType = ImageView.ScaleType.FIT_CENTER; setPadding(32, 32, 32, 32); setBackgroundColor(Color.WHITE) }
                    AlertDialog.Builder(this@MainActivity).setTitle(getString(R.string.qr_title)).setMessage(getString(R.string.qr_desc)).setView(imageView).setPositiveButton(getString(R.string.dialog_close)) { d, _ -> d.dismiss() }.show()
                }
            } catch (e: Exception) {
                AppLog.e("QR_CRASH", e.message ?: "Unknown error")
                runOnUiThread { btnShowQR.isEnabled = true; btnShowQR.text = originalText; Toast.makeText(this@MainActivity, getString(R.string.qr_error), Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun setupTvFocusAnimations() {
        val buttonFocusListener = View.OnFocusChangeListener { view, hasFocus ->
            if (hasFocus) {
                val border = android.graphics.drawable.GradientDrawable().apply { setColor(Color.TRANSPARENT); setStroke(8, ContextCompat.getColor(this@MainActivity, R.color.border_focus)); cornerRadius = 16f }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) view.foreground = border
                view.animate().scaleX(1.05f).scaleY(1.05f).translationZ(10f).setDuration(150).start()
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) view.foreground = null
                view.animate().scaleX(1.0f).scaleY(1.0f).translationZ(0f).setDuration(150).start()
            }
        }
        val blockFocusListener = View.OnFocusChangeListener { view, hasFocus ->
            if (hasFocus) {
                val border = android.graphics.drawable.GradientDrawable().apply { setColor(Color.TRANSPARENT); setStroke(8, ContextCompat.getColor(this@MainActivity, R.color.border_focus)); cornerRadius = 16f }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) view.foreground = border
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) view.foreground = null
            }
        }
        listOf(tabHardware, tabScanner, tabDebug, btnAnalyze, btnShowQR, btnExportReport, btnSettings, btnShowHistory, btnClearHistory, btnSubWifi, btnSubBt).forEach { it.onFocusChangeListener = buttonFocusListener }
        listOf(blockSystem, blockHardware, blockStandards, blockConnection).forEach { it.onFocusChangeListener = blockFocusListener }
    }

    private fun setupTabs() {
        tabHardware.setOnClickListener { switchTab(0) }
        tabScanner.setOnClickListener { switchTab(1) }
        tabDebug.setOnClickListener { switchTab(2) }
        switchTab(0)
    }

    private fun switchTab(index: Int) {
        currentTabIndex = index
        layoutHardware.visibility = if (index == 0) View.VISIBLE else View.GONE
        layoutScanner.visibility = if (index == 1) View.VISIBLE else View.GONE
        layoutDebug.visibility = if (index == 2) View.VISIBLE else View.GONE

        tabHardware.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, if (index == 0) R.color.accent_green else R.color.btn_inactive))
        tabHardware.setTextColor(if (index == 0) Color.BLACK else ContextCompat.getColor(this, R.color.text_primary))
        tabScanner.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, if (index == 1) R.color.accent_blue else R.color.btn_inactive))
        tabScanner.setTextColor(if (index == 1) Color.BLACK else ContextCompat.getColor(this, R.color.text_primary))
        tabDebug.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, if (index == 2) R.color.accent_purple else R.color.btn_inactive))
        tabDebug.setTextColor(ContextCompat.getColor(this, R.color.text_primary))

        liveGraphHandler.removeCallbacks(liveGraphRunnable)
        scanHandler.removeCallbacks(scanRunnable)

        if (index == 0) liveGraphHandler.post(liveGraphRunnable)
        if (index == 1) {
            if (currentScannerTab == 1) analyzer.startBtDiscovery()
            scanHandler.post(scanRunnable)
        } else {
            analyzer.stopBtDiscovery()
        }

        if (index == 2) {
            tvDebugConsole.text = getString(R.string.status_logs)
            Thread { val rawData = analyzer.getRawSystemData(); runOnUiThread { tvDebugConsole.text = rawData } }.start()
        }
    }

    private fun runHardwareScan() {
        btnAnalyze.isEnabled = false; btnAnalyze.text = getString(R.string.status_ping)
        Thread {
            val report = analyzer.getHardwareReport(); val mem = analyzer.getMemoryReport(); val bt = analyzer.getBluetoothReport()
            val prefs = getSharedPreferences("WifiPrefs", Context.MODE_PRIVATE)
            runOnUiThread {
                lastPingMs = report.pingMs; lastReport = report; lastMemReport = mem; lastBtReport = bt
                tvDeviceModel.text = getString(R.string.label_device, Build.MANUFACTURER.uppercase(), Build.MODEL)
                tvRam.text = getString(R.string.label_ram, String.format(Locale.US, "%.1f", mem.ramTotalGb))

                val physText = if (mem.physicalRomGb < 0) "Физический чип: Скрыт (защита Android) \uD83D\uDD12" else if (mem.physicalRomGb > (mem.romTotalGb * 1.5)) "Физический чип: ${String.format(Locale.US, "%.1f", mem.physicalRomGb)} GB ⚠️" else "Физический чип: ${String.format(Locale.US, "%.1f", mem.physicalRomGb)} GB"
                tvRom.text = getString(R.string.label_rom, String.format(Locale.US, "%.1f", mem.romTotalGb)) + "\n$physText\n\n=== АНАЛИЗ ПРОШИВКИ ===\n${analyzer.getFirmwareFlags()}"

                findViewById<TextView>(R.id.tvBand5G).apply { text = getString(R.string.label_5g, if (report.is5GSupported) getString(R.string.supported) else getString(R.string.not_supported)); setTextColor(ContextCompat.getColor(this@MainActivity, if (report.is5GSupported) R.color.accent_green else R.color.error_red)) }
                findViewById<TextView>(R.id.tvBand6G).apply { text = getString(R.string.label_6g, if (report.is6GSupported) getString(R.string.supported) else getString(R.string.not_supported)); setTextColor(ContextCompat.getColor(this@MainActivity, if (report.is6GSupported) R.color.accent_green else R.color.text_secondary)) }

                val btText = if (bt.isDetected) {
                    if (bt.isAuthorized) "Bluetooth: ${bt.btVersion} (${bt.moduleName}) ✅" else "Bluetooth: Нашёлся ${bt.moduleName}, но его нет в базе! ⚠️"
                } else "Bluetooth: Модуль не опознан (Скрыт) \uD83D\uDD12"
                findViewById<TextView>(R.id.tvBluetooth).apply { text = btText; setTextColor(ContextCompat.getColor(this@MainActivity, if (bt.isAuthorized) R.color.accent_green else R.color.error_red)) }

                var hasWifi5 = report.is5GSupported || prefs.getBoolean("has_wifi5", false)
                var hasWifi6 = report.currentStandard == "WiFi 6" || report.is6GSupported || prefs.getBoolean("has_wifi6", false)
                var hasWifi7 = report.currentStandard == "WiFi 7" || prefs.getBoolean("has_wifi7", false)
                prefs.edit().apply { putBoolean("has_wifi5", hasWifi5); putBoolean("has_wifi6", hasWifi6); putBoolean("has_wifi7", hasWifi7); apply() }

                val cGreen = R.color.accent_green; val cGray = R.color.text_secondary
                findViewById<TextView>(R.id.tvWifi1).apply { text = getString(R.string.label_wifi_yes, "1 (802.11b)", getString(R.string.supported)); setTextColor(ContextCompat.getColor(this@MainActivity, cGreen)) }
                findViewById<TextView>(R.id.tvWifi3).apply { text = getString(R.string.label_wifi_yes, "3 (802.11g)", getString(R.string.supported)); setTextColor(ContextCompat.getColor(this@MainActivity, cGreen)) }
                findViewById<TextView>(R.id.tvWifi2).apply { text = if (report.is5GSupported) getString(R.string.label_wifi_yes, "2 (802.11a)", getString(R.string.supported)) else getString(R.string.label_wifi_no, "2 (802.11a)", getString(R.string.not_supported)); setTextColor(ContextCompat.getColor(this@MainActivity, if (report.is5GSupported) cGreen else cGray)) }
                findViewById<TextView>(R.id.tvWifi4).apply { text = getString(R.string.label_wifi_yes, "4 (802.11n)", getString(R.string.supported)); setTextColor(ContextCompat.getColor(this@MainActivity, cGreen)) }
                findViewById<TextView>(R.id.tvWifi5).apply { text = if (hasWifi5) getString(R.string.label_wifi_yes, "5 (802.11ac)", getString(R.string.supported)) else getString(R.string.label_wifi_no, "5 (802.11ac)", getString(R.string.not_detected)); setTextColor(ContextCompat.getColor(this@MainActivity, if (hasWifi5) cGreen else cGray)) }
                findViewById<TextView>(R.id.tvWifi6).apply { text = if (hasWifi6) getString(R.string.label_wifi_yes, "6 (802.11ax)", getString(R.string.confirmed)) else getString(R.string.label_wifi_maybe, "6 (802.11ax)", getString(R.string.not_detected)); setTextColor(ContextCompat.getColor(this@MainActivity, if (hasWifi6) cGreen else cGray)) }
                findViewById<TextView>(R.id.tvWifi7).apply { text = if (hasWifi7) getString(R.string.label_wifi_yes, "7 (802.11be)", getString(R.string.confirmed)) else getString(R.string.label_wifi_no, "7 (802.11be)", getString(R.string.not_detected)); setTextColor(ContextCompat.getColor(this@MainActivity, if (hasWifi7) cGreen else cGray)) }

                btnAnalyze.isEnabled = true; btnAnalyze.text = getString(R.string.btn_scan)
            }
        }.start()
    }

    private fun runScannerUpdate() {
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val isLocEnabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) locationManager.isLocationEnabled else (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) || locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER))

        if (currentScannerTab == 0) {
            containerWifi.removeAllViews()
            if (!isLocEnabled) {
                containerWifi.addView(TextView(this).apply { text = getString(R.string.status_gps_off); setTextColor(ContextCompat.getColor(this@MainActivity, R.color.error_red)); textSize = 16f; gravity = Gravity.CENTER; setPadding(0, 50, 0, 0) })
                return
            }
            val wifiNetworks = analyzer.scanEther()
            if (wifiNetworks.isEmpty()) {
                containerWifi.addView(TextView(this).apply { text = getString(R.string.status_scanning); setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary)); textSize = 14f })
                return
            }
            for (net in wifiNetworks) {
                val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.bg_card)); setPadding(32, 24, 32, 24); layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, 16) } }
                card.addView(TextView(this).apply { text = net.ssid; setTextColor(ContextCompat.getColor(this@MainActivity, if (net.ssid == "[Hidden]" || net.ssid == "[Скрытая сеть]") R.color.error_red else R.color.warn_yellow)); textSize = 18f; setTypeface(null, android.graphics.Typeface.BOLD) })
                card.addView(TextView(this).apply { text = "MAC: ${net.bssid} (${net.vendor})\n\uD83D\uDD12 ${net.security}"; setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary)); textSize = 13f })
                card.addView(TextView(this).apply { text = getString(R.string.label_freq_signal, net.frequency.toString(), net.channel.toString(), net.rssi.toString()); setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent_blue)); textSize = 14f; setPadding(0, 8, 0, 0) })
                containerWifi.addView(card)
            }
        } else {
            containerBt.removeAllViews()
            val btDevices = analyzer.getDiscoveredBtDevices()
            if (btDevices.isEmpty()) {
                containerBt.addView(TextView(this).apply { text = "Поиск Bluetooth устройств..."; setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary)); textSize = 14f })
                return
            }
            for (bt in btDevices) {
                val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.bg_card)); setPadding(32, 24, 32, 24); layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, 16) } }
                card.addView(TextView(this).apply { text = bt.name; setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent_purple)); textSize = 18f; setTypeface(null, android.graphics.Typeface.BOLD) })
                card.addView(TextView(this).apply { text = "MAC: ${bt.address} (${bt.type})"; setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary)); textSize = 13f })
                card.addView(TextView(this).apply { text = "Сигнал (RSSI): ${bt.rssi} dBm"; setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent_blue)); textSize = 14f; setPadding(0, 8, 0, 0) })
                containerBt.addView(card)
            }
        }
    }

    private fun showExportDialog() {
        prepareReportContent()
        val options = arrayOf(getString(R.string.export_auto_usb), getString(R.string.export_manual_picker))
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.btn_export))
            .setItems(options) { _, which ->
                if (which == 0) exportAutoToUsb()
                else {
                    try { createDocumentLauncher.launch("WiFi_Report_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.txt") }
                    catch (e: Exception) { Toast.makeText(this, getString(R.string.export_picker_err), Toast.LENGTH_LONG).show(); exportAutoToUsb() }
                }
            }.show()
    }

    private fun prepareReportContent() {
        val header = StringBuilder()
        header.append("=== WIFI HARDWARE PRO ===\nDate: ${SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.US).format(Date())}\nDevice: ${Build.MANUFACTURER.uppercase()} ${Build.MODEL}\n\n")
        lastMemReport?.let { header.append("--- MEMORY ---\nRAM: ${String.format(Locale.US, "%.1f", it.ramTotalGb)} GB\nROM: ${String.format(Locale.US, "%.1f", it.romTotalGb)} GB\nPHYSICAL CHIP: ${if (it.physicalRomGb < 0) "Hidden (Android 12+)" else "${String.format(Locale.US, "%.1f", it.physicalRomGb)} GB"}\n\n") }
        lastReport?.let {
            header.append("--- WIFI & BT ---\n5 GHz: ${it.is5GSupported}\n6 GHz: ${it.is6GSupported}\n")
            lastBtReport?.let { bt -> header.append("Bluetooth: ${if (bt.isDetected) "${bt.btVersion} (${bt.moduleName})" else "Hidden"}\n") }
            header.append("\n")
        }
        reportContentToSave = header.toString() + tvDebugConsole.text.toString()
    }

    private fun exportAutoToUsb() {
        val fileName = "WiFi_Report_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.txt"
        Thread {
            try {
                val dirs = ContextCompat.getExternalFilesDirs(this, null)
                val targetDir = if (dirs.size > 1) dirs.lastOrNull() else getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
                if (targetDir != null && !targetDir.exists()) targetDir.mkdirs()
                val file = File(targetDir, fileName)
                file.writeText(reportContentToSave)
                runOnUiThread { Toast.makeText(this, getString(R.string.export_saved_auto, file.absolutePath), Toast.LENGTH_LONG).show() }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, getString(R.string.export_error, e.message), Toast.LENGTH_SHORT).show() }
            }
        }.start()
    }

    override fun onDestroy() { super.onDestroy(); dbLoggerHandler.removeCallbacks(dbLoggerRunnable); analyzer.stopBtDiscovery() }
    override fun onPause() { super.onPause(); scanHandler.removeCallbacks(scanRunnable); liveGraphHandler.removeCallbacks(liveGraphRunnable); analyzer.stopBtDiscovery() }
    override fun onResume() {
        super.onResume()
        if (currentTabIndex == 0) liveGraphHandler.post(liveGraphRunnable)
        if (currentTabIndex == 1) {
            if (currentScannerTab == 1) analyzer.startBtDiscovery()
            scanHandler.post(scanRunnable)
        }
    }

    private fun requestPermissionsIfNeeded() {
        val permissions = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        val missing = permissions.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) ActivityCompat.requestPermissions(this, missing.toTypedArray(), 100)
    }
}