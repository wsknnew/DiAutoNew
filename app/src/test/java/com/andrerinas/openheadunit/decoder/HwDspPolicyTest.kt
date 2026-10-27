package com.andrerinas.openheadunit.decoder

import org.junit.Assert.assertFalse
import org.junit.Test

class HwDspPolicyTest {

    @Test
    fun testDefaultAttachHwDsp() {
        // Hardware DSP Equalizer Trigger defaults to false to prevent audio pops and spikes on initial playback
        assertFalse(HwDspPolicy.defaultAttachHwDsp(isByd = true))
        assertFalse(HwDspPolicy.defaultAttachHwDsp(isByd = false))
    }
}
