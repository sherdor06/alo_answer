package com.aloanswer.alo_answer.ui

import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Toast
import com.aloanswer.alo_answer.ModelStore
import com.aloanswer.alo_answer.R
import com.aloanswer.alo_answer.Settings
import com.aloanswer.alo_answer.SpeechLang
import com.aloanswer.alo_answer.WakeWords
import com.aloanswer.alo_answer.dialer.dp
import java.util.concurrent.Executors

/**
 * Which words answer a call: ready-made ones in Uzbek, Russian and English, and the user's
 * own, any number of them ticked (at least one). A word of the user's own is checked against
 * its language's speech model before it is added, since the model can't hear a word it
 * doesn't know.
 */
class WakeWordsActivity : Screen() {

    /** The vocabulary check reads a few MB: never on the main thread. */
    private val io = Executors.newSingleThreadExecutor()

    /** A word that isn't ready-made, to show how to type one (shown as "For example: …"). */
    private val examples = mapOf(
        SpeechLang.UZ to "buyuring",
        SpeechLang.RU to "отвечаю",
        SpeechLang.EN to "pick up",
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scaffold(getString(R.string.ui_wake_title), back = true)
        // The user found the feature: the dial pad's introduction card isn't needed any more.
        Settings.setVoiceIntroSeen(this)
    }

    override fun onScreenResume() = render()

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    /** Ticked words changed: copy the models they need now and free the others (in the background). */
    private fun changed() {
        render()
        ModelStore.prepareTicked(this)
    }

    private fun render() {
        content.removeAllViews()
        val selected = WakeWords.selected(this)
        val ticked = selected.map { it.id }.toSet()
        content.addView(text(getString(R.string.ui_wake_intro), 15.5f), matchWrap().apply { bottomMargin = dp(16) })

        val presets = WakeWords.presets()
        for (lang in SpeechLang.values()) {
            val ofLang = presets.filter { it.lang == lang }
            if (ofLang.isEmpty()) continue
            val card = listCard(WakeWords.languageName(this, lang))
            ofLang.forEachIndexed { i, p ->
                if (i > 0) rowDivider(card)
                checkRow(card, p, p.id in ticked, getString(R.string.ui_wake_default).takeIf { p.id == WakeWords.DEFAULT_ID })
            }
        }

        val own = listCard(getString(R.string.ui_wake_own))
        for (p in WakeWords.custom(this)) {
            checkRow(own, p, p.id in ticked, WakeWords.languageName(this, p.lang))
            rowDivider(own)
        }
        listRow(own, R.drawable.ic_add, getString(R.string.ui_wake_add), getString(R.string.ui_wake_add_sub, WakeWords.MAX_WORDS)) {
            addDialog()
        }

        if (WakeWords.languages(selected).size > 1) note(getString(R.string.ui_wake_multi_lang))
        if (ticked.size >= 3) note(getString(R.string.ui_wake_many))
        content.addView(text(getString(R.string.ui_wake_applies), 14f, c.dim), matchWrap().apply { setMargins(dp(4), 0, dp(4), dp(8)) })
        content.addView(buttonRow(button(getString(R.string.ui_test_listening), Btn.TONAL) {
            startActivity(Intent(this, TestListenActivity::class.java))
        }))
    }

    private fun note(s: String) = content.addView(
        iconRow(R.drawable.ic_info_outline, c.dim, s, c.dim),
        matchWrap().apply { setMargins(dp(4), 0, dp(4), dp(10)) }
    )

    /** A tickable row in the style of the settings rows; the whole row toggles. */
    private fun checkRow(parent: LinearLayout, p: WakeWords.Phrase, ticked: Boolean, subtitle: String?) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(60)
            setPadding(dp(20), dp(6), dp(8), dp(6))
            background = RippleDrawable(ColorStateList.valueOf(0x22808080), null, ColorDrawable(Color.WHITE))
            setOnClickListener { toggle(p, ticked) }
            accessibilityDelegate = checkable(ticked)
        }
        row.addView(ImageView(this).apply {
            setImageResource(if (ticked) R.drawable.ic_check_box else R.drawable.ic_check_box_outline_blank)
            setColorFilter(if (ticked) c.primary else c.dim)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(18) })
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(text(WakeWords.capitalized(p.label), 16f))
        if (subtitle != null) texts.addView(text(subtitle, 13.5f, c.dim), matchWrap().apply { topMargin = dp(2) })
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (p.custom) {
            row.addView(iconButton(R.drawable.ic_delete, getString(R.string.ui_wake_delete)) { confirmDelete(p) }.apply {
                setColorFilter(c.dim)
            })
        }
        parent.addView(row, matchWrap())
    }

    /** TalkBack reads the row as a checkbox with its state. */
    private fun checkable(checked: Boolean) = object : View.AccessibilityDelegate() {
        override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(host, info)
            info.className = CheckBox::class.java.name
            info.isCheckable = true
            info.isChecked = checked
        }
    }

    private fun toggle(p: WakeWords.Phrase, ticked: Boolean) {
        val ids = WakeWords.selected(this).map { it.id }.toMutableList()
        if (ticked) {
            if (ids.size <= 1) {
                Toast.makeText(this, R.string.ui_wake_need_one, Toast.LENGTH_SHORT).show()
                return
            }
            ids -= p.id
        } else {
            ids += p.id
        }
        WakeWords.setSelected(this, ids)
        changed()
    }

    private fun confirmDelete(p: WakeWords.Phrase) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.ui_wake_delete_q, getString(R.string.wake_quote, p.label)))
            .setNegativeButton(R.string.ui_cancel, null)
            .setPositiveButton(R.string.ui_wake_delete) { _, _ ->
                WakeWords.removeCustom(this, p)
                changed()
            }
            .show()
    }

    /** The language the add dialog starts with: the app's, if it is one of the three. */
    private fun defaultLanguage(): SpeechLang =
        SpeechLang.of(resources.configuration.locales[0].language) ?: SpeechLang.UZ

    /**
     * Asks for a word or phrase and its language. The dialog stays open while the word is
     * checked and when it can't be used, with the reason under the field.
     */
    private fun addDialog() {
        if (WakeWords.custom(this).size >= WakeWords.MAX_CUSTOM) {
            Toast.makeText(this, getString(R.string.ui_wake_limit, WakeWords.MAX_CUSTOM), Toast.LENGTH_LONG).show()
            return
        }
        var lang = defaultLanguage()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
        }
        val langs = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        for (l in SpeechLang.values()) {
            langs.addView(RadioButton(this).apply {
                id = View.generateViewId()
                text = WakeWords.languageName(this@WakeWordsActivity, l)
                textSize = 15f
                setTextColor(c.text)
                buttonTintList = ColorStateList.valueOf(c.primary)
                isChecked = l == lang
                tag = l
            })
        }
        box.addView(langs, matchWrap())
        val input = EditText(this).apply {
            hint = getString(R.string.ui_wake_add_hint, examples.getValue(lang))
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_DONE
        }
        box.addView(input, matchWrap().apply { topMargin = dp(4) })
        box.addView(text(getString(R.string.ui_wake_add_help), 13.5f, c.dim), matchWrap().apply { topMargin = dp(8) })
        val status = text("", 14f, c.error).apply { visibility = View.GONE }
        box.addView(status, matchWrap().apply { topMargin = dp(8) })

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.ui_wake_add)
            .setView(ScrollView(this).apply { addView(box) })
            .setNegativeButton(R.string.ui_cancel, null)
            .setPositiveButton(R.string.ui_wake_add_btn, null) // replaced on show, so a failed check keeps it open
            .create()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)

        fun say(msg: String, color: Int) {
            status.text = msg
            status.setTextColor(color)
            status.visibility = View.VISIBLE
        }

        /** The short phrase the user was already warned about: adding it again means "yes, keep it". */
        var warnedShort: String? = null
        langs.setOnCheckedChangeListener { group, checkedId ->
            lang = group.findViewById<RadioButton>(checkedId).tag as SpeechLang
            input.hint = getString(R.string.ui_wake_add_hint, examples.getValue(lang))
            status.visibility = View.GONE
            warnedShort = null
        }
        dialog.setOnShowListener {
            val add = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            fun submit() {
                if (!add.isEnabled) return
                val l = lang
                val phrase = WakeWords.normalize(input.text.toString(), l)
                if (phrase.isEmpty()) return
                val words = phrase.split(' ')
                if (words.size > WakeWords.MAX_WORDS) {
                    say(getString(R.string.ui_wake_too_long, WakeWords.MAX_WORDS), c.error)
                    return
                }
                say(getString(R.string.ui_wake_checking), c.dim)
                add.isEnabled = false
                val app = applicationContext
                io.execute {
                    // How the model spells each word (Russian еще → ещё); missing = unknown.
                    val known = ModelStore.resolveWords(app, l, words)
                    runOnUiThread {
                        if (isDestroyed || !dialog.isShowing) return@runOnUiThread
                        add.isEnabled = true
                        val unknown = known?.let { k -> words.filter { it !in k } }
                        val spelled = known?.let { k -> words.joinToString(" ") { k[it] ?: it } } ?: phrase
                        val existing = WakeWords.find(this, l, spelled)
                        when {
                            existing != null -> {
                                // Already on the list ("labbay", or "allo" of "alo"): just tick it.
                                WakeWords.setSelected(this, WakeWords.selected(this).map { it.id } + existing.id)
                                dialog.dismiss()
                                changed()
                            }
                            unknown == null -> say(getString(R.string.ui_wake_check_failed), c.error)
                            unknown.isNotEmpty() -> say(
                                getString(R.string.ui_wake_unknown, unknown.joinToString(", ") { getString(R.string.wake_quote, it) }),
                                c.error
                            )
                            WakeWords.isShort(spelled, l) && warnedShort != spelled -> {
                                warnedShort = spelled
                                say(getString(R.string.ui_wake_short), c.error)
                            }
                            else -> {
                                WakeWords.addCustom(this, l, spelled)
                                dialog.dismiss()
                                changed()
                            }
                        }
                    }
                }
            }
            add.setOnClickListener { submit() }
            input.setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_DONE) {
                    submit()
                    true
                } else {
                    false
                }
            }
            input.requestFocus()
        }
        dialog.show()
    }
}
