package com.aloanswer.alo_answer

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import org.vosk.Recognizer
import java.io.IOException
import kotlin.math.roundToInt

/**
 * One microphone for several recognizers (one per answer-word language). It records the way
 * Vosk's SpeechService does for a single recognizer: VOICE_RECOGNITION source, 16 kHz mono
 * 16-bit, 0.2 s reads, and an AudioRecord buffer of twice that. Every read goes to each
 * recognizer in turn; results are delivered on the main thread, tagged with the recognizer's
 * index. Creating it opens the microphone, so create it only once RECORD_AUDIO is granted.
 */
class MicListener(private val recognizers: List<Recognizer>, sampleRate: Float) {

    interface Callback {
        fun onPartial(index: Int, json: String)
        fun onResult(index: Int, json: String)
        fun onError(e: Exception)
    }

    private val rate = sampleRate.roundToInt()
    /** Samples per read: 0.2 s, as in SpeechService. */
    private val chunk = (rate * 0.2f).roundToInt()
    private val main = Handler(Looper.getMainLooper())
    @Volatile
    private var thread: Thread? = null

    @SuppressLint("MissingPermission") // WakeWordService checks RECORD_AUDIO before creating this
    private val recorder = AudioRecord(
        MediaRecorder.AudioSource.VOICE_RECOGNITION, rate,
        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, chunk * 2
    ).also {
        if (it.state == AudioRecord.STATE_UNINITIALIZED) {
            it.release()
            throw IOException("Failed to initialize recorder. Microphone might be already in use.")
        }
    }

    /** Starts recording on its own thread. False if it was already started. */
    fun start(callback: Callback): Boolean {
        if (thread != null) return false
        thread = Thread({ loop(callback) }, "AloAnswerMic").apply { start() }
        return true
    }

    private fun loop(callback: Callback) {
        try {
            recorder.startRecording()
            if (recorder.recordingState == AudioRecord.RECORDSTATE_STOPPED) {
                throw IOException("Failed to start recording. Microphone might be already in use.")
            }
            val buffer = ShortArray(chunk)
            while (!Thread.currentThread().isInterrupted) {
                val n = recorder.read(buffer, 0, buffer.size)
                if (n < 0) throw IOException("AudioRecord.read() returned $n")
                for ((i, r) in recognizers.withIndex()) {
                    if (r.acceptWaveForm(buffer, n)) {
                        val json = r.result
                        main.post { callback.onResult(i, json) }
                    } else {
                        val json = r.partialResult
                        main.post { callback.onPartial(i, json) }
                    }
                }
            }
        } catch (e: Exception) {
            main.post { callback.onError(e) }
        } finally {
            try {
                recorder.stop()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Stops recording and frees the microphone. Blocks until the recording thread has ended
     * (at most one 0.2 s read), so the recognizers can be closed right after.
     */
    fun shutdown() {
        val t = thread
        thread = null
        if (t != null) {
            t.interrupt()
            try {
                t.join()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        try {
            recorder.release()
        } catch (_: Exception) {
        }
    }
}
