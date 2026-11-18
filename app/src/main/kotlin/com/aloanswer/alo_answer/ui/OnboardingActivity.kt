package com.aloanswer.alo_answer.ui

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import com.aloanswer.alo_answer.MainActivity
import com.aloanswer.alo_answer.PermissionHelper
import com.aloanswer.alo_answer.R
import com.aloanswer.alo_answer.XiaomiIntents
import com.aloanswer.alo_answer.dialer.dp

/**
 * Explains each permission, requests it, and handles permanent denial.
 * First start ([EXTRA_GATE]): shown instead of the home screen until
 * everything is allowed. From the home screen: closes when done.
 */
class OnboardingActivity : Screen() {

    companion object {
        const val EXTRA_GATE = "gate"
        private const val REQ = 7
    }

    /** [special]: a Settings toggle rather than a dialog (Display over other apps). */
    private class Perm(val name: String, val icon: Int, val title: Int, val why: Int, val special: Boolean = false)

    private val perms = listOf(
        Perm("phone", R.drawable.ic_phone_in_talk, R.string.ui_perm_title_phone, R.string.ui_perm_why_phone),
        Perm("answer", R.drawable.ic_call, R.string.ui_perm_title_answer, R.string.ui_perm_why_answer),
        Perm("mic", R.drawable.ic_mic, R.string.ui_perm_title_mic, R.string.ui_perm_why_mic),
        Perm(
            "notifications", R.drawable.ic_notifications,
            R.string.ui_perm_title_notifications, R.string.ui_perm_why_notifications
        ),
        Perm(
            PermissionHelper.OVERLAY, R.drawable.ic_layers,
            R.string.ui_perm_title_overlay, R.string.ui_perm_why_overlay, special = true
        ),
    )

    private var status: Map<String, String> = emptyMap()
    private var busy = false
    private val gate get() = intent.getBooleanExtra(EXTRA_GATE, false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scaffold(getString(R.string.ui_permissions), back = !gate)
        addLanguageAction()
    }

    // Also runs when coming back from a Settings screen: re-check everything.
    override fun onScreenResume() = refresh()

    private fun refresh() {
        status = PermissionHelper.statusAll(this)
        render()
    }

    private fun render() {
        content.removeAllViews()
        content.addView(text(getString(R.string.ui_onboarding_intro), 16f), matchWrap().apply { bottomMargin = dp(16) })
        val required = PermissionHelper.required(this)
        for (p in perms) permCard(p, needed = p.name in required)
        val all = required.all { status[it] == PermissionHelper.GRANTED }
        content.addView(
            button(getString(if (all) R.string.ui_continue_btn else R.string.ui_allow_all_to_continue), Btn.FILLED) {
                done()
            }.enabled(all),
            matchWrap().apply { topMargin = dp(4) }
        )
    }

    private fun permCard(p: Perm, needed: Boolean) {
        val st = status[p.name]
        val granted = st == PermissionHelper.GRANTED
        if (!needed && !granted) {
            // Default phone app: our call screen starts listening itself, so this one isn't needed.
            val v = card()
            v.addView(iconRow(p.icon, c.dim, getString(p.title), c.dim, 24))
            v.addView(text(getString(R.string.ui_perm_not_needed_dialer), 14f, c.dim), matchWrap().apply { topMargin = dp(6) })
            return
        }
        val permanent = st == PermissionHelper.PERMANENTLY_DENIED
        val v = card()
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(ImageView(this).apply {
            setImageResource(p.icon)
            setColorFilter(if (granted) c.primary else c.dim)
        }, LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(12) })
        head.addView(text(getString(p.title), 17f, medium = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(ImageView(this).apply {
            setImageResource(if (granted) R.drawable.ic_check_circle else R.drawable.ic_radio_button_unchecked)
            setColorFilter(if (granted) c.primary else c.outline)
        }, LinearLayout.LayoutParams(dp(24), dp(24)))
        v.addView(head)
        v.addView(text(getString(p.why), 14.5f), matchWrap().apply { topMargin = dp(8) })
        if (permanent) {
            v.addView(text(getString(R.string.ui_permanently_denied), 14f, c.error), matchWrap().apply { topMargin = dp(8) })
        }
        if (p.special && !granted) {
            v.addView(text(getString(R.string.ui_overlay_hint), 14f, c.dim), matchWrap().apply { topMargin = dp(8) })
        }
        val label = when {
            granted -> R.string.ui_allowed
            p.special || permanent -> R.string.ui_open_settings
            else -> R.string.ui_allow
        }
        v.addView(
            buttonRow(button(getString(label), Btn.TONAL) { request(p) }.enabled(!granted && !busy)),
            matchWrap().apply { topMargin = dp(10) }
        )
    }

    private fun request(p: Perm) {
        if (p.special) {
            XiaomiIntents.open(this, "overlay") // a Settings toggle: re-checked on resume
            return
        }
        if (status[p.name] == PermissionHelper.PERMANENTLY_DENIED) {
            XiaomiIntents.open(this, "app")
            return
        }
        val perm = PermissionHelper.runtimePermission(p.name) ?: return refresh()
        PermissionHelper.markAsked(this, p.name)
        busy = true
        render()
        requestPermissions(arrayOf(perm), REQ)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        busy = false
        refresh()
    }

    private fun done() {
        if (gate) startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
