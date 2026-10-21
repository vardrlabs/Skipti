import XCTest
@testable import Skipti

/// D-168: one account per phone (the founder, 2026-09-25); D-192: the owner
/// is the Firebase uid (AccountTests covers the rule itself). Android's twin
/// is AccountOwnerTest.
final class AccountOwnerTests: XCTestCase {
    private func tempPath() -> String {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("owner-\(UUID().uuidString)", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir.appendingPathComponent("vault.db").path
    }

    /// D-172: how the phone signed in is kept for Settings, and a stored
    /// value this build does not know is no method at all.
    func testTheSignInMethodIsKeptAndAnUnknownOneIsNone() throws {
        XCTAssertEqual(SignInMethod.decode("apple"), .apple)
        XCTAssertEqual(SignInMethod.decode("google"), .google)
        XCTAssertEqual(SignInMethod.decode("email:tim@example.com"), .email("tim@example.com"))
        XCTAssertNil(SignInMethod.decode("email:"))
        XCTAssertNil(SignInMethod.decode("facebook"))
        XCTAssertNil(SignInMethod.decode("Apple"))
        for method in [SignInMethod.apple, .google, .email("a@b.c")] {
            XCTAssertEqual(SignInMethod.decode(method.encoded), method)
        }
        XCTAssertEqual(SignInMethod.apple.label, "Signed in with Apple")
        XCTAssertEqual(SignInMethod.google.label, "Signed in with Google")
        // D-234 (decision 84): You's row says the method in one word.
        XCTAssertEqual(SignInMethod.apple.rowLabel, "Apple")
        XCTAssertEqual(SignInMethod.google.rowLabel, "Google")
        XCTAssertEqual(SignInMethod.email("a@b.c").rowLabel, "Email", "never the address on the row")

        let store = LedgerStore(path: tempPath())
        XCTAssertNil(SignInMethod.stored(store))
        try SignInMethod.remember(.apple, store)
        XCTAssertEqual(SignInMethod.stored(store), .apple)
        try SignInMethod.remember(.google, store)
        XCTAssertEqual(SignInMethod.stored(store), .google, "the latest way in")
    }

    /// D-168: the erase takes the vault's files and its key; a new ledger at
    /// the same place has no owner, so any account may sign in there.
    func testTheEraseTakesTheFilesAndTheKeyAndLeavesAFreshLedger() throws {
        final class FakeKeystore: SkiptiKeystore {
            var destroyed = false
            func provision(key: Data) throws {}
            func load() throws -> Data? { nil }
            func destroy() throws { destroyed = true }
        }
        let path = tempPath()
        let store = LedgerStore(path: path)
        try store.kvSet(key: Account.ownerKey, value: Data("uid-tim".utf8))
        _ = try store.ensureSelf()
        XCTAssertEqual(Account.storedOwner(store), "uid-tim")
        XCTAssertTrue(FileManager.default.fileExists(atPath: path))

        let keystore = FakeKeystore()
        try LedgerErase.erase(vaultPath: path, keystore: keystore)
        XCTAssertFalse(FileManager.default.fileExists(atPath: path))
        XCTAssertTrue(keystore.destroyed)

        let fresh = LedgerStore(path: path)
        XCTAssertNil(Account.storedOwner(fresh))
        XCTAssertNil(try fresh.loadSelf())
        XCTAssertTrue(Account.owns(stored: Account.storedOwner(fresh), incoming: "uid-bob"))
    }

    // ---- D-225 Task 6: the vault recovery screen tries again first (A7, A9) ----

    private struct Refused: Error {}

    /// A9: "Try again" re-reads the vault; when it opens, the app goes on as a normal launch (no screen, the D-175 flag
    /// read and, on a first launch, set).
    func testTryAgainThatOpensTheLedgerGoesOnAsANormalLaunch() {
        var marked = 0
        XCTAssertEqual(VaultRecovery.launch(read: { true }, mark: { marked += 1 }), .opened(launchedBefore: true))
        XCTAssertEqual(marked, 0, "a phone that launched before is not marked again")
        let first = VaultRecovery.launch(read: { false }, mark: { marked += 1 })
        XCTAssertEqual(first, .opened(launchedBefore: false))
        XCTAssertEqual(marked, 1, "a first launch is marked")
        XCTAssertNil(VaultRecovery.retried(.opened(launchedBefore: true)), "the screen goes")
        XCTAssertNil(VaultRecovery.retried(first))
    }

    /// A9: a retry that still cannot open it keeps the screen, with the reason read now and the calm retry line;
    /// nothing is marked and nothing erased. A real vault of garbage bytes is unavailable and left as it was.
    func testTryAgainThatStillFailsKeepsTheScreen() throws {
        var marked = false
        let launch = VaultRecovery.launch(read: { throw StoreError.WrongKey }, mark: { marked = true })
        XCTAssertEqual(launch, .unavailable(reason: "The key that opens this ledger is not on this device."))
        XCTAssertFalse(marked)
        let screen = try XCTUnwrap(VaultRecovery.retried(launch))
        XCTAssertEqual(screen, VaultRecovery.Screen(reason: VaultRecoveryCopy.wrongKey, note: .retryFailed))
        XCTAssertEqual(screen.note?.text, "Tried again just now. It still will not open.")

        let path = tempPath()
        let garbage = Data((0..<4096).map { UInt8(($0 * 37 + 11) % 251) })
        try garbage.write(to: URL(fileURLWithPath: path))
        guard case .unavailable = VaultRecovery.launch(LedgerStore(path: path)) else {
            return XCTFail("a vault that will not open must never read as a launch")
        }
        XCTAssertEqual(try Data(contentsOf: URL(fileURLWithPath: path)), garbage, "nothing erased or rewritten")
    }

    /// A keystore that records its destroy (and can refuse it), so no test touches the phone's real keychain item.
    private final class RecordingKeystore: SkiptiKeystore {
        var destroyed = false
        var refuses = false
        func provision(key: Data) throws {}
        func load() throws -> Data? { nil }
        func destroy() throws {
            if refuses { throw Refused() }
            destroyed = true
        }
    }

    /// A9: Start fresh's erase that fails says so (`EraseCopy.failed`) and keeps the screen and its reason; it used
    /// `try?` and went on as if the files had gone. One that works takes the vault's files and leaves the screen.
    /// D-225 fix wave (the controller's A9 decision): the write-ahead files go first, the database last, then the
    /// keychain item, only once every file is gone (as Android's `LedgerErase`); a refused file keeps the key, and a
    /// refused key keeps the screen too.
    func testAFailedEraseShowsTheFailedWordingAndKeepsTheScreen() throws {
        let path = tempPath()
        try Data([1, 2, 3]).write(to: URL(fileURLWithPath: path))
        try Data([4]).write(to: URL(fileURLWithPath: path + "-wal"))
        let refusing = RecordingKeystore()
        let kept = VaultRecovery.erase(vaultPath: path, reason: "why", keystore: refusing, signOut: {}, remove: { _ in throw Refused() })
        XCTAssertEqual(kept, VaultRecovery.Screen(reason: "why", note: .eraseFailed))
        XCTAssertEqual(kept?.note?.text, EraseCopy.failed)
        XCTAssertTrue(FileManager.default.fileExists(atPath: path))
        XCTAssertFalse(refusing.destroyed, "the key stays while a file is left")

        let keyRefused = RecordingKeystore()
        keyRefused.refuses = true
        XCTAssertEqual(VaultRecovery.erase(vaultPath: path, reason: "why", keystore: keyRefused, signOut: {}),
                       VaultRecovery.Screen(reason: "why", note: .eraseFailed), "a keychain that refuses keeps the screen")
        try Data([1, 2, 3]).write(to: URL(fileURLWithPath: path))
        try Data([4]).write(to: URL(fileURLWithPath: path + "-wal"))
        try Data([5]).write(to: URL(fileURLWithPath: path + "-shm"))
        try Data([6]).write(to: URL(fileURLWithPath: path + "-journal"))

        var order: [String] = []
        let keystore = RecordingKeystore()
        XCTAssertNil(VaultRecovery.erase(vaultPath: path, reason: "why", keystore: keystore, signOut: {}, remove: { file in
            XCTAssertFalse(keystore.destroyed, "the key goes after the files")
            order.append(file)
            try FileManager.default.removeItem(atPath: file)
        }), "erased: the launch runs again")
        XCTAssertEqual(order, [path + "-wal", path + "-shm", path + "-journal", path], "write-ahead files first, the database last")
        XCTAssertTrue(keystore.destroyed, "then the key")
        for file in [path, path + "-wal", path + "-shm", path + "-journal"] {
            XCTAssertFalse(FileManager.default.fileExists(atPath: file), file)
        }
        XCTAssertEqual(LedgerErase.files(path), [path + "-wal", path + "-shm", path + "-journal", path], "one order for both erases")
        // Android's erase takes the same files in the same order (its `LedgerErase.ORDER`).
        let app = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
        let kotlin = try String(contentsOf: app.appendingPathComponent("../android/app/src/main/java/com/vardrlabs/skipti/AccountOwner.kt"), encoding: .utf8)
        XCTAssertTrue(kotlin.contains("val ORDER = listOf(\"-wal\", \"-shm\", \"-journal\", \"\")"), "Android's order")
    }

    /// D-225 follow-up (WORKLOG decision 27): the recovery screen's confirmed erase signs out of the cloud session
    /// first, as the deliberate erase does (the account's owner key lives in the vault, so a session left behind would
    /// point at a ledger that no longer exists), then takes the files and the key; a sign-out that throws erases
    /// nothing and keeps the screen. Both erases are the one operation, `LedgerErase.signOutAndErase` (Android's twin
    /// `the_recovery_erase_signs_out_first_as_the_deliberate_erase_does`).
    func testTheRecoveryEraseSignsOutFirstAsTheDeliberateEraseDoes() throws {
        let path = tempPath()
        try Data([1, 2, 3]).write(to: URL(fileURLWithPath: path))
        try Data([4]).write(to: URL(fileURLWithPath: path + "-wal"))
        var steps: [String] = []
        let keystore = RecordingKeystore()
        XCTAssertNil(VaultRecovery.erase(vaultPath: path, reason: "why", keystore: keystore, signOut: { steps.append("sign out") },
                                         remove: { file in
                                             steps.append(URL(fileURLWithPath: file).lastPathComponent)
                                             try FileManager.default.removeItem(atPath: file)
                                         }), "the erase is done")
        let name = URL(fileURLWithPath: path).lastPathComponent
        XCTAssertEqual(steps, ["sign out", name + "-wal", name], "signed out before any file goes")
        XCTAssertTrue(keystore.destroyed, "the key went too")

        // A sign-out that throws erases nothing and keeps the screen with the failed wording.
        try Data([1, 2, 3]).write(to: URL(fileURLWithPath: path))
        let kept = RecordingKeystore()
        let refused = VaultRecovery.erase(vaultPath: path, reason: "why", keystore: kept, signOut: { throw Refused() })
        XCTAssertEqual(refused, VaultRecovery.Screen(reason: "why", note: .eraseFailed))
        XCTAssertTrue(FileManager.default.fileExists(atPath: path), "nothing erased")
        XCTAssertFalse(kept.destroyed, "the key stays")

        // The shell: the recovery erase signs this phone's account out (on main, where the account lives), and the
        // deliberate erase is the same operation; nothing erases without signing out.
        let app = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
        let shell = try String(contentsOf: app.appendingPathComponent("Skipti/App/SkiptiApp.swift"), encoding: .utf8)
        XCTAssertTrue(shell.contains("""
            let kept = VaultRecovery.erase(vaultPath: db.path, reason: reason, keystore: KeychainKeystore(), signOut: {
                            DispatchQueue.main.sync { MainActor.assumeIsolated { Account.shared.signOut() } }
                        })
            """), "the recovery erase signs out")
        let owner = try String(contentsOf: app.appendingPathComponent("Skipti/App/AccountOwner.swift"), encoding: .utf8)
        let deliberate = try XCTUnwrap(owner.components(separatedBy: "static func eraseThisPhone() throws {").last?
            .components(separatedBy: "\n    }\n").first)
        XCTAssertTrue(deliberate.contains("try signOutAndErase(vaultPath: LedgerStore.defaultStore().path, keystore: KeychainKeystore(),"))
        XCTAssertTrue(deliberate.contains("signOut: { Account.shared.signOut() })"))
        let both = try XCTUnwrap(owner.components(separatedBy: "static func signOutAndErase(").last?
            .components(separatedBy: "\n    }\n").first)
        XCTAssertTrue(both.contains("try signOut()\n        try erase(vaultPath: vaultPath, keystore: keystore, remove: remove)"), "sign out, then erase")
        let recovery = try String(contentsOf: app.appendingPathComponent("Skipti/App/VaultRecoveryView.swift"), encoding: .utf8)
        XCTAssertTrue(recovery.contains("try LedgerErase.signOutAndErase(vaultPath: vaultPath, keystore: keystore, signOut: signOut, remove: remove)"))
        var bare = 0
        for file in try FileManager.default.contentsOfDirectory(atPath: app.appendingPathComponent("Skipti/App").path) where file.hasSuffix(".swift") {
            let source = try String(contentsOf: app.appendingPathComponent("Skipti/App/\(file)"), encoding: .utf8)
            bare += source.components(separatedBy: "LedgerErase.erase(").count - 1
        }
        XCTAssertEqual(bare, 0, "no erase outside the signed-out one")
    }

    /// D-225 fix wave (the controller's A9 decision, review M1): the retry line is about trying again, so it hides while
    /// the erase is asked; a failed erase's line shows there, where it happened.
    func testTheRetryLineHidesWhileTheEraseIsAsked() {
        XCTAssertNil(VaultRecovery.shown(.retryFailed, confirming: true))
        XCTAssertEqual(VaultRecovery.shown(.retryFailed, confirming: false), .retryFailed)
        XCTAssertEqual(VaultRecovery.shown(.eraseFailed, confirming: true), .eraseFailed)
        XCTAssertEqual(VaultRecovery.shown(.eraseFailed, confirming: false), .eraseFailed)
        XCTAssertNil(VaultRecovery.shown(nil, confirming: false))
        XCTAssertNil(VaultRecovery.shown(nil, confirming: true))
    }

    /// D-225 fix wave (the controller's A9 decision, review M10): one attempt at a time; while "Try again" or the
    /// erase works, a second tap does nothing, and the next attempt starts once it is done.
    func testOneRecoveryAttemptAtATime() {
        var attempts = VaultRecovery.Attempts()
        XCTAssertFalse(attempts.busy)
        XCTAssertTrue(attempts.begin())
        XCTAssertTrue(attempts.busy)
        XCTAssertFalse(attempts.begin(), "a second tap while working does nothing")
        attempts.end()
        XCTAssertFalse(attempts.busy)
        XCTAssertTrue(attempts.begin(), "the next attempt after it")
    }

    /// A7, A9 (source guard): the screen offers "Try again" first as the primary button and "Start fresh" second as a
    /// secondary one that only asks; the one erase sits in the confirm step; the app's reset reports a failure instead
    /// of `try?`; the words are Android's (the paragraph is new here; Android copies it in the fix wave).
    func testTheRecoveryScreenTriesAgainFirst() throws {
        let app = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
        let view = try String(contentsOf: app.appendingPathComponent("Skipti/App/VaultRecoveryView.swift"), encoding: .utf8)
        let body = try XCTUnwrap(view.components(separatedBy: "struct VaultRecoveryView: View {").last)
        let retry = try XCTUnwrap(body.range(of: "Button(VaultRecoveryCopy.tryAgain) { onRetry() }\n                        .buttonStyle(PrimaryButtonStyle())"))
        let fresh = try XCTUnwrap(body.range(of: "Button(VaultRecoveryCopy.startFresh) { confirming = true }\n                        .buttonStyle(SecondaryButtonStyle())"))
        XCTAssertLessThan(retry.lowerBound, fresh.lowerBound, "Try again comes first")
        XCTAssertEqual(body.components(separatedBy: "onReset()").count - 1, 1, "one erase")
        let confirm = try XCTUnwrap(body.range(of: "                if confirming {\n                    Button(VaultRecoveryCopy.erase"))
        let erase = try XCTUnwrap(body.range(of: "onReset()"))
        XCTAssertLessThan(confirm.lowerBound, erase.lowerBound, "the erase is inside the confirm step")
        XCTAssertLessThan(erase.lowerBound, retry.lowerBound, "and not in the first step")
        // Fix wave: the note through `shown` (the retry line hides while the erase is asked); every button is
        // disabled (dimmed, deaf to taps) while an attempt works.
        XCTAssertTrue(body.contains("if let note = VaultRecovery.shown(note, confirming: confirming) {"))
        XCTAssertFalse(body.contains("if let note {"))
        XCTAssertEqual(body.components(separatedBy: "Button(VaultRecoveryCopy.").count - 1, 4, "four buttons")
        XCTAssertEqual(body.components(separatedBy: "                        .disabled(busy)\n").count - 1, 4, "each one waits")

        let shell = try String(contentsOf: app.appendingPathComponent("Skipti/App/SkiptiApp.swift"), encoding: .utf8)
        let reset = try XCTUnwrap(shell.components(separatedBy: "    private func resetVault() {").last?.components(separatedBy: "\n    }\n").first)
        XCTAssertFalse(reset.contains("try?"), "a failed erase is never swallowed")
        XCTAssertTrue(reset.hasPrefix("\n        guard vaultAttempts.begin() else { return }\n"), "one attempt at a time")
        XCTAssertTrue(reset.contains("let kept = VaultRecovery.erase(vaultPath: db.path, reason: reason, keystore: KeychainKeystore(), signOut: {"))
        XCTAssertTrue(reset.contains("vaultAttempts.end()\n                if let kept {\n                    vaultNote = kept.note\n                    return\n                }"))
        XCTAssertTrue(shell.contains("onRetry: { loadGate(retry: true) }"))
        XCTAssertTrue(shell.contains("busy: vaultAttempts.busy"))
        XCTAssertTrue(shell.contains("if retry { guard vaultAttempts.begin() else { return } }"))
        XCTAssertTrue(shell.contains("vaultNote = retry ? VaultRecovery.retried(launch)?.note : nil"))

        XCTAssertTrue(VaultRecoveryCopy.explain.contains("try again first"))
        XCTAssertTrue(VaultRecoveryCopy.explain.contains("keep it for last"))
        let kotlin = try String(contentsOf: app.appendingPathComponent("../android/app/src/main/java/com/vardrlabs/skipti/AccountOwner.kt"), encoding: .utf8)
        for line in [VaultRecoveryCopy.kicker, VaultRecoveryCopy.title, VaultRecoveryCopy.startFresh, VaultRecoveryCopy.eraseWarning,
                     VaultRecoveryCopy.erase, VaultRecoveryCopy.cancel, VaultRecoveryCopy.wrongKey] {
            XCTAssertTrue(kotlin.contains("\"\(line)\""), "Android says: \(line)")
        }
        // Fix wave (review I1): Android's new lines, by name, word for word (the paragraph is joined from its pieces).
        func kotlinString(_ name: String) -> String? {
            guard let decl = kotlin.components(separatedBy: "const val \(name) =").dropFirst().first?
                .components(separatedBy: "\n    const val").first else { return nil }
            return decl.components(separatedBy: "\"").enumerated().filter { $0.offset % 2 == 1 }.map(\.element).joined()
        }
        XCTAssertEqual(kotlinString("EXPLAIN"), VaultRecoveryCopy.explain)
        XCTAssertEqual(kotlinString("TRY_AGAIN"), VaultRecoveryCopy.tryAgain)
        XCTAssertEqual(kotlinString("RETRY_FAILED"), VaultRecoveryCopy.retryFailed)
        for line in [VaultRecoveryCopy.explain, VaultRecoveryCopy.eraseWarning, VaultRecoveryCopy.retryFailed, VaultRecoveryCopy.tryAgain] {
            XCTAssertFalse(line.contains("\u{2014}") || line.contains("\u{2013}"), "no dash: \(line)") // em-dash-ok: the test asserts this dash is absent
        }
    }
}
