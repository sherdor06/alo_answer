package com.aloanswer.alo_answer

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import org.json.JSONArray
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File

/**
 * The offline Vosk Uzbek model (vosk-model-small-uz-0.22, Apache 2.0) ships
 * inside the APK as assets/model-uz and is copied once to internal storage,
 * because Vosk loads a model from a real directory (~107 MB unpacked). It is
 * copied again after every app update, in case the model changed.
 */
object ModelStore {
    const val ASSET_DIR = "model-uz"
    const val SAMPLE_RATE = 16000f

    /**
     * Spoken forms of the wake word. Checked against this model's vocabulary
     * (the symbol table inside graph/Gr.fst): "allo" (the phone greeting) and
     * "a'lo" are in it, plain "alo" is not. Both sound like “alo”.
     */
    val WAKE_WORDS = listOf("allo", "a'lo")

    private const val STAMP = ".copied-stamp"
    private val lock = Any()

    fun assetPresent(ctx: Context): Boolean = try {
        ctx.assets.list(ASSET_DIR)?.isNotEmpty() == true
    } catch (_: Exception) {
        false
    }

    fun dir(ctx: Context) = File(ctx.filesDir, ASSET_DIR)

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

    fun isReady(ctx: Context): Boolean {
        val f = File(dir(ctx), STAMP)
        return f.exists() && f.readText() == stamp(ctx)
    }

    /** Blocking (the first copy takes a few seconds). Call off the main thread. */
    fun ensureReady(ctx: Context): File = synchronized(lock) {
        val target = dir(ctx)
        if (isReady(ctx)) return target
        target.deleteRecursively()
        copyAssetDir(ctx, ASSET_DIR, target)
        File(target, STAMP).writeText(stamp(ctx))
        target
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

    class Opened(
        val model: Model,
        val recognizer: Recognizer,
        val words: List<String>,
        val grammar: Boolean,
    ) : AutoCloseable {
        override fun close() {
            try { recognizer.close() } catch (_: Exception) {}
            try { model.close() } catch (_: Exception) {}
        }
    }

    /**
     * Loads the model and a recognizer limited to the wake word (plus "[unk]"
     * for everything else) when the model supports it. Blocking: ~1–2 s.
     */
    fun open(ctx: Context): Opened {
        val dir = ensureReady(ctx)
        val grammar = supportsGrammar(dir)
        val model = Model(dir.absolutePath)
        try {
            val recognizer = if (grammar) {
                Recognizer(model, SAMPLE_RATE, JSONArray(WAKE_WORDS + "[unk]").toString())
            } else {
                Recognizer(model, SAMPLE_RATE)
            }
            recognizer.setWords(true) // per-word "conf" in final results
            return Opened(model, recognizer, WAKE_WORDS, grammar)
        } catch (e: Exception) {
            model.close()
            throw e
        }
    }

    /** For the Setup check in the UI. Blocking (may copy the model the first time). */
    fun info(ctx: Context): Map<String, Any> {
        if (!assetPresent(ctx)) return mapOf("modelAsset" to false, "modelReady" to false)
        val dir = ensureReady(ctx)
        return mapOf(
            "modelAsset" to true,
            "modelReady" to true,
            "grammar" to supportsGrammar(dir),
            "wakeWords" to WAKE_WORDS,
        )
    }
}
