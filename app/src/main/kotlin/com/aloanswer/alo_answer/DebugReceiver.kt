package com.aloanswer.alo_answer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import com.aloanswer.alo_answer.dialer.CallManager
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
 *   # raw 16 kHz mono 16-bit little-endian PCM at files/<name> (push with run-as)
 *   adb shell am broadcast -n $R -a $P.debug.RECOGNIZE_PCM --es file test.pcm
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
        private const val TAG = "AloAnswer"
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
                val file = File(app.filesDir, intent.getStringExtra("file") ?: "test.pcm")
                val pending = goAsync()
                Thread {
                    try {
                        recognizeFile(app, file)
                    } catch (e: Exception) {
                        Log.e(TAG, "[debug] file test failed", e)
                    } finally {
                        pending.finish()
                    }
                }.start()
            }
        }
    }

    /** Same model, grammar and WakeMatcher as a real call, fed from a file instead of the mic. */
    private fun recognizeFile(ctx: Context, f: File) {
        val t0 = SystemClock.elapsedRealtime()
        ModelStore.open(ctx).use { opened ->
            Log.i(
                TAG, "[debug] model ready in ${SystemClock.elapsedRealtime() - t0} ms; " +
                    "grammar=${opened.grammar} words=${opened.words}"
            )
            val matcher = WakeMatcher(opened.words, Settings.sensitivity(ctx))
            var detected = false
            f.inputStream().use { input ->
                val buf = ByteArray(6400) // 0.2 s of 16 kHz 16-bit audio, like SpeechService
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    if (opened.recognizer.acceptWaveForm(buf, n)) {
                        val r = opened.recognizer.result
                        Log.i(TAG, "[debug] result: $r")
                        detected = matcher.onFinal(r) || detected
                    } else {
                        val p = opened.recognizer.partialResult
                        Log.i(TAG, "[debug] partial: $p")
                        detected = matcher.onPartial(p) || detected
                    }
                }
            }
            val fin = opened.recognizer.finalResult
            Log.i(TAG, "[debug] final: $fin")
            detected = matcher.onFinal(fin) || detected
            Log.i(
                TAG, "[debug] ${f.name}: wake word ${if (detected) "DETECTED" else "not detected"} " +
                    "(minConf=${"%.2f".format(matcher.minConf)}, partialsNeeded=${matcher.partialsNeeded})"
            )
        }
    }
}
