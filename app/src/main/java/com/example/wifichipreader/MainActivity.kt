package com.example.wifichipreader

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.ColorStateList
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
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var analyzer: WifiAnalyzer

    private lateinit var tabHardware: Button
    private lateinit var tabScanner: Button
    private lateinit var tabDebug: Button

    private lateinit var layoutHardware: ScrollView
    private lateinit var layoutScanner: ScrollView
    private lateinit var layoutDebug: ScrollView

    private lateinit var containerNetworks: LinearLayout
    private lateinit var btnAnalyze: Button
    private lateinit var btnExportReport: Button
    private lateinit var tvDebugConsole: TextView

    private lateinit var blockHardware: LinearLayout
    private lateinit var blockStandards: LinearLayout
    private lateinit var blockConnection: LinearLayout
    private lateinit var graphSignal: SignalGraphView
    private lateinit var tvConnectionDetails: TextView

    private var currentTabIndex = 0
    private var lastPingMs = -1L

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

                    val qColor = if (stats.qualityScore > 75) "#4CAF50" else if (stats.qualityScore > 40) "#FFC107" else "#F44336"
                    findViewById<TextView>(R.id.tvQualityScore).apply {
                        text = "Качество связи: ${stats.qualityScore}%"
                        setTextColor(Color.parseColor(qColor))
                    }

                    val details = "• Сеть: ${stats.ssid}\n• Шифрование: ${stats.securityType}\n• Частота: ${stats.frequency} MHz\n• Теорет. линк: ${stats.linkSpeed} Mbps\n• Сигнал: ${stats.rssi} dBm\n• Пинг: ${if (lastPingMs >= 0) "$lastPingMs ms" else "N/A"}"
                    tvConnectionDetails.text = details
                } else {
                    graphSignal.visibility = View.GONE
                    findViewById<ProgressBar>(R.id.pbQuality).progress = 0
                    findViewById<TextView>(R.id.tvQualityScore).text = "Нет активного подключения"
                    tvConnectionDetails.text = "Информация недоступна"
                }
            }
            liveGraphHandler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        AppLog.messages.clear()
        AppLog.i("App", "Приложение запущено")

        analyzer = WifiAnalyzer(this)

        initViews()
        requestPermissionsIfNeeded()
        setupTvFocusAnimations()
        setupTabs()
        setupTooltips()

        btnAnalyze.setOnClickListener { runHardwareScan() }
        btnExportReport.setOnClickListener { exportDebugReport() }

        runHardwareScan()
    }

    private fun initViews() {
        tabHardware = findViewById(R.id.tabHardware)
        tabScanner = findViewById(R.id.tabScanner)
        tabDebug = findViewById(R.id.tabDebug)

        layoutHardware = findViewById(R.id.layoutHardware)
        layoutScanner = findViewById(R.id.layoutScanner)
        layoutDebug = findViewById(R.id.layoutDebug)

        containerNetworks = findViewById(R.id.containerNetworks)
        btnAnalyze = findViewById(R.id.btnAnalyze)
        btnExportReport = findViewById(R.id.btnExportReport)
        tvDebugConsole = findViewById(R.id.tvDebugConsole)

        blockHardware = findViewById(R.id.blockHardware)
        blockStandards = findViewById(R.id.blockStandards)
        blockConnection = findViewById(R.id.blockConnection)
        graphSignal = findViewById(R.id.graphSignal)
        tvConnectionDetails = findViewById(R.id.tvConnectionDetails)
    }

    private fun setupTooltips() {
        blockHardware.setOnClickListener {
            showInfoDialog(
                "Аппаратные диапазоны",
                "• 5 GHz: Высокая скорость, но малый радиус (сигнал плохо проходит сквозь стены).\n\n• 6 GHz: Свободный от помех диапазон (Wi-Fi 6E и Wi-Fi 7)."
            )
        }

        blockStandards.setOnClickListener {
            showInfoDialog(
                "Стандарты связи",
                "• Wi-Fi 4 (n): Базовый стандарт.\n• Wi-Fi 5 (ac): Отлично для 4K.\n• Wi-Fi 6 (ax): Не боится загруженного эфира.\n• Wi-Fi 7 (be): Каналы 320 МГц, сверхвысокие скорости."
            )
        }

        blockConnection.setOnClickListener {
            showInfoDialog(
                "Соединение",
                "Весь этот блок обновляется в реальном времени.\nГрафик показывает живое изменение уровня сигнала (RSSI). Идеальное значение: от -30 до -50 dBm. Если график падает ниже -75 dBm — возможны обрывы сети."
            )
        }
    }

    private fun showInfoDialog(title: String, message: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("Понятно") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    // Идеальная анимация фокуса для ТВ (Через Foreground, без поломки цветов)
    private fun setupTvFocusAnimations() {
        // Анимация для интерактивных кнопок (Увеличиваются + Белая рамка)
        val buttonFocusListener = View.OnFocusChangeListener { view, hasFocus ->
            if (hasFocus) {
                val border = android.graphics.drawable.GradientDrawable()
                border.setColor(Color.TRANSPARENT)
                border.setStroke(8, Color.WHITE)
                border.cornerRadius = 16f
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) { view.foreground = border }
                view.animate().scaleX(1.05f).scaleY(1.05f).translationZ(10f).setDuration(150).start()
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) { view.foreground = null }
                view.animate().scaleX(1.0f).scaleY(1.0f).translationZ(0f).setDuration(150).start()
            }
        }

        // Анимация для информационных блоков (Только рамка, без увеличения)
        val blockFocusListener = View.OnFocusChangeListener { view, hasFocus ->
            if (hasFocus) {
                val border = android.graphics.drawable.GradientDrawable()
                border.setColor(Color.TRANSPARENT)
                border.setStroke(8, Color.WHITE)
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
        btnExportReport.onFocusChangeListener = buttonFocusListener

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

    // Родная перекраска вкладок без костылей
    private fun switchTab(index: Int) {
        currentTabIndex = index

        layoutHardware.visibility = if (index == 0) View.VISIBLE else View.GONE
        layoutScanner.visibility = if (index == 1) View.VISIBLE else View.GONE
        layoutDebug.visibility = if (index == 2) View.VISIBLE else View.GONE

        tabHardware.backgroundTintList = ColorStateList.valueOf(Color.parseColor(if (index == 0) "#4CAF50" else "#333333"))
        tabHardware.setTextColor(if (index == 0) Color.BLACK else Color.WHITE)

        tabScanner.backgroundTintList = ColorStateList.valueOf(Color.parseColor(if (index == 1) "#03A9F4" else "#333333"))
        tabScanner.setTextColor(if (index == 1) Color.BLACK else Color.WHITE)

        tabDebug.backgroundTintList = ColorStateList.valueOf(Color.parseColor(if (index == 2) "#673AB7" else "#333333"))
        tabDebug.setTextColor(Color.WHITE)

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
            tvDebugConsole.text = "Сбор логов системы..."
            Thread {
                val rawData = analyzer.getRawSystemData()
                runOnUiThread { tvDebugConsole.text = rawData }
            }.start()
        }
    }

    private fun runHardwareScan() {
        btnAnalyze.isEnabled = false
        btnAnalyze.text = "Сканирование системы (Пинг...)"

        Thread {
            val report = analyzer.getHardwareReport()
            val prefs = getSharedPreferences("WifiPrefs", Context.MODE_PRIVATE)

            runOnUiThread {
                lastPingMs = report.pingMs

                findViewById<TextView>(R.id.tvBand5G).apply {
                    text = "• 5 GHz Диапазон: " + if (report.is5GSupported) "Поддерживается" else "Нет"
                    setTextColor(Color.parseColor(if (report.is5GSupported) "#4CAF50" else "#F44336"))
                }
                findViewById<TextView>(R.id.tvBand6G).apply {
                    text = "• 6 GHz Диапазон: " + if (report.is6GSupported) "Поддерживается" else "Нет"
                    setTextColor(Color.parseColor(if (report.is6GSupported) "#4CAF50" else "#888888"))
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

                val colorGreen = Color.parseColor("#4CAF50")
                val colorGray = Color.parseColor("#888888")

                findViewById<TextView>(R.id.tvWifi1).apply { text = "✓ WiFi 1 (802.11b) — Поддерживается"; setTextColor(colorGreen) }
                findViewById<TextView>(R.id.tvWifi3).apply { text = "✓ WiFi 3 (802.11g) — Поддерживается"; setTextColor(colorGreen) }
                findViewById<TextView>(R.id.tvWifi2).apply {
                    text = if (report.is5GSupported) "✓ WiFi 2 (802.11a) — Поддерживается" else "✕ WiFi 2 (802.11a) — Нет 5GHz"
                    setTextColor(if (report.is5GSupported) colorGreen else colorGray)
                }
                findViewById<TextView>(R.id.tvWifi4).apply { text = "✓ WiFi 4 (802.11n) — Поддерживается"; setTextColor(colorGreen) }
                findViewById<TextView>(R.id.tvWifi5).apply {
                    text = if (hasWifi5) "✓ WiFi 5 (802.11ac) — Поддерживается" else "✕ WiFi 5 — Не обнаружено"
                    setTextColor(if (hasWifi5) colorGreen else colorGray)
                }
                findViewById<TextView>(R.id.tvWifi6).apply {
                    text = if (hasWifi6) "✓ WiFi 6 (802.11ax) — Подтверждено" else "• WiFi 6 (802.11ax) — Не зафиксировано"
                    setTextColor(if (hasWifi6) colorGreen else colorGray)
                }
                findViewById<TextView>(R.id.tvWifi7).apply {
                    text = if (hasWifi7) "✓ WiFi 7 (802.11be) — Подтверждено" else "✕ WiFi 7 (802.11be) — Не обнаружено"
                    setTextColor(if (hasWifi7) colorGreen else colorGray)
                }

                btnAnalyze.isEnabled = true
                btnAnalyze.text = "Просканировать систему"
            }
        }.start()
    }

    private fun runEtherScan() {
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val isLocEnabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) locationManager.isLocationEnabled else (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) || locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER))

        containerNetworks.removeAllViews()

        if (!isLocEnabled) {
            val emptyTv = TextView(this).apply {
                text = "ВНИМАНИЕ: Геолокация (GPS) выключена!\n\nВключите её для сканирования сетей."
                setTextColor(Color.parseColor("#F44336"))
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
                text = "Идет сканирование (сетей пока не найдено)..."
                setTextColor(Color.parseColor("#AAAAAA"))
                textSize = 14f
            }
            containerNetworks.addView(emptyTv)
            return
        }

        for (net in networks) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor("#1E1E1E"))
                setPadding(32, 24, 32, 24)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, 16) }
            }

            val title = TextView(this).apply {
                text = net.ssid
                setTextColor(Color.parseColor(if (net.ssid == "[Скрытая сеть]") "#F44336" else "#FFC107"))
                textSize = 18f
                setTypeface(null, android.graphics.Typeface.BOLD)
            }

            val macAndSec = TextView(this).apply {
                text = "MAC: ${net.bssid} (${net.vendor})\n\uD83D\uDD12 ${net.security}"
                setTextColor(Color.parseColor("#AAAAAA"))
                textSize = 13f
            }

            val freqAndSignal = TextView(this).apply {
                text = "Freq: ${net.frequency}MHz  |  CH ${net.channel}  |  Уровень: ${net.rssi} dBm"
                setTextColor(Color.parseColor("#03A9F4"))
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
        val logData = tvDebugConsole.text.toString()
        val fileName = "WiFi_Debug_${System.currentTimeMillis()}.txt"

        try {
            val dir = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
            if (dir != null && !dir.exists()) dir.mkdirs()

            val file = File(dir, fileName)
            file.writeText(logData)

            Toast.makeText(this, "Отчет сохранен:\n${file.absolutePath}", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Ошибка экспорта: ${e.message}", Toast.LENGTH_SHORT).show()
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