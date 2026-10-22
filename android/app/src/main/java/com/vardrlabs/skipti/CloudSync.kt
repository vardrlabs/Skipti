package com.vardrlabs.skipti

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.firebase.Timestamp
import com.google.firebase.firestore.Blob
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.Source
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.skipti_core.RecordWire
import java.io.File

/**
 * D-192: the one seam between the ledger and the cloud; every ledger change
 * and every return to the front comes through here. iOS's twin is
 * CloudSync.swift. D-194 (the Firebase plan's unit 4): your ledger syncs
 * between your phones. Each record in the vault (an expense, a settlement, an
 * edit, a group operation or "you") is one Firestore document under
 * `users/{uid}/records` or its group's `groups/{g}/records`, uploaded once;
 * snapshot listeners bring the account's other phones' records down, and
 * core's own merge applies them exactly as it always has. The vault stays the
 * database every screen reads. A group goes up with this account its only
 * member (others join through invites, unit 5).
 */
object CloudSync {
    /** The hook every ledger mutation calls after persisting locally. Never
     *  throws (it runs on the save paths). */
    fun ledgerChanged(vaultPath: String) {
        push()
    }

    /** SKIPTI came to the front (listeners on) or left it (listeners off). */
    fun appIsActive(active: Boolean) {
        if (active) {
            val context = VaultGate.appContextRef ?: return
            // D-235 (M1): a token delete that could not finish is tried again, unless push is about to want the token.
            if (Account.uid() == null) Push.settleOwed(LedgerStore(vaultPathOf(context)))
            // D-254: the account's approval is listened for, and asked for while it is not approved; the cloud work
            // below runs only once it is ([ready], [registerPhone]).
            Approval.front(Account.uid(), LedgerStore(vaultPathOf(context)))
            registerPhone(LedgerStore(vaultPathOf(context)), context)
            push()
            listen()
        } else {
            stopListening()
            Approval.stopListening()
        }
    }

    /** D-254: approval taken away (approved to denied): the cloud stops as sync off stops it (the listeners down,
     *  nothing pushed, push off at FCM; the device document is not written, the rules refuse it now). Local data is
     *  untouched; approval given back starts it all again ([Approval.heard]). */
    fun approvalWithdrawn() {
        registered = null
        stopListening()
        val context = VaultGate.appContextRef ?: return
        scope.launch { Push.stop(LedgerStore(vaultPathOf(context)), inCloud = false) }
    }

    /** The sync switch changed (Settings). D-196: pushes come with sync. */
    fun syncSwitched() {
        stopListening()
        val context = VaultGate.appContextRef
        if (context != null && Account.uid() != null) {
            val store = LedgerStore(vaultPathOf(context))
            // D-235 (M2): Push.stop bounds each cloud call, so sync off never hangs offline.
            if (!runCatching { syncEnabled(store) }.getOrDefault(true)) scope.launch { Push.stop(store, inCloud = true) }
        }
        appIsActive(true)
    }

    // D-193: the profile and this phone's entry, once per launch per account.
    @Volatile private var registered: String? = null
    private val registering = java.util.concurrent.atomic.AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** One thing at a time touches the vault from here: pushes and arrivals
     *  run in order. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val serial = Dispatchers.IO.limitedParallelism(1)

    /** After a sign-in, and each time SKIPTI comes to the front. */
    fun registerPhone(store: LedgerStore, context: Context) {
        // D-235 round 4 (M9): nor is this phone registered, or push started, for a session the vault does not own.
        val uid = CloudGate.owned(Account.uid(), Account.storedOwner(store)) ?: return
        // D-254: nor for an account not approved (the rules refuse its profile and devices); push's restart below waits
        // too.
        if (Approval.gate(uid, Account.storedOwner(store), Approval.cached(store, uid)) == null) return
        if (registered == uid) {
            if (runCatching { syncEnabled(store) }.getOrDefault(false)) Push.start(store)
            return
        }
        if (!registering.compareAndSet(false, true)) return
        val version = AboutInfo.current(context).first
        scope.launch {
            val outcome = try {
                CloudProfile.register(uid, store, version).also { if (it is CloudProfile.Outcome.Registered) registered = uid }
            } finally {
                registering.set(false)
            }
            // D-198: signed out from another phone (or Sign out everywhere).
            if (outcome is CloudProfile.Outcome.SignedOutRemotely) {
                withContext(Dispatchers.Main) { RemoteSignOut.happened(vaultPathOf(context)) }
                return@launch
            }
            // D-196: this phone's push address, once its entry exists.
            if (registered == uid && runCatching { syncEnabled(store) }.getOrDefault(false)) Push.start(store)
            // D-197: whether people can find this account (on unless switched off).
            if (registered == uid) People.syncFindable(uid, store)
        }
    }

    /** D-254: the sign-in screen's username step and the username step itself register the profile directly (they need
     *  it before a username can be set); through here, so an account not approved writes nothing the rules refuse, and
     *  the vault's owner is checked as [registerPhone] checks it. Call off the main thread. */
    suspend fun registration(uid: String, store: LedgerStore, appVersion: String): CloudProfile.Outcome {
        if (Account.storedOwner(store) != uid) return CloudProfile.Outcome.Failed("This phone's ledger belongs to another account.")
        val approval = Approval.cached(store, uid)
        if (!Approval.allowsCloud(approval)) {
            return CloudProfile.Outcome.Failed(if (approval == ApprovalState.DENIED) ApprovalCopy.CALL_DENIED else ApprovalCopy.CALL_WAITING)
        }
        return CloudProfile.register(uid, store, appVersion)
    }

    fun accountSignedOut() {
        registered = null
        stopListening()
        // D-196: FCM's token is deleted, so no push reaches this phone for the
        // account (Settings' sign-out also removes it from the cloud first,
        // while it still may).
        val context = VaultGate.appContextRef ?: return
        scope.launch { Push.stop(LedgerStore(vaultPathOf(context)), inCloud = false) }
    }

    fun vaultPathOf(context: Context): String = File(context.filesDir, "ledger-vault.db").absolutePath

    /** GUARANTEES #5: sync is on with an account and off in one tap. This
     *  phone's own switch (the vault's `sync/enabled`, absent = on), the same
     *  key the relay's sync used, so a phone keeps its choice. */
    const val SYNC_ENABLED_KEY = "sync/enabled"

    fun syncEnabled(store: LedgerStore): Boolean =
        store.rawKvGet(SYNC_ENABLED_KEY)?.decodeToString() != "0"

    fun setSyncEnabled(store: LedgerStore, enabled: Boolean) {
        store.rawKvSet(SYNC_ENABLED_KEY, (if (enabled) "1" else "0").toByteArray(Charsets.UTF_8))
    }

    /** Signed in, and sync on: the account and the vault to sync. */
    private fun ready(): Pair<String, LedgerStore>? {
        val uid = Account.uid() ?: return null
        val context = VaultGate.appContextRef ?: return null
        val store = LedgerStore(vaultPathOf(context))
        // D-235 round 4 (shells review M9, decision 107's twin): only the vault's own account syncs it. A read that
        // fails is no owner: fail closed (nothing syncs this launch), and nobody is signed out for it.
        if (CloudGate.owned(uid, Account.storedOwner(store)) == null) return null
        // D-254: and only when it is approved (the vault's copy of the last state heard), so a waiting account pushes
        // and listens to nothing.
        if (!Approval.allowsCloud(Approval.cached(store, uid))) return null
        return if (runCatching { syncEnabled(store) }.getOrDefault(false)) uid to store else null
    }

    // ---- D-194: up ----

    private val pushing = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var pushAgain = false
    /** Records uploaded by the last push; for the emulator proof. */
    @Volatile var lastPushed = 0
        private set

    /** Uploads every record the cloud does not have yet. One push at a time;
     *  a change during a push runs another after it. */
    fun push() {
        if (ready() == null) return
        if (!pushing.compareAndSet(false, true)) {
            pushAgain = true
            return
        }
        scope.launch {
            try {
                do {
                    pushAgain = false
                    val (uid, store) = ready() ?: break
                    lastPushed = withContext(serial) { pushAll(uid, store) }
                } while (pushAgain)
            } finally {
                pushing.set(false)
            }
        }
    }

    /** A pull to refresh: the listeners already bring records down; this
     *  sends what is waiting. True when nothing of [group] (of anything, with
     *  no group) is left waiting afterwards (D-210 final B6: Settle first says
     *  whether its payments went up before the removal). */
    suspend fun syncNow(group: String? = null): Boolean {
        val (uid, store) = ready() ?: return false
        lastPushed = withContext(serial) { pushAll(uid, store) }
        return withContext(serial) {
            runCatching {
                // D-235 round 6 (twin of iOS): a parked record is not waiting while it is not due again; a due one
                // (the push just tried it) still counts until it goes up or is refused again.
                val refused = CloudRecords.adoptRefused(store) + CloudRecords.heldParked(store, System.currentTimeMillis())
                CloudRecords.pending(store.allRecords(), CloudRecords.uploaded(store))
                    .none { (group == null || it.groupId == group) && CloudRecords.documentId(it) !in refused }
            }.getOrDefault(false)
        }
    }

    /** Where a record lives: the account's own records (personal scope) or its
     *  group's. */
    fun collection(uid: String, groupId: String?): CollectionReference =
        if (groupId == null) Cloud.db().collection("users").document(uid).collection("records")
        else Cloud.db().collection("groups").document(groupId).collection("records")

    /** Writes in batches; a batch the rules refuse (a document that already
     *  exists: records are never overwritten) is retried one by one, and a
     *  document found to exist counts as uploaded. A group is created in the
     *  cloud, with this account its only member, before its first record.
     *  Offline, it stops and the next push carries on. Returns how many
     *  records were uploaded. */
    suspend fun pushAll(uid: String, store: LedgerStore): Int {
        // D-195: this account's claim on its own place in every group it is
        // in, so the other members' phones know that person has joined.
        runCatching {
            store.appendGroupOps(CloudRecords.ownClaims(store.projectGroups(), LedgerStore.Sync.deviceId(store)), Placing.NONE)
        }
        val all = runCatching { store.allRecords() }.getOrNull() ?: return 0
        val uploaded = runCatching { CloudRecords.uploaded(store) }.getOrNull()?.toMutableSet() ?: return 0
        // D-235 round 4 (shells review M7, the twin of iOS's M2): every mark is ADDED to what is stored when it is
        // saved (`addUploaded` and friends read just before they write); `receive` runs while this push waits on the
        // network, and a whole-set save of these copies would drop the marks it wrote.
        val uploadedBefore = uploaded.toSet()
        val groupsUp = runCatching { CloudRecords.groupsUp(store) }.getOrNull()?.toMutableSet() ?: return 0
        // D-210: a group that is a read-only copy (removed from it, or it was
        // deleted) takes nothing up; what is pending there stays on the phone.
        val readOnly = store.readOnly().keys
        // D-210 final B5: a removal adoptRemovals refused for good stays here.
        val refusedBefore = runCatching { CloudRecords.adoptRefused(store) }.getOrDefault(emptySet())
        // D-211: only the latest of the account's pictures goes up.
        val out = CloudRecords.outgoing(all, uploaded, readOnly, refusedBefore)
        // D-235 round 2 (wave B review I2): a record the rules refused for good stays on this phone and is not sent
        // again; it never holds back the records after it.
        // Decision 114 (shells review M5): a parked record is tried again once a day, at most three times, then stays.
        val parked = runCatching { CloudRecords.uploadRefused(store) }.getOrDefault(emptySet())
        val retries = runCatching { CloudRecords.uploadRetries(store) }.getOrDefault(emptyMap())
        val nowMs = System.currentTimeMillis()
        var pending = CloudRecords.sendable(out.send, parked, retries, nowMs)
        val newlyRefused = mutableMapOf<String, CloudRecords.Retry>()
        if (out.superseded.isNotEmpty()) {
            uploaded += out.superseded
            runCatching { CloudRecords.addUploaded(store, out.superseded) }
        }
        if (pending.isEmpty()) {
            // D-229: nothing waits, so every group this phone knows is up has
            // its records there: the aliases go now (iOS's twin does the same).
            shareAliases(store)
            // D-230: then the places this account holds, for the server to mark.
            sharePlaces(store)
            return 0
        }
        // Groups first: a record can only be written by a member.
        val names = runCatching { store.loadGroups() }.getOrDefault(emptyList()).associate { it.id to it.name }
        val unavailable = mutableSetOf<String>()
        val upBefore = groupsUp.toSet()
        for (groupId in pending.mapNotNull { it.groupId }.toSet()) {
            if (groupId in groupsUp) continue
            when (ensureGroup(groupId, names[groupId], uid)) {
                GroupState.MEMBER -> groupsUp += groupId
                GroupState.NOT_MEMBER -> unavailable += groupId
                GroupState.OFFLINE -> return 0
            }
        }
        runCatching { CloudRecords.addGroupsUp(store, groupsUp - upBefore) }
        // D-211: a group that has just reached the cloud: your name and preview
        // go to it once its records are up (end of this push).
        val gainedGroups = groupsUp - upBefore
        // D-227 (look F2): an open page of such a group reads it again now (People
        // turns from the phone-only layout to the cloud's), not on its next arrival.
        if (gainedGroups.isNotEmpty()) withContext(Dispatchers.Main) { SyncStatus.changed() }
        pending = pending.filter { it.groupId == null || it.groupId !in unavailable }
        // D-210 final B5: a removal made while the group was on this phone
        // alone goes up through adoptRemovals (the Function marks it), never
        // as a plain write other phones would refuse; one refused for good
        // stays here.
        val split = CloudRecords.adoptionUnits(pending, refusedBefore)
        pending = split.direct
        // D-196: this phone's own expense changes in groups carry their push line.
        val notices = PushNotice.notices(pending, store)

        var count = 0
        var offline = false
        // D-227: the groups this push put records into.
        val pushedGroups = mutableSetOf<String>()
        for (chunk in pending.chunked(100)) {
            val batch = Cloud.db().batch()
            for (record in chunk) {
                batch.set(
                    collection(uid, record.groupId).document(CloudRecords.documentId(record)),
                    CloudRecords.document(record, uid, notices[CloudRecords.documentId(record)]),
                )
            }
            try {
                batch.commit().awaitTask()
                chunk.forEach { uploaded += CloudRecords.documentId(it) }
                chunk.mapNotNullTo(pushedGroups) { it.groupId }
                count += chunk.size
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // One by one: a document that exists counts as uploaded; D-235 round 2: one the rules refuse (and the
                // server does not hold) is recorded as refused and skipped, and the rest go on; anything else (offline,
                // signed out, the server busy) stops this push and the next one carries on.
                fun doc(record: RecordWire) = collection(uid, record.groupId).document(CloudRecords.documentId(record))
                val result = CloudRecords.oneByOne(
                    chunk,
                    write = { record -> doc(record).set(CloudRecords.document(record, uid, notices[CloudRecords.documentId(record)])).awaitTask() },
                    // Look fix F4: a read the rules refuse too (a group this account was removed from) is DENIED.
                    exists = { record -> CloudRecords.held(Account.uid() == uid) { doc(record).get(Source.SERVER).awaitTask().exists() } },
                )
                for (record in result.uploaded) {
                    uploaded += CloudRecords.documentId(record)
                    record.groupId?.let { pushedGroups += it }
                }
                count += result.written
                for (record in result.refused) {
                    val id = CloudRecords.documentId(record)
                    newlyRefused[id] = CloudRecords.afterRefusal(retries[id], id in parked, nowMs)
                }
                offline = result.stopped
            }
            if (offline) break
        }
        if (!offline) {
            val refused = refusedBefore.toMutableSet()
            for (unit in split.units) {
                val groupId = unit.firstOrNull()?.groupId ?: continue
                val error = try {
                    CloudFunctions.callJson("adoptRemovals", CloudRecords.adoptData(groupId, unit))
                    null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    e
                }
                when (CloudRecords.adoptOutcome(error)) {
                    CloudRecords.AdoptOutcome.DONE -> {
                        unit.forEach { uploaded += CloudRecords.documentId(it) }
                        pushedGroups += groupId
                        count += unit.size
                    }
                    // Others joined first: the removal stays on this phone
                    // (recorded in BUILD-LOG §4), and is not tried again.
                    CloudRecords.AdoptOutcome.REFUSED -> unit.forEach { refused += CloudRecords.documentId(it) }
                    CloudRecords.AdoptOutcome.RETRY -> break
                }
            }
            runCatching { CloudRecords.addAdoptRefused(store, refused - refusedBefore) }
        }
        runCatching { CloudRecords.addUploaded(store, uploaded - uploadedBefore) }
        // Decision 114: refused again (or for the first time) counts a try; a parked record that went up leaves the lot.
        runCatching { CloudRecords.updateParked(store, newlyRefused, parked intersect uploaded) }
        // Decision 122: a group's page says a change is parked there, and drops the line once it went up.
        if (newlyRefused.isNotEmpty() || (parked intersect uploaded).isNotEmpty()) withContext(Dispatchers.Main) { SyncStatus.changed() }
        // D-227: a group whose cached roles do not name your place yet (its create
        // op was not up when they were asked) is asked again now, before the
        // profile share reads that place.
        if (!offline) {
            for (groupId in CloudRecords.rolesAfterPush(pushedGroups) { GroupRoles.load(store, it) }) {
                GroupRoles.refresh(store, groupId)
            }
        }
        if (gainedGroups.isNotEmpty() && !offline) ProfileShare.request(store)
        // D-229 (Phase B): an id of this account that a group cannot read, used
        // there before the place was known, is shared once as a server-verified
        // alias; a failed call is asked again at the next push.
        if (!offline) shareAliases(store)
        // D-230: then every place this account holds that the group's log does
        // not yet mark, for the server to mark (held: true).
        if (!offline) sharePlaces(store)
        return count
    }

    /** [AliasShare.share], whose failure never fails the push (it is asked
     *  again at the next one). */
    private suspend fun shareAliases(store: LedgerStore) {
        try {
            AliasShare.share(store)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    /** [PlaceShare.share], whose failure never fails the push (it is asked
     *  again at the next one). */
    private suspend fun sharePlaces(store: LedgerStore) {
        try {
            PlaceShare.share(store)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    enum class GroupState { MEMBER, NOT_MEMBER, OFFLINE }

    /** Creates the group in the cloud with this account as its only member, or
     *  finds it already there. */
    suspend fun ensureGroup(groupId: String, name: String?, uid: String): GroupState {
        val doc = Cloud.db().collection("groups").document(groupId)
        // D-235 (Android audit M2): offline, the write's Task never completes (it resolves on the server's
        // acknowledgement), and "Making an invite…" spun for good; bounded, it falls to the server read, which fails
        // fast offline and says OFFLINE.
        return GroupEnsure.decide(
            create = { doc.set(CloudRecords.groupDocument(name, uid)).awaitTask() },
            read = {
                // It exists: readable means a member (the rules refuse anyone
                // else); a refusal means someone else's group with this id.
                try {
                    if (doc.get(Source.SERVER).awaitTask().exists()) GroupState.MEMBER else GroupState.OFFLINE
                } catch (e: FirebaseFirestoreException) {
                    if (e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) GroupState.NOT_MEMBER
                    else GroupState.OFFLINE
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    GroupState.OFFLINE
                }
            },
        )
    }

    // ---- D-194: down ----

    private var personalListener: ListenerRegistration? = null
    private var phoneListener: ListenerRegistration? = null
    private var groupsListener: ListenerRegistration? = null
    private var groupListeners: GroupListeners<ListenerRegistration>? = null
    private val listenLock = Any()

    /** The account's own records, the groups it is in, and each group's
     *  records, each from where this phone last left off. */
    fun listen() = synchronized(listenLock) {
        if (personalListener != null) return
        val (uid, store) = ready() ?: return
        // D-210 final B4 (and D-235's for the refused money edits): once per
        // install of this build, before any group listener starts from its cursor.
        runCatching { CloudRecords.resetGroupCursorsOnce(store) }
        // D-211: the profile share waits for this listener's first snapshot
        // ([receive] says when), then runs once per install and whatever a
        // failed try left pending (final review I1(b)).
        ProfileShare.listening(store)
        personalListener = listen(collection(uid, null), null, store)
        // D-197: invitations sent to this account.
        IncomingInvites.listen(uid)
        // D-210: what happened in its groups (a request, a removal, a role, a deletion).
        Notices.listen(uid)
        // D-198: this phone's own entry; a sign-out from elsewhere ends here.
        runCatching { CloudProfile.deviceId(store) }.getOrNull()?.let { device ->
            phoneListener = Cloud.db().collection("users").document(uid).collection("devices").document(device)
                .addSnapshotListener { snapshot, _ ->
                    val data = snapshot?.data ?: return@addSnapshotListener
                    if (data["signedOut"] != true) return@addSnapshotListener
                    scope.launch {
                        if (Phones.signedOutSince(data, Phones.authTimeMs())) {
                            val context = VaultGate.appContextRef ?: return@launch
                            withContext(Dispatchers.Main) { RemoteSignOut.happened(vaultPathOf(context)) }
                        }
                    }
                }
        }
        groupListeners = GroupListeners(start = { groupId -> listen(collection(uid, groupId), groupId, store) }, stop = { it.remove() })
        // Metadata changes too: the snapshot that says the server now holds a
        // group this phone just created changes nothing else ([GroupListeners.update]).
        groupsListener = Cloud.db().collection("groups").whereArrayContains("memberUids", uid)
            .addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, _ ->
                if (snapshot == null) return@addSnapshotListener
                val groups = snapshot.documents.map {
                    Triple(it.id, it.data ?: emptyMap(), !it.metadata.hasPendingWrites())
                }
                val fromCache = snapshot.metadata.isFromCache
                listenToGroups(groups, uid, store, fromCache)
            }
    }

    /** D-227: when to ask a group's roles. The signature is the group
     *  document's owner and admins (account ids): a change means the phones'
     *  participant-id list is stale. Guarded by [listenLock]. */
    private val roleAsking = RoleAsking()

    /** What the Firestore groups listener says: listen to each group's
     *  records, ask who owns and administers a group when that changed on
     *  another phone, and let go of a group that left the list. */
    private fun listenToGroups(
        groups: List<Triple<String, Map<String, Any?>, Boolean>>,
        uid: String,
        store: LedgerStore,
        fromCache: Boolean,
    ) {
        val askFor = mutableListOf<Pair<String, String>>()
        synchronized(listenLock) {
            if (personalListener == null) return
            val listeners = groupListeners ?: return
            val ids = groups.map { it.first }.toSet()
            // D-252: what the server lists now; a group that left it is asked about (never from the cache).
            listedNow = ids
            if (!fromCache) checkVanished(groups.map { it.first to it.third }, uid, store)
            // D-210: a group that left this account's list (removed, or deleted):
            // stop listening to its records and nothing else. The notice says why
            // and brings in what was written up to then ([handle]).
            listeners.update(groups.map { GroupListeners.Listed(it.first, confirmed = it.third) })
            for ((groupId, data, confirmed) in groups) {
                // D-210: the owner or the admins changed on another phone: ask who they are now.
                // D-227: only once the server holds the snapshot, once at a time.
                val owner = data["owner"] as? String ?: ""
                val admins = ((data["admins"] as? List<*>)?.filterIsInstance<String>() ?: emptyList()).sorted().joinToString(",")
                val signature = "$owner|$admins"
                if (roleAsking.shouldAsk(groupId, signature, confirmed)) askFor += groupId to signature
            }
            roleAsking.keep(only = ids)
        }
        for ((groupId, signature) in askFor) askRoles(groupId, signature, store)
    }

    // ---- D-252: a group that left the account's listing ----

    /** The groups the latest listing holds ([checkVanished] asks again before it marks). Guarded by [listenLock]. */
    private var listedNow: Set<String> = emptySet()

    /** Groups asked about this listening session whose answer was keep, or whose read is in flight; cleared by
     *  [stopListening], and for a group the listing holds again (it is asked again if it leaves again). Guarded by
     *  [listenLock]. */
    private val vanishAsked = mutableSetOf<String>()

    /** D-252 fix wave (M3): the listing the last check read ([GroupGone.unchanged]); a listing like it is passed by.
     *  Null: the next listing is checked (a new session, a vault read that failed, a read with no answer). Guarded by
     *  [listenLock]. */
    private var vanishListing: Map<String, Boolean>? = null

    /** D-252 (decision 218): on every listing from the server, a vanished mark lifts from a group listed again, and each
     *  held group the listing no longer has, with no notice to say why, is asked about once ([GroupGone]): a refusal or
     *  no document makes the copy read-only ("This group is no longer shared with you."), no answer changes nothing
     *  (asked again at the next listing or launch). A session's first listing is the sweep: groups that ended while
     *  this phone was away, 0.5.0's deleted groups on the deleting account's other phones included. Nothing local is
     *  ever deleted (CLAUDE.md 10). Called under [listenLock]; the vault work runs off the main thread. */
    private fun checkVanished(listed: List<Pair<String, Boolean>>, uid: String, store: LedgerStore) {
        val ids = listed.map { it.first }.toSet()
        vanishAsked.removeAll(ids)
        // D-252 fix wave (M3): every snapshot from the server comes here (metadata changes too); one like the last
        // checked is passed by (iOS's twin does the same, and reads its vault off the main thread too).
        val listing = listed.toMap()
        if (GroupGone.unchanged(listing, vanishListing)) return
        vanishListing = listing
        // The notices that say why a group ended (read here, where the screens' list is current).
        val noticed = Notices.items.filter { it.kind.endsSync }.map { it.groupId }.toSet()
        scope.launch {
            class Held(val lifted: Boolean, val again: Boolean, val held: Set<String>, val up: Set<String>, val copies: Set<String>)
            val seen = withContext(serial) {
                val marks = runCatching { store.readOnlyChecked() }.getOrNull() ?: return@withContext null
                var lifted = false
                var again = false
                for ((groupId, confirmed) in listed) {
                    if (!GroupGone.lifts(marks[groupId], listedConfirmed = confirmed)) continue
                    if (runCatching { store.clearReadOnly(groupId) }.isSuccess) lifted = true else again = true
                }
                runCatching { Held(lifted, again, store.loadGroups().map { it.id }.toSet(), CloudRecords.groupsUp(store), marks.keys) }
                    .getOrDefault(Held(lifted, true, emptySet(), emptySet(), emptySet()))
            }
            // The vault would not say (or a lift failed): the next listing is checked again.
            if (seen == null || seen.again) synchronized(listenLock) { vanishListing = null }
            if (seen == null) return@launch
            if (seen.lifted) withContext(Dispatchers.Main) { SyncStatus.changed() }
            val ask = synchronized(listenLock) {
                if (groupsListener == null) return@launch
                GroupGone.toCheck(seen.held, seen.up, ids, seen.copies, noticed, vanishAsked).also { vanishAsked += it }
            }
            for (groupId in ask) askVanished(groupId, uid, store)
        }
    }

    /** The one read for a group [checkVanished] chose: the removal's own ([groupRead]), no new server read. */
    private fun askVanished(groupId: String, uid: String, store: LedgerStore) {
        scope.launch {
            val outcome = GroupGone.decide(GroupGone.read(groupRead(groupId), uid))
            val mark = synchronized(listenLock) {
                // Signed out, or listening as someone else, while it was asked: what it said does not count.
                if (groupsListener == null || Account.uid() != uid) return@launch
                when (outcome) {
                    GroupGone.Outcome.KEEP -> return@launch
                    GroupGone.Outcome.RETRY -> {
                        vanishAsked.remove(groupId)
                        vanishListing = null
                        return@launch
                    }
                    GroupGone.Outcome.READ_ONLY -> {
                        vanishAsked.remove(groupId)
                        // Listed again meanwhile: the newer word stands.
                        groupId !in listedNow
                    }
                }
            }
            if (!mark) return@launch
            val marked = withContext(serial) {
                runCatching {
                    // A notice marked it first: the newer word stands.
                    if (!GroupGone.marks(over = store.readOnlyChecked()[groupId])) return@runCatching false
                    store.markReadOnly(groupId, GroupGone.mark(System.currentTimeMillis()))
                    true
                }.getOrDefault(false)
            }
            if (!marked) return@launch
            synchronized(listenLock) { groupListeners?.remove(groupId) }
            withContext(Dispatchers.Main) { SyncStatus.changed() }
        }
    }

    /** One `groupRoles` call the listener decided on ([RoleAsking.shouldAsk]).
     *  D-227 Design 8: it counts only when [RoleAsking.ok] says so; a signature
     *  that changed meanwhile is asked next (Decision 35). Decision 38: a failed
     *  ask is asked again on a timer too ([RoleAsking.failed]). */
    private fun askRoles(groupId: String, signature: String, store: LedgerStore) {
        scope.launch {
            var ok = false
            try {
                ok = RoleAsking.ok(GroupRoles.refreshed(store, groupId))
            } finally {
                // A not-found or offline answer is asked again on the next snapshot,
                // and on a timer while nothing else asks (Decision 38).
                var timer: RoleAsking.RetryTimer? = null
                val next = synchronized(listenLock) {
                    val pendingNext = roleAsking.finished(groupId, signature, ok)
                    // Follow-up A: only an answer that counted sets a timer ([RoleAsking.failed]).
                    if (pendingNext == null && !ok) timer = roleAsking.failed(groupId, signature)
                    pendingNext?.takeIf { roleAsking.shouldAsk(groupId, it, confirmed = true) }
                }
                if (next != null) askRoles(groupId, next, store)
                timer?.let { retryRoles(groupId, signature, it, store) }
            }
        }
    }

    /** Decision 38: the timed retry of a failed ask. It asks only while the
     *  timer is still the group's ([RoleAsking.due]: [stopListening]'s clear, the
     *  group leaving, or a successful ask cancel it), and through
     *  [RoleAsking.shouldAsk], so it is never a second ask at once. */
    private fun retryRoles(groupId: String, signature: String, timer: RoleAsking.RetryTimer, store: LedgerStore) {
        scope.launch {
            kotlinx.coroutines.delay(timer.seconds * 1000)
            val ask = synchronized(listenLock) {
                roleAsking.due(groupId, timer.token) && roleAsking.shouldAsk(groupId, signature, confirmed = true)
            }
            if (ask) askRoles(groupId, signature, store)
        }
    }

    private fun listen(collection: CollectionReference, groupId: String?, store: LedgerStore): ListenerRegistration {
        var self: ListenerRegistration? = null
        val registration = collection
            .whereGreaterThanOrEqualTo("createdAt", CloudRecords.timestamp(CloudRecords.cursor(store, groupId)))
            .orderBy("createdAt", Query.Direction.ASCENDING)
            // The personal listener also hears the change from "cache" to
            // "server" (Fix 3, M-A): when the server then holds nothing new,
            // that is the only snapshot that says this phone has caught up.
            .addSnapshotListener(if (groupId == null) MetadataChanges.INCLUDE else MetadataChanges.EXCLUDE) { snapshot, error ->
                // A refused or failed listener is over: let it go, so the
                // groups listing starts it again ([GroupListeners.failed]).
                if (error != null) {
                    val failed = self
                    if (groupId != null && failed != null) synchronized(listenLock) { groupListeners?.failed(groupId, failed) }
                    return@addSnapshotListener
                }
                val docs = snapshot?.documentChanges
                    ?.filter { it.type != com.google.firebase.firestore.DocumentChange.Type.REMOVED }
                    ?.map { it.document.data } ?: return@addSnapshotListener
                val fromCache = snapshot.metadata.isFromCache
                scope.launch { withContext(serial) { receive(docs, groupId, store, fromCache) } }
            }
        self = registration
        return registration
    }

    fun stopListening() = synchronized(listenLock) {
        // D-211: no profile share until a listener has caught up again.
        VaultGate.appContextRef?.let { ProfileShare.listening(LedgerStore(vaultPathOf(it))) }
        IncomingInvites.stop()
        Notices.stop()
        roleAsking.clear()
        // D-252: a new session asks again (its first listing is the sweep).
        vanishAsked.clear()
        listedNow = emptySet()
        vanishListing = null
        phoneListener?.remove()
        phoneListener = null
        personalListener?.remove()
        groupsListener?.remove()
        groupListeners?.clear()
        personalListener = null
        groupsListener = null
        groupListeners = null
    }

    /** Records applied since launch; for the emulator proof. */
    @Volatile var receivedCount = 0
        private set

    /** Applies what arrived: new records go into the vault, the clock moves
     *  past them, and the screens' ledger is rebuilt from the records. */
    /** No default: every caller says whether the snapshot came from the cache (D-211 Fix 4). */
    suspend fun receive(documents: List<Map<String, Any?>>, groupId: String?, store: LedgerStore, fromCache: Boolean) {
        // A listener has nobody to tell: a vault that will not take the
        // records leaves the cursor where it was, so the next snapshot
        // brings them again.
        val fresh = runCatching { CloudRecords.absorb(documents, groupId, store) }.getOrNull() ?: return
        // D-211: a joined group whose place this account was waiting for shares
        // once, when that place arrives. Nothing else that arrives (this
        // phone's own returning profiles included) shares. The personal
        // listener's first snapshot lets the share run (final review I1(b)).
        ProfileShare.afterReceive(groupId, fresh, store, fromCache)
        if (fresh.isNotEmpty()) withContext(Dispatchers.Main) { SyncStatus.changed() }
        receivedCount += fresh.size
        // D-244 (ADR-0043): the account's settings come only through its own log. The first server snapshot catches this
        // phone up (its first sign-in: the account's values win, the gaps are filled); later, what arrived is applied.
        if (groupId == null) SettingsSync.received(store, fresh = fresh.isNotEmpty(), fromCache = fromCache)
    }

    // ---- D-210: notices ----

    private val handling = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** Acts on what the cloud left for this account. A removal: bring in what
     *  was written up to it (the one read the rules allow), then keep the
     *  group as a read-only copy. A deletion: keep it as a copy. A role or a
     *  decision: ask who owns and administers the group now. A request stays:
     *  it is the card, until the request is decided (the Function deletes it).
     *  Each runs once at a time, and an offline phone leaves the notice for
     *  the next time.
     *
     *  D-210 final B3: a removal or a deletion stays in the cloud (every phone
     *  of the account must find it); this phone keeps the ids it has handled
     *  and skips them. A role or a decision is dismissed as before. B6: a
     *  removal is bounded by the server's own times, and one for a group this
     *  account has joined again is old news: never a copy. */
    fun handle(items: List<Notices.Item>) {
        val context = VaultGate.appContextRef ?: return
        val store = LedgerStore(vaultPathOf(context))
        // Off the main thread (the listener calls here): the list of handled
        // ids is a vault read.
        scope.launch {
            val handled = runCatching { withContext(serial) { Notices.handled(store) } }.getOrDefault(emptySet())
            for (item in Notices.toHandle(items, handled)) {
                if (handling.add(item.id)) handleOne(item, store)
            }
        }
    }

    private fun handleOne(item: Notices.Item, store: LedgerStore) {
        scope.launch {
            try {
                // Handled since the list was read (another snapshot's run).
                if (item.kind.keptInCloud && item.id in withContext(serial) { Notices.handled(store) }) return@launch
                // Each effect must land before the notice goes: anything
                // that fails returns here, and the notice is tried again.
                when (item.kind) {
                    Notices.Kind.REMOVED -> {
                        val uid = Account.uid() ?: return@launch
                        val read = groupRead(item.groupId) ?: return@launch
                        when (val bound = Notices.removalBound(item, uid, read)) {
                            Notices.RemovalBound.MemberAgain -> {
                                runCatching { withContext(serial) { Notices.markHandled(store, item.id) } }
                                return@launch
                            }
                            // Deleted or refused: nothing more can come; the copy is what is here.
                            Notices.RemovalBound.Gone -> Unit
                            is Notices.RemovalBound.UpTo -> if (!catchUp(item, bound.stamp, store)) return@launch
                        }
                        try {
                            withContext(serial) {
                                store.markReadOnly(
                                    item.groupId, ReadOnlyDTO("removed", item.byName, item.removedAtMs ?: item.atMs),
                                )
                            }
                        } catch (_: Exception) {
                            return@launch
                        }
                    }
                    Notices.Kind.DELETED -> {
                        // D-252: the deleting account's own notice reaches its other phones ("You deleted"); a phone
                        // whose copy already says deleted keeps it (the deleting phone's own mark), and a vanished copy
                        // says more.
                        val uid = Account.uid()
                        try {
                            withContext(serial) {
                                Notices.deletedMark(item, uid, store.readOnlyChecked()[item.groupId])?.let {
                                    store.markReadOnly(item.groupId, it)
                                }
                            }
                        } catch (_: Exception) {
                            return@launch
                        }
                    }
                    Notices.Kind.ROLE, Notices.Kind.DECLINED -> {
                        if (!GroupRoles.refresh(store, item.groupId)) return@launch
                    }
                    Notices.Kind.REQUEST -> return@launch
                }
                // A role or a decision keeps the group syncing.
                if (item.kind.endsSync) {
                    synchronized(listenLock) { groupListeners?.remove(item.groupId) }
                }
                if (item.kind.keptInCloud) {
                    try {
                        withContext(serial) { Notices.markHandled(store, item.id) }
                    } catch (_: Exception) {
                        return@launch
                    }
                } else {
                    Notices.dismiss(item)
                }
                withContext(Dispatchers.Main) { SyncStatus.changed() }
            } finally {
                handling.remove(item.id)
            }
        }
    }

    /** The group's document from the server, for a removal's bound: null when
     *  the server could not be reached (the notice waits). */
    private suspend fun groupRead(groupId: String): Notices.GroupRead? = try {
        val snapshot = Cloud.db().collection("groups").document(groupId).get(Source.SERVER).awaitTask()
        val data = snapshot.data
        if (!snapshot.exists() || data == null) Notices.GroupRead.Missing else Notices.GroupRead.Found(data)
    } catch (e: FirebaseFirestoreException) {
        Notices.groupRead(e.code.name)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    /** A removed person's last read: the group's records from where this phone
     *  left off up to the moment of removal, both bounds on the query (the
     *  rules refuse an unbounded list from a former member). False when the
     *  cloud could not be reached or the vault would not take what came, so
     *  the notice waits. Asks the server, never the cache: offline, an empty
     *  cached answer must not stand for "nothing more". */
    private suspend fun catchUp(item: Notices.Item, upper: Timestamp, store: LedgerStore): Boolean {
        return try {
            val snapshot = collection("", item.groupId)
                .whereGreaterThanOrEqualTo("createdAt", CloudRecords.timestamp(CloudRecords.cursor(store, item.groupId)))
                .whereLessThanOrEqualTo("createdAt", upper)
                .orderBy("createdAt", Query.Direction.ASCENDING)
                .get(Source.SERVER).awaitTask()
            val fresh = withContext(serial) {
                val fresh = CloudRecords.absorb(snapshot.documents.map { it.data ?: emptyMap() }, item.groupId, store)
                // A try after a rebuild that failed finds nothing new: rebuild anyway.
                if (fresh.isEmpty()) store.rebuildProjection()
                fresh
            }
            withContext(Dispatchers.Main) { SyncStatus.changed() }
            receivedCount += fresh.size
            true
        } catch (e: FirebaseFirestoreException) {
            // A refusal or a missing group will not change by asking again: the
            // copy is what is already here.
            when (e.code) {
                FirebaseFirestoreException.Code.UNAVAILABLE,
                FirebaseFirestoreException.Code.DEADLINE_EXCEEDED,
                FirebaseFirestoreException.Code.CANCELLED -> false
                else -> true
            }
        } catch (e: CancellationException) {
            // A cancelled scope is not "offline": the notice stays, nothing marked.
            throw e
        } catch (_: Exception) {
            false
        }
    }
}

/**
 * D-194: a record as a Firestore document, and back; iOS's twin is
 * CloudRecords in CloudSync.swift. The document holds the record's own fields;
 * its id is the record's identity (HLC), so an upload is idempotent. Field
 * names are the rules' (`validRecord` in firebase/firestore.rules), tested
 * against them.
 */
object CloudRecords {
    val FIELDS = listOf("type", "hlc", "authorUid", "deviceId", "createdAt", "payload", "notice")
    val GROUP_FIELDS = listOf("name", "memberUids", "createdBy", "createdAt", "emoji")
    const val UPLOADED_KEY = "sync/uploaded"
    const val GROUPS_UP_KEY = "sync/groups-up"
    private val hex32 = Regex("^[0-9a-f]{32}$")

    /** D-227 Design 8: the groups whose roles are asked again after a push: of
     *  those it uploaded records of ([pushed]), every one whose cached list
     *  ([cached]) does not name your place. The group's document goes up before
     *  its records, so the first `groupRoles` can answer before the create op
     *  that decides the creator's place is there. */
    fun rolesAfterPush(pushed: Set<String>, cached: (String) -> RoleRules.Roles?): Set<String> =
        pushed.filter { cached(it)?.places?.you.isNullOrEmpty() }.toSet()

    /** What a push sends ([outgoing]), and the pictures marked uploaded
     *  without being sent. */
    data class Outgoing(val send: List<RecordWire>, val superseded: Set<String>)

    /** What a push sends: the pending records, less a read-only group's (D-210)
     *  and a removal refused for good (D-210 final B5). D-211 (founder decision
     *  5): of the account's pictures, only the latest goes up; an earlier one
     *  still waiting (pictures set before the first sign-in, or while offline)
     *  is `superseded`: marked uploaded, never sent. A member's profile is never
     *  sent either (Fix 3, M-B): only the Function writes one, so one found here
     *  as pending (its mark lost) is `superseded` too. D-229: and so is a
     *  `placeAlias` (only shareAliases writes one). D-230: and a `heldPlace`
     *  (only sharePlaces and acceptInvite write one). D-244 (ADR-0043): and of each setting, only the latest goes up; an
     *  earlier one of the same key still waiting (the steps of one visit to Appearance, or changes made while sync was
     *  off) is `superseded`, as a picture is. */
    fun outgoing(all: List<RecordWire>, uploaded: Set<String>, readOnly: Set<String>, refused: Set<String>): Outgoing {
        val waiting = pending(all, uploaded)
            .filter { it.groupId == null || it.groupId !in readOnly }
            .filter { documentId(it) !in refused }
        val latest = all.filter(::isAvatar).maxWithOrNull(
            compareBy<RecordWire> { it.hlcMillis }.thenBy { it.hlcCounter }.thenBy { it.hlcDeviceId },
        )?.let(::documentId)
        val latestSetting = latestSettings(all)
        val superseded = waiting.filter { record ->
            profileType(record) || aliasType(record) || heldType(record) || (isAvatar(record) && documentId(record) != latest) ||
                settingKey(record)?.let { latestSetting[it] != documentId(record) } == true
        }.map(::documentId).toSet()
        return Outgoing(waiting.filter { documentId(it) !in superseded }, superseded)
    }

    /** D-244: the key of a personal `Setting` record, null for any other record. */
    fun settingKey(record: RecordWire): String? {
        if (record.recordType != LedgerStore.GROUP_OP_TYPE || record.groupId != null) return null
        return (runCatching { uniffi.skipti_core.ffiDecodeGroupOp(record.blob) }.getOrNull() as? uniffi.skipti_core.GroupOpIn.Setting)?.key
    }

    /** D-244: each key's latest setting record (the one core's projection keeps), by document id. */
    fun latestSettings(all: List<RecordWire>): Map<String, String> {
        val order = compareBy<RecordWire> { it.hlcMillis }.thenBy { it.hlcCounter }.thenBy { it.hlcDeviceId }
        val latest = mutableMapOf<String, RecordWire>()
        for (record in all) {
            val key = settingKey(record) ?: continue
            val held = latest[key]
            if (held != null && order.compare(held, record) >= 0) continue
            latest[key] = record
        }
        return latest.mapValues { documentId(it.value) }
    }

    /** A personal `Avatar` record (the account's picture). */
    fun isAvatar(record: RecordWire): Boolean =
        record.recordType == LedgerStore.GROUP_OP_TYPE && record.groupId == null &&
            runCatching { uniffi.skipti_core.ffiDecodeGroupOp(record.blob) }.getOrNull() is uniffi.skipti_core.GroupOpIn.Avatar

    fun documentId(record: RecordWire): String = "${record.hlcMillis}-${record.hlcCounter}-${record.hlcDeviceId}"

    fun isHex32(value: String): Boolean = hex32.matches(value)

    /** The records the cloud does not have yet, oldest first. A record whose
     *  device id is not this format (a pre-sync fixture) stays on the phone:
     *  the rules would refuse it. */
    fun pending(records: List<RecordWire>, uploaded: Set<String>): List<RecordWire> = records
        .filter { isHex32(it.authorDeviceId) && isHex32(it.hlcDeviceId) }
        .filter { it.groupId?.let(::isHex32) ?: true }
        .filter { it.hlcMillis <= Long.MAX_VALUE.toULong() && documentId(it) !in uploaded }
        .sortedWith(compareBy<RecordWire> { it.hlcMillis }.thenBy { it.hlcCounter })

    fun document(record: RecordWire, uid: String, notice: PushNotice.Notice? = null): Map<String, Any> = mapOf(
        "type" to record.recordType.toString(),
        "hlc" to mapOf(
            "millis" to record.hlcMillis.toLong(), "counter" to record.hlcCounter.toLong(),
            "device" to record.hlcDeviceId,
        ),
        "authorUid" to uid,
        "deviceId" to record.authorDeviceId,
        "createdAt" to FieldValue.serverTimestamp(),
        "payload" to mapOf(
            "version" to record.version.toLong(), "epoch" to record.epoch.toLong(),
            "flags" to record.flags.toLong(), "nonce" to Blob.fromBytes(record.nonceSession),
            "blob" to Blob.fromBytes(record.blob),
        ),
    ) + (
        // D-196: only a group record carries a push line (the rules refuse one
        // on a personal record).
        if (notice != null && record.groupId != null) {
            mapOf("notice" to mapOf("text" to notice.text, "expense" to notice.expense))
        } else {
            emptyMap()
        }
    )

    /** A group as the cloud first sees it: this account alone in it. Its
     *  people, names and changes travel as the group's own records. */
    fun groupDocument(name: String?, uid: String): Map<String, Any> {
        val trimmed = (name ?: "").trim()
        return mapOf(
            "name" to (if (trimmed.isEmpty()) "Group" else trimmed.take(80)),
            "memberUids" to listOf(uid), "createdBy" to uid, "createdAt" to FieldValue.serverTimestamp(),
        )
    }

    /** The only record header version a vault takes (core's `put_record`, D-235). */
    const val RECORD_VERSION = 1L

    /** The record types a vault takes (core's `wire_header`): expense 1 to redistribution 6. */
    val RECORD_TYPES = 1..6

    /** A record header's nonce, always 4 bytes (core's `wire_header`). */
    const val NONCE_BYTES = 4

    /** null for anything that is not a well-formed record. */
    fun record(data: Map<String, Any?>, groupId: String?): RecordWire? {
        // D-235 round 3 (twin of iOS): the vault refuses a header whose type it does not know or whose nonce is not 4
        // bytes, and a refused put fails the whole snapshot (the version-2 stall). Skipped like any malformed document.
        val type = (data["type"] as? String)?.toIntOrNull()?.takeIf { it in RECORD_TYPES } ?: return null
        val hlc = data["hlc"] as? Map<*, *> ?: return null
        val payload = data["payload"] as? Map<*, *> ?: return null
        val millis = (hlc["millis"] as? Long)?.takeIf { it >= 0 } ?: return null
        val counter = (hlc["counter"] as? Long)?.takeIf { it in 0..UInt.MAX_VALUE.toLong() } ?: return null
        val hlcDevice = (hlc["device"] as? String)?.takeIf(::isHex32) ?: return null
        val author = (data["deviceId"] as? String)?.takeIf(::isHex32) ?: return null
        // D-235 (wave A's L5): the vault refuses a header version other than 1, and a refused put fails the whole
        // snapshot, so one document of another version would hold its log's cursor for good. It is skipped like any
        // malformed document.
        val version = (payload["version"] as? Long)?.takeIf { it == RECORD_VERSION } ?: return null
        val epoch = (payload["epoch"] as? Long)?.takeIf { it in 0..UInt.MAX_VALUE.toLong() } ?: return null
        val flags = (payload["flags"] as? Long)?.takeIf { it in 0..255 } ?: return null
        val nonce = (payload["nonce"] as? Blob)?.toBytes()?.takeIf { it.size == NONCE_BYTES } ?: return null
        val blob = (payload["blob"] as? Blob)?.toBytes() ?: return null
        return RecordWire(
            version.toUShort(), type.toUByte(), groupId, epoch.toUInt(), millis.toULong(), counter.toUInt(),
            hlcDevice, author, nonce, flags.toUByte(), blob,
        )
    }

    // ---- D-195: what a group's log may bring in (D-158's rule, kept from the
    // relay). Every member of a group can write to its log, so a record read
    // from it must be about that group alone: never another group's money or
    // people, never the account's own "you", never a record that claims to be
    // this phone's. ----

    enum class Refusal {
        WRONG_SCOPE, WRONG_GROUP, PERSONAL, THIS_DEVICE, UNREADABLE, UNKNOWN_TYPE,
        /** D-210: a removal or a redistribution a phone wrote; only the
         *  removeMember Function writes them (`removal: true`). */
        UNVERIFIED_REMOVAL,
        /** D-211: a member's profile a phone wrote; only the shareProfile
         *  Function writes them (`profile: true`). */
        UNVERIFIED_PROFILE,
        /** D-229: a `placeAlias` a phone wrote; only the shareAliases
         *  Function writes them (`alias: true`). */
        UNVERIFIED_ALIAS,
        /** D-230: a `heldPlace` a phone wrote; only the sharePlaces and
         *  acceptInvite Functions write them (`held: true`). */
        UNVERIFIED_PLACE,
        /** D-235 (core H1, decision 103): an expense or a settlement whose money breaks core's rules (shares that do
         *  not add up, an amount past the bound, an id that is not one). A member could write one; let in, it would
         *  break export, the settle plans and Home for everyone in the group. */
        INVALID,
    }

    private val dtoJson = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    /** null when [record], read from [group]'s log, may be applied. [removal]
     *  is the document's own mark: the Function that removes a person writes
     *  their RemoveMember operation and their balance's redistribution with
     *  it, and the rules refuse the mark from a phone.
     *
     *  D-211: [profile] is the document's own mark for a member's profile: the
     *  shareProfile Function writes it, and the rules refuse the mark from a
     *  phone. Every profile in a vault is the server's word.
     *
     *  A record that claims this phone is refused, except a removal or a
     *  redistribution carrying the mark: this phone called removeMember, the
     *  Function wrote them, and saving them here failed ([absorb] takes one
     *  only when the vault does not hold it; applying is idempotent anyway).
     *  D-211: and a marked profile: this phone built it, never put it in its
     *  vault, and sees it only as it comes back from the group's log.
     *
     *  D-229: [alias] is the document's own mark for a `placeAlias`: the
     *  shareAliases Function writes it, the rules refuse the mark from a
     *  phone, and this phone's own comes back marked as a profile does.
     *
     *  D-230: [held] is the document's own mark for a `heldPlace`: the
     *  sharePlaces and acceptInvite Functions write it, the rules refuse the
     *  mark from a phone, and this phone's own comes back marked as an alias
     *  does. */
    fun refusal(
        record: RecordWire, group: String, thisDevice: String, removal: Boolean = false, profile: Boolean = false,
        alias: Boolean = false, held: Boolean = false,
    ): Refusal? {
        if (record.groupId != group) return Refusal.WRONG_SCOPE
        if (fromThisDevice(record, thisDevice) && !(removal && removalType(record)) &&
            !(profile && profileType(record)) && !(alias && aliasType(record)) && !(held && heldType(record))
        ) {
            return Refusal.THIS_DEVICE
        }
        return when (record.recordType.toInt()) {
            1 -> {
                val e = try {
                    dtoJson.decodeFromString(ExpenseDTO.serializer(), record.blob.decodeToString())
                } catch (_: Exception) {
                    return Refusal.UNREADABLE
                }
                if (e.group != group) return Refusal.WRONG_GROUP
                // D-235: core's money rules, the same every phone applies.
                if (runCatching { uniffi.skipti_core.ffiCheckExpense(e.toInput()) }.isFailure) Refusal.INVALID else null
            }
            2 -> {
                val s = try {
                    dtoJson.decodeFromString(SettlementDTO.serializer(), record.blob.decodeToString())
                } catch (_: Exception) {
                    return Refusal.UNREADABLE
                }
                if (s.group != group) return Refusal.WRONG_GROUP
                // D-235: two different people, an amount above 0 and inside the bound, a known currency.
                if (runCatching { uniffi.skipti_core.ffiCheckSettlement(s.toInput()) }.isFailure) Refusal.INVALID else null
            }
            LedgerStore.REVISION_TYPE.toInt() -> {
                // D-235 (core C1): decoded in this group's scope, new money comes back in it (before, it came back
                // with no group and every money edit in a group was refused here as another group's). Core also runs
                // its money rules on new money: a revision that breaks them does not decode.
                val r = try {
                    uniffi.skipti_core.ffiDecodeExpenseRevision(record.blob, group)
                } catch (_: Exception) {
                    return Refusal.UNREADABLE
                }
                // The expense it revises must be this group's: core checks that
                // against the record's scope. New money stays in the group.
                val money = r.money
                if (money != null && money.group != group) Refusal.WRONG_GROUP else null
            }
            LedgerStore.GROUP_OP_TYPE.toInt() -> {
                val op = try {
                    uniffi.skipti_core.ffiDecodeGroupOp(record.blob)
                } catch (_: Exception) {
                    return Refusal.UNREADABLE
                }
                val about = when (op) {
                    is uniffi.skipti_core.GroupOpIn.Create -> op.groupId
                    is uniffi.skipti_core.GroupOpIn.AddMember -> op.groupId
                    is uniffi.skipti_core.GroupOpIn.RenameMember -> op.groupId
                    is uniffi.skipti_core.GroupOpIn.RenameGroup -> op.groupId
                    is uniffi.skipti_core.GroupOpIn.RemoveMember -> {
                        if (op.groupId != group) return Refusal.WRONG_GROUP
                        return if (removal) null else Refusal.UNVERIFIED_REMOVAL
                    }
                    is uniffi.skipti_core.GroupOpIn.GroupPicture -> op.groupId
                    is uniffi.skipti_core.GroupOpIn.Claim -> op.groupId
                    // D-229: a server-verified alias counts only marked.
                    is uniffi.skipti_core.GroupOpIn.PlaceAlias -> {
                        if (op.groupId != group) return Refusal.WRONG_GROUP
                        return if (alias) null else Refusal.UNVERIFIED_ALIAS
                    }
                    // D-230: the server's word on a place counts only marked.
                    is uniffi.skipti_core.GroupOpIn.HeldPlace -> {
                        if (op.groupId != group) return Refusal.WRONG_GROUP
                        return if (held) null else Refusal.UNVERIFIED_PLACE
                    }
                    is uniffi.skipti_core.GroupOpIn.MemberProfile -> {
                        if (op.groupId != group) return Refusal.WRONG_GROUP
                        return if (profile) null else Refusal.UNVERIFIED_PROFILE
                    }
                    // D-211: your picture is personal like a device name. D-244: and so is a setting.
                    is uniffi.skipti_core.GroupOpIn.You, is uniffi.skipti_core.GroupOpIn.GroupKey,
                    is uniffi.skipti_core.GroupOpIn.Security,
                    is uniffi.skipti_core.GroupOpIn.Avatar,
                    is uniffi.skipti_core.GroupOpIn.Setting,
                    is uniffi.skipti_core.GroupOpIn.DeviceName -> return Refusal.PERSONAL
                }
                if (about == group) null else Refusal.WRONG_GROUP
            }
            LedgerStore.REDISTRIBUTION_TYPE.toInt() -> {
                val r = try {
                    uniffi.skipti_core.ffiDecodeRedistribution(record.blob)
                } catch (_: Exception) {
                    return Refusal.UNREADABLE
                }
                if (r.group != group) Refusal.WRONG_GROUP else if (removal) null else Refusal.UNVERIFIED_REMOVAL
            }
            else -> Refusal.UNKNOWN_TYPE
        }
    }

    fun fromThisDevice(record: RecordWire, thisDevice: String): Boolean =
        record.authorDeviceId == thisDevice || record.hlcDeviceId == thisDevice

    /** D-211: what the account's own log may not hold: a member's profile
     *  belongs to a group's log (the Function writes it there), never here.
     *  D-229: and so does a `placeAlias`. D-230: and a `heldPlace`. */
    fun personalRefusal(record: RecordWire): Refusal? {
        if (record.recordType != LedgerStore.GROUP_OP_TYPE) return null
        val op = runCatching { uniffi.skipti_core.ffiDecodeGroupOp(record.blob) }.getOrNull()
        return if (op is uniffi.skipti_core.GroupOpIn.MemberProfile || op is uniffi.skipti_core.GroupOpIn.PlaceAlias ||
            op is uniffi.skipti_core.GroupOpIn.HeldPlace
        ) {
            Refusal.WRONG_SCOPE
        } else {
            null
        }
    }

    /** D-229: a `placeAlias`: what the shareAliases Function writes with its mark. */
    fun aliasType(record: RecordWire): Boolean =
        record.recordType == LedgerStore.GROUP_OP_TYPE &&
            runCatching { uniffi.skipti_core.ffiDecodeGroupOp(record.blob) }.getOrNull() is uniffi.skipti_core.GroupOpIn.PlaceAlias

    /** D-230: a `heldPlace`: what the sharePlaces and acceptInvite Functions
     *  write with their mark. */
    fun heldType(record: RecordWire): Boolean =
        record.recordType == LedgerStore.GROUP_OP_TYPE &&
            runCatching { uniffi.skipti_core.ffiDecodeGroupOp(record.blob) }.getOrNull() is uniffi.skipti_core.GroupOpIn.HeldPlace

    /** D-211: a member's profile: what the shareProfile Function writes with
     *  its mark. */
    fun profileType(record: RecordWire): Boolean =
        record.recordType == LedgerStore.GROUP_OP_TYPE &&
            runCatching { uniffi.skipti_core.ffiDecodeGroupOp(record.blob) }.getOrNull() is uniffi.skipti_core.GroupOpIn.MemberProfile

    /** A redistribution, or a RemoveMember operation: what the removeMember
     *  Function writes with the mark. */
    fun removalType(record: RecordWire): Boolean = when (record.recordType) {
        LedgerStore.REDISTRIBUTION_TYPE -> true
        LedgerStore.GROUP_OP_TYPE ->
            runCatching { uniffi.skipti_core.ffiDecodeGroupOp(record.blob) }.getOrNull() is uniffi.skipti_core.GroupOpIn.RemoveMember
        else -> false
    }

    /** Documents from one collection into the vault: the records it may take
     *  go in, the clock moves past them, the cursor moves on and the screens'
     *  ledger is rebuilt (whenever any record came). Returns the records that
     *  were new; throws when the vault would not take them or the ledger would
     *  not rebuild (the cursor then stays where it was). */
    fun absorb(documents: List<Map<String, Any?>>, groupId: String?, store: LedgerStore): List<RecordWire> {
        val arrived = mutableListOf<RecordWire>()
        var newest = cursor(store, groupId)
        val thisDevice = runCatching { LedgerStore.Sync.deviceId(store) }.getOrDefault("")
        // This phone's own removal records, already in the vault: an echo.
        var held: Set<String>? = null
        for (data in documents) {
            // Our own write echoes back before the server stamps it.
            val stamp = data["createdAt"] as? Timestamp ?: continue
            newest = maxOf(newest, millis(stamp))
            val record = record(data, groupId) ?: continue
            // D-195: other people write a group's log; only that group's own
            // records may come in through it.
            // D-210: a removal or a redistribution counts only when a Function wrote it (`removal: true`).
            val removal = data["removal"] == true
            // D-211: and a member's profile counts only when shareProfile wrote it (`profile: true`).
            val profile = data["profile"] == true
            // D-229: and a placeAlias only when shareAliases wrote it (`alias: true`).
            val alias = data["alias"] == true
            // D-230: and a heldPlace only when sharePlaces or acceptInvite wrote it (`held: true`).
            val heldMark = data["held"] == true
            if (groupId != null) {
                if (refusal(record, groupId, thisDevice, removal, profile, alias, heldMark) != null) continue
            } else if (personalRefusal(record) != null) {
                continue
            }
            if (fromThisDevice(record, thisDevice)) {
                if (held == null) held = store.allRecords().map { LedgerStore.Sync.recordIdentity(it) }.toSet()
                if (LedgerStore.Sync.recordIdentity(record) in held) continue
            }
            arrived += record
        }
        val fresh = store.applyRecords(arrived)
        val now = System.currentTimeMillis()
        for (record in fresh) {
            runCatching { LedgerStore.Sync.receiveHlc(store, record.hlcMillis, record.hlcCounter, now) }
        }
        runCatching { saveUploaded(store, uploaded(store) + arrived.map { documentId(it) }) }
        if (groupId != null) runCatching { saveGroupsUp(store, groupsUp(store) + groupId) }
        // Whenever anything came, not only when something was new: a rebuild
        // that threw last time left its records in the vault and the cursor
        // behind, so the next snapshot finds them present ("not fresh") and
        // would otherwise never rebuild. No flag to persist, and a snapshot
        // carries only what is past the cursor (plus our own echoes).
        if (arrived.isNotEmpty()) store.rebuildProjection()
        runCatching { saveCursor(store, groupId, newest) }
        return fresh
    }

    /** D-159 (kept from the relay): every group this account is in carries its
     *  claim on its own place (a joiner's is written when joining, a
     *  creator's here), once; without it the other members' phones would
     *  offer to invite the creator. The claim's device key is unused since
     *  ADR-0040 ([Invites.claim]).
     *
     *  D-229: the claim names the PLACE core found ([place]: the raw member
     *  that is this account's there), with "you" as its `you`. Before, it named
     *  "you" itself, which in a group made under another of the account's ids
     *  is no member: core kept it pending and every push wrote another. A
     *  claim on the place lands, and a group made under the other phone's id
     *  then reads "you" as that place on every phone (D-158's alias). */
    fun ownClaims(p: uniffi.skipti_core.GroupsProjectionOut, device: String): List<uniffi.skipti_core.GroupOpIn> {
        val you = p.you ?: return emptyList()
        val mine = (listOf(you.id) + p.otherYouIds).toSet()
        return p.groups.mapNotNull { g ->
            val place = g.place
            if (g.claims.any { it.youId in mine } || place == null) null
            else Invites.claim(g.group.id, place, you.id, device)
        }
    }

    private fun idSet(store: LedgerStore, key: String): Set<String> =
        store.rawKvGet(key)?.decodeToString()?.split("\n")?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()

    private fun saveIdSet(store: LedgerStore, key: String, ids: Set<String>) {
        store.rawKvSet(key, ids.sorted().joinToString("\n").toByteArray(Charsets.UTF_8))
    }

    fun uploaded(store: LedgerStore): Set<String> = idSet(store, UPLOADED_KEY)
    fun saveUploaded(store: LedgerStore, ids: Set<String>) = saveIdSet(store, UPLOADED_KEY, ids)

    /** The groups this phone knows are in the cloud with this account in them. */
    fun groupsUp(store: LedgerStore): Set<String> = idSet(store, GROUPS_UP_KEY)
    fun saveGroupsUp(store: LedgerStore, ids: Set<String>) = saveIdSet(store, GROUPS_UP_KEY, ids)

    /** Milliseconds of the newest server time seen in one collection; its
     *  listener starts there (inclusive: a record in the same millisecond is
     *  applied twice, which is a no-op). */
    fun cursorKey(groupId: String?): String = "sync/cursor/" + (groupId ?: "personal")

    fun cursor(store: LedgerStore, groupId: String?): Long =
        runCatching { store.rawKvGet(cursorKey(groupId))?.decodeToString()?.toLongOrNull() }.getOrNull() ?: 0L

    fun saveCursor(store: LedgerStore, groupId: String?, millis: Long) {
        store.rawKvSet(cursorKey(groupId), millis.toString().toByteArray(Charsets.UTF_8))
    }

    // ---- D-210 final B4: group cursors start again, once ----

    const val GROUP_CURSORS_RESET_KEY = "sync/migrations/group-cursors-d210"

    /** D-235 (core C1): every money edit made in a group since D-195 was refused at ingest (its new money decoded
     *  with no group, read as another group's), and the cursor moved past it. Its own mark, so a phone that already
     *  ran D-210's reset runs this one too. */
    const val GROUP_CURSORS_RESET_D235_KEY = "sync/migrations/group-cursors-d235"

    /** Every reset, oldest first: each runs once per install. */
    val GROUP_CURSOR_RESETS = listOf(GROUP_CURSORS_RESET_KEY, GROUP_CURSORS_RESET_D235_KEY)

    /** 0.4.0 skipped records it did not know (redistributions, type 6) and
     *  moved its cursors past them (D-210); and every phone refused the
     *  group's money edits until D-235. Once per install of a build with a
     *  reset this phone has not run, every group's cursor goes back to 0, so
     *  the listeners fetch those records again (records already here are
     *  no-ops). The personal cursor stays. True when it ran now. */
    fun resetGroupCursorsOnce(store: LedgerStore): Boolean {
        val owed = GROUP_CURSOR_RESETS.filter { store.rawKvGet(it)?.isNotEmpty() != true }
        if (owed.isEmpty()) return false
        val groups = (store.loadGroups().map { it.id } + groupsUp(store)).toSortedSet()
        for (groupId in groups) saveCursor(store, groupId, 0)
        for (key in owed) store.rawKvSet(key, "1".toByteArray(Charsets.UTF_8))
        return true
    }

    // ---- D-210 final B5: removals made before a group was shared ----

    /** What `adoptRemovals` said: in (or already in), refused for good, or try
     *  again later. */
    enum class AdoptOutcome { DONE, REFUSED, RETRY }

    const val ADOPT_REFUSED_KEY = "sync/adopt-refused"

    // ---- D-235 round 2 (wave B review I2): a record the rules refuse never stops sync ----

    /** The records the rules refused for good (a note past the size cap, a stamp more than a day ahead of the
     *  server's clock): kept on this phone, never sent again. */
    const val UPLOAD_REFUSED_KEY = "sync/upload-refused"

    fun uploadRefused(store: LedgerStore): Set<String> = idSet(store, UPLOAD_REFUSED_KEY)

    // ---- D-235 round 4: marks added to what is stored (M7), parked records tried again (decision 114) ----

    /** Adds [ids] to the marks as stored NOW (read just before the write, nothing suspends between), so marks another
     *  writer saved meanwhile stay. Nothing to add writes nothing. The twin of iOS's `addUploaded`. */
    fun addUploaded(store: LedgerStore, ids: Set<String>) {
        if (ids.isEmpty()) return
        saveUploaded(store, uploaded(store) + ids)
    }

    fun addGroupsUp(store: LedgerStore, ids: Set<String>) {
        if (ids.isEmpty()) return
        saveGroupsUp(store, groupsUp(store) + ids)
    }

    fun addAdoptRefused(store: LedgerStore, ids: Set<String>) {
        if (ids.isEmpty()) return
        saveAdoptRefused(store, adoptRefused(store) + ids)
    }

    /** Decision 114: a parked record's tries after it was parked, and when it was last refused. */
    data class Retry(val tries: Int, val lastMs: Long)

    const val UPLOAD_RETRIES_KEY = "sync/upload-retries"
    const val RETRY_EVERY_MS = 24L * 60 * 60 * 1000
    const val RETRY_MAX = 3

    /** A parked record is due while it has had fewer than [RETRY_MAX] tries, once a day after its last refusal, or at
     *  once when this phone's clock has gone back below that refusal (a fast clock that was fixed: its stamp may be
     *  acceptable now). One parked before this build (no entry) is due now. Round 5: iOS's rule exactly (its
     *  `refusals <= 3` counts the first refusal, so it is `tries < 3` here). */
    fun retryDue(retry: Retry?, nowMs: Long): Boolean {
        val r = retry ?: return true
        return r.tries < RETRY_MAX && (nowMs < r.lastMs || nowMs - r.lastMs >= RETRY_EVERY_MS)
    }

    /** The entry after a refusal: a first refusal parks it with no tries; a refusal of a parked record counts one. */
    fun afterRefusal(retry: Retry?, wasParked: Boolean, nowMs: Long): Retry =
        if (!wasParked) Retry(0, nowMs) else Retry((retry?.tries ?: 0) + 1, nowMs)

    /** The parked records not due again at [nowMs] (iOS's `heldParked`): what a push and `syncNow` leave out. A vault
     *  that cannot be read holds nothing back. */
    fun heldParked(store: LedgerStore, nowMs: Long): Set<String> = runCatching {
        val retries = uploadRetries(store)
        uploadRefused(store).filterNot { retryDue(retries[it], nowMs) }.toSet()
    }.getOrDefault(emptySet())

    /** What a push sends: every waiting record, except a parked one that is not due. */
    fun sendable(waiting: List<RecordWire>, parked: Set<String>, retries: Map<String, Retry>, nowMs: Long): List<RecordWire> =
        waiting.filter { val id = documentId(it); id !in parked || retryDue(retries[id], nowMs) }

    fun uploadRetries(store: LedgerStore): Map<String, Retry> =
        store.rawKvGet(UPLOAD_RETRIES_KEY)?.decodeToString()?.split("\n")?.mapNotNull { line ->
            val parts = line.split(" ")
            val tries = parts.getOrNull(1)?.toIntOrNull()
            val last = parts.getOrNull(2)?.toLongOrNull()
            if (parts.size == 3 && tries != null && last != null) parts[0] to Retry(tries, last) else null
        }?.toMap() ?: emptyMap()

    /** Parks [refused] (with their entries) and lets [cleared] go, against what is stored now. */
    fun updateParked(store: LedgerStore, refused: Map<String, Retry>, cleared: Set<String>) {
        if (refused.isEmpty() && cleared.isEmpty()) return
        saveIdSet(store, UPLOAD_REFUSED_KEY, uploadRefused(store) + refused.keys - cleared)
        val retries = uploadRetries(store) + refused - cleared
        store.rawKvSet(
            UPLOAD_RETRIES_KEY,
            retries.toSortedMap().entries.joinToString("\n") { (id, r) -> "$id ${r.tries} ${r.lastMs}" }.toByteArray(Charsets.UTF_8),
        )
    }

    fun saveUploadRefused(store: LedgerStore, ids: Set<String>) = saveIdSet(store, UPLOAD_REFUSED_KEY, ids)

    /** Decision 122 (the founder): whether any of this phone's records in [group] is parked, due again or not; the
     *  group's page then says so in one quiet line. The group is the record's own ([RecordWire.groupId]); a personal
     *  record is in none. iOS's twin: `CloudRecords.parkedIn`. */
    fun parkedIn(group: String, records: List<RecordWire>, parked: Set<String>): Boolean =
        parked.isNotEmpty() && records.any { it.groupId == group && documentId(it) in parked }

    /** The group page's read: the parked set first (empty, the usual case, reads no record). */
    fun hasParked(store: LedgerStore, group: String): Boolean {
        val ids = uploadRefused(store)
        if (ids.isEmpty()) return false
        return parkedIn(group, store.allRecords(), ids)
    }

    enum class UploadFailure { UPLOADED, REFUSED, STOP }

    /** What the server answered when asked for a record's document after a failed write: it holds it ([YES]), it does
     *  not ([NO]), the rules refused the read ([DENIED]: not a member of the record's group), or no answer
     *  ([UNKNOWN]: offline, signed out, the server busy). */
    enum class Held { YES, NO, DENIED, UNKNOWN }

    /** The existence read: [read] asks the server; a permission denial ([isDenied]) is [Held.DENIED] only while
     *  this phone is still signed in as the pushing account ([signedIn]); signed out, nothing is asked. */
    suspend fun held(
        signedIn: Boolean,
        isDenied: (Exception) -> Boolean = ::isPermissionDenied,
        read: suspend () -> Boolean,
    ): Held {
        if (!signedIn) return Held.UNKNOWN
        return try {
            if (read()) Held.YES else Held.NO
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (isDenied(e)) Held.DENIED else Held.UNKNOWN
        }
    }

    /** What one failed write means. [held]: what the server said about the document. Records are never overwritten,
     *  so a refused write of a document the server holds is an upload done before. A refusal by the rules
     *  ([refusedByRules]: the server answered) of one it does not hold is this record, refused for good. Look fix F4:
     *  a group's record ([inGroup]) whose write AND read were both permission-denied ([writeDenied], [Held.DENIED]) is
     *  for a group this account was removed from: refused too (parked), so it holds back no other group's records.
     *  Anything else (offline, signed out, the server busy, no answer to the read) stops the push. */
    fun uploadFailure(refusedByRules: Boolean, writeDenied: Boolean, inGroup: Boolean, held: Held): UploadFailure = when {
        held == Held.YES -> UploadFailure.UPLOADED
        held == Held.NO && refusedByRules -> UploadFailure.REFUSED
        held == Held.DENIED && writeDenied && inGroup -> UploadFailure.REFUSED
        else -> UploadFailure.STOP
    }

    /** The rules refused it: PERMISSION_DENIED alone (not a document past Firestore's limits, not auth or network). */
    fun isPermissionDenied(e: Exception): Boolean {
        val code = (e as? FirebaseFirestoreException)?.code ?: return false
        return code == FirebaseFirestoreException.Code.PERMISSION_DENIED
    }

    /** The server refused the write by its rules: PERMISSION_DENIED (a rule said no) or INVALID_ARGUMENT (a document
     *  past Firestore's own limits). UNAUTHENTICATED, UNAVAILABLE and the rest are not: they pass. */
    fun isRuleRefusal(e: Exception): Boolean {
        val code = (e as? FirebaseFirestoreException)?.code ?: return false
        return code == FirebaseFirestoreException.Code.PERMISSION_DENIED || code == FirebaseFirestoreException.Code.INVALID_ARGUMENT
    }

    /** [uploaded]: written now or already there; [written]: how many of them this push wrote; [refused]: refused for
     *  good; [stopped]: a failure that ends this push (the records after it wait for the next). */
    data class OneByOne(val uploaded: List<RecordWire>, val written: Int, val refused: List<RecordWire>, val stopped: Boolean)

    /** A batch the server refused, sent again one record at a time, so one record the rules refuse holds back
     *  nothing after it. */
    suspend fun oneByOne(
        chunk: List<RecordWire>,
        write: suspend (RecordWire) -> Unit,
        exists: suspend (RecordWire) -> Held,
        refusedByRules: (Exception) -> Boolean = ::isRuleRefusal,
        deniedByRules: (Exception) -> Boolean = ::isPermissionDenied,
    ): OneByOne {
        val uploaded = mutableListOf<RecordWire>()
        val refused = mutableListOf<RecordWire>()
        var written = 0
        for (record in chunk) {
            try {
                write(record)
                written += 1
                uploaded += record
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                when (uploadFailure(refusedByRules(e), deniedByRules(e), record.groupId != null, exists(record))) {
                    UploadFailure.UPLOADED -> uploaded += record
                    UploadFailure.REFUSED -> refused += record
                    UploadFailure.STOP -> return OneByOne(uploaded, written, refused, stopped = true)
                }
            }
        }
        return OneByOne(uploaded, written, refused, stopped = false)
    }

    data class Adoption(val direct: List<RecordWire>, val units: List<List<RecordWire>>)

    /** The pending records, split: the ones written directly, and the removals
     *  (a RemoveMember operation with that person's redistributions, one unit
     *  per person, oldest first) that only `adoptRemovals` may write. Records
     *  the Function refused for good stay on this phone ([refused]). */
    fun adoptionUnits(pending: List<RecordWire>, refused: Set<String> = emptySet()): Adoption {
        val direct = mutableListOf<RecordWire>()
        // (group, person) -> the unit's records, in the order they came (HLC).
        val units = linkedMapOf<String, MutableList<RecordWire>>()
        for (record in pending) {
            val groupId = record.groupId
            if (groupId == null || !removalType(record)) {
                direct += record
                continue
            }
            if (documentId(record) in refused) continue
            val person = if (record.recordType == LedgerStore.REDISTRIBUTION_TYPE) {
                runCatching { uniffi.skipti_core.ffiDecodeRedistribution(record.blob).from }.getOrDefault("")
            } else {
                (runCatching { uniffi.skipti_core.ffiDecodeGroupOp(record.blob) }.getOrNull()
                    as? uniffi.skipti_core.GroupOpIn.RemoveMember)?.memberId ?: ""
            }
            units.getOrPut("$groupId/$person") { mutableListOf() } += record
        }
        // The callable takes 50 records at most: a larger unit (more than 49
        // currencies) goes in pieces, oldest first.
        return Adoption(direct, units.values.flatMap { it.chunked(50) })
    }

    /** The `adoptRemovals` callable's data for one unit. */
    fun adoptData(groupId: String, unit: List<RecordWire>): kotlinx.serialization.json.JsonObject =
        kotlinx.serialization.json.buildJsonObject {
            put("groupId", kotlinx.serialization.json.JsonPrimitive(groupId))
            put("records", Removal.payload(unit))
        }

    /** In (ALREADY_EXISTS: an earlier try got there), refused for good (the
     *  group has others in it now, is gone, or will not take these), or
     *  offline and tried again on the next push. */
    fun adoptOutcome(error: Throwable?): AdoptOutcome {
        if (error == null) return AdoptOutcome.DONE
        val status = (error as? CloudFunctions.Failure.Refused)?.status ?: return AdoptOutcome.RETRY
        return when (status) {
            "ALREADY_EXISTS" -> AdoptOutcome.DONE
            "FAILED_PRECONDITION", "PERMISSION_DENIED", "NOT_FOUND", "INVALID_ARGUMENT" -> AdoptOutcome.REFUSED
            else -> AdoptOutcome.RETRY
        }
    }

    fun adoptRefused(store: LedgerStore): Set<String> = idSet(store, ADOPT_REFUSED_KEY)

    fun saveAdoptRefused(store: LedgerStore, ids: Set<String>) = saveIdSet(store, ADOPT_REFUSED_KEY, ids)

    fun millis(stamp: Timestamp): Long = stamp.seconds * 1000 + stamp.nanoseconds / 1_000_000

    fun timestamp(millis: Long): Timestamp = Timestamp(millis / 1000, ((millis % 1000) * 1_000_000).toInt())
}

/**
 * Which groups' records this phone listens to, and the registrations. [R] is a
 * Firestore ListenerRegistration in the app, anything in a test.
 */
class GroupListeners<R : Any>(private val start: (String) -> R, private val stop: (R) -> Unit) {
    /** A group in the account's list. [confirmed]: the server holds it (the
     *  listing has no pending write of this phone's for it). */
    data class Listed(val id: String, val confirmed: Boolean)

    private val live = mutableMapOf<String, R>()

    operator fun contains(groupId: String): Boolean = groupId in live

    /** The account's list changed: listen to each listed group the server
     *  holds, let go of a group that left the list. A group this phone has
     *  just created is listed at once, from its own pending write; the
     *  server refuses a records listener until the group is there, and a
     *  refused listener is over for good. It starts when the listing says the
     *  server has it. */
    fun update(groups: List<Listed>) {
        val ids = groups.map { it.id }.toSet()
        for (group in groups) {
            if (group.confirmed && group.id !in live) live[group.id] = start(group.id)
        }
        for (groupId in live.keys.toList()) {
            if (groupId !in ids) live.remove(groupId)?.let(stop)
        }
    }

    /** A group's listener ended with an error (Firestore does not resume
     *  it): let it go, so the next listing starts it again. A late report
     *  from a listener already replaced leaves the new one. */
    fun failed(groupId: String, registration: R) {
        if (live[groupId] != registration) return
        live.remove(groupId)
        stop(registration)
    }

    fun remove(groupId: String) {
        live.remove(groupId)?.let(stop)
    }

    fun clear() {
        live.values.forEach(stop)
        live.clear()
    }
}

/**
 * What screens watch to read the ledger again: [revision] goes up whenever
 * something arrived or changed (D-178's shape; unit 4's listeners bump it).
 */
object SyncStatus {
    /** D-191: creates this object's Compose state on the main thread, outside
     *  any composition; MainActivity.onCreate calls it before the first frame. */
    fun createState() = Unit

    var revision by mutableIntStateOf(0)
        private set

    /** A sync that pull to sync started is running. */
    var syncing by mutableStateOf(false)
        private set

    /** Something the screens show changed. */
    fun changed() {
        revision++
    }

    /** A pull to refresh (D-194): sends what is waiting; the listeners bring
     *  the rest. */
    suspend fun syncNow(vaultPath: String, pulled: Boolean = false) {
        CloudSync.syncNow()
    }
}

/** Hex codec, the port of iOS's HexCodec (group pictures' JPEG bytes). */
object HexCodec {
    private val digits = "0123456789abcdef"

    fun encode(data: ByteArray): String {
        val out = StringBuilder(data.size * 2)
        for (b in data) {
            val v = b.toInt() and 0xff
            out.append(digits[v ushr 4]).append(digits[v and 0x0f])
        }
        return out.toString()
    }

    fun decode(string: String): ByteArray {
        val chars = string.toCharArray()
        require(chars.size % 2 == 0) { "hex string has odd length" }
        val out = ByteArray(chars.size / 2)
        for (i in out.indices) {
            out[i] = ((hexValue(chars[i * 2]) shl 4) or hexValue(chars[i * 2 + 1])).toByte()
        }
        return out
    }

    private fun hexValue(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> throw IllegalArgumentException("bad hex digit: $c")
    }
}

/** D-235 (Android audit M2): whether a group is in the cloud with this account in it, in bounded time. The create is
 *  tried first (a new group's only way up); when it fails or runs out of [createMs], the server is asked, for at most
 *  [readMs]; no answer is OFFLINE. Pure over its two calls: the JVM tests pin it with calls that never return. */
object GroupEnsure {
    const val CREATE_TIMEOUT_MS = 8_000L
    const val READ_TIMEOUT_MS = 8_000L

    suspend fun decide(
        create: suspend () -> Unit,
        read: suspend () -> CloudSync.GroupState,
        createMs: Long = CREATE_TIMEOUT_MS,
        readMs: Long = READ_TIMEOUT_MS,
    ): CloudSync.GroupState {
        if (Bounded.run(createMs, create)) return CloudSync.GroupState.MEMBER
        return kotlinx.coroutines.withTimeoutOrNull(readMs) { read() } ?: CloudSync.GroupState.OFFLINE
    }
}

/** D-235 round 4 (shells review M9; iOS's `InstallSession.owned`): the signed-in account, when it is the vault's own
 *  (the stored owner, written at sign-in). No owner, an unreadable one, or another account: null, and nothing syncs. */
object CloudGate {
    fun owned(uid: String?, owner: String?): String? = if (uid != null && owner == uid) uid else null
}
