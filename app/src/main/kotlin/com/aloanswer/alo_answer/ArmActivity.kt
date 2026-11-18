package com.aloanswer.alo_answer

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.telephony.TelephonyManager
import android.view.WindowManager

/**
 * Invisible "trampoline" opened by CallStateReceiver while the phone rings.
 *
 * Why it exists: a microphone foreground service may only use the mic if it
 * was started while the app was visible (or via a few system exemptions). A
 * broadcast receiver alone doesn't count; on Android 14+ startForeground()
 * would throw SecurityException. So we show this transparent activity for a
 * moment, start WakeWordService from it, and finish as soon as the service is
 * in the foreground.
 *
 * Opening an activity from the background is itself restricted. The exemption
 * we rely on is SYSTEM_ALERT_WINDOW ("Display over other apps"). Xiaomi adds
 * its own gates ("Display pop-up windows while running in the background",
 * "Show on Lock screen"). A blocked launch fails silently (only a logcat line
 * "Background activity launch blocked!"), so CallStateReceiver checks
 * [lastResumedAt] afterwards.
 */
class ArmActivity : Activity() {

    companion object {
        /** elapsedRealtime() of the last onResume, read by CallStateReceiver's check. */
        @Volatile
        var lastResumedAt = 0L
            private set

        /** Safety net: never stay on screen longer than this. */
        private const val MAX_VISIBLE_MS = 2_000L

        fun launch(ctx: Context) {
            ctx.startActivity(
                Intent(ctx, ArmActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                        Intent.FLAG_ACTIVITY_NO_HISTORY
                )
            )
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var started = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The phone is usually locked while it rings: we must be visible over the keyguard.
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
    }

    override fun onResume() {
        super.onResume()
        lastResumedAt = SystemClock.elapsedRealtime()
        if (started) return
        started = true

        if (Settings.lastCallState(this) != TelephonyManager.EXTRA_STATE_RINGING) {
            finish()
            return
        }

        // Started from onResume (we are visible), and we stay visible until the
        // service reports startForeground() done, so the mic capability is granted.
        WakeWordService.onForegroundResult = { handler.post { finish() } }
        try {
            startForegroundService(
                Intent(this, WakeWordService::class.java).setAction(WakeWordService.ACTION_ARM)
            )
        } catch (e: Exception) {
            WakeWordService.onForegroundResult = null
            val msg = Settings.str(this, R.string.err_start_listening, "${e.javaClass.simpleName}: ${e.message}")
            EventLog.add(this, EventLog.MIC_BLOCKED, msg)
            ProblemNotification.show(this, msg)
            finish()
            return
        }
        handler.postDelayed({ finish() }, MAX_VISIBLE_MS)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        WakeWordService.onForegroundResult = null
        super.onDestroy()
    }
}
