package com.headunit.wirelessadb

import android.os.Build
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

object DiagnosticsLogger {

    private val logEntries = CopyOnWriteArrayList<String>()
    private var listener: ((String) -> Unit)? = null
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun log(message: String) {
        val entry = "[${timeFormat.format(Date())}] $message"
        logEntries.add(entry)
        if (logEntries.size > 200) {
            logEntries.removeAt(0)
        }
        listener?.invoke(getAllLogs())
    }

    fun setListener(l: ((String) -> Unit)?) {
        listener = l
        l?.invoke(getAllLogs())
    }

    fun getAllLogs(): String {
        return logEntries.joinToString("\n")
    }

    fun clear() {
        logEntries.clear()
        listener?.invoke("")
    }

    fun getDeviceDiagnosticsReport(): String {
        val selinux = getSELinuxState()
        val propTcpPort = AdbController.getAdbTcpPort()
        val adbdState = AdbController.getSystemProperty("init.svc.adbd", "unknown")
        val (hasRoot, rootInfo) = AdbController.checkRoot()
        val ips = NetworkUtils.getActiveIpAddresses().joinToString { "${it.interfaceName}: ${it.ipAddress}" }

        return """
            === Head Unit Diagnostics Report ===
            Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})
            Hardware: ${Build.HARDWARE} / Board: ${Build.BOARD}
            Platform: ${AdbController.getSystemProperty("ro.board.platform", "unknown")}
            Android Version: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})
            Build Display ID: ${Build.DISPLAY}
            CPU ABI: ${Build.SUPPORTED_ABIS.joinToString()}
            SELinux Enforce: $selinux
            Root Status: $rootInfo (Available: $hasRoot)
            service.adb.tcp.port: $propTcpPort
            init.svc.adbd: $adbdState
            Active IPs: ${if (ips.isNotEmpty()) ips else "None"}
            Port 5555 Listening: ${AdbController.isPortListening(5555)}
            Timestamp: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}
        """.trimIndent()
    }

    private fun getSELinuxState(): String {
        return try {
            val process = Runtime.getRuntime().exec("getenforce")
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            reader.readLine()?.trim() ?: "Unknown"
        } catch (_: Exception) {
            "Unavailable"
        }
    }
}
