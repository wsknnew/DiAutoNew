package com.andrerinas.openheadunit.app

import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.aap.AapService
import com.andrerinas.openheadunit.main.MainActivity
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.Settings
import android.os.UserManager
import android.os.Build

class AutoStartReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        val isLocked = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && 
                      !(context.getSystemService(Context.USER_SERVICE) as UserManager).isUserUnlocked
        
        // Don't trigger auto-start if we are already connected!
        // This prevents activity restarts if BT reconnects during a session.
        if (!isLocked && com.andrerinas.openheadunit.App.provide(context).commManager.isConnected) {
            AppLog.d("AutoStartReceiver: Already connected to Android Auto. Ignoring BT event.")
            return
        }

        if (action == BluetoothDevice.ACTION_ACL_CONNECTED) {
            val device = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            }

            if (device == null) return

            AppLog.i("BT Device connected: ${device.name} (${device.address})")

            // Filter out non-phone devices like OBD scanners, Bluetooth remotes/mice, etc.
            val btClass = try { device.bluetoothClass } catch (_: SecurityException) { null }
            val isPeripheral = btClass?.majorDeviceClass == BluetoothClass.Device.Major.PERIPHERAL
            val isObd = (device.name?.contains("OBD", ignoreCase = true) == true) || 
                        (device.name?.contains("ELM327", ignoreCase = true) == true)
            if (isPeripheral || isObd) {
                AppLog.d("AutoStartReceiver: Ignoring non-phone Bluetooth device: ${device.name} (${device.address})")
                return
            }

            val removedMacs = if (!isLocked) App.provide(context).settings.autoStartBluetoothDeviceMacsRemoved else emptySet()
            if (device.address in removedMacs) {
                AppLog.d("AutoStartReceiver: Device explicitly removed by user: ${device.address}. Ignoring.")
                return
            }

            // Auto-detect and register this device so user never needs to reconnect inside app
            val targetMacs = Settings.getAutoStartBtMacs(context)
            val devName = device.name?.takeIf { it.isNotBlank() } ?: device.address

            if (!targetMacs.contains(device.address)) {
                val updated = targetMacs + device.address
                if (!isLocked) {
                    val settings = App.provide(context).settings
                    settings.autoStartBluetoothDeviceMacs = updated
                    settings.autoStartBluetoothDeviceName = devName
                }
                Settings.syncAutoStartBtMacsToDeviceStorage(context, updated)
                AppLog.i("AutoStartReceiver: Auto-registered connected Bluetooth phone: $devName (${device.address})")
            }

            AppLog.i("Starting AapService via Bluetooth Auto-start for $devName...")

            // Start the service to make the app alive. Explicit action so onStartCommand
            // re-arms wireless mode even if the service process was already running from
            // an earlier session (onCreate's init only runs once) — see ACTION_BT_AUTO_START.
            val serviceIntent = Intent(context, AapService::class.java).setAction(AapService.ACTION_BT_AUTO_START).apply {
                putExtra(AapService.EXTRA_MAC, device.address)
            }
            var serviceStarted = true
            try {
                androidx.core.content.ContextCompat.startForegroundService(context, serviceIntent)
            } catch (e: Exception) {
                serviceStarted = false
                AppLog.e("Failed to start AapService from background: ${e.message}")
            }

            // Also attempt to start the UI
            val launchIntent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(MainActivity.EXTRA_LAUNCH_SOURCE, "Bluetooth auto-start")
            }
            try {
                context.startActivity(launchIntent)
            } catch (e: Exception) {
                AppLog.w("Could not start UI from background (expected on Android 10+): ${e.message}")
            }

            // Android 12+ refuses a background foreground-service start unless the app is
            // exempt, and a blocked startActivity() does not throw, so nothing above would
            // bring the app up. A full-screen notification is still allowed to open the
            // activity, which starts the service itself — as WifiAutoStartReceiver does.
            if (!serviceStarted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                showLaunchNotification(context, launchIntent, devName)
            }
        }
    }

    private fun showLaunchNotification(context: Context, launchIntent: Intent, deviceLabel: String) {
        try {
            val pendingIntent = PendingIntent.getActivity(
                context, 0, launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(context, App.bootStartChannel)
                .setSmallIcon(R.drawable.ic_stat_aa)
                .setContentTitle(context.getString(R.string.auto_start_bt_label))
                .setContentText(context.getString(R.string.wifi_autostart_content, deviceLabel))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_EVENT)
                .setAutoCancel(true)
                .setFullScreenIntent(pendingIntent, true)
                .setContentIntent(pendingIntent)
                .build()
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(BT_AUTO_START_NOTIFICATION_ID, notification)
            AppLog.i("AutoStartReceiver: Triggered FullScreenIntent notification.")
        } catch (e: Exception) {
            AppLog.e("AutoStartReceiver: Could not post launch notification: ${e.message}")
        }
    }

    private companion object {
        // WifiAutoStartReceiver uses 99; kept apart so one does not replace the other.
        const val BT_AUTO_START_NOTIFICATION_ID = 98
    }
}