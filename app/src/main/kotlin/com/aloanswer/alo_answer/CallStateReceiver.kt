package com.aloanswer.alo_answer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.telephony.TelephonyManager
import com.aloanswer.alo_answer.dialer.DialerRole
import com.aloanswer.alo_answer.dialer.InCallActivity
import android.provider.Settings as AndroidSettings

/**
 * Manifest receiver for android.intent.action.PHONE_STATE
 * (TelephonyManager.ACTION_PHONE_STATE_CHANGED is on the implicit-broadcast
 * exception list, so no always-on service is needed).
 *
 * RINGING -> open the invisible ArmActivity, which starts the mic service.
 *            (Starting the mic service directly from here is not allowed.)
 * OFFHOOK / IDLE -> tell the service to release the mic.
 */
class CallStateReceiver : BroadcastReceiver() {

    companion object {
        private const val LAUNCH_CHECK_MS = 3_000L
        private const val DUPLICATE_WINDOW_MS = 3_000L
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        val app = context.applicationContext

        val previous = Settings.lastCallState(app)
        if (state == previous && Settings.msSinceLastCallState(app) < DUPLICATE_WINDOW_MS) {
            return // some ROMs deliver the same state twice
        }
        Settings.setLastCallState(app, state)

        when (state) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                if (Settings.isEnabled(app)) onRinging(app)
            }
            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                if (Settings.incomingActive(app) && previous == TelephonyManager.EXTRA_STATE_RINGING) {
                    EventLog.add(app, EventLog.OFFHOOK, Settings.str(app, R.string.ev_offhook))
                }
                WakeWordService.instance?.onCallStateChanged(state)
            }
            TelephonyManager.EXTRA_STATE_IDLE -> {
                if (Settings.incomingActive(app)) {
                    EventLog.add(
                        app, EventLog.IDLE,
                        Settings.str(
                            app,
                            if (previous == TelephonyManager.EXTRA_STATE_RINGING) R.string.ev_missed
                            else R.string.ev_call_ended
                        )
                    )
                    Settings.setIncomingActive(app, false)
                }
                WakeWordService.instance?.onCallStateChanged(state)
                SpeakerRouter.reset(app)
            }
        }
    }

    private fun onRinging(app: Context) {
        Settings.setIncomingActive(app, true)
        EventLog.add(app, EventLog.RING, Settings.str(app, R.string.ev_ringing))

        val requestedAt = SystemClock.elapsedRealtime()
        val dialerMode = DialerRole.isDefault(app)
        if (!dialerMode) try {
            ArmActivity.launch(app)
        } catch (e: Exception) {
            val msg = Settings.str(app, R.string.err_open_listener, "${e.javaClass.simpleName}: ${e.message}")
            EventLog.add(app, EventLog.MIC_BLOCKED, msg)
            ProblemNotification.show(app, msg)
            return
        }

        // A blocked background activity launch fails silently, so check afterwards
        // whether ArmActivity actually came up.
        val pending = goAsync()
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                // Default phone app: our own call screen (InCallActivity) starts listening.
                // Otherwise: the invisible ArmActivity does. Either may have opened a moment
                // before this broadcast arrived, hence the 5 s tolerance.
                val listenerOpenedAt = if (dialerMode) InCallActivity.lastResumedAt else ArmActivity.lastResumedAt
                if (listenerOpenedAt < requestedAt - 5_000 &&
                    Settings.lastCallState(app) == TelephonyManager.EXTRA_STATE_RINGING
                ) {
                    val reason = Settings.str(
                        app,
                        when {
                            dialerMode -> R.string.blocked_dialer
                            !AndroidSettings.canDrawOverlays(app) -> R.string.blocked_overlay
                            else -> R.string.blocked_xiaomi
                        }
                    )
                    EventLog.add(app, EventLog.MIC_BLOCKED, reason)
                    ProblemNotification.show(app, reason)
                }
            } finally {
                pending.finish()
            }
        }, LAUNCH_CHECK_MS)
    }
}
