package com.aloanswer.alo_answer.ui

import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import com.aloanswer.alo_answer.PermissionHelper
import com.aloanswer.alo_answer.R
import com.aloanswer.alo_answer.XiaomiIntents
import com.aloanswer.alo_answer.dialer.circle
import com.aloanswer.alo_answer.dialer.dp

/**
 * HyperOS / MIUI background setup with deep links where they exist.
 *
 * The Xiaomi screens are opened through undocumented component names; when a
 * HyperOS update moves them, the app falls back to App info and says so.
 */
class XiaomiSetupActivity : Screen() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scaffold(getString(R.string.ui_xiaomi_setup), back = true)
    }

    // Coming back from a settings screen: the battery and overlay lines update.
    override fun onScreenResume() = render()

    private fun render() {
        content.removeAllViews()
        if (!XiaomiIntents.isXiaomi) {
            card(bg = c.primaryContainer).addView(
                text(getString(R.string.ui_not_xiaomi, "${Build.MANUFACTURER} ${Build.MODEL}"), 15f, c.onPrimaryContainer)
            )
        }
        content.addView(text(getString(R.string.ui_xiaomi_intro), 15.5f), matchWrap().apply { bottomMargin = dp(16) })
        step(
            1, R.string.ui_step_autostart_title, R.string.ui_step_autostart_why, R.string.ui_step_autostart_path,
            R.string.ui_step_autostart_btn, "autostart"
        )
        step(
            2, R.string.ui_step_battery_title, R.string.ui_step_battery_why, R.string.ui_step_battery_path,
            R.string.ui_step_battery_btn, "battery",
            getString(
                if (XiaomiIntents.isIgnoringBatteryOptimizations(this)) R.string.ui_battery_reported_off
                else R.string.ui_battery_reported_on
            )
        )
        step(
            3, R.string.ui_step_other_title, R.string.ui_step_other_why, R.string.ui_step_other_path,
            R.string.ui_step_other_btn, "otherPermissions"
        )
        step(
            4, R.string.ui_step_overlay_title, R.string.ui_step_overlay_why, R.string.ui_step_overlay_path,
            R.string.ui_step_overlay_btn, "overlay",
            getString(
                if (PermissionHelper.status(this, PermissionHelper.OVERLAY) == PermissionHelper.GRANTED) {
                    R.string.ui_overlay_allowed
                } else {
                    R.string.ui_overlay_not_yet
                }
            )
        )
        step(5, R.string.ui_step_lock_title, R.string.ui_step_lock_why, R.string.ui_step_lock_path, null, null)
        content.addView(text(getString(R.string.ui_xiaomi_footer), 14f, c.dim), matchWrap().apply { topMargin = dp(4) })
    }

    private fun step(n: Int, title: Int, why: Int, path: Int, buttonText: Int?, target: String?, status: String? = null) {
        val v = card()
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(text("$n", 14f, c.onPrimaryContainer, medium = true).apply {
            gravity = Gravity.CENTER
            background = circle(c.primaryContainer)
        }, LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginEnd = dp(12) })
        head.addView(text(getString(title), 17f, medium = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        v.addView(head)
        v.addView(text(getString(why), 14.5f), matchWrap().apply { topMargin = dp(8) })
        v.addView(text(getString(path), 13.5f, c.dim), matchWrap().apply { topMargin = dp(8) })
        if (status != null) v.addView(text(status, 14f, c.primary), matchWrap().apply { topMargin = dp(8) })
        if (buttonText != null && target != null) {
            v.addView(
                buttonRow(button(getString(buttonText), Btn.TONAL) { open(target) }),
                matchWrap().apply { topMargin = dp(10) }
            )
        }
    }

    private fun open(target: String) {
        val msg = when (XiaomiIntents.open(this, target)) {
            "xiaomi" -> null
            "fallback" -> if (target == "overlay") null else getString(R.string.ui_xiaomi_fallback)
            else -> getString(R.string.ui_xiaomi_failed)
        }
        if (msg != null) Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }
}
