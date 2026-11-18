package com.aloanswer.alo_answer

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.Switch
import com.aloanswer.alo_answer.dialer.DialerActivity
import com.aloanswer.alo_answer.dialer.DialerRole
import com.aloanswer.alo_answer.dialer.dp
import com.aloanswer.alo_answer.ui.Btn
import com.aloanswer.alo_answer.ui.EventLogActivity
import com.aloanswer.alo_answer.ui.OnboardingActivity
import com.aloanswer.alo_answer.ui.Screen
import com.aloanswer.alo_answer.ui.TestListenActivity
import com.aloanswer.alo_answer.ui.XiaomiSetupActivity
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Home screen: master switch, default phone app, sensitivity, the last
 * loudspeaker result, auto speaker and the setup check. While a permission is
 * missing at launch, the permissions screen is shown instead.
 */
class MainActivity : Screen() {

    private companion object {
        const val REQ_ROLE = 42
    }

    /** Model copy / status checks touch ~100 MB of files: never on the main thread. */
    private val io = Executors.newSingleThreadExecutor()

    /** ModelStore.info(), or null until it is known (the first copy takes a few seconds). */
    private var model: Map<String, Any>? = null
    private var sensitivity = Settings.DEFAULT_SENSITIVITY
    private var dragging = false

    private val eventListener: (Map<String, Any>) -> Unit = { e ->
        if (e["type"] != EventLog.TEST_HIT) refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null && !PermissionHelper.allGranted(this)) {
            startActivity(Intent(this, OnboardingActivity::class.java).putExtra(OnboardingActivity.EXTRA_GATE, true))
            finish()
            return
        }
        // Copy the speech model out of the APK now, so the first call doesn't wait for it.
        io.execute {
            try {
                if (ModelStore.assetPresent(applicationContext)) ModelStore.ensureReady(applicationContext)
            } catch (_: Exception) {
            }
        }
        sensitivity = Settings.sensitivity(this)
        scaffold("Alo Answer", back = false)
        addLanguageAction()
        addAction(R.drawable.ic_list_alt, getString(R.string.ui_event_log)) { open(EventLogActivity::class.java) }
        EventLog.addListener(eventListener)
    }

    override fun onScreenResume() = refresh()

    override fun onDestroy() {
        EventLog.removeListener(eventListener)
        io.shutdown()
        super.onDestroy()
    }

    private fun open(cls: Class<out Activity>) = startActivity(Intent(this, cls))

    private fun refresh() {
        if (!built || io.isShutdown) return
        render()
        io.execute {
            val m: Map<String, Any> = try {
                ModelStore.info(applicationContext)
            } catch (e: Exception) {
                mapOf(
                    "modelAsset" to ModelStore.assetPresent(applicationContext),
                    "modelReady" to false,
                    "modelError" to "${e.javaClass.simpleName}: ${e.message}",
                )
            }
            runOnUiThread {
                if (!isDestroyed) {
                    model = m
                    render()
                }
            }
        }
    }

    // ---------------- rendering ----------------

    private fun render() {
        if (!built || dragging) return
        content.removeAllViews()
        val perms = PermissionHelper.statusAll(this)
        val allPerms = perms.values.all { it == PermissionHelper.GRANTED }
        val m = model
        val enabled = Settings.isEnabled(this)
        val dialerDefault = DialerRole.isDefault(this)
        val blockers = buildList {
            if (!allPerms) add(getString(R.string.ui_blocker_perms))
            if (m != null && m["modelAsset"] != true) {
                add(getString(R.string.ui_blocker_model))
            } else if (m == null || m["modelReady"] != true) {
                add(getString(R.string.ui_blocker_model_loading))
            }
        }

        if (!allPerms) permissionsBanner()
        masterCard(enabled, blockers)
        dialerCard(dialerDefault, DialerRole.isAvailable(this))
        sensitivityCard()
        speakerCard(Settings.lastSpeakerResult(this))
        if (!dialerDefault) autoSpeakerCard(SpeakerClickService.isEnabled(this))
        setupCheck(m, perms[PermissionHelper.OVERLAY] == PermissionHelper.GRANTED)

        content.addView(
            button(getString(R.string.ui_test_listening), Btn.FILLED) { open(TestListenActivity::class.java) },
            matchWrap().apply { topMargin = dp(4) }
        )
        content.addView(
            button(getString(R.string.ui_xiaomi_setup), Btn.OUTLINED) { open(XiaomiSetupActivity::class.java) },
            matchWrap().apply { topMargin = dp(8) }
        )
        content.addView(
            button(getString(R.string.ui_permissions), Btn.OUTLINED) { open(OnboardingActivity::class.java) },
            matchWrap().apply { topMargin = dp(8) }
        )
    }

    private fun permissionsBanner() {
        val v = card(bg = c.errorContainer)
        v.setPadding(dp(16), dp(6), dp(6), dp(6))
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(
            iconRow(R.drawable.ic_warning, c.onErrorContainer, getString(R.string.ui_perms_missing), c.onErrorContainer, 22),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        row.addView(button(getString(R.string.ui_fix), Btn.TEXT) { open(OnboardingActivity::class.java) })
        v.addView(row)
    }

    private fun masterCard(enabled: Boolean, blockers: List<String>) {
        val canEnable = blockers.isEmpty()
        val v = card()
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(text(getString(R.string.ui_master_title), 17f, medium = true))
        val subtitle = if (canEnable || enabled) {
            getString(if (enabled) R.string.ui_master_on else R.string.ui_master_off)
        } else {
            getString(R.string.ui_to_turn_on, blockers.joinToString(", "))
        }
        texts.addView(text(subtitle, 14f, c.dim), matchWrap().apply { topMargin = dp(2) })
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val switch = Switch(this).apply {
            isChecked = enabled
            isEnabled = canEnable || enabled
            setOnCheckedChangeListener { _, on ->
                Settings.save(this@MainActivity, mapOf(Settings.KEY_ENABLED to on))
                content.post { render() }
            }
        }
        row.addView(switch, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = dp(12) })
        if (switch.isEnabled) row.setOnClickListener { switch.toggle() }
        v.addView(row)
    }

    /** Become the default phone app: own call screen, direct speaker control. */
    private fun dialerCard(isDefault: Boolean, available: Boolean) {
        val v = section(getString(R.string.ui_dialer_title))
        v.addView(
            iconRow(
                if (isDefault) R.drawable.ic_check_circle else R.drawable.ic_phone_in_talk,
                if (isDefault) c.primary else c.dim,
                getString(
                    when {
                        isDefault -> R.string.ui_dialer_on
                        available -> R.string.ui_dialer_off
                        else -> R.string.ui_dialer_not_available
                    }
                )
            )
        )
        v.addView(
            buttonRow(
                button(getString(R.string.ui_open_dialer), Btn.OUTLINED) { open(DialerActivity::class.java) },
                if (!isDefault && available) {
                    button(getString(R.string.ui_make_default), Btn.FILLED) {
                        @Suppress("DEPRECATION") // RoleManager's intent is meant for startActivityForResult
                        startActivityForResult(DialerRole.requestIntent(this), REQ_ROLE)
                    }
                } else {
                    null
                },
            ),
            matchWrap().apply { topMargin = dp(12) }
        )
    }

    private fun sensitivityCard() {
        val v = card()
        val title = text(getString(R.string.ui_sensitivity, fmt(sensitivity)), 17f, medium = true)
        v.addView(title, matchWrap().apply { bottomMargin = dp(6) })
        val bar = SeekBar(this).apply {
            max = 20
            progress = Math.round(sensitivity * 20)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    sensitivity = p / 20f
                    title.text = getString(R.string.ui_sensitivity, fmt(sensitivity))
                }

                override fun onStartTrackingTouch(sb: SeekBar) {
                    dragging = true
                }

                override fun onStopTrackingTouch(sb: SeekBar) {
                    dragging = false
                    Settings.save(this@MainActivity, mapOf(Settings.KEY_SENSITIVITY to sensitivity))
                }
            })
        }
        v.addView(bar, matchWrap())
        v.addView(text(getString(R.string.ui_sensitivity_help), 14f, c.dim), matchWrap().apply { topMargin = dp(6) })
    }

    private fun fmt(v: Float) = String.format(Locale.US, "%.2f", v)

    /** Honest display of the last speaker-routing attempt (written by WakeWordService). */
    private fun speakerCard(result: String) {
        class Look(val head: Int, val body: Int, val icon: Int, val color: Int, val detail: String)

        val look = when {
            result.isEmpty() -> Look(
                R.string.ui_speaker_none_head, R.string.ui_speaker_none_body, R.drawable.ic_volume_off, c.dim, ""
            )
            result.startsWith("DIALER_OK: ") -> Look(
                R.string.ui_speaker_dialer_head, R.string.ui_speaker_dialer_body, R.drawable.ic_volume_up,
                c.primary, result.removePrefix("DIALER_OK: ")
            )
            result.startsWith("KEPT: ") -> Look(
                R.string.ui_speaker_kept_head, R.string.ui_speaker_kept_body, R.drawable.ic_bluetooth,
                c.primary, result.removePrefix("KEPT: ")
            )
            result.startsWith("CLICKED_OK: ") -> Look(
                R.string.ui_speaker_clicked_head, R.string.ui_speaker_clicked_body, R.drawable.ic_volume_up,
                c.primary, result.removePrefix("CLICKED_OK: ")
            )
            result.startsWith("REPORTED_OK: ") -> Look(
                R.string.ui_speaker_ok_head, R.string.ui_speaker_ok_body, R.drawable.ic_volume_up,
                c.primary, result.removePrefix("REPORTED_OK: ")
            )
            else -> Look(
                R.string.ui_speaker_fail_head, R.string.ui_speaker_fail_body, R.drawable.ic_volume_off,
                c.error, result.removePrefix("FAILED: ")
            )
        }
        val v = section(getString(R.string.ui_speaker_title))
        v.addView(iconRow(look.icon, look.color, getString(look.head), look.color, 22))
        val body = getString(look.body) + if (look.detail.isEmpty()) "" else "\n\n" + look.detail
        v.addView(text(body, 14.5f), matchWrap().apply { topMargin = dp(8) })
    }

    /** Setup of the accessibility service that presses the dialer's Speaker button (normal mode). */
    private fun autoSpeakerCard(enabled: Boolean) {
        val v = section(getString(R.string.ui_auto_speaker_title))
        v.addView(
            iconRow(
                if (enabled) R.drawable.ic_check_circle else R.drawable.ic_cancel,
                if (enabled) c.primary else c.error,
                getString(if (enabled) R.string.ui_auto_speaker_on else R.string.ui_auto_speaker_off)
            )
        )
        if (!enabled) {
            v.addView(text(getString(R.string.ui_auto_speaker_steps), 14f, c.dim), matchWrap().apply { topMargin = dp(8) })
        }
        v.addView(
            buttonRow(
                if (!enabled) button(getString(R.string.ui_open_app_info), Btn.OUTLINED) { XiaomiIntents.open(this, "app") } else null,
                button(getString(R.string.ui_open_accessibility), Btn.TONAL) { XiaomiIntents.open(this, "accessibility") },
            ),
            matchWrap().apply { topMargin = dp(12) }
        )
    }

    private fun setupCheck(m: Map<String, Any>?, overlay: Boolean) {
        val v = section(getString(R.string.ui_setup_check))
        if (m == null) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(4), 0, dp(4))
            }
            row.addView(
                ProgressBar(this, null, android.R.attr.progressBarStyleSmall),
                LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(10) }
            )
            row.addView(text(getString(R.string.ui_model_preparing)), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            v.addView(row)
        } else {
            v.addView(check(getString(R.string.ui_check_model_asset), m["modelAsset"] == true))
            v.addView(check(getString(R.string.ui_check_model_ready), m["modelReady"] == true))
            val words = (m["wakeWords"] as? List<*>)?.joinToString(", ").orEmpty()
            if (m["modelReady"] == true && words.isNotEmpty()) {
                val id = if (m["grammar"] == true) R.string.ui_listens_for_grammar else R.string.ui_listens_for
                v.addView(text(getString(id, words), 14f, c.dim), matchWrap().apply { marginStart = dp(30); bottomMargin = dp(4) })
            }
            (m["modelError"] as? String)?.takeIf { it.isNotEmpty() }?.let { v.addView(text(it, 14f, c.error)) }
        }
        v.addView(check(getString(R.string.ui_overlay_name), overlay))
        v.addView(check(getString(R.string.ui_check_battery), XiaomiIntents.isIgnoringBatteryOptimizations(this)))
        v.addView(
            text("${Build.MANUFACTURER} ${Build.MODEL} · Android SDK ${Build.VERSION.SDK_INT}", 13f, c.dim),
            matchWrap().apply { topMargin = dp(8) }
        )
    }
}
