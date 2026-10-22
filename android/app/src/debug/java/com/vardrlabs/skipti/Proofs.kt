package com.vardrlabs.skipti

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import uniffi.skipti_core.skiptiCoreVersion

// D-260 (G-13, decision 269): the proof surfaces' dispatch and the emulator launch hook, Debug builds only. The
// release build compiles src/release's stubs instead (ProofSurface draws nothing, the hook does nothing), so none of
// these words or screens ships in it (scripts/check-release-proofs.py).

/**
 * The headless verification surfaces (moved from AppRoot, D-260): the first argument's word picks one, which draws
 * instead of the app. They bypass the §3.4 gate entirely (not user flows): the balance screen computes from fixtures or
 * the vault, the list reads the vault, the add screen writes it, sign-in and the sync proofs are the §4.2/§4.3
 * verification surfaces, so no kit needs to exist to verify them. Returns whether a surface was drawn (AppRoot then
 * draws nothing else). Reached only through ProofArgs.proof, so a debuggable build only.
 */
@Composable
fun ProofSurface(headless: String?, args: List<String>, vaultPath: String): Boolean {
    val context = LocalContext.current
    when (headless) {
        "auth-emulator-proof" -> {
            // D-191: a Firebase sign-in against the local Auth emulator.
            AuthEmulatorProof(vaultPath)
            return true
        }
        "sync-emulator-proof" -> {
            // D-194: two phones of one account converge through Firestore.
            SyncEmulatorProof(vaultPath)
            return true
        }
        "two-account-proof" -> {
            // D-195: Bo joins Al's group with the link among the arguments.
            TwoAccountProof(vaultPath, args.getOrNull(1) ?: "")
            return true
        }
        "settings-emulator-proof" -> {
            // D-244: settings follow one account across two phones; the role (a or b) is among the arguments.
            SettingsEmulatorProof(vaultPath, args.getOrNull(1))
            return true
        }
        "bar-proof" -> {
            // D-217 Task 4: the three forms of SkiptiBar over rows (a look's tool; every screen uses the bar since Task 8).
            BarProof(args.getOrNull(1))
            return true
        }
        "notification-proof" -> {
            NotificationProof()
            return true
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
            return true
        }
        "store-shots" -> {
            // D-251: the store pages' seed (a proof launch: no lock, no welcome, no cloud), then the ordinary tabs on Home.
            StoreShotsProof(vaultPath) { HomeShell(vaultPath = vaultPath, startOn = HomeTab.BALANCE) }
            return true
        }
        "balance" -> {
            BalanceScreen(BalanceSource.FIXTURE, version = skiptiCoreVersion(), vaultPath = vaultPath)
            return true
        }
        "balance-vault" -> {
            BalanceScreen(BalanceSource.VAULT_FIXTURES, version = skiptiCoreVersion(), vaultPath = vaultPath)
            return true
        }
        "balance-empty" -> {
            BalanceScreen(BalanceSource.EMPTY, version = skiptiCoreVersion(), vaultPath = vaultPath)
            return true
        }
        "add" -> {
            AddExpenseScreen(vaultPath = vaultPath)
            return true
        }
        "settle" -> {
            SettleScreen(vaultPath = vaultPath)
            return true
        }
        "list-vault" -> {
            ExpenseListScreen(vaultPath = vaultPath)
            return true
        }
        "signin" -> {
            SignInScreen(vaultPath, onBack = {}) { _, _ -> }
            return true
        }
        "export" -> {
            ExportScreen(LedgerStore(vaultPath))
            return true
        }
        "import" -> {
            ImportScreen(LedgerStore(vaultPath))
            return true
        }
        "export-proof" -> {
            ExportProofScreen(dir = File(context.cacheDir, "export-proof").apply { mkdirs() })
            return true
        }
        "expense-conflict" -> {
            // D-143: SPEC §6.1's question, over a throwaway vault.
            ExpenseConflictProofScreen(File(context.cacheDir, "expense-conflict-proof"))
            return true
        }
        "import-proof" -> {
            ImportProofScreen(store = LedgerStore(vaultPath))
            return true
        }
    }
    return false
}

/**
 * `--emulators` (D-194; moved from MainActivity.onCreate, D-260): the ordinary app against the local Auth and Firestore
 * emulators. Debug builds only: another app can start this activity with any extras, and MainActivity asks
 * ProofArgs.emulators first. [openGroup] runs on the main thread.
 */
object ProofLaunch {
    fun emulators(activity: ComponentActivity, launchArgs: String?, debuggable: Boolean, openGroup: (String) -> Unit) {
        if (!ProofArgs.emulators(launchArgs, debuggable)) return
        com.google.firebase.auth.FirebaseAuth.getInstance().useEmulator("localhost", 9099)
        Cloud.emulator = true
        // D-210: `--emulators --emulator-as <sub>` signs in as that
        // subject first (a look with several accounts); Debug builds only.
        ProofArgs.emulatorAs(launchArgs, debuggable)?.takeIf { EmulatorSignIn.firstThisProcess() }?.let { sub ->
            val vault = File(activity.applicationContext.filesDir, "ledger-vault.db").absolutePath
            kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                EmulatorSignIn.signIn(sub, vault)
                // D-252: `--group-end <step> [arg]` then runs one step of the two-phone proof (GroupEndProof).
                GroupEndProof.run(launchArgs?.trim()?.split(Regex("\\s+")).orEmpty(), vault, activity.filesDir, openGroup)
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

/**
 * `--emulators --emulator-as <sub> --group-end <step> [arg] [--group-end-me <name>]` (D-252, debug builds only, the
 * two-phone proof's tool, not a user flow): the ORDINARY app against the local emulators, signed in as `sub`, runs one
 * step by script, then watches every group it holds. Steps: `create <group>` (with a member "Yan", a Dinner, and an
 * invite for Yan, its link printed), `join <link>`, `delete <group>` (the call and the mark Delete group makes), and
 * `watch`. The watch prints each group's read-only state whenever it changes (the banner's own line), and at a group's
 * first mark tries an Add into it (refused with words before anything is written, nothing parked), then opens that
 * group's page. Lines go to logcat as `GROUP-END <epoch ms> ...` and to `files/group-end.txt`. iOS's twin is
 * GroupEndProof in SkiptiApp.swift.
 */
object GroupEndProof {
    private fun say(files: File, line: String) {
        val stamped = "${System.currentTimeMillis()} $line"
        android.util.Log.i("GROUP-END", stamped)
        runCatching { File(files, "group-end.txt").appendText(stamped + "\n") }
    }

    suspend fun run(args: List<String>, vaultPath: String, files: File, open: (String) -> Unit) {
        val i = args.indexOf("--group-end")
        if (i < 0) return
        val step = args.getOrNull(i + 1) ?: return
        val arg = args.getOrNull(i + 2)?.takeIf { !it.startsWith("--") } ?: ""
        val me = args.indexOf("--group-end-me").takeIf { it >= 0 }?.let { args.getOrNull(it + 1) } ?: "Xan"
        val store = LedgerStore(vaultPath)
        runCatching { WelcomeGate.clear(store) }
        val started = System.currentTimeMillis()
        while (Account.uid() == null && System.currentTimeMillis() - started < 60_000) delay(250)
        // D-254: the emulator account is approved (as the founder would), so the cloud starts.
        Account.uid()?.let { say(files, "approved: ${EmulatorApproval.approve(it)}") }
        say(files, "step $step $arg: uid ${Account.uid() ?: "none"}")
        try {
            when (step) {
                "create" -> {
                    if (store.loadSelf()?.name == null) store.setYourName(me)
                    val group = store.createGroup(arg, "USD", listOf("Yan"))
                    val yan = group.members.first { it.id != group.selfId }
                    store.appendExpense(
                        ExpenseDTO(
                            payer = group.selfId, paidMinor = 4_000, currencyCode = "USD", group = group.group.id,
                            shares = listOf(ExpenseDTO.ShareDTO(group.selfId, 2_000), ExpenseDTO.ShareDTO(yan.id, 2_000)),
                            note = "Dinner", timestampMs = System.currentTimeMillis(), addedBy = group.selfId,
                        ),
                    )
                    CloudSync.ledgerChanged(vaultPath)
                    val link = Invites.create(store, group, yan)
                    say(files, "created $arg ${group.group.id} INVITE $link")
                }
                "join" -> {
                    if (store.loadSelf()?.name == null) store.setYourName(me)
                    val offer = Invites.peek(arg)
                    val group = Invites.accept(arg, offer, store)
                    CloudSync.ledgerChanged(vaultPath)
                    say(files, "joined ${offer.groupName} $group")
                }
                "delete" -> {
                    var found: GroupDTO? = null
                    while (found == null && System.currentTimeMillis() - started < 120_000) {
                        found = runCatching { store.loadGroups() }.getOrNull()?.firstOrNull { it.name == arg }
                        if (found == null) delay(500)
                    }
                    val group = found ?: return say(files, "no group $arg here")
                    val named = store.namedGroups().firstOrNull { it.group.id == group.id }
                    val viewerName = named?.members?.firstOrNull { it.id == named.selfId }?.name ?: ""
                    say(files, "deleting $arg ${group.id}")
                    GroupDelete.run(
                        call = {
                            CloudFunctions.callJson("deleteGroup", kotlinx.serialization.json.buildJsonObject {
                                put("groupId", kotlinx.serialization.json.JsonPrimitive(group.id))
                                put("groupName", kotlinx.serialization.json.JsonPrimitive(RoleRules.wireName(group.name)))
                                put("byName", kotlinx.serialization.json.JsonPrimitive(RoleRules.wireName(viewerName)))
                            })
                        },
                        mark = {
                            store.markReadOnly(group.id, ReadOnlyDTO("deleted", viewerName, System.currentTimeMillis(), byYou = true))
                        },
                    )
                    say(files, "deleted $arg: the call returned and this phone marked it")
                }
                else -> Unit
            }
        } catch (e: Exception) {
            say(files, "FAILED $step: $e")
        }
        watch(store, files, open)
    }

    /** Prints each group's state whenever it changes; at a group's first mark, tries an Add and opens its page. */
    private suspend fun watch(store: LedgerStore, files: File, open: (String) -> Unit) {
        val seen = mutableMapOf<String, String>()
        val tried = mutableSetOf<String>()
        val started = System.currentTimeMillis()
        while (System.currentTimeMillis() - started < 1_200_000) {
            val marks = runCatching { store.readOnlyChecked() }.getOrDefault(emptyMap())
            for (group in runCatching { store.loadGroups() }.getOrDefault(emptyList())) {
                val mark = marks[group.id]
                val state = mark?.let {
                    "read-only ${it.kind}${if (it.byYou == true) " byYou" else ""}: \"${ReadOnlyBanner.title(it, Phones.day(it.atMs), group.name)}\""
                } ?: "writable"
                if (seen[group.id] != state) {
                    seen[group.id] = state
                    say(files, "group ${group.name} ${group.id.take(8)}: $state")
                }
                if (mark == null || group.id in tried || group.members.size < 2) continue
                tried += group.id
                val records = runCatching { store.allRecords().size }.getOrDefault(-1)
                val add = ExpenseDTO(
                    payer = group.members[0], paidMinor = 1_000, currencyCode = "USD", group = group.id,
                    shares = listOf(ExpenseDTO.ShareDTO(group.members[0], 500), ExpenseDTO.ShareDTO(group.members[1], 500)),
                    note = "Taxi after the end", timestampMs = System.currentTimeMillis(), addedBy = group.members[0],
                )
                try {
                    store.appendExpense(add)
                    say(files, "ADD in ${group.name} was WRITTEN (wrong)")
                } catch (e: Exception) {
                    say(files, "add in ${group.name} refused: \"${e.message}\"")
                }
                val after = runCatching { store.allRecords().size }.getOrDefault(-1)
                val parked = runCatching { if (CloudRecords.hasParked(store, group.id)) "yes" else "no" }.getOrDefault("unknown")
                say(files, "records before $records, after $after; parked here: $parked")
                open(group.id)
            }
            delay(250)
        }
    }
}
