package com.example.wifichipreader

import android.content.Context
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build

class WifiAnalyzer(private val context: Context) {

    data class NetworkInfo(
        val ssid: String,
        val bssid: String,
        val rssi: Int,
        val frequency: Int,
        val channel: Int,
        val security: String
    )

    fun getHardwareCapabilities(): Map<String, Boolean> {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val is5G = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) wifiManager.is5GHzBandSupported else true
        val is6G = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) wifiManager.is6GHzBandSupported else false
        return mapOf("5G" to is5G, "6G" to is6G)
    }

    fun scanEther(): List<NetworkInfo> {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val results = mutableListOf<NetworkInfo>()

        try {
            val scanResults = wifiManager.scanResults
            for (scan in scanResults) {
                // Исключаем пустые скрытые сети
                val ssid = if (scan.SSID.isNullOrEmpty()) "[Скрытая сеть]" else scan.SSID

                results.add(
                    NetworkInfo(
                        ssid = ssid,
                        bssid = scan.BSSID ?: "Неизвестно",
                        rssi = scan.level,
                        frequency = scan.frequency,
                        channel = calculateChannel(scan.frequency),
                        security = getSecurityString(scan.capabilities)
                    )
                )
            }
        } catch (e: Exception) {
            // Если нет прав или отключен WiFi
        }

        // Сортируем по мощности сигнала (от сильного к слабому)
        return results.sortedByDescending { it.rssi }
    }

    // Перевод частоты в номер канала
    private fun calculateChannel(freq: Int): Int {
        return when {
            freq == 2484 -> 14
            freq in 2412..2472 -> (freq - 2407) / 5
            freq in 5170..5825 -> (freq - 5000) / 5
            freq in 5925..7125 -> (freq - 5950) / 5 // Для 6GHz (WiFi 6E)
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
}