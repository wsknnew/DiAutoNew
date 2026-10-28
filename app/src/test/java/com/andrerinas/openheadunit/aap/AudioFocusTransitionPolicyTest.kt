package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.aap.protocol.proto.Control
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AudioFocusTransitionPolicyTest {

    @Test
    fun `audio focus release maps to STATE_LOSS_TRANSIENT to prevent routing collapse during song transition`() {
        val releaseResponse = AudioFocusPolicy.getResponse(
            Control.AudioFocusRequestNotification.AudioFocusRequestType.RELEASE,
            isMediaActive = false
        )
        assertEquals(Control.AudioFocusNotification.AudioFocusStateType.STATE_LOSS_TRANSIENT, releaseResponse)
        assertNotEquals(Control.AudioFocusNotification.AudioFocusStateType.STATE_LOSS, releaseResponse)
    }

    @Test
    fun `audio focus release maps to STATE_GAIN when media is active during navigation reminder`() {
        val releaseResponse = AudioFocusPolicy.getResponse(
            Control.AudioFocusRequestNotification.AudioFocusRequestType.RELEASE,
            isMediaActive = true
        )
        assertEquals(Control.AudioFocusNotification.AudioFocusStateType.STATE_GAIN, releaseResponse)
        assertNotEquals(Control.AudioFocusNotification.AudioFocusStateType.STATE_LOSS_TRANSIENT, releaseResponse)
        assertNotEquals(Control.AudioFocusNotification.AudioFocusStateType.STATE_LOSS, releaseResponse)
    }

    @Test
    fun `audio focus gain maps to STATE_GAIN`() {
        val gainResponse = AudioFocusPolicy.getResponse(Control.AudioFocusRequestNotification.AudioFocusRequestType.GAIN)
        assertEquals(Control.AudioFocusNotification.AudioFocusStateType.STATE_GAIN, gainResponse)
    }

    @Test
    fun `audio focus transient gain maps to STATE_GAIN_TRANSIENT`() {
        val transientResponse = AudioFocusPolicy.getResponse(Control.AudioFocusRequestNotification.AudioFocusRequestType.GAIN_TRANSIENT)
        assertEquals(Control.AudioFocusNotification.AudioFocusStateType.STATE_GAIN_TRANSIENT, transientResponse)
    }

    @Test
    fun `audio focus ducking maps to STATE_GAIN_TRANSIENT_GUIDANCE_ONLY`() {
        val duckResponse = AudioFocusPolicy.getResponse(Control.AudioFocusRequestNotification.AudioFocusRequestType.GAIN_TRANSIENT_MAY_DUCK)
        assertEquals(Control.AudioFocusNotification.AudioFocusStateType.STATE_GAIN_TRANSIENT_GUIDANCE_ONLY, duckResponse)
    }
}
