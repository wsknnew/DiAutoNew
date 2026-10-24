package com.andrerinas.openheadunit.main

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.BluetoothHelper
import com.google.android.material.button.MaterialButton

class BluetoothDeviceSelectionDialog : DialogFragment() {

    var onDeviceSelectedListener: ((BluetoothDevice) -> Unit)? = null

    private var bluetoothAdapter: BluetoothAdapter? = null
    private val discoveredDevices = mutableListOf<BluetoothDevice>()
    private var isReceiverRegistered = false
    private var pairingDevice: BluetoothDevice? = null

    private val bluetoothReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND, BluetoothDevice.ACTION_NAME_CHANGED -> {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }

                    if (device != null) {
                        val isAlreadyPaired = bluetoothAdapter?.bondedDevices?.any { it.address == device.address } == true
                        val broadcastName = intent.getStringExtra(BluetoothDevice.EXTRA_NAME)
                        val name = broadcastName?.takeIf { it.isNotBlank() } ?: try { device.name } catch (_: SecurityException) { null }

                        if (!isAlreadyPaired && !name.isNullOrBlank()) {
                            val existingIndex = discoveredDevices.indexOfFirst { it.address == device.address }
                            if (existingIndex >= 0) {
                                discoveredDevices[existingIndex] = device
                            } else {
                                discoveredDevices.add(device)
                            }
                            renderUnpairedDevices()
                        }
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_STARTED -> {
                    updateScanUi(isScanning = true)
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    updateScanUi(isScanning = false)
                }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }

                    when (state) {
                        BluetoothDevice.BOND_BONDING -> {
                            val name = device?.name?.takeIf { it.isNotBlank() } ?: getString(R.string.da_device)
                            showPairingBanner(getString(R.string.pairing_with_phone, name))
                        }
                        BluetoothDevice.BOND_BONDED -> {
                            hidePairingBanner()
                            if (device != null) {
                                Toast.makeText(context, getString(R.string.paired_success, device.name ?: device.address), Toast.LENGTH_SHORT).show()
                                onDeviceSelectedListener?.invoke(device)
                                dismiss()
                            }
                        }
                        BluetoothDevice.BOND_NONE -> {
                            hidePairingBanner()
                            renderPairedDevices()
                        }
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_TITLE, R.style.DarkAlertDialog)
        bluetoothAdapter = BluetoothHelper.getBluetoothAdapter(requireContext())
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.dialog_bluetooth_device_selection, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<View>(R.id.btn_close_picker).setOnClickListener {
            dismiss()
        }

        view.findViewById<View>(R.id.btn_open_bt_settings).setOnClickListener {
            openBluetoothSettings()
        }

        view.findViewById<View>(R.id.btn_refresh_scan).setOnClickListener {
            startDiscovery()
        }

        registerBluetoothReceiver()
        renderPairedDevices()
        startDiscovery()
    }

    private fun registerBluetoothReceiver() {
        if (!isReceiverRegistered) {
            val ctx = context ?: return
            val filter = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothDevice.ACTION_NAME_CHANGED)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
                addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            }
            // Android 14+ requires RECEIVER_EXPORTED for system Bluetooth broadcasts
            ContextCompat.registerReceiver(ctx, bluetoothReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
            isReceiverRegistered = true
        }
    }

    private fun unregisterBluetoothReceiver() {
        if (isReceiverRegistered) {
            try {
                context?.unregisterReceiver(bluetoothReceiver)
            } catch (_: Exception) {}
            isReceiverRegistered = false
        }
    }

    @SuppressLint("MissingPermission")
    private fun renderPairedDevices() {
        val view = view ?: return
        val container = view.findViewById<LinearLayout>(R.id.paired_devices_container) ?: return
        val emptyText = view.findViewById<TextView>(R.id.no_paired_text) ?: return
        container.removeAllViews()

        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            emptyText.visibility = View.VISIBLE
            emptyText.text = getString(R.string.da_bluetooth_off)
            return
        }

        val preferredMacs = App.provide(requireContext()).settings.autoStartBluetoothDeviceMacs
        val connectedDevice = BluetoothHelper.getConnectedBluetoothDevice(requireContext())

        val pairedList = try {
            adapter.bondedDevices.orEmpty().sortedWith(
                compareByDescending<BluetoothDevice> { it.address == connectedDevice?.address }
                    .thenByDescending { it.address in preferredMacs }
                    .thenBy { it.name ?: it.address }
            )
        } catch (_: SecurityException) {
            emptyList()
        }

        if (pairedList.isEmpty()) {
            emptyText.visibility = View.VISIBLE
        } else {
            emptyText.visibility = View.GONE

            for (device in pairedList) {
                val itemView = LayoutInflater.from(context).inflate(R.layout.item_bluetooth_device, container, false)
                val nameView = itemView.findViewById<TextView>(R.id.device_name)
                val statusView = itemView.findViewById<TextView>(R.id.device_status)
                val badgeView = itemView.findViewById<TextView>(R.id.device_action_badge)
                val iconView = itemView.findViewById<ImageView>(R.id.device_icon)

                val name = device.name?.takeIf { it.isNotBlank() } ?: device.address
                nameView.text = name

                val isConnected = device.address == connectedDevice?.address || BluetoothHelper.isDeviceConnected(device)
                val isPreferred = device.address in preferredMacs

                when {
                    isConnected -> {
                        statusView.text = "${device.address} • Connected to Car Bluetooth"
                        badgeView.text = getString(R.string.connected)
                        badgeView.setBackgroundResource(R.drawable.da_card)
                        badgeView.setTextColor(ContextCompat.getColor(requireContext(), R.color.material_green_700))
                    }
                    isPreferred -> {
                        statusView.text = "${device.address} • ${getString(R.string.preferred_device_label)}"
                        badgeView.text = getString(R.string.active_badge)
                        badgeView.setBackgroundResource(R.drawable.da_card)
                        badgeView.setTextColor(ContextCompat.getColor(requireContext(), R.color.da_accent))
                    }
                    else -> {
                        statusView.text = "${device.address} • ${getString(R.string.paired_ready)}"
                        badgeView.text = getString(R.string.da_connect)
                    }
                }

                iconView.setImageResource(R.drawable.ic_phone)
                itemView.setOnClickListener {
                    onDeviceSelectedListener?.invoke(device)
                    dismiss()
                }
                container.addView(itemView)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun renderUnpairedDevices() {
        val view = view ?: return
        val container = view.findViewById<LinearLayout>(R.id.unpaired_devices_container) ?: return
        val emptyText = view.findViewById<TextView>(R.id.no_unpaired_text) ?: return
        container.removeAllViews()

        if (discoveredDevices.isEmpty()) {
            emptyText.visibility = if (bluetoothAdapter?.isDiscovering == true) View.GONE else View.VISIBLE
        } else {
            emptyText.visibility = View.GONE
            for (device in discoveredDevices) {
                val itemView = LayoutInflater.from(context).inflate(R.layout.item_bluetooth_device, container, false)
                val nameView = itemView.findViewById<TextView>(R.id.device_name)
                val statusView = itemView.findViewById<TextView>(R.id.device_status)
                val badgeView = itemView.findViewById<TextView>(R.id.device_action_badge)
                val iconView = itemView.findViewById<ImageView>(R.id.device_icon)

                val name = device.name?.takeIf { it.isNotBlank() } ?: device.address
                nameView.text = name
                statusView.text = "${device.address} • ${getString(R.string.unpaired_phone_status)}"
                badgeView.text = getString(R.string.pair_and_connect)

                iconView.setImageResource(R.drawable.ic_phone)
                itemView.setOnClickListener {
                    pairAndConnectDevice(device)
                }
                container.addView(itemView)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun pairAndConnectDevice(device: BluetoothDevice) {
        pairingDevice = device
        bluetoothAdapter?.cancelDiscovery()

        val name = device.name?.takeIf { it.isNotBlank() } ?: getString(R.string.da_device)
        showPairingBanner(getString(R.string.pairing_with_phone, name))

        try {
            val initiated = device.createBond()
            if (!initiated) {
                hidePairingBanner()
                Toast.makeText(context, R.string.pairing_failed_toast, Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            AppLog.e("BluetoothSelection: Error creating bond", e)
            hidePairingBanner()
            Toast.makeText(context, R.string.pairing_failed_toast, Toast.LENGTH_SHORT).show()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startDiscovery() {
        val adapter = bluetoothAdapter ?: return
        if (!adapter.isEnabled) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                return
            }
        }

        discoveredDevices.clear()
        renderUnpairedDevices()
        if (adapter.isDiscovering) {
            adapter.cancelDiscovery()
        }
        adapter.startDiscovery()
    }

    private fun updateScanUi(isScanning: Boolean) {
        val view = view ?: return
        val spinner = view.findViewById<ProgressBar>(R.id.scan_progress_spinner)
        val statusText = view.findViewById<TextView>(R.id.scan_status_text)
        val emptyText = view.findViewById<TextView>(R.id.no_unpaired_text)

        spinner?.visibility = if (isScanning) View.VISIBLE else View.GONE
        statusText?.text = if (isScanning) getString(R.string.scanning_nearby) else getString(R.string.scan_finished)

        if (!isScanning && discoveredDevices.isEmpty()) {
            emptyText?.visibility = View.VISIBLE
        }
    }

    private fun showPairingBanner(message: String) {
        val view = view ?: return
        val banner = view.findViewById<LinearLayout>(R.id.pairing_banner)
        val text = view.findViewById<TextView>(R.id.pairing_status_text)
        banner?.visibility = View.VISIBLE
        text?.text = message
    }

    private fun hidePairingBanner() {
        val view = view ?: return
        view.findViewById<LinearLayout>(R.id.pairing_banner)?.visibility = View.GONE
    }

    private fun openBluetoothSettings() {
        try {
            startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS))
        } catch (_: Exception) {
            startActivity(Intent(android.provider.Settings.ACTION_SETTINGS))
        }
    }

    @SuppressLint("MissingPermission")
    override fun onDestroyView() {
        bluetoothAdapter?.cancelDiscovery()
        unregisterBluetoothReceiver()
        super.onDestroyView()
    }

    companion object {
        const val TAG = "BluetoothDeviceSelectionDialog"

        fun show(
            fragmentManager: androidx.fragment.app.FragmentManager,
            onSelected: (BluetoothDevice) -> Unit
        ) {
            val dialog = BluetoothDeviceSelectionDialog()
            dialog.onDeviceSelectedListener = onSelected
            dialog.show(fragmentManager, TAG)
        }
    }
}
