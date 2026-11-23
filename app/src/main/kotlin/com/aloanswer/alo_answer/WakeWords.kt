package com.aloanswer.alo_answer

import android.content.Context
import org.json.JSONArray
import java.util.Locale

/**
 * The words that answer a call: ready-made ones and the user's own, in Uzbek, Russian or
 * English, any number of them ticked. Each language has its own speech model; a call loads
 * the models of the ticked words' languages.
 *
 * Vosk recognizes only words of its model's vocabulary and silently drops any other word from
 * the grammar. Every ready-made spelling below is in its model's vocabulary; the user's own
 * words are checked when they are added ([ModelStore.resolveWords]).
 */
object WakeWords {

    /**
     * One answer word or phrase. [spellings] are what the recognizer of [lang] listens for, as
     * its model spells them: lowercase, words separated by one space.
     */
    class Phrase(
        val id: String,
        val label: String,
        val spellings: List<String>,
        val custom: Boolean,
        val lang: SpeechLang,
    )

    const val DEFAULT_ID = "alo"
    /** Words in one phrase of the user's own. */
    const val MAX_WORDS = 4
    /** Phrases of the user's own. */
    const val MAX_CUSTOM = 10

    private const val CUSTOM_PREFIX = "c:"
    private const val KEY_SELECTED = "wake_selected"
    private const val KEY_CUSTOM = "wake_custom"

    private class Preset(val lang: SpeechLang, val id: String, val spellings: List<String>, val label: String = spellings[0])

    /**
     * Ready-made phrases, picked with an emulator test (README, "Answer words"): TTS clips in
     * several voices with a ringtone mixed in, against unrelated sentences. Left out because
     * they answered by mistake even with the [fillers]: "gapiring" ("gapirdi"), "hello" and
     * "pick up" were also caught less often; "да" and "yes" are too short.
     *
     * "alo" listens for "allo" and "a'lo", not the model's own "alo": with all three sound-alikes
     * in the grammar the recognizer split its confidence between them, and "alo" clips scored
     * about 0.2 lower (0.60 → 0.40 for a Russian voice).
     */
    private val PRESETS: List<Preset> = listOf(
        Preset(SpeechLang.UZ, DEFAULT_ID, listOf("allo", "a'lo"), label = "alo"),
        Preset(SpeechLang.UZ, "labbay", listOf("labbay")),
        Preset(SpeechLang.UZ, "eshitaman", listOf("eshitaman")),
        Preset(SpeechLang.UZ, "assalomu alaykum", listOf("assalomu alaykum")),
        Preset(SpeechLang.UZ, "marhamat", listOf("marhamat")),
        Preset(SpeechLang.UZ, "javob ber", listOf("javob ber")),
        Preset(SpeechLang.RU, "ru:алло", listOf("алло")),
        Preset(SpeechLang.RU, "ru:слушаю", listOf("слушаю")),
        Preset(SpeechLang.RU, "ru:говорите", listOf("говорите")),
        Preset(SpeechLang.RU, "ru:здравствуйте", listOf("здравствуйте")),
        Preset(SpeechLang.EN, "en:hello", listOf("hello")),
        Preset(SpeechLang.EN, "en:answer", listOf("answer")),
        Preset(SpeechLang.EN, "en:speaking", listOf("speaking")),
    )

    fun presets(): List<Phrase> = PRESETS.map { Phrase(it.id, it.label, it.spellings, custom = false, lang = it.lang) }

    /** Own phrases are stored as "ru:слушаю"; a bare "keling" (2.2 test builds) is Uzbek. */
    private fun parseCustom(entry: String): Pair<SpeechLang, String> {
        val i = entry.indexOf(':')
        val lang = if (i > 0) SpeechLang.of(entry.substring(0, i)) else null
        return if (lang != null) lang to entry.substring(i + 1) else SpeechLang.UZ to entry
    }

    private fun entry(lang: SpeechLang, text: String) = "${lang.code}:$text"

    private fun customId(lang: SpeechLang, text: String) = CUSTOM_PREFIX + entry(lang, text)

    fun custom(ctx: Context): List<Phrase> = readList(ctx, KEY_CUSTOM).map {
        val (lang, text) = parseCustom(it)
        Phrase(customId(lang, text), text, listOf(text), custom = true, lang = lang)
    }

    fun all(ctx: Context): List<Phrase> = presets() + custom(ctx)

    /** The ticked phrases: ready-made ones first, then the user's own. Never empty: falls back to "alo". */
    fun selected(ctx: Context): List<Phrase> {
        val ids = readList(ctx, KEY_SELECTED).map { id ->
            // "c:keling" from 2.2 test builds is "c:uz:keling" now.
            if (id.startsWith(CUSTOM_PREFIX)) {
                val (lang, text) = parseCustom(id.removePrefix(CUSTOM_PREFIX))
                customId(lang, text)
            } else id
        }.toSet().ifEmpty { setOf(DEFAULT_ID) }
        val all = all(ctx)
        return all.filter { it.id in ids }.ifEmpty { all.filter { it.id == DEFAULT_ID } }
    }

    fun setSelected(ctx: Context, ids: Collection<String>) = writeList(ctx, KEY_SELECTED, ids.distinct())

    /** The languages whose models the ticked phrases need. */
    fun languages(phrases: List<Phrase>): Set<SpeechLang> = phrases.mapTo(LinkedHashSet()) { it.lang }

    /** Adds and ticks a phrase of the user's own, as the model of [lang] spells it. */
    fun addCustom(ctx: Context, lang: SpeechLang, phrase: String) {
        val list = readList(ctx, KEY_CUSTOM)
        val e = entry(lang, phrase)
        if (e !in list) writeList(ctx, KEY_CUSTOM, list + e)
        setSelected(ctx, selected(ctx).map { it.id } + customId(lang, phrase))
    }

    /** Removes a phrase of the user's own; "alo" gets ticked if nothing else is left. */
    fun removeCustom(ctx: Context, p: Phrase) {
        if (!p.custom) return
        writeList(ctx, KEY_CUSTOM, readList(ctx, KEY_CUSTOM).filter { parseCustom(it) != p.lang to p.label })
        setSelected(ctx, selected(ctx).map { it.id })
    }

    /**
     * The phrase of [lang] that already stands for [phrase], if any: typing "allo" or "labbay"
     * ticks the ready-made one instead of adding a sound-alike next to it.
     */
    fun find(ctx: Context, lang: SpeechLang, phrase: String): Phrase? = all(ctx).firstOrNull {
        it.lang == lang && (phrase == it.id || phrase == it.label || phrase in it.spellings)
    }

    /** Every grammar entry of [phrases], each once. */
    fun spellings(phrases: List<Phrase>): List<String> = phrases.flatMap { it.spellings }.distinct()

    /**
     * Everyday words that go into a grammar next to the answer words, so that other speech has
     * somewhere to go. With only the answer words and "[unk]", the Russian model took most
     * unrelated sentences for "алло" (22 of 72 test clips, README "Answer words"). They never
     * answer a call: [WakeMatcher] only looks for the answer words. A filler that is a word of
     * a ticked phrase is left out. All of them are in their model's vocabulary.
     */
    fun fillers(lang: SpeechLang, phrases: List<Phrase>): List<String> {
        val used = phrases.flatMap { p -> p.spellings.flatMap { it.split(' ') } }.toSet()
        return FILLERS[lang].orEmpty().filter { it !in used }
    }

    private val FILLERS: Map<SpeechLang, List<String>> = mapOf(
        SpeechLang.UZ to """
            men sen u biz siz ular meni seni uni bizni sizni menga senga unga bizga sizga ularga
            bu shu o'sha nima qanday qayerda qayerga qachon nega kim qaysi qancha ha yo'q mayli
            xo'p yaxshi yomon kerak mumkin kel keldi keladi ket ketdi bor bordi boraman borasan
            yur yuring o'tir o'tiring kiring keling gapir gaplashamiz eshik eshit eshitdim
            ko'rdim bilaman bilmayman qilaman qilyapti qiling telefon qo'ng'iroq uy ish bugun
            ertaga kecha hozir keyin oldin endi yana ham faqat juda ancha bir ikki uch to'rt besh
            o'n ona ota aka uka opa singil do'st o'g'il qiz bola choy non suv bozor
            yaxshimisiz qalaysiz qalaysan xayr allohga shukur rahmat band bandman joyida hammasi
            gap yopib ochiq qoldi bo'ladi bo'lmaydi olib
        """.trimIndent().split(Regex("\\s+")).filter { it.isNotEmpty() },
        SpeechLang.RU to """
            я ты он она оно мы вы они меня тебя его её нас вас их мне тебе ему ей нам вам им
            это этот эта эти тот та те что как где куда когда почему зачем кто какой сколько
            не нет ну вот так там тут здесь туда сюда сейчас потом тогда уже ещё только тоже
            хорошо плохо ладно давай давайте можно нужно надо есть был была были будет быть
            иди иду пошли пойдём говорить сказать сказал скажи слышу слышишь понял знаю знаешь
            думаю хочу могу можешь работа работаю дом домой время день ночь утро вечер завтра
            сегодня вчера неделя звонок звонит звони позвони перезвоню трубка трубку телефон
            номер привет пока спасибо пожалуйста извините конечно может очень всё все один два
            три четыре пять десять мама папа брат сестра друг сын дочь алла слушай говорят
            говори поговорим здравствуй занят дождь радио поёт внимательно дела
        """.trimIndent().split(Regex("\\s+")).filter { it.isNotEmpty() },
        SpeechLang.EN to """
            i you he she it we they me him her us them my your his our their this that these
            those what how where when why who which no not ok okay well so now then here there
            today tomorrow yesterday good bad fine right sorry please thanks thank go going come
            coming call called calling back later soon wait hold on second minute can could will
            would should do does did have has had be is are was were time day night morning
            evening week work home phone line hear see know think want need one two three four
            five ten mom dad brother sister friend yellow hollow hotel answers answered speaker
            speak spoke picking pick up car tree check let loud
        """.trimIndent().split(Regex("\\s+")).filter { it.isNotEmpty() },
    )

    /** The ticked words for a sentence, in the app language: «alo» yoki «слушаю». */
    fun spoken(ctx: Context, phrases: List<Phrase> = selected(ctx)): String {
        val l = Settings.localized(ctx)
        val q = phrases.map { l.getString(R.string.wake_quote, it.label) }
        return when (q.size) {
            0 -> ""
            1 -> q[0]
            else -> l.getString(R.string.wake_or, q.dropLast(1).joinToString(", "), q.last())
        }
    }

    /** The ticked words for a settings row: "Alo, Слушаю". */
    fun summary(ctx: Context): String = selected(ctx).joinToString(", ") { capitalized(it.label) }

    fun capitalized(s: String) = s.replaceFirstChar { it.titlecase(Locale.ROOT) }

    /** The language's name in the app language: "Ruscha". */
    fun languageName(ctx: Context, lang: SpeechLang): String = Settings.str(
        ctx,
        when (lang) {
            SpeechLang.UZ -> R.string.wake_lang_uz
            SpeechLang.RU -> R.string.wake_lang_ru
            SpeechLang.EN -> R.string.wake_lang_en
        }
    )

    private val CYRILLIC = mapOf(
        'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'ғ' to "g'", 'д' to "d", 'ё' to "yo",
        'ж' to "j", 'з' to "z", 'и' to "i", 'й' to "y", 'к' to "k", 'қ' to "q", 'л' to "l",
        'м' to "m", 'н' to "n", 'о' to "o", 'п' to "p", 'р' to "r", 'с' to "s", 'т' to "t",
        'у' to "u", 'ў' to "o'", 'ф' to "f", 'х' to "x", 'ҳ' to "h", 'ц' to "s", 'ч' to "ch",
        'ш' to "sh", 'щ' to "sh", 'ъ' to "'", 'ь' to "", 'ы' to "i", 'э' to "e", 'ю' to "yu",
        'я' to "ya",
    )
    private const val CYRILLIC_VOWELS = "аеёиоуўэюяыъь"

    /** Marks people type for an apostrophe (oʻ, gʻ, taʼlim, don’t); the models spell them all '. */
    private const val APOSTROPHES = "'ʻʼ‘’`´"

    /**
     * What the user typed, spelled the way the model of [lang] spells words: lowercase, other
     * punctuation as a space. Uzbek Cyrillic becomes Latin and any apostrophe mark becomes ';
     * Russian keeps its letters (the vocabulary check also finds ё for е); English keeps '.
     */
    fun normalize(input: String, lang: SpeechLang): String {
        val s = input.lowercase(Locale.ROOT)
        val out = StringBuilder(s.length + 4)
        for ((i, ch) in s.withIndex()) {
            val prev = s.getOrNull(i - 1)
            when {
                // "ye" at the start of a word and after a vowel, as in Uzbek Latin (ер → yer).
                lang == SpeechLang.UZ && ch == 'е' ->
                    out.append(if (prev == null || !prev.isLetter() || prev in CYRILLIC_VOWELS) "ye" else "e")
                lang == SpeechLang.UZ && ch in CYRILLIC -> out.append(CYRILLIC.getValue(ch))
                ch in APOSTROPHES -> out.append(if (lang == SpeechLang.RU) ' ' else '\'')
                ch.isLetter() -> out.append(ch)
                else -> out.append(' ')
            }
        }
        return out.split(' ').map { it.trim('\'') }.filter { it.isNotEmpty() }.joinToString(" ")
    }

    /** One syllable or less ("ha", "да", "yes"): other sounds are easily taken for it by mistake. */
    fun isShort(phrase: String, lang: SpeechLang): Boolean {
        val vowels = when (lang) {
            SpeechLang.UZ -> "aeiou"
            SpeechLang.RU -> "аеёиоуыэюя"
            SpeechLang.EN -> "aeiouy"
        }
        return phrase.count { it in vowels } <= 1
    }

    private fun readList(ctx: Context, key: String): List<String> {
        val raw = Settings.prefs(ctx).getString(key, null) ?: return emptyList()
        return try {
            val a = JSONArray(raw)
            (0 until a.length()).map { a.getString(it) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun writeList(ctx: Context, key: String, list: List<String>) {
        Settings.prefs(ctx).edit().putString(key, JSONArray(list).toString()).apply()
    }
}
