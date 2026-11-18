package com.aloanswer.alo_answer.dialer

import android.Manifest
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.provider.CallLog
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import com.aloanswer.alo_answer.R
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Recents tab: calls grouped by number and day, All / Missed filter. Tap a row
 * to call back; the ⓘ button (or a long press) opens the number's history and
 * actions: message, copy, edit before calling, contact, block, delete.
 */
internal class RecentsPage(private val a: DialerActivity) {

    private val c get() = a.c
    var missedOnly = false
        private set
    private var entries: List<CallLogRepo.Entry> = emptyList()
    private var groups: List<CallLogRepo.Group> = emptyList()
    private var loaded = false

    private lateinit var list: ListView
    private lateinit var empty: TextView
    private lateinit var perm: View
    private lateinit var chipRow: View
    private lateinit var allChip: Chip
    private lateinit var missedChip: Chip
    private val adapter = Adapter()

    val view: View = build()

    fun onShown() {
        reload()
        a.markMissedRead()
    }

    fun hasCalls() = entries.isNotEmpty()

    /** Numbers in the log that aren't contacts (newest first, each once), for smart dial. */
    fun unknownNumbers(): List<CallLogRepo.Entry> =
        entries.filter { it.name == null && it.number.isNotEmpty() }.distinctBy { it.number }

    fun setMissedOnly(on: Boolean) {
        missedOnly = on
        allChip.setSelectedLook(!on)
        missedChip.setSelectedLook(on)
        regroup()
    }

    fun reload() {
        val granted = CallLogRepo.granted(a)
        perm.visibility = if (granted) View.GONE else View.VISIBLE
        list.visibility = if (granted) View.VISIBLE else View.GONE
        chipRow.visibility = if (granted) View.VISIBLE else View.GONE
        if (!granted) {
            empty.visibility = View.GONE
            return
        }
        val app = a.applicationContext
        a.io.execute {
            val loadedEntries = CallLogRepo.load(app)
            a.main.post {
                if (a.isDestroyed) return@post
                entries = loadedEntries
                loaded = true
                regroup()
            }
        }
    }

    private fun regroup() {
        val src = if (missedOnly) entries.filter { it.missed } else entries
        groups = CallLogRepo.group(src)
        adapter.notifyDataSetChanged()
        empty.setText(if (missedOnly) R.string.recents_empty_missed else R.string.recents_empty)
        empty.visibility = if (loaded && groups.isEmpty() && CallLogRepo.granted(a)) View.VISIBLE else View.GONE
    }

    // ---------------- formatting ----------------

    private val timeFmt: DateFormat = android.text.format.DateFormat.getTimeFormat(a)
    private val dateFmt: DateFormat = SimpleDateFormat("d MMM", Locale.getDefault())
    private val yearFmt: DateFormat = android.text.format.DateFormat.getDateFormat(a)

    fun whenText(ms: Long): String {
        val now = Calendar.getInstance()
        val then = Calendar.getInstance().apply { timeInMillis = ms }
        fun sameDay(x: Calendar, y: Calendar) =
            x.get(Calendar.YEAR) == y.get(Calendar.YEAR) && x.get(Calendar.DAY_OF_YEAR) == y.get(Calendar.DAY_OF_YEAR)
        val time = timeFmt.format(Date(ms))
        if (sameDay(now, then)) return time
        now.add(Calendar.DAY_OF_YEAR, -1)
        if (sameDay(now, then)) return a.getString(R.string.recents_yesterday) + ", " + time
        val fmt = if (now.get(Calendar.YEAR) == then.get(Calendar.YEAR)) dateFmt else yearFmt
        return fmt.format(Date(ms)) + ", " + time
    }

    private fun durationText(sec: Long) =
        if (sec >= 3600) "%d:%02d:%02d".format(Locale.US, sec / 3600, (sec / 60) % 60, sec % 60)
        else "%d:%02d".format(Locale.US, sec / 60, sec % 60)

    /** (icon, label, red?) for a CallLog type. */
    private fun typeInfo(type: Int): Triple<Int, String, Boolean> = when (type) {
        CallLog.Calls.OUTGOING_TYPE -> Triple(R.drawable.ic_call_made, a.getString(R.string.calltype_outgoing), false)
        CallLog.Calls.MISSED_TYPE -> Triple(R.drawable.ic_call_missed, a.getString(R.string.calltype_missed), true)
        CallLog.Calls.REJECTED_TYPE -> Triple(R.drawable.ic_call_missed, a.getString(R.string.calltype_rejected), true)
        CallLog.Calls.BLOCKED_TYPE -> Triple(R.drawable.ic_block, a.getString(R.string.calltype_blocked), true)
        else -> Triple(R.drawable.ic_call_received, a.getString(R.string.calltype_incoming), false)
    }

    private fun title(g: CallLogRepo.Group) =
        g.name ?: g.number.takeIf { it.isNotEmpty() }?.let { PhoneSettings.format(a, it) }
            ?: a.getString(R.string.dialer_private_number)

    private fun call(g: CallLogRepo.Group) {
        if (g.number.isEmpty()) {
            Toast.makeText(a, R.string.dialer_private_number, Toast.LENGTH_SHORT).show()
            return
        }
        a.placeCall(g.number)
    }

    // ---------------- details ----------------

    private fun details(g: CallLogRepo.Group) {
        val number = g.number
        val app = a.applicationContext
        a.io.execute {
            val info = if (number.isEmpty()) null else ContactsRepo.lookup(app, number)
            val canBlock = number.isNotEmpty() && BlockRepo.canBlock(app)
            val blocked = canBlock && BlockRepo.isBlocked(app, number)
            a.main.post { if (!a.isDestroyed) showDetails(g, info, canBlock, blocked) }
        }
    }

    private fun showDetails(g: CallLogRepo.Group, info: ContactsRepo.Info?, canBlock: Boolean, blocked: Boolean) {
        val number = g.number
        val name = info?.name ?: g.name
        val formatted = if (number.isEmpty()) a.getString(R.string.dialer_private_number) else PhoneSettings.format(a, number)
        val sheet = Sheet(a, c).header(name, name ?: number, info?.photoUri ?: g.first.photoUri, name ?: formatted, if (name != null) formatted else null)
        if (number.isNotEmpty()) {
            sheet.actions(
                Sheet.Action(R.drawable.ic_call, a.getString(R.string.dialer_call)) { a.placeCall(number) },
                Sheet.Action(R.drawable.ic_message, a.getString(R.string.dialer_sms)) { PhoneSettings.sms(a, number) },
                Sheet.Action(R.drawable.ic_content_copy, a.getString(R.string.dialer_copy_short)) { PhoneSettings.copy(a, number) },
                Sheet.Action(R.drawable.ic_edit, a.getString(R.string.dialer_edit_short)) { a.toKeypad(number) },
            )
        }
        sheet.subtitle(a.getString(R.string.dialer_call_history))
        for (e in g.entries.take(20)) {
            val (icon, label, red) = typeInfo(e.type)
            val dur = if (!e.missed && !e.declined && e.durationSec > 0) durationText(e.durationSec) else null
            val sim = e.accountId?.let { a.simLabels[it] }
            sheet.info(icon, if (red) c.missed else c.dim, listOfNotNull(label, sim).joinToString(" · ") + " · " + whenText(e.date), dur)
        }
        if (number.isNotEmpty()) {
            sheet.divider()
            val contactUri = info?.contactUri
            if (contactUri != null) {
                sheet.row(R.drawable.ic_account_circle, a.getString(R.string.dialer_open_contact)) { PhoneSettings.viewContact(a, contactUri) }
            } else {
                sheet.row(R.drawable.ic_person_add, a.getString(R.string.dialer_add_contact)) { PhoneSettings.addToContacts(a, number) }
            }
            if (canBlock) {
                if (blocked) {
                    sheet.row(R.drawable.ic_block, a.getString(R.string.dialer_unblock)) { setBlocked(number, false) }
                } else {
                    sheet.row(R.drawable.ic_block, a.getString(R.string.dialer_block), danger = true) { setBlocked(number, true) }
                }
            }
        }
        sheet.row(R.drawable.ic_delete, a.getString(R.string.dialer_delete_history), danger = true) {
            a.withCallLogWrite {
                val ids = g.entries.map { it.id }
                val app = a.applicationContext
                a.io.execute {
                    CallLogRepo.delete(app, ids)
                    a.main.post { reload() }
                }
            }
        }
        sheet.show()
    }

    private fun setBlocked(number: String, block: Boolean) {
        val app = a.applicationContext
        a.io.execute {
            val ok = if (block) BlockRepo.block(app, number) else BlockRepo.unblock(app, number)
            a.main.post {
                val msg = when {
                    !ok -> R.string.dialer_cant_open
                    block -> R.string.dialer_blocked_toast
                    else -> R.string.dialer_unblocked_toast
                }
                Toast.makeText(a, msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ---------------- list ----------------

    private inner class Adapter : BaseAdapter() {
        override fun getCount() = groups.size
        override fun getItem(position: Int) = groups[position]
        override fun getItemId(position: Int) = groups[position].first.id
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val row = convertView as? Row ?: Row(a)
            row.bind(groups[position])
            return row
        }
    }

    private inner class Row(ctx: Context) : LinearLayout(ctx) {
        private val avatar = AvatarView(ctx, 44)
        private val titleView = TextView(ctx)
        private val subtitle = TextView(ctx)
        private val info = ctx.iconButton(R.drawable.ic_info_outline, c.dim, ctx.getString(R.string.dialer_details)) {
            group?.let { details(it) }
        }
        private var group: CallLogRepo.Group? = null

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = ctx.dp(68)
            setPadding(ctx.dp(16), ctx.dp(8), ctx.dp(8), ctx.dp(8))
            background = ctx.rippleRect()
            setOnClickListener { group?.let { call(it) } }
            setOnLongClickListener {
                group?.let { details(it) }
                true
            }
            addView(avatar, LayoutParams(ctx.dp(44), ctx.dp(44)))
            val texts = LinearLayout(ctx).apply { orientation = VERTICAL }
            titleView.textSize = 16.5f
            titleView.maxLines = 1
            titleView.ellipsize = TextUtils.TruncateAt.END
            subtitle.textSize = 13.5f
            subtitle.maxLines = 1
            subtitle.ellipsize = TextUtils.TruncateAt.END
            subtitle.compoundDrawablePadding = ctx.dp(4)
            subtitle.gravity = Gravity.CENTER_VERTICAL
            texts.addView(titleView)
            texts.addView(subtitle)
            addView(texts, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = ctx.dp(14) })
            addView(info, LayoutParams(ctx.dp(48), ctx.dp(48)))
        }

        fun bind(g: CallLogRepo.Group) {
            group = g
            val e = g.first
            val (icon, label, red) = typeInfo(e.type)
            titleView.text = title(g) + if (g.count > 1) "  (${g.count})" else ""
            titleView.setTextColor(if (red) c.missed else c.text)
            val parts = mutableListOf(label)
            e.accountId?.let { a.simLabels[it] }?.let { parts += it }
            parts += whenText(e.date)
            if (!e.missed && !e.declined && e.durationSec > 0) parts += durationText(e.durationSec)
            subtitle.text = parts.joinToString(" · ")
            subtitle.setTextColor(c.dim)
            subtitle.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0)
            subtitle.compoundDrawableTintList = ColorStateList.valueOf(if (red) c.missed else c.dim)
            avatar.bind(g.name, g.name ?: g.number, e.photoUri)
            info.contentDescription = a.getString(R.string.dialer_details) + ": " + title(g)
        }
    }

    // ---------------- layout ----------------

    private fun build(): View {
        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        val chips = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(a.dp(16), a.dp(4), a.dp(16), a.dp(8))
        }
        allChip = Chip(a, c, a.getString(R.string.dialer_filter_all)).apply { setOnClickListener { setMissedOnly(false) } }
        missedChip = Chip(a, c, a.getString(R.string.dialer_filter_missed)).apply { setOnClickListener { setMissedOnly(true) } }
        chips.addView(allChip)
        chips.addView(missedChip, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = a.dp(8) })
        allChip.setSelectedLook(true)
        chipRow = chips
        col.addView(chips)

        perm = permissionBox(a, c, R.string.perm_call_log) {
            // One question for both: read the history, and mark missed calls seen / delete calls.
            a.requestPermissions(
                arrayOf(Manifest.permission.READ_CALL_LOG, Manifest.permission.WRITE_CALL_LOG),
                DialerActivity.REQ_CALL_LOG
            )
        }
        col.addView(perm)
        empty = TextView(a).apply {
            textSize = 15f
            setTextColor(c.dim)
            gravity = Gravity.CENTER
            setPadding(a.dp(32), a.dp(48), a.dp(32), a.dp(16))
            visibility = View.GONE
        }
        col.addView(empty)
        list = ListView(a).apply {
            adapter = this@RecentsPage.adapter
            divider = null
            selector = ColorDrawable(Color.TRANSPARENT)
            cacheColorHint = Color.TRANSPARENT
            clipToPadding = false
            setPadding(0, 0, 0, a.dp(8))
        }
        col.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return col
    }
}

/** "Allow access to …" with a button, for a tab whose permission is missing. */
internal fun permissionBox(a: DialerActivity, c: com.aloanswer.alo_answer.ui.AppColors, message: Int, onAllow: () -> Unit): View =
    LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(a.dp(32), a.dp(48), a.dp(32), a.dp(16))
        addView(ImageView(a).apply {
            setImageResource(R.drawable.ic_info_outline)
            setColorFilter(c.dim)
        }, LinearLayout.LayoutParams(a.dp(32), a.dp(32)).apply { bottomMargin = a.dp(12) })
        addView(TextView(a).apply {
            setText(message)
            textSize = 15f
            setTextColor(c.dim)
            gravity = Gravity.CENTER
        })
        addView(Button(a).apply {
            setText(R.string.perm_allow)
            setTextColor(c.onPrimary)
            isAllCaps = false
            background = rounded(c.primary, a.dp(24).toFloat())
            setPadding(a.dp(24), 0, a.dp(24), 0)
            stateListAnimator = null
            setOnClickListener { onAllow() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, a.dp(48)).apply { topMargin = a.dp(16) })
    }
