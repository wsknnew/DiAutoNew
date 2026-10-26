package com.andrerinas.openheadunit.decoder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MicBufferPoolTest {

    @Test
    fun testPoolReusesBuffersInRing() {
        val pool = MicBufferPool(poolSize = 4)
        val first = pool.obtain(128)
        assertNotNull(first)
        assertTrue(first.size >= 128)

        val second = pool.obtain(128)
        val third = pool.obtain(128)
        val fourth = pool.obtain(128)
        assertNotNull(second)
        assertNotNull(third)
        assertNotNull(fourth)

        // The 5th obtain wraps around to slot 0 and should reuse the exact same ByteArray instance
        val fifth = pool.obtain(128)
        assertSame(first, fifth)
    }

    @Test
    fun testPoolDynamicallyExpandsBufferWhenNeeded() {
        val pool = MicBufferPool(poolSize = 2)
        val small = pool.obtain(64)
        assertEquals(64, small.size)

        // Next slot
        pool.obtain(64)

        // Slot 0 again, but requesting larger size
        val larger = pool.obtain(256)
        assertTrue(larger.size >= 256)
    }

    @Test
    fun testPoolCurrentSlotAdvances() {
        val pool = MicBufferPool(poolSize = 4)
        assertEquals(0, pool.currentSlot())
        pool.obtain(10)
        assertEquals(1, pool.currentSlot())
        pool.obtain(10)
        assertEquals(2, pool.currentSlot())
        pool.obtain(10)
        assertEquals(3, pool.currentSlot())
        pool.obtain(10)
        assertEquals(0, pool.currentSlot())
    }
}
