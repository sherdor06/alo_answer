package com.aloanswer.alo_answer.dialer

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.telecom.Call
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.aloanswer.alo_answer.EventLog
import com.aloanswer.alo_answer.R
import com.aloanswer.alo_answer.Settings
import com.aloanswer.alo_answer.WakeWordService

/**
 * The call screen while Alo Answer is the default phone app: incoming call
 * (answer / decline / "say allo") and ongoing call (mute, keypad, speaker or
 * audio picker, hold, end). Native framework views only. Shows over the lock screen.
 *
 * While a call rings and voice answer is on, this screen starts
 * WakeWordService: the mic may only be used while our screen is visible.
 */
class InCallActivity : Activity() {

    companion object {
        /** elapsedRealtime() of the last onResume (CallStateReceiver checks the listener really opened). */
        @Volatile
        var lastResumedAt = 0L
            private set

        /** True while the call screen is in front (the incoming notification then skips the heads-up). */
        @Volatile
        var visible = false
            private set

        fun start(ctx: Context) {
            try {
                ctx.startActivity(
                    Intent(ctx, InCallActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                )
            } catch (_: Exception) {
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())

    private lateinit var nameView: TextView
    private lateinit var numberView: TextView
    private lateinit var statusView: TextView
    private lateinit var voiceView: TextView
    private lateinit var ringingRow: View
    private lateinit var activePanel: View
    private lateinit var keypadPanel: View
    private lateinit var muteBtn: RoundButton
    private lateinit var speakerBtn: RoundButton
    private lateinit var holdBtn: RoundButton
    private lateinit var keypadBtn: RoundButton

    private var proximityLock: PowerManager.WakeLock? = null
    private var endingScheduled = false
    private var accountDialog: AlertDialog? = null
    private var routeDialog: Dialog? = null
    private var routeList: LinearLayout? = null

    private val callListener = CallManager.Listener { render() }
    private val eventListener: (Map<String, Any>) -> Unit = { e -> onVoiceEvent(e["type"] as? String, e["msg"] as? String) }
    private val ticker = object : Runnable {
        override fun run() {
            renderStatus()
            handler.postDelayed(this, 1000)
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(Settings.localized(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        edgeToEdge()
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        lastResumedAt = SystemClock.elapsedRealtime()
        visible = true
        CallNotifications.refresh(this)
        CallManager.addListener(callListener)
        EventLog.addListener(eventListener)
        render()
        handler.post(ticker)
        maybeStartVoiceAnswer()
    }

    override fun onPause() {
        visible = false
        CallNotifications.refresh(this) // ringing again in the background → heads-up comes back
        CallManager.removeListener(callListener)
        EventLog.removeListener(eventListener)
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    override fun onDestroy() {
        releaseProximity()
        accountDialog?.dismiss()
        routeDialog?.dismiss()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // ---------------- voice answer ----------------

    private fun maybeStartVoiceAnswer() {
        if (CallManager.ringingCall() == null) return
        if (!Settings.isEnabled(this)) {
            voiceView.text = getString(R.string.incall_voice_off)
            return
        }
        voiceView.text = getString(R.string.incall_say_alo)
        if (WakeWordService.isArmed) return
        try {
            // We are visible right now, so this microphone service may use the mic.
            startForegroundService(Intent(this, WakeWordService::class.java).setAction(WakeWordService.ACTION_ARM))
        } catch (e: Exception) {
            voiceView.text = "${e.javaClass.simpleName}: ${e.message}"
        }
    }

    private fun onVoiceEvent(type: String?, msg: String?) {
        if (!::voiceView.isInitialized) return
        when (type) {
            EventLog.LISTENING -> voiceView.text = getString(R.string.incall_listening)
            EventLog.WAKE -> voiceView.text = getString(R.string.incall_heard)
            EventLog.ERROR, EventLog.MIC_BLOCKED -> voiceView.text = msg ?: ""
        }
    }

    // ---------------- rendering ----------------

    private fun render() {
        if (!::statusView.isInitialized) return
        val call = CallManager.primaryCall()
        if (call == null) {
            showEnded()
            return
        }
        endingScheduled = false
        val state = CallManager.stateOf(call)
        val name = CallManager.displayName(call) ?: getString(R.string.incall_unknown)
        val number = CallManager.number(call).orEmpty()
        nameView.text = name
        numberView.text = number
        numberView.visibility = if (number.isEmpty() || number == name) View.GONE else View.VISIBLE

        val ringing = state == Call.STATE_RINGING
        ringingRow.visibility = if (ringing) View.VISIBLE else View.GONE
        voiceView.visibility = if (ringing) View.VISIBLE else View.GONE
        activePanel.visibility = if (ringing) View.GONE else View.VISIBLE
        if (ringing) keypadPanel.visibility = View.GONE

        muteBtn.setActive(CallManager.muted)
        renderAudioButton()
        holdBtn.setActive(state == Call.STATE_HOLDING)
        holdBtn.isEnabled = state == Call.STATE_HOLDING || call.details.can(Call.Details.CAPABILITY_HOLD)
        keypadBtn.setActive(keypadPanel.visibility == View.VISIBLE)
        renderStatus()
        // Screen off at the ear: only while the audio is on the earpiece.
        val route = CallManager.currentRoute()?.kind
        updateProximity(
            state == Call.STATE_ACTIVE &&
                (route == CallManager.RouteKind.EARPIECE || (route == null && !CallManager.isSpeakerOn()))
        )
        if (state == Call.STATE_SELECT_PHONE_ACCOUNT) chooseSim(call)
    }

    private fun renderStatus() {
        val call = CallManager.primaryCall() ?: return
        statusView.text = when (CallManager.stateOf(call)) {
            Call.STATE_RINGING -> getString(R.string.incall_incoming)
            Call.STATE_DIALING -> getString(R.string.incall_dialing)
            Call.STATE_HOLDING -> getString(R.string.incall_on_hold)
            Call.STATE_ACTIVE -> duration(call)
            Call.STATE_DISCONNECTING, Call.STATE_DISCONNECTED -> getString(R.string.incall_ended)
            else -> getString(R.string.incall_connecting)
        }
    }

    private fun duration(call: Call): String {
        val start = call.details.connectTimeMillis
        if (start <= 0) return ""
        val s = ((System.currentTimeMillis() - start) / 1000).coerceAtLeast(0)
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60) else "%02d:%02d".format(s / 60, s % 60)
    }

    private fun showEnded() {
        statusView.text = getString(R.string.incall_ended)
        ringingRow.visibility = View.GONE
        activePanel.visibility = View.GONE
        keypadPanel.visibility = View.GONE
        voiceView.visibility = View.GONE
        routeDialog?.dismiss()
        releaseProximity()
        if (!endingScheduled) {
            endingScheduled = true
            handler.postDelayed({ if (CallManager.primaryCall() == null) finishAndRemoveTask() }, 900)
        }
    }

    /** Dual SIM without a default calling SIM: Telecom waits for us to pick one. */
    private fun chooseSim(call: Call) {
        if (accountDialog?.isShowing == true) return
        val telecom = getSystemService(TelecomManager::class.java)
        val handles: List<PhoneAccountHandle> = try {
            telecom.callCapablePhoneAccounts
        } catch (_: SecurityException) {
            emptyList()
        }
        if (handles.isEmpty()) return
        val labels = handles.map { h ->
            try {
                telecom.getPhoneAccount(h)?.label?.toString() ?: h.id
            } catch (_: Exception) {
                h.id
            }
        }.toTypedArray()
        accountDialog = AlertDialog.Builder(this)
            .setTitle(R.string.incall_choose_sim)
            .setItems(labels) { _, which -> call.phoneAccountSelected(handles[which], false) }
            .setOnCancelListener { CallManager.hangup(call) }
            .show()
    }

    // ---------------- audio output (speaker / Bluetooth / phone) ----------------

    /** With a Bluetooth device around there are more than two places for the audio: then the button opens a picker. */
    private fun hasRouteChoice() = CallManager.routes().any { it.kind == CallManager.RouteKind.BLUETOOTH }

    private fun renderAudioButton() {
        val routes = CallManager.routes()
        if (routes.none { it.kind == CallManager.RouteKind.BLUETOOTH }) {
            // Plain speaker on/off.
            speakerBtn.setIcon(R.drawable.ic_volume_up)
            speakerBtn.setLabel(getString(R.string.incall_speaker))
            speakerBtn.setActive(CallManager.isSpeakerOn())
            routeDialog?.dismiss() // the Bluetooth device went away while the picker was open
            return
        }
        val current = routes.firstOrNull { it.active }
        speakerBtn.setIcon(if (current == null) R.drawable.ic_volume_up else iconFor(current.kind))
        speakerBtn.setLabel(
            (if (current == null) getString(R.string.incall_audio) else shortLabel(current.kind)) + " \u25BE"
        )
        speakerBtn.setActive(current != null && current.kind != CallManager.RouteKind.EARPIECE)
        if (routeDialog?.isShowing == true) fillRoutes()
    }

    private fun iconFor(kind: CallManager.RouteKind) = when (kind) {
        CallManager.RouteKind.BLUETOOTH -> R.drawable.ic_bluetooth
        CallManager.RouteKind.WIRED_HEADSET -> R.drawable.ic_headset
        CallManager.RouteKind.EARPIECE -> R.drawable.ic_phone_in_talk
        CallManager.RouteKind.SPEAKER -> R.drawable.ic_volume_up
    }

    private fun kindLabel(kind: CallManager.RouteKind) = getString(
        when (kind) {
            CallManager.RouteKind.BLUETOOTH -> R.string.route_bluetooth
            CallManager.RouteKind.WIRED_HEADSET -> R.string.route_wired
            CallManager.RouteKind.EARPIECE -> R.string.route_phone
            CallManager.RouteKind.SPEAKER -> R.string.incall_speaker
        }
    )

    /** Fits under a round button. */
    private fun shortLabel(kind: CallManager.RouteKind) =
        if (kind == CallManager.RouteKind.WIRED_HEADSET) getString(R.string.route_wired_short) else kindLabel(kind)

    /** Bottom sheet: Bluetooth device(s), wired headset, phone, speaker; a tick on the current one. */
    private fun showRoutes() {
        routeDialog?.dismiss()
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Palette.SHEET, dp(28).toFloat())
            setPadding(dp(8), dp(20), dp(8), dp(12))
            addView(TextView(this@InCallActivity).apply {
                text = getString(R.string.route_title)
                textSize = 15f
                setTextColor(Palette.TEXT_DIM)
                setPadding(dp(16), 0, dp(16), dp(10))
            })
            addView(list)
        }
        val outer = FrameLayout(this).apply {
            setPadding(dp(12), 0, dp(12), dp(12))
            addView(sheet)
        }
        val d = Dialog(this)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        d.setContentView(outer)
        d.window?.let { w ->
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            w.setGravity(Gravity.BOTTOM)
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        d.setOnDismissListener {
            if (routeDialog === d) {
                routeDialog = null
                routeList = null
            }
        }
        routeDialog = d
        routeList = list
        fillRoutes()
        d.show()
    }

    /** (Re)builds the picker rows; called again when Telecom reports a change while it is open. */
    private fun fillRoutes() {
        val list = routeList ?: return
        list.removeAllViews()
        for (r in CallManager.routes()) list.addView(routeRow(r))
    }

    private fun routeRow(r: CallManager.AudioRoute): View {
        val radius = dp(16).toFloat()
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(60)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            background = RippleDrawable(
                ColorStateList.valueOf(0x40FFFFFF),
                if (r.active) rounded(0x1FFFFFFF, radius) else null,
                rounded(Color.WHITE, radius) // ripple mask
            )
            setOnClickListener {
                CallManager.selectRoute(r)
                routeDialog?.dismiss()
            }
        }
        val tint = if (r.active) Palette.ACCENT else Color.WHITE
        row.addView(ImageView(this).apply {
            setImageResource(iconFor(r.kind))
            setColorFilter(tint)
        }, LinearLayout.LayoutParams(dp(24), dp(24)))
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), 0, dp(8), 0)
        }
        texts.addView(TextView(this).apply {
            text = r.name ?: kindLabel(r.kind)
            textSize = 17f
            setTextColor(tint)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        if (r.name != null) {
            texts.addView(TextView(this).apply {
                text = kindLabel(r.kind)
                textSize = 13f
                setTextColor(Palette.TEXT_DIM)
            })
        }
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (r.active) {
            row.addView(ImageView(this).apply {
                setImageResource(R.drawable.ic_check)
                setColorFilter(Palette.ACCENT)
            }, LinearLayout.LayoutParams(dp(24), dp(24)))
        }
        return row
    }

    // ---------------- proximity (screen off at the ear) ----------------

    private fun updateProximity(wanted: Boolean) {
        if (wanted) {
            if (proximityLock?.isHeld == true) return
            val pm = getSystemService(PowerManager::class.java)
            if (!pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) return
            proximityLock = pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "aloanswer:incall")
                .apply { acquire(3 * 60 * 60 * 1000L) }
        } else {
            releaseProximity()
        }
    }

    private fun releaseProximity() {
        try {
            proximityLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {
        }
        proximityLock = null
    }

    // ---------------- UI ----------------

    private fun text(sizeSp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        textSize = sizeSp
        setTextColor(color)
        gravity = Gravity.CENTER
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
    }

    private fun lp(w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT) =
        LinearLayout.LayoutParams(w, h)

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = screenBackground()
            padForSystemBars(dp(16))
        }

        root.addView(View(this), lp(h = 0).apply { weight = 0.6f })

        val avatar = ImageView(this).apply {
            setImageResource(R.drawable.ic_person)
            setColorFilter(android.graphics.Color.WHITE)
            background = circle(Palette.TEAL)
            val p = dp(22)
            setPadding(p, p, p, p)
        }
        root.addView(avatar, LinearLayout.LayoutParams(dp(104), dp(104)))

        nameView = text(30f, android.graphics.Color.WHITE, bold = true)
        root.addView(nameView, lp().apply { topMargin = dp(20) })
        numberView = text(18f, Palette.TEXT_DIM)
        root.addView(numberView, lp().apply { topMargin = dp(4) })
        statusView = text(17f, 0xFF80CBC4.toInt())
        root.addView(statusView, lp().apply { topMargin = dp(10) })

        voiceView = text(15f, android.graphics.Color.WHITE).apply {
            background = rounded(0x26FFFFFF, dp(20).toFloat())
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_stat_mic, 0, 0, 0)
            compoundDrawablePadding = dp(8)
        }
        root.addView(voiceView, lp(ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(24) })

        root.addView(View(this), lp(h = 0).apply { weight = 1f })

        keypadPanel = buildKeypad()
        root.addView(keypadPanel, lp(ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) })

        activePanel = buildActivePanel()
        root.addView(activePanel, lp())

        ringingRow = buildRingingRow()
        root.addView(ringingRow, lp())

        return root
    }

    private fun buildRingingRow(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(24))
        }
        val decline = RoundButton(this, R.drawable.ic_call_end, getString(R.string.incall_decline), 76, 34, Palette.RED)
        decline.onClick { CallManager.ringingCall()?.let { CallManager.reject(it) } }
        val answer = RoundButton(this, R.drawable.ic_call, getString(R.string.incall_answer), 76, 34, Palette.GREEN)
        answer.onClick { CallManager.ringingCall()?.let { CallManager.answer(it) } }
        row.addView(decline)
        row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        row.addView(answer)
        return FrameLayout(this).apply {
            addView(row, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { leftMargin = dp(36); rightMargin = dp(36) })
        }
    }

    private fun buildActivePanel(): View {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, 0, dp(16))
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        muteBtn = RoundButton(this, R.drawable.ic_mic_off, getString(R.string.incall_mute), 64, 28, Palette.BUTTON)
        muteBtn.onClick { CallManager.setMuted(!CallManager.muted) }
        keypadBtn = RoundButton(this, R.drawable.ic_dialpad, getString(R.string.incall_keypad), 64, 28, Palette.BUTTON)
        keypadBtn.onClick {
            keypadPanel.visibility = if (keypadPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            keypadBtn.setActive(keypadPanel.visibility == View.VISIBLE)
        }
        speakerBtn = RoundButton(this, R.drawable.ic_volume_up, getString(R.string.incall_speaker), 64, 28, Palette.BUTTON)
        speakerBtn.onClick {
            if (hasRouteChoice()) showRoutes() else CallManager.setSpeaker(!CallManager.isSpeakerOn())
        }
        holdBtn = RoundButton(this, R.drawable.ic_pause, getString(R.string.incall_hold), 64, 28, Palette.BUTTON)
        holdBtn.onClick { CallManager.primaryCall()?.let { CallManager.toggleHold(it) } }
        for (b in listOf(muteBtn, keypadBtn, speakerBtn, holdBtn)) {
            row.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        panel.addView(row, lp())
        val end = RoundButton(this, R.drawable.ic_call_end, getString(R.string.incall_end), 76, 34, Palette.RED)
        end.onClick { CallManager.primaryCall()?.let { CallManager.hangup(it) } }
        panel.addView(end, lp(ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(28) })
        return panel
    }

    /** DTMF keypad: tone plays while the key is held. */
    private fun buildKeypad(): View {
        val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "*", "0", "#")
        val grid = GridLayout(this).apply {
            columnCount = 3
            visibility = View.GONE
        }
        for (k in keys) {
            val key = TextView(this).apply {
                text = k
                textSize = 26f
                setTextColor(android.graphics.Color.WHITE)
                gravity = Gravity.CENTER
                background = ripple(circle(Palette.BUTTON))
                setOnTouchListener { v, ev ->
                    val call = CallManager.primaryCall()
                    when (ev.action) {
                        MotionEvent.ACTION_DOWN -> call?.playDtmfTone(k[0])
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            call?.stopDtmfTone()
                            if (ev.action == MotionEvent.ACTION_UP) v.performClick()
                        }
                    }
                    false
                }
            }
            grid.addView(key, GridLayout.LayoutParams().apply {
                width = dp(64)
                height = dp(64)
                setMargins(dp(10), dp(6), dp(10), dp(6))
            })
        }
        return grid
    }
}
