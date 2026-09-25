package com.example.wifichipreader

import android.Manifest
import android.app.ActivityManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import androidx.core.app.ActivityCompat
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

object AppLog {
    val messages = java.lang.StringBuilder()
    fun e(tag: String, msg: String) { messages.append("[ERROR] $tag: $msg\n") }
    fun i(tag: String, msg: String) { messages.append("[INFO] $tag: $msg\n") }
}

class WifiAnalyzer(private val context: Context) {

    data class HardwareReport(
        val is5GSupported: Boolean, val is6GSupported: Boolean, val currentStandard: String,
        val hasConnection: Boolean, val ssid: String, val frequency: Int, val linkSpeed: Int,
        val rssi: Int, val securityType: String, val pingMs: Long, val qualityScore: Int
    )

    data class NetworkInfo(
        val ssid: String, val bssid: String, val vendor: String, val rssi: Int,
        val frequency: Int, val channel: Int, val security: String
    )

    data class BtDeviceInfo(
        val name: String, val address: String, val rssi: Int, val type: String
    )

    data class LiveStats(
        val hasConnection: Boolean, val ssid: String, val securityType: String,
        val frequency: Int, val rssi: Int, val linkSpeed: Int, val qualityScore: Int
    )

    data class MemoryReport(
        val ramTotalGb: Double, val ramAvailGb: Double, val romTotalGb: Double,
        val romAvailGb: Double, val physicalRomGb: Double
    )

    data class BtReport(
        val isDetected: Boolean, val moduleName: String, val btVersion: String,
        val isAuthorized: Boolean, val rawSource: String
    )

    // ДОБАВЛЕН aic8800 ДЛЯ ТВ RAZZ И VITEK
    private val defaultBtDatabase = mapOf(
        "mt7663" to "5.1", "mt7668" to "5.0", "mt7921" to "5.2", "mt7922" to "5.2", "mt7662" to "4.0",
        "rtl8723bs" to "4.0", "rtl8723bu" to "4.0", "rtl8723ds" to "4.2", "rtl8822bs" to "4.2",
        "rtl8822cs" to "5.0", "rtl8822ce" to "5.0", "rtl8852ae" to "5.2", "rtl8852be" to "5.2",
        "rtl8852ce" to "5.3", "rtk8723" to "4.0", "rtk8822" to "5.0", "bcm4354" to "4.1",
        "bcm4356" to "4.1", "bcm4359" to "4.2", "bcm4364" to "5.0", "qca9377" to "4.1",
        "qca1023" to "4.1", "w155s1" to "5.0", "w265s1" to "5.0", "aml_wcn" to "5.0",
        "aic8800" to "5.0" // Добавлен чип из лога
    )

    // --- НОВАЯ СИСТЕМА СКАНИРОВАНИЯ BLUETOOTH ЭФИРА ---
    private val discoveredBtDevices = ConcurrentHashMap<String, BtDeviceInfo>()
    private var isBtScanning = false

    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action
            if (BluetoothDevice.ACTION_FOUND == action) {
                val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt()
                if (device != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return
                    val name = device.name ?: "Unknown Device"
                    val type = when (device.type) {
                        BluetoothDevice.DEVICE_TYPE_CLASSIC -> "Classic"
                        BluetoothDevice.DEVICE_TYPE_LE -> "BLE"
                        BluetoothDevice.DEVICE_TYPE_DUAL -> "Dual"
                        else -> "Unknown"
                    }
                    // Сохраняем или обновляем устройство в Map
                    discoveredBtDevices[device.address] = BtDeviceInfo(name, device.address, rssi, type)
                }
            } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED == action) {
                // Если мы всё ещё находимся на вкладке сканера, заново запускаем прослушивание
                if (isBtScanning) {
                    val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
                    val adapter = bluetoothManager.adapter
                    if (adapter != null && adapter.isEnabled) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) return
                        adapter.startDiscovery()
                    }
                }
            }
        }
    }

    fun startBtDiscovery() {
        if (isBtScanning) return
        isBtScanning = true
        discoveredBtDevices.clear()

        val filter = IntentFilter(BluetoothDevice.ACTION_FOUND)
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(btReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(btReceiver, filter)
        }

        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = bluetoothManager.adapter
        if (adapter != null && adapter.isEnabled) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) return
            adapter.startDiscovery()
        }
    }

    fun stopBtDiscovery() {
        if (!isBtScanning) return
        isBtScanning = false
        try {
            context.unregisterReceiver(btReceiver)
        } catch (e: Exception) {}

        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = bluetoothManager.adapter
        if (adapter != null && adapter.isEnabled) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) return
            adapter.cancelDiscovery()
        }
    }

    fun getDiscoveredBtDevices(): List<BtDeviceInfo> {
        return discoveredBtDevices.values.sortedByDescending { it.rssi }
    }
    // --------------------------------------------------

    private fun loadBtDatabase(): Map<String, String> {
        val map = mutableMapOf<String, String>()
        map.putAll(defaultBtDatabase)
        try {
            val externalDirs = ContextCompat.getExternalFilesDirs(context, null)
            val targetDir = if (externalDirs.size > 1) externalDirs.lastOrNull() else context.getExternalFilesDirs(null).firstOrNull()
            if (targetDir != null && !targetDir.exists()) targetDir.mkdirs()
            val jsonFile = File(targetDir, "bt_whitelist.json")

            if (!jsonFile.exists()) {
                val jsonObject = JSONObject(defaultBtDatabase as Map<*, *>)
                jsonFile.writeText(jsonObject.toString(4))
            }

            if (jsonFile.exists()) {
                val jsonStr = jsonFile.readText()
                val jsonObj = JSONObject(jsonStr)
                val keys = jsonObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    map[key.lowercase()] = jsonObj.getString(key)
                }
            }
        } catch (e: Exception) {}
        return map
    }

    fun getMemoryReport(): MemoryReport {
        val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        actManager.getMemoryInfo(memInfo)
        val ramTotal = memInfo.totalMem.toDouble() / (1024 * 1024 * 1024)
        val ramAvail = memInfo.availMem.toDouble() / (1024 * 1024 * 1024)

        val statFs = StatFs(Environment.getDataDirectory().path)
        val romTotal = statFs.totalBytes.toDouble() / (1024 * 1024 * 1024)
        val romAvail = statFs.availableBytes.toDouble() / (1024 * 1024 * 1024)

        var physicalRom = -1.0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val storageStatsManager = context.getSystemService(Context.STORAGE_STATS_SERVICE) as android.app.usage.StorageStatsManager
                val totalBytes = storageStatsManager.getTotalBytes(android.os.storage.StorageManager.UUID_DEFAULT)
                physicalRom = totalBytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
            } catch (e: Exception) {}
        }
        return MemoryReport(ramTotal, ramAvail, romTotal, romAvail, physicalRom)
    }

    fun getBluetoothReport(): BtReport {
        var detectedModule = "Неизвестно"
        var rawSource = ""
        var found = false
        val btDatabase = loadBtDatabase()

        val sysfsPaths = listOf(
            "/sys/class/net/wlan0/device/uevent",
            "/sys/class/bluetooth/hci0/device/uevent",
            "/sys/bus/usb/devices/1-1/uevent",
            "/sys/bus/usb/devices/1-2/uevent",
            "/sys/bus/usb/devices/usb1/uevent",
            "/sys/bus/sdio/devices/uevent"
        )

        for (path in sysfsPaths) {
            try {
                val file = File(path)
                if (file.exists() && file.canRead()) {
                    val content = file.readText().lowercase()
                    for (mod in btDatabase.keys) {
                        if (content.contains(mod)) {
                            detectedModule = mod
                            rawSource = "sysfs ($path)"
                            found = true
                            break
                        }
                    }
                }
            } catch (e: Exception) {}
            if (found) break
        }

        if (!found) {
            try {
                val process = Runtime.getRuntime().exec("getprop")
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val lower = line!!.lowercase()
                    if (lower.contains("bluetooth") || lower.contains("wlan") || lower.contains("wifi") ||
                        lower.contains("vendor.bt") || lower.contains("hardware") || lower.contains("mediatek") || lower.contains("ro.chipname")) {

                        for (mod in btDatabase.keys) {
                            if (lower.contains(mod)) {
                                detectedModule = mod
                                rawSource = "getprop ($line)"
                                found = true
                                break
                            }
                        }
                    }
                    if (found) break
                }
                reader.close()
            } catch (e: Exception) {}
        }

        val btVersion = btDatabase[detectedModule] ?: "Неизвестно"
        val isAuthorized = btVersion != "Неизвестно"

        return BtReport(found, detectedModule.uppercase(), btVersion, isAuthorized, rawSource)
    }

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
        var securityType = context.getString(R.string.open_net)
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

        return HardwareReport(is5G, is6G, currentStandard, hasConnection, wifiInfo.ssid?.replace("\"", "") ?: "", freq, linkSpeed, rssi, securityType, pingMs, quality)
    }

    fun getLiveStats(): LiveStats {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val wifiInfo = wifiManager.connectionInfo
        val freq = wifiInfo.frequency
        val hasConnection = freq > 0 && wifiInfo.ssid != null && wifiInfo.ssid != "<unknown ssid>"
        var ssid = ""
        var securityType = context.getString(R.string.open_net)
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
            for (scan in wifiManager.scanResults) {
                results.add(NetworkInfo(if (scan.SSID.isNullOrEmpty()) "[Hidden]" else scan.SSID, scan.BSSID ?: "00:00:00:00:00:00", getVendorFromMac(scan.BSSID ?: ""), scan.level, scan.frequency, calculateChannel(scan.frequency), getSecurityString(scan.capabilities)))
            }
        } catch (e: Exception) {}
        return results.sortedByDescending { it.rssi }
    }

    private fun getVendorFromMac(mac: String): String {
        val cleanMac = mac.uppercase().replace(":", "")
        if (cleanMac.length < 6) return context.getString(R.string.unknown)
        return when (cleanMac.substring(0, 6)) {
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
            else -> context.getString(R.string.unknown)
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
            else -> context.getString(R.string.open_net)
        }
    }

    fun measurePing(host: String = "8.8.8.8"): Long {
        return try {
            val start = System.currentTimeMillis()
            if (InetAddress.getByName(host).isReachable(1500)) System.currentTimeMillis() - start else -1L
        } catch (e: Exception) { -1L }
    }

    private fun runRootCommand(command: String): String {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = java.lang.StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                output.append(line).append("\n")
            }
            process.waitFor()

            if (output.isEmpty()) {
                val errorReader = BufferedReader(InputStreamReader(process.errorStream))
                while (errorReader.readLine().also { line = it } != null) {
                    output.append(line).append("\n")
                }
            }
            output.toString().trim()
        } catch (e: Exception) {
            "[NO ROOT]: ${e.message}"
        }
    }

    fun getRawSystemData(): String {
        val sb = StringBuilder()
        sb.append("=== APP LOGS ===\n${AppLog.messages}\n=== SYSTEM PROPERTIES ===\n")
        try {
            val process = Runtime.getRuntime().exec("getprop")
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                if (line!!.lowercase().let { it.contains("wifi") || it.contains("wlan") || it.contains("chip") || it.contains("board") || it.contains("vendor.bt") }) {
                    sb.append(line).append("\n")
                }
            }
            reader.close()
        } catch (e: Exception) {}

        sb.append("\n=== SYSFS WLAN0 ===\n")
        val sysfsPaths = listOf(
            "/sys/class/net/wlan0/address",
            "/sys/class/net/wlan0/operstate",
            "/sys/class/net/wlan0/device/uevent",
            "/sys/class/net/wlan0/carrier",
            "/sys/bus/usb/devices/1-1/uevent",
            "/sys/bus/usb/devices/1-2/uevent"
        )

        for (path in sysfsPaths) {
            try {
                val file = File(path)
                if (file.exists() && file.canRead()) {
                    sb.append("$path: ${file.readText().trim()}\n")
                } else {
                    val rootOutput = runRootCommand("cat $path")
                    if (rootOutput.isNotEmpty() && !rootOutput.contains("Permission denied", ignoreCase = true) && !rootOutput.contains("not found", ignoreCase = true) && !rootOutput.contains("NO ROOT")) {
                        sb.append("$path: [ROOT] $rootOutput\n")
                    } else {
                        sb.append("$path: [Permission Denied / No Root]\n")
                    }
                }
            } catch (e: Exception) {
                sb.append("$path: [Ошибка доступа]\n")
            }
        }
        return sb.toString()
    }

    fun getFirmwareFlags(): String {
        val flags = StringBuilder()
        val props = mutableMapOf<String, String>()
        try {
            val process = Runtime.getRuntime().exec("getprop")
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                if (line!!.startsWith("[") && line!!.contains("]: [")) {
                    val parts = line!!.split("]: [")
                    if (parts.size == 2) props[parts[0].removePrefix("[")] = parts[1].removeSuffix("]")
                }
            }
            reader.close()
        } catch (e: Exception) {}

        flags.append("${context.getString(R.string.fw_platform)}: ${props["ro.board.platform"] ?: "N/A"}\n")
        flags.append("${context.getString(R.string.fw_build)}: ${props["ro.build.display.id"] ?: "N/A"}\n\n")

        val ota = props["ro.ota.disable"] ?: props["persist.sys.ota.enable"]
        flags.append("OTA: ").append(when (ota) {
            "1", "true", "disable" -> context.getString(R.string.ota_disabled)
            "0", "false", "enable" -> context.getString(R.string.ota_enabled)
            else -> context.getString(R.string.ota_default)
        }).append("\n")

        return flags.toString()
    }
}