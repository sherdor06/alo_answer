package com.aloanswer.alo_answer.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.os.Build
import com.aloanswer.alo_answer.R
import com.aloanswer.alo_answer.Settings

/**
 * One language for the whole app: every screen, the call screen, notifications.
 * Android 13+ stores it as the system per-app language (also in the phone's
 * Settings → Apps → Language) and recreates the open screens itself.
 */
object LanguagePicker {

    /** Name of a language code ("" = the phone's language), as shown in the list. */
    fun label(ctx: Context, code: String): String = when (code) {
        "uz" -> ctx.getString(R.string.ui_lang_uz)
        "ru" -> ctx.getString(R.string.ui_lang_ru)
        "en" -> ctx.getString(R.string.ui_lang_en)
        else -> {
            val sys = Settings.systemLocale(ctx)
            val name = sys.getDisplayLanguage(sys).replaceFirstChar { it.titlecase(sys) }
            if (name.isBlank()) ctx.getString(R.string.ui_lang_system)
            else ctx.getString(R.string.ui_lang_system_named, name)
        }
    }

    fun show(activity: Activity) {
        val codes = Settings.LANGUAGES // "", uz, ru, en
        val current = Settings.language(activity)
        AlertDialog.Builder(activity)
            .setTitle(R.string.ui_language)
            .setSingleChoiceItems(codes.map { label(activity, it) }.toTypedArray(), codes.indexOf(current)) { d, which ->
                d.dismiss()
                val code = codes[which]
                if (code == Settings.language(activity)) return@setSingleChoiceItems
                Settings.setLanguage(activity, code)
                // Android 13+ recreates every open screen by itself after setApplicationLocales().
                if (Build.VERSION.SDK_INT < 33) activity.recreate()
            }
            .setNegativeButton(R.string.ui_cancel, null)
            .show()
    }

    /** The language the screens are shown in now (for the "Language" row). */
    fun currentLabel(ctx: Context): String = label(ctx, Settings.language(ctx))
}
