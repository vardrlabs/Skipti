import Contacts
import CryptoKit
import FirebaseFirestore
import SwiftUI
import UIKit

/// D-197 (the Firebase plan's unit 7): finding people. Three ways, all among
/// people who let themselves be found (Settings, on by default; the founder's
/// decision 14): their @username, their exact email, or the emails in your
/// contacts (the founder, 2026-10-02: "match all contacts"). An email never
/// leaves this phone as text when you search: the phone sends its SHA-256
/// (trimmed, lower case), and SKIPTI's cloud compares it with the fingerprint
/// it keeps of each findable person's verified sign-in email, written only by
/// a Function. Someone found is invited as a named person of the group: an
/// invite in their account and a push. Android's twin is People.kt.
/// D-207: the add-person sheet's words. Android's twin is AddPersonCopy in People.kt.
enum AddPersonCopy {
    static let title = "Add a person"
    static let name = "Name"
    static let namePrompt = "Their name"
    static let username = "Username (optional)"
    static let usernamePrompt = "find them on SKIPTI"
    static let add = "Add"
    static let addAndInvite = "Add and invite"
    static let addOnly = "Add only"
    static let recents = "Recently with you"
    static let onSkipti = "On SKIPTI"
    static let added = "Added"
    static let addedAndInvited = "Added and invited"
    static let looking = "Looking…"
    static func missing(_ handle: String) -> String {
        "Nobody on SKIPTI is @\(handle). Check the spelling, or add the name and send an invite link later."
    }
    static func done(_ name: String, invited: Bool) -> String { invited ? "\(name) added and invited" : "\(name) added" }
}

enum PeopleCopy {
    static let findMe = "Find me"
    static let username = "Username"
    static func handle(_ h: String) -> String { "@\(h)" }
    static let noUsername = "Not set"
    static let chooseUsername = "Choose a username"
    static let changeUsername = "Change username"
    static let usernameTitle = "Your username"
    static let usernamePlaceholder = "username"
    static let usernameHelp = "3 to 20 letters, numbers or _. People can find you by it."
    static let usernameTaken = "That username is taken."
    static let usernameShape = "Use 3 to 20 letters, numbers or _."
    static let usernameReserved = "That username is not available."
    static let save = "Save"
    static let findableToggle = "Let people find me by my username or email"
    static let findableOn = "People who know your username or email can find you and invite you to their groups."
    static let findableOff = "Nobody can find you. You can still join a group with an invite link."

    static func findTitle(_ name: String) -> String { "Find \(name)" }
    static func findOnSkipti(_ name: String) -> String { "Find \(name) on SKIPTI" }
    static let queryPrompt = "Their username or email"
    static let search = "Search"
    static let searching = "Searching…"
    static let notAQuery = "Type a username (3 to 20 letters, numbers or _) or a whole email address."
    static let fromContacts = "Find friends in contacts"
    static let contactsDetail =
        "Each email in your contacts is turned into a fingerprint on this phone. SKIPTI's cloud compares the fingerprints and keeps none of them."
    static let contactsDenied = "SKIPTI cannot read your contacts. You can allow it in your phone's settings."
    static let noContacts = "None of your contacts have an email address."
    static let noMatch = "Nobody on SKIPTI matches. They may have switched off being found; an invite link always works."
    static let someone = "Someone on SKIPTI"
    static func inContacts(_ name: String) -> String { "In your contacts as \(name)" }
    static let invite = "Invite"
    static let inviting = "Inviting…"
    static func invited(_ name: String) -> String { "Invited. \(name) gets a notification and the invite in SKIPTI." }
    static let limit = "Too many for today. Try again tomorrow."
    static let alreadyIn = "They are already in this group."

    static let invitations = "Invitations"
    static func invitedYou(_ from: String?, _ group: String) -> String {
        (from.map { "\($0) invited you to \(group)" }) ?? "You are invited to \(group)"
    }
    static func joinAs(_ name: String) -> String { "You join as \(name)." }
    static let join = "Join"
    static let dismiss = "Dismiss"
    static let noInvitations = "No invitations."
}

/// An email's fingerprint, exactly as the cloud makes it (people.js).
enum EmailFingerprint {
    static func hash(_ email: String) -> String {
        let normal = email.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        return SHA256.hash(data: Data(normal.utf8)).map { String(format: "%02x", $0) }.joined()
    }

    static func looksLikeEmail(_ text: String) -> Bool {
        text.range(of: #"^[^@\s]+@[^@\s]+\.[^@\s]+$"#, options: .regularExpression) != nil
    }
}

/// What a search box holds: a username or a whole email, else nothing.
enum PeopleQuery: Equatable {
    case username(String)
    case email(String)

    static func parse(_ raw: String) -> PeopleQuery? {
        let text = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if text.contains("@") && !text.hasPrefix("@") {
            return EmailFingerprint.looksLikeEmail(text) ? .email(text) : nil
        }
        let handle = (text.hasPrefix("@") ? String(text.dropFirst()) : text).lowercased()
        return handle.range(of: "^[a-z0-9_]{3,20}$", options: .regularExpression) != nil ? .username(handle) : nil
    }
}

/// D-207 (the founder, 2026-10-13): someone from your other groups, offered
/// when you add people to a group. "On SKIPTI" when they joined one of those
/// groups (a claim), so they can be invited in one tap (inviteKnown).
struct RecentPerson: Equatable, Identifiable {
    let name: String
    /// How many of your other groups they are in (orders the list).
    let shared: Int
    /// Up to two of those groups' names, for the row's second line.
    let groups: [String]
    let participantId: String?
    /// A group you share with them that they joined (for inviteKnown).
    let viaGroupId: String?
    /// D-211: their preview (a picture or an emoji) from a group of theirs
    /// that has one; `.none` draws their initials.
    var preview: Avatar = .none
    var id: String { participantId ?? "name:" + name.lowercased() }
}

/// D-207: recents from what is already on this phone. Android's twin is
/// Recents in People.kt.
enum Recents {
    private static func key(_ name: String) -> String {
        name.split(whereSeparator: \.isWhitespace).joined(separator: " ").lowercased()
    }

    /// People from your other groups, for adding to `groupId`. Left out: every
    /// group's own self member, and anyone already in `groupId` (the same
    /// participant, or the same name ignoring case and spaces). One entry per
    /// participant; an unclaimed name that matches a participant's name is that
    /// person; other names once each. Most shared groups first, then people on
    /// SKIPTI, then by name; at most `limit`.
    static func people(
        groups: [NamedGroupWire], claims: [String: [ClaimOut]], groupId: String, limit: Int = 8,
        previews: [String: [String: Avatar]] = [:]
    ) -> [RecentPerson] {
        let target = groups.first { $0.group.id == groupId }
        let targetPids = Set((claims[groupId] ?? []).map(\.youId))
        let targetNames = Set((target?.members ?? []).map { key($0.name) })
        struct Acc { var name: String; var groups: [String]; var pid: String?; var via: String?; var seen: [(group: String, member: String)] = [] }
        var byPid: [String: Acc] = [:]
        var pidOrder: [String] = []
        var byName: [String: Acc] = [:]
        var nameOrder: [String] = []
        for g in groups where g.group.id != groupId {
            let claimOf = Dictionary((claims[g.group.id] ?? []).map { ($0.memberId, $0.youId) }, uniquingKeysWith: { a, _ in a })
            for m in g.members where m.id != g.selfId {
                let k = key(m.name)
                guard !k.isEmpty else { continue }
                if let pid = claimOf[m.id] {
                    guard !targetPids.contains(pid) else { continue }
                    if byPid[pid] == nil {
                        byPid[pid] = Acc(name: m.name.trimmingCharacters(in: .whitespaces), groups: [], pid: pid, via: g.group.id)
                        pidOrder.append(pid)
                    }
                    if !byPid[pid]!.groups.contains(g.group.name) { byPid[pid]!.groups.append(g.group.name) }
                    byPid[pid]!.seen.append((g.group.id, m.id))
                } else {
                    if byName[k] == nil {
                        byName[k] = Acc(name: m.name.trimmingCharacters(in: .whitespaces), groups: [], pid: nil, via: nil)
                        nameOrder.append(k)
                    }
                    if !byName[k]!.groups.contains(g.group.name) { byName[k]!.groups.append(g.group.name) }
                    byName[k]!.seen.append((g.group.id, m.id))
                }
            }
        }
        // An unclaimed name that matches a participant's name is that person.
        for k in nameOrder {
            if let pid = pidOrder.first(where: { key(byPid[$0]!.name) == k }) {
                for name in byName[k]!.groups where !byPid[pid]!.groups.contains(name) { byPid[pid]!.groups.append(name) }
                byPid[pid]!.seen.append(contentsOf: byName[k]!.seen)
                byName[k] = nil
            }
        }
        let all = pidOrder.map { byPid[$0]! } + nameOrder.compactMap { byName[$0] }
        return all
            .filter { !targetNames.contains(key($0.name)) }
            .map { RecentPerson(name: $0.name, shared: $0.groups.count, groups: Array($0.groups.prefix(2)),
                                participantId: $0.pid, viaGroupId: $0.via,
                                // The card is about no one group: the first that has a preview.
                                preview: MemberPreviews.pick(previews, candidates: $0.seen, preferred: nil)) }
            .sorted {
                if $0.shared != $1.shared { return $0.shared > $1.shared }
                if ($0.participantId != nil) != ($1.participantId != nil) { return $0.participantId != nil }
                return $0.name.lowercased() < $1.name.lowercased()
            }
            .prefix(limit)
            .map { $0 }
    }
}

struct FoundPerson: Equatable, Identifiable {
    let uid: String
    let name: String?
    let username: String?
    let matched: String?
    var id: String { uid }

    /// The line a result reads as: their name, else their handle, else
    /// "Someone on SKIPTI"; the contact's own name when it came from contacts.
    func title(contacts: [String: String] = [:]) -> String {
        name ?? username.map(PeopleCopy.handle) ?? matched.flatMap { contacts[$0] } ?? PeopleCopy.someone
    }

    func detail(contacts: [String: String] = [:]) -> String? {
        let parts = [username.map(PeopleCopy.handle), matched.flatMap { contacts[$0] }.map(PeopleCopy.inContacts)]
        let line = parts.compactMap { $0 }.joined(separator: Separator.meta)
        return line.isEmpty ? nil : line
    }
}

enum People {
    static let findableKey = "find/findable"
    static let syncedKey = "find/synced"
    static let chunk = 500

    /// findPeople's result: well-formed people only.
    static func decode(_ result: [String: Any]) -> [FoundPerson] {
        ((result["people"] as? [[String: Any]]) ?? []).compactMap { p in
            guard let uid = p["uid"] as? String, !uid.isEmpty else { return nil }
            let matched = (p["matched"] as? String).flatMap { $0.range(of: "^[0-9a-f]{64}$", options: .regularExpression) != nil ? $0 : nil }
            return FoundPerson(uid: uid, name: p["name"] as? String, username: p["username"] as? String, matched: matched)
        }
    }

    @MainActor
    static func find(_ query: PeopleQuery) async throws -> [FoundPerson] {
        switch query {
        case .username(let handle):
            return decode(try await CloudFunctions.call("findPeople", ["username": handle]))
        case .email(let email):
            return try await find(hashes: [EmailFingerprint.hash(email)])
        }
    }

    @MainActor
    static func find(hashes: [String]) async throws -> [FoundPerson] {
        var out: [FoundPerson] = []
        let unique = Array(Set(hashes)).sorted()
        for start in stride(from: 0, to: unique.count, by: chunk) {
            let part = Array(unique[start..<min(start + chunk, unique.count)])
            out += decode(try await CloudFunctions.call("findPeople", ["emailHashes": part]))
        }
        var seen = Set<String>()
        return out.filter { seen.insert($0.uid).inserted }
    }

    /// D-206: your @username from your cloud profile, or nil (no account,
    /// none chosen, or offline). FindMeCard and the You hero read it.
    @MainActor
    static func myUsername() async -> String? {
        guard let uid = Account.shared.uid else { return nil }
        let profile = try? await Cloud.db.collection("users").document(uid).getDocument()
        return profile?.data()?["username"] as? String
    }

    @MainActor
    /// `first`: the required step's save (C2), which the cloud refuses once
    /// a username exists, so it never spends the one change.
    static func setUsername(_ handle: String?, first: Bool = false) async throws -> String? {
        var payload: [String: Any] = ["username": handle ?? NSNull()]
        if first { payload["first"] = true }
        let result = try await CloudFunctions.call("setUsername", payload)
        return result["username"] as? String
    }

    /// C2: the account's username and whether its one change is left; nil
    /// when there is no profile to read (signed out, offline, not registered).
    @MainActor
    static func accountUsername() async -> UsernameState? {
        guard let uid = Account.shared.uid,
              let doc = try? await Cloud.db.collection("users").document(uid).getDocument(source: .server),
              doc.exists else { return nil }
        return UsernameState.from(doc.data())
    }

    enum UsernameAvailability: Equatable { case available, taken, shape, unavailable }

    /// C2: the live check while typing.
    @MainActor
    static func checkUsername(_ raw: String) async throws -> UsernameAvailability {
        let result = try await CloudFunctions.call("checkUsername", ["username": raw])
        if result["available"] as? Bool == true { return .available }
        switch result["reason"] as? String {
        case "taken": return .taken
        case "shape": return .shape
        default: return .unavailable
        }
    }

    /// C2: up to three free usernames from a name; [] on any failure.
    @MainActor
    static func suggestUsernames(name: String) async -> [String] {
        guard let result = try? await CloudFunctions.call("suggestUsernames", ["name": name]) else { return [] }
        return (result["usernames"] as? [String] ?? []).filter { UsernameRules.problem($0) == nil }
    }

    /// This phone's choice (absent = on, the founder's default), sent to the
    /// cloud whenever it or the account changes.
    static func findable(_ store: LedgerStore) -> Bool {
        guard let raw = try? store.kvGet(key: findableKey) else { return true }
        return String(decoding: raw, as: UTF8.self) != "0"
    }

    @MainActor
    static func setFindable(_ on: Bool, store: LedgerStore) async throws {
        try store.kvSet(key: findableKey, value: Data((on ? "1" : "0").utf8))
        _ = try await CloudFunctions.call("setFindable", ["findable": on])
        if let uid = Account.shared.uid { try? store.kvSet(key: syncedKey, value: Data("\(uid):\(on ? 1 : 0)".utf8)) }
    }

    /// After a sign-in (CloudSync.registerPhone): the cloud learns this
    /// phone's choice once per account and choice.
    @MainActor
    static func syncFindable(uid: String, store: LedgerStore) async {
        let on = findable(store)
        let want = "\(uid):\(on ? 1 : 0)"
        if let raw = try? store.kvGet(key: syncedKey), String(decoding: raw, as: UTF8.self) == want { return }
        try? await setFindable(on, store: store)
    }

    @MainActor
    static func invite(store: LedgerStore, group: NamedGroupWire, member: MemberWire, person: FoundPerson) async throws {
        guard let uid = Account.shared.uid else { throw CloudFunctions.Failure.signedOut }
        // D-254: before the group is put in the cloud (a write the rules refuse an account not approved).
        if let refusal = await Approval.shared.refusal(for: "inviteUser", uid: uid, store: store) { throw refusal }
        switch await CloudSync.ensureGroup(group.group.id, name: group.group.name, uid: uid) {
        case .member: break
        case .notMember: throw CloudFunctions.Failure.refused("PERMISSION_DENIED")
        case .offline: throw CloudFunctions.Failure.offline
        }
        var data: [String: Any] = [
            "groupId": group.group.id, "memberId": member.id, "memberName": member.name,
            "groupName": group.group.name, "targetUid": person.uid,
        ]
        if let inviter = (try? store.loadSelf())?.name { data["inviterName"] = inviter }
        _ = try await CloudFunctions.call("inviteUser", data)
    }

    /// D-207: invites someone from your recents who joined a group you share
    /// (inviteKnown); the cloud finds their account, the phone never sees it.
    @MainActor
    static func inviteKnown(store: LedgerStore, group: NamedGroupWire, member: MemberWire, recent: RecentPerson) async throws {
        guard let uid = Account.shared.uid else { throw CloudFunctions.Failure.signedOut }
        guard let participantId = recent.participantId, let via = recent.viaGroupId else { return }
        // D-254: before the group is put in the cloud (a write the rules refuse an account not approved).
        if let refusal = await Approval.shared.refusal(for: "inviteKnown", uid: uid, store: store) { throw refusal }
        switch await CloudSync.ensureGroup(group.group.id, name: group.group.name, uid: uid) {
        case .member: break
        case .notMember: throw CloudFunctions.Failure.refused("PERMISSION_DENIED")
        case .offline: throw CloudFunctions.Failure.offline
        }
        var data: [String: Any] = [
            "groupId": group.group.id, "memberId": member.id, "memberName": member.name,
            "groupName": group.group.name, "viaGroupId": via, "participantId": participantId,
        ]
        if let inviter = (try? store.loadSelf())?.name { data["inviterName"] = inviter }
        _ = try await CloudFunctions.call("inviteKnown", data)
    }

    /// D-207: the recents for adding to `groupID`, from this phone's ledger.
    static func recents(_ store: LedgerStore, groupID: String) -> [RecentPerson] {
        guard let groups = try? store.namedGroups(),
              let projected = try? LedgerStore.Sync.projectGroups(store) else { return [] }
        let claims = Dictionary(projected.groups.map { ($0.group.id, $0.claims) }, uniquingKeysWith: { a, _ in a })
        // D-211: a preview is decoration (PeopleScreen keeps the last good ones when a
        // read fails); a failed read here only means initials.
        let previews = (try? store.memberPreviews()) ?? [:]
        return Recents.people(groups: groups, claims: claims, groupId: groupID, previews: previews)
    }

    static func failure(_ error: Error) -> String {
        switch error as? CloudFunctions.Failure {
        case .refused("ALREADY_EXISTS")?: return PeopleCopy.usernameTaken
        case .refused("RESOURCE_EXHAUSTED")?: return PeopleCopy.limit
        case .refused("FAILED_PRECONDITION")?: return PeopleCopy.alreadyIn
        case .refused("NOT_FOUND")?: return PeopleCopy.noMatch
        default: return InviteCopy.failure(error)
        }
    }

    /// D-218: the failure of an invite call (inviteUser, inviteKnown). `already-exists` there
    /// means the place is held; on the username path it still means "taken", so only the
    /// invite call sites read it this way.
    static func inviteFailure(_ error: Error) -> String {
        if case .refused("ALREADY_EXISTS")? = error as? CloudFunctions.Failure { return InviteCopy.held }
        return failure(error)
    }

    /// Why a username cannot be saved, before asking the cloud (C2: the
    /// cloud's own list, bundled).
    static func usernameProblem(_ raw: String) -> String? {
        switch UsernameRules.problem(raw) {
        case .shape?: return PeopleCopy.usernameShape
        case .unavailable?: return PeopleCopy.usernameReserved
        case nil: return nil
        }
    }
}

/// Your contacts' emails, read only when you ask, kept only on screen.
enum ContactBook {
    struct Entry: Equatable {
        let name: String
        let email: String
    }

    enum Problem: Error { case denied }

    static func entries() async throws -> [Entry] {
        let store = CNContactStore()
        let granted = try await store.requestAccess(for: .contacts)
        guard granted else { throw Problem.denied }
        return try await Task.detached(priority: .userInitiated) {
            var out: [Entry] = []
            let keys = [CNContactGivenNameKey, CNContactFamilyNameKey, CNContactEmailAddressesKey] as [CNKeyDescriptor]
            try store.enumerateContacts(with: CNContactFetchRequest(keysToFetch: keys)) { contact, _ in
                let name = [contact.givenName, contact.familyName].filter { !$0.isEmpty }.joined(separator: " ")
                for address in contact.emailAddresses {
                    out.append(Entry(name: name, email: address.value as String))
                }
            }
            return out
        }.value
    }

    /// Fingerprint → the contact's name (the first one with that email).
    static func byHash(_ entries: [Entry]) -> [String: String] {
        var out: [String: String] = [:]
        for e in entries where EmailFingerprint.looksLikeEmail(e.email.trimmingCharacters(in: .whitespaces)) {
            let key = EmailFingerprint.hash(e.email)
            if out[key] == nil { out[key] = e.name.isEmpty ? e.email : e.name }
        }
        return out
    }
}

/// Invitations sent to this account (users/{uid}/invites), while signed in
/// with sync on.
@MainActor
final class IncomingInvites: ObservableObject {
    static let shared = IncomingInvites()

    struct Item: Equatable, Identifiable {
        let id: String
        let groupName: String
        let fromName: String?
        let memberName: String
        let token: String
        /// The group it joins (D-208); nil in an invitation older than that field.
        var groupId: String? = nil
        var link: String { InviteLink.make(token: token) }
    }

    @Published private(set) var items: [Item] = []
    private var listener: ListenerRegistration?
    private var uid: String?

    /// A document the way inviteUser writes it, or nil.
    nonisolated static func item(id: String, data: [String: Any]) -> Item? {
        guard let group = data["groupName"] as? String, !group.isEmpty,
              let member = data["memberName"] as? String, !member.isEmpty,
              let token = data["token"] as? String,
              token.range(of: "^[A-Za-z0-9_-]{43}$", options: .regularExpression) != nil else { return nil }
        let groupId = (data["groupId"] as? String).flatMap { $0.range(of: "^[0-9a-f]{32}$", options: .regularExpression) != nil ? $0 : nil }
        return Item(id: id, groupName: group, fromName: data["fromName"] as? String, memberName: member, token: token,
                    groupId: groupId)
    }

    /// D-208: the invitations worth offering: not one to a group already on
    /// this phone, whose Join could only say "already used".
    nonisolated static func waiting(_ items: [Item], joined: Set<String>) -> [Item] {
        items.filter { item in item.groupId.map { !joined.contains($0) } ?? true }
    }

    func listen(uid: String) {
        guard listener == nil else { return }
        self.uid = uid
        listener = Cloud.db.collection("users").document(uid).collection("invites")
            .addSnapshotListener { snapshot, _ in
                guard let docs = snapshot?.documents else { return }
                let items = docs.compactMap { IncomingInvites.item(id: $0.documentID, data: $0.data()) }
                MainActor.assumeIsolated { IncomingInvites.shared.items = items }
            }
    }

    func stop() {
        listener?.remove()
        listener = nil
        uid = nil
        items = []
    }

    func dismiss(_ item: Item) {
        guard let uid else { return }
        items.removeAll { $0.id == item.id }
        Cloud.db.collection("users").document(uid).collection("invites").document(item.id).delete()
    }
}

// MARK: - Screens

/// C2: the change sheet's item: the handle it was opened for.
struct UsernameChange: Identifiable {
    let from: String
    var id: String { from }
}

/// Settings: your username and whether people can find you (account only).
struct FindMeCard: View {
    let store: LedgerStore
    @State private var findable = true
    @State private var state: UsernameState?
    @State private var picking = false
    /// The open change sheet and the handle it was opened for. One value, so
    /// the sheet is built from it on first presentation (a separate Bool and
    /// handle built it from a stale nil and showed a blank sheet).
    @State private var changing: UsernameChange?
    @State private var error: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(verbatim: PeopleCopy.findMe)
                .sectionLabel()
                .foregroundStyle(Color(Tokens.textDim))
            HStack {
                Text(verbatim: PeopleCopy.username).font(TypeRole.body()).foregroundStyle(Tokens.text)
                Spacer()
                switch state {
                case .canChange(let h)?, .final(let h)?:
                    Text(verbatim: PeopleCopy.handle(h)).font(TypeRole.body()).foregroundStyle(Tokens.text)
                case UsernameState.none?:
                    Text(verbatim: PeopleCopy.noUsername).font(TypeRole.body()).foregroundStyle(Tokens.textDim)
                case nil:
                    // Unread (offline): say nothing rather than "Not set".
                    EmptyView()
                }
            }
            // C2: set once, changed once; never removed.
            switch state {
            case .canChange(let h)?:
                Text(verbatim: UsernameCopy.oneChangeLeft).font(TypeRole.body(14)).foregroundStyle(Tokens.textDim)
                Button(PeopleCopy.changeUsername) { changing = UsernameChange(from: h) }
                .buttonStyle(SecondaryButtonStyle())
            case .final?:
                Text(verbatim: UsernameCopy.changedOnce).font(TypeRole.body(14)).foregroundStyle(Tokens.textDim)
            case UsernameState.none?:
                Button(PeopleCopy.chooseUsername) { picking = true }.buttonStyle(SecondaryButtonStyle())
            case nil:
                EmptyView()
            }
            // D-225 (Design 3): the drawn switch, the whole row toggling it.
            ListRow(title: PeopleCopy.findableToggle, trailing: .toggle(Binding(get: { findable }, set: { on in
                findable = on
                Task {
                    do {
                        try await People.setFindable(on, store: store)
                        error = nil
                    } catch {
                        self.error = People.failure(error)
                    }
                }
            })))
            Text(verbatim: findable ? PeopleCopy.findableOn : PeopleCopy.findableOff)
                .font(TypeRole.body(14))
                .foregroundStyle(Tokens.textDim)
            if let error {
                Text(verbatim: error).font(TypeRole.body(14)).foregroundStyle(Tokens.owed)
            }
        }
        .glassCard()
        .task { await load() }
        .sheet(item: $changing) { change in
            NavigationStack {
                UsernameChangeSheet(current: change.from, store: store, onSaved: { saved in
                    // Close first, then update what the card shows.
                    changing = nil
                    state = .final(saved)
                }, onUsed: {
                    // The one change was spent elsewhere: show the real handle.
                    changing = nil
                    Task { await readState() }
                })
            }
        }
        .sheet(isPresented: $picking) {
            NavigationStack {
                UsernameStep(store: store, onCancel: { picking = false }) {
                    picking = false
                    Task { await readState() }
                }
            }
        }
    }

    private func load() async {
        findable = People.findable(store)
        await readState()
    }

    /// A failed read keeps what was known (C2, UsernameState.merge).
    private func readState() async {
        let read = await People.accountUsername()
        state = UsernameState.merge(known: state, read: read, signedIn: Account.shared.isSignedIn)
    }
}

/// Find one named member of a group on SKIPTI, then invite them.
struct FindPersonScreen: View {
    let store: LedgerStore
    let group: NamedGroupWire
    let member: MemberWire

    @State private var query = ""
    @State private var results: [FoundPerson]?
    @State private var contacts: [String: String] = [:]
    @State private var busy = false
    /// Which of the two buttons is working: only Search says "Searching…".
    @State private var searchingByName = false
    @State private var message: String?
    @State private var invitedUID: String?
    @State private var invitingUID: String?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                // D-254: one line instead of the search and Find friends in contacts while the account is not approved.
                if let line = ApprovalCopy.invite(Approval.shared.state) {
                    Text(verbatim: line).font(TypeRole.body()).foregroundStyle(Tokens.textDim)
                } else {
                    findControls
                }
            }
            .padding(16)
        }
        .auroraBackground()
        .navigationTitle(PeopleCopy.findTitle(member.name))
        .navigationBarTitleDisplayMode(.inline)
    }

    @ViewBuilder
    private var findControls: some View {
        TextField(PeopleCopy.queryPrompt, text: $query)
            .textInputAutocapitalization(.never)
            .autocorrectionDisabled()
            .keyboardType(.emailAddress)
            .font(TypeRole.body())
            .padding(14)
            .glassCard(padding: 0)
            .onSubmit { Task { await search() } }
            .accessibilityLabel(PeopleCopy.queryPrompt)
        Button(searchingByName ? PeopleCopy.searching : PeopleCopy.search) { Task { await search() } }
            .buttonStyle(PrimaryButtonStyle())
            .disabled(busy)
        Button(PeopleCopy.fromContacts) { Task { await searchContacts() } }
            .buttonStyle(SecondaryButtonStyle())
            .disabled(busy)
        Text(verbatim: PeopleCopy.contactsDetail).font(TypeRole.body(14)).foregroundStyle(Tokens.textDim)
        if let message {
            Text(verbatim: message).font(TypeRole.body()).foregroundStyle(Tokens.text).glassCard()
        }
        if let results, !results.isEmpty {
            VStack(spacing: 0) {
                ForEach(Array(results.enumerated()), id: \.element.id) { i, person in
                    if i > 0 { Divider().overlay(Tokens.hairline) }
                    row(person)
                }
            }
            .glassCard(padding: 14)
        }
    }

    private func row(_ person: FoundPerson) -> some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 3) {
                Text(verbatim: person.title(contacts: contacts)).font(TypeRole.label(15)).foregroundStyle(Tokens.text)
                if let detail = person.detail(contacts: contacts) {
                    Text(verbatim: detail).font(TypeRole.body(13)).foregroundStyle(Tokens.textDim)
                }
            }
            Spacer(minLength: 8)
            if invitedUID != person.uid {
                Button(invitingUID == person.uid ? PeopleCopy.inviting : PeopleCopy.invite) { Task { await invite(person) } }
                    .buttonStyle(SecondaryButtonStyle())
                    .fixedSize()
                    .disabled(invitingUID != nil || invitedUID != nil)
            }
        }
        .padding(.vertical, 8)
    }

    private func search() async {
        guard let parsed = PeopleQuery.parse(query) else {
            message = PeopleCopy.notAQuery
            return
        }
        busy = true
        searchingByName = true
        defer {
            busy = false
            searchingByName = false
        }
        do {
            let found = try await People.find(parsed)
            results = found
            message = found.isEmpty ? PeopleCopy.noMatch : nil
        } catch {
            message = People.failure(error)
        }
    }

    private func searchContacts() async {
        busy = true
        defer { busy = false }
        do {
            let byHash = ContactBook.byHash(try await ContactBook.entries())
            guard !byHash.isEmpty else {
                message = PeopleCopy.noContacts
                return
            }
            contacts = byHash
            let found = try await People.find(hashes: Array(byHash.keys))
            results = found
            message = found.isEmpty ? PeopleCopy.noMatch : nil
        } catch ContactBook.Problem.denied {
            message = PeopleCopy.contactsDenied
        } catch {
            message = People.failure(error)
        }
    }

    private func invite(_ person: FoundPerson) async {
        invitingUID = person.uid
        defer { invitingUID = nil }
        do {
            try await People.invite(store: store, group: group, member: member, person: person)
            invitedUID = person.uid
            message = PeopleCopy.invited(person.title(contacts: contacts))
            await Push.shared.askPermission()
        } catch {
            message = People.inviteFailure(error)
        }
    }
}

/// Invitations to this account, each with Join and Dismiss.
struct InvitationsList: View {
    @ObservedObject var invites = IncomingInvites.shared
    /// The groups already on this phone (D-208): their invitations are not offered.
    var joined: Set<String>
    /// The invitation being joined; the screen that owns it presents the join
    /// sheet (`joinInvitationSheet`), never this list (D-208).
    @Binding var joining: IncomingInvites.Item?

    var body: some View {
        let waiting = IncomingInvites.waiting(invites.items, joined: joined)
        if !waiting.isEmpty {
            VStack(alignment: .leading, spacing: 10) {
                Text(verbatim: PeopleCopy.invitations).font(TypeRole.label(17)).foregroundStyle(Tokens.text)
                ForEach(waiting) { item in
                    VStack(alignment: .leading, spacing: 8) {
                        Text(verbatim: PeopleCopy.invitedYou(item.fromName, item.groupName))
                            .font(TypeRole.label(15)).foregroundStyle(Tokens.text)
                        Text(verbatim: PeopleCopy.joinAs(item.memberName))
                            .font(TypeRole.body(14)).foregroundStyle(Tokens.textDim)
                        HStack(spacing: 10) {
                            Button(PeopleCopy.join) { joining = item }.buttonStyle(PrimaryButtonStyle())
                            Button(PeopleCopy.dismiss) { invites.dismiss(item) }.buttonStyle(SecondaryButtonStyle())
                        }
                    }
                    .glassCard()
                }
            }
        }
    }
}

extension View {
    /// D-208: the join sheet for an invitation, on a view that is always
    /// there. Joining restarts sync, which empties the invitations for a
    /// moment (and the joined one then leaves for good); the sheet used to sit
    /// on the list inside `if !items.isEmpty`, so it was torn down mid-join
    /// and presented again, and the new screen asked about the spent invite:
    /// "This invite was already used".
    func joinInvitationSheet(_ joining: Binding<IncomingInvites.Item?>, store: LedgerStore,
                             onJoined: @escaping () -> Void) -> some View {
        sheet(item: joining) { item in
            NavigationStack {
                // D-217: Cancel is "Not now" (the invitation stays in the list); JoinScreen shows it only before a
                // join starts, so a joined sheet leaves through Done and the invitation is dismissed.
                JoinScreen(store: store, link: item.link, onJoined: { _ in
                    IncomingInvites.shared.dismiss(item)
                    joining.wrappedValue = nil
                    onJoined()
                }, onCancel: { joining.wrappedValue = nil })
            }
        }
    }
}

/// Opened by a tapped invite push.
struct InvitationsScreen: View {
    @ObservedObject var invites = IncomingInvites.shared
    let store: LedgerStore
    var onDone: () -> Void
    @State private var joining: IncomingInvites.Item?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                let joined = Set(((try? store.namedGroups()) ?? []).map(\.group.id))
                if IncomingInvites.waiting(invites.items, joined: joined).isEmpty {
                    Text(verbatim: PeopleCopy.noInvitations).font(TypeRole.body()).foregroundStyle(Tokens.textDim).glassCard()
                }
                InvitationsList(joined: joined, joining: $joining)
            }
            .padding(16)
        }
        .joinInvitationSheet($joining, store: store, onJoined: onDone)
        .auroraBackground()
        .navigationTitle(PeopleCopy.invitations)
        .navigationBarTitleDisplayMode(.inline)
    }
}

/// D-210 (Task 8 look, found on devices): adding a person is a group
/// operation, and it has to reach the cloud log before the invitee can join and
/// claim the place. `add` saves it and then calls `synced` with the group (the
/// sync's `ledgerChanged`, and the screen's refresh); nothing is called when
/// the add is refused. Android's twin is `AddPerson` in People.kt.
enum AddPerson {
    static func add(
        store: LedgerStore, groupID: String, name: String, synced: (NamedGroupWire) -> Void
    ) throws -> (NamedGroupWire, MemberWire)? {
        let before = Set(((try? store.namedGroups().first { $0.group.id == groupID })?.members ?? []).map(\.id))
        let named = try store.addMember(groupID: groupID, name: name)
        synced(named)
        guard let member = named.members.first(where: { !before.contains($0.id) }) else { return nil }
        return (named, member)
    }
}

/// D-207 (the founder's item 1 and his recents note, 2026-10-13; mock-up
/// https://claude.ai/artifact/9rN75e4BCcew1GHWaWdFUQ): a name, an optional
/// @username looked up and shown before anything is added, "Add and invite"
/// in one tap, and recents from your other groups. Without an account the
/// username field, the "On SKIPTI" marks and inviting are absent
/// (GUARANTEES #5); names work as before. Android's twin is AddPersonSheet.
struct AddPersonSheet: View {
    let store: LedgerStore
    let groupID: String
    /// The group after each add, for the People screen.
    let onAdded: (NamedGroupWire) -> Void
    @Environment(\.dismiss) private var dismiss

    private enum Lookup: Equatable { case idle, looking, found(FoundPerson), missing(String), failed(String) }

    @State private var name = ""
    @State private var handle = ""
    @State private var lookup: Lookup = .idle
    @State private var recents: [RecentPerson] = []
    @State private var added: Set<String> = []
    @State private var busy = false
    /// D-224 (Design 8): this sheet's own done moments (it sits above the shell's host), on the app's one success gate.
    @State private var localDone = DoneCenter()
    @State private var error: String?
    private var signedIn: Bool { Account.shared.isSignedIn }
    /// D-254: signed in, and the account not waiting or refused: Add by @username and the recents' invites are there.
    private var invites: Bool { signedIn && ApprovalCopy.invite(Approval.shared.state) == nil }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                field(AddPersonCopy.name) {
                    TextField(AddPersonCopy.namePrompt, text: $name)
                        .textInputAutocapitalization(.words)
                        .submitLabel(.done)
                        .onSubmit { if lookup == .idle { Task { await addName() } } }
                }
                if signedIn, let line = ApprovalCopy.invite(Approval.shared.state) {
                    // D-254: one line instead of Add by @username while the account is not approved.
                    Text(verbatim: line).font(TypeRole.body(14)).foregroundStyle(Tokens.textDim)
                } else if signedIn {
                    field(AddPersonCopy.username) {
                        HStack(spacing: 4) {
                            Text(verbatim: "@").font(TypeRole.body()).foregroundStyle(Tokens.accent)
                            TextField(AddPersonCopy.usernamePrompt, text: $handle)
                                .textInputAutocapitalization(.never)
                                .autocorrectionDisabled()
                                .submitLabel(.search)
                                .onSubmit { Task { await find() } }
                                .onChange(of: handle) { if lookup != .looking { lookup = .idle } }
                        }
                    }
                }
                lookupView
                if lookup == .idle || lookup == .missing(handle) {
                    Button(AddPersonCopy.add) { Task { await addName() } }
                        .buttonStyle(PrimaryButtonStyle())
                        .disabled(busy || name.trimmingCharacters(in: .whitespaces).isEmpty)
                }
                if !recents.isEmpty {
                    Text(verbatim: AddPersonCopy.recents)
                        .sectionLabel()
                        .foregroundStyle(Tokens.textDim)
                        .padding(.top, 6)
                    VStack(spacing: 8) { ForEach(recents) { recentRow($0) } }
                }
                if let error {
                    Text(verbatim: error).font(TypeRole.body(14)).foregroundStyle(Tokens.owed)
                }
            }
            .padding(20)
        }
        .auroraBackground()
        .navigationTitle(AddPersonCopy.title)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Done") { dismiss() }.barButtonText().barTint() } }
        // D-224 (Design 8): the done bar became the toast, with the same words, drawn by this sheet's own host.
        .overlay { DoneToastHost(center: localDone) }
        .task { recents = People.recents(store, groupID: groupID) }
    }

    @ViewBuilder
    private var lookupView: some View {
        switch lookup {
        case .idle:
            EmptyView()
        case .looking:
            HStack(spacing: 8) { ProgressView(); Text(verbatim: AddPersonCopy.looking).font(TypeRole.body()).foregroundStyle(Tokens.textDim) }
        case .found(let person):
            VStack(alignment: .leading, spacing: 12) {
                HStack(spacing: 12) {
                    AvatarView(avatar: .none, name: person.name ?? person.username, size: 44)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(verbatim: person.title()).font(TypeRole.label(16)).foregroundStyle(Tokens.text)
                        if let u = person.username {
                            Separator.text(["@" + u, AddPersonCopy.onSkipti], size: 13).font(TypeRole.body(13)).foregroundStyle(Tokens.textDim)
                        }
                    }
                }
                HStack(spacing: 10) {
                    Button(AddPersonCopy.addAndInvite) { Task { await add(person, invite: true) } }
                        .buttonStyle(PrimaryButtonStyle())
                    Button(AddPersonCopy.addOnly) { Task { await add(person, invite: false) } }
                        .buttonStyle(SecondaryButtonStyle())
                }
                .disabled(busy)
            }
            .glassCard()
        case .missing(let h):
            Text(verbatim: AddPersonCopy.missing(h)).font(TypeRole.body(14)).foregroundStyle(Tokens.textDim).glassCard()
        case .failed(let message):
            Text(verbatim: message).font(TypeRole.body(14)).foregroundStyle(Tokens.owed)
        }
    }

    private func recentRow(_ r: RecentPerson) -> some View {
        let isAdded = added.contains(r.id)
        return Button { Task { await addRecent(r) } } label: {
            HStack(spacing: 10) {
                AvatarView(avatar: r.preview, name: r.name, size: 32)
                VStack(alignment: .leading, spacing: 1) {
                    Text(verbatim: r.name).font(TypeRole.body(15)).foregroundStyle(Tokens.text)
                    Text(verbatim: isAdded ? (invites && r.participantId != nil ? AddPersonCopy.addedAndInvited : AddPersonCopy.added)
                         : r.groups.joined(separator: ", "))
                        .font(TypeRole.body(12)).foregroundStyle(Tokens.textDim).lineLimit(1)
                }
                Spacer(minLength: 8)
                if invites && r.participantId != nil || isAdded {
                    Text(verbatim: isAdded ? AddPersonCopy.added : AddPersonCopy.onSkipti)
                        .font(TypeRole.label(11))
                        .padding(.horizontal, 8).padding(.vertical, 3)
                        .themePill(isAdded ? .selected : .accent)
                }
            }
            .padding(10)
            .glassCard(padding: 0)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(busy || isAdded)
        .accessibilityLabel(Text(verbatim: r.name))
    }

    private func field<Content: View>(_ label: String, @ViewBuilder _ content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(verbatim: label).sectionLabel().foregroundStyle(Tokens.textDim)
            content().font(TypeRole.body()).padding(12).glassCard(padding: 0)
        }
    }

    private func find() async {
        guard case .username(let h)? = PeopleQuery.parse("@" + handle.replacingOccurrences(of: "@", with: "")) else {
            lookup = .failed(PeopleCopy.notAQuery)
            return
        }
        lookup = .looking
        do {
            let found = try await People.find(.username(h))
            lookup = found.first.map { .found($0) } ?? .missing(h)
            if case .found(let p) = lookup, name.trimmingCharacters(in: .whitespaces).isEmpty { name = p.name ?? h }
        } catch {
            lookup = .failed(People.failure(error))
        }
    }

    /// Adds `memberName`; returns the group and the new member.
    private func addMember(_ memberName: String) throws -> (NamedGroupWire, MemberWire)? {
        // Every path (name only, add and invite, recents) comes through here,
        // and the sync hears of the operation as soon as it is saved.
        try AddPerson.add(store: store, groupID: groupID, name: memberName) { named in
            CloudSync.shared.ledgerChanged()
            onAdded(named)
        }
    }

    private func addName() async {
        let n = name.trimmingCharacters(in: .whitespaces)
        guard !n.isEmpty, !busy else { return }
        await run(n) { _ = try addMember(n); return false }
    }

    private func add(_ person: FoundPerson, invite: Bool) async {
        let n = name.trimmingCharacters(in: .whitespaces).isEmpty ? person.title() : name.trimmingCharacters(in: .whitespaces)
        await run(n) {
            guard let pair = try addMember(n) else { return false }
            let (named, member) = pair
            if invite { try await People.invite(store: store, group: named, member: member, person: person) }
            return invite
        }
        handle = ""
        lookup = .idle
    }

    private func addRecent(_ r: RecentPerson) async {
        await run(r.name) {
            guard let pair = try addMember(r.name) else { return false }
            let (named, member) = pair
            guard invites, r.participantId != nil else { return false }
            try await People.inviteKnown(store: store, group: named, member: member, recent: r)
            return true
        }
        added.insert(r.id)
    }

    /// One add: busy while it runs, the done toast after, the error if not.
    private func run(_ who: String, _ work: () async throws -> Bool) async {
        busy = true
        defer { busy = false }
        do {
            let invited = try await work()
            error = nil
            name = ""
            localDone.post(DoneMoments.line(AddPersonCopy.done(who, invited: invited)))
        } catch let e as CoreError {
            error = GroupCopy.message(e)
        } catch {
            self.error = People.inviteFailure(error)
        }
    }
}

