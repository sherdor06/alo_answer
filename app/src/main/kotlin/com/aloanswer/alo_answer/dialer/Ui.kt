package com.aloanswer.alo_answer.dialer

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** Small helpers for the native (framework-only, no extra libraries) call and dialer screens. */
internal object Palette {
    val BG_TOP = 0xFF0B3D3A.toInt()
    val BG_BOTTOM = 0xFF061A19.toInt()
    val TEAL = 0xFF00897B.toInt()
    val GREEN = 0xFF2E9E4F.toInt()
    val RED = 0xFFE53935.toInt()
    val BUTTON = 0x29FFFFFF
    val BUTTON_ON = Color.WHITE
    val ICON_ON = 0xFF00352F.toInt()
    val TEXT_DIM = 0xB3FFFFFF.toInt()
    val ACCENT = 0xFF80CBC4.toInt()
    val SHEET = 0xFF123634.toInt()
}

internal fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

internal fun circle(color: Int) = GradientDrawable().apply {
    shape = GradientDrawable.OVAL
    setColor(color)
}

internal fun rounded(color: Int, radiusPx: Float) = GradientDrawable().apply {
    cornerRadius = radiusPx
    setColor(color)
}

internal fun ripple(content: Drawable, rippleColor: Int = 0x40FFFFFF): Drawable =
    RippleDrawable(ColorStateList.valueOf(rippleColor), content, null)

internal fun screenBackground() = GradientDrawable(
    GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Palette.BG_TOP, Palette.BG_BOTTOM)
)

/** Draw behind the system bars on every Android version (15+ does it anyway), then pad with [padForSystemBars]. */
internal fun Activity.edgeToEdge() {
    if (Build.VERSION.SDK_INT >= 30) {
        window.setDecorFitsSystemWindows(false)
    } else {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
    }
}

/** Keep content clear of the status and navigation bars (Android 15+ is edge-to-edge). */
internal fun View.padForSystemBars(extraPx: Int = 0) {
    setOnApplyWindowInsetsListener { v, insets ->
        if (Build.VERSION.SDK_INT >= 30) {
            val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            v.setPadding(i.left + extraPx, i.top + extraPx, i.right + extraPx, i.bottom + extraPx)
        } else {
            @Suppress("DEPRECATION")
            v.setPadding(
                insets.systemWindowInsetLeft + extraPx, insets.systemWindowInsetTop + extraPx,
                insets.systemWindowInsetRight + extraPx, insets.systemWindowInsetBottom + extraPx
            )
        }
        insets
    }
}

/** Round icon button with a label under it; optional on/off look for toggles. */
internal class RoundButton(
    ctx: Context,
    iconRes: Int,
    label: String,
    sizeDp: Int,
    iconDp: Int,
    private val bg: Int,
    private val bgOn: Int = Palette.BUTTON_ON,
) : LinearLayout(ctx) {

    private val circleView = ImageView(ctx)
    private val labelView = TextView(ctx)

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        val size = ctx.dp(sizeDp)
        val pad = (size - ctx.dp(iconDp)) / 2
        circleView.layoutParams = LayoutParams(size, size)
        circleView.setPadding(pad, pad, pad, pad)
        circleView.scaleType = ImageView.ScaleType.FIT_CENTER
        circleView.setImageResource(iconRes)
        circleView.contentDescription = label
        addView(circleView)
        labelView.text = label
        labelView.setTextColor(Palette.TEXT_DIM)
        labelView.textSize = 13f
        labelView.gravity = Gravity.CENTER
        labelView.setPadding(0, ctx.dp(6), 0, 0)
        addView(labelView)
        setActive(false)
    }

    fun onClick(f: () -> Unit) = circleView.setOnClickListener { f() }

    private var iconShown = iconRes

    fun setIcon(res: Int) {
        if (res == iconShown) return
        iconShown = res
        circleView.setImageResource(res)
    }

    fun setLabel(text: String) {
        if (labelView.text.toString() == text) return
        labelView.text = text
        circleView.contentDescription = text
    }

    fun setActive(on: Boolean) {
        circleView.background = ripple(circle(if (on) bgOn else bg))
        circleView.setColorFilter(if (on && bgOn == Palette.BUTTON_ON) Palette.ICON_ON else Color.WHITE)
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        circleView.isEnabled = enabled
        alpha = if (enabled) 1f else 0.35f
    }
}
