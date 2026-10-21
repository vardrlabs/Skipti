import Foundation
import Security

/// The iOS Keychain implementation of the `SkiptiKeystore` contract D-42
/// defined and skrin depends on: provision/load/destroy of exactly one
/// 32-byte database key, accessible only when unlocked and never backed up
/// to iCloud (kSecAttrAccessibleWhenUnlockedThisDeviceOnly per §10.2).
///
/// There is deliberately no API to export the key anywhere except back into
/// memory through `load()`: the same shape as skrin's native trait, so
/// A-17 ("keys not present in backup") holds by construction.
final class KeychainKeystore: SkiptiKeystore {
    static let service = "com.vardrlabs.skipti.vaultkey"
    static let account = "database-key"

    private func query() -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: Self.service,
            kSecAttrAccount as String: Self.account,
        ]
    }

    /// Stores a fresh key. Called by skrin on first open with the key it
    /// generated; later opens read it back via `load()`.
    func provision(key: Data) throws -> () {
        guard key.count == 32 else {
            throw StoreError.InvalidInput("keystore key must be 32 bytes")
        }
        SecItemDelete(query() as CFDictionary) // idempotent replace
        var attributes = query()
        attributes[kSecValueData as String] = key
        attributes[kSecAttrAccessible as String] =
            kSecAttrAccessibleWhenUnlockedThisDeviceOnly
        let status = SecItemAdd(attributes as CFDictionary, nil)
        guard status == errSecSuccess else {
            throw StoreError.Keystore("SecItemAdd failed: \(status)")
        }
    }

    /// `nil` before first provisioning: skrin then generates a key and
    /// calls `provision`.
    func load() throws -> Data? {
        var item: CFTypeRef?
        var request = query()
        request[kSecReturnData as String] = true
        request[kSecMatchLimit as String] = kSecMatchLimitOne
        let status = SecItemCopyMatching(request as CFDictionary, &item)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess else {
            throw StoreError.Keystore("SecItemCopyMatching failed: \(status)")
        }
        guard let data = item as? Data else {
            throw StoreError.Keystore("keychain returned non-data item")
        }
        guard data.count == 32 else {
            throw StoreError.Keystore(
                "keychain held \(data.count) bytes, expected 32"
            )
        }
        return data
    }

    func destroy() throws -> () {
        let status = SecItemDelete(query() as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else {
            throw StoreError.Keystore("SecItemDelete failed: \(status)")
        }
    }
}
