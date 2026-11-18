package com.aloanswer.alo_answer

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import com.aloanswer.alo_answer.dialer.CallManager
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * Microphone foreground service running the offline Vosk recognizer
 * (Uzbek model, no account or key, no internet).
 *
 * Modes:
 *  - ARM:  started by ArmActivity while the phone rings. On wake word ->
 *          release mic -> acceptRingingCall() -> best-effort speaker -> stop.
 *  - TEST: started from the app UI. Emits test_hit events, never answers.
 *
 * Both start paths come from a VISIBLE activity of this app, which is what
 * lets a microphone-type FGS actually use the mic.
 *
 * The mic is released on: wake word (ARM), call OFFHOOK/IDLE, timeout,
 * explicit stop, error, or onDestroy. Never left open.
 *
 * Loading the model and creating/stopping the recognizer run on a single
 * worker thread; all state lives on the main thread, where Vosk's
 * SpeechService also delivers its callbacks.
 */
class WakeWordService : Service() {

    companion object {
        const val ACTION_ARM = "com.aloanswer.alo_answer.ARM"
        const val ACTION_TEST = "com.aloanswer.alo_answer.TEST"
        const val ACTION_STOP = "com.aloanswer.alo_answer.STOP"

        private const val CHANNEL_ID = "listening"
        private const val NOTIF_ID = 1002
        private const val ARM_TIMEOUT_MS = 30_000L
        private const val TEST_TIMEOUT_MS = 60_000L
        private const val SPEAKER_DELAY_MS = 800L
        private const val SPEAKER_VERIFY_DELAY_MS = 1_500L
        /** How long a connected Bluetooth device / headset gets to take the answered call. */
        private const val HEADSET_WAIT_MS = 2_000L
        private const val SPEAKER_CLICK_TIMEOUT_MS = 8_000L

        @Volatile
        var instance: WakeWordService? = null
            private set

        /** One-shot callback for ArmActivity: called once startForeground() succeeded (true) or failed. */
        @Volatile
        var onForegroundResult: ((Boolean) -> Unit)? = null

        val isRunning get() = instance != null
        val isTesting get() = instance?.mode == Mode.TEST
        val isArmed get() = instance?.mode == Mode.ARM
    }

    private enum class Mode { ARM, TEST }

    /** Everything Vosk holds natively for one listening session. */
    private class Session(val opened: ModelStore.Opened, val speech: SpeechService) {
        fun release() {
            try { speech.stop() } catch (_: Exception) {}     // joins the audio thread
            try { speech.shutdown() } catch (_: Exception) {} // releases AudioRecord
            opened.close()                                    // recognizer + model
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()

    private var session: Session? = null
    private var initializing = false
    /** Bumped whenever the current mic session is torn down; stale callbacks compare against it. */
    private var generation = 0
    private var mode: Mode? = null
    private var answering = false
    /** DEBUG BUILDS ONLY: true while a simulated detector stands in for Vosk (see DebugReceiver). */
    private var fakeDetector = false
    private var destroyed = false

    /** Localized text in the in-app language (works with no screen open). */
    private fun str(id: Int, vararg args: Any) = Settings.str(this, id, *args)

    private val timeout = Runnable {
        EventLog.add(this, EventLog.TIMEOUT, str(R.string.ev_timeout), persist = mode == Mode.ARM)
        shutdown()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP) {
            EventLog.add(this, EventLog.STOPPED, str(R.string.ev_stopped), persist = mode == Mode.ARM)
            shutdown()
            return START_NOT_STICKY
        }
        val newMode = when (action) {
            ACTION_ARM -> Mode.ARM
            ACTION_TEST -> Mode.TEST
            else -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }

        // Must happen promptly after startForegroundService().
        val inForeground = enterForeground(newMode)
        notifyForegroundResult(inForeground)
        if (!inForeground) return START_NOT_STICKY

        if (newMode == Mode.ARM && !ringingNow()) {
            EventLog.add(this, EventLog.STOPPED, str(R.string.ev_not_ringing))
            shutdown()
            return START_NOT_STICKY
        }

        mode = newMode
        answering = false
        handler.removeCallbacks(timeout)
        handler.postDelayed(timeout, if (newMode == Mode.ARM) ARM_TIMEOUT_MS else TEST_TIMEOUT_MS)

        if (session == null && !initializing && !fakeDetector) {
            startDetector()
        } else if (session != null || fakeDetector) {
            logListening() // already listening, just switched mode
        }
        return START_NOT_STICKY
    }

    /** Ringing per our own call list (default phone app) or the PHONE_STATE broadcast. */
    private fun ringingNow() =
        CallManager.hasRingingCall() || Settings.lastCallState(this) == TelephonyManager.EXTRA_STATE_RINGING

    private fun notifyForegroundResult(ok: Boolean) {
        val cb = onForegroundResult
        onForegroundResult = null
        cb?.invoke(ok)
    }

    private fun enterForeground(forMode: Mode): Boolean {
        val nm = getSystemService(NotificationManager::class.java)
        // Re-creating an existing channel only updates its name/description (current language).
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, str(R.string.notif_listen_channel), NotificationManager.IMPORTANCE_LOW)
                .apply { description = str(R.string.notif_listen_channel_desc) }
        )
        val stopPi = PendingIntent.getService(
            this, 2, Intent(this, WakeWordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle(str(if (forMode == Mode.ARM) R.string.notif_listen_title_call else R.string.notif_listen_title_test))
            .setContentText(str(R.string.notif_listen_text))
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_stat_mic), str(R.string.notif_action_stop), stopPi
                ).build()
            )
            .build()
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIF_ID, n)
            }
            true
        } catch (e: Exception) {
            // SecurityException (while-in-use mic restriction) or
            // ForegroundServiceStartNotAllowedException (API 31+, an IllegalStateException).
            fail(EventLog.MIC_BLOCKED, "${e.javaClass.simpleName}: ${e.message}", forMode == Mode.ARM)
            stopSelf()
            false
        }
    }

    private fun startDetector() {
        if (Settings.debugFakeDetector(this)) {
            // DEBUG BUILDS ONLY: no Vosk and the mic is not opened.
            // The wake word is simulated with DebugReceiver's SAY_ALO broadcast.
            fakeDetector = true
            logListening()
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            fail(EventLog.ERROR, str(R.string.ev_no_mic_perm)); shutdown(); return
        }
        if (!ModelStore.assetPresent(this)) {
            fail(EventLog.ERROR, str(R.string.ev_missing_model, ModelStore.ASSET_DIR)); shutdown(); return
        }
        val sensitivity = Settings.sensitivity(this)
        val gen = ++generation
        initializing = true

        onWorker {
            var opened: ModelStore.Opened? = null
            try {
                opened = ModelStore.open(applicationContext) // copies the model the first time, then loads it
                val speech = SpeechService(opened.recognizer, ModelStore.SAMPLE_RATE) // VOICE_RECOGNITION mic source
                val s = Session(opened, speech)
                val matcher = WakeMatcher(opened.words, sensitivity)
                handler.post {
                    initializing = false
                    if (gen != generation) {
                        onWorker { s.release() } // session was cancelled while we were starting
                        return@post
                    }
                    if (!speech.startListening(listener(gen, matcher))) {
                        onWorker { s.release() }
                        fail(EventLog.ERROR, str(R.string.ev_detector_init, "startListening() returned false"))
                        shutdown()
                        return@post
                    }
                    session = s
                    logListening()
                }
            } catch (e: Exception) {
                opened?.close()
                handler.post {
                    initializing = false
                    if (gen == generation) {
                        fail(EventLog.ERROR, str(R.string.ev_detector_init, "${e.javaClass.simpleName}: ${e.message}"))
                        shutdown()
                    }
                }
            }
        }
    }

    /** Vosk delivers these on the main thread. */
    private fun listener(gen: Int, matcher: WakeMatcher) = object : RecognitionListener {
        override fun onPartialResult(hypothesis: String?) {
            if (gen == generation && matcher.onPartial(hypothesis)) onWakeWord()
        }

        override fun onResult(hypothesis: String?) {
            if (gen == generation && matcher.onFinal(hypothesis)) onWakeWord()
        }

        override fun onFinalResult(hypothesis: String?) {
            if (gen == generation && matcher.onFinal(hypothesis)) onWakeWord()
        }

        override fun onError(exception: Exception?) {
            if (gen != generation) return
            fail(EventLog.ERROR, str(R.string.ev_detector_error, "${exception?.javaClass?.simpleName}: ${exception?.message}"))
            shutdown()
        }

        override fun onTimeout() = Unit // we use our own timeout
    }

    private fun logListening() {
        val arm = mode == Mode.ARM
        val what = str(if (arm) R.string.ev_listening_call else R.string.ev_listening_test)
        EventLog.add(
            this, EventLog.LISTENING,
            if (fakeDetector) "$what [DEBUG: simulated detector, mic not opened]" else what,
            persist = arm
        )
    }

    private fun onWakeWord() {
        when (mode) {
            Mode.TEST -> EventLog.add(this, EventLog.TEST_HIT, str(R.string.ev_wake_test), persist = false)
            Mode.ARM -> {
                if (answering) return
                answering = true
                handler.removeCallbacks(timeout)
                EventLog.add(this, EventLog.WAKE, str(R.string.ev_wake))
                // Free the mic first, then answer.
                val s = session
                session = null
                fakeDetector = false
                generation++
                onWorker {
                    s?.release()
                    handler.post { if (mode == Mode.ARM) answerCall() }
                }
            }
            null -> Unit
        }
    }

    private fun answerCall() {
        // Default phone app: answer through Telecom's Call object and route the audio ourselves.
        CallManager.ringingCall()?.let { call ->
            CallManager.answer(call)
            EventLog.add(this, EventLog.ANSWER_REQUESTED, str(R.string.ev_answer_dialer))
            handler.postDelayed({ speakerViaDialer() }, SPEAKER_DELAY_MS)
            return
        }
        if (checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) != PackageManager.PERMISSION_GRANTED) {
            fail(EventLog.ANSWER_FAILED, str(R.string.ev_answer_no_perm))
            shutdown()
            return
        }
        try {
            val tm = getSystemService(TelecomManager::class.java)
            @Suppress("DEPRECATION") // deprecated in API 29; no replacement for non-dialer apps
            tm.acceptRingingCall()
            EventLog.add(this, EventLog.ANSWER_REQUESTED, str(R.string.ev_answer_requested))
        } catch (e: Exception) {
            fail(EventLog.ANSWER_FAILED, "${e.javaClass.simpleName}: ${e.message}")
            shutdown()
            return
        }

        handler.postDelayed({ routeToSpeaker() }, SPEAKER_DELAY_MS)
    }

    /**
     * Preferred: the accessibility service presses the in-call screen's
     * Speaker button (on a real Redmi, AudioManager could not move the call).
     * Fallback: the AudioManager request, which a SIM call usually ignores.
     */
    private fun routeToSpeaker() {
        if (mode != Mode.ARM) return // call already over
        val clicker = SpeakerClickService.instance
        if (clicker == null) {
            speakerViaAudioManager(str(R.string.ev_speaker_no_a11y))
            return
        }
        try {
            // Bring the dialer's ongoing-call screen (with its Speaker button) to the front.
            getSystemService(TelecomManager::class.java).showInCallScreen(false)
        } catch (_: Exception) {
        }
        clicker.pressSpeaker(SPEAKER_CLICK_TIMEOUT_MS) { r ->
            if (destroyed) return@pressSpeaker
            if (r.ok) {
                speakerResult("CLICKED_OK: ", true, str(R.string.ev_speaker_clicked, r.detail))
            } else {
                speakerViaAudioManager(str(R.string.ev_speaker_click_failed, r.detail))
            }
        }
    }

    /**
     * We own the call as the default phone app, so routing to the speaker is reliable here.
     * A connected Bluetooth device or wired headset keeps the call, as on any phone
     * (a car kit, earbuds): the loudspeaker is only for when nothing is connected.
     */
    private fun speakerViaDialer(waitForHeadset: Boolean = true) {
        if (mode != Mode.ARM) return
        CallManager.headsetRoute()?.let { r ->
            val name = r.name ?: str(
                if (r.kind == CallManager.RouteKind.BLUETOOTH) R.string.route_bluetooth else R.string.route_wired
            )
            val text = str(R.string.ev_speaker_kept, name)
            Settings.setLastSpeakerResult(this, "KEPT: $text")
            EventLog.add(this, EventLog.SPEAKER_KEPT, text)
            shutdown()
            return
        }
        if (waitForHeadset && CallManager.headsetAvailable()) {
            // Connected, but Telecom hasn't moved the call there yet (Bluetooth audio takes a moment).
            handler.postDelayed({ speakerViaDialer(waitForHeadset = false) }, HEADSET_WAIT_MS)
            return
        }
        CallManager.setSpeaker(true)
        handler.postDelayed({
            val ok = CallManager.isSpeakerOn() || SpeakerRouter.isSpeakerOn(this)
            val detail = "${CallManager.describeRoute()}; ${SpeakerRouter.describe(this)}"
            speakerResult(
                if (ok) "DIALER_OK: " else "FAILED: ", ok,
                str(if (ok) R.string.ev_speaker_dialer else R.string.ev_speaker_dialer_failed, detail)
            )
        }, SPEAKER_VERIFY_DELAY_MS)
    }

    private fun speakerViaAudioManager(prefix: String) {
        val attempt = SpeakerRouter.attempt(this)
        handler.postDelayed({
            val (ok, detail) = SpeakerRouter.verify(this)
            val text = "$attempt → $detail"
            speakerResult(
                if (ok) "REPORTED_OK: " else "FAILED: ", ok,
                "$prefix " + str(if (ok) R.string.ev_speaker_ok else R.string.ev_speaker_failed, text)
            )
        }, SPEAKER_VERIFY_DELAY_MS)
    }

    private fun speakerResult(prefix: String, ok: Boolean, text: String) {
        Settings.setLastSpeakerResult(this, prefix + text)
        EventLog.add(this, if (ok) EventLog.SPEAKER_OK else EventLog.SPEAKER_FAILED, text)
        shutdown()
    }

    /**
     * DEBUG BUILDS ONLY (called by DebugReceiver on the main thread): behave as if
     * the wake word was heard. Returns false when nothing is listening.
     */
    fun debugSimulateWake(): Boolean {
        if (!Settings.isDebuggable(this)) return false
        if (mode == null || (session == null && !fakeDetector)) return false
        onWakeWord()
        return true
    }

    /** Called by CallStateReceiver on OFFHOOK / IDLE. */
    fun onCallStateChanged(state: String) {
        handler.post {
            if (mode != Mode.ARM) return@post // test mode isn't tied to calls
            if (answering && state == TelephonyManager.EXTRA_STATE_OFFHOOK) {
                return@post // expected after acceptRingingCall(); the speaker step calls shutdown()
            }
            EventLog.add(this, EventLog.STOPPED, str(R.string.ev_call_state, state))
            shutdown()
        }
    }

    /**
     * For a real call: stored in the 20-event log and posted as a notification.
     * In test mode: only sent live to the Test screen, so test attempts don't
     * push real call events out of the log.
     */
    private fun fail(type: String, msg: String, arm: Boolean = mode == Mode.ARM) {
        EventLog.add(this, type, msg, persist = arm)
        if (arm) ProblemNotification.show(this, msg)
    }

    private fun onWorker(block: () -> Unit) {
        try {
            worker.execute(block)
        } catch (_: RejectedExecutionException) {
            block() // service already destroyed: just do it here
        }
    }

    private fun releaseMic() {
        generation++
        fakeDetector = false
        val s = session ?: return
        session = null
        onWorker { s.release() }
    }

    private fun shutdown() {
        handler.removeCallbacks(timeout)
        releaseMic()
        mode = null
        answering = false
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {}
        stopSelf()
    }

    override fun onDestroy() {
        destroyed = true
        handler.removeCallbacksAndMessages(null)
        releaseMic()
        worker.shutdown() // queued releases still run
        instance = null
        super.onDestroy()
    }
}
