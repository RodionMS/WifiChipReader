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

    private lateinit var layoutHardware: ScrollView
    private lateinit var layoutScanner: ScrollView
    private lateinit var layoutDebug: ScrollView

    private lateinit var containerNetworks: LinearLayout
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

    private val scanHandler = Handler(Looper.getMainLooper())
    private val scanRunnable = object : Runnable {
        override fun run() {
            runEtherScan()
            scanHandler.postDelayed(this, 8000)
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

                    val pingText = if (lastPingMs >= 0) "$lastPingMs ms" else "N/A"
                    tvConnectionDetails.text = getString(R.string.label_details, stats.ssid, stats.securityType, stats.frequency.toString(), stats.linkSpeed.toString(), stats.rssi.toString(), pingText)
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
        // Применяем тему до отрисовки интерфейса
        val prefs = getSharedPreferences("WifiPrefs", Context.MODE_PRIVATE)
        val themeMode = prefs.getInt("AppTheme", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        AppCompatDelegate.setDefaultNightMode(themeMode)

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        AppLog.messages.clear()
        AppLog.i("App", "App Started")

        analyzer = WifiAnalyzer(this)

        initViews()
        requestPermissionsIfNeeded()
        setupTvFocusAnimations()
        setupTabs()
        setupTooltips()

        btnSettings.setOnClickListener { showSettingsDialog() }
        btnAnalyze.setOnClickListener { runHardwareScan() }
        btnExportReport.setOnClickListener { exportDebugReport() }
        btnShowQR.setOnClickListener { showQrDialog() }

        runHardwareScan()
    }

    private fun initViews() {
        tabHardware = findViewById(R.id.tabHardware)
        tabScanner = findViewById(R.id.tabScanner)
        tabDebug = findViewById(R.id.tabDebug)
        btnSettings = findViewById(R.id.btnSettings)

        layoutHardware = findViewById(R.id.layoutHardware)
        layoutScanner = findViewById(R.id.layoutScanner)
        layoutDebug = findViewById(R.id.layoutDebug)

        containerNetworks = findViewById(R.id.containerNetworks)
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

    private fun showSettingsDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 40, 50, 40)
        }

        var dialog: AlertDialog? = null

        // Блок языка
        val tvLang = TextView(this).apply {
            text = getString(R.string.language)
            textSize = 18f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            setPadding(0, 0, 0, 20)
        }
        layout.addView(tvLang)

        val langGroup = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val btnRu = Button(this).apply { text = "RU"; setOnClickListener { setAppLanguage("ru") } }
        val btnEn = Button(this).apply { text = "EN"; setOnClickListener { setAppLanguage("en") } }
        val btnZh = Button(this).apply { text = "中文"; setOnClickListener { setAppLanguage("zh") } }
        langGroup.addView(btnRu)
        langGroup.addView(btnEn)
        langGroup.addView(btnZh)
        layout.addView(langGroup)

        // Блок темы
        val tvTheme = TextView(this).apply {
            text = getString(R.string.theme)
            textSize = 18f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            setPadding(0, 40, 0, 20)
        }
        layout.addView(tvTheme)

        val themeGroup = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val btnDark = Button(this).apply {
            text = getString(R.string.theme_dark)
            setOnClickListener { setAppTheme(AppCompatDelegate.MODE_NIGHT_YES); dialog?.dismiss() }
        }
        val btnLight = Button(this).apply {
            text = getString(R.string.theme_light)
            setOnClickListener { setAppTheme(AppCompatDelegate.MODE_NIGHT_NO); dialog?.dismiss() }
        }
        themeGroup.addView(btnDark)
        themeGroup.addView(btnLight)
        layout.addView(themeGroup)

        dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.settings))
            .setView(layout)
            .setPositiveButton(getString(R.string.dialog_close)) { d, _ -> d.dismiss() }
            .show()
    }

    private fun setAppLanguage(langCode: String) {
        val prefs = getSharedPreferences("WifiPrefs", Context.MODE_PRIVATE)
        prefs.edit().putString("AppLang", langCode).apply()
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
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(getString(R.string.dialog_ok)) { dialog, _ -> dialog.dismiss() }
            .show()
    }

    private fun showQrDialog() {
        val report = lastReport
        val mem = lastMemReport
        if (report == null || mem == null) {
            Toast.makeText(this, getString(R.string.qr_need_scan), Toast.LENGTH_SHORT).show()
            return
        }

        val prefs = getSharedPreferences("WifiPrefs", Context.MODE_PRIVATE)
        val hasWifi5 = report.is5GSupported || prefs.getBoolean("has_wifi5", false)
        val hasWifi6 = report.currentStandard == "WiFi 6" || report.is6GSupported || prefs.getBoolean("has_wifi6", false)

        val json = """
            {
              "Device": "${Build.MANUFACTURER} ${Build.MODEL}",
              "Android": "${Build.VERSION.RELEASE}",
              "RAM_GB": ${String.format(Locale.US, "%.1f", mem.ramTotalGb)},
              "ROM_GB": ${String.format(Locale.US, "%.1f", mem.romTotalGb)},
              "is5GHz": ${report.is5GSupported},
              "is6GHz": ${report.is6GSupported},
              "WiFi5_ac": $hasWifi5,
              "WiFi6_ax": $hasWifi6,
              "Standard": "${report.currentStandard}"
            }
        """.trimIndent()

        try {
            val size = 600
            val bitMatrix = QRCodeWriter().encode(json, BarcodeFormat.QR_CODE, size, size)
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
            for (x in 0 until size) {
                for (y in 0 until size) {
                    bitmap.setPixel(x, y, if (bitMatrix.get(x, y)) Color.BLACK else Color.WHITE)
                }
            }

            val imageView = ImageView(this)
            imageView.setImageBitmap(bitmap)
            imageView.setPadding(32, 32, 32, 32)
            imageView.setBackgroundColor(Color.WHITE)

            AlertDialog.Builder(this)
                .setTitle(getString(R.string.qr_title))
                .setMessage(getString(R.string.qr_desc))
                .setView(imageView)
                .setPositiveButton(getString(R.string.dialog_close)) { dialog, _ -> dialog.dismiss() }
                .show()

        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.qr_error), Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupTvFocusAnimations() {
        val buttonFocusListener = View.OnFocusChangeListener { view, hasFocus ->
            if (hasFocus) {
                val border = android.graphics.drawable.GradientDrawable()
                border.setColor(Color.TRANSPARENT)
                border.setStroke(8, ContextCompat.getColor(this, R.color.border_focus))
                border.cornerRadius = 16f
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) { view.foreground = border }
                view.animate().scaleX(1.05f).scaleY(1.05f).translationZ(10f).setDuration(150).start()
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) { view.foreground = null }
                view.animate().scaleX(1.0f).scaleY(1.0f).translationZ(0f).setDuration(150).start()
            }
        }

        val blockFocusListener = View.OnFocusChangeListener { view, hasFocus ->
            if (hasFocus) {
                val border = android.graphics.drawable.GradientDrawable()
                border.setColor(Color.TRANSPARENT)
                border.setStroke(8, ContextCompat.getColor(this, R.color.border_focus))
                border.cornerRadius = 16f
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) { view.foreground = border }
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) { view.foreground = null }
            }
        }

        tabHardware.onFocusChangeListener = buttonFocusListener
        tabScanner.onFocusChangeListener = buttonFocusListener
        tabDebug.onFocusChangeListener = buttonFocusListener
        btnAnalyze.onFocusChangeListener = buttonFocusListener
        btnShowQR.onFocusChangeListener = buttonFocusListener
        btnExportReport.onFocusChangeListener = buttonFocusListener
        btnSettings.onFocusChangeListener = buttonFocusListener

        blockSystem.onFocusChangeListener = blockFocusListener
        blockHardware.onFocusChangeListener = blockFocusListener
        blockStandards.onFocusChangeListener = blockFocusListener
        blockConnection.onFocusChangeListener = blockFocusListener
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

        if (index == 0) {
            liveGraphHandler.removeCallbacks(liveGraphRunnable)
            liveGraphHandler.post(liveGraphRunnable)
        } else {
            liveGraphHandler.removeCallbacks(liveGraphRunnable)
        }

        if (index == 1) {
            scanHandler.removeCallbacks(scanRunnable)
            scanHandler.post(scanRunnable)
        } else {
            scanHandler.removeCallbacks(scanRunnable)
        }

        if (index == 2) {
            tvDebugConsole.text = getString(R.string.status_logs)
            Thread {
                val rawData = analyzer.getRawSystemData()
                runOnUiThread { tvDebugConsole.text = rawData }
            }.start()
        }
    }

    private fun runHardwareScan() {
        btnAnalyze.isEnabled = false
        btnAnalyze.text = getString(R.string.status_ping)

        Thread {
            val report = analyzer.getHardwareReport()
            val memReport = analyzer.getMemoryReport()
            val prefs = getSharedPreferences("WifiPrefs", Context.MODE_PRIVATE)

            runOnUiThread {
                lastPingMs = report.pingMs
                lastReport = report
                lastMemReport = memReport

                tvDeviceModel.text = getString(R.string.label_device, Build.MANUFACTURER.uppercase(), Build.MODEL)
                tvRam.text = getString(R.string.label_ram, String.format(Locale.US, "%.1f", memReport.ramTotalGb))
                tvRom.text = getString(R.string.label_rom, String.format(Locale.US, "%.1f", memReport.romTotalGb))

                findViewById<TextView>(R.id.tvBand5G).apply {
                    text = getString(R.string.label_5g, if (report.is5GSupported) getString(R.string.supported) else getString(R.string.not_supported))
                    setTextColor(ContextCompat.getColor(this@MainActivity, if (report.is5GSupported) R.color.accent_green else R.color.error_red))
                }
                findViewById<TextView>(R.id.tvBand6G).apply {
                    text = getString(R.string.label_6g, if (report.is6GSupported) getString(R.string.supported) else getString(R.string.not_supported))
                    setTextColor(ContextCompat.getColor(this@MainActivity, if (report.is6GSupported) R.color.accent_green else R.color.text_secondary))
                }

                var hasWifi5 = report.is5GSupported || prefs.getBoolean("has_wifi5", false)
                var hasWifi6 = report.currentStandard == "WiFi 6" || report.is6GSupported || prefs.getBoolean("has_wifi6", false)
                var hasWifi7 = report.currentStandard == "WiFi 7" || prefs.getBoolean("has_wifi7", false)

                prefs.edit().apply {
                    putBoolean("has_wifi5", hasWifi5)
                    putBoolean("has_wifi6", hasWifi6)
                    putBoolean("has_wifi7", hasWifi7)
                    apply()
                }

                val colorGreenId = R.color.accent_green
                val colorGrayId = R.color.text_secondary

                findViewById<TextView>(R.id.tvWifi1).apply { text = getString(R.string.label_wifi_yes, "1 (802.11b)", getString(R.string.supported)); setTextColor(ContextCompat.getColor(this@MainActivity, colorGreenId)) }
                findViewById<TextView>(R.id.tvWifi3).apply { text = getString(R.string.label_wifi_yes, "3 (802.11g)", getString(R.string.supported)); setTextColor(ContextCompat.getColor(this@MainActivity, colorGreenId)) }
                findViewById<TextView>(R.id.tvWifi2).apply {
                    text = if (report.is5GSupported) getString(R.string.label_wifi_yes, "2 (802.11a)", getString(R.string.supported)) else getString(R.string.label_wifi_no, "2 (802.11a)", getString(R.string.not_supported))
                    setTextColor(ContextCompat.getColor(this@MainActivity, if (report.is5GSupported) colorGreenId else colorGrayId))
                }
                findViewById<TextView>(R.id.tvWifi4).apply { text = getString(R.string.label_wifi_yes, "4 (802.11n)", getString(R.string.supported)); setTextColor(ContextCompat.getColor(this@MainActivity, colorGreenId)) }
                findViewById<TextView>(R.id.tvWifi5).apply {
                    text = if (hasWifi5) getString(R.string.label_wifi_yes, "5 (802.11ac)", getString(R.string.supported)) else getString(R.string.label_wifi_no, "5 (802.11ac)", getString(R.string.not_detected))
                    setTextColor(ContextCompat.getColor(this@MainActivity, if (hasWifi5) colorGreenId else colorGrayId))
                }
                findViewById<TextView>(R.id.tvWifi6).apply {
                    text = if (hasWifi6) getString(R.string.label_wifi_yes, "6 (802.11ax)", getString(R.string.confirmed)) else getString(R.string.label_wifi_maybe, "6 (802.11ax)", getString(R.string.not_detected))
                    setTextColor(ContextCompat.getColor(this@MainActivity, if (hasWifi6) colorGreenId else colorGrayId))
                }
                findViewById<TextView>(R.id.tvWifi7).apply {
                    text = if (hasWifi7) getString(R.string.label_wifi_yes, "7 (802.11be)", getString(R.string.confirmed)) else getString(R.string.label_wifi_no, "7 (802.11be)", getString(R.string.not_detected))
                    setTextColor(ContextCompat.getColor(this@MainActivity, if (hasWifi7) colorGreenId else colorGrayId))
                }

                btnAnalyze.isEnabled = true
                btnAnalyze.text = getString(R.string.btn_scan)
            }
        }.start()
    }

    private fun runEtherScan() {
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val isLocEnabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) locationManager.isLocationEnabled else (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) || locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER))

        containerNetworks.removeAllViews()

        if (!isLocEnabled) {
            val emptyTv = TextView(this).apply {
                text = getString(R.string.status_gps_off)
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.error_red))
                textSize = 16f
                gravity = Gravity.CENTER
                setPadding(0, 50, 0, 0)
            }
            containerNetworks.addView(emptyTv)
            return
        }

        val networks = analyzer.scanEther()

        if (networks.isEmpty()) {
            val emptyTv = TextView(this).apply {
                text = getString(R.string.status_scanning)
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
                textSize = 14f
            }
            containerNetworks.addView(emptyTv)
            return
        }

        for (net in networks) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.bg_card))
                setPadding(32, 24, 32, 24)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, 16) }
            }

            val title = TextView(this).apply {
                text = net.ssid
                val colorId = if (net.ssid == "[Hidden]" || net.ssid == "[Скрытая сеть]") R.color.error_red else R.color.warn_yellow
                setTextColor(ContextCompat.getColor(this@MainActivity, colorId))
                textSize = 18f
                setTypeface(null, android.graphics.Typeface.BOLD)
            }

            val macAndSec = TextView(this).apply {
                text = "MAC: ${net.bssid} (${net.vendor})\n\uD83D\uDD12 ${net.security}"
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
                textSize = 13f
            }

            val freqAndSignal = TextView(this).apply {
                text = getString(R.string.label_freq_signal, net.frequency.toString(), net.channel.toString(), net.rssi.toString())
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent_blue))
                textSize = 14f
                setPadding(0, 8, 0, 0)
            }

            card.addView(title)
            card.addView(macAndSec)
            card.addView(freqAndSignal)
            containerNetworks.addView(card)
        }
    }

    private fun exportDebugReport() {
        val report = lastReport
        val mem = lastMemReport

        val header = StringBuilder()
        header.append("=== WIFI HARDWARE PRO ===\n")
        header.append("Date: ${SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.US).format(Date())}\n")
        header.append("Device: ${Build.MANUFACTURER.uppercase()} ${Build.MODEL}\n\n")

        if (mem != null) {
            header.append("--- MEMORY ---\n")
            header.append("RAM: ${String.format(Locale.US, "%.1f", mem.ramTotalGb)} GB\n")
            header.append("ROM: ${String.format(Locale.US, "%.1f", mem.romTotalGb)} GB\n\n")
        }

        if (report != null) {
            header.append("--- WIFI ---\n")
            header.append("5 GHz: ${report.is5GSupported}\n")
            header.append("6 GHz: ${report.is6GSupported}\n\n")
        }

        val rawData = tvDebugConsole.text.toString()
        val fullReport = header.toString() + rawData
        val fileName = "WiFi_Report_${System.currentTimeMillis()}.txt"

        try {
            val dir = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
            if (dir != null && !dir.exists()) dir.mkdirs()

            val file = File(dir, fileName)
            file.writeText(fullReport)

            Toast.makeText(this, getString(R.string.export_saved, file.absolutePath), Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.export_error, e.message), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onPause() {
        super.onPause()
        scanHandler.removeCallbacks(scanRunnable)
        liveGraphHandler.removeCallbacks(liveGraphRunnable)
    }

    override fun onResume() {
        super.onResume()
        if (currentTabIndex == 0) liveGraphHandler.post(liveGraphRunnable)
        if (currentTabIndex == 1) scanHandler.post(scanRunnable)
    }

    private fun requestPermissionsIfNeeded() {
        val permissions = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        val missingPermissions = permissions.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missingPermissions.toTypedArray(), 100)
        }
    }
}