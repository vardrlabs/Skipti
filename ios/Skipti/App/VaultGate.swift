import Foundation

/// Every `SkiptiVault.open` in the app funnels through this gate. Open is
/// the only place skrin runs the schema migration (a user_version
/// read-then-write) and provisions the database key (load-nil → generate →
/// provision), and neither window interleaves safely with itself: two
/// concurrent opens of a FRESH vault can both read user_version 0 and both
/// run schema_v1.sql: the loser dies on "table hlc_state already exists"
/// (incident §5.10), and two concurrent first-opens can both see a
/// missing Keychain key and provision different database keys, stranding
/// the vault behind a key nobody holds. The gate holds one process-wide
/// lock across the whole open, so no two opens in this process can
/// interleave those windows. After open returns, the vault's own
/// BEGIN IMMEDIATE transactions guard concurrent use of live handles.
enum VaultGate {
    private static let lock = NSLock()

    static func open(path: String, keystore: SkiptiKeystore) throws -> SkiptiVault {
        lock.lock()
        defer { lock.unlock() }
        return try SkiptiVault.open(path: path, keystore: keystore)
    }
}
