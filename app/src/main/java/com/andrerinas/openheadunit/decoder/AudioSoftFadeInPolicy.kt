package com.andrerinas.openheadunit.decoder

import com.andrerinas.openheadunit.aap.protocol.Channel

/**
 * Policy governing soft fade-in ramp durations and multipliers for audio channels.
 * Prevents initial volume spikes, DAC step pops, and speaker thumps when media begins playback.
 */
object AudioSoftFadeInPolicy {
    const val MEDIA_FADE_IN_MS = 300L
    const val VOICE_FADE_IN_MS = 60L

    fun getFadeInDurationMs(channelId: Int): Long {
        return when (channelId) {
            Channel.ID_AUD -> MEDIA_FADE_IN_MS
            else -> VOICE_FADE_IN_MS
        }
    }

    fun calculateTotalRampFrames(sampleRateInHz: Int, channelId: Int): Long {
        val durationMs = getFadeInDurationMs(channelId)
        val rate = maxOf(sampleRateInHz, 8000)
        return (rate * durationMs / 1000L).coerceAtLeast(1L)
    }

    fun calculateMultiplier(processedFrames: Long, totalFrames: Long): Float {
        if (totalFrames <= 0L) return 1.0f
        return (processedFrames.toFloat() / totalFrames).coerceIn(0.0f, 1.0f)
    }
}
