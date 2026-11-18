package com.aloanswer.alo_answer.dialer

import android.Manifest
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import com.aloanswer.alo_answer.R

/**
 * Contacts tab: search by name or number, favourites first, then A→Z with
 * letter headers. Tap a contact for its numbers (call / message) and to open it
 * in the contacts app; the green button calls the main number straight away.
 */
internal class ContactsPage(private val a: DialerActivity) {

    private val c get() = a.c
    private var shown: List<ContactsRepo.Contact> = emptyList()

    private lateinit var search: EditText
    private lateinit var searchRow: View
    private lateinit var list: ListView
    private lateinit var empty: TextView
    private lateinit var perm: View
    private val adapter = Adapter()

    val view: View = build()

    fun onShown() = refresh()

    fun onContactsChanged() = applyFilter()

    private fun refresh() {
        val granted = ContactsRepo.granted(a)
        perm.visibility = if (granted) View.GONE else View.VISIBLE
        list.visibility = if (granted) View.VISIBLE else View.GONE
        searchRow.visibility = if (granted) View.VISIBLE else View.GONE
        if (!granted) empty.visibility = View.GONE
        applyFilter()
    }

    private fun applyFilter() {
        if (!::search.isInitialized) return
        val q = search.text.toString().trim().lowercase()
        val digits = q.filter(Char::isDigit)
        val all = a.contactList
        shown = if (q.isEmpty()) all else all.filter { ct ->
            ct.name.lowercase().contains(q) || (digits.length >= 2 && ct.digitNumbers.any { it.contains(digits) })
        }
        adapter.notifyDataSetChanged()
        empty.visibility = if (shown.isEmpty() && ContactsRepo.granted(a)) View.VISIBLE else View.GONE
        empty.setText(if (q.isEmpty()) R.string.contacts_empty else R.string.contacts_no_match)
    }

    /** Header above a row: ★ for favourites, else the first letter when it changes. */
    private fun header(pos: Int): String? {
        if (search.text.isNotEmpty()) return null
        val ct = shown[pos]
        val prev = shown.getOrNull(pos - 1)
        if (ct.starred) return if (prev == null) a.getString(R.string.dialer_favourites) else null
        val letter = ct.name.trim().firstOrNull()?.uppercaseChar()?.let { if (it.isLetter()) it.toString() else "#" } ?: "#"
        if (prev == null || prev.starred) return letter
        val prevLetter = prev.name.trim().firstOrNull()?.uppercaseChar()?.let { if (it.isLetter()) it.toString() else "#" } ?: "#"
        return if (letter != prevLetter) letter else null
    }

    private fun open(ct: ContactsRepo.Contact) {
        val sheet = Sheet(a, c).header(ct.name, ct.name, ct.photoUri, ct.name, null)
        for (n in ct.numbers) {
            sheet.numberRow(
                PhoneSettings.format(a, n.number), n.label,
                onCall = { a.placeCall(n.number) },
                onSms = { PhoneSettings.sms(a, n.number) },
                onLong = { PhoneSettings.copy(a, n.number) },
            )
        }
        sheet.divider()
        sheet.row(R.drawable.ic_account_circle, a.getString(R.string.dialer_open_contact)) { PhoneSettings.viewContact(a, ct.uri) }
        sheet.show()
    }

    // ---------------- list ----------------

    private inner class Adapter : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(position: Int) = shown[position]
        override fun getItemId(position: Int) = shown[position].id
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val row = convertView as? Row ?: Row(a)
            row.bind(shown[position], header(position))
            return row
        }
    }

    private inner class Row(ctx: Context) : LinearLayout(ctx) {
        private val headerView = TextView(ctx)
        private val avatar = AvatarView(ctx, 44)
        private val name = TextView(ctx)
        private val sub = TextView(ctx)
        private var contact: ContactsRepo.Contact? = null

        init {
            orientation = VERTICAL
            headerView.textSize = 14f
            headerView.setTextColor(c.primary)
            headerView.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            headerView.setPadding(ctx.dp(24), ctx.dp(14), ctx.dp(24), ctx.dp(4))
            addView(headerView)
            val line = LinearLayout(ctx).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = ctx.dp(64)
                setPadding(ctx.dp(16), ctx.dp(6), ctx.dp(8), ctx.dp(6))
                background = ctx.rippleRect()
                setOnClickListener { contact?.let { open(it) } }
            }
            line.addView(avatar, LayoutParams(ctx.dp(44), ctx.dp(44)))
            val texts = LinearLayout(ctx).apply { orientation = VERTICAL }
            name.textSize = 16.5f
            name.setTextColor(c.text)
            name.maxLines = 1
            name.ellipsize = TextUtils.TruncateAt.END
            sub.textSize = 13.5f
            sub.setTextColor(c.dim)
            sub.maxLines = 1
            sub.ellipsize = TextUtils.TruncateAt.END
            texts.addView(name)
            texts.addView(sub)
            line.addView(texts, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = ctx.dp(14) })
            line.addView(ctx.iconButton(R.drawable.ic_call, c.hit, ctx.getString(R.string.dialer_call)) {
                contact?.numbers?.firstOrNull()?.let { a.placeCall(it.number) }
            }, LayoutParams(ctx.dp(48), ctx.dp(48)))
            addView(line, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        fun bind(ct: ContactsRepo.Contact, header: String?) {
            contact = ct
            headerView.text = header.orEmpty()
            headerView.visibility = if (header == null) View.GONE else View.VISIBLE
            name.text = ct.name
            val first = ct.numbers.first()
            val more = if (ct.numbers.size > 1) "  +${ct.numbers.size - 1}" else ""
            sub.text = "${first.label} · ${PhoneSettings.format(a, first.number)}$more"
            avatar.bind(ct.name, ct.name, ct.photoUri)
        }
    }

    // ---------------- layout ----------------

    private fun build(): View {
        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        val row = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(a.dp(16), a.dp(4), a.dp(8), a.dp(8))
        }
        search = EditText(a).apply {
            hint = a.getString(R.string.search_contacts)
            setHintTextColor(c.dim)
            setTextColor(c.text)
            textSize = 16f
            maxLines = 1
            isSingleLine = true
            background = rounded(c.neutralContainer, a.dp(24).toFloat())
            setPadding(a.dp(16), a.dp(10), a.dp(16), a.dp(10))
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_search, 0, 0, 0)
            compoundDrawablePadding = a.dp(10)
            compoundDrawableTintList = ColorStateList.valueOf(c.dim)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) = applyFilter()
            })
        }
        row.addView(search, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(a.iconButton(R.drawable.ic_person_add, c.primary, a.getString(R.string.dialer_new_contact)) {
            PhoneSettings.newContact(a)
        }, LinearLayout.LayoutParams(a.dp(48), a.dp(48)).apply { marginStart = a.dp(4) })
        searchRow = row
        col.addView(row)
        perm = permissionBox(a, c, R.string.perm_contacts) {
            a.requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), DialerActivity.REQ_CONTACTS)
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
            adapter = this@ContactsPage.adapter
            divider = null
            selector = ColorDrawable(Color.TRANSPARENT)
            cacheColorHint = Color.TRANSPARENT
            isFastScrollEnabled = true
        }
        col.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return col
    }
}
