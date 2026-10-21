package com.vardrlabs.skipti

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.skipti_core.SkiptiKeystore

/**
 * D-168: one account per phone (the founder, 2026-09-25); the twin of
 * ios/Skipti/App/AccountOwner.swift.
 *
 * A phone's ledger belongs to the first account that signs in on it. Signing
 * out keeps the sync keys, the push cursor and "you" so that signing back in
 * resumes (D-155), which meant a DIFFERENT account signing in would have
 * received this ledger. Now the phone remembers the account's subject id,
 * kept through sign-out, and a sign-in by another account is refused before
 * its session is stored or anything syncs; the refused session is ended on
 * the relay. To use the other account here, the person exports if they want
 * a copy, then erases this phone's ledger and sets SKIPTI up again. The erase
 * is never automatic.
 */
object AccountOwner {
    const val KEY = "account/owner"

    /** The pure rule: no owner yet, or the same account. */
    fun owns(stored: String?, incoming: String): Boolean = stored == null || stored == incoming

    fun stored(store: LedgerStore): String? =
        store.rawKvGet(KEY)?.takeIf { it.isNotEmpty() }?.decodeToString()

    fun remember(subject: String, store: LedgerStore) {
        store.rawKvSet(KEY, subject.toByteArray(Charsets.UTF_8))
    }
}

/**
 * D-172: how this phone signed in, so Settings can say it (the founder,
 * 2026-09-26: "show exactly what was used to sign in"). Google gives SKIPTI no
 * email (scope `openid`, only `sub` is read: ADR-0035), so it is named by
 * provider; an email code or a passkey is named by the address typed. Kept in
 * the vault, on this phone only. Twin of iOS's `SignInMethod` (Apple exists
 * there only, but a stored value decodes the same on both).
 */
sealed class SignInMethod {
    object Apple : SignInMethod()
    object Google : SignInMethod()
    data class Email(val address: String) : SignInMethod()

    val encoded: String
        get() = when (this) {
            Apple -> "apple"
            Google -> "google"
            is Email -> "email:$address"
        }

    /** D-234 (decision 84): You's Profile and account row, in one word (the page keeps [label]; never the address). */
    val rowLabel: String
        get() = when (this) {
            Apple -> "Apple"
            Google -> "Google"
            is Email -> "Email"
        }

    /** What Settings and the confirmation say. */
    val label: String
        get() = when (this) {
            Apple -> "Signed in with Apple"
            Google -> "Signed in with Google"
            is Email -> "Signed in as $address"
        }

    companion object {
        const val KEY = "account/method"

        /** Anything this build does not recognise is no method at all. */
        fun decode(raw: String): SignInMethod? = when {
            raw == "apple" -> Apple
            raw == "google" -> Google
            raw.startsWith("email:") -> raw.removePrefix("email:").takeIf { it.isNotEmpty() }?.let { Email(it) }
            else -> null
        }

        fun stored(store: LedgerStore): SignInMethod? =
            runCatching { store.rawKvGet(KEY)?.decodeToString()?.let(::decode) }.getOrNull()

        fun remember(method: SignInMethod, store: LedgerStore) {
            store.rawKvSet(KEY, method.encoded.toByteArray(Charsets.UTF_8))
        }

        /** C2: keeps a completed sign-in across recreation (rotation, a
         *  theme or font change, process death) as its stored encoding. Only
         *  Apple's and Google's encodings ever reach it: email sign-in was
         *  retired in D-191, so no sign-in this page completes is `Email`,
         *  and no address is written to the saved state. */
        val saver: Saver<SignInMethod?, String> = Saver(save = { it?.encoded }, restore = { decode(it) })
    }
}


/** D-172: what a completed sign-in shows (the founder, 2026-09-26). */
object SignedInCopy {
    const val TITLE = "You're signed in"
    const val DETAIL = "Settings shows how you signed in. You can sign out there at any time."
    const val DONE = "Done"
    const val NOT_SAVED = "Signed in, but this phone could not save it. Try again."
}

/** Google's G, or nothing: Android never signs in with Apple, and an address
 *  is its own label. */
@Composable
fun SignInMethodRow(method: SignInMethod, dark: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (method == SignInMethod.Google) GoogleMark(28)
        Text(method.label, style = Type.body(dark))
    }
}

@Composable
fun SignedInConfirmation(method: SignInMethod, onDone: () -> Unit) {
    val dark = isSystemInDarkTheme()
    // D-217: iOS keeps the bar's title "Sign in" here and hides the back button (onBack = null draws none).
    Box(Modifier.fillMaxSize().auroraBackground(dark)) {
        Column(
            Modifier.fillMaxSize().padding(start = 24.dp, end = 24.dp, top = BarDecisions.scrollTop(20f).dp, bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            // D-224 fix wave (F8, iOS is the scale): iOS's `Spacer(minLength: 40)` over the check and `Spacer()` over the
            // button share the free height, so the block sits centred with at least 40 above it.
            Spacer(Modifier.heightIn(min = 40.dp).weight(1f))
            // D-224 (Design 6, 8): the big check, drawn by the motion engine, in place of the 72 dp drawn tick.
            DoneCheck(DonePresentation.SCREEN, dark)
            Text(SignedInCopy.TITLE, style = Type.display(dark).copy(fontSize = 28.sp))
            SignInMethodRow(method, dark)
            Text(SignedInCopy.DETAIL, style = Type.bodyDim(dark).copy(fontSize = TypeScale.detail.sp), textAlign = TextAlign.Center)
            Spacer(Modifier.weight(1f))
            PrimaryAction(SignedInCopy.DONE, dark, onClick = onDone)
        }
        PushedBar("Sign in", onBack = null)
    }
}

/** D-184's notice that this phone's ledger is erased, as D-224's big screen (Design 7, 8): the check in place of the
 *  alert, then its title, its words and "OK", laid out as the signed-in screen is. The twin of iOS's erase cover. */
@Composable
fun ErasedScreen(onDone: () -> Unit) {
    val dark = isSystemInDarkTheme()
    Box(Modifier.fillMaxSize().auroraBackground(dark)) {
        Column(
            // D-224 fix wave (F8): no bar over this screen (iOS's erase cover has none either), so its top is iOS's
            // `.padding(20)`, not a bar's height below it.
            Modifier.fillMaxSize().padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            // D-224 fix wave (F8, iOS is the scale): iOS's `Spacer(minLength: 40)` over the check and `Spacer()` over the
            // button share the free height, so the block sits centred with at least 40 above it.
            Spacer(Modifier.heightIn(min = 40.dp).weight(1f))
            DoneCheck(DonePresentation.SCREEN, dark)
            Text(EraseCopy.DONE_TITLE, style = Type.display(dark).copy(fontSize = 28.sp), textAlign = TextAlign.Center)
            Text(EraseCopy.DONE_BODY, style = Type.bodyDim(dark).copy(fontSize = TypeScale.detail.sp), textAlign = TextAlign.Center)
            Spacer(Modifier.weight(1f))
            PrimaryAction(EraseCopy.DONE_OK, dark, onClick = onDone)
        }
    }
}

object OtherAccountCopy {
    const val TITLE = "This phone's ledger belongs to another account"
    const val BODY =
        "SKIPTI keeps one account's ledger on a phone. You were not signed in, and nothing was synced. " +
            "To use this account here, export your data if you want a copy, then erase this phone's " +
            "ledger and set SKIPTI up again."
}

object EraseCopy {
    const val TITLE = "Erase this phone"
    const val DETAIL =
        "Deletes everything SKIPTI keeps on this phone: its groups and expenses. " +
            "For selling or handing on a phone. Your account, if you have one, is not deleted."
    const val BUTTON = "Erase this phone's ledger"
    const val CONFIRM_TITLE = "Erase this phone's ledger?"
    const val CONFIRM_BODY = "This cannot be undone. Export first if you want a copy."
    const val CONFIRM = "Erase"
    // D-184: said once the erase is done (a tester: "no confirmation at all").
    const val DONE_TITLE = "This phone's ledger is erased"
    const val DONE_BODY =
        "Everything SKIPTI kept on this phone is gone. Your account, if you have one, is not deleted."
    const val DONE_OK = "OK"
    const val CANCEL = "Cancel"
    const val FAILED = "The ledger could not be erased."
}

/** The app's erases (Settings' deliberate one and the recovery screen's confirmed one, the same operation since WORKLOG
 *  decision 27: [signOutAndErase] signs out of the cloud session, then takes the vault's files, then its key). A new key and a
 *  new vault are made at setup; the key goes too, so a vault file restored
 *  later could not open. D-225 fix wave (A9, the controller's order): the
 *  write-ahead files first and the database last, so a refusal never leaves a
 *  write-ahead file without its database; the key only after every file is
 *  gone (a refused file stops the erase with the key kept). iOS's erase now
 *  does the same (ae7f770): `LedgerErase.files` lists -wal, -shm, -journal and
 *  then the database, and the keychain item goes after them, in both of its
 *  erases (AccountOwnerTest reads the file list). */
object LedgerErase {
    /** Bumped after an erase; AppRoot goes back to setup when it changes. */
    var generation by mutableIntStateOf(0)
        private set

    /** The vault's files in the order they go: the write-ahead files, then the database. */
    val ORDER = listOf("-wal", "-shm", "-journal", "")

    fun erase(vaultPath: String, keystore: SkiptiKeystore, remove: (File) -> Boolean = { it.delete() }) {
        for (suffix in ORDER) {
            val file = File(vaultPath + suffix)
            if (file.exists() && !remove(file)) error("could not delete ${file.name}")
        }
        keystore.destroy()
    }

    /** Both of the app's erases, the deliberate one and the recovery screen's confirmed one (D-225 follow-up, WORKLOG
     *  decision 27): sign out of the cloud session first (D-192: of the Firebase account), then [erase]. The account's
     *  owner key lives in the vault, so a session left behind would point at a ledger that no longer exists; the person
     *  can sign in again afterwards. A sign-out that throws erases nothing. Signing out touches no vault: the push stop
     *  it starts reads nothing from the ledger (`inCloud = false`). iOS's twin signs out the same way. */
    fun signOutAndErase(
        vaultPath: String,
        keystore: SkiptiKeystore,
        signOut: () -> Unit = { Account.signOut() },
        remove: (File) -> Boolean = { it.delete() },
    ) {
        signOut()
        erase(vaultPath, keystore, remove)
    }

    /** The app's own erase (Settings, and the other-account screen): [signOutAndErase] with this phone's keystore. */
    fun eraseThisPhone(context: android.content.Context, vaultPath: String) {
        signOutAndErase(vaultPath, AndroidKeystore(context.applicationContext))
    }

    /** D-184: true from a finished erase until the person dismisses the
     *  notice that says so. */
    var notice by mutableStateOf(false)

    fun announce() {
        notice = true
        generation++
    }
}

/** The erase button with its confirmation, used by Settings and by the
 *  other-account screen. */
@Composable
fun EraseLedgerButton(vaultPath: String, dark: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirming by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    DangerAction(EraseCopy.BUTTON, dark) { confirming = true }
    if (failed) ErrorLine(EraseCopy.FAILED, dark, detail = true)
    // D-224 (Design 3): the erase cannot be undone; its question warns as it opens.
    HapticWarning(confirming)
    if (confirming) {
        SkiptiAlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(EraseCopy.CONFIRM_TITLE) },
            text = { Text(EraseCopy.CONFIRM_BODY) },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) {
                            runCatching { LedgerErase.eraseThisPhone(context, vaultPath) }.isSuccess
                        }
                        if (ok) LedgerErase.announce() else failed = true
                    }
                }) { Text(EraseCopy.CONFIRM, color = Tok.owed(dark)) }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text(EraseCopy.CANCEL) } },
        )
    }
}

/** D-225 (Design 4): Security → Erase this phone's ledger: the page says what goes, then the erase button with its
 *  confirmation and its warning haptic (D-224), unchanged; the bar names the page, so the card draws no title of its
 *  own (D-217). Never automatic (D-127). The twin of iOS's `EraseScreen`. */
@Composable
fun EraseScreen(vaultPath: String, onBack: () -> Unit) {
    val dark = isSystemInDarkTheme()
    PushedPage(EraseCopy.TITLE, dark, onBack, side = BarDecisions.PAGE_SIDE_WIDE.dp) {
        Column(Modifier.glassCard(dark)) {
            Text(EraseCopy.DETAIL, style = Type.bodyDim(dark).copy(fontSize = TypeScale.detail.sp))
            Spacer(Modifier.height(8.dp))
            EraseLedgerButton(vaultPath, dark)
        }
    }
}

/** What a refused sign-in shows. */
@Composable
fun OtherAccountScreen(vaultPath: String, onBack: () -> Unit, onOpenExport: () -> Unit) {
    val dark = isSystemInDarkTheme()
    PushedPage("Sign in", dark, onBack, spacing = 12.dp) {
        Text(OtherAccountCopy.TITLE, style = Type.body(dark).copy(color = Tok.owed(dark)))
        Text(OtherAccountCopy.BODY, style = Type.bodyDim(dark).copy(fontSize = TypeScale.detail.sp))
        SecondaryAction("Export your data", dark, onClick = onOpenExport)
        EraseLedgerButton(vaultPath, dark)
    }
}

/** D-225 T7 (A7, the founder 2026-10-17; D-127): the twin of iOS's `VaultRecoveryView`'s words, in its order. The
 *  explanation lost its dash (iOS's paragraph is de-dashed the same way in Task 6, so the two read alike). */
object VaultRecoveryCopy {
    const val KICKER = "Ledger unavailable"
    const val TITLE = "This device cannot open your ledger."
    // D-225 fix wave (I1, A9): iOS's paragraph word for word: try again first, starting fresh last.
    const val EXPLAIN =
        "Your entries are encrypted with a key held by this device. Without that key nobody can read them: not us, " +
            "not anyone with the file. That is the guarantee working. Sometimes the key is only out of reach for a " +
            "moment, after a restart or a restore, so try again first. Starting fresh erases this ledger for good: keep " +
            "it for last."
    const val TRY_AGAIN = "Try again"
    const val RETRY_FAILED = "Tried again just now. It still will not open."
    const val START_FRESH = "Start fresh"
    const val WARNING =
        "Starting fresh erases the ledger on this device permanently. If you have it on another device, sign in there instead."
    const val ERASE = "Erase and start fresh"
    const val CANCEL = "Cancel"
    const val WRONG_KEY = "The key that opens this ledger is not on this device."

    /** The honest name for why the vault would not open, iOS's `describe`: a wrong key is the one people meet (the
     *  database survived and the key did not: a restored backup, a cleared keystore). */
    fun reason(e: Throwable): String = when (e) {
        is uniffi.skipti_core.StoreException.WrongKey -> WRONG_KEY
        is uniffi.skipti_core.StoreException.NewerSchema ->
            "This ledger was written by a newer version (schema ${e.found}, this build reads ${e.latest})."
        is uniffi.skipti_core.StoreException.Keystore -> "The device keychain refused: ${e.v1}"
        else -> e.message?.takeIf { it.isNotBlank() } ?: e.toString()
    }
}

/** D-225 T7 (A7; D-127): the launch's one read of the vault, and what follows from it. A vault that will not open is
 *  never "a first launch": it used to be swallowed (`getOrDefault(true)`), so the app went on as if nothing were wrong
 *  over a ledger it could not read. D-225 fix wave (I1, A9, the twin of iOS's `VaultRecovery`): Try again reads it
 *  again ([retried]); Start fresh's confirmed erase ([erase]) is the only destructive step, and never swallowed. */
object VaultRecovery {
    sealed interface Launch {
        /** The vault opened; [launchedBefore] is D-175's "this phone has opened SKIPTI before". */
        data class Opened(val launchedBefore: Boolean) : Launch

        /** The vault exists and will not open: the recovery screen, with [reason]. */
        data class Unavailable(val reason: String) : Launch
    }

    /** The line the screen adds after an attempt failed (iOS's `VaultRecovery.Note`). */
    enum class Note(val text: String) {
        RETRY_FAILED(VaultRecoveryCopy.RETRY_FAILED),
        ERASE_FAILED(EraseCopy.FAILED),
    }

    /** The screen as it stands: why, and what the last attempt left. */
    data class Screen(val reason: String, val note: Note? = null)

    /** Reads D-175's flag through [open] (and sets it on a first launch); a thrown Exception is [Launch.Unavailable].
     *  An Error (out of memory, a missing native library) is not a ledger that will not open, so it is not caught. */
    fun launch(key: String, open: () -> LedgerStore): Launch = try {
        val store = open()
        val before = store.rawKvGet(key)?.isNotEmpty() == true
        if (!before) store.rawKvSet(key, byteArrayOf(1))
        Launch.Opened(before)
    } catch (e: Exception) {
        Launch.Unavailable(VaultRecoveryCopy.reason(e))
    }

    /** After "Try again": null when the ledger opened (the shell goes on as a normal launch), else the screen with the
     *  reason read now and the retry line. */
    fun retried(launch: Launch): Screen? = when (launch) {
        is Launch.Opened -> null
        is Launch.Unavailable -> Screen(launch.reason, Note.RETRY_FAILED)
    }

    /** The ONLY destructive step, from the confirm step alone: [remove] signs out and takes the vault
     *  ([LedgerErase.signOutAndErase], the deliberate erase's operation: WORKLOG decision 27). null when
     *  it is done (the launch runs again); otherwise the screen stays with its [reason] and `EraseCopy.FAILED`. */
    fun erase(reason: String, remove: () -> Unit): Screen? = try {
        remove()
        null
    } catch (e: Exception) {
        Screen(reason, Note.ERASE_FAILED)
    }

    /** The screen's two steps: Start fresh only asks; the erase is offered only while asking. */
    enum class Step { EXPLAIN, CONFIRM }

    /** What the person can press at [step], in order (iOS's buttons): Try again first (primary), Start fresh last. */
    fun actions(step: Step): List<String> = when (step) {
        Step.EXPLAIN -> listOf(VaultRecoveryCopy.TRY_AGAIN, VaultRecoveryCopy.START_FRESH)
        Step.CONFIRM -> listOf(VaultRecoveryCopy.ERASE, VaultRecoveryCopy.CANCEL)
    }

    /** The note shown at [step]: the retry line is not shown under the erase warning (it is about trying again, which
     *  the confirm step does not offer); a failed erase is said in either step. */
    fun noteShown(step: Step, note: Note?): Note? =
        if (step == Step.CONFIRM && note == Note.RETRY_FAILED) null else note

    /** One attempt at a time: a tap while a retry or an erase runs does nothing (iOS's busy flag). */
    class Busy {
        var working by mutableStateOf(false)
            private set

        /** True when this tap starts the work; false while other work runs. */
        fun start(): Boolean {
            if (working) return false
            working = true
            return true
        }

        fun finish() {
            working = false
        }
    }
}

/** D-225 T7 (A7; D-127): the twin of iOS's `VaultRecoveryView`: the kicker in `owed` over a hairline, the title, the
 *  [reason], the explanation, then Try again (primary, [onRetry]) and Start fresh, which only asks: the warning in
 *  `owed`, "Erase and start fresh" (the primary in `owed`, the only call of [onErase]) and Cancel. The [note] the last
 *  attempt left sits over the buttons ([VaultRecovery.noteShown]). While [busy] every button is dimmed and inert. 20
 *  apart, padded 20, on the plain ground, scrolling. The warning haptic plays as the question opens (D-224). It never
 *  erases on its own. D-225 fix wave (I1, A9). */
@Composable
fun VaultRecoveryScreen(reason: String, note: VaultRecovery.Note?, busy: Boolean, onRetry: () -> Unit, onErase: () -> Unit) {
    val dark = isSystemInDarkTheme()
    var step by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(VaultRecovery.Step.EXPLAIN) }
    HapticWarning(step == VaultRecovery.Step.CONFIRM)
    Box(Modifier.fillMaxSize().background(Tok.bg(dark))) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(Type.kicker(VaultRecoveryCopy.KICKER), style = Type.microLabel(dark).copy(color = Tok.owed(dark)))
            Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hairline(dark)))
            Text(VaultRecoveryCopy.TITLE, style = Type.display(dark).copy(fontSize = TypeScale.lockTitle.sp))
            Text(reason, style = Type.bodyDim(dark))
            Text(VaultRecoveryCopy.EXPLAIN, style = Type.body(dark))
            if (step == VaultRecovery.Step.CONFIRM) {
                Text(VaultRecoveryCopy.WARNING, style = Type.body(dark).copy(color = Tok.owed(dark)))
            }
            // What the last attempt left: a retry that still could not open it (calm), or an erase that failed.
            VaultRecovery.noteShown(step, note)?.let { shown ->
                Text(shown.text, style = Type.body(dark).copy(color = if (shown == VaultRecovery.Note.ERASE_FAILED) Tok.owed(dark) else Tok.textDim(dark)))
            }
            if (step == VaultRecovery.Step.CONFIRM) {
                PrimaryAction(VaultRecoveryCopy.ERASE, dark, enabled = !busy, tint = Tok.owed(dark)) { onErase() }
                SecondaryAction(VaultRecoveryCopy.CANCEL, dark, enabled = !busy) { step = VaultRecovery.Step.EXPLAIN }
            } else {
                // A9: trying again is the first thing offered; starting fresh is the last resort and only asks.
                PrimaryAction(VaultRecoveryCopy.TRY_AGAIN, dark, enabled = !busy) { onRetry() }
                SecondaryAction(VaultRecoveryCopy.START_FRESH, dark, enabled = !busy) { step = VaultRecovery.Step.CONFIRM }
            }
        }
    }
}
