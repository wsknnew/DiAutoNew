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

data class AdbResult(
    val success: Boolean,
    val isRoot: Boolean,
    val message: String,
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
     * Test if a given port is actively accepting TCP connections on loopback
     */
    fun isPortListening(port: Int, timeoutMs: Int = 400): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", port), timeoutMs)
                true
            }
        } catch (_: Exception) {
            false
        }
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
     * Read the current service.adb.tcp.port property
     */
    fun getAdbTcpPort(): String {
        return getSystemProperty("service.adb.tcp.port", "-1")
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

        var commandExecuted = false

        if (hasRoot) {
            log("Executing privileged activation script via su...")
            val commands = arrayOf(
                "setprop service.adb.tcp.port $port",
                "stop adbd",
                "start adbd",
                "setprop ctl.restart adbd",
                "settings put global development_settings_enabled 1",
                "settings put global adb_enabled 1",
                "settings put global adb_wifi_enabled 1"
            )

            val script = commands.joinToString(" && ")
            val exitCode = runSuCommand(script, logs)
            commandExecuted = (exitCode == 0)
            log("SU script execution exit code: $exitCode")
        }

        // Secondary fallback: Non-root execution if su was unavailable or failed
        if (!commandExecuted) {
            log("Attempting unprivileged / permissive SELinux commands...")
            try {
                Runtime.getRuntime().exec(arrayOf("setprop", "service.adb.tcp.port", port.toString())).waitFor()
                log("Executed: setprop service.adb.tcp.port $port")
            } catch (e: Exception) {
                log("Non-root setprop failed: ${e.message}")
            }

            try {
                val c = Class.forName("android.os.SystemProperties")
                val set = c.getMethod("set", String::class.java, String::class.java)
                set.invoke(null, "service.adb.tcp.port", port.toString())
                log("SystemProperties.set reflection invoked")
            } catch (e: Exception) {
                log("SystemProperties reflection failed: ${e.message}")
            }

            try {
                Settings.Global.putInt(context.contentResolver, "development_settings_enabled", 1)
                Settings.Global.putInt(context.contentResolver, Settings.Global.ADB_ENABLED, 1)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Settings.Global.putInt(context.contentResolver, "adb_wifi_enabled", 1)
                }
                log("Settings.Global keys updated")
            } catch (e: Exception) {
                log("Settings.Global update notice: ${e.message}")
            }
        }

        // Wait up to 2 seconds for adbd to restart and bind to port
        log("Testing TCP port $port listener...")
        var listening = false
        for (i in 1..4) {
            Thread.sleep(500)
            if (isPortListening(port)) {
                listening = true
                break
            }
        }

        val propPort = getAdbTcpPort()
        log("Current service.adb.tcp.port: '$propPort'")
        log("Port $port listening state: $listening")

        val message = when {
            listening -> "Wireless ADB is now ACTIVE and listening on port $port!"
            hasRoot -> "Commands executed via root, but port $port is not yet responding. Try restarting ADB daemon or check Wi-Fi."
            else -> "Port $port not open. Root access is required on standard ROMs, or use Developer Options / MTK Engineer Mode."
        }

        AdbResult(
            success = listening,
            isRoot = hasRoot,
            message = message,
            logs = logs
        )
    }

    /**
     * Disable wireless ADB (revert to USB mode only)
     */
    suspend fun disableWirelessAdb(): AdbResult = withContext(Dispatchers.IO) {
        val logs = mutableListOf<String>()
        fun log(msg: String) {
            Log.i(TAG, msg)
            logs.add(msg)
        }

        log("Disabling Wireless ADB (reverting to USB-only)...")
        val (hasRoot, _) = checkRoot()

        if (hasRoot) {
            val script = "setprop service.adb.tcp.port -1 && stop adbd && start adbd"
            runSuCommand(script, logs)
        } else {
            try {
                Runtime.getRuntime().exec(arrayOf("setprop", "service.adb.tcp.port", "-1")).waitFor()
            } catch (_: Exception) {}
        }

        Thread.sleep(500)
        val listening = isPortListening(DEFAULT_PORT)
        val success = !listening

        AdbResult(
            success = success,
            isRoot = hasRoot,
            message = if (success) "Wireless ADB disabled. Port is now closed." else "Failed to close port.",
            logs = logs
        )
    }

    /**
     * Restart the ADB daemon
     */
    suspend fun restartAdbDaemon(port: Int = DEFAULT_PORT): AdbResult = withContext(Dispatchers.IO) {
        val logs = mutableListOf<String>()
        val (hasRoot, _) = checkRoot()

        if (hasRoot) {
            val script = "setprop service.adb.tcp.port $port && stop adbd && sleep 1 && start adbd"
            runSuCommand(script, logs)
        }

        Thread.sleep(1200)
        val listening = isPortListening(port)

        AdbResult(
            success = listening,
            isRoot = hasRoot,
            message = if (listening) "ADB Daemon restarted successfully on port $port" else "ADB daemon restart executed",
            logs = logs
        )
    }

    private fun runSuCommand(command: String, logs: MutableList<String>): Int {
        for (su in SU_BINARY_PATHS) {
            try {
                if (su != "su" && !File(su).exists()) continue
                val process = ProcessBuilder(su, "-c", command)
                    .redirectErrorStream(true)
                    .start()

                val reader = BufferedReader(InputStreamReader(process.inputStream))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    line?.let { logs.add("[$su] $it") }
                }

                val finished = process.waitFor(5, TimeUnit.SECONDS)
                if (finished) {
                    return process.exitValue()
                } else {
                    process.destroy()
                }
            } catch (e: Exception) {
                logs.add("[$su error] ${e.message}")
            }
        }
        return -1
    }
}
