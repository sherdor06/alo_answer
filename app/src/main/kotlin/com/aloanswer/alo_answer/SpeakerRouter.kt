package com.aloanswer.alo_answer

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

/**
 * BEST-EFFORT loudspeaker routing for a call this app does not own.
 *
 * A non-default-dialer app cannot reliably force a SIM call to the speaker:
 * setCommunicationDevice() applies to the requesting app's communication use
 * case, and setMode(MODE_IN_CALL) is reserved for the telephony app. We try
 * anyway, then read back what AudioManager reports. A "reported OK" result is
 * NOT proof the call is audible on the speaker — the UI says so.
 */
object SpeakerRouter {

    private var weChangedRouting = false

    /** Returns a human-readable description of the attempt. */
    fun attempt(ctx: Context): String {
        val am = ctx.getSystemService(AudioManager::class.java)
        return try {
            if (Build.VERSION.SDK_INT >= 31) {
                val speaker = am.availableCommunicationDevices
                    .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                    ?: return "setCommunicationDevice: no built-in speaker in availableCommunicationDevices"
                val accepted = am.setCommunicationDevice(speaker)
                weChangedRouting = accepted
                "setCommunicationDevice(TYPE_BUILTIN_SPEAKER) returned $accepted"
            } else {
                // MODE_IN_CALL is documented as telephony-app only; expected to be ignored or throw.
                var modeNote = ""
                try {
                    am.mode = AudioManager.MODE_IN_CALL
                } catch (e: SecurityException) {
                    modeNote = " (setMode refused: ${e.message})"
                }
                @Suppress("DEPRECATION")
                am.isSpeakerphoneOn = true
                weChangedRouting = true
                "setMode(MODE_IN_CALL) + setSpeakerphoneOn(true)$modeNote"
            }
        } catch (e: Exception) {
            "routing threw ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    /** What AudioManager now reports. True only means the API *says* speaker. */
    fun verify(ctx: Context): Pair<Boolean, String> {
        val am = ctx.getSystemService(AudioManager::class.java)
        return try {
            if (Build.VERSION.SDK_INT >= 31) {
                val dev = am.communicationDevice
                val ok = dev?.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                ok to "getCommunicationDevice()=${dev?.let { typeName(it.type) } ?: "none"}, mode=${am.mode}"
            } else {
                @Suppress("DEPRECATION")
                val on = am.isSpeakerphoneOn
                on to "isSpeakerphoneOn()=$on, mode=${am.mode}"
            }
        } catch (e: Exception) {
            false to "verify threw ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    /** True if the current communication route (the call's, during a call) is the loudspeaker. */
    fun isSpeakerOn(ctx: Context) = verify(ctx).first

    /** Short description of the current route, for the event log. */
    fun describe(ctx: Context) = verify(ctx).second

    /** Undo our request when the call ends so we don't leave routing changed. */
    fun reset(ctx: Context) {
        if (!weChangedRouting) return
        weChangedRouting = false
        val am = ctx.getSystemService(AudioManager::class.java)
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                am.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                am.isSpeakerphoneOn = false
            }
        } catch (_: Exception) {
        }
    }

    private fun typeName(t: Int) = when (t) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "BUILTIN_SPEAKER"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "BUILTIN_EARPIECE"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "WIRED_HEADSET"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BLUETOOTH_SCO"
        else -> "type$t"
    }
}
