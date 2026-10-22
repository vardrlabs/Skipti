import FirebaseFirestore
import Foundation

/// D-210: who may do what in a group (one owner, admins, members), the words
/// for it, the roles the cloud reports, and the notices it leaves for an
/// account. The founder's rules (2026-10-13): one owner; only the owner makes
/// an owner (the old owner becomes an admin); the owner or an admin makes an
/// admin and only the owner takes it away; the owner or an admin removes
/// members, never the owner, and an admin never removes an admin; nobody
/// leaves, a member asks to be removed. Android's twin is Roles.kt.

// MARK: - The rules (pure)

enum RoleRules {
    /// The cloud's list, as the account ("you") ids the phones know people by.
    struct Roles: Equatable, Codable {
        var owner: String?
        var admins: [String]
        /// D-227 (Design 5): the same roles as the places the group knows people by, and the caller's own places.
        /// Nil from a server before D-227 and from a cache it wrote (decoding leaves it out).
        var places: Places? = nil
    }

    /// D-227: `groupRoles`' `places` (each a list of 32-hex places in the group). Android's twin is `Places` in Roles.kt.
    struct Places: Equatable, Codable {
        var owner: [String]
        var admins: [String]
        var you: [String]
    }

    enum Role: Equatable { case owner, admin, member, unjoined }

    /// What a person's sheet offers the viewer; one flag per row.
    struct Offer: Equatable {
        var remove: Bool
        var ask: Bool
        var askSelf: Bool
        var makeAdmin: Bool
        var removeAdmin: Bool
        var makeOwner: Bool
    }

    /// `ownerDecides`: a request naming an admin, seen by another admin: only
    /// the owner decides it, so the card has no buttons.
    enum RequestCard: Equatable { case decide, ownerDecides, asker, person }

    /// A person's role from the cloud's list; someone without an account
    /// (no "you") is `unjoined`, and so is anyone while the list is unknown.
    static func role(of you: String?, in roles: Roles) -> Role {
        guard let you else { return .unjoined }
        if roles.owner == you { return .owner }
        if roles.admins.contains(you) { return .admin }
        return .member
    }

    /// D-227 (Design 5): a row's role by its place when the cloud said who holds which place; otherwise the
    /// account rule above (an old server, or an old cache). A row in a role list is joined, whatever its claim.
    /// D-227 fix wave (I1): an owner the cloud cannot place yet (its create not up, `places.owner` empty) reads by the
    /// account rule, as 0.4.0 shows it; the admins stay by place.
    static func role(of you: String?, place: String, in roles: Roles) -> Role {
        guard let places = roles.places else { return role(of: you, in: roles) }
        if places.owner.isEmpty ? (you != nil && you == roles.owner) : places.owner.contains(place) { return .owner }
        if places.admins.contains(place) { return .admin }
        return you == nil ? .unjoined : .member
    }

    static func manages(_ viewer: Role) -> Bool { viewer == .owner || viewer == .admin }

    static func offer(viewer: Role, target: Role, isSelf: Bool) -> Offer {
        let remove = manages(viewer) && target != .owner && (target != .admin || viewer == .owner) && !isSelf
        return Offer(
            remove: remove,
            ask: !isSelf && !remove && target != .owner,
            askSelf: isSelf && viewer != .owner,
            makeAdmin: manages(viewer) && target == .member && !isSelf,
            removeAdmin: viewer == .owner && target == .admin,
            makeOwner: viewer == .owner && (target == .member || target == .admin) && !isSelf
        )
    }

    /// A role change the cloud confirmed (`setAdmin`, `makeOwner`).
    enum Change: Equatable { case admin(on: Bool), owner }

    /// D-227 fix wave (F4): the roles after a change the cloud confirmed, by place, so an open sheet reads the new role
    /// at once; the refresh that follows brings the rest. The old owner becomes an admin. Without places nothing
    /// changes here (the account rule waits for the refresh).
    static func confirmed(_ roles: Roles, place: String, change: Change) -> Roles {
        guard var places = roles.places else { return roles }
        switch change {
        case .admin(let on):
            places.admins = Array(Set(places.admins.filter { $0 != place } + (on ? [place] : []))).sorted()
        case .owner:
            places.admins = Array(Set(places.admins + places.owner).subtracting([place])).sorted()
            places.owner = [place]
        }
        var out = roles
        out.places = places
        return out
    }

    /// Only the owner deletes a group (an admin never).
    static func canDelete(_ viewer: Role) -> Bool { viewer == .owner }

    /// The card an open removal request shows this viewer, or nil. The owner
    /// and the admins decide, unless the request is about them. `person` is
    /// the role of the person named.
    static func requestCard(viewer: Role, person: Role, viewerIsAsker: Bool, viewerIsPerson: Bool) -> RequestCard? {
        if viewerIsPerson { return .person }
        // A request naming an admin is approved only by the owner.
        if manages(viewer) { return person == .admin && viewer != .owner ? .ownerDecides : .decide }
        if viewerIsAsker { return .asker }
        return nil
    }

    /// A name as the Functions take it: trimmed, at most 80 UTF-16 units (the
    /// Functions count them that way), whole characters only: one that would
    /// cross the limit is dropped, never cut.
    static func wireName(_ name: String) -> String {
        var trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        while trimmed.utf16.count > 80 { trimmed.removeLast() }
        return trimmed
    }
}

extension RoleRules.Places {
    /// D-227 fix wave (M-g): a cached entry's missing list reads as none (Android's `Places` defaults each).
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        owner = try c.decodeIfPresent([String].self, forKey: .owner) ?? []
        admins = try c.decodeIfPresent([String].self, forKey: .admins) ?? []
        you = try c.decodeIfPresent([String].self, forKey: .you) ?? []
    }
}

extension RoleRules.Roles {
    /// D-227: a cached list whose `places` cannot be read keeps its owner and admins, without places (the account rule
    /// then applies), as Android's `GroupRoles.decode` does. A cache from 0.4.0 (no `places`) reads as before.
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        owner = try c.decodeIfPresent(String.self, forKey: .owner)
        // D-227 fix wave (M-g): a missing list reads as none, as Android's decode reads it.
        admins = try c.decodeIfPresent([String].self, forKey: .admins) ?? []
        places = try? c.decodeIfPresent(RoleRules.Places.self, forKey: .places)
    }
}

// MARK: - The words (Android's twin: RolesCopy in Roles.kt)

enum RolesCopy {
    static let ownerPill = "Owner"
    static let adminPill = "Admin"

    static func removeFrom(group: String) -> String { "Remove from \(group)" }
    static func askToRemove(_ name: String) -> String { "Ask to remove \(name)" }
    static let askSelf = "Ask to be removed"
    static let roleSection = "Role"
    static let makeAdminRow = "Make admin"
    static func makeAdminSub(_ name: String) -> String { "\(name) can add and remove members, and name admins" }
    static let removeAdminRow = "Remove admin"
    static func removeAdminSub(_ name: String) -> String { "\(name) stays as a member" }
    static let makeOwnerRow = "Make owner"
    static let makeOwnerSub = "There is one owner; you become an admin"
    /// Someone who has not joined has no phone to see the request.
    static func askNote(name: String, joined: Bool = true) -> String {
        joined
            ? "Only the owner or an admin can remove someone. \(name), the owner and the admins will see your request."
            : "Only the owner or an admin can remove someone. The owner and the admins will see your request."
    }
    static let asked = "Request sent"
    static let cancel = "Cancel"
    /// A person's sheet: the row that renames them (D-186's rename, moved here).
    static let rename = "Rename"
    static let save = "Save"

    static func makeAdminTitle(_ name: String) -> String { "Make \(name) an admin?" }
    static func makeAdminBody(_ name: String) -> String {
        "\(name) will be able to add people, remove members, approve requests and name other admins. Only the owner can take it away."
    }
    static func makeAdminConfirm(_ name: String) -> String { "Make \(name) admin" }
    static func removeAdminTitle(_ name: String) -> String { "Remove \(name) as admin?" }
    static func removeAdminBody(_ name: String) -> String { "\(name) stays in the group as a member." }
    static let removeAdminConfirm = "Remove admin"
    static func makeOwnerTitle(_ name: String) -> String { "Make \(name) the owner?" }
    static func makeOwnerBody(_ name: String, group: String) -> String {
        "\(name) will own \(group): names admins, removes anyone, can delete the group. You become an admin. Only \(name) can undo this."
    }
    static func makeOwnerConfirm(_ name: String) -> String { "Make \(name) owner" }

    static func cardAsked(asker: String, name: String) -> String { "\(asker) asked to remove \(name)" }
    static let decline = "Decline"
    static let review = "Review"
    static func cardAsker(name: String) -> String { "You asked to remove \(name)" }
    /// D-210 last wave: a person who asked for their own removal. Their own
    /// card, and everyone else's.
    static let cardYouAskedToBeRemoved = "You asked to be removed"
    static func cardAskedToBeRemoved(name: String) -> String { "\(name) asked to be removed" }
    static let cardWait = "The owner or an admin will decide."
    /// D-210 fix: a request naming an admin, as another admin sees it.
    static let cardOwnerWill = "The owner will decide."
    static func cardPerson(asker: String) -> String { "\(asker) asked to remove you" }
    static let settleUp = "Settle up"

    static func reviewTitle(_ name: String, group: String) -> String { "Remove \(name) from \(group)?" }
    static func reviewBody(asker: String, name: String, joined: Bool, noHistory: Bool) -> String {
        "\(asker) asked. " + removalBody(name: name, joined: joined, noHistory: noHistory)
    }
    static let continueToRemove = "Continue to remove"
    static let declineRequest = "Decline request"

    static func removeTitle(_ name: String, group: String) -> String { "Remove \(name) from \(group)?" }
    static func removeBody(_ name: String) -> String {
        "\(name) will get a notification and keeps a read-only copy of the group's history."
    }
    /// The confirm's body: a joined person is notified and keeps a copy;
    /// someone who never joined is sent nothing, and with no history D-186's
    /// words (true only then) apply.
    static func removalBody(name: String, joined: Bool, noHistory: Bool) -> String {
        if joined { return removeBody(name) }
        return noHistory ? removeUnjoinedBody : unjoinedNothingSent(name)
    }
    static func unjoinedNothingSent(_ name: String) -> String { "\(name) has not joined, so nothing is sent to them." }
    static func youOwe(_ amount: String) -> String { "you owe \(amount)" }
    static func youAreOwed(_ amount: String) -> String { "you are owed \(amount)" }
    /// A request card's line under its title: "Sam owes ₹1,000.00 here."
    static func cardBalance(_ name: String, plan: [String: Int64]) -> String? {
        balanceHere(name, plan: plan).map { $0 + "." }
    }
    static let reviewChooseNext = "You'll choose next: settle first, or move it to others."
    /// The review sheet's banner, when the person has a balance.
    static func reviewBanner(_ name: String, plan: [String: Int64]) -> (title: String, sub: String)? {
        balanceHere(name, plan: plan).map { ($0, reviewChooseNext) }
    }
    static let removeConfirm = "Remove"
    static let removeUnjoinedBody = "They are in no expense or payment here. You can add them again later."

    static func balanceTitle(_ name: String) -> String { "Remove \(name)" }
    static func owes(_ name: String, _ amount: String) -> String { "\(name) owes \(amount)" }
    static func isOwed(_ name: String, _ amount: String) -> String { "\(name) is owed \(amount)" }
    /// A person's sheet, under Remove or Ask: "Asha owes ₹200.00 here"
    /// (currencies in code order, joined with the meta separator, one "here" at the end);
    /// nil when settled. A currency core cannot format is left out.
    static func balanceHere(_ name: String, plan: [String: Int64]) -> String? {
        let parts = plan.keys.sorted().compactMap { code -> String? in
            guard let net = plan[code], net != 0, let amount = RolesMoney.format(abs(net), code) else { return nil }
            return net < 0 ? owes(name, amount) : isOwed(name, amount)
        }
        return parts.isEmpty ? nil : parts.joined(separator: Separator.meta) + " here"
    }
    static func balanceLede(_ name: String) -> String { "Before \(name) goes, decide what happens to it." }
    static let settleFirst = "Settle first"
    static func settleFirstSub(_ name: String) -> String { "Record \(name)'s payments, then remove \(name)." }
    static let redistribute = "Redistribute"
    static func redistributeSub(_ name: String) -> String {
        "Move \(name)'s balance to people you choose. Their balances change; \(name)'s goes to zero."
    }
    static let next = "Next"
    static let recordPaymentsTitle = "Record these payments?"
    static let cannotWritePayment = "A payment's amount could not be written down, so nothing was recorded."
    static func paid(_ from: String, _ to: String, _ amount: String) -> String { "\(from) paid \(to) \(amount)" }
    static func recordAndRemove(_ name: String) -> String { "Record and remove \(name)" }

    static let redistributeTitle = "Redistribute"
    static func redistributeLede(_ name: String) -> String {
        "\(name)'s balance goes to the people you tick. Their new balances:"
    }
    static let editAmounts = "Edit amounts"
    static let splitEqually = "Split equally"
    static let addsUp = "Adds up"
    /// The tally's right side: what is assigned of what there is.
    static func ofTotal(_ assigned: String, _ total: String) -> String { "\(assigned) of \(total)" }
    /// A row's balance on the Redistribute screen: "owes ₹200.00", "owed ₹700.00", "settled".
    static func rowBalance(_ minor: Int64, _ amount: String) -> String {
        minor < 0 ? "owes \(amount)" : (minor > 0 ? "owed \(amount)" : "settled")
    }
    static func takes(_ amount: String) -> String { "takes \(amount)" }
    static func rowNow(_ balance: String) -> String { "\(balance) now" }
    static func left(_ amount: String) -> String { "\(amount) left to assign" }
    static func tooMuch(_ amount: String) -> String { "\(amount) too much" }
    static let typeAmounts = "Type an amount for each ticked person"
    static let tickOne = "Tick at least one person"
    static func removeButton(_ name: String) -> String { "Remove \(name)" }
    static func recordedFine(group: String) -> String { "Recorded in \(group)'s history for everyone." }

    /// The history line for a redistribution: `A`, `A and B`, `A, B and C`.
    static func moved(from: String, amount: String, to names: [String]) -> String {
        let list: String
        switch names.count {
        // Nobody named (a record this phone cannot name anyone in): no
        // dangling "moved to". Android's twin says the same.
        case 0: return "\(from)'s \(amount) moved"
        case 1: list = names[0]
        default: list = names.dropLast().joined(separator: ", ") + " and " + names[names.count - 1]
        }
        return "\(from)'s \(amount) moved to \(list)"
    }

    static let groupSettingsTitle = "Group settings"
    static let settingsName = "Name"
    static let settingsPicture = "Picture"
    static let settingsSettlesIn = "Settles in"
    static let deleteGroup = "Delete group"
    static let deleteGroupSub = "Only you, the owner, can do this"
    static func deleteCloudTitle(_ group: String) -> String { "Delete \(group) for everyone?" }
    static let deleteCloudBody =
        "It's erased from SKIPTI's cloud and nobody can add to it again. Everyone keeps a read-only copy on their phone."
    static let stillOpen = "Still open"
    static let deleteForEveryone = "Delete for everyone"
    static func deleteLocalTitle(_ group: String) -> String { "Delete \(group)?" }
    static let deleteLocalBody = "It's removed from this phone."
    static let deleteLocalConfirm = "Delete"

    static func removedBanner(by: String, date: String) -> String { "\(by) removed you on \(date)" }
    static func removedBannerBody(group: String) -> String {
        "This is your copy of \(group)'s history. Nothing new reaches it, and nothing can be added."
    }
    static func deletedBanner(by: String, date: String) -> String { "\(by) deleted this group on \(date)" }
    static let deletedBannerBody = "This is your copy. Nothing new can be added."
    /// D-229 decision 45: the one line of a group where this account lost its place.
    static let placeLostBanner =
        "You can't add to this group from this phone: someone else holds your place in it. Ask the group's owner to check People."
    static let exportThisGroup = "Export this group"
    static let forgetRow = "Remove from this phone"
    static func forgetTitle(_ group: String) -> String { "Remove \(group) from this phone?" }
    static let forgetBody = "Its history is erased from this phone only. Export it first if you want to keep it."
    static let forgetConfirm = "Remove from this phone"

    // What a Function says in a notice and a push (written by the cloud; here
    // so the words stay with their twins).
    static func noticeRequest(asker: String, name: String) -> String { "\(asker) asked to remove \(name)" }
    static func noticeRequestPerson(asker: String) -> String { "\(asker) asked to remove you" }
    static func noticeDeclined(by: String, name: String, group: String) -> String { "\(by) kept \(name) in \(group)" }
    static func noticeDeclinedPerson(by: String, group: String) -> String { "\(by) kept you in \(group)" }
    static func noticeRemoved(by: String, group: String) -> String { "\(by) removed you from \(group)" }
    static func noticeAdmin(by: String, group: String) -> String { "\(by) made you an admin of \(group)" }
    static func noticeOwner(by: String, group: String) -> String { "\(by) made you the owner of \(group)" }
    static func noticeDeleted(by: String, group: String) -> String { "\(by) deleted \(group)" }

    // Failures of the role and removal calls.
    static let notAllowed = "Only the owner or an admin can do that."
    static let changed = "That changed on another phone. Try again."
    static let gone = "This group no longer exists."
    /// NOT_FOUND from a call about a person: the group is there, the person is not.
    static let personGone = "That person is not in this group any more."

    /// requestRemoval says ALREADY_EXISTS when that person was already asked
    /// about: the screens show the same "Request sent" as for a new request.
    static func alreadyAsked(_ error: Error) -> Bool {
        guard case .refused(let status)? = error as? CloudFunctions.Failure else { return false }
        return status == "ALREADY_EXISTS"
    }

    /// A refusal as words; anything else is the offline line.
    /// `person`: the call was about one person (requestRemoval, removeMember,
    /// declineRemoval, setAdmin, makeOwner), so NOT_FOUND means that person.
    static func failure(_ error: Error, person: Bool = false) -> String {
        // D-254: a refusal for approval says so (never "not allowed" or the offline line).
        if let approval = ApprovalCopy.failure(error) { return approval }
        guard case .refused(let status)? = error as? CloudFunctions.Failure else { return InviteCopy.offline }
        switch status {
        case "PERMISSION_DENIED": return notAllowed
        case "FAILED_PRECONDITION": return changed
        case "NOT_FOUND": return person ? personGone : gone
        default: return InviteCopy.offline
        }
    }
}

/// D-252 (decision 218): the words of a group that ended on this phone: one that left the account's listing and the
/// server no longer gives (`vanished`: a removal and a deletion read the same from outside), a deletion this account
/// made (on this phone or another of its phones), and a write refused in any read-only copy. ONE table: Android's
/// twin `GroupEndCopy` (Roles.kt) is checked against these lines, so each stays one literal on one line.
enum GroupEndCopy {
    /// The banner's one line for a vanished group (its second is `RolesCopy.deletedBannerBody`).
    static let noLongerShared = "This group is no longer shared with you."
    /// The banner's line for a group this account deleted, on every one of its phones.
    static func youDeleted(_ group: String) -> String { "You deleted \(group)." }
    /// D-256 (decision 276): the banner's line for a deletion known only from its tombstone (no deleter's name in it).
    static let wasDeleted = "This group was deleted."
    /// A write into a read-only copy, refused before anything is written (`LedgerStore.Placing.ReadOnlyCopy`).
    static let refusedVanished = "This group is no longer shared with you. Nothing new can be added."
    static let refusedYouDeleted = "You deleted this group. Nothing new can be added."
    static let refusedDeleted = "This group was deleted. Nothing new can be added."
    static let refusedRemoved = "You were removed from this group. Nothing new can be added."

    /// The refusal's words for a copy's mark (a lost place has its own, `RolesCopy.placeLostBanner`).
    static func refused(_ mark: LedgerStore.ReadOnly) -> String {
        switch mark.kind {
        case LedgerStore.ReadOnly.vanishedKind: return refusedVanished
        case "removed": return refusedRemoved
        case LedgerStore.ReadOnly.placeLostKind: return RolesCopy.placeLostBanner
        default: return mark.byYou == true ? refusedYouDeleted : refusedDeleted
        }
    }
}

/// D-227 (Design 6): when the groups listing asks the cloud for a group's roles. Only for a confirmed snapshot (no
/// pending writes: a group this phone just made is not on the server yet), never twice at once for one group, and the
/// signature (owner and admins in the group's document) is kept only after a refresh that worked, so a not-found or
/// offline answer is asked again on the next snapshot. A pure value; Android's twin is `RoleAsking` in Roles.kt.
/// Decision 35: an answer for a group no longer in flight (after `clear` or `keep(only:)`) changes nothing, and a
/// confirmed signature seen while a group is in flight is held, so `finished` hands it back to be asked once more.
struct RoleAsking {
    /// Each group's signature as of its last successful refresh.
    private var kept: [String: String] = [:]
    /// The groups whose refresh has not answered yet, each with the signature it was asked for.
    private var inFlight: [String: String] = [:]
    /// The latest confirmed signature seen while the group was in flight.
    private var pending: [String: String] = [:]
    /// Decision 38: a group's timed retries of one signature: how many were made, and the token of the one that is
    /// still the group's.
    private struct Timed {
        let signature: String
        let made: Int
        let token: Int
    }
    private var retries: [String: Timed] = [:]
    private var nextToken = 0
    /// D-227 follow-up B: each group's latest confirmed signature.
    private var latest: [String: String] = [:]
    /// D-227 follow-up A: the failed answer that counted (its group was in flight for that signature) and asked
    /// nothing next; only it may set a timer (`failed`).
    private var failedAnswers: [String: String] = [:]

    /// True when the cloud should be asked now; the group is then in flight until `finished`.
    mutating func shouldAsk(_ group: String, signature: String, confirmed: Bool) -> Bool {
        guard confirmed else { return false }
        latest[group] = signature
        guard inFlight[group] == nil else {
            pending[group] = signature
            return false
        }
        guard GroupRoles.needsAsking(seen: kept[group], now: signature) else { return false }
        inFlight[group] = signature
        return true
    }

    /// The refresh answered: the signature is kept only when it worked; the group is no longer in flight either way.
    /// Returns the signature seen while asking when it differs from this one (ask again with it, through
    /// `shouldAsk`), else nil. A group not in flight for that signature (a late answer, after `clear` or after the
    /// group left) changes nothing and returns nil.
    @discardableResult
    mutating func finished(_ group: String, signature: String, ok: Bool) -> String? {
        guard inFlight[group] == signature else { return nil }
        inFlight[group] = nil
        if ok {
            kept[group] = signature
            // Decision 38: a successful ask cancels the group's timer.
            retries[group] = nil
        }
        let held = pending.removeValue(forKey: group)
        let next = held == signature ? nil : held
        // Follow-up A: only this answer may set a timer, and only when nothing is asked next.
        if !ok, next == nil { failedAnswers[group] = signature }
        return next
    }

    /// D-227 follow-up A (decision 35(a)): the failed answer of `group` at `signature` arrived: the timer to set
    /// (`retryAfter`), or nil unless `finished` counted it (the group was in flight for that signature and nothing is
    /// asked next). A late answer, after `clear` or after the group left, sets none; one answer sets one timer at most.
    /// Android's twin: `RoleAsking.failed`.
    mutating func failed(_ group: String, signature: String) -> RetryTimer? {
        guard failedAnswers[group] == signature else { return nil }
        failedAnswers[group] = nil
        return retryAfter(group, signature: signature)
    }

    /// D-227 (Design 8): a refresh counts only when the call worked and the cloud placed the caller (`places.you`
    /// not empty), or said nothing of places (a server before D-227). An empty `you` (the group's first records are
    /// not up yet) is asked again.
    /// `answer` is what the refresh kept, nil when it failed (`GroupRoles.refreshed`). Android's twin: `RoleAsking.ok`.
    static func ok(_ answer: RoleRules.Roles?) -> Bool {
        guard let answer else { return false }
        guard let places = answer.places else { return true }
        return !places.you.isEmpty
    }

    // MARK: Decision 38: a failed ask is asked again on a timer (Android's twin: the same names in Roles.kt)

    /// A timed retry: ask again after `seconds`, if `due` still says so with `token`.
    struct RetryTimer: Equatable {
        let seconds: TimeInterval
        let token: Int
    }

    /// The seconds before a failed ask is asked again, by the number of timed retries already made for its
    /// signature: 15, 60, 300, then none (the next snapshot or the next start asks).
    static func retryDelay(_ attempt: Int) -> TimeInterval? {
        switch attempt {
        case 0: return 15
        case 1: return 60
        case 2: return 300
        default: return nil
        }
    }

    /// An ask of `group` at `signature` failed and nothing is asked next: the timer to set, or nil once this
    /// signature had its three. A timer set earlier for the group is no longer `due`. The listener reaches it only
    /// through `failed`.
    mutating func retryAfter(_ group: String, signature: String) -> RetryTimer? {
        let made = retries[group].flatMap { $0.signature == signature ? $0.made : nil } ?? 0
        guard let seconds = Self.retryDelay(made) else { return nil }
        nextToken += 1
        retries[group] = Timed(signature: signature, made: made + 1, token: nextToken)
        return RetryTimer(seconds: seconds, token: nextToken)
    }

    /// The timer with `token` fired: true when it is still the group's own (not cancelled by `clear()`,
    /// `keep(only:)`, a successful ask or a later timer) and its signature is still the group's latest confirmed one
    /// (follow-up B: a newer one is asked for its own sake). The caller then asks through `shouldAsk`.
    func due(_ group: String, token: Int) -> Bool {
        guard let timed = retries[group] else { return false }
        return timed.token == token && latest[group] == timed.signature
    }

    /// Forgets every group no longer listed (it left this account's list); one that comes back is asked again.
    mutating func keep(only ids: Set<String>) {
        kept = kept.filter { ids.contains($0.key) }
        inFlight = inFlight.filter { ids.contains($0.key) }
        pending = pending.filter { ids.contains($0.key) }
        retries = retries.filter { ids.contains($0.key) }
        latest = latest.filter { ids.contains($0.key) }
        failedAnswers = failedAnswers.filter { ids.contains($0.key) }
    }

    /// Listening stopped: every group is asked again when it starts.
    mutating func clear() {
        kept = [:]
        inFlight = [:]
        pending = [:]
        retries = [:]
        latest = [:]
        failedAnswers = [:]
    }
}

// MARK: - The cloud's roles, cached

/// Each group's owner and admins as the cloud last reported them (the
/// `groupRoles` callable), kept in the vault (`cloud/roles/{g}`) so a phone
/// offline still knows what it may offer.
@MainActor
final class GroupRoles: ObservableObject {
    static let shared = GroupRoles()

    /// Screens read it; only this class sets it.
    @Published private(set) var roles: [String: RoleRules.Roles] = [:]

    /// The test seam (reached with @testable): sets or clears one group's roles.
    func setForTest(_ groupID: String, _ value: RoleRules.Roles?) { roles[groupID] = value }

    /// The cloud is asked once per launch for every group (first sight,
    /// cached or not: the cache may be stale), and again whenever the
    /// group's owner or admins change in its document.
    nonisolated static func needsAsking(seen before: String?, now: String) -> Bool { before != now }

    nonisolated static func key(_ groupID: String) -> String { "cloud/roles/" + groupID }

    /// D-227 (Design 7): each group's cached `places.you`, for `ProfileShare.places`.
    func yourPlaces(_ groupIDs: [String]) -> [String: [String]] {
        var out: [String: [String]] = [:]
        for g in groupIDs { if let you = load(g)?.places?.you { out[g] = you } }
        return out
    }

    private var store: LedgerStore { .defaultStore() }

    /// What the vault last held for a group (and keeps in `roles`).
    @discardableResult
    func load(_ groupID: String) -> RoleRules.Roles? {
        if let known = roles[groupID] { return known }
        guard let data = try? store.kvGet(key: Self.key(groupID)),
              let decoded = try? JSONDecoder().decode(RoleRules.Roles.self, from: data) else { return nil }
        roles[groupID] = decoded
        return decoded
    }

    private func keep(_ groupID: String, _ value: RoleRules.Roles) throws {
        roles[groupID] = value
        try store.kvSet(key: Self.key(groupID), value: try LedgerStore.kvEncoder().encode(value))
    }

    /// A result as the cloud writes it, or nil.
    nonisolated static func parse(_ result: [String: Any]) -> RoleRules.Roles? {
        let owner = result["owner"] as? String
        if let owner, !CloudRecords.isHex32(owner) { return nil }
        let admins = (result["admins"] as? [Any]) ?? []
        let ids = admins.compactMap { $0 as? String }.filter(CloudRecords.isHex32)
        guard ids.count == admins.count else { return nil }
        // D-227: `places` (a server before D-227 sends none). Any entry that is not a place refuses the whole answer.
        var places: RoleRules.Places?
        if let raw = result["places"], !(raw is NSNull) {
            guard let map = raw as? [String: Any], let o = hexList(map["owner"]), let a = hexList(map["admins"]),
                  let y = hexList(map["you"]) else { return nil }
            places = RoleRules.Places(owner: o, admins: a, you: y)
        }
        return RoleRules.Roles(owner: owner, admins: ids, places: places)
    }

    /// A list of 32-hex ids as the cloud writes it (missing reads as none), or nil when any entry is not one.
    nonisolated private static func hexList(_ raw: Any?) -> [String]? {
        guard let raw, !(raw is NSNull) else { return [] }
        guard let list = raw as? [Any] else { return nil }
        let ids = list.compactMap { $0 as? String }.filter(CloudRecords.isHex32)
        return ids.count == list.count ? ids : nil
    }

    /// Asks the cloud who owns the group and who administers it. Offline or
    /// refused leaves what is cached, and says so (false): a notice waiting
    /// on this stays for the next time.
    @discardableResult
    func refresh(_ groupID: String) async -> Bool { await refreshed(groupID) != nil }

    /// `refresh`, with the answer it kept (nil when none was): the groups listener judges it with `RoleAsking.ok`.
    func refreshed(_ groupID: String) async -> RoleRules.Roles? {
        load(groupID)
        guard Account.shared.isSignedIn,
              let result = try? await CloudFunctions.call("groupRoles", ["groupId": groupID]),
              let fresh = Self.parse(result) else { return nil }
        do {
            try keep(groupID, fresh)
            return fresh
        } catch {
            return nil
        }
    }

    /// The `setAdmin` callable's data: the place (`member`, the group's id
    /// for the person) and the account's participant id (`you`); the
    /// Function resolves the place and checks they are one (D-210 final A2).
    nonisolated static func setAdminData(groupID: String, member: String, you: String, on: Bool, byName: String, groupName: String) -> [String: Any] {
        [
            "groupId": groupID, "member": member, "you": you, "on": on,
            "byName": RoleRules.wireName(byName), "groupName": RoleRules.wireName(groupName),
        ]
    }

    /// The `makeOwner` callable's data (as `setAdminData`, without `on`).
    nonisolated static func makeOwnerData(groupID: String, member: String, you: String, byName: String, groupName: String) -> [String: Any] {
        [
            "groupId": groupID, "member": member, "you": you,
            "byName": RoleRules.wireName(byName), "groupName": RoleRules.wireName(groupName),
        ]
    }

    /// Makes the person at `member` (account `you`) an admin, or takes it away (`on: false`).
    func setAdmin(groupID: String, member: String, you: String, on: Bool, byName: String, groupName: String) async throws {
        let result = try await CloudFunctions.call("setAdmin", Self.setAdminData(
            groupID: groupID, member: member, you: you, on: on, byName: byName, groupName: groupName))
        if var known = load(groupID) {
            if let admins = (result["admins"] as? [Any])?.compactMap({ $0 as? String }) {
                known.admins = admins.filter { $0 != known.owner }
            }
            // D-227 fix wave (F4): by place too, so the open sheet reads the new role before the refresh answers.
            try keep(groupID, RoleRules.confirmed(known, place: member, change: .admin(on: on)))
        }
        await refresh(groupID)
    }

    /// Hands the group to the person at `member` (account `you`); the old owner becomes an admin.
    func makeOwner(groupID: String, member: String, you: String, byName: String, groupName: String) async throws {
        _ = try await CloudFunctions.call("makeOwner", Self.makeOwnerData(
            groupID: groupID, member: member, you: you, byName: byName, groupName: groupName))
        if let known = load(groupID) {
            // D-227 fix wave (F4): the new owner at once, by place.
            try keep(groupID, RoleRules.confirmed(known, place: member, change: .owner))
        }
        await refresh(groupID)
    }

    /// The group is gone from this phone (or was deleted): its cached list too.
    func forget(_ groupID: String) {
        roles[groupID] = nil
    }
}

// MARK: - D-252: a group that left the account's listing

/// D-252 (decision 218): a group this phone holds that the account's listing no longer has, and no removal or
/// deletion notice explained: the phone asks the server once (a read of `groups/{g}`). Pure, so every case is
/// tested alone; `CloudSync.checkVanished` does the reading. Android's twin is `GroupGone` in Roles.kt.
enum GroupGone {
    /// What the one read said.
    enum Read: Equatable {
        /// The document is there and this account is in its `memberUids`: still shared (a listing that missed it).
        case exists
        /// D-252 fix wave (M2): the document is there and this account is in its `former` (the rules let a removed
        /// account read it), not in `memberUids`: removed, and the removal's notice is lost or still on its way.
        case former
        /// The document is there and this account is in neither list (no reading the rules allow; refused in kind).
        case notMember
        /// The rules refused it: the account is no longer in it, or it is gone (a deleted group refuses every read).
        case refused
        case notFound
        /// No answer from the server (offline, a timeout, auth trouble): it says nothing about the group.
        case networkError
    }

    enum Outcome: Equatable {
        /// Nothing changes (it is still there).
        case keep
        /// The local copy becomes read-only ("This group is no longer shared with you.").
        case readOnly
        /// Nothing changes now; asked again at the next listing change or launch.
        case retry
        /// D-256 (decision 276): its tombstone says it was deleted: a deleted copy ("You deleted {group}." when this
        /// account deleted it, else "This group was deleted.").
        case deleted
    }

    /// D-256 (decision 276): what `deletedGroups/{g}` said to this account. Read once, from the server, only when the
    /// group's own read was refused or not found (`asksTombstone`). `deleteGroup` writes it (whose delete it was, the
    /// accounts that held the group, the time; no name); the rules let only those accounts read it.
    struct Tombstone: Equatable, Sendable {
        /// This account deleted it (the tombstone's `byUid`).
        let byYou: Bool
        /// When (`deletedAt`); nil when the document has no time.
        let at: Date?
    }

    /// The tombstone as the Function writes it, for the account `uid`; nil for none (refused, not there, no answer:
    /// nil data) or one without its deleter.
    static func tombstone(_ data: [String: Any]?, uid: String) -> Tombstone? {
        guard let data, let by = data["byUid"] as? String, !by.isEmpty else { return nil }
        return Tombstone(byYou: !uid.isEmpty && by == uid, at: (data["deletedAt"] as? Timestamp)?.dateValue())
    }

    /// D-256: the tombstone is asked about only when the group's read says it is gone for this account (refused or
    /// not found); a document that is there was not deleted, and no answer asks nothing more.
    static func asksTombstone(_ read: Read) -> Bool {
        read == .refused || read == .notFound
    }

    /// The decision. D-256 (decision 276): a refused or missing group with a tombstone this account may read was
    /// deleted, in those words; without one (refused, not there, no answer) it is the neutral copy, as before.
    static func decide(_ read: Read, tombstone: Tombstone?) -> Outcome {
        switch read {
        case .refused, .notFound: return tombstone == nil ? .readOnly : .deleted
        case .former, .notMember: return .readOnly
        case .networkError: return .retry
        case .exists: return .keep
        }
    }

    /// The mark an outcome leaves, or nil when it leaves none: the vanished mark, or (D-256) a deleted copy from the
    /// tombstone: no deleter's name, "You deleted" when it was this account's, dated by the tombstone (else `now`).
    static func mark(for outcome: Outcome, tombstone: Tombstone?, now: Date) -> LedgerStore.ReadOnly? {
        switch outcome {
        case .readOnly: return mark(at: now)
        case .deleted:
            guard let tombstone else { return mark(at: now) }
            return LedgerStore.ReadOnly(kind: "deleted", by: "", at: tombstone.at ?? now, byYou: tombstone.byYou ? true : nil)
        case .keep, .retry: return nil
        }
    }

    /// D-256 (decision 276): `ensureGroup`'s create was refused and its read refused too (`.notMember`). With a
    /// tombstone this account may read, the group was deleted: the copy ends read-only, in the deletion's words, so its
    /// waiting records stay on the phone (never parked, never sent again) and an invite stops at once. Without one
    /// (someone else's group with this id, or the approval gate) nothing is marked, as before.
    static func createRefused(_ tombstone: Tombstone?, now: Date) -> LedgerStore.ReadOnly? {
        guard let tombstone else { return nil }
        return mark(for: .deleted, tombstone: tombstone, now: now)
    }

    /// The read as `CloudSync.groupRead` gives it (the D-210 removal read: permission-denied and not-found are
    /// `.refused`, nil is no answer), for the account `uid`. D-252 fix wave (M2): a document that is there says
    /// `exists` only when the account is a member; a removed account still reads it (`former`, firestore.rules), so a
    /// removal whose notice never came ends the copy too, in the neutral words (the remover's name is not in the
    /// document; the removal's notice, when it comes, says more).
    static func read(_ answer: Notices.GroupRead?, uid: String) -> Read {
        switch answer {
        case .found(let data)?:
            let members = (data["memberUids"] as? [Any])?.compactMap { $0 as? String } ?? []
            if !uid.isEmpty, members.contains(uid) { return .exists }
            return (data["former"] as? [String: Any])?[uid] != nil && !uid.isEmpty ? .former : .notMember
        case .missing?: return .notFound
        case .refused?: return .refused
        case nil: return .networkError
        }
    }

    /// The groups to ask about, sorted: held on this phone AND in the cloud from here (`sync/groups-up`: a group that
    /// never left this phone is not in any listing, and asking about it would end it wrongly), not listed now, not
    /// already a copy, no removal or deletion notice waiting for it (that notice says why), and not already asked
    /// this session (`asked`: a read that said `keep`, or one still in flight). The first listing of a session sweeps
    /// every such group (0.5.0's groups deleted on another phone of the account, decision 218 point 4).
    static func toCheck(held: Set<String>, groupsUp: Set<String>, listed: Set<String>, copies: Set<String>,
                        noticed: Set<String>, asked: Set<String>) -> [String] {
        held.intersection(groupsUp).subtracting(listed).subtracting(copies).subtracting(noticed).subtracting(asked).sorted()
    }

    /// Whether the vanished mark may be written over what the copy already says: only an unmarked group or a lost
    /// place (derived, never stored). A removal or a deletion already says more, and is never made vaguer.
    static func marks(over existing: LedgerStore.ReadOnly?) -> Bool {
        existing == nil || existing?.kind == LedgerStore.ReadOnly.placeLostKind
    }

    /// The mark itself.
    static func mark(at: Date) -> LedgerStore.ReadOnly {
        LedgerStore.ReadOnly(kind: LedgerStore.ReadOnly.vanishedKind, by: "", at: at)
    }

    /// A vanished group listed again (the account is in it once more: a false alarm, or a new invite) is shared
    /// again: its vanished mark lifts. Only that mark: a removal or a deletion lifts only through an accepted invite.
    static func lifts(_ mark: LedgerStore.ReadOnly?, listedConfirmed: Bool) -> Bool {
        listedConfirmed && mark?.kind == LedgerStore.ReadOnly.vanishedKind
    }

    /// D-252 fix wave (M3): whether a listing from the server can be passed by: the same groups, each as confirmed as
    /// in the listing the last check read (a confirmation can lift a vanished mark, so it is a change). Nil `last`:
    /// the session's first listing, or a check that must run again (a vault read that failed, a read with no answer).
    static func unchanged(_ listing: [String: Bool], last: [String: Bool]?) -> Bool {
        guard let last else { return false }
        return last == listing
    }

    /// What a check reads from the vault, off the main thread (D-252 fix wave, M3): the marks, the groups held here
    /// and the groups in the cloud from here. Nil when the vault would not give them all (nothing is decided on a guess).
    struct Seen {
        let marks: [String: LedgerStore.ReadOnly]
        let held: Set<String>
        let up: Set<String>
    }

    static func see(_ store: LedgerStore) -> Seen? {
        guard let marks = try? store.readOnlyChecked(), let groups = try? store.loadGroups(),
              let up = try? CloudRecords.groupsUp(store) else { return nil }
        return Seen(marks: marks, held: Set(groups.map(\.id)), up: up)
    }
}

// MARK: - Notices

/// What happened in this account's groups (users/{uid}/notices): a removal
/// request, a request decided, a removal, a deletion, a role. Written by the
/// Functions; the account reads them and dismisses them. Modelled on
/// `IncomingInvites`. The sync layer acts on `removed`, `deleted`, `role`
/// and `declined` (CloudSync.handle); `request` stays as the card until the
/// Function deletes it.
@MainActor
final class Notices: ObservableObject {
    static let shared = Notices()

    enum Kind: String {
        case request, declined, removed, deleted, role
        /// Only a removal or a deletion ends a group's syncing on this phone;
        /// a role or a decision changes nothing about what it receives.
        var endsSync: Bool { self == .removed || self == .deleted }
        /// D-210 final B3: a removal or a deletion stays in the cloud, so
        /// every phone of the account finds it; each phone keeps its own
        /// list of the ones it has handled (`handled`).
        var keptInCloud: Bool { self == .removed || self == .deleted }
    }

    struct Item: Equatable, Identifiable {
        let id: String
        let kind: Kind
        let groupId: String
        let groupName: String
        let byName: String
        /// D-252: the account that did it (a deletion's notice names its deleter): the same account as this phone's
        /// reads "You deleted". Absent on notices written before D-252 and on every other kind.
        var byUid: String? = nil
        var memberId: String? = nil
        var memberName: String? = nil
        var role: String? = nil
        var at: Date = Date(timeIntervalSince1970: 0)
        var removedAt: Date? = nil
        /// The server's own stamp of the removal, kept whole: a catch-up
        /// query bounded by a rounded date could miss the last record.
        var removedStamp: Timestamp? = nil
        /// The notice's own server stamp, kept whole (the bound when the
        /// removal's own is missing and the notice is not past it).
        var atStamp: Timestamp? = nil

        /// The person named asked for it themself: the asker's name is the
        /// person's (the notice carries names, not accounts).
        var askedItself: Bool {
            guard kind == .request, let name = memberName else { return false }
            let a = byName.trimmingCharacters(in: .whitespacesAndNewlines)
            return !a.isEmpty && a == name.trimmingCharacters(in: .whitespacesAndNewlines)
        }
    }

    // MARK: D-210 final B3 and B6: what this phone has handled, and a removal's bound

    nonisolated static let handledKey = "cloud/notices-handled"

    /// The removals and deletions this phone has acted on (ids, this vault's).
    /// Unreadable reads as none: handling one again is safe (the catch-up
    /// and the mark are idempotent).
    nonisolated static func handled(_ store: LedgerStore) -> Set<String> {
        guard let data = try? store.kvGet(key: handledKey), !data.isEmpty else { return [] }
        return Set(String(decoding: data, as: UTF8.self).split(separator: "\n").map(String.init))
    }

    nonisolated static func markHandled(_ store: LedgerStore, _ id: String) throws {
        var ids = handled(store)
        guard ids.insert(id).inserted else { return }
        try store.kvSet(key: handledKey, value: Data(ids.sorted().joined(separator: "\n").utf8))
    }

    /// The notices to act on: never a request (the card), never a removal
    /// or a deletion this phone has already handled.
    nonisolated static func toHandle(_ items: [Item], handled: Set<String>) -> [Item] {
        items.filter { $0.kind != .request && !($0.kind.keptInCloud && handled.contains($0.id)) }
    }

    /// How the group reads from the server for a removal: there, missing, or refused.
    enum GroupRead {
        case found([String: Any])
        case missing
        case refused
    }

    /// What a failed read of the group means for a removal (D-210 M1). Only
    /// permission-denied and not-found are the server's own answer, "not for
    /// you" or "not there": `.refused`, and the removal goes on as `.gone`.
    /// Anything else (unavailable, deadline, cancelled, aborted, internal,
    /// resource-exhausted, unauthenticated, ...) says nothing about the
    /// group: nil, and the notice waits unhandled for another try.
    nonisolated static func groupRead(forFirestoreCode code: FirestoreErrorCode.Code?) -> GroupRead? {
        switch code {
        case .permissionDenied?, .notFound?: return .refused
        default: return nil
        }
    }

    /// Where a removal's catch-up ends.
    enum RemovalBound: Equatable {
        /// Records up to this server time (never past `former[uid].at`: the rules refuse that).
        case upTo(Timestamp)
        /// The account is a member of the group again: the notice is old news.
        case memberAgain
        /// The group cannot be read (deleted, or refused): nothing to catch up.
        case gone
    }

    /// D-210 final B6. The group as the server has it decides: an account
    /// that is a member again (its `former` entry cleared by a new join) has
    /// nothing to catch up and stays writable; otherwise the records are read
    /// up to the removal's own stamp, or, when the notice has none, up to its
    /// `at` only if that is not past `former[uid].at` (the rules refuse a
    /// read past it), else up to `former[uid].at` itself. A group that is
    /// gone, or refuses, has nothing more to give: the copy is what is here.
    nonisolated static func removalBound(_ item: Item, uid: String, group: GroupRead) -> RemovalBound {
        guard case .found(let data) = group else { return .gone }
        let former = (data["former"] as? [String: Any])?[uid] as? [String: Any]
        let formerAt = former?["at"] as? Timestamp
        let members = (data["memberUids"] as? [Any])?.compactMap { $0 as? String } ?? []
        if former == nil, members.contains(uid) { return .memberAgain }
        if let own = item.removedStamp { return .upTo(own) }
        guard let formerAt else { return .gone }
        if let at = item.atStamp, at.compare(formerAt) != .orderedDescending { return .upTo(at) }
        return .upTo(formerAt)
    }

    /// D-252: the mark a deletion's notice leaves, or nil when the copy already says it. A copy already marked deleted
    /// keeps its mark (the deleting phone's own, or an earlier notice's: a second phone of the deleting account handles
    /// the same notice once, and the deleting phone ignores its own); a vanished, removed, lost-place or unmarked group
    /// becomes a deleted copy. The notice's `byUid` against this account's uid says whose delete it was: "You deleted".
    /// D-256 (decision 276): a deleted mark from a tombstone that names nobody (not this account's) is told the
    /// deleter's name by the notice when it comes.
    nonisolated static func deletedMark(_ item: Item, uid: String?, existing: LedgerStore.ReadOnly?) -> LedgerStore.ReadOnly? {
        if let existing, existing.kind == "deleted", !(existing.by.isEmpty && existing.byYou != true) { return nil }
        let yours = uid.map { !$0.isEmpty && item.byUid == $0 } ?? false
        return LedgerStore.ReadOnly(kind: "deleted", by: item.byName, at: item.at, byYou: yours ? true : nil)
    }

    @Published private(set) var items: [Item] = []
    private var listener: ListenerRegistration?
    private var uid: String?

    nonisolated static func isDocID(_ id: String) -> Bool {
        id.range(of: "^[A-Za-z0-9_-]{1,200}$", options: .regularExpression) != nil
    }

    /// A document the way the Functions write it, or nil: a kind it does not
    /// know, an id that is not an id, a name that is not text, all refused.
    nonisolated static func item(id: String, data: [String: Any]) -> Item? {
        func date(_ any: Any?) -> Date? { (any as? Timestamp)?.dateValue() ?? (any as? Date) }
        guard isDocID(id),
              let kind = (data["kind"] as? String).flatMap(Kind.init(rawValue:)),
              let groupId = data["groupId"] as? String, CloudRecords.isHex32(groupId),
              let groupName = data["groupName"] as? String,
              let byName = data["byName"] as? String else { return nil }
        var memberId: String?
        if let raw = data["memberId"] {
            guard let value = raw as? String, CloudRecords.isHex32(value) else { return nil }
            memberId = value
        }
        var memberName: String?
        if let raw = data["memberName"] {
            guard let value = raw as? String else { return nil }
            memberName = value
        }
        var byUid: String?
        if let raw = data["byUid"] {
            guard let value = raw as? String, !value.isEmpty else { return nil }
            byUid = value
        }
        var role: String?
        if let raw = data["role"] {
            guard let value = raw as? String, value == "admin" || value == "owner" else { return nil }
            role = value
        }
        return Item(
            id: id, kind: kind, groupId: groupId, groupName: groupName, byName: byName, byUid: byUid,
            memberId: memberId, memberName: memberName, role: role,
            at: date(data["at"]) ?? Date(timeIntervalSince1970: 0),
            removedAt: date(data["removedAt"]),
            removedStamp: data["removedAt"] as? Timestamp,
            atStamp: data["at"] as? Timestamp
        )
    }

    /// The requests open in a group, newest first.
    func requests(in groupID: String) -> [Item] {
        items.filter { $0.kind == .request && $0.groupId == groupID }.sorted { $0.at > $1.at }
    }

    func listen(uid: String) {
        guard listener == nil else { return }
        self.uid = uid
        listener = Cloud.db.collection("users").document(uid).collection("notices")
            .addSnapshotListener { snapshot, _ in
                guard let docs = snapshot?.documents else { return }
                let items = docs.compactMap { Notices.item(id: $0.documentID, data: $0.data()) }
                MainActor.assumeIsolated {
                    Notices.shared.items = items
                    CloudSync.shared.handle(items)
                }
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
        Cloud.db.collection("users").document(uid).collection("notices").document(item.id).delete()
    }
}
