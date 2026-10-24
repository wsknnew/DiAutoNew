package com.headunit.wirelessadb

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast

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
        // Direct activity intent
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

        // Try dialer intents (MTK secret codes)
        val dialCodes = listOf("*#*#3646633#*#*", "*#*#8888#*#*", "*#*#5678#*#*")
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
            These are the most common factory/extra settings PIN codes for AC8227L (JCAC10003):
            
            • 8888 (Standard factory code on ~90% of units)
            • 1617 (Common on newer JCAC10003-OC2 firmware)
            • 7890 (Sometimes + current hour, e.g. 789013 for 1 PM)
            • 3368 / 3711
            • 000000 / 123456
            
            Inside Factory Settings:
            Look for "USB Mode" or "Engineer" and ensure it is set to allow ADB/Device mode if needed.
        """.trimIndent()
    }
}
