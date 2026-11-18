package com.aloanswer.alo_answer

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.Switch
import com.aloanswer.alo_answer.dialer.DialerActivity
import com.aloanswer.alo_answer.dialer.DialerRole
import com.aloanswer.alo_answer.dialer.PhoneSettings
import com.aloanswer.alo_answer.dialer.circle
import com.aloanswer.alo_answer.dialer.dp
import com.aloanswer.alo_answer.dialer.rounded
import com.aloanswer.alo_answer.ui.Btn
import com.aloanswer.alo_answer.ui.EventLogActivity
import com.aloanswer.alo_answer.ui.LanguagePicker
import com.aloanswer.alo_answer.ui.OnboardingActivity
import com.aloanswer.alo_answer.ui.Screen
import com.aloanswer.alo_answer.ui.TestListenActivity
import com.aloanswer.alo_answer.ui.XiaomiSetupActivity
import java.util.concurrent.Executors

/**
 * Settings of the app: voice answer on/off and sensitivity, the phone app,
 * language and setup help. While a permission is missing at launch, the
 * permissions screen is shown instead. As the default phone app, the app icon
 * opens the dial pad and this screen is one tap away from it (⋮).
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
        val fresh = savedInstanceState == null
        // Phone app mode: the app icon opens the dial pad, like any phone app.
        if (fresh && isLauncherStart() && DialerRole.isDefault(this) && PermissionHelper.allGranted(this)) {
            startActivity(Intent(this, DialerActivity::class.java).putExtra(DialerActivity.EXTRA_FROM_LAUNCHER, true))
            finish()
            return
        }
        if (fresh && !PermissionHelper.allGranted(this)) {
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
        // Opened from the dial pad's menu: a back arrow returns there.
        scaffold(if (DialerRole.isDefault(this)) getString(R.string.dialer_settings) else "Alo Answer", back = !isTaskRoot)
        EventLog.addListener(eventListener)
    }

    private fun isLauncherStart() =
        intent?.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_LAUNCHER)

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
        val required = PermissionHelper.required(this)
        val missing = required.count { perms[it] != PermissionHelper.GRANTED }
        val m = model
        val enabled = Settings.isEnabled(this)
        val dialerDefault = DialerRole.isDefault(this)
        val blockers = buildList {
            if (missing > 0) add(getString(R.string.ui_blocker_perms))
            if (m != null && m["modelAsset"] != true) {
                add(getString(R.string.ui_blocker_model))
            } else if (m == null || m["modelReady"] != true) {
                add(getString(R.string.ui_blocker_model_loading))
            }
        }

        if (missing > 0) permissionsBanner()
        masterCard(enabled, blockers)
        voiceCard()
        phoneAppCard(dialerDefault, DialerRole.isAvailable(this))
        speakerCard(Settings.lastSpeakerResult(this))
        if (!dialerDefault) autoSpeakerCard(SpeakerClickService.isEnabled(this))
        generalCard(missing)
        setupCheck(m, perms[PermissionHelper.OVERLAY] == PermissionHelper.GRANTED, dialerDefault)
        content.addView(
            text(
                "Alo Answer ${versionName()} · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}",
                12.5f, c.dim
            ).apply { gravity = Gravity.CENTER },
            matchWrap().apply { topMargin = dp(4) }
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

    /** The main switch, with a round icon that lights up when voice answer is on. */
    private fun masterCard(enabled: Boolean, blockers: List<String>) {
        val canEnable = blockers.isEmpty()
        val v = card()
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_record_voice_over)
            setColorFilter(if (enabled) c.onPrimary else c.dim)
            background = circle(if (enabled) c.primary else c.neutralContainer)
            val p = dp(12)
            setPadding(p, p, p, p)
        }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(14) })
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
            contentDescription = getString(R.string.ui_master_title)
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

    /** Sensitivity (as words, not numbers) and the listening test. */
    private fun voiceCard() {
        val v = listCard(getString(R.string.ui_voice_title))
        val block = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(6))
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(text(getString(R.string.ui_sensitivity_title), 16f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val level = text(levelName(sensitivity), 13.5f, c.onPrimaryContainer, medium = true).apply {
            background = rounded(c.primaryContainer, dp(12).toFloat())
            setPadding(dp(12), dp(4), dp(12), dp(4))
        }
        head.addView(level)
        block.addView(head)
        val bar = SeekBar(this).apply {
            max = 20
            progress = Math.round(sensitivity * 20)
            contentDescription = getString(R.string.ui_sensitivity_title)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    sensitivity = p / 20f
                    level.text = levelName(sensitivity)
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
        block.addView(bar, matchWrap().apply { topMargin = dp(8) })
        val ends = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        ends.addView(text(getString(R.string.ui_sens_low_hint), 12.5f, c.dim), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        ends.addView(text(getString(R.string.ui_sens_high_hint), 12.5f, c.dim).apply { gravity = Gravity.END },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        block.addView(ends, matchWrap().apply { topMargin = dp(2) })
        v.addView(block)
        rowDivider(v)
        listRow(v, R.drawable.ic_mic, getString(R.string.ui_test_listening), getString(R.string.ui_test_listening_sub)) {
            open(TestListenActivity::class.java)
        }
    }

    private fun levelName(s: Float) = getString(
        when {
            s < 0.2f -> R.string.ui_level_very_low
            s < 0.4f -> R.string.ui_level_low
            s < 0.6f -> R.string.ui_level_medium
            s < 0.8f -> R.string.ui_level_high
            else -> R.string.ui_level_very_high
        }
    )

    /** Default phone app status, the dial pad, and the phone's own call settings. */
    private fun phoneAppCard(isDefault: Boolean, available: Boolean) {
        val v = listCard(getString(R.string.ui_dialer_title))
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        if (isDefault) {
            box.addView(iconRow(R.drawable.ic_check_circle, c.primary, getString(R.string.ui_dialer_on_short)))
        } else {
            box.addView(text(getString(if (available) R.string.ui_dialer_off_short else R.string.ui_dialer_not_available), 14.5f))
            if (available) {
                box.addView(buttonRow(button(getString(R.string.ui_make_default), Btn.FILLED) {
                    @Suppress("DEPRECATION") // RoleManager's intent is meant for startActivityForResult
                    startActivityForResult(DialerRole.requestIntent(this), REQ_ROLE)
                }), matchWrap().apply { topMargin = dp(12) })
            }
        }
        v.addView(box)
        rowDivider(v)
        listRow(v, R.drawable.ic_dialpad, getString(R.string.ui_open_dialer), getString(R.string.ui_open_dialer_sub)) {
            open(DialerActivity::class.java)
        }
        if (!isDefault) return
        rowDivider(v)
        listRow(v, R.drawable.ic_phone_forwarded, getString(R.string.ui_call_settings), getString(R.string.ui_call_settings_sub)) {
            PhoneSettings.openCallSettings(this)
        }
        if (PhoneSettings.simCount(this) > 1) {
            rowDivider(v)
            listRow(v, R.drawable.ic_sim_card, getString(R.string.ui_sim_accounts), getString(R.string.ui_sim_accounts_sub)) {
                PhoneSettings.openAccounts(this)
            }
        }
        rowDivider(v)
        listRow(v, R.drawable.ic_block, getString(R.string.ui_blocked_numbers), getString(R.string.ui_blocked_numbers_sub)) {
            PhoneSettings.openBlocked(this)
        }
    }

    /** What happened to the audio after the last voice answer (written by WakeWordService). */
    private fun speakerCard(result: String) {
        class Look(val head: Int, val body: Int, val icon: Int, val color: Int)

        val look = when {
            result.isEmpty() -> Look(R.string.ui_speaker_none_head, R.string.ui_speaker_none_body, R.drawable.ic_volume_off, c.dim)
            result.startsWith("DIALER_OK: ") ->
                Look(R.string.ui_speaker_dialer_head, R.string.ui_speaker_dialer_body, R.drawable.ic_volume_up, c.primary)
            result.startsWith("KEPT: ") ->
                Look(R.string.ui_speaker_kept_head, R.string.ui_speaker_kept_body, R.drawable.ic_bluetooth, c.primary)
            result.startsWith("CLICKED_OK: ") ->
                Look(R.string.ui_speaker_clicked_head, R.string.ui_speaker_clicked_body, R.drawable.ic_volume_up, c.primary)
            result.startsWith("REPORTED_OK: ") ->
                Look(R.string.ui_speaker_ok_head, R.string.ui_speaker_ok_body, R.drawable.ic_volume_up, c.primary)
            else -> Look(R.string.ui_speaker_fail_head, R.string.ui_speaker_fail_body, R.drawable.ic_volume_off, c.error)
        }
        val v = section(getString(R.string.ui_speaker_title))
        v.addView(iconRow(look.icon, look.color, getString(look.head), look.color, 22))
        v.addView(text(getString(look.body), 14f, c.dim), matchWrap().apply { topMargin = dp(6) })
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

    /** Language, phone setup help, permissions and the event log. */
    private fun generalCard(missingPerms: Int) {
        val v = listCard(getString(R.string.ui_general))
        listRow(v, R.drawable.ic_translate, getString(R.string.ui_language), value = LanguagePicker.currentLabel(this)) {
            LanguagePicker.show(this)
        }
        rowDivider(v)
        listRow(v, R.drawable.ic_settings, getString(R.string.ui_xiaomi_setup), getString(R.string.ui_xiaomi_setup_sub)) {
            open(XiaomiSetupActivity::class.java)
        }
        rowDivider(v)
        val ok = missingPerms == 0
        listRow(
            v,
            if (ok) R.drawable.ic_check_circle else R.drawable.ic_warning,
            getString(R.string.ui_permissions),
            value = if (ok) getString(R.string.ui_perms_all_ok)
            else resources.getQuantityString(R.plurals.ui_perms_missing_n, missingPerms, missingPerms),
            tint = if (ok) c.primary else c.error,
        ) { open(OnboardingActivity::class.java) }
        rowDivider(v)
        listRow(v, R.drawable.ic_list_alt, getString(R.string.ui_event_log), getString(R.string.ui_event_log_sub)) {
            open(EventLogActivity::class.java)
        }
    }

    private fun setupCheck(m: Map<String, Any>?, overlay: Boolean, dialerDefault: Boolean) {
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
        // As the default phone app, our own call screen starts listening: no overlay needed.
        if (!dialerDefault) v.addView(check(getString(R.string.ui_overlay_name), overlay))
        v.addView(check(getString(R.string.ui_check_battery), XiaomiIntents.isIgnoringBatteryOptimizations(this)))
    }
}
