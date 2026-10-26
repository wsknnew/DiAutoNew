package com.andrerinas.openheadunit.location

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GpsLocationPolicyTest {

    @Test
    fun `accepts fallback when no primary GPS fix has ever arrived`() {
        assertTrue(GpsLocationPolicy.shouldAcceptFallback(hasGpsFix = false, primaryFixAgeMs = 0L))
        assertTrue(GpsLocationPolicy.shouldAcceptFallback(hasGpsFix = false, primaryFixAgeMs = 10_000L))
    }

    @Test
    fun `rejects fallback when primary GPS fix is fresh`() {
        assertFalse(GpsLocationPolicy.shouldAcceptFallback(hasGpsFix = true, primaryFixAgeMs = 1000L))
        assertFalse(GpsLocationPolicy.shouldAcceptFallback(hasGpsFix = true, primaryFixAgeMs = 5000L))
    }

    @Test
    fun `accepts fallback when primary GPS fix drops out for more than 5 seconds`() {
        assertTrue(GpsLocationPolicy.shouldAcceptFallback(hasGpsFix = true, primaryFixAgeMs = 5001L))
        assertTrue(GpsLocationPolicy.shouldAcceptFallback(hasGpsFix = true, primaryFixAgeMs = 12_000L))
    }

    @Test
    fun `resends fix when navigation GPS is enabled and fix is within stationary window`() {
        // Between 3s and 60s
        assertTrue(GpsLocationPolicy.shouldResendFix(useGpsForNavigation = true, hasFix = true, fixAgeMs = 3000L))
        assertTrue(GpsLocationPolicy.shouldResendFix(useGpsForNavigation = true, hasFix = true, fixAgeMs = 15_000L))
        assertTrue(GpsLocationPolicy.shouldResendFix(useGpsForNavigation = true, hasFix = true, fixAgeMs = 60_000L))
    }

    @Test
    fun `does not resend fix when navigation GPS toggle is disabled`() {
        assertFalse(GpsLocationPolicy.shouldResendFix(useGpsForNavigation = false, hasFix = true, fixAgeMs = 5000L))
    }

    @Test
    fun `does not resend fix when too fresh or no fix exists`() {
        assertFalse(GpsLocationPolicy.shouldResendFix(useGpsForNavigation = true, hasFix = false, fixAgeMs = 5000L))
        assertFalse(GpsLocationPolicy.shouldResendFix(useGpsForNavigation = true, hasFix = true, fixAgeMs = 1000L))
    }

    @Test
    fun `does not resend fix when fix is older than max age threshold`() {
        assertFalse(GpsLocationPolicy.shouldResendFix(useGpsForNavigation = true, hasFix = true, fixAgeMs = 60_001L))
    }

    @Test
    fun `marks fix as stale when older than 60 seconds`() {
        assertFalse(GpsLocationPolicy.isFixStale(hasFix = false, fixAgeMs = 70_000L))
        assertFalse(GpsLocationPolicy.isFixStale(hasFix = true, fixAgeMs = 60_000L))
        assertTrue(GpsLocationPolicy.isFixStale(hasFix = true, fixAgeMs = 60_001L))
    }

    @Test
    fun `triggers HAL recovery when fix has stalled beyond threshold and throttle elapsed`() {
        // Less than 15s since last fix: no recovery
        assertFalse(GpsLocationPolicy.shouldTriggerHalRecovery(timeSinceLastFixMs = 14_999L, timeSinceLastReconnectMs = 20_000L))

        // More than 15s without fix, but reconnect was recent (< 10s): throttled, no recovery
        assertFalse(GpsLocationPolicy.shouldTriggerHalRecovery(timeSinceLastFixMs = 16_000L, timeSinceLastReconnectMs = 5_000L))

        // More than 15s without fix and more than 10s since last reconnect: trigger HAL kick
        assertTrue(GpsLocationPolicy.shouldTriggerHalRecovery(timeSinceLastFixMs = 15_001L, timeSinceLastReconnectMs = 10_001L))
    }
}
