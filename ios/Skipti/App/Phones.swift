import FirebaseAuth
import FirebaseFirestore
import SwiftUI

/// D-198 (the Firebase plan's unit 8): your phones and deleting your account.
/// SPEC §3.5: the phone list is a screen of its own. SPEC §4.4: one deletion
/// screen, two taps from Settings, export first, a 7-day grace in which
/// signing in keeps the account, then irreversible. Firebase cannot end
/// another phone's session by itself, so a phone signed out from elsewhere
/// signs itself out when it next reaches the cloud; "Sign out everywhere" also
/// revokes every session. Android's twin is Phones.kt.
enum PhonesCopy {
    static let title = "Your phones"
    static let thisPhone = "This phone"
    static let signedOut = "Signed out"
    static func lastSeen(_ when: String) -> String { "Last seen \(when)" }
    /// D-222 (L58): You's phone count.
    static func count(_ n: Int) -> String { n == 1 ? "1 phone" : "\(n) phones" }
    static let signItOut = "Sign it out"
    static func confirmTitle(_ name: String) -> String { "Sign out \(name)?" }
    static let confirmBody = "It stops syncing and signs itself out the next time it goes online. Its ledger stays on it."
    static let everywhere = "Sign out everywhere"
    static let everywhereTitle = "Sign out every phone?"
    static let everywhereBody =
        "Every phone on your account, this one too, is signed out. Each keeps its ledger. Use this if a phone is lost or stolen."
    static let limit = "A phone you sign out stops syncing the next time it goes online. If a phone is lost, sign out everywhere."
    static let empty = "No phones yet."
    /// D-225 follow-up (R2): a listen that failed, in the cloud surfaces' words ("Could not load groups.", the invite's
    /// "Check your connection and try again."), with their "Try again". Android's `LOAD_FAILED` and `TRY_AGAIN`.
    static let loadFailed = "Could not load your phones. Check your connection and try again."
    static let tryAgain = "Try again"
    static let remoteTitle = "Signed out"
    static let remoteBody = "This phone was signed out of your account from another phone. Your ledger stays here."
    static let cancel = "Cancel"
    static let ok = "OK"

    static let deleteTitle = "Delete account"
    static let deleteIntro =
        "Deleting your account removes it from SKIPTI's cloud: your profile, username, phones and personal records. You leave every group; what you added to a group stays for its other members. Groups where you are the only member are deleted."
    static let deleteStays = "Your ledger stays on this phone."
    static let deleteGrace =
        "Deletion happens 7 days after you ask. To keep your account, sign in before then and tap Keep my account. After that it cannot be undone."
    static let exportFirst = "Export your data first"
    static let deleteButton = "Delete my account"
    static let deleteConfirmTitle = "Delete your account?"
    static let deleteConfirmBody = "Every phone is signed out now, and your account is deleted in 7 days."
    static let deleteDoneTitle = "Account deletion requested"
    static func deleteDone(_ date: String) -> String {
        "Your account will be deleted on \(date). To keep it, sign in before then and tap Keep my account."
    }
    static func pending(_ date: String) -> String { "Your account will be deleted on \(date)." }
    static let keep = "Keep my account"
}

/// D-225 (Design 4): the Your phones row's summary on Security: the account's phones' names, this phone first, the
/// phones signed out left out, joined with ", "; nil when none is left (the row then shows no summary, never an empty
/// one). It reads the rows the Your phones page already makes. Android's twin is `PhonesSummary` in Phones.kt.
enum PhonesSummary {
    static func line(rows: [Phones.Row]) -> String? {
        let active = rows.filter { !$0.signedOut }
        let names = (active.filter(\.isThis) + active.filter { !$0.isThis }).map(\.name)
        return names.isEmpty ? nil : names.joined(separator: ", ")
    }
}

enum Phones {
    struct Row: Equatable, Identifiable {
        let id: String
        let name: String
        let platform: String
        let lastSeenMs: Int64?
        let signedOut: Bool
        let isThis: Bool
    }

    /// D-222 (L58): the account's phones not marked signed out (the documents Your phones lists), for You's row.
    static func activeCount(_ docs: [(id: String, data: [String: Any])]) -> Int {
        docs.filter { $0.data["signedOut"] as? Bool != true }.count
    }

    /// D-222 (L58): the count for You's row, read once per visit from the server (the documents Your phones lists; no
    /// rules change, nothing logged). Signed out, offline or failing: nil, and the row leaves the count out.
    @MainActor static func activeCount() async -> Int? {
        guard let uid = Account.shared.uid,
              let snapshot = try? await Cloud.db.collection("users").document(uid).collection("devices").getDocuments(source: .server)
        else { return nil }
        return activeCount(snapshot.documents.map { ($0.documentID, $0.data()) })
    }

    /// D-225 (Design 4): Security's Your phones summary (`PhonesSummary.line` over the rows Your phones lists), read once
    /// per visit from the server; nil without an account or when the read fails, so the row then shows no summary.
    /// Android's twin is `Phones.summary`.
    @MainActor static func summary() async -> String? {
        guard let uid = Account.shared.uid,
              let snapshot = try? await Cloud.db.collection("users").document(uid).collection("devices").getDocuments(source: .server)
        else { return nil }
        let this = (try? CloudProfile.deviceId(.defaultStore())) ?? ""
        return PhonesSummary.line(rows: rows(snapshot.documents.map { ($0.documentID, $0.data()) }, thisDevice: this))
    }

    static func platformWord(_ platform: String) -> String {
        switch platform {
        case "ios": return "iPhone"
        case "android": return "Android"
        default: return ""
        }
    }

    /// D-225 follow-up (R2, the re-look): what Your phones shows from its listener's latest event. It is never silently
    /// empty: before the first event it waits, a listen that fails says so with Try again, and a listen that works lists
    /// the rows (or "No phones yet."). The twin of Android's `Phones.Listing`.
    enum Listing: Equatable {
        case waiting
        case failed
        case listed([Row])
    }

    /// An event's documents, or nil when the listen failed (Firestore then ends the listener: Try again starts a new
    /// one).
    static func listing(_ docs: [(id: String, data: [String: Any])]?, thisDevice: String) -> Listing {
        guard let docs else { return .failed }
        return .listed(rows(docs, thisDevice: thisDevice))
    }

    /// The account's phones as the screen lists them: this phone first, then
    /// the most recently seen.
    static func rows(_ docs: [(id: String, data: [String: Any])], thisDevice: String) -> [Row] {
        docs.map { doc in
            Row(
                id: doc.id,
                name: (doc.data["name"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? platformWord(doc.data["platform"] as? String ?? ""),
                platform: doc.data["platform"] as? String ?? "",
                lastSeenMs: (doc.data["lastSeen"] as? Timestamp).map(CloudRecords.millis),
                signedOut: doc.data["signedOut"] as? Bool == true,
                isThis: doc.id == thisDevice
            )
        }
        .sorted { a, b in
            if a.isThis != b.isThis { return a.isThis }
            return (a.lastSeenMs ?? 0, a.id) > (b.lastSeenMs ?? 0, b.id)
        }
    }

    /// The line under a phone's name.
    static func status(_ row: Row, relative: (Int64) -> String) -> String {
        if row.isThis { return PhonesCopy.thisPhone }
        if row.signedOut { return PhonesCopy.signedOut }
        return row.lastSeenMs.map { PhonesCopy.lastSeen(relative($0)) } ?? platformWord(row.platform)
    }

    /// Whether this phone's own device document says it was signed out AFTER
    /// it last signed in (an older flag is from before: the phone signed in
    /// again since, and its entry is made fresh).
    static func signedOutSince(_ data: [String: Any]?, authTimeMs: Int64) -> Bool {
        guard data?["signedOut"] as? Bool == true else { return false }
        guard let at = data?["signedOutAt"] as? Timestamp else { return true }
        return CloudRecords.millis(at) >= authTimeMs
    }

    @MainActor
    static func authTimeMs() async -> Int64 {
        guard let result = try? await FirebaseGate.auth?.currentUser?.getIDTokenResult() else { return 0 }
        return Int64(result.authDate.timeIntervalSince1970 * 1000)
    }

    @MainActor static func signOutDevice(_ id: String) async throws {
        _ = try await CloudFunctions.call("signOutDevice", ["deviceId": id])
    }

    @MainActor static func signOutEverywhere() async throws {
        _ = try await CloudFunctions.call("signOutEverywhere", [:])
    }

    /// SPEC §4.4: when this account is to be deleted; nil when none is
    /// pending or the profile cannot be read (the card then stays away).
    @MainActor static func pendingDeletion() async -> Date? {
        guard let uid = Account.shared.uid,
              let profile = try? await Cloud.db.collection("users").document(uid).getDocument(source: .server)
        else { return nil }
        guard !profile.exists else { return deletionDate(profile: profile.data(), approval: nil) }
        // D-254: an account with no profile (waiting for approval, or not approved: the rules refuse it one) carries the
        // marker on its own `approvals/{uid}`, which it may read; Keep my account clears it there. Without this read
        // the next sign-in within 7 days offered no Keep my account, and the purge deleted the account unannounced.
        let approval = try? await Cloud.db.collection("approvals").document(uid).getDocument(source: .server)
        return deletionDate(profile: nil, approval: approval?.data())
    }

    /// D-254: where the deletion marker is: the profile's `deleteAt` when there is a profile (`profile` non-nil),
    /// else the approval document's (`requestDeletion` puts it there for an account with no profile). Android's twin is
    /// `Phones.deletionDate`.
    static func deletionDate(profile: [String: Any]?, approval: [String: Any]?) -> Date? {
        if let profile { return (profile["deleteAt"] as? Timestamp)?.dateValue() }
        return (approval?["deleteAt"] as? Timestamp)?.dateValue()
    }

    @MainActor static func requestDeletion() async throws -> Date {
        let result = try await CloudFunctions.call("requestDeletion", [:])
        let ms = (result["deleteAt"] as? NSNumber)?.doubleValue ?? (Date().timeIntervalSince1970 * 1000 + 7 * 86_400_000)
        return Date(timeIntervalSince1970: ms / 1000)
    }

    @MainActor static func cancelDeletion() async throws {
        _ = try await CloudFunctions.call("cancelDeletion", [:])
    }

    static func day(_ date: Date) -> String { date.formatted(date: .long, time: .omitted) }
}

/// A phone signed out from another phone (or by Sign out everywhere there):
/// it signs itself out here, keeps its ledger, and says so.
@MainActor
final class RemoteSignOut: ObservableObject {
    static let shared = RemoteSignOut()
    @Published var notice = false
    /// D-198: after a deletion request, the date it happens.
    @Published var deletionDate: Date?
    /// D-198: this phone's own Sign out everywhere or deletion request is
    /// running. The cloud flags this phone before the call returns, so its
    /// listener fires; that sign-out is not "from another phone".
    private(set) var ownRequest = false
    /// D-198: counts this phone's sign-outs through here, so screens that
    /// are open when one happens can refresh or leave.
    @Published private(set) var signOuts = 0

    func happened() {
        arrived(signedIn: Account.shared.isSignedIn) { Account.shared.signOut() }
    }

    /// The decision, testable without an account.
    func arrived(signedIn: Bool, signOut: () -> Void) {
        guard signedIn else { return }
        signOut()
        signOuts += 1
        notice = !ownRequest
    }

    /// Runs this phone's own sign-out call, including the local sign-out
    /// that follows it, so the listener stays quiet throughout.
    func quietly<T>(_ call: () async throws -> T) async rethrows -> T {
        ownRequest = true
        defer { ownRequest = false }
        return try await call()
    }
}

// MARK: - Screens

/// SPEC §3.5's first-class list of the account's phones.
struct PhonesScreen: View {
    @ObservedObject private var remote = RemoteSignOut.shared
    @Environment(\.dismiss) private var dismiss
    @State private var listing = Phones.Listing.waiting
    @State private var confirming: Phones.Row?
    @State private var confirmingEverywhere = false
    @State private var error: String?
    @State private var listener: ListenerRegistration?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                switch listing {
                case .waiting:
                    EmptyView()
                case .failed:
                    // D-225 follow-up (R2): a listen that failed says so, never an empty page; Try again listens again.
                    Text(verbatim: PhonesCopy.loadFailed).font(TypeRole.body()).foregroundStyle(Tokens.owed)
                    Button { listenAgain() } label: { Text(verbatim: PhonesCopy.tryAgain) }
                        .buttonStyle(SecondaryButtonStyle())
                case .listed(let rows) where rows.isEmpty:
                    Text(verbatim: PhonesCopy.empty).font(TypeRole.body()).foregroundStyle(Tokens.textDim).glassCard()
                case .listed(let rows):
                    VStack(spacing: 0) {
                        ForEach(Array(rows.enumerated()), id: \.element.id) { i, row in
                            if i > 0 { Divider().overlay(Tokens.hairline) }
                            rowView(row)
                        }
                    }
                    .glassCard(padding: 14)
                }
                Text(verbatim: PhonesCopy.limit).font(TypeRole.body(14)).foregroundStyle(Tokens.textDim)
                if let error {
                    Text(verbatim: error).font(TypeRole.body()).foregroundStyle(Tokens.owed)
                }
                Button(PhonesCopy.everywhere) { confirmingEverywhere = true }
                    .buttonStyle(SecondaryButtonStyle())
            }
            .padding(16)
        }
        .auroraBackground()
        .navigationTitle(PhonesCopy.title)
        .navigationBarTitleDisplayMode(.inline)
        // Signed out (here, or from another phone): nothing left to show.
        .onChange(of: remote.signOuts) { dismiss() }
        .onAppear(perform: start)
        .onDisappear {
            listener?.remove()
            listener = nil
        }
        .alert(confirming.map { PhonesCopy.confirmTitle($0.name) } ?? "", isPresented: Binding(
            get: { confirming != nil }, set: { if !$0 { confirming = nil } }
        )) {
            Button(PhonesCopy.signItOut, role: .destructive) {
                if let row = confirming { Task { await signOut(row) } }
            }
            Button(PhonesCopy.cancel, role: .cancel) {}
        } message: { Text(verbatim: PhonesCopy.confirmBody) }
        // D-224 (Design 3): each question warns as it opens.
        .hapticWarning(when: confirming != nil)
        .alert(PhonesCopy.everywhereTitle, isPresented: $confirmingEverywhere) {
            Button(PhonesCopy.everywhere, role: .destructive) { Task { await everywhere() } }
            Button(PhonesCopy.cancel, role: .cancel) {}
        } message: { Text(verbatim: PhonesCopy.everywhereBody) }
        .hapticWarning(when: confirmingEverywhere)
    }

    private func rowView(_ row: Phones.Row) -> some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 3) {
                Text(verbatim: row.name).font(TypeRole.label(15)).foregroundStyle(Tokens.text)
                Text(verbatim: Phones.status(row) { ms in
                    Date(timeIntervalSince1970: Double(ms) / 1000).formatted(.relative(presentation: .named))
                })
                .font(TypeRole.body(13))
                .foregroundStyle(row.signedOut ? Tokens.textDim : Tokens.textDim)
            }
            Spacer(minLength: 8)
            if !row.isThis && !row.signedOut {
                Button(PhonesCopy.signItOut) { confirming = row }
                    .buttonStyle(SecondaryButtonStyle())
                    .fixedSize()
            }
        }
        .padding(.vertical, 8)
    }

    private func start() {
        guard listener == nil, let uid = Account.shared.uid else { return }
        let this = (try? CloudProfile.deviceId(.defaultStore())) ?? ""
        // D-225 follow-up (R2): each event goes through `Phones.listing`; a failed listen (no snapshot) is said, never
        // dropped.
        listener = Cloud.db.collection("users").document(uid).collection("devices").addSnapshotListener { snapshot, _ in
            let next = Phones.listing(snapshot?.documents.map { ($0.documentID, $0.data()) }, thisDevice: this)
            MainActor.assumeIsolated { listing = next }
        }
    }

    /// Try again: the failed listener is over, so a new one starts.
    private func listenAgain() {
        listener?.remove()
        listener = nil
        listing = .waiting
        start()
    }

    private func signOut(_ row: Phones.Row) async {
        do {
            try await Phones.signOutDevice(row.id)
            error = nil
        } catch {
            self.error = InviteCopy.failure(error)
        }
    }

    private func everywhere() async {
        do {
            try await RemoteSignOut.shared.quietly {
                try await Phones.signOutEverywhere()
                await Push.shared.stop(inCloud: false)
                RemoteSignOut.shared.happened()
            }
        } catch {
            self.error = InviteCopy.failure(error)
        }
    }
}

/// SPEC §4.4: one screen, two taps from Settings.
struct DeleteAccountScreen: View {
    @ObservedObject private var remote = RemoteSignOut.shared
    @Environment(\.dismiss) private var dismiss
    @State private var confirming = false
    @State private var busy = false
    @State private var error: String?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                VStack(alignment: .leading, spacing: 10) {
                    Text(verbatim: PhonesCopy.deleteIntro).font(TypeRole.body()).foregroundStyle(Tokens.text)
                    Text(verbatim: PhonesCopy.deleteStays).font(TypeRole.body()).foregroundStyle(Tokens.text)
                    Text(verbatim: PhonesCopy.deleteGrace).font(TypeRole.body(14)).foregroundStyle(Tokens.textDim)
                }
                .glassCard()
                NavigationLink {
                    ExportScreen(store: LedgerStore.defaultStore())
                } label: { Text(verbatim: PhonesCopy.exportFirst) }
                    .buttonStyle(SecondaryButtonStyle())
                if let error {
                    Text(verbatim: error).font(TypeRole.body()).foregroundStyle(Tokens.owed)
                }
                Button(role: .destructive) { confirming = true } label: { Text(verbatim: PhonesCopy.deleteButton) }
                    .buttonStyle(SecondaryButtonStyle(destructive: true))
                    .disabled(busy)
            }
            .padding(16)
        }
        .auroraBackground()
        .navigationTitle(PhonesCopy.deleteTitle)
        .navigationBarTitleDisplayMode(.inline)
        .onChange(of: remote.signOuts) { dismiss() }
        .alert(PhonesCopy.deleteConfirmTitle, isPresented: $confirming) {
            Button(PhonesCopy.deleteButton, role: .destructive) { Task { await delete() } }
            Button(PhonesCopy.cancel, role: .cancel) {}
        } message: { Text(verbatim: PhonesCopy.deleteConfirmBody) }
        // D-224 (Design 3): the warning as the delete question opens.
        .hapticWarning(when: confirming)
    }

    private func delete() async {
        busy = true
        defer { busy = false }
        do {
            let date = try await RemoteSignOut.shared.quietly {
                let date = try await Phones.requestDeletion()
                await Push.shared.stop(inCloud: false)
                RemoteSignOut.shared.happened()
                return date
            }
            RemoteSignOut.shared.deletionDate = date
        } catch {
            self.error = InviteCopy.failure(error)
        }
    }
}

/// Settings, while a deletion is pending: the date, and keeping the account.
struct DeletionPendingCard: View {
    /// The hub loads it (`Phones.pendingDeletion`). A `.task` here would never
    /// run: until the date is known this view is an empty Group, and a Group
    /// hands its modifiers to children it does not have (D-198).
    @Binding var date: Date?
    @State private var error: String?

    var body: some View {
        Group {
            if let date {
                VStack(alignment: .leading, spacing: 10) {
                    Text(verbatim: PhonesCopy.pending(Phones.day(date))).font(TypeRole.body()).foregroundStyle(Tokens.owed)
                    Button(PhonesCopy.keep) { Task { await keep() } }
                        .buttonStyle(PrimaryButtonStyle())
                    if let error {
                        Text(verbatim: error).font(TypeRole.body(14)).foregroundStyle(Tokens.owed)
                    }
                }
                .glassCard()
            }
        }
    }

    private func keep() async {
        do {
            try await Phones.cancelDeletion()
            date = nil
        } catch {
            self.error = InviteCopy.failure(error)
        }
    }
}
