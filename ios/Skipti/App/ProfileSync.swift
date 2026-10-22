import FirebaseFirestore
import Foundation
import UIKit

/// D-211: your name and picture in your groups. Your own phones get the
/// picture as a personal record (`LedgerStore.saveAvatar`); a group's members
/// get a small preview and your name as a `MemberProfile` record that only the
/// `shareProfile` Function writes, so nobody can put a name or a picture on
/// someone else's row. This phone builds the records, sends them, and never
/// puts one in its own vault: it comes back through the group's log marked
/// (`profile: true`), the only way a profile is ever imported. Android's twin
/// is ProfileSync.kt.

/// A group member's preview: 120 px square, JPEG, at most 12,000 bytes
/// (core's `THUMB_MAX_BYTES`). Never enlarged anywhere.
enum ProfileThumb {
    static let side = 120
    static let maxBytes = 12_000

    /// The centre square of `jpeg` at 120 px (a smaller picture is not
    /// enlarged), as a JPEG at the best quality (0.8, stepping down by 0.1 to
    /// 0.4) that fits `maxBytes`. Nil when the bytes are not an image or never
    /// fit (the share then sends the name only).
    static func make(from jpeg: Data) -> Data? {
        ProfileRules.squareJPEG(from: jpeg, side: side, maxBytes: maxBytes, startQuality: 0.8, minQuality: 0.4)
    }
}

enum ProfileShare {
    /// The job's flag: set by every trigger, cleared when the cloud answers.
    static let pendingKey = "profile.share.pending"
    /// Set once the backfill (every install of this build shares once, for the
    /// groups joined before profiles existed) has been requested.
    static let backfilledKey = "profile.share.backfilled"
    /// The rules' `validName` limit: a longer name is not shared.
    static let nameMax = 60
    /// The callable takes 1 to 50 records.
    static let callMax = 50

    typealias Place = (groupId: String, memberId: String)

    // MARK: - Pure parts

    /// The place this account holds in each group, from the projection: the
    /// person it claimed by joining, or for a group it made, its own "you" id
    /// (`PeopleRoles.placeFor`, D-210: the id the cloud knows the row by).
    /// Groups it was taken out of (the place is removed) and groups that are
    /// read-only copies on this phone (`readOnly`: removed or deleted) are left
    /// out: nothing can be written there. D-227: `known` is each group's cached
    /// `places.you` (`GroupRoles.yourPlaces`); where the cloud said, its place wins.
    static func places(_ p: GroupsProjectionOut, readOnly: Set<String>, known: [String: [String]]) -> [Place] {
        guard let you = p.you else { return [] }
        let yous = [you.id] + p.otherYouIds
        return p.groups.compactMap { g in
            guard !readOnly.contains(g.group.id) else { return nil }
            let claims = Dictionary(g.claims.map { ($0.memberId, $0.youId) }, uniquingKeysWith: { a, _ in a })
            // D-227 (Design 7): the place the group's cloud said is this account's (`known`), else `placeFor`.
            let place = PeopleRoles.selfPlace(yourPlaces: known[g.group.id], selfId: you.id, yous: yous, claims: claims)
            // The projection has already shown this account's own place by its
            // "you" id (the merge): that is the id to look for in the members
            // and the removed; the place the cloud knows is `place`.
            let held = g.group.members.contains(you.id) || g.group.members.contains(place)
            guard held, !g.removed.contains(you.id), !g.removed.contains(place) else { return nil }
            return (g.group.id, place)
        }
    }

    /// What a group sees of your picture: an emoji as it is, a photo as a
    /// 120 px preview (none when it will not fit 12,000 bytes: the name is
    /// still shared).
    static func preview(of picture: GroupPictureOut?) -> (emoji: String?, thumbHex: String?) {
        guard let picture else { return (nil, nil) }
        if let emoji = picture.emoji { return (emoji, nil) }
        guard let hex = picture.photoHex, let bytes = try? HexCodec.decode(hex),
              let thumb = ProfileThumb.make(from: bytes) else { return (nil, nil) }
        return (nil, HexCodec.encode(thumb))
    }

    /// One `MemberProfile` record per place, stamped and authored by this
    /// phone through the same path as every group operation (`groupOpRecord`)
    /// but NOT put into the vault: the Function writes it to the cloud, and it
    /// reaches the vault only as it comes back marked.
    static func records(
        store: LedgerStore, places: [Place], name: String, emoji: String?, thumbHex: String?,
        nowMs: UInt64 = UInt64(Date().timeIntervalSince1970 * 1000)
    ) throws -> [RecordWire] {
        let device = try LedgerStore.Sync.deviceId(store)
        return try places.map { place in
            try LedgerStore.Sync.groupOpRecord(
                .memberProfile(groupId: place.groupId, memberId: place.memberId, name: name, emoji: emoji, thumbHex: thumbHex),
                hlc: try LedgerStore.Sync.nextHlc(store, nowMs: nowMs), deviceId: device)
        }
    }

    /// The `shareProfile` callable's data: the D-210 record shape (as
    /// `adoptRemovals` takes it).
    static func callData(_ records: [RecordWire]) -> [String: Any] {
        ["records": Removal.payload(records)]
    }

    enum Answer: Equatable {
        /// The cloud answered: the flag is cleared.
        case done
        /// `failed-precondition`: the name is not the account's; read it again.
        case nameChanged
        /// Offline or any other error: the flag stays, for decision 40's timer and the next sync start.
        case later
    }

    static func answer(_ error: Error?) -> Answer {
        guard let error else { return .done }
        guard case .refused(let status)? = error as? CloudFunctions.Failure else { return .later }
        switch status {
        case "FAILED_PRECONDITION": return .nameChanged
        // The call itself was wrong: asking again would not change that.
        case "INVALID_ARGUMENT": return .done
        default: return .later
        }
    }

    // MARK: - The flags

    static func isPending(_ store: LedgerStore) -> Bool {
        ((try? store.kvGet(key: pendingKey)) ?? nil).map { !$0.isEmpty } ?? false
    }

    static func setPending(_ store: LedgerStore, _ on: Bool) throws {
        try store.kvSet(key: pendingKey, value: on ? Data("1".utf8) : Data())
    }

    /// Once per install of this build: marks the share pending so the groups
    /// joined before profiles existed get this account's name and preview.
    /// True when it asked now. Pending is set first: a crash between the two
    /// writes asks again rather than never.
    @discardableResult
    static func backfillOnce(_ store: LedgerStore) throws -> Bool {
        if let done = try store.kvGet(key: backfilledKey), !done.isEmpty { return false }
        try setPending(store, true)
        try store.kvSet(key: backfilledKey, value: Data("1".utf8))
        return true
    }

    // MARK: - The waiting groups

    /// The groups this account joined whose place has not arrived yet (the
    /// claim is written before the group's records come down). Kept apart
    /// from `pending`: a pass that shared to every place it found clears
    /// pending, whatever waits here. Each group leaves when its place is
    /// found, or when it is deleted, removed or forgotten on this phone.
    static let waitingKey = "profile.share.waiting"

    static func waiting(_ store: LedgerStore) -> Set<String> {
        guard let data = (try? store.kvGet(key: waitingKey)) ?? nil else { return [] }
        return Set(String(decoding: data, as: UTF8.self).split(separator: "\n").map(String.init))
    }

    static func setWaiting(_ store: LedgerStore, _ groups: Set<String>) throws {
        try store.kvSet(key: waitingKey, value: Data(groups.sorted().joined(separator: "\n").utf8))
    }

    /// A group that can no longer take a profile here (deleted, removed,
    /// forgotten): never waited for again.
    static func dropWaiting(_ store: LedgerStore, _ groupId: String) throws {
        var all = waiting(store)
        guard all.remove(groupId) != nil else { return }
        try setWaiting(store, all)
    }

    // MARK: - The job

    /// The calls the job makes, replaceable by the tests (each restores the
    /// real one after). Seams: tests only; production never reassigns.
    @MainActor static let realSend: ([String: Any]) async -> Answer = { data in
        do {
            _ = try await CloudFunctions.call("shareProfile", data)
            return .done
        } catch {
            return answer(error)
        }
    }
    /// Tests only; production never reassigns.
    @MainActor static var send = realSend

    enum NameOutcome: Equatable {
        case ok
        /// Offline, or the account's profile is not there yet: next sync start.
        case later
        /// The rules refused the write (`permission-denied`): not offline,
        /// and not retried now; the share stays pending.
        case refused
    }

    static func nameOutcome(_ error: Error) -> NameOutcome {
        let e = error as NSError
        return e.domain == FirestoreErrorDomain && e.code == FirestoreErrorCode.permissionDenied.rawValue
            ? .refused : .later
    }

    @MainActor static let realEnsure: (String, String) async -> NameOutcome = { uid, name in
        do {
            try await CloudProfile.ensureName(uid: uid, name: name)
            return .ok
        } catch {
            let outcome = nameOutcome(error)
            // Nothing personal: the reason only, never the name.
            if outcome == .refused { NSLog("PROFILE-SHARE the rules refused the account name; the share waits") }
            return outcome
        }
    }
    /// Tests only; production never reassigns.
    @MainActor static var ensure = realEnsure

    /// The signed-in account when sync is on, else nil (the share waits).
    @MainActor static let realAccount: (LedgerStore) -> String? = { store in
        LaunchWork.signedInAccount(uid: Account.shared.uid, syncOn: CloudSync.syncEnabled(store),
                                   owner: Account.storedOwner(store),
                                   approval: Approval.cached(store, uid: Account.shared.uid))
    }
    /// Tests only; production never reassigns.
    @MainActor static var account = realAccount

    /// Decision 40's timer: waits `seconds`; true unless the wait was cancelled.
    @MainActor static let realRetryWait: (TimeInterval) async -> Bool = { seconds in
        try? await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
        return !Task.isCancelled
    }
    /// Tests only; production never reassigns.
    @MainActor static var retryWait = realRetryWait

    /// Per vault: a run in progress, the triggers so far, and the last one a
    /// pass has served. Every real trigger counts one; a pass serves every
    /// trigger made before it started, so the job runs at most one pass per
    /// trigger and never on anything else.
    @MainActor private static var running = Set<String>()
    @MainActor private static var generation: [String: Int] = [:]
    @MainActor private static var served: [String: Int] = [:]
    /// The records of a call that has not been answered: a retry sends the
    /// SAME ones (the Function treats a record it already wrote as shared),
    /// and they are built again only when the name changed or the inputs did.
    @MainActor private static var built: (key: String, records: [RecordWire])?
    /// The vaults whose personal listener has delivered its first snapshot
    /// since it last started (final review I1(b)). No pass runs for any
    /// other: until this phone has the account's own records, its name and
    /// picture may be stale, and a share made then would carry them with a
    /// fresh stamp that beats the right ones in every group.
    @MainActor private static var caughtUpVaults = Set<String>()
    /// D-227 follow-up E (look F3): the vaults whose personal listener last spoke from the cache (the phone lost the
    /// server). Its next snapshot from the server is the reconnect (`afterReceive`): a share left for later runs then.
    @MainActor private static var cachedVaults = Set<String>()
    /// D-227 decision 40: each vault's timed retries of a share whose call failed, and the timer waiting for it.
    @MainActor private static var shareRetries: [String: ShareRetry] = [:]
    @MainActor private static var retryTasks: [String: Task<Void, Never>] = [:]
    /// Per vault: the runs started and not finished (the tests wait on it).
    @MainActor private static var started: [String: Int] = [:]

    @MainActor
    private static func trigger(_ store: LedgerStore) -> Task<Void, Never> {
        let id = store.path
        generation[id, default: 0] += 1
        started[id, default: 0] += 1
        return Task {
            await run(store: store)
            started[id, default: 1] -= 1
        }
    }

    /// The runs of the vault at `path` started and not finished.
    @MainActor static func inFlight(_ path: String) -> Int { started[path, default: 0] }

    /// The personal listener started or stopped (CloudSync.listen and
    /// stopListening): the share waits for its next first snapshot.
    @MainActor
    static func listening(store: LedgerStore) {
        caughtUpVaults.remove(store.path)
        cachedVaults.remove(store.path)
        // Decision 40: a sign-out (or a new listener) cancels the timer and starts the count again.
        shareRetries[store.path] = nil
        retryTasks.removeValue(forKey: store.path)?.cancel()
    }

    /// The personal listener's first snapshot is in the vault: the sync start
    /// runs now (the backfill once per install, then whatever is pending).
    /// Later snapshots do nothing here.
    @MainActor @discardableResult
    static func caughtUp(store: LedgerStore) -> Task<Void, Never>? {
        guard markCaughtUp(store) else { return nil }
        return syncStarted(store: store)
    }

    /// The vault has the account's own records: passes may run. True the
    /// first time since the listener started. (`caughtUp` without the sync
    /// start; the tests mark their vaults with it.)
    @MainActor @discardableResult
    static func markCaughtUp(_ store: LedgerStore) -> Bool {
        caughtUpVaults.insert(store.path).inserted
    }

    /// What `CloudSync.receive` calls once records are in: only a group's own records that were new can be a waiting
    /// place arriving. The personal listener's snapshot (empty or not) says this phone has the account's own name and
    /// picture, so the share may run (`caughtUp`): only when the server sent it. A cache snapshot says nothing of that
    /// (the cache is memory only, so it is empty): Fix 3, M-A. D-227 follow-up E: a server snapshot after a cache one
    /// is the reconnect (`reconnected`). Android's twin: `ProfileShare.afterReceive`.
    @MainActor @discardableResult
    static func afterReceive(groupId: String?, fresh: [RecordWire], store: LedgerStore, fromCache: Bool)
        -> Task<Void, Never>? {
        guard let groupId else {
            if fromCache {
                cachedVaults.insert(store.path)
                return nil
            }
            let back = cachedVaults.remove(store.path) != nil
            return caughtUp(store: store) ?? (back ? reconnected(store: store) : nil)
        }
        return fresh.isEmpty ? nil : arrived(groupId: groupId, store: store)
    }

    /// D-227 follow-up E (look F3's twin): the personal listener hears the server again after speaking from the
    /// cache. A share a pass left for later (offline: the name's server read failed, or the call did) runs now, not
    /// at the next sync start; with nothing pending the pass ends at once.
    @MainActor @discardableResult
    static func reconnected(store: LedgerStore) -> Task<Void, Never> {
        trigger(store)
    }

    /// D-227 decision 40 (re-look R2): a pass whose `shareProfile` call failed is tried again on decision 38's
    /// schedule, while its timer is still the vault's (`ShareRetry.due`). A name that could not be read is no failed
    /// call: the reconnect (`reconnected`) or the next sync start runs it. Counted in `started` while it waits.
    @MainActor
    private static func retryLater(_ store: LedgerStore, _ timer: RoleAsking.RetryTimer) {
        let id = store.path
        started[id, default: 0] += 1
        retryTasks.removeValue(forKey: id)?.cancel()
        retryTasks[id] = Task {
            defer { started[id, default: 1] -= 1 }
            guard await retryWait(timer.seconds), shareRetries[id]?.due(timer.token) == true else { return }
            retryTasks[id] = nil
            _ = trigger(store)
        }
    }

    /// A trigger (a group made, the name or picture saved): marks the share
    /// pending and runs it. Never blocks a screen, never throws.
    @MainActor @discardableResult
    static func request(store: LedgerStore = .defaultStore()) -> Task<Void, Never> {
        try? setPending(store, true)
        return trigger(store)
    }

    /// A join's claim is written: the group waits for its place to arrive
    /// (`arrived`), and the share runs now for every place already here.
    @MainActor @discardableResult
    static func joined(group: String, store: LedgerStore) -> Task<Void, Never> {
        var all = waiting(store)
        all.insert(group)
        try? setWaiting(store, all)
        return request(store: store)
    }

    /// Sync started: the backfill once per install, then whatever is pending.
    @MainActor @discardableResult
    static func syncStarted(store: LedgerStore = .defaultStore()) -> Task<Void, Never> {
        _ = try? backfillOnce(store)
        return trigger(store)
    }

    /// A group's records arrived. The ONLY arrival that shares: a group this
    /// account joined and was waiting for, which now holds its place. It
    /// leaves the waiting set first, so it shares once. Any other group's
    /// records, this phone's own returning profiles among them, do nothing.
    @MainActor @discardableResult
    static func arrived(groupId: String, store: LedgerStore) -> Task<Void, Never>? {
        var all = waiting(store)
        guard all.contains(groupId),
              let p = try? LedgerStore.Sync.projectGroups(store),
              places(p, readOnly: Set(store.readOnly().keys), known: GroupRoles.shared.yourPlaces(p.groups.map(\.group.id)))
                  .contains(where: { $0.groupId == groupId })
        else { return nil }
        all.remove(groupId)
        // Never share without taking it out: that would share on every arrival.
        do { try setWaiting(store, all) } catch { return nil }
        return request(store: store)
    }

    /// Shares when it is pending, signed in and syncing, and this phone has
    /// its own records (`caughtUp`); otherwise the flag waits. One run per
    /// vault at a time; a trigger during a pass makes exactly one more.
    /// Pending is cleared only by a pass that served every trigger and that
    /// the cloud answered.
    @MainActor
    static func run(store: LedgerStore = .defaultStore()) async {
        let id = store.path
        guard !running.contains(id) else { return }
        running.insert(id)
        defer { running.remove(id) }
        while true {
            let now = generation[id, default: 0]
            guard now != served[id, default: 0], caughtUpVaults.contains(id), isPending(store),
                  let uid = account(store) else { return }
            var callFailed = false
            let outcome = await attempt(uid: uid, store: store) { callFailed = true }
            served[id] = now
            if generation[id, default: 0] == now {
                if outcome == .done { try? setPending(store, false) }
                // Decision 40: an answered pass ends the timed retries; a failed call sets the next timer, if any is left.
                var retry = shareRetries[id] ?? ShareRetry()
                var timer: RoleAsking.RetryTimer?
                if outcome == .done { retry.succeeded() } else if callFailed { timer = retry.failed() }
                shareRetries[id] = retry
                if let timer { retryLater(store, timer) }
                return
            }
        }
    }

    /// What a pass reads of the vault: the projection, the read-only groups,
    /// and the preview of the account's picture.
    private struct Look {
        let projection: GroupsProjectionOut
        let readOnly: Set<String>
        let emoji: String?
        let thumbHex: String?
    }

    /// The projection and the preview (decoding and redrawing a photo) run
    /// off the main actor (final review M6); nil when the vault will not
    /// project.
    private static func look(_ store: LedgerStore) async -> Look? {
        await Task.detached(priority: .utility) { () -> Look? in
            guard let p = try? LedgerStore.Sync.projectGroups(store) else { return nil }
            let (emoji, thumbHex) = preview(of: p.avatar)
            return Look(projection: p, readOnly: Set(store.readOnly().keys), emoji: emoji, thumbHex: thumbHex)
        }.value
    }

    /// One try: the name, then the records, then the call. `.later` keeps the
    /// flag; anything the cloud answers is `.done`. `callFailed` hears a
    /// `shareProfile` call that failed (decision 40).
    @MainActor
    private static func attempt(uid: String, store: LedgerStore, callFailed: () -> Void) async -> Answer {
        // Final review I1(a): a picture kept before pictures were records is
        // a record before anything below reads the picture (the rebuild that
        // migrates it may not have run yet this launch).
        _ = try? LedgerStore.Sync.migrateAvatarOnce(store)
        // A name the vault cannot read is not "no name": the next try (M4).
        let me: LedgerStore.SelfDTO?
        do {
            me = try store.loadSelf()
        } catch {
            return .later
        }
        // No name, or one the rules would refuse: nothing to share (saving a
        // name asks again).
        guard let name = shareable(me?.name) else { return .done }
        // The account's public name is this one (saving a name used to reach
        // it only when registration next ran).
        guard await ensure(uid, name) == .ok else { return .later }
        for round in 0..<2 {
            guard let seen = await look(store) else { return .later }
            let p = seen.projection
            let readOnly = seen.readOnly
            // D-227 (Design 7): the place each group's cloud says is this account's, where it said one.
            let places = places(p, readOnly: readOnly, known: GroupRoles.shared.yourPlaces(p.groups.map(\.group.id)))
            // A waiting group whose place is here now is shared to by this
            // pass; one that became a read-only copy is never shared to.
            let wait = waiting(store)
            let found = wait.filter { g in readOnly.contains(g) || places.contains { $0.groupId == g } }
            if !found.isEmpty { try? setWaiting(store, wait.subtracting(found)) }
            guard !places.isEmpty else { return .done }
            let (emoji, thumbHex) = (seen.emoji, seen.thumbHex)
            let key = ([store.path, name, emoji ?? "", thumbHex ?? ""] + places.map { $0.groupId + "/" + $0.memberId })
                .joined(separator: "|")
            let records: [RecordWire]
            if let kept = built, kept.key == key {
                records = kept.records
            } else {
                guard let fresh = try? Self.records(
                    store: store, places: places, name: name, emoji: emoji, thumbHex: thumbHex) else { return .later }
                built = (key, fresh)
                records = fresh
            }
            var outcome = Answer.done
            for start in stride(from: 0, to: records.count, by: callMax) {
                let answer = await send(callData(Array(records[start..<min(start + callMax, records.count)])))
                if answer != .done {
                    outcome = answer
                    break
                }
            }
            switch outcome {
            case .done:
                built = nil
                return .done
            case .later:
                callFailed()
                return .later
            case .nameChanged:
                built = nil
                // The name is read again once; still refused, that is the answer.
                if round == 1 { return .done }
                guard await ensure(uid, name) == .ok else { return .later }
            }
        }
        return .done
    }

    /// The name as the account shares it, or nil when the rules would refuse
    /// it: empty, or over `nameMax`. Counted in UTF-16 code units, the
    /// largest count of the ways a string's size can be read (the rules'
    /// `string.size()`), so a name that passes here passes there.
    static func shareable(_ raw: String?) -> String? {
        let name = (raw ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        return name.isEmpty || name.utf16.count > nameMax ? nil : name
    }
}

/// D-211: what the screens draw for another person in a group, and whether
/// Rename is offered. `previews` is `LedgerStore.memberPreviews()`: by group
/// id, then by the id the person is SHOWN by; a person is present when they
/// have a profile of their own (their name is theirs), and `.none` there
/// means a name with no picture. Android's twin is MemberPreviews in
/// ProfileSync.kt.
enum MemberPreviews {
    /// Rename is absent (not disabled, D-126) for a person with a profile in
    /// this group; people who never joined stay renamable.
    static func mayRename(_ previews: [String: [String: Avatar]], group: String, member: String) -> Bool {
        previews[group]?[member] == nil
    }

    /// The previews after a reload: what the read gives, or, when the read
    /// throws, the last good ones. A picture is decoration: a failed read
    /// never costs the screen its people (nothing is logged: it would carry
    /// nothing but the error of a vault read).
    static func reloaded(
        last: [String: [String: Avatar]], read: () throws -> [String: [String: Avatar]]
    ) -> [String: [String: Avatar]] {
        (try? read()) ?? last
    }

    /// The preview of one person in the group the screen is about.
    static func preview(_ previews: [String: [String: Avatar]], group: String, member: String) -> Avatar {
        previews[group]?[member] ?? .none
    }

    /// A person who is in several groups: the preview from the group the card
    /// is about when it has a picture, else the first group, in `candidates`'
    /// order (the projection's), that has one. `.none` when none does.
    static func pick(
        _ previews: [String: [String: Avatar]], candidates: [(group: String, member: String)], preferred: String?
    ) -> Avatar {
        if let preferred, let own = candidates.first(where: { $0.group == preferred }) {
            let shown = preview(previews, group: own.group, member: own.member)
            if shown != .none { return shown }
        }
        for c in candidates {
            let shown = preview(previews, group: c.group, member: c.member)
            if shown != .none { return shown }
        }
        return .none
    }
}

/// D-227 decision 40: one vault's timed retries of a profile share whose call failed, on decision 38's schedule
/// (`RoleAsking.retryDelay`: 15, 60, 300 s, then none until the count starts again). A share the cloud answered and a
/// sign-out (the personal listener stopping, `ProfileShare.listening`) start it again; a group that left leaves the
/// next pass nothing to share, which ends as answered. Android's twin: `ShareRetry` in ProfileSync.kt.
struct ShareRetry {
    private var made = 0
    private var live: Int?
    private var nextToken = 0

    /// A pass's call failed: the timer to set, or nil once three were made. A timer set earlier is no longer `due`.
    mutating func failed() -> RoleAsking.RetryTimer? {
        guard let seconds = RoleAsking.retryDelay(made) else {
            live = nil
            return nil
        }
        made += 1
        nextToken += 1
        live = nextToken
        return RoleAsking.RetryTimer(seconds: seconds, token: nextToken)
    }

    /// A pass the cloud answered: no timer, and the count starts again.
    mutating func succeeded() {
        made = 0
        live = nil
    }

    /// The timer with `token` fired: true while it is still the vault's own.
    func due(_ token: Int) -> Bool { live == token }
}

/// D-229 (Phase B, Design 5 and 7): records written under an id of this account that a group's other members cannot
/// read (one account on two phones, before D-229) become readable there through a `placeAlias` ("in this group, id y
/// reads as place x"). Only the `shareAliases` Function writes one, marked `alias: true`, after checking that x is the
/// caller's place and y the caller's own id and nobody else's; this phone builds the records, sends them, never puts
/// one in its vault (it comes back through the group's log marked, as a profile does), and never uploads one itself
/// (`CloudRecords.outgoing`). Asked once per (group, id): an answer, shared or skipped, is remembered
/// (`alias.share.answered`); a call that failed is asked again at the next push. Android's twin: `AliasShare` in
/// ProfileSync.kt.
enum AliasShare {
    /// "group:you" lines: the pairs the cloud has answered.
    static let answeredKey = "alias.share.answered"
    /// The callable takes 1 to 50 records.
    static let callMax = 50

    /// In `groupId`, `youId` reads as the place `memberId`.
    struct Alias: Equatable {
        let groupId: String
        let memberId: String
        let youId: String

        var key: String { groupId + ":" + youId }
    }

    // MARK: - Pure parts

    /// Every id a record of `group` names, as written (raw): an expense's payer, shares and who added it; a
    /// payment's two ends; an edit's editor and its money; a redistribution's ids; a renamed member. A record that
    /// does not decode names nothing.
    static func named(_ records: [RecordWire], group: String) -> Set<String> {
        var out = Set<String>()
        func money(_ e: ExpenseInput) {
            out.insert(e.payer)
            e.shares.forEach { out.insert($0.id) }
        }
        for record in records where record.groupId == group {
            switch record.recordType {
            case LedgerStore.Sync.expenseType:
                guard let e = try? JSONDecoder().decode(LedgerStore.ExpenseDTO.self, from: record.blob) else { continue }
                money(e.asInput)
                if let by = e.addedBy { out.insert(by) }
            case LedgerStore.Sync.settlementType:
                guard let s = try? JSONDecoder().decode(LedgerStore.SettlementDTO.self, from: record.blob) else {
                    continue
                }
                out.insert(s.from)
                out.insert(s.to)
            case LedgerStore.Sync.revisionType:
                guard let r = try? ffiDecodeExpenseRevision(blob: record.blob) else { continue }
                if let editor = r.editor { out.insert(editor) }
                if let m = r.money { money(m) }
            case LedgerStore.Sync.redistributionType:
                guard let r = try? ffiDecodeRedistribution(blob: record.blob) else { continue }
                out.insert(r.from)
                r.shares.forEach { out.insert($0.id) }
            case LedgerStore.Sync.groupOpType:
                if case .renameMember(_, let member, _)? = try? ffiDecodeGroupOp(blob: record.blob) { out.insert(member) }
            default:
                continue
            }
        }
        return out
    }

    /// The aliases to ask for: per group with a place that is not a read-only copy (`readOnly`), each of this
    /// account's ids core found unreadable there (`unaliasedYous`) that a record of the group actually names, less
    /// the pairs already `answered`. An id never used in a group is never shared into it.
    static func wanted(
        _ p: GroupsProjectionOut, records: [RecordWire], readOnly: Set<String>, answered: Set<String>
    ) -> [Alias] {
        p.groups.flatMap { g -> [Alias] in
            guard let place = g.place, !readOnly.contains(g.group.id), !g.unaliasedYous.isEmpty else { return [] }
            let named = named(records, group: g.group.id)
            return g.unaliasedYous
                .map { Alias(groupId: g.group.id, memberId: place, youId: $0) }
                .filter { !answered.contains($0.key) && named.contains($0.youId) }
        }
    }

    /// One `placeAlias` record per alias, stamped and authored by this phone through `groupOpRecord` (the group's
    /// scope), NOT put into the vault.
    static func records(
        store: LedgerStore, aliases: [Alias], nowMs: UInt64 = UInt64(Date().timeIntervalSince1970 * 1000)
    ) throws -> [RecordWire] {
        let device = try LedgerStore.Sync.deviceId(store)
        return try aliases.map { alias in
            try LedgerStore.Sync.groupOpRecord(
                .placeAlias(groupId: alias.groupId, memberId: alias.memberId, youId: alias.youId),
                hlc: try LedgerStore.Sync.nextHlc(store, nowMs: nowMs), deviceId: device)
        }
    }

    /// The records `share` sends, built one group at a time: a group whose records core cannot build (one bad alias)
    /// is skipped, and asked again at the next push; every other group's still go. Each record with the alias it
    /// carries.
    static func recordsByGroup(
        store: LedgerStore, aliases: [Alias], nowMs: UInt64 = UInt64(Date().timeIntervalSince1970 * 1000)
    ) -> [(alias: Alias, record: RecordWire)] {
        var groups: [String] = []
        for alias in aliases where !groups.contains(alias.groupId) { groups.append(alias.groupId) }
        return groups.flatMap { group -> [(alias: Alias, record: RecordWire)] in
            let mine = aliases.filter { $0.groupId == group }
            guard let built = try? records(store: store, aliases: mine, nowMs: nowMs) else { return [] }
            return Array(zip(mine, built)).map { (alias: $0.0, record: $0.1) }
        }
    }

    /// The `shareAliases` callable's data: the D-210 record shape.
    static func callData(_ records: [RecordWire]) -> [String: Any] {
        ["records": Removal.payload(records)]
    }

    /// The record ids the reply answered (`shared` and `skipped`).
    static func replied(_ reply: [String: Any]) -> Set<String> {
        Set(((reply["shared"] as? [String]) ?? []) + ((reply["skipped"] as? [String]) ?? []))
    }

    enum Answer: Equatable {
        /// The cloud answered (or there was nothing to ask): what its reply names is remembered.
        case done
        /// `invalid-argument`: the call itself was wrong, and asking again would not change that: every pair it
        /// carried is remembered.
        case refused
        /// Offline or any other error, `resource-exhausted` (the daily quota) included: nothing more is remembered;
        /// the next push asks again.
        case later
    }

    static func answer(_ error: Error?) -> Answer {
        guard let error else { return .done }
        guard case .refused("INVALID_ARGUMENT")? = error as? CloudFunctions.Failure else { return .later }
        return .refused
    }

    // MARK: - The answered pairs

    static func answered(_ store: LedgerStore) -> Set<String> {
        guard let data = (try? store.kvGet(key: answeredKey)) ?? nil, !data.isEmpty else { return [] }
        return Set(String(decoding: data, as: UTF8.self).split(separator: "\n").map(String.init))
    }

    static func saveAnswered(_ store: LedgerStore, _ keys: Set<String>) throws {
        try store.kvSet(key: answeredKey, value: Data(keys.sorted().joined(separator: "\n").utf8))
    }

    // MARK: - The job

    /// The call, replaceable by the tests. Seam: tests only; production never reassigns.
    @MainActor static let realSend: ([String: Any]) async throws -> [String: Any] = { data in
        try await CloudFunctions.call("shareAliases", data)
    }
    /// Tests only; production never reassigns.
    @MainActor static var send = realSend

    /// After a push that reached the cloud: asks for every wanted alias of a group this phone knows is in the cloud
    /// (`CloudRecords.groupsUp`: the Function would skip, for good, a group it cannot find), in calls of at most 50,
    /// and remembers what the cloud answered. A failed call stops the run (`.later`); what it carried is asked
    /// again at the next push. A refused call (`invalid-argument`) is an answer: its pairs are remembered and the run
    /// is `.done`, as Android's.
    @MainActor @discardableResult
    static func share(store: LedgerStore, nowMs: UInt64 = UInt64(Date().timeIntervalSince1970 * 1000)) async -> Answer {
        guard let up = try? CloudRecords.groupsUp(store), let p = try? LedgerStore.Sync.projectGroups(store),
              let all = try? store.allRecords() else { return .later }
        let want = wanted(p, records: all, readOnly: Set(store.readOnly().keys), answered: answered(store))
            .filter { up.contains($0.groupId) }
        guard !want.isEmpty else { return .done }
        let pairs = recordsByGroup(store: store, aliases: want, nowMs: nowMs)
        guard !pairs.isEmpty else { return .later }
        let built = pairs.map { $0.record }
        let keyOf = Dictionary(pairs.map { (CloudRecords.documentId($0.record), $0.alias.key) },
                               uniquingKeysWith: { a, _ in a })
        for start in stride(from: 0, to: built.count, by: callMax) {
            let chunk = Array(built[start..<min(start + callMax, built.count)])
            var reply: [String: Any] = [:]
            var failure: Error?
            do { reply = try await send(callData(chunk)) } catch { failure = error }
            let ids: Set<String>
            switch answer(failure) {
            case .done: ids = replied(reply)
            case .refused: ids = Set(chunk.map(CloudRecords.documentId))
            case .later: return .later
            }
            var done = answered(store)
            ids.compactMap { keyOf[$0] }.forEach { done.insert($0) }
            // A list that cannot be written is asked again next time (the Function counts a repeat as shared).
            try? saveAnswered(store, done)
        }
        return .done
    }
}

/// D-230: the server's word on who holds a place. After a push that reached the cloud, every place this account
/// holds that the group's log does not yet mark for it is shared once as a `heldPlace` the `sharePlaces` Function
/// verifies and writes marked (`held: true`); core then treats it as final for the member and the id (CLOUD-SECURITY
/// G-08). Amendment A1: a removed place a claim of this account still holds is shared too (with that claim's id), so
/// a rejoin whose join-time records failed is repaired here. The AliasShare contract: asked once per (group, place,
/// you), answers remembered (`place.share.answered`), a failed call or the daily quota asked again at the next push,
/// a refused call done. Android's twin: `PlaceShare` in ProfileSync.kt.
enum PlaceShare {
    /// "group:member:you" lines: the triples the cloud has answered.
    static let answeredKey = "place.share.answered"
    /// The callable takes 1 to 50 records.
    static let callMax = 50

    /// In `groupId`, the place `memberId` is held by this account's id `youId`.
    struct Held: Equatable {
        let groupId: String
        let memberId: String
        let youId: String

        var key: String { groupId + ":" + memberId + ":" + youId }
    }

    // MARK: - Pure parts

    /// D-230 A1 (second form) and A7: every place a claim of this account holds in `g` (a claim whose id is its
    /// "you" or one of its other ids), with that claim's id, less every member the group's marked held places already
    /// give to one of this account's ids (A10 M1: a two-phone account never re-marks a member under its other id, so
    /// the latest mark never flips), in the group's member order. The claims are core's (`g.claims`) united with every `claim` this vault
    /// holds for the group (`records`): core voids the phone's own claim on a rejoined place while the old place's mark
    /// holds its id (it lists it under `contested`, not `claims`), and the vault still has it. No removed check: the
    /// server is the authority (it marks a present place the caller holds and a removed place the caller held before,
    /// and skips the rest), and `answered` keeps a skipped pair from being asked again. A rejoin sends them with the
    /// new place, so the old place is marked too and core never reads its claim as a forgery.
    static func claimed(_ p: GroupsProjectionOut, _ g: ProjectedGroupOut, records: [RecordWire]) -> [Held] {
        let mine = Set((p.you.map { [$0.id] } ?? []) + p.otherYouIds)
        let fromCore = g.claims.map { Held(groupId: g.group.id, memberId: $0.memberId, youId: $0.youId) }
        let fromVault = records
            .filter { $0.recordType == LedgerStore.Sync.groupOpType && $0.groupId == g.group.id }
            .compactMap { r -> Held? in
                guard case .claim(let group, let member, let you, _)? = try? ffiDecodeGroupOp(blob: r.blob),
                      group == g.group.id else { return nil }
                return Held(groupId: group, memberId: member, youId: you)
            }
        var out: [Held] = []
        for held in fromCore + fromVault where mine.contains(held.youId) && !out.contains(held) {
            if g.held.contains(where: { $0.memberId == held.memberId && mine.contains($0.youId) }) { continue }
            out.append(held)
        }
        // The group's member order (stable: one member's ids keep core's, then the vault's order), as Android's.
        let order = Dictionary(g.group.members.enumerated().map { ($1, $0) }, uniquingKeysWith: { a, _ in a })
        return out.enumerated()
            .sorted { (order[$0.element.memberId] ?? Int.max, $0.offset) < (order[$1.element.memberId] ?? Int.max, $1.offset) }
            .map(\.element)
    }

    /// The places to ask for: per group that is not a read-only copy (`readOnly`), the places this account's claims
    /// hold there (`claimed`, A1 and A7, from core's claims and the vault's `records`), then (place, you) when there
    /// is a place, each unless the group's marked held places list exactly that pair, once each, less the keys
    /// already `answered`. A6 (I-B): no place is needed, so a rejoin whose new place's mark failed (core then voids
    /// the phone's claim and finds no place) is still asked.
    static func wanted(
        _ p: GroupsProjectionOut, records: [RecordWire], readOnly: Set<String>, answered: Set<String>
    ) -> [Held] {
        guard let you = p.you?.id else { return [] }
        return p.groups.flatMap { g -> [Held] in
            guard !readOnly.contains(g.group.id) else { return [] }
            var out: [Held] = []
            var all = claimed(p, g, records: records)
            if let place = g.place, !g.held.contains(where: { $0.memberId == place && $0.youId == you }) {
                all.append(Held(groupId: g.group.id, memberId: place, youId: you))
            }
            for held in all where !out.contains(held) { out.append(held) }
            return out.filter { !answered.contains($0.key) }
        }
    }

    /// One `heldPlace` record per place, stamped and authored by this phone through `groupOpRecord` (the group's
    /// scope), in the order given, NOT put into the vault.
    static func records(
        store: LedgerStore, places: [Held], nowMs: UInt64 = UInt64(Date().timeIntervalSince1970 * 1000)
    ) throws -> [RecordWire] {
        let device = try LedgerStore.Sync.deviceId(store)
        return try places.map { held in
            try LedgerStore.Sync.groupOpRecord(
                .heldPlace(groupId: held.groupId, memberId: held.memberId, youId: held.youId),
                hlc: try LedgerStore.Sync.nextHlc(store, nowMs: nowMs), deviceId: device)
        }
    }

    /// The records `share` sends, built one place at a time: a place whose record core cannot build is skipped and
    /// asked again at the next push; every other still goes. Each record with the place it carries.
    static func recordsByGroup(
        store: LedgerStore, places: [Held], nowMs: UInt64 = UInt64(Date().timeIntervalSince1970 * 1000)
    ) -> [(held: Held, record: RecordWire)] {
        places.compactMap { held in
            guard let built = try? records(store: store, places: [held], nowMs: nowMs), built.count == 1 else {
                return nil
            }
            return (held: held, record: built[0])
        }
    }

    /// The `sharePlaces` callable's data: the D-210 record shape.
    static func callData(_ records: [RecordWire]) -> [String: Any] {
        ["records": Removal.payload(records)]
    }

    /// The record ids the reply answered (`shared` and `skipped`).
    static func replied(_ reply: [String: Any]) -> Set<String> {
        Set(((reply["shared"] as? [String]) ?? []) + ((reply["skipped"] as? [String]) ?? []))
    }

    /// AliasShare's answers, the same meaning.
    typealias Answer = AliasShare.Answer

    static func answer(_ error: Error?) -> Answer { AliasShare.answer(error) }

    // MARK: - The answered triples

    static func answered(_ store: LedgerStore) -> Set<String> {
        guard let data = (try? store.kvGet(key: answeredKey)) ?? nil, !data.isEmpty else { return [] }
        return Set(String(decoding: data, as: UTF8.self).split(separator: "\n").map(String.init))
    }

    static func saveAnswered(_ store: LedgerStore, _ keys: Set<String>) throws {
        try store.kvSet(key: answeredKey, value: Data(keys.sorted().joined(separator: "\n").utf8))
    }

    // MARK: - The job

    /// The call, replaceable by the tests. Seam: tests only; production never reassigns.
    @MainActor static let realSend: ([String: Any]) async throws -> [String: Any] = { data in
        try await CloudFunctions.call("sharePlaces", data)
    }
    /// Tests only; production never reassigns.
    @MainActor static var send = realSend

    /// After a push that reached the cloud: asks for every wanted place of a group this phone knows is in the cloud
    /// (`CloudRecords.groupsUp`), in calls of at most 50, and remembers what the cloud answered. A failed call stops
    /// the run (`.later`); a refused call (`invalid-argument`) is an answer: its places are remembered and the run is
    /// `.done`, as AliasShare's.
    @MainActor @discardableResult
    static func share(store: LedgerStore, nowMs: UInt64 = UInt64(Date().timeIntervalSince1970 * 1000)) async -> Answer {
        guard let up = try? CloudRecords.groupsUp(store), let p = try? LedgerStore.Sync.projectGroups(store),
              let all = try? store.allRecords() else { return .later }
        let want = wanted(p, records: all, readOnly: Set(store.readOnly().keys), answered: answered(store))
            .filter { up.contains($0.groupId) }
        guard !want.isEmpty else { return .done }
        let pairs = recordsByGroup(store: store, places: want, nowMs: nowMs)
        guard !pairs.isEmpty else { return .later }
        let built = pairs.map { $0.record }
        let keyOf = Dictionary(pairs.map { (CloudRecords.documentId($0.record), $0.held.key) },
                               uniquingKeysWith: { a, _ in a })
        for start in stride(from: 0, to: built.count, by: callMax) {
            let chunk = Array(built[start..<min(start + callMax, built.count)])
            var reply: [String: Any] = [:]
            var failure: Error?
            do { reply = try await send(callData(chunk)) } catch { failure = error }
            let ids: Set<String>
            switch answer(failure) {
            case .done: ids = replied(reply)
            case .refused: ids = Set(chunk.map(CloudRecords.documentId))
            case .later: return .later
            }
            var done = answered(store)
            ids.compactMap { keyOf[$0] }.forEach { done.insert($0) }
            // A list that cannot be written is asked again next time (the Function counts a repeat as shared).
            try? saveAnswered(store, done)
        }
        return .done
    }
}
