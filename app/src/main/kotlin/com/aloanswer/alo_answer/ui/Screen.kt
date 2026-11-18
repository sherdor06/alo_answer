package com.aloanswer.alo_answer.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.aloanswer.alo_answer.R
import com.aloanswer.alo_answer.Settings
import com.aloanswer.alo_answer.dialer.dp
import com.aloanswer.alo_answer.dialer.edgeToEdge
import com.aloanswer.alo_answer.dialer.padForSystemBars
import com.aloanswer.alo_answer.dialer.rounded
import java.util.Locale

/** Colours of the app screens; light or dark from res/values(-night)/colors.xml. */
class AppColors(ctx: Context) {
    val bg = ctx.getColor(R.color.app_bg)
    val card = ctx.getColor(R.color.app_card)
    val text = ctx.getColor(R.color.app_text)
    val dim = ctx.getColor(R.color.app_text_dim)
    val primary = ctx.getColor(R.color.app_primary)
    val onPrimary = ctx.getColor(R.color.app_on_primary)
    val primaryContainer = ctx.getColor(R.color.app_primary_container)
    val onPrimaryContainer = ctx.getColor(R.color.app_on_primary_container)
    val error = ctx.getColor(R.color.app_error)
    val errorContainer = ctx.getColor(R.color.app_error_container)
    val onErrorContainer = ctx.getColor(R.color.app_on_error_container)
    val outline = ctx.getColor(R.color.app_outline)
    val neutralContainer = ctx.getColor(R.color.app_neutral_container)
    val hit = ctx.getColor(R.color.app_hit)
}

enum class Btn { FILLED, TONAL, OUTLINED, TEXT }

/**
 * Base of the app screens: in-app language, edge-to-edge, a top bar and a
 * scrolling column. Framework views only (no AndroidX), like the call screens.
 */
abstract class Screen : Activity() {

    protected lateinit var c: AppColors
    /** The scrolling column under the top bar. */
    protected lateinit var content: LinearLayout
    private lateinit var actions: LinearLayout
    private var createdLang = ""

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(Settings.localized(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createdLang = Settings.language(this)
        c = AppColors(this)
        edgeToEdge()
    }

    final override fun onResume() {
        super.onResume()
        // The language was changed on another screen: rebuild this one in it.
        if (Settings.language(this) != createdLang) {
            recreate()
            return
        }
        onScreenResume()
    }

    /** onResume, unless the screen is being recreated for a new language. */
    protected open fun onScreenResume() = Unit

    /** False when the screen finished in onCreate before building its views. */
    protected val built: Boolean
        get() = ::content.isInitialized

    // ---------------- layout ----------------

    protected fun scaffold(title: String, back: Boolean) {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(c.bg)
            padForSystemBars()
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(64)
            setPadding(if (back) dp(4) else dp(20), 0, dp(4), 0)
        }
        if (back) bar.addView(iconButton(R.drawable.ic_arrow_back, getString(R.string.ui_back)) { finish() })
        bar.addView(TextView(this).apply {
            text = title
            textSize = 22f
            setTextColor(c.text)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            if (back) setPadding(dp(8), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bar.addView(actions)
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(24))
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    protected fun addAction(icon: Int, description: String, onClick: () -> Unit): ImageView =
        iconButton(icon, description, onClick).also { actions.addView(it) }

    /** Top-bar action: switch between Oʻzbekcha / Русский / English. Stored natively, so calls use it too. */
    protected fun addLanguageAction() = addAction(R.drawable.ic_translate, getString(R.string.ui_language)) {
        val codes = arrayOf("uz", "ru", "en")
        val names = arrayOf(
            getString(R.string.ui_lang_uz), getString(R.string.ui_lang_ru), getString(R.string.ui_lang_en)
        )
        val current = Settings.language(this).ifEmpty {
            when (resources.configuration.locales[0].language) {
                "uz" -> "uz"
                "ru" -> "ru"
                else -> "en"
            }
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.ui_language)
            .setSingleChoiceItems(names, codes.indexOf(current)) { d, which ->
                d.dismiss()
                if (codes[which] != Settings.language(this)) {
                    Settings.save(this, mapOf(Settings.KEY_LANGUAGE to codes[which]))
                    recreate()
                }
            }
            .setNegativeButton(R.string.ui_cancel, null)
            .show()
    }

    // ---------------- building blocks ----------------

    protected fun iconButton(icon: Int, description: String, onClick: () -> Unit) = ImageView(this).apply {
        setImageResource(icon)
        setColorFilter(c.text)
        contentDescription = description
        scaleType = ImageView.ScaleType.CENTER
        background = RippleDrawable(ColorStateList.valueOf(0x33808080), null, circleMask())
        layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
        setOnClickListener { onClick() }
    }

    private fun circleMask() = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(Color.WHITE)
    }

    /** Rounded card added to [parent]; returns its vertical column. */
    protected fun card(parent: LinearLayout = content, bg: Int = c.card): LinearLayout {
        val v = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(bg, dp(20).toFloat())
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        parent.addView(v, matchWrap().apply { bottomMargin = dp(12) })
        return v
    }

    /** Card with a title. */
    protected fun section(title: String, parent: LinearLayout = content): LinearLayout {
        val v = card(parent)
        v.addView(text(title, 17f, c.text, medium = true), matchWrap().apply { bottomMargin = dp(10) })
        return v
    }

    protected fun text(
        s: CharSequence, sp: Float = 15f, color: Int = c.text, medium: Boolean = false,
    ) = TextView(this).apply {
        text = s
        textSize = sp
        setTextColor(color)
        setLineSpacing(0f, 1.12f)
        if (medium) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    /** Icon on the left, text on the right (top-aligned for multi-line text). */
    protected fun iconRow(icon: Int, tint: Int, s: CharSequence, textColor: Int = c.text, iconDp: Int = 20) =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(ImageView(this@Screen).apply {
                setImageResource(icon)
                setColorFilter(tint)
            }, LinearLayout.LayoutParams(dp(iconDp), dp(iconDp)).apply { topMargin = dp(1); marginEnd = dp(10) })
            addView(text(s, color = textColor), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }

    /** ✓ / ✗ line of the setup check. */
    protected fun check(label: String, ok: Boolean?) = iconRow(
        if (ok == true) R.drawable.ic_check_circle else R.drawable.ic_cancel,
        if (ok == true) c.primary else c.error, label
    ).apply { setPadding(0, dp(4), 0, dp(4)) }

    protected fun button(label: String, kind: Btn, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 14.5f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.CENTER
        minHeight = dp(42)
        setPadding(dp(22), dp(10), dp(22), dp(10))
        val (bg, fg) = when (kind) {
            Btn.FILLED -> c.primary to c.onPrimary
            Btn.TONAL -> c.primaryContainer to c.onPrimaryContainer
            Btn.OUTLINED, Btn.TEXT -> Color.TRANSPARENT to c.primary
        }
        val radius = dp(21).toFloat()
        val shape = GradientDrawable().apply {
            cornerRadius = radius
            setColor(bg)
            if (kind == Btn.OUTLINED) setStroke(dp(1), c.outline)
        }
        val mask = GradientDrawable().apply {
            cornerRadius = radius
            setColor(Color.WHITE)
        }
        background = RippleDrawable(ColorStateList.valueOf(0x33808080), shape, mask)
        setTextColor(fg)
        setOnClickListener { onClick() }
    }

    protected fun View.enabled(on: Boolean) = apply {
        isEnabled = on
        alpha = if (on) 1f else 0.38f
    }

    /** Buttons aligned to the right, wrapping to a new line on narrow screens. */
    protected fun buttonRow(vararg buttons: View?) = FlowRow(this, dp(8)).apply {
        buttons.filterNotNull().forEach { addView(it) }
    }

    protected fun matchWrap() =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    protected fun gap(dpValue: Int, parent: LinearLayout = content) =
        parent.addView(View(this), LinearLayout.LayoutParams(1, dp(dpValue)))

    protected fun two(n: Int) = String.format(Locale.US, "%02d", n)
}

/** Right-aligned row of views that wraps onto a new line when it doesn't fit. */
class FlowRow(ctx: Context, private val gapPx: Int) : ViewGroup(ctx) {

    override fun generateDefaultLayoutParams() =
        LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)

    private fun rows(maxW: Int): List<List<View>> {
        val rows = mutableListOf<MutableList<View>>()
        var row = mutableListOf<View>()
        var x = 0
        for (i in 0 until childCount) {
            val ch = getChildAt(i)
            if (ch.visibility == GONE) continue
            if (row.isNotEmpty() && x + ch.measuredWidth > maxW) {
                rows += row
                row = mutableListOf()
                x = 0
            }
            row += ch
            x += ch.measuredWidth + gapPx
        }
        if (row.isNotEmpty()) rows += row
        return rows
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val maxW = width - paddingLeft - paddingRight
        for (i in 0 until childCount) {
            measureChild(
                getChildAt(i),
                MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            )
        }
        val rows = rows(maxW)
        val h = rows.sumOf { r -> r.maxOf { it.measuredHeight } } + gapPx * (rows.size - 1).coerceAtLeast(0)
        setMeasuredDimension(width, h + paddingTop + paddingBottom)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val maxW = width - paddingLeft - paddingRight
        var y = paddingTop
        for (row in rows(maxW)) {
            val rowW = row.sumOf { it.measuredWidth } + gapPx * (row.size - 1)
            var x = paddingLeft + maxW - rowW
            val rowH = row.maxOf { it.measuredHeight }
            for (ch in row) {
                ch.layout(x, y, x + ch.measuredWidth, y + ch.measuredHeight)
                x += ch.measuredWidth + gapPx
            }
            y += rowH + gapPx
        }
    }
}
