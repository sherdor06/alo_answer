package com.aloanswer.alo_answer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import com.aloanswer.alo_answer.dialer.CallManager
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File

/**
 * DEBUG BUILDS ONLY — test hooks for an emulator or a phone on adb.
 *
 * Declared only in src/debug/AndroidManifest.xml (so it does not exist in a
 * release build), protected by android.permission.DUMP (adb shell has it,
 * normal apps can't get it), and it also refuses to act unless the app is
 * debuggable.
 *
 *   P=com.aloanswer.alo_answer; R=$P/.DebugReceiver
 *   # stand-in detector on/off (no Vosk, mic not opened)
 *   adb shell am broadcast -n $R -a $P.debug.FAKE_DETECTOR --ez on true
 *   # master switch on/off
 *   adb shell am broadcast -n $R -a $P.debug.ENABLE --ez on true
 *   # "say alo" while listening (during a ringing call or in Test listening)
 *   adb shell am broadcast -n $R -a $P.debug.SAY_ALO
 *   # run the real recognizer + wake-word logic over a recording:
 *   # raw 16 kHz mono 16-bit little-endian PCM at files/<name> (push with run-as);
 *   # optional "words": answer words to use instead of the ticked ones, | between them
 *   # (a ready-made id such as labbay, or any phrase; "ru:" / "en:" in front for those languages)
 *   adb shell am broadcast -n $R -a $P.debug.RECOGNIZE_PCM --es file test.pcm --es words "alo|ru:слушаю"
 *   # check words against a model's vocabulary (as "Add a word" does; lang uz, ru or en)
 *   adb shell am broadcast -n $R -a $P.debug.CHECK_WORDS --es lang ru --es words "слушаю|еще"
 *   # pretend Bluetooth headset in the call screen's audio picker (emulators have none)
 *   adb shell am broadcast -n $R -a $P.debug.FAKE_BLUETOOTH --ez on true
 *   adb logcat -s AloAnswer
 */
class DebugReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_FAKE_DETECTOR = "com.aloanswer.alo_answer.debug.FAKE_DETECTOR"
        const val ACTION_SAY_ALO = "com.aloanswer.alo_answer.debug.SAY_ALO"
        const val ACTION_ENABLE = "com.aloanswer.alo_answer.debug.ENABLE"
        const val ACTION_RECOGNIZE_PCM = "com.aloanswer.alo_answer.debug.RECOGNIZE_PCM"
        const val ACTION_FAKE_BLUETOOTH = "com.aloanswer.alo_answer.debug.FAKE_BLUETOOTH"
        const val ACTION_CHECK_WORDS = "com.aloanswer.alo_answer.debug.CHECK_WORDS"
        private const val TAG = "AloAnswer"
    }

    /**
     * "alo|ru:слушаю|en:pick up" → phrases: ready-made ones by id or text, anything else as a
     * phrase of its own; "ru:" / "en:" picks the language (Uzbek without one).
     */
    private fun phrases(words: String?): List<WakeWords.Phrase>? {
        val items = words?.split('|')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        if (items.isEmpty()) return null
        val presets = WakeWords.presets()
        return items.map { item ->
            val i = item.indexOf(':')
            val lang = (if (i > 0) SpeechLang.of(item.substring(0, i)) else null) ?: SpeechLang.UZ
            val text = if (i > 0 && SpeechLang.of(item.substring(0, i)) != null) item.substring(i + 1) else item
            presets.firstOrNull { it.id == item || (it.lang == lang && (it.label == text || text in it.spellings)) }
                ?: WakeWords.Phrase("c:${lang.code}:$text", text, listOf(text), custom = true, lang = lang)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (!Settings.isDebuggable(context)) return
        when (intent.action) {
            ACTION_FAKE_DETECTOR -> {
                val on = intent.getBooleanExtra("on", true)
                Settings.setDebugFakeDetector(context, on)
                Log.i(TAG, "[debug] simulated detector ${if (on) "ON" else "OFF"}")
            }
            ACTION_ENABLE -> {
                // The UI keeps the master switch locked until setup is complete.
                val on = intent.getBooleanExtra("on", true)
                Settings.save(context, mapOf(Settings.KEY_ENABLED to on))
                Log.i(TAG, "[debug] master switch ${if (on) "ON" else "OFF"}")
            }
            ACTION_FAKE_BLUETOOTH -> {
                val on = intent.getBooleanExtra("on", true)
                Settings.setDebugFakeBluetooth(context, on)
                // Applies to the current call too.
                if (CallManager.calls.isNotEmpty()) CallManager.setDemoBluetooth(on)
                Log.i(TAG, "[debug] demo Bluetooth headset ${if (on) "ON" else "OFF"}")
            }
            ACTION_SAY_ALO -> {
                val ok = WakeWordService.instance?.debugSimulateWake() ?: false
                Log.i(TAG, if (ok) "[debug] simulated “alo”" else "[debug] SAY_ALO ignored: nothing is listening")
            }
            ACTION_RECOGNIZE_PCM -> {
                val app = context.applicationContext
                // One file, or several with | between them (the model is loaded once).
                val files = (intent.getStringExtra("file") ?: "test.pcm").split('|')
                    .filter { it.isNotBlank() }.map { File(app.filesDir, it.trim()) }
                val phrases = phrases(intent.getStringExtra("words")) ?: WakeWords.selected(app)
                // Optional "--ef sensitivity 0.5" instead of the saved one.
                val sensitivity = intent.getFloatExtra("sensitivity", -1f).takeIf { it in 0f..1f } ?: Settings.sensitivity(app)
                // Optional "--ez fillers false": grammar without the everyday filler words.
                val fillers = intent.getBooleanExtra("fillers", true)
                background {
                    try {
                        recognizeFiles(app, files, phrases, sensitivity, fillers)
                    } catch (e: Exception) {
                        Log.e(TAG, "[debug] file test failed", e)
                    }
                    Log.i(TAG, "[debug] batch done")
                }
            }
            ACTION_CHECK_WORDS -> {
                val app = context.applicationContext
                val lang = SpeechLang.of(intent.getStringExtra("lang")) ?: SpeechLang.UZ
                val words = intent.getStringExtra("words").orEmpty().split('|')
                    .map { WakeWords.normalize(it, lang) }.filter { it.isNotEmpty() }
                    .flatMap { it.split(' ') }
                background {
                    val t0 = SystemClock.elapsedRealtime()
                    val known = ModelStore.resolveWords(app, lang, words)
                    val unknown = known?.let { k -> words.filter { it !in k } }
                    Log.i(
                        TAG, "[debug] check ${lang.code} $words → known=$known unknown=$unknown " +
                            "in ${SystemClock.elapsedRealtime() - t0} ms"
                    )
                }
            }
        }
    }

    /** Runs [block] on a thread while the broadcast stays alive (goAsync). */
    private fun background(block: () -> Unit) {
        val pending = goAsync()
        Thread {
            try {
                block()
            } finally {
                pending.finish()
            }
        }.start()
    }

    /**
     * Same models, grammars and WakeMatchers as a real call (one per language of [phrases], each
     * fed every chunk, as MicListener does), but fed from files instead of the mic.
     */
    private fun recognizeFiles(
        ctx: Context, files: List<File>, phrases: List<WakeWords.Phrase>, sensitivity: Float, fillers: Boolean,
    ) {
        val t0 = SystemClock.elapsedRealtime()
        val byLang = phrases.groupBy { it.lang }
        val models = LinkedHashMap<SpeechLang, Pair<File, Model>>()
        try {
            for (lang in byLang.keys) {
                val dir = ModelStore.ensureReady(ctx, lang)
                models[lang] = dir to Model(dir.absolutePath)
            }
            Log.i(
                TAG, "[debug] models ${models.keys.map { it.code }} ready in ${SystemClock.elapsedRealtime() - t0} ms; " +
                    "words=${byLang.mapValues { WakeWords.spellings(it.value) }}"
            )
            for (f in files) {
                val recognizers = models.map { (lang, m) -> ModelStore.recognizer(m.second, m.first, byLang.getValue(lang), fillers) }
                try {
                    recognize(f, recognizers, byLang.values.toList(), sensitivity)
                } finally {
                    recognizers.forEach { it.close() }
                }
            }
        } finally {
            models.values.forEach { it.second.close() }
        }
    }

    private fun recognize(f: File, recognizers: List<Recognizer>, phraseSets: List<List<WakeWords.Phrase>>, sensitivity: Float) {
        val matchers = phraseSets.map { WakeMatcher(it, sensitivity) }
        var hit: WakeMatcher.Hit? = null
        f.inputStream().use { input ->
            val buf = ByteArray(6400) // 0.2 s of 16 kHz 16-bit audio, as MicListener reads it
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                for ((i, r) in recognizers.withIndex()) {
                    hit = if (r.acceptWaveForm(buf, n)) {
                        val json = r.result
                        Log.i(TAG, "[debug] result[$i]: $json")
                        matchers[i].onFinal(json) ?: hit
                    } else {
                        matchers[i].onPartial(r.partialResult) ?: hit
                    }
                }
            }
        }
        for ((i, r) in recognizers.withIndex()) {
            val json = r.finalResult
            Log.i(TAG, "[debug] final[$i]: $json")
            hit = matchers[i].onFinal(json) ?: hit
        }
        val verdict = hit?.let { "DETECTED ${it.phrase.id} heard=${it.heard} conf=${it.conf?.let { c -> "%.2f".format(c) } ?: "partial"}" }
            ?: "not detected"
        Log.i(
            TAG, "[debug] ${f.name}: wake word $verdict " +
                "(minConf=${"%.2f".format(matchers[0].minConf)}, partialsNeeded=${matchers[0].partialsNeeded})"
        )
    }
}
