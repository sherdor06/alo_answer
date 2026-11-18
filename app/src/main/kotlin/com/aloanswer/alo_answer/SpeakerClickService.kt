package com.aloanswer.alo_answer

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.telecom.TelecomManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Optional accessibility service: right after the app answers a call, it
 * presses the in-call screen's own "Speaker" button.
 *
 * Why: a non-dialer app can't move a SIM call to the loudspeaker through
 * AudioManager (tested on a Redmi: setCommunicationDevice(SPEAKER) returned
 * true, but the call stayed on BUILTIN_EARPIECE). Pressing the dialer's
 * button is what the user's finger would do, so the dialer routes it itself.
 *
 * Behaviour/privacy: every event is ignored unless WakeWordService asked for
 * a press in the last few seconds. It only looks for a button on the in-call
 * screen and never stores screen content.
 */
class SpeakerClickService : AccessibilityService() {

    data class Result(val ok: Boolean, val detail: String)

    companion object {
        @Volatile
        var instance: SpeakerClickService? = null
            private set

        /** Packages that draw the in-call screen on common ROMs (the default dialer is added at runtime). */
        private val IN_CALL_PACKAGES = setOf(
            "com.android.incallui",          // AOSP, MIUI / HyperOS
            "com.android.contacts",          // MIUI phone app
            "com.android.phone",
            "com.android.dialer",
            "com.google.android.dialer",
            "com.samsung.android.incallui",
        )

        /** "Speaker" in English, Russian and Uzbek (lowercase, substring match on label/description). */
        private val LABELS = listOf(
            "speaker", "loudspeaker",
            "динамик", "громкая", "громкоговоритель",
            "karnay", "dinamik",
        )

        private const val RETRY_MS = 400L
        private const val VERIFY_MS = 700L

        /** Whether the user has turned this service on in Accessibility settings. */
        fun isEnabled(ctx: Context): Boolean {
            val am = ctx.getSystemService(AccessibilityManager::class.java) ?: return false
            val me = ComponentName(ctx, SpeakerClickService::class.java)
            return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any {
                val si = it.resolveInfo?.serviceInfo
                si != null && si.packageName == me.packageName && si.name == me.className
            }
        }
    }

    private class Job(val done: (Result) -> Unit) {
        var pressed = false
    }

    private val handler = Handler(Looper.getMainLooper())
    private var job: Job? = null
    private val scanRunnable = Runnable { scan() }
    private val giveUp = Runnable { finish(Result(false, notFoundDetail())) }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onInterrupt() = Unit

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val j = job ?: return // idle: ignore everything
        if (j.pressed) return
        handler.removeCallbacks(scanRunnable)
        handler.postDelayed(scanRunnable, 150) // coalesce bursts of window events
    }

    /**
     * Main thread. Looks for the Speaker button for up to [timeoutMs], presses
     * it once, then checks the audio route. Calls [done] exactly once.
     */
    fun pressSpeaker(timeoutMs: Long, done: (Result) -> Unit) {
        if (job != null) finish(Result(false, "superseded"))
        job = Job(done)
        handler.postDelayed(giveUp, timeoutMs)
        handler.post(scanRunnable)
    }

    private fun scan() {
        val j = job ?: return
        if (j.pressed) return
        if (SpeakerRouter.isSpeakerOn(this)) {
            finish(Result(true, "already on → ${SpeakerRouter.describe(this)}"))
            return
        }
        val node = findSpeakerButton()
        if (node == null) {
            handler.postDelayed(scanRunnable, RETRY_MS) // the ongoing-call screen may not be up yet
            return
        }
        @Suppress("DEPRECATION")
        if (node.isCheckable && node.isChecked) {
            finish(Result(true, "button already on → ${SpeakerRouter.describe(this)}"))
            return
        }
        if (!click(node)) {
            handler.postDelayed(scanRunnable, RETRY_MS)
            return
        }
        // Pressed once; never press again (a second press would turn it off).
        j.pressed = true
        handler.removeCallbacks(giveUp)
        val label = describeNode(node)
        handler.postDelayed({ verify(label, 0) }, VERIFY_MS)
    }

    private fun verify(label: String, round: Int) {
        if (job == null) return
        val on = SpeakerRouter.isSpeakerOn(this)
        if (on || round >= 3) {
            finish(Result(on, "pressed “$label” → ${SpeakerRouter.describe(this)}"))
        } else {
            handler.postDelayed({ verify(label, round + 1) }, VERIFY_MS)
        }
    }

    private fun finish(r: Result) {
        val j = job ?: return
        job = null
        handler.removeCallbacks(scanRunnable)
        handler.removeCallbacks(giveUp)
        j.done(r)
    }

    // ---------------- finding and pressing the button ----------------

    private fun inCallPackages(): Set<String> {
        val dialer = try {
            getSystemService(TelecomManager::class.java)?.defaultDialerPackage
        } catch (_: Exception) {
            null
        }
        return if (dialer != null) IN_CALL_PACKAGES + dialer else IN_CALL_PACKAGES
    }

    private fun roots(): List<AccessibilityNodeInfo> {
        val out = mutableListOf<AccessibilityNodeInfo>()
        try {
            windows?.forEach { w -> w.root?.let(out::add) }
        } catch (_: Exception) {
        }
        rootInActiveWindow?.let(out::add)
        return out
    }

    private fun inCallRoots(): List<AccessibilityNodeInfo> {
        val pkgs = inCallPackages()
        return roots().filter { it.packageName?.toString() in pkgs }
    }

    private fun findSpeakerButton(): AccessibilityNodeInfo? {
        for (root in inCallRoots()) find(root, 0)?.let { return it }
        return null
    }

    private fun find(node: AccessibilityNodeInfo, depth: Int): AccessibilityNodeInfo? {
        if (depth > 40) return null
        if (node.isVisibleToUser && matches(node)) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            find(child, depth + 1)?.let { return it }
        }
        return null
    }

    private fun matches(n: AccessibilityNodeInfo): Boolean {
        val id = n.viewIdResourceName?.substringAfter(":id/")?.lowercase().orEmpty()
        if ("speaker" in id) return true
        val label = "${n.contentDescription ?: ""} ${n.text ?: ""}".lowercase()
        return LABELS.any { it in label }
    }

    /** Clicks the node or its nearest clickable ancestor; falls back to a tap gesture on its bounds. */
    private fun click(node: AccessibilityNodeInfo): Boolean {
        var n: AccessibilityNodeInfo? = node
        var depth = 0
        while (n != null && depth < 6) {
            if (n.isClickable && n.isEnabled) return n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            n = n.parent
            depth++
        }
        val r = Rect()
        node.getBoundsInScreen(r)
        if (r.isEmpty) return false
        val path = Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()) }
        val tap = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            .build()
        return dispatchGesture(tap, null, null)
    }

    private fun describeNode(n: AccessibilityNodeInfo): String =
        n.viewIdResourceName?.substringAfter(":id/")
            ?: (n.contentDescription ?: n.text)?.toString()?.take(24)
            ?: "?"

    /** When nothing matched: list the in-call screen's buttons (ids / short labels) so the matcher can be adjusted. */
    private fun notFoundDetail(): String {
        val inCall = inCallRoots()
        if (inCall.isEmpty()) {
            val seen = roots().mapNotNull { it.packageName?.toString() }.distinct()
            return "no in-call screen found; windows: ${seen.joinToString()}"
        }
        val buttons = mutableListOf<String>()
        fun walk(n: AccessibilityNodeInfo, d: Int) {
            if (d > 40 || buttons.size >= 12) return
            if (n.isClickable) {
                val name = n.viewIdResourceName?.substringAfter(":id/")
                    ?: n.contentDescription?.toString()?.take(20)
                if (!name.isNullOrBlank()) buttons += name
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, d + 1) }
        }
        inCall.forEach { walk(it, 0) }
        return "Speaker button not found in ${inCall.mapNotNull { it.packageName }.distinct().joinToString()}; " +
            "buttons: ${buttons.distinct().joinToString()}"
    }
}
