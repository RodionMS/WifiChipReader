package com.example.wifichipreader

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
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

    // Вкладки
    private lateinit var tabHardware: Button
    private lateinit var tabScanner: Button
    private lateinit var layoutHardware: ScrollView
    private lateinit var layoutScanner: ScrollView

    // Контейнер для сетей
    private lateinit var containerNetworks: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        analyzer = WifiAnalyzer(this)

        initViews()
        requestPermissionsIfNeeded()
        setupTabs()

        findViewById<Button>(R.id.btnAnalyze).setOnClickListener { runHardwareScan() }
        findViewById<Button>(R.id.btnScanEther).setOnClickListener { runEtherScan() }

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
        }

        tabScanner.setOnClickListener {
            layoutHardware.visibility = View.GONE
            layoutScanner.visibility = View.VISIBLE
            tabScanner.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#03A9F4"))
            tabScanner.setTextColor(Color.BLACK)
            tabHardware.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#333333"))
            tabHardware.setTextColor(Color.WHITE)
            runEtherScan() // Автоматически сканируем при открытии
        }
    }

    private fun runHardwareScan() {
        val caps = analyzer.getHardwareCapabilities()
        val prefs = getSharedPreferences("WifiPrefs", Context.MODE_PRIVATE)

        val is5G = caps["5G"] ?: false
        val is6G = caps["6G"] ?: false

        findViewById<TextView>(R.id.tvBand5G).apply {
            text = "• 5 GHz Диапазон: " + if (is5G) "Поддерживается" else "Нет"
            setTextColor(Color.parseColor(if (is5G) "#4CAF50" else "#F44336"))
        }

        findViewById<TextView>(R.id.tvBand6G).apply {
            text = "• 6 GHz Диапазон: " + if (is6G) "Поддерживается" else "Нет"
            setTextColor(Color.parseColor(if (is6G) "#4CAF50" else "#888888"))
        }

        val hasWifi5 = is5G || prefs.getBoolean("has_wifi5", false)
        findViewById<TextView>(R.id.tvWifi4).text = "✓ WiFi 4 (802.11n) — Поддерживается"
        findViewById<TextView>(R.id.tvWifi4).setTextColor(Color.parseColor("#4CAF50"))

        findViewById<TextView>(R.id.tvWifi5).text = if (hasWifi5) "✓ WiFi 5 (802.11ac) — Поддерживается" else "✕ WiFi 5 — Не обнаружено"
        findViewById<TextView>(R.id.tvWifi5).setTextColor(Color.parseColor(if (hasWifi5) "#4CAF50" else "#888888"))
    }

    private fun runEtherScan() {
        containerNetworks.removeAllViews() // Очищаем старый список
        val networks = analyzer.scanEther()

        if (networks.isEmpty()) {
            val emptyTv = TextView(this).apply {
                text = "Сети не найдены. Убедитесь, что включена геолокация (GPS) и Wi-Fi."
                setTextColor(Color.parseColor("#AAAAAA"))
                textSize = 14f
            }
            containerNetworks.addView(emptyTv)
            return
        }

        // Динамически рисуем карточки для каждой сети
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