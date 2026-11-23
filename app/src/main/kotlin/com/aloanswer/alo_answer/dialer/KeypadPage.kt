package com.aloanswer.alo_answer.dialer

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import com.aloanswer.alo_answer.R
import com.aloanswer.alo_answer.Settings
import com.aloanswer.alo_answer.WakeWords

/**
 * Keypad tab: the number (formatted, long-press to copy or paste), smart dial
 * (contacts matching the digits by name or number, Latin and Cyrillic), the
 * dial pad (long-press 1 = voicemail, long-press 0 = +) and the call button
 * (with nothing typed it brings back the last number called).
 */
internal class KeypadPage(private val a: DialerActivity) {

    private companion object {
        val LETTERS = mapOf(
            '2' to "ABC", '3' to "DEF", '4' to "GHI", '5' to "JKL", '6' to "MNO",
            '7' to "PQRS", '8' to "TUV", '9' to "WXYZ", '0' to "+",
        )
        const val MAX_RESULTS = 25
    }

    private val c get() = a.c
    private val number = StringBuilder()

    private lateinit var numberView: TextView
    private lateinit var backspace: View
    private lateinit var results: LinearLayout
    private lateinit var noMatch: LinearLayout

    val view: View = build()

    fun number(): String = number.toString()

    fun setNumber(s: String) {
        number.clear()
        number.append(s.filter { it.isDigit() || it in "+*#,;" })
        changed()
    }

    fun onShown() = refreshSuggestions()

    fun onContactsChanged() = refreshSuggestions()

    private fun press(ch: Char) {
        number.append(ch)
        a.tone(ch)
        changed()
    }

    private fun changed() {
        numberView.text = PhoneSettings.format(a, number.toString())
        backspace.visibility = if (number.isEmpty()) View.INVISIBLE else View.VISIBLE
        refreshSuggestions()
    }

    private fun onCallPressed() {
        if (number.isNotEmpty()) {
            a.placeCall(number.toString())
            return
        }
        // Like other phone apps: the call button with nothing typed brings back the last number.
        val app = a.applicationContext
        a.io.execute {
            val last = CallLogRepo.lastOutgoingNumber(app)
            a.main.post { if (last != null && number.isEmpty()) setNumber(last) }
        }
    }

    private fun showNumberMenu(anchor: View) {
        val menu = PopupMenu(a, anchor)
        if (number.isNotEmpty()) menu.menu.add(0, 1, 0, R.string.dialer_copy)
        val pasted = PhoneSettings.pasteNumber(a)
        if (pasted != null) menu.menu.add(0, 2, 1, R.string.dialer_paste)
        if (!menu.menu.hasVisibleItems()) return
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> PhoneSettings.copy(a, number.toString())
                2 -> pasted?.let { setNumber(it) }
            }
            true
        }
        menu.show()
    }

    // ---------------- smart dial ----------------

    private class Match(val name: String?, val number: String, val label: String, val photoUri: String?, val score: Int)

    private fun refreshSuggestions() {
        if (!::results.isInitialized) return
        results.removeAllViews()
        val typed = number.toString()
        val digits = typed.filter(Char::isDigit)
        if (digits.isEmpty()) {
            noMatch.visibility = View.GONE
            // Nothing typed: the empty space above the number introduces voice answer, once.
            if (!Settings.voiceIntroSeen(a) && !a.addingCall) results.addView(voiceIntro())
            return
        }
        val matches = ArrayList<Match>()
        for (ct in a.contactList) {
            val nameScore = if (typed.all(Char::isDigit)) T9.nameScore(ct.t9Words, digits) else 0
            var bestNumber = -1
            var numberScore = 0
            if (digits.length >= 2) {
                ct.digitNumbers.forEachIndexed { i, d ->
                    val s = when {
                        d.startsWith(digits) -> 28
                        d.contains(digits) && digits.length >= 3 -> 25
                        else -> 0
                    }
                    if (s > numberScore) {
                        numberScore = s
                        bestNumber = i
                    }
                }
            }
            val score = maxOf(nameScore * 10, numberScore)
            if (score == 0) continue
            val n = ct.numbers[if (bestNumber >= 0) bestNumber else 0]
            matches += Match(ct.name, n.number, n.label, ct.photoUri, score + if (ct.starred) 1 else 0)
        }
        if (digits.length >= 3) {
            val known = matches.map { it.number.filter(Char::isDigit) }.toHashSet()
            for (e in a.recentNumbers()) {
                val d = e.number.filter(Char::isDigit)
                if (d.contains(digits) && known.add(d)) {
                    matches += Match(null, e.number, a.getString(R.string.tab_recents), null, if (d.startsWith(digits)) 27 else 24)
                }
            }
        }
        matches.sortWith(compareByDescending<Match> { it.score }.thenBy { it.name ?: "￿" })
        for (m in matches.take(MAX_RESULTS)) results.addView(resultRow(m))
        noMatch.visibility = if (matches.isEmpty() && digits.length >= 3) View.VISIBLE else View.GONE
    }

    private fun resultRow(m: Match): View {
        val row = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = a.dp(60)
            setPadding(a.dp(20), a.dp(6), a.dp(20), a.dp(6))
            background = a.rippleRect()
            setOnClickListener { a.placeCall(m.number) }
            setOnLongClickListener {
                setNumber(m.number)
                true
            }
        }
        row.addView(AvatarView(a, 40).apply { bind(m.name, m.name ?: m.number, m.photoUri) },
            LinearLayout.LayoutParams(a.dp(40), a.dp(40)))
        val texts = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(a.dp(14), 0, a.dp(8), 0)
        }
        texts.addView(TextView(a).apply {
            text = m.name ?: PhoneSettings.format(a, m.number)
            textSize = 16f
            setTextColor(c.text)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        texts.addView(TextView(a).apply {
            text = if (m.name != null) "${m.label} · ${PhoneSettings.format(a, m.number)}" else m.label
            textSize = 13.5f
            setTextColor(c.dim)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(ImageView(a).apply {
            setImageResource(R.drawable.ic_call)
            setColorFilter(c.hit)
        }, LinearLayout.LayoutParams(a.dp(22), a.dp(22)))
        return row
    }

    // ---------------- layout ----------------

    private fun build(): View {
        val col = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        // Smart dial results above the number (they get whatever height is left).
        val area = FrameLayout(a)
        results = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        area.addView(ScrollView(a).apply {
            isVerticalScrollBarEnabled = false
            addView(results)
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        noMatch = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            addView(link(R.drawable.ic_person_add, R.string.dialer_add_contact) { PhoneSettings.addToContacts(a, number()) })
            addView(link(R.drawable.ic_message, R.string.dialer_send_sms) { PhoneSettings.sms(a, number()) })
        }
        area.addView(noMatch, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        ).apply { bottomMargin = a.dp(6) })
        col.addView(area, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // The number, centred, with backspace on the right.
        val numRow = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(a.dp(12), 0, a.dp(12), 0)
        }
        numRow.addView(View(a), LinearLayout.LayoutParams(a.dp(48), 1))
        numberView = TextView(a).apply {
            setTextColor(c.text)
            gravity = Gravity.CENTER
            maxLines = 1
            setAutoSizeTextTypeUniformWithConfiguration(18, 36, 1, TypedValue.COMPLEX_UNIT_SP)
            setOnLongClickListener {
                showNumberMenu(it)
                true
            }
        }
        numRow.addView(numberView, LinearLayout.LayoutParams(0, a.dp(58), 1f))
        backspace = a.iconButton(R.drawable.ic_backspace, c.dim, a.getString(R.string.dialer_delete)) {
            if (number.isNotEmpty()) {
                number.deleteCharAt(number.length - 1)
                changed()
            }
        }.apply {
            setOnLongClickListener {
                setNumber("")
                true
            }
            visibility = View.INVISIBLE
        }
        numRow.addView(backspace, LinearLayout.LayoutParams(a.dp(48), a.dp(48)))
        col.addView(numRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // WRAP_CONTENT width: a vertical LinearLayout gives children MATCH_PARENT by default,
        // which pins the 3-column grid to the left instead of letting CENTER_HORIZONTAL center it.
        col.addView(buildPad(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val call = ImageView(a).apply {
            setImageResource(R.drawable.ic_call)
            setColorFilter(Color.WHITE)
            scaleType = ImageView.ScaleType.CENTER
            background = RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), circle(c.hit), null)
            contentDescription = a.getString(R.string.dialer_call)
            elevation = a.dp(2).toFloat()
            setOnClickListener { onCallPressed() }
        }
        col.addView(call, LinearLayout.LayoutParams(a.dp(68), a.dp(68)).apply {
            topMargin = a.dp(8)
            bottomMargin = a.dp(14)
        })
        return col
    }

    /**
     * A card that tells what the app is for: answering by voice, with the word to say and
     * a button to pick another one (or to turn it on). Closing it, or using it, hides it for good.
     */
    private fun voiceIntro(): View {
        val on = Settings.isEnabled(a)
        val card = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(c.primaryContainer, a.dp(20).toFloat())
            setPadding(a.dp(16), a.dp(6), a.dp(6), a.dp(6))
        }
        val head = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(ImageView(a).apply {
            setImageResource(R.drawable.ic_record_voice_over)
            setColorFilter(c.onPrimaryContainer)
        }, LinearLayout.LayoutParams(a.dp(22), a.dp(22)).apply { marginEnd = a.dp(10) })
        head.addView(TextView(a).apply {
            setText(R.string.ui_master_title)
            textSize = 16f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(c.onPrimaryContainer)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(a.iconButton(R.drawable.ic_close, c.onPrimaryContainer, a.getString(R.string.dialer_voice_dismiss)) {
            Settings.setVoiceIntroSeen(a)
            refreshSuggestions()
        }, LinearLayout.LayoutParams(a.dp(44), a.dp(44)))
        card.addView(head)
        card.addView(TextView(a).apply {
            text = a.getString(if (on) R.string.dialer_voice_intro_on else R.string.dialer_voice_intro_off, WakeWords.spoken(a))
            textSize = 14.5f
            setTextColor(c.onPrimaryContainer)
            setLineSpacing(0f, 1.1f)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginEnd = a.dp(10)
        })
        card.addView(TextView(a).apply {
            setText(if (on) R.string.dialer_voice_pick else R.string.dialer_voice_turn_on)
            textSize = 14.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(c.primary)
            setPadding(a.dp(14), a.dp(10), a.dp(14), a.dp(10))
            background = a.rippleRect()
            setOnClickListener { a.openVoiceAnswer(pickWord = on) }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.END
            topMargin = a.dp(2)
        })
        return FrameLayout(a).apply {
            setPadding(a.dp(16), a.dp(8), a.dp(16), a.dp(8))
            addView(card)
        }
    }

    private fun link(icon: Int, label: Int, onClick: () -> Unit) = TextView(a).apply {
        setText(label)
        textSize = 14.5f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(c.primary)
        gravity = Gravity.CENTER_VERTICAL
        setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0)
        compoundDrawableTintList = ColorStateList.valueOf(c.primary)
        compoundDrawablePadding = a.dp(8)
        setPadding(a.dp(14), a.dp(10), a.dp(14), a.dp(10))
        background = a.rippleRect()
        setOnClickListener { onClick() }
    }

    private fun buildPad(): View {
        val grid = GridLayout(a).apply { columnCount = 3 }
        for (ch in "123456789*0#") {
            val key = LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = RippleDrawable(ColorStateList.valueOf(0x33808080), circle(c.key), null)
                isClickable = true
                isFocusable = true
                contentDescription = ch.toString()
                setOnClickListener {
                    it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    press(ch)
                }
                when (ch) {
                    '0' -> setOnLongClickListener {
                        press('+')
                        true
                    }
                    '1' -> setOnLongClickListener {
                        it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        a.placeVoicemail()
                        true
                    }
                }
            }
            key.addView(TextView(a).apply {
                text = ch.toString()
                textSize = 28f
                setTextColor(c.text)
                gravity = Gravity.CENTER
                includeFontPadding = false
            })
            val letters = LETTERS[ch]
            when {
                letters != null -> key.addView(TextView(a).apply {
                    text = letters
                    textSize = 10f
                    letterSpacing = 0.15f
                    setTextColor(c.dim)
                    gravity = Gravity.CENTER
                })
                ch == '1' -> key.addView(ImageView(a).apply {
                    setImageResource(R.drawable.ic_voicemail)
                    setColorFilter(c.dim)
                    contentDescription = a.getString(R.string.dialer_voicemail)
                }, LinearLayout.LayoutParams(a.dp(16), a.dp(14)))
            }
            grid.addView(key, GridLayout.LayoutParams().apply {
                width = a.dp(72)
                height = a.dp(72)
                setMargins(a.dp(12), a.dp(5), a.dp(12), a.dp(5))
            })
        }
        return grid
    }
}
