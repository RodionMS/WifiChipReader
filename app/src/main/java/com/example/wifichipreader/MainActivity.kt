package com.example.wifichipreader

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
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

    private val scanHandler = Handler(Looper.getMainLooper())
    private val scanRunnable = object : Runnable {
        override fun run() {
            runEtherScan()
            scanHandler.postDelayed(this, 8000)
        }
    }
    private var isScannerActive = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        AppLog.messages.clear()
        AppLog.i("App", "Приложение запущено")

        analyzer = WifiAnalyzer(this)

        initViews()
        requestPermissionsIfNeeded()
        setupTvFocusAnimations() // Устанавливаем ТВ рамки (Foreground)
        setupTabs()              // Запускаем старую надежную логику вкладок

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
    }

    // ИДЕАЛЬНАЯ ЛОГИКА ТВ ФОКУСА (Без поломки цветов вкладок)
    private fun setupTvFocusAnimations() {
        val focusListener = View.OnFocusChangeListener { view, hasFocus ->
            if (hasFocus) {
                // Создаем белую рамку
                val border = android.graphics.drawable.GradientDrawable()
                border.setColor(Color.TRANSPARENT)
                border.setStroke(8, Color.WHITE)
                border.cornerRadius = 16f

                // Накладываем поверх элемента
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    view.foreground = border
                }
                view.animate().scaleX(1.05f).scaleY(1.05f).translationZ(10f).setDuration(150).start()
            } else {
                // Убираем рамку
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    view.foreground = null
                }
                view.animate().scaleX(1.0f).scaleY(1.0f).translationZ(0f).setDuration(150).start()
            }
        }

        tabHardware.onFocusChangeListener = focusListener
        tabScanner.onFocusChangeListener = focusListener
        tabDebug.onFocusChangeListener = focusListener
        btnAnalyze.onFocusChangeListener = focusListener
        btnExportReport.onFocusChangeListener = focusListener
    }

    // ВОЗВРАТ К СТАРОЙ, РАБОЧЕЙ ЛОГИКЕ ВКЛАДОК
    private fun setupTabs() {
        tabHardware.setOnClickListener { switchTab(0) }
        tabScanner.setOnClickListener { switchTab(1) }
        tabDebug.setOnClickListener { switchTab(2) }
        switchTab(0)
    }

    private fun switchTab(index: Int) {
        layoutHardware.visibility = if (index == 0) View.VISIBLE else View.GONE
        layoutScanner.visibility = if (index == 1) View.VISIBLE else View.GONE
        layoutDebug.visibility = if (index == 2) View.VISIBLE else View.GONE

        // Цвета назначаются жестко и больше не конфликтуют с фокусом
        tabHardware.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor(if (index == 0) "#4CAF50" else "#333333"))
        tabHardware.setTextColor(if (index == 0) Color.BLACK else Color.WHITE)

        tabScanner.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor(if (index == 1) "#03A9F4" else "#333333"))
        tabScanner.setTextColor(if (index == 1) Color.BLACK else Color.WHITE)

        tabDebug.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor(if (index == 2) "#673AB7" else "#333333"))
        tabDebug.setTextColor(if (index == 2) Color.WHITE else Color.WHITE)

        if (index == 1) startAutoScan() else stopAutoScan()

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

                if (report.hasConnection) {
                    findViewById<ProgressBar>(R.id.pbQuality).progress = report.qualityScore

                    val qColor = if (report.qualityScore > 75) "#4CAF50" else if (report.qualityScore > 40) "#FFC107" else "#F44336"
                    findViewById<TextView>(R.id.tvQualityScore).apply {
                        text = "Качество связи: ${report.qualityScore}%"
                        setTextColor(Color.parseColor(qColor))
                    }

                    val details = "• Сеть: ${report.ssid}\n• Шифрование: ${report.securityType}\n• Частота: ${report.frequency} MHz\n• Теорет. линк: ${report.linkSpeed} Mbps\n• Сигнал: ${report.rssi} dBm\n• Пинг: ${if (report.pingMs >= 0) "${report.pingMs} ms" else "N/A"}"
                    findViewById<TextView>(R.id.tvConnectionDetails).text = details
                } else {
                    findViewById<ProgressBar>(R.id.pbQuality).progress = 0
                    findViewById<TextView>(R.id.tvQualityScore).text = "Нет активного подключения"
                    findViewById<TextView>(R.id.tvConnectionDetails).text = "Информация недоступна"
                }

                btnAnalyze.isEnabled = true
                btnAnalyze.text = "Просканировать систему"
            }
        }.start()
    }

    private fun isLocationEnabled(): Boolean {
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            locationManager.isLocationEnabled
        } else {
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) || locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }
    }

    private fun startAutoScan() {
        isScannerActive = true
        runEtherScan()
        scanHandler.removeCallbacks(scanRunnable)
        scanHandler.postDelayed(scanRunnable, 8000)
    }

    private fun stopAutoScan() {
        isScannerActive = false
        scanHandler.removeCallbacks(scanRunnable)
    }

    private fun runEtherScan() {
        containerNetworks.removeAllViews()

        if (!isLocationEnabled()) {
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
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(0, 0, 0, 16)
                }
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
        stopAutoScan()
    }

    override fun onResume() {
        super.onResume()
        if (isScannerActive) startAutoScan()
    }

    private fun requestPermissionsIfNeeded() {
        val permissions = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), 100)
        }
    }
}