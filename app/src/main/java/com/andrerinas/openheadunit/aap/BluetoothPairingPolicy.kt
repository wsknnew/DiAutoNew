package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.aap.protocol.proto.Control

/**
 * Decides which Bluetooth pairing methods to advertise in ServiceDiscoveryResponse.
 *
 * When musicViaBluetooth is false (default), Android Auto media audio is streamed via
 * protocol Channel 3 (ID_AUD). In this mode, advertising A2DP causes the phone to establish
 * a concurrent Bluetooth A2DP audio link alongside Android Auto, confusing streaming apps
 * (e.g. YouTube Music, Amazon Music) into detecting simultaneous streams and erroring out
 * with "Too many devices streaming on your plan right now".
 *
 * When musicViaBluetooth is true, media is intentionally routed over the car's Bluetooth
 * A2DP stack, so A2DP is advertised alongside HFP.
 */
object BluetoothPairingPolicy {
    fun getSupportedPairingMethods(musicViaBluetooth: Boolean): List<Control.BluetoothPairingMethod> {
        return if (musicViaBluetooth) {
            listOf(Control.BluetoothPairingMethod.A2DP, Control.BluetoothPairingMethod.HFP)
        } else {
            listOf(Control.BluetoothPairingMethod.HFP)
        }
    }
}
