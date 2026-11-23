package com.aloanswer.alo_answer

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import org.json.JSONArray
import org.vosk.Model
import org.vosk.Recognizer
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.Executors

/**
 * The offline Vosk models (Apache 2.0, alphacephei.com), one per answer-word language:
 * vosk-model-small-uz-0.22, vosk-model-small-ru-0.22 and vosk-model-small-en-us-0.15. They
 * ship inside the APK as assets/model-uz, model-ru and model-en. Vosk loads a model from a real
 * directory, so a model is copied to internal storage (70–100 MB each) once a ticked word needs
 * it, again after every app update, and deleted when no ticked word needs it any more.
 */
object ModelStore {
    const val SAMPLE_RATE = 16000f

    private const val STAMP = ".copied-stamp"
    private val lock = Any()

    fun assetPresent(ctx: Context, lang: SpeechLang): Boolean = try {
        ctx.assets.list(lang.assetDir)?.isNotEmpty() == true
    } catch (_: Exception) {
        false
    }

    fun dir(ctx: Context, lang: SpeechLang) = File(ctx.filesDir, lang.assetDir)

    private fun stamp(ctx: Context): String {
        val pm = ctx.packageManager
        val info = if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageInfo(ctx.packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(ctx.packageName, 0)
        }
        return info.lastUpdateTime.toString()
    }

    fun isReady(ctx: Context, lang: SpeechLang): Boolean {
        val f = File(dir(ctx, lang), STAMP)
        return f.exists() && f.readText() == stamp(ctx)
    }

    /** Blocking (the first copy takes a few seconds). Call off the main thread. */
    fun ensureReady(ctx: Context, lang: SpeechLang): File = synchronized(lock) {
        val target = dir(ctx, lang)
        if (isReady(ctx, lang)) return target
        target.deleteRecursively()
        copyAssetDir(ctx, lang.assetDir, target)
        File(target, STAMP).writeText(stamp(ctx))
        target
    }

    /**
     * Copies the models of [langs] now, so a call doesn't wait for it, and deletes the copies of
     * the other languages to free their space. Blocking.
     */
    fun prepare(ctx: Context, langs: Set<SpeechLang>) {
        for (lang in SpeechLang.values()) {
            if (lang in langs) {
                if (assetPresent(ctx, lang)) ensureReady(ctx, lang)
            } else {
                synchronized(lock) { dir(ctx, lang).deleteRecursively() }
            }
        }
    }

    private val background = Executors.newSingleThreadExecutor()

    /** [prepare] for the languages of the ticked words, on a background thread. */
    fun prepareTicked(ctx: Context) {
        val app = ctx.applicationContext
        background.execute {
            try {
                prepare(app, WakeWords.languages(WakeWords.selected(app)))
            } catch (_: Exception) {
            }
        }
    }

    private fun copyAssetDir(ctx: Context, assetPath: String, out: File) {
        val children = ctx.assets.list(assetPath) ?: emptyArray()
        if (children.isEmpty()) { // a file
            out.parentFile?.mkdirs()
            ctx.assets.open(assetPath).use { input -> out.outputStream().use { input.copyTo(it) } }
            return
        }
        out.mkdirs()
        for (child in children) copyAssetDir(ctx, "$assetPath/$child", File(out, child))
    }

    /** Small "lookahead" models (graph/Gr.fst + HCLr.fst) accept a runtime word list; static HCLG models don't. */
    fun supportsGrammar(dir: File) =
        File(dir, "graph/Gr.fst").exists() && File(dir, "graph/HCLr.fst").exists()

    /** One loaded model and its recognizer, listening for the [phrases] of its language. */
    class Part(
        val lang: SpeechLang,
        val model: Model,
        val recognizer: Recognizer,
        val phrases: List<WakeWords.Phrase>,
    ) : AutoCloseable {
        override fun close() {
            try { recognizer.close() } catch (_: Exception) {}
            try { model.close() } catch (_: Exception) {}
        }
    }

    class Opened(val parts: List<Part>, val grammar: Boolean) : AutoCloseable {
        val phrases: List<WakeWords.Phrase> get() = parts.flatMap { it.phrases }
        override fun close() = parts.forEach { it.close() }
    }

    /**
     * Loads a model and a recognizer for each language of [phrases], each limited to its words
     * (plus "[unk]" for everything else) when the model supports it. Blocking: ~1 s per language.
     */
    fun open(ctx: Context, phrases: List<WakeWords.Phrase> = WakeWords.selected(ctx)): Opened {
        val parts = ArrayList<Part>()
        try {
            for ((lang, ps) in phrases.groupBy { it.lang }) {
                val dir = ensureReady(ctx, lang)
                val model = Model(dir.absolutePath)
                try {
                    parts += Part(lang, model, recognizer(model, dir, ps), ps)
                } catch (e: Exception) {
                    model.close()
                    throw e
                }
            }
            return Opened(parts, parts.all { supportsGrammar(dir(ctx, it.lang)) })
        } catch (e: Exception) {
            parts.forEach { it.close() }
            throw e
        }
    }

    /**
     * A recognizer for a loaded [model] (from [dir]) that listens for [phrases], all of one
     * language. Its grammar: the phrases, that language's everyday [WakeWords.fillers] (unless
     * [fillers] is false; a debug comparison) and "[unk]".
     */
    fun recognizer(model: Model, dir: File, phrases: List<WakeWords.Phrase>, fillers: Boolean = true): Recognizer {
        val recognizer = if (supportsGrammar(dir)) {
            val lang = phrases.firstOrNull()?.lang ?: SpeechLang.UZ
            val extra = if (fillers) WakeWords.fillers(lang, phrases) else emptyList()
            Recognizer(model, SAMPLE_RATE, JSONArray(WakeWords.spellings(phrases) + extra + "[unk]").toString())
        } else {
            Recognizer(model, SAMPLE_RATE)
        }
        recognizer.setWords(true) // per-word "conf" in final results
        return recognizer
    }

    /** For the Setup check: the models of the ticked words. Blocking (may copy them). */
    fun info(ctx: Context): Map<String, Any> {
        val selected = WakeWords.selected(ctx)
        val langs = selected.map { it.lang }.distinct()
        val codes = langs.map { it.code }
        if (langs.any { !assetPresent(ctx, it) }) {
            return mapOf("modelAsset" to false, "modelReady" to false, "langs" to codes)
        }
        langs.forEach { ensureReady(ctx, it) }
        return mapOf(
            "modelAsset" to true,
            "modelReady" to true,
            "grammar" to langs.all { supportsGrammar(dir(ctx, it)) },
            "wakeWords" to WakeWords.spellings(selected),
            "langs" to codes,
        )
    }

    /**
     * How the model of [lang] spells each of [words] it knows; a word it doesn't know is left
     * out. Null when that can't be told. A Russian "е" also finds the model's "ё" (еще → ещё).
     *
     * Vosk takes its vocabulary from the output symbols of graph/Gr.fst (small "lookahead"
     * models have no words.txt) or else from graph/words.txt. This reads the APK's copy, so
     * nothing needs to be copied first. Blocking: reads a few MB.
     */
    fun resolveWords(ctx: Context, lang: SpeechLang, words: Collection<String>): Map<String, String>? {
        val want = words.toSet()
        val byFolded = want.associateBy { lang.fold(it) }
        val exact = HashSet<String>()
        val loose = HashMap<String, String>()
        val onWord: (String) -> Unit = { w ->
            if (w in want) exact += w
            else byFolded[lang.fold(w)]?.let { input -> loose.putIfAbsent(input, w) }
        }
        return try {
            val graph = ctx.assets.list("${lang.assetDir}/graph").orEmpty()
            when {
                "Gr.fst" in graph -> ctx.assets.open("${lang.assetDir}/graph/Gr.fst").use { FstWords.scan(it, onWord) }
                "words.txt" in graph -> ctx.assets.open("${lang.assetDir}/graph/words.txt").bufferedReader().useLines { lines ->
                    lines.forEach { onWord(it.substringBefore(' ')) }
                }
                else -> return null
            }
            want.mapNotNull { w -> (if (w in exact) w else loose[w])?.let { w to it } }.toMap()
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * The word list at the start of an OpenFst binary file: the header, then the input and/or
 * output symbol table, little-endian (OpenFst's FstHeader::Write and SymbolTable::Write).
 */
private object FstWords {
    private const val FST_MAGIC = 2125659606
    private const val SYMBOLS_MAGIC = 2125658996
    private const val HAS_ISYMBOLS = 1
    private const val HAS_OSYMBOLS = 2

    /** Calls [onWord] for each output symbol (the input ones when there are no output ones). */
    fun scan(input: InputStream, onWord: (String) -> Unit) {
        val s = DataInputStream(BufferedInputStream(input, 1 shl 16))
        if (s.i32() != FST_MAGIC) throw IOException("not an OpenFst file")
        s.str() // fst type
        s.str() // arc type
        s.i32() // version
        val flags = s.i32()
        repeat(4) { s.i64() } // properties, start state, number of states, number of arcs
        val hasInput = flags and HAS_ISYMBOLS != 0
        val hasOutput = flags and HAS_OSYMBOLS != 0
        if (!hasInput && !hasOutput) throw IOException("no word list")
        if (hasInput && hasOutput) table(s, null)
        table(s, onWord)
    }

    /** Reads one symbol table, passing each symbol to [onWord]; null just skips the table. */
    private fun table(s: DataInputStream, onWord: ((String) -> Unit)?) {
        if (s.i32() != SYMBOLS_MAGIC) throw IOException("bad symbol table")
        s.str() // table name
        s.i64() // available key
        val size = s.i64()
        var i = 0L
        while (i < size) {
            val n = s.i32()
            if (n !in 0..1024) throw IOException("bad symbol length $n")
            if (onWord == null) {
                s.skipFully(n)
            } else {
                val b = ByteArray(n)
                s.readFully(b)
                onWord(String(b, Charsets.UTF_8))
            }
            s.i64() // key
            i++
        }
    }

    private fun DataInputStream.i32() = Integer.reverseBytes(readInt())
    private fun DataInputStream.i64() = java.lang.Long.reverseBytes(readLong())

    private fun DataInputStream.str(): String {
        val n = i32()
        if (n !in 0..4096) throw IOException("bad string length $n")
        val b = ByteArray(n)
        readFully(b)
        return String(b, Charsets.UTF_8)
    }

    private fun DataInputStream.skipFully(n: Int) {
        var left = n
        while (left > 0) {
            val k = skipBytes(left)
            if (k <= 0) throw EOFException()
            left -= k
        }
    }
}
