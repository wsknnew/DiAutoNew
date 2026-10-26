package com.andrerinas.openheadunit.decoder

import com.andrerinas.openheadunit.utils.AppLog
import java.util.concurrent.ConcurrentHashMap

class AudioDecoder {

    private val audioTracks = ConcurrentHashMap<Int, AudioTrackWrapper>()
    private var mixer: AudioMixer? = null

    fun getTrack(channel: Int): AudioTrackWrapper? {
        return audioTracks[channel]
    }

    fun getMixer(): AudioMixer? {
        return mixer
    }

    fun hasMixer(): Boolean {
        return mixer != null && mixer!!.isRunning()
    }

    fun decode(channel: Int, buffer: ByteArray, offset: Int, size: Int) {
        audioTracks[channel]?.write(buffer, offset, size)
    }

    fun stop() {
        val keys = audioTracks.keys().toList()
        for (key in keys) {
            stop(key)
        }
        releaseMixer()
    }

    fun stop(chan: Int) {
        val audioTrack = audioTracks.remove(chan)
        audioTrack?.stopPlayback()
    }

    fun releaseMixer() {
        synchronized(this) {
            mixer?.stop()
            mixer = null
        }
    }

    fun start(channel: Int, stream: Int, sampleRate: Int, numberOfBits: Int, numberOfChannels: Int, isAac: Boolean = false, gain: Float = 1.0f, audioLatencyMultiplier: Int = 8, audioQueueCapacity: Int = 0, staticAudioFocus: Boolean = false, attachHwDspEqualizer: Boolean = false) {
        stop(channel)
        if (staticAudioFocus) {
            synchronized(this) {
                if (mixer == null) {
                    mixer = AudioMixer(stream, attachHwDspEqualizer)
                    mixer!!.start()
                    AppLog.i("AudioDecoder: Created and started shared AudioMixer")
                }
            }
        }
        val thread = AudioTrackWrapper(
            stream = stream,
            sampleRateInHz = sampleRate,
            bitDepth = numberOfBits,
            channelCount = numberOfChannels,
            isAac = isAac,
            gain = gain,
            audioLatencyMultiplier = audioLatencyMultiplier,
            audioQueueCapacity = audioQueueCapacity,
            mixer = if (staticAudioFocus) mixer else null,
            channelId = channel,
            attachHwDspEqualizer = attachHwDspEqualizer
        )
        audioTracks[channel] = thread
    }

    fun setGain(channel: Int, gain: Float) {
        audioTracks[channel]?.setGain(gain)
    }

    companion object {
        const val SAMPLE_RATE_HZ_48 = 48000
        const val SAMPLE_RATE_HZ_16 = 16000
    }
}
