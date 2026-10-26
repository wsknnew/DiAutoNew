package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.aap.protocol.proto.Control
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BluetoothPairingPolicyTest {

    @Test
    fun `default non-bluetooth music mode only advertises HFP to prevent dual-streaming conflict`() {
        val methods = BluetoothPairingPolicy.getSupportedPairingMethods(musicViaBluetooth = false)
        assertEquals(1, methods.size)
        assertTrue(methods.contains(Control.BluetoothPairingMethod.HFP))
        assertFalse(methods.contains(Control.BluetoothPairingMethod.A2DP))
    }

    @Test
    fun `musicViaBluetooth mode advertises both A2DP and HFP`() {
        val methods = BluetoothPairingPolicy.getSupportedPairingMethods(musicViaBluetooth = true)
        assertEquals(2, methods.size)
        assertTrue(methods.contains(Control.BluetoothPairingMethod.A2DP))
        assertTrue(methods.contains(Control.BluetoothPairingMethod.HFP))
    }
}
