import FirebaseAuth
import Foundation
import Observation
import SwiftUI

/// D-191: the account is a Firebase Auth user. Every "is this phone signed
/// in" question reads `Account.shared.isSignedIn`; Firebase keeps the session
/// in the Keychain and refreshes it itself. Android's twin is Account.kt.
///
/// D-168's rule carries over: one account per phone. The phone remembers the
/// first Firebase uid that signed in on it (kept through sign-out), and a
/// sign-in by another uid is signed straight back out and refused. Only the
/// Firebase key counts: an owner from the old relay (`AccountOwner.key`)
/// belongs to an account that no longer exists and blocks nothing.
@Observable
@MainActor
final class Account {
    static let shared = Account()
    static let ownerKey = "account/firebase-uid"

    enum Outcome: Equatable {
        case signedIn
        /// Another account's ledger is on this phone: nothing was kept.
        case otherAccount
        case failed(String)
    }

    private(set) var uid: String?
    var isSignedIn: Bool { uid != nil }
    private var listener: AuthStateDidChangeListenerHandle?

    /// D-235 round 5 (decision 116): no Firebase call until Firebase is configured (`FirebaseGate`); before that
    /// there is no account. The gate attaches the account right after it configures.
    private init() {
        if FirebaseGate.accountAttaches { attachToFirebase() }
    }

    /// Reads the session and listens for changes, once, after `FirebaseGate.configure`. D-235 final wave (I-1): never
    /// while Firebase is configured only to settle an owed token delete.
    func attachToFirebase() {
        guard listener == nil, FirebaseGate.accountAttaches, let auth = FirebaseGate.auth else { return }
        uid = auth.currentUser?.uid
        // Firebase calls this on the main thread: a sign-out elsewhere (a
        // revoked session) reaches every screen.
        listener = auth.addStateDidChangeListener { [weak self] _, user in
            MainActor.assumeIsolated {
                self?.uid = user?.uid
                // Decision 117: a session ended anywhere (here, another phone, a revoked or deleted account) means the
                // next launch makes no Firebase call.
                if user == nil { Account.setSignedInMark(.defaultStore(), false) }
            }
        }
    }

    /// D-235 round 5 (decision 117): whether this install is signed in, kept in the vault: set by a successful sign-in,
    /// cleared by every sign-out. A signed-out phone keeps its owner key (D-168), so the owner alone would configure
    /// Firebase, and make its lookups, at every launch with nobody signed in. "1" signed in, "0" signed out, absent:
    /// never written (an install from before this build, signed in or not: every launch configures, as before this
    /// build, until a sign-out writes "0"; only a sign-in writes "1", D-235 final review minor 6).
    nonisolated static let signedInKey = "account/signed-in"

    nonisolated static func signedInMark(_ store: LedgerStore) -> Bool? {
        guard let data = try? store.kvGet(key: signedInKey), !data.isEmpty else { return nil }
        return data == Data("1".utf8)
    }

    nonisolated static func setSignedInMark(_ store: LedgerStore, _ on: Bool) {
        try? store.kvSet(key: signedInKey, value: Data((on ? "1" : "0").utf8))
    }

    /// D-235 round 5: what the vault says of its owner, for the gate (`.failed` when it cannot be read).
    nonisolated static func ownerRead(_ store: LedgerStore) -> InstallSession.OwnerRead {
        do { return .read(try readOwner(store)) } catch { return .failed }
    }

    /// The pure rule: no owner yet, or the same account.
    nonisolated static func owns(stored: String?, incoming: String) -> Bool {
        stored == nil || stored == incoming
    }

    /// The vault's owner, nil when none. For the cloud gates, which fail closed: a vault that cannot be read reads as no
    /// owner, so nothing syncs. Never for a decision that would sign someone out or write the owner (`readOwner`).
    nonisolated static func storedOwner(_ store: LedgerStore) -> String? {
        try? readOwner(store)
    }

    /// D-235 round 4 (shells review M4): the vault's owner, throwing when the vault cannot be read (CLAUDE.md 10.1: a
    /// `try?` on a vault read turns "cannot read" into "empty").
    nonisolated static func readOwner(_ store: LedgerStore) throws -> String? {
        guard let data = try store.kvGet(key: ownerKey), !data.isEmpty else { return nil }
        return String(data: data, encoding: .utf8)
    }

    /// How this phone signed in (D-172), for Settings.
    func method(_ store: LedgerStore = .defaultStore()) -> SignInMethod? {
        isSignedIn ? SignInMethod.stored(store) : nil
    }

    func signIn(
        _ credential: AuthCredential, method: SignInMethod, store: LedgerStore = .defaultStore()
    ) async -> Outcome {
        // D-235 round 5 (decision 116): a sign-in is where an install without an account first configures Firebase
        // (the tap already did; idempotent), and a session left in the keychain is signed out before this one.
        FirebaseGate.configure(owner: Self.ownerRead(store))
        let incoming: String
        do {
            incoming = try await Auth.auth().signIn(with: credential).user.uid
        } catch {
            return .failed(error.localizedDescription)
        }
        // D-235 round 4 (M4): a vault that cannot be read is a failed sign-in, never "no owner yet" (which would
        // write this account over the real owner).
        let owner: String?
        do {
            owner = try Self.readOwner(store)
        } catch {
            signOut()
            return .failed(String(localized: "Signed in, but this phone could not save it. Try again."))
        }
        guard Self.owns(stored: owner, incoming: incoming) else {
            signOut()
            return .otherAccount
        }
        do {
            if owner == nil { try store.kvSet(key: Self.ownerKey, value: Data(incoming.utf8)) }
            try SignInMethod.remember(method, store)
        } catch {
            // The vault could not keep it: not signed in, rather than signed
            // in to a phone that does not know whose it is.
            signOut()
            return .failed(String(localized: "Signed in, but this phone could not save it. Try again."))
        }
        uid = incoming
        // Decision 117: the next launches configure Firebase again.
        Self.setSignedInMark(store, true)
        // D-193/D-194: register this phone, upload the ledger, start listening.
        CloudSync.shared.appIsActive(true)
        return .signedIn
    }

    func signOut() {
        // D-235 round 5: never configured means never signed in on this launch: nothing to sign out of.
        try? FirebaseGate.auth?.signOut()
        // Decision 117: the next launch makes no Firebase call.
        Self.setSignedInMark(.defaultStore(), false)
        // D-244 (M5): a later sign-in catches up with the account again.
        SettingsSync.signedOut(.defaultStore(), uid: uid)
        uid = nil
        CloudSync.shared.accountSignedOut()
    }
}

/// D-235 (C2, decision 107; iOS audit H2, cross audit H1): iOS keeps Firebase Auth's session in the keychain, and the
/// keychain outlives an uninstall, so a reinstalled SKIPTI used to start signed in, and CloudSync registered the phone
/// and synced the account without a tap (GUARANTEES #5), on a fresh vault with no owner (D-168's one-account rule off).
/// An install marker in UserDefaults (which an uninstall clears, as `AppearanceDefault`'s settled mark relies on) tells
/// the first launch of a new install; that launch signs out a session it finds when no vault exists yet. Once the vault
/// opens, a session it was never signed in to is signed out too (`signsOutAfterOpen`: a restore, where UserDefaults
/// came back and the vault did not). Android's session lives in app data, wiped on uninstall: nothing to do there.
enum InstallSession {
    static let markerKey = "install/marker"
    /// D-235 round 5 (decision 116): set on a new install's first launch, cleared right after the first configure.
    /// Firebase is no longer configured at launch without an account, so the sign-out the marker decides waits for the
    /// configure (a sign-in tap, or a launch with an owner), and runs there before anything can sync.
    static let pendingSignOutKey = "install/sign-out-at-configure"

    /// The first launch of an install with no vault yet: any session the keychain holds was left by an earlier
    /// install. An install that already has a vault on its first launch of this build is an update: nothing pends.
    static func pendsSignOut(markerPresent: Bool, vaultExists: Bool) -> Bool {
        !markerPresent && !vaultExists
    }

    /// Once per install, at launch, before anything can act on the account (nothing Firebase here): marks a sign-out
    /// for the first configure, then sets the marker. Only whether the vault file exists is asked; nothing opens it.
    static func settle(_ defaults: UserDefaults = .standard, vaultPath: String = AppearanceDefault.defaultVaultPath()) {
        let marker = defaults.bool(forKey: markerKey)
        if pendsSignOut(markerPresent: marker, vaultExists: FileManager.default.fileExists(atPath: vaultPath)) {
            defaults.set(true, forKey: pendingSignOutKey)
        }
        if !marker { defaults.set(true, forKey: markerKey) }
    }

    static func pendingSignOut(_ defaults: UserDefaults = .standard) -> Bool { defaults.bool(forKey: pendingSignOutKey) }

    static func clearPendingSignOut(_ defaults: UserDefaults = .standard) { defaults.removeObject(forKey: pendingSignOutKey) }

    /// D-235 round 4 (M4): what the read of the owner came to.
    enum OwnerRead: Equatable {
        case read(String?)
        case failed
    }

    /// Right after configure (`FirebaseGate`): a session the keychain holds is signed out when a new install's first
    /// launch marked one (`pending`), or when it is not the vault's owner (no owner, or another account). A vault
    /// that could not be read signs nobody out (round 4, M4: the cloud gate fails closed instead).
    static func signsOutAfterConfigure(pending: Bool, currentUid: String?, owner: OwnerRead) -> Bool {
        guard let currentUid else { return false }
        if pending { return true }
        guard case .read(let stored) = owner else { return false }
        return stored != currentUid
    }

    /// The cloud gate (CloudSync, phone registration, push, the profile share): the signed-in account, only when it
    /// is the vault's owner.
    static func owned(uid: String?, owner: String?) -> String? {
        guard let uid, owner == uid else { return nil }
        return uid
    }
}

#if DEBUG
/// D-235 (C4, iOS L1, decision 108): the proof screens below are Debug builds only.
/// `--auth-emulator-proof` (D-191, headless verification, not a user flow):
/// points Firebase Auth at the local emulator (`firebase emulators:start
/// --only auth --project demo-skipti`, port 9099), signs in with an
/// emulator-only Google ID token, then signs out, through the same
/// `Account.signIn` the sign-in page uses. The emulator accepts an unsigned
/// token; production never sees this path (a demo- project, a local host).
struct AuthEmulatorProofScreen: View {
    @State private var lines: [String] = []

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(verbatim: "Firebase Auth, against the emulator").font(TypeRole.label(17))
            ForEach(Array(lines.enumerated()), id: \.offset) { _, line in
                Text(verbatim: line).font(TypeRole.body(14))
            }
        }
        .padding(20)
        .task {
            // D-193: Firestore's emulator too (port 8180, the repo's rules). D-235 round 5: configured through the
            // gate, which points Auth at the emulator.
            Cloud.emulator = true
            FirebaseGate.configure(owner: .failed)
            let account = Account.shared
            account.signOut()
            let token = #"{"sub":"proof-a","email":"a@example.test","email_verified":true}"#
            let outcome = await account.signIn(
                GoogleAuthProvider.credential(withIDToken: token, accessToken: ""), method: .google
            )
            lines.append("outcome: \(outcome)")
            lines.append("uid: \(account.uid ?? "none")")
            lines.append("method: \(account.method()?.label ?? "none")")
            if let uid = account.uid { await proveProfile(uid) }
            account.signOut()
            lines.append("after sign-out: \(account.isSignedIn ? "signed in" : "signed out")")
        }
    }

    /// The sign-in registered the profile and this phone (CloudSync); read
    /// them back, then show the rules refusing a phone that marks itself
    /// signed out.
    private func proveProfile(_ uid: String) async {
        let store = LedgerStore.defaultStore()
        guard let device = try? CloudProfile.deviceId(store) else { return lines.append("no device id") }
        let user = Cloud.db.collection("users").document(uid)
        let phone = user.collection("devices").document(device)
        for _ in 0..<50 {
            if (try? await phone.getDocument(source: .server).exists) == true { break }
            try? await Task.sleep(nanoseconds: 100_000_000)
        }
        let profile = try? await user.getDocument(source: .server).data()
        let entry = try? await phone.getDocument(source: .server).data()
        let you = (try? store.loadSelf())?.id
        lines.append("profile participantId is you: \((profile?["participantId"] as? String) == you)")
        lines.append("profile keys: \(profile.map { $0.keys.sorted().joined(separator: ",") } ?? "none")")
        lines.append("device: \(entry?["name"] as? String ?? "none"), \(entry?["platform"] as? String ?? "none"), \(entry?["appVersion"] as? String ?? "none")")
        do {
            try await phone.updateData(["signedOut": false])
            lines.append("signedOut write: ALLOWED (wrong)")
        } catch {
            lines.append("signedOut write: refused")
        }
    }
}

/// `--sync-emulator-proof` (D-194, headless verification, not a user flow):
/// two phones of ONE account converge. Run it on the iOS simulator and the
/// Android emulator at once, both against the local Auth and Firestore
/// emulators: each signs in as the same emulator account, makes a group with
/// one expense, and waits for the other phone's group and expense to arrive
/// through Firestore. Android's twin is SyncEmulatorProof.
struct SyncEmulatorProofScreen: View {
    @State private var lines: [String] = []
    private let me = "iOS"
    private let other = "Android"

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(verbatim: "Two phones, one account, against the emulator").font(TypeRole.label(17))
            ForEach(Array(lines.enumerated()), id: \.offset) { _, line in
                Text(verbatim: line).font(TypeRole.body(14))
            }
        }
        .padding(20)
        .task {
            Cloud.emulator = true
            FirebaseGate.configure(owner: .failed)
            let account = Account.shared
            account.signOut()
            let token = #"{"sub":"proof-sync","email":"s@example.test","email_verified":true}"#
            let outcome = await account.signIn(
                GoogleAuthProvider.credential(withIDToken: token, accessToken: ""), method: .google
            )
            lines.append("signed in: \(outcome), uid \(account.uid ?? "none")")
            let store = LedgerStore.defaultStore()
            do {
                _ = try store.setYourName("Sam on \(me)")
                let group = try store.createGroup(name: "From \(me)", settlementCurrency: "USD", people: [])
                let you = group.selfId
                _ = try store.appendExpense(LedgerStore.ExpenseDTO(
                    payer: you, paidMinor: 1_200, currencyCode: "USD", group: group.group.id, conversion: nil,
                    shares: [.init(id: you, amountMinor: 1_200)], note: "Dinner from \(me)",
                    timestampMs: UInt64(Date().timeIntervalSince1970 * 1000), category: nil, addedBy: you
                ))
                CloudSync.shared.ledgerChanged()
                lines.append("made: group From \(me), expense Dinner from \(me)")
            } catch {
                lines.append("could not make the group: \(error)")
                return
            }
            let started = Date()
            var seen = false
            while Date().timeIntervalSince(started) < 120 {
                if (try? store.loadExpenses())?.contains(where: { $0.note == "Dinner from \(other)" }) == true {
                    seen = true
                    break
                }
                try? await Task.sleep(nanoseconds: 500_000_000)
            }
            let groups = ((try? store.loadGroups()) ?? []).map(\.name).sorted()
            let expenses = (try? store.loadExpenses()) ?? []
            let you = (try? store.loadSelf())?.id
            let theirs = expenses.first { $0.note == "Dinner from \(other)" }
            lines.append("pushed (last push): \(CloudSync.shared.lastPushed); received: \(CloudSync.shared.receivedCount)")
            lines.append("groups here: \(groups.joined(separator: ", "))")
            lines.append("\(other)'s expense here: \(seen ? "yes" : "NO") after \(Int(Date().timeIntervalSince(started))) s")
            lines.append("\(other)'s payer reads as you: \(theirs.map { $0.payer == you } ?? false)")
            lines.append("expenses here: \(expenses.compactMap(\.note).sorted().joined(separator: ", "))")
        }
    }
}

/// `--two-account-proof` (D-195, headless verification): the test the relay
/// never passed. This phone is Al (one emulator account); it makes "Goa trip"
/// with Bo in it, adds Dinner, and invites Bo; the link is printed (on screen
/// and in the log as `INVITE-LINK`). Android, signed in as Bo (ANOTHER
/// account), joins with that link, adds Taxi and edits Dinner's note; this
/// phone must show both. Android's twin of the other half is TwoAccountProof.
struct TwoAccountProofScreen: View {
    @State private var lines: [String] = []

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 8) {
                Text(verbatim: "Two accounts: Al (this phone) and Bo").font(TypeRole.label(17))
                ForEach(Array(lines.enumerated()), id: \.offset) { _, line in
                    Text(verbatim: line).font(TypeRole.body(13))
                }
            }
            .padding(20)
        }
        .task { await run() }
    }

    private func run() async {
        Cloud.emulator = true
        FirebaseGate.configure(owner: .failed)
        let account = Account.shared
        account.signOut()
        let token = #"{"sub":"proof-al","email":"al@example.test","email_verified":true}"#
        let outcome = await account.signIn(GoogleAuthProvider.credential(withIDToken: token, accessToken: ""), method: .google)
        lines.append("Al signed in: \(outcome), uid \(account.uid ?? "none")")
        let store = LedgerStore.defaultStore()
        let link: String
        do {
            _ = try store.setYourName("Al")
            let group = try store.createGroup(name: "Goa trip", settlementCurrency: "USD", people: ["Bo"])
            let al = group.selfId
            guard let bo = group.members.first(where: { $0.id != al }) else { return lines.append("no Bo") }
            _ = try store.appendExpense(LedgerStore.ExpenseDTO(
                payer: al, paidMinor: 4_000, currencyCode: "USD", group: group.group.id, conversion: nil,
                shares: [.init(id: al, amountMinor: 2_000), .init(id: bo.id, amountMinor: 2_000)], note: "Dinner",
                timestampMs: UInt64(Date().timeIntervalSince1970 * 1000), category: nil, addedBy: al
            ))
            CloudSync.shared.ledgerChanged()
            link = try await Invites.create(store: store, group: group, member: bo)
        } catch {
            return lines.append("could not invite: \(error)")
        }
        NSLog("INVITE-LINK %@", link)
        lines.append("invite: \(link)")
        let started = Date()
        var taxi = false
        var edited = false
        while Date().timeIntervalSince(started) < 300 {
            let notes = ((try? store.loadExpenses()) ?? []).compactMap(\.note)
            taxi = notes.contains("Taxi from Bo")
            edited = notes.contains("Dinner, edited by Bo")
            if taxi && edited { break }
            try? await Task.sleep(nanoseconds: 500_000_000)
        }
        let claims = (try? LedgerStore.Sync.projectGroups(store).groups.first?.claims.count) ?? 0
        lines.append("Bo joined (claims in the group): \(claims)")
        lines.append("Bo's Taxi here: \(taxi ? "yes" : "NO"); Bo's edit of Dinner here: \(edited ? "yes" : "NO") after \(Int(Date().timeIntervalSince(started))) s")
        lines.append("expenses here: \(((try? store.loadExpenses()) ?? []).compactMap(\.note).sorted().joined(separator: ", "))")
        NSLog("TWO-ACCOUNT-RESULT taxi=%@ edited=%@", taxi ? "yes" : "no", edited ? "yes" : "no")
    }
}

/// `--settings-emulator-proof a|b` (D-244, headless verification, not a user flow): settings follow ONE account
/// across two phones, against the local Auth and Firestore emulators. Run it on the iOS simulator and the Android
/// emulator at once, one phone `a` and the other `b` (then the other way round); both sign in as the emulator account
/// named after phone a. Phone a first shows that without an account a change writes no record (GUARANTEES #5), then
/// signs in, catches up, and sets Norse and EUR through the funnel's write, which go up. Phone b signs in and waits:
/// it must apply Norse and EUR, write no setting record of its own (no echo), and leave the icon pending (never
/// switched at arrival) for its front moment, which a proof never has: the look shows that moment. Lines go to the
/// screen and to the log as `SETTINGS-PROOF`. Android's twin is SettingsEmulatorProof.
struct SettingsEmulatorProofScreen: View {
    @State private var lines: [String] = []
    private let me = "iOS"
    private let other = "Android"

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 8) {
                Text(verbatim: "Settings follow the account, against the emulator").font(TypeRole.label(17))
                ForEach(Array(lines.enumerated()), id: \.offset) { _, line in
                    Text(verbatim: line).font(TypeRole.body(13))
                }
            }
            .padding(20)
        }
        .task { await run() }
    }

    private func say(_ line: String) {
        lines.append(line)
        NSLog("SETTINGS-PROOF %@", line)
    }

    /// Every setting record in this vault (the log, not the projection's winners), with its device.
    private static func settingRecords(_ store: LedgerStore) -> [(key: String, value: String, device: String, id: String)] {
        ((try? store.allRecords()) ?? []).compactMap { record in
            guard CloudRecords.settingKey(record) != nil,
                  case .setting(let key, let value)? = try? ffiDecodeGroupOp(blob: record.blob) else { return nil }
            return (key, value, record.hlcDeviceId, CloudRecords.documentId(record))
        }
    }

    private static func describe(_ records: [(key: String, value: String, device: String, id: String)]) -> String {
        records.isEmpty ? "none" : records.map { "\($0.key)=\($0.value)" }.joined(separator: ", ")
    }

    private func run() async {
        let args = ProcessInfo.processInfo.arguments
        let role = args.firstIndex(of: "--settings-emulator-proof").flatMap { args.indices.contains($0 + 1) ? args[$0 + 1] : nil }
        let writer = role == "b" ? other : me
        let sub = "proof-settings-" + writer.lowercased()
        Cloud.emulator = true
        FirebaseGate.configure(owner: .failed)
        let account = Account.shared
        account.signOut()
        let store = LedgerStore.defaultStore()
        let defaults = UserDefaults.standard
        let device = (try? LedgerStore.Sync.deviceId(store)) ?? "unknown"
        func mine() -> [(key: String, value: String, device: String, id: String)] {
            Self.settingRecords(store).filter { $0.device == device }
        }
        say("phone \(role == "b" ? "b" : "a") (\(me)), account \(sub), device \(device.prefix(8))")
        if role != "b" {
            // GUARANTEES #5: signed out, a change is stored here and writes no record.
            let before = mine().count
            SettingsSync.write(AppTheme.storageKey, AppTheme.vardr.rawValue, store: store, defaults: defaults, uid: account.uid)
            say("no account: theme here \(defaults.string(forKey: AppTheme.storageKey) ?? "none"); setting records written: \(mine().count - before) (want 0)")
        }
        let token = #"{"sub":"\#(sub)","email":"\#(sub)@example.test","email_verified":true}"#
        let outcome = await account.signIn(GoogleAuthProvider.credential(withIDToken: token, accessToken: ""), method: .google)
        say("signed in: \(outcome), uid \(account.uid ?? "none")")
        guard let uid = account.uid else { return say("NOT signed in") }
        let started = Date()
        while !SettingsSync.isSeeded(store, uid: uid), Date().timeIntervalSince(started) < 60 {
            try? await Task.sleep(nanoseconds: 250_000_000)
        }
        say("caught up with the account (seeded): \(SettingsSync.isSeeded(store, uid: uid) ? "yes" : "NO") after \(Int(Date().timeIntervalSince(started))) s; my setting records: \(Self.describe(mine()))")
        if role != "b" {
            SettingsSync.write(AppTheme.storageKey, AppTheme.norse.rawValue, store: store, defaults: defaults, uid: uid)
            SettingsSync.write(DefaultCurrency.storageKey, "EUR", store: store, defaults: defaults, uid: uid)
            CloudSync.shared.ledgerChanged()
            say("set: theme norse, currency EUR; my setting records: \(Self.describe(mine()))")
            let pushStart = Date()
            var up = false
            while Date().timeIntervalSince(pushStart) < 60 {
                let uploaded = (try? CloudRecords.uploaded(store)) ?? []
                if !mine().isEmpty, mine().allSatisfy({ uploaded.contains($0.id) }) { up = true; break }
                try? await Task.sleep(nanoseconds: 250_000_000)
            }
            say("my setting records up: \(up ? "yes" : "NO") after \(Int(Date().timeIntervalSince(pushStart))) s (last push \(CloudSync.shared.lastPushed))")
            say("theme here \(defaults.string(forKey: AppTheme.storageKey) ?? "none"), currency here \(defaults.string(forKey: DefaultCurrency.storageKey) ?? "none")")
            return
        }
        let shownBefore = SettingsSync.shownIcon()
        let waitStart = Date()
        var arrived = false
        while Date().timeIntervalSince(waitStart) < 180 {
            if defaults.string(forKey: AppTheme.storageKey) == AppTheme.norse.rawValue,
               defaults.string(forKey: DefaultCurrency.storageKey) == "EUR" { arrived = true; break }
            try? await Task.sleep(nanoseconds: 250_000_000)
        }
        say("applied from \(other): \(arrived ? "yes" : "NO") after \(Int(Date().timeIntervalSince(waitStart))) s (received \(CloudSync.shared.receivedCount))")
        // Give an echo time to happen, then count.
        try? await Task.sleep(nanoseconds: 8_000_000_000)
        let all = Self.settingRecords(store)
        say("theme here \(defaults.string(forKey: AppTheme.storageKey) ?? "none"), currency here \(defaults.string(forKey: DefaultCurrency.storageKey) ?? "none")")
        say("setting records here: \(all.count) (from \(other): \(Self.describe(all.filter { $0.device != device })))")
        say("my own setting records (echo): \(mine().count) (want 0) \(Self.describe(mine()))")
        say("applied marks: \(((try? SettingsSync.applied(store)) ?? [:]).keys.sorted().joined(separator: ", "))")
        let shownNow = SettingsSync.shownIcon()
        say("icon shown before \(shownBefore?.rawValue ?? "none"), now \(shownNow?.rawValue ?? "none") (unchanged at arrival); pending: \(SettingsSync.iconIsPending(store) ? "yes" : "NO")")
        let target = AppIconPlan.target(AppIconChoice.parse(defaults.string(forKey: AppIconChoice.storageKey)),
                                        theme: AppTheme.parse(defaults.string(forKey: AppTheme.storageKey)))
        say("at the front moment (in front, unlocked): changes \(SettingsSync.frontApplies(pending: SettingsSync.iconIsPending(store), locked: false, active: true) ? "yes" : "no") to \(target.rawValue); in the background or locked: \(SettingsSync.frontApplies(pending: SettingsSync.iconIsPending(store), locked: true, active: true) || SettingsSync.frontApplies(pending: SettingsSync.iconIsPending(store), locked: false, active: false) ? "changes (wrong)" : "waits")")
    }
}
#endif
