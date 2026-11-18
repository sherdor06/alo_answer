package com.aloanswer.alo_answer.ui

import android.app.AlertDialog
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import com.aloanswer.alo_answer.EventLog
import com.aloanswer.alo_answer.R
import com.aloanswer.alo_answer.dialer.dp
import java.util.Calendar

/**
 * Last 20 events of the native log, newest first, in the current language
 * (only text that came straight from Android, such as an error, stays as it was).
 */
class EventLogActivity : Screen() {

    private var events: List<Map<String, Any>> = emptyList()
    private lateinit var clearButton: ImageView
    private val listener: (Map<String, Any>) -> Unit = { e ->
        if (e["type"] != EventLog.TEST_HIT) load()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scaffold(getString(R.string.ui_last20), back = true)
        clearButton = addAction(R.drawable.ic_delete_sweep, getString(R.string.ui_clear)) { confirmClear() }
        EventLog.addListener(listener)
    }

    override fun onScreenResume() = load()

    override fun onDestroy() {
        EventLog.removeListener(listener)
        super.onDestroy()
    }

    private fun load() {
        events = EventLog.all(this).reversed()
        render()
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle(R.string.ui_clear_log_q)
            .setNegativeButton(R.string.ui_cancel, null)
            .setPositiveButton(R.string.ui_clear) { _, _ ->
                EventLog.clear(this)
                load()
            }
            .show()
    }

    private fun render() {
        content.removeAllViews()
        clearButton.enabled(events.isNotEmpty())
        if (events.isEmpty()) {
            content.addView(text(getString(R.string.ui_no_events), 15f, c.dim).apply {
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(32), dp(16), dp(32))
            }, matchWrap())
            return
        }
        val list = card().apply { setPadding(0, dp(4), 0, dp(4)) }
        events.forEachIndexed { i, e ->
            if (i > 0) {
                list.addView(View(this).apply { setBackgroundColor(c.outline) }, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 1
                ).apply { marginStart = dp(56) })
            }
            list.addView(row(e))
        }
    }

    private fun row(e: Map<String, Any>): View {
        val type = e["type"] as? String ?: "?"
        val msg = EventLog.message(this, e)
        val bad = type in setOf("answer_failed", "speaker_failed", "mic_blocked", "error")
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        row.addView(ImageView(this).apply {
            setImageResource(icon(type))
            setColorFilter(if (bad) c.error else c.primary)
        }, LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(16); topMargin = dp(2) })
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val title = label(type)
        texts.addView(text(title, 15.5f))
        if (msg.isNotEmpty() && !msg.equals(title, ignoreCase = true)) {
            texts.addView(text(msg, 13.5f, c.dim), matchWrap().apply { topMargin = dp(2) })
        }
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(text(time((e["ts"] as? Number)?.toLong() ?: 0L), 12f, c.dim).apply {
            gravity = Gravity.END
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = dp(12) })
        return row
    }

    private fun time(ms: Long): String {
        val t = Calendar.getInstance().apply { timeInMillis = ms }
        return "${two(t.get(Calendar.HOUR_OF_DAY))}:${two(t.get(Calendar.MINUTE))}:${two(t.get(Calendar.SECOND))}\n" +
            "${two(t.get(Calendar.DAY_OF_MONTH))}.${two(t.get(Calendar.MONTH) + 1)}"
    }

    private fun icon(type: String) = when (type) {
        EventLog.RING -> R.drawable.ic_ring_volume
        EventLog.LISTENING -> R.drawable.ic_mic
        EventLog.WAKE, EventLog.TEST_HIT -> R.drawable.ic_record_voice_over
        EventLog.ANSWER_REQUESTED, EventLog.OFFHOOK -> R.drawable.ic_call
        EventLog.ANSWER_FAILED, EventLog.IDLE -> R.drawable.ic_call_end
        EventLog.SPEAKER_OK -> R.drawable.ic_volume_up
        EventLog.SPEAKER_FAILED -> R.drawable.ic_volume_off
        EventLog.SPEAKER_KEPT -> R.drawable.ic_bluetooth
        EventLog.TIMEOUT -> R.drawable.ic_timer_off
        EventLog.STOPPED -> R.drawable.ic_mic_off
        EventLog.MIC_BLOCKED -> R.drawable.ic_block
        else -> R.drawable.ic_error_outline
    }

    private fun label(type: String): String {
        val id = when (type) {
            EventLog.RING -> R.string.ui_event_ring
            EventLog.LISTENING -> R.string.ui_event_listening
            EventLog.WAKE -> R.string.ui_event_wake
            EventLog.TEST_HIT -> R.string.ui_event_test_hit
            EventLog.ANSWER_REQUESTED -> R.string.ui_event_answer_requested
            EventLog.ANSWER_FAILED -> R.string.ui_event_answer_failed
            EventLog.OFFHOOK -> R.string.ui_event_offhook
            EventLog.IDLE -> R.string.ui_event_idle
            EventLog.SPEAKER_OK -> R.string.ui_event_speaker_ok
            EventLog.SPEAKER_FAILED -> R.string.ui_event_speaker_failed
            EventLog.SPEAKER_KEPT -> R.string.ui_event_speaker_kept
            EventLog.TIMEOUT -> R.string.ui_event_timeout
            EventLog.STOPPED -> R.string.ui_event_stopped
            EventLog.MIC_BLOCKED -> R.string.ui_event_mic_blocked
            EventLog.ERROR -> R.string.ui_event_error
            else -> return type
        }
        return getString(id)
    }
}
