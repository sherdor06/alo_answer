# Alo Answer

Native Android app (Kotlin): when the phone rings, say **“alo”** (Uzbek “allo” /
“a'lo”) and the call is answered. It then *tries* to switch the call to the
loudspeaker (best effort, see below).

Speech is recognized **offline** by [Vosk](https://alphacephei.com/vosk/) with
its Uzbek model: no account, no key, and the app has no internet permission.

Since 2.0.0 the whole app is native Kotlin: the screens are plain Android
framework views (no Flutter, no AndroidX), next to the call-state handling,
microphone, speech recognition, answering and audio routing that were always
native. Versions up to 1.4.0 had a Flutter UI; that version is kept as a
backup in `../alo_answer_flutter_backup`.

### Project layout

| Path | What |
|---|---|
| `app/src/main/kotlin/com/aloanswer/alo_answer/` | Call handling: `CallStateReceiver`, `ArmActivity`, `WakeWordService`, `ModelStore`, `WakeMatcher`, `SpeakerRouter`, `SpeakerClickService`, `Settings`, `EventLog`, `PermissionHelper`, `XiaomiIntents`; `MainActivity` (home screen) |
| `…/ui/` | The other screens: `OnboardingActivity` (permissions), `TestListenActivity`, `EventLogActivity`, `XiaomiSetupActivity`, and `Screen.kt` (shared top bar, cards, buttons) |
| `…/dialer/` | Default phone app mode: `AloInCallService`, `CallManager`, `InCallActivity`, `DialerActivity`, `CallNotifications`, `PhoneData` |
| `app/src/main/res/values{,-uz,-ru}/strings.xml` | All texts in English, Uzbek and Russian |
| `app/src/main/assets/model-uz/` | Vosk Uzbek model |
| `app/src/debug/` | Debug-only test hook (`DebugReceiver`, see below) |

---

## How it works

```
PHONE_STATE broadcast (manifest receiver, CallStateReceiver.kt)
   │ RINGING and the master switch is on
   ▼
ArmActivity.kt — invisible screen, shown for well under a second (over the lock screen too)
   │ starts the service while the app is visible, then closes
   ▼
WakeWordService.kt — foreground service, type=microphone
   │ loads the Uzbek Vosk model (~1 s), listens only for “allo” / “a'lo”
   │ “allo” heard                 │ OFFHOOK / IDLE / 30 s timeout / error
   ▼                              ▼
mic released → TelecomManager.acceptRingingCall()     mic released, service stops
   ▼
SpeakerClickService.kt (if enabled) presses the call screen's Speaker button;
otherwise SpeakerRouter.kt makes a best-effort AudioManager request — both read back the real route
   ▼
service stops (mic was already released before answering)
```

The receiver checks 3 seconds later whether the invisible screen actually
opened. If Android blocked it, the receiver logs **Blocked by Android** and
shows a notification saying which setting is missing.

### Why the invisible screen

A microphone-type foreground service may use the mic only if it was started
while the app was visible, or through a few system exemptions such as a
notification tap or the default voice assistant app. The docs say this
applies "even if the app falls into one of the exemptions from background
start restrictions."
- **Android 14+ (HyperOS):** starting it from a broadcast receiver throws
  `SecurityException`.
- **Android 11–13:** the service starts, but the mic records silence.

So the receiver opens `ArmActivity`, which starts the service while it's
visible. Opening an activity from the background is itself restricted. The
exemption this app relies on is **Display over other apps**
(`SYSTEM_ALERT_WINDOW`), and Xiaomi adds its own switches on top (see
MIUI / HyperOS setup).

---

## Default phone app mode (recommended)

If the user makes Alo Answer the **default phone app** (the home screen card
“Default phone app” → **Make default**, or Settings → Apps → Default apps →
Phone app), Telecom hands every call to the app itself. There are no
workarounds left:

| | Normal mode | Default phone app mode |
|---|---|---|
| Who shows the call screen | the system dialer | `InCallActivity` (ours, native) |
| Starting the mic while ringing | invisible `ArmActivity` + “Display over other apps” | our own call screen is in front, so the mic is allowed |
| Answering | `TelecomManager.acceptRingingCall()` (deprecated) | `Call.answer(VideoProfile.STATE_AUDIO_ONLY)` |
| Loudspeaker | not possible directly; Accessibility presses the button | `requestCallEndpointChange(TYPE_SPEAKER)` (Android 14+) / `setAudioRoute(ROUTE_SPEAKER)` |

What's built, all native Kotlin with framework views (no extra libraries,
package `…alo_answer.dialer`):

- `AloInCallService`: the `InCallService` (permission
  `BIND_INCALL_SERVICE`, meta-data `IN_CALL_SERVICE_UI=true`). It is
  exported, as the docs require. Telecom keeps playing the ringtone, because
  `IN_CALL_SERVICE_RINGING` isn't declared.
- `CallManager`: the list of calls, plus answer, reject, end, hold, mute,
  audio routes and DTMF. Audio routes come from Telecom:
  - Android 14+: `onAvailableCallEndpointsChanged` / `onCallEndpointChanged`;
    switching uses `requestCallEndpointChange`. Bluetooth device names come
    from `CallEndpoint.getEndpointName()`, so no Bluetooth permission is needed.
  - Android 8–13: the `CallAudioState` route mask and `setAudioRoute`. On
    Android 9–13, a given Bluetooth device is picked with
    `requestBluetoothAudio`. There, `BluetoothDevice.getName()` needs
    `BLUETOOTH_CONNECT` (Android 12+), which the app doesn't request, so the
    picker shows just “Bluetooth” for those devices.
- Voice answer and Bluetooth: if a Bluetooth device or wired headset is
  connected (a car kit, earbuds, a watch that takes calls), the answered call
  stays on it, as on any phone, and the speaker is left off. The app checks
  800 ms after answering. If a device is connected but Telecom hasn't moved
  the call yet, it checks once more 2 s later. The event log then says
  “Kept on Bluetooth / headset”, with the device. The loudspeaker is used only
  when nothing is connected. In normal mode (not the default phone app),
  auto speaker works as before.
- `InCallActivity`: the call screen, which also shows over the lock screen.
  - Incoming: Decline / Answer, plus the “Listening for ‘allo’…” chip.
  - Ongoing: timer, Mute / Keypad (DTMF) / Speaker / Hold / End.
  - Audio output: with no Bluetooth device, the button is a plain Speaker
    on/off. With a Bluetooth device that can take calls, it becomes an audio
    button that shows where the sound is (Bluetooth / Speaker / Phone ▾) and
    opens a picker: each Bluetooth device by name, wired headset, phone
    (earpiece), speaker, with a tick on the current one. The list updates
    while open, if a device connects or drops.
  - Dual SIM: it asks which SIM to use.
  - The proximity sensor turns the screen off at the ear, only while the
    audio is on the earpiece.
- `DialerActivity`: the dial pad. It handles `ACTION_DIAL` and `tel:` links
  (a requirement for the role) and places calls with
  `TelecomManager.placeCall`, which also handles emergency numbers. It opens
  from the home screen card or by long-pressing the app icon → “Dial”.
- `CallNotifications`: the incoming-call notification (full-screen intent +
  `CallStyle`) and the ongoing-call notification with “End”. While our call
  screen is already in front, the incoming notification uses a quiet channel,
  so no heads-up covers the screen.
- New permissions: `CALL_PHONE`, `USE_FULL_SCREEN_INTENT`, `WAKE_LOCK`, `READ_CALL_LOG`, `READ_CONTACTS`.

**Verified on the emulator** (SDK 37, Alo Answer set as the default phone
app):
- **Incoming call:** our screen opened, and listening started 1.2 s after the
  ring (AppOps `RECORD_AUDIO … Running`).
- **“allo”:** supplied by the debug hook. The app answered (“Answered by the
  app itself”), the call was off-hook 0.1 s later, and the app switched the
  route to the speaker itself (`route=SPEAKER`).
- **Our buttons:** End and Decline work.
- **Outgoing call:** from our dial pad, it connected with a timer.

- **Phone app tabs** (`DialerActivity`): **Recents · Contacts · Keypad**. It
  always opens on Keypad.
  - **Recents** (`READ_CALL_LOG`): incoming / outgoing / missed / declined
    icons, times and durations, and contact names (from `CACHED_NAME` or a
    contacts lookup). Tap to call back; long-press to put the number on the
    keypad.
  - **Contacts** (`READ_CONTACTS`): A→Z with favourites first and search by
    name or number. When a contact has several numbers, the app asks which
    one to call.
  - Each tab asks for its permission the first time. As the default phone
    app, Android usually grants them automatically.
- **Caller name:** the call screen and notifications show the contact's name,
  looked up off the main thread with `PhoneLookup`.
- Verified on the emulator, with test contacts removed afterwards:
  - Recents listed calls with resolved names.
  - Searching “dil” filtered the contacts.
  - An incoming call from a saved number showed the contact name.

**Added in 2.1** (verified on the emulator):
- The app icon opens the dial pad (as the default phone app); settings via ⋮.
- Dial pad: smart dial (contacts by T9 name, Latin and Cyrillic, or number),
  copy/paste, long-press 1 = voicemail, call button recalls the last number.
- Recents: grouped calls, All / Missed filter, missed badge; details sheet with
  message, copy, edit, add/open contact, block, delete (`WRITE_CALL_LOG`).
- Contacts: photos, favourites, letter headers, new contact, call / message.
- Missed-call notification (`ACTION_SHOW_MISSED_CALLS_NOTIFICATION`) with
  Call back / Message; seen calls are marked read by the app.
- Call screen: call waiting (hold and answer / end and answer), add call,
  swap, merge (conference), reply with a message, contact photo, SIM name.
- Settings: call forwarding/waiting (system call settings), SIM accounts,
  blocked numbers. Light and dark themes for the phone app.
- Not verifiable on the emulator: call waiting holds the first call on a
  real network (the emulator modem keeps both active).

**Not built yet:** the Play build (a flavor without the overlay and
Accessibility code).

## Wake word and speech model

| | |
|---|---|
| Engine | `com.alphacephei:vosk-android:0.3.75` (Apache 2.0). It brings in `net.java.dev.jna:jna` automatically. |
| Model | `vosk-model-small-uz-0.22` (Apache 2.0), from alphacephei.com/vosk/models. 51 MB zip, ~102 MB unpacked. |
| Where | `app/src/main/assets/model-uz/`. Copied to internal storage on first start and again after every app update (`ModelStore.kt`). |
| Words | `allo` and `a'lo`. Both are in the model's vocabulary; plain `alo` is not. Both sound like “alo”. |
| Mode | **Grammar mode**: the model has `graph/Gr.fst` + `HCLr.fst`, so the recognizer is limited to `["allo", "a'lo", "[unk]"]`. Anything else comes out as `[unk]`. |

How a hit is decided (`WakeMatcher.kt`):
- **Final result:** the word appears with confidence `conf ≥ 0.85 − 0.75 × sensitivity`.
  The default sensitivity is 0.5, which needs conf ≥ 0.475.
- **Or partial results:** the word stays in the partial hypothesis for
  `2 + round((1 − sensitivity) × 4)` updates in a row (~0.2 s each; 4 at the
  default). This matters during a call, where the ringtone can keep an
  utterance “open” and delay the final result.

To use a different language (e.g. `vosk-model-small-ru-0.22`, Russian “алло”):
replace `assets/model-uz`, then update `ModelStore.ASSET_DIR` and
`ModelStore.WAKE_WORDS`. The words must be in the new model's vocabulary.

---

## Build

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home  # any JDK 17+
./gradlew assembleRelease     # → app/build/outputs/apk/release/app-release.apk
adb install -r app/build/outputs/apk/release/app-release.apk
```

Or open this folder in Android Studio and press Run. `./gradlew assembleDebug`
gives `app/build/outputs/apk/debug/app-debug.apk` (debug key, with the test
hook).

- **Version:** `versionCode` / `versionName` in `app/build.gradle.kts`
  (2.0.0 = 2007, 2.0.1 = 2008). Raise `versionCode` for every build you
  install over an older one. The Flutter builds used 2000 + build number (up
  to 2006), so 2.0.0 and later install over them with the same key.
- **arm64-v8a only** (`abiFilters` in `app/build.gradle.kts`), which covers
  every current Redmi. Add `"armeabi-v7a"` for old 32-bit phones or
  `"x86_64"` for an Intel-Mac emulator; each adds its own copy of Vosk.
- App name **Alo Answer**, with an adaptive vector icon: a phone handset with
  sound waves on teal (`res/drawable/ic_launcher_foreground.xml`, Material
  Icons “phone_in_talk”, Apache 2.0). It has a monochrome layer for Android
  13+ themed icons, and the same splash on start.
- Light and dark theme (`values/` and `values-night/` colors and styles). All
  screens draw edge-to-edge and keep their content clear of the status and
  navigation bars (`edgeToEdge()` + `padForSystemBars()` in `dialer/Ui.kt`).
- **Signing:** release builds are signed with the app's own key:
  `keystore/alo-answer-release.jks` (alias `alo_answer`, RSA 2048,
  valid until 2054, certificate `CN=Alo Answer`, SHA-256
  `9E:9B:56:03:…:40:8E:4B:62`). The passwords are in `key.properties`.
  Both files are git-ignored and readable only by your macOS user.
  - **Back both files up** somewhere safe, such as a password manager or a
    private cloud folder. Without them you can't build an update that
    installs over the existing app.
  - Without `key.properties` (e.g. on another machine), release builds fall
    back to the debug key.
  - Versions up to 1.1.0 were debug-signed. Going to 1.1.1, uninstall the old
    app once: Android refuses an update signed with a different key. You'll
    then need to grant the permissions and turn on accessibility again.

- minSdk 26, compileSdk / targetSdk 36.
- Toolchain: Gradle 9.1.0, Android Gradle Plugin 9.0.1, Kotlin 2.3.21 (the
  Kotlin Gradle plugin applied in `app/build.gradle.kts`, with
  `android.builtInKotlin=false` and `android.newDsl=false` in
  `gradle.properties`), JDK 21. NDK 28.2 is only used to strip the prebuilt
  native libraries.
- Dependencies: only `vosk-android` 0.3.75 (+ `jna`). No AndroidX or other
  libraries: the screens use framework views. Neither library ships R8 keep
  rules, so `app/proguard-rules.pro` keeps the JNA/Vosk classes for release
  builds (R8 + resource shrinking are on).
- Size: the **release APK is 61.5 MB** (the Flutter 1.3.0 arm64 APK was
  78.2 MB). Most of it is the model and Vosk's arm64 library.
- No `INTERNET` permission, in release or debug builds, and neither library
  adds one.
- Settings live in one SharedPreferences file (`alo_settings`), so the
  receiver and service read them at any time, with no screen open.
- The event log keeps the last 20 events on disk (they survive restarts);
  open screens get live updates through `EventLog` listeners.

---

## MIUI / HyperOS setup (Redmi)

The app has a **Xiaomi / Redmi setup** screen with buttons for each step.

1. **Autostart: on.** Without it, HyperOS usually doesn't wake the app for an
   incoming call.
2. **Battery saver: No restrictions.** Settings → Apps → Manage apps →
   Alo Answer → Battery saver.
3. **Other permissions:**
   - **Display pop-up windows while running in the background** — without
     this, Xiaomi blocks the invisible screen even when the Android
     permission is granted.
   - **Show on Lock screen** — needed when the phone is locked while it rings.
4. **Display over other apps: on.** This is also asked for during onboarding.
5. *(Optional)* Lock the app in Recents.

The Xiaomi buttons open `com.miui.securitycenter` / `com.miui.powerkeeper`
screens by component name. **These names aren't public API**, and Xiaomi can
move them in any update. If one isn't found, the app opens App info instead
and says so. Menu names also differ between versions.

---

## Testing on the phone

1. Install the app and grant every permission in onboarding. Each permission
   says why it's needed; for one you denied permanently, the app sends you to
   Settings.
2. Check that **Setup check** shows the model as ready and lists the words
   it listens for. The first start copies the model, which takes a few
   seconds.
3. **Test listening** (no call needed): say “allo” / “alo”. The circle
   flashes green and the counter goes up. Tune the sensitivity here. Test mode
   uses the same service, model and rules as a call, but never answers
   anything and stops after 60 seconds or when you leave the screen.
4. Turn on **Answer calls by saying “alo”**.
5. **Real call:** call the phone from another phone, once while it's locked
   and once while it's unlocked. While it rings, the **“Say ‘alo’ to answer”**
   notification means the mic is open. Say “allo”.
6. Open the **Event log**. A working run looks like this:
   Ring detected → Listening → Wake word heard → Answer requested →
   Call answered → Speaker: …
7. Logcat:

   ```bash
   adb logcat -s AloAnswer ActivityTaskManager
   ```

   If you see `Background activity launch blocked!`, the invisible screen was
   blocked, so re-check steps 3–4 of MIUI / HyperOS setup.

| Event log shows | Meaning / fix |
|---|---|
| Nothing at all on a call | Master switch off, or Autostart / battery restrictions stop the receiver |
| Ring detected, then **Blocked by Android** | Display over other apps, Xiaomi pop-up windows, or Show on Lock screen is off |
| **Error** speech model missing / failed to start | The model folder is missing or broken: rebuild with `assets/model-uz` |
| Listening → **Timed out** | “allo” wasn't recognized within 30 s. Raise the sensitivity, lower the ringtone volume, speak closer |
| **Answer failed** | Answer-calls permission missing, or the ROM refused |

The ringtone plays right next to the microphone, which lowers recognition
confidence (see the results below). A lower ringtone volume helps.

### Verified so far (emulator, SDK 37)

**Recognition**, using the app's real Vosk model, grammar and `WakeMatcher`,
fed from recordings through the debug hook. The recordings are macOS
text-to-speech voices; no Uzbek voice is available.

| Recording | Clean | + ringtone 6 dB | + ringtone 0 dB |
|---|---|---|---|
| Russian voice “Алло” | ✅ allo 0.60 | ✅ a'lo 0.55 | ✅ a'lo 0.60 |
| Turkish voice “Alo” | ✅ a'lo 0.86 | ✅ a'lo 1.00 | ✅ a'lo 1.00 |
| Kazakh voice “Алло” | ✅ allo 0.61 | ❌ a'lo 0.36 | ❌ [unk] |
| Russian sentence without “allo” | ✅ not detected | ✅ not detected | ✅ not detected |
| Kazakh sentence without “allo” | ✅ not detected | — | — |

So there were no false triggers, and 7 of 9 “allo” recordings were caught.
The two misses were the Kazakh voice under a loud ringtone; at 0.36 it would
pass with the sensitivity slider at about 0.7 or higher.

**Real call path, with the real microphone and recognizer:**
- **Opening the listener:** `BAL_ALLOW_SAW_PERMISSION` → the invisible screen
  opened from the background, also over the lock screen.
- **Starting the service:** `Background started FGS: Allowed … uidState: TOP`;
  the service ran in the foreground with the microphone type
  (`types=0x80`).
- **Listening:** started **1.2 s** after the ring (model load).
- **Mic really recording:** AppOps showed `RECORD_AUDIO … Access: [top-s] …
  Running`, so the mic was recording and nothing was silenced or rejected.
- **“allo”:** the emulator mic can't hear us, so the debug hook supplied it.
  The mic was released at once (AppOps: recording ended), and
  `acceptRingingCall()` answered the call **24 ms** later.
- With “Display over other apps” off, the launch showed `BAL_BLOCK`, and
  3 s later the app logged “Blocked by Android” and posted the notice.

**Other checks:**
- **Onboarding:** all five permissions were granted through the real system
  dialogs. On Android 11+, the overlay setting opens a list rather than the
  app's own page.
- **Test screen:** the hit indicator works.
- **Languages:** Uzbek, Russian and English, including native notifications
  and log messages; the choice survives a restart.

**The speaker result on the emulator proves nothing.** The emulator has no
earpiece (`getAvailableCommunicationDevices: no EARPIECE!`), so Android's
telecom puts every call on the speaker by itself.

**Not verified yet:**
- A real Uzbek speaker saying “allo” while a real ringtone plays.
- The speaker on a phone that has an earpiece.
- Anything specific to HyperOS: Autostart, the pop-up and lock-screen
  switches, and battery limits.

These all need the Redmi and a real call.

### Debug test hook (debug builds only)

`DebugReceiver.kt` lets you test without speaking into the device.
- It is declared only in `src/debug/AndroidManifest.xml`, so it doesn't exist
  in a release build.
- It requires `android.permission.DUMP`: the adb shell has it, normal apps
  can't get it.
- It does nothing unless the app is debuggable.

```bash
P=com.aloanswer.alo_answer; R=$P/.DebugReceiver
# Stand-in detector: no Vosk, and the mic is not opened.
adb shell am broadcast -n $R -a $P.debug.FAKE_DETECTOR --ez on true
# Master switch on/off.
adb shell am broadcast -n $R -a $P.debug.ENABLE --ez on true
# Simulate an incoming call (emulator only), then "say alo".
adb emu gsm call 5551234
adb shell am broadcast -n $R -a $P.debug.SAY_ALO
# Run the real recognizer + wake-word rules over a recording:
# raw 16 kHz mono 16-bit little-endian PCM, copied into the app's files/ folder.
adb push test.pcm /data/local/tmp/
adb shell "cat /data/local/tmp/test.pcm | run-as $P sh -c 'cat > files/test.pcm'"
adb shell am broadcast -n $R -a $P.debug.RECOGNIZE_PCM --es file test.pcm
adb logcat -s AloAnswer        # → "test.pcm: wake word DETECTED / not detected"
# Turn the stand-in detector off afterwards.
adb shell am broadcast -n $R -a $P.debug.FAKE_DETECTOR --ez on false
# Pretend Bluetooth headset "Demo Buds" in the call screen's audio picker
# (emulators have no Bluetooth audio). Picking it changes nothing real; a new
# call starts "on" it, like a real headset. Off: --ez on false.
adb shell am broadcast -n $R -a $P.debug.FAKE_BLUETOOTH --ez on true
```

To make a PCM file on a Mac:
`say -v Milena -o a.aiff "Алло" && afconvert -f WAVE -d LEI16@16000 -c 1 a.aiff a.wav`,
then strip the WAV header, for example with Python's `wave` module.

---

## Languages

The UI comes in **Oʻzbekcha, Русский and English**, plus “Phone language”
(follow the phone). One setting for the whole app: Settings → General →
Language (or 文A on the permissions screen).
- Android 13+: stored as the system per-app language (`LocaleManager`,
  `res/xml/locale_config.xml`), so it also appears in the phone's Settings →
  Apps → Alo Answer → Language, and every open screen switches at once.
  A choice from an older version is handed over once (`App`).
- Android 8–12: stored in `alo_settings` (`language`); every screen
  (including the dial pad and call screen) rebuilds itself when you return.
- Notifications and services use it through `Settings.localized()`.
- All texts are in `app/src/main/res/values{,-uz,-ru}/strings.xml`. The texts
  of the app screens start with `ui_` (they were moved over from the Flutter
  version's `lib/l10n.dart` by a script, unchanged).
- Uzbek uses the proper Latin letters: `ʻ` (U+02BB) in oʻ/gʻ and `ʼ`
  (U+02BC).
- Each event-log entry keeps the language that was active when it was
  written.

---

## Loudspeaker: what works and what doesn't

**On a real Redmi**, voice answering worked, but the AudioManager request did
not move the call: `setCommunicationDevice(TYPE_BUILTIN_SPEAKER)` returned
`true`, yet `getCommunicationDevice()` stayed `BUILTIN_EARPIECE`.

### Auto speaker (accessibility service, recommended)

`SpeakerClickService.kt` is an optional accessibility service. Right after the
app answers, it:

1. brings the dialer's ongoing-call screen to the front with
   `TelecomManager.showInCallScreen(false)`;
2. finds the **Speaker** button on that screen, by view id containing
   “speaker” or a label “Speaker / Динамик / Karnay / Dinamik”;
3. presses it once, through `ACTION_CLICK` or a tap gesture;
4. checks the real route through `AudioManager`.

The result shows on the home screen as **“Karnay yoqildi”** (speaker on), or
as a failure. A failure lists the call screen's button ids, so the matcher can
be adjusted.

- **What it can see:** it ignores every event except for up to 8 s after the
  app answers a call, and it stores nothing.
- **Turning it on:** Settings → Additional settings → Accessibility →
  Downloaded apps → **Alo Answer: speaker** → On. The app's home screen has an
  “Avto-karnay” card that links there.
- **If the switch is greyed out:** Android blocks this for apps installed
  outside a store. First go to Settings → Apps → Alo Answer → ⋮ →
  **Allow restricted settings**.
- **When it's off:** the old AudioManager request runs instead.
- **Play Store:** using accessibility for this is not acceptable there. It's
  fine for sideloading.
- **Not verified:** pressing the button wasn't checked on the emulator, which
  has no earpiece, so its calls are always on the speaker. It still needs a
  call on the Redmi.

**An app that isn't the default phone app can't reliably force a SIM call to
the loudspeaker.** The telephony call belongs to the system dialer.

What the app does after answering (`SpeakerRouter.kt`):

- **API 31+:** `AudioManager.setCommunicationDevice(<TYPE_BUILTIN_SPEAKER device>)`,
  then it reads back `getCommunicationDevice()`.
- **Below 31:** `setMode(MODE_IN_CALL)` and `setSpeakerphoneOn(true)`, then it
  reads back `isSpeakerphoneOn()`. `setSpeakerphoneOn` is deprecated since
  API 34, and `MODE_IN_CALL` is meant for the telephony app, so this is
  expected to be ignored.

The result is logged and shown on the home screen as either "Android says the
speaker was selected" or "Speaker routing did not work". The first only means
Android *accepted the request*; it doesn't prove the call audio moved, so
check by ear. `setCommunicationDevice` also applies to the requesting app's
own communication audio and lasts only while the app's process is alive.

### What full speaker control would take (not built)

Become the **default phone app**:

1. Hold `RoleManager.ROLE_DIALER` (API 29+). The user has to pick the app as
   the default phone app.
2. Implement an `InCallService`, then route with
   `InCallService.setAudioRoute(CallAudioState.ROUTE_SPEAKER)`. That method is
   deprecated since API 34; the replacement is
   `requestCallEndpointChange(CallEndpoint, Executor, OutcomeReceiver)`.

The cost is large. Android only grants the role to an app that handles
`ACTION_DIAL` (a dial pad) and fully implements `InCallService` with both an
incoming-call and an in-call UI. That means replacing HyperOS's phone app for
**every** call:

- dialer, call screens, mute, hold, keypad (DTMF), Bluetooth/headset routing,
  call waiting, conference calls;
- plus the HyperOS-specific behavior users expect.

Emergency calls always go through the preloaded dialer. In return you get
reliable speaker routing and an answer path that doesn't need the invisible
screen.

---

## Play Store policy risk

This app is meant for **sideloading**. Publishing it is risky:

- **The default-handler rule:** Play's rule that an app "must be the default
  Phone or Assistant handler" covers the **Call Log** permission group
  (e.g. `READ_CALL_LOG`, `WRITE_CALL_LOG`, `PROCESS_OUTGOING_CALLS`) and the
  SMS group. I found no Play policy text that names `ANSWER_PHONE_CALLS` or
  `READ_PHONE_STATE`. This app requests neither group.
- **The real risks:**
  - **Microphone foreground service declaration.** Play asks for a
    description and a video that "show the user initiate the microphone". This
    app opens the mic automatically when a call rings, which a reviewer may
    reject.
  - **Display over other apps used to open a screen from the background.** It
    is a sensitive permission, and using it only to get around background
    limits is the kind of use reviewers question.
  - **A deprecated answer API.** `acceptRingingCall()` has been deprecated
    since API 29, and Google points call-handling apps to `InCallService`.
- **Size:** the bundled ~51 MB compressed model makes the download large. Play
  would want it delivered as an asset pack.

A default-dialer build would avoid most of this, at the cost described above.
