import XCTest
@testable import Skipti

/// Incident §5.10's regression guard: concurrent opens of a FRESH vault
/// used to be able to interleave skrin's schema migration ("table
/// hlc_state already exists") and the Keychain key provisioning. Through
/// VaultGate every open is serialised, so concurrent first-opens all
/// succeed, and a write → close → reopen through the gate reads the
/// persisted bytes back.
final class VaultGateTests: XCTestCase {
    func testConcurrentFreshOpensAllSucceed() throws {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("vaultgate-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        let path = dir.appendingPathComponent("gate-vault.db").path

        let group = DispatchGroup()
        let errors = ErrorBox()
        let queue = DispatchQueue(label: "vaultgate.test", attributes: .concurrent)
        for _ in 0..<8 {
            group.enter()
            queue.async {
                defer { group.leave() }
                do {
                    let vault = try VaultGate.open(path: path, keystore: KeychainKeystore())
                    try vault.closeVault()
                } catch {
                    errors.add(error)
                }
            }
        }
        XCTAssertEqual(
            group.wait(timeout: .now() + 30), .success,
            "concurrent opens hung: the gate must never deadlock"
        )
        XCTAssertTrue(
            errors.all.isEmpty,
            "concurrent fresh opens must all succeed: \(errors.all)"
        )

        // The surviving vault is sane: write, close, reopen, read back.
        let vault = try VaultGate.open(path: path, keystore: KeychainKeystore())
        try vault.kvSet(key: "gate/probe", value: Data([7]))
        try vault.closeVault()
        let reopened = try VaultGate.open(path: path, keystore: KeychainKeystore())
        XCTAssertEqual(try reopened.kvGet(key: "gate/probe"), Data([7]))
        try reopened.closeVault()
    }
}

/// Thread-safe error collection for the concurrent opens.
private final class ErrorBox: @unchecked Sendable {
    private let queue = DispatchQueue(label: "vaultgate.errors")
    private var storage: [Error] = []
    var all: [Error] { queue.sync { storage } }
    func add(_ e: Error) { queue.sync { storage.append(e) } }
}

/// D-162 (SPEC §2.3 A7, §3.7): the vault's folder is excluded from every
/// backup, and the app's own vault location is excluded once it is used.
extension VaultGateTests {
    func testTheVaultFolderIsNeverBackedUp() throws {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("backup-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        XCTAssertFalse(VaultBackup.isExcluded(dir), "a new folder is backed up by default")
        VaultBackup.exclude(dir)
        XCTAssertTrue(VaultBackup.isExcluded(dir))
        // The app's own folder: cleared first (the flag outlives a run), then
        // the ordinary way of opening the vault must set it again.
        var vault = URL(fileURLWithPath: LedgerStore.defaultStore().path).deletingLastPathComponent()
        var cleared = URLResourceValues()
        cleared.isExcludedFromBackup = false
        try vault.setResourceValues(cleared)
        XCTAssertFalse(VaultBackup.isExcluded(vault))
        _ = LedgerStore.defaultStore()
        XCTAssertTrue(VaultBackup.isExcluded(vault), "the app's vault folder")
    }
}
