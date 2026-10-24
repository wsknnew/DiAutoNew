package com.headunit.wirelessadb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        Log.i(TAG, "Received broadcast action: $action")

        val prefs = context.getSharedPreferences("headunit_adb_prefs", Context.MODE_PRIVATE)
        val autoEnable = prefs.getBoolean("auto_enable_on_boot", true)
        val port = prefs.getInt("adb_port", AdbController.DEFAULT_PORT)

        if (autoEnable) {
            Log.i(TAG, "Auto-enable on boot is ON. Starting wireless ADB on port $port...")
            val pendingResult = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val result = AdbController.enableWirelessAdb(context, port)
                    Log.i(TAG, "Boot ADB result: ${result.message}")
                } catch (e: Exception) {
                    Log.e(TAG, "Boot ADB error: ${e.message}", e)
                } finally {
                    pendingResult.finish()
                }
            }
        } else {
            Log.i(TAG, "Auto-enable on boot is OFF. Skipping activation.")
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
