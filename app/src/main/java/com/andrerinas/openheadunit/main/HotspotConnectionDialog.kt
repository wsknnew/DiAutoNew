package com.andrerinas.openheadunit.main

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.provider.Settings as SystemSettings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.aap.AapService
import com.andrerinas.openheadunit.utils.HotspotConnectionHelper
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HotspotConnectionDialog : DialogFragment() {

    private var currentStatus: HotspotConnectionHelper.NetworkStatus? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_TITLE, R.style.DarkAlertDialog)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.dialog_hotspot_connection, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<View>(R.id.btn_close).setOnClickListener {
            dismiss()
        }

        view.findViewById<View?>(R.id.btn_refresh_status)?.setOnClickListener {
            refreshStatus()
        }

        view.findViewById<View>(R.id.btn_open_wifi).setOnClickListener {
            openWifiSettings()
        }

        view.findViewById<View>(R.id.btn_connect_hotspot).setOnClickListener {
            val status = currentStatus
            val targetIp = status?.gatewayIp ?: "192.168.43.1"
            connectToPhone(targetIp)
        }

        view.findViewById<View>(R.id.btn_scan_network).setOnClickListener {
            val app = App.provide(requireContext())
            app.settings.wifiConnectionMode = 1
            ContextCompat.startForegroundService(requireContext(), Intent(requireContext(), AapService::class.java).apply {
                action = AapService.ACTION_START_WIRELESS_SCAN
            })
            Toast.makeText(context, R.string.scanning_network_for_phone, Toast.LENGTH_SHORT).show()
            (activity as? MainActivity)?.beginAutoConnect("hotspot scan", MainActivity.ConnectionUiMode.PILL)
            dismiss()
        }

        view.findViewById<View>(R.id.btn_edit_ip).setOnClickListener {
            showEditIpDialog()
        }

        val app = App.provide(requireContext())
        val autoConnectSwitch = view.findViewById<SwitchCompat>(R.id.switch_auto_connect_hotspot)
        autoConnectSwitch.isChecked = app.settings.autoConnectOnHotspot
        autoConnectSwitch.setOnCheckedChangeListener { _, isChecked ->
            app.settings.autoConnectOnHotspot = isChecked
        }

        val defaultHotspotSwitch = view.findViewById<SwitchCompat>(R.id.switch_default_hotspot)
        defaultHotspotSwitch.isChecked = app.settings.useHotspotAsDefaultWireless
        defaultHotspotSwitch.setOnCheckedChangeListener { _, isChecked ->
            app.settings.useHotspotAsDefaultWireless = isChecked
        }

        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        registerNetworkCallback()
    }

    override fun onPause() {
        unregisterNetworkCallback()
        super.onPause()
    }

    private fun registerNetworkCallback() {
        val cm = context?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        if (networkCallback == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    view?.post { refreshStatus() }
                }
                override fun onLost(network: Network) {
                    view?.post { refreshStatus() }
                }
                override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                    view?.post { refreshStatus() }
                }
            }
            try {
                cm.registerDefaultNetworkCallback(callback)
                networkCallback = callback
            } catch (_: Exception) {}
        }
    }

    private fun unregisterNetworkCallback() {
        val cm = context?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        networkCallback?.let {
            try { cm.unregisterNetworkCallback(it) } catch (_: Exception) {}
            networkCallback = null
        }
    }

    private fun refreshStatus() {
        val view = view ?: return
        lifecycleScope.launch {
            val status = HotspotConnectionHelper.getNetworkStatus(requireContext())
            currentStatus = status
            updateUi(view, status)
        }
    }

    private fun updateUi(view: View, status: HotspotConnectionHelper.NetworkStatus) {
        val networkBadge = view.findViewById<TextView>(R.id.network_badge)
        val ssidText = view.findViewById<TextView>(R.id.network_ssid_text)
        val phoneIpText = view.findViewById<TextView>(R.id.phone_ip_text)
        val headUnitIpText = view.findViewById<TextView>(R.id.head_unit_ip_text)
        val connectBtn = view.findViewById<MaterialButton>(R.id.btn_connect_hotspot)
        val serverStatusContainer = view.findViewById<View>(R.id.server_status_container)
        val serverStatusText = view.findViewById<TextView>(R.id.server_status_text)

        val app = App.provide(requireContext())
        if (status.isWifiConnected) {
            val displayName = status.ssid?.takeIf { it.isNotBlank() }
                ?: if (status.isPhoneHotspot) getString(R.string.phone_hotspot) else getString(R.string.local_network)
            ssidText.text = displayName

            if (status.isPhoneHotspot) {
                networkBadge.text = getString(R.string.phone_hotspot_connected)
                networkBadge.setTextColor(ContextCompat.getColor(requireContext(), R.color.da_accent))
            } else {
                networkBadge.text = getString(R.string.wifi_network_connected)
                networkBadge.setTextColor(ContextCompat.getColor(requireContext(), R.color.da_accent))
            }

            headUnitIpText.text = getString(R.string.head_unit_ip_format, status.localIp ?: "—")

            if (status.gatewayIp != null) {
                phoneIpText.text = getString(R.string.gateway_ip_format, status.gatewayIp)
                connectBtn.isEnabled = true
                connectBtn.text = getString(R.string.connect_to_phone_hotspot_ip, status.gatewayIp)
            } else {
                phoneIpText.text = getString(R.string.gateway_ip_none)
                connectBtn.isEnabled = true
                connectBtn.text = getString(R.string.connect_to_phone_hotspot)
            }

            serverStatusContainer.visibility = View.VISIBLE
            if (app.commManager.isConnected) {
                serverStatusText.text = getString(R.string.da_connected)
                serverStatusText.setTextColor(ContextCompat.getColor(requireContext(), R.color.da_accent))
            } else if (app.commManager.isBusy) {
                serverStatusText.text = getString(R.string.da_connecting)
                serverStatusText.setTextColor(ContextCompat.getColor(requireContext(), R.color.da_accent))
            } else {
                serverStatusText.text = getString(R.string.aa_server_ready)
                serverStatusText.setTextColor(ContextCompat.getColor(requireContext(), R.color.da_accent))
            }
        } else {
            networkBadge.text = getString(R.string.no_wifi_connection)
            networkBadge.setTextColor(ContextCompat.getColor(requireContext(), R.color.da_muted))
            ssidText.text = getString(R.string.no_wifi_connection)
            phoneIpText.text = getString(R.string.gateway_ip_none)
            headUnitIpText.text = getString(R.string.head_unit_ip_not_connected)
            serverStatusContainer.visibility = View.GONE
            connectBtn.isEnabled = false
            connectBtn.text = getString(R.string.no_wifi_connection)
        }
    }

    private fun connectToPhone(targetIp: String) {
        val app = App.provide(requireContext())
        app.settings.wifiConnectionMode = 1 // Headunit Server mode

        (activity as? MainActivity)?.beginAutoConnect(
            "manual hotspot AA $targetIp:5277",
            MainActivity.ConnectionUiMode.PILL
        )

        val ctx = requireContext().applicationContext
        try {
            ContextCompat.startForegroundService(ctx, Intent(ctx, AapService::class.java).apply {
                action = AapService.ACTION_CONNECT_SOCKET
            })
        } catch (_: Exception) {}

        // Launch via CommManager's independent scope so dismissing this Dialog does not cancel the socket connection
        App.provide(ctx).commManager.connectAsync(targetIp, HotspotConnectionHelper.AAP_TCP_PORT)

        dismiss()
    }

    private fun showEditIpDialog() {
        val app = App.provide(requireContext())
        val input = EditText(requireContext()).apply {
            setText(app.settings.customHotspotPhoneIp.ifEmpty { currentStatus?.gatewayIp ?: "" })
            setTextColor(ContextCompat.getColor(requireContext(), R.color.da_text))
            setHintTextColor(ContextCompat.getColor(requireContext(), R.color.da_muted))
            hint = "192.168.43.1"
            setPadding(40, 30, 40, 30)
        }

        MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
            .setTitle(R.string.edit_phone_ip)
            .setView(input)
            .setPositiveButton(R.string.da_open) { _, _ ->
                val newIp = input.text.toString().trim()
                app.settings.customHotspotPhoneIp = newIp
                refreshStatus()
            }
            .setNeutralButton(R.string.setup_not_set) { _, _ ->
                app.settings.customHotspotPhoneIp = ""
                refreshStatus()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun openWifiSettings() {
        try {
            startActivity(Intent(SystemSettings.ACTION_WIFI_SETTINGS))
        } catch (_: Exception) {
            startActivity(Intent(SystemSettings.ACTION_SETTINGS))
        }
    }

    companion object {
        const val TAG = "HotspotConnectionDialog"

        fun show(fragmentManager: FragmentManager) {
            val dialog = HotspotConnectionDialog()
            dialog.show(fragmentManager, TAG)
        }
    }
}
