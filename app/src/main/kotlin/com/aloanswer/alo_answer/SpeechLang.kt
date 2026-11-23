package com.aloanswer.alo_answer

/** A language of answer words. Each has its own Vosk model in the APK (assets/[assetDir]). */
enum class SpeechLang(val code: String, val assetDir: String) {
    UZ("uz", "model-uz"),
    RU("ru", "model-ru"),
    EN("en", "model-en");

    /** Spelling differences a user shouldn't have to know: Russian ё is often typed as е. */
    fun fold(word: String) = if (this == RU) word.replace('ё', 'е') else word

    companion object {
        fun of(code: String?): SpeechLang? = values().firstOrNull { it.code == code }
    }
}
