package com.andrerinas.openheadunit.location

/**
 * Pure policy rules for automotive GPS management and watchdog recovery.
 */
object GpsLocationPolicy {
    const val UPDATE_INTERVAL_MS = 1000L
    const val RESEND_INTERVAL_MS = 3000L

    /** How stale the last real fix may be before a resend stops standing in for it. */
    const val RESEND_MAX_AGE_MS = 60_000L

    /** How long without a primary GPS fix before fallback network/passive fixes are forwarded. */
    const val FALLBACK_TRIGGER_AGE_MS = 5000L

    /** How long without any fix before attempting a HAL recovery reconnection. */
    const val STALL_RECOVERY_THRESHOLD_MS = 15_000L

    /** Throttle between successive HAL recovery attempts. */
    const val MIN_RECONNECT_INTERVAL_MS = 10_000L

    /**
     * Whether a fallback fix (from Network or Passive provider) should be forwarded
     * because the primary GPS fix is missing or too stale.
     */
    fun shouldAcceptFallback(hasGpsFix: Boolean, primaryFixAgeMs: Long): Boolean {
        if (!hasGpsFix) return true
        return primaryFixAgeMs > FALLBACK_TRIGGER_AGE_MS
    }

    /**
     * Whether a stationary/last known fix should be resent to keep the Android Auto
     * location channel active.
     */
    fun shouldResendFix(useGpsForNavigation: Boolean, hasFix: Boolean, fixAgeMs: Long): Boolean {
        return useGpsForNavigation && hasFix && fixAgeMs in RESEND_INTERVAL_MS..RESEND_MAX_AGE_MS
    }

    /**
     * Whether the fix has gone completely stale and resends should stop so the phone can
     * switch back to its own GPS.
     */
    fun isFixStale(hasFix: Boolean, fixAgeMs: Long): Boolean {
        return hasFix && fixAgeMs > RESEND_MAX_AGE_MS
    }

    /**
     * Whether the GPS HAL watchdog should trigger a re-registration to kick
     * frozen vendor GPS HAL daemons.
     */
    fun shouldTriggerHalRecovery(timeSinceLastFixMs: Long, timeSinceLastReconnectMs: Long): Boolean {
        return timeSinceLastFixMs > STALL_RECOVERY_THRESHOLD_MS &&
                timeSinceLastReconnectMs > MIN_RECONNECT_INTERVAL_MS
    }
}
