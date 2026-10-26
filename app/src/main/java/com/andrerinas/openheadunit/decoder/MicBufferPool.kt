package com.andrerinas.openheadunit.decoder

/**
 * Pre-allocated ring buffer pool for steady-state microphone PCM packet transmission.
 * Eliminates garbage-collection pauses and memory churn on low-RAM head units during voice sessions.
 */
class MicBufferPool(val poolSize: Int = 16) {
    private val buffers = Array(poolSize) { ByteArray(0) }
    private var index = 0

    @Synchronized
    fun obtain(neededLength: Int): ByteArray {
        val slot = index
        index = (index + 1) % poolSize
        var buf = buffers[slot]
        if (buf.size < neededLength) {
            buf = ByteArray(neededLength)
            buffers[slot] = buf
        }
        return buf
    }

    @Synchronized
    fun currentSlot(): Int = index
}
