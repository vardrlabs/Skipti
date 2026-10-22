package com.vardrlabs.skipti

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * D-198 (the Firebase plan's unit 8): your phones and deleting your account.
 * SPEC §3.5: the phone list is a screen of its own. SPEC §4.4: one deletion
 * screen, two taps from Settings, export first, a 7-day grace in which
 * signing in and keeping the account cancels it, then irreversible. Firebase
 * cannot end another phone's session by itself, so a phone signed out from
 * elsewhere signs itself out when it next reaches the cloud; "Sign out
 * everywhere" also revokes every session. iOS's twin is Phones.swift.
 */
object PhonesCopy {
    const val TITLE = "Your phones"
    const val THIS_PHONE = "This phone"
    const val SIGNED_OUT = "Signed out"
    fun lastSeen(whenText: String) = "Last seen $whenText"
    /** D-222 (L58): You's phone count. */
    fun count(n: Int): String = if (n == 1) "1 phone" else "$n phones"
    const val SIGN_IT_OUT = "Sign it out"
    fun confirmTitle(name: String) = "Sign out $name?"
    const val CONFIRM_BODY = "It stops syncing and signs itself out the next time it goes online. Its ledger stays on it."
    const val EVERYWHERE = "Sign out everywhere"
    const val EVERYWHERE_TITLE = "Sign out every phone?"
    const val EVERYWHERE_BODY =
        "Every phone on your account, this one too, is signed out. Each keeps its ledger. Use this if a phone is lost or stolen."
    const val LIMIT = "A phone you sign out stops syncing the next time it goes online. If a phone is lost, sign out everywhere."
    const val EMPTY = "No phones yet."
    /** D-225 follow-up (R2): a listen that failed, in the cloud surfaces' words ("Could not load groups.", the invite's
     *  "Could not reach SKIPTI's cloud. Check your connection and try again.") with their "Try again". */
    const val LOAD_FAILED = "Could not load your phones. Check your connection and try again."
    const val TRY_AGAIN = "Try again"
    const val REMOTE_TITLE = "Signed out"
    const val REMOTE_BODY = "This phone was signed out of your account from another phone. Your ledger stays here."
    const val CANCEL = "Cancel"
    const val OK = "OK"

    const val DELETE_TITLE = "Delete account"
    const val DELETE_INTRO =
        "Deleting your account removes it from SKIPTI's cloud: your profile, username, phones and personal records. You leave every group; what you added to a group stays for its other members. Groups where you are the only member are deleted."
    const val DELETE_STAYS = "Your ledger stays on this phone."
    const val DELETE_GRACE =
        "Deletion happens 7 days after you ask. To keep your account, sign in before then and tap Keep my account. After that it cannot be undone."
    const val EXPORT_FIRST = "Export your data first"
    const val DELETE_BUTTON = "Delete my account"
    const val DELETE_CONFIRM_TITLE = "Delete your account?"
    const val DELETE_CONFIRM_BODY = "Every phone is signed out now, and your account is deleted in 7 days."
    const val DELETE_DONE_TITLE = "Account deletion requested"
    fun deleteDone(date: String) = "Your account will be deleted on $date. To keep it, sign in before then and tap Keep my account."
    fun pending(date: String) = "Your account will be deleted on $date."
    const val KEEP = "Keep my account"
}

/** D-225 (Design 4): the Your phones row's summary on Security: the account's phones' names, this phone first, the
 *  phones signed out left out, joined with ", "; null when none is left (the row then shows no summary, never an empty
 *  one). It reads the rows the Your phones page already makes. The twin of iOS's `PhonesSummary` (Phones.swift). */
object PhonesSummary {
    fun line(rows: List<Phones.Row>): String? {
        val active = rows.filterNot { it.signedOut }
        val names = (active.filter { it.isThis } + active.filterNot { it.isThis }).map { it.name }
        return if (names.isEmpty()) null else names.joinToString(", ")
    }
}

object Phones {
    /** When a pending deletion happens (ms), or null. D-254: where the marker is: the profile's `deleteAt` when there is
     *  a profile ([profile] non-null), else the approval document's (`requestDeletion` puts it there for an account with
     *  no profile: waiting, or not approved), so Keep my account is offered on the next sign-in within the 7 days
     *  instead of the purge deleting the account unwarned. The approval document is read only with no profile. Fix wave
     *  M8: iOS's `Phones.deletionDate(profile:approval:)`, the same rule and the same test inputs. */
    suspend fun deletionDate(
        profile: suspend () -> Map<String, Any?>?,
        approval: suspend () -> Map<String, Any?>?,
    ): Long? {
        profile()?.let { own -> return (own["deleteAt"] as? com.google.firebase.Timestamp)?.let(CloudRecords::millis) }
        return (approval()?.get("deleteAt") as? com.google.firebase.Timestamp)?.let(CloudRecords::millis)
    }

    data class Row(
        val id: String, val name: String, val platform: String, val lastSeenMs: Long?, val signedOut: Boolean, val isThis: Boolean,
    )

    /** D-222 (L58): the account's phones not marked signed out (the documents Your phones lists), for You's row. */
    fun activeCount(docs: List<Pair<String, Map<String, Any?>>>): Int = docs.count { (_, data) -> data["signedOut"] != true }

    /** D-222 (L58): You's phone count, read once per visit from the server (the collection Your phones lists); null
     *  without an account or when the read fails, so the row never shows "0 phones". Nothing is logged. */
    suspend fun activeCount(): Int? {
        val uid = Account.uid() ?: return null
        return runCatching {
            val snapshot = Cloud.db().collection("users").document(uid).collection("devices").get(Source.SERVER).awaitTask()
            activeCount(snapshot.documents.map { it.id to (it.data ?: emptyMap()) })
        }.getOrNull()
    }

    /** D-225 (Design 4): Security's Your phones summary ([PhonesSummary.line] over the rows Your phones lists), read once
     *  per visit from the server; null without an account or when the read fails, so the row then shows no summary. */
    suspend fun summary(vaultPath: String): String? {
        val uid = Account.uid() ?: return null
        return runCatching {
            val thisDevice = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { CloudProfile.deviceId(LedgerStore(vaultPath)) }
            val snapshot = Cloud.db().collection("users").document(uid).collection("devices").get(Source.SERVER).awaitTask()
            PhonesSummary.line(rows(snapshot.documents.map { it.id to (it.data ?: emptyMap()) }, thisDevice))
        }.getOrNull()
    }

    fun platformWord(platform: String) = when (platform) {
        "ios" -> "iPhone"
        "android" -> "Android"
        else -> ""
    }

    /** The account's phones as the screen lists them: this phone first, then
     *  the most recently seen. */
    /** D-225 follow-up (R2, the re-look): what Your phones shows from its listener's latest event. It is never silently
     *  empty: before the first event it waits, a listen that fails says so with Try again, and a listen that works
     *  lists the rows (or "No phones yet."). The twin of iOS's Your phones. */
    sealed interface Listing {
        data object Waiting : Listing
        data object Failed : Listing
        data class Listed(val rows: List<Row>) : Listing
    }

    /** An event's documents, or null when the listen failed (Firestore then ends the listener: Try again starts a new
     *  one). */
    fun listing(docs: List<Pair<String, Map<String, Any?>>>?, thisDevice: String): Listing =
        if (docs == null) Listing.Failed else Listing.Listed(rows(docs, thisDevice))

    fun rows(docs: List<Pair<String, Map<String, Any?>>>, thisDevice: String): List<Row> =
        docs.map { (id, data) ->
            val platform = data["platform"] as? String ?: ""
            Row(
                id = id,
                name = (data["name"] as? String)?.takeIf { it.isNotEmpty() } ?: platformWord(platform),
                platform = platform,
                lastSeenMs = (data["lastSeen"] as? Timestamp)?.let(CloudRecords::millis),
                signedOut = data["signedOut"] == true,
                isThis = id == thisDevice,
            )
        }.sortedWith(compareByDescending<Row> { it.isThis }.thenByDescending { it.lastSeenMs ?: 0L }.thenByDescending { it.id })

    /** The line under a phone's name. */
    fun status(row: Row, relative: (Long) -> String): String = when {
        row.isThis -> PhonesCopy.THIS_PHONE
        row.signedOut -> PhonesCopy.SIGNED_OUT
        else -> row.lastSeenMs?.let { PhonesCopy.lastSeen(relative(it)) } ?: platformWord(row.platform)
    }

    /** Whether this phone's own device document says it was signed out AFTER it
     *  last signed in (an older flag is from before: it signed in again since,
     *  and its entry is made fresh). */
    fun signedOutSince(data: Map<String, Any?>?, authTimeMs: Long): Boolean {
        if (data?.get("signedOut") != true) return false
        val at = data["signedOutAt"] as? Timestamp ?: return true
        return CloudRecords.millis(at) >= authTimeMs
    }

    suspend fun authTimeMs(): Long = runCatching {
        (FirebaseAuth.getInstance().currentUser?.getIdToken(false)?.awaitTask()?.authTimestamp ?: 0L) * 1000
    }.getOrDefault(0L)

    suspend fun signOutDevice(id: String) {
        CloudFunctions.call("signOutDevice", mapOf("deviceId" to id))
    }

    suspend fun signOutEverywhere() {
        CloudFunctions.callJson("signOutEverywhere", JsonObject(emptyMap()))
    }

    /** Returns when the deletion happens, in milliseconds. */
    suspend fun requestDeletion(): Long {
        val result = CloudFunctions.callJson("requestDeletion", JsonObject(emptyMap()))
        return (result["deleteAt"] as? JsonPrimitive)?.longOrNull ?: (System.currentTimeMillis() + 7L * 86_400_000)
    }

    suspend fun cancelDeletion() {
        CloudFunctions.callJson("cancelDeletion", JsonObject(emptyMap()))
    }

    fun day(ms: Long): String = java.text.DateFormat.getDateInstance(java.text.DateFormat.LONG).format(java.util.Date(ms))
}

/** A phone signed out from another phone (or by Sign out everywhere there):
 *  it signs itself out here, keeps its ledger, and says so. */
object RemoteSignOut {
    /** D-191's rule: Compose state made on the main thread before any frame. */
    fun createState() = Unit

    var notice by mutableStateOf(false)
    /** D-198: after a deletion request, when it happens (ms). */
    var deletionDateMs by mutableStateOf<Long?>(null)
    /** D-198: this phone's own Sign out everywhere or deletion request is
     *  running. The cloud flags this phone before the call returns, so its
     *  listener fires; that sign-out is not "from another phone". */
    @Volatile var ownRequest = false
        private set
    /** D-198: counts this phone's sign-outs through here, so screens that
     *  are open when one happens can refresh or leave. */
    var signOuts by mutableIntStateOf(0)
        private set

    suspend fun happened(vaultPath: String) = arrived(Account.isSignedIn()) {
        withContext(Dispatchers.IO) { runCatching { Push.stop(LedgerStore(vaultPath), inCloud = false) } }
        withContext(Dispatchers.IO) { Account.signOut() }
    }

    /** The decision, testable without an account. */
    suspend fun arrived(signedIn: Boolean, signOut: suspend () -> Unit) {
        if (!signedIn) return
        signOut()
        signOuts++
        notice = !ownRequest
    }

    /** Runs this phone's own sign-out call, including the local sign-out
     *  that follows it, so the listener stays quiet throughout. */
    suspend fun <T> quietly(call: suspend () -> T): T {
        ownRequest = true
        try {
            return call()
        } finally {
            ownRequest = false
        }
    }
}

// ---- Screens ----

/** SPEC §3.5's first-class list of the account's phones. */
@Composable
fun PhonesScreen(vaultPath: String, onBack: () -> Unit) {
    val dark = isSystemInDarkTheme()
    val scope = rememberCoroutineScope()
    var listing by remember { mutableStateOf<Phones.Listing>(Phones.Listing.Waiting) }
    var attempt by remember { mutableIntStateOf(0) }
    var confirming by remember { mutableStateOf<Phones.Row?>(null) }
    var everywhere by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // Signed out (here, or from another phone): nothing left to show.
    LaunchedEffect(RemoteSignOut.signOuts) { if (RemoteSignOut.signOuts > 0 && !Account.isSignedIn()) onBack() }
    // D-225 follow-up (R2): each event goes through [Phones.listing]; a failed listen (no snapshot) is said, never
    // dropped, and Try again listens again (the effect is keyed by the attempt).
    DisposableEffect(vaultPath, attempt) {
        listing = Phones.Listing.Waiting
        val uid = Account.uid()
        val registration = if (uid == null) null else {
            val thisDevice = runCatching { CloudProfile.deviceId(LedgerStore(vaultPath)) }.getOrDefault("")
            Cloud.db().collection("users").document(uid).collection("devices").addSnapshotListener { snapshot, _ ->
                listing = Phones.listing(snapshot?.documents?.map { it.id to (it.data ?: emptyMap()) }, thisDevice)
            }
        }
        onDispose { registration?.remove() }
    }
    PushedPage(PhonesCopy.TITLE, dark, onBack, spacing = 12.dp) {
        when (val shown = listing) {
            Phones.Listing.Waiting -> Unit
            Phones.Listing.Failed -> {
                ErrorLine(PhonesCopy.LOAD_FAILED, dark)
                SecondaryAction(PhonesCopy.TRY_AGAIN, dark) { attempt++ }
            }
            is Phones.Listing.Listed -> if (shown.rows.isEmpty()) {
                Text(PhonesCopy.EMPTY, style = Type.bodyDim(dark), modifier = Modifier.glassCard(dark))
            } else {
                // D-234 wave (decision 102): iOS pads this card 14.
                Column(Modifier.glassCard(dark, padding = 14)) {
                    shown.rows.forEachIndexed { i, row ->
                        if (i > 0) HorizontalDivider(color = Tok.hairline(dark))
                        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(row.name, style = Type.label(dark, 15))
                                Text(
                                    Phones.status(row) { ms ->
                                        android.text.format.DateUtils.getRelativeTimeSpanString(
                                            ms, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS,
                                        ).toString()
                                    },
                                    style = Type.bodyDim(dark),
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            if (!row.isThis && !row.signedOut) {
                                TextButton(onClick = { confirming = row }) {
                                    Text(PhonesCopy.SIGN_IT_OUT, style = Type.body(dark).copy(color = Tok.accent(dark)))
                                }
                            }
                        }
                    }
                }
            }
        }
        Text(PhonesCopy.LIMIT, style = Type.bodyDim(dark))
        error?.let { ErrorLine(it, dark) }
        SecondaryAction(PhonesCopy.EVERYWHERE, dark) { everywhere = true }
    }
    // D-224 (Design 3): signing a phone out, or every phone, asks first; each question warns as it opens.
    HapticWarning(confirming != null)
    HapticWarning(everywhere)
    confirming?.let { row ->
        SkiptiAlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text(PhonesCopy.confirmTitle(row.name)) },
            text = { Text(PhonesCopy.CONFIRM_BODY) },
            confirmButton = {
                TextButton(onClick = {
                    confirming = null
                    scope.launch {
                        error = runCatching { Phones.signOutDevice(row.id) }.exceptionOrNull()?.let(InviteCopy::failure)
                    }
                }) { Text(PhonesCopy.SIGN_IT_OUT) }
            },
            dismissButton = { TextButton(onClick = { confirming = null }) { Text(PhonesCopy.CANCEL) } },
        )
    }
    if (everywhere) {
        SkiptiAlertDialog(
            onDismissRequest = { everywhere = false },
            title = { Text(PhonesCopy.EVERYWHERE_TITLE) },
            text = { Text(PhonesCopy.EVERYWHERE_BODY) },
            confirmButton = {
                TextButton(onClick = {
                    everywhere = false
                    scope.launch {
                        runCatching {
                            RemoteSignOut.quietly {
                                Phones.signOutEverywhere()
                                RemoteSignOut.happened(vaultPath)
                            }
                        }
                            .onSuccess { onBack() }
                            .onFailure { error = InviteCopy.failure(it) }
                    }
                }) { Text(PhonesCopy.EVERYWHERE) }
            },
            dismissButton = { TextButton(onClick = { everywhere = false }) { Text(PhonesCopy.CANCEL) } },
        )
    }
}

/** SPEC §4.4: one screen, two taps from Settings. */
@Composable
fun DeleteAccountScreen(vaultPath: String, onExport: () -> Unit, onDone: () -> Unit, onBack: () -> Unit) {
    val dark = isSystemInDarkTheme()
    val scope = rememberCoroutineScope()
    var confirming by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(RemoteSignOut.signOuts) { if (RemoteSignOut.signOuts > 0 && !Account.isSignedIn()) onBack() }
    PushedPage(PhonesCopy.DELETE_TITLE, dark, onBack, spacing = 12.dp) {
        Column(Modifier.glassCard(dark)) {
            Text(PhonesCopy.DELETE_INTRO, style = Type.body(dark))
            Spacer(Modifier.height(10.dp))
            Text(PhonesCopy.DELETE_STAYS, style = Type.body(dark))
            Spacer(Modifier.height(10.dp))
            Text(PhonesCopy.DELETE_GRACE, style = Type.bodyDim(dark))
        }
        SecondaryAction(PhonesCopy.EXPORT_FIRST, dark, onExport)
        error?.let { ErrorLine(it, dark) }
        DangerAction(PhonesCopy.DELETE_BUTTON, dark) { if (!busy) confirming = true }
    }
    // D-224 (Design 3): the account's deletion asks first; the question warns as it opens.
    HapticWarning(confirming)
    if (confirming) {
        SkiptiAlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(PhonesCopy.DELETE_CONFIRM_TITLE) },
            text = { Text(PhonesCopy.DELETE_CONFIRM_BODY) },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    busy = true
                    scope.launch {
                        runCatching {
                            RemoteSignOut.quietly {
                                val at = Phones.requestDeletion()
                                RemoteSignOut.happened(vaultPath)
                                at
                            }
                        }
                            .onSuccess { at ->
                                RemoteSignOut.deletionDateMs = at
                                onDone()
                            }
                            .onFailure { error = InviteCopy.failure(it) }
                        busy = false
                    }
                }) { Text(PhonesCopy.DELETE_BUTTON, color = Tok.owed(dark)) }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text(PhonesCopy.CANCEL) } },
        )
    }
}

/** Settings, while a deletion is pending: the date, and keeping the account. */
@Composable
fun DeletionPendingCard(dark: Boolean, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    var dateMs by remember { mutableStateOf<Long?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        val uid = Account.uid() ?: return@LaunchedEffect
        dateMs = runCatching {
            Phones.deletionDate(
                profile = { Cloud.db().collection("users").document(uid).get(Source.SERVER).awaitTask().let { if (it.exists()) it.data.orEmpty() else null } },
                approval = { Cloud.db().collection("approvals").document(uid).get(Source.SERVER).awaitTask().data },
            )
        }.getOrNull()
    }
    val at = dateMs ?: return
    Column(modifier.glassCard(dark)) {
        Text(PhonesCopy.pending(Phones.day(at)), style = Type.body(dark).copy(color = Tok.owed(dark)))
        Spacer(Modifier.height(10.dp))
        PrimaryAction(PhonesCopy.KEEP, dark) {
            scope.launch {
                runCatching { Phones.cancelDeletion() }
                    .onSuccess { dateMs = null }
                    .onFailure { error = InviteCopy.failure(it) }
            }
        }
        error?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, style = Type.body(dark).copy(color = Tok.owed(dark)))
        }
    }
}

/** The notices the app shows over everything (AppRoot). */
@Composable
fun RemoteSignOutNotices() {
    if (RemoteSignOut.notice) {
        SkiptiAlertDialog(
            onDismissRequest = { RemoteSignOut.notice = false },
            title = { Text(PhonesCopy.REMOTE_TITLE) },
            text = { Text(PhonesCopy.REMOTE_BODY) },
            confirmButton = { TextButton(onClick = { RemoteSignOut.notice = false }) { Text(PhonesCopy.OK) } },
        )
    }
    RemoteSignOut.deletionDateMs?.let { ms ->
        SkiptiAlertDialog(
            onDismissRequest = { RemoteSignOut.deletionDateMs = null },
            title = { Text(PhonesCopy.DELETE_DONE_TITLE) },
            text = { Text(PhonesCopy.deleteDone(Phones.day(ms))) },
            confirmButton = { TextButton(onClick = { RemoteSignOut.deletionDateMs = null }) { Text(PhonesCopy.OK) } },
        )
    }
}
