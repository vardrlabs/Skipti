import SwiftUI

/// C2 copy (the approved mock-up, v2). Android's twin is UsernameCopy in UsernameStep.kt.
enum UsernameCopy {
    static let title = "Pick your username"
    static let lede = "People find and add you by it. You can change it once later."
    static let yourName = "Your name"
    static let namePrompt = "What friends call you"
    static let username = "Username"
    static let prompt = "yourname"
    static let hint = "3 to 20 letters, numbers or _"
    static let checking = "Checking…"
    static func available(_ h: String) -> String { "@\(h) is available" }
    static func taken(_ h: String) -> String { "@\(h) is taken. Try one below." }
    static let notAvailable = "That username is not available."
    static let suggested = "Suggested for you"
    static let save = "Save username"
    static let offline = "Could not reach SKIPTI's cloud. Check your connection and try again."
    static func confirmTitle(_ h: String) -> String { "Change to @\(h)?" }
    static func confirmBody(new: String, old: String) -> String {
        "You can change your username once. After this, @\(new) is yours for good, and @\(old) is free for anyone to take."
    }
    static let change = "Change username"
    static func keep(_ h: String) -> String { "Keep @\(h)" }
    static let oneChangeLeft = "1 change left"
    static let changedOnce = "Changed once, it stays now"
    static let cardTitle = "Pick your username"
    static let cardBody = "People can find and add you by it. It takes a few seconds."
    static let cardAction = "Pick username"
    static let cancel = "Cancel"
    /// C2 (GUARANTEES #2): the required step's way on after a save failed.
    static let later = "Later"
}

/// The live status under the field.
enum UsernameStatus: Equatable {
    case hint, checking, available(String), taken(String), notAvailable, offline, limit
    /// D-254 fix wave (review I1): the check was refused because the account is not approved: its words, and no Save.
    case approval(String)

    /// What the phone can say at once, before any cloud check. `list`: the
    /// blocklist (the bundled one unless a test passes the parsed file).
    static func local(_ raw: String, list: UsernameRules.Compiled? = UsernameRules.list) -> UsernameStatus? {
        let h = UsernameRules.handle(raw)
        if h.count < 3 { return .hint }
        switch UsernameRules.problem(raw, list: list) {
        case .shape?: return .hint
        case .unavailable?: return .notAvailable
        case nil: return nil
        }
    }

    /// The handle Save may send: only an answer the cloud gave for exactly
    /// what the field holds now. A check for an older text never counts.
    static func acceptedHandle(_ status: UsernameStatus, text: String) -> String? {
        guard case .available(let h) = status, h == UsernameRules.handle(text) else { return nil }
        return h
    }

    /// The handle the step (never the change screen) may send: a free answer
    /// for exactly this text, or, when only the live check failed (offline,
    /// the day's limit), what the field holds if the phone finds nothing
    /// wrong with it. The cloud still decides; offline the save fails and
    /// "Later" appears (GUARANTEES #2).
    static func canSave(status: UsernameStatus, text: String,
                        list: UsernameRules.Compiled? = UsernameRules.list) -> String? {
        if let h = acceptedHandle(status, text: text) { return h }
        switch status {
        case .offline, .limit: return local(text, list: list) == nil ? UsernameRules.handle(text) : nil
        default: return nil
        }
    }

    /// A live check that failed: the day's limit says so; D-254 fix wave (review I1): a refusal for approval says
    /// that (never "Could not reach SKIPTI's cloud"); anything else is the offline line.
    static func failedCheck(_ error: Error) -> UsernameStatus {
        if let words = ApprovalCopy.failure(error) { return .approval(words) }
        return error as? CloudFunctions.Failure == .refused("RESOURCE_EXHAUSTED") ? .limit : .offline
    }
}

/// C2: why a save failed, and what the screen does about it.
enum UsernameSaveFailure: Equatable {
    /// The field says it (taken, not available): pick another.
    case taken, notAvailable
    /// The cloud refused the step's first set or the one change: a username
    /// exists already (another phone), or the profile is not ready.
    case used
    /// A line under the field (offline, signed out, the day's limit).
    case message(String)

    static func from(_ error: Error) -> UsernameSaveFailure {
        switch error as? CloudFunctions.Failure {
        case .refused("ALREADY_EXISTS")?: return .taken
        case .refused("INVALID_ARGUMENT")?: return .notAvailable
        case .refused("FAILED_PRECONDITION")?: return .used
        case .refused("RESOURCE_EXHAUSTED")?: return .message(PeopleCopy.limit)
        // D-254: the account is not approved yet (or was not): its words, never the offline line.
        case .awaitingApproval?: return .message(ApprovalCopy.callWaiting)
        case .notApproved?: return .message(ApprovalCopy.callDenied)
        default: return .message(UsernameCopy.offline)
        }
    }

    /// GUARANTEES #2: the required step offers "Later" only once a save has
    /// failed for a reason the field cannot fix.
    static func offersLater(_ failure: UsernameSaveFailure?) -> Bool {
        switch failure {
        case nil, .taken?, .notAvailable?: return false
        case .used?, .message?: return true
        }
    }
}

/// C2: what the sign-in page shows. A signed-out account never sees the
/// step (or the confirmation): it is back on the form.
enum SignInStep: Equatable {
    case form, waiting, step, confirmation

    static func decide(signedIn: Bool, needed: Bool?) -> SignInStep {
        guard signedIn else { return .form }
        switch needed {
        case true?: return .step
        case false?: return .confirmation
        case nil: return .waiting
        }
    }
}

extension UsernameState {
    /// What a screen keeps after a read. Signed out: nothing. A read that
    /// failed (nil) never forgets what was known: Home reads on every sync
    /// revision, and dropping to nil would take the card, and its open sheet
    /// with what was typed, away.
    static func merge(known: UsernameState?, read: UsernameState?, signedIn: Bool) -> UsernameState? {
        guard signedIn else { return nil }
        return read ?? known
    }

    /// The step after sign-in is only for an account known to have no
    /// username; unread (offline, slow) goes on, and the card asks later.
    static func needsStep(_ state: UsernameState?) -> Bool {
        state == UsernameState.none
    }
}

/// The @username field, its live status and the three suggestions; shared
/// by the step after sign-in and the change sheet.
struct UsernameField: View {
    @Binding var text: String
    @Binding var status: UsernameStatus
    let suggestions: [String]

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(verbatim: UsernameCopy.username).sectionLabel().foregroundStyle(Color(Tokens.textDim))
            HStack(spacing: 2) {
                Text(verbatim: "@").font(TypeRole.body()).foregroundStyle(Tokens.accent)
                TextField(UsernameCopy.prompt, text: $text)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .font(TypeRole.body())
                    .accessibilityLabel(UsernameCopy.username)
            }
            .padding(12)
            .glassCard()
            statusLine
            if !suggestions.isEmpty {
                Text(verbatim: UsernameCopy.suggested).sectionLabel().foregroundStyle(Color(Tokens.textDim))
                // A row when the three fit, else one under another (a long
                // name makes long suggestions).
                // In the row each chip keeps its natural width, so a row
                // that would cut a chip short does not fit and they stack.
                ViewThatFits(in: .horizontal) {
                    HStack(spacing: 8) { chips(fixed: true) }
                    VStack(spacing: 8) { chips(fixed: false) }
                }
            }
        }
        .task(id: text) {
            if let now = UsernameStatus.local(text) { status = now; return }
            status = .checking
            try? await Task.sleep(nanoseconds: 400_000_000)
            guard !Task.isCancelled else { return }
            let h = UsernameRules.handle(text)
            do {
                let answer = try await People.checkUsername(h)
                // The text changed while the cloud answered: this answer is
                // for an older text, and the newer task owns the status.
                guard !Task.isCancelled else { return }
                switch answer {
                case .available: status = .available(h)
                case .taken: status = .taken(h)
                case .shape: status = .hint
                case .unavailable: status = .notAvailable
                }
            } catch {
                if !Task.isCancelled { status = UsernameStatus.failedCheck(error) }
            }
        }
    }

    @ViewBuilder private func chips(fixed: Bool) -> some View {
        ForEach(suggestions, id: \.self) { s in
            Button { text = s } label: { Text(verbatim: "@" + s).lineLimit(1) }
                .buttonStyle(SecondaryButtonStyle(keepsCase: true))
                .fixedSize(horizontal: fixed, vertical: false)
        }
    }

    @ViewBuilder private var statusLine: some View {
        switch status {
        case .hint: Text(verbatim: UsernameCopy.hint).font(TypeRole.body(13)).foregroundStyle(Tokens.textDim)
        case .checking: Text(verbatim: UsernameCopy.checking).font(TypeRole.body(13)).foregroundStyle(Tokens.textDim)
        case .available(let h): Text(verbatim: "✓ " + UsernameCopy.available(h)).font(TypeRole.body(13)).foregroundStyle(Tokens.owing)
        case .taken(let h): Text(verbatim: UsernameCopy.taken(h)).font(TypeRole.body(13)).foregroundStyle(Tokens.owed)
        case .notAvailable: Text(verbatim: UsernameCopy.notAvailable).font(TypeRole.body(13)).foregroundStyle(Tokens.owed)
        case .offline: Text(verbatim: UsernameCopy.offline).font(TypeRole.body(13)).foregroundStyle(Tokens.owed)
        case .limit: Text(verbatim: PeopleCopy.limit).font(TypeRole.body(13)).foregroundStyle(Tokens.owed)
        case .approval(let words): Text(verbatim: words).font(TypeRole.body(13)).foregroundStyle(Tokens.owed)
        }
    }
}

/// C2: right after sign-in, for an account with no username. Required: no
/// close button there, but once a save has failed (offline, the day's limit)
/// "Later" goes on to the usual confirmation and the card asks later
/// (`onLater`). Asks your name first when this phone has none. Opened from the
/// card or You (`onCancel` set), it can be closed: the person chose to open
/// it, and it must never stand in the way (GUARANTEES #2).
struct UsernameStep: View {
    let store: LedgerStore
    var onCancel: (() -> Void)? = nil
    var onLater: (() -> Void)? = nil
    var onDone: () -> Void
    @State private var name = ""
    @State private var needsName = false
    @State private var text = ""
    @State private var status = UsernameStatus.hint
    @State private var suggestions: [String] = []
    @State private var saving = false
    @State private var error: String?
    /// Once offered, "Later" stays: a way on never disappears again.
    @State private var laterOffered = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                Text(verbatim: UsernameCopy.lede).font(TypeRole.body()).foregroundStyle(Tokens.textDim)
                if needsName {
                    Text(verbatim: UsernameCopy.yourName).sectionLabel().foregroundStyle(Color(Tokens.textDim))
                    TextField(UsernameCopy.namePrompt, text: $name)
                        .textContentType(.name)
                        .font(TypeRole.body())
                        .padding(12)
                        .glassCard()
                }
                UsernameField(text: $text, status: $status, suggestions: suggestions)
                if let error {
                    Text(verbatim: error).font(TypeRole.body(14)).foregroundStyle(Tokens.owed)
                }
            }
            .padding(20)
        }
        .formActionBar {
            VStack(spacing: 8) {
                Button(UsernameCopy.save) { Task { await save() } }
                    .buttonStyle(PrimaryButtonStyle())
                    .disabled(saving || !canSave)
                if let onLater, laterOffered {
                    Button(UsernameCopy.later) { onLater() }
                        .buttonStyle(SecondaryButtonStyle())
                        .disabled(saving)
                }
            }
        }
        .auroraBackground()
        .navigationTitle(UsernameCopy.title)
        .navigationBarTitleDisplayMode(.inline)
        .navigationBarBackButtonHidden()
        .interactiveDismissDisabled(onCancel == nil)
        .toolbar {
            if let onCancel {
                ToolbarItem(placement: .cancellationAction) {
                    Button(UsernameCopy.cancel) { onCancel() }.barButtonText().barTint()
                }
            }
        }
        .task { needsName = ((try? store.loadSelf())?.name ?? "").isEmpty }
        .task(id: needsName ? name : "self") {
            let source = needsName ? name : ((try? store.loadSelf())?.name ?? "")
            guard !source.trimmingCharacters(in: .whitespaces).isEmpty else { suggestions = []; return }
            try? await Task.sleep(nanoseconds: 400_000_000)
            guard !Task.isCancelled else { return }
            suggestions = await People.suggestUsernames(name: source)
        }
    }

    private var canSave: Bool {
        guard UsernameStatus.canSave(status: status, text: text) != nil else { return false }
        return !needsName || !name.trimmingCharacters(in: .whitespaces).isEmpty
    }

    private func save() async {
        guard let h = UsernameStatus.canSave(status: status, text: text) else { return }
        saving = true
        error = nil
        defer { saving = false }
        do {
            if needsName {
                _ = try store.setYourName(name)
                CloudSync.shared.ledgerChanged()
            }
            // A `.none` read earlier may be stale (a username picked on
            // another phone since): setUsername would then spend the one
            // change without its confirm. Already set: nothing to pick.
            if let now = await People.accountUsername(), now != UsernameState.none {
                // D-211: a name saved just now reaches the account's groups.
                if needsName { ProfileShare.request() }
                onDone()
                return
            }
            guard let uid = Account.shared.uid else { throw CloudFunctions.Failure.signedOut }
            // The profile (and its participantId) first: setUsername refuses
            // until it exists, and must never make a half one.
            // D-235 (C4, iOS L3): CloudSync's one registration, never a second one beside it.
            _ = await CloudSync.shared.registration(uid: uid)
            // D-211: the name just saved reaches the account's groups.
            if needsName { ProfileShare.request() }
            // `first`: never spends the one change. A username set on
            // another phone since the read above is refused, not replaced.
            _ = try await People.setUsername(h, first: true)
            // D-224 (Design 7): the toast only after a real save, outside the sign-in flow (inside it the signed-in
            // screen follows). The ways out above and below saved nothing on this tap, so they post nothing.
            if onCancel != nil { DoneCenter.shared.post(DoneMoments.line(DoneCopy.usernameSaved, PeopleCopy.handle(h))) }
            onDone()
        } catch {
            let failed = UsernameSaveFailure.from(error)
            if UsernameSaveFailure.offersLater(failed) { laterOffered = true }
            switch failed {
            case .taken: status = .taken(h)
            case .notAvailable: status = .notAvailable
            case .used:
                // Set on another phone meanwhile: nothing left to pick.
                if let now = await People.accountUsername(), now != UsernameState.none {
                    onDone()
                } else {
                    self.error = UsernameCopy.offline
                }
            case .message(let line): self.error = line
            }
        }
    }
}

/// C2: the one change, from You. Asks first. `onUsed`: the cloud says the
/// one change is already spent (another phone); the parent closes the sheet
/// and reads the account again.
struct UsernameChangeSheet: View {
    let current: String
    let store: LedgerStore
    var onSaved: (String) -> Void
    var onUsed: () -> Void
    @State private var text = ""
    @State private var status = UsernameStatus.hint
    @State private var suggestions: [String] = []
    @State private var confirming = false
    /// The handle the confirm names, captured at the tap: the one sent.
    @State private var pending: String?
    @State private var saving = false
    @State private var error: String?
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                UsernameField(text: $text, status: $status, suggestions: suggestions)
                if let error { Text(verbatim: error).font(TypeRole.body(14)).foregroundStyle(Tokens.owed) }
            }
            .padding(20)
        }
        .formActionBar {
            Button(UsernameCopy.change) {
                pending = acceptedChange
                confirming = pending != nil
            }
                .buttonStyle(PrimaryButtonStyle())
                .disabled(saving || !canChange)
        }
        .auroraBackground()
        .navigationTitle(UsernameCopy.change)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { ToolbarItem(placement: .cancellationAction) { Button(UsernameCopy.cancel) { dismiss() }.barButtonText().barTint() } }
        .alert(UsernameCopy.confirmTitle(pending ?? ""), isPresented: $confirming) {
            Button(UsernameCopy.change) {
                if let h = pending { Task { await save(h) } }
            }
            Button(UsernameCopy.keep(current), role: .cancel) {}
        } message: {
            Text(verbatim: UsernameCopy.confirmBody(new: pending ?? "", old: current))
        }
        // D-224 (Design 3): the one change cannot be taken back: it warns as its question opens.
        .hapticWarning(when: confirming)
        .task {
            let name = (try? store.loadSelf())?.name ?? ""
            if !name.isEmpty {
                suggestions = UsernameRules.changeSuggestions(await People.suggestUsernames(name: name), current: current)
            }
        }
    }

    /// Only a free username, checked for exactly what the field holds, that
    /// is not the one you have.
    private var acceptedChange: String? {
        guard let h = UsernameStatus.acceptedHandle(status, text: text), h != current else { return nil }
        return h
    }

    private var canChange: Bool { acceptedChange != nil }

    private func save(_ h: String) async {
        saving = true
        error = nil
        defer { saving = false }
        do {
            let saved = try await People.setUsername(h) ?? current
            // D-224 (Design 7).
            DoneCenter.shared.post(DoneMoments.line(DoneCopy.usernameSaved, PeopleCopy.handle(saved)))
            onSaved(saved)
        } catch {
            switch UsernameSaveFailure.from(error) {
            case .used: onUsed()
            case .taken: status = .taken(h)
            case .notAvailable: status = .notAvailable
            case .message(let line): self.error = line
            }
        }
    }
}

/// C2: for an account signed in before usernames were asked: a card on Home
/// and You, never a screen in the way (GUARANTEES #2). The parent shows it
/// only while its own `UsernameState` reads `.none` (loaded in the parent's
/// existing task, so no task sits on a view that can be empty, CLAUDE.md
/// §10.1). The card only asks: the parent owns the sheet
/// (`usernamePickSheet`) on its always-present container, because the card
/// itself comes and goes with a sync (D-208: a sheet on it was torn down).
struct UsernameCard: View {
    var onPick: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(verbatim: UsernameCopy.cardTitle).font(TypeRole.label(16)).foregroundStyle(Tokens.text)
            Text(verbatim: UsernameCopy.cardBody).font(TypeRole.body(14)).foregroundStyle(Tokens.textDim)
            Button(UsernameCopy.cardAction) { onPick() }.buttonStyle(PrimaryButtonStyle())
        }
        .glassCard()
    }
}

extension View {
    /// The card's step, presented by a parent that never goes away while it
    /// is open; `onPicked` reloads the parent's state.
    func usernamePickSheet(isPresented: Binding<Bool>, store: LedgerStore, onPicked: @escaping () -> Void) -> some View {
        sheet(isPresented: isPresented) {
            NavigationStack {
                UsernameStep(store: store, onCancel: { isPresented.wrappedValue = false }) {
                    isPresented.wrappedValue = false
                    onPicked()
                }
            }
        }
    }
}
