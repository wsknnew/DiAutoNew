package com.andrerinas.openheadunit.decoder

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HwDspPolicyTest {

    @Test
    fun testDefaultAttachHwDsp() {
        // BYD units manage audio routing through BYD vehicle service; should default to false
        assertFalse(HwDspPolicy.defaultAttachHwDsp(isByd = true))

        // Non-BYD aftermarket head units (TS10, FYT7862, etc.) need Equalizer session to drive DSP chip; should default to true
        assertTrue(HwDspPolicy.defaultAttachHwDsp(isByd = false))
    }
}
