package com.aloanswer.alo_answer.dialer

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.Window
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.aloanswer.alo_answer.R
import com.aloanswer.alo_answer.ui.AppColors
import java.util.concurrent.Executors

/** Building blocks of the phone app screens (light or dark, like the rest of the app). */

internal val AVATAR_COLORS = intArrayOf(
    0xFF00897B.toInt(), 0xFF5E35B1.toInt(), 0xFF1E88E5.toInt(), 0xFFD81B60.toInt(),
    0xFF43A047.toInt(), 0xFFF4511E.toInt(), 0xFF6D4C41.toInt(), 0xFF546E7A.toInt(),
)

internal fun avatarColor(seed: String) = AVATAR_COLORS[Math.floorMod(seed.hashCode(), AVATAR_COLORS.size)]

/** Contact photos (small thumbnails), loaded off the main thread and kept in memory. */
internal object Photos {
    private val cache = LruCache<String, Bitmap>(120)
    private val io = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    fun load(ctx: Context, uri: String, done: (Bitmap?) -> Unit) {
        cache.get(uri)?.let { return done(it) }
        val app = ctx.applicationContext
        io.execute {
            val bmp = try {
                app.contentResolver.openInputStream(Uri.parse(uri))?.use { BitmapFactory.decodeStream(it) }
            } catch (_: Exception) {
                null
            }
            if (bmp != null) cache.put(uri, bmp)
            main.post { done(bmp) }
        }
    }
}

/** Round avatar: the contact photo, else the first letter, else a person icon (unknown number). */
internal class AvatarView(ctx: Context, private val sizeDp: Int) : FrameLayout(ctx) {
    private val letter = TextView(ctx)
    private val image = ImageView(ctx)
    private var wanted: String? = null

    init {
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) = outline.setOval(0, 0, view.width, view.height)
        }
        clipToOutline = true
        letter.gravity = Gravity.CENTER
        letter.setTextColor(Color.WHITE)
        letter.textSize = sizeDp * 0.42f
        letter.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        addView(letter, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(image, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    /** [name] null = unknown number. */
    fun bind(name: String?, seed: String, photoUri: String?) {
        setBackgroundColor(avatarColor(seed.ifEmpty { "?" }))
        val first = name?.trim()?.firstOrNull { it.isLetter() }?.uppercaseChar()
        if (first != null) {
            letter.text = first.toString()
            letter.visibility = View.VISIBLE
            image.setImageDrawable(null)
        } else {
            letter.visibility = View.GONE
            showIcon()
        }
        wanted = photoUri
        if (photoUri != null) {
            Photos.load(context, photoUri) { bmp ->
                if (wanted != photoUri || bmp == null) return@load
                image.clearColorFilter()
                image.setPadding(0, 0, 0, 0)
                image.scaleType = ImageView.ScaleType.CENTER_CROP
                image.setImageBitmap(bmp)
                letter.visibility = View.GONE
            }
        }
    }

    private fun showIcon() {
        val p = context.dp(sizeDp) / 5
        image.setPadding(p, p, p, p)
        image.scaleType = ImageView.ScaleType.FIT_CENTER
        image.setImageResource(R.drawable.ic_person)
        image.setColorFilter(Color.WHITE)
    }
}

internal fun Context.rippleRect(color: Int = 0x22808080): RippleDrawable =
    RippleDrawable(ColorStateList.valueOf(color), null, ColorDrawable(Color.WHITE))

internal fun Context.rippleOval(color: Int = 0x33808080): RippleDrawable =
    RippleDrawable(ColorStateList.valueOf(color), null, GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(Color.WHITE)
    })

/** 48 dp round icon button. */
internal fun Context.iconButton(icon: Int, tint: Int, description: String, onClick: () -> Unit) = ImageView(this).apply {
    setImageResource(icon)
    setColorFilter(tint)
    scaleType = ImageView.ScaleType.CENTER
    contentDescription = description
    background = rippleOval()
    setOnClickListener { onClick() }
}

/** Filter chip (All / Missed). */
internal class Chip(ctx: Context, private val c: AppColors, label: String) : TextView(ctx) {
    init {
        text = label
        textSize = 14f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.CENTER
        minHeight = ctx.dp(36)
        setPadding(ctx.dp(16), 0, ctx.dp(16), 0)
        setSelectedLook(false)
    }

    fun setSelectedLook(on: Boolean) {
        isSelected = on
        val shape = GradientDrawable().apply {
            cornerRadius = context.dp(18).toFloat()
            setColor(if (on) c.primaryContainer else Color.TRANSPARENT)
            if (!on) setStroke(context.dp(1), c.outline)
        }
        background = RippleDrawable(ColorStateList.valueOf(0x22808080), shape, null)
        setTextColor(if (on) c.onPrimaryContainer else c.dim)
    }
}

/**
 * Bottom sheet in the app's colours: a header, round action buttons, rows.
 * Scrolls when it is taller than the screen.
 */
internal class Sheet(private val activity: Activity, private val c: AppColors) {
    private val dialog = Dialog(activity)
    private val body = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, activity.dp(10), 0, activity.dp(16))
    }

    init {
        val handle = View(activity).apply { background = rounded(c.outline, activity.dp(2).toFloat()) }
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                val r = activity.dp(28).toFloat()
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
                setColor(c.card)
            }
            clipToOutline = true
            addView(handle, LinearLayout.LayoutParams(activity.dp(36), activity.dp(4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = activity.dp(10)
            })
            addView(ScrollView(activity).apply { addView(body) })
        }
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(column)
        dialog.window?.let { w ->
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            w.setGravity(Gravity.BOTTOM)
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        // The column pads itself for the navigation bar; only its top/left/right should stay flush.
        column.setOnApplyWindowInsetsListener { v, insets ->
            @Suppress("DEPRECATION")
            v.setPadding(0, 0, 0, insets.systemWindowInsetBottom)
            insets
        }
    }

    fun header(name: String?, seed: String, photoUri: String?, title: String, subtitle: String?): Sheet {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(activity.dp(20), activity.dp(8), activity.dp(20), activity.dp(12))
        }
        row.addView(AvatarView(activity, 52).apply { bind(name, seed, photoUri) }, LinearLayout.LayoutParams(activity.dp(52), activity.dp(52)))
        val texts = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.dp(16), 0, 0, 0)
        }
        texts.addView(label(title, 20f, c.text, medium = true).apply { maxLines = 2 })
        if (!subtitle.isNullOrEmpty()) texts.addView(label(subtitle, 15f, c.dim))
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        body.addView(row)
        return this
    }

    class Action(val icon: Int, val label: String, val onClick: () -> Unit)

    /** Row of round buttons with a label under each (Call, Message, Copy…). */
    fun actions(vararg list: Action): Sheet {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(activity.dp(8), activity.dp(4), activity.dp(8), activity.dp(12))
        }
        list.forEachIndexed { i, a ->
            val item = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setOnClickListener { dismiss(); a.onClick() }
                background = activity.rippleRect()
                setPadding(0, activity.dp(6), 0, activity.dp(6))
                contentDescription = a.label
            }
            item.addView(ImageView(activity).apply {
                setImageResource(a.icon)
                setColorFilter(if (i == 0) Color.WHITE else c.onPrimaryContainer)
                scaleType = ImageView.ScaleType.CENTER
                background = circle(if (i == 0) c.hit else c.primaryContainer)
            }, LinearLayout.LayoutParams(activity.dp(52), activity.dp(52)))
            item.addView(label(a.label, 13f, c.text).apply {
                gravity = Gravity.CENTER
                maxLines = 2
                setPadding(activity.dp(2), activity.dp(6), activity.dp(2), 0)
            })
            row.addView(item, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        body.addView(row)
        return this
    }

    /** Small grey title above a group of rows. */
    fun subtitle(text: String): Sheet {
        body.addView(label(text, 14f, c.primary, medium = true).apply {
            setPadding(activity.dp(20), activity.dp(10), activity.dp(20), activity.dp(4))
        })
        return this
    }

    /** Plain info line (call history). */
    fun info(icon: Int, tint: Int, text: String, trailing: String? = null): Sheet {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(activity.dp(20), activity.dp(8), activity.dp(20), activity.dp(8))
        }
        row.addView(ImageView(activity).apply {
            setImageResource(icon)
            setColorFilter(tint)
        }, LinearLayout.LayoutParams(activity.dp(20), activity.dp(20)).apply { marginEnd = activity.dp(16) })
        row.addView(label(text, 15f, c.text), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (trailing != null) row.addView(label(trailing, 14f, c.dim))
        body.addView(row)
        return this
    }

    fun row(icon: Int, text: String, danger: Boolean = false, onClick: () -> Unit): Sheet {
        val tint = if (danger) c.error else c.text
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = activity.dp(54)
            setPadding(activity.dp(20), 0, activity.dp(20), 0)
            background = activity.rippleRect()
            setOnClickListener { dismiss(); onClick() }
        }
        row.addView(ImageView(activity).apply {
            setImageResource(icon)
            setColorFilter(if (danger) c.error else c.dim)
        }, LinearLayout.LayoutParams(activity.dp(24), activity.dp(24)).apply { marginEnd = activity.dp(18) })
        row.addView(label(text, 16f, tint), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        body.addView(row)
        return this
    }

    /** A number of a contact: label + number, with message and call buttons. */
    fun numberRow(number: String, label: String, onCall: () -> Unit, onSms: () -> Unit, onLong: () -> Unit): Sheet {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = activity.dp(64)
            setPadding(activity.dp(20), activity.dp(4), activity.dp(8), activity.dp(4))
            background = activity.rippleRect()
            setOnClickListener { dismiss(); onCall() }
            setOnLongClickListener { dismiss(); onLong(); true }
        }
        val texts = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(label(number, 17f, c.text))
        texts.addView(label(label, 13.5f, c.dim))
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(activity.iconButton(R.drawable.ic_message, c.primary, activity.getString(R.string.dialer_sms)) {
            dismiss(); onSms()
        }, LinearLayout.LayoutParams(activity.dp(48), activity.dp(48)))
        row.addView(activity.iconButton(R.drawable.ic_call, c.hit, activity.getString(R.string.dialer_call)) {
            dismiss(); onCall()
        }, LinearLayout.LayoutParams(activity.dp(48), activity.dp(48)))
        body.addView(row)
        return this
    }

    fun divider(): Sheet {
        body.addView(View(activity).apply { setBackgroundColor(c.divider) }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, activity.dp(1)
        ).apply { topMargin = activity.dp(6); bottomMargin = activity.dp(6) })
        return this
    }

    fun show(): Sheet {
        if (!activity.isFinishing) dialog.show()
        return this
    }

    fun dismiss() = dialog.dismiss()

    private fun label(s: String, sp: Float, color: Int, medium: Boolean = false) = TextView(activity).apply {
        text = s
        textSize = sp
        setTextColor(color)
        ellipsize = TextUtils.TruncateAt.END
        if (medium) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
}
