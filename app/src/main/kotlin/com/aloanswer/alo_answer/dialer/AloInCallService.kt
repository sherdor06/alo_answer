package com.aloanswer.alo_answer.dialer

import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.CallEndpoint
import android.telecom.InCallService
import com.aloanswer.alo_answer.Settings

/**
 * Telecom binds this while Alo Answer is the default phone app and there is
 * at least one call. It replaces the system in-call UI
 * (meta-data android.telecom.IN_CALL_SERVICE_UI = true). Telecom itself keeps
 * playing the ringtone (we don't declare IN_CALL_SERVICE_RINGING).
 */
class AloInCallService : InCallService() {

    private val notifier = CallManager.Listener { CallNotifications.refresh(this) }

    override fun onCreate() {
        super.onCreate()
        CallManager.service = this
        CallManager.addListener(notifier)
    }

    override fun onDestroy() {
        CallManager.removeListener(notifier)
        if (CallManager.service === this) CallManager.service = null
        CallNotifications.cancelAll(this)
        super.onDestroy()
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        CallManager.service = this
        // Debug builds only: the pretend Bluetooth headset for trying the audio picker on an emulator.
        if (CallManager.calls.isEmpty()) CallManager.setDemoBluetooth(Settings.debugFakeBluetooth(this))
        CallManager.onCallAdded(call)
        CallManager.resolveName(this, call)
        val ringing = CallManager.stateOf(call) == Call.STATE_RINGING
        // Outgoing call: show our call screen. Incoming call: the notification's
        // full-screen intent shows it when the phone is locked; with voice answer
        // on we open it right away, because the mic may only be used while our
        // screen is visible.
        if (!ringing || Settings.isEnabled(this)) InCallActivity.start(this)
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        CallManager.onCallRemoved(call)
    }

    @Deprecated("Replaced by onCallEndpointChanged/onMuteStateChanged on Android 14+")
    override fun onCallAudioStateChanged(audioState: CallAudioState?) {
        @Suppress("DEPRECATION")
        super.onCallAudioStateChanged(audioState)
        CallManager.onAudioState(audioState)
    }

    override fun onCallEndpointChanged(callEndpoint: CallEndpoint) {
        super.onCallEndpointChanged(callEndpoint)
        CallManager.onEndpoint(callEndpoint)
    }

    override fun onAvailableCallEndpointsChanged(availableEndpoints: List<CallEndpoint>) {
        super.onAvailableCallEndpointsChanged(availableEndpoints)
        CallManager.onAvailableEndpoints(availableEndpoints)
    }

    override fun onMuteStateChanged(isMuted: Boolean) {
        super.onMuteStateChanged(isMuted)
        CallManager.onMute(isMuted)
    }
}
