package com.aloanswer.alo_answer.ui

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.aloanswer.alo_answer.EventLog
import com.aloanswer.alo_answer.R
import com.aloanswer.alo_answer.WakeWordService
import com.aloanswer.alo_answer.dialer.circle
import com.aloanswer.alo_answer.dialer.dp

/**
 * Listens for the wake word right now, with no call, and flashes on each hit.
 * Same service, model and rules as a real call, but it never answers anything.
 */
class TestListenActivity : Screen() {

    private val handler = Handler(Looper.getMainLooper())
    private var starting = true
    private var listening = false
    private var flash = false
    private var hits = 0
    private var error: String? = null

    private lateinit var ring: FrameLayout
    private lateinit var icon: ImageView
    private lateinit var statusView: TextView
    private lateinit var hitsView: TextView
    private lateinit var errorView: TextView
    private lateinit var againButton: View

    private val listener: (Map<String, Any>) -> Unit = { e -> onEvent(e["type"] as? String, e["msg"] as? String) }
    private val unflash = Runnable {
        flash = false
        render()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scaffold(getString(R.string.ui_test_listening), back = true)
        content.gravity = Gravity.CENTER_HORIZONTAL
        gap(24)
        icon = ImageView(this)
        ring = FrameLayout(this).apply {
            addView(icon, FrameLayout.LayoutParams(dp(96), dp(96), Gravity.CENTER))
        }
        content.addView(ring, LinearLayout.LayoutParams(dp(200), dp(200)))
        statusView = text("", 24f).apply { gravity = Gravity.CENTER }
        content.addView(statusView, matchWrap().apply { topMargin = dp(24) })
        hitsView = text("", 17f, c.dim).apply { gravity = Gravity.CENTER }
        content.addView(hitsView, matchWrap().apply { topMargin = dp(8) })
        errorView = text("", 15f, c.error).apply { gravity = Gravity.CENTER }
        content.addView(errorView, matchWrap().apply { topMargin = dp(16) })
        againButton = button(getString(R.string.ui_start_again), Btn.FILLED) { start() }
        content.addView(againButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(16) })
        content.addView(text(getString(R.string.ui_test_footnote), 14f, c.dim), matchWrap().apply { topMargin = dp(32) })
        EventLog.addListener(listener)
        start()
    }

    override fun onDestroy() {
        EventLog.removeListener(listener)
        handler.removeCallbacksAndMessages(null)
        // Never leave the mic open.
        if (WakeWordService.isTesting) {
            try {
                startService(Intent(this, WakeWordService::class.java).setAction(WakeWordService.ACTION_STOP))
            } catch (_: Exception) {
            }
        }
        super.onDestroy()
    }

    private fun start() {
        starting = true
        listening = false
        error = null
        if (WakeWordService.isArmed) {
            // Don't take over the listener of a real ringing call.
            starting = false
            error = getString(R.string.ui_test_busy)
        } else {
            try {
                // Started while this screen is visible, so the service may use the mic.
                startForegroundService(Intent(this, WakeWordService::class.java).setAction(WakeWordService.ACTION_TEST))
            } catch (e: Exception) {
                starting = false
                error = "${e.javaClass.simpleName}: ${e.message}"
            }
        }
        render()
    }

    private fun onEvent(type: String?, msg: String?) {
        when (type) {
            EventLog.LISTENING -> {
                starting = false
                listening = true
            }
            EventLog.TEST_HIT -> {
                hits++
                flash = true
                handler.removeCallbacks(unflash)
                handler.postDelayed(unflash, 700)
                ring.animate().scaleX(1.06f).scaleY(1.06f).setDuration(110).withEndAction {
                    ring.animate().scaleX(1f).scaleY(1f).setDuration(160).start()
                }.start()
            }
            EventLog.ERROR, EventLog.MIC_BLOCKED -> {
                starting = false
                listening = false
                error = msg
            }
            EventLog.TIMEOUT, EventLog.STOPPED -> {
                starting = false
                listening = false
            }
            else -> return
        }
        render()
    }

    private fun render() {
        ring.background = circle(
            when {
                flash -> c.hit
                listening -> c.primaryContainer
                else -> c.neutralContainer
            }
        )
        icon.setImageResource(
            when {
                flash -> R.drawable.ic_check
                listening -> R.drawable.ic_mic
                else -> R.drawable.ic_mic_off
            }
        )
        icon.setColorFilter(if (flash) Color.WHITE else c.onPrimaryContainer)
        statusView.text = getString(
            when {
                starting -> R.string.ui_starting
                listening -> R.string.ui_say_alo
                else -> R.string.ui_not_listening
            }
        )
        hitsView.text = resources.getQuantityString(R.plurals.ui_heard_times, hits, hits)
        errorView.text = error.orEmpty()
        errorView.visibility = if (error.isNullOrEmpty()) View.GONE else View.VISIBLE
        againButton.visibility = if (!listening && !starting) View.VISIBLE else View.GONE
    }
}
