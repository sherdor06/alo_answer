package com.aloanswer.alo_answer

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Last-20 event ring buffer, persisted in SharedPreferences so it survives the
 * process dying between calls, plus live listeners for the open screens.
 */
object EventLog {
    private const val TAG = "AloAnswer"
    private const val FILE = "alo_events"
    private const val KEY = "events"
    private const val MAX = 20

    // Event types (icons and labels: ui/EventLogActivity.kt)
    const val RING = "ring"
    const val PROMPT_SHOWN = "prompt_shown"
    const val LISTENING = "listening"
    const val WAKE = "wake"
    const val TEST_HIT = "test_hit" // live only, not persisted
    const val ANSWER_REQUESTED = "answer_requested"
    const val ANSWER_FAILED = "answer_failed"
    const val OFFHOOK = "offhook"
    const val IDLE = "idle"
    const val SPEAKER_OK = "speaker_ok"
    const val SPEAKER_FAILED = "speaker_failed"
    /** A Bluetooth device or wired headset had the call, so the loudspeaker was left off. */
    const val SPEAKER_KEPT = "speaker_kept"
    const val TIMEOUT = "timeout"
    const val STOPPED = "stopped"
    const val MIC_BLOCKED = "mic_blocked"
    const val ERROR = "error"

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<(Map<String, Any>) -> Unit>()
    private val lock = Any()

    fun addListener(l: (Map<String, Any>) -> Unit) = listeners.add(l)
    fun removeListener(l: (Map<String, Any>) -> Unit) = listeners.remove(l)

    fun add(ctx: Context, type: String, msg: String = "", persist: Boolean = true) {
        val ts = System.currentTimeMillis()
        Log.i(TAG, "[$type] $msg")
        if (persist) {
            synchronized(lock) {
                val p = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                val arr = readArray(p.getString(KEY, null))
                arr.put(JSONObject().put("ts", ts).put("type", type).put("msg", msg))
                val trimmed = JSONArray()
                val start = maxOf(0, arr.length() - MAX)
                for (i in start until arr.length()) trimmed.put(arr.get(i))
                // commit(): tiny write, and it must survive the process dying right after
                p.edit().putString(KEY, trimmed.toString()).commit()
            }
        }
        val event = mapOf<String, Any>("ts" to ts, "type" to type, "msg" to msg)
        main.post { listeners.forEach { it(event) } }
    }

    fun all(ctx: Context): List<Map<String, Any>> = synchronized(lock) {
        val p = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val arr = readArray(p.getString(KEY, null))
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            mapOf("ts" to o.optLong("ts"), "type" to o.optString("type"), "msg" to o.optString("msg"))
        }
    }

    fun clear(ctx: Context) = synchronized(lock) {
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().remove(KEY).apply()
    }

    private fun readArray(s: String?): JSONArray =
        try { if (s == null) JSONArray() else JSONArray(s) } catch (_: Exception) { JSONArray() }
}
