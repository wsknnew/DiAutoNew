package com.andrerinas.openheadunit.decoder

/**
 * Determines whether hardware DSP equalizer attachment should be enabled by default.
 * Aftermarket Android units benefit from dummy Equalizer attachment to activate internal sound processors (AKM, Rohm, etc.),
 * while BYD vehicles should keep it off by default to avoid conflicts with native vehicle sound routing.
 */
object HwDspPolicy {
    fun defaultAttachHwDsp(isByd: Boolean): Boolean {
        return !isByd
    }
}
