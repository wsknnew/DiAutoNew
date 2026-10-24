package com.andrerinas.openheadunit.main

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.aap.AapProjectionActivity
import com.andrerinas.openheadunit.aap.AapService
import com.andrerinas.openheadunit.aap.WirelessScopePolicy
import com.andrerinas.openheadunit.connection.CommManager
import com.andrerinas.openheadunit.utils.AppThemeManager
import com.andrerinas.openheadunit.utils.BluetoothHelper
import com.andrerinas.openheadunit.utils.HotspotConnectionHelper
import com.andrerinas.openheadunit.utils.Settings
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The car's cockpit standby screen. Wireless pairing is the primary path; USB remains a fallback. */
class HomeFragment : Fragment() {
    private val app get() = App.provide(requireContext())
    private var activeDialog: androidx.appcompat.app.AlertDialog? = null
    private var hasAttemptedAutoConnectOnOpen = false
    private var autoConnectJob: Job? = null

    private val bluetoothPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            results[Manifest.permission.BLUETOOTH_CONNECT] == true
        } else {
            true
        }
        if (granted) {
            updateDeviceUi()
            val preferred = getPreferredDevice()
            if (preferred != null) {
                connectToDevice(preferred)
            } else {
                selectPhone()
            }
        } else {
            showBluetoothHelp(R.string.bt_permission_denied)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View =
        inflater.inflate(R.layout.fragment_home, container, false)

    override fun onViewCreated(view: View, state: Bundle?) {
        val wifiButton = view.findViewById<MaterialButton>(R.id.wifi_button)

        wifiButton.setOnClickListener {
            cancelAutoConnectCountdown()
            if (app.commManager.isConnected) {
                openProjection()
            } else if (app.settings.useHotspotAsDefaultWireless) {
                connectViaHotspot()
            } else {
                requestBluetoothAndConnect()
            }
        }

        wifiButton.setOnLongClickListener {
            if (!app.commManager.isConnected) {
                cancelAutoConnectCountdown()
                if (checkBluetoothPermission()) {
                    selectPhone()
                } else {
                    requestBluetoothAndConnect()
                }
                true
            } else {
                false
            }
        }

        view.findViewById<View>(R.id.usb_button).setOnClickListener {
            cancelAutoConnectCountdown()
            if (app.commManager.isConnected) openProjection()
            else findNavController().navigate(R.id.action_homeFragment_to_usbListFragment)
        }

        view.findViewById<View>(R.id.settings_button).setOnClickListener {
            cancelAutoConnectCountdown()
            startActivity(Intent(requireContext(), SettingsActivity::class.java))
        }

        view.findViewById<View?>(R.id.hotspot_button)?.setOnClickListener {
            cancelAutoConnectCountdown()
            HotspotConnectionDialog.show(childFragmentManager)
        }

        view.findViewById<View?>(R.id.btn_cancel_connect)?.setOnClickListener {
            cancelAutoConnectCountdown()
            app.commManager.disconnect()
            (activity as? MainActivity)?.cancelAutoConnect()
            view.findViewById<View?>(R.id.connection_step_text)?.visibility = View.GONE
            Toast.makeText(context, R.string.connection_cancelled, Toast.LENGTH_SHORT).show()
        }

        view.findViewById<View?>(R.id.theme_toggle_button)?.setOnClickListener {
            val currentTheme = app.settings.appTheme
            val newTheme = if (currentTheme == Settings.AppTheme.DARK || currentTheme == Settings.AppTheme.EXTREME_DARK) {
                Settings.AppTheme.AUTOMATIC
            } else {
                Settings.AppTheme.DARK
            }
            app.settings.appTheme = newTheme
            AppThemeManager.reapply(requireContext(), app.settings)
            Toast.makeText(
                context,
                if (newTheme == Settings.AppTheme.DARK) R.string.theme_dark_enabled else R.string.theme_auto_enabled,
                Toast.LENGTH_SHORT
            ).show()
        }

        view.findViewById<View?>(R.id.audio_mode_button)?.setOnClickListener {
            val newState = !app.settings.musicViaBluetooth
            app.settings.musicViaBluetooth = newState
            updateAudioRoutingUi(view)
            Toast.makeText(
                context,
                if (newState) R.string.da_audio_routing_enabled else R.string.da_audio_routing_disabled,
                Toast.LENGTH_SHORT
            ).show()
        }
        updateAudioRoutingUi(view)

        // Going back to the car does not tear down a connection or disable reconnect.
        view.findViewById<View>(R.id.exit_button).setOnClickListener {
            cancelAutoConnectCountdown()
            requireActivity().moveTaskToBack(true)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.commManager.connectionState.collect { connectionState ->
                    updateConnectionStateUi(connectionState)
                }
            }
        }

        if (app.settings.autoStartOnBoot || app.settings.autoStartOnScreenOn) {
            ContextCompat.startForegroundService(requireContext(), Intent(requireContext(), AapService::class.java))
        }

        updateDeviceUi()
        if (state == null) {
            maybeAutoConnectOnOpen()
        }
    }

    override fun onResume() {
        super.onResume()
        view?.let { updateAudioRoutingUi(it) }
        updateDeviceUi()
        updateConnectionStateUi(app.commManager.connectionState.value)
    }

    private fun updateAudioRoutingUi(currentView: View) {
        val icon = currentView.findViewById<ImageView?>(R.id.audio_mode_icon) ?: return
        val text = currentView.findViewById<TextView?>(R.id.audio_mode_text) ?: return
        val isBtMusic = app.settings.musicViaBluetooth

        if (isBtMusic) {
            text.setText(R.string.audio_mode_car_bt)
            text.setTextColor(ContextCompat.getColor(requireContext(), R.color.da_text))
            icon.imageTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(requireContext(), R.color.da_accent)
            )
        } else {
            text.setText(R.string.audio_mode_aa)
            text.setTextColor(ContextCompat.getColor(requireContext(), R.color.da_muted))
            icon.imageTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(requireContext(), R.color.da_muted)
            )
        }
    }

    private fun cancelAutoConnectCountdown() {
        autoConnectJob?.cancel()
        autoConnectJob = null
        view?.findViewById<View?>(R.id.auto_connect_container)?.visibility = View.GONE
    }

    private fun checkBluetoothPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun requestBluetoothAndConnect(targetDevice: BluetoothDevice? = null) {
        if (!checkBluetoothPermission()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                bluetoothPermissionsLauncher.launch(
                    arrayOf(
                        Manifest.permission.BLUETOOTH_CONNECT,
                        Manifest.permission.BLUETOOTH_SCAN
                    )
                )
            } else {
                if (targetDevice != null) {
                    connectToDevice(targetDevice)
                } else {
                    val preferred = getPreferredDevice()
                    if (preferred != null) {
                        connectToDevice(preferred)
                    } else {
                        selectPhone()
                    }
                }
            }
        } else {
            if (targetDevice != null) {
                connectToDevice(targetDevice)
            } else {
                val preferred = getPreferredDevice()
                if (preferred != null) {
                    connectToDevice(preferred)
                } else {
                    selectPhone()
                }
            }
        }
    }

    private fun getPreferredDevice(): BluetoothDevice? {
        val dev = BluetoothHelper.getActiveOrPreferredDevice(requireContext())
        if (dev != null) {
            val currentMacs = app.settings.autoStartBluetoothDeviceMacs
            if (!currentMacs.contains(dev.address) && dev.address !in app.settings.autoStartBluetoothDeviceMacsRemoved) {
                val updated = currentMacs + dev.address
                app.settings.autoStartBluetoothDeviceMacs = updated
                app.settings.autoStartBluetoothDeviceName = dev.name?.takeIf { it.isNotBlank() } ?: dev.address
                Settings.syncAutoStartBtMacsToDeviceStorage(requireContext(), updated)
            }
        }
        return dev
    }

    private fun updateDeviceUi() {
        val currentView = view ?: return
        val container = currentView.findViewById<View>(R.id.device_info_container) ?: return
        val nameView = currentView.findViewById<TextView>(R.id.preferred_device_name) ?: return
        val switchBtn = currentView.findViewById<TextView>(R.id.btn_switch_device) ?: return

        val preferred = getPreferredDevice()
        container.visibility = View.VISIBLE
        if (preferred != null) {
            val devName = preferred.name?.takeIf { it.isNotBlank() } ?: preferred.address
            val isConnected = BluetoothHelper.isDeviceConnected(preferred)
            nameView.text = if (isConnected) "$devName (${getString(R.string.connected)})" else devName
            switchBtn.text = getString(R.string.da_switch_phone)
            switchBtn.setOnClickListener {
                cancelAutoConnectCountdown()
                if (checkBluetoothPermission()) selectPhone()
                else requestBluetoothAndConnect()
            }
        } else {
            val adapter = BluetoothHelper.getBluetoothAdapter(requireContext())
            val hasBonded = try {
                adapter?.isEnabled == true && !adapter.bondedDevices.isNullOrEmpty()
            } catch (_: SecurityException) { false }

            if (hasBonded) {
                nameView.text = getString(R.string.da_paired_phone)
                switchBtn.text = getString(R.string.da_connect)
            } else {
                nameView.text = getString(R.string.no_paired_devices_found)
                switchBtn.text = getString(R.string.da_pair)
            }
            switchBtn.setOnClickListener {
                cancelAutoConnectCountdown()
                if (checkBluetoothPermission()) selectPhone()
                else requestBluetoothAndConnect()
            }
        }
    }

    private fun updateConnectionStateUi(state: CommManager.ConnectionState) {
        val currentView = view ?: return
        val statusView = currentView.findViewById<TextView>(R.id.connection_status) ?: return
        val wifiButton = currentView.findViewById<MaterialButton>(R.id.wifi_button) ?: return
        val badge = currentView.findViewById<TextView>(R.id.connection_status_badge)
        val statusDot = currentView.findViewById<ImageView>(R.id.status_dot)
        val cancelBtn = currentView.findViewById<View>(R.id.btn_cancel_connect)
        val stepText = currentView.findViewById<TextView>(R.id.connection_step_text)

        val isConnected = state is CommManager.ConnectionState.TransportStarted || app.commManager.isConnected
        val isConnecting = !isConnected && (state is CommManager.ConnectionState.Connecting ||
                state is CommManager.ConnectionState.StartingTransport ||
                state is CommManager.ConnectionState.Connected ||
                state is CommManager.ConnectionState.HandshakeComplete)

        cancelBtn?.visibility = if (isConnecting) View.VISIBLE else View.GONE

        when {
            isConnected -> {
                cancelAutoConnectCountdown()
                statusView.setText(R.string.da_connected)
                wifiButton.isEnabled = true
                wifiButton.setText(R.string.da_open)
                wifiButton.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.material_green_700))
                badge?.visibility = View.VISIBLE
                badge?.setText(R.string.connected)
                statusDot?.setImageResource(R.drawable.shape_dot_green)
                stepText?.visibility = View.GONE
            }
            isConnecting -> {
                cancelAutoConnectCountdown()
                statusView.setText(R.string.da_connecting)
                wifiButton.isEnabled = false
                val preferred = getPreferredDevice()
                val targetName = preferred?.name?.takeIf { it.isNotBlank() }
                if (targetName != null) {
                    wifiButton.text = getString(R.string.da_connecting_to, targetName)
                } else {
                    wifiButton.setText(R.string.da_connecting)
                }
                wifiButton.backgroundTintList = android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(requireContext(), R.color.da_accent)
                )
                badge?.visibility = View.VISIBLE
                badge?.setText(R.string.da_connecting)
                statusDot?.setImageResource(R.drawable.shape_dot_amber)

                // 3-Stage Progress Feedback
                stepText?.visibility = View.VISIBLE
                when (state) {
                    is CommManager.ConnectionState.Connecting -> {
                        stepText?.setText(R.string.step_1_bt)
                    }
                    is CommManager.ConnectionState.Connected,
                    is CommManager.ConnectionState.StartingTransport -> {
                        stepText?.setText(R.string.step_2_wifi)
                    }
                    is CommManager.ConnectionState.HandshakeComplete -> {
                        stepText?.setText(R.string.step_3_aa)
                    }
                    else -> {
                        stepText?.setText(R.string.step_1_bt)
                    }
                }
            }
            else -> {
                statusView.setText(R.string.da_ready)
                wifiButton.isEnabled = true
                wifiButton.backgroundTintList = android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(requireContext(), R.color.da_accent)
                )
                badge?.visibility = View.VISIBLE
                badge?.setText(R.string.da_ready)
                statusDot?.setImageResource(R.drawable.shape_dot_green)
                stepText?.visibility = View.GONE

                if (app.settings.useHotspotAsDefaultWireless) {
                    wifiButton.setText(R.string.da_hotspot_connect)
                } else {
                    val preferred = getPreferredDevice()
                    val targetName = preferred?.name?.takeIf { it.isNotBlank() }
                    if (targetName != null) {
                        wifiButton.text = getString(R.string.da_connect_to, targetName)
                    } else {
                        wifiButton.setText(R.string.da_connect)
                    }
                }
            }
        }
    }

    private fun connectToDevice(device: BluetoothDevice) {
        val devName = device.name?.takeIf { it.isNotBlank() } ?: getString(R.string.da_device)

        // Save as auto-start Bluetooth device so the car auto-connects next time on startup!
        val currentMacs = app.settings.autoStartBluetoothDeviceMacs
        if (!currentMacs.contains(device.address)) {
            val updated = currentMacs + device.address
            app.settings.autoStartBluetoothDeviceMacs = updated
            app.settings.autoStartBluetoothDeviceName = devName
            Settings.syncAutoStartBtMacsToDeviceStorage(requireContext(), updated)
        }
        if (device.address in app.settings.autoStartBluetoothDeviceMacsRemoved) {
            app.settings.autoStartBluetoothDeviceMacsRemoved = app.settings.autoStartBluetoothDeviceMacsRemoved - device.address
        }

        // The button explicitly selects native wireless AA
        app.settings.wifiConnectionMode = 3
        if (!WirelessScopePolicy.allowsWireless(app.settings.connectionModes)) {
            app.settings.connectionModes =
                app.settings.connectionModes + Settings.ConnectionMode.WIFI
        }

        // Update UI immediately
        val currentView = view
        val wifiButton = currentView?.findViewById<MaterialButton>(R.id.wifi_button)
        val statusView = currentView?.findViewById<TextView>(R.id.connection_status)
        val badge = currentView?.findViewById<TextView>(R.id.connection_status_badge)
        val statusDot = currentView?.findViewById<ImageView>(R.id.status_dot)
        val stepText = currentView?.findViewById<TextView>(R.id.connection_step_text)
        val cancelBtn = currentView?.findViewById<View>(R.id.btn_cancel_connect)

        statusView?.setText(R.string.da_connecting)
        wifiButton?.isEnabled = false
        wifiButton?.text = getString(R.string.da_connecting_to, devName)
        badge?.visibility = View.VISIBLE
        badge?.setText(R.string.da_connecting)
        statusDot?.setImageResource(R.drawable.shape_dot_amber)
        stepText?.visibility = View.VISIBLE
        stepText?.setText(R.string.step_1_bt)
        cancelBtn?.visibility = View.VISIBLE

        // Safety watchdog: re-enable button after 8 seconds if connection doesn't transition
        viewLifecycleOwner.lifecycleScope.launch {
            delay(8000)
            if (isAdded && !app.commManager.isConnected) {
                view?.findViewById<MaterialButton>(R.id.wifi_button)?.let { btn ->
                    btn.isEnabled = true
                    if (app.commManager.connectionState.value is CommManager.ConnectionState.Disconnected) {
                        val preferred = getPreferredDevice()
                        val name = preferred?.name?.takeIf { it.isNotBlank() }
                        btn.text = if (name != null) getString(R.string.da_connect_to, name) else getString(R.string.da_connect)
                    }
                }
            }
        }

        (activity as? MainActivity)?.beginAutoConnect(
            "manual native wireless", MainActivity.ConnectionUiMode.PILL)
        ContextCompat.startForegroundService(requireContext(), Intent(requireContext(), AapService::class.java).apply {
            action = AapService.ACTION_NATIVE_AA_POKE
            putExtra(AapService.EXTRA_MAC, device.address)
        })
    }

    private fun connectViaHotspot() {
        viewLifecycleOwner.lifecycleScope.launch {
            val status = HotspotConnectionHelper.getNetworkStatus(requireContext())
            val targetIp = status.gatewayIp
            if (status.isWifiConnected && targetIp != null) {
                connectToHotspotIp(targetIp)
            } else {
                HotspotConnectionDialog.show(childFragmentManager)
            }
        }
    }

    private fun connectToHotspotIp(targetIp: String) {
        app.settings.wifiConnectionMode = 1
        if (!WirelessScopePolicy.allowsWireless(app.settings.connectionModes)) {
            app.settings.connectionModes =
                app.settings.connectionModes + Settings.ConnectionMode.WIFI
        }

        val currentView = view
        val wifiButton = currentView?.findViewById<MaterialButton>(R.id.wifi_button)
        val statusView = currentView?.findViewById<TextView>(R.id.connection_status)
        val badge = currentView?.findViewById<TextView>(R.id.connection_status_badge)
        val statusDot = currentView?.findViewById<ImageView>(R.id.status_dot)
        val stepText = currentView?.findViewById<TextView>(R.id.connection_step_text)
        val cancelBtn = currentView?.findViewById<View>(R.id.btn_cancel_connect)

        statusView?.setText(R.string.da_connecting)
        wifiButton?.isEnabled = false
        wifiButton?.text = getString(R.string.da_connecting_to, targetIp)
        badge?.visibility = View.VISIBLE
        badge?.setText(R.string.da_connecting)
        statusDot?.setImageResource(R.drawable.shape_dot_amber)
        stepText?.visibility = View.VISIBLE
        stepText?.setText(R.string.step_2_wifi)
        cancelBtn?.visibility = View.VISIBLE

        viewLifecycleOwner.lifecycleScope.launch {
            delay(8000)
            if (isAdded && !app.commManager.isConnected) {
                view?.findViewById<MaterialButton>(R.id.wifi_button)?.let { btn ->
                    btn.isEnabled = true
                    if (app.commManager.connectionState.value is CommManager.ConnectionState.Disconnected) {
                        btn.setText(if (app.settings.useHotspotAsDefaultWireless) R.string.da_hotspot_connect else R.string.da_connect)
                    }
                }
            }
        }

        (activity as? MainActivity)?.beginAutoConnect(
            "manual hotspot AA $targetIp:5277", MainActivity.ConnectionUiMode.PILL)

        val ctx = requireContext().applicationContext
        try {
            ContextCompat.startForegroundService(ctx, Intent(ctx, AapService::class.java).apply {
                action = AapService.ACTION_CONNECT_SOCKET
            })
        } catch (_: Exception) {}

        App.provide(ctx).commManager.connectAsync(targetIp, HotspotConnectionHelper.AAP_TCP_PORT)
    }

    private fun maybeAutoConnectOnOpen() {
        if (hasAttemptedAutoConnectOnOpen) return
        if (app.commManager.isConnected) return

        if (app.settings.autoConnectOnHotspot) {
            viewLifecycleOwner.lifecycleScope.launch {
                val status = HotspotConnectionHelper.getNetworkStatus(requireContext())
                val targetIp = status.gatewayIp
                if (status.isWifiConnected && targetIp != null) {
                    hasAttemptedAutoConnectOnOpen = true
                    delay(400)
                    if (isAdded && !app.commManager.isConnected && view != null) {
                        connectToHotspotIp(targetIp)
                    }
                    return@launch
                }
            }
        }

        if (!app.settings.autoConnectOnOpen) return

        val preferred = getPreferredDevice()
        if (preferred == null && !app.settings.useHotspotAsDefaultWireless) return
        hasAttemptedAutoConnectOnOpen = true

        val currentView = view ?: return
        val autoContainer = currentView.findViewById<View?>(R.id.auto_connect_container)
        val autoText = currentView.findViewById<TextView?>(R.id.auto_connect_text)
        val cancelAutoBtn = currentView.findViewById<View?>(R.id.btn_cancel_auto_connect)

        val devName = preferred?.name?.takeIf { it.isNotBlank() } ?: getString(R.string.da_device)

        cancelAutoBtn?.setOnClickListener {
            cancelAutoConnectCountdown()
        }

        autoConnectJob = viewLifecycleOwner.lifecycleScope.launch {
            autoContainer?.visibility = View.VISIBLE
            for (seconds in 4 downTo 1) {
                if (!isAdded || app.commManager.isConnected) {
                    autoContainer?.visibility = View.GONE
                    return@launch
                }
                if (app.settings.useHotspotAsDefaultWireless) {
                    autoText?.text = getString(R.string.da_auto_connecting_hotspot_countdown, seconds)
                } else {
                    autoText?.text = getString(R.string.auto_connecting_countdown, devName, seconds)
                }
                delay(1000)
            }
            autoContainer?.visibility = View.GONE
            if (isAdded && !app.commManager.isConnected && view != null) {
                if (app.settings.useHotspotAsDefaultWireless) {
                    connectViaHotspot()
                } else if (preferred != null) {
                    connectToDevice(preferred)
                }
            }
        }
    }

    private fun openProjection() {
        startActivity(AapProjectionActivity.intent(requireContext()).apply {
            putExtra(AapProjectionActivity.EXTRA_FOCUS, true)
        })
    }

    private fun selectPhone() {
        val adapter = BluetoothHelper.getBluetoothAdapter(requireContext())
        if (adapter == null || !adapter.isEnabled) {
            showBluetoothHelp(R.string.da_bluetooth_off)
            return
        }

        activeDialog?.dismiss()
        activeDialog = null

        BluetoothDeviceSelectionDialog.show(childFragmentManager) { device ->
            connectToDevice(device)
            updateDeviceUi()
        }
    }

    private fun showBluetoothHelp(message: Int) {
        activeDialog?.dismiss()
        activeDialog = MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
            .setTitle(R.string.da_pair).setMessage(message)
            .setPositiveButton(R.string.da_system_settings) { _, _ -> openBluetoothSettings() }
            .setNegativeButton(R.string.cancel, null).show()
    }

    private fun openBluetoothSettings() {
        try { startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)) }
        catch (_: android.content.ActivityNotFoundException) {
            startActivity(Intent(android.provider.Settings.ACTION_SETTINGS))
        }
    }

    override fun onPause() {
        cancelAutoConnectCountdown()
        activeDialog?.dismiss()
        activeDialog = null
        super.onPause()
    }

    companion object {
        // Retained for existing automation entry points; self projection has no standby control.
        var forceSelfModeLaunch = false
        fun resetAutoStart() { forceSelfModeLaunch = false }
    }
}
