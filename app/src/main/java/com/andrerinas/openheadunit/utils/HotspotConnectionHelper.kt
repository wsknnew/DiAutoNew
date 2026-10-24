package com.andrerinas.openheadunit.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import com.andrerinas.openheadunit.App
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.Collections

object HotspotConnectionHelper {

    const val AAP_TCP_PORT = 5277

    data class NetworkStatus(
        val isWifiConnected: Boolean,
        val ssid: String?,
        val localIp: String?,
        val gatewayIp: String?,
        val isPhoneHotspot: Boolean
    )

    suspend fun getNetworkStatus(context: Context): NetworkStatus = withContext(Dispatchers.IO) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        var isWifi = false
        var localIp: String? = null
        var gatewayIp: String? = null
        var ssid: String? = null

        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val wifiInfo = try { wm?.connectionInfo } catch (_: Exception) { null }
        if (wifiInfo != null && wifiInfo.networkId != -1) {
            val rawSsid = wifiInfo.ssid
            if (!rawSsid.isNullOrBlank() && rawSsid != "<unknown ssid>") {
                ssid = rawSsid.removePrefix("\"").removeSuffix("\"")
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && cm != null) {
            val activeNet = cm.activeNetwork
            if (activeNet != null) {
                val caps = cm.getNetworkCapabilities(activeNet)
                isWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true

                val lp = cm.getLinkProperties(activeNet)
                lp?.linkAddresses?.forEach { addr ->
                    if (addr.address is Inet4Address && !addr.address.isLoopbackAddress) {
                        localIp = addr.address.hostAddress
                    }
                }
                lp?.routes?.forEach { route ->
                    if (route.isDefaultRoute && route.gateway is Inet4Address) {
                        gatewayIp = route.gateway?.hostAddress
                    }
                }
            }
        }

        if (localIp == null) {
            localIp = getLocalWifiIp()
            if (localIp != null) isWifi = true
        }

        if (gatewayIp == null && wm != null) {
            try {
                @Suppress("DEPRECATION")
                val dhcp = wm.dhcpInfo
                if (dhcp != null && dhcp.gateway != 0) {
                    val gw = dhcp.gateway
                    gatewayIp = "${gw and 0xFF}.${(gw shr 8) and 0xFF}.${(gw shr 16) and 0xFF}.${(gw shr 24) and 0xFF}"
                }
            } catch (_: Exception) {}
        }

        val resolvedLocalIp = localIp
        if (gatewayIp == null && resolvedLocalIp != null) {
            gatewayIp = guessGatewayFromIp(resolvedLocalIp)
        }

        val customIp = App.provide(context).settings.customHotspotPhoneIp
        val effectivePhoneIp = if (customIp.isNotBlank()) customIp else gatewayIp

        val isHotspot = isLikelyPhoneHotspot(resolvedLocalIp, gatewayIp)

        // Note: Never probe port 5277 here! Probing port 5277 without completing the SSL handshake
        // wedges Android Auto's Headunit Server on the phone until manually restarted.

        NetworkStatus(
            isWifiConnected = isWifi || resolvedLocalIp != null,
            ssid = ssid,
            localIp = resolvedLocalIp,
            gatewayIp = effectivePhoneIp,
            isPhoneHotspot = isHotspot
        )
    }

    fun isLikelyPhoneHotspot(localIp: String?, gatewayIp: String?): Boolean {
        if (gatewayIp == null) return false
        return gatewayIp.startsWith("192.168.43.") ||
                gatewayIp.startsWith("192.168.44.") ||
                gatewayIp.startsWith("192.168.49.") ||
                gatewayIp.startsWith("192.168.50.") ||
                gatewayIp.startsWith("192.168.225.") ||
                gatewayIp.startsWith("172.20.10.") ||
                (localIp != null && localIp.startsWith("192.168.43."))
    }

    private fun guessGatewayFromIp(ip: String): String? {
        val parts = ip.split(".")
        if (parts.size == 4) {
            return "${parts[0]}.${parts[1]}.${parts[2]}.1"
        }
        return null
    }

    private fun getLocalWifiIp(): String? {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                if (!intf.isUp || intf.isLoopback) continue
                val name = intf.name.lowercase()
                if (name.contains("wlan") || name.contains("eth") || name.contains("ap") || name.contains("p2p")) {
                    for (addr in Collections.list(intf.inetAddresses)) {
                        if (addr is Inet4Address && !addr.isLoopbackAddress) {
                            return addr.hostAddress
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }

    fun isTcpPortOpen(host: String, port: Int, timeoutMs: Int = 300): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                true
            }
        } catch (_: Exception) {
            false
        }
    }
}
