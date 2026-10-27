package com.andrerinas.openheadunit.ota

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.utils.AppLog

class OtaInstallReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_INSTALL_STATUS = "com.wsknnew.diautonew.ota.INSTALL_STATUS"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_INSTALL_STATUS) return

        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)

        AppLog.i("OtaInstallReceiver: status=$status, message=$message")

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // User confirmation required on some Android versions / OEM launchers
                val confirmIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                confirmIntent?.let {
                    it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(it)
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                AppLog.i("OtaInstallReceiver: Update installed successfully! Relaunching app.")
                try {
                    Toast.makeText(context, context.getString(R.string.update_installing), Toast.LENGTH_SHORT).show()
                } catch (_: Exception) {}
                val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
                launchIntent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                launchIntent?.let { context.startActivity(it) }
            }
            else -> {
                AppLog.e("OtaInstallReceiver: Install failed: status=$status, message=$message")
                try {
                    Toast.makeText(context, "Installation failed: $message", Toast.LENGTH_LONG).show()
                } catch (_: Exception) {}
            }
        }
    }
}
