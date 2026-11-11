package com.headunit.wirelessadb

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

enum class UsbMode {
    HOST,
    DEVICE,
    UNKNOWN
}

data class UsbModeResult(
    val success: Boolean,
    val currentMode: UsbMode,
    val message: String,
    val logs: List<String>
)

object UsbModeController {
    private const val TAG = "UsbModeController"

    // Known MTK / AutoChips sysfs nodes for USB OTG mode
    private val MTK_CMODE_PATHS = arrayOf(
        "/sys/devices/platform/mt_usb/cmode",
        "/sys/devices/soc/11200000.usb/cmode",
        "/sys/devices/platform/soc/11200000.usb/cmode",
        "/sys/bus/platform/drivers/mt_usb/cmode",
        "/sys/kernel/debug/usb/mode"
    )

    private val ANDROID_USB_FUNCTION_PATHS = arrayOf(
        "/sys/class/android_usb/android0/functions",
        "/config/usb_gadget/g1/configs/b.1/strings/0x409/configuration"
    )

    private val ANDROID_USB_ENABLE_PATHS = arrayOf(
        "/sys/class/android_usb/android0/enable"
    )

    /**
     * Detect the current USB mode based on system properties and sysfs
     */
    fun detectCurrentUsbMode(): UsbMode {
        // 1. Check MediaTek cmode (1 = Host, 2 = Device)
        for (path in MTK_CMODE_PATHS) {
            val file = File(path)
            if (file.exists() && file.canRead()) {
                try {
                    val content = file.readText().trim()
                    if (content == "1") return UsbMode.HOST
                    if (content == "2") return UsbMode.DEVICE
                } catch (_: Exception) {}
            }
        }

        // 2. Check vendor / Jancar properties
        val jancarMode = AdbController.getSystemProperty("persist.jancar.usb.mode", "").lowercase()
        if (jancarMode == "device") return UsbMode.DEVICE
        if (jancarMode == "host") return UsbMode.HOST

        val usbMode = AdbController.getSystemProperty("persist.usb.mode", "").lowercase()
        if (usbMode == "device") return UsbMode.DEVICE
        if (usbMode == "host") return UsbMode.HOST

        // 3. Check sys.usb.config
        val usbConfig = AdbController.getSystemProperty("sys.usb.config", "").lowercase()
        val persistConfig = AdbController.getSystemProperty("persist.sys.usb.config", "").lowercase()

        if (usbConfig.contains("adb") || persistConfig.contains("adb")) {
            return UsbMode.DEVICE
        }
        if (usbConfig.contains("none") || usbConfig.isEmpty()) {
            return UsbMode.HOST
        }

        return UsbMode.UNKNOWN
    }

    /**
     * Switch USB Mode to Device (for PC USB cable and ADB) or Host (for Flash drives)
     */
    suspend fun switchUsbMode(toDevice: Boolean): UsbModeResult = withContext(Dispatchers.IO) {
        val logs = mutableListOf<String>()
        fun log(msg: String) {
            Log.i(TAG, msg)
            logs.add(msg)
        }

        val targetModeStr = if (toDevice) "DEVICE (PC ADB)" else "HOST (Flash Drive)"
        log("Switching USB Mode to $targetModeStr...")

        val (hasRoot, _) = AdbController.checkRoot()

        // 1. Set System Properties
        val targetConfig = if (toDevice) "mtp,adb" else "none"
        val targetJancarMode = if (toDevice) "device" else "host"
        val cmodeVal = if (toDevice) "2" else "1"

        val props = mapOf(
            "sys.usb.config" to targetConfig,
            "persist.sys.usb.config" to targetConfig,
            "persist.vendor.usb.config" to targetConfig,
            "vendor.usb.config" to targetConfig,
            "persist.jancar.usb.mode" to targetJancarMode,
            "persist.usb.mode" to targetJancarMode,
            "persist.sys.usb.mode" to targetJancarMode
        )

        for ((key, value) in props) {
            val setOk = AdbController.setSystemProperty(key, value)
            log("setprop $key=$value -> $setOk")
        }

        // 2. Try writing directly to sysfs if accessible or via root
        for (path in MTK_CMODE_PATHS) {
            val file = File(path)
            if (file.exists()) {
                if (file.canWrite()) {
                    try {
                        FileOutputStream(file).use { it.write(cmodeVal.toByteArray()) }
                        log("Wrote $cmodeVal to $path")
                    } catch (e: Exception) {
                        log("Failed to write to $path: ${e.message}")
                    }
                } else if (hasRoot) {
                    val exit = AdbController.runShellCommandDirect("echo $cmodeVal > $path")
                    log("Root write $cmodeVal to $path -> exit $exit")
                }
            }
        }

        // 3. Update android_usb functions if switching to device
        if (toDevice) {
            for (path in ANDROID_USB_FUNCTION_PATHS) {
                tryWriteSysfs(path, "mtp,adb", hasRoot, logs)
            }
            for (path in ANDROID_USB_ENABLE_PATHS) {
                tryWriteSysfs(path, "1", hasRoot, logs)
            }
        }

        Thread.sleep(600)
        val detected = detectCurrentUsbMode()
        log("Resulting USB Mode detected: $detected")

        val expected = if (toDevice) UsbMode.DEVICE else UsbMode.HOST
        val success = (detected == expected)

        val message = if (success) {
            if (toDevice) {
                "USB Mode switched to DEVICE! Connect PC to 4-pin USB port with USB debugging ON."
            } else {
                "USB Mode switched to HOST! USB flash drives and storage are now active."
            }
        } else {
            if (toDevice) {
                "USB properties updated. If PC still doesn't detect device, open Factory Settings (PIN: 56789) -> Other -> USB Mode -> Device."
            } else {
                "USB properties set. If flash drives don't read, open Factory Settings (PIN: 56789) -> Other -> USB Mode -> Host."
            }
        }

        UsbModeResult(
            success = success,
            currentMode = detected,
            message = message,
            logs = logs
        )
    }

    private fun tryWriteSysfs(path: String, value: String, hasRoot: Boolean, logs: MutableList<String>) {
        val file = File(path)
        if (!file.exists()) return
        try {
            if (file.canWrite()) {
                FileOutputStream(file).use { it.write(value.toByteArray()) }
                logs.add("Wrote $value to $path")
            } else if (hasRoot) {
                AdbController.runShellCommandDirect("echo $value > $path")
                logs.add("Root echo $value > $path")
            }
        } catch (e: Exception) {
            logs.add("Cannot write to $path: ${e.message}")
        }
    }
}
