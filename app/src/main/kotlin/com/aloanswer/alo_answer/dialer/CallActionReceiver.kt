package com.aloanswer.alo_answer.dialer

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.telecom.PhoneAccount
import android.telecom.TelecomManager

/** Buttons on the call and missed-call notifications. Not exported. */
class CallActionReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_ANSWER = "com.aloanswer.alo_answer.call.ANSWER"
        const val ACTION_DECLINE = "com.aloanswer.alo_answer.call.DECLINE"
        const val ACTION_HANGUP = "com.aloanswer.alo_answer.call.HANGUP"
        const val ACTION_CALL_BACK = "com.aloanswer.alo_answer.call.CALL_BACK"
        const val EXTRA_NUMBER = "number"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_ANSWER -> CallManager.ringingCall()?.let {
                CallManager.answer(it)
                InCallActivity.start(context)
            }
            ACTION_DECLINE -> CallManager.ringingCall()?.let { CallManager.reject(it) }
            ACTION_HANGUP -> CallManager.primaryCall()?.let { CallManager.hangup(it) }
            ACTION_CALL_BACK -> {
                val number = intent.getStringExtra(EXTRA_NUMBER)?.takeIf { it.isNotBlank() } ?: return
                MissedCalls.markRead(context)
                // Without the permission the notification opens the dial pad instead (see MissedCalls).
                if (context.checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) return
                try {
                    context.getSystemService(TelecomManager::class.java)
                        ?.placeCall(Uri.fromParts(PhoneAccount.SCHEME_TEL, number, null), Bundle())
                } catch (_: Exception) {
                }
            }
        }
    }
}
