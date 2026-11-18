package com.aloanswer.alo_answer.dialer

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.database.ContentObserver
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.RippleDrawable
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import android.telecom.TelecomManager
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import com.aloanswer.alo_answer.MainActivity
import com.aloanswer.alo_answer.R
import com.aloanswer.alo_answer.Settings
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.Executors

/**
 * The phone app's main screen (default phone app mode): Recents, Contacts and
 * Keypad tabs. Handles ACTION_DIAL / tel: links (required for ROLE_DIALER).
 * Calls are always placed through TelecomManager.placeCall, which also covers
 * emergency numbers correctly. Framework views only, no extra libraries.
 */
class DialerActivity : Activity() {

    companion object {
        private const val REQ_CALL_PHONE = 7
        private const val REQ_CALL_LOG = 8
        private const val REQ_CONTACTS = 9
        private const val TAB_RECENTS = 0
        private const val TAB_CONTACTS = 1
        private const val TAB_KEYPAD = 2
        private val LETTERS = mapOf(
            '2' to "ABC", '3' to "DEF", '4' to "GHI", '5' to "JKL", '6' to "MNO",
            '7' to "PQRS", '8' to "TUV", '9' to "WXYZ", '0' to "+",
        )
        private val TONES = mapOf(
            '0' to ToneGenerator.TONE_DTMF_0, '1' to ToneGenerator.TONE_DTMF_1, '2' to ToneGenerator.TONE_DTMF_2,
            '3' to ToneGenerator.TONE_DTMF_3, '4' to ToneGenerator.TONE_DTMF_4, '5' to ToneGenerator.TONE_DTMF_5,
            '6' to ToneGenerator.TONE_DTMF_6, '7' to ToneGenerator.TONE_DTMF_7, '8' to ToneGenerator.TONE_DTMF_8,
            '9' to ToneGenerator.TONE_DTMF_9, '*' to ToneGenerator.TONE_DTMF_S, '#' to ToneGenerator.TONE_DTMF_P,
        )
        private val AVATAR_COLORS = intArrayOf(
            0xFF00897B.toInt(), 0xFF5E35B1.toInt(), 0xFF1E88E5.toInt(), 0xFFD81B60.toInt(),
            0xFF43A047.toInt(), 0xFFF4511E.toInt(), 0xFF6D4C41.toInt(), 0xFF546E7A.toInt(),
        )
    }

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var tones: ToneGenerator? = null
    private var pendingNumber: String? = null
    private var currentTab = TAB_RECENTS

    // keypad
    private lateinit var numberView: TextView
    private val number = StringBuilder()

    // tabs + nav
    private val tabViews = arrayOfNulls<View>(3)
    private val navIcons = arrayOfNulls<ImageView>(3)
    private val navLabels = arrayOfNulls<TextView>(3)

    // recents
    private lateinit var recentsList: ListView
    private lateinit var recentsPerm: View
    private lateinit var recentsEmpty: TextView
    private val recentsAdapter = RecentsAdapter()

    // contacts
    private lateinit var contactsList: ListView
    private lateinit var contactsPerm: View
    private lateinit var contactsEmpty: TextView
    private lateinit var searchBox: EditText
    private val contactsAdapter = ContactsAdapter()
    private var allContacts: List<ContactsRepo.Contact> = emptyList()

    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val dateFmt = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())

    private val callLogObserver = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean) {
            if (currentTab == TAB_RECENTS) loadRecents()
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(Settings.localized(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        edgeToEdge()
        setContentView(buildUi())
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        tones = try {
            ToneGenerator(AudioManager.STREAM_DTMF, 70)
        } catch (_: Exception) {
            null
        }
        if (CallLogRepo.granted(this)) {
            contentResolver.registerContentObserver(CallLog.Calls.CONTENT_URI, true, callLogObserver)
        }
        refreshTab()
    }

    override fun onPause() {
        tones?.release()
        tones = null
        contentResolver.unregisterContentObserver(callLogObserver)
        super.onPause()
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }

    /** ACTION_DIAL / tel: → Keypad with the number; otherwise Recents. */
    private fun handleIntent(intent: Intent?) {
        val data: Uri? = intent?.data
        if (data?.scheme == "tel") {
            number.clear()
            number.append(data.schemeSpecificPart.orEmpty())
            showNumber()
            selectTab(TAB_KEYPAD)
        } else if (intent?.action == Intent.ACTION_DIAL) {
            selectTab(TAB_KEYPAD)
        } else {
            selectTab(TAB_RECENTS)
        }
    }

    // ---------------- calling ----------------

    private fun placeCall(n: String) {
        val num = n.trim()
        if (num.isEmpty()) return
        if (checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            pendingNumber = num
            requestPermissions(arrayOf(Manifest.permission.CALL_PHONE), REQ_CALL_PHONE)
            return
        }
        try {
            getSystemService(TelecomManager::class.java).placeCall(Uri.fromParts("tel", num, null), Bundle())
        } catch (e: Exception) {
            Toast.makeText(this, "${e.javaClass.simpleName}: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun toKeypad(n: String) {
        number.clear()
        number.append(n)
        showNumber()
        selectTab(TAB_KEYPAD)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val ok = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        when (requestCode) {
            REQ_CALL_PHONE -> {
                val n = pendingNumber
                pendingNumber = null
                if (ok && n != null) placeCall(n)
                else Toast.makeText(this, R.string.dialer_need_permission, Toast.LENGTH_LONG).show()
            }
            REQ_CALL_LOG -> {
                if (ok) contentResolver.registerContentObserver(CallLog.Calls.CONTENT_URI, true, callLogObserver)
                loadRecents()
            }
            REQ_CONTACTS -> loadContacts()
        }
    }

    // ---------------- tabs ----------------

    private fun selectTab(tab: Int) {
        currentTab = tab
        for (i in 0..2) {
            tabViews[i]?.visibility = if (i == tab) View.VISIBLE else View.GONE
            val color = if (i == tab) Color.WHITE else 0x99FFFFFF.toInt()
            navIcons[i]?.setColorFilter(if (i == tab) 0xFF80CBC4.toInt() else color)
            navLabels[i]?.setTextColor(color)
        }
        refreshTab()
    }

    private fun refreshTab() {
        if (!::recentsList.isInitialized) return
        when (currentTab) {
            TAB_RECENTS -> loadRecents()
            TAB_CONTACTS -> loadContacts()
        }
    }

    private fun loadRecents() {
        val granted = CallLogRepo.granted(this)
        recentsPerm.visibility = if (granted) View.GONE else View.VISIBLE
        recentsList.visibility = if (granted) View.VISIBLE else View.GONE
        if (!granted) {
            recentsEmpty.visibility = View.GONE
            return
        }
        val app = applicationContext
        io.execute {
            val list = CallLogRepo.load(app)
            main.post {
                recentsAdapter.items = list
                recentsAdapter.notifyDataSetChanged()
                recentsEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun loadContacts() {
        val granted = ContactsRepo.granted(this)
        contactsPerm.visibility = if (granted) View.GONE else View.VISIBLE
        contactsList.visibility = if (granted) View.VISIBLE else View.GONE
        searchBox.visibility = if (granted) View.VISIBLE else View.GONE
        if (!granted) {
            contactsEmpty.visibility = View.GONE
            return
        }
        val app = applicationContext
        io.execute {
            val list = ContactsRepo.load(app)
            main.post {
                allContacts = list
                applyFilter()
            }
        }
    }

    private fun applyFilter() {
        val q = searchBox.text.toString().trim().lowercase()
        val digits = q.filter(Char::isDigit)
        val shown = if (q.isEmpty()) allContacts else allContacts.filter { c ->
            c.name.lowercase().contains(q) ||
                (digits.isNotEmpty() && c.numbers.any { it.number.filter(Char::isDigit).contains(digits) })
        }
        contactsAdapter.items = shown
        contactsAdapter.notifyDataSetChanged()
        contactsEmpty.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun callContact(c: ContactsRepo.Contact) {
        if (c.numbers.size == 1) {
            placeCall(c.numbers[0].number)
            return
        }
        AlertDialog.Builder(this)
            .setTitle(c.name)
            .setItems(c.numbers.map { "${it.label}: ${it.number}" }.toTypedArray()) { _, which ->
                placeCall(c.numbers[which].number)
            }
            .show()
    }

    // ---------------- formatting ----------------

    private fun whenText(ms: Long): String {
        val now = Calendar.getInstance()
        val then = Calendar.getInstance().apply { timeInMillis = ms }
        fun sameDay(a: Calendar, b: Calendar) =
            a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
        if (sameDay(now, then)) return timeFmt.format(ms)
        now.add(Calendar.DAY_OF_YEAR, -1)
        if (sameDay(now, then)) return getString(R.string.recents_yesterday) + ", " + timeFmt.format(ms)
        return dateFmt.format(ms)
    }

    private fun durationText(sec: Long) =
        if (sec >= 3600) "%d:%02d:%02d".format(sec / 3600, (sec / 60) % 60, sec % 60)
        else "%d:%02d".format(sec / 60, sec % 60)

    /** (icon, label, missed?) for a CallLog type. */
    private fun typeInfo(type: Int): Triple<Int, String, Boolean> = when (type) {
        CallLog.Calls.OUTGOING_TYPE -> Triple(R.drawable.ic_call_made, getString(R.string.calltype_outgoing), false)
        CallLog.Calls.MISSED_TYPE -> Triple(R.drawable.ic_call_missed, getString(R.string.calltype_missed), true)
        CallLog.Calls.REJECTED_TYPE, CallLog.Calls.BLOCKED_TYPE ->
            Triple(R.drawable.ic_call_missed, getString(R.string.calltype_rejected), true)
        else -> Triple(R.drawable.ic_call_received, getString(R.string.calltype_incoming), false)
    }

    // ---------------- adapters ----------------

    private inner class RecentsAdapter : BaseAdapter() {
        var items: List<CallLogRepo.Entry> = emptyList()
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = items[position].id
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val row = convertView as? Row ?: Row(this@DialerActivity)
            val e = items[position]
            val (icon, label, missed) = typeInfo(e.type)
            val title = e.name ?: e.number.ifEmpty { getString(R.string.incall_unknown) }
            row.bind(
                title = title,
                subtitle = buildString {
                    append(label).append(" · ").append(whenText(e.date))
                    if (!missed && e.durationSec > 0) append(" · ").append(durationText(e.durationSec))
                },
                subtitleIcon = icon,
                alert = missed,
                avatarSeed = e.name ?: e.number,
            )
            return row
        }
    }

    private inner class ContactsAdapter : BaseAdapter() {
        var items: List<ContactsRepo.Contact> = emptyList()
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = items[position].id
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val row = convertView as? Row ?: Row(this@DialerActivity)
            val c = items[position]
            val first = c.numbers.first()
            val more = if (c.numbers.size > 1) "  +${c.numbers.size - 1}" else ""
            row.bind(
                title = (if (c.starred) "★ " else "") + c.name,
                subtitle = "${first.label} · ${first.number}$more",
                subtitleIcon = 0,
                alert = false,
                avatarSeed = c.name,
            )
            return row
        }
    }

    /** List row: letter avatar, title, subtitle (with optional type icon), call icon. */
    private class Row(ctx: Context) : LinearLayout(ctx) {
        private val avatar = TextView(ctx)
        private val title = TextView(ctx)
        private val subtitle = TextView(ctx)

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val p = ctx.dp(12)
            setPadding(ctx.dp(16), p, ctx.dp(16), p)
            background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), null, ColorDrawable(Color.WHITE))

            avatar.gravity = Gravity.CENTER
            avatar.setTextColor(Color.WHITE)
            avatar.textSize = 18f
            avatar.setTypeface(avatar.typeface, Typeface.BOLD)
            addView(avatar, LayoutParams(ctx.dp(44), ctx.dp(44)))

            val texts = LinearLayout(ctx).apply { orientation = VERTICAL }
            title.textSize = 17f
            title.setTextColor(Color.WHITE)
            title.maxLines = 1
            title.ellipsize = TextUtils.TruncateAt.END
            subtitle.textSize = 13f
            subtitle.setTextColor(Palette.TEXT_DIM)
            subtitle.maxLines = 1
            subtitle.ellipsize = TextUtils.TruncateAt.END
            subtitle.compoundDrawablePadding = ctx.dp(4)
            texts.addView(title)
            texts.addView(subtitle)
            addView(texts, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = ctx.dp(14) })

            val callIcon = ImageView(ctx).apply {
                setImageResource(R.drawable.ic_call)
                setColorFilter(0xFF80CBC4.toInt())
            }
            addView(callIcon, LayoutParams(ctx.dp(24), ctx.dp(24)))
        }

        fun bind(title: String, subtitle: String, subtitleIcon: Int, alert: Boolean, avatarSeed: String) {
            this.title.text = title
            this.title.setTextColor(if (alert) 0xFFFF8A80.toInt() else Color.WHITE)
            this.subtitle.text = subtitle
            this.subtitle.setCompoundDrawablesRelativeWithIntrinsicBounds(subtitleIcon, 0, 0, 0)
            this.subtitle.compoundDrawableTintList =
                ColorStateList.valueOf(if (alert) 0xFFFF8A80.toInt() else Palette.TEXT_DIM)
            val letter = avatarSeed.trim().firstOrNull { it.isLetterOrDigit() }?.uppercaseChar() ?: '#'
            avatar.text = if (letter.isDigit()) "#" else letter.toString()
            avatar.background = circle(AVATAR_COLORS[Math.floorMod(avatarSeed.hashCode(), AVATAR_COLORS.size)])
        }
    }

    // ---------------- UI ----------------

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = screenBackground()
            padForSystemBars()
        }

        // top bar: app name + settings
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(8), dp(4), dp(4))
        }
        bar.addView(TextView(this).apply {
            text = getString(R.string.app_title)
            textSize = 22f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(ImageButton(this).apply {
            setImageResource(R.drawable.ic_more_vert)
            setColorFilter(Color.WHITE)
            background = ripple(circle(0x00000000))
            contentDescription = getString(R.string.dialer_settings)
            setOnClickListener { startActivity(Intent(this@DialerActivity, MainActivity::class.java)) }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        root.addView(bar)

        val content = FrameLayout(this)
        tabViews[TAB_RECENTS] = buildRecentsTab()
        tabViews[TAB_CONTACTS] = buildContactsTab()
        tabViews[TAB_KEYPAD] = buildKeypadTab()
        tabViews.forEach { content.addView(it, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)) }
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        root.addView(buildNav())
        return root
    }

    private fun permissionBox(message: Int, onAllow: () -> Unit): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(32), dp(48), dp(32), dp(16))
            addView(TextView(this@DialerActivity).apply {
                setText(message)
                textSize = 15f
                setTextColor(Palette.TEXT_DIM)
                gravity = Gravity.CENTER
            })
            addView(Button(this@DialerActivity).apply {
                setText(R.string.perm_allow)
                setTextColor(Color.WHITE)
                background = ripple(rounded(Palette.TEAL, dp(24).toFloat()))
                setPadding(dp(24), 0, dp(24), 0)
                setOnClickListener { onAllow() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)).apply { topMargin = dp(16) })
        }

    private fun emptyText(text: Int) = TextView(this).apply {
        setText(text)
        textSize = 15f
        setTextColor(Palette.TEXT_DIM)
        gravity = Gravity.CENTER
        setPadding(dp(32), dp(48), dp(32), dp(16))
        visibility = View.GONE
    }

    private fun listView() = ListView(this).apply {
        divider = null
        selector = ColorDrawable(Color.TRANSPARENT)
        cacheColorHint = Color.TRANSPARENT
        clipToPadding = false
    }

    private fun buildRecentsTab(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        recentsPerm = permissionBox(R.string.perm_call_log) {
            requestPermissions(arrayOf(Manifest.permission.READ_CALL_LOG), REQ_CALL_LOG)
        }
        recentsEmpty = emptyText(R.string.recents_empty)
        recentsList = listView().apply {
            adapter = recentsAdapter
            setOnItemClickListener { _, _, pos, _ -> recentsAdapter.items.getOrNull(pos)?.number?.let { placeCall(it) } }
            setOnItemLongClickListener { _, _, pos, _ ->
                recentsAdapter.items.getOrNull(pos)?.number?.let { toKeypad(it) }
                true
            }
        }
        col.addView(recentsPerm)
        col.addView(recentsEmpty)
        col.addView(recentsList, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return col
    }

    private fun buildContactsTab(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        searchBox = EditText(this).apply {
            hint = getString(R.string.search_contacts)
            setHintTextColor(Palette.TEXT_DIM)
            setTextColor(Color.WHITE)
            textSize = 16f
            maxLines = 1
            isSingleLine = true
            background = rounded(0x1FFFFFFF, dp(24).toFloat())
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_search, 0, 0, 0)
            compoundDrawablePadding = dp(10)
            compoundDrawableTintList = ColorStateList.valueOf(Palette.TEXT_DIM)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) = applyFilter()
            })
        }
        col.addView(searchBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { setMargins(dp(16), dp(4), dp(16), dp(8)) })
        contactsPerm = permissionBox(R.string.perm_contacts) {
            requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), REQ_CONTACTS)
        }
        contactsEmpty = emptyText(R.string.contacts_empty)
        contactsList = listView().apply {
            adapter = contactsAdapter
            setOnItemClickListener { _, _, pos, _ -> contactsAdapter.items.getOrNull(pos)?.let { callContact(it) } }
            setOnItemLongClickListener { _, _, pos, _ ->
                contactsAdapter.items.getOrNull(pos)?.numbers?.firstOrNull()?.let { toKeypad(it.number) }
                true
            }
        }
        col.addView(contactsPerm)
        col.addView(contactsEmpty)
        col.addView(contactsList, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return col
    }

    private fun showNumber() {
        if (::numberView.isInitialized) numberView.text = number
    }

    private fun press(c: Char) {
        number.append(c)
        showNumber()
        TONES[c]?.let { tones?.startTone(it, 120) }
    }

    private fun buildKeypadTab(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        col.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))

        val numRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(12), 0)
        }
        numberView = TextView(this).apply {
            textSize = 34f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.START
            text = number
        }
        numRow.addView(View(this), LinearLayout.LayoutParams(dp(48), 1))
        numRow.addView(numberView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        numRow.addView(ImageButton(this).apply {
            setImageResource(R.drawable.ic_backspace)
            setColorFilter(Palette.TEXT_DIM)
            background = ripple(circle(0x00000000))
            contentDescription = "⌫"
            setOnClickListener {
                if (number.isNotEmpty()) number.deleteCharAt(number.length - 1)
                showNumber()
            }
            setOnLongClickListener {
                number.clear()
                showNumber()
                true
            }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        col.addView(numRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { bottomMargin = dp(12) })

        col.addView(buildPad())

        val call = RoundButton(this, R.drawable.ic_call, getString(R.string.dialer_call), 72, 32, Palette.GREEN)
        call.onClick { placeCall(number.toString()) }
        col.addView(call, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(12); bottomMargin = dp(8) })
        return col
    }

    private fun buildPad(): View {
        val grid = GridLayout(this).apply { columnCount = 3 }
        for (c in "123456789*0#") {
            val key = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = ripple(circle(Palette.BUTTON))
                isClickable = true
                isFocusable = true
                contentDescription = c.toString()
                setOnClickListener {
                    it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    press(c)
                }
                if (c == '0') setOnLongClickListener {
                    press('+')
                    true
                }
            }
            key.addView(TextView(this).apply {
                text = c.toString()
                textSize = 28f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
            })
            LETTERS[c]?.let { letters ->
                key.addView(TextView(this).apply {
                    text = letters
                    textSize = 10f
                    letterSpacing = 0.15f
                    setTextColor(Palette.TEXT_DIM)
                    gravity = Gravity.CENTER
                })
            }
            grid.addView(key, GridLayout.LayoutParams().apply {
                width = dp(72)
                height = dp(72)
                setMargins(dp(12), dp(6), dp(12), dp(6))
            })
        }
        return grid
    }

    private fun buildNav(): View {
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, dp(4))
            background = ColorDrawable(0x33000000)
        }
        val items = listOf(
            R.drawable.ic_history to R.string.tab_recents,
            R.drawable.ic_people to R.string.tab_contacts,
            R.drawable.ic_dialpad to R.string.incall_keypad,
        )
        items.forEachIndexed { i, (icon, label) ->
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), null, ColorDrawable(Color.WHITE))
                isClickable = true
                contentDescription = getString(label)
                setOnClickListener { selectTab(i) }
            }
            val iv = ImageView(this).apply { setImageResource(icon) }
            val tv = TextView(this).apply {
                setText(label)
                textSize = 12f
                gravity = Gravity.CENTER
            }
            item.addView(iv, LinearLayout.LayoutParams(dp(24), dp(24)))
            item.addView(tv)
            navIcons[i] = iv
            navLabels[i] = tv
            nav.addView(item, LinearLayout.LayoutParams(0, dp(56), 1f))
        }
        return nav
    }
}
