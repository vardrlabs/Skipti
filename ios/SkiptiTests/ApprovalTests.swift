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
    /// waiting words (pending, or not heard yet) or the not approved ones (denied); the exempt ones always go.
    func testTheCallGateRefusesLocallyAndLetsLeavingThrough() {
        let exempt = ["signOutDevice", "signOutEverywhere", "requestDeletion", "cancelDeletion", "requestApproval", "adminApprovals"]
        XCTAssertEqual(Approval.exempt, Set(exempt), "the server's approvals.EXEMPT")
        for state in ApprovalState.allCases {
            for name in exempt { XCTAssertNil(Approval.localRefusal(name, approval: state), "\(name) \(state)") }
        }
        for name in ["createInvite", "peekInvite", "acceptInvite", "findPeople", "setUsername", "setFindable", "inviteUser",
                     "inviteKnown", "adoptRemovals", "deleteGroup", "groupRoles", "shareProfile"] {
            XCTAssertEqual(Approval.localRefusal(name, approval: .pending), .awaitingApproval, name)
            XCTAssertEqual(Approval.localRefusal(name, approval: .unknown), .awaitingApproval, name)
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
                       "if let refusal = Approval.localRefusal(name, approval: Approval.cached(.defaultStore(), uid: user.uid)) {",
                       "throw refusal", "user.getIDToken()", "URLSession.shared.data(for: request)",
                       // A server refusal for approval (`none` included) asks for approval, so the request exists.
                       "catch let failure as Failure where failure == .awaitingApproval || failure == .notApproved {",
                       "Approval.shared.ask(uid: user.uid)", "throw failure"])
        let create = try slice(invites, from: "    static func create(store: LedgerStore, group: NamedGroupWire, member: MemberWire)", to: "\n    }\n")
        inOrder(create, ["Approval.localRefusal(\"createInvite\"", "CloudSync.ensureGroup("])
        let people = try appSource("People.swift")
        let invite = try slice(people, from: "    static func invite(store: LedgerStore, group: NamedGroupWire, member: MemberWire, person: FoundPerson)", to: "\n    }\n")
        inOrder(invite, ["Approval.localRefusal(\"inviteUser\"", "CloudSync.ensureGroup("])
        let known = try slice(people, from: "    static func inviteKnown(", to: "\n    }\n")
        inOrder(known, ["Approval.localRefusal(\"inviteKnown\"", "CloudSync.ensureGroup("])
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
        let ask = try slice(approval, from: "    func ask(uid: String, store: LedgerStore = .defaultStore()) async {", to: "\n    }\n")
        inOrder(ask, ["[\"platform\": \"ios\"]", "CloudProfile.cloudName((try? store.loadSelf())?.name)",
                      "CloudFunctions.call(\"requestApproval\", data)", "heard(ApprovalState.server(result[\"state\"] as? String)"])
        // The listener trusts the server only: a snapshot from the (memory) cache says nothing.
        let listen = try slice(approval, from: "    func listen(uid: String, store: LedgerStore = .defaultStore()) {", to: "\n    }\n")
        XCTAssertTrue(listen.contains("guard error == nil, let snapshot, !snapshot.metadata.isFromCache else { return }"))
        XCTAssertTrue(listen.contains("Cloud.db.collection(\"approvals\").document(uid)"))
        // Approval arriving: cached, the toast, then the cloud; taken away: cached, then the cloud stops.
        let heard = try slice(approval, from: "    func heard(_ server: ApprovalState, uid: String", to: "\n    }\n")
        inOrder(heard, ["case .approve(let toast):", "Self.setCached(store, uid: uid, .approved)",
                        "if toast { DoneCenter.shared.post(DoneMoments.line(ApprovalCopy.approvedToast)) }",
                        "CloudSync.shared.appIsActive(true)", "case .withdraw(let new):", "Self.setCached(store, uid: uid, new)",
                        "CloudSync.shared.approvalWithdrawn()"])
        let sync = try appSource("CloudSync.swift")
        let withdrawn = try slice(sync, from: "    func approvalWithdrawn() {", to: "\n    }\n")
        inOrder(withdrawn, ["registered = nil", "stopListening()", "Push.shared.stop(inCloud: false)"])
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
