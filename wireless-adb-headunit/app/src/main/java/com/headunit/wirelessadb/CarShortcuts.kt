package com.headunit.wirelessadb

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog

object CarShortcuts {

    fun openDeveloperOptions(context: Context): Boolean {
        val intents = listOf(
            Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
            Intent().setComponent(ComponentName("com.android.settings", "com.android.settings.DevelopmentSettings")),
            Intent().setComponent(ComponentName("com.android.settings", "com.android.settings.Settings\$DevelopmentSettingsDashboardActivity")),
            Intent().setComponent(ComponentName("com.android.settings", "com.android.settings.Settings\$DevelopmentSettingsActivity"))
        )

        for (intent in intents) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return true
            } catch (_: Exception) {
            }
        }

        Toast.makeText(context, "Could not open Developer Options directly. Tap Build Number 7 times in Car About Settings.", Toast.LENGTH_LONG).show()
        return false
    }

    fun openMtkEngineerMode(context: Context): Boolean {
        val directIntents = listOf(
            Intent().setComponent(ComponentName("com.mediatek.engineermode", "com.mediatek.engineermode.EngineerMode")),
            Intent().setComponent(ComponentName("com.mediatek.engineermode", "com.mediatek.engineermode.MainActivity")),
            context.packageManager.getLaunchIntentForPackage("com.mediatek.engineermode")
        )

        for (intent in directIntents) {
            if (intent != null) {
                try {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    return true
                } catch (_: Exception) {
                }
            }
        }

        val dialCodes = listOf("*#*#3646633#*#*", "*#*#5678#*#*", "*#*#8888#*#*")
        for (code in dialCodes) {
            try {
                val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(code)))
                dialIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(dialIntent)
                return true
            } catch (_: Exception) {
            }
        }

        Toast.makeText(context, "MediaTek Engineer Mode app not found on this ROM build.", Toast.LENGTH_LONG).show()
        return false
    }

    fun openFactorySettings(context: Context): Boolean {
        // Copy most common PIN 56789 to clipboard automatically
        copyToClipboard(context, "Factory PIN", "56789")

        val factoryIntents = listOf(
            Intent().setComponent(ComponentName("com.jancar.settings", "com.jancar.settings.FactoryActivity")),
            Intent().setComponent(ComponentName("com.jancar.settings", "com.jancar.settings.MainActivity")),
            Intent().setComponent(ComponentName("com.autochips.carsetting", "com.autochips.carsetting.FactorySettingsActivity")),
            Intent().setComponent(ComponentName("com.autochips.carsetting", "com.autochips.carsetting.MainActivity")),
            Intent().setComponent(ComponentName("com.ts.MainUI", "com.ts.MainUI.FactoryActivity")),
            context.packageManager.getLaunchIntentForPackage("com.jancar.settings"),
            context.packageManager.getLaunchIntentForPackage("com.autochips.carsetting"),
            Intent(Settings.ACTION_SETTINGS)
        )

        for (intent in factoryIntents) {
            if (intent != null) {
                try {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    Toast.makeText(context, "PIN '56789' copied to clipboard!", Toast.LENGTH_SHORT).show()
                    return true
                } catch (_: Exception) {}
            }
        }

        Toast.makeText(context, "Please open Car Settings -> Factory Settings manually (PIN: 56789)", Toast.LENGTH_LONG).show()
        return false
    }

    fun showUsbModeGuideDialog(context: Context) {
        val message = """
            To enable USB cable ADB on AC8227L:
            
            1. Open Factory Settings (PIN: 56789 or 8888).
            2. Go to: Car Settings / Other.
            3. Find "USB Mode" and change from "Host" to "Device".
            4. Connect your PC to the 4-PIN USB port on the back.
            5. Ensure Developer Options -> "USB Debugging" is ON.
            
            ⚠️ Note: In "Device" mode, USB flash drives will not work until you switch it back to "Host" mode.
        """.trimIndent()

        AlertDialog.Builder(context)
            .setTitle("AC8227L USB Mode Setup")
            .setMessage(message)
            .setPositiveButton("Open Factory Settings") { _, _ ->
                openFactorySettings(context)
            }
            .setNegativeButton("Close", null)
            .show()
    }

    fun copyToClipboard(context: Context, label: String, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        clipboard.setPrimaryClip(clip)
    }

    fun openAndroidSettings(context: Context): Boolean {
        return try {
            val intent = Intent(Settings.ACTION_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Toast.makeText(context, "Unable to launch Android Settings: ${e.message}", Toast.LENGTH_SHORT).show()
            false
        }
    }

    fun openWifiSettings(context: Context): Boolean {
        return try {
            val intent = Intent(Settings.ACTION_WIFI_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Toast.makeText(context, "Unable to launch Wi-Fi Settings: ${e.message}", Toast.LENGTH_SHORT).show()
            false
        }
    }

    fun getFactoryPasscodesInfo(): String {
        return """
            === AC8227L Factory Passcodes ===
            These are the verified passcodes for AC8227L (JCAC10003):
            
            • 56789 (Primary Developer Factory Code on JCAC10003)
            • 5678 (Engineer / Developer Settings)
            • 8888 (Standard factory code on ~90% of units)
            • 1234 (Common alternate)
            • 1617 (Common on newer JCAC10003-OC2 firmware)
            • 7890 (Sometimes + current hour, e.g. 789013)
            • 000000 / 3368 / 3711
            
            Inside Factory Settings:
            Go to: Car Settings -> Other -> USB Mode
            Change "Host" to "Device" for USB cable ADB.
        """.trimIndent()
    }
}
