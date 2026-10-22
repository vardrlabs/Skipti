import FirebaseFirestore
import Foundation
import Observation

/// D-254 (decisions 228, 229): the approval gate, on the phone. Anyone can sign in; until the founder approves the
/// account, the cloud does nothing for it, and the ledger on this phone works exactly as it does with no account
/// (GUARANTEES #5: the app itself is never gated). The server is the control (the rules refuse every client write of an
/// unapproved account, and every Cloud Function but the ones that let a person leave refuses its calls); this phone's
/// gate is the courtesy: no write that would fail, and honest words. Android's twin is Approval.kt.
///
/// What the phone knows of its own account: `approvals/{uid}.state`, read through one listener while signed in and in
/// front, and through `requestApproval`'s answer. The last state heard is kept in the vault as a LOCAL-ONLY value
/// (`account/approval`, never a setting record that syncs), so a launch offline shows it without a flash; it is cleared
/// by every sign-out (sign-out everywhere, a deletion and an erase all sign out through `Account.signOut`).

/// The account's approval as this phone knows it. `unknown`: nothing heard yet for this account (a fresh sign-in, or an
/// install from before the gate). The cloud runs only on `approved`: an unknown state is closed, and the screens show
/// it as they always have (the first answer comes within a second of a launch online).
enum ApprovalState: String, Equatable, CaseIterable {
    case unknown, pending, approved, denied

    /// The server's word: `approvals/{uid}.state`, requestApproval's `{ state }`, or a refusal's `details.approval`.
    /// No word (no document) and `none` (a document holding only a deletion marker) are pending, as the server's own
    /// gate reads them; anything else unknown is pending too (closed, never approved).
    static func server(_ word: String?) -> ApprovalState {
        switch word {
        case "approved": return .approved
        case "denied": return .denied
        default: return .pending
        }
    }
}

/// D-254: every word the gate shows. One table: Android's twin `ApprovalCopy` (Approval.kt) is checked against these
/// lines by its parity test, so each stays one literal on one line. The founder approved them (decision 233, the
/// mock-up); the two `call` lines are the server's own refusal words (the design's section 5).
enum ApprovalCopy {
    /// You, the Account row's summary while waiting, and the invite page's title.
    static let accountWaiting = "Waiting for approval"
    /// You, the Account row's summary when denied, and the invite page's title then.
    static let accountDenied = "Not approved"
    /// The Sync card's value while waiting.
    static let syncWaitingValue = "Waiting"
    static let syncWaitingBody = "SKIPTI is in early testing, so new accounts are approved by hand. Your ledger on this phone works as usual. Sync and shared groups start once your account is approved."
    /// The Sync card's value when denied.
    static let syncDeniedValue = "Off"
    static let syncDeniedBody = "This account was not approved for sync. Your ledger on this phone works as usual and stays on this phone."
    /// Opening an invite while waiting; `{group}` is the group's name, or `thisGroup` when it is not known.
    static let joinWaitingBody = "You can join {group} once your account is approved. The link works for 7 days, so open it again then."
    static let thisGroup = "this group"
    static let joinDeniedBody = "This account was not approved, so it cannot join groups."
    /// In place of a group's invite rows, Find friends in contacts and Add by @username.
    static let inviteWaiting = "Inviting people starts once your account is approved."
    static let inviteDenied = "This account was not approved, so it cannot invite people."
    /// The toast, once, when approval arrives after the phone showed it waiting (or denied).
    static let approvedToast = "Your account is approved. Syncing now."
    /// A call refused for approval (on this phone, or by the server's `details.approval`).
    static let callWaiting = "This account is waiting for approval."
    static let callDenied = "This account was not approved."
    static let done = "Done"

    static func joinWaiting(_ group: String?) -> String {
        let name = group?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return joinWaitingBody.replacingOccurrences(of: "{group}", with: name.isEmpty ? thisGroup : name)
    }

    /// The Account row's summary for `state`, or nil: as before the gate.
    static func accountSummary(_ state: ApprovalState) -> String? {
        switch state {
        case .pending: return accountWaiting
        case .denied: return accountDenied
        case .unknown, .approved: return nil
        }
    }

    /// The one line in place of inviting, or nil: inviting as before.
    static func invite(_ state: ApprovalState) -> String? {
        switch state {
        case .pending: return inviteWaiting
        case .denied: return inviteDenied
        case .unknown, .approved: return nil
        }
    }

    /// The invite page's words while the account cannot join (title and body), or nil: open the invite.
    static func join(_ state: ApprovalState, group: String?) -> (title: String, body: String)? {
        switch state {
        case .pending: return (accountWaiting, joinWaiting(group))
        case .denied: return (accountDenied, joinDeniedBody)
        case .unknown, .approved: return nil
        }
    }

    /// A failed call's approval words (the waiting, or the not approved), or nil for any other failure. Never the
    /// "not a member" words: a refusal for approval is not about the group.
    static func failure(_ error: Error) -> String? {
        switch error as? CloudFunctions.Failure {
        case .awaitingApproval?: return callWaiting
        case .notApproved?: return callDenied
        default: return nil
        }
    }

    /// The state a failed call says the account is in, or nil when the failure is not about approval.
    static func state(of error: Error) -> ApprovalState? {
        switch error as? CloudFunctions.Failure {
        case .awaitingApproval?: return .pending
        case .notApproved?: return .denied
        default: return nil
        }
    }
}

/// D-254: what this phone does when it hears the account's state (`Approval.decide`).
enum ApprovalStep: Equatable {
    /// Nothing changed (or no answer).
    case nothing
    /// Keep the new state; the cloud was not running and still is not.
    case keep(ApprovalState)
    /// Approved: keep it, show the toast when the phone had shown the account waiting or denied, start the cloud.
    case approve(toast: Bool)
    /// Approval taken away: keep the new state and stop the cloud as sync off does (local data untouched).
    case withdraw(ApprovalState)
}

@Observable
@MainActor
final class Approval {
    static let shared = Approval()

    /// The signed-in account's state, for the screens. The gates read the vault's copy (`cached`).
    private(set) var state: ApprovalState = .unknown

    @ObservationIgnored private var loadedFor: String?
    @ObservationIgnored private var listener: ListenerRegistration?
    @ObservationIgnored private var listeningAs: String?
    /// D-254 fix wave (M5): the account and the state the last answered ask left it in; a front event asks again only
    /// when either differs (`frontAsks`). Cleared at sign-out.
    @ObservationIgnored private var answered: (uid: String, state: ApprovalState)?
    @ObservationIgnored private let asks = SingleFlight<ApprovalState>()
    /// D-254 fix wave (M4): SKIPTI is in front (the scene phase, and `CloudSync.appIsActive`). Approval heard while it is
    /// not is kept, and the next front event starts the cloud.
    @ObservationIgnored var inFront = false

    private init() {}

    // MARK: - The pure parts

    /// The calls an unapproved account may make (the server's `approvals.EXEMPT`): leaving never waits, and asking for
    /// approval is how it starts. Every other call is refused on this phone first (`CloudFunctions.call`).
    nonisolated static let exempt: Set<String> = [
        "signOutDevice", "signOutEverywhere", "requestDeletion", "cancelDeletion", "requestApproval", "adminApprovals",
    ]

    /// The cloud runs only for an approved account.
    nonisolated static func allowsCloud(_ state: ApprovalState) -> Bool { state == .approved }

    /// The cloud gate's third condition: the signed-in account, only when it is the vault's owner
    /// (`InstallSession.owned`) AND approved.
    nonisolated static func gate(uid: String?, owner: String?, approval: ApprovalState) -> String? {
        guard allowsCloud(approval) else { return nil }
        return InstallSession.owned(uid: uid, owner: owner)
    }

    /// `CloudFunctions.call`'s gate: a call the server would refuse for approval is refused here, with no network.
    /// D-254 fix wave (I2): `approval` is the state after `callRefusal` resolved an unknown one, so unknown here means no
    /// answer: the offline words, never the waiting ones (an approved tester must not read "waiting").
    nonisolated static func localRefusal(_ name: String, approval: ApprovalState) -> CloudFunctions.Failure? {
        guard !exempt.contains(name), !allowsCloud(approval) else { return nil }
        switch approval {
        case .denied: return .notApproved
        case .pending: return .awaitingApproval
        case .unknown, .approved: return .offline
        }
    }

    /// D-254 fix wave (I2): how long a gated call waits to resolve an unknown state.
    nonisolated static let resolveSeconds: TimeInterval = 5

    /// D-254 fix wave (I2): the call gate. Exempt or approved: through, nothing asked. Pending or denied: refused,
    /// nothing asked. Unknown (nothing heard yet, as on the first launch after the update): one bounded ask of the
    /// server (`requestApproval`, which answers with the state and caches it through `heard`), then refused only if
    /// that answer is pending or denied; no answer in time is the offline refusal.
    static func callRefusal(_ name: String, cached: ApprovalState, seconds: TimeInterval = resolveSeconds,
                            ask: @escaping @MainActor () async -> ApprovalState) async -> CloudFunctions.Failure? {
        guard !exempt.contains(name), !allowsCloud(cached) else { return nil }
        guard cached == .unknown else { return localRefusal(name, approval: cached) }
        let answer = await Bounded.value(seconds: seconds, otherwise: ApprovalState.unknown, ask)
        return localRefusal(name, approval: answer)
    }

    /// D-254 fix wave (M4): approval heard starts the cloud only while SKIPTI is in front.
    nonisolated static func startsCloudNow(_ step: ApprovalStep, inFront: Bool) -> Bool {
        guard case .approve = step else { return false }
        return inFront
    }

    /// D-254 fix wave (M5): a front event asks for approval once per app process while the state stays the same (not on
    /// every return to the front: each ask is a server write). `answered`: the last answered ask's account and the
    /// state it left. The sign-in and a refusal for approval ask anyway (`ask`).
    nonisolated static func frontAsks(answered: (uid: String, state: ApprovalState)?, uid: String,
                                      state: ApprovalState) -> Bool {
        guard !allowsCloud(state) else { return false }
        guard let answered else { return true }
        return answered.uid != uid || answered.state != state
    }

    /// What hearing `server` does, given what the phone last knew (`cached`). The toast only follows a state the
    /// phone showed (waiting or denied): an account heard as approved for the first time (an install from before the
    /// gate, or a second phone of an approved account) starts its cloud without one.
    nonisolated static func decide(cached: ApprovalState, server: ApprovalState) -> ApprovalStep {
        guard server != .unknown, server != cached else { return .nothing }
        if server == .approved { return .approve(toast: cached == .pending || cached == .denied) }
        if cached == .approved { return .withdraw(server) }
        return .keep(server)
    }

    // MARK: - The vault's copy (local only)

    /// `<uid>:<state>`: the state belongs to that account only.
    nonisolated static let cacheKey = "account/approval"

    nonisolated static func encode(uid: String, state: ApprovalState) -> String { uid + ":" + state.rawValue }

    /// The state the vault holds for `uid`; unknown for another account, nothing, or anything malformed.
    nonisolated static func decode(_ raw: String?, uid: String?) -> ApprovalState {
        guard let raw, let uid, let cut = raw.lastIndex(of: ":"), raw[..<cut] == uid,
              let state = ApprovalState(rawValue: String(raw[raw.index(after: cut)...])) else { return .unknown }
        return state
    }

    /// For the gates, which fail closed: a vault that cannot be read reads as unknown, so nothing syncs.
    nonisolated static func cached(_ store: LedgerStore, uid: String?) -> ApprovalState {
        guard uid != nil, let data = try? store.kvGet(key: cacheKey), !data.isEmpty else { return .unknown }
        return decode(String(data: data, encoding: .utf8), uid: uid)
    }

    nonisolated static func setCached(_ store: LedgerStore, uid: String, _ state: ApprovalState) {
        try? store.kvSet(key: cacheKey, value: Data(encode(uid: uid, state: state).utf8))
    }

    nonisolated static func clearCached(_ store: LedgerStore) {
        try? store.kvSet(key: cacheKey, value: Data())
    }

    // MARK: - The account's state, live

    /// The last state the vault holds for `uid`, for the first frame (Account's attach, a sign-in, a front event).
    func load(uid: String?, store: LedgerStore = .defaultStore()) {
        loadedFor = uid
        state = uid == nil ? .unknown : Self.cached(store, uid: uid)
    }

    /// Right after a sign-in kept its owner, before the cloud starts (`Account.signIn`): ask for approval (bounded:
    /// the sign-in never waits long on it) and listen for the decision.
    func signedIn(uid: String, store: LedgerStore = .defaultStore()) async {
        load(uid: uid, store: store)
        await Bounded.value(seconds: 10, otherwise: ()) { await Approval.shared.ask(uid: uid, store: store) }
        listen(uid: uid, store: store)
    }

    /// Every return to the front (`CloudSync.appIsActive(true)`): signed in to the vault's owner, the listener runs,
    /// and an account not approved asks again (its name may have changed; a lost request is made again).
    func front(uid: String?, store: LedgerStore = .defaultStore()) {
        guard LaunchWork.startsCloud(), FirebaseGate.isConfigured,
              let uid = InstallSession.owned(uid: uid, owner: Account.storedOwner(store)) else { return }
        if loadedFor != uid { load(uid: uid, store: store) }
        listen(uid: uid, store: store)
        guard Self.frontAsks(answered: answered, uid: uid, state: state) else { return }
        Task { await Approval.shared.ask(uid: uid, store: store) }
    }

    /// `requestApproval({ name?, platform })`: the name people see (the person's own name on this phone, if set) and
    /// this platform. Its answer is heard like the listener's, and returned (D-254 fix wave I2: the call gate resolves
    /// an unknown state with it); a failure (offline) changes nothing and returns unknown. One at a time: a second ask
    /// while one runs gets its answer.
    @discardableResult
    func ask(uid: String, store: LedgerStore = .defaultStore()) async -> ApprovalState {
        await asks.run(uid) {
            var data: [String: Any] = ["platform": "ios"]
            if let name = CloudProfile.cloudName((try? store.loadSelf())?.name) { data["name"] = name }
            guard let result = try? await CloudFunctions.call("requestApproval", data) else { return .unknown }
            let answer = ApprovalState.server(result["state"] as? String)
            let shared = Approval.shared
            shared.heard(answer, uid: uid, store: store)
            if Account.shared.uid == uid { shared.answered = (uid, answer) }
            return answer
        }
    }

    /// D-254 fix wave (I2): the live call gate (`callRefusal`) for `name`, with the vault's state for `uid`.
    func refusal(for name: String, uid: String, store: LedgerStore = .defaultStore()) async -> CloudFunctions.Failure? {
        await Self.callRefusal(name, cached: Self.cached(store, uid: uid)) { await Approval.shared.ask(uid: uid, store: store) }
    }

    /// One listener on `approvals/{uid}` while signed in and in front. A snapshot from the cache says nothing (the
    /// cache is memory only, so it starts empty); a refused or failed one changes nothing.
    func listen(uid: String, store: LedgerStore = .defaultStore()) {
        if listeningAs == uid, listener != nil { return }
        stopListening()
        listeningAs = uid
        listener = Cloud.db.collection("approvals").document(uid).addSnapshotListener { snapshot, error in
            guard error == nil, let snapshot, !snapshot.metadata.isFromCache else { return }
            let word = snapshot.exists ? snapshot.data()?["state"] as? String : nil
            MainActor.assumeIsolated { Approval.shared.heard(ApprovalState.server(word), uid: uid, store: store) }
        }
    }

    func stopListening() {
        listener?.remove()
        listener = nil
        listeningAs = nil
    }

    /// What the server said, acted on (`decide`) for the account still signed in as `uid`.
    func heard(_ server: ApprovalState, uid: String, store: LedgerStore = .defaultStore()) {
        guard Account.shared.uid == uid else { return }
        let step = Self.decide(cached: Self.cached(store, uid: uid), server: server)
        switch step {
        case .nothing:
            if loadedFor == uid, state != server, server != .unknown { state = server }
            return
        case .keep(let new):
            Self.setCached(store, uid: uid, new)
            loadedFor = uid
            state = new
        case .approve(let toast):
            Self.setCached(store, uid: uid, .approved)
            loadedFor = uid
            state = .approved
            if toast { DoneCenter.shared.post(DoneMoments.line(ApprovalCopy.approvedToast)) }
            // D-254 fix wave (M4): only in front; otherwise the next front event starts it (the vault says approved).
            if Self.startsCloudNow(step, inFront: inFront) { CloudSync.shared.appIsActive(true) }
        case .withdraw(let new):
            Self.setCached(store, uid: uid, new)
            loadedFor = uid
            state = new
            CloudSync.shared.approvalWithdrawn()
        }
        SyncStatus.shared.changed()
    }

    /// Every sign-out (`Account.signOut`, and a session ended elsewhere): the listener stops and the vault forgets.
    func signedOut(store: LedgerStore = .defaultStore()) {
        stopListening()
        answered = nil
        loadedFor = nil
        state = .unknown
        Self.clearCached(store)
    }
}

#if DEBUG
/// D-254 (Debug builds only, the emulator proofs' tool, never a user flow): every `--*-emulator-proof` signs in fake
/// accounts, which would now wait forever. This approves one through the LOCAL Functions emulator's
/// `adminApprovals({ action: 'decide', uid, state: 'approved' })`, as the emulators' admin account (`emulator-admin`,
/// `firebase/functions/.env.local`; the production admin id is never that) with an unsigned test token the emulator
/// accepts (the shape `firebase/tests/functions.test.mjs`'s `idToken` builds). Only under `Cloud.emulator`: anywhere
/// else it returns false without a call. Then the phone asks again, so it hears the answer at once.
@MainActor
enum EmulatorApproval {
    static let admin = "emulator-admin"

    /// An unsigned ID token for the emulators' admin (alg none, signed in with Google).
    static func adminToken(now: Date = Date()) -> String {
        let issued = Int(now.timeIntervalSince1970)
        func part(_ object: [String: Any]) -> String {
            let data = (try? JSONSerialization.data(withJSONObject: object, options: [.sortedKeys])) ?? Data()
            return data.base64EncodedString().replacingOccurrences(of: "+", with: "-")
                .replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "")
        }
        let claims: [String: Any] = [
            "iss": "https://securetoken.google.com/\(FirebaseConfig.projectID)", "aud": FirebaseConfig.projectID,
            "iat": issued, "exp": issued + 3600, "auth_time": issued, "sub": admin, "user_id": admin,
            "firebase": ["sign_in_provider": "google.com", "identities": [String: Any]()],
        ]
        return part(["alg": "none", "typ": "JWT"]) + "." + part(claims) + "."
    }

    @discardableResult
    static func approve(_ uid: String) async -> Bool {
        guard Cloud.emulator else { return false }
        var request = URLRequest(url: CloudFunctions.url("adminApprovals"))
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("Bearer \(adminToken())", forHTTPHeaderField: "Authorization")
        request.httpBody = try? JSONSerialization.data(withJSONObject: [
            "data": ["action": "decide", "uid": uid, "state": "approved"] as [String: Any],
        ])
        guard let (body, _) = try? await URLSession.shared.data(for: request),
              (try? CloudFunctions.decode(body)) != nil else {
            NSLog("EMULATOR-APPROVAL could not approve %@", uid)
            return false
        }
        await Approval.shared.ask(uid: uid)
        return true
    }
}
#endif
