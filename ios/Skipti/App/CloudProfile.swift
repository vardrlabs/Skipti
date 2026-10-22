import FirebaseFirestore
import Foundation
import UIKit

/// D-193 (the Firebase plan's unit 3): this account's profile and this phone's
/// entry in the cloud, written at sign-in and when SKIPTI comes to the front.
/// `users/{uid}` holds only the account's "you" id; `users/{uid}/devices/{id}`
/// names this phone for the account's phone list (unit 8). What each document
/// may hold is fixed by `firebase/firestore.rules`, and the tests compare
/// these field lists with the rules' own. Android's twin is CloudProfile.kt.
enum CloudProfile {
    // This phone's id in the cloud is its records' device id (D-194: one id
    // per phone; D-193 had minted a second one): 16 random bytes from core,
    // made once and kept in the vault, so a reinstall is a new phone.

    /// Exactly the keys the rules allow, in the rules' order.
    static let profileFields = ["participantId", "createdAt", "updatedAt", "name"]
    static let deviceCreateFields = ["name", "platform", "appVersion", "lastSeen", "fcmToken"]
    static let deviceUpdateFields = ["name", "appVersion", "lastSeen", "fcmToken"]

    enum Outcome: Equatable {
        case registered
        case failed(String)
        /// D-198: this phone was signed out from elsewhere since it signed in.
        case signedOutRemotely
    }

    static func deviceId(_ store: LedgerStore) throws -> String {
        try LedgerStore.Sync.deviceId(store)
    }

    /// D-197: `name` is the name people see when they find you (your name in
    /// SKIPTI), sent when you have one.
    static func profileCreate(participantId: String, name: String? = nil) -> [String: Any] {
        var out: [String: Any] = ["participantId": participantId, "createdAt": FieldValue.serverTimestamp(),
                                  "updatedAt": FieldValue.serverTimestamp()]
        if let name = cloudName(name) { out["name"] = name }
        return out
    }

    static func profileUpdate(name: String? = nil) -> [String: Any] {
        var out: [String: Any] = ["updatedAt": FieldValue.serverTimestamp()]
        if let name = cloudName(name) { out["name"] = name }
        return out
    }

    /// The rules' limit: 1 to 60 characters, as the rules' `string.size()`
    /// counts them. Counted here in UTF-16 code units (never fewer than the
    /// rules count), cut at a whole character, so the cut name always passes.
    static func cloudName(_ name: String?) -> String? {
        let trimmed = (name ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        var cut = ""
        for character in trimmed {
            guard cut.utf16.count + String(character).utf16.count <= 60 else { break }
            cut.append(character)
        }
        return cut.isEmpty ? nil : cut
    }

    enum NameError: Error, Equatable {
        /// `users/{uid}` does not exist yet: sign-in's registration makes it.
        case noProfile
    }

    /// D-211: the account's public name (`users/{uid}.name`, what find-people
    /// shows and what `shareProfile` checks every record's name against) made
    /// the name on this phone. Saving a name used to reach it only when
    /// registration next ran; sharing a profile needs it now. Reads the server's
    /// copy, writes only when it differs, and throws when the cloud cannot be
    /// reached or the profile is not there yet.
    @MainActor
    static func ensureName(uid: String, name: String) async throws {
        guard let wanted = cloudName(name) else { return }
        let user = Cloud.db.collection("users").document(uid)
        let snapshot = try await user.getDocument(source: .server)
        guard snapshot.exists else { throw NameError.noProfile }
        if snapshot.data()?["name"] as? String != wanted {
            try await user.updateData(profileUpdate(name: wanted))
        }
    }

    static func deviceCreate(name: String, appVersion: String) -> [String: Any] {
        ["name": name, "platform": "ios", "appVersion": appVersion, "lastSeen": FieldValue.serverTimestamp()]
    }

    static func deviceUpdate(name: String, appVersion: String) -> [String: Any] {
        ["name": name, "appVersion": appVersion, "lastSeen": FieldValue.serverTimestamp()]
    }

    /// D-196: this phone's push address (FCM's token), or its removal. The
    /// rules want `lastSeen` refreshed with any change.
    static func tokenUpdate(_ token: String?) -> [String: Any] {
        ["fcmToken": token.map { $0 as Any } ?? FieldValue.delete(), "lastSeen": FieldValue.serverTimestamp()]
    }

    /// "iPhone" or "iPad": iOS gives apps no more than the model's kind.
    @MainActor static var deviceName: String { UIDevice.current.model }

    static var appVersion: String {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0"
    }

    /// Creates the profile and this phone's entry, or refreshes them. A
    /// failure (offline) is retried the next time SKIPTI comes to the front.
    @MainActor
    static func register(uid: String, store: LedgerStore = .defaultStore()) async -> Outcome {
        do {
            let me = try store.ensureSelf()
            let participantId = me.id
            let device = try deviceId(store)
            let user = Cloud.db.collection("users").document(uid)
            if try await user.getDocument(source: .server).exists {
                try await user.updateData(profileUpdate(name: me.name))
            } else {
                try await user.setData(profileCreate(participantId: participantId, name: me.name))
            }
            let phone = user.collection("devices").document(device)
            let existing = try await phone.getDocument(source: .server)
            // D-198: a phone signed out from elsewhere signs itself out; a flag
            // older than this sign-in is from before it signed in again, and
            // its entry is made fresh (no phone can clear the flag itself).
            if existing.exists, existing.data()?["signedOut"] as? Bool == true {
                if Phones.signedOutSince(existing.data(), authTimeMs: await Phones.authTimeMs()) {
                    return .signedOutRemotely
                }
                try await phone.delete()
                try await phone.setData(deviceCreate(name: deviceName, appVersion: appVersion))
            } else if existing.exists {
                try await phone.updateData(deviceUpdate(name: deviceName, appVersion: appVersion))
            } else {
                try await phone.setData(deviceCreate(name: deviceName, appVersion: appVersion))
            }
            return .registered
        } catch {
            return .failed(error.localizedDescription)
        }
    }
}

/// The one Firestore instance. Its cache lives in memory only: the vault
/// (SQLCipher) is the phone's database, and a disk cache would keep cloud
/// documents outside it, unencrypted by SKIPTI.
enum Cloud {
    @MainActor static let db: Firestore = {
        // D-235 round 5: every caller has an account (so Firebase is configured); the gate is the safety net.
        FirebaseGate.configure(owner: .failed)
        let db = Firestore.firestore()
        let settings = db.settings
        settings.cacheSettings = MemoryCacheSettings()
        if emulator {
            settings.host = "localhost:8180"
            settings.isSSLEnabled = false
        }
        db.settings = settings
        return db
    }()

    #if DEBUG
    /// Set by the emulator proof before the first use of `db`.
    @MainActor static var emulator = false
    #else
    /// D-260 (G-13, decision 269): no emulator outside Debug builds. A constant, so every branch on it folds away.
    static let emulator = false
    #endif
}
