package com.headunit.wirelessadb

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.headunit.wirelessadb.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences
    private var isAdbActive: Boolean = false
    private var currentPort: Int = AdbController.DEFAULT_PORT
    private var currentIp: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences("headunit_adb_prefs", Context.MODE_PRIVATE)
        currentPort = prefs.getInt("adb_port", AdbController.DEFAULT_PORT)

        setupUI()
        setupListeners()
        setupDiagnostics()

        // Initial check
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    override fun onDestroy() {
        super.onDestroy()
        DiagnosticsLogger.setListener(null)
    }

    private fun setupUI() {
        binding.tvLogs.movementMethod = ScrollingMovementMethod()

        val platform = AdbController.getSystemProperty("ro.board.platform", "ac8227l")
        val buildId = Build.DISPLAY
        val androidVer = Build.VERSION.RELEASE
        binding.tvDeviceSubtitle.text = "$platform | Android $androidVer | $buildId"

        binding.tvPort.text = currentPort.toString()

        val autoBoot = prefs.getBoolean("auto_enable_on_boot", true)
        binding.switchAutoBoot.isChecked = autoBoot
    }

    private fun setupListeners() {
        binding.btnRefresh.setOnClickListener {
            DiagnosticsLogger.log("Manually refreshing status...")
            refreshStatus()
        }

        binding.btnToggleAdb.setOnClickListener {
            if (isAdbActive) {
                disableAdb()
            } else {
                enableAdb()
            }
        }

        binding.btnRestartDaemon.setOnClickListener {
            restartDaemon()
        }

        binding.btnCopyCommand.setOnClickListener {
            copyConnectCommand()
        }

        binding.switchAutoBoot.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("auto_enable_on_boot", isChecked).apply()
            DiagnosticsLogger.log("Auto-enable on boot changed to: $isChecked")
            val text = if (isChecked) "Auto-enable on boot: Enabled" else "Auto-enable on boot: Disabled"
            Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
        }

        binding.btnDevOptions.setOnClickListener {
            DiagnosticsLogger.log("Launching Developer Options intent...")
            CarShortcuts.openDeveloperOptions(this)
        }

        binding.btnMtkEngineer.setOnClickListener {
            DiagnosticsLogger.log("Launching MediaTek Engineer Mode intent...")
            CarShortcuts.openMtkEngineerMode(this)
        }

        binding.btnAndroidSettings.setOnClickListener {
            DiagnosticsLogger.log("Launching Android Settings...")
            CarShortcuts.openAndroidSettings(this)
        }

        binding.btnFactoryCodes.setOnClickListener {
            showFactoryCodesDialog()
        }

        binding.btnCopyLogs.setOnClickListener {
            val report = DiagnosticsLogger.getDeviceDiagnosticsReport() + "\n\n=== Execution Logs ===\n" + DiagnosticsLogger.getAllLogs()
            copyToClipboard("HeadUnit Diagnostics", report)
            Toast.makeText(this, R.string.toast_logs_copied, Toast.LENGTH_SHORT).show()
        }

        binding.btnClearLogs.setOnClickListener {
            DiagnosticsLogger.clear()
        }
    }

    private fun setupDiagnostics() {
        DiagnosticsLogger.setListener { logs ->
            runOnUiThread {
                binding.tvLogs.text = logs
                // Auto-scroll to bottom
                val scrollAmount = binding.tvLogs.layout?.let {
                    it.getLineTop(binding.tvLogs.lineCount) - binding.tvLogs.height
                } ?: 0
                if (scrollAmount > 0) {
                    binding.tvLogs.scrollTo(0, scrollAmount)
                }
            }
        }

        DiagnosticsLogger.log("HeadUnit Wireless ADB initialized.")
        DiagnosticsLogger.log(DiagnosticsLogger.getDeviceDiagnosticsReport())
    }

    private fun refreshStatus() {
        lifecycleScope.launch {
            binding.tvStatusBadge.text = getString(R.string.status_checking)
            binding.btnToggleAdb.isEnabled = false

            val (hasRoot, rootInfo) = withContext(Dispatchers.IO) {
                AdbController.checkRoot()
            }
            binding.tvRootBadge.text = if (hasRoot) "Root: Active" else "Root: None"
            binding.tvRootBadge.setTextColor(
                ContextCompat.getColor(this@MainActivity, if (hasRoot) R.color.accent_green else R.color.text_muted)
            )

            currentIp = withContext(Dispatchers.IO) {
                NetworkUtils.getPrimaryIpAddress()
            }
            val ssid = NetworkUtils.getWifiSsid(this@MainActivity)

            binding.tvIpAddress.text = currentIp ?: "No Wi-Fi Connection"
            binding.tvWifiSsid.text = "SSID: $ssid"

            val listening = withContext(Dispatchers.IO) {
                AdbController.isPortListening(currentPort)
            }
            isAdbActive = listening

            updateStatusUI(listening)
            binding.btnToggleAdb.isEnabled = true
        }
    }

    private fun updateStatusUI(active: Boolean) {
        val ip = currentIp ?: "<head_unit_ip>"
        val command = "adb connect $ip:$currentPort"
        binding.tvConnectCommand.text = command

        if (active) {
            binding.tvStatusBadge.text = getString(R.string.status_active)
            binding.tvStatusBadge.setBackgroundResource(R.drawable.bg_active_badge)
            binding.tvStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.accent_green))

            binding.btnToggleAdb.text = getString(R.string.btn_disable)
            binding.btnToggleAdb.backgroundTintList = ContextCompat.getColorStateList(this, R.color.accent_red_dark)
        } else {
            binding.tvStatusBadge.text = getString(R.string.status_inactive)
            binding.tvStatusBadge.setBackgroundResource(R.drawable.bg_inactive_badge)
            binding.tvStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.accent_red))

            binding.btnToggleAdb.text = getString(R.string.btn_enable)
            binding.btnToggleAdb.backgroundTintList = ContextCompat.getColorStateList(this, R.color.accent_green_dark)
        }
    }

    private fun enableAdb() {
        lifecycleScope.launch {
            binding.btnToggleAdb.isEnabled = false
            binding.btnToggleAdb.text = "ENABLING..."
            DiagnosticsLogger.log("--> Request to ENABLE Wireless ADB on port $currentPort")

            val result = AdbController.enableWirelessAdb(this@MainActivity, currentPort)
            for (line in result.logs) {
                DiagnosticsLogger.log(line)
            }
            DiagnosticsLogger.log("<-- Result: ${result.message}")

            Toast.makeText(this@MainActivity, result.message, Toast.LENGTH_LONG).show()
            refreshStatus()
        }
    }

    private fun disableAdb() {
        lifecycleScope.launch {
            binding.btnToggleAdb.isEnabled = false
            binding.btnToggleAdb.text = "DISABLING..."
            DiagnosticsLogger.log("--> Request to DISABLE Wireless ADB")

            val result = AdbController.disableWirelessAdb()
            for (line in result.logs) {
                DiagnosticsLogger.log(line)
            }
            DiagnosticsLogger.log("<-- Result: ${result.message}")

            Toast.makeText(this@MainActivity, result.message, Toast.LENGTH_SHORT).show()
            refreshStatus()
        }
    }

    private fun restartDaemon() {
        lifecycleScope.launch {
            binding.btnRestartDaemon.isEnabled = false
            DiagnosticsLogger.log("--> Request to RESTART ADB daemon")

            val result = AdbController.restartAdbDaemon(currentPort)
            for (line in result.logs) {
                DiagnosticsLogger.log(line)
            }
            DiagnosticsLogger.log("<-- Result: ${result.message}")

            Toast.makeText(this@MainActivity, result.message, Toast.LENGTH_SHORT).show()
            binding.btnRestartDaemon.isEnabled = true
            refreshStatus()
        }
    }

    private fun copyConnectCommand() {
        val ip = currentIp
        if (ip.isNullOrEmpty()) {
            Toast.makeText(this, "Connect your head unit to Wi-Fi first!", Toast.LENGTH_SHORT).show()
            return
        }
        val command = "adb connect $ip:$currentPort"
        copyToClipboard("ADB Connect Command", command)
        Toast.makeText(this, getString(R.string.toast_copied), Toast.LENGTH_SHORT).show()
    }

    private fun copyToClipboard(label: String, text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        clipboard.setPrimaryClip(clip)
    }

    private fun showFactoryCodesDialog() {
        val info = CarShortcuts.getFactoryPasscodesInfo()
        AlertDialog.Builder(this)
            .setTitle("AC8227L Factory Passcodes")
            .setMessage(info)
            .setPositiveButton("OK", null)
            .setNeutralButton("Copy") { _, _ ->
                copyToClipboard("Factory Passcodes", info)
                Toast.makeText(this, "Passcodes copied to clipboard", Toast.LENGTH_SHORT).show()
            }
            .show()
    }
}
