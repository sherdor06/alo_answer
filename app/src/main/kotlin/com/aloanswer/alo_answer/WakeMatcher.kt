package com.aloanswer.alo_answer

import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * Decides from Vosk's JSON results whether an answer word or phrase was said.
 *
 *  - Final results ({"result":[{"word":"alo","conf":0.93,...}], "text":"alo"}):
 *    the phrase's words in a row, with average conf >= [minConf], trigger.
 *  - Partial results ({"partial":"alo"}) have no confidence, so a phrase
 *    must stay in the hypothesis for [partialsNeeded] updates in a row
 *    (~0.2 s each). This matters during a call, where the ringtone can keep
 *    an utterance "open" and delay the final result.
 *
 * The sensitivity slider (0..1) moves both: higher = lower conf needed and
 * fewer partial updates. Calibration (emulator, TTS clips): “allo” scored
 * conf 0.59–0.61, “a'lo” 1.00, unrelated speech came out as [unk]. The
 * default 0.5 therefore needs conf >= 0.475, leaving a margin.
 */
class WakeMatcher(phrases: List<WakeWords.Phrase>, sensitivity: Float) {

    /** What triggered: the phrase, the words as recognized, and their confidence (null from a partial result). */
    class Hit(val phrase: WakeWords.Phrase, val heard: String, val conf: Double?)

    private class Target(val phrase: WakeWords.Phrase, val words: List<String>)

    private val targets = phrases.flatMap { p ->
        p.spellings.map { Target(p, words(it)) }.filter { it.words.isNotEmpty() }
    }
    private val s = sensitivity.coerceIn(0f, 1f)

    val minConf: Double = 0.85 - 0.75 * s
    val partialsNeeded: Int = 2 + ((1f - s) * 4).roundToInt()

    private var streak = 0

    private fun words(text: String) = text.lowercase().split(' ').filter { it.isNotEmpty() }

    /** Start indexes where [t]'s words appear in a row in [tokens]. */
    private fun positions(t: Target, tokens: List<String>) =
        (0..tokens.size - t.words.size).filter { i -> t.words.indices.all { tokens[i + it] == t.words[it] } }

    fun onPartial(json: String?): Hit? {
        val text = runCatching { JSONObject(json ?: "").optString("partial") }.getOrDefault("")
        val tokens = words(text)
        val t = targets.firstOrNull { positions(it, tokens).isNotEmpty() }
        streak = if (t != null) streak + 1 else 0
        return if (t != null && streak >= partialsNeeded) Hit(t.phrase, t.words.joinToString(" "), null) else null
    }

    fun onFinal(json: String?): Hit? {
        streak = 0
        val o = runCatching { JSONObject(json ?: "") }.getOrNull() ?: return null
        val arr = o.optJSONArray("result") ?: return null
        val items = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
        val tokens = items.map { it.optString("word").lowercase() }
        for (t in targets) {
            for (i in positions(t, tokens)) {
                val conf = t.words.indices.map { items[i + it].optDouble("conf", 0.0) }.average()
                if (conf >= minConf) return Hit(t.phrase, t.words.joinToString(" "), conf)
            }
        }
        return null
    }
}
