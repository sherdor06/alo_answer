package com.aloanswer.alo_answer

import android.app.LocaleManager
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * App settings in one SharedPreferences file, written by the home screen and
 * read by the call receiver and services at any time (no screen needed).
 */
object Settings {
    private const val FILE = "alo_settings"

    const val KEY_ENABLED = "enabled"
    const val KEY_SENSITIVITY = "sensitivity"
    const val KEY_LAST_CALL_STATE = "last_call_state"
    const val KEY_LAST_CALL_STATE_AT = "last_call_state_at"
    const val KEY_INCOMING_ACTIVE = "incoming_active"
    const val KEY_LAST_SPEAKER = "last_speaker_result"
    const val KEY_ASKED_PREFIX = "asked_perm_"
    /**
     * "uz", "ru", "en", or "" = follow the phone's language. Android 13+ keeps the
     * choice itself (per-app language, also in the phone's Settings), so every
     * screen, notification and service follows it at once; this key mirrors it and
     * is the only store on Android 8–12.
     */
    const val KEY_LANGUAGE = "language"
    /** Android 13+: the old in-app choice was handed to the system once (see [migrateLanguage]). */
    private const val KEY_LANGUAGE_SYNCED = "language_synced"
    val LANGUAGES = listOf("", "uz", "ru", "en")

    const val DEFAULT_SENSITIVITY = 0.5f

    fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun isEnabled(ctx: Context) = prefs(ctx).getBoolean(KEY_ENABLED, false)
    fun sensitivity(ctx: Context) =
        prefs(ctx).getFloat(KEY_SENSITIVITY, DEFAULT_SENSITIVITY).coerceIn(0f, 1f)

    /** The app language: "uz", "ru", "en", or "" when it follows the phone. */
    fun language(ctx: Context): String {
        if (Build.VERSION.SDK_INT >= 33) {
            val code = try {
                ctx.getSystemService(LocaleManager::class.java)?.applicationLocales
                    ?.takeIf { !it.isEmpty }?.get(0)?.language
            } catch (_: Exception) {
                null
            }
            return code?.takeIf { it in LANGUAGES }.orEmpty()
        }
        return (prefs(ctx).getString(KEY_LANGUAGE, "") ?: "").takeIf { it in LANGUAGES }.orEmpty()
    }

    /**
     * Changes the language of the whole app. Android 13+ then recreates every open
     * screen by itself; on older Android each screen checks in onResume.
     */
    fun setLanguage(ctx: Context, code: String) {
        if (code !in LANGUAGES) return
        prefs(ctx).edit().putString(KEY_LANGUAGE, code).putBoolean(KEY_LANGUAGE_SYNCED, true).commit()
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                ctx.getSystemService(LocaleManager::class.java)?.applicationLocales =
                    if (code.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(code)
            } catch (_: Exception) {
            }
        }
    }

    /** Android 13+, once: a language chosen in an older version becomes the system per-app language. */
    fun migrateLanguage(ctx: Context) {
        if (Build.VERSION.SDK_INT < 33) return
        val p = prefs(ctx)
        if (p.getBoolean(KEY_LANGUAGE_SYNCED, false)) return
        p.edit().putBoolean(KEY_LANGUAGE_SYNCED, true).apply()
        val stored = p.getString(KEY_LANGUAGE, "") ?: ""
        if (stored.isEmpty() || stored !in LANGUAGES) return
        try {
            val lm = ctx.getSystemService(LocaleManager::class.java) ?: return
            if (lm.applicationLocales.isEmpty) lm.applicationLocales = LocaleList.forLanguageTags(stored)
        } catch (_: Exception) {
        }
    }

    /** The phone's own language (what "Phone language" means). */
    fun systemLocale(ctx: Context): Locale {
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                ctx.getSystemService(LocaleManager::class.java)?.systemLocales
                    ?.takeIf { !it.isEmpty }?.get(0)?.let { return it }
            } catch (_: Exception) {
            }
        }
        return Resources.getSystem().configuration.locales[0]
    }

    /** Context whose resources use the in-app language (or the phone's, if none chosen). */
    fun localized(ctx: Context): Context {
        val lang = language(ctx)
        if (lang.isEmpty()) return ctx
        val config = Configuration(ctx.resources.configuration)
        config.setLocale(Locale.forLanguageTag(lang))
        return ctx.createConfigurationContext(config)
    }

    /** Localized string for notifications and the event log (works with no screen open). */
    fun str(ctx: Context, id: Int, vararg args: Any): String = localized(ctx).getString(id, *args)

    fun lastCallState(ctx: Context) = prefs(ctx).getString(KEY_LAST_CALL_STATE, "") ?: ""
    fun setLastCallState(ctx: Context, s: String) {
        // commit(): ArmActivity / the service read this right after.
        prefs(ctx).edit()
            .putString(KEY_LAST_CALL_STATE, s)
            .putLong(KEY_LAST_CALL_STATE_AT, System.currentTimeMillis())
            .commit()
    }

    fun msSinceLastCallState(ctx: Context): Long =
        System.currentTimeMillis() - prefs(ctx).getLong(KEY_LAST_CALL_STATE_AT, 0L)

    /** True between a RINGING we acted on and the following IDLE (filters out outgoing calls). */
    fun incomingActive(ctx: Context) = prefs(ctx).getBoolean(KEY_INCOMING_ACTIVE, false)
    fun setIncomingActive(ctx: Context, v: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_INCOMING_ACTIVE, v).commit()
    }

    private const val KEY_VOICE_INTRO_SEEN = "voice_intro_seen"

    /** The dial pad's card that introduces voice answer was closed or used, or the answer word screen was opened. */
    fun voiceIntroSeen(ctx: Context) = prefs(ctx).getBoolean(KEY_VOICE_INTRO_SEEN, false)
    fun setVoiceIntroSeen(ctx: Context) {
        prefs(ctx).edit().putBoolean(KEY_VOICE_INTRO_SEEN, true).apply()
    }

    fun lastSpeakerResult(ctx: Context) = prefs(ctx).getString(KEY_LAST_SPEAKER, "") ?: ""
    fun setLastSpeakerResult(ctx: Context, s: String) {
        prefs(ctx).edit().putString(KEY_LAST_SPEAKER, s).apply()
    }

    // ---- Debug-build-only test hook (see DebugReceiver) ----
    const val KEY_DEBUG_FAKE_DETECTOR = "debug_fake_detector"

    fun isDebuggable(ctx: Context) =
        (ctx.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /** Never true in a release (non-debuggable) build, whatever is stored. */
    fun debugFakeDetector(ctx: Context) =
        isDebuggable(ctx) && prefs(ctx).getBoolean(KEY_DEBUG_FAKE_DETECTOR, false)

    fun setDebugFakeDetector(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_DEBUG_FAKE_DETECTOR, on).commit()
    }

    const val KEY_DEBUG_FAKE_BLUETOOTH = "debug_fake_bluetooth"

    /** Pretend Bluetooth headset in the call screen's audio picker. Never true in a release build. */
    fun debugFakeBluetooth(ctx: Context) =
        isDebuggable(ctx) && prefs(ctx).getBoolean(KEY_DEBUG_FAKE_BLUETOOTH, false)

    fun setDebugFakeBluetooth(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_DEBUG_FAKE_BLUETOOTH, on).commit()
    }

    fun toMap(ctx: Context): Map<String, Any> = mapOf(
        KEY_ENABLED to isEnabled(ctx),
        KEY_SENSITIVITY to sensitivity(ctx).toDouble(),
        KEY_LANGUAGE to language(ctx),
    )

    fun save(ctx: Context, map: Map<*, *>) {
        val e = prefs(ctx).edit()
        (map[KEY_ENABLED] as? Boolean)?.let { e.putBoolean(KEY_ENABLED, it) }
        (map[KEY_SENSITIVITY] as? Number)?.let {
            e.putFloat(KEY_SENSITIVITY, it.toFloat().coerceIn(0f, 1f))
        }
        e.apply()
        (map[KEY_LANGUAGE] as? String)?.let { setLanguage(ctx, it) }
    }
}
