import Foundation

#if DEBUG
// D-235 (C4, iOS L1, decision 108): a proof surface, Debug builds only.
/// The end-to-end smoke the task asks for: version string, a real vault
/// round-trip through the real Keychain keystore, and the WrongKey
/// negative: a wrong key surfaces `StoreError.WrongKey` and never
/// silently re-keys (the original key still opens afterwards).
///
/// The iOS Simulator's Keychain is a real Keychain implementation, so this
/// proof is valid there; a device run additionally exercises the
/// ThisDeviceOnly accessibility class in situ and is recorded as pending
/// hardware/paid-account access.
enum VaultSmoke {
    struct Result: Equatable {
        var version: String
        var recordRoundTrip = false
        var wrongKeyRejected = false
        var originalKeyStillOpens = false
        var detail = ""
    }

    /// A keystore holding one FIXED key: the wrong-key adversary for step 3.
    final class FixedKeyKeystore: SkiptiKeystore {
        let key: Data
        init(key: Data) { self.key = key }
        func provision(key: Data) throws -> () {}
        func load() throws -> Data? { key }
        func destroy() throws -> () {}
    }

    static func run() -> Result {
        var r = Result(version: skiptiCoreVersion())
        let fm = FileManager.default
        let dir = fm.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("smoke", isDirectory: true)
        try? fm.createDirectory(at: dir, withIntermediateDirectories: true)
        let path = dir.appendingPathComponent("smoke-vault.db").path
        try? fm.removeItem(atPath: path) // start clean each smoke run

        do {
            // 1. First open provisions a fresh key through the Keychain.
            let vault = try VaultGate.open(path: path, keystore: KeychainKeystore())
            let record = makeRecord(nonce: 0x21)
            try vault.putRecord(record: record)
            try vault.kvSet(key: "cursor/personal", value: Data([1, 2, 3]))
            try vault.closeVault()

            // 2. Reopen through the SAME Keychain item: bytes identical.
            let reopened = try VaultGate.open(path: path, keystore: KeychainKeystore())
            let records = try reopened.allRecords()
            r.recordRoundTrip = try records.count == 1 &&
                records[0] == record &&
                reopened.kvGet(key: "cursor/personal") == Data([1, 2, 3])
            try reopened.closeVault()

            // 3. The negative: a keystore holding a DIFFERENT key must be
            //    refused as WrongKey, never a silent re-key.
            do {
                _ = try VaultGate.open(
                    path: path,
                    keystore: FixedKeyKeystore(key: Data(repeating: 0xAB, count: 32))
                )
                r.detail = "wrong key OPENED the vault: that must never happen"
                return r
            } catch StoreError.WrongKey {
                r.wrongKeyRejected = true
            }

            // 4. And the ORIGINAL key still opens afterwards (no re-key),
            //    with every byte where the first session left it.
            let third = try VaultGate.open(path: path, keystore: KeychainKeystore())
            r.originalKeyStillOpens = try third.allRecords().count == 1 &&
                third.allRecords()[0] == record &&
                third.kvGet(key: "cursor/personal") == Data([1, 2, 3])
            try third.closeVault()
        } catch {
            r.detail = "unexpected error: \(error)"
        }
        return r
    }

    private static func makeRecord(nonce: UInt8) -> RecordWire {
        RecordWire(
            version: 1,
            recordType: 1,
            groupId: nil,
            epoch: 0,
            hlcMillis: UInt64(Date().timeIntervalSince1970 * 1000),
            hlcCounter: 0,
            hlcDeviceId: String(repeating: "a", count: 31) + "1",
            authorDeviceId: String(repeating: "a", count: 31) + "1",
            nonceSession: Data([nonce, 0, 0, 1]),
            flags: 0,
            blob: Data([0xDE, 0xAD, 0xBE, 0xEF])
        )
    }
}
#endif
