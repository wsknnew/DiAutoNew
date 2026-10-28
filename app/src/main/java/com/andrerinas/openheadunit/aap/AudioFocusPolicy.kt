package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.aap.protocol.proto.Control

/**
 * Decides headunit audio focus responses for incoming Android Auto AudioFocusRequestNotification.
 *
 * During track transitions, streaming services (e.g. YouTube Music, Spotify) briefly release focus
 * before acquiring focus for the next track. Responding with permanent STATE_LOSS causes Android Auto
 * to tear down virtual audio routing and flip audio to phone speakers/Bluetooth, which triggers
 * concurrent playback detection ("too many devices streaming on your plan right now").
 * Responding with STATE_LOSS_TRANSIENT preserves the virtual audio pipeline in standby mode.
 *
 * During navigation guidance (e.g. Google Maps or Waze turn-by-turn prompts or incident alerts),
 * the phone requests transient focus (GAIN_TRANSIENT_MAY_DUCK) while media is actively playing.
 * When the navigation reminder finishes, the phone releases that transient focus.
 * If media is active, responding with STATE_LOSS_TRANSIENT mistakenly informs the phone that
 * all audio has lost focus, pausing YouTube Music and causing concurrent stream errors on subsequent reminders.
 * Instead, when media is active, releasing transient guidance returns the focus state to STATE_GAIN.
 */
object AudioFocusPolicy {

    val focusResponse: Map<Control.AudioFocusRequestNotification.AudioFocusRequestType, Control.AudioFocusNotification.AudioFocusStateType> = mapOf(
        Control.AudioFocusRequestNotification.AudioFocusRequestType.RELEASE to Control.AudioFocusNotification.AudioFocusStateType.STATE_LOSS_TRANSIENT,
        Control.AudioFocusRequestNotification.AudioFocusRequestType.GAIN to Control.AudioFocusNotification.AudioFocusStateType.STATE_GAIN,
        Control.AudioFocusRequestNotification.AudioFocusRequestType.GAIN_TRANSIENT to Control.AudioFocusNotification.AudioFocusStateType.STATE_GAIN_TRANSIENT,
        Control.AudioFocusRequestNotification.AudioFocusRequestType.GAIN_TRANSIENT_MAY_DUCK to Control.AudioFocusNotification.AudioFocusStateType.STATE_GAIN_TRANSIENT_GUIDANCE_ONLY
    )

    fun getResponse(
        request: Control.AudioFocusRequestNotification.AudioFocusRequestType,
        isMediaActive: Boolean = false
    ): Control.AudioFocusNotification.AudioFocusStateType? {
        if (request == Control.AudioFocusRequestNotification.AudioFocusRequestType.RELEASE && isMediaActive) {
            return Control.AudioFocusNotification.AudioFocusStateType.STATE_GAIN
        }
        return focusResponse[request]
    }
}
