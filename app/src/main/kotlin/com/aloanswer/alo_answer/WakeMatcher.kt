package com.aloanswer.alo_answer

import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * Decides from Vosk's JSON results whether the wake word was said.
 *
 *  - Final results ({"result":[{"word":"alo","conf":0.93,...}], "text":"alo"}):
 *    a wake word with conf >= [minConf] triggers.
 *  - Partial results ({"partial":"alo"}) have no confidence, so the wake word
 *    must stay in the hypothesis for [partialsNeeded] updates in a row
 *    (~0.2 s each). This matters during a call, where the ringtone can keep
 *    an utterance "open" and delay the final result.
 *
 * The sensitivity slider (0..1) moves both: higher = lower conf needed and
 * fewer partial updates. Calibration (emulator, TTS clips): “allo” scored
 * conf 0.59–0.61, “a'lo” 1.00, unrelated speech came out as [unk]. The
 * default 0.5 therefore needs conf >= 0.475, leaving a margin.
 */
class WakeMatcher(words: Collection<String>, sensitivity: Float) {
    private val words = words.map { it.lowercase() }.toSet()
    private val s = sensitivity.coerceIn(0f, 1f)

    val minConf: Double = 0.85 - 0.75 * s
    val partialsNeeded: Int = 2 + ((1f - s) * 4).roundToInt()

    private var streak = 0

    private fun hasWord(text: String) = text.split(' ').any { it.lowercase() in words }

    fun onPartial(json: String?): Boolean {
        val text = runCatching { JSONObject(json ?: "").optString("partial") }.getOrDefault("")
        streak = if (hasWord(text)) streak + 1 else 0
        return streak >= partialsNeeded
    }

    fun onFinal(json: String?): Boolean {
        streak = 0
        val o = runCatching { JSONObject(json ?: "") }.getOrNull() ?: return false
        val arr = o.optJSONArray("result") ?: return false
        for (i in 0 until arr.length()) {
            val w = arr.optJSONObject(i) ?: continue
            if (w.optString("word").lowercase() in words && w.optDouble("conf", 0.0) >= minConf) return true
        }
        return false
    }
}
