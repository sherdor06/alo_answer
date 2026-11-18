package com.aloanswer.alo_answer

import android.annotation.SuppressLint
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
 *
 * Messages are stored as string-resource names + arguments ([Msg]), so the log
 * is shown in whatever language the app uses now, not the one of the moment.
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

    /** One localizable piece of an event message: a string resource and its arguments. */
    class Msg(val res: Int, vararg val args: Any)

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<(Map<String, Any>) -> Unit>()
    private val lock = Any()

    fun addListener(l: (Map<String, Any>) -> Unit) = listeners.add(l)
    fun removeListener(l: (Map<String, Any>) -> Unit) = listeners.remove(l)

    /** Event whose text comes from string resources (shown in the current language later too). */
    fun add(ctx: Context, type: String, vararg parts: Msg, persist: Boolean = true) {
        val stored = JSONArray()
        for (p in parts) {
            stored.put(
                JSONObject()
                    .put("res", ctx.resources.getResourceEntryName(p.res))
                    .put("args", JSONArray(p.args.map { it.toString() }))
            )
        }
        val text = parts.joinToString(" ") { Settings.str(ctx, it.res, *it.args) }
        record(ctx, type, text, stored, persist)
    }

    /** Event with text that has no translation (an exception message, a reason from Android). */
    fun addRaw(ctx: Context, type: String, msg: String, persist: Boolean = true) =
        record(ctx, type, msg, null, persist)

    private fun record(ctx: Context, type: String, msg: String, parts: JSONArray?, persist: Boolean) {
        val ts = System.currentTimeMillis()
        Log.i(TAG, "[$type] $msg")
        if (persist) {
            synchronized(lock) {
                val p = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                val arr = readArray(p.getString(KEY, null))
                val o = JSONObject().put("ts", ts).put("type", type).put("msg", msg)
                if (parts != null && parts.length() > 0) o.put("parts", parts)
                arr.put(o)
                val trimmed = JSONArray()
                val start = maxOf(0, arr.length() - MAX)
                for (i in start until arr.length()) trimmed.put(arr.get(i))
                // commit(): tiny write, and it must survive the process dying right after
                p.edit().putString(KEY, trimmed.toString()).commit()
            }
        }
        val event = buildMap<String, Any> {
            put("ts", ts)
            put("type", type)
            put("msg", msg)
            if (parts != null && parts.length() > 0) put("parts", parts)
        }
        main.post { listeners.forEach { it(event) } }
    }

    fun all(ctx: Context): List<Map<String, Any>> = synchronized(lock) {
        val p = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val arr = readArray(p.getString(KEY, null))
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            buildMap {
                put("ts", o.optLong("ts"))
                put("type", o.optString("type"))
                put("msg", o.optString("msg"))
                o.optJSONArray("parts")?.let { parts -> put("parts", parts) }
            }
        }
    }

    /**
     * The event's message in [ctx]'s language. Falls back to the text stored at the
     * time (older entries, or a string that no longer exists).
     */
    @SuppressLint("DiscouragedApi") // resource names are stored because ids change between builds
    fun message(ctx: Context, event: Map<String, Any>): String {
        val fallback = event["msg"] as? String ?: ""
        val parts = event["parts"] as? JSONArray ?: return fallback
        val out = ArrayList<String>(parts.length())
        for (i in 0 until parts.length()) {
            val p = parts.optJSONObject(i) ?: return fallback
            val id = ctx.resources.getIdentifier(p.optString("res"), "string", ctx.packageName)
            if (id == 0) return fallback
            val a = p.optJSONArray("args")
            val args = Array<Any>(a?.length() ?: 0) { a!!.optString(it) }
            out += try {
                ctx.getString(id, *args)
            } catch (_: Exception) {
                return fallback
            }
        }
        return out.joinToString(" ")
    }

    fun clear(ctx: Context) = synchronized(lock) {
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().remove(KEY).apply()
    }

    private fun readArray(s: String?): JSONArray =
        try { if (s == null) JSONArray() else JSONArray(s) } catch (_: Exception) { JSONArray() }
}
