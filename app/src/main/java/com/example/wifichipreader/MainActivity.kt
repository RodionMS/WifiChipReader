package com.example.wifichipreader

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var analyzer: WifiAnalyzer

    // UI Вкладок
    private lateinit var tabHardware: Button
    private lateinit var tabScanner: Button
    private lateinit var layoutHardware: ScrollView
    private lateinit var layoutScanner: ScrollView
    private lateinit var containerNetworks: LinearLayout

    // Таймер для автообновления
    private val scanHandler = Handler(Looper.getMainLooper())
    private val scanRunnable = object : Runnable {
        override fun run() {
            runEtherScan()
            scanHandler.postDelayed(this, 3000) // Повтор каждые 3 секунды
        }
    }

    private var isScannerActive = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        analyzer = WifiAnalyzer(this)

        initViews()
        requestPermissionsIfNeeded()
        setupTabs()

        findViewById<Button>(R.id.btnAnalyze).setOnClickListener { runHardwareScan() }
        runHardwareScan()
    }

    private fun initViews() {
        tabHardware = findViewById(R.id.tabHardware)
        tabScanner = findViewById(R.id.tabScanner)
        layoutHardware = findViewById(R.id.layoutHardware)
        layoutScanner = findViewById(R.id.layoutScanner)
        containerNetworks = findViewById(R.id.containerNetworks)
    }

    private fun setupTabs() {
        tabHardware.setOnClickListener {
            layoutHardware.visibility = View.VISIBLE
            layoutScanner.visibility = View.GONE
            tabHardware.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#4CAF50"))
            tabHardware.setTextColor(Color.BLACK)
            tabScanner.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#333333"))
            tabScanner.setTextColor(Color.WHITE)

            stopAutoScan() // Выключаем сканер эфира для экономии батареи
        }

        tabScanner.setOnClickListener {
            layoutHardware.visibility = View.GONE
            layoutScanner.visibility = View.VISIBLE
            tabScanner.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#03A9F4"))
            tabScanner.setTextColor(Color.BLACK)
            tabHardware.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#333333"))
            tabHardware.setTextColor(Color.WHITE)

            startAutoScan() // Запускаем автообновление
        }
    }

    private fun runHardwareScan() {
        val report = analyzer.getHardwareReport()
        val prefs = getSharedPreferences("WifiPrefs", Context.MODE_PRIVATE)

        // Аппаратные диапазоны
        findViewById<TextView>(R.id.tvBand5G).apply {
            text = "• 5 GHz Диапазон: " + if (report.is5GSupported) "Поддерживается" else "Нет"
            setTextColor(Color.parseColor(if (report.is5GSupported) "#4CAF50" else "#F44336"))
        }

        findViewById<TextView>(R.id.tvBand6G).apply {
            text = "• 6 GHz Диапазон: " + if (report.is6GSupported) "Поддерживается" else "Нет"
            setTextColor(Color.parseColor(if (report.is6GSupported) "#4CAF50" else "#888888"))
        }

        // Логика памяти для Wi-Fi 5, 6, 7
        var hasWifi5 = report.is5GSupported || prefs.getBoolean("has_wifi5", false)
        var hasWifi6 = report.currentStandard == "WiFi 6" || report.is6GSupported || prefs.getBoolean("has_wifi6", false)
        var hasWifi7 = report.currentStandard == "WiFi 7" || prefs.getBoolean("has_wifi7", false)

        prefs.edit().apply {
            putBoolean("has_wifi5", hasWifi5)
            putBoolean("has_wifi6", hasWifi6)
            putBoolean("has_wifi7", hasWifi7)
            apply()
        }

        // Отрисовка всех 7 поколений
        val colorGreen = Color.parseColor("#4CAF50")
        val colorGray = Color.parseColor("#888888")

        // Стандарты 1 (b) и 3 (g) поддерживают все современные устройства
        findViewById<TextView>(R.id.tvWifi1).apply { text = "✓ WiFi 1 (802.11b) — Поддерживается"; setTextColor(colorGreen) }
        findViewById<TextView>(R.id.tvWifi3).apply { text = "✓ WiFi 3 (802.11g) — Поддерживается"; setTextColor(colorGreen) }

        // Стандарт 2 (a) работает только на 5GHz
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
    }

    // Управление автосканером
    private fun startAutoScan() {
        isScannerActive = true
        runEtherScan() // Моментальный скан при открытии
        scanHandler.removeCallbacks(scanRunnable)
        scanHandler.postDelayed(scanRunnable, 3000)
    }

    private fun stopAutoScan() {
        isScannerActive = false
        scanHandler.removeCallbacks(scanRunnable)
    }

    private fun runEtherScan() {
        val networks = analyzer.scanEther()
        containerNetworks.removeAllViews()

        if (networks.isEmpty()) {
            val emptyTv = TextView(this).apply {
                text = "Идет сканирование... Убедитесь, что включена геолокация (GPS)."
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

                val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                params.setMargins(0, 0, 0, 16)
                layoutParams = params
            }

            val title = TextView(this).apply {
                text = net.ssid
                setTextColor(Color.parseColor(if (net.ssid == "[Скрытая сеть]") "#F44336" else "#FFC107"))
                textSize = 18f
                setTypeface(null, android.graphics.Typeface.BOLD)
            }

            val macAndSec = TextView(this).apply {
                text = "MAC: ${net.bssid}  |  \uD83D\uDD12 ${net.security}"
                setTextColor(Color.parseColor("#AAAAAA"))
                textSize = 12f
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

    // Останавливаем таймер при сворачивании приложения
    override fun onPause() {
        super.onPause()
        stopAutoScan()
    }

    // Запускаем таймер при возврате в приложение (если открыта вкладка сканера)
    override fun onResume() {
        super.onResume()
        if (isScannerActive) {
            startAutoScan()
        }
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