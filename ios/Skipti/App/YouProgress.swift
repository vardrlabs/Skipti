import SwiftUI

/// D-221 (E4, the spec's completion ring, GUARANTEES #5): the setup steps You counts. Every fact is data the phone
/// already holds; nothing new is stored. Without an account five steps, signed in two more. No step's words mention
/// signing in or an account. Android's twin is YouProgress.kt; its YouProgressTest reads this file's copy.
enum SetupStep: CaseIterable, Equatable, Hashable {
    case name, picture, currency, appLock, firstGroup, username, notifications
}

/// What the phone holds, read by the You screen (Task 4's `SetupFacts.read`). Pure data: `SetupSteps` decides.
struct SetupFacts: Equatable {
    var signedIn = false
    /// `loadSelf()?.name` is set (vault).
    var hasName = false
    /// `loadAvatar() != .none` (vault).
    var hasPicture = false
    /// The stored `money.default-currency`, nil when unset. Unset is not done, though it resolves to the region's.
    var storedCurrency: String?
    /// The stored app lock timeout (`LockTimeout.parse`; missing reads as the default, two minutes).
    var lockTimeout = LockTimeout.standard
    /// The phone has a lock (Face ID, Touch ID or a passcode): `LAContext().canEvaluatePolicy(.deviceOwnerAuthentication)`
    /// (`AppLock.available`). D-247: without one the app lock step is not counted.
    var lockAvailable = false
    /// `namedGroups()` is not empty (read-only copies count: you were in a group).
    var hasGroup = false
    /// The account's username is known (signed in only).
    var hasUsername = false
    /// Notifications are authorised, provisional or ephemeral (signed in only).
    var notificationsOn = false
}

enum SetupSteps {
    /// The steps in their order: five without an account, seven signed in. D-247 (decision 169, D-221 h): a phone that
    /// cannot lock (no passcode, no Face ID or Touch ID: `lockAvailable`, read again whenever SKIPTI comes to the front)
    /// has no app lock step, so its ring can complete ("4 of 4"); it comes back once the phone has a lock.
    static func steps(signedIn: Bool, lockAvailable: Bool) -> [SetupStep] {
        let local: [SetupStep] = [.name, .picture, .currency, .appLock, .firstGroup].filter { $0 != .appLock || lockAvailable }
        return signedIn ? local + [.username, .notifications] : local
    }

    /// The facts' own steps.
    static func steps(_ facts: SetupFacts) -> [SetupStep] {
        steps(signedIn: facts.signedIn, lockAvailable: facts.lockAvailable)
    }

    /// Whether one step is done by these facts.
    static func isDone(_ step: SetupStep, _ facts: SetupFacts) -> Bool {
        switch step {
        case .name: return facts.hasName
        case .picture: return facts.hasPicture
        case .currency: return facts.storedCurrency.map { DefaultCurrency.all.contains($0) } ?? false
        case .appLock: return AppLockRules.lockedAtLaunch(timeout: facts.lockTimeout, available: facts.lockAvailable)
        case .firstGroup: return facts.hasGroup
        case .username: return facts.hasUsername
        case .notifications: return facts.notificationsOn
        }
    }

    /// The done steps among the facts' own steps.
    static func done(_ facts: SetupFacts) -> Set<SetupStep> {
        Set(steps(facts).filter { isDone($0, facts) })
    }

    /// The first undone step in order, passing over `skipping` (while the username card shows, it already asks);
    /// nil when every step is done.
    static func next(_ facts: SetupFacts, skipping: Set<SetupStep> = []) -> SetupStep? {
        steps(facts).first { !skipping.contains($0) && !isDone($0, facts) }
    }

    static func progress(_ facts: SetupFacts) -> (done: Int, total: Int) {
        (done(facts).count, steps(facts).count)
    }
}

/// Where a step's "Do it" goes (Task 4's switch over every case, no default).
enum StepPlace: Equatable {
    case profile, data, security, createGroup, username, profileAccount

    static func of(_ step: SetupStep) -> StepPlace {
        switch step {
        case .name, .picture: return .profile
        case .currency: return .data
        case .appLock: return .security
        case .firstGroup: return .createGroup
        case .username: return .username
        case .notifications: return .profileAccount
        }
    }
}

/// The steps' words, verbatim on both phones (GUARANTEES #5: none mentions signing in or an account).
enum StepCopy {
    static func title(_ step: SetupStep) -> String {
        switch step {
        case .name: return "Add your name"
        case .picture: return "Add a picture"
        case .currency: return "Choose your currency"
        case .appLock: return "Set an app lock"
        case .firstGroup: return "Start a group"
        case .username: return "Pick your username"
        case .notifications: return "Turn on notifications"
        }
    }

    static func why(_ step: SetupStep) -> String {
        switch step {
        case .name: return "Shown on Balance and in your groups."
        case .picture: return "A photo or an emoji beside your name."
        case .currency: return "New groups and expenses start in it."
        case .appLock: return "Your phone's lock before anyone sees your ledger."
        case .firstGroup: return "Split a trip, a flat or a dinner."
        case .username: return "People can find and add you by it."
        case .notifications: return "Hear when your groups add an expense."
        }
    }

    static let doIt = "Do it"

    /// The progress pill's words: "6 of 7 done".
    static func pill(done: Int, total: Int) -> String { "\(done) of \(total) done" }
}

/// Norse's ring: the 24 runes of the Elder Futhark round You's picture, the first `lit` in gold. Task 4 draws it.
struct RuneRing {
    let done: Int
    let total: Int

    /// The Look Book's `Math.round(24 * done / total)`, half up, in integers (art.py 18): 6 of 7 lights 21.
    static func lit(done: Int, total: Int) -> Int {
        guard total > 0 else { return 0 }
        return (48 * done + total) / (2 * total)
    }
}

// MARK: - D-221 Task 4: the views

extension SetupFacts {
    /// What the phone holds now: the vault (name, picture, groups), the two preferences as stored (an unset currency
    /// stays nil, never resolved), the phone's lock, and, signed in, the notification permission. `hasUsername` is
    /// the hub's own read of the account (a second cloud read on every reload would be waste). Nothing is written.
    /// D-221 (S7): the vault's part (three reads, each opening the vault) runs off the main thread.
    @MainActor
    static func read(store: LedgerStore, account: Account, hasUsername: Bool) async -> SetupFacts {
        var facts = SetupFacts()
        facts.signedIn = account.isSignedIn
        let vault = await Task.detached(priority: .userInitiated) { VaultFacts.read(store) }.value
        facts.hasName = vault.hasName
        facts.hasPicture = vault.hasPicture
        facts.hasGroup = vault.hasGroup
        facts.storedCurrency = UserDefaults.standard.string(forKey: DefaultCurrency.storageKey)
        facts.lockTimeout = LockTimeout.parse(UserDefaults.standard.string(forKey: LockTimeout.storageKey))
        facts.lockAvailable = AppLock.available
        #if DEBUG
        // D-247: the look's surface only (`--groups-proof --settle-look ring-nolock`): a simulator reports a lock.
        if SettleLook.step == "ring-nolock" { facts.lockAvailable = false }
        #endif
        facts.hasUsername = facts.signedIn && hasUsername
        if facts.signedIn {
            let status = await Push.shared.permission()
            facts.notificationsOn = status == .authorized || status == .provisional || status == .ephemeral
        }
        return facts
    }
}

/// D-221 (S7): the steps' facts the vault holds: a name, a picture, a group. Read off the main thread (`SetupFacts.read`).
/// A vault that cannot be read reads as "not done" (You is not the launch path; nothing is written or destroyed).
struct VaultFacts: Equatable {
    var hasName = false
    var hasPicture = false
    var hasGroup = false

    static func read(_ store: LedgerStore) -> VaultFacts {
        VaultFacts(hasName: (try? store.loadSelf())?.name != nil,
                   hasPicture: ((try? store.loadAvatar()) ?? .none) != .none,
                   hasGroup: !((try? store.namedGroups()) ?? []).isEmpty)
    }
}

/// D-221 (S7): which of several overlapping reads is the newest: a read that finishes after a newer one started is
/// dropped, so an old answer never overwrites a fresh one.
struct ReadGeneration: Equatable {
    private(set) var latest = 0

    /// A read starts: its number.
    mutating func next() -> Int {
        latest += 1
        return latest
    }

    func isLatest(_ generation: Int) -> Bool { generation == latest }
}

/// L38: the ring round You's picture in Aurora and Vardr Labs: the track, then the arc from 12 o'clock clockwise
/// over done of total, round caps; no arc at 0, the whole circle when every step is done. Decorative: the pill and
/// the hero's label carry the count.
struct CompletionRing: View {
    let done: Int
    let total: Int

    var body: some View {
        let radius = ThemeArt.m("ringRadius")
        let stroke = ThemeArt.m("ringStroke")
        ZStack {
            Circle()
                .stroke(Tokens.ringTrack, lineWidth: stroke)
                .frame(width: radius * 2, height: radius * 2)
            if done > 0, total > 0 {
                Circle()
                    .trim(from: 0, to: CGFloat(min(done, total)) / CGFloat(total))
                    .stroke(Tokens.ring, style: StrokeStyle(lineWidth: stroke, lineCap: .round))
                    .rotationEffect(.degrees(-90))
                    .frame(width: radius * 2, height: radius * 2)
            }
        }
        .frame(width: ThemeArt.m("ringBox"), height: ThemeArt.m("ringBox"))
        .accessibilityHidden(true)
    }
}

/// L39: Norse's ring: the 24 runes in order, centred on the radius, 15 degrees apart from 12 o'clock clockwise, each
/// turned by its angle (tops outward); the first `lit` in the ring's gold over a static glow (D-246: the rune's own shape
/// blurred, `GlyphGlow`; D-243's disc halo is gone), the rest unlit.
/// Decorative. You's ring turns (`ArtTurn` in Profile); the done check's turns on the moment's own clock.
extension RuneRing: View {
    var body: some View {
        let radius = ThemeArt.m("runeRingRadius")
        let size = ThemeArt.m("runeRingSize")
        let lit = RuneRing.lit(done: done, total: total)
        let step = 360 / Double(Runes.futhark.count)
        ZStack {
            ForEach(Array(Runes.futhark.enumerated()), id: \.offset) { i, rune in
                let angle = Angle.degrees(Double(i) * step)
                Text(verbatim: rune)
                    .font(TypeRole.rune(size))
                    .foregroundStyle(i < lit ? Tokens.ring : ThemeArt.color("runeUnlit"))
                    .fixedSize()
                    // D-246: a lit rune's static glow, its own shape blurred (GlyphGlow's cached mask, built once) in the
                    // ring's gold at RingTurn.litGlow, centred on the rune's box (it turns with the rune and the ring).
                    .background {
                        if i < lit {
                            GlyphGlow.view(rune, size: size, blur: RuneGlow.blur)
                                .foregroundStyle(Tokens.ring)
                                .opacity(RingTurn.litGlow)
                        }
                    }
                    .rotationEffect(angle)
                    .offset(x: radius * CGFloat(sin(angle.radians)), y: -radius * CGFloat(cos(angle.radians)))
            }
        }
        .frame(width: ThemeArt.m("ringBox"), height: ThemeArt.m("ringBox"))
        .accessibilityHidden(true)
    }
}

/// L40: "6 of 7 done" in C2's accent pill on the page; Vardr Labs leads it with the dot in `ring`.
extension ProgressPill: View {
    var body: some View {
        HStack(spacing: ThemeArt.m("progressDotGap")) {
            if ProgressPill.showsDot(AppearanceState.shared.theme) {
                Circle().fill(Tokens.ring).frame(width: ThemeArt.m("progressDot"), height: ThemeArt.m("progressDot"))
            }
            Text(verbatim: StepCopy.pill(done: done, total: total))
                .font(TypeRole.label(TypeScale.pill))
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 6)
        .themePill(.accent, onPage: true)
    }
}

/// L41: the next step on You: its title and why on the left, "Do it" (the compact primary) on the right; at the
/// accessibility text sizes the button drops under the words at full width. The hub decides where it goes.
struct NextStepCard: View {
    let step: SetupStep
    let doIt: () -> Void

    var body: some View {
        let large = AppearanceState.shared.contentSizeCategory.isAccessibilityCategory
        let layout = large
            ? AnyLayout(VStackLayout(alignment: .leading, spacing: 12))
            : AnyLayout(HStackLayout(alignment: .center, spacing: 12))
        layout {
            VStack(alignment: .leading, spacing: 4) {
                Text(verbatim: StepCopy.title(step))
                    .font(TypeRole.name(16))
                    .foregroundStyle(Tokens.text)
                Text(verbatim: StepCopy.why(step))
                    .font(TypeRole.body(TypeScale.detail))
                    .foregroundStyle(Tokens.textDim)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            // M7: the words are one element; "Do it" stays its own.
            .accessibilityElement(children: .combine)
            Button(action: doIt) {
                Text(verbatim: StepCopy.doIt)
                    .frame(maxWidth: large ? .infinity : nil)
            }
            .buttonStyle(PrimaryButtonStyle(compact: true))
        }
        .glassCard()
    }
}
