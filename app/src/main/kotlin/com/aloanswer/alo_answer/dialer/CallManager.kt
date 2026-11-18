package com.aloanswer.alo_answer.dialer

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.OutcomeReceiver
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.CallEndpoint
import android.telecom.CallEndpointException
import android.telecom.InCallService
import android.telecom.VideoProfile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors

/**
 * Process-wide state of the calls Telecom gave us while Alo Answer is the
 * default phone app (ROLE_DIALER). Filled by [AloInCallService]; read and
 * driven by the in-call screen, the notifications and WakeWordService.
 *
 * Everything here runs on the main thread (Telecom delivers InCallService and
 * Call.Callback events there).
 */
object CallManager {

    fun interface Listener {
        fun onChanged()
    }

    /** Where the call audio can go. The order is the order of the call screen's audio picker. */
    enum class RouteKind { BLUETOOTH, WIRED_HEADSET, EARPIECE, SPEAKER }

    /**
     * One choice in the audio picker. [name] is the device name (Bluetooth only, when
     * Android gives it). [token] is what Telecom needs to switch to it: a CallEndpoint
     * (Android 14+), a BluetoothDevice (Android 9–13), the debug demo device, or null.
     */
    data class AudioRoute(val kind: RouteKind, val name: String?, val active: Boolean, val token: Any?)

    private val listeners = CopyOnWriteArraySet<Listener>()
    private val main = Handler(Looper.getMainLooper())
    private val lookups = Executors.newSingleThreadExecutor()

    /** Contact names found for numbers of current calls (number → name). */
    private val names = ConcurrentHashMap<String, String>()

    /** Calls currently known to Telecom (main thread only). */
    val calls = mutableListOf<Call>()

    /** The bound InCallService, needed for mute and audio routing. */
    @Volatile
    var service: InCallService? = null

    var muted = false
        private set
    private var speakerOn = false

    /** Latest CallAudioState. Telecom still delivers it on Android 14+, next to the endpoint callbacks. */
    private var audioState: CallAudioState? = null

    /** Latest CallEndpoint list and current endpoint (Android 14+), kept as Any so this class loads on older Android. */
    private var endpoints: List<Any> = emptyList()
    private var currentEndpoint: Any? = null

    // DEBUG BUILDS ONLY: a pretend Bluetooth headset, so the audio picker can be tried on an
    // emulator, which has no Bluetooth audio. Switched on by DebugReceiver; picking it changes
    // nothing real.
    private val DEMO = Any()
    private const val DEMO_NAME = "Demo Buds"
    private var demoBluetooth = false
    private var demoActive = false

    fun addListener(l: Listener) {
        listeners.add(l)
    }

    fun removeListener(l: Listener) {
        listeners.remove(l)
    }

    private fun changed() = listeners.forEach { it.onChanged() }

    private val callback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) = changed()
        override fun onDetailsChanged(call: Call, details: Call.Details) = changed()
    }

    fun onCallAdded(call: Call) {
        if (call !in calls) calls.add(call)
        call.registerCallback(callback)
        changed()
    }

    /** Look the caller up in contacts (off the main thread) so the call screen and notification show a name. */
    fun resolveName(ctx: Context, call: Call) {
        val n = number(call) ?: return
        if (names.containsKey(n) || (displayName(call) ?: n) != n) return
        val app = ctx.applicationContext
        lookups.execute {
            val name = ContactsRepo.nameFor(app, n) ?: return@execute
            names[n] = name
            main.post { changed() }
        }
    }

    fun onCallRemoved(call: Call) {
        call.unregisterCallback(callback)
        calls.remove(call)
        if (calls.isEmpty()) {
            muted = false
            speakerOn = false
            names.clear()
            audioState = null
            endpoints = emptyList()
            currentEndpoint = null
            demoActive = false
        }
        changed()
    }

    // ---------------- reading state ----------------

    fun stateOf(call: Call): Int =
        if (Build.VERSION.SDK_INT >= 31) call.details.state else @Suppress("DEPRECATION") call.state

    fun ringingCall(): Call? = calls.firstOrNull { stateOf(it) == Call.STATE_RINGING }

    fun hasRingingCall() = ringingCall() != null

    /** The call the UI should show: a ringing one first, then an active/dialing one, then anything. */
    fun primaryCall(): Call? {
        ringingCall()?.let { return it }
        val live = setOf(Call.STATE_ACTIVE, Call.STATE_DIALING, Call.STATE_CONNECTING, Call.STATE_SELECT_PHONE_ACCOUNT)
        return calls.firstOrNull { stateOf(it) in live }
            ?: calls.firstOrNull { stateOf(it) != Call.STATE_DISCONNECTED }
    }

    fun number(call: Call): String? {
        val handle: Uri = call.details.handle ?: return null
        return handle.schemeSpecificPart?.takeIf { it.isNotBlank() }
    }

    /** Contact name if Telecom knows it, else the network-provided caller name, else null. */
    fun displayName(call: Call): String? {
        number(call)?.let { n -> names[n]?.let { return it } }
        val d = call.details
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                d.contactDisplayName?.takeIf { it.isNotBlank() }?.let { return it }
            } catch (_: Exception) {
            }
        }
        d.callerDisplayName?.takeIf { it.isNotBlank() }?.let { return it }
        return number(call)
    }

    // ---------------- actions ----------------

    fun answer(call: Call) = call.answer(VideoProfile.STATE_AUDIO_ONLY)

    fun reject(call: Call) {
        if (Build.VERSION.SDK_INT >= 30) call.reject(Call.REJECT_REASON_DECLINED) else call.reject(false, null)
    }

    fun hangup(call: Call) = call.disconnect()

    fun toggleHold(call: Call) {
        if (stateOf(call) == Call.STATE_HOLDING) call.unhold() else call.hold()
    }

    fun setMuted(on: Boolean) {
        service?.setMuted(on)
    }

    // ---------------- audio routes ----------------

    fun isSpeakerOn(): Boolean = speakerOn && !demoActive

    /** Every place the call audio can go right now. Empty until Telecom reports them. */
    fun routes(): List<AudioRoute> {
        var list = if (Build.VERSION.SDK_INT >= 34 && endpoints.isNotEmpty()) endpointRoutes() else legacyRoutes()
        if (demoBluetooth) {
            if (demoActive) list = list.map { it.copy(active = false) }
            list = listOf(AudioRoute(RouteKind.BLUETOOTH, DEMO_NAME, demoActive, DEMO)) + list
        }
        return list.sortedBy { it.kind.ordinal } // stable: keeps Telecom's order within a kind
    }

    fun currentRoute(): AudioRoute? = routes().firstOrNull { it.active }

    /** The Bluetooth device or wired headset the call is on right now, if any. */
    fun headsetRoute(): AudioRoute? = currentRoute()?.takeIf { it.kind.isHeadset() }

    /** True when a Bluetooth device or wired headset could take the call. */
    fun headsetAvailable(): Boolean = routes().any { it.kind.isHeadset() }

    private fun RouteKind.isHeadset() = this == RouteKind.BLUETOOTH || this == RouteKind.WIRED_HEADSET

    /** Android 14+: Telecom's CallEndpoints. */
    private fun endpointRoutes(): List<AudioRoute> {
        if (Build.VERSION.SDK_INT < 34) return emptyList()
        val current = currentEndpoint as? CallEndpoint
        return endpoints.filterIsInstance<CallEndpoint>().mapNotNull { e ->
            val kind = when (e.endpointType) {
                CallEndpoint.TYPE_BLUETOOTH -> RouteKind.BLUETOOTH
                CallEndpoint.TYPE_WIRED_HEADSET -> RouteKind.WIRED_HEADSET
                CallEndpoint.TYPE_EARPIECE -> RouteKind.EARPIECE
                CallEndpoint.TYPE_SPEAKER -> RouteKind.SPEAKER
                else -> return@mapNotNull null // streaming / unknown: nothing the user can pick
            }
            val name = if (kind == RouteKind.BLUETOOTH) e.endpointName.toString().takeIf { it.isNotBlank() } else null
            AudioRoute(kind, name, current != null && current.identifier == e.identifier, e)
        }
    }

    /** Android 8–13 (and 14+ before the endpoints arrive): the route bit mask of CallAudioState. */
    @Suppress("DEPRECATION")
    private fun legacyRoutes(): List<AudioRoute> {
        val st = audioState ?: service?.callAudioState ?: return emptyList()
        val mask = st.supportedRouteMask
        val route = st.route
        val out = mutableListOf<AudioRoute>()
        if ((mask and CallAudioState.ROUTE_BLUETOOTH) != 0) {
            val onBt = route == CallAudioState.ROUTE_BLUETOOTH
            val devices = if (Build.VERSION.SDK_INT >= 28) st.supportedBluetoothDevices.toList() else emptyList()
            if (devices.isEmpty()) {
                out += AudioRoute(RouteKind.BLUETOOTH, null, onBt, null)
            } else {
                val activeDevice = if (Build.VERSION.SDK_INT >= 28) st.activeBluetoothDevice else null
                devices.forEachIndexed { i, d ->
                    val active = onBt && (if (activeDevice != null) d == activeDevice else i == 0)
                    out += AudioRoute(RouteKind.BLUETOOTH, bluetoothName(d), active, d)
                }
            }
        }
        if ((mask and CallAudioState.ROUTE_WIRED_HEADSET) != 0) {
            out += AudioRoute(RouteKind.WIRED_HEADSET, null, route == CallAudioState.ROUTE_WIRED_HEADSET, null)
        }
        if ((mask and CallAudioState.ROUTE_EARPIECE) != 0) {
            out += AudioRoute(RouteKind.EARPIECE, null, route == CallAudioState.ROUTE_EARPIECE, null)
        }
        if ((mask and CallAudioState.ROUTE_SPEAKER) != 0) {
            out += AudioRoute(RouteKind.SPEAKER, null, route == CallAudioState.ROUTE_SPEAKER, null)
        }
        return out
    }

    /**
     * BluetoothDevice.getName() needs BLUETOOTH_CONNECT on Android 12+, which we don't ask for:
     * without it the picker just says "Bluetooth". (Android 14+ names come from Telecom instead.)
     */
    @SuppressLint("MissingPermission")
    private fun bluetoothName(d: BluetoothDevice): String? = try {
        d.name?.takeIf { it.isNotBlank() }
    } catch (_: SecurityException) {
        null
    }

    /**
     * Moves the call audio to [r]. As the default phone app we own the call, so
     * Telecom honours this, unlike AudioManager requests from a normal app.
     */
    fun selectRoute(r: AudioRoute): Boolean {
        if (r.token === DEMO) {
            demoActive = true
            changed()
            return true
        }
        val wasDemo = demoActive
        demoActive = false
        val s = service
        val ok = when {
            s == null -> false
            Build.VERSION.SDK_INT >= 34 && r.token is CallEndpoint -> {
                s.requestCallEndpointChange(
                    r.token, s.mainExecutor,
                    object : OutcomeReceiver<Void?, CallEndpointException> {
                        override fun onResult(result: Void?) = Unit
                        override fun onError(error: CallEndpointException) = Unit
                    }
                )
                true
            }
            Build.VERSION.SDK_INT >= 28 && r.token is BluetoothDevice -> {
                @Suppress("DEPRECATION")
                s.requestBluetoothAudio(r.token)
                true
            }
            else -> {
                @Suppress("DEPRECATION")
                s.setAudioRoute(legacyRoute(r.kind))
                true
            }
        }
        if (wasDemo) changed()
        return ok
    }

    private fun legacyRoute(k: RouteKind) = when (k) {
        RouteKind.BLUETOOTH -> CallAudioState.ROUTE_BLUETOOTH
        RouteKind.WIRED_HEADSET -> CallAudioState.ROUTE_WIRED_HEADSET
        RouteKind.EARPIECE -> CallAudioState.ROUTE_EARPIECE
        RouteKind.SPEAKER -> CallAudioState.ROUTE_SPEAKER
    }

    /**
     * Loudspeaker on, or back to the private route (wired headset, then
     * Bluetooth, then earpiece), like the speaker button of any phone app.
     */
    fun setSpeaker(on: Boolean): Boolean {
        val rs = routes()
        val target = if (on) {
            rs.firstOrNull { it.kind == RouteKind.SPEAKER }
        } else {
            rs.firstOrNull { it.kind == RouteKind.WIRED_HEADSET }
                ?: rs.firstOrNull { it.kind == RouteKind.BLUETOOTH }
                ?: rs.firstOrNull { it.kind == RouteKind.EARPIECE }
        }
        if (target != null) return selectRoute(target)
        // Telecom hasn't reported the routes yet.
        val s = service ?: return false
        demoActive = false
        @Suppress("DEPRECATION")
        s.setAudioRoute(if (on) CallAudioState.ROUTE_SPEAKER else CallAudioState.ROUTE_WIRED_OR_EARPIECE)
        return true
    }

    /** DEBUG BUILDS ONLY (see [DEMO]); callers check Settings.debugFakeBluetooth. */
    fun setDemoBluetooth(on: Boolean) {
        demoBluetooth = on
        demoActive = on // like a real headset: a new call starts on it
        changed()
    }

    // ---------------- audio state from InCallService ----------------

    fun onAudioState(state: CallAudioState?) {
        state ?: return
        audioState = state
        @Suppress("DEPRECATION")
        run {
            muted = state.isMuted
            speakerOn = state.route == CallAudioState.ROUTE_SPEAKER
        }
        changed()
    }

    /** Android 14+. */
    fun onEndpoint(endpoint: Any) {
        currentEndpoint = endpoint
        if (Build.VERSION.SDK_INT >= 34) {
            speakerOn = (endpoint as CallEndpoint).endpointType == CallEndpoint.TYPE_SPEAKER
        }
        changed()
    }

    /** Android 14+. Also fires when a Bluetooth device connects or drops during the call. */
    fun onAvailableEndpoints(list: List<Any>) {
        endpoints = list
        changed()
    }

    fun onMute(isMuted: Boolean) {
        muted = isMuted
        changed()
    }

    /** Short route description for the event log. */
    fun describeRoute(): String {
        val r = currentRoute() ?: return if (speakerOn) "route=SPEAKER" else "route=unknown"
        val name = r.name?.let { " ($it)" }.orEmpty()
        return "route=${r.kind}$name" + if (r.token === DEMO) " [demo]" else ""
    }
}
