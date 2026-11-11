package com.headunit.wirelessadb

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

data class PortReachability(
    val loopbackListening: Boolean,
    val wifiListening: Boolean,
    val wifiIp: String?
)

data class AdbResult(
    val success: Boolean,
    val isRoot: Boolean,
    val reachability: PortReachability,
    val message: String,
    val requiresDevOptionsToggle: Boolean,
    val logs: List<String>
)

object AdbController {
    private const val TAG = "AdbController"
    const val DEFAULT_PORT = 5555

    private val SU_BINARY_PATHS = arrayOf(
        "/system/xbin/su",
        "/system/bin/su",
        "/vendor/bin/su",
        "/sbin/su",
        "/system/sd/xbin/su",
        "/su/bin/su",
        "su"
    )

    /**
     * Test if a given host & port accepts TCP connections
     */
    fun isPortListening(host: String = "127.0.0.1", port: Int = DEFAULT_PORT, timeoutMs: Int = 300): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Check both local loopback (127.0.0.1) and external Wi-Fi interface
     */
    fun checkReachability(port: Int = DEFAULT_PORT): PortReachability {
        val loopback = isPortListening("127.0.0.1", port, 250)
        val wifiIp = NetworkUtils.getPrimaryIpAddress()
        val wifi = if (!wifiIp.isNullOrEmpty()) {
            isPortListening(wifiIp, port, 350)
        } else {
            false
        }
        return PortReachability(
            loopbackListening = loopback,
            wifiListening = wifi,
            wifiIp = wifiIp
        )
    }

    /**
     * Check if root (su) is available and functional
     */
    fun checkRoot(): Pair<Boolean, String> {
        for (path in SU_BINARY_PATHS) {
            try {
                if (path != "su" && !File(path).exists()) {
                    continue
                }
                val process = Runtime.getRuntime().exec(arrayOf(path, "-c", "id"))
                val finished = process.waitFor(2, TimeUnit.SECONDS)
                if (finished && process.exitValue() == 0) {
                    val reader = BufferedReader(InputStreamReader(process.inputStream))
                    val output = reader.readLine() ?: ""
                    if (output.contains("uid=0")) {
                        return Pair(true, "$path (uid=0 root confirmed)")
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "su test failed for $path: ${e.message}")
            }
        }
        return Pair(false, "Root binary not detected or permission denied")
    }

    /**
     * Get system property via reflection and getprop fallback
     */
    fun getSystemProperty(key: String, defaultValue: String = ""): String {
        try {
            val c = Class.forName("android.os.SystemProperties")
            val get = c.getMethod("get", String::class.java, String::class.java)
            val result = get.invoke(null, key, defaultValue) as? String
            if (!result.isNullOrBlank()) {
                return result
            }
        } catch (_: Exception) {
        }

        try {
            val process = Runtime.getRuntime().exec(arrayOf("getprop", key))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val line = reader.readLine()?.trim()
            if (!line.isNullOrEmpty()) {
                return line
            }
        } catch (_: Exception) {
        }

        return defaultValue
    }

    /**
     * Set system property using reflection, setprop command, or root fallback
     */
    fun setSystemProperty(key: String, value: String): Boolean {
        try {
            val c = Class.forName("android.os.SystemProperties")
            val set = c.getMethod("set", String::class.java, String::class.java)
            set.invoke(null, key, value)
            return true
        } catch (_: Exception) {
        }

        try {
            val p = Runtime.getRuntime().exec(arrayOf("setprop", key, value))
            p.waitFor(1, TimeUnit.SECONDS)
            if (p.exitValue() == 0) return true
        } catch (_: Exception) {
        }

        val (hasRoot, _) = checkRoot()
        if (hasRoot) {
            val exit = runShellCommandDirect("setprop $key $value")
            return exit == 0
        }

        return false
    }

    /**
     * Enable wireless ADB on the given port
     */
    suspend fun enableWirelessAdb(context: Context, port: Int = DEFAULT_PORT): AdbResult = withContext(Dispatchers.IO) {
        val logs = mutableListOf<String>()
        fun log(msg: String) {
            Log.i(TAG, msg)
            logs.add(msg)
        }

        log("Initiating Wireless ADB activation on port $port...")
        val (hasRoot, rootInfo) = checkRoot()
        log("Root status: $rootInfo")

        // 1. Set BOTH service and persist properties
        val portStr = port.toString()
        setSystemProperty("service.adb.tcp.port", portStr)
        setSystemProperty("persist.adb.tcp.port", portStr)
        setSystemProperty("persist.sys.usb.config", "mtp,adb")
        setSystemProperty("sys.usb.config", "mtp,adb")
        setSystemProperty("persist.vendor.usb.config", "mtp,adb")
        setSystemProperty("vendor.usb.config", "mtp,adb")
        log("Properties configured: service.adb.tcp.port=$portStr, persist.adb.tcp.port=$portStr")

        try {
            Settings.Global.putInt(context.contentResolver, "development_settings_enabled", 1)
            Settings.Global.putInt(context.contentResolver, Settings.Global.ADB_ENABLED, 1)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Settings.Global.putInt(context.contentResolver, "adb_wifi_enabled", 1)
            }
        } catch (_: Exception) {}

        // 2. If Root is present, restart daemon directly
        if (hasRoot) {
            log("Executing su daemon restart...")
            runShellCommandDirect("setprop service.adb.tcp.port $port && stop adbd && sleep 1 && start adbd")
        } else {
            // Permissive SELinux trigger attempt
            try {
                Runtime.getRuntime().exec(arrayOf("setprop", "ctl.restart", "adbd")).waitFor(1, TimeUnit.SECONDS)
            } catch (_: Exception) {}
        }

        // 3. Test reachability
        Thread.sleep(700)
        var reachability = checkReachability(port)
        if (!reachability.loopbackListening && !reachability.wifiListening) {
            Thread.sleep(800)
            reachability = checkReachability(port)
        }

        log("Reachability check: Loopback=${reachability.loopbackListening}, Wi-Fi (${reachability.wifiIp})=${reachability.wifiListening}")

        val success = reachability.wifiListening || reachability.loopbackListening
        val requiresDevOptions = !success && !hasRoot

        val message = when {
            reachability.wifiListening -> "Wireless ADB is fully ACTIVE and listening on ${reachability.wifiIp}:$port!"
            reachability.loopbackListening -> "ADB listening on localhost! To open for external Wi-Fi, toggle USB Debugging OFF then ON in Developer Options."
            hasRoot -> "Properties saved via root, but port $port is not responding yet. Try restarting ADB daemon."
            else -> "Port $port not active yet. Tap 'RELOAD ADB VIA DEV OPTIONS' and toggle USB Debugging OFF then ON."
        }

        AdbResult(
            success = success,
            isRoot = hasRoot,
            reachability = reachability,
            message = message,
            requiresDevOptionsToggle = requiresDevOptions,
            logs = logs
        )
    }

    /**
     * Disable wireless ADB
     */
    suspend fun disableWirelessAdb(): AdbResult = withContext(Dispatchers.IO) {
        val logs = mutableListOf<String>()
        fun log(msg: String) {
            Log.i(TAG, msg)
            logs.add(msg)
        }

        log("Disabling Wireless ADB (reverting to USB-only)...")
        val (hasRoot, _) = checkRoot()

        setSystemProperty("service.adb.tcp.port", "-1")
        setSystemProperty("persist.adb.tcp.port", "")

        if (hasRoot) {
            runShellCommandDirect("setprop service.adb.tcp.port -1 && stop adbd && start adbd")
        } else {
            try {
                Runtime.getRuntime().exec(arrayOf("setprop", "ctl.restart", "adbd")).waitFor(1, TimeUnit.SECONDS)
            } catch (_: Exception) {}
        }

        Thread.sleep(600)
        val reachability = checkReachability(DEFAULT_PORT)
        val closed = !reachability.loopbackListening && !reachability.wifiListening

        AdbResult(
            success = closed,
            isRoot = hasRoot,
            reachability = reachability,
            message = if (closed) "Wireless ADB disabled. Port is closed." else "Properties cleared. Reconnect USB or restart to apply.",
            requiresDevOptionsToggle = !closed && !hasRoot,
            logs = logs
        )
    }

    /**
     * Restart the ADB daemon
     */
    suspend fun restartAdbDaemon(port: Int = DEFAULT_PORT): AdbResult = withContext(Dispatchers.IO) {
        val logs = mutableListOf<String>()
        val (hasRoot, _) = checkRoot()

        setSystemProperty("service.adb.tcp.port", port.toString())
        setSystemProperty("persist.adb.tcp.port", port.toString())

        if (hasRoot) {
            runShellCommandDirect("setprop service.adb.tcp.port $port && stop adbd && sleep 1 && start adbd")
        } else {
            try {
                Runtime.getRuntime().exec(arrayOf("setprop", "ctl.restart", "adbd")).waitFor(1, TimeUnit.SECONDS)
            } catch (_: Exception) {}
        }

        Thread.sleep(1000)
        val reachability = checkReachability(port)

        AdbResult(
            success = reachability.wifiListening || reachability.loopbackListening,
            isRoot = hasRoot,
            reachability = reachability,
            message = if (reachability.wifiListening) {
                "ADB Daemon active on ${reachability.wifiIp}:$port"
            } else if (reachability.loopbackListening) {
                "ADB Daemon active on localhost. Toggle USB Debugging to bind Wi-Fi."
            } else {
                "Daemon restart triggered. If not listening, toggle USB Debugging OFF then ON in Dev Options."
            },
            requiresDevOptionsToggle = !reachability.wifiListening && !hasRoot,
            logs = logs
        )
    }

    fun runShellCommandDirect(command: String): Int {
        for (su in SU_BINARY_PATHS) {
            try {
                if (su != "su" && !File(su).exists()) continue
                val process = ProcessBuilder(su, "-c", command)
                    .redirectErrorStream(true)
                    .start()
                val finished = process.waitFor(5, TimeUnit.SECONDS)
                if (finished) return process.exitValue() else process.destroy()
            } catch (_: Exception) {}
        }
        return -1
    }
}
