package com.headunit.wirelessadb

import android.content.Context
import android.net.wifi.WifiManager
import java.net.Inet4Address
import java.net.NetworkInterface

data class NetworkInfo(
    val ipAddress: String,
    val interfaceName: String,
    val isWifi: Boolean,
    val isHotspot: Boolean
)

object NetworkUtils {

    /**
     * Get all active IPv4 addresses sorted by likelihood of in-car use (wlan0, ap0, eth0, etc.)
     */
    fun getActiveIpAddresses(): List<NetworkInfo> {
        val list = mutableListOf<NetworkInfo>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            for (iface in interfaces) {
                if (iface.isLoopback || !iface.isUp) continue

                val ifaceName = iface.name.lowercase()
                val isWifi = ifaceName.startsWith("wlan")
                val isHotspot = ifaceName.startsWith("ap") || ifaceName.contains("softap")

                for (addr in iface.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val host = addr.hostAddress ?: continue
                        list.add(
                            NetworkInfo(
                                ipAddress = host,
                                interfaceName = iface.name,
                                isWifi = isWifi,
                                isHotspot = isHotspot
                            )
                        )
                    }
                }
            }
        } catch (_: Exception) {
        }

        // Sort: Wi-Fi interfaces first, then Hotspot, then others
        return list.sortedWith(
            compareByDescending<NetworkInfo> { it.isWifi }
                .thenByDescending { it.isHotspot }
        )
    }

    /**
     * Get primary IP address string (e.g. "192.168.1.50") or null if none
     */
    fun getPrimaryIpAddress(): String? {
        val ips = getActiveIpAddresses()
        return ips.firstOrNull()?.ipAddress
    }

    /**
     * Get Wi-Fi SSID if connected
     */
    fun getWifiSsid(context: Context): String {
        return try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val info = wm?.connectionInfo
            val ssid = info?.ssid ?: ""
            if (ssid.isNotEmpty() && ssid != "<unknown ssid>") {
                ssid.replace("\"", "")
            } else {
                "Not Connected"
            }
        } catch (_: Exception) {
            "Unknown"
        }
    }
}
