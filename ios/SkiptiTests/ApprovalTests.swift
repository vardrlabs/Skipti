import FirebaseFirestore
import XCTest
@testable import Skipti

/// D-254 (decisions 228, 229): the approval gate on the phone. The decision from (what the phone knew, what the server
/// said), the words, the account hub's four states, the gates (an account not approved pushes, listens, registers and
/// calls nothing; the calls that let a person leave still go), `details.approval`'s mapping, the vault's local-only copy
/// cleared at sign-out, and the emulator helper kept to Debug and the emulators. Android's twin is ApprovalTest.kt,
/// whose parity test reads `ApprovalCopy` out of Approval.swift.
final class ApprovalTests: XCTestCase {
    private let uid = "uid-approval-test"
    private var dir: URL!

    override func setUpWithError() throws {
        dir = FileManager.default.temporaryDirectory.appendingPathComponent("approval-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: dir)
    }

    private func tempStore() -> LedgerStore {
        LedgerStore(path: dir.appendingPathComponent("vault-\(UUID().uuidString).db").path)
    }

    private func appSource(_ file: String) throws -> String {
        let url = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("Skipti/App/\(file)")
        return try String(contentsOf: url, encoding: .utf8)
    }

    private func appSources() throws -> [(name: String, text: String)] {
        let folder = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("Skipti/App")
        return try FileManager.default.contentsOfDirectory(atPath: folder.path).filter { $0.hasSuffix(".swift") }.sorted()
            .map { ($0, try String(contentsOf: folder.appendingPathComponent($0), encoding: .utf8)) }
    }

    /// The text after the first `from`, up to the first `to` after it.
    private func slice(_ text: String, from: String, to: String) throws -> String {
        let after = try XCTUnwrap(text.components(separatedBy: from).dropFirst().first, "anchor \(from)")
        return try XCTUnwrap(after.components(separatedBy: to).first, "anchor \(to)")
    }

    /// Each needle appears in `text`, in this order.
    private func inOrder(_ text: String, _ needles: [String], file: StaticString = #filePath, line: UInt = #line) {
        var from = text.startIndex
        for needle in needles {
            guard let found = text.range(of: needle, range: from..<text.endIndex) else {
                return XCTFail("missing, or out of order: \(needle)", file: file, line: line)
            }
            from = found.upperBound
        }
    }

    // MARK: - The decision

    /// Every (cached, server) pair, the table written out by hand (never read back out of `decide`): the toast only
    /// after the phone showed waiting or denied; approval taken away stops the cloud; no answer changes nothing.
    func testTheDecisionForEveryCachedAndServerPair() {
        let table: [(ApprovalState, ApprovalState, ApprovalStep)] = [
            (.unknown, .unknown, .nothing), (.unknown, .pending, .keep(.pending)),
            (.unknown, .approved, .approve(toast: false)), (.unknown, .denied, .keep(.denied)),
            (.pending, .unknown, .nothing), (.pending, .pending, .nothing),
            (.pending, .approved, .approve(toast: true)), (.pending, .denied, .keep(.denied)),
            (.approved, .unknown, .nothing), (.approved, .pending, .withdraw(.pending)),
            (.approved, .approved, .nothing), (.approved, .denied, .withdraw(.denied)),
            (.denied, .unknown, .nothing), (.denied, .pending, .keep(.pending)),
            (.denied, .approved, .approve(toast: true)), (.denied, .denied, .nothing),
        ]
        XCTAssertEqual(table.count, ApprovalState.allCases.count * ApprovalState.allCases.count, "every pair")
        for (cached, server, want) in table {
            XCTAssertEqual(Approval.decide(cached: cached, server: server), want, "\(cached) then \(server)")
        }
    }

    /// The server's words: the three states, and no document or `none` (a deletion marker alone) as pending, as the
    /// server's own gate reads them. Nothing unknown is ever approved.
    func testTheServersWordsReadAsTheServerReadsThem() {
        XCTAssertEqual(ApprovalState.server("approved"), .approved)
        XCTAssertEqual(ApprovalState.server("denied"), .denied)
        XCTAssertEqual(ApprovalState.server("pending"), .pending)
        XCTAssertEqual(ApprovalState.server("none"), .pending)
        XCTAssertEqual(ApprovalState.server(nil), .pending)
        XCTAssertEqual(ApprovalState.server("APPROVED"), .pending, "closed, never approved by accident")
    }

    // MARK: - The words

    /// The approved words (decision 233), letter for letter, and no em or en dash in any of them.
    func testTheCopyTable() {
        XCTAssertEqual(ApprovalCopy.accountWaiting, "Waiting for approval")
        XCTAssertEqual(ApprovalCopy.accountDenied, "Not approved")
        XCTAssertEqual(ApprovalCopy.syncWaitingValue, "Waiting")
        XCTAssertEqual(ApprovalCopy.syncWaitingBody, "SKIPTI is in early testing, so new accounts are approved by hand. Your ledger on this phone works as usual. Sync and shared groups start once your account is approved.")
        XCTAssertEqual(ApprovalCopy.syncDeniedValue, "Off")
        XCTAssertEqual(ApprovalCopy.syncDeniedBody, "This account was not approved for sync. Your ledger on this phone works as usual and stays on this phone.")
        XCTAssertEqual(ApprovalCopy.joinWaiting("Goa trip"), "You can join Goa trip once your account is approved. The link works for 7 days, so open it again then.")
        XCTAssertEqual(ApprovalCopy.joinWaiting(nil), "You can join this group once your account is approved. The link works for 7 days, so open it again then.")
        XCTAssertEqual(ApprovalCopy.joinWaiting("  "), ApprovalCopy.joinWaiting(nil), "a blank name is no name")
        XCTAssertEqual(ApprovalCopy.joinDeniedBody, "This account was not approved, so it cannot join groups.")
        XCTAssertEqual(ApprovalCopy.inviteWaiting, "Inviting people starts once your account is approved.")
        XCTAssertEqual(ApprovalCopy.inviteDenied, "This account was not approved, so it cannot invite people.")
        XCTAssertEqual(ApprovalCopy.approvedToast, "Your account is approved. Syncing now.")
        XCTAssertEqual(ApprovalCopy.callWaiting, "This account is waiting for approval.")
        XCTAssertEqual(ApprovalCopy.callDenied, "This account was not approved.")
        XCTAssertEqual(ApprovalCopy.done, "Done")
        let all = [ApprovalCopy.accountWaiting, ApprovalCopy.accountDenied, ApprovalCopy.syncWaitingValue,
                   ApprovalCopy.syncWaitingBody, ApprovalCopy.syncDeniedValue, ApprovalCopy.syncDeniedBody,
                   ApprovalCopy.joinWaitingBody, ApprovalCopy.thisGroup, ApprovalCopy.joinDeniedBody,
                   ApprovalCopy.inviteWaiting, ApprovalCopy.inviteDenied, ApprovalCopy.approvedToast,
                   ApprovalCopy.callWaiting, ApprovalCopy.callDenied, ApprovalCopy.done]
        for line in all {
            XCTAssertFalse(line.contains("\u{2014}") || line.contains("\u{2013}"), line)
        }
        // Where each state shows what: approved and unknown keep today's screens.
        XCTAssertEqual(ApprovalCopy.accountSummary(.pending), "Waiting for approval")
        XCTAssertEqual(ApprovalCopy.accountSummary(.denied), "Not approved")
        XCTAssertNil(ApprovalCopy.accountSummary(.approved))
        XCTAssertNil(ApprovalCopy.accountSummary(.unknown))
        XCTAssertEqual(ApprovalCopy.invite(.pending), ApprovalCopy.inviteWaiting)
        XCTAssertEqual(ApprovalCopy.invite(.denied), ApprovalCopy.inviteDenied)
        XCTAssertNil(ApprovalCopy.invite(.approved))
        XCTAssertNil(ApprovalCopy.invite(.unknown))
        XCTAssertEqual(ApprovalCopy.join(.pending, group: "Goa trip")?.title, "Waiting for approval")
        XCTAssertEqual(ApprovalCopy.join(.pending, group: "Goa trip")?.body, ApprovalCopy.joinWaiting("Goa trip"))
        XCTAssertEqual(ApprovalCopy.join(.denied, group: "Goa trip")?.title, "Not approved")
        XCTAssertEqual(ApprovalCopy.join(.denied, group: "Goa trip")?.body, ApprovalCopy.joinDeniedBody)
        XCTAssertNil(ApprovalCopy.join(.approved, group: nil))
        XCTAssertNil(ApprovalCopy.join(.unknown, group: nil))
    }

    /// Twin parity's input: every `ApprovalCopy` literal is one `static let name = "..."` on one line of Approval.swift,
    /// so Android's parity test can read the table out of this file.
    func testTheCopyTableIsOneLiteralALine() throws {
        let table = try slice(try appSource("Approval.swift"), from: "enum ApprovalCopy {", to: "\n    static func ")
        let lets = table.components(separatedBy: "\n").filter { $0.hasPrefix("    static let ") }
        XCTAssertEqual(lets.count, 15)
        for line in lets {
            XCTAssertNotNil(line.range(of: #"^    static let [a-zA-Z]+ = "[^"\\]*"$"#, options: .regularExpression), line)
        }
    }

    // MARK: - The account hub

    /// The four states: no account (whatever the approval says), active (approved, or not heard yet), waiting and not
    /// approved. Sign out and Delete account are there in every signed-in state.
    func testTheAccountHubHasFourStates() {
        for approval in ApprovalState.allCases {
            XCTAssertEqual(accountHubModel(signedIn: false, syncOn: true, approval: approval).state, .noAccount)
        }
        XCTAssertEqual(accountHubModel(signedIn: true, syncOn: true, approval: .approved).state, .active)
        XCTAssertEqual(accountHubModel(signedIn: true, syncOn: true, approval: .unknown).state, .active)
        XCTAssertEqual(accountHubModel(signedIn: true, syncOn: true, approval: .pending).state, .waiting)
        XCTAssertEqual(accountHubModel(signedIn: true, syncOn: true, approval: .denied).state, .notApproved)
        for approval in ApprovalState.allCases {
            let model = accountHubModel(signedIn: true, syncOn: false, approval: approval)
            XCTAssertTrue(model.showsAccountControls, "\(approval)")
            XCTAssertFalse(model.syncOn)
            let items = SettingsMap.items(.profileAccount, signedIn: model.showsAccountControls)
                + SettingsMap.items(.security, signedIn: model.showsAccountControls)
            XCTAssertTrue(items.contains(.signOut) && items.contains(.deleteAccount), "leaving never waits (\(approval))")
        }
        // The page draws from the model, never from `.active` alone (a waiting account keeps Sign out).
        let hub = try? appSource("AccountHubScreen.swift")
        XCTAssertFalse(hub?.contains("signedIn: model.state == .active") ?? true)
    }

    /// You's Account row: the waiting or not approved words stand in for "Google · 1 phone"; otherwise as today.
    func testTheAccountRowSaysWaitingOrNotApproved() {
        func parts(_ approval: ApprovalState, signedIn: Bool = true) -> [String] {
            YouRows.parts(.profileAccount, YouRowFacts(
                signedIn: signedIn, methodLabel: SignInMethod.google.rowLabel, phoneCount: 1, theme: .aurora, mode: .dark,
                lockTimeout: .oneMinute, lockAvailable: true, version: "0.5.1", approval: approval))
        }
        XCTAssertEqual(parts(.pending), ["Waiting for approval"])
        XCTAssertEqual(parts(.denied), ["Not approved"])
        XCTAssertEqual(parts(.approved), [SignInMethod.google.rowLabel, PhonesCopy.count(1)])
        XCTAssertEqual(parts(.unknown), parts(.approved), "not heard yet: as today, no flash")
        XCTAssertEqual(parts(.pending, signedIn: false), [YouCopy.noAccount])
    }

    /// The Sync card: Waiting or Off with its words and no switch while the account is not approved; the switch card
    /// otherwise.
    func testTheSyncCardHasNoSwitchWhileNotApproved() throws {
        let hub = try appSource("AccountHubScreen.swift")
        let card = try slice(hub, from: "    private var syncCard: some View {", to: "\n    }\n")
        XCTAssertTrue(card.contains("case .waiting:\n            approvalSyncCard(value: ApprovalCopy.syncWaitingValue, body: ApprovalCopy.syncWaitingBody"))
        XCTAssertTrue(card.contains("case .notApproved:\n            approvalSyncCard(value: ApprovalCopy.syncDeniedValue, body: ApprovalCopy.syncDeniedBody"))
        XCTAssertTrue(card.contains("case .noAccount, .active:\n            switchSyncCard"))
        let approvalCard = try slice(hub, from: "    private func approvalSyncCard(", to: "\n    }\n")
        XCTAssertFalse(approvalCard.contains("Button"), "no switch")
        XCTAssertFalse(approvalCard.contains("toggleSync"))
    }

    // MARK: - The gates

    /// The third condition: owned AND approved. Every other state is closed.
    func testTheCloudGateNeedsApprovalBesideOwnership() {
        XCTAssertEqual(Approval.gate(uid: uid, owner: uid, approval: .approved), uid)
        for state in [ApprovalState.unknown, .pending, .denied] {
            XCTAssertNil(Approval.gate(uid: uid, owner: uid, approval: state), "\(state)")
            XCTAssertNil(LaunchWork.signedInAccount(uid: uid, syncOn: true, owner: uid, approval: state, environment: [:]),
                         "push, listen and the profile share wait (\(state))")
        }
        XCTAssertNil(Approval.gate(uid: uid, owner: "uid-other", approval: .approved), "ownership still counts")
        XCTAssertNil(Approval.gate(uid: nil, owner: uid, approval: .approved))
        XCTAssertEqual(LaunchWork.signedInAccount(uid: uid, syncOn: true, owner: uid, approval: .approved, environment: [:]), uid,
                       "the same gate opens when approved (not vacuous)")
        XCTAssertNil(LaunchWork.signedInAccount(uid: uid, syncOn: true, owner: uid, approval: .approved), "never in a test host")
    }

    /// Every path that starts cloud work reads the approval: `ready` (push, listen, sync now), the registration and the
    /// push restart inside `registerPhone`, `registration` (the sign-in and username screens call it directly), the
    /// profile share's account and the settings funnel.
    func testEveryPathThatStartsCloudWorkReadsTheApproval() throws {
        let sync = try appSource("CloudSync.swift")
        let ready = try slice(sync, from: "    func ready(uid: String?) -> (uid: String, store: LedgerStore)? {", to: "\n    }\n")
        XCTAssertTrue(ready.contains("approval: Approval.cached(store, uid: uid)"))
        let register = try slice(sync, from: "    func registerPhone() {", to: "\n    }\n")
        inOrder(register, ["Approval.gate(uid: uid, owner: Account.storedOwner(store), approval: Approval.cached(store, uid: uid)) != nil",
                           "Push.shared.start()"])
        let registration = try slice(sync, from: "    func registration(uid: String) async -> CloudProfile.Outcome {", to: "\n    }\n")
        inOrder(registration, ["guard Approval.allowsCloud(approval) else {", "CloudProfile.register(uid: uid, store: store)"])
        let profile = try appSource("ProfileSync.swift")
        XCTAssertTrue(profile.contains("approval: Approval.cached(store, uid: Account.shared.uid))"))
        let settings = try appSource("SettingsSync.swift")
        XCTAssertTrue(settings.contains("guard writesRecord(uid: uid, owner: Account.storedOwner(store), approval: Approval.cached(store, uid: uid)),"))
        // The front event asks and listens; leaving it stops the listener.
        let active = try slice(sync, from: "    func appIsActive(_ active: Bool) {", to: "\n    }\n")
        inOrder(active, ["Approval.shared.front(uid: Account.shared.uid)", "registerPhone()", "push()", "listen()",
                         "stopListening()", "Approval.shared.stopListening()"])
    }

    /// A settings change while waiting is kept on this phone and writes no record (no record to push later as stale).
    @MainActor
    func testWhileWaitingASettingChangeWritesNoRecord() throws {
        let store = tempStore()
        try store.kvSet(key: Account.ownerKey, value: Data(uid.utf8))
        let defaults = try XCTUnwrap(UserDefaults(suiteName: "approval-\(UUID().uuidString)"))
        Approval.setCached(store, uid: uid, .pending)
        XCTAssertFalse(SettingsSync.write("appearance.theme", "norse", store: store, defaults: defaults, uid: uid))
        XCTAssertEqual(defaults.string(forKey: AppTheme.storageKey), "norse", "kept on the phone")
        XCTAssertEqual(try store.allRecords().count, 0, "no record while waiting")
        Approval.setCached(store, uid: uid, .approved)
        XCTAssertTrue(SettingsSync.write("appearance.theme", "vardr", store: store, defaults: defaults, uid: uid), "approved: written")
        XCTAssertEqual(try store.allRecords().count, 1)
    }

    /// `CloudFunctions.call`'s own gate: every call but the exempt ones is refused locally until approved, with the
    /// waiting words (pending) or the not approved ones (denied); the exempt ones always go. Fix wave (I2): unknown here
    /// is the state after the bounded ask found no answer, so it is the offline refusal, never the waiting words.
    func testTheCallGateRefusesLocallyAndLetsLeavingThrough() {
        let exempt = ["signOutDevice", "signOutEverywhere", "requestDeletion", "cancelDeletion", "requestApproval", "adminApprovals"]
        XCTAssertEqual(Approval.exempt, Set(exempt), "the server's approvals.EXEMPT")
        for state in ApprovalState.allCases {
            for name in exempt { XCTAssertNil(Approval.localRefusal(name, approval: state), "\(name) \(state)") }
        }
        for name in ["createInvite", "peekInvite", "acceptInvite", "findPeople", "setUsername", "setFindable", "inviteUser",
                     "inviteKnown", "adoptRemovals", "deleteGroup", "groupRoles", "shareProfile"] {
            XCTAssertEqual(Approval.localRefusal(name, approval: .pending), .awaitingApproval, name)
            XCTAssertEqual(Approval.localRefusal(name, approval: .unknown), .offline, name)
            XCTAssertEqual(Approval.localRefusal(name, approval: .denied), .notApproved, name)
            XCTAssertNil(Approval.localRefusal(name, approval: .approved), name)
        }
    }

    /// The gate is the call's first act once signed in: before the ID token and before any request leaves the phone.
    /// The invite paths that write the group to the cloud first (create, inviteUser, inviteKnown) refuse before that.
    func testTheCallGateComesBeforeAnyNetwork() throws {
        let invites = try appSource("Invites.swift")
        let call = try slice(invites, from: "    static func call(_ name: String, _ data: [String: Any]) async throws -> [String: Any] {",
                             to: "\n    }\n")
        inOrder(call, ["guard let user = FirebaseGate.auth?.currentUser else { throw Failure.signedOut }",
                       "if let refusal = await Approval.shared.refusal(for: name, uid: user.uid) {",
                       "throw refusal", "user.getIDToken()", "URLSession.shared.data(for: request)",
                       // A server refusal for approval (`none` included) asks for approval, so the request exists.
                       "catch let failure as Failure where failure == .awaitingApproval || failure == .notApproved {",
                       "Approval.shared.ask(uid: user.uid)", "throw failure"])
        let create = try slice(invites, from: "    static func create(store: LedgerStore, group: NamedGroupWire, member: MemberWire)", to: "\n    }\n")
        inOrder(create, ["Approval.shared.refusal(for: \"createInvite\"", "CloudSync.ensureGroup("])
        let people = try appSource("People.swift")
        let invite = try slice(people, from: "    static func invite(store: LedgerStore, group: NamedGroupWire, member: MemberWire, person: FoundPerson)", to: "\n    }\n")
        inOrder(invite, ["Approval.shared.refusal(for: \"inviteUser\"", "CloudSync.ensureGroup("])
        let known = try slice(people, from: "    static func inviteKnown(", to: "\n    }\n")
        inOrder(known, ["Approval.shared.refusal(for: \"inviteKnown\"", "CloudSync.ensureGroup("])
    }

    // MARK: - details.approval

    /// The server's refusal for approval (permission-denied with `details.approval`) is its own failure, and its words
    /// are the waiting or not approved ones, never "not a member" (the same status without the detail still is).
    func testDetailsApprovalMapsToTheApprovalWords() throws {
        func refusal(_ details: String?) -> Error? {
            let detail = details.map { #","details":{"approval":"\#($0)"}"# } ?? ""
            let body = Data(#"{"error":{"status":"PERMISSION_DENIED","message":"x"\#(detail)}}"#.utf8)
            do { _ = try CloudFunctions.decode(body); return nil } catch { return error }
        }
        XCTAssertEqual(refusal("pending") as? CloudFunctions.Failure, .awaitingApproval)
        XCTAssertEqual(refusal("none") as? CloudFunctions.Failure, .awaitingApproval)
        XCTAssertEqual(refusal("denied") as? CloudFunctions.Failure, .notApproved)
        XCTAssertEqual(refusal(nil) as? CloudFunctions.Failure, .refused("PERMISSION_DENIED"), "the detail decides")
        let waiting = try XCTUnwrap(refusal("pending"))
        let denied = try XCTUnwrap(refusal("denied"))
        XCTAssertEqual(InviteCopy.failure(waiting), ApprovalCopy.callWaiting)
        XCTAssertEqual(InviteCopy.failure(denied), ApprovalCopy.callDenied)
        XCTAssertNotEqual(InviteCopy.failure(waiting), InviteCopy.notMember)
        XCTAssertEqual(InviteCopy.failure(try XCTUnwrap(refusal(nil))), InviteCopy.notMember, "a real membership refusal")
        XCTAssertEqual(People.failure(waiting), ApprovalCopy.callWaiting)
        XCTAssertEqual(People.inviteFailure(denied), ApprovalCopy.callDenied)
        XCTAssertEqual(RolesCopy.failure(waiting, person: true), ApprovalCopy.callWaiting)
        XCTAssertEqual(UsernameSaveFailure.from(waiting), .message(ApprovalCopy.callWaiting))
        XCTAssertEqual(UsernameSaveFailure.from(denied), .message(ApprovalCopy.callDenied))
        XCTAssertFalse(InviteCopy.canRetry(waiting), "the same call says the same until approved")
        XCTAssertEqual(ApprovalCopy.state(of: waiting), .pending)
        XCTAssertEqual(ApprovalCopy.state(of: denied), .denied)
        XCTAssertNil(ApprovalCopy.state(of: CloudFunctions.Failure.offline))
    }

    /// Opening an invite while not approved makes no peek call; a refusal for approval later shows the same page.
    func testOpeningAnInviteWhileWaitingMakesNoCall() throws {
        let invites = try appSource("Invites.swift")
        let open = try slice(invites, from: "    private func open() async {", to: "\n    }\n")
        inOrder(open, ["if let blocked = ApprovalCopy.join(Approval.shared.state, group: nil) {",
                       "phase = .waiting(title: blocked.title, body: blocked.body)", "return", "Invites.peek(link: link)"])
        let paste = try slice(invites, from: "struct JoinPasteScreen: View {", to: "\n    private var trimmed")
        inOrder(paste, ["needsAccount", "ApprovalCopy.join(Approval.shared.state, group: nil)", "ApprovalJoinBlock(", "pasteField"])
    }

    // MARK: - The vault's copy

    /// `<uid>:<state>`, for that account only; another account, nothing, or anything malformed reads unknown.
    func testTheCacheBelongsToItsAccount() {
        XCTAssertEqual(Approval.encode(uid: "a:b", state: .approved), "a:b:approved")
        XCTAssertEqual(Approval.decode("a:b:approved", uid: "a:b"), .approved, "a colon in an id is kept")
        XCTAssertEqual(Approval.decode("u1:pending", uid: "u1"), .pending)
        XCTAssertEqual(Approval.decode("u1:approved", uid: "u2"), .unknown, "another account's state is not this one's")
        XCTAssertEqual(Approval.decode("u1:maybe", uid: "u1"), .unknown)
        XCTAssertEqual(Approval.decode("approved", uid: "u1"), .unknown)
        XCTAssertEqual(Approval.decode(nil, uid: "u1"), .unknown)
        XCTAssertEqual(Approval.decode("u1:approved", uid: nil), .unknown)
    }

    /// The copy lives in the vault (a kv value, never a record: nothing to sync), and every sign-out clears it.
    @MainActor
    func testTheCacheIsLocalAndClearedAtSignOut() throws {
        let store = tempStore()
        XCTAssertEqual(Approval.cached(store, uid: uid), .unknown)
        Approval.setCached(store, uid: uid, .approved)
        XCTAssertEqual(Approval.cached(store, uid: uid), .approved)
        XCTAssertEqual(Approval.cached(store, uid: "uid-other"), .unknown)
        XCTAssertEqual(try store.allRecords().count, 0, "a local value, never a record")
        Approval.shared.signedOut(store: store)
        XCTAssertEqual(Approval.cached(store, uid: uid), .unknown, "forgotten at sign-out")
        XCTAssertEqual(Approval.shared.state, .unknown)
        // A vault that cannot be read is closed.
        let brokenPath = dir.appendingPathComponent("broken.db").path
        FileManager.default.createFile(atPath: brokenPath, contents: Data(repeating: 7, count: 4096))
        let broken = LedgerStore(path: brokenPath)
        XCTAssertEqual(Approval.cached(broken, uid: uid), .unknown)
        // Every sign-out comes through Account.signOut (sign-out everywhere, a deletion and an erase too), and a session
        // ended elsewhere through the auth listener.
        let account = try appSource("Account.swift")
        let signOut = try slice(account, from: "    func signOut() {", to: "\n    }\n")
        XCTAssertTrue(signOut.contains("Approval.shared.signedOut()"))
        let attach = try slice(account, from: "    func attachToFirebase() {", to: "\n    }\n")
        inOrder(attach, ["uid = auth.currentUser?.uid", "Approval.shared.load(uid: uid)", "if user == nil {",
                         "Approval.shared.signedOut()"])
    }

    /// The sign-in asks for approval after the owner is kept and before the cloud starts.
    func testTheSignInAsksBeforeTheCloudStarts() throws {
        let account = try appSource("Account.swift")
        let signIn = try slice(account, from: "    func signIn(", to: "\n    }\n")
        inOrder(signIn, ["try SignInMethod.remember(method, store)", "uid = incoming", "Self.setSignedInMark(store, true)",
                         "await Approval.shared.signedIn(uid: incoming, store: store)", "CloudSync.shared.appIsActive(true)"])
        let approval = try appSource("Approval.swift")
        // D-254 look fix (item 3): `ask` gained `fresh` (a saved name never joins an ask in flight).
        let ask = try slice(approval, from: "    func ask(uid: String, store: LedgerStore = .defaultStore(), fresh: Bool = false) async -> ApprovalState {", to: "\n    }\n")
        inOrder(ask, ["[\"platform\": \"ios\"]", "CloudProfile.cloudName((try? store.loadSelf())?.name)",
                      "CloudFunctions.call(\"requestApproval\", data)",
                      "let answer = ApprovalState.server(result[\"state\"] as? String)", "shared.heard(answer, uid: uid, store: store)",
                      "return answer"])
        // The listener trusts the server only: a snapshot from the (memory) cache says nothing.
        let listen = try slice(approval, from: "    func listen(uid: String, store: LedgerStore = .defaultStore()) {", to: "\n    }\n")
        XCTAssertTrue(listen.contains("guard error == nil, let snapshot, !snapshot.metadata.isFromCache else { return }"))
        XCTAssertTrue(listen.contains("Cloud.db.collection(\"approvals\").document(uid)"))
        // Approval arriving: cached, the toast, then the cloud; taken away: cached, then the cloud stops.
        let heard = try slice(approval, from: "    func heard(_ server: ApprovalState, uid: String", to: "\n    }\n")
        inOrder(heard, ["case .approve(let toast):", "Self.setCached(store, uid: uid, .approved)",
                        "if toast { DoneCenter.shared.post(DoneMoments.line(ApprovalCopy.approvedToast)) }",
                        "if Self.startsCloudNow(step, inFront: inFront) { CloudSync.shared.appIsActive(true) }",
                        "case .withdraw(let new):", "Self.setCached(store, uid: uid, new)",
                        "CloudSync.shared.approvalWithdrawn()"])
        let sync = try appSource("CloudSync.swift")
        let withdrawn = try slice(sync, from: "    func approvalWithdrawn() {", to: "\n    }\n")
        inOrder(withdrawn, ["registered = nil", "stopListening()", "await Self.stopPush(true)"])
    }

    // MARK: - A deletion asked for while not approved

    /// An account with no profile (waiting, or not approved) has its deletion marker on `approvals/{uid}`; the hub's
    /// read finds it there, so Keep my account is offered at the next sign-in within the 7 days. A profile's own marker
    /// (or its absence) decides whenever there is a profile.
    func testAPendingDeletionIsReadFromTheApprovalWhenThereIsNoProfile() throws {
        let at = Date(timeIntervalSince1970: 1_800_000_000)
        let marker: [String: Any] = ["state": "pending", "deleteAt": Timestamp(date: at)]
        XCTAssertEqual(Phones.deletionDate(profile: nil, approval: marker), at, "no profile: the approval document's marker")
        XCTAssertNil(Phones.deletionDate(profile: nil, approval: ["state": "pending"]), "none asked")
        XCTAssertNil(Phones.deletionDate(profile: nil, approval: nil), "no approval document either")
        XCTAssertEqual(Phones.deletionDate(profile: ["deleteAt": Timestamp(date: at)], approval: nil), at)
        XCTAssertNil(Phones.deletionDate(profile: ["participantId": "x"], approval: marker), "a profile decides for itself")
        let phones = try appSource("Phones.swift")
        let read = try slice(phones, from: "    @MainActor static func pendingDeletion() async -> Date? {", to: "\n    }\n")
        inOrder(read, ["Cloud.db.collection(\"users\").document(uid).getDocument(source: .server)",
                       "guard !profile.exists else { return deletionDate(profile: profile.data(), approval: nil) }",
                       "Cloud.db.collection(\"approvals\").document(uid).getDocument(source: .server)",
                       "return deletionDate(profile: nil, approval: approval?.data())"])
    }

    // MARK: - D-254 fix wave (shells review)

    /// I1: while the account is not approved (waiting, not approved, or not heard yet), You's ring counts the five local
    /// steps only: no "Pick your username" the cloud would refuse, no notifications step that hears nothing.
    func testTheRingLeavesOutTheAccountStepsUntilApproved() throws {
        XCTAssertEqual(SetupSteps.steps(signedIn: true, lockAvailable: true, approved: false),
                       [.name, .picture, .currency, .appLock, .firstGroup])
        XCTAssertEqual(SetupSteps.steps(signedIn: true, lockAvailable: true, approved: true),
                       [.name, .picture, .currency, .appLock, .firstGroup, .username, .notifications])
        XCTAssertEqual(SetupSteps.steps(signedIn: true, lockAvailable: false, approved: false),
                       [.name, .picture, .currency, .firstGroup])
        var facts = SetupFacts(signedIn: true, lockAvailable: true)
        facts.approved = false
        XCTAssertFalse(SetupSteps.steps(facts).contains(.username))
        XCTAssertTrue(SetupSteps.progress(facts).total == 5)
        XCTAssertNotEqual(SetupSteps.next(facts, skipping: [.name, .picture, .currency, .appLock, .firstGroup]), .username)
        facts.approved = true
        XCTAssertTrue(SetupSteps.progress(facts).total == 7, "approved: the account steps are back")
        let you = try appSource("YouProgress.swift")
        let read = try slice(you, from: "    static func read(store: LedgerStore, account: Account, hasUsername: Bool) async -> SetupFacts {",
                             to: "\n    }\n")
        XCTAssertTrue(read.contains("facts.approved = Approval.allowsCloud(Approval.shared.state)"))
        // The facts are read again when the approval changes.
        let hub = try appSource("AccountHubScreen.swift")
        XCTAssertTrue(hub.contains("|\\(username ?? \"\")|\\(Approval.shared.state)\""))
    }

    /// I1: a live username check refused for approval says so; never "Could not reach SKIPTI's cloud", and Save stays off.
    func testAUsernameCheckRefusedForApprovalSaysSo() {
        XCTAssertEqual(UsernameStatus.failedCheck(CloudFunctions.Failure.awaitingApproval), .approval(ApprovalCopy.callWaiting))
        XCTAssertEqual(UsernameStatus.failedCheck(CloudFunctions.Failure.notApproved), .approval(ApprovalCopy.callDenied))
        XCTAssertNotEqual(UsernameStatus.failedCheck(CloudFunctions.Failure.awaitingApproval), .offline)
        XCTAssertEqual(UsernameStatus.failedCheck(CloudFunctions.Failure.offline), .offline, "offline is still offline")
        XCTAssertNil(UsernameStatus.canSave(status: .approval(ApprovalCopy.callWaiting), text: "goodname", list: nil),
                     "no Save that the cloud would refuse")
        XCTAssertEqual(UsernameStatus.canSave(status: .offline, text: "goodname", list: nil), "goodname", "offline still saves")
    }

    /// I2: a gated call while nothing is heard yet asks the server first (bounded), and is refused only for pending or
    /// denied; no answer is the offline refusal. A known state asks nothing.
    @MainActor
    func testAnUnknownStateIsAskedForBeforeACallIsRefused() async {
        var asked = 0
        func gate(_ cached: ApprovalState, answer: ApprovalState, name: String = "createInvite") async -> CloudFunctions.Failure? {
            await Approval.callRefusal(name, cached: cached) { asked += 1; return answer }
        }
        let approvedNow = await gate(.unknown, answer: .approved)
        XCTAssertNil(approvedNow, "approved on the server: the call goes")
        let noAnswer = await gate(.unknown, answer: .unknown)
        XCTAssertEqual(noAnswer, .offline, "no answer: the offline words, never the waiting ones")
        let pending = await gate(.unknown, answer: .pending)
        XCTAssertEqual(pending, .awaitingApproval)
        let denied = await gate(.unknown, answer: .denied)
        XCTAssertEqual(denied, .notApproved)
        XCTAssertEqual(asked, 4, "one ask each")
        let cachedPending = await gate(.pending, answer: .approved)
        XCTAssertEqual(cachedPending, .awaitingApproval)
        let cachedApproved = await gate(.approved, answer: .denied)
        XCTAssertNil(cachedApproved)
        let leaving = await gate(.unknown, answer: .denied, name: "requestDeletion")
        XCTAssertNil(leaving)
        XCTAssertEqual(asked, 4, "a known state, and an exempt call, ask nothing")
        // Bounded: an ask that never answers is no answer.
        let silent = await Approval.callRefusal("createInvite", cached: .unknown, seconds: 0.2) {
            try? await Task.sleep(nanoseconds: 30_000_000_000)
            return .approved
        }
        XCTAssertEqual(silent, .offline)
        XCTAssertEqual(Approval.resolveSeconds, 5)
        let approval = try? appSource("Approval.swift")
        let live = try? slice(approval ?? "", from: "    func refusal(for name: String, uid: String", to: "\n    }\n")
        XCTAssertTrue(live?.contains("Self.callRefusal(name, cached: Self.cached(store, uid: uid)) { await Approval.shared.ask(uid: uid, store: store) }") ?? false)
    }

    /// M1: a permission refusal is parked only while the server still holds the account approved; otherwise the push
    /// stops with nothing parked and the state read is handed on. An invalid argument parks without asking.
    @MainActor
    func testAPermissionRefusalIsNotParkedWhileNotApproved() async throws {
        let records = (0..<3).map { i -> RecordWire in
            RecordWire(version: 1, recordType: 1, groupId: nil, epoch: 0, hlcMillis: 1_790_000_000_000, hlcCounter: UInt32(i),
                       hlcDeviceId: String(repeating: "d", count: 32), authorDeviceId: String(repeating: "d", count: 32),
                       nonceSession: Data([0, 0, 0, 1]), flags: 0, blob: Data())
        }
        let ids = records.map(CloudRecords.documentId)
        func refused(_ code: FirestoreErrorCode.Code) -> NSError {
            NSError(domain: FirestoreErrorDomain, code: code.rawValue)
        }
        func push(_ state: ApprovalState, code: FirestoreErrorCode.Code = .permissionDenied) async
            -> (uploaded: [String], written: [String], refused: [String], offline: Bool, heard: ApprovalState?, asked: Int) {
            var asked = 0
            let one = await CloudSync.uploadOneByOne(records, write: { r in
                if r.hlcCounter == 1 { throw refused(code) }
            }, onServer: { _ in .notThere }, approval: { asked += 1; return state })
            return (one.uploaded, one.written, one.refused, one.offline, one.heard, asked)
        }
        for state in [ApprovalState.pending, .denied, .unknown] {
            let r = await push(state)
            XCTAssertEqual(r.refused, [], "nothing parked (\(state))")
            XCTAssertTrue(r.offline, "the push stops (\(state))")
            XCTAssertEqual(r.heard, state)
            XCTAssertEqual(r.uploaded, [ids[0]])
            XCTAssertEqual(r.asked, 1)
        }
        let approved = await push(.approved)
        XCTAssertEqual(approved.refused, [ids[1]], "approved: parked as before")
        XCTAssertFalse(approved.offline)
        XCTAssertNil(approved.heard)
        let invalid = await push(.pending, code: .invalidArgument)
        XCTAssertEqual(invalid.refused, [ids[1]], "not a permission refusal: parked, nothing asked")
        XCTAssertEqual(invalid.asked, 0)
        // pushAll reads the approval from the server and hands a state on to `heard`.
        let sync = try appSource("CloudSync.swift")
        let all = try slice(sync, from: "    static func pushAll(uid: String, store: LedgerStore) async -> Int {", to: "\n    enum GroupState")
        inOrder(all, ["}, approval: {", "await serverApproval(uid: uid)",
                      "if let state = one.heard, state != .unknown, Account.shared.uid == uid {",
                      "Approval.shared.heard(state, uid: uid, store: store)", "try? CloudRecords.park(store, Set(one.refused)"])
        let read = try slice(sync, from: "    static func serverApproval(uid: String) async -> ApprovalState {", to: "\n    }\n")
        XCTAssertTrue(read.contains("Cloud.db.collection(\"approvals\").document(uid).getDocument(source: .server)"))
    }

    /// M3: Notifications and Find me are away while the account waits or was not approved (they do nothing until then);
    /// Sign out, Sync and Delete account stay. Approved, or not heard yet, the page is as before.
    func testNotificationsAndFindMeWaitForApproval() throws {
        func shown(_ approval: ApprovalState) -> [SettingItem] {
            SettingsMap.shown(.profileAccount, model: accountHubModel(signedIn: true, syncOn: true, approval: approval))
        }
        XCTAssertEqual(shown(.pending), [.signedInAs, .signOut, .sync])
        XCTAssertEqual(shown(.denied), [.signedInAs, .signOut, .sync])
        // D-254 look fix (item 6, decision 252): Find me now sits right under Sign out (was after Notifications).
        XCTAssertEqual(shown(.approved), [.signedInAs, .signOut, .findMe, .sync, .notifications])
        XCTAssertEqual(shown(.unknown), shown(.approved), "not heard yet: as before")
        XCTAssertEqual(SettingsMap.waitsForApproval, [.notifications, .findMe])
        let waiting = accountHubModel(signedIn: true, syncOn: true, approval: .pending)
        XCTAssertTrue(SettingsMap.shown(.security, model: waiting).contains(.deleteAccount), "leaving never waits")
        XCTAssertEqual(SettingsMap.shown(.profileAccount, model: accountHubModel(signedIn: false, syncOn: true, approval: .pending)),
                       [.signIn])
        let hub = try appSource("AccountHubScreen.swift")
        // D-254 look fix (item 6): the page draws `shown` card by card (`SettingsMap.cards`), still never `items` alone.
        XCTAssertTrue(hub.contains("ForEach(SettingsMap.cards(SettingsMap.shown(section, model: model)), id: \\.self) { items in"))
        XCTAssertFalse(hub.contains("ForEach(SettingsMap.items(section, signedIn: model.showsAccountControls)"))
    }

    /// M4: approval heard while SKIPTI is not in front is kept, and the cloud starts at the next front event, not in the
    /// background.
    func testApprovalHeardInTheBackgroundWaitsForTheFront() throws {
        XCTAssertTrue(Approval.startsCloudNow(.approve(toast: true), inFront: true))
        XCTAssertTrue(Approval.startsCloudNow(.approve(toast: false), inFront: true))
        XCTAssertFalse(Approval.startsCloudNow(.approve(toast: true), inFront: false))
        XCTAssertFalse(Approval.startsCloudNow(.approve(toast: false), inFront: false))
        for step in [ApprovalStep.nothing, .keep(.pending), .withdraw(.denied)] {
            XCTAssertFalse(Approval.startsCloudNow(step, inFront: true), "\(step)")
        }
        let sync = try appSource("CloudSync.swift")
        let active = try slice(sync, from: "    func appIsActive(_ active: Bool) {", to: "\n        if active {")
        XCTAssertTrue(active.contains("Approval.shared.inFront = active"))
        let app = try appSource("SkiptiApp.swift")
        XCTAssertTrue(app.contains("Approval.shared.inFront = phase == .active"))
    }

    /// M5: a front event asks at most once per app process while the state stays the same; a new state, or another
    /// account, asks again; approved never asks.
    func testTheFrontAsksOncePerStatePerProcess() throws {
        XCTAssertTrue(Approval.frontAsks(answered: nil, uid: "u", state: .pending))
        XCTAssertFalse(Approval.frontAsks(answered: ("u", .pending), uid: "u", state: .pending))
        XCTAssertTrue(Approval.frontAsks(answered: ("u", .pending), uid: "u", state: .denied))
        XCTAssertTrue(Approval.frontAsks(answered: ("u", .pending), uid: "v", state: .pending))
        XCTAssertFalse(Approval.frontAsks(answered: nil, uid: "u", state: .approved))
        XCTAssertTrue(Approval.frontAsks(answered: nil, uid: "u", state: .unknown))
        let approval = try appSource("Approval.swift")
        let front = try slice(approval, from: "    func front(uid: String?, store: LedgerStore = .defaultStore()) {", to: "\n    }\n")
        inOrder(front, ["listen(uid: uid, store: store)", "guard Self.frontAsks(answered: answered, uid: uid, state: state) else { return }",
                        "Approval.shared.ask(uid: uid, store: store)"])
        XCTAssertFalse(approval.contains("askedRecently"), "no time window any more")
        // D-254 look fix (item 3): `ask` gained `fresh`.
        let ask = try slice(approval, from: "    func ask(uid: String, store: LedgerStore = .defaultStore(), fresh: Bool = false) async -> ApprovalState {", to: "\n    }\n")
        inOrder(ask, ["shared.heard(answer, uid: uid, store: store)", "if Account.shared.uid == uid { shared.answered = (uid, answer) }"])
        let signedOut = try slice(approval, from: "    func signedOut(store: LedgerStore = .defaultStore()) {", to: "\n    }\n")
        XCTAssertTrue(signedOut.contains("answered = nil"))
    }

    /// M8: one rule for the deletion marker on both shells (Android's twin uses the same inputs): a profile that exists
    /// decides (its marker or none); with no profile, the approval document's marker.
    func testTheDeletionMarkerRuleTwinTable() {
        let t1 = Date(timeIntervalSince1970: 1_800_000_000), t2 = Date(timeIntervalSince1970: 1_800_086_400)
        XCTAssertEqual(Phones.deletionDate(profile: ["deleteAt": Timestamp(date: t1)], approval: ["deleteAt": Timestamp(date: t2)]), t1)
        XCTAssertNil(Phones.deletionDate(profile: ["participantId": "x"], approval: ["deleteAt": Timestamp(date: t2)]))
        XCTAssertEqual(Phones.deletionDate(profile: nil, approval: ["state": "pending", "deleteAt": Timestamp(date: t2)]), t2)
        XCTAssertNil(Phones.deletionDate(profile: nil, approval: ["state": "pending"]))
        XCTAssertNil(Phones.deletionDate(profile: nil, approval: nil))
    }

    /// Review M9: approval taken away also clears this phone's token from its device document, by the token-only update
    /// the rules allow an account not approved (`fcmToken` removed, `lastSeen` set).
    @MainActor
    func testWithdrawalClearsTheTokenInTheCloud() async {
        let real = CloudSync.stopPush
        defer { CloudSync.stopPush = real }
        var calls: [Bool] = []
        let done = expectation(description: "push stopped")
        CloudSync.stopPush = { inCloud in calls.append(inCloud); done.fulfill() }
        CloudSync.shared.approvalWithdrawn()
        await fulfillment(of: [done], timeout: 5)
        XCTAssertEqual(calls, [true], "the token leaves the device document too")
        XCTAssertEqual(Set(CloudProfile.tokenUpdate(nil).keys), ["fcmToken", "lastSeen"], "a token-only update")
    }

    // MARK: - The look fixes (D-254 triage-look items 1, 2, 3, 6)

    /// Item 1: the "Pick your username" card asks only an approved account with no username, on Balance and on You.
    func testTheUsernameCardWaitsForApproval() throws {
        XCTAssertTrue(UsernameCard.shows(UsernameState.none, approval: .approved))
        XCTAssertFalse(UsernameCard.shows(UsernameState.none, approval: .pending))
        XCTAssertFalse(UsernameCard.shows(UsernameState.none, approval: .denied))
        XCTAssertFalse(UsernameCard.shows(UsernameState.none, approval: .unknown), "not heard yet: closed")
        XCTAssertFalse(UsernameCard.shows(.canChange("ada"), approval: .approved))
        XCTAssertFalse(UsernameCard.shows(.final("ada"), approval: .approved))
        XCTAssertFalse(UsernameCard.shows(nil, approval: .approved), "not read yet")
        for file in ["OverviewScreen.swift", "AccountHubScreen.swift"] {
            let source = try appSource(file)
            XCTAssertEqual(source.components(separatedBy: "UsernameCard { pickingUsername = true }").count, 2, file)
            inOrder(source, ["if UsernameCard.shows(usernameState, approval: Approval.shared.state) {",
                             "UsernameCard { pickingUsername = true }"])
            XCTAssertFalse(source.contains("if usernameState == UsernameState.none {"), file)
        }
    }

    /// Item 2: a front event while not approved READS the server's word (bounded) and hears it; approved reads nothing;
    /// no answer hears nothing. The read is the server read, never a write.
    @MainActor
    func testTheFrontReadsTheServerWhileNotApproved() async throws {
        var reads = 0
        func read(_ answer: ApprovalState) -> @MainActor () async -> ApprovalState { { reads += 1; return answer } }
        let approved = await Approval.frontRecheck(state: .approved, read: read(.approved))
        XCTAssertNil(approved)
        XCTAssertEqual(reads, 0, "approved: nothing read")
        let fromPending = await Approval.frontRecheck(state: .pending, read: read(.approved))
        XCTAssertEqual(fromPending, .approved)
        XCTAssertEqual(reads, 1)
        let stillPending = await Approval.frontRecheck(state: .pending, read: read(.pending))
        XCTAssertEqual(stillPending, .pending)
        let fromDenied = await Approval.frontRecheck(state: .denied, read: read(.approved))
        XCTAssertEqual(fromDenied, .approved)
        let fromUnknown = await Approval.frontRecheck(state: .unknown, read: read(.approved))
        XCTAssertEqual(fromUnknown, .approved)
        let noAnswer = await Approval.frontRecheck(state: .pending, read: read(.unknown))
        XCTAssertNil(noAnswer, "no answer: nothing heard")
        let never = await Approval.frontRecheck(state: .pending, seconds: 0.2) {
            try? await Task.sleep(nanoseconds: 5_000_000_000)
            return .approved
        }
        XCTAssertNil(never, "a read that never answers is bounded")
        XCTAssertEqual(Approval.resolveSeconds, 5)
        let approval = try appSource("Approval.swift")
        let front = try slice(approval, from: "    func front(uid: String?, store: LedgerStore = .defaultStore()) {", to: "\n    }\n")
        inOrder(front, ["listen(uid: uid, store: store)", "if Self.frontReads(state) {",
                        "await Self.frontRecheck(state: current, read: { await Approval.serverRead(uid) })",
                        "Approval.shared.heard(heard, uid: uid, store: store)",
                        "guard Self.frontAsks(answered: answered, uid: uid, state: state) else { return }"])
        XCTAssertTrue(approval.contains("static var serverRead: @MainActor (_ uid: String) async -> ApprovalState = { await CloudSync.serverApproval(uid: $0) }"))
    }

    /// Item 3: a saved name asks for approval again while the account is not approved; a refused name asks nothing.
    @MainActor
    func testSavingYourNameAsksAgainWhileNotApproved() throws {
        for state in [ApprovalState.pending, .denied, .unknown] {
            XCTAssertTrue(Approval.nameAsks(uid: "u", owner: "u", state: state), "\(state)")
        }
        XCTAssertFalse(Approval.nameAsks(uid: "u", owner: "u", state: .approved), "approved: nothing to ask")
        XCTAssertFalse(Approval.nameAsks(uid: nil, owner: "u", state: .pending), "signed out")
        XCTAssertFalse(Approval.nameAsks(uid: "u", owner: "v", state: .pending), "not the vault's owner")
        let store = tempStore()
        var asks = 0
        let spy = ProfileSave.Effects(changed: {}, share: {}, bump: {}, ask: { asks += 1 })
        let center = DoneCenter(gate: SuccessGate(), haptic: { _, _ in }, announce: { _ in })
        XCTAssertEqual(ProfileSave.name("   ", store: store, effects: spy, center: center), .refused("Add a name first."))
        XCTAssertEqual(asks, 0, "a refused name asks nothing")
        XCTAssertEqual(ProfileSave.name("Ada", store: store, effects: spy, center: center), .saved("Ada"))
        XCTAssertEqual(asks, 1, "one ask per saved name")
        XCTAssertNil(ProfileSave.returned("Ada", saved: "Ada", store: store, effects: spy, center: center))
        XCTAssertEqual(asks, 1, "an unchanged Return saves and asks nothing")
        let confirm = try appSource("ProfileConfirm.swift")
        XCTAssertTrue(confirm.contains("ask: { Approval.shared.nameSaved() })"), "the live save asks")
        let approval = try appSource("Approval.swift")
        let saved = try slice(approval, from: "    func nameSaved(store: LedgerStore = .defaultStore()) {", to: "\n    }\n")
        inOrder(saved, ["Self.nameAsks(uid: current, owner: Account.storedOwner(store), state: Self.cached(store, uid: current))",
                        "await Approval.shared.ask(uid: uid, store: store, fresh: true)"])
        // The other places your own name is saved ask too.
        let sheets = try appSource("GroupSheets.swift")
        inOrder(sheets, ["try store.setYourName(name)", "Approval.shared.nameSaved()"])
        inOrder(sheets, ["try CreateGroup.create(", "if needsYourName { Approval.shared.nameSaved() }"])
        inOrder(try appSource("UsernameStep.swift"), ["_ = try store.setYourName(name)", "Approval.shared.nameSaved()"])
        inOrder(try appSource("ImportFlow.swift"), ["try job.commit($0, store: LedgerStore.defaultStore())",
                                                    "if job.newName != nil { Approval.shared.nameSaved() }"])
        // Every file that saves your own name (Android's twin guards the same list). The four that do not ask are the
        // Debug proofs and seeds (Account, GroupsScreen, SkiptiApp: emulator accounts approved first) and the store
        // shots' seed (no account); a new path that saves your name must ask, or be added here with its reason.
        let saves = Set(try appSources().filter { $0.text.contains(".setYourName(") }.map(\.name))
        XCTAssertEqual(saves, ["Account.swift", "GroupSheets.swift", "GroupsScreen.swift", "ImportFlow.swift",
                               "ProfileConfirm.swift", "SkiptiApp.swift", "StoreShots.swift", "UsernameStep.swift"])
    }

    /// Item 3: a fresh run never joins the one in flight (its input may be stale): it waits, then runs its own work.
    @MainActor
    func testAFreshRunWaitsForTheOneInFlightThenRunsItsOwn() async {
        let flight = SingleFlight<String>()
        var gate: CheckedContinuation<Void, Never>?
        var started: [String] = []
        let first = Task { @MainActor in
            await flight.run("k") {
                started.append("old")
                await withCheckedContinuation { gate = $0 }
                return "old"
            }
        }
        while gate == nil { await Task.yield() }
        let joined = Task { @MainActor in await flight.run("k") { started.append("joiner"); return "joiner" } }
        let fresh = Task { @MainActor in await flight.run("k", fresh: true) { started.append("new"); return "new" } }
        for _ in 0..<20 { await Task.yield() }
        XCTAssertEqual(started, ["old"], "nothing else starts while one is in flight")
        gate?.resume()
        let answers = (await first.value, await joined.value, await fresh.value)
        XCTAssertEqual(answers.0, "old")
        XCTAssertEqual(answers.1, "old", "an ordinary caller joins")
        XCTAssertEqual(answers.2, "new", "a fresh caller runs its own work after")
        XCTAssertEqual(started, ["old", "new"])
    }

    /// Item 6 (decision 252): Profile and account's cards: how you are signed in with Sign out (one card), Find me, then
    /// Sync and Notifications; waiting or denied, Find me and Notifications are away.
    func testProfileAndAccountCardsPutSignOutAndFindMeFirst() {
        func shown(_ approval: ApprovalState, signedIn: Bool = true) -> [SettingItem] {
            SettingsMap.shown(.profileAccount, model: accountHubModel(signedIn: signedIn, syncOn: true, approval: approval))
        }
        XCTAssertEqual(SettingsMap.cards(shown(.approved)), [[.signedInAs, .signOut], [.findMe], [.sync], [.notifications]])
        XCTAssertEqual(SettingsMap.cards(shown(.unknown)), SettingsMap.cards(shown(.approved)))
        XCTAssertEqual(SettingsMap.cards(shown(.pending)), [[.signedInAs, .signOut], [.sync]])
        XCTAssertEqual(SettingsMap.cards(shown(.denied)), [[.signedInAs, .signOut], [.sync]])
        XCTAssertEqual(SettingsMap.cards(shown(.approved, signedIn: false)), [[.signIn]])
        XCTAssertEqual(SettingsMap.accountCard, [.signedInAs, .signOut])
        // Only the two account items join; every other section keeps one card per item.
        XCTAssertEqual(SettingsMap.cards([.signOut, .signedInAs]), [[.signOut], [.signedInAs]])
        XCTAssertEqual(SettingsMap.cards([.theme, .appearanceMode, .textSize]), [[.theme], [.appearanceMode], [.textSize]])
    }

    // MARK: - The emulator helper

    /// `EmulatorApproval` exists only in Debug builds and acts only against the emulators; every call to it sits in
    /// Debug-only code (the proofs).
    @MainActor
    func testTheEmulatorHelperIsDebugOnlyAndEmulatorOnly() throws {
        let approval = try appSource("Approval.swift")
        let debug = try XCTUnwrap(approval.range(of: "\n#if DEBUG\n"))
        let helper = try XCTUnwrap(approval.range(of: "enum EmulatorApproval {"))
        let end = try XCTUnwrap(approval.range(of: "\n#endif", range: helper.upperBound..<approval.endIndex))
        XCTAssertLessThan(debug.lowerBound, helper.lowerBound)
        XCTAssertEqual(approval.components(separatedBy: "enum EmulatorApproval").count, 2, "one declaration")
        XCTAssertNil(approval[debug.upperBound..<helper.lowerBound].range(of: "#endif"), "still inside #if DEBUG")
        let approve = try slice(String(approval[helper.lowerBound..<end.lowerBound]), from: "static func approve(_ uid: String) async -> Bool {", to: "\n    }\n")
        XCTAssertTrue(approve.hasPrefix("\n        guard Cloud.emulator else { return false }"), "the emulators only, first")
        XCTAssertTrue(approve.contains("CloudFunctions.url(\"adminApprovals\")"))
        XCTAssertTrue(approval.contains("\"sub\": admin, \"user_id\": admin"))
        XCTAssertEqual(EmulatorApproval.admin, "emulator-admin")
        let token = EmulatorApproval.adminToken().components(separatedBy: ".")
        XCTAssertEqual(token.count, 3)
        XCTAssertEqual(token[2], "", "unsigned")
        // Every call site is inside an #if DEBUG block, and there is one in each proof.
        var calls = 0
        for (name, text) in try appSources() {
            var open: [Bool] = []
            for line in text.components(separatedBy: "\n") {
                let trimmed = line.trimmingCharacters(in: .whitespaces)
                if trimmed.hasPrefix("#if ") { open.append(trimmed == "#if DEBUG") }
                else if trimmed.hasPrefix("#endif") { _ = open.popLast() }
                if line.contains("EmulatorApproval.approve(") {
                    calls += 1
                    XCTAssertTrue(open.contains(true), "\(name): \(trimmed) is outside #if DEBUG")
                }
            }
        }
        XCTAssertEqual(calls, 5, "the four Account proofs and the group-end proof")
    }
}
