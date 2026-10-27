package com.andrerinas.openheadunit.decoder

/**
 * Determines whether hardware DSP equalizer attachment should be enabled by default.
 * Defaults to false for all head units to prevent audio pops, relay clicks, and initial volume spikes.
 */
object HwDspPolicy {
    fun defaultAttachHwDsp(isByd: Boolean): Boolean {
        return false
    }
}
