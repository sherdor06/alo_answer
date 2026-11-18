package com.aloanswer.alo_answer.dialer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Answer / Decline / Hang up buttons on the call notifications. Not exported. */
class CallActionReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_ANSWER = "com.aloanswer.alo_answer.call.ANSWER"
        const val ACTION_DECLINE = "com.aloanswer.alo_answer.call.DECLINE"
        const val ACTION_HANGUP = "com.aloanswer.alo_answer.call.HANGUP"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_ANSWER -> CallManager.ringingCall()?.let {
                CallManager.answer(it)
                InCallActivity.start(context)
            }
            ACTION_DECLINE -> CallManager.ringingCall()?.let { CallManager.reject(it) }
            ACTION_HANGUP -> CallManager.primaryCall()?.let { CallManager.hangup(it) }
        }
    }
}
