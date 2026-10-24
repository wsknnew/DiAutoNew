package com.andrerinas.openheadunit.main

import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.R
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.switchmaterial.SwitchMaterial

class DriveSettingsFragment : Fragment(R.layout.fragment_drive_settings) {

    private val settings by lazy { App.provide(requireContext()).settings }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view.findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener {
            requireActivity().finish()
        }

        view.findViewById<View>(R.id.da_hotspot_connection)?.setOnClickListener {
            HotspotConnectionDialog.show(childFragmentManager)
        }

        // Direct navigation mapping
        mapOf(
            R.id.da_connection_setup to R.id.connectionSetupFragment,
            R.id.da_automation to R.id.autoStartFragment,
            R.id.da_theme to R.id.darkModeFragment,
            R.id.da_dpi to R.id.dpiSettingsFragment,
            R.id.da_display to R.id.settingsFragment,
            R.id.da_permissions to R.id.permissionsFragment,
            R.id.da_diagnostics to R.id.diagnosticsFragment,
            R.id.da_about to R.id.aboutFragment
        ).forEach { (buttonId, destinationId) ->
            view.findViewById<View?>(buttonId)?.setOnClickListener {
                findNavController().navigate(destinationId)
            }
        }

        // Quick Audio Routing Toggle (Music via Car Bluetooth vs AA Audio)
        setupAudioRouting(view)
    }

    override fun onResume() {
        super.onResume()
        view?.let { setupAudioRouting(it) }
    }

    private fun setupAudioRouting(view: View) {
        val audioCard = view.findViewById<View?>(R.id.da_audio_card) ?: return
        val audioSwitch = view.findViewById<SwitchMaterial?>(R.id.switch_bluetooth_music) ?: return
        val audioDesc = view.findViewById<TextView?>(R.id.audio_routing_desc)

        val isBtMusic = settings.musicViaBluetooth
        audioSwitch.isChecked = isBtMusic
        audioDesc?.text = getString(
            if (isBtMusic) R.string.da_audio_routing_enabled
            else R.string.da_audio_routing_disabled
        )

        val toggleAction = {
            val newState = !audioSwitch.isChecked
            audioSwitch.isChecked = newState
            settings.musicViaBluetooth = newState
            audioDesc?.text = getString(
                if (newState) R.string.da_audio_routing_enabled
                else R.string.da_audio_routing_disabled
            )
            Toast.makeText(
                requireContext(),
                if (newState) R.string.da_audio_routing_enabled else R.string.da_audio_routing_disabled,
                Toast.LENGTH_SHORT
            ).show()
        }

        audioCard.setOnClickListener { toggleAction() }
    }
}
