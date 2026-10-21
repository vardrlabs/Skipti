import FirebaseAuth
import Foundation
import SwiftUI
import UIKit

/// D-195 (the Firebase plan's unit 5): inviting a NAMED person into a group
/// and joining one, through SKIPTI's Cloud Functions (firebase/functions).
/// The founder's decisions (2026-09-24, kept): invite by QR when together or a
/// link when apart; the person is in the group's ledger whether or not they
/// ever join, so joining only links them. Inviting and joining need an
/// account (GUARANTEES #5), so neither appears without one. Android's twin is
/// Invites.kt: same copy, same order.

enum InviteCopy {
    static let section = "Invite"
    static let sectionFooter =
        "People you invite see this group and add to it. They are in its ledger whether or not they join."
    static func invite(_ name: String) -> String { "Invite \(name)" }
    static let making = "Making an invite…"
    static func how(_ name: String) -> String {
        "\(name) scans this with their phone's camera, or opens the link you send."
    }
    static func joinsAs(_ name: String, group: String) -> String {
        "\(name) joins \(group) as \(name). They are already in its ledger; joining lets them see it and add to it."
    }
    static let once = "The link works once, for 7 days."
    static let send = "Send the link"
    static let copy = "Copy link"
    static let copied = "Link copied"
    static let tryAgain = "Try again"

    static let joinTitle = "Join a group"
    static let pastePrompt = "Paste the invite link"
    static let notALink = "That is not a SKIPTI invite link."
    static let continueLabel = "Continue"
    static let opening = "Opening the invite…"
    static func invited(by inviter: String?, to group: String) -> String {
        inviter.map { "\($0) invited you to \(group)." } ?? "You are invited to \(group)."
    }
    static func youJoinAs(_ name: String) -> String { "You join as \(name)." }
    static let joinDetail = "You will see this group's expenses and add to them."
    static let join = "Join"
    static let joining = "Joining…"
    static let notNow = "Not now"
    static func joined(_ group: String) -> String { "You are in \(group)." }
    static let done = "Done"
    /// D-172: Join a group opened without an account.
    static let joinNeedsAccountTitle = "Joining needs an account"
    static let joinNeedsAccountBody =
        "A shared group reaches you through sync, so joining one means signing in first. Everything else in SKIPTI works without an account."
    static let signIn = "Sign in"
    static let needsAccount =
        "Joining someone's group needs an account, because the group is shared through sync. Sign in from Settings, then open the link again."
    static let used = "This invite was already used or has expired. Ask for a new one."
    /// D-218: the cloud refuses an invite into a place someone has already joined as
    /// (status `already-exists`). The same words acceptInvite uses for this case.
    static let held = "Someone has already joined as this person."
    /// D-229 decision 52: the cloud refuses an invite into a group this account is already in.
    static let alreadyMember = "You're already in this group."
    static let offline = "Could not reach SKIPTI's cloud. Check your connection and try again."
    static let notMember = "This group is not in SKIPTI's cloud with your account, so you cannot invite to it."

    static func failure(_ error: Error) -> String {
        switch error as? CloudFunctions.Failure {
        case .signedOut?: return needsAccount
        case .alreadyMember?: return alreadyMember
        case .refused(let status)?:
            switch status {
            case "FAILED_PRECONDITION", "NOT_FOUND": return used
            case "ALREADY_EXISTS": return held
            case "PERMISSION_DENIED": return notMember
            case "INVALID_ARGUMENT": return notALink
            case "UNAUTHENTICATED": return needsAccount
            default: return offline
            }
        default: return offline
        }
    }

    /// D-218: whether the failed invite is worth offering "Try again" for. A held place stays
    /// held until its person is removed, so the same call would say the same thing; so does
    /// a group this account is already in (D-229 decision 52).
    static func canRetry(_ error: Error) -> Bool {
        switch error as? CloudFunctions.Failure {
        case .refused("ALREADY_EXISTS")?, .alreadyMember?: return false
        default: return true
        }
    }
}

/// The invite link: SKIPTI's join page with the token in the fragment, which
/// a browser never sends to any server. Only the Functions turn a token into
/// a group, and they keep only its hash.
enum InviteLink {
    static let prefix = "https://skipti.vardrlabs.com/join#"

    static func make(token: String) -> String { prefix + token }

    /// The token in a pasted or opened link, or nil.
    static func token(_ text: String) -> String? {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed.hasPrefix(prefix) else { return nil }
        let token = String(trimmed.dropFirst(prefix.count))
        return token.range(of: "^[A-Za-z0-9_-]{43}$", options: .regularExpression) != nil ? token : nil
    }
}

/// Calls a Cloud Function the way Firebase's callable protocol defines it
/// (POST {"data": …} with the account's ID token; {"result": …} or
/// {"error": {"status": …}} back), without adding the Functions SDK.
enum CloudFunctions {
    enum Failure: Error, Equatable {
        case signedOut
        /// The Function said no: its status ("PERMISSION_DENIED", …).
        case refused(String)
        /// D-229 decision 52: acceptInvite's `already-exists` with `details.member == true`: this
        /// account already holds a place in the invite's group.
        case alreadyMember
        case offline
    }

    @MainActor
    static func url(_ name: String) -> URL {
        let base = Cloud.emulator
            ? "http://localhost:5001/\(FirebaseConfig.projectID)/us-central1/"
            : "https://us-central1-\(FirebaseConfig.projectID).cloudfunctions.net/"
        return URL(string: base + name)!
    }

    /// The callable protocol's reply: the result, or the Function's refusal.
    static func decode(_ body: Data) throws -> [String: Any] {
        guard let json = try? JSONSerialization.jsonObject(with: body) as? [String: Any] else {
            throw Failure.offline
        }
        if let error = json["error"] as? [String: Any] {
            let status = error["status"] as? String ?? "UNKNOWN"
            if status == "ALREADY_EXISTS", let details = error["details"] as? [String: Any],
               let member = details["member"] as? Bool, member {
                throw Failure.alreadyMember
            }
            throw Failure.refused(status)
        }
        guard let result = json["result"] as? [String: Any] else { throw Failure.offline }
        return result
    }

    @MainActor
    static func call(_ name: String, _ data: [String: Any]) async throws -> [String: Any] {
        guard let user = FirebaseGate.auth?.currentUser else { throw Failure.signedOut }
        let token: String
        do { token = try await user.getIDToken() } catch { throw Failure.offline }
        var request = URLRequest(url: url(name))
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        request.httpBody = try JSONSerialization.data(withJSONObject: ["data": data])
        let body: Data
        do { (body, _) = try await URLSession.shared.data(for: request) } catch { throw Failure.offline }
        return try decode(body)
    }
}

/// The three steps, on top of the Functions.
enum Invites {
    struct Offer: Equatable {
        let groupName: String
        let memberName: String
        let inviterName: String?
        /// D-230: the invite's group and member, for the held places the join sends (nil from an older Function:
        /// the join then sends none, and `PlaceShare` asks at the next push).
        let groupId: String?
        let memberId: String?

        init(groupName: String, memberName: String, inviterName: String?, groupId: String? = nil,
             memberId: String? = nil) {
            self.groupName = groupName
            self.memberName = memberName
            self.inviterName = inviterName
            self.groupId = groupId
            self.memberId = memberId
        }
    }

    /// A link that invites `member` into the group. The group is put in the
    /// cloud first if this phone has not yet (one made before signing in).
    @MainActor
    static func create(store: LedgerStore, group: NamedGroupWire, member: MemberWire) async throws -> String {
        guard let uid = Account.shared.uid else { throw CloudFunctions.Failure.signedOut }
        switch await CloudSync.ensureGroup(group.group.id, name: group.group.name, uid: uid) {
        case .member: break
        case .notMember: throw CloudFunctions.Failure.refused("PERMISSION_DENIED")
        case .offline: throw CloudFunctions.Failure.offline
        }
        CloudSync.shared.push()
        let inviter = (try? store.loadSelf())?.name
        var data: [String: Any] = [
            "groupId": group.group.id, "memberId": member.id, "memberName": member.name,
            "groupName": group.group.name,
        ]
        if let inviter { data["inviterName"] = inviter }
        let result = try await CloudFunctions.call("createInvite", data)
        guard let token = result["token"] as? String else { throw CloudFunctions.Failure.offline }
        return InviteLink.make(token: token)
    }

    @MainActor
    static func peek(link: String) async throws -> Offer {
        guard let token = InviteLink.token(link) else { throw CloudFunctions.Failure.refused("INVALID_ARGUMENT") }
        return try offer(from: try await CloudFunctions.call("peekInvite", ["token": token]))
    }

    /// The offer a `peekInvite` answer makes (D-230: with the invite's place; an older Function's answer has none).
    static func offer(from result: [String: Any]) throws -> Offer {
        guard let group = result["groupName"] as? String, let member = result["memberName"] as? String else {
            throw CloudFunctions.Failure.offline
        }
        return Offer(groupName: group, memberName: member, inviterName: result["inviterName"] as? String,
                     groupId: result["groupId"] as? String, memberId: result["memberId"] as? String)
    }

    /// D-230 (A1, A5): acceptInvite takes 1 to 11 held places.
    static let joinMax = 11

    /// D-230 (A1 second form, A5, A7): the held places the join sends for the server to mark, stamped by this phone
    /// in this order, NOT put into the vault (they come back marked through the group's log): every place of `group`
    /// a claim of this account holds (core's and this vault's), with that claim's id (`PlaceShare.claimed`; the server
    /// marks the ones it may and skips the rest), at most `joinMax - 1` in the group's member order (`claimed`'s;
    /// the rest reach the server through `PlaceShare` at the next push); then, last, the account's
    /// "you" (`p.you`, the earliest, never this phone's own id) on the invite's `member`. A brand-new install has no
    /// "you" yet: it is minted here (`ensureSelf`, which the join's claim does right after anyway), so its first join
    /// carries its mark too. The new pair is never repeated among the claimed ones (the server refuses a (group,
    /// member, you) twice); the same member under another of the account's ids is its own record (A8).
    static func heldRecords(
        store: LedgerStore, group: String, member: String, nowMs: UInt64 = UInt64(Date().timeIntervalSince1970 * 1000)
    ) throws -> [RecordWire] {
        let p = try LedgerStore.Sync.projectGroups(store)
        let you = try p.you?.id ?? store.ensureSelf().id
        let place = PlaceShare.Held(groupId: group, memberId: member, youId: you)
        let records = try store.allRecords()
        let old = (p.groups.first { $0.group.id == group }.map { PlaceShare.claimed(p, $0, records: records) } ?? [])
            .filter { $0 != place }
        return joinBuilt(store: store, places: Array(old.prefix(joinMax - 1)) + [place], nowMs: nowMs)
    }

    /// The join's records, built one place at a time (`PlaceShare.recordsByGroup`, as the place job does): an old
    /// pair whose record core cannot build is left out (the place job asks for it at the next push) and never takes
    /// the new place's record with it. The new place, `places`' last, is required: without its record, none (the
    /// join sends the token alone). Android's twin: `Invites.joinBuilt`.
    static func joinBuilt(
        store: LedgerStore, places: [PlaceShare.Held], nowMs: UInt64 = UInt64(Date().timeIntervalSince1970 * 1000)
    ) -> [RecordWire] {
        guard let place = places.last else { return [] }
        let built = PlaceShare.recordsByGroup(store: store, places: places, nowMs: nowMs)
        return built.contains { $0.held == place } ? built.map(\.record) : []
    }

    /// The held places a join sends: none without the offer's place (a Function older than D-230) or when they
    /// cannot be built (the join never waits on them).
    static func joinRecords(
        offer: Offer?, store: LedgerStore, nowMs: UInt64 = UInt64(Date().timeIntervalSince1970 * 1000)
    ) -> [RecordWire] {
        guard let g = offer?.groupId, let m = offer?.memberId else { return [] }
        return (try? heldRecords(store: store, group: g, member: m, nowMs: nowMs)) ?? []
    }

    /// The acceptInvite call's data: the token, and the held places when this phone could build them.
    static func acceptData(token: String, records: [RecordWire]) -> [String: Any] {
        var data: [String: Any] = ["token": token]
        if !records.isEmpty { data["records"] = Removal.payload(records) }
        return data
    }

    /// Joins: the Function adds this account to the group and, when the offer names the place, marks the held
    /// places this phone built (D-230; a record that fails a check is skipped, never the join); this phone then
    /// claims the named person in the group's own log (0.4.0 readers), and syncs. Returns the group's id.
    @MainActor
    static func accept(link: String, offer: Offer?, store: LedgerStore) async throws -> String {
        guard let token = InviteLink.token(link) else { throw CloudFunctions.Failure.refused("INVALID_ARGUMENT") }
        let records = joinRecords(offer: offer, store: store)
        let result = try await CloudFunctions.call("acceptInvite", acceptData(token: token, records: records))
        guard let group = result["groupId"] as? String, let member = result["memberId"] as? String else {
            throw CloudFunctions.Failure.offline
        }
        try LedgerStore.Sync.appendGroupOps(store, [claim(group: group, member: member, you: try store.ensureSelf().id,
                                                          device: try LedgerStore.Sync.deviceId(store))],
                                            placing: .none)
        if var up = try? CloudRecords.groupsUp(store) {
            up.insert(group)
            try? CloudRecords.saveGroupsUp(store, up)
        }
        // D-210: someone removed from this group and invited back writes to it again.
        try? store.clearReadOnly(group)
        CloudSync.shared.syncSwitched()
        // D-211: your own name and preview go to the place you claimed; until
        // the group's records bring that place, the group waits
        // (`ProfileShare.arrived` shares once when it does).
        ProfileShare.joined(group: group, store: store)
        return group
    }

    /// The claim that links this account's "you" to the named person. Core
    /// still asks for a key per device (the relay's agreement key, unused since
    /// ADR-0040): 32 zero bytes.
    static func claim(group: String, member: String, you: String, device: String) -> GroupOpIn {
        .claim(groupId: group, memberId: member, youId: you,
               devices: [ClaimDeviceIn(deviceId: device, agreementPublicHex: String(repeating: "0", count: 64))])
    }
}

enum QRImage {
    static func cgImage(_ text: String, scale: Int = 8) -> CGImage? {
        guard let code = try? ffiQrEncode(text: text) else { return nil }
        let quiet = 4
        let n = Int(code.size)
        let side = (n + 2 * quiet) * scale
        var pixels = [UInt8](repeating: 255, count: side * side)
        for row in 0..<n {
            for col in 0..<n where code.modules[row * n + col] {
                for dy in 0..<scale {
                    let y = (row + quiet) * scale + dy
                    for dx in 0..<scale {
                        pixels[y * side + (col + quiet) * scale + dx] = 0
                    }
                }
            }
        }
        guard let provider = CGDataProvider(data: Data(pixels) as CFData) else { return nil }
        return CGImage(
            width: side, height: side, bitsPerComponent: 8, bitsPerPixel: 8, bytesPerRow: side,
            space: CGColorSpaceCreateDeviceGray(), bitmapInfo: CGBitmapInfo(rawValue: 0),
            provider: provider, decode: nil, shouldInterpolate: false, intent: .defaultIntent
        )
    }
}

struct QRCodeView: View {
    let text: String

    var body: some View {
        if let image = QRImage.cgImage(text) {
            Image(decorative: image, scale: 1)
                .interpolation(.none)
                .resizable()
                .aspectRatio(1, contentMode: .fit)
                .accessibilityLabel("QR code for the invite link")
        }
    }
}

/// Invite one named member (from the group's People screen).
struct InviteScreen: View {
    let store: LedgerStore
    let group: NamedGroupWire
    let member: MemberWire

    private enum Phase: Equatable { case making, ready(String), failed(String, retry: Bool) }
    @State private var phase = Phase.making
    @State private var copied = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                switch phase {
                case .making:
                    HStack(spacing: 12) {
                        ProgressView()
                        Text(verbatim: InviteCopy.making).font(TypeRole.body()).foregroundStyle(Tokens.textDim)
                    }
                case .ready(let link):
                    QRCodeView(text: link)
                        .frame(maxWidth: 280)
                        .frame(maxWidth: .infinity)
                    Text(verbatim: InviteCopy.how(member.name)).font(TypeRole.body()).foregroundStyle(Tokens.text)
                    Text(verbatim: InviteCopy.joinsAs(member.name, group: group.group.name))
                        .font(TypeRole.body()).foregroundStyle(Tokens.textDim)
                    Text(verbatim: InviteCopy.once).font(TypeRole.body()).foregroundStyle(Tokens.textDim)
                    if let url = URL(string: link) {
                        ShareLink(item: url) { Text(verbatim: InviteCopy.send) }
                            .buttonStyle(PrimaryButtonStyle())
                    }
                    Button {
                        // D-164: this phone only, and it expires.
                        Clipboard.copySecret(link)
                        copied = true
                    } label: {
                        Text(verbatim: copied ? InviteCopy.copied : InviteCopy.copy)
                    }
                    .buttonStyle(SecondaryButtonStyle())
                    // D-197: or find them on SKIPTI and invite them in the app.
                    NavigationLink {
                        FindPersonScreen(store: store, group: group, member: member)
                    } label: { Text(verbatim: PeopleCopy.findOnSkipti(member.name)) }
                        .buttonStyle(SecondaryButtonStyle())
                case .failed(let message, let retry):
                    Text(verbatim: message).font(TypeRole.body()).foregroundStyle(Tokens.owed)
                    if retry {
                        Button { Task { await make() } } label: { Text(verbatim: InviteCopy.tryAgain) }
                            .buttonStyle(SecondaryButtonStyle())
                    }
                }
            }
            .padding(20)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .auroraBackground() // D-176
        .navigationTitle(InviteCopy.invite(member.name))
        .navigationBarTitleDisplayMode(.inline)
        .task { if phase == .making { await make() } }
    }

    private func make() async {
        phase = .making
        copied = false
        do {
            phase = .ready(try await Invites.create(store: store, group: group, member: member))
            // D-196: the moment pushes start to matter: someone may join.
            await Push.shared.askPermission()
        } catch {
            phase = .failed(InviteCopy.failure(error), retry: InviteCopy.canRetry(error))
        }
    }
}

/// Paste a link (the Groups list's "Join a group"); a link opened from
/// outside the app skips straight to `JoinScreen`.
struct JoinPasteScreen: View {
    let store: LedgerStore
    var onJoined: (String) -> Void
    @State private var text = ""
    @State private var link: String?
    /// D-172: read on every appearance, so returning from Sign in shows the
    /// paste field.
    @State private var hasAccount = true

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                if hasAccount { pasteField } else { needsAccount }
            }
            .padding(20)
        }
        .auroraBackground() // D-176
        .navigationTitle(InviteCopy.joinTitle)
        .navigationBarTitleDisplayMode(.inline)
        .navigationDestination(item: $link) { link in
            JoinScreen(store: store, link: link, onJoined: onJoined)
        }
        .onAppear { hasAccount = Account.shared.isSignedIn }
    }

    /// Said only in answer to the person's own tap on Join (GUARANTEES #5:
    /// no pitch, no benefit copy); the sign-in is the secondary style.
    @ViewBuilder
    private var needsAccount: some View {
        Text(verbatim: InviteCopy.joinNeedsAccountTitle)
            .font(TypeRole.body())
            .foregroundStyle(Tokens.text)
        Text(verbatim: InviteCopy.joinNeedsAccountBody)
            .font(TypeRole.body(14))
            .foregroundStyle(Tokens.textDim)
        NavigationLink {
            SignInScreen()
        } label: {
            Text(verbatim: InviteCopy.signIn)
        }
        .buttonStyle(SecondaryButtonStyle())
    }

    @ViewBuilder
    private var pasteField: some View {
        TextField(InviteCopy.pastePrompt, text: $text, axis: .vertical)
            .font(TypeRole.body())
            .textInputAutocapitalization(.never)
            .autocorrectionDisabled()
            .padding(12)
            .background(Tokens.surface, in: ThemeArt.controlShape(AppearanceState.shared.theme, radius: 10))
            .accessibilityLabel(InviteCopy.pastePrompt)
        if !trimmed.isEmpty && InviteLink.token(trimmed) == nil {
            Text(verbatim: InviteCopy.notALink).font(TypeRole.body()).foregroundStyle(Tokens.owed)
        }
        Button { link = trimmed } label: { Text(verbatim: InviteCopy.continueLabel) }
            .buttonStyle(PrimaryButtonStyle())
            .disabled(InviteLink.token(trimmed) == nil)
    }

    private var trimmed: String { text.trimmingCharacters(in: .whitespacesAndNewlines) }
}

/// What an invite offers, and joining it.
struct JoinScreen: View {
    let store: LedgerStore
    let link: String
    var onJoined: (String) -> Void
    /// D-217: a sheet's Cancel ("Not now"); nil on the pushed page (Join paste), whose back is the system's.
    var onCancel: (() -> Void)? = nil
    @Environment(\.dismiss) private var dismiss

    private enum Phase: Equatable {
        case opening, offer(Invites.Offer), joining(Invites.Offer), joined(String, name: String), failed(String)

        /// D-217 final fix: once a join has started there is no Cancel and no back: Done (onJoined) is the way out,
        /// so the caller's dismissal and reload always run.
        var locked: Bool {
            switch self {
            case .joining, .joined: true
            case .opening, .offer, .failed: false
            }
        }
    }
    @State private var phase = Phase.opening

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                switch phase {
                case .opening:
                    HStack(spacing: 12) {
                        ProgressView()
                        Text(verbatim: InviteCopy.opening).font(TypeRole.body()).foregroundStyle(Tokens.textDim)
                    }
                case .offer(let p), .joining(let p):
                    Text(verbatim: InviteCopy.invited(by: p.inviterName, to: p.groupName))
                        .font(TypeRole.display(28)).foregroundStyle(Tokens.text)
                    Text(verbatim: InviteCopy.youJoinAs(p.memberName)).font(TypeRole.body()).foregroundStyle(Tokens.text)
                    Text(verbatim: InviteCopy.joinDetail).font(TypeRole.body()).foregroundStyle(Tokens.textDim)
                    Button { Task { await join(p) } } label: {
                        Text(verbatim: phase == .joining(p) ? InviteCopy.joining : InviteCopy.join)
                    }
                    .buttonStyle(PrimaryButtonStyle())
                    .disabled(phase == .joining(p))
                    Button { dismiss() } label: { Text(verbatim: InviteCopy.notNow) }
                        .buttonStyle(SecondaryButtonStyle())
                case .joined(let group, let name):
                    // D-224 (Design 7, 8): the big check, centred above the heading (it plays its own success).
                    DoneCheck(.screen)
                        .frame(maxWidth: .infinity)
                    Text(verbatim: InviteCopy.joined(name)).font(TypeRole.display(28)).foregroundStyle(Tokens.text)
                    Button { onJoined(group) } label: { Text(verbatim: InviteCopy.done) }
                        .buttonStyle(PrimaryButtonStyle())
                case .failed(let message):
                    Text(verbatim: message).font(TypeRole.body()).foregroundStyle(Tokens.owed)
                    Button { dismiss() } label: { Text(verbatim: InviteCopy.done) }
                        .buttonStyle(SecondaryButtonStyle())
                }
            }
            .padding(20)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .auroraBackground() // D-176
        .navigationTitle(InviteCopy.joinTitle)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if let onCancel, !phase.locked {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { onCancel() }.barButtonText().barTint()
                }
            }
        }
        .navigationBarBackButtonHidden(phase.locked)
        .interactiveDismissDisabled(phase.locked)
        .task { if phase == .opening { await open() } }
    }

    private func open() async {
        guard Account.shared.isSignedIn else {
            phase = .failed(InviteCopy.needsAccount)
            return
        }
        do {
            phase = .offer(try await Invites.peek(link: link))
        } catch {
            phase = .failed(InviteCopy.failure(error))
        }
    }

    private func join(_ offer: Invites.Offer) async {
        phase = .joining(offer)
        do {
            let group = try await Invites.accept(link: link, offer: offer, store: store)
            try? store.selectGroup(id: group)
            phase = .joined(group, name: offer.groupName)
            // D-196: and now this group's news can reach you.
            await Push.shared.askPermission()
        } catch {
            phase = .failed(InviteCopy.failure(error))
        }
    }
}
