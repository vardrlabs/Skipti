package com.vardrlabs.skipti

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.firebase.auth.AuthCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.Source
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

// D-260 (G-13, decision 269): a proof surface moved out of src/main, Debug builds only (src/release has none).

/**
 * `--auth-emulator-proof` (D-191, headless verification, not a user flow):
 * points Firebase Auth at the local emulator (`firebase emulators:start
 * --only auth --project demo-skipti`, port 9099, reached through `adb reverse
 * tcp:9099 tcp:9099` so the loopback-only cleartext rule holds), signs in
 * with an emulator-only Google ID token, then signs out, through the same
 * [Account.signIn] the sign-in page uses. iOS's twin is AuthEmulatorProofScreen.
 */
@Composable
fun AuthEmulatorProof(vaultPath: String) {
    val dark = isSystemInDarkTheme()
    var lines by remember { mutableStateOf(listOf<String>()) }
    LaunchedEffect(Unit) {
        val out = mutableListOf<String>()
        withContext(Dispatchers.IO) {
            FirebaseAuth.getInstance().useEmulator("localhost", 9099)
            // D-193: Firestore's emulator too (port 8180, the repo's rules;
            // `adb reverse tcp:8180 tcp:8180`).
            Cloud.emulator = true
            Account.signOut()
            val token = """{"sub":"proof-a","email":"a@example.test","email_verified":true}"""
            val outcome = Account.signIn(
                GoogleAuthProvider.getCredential(token, null), SignInMethod.Google, LedgerStore(vaultPath),
            )
            // D-254: the emulator account is approved (as the founder would), so the cloud starts.
            Account.uid()?.let { out += "approved: ${EmulatorApproval.approve(it)}" }
            out += "outcome: $outcome"
            out += "uid: ${Account.uid() ?: "none"}"
            out += "method: ${Account.method(LedgerStore(vaultPath))?.label ?: "none"}"
            Account.uid()?.let { out += proveProfile(it, LedgerStore(vaultPath)) }
            Account.signOut()
            out += "after sign-out: ${if (Account.isSignedIn()) "signed in" else "signed out"}"
        }
        lines = out
    }
    Column(Modifier.fillMaxSize().background(Tok.bg(dark)).padding(20.dp)) {
        Text("Firebase Auth, against the emulator", style = Type.label(dark, 17))
        lines.forEach { Text(it, style = Type.body(dark)) }
    }
}

/** The sign-in registered the profile and this phone (CloudSync); read them
 *  back, then show the rules refusing a phone that marks itself signed out. */
private suspend fun proveProfile(uid: String, store: LedgerStore): List<String> {
    val out = mutableListOf<String>()
    val user = Cloud.db().collection("users").document(uid)
    val phone = user.collection("devices").document(CloudProfile.deviceId(store))
    for (attempt in 0 until 50) {
        if (runCatching { phone.get(Source.SERVER).awaitTask().exists() }.getOrDefault(false)) break
        delay(100)
    }
    val profile = runCatching { user.get(Source.SERVER).awaitTask().data }.getOrNull()
    val entry = runCatching { phone.get(Source.SERVER).awaitTask().data }.getOrNull()
    val you = store.loadSelf()?.id
    out += "profile participantId is you: ${profile?.get("participantId") == you}"
    out += "profile keys: ${profile?.keys?.sorted()?.joinToString(",") ?: "none"}"
    out += "device: ${entry?.get("name") ?: "none"}, ${entry?.get("platform") ?: "none"}, ${entry?.get("appVersion") ?: "none"}"
    out += try {
        phone.update(mapOf("signedOut" to false)).awaitTask()
        "signedOut write: ALLOWED (wrong)"
    } catch (_: Exception) {
        "signedOut write: refused"
    }
    return out
}

/**
 * `--sync-emulator-proof` (D-194, headless verification, not a user flow): two
 * phones of ONE account converge. Run it on the Android emulator and the iOS
 * simulator at once, both against the local Auth and Firestore emulators
 * (`adb reverse tcp:9099 tcp:9099` and `tcp:8180 tcp:8180`): each signs in as
 * the same emulator account, makes a group with one expense, and waits for the
 * other phone's group and expense to arrive through Firestore. iOS's twin is
 * SyncEmulatorProofScreen.
 */
@Composable
fun SyncEmulatorProof(vaultPath: String) {
    val dark = isSystemInDarkTheme()
    var lines by remember { mutableStateOf(listOf<String>()) }
    LaunchedEffect(Unit) {
        val me = "Android"
        val other = "iOS"
        val out = mutableListOf<String>()
        withContext(Dispatchers.IO) {
            FirebaseAuth.getInstance().useEmulator("localhost", 9099)
            Cloud.emulator = true
            Account.signOut()
            val token = """{"sub":"proof-sync","email":"s@example.test","email_verified":true}"""
            val store = LedgerStore(vaultPath)
            val outcome = Account.signIn(GoogleAuthProvider.getCredential(token, null), SignInMethod.Google, store)
            out += "signed in: $outcome, uid ${Account.uid() ?: "none"}"
            // D-254: the emulator account is approved (as the founder would), so the cloud starts.
            Account.uid()?.let { out += "approved: ${EmulatorApproval.approve(it)}" }
            try {
                store.setYourName("Sam on $me")
                val group = store.createGroup("From $me", "USD", emptyList())
                val you = group.selfId
                store.appendExpense(
                    ExpenseDTO(
                        payer = you, paidMinor = 1_200, currencyCode = "USD", group = group.group.id,
                        shares = listOf(ExpenseDTO.ShareDTO(you, 1_200)), note = "Dinner from $me",
                        timestampMs = System.currentTimeMillis(), addedBy = you,
                    ),
                )
                CloudSync.ledgerChanged(vaultPath)
                out += "made: group From $me, expense Dinner from $me"
            } catch (e: Exception) {
                out += "could not make the group: $e"
                return@withContext
            }
            val started = System.currentTimeMillis()
            var seen = false
            while (System.currentTimeMillis() - started < 120_000) {
                if (runCatching { store.loadExpenses() }.getOrDefault(emptyList()).any { it.note == "Dinner from $other" }) {
                    seen = true
                    break
                }
                delay(500)
            }
            val groups = store.loadGroups().map { it.name }.sorted()
            val expenses = store.loadExpenses()
            val you = store.loadSelf()?.id
            val theirs = expenses.firstOrNull { it.note == "Dinner from $other" }
            out += "pushed (last push): ${CloudSync.lastPushed}; received: ${CloudSync.receivedCount}"
            out += "groups here: ${groups.joinToString(", ")}"
            out += "$other's expense here: ${if (seen) "yes" else "NO"} after ${(System.currentTimeMillis() - started) / 1000} s"
            out += "$other's payer reads as you: ${theirs?.payer == you && you != null}"
            out += "expenses here: ${expenses.mapNotNull { it.note }.sorted().joinToString(", ")}"
        }
        lines = out
    }
    Column(Modifier.fillMaxSize().background(Tok.bg(dark)).padding(20.dp)) {
        Text("Two phones, one account, against the emulator", style = Type.label(dark, 17))
        lines.forEach { Text(it, style = Type.body(dark)) }
    }
}

/**
 * `--two-account-proof <link>` (D-195, headless verification): Bo's half of the
 * test the relay never passed. Signs in as Bo (ANOTHER emulator account than
 * the iPhone's Al), joins "Goa trip" with the invite link the iPhone printed,
 * waits for Al's Dinner, then adds Taxi and edits Dinner's note; the iPhone
 * must show both. iOS's twin of the other half is TwoAccountProofScreen.
 */
@Composable
fun TwoAccountProof(vaultPath: String, link: String) {
    val dark = isSystemInDarkTheme()
    var lines by remember { mutableStateOf(listOf<String>()) }
    LaunchedEffect(Unit) {
        val out = mutableListOf<String>()
        withContext(Dispatchers.IO) {
            FirebaseAuth.getInstance().useEmulator("localhost", 9099)
            Cloud.emulator = true
            Account.signOut()
            val token = """{"sub":"proof-bo","email":"bo@example.test","email_verified":true}"""
            val store = LedgerStore(vaultPath)
            val outcome = Account.signIn(GoogleAuthProvider.getCredential(token, null), SignInMethod.Google, store)
            out += "Bo signed in: $outcome, uid ${Account.uid() ?: "none"}"
            // D-254: the emulator account is approved (as the founder would), so the cloud starts.
            Account.uid()?.let { out += "approved: ${EmulatorApproval.approve(it)}" }
            try {
                store.setYourName("Bo")
                val offer = Invites.peek(link)
                out += "offer: ${InviteCopy.invited(offer.inviterName, offer.groupName)} ${InviteCopy.youJoinAs(offer.memberName)}"
                val group = Invites.accept(link, offer, store)
                out += "joined: $group"
                val started = System.currentTimeMillis()
                while (System.currentTimeMillis() - started < 120_000 &&
                    store.loadExpenses().none { it.note == "Dinner" }
                ) delay(500)
                val named = store.namedGroups().first { it.group.id == group }
                val me = named.selfId
                val al = named.members.first { it.id != me }
                out += "Al's Dinner here: ${store.loadExpenses().any { it.note == "Dinner" }}; I am ${LedgerStore.label(me, named)}"
                store.appendExpense(
                    ExpenseDTO(
                        payer = me, paidMinor = 1_000, currencyCode = "USD", group = group,
                        shares = listOf(ExpenseDTO.ShareDTO(me, 500), ExpenseDTO.ShareDTO(al.id, 500)),
                        note = "Taxi from Bo", timestampMs = System.currentTimeMillis(), addedBy = me,
                    ),
                )
                val dinner = store.projectedExpenses().expenses.first { it.note == "Dinner" }
                store.reviseExpense(
                    uniffi.skipti_core.ExpenseRevisionIn(
                        dinner.id, dinner.seen, me, null, "Dinner, edited by Bo", null, null,
                    ),
                )
                CloudSync.ledgerChanged(vaultPath)
                out += "added Taxi from Bo; edited Dinner"
                delay(5_000)
                out += "expenses here: ${store.loadExpenses().mapNotNull { it.note }.sorted().joinToString(", ")}"
            } catch (e: Exception) {
                out += "failed: $e"
            }
        }
        lines = out
    }
    Column(Modifier.fillMaxSize().background(Tok.bg(dark)).padding(20.dp)) {
        Text("Two accounts: Bo (this phone) and Al", style = Type.label(dark, 17))
        lines.forEach { Text(it, style = Type.body(dark)) }
    }
}


/**
 * `--settings-emulator-proof a|b` (D-244, headless verification, not a user flow): settings follow ONE account across
 * two phones, against the local Auth and Firestore emulators (`adb reverse tcp:9099 tcp:9099` and `tcp:8180
 * tcp:8180`). Run it on the Android emulator and the iOS simulator at once, one phone `a` and the other `b` (then the
 * other way round); both sign in as the emulator account named after phone a. Phone a first shows that without an
 * account a change writes no record (GUARANTEES #5), then signs in, catches up, and sets Norse and EUR through the
 * setters a tap uses, which go up. Phone b signs in and waits: it must apply Norse and EUR, write no setting record of
 * its own (no echo), and leave the icon pending (never switched at arrival) for its front moment, which a proof never
 * has: the look shows that moment. The run lives in this object, not the composition: an arrived theme tells the
 * night mode, which makes the activity again (D-232). Lines go to the screen and to logcat (`SettingsProof`). iOS's
 * twin is SettingsEmulatorProofScreen.
 */
object SettingsEmulatorProof {
    val lines = androidx.compose.runtime.mutableStateListOf<String>()
    private val started = java.util.concurrent.atomic.AtomicBoolean(false)

    private data class Rec(val key: String, val value: String, val device: String, val id: String)

    /** Every setting record in this vault (the log, not the projection's winners), with its device. */
    private fun records(store: LedgerStore): List<Rec> = runCatching { store.allRecords() }.getOrDefault(emptyList()).mapNotNull { r ->
        if (CloudRecords.settingKey(r) == null) return@mapNotNull null
        val op = runCatching { uniffi.skipti_core.ffiDecodeGroupOp(r.blob) }.getOrNull() as? uniffi.skipti_core.GroupOpIn.Setting
            ?: return@mapNotNull null
        Rec(op.key, op.value, r.hlcDeviceId, CloudRecords.documentId(r))
    }

    private fun describe(rs: List<Rec>): String = if (rs.isEmpty()) "none" else rs.joinToString(", ") { "${it.key}=${it.value}" }

    fun start(context: android.content.Context, vaultPath: String, role: String?) {
        if (!started.compareAndSet(false, true)) return
        val app = context.applicationContext
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO).launch { run(app, vaultPath, role) }
    }

    private suspend fun say(line: String) {
        android.util.Log.i("SettingsProof", line)
        withContext(Dispatchers.Main) { lines += line }
    }

    private suspend fun run(app: android.content.Context, vaultPath: String, role: String?) {
        val me = "Android"
        val other = "iOS"
        val b = role == "b"
        val sub = "proof-settings-" + (if (b) other else me).lowercase()
        FirebaseAuth.getInstance().useEmulator("localhost", 9099)
        Cloud.emulator = true
        Account.signOut()
        val store = LedgerStore(vaultPath)
        val prefs = SettingsSync.prefsOf(app)
        val device = runCatching { LedgerStore.deviceId(store) }.getOrNull() ?: "unknown"
        fun mine() = records(store).filter { it.device == device }
        fun theme() = SettingsSync.local(SettingsSync.THEME, prefs) ?: "none"
        fun currency() = SettingsSync.local(SettingsSync.CURRENCY, prefs) ?: "none"
        say("phone ${if (b) "b" else "a"} ($me), account $sub, device ${device.take(8)}")
        if (!b) {
            // GUARANTEES #5: signed out, a change is stored here and writes no record.
            val before = mine().size
            withContext(Dispatchers.Main) { Appearance.setTheme(app, AppTheme.VARDR) }
            delay(3_000)
            say("no account: theme here ${theme()}; setting records written: ${mine().size - before} (want 0)")
        }
        val token = """{"sub":"$sub","email":"$sub@example.test","email_verified":true}"""
        val outcome = Account.signIn(GoogleAuthProvider.getCredential(token, null), SignInMethod.Google, store)
        say("signed in: $outcome, uid ${Account.uid() ?: "none"}")
        val uid = Account.uid() ?: return say("NOT signed in")
        // D-254: the emulator account is approved (as the founder would), so the cloud starts.
        say("approved: ${EmulatorApproval.approve(uid)}")
        val started = System.currentTimeMillis()
        while (!SettingsSync.isSeeded(store, uid) && System.currentTimeMillis() - started < 60_000) delay(250)
        val seededAfter = (System.currentTimeMillis() - started) / 1000
        say("caught up with the account (seeded): ${if (SettingsSync.isSeeded(store, uid)) "yes" else "NO"} after $seededAfter s; my setting records: ${describe(mine())}")
        if (!b) {
            withContext(Dispatchers.Main) {
                Appearance.setTheme(app, AppTheme.NORSE)
                DefaultCurrency.set(app, "EUR")
            }
            val wrote = System.currentTimeMillis()
            while (mine().none { it.key == SettingsSync.CURRENCY } && System.currentTimeMillis() - wrote < 10_000) delay(100)
            CloudSync.ledgerChanged(vaultPath)
            say("set: theme norse, currency EUR; my setting records: ${describe(mine())}")
            val pushStart = System.currentTimeMillis()
            var up = false
            while (System.currentTimeMillis() - pushStart < 60_000) {
                val uploaded = runCatching { CloudRecords.uploaded(store) }.getOrDefault(emptySet())
                val own = mine()
                if (own.isNotEmpty() && own.all { it.id in uploaded }) {
                    up = true
                    break
                }
                delay(250)
            }
            say("my setting records up: ${if (up) "yes" else "NO"} after ${(System.currentTimeMillis() - pushStart) / 1000} s (last push ${CloudSync.lastPushed})")
            say("theme here ${theme()}, currency here ${currency()}")
            return
        }
        val shownBefore = SettingsSync.shownIcon(app)
        val waitStart = System.currentTimeMillis()
        var arrived = false
        while (System.currentTimeMillis() - waitStart < 180_000) {
            if (theme() == AppTheme.NORSE.stored && currency() == "EUR") {
                arrived = true
                break
            }
            delay(250)
        }
        say("applied from $other: ${if (arrived) "yes" else "NO"} after ${(System.currentTimeMillis() - waitStart) / 1000} s (received ${CloudSync.receivedCount})")
        // Give an echo time to happen, then count.
        delay(8_000)
        val all = records(store)
        say("theme here ${theme()}, currency here ${currency()}")
        say("setting records here: ${all.size} (from $other: ${describe(all.filter { it.device != device })})")
        say("my own setting records (echo): ${mine().size} (want 0) ${describe(mine())}")
        say("applied marks: ${runCatching { SettingsSync.applied(store) }.getOrDefault(emptyMap()).keys.sorted().joinToString(", ")}")
        val shownNow = SettingsSync.shownIcon(app)
        val pending = SettingsSync.iconIsPending(store)
        say("icon shown before ${shownBefore?.stored ?: "none"}, now ${shownNow?.stored ?: "none"} (unchanged at arrival); pending: ${if (pending) "yes" else "NO"}")
        val target = AppIconPlan.target(AppIconChoice.parse(prefs(Appearance.PREFS).getString(AppIconChoice.STORAGE_KEY, null)), AppTheme.parse(theme()))
        val atFront = SettingsSync.frontApplies(pending, locked = false, active = true)
        val elsewhere = SettingsSync.frontApplies(pending, locked = true, active = true) || SettingsSync.frontApplies(pending, locked = false, active = false)
        say("at the front moment (in front, unlocked): changes ${if (atFront) "yes" else "no"} to ${target.stored}; in the background or locked: ${if (elsewhere) "changes (wrong)" else "waits"}")
    }
}

@Composable
fun SettingsEmulatorProof(vaultPath: String, role: String?) {
    val dark = isSystemInDarkTheme()
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) { SettingsEmulatorProof.start(context, vaultPath, role) }
    Column(Modifier.fillMaxSize().background(Tok.bg(dark)).padding(20.dp)) {
        Text("Settings follow the account, against the emulator", style = Type.label(dark, 17))
        SettingsEmulatorProof.lines.forEach { Text(it, style = Type.body(dark)) }
    }
}
