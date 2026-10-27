package com.andrerinas.openheadunit.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingPolicyTest {

    @Test
    fun `current onboarding version is 3`() {
        assertEquals(3, OnboardingActivity.CURRENT_ONBOARDING_VERSION)
    }

    @Test
    fun `fresh install requires onboarding`() {
        val storedVersion = 0
        val isPending = storedVersion < OnboardingActivity.CURRENT_ONBOARDING_VERSION
        assertTrue("Fresh install should require onboarding", isPending)
    }

    @Test
    fun `upgrader from older version requires simplified onboarding once`() {
        val legacyVersion = 2
        val isPending = legacyVersion < OnboardingActivity.CURRENT_ONBOARDING_VERSION
        assertTrue("Legacy v2 install should see simplified onboarding", isPending)
    }

    @Test
    fun `completed onboarding does not trigger wizard again`() {
        val completedVersion = 3
        val isPending = completedVersion < OnboardingActivity.CURRENT_ONBOARDING_VERSION
        assertFalse("Completed onboarding should not be pending", isPending)
    }

    @Test
    fun `deferred session flag can be set and reset`() {
        OnboardingActivity.deferredThisSession = true
        assertTrue(OnboardingActivity.deferredThisSession)
        OnboardingActivity.deferredThisSession = false
        assertFalse(OnboardingActivity.deferredThisSession)
    }
}
