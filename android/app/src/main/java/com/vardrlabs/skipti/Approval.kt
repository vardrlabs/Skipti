package com.vardrlabs.skipti

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * D-254 (decisions 228, 229): the approval gate, on the phone. Anyone can sign in; until the founder approves the
 * account, the cloud does nothing for it, and the ledger on this phone works exactly as it does with no account
 * (GUARANTEES #5: the app itself is never gated). The server is the control (the rules refuse every client write of an
 * unapproved account, and every Cloud Function but the ones that let a person leave refuses its calls); this phone's
 * gate is the courtesy: no write that would fail, and honest words. iOS's twin is Approval.swift.
 *
 * What the phone knows of its own account: `approvals/{uid}.state`, read through one listener while signed in and in
 * front, and through `requestApproval`'s answer. The last state heard is kept in the vault as a LOCAL-ONLY value
 * (`account/approval`, a raw kv value, never a setting record that syncs), so a launch offline shows it without a flash;
 * it is cleared by every sign-out (sign-out everywhere, a deletion and a remote sign-out all sign out through
 * [Account.signOut]; an erase deletes the vault).
 */

/** The account's approval as this phone knows it. [UNKNOWN]: nothing heard yet for this account (a fresh sign-in, or an
 *  install from before the gate). The cloud runs only on [APPROVED]: an unknown state is closed, and the screens show it
 *  as they always have (the first answer comes within a second of a launch online). */
enum class ApprovalState(val stored: String) {
    UNKNOWN("unknown"), PENDING("pending"), APPROVED("approved"), DENIED("denied");

    companion object {
        /** The server's word: `approvals/{uid}.state`, requestApproval's `{ state }`, or a refusal's
         *  `details.approval`. No word (no document) and `none` (a document holding only a deletion marker) are pending,
         *  as the server's own gate reads them; anything else unknown is pending too (closed, never approved). */
        fun server(word: String?): ApprovalState = when (word) {
            "approved" -> APPROVED
            "denied" -> DENIED
            else -> PENDING
        }

        /** The vault's word for a state, or null. */
        fun parse(word: String): ApprovalState? = entries.firstOrNull { it.stored == word }
    }
}

/** D-254: every word the gate shows. One table, the twin of iOS's `ApprovalCopy` (Approval.swift), which
 *  ApprovalTest reads line by line. The founder approved them (decision 233, the mock-up); the two `CALL_` lines are
 *  the server's own refusal words (the design's section 5). */
object ApprovalCopy {
    /** You, the Account row's summary while waiting, and the invite page's title. */
    const val ACCOUNT_WAITING = "Waiting for approval"
    /** You, the Account row's summary when denied, and the invite page's title then. */
    const val ACCOUNT_DENIED = "Not approved"
    /** The Sync card's value while waiting. */
    const val SYNC_WAITING_VALUE = "Waiting"
    const val SYNC_WAITING_BODY = "SKIPTI is in early testing, so new accounts are approved by hand. Your ledger on this phone works as usual. Sync and shared groups start once your account is approved."
    /** The Sync card's value when denied. */
    const val SYNC_DENIED_VALUE = "Off"
    const val SYNC_DENIED_BODY = "This account was not approved for sync. Your ledger on this phone works as usual and stays on this phone."
    /** Opening an invite while waiting; `{group}` is the group's name, or [THIS_GROUP] when it is not known. */
    const val JOIN_WAITING_BODY = "You can join {group} once your account is approved. The link works for 7 days, so open it again then."
    const val THIS_GROUP = "this group"
    const val JOIN_DENIED_BODY = "This account was not approved, so it cannot join groups."
    /** In place of a group's invite rows, Find friends in contacts and Add by @username. */
    const val INVITE_WAITING = "Inviting people starts once your account is approved."
    const val INVITE_DENIED = "This account was not approved, so it cannot invite people."
    /** The toast, once, when approval arrives after the phone showed it waiting (or denied). */
    const val APPROVED_TOAST = "Your account is approved. Syncing now."
    /** A call refused for approval (on this phone, or by the server's `details.approval`). */
    const val CALL_WAITING = "This account is waiting for approval."
    const val CALL_DENIED = "This account was not approved."
    const val DONE = "Done"

    fun joinWaiting(group: String?): String {
        val name = group?.trim().orEmpty()
        return JOIN_WAITING_BODY.replace("{group}", name.ifEmpty { THIS_GROUP })
    }

    /** The Account row's summary for [state], or null: as before the gate. */
    fun accountSummary(state: ApprovalState): String? = when (state) {
        ApprovalState.PENDING -> ACCOUNT_WAITING
        ApprovalState.DENIED -> ACCOUNT_DENIED
        ApprovalState.UNKNOWN, ApprovalState.APPROVED -> null
    }

    /** The one line in place of inviting, or null: inviting as before. */
    fun invite(state: ApprovalState): String? = when (state) {
        ApprovalState.PENDING -> INVITE_WAITING
        ApprovalState.DENIED -> INVITE_DENIED
        ApprovalState.UNKNOWN, ApprovalState.APPROVED -> null
    }

    /** The invite page's words while the account cannot join (title and body), or null: open the invite. */
    fun join(state: ApprovalState, group: String?): Pair<String, String>? = when (state) {
        ApprovalState.PENDING -> ACCOUNT_WAITING to joinWaiting(group)
        ApprovalState.DENIED -> ACCOUNT_DENIED to JOIN_DENIED_BODY
        ApprovalState.UNKNOWN, ApprovalState.APPROVED -> null
    }

    /** A failed call's approval words (the waiting, or the not approved), or null for any other failure. Never the
     *  "not a member" words: a refusal for approval is not about the group. */
    fun failure(error: Throwable): String? = when (error) {
        is CloudFunctions.Failure.AwaitingApproval -> CALL_WAITING
        is CloudFunctions.Failure.NotApproved -> CALL_DENIED
        else -> null
    }

    /** The state a failed call says the account is in, or null when the failure is not about approval. */
    fun state(of: Throwable): ApprovalState? = when (of) {
        is CloudFunctions.Failure.AwaitingApproval -> ApprovalState.PENDING
        is CloudFunctions.Failure.NotApproved -> ApprovalState.DENIED
        else -> null
    }
}

/** D-254: what this phone does when it hears the account's state ([Approval.decide]). */
sealed class ApprovalStep {
    /** Nothing changed (or no answer). */
    data object Nothing : ApprovalStep()
    /** Keep the new state; the cloud was not running and still is not. */
    data class Keep(val state: ApprovalState) : ApprovalStep()
    /** Approved: keep it, show the toast when the phone had shown the account waiting or denied, start the cloud. */
    data class Approve(val toast: Boolean) : ApprovalStep()
    /** Approval taken away: keep the new state and stop the cloud as sync off does (local data untouched). */
    data class Withdraw(val state: ApprovalState) : ApprovalStep()
}

object Approval {
    /** D-191's rule: Compose state made on the main thread before any frame (MainActivity.onCreate). */
    fun createState() = Unit

    /** The signed-in account's state, for the screens. The gates read the vault's copy ([cached]). */
    var state by mutableStateOf(ApprovalState.UNKNOWN)
        private set

    @Volatile private var loadedFor: String? = null
    private var listener: ListenerRegistration? = null
    private var listeningAs: String? = null
    private val listenLock = Any()
    /** Fix wave M2: the one ask in flight (and its account); a second asker joins it. */
    private val askLock = Any()
    private var inFlight: Pair<String, Deferred<ApprovalState?>>? = null
    /** Fix wave M5: the account and the state of the last ask the server answered, this process. */
    @Volatile private var answered: Pair<String, ApprovalState>? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Fix wave M4: SKIPTI is in front ([CloudSync.appIsActive] sets it first). Approval heard while it is not is kept,
     *  and the next front event starts the cloud from the vault's copy. */
    @Volatile var inFront = false

    /** Tests only; production never reassigns. The account signed in now ([heard] acts only for it). */
    @Volatile var signedInAs: () -> String? = { runCatching { Account.uid() }.getOrNull() }
    /** Tests only; production never reassigns. Approval arrived in front: the cloud starts. */
    @Volatile var startCloud: () -> Unit = { CloudSync.appIsActive(true) }
    /** Tests only; production never reassigns. Approval taken away: the cloud stops. */
    @Volatile var stopCloud: () -> Unit = { CloudSync.approvalWithdrawn() }
    /** Tests only; production never reassigns. The network part of an ask: `requestApproval`'s reply. */
    @Volatile var request: suspend (JsonObject) -> JsonObject = { CloudFunctions.callJson("requestApproval", it) }

    // ---- The pure parts ----

    /** The calls an unapproved account may make (the server's `approvals.EXEMPT`): leaving never waits, and asking for
     *  approval is how it starts. Every other call is refused on this phone first ([CloudFunctions.callJson]). */
    val EXEMPT: Set<String> = setOf(
        "signOutDevice", "signOutEverywhere", "requestDeletion", "cancelDeletion", "requestApproval", "adminApprovals",
    )

    /** The cloud runs only for an approved account. */
    fun allowsCloud(approval: ApprovalState): Boolean = approval == ApprovalState.APPROVED

    /** The cloud gate's third condition: the signed-in account, only when it is the vault's owner ([CloudGate.owned])
     *  AND approved. */
    fun gate(uid: String?, owner: String?, approval: ApprovalState): String? =
        if (allowsCloud(approval)) CloudGate.owned(uid, owner) else null

    /** [CloudFunctions.callJson]'s gate: a call the server would refuse for approval is refused here, with no network.
     *  [approval] is the state after [callRefusal]'s ask: pending or denied have their words; fix wave I2: still unknown (no
     *  answer from the server) is the offline failure, never the waiting words (an approved tester whose first answer
     *  has not come yet is not waiting). */
    fun localRefusal(name: String, approval: ApprovalState): CloudFunctions.Failure? {
        if (name in EXEMPT || allowsCloud(approval)) return null
        return when (approval) {
            ApprovalState.DENIED -> CloudFunctions.Failure.NotApproved()
            ApprovalState.PENDING -> CloudFunctions.Failure.AwaitingApproval()
            ApprovalState.UNKNOWN, ApprovalState.APPROVED -> CloudFunctions.Failure.Offline()
        }
    }

    /** Fix wave I2 (iOS's `callRefusal`): a gated call's refusal, or null to go on. Exempt or approved: go, asking
     *  nothing; pending or denied: refused, asking nothing. Unknown (nothing heard yet for this account: a first launch
     *  after the update, a sign-in whose ask ran out of time) asks the server once, waiting at most [ms]: approved goes,
     *  pending and denied have their words, and no answer is offline ([localRefusal]). */
    suspend fun callRefusal(name: String, cached: ApprovalState, ms: Long, ask: suspend () -> ApprovalState?): CloudFunctions.Failure? {
        if (name in EXEMPT || cached != ApprovalState.UNKNOWN) return localRefusal(name, cached)
        return localRefusal(name, withTimeoutOrNull(ms) { ask() } ?: ApprovalState.UNKNOWN)
    }

    /** Fix wave I2: how long a gated call waits for the server's answer when nothing was heard yet. */
    const val RESOLVE_TIMEOUT_MS = 5_000L

    /** Fix wave M5 (iOS's `frontAsks`): whether a front event asks: not approved, and not already [answered] for this
     *  account in this state this process. A sign-in and a refusal carrying `details.approval` ask regardless. */
    fun frontAsks(answered: Pair<String, ApprovalState>?, uid: String, state: ApprovalState): Boolean =
        !allowsCloud(state) && answered != (uid to state)

    /** Fix wave M4 (iOS's `startsCloudNow`): the cloud starts on approval only while SKIPTI is in front. */
    fun startsCloudNow(step: ApprovalStep, inFront: Boolean): Boolean = step is ApprovalStep.Approve && inFront

    /** What hearing [server] does, given what the phone last knew ([cached]). The toast only follows a state the phone
     *  showed (waiting or denied): an account heard as approved for the first time (an install from before the gate, or
     *  a second phone of an approved account) starts its cloud without one. */
    fun decide(cached: ApprovalState, server: ApprovalState): ApprovalStep {
        if (server == ApprovalState.UNKNOWN || server == cached) return ApprovalStep.Nothing
        if (server == ApprovalState.APPROVED) {
            return ApprovalStep.Approve(toast = cached == ApprovalState.PENDING || cached == ApprovalState.DENIED)
        }
        if (cached == ApprovalState.APPROVED) return ApprovalStep.Withdraw(server)
        return ApprovalStep.Keep(server)
    }

    // ---- The vault's copy (local only) ----

    /** `<uid>:<state>`: the state belongs to that account only. */
    const val CACHE_KEY = "account/approval"

    fun encode(uid: String, state: ApprovalState): String = uid + ":" + state.stored

    /** The state the vault holds for [uid]; unknown for another account, nothing, or anything malformed. */
    fun decode(raw: String?, uid: String?): ApprovalState {
        if (raw == null || uid == null) return ApprovalState.UNKNOWN
        val cut = raw.lastIndexOf(':')
        if (cut < 0 || raw.substring(0, cut) != uid) return ApprovalState.UNKNOWN
        return ApprovalState.parse(raw.substring(cut + 1)) ?: ApprovalState.UNKNOWN
    }

    /** For the gates, which fail closed: a vault that cannot be read reads as unknown, so nothing syncs. */
    fun cached(store: LedgerStore, uid: String?): ApprovalState {
        if (uid == null) return ApprovalState.UNKNOWN
        val raw = runCatching { store.rawKvGet(CACHE_KEY) }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return ApprovalState.UNKNOWN
        return decode(raw.decodeToString(), uid)
    }

    fun setCached(store: LedgerStore, uid: String, state: ApprovalState) {
        runCatching { store.rawKvSet(CACHE_KEY, encode(uid, state).toByteArray(Charsets.UTF_8)) }
    }

    fun clearCached(store: LedgerStore) {
        runCatching { store.rawKvSet(CACHE_KEY, ByteArray(0)) }
    }

    // ---- The account's state, live ----

    private fun defaultStore(): LedgerStore? =
        runCatching { VaultGate.appContextRef }.getOrNull()?.let { LedgerStore(CloudSync.vaultPathOf(it)) }

    /** The last state the vault holds for [uid], for the first frame (a sign-in, a front event). */
    fun load(uid: String?, store: LedgerStore) {
        loadedFor = uid
        state = if (uid == null) ApprovalState.UNKNOWN else cached(store, uid)
    }

    /** Right after a sign-in kept its owner, before the cloud starts ([Account.signIn]): ask for approval (bounded: the
     *  sign-in never waits long on it) and listen for the decision. */
    suspend fun signedIn(uid: String, store: LedgerStore) {
        load(uid, store)
        // Fix wave M2: the ask runs in this object's scope and the sign-in waits for its answer at most this long (a
        // blocking HTTP call cannot be interrupted, so a timeout around the call itself waited for it).
        answerWithin(ASK_TIMEOUT_MS, uid, store)
        listen(uid, store)
    }

    const val ASK_TIMEOUT_MS = 10_000L

    /** Every return to the front ([CloudSync.appIsActive]): signed in to the vault's owner, the listener runs, and an
     *  account not approved asks again (its name may have changed; a lost request is made again). */
    fun front(uid: String?, store: LedgerStore) {
        // No session (it ended elsewhere: Android has no auth listener, iOS's twin forgets in its own): the vault forgets
        // the state, once.
        if (uid == null) {
            if (runCatching { store.rawKvGet(CACHE_KEY) }.getOrNull()?.isNotEmpty() == true) signedOut(store)
            return
        }
        val owned = CloudGate.owned(uid, Account.storedOwner(store)) ?: return
        if (loadedFor != owned) load(owned, store)
        listen(owned, store)
        // Fix wave M5: once per process while the state is unchanged (a write on the server each time, and two phones
        // of one account flipped its platform back and forth); an ask in flight is joined, not repeated.
        if (!frontAsks(answered, owned, state)) return
        asking(owned, store)
    }

    /** Fix wave M2: the ask, started in this object's own scope (or the one in flight for [uid], joined): its answer is
     *  heard when it comes, whoever stopped waiting for it. */
    fun asking(uid: String, store: LedgerStore): Deferred<ApprovalState?> = synchronized(askLock) {
        inFlight?.let { (who, job) -> if (who == uid && job.isActive) return job }
        scope.async { ask(uid, store) }.also { inFlight = uid to it }
    }

    /** Fix wave M2: the server's answer within [ms], or null (offline, or slower than that); the ask goes on. */
    suspend fun answerWithin(ms: Long, uid: String, store: LedgerStore): ApprovalState? =
        withTimeoutOrNull(ms) { asking(uid, store).await() }

    /** `requestApproval({ name?, platform })`: the name people see (the person's own name on this phone, if set) and
     *  this platform. Its answer is heard like the listener's, and returned; a failure (offline) changes nothing and
     *  is null. Through [asking], never alone. */
    private suspend fun ask(uid: String, store: LedgerStore): ApprovalState? {
        val data = mutableMapOf<String, kotlinx.serialization.json.JsonElement>("platform" to JsonPrimitive("android"))
        withContext(Dispatchers.IO) { CloudProfile.cloudName(runCatching { store.loadSelf()?.name }.getOrNull()) }
            ?.let { data["name"] = JsonPrimitive(it) }
        val result = try {
            request(JsonObject(data))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return null
        }
        val answer = ApprovalState.server(with(CloudFunctions) { result.text("state") })
        answered = uid to answer
        withContext(Dispatchers.Main) { heard(answer, uid, store) }
        return answer
    }

    /** [asking] off the caller's path, for the vault's account (a call the server refused for approval). */
    fun askSoon(uid: String) {
        val store = defaultStore() ?: return
        asking(uid, store)
    }

    /** One listener on `approvals/{uid}` while signed in and in front. A snapshot from the cache says nothing (the cache
     *  is memory only, so it starts empty); a refused or failed one changes nothing. Firestore calls back on the main
     *  thread. */
    fun listen(uid: String, store: LedgerStore) = synchronized(listenLock) {
        if (listeningAs == uid && listener != null) return
        stopListening()
        listeningAs = uid
        listener = Cloud.db().collection("approvals").document(uid).addSnapshotListener { snapshot, error ->
            if (error != null || snapshot == null || snapshot.metadata.isFromCache) return@addSnapshotListener
            val word = if (snapshot.exists()) snapshot.getString("state") else null
            heard(ApprovalState.server(word), uid, store)
        }
    }

    fun stopListening() = synchronized(listenLock) {
        listener?.remove()
        listener = null
        listeningAs = null
    }

    /** What the server said, acted on ([decide]) for the account still signed in as [uid]. On the main thread: the
     *  toast posts from here. */
    fun heard(server: ApprovalState, uid: String, store: LedgerStore) {
        if (signedInAs() != uid) return
        when (val step = decide(cached(store, uid), server)) {
            ApprovalStep.Nothing -> {
                if (loadedFor == uid && state != server && server != ApprovalState.UNKNOWN) state = server
                return
            }
            is ApprovalStep.Keep -> {
                setCached(store, uid, step.state)
                loadedFor = uid
                state = step.state
            }
            is ApprovalStep.Approve -> {
                setCached(store, uid, ApprovalState.APPROVED)
                loadedFor = uid
                state = ApprovalState.APPROVED
                if (step.toast) DoneCenter.post(DoneMoments.line(ApprovalCopy.APPROVED_TOAST))
                // Fix wave M4: only in front (an answer that came after SKIPTI left it waits for the next front event).
                if (startsCloudNow(step, inFront)) startCloud()
            }
            is ApprovalStep.Withdraw -> {
                setCached(store, uid, step.state)
                loadedFor = uid
                state = step.state
                stopCloud()
            }
        }
        SyncStatus.changed()
    }

    /** Every sign-out ([Account.signOut]): the listener stops and the vault forgets. */
    fun signedOut(store: LedgerStore? = defaultStore()) {
        stopListening()
        answered = null
        loadedFor = null
        state = ApprovalState.UNKNOWN
        store?.let(::clearCached)
    }
}
