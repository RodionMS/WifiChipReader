package com.example.wifichipreader

import android.content.Context
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetAddress
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppLog {
    val messages = java.lang.StringBuilder()

    private fun log(level: String, tag: String, msg: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        messages.append("[$time] [$level] $tag: $msg\n")
    }

    fun e(tag: String, msg: String) = log("ERROR", tag, msg)
    fun i(tag: String, msg: String) = log("INFO", tag, msg)
}

class WifiAnalyzer(private val context: Context) {

    data class HardwareReport(
        val is5GSupported: Boolean,
        val is6GSupported: Boolean,
        val currentStandard: String,
        val hasConnection: Boolean,
        val ssid: String,
        val frequency: Int,
        val linkSpeed: Int,
        val rssi: Int,
        val securityType: String,
        val pingMs: Long,
        val qualityScore: Int
    )

    data class NetworkInfo(
        val ssid: String,
        val bssid: String,
        val vendor: String,
        val rssi: Int,
        val frequency: Int,
        val channel: Int,
        val security: String
    )

    // Расширенная модель для обновления всего блока в реальном времени
    data class LiveStats(
        val hasConnection: Boolean,
        val ssid: String,
        val securityType: String,
        val frequency: Int,
        val rssi: Int,
        val linkSpeed: Int,
        val qualityScore: Int
    )

    fun getHardwareReport(): HardwareReport {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

        val is5G = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) wifiManager.is5GHzBandSupported else true
        val is6G = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) wifiManager.is6GHzBandSupported else false

        val wifiInfo = wifiManager.connectionInfo
        val freq = wifiInfo.frequency
        val hasConnection = freq > 0 && wifiInfo.ssid != null && wifiInfo.ssid != "<unknown ssid>"

        var currentStandard = ""
        if (hasConnection && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            currentStandard = when (wifiInfo.wifiStandard) {
                ScanResult.WIFI_STANDARD_11AX -> "WiFi 6"
                ScanResult.WIFI_STANDARD_11BE -> "WiFi 7"
                else -> ""
            }
        }

        val rssi = wifiInfo.rssi
        val linkSpeed = wifiInfo.linkSpeed
        var pingMs = -1L
        var securityType = "Открытая сеть"
        var quality = 0

        if (hasConnection) {
            pingMs = measurePing("8.8.8.8")
            quality = calculateQualityScore(rssi, linkSpeed)

            val currentSsid = wifiInfo.ssid?.replace("\"", "") ?: ""
            try {
                val matched = wifiManager.scanResults.find { it.SSID == currentSsid }
                if (matched != null) securityType = getSecurityString(matched.capabilities)
            } catch (e: Exception) {}
        }

        return HardwareReport(
            is5G, is6G, currentStandard, hasConnection,
            wifiInfo.ssid?.replace("\"", "") ?: "",
            freq, linkSpeed, rssi, securityType, pingMs, quality
        )
    }

    // Мгновенный сбор всех данных для графика и текстового блока (без пинга)
    fun getLiveStats(): LiveStats {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val wifiInfo = wifiManager.connectionInfo
        val freq = wifiInfo.frequency
        val hasConnection = freq > 0 && wifiInfo.ssid != null && wifiInfo.ssid != "<unknown ssid>"

        var ssid = ""
        var securityType = "Открытая сеть"
        var quality = 0

        if (hasConnection) {
            ssid = wifiInfo.ssid?.replace("\"", "") ?: ""
            quality = calculateQualityScore(wifiInfo.rssi, wifiInfo.linkSpeed)
            try {
                val matched = wifiManager.scanResults.find { it.SSID == ssid }
                if (matched != null) securityType = getSecurityString(matched.capabilities)
            } catch (e: Exception) {}
        }

        return LiveStats(hasConnection, ssid, securityType, freq, wifiInfo.rssi, wifiInfo.linkSpeed, quality)
    }

    @Suppress("DEPRECATION")
    fun scanEther(): List<NetworkInfo> {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val results = mutableListOf<NetworkInfo>()

        try {
            wifiManager.startScan()
            val scanResults = wifiManager.scanResults
            for (scan in scanResults) {
                val ssid = if (scan.SSID.isNullOrEmpty()) "[Скрытая сеть]" else scan.SSID
                results.add(
                    NetworkInfo(
                        ssid = ssid,
                        bssid = scan.BSSID ?: "Неизвестно",
                        vendor = getVendorFromMac(scan.BSSID ?: ""),
                        rssi = scan.level,
                        frequency = scan.frequency,
                        channel = calculateChannel(scan.frequency),
                        security = getSecurityString(scan.capabilities)
                    )
                )
            }
        } catch (e: Exception) {
            AppLog.e("EtherScan", "Ошибка сканирования: ${e.message}")
        }

        return results.sortedByDescending { it.rssi }
    }

    private fun getVendorFromMac(mac: String): String {
        val cleanMac = mac.uppercase().replace(":", "")
        if (cleanMac.length < 6) return "Неизвестно"

        val secondChar = cleanMac[1]
        if (secondChar == '2' || secondChar == '6' || secondChar == 'A' || secondChar == 'E') {
            return "Случайный MAC (Hotspot)"
        }

        val oui = cleanMac.substring(0, 6)
        return when (oui) {
            "CCBBFE", "001E10", "4846FB", "A4933F", "00464B" -> "Huawei"
            "503EAA", "C0C9E3", "E894F6", "003192", "30B5C2", "68FF7B" -> "TP-Link"
            "04BF6D", "04D4C4", "107B44", "14D64D", "1C5F2B" -> "Asus"
            "001DD8", "048D38", "50D4F7", "C46E1F" -> "Keenetic"
            "286C07", "34CE00", "7811DC", "8C10D4" -> "Xiaomi"
            "001E8C", "00259E", "0030EA", "00D0D0", "C864C7" -> "D-Link"
            "0019CB", "00223F", "002511", "081075" -> "ZTE"
            "000C42", "4C5E0C", "D4CA6D" -> "MikroTik"
            "0012A9", "0014D8", "0495E6" -> "Tenda"
            "00A0F8", "082697", "480033" -> "Mercusys"
            "107C61", "000393", "000A27" -> "Apple"
            else -> "Неизвестно"
        }
    }

    private fun calculateQualityScore(rssi: Int, linkSpeed: Int): Int {
        var score = 100
        if (rssi < -50) score -= (rssi + 50) * -2
        if (linkSpeed in 1..99) score -= 10
        if (linkSpeed in 1..29) score -= 10
        return score.coerceIn(0, 100)
    }

    private fun calculateChannel(freq: Int): Int {
        return when {
            freq == 2484 -> 14
            freq in 2412..2472 -> (freq - 2407) / 5
            freq in 5170..5825 -> (freq - 5000) / 5
            freq in 5925..7125 -> (freq - 5950) / 5
            else -> 0
        }
    }

    private fun getSecurityString(capabilities: String): String {
        return when {
            capabilities.contains("WPA3") -> "WPA3"
            capabilities.contains("WPA2") -> "WPA2-PSK"
            capabilities.contains("WPA") -> "WPA-PSK"
            capabilities.contains("WEP") -> "WEP"
            else -> "Открытая сеть"
        }
    }

    private fun measurePing(host: String): Long {
        return try {
            val startTime = System.currentTimeMillis()
            val reachable = InetAddress.getByName(host).isReachable(1500)
            if (reachable) System.currentTimeMillis() - startTime else -1L
        } catch (e: Exception) {
            AppLog.e("Ping", "Сбой пинга: ${e.message}")
            -1L
        }
    }

    fun getRawSystemData(): String {
        val sb = StringBuilder()

        sb.append("=== APP LOGS (ВНУТРЕННИЕ ОШИБКИ) ===\n")
        if (AppLog.messages.isEmpty()) sb.append("Ошибок пока нет.\n")
        else sb.append(AppLog.messages.toString())

        sb.append("\n=== SYSTEM PROPERTIES (GETPROP) ===\n")
        try {
            val process = Runtime.getRuntime().exec("getprop")
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val lower = line!!.lowercase()
                if (lower.contains("wifi") || lower.contains("wlan") || lower.contains("chip") || lower.contains("board.platform") || lower.contains("hardware")) {
                    sb.append(line).append("\n")
                }
            }
            reader.close()
        } catch (e: Exception) {
            sb.append("Ошибка чтения getprop: ${e.message}\n")
        }

        sb.append("\n=== SYSFS WLAN0 (HW DIRECTORY) ===\n")
        val sysfsPaths = listOf(
            "/sys/class/net/wlan0/address",
            "/sys/class/net/wlan0/operstate",
            "/sys/class/net/wlan0/device/uevent",
            "/sys/class/net/wlan0/carrier"
        )
        for (path in sysfsPaths) {
            try {
                val file = File(path)
                if (file.exists()) sb.append("$path: ${file.readText().trim()}\n")
                else sb.append("$path: [Нет файла / Permission Denied]\n")
            } catch (e: Exception) {
                sb.append("$path: [Ошибка доступа]\n")
            }
        }
        return sb.toString()
    }
}