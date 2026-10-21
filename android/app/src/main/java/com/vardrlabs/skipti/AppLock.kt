package com.vardrlabs.skipti

import android.app.KeyguardManager
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.os.SystemClock
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * D-163 (SPEC §2.3 A6, §3.7): the app lock, the twin of iOS's AppLock.swift.
 * SKIPTI locks when it starts and when it has been in the background longer
 * than the chosen time (two minutes unless changed), and opens with the
 * phone's biometrics or its screen lock: SPEC's A6 says "biometric or device
 * credential", and a finger that stops matching must not lock someone out of
 * their own ledger. The platform `BiometricPrompt` only (no library, as in
 * KitReveal); allowing the screen lock inside it needs Android 11, so older
 * phones are told the lock is not available rather than given a weaker one.
 * While locked on, the Recents thumbnail shows nothing (Android 13+).
 */
enum class LockTimeout(val stored: String, val seconds: Long?, val label: String) {
    IMMEDIATELY("0", 0, "Immediately"),
    ONE_MINUTE("60", 60, "After 1 minute"),
    TWO_MINUTES("120", 120, "After 2 minutes"),
    FIVE_MINUTES("300", 300, "After 5 minutes"),
    FIFTEEN_MINUTES("900", 900, "After 15 minutes"),
    OFF("off", null, "Off"),
    ;

    companion object {
        val STANDARD = TWO_MINUTES

        /** Unknown or missing reads as the default: a setting this build does
         *  not know never turns the lock off. */
        fun parse(stored: String?): LockTimeout = entries.firstOrNull { it.stored == stored } ?: STANDARD
    }
}

/** The pure rules, tested on the JVM. Times are milliseconds. */
object AppLockRules {
    /** Locked at start whenever a lock is possible and on. */
    fun lockedAtLaunch(timeout: LockTimeout, available: Boolean): Boolean = available && timeout != LockTimeout.OFF

    /** Coming back to the front: locks once the time in the background
     *  reached the timeout. A clock that went backwards locks too, so changing
     *  the phone's time cannot skip the lock. */
    fun locksOnReturn(timeout: LockTimeout, available: Boolean, backgroundedAtMs: Long?, nowMs: Long): Boolean {
        val limit = timeout.seconds ?: return false
        if (!available || backgroundedAtMs == null) return false
        val away = nowMs - backgroundedAtMs
        return away < 0 || away >= limit * 1000
    }

    /** D-225 fix wave (M6): the App lock page asks before turning the lock off, and only then: choosing Off while it
     *  is already off just goes back (the twin of iOS's `AppLockPage`). */
    fun asksToTurnOff(choice: LockTimeout, current: LockTimeout): Boolean = choice == LockTimeout.OFF && current != LockTimeout.OFF

    /** D-232 final wave: the lifecycle facts the lock decides from, one transition per callback. A configuration change
     *  (rotation; since decision 78 also a light or dark change on Android 12+) stops and makes the activity again
     *  without SKIPTI leaving the front, so it neither starts the time away nor counts as a launch. Everything else
     *  locks exactly as before: a real stop (Home, the app switcher, the screen going off, another app on top, Back)
     *  starts the time away; a configuration change while already in the background keeps that time; and a new process
     *  (process death, then a restore) starts from a fresh [LockLife], so its launch locks. Confirmation C1: the launch's
     *  own decision is made once per process and is never skipped; only a re-creation after it keeps the state, so an
     *  activity made again before the vault first opened (a rotation at once, decision 78's first tell) still locks.
     *  Confirmation 2 (h1): the in-front mark carries the monotonic time of its stop, and only a create within
     *  [IN_FRONT_WINDOW_MS] of it honours the mark, so a stale one can never open a later activity. */
    data class LockLife(
        val locked: Boolean = false,
        val backgroundedAtMs: Long? = null,
        /** The last stop was a configuration change made while SKIPTI was in front, at this monotonic time
         *  (`SystemClock.elapsedRealtime`), or null. */
        val stoppedInFrontAtMs: Long? = null,
        /** This activity replaced one stopped for a configuration change in front: its launch keeps the lock as it was. */
        val recreatedInFront: Boolean = false,
        /** This process has made its launch decision (C1): until then a launch always decides. */
        val launchDecided: Boolean = false,
    )

    /** How soon after an in-front configuration-change stop the replacing activity must be created (h1). The platform
     *  stops, destroys and creates in one relaunch on the main thread; D-232's lock look measured the whole relaunch,
     *  redraw included, at 0.4 to 0.6 s on API 34 and 37. Two seconds is over three times that, and a create that misses
     *  it costs only one unlock prompt (fail closed), while a shorter window leaves a stale mark less time. */
    const val IN_FRONT_WINDOW_MS = 2_000L

    /** `onStop`. [nowMs] is the wall clock (time away); [elapsedMs] the monotonic clock (the in-front mark). */
    fun stopped(life: LockLife, changingConfigurations: Boolean, nowMs: Long, elapsedMs: Long): LockLife = when {
        changingConfigurations && life.backgroundedAtMs == null -> life.copy(stoppedInFrontAtMs = elapsedMs)
        changingConfigurations -> life
        else -> life.copy(backgroundedAtMs = nowMs, stoppedInFrontAtMs = null, recreatedInFront = false)
    }

    /** `onCreate`: what the last stop said, for this activity alone, and only if it was moments ago on the monotonic
     *  clock. A difference below zero or past the window ignores the mark, so the launch decides (fail closed). */
    fun created(life: LockLife, elapsedMs: Long): LockLife {
        val at = life.stoppedInFrontAtMs
        val fresh = at != null && (elapsedMs - at) in 0..IN_FRONT_WINDOW_MS
        return life.copy(recreatedInFront = fresh, stoppedInFrontAtMs = null)
    }

    /** `onStart`. */
    fun started(life: LockLife, timeout: LockTimeout, available: Boolean, nowMs: Long): LockLife = life.copy(
        locked = life.locked || locksOnReturn(timeout, available, life.backgroundedAtMs, nowMs),
        backgroundedAtMs = null,
    )

    /** The vault opened for a ledger used before (AppRoot's `goOn`): locked at launch, unless this process already
     *  decided and this activity only replaced one SKIPTI kept in front. */
    fun launched(life: LockLife, timeout: LockTimeout, available: Boolean): LockLife =
        if (life.recreatedInFront && life.launchDecided) {
            life
        } else {
            life.copy(locked = lockedAtLaunch(timeout, available), launchDecided = true)
        }
}

object AppLockCopy {
    const val SECTION = "App lock"
    const val TITLE = "SKIPTI is locked"
    const val DETAIL = "Unlock with your fingerprint, face or screen lock."
    const val UNLOCK = "Unlock"
    const val FAILED = "Not unlocked. Try again."
    const val ON = "SKIPTI locks when it starts, and when it has been in the background this long."
    const val OFF_DETAIL = "App lock is off. Anyone with your unlocked phone can open SKIPTI."
    const val UNAVAILABLE =
        "App lock needs a screen lock on this phone, and Android 11 or later. Set one in the phone's Settings."
    const val CONFIRM_TITLE = "Turn off app lock?"
    const val CONFIRM_BODY = "Anyone who picks up your unlocked phone could open SKIPTI and see your ledger."
    const val CONFIRM_OFF = "Turn off"
    const val KEEP_ON = "Keep it on"
}

/** D-225 (L67 to L70): the Security page's rows. The safe card holds App lock, the screen-privacy switch and Your
 *  phones; the careful card ("Careful") holds Erase and Delete account, each drawn in the danger tone. [SettingsMap]
 *  keeps the order; the page partitions it by [CAREFUL]. The twin of iOS's `SecurityRows` (AppLock.swift). */
object SecurityRows {
    val CAREFUL: Set<SettingItem> = setOf(SettingItem.ERASE, SettingItem.DELETE_ACCOUNT)

    /** App lock's summary when this phone has no lock to use. */
    const val NO_PHONE_LOCK = "Needs a phone lock"

    /** The Erase row's summary (the page it opens asks before anything goes). */
    const val ERASE_SUMMARY = "Asks first. Cannot be undone."

    /** The Delete account row's summary (deletion waits 7 days, `PhonesCopy.DELETE_GRACE`). */
    const val DELETE_ACCOUNT_SUMMARY = "A week to change your mind"

    /** App lock's summary: the chosen timeout's label, or [NO_PHONE_LOCK] whatever the timeout when the phone cannot lock. */
    fun appLockLine(timeout: LockTimeout, available: Boolean): String = if (available) timeout.label else NO_PHONE_LOCK

    /** The section's items split into the two cards (safe, careful), each in [SettingsMap]'s order. */
    fun partition(items: List<SettingItem>): Pair<List<SettingItem>, List<SettingItem>> = items.partition { it !in CAREFUL }
}

/** The lock's state for the whole app. */
object AppLock {
    private const val PREFS = "skipti-applock"
    private const val KEY = "timeout"

    var timeout by mutableStateOf(LockTimeout.STANDARD)
        private set
    var locked by mutableStateOf(false)
        private set
    var failure by mutableStateOf<String?>(null)
        private set
    /** The lifecycle facts; a new process starts fresh (D-232 final wave, [AppLockRules.LockLife]). D-244: volatile, as
     *  [away] is read off the main thread. */
    @Volatile private var life = AppLockRules.LockLife()

    /** D-244 (ADR-0043): SKIPTI left the front for real (a stop that was no configuration change) and has not come back
     *  ([wentToBackground] set the time away, [cameToFront] clears it). Read before [cameToFront] in onStart, it says the
     *  start is a return, never a re-creation; read anywhere else, that SKIPTI is not in front. */
    val away: Boolean get() = life.backgroundedAtMs != null
    private var prompting = false

    /** Whether this phone can lock SKIPTI: a screen lock, and Android 11+. */
    fun available(context: Context): Boolean =
        Build.VERSION.SDK_INT >= 30 &&
            (context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isDeviceSecure

    fun load(context: Context) {
        timeout = LockTimeout.parse(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null))
    }

    fun set(context: Context, choice: LockTimeout) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, choice.stored).apply()
        timeout = choice
    }

    private fun moved(next: AppLockRules.LockLife) {
        life = next
        locked = next.locked
    }

    fun launch(context: Context) = moved(AppLockRules.launched(life, timeout, available(context)))

    /** `onCreate`. */
    fun created(elapsedMs: Long = SystemClock.elapsedRealtime()) = moved(AppLockRules.created(life, elapsedMs))

    /** `onStop`: [changingConfigurations] is the activity's `isChangingConfigurations`. */
    fun wentToBackground(
        changingConfigurations: Boolean,
        nowMs: Long = System.currentTimeMillis(),
        elapsedMs: Long = SystemClock.elapsedRealtime(),
    ) = moved(AppLockRules.stopped(life, changingConfigurations, nowMs, elapsedMs))

    fun cameToFront(context: Context, nowMs: Long = System.currentTimeMillis()) =
        moved(AppLockRules.started(life, timeout, available(context), nowMs))

    /** One system prompt, new every time. */
    suspend fun unlock(context: Context) {
        if (!locked || prompting || Build.VERSION.SDK_INT < 30) return
        prompting = true
        try {
            if (systemUnlockPrompt(context)) {
                moved(life.copy(locked = false))
                failure = null
                // D-244 (ADR-0043): opening the lock is a front moment (an icon change that arrived waited for it).
                SettingsSync.frontMoment(context)
            } else {
                failure = AppLockCopy.FAILED
            }
        } finally {
            prompting = false
        }
    }
}

/** The platform prompt: biometrics or the screen lock (Android 11+). */
private suspend fun systemUnlockPrompt(context: Context): Boolean = suspendCancellableCoroutine { cont ->
    val prompt = BiometricPrompt.Builder(context)
        .setTitle("Unlock SKIPTI")
        .setAllowedAuthenticators(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL,
        )
        .build()
    val cancel = CancellationSignal()
    cont.invokeOnCancellation { cancel.cancel() }
    prompt.authenticate(
        cancel,
        context.mainExecutor,
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                if (cont.isActive) cont.resume(true)
            }

            // A face or finger that does not match is not terminal: the
            // system prompt says so and stays open. Only an error ends it.
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                if (cont.isActive) cont.resume(false)
            }
        },
    )
}

/** Instead of everything while locked. */
@Composable
fun AppLockScreen() {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { AppLock.unlock(context) }
    Column(
        Modifier
            .fillMaxSize()
            .auroraBackground(dark)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.weight(1f))
        // D-225 (Design 13; art.py 44): Lucide's lock at `lockGlyph` in textDim above the title at the lock title's size,
        // on the art ground (iOS's SF lock went for the same drawing).
        Icon(TabIcons.Lock, contentDescription = null, tint = Tok.textDim(dark), modifier = Modifier.size(ThemeArt.m("lockGlyph").dp))
        Text(AppLockCopy.TITLE, style = Type.display(dark).copy(fontSize = TypeScale.lockTitle.sp), textAlign = TextAlign.Center)
        Text(AppLockCopy.DETAIL, style = Type.bodyDim(dark), textAlign = TextAlign.Center)
        AppLock.failure?.let { ErrorLine(it, dark) }
        Spacer(Modifier.weight(1f))
        PrimaryAction(AppLockCopy.UNLOCK, dark) { scope.launch { AppLock.unlock(context) } }
    }
}

/** D-225 (Design 4): Security → App lock, a choice page of the six timeouts (the stored one checked) with the on or off
 *  caption under the card. Every choice but Off writes at once and returns; Off stays on the page and asks first (D-224's
 *  warning as the question opens), writing only from "Turn off", then returns. A phone that cannot lock shows only the
 *  unavailable caption. The twin of iOS's `AppLockPage`. */
@Composable
fun AppLockPage(dark: Boolean, onClose: () -> Unit) {
    val context = LocalContext.current
    val available = remember { AppLock.available(context) }
    var confirmingOff by remember { mutableStateOf(false) }
    if (!available) {
        PushedWindow(AppLockCopy.SECTION, dark, onClose) { CardCaption(AppLockCopy.UNAVAILABLE, dark) }
        return
    }
    ChoicePage(
        title = AppLockCopy.SECTION,
        options = LockTimeout.entries.map { ChoiceOption(it, it.label) },
        chosen = AppLock.timeout,
        dark = dark,
        onPick = { choice ->
            when {
                AppLockRules.asksToTurnOff(choice, AppLock.timeout) -> confirmingOff = true
                choice == LockTimeout.OFF -> onClose()
                else -> AppLock.set(context, choice)
            }
        },
        onClose = onClose,
        caption = if (AppLock.timeout == LockTimeout.OFF) AppLockCopy.OFF_DETAIL else AppLockCopy.ON,
        stays = { it == LockTimeout.OFF },
    )
    // D-224 (Design 3): turning the lock off asks first; the question warns as it opens.
    HapticWarning(confirmingOff)
    if (confirmingOff) {
        SkiptiAlertDialog(
            onDismissRequest = { confirmingOff = false },
            title = { Text(AppLockCopy.CONFIRM_TITLE) },
            text = { Text(AppLockCopy.CONFIRM_BODY) },
            confirmButton = {
                TextButton(onClick = {
                    AppLock.set(context, LockTimeout.OFF)
                    confirmingOff = false
                    onClose()
                }) { Text(AppLockCopy.CONFIRM_OFF, color = Tok.owed(dark)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingOff = false }) { Text(AppLockCopy.KEEP_ON) }
            },
        )
    }
}

// ---- D-164: the rest of SPEC §3.7 ----

/**
 * Screen privacy (the founder, 2026-09-25: a setting, off by default, so a
 * balance can still be screenshotted and sent to the group). On, the whole
 * window is FLAG_SECURE: no screenshots, no screen recording, and a blank
 * Recents card on every Android version. iOS cannot block screenshots and
 * hides the ledger during recording instead; the copy on each says which.
 */
object ScreenPrivacy {
    private const val PREFS = "skipti-privacy"
    private const val KEY = "block-capture"

    var enabled by mutableStateOf(false)
        private set

    fun load(context: Context) {
        enabled = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)
    }

    fun set(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply()
        enabled = on
        (context as? android.app.Activity)?.window?.let { apply(it) }
    }

    fun apply(window: android.view.Window) {
        if (enabled) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}

object PrivacyCopy {
    const val SECTION = "Privacy"
    const val CAPTURE_TOGGLE = "Block screenshots and screen recording"
    const val CAPTURE_DETAIL =
        "Your ledger stays out of screenshots, screen recordings and the Recents preview. Leave this off to screenshot a balance for your group."
    fun modified(kind: String) =
        "This phone looks $kind. Other apps may be able to read what SKIPTI stores and shows. SKIPTI still works; this is a warning, not a block."
}

/** SPEC §3.7: "Detect and warn, never block." Nothing is logged or sent. */
object DeviceIntegrity {
    val ROOT_PATHS = listOf(
        "/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su", "/system/app/Superuser.apk",
        "/data/adb/magisk", "/system/sd/xbin/su", "/data/local/xbin/su", "/data/local/bin/su",
    )

    fun looksRooted(exists: (String) -> Boolean, buildTags: String?): Boolean =
        buildTags?.contains("test-keys") == true || ROOT_PATHS.any(exists)

    fun looksRooted(): Boolean = looksRooted({ java.io.File(it).exists() }, Build.TAGS)
}

/** D-164: what SKIPTI copies is marked sensitive, so the system keyboard's
 *  and clipboard's previews hide it (Android 13+). An invite link is a
 *  secret until it is used. */
fun copySecret(context: Context, text: String) {
    val clip = android.content.ClipData.newPlainText("SKIPTI", text)
    clip.description.extras = android.os.PersistableBundle().apply {
        putBoolean("android.content.extra.IS_SENSITIVE", true)
    }
    (context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(clip)
}

/** D-225 (Design 4; L-D217-9): the screen-privacy row of Security's first card: the platform's true title, its honest
 *  limit as the summary on as many lines as it needs, and the drawn switch a row gap clear of the words; the whole row
 *  toggles (a Switch to TalkBack). */
@Composable
fun PrivacyRow(dark: Boolean) {
    val context = LocalContext.current
    ListRow(
        PrivacyCopy.CAPTURE_TOGGLE,
        dark,
        parts = listOf(PrivacyCopy.CAPTURE_DETAIL),
        trailing = RowTrailing.Toggle(ScreenPrivacy.enabled) { ScreenPrivacy.set(context, it) },
        summaryLines = 0,
    )
}

/** D-225 (Design 4): on a phone that looks rooted, SPEC §3.7's warning in `watch`, in its own glass card at the top of
 *  Security (art.py 41: 4.5 on every card ground). Draws nothing on any other phone. */
@Composable
fun ModifiedPhoneWarning(dark: Boolean) {
    val rooted = remember { DeviceIntegrity.looksRooted() }
    if (rooted) {
        Text(
            PrivacyCopy.modified("rooted"),
            style = Type.bodyDim(dark).copy(color = Tok.watch(dark), fontSize = TypeScale.detail.sp),
            modifier = Modifier.fillMaxWidth().glassCard(dark),
        )
    }
}
