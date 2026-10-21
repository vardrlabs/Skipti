package com.vardrlabs.skipti

import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import uniffi.skipti_core.GroupOpIn
import uniffi.skipti_core.GroupPictureOut
import uniffi.skipti_core.GroupsProjectionOut
import uniffi.skipti_core.RecordWire

/**
 * D-211: your name and picture in your groups. Your own phones get the
 * picture as a personal record ([saveAvatar]); a group's members get a small
 * preview and your name as a `MemberProfile` record that only the
 * `shareProfile` Function writes, so nobody can put a name or a picture on
 * someone else's row. This phone builds the records, sends them, and never
 * puts one in its own vault: it comes back through the group's log marked
 * (`profile: true`), the only way a profile is ever imported. iOS's twin is
 * ProfileSync.swift.
 */

/** A group member's preview: 120 px square, JPEG, at most 12,000 bytes
 *  (core's `THUMB_MAX_BYTES`). Never enlarged anywhere. */
object ProfileThumb {
    const val SIDE = 120
    const val MAX_BYTES = 12_000

    /** The centre square of [jpeg] at 120 px (a smaller picture is not
     *  enlarged), as a JPEG at the best quality (80, stepping down by 10 to 40)
     *  that fits [MAX_BYTES]. Null when the bytes are not an image or never fit
     *  (the share then sends the name only). */
    fun make(jpeg: ByteArray): ByteArray? =
        ProfileRules.squareJPEG(jpeg, SIDE, MAX_BYTES, startQuality = 80, minQuality = 40)
}

object ProfileShare {
    /** The job's flag: set by every trigger, cleared when the cloud answers. */
    const val PENDING_KEY = "profile.share.pending"

    /** Set once the backfill (every install of this build shares once, for the
     *  groups joined before profiles existed) has been requested. */
    const val BACKFILLED_KEY = "profile.share.backfilled"

    /** The groups this account joined whose place has not arrived yet (the claim
     *  is written before the group's records come down). Kept apart from
     *  pending: a pass that shared to every place it found clears pending,
     *  whatever waits here. Each group leaves when its place is found, or when
     *  it is deleted, removed or forgotten on this phone. */
    const val WAITING_KEY = "profile.share.waiting"

    /** The callable takes 1 to 50 records. */
    const val CALL_MAX = 50

    data class Place(val groupId: String, val memberId: String)

    // ---- Pure parts ----

    /**
     * The place this account holds in each group, from the projection: the
     * person it claimed by joining, or for a group it made, its own "you" id
     * ([PeopleRoles.placeFor], D-210: the id the cloud knows the row by).
     * Groups it was taken out of (the place is removed) and groups that are
     * read-only copies on this phone ([readOnly]: removed or deleted) are left
     * out: nothing can be written there. D-227: the cloud's own word for your
     * place in a group ([known]: its cached `places.you`) wins
     * ([PeopleRoles.selfPlace]); it finds a group made on another phone of the
     * account under an older "you" id.
     */
    fun places(p: GroupsProjectionOut, readOnly: Set<String>, known: Map<String, List<String>>): List<Place> {
        val you = p.you ?: return emptyList()
        val yous = listOf(you.id) + p.otherYouIds
        return p.groups.mapNotNull { g ->
            if (g.group.id in readOnly) return@mapNotNull null
            val claims = g.claims.associate { it.memberId to it.youId }
            val place = PeopleRoles.selfPlace(you.id, yous, claims, known[g.group.id])
            // The projection has already shown this account's own place by its
            // "you" id (the merge): that is the id to look for in the members
            // and the removed; the place the cloud knows is `place`.
            val held = you.id in g.group.members || place in g.group.members
            if (!held || you.id in g.removed || place in g.removed) null else Place(g.group.id, place)
        }
    }

    /** What a group sees of your picture: an emoji as it is, a photo as a
     *  120 px preview (none when it will not fit 12,000 bytes: the name is
     *  still shared). [thumb] makes the preview (a test seam). */
    data class Preview(val emoji: String?, val thumbHex: String?)

    fun preview(picture: GroupPictureOut?, thumb: (ByteArray) -> ByteArray? = ProfileThumb::make): Preview {
        if (picture == null) return Preview(null, null)
        picture.emoji?.let { return Preview(it, null) }
        val hex = picture.photoHex ?: return Preview(null, null)
        val bytes = runCatching { HexCodec.decode(hex) }.getOrNull() ?: return Preview(null, null)
        val small = thumb(bytes) ?: return Preview(null, null)
        return Preview(null, HexCodec.encode(small))
    }

    /**
     * One `MemberProfile` record per place, stamped and authored by this phone
     * through the same path as every group operation ([LedgerStore.groupOpRecord])
     * but NOT put into the vault: the Function writes it to the cloud, and it
     * reaches the vault only as it comes back marked. Throws when core refuses
     * the name or the preview.
     */
    fun records(
        store: LedgerStore, places: List<Place>, name: String, emoji: String?, thumbHex: String?,
        nowMs: Long = System.currentTimeMillis(),
    ): List<RecordWire> {
        val device = LedgerStore.deviceId(store)
        return places.map { place ->
            LedgerStore.groupOpRecord(
                GroupOpIn.MemberProfile(place.groupId, place.memberId, name, emoji, thumbHex),
                LedgerStore.nextHlc(store, nowMs), device,
            )
        }
    }

    /** The `shareProfile` callable's data: the D-210 record shape (as
     *  `adoptRemovals` takes it). */
    fun callData(records: List<RecordWire>): JsonObject = buildJsonObject {
        put("records", Removal.payload(records))
    }

    enum class Answer {
        /** The cloud answered: the flag is cleared. */
        DONE,

        /** `failed-precondition`: the name is not the account's; read it again. */
        NAME_CHANGED,

        /** Offline or any other error: the flag stays, for decision 40's timer and the next sync start. */
        LATER,
    }

    fun answer(error: Throwable?): Answer {
        if (error == null) return Answer.DONE
        val status = (error as? CloudFunctions.Failure.Refused)?.status ?: return Answer.LATER
        return when (status) {
            "FAILED_PRECONDITION" -> Answer.NAME_CHANGED
            // The call itself was wrong: asking again would not change that.
            "INVALID_ARGUMENT" -> Answer.DONE
            else -> Answer.LATER
        }
    }

    // ---- The flags ----

    fun isPending(store: LedgerStore): Boolean =
        runCatching { store.rawKvGet(PENDING_KEY)?.isNotEmpty() }.getOrNull() ?: false

    fun setPending(store: LedgerStore, on: Boolean) {
        store.rawKvSet(PENDING_KEY, if (on) byteArrayOf(1) else ByteArray(0))
    }

    /** Once per install of this build: marks the share pending so the groups
     *  joined before profiles existed get this account's name and preview. True
     *  when it asked now. Pending is set first: a crash between the two writes
     *  asks again rather than never. */
    fun backfillOnce(store: LedgerStore): Boolean {
        if (store.rawKvGet(BACKFILLED_KEY)?.isNotEmpty() == true) return false
        setPending(store, true)
        store.rawKvSet(BACKFILLED_KEY, byteArrayOf(1))
        return true
    }

    // ---- The waiting groups ----

    fun waiting(store: LedgerStore): Set<String> =
        (runCatching { store.rawKvGet(WAITING_KEY) }.getOrNull() ?: ByteArray(0))
            .decodeToString().split("\n").filter { it.isNotEmpty() }.toSet()

    fun setWaiting(store: LedgerStore, groups: Set<String>) {
        store.rawKvSet(WAITING_KEY, groups.sorted().joinToString("\n").toByteArray(Charsets.UTF_8))
    }

    /** A group that can no longer take a profile here (deleted, removed,
     *  forgotten): never waited for again. */
    fun dropWaiting(store: LedgerStore, groupId: String) = synchronized(lock) {
        val all = waiting(store).toMutableSet()
        if (all.remove(groupId)) setWaiting(store, all)
    }

    // ---- The name ----

    enum class NameOutcome {
        OK,

        /** Offline, or the account's profile is not there yet: next sync start. */
        LATER,

        /** The rules refused the write (`permission-denied`): not offline, and
         *  not retried now; the share stays pending. */
        REFUSED,
    }

    fun nameOutcome(error: Throwable): NameOutcome =
        nameOutcome((error as? FirebaseFirestoreException)?.code?.name)

    /** The same by Firestore's status name (its exception cannot be made on
     *  the JVM, where the tests run). */
    fun nameOutcome(status: String?): NameOutcome =
        if (status == "PERMISSION_DENIED") NameOutcome.REFUSED else NameOutcome.LATER

    /** The name as the account shares it, or null when the rules would refuse
     *  it: empty, or over [NAME_MAX]. Counted in UTF-16 code units, the largest
     *  count of the ways a string's size can be read (the rules'
     *  `string.size()`), so a name that passes here passes there. */
    fun shareable(raw: String?): String? {
        val name = (raw ?: "").trim()
        return if (name.isEmpty() || name.length > CloudProfile.NAME_MAX) null else name
    }

    // ---- The job ----

    /** The job's own lifetime: the app's. A test seam, as [GroupJobs.scope]:
     *  tests only; production never reassigns. */
    @Volatile var scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The calls the job makes, replaceable by the tests (each restores the
     *  real one after). */
    suspend fun realSend(data: JsonObject): Answer = try {
        CloudFunctions.callJson("shareProfile", data)
        Answer.DONE
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        answer(e)
    }

    /** Decision 40's timer: waits [seconds]. */
    suspend fun realRetryWait(seconds: Long) = kotlinx.coroutines.delay(seconds * 1000)

    /** Tests only; production never reassigns. */
    @Volatile var retryWait: suspend (Long) -> Unit = { realRetryWait(it) }

    /** Tests only; production never reassigns. */
    @Volatile var send: suspend (JsonObject) -> Answer = { realSend(it) }

    suspend fun realEnsure(uid: String, name: String): NameOutcome = try {
        CloudProfile.ensureName(uid, name)
        NameOutcome.OK
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // Nothing personal is logged: the reason only, never the name.
        nameOutcome(e).also {
            if (it == NameOutcome.REFUSED) android.util.Log.w("PROFILE-SHARE", "the rules refused the account name; the share waits")
        }
    }

    /** Tests only; production never reassigns. */
    @Volatile var ensure: suspend (String, String) -> NameOutcome = { uid, name -> realEnsure(uid, name) }

    /** The signed-in account when sync is on and it is the vault's owner, else null (the share waits). */
    val realAccount: (LedgerStore) -> String? = { store ->
        accountFor(CloudSync.syncEnabled(store), Account.uid(), Account.storedOwner(store))
    }

    /** D-235 final wave (the owner gate, decision 107's twin; iOS's `LaunchWork.signedInAccount`): the account the
     *  share runs for. Sync off, signed out, no owner, an unreadable one, or another account: none. */
    fun accountFor(syncOn: Boolean, uid: String?, owner: String?): String? =
        if (syncOn) CloudGate.owned(uid, owner) else null

    /** Tests only; production never reassigns. */
    @Volatile var account: (LedgerStore) -> String? = realAccount

    /** [account] for the job, where a throw (the vault will not open) is no
     *  account for this run: the share waits, and pending stays set for the
     *  next sync start. Cancellation is never swallowed. */
    private fun accountOrNull(store: LedgerStore): String? = try {
        account(store)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    /**
     * Per vault: a run in progress, the triggers so far, and the last one a
     * pass has served. Every real trigger counts one; a pass serves every
     * trigger made before it started, so the job runs at most one pass per
     * trigger and never on anything else. Every decision (a trigger, a pass's
     * end, a run's start or its early return) is made under [lock], with the
     * flag it reads or clears, so a trigger is never lost between a run's last
     * look and its return (iOS gets the same from the main actor).
     */
    private val lock = Any()
    private val running = HashSet<String>()
    private val generation = HashMap<String, Int>()
    private val served = HashMap<String, Int>()

    /** The vaults whose personal listener has delivered its first snapshot
     *  since it last started (final review I1(b)). No pass runs for any other:
     *  until this phone has the account's own records, its name and picture
     *  may be stale, and a share made then would carry them with a fresh
     *  stamp that beats the right ones in every group. */
    private val caughtUpVaults = HashSet<String>()

    /** D-227 (look F3): the vaults whose personal listener last spoke from the
     *  cache (the phone lost the server). Its next snapshot from the server is
     *  the reconnect ([afterReceive]): a share left for later runs then. */
    private val cachedVaults = HashSet<String>()

    /** D-227 decision 40: each vault's timed retries of a share that failed. */
    private val shareRetries = HashMap<String, ShareRetry>()

    /** The records of a call that has not been answered: a retry sends the SAME
     *  ones (the Function treats a record it already wrote as shared), and they
     *  are built again only when the name changed or the inputs did. */
    private var built: Pair<String, List<RecordWire>>? = null

    /** Counts one trigger ([prepare] runs with it, under the same lock) and
     *  runs the job. */
    private fun trigger(store: LedgerStore, prepare: () -> Unit = {}): Job {
        synchronized(lock) {
            prepare()
            generation[store.path] = (generation[store.path] ?: 0) + 1
        }
        return scope.launch { run(store) }
    }

    /** A trigger (a group made, the name or picture saved): marks the share
     *  pending and runs it. Never blocks a screen, never throws. */
    fun request(store: LedgerStore): Job = trigger(store) { runCatching { setPending(store, true) } }

    /** A join's claim is written: the group waits for its place to arrive
     *  ([arrived]), and the share runs now for every place already here. */
    fun joined(group: String, store: LedgerStore): Job {
        synchronized(lock) { runCatching { setWaiting(store, waiting(store) + group) } }
        return request(store)
    }

    /** The personal listener started or stopped ([CloudSync.listen] and
     *  stopListening): the share waits for its next first snapshot. */
    fun listening(store: LedgerStore) {
        synchronized(lock) {
            caughtUpVaults.remove(store.path)
            cachedVaults.remove(store.path)
            // Decision 40: a sign-out (or a new listener) cancels the timer and starts the count again.
            shareRetries.remove(store.path)
        }
    }

    /** The personal listener's first snapshot is in the vault: the sync start
     *  runs now (the backfill once per install, then whatever is pending).
     *  Later snapshots do nothing here. */
    fun caughtUp(store: LedgerStore): Job? = if (markCaughtUp(store)) syncStarted(store) else null

    /** The vault has the account's own records: passes may run. True the first
     *  time since the listener started. ([caughtUp] without the sync start;
     *  the tests mark their vaults with it.) */
    fun markCaughtUp(store: LedgerStore): Boolean = synchronized(lock) { caughtUpVaults.add(store.path) }

    /** Sync started: the backfill once per install, then whatever is pending. */
    fun syncStarted(store: LedgerStore): Job = trigger(store) { runCatching { backfillOnce(store) } }

    /** A group's records arrived. The ONLY arrival that shares: a group this
     *  account joined and was waiting for, which now holds its place. It leaves
     *  the waiting set first, so it shares once. Any other group's records,
     *  this phone's own returning profiles among them, do nothing. */
    fun arrived(groupId: String, store: LedgerStore): Job? {
        synchronized(lock) {
            val all = waiting(store).toMutableSet()
            if (groupId !in all) return null
            val p = runCatching { store.projectGroups() }.getOrNull() ?: return null
            if (places(p, store.readOnly().keys, GroupRoles.yourPlaces(store, p.groups.map { it.group.id })).none { it.groupId == groupId }) return null
            all.remove(groupId)
            // Never share without taking it out: that would share on every arrival.
            try {
                setWaiting(store, all)
            } catch (_: Exception) {
                return null
            }
        }
        return request(store)
    }

    /** What [CloudSync.receive] calls once records are in: only a group's own
     *  records that were new can be a waiting place arriving. The personal
     *  listener's snapshot (empty or not) says this phone has the account's
     *  own name and picture, so the share may run ([caughtUp]): only when the
     *  server sent it. A cache snapshot says nothing of that (the cache is
     *  memory only, so it is empty): Fix 3, M-A. */
    fun afterReceive(groupId: String?, fresh: List<RecordWire>, store: LedgerStore, fromCache: Boolean): Job? = when {
        groupId == null -> if (fromCache) {
            synchronized(lock) { cachedVaults.add(store.path) }
            null
        } else {
            val back = synchronized(lock) { cachedVaults.remove(store.path) }
            caughtUp(store) ?: if (back) reconnected(store) else null
        }
        fresh.isNotEmpty() -> arrived(groupId, store)
        else -> null
    }

    /** D-227 (look F3): the personal listener hears the server again after
     *  speaking from the cache. A share a pass left for later (offline: the
     *  name's server read failed, or the call did) runs now, not at the next
     *  sync start; with nothing pending the pass ends at once. */
    fun reconnected(store: LedgerStore): Job = trigger(store)

    /** D-227 decision 40 (re-look R2): a pass whose `shareProfile` call
     *  failed is tried again on decision 38's schedule, while its timer is
     *  still the vault's ([ShareRetry.due]). A name that could not be read is
     *  no failed call: the reconnect ([reconnected]) or the next sync start
     *  runs it. */
    private fun retryLater(store: LedgerStore, timer: RoleAsking.RetryTimer) {
        scope.launch {
            retryWait(timer.seconds)
            val due = synchronized(lock) { shareRetries[store.path]?.due(timer.token) == true }
            if (due) trigger(store)
        }
    }

    /** Shares when it is pending, signed in and syncing, and this phone has
     *  its own records ([caughtUp]); otherwise the flag waits. One run per vault at a time; a trigger
     *  during a pass makes exactly one more. Pending is cleared only by a pass
     *  that served every trigger and that the cloud answered. */
    suspend fun run(store: LedgerStore) {
        val id = store.path
        synchronized(lock) { if (!running.add(id)) return }
        var owner = true
        try {
            while (true) {
                // The look at the start of a pass: an unserved trigger, a
                // pending share, an account. Without all three the run ends,
                // and gives up its place in the same breath.
                val look = synchronized(lock) {
                    val now = generation[id] ?: 0
                    val uid = if (now != (served[id] ?: 0) && id in caughtUpVaults && isPending(store)) {
                        accountOrNull(store)
                    } else {
                        null
                    }
                    if (uid == null) {
                        running.remove(id)
                        owner = false
                        null
                    } else {
                        now to uid
                    }
                }
                val (now, uid) = look ?: return
                var callFailed = false
                val outcome = try {
                    attempt(uid, store) { callFailed = true }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    Answer.LATER
                }
                var timer: RoleAsking.RetryTimer? = null
                val finished = synchronized(lock) {
                    served[id] = now
                    if ((generation[id] ?: 0) == now) {
                        if (outcome == Answer.DONE) runCatching { setPending(store, false) }
                        // Decision 40: an answered pass ends the timed retries;
                        // a failed call sets the next timer, if any is left.
                        val retry = shareRetries.getOrPut(id) { ShareRetry() }
                        if (outcome == Answer.DONE) retry.succeeded() else if (callFailed) timer = retry.failed()
                        running.remove(id)
                        owner = false
                        true
                    } else {
                        false
                    }
                }
                if (finished) {
                    timer?.let { retryLater(store, it) }
                    return
                }
            }
        } finally {
            if (owner) synchronized(lock) { running.remove(id) }
        }
    }

    /** One try: the name, then the records, then the call. LATER keeps the
     *  flag; anything the cloud answers is DONE. [callFailed] hears a
     *  `shareProfile` call that failed (decision 40). */
    private suspend fun attempt(uid: String, store: LedgerStore, callFailed: () -> Unit): Answer {
        // Final review I1(a): a picture kept before pictures were records is a
        // record before anything below reads the picture (the rebuild that
        // migrates it may not have run yet this launch).
        try {
            store.migrateAvatarOnce()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
        // A name the vault cannot read is not "no name": the next try (M4).
        val me = try {
            store.loadSelf()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return Answer.LATER
        }
        // No name, or one the rules would refuse: nothing to share (saving a
        // name asks again).
        val name = shareable(me?.name) ?: return Answer.DONE
        // The account's public name is this one (saving a name used to reach it
        // only when registration next ran).
        if (ensure(uid, name) != NameOutcome.OK) return Answer.LATER
        for (round in 0 until 2) {
            val p = runCatching { store.projectGroups() }.getOrNull() ?: return Answer.LATER
            val readOnly = store.readOnly().keys
            val places = places(p, readOnly, GroupRoles.yourPlaces(store, p.groups.map { it.group.id }))
            // A waiting group whose place is here now is shared to by this pass;
            // one that became a read-only copy is never shared to.
            synchronized(lock) {
                val wait = waiting(store)
                val found = wait.filter { g -> g in readOnly || places.any { it.groupId == g } }
                if (found.isNotEmpty()) runCatching { setWaiting(store, wait - found.toSet()) }
            }
            if (places.isEmpty()) return Answer.DONE
            val (emoji, thumbHex) = preview(p.avatar)
            val key = (listOf(store.path, name, emoji ?: "", thumbHex ?: "") + places.map { it.groupId + "/" + it.memberId })
                .joinToString("|")
            val kept = synchronized(lock) { built?.takeIf { it.first == key }?.second }
            val toSend = kept ?: (runCatching { records(store, places, name, emoji, thumbHex) }.getOrNull()
                ?: return Answer.LATER).also { fresh -> synchronized(lock) { built = key to fresh } }
            var outcome = Answer.DONE
            var start = 0
            while (start < toSend.size) {
                val answer = send(callData(toSend.subList(start, minOf(start + CALL_MAX, toSend.size))))
                if (answer != Answer.DONE) {
                    outcome = answer
                    break
                }
                start += CALL_MAX
            }
            when (outcome) {
                Answer.DONE -> {
                    synchronized(lock) { built = null }
                    return Answer.DONE
                }
                Answer.LATER -> {
                    callFailed()
                    return Answer.LATER
                }
                Answer.NAME_CHANGED -> {
                    synchronized(lock) { built = null }
                    // The name is read again once; still refused, that is the answer.
                    if (round == 1) return Answer.DONE
                    if (ensure(uid, name) != NameOutcome.OK) return Answer.LATER
                }
            }
        }
        return Answer.DONE
    }
}

/**
 * D-227 decision 40: one vault's timed retries of a profile share whose call
 * failed, on decision 38's schedule ([RoleAsking.retryDelay]: 15, 60, 300 s,
 * then none until the count starts again). A share the cloud answered and a
 * sign-out (the personal listener stopping, [ProfileShare.listening]) start
 * it again; a group that left leaves the next pass nothing to share, which
 * ends as answered.
 */
class ShareRetry {
    private var made = 0
    private var live: Long? = null
    private var nextToken = 0L

    /** A pass's call failed: the timer to set, or null once three were made.
     *  A timer set earlier is no longer [due]. */
    fun failed(): RoleAsking.RetryTimer? {
        val seconds = RoleAsking.retryDelay(made)
        if (seconds == null) {
            live = null
            return null
        }
        made += 1
        nextToken += 1
        live = nextToken
        return RoleAsking.RetryTimer(seconds, nextToken)
    }

    /** A pass the cloud answered: no timer, and the count starts again. */
    fun succeeded() {
        made = 0
        live = null
    }

    /** The timer with [token] fired: true while it is still the vault's own. */
    fun due(token: Long): Boolean = live == token
}

/**
 * D-229 (Phase B): one account on two phones may have written records into a
 * group under an id the group does not know it by (the other phone's "you",
 * adopted after the group was made under this one's place). Those records are
 * never rewritten: the `shareAliases` Function writes, per (group, id), a
 * `placeAlias` operation "in this group, that id reads as this place", marked
 * `alias: true` after it checked the place is this account's and the id is
 * this account's own and nobody else's. Phones import one only with the mark
 * ([CloudRecords.refusal]). This phone builds the records, sends them and never
 * keeps one: it comes back through the group's log marked, as a profile does
 * ([ProfileShare]). Asked once per (group, id): what the Function answered
 * (shared or skipped) is remembered; a call that failed is asked again at the
 * next push. iOS's twin is AliasShare in ProfileSync.swift.
 */
object AliasShare {
    /** The answered pairs, one "group:you" line each. */
    const val ANSWERED_KEY = "alias.share.answered"

    /** The callable takes 1 to 50 records. */
    const val CALL_MAX = 50

    /** In [groupId], [youId] reads as the place [memberId]. */
    data class Alias(val groupId: String, val memberId: String, val youId: String) {
        val key: String get() = "$groupId:$youId"
    }

    // ---- Pure parts ----

    private val dtoJson = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    /** Every id a record of [group] names, as written (raw): an expense's payer,
     *  shares and who added it; a payment's two ends; an edit's editor and its
     *  money; a redistribution's ids; a renamed member. A record that does not
     *  decode names nothing. */
    fun named(records: List<RecordWire>, group: String): Set<String> {
        val out = mutableSetOf<String>()
        for (record in records) {
            if (record.groupId != group) continue
            runCatching {
                when (record.recordType) {
                    1u.toUByte() -> {
                        val e = dtoJson.decodeFromString(ExpenseDTO.serializer(), record.blob.decodeToString())
                        out += e.payer
                        e.shares.mapTo(out) { it.id }
                        e.addedBy?.let { out += it }
                    }
                    2u.toUByte() -> {
                        val s = dtoJson.decodeFromString(SettlementDTO.serializer(), record.blob.decodeToString())
                        out += s.from
                        out += s.to
                    }
                    LedgerStore.REVISION_TYPE -> {
                        val r = uniffi.skipti_core.ffiDecodeExpenseRevision(record.blob)
                        r.editor?.let { out += it }
                        r.money?.let { m ->
                            out += m.payer
                            m.shares.mapTo(out) { it.id }
                        }
                    }
                    LedgerStore.REDISTRIBUTION_TYPE -> {
                        val r = uniffi.skipti_core.ffiDecodeRedistribution(record.blob)
                        out += r.from
                        r.shares.mapTo(out) { it.id }
                    }
                    LedgerStore.GROUP_OP_TYPE -> {
                        val op = uniffi.skipti_core.ffiDecodeGroupOp(record.blob)
                        if (op is GroupOpIn.RenameMember) out += op.memberId
                    }
                    else -> Unit
                }
            }
        }
        return out
    }

    /** The aliases to ask for: per group with a place that is not a read-only
     *  copy ([readOnly]), each of this account's ids core found unreadable
     *  there (`unaliasedYous`) that a record of the group actually names, less
     *  the pairs already [answered]. An id never used in a group is never
     *  shared into it. */
    fun wanted(p: GroupsProjectionOut, records: List<RecordWire>, readOnly: Set<String>, answered: Set<String>): List<Alias> {
        val out = mutableListOf<Alias>()
        for (g in p.groups) {
            val place = g.place ?: continue
            val group = g.group.id
            if (group in readOnly || g.unaliasedYous.isEmpty()) continue
            val named = named(records, group)
            for (you in g.unaliasedYous) {
                val alias = Alias(group, place, you)
                if (alias.key !in answered && you in named) out += alias
            }
        }
        return out
    }

    /** One `placeAlias` record per alias, stamped and authored by this phone
     *  through the same path as every group operation, NOT put into the vault.
     *  Throws when core refuses one. */
    fun records(store: LedgerStore, aliases: List<Alias>, nowMs: Long = System.currentTimeMillis()): List<RecordWire> {
        val device = LedgerStore.deviceId(store)
        return aliases.map { a ->
            LedgerStore.groupOpRecord(GroupOpIn.PlaceAlias(a.groupId, a.memberId, a.youId), LedgerStore.nextHlc(store, nowMs), device)
        }
    }

    /** [records] one group at a time: a group whose records core refuses is
     *  left out (asked again at the next push), so it never holds the others
     *  back. The aliases built, each beside its record, in [aliases]' order. */
    fun recordsByGroup(
        store: LedgerStore, aliases: List<Alias>, nowMs: Long = System.currentTimeMillis(),
    ): List<Pair<Alias, RecordWire>> =
        aliases.groupBy { it.groupId }.values.flatMap { group ->
            val built = try {
                records(store, group, nowMs)
            } catch (_: Exception) {
                return@flatMap emptyList()
            }
            group.zip(built)
        }

    /** The callable's data: the D-210 record shape (as `shareProfile` takes it). */
    fun callData(records: List<RecordWire>): JsonObject = buildJsonObject {
        put("records", Removal.payload(records))
    }

    /** The record ids the reply answered: shared and skipped alike. */
    fun replied(reply: JsonObject): Set<String> =
        listOf("shared", "skipped").flatMap { key ->
            (reply[key] as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull {
                (it as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { p -> p.isString }?.content
            }
        }.toSet()

    enum class Answer {
        /** The cloud answered (or nothing was asked). */
        DONE,

        /** `invalid-argument`: the call itself was wrong; asking again would not
         *  change that, so its pairs count as answered. */
        REFUSED,

        /** Offline or any other error: the pairs are asked again at the next push. */
        LATER,
    }

    /** Only `invalid-argument` is REFUSED. Everything else is LATER, the
     *  daily quota's `resource-exhausted` included (D-229 decision 48: the
     *  pairs are asked again on a later push, never given up). */
    fun answer(error: Throwable?): Answer {
        if (error == null) return Answer.DONE
        val status = (error as? CloudFunctions.Failure.Refused)?.status ?: return Answer.LATER
        return if (status == "INVALID_ARGUMENT") Answer.REFUSED else Answer.LATER
    }

    // ---- The answered pairs ----

    fun answered(store: LedgerStore): Set<String> =
        (store.rawKvGet(ANSWERED_KEY) ?: ByteArray(0)).decodeToString().split("\n").filter { it.isNotEmpty() }.toSet()

    fun saveAnswered(store: LedgerStore, pairs: Set<String>) {
        store.rawKvSet(ANSWERED_KEY, pairs.sorted().joinToString("\n").toByteArray(Charsets.UTF_8))
    }

    // ---- The job ----

    suspend fun realSend(data: JsonObject): JsonObject = CloudFunctions.callJson("shareAliases", data)

    /** Tests only; production never reassigns. */
    @Volatile var send: suspend (JsonObject) -> JsonObject = { realSend(it) }

    /** At the end of a push ([CloudSync.pushAll], which runs one at a time):
     *  asks for every wanted alias of a group in the cloud ([CloudRecords.groupsUp]:
     *  the Function skips, for good, a group it cannot find), 50 to a call, and
     *  remembers each pair the reply answered. LATER when a call failed: the
     *  rest waits for the next push. */
    suspend fun share(store: LedgerStore, nowMs: Long = System.currentTimeMillis()): Answer {
        val up = CloudRecords.groupsUp(store)
        val answered = answered(store).toMutableSet()
        val want = wanted(store.projectGroups(), store.allRecords(), store.readOnly().keys, answered)
            .filter { it.groupId in up }
        if (want.isEmpty()) return Answer.DONE
        val pairs = recordsByGroup(store, want, nowMs)
        // Every group's records refused: nothing asked, all asked again next push.
        if (pairs.isEmpty()) return Answer.LATER
        val built = pairs.map { it.second }
        val keyOf = pairs.associate { (alias, record) -> CloudRecords.documentId(record) to alias.key }
        for (chunk in built.chunked(CALL_MAX)) {
            val ids = try {
                replied(send(callData(chunk)))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (answer(e) != Answer.REFUSED) return Answer.LATER
                chunk.map(CloudRecords::documentId).toSet()
            }
            answered += ids.mapNotNull { keyOf[it] }
            saveAnswered(store, answered)
        }
        return Answer.DONE
    }
}

/**
 * D-230: the server's word on who holds a place. After a push that reached the
 * cloud, every place this account holds that the group's log does not yet mark
 * for this account's "you" is shared once as a `heldPlace` the `sharePlaces`
 * Function verifies and writes marked (`held: true`); core then treats it as
 * final for the member and the id (CLOUD-SECURITY G-08). The [AliasShare]
 * contract: asked once per (group, place, you), answers remembered, a failed
 * call or the daily quota asked again at the next push, a refused call done.
 * This phone builds the records, sends them and never keeps one: it comes back
 * through the group's log marked. iOS's twin is PlaceShare in ProfileSync.swift.
 */
object PlaceShare {
    /** The answered triples, one "group:member:you" line each. */
    const val ANSWERED_KEY = "place.share.answered"

    /** The callable takes 1 to 50 records. */
    const val CALL_MAX = 50

    /** In [groupId], the place [memberId] is held by this account's id [youId]. */
    data class Held(val groupId: String, val memberId: String, val youId: String) {
        val key: String get() = "$groupId:$memberId:$youId"
    }

    // ---- Pure parts ----

    /** D-230 A1 (second form): every place a claim of this account holds in
     *  [g] (a claim whose id is its "you" or one of its other ids), with that
     *  claim's id, less the pairs the group's marked held places already
     *  list. No removed check: the server is the authority (it marks a
     *  present place the caller holds and a removed place the caller held
     *  before, and skips the rest), and [answered] keeps a skipped pair from
     *  being asked again. A rejoin sends them with the new place, so the old
     *  place is marked too and core never reads its claim as a forgery.
     *  A7: also every `claim` group op in the vault's [records] for [g] whose
     *  `you` is one of this account's ids: core voids a rejoin's claim on its
     *  new place (its `you` is held by the old member) and lists it nowhere but
     *  `contested`, and only the server's mark brings that place back. In
     *  member order (the group's), a claim on a member the group does not list
     *  last; one pair once. A10 (M1): a member any of this account's ids
     *  already holds (marked in `g.held`) is skipped whatever the claim's id,
     *  so a two-phone account never re-marks a member under its other id and
     *  the latest mark never flips. */
    fun claimed(p: GroupsProjectionOut, g: uniffi.skipti_core.ProjectedGroupOut, records: List<RecordWire>): List<Held> {
        val mine = listOfNotNull(p.you?.id).toSet() + p.otherYouIds
        val group = g.group.id
        val fromCore = g.claims.filter { c -> c.youId in mine }.map { Held(group, it.memberId, it.youId) }
        val fromVault = records.filter { it.recordType == LedgerStore.GROUP_OP_TYPE && it.groupId == group }.mapNotNull { r ->
            (runCatching { uniffi.skipti_core.ffiDecodeGroupOp(r.blob) }.getOrNull() as? GroupOpIn.Claim)
                ?.takeIf { it.groupId == group && it.youId in mine }
                ?.let { Held(group, it.memberId, it.youId) }
        }
        val order = g.group.members.withIndex().associate { (i, m) -> m to i }
        return (fromCore + fromVault).distinct()
            .filter { h -> g.held.none { it.memberId == h.memberId && it.youId in mine } }
            .sortedBy { order[it.memberId] ?: Int.MAX_VALUE }
    }

    /** The places to ask for: per group that is not a read-only copy
     *  ([readOnly]), the places this account's claims hold there ([claimed],
     *  A1), then (place, you) when it has a place, each unless the group's
     *  marked held places list exactly that pair, less the keys already
     *  [answered]. A6 (I-B): a group with no place is asked too: a rejoin
     *  whose new place's mark failed while the old place is marked has no
     *  place (its claim on the new place is void until the server marks it),
     *  and only the claimed pair brings it back. */
    fun wanted(p: GroupsProjectionOut, records: List<RecordWire>, readOnly: Set<String>, answered: Set<String>): List<Held> {
        val you = p.you?.id ?: return emptyList()
        return p.groups.flatMap { g ->
            if (g.group.id in readOnly) return@flatMap emptyList()
            val now = g.place?.let { place ->
                Held(g.group.id, place, you).takeIf { g.held.none { it.memberId == place && it.youId == you } }
            }
            (claimed(p, g, records) + listOfNotNull(now)).distinct().filter { it.key !in answered }
        }
    }

    /** One `heldPlace` record per place, stamped and authored by this phone
     *  through the same path as every group operation (the group's scope),
     *  NOT put into the vault. Throws when core refuses one. */
    fun records(store: LedgerStore, places: List<Held>, nowMs: Long = System.currentTimeMillis()): List<RecordWire> {
        val device = LedgerStore.deviceId(store)
        return places.map { h ->
            LedgerStore.groupOpRecord(GroupOpIn.HeldPlace(h.groupId, h.memberId, h.youId), LedgerStore.nextHlc(store, nowMs), device)
        }
    }

    /** The records [share] sends, one place at a time: a place whose record
     *  core cannot build is left out (asked again at the next push); every
     *  other place's still goes. Each beside its place, in [places]' order. */
    fun recordsByGroup(
        store: LedgerStore, places: List<Held>, nowMs: Long = System.currentTimeMillis(),
    ): List<Pair<Held, RecordWire>> =
        places.mapNotNull { held ->
            val built = try {
                records(store, listOf(held), nowMs)
            } catch (_: Exception) {
                return@mapNotNull null
            }
            built.singleOrNull()?.let { held to it }
        }

    /** The `sharePlaces` callable's data: the D-210 record shape. */
    fun callData(records: List<RecordWire>): JsonObject = AliasShare.callData(records)

    /** The record ids the reply answered: shared and skipped alike. */
    fun replied(reply: JsonObject): Set<String> = AliasShare.replied(reply)

    /** [AliasShare]'s answers, the same meaning. */
    fun answer(error: Throwable?): AliasShare.Answer = AliasShare.answer(error)

    // ---- The answered triples ----

    fun answered(store: LedgerStore): Set<String> =
        (store.rawKvGet(ANSWERED_KEY) ?: ByteArray(0)).decodeToString().split("\n").filter { it.isNotEmpty() }.toSet()

    fun saveAnswered(store: LedgerStore, keys: Set<String>) {
        store.rawKvSet(ANSWERED_KEY, keys.sorted().joinToString("\n").toByteArray(Charsets.UTF_8))
    }

    // ---- The job ----

    suspend fun realSend(data: JsonObject): JsonObject = CloudFunctions.callJson("sharePlaces", data)

    /** Tests only; production never reassigns. */
    @Volatile var send: suspend (JsonObject) -> JsonObject = { realSend(it) }

    /** At the end of a push ([CloudSync.pushAll], which runs one at a time),
     *  after [AliasShare.share]: asks for every wanted place of a group in the
     *  cloud ([CloudRecords.groupsUp]), 50 to a call, and remembers each triple
     *  the reply answered. LATER when a call failed: the rest waits for the
     *  next push. */
    suspend fun share(store: LedgerStore, nowMs: Long = System.currentTimeMillis()): AliasShare.Answer {
        val up = CloudRecords.groupsUp(store)
        val answered = answered(store).toMutableSet()
        val want = wanted(store.projectGroups(), store.allRecords(), store.readOnly().keys, answered).filter { it.groupId in up }
        if (want.isEmpty()) return AliasShare.Answer.DONE
        val pairs = recordsByGroup(store, want, nowMs)
        // Every place's record refused: nothing asked, all asked again next push.
        if (pairs.isEmpty()) return AliasShare.Answer.LATER
        val built = pairs.map { it.second }
        val keyOf = pairs.associate { (held, record) -> CloudRecords.documentId(record) to held.key }
        for (chunk in built.chunked(CALL_MAX)) {
            val ids = try {
                replied(send(callData(chunk)))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (answer(e) != AliasShare.Answer.REFUSED) return AliasShare.Answer.LATER
                chunk.map(CloudRecords::documentId).toSet()
            }
            answered += ids.mapNotNull { keyOf[it] }
            saveAnswered(store, answered)
        }
        return AliasShare.Answer.DONE
    }
}

/**
 * D-211: what the screens draw for another person in a group, and whether
 * Rename is offered. `previews` is [memberPreviews]: by group id, then by the
 * id the person is SHOWN by; a person is present when they have a profile of
 * their own (their name is theirs), and `Avatar.None` there means a name with
 * no picture. iOS's twin is MemberPreviews in ProfileSync.swift.
 */
object MemberPreviews {
    /** Rename is absent (not disabled, D-126) for a person with a profile in
     *  this group; people who never joined stay renamable. */
    fun mayRename(previews: Map<String, Map<String, Avatar>>, group: String, member: String): Boolean =
        previews[group]?.get(member) == null

    /** The previews after a reload: what the read gives, or, when the read
     *  throws, the last good ones. A picture is decoration: a failed read never
     *  costs the screen its people (nothing is logged: it would carry nothing
     *  but the error of a vault read). */
    fun reloaded(
        last: Map<String, Map<String, Avatar>>,
        read: () -> Map<String, Map<String, Avatar>>,
    ): Map<String, Map<String, Avatar>> =
        try {
            read()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            last
        }

    /** The preview of one person in the group the screen is about. */
    fun preview(previews: Map<String, Map<String, Avatar>>, group: String, member: String): Avatar =
        previews[group]?.get(member) ?: Avatar.None

    /** A person who is in several groups: the preview from the group the card
     *  is about when it has a picture, else the first group, in [candidates]'
     *  order (the projection's), that has one. `Avatar.None` when none does. */
    fun pick(
        previews: Map<String, Map<String, Avatar>>,
        candidates: List<Pair<String, String>>,
        preferred: String?,
    ): Avatar {
        if (preferred != null) {
            candidates.firstOrNull { it.first == preferred }?.let { own ->
                val shown = preview(previews, own.first, own.second)
                if (shown != Avatar.None) return shown
            }
        }
        for ((group, member) in candidates) {
            val shown = preview(previews, group, member)
            if (shown != Avatar.None) return shown
        }
        return Avatar.None
    }
}
