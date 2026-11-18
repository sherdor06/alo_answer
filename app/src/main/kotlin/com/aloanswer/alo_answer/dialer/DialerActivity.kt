package com.aloanswer.alo_answer.dialer

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.graphics.Color
import android.graphics.Typeface
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import android.provider.ContactsContract
import android.telecom.PhoneAccount
import android.telecom.TelecomManager
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import com.aloanswer.alo_answer.MainActivity
import com.aloanswer.alo_answer.R
import com.aloanswer.alo_answer.Settings
import com.aloanswer.alo_answer.ui.AppColors
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The phone app (default phone app mode): Recents, Contacts and Keypad tabs.
 * Opens on the keypad. Handles ACTION_DIAL / tel: links (required for
 * ROLE_DIALER) and the call log view intent. Calls are always placed through
 * TelecomManager.placeCall, which also covers emergency numbers correctly.
 * Light or dark like the rest of the app; framework views only.
 */
class DialerActivity : Activity() {

    companion object {
        /** Started by the app icon: when the screen is already open, stay where the user left off. */
        const val EXTRA_FROM_LAUNCHER = "from_launcher"
        /** "Add call" on the call screen. */
        const val EXTRA_ADD_CALL = "add_call"
        /** Missed-call notification: Recents, missed calls only. */
        const val EXTRA_SHOW_MISSED = "show_missed"

        const val TAB_RECENTS = 0
        const val TAB_CONTACTS = 1
        const val TAB_KEYPAD = 2

        const val REQ_CALL_LOG = 8
        const val REQ_CONTACTS = 9
        private const val REQ_CALL_PHONE = 7
        private const val REQ_WRITE_CALL_LOG = 10
        private const val REQ_WRITE_SILENT = 11
        private const val STATE_TAB = "tab"
        private const val STATE_NUMBER = "number"
        private const val STATE_MISSED = "missed_only"

        private val TONES = mapOf(
            '0' to ToneGenerator.TONE_DTMF_0, '1' to ToneGenerator.TONE_DTMF_1, '2' to ToneGenerator.TONE_DTMF_2,
            '3' to ToneGenerator.TONE_DTMF_3, '4' to ToneGenerator.TONE_DTMF_4, '5' to ToneGenerator.TONE_DTMF_5,
            '6' to ToneGenerator.TONE_DTMF_6, '7' to ToneGenerator.TONE_DTMF_7, '8' to ToneGenerator.TONE_DTMF_8,
            '9' to ToneGenerator.TONE_DTMF_9, '*' to ToneGenerator.TONE_DTMF_S, '#' to ToneGenerator.TONE_DTMF_P,
        )
    }

    lateinit var c: AppColors
        private set
    val io: ExecutorService = Executors.newSingleThreadExecutor()
    val main = Handler(Looper.getMainLooper())

    private var createdLang = ""
    var currentTab = TAB_KEYPAD
        private set
    private var addCallMode = false

    private lateinit var recents: RecentsPage
    private lateinit var contacts: ContactsPage
    private lateinit var keypad: KeypadPage
    private val pageViews = arrayOfNulls<View>(3)
    private val navIcons = arrayOfNulls<ImageView>(3)
    private val navLabels = arrayOfNulls<TextView>(3)
    private lateinit var badge: TextView
    private lateinit var titleView: TextView

    private var pendingCall: Uri? = null
    private var pendingWrite: (() -> Unit)? = null
    private var askedWrite = false
    private var tones: ToneGenerator? = null

    /** Contacts for the Contacts tab and smart dial; reloaded on resume and when they change. */
    var contactList: List<ContactsRepo.Contact> = emptyList()
        private set

    /** Calling account id → SIM name, only with two or more SIMs. */
    var simLabels: Map<String, String> = emptyMap()
        private set

    private val callLogObserver = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean) {
            recents.reload()
            updateBadge()
        }
    }
    private val contactsObserver = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean) = loadContacts()
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(Settings.localized(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createdLang = Settings.language(this)
        c = AppColors(this)
        edgeToEdge()
        recents = RecentsPage(this)
        contacts = ContactsPage(this)
        keypad = KeypadPage(this)
        setContentView(buildUi())
        if (savedInstanceState != null) {
            // Recreated (language change, rotation): keep the typed number and the tab.
            keypad.setNumber(savedInstanceState.getString(STATE_NUMBER).orEmpty())
            recents.setMissedOnly(savedInstanceState.getBoolean(STATE_MISSED, false))
            selectTab(savedInstanceState.getInt(STATE_TAB, TAB_KEYPAD))
        } else {
            handleIntent(intent, fresh = true)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent, fresh = false)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_TAB, currentTab)
        outState.putString(STATE_NUMBER, keypad.number())
        outState.putBoolean(STATE_MISSED, recents.missedOnly)
    }

    override fun onResume() {
        super.onResume()
        // Android 8–12: the language was changed on another screen (13+ recreates us itself).
        if (Settings.language(this) != createdLang) {
            recreate()
            return
        }
        tones = try {
            ToneGenerator(AudioManager.STREAM_DTMF, 70)
        } catch (_: Exception) {
            null
        }
        if (CallLogRepo.granted(this)) {
            contentResolver.registerContentObserver(CallLog.Calls.CONTENT_URI, true, callLogObserver)
        }
        if (ContactsRepo.granted(this)) {
            contentResolver.registerContentObserver(ContactsContract.Contacts.CONTENT_URI, true, contactsObserver)
        }
        val app = applicationContext
        io.execute {
            val labels = PhoneSettings.simLabels(app)
            main.post { simLabels = labels }
        }
        loadContacts()
        if (currentTab == TAB_RECENTS) recents.onShown() else recents.reload()
        updateBadge()
    }

    override fun onPause() {
        tones?.release()
        tones = null
        contentResolver.unregisterContentObserver(callLogObserver)
        contentResolver.unregisterContentObserver(contactsObserver)
        super.onPause()
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }

    private fun handleIntent(i: Intent?, fresh: Boolean) {
        addCallMode = i?.getBooleanExtra(EXTRA_ADD_CALL, false) == true
        renderTitle()
        val data: Uri? = i?.data
        when {
            data?.scheme == PhoneAccount.SCHEME_TEL -> {
                keypad.setNumber(data.schemeSpecificPart.orEmpty())
                selectTab(TAB_KEYPAD)
            }
            i?.getBooleanExtra(EXTRA_SHOW_MISSED, false) == true -> {
                recents.setMissedOnly(true)
                selectTab(TAB_RECENTS)
            }
            i?.type == CallLog.Calls.CONTENT_TYPE -> selectTab(TAB_RECENTS)
            // The app icon while we were already open: back to where the user was.
            !fresh && i?.getBooleanExtra(EXTRA_FROM_LAUNCHER, false) == true -> Unit
            else -> {
                if (addCallMode) keypad.setNumber("")
                selectTab(TAB_KEYPAD)
            }
        }
    }

    // ---------------- calling ----------------

    fun placeCall(number: String) {
        val n = number.trim()
        if (n.isEmpty()) return
        place(Uri.fromParts(PhoneAccount.SCHEME_TEL, n, null))
    }

    /** Long-press 1. */
    fun placeVoicemail() = place(Uri.fromParts(PhoneAccount.SCHEME_VOICEMAIL, "", null))

    private fun place(uri: Uri) {
        if (checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            pendingCall = uri
            requestPermissions(arrayOf(Manifest.permission.CALL_PHONE), REQ_CALL_PHONE)
            return
        }
        try {
            getSystemService(TelecomManager::class.java).placeCall(uri, Bundle())
        } catch (e: Exception) {
            Toast.makeText(this, "${e.javaClass.simpleName}: ${e.message}", Toast.LENGTH_LONG).show()
            return
        }
        if (addCallMode) {
            addCallMode = false
            renderTitle()
        }
    }

    fun tone(ch: Char) {
        TONES[ch]?.let { tones?.startTone(it, 120) }
    }

    fun toKeypad(number: String) {
        keypad.setNumber(number)
        selectTab(TAB_KEYPAD)
    }

    /** Runs [action] once WRITE_CALL_LOG is granted (deleting calls), asking for it if needed. */
    fun withCallLogWrite(action: () -> Unit) {
        if (CallLogRepo.canWrite(this)) {
            action()
            return
        }
        pendingWrite = action
        requestPermissions(arrayOf(Manifest.permission.WRITE_CALL_LOG), REQ_WRITE_CALL_LOG)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val ok = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        when (requestCode) {
            REQ_CALL_PHONE -> {
                val uri = pendingCall
                pendingCall = null
                if (ok && uri != null) place(uri)
                else Toast.makeText(this, R.string.dialer_need_permission, Toast.LENGTH_LONG).show()
            }
            REQ_CALL_LOG -> {
                if (ok) contentResolver.registerContentObserver(CallLog.Calls.CONTENT_URI, true, callLogObserver)
                recents.reload()
                updateBadge()
            }
            REQ_CONTACTS -> {
                if (ok) contentResolver.registerContentObserver(ContactsContract.Contacts.CONTENT_URI, true, contactsObserver)
                loadContacts()
                recents.reload() // names and photos
            }
            REQ_WRITE_CALL_LOG -> {
                val action = pendingWrite
                pendingWrite = null
                if (ok) action?.invoke()
                else Toast.makeText(this, R.string.dialer_need_write_call_log, Toast.LENGTH_LONG).show()
            }
            REQ_WRITE_SILENT -> if (ok && currentTab == TAB_RECENTS) markMissedRead()
        }
    }

    // ---------------- data ----------------

    fun loadContacts() {
        if (!ContactsRepo.granted(this)) {
            contactList = emptyList()
            contacts.onContactsChanged()
            keypad.onContactsChanged()
            return
        }
        val app = applicationContext
        io.execute {
            val list = ContactsRepo.load(app)
            main.post {
                if (isDestroyed) return@post
                contactList = list
                contacts.onContactsChanged()
                keypad.onContactsChanged()
            }
        }
    }

    /** Recent numbers that aren't contacts, for smart dial. */
    fun recentNumbers(): List<CallLogRepo.Entry> = recents.unknownNumbers()

    fun updateBadge() {
        val app = applicationContext
        io.execute {
            val n = CallLogRepo.newMissedCount(app)
            main.post {
                if (!::badge.isInitialized) return@post
                badge.text = if (n > 99) "99+" else n.toString()
                badge.visibility = if (n > 0 && currentTab != TAB_RECENTS) View.VISIBLE else View.GONE
            }
        }
    }

    /** Seen in Recents: missed calls are no longer new; the notification and the badge go away. */
    fun markMissedRead() {
        MissedCalls.cancel(this)
        // Clearing the "new" flag needs WRITE_CALL_LOG. It is in the same group as READ_CALL_LOG,
        // so Android grants it without asking once call history is allowed.
        if (CallLogRepo.granted(this) && !CallLogRepo.canWrite(this) && !askedWrite) {
            askedWrite = true
            requestPermissions(arrayOf(Manifest.permission.WRITE_CALL_LOG), REQ_WRITE_SILENT)
            return
        }
        val app = applicationContext
        io.execute {
            if (CallLogRepo.newMissedCount(app) == 0) return@execute
            MissedCalls.markReadBlocking(app)
            main.post { updateBadge() }
        }
    }

    // ---------------- tabs ----------------

    private fun selectTab(tab: Int) {
        currentTab = tab
        for (i in 0..2) {
            val on = i == tab
            pageViews[i]?.visibility = if (on) View.VISIBLE else View.GONE
            navIcons[i]?.setColorFilter(if (on) c.onPrimaryContainer else c.dim)
            navIcons[i]?.background = if (on) rounded(c.primaryContainer, dp(16).toFloat()) else null
            navLabels[i]?.setTextColor(if (on) c.text else c.dim)
            navLabels[i]?.typeface = Typeface.create(if (on) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
        }
        when (tab) {
            TAB_RECENTS -> {
                recents.onShown()
                if (::badge.isInitialized) badge.visibility = View.GONE
            }
            TAB_CONTACTS -> contacts.onShown()
            TAB_KEYPAD -> keypad.onShown()
        }
        updateBadge()
    }

    private fun renderTitle() {
        if (!::titleView.isInitialized) return
        titleView.text = if (addCallMode) getString(R.string.incall_add_call) else getString(R.string.app_title)
    }

    // ---------------- menu ----------------

    private fun showMenu(anchor: View) {
        val menu = PopupMenu(this, anchor)
        val isDefault = DialerRole.isDefault(this)
        if (currentTab == TAB_RECENTS && CallLogRepo.granted(this) && recents.hasCalls()) {
            menu.menu.add(0, 1, 0, R.string.dialer_clear_history)
        }
        if (isDefault) {
            menu.menu.add(0, 2, 1, R.string.ui_call_settings)
            menu.menu.add(0, 3, 2, R.string.ui_blocked_numbers)
        }
        menu.menu.add(0, 4, 3, R.string.dialer_settings)
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> confirmClearHistory()
                2 -> PhoneSettings.openCallSettings(this)
                3 -> PhoneSettings.openBlocked(this)
                4 -> startActivity(Intent(this, MainActivity::class.java))
            }
            true
        }
        menu.show()
    }

    private fun confirmClearHistory() {
        AlertDialog.Builder(this)
            .setTitle(R.string.dialer_clear_history_q)
            .setNegativeButton(R.string.ui_cancel, null)
            .setPositiveButton(R.string.ui_clear) { _, _ ->
                withCallLogWrite {
                    val app = applicationContext
                    io.execute {
                        CallLogRepo.clearAll(app)
                        main.post { recents.reload() }
                    }
                }
            }
            .show()
    }

    // ---------------- UI ----------------

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(c.bg)
            padForSystemBars()
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(60)
            setPadding(dp(20), 0, dp(4), 0)
        }
        titleView = TextView(this).apply {
            textSize = 22f
            setTextColor(c.text)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        bar.addView(titleView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        lateinit var more: View
        more = iconButton(R.drawable.ic_more_vert, c.text, getString(R.string.dialer_more)) { showMenu(more) }
        bar.addView(more, LinearLayout.LayoutParams(dp(48), dp(48)))
        root.addView(bar)
        renderTitle()

        val content = FrameLayout(this)
        pageViews[TAB_RECENTS] = recents.view
        pageViews[TAB_CONTACTS] = contacts.view
        pageViews[TAB_KEYPAD] = keypad.view
        pageViews.forEach { v ->
            content.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(View(this).apply { setBackgroundColor(c.divider) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
        root.addView(buildNav())
        return root
    }

    private fun buildNav(): View {
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(c.nav)
            setPadding(0, dp(8), 0, dp(6))
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
                isClickable = true
                contentDescription = getString(label)
                background = rippleRect()
                setOnClickListener { selectTab(i) }
            }
            val iconBox = FrameLayout(this)
            val iv = ImageView(this).apply {
                setImageResource(icon)
                scaleType = ImageView.ScaleType.CENTER
            }
            iconBox.addView(iv, FrameLayout.LayoutParams(dp(60), dp(32), Gravity.CENTER))
            if (i == TAB_RECENTS) {
                badge = TextView(this).apply {
                    textSize = 10.5f
                    setTextColor(Color.WHITE)
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    minWidth = dp(18)
                    setPadding(dp(4), 0, dp(4), 0)
                    background = rounded(c.missed, dp(9).toFloat())
                    visibility = View.GONE
                }
                iconBox.addView(badge, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(18), Gravity.TOP or Gravity.END
                ).apply { marginEnd = dp(6) })
            }
            item.addView(iconBox, LinearLayout.LayoutParams(dp(64), dp(32)))
            val tv = TextView(this).apply {
                setText(label)
                textSize = 12.5f
                gravity = Gravity.CENTER
                setPadding(0, dp(4), 0, 0)
            }
            item.addView(tv)
            navIcons[i] = iv
            navLabels[i] = tv
            nav.addView(item, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        return nav
    }
}
