package com.andrerinas.openheadunit.decoder

import com.andrerinas.openheadunit.aap.protocol.Channel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioSoftFadeInPolicyTest {

    @Test
    fun `media channel uses 300ms fade-in`() {
        assertEquals(300L, AudioSoftFadeInPolicy.getFadeInDurationMs(Channel.ID_AUD))
    }

    @Test
    fun `voice channels use 60ms fade-in`() {
        assertEquals(60L, AudioSoftFadeInPolicy.getFadeInDurationMs(Channel.ID_AU1))
        assertEquals(60L, AudioSoftFadeInPolicy.getFadeInDurationMs(Channel.ID_AU2))
    }

    @Test
    fun `calculates correct ramp frames for 48kHz stereo media`() {
        val totalFrames = AudioSoftFadeInPolicy.calculateTotalRampFrames(48000, Channel.ID_AUD)
        // 48000 * 300 / 1000 = 14400 frames
        assertEquals(14400L, totalFrames)
    }

    @Test
    fun `calculates correct ramp frames for 16kHz speech`() {
        val totalFrames = AudioSoftFadeInPolicy.calculateTotalRampFrames(16000, Channel.ID_AU1)
        // 16000 * 60 / 1000 = 960 frames
        assertEquals(960L, totalFrames)
    }

    @Test
    fun `multiplier starts at zero and ramps smoothly to one`() {
        val total = 1000L
        assertEquals(0.0f, AudioSoftFadeInPolicy.calculateMultiplier(0L, total), 0.0001f)
        assertEquals(0.5f, AudioSoftFadeInPolicy.calculateMultiplier(500L, total), 0.0001f)
        assertEquals(1.0f, AudioSoftFadeInPolicy.calculateMultiplier(1000L, total), 0.0001f)
        assertEquals(1.0f, AudioSoftFadeInPolicy.calculateMultiplier(1500L, total), 0.0001f)
    }

    @Test
    fun `multiplier handles zero or negative total frames safely`() {
        assertEquals(1.0f, AudioSoftFadeInPolicy.calculateMultiplier(0L, 0L), 0.0001f)
        assertEquals(1.0f, AudioSoftFadeInPolicy.calculateMultiplier(10L, -5L), 0.0001f)
    }
}
