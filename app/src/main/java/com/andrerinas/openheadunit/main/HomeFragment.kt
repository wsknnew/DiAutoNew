package com.andrerinas.openheadunit.main

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.hardware.usb.UsbManager
import android.net.wifi.WifiManager
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
import androidx.fragment.app.activityViewModels
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
import com.andrerinas.openheadunit.connection.UsbAccessoryMode
import com.andrerinas.openheadunit.connection.UsbDeviceCompat
import com.andrerinas.openheadunit.connection.UsbReceiver
import com.andrerinas.openheadunit.utils.AppThemeManager
import com.andrerinas.openheadunit.utils.BluetoothHelper
import com.andrerinas.openheadunit.utils.HotspotConnectionHelper
import com.andrerinas.openheadunit.utils.Settings
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The car's cockpit standby screen. Wireless and USB connections are both primary 1-tap options. */
class HomeFragment : Fragment() {
    private val app get() = App.provide(requireContext())
    private val mainViewModel: MainViewModel by activityViewModels()
    private var detectedUsbDevice: UsbDeviceCompat? = null
    private var activeDialog: androidx.appcompat.app.AlertDialog? = null
    private var hasAttemptedAutoConnectOnOpen = false
    private var autoConnectJob: Job? = null

    private val glanceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            updateQuickGlanceUi()
        }
    }

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
            } else {
                handleWirelessConnect()
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

        // 1-Tap USB Connect: Button, card click, and quick glance pill
        val usbConnectButton = view.findViewById<MaterialButton?>(R.id.usb_connect_button)
        usbConnectButton?.setOnClickListener {
            handleUsbConnect()
        }

        view.findViewById<View?>(R.id.usb_button)?.setOnClickListener {
            handleUsbConnect()
        }

        view.findViewById<View?>(R.id.btn_usb_manage)?.setOnClickListener {
            cancelAutoConnectCountdown()
            findNavController().navigate(R.id.action_homeFragment_to_usbListFragment)
        }

        view.findViewById<View?>(R.id.glance_usb_card)?.setOnClickListener {
            handleUsbConnect()
        }

        view.findViewById<View?>(R.id.glance_usb_card)?.setOnLongClickListener {
            cancelAutoConnectCountdown()
            findNavController().navigate(R.id.action_homeFragment_to_usbListFragment)
            true
        }

        // Observe USB devices in real time on main dashboard
        mainViewModel.usbDevices.observe(viewLifecycleOwner) { devices ->
            updateUsbDeviceUi(devices)
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

        // Quick Glance Cards (Wi-Fi & Bluetooth)
        view.findViewById<View?>(R.id.glance_wifi_card)?.setOnClickListener {
            cancelAutoConnectCountdown()
            HotspotConnectionDialog.show(childFragmentManager)
        }
        view.findViewById<View?>(R.id.glance_wifi_card)?.setOnLongClickListener {
            try {
                startActivity(Intent(android.provider.Settings.ACTION_WIFI_SETTINGS))
            } catch (_: Exception) {
                try { startActivity(Intent(android.provider.Settings.ACTION_SETTINGS)) } catch (_: Exception) {}
            }
            true
        }

        view.findViewById<View?>(R.id.glance_bluetooth_card)?.setOnClickListener {
            cancelAutoConnectCountdown()
            if (checkBluetoothPermission()) selectPhone()
            else requestBluetoothAndConnect()
        }
        view.findViewById<View?>(R.id.glance_bluetooth_card)?.setOnLongClickListener {
            openBluetoothSettings()
            true
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    updateQuickGlanceUi()
                    delay(3000)
                }
            }
        }

        updateDeviceUi()
        updateQuickGlanceUi()
        if (state == null) {
            maybeAutoConnectOnOpen()
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        }
        try {
            ContextCompat.registerReceiver(requireContext(), glanceReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        } catch (_: Exception) {}
    }

    override fun onStop() {
        super.onStop()
        try {
            requireContext().unregisterReceiver(glanceReceiver)
        } catch (_: Exception) {}
    }

    override fun onResume() {
        super.onResume()
        view?.let { updateAudioRoutingUi(it) }
        updateDeviceUi()
        updateQuickGlanceUi()
        updateUsbDeviceUi(mainViewModel.usbDevices.value)
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

    private fun handleWirelessConnect() {
        viewLifecycleOwner.lifecycleScope.launch {
            val netStatus = HotspotConnectionHelper.getNetworkStatus(requireContext())
            if (netStatus.isWifiConnected && (app.settings.useHotspotAsDefaultWireless || netStatus.isPhoneHotspot || netStatus.gatewayIp != null)) {
                val targetIp = netStatus.gatewayIp ?: app.settings.customHotspotPhoneIp.ifEmpty { "192.168.43.1" }
                connectToHotspotIp(targetIp)
            } else if (app.settings.useHotspotAsDefaultWireless) {
                connectViaHotspot()
            } else {
                requestBluetoothAndConnect()
            }
        }
    }

    private fun handleUsbConnect() {
        cancelAutoConnectCountdown()
        if (app.commManager.isConnected) {
            openProjection()
            return
        }

        val dev = detectedUsbDevice
        if (dev != null) {
            connectUsbDevice(dev)
        } else {
            val usbManager = requireContext().getSystemService(Context.USB_SERVICE) as UsbManager
            val accessories = usbManager.accessoryList
            if (!accessories.isNullOrEmpty()) {
                Toast.makeText(requireContext(), R.string.android_auto_starting, Toast.LENGTH_SHORT).show()
                (activity as? MainActivity)?.beginAutoConnect(
                    "1-tap USB (accessory mode)",
                    MainActivity.ConnectionUiMode.OVERLAY
                )
                ContextCompat.startForegroundService(requireContext(), Intent(requireContext(), AapService::class.java).apply {
                    action = AapService.ACTION_CHECK_USB
                })
            } else {
                Toast.makeText(requireContext(), R.string.da_plug_in_usb_hint, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun connectUsbDevice(device: UsbDeviceCompat) {
        if (device.isInAccessoryMode) {
            Toast.makeText(requireContext(), R.string.android_auto_starting, Toast.LENGTH_SHORT).show()
            (activity as? MainActivity)?.beginAutoConnect(
                "1-tap USB (accessory mode)",
                MainActivity.ConnectionUiMode.OVERLAY
            )
            ContextCompat.startForegroundService(requireContext(), Intent(requireContext(), AapService::class.java).apply {
                action = AapService.ACTION_CHECK_USB
            })
        } else {
            val usbManager = requireContext().getSystemService(Context.USB_SERVICE) as UsbManager
            if (usbManager.hasPermission(device.wrappedDevice)) {
                val usbMode = UsbAccessoryMode(usbManager)
                if (usbMode.connectAndSwitch(device.wrappedDevice, app.settings.useLibusb)) {
                    Toast.makeText(requireContext(), R.string.switching_to_android_auto, Toast.LENGTH_SHORT).show()
                    (activity as? MainActivity)?.beginAutoConnect(
                        "1-tap USB (AOA switch)",
                        MainActivity.ConnectionUiMode.OVERLAY
                    )
                } else {
                    Toast.makeText(requireContext(), R.string.switch_failed, Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(requireContext(), R.string.requesting_usb_permission, Toast.LENGTH_SHORT).show()
                ContextCompat.startForegroundService(requireContext(), Intent(requireContext(), AapService::class.java))
                usbManager.requestPermission(
                    device.wrappedDevice,
                    UsbReceiver.createPermissionPendingIntent(requireContext())
                )
            }
        }
    }

    private fun getFriendlyUsbDeviceName(device: UsbDeviceCompat): String {
        val dev = device.wrappedDevice
        val mfg = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) dev.manufacturerName?.trim() else null
        val prod = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) dev.productName?.trim() else null
        val combined = listOfNotNull(mfg?.takeIf { it.isNotBlank() }, prod?.takeIf { it.isNotBlank() }).joinToString(" ")
        if (combined.isNotBlank()) return combined
        if (device.isInAccessoryMode) return "Android Auto Accessory"
        val name = device.deviceName.substringAfterLast('/')
        return if (name.isNotBlank()) name else getString(R.string.da_device)
    }

    private fun updateUsbGlanceUi(currentView: View, ctx: Context, device: UsbDeviceCompat?) {
        val glanceUsbStatus = currentView.findViewById<TextView?>(R.id.glance_usb_status) ?: return
        val glanceUsbDot = currentView.findViewById<ImageView?>(R.id.glance_usb_dot) ?: return
        val glanceUsbIcon = currentView.findViewById<ImageView?>(R.id.glance_usb_icon) ?: return

        val usbManager = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        val hasAccessory = !usbManager.accessoryList.isNullOrEmpty()
        val isConnected = app.commManager.isConnected

        if (isConnected && app.settings.lastConnectionType == Settings.CONNECTION_TYPE_USB) {
            glanceUsbStatus.setText(R.string.connected)
            glanceUsbStatus.setTextColor(ContextCompat.getColor(ctx, R.color.da_text))
            glanceUsbDot.setImageResource(R.drawable.shape_dot_green)
            glanceUsbIcon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.da_accent))
        } else if (device != null) {
            glanceUsbStatus.text = getFriendlyUsbDeviceName(device)
            glanceUsbStatus.setTextColor(ContextCompat.getColor(ctx, R.color.da_text))
            glanceUsbDot.setImageResource(R.drawable.shape_dot_green)
            glanceUsbIcon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.da_accent))
        } else if (hasAccessory) {
            glanceUsbStatus.setText(R.string.glance_usb_connected)
            glanceUsbStatus.setTextColor(ContextCompat.getColor(ctx, R.color.da_text))
            glanceUsbDot.setImageResource(R.drawable.shape_dot_green)
            glanceUsbIcon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.da_accent))
        } else {
            glanceUsbStatus.setText(R.string.glance_usb_disconnected)
            glanceUsbStatus.setTextColor(ContextCompat.getColor(ctx, R.color.da_muted))
            glanceUsbDot.setImageResource(R.drawable.shape_dot_grey)
            glanceUsbIcon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.da_muted))
        }
    }

    private fun updateUsbDeviceUi(devices: List<UsbDeviceCompat>?) {
        val currentView = view ?: return
        val ctx = context ?: return
        val usbManager = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        val hasAccessory = !usbManager.accessoryList.isNullOrEmpty()

        val firstDevice = devices?.firstOrNull()
        detectedUsbDevice = firstDevice

        val isConnected = app.commManager.isConnected

        // Update glance item
        updateUsbGlanceUi(currentView, ctx, firstDevice)

        // Update Cockpit USB Card
        val usbStatusView = currentView.findViewById<TextView?>(R.id.usb_connection_status)
        val usbDeviceNameView = currentView.findViewById<TextView?>(R.id.usb_device_name)
        val usbStatusDot = currentView.findViewById<ImageView?>(R.id.usb_status_dot)
        val usbConnectButton = currentView.findViewById<MaterialButton?>(R.id.usb_connect_button)

        if (isConnected) {
            usbStatusView?.setText(R.string.da_connected)
            usbStatusDot?.setImageResource(R.drawable.shape_dot_green)
            usbConnectButton?.isEnabled = true
            usbConnectButton?.setText(R.string.da_open)
            usbConnectButton?.setBackgroundColor(ContextCompat.getColor(ctx, R.color.material_green_700))
            usbConnectButton?.setTextColor(ContextCompat.getColor(ctx, android.R.color.white))
        } else if (firstDevice != null) {
            val friendly = getFriendlyUsbDeviceName(firstDevice)
            usbStatusView?.setText(if (firstDevice.isInAccessoryMode) R.string.da_usb_ready else R.string.da_usb_detected)
            usbDeviceNameView?.text = friendly
            usbStatusDot?.setImageResource(R.drawable.shape_dot_green)
            usbConnectButton?.isEnabled = true
            usbConnectButton?.setText(R.string.da_connect_usb)
            usbConnectButton?.setBackgroundColor(ContextCompat.getColor(ctx, R.color.da_accent))
            usbConnectButton?.setTextColor(ContextCompat.getColor(ctx, R.color.da_background))
        } else if (hasAccessory) {
            usbStatusView?.setText(R.string.da_usb_ready)
            usbDeviceNameView?.setText(R.string.glance_usb_connected)
            usbStatusDot?.setImageResource(R.drawable.shape_dot_green)
            usbConnectButton?.isEnabled = true
            usbConnectButton?.setText(R.string.da_connect_usb)
            usbConnectButton?.setBackgroundColor(ContextCompat.getColor(ctx, R.color.da_accent))
            usbConnectButton?.setTextColor(ContextCompat.getColor(ctx, R.color.da_background))
        } else {
            usbStatusView?.setText(R.string.da_plug_in_usb)
            usbDeviceNameView?.setText(R.string.da_no_usb_phone)
            usbStatusDot?.setImageResource(R.drawable.shape_dot_grey)
            usbConnectButton?.isEnabled = true
            usbConnectButton?.setText(R.string.da_connect_usb)
            usbConnectButton?.setBackgroundColor(ContextCompat.getColor(ctx, R.color.da_surface))
            usbConnectButton?.setTextColor(ContextCompat.getColor(ctx, R.color.da_muted))
        }
    }

    private fun updateDeviceUi() {
        val currentView = view ?: return
        val container = currentView.findViewById<View>(R.id.device_info_container) ?: return
        val nameView = currentView.findViewById<TextView>(R.id.preferred_device_name) ?: return
        val switchBtn = currentView.findViewById<TextView>(R.id.btn_switch_device) ?: return
        val statusView = currentView.findViewById<TextView>(R.id.connection_status) ?: return
        val wifiButton = currentView.findViewById<MaterialButton>(R.id.wifi_button)

        container.visibility = View.VISIBLE

        viewLifecycleOwner.lifecycleScope.launch {
            val netStatus = HotspotConnectionHelper.getNetworkStatus(requireContext())
            if (!isAdded) return@launch
            if (netStatus.isWifiConnected && (app.settings.useHotspotAsDefaultWireless || netStatus.isPhoneHotspot || netStatus.gatewayIp != null)) {
                val targetIp = netStatus.gatewayIp ?: app.settings.customHotspotPhoneIp.ifEmpty { "192.168.43.1" }
                nameView.text = "${netStatus.ssid ?: "Hotspot"} • $targetIp:5277"
                switchBtn.text = getString(R.string.da_switch_phone)
                switchBtn.setOnClickListener {
                    cancelAutoConnectCountdown()
                    HotspotConnectionDialog.show(childFragmentManager)
                }
                if (!app.commManager.isConnected) {
                    statusView.setText(R.string.da_headunit_server_ready)
                    wifiButton?.setText(R.string.da_connect_wireless)
                }
                return@launch
            }

            val preferred = getPreferredDevice()
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
    }

    private fun updateQuickGlanceUi() {
        val currentView = view ?: return
        val ctx = context ?: return

        // 1. Wi-Fi status & connected network name (SSID)
        viewLifecycleOwner.lifecycleScope.launch {
            val netStatus = HotspotConnectionHelper.getNetworkStatus(ctx)
            if (!isAdded) return@launch

            val wifiStatusText = currentView.findViewById<TextView?>(R.id.glance_wifi_status) ?: return@launch
            val wifiIcon = currentView.findViewById<ImageView?>(R.id.glance_wifi_icon) ?: return@launch
            val wifiDot = currentView.findViewById<ImageView?>(R.id.glance_wifi_dot) ?: return@launch

            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val isWifiEnabled = try { wm?.isWifiEnabled == true } catch (_: Exception) { true }

            when {
                !isWifiEnabled -> {
                    wifiStatusText.setText(R.string.glance_wifi_off)
                    wifiStatusText.setTextColor(ContextCompat.getColor(ctx, R.color.da_muted))
                    wifiIcon.imageTintList = android.content.res.ColorStateList.valueOf(
                        ContextCompat.getColor(ctx, R.color.da_muted)
                    )
                    wifiDot.setImageResource(R.drawable.shape_dot_grey)
                }
                netStatus.isWifiConnected -> {
                    val displayName = netStatus.ssid?.takeIf { it.isNotBlank() }
                        ?: if (netStatus.isPhoneHotspot) getString(R.string.phone_hotspot)
                        else getString(R.string.wifi_network_connected)
                    wifiStatusText.text = displayName
                    wifiStatusText.setTextColor(ContextCompat.getColor(ctx, R.color.da_text))
                    wifiIcon.imageTintList = android.content.res.ColorStateList.valueOf(
                        ContextCompat.getColor(ctx, R.color.da_accent)
                    )
                    wifiDot.setImageResource(R.drawable.shape_dot_green)
                }
                else -> {
                    wifiStatusText.setText(R.string.glance_wifi_disconnected)
                    wifiStatusText.setTextColor(ContextCompat.getColor(ctx, R.color.da_muted))
                    wifiIcon.imageTintList = android.content.res.ColorStateList.valueOf(
                        ContextCompat.getColor(ctx, R.color.da_muted)
                    )
                    wifiDot.setImageResource(R.drawable.shape_dot_grey)
                }
            }
        }

        // 2. Bluetooth connected device name
        val btStatusText = currentView.findViewById<TextView?>(R.id.glance_bluetooth_status) ?: return
        val btIcon = currentView.findViewById<ImageView?>(R.id.glance_bluetooth_icon) ?: return
        val btDot = currentView.findViewById<ImageView?>(R.id.glance_bluetooth_dot) ?: return

        val adapter = BluetoothHelper.getBluetoothAdapter(ctx)
        val isBtEnabled = try { adapter?.isEnabled == true } catch (_: Exception) { false }

        when {
            !isBtEnabled -> {
                btStatusText.setText(R.string.glance_bt_off)
                btStatusText.setTextColor(ContextCompat.getColor(ctx, R.color.da_muted))
                btIcon.imageTintList = android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(ctx, R.color.da_muted)
                )
                btDot.setImageResource(R.drawable.shape_dot_grey)
            }
            else -> {
                val connectedDevice = BluetoothHelper.getConnectedBluetoothDevice(ctx)
                if (connectedDevice != null) {
                    val name = try {
                        connectedDevice.name?.takeIf { it.isNotBlank() } ?: connectedDevice.address
                    } catch (_: SecurityException) {
                        connectedDevice.address
                    }
                    btStatusText.text = name
                    btStatusText.setTextColor(ContextCompat.getColor(ctx, R.color.da_text))
                    btIcon.imageTintList = android.content.res.ColorStateList.valueOf(
                        ContextCompat.getColor(ctx, R.color.da_accent)
                    )
                    btDot.setImageResource(R.drawable.shape_dot_green)
                } else {
                    val preferred = getPreferredDevice()
                    if (preferred != null) {
                        val name = try {
                            preferred.name?.takeIf { it.isNotBlank() } ?: preferred.address
                        } catch (_: SecurityException) {
                            preferred.address
                        }
                        btStatusText.text = "$name (${getString(R.string.disconnected)})"
                    } else {
                        btStatusText.setText(R.string.glance_bt_disconnected)
                    }
                    btStatusText.setTextColor(ContextCompat.getColor(ctx, R.color.da_muted))
                    btIcon.imageTintList = android.content.res.ColorStateList.valueOf(
                        ContextCompat.getColor(ctx, R.color.da_muted)
                    )
                    btDot.setImageResource(R.drawable.shape_dot_grey)
                }
            }
        }

        // 3. USB connected device glance
        updateUsbGlanceUi(currentView, ctx, detectedUsbDevice)
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

        // Refresh USB card state in sync with connection state
        updateUsbDeviceUi(mainViewModel.usbDevices.value)

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
                    wifiButton.setText(R.string.da_connect_wireless)
                } else {
                    val preferred = getPreferredDevice()
                    val targetName = preferred?.name?.takeIf { it.isNotBlank() }
                    if (targetName != null) {
                        wifiButton.text = getString(R.string.da_connect_to, targetName)
                    } else {
                        wifiButton.setText(R.string.da_connect_wireless)
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
