package com.vardrlabs.skipti

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import uniffi.skipti_core.skiptiCoreVersion
import java.io.File

/**
 * The app root. §3.4's structural gate: no balance, no settle, no add until
 * the Recovery Kit is CONFIRMED AND PERSISTED, exactly iOS's root gate.
 * Persisting happens on the kit screen's completion; a failed save blocks
 * completion (a kit the app cannot store again is a kit the user loses).
 *
 * Everything money-shaped renders through core FFI (D-46's rule carried
 * over): ffiMoneyFormat here, no Kotlin NumberFormat anywhere, ever.
 *
 * The post-gate home is §11.4's balance screen over the USER'S OWN ledger
 * (BalanceSource.VAULT, read-only, never seeded): empty until they add
 * something, rendering §11.6's copy. Fixtures are reachable only through
 * the headless args, which BYPASS the gate (iOS SkiptiApp.swift's rule:
 * they are not user flows).
 */
class MainActivity : ComponentActivity() {
    /** D-159: an invite link the system handed the app (a scanned QR, a
     *  message), once the domain's app links are live. */
    private val openedInvite = androidx.compose.runtime.mutableStateOf<String?>(null)

    /** D-196: the expense a tapped push named (its data, read as untrusted:
     *  PushRoute accepts only well-formed ids, and it only navigates). */
    private val openedPush = androidx.compose.runtime.mutableStateOf<PushRoute.Target?>(null)
    /** D-197: a tapped invite push opens the invitations. */
    private val openedInvitations = androidx.compose.runtime.mutableStateOf(false)
    /** D-198: a tapped "Was this you?" push opens Your phones. */
    private val openedPhones = androidx.compose.runtime.mutableStateOf(false)
    /** D-210: a tapped group push (a request, a removal, a role, a deletion)
     *  opens that group's page. */
    private val openedGroup = androidx.compose.runtime.mutableStateOf<String?>(null)

    private fun inviteIn(intent: Intent?): String? =
        intent?.data?.toString()?.takeIf { InviteLink.token(it) != null }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        inviteIn(intent)?.let { openedInvite.value = it }
        PushRoute.target(intent)?.let { openedPush.value = it }
        if (PushRoute.isInvite(intent)) openedInvitations.value = true
        if (PushRoute.isDevice(intent)) openedPhones.value = true
        PushRoute.groupId(intent)?.let { openedGroup.value = it }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // D-232 (decision 74): the appearance default is settled first of all, before anything can read the mode or
        // open (and so create) the vault: the `--emulator-as` sign-in below opens it on an IO thread.
        Appearance.settleDefault(applicationContext)
        // D-232 (decision 78): Android 12+ is told the app's own night mode, so the window theme, the selection
        // toolbar and the next launch's splash follow it. Changing it makes this activity again, once.
        Appearance.tellNightMode(applicationContext)
        // D-232 final wave: whether this activity only replaces one stopped for a configuration change, before the
        // vault can open and launch the lock.
        AppLock.created()
        VaultGate.appContextRef = applicationContext
        // C2: the username blocklist (the cloud's own file), once.
        UsernameRules.load(applicationContext)
        // D-191: Firebase before anything can ask for the account.
        FirebaseConfig.configure(applicationContext)
        // D-194: `--emulators` runs the ordinary app against the local Auth
        // and Firestore emulators. Debug builds only: another app can start
        // this activity with any extras.
        val launchArgs = intent?.getStringExtra("args")
        if (ProofArgs.emulators(launchArgs, debuggable())) {
            com.google.firebase.auth.FirebaseAuth.getInstance().useEmulator("localhost", 9099)
            Cloud.emulator = true
            // D-210: `--emulators --emulator-as <sub>` signs in as that
            // subject first (a look with several accounts); Debug builds only.
            ProofArgs.emulatorAs(launchArgs, debuggable())?.takeIf { EmulatorSignIn.firstThisProcess() }?.let { sub ->
                val vault = File(applicationContext.filesDir, "ledger-vault.db").absolutePath
                kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch { EmulatorSignIn.signIn(sub, vault) }
            }
        }
        // D-191: app-wide Compose state lives in Kotlin objects, created on
        // first touch. A background load (the shell's IO effect) or the first
        // composition could each be first; a state created inside
        // composition's snapshot and touched from an IO coroutine crashed
        // every launch ("Reading a state that was created after the snapshot
        // was taken", the D-189 bell's count, gone since D-192). Create them here, on
        // the main thread and before any frame, like Appearance and AppLock.
        GroupPictures.createState()
        SyncStatus.createState()
        openedInvite.value = inviteIn(intent)
        openedPush.value = PushRoute.target(intent)
        openedInvitations.value = PushRoute.isInvite(intent)
        openedPhones.value = PushRoute.isDevice(intent)
        openedGroup.value = PushRoute.groupId(intent)
        IncomingInvites.createState()
        Notices.createState()
        GroupRoles.createState()
        GroupJobs.createState()
        RemoteSignOut.createState()
        Push.ensureChannel(applicationContext)

        // D-144, D-212: the person's theme, mode and text size, read
        // before the first frame.
        Appearance.load(applicationContext)
        // D-163: the app lock's setting, before the first frame too.
        AppLock.load(applicationContext)
        // D-164: screen privacy (off by default), applied before the first
        // frame so a screenshot never catches one.
        ScreenPrivacy.load(applicationContext)
        ScreenPrivacy.apply(window)
        // D-165: the default currency (D-244: once chosen, it follows the account).
        DefaultCurrency.load(applicationContext)
        // D-249 fix wave (M6): Your spending's view and Spending on Home, before the first frame (no flip from the
        // defaults on Home or the page).
        SpendingPrefs.load(applicationContext)
        setContent {
            // The phone's configuration with the chosen mode's night bit, so
            // every screen's `isSystemInDarkTheme()` follows the setting, and
            // D-212: SKIPTI's own text size, on the phone's. Every dialog,
            // sheet and menu applies the same again inside its own window.
            SkiptiWindowLocals {
                val dark = isSystemInDarkTheme()
                // D-184: edge to edge on every Android version, the same as
                // Android 15+ imposes on an app that targets it (there the
                // account glyph sat on the battery and the bar on the
                // gesture handle). Bar icons follow SKIPTI's own mode, not
                // the phone's, so they stay legible with Appearance set.
                androidx.compose.runtime.DisposableEffect(dark) {
                    val style = if (dark) {
                        androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    } else {
                        androidx.activity.SystemBarStyle.light(
                            android.graphics.Color.TRANSPARENT,
                            android.graphics.Color.TRANSPARENT,
                        )
                    }
                    enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                    onDispose {}
                }
                MaterialTheme(
                    colorScheme = skiptiColorScheme(dark),
                    typography = remember(Appearance.theme) { Type.materialTypography(Appearance.theme) },
                ) {
                    // D-184: one place keeps every screen clear of the status
                    // bar, the navigation bar and the keyboard.
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Tok.bg(dark))
                            .windowInsetsPadding(WindowInsets.safeDrawing)
                    ) {
                        // D-226 (L36): the window's art holder, over every route: Aurora's topmost page art records into
                        // it and its glass cards blur it (Backdrop.kt). Provided here, not in HomeShell: HomeShell's
                        // pushed routes return before its shell Box, and the proofs and the lock draw art too.
                        CompositionLocalProvider(LocalArtBackdrop provides rememberArtBackdrop()) {
                            AppRoot(
                                verificationArg = proofArg(),
                                forceWelcome = ProofArgs.welcome(intent?.getStringExtra("args"), debuggable()),
                                openedInvite = openedInvite.value,
                                onInviteHandled = { openedInvite.value = null },
                                openedPush = openedPush.value,
                                onPushHandled = { openedPush.value = null },
                                openedInvitations = openedInvitations.value,
                                onInvitationsHandled = { openedInvitations.value = false },
                                openedPhones = openedPhones.value,
                                onPhonesHandled = { openedPhones.value = false },
                                openedGroup = openedGroup.value,
                                onGroupHandled = { openedGroup.value = null },
                            )
                        }
                        // D-224 (Design 5): the small moments' toast, over the root and above every route; the root's
                        // view plays their haptics. It takes no touch.
                        DoneToastHost(barShown = ShellBarShown.value)
                    }
                }
                // D-250: the person's theme or mode change, fading from the old look over the whole window (it outlives
                // the activity a mode change makes again). It takes no touch.
                ThemeCrossfadeOverlay()
            }
        }
    }

    /** D-158: other people write shared groups, so coming to the front
     *  fetches what changed. D-178: and again every 10 s while SKIPTI stays
     *  in front, stopped when it leaves. Quiet; nothing without a session;
     *  never for the headless proof launches. */

    private fun debuggable(): Boolean =
        (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

    private fun proofArg(): String? = ProofArgs.proof(intent?.getStringExtra("args"), debuggable())

    override fun onResume() {
        super.onResume()
        if (proofArg() == null) {
            // D-192: coming to the front reaches the cloud sync (Firestore
            // listeners from the plan's unit 4; nothing yet).
            CloudSync.appIsActive(true)
        }
    }

    override fun onPause() {
        CloudSync.appIsActive(false)
        // D-247 fix wave (review M1): a settle held for Undo lands as SKIPTI stops being the one in front (Recents, a
        // split screen), the twin of iOS's inactive phase: a task removed from Recents may get no onStop. Not for a
        // re-creation: the person is still here.
        if (!isChangingConfigurations) SettleHold.shared.commit()
        super.onPause()
    }

    /** D-163: time away decides whether the app lock closes again; proof
     *  launches are not user flows and skip it. While the lock is on, the
     *  Recents thumbnail shows nothing (Android 13+). */
    override fun onStart() {
        super.onStart()
        if (proofArg() != null) return
        // D-244: a return after a real stop (a re-creation sets no time away), read before the lock's decision clears it.
        val returned = AppLock.away
        AppLock.cameToFront(this)
        if (Build.VERSION.SDK_INT >= 33) {
            setRecentsScreenshotEnabled(!(AppLock.available(this) && AppLock.timeout != LockTimeout.OFF))
        }
        // D-244 (ADR-0043): the front moment, after the lock's decision: an icon change that arrived from the account is
        // made now, only if it is pending and SKIPTI is unlocked. Never after a re-creation.
        if (returned) SettingsSync.frontMoment(this)
    }

    /** D-232 final wave: a configuration change (rotation, decision 78's theme change) stops the activity without
     *  SKIPTI leaving the front, so it is not time away. */
    override fun onStop() {
        super.onStop()
        // D-247 (decision 182): a settle held for Undo lands as SKIPTI leaves the front (before onStop returns: the
        // process may be ended from Recents with no further word). Not for a re-creation: the person is still here.
        if (!isChangingConfigurations) SettleHold.shared.commit()
        if (proofArg() == null) AppLock.wentToBackground(isChangingConfigurations)
    }
}

/**
 * D-144: the Material scheme behind every Material component. `onPrimary`
 * is the PAGE colour, as iOS's primary button draws its label. It was the
 * text colour: #182430 on the light accent (2.44:1) and #e9eef3 on the dark
 * one (1.91:1), under every default-coloured button label ("Sign in" among
 * them). AppearanceTest pins it at 4.5:1 or better in every mode.
 */
internal fun skiptiColorScheme(dark: Boolean): androidx.compose.material3.ColorScheme {
    // D-212: every role a Material component can draw is the theme's own
    // token, so no baseline colour shows through (the purple slider track,
    // the lavender dialog container). AppearanceTest pins each one. The
    // builders keep their own derivations (surfaceTint follows primary).
    return if (dark) {
        darkColorScheme(
            background = Tok.bg(dark), surface = Tok.surface(dark),
            primary = Tok.accent(dark), onPrimary = Tok.onAccent(dark),
            onBackground = Tok.text(dark), onSurface = Tok.text(dark), onSurfaceVariant = Tok.textDim(dark),
            surfaceVariant = Tok.surfaceRaised(dark),
            surfaceContainerLowest = Tok.surface(dark), surfaceContainerLow = Tok.surface(dark),
            surfaceContainer = Tok.surface(dark),
            surfaceContainerHigh = Tok.surfaceRaised(dark), surfaceContainerHighest = Tok.surfaceRaised(dark),
            secondary = Tok.accent(dark), onSecondary = Tok.onAccent(dark),
            secondaryContainer = Tok.accentSoft(dark), onSecondaryContainer = Tok.accent(dark),
            primaryContainer = Tok.accentSoft(dark), onPrimaryContainer = Tok.accent(dark),
            outline = Tok.textFaint(dark), outlineVariant = Tok.hairline(dark),
            error = Tok.owed(dark), onError = Tok.onAccent(dark),
        )
    } else {
        lightColorScheme(
            background = Tok.bg(dark), surface = Tok.surface(dark),
            primary = Tok.accent(dark), onPrimary = Tok.onAccent(dark),
            onBackground = Tok.text(dark), onSurface = Tok.text(dark), onSurfaceVariant = Tok.textDim(dark),
            surfaceVariant = Tok.surfaceRaised(dark),
            surfaceContainerLowest = Tok.surface(dark), surfaceContainerLow = Tok.surface(dark),
            surfaceContainer = Tok.surface(dark),
            surfaceContainerHigh = Tok.surfaceRaised(dark), surfaceContainerHighest = Tok.surfaceRaised(dark),
            secondary = Tok.accent(dark), onSecondary = Tok.onAccent(dark),
            secondaryContainer = Tok.accentSoft(dark), onSecondaryContainer = Tok.accent(dark),
            primaryContainer = Tok.accentSoft(dark), onPrimaryContainer = Tok.accent(dark),
            outline = Tok.textFaint(dark), outlineVariant = Tok.hairline(dark),
            error = Tok.owed(dark), onError = Tok.onAccent(dark),
        )
    }
}

@Composable
fun AppRoot(
    verificationArg: String? = null,
    /** D-245: `--es args --welcome` (Debug builds only): the ordinary app with the welcome shown (a look's tool). */
    forceWelcome: Boolean = false,
    openedInvite: String? = null,
    onInviteHandled: () -> Unit = {},
    openedPush: PushRoute.Target? = null,
    onPushHandled: () -> Unit = {},
    openedInvitations: Boolean = false,
    onInvitationsHandled: () -> Unit = {},
    openedPhones: Boolean = false,
    onPhonesHandled: () -> Unit = {},
    openedGroup: String? = null,
    onGroupHandled: () -> Unit = {},
) {
    // D-198: signed out from elsewhere, or a deletion asked for.
    RemoteSignOutNotices()
    val context = LocalContext.current
    val vaultPath: String by rememberOnce {
        mutableStateOf(File(context.filesDir, "ledger-vault.db").absolutePath)
    }

    var kitLoaded by rememberOnce { mutableStateOf<Boolean?>(null) }
    val scope = rememberCoroutineScope()
    // D-168: after a deliberate erase, back to setup.
    val erased = LedgerErase.generation

    // Launch arguments may carry more than the headless flag (--relay-local
    // rides along). The FIRST token is the headless flag; the LIST is what
    // RelayConfig resolves. iOS's RelayConfig.current equivalent: resolve
    // once, aim every sync hub at the result.
    val args: List<String> = rememberOnce {
        verificationArg?.trim()?.split(Regex("\\s+"))?.filter { it.isNotBlank() } ?: emptyList()
    }
    val headless: String? = rememberOnce { args.firstOrNull()?.removePrefix("--") }

    // Headless verification args bypass the §3.4 gate entirely (not user
    // flows): the balance screen computes from fixtures or the vault, the
    // list reads the vault, the add screen writes it, sign-in and the
    // sync-proof are the §4.2/§4.3 verification surfaces, so no kit needs
    // to exist to verify them. Recorded in BUILD-LOG §3.
    when (headless) {
        "auth-emulator-proof" -> {
            // D-191: a Firebase sign-in against the local Auth emulator.
            AuthEmulatorProof(vaultPath)
            return
        }
        "sync-emulator-proof" -> {
            // D-194: two phones of one account converge through Firestore.
            SyncEmulatorProof(vaultPath)
            return
        }
        "two-account-proof" -> {
            // D-195: Bo joins Al's group with the link among the arguments.
            TwoAccountProof(vaultPath, args.getOrNull(1) ?: "")
            return
        }
        "settings-emulator-proof" -> {
            // D-244: settings follow one account across two phones; the role (a or b) is among the arguments.
            SettingsEmulatorProof(vaultPath, args.getOrNull(1))
            return
        }
        "bar-proof" -> {
            // D-217 Task 4: the three forms of SkiptiBar over rows (a look's tool; every screen uses the bar since Task 8).
            BarProof(args.getOrNull(1))
            return
        }
        "notification-proof" -> {
            NotificationProof()
            return
        }
        "groups-proof" -> {
            // D-179: three groups, then the ordinary tabs on Groups. D-247: `--settle-look <step>` (a look's surface, in
            // the debuggable builds proofs run in): the step's ledger state, then You's page for the ring, Flat 4B's page
            // for the count, else Settle up.
            SettleLook.step = SettleLook.parse(args)
            GroupsProof(vaultPath) {
                var looked by remember { mutableStateOf(SettleLook.step == null) }
                var flat by remember { mutableStateOf<String?>(null) }
                LaunchedEffect(Unit) {
                    if (!looked) {
                        flat = SettleLook.apply(vaultPath)
                        looked = true
                    }
                }
                if (!looked) return@GroupsProof
                when (SettleLook.step) {
                    null -> HomeShell(vaultPath = vaultPath, startOn = HomeTab.GROUPS)
                    "ring", "ring-nolock" -> AccountHubScreen(vaultPath = vaultPath, onOpenSignIn = {}, onBack = {})
                    "group" -> HomeShell(vaultPath = vaultPath, startOn = HomeTab.GROUPS, pushedGroup = flat)
                    else -> HomeShell(vaultPath = vaultPath, startOn = HomeTab.SETTLE)
                }
            }
            return
        }
        "balance" -> {
            BalanceScreen(BalanceSource.FIXTURE, version = skiptiCoreVersion(), vaultPath = vaultPath)
            return
        }
        "balance-vault" -> {
            BalanceScreen(BalanceSource.VAULT_FIXTURES, version = skiptiCoreVersion(), vaultPath = vaultPath)
            return
        }
        "balance-empty" -> {
            BalanceScreen(BalanceSource.EMPTY, version = skiptiCoreVersion(), vaultPath = vaultPath)
            return
        }
        "add" -> {
            AddExpenseScreen(vaultPath = vaultPath)
            return
        }
        "settle" -> {
            SettleScreen(vaultPath = vaultPath)
            return
        }
        "list-vault" -> {
            ExpenseListScreen(vaultPath = vaultPath)
            return
        }
        "signin" -> {
            SignInScreen(vaultPath, onBack = {}) { _, _ -> }
            return
        }
        "export" -> {
            ExportScreen(LedgerStore(vaultPath))
            return
        }
        "import" -> {
            ImportScreen(LedgerStore(vaultPath))
            return
        }
        "export-proof" -> {
            ExportProofScreen(dir = File(context.cacheDir, "export-proof").apply { mkdirs() })
            return
        }
        "expense-conflict" -> {
            // D-143: SPEC §6.1's question, over a throwaway vault.
            ExpenseConflictProofScreen(File(context.cacheDir, "expense-conflict-proof"))
            return
        }
        "import-proof" -> {
            ImportProofScreen(store = LedgerStore(vaultPath))
            return
        }
    }

    // D-225 T7 (A7; D-127): why the vault would not open at launch, and what the last attempt left (null while it
    // opens). Never a toast and never an automatic erase: iOS's recovery screen. D-225 fix wave (I1, A9): Try again
    // first; one attempt at a time.
    var vaultScreen by rememberOnce { mutableStateOf<VaultRecovery.Screen?>(null) }
    var vaultRetry by rememberOnce { mutableIntStateOf(0) }
    val vaultBusy = rememberOnce { VaultRecovery.Busy() }

    // D-245 (U9, decision 177): the vault's `welcome/pending` as this launch read it, and the welcome's state, kept
    // here, above the lock and the shell, so a lock that comes back or a re-made activity keeps its step.
    var welcomePending by rememberOnce { mutableStateOf(false) }
    val welcome = rememberSaveable(saver = WelcomeSession.Saver) { WelcomeSession() }

    suspend fun readVault(): VaultRecovery.Launch =
        withContext(Dispatchers.IO) {
            VaultRecovery.launch(LAUNCHED_KEY, firstLaunch = { WelcomeGate.mark(it) }) { LedgerStore(vaultPath) }
        }

    // D-245: whether the welcome shows: a new install, or the flag still set (a welcome killed midway); a forced one.
    suspend fun readWelcome(opened: VaultRecovery.Launch.Opened): Boolean = forceWelcome ||
        withContext(Dispatchers.IO) { WelcomeGate.atLaunch(opened.launchedBefore) { WelcomeGate.pending(LedgerStore(vaultPath)) } }

    // The vault opened, at launch or from Try again: the app goes on as a normal launch.
    fun goOn(opened: VaultRecovery.Launch.Opened, welcomeShows: Boolean) {
        vaultScreen = null
        // D-163: locked BEFORE the ledger first renders, so it never flashes.
        // A first launch has nothing to lock, and a lock prompt is no welcome.
        if (opened.launchedBefore) AppLock.launch(context)
        // D-244 (ADR-0043): signed in, the account's settings the vault holds are applied, then (once per process) an
        // icon change still pending, at the front only, after the lock's decision above.
        SettingsSync.launched(context)
        // Fix wave I1: a welcome that closed starts fresh (after an erase, the new vault's first run opens at W1 with an
        // empty field); one still open (a re-made activity re-runs this launch) keeps its step.
        if (welcomeShows) welcome.opening()
        welcomePending = welcomeShows
        kitLoaded = true
    }

    // D-175: a first launch goes straight into the app; D-192 removed the
    // Recovery Kit with the relay, so there is no kit step. Re-run after an
    // erase, and after the recovery screen's confirmed erase.
    LaunchedEffect(vaultPath, erased, vaultRetry) {
        kitLoaded = null
        when (val launch = readVault()) {
            is VaultRecovery.Launch.Unavailable -> vaultScreen = VaultRecovery.Screen(launch.reason)
            is VaultRecovery.Launch.Opened -> goOn(launch, readWelcome(launch))
        }
    }

    // D-225 T7 (A7; D-127): a vault that exists and will not open gets the honest screen. Try again reads it again and
    // erases nothing; the ledger's files go only after the screen's own confirm, and a failed erase stays on the screen
    // and says so (it used to be dropped). The erase is the deliberate erase's operation (WORKLOG decision 27): it signs
    // out of the cloud session first, then LedgerErase takes the write-ahead files, the database, then the key.
    vaultScreen?.let { screen ->
        VaultRecoveryScreen(
            reason = screen.reason,
            note = screen.note,
            busy = vaultBusy.working,
            onRetry = {
                if (!vaultBusy.start()) return@VaultRecoveryScreen
                scope.launch {
                    val launch = readVault()
                    when (val after = VaultRecovery.retried(launch)) {
                        null -> (launch as? VaultRecovery.Launch.Opened)?.let { goOn(it, readWelcome(it)) }
                        else -> vaultScreen = after
                    }
                    vaultBusy.finish()
                }
            },
            onErase = {
                if (!vaultBusy.start()) return@VaultRecoveryScreen
                scope.launch {
                    val after = withContext(Dispatchers.IO) {
                        VaultRecovery.erase(screen.reason) { LedgerErase.signOutAndErase(vaultPath, AndroidKeystore(context.applicationContext)) }
                    }
                    if (after == null) {
                        vaultScreen = null
                        vaultRetry++
                    } else {
                        vaultScreen = after
                    }
                    vaultBusy.finish()
                }
            },
        )
        return
    }

    // D-184: the erase is done, and the app says so over what comes next. D-224 (Design 7, 8): the big screen with the
    // check, in place of the alert (the twin of iOS's full-screen cover), left with "OK" or Back as the alert was.
    if (LedgerErase.notice) {
        BackHandler { LedgerErase.notice = false }
        // Follow-up (A-I1): a cover over everything on iOS, so no toast over it.
        CoversShellToast()
        ErasedScreen(onDone = { LedgerErase.notice = false })
        return
    }

    when (kitLoaded) {
        null -> Loading()
        else -> if (AppLock.locked) {
            // D-163: instead of everything while locked (SPEC §3.7).
            AppLockScreen()
        } else if (openedInvite != null) {
            // D-159/D-195: an opened invite link, over the tabs. D-217 final fix: a sheet as on iOS, and system Back
            // closes it as its Cancel does (JoinScreen's own handler takes Back once a join has started).
            BackHandler { onInviteHandled() }
            // Follow-up (A-I1): iOS's sheet covers its toast.
            CoversShellToast()
            JoinScreen(vaultPath, openedInvite, onJoined = { onInviteHandled() }, onBack = onInviteHandled, asSheet = true)
        } else {
            // D-245 (U9): the welcome is a full-screen layer over the shell (Home behind it, hidden from TalkBack while
            // it shows), after the lock on a relaunch (the lock above replaces everything), never on a first launch's
            // lock (there is none: a lock prompt is no welcome).
            Box(if (welcomePending) Modifier.fillMaxSize().clearAndSetSemantics {} else Modifier.fillMaxSize()) {
                HomeShell(
                    vaultPath = vaultPath, pushed = openedPush, onPushHandled = onPushHandled,
                    invitations = openedInvitations, onInvitationsHandled = onInvitationsHandled,
                    phones = openedPhones, onPhonesHandled = onPhonesHandled,
                    pushedGroup = openedGroup, onGroupHandled = onGroupHandled,
                )
            }
            if (welcomePending) {
                WelcomeLayer(vaultPath, WelcomeMode.FIRST_RUN, welcome) { exit ->
                    welcomePending = false
                    welcome.close()
                    scope.launch(Dispatchers.IO) {
                        WelcomeGate.closed(WelcomeMode.FIRST_RUN) { WelcomeGate.clear(LedgerStore(vaultPath)) }
                    }
                    WelcomeLook.after(context, exit)
                }
            }
        }
    }
}

/** The vault flag "this phone has opened SKIPTI before" (D-175's
 *  `app/launched`; it lived on KitGate until D-192). */
private const val LAUNCHED_KEY = "app/launched"

/** D-224 (Design 5, `ToastPlace.bottom(barShown)`): whether the shell's tab bar is composed, so the toast sits clear of
 *  it (and of the +) or near the bottom of a route without it. Memory only. */
internal object ShellBarShown {
    var value by mutableStateOf(false)
}

/** D-223 fix wave (I2): an expense opened from an Activity screen remembers that Activity, as iOS's push does: Back (and
 *  the pop after a delete) returns to it. The whole ledger's Activity ("activity", Home's "See all") opens
 *  `ledger-activity/expense/<id>`; a group's Recent activity (`activity/<group>`) opens `activity/<group>/expense/<id>`. */
internal object ActivityExpenseRoute {
    private const val LEDGER = "activity"
    private const val LEDGER_EXPENSE = "ledger-activity/expense/"
    private const val EXPENSE = "/expense/"

    /** [activity] is the Activity route ("activity" or "activity/<group>"), [expense] the expense it opens. */
    data class Open(val activity: String, val expense: String)

    fun of(activity: String, expense: String): String =
        if (activity == LEDGER) LEDGER_EXPENSE + expense else activity + EXPENSE + expense

    fun parse(route: String?): Open? {
        if (route == null) return null
        if (route.startsWith(LEDGER_EXPENSE)) return Open(LEDGER, route.removePrefix(LEDGER_EXPENSE)).takeIf { it.expense.isNotEmpty() }
        if (!route.startsWith("$LEDGER/") || EXPENSE !in route) return null
        val group = route.removePrefix("$LEDGER/").substringBefore(EXPENSE)
        val expense = route.substringAfter(EXPENSE)
        return if (group.isEmpty() || expense.isEmpty()) null else Open("$LEDGER/$group", expense)
    }
}

/** D-249: where Your spending's page is open, and whether it covers the shown tab's root. */
internal object SpendingOpen {
    const val CARD = "card"
    const val HEADER = "header"

    fun covers(open: String?, tab: HomeTab): Boolean =
        (open == CARD && tab == HomeTab.BALANCE) || (open == HEADER && tab == HomeTab.EXPENSES)
}

/** D-250 fix wave (I4): the route the shell last composed, so the composition a route changes in works out its move
 *  ([PageMotion.move]) before the pages and the tab bar read it. */
internal class RouteMemo(var route: String?)

/** D-250: what a tab shows: its root, or a page over it ([over]: a group's page, Your spending), for [PageMotion.tabMove]. */
internal data class TabPage(val tab: HomeTab, val group: String?, val spending: String?) {
    val over: String?
        get() = when {
            (tab == HomeTab.BALANCE || tab == HomeTab.GROUPS) && group != null -> "group/$group"
            SpendingOpen.covers(spending, tab) -> "spending"
            else -> null
        }

    /** D-250 fix wave (M1): what the tab SHOWS, the page's identity: a group left open on Balance, cleared while Expenses
     *  shows, changes nothing Expenses draws, so its root is neither composed again nor reloaded. */
    val key: Pair<HomeTab, String?> get() = tab to over
}

/** D-143: where system Back goes from each route over the tabs. Pure, so a
 *  JVM test pins it. Null is the tabs. */
internal object HomeRoutes {
    fun parent(route: String?): String? {
        if (route == null) return null
        // D-172: sign-in from Join a group goes back to it.
        if (route == "signin/join") return "join"
        // D-172: sign-in from Join a group goes back to it.
        // D-206: You's groups return to You; their pages return to their group.
        if (route == "account/profile" || route == "account/appearance" || route == "account/security" || route == "account/data") return "account"
        // D-225: the Erase page returns to Security.
        if (route == "devices" || route == "delete-account" || route == "erase") return "account/security"
        if (route == "export") return "account/data"
        // D-210: a removal returns to where it was opened (People, or the
        // group's page); a group's settings and a copy's export return to the page.
        RemovalRoute.parse(route)?.let { return if (it.fromPeople) "people/${it.group}" else null }
        if (route == "signin") return "account/profile"
        if (route == "profile" || route == "about" || route == "kit") return "account"
        if (route == "delete-export") return "delete-account"
        // D-166: import returns to where it was opened from.
        if (route == "import/account") return "account/data"
        // D-168: a refused sign-in returns to Settings.
        if (route == "other-account") return "account"
        // D-146: About → Licences → one licence.
        if (route == "licences") return "about"
        // C2: the username step returns where it was opened (Home, You, Find
        // me); the one change returns to Find me.
        if (route == "username/you") return "account"
        // D-221: You's next card opens the create-group screen and returns to You.
        if (route == "new-group/you") return "account"
        if (route == "username/find-me" || route.startsWith("username/change/")) return "account/profile"
        if (route.startsWith("licence/")) return "licences"
        if (route.startsWith("expense-edit/") && route.endsWith("/detail")) {
            return "expense/" + route.removePrefix("expense-edit/").removeSuffix("/detail")
        }
        // D-223 fix wave (I2): an expense opened from an Activity returns to it; its edit returns to the expense.
        ActivityExpenseRoute.parse(route)?.let { return it.activity }
        if (route.startsWith("expense-edit/") && "/from/" in route) return route.substringAfter("/from/")
        return null
    }
}

/**
 * §3.3's navigation, the post-gate shell: one bottom bar, four tabs, no
 * launch argument required for anything a user touches. Mirrors
 * SkiptiApp.swift's settled shape (root gate → the screens under one
 * navigation chrome, screen titles carried by each screen). BACK BEHAVIOUR,
 * stated: tabs are ROOTS, not a back stack; system back from any tab
 * exits the app (the standard NavigationBar pattern); a save on the Add
 * tab switches to Balance so the recorded result is visible before exit.
 * The headless verification args are untouched and keep bypassing all of
 * this: they are test entry points, not user flows.
 */
@Composable
private fun HomeShell(
    vaultPath: String,
    startOn: HomeTab = HomeTab.BALANCE,
    pushed: PushRoute.Target? = null,
    onPushHandled: () -> Unit = {},
    invitations: Boolean = false,
    onInvitationsHandled: () -> Unit = {},
    phones: Boolean = false,
    onPhonesHandled: () -> Unit = {},
    pushedGroup: String? = null,
    onGroupHandled: () -> Unit = {},
) {
    val dark = isSystemInDarkTheme()
    // D-179: saved by name, so a reordered bar never restores the wrong tab.
    var selectedName by rememberSaveable { mutableStateOf(startOn.name) }
    val selectedTab = HomeTab.valueOf(selectedName)
    /** D-180: the group whose page is open over Balance or Groups. */
    var openGroup by rememberSaveable { mutableStateOf<String?>(null) }
    /** D-249: Your spending's page, inside the tab it was opened from: [SpendingOpen.CARD] over Balance (Home's card),
     *  [SpendingOpen.HEADER] over Expenses (the Just me header, decision 206); null: not open. */
    var spendingOpen by rememberSaveable { mutableStateOf<String?>(null) }
    /** D-249 fix wave (I2): the page's place, above the route switch (an expense or Export opened from the page replaces
     *  it on screen, and Back finds it as it was); a fresh one each time the page opens. */
    val spendingKeep = remember(vaultPath, spendingOpen) { SpendingKeep() }
    /** D-221 (Design 12; fix wave S8): each group's page memory between loads, kept per group for as long as the app
     *  runs (a pushed page, another tab or another group replaces the page, and this keeps its last settled state and a
     *  waiting celebration), so a group settled while its page was off screen plays when the page is next on screen.
     *  Memory only: never stored, never after a relaunch. */
    val celebrations = remember { HashMap<String, CelebrationState>() }
    /** D-250 fix wave (M3): each group's page's last read, kept the same way, so a page opened again slides in drawn. */
    val groupPages = remember { HashMap<String, GroupPageKeep>() }
    /** D-223 fix wave (F6): Home's place (its last read and its scroll offset) across a pushed route, a group's page
     *  or another tab, as iOS's stack keeps it. The offset is saved like `route`; the read is memory only. */
    val homeScroll = rememberSaveable(saver = ScrollState.Saver) { ScrollState(0) }
    val homeKeep = remember(vaultPath) { HomeKeep(homeScroll) }
    /** D-225 T7 (A5, D-223's fix re-review m4): Expenses', Groups' and Settle up's places, kept the same way (each
     *  offset saved like Home's, each last read in memory), so each tab comes back where it was after a pushed route,
     *  a group's page or another tab, as iOS's per-tab stacks do. */
    val expensesScroll = rememberSaveable(saver = ScrollState.Saver) { ScrollState(0) }
    val expensesKeep = remember(vaultPath) { TabKeep(expensesScroll) }
    val groupsScroll = rememberSaveable(saver = ScrollState.Saver) { ScrollState(0) }
    val groupsKeep = remember(vaultPath) { TabKeep(groupsScroll) }
    val settleScroll = rememberSaveable(saver = ScrollState.Saver) { ScrollState(0) }
    val settleKeep = remember(vaultPath) { TabKeep(settleScroll) }
    /** D-225 fix wave (F6, the look at font scale 2.0): You's offset, kept while its pages are pushed and saved like
     *  the tabs'; each of its pages takes a scroll state of its own (keyed by its route), so Security never opens at
     *  You's offset. You and its pages share one call site, which used to share one `rememberScrollState`.
     *  D-225 follow-up (M1, the re-review): kept per ENTRY: You opened afresh from a tab root ([openYou]) is a new entry
     *  and starts at its top, as iOS pushes a new You; coming back from You's own pages keeps the entry and its offset. */
    var youEntry by rememberSaveable { mutableIntStateOf(0) }
    val youScroll = key(youEntry) { rememberSaveable(saver = ScrollState.Saver) { ScrollState(0) } }
    /** D-223 follow-up (R3): the tab bar's measured height, kept here above the route switch (a route replaces the tabs,
     *  so a `remember` beside the bar restarted at 0): Home's first frame back keeps the bar's height clear at its end,
     *  and a kept offset near the end is not clamped by a 0 inset. */
    var tabBarPx by rememberSaveable { mutableIntStateOf(0) }

    // D-126: the account surface is a ROUTE OVER the tabs, not a fifth
    // tab: the founder's call, and it keeps the bottom bar the four
    // ledger actions it was designed as. Null means "the tabs"; each
    // route is a full screen carrying its own Back, so the tab bar's
    // roots-not-a-back-stack rule above is untouched.
    var route by rememberSaveable { mutableStateOf<String?>(null) }
    /** D-225 follow-up (M1): You from a tab root (the account glyph): a new entry, at its top. */
    fun openYou() {
        youEntry++
        route = "account"
    }
    /** D-224 follow-up (A-M3): the screen a tapped push opened that iOS presents as a sheet ([PushCover]), until it
     *  closes; it covers the shell's toast meanwhile. */
    var pushCover by rememberSaveable { mutableStateOf<String?>(null) }
    /** D-172: set by a completed sign-in; its confirmation shows until Done.
     *  C2: saved like `route`, so a recreation keeps the required step. */
    var signedInAs by rememberSaveable(stateSaver = SignInMethod.saver) { mutableStateOf<SignInMethod?>(null) }
    /** C2: after a sign-in, whether the account still needs a username (null
     *  while that is being read; a recreation reads again only then). */
    var usernameNeeded by rememberSaveable { mutableStateOf<Boolean?>(null) }
    /** C2: the account's username as Home, You and Find me last read it,
     *  kept across tabs and routes (iOS keeps each screen's state); a failed
     *  read never forgets it (UsernameState.merge). */
    var usernameKnown by remember { mutableStateOf<UsernameState?>(null) }
    val shellScope = rememberCoroutineScope()
    val context = LocalContext.current
    // D-187: group pictures, read again on every sync.
    LaunchedEffect(vaultPath, SyncStatus.revision) {
        withContext(Dispatchers.IO) {
            GroupPictures.load(LedgerStore(vaultPath))
        }
    }
    // D-248 (U30.4): a delete's Undo is the done toast wherever it happened (`ExpenseUndo`); the shell keeps nothing.
    // Bumped after an import so every tab reads the ledger again.
    var generation by remember { mutableIntStateOf(0) }
    // D-205: per tab, bumped by a re-tap at the tab's first screen.
    var scrollTop by remember { mutableStateOf(mapOf<HomeTab, Int>()) }

    // D-143: system Back steps back through a route instead of leaving the
    // app. Before this, Back on any route exited (D-136's residue), and on
    // 2026-09-19 the next taps of an emulator check landed on the launcher.
    // The tabs stay roots: Back from a tab still exits.
    // D-196: a tapped push opens its expense over the tabs (after the lock).
    LaunchedEffect(pushed) {
        if (pushed != null) {
            route = "pushed/${pushed.groupId}/${pushed.expense}"
            onPushHandled()
        }
    }
    // D-197: a tapped invite push opens the invitations.
    LaunchedEffect(invitations) {
        if (invitations) {
            route = "invitations"
            onInvitationsHandled()
        }
    }
    // D-198: a tapped "Was this you?" push opens Your phones.
    LaunchedEffect(phones) {
        if (phones) {
            route = "devices"
            pushCover = PushCover.PHONES
            onPhonesHandled()
        }
    }
    // D-210: a tapped group push opens that group's page (over Balance).
    LaunchedEffect(pushedGroup) {
        if (pushedGroup != null) {
            route = null
            selectedName = HomeTab.BALANCE.name
            openGroup = pushedGroup
            pushCover = PushCover.group(pushedGroup)
            onGroupHandled()
        }
    }
    // D-197: an invitation being joined (its link, as if opened).
    var joiningInvite by remember { mutableStateOf<IncomingInvites.Item?>(null) }
    // D-145: your picture (or initials) for the account glyph once you have one. D-217 final fix: read here, once (the
    // shell outlives a tab switch and the tab roots' remount), not by each root's glyph, which started plain on every
    // switch and swapped when its own vault read returned.
    var glyphAvatar by remember { mutableStateOf<Avatar>(Avatar.None) }
    var glyphName by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(vaultPath, ProfileState.revision) {
        withContext(Dispatchers.IO) {
            val store = LedgerStore(vaultPath)
            glyphName = runCatching { store.loadSelf()?.name }.getOrNull()
            glyphAvatar = runCatching { store.loadAvatar() }.getOrDefault(Avatar.None)
        }
    }
    /** D-250 fix wave (M3): opens a group's page over the tab. A page read before slides in at once, drawn from that
     *  read; a first visit is read first, the slide waiting at most [GroupPageKeep.HOLD_MS] for it (past that it slides in
     *  and its cards fade in as they arrive). */
    fun openGroupPage(id: String) {
        val keep = groupPages.getOrPut(id) { GroupPageKeep() }
        if (!GroupPageKeep.holds(kept = keep.last != null)) {
            openGroup = id
            return
        }
        shellScope.launch {
            val read = async(Dispatchers.IO) { runCatching { GroupPageKeep.read(vaultPath, id) }.getOrNull() }
            withTimeoutOrNull(GroupPageKeep.HOLD_MS) { read.await() }?.let { if (keep.last == null) keep.last = it }
            openGroup = id
        }
    }
    // D-245 (U9): the welcome's "Start a group" opens New group over Balance (iOS's `.skiptiStartGroup`).
    LaunchedEffect(StartGroupRequest.pending) {
        if (StartGroupRequest.pending) {
            StartGroupRequest.pending = false
            openGroup = null
            selectedName = HomeTab.BALANCE.name
            route = "new-group"
        }
    }
    BackHandler(enabled = route != null) { route = HomeRoutes.parent(route) }
    BackHandler(enabled = route == null && openGroup != null) { openGroup = null }
    BackHandler(enabled = route == null && openGroup == null && SpendingOpen.covers(spendingOpen, selectedTab)) { spendingOpen = null }
    // C2: a sign-in's confirmation and step belong to the sign-in route;
    // leaving it any way (Back, a push, an invite) forgets both.
    LaunchedEffect(route) {
        if (route?.startsWith("signin") != true) {
            signedInAs = null
            usernameNeeded = null
        }
    }

    // D-224 fix wave (F6): a route iOS presents as a sheet over the shell hides the shell's toast while it shows.
    if (ToastCover.covers(route)) CoversShellToast()
    // D-224 follow-up (A-M3): and so does a screen a push opened that iOS presents as a sheet, while it shows.
    val pushCovered = ToastCover.pushed(pushCover, route, openGroup, selectedTab == HomeTab.BALANCE)
    if (pushCovered) CoversShellToast()
    LaunchedEffect(pushCovered) { if (!pushCovered) pushCover = null }
    // D-250 (decision 194): a route's page moves as iOS's do: a push slides in from the trailing edge over the page
    // (which shifts a third, the tab bar sliding away with it), Back reverses it, a route iOS presents as a sheet rises
    // from the bottom and falls back ([PageMotion], [pageTransform]); a cut when motion is reduced. Each page reads the
    // route it was composed for (`shown`), so the page leaving keeps drawing itself, and its state, while it slides out.
    val reduce = rememberMotionState().reduce
    // D-250 fix wave (I4): the move of this route change, worked out as it is composed (state, not a note from the
    // transition), for the bar.
    val routeMemo = remember { RouteMemo(route) }
    val move = remember(route) { PageMotion.move(routeMemo.route, route).also { routeMemo.route = route } }
    // D-250 fix wave (I4): the shell's backdrop and tab bar, above the route switch: the bar is drawn over the pages
    // (dropping away with a push, fading, as iOS's), and the tab content under a sheet draws its own copy of it.
    val shellBackdrop = rememberBackdrop()
    val shellView = LocalView.current
    val tabBar: @Composable () -> Unit = {
        // D-179: SKIPTI's own bar with the raised + (the founder's canvas).
        SkiptiTabBar(
            dark = dark,
            selected = selectedTab,
            onSelect = { tapped ->
                // D-205 (TabTap): a group's page and (D-249) Your spending are the pages under the bar.
                val covered = if (openGroup != null || SpendingOpen.covers(spendingOpen, selectedTab)) setOf(selectedTab) else emptySet()
                when (val tap = TabTap.decide(selectedTab, tapped, covered)) {
                    is TabTap.SwitchTo -> {
                        // D-224 (Design 3): a tap that switches tabs, and only that, plays the light haptic.
                        Haptics.play(shellView, HapticKind.LIGHT)
                        selectedName = tap.tab.name
                        openGroup = null
                        spendingOpen = null
                    }
                    is TabTap.PopToRoot -> {
                        openGroup = null
                        spendingOpen = null
                    }
                    is TabTap.ScrollToTop -> scrollTop = scrollTop + (tap.tab to ((scrollTop[tap.tab] ?: 0) + 1))
                }
            },
            // D-249: the + over Your spending opens Add expense on Just me.
            onAdd = { route = if (SpendingPresence.shown) "add/justme" else "add" },
        )
    }
    Box(Modifier.fillMaxSize()) {
    AnimatedContent(
        targetState = route,
        modifier = Modifier.fillMaxSize(),
        transitionSpec = { pageTransform(PageMotion.move(initialState, targetState), reduce) },
        label = "page",
    ) { shown ->
    // D-250 fix wave (I1, I3): each page its own layer; the page leaving drawn from one picture of it and taking no touch.
    SlidingPage page@{
    val routeSlide = LocalPageSlide.current
    when (shown) {
        // D-206: You ("account") and its four groups (account/…).
        "account", "account/profile", "account/appearance", "account/security", "account/data" -> {
            val here = shown
            // D-224 (Design 6): You's "Start a group" returns here, so a first group made from it plays its overlay
            // over You, dimmed under it (iOS's You sits in a tab's stack, under the shell's overlay). Never a touch.
            val youMotion = rememberMotionState()
            val youPlay = rememberDoneOverlay(youMotion)
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().doneDim(youPlay, dark)) {
                    AccountHubScreen(
                        vaultPath = vaultPath,
                        section = SettingsMap.section(here),
                        onOpenSection = { route = it.route },
                        onOpenSignIn = { route = "signin" },
                        onBack = { route = HomeRoutes.parent(here) },
                        onOpenExport = { route = "export" },
                        onOpenImport = { route = "import/account" },
                        onOpenProfile = { route = "profile" },
                        onOpenAbout = { route = "about" },
                        onOpenPhones = { route = "devices" },
                        onOpenDelete = { route = "delete-account" },
                        onOpenErase = { route = "erase" },
                        usernameState = usernameKnown,
                        onUsernameState = { usernameKnown = it },
                        onPickUsername = { route = "username/you" },
                        onFindMePick = { route = "username/find-me" },
                        onChangeUsername = { route = "username/change/$it" },
                        onOpenCreateGroup = { route = "new-group/you" },
                        scroll = if (here == "account") youScroll else key(here) { androidx.compose.foundation.rememberScrollState() },
                    )
                }
                DoneOverlay(youPlay, dark)
            }
            return@page
        }
        // C2: the username step opened from a card or Find me: it can be
        // left (Cancel, Back), and returns where it was opened.
        "username", "username/you", "username/find-me" -> {
            val back = HomeRoutes.parent(shown)
            UsernameStep(vaultPath, dark, onCancel = { route = back }) { route = back }
            return@page
        }
        // D-198: SPEC §3.5 and §4.4.
        "devices" -> {
            PhonesScreen(vaultPath, onBack = { route = HomeRoutes.parent("devices") })
            return@page
        }
        "delete-account" -> {
            DeleteAccountScreen(
                vaultPath,
                onExport = { route = "delete-export" },
                onDone = { route = HomeRoutes.parent("delete-account") },
                onBack = { route = HomeRoutes.parent("delete-account") },
            )
            return@page
        }
        // D-225 (Design 4): Security → Erase this phone's ledger.
        "erase" -> {
            EraseScreen(vaultPath, onBack = { route = HomeRoutes.parent("erase") })
            return@page
        }
        "delete-export" -> {
            ExportScreen(LedgerStore(vaultPath), onBack = { route = "delete-account" })
            return@page
        }
        "other-account" -> {
            OtherAccountScreen(vaultPath, onBack = { route = "account" }, onOpenExport = { route = "export" })
            return@page
        }
        "about" -> {
            AboutScreen(vaultPath, onBack = { route = "account" }, onOpenLicences = { route = "licences" })
            return@page
        }
        "licences" -> {
            LicencesScreen(onBack = { route = "about" }, onOpen = { route = "licence/$it" })
            return@page
        }
        "profile" -> {
            // D-145: the on-device profile.
            ProfileScreen(vaultPath = vaultPath, onBack = { route = "account" })
            return@page
        }
        "export" -> {
            // D-144: GUARANTEES #4's one tap, from Settings.
            ExportScreen(LedgerStore(vaultPath), onBack = { route = HomeRoutes.parent("export") })
            return@page
        }
        // D-172: a success shows its confirmation first (the founder,
        // 2026-09-26), then Done returns to Settings.
        "signin", "signin/join" -> {
            val back = if (shown == "signin/join") "join" else HomeRoutes.parent("signin")
            // C2: a signed-out account (signed out elsewhere, or by a
            // deletion) never sees the step: back to the form, with nothing
            // of that sign-in kept. Reading signOuts here recomposes on one.
            val signOuts = RemoteSignOut.signOuts
            val stillIn = Account.isSignedIn()
            LaunchedEffect(signOuts, signedInAs, stillIn) {
                if (signedInAs != null && !Account.isSignedIn()) {
                    signedInAs = null
                    usernameNeeded = null
                }
            }
            val step = SignInStep.decide(signedIn = signedInAs != null && stillIn, needed = usernameNeeded)
            signedInAs?.takeIf { step != SignInStep.FORM }?.let { method ->
                fun finish() {
                    signedInAs = null
                    usernameNeeded = null
                    route = back
                }
                LaunchedEffect(method) {
                    if (usernameNeeded != null) return@LaunchedEffect
                    // C2: the wait is bounded: after 10 seconds without an
                    // answer (a slow or dead network) the confirmation shows
                    // and the card asks later. Whichever finishes first
                    // decides; the other does nothing. Two coroutines, not a
                    // timeout around the work: a Firestore read that ignores
                    // cancellation would keep the spinner.
                    val timer = launch {
                        delay(10_000)
                        if (usernameNeeded == null) usernameNeeded = false
                    }
                    launch {
                        // An account that already has a username (a second
                        // phone) skips the step. Right after sign-in the
                        // profile may not exist yet (registration runs in the
                        // background): register it here, then read again.
                        // Still unreadable: no step; the card asks later.
                        var state = People.accountUsername()
                        if (state == null) Account.uid()?.let { uid ->
                            val version = AboutInfo.current(context).first
                            withContext(Dispatchers.IO) { CloudProfile.register(uid, LedgerStore(vaultPath), version) }
                            state = People.accountUsername()
                        }
                        timer.cancel()
                        if (usernameNeeded == null && Account.isSignedIn()) usernameNeeded = UsernameState.needsStep(state)
                    }
                }
                // C2: no way out of the step or the wait (iOS hides Back
                // there); on the confirmation Back is Done.
                BackHandler { if (usernameNeeded == false) finish() }
                when (step) {
                    // C2: required, no Cancel; then the usual confirmation.
                    // Once a save has failed, "Later" goes on to it
                    // (GUARANTEES #2).
                    SignInStep.STEP -> UsernameStep(vaultPath, dark, onLater = { usernameNeeded = false }) {
                        usernameNeeded = false
                    }
                    SignInStep.CONFIRMATION -> SignedInConfirmation(method) { finish() }
                    SignInStep.FORM, SignInStep.WAITING -> Box(
                        Modifier
                            .fillMaxSize()
                            .auroraBackground(dark),
                        contentAlignment = androidx.compose.ui.Alignment.Center,
                    ) { androidx.compose.material3.CircularProgressIndicator(color = Tok.accent(dark)) }
                }
                return@page
            }
            SignInScreen(vaultPath, onBack = { route = HomeRoutes.parent(shown) }) { outcome, method ->
                // D-191: a Firebase sign-in; D-168: another account's ledger
                // on this phone is refused; a failure stays on the page.
                when (outcome) {
                    Account.Outcome.SignedIn -> {
                        // C2: a new sign-in reads its account afresh.
                        usernameKnown = null
                        usernameNeeded = null
                        signedInAs = method
                    }
                    Account.Outcome.OtherAccount -> route = "other-account"
                    is Account.Outcome.Failed -> Unit
                }
            }
            return@page
        }
        // D-172/D-195: Join a group from Balance's empty state or the group
        // list, for everyone (the founder, 2026-09-26). Without an account it
        // says why joining needs one and offers sign-in, which returns here.
        "join" -> {
            JoinPasteScreen(
                vaultPath,
                onJoined = {
                    route = null
                    selectedName = HomeTab.BALANCE.name
                    generation++
                },
                onBack = { route = null },
                onOpenSignIn = { route = "signin/join" },
            )
            return@page
        }
        // D-166: import, from Balance's empty state or Settings. Done lands on
        // Balance with every tab reading the ledger again.
        "import/balance", "import/account" -> {
            ImportFlowScreen(
                vaultPath = vaultPath,
                onBack = { route = HomeRoutes.parent(shown) },
                onDone = {
                    route = null
                    selectedName = HomeTab.BALANCE.name
                    generation++
                },
            )
            return@page
        }
        // D-179: the Groups tab replaced the group list route; its People
        // page is a route over the tabs.
        "add", "add/justme" -> {
            // D-179: the raised + opens a fresh form every time; a save lands
            // on Balance, which reads the new expense. D-249: the + over Your spending opens on Just me.
            AddExpenseScreen(
                vaultPath = vaultPath,
                onSaved = {
                    route = null
                    selectedName = HomeTab.BALANCE.name
                    SyncStatus.changed()
                },
                onBack = { route = null },
                startOnJustMe = shown == "add/justme",
            )
            return@page
        }
        // D-221: "new-group/you" is You's next card's "Start a group": back and a new group both return to You.
        "new-group", "new-group/you" -> {
            val fromYou = shown == "new-group/you"
            CreateGroupScreen(
                vaultPath = vaultPath,
                // D-224 follow-up (A-R3): Home's new page is read before the moment and the return to Balance.
                homeKeep = if (fromYou) null else homeKeep,
                onCreated = { created ->
                    if (fromYou) {
                        route = "account"
                    } else {
                        // D-248 (U30.6, decision 185): the new group's page opens over Home (Back lands on Home, which
                        // lists it), from Home's New group, Groups' and the welcome's Start a group alike; the first
                        // group's big moment still plays over it (the shell's overlay). The twin of iOS's `GroupOpening`.
                        route = null
                        selectedName = HomeTab.BALANCE.name
                        openGroup = created
                    }
                    SyncStatus.changed()
                },
                onBack = { route = HomeRoutes.parent(shown) },
            )
            return@page
        }
        "your-name" -> {
            YourNameScreen(
                vaultPath = vaultPath,
                onSaved = { route = null },
                onBack = { route = null },
            )
            return@page
        }
        else -> {
            // D-143: one expense, and the form that edits it.
            val r = shown
            if (r != null && r.startsWith("expense/")) {
                val id = r.removePrefix("expense/")
                ExpenseDetailScreen(
                    vaultPath = vaultPath,
                    expenseId = id,
                    onBack = { route = null },
                    onEdit = { route = "expense-edit/$id/detail" },
                    onDeleted = { route = null },
                )
                return@page
            }
            // D-223 fix wave (I2): an expense opened from an Activity screen, going back to that Activity.
            val opened = ActivityExpenseRoute.parse(r)
            if (r != null && opened != null) {
                ExpenseDetailScreen(
                    vaultPath = vaultPath,
                    expenseId = opened.expense,
                    onBack = { route = opened.activity },
                    onEdit = { route = "expense-edit/${opened.expense}/from/$r" },
                    onDeleted = { route = opened.activity },
                )
                return@page
            }
            // D-197: an invitation sent to this account, joined through its link.
            if (r == "join-invite") {
                val item = joiningInvite
                if (item == null) {
                    LaunchedEffect(Unit) { route = null }
                } else {
                    JoinScreen(vaultPath, item.link, onJoined = {
                        IncomingInvites.dismiss(item)
                        joiningInvite = null
                        route = null
                        selectedName = HomeTab.BALANCE.name
                        generation++
                    }, onBack = { route = null }, asSheet = true)
                }
                return@page
            }
            if (r == "invitations") {
                InvitationsScreen(vaultPath, dark, onJoin = {
                    joiningInvite = it
                    route = "join-invite"
                }, onBack = { route = null })
                return@page
            }
            // D-223 A1: the whole ledger's Activity, from Home's Lately (orphaned since D-196 took the bell away).
            if (r == "activity") {
                ActivityScreen(
                    vaultPath, dark, group = null,
                    onOpenExpense = { route = ActivityExpenseRoute.of(r, it) }, onBack = { route = null },
                )
                return@page
            }
            // D-196: a group's Recent activity; an expense opens over it.
            if (r != null && r.startsWith("activity/")) {
                ActivityScreen(
                    vaultPath, dark, group = r.removePrefix("activity/"),
                    onOpenExpense = { route = ActivityExpenseRoute.of(r, it) }, onBack = { route = null },
                )
                return@page
            }
            if (r != null && r.startsWith("pushed/")) {
                val (groupId, expense) = r.removePrefix("pushed/").split("/", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
                PushedExpenseScreen(
                    vaultPath = vaultPath,
                    target = PushRoute.Target(groupId, expense),
                    onBack = { route = null },
                    onEdit = { route = "expense-edit/$expense/detail" },
                    onDeleted = { route = null },
                )
                return@page
            }
            // C2: Find me's one change, for the handle it was opened with.
            if (r != null && r.startsWith("username/change/")) {
                val back = HomeRoutes.parent(r)
                UsernameChangeScreen(
                    current = r.removePrefix("username/change/"),
                    vaultPath = vaultPath,
                    dark = dark,
                    onSaved = { saved ->
                        usernameKnown = UsernameState.Final(saved)
                        route = back
                    },
                    // The one change was spent elsewhere: Find me reads the
                    // account again and shows the real handle.
                    onUsed = { route = back },
                    onCancel = { route = back },
                )
                return@page
            }
            if (r != null && r.startsWith("people/")) {
                PeopleScreen(
                    vaultPath, r.removePrefix("people/"), onBack = { route = null },
                    // D-210: a person's Remove, or a request's Review, is its own screen.
                    onRemove = { route = it.route() },
                    // A request card's Settle up opens that tab.
                    onSettleUp = {
                        route = null
                        openGroup = null
                        selectedName = HomeTab.SETTLE.name
                    },
                )
                return@page
            }
            // D-210: taking a person out (settle first, or redistribute); Back
            // and Done return to People, or to the group's page.
            val removal = r?.let { RemovalRoute.parse(it) }
            if (removal != null) {
                val back = HomeRoutes.parent(r)
                RemovalFlowScreen(vaultPath, removal, dark, onBack = { route = back }, onFinished = { route = back })
                return@page
            }
            // D-210: a group's name, picture and currency, and deleting it.
            if (r != null && r.startsWith("group-settings/")) {
                GroupSettingsScreen(
                    vaultPath, r.removePrefix("group-settings/"), dark,
                    onBack = { route = HomeRoutes.parent(r) },
                    // A group deleted (here or for everyone): its page closes too (U27).
                    onGone = {
                        route = null
                        openGroup = null
                    },
                )
                return@page
            }
            // D-249: Your spending's "Export Just me" (the personal scope only); back to the page.
            if (r == "export-justme") {
                ExportScreen(LedgerStore(vaultPath), onBack = { route = null }, justMe = true)
                return@page
            }
            // D-210: a read-only copy exports its own group.
            if (r != null && r.startsWith("export-group/")) {
                ExportScreen(LedgerStore(vaultPath), onBack = { route = HomeRoutes.parent(r) }, group = r.removePrefix("export-group/"))
                return@page
            }
            if (r != null && r.startsWith("licence/")) {
                LicenceDetailScreen(
                    index = r.removePrefix("licence/").toIntOrNull() ?: -1,
                    onBack = { route = "licences" },
                )
                return@page
            }
            if (r != null && r.startsWith("expense-edit/")) {
                val back = HomeRoutes.parent(r)
                AddExpenseScreen(
                    vaultPath = vaultPath,
                    onSaved = { route = back },
                    editingId = r.removePrefix("expense-edit/").substringBefore("/"),
                    onBack = { route = back },
                )
                return@page
            }
        }
    }

    // D-220 (L-D217-3): the tab bar overlays the page (iOS's sits over its stacks), so the page runs under its glass;
    // one backdrop for the shell: the tab root on screen (or a group's page) records into it, the tab bar and the
    // page's own bar draw it blurred. The page keeps the bar's measured height clear at its end.
    val tabBarInset = with(LocalDensity.current) { tabBarPx.toDp() }
    // D-224 (Design 3, 6): the root's view for the tab switch's light haptic; the first group's big moment over the tab
    // content, which dims under it, ground and all (fix wave F2: the dim's scrim covers the ground drawn here too), while
    // the bar stays bright.
    val doneMotion = rememberMotionState()
    val donePlay = rememberDoneOverlay(doneMotion)
    Box(
        Modifier
            .fillMaxSize()
            .auroraBackground(dark)
    ) {
      CompositionLocalProvider(LocalShellBackdrop provides shellBackdrop, LocalShellBarInset provides tabBarInset) {
        // D-140: the account glyph on EVERY tab (the founder, 2026-09-19), the
        // twin of iOS's toolbar item; D-217: it is each tab root's trailing bar
        // item (`accountGlyph`), no longer a row of its own above the content.
        // GUARANTEES #5: a glyph, not a prompt; a user with no account is
        // never told to get one.
        // D-178: pull to sync (the founder, 2026-09-29), on every tab. A
        // sync never resets the Add form: it reads nothing the form holds.
        val pullScope = rememberCoroutineScope()
        androidx.compose.material3.pulltorefresh.PullToRefreshBox(
            isRefreshing = SyncStatus.syncing,
            onRefresh = { pullScope.launch { SyncStatus.syncNow(vaultPath, pulled = true) } },
            modifier = Modifier.fillMaxSize().doneDim(donePlay, dark),
        ) {
            // D-205: the shown tab's scroll-to-top signal.
            CompositionLocalProvider(
                LocalScrollTop provides (scrollTop[selectedTab] ?: 0),
                LocalAccountFace provides AccountFace(glyphAvatar, glyphName),
            ) {
                key(generation) {
                    // D-250: a group's page and Your spending push over their tab's root (the bar stays, as on iOS) and
                    // Back reverses it; a tab switch stays a cut. Each page reads the place it was composed for (`page`).
                    val tabTarget = TabPage(selectedTab, openGroup, spendingOpen)
                    AnimatedContent(
                        targetState = tabTarget,
                        modifier = Modifier.fillMaxSize(),
                        transitionSpec = { pageTransform(PageMotion.tabMove(initialState.tab == targetState.tab, initialState.over, targetState.over), reduce) },
                        // D-250 fix wave (M1): keyed by what the tab shows, so a field it does not show never re-composes it.
                        contentKey = { it.key },
                        label = "tabPage",
                    ) { page ->
                    SlidingPage {
                    // D-250 (the look): two pages record into one backdrop while one slides over the other, and a layer
                    // drawn by reference shows its LAST recording on both. Only the page the tab is going to records into
                    // the shell's (the tab bar blurs it); the page leaving draws through its own.
                    val leavingBackdrop = rememberBackdrop()
                    val slide = LocalPageSlide.current
                    val current = slide != PageSlide.LEAVING
                    // A tab's pages sit on the shell's ground (transparent); while one slides over another only the page on
                    // top takes the ground itself (fix wave I1: one opaque ground a slide), so the page under it never
                    // shows through it.
                    val ownGround = PageMotion.ownGround(slide, tabTarget.over)
                    CompositionLocalProvider(LocalShellBackdrop provides if (current) shellBackdrop else leavingBackdrop) {
                    Box(if (ownGround) Modifier.fillMaxSize().auroraBackground(dark) else Modifier.fillMaxSize()) {
                    when (page.tab) {
                        // D-180: a group's page opens inside Balance or Groups, so
                        // the bar stays under it (iOS and the canvas).
                        HomeTab.BALANCE, HomeTab.GROUPS -> page.group?.let { id ->
                            GroupPage(
                                vaultPath = vaultPath,
                                groupId = id,
                                dark = dark,
                                onBack = { openGroup = null },
                                onPeople = { route = "people/$it" },
                                // D-180: an expense opens from its group's page, to
                                // edit or delete (the founder, 2026-09-29).
                                onOpenExpense = { route = "expense/$it" },
                                onActivity = { route = "activity/$it" },
                                // D-210: settings, a request's review, Settle up, a copy's export.
                                onSettings = { route = "group-settings/$it" },
                                onReview = { g, m -> route = RemovalRoute(g, m, review = true, fromPeople = false).route() },
                                onSettleUp = {
                                    openGroup = null
                                    selectedName = HomeTab.SETTLE.name
                                },
                                onExport = { route = "export-group/$it" },
                                celebration = celebrations.getOrPut(id) { CelebrationState() },
                                keep = groupPages.getOrPut(id) { GroupPageKeep() },
                            )
                        } ?: if (page.tab == HomeTab.BALANCE && page.spending == SpendingOpen.CARD) {
                            // D-249: Your spending, opened from Home's card, under the tab bar as a group's page.
                            SpendingScreen(
                                vaultPath = vaultPath, dark = dark, fromJustMeHeader = false,
                                onBack = { spendingOpen = null },
                                onOpenExpense = { route = "expense/$it" },
                                onExport = { route = "export-justme" },
                                keep = spendingKeep,
                            )
                        } else if (page.tab == HomeTab.BALANCE) {
                            OverviewScreen(
                                vaultPath = vaultPath,
                                dark = dark,
                                onSettle = { selectedName = HomeTab.SETTLE.name },
                                onOpenGroup = ::openGroupPage,
                                onCreateGroup = { route = "new-group" },
                                onOpenAccount = ::openYou,
                                onAddYourName = { route = "your-name" },
                                onImport = { route = "import/balance" },
                                onJoin = { route = "join" },
                                username = usernameKnown,
                                onUsername = { usernameKnown = it },
                                onPickUsername = { route = "username" },
                                // D-223: a Lately row opens its expense; "See all" the whole ledger's Activity (A1).
                                onOpenExpense = { route = "expense/$it" },
                                onSeeAllActivity = { route = "activity" },
                                keep = homeKeep,
                                onOpenSpending = { spendingOpen = SpendingOpen.CARD },
                            )
                        } else {
                            GroupsTab(
                                vaultPath = vaultPath,
                                dark = dark,
                                onOpen = {
                                    selectedName = HomeTab.BALANCE.name
                                    SyncStatus.changed()
                                },
                                onOpenGroup = ::openGroupPage,
                                onOpenAccount = ::openYou,
                                onCreate = { route = "new-group" },
                                onJoin = { route = "join" },
                                onPeople = { route = "people/$it" },
                                onJoinInvite = {
                                    joiningInvite = it
                                    route = "join-invite"
                                },
                                keep = groupsKeep,
                            )
                        }
                        HomeTab.EXPENSES -> if (page.spending == SpendingOpen.HEADER) {
                            // D-249 (decisions 190, 206): from the Just me header, always in Just me.
                            SpendingScreen(
                                vaultPath = vaultPath, dark = dark, fromJustMeHeader = true,
                                onBack = { spendingOpen = null },
                                onOpenExpense = { route = "expense/$it" },
                                onExport = { route = "export-justme" },
                                keep = spendingKeep,
                            )
                        } else {
                            ExpenseListScreen(
                                vaultPath = vaultPath,
                                onOpen = { route = "expense/$it" },
                                onEdit = { route = "expense-edit/$it/list" },
                                onOpenAccount = ::openYou,
                                keep = expensesKeep,
                                onOpenSpending = { spendingOpen = SpendingOpen.HEADER },
                            )
                        }
                        // D-182: every group at once (the founder's canvas).
                        HomeTab.SETTLE ->
                            SettleAllScreen(vaultPath = vaultPath, dark = dark, onOpenAccount = ::openYou, keep = settleKeep)
                    }
                    }
                    }
                    }
                    }
                }
            }
        }
        // D-224 (Design 6, 11): the first group's check and words, over the content and under the bar; no touch.
        DoneOverlay(donePlay, dark)
        // D-250 fix wave (I4): under a sheet (rising, up, or falling back) the shell draws the bar itself, so the sheet
        // covers it as iOS's does; otherwise the shell's bar is the one drawn over the pages, below.
        if (PageMotion.barUnderSheet(route, move, entering = routeSlide == PageSlide.ENTERING)) {
            Box(Modifier.align(Alignment.BottomCenter)) { tabBar() }
        }
      }
    }
    }
    }
    // D-250 fix wave (I4): the tab bar, over the pages and outside them (it never slides sideways with the tabs): it
    // drops away with a fade when a page is pushed and comes back up with Back, over iOS's 0.2 s ([PageMotion.barShows],
    // [PageMotion.barMove]); a sheet swaps it for the shell's own copy under the sheet.
    val barMove = PageMotion.barMove(move)
    AnimatedVisibility(
        visible = PageMotion.barShows(route),
        modifier = Modifier.align(Alignment.BottomCenter),
        enter = if (reduce) EnterTransition.None else when (barMove) {
            PageMotion.BarMove.DROP -> slideInVertically(ChangeMotion.tween(ChangeMotion.Kind.CHANGE)) { it } + fadeIn(ChangeMotion.tween(ChangeMotion.Kind.CHANGE))
            PageMotion.BarMove.AFTER_SHEET -> fadeIn(androidx.compose.animation.core.snap(delayMillis = ChangeMotion.millis(ChangeMotion.Kind.PAGE)))
            else -> EnterTransition.None
        },
        exit = if (reduce) ExitTransition.None else when (barMove) {
            PageMotion.BarMove.DROP -> slideOutVertically(ChangeMotion.tween(ChangeMotion.Kind.CHANGE)) { it } + fadeOut(ChangeMotion.tween(ChangeMotion.Kind.CHANGE))
            else -> ExitTransition.None
        },
    ) {
        Box(Modifier.onSizeChanged { tabBarPx = it.height }) {
            androidx.compose.runtime.DisposableEffect(Unit) {
                ShellBarShown.value = true
                onDispose { ShellBarShown.value = false }
            }
            CompositionLocalProvider(LocalShellBackdrop provides shellBackdrop) { tabBar() }
        }
    }
    }
}

/**
 * D-217: the account glyph as a tab root's trailing bar item (a `TabRootScaffold` slot), or none when the root was
 * not given a way to open the account. [AccountGlyph] is the item.
 */
internal fun accountGlyph(dark: Boolean, onOpenAccount: (() -> Unit)?): (@Composable () -> Unit)? =
    onOpenAccount?.let { open -> { AccountGlyph(dark, open) } }

/** What the account glyph shows: your picture, else your initials, else the plain glyph. */
internal data class AccountFace(val avatar: Avatar, val name: String?)

/** D-217 final fix: the face HomeShell has read, for every tab root's glyph (the plain glyph outside the shell). */
internal val LocalAccountFace = androidx.compose.runtime.compositionLocalOf { AccountFace(Avatar.None, null) }

@Composable
internal fun AccountGlyph(dark: Boolean, onOpenAccount: () -> Unit) {
    // D-145: your picture (or initials) once you have one; the plain glyph
    // until then. Read once by HomeShell (D-217 final fix).
    val face = LocalAccountFace.current
    val avatar = face.avatar
    val name = face.name
    // D-192: the D-189 bell is gone (push notifications replace it). D-217: the bar's trailing item is a full
    // 48 x 48 dp target (the old row's IconButton was one), top-aligned in the bar's 48 dp node, with the glyph
    // centred on the 44 dp bar's own middle (22 dp down), as the back button is.
    // D-220 fix wave (F4): the target sits in by controlShift, so the item's centre is iOS's 41 dp from the edge.
    androidx.compose.foundation.layout.Box(
        Modifier
            .padding(end = BarDecisions.controlShift(BarDecisions.ACCOUNT_CENTRE_FROM_EDGE).dp)
            .size(BarDecisions.TOUCH_TARGET.dp)
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = androidx.compose.material3.ripple(bounded = false, radius = (BarDecisions.TOUCH_TARGET / 2).dp),
                role = androidx.compose.ui.semantics.Role.Button,
                onClick = onOpenAccount,
            )
            // D-144: the page it opens is Settings, the twin of iOS.
            .semantics { contentDescription = "Settings" },
        contentAlignment = androidx.compose.ui.Alignment.TopCenter,
    ) {
        // D-217 look fix 3: iOS 26 puts the plain glyph (0.73 of the 30 pt badge) in the same 45 pt glass circle as the
        // back button. D-220 (A1): round a picture or initials its glass is a 51 x 44 capsule (measured).
        val badge = !(avatar == Avatar.None && ProfileRules.initials(name) == null)
        BarCircle(dark, BarDecisions.accountBubbleHeight(badge), width = BarDecisions.accountBubbleWidth(badge)) {
            if (!badge) {
                androidx.compose.material3.Icon(
                    imageVector = TabIcons.UserRound,
                    contentDescription = null,
                    tint = Tok.textDim(dark),
                    modifier = Modifier.size((BarDecisions.ACCOUNT_BADGE_SIZE * BarDecisions.ACCOUNT_GLYPH_SCALE).dp),
                )
            } else {
                // D-221 (Q2 a): Home's account badge is the accent avatar (the accent fill, the initials in onAccent).
                AvatarView(avatar, name, BarDecisions.ACCOUNT_BADGE_SIZE.dp, dark, fill = AvatarStyle.fill(badge = true))
            }
        }
    }
}

/** D-216 (Design 6), a look's tool, not a user flow: posts ONE notification through [Push.show] with fixed words (no
 *  vault, no account, no network), so the look sees the cut S in the status bar and the shade. Reached only through
 *  ProofArgs.proof, so a debuggable build only (AppearanceTest). It needs the notification permission granted first. */
@Composable
private fun NotificationProof() {
    val context = LocalContext.current
    LaunchedEffect(Unit) { Push.show(context, "Group activity", "Ana added Dinner at Thalassa.", target = null) }
    Column(
        Modifier
            .fillMaxSize()
            .background(Tok.bg(isSystemInDarkTheme()))
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Notification posted.", style = Type.body(isSystemInDarkTheme()))
    }
}

@Composable
private fun Loading() {
    Column(
        Modifier
            .fillMaxSize()
            .background(Tok.bg(isSystemInDarkTheme()))
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("…", style = Type.numericColored(28, Tok.textFaint(isSystemInDarkTheme())))
    }
}

/** remember{} that evaluates its factory once per composition lifetime slot. */
@Composable
private fun <T> rememberOnce(calculation: () -> T): T =
    androidx.compose.runtime.remember { calculation() }

/**
 * D-194: which headless proof a launch asks for. The proofs are not user flows:
 * they skip the app lock, read or write the vault, and the emulator proofs
 * sign the account out. This activity is exported (the launcher starts it), so
 * any app on the phone can start it with any extras: a release build honours
 * none of them. `--emulators` (debug only) is the ordinary app against the
 * local emulators, not a proof.
 */
object ProofArgs {
    private fun tokens(args: String?): List<String> =
        args?.trim()?.split(Regex("\\s+"))?.filter { it.isNotEmpty() } ?: emptyList()

    fun proof(args: String?, debuggable: Boolean): String? {
        val trimmed = args?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (!debuggable || tokens(trimmed).first() == "--emulators" || tokens(trimmed).first() == "--welcome") return null
        return trimmed
    }

    /** D-245: `--welcome` (Debug builds only, alone or after `--emulators`): the ORDINARY app with the welcome shown,
     *  a look's tool. Not a proof: the lock and the gate run as always, and closing it clears nothing it did not set. */
    fun welcome(args: String?, debuggable: Boolean): Boolean = debuggable && "--welcome" in tokens(args)

    /** `--emulators` (Debug builds only): the ordinary app against the local
     *  emulators. It may carry `--emulator-as <sub>`. */
    fun emulators(args: String?, debuggable: Boolean): Boolean =
        debuggable && tokens(args).firstOrNull() == "--emulators"

    /** D-210: `--emulators --emulator-as <sub>` signs in to the Auth emulator
     *  as that subject first (a look with several accounts), then the ordinary
     *  app runs. Debug builds only; a look's tool, not a user flow. */
    fun emulatorAs(args: String?, debuggable: Boolean): String? {
        if (!emulators(args, debuggable)) return null
        val t = tokens(args)
        val i = t.indexOf("--emulator-as")
        return if (i >= 0) t.getOrNull(i + 1) else null
    }
}

/**
 * `--emulators --emulator-as <sub>` (D-210, Debug builds only, a look's tool,
 * not a user flow): signs in to the local Auth emulator with an unsigned
 * Google token for `sub`, as the sync proofs do, unless this phone is already
 * signed in as it. iOS's twin is `EmulatorSignIn` in SkiptiApp.swift.
 */
object EmulatorSignIn {
    /** Set by the first onCreate that signs in (a rotation makes the activity again). */
    internal val started = java.util.concurrent.atomic.AtomicBoolean(false)

    /** True once per process. */
    fun firstThisProcess(): Boolean = started.compareAndSet(false, true)

    suspend fun signIn(sub: String, vaultPath: String) {
        val current = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser
            ?.providerData?.firstOrNull { it.providerId == "google.com" }?.uid
        if (current == sub) {
            android.util.Log.i("emulator-as", "already signed in as $sub")
            return
        }
        if (Account.isSignedIn()) Account.signOut()
        val token = """{"sub":"$sub","email":"$sub@example.test","email_verified":true}"""
        val outcome = Account.signIn(
            com.google.firebase.auth.GoogleAuthProvider.getCredential(token, null), SignInMethod.Google, LedgerStore(vaultPath),
        )
        android.util.Log.i("emulator-as", "signed in as $sub: $outcome")
    }
}
