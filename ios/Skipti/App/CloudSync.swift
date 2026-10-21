import FirebaseFirestore
import Foundation
import Observation

/// D-192: the one seam between the ledger and the cloud; every ledger change
/// and every return to the front comes through here. D-194 (the Firebase
/// plan's unit 4): the personal ledger syncs. Each record in the vault (an
/// expense, a settlement, an edit, a group operation or "you") is one
/// Firestore document under `users/{uid}/records` or its group's
/// `groups/{g}/records`, uploaded once; a snapshot
/// listener brings the account's other phones' records down, and core's own
/// merge applies them exactly as it always has. The vault stays the database
/// every screen reads. A group goes up with this account its only member
/// (others join through invites, unit 5). Android's twin is
/// CloudSync.kt.
@MainActor
final class CloudSync {
    static let shared = CloudSync()

    /// The hook every ledger mutation calls after persisting locally.
    func ledgerChanged() { push() }

    /// SKIPTI came to the front (listeners on) or left it (listeners off).
    func appIsActive(_ active: Bool) {
        if active {
            // D-235 round 4 (I1): a token delete that could not finish is tried again when no one is signed in.
            if LaunchWork.startsCloud(), Account.shared.uid == nil { Push.shared.settleOwed() }
            registerPhone()
            push()
            listen()
        } else {
            stopListening()
        }
    }

    /// The sync switch changed (Settings). D-196: pushes come with sync.
    func syncSwitched() {
        stopListening()
        // D-211: a test host never stops push (it has no cloud work to stop).
        if LaunchWork.startsCloud(), Account.shared.uid != nil, !Self.syncEnabled(store) {
            Task { await Push.shared.stop(inCloud: true) }
        }
        appIsActive(true)
    }

    // GUARANTEES #5: sync is on with an account and off in one tap. The
    // switch is this phone's own (the vault's `sync/enabled`, absent = on);
    // the same key the relay's sync used, so a phone keeps its choice.
    nonisolated static let syncEnabledKey = "sync/enabled"

    nonisolated static func syncEnabled(_ store: LedgerStore) -> Bool {
        guard let raw = try? store.kvGet(key: syncEnabledKey) else { return true }
        return String(decoding: raw, as: UTF8.self) != "0"
    }

    nonisolated static func setSyncEnabled(_ store: LedgerStore, _ enabled: Bool) throws {
        try store.kvSet(key: syncEnabledKey, value: Data((enabled ? "1" : "0").utf8))
    }

    private var store: LedgerStore { .defaultStore() }

    /// Signed in, and sync on: the account and the vault to sync.
    private func ready() -> (uid: String, store: LedgerStore)? { ready(uid: Account.shared.uid) }

    /// `ready()` with the signed-in account given (tests). D-235 (C2, decision 107): and only when that account is
    /// the vault's owner, so a session this vault was never signed in to syncs nothing.
    func ready(uid: String?) -> (uid: String, store: LedgerStore)? {
        let store = self.store
        guard let uid = LaunchWork.signedInAccount(uid: uid, syncOn: Self.syncEnabled(store),
                                                   owner: Account.storedOwner(store)) else { return nil }
        return (uid, store)
    }

    // MARK: - D-193: the profile and this phone's entry, once per launch per account

    private var registered: String?
    /// D-235 (C4, iOS L3): one registration at a time. The sign-in screen's username step used to register the
    /// profile itself while this one ran; both saw no profile and both created it, the rules refused the second as an
    /// update, and when that was this one, push and the findable flag waited for the next return to the front.
    private let registrations = SingleFlight<CloudProfile.Outcome>()

    /// After a sign-in, and each time SKIPTI comes to the front.
    func registerPhone() {
        // D-211: a test host registers nothing (see LaunchWork). D-235 (C2): nor does a session this vault does not own.
        guard LaunchWork.startsCloud(), let uid = Account.shared.uid, Account.storedOwner(store) == uid,
              !registrations.isRunning(uid) else { return }
        if registered == uid {
            if Self.syncEnabled(store) { Push.shared.start() }
            return
        }
        Task { _ = await registration(uid: uid) }
    }

    /// The profile and this phone's entry for `uid`: the registration in flight, or a new one; `.registered` at once
    /// when this launch has already registered. What follows a registration (push, the findable flag, a remote
    /// sign-out) runs once, inside it.
    func registration(uid: String) async -> CloudProfile.Outcome {
        let store = self.store
        guard LaunchWork.startsCloud(), Account.storedOwner(store) == uid else {
            return .failed("This phone's ledger belongs to another account.")
        }
        if registered == uid { return .registered }
        return await registrations.run(uid) {
            let outcome = await CloudProfile.register(uid: uid, store: store)
            let sync = CloudSync.shared
            if outcome == .registered { sync.registered = uid }
            // D-198: signed out from another phone (or Sign out everywhere).
            if outcome == .signedOutRemotely {
                await Push.shared.stop(inCloud: false)
                RemoteSignOut.shared.happened()
                return outcome
            }
            // D-196: this phone's push address, once its entry exists.
            if sync.registered == uid, Self.syncEnabled(store) { Push.shared.start() }
            // D-197: whether people can find this account (on unless switched off).
            if sync.registered == uid { Task { await People.syncFindable(uid: uid, store: store) } }
            return outcome
        }
    }

    func accountSignedOut() {
        registered = nil
        stopListening()
        // D-196: FCM's token is deleted, so no push reaches this phone for
        // the account (Settings' sign-out also removes it from the cloud
        // first, while it still may).
        Task { await Push.shared.stop(inCloud: false) }
    }

    // MARK: - D-194: up

    private var pushing = false
    private var pushAgain = false
    /// Records uploaded by the last push; for the emulator proof.
    private(set) var lastPushed = 0

    /// Uploads every record the cloud does not have yet. One push at a time;
    /// a change during a push runs another after it.
    func push() {
        guard ready() != nil else { return }
        if pushing {
            pushAgain = true
            return
        }
        pushing = true
        Task {
            repeat {
                pushAgain = false
                guard let (uid, store) = ready() else { break }
                lastPushed = await Self.pushAll(uid: uid, store: store)
            } while pushAgain
            pushing = false
        }
    }

    /// A pull to refresh: the listeners already bring records down; this
    /// sends what is waiting. True when nothing of `group` (of anything, with
    /// no group) is left waiting afterwards (D-210 final B6: Settle first
    /// says whether its payments went up before the removal).
    @discardableResult
    func syncNow(group: String? = nil) async -> Bool {
        guard let (uid, store) = ready() else { return false }
        lastPushed = await Self.pushAll(uid: uid, store: store)
        guard let all = try? store.allRecords(), let uploaded = try? CloudRecords.uploaded(store) else { return false }
        let refused = ((try? CloudRecords.adoptRefused(store)) ?? []).union(CloudRecords.heldParked(store, nowMs: CloudRecords.nowMs()))
        return !CloudRecords.pending(all, uploaded: uploaded)
            .contains { (group == nil || $0.groupId == group) && !refused.contains(CloudRecords.documentId($0)) }
    }

    /// Where a record lives: the account's own records (personal scope) or
    /// its group's.
    static func collection(uid: String, groupId: String?) -> CollectionReference {
        guard let groupId else { return Cloud.db.collection("users").document(uid).collection("records") }
        return Cloud.db.collection("groups").document(groupId).collection("records")
    }

    /// Writes in batches; a batch the rules refuse (a document that already
    /// exists: records are never overwritten) is retried one by one, and a
    /// document found to exist counts as uploaded. A group is created in the
    /// cloud, with this account its only member, before its first record.
    /// Offline, it stops and the next push carries on. Returns how many
    /// records were uploaded.
    static func pushAll(uid: String, store: LedgerStore) async -> Int {
        // D-195: this account's claim on its own place in every group it is
        // in, so the other members' phones know that person has joined.
        if let p = try? LedgerStore.Sync.projectGroups(store), let device = try? LedgerStore.Sync.deviceId(store) {
            try? LedgerStore.Sync.appendGroupOps(store, CloudRecords.ownClaims(p, device: device), placing: .none)
        }
        guard let all = try? store.allRecords(), var uploaded = try? CloudRecords.uploaded(store),
              var groupsUp = try? CloudRecords.groupsUp(store) else { return 0 }
        let uploadedBefore = uploaded
        // D-210: a group that is a read-only copy (removed from it, or it was
        // deleted) takes nothing up; what is pending there stays on the phone.
        let readOnly = Set(store.readOnly().keys)
        // D-210 final B5: a removal adoptRemovals refused for good stays here.
        let refusedBefore = (try? CloudRecords.adoptRefused(store)) ?? []
        // D-235 round 2: and a record the rules refused for good is never sent again.
        // Decision 114: a parked record comes back once a day, three times at most (`CloudRecords.due`).
        let uploadRefused = CloudRecords.heldParked(store, nowMs: CloudRecords.nowMs())
        // D-211: only the latest of the account's pictures goes up.
        let out = CloudRecords.outgoing(all, uploaded: uploaded, readOnly: readOnly, refused: refusedBefore.union(uploadRefused))
        var pending = out.send
        // D-235 (C4, iOS M2): every save below adds what this push did to the marks as they are NOW: a listener's
        // `absorb` runs in this push's awaits and adds its own, which a save of this push's stale copy used to drop.
        if !out.superseded.isEmpty {
            uploaded.formUnion(out.superseded)
            try? CloudRecords.addUploaded(store, out.superseded)
        }
        guard !pending.isEmpty else {
            // D-229: nothing waits, so every group this phone knows is up has its records there: the aliases go now.
            await AliasShare.share(store: store)
            // D-230: then the places this account holds, for the server to mark.
            await PlaceShare.share(store: store)
            return 0
        }
        // Groups first: a record can only be written by a member.
        let names = Dictionary(((try? store.loadGroups()) ?? []).map { ($0.id, $0.name) }, uniquingKeysWith: { a, _ in a })
        var unavailable = Set<String>()
        let upBefore = groupsUp
        for groupId in Set(pending.compactMap(\.groupId)) where !groupsUp.contains(groupId) {
            switch await ensureGroup(groupId, name: names[groupId], uid: uid) {
            case .member: groupsUp.insert(groupId)
            case .notMember: unavailable.insert(groupId)
            case .offline: return 0
            }
        }
        try? CloudRecords.addGroupsUp(store, groupsUp.subtracting(upBefore))
        // D-211: a group that has just reached the cloud: your name and
        // preview go to it once its records are up (end of this push).
        let gainedGroups = groupsUp.subtracting(upBefore)
        // D-227 follow-up D (look F2's twin): an open page of such a group reads it again now (People turns from the
        // phone-only layout to the cloud's), not on its next arrival or share.
        if !gainedGroups.isEmpty { SyncStatus.shared.changed() }
        pending.removeAll { $0.groupId.map(unavailable.contains) ?? false }
        // D-210 final B5: a removal made while the group was on this phone
        // alone goes up through adoptRemovals (the Function marks it), never
        // as a plain write other phones would refuse; one refused for good
        // stays here.
        let split = CloudRecords.adoptionUnits(pending, refused: refusedBefore)
        pending = split.direct
        // D-196: this phone's own expense changes in groups carry their push line.
        let notices = PushNotice.notices(for: pending, store: store)

        var count = 0
        var offline = false
        // D-227 (Design 8): the groups this push wrote records to.
        var written = Set<String>()
        for chunk in stride(from: 0, to: pending.count, by: 100).map({ Array(pending[$0..<min($0 + 100, pending.count)]) }) {
            let batch = Cloud.db.batch()
            for record in chunk {
                batch.setData(CloudRecords.document(record, uid: uid, notice: notices[CloudRecords.documentId(record)]),
                              forDocument: collection(uid: uid, groupId: record.groupId)
                                  .document(CloudRecords.documentId(record)))
            }
            do {
                try await batch.commit()
                chunk.forEach { uploaded.insert(CloudRecords.documentId($0)) }
                written.formUnion(chunk.compactMap(\.groupId))
                count += chunk.count
            } catch {
                // D-235 round 2 (wave B review I2): one by one. A record the rules refuse for good (it is not on the
                // server, and the write was refused, not lost) is set aside and the rest go on; only an error that may
                // pass (offline, auth) stops the push, as before, and it is tried again next time.
                let one = await uploadOneByOne(chunk, write: { record in
                    try await collection(uid: uid, groupId: record.groupId).document(CloudRecords.documentId(record))
                        .setData(CloudRecords.document(record, uid: uid, notice: notices[CloudRecords.documentId(record)]))
                }, onServer: { record in
                    // D-235 look F4: a refused read is told apart from no answer, and trusted only while this phone
                    // is still signed in as the account pushing (signed out, the rules refuse everything).
                    await serverCopy(signedIn: Account.shared.uid == uid) {
                        try await collection(uid: uid, groupId: record.groupId).document(CloudRecords.documentId(record))
                            .getDocument(source: .server).exists
                    }
                })
                uploaded.formUnion(one.uploaded)
                written.formUnion(chunk.filter { one.written.contains(CloudRecords.documentId($0)) }.compactMap(\.groupId))
                count += one.written.count
                if !one.refused.isEmpty {
                    try? CloudRecords.park(store, Set(one.refused), nowMs: CloudRecords.nowMs())
                    NSLog("CLOUD-SYNC %d record(s) refused by the rules were set aside", one.refused.count)
                    // Decision 122: the group's page says so.
                    SyncStatus.shared.changed()
                }
                offline = one.offline
            }
            if offline { break }
        }
        if !offline {
            var refused = refusedBefore
            adopting: for unit in split.units {
                guard let groupId = unit.first?.groupId else { continue }
                var error: Error?
                do {
                    _ = try await CloudFunctions.call("adoptRemovals", CloudRecords.adoptData(groupId: groupId, unit: unit))
                } catch let e {
                    error = e
                }
                switch CloudRecords.adoptOutcome(error) {
                case .done:
                    unit.forEach { uploaded.insert(CloudRecords.documentId($0)) }
                    // D-227 fix wave (M-g): the group was written to, as Android's twin counts it.
                    written.insert(groupId)
                    count += unit.count
                case .refused:
                    // Others joined first: the removal stays on this phone
                    // (recorded in BUILD-LOG §4), and is not tried again.
                    unit.forEach { refused.insert(CloudRecords.documentId($0)) }
                case .retry:
                    break adopting
                }
            }
            try? CloudRecords.addAdoptRefused(store, refused.subtracting(refusedBefore))
        }
        try? CloudRecords.addUploaded(store, uploaded.subtracting(uploadedBefore))
        // Decision 114: a parked record that went up this time is parked no more.
        // Decision 122: its group's page drops the line.
        if (try? CloudRecords.unpark(store, uploaded.subtracting(uploadedBefore))) == true { SyncStatus.shared.changed() }
        // D-227 (Design 8): a group's first records may only now be up (the group document goes first), so its roles
        // are asked again wherever they do not place this account yet, and awaited: the profile share below reads
        // the fresh `places.you` (as Android's twin does).
        if !offline {
            for g in CloudRecords.rolesAfterPush(written, cached: { GroupRoles.shared.load($0) }) {
                await GroupRoles.shared.refresh(g)
            }
        }
        if !gainedGroups.isEmpty, !offline { ProfileShare.request(store: store) }
        // D-229 (Phase B): the place aliases this account's records in its groups need, once the push reached the
        // cloud (a group not up yet, or a failed call, is asked at a later push).
        if !offline { await AliasShare.share(store: store) }
        // D-230: then the held places, after the aliases (as in the branch above).
        if !offline { await PlaceShare.share(store: store) }
        return count
    }

    /// D-235 round 2: what one record's upload came to.
    enum UploadOutcome: Equatable { case uploaded, refused, offline }

    /// D-235 look F4: what the server said, after a refused write, when asked whether it holds the record: it does, it
    /// does not, its rules refused the read (permission denied), or no answer (offline, unauthenticated, anything else).
    /// Android's twin: `CloudRecords.Held` (YES, NO, DENIED, UNKNOWN).
    enum ServerCopy: Equatable { case there, notThere, readRefused, noAnswer }

    /// The existence read: asked only while this phone is still signed in as the pushing account (signed out, the rules
    /// refuse every read too, which must never park anything): otherwise no answer. A read the rules refused is
    /// `.readRefused`; any other failure is no answer. Android's twin: `CloudRecords.held`.
    static func serverCopy(signedIn: Bool, read: () async throws -> Bool) async -> ServerCopy {
        guard signedIn else { return .noAnswer }
        do {
            return try await read() ? .there : .notThere
        } catch {
            return isPermissionDenied(error) ? .readRefused : .noAnswer
        }
    }

    /// PERMISSION_DENIED alone (not invalid argument, not auth or network). Android's twin: `isPermissionDenied`.
    nonisolated static func isPermissionDenied(_ error: Error) -> Bool {
        let error = error as NSError
        return error.domain == FirestoreErrorDomain && error.code == FirestoreErrorCode.permissionDenied.rawValue
    }

    /// The pure rule: a write that went through is up; a write refused while the server says the document is there is
    /// up too (records are never overwritten); a write the rules refused (permission denied, invalid argument) while
    /// the server answered that the document is NOT there is refused for good; anything else (no answer from the
    /// server, an auth or network error) may pass, so the push stops and tries again later.
    /// D-235 look F4: a GROUP's record whose write was refused by permission and whose existence read was refused by
    /// permission too is refused for good as well. That is a group this account was removed from (a records read needs
    /// membership, or `former` plus a document that exists): it is parked (decision 114, the group's page says so,
    /// decision 122) and the push goes on to the groups the account is still in, where it used to stop every later push.
    /// A personal record refused both ways (its own collection is never a membership refusal: auth trouble) and an
    /// invalid argument with a refused read still stop. Android's twin: `uploadFailure`.
    nonisolated static func uploadOutcome(writeError: Error?, onServer: ServerCopy, inGroup: Bool) -> UploadOutcome {
        guard let writeError else { return .uploaded }
        switch onServer {
        case .there:
            return .uploaded
        case .notThere:
            let error = writeError as NSError
            let refusedForGood = error.domain == FirestoreErrorDomain
                && [FirestoreErrorCode.permissionDenied.rawValue, FirestoreErrorCode.invalidArgument.rawValue].contains(error.code)
            return refusedForGood ? .refused : .offline
        case .readRefused:
            return inGroup && isPermissionDenied(writeError) ? .refused : .offline
        case .noAnswer:
            return .offline
        }
    }

    /// Records one at a time (after a batch failed): `uploaded` holds what is on the server now (written here or found
    /// there), `written` what this call wrote, `refused` what the rules refused for good (set aside; the rest go on),
    /// and `offline` is true when one could not be settled (the records from it on wait for the next push).
    static func uploadOneByOne(
        _ records: [RecordWire], write: (RecordWire) async throws -> Void, onServer: (RecordWire) async -> ServerCopy
    ) async -> (uploaded: [String], written: [String], refused: [String], offline: Bool) {
        var uploaded: [String] = [], written: [String] = [], refused: [String] = []
        for record in records {
            let id = CloudRecords.documentId(record)
            var failure: Error?
            do { try await write(record) } catch { failure = error }
            let server: ServerCopy = failure == nil ? .there : await onServer(record)
            switch uploadOutcome(writeError: failure, onServer: server, inGroup: record.groupId != nil) {
            case .uploaded:
                uploaded.append(id)
                if failure == nil { written.append(id) }
            case .refused:
                refused.append(id)
            case .offline:
                return (uploaded, written, refused, true)
            }
        }
        return (uploaded, written, refused, false)
    }

    enum GroupState { case member, notMember, offline }

    /// Creates the group in the cloud with this account as its only member,
    /// or finds it already there.
    static func ensureGroup(_ groupId: String, name: String?, uid: String) async -> GroupState {
        let doc = Cloud.db.collection("groups").document(groupId)
        // D-235 round 4 (shells review I1, Android's GroupEnsure): offline, the create never completes (it resolves on
        // the server's acknowledgement) and "Making an invite" spun for good; bounded, it falls to the server read,
        // which is bounded too, and no answer is `.offline`.
        return await decideGroup(create: { try await doc.setData(CloudRecords.groupDocument(name: name, uid: uid)) },
                                 read: {
            // It exists: readable means a member (the rules refuse anyone
            // else); a refusal means someone else's group with this id.
            do {
                let snapshot = try await doc.getDocument(source: .server)
                return snapshot.exists ? .member : .offline
            } catch let error as NSError where error.domain == FirestoreErrorDomain
                && error.code == FirestoreErrorCode.permissionDenied.rawValue {
                return .notMember
            } catch {
                return .offline
            }
        })
    }

    static let groupCreateTimeout: TimeInterval = 8
    static let groupReadTimeout: TimeInterval = 8

    /// The create first (a new group's only way up); when it fails or runs out of time, the server is asked, in
    /// bounded time; no answer is `.offline`. Pure over its two calls: the tests pin it with calls that never return.
    static func decideGroup(create: @escaping @MainActor () async throws -> Void,
                            read: @escaping @MainActor () async -> GroupState,
                            createSeconds: TimeInterval = groupCreateTimeout,
                            readSeconds: TimeInterval = groupReadTimeout) async -> GroupState {
        if await Bounded.run(seconds: createSeconds, create) { return .member }
        return await Bounded.value(seconds: readSeconds, otherwise: .offline, read)
    }

    // MARK: - D-194: down

    private var personalListener: ListenerRegistration?
    private var phoneListener: ListenerRegistration?
    private var groupsListener: ListenerRegistration?
    private var groupListeners: GroupListeners<ListenerRegistration>?

    /// The account's own records, the groups it is in, and each group's
    /// records, each from where this phone last left off.
    func listen() {
        guard personalListener == nil, let (uid, store) = ready() else { return }
        // D-210 final B4: once per install of this build, before any group
        // listener starts from its cursor.
        try? CloudRecords.resetGroupCursorsOnce(store)
        // D-211: the profile share waits for this listener's first snapshot
        // (`receive` says when), then runs once per install and whatever a
        // failed try left pending (final review I1(b)).
        ProfileShare.listening(store: store)
        personalListener = listen(to: Self.collection(uid: uid, groupId: nil), groupId: nil, token: 0, store: store)
        // D-197: invitations sent to this account.
        IncomingInvites.shared.listen(uid: uid)
        // D-210: what happened in its groups (a request, a removal, a role, a deletion).
        Notices.shared.listen(uid: uid)
        // D-198: this phone's own entry; a sign-out from elsewhere ends here.
        if let device = try? CloudProfile.deviceId(store) {
            phoneListener = Cloud.db.collection("users").document(uid).collection("devices").document(device)
                .addSnapshotListener { snapshot, _ in
                    guard let data = snapshot?.data(), data["signedOut"] as? Bool == true else { return }
                    Task { @MainActor in
                        if Phones.signedOutSince(data, authTimeMs: await Phones.authTimeMs()) {
                            await Push.shared.stop(inCloud: false)
                            RemoteSignOut.shared.happened()
                        }
                    }
                }
        }
        groupListeners = GroupListeners(
            start: { groupId, token in
                CloudSync.shared.listen(to: Self.collection(uid: uid, groupId: groupId), groupId: groupId, token: token, store: store)
            },
            stop: { $0.remove() })
        // Metadata changes too: the snapshot that says the server now holds a
        // group this phone just created changes nothing else (GroupListeners.update).
        groupsListener = Cloud.db.collection("groups").whereField("memberUids", arrayContains: uid)
            .addSnapshotListener(includeMetadataChanges: true) { snapshot, _ in
                guard let snapshot else { return }
                let groups = snapshot.documents.map { ($0.documentID, $0.data(), !$0.metadata.hasPendingWrites) }
                let fromCache = snapshot.metadata.isFromCache
                MainActor.assumeIsolated { CloudSync.shared.listenToGroups(groups, uid: uid, store: store, fromCache: fromCache) }
            }
    }

    /// When to ask for a group's roles (D-227, `RoleAsking`): the signature is
    /// the owner and admins in its document (account ids); a change means the
    /// phones' participant-id list is stale.
    private var roleAsking = RoleAsking()
    /// Decision 38: each group's timed ask after a failed one (`RoleAsking.retryAfter`), cancelled by `stopListening`, a
    /// successful ask, a newer timer or the group leaving the list.
    private var roleRetries: [String: Task<Void, Never>] = [:]

    private func listenToGroups(_ groups: [(String, [String: Any], Bool)], uid: String, store: LedgerStore, fromCache: Bool) {
        guard personalListener != nil, let listeners = groupListeners else { return }
        let ids = Set(groups.map(\.0))
        // D-252: what the server lists now; a group that left it is asked about (never from the cache).
        listedNow = ids
        if !fromCache { checkVanished(listed: groups.map { ($0.0, $0.2) }, uid: uid, store: store) }
        // D-210: a group that left this account's list (removed, or deleted):
        // stop listening to its records and nothing else. The notice says why
        // and brings in what was written up to then (`handle`).
        listeners.update(groups.map { GroupListeners<ListenerRegistration>.Listed(id: $0.0, confirmed: $0.2) })
        for (groupId, data, confirmed) in groups {
            // D-210: the owner or the admins changed on another phone: ask who they are now.
            // D-227: only once the server holds the group (a confirmed snapshot), one ask at a time, and again
            // on the next snapshot when the answer was not-found or offline.
            let owner = data["owner"] as? String ?? ""
            let admins = ((data["admins"] as? [String]) ?? []).sorted().joined(separator: ",")
            let signature = owner + "|" + admins
            if roleAsking.shouldAsk(groupId, signature: signature, confirmed: confirmed) {
                askRoles(groupId, signature: signature)
            }
        }
        roleAsking.keep(only: ids)
        for (groupId, task) in roleRetries where !ids.contains(groupId) {
            task.cancel()
            roleRetries[groupId] = nil
        }
    }

    // MARK: - D-252: a group that left the account's listing

    /// The groups the latest listing holds (`checkVanished` asks again before it marks).
    private var listedNow = Set<String>()
    /// Groups asked about this listening session whose answer was `keep`, or whose read is in flight; cleared by
    /// `stopListening`, and for a group the listing holds again (it is asked again if it leaves again).
    private var vanishAsked = Set<String>()

    /// D-252 (decision 218): on every listing from the server, a vanished mark lifts from a group listed again, and
    /// each held group the listing no longer has, with no notice to say why, is asked about once (`GroupGone`): a
    /// refusal or no document makes the copy read-only ("This group is no longer shared with you."), no answer
    /// changes nothing (asked again at the next listing or launch). A session's first listing is the sweep: groups
    /// that ended while this phone was away, 0.5.0's deleted groups on the deleting account's other phones included.
    /// Nothing local is ever deleted (CLAUDE.md 10).
    private func checkVanished(listed: [(String, Bool)], uid: String, store: LedgerStore) {
        let ids = Set(listed.map(\.0))
        vanishAsked.subtract(ids)
        guard let marks = try? store.readOnlyChecked() else { return }
        var lifted = false
        for (groupId, confirmed) in listed where GroupGone.lifts(marks[groupId], listedConfirmed: confirmed) {
            if (try? store.clearReadOnly(groupId)) != nil { lifted = true }
        }
        if lifted { SyncStatus.shared.changed() }
        guard let held = try? Set(store.loadGroups().map(\.id)), let up = try? CloudRecords.groupsUp(store) else { return }
        let noticed = Set(Notices.shared.items.filter(\.kind.endsSync).map(\.groupId))
        let ask = GroupGone.toCheck(held: held, groupsUp: up, listed: ids, copies: Set(marks.keys), noticed: noticed,
                                    asked: vanishAsked)
        for groupId in ask {
            vanishAsked.insert(groupId)
            Task { @MainActor in
                let outcome = GroupGone.decide(GroupGone.read(await Self.groupRead(groupId)))
                let sync = CloudSync.shared
                // Signed out, or listening as someone else, while it was asked: what it said does not count.
                guard sync.groupsListener != nil, Account.shared.uid == uid else { return }
                switch outcome {
                case .keep:
                    return
                case .retry:
                    sync.vanishAsked.remove(groupId)
                    return
                case .readOnly:
                    sync.vanishAsked.remove(groupId)
                    // Listed again meanwhile, or a notice marked it first: the newer word stands.
                    guard !sync.listedNow.contains(groupId) else { return }
                    do {
                        guard GroupGone.marks(over: try store.readOnlyChecked()[groupId]) else { return }
                        try store.markReadOnly(groupId, GroupGone.mark(at: Date()))
                    } catch { return }
                    sync.groupListeners?.remove(groupId)
                    SyncStatus.shared.changed()
                }
            }
        }
    }

    /// One `groupRoles` ask. D-227: it counts only when the answer places this account (`RoleAsking.ok`), and a
    /// change seen while it was asked is asked once more (Decision 35), through `shouldAsk` like any other.
    private func askRoles(_ groupId: String, signature: String) {
        Task { @MainActor in
            let ok = RoleAsking.ok(await GroupRoles.shared.refreshed(groupId))
            let sync = CloudSync.shared
            // Decision 38: a successful ask cancels the timer (RoleAsking forgets it too).
            if ok { sync.roleRetries.removeValue(forKey: groupId)?.cancel() }
            if let next = sync.roleAsking.finished(groupId, signature: signature, ok: ok) {
                if sync.roleAsking.shouldAsk(groupId, signature: next, confirmed: true) {
                    sync.askRoles(groupId, signature: next)
                }
            } else if !ok, let timer = sync.roleAsking.failed(groupId, signature: signature) {
                // Follow-up A: only an answer that counted sets a timer (`RoleAsking.failed`).
                sync.retryRoles(groupId, signature: signature, timer)
            }
        }
    }

    /// Decision 38: the timed retry of a failed ask. It asks only while the timer is still the group's
    /// (`RoleAsking.due`), and through `RoleAsking.shouldAsk`, so it is never a second ask at once.
    private func retryRoles(_ groupId: String, signature: String, _ timer: RoleAsking.RetryTimer) {
        roleRetries.removeValue(forKey: groupId)?.cancel()
        roleRetries[groupId] = Task { @MainActor in
            try? await Task.sleep(nanoseconds: UInt64(timer.seconds * 1_000_000_000))
            guard !Task.isCancelled else { return }
            let sync = CloudSync.shared
            sync.roleRetries[groupId] = nil
            if sync.roleAsking.due(groupId, token: timer.token),
               sync.roleAsking.shouldAsk(groupId, signature: signature, confirmed: true) {
                sync.askRoles(groupId, signature: signature)
            }
        }
    }

    private func listen(to collection: CollectionReference, groupId: String?, token: Int, store: LedgerStore) -> ListenerRegistration {
        collection
            .whereField("createdAt", isGreaterThanOrEqualTo: CloudRecords.timestamp(CloudRecords.cursor(store, groupId)))
            .order(by: "createdAt")
            // The personal listener also hears the change from "cache" to
            // "server" (Fix 3, M-A): when the server then holds nothing new,
            // that is the only snapshot that says this phone has caught up.
            .addSnapshotListener(includeMetadataChanges: groupId == nil) { snapshot, error in
                // A refused or failed listener is over: let it go, so the
                // groups listing starts it again (GroupListeners.failed).
                if error != nil {
                    if let groupId {
                        MainActor.assumeIsolated { CloudSync.shared.groupListeners?.failed(groupId, token: token) }
                    }
                    return
                }
                guard let snapshot else { return }
                let changes = snapshot.documentChanges.filter { $0.type != .removed }.map { $0.document.data() }
                let fromCache = snapshot.metadata.isFromCache
                MainActor.assumeIsolated {
                    CloudSync.shared.receive(changes, groupId: groupId, store: store, fromCache: fromCache)
                }
            }
    }

    func stopListening() {
        // D-211: no profile share until a listener has caught up again.
        ProfileShare.listening(store: store)
        IncomingInvites.shared.stop()
        Notices.shared.stop()
        roleAsking.clear()
        // D-252: a new session asks again (its first listing is the sweep).
        vanishAsked = []
        listedNow = []
        roleRetries.values.forEach { $0.cancel() }
        roleRetries = [:]
        phoneListener?.remove()
        phoneListener = nil
        personalListener?.remove()
        groupsListener?.remove()
        groupListeners?.clear()
        personalListener = nil
        groupsListener = nil
        groupListeners = nil
    }

    /// Applies what arrived: new records go into the vault, the clock moves
    /// past them, and the screens' ledger is rebuilt from the records.
    /// `fromCache`: the snapshot came from the local cache, not the server.
    /// No default: every caller says which it is (D-211 Fix 4).
    func receive(_ documents: [[String: Any]], groupId: String?, store: LedgerStore, fromCache: Bool) {
        // A listener has nobody to tell: a vault that will not take the
        // records leaves the cursor where it was, so the next snapshot
        // brings them again.
        guard let fresh = try? CloudRecords.absorb(documents, groupId: groupId, store: store) else { return }
        if !fresh.isEmpty { SyncStatus.shared.changed() }
        receivedCount += fresh.count
        // D-211: a joined group whose place this account was waiting for
        // shares once, when that place arrives. Nothing else that arrives
        // (this phone's own returning profiles included) shares.
        // The personal listener's first snapshot from the SERVER is in (empty
        // or not): this phone has the account's own name and picture, so the
        // share may run. A cache snapshot says nothing of that: the cache is
        // memory only, so it is empty (Fix 3, M-A). D-227 follow-up E: a server
        // snapshot after a cache one (a reconnect) runs a share left for later.
        ProfileShare.afterReceive(groupId: groupId, fresh: fresh, store: store, fromCache: fromCache)
        // D-244 (ADR-0043): the account's settings come only through its own log. The first server snapshot catches this
        // phone up (its first sign-in: the account's values win, the gaps are filled); later, what arrived is applied.
        if groupId == nil { SettingsSync.received(store: store, fresh: !fresh.isEmpty, fromCache: fromCache) }
    }

    /// Records applied since launch; for the emulator proof.
    private(set) var receivedCount = 0

    // MARK: - D-210: notices

    private var handling = Set<String>()

    /// Acts on what the cloud left for this account. A removal: bring in
    /// what was written up to it (the one read the rules allow), then keep
    /// the group as a read-only copy. A deletion: keep it as a copy. A role
    /// or a decision: ask who owns and administers the group now. A request
    /// stays: it is the card, until the request is decided (the Function
    /// deletes it). Each runs once at a time, and an offline phone leaves
    /// the notice for the next time.
    ///
    /// D-210 final B3: a removal or a deletion stays in the cloud (every
    /// phone of the account must find it); this phone keeps the ids it has
    /// handled and skips them. A role or a decision is dismissed as before.
    /// B6: a removal is bounded by the server's own times, and one for a
    /// group this account has joined again is old news: never a copy.
    func handle(_ items: [Notices.Item]) {
        let store = self.store
        for item in Notices.toHandle(items, handled: Notices.handled(store)) where !handling.contains(item.id) {
            handling.insert(item.id)
            Task {
                defer { handling.remove(item.id) }
                // Each effect must land before the notice goes: anything
                // that fails returns here, and the notice is tried again.
                switch item.kind {
                case .removed:
                    guard let uid = Account.shared.uid, let read = await Self.groupRead(item.groupId) else { return }
                    switch Notices.removalBound(item, uid: uid, group: read) {
                    case .memberAgain:
                        try? Notices.markHandled(store, item.id)
                        return
                    case .gone:
                        // Deleted or refused: nothing more can come; the copy is what is here.
                        break
                    case .upTo(let upper):
                        guard await catchUp(item, upper: upper, store: store) else { return }
                    }
                    do {
                        try store.markReadOnly(item.groupId, LedgerStore.ReadOnly(
                            kind: "removed", by: item.byName, at: item.removedAt ?? item.at))
                    } catch { return }
                case .deleted:
                    // D-252: the deleting account's own notice reaches its other phones ("You deleted"); a phone whose
                    // copy already says deleted keeps it (the deleting phone's own mark), and a vanished copy says more.
                    do {
                        if let mark = Notices.deletedMark(item, uid: Account.shared.uid, existing: try store.readOnlyChecked()[item.groupId]) {
                            try store.markReadOnly(item.groupId, mark)
                        }
                    } catch { return }
                case .role, .declined:
                    guard await GroupRoles.shared.refresh(item.groupId) else { return }
                case .request:
                    return
                }
                // A role or a decision keeps the group syncing.
                if item.kind.endsSync {
                    groupListeners?.remove(item.groupId)
                }
                if item.kind.keptInCloud {
                    do { try Notices.markHandled(store, item.id) } catch { return }
                } else {
                    Notices.shared.dismiss(item)
                }
                SyncStatus.shared.changed()
            }
        }
    }

    /// The group's document from the server, for a removal's bound: nil
    /// when the server could not be reached (the notice waits).
    private static func groupRead(_ groupId: String) async -> Notices.GroupRead? {
        do {
            let snapshot = try await Cloud.db.collection("groups").document(groupId).getDocument(source: .server)
            guard snapshot.exists, let data = snapshot.data() else { return .missing }
            return .found(data)
        } catch let error as NSError where error.domain == FirestoreErrorDomain {
            return Notices.groupRead(forFirestoreCode: FirestoreErrorCode.Code(rawValue: error.code))
        } catch {
            return nil
        }
    }

    /// A removed person's last read: the group's records from where this
    /// phone left off up to the moment of removal, both bounds on the query
    /// (the rules refuse an unbounded list from a former member). False when
    /// the cloud could not be reached or the vault would not take what
    /// came, so the notice waits.
    private func catchUp(_ item: Notices.Item, upper: Timestamp, store: LedgerStore) async -> Bool {
        let query = Self.collection(uid: "", groupId: item.groupId)
            .whereField("createdAt", isGreaterThanOrEqualTo: CloudRecords.timestamp(CloudRecords.cursor(store, item.groupId)))
            .whereField("createdAt", isLessThanOrEqualTo: upper)
            .order(by: "createdAt")
        do {
            // The server, never the cache: offline, an empty cached answer
            // must not stand for "nothing more" (it throws, and the notice waits).
            let snapshot = try await query.getDocuments(source: .server)
            let fresh = try CloudRecords.absorb(snapshot.documents.map { $0.data() }, groupId: item.groupId, store: store)
            // A try after a rebuild that failed finds nothing new: rebuild anyway.
            if fresh.isEmpty { try LedgerStore.Sync.rebuildProjection(store) }
            SyncStatus.shared.changed()
            receivedCount += fresh.count
            return true
        } catch let error as NSError where error.domain == FirestoreErrorDomain {
            // A refusal or a missing group will not change by asking again:
            // the copy is what is already here.
            switch FirestoreErrorCode.Code(rawValue: error.code) {
            case .unavailable?, .deadlineExceeded?, .cancelled?: return false
            default: return true
            }
        } catch {
            return false
        }
    }
}

/// Which groups' records this phone listens to, and the registrations. `R` is
/// a Firestore `ListenerRegistration` in the app, anything in a test. Twin:
/// GroupListeners in CloudSync.kt.
final class GroupListeners<R> {
    /// A group in the account's list. `confirmed`: the server holds it (the
    /// listing has no pending write of this phone's for it).
    struct Listed {
        let id: String
        let confirmed: Bool
    }

    private let start: (String, Int) -> R
    private let stop: (R) -> Void
    private var live: [String: (token: Int, registration: R)] = [:]
    private var lastToken = 0

    /// `start` gets the group and a token for this registration: the listener
    /// reports its own failure with it (`failed`).
    init(start: @escaping (String, Int) -> R, stop: @escaping (R) -> Void) {
        self.start = start
        self.stop = stop
    }

    func contains(_ groupId: String) -> Bool { live[groupId] != nil }

    /// The account's list changed: listen to each listed group the server
    /// holds, let go of a group that left the list. A group this phone has
    /// just created is listed at once, from its own pending write; the
    /// server refuses a records listener until the group is there, and a
    /// refused listener is over for good. It starts when the listing says the
    /// server has it. A pending write to a group already listened to keeps
    /// its listener.
    func update(_ groups: [Listed]) {
        let ids = Set(groups.map(\.id))
        for group in groups where group.confirmed && live[group.id] == nil {
            lastToken += 1
            live[group.id] = (lastToken, start(group.id, lastToken))
        }
        for groupId in Array(live.keys) where !ids.contains(groupId) {
            if let gone = live.removeValue(forKey: groupId) { stop(gone.registration) }
        }
    }

    /// A group's listener ended with an error (Firestore does not resume
    /// it): let it go, so the next listing starts it again. A late report
    /// from a listener already replaced leaves the new one.
    func failed(_ groupId: String, token: Int) {
        guard let current = live[groupId], current.token == token else { return }
        live.removeValue(forKey: groupId)
        stop(current.registration)
    }

    func remove(_ groupId: String) {
        if let gone = live.removeValue(forKey: groupId) { stop(gone.registration) }
    }

    func clear() {
        live.values.forEach { stop($0.registration) }
        live = [:]
    }
}

/// D-194: a record as a Firestore document, and back. The document holds
/// the record's own fields; its id is the record's identity (HLC), so an
/// upload is idempotent. Field names are the rules' (`validRecord` in
/// firebase/firestore.rules), tested against them.
enum CloudRecords {
    static let fields = ["type", "hlc", "authorUid", "deviceId", "createdAt", "payload", "notice"]
    static let uploadedKey = "sync/uploaded"
    static let groupsUpKey = "sync/groups-up"
    static let groupFields = ["name", "memberUids", "createdBy", "createdAt", "emoji"]

    static func documentId(_ record: RecordWire) -> String {
        "\(record.hlcMillis)-\(record.hlcCounter)-\(record.hlcDeviceId)"
    }

    /// D-227 (Design 8): of the groups a push wrote records to, the ones whose cached roles do not place this
    /// account yet (no roles, no places, or an empty `places.you`), in order: asked again, now that the group's create
    /// may be up (its document goes up before its records). Android's twin: `CloudRecords.rolesAfterPush`.
    static func rolesAfterPush(_ pushed: Set<String>, cached: (String) -> RoleRules.Roles?) -> [String] {
        pushed.filter { cached($0)?.places?.you.isEmpty ?? true }.sorted()
    }

    static func isHex32(_ value: String) -> Bool {
        value.range(of: "^[0-9a-f]{32}$", options: .regularExpression) != nil
    }

    /// The records the cloud does not have yet, oldest first. A record whose
    /// device id is not this format (a pre-sync fixture) stays on the phone:
    /// the rules would refuse it.
    static func pending(_ records: [RecordWire], uploaded: Set<String>) -> [RecordWire] {
        records
            .filter { isHex32($0.authorDeviceId) && isHex32($0.hlcDeviceId) }
            .filter { $0.groupId.map(isHex32) ?? true }
            .filter { $0.hlcMillis <= UInt64(Int64.max) && !uploaded.contains(documentId($0)) }
            .sorted { ($0.hlcMillis, $0.hlcCounter) < ($1.hlcMillis, $1.hlcCounter) }
    }

    static func document(_ record: RecordWire, uid: String, notice: PushNotice.Notice? = nil) -> [String: Any] {
        var document: [String: Any] = [
            "type": String(record.recordType),
            "hlc": ["millis": Int64(record.hlcMillis), "counter": Int64(record.hlcCounter),
                    "device": record.hlcDeviceId],
            "authorUid": uid,
            "deviceId": record.authorDeviceId,
            "createdAt": FieldValue.serverTimestamp(),
            "payload": ["version": Int64(record.version), "epoch": Int64(record.epoch),
                        "flags": Int64(record.flags), "nonce": record.nonceSession, "blob": record.blob],
        ]
        // D-196: only a group record carries a push line (the rules refuse
        // one on a personal record).
        if let notice, record.groupId != nil {
            document["notice"] = ["text": notice.text, "expense": notice.expense]
        }
        return document
    }

    /// nil for anything that is not a well-formed record.
    /// A group as the cloud first sees it: this account alone in it. Its
    /// people, names and changes travel as the group's own records.
    static func groupDocument(name: String?, uid: String) -> [String: Any] {
        let trimmed = (name ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        return ["name": trimmed.isEmpty ? "Group" : String(trimmed.prefix(80)), "memberUids": [uid],
                "createdBy": uid, "createdAt": FieldValue.serverTimestamp()]
    }

    static func record(_ data: [String: Any], groupId: String?) -> RecordWire? {
        func int(_ any: Any?) -> Int64? { (any as? NSNumber)?.int64Value }
        guard let type = (data["type"] as? String).flatMap(UInt8.init),
              let hlc = data["hlc"] as? [String: Any], let payload = data["payload"] as? [String: Any],
              let millis = int(hlc["millis"]), millis >= 0,
              let counter = int(hlc["counter"]), let counter32 = UInt32(exactly: counter),
              let hlcDevice = hlc["device"] as? String, isHex32(hlcDevice),
              let author = data["deviceId"] as? String, isHex32(author),
              // D-235 (wave A L5): the vault refuses a record header version other than 1, which made `apply` throw
              // and the collection's cursor stall for good on one such document: it is skipped here instead.
              let version = int(payload["version"]).flatMap({ UInt16(exactly: $0) }), version == 1,
              let epoch = int(payload["epoch"]).flatMap({ UInt32(exactly: $0) }),
              let flags = int(payload["flags"]).flatMap({ UInt8(exactly: $0) }),
              let nonce = payload["nonce"] as? Data, let blob = payload["blob"] as? Data,
              // D-235 round 2: the vault refuses a header it cannot store (a nonce that is not 4 bytes, a type it does
              // not know), and that refusal used to throw out of `absorb` before the cursor moved: skipped here too.
              nonce.count == 4, (1...6).contains(type)
        else { return nil }
        return RecordWire(
            version: version, recordType: type, groupId: groupId, epoch: epoch,
            hlcMillis: UInt64(millis), hlcCounter: counter32, hlcDeviceId: hlcDevice,
            authorDeviceId: author, nonceSession: nonce, flags: flags, blob: blob
        )
    }

    // MARK: D-195: what a group's log may bring in (D-158's rule, kept from
    // the relay). Every member of a group can write to its log, so a record
    // read from it must be about that group alone: never another group's
    // money or people, never the account's own "you", never a record that
    // claims to be this phone's.

    enum Refusal: Equatable {
        case wrongScope, wrongGroup, personal, thisDevice, unreadable, unknownType
        /// D-235 (C3, decision 103): an expense or a settlement whose money core's rules refuse (shares that do not
        /// add up, a negative or out-of-bound amount, an id that is not an id, an unknown currency): it never reaches
        /// the ledger, so it can neither stop an export nor unbalance a group.
        case invalid
        /// D-210: a removal or a redistribution a phone wrote; only the
        /// removeMember Function writes them (`removal: true`).
        case unverifiedRemoval
        /// D-211: a member's profile a phone wrote; only the shareProfile
        /// Function writes them (`profile: true`).
        case unverifiedProfile
        /// D-229: a place alias a phone wrote; only the shareAliases Function
        /// writes them (`alias: true`).
        case unverifiedAlias
        /// D-230: a held place a phone wrote; only the sharePlaces and
        /// acceptInvite Functions write them (`held: true`).
        case unverifiedPlace
    }

    /// nil when `record`, read from `group`'s log, may be applied. `removal`
    /// is the document's own mark: the Function that removes a person writes
    /// their RemoveMember operation and their balance's redistribution with
    /// it, and the rules refuse the mark from a phone.
    ///
    /// D-211: `profile` is the document's own mark for a member's profile:
    /// the shareProfile Function writes it, and the rules refuse the mark from
    /// a phone. Every profile in a vault is the server's word.
    ///
    /// A record that claims this phone is refused, except a removal or a
    /// redistribution carrying the mark: this phone called removeMember, the
    /// Function wrote them, and saving them here failed (`absorb` takes one
    /// only when the vault does not hold it; applying is idempotent anyway).
    /// D-211: and a marked profile: this phone built it, never put it in its
    /// vault, and sees it only as it comes back from the group's log.
    ///
    /// D-229: `alias` is the document's own mark for a place alias (the
    /// shareAliases Function writes it, the rules refuse it from a phone); a
    /// marked one from this phone comes back as a marked profile does.
    ///
    /// D-230: `held` is the document's own mark for a held place (the
    /// sharePlaces and acceptInvite Functions write it, the rules refuse it
    /// from a phone); a marked one from this phone comes back as a marked
    /// alias does. No other mark excuses a held place, and the held mark
    /// excuses nothing else.
    static func refusal(
        _ record: RecordWire, removal: Bool = false, profile: Bool = false, alias: Bool = false, held: Bool = false,
        from group: String, thisDevice: String
    ) -> Refusal? {
        guard record.groupId == group else { return .wrongScope }
        if fromThisDevice(record, thisDevice), !(removal && removalType(record)), !(profile && profileType(record)),
           !(alias && aliasType(record)), !(held && heldType(record)) {
            return .thisDevice
        }
        switch record.recordType {
        case LedgerStore.Sync.expenseType:
            guard let e = try? JSONDecoder().decode(LedgerStore.ExpenseDTO.self, from: record.blob) else {
                return .unreadable
            }
            guard e.group == group else { return .wrongGroup }
            return moneyRefusal(record)
        case LedgerStore.Sync.settlementType:
            guard let s = try? JSONDecoder().decode(LedgerStore.SettlementDTO.self, from: record.blob) else {
                return .unreadable
            }
            guard s.group == group else { return .wrongGroup }
            return moneyRefusal(record)
        case LedgerStore.Sync.revisionType:
            // D-235 (C3, core C1): decoded in the group's scope, so new money is this group's (it used to come back
            // personal and every group money edit was refused as wrong-group); core's money rules run in the decode.
            guard let r = try? ffiDecodeExpenseRevision(blob: record.blob, scope: group) else { return .unreadable }
            // The expense it revises must be this group's: core checks that
            // against the record's scope. New money must stay in the group.
            if let money = r.money, money.group != group { return .wrongGroup }
            return nil
        case LedgerStore.Sync.groupOpType:
            guard let op = try? ffiDecodeGroupOp(blob: record.blob) else { return .unreadable }
            switch op {
            case .removeMember(let g, _):
                guard g == group else { return .wrongGroup }
                return removal ? nil : .unverifiedRemoval
            case .create(let g, _, _, _), .addMember(let g, _), .renameMember(let g, _, _),
                 .renameGroup(let g, _), .claim(let g, _, _, _), .groupPicture(let g, _, _):
                return g == group ? nil : .wrongGroup
            case .memberProfile(let g, _, _, _, _):
                guard g == group else { return .wrongGroup }
                return profile ? nil : .unverifiedProfile
            case .placeAlias(let g, _, _):
                guard g == group else { return .wrongGroup }
                return alias ? nil : .unverifiedAlias
            case .heldPlace(let g, _, _):
                guard g == group else { return .wrongGroup }
                return held ? nil : .unverifiedPlace
            case .you, .groupKey, .security, .deviceName, .avatar, .setting:
                // D-211: your picture is personal like a device name. D-244: and so is a setting.
                return .personal
            }
        case LedgerStore.Sync.redistributionType:
            guard let r = try? ffiDecodeRedistribution(blob: record.blob) else { return .unreadable }
            guard r.group == group else { return .wrongGroup }
            return removal ? nil : .unverifiedRemoval
        default:
            return .unknownType
        }
    }

    /// D-211: what the account's own log may not hold: a member's profile
    /// belongs to a group's log (the Function writes it there), never here.
    /// D-229: and so does a place alias. D-230: and a held place.
    static func personalRefusal(_ record: RecordWire) -> Refusal? {
        // D-235 round 2 (decision 111): no money check here, as on Android. The account's own log holds only its own
        // phones' records, checked when written; checking them on the way in could drop a 0.4.0 phone's record.
        guard record.recordType == LedgerStore.Sync.groupOpType else { return nil }
        switch try? ffiDecodeGroupOp(blob: record.blob) {
        case .memberProfile?, .placeAlias?, .heldPlace?: return .wrongScope
        default: return nil
        }
    }

    /// D-235 (C3, decision 103): an expense or a settlement checked by core (`ffiCheckExpense`, `ffiCheckSettlement`);
    /// `.unreadable` when it does not decode, `.invalid` when core refuses its money, nil when it passes or the record
    /// is of another type.
    static func moneyRefusal(_ record: RecordWire) -> Refusal? {
        switch record.recordType {
        case LedgerStore.Sync.expenseType:
            guard let e = try? JSONDecoder().decode(LedgerStore.ExpenseDTO.self, from: record.blob) else {
                return .unreadable
            }
            return (try? ffiCheckExpense(expense: e.asInput)) == nil ? .invalid : nil
        case LedgerStore.Sync.settlementType:
            guard let s = try? JSONDecoder().decode(LedgerStore.SettlementDTO.self, from: record.blob) else {
                return .unreadable
            }
            return (try? ffiCheckSettlement(settlement: s.asInput)) == nil ? .invalid : nil
        default:
            return nil
        }
    }

    static func fromThisDevice(_ record: RecordWire, _ thisDevice: String) -> Bool {
        record.authorDeviceId == thisDevice || record.hlcDeviceId == thisDevice
    }

    /// D-211: a member's profile: what the shareProfile Function writes with
    /// its mark.
    static func profileType(_ record: RecordWire) -> Bool {
        guard record.recordType == LedgerStore.Sync.groupOpType else { return false }
        if case .memberProfile? = try? ffiDecodeGroupOp(blob: record.blob) { return true }
        return false
    }

    /// D-229: a place alias: what the shareAliases Function writes with its mark.
    static func aliasType(_ record: RecordWire) -> Bool {
        guard record.recordType == LedgerStore.Sync.groupOpType else { return false }
        if case .placeAlias? = try? ffiDecodeGroupOp(blob: record.blob) { return true }
        return false
    }

    /// D-230: a held place: what the sharePlaces and acceptInvite Functions write with their mark.
    static func heldType(_ record: RecordWire) -> Bool {
        guard record.recordType == LedgerStore.Sync.groupOpType else { return false }
        if case .heldPlace? = try? ffiDecodeGroupOp(blob: record.blob) { return true }
        return false
    }

    /// A redistribution, or a RemoveMember operation: what the removeMember
    /// Function writes with the mark.
    static func removalType(_ record: RecordWire) -> Bool {
        switch record.recordType {
        case LedgerStore.Sync.redistributionType: return true
        case LedgerStore.Sync.groupOpType:
            if case .removeMember? = try? ffiDecodeGroupOp(blob: record.blob) { return true }
            return false
        default: return false
        }
    }

    /// D-159 (kept from the relay): every group this account is in carries
    /// its claim on its own place (a joiner's is written when joining, a
    /// creator's here), once; without it the other members' phones would
    /// offer to invite the creator. The claim's device key is unused since
    /// ADR-0040 (`Invites.claim`).
    ///
    /// D-229 (Design 4): the claim names the group's PLACE for this account
    /// (core's `place`, a raw member there), not the merged "you": a claim on
    /// "you" in a group made under another of the account's ids waited for a
    /// member nobody would add, and every push wrote another. Where no claim
    /// stood yet, the first claim on the place with `you` = "you" makes every
    /// record written under "you" readable there (D-158's claim alias).
    static func ownClaims(_ p: GroupsProjectionOut, device: String) -> [GroupOpIn] {
        guard let you = p.you else { return [] }
        let mine = Set([you.id] + p.otherYouIds)
        return p.groups.compactMap { g in
            guard !g.claims.contains(where: { mine.contains($0.youId) }), let place = g.place else { return nil }
            return Invites.claim(group: g.group.id, member: place, you: you.id, device: device)
        }
    }

    /// Documents from one collection into the vault: the records it may
    /// take go in, the clock moves past them, the cursor moves on and the
    /// screens' ledger is rebuilt (whenever any record came). Returns the records that were new; throws
    /// when the vault would not take them or the ledger would not rebuild
    /// (the cursor then stays where it was).
    @discardableResult
    static func absorb(_ documents: [[String: Any]], groupId: String?, store: LedgerStore) throws -> [RecordWire] {
        var arrived: [RecordWire] = []
        var newest = cursor(store, groupId)
        let thisDevice = (try? LedgerStore.Sync.deviceId(store)) ?? ""
        // This phone's own removal records, already in the vault: an echo.
        var held: Set<String>?
        for data in documents {
            // Our own write echoes back before the server stamps it.
            guard let stamp = data["createdAt"] as? Timestamp else { continue }
            newest = max(newest, millis(stamp))
            guard let record = Self.record(data, groupId: groupId) else { continue }
            // D-195: other people write a group's log; only that group's own
            // records may come in through it.
            // D-210: a removal or a redistribution counts only when a Function wrote it (`removal: true`).
            let removal = data["removal"] as? Bool == true
            // D-211: and a member's profile counts only when shareProfile wrote it (`profile: true`).
            let profile = data["profile"] as? Bool == true
            // D-229: and a place alias only when shareAliases wrote it (`alias: true`).
            let alias = data["alias"] as? Bool == true
            // D-230: and a held place only when sharePlaces or acceptInvite wrote it (`held: true`).
            let heldMark = data["held"] as? Bool == true
            if let groupId {
                if refusal(record, removal: removal, profile: profile, alias: alias, held: heldMark, from: groupId,
                           thisDevice: thisDevice) != nil { continue }
            } else if personalRefusal(record) != nil {
                continue
            }
            if fromThisDevice(record, thisDevice) {
                if held == nil { held = Set(try store.allRecords().map(LedgerStore.Sync.recordIdentity)) }
                if held?.contains(LedgerStore.Sync.recordIdentity(record)) == true { continue }
            }
            arrived.append(record)
        }
        let fresh = try apply(arrived, store: store)
        let now = UInt64(Date().timeIntervalSince1970 * 1000)
        for record in fresh {
            try? LedgerStore.Sync.receiveHlc(store, millis: record.hlcMillis, counter: record.hlcCounter, nowMs: now)
        }
        try? addUploaded(store, Set(arrived.map(documentId)))
        if let groupId { try? addGroupsUp(store, [groupId]) }
        // Whenever anything came, not only when something was new: a rebuild
        // that threw last time left its records in the vault and the cursor
        // behind, so the next snapshot finds them present ("not fresh") and
        // would otherwise never rebuild. No flag to persist, and a snapshot
        // carries only what is past the cursor (plus our own echoes).
        if !arrived.isEmpty {
            try LedgerStore.Sync.rebuildProjection(store)
        }
        try? saveCursor(store, groupId, newest)
        return fresh
    }

    /// The records not in the vault yet, written in one vault session.
    static func apply(_ records: [RecordWire], store: LedgerStore) throws -> [RecordWire] {
        guard !records.isEmpty else { return [] }
        let vault = try VaultGate.open(path: store.path, keystore: KeychainKeystore())
        defer { try? vault.closeVault() }
        var known = Set(try vault.allRecords().map(LedgerStore.Sync.recordIdentity))
        var fresh: [RecordWire] = []
        for record in records where !known.contains(LedgerStore.Sync.recordIdentity(record)) {
            try vault.putRecord(record: record)
            known.insert(LedgerStore.Sync.recordIdentity(record))
            fresh.append(record)
        }
        return fresh
    }

    /// What a push sends: the pending records, less a read-only group's
    /// (D-210) and a removal refused for good (D-210 final B5). D-211 (founder
    /// decision 5): of the account's pictures, only the latest goes up; an
    /// earlier one still waiting (pictures set before the first sign-in, or
    /// while offline) is `superseded`: marked uploaded, never sent. A member's
    /// profile is never sent either (Fix 3, M-B): only the Function writes
    /// one, so one found here as pending (its mark lost) is `superseded` too.
    /// D-229: and so is a place alias (only shareAliases writes one). D-230:
    /// and a held place (only sharePlaces and acceptInvite write one).
    /// D-244 (ADR-0043): and of each setting, only the latest goes up; an earlier one of the same key still waiting
    /// (the steps of one visit to Appearance, or changes made while sync was off) is `superseded`, as a picture is.
    static func outgoing(
        _ all: [RecordWire], uploaded: Set<String>, readOnly: Set<String>, refused: Set<String>
    ) -> (send: [RecordWire], superseded: Set<String>) {
        let waiting = pending(all, uploaded: uploaded)
            .filter { $0.groupId.map { !readOnly.contains($0) } ?? true }
            .filter { !refused.contains(documentId($0)) }
        let latest = all.filter(isAvatar).max {
            ($0.hlcMillis, $0.hlcCounter, $0.hlcDeviceId) < ($1.hlcMillis, $1.hlcCounter, $1.hlcDeviceId)
        }.map(documentId)
        let latestSetting = latestSettings(all)
        let superseded = Set(waiting.filter { record in
            profileType(record) || aliasType(record) || heldType(record) || (isAvatar(record) && documentId(record) != latest)
                || settingKey(record).map { latestSetting[$0] != documentId(record) } == true
        }.map(documentId))
        return (waiting.filter { !superseded.contains(documentId($0)) }, superseded)
    }

    /// D-244: the key of a personal `.setting` record, nil for any other record.
    static func settingKey(_ record: RecordWire) -> String? {
        guard record.recordType == LedgerStore.Sync.groupOpType, record.groupId == nil else { return nil }
        if case .setting(let key, _)? = try? ffiDecodeGroupOp(blob: record.blob) { return key }
        return nil
    }

    /// D-244: each key's latest setting record (the one core's projection keeps), by document id.
    static func latestSettings(_ all: [RecordWire]) -> [String: String] {
        var latest: [String: RecordWire] = [:]
        for record in all {
            guard let key = settingKey(record) else { continue }
            if let held = latest[key],
               (held.hlcMillis, held.hlcCounter, held.hlcDeviceId) >= (record.hlcMillis, record.hlcCounter, record.hlcDeviceId) {
                continue
            }
            latest[key] = record
        }
        return latest.mapValues(documentId)
    }

    /// A personal `.avatar` record (the account's picture).
    static func isAvatar(_ record: RecordWire) -> Bool {
        guard record.recordType == LedgerStore.Sync.groupOpType, record.groupId == nil else { return false }
        if case .avatar? = try? ffiDecodeGroupOp(blob: record.blob) { return true }
        return false
    }

    static func uploaded(_ store: LedgerStore) throws -> Set<String> {
        guard let data = try store.kvGet(key: uploadedKey), !data.isEmpty else { return [] }
        return Set(String(decoding: data, as: UTF8.self).split(separator: "\n").map(String.init))
    }

    static func saveUploaded(_ store: LedgerStore, _ ids: Set<String>) throws {
        try store.kvSet(key: uploadedKey, value: Data(ids.sorted().joined(separator: "\n").utf8))
    }

    /// D-235 (C4, iOS M2): adds `ids` to the marks as stored now (read just before the write, no await between), so
    /// marks another writer saved while the caller was suspended stay. Nothing to add writes nothing.
    static func addUploaded(_ store: LedgerStore, _ ids: Set<String>) throws {
        guard !ids.isEmpty else { return }
        try saveUploaded(store, try uploaded(store).union(ids))
    }

    static func addGroupsUp(_ store: LedgerStore, _ ids: Set<String>) throws {
        guard !ids.isEmpty else { return }
        try saveGroupsUp(store, try groupsUp(store).union(ids))
    }

    /// D-235 round 2: the records the rules refused on upload (`CloudSync.uploadOutcome`), parked on this phone. One
    /// line per record: `id<TAB>refusals<TAB>last refusal in ms` (a bare id, from before decision 114, reads as one
    /// refusal at time 0).
    static let uploadRefusedKey = "sync/upload-refused"

    /// Decision 114 (shells review M5): a parked record is tried again once a day, at most three times, then stays
    /// parked. A fast clock that was fixed recovers (its stamp becomes acceptable as real time passes); a hostile
    /// record stops costing calls.
    static let parkRetryAfterMs: Int64 = 86_400_000
    static let parkRetries = 3

    struct Parked: Equatable {
        var refusals: Int
        var lastMs: Int64
    }

    static func nowMs() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

    static func parked(_ store: LedgerStore) throws -> [String: Parked] {
        guard let data = try store.kvGet(key: uploadRefusedKey), !data.isEmpty else { return [:] }
        var out: [String: Parked] = [:]
        for line in String(decoding: data, as: UTF8.self).split(separator: "\n") {
            let parts = line.split(separator: "\t").map(String.init)
            guard let id = parts.first, !id.isEmpty else { continue }
            out[id] = Parked(refusals: parts.count > 1 ? Int(parts[1]) ?? 1 : 1,
                             lastMs: parts.count > 2 ? Int64(parts[2]) ?? 0 : 0)
        }
        return out
    }

    static func saveParked(_ store: LedgerStore, _ parked: [String: Parked]) throws {
        let lines = parked.keys.sorted().map { "\($0)\t\(parked[$0]!.refusals)\t\(parked[$0]!.lastMs)" }
        try store.kvSet(key: uploadRefusedKey, value: Data(lines.joined(separator: "\n").utf8))
    }

    /// Due to be tried again: fewer refusals than the first plus three retries, and a day since the last one (or the
    /// clock went back: a fast clock that was fixed).
    static func due(_ p: Parked, nowMs: Int64) -> Bool {
        p.refusals <= parkRetries && (nowMs < p.lastMs || nowMs - p.lastMs >= parkRetryAfterMs)
    }

    /// The parked records a push leaves out now (every one not `due`). A vault that cannot be read holds nothing back.
    static func heldParked(_ store: LedgerStore, nowMs: Int64) -> Set<String> {
        Set(((try? parked(store)) ?? [:]).filter { !due($0.value, nowMs: nowMs) }.keys)
    }

    /// Refused (again): one more refusal, now.
    static func park(_ store: LedgerStore, _ ids: Set<String>, nowMs: Int64) throws {
        guard !ids.isEmpty else { return }
        var all = try parked(store)
        for id in ids { all[id] = Parked(refusals: (all[id]?.refusals ?? 0) + 1, lastMs: nowMs) }
        try saveParked(store, all)
    }

    /// Up at last: no longer parked. True when one was (decision 122: its group's page reads again).
    @discardableResult
    static func unpark(_ store: LedgerStore, _ ids: Set<String>) throws -> Bool {
        var all = try parked(store)
        guard !ids.isDisjoint(with: all.keys) else { return false }
        for id in ids { all[id] = nil }
        try saveParked(store, all)
        return true
    }

    /// Decision 122 (the founder): whether any of this phone's records in `group` is parked, due again or not; the
    /// group's page then says so in one quiet line. The group is the record's own (`RecordWire.groupId`); a personal
    /// record is in none. Android's twin: `CloudRecords.parkedIn`.
    static func parkedIn(group: String, records: [RecordWire], parked: Set<String>) -> Bool {
        !parked.isEmpty && records.contains { $0.groupId == group && parked.contains(documentId($0)) }
    }

    /// The group page's read: the parked set first (empty, the usual case, reads no record).
    static func hasParked(_ store: LedgerStore, group: String) throws -> Bool {
        let ids = Set(try parked(store).keys)
        guard !ids.isEmpty else { return false }
        return parkedIn(group: group, records: try store.allRecords(), parked: ids)
    }

    static func addAdoptRefused(_ store: LedgerStore, _ ids: Set<String>) throws {
        guard !ids.isEmpty else { return }
        try saveAdoptRefused(store, try adoptRefused(store).union(ids))
    }

    /// The groups this phone knows are in the cloud with this account in them.
    static func groupsUp(_ store: LedgerStore) throws -> Set<String> {
        guard let data = try store.kvGet(key: groupsUpKey), !data.isEmpty else { return [] }
        return Set(String(decoding: data, as: UTF8.self).split(separator: "\n").map(String.init))
    }

    static func saveGroupsUp(_ store: LedgerStore, _ ids: Set<String>) throws {
        try store.kvSet(key: groupsUpKey, value: Data(ids.sorted().joined(separator: "\n").utf8))
    }

    /// Milliseconds of the newest server time seen in one collection; its
    /// listener starts there (inclusive: a record in the same millisecond is
    /// applied twice, which is a no-op).
    static func cursorKey(_ groupId: String?) -> String { "sync/cursor/" + (groupId ?? "personal") }

    static func cursor(_ store: LedgerStore, _ groupId: String?) -> Int64 {
        guard let data = try? store.kvGet(key: cursorKey(groupId)) else { return 0 }
        return Int64(String(decoding: data, as: UTF8.self)) ?? 0
    }

    static func saveCursor(_ store: LedgerStore, _ groupId: String?, _ millis: Int64) throws {
        try store.kvSet(key: cursorKey(groupId), value: Data(String(millis).utf8))
    }

    // MARK: D-210 final B4: group cursors start again, once

    nonisolated static let groupCursorsResetKey = "sync/migrations/group-cursors-d210"
    /// D-235 (C3, core C1): every group money edit was refused as wrong-group from D-195 until this build, and the
    /// cursors moved past them; one more reset brings them in.
    nonisolated static let revisionCursorsResetKey = "sync/migrations/group-cursors-d235"
    /// Each reset runs once per install, in this order; one that ran before is not run again.
    nonisolated static let groupCursorResets = [groupCursorsResetKey, revisionCursorsResetKey]

    /// 0.4.0 skipped records it did not know (redistributions, type 6) and
    /// moved its cursors past them. Once per install of this build, every
    /// group's cursor goes back to 0, so the listeners fetch those records
    /// again (records already here are no-ops). The personal cursor stays.
    /// True when it ran now.
    @discardableResult
    static func resetGroupCursorsOnce(_ store: LedgerStore) throws -> Bool {
        var ran = false
        for key in groupCursorResets {
            if try resetGroupCursorsOnce(store, key: key) { ran = true }
        }
        return ran
    }

    /// One reset, marked by `key`: true when it ran now.
    @discardableResult
    static func resetGroupCursorsOnce(_ store: LedgerStore, key: String) throws -> Bool {
        if let done = try store.kvGet(key: key), !done.isEmpty { return false }
        let groups = Set(try store.loadGroups().map(\.id)).union(try groupsUp(store))
        for groupId in groups.sorted() { try saveCursor(store, groupId, 0) }
        try store.kvSet(key: key, value: Data("1".utf8))
        return true
    }

    // MARK: D-210 final B5: removals made before a group was shared

    /// What `adoptRemovals` said: in (or already in), refused for good, or
    /// try again later.
    enum AdoptOutcome: Equatable { case done, refused, retry }

    static let adoptRefusedKey = "sync/adopt-refused"

    /// The pending records, split: the ones written directly, and the
    /// removals (a RemoveMember operation with that person's redistributions,
    /// one unit per person, oldest first) that only `adoptRemovals` may write.
    /// Records the Function refused for good stay on this phone (`refused`).
    static func adoptionUnits(_ pending: [RecordWire], refused: Set<String> = []) -> (direct: [RecordWire], units: [[RecordWire]]) {
        var direct: [RecordWire] = []
        // (group, person) -> the unit's records, in the order they came (HLC).
        var units: [String: [RecordWire]] = [:]
        var order: [String] = []
        for record in pending {
            guard let groupId = record.groupId, removalType(record) else {
                direct.append(record)
                continue
            }
            if refused.contains(documentId(record)) { continue }
            let person: String
            if record.recordType == LedgerStore.Sync.redistributionType {
                person = (try? ffiDecodeRedistribution(blob: record.blob))?.from ?? ""
            } else if case .removeMember(_, let member)? = try? ffiDecodeGroupOp(blob: record.blob) {
                person = member
            } else {
                person = ""
            }
            let key = groupId + "/" + person
            if units[key] == nil { order.append(key) }
            units[key, default: []].append(record)
        }
        // The callable takes 50 records at most: a larger unit (more than 49
        // currencies) goes in pieces, oldest first.
        let pieces = order.flatMap { key -> [[RecordWire]] in
            let unit = units[key] ?? []
            return stride(from: 0, to: unit.count, by: 50).map { Array(unit[$0..<min($0 + 50, unit.count)]) }
        }
        return (direct, pieces)
    }

    /// The `adoptRemovals` callable's data for one unit.
    static func adoptData(groupId: String, unit: [RecordWire]) -> [String: Any] {
        ["groupId": groupId, "records": Removal.payload(unit)]
    }

    /// In (ALREADY_EXISTS: an earlier try got there), refused for good (the
    /// group has others in it now, is gone, or will not take these), or
    /// offline and tried again on the next push.
    static func adoptOutcome(_ error: Error?) -> AdoptOutcome {
        guard let error else { return .done }
        guard case .refused(let status)? = error as? CloudFunctions.Failure else { return .retry }
        switch status {
        case "ALREADY_EXISTS": return .done
        case "FAILED_PRECONDITION", "PERMISSION_DENIED", "NOT_FOUND", "INVALID_ARGUMENT": return .refused
        default: return .retry
        }
    }

    static func adoptRefused(_ store: LedgerStore) throws -> Set<String> {
        guard let data = try store.kvGet(key: adoptRefusedKey), !data.isEmpty else { return [] }
        return Set(String(decoding: data, as: UTF8.self).split(separator: "\n").map(String.init))
    }

    static func saveAdoptRefused(_ store: LedgerStore, _ ids: Set<String>) throws {
        try store.kvSet(key: adoptRefusedKey, value: Data(ids.sorted().joined(separator: "\n").utf8))
    }

    static func millis(_ stamp: Timestamp) -> Int64 {
        stamp.seconds * 1000 + Int64(stamp.nanoseconds / 1_000_000)
    }

    static func timestamp(_ millis: Int64) -> Timestamp {
        Timestamp(seconds: millis / 1000, nanoseconds: Int32((millis % 1000) * 1_000_000))
    }
}

/// What screens watch to read the ledger again: `revision` goes up whenever
/// something arrived or changed. Kept from D-158 (screens use
/// `.task(id: SyncStatus.shared.revision)`); unit 4's listeners bump it.
@Observable
@MainActor
final class SyncStatus {
    static let shared = SyncStatus()
    private(set) var revision = 0

    /// Something the screens show changed.
    func changed() { revision += 1 }

    /// A pull to refresh (D-194): sends what is waiting; the listener
    /// brings the rest.
    func syncNow() async { await CloudSync.shared.syncNow() }
}

enum HexCodec {
    static func encode(_ data: Data) -> String {
        data.map { String(format: "%02x", $0) }.joined()
    }

    static func decode(_ string: String) throws -> Data {
        let chars = Array(string)
        guard chars.count % 2 == 0 else { throw LedgerStore.LoadError.corrupt }
        var index = 0
        var out = Data()
        while index < chars.count {
            guard let high = chars[index].hexDigitValue,
                  let low = chars[index + 1].hexDigitValue else {
                throw LedgerStore.LoadError.corrupt
            }
            out.append(UInt8(high << 4 | low))
            index += 2
        }
        return out
    }
}

/// D-235 (C4, iOS L3): one piece of async work per key at a time; a second caller for the same key awaits the one in
/// flight instead of starting its own. Another key starts its own (and becomes the one in flight).
@MainActor
final class SingleFlight<Value> {
    private var running: (key: String, token: Int, task: Task<Value, Never>)?
    private var lastToken = 0

    func isRunning(_ key: String) -> Bool { running?.key == key }

    func run(_ key: String, _ work: @escaping @MainActor () async -> Value) async -> Value {
        if let running, running.key == key { return await running.task.value }
        lastToken += 1
        let token = lastToken
        let task = Task { @MainActor in await work() }
        running = (key, token, task)
        let value = await task.value
        if running?.token == token { running = nil }
        return value
    }
}
