import LocalAuthentication
import SwiftUI

/// D-163 (SPEC §2.3 A6, §3.7): the app lock. SKIPTI locks when it starts and
/// when it has been in the background longer than the chosen time (two
/// minutes unless changed), and opens with Face ID, Touch ID or the phone's
/// passcode. The passcode is allowed on purpose: SPEC's A6 says "biometric
/// or device credential", and a face that stops matching must not lock
/// someone out of their own ledger. While SKIPTI is not in front, a plain
/// cover hides the ledger from the app switcher's snapshot. Android's twin
/// is AppLock.kt: same rules, same copy where the platforms allow.
///
/// No passcode on the phone means no lock is possible; Settings says so,
/// and nothing pretends otherwise.

/// How long SKIPTI may sit in the background before it locks.
enum LockTimeout: String, CaseIterable, Identifiable {
    case immediately = "0"
    case oneMinute = "60"
    case twoMinutes = "120"
    case fiveMinutes = "300"
    case fifteenMinutes = "900"
    case off

    static let storageKey = "applock.timeout"
    static let standard = LockTimeout.twoMinutes

    var id: String { rawValue }

    /// Unknown or missing reads as the default: a setting this build does
    /// not know never turns the lock off.
    static func parse(_ stored: String?) -> LockTimeout {
        stored.flatMap(LockTimeout.init(rawValue:)) ?? standard
    }

    var seconds: TimeInterval? { self == .off ? nil : TimeInterval(rawValue) }

    var label: String {
        switch self {
        case .immediately: return "Immediately"
        case .oneMinute: return "After 1 minute"
        case .twoMinutes: return "After 2 minutes"
        case .fiveMinutes: return "After 5 minutes"
        case .fifteenMinutes: return "After 15 minutes"
        case .off: return "Off"
        }
    }
}

/// The pure rules, tested without a device.
enum AppLockRules {
    /// Locked at start whenever a lock is possible and on.
    static func lockedAtLaunch(timeout: LockTimeout, available: Bool) -> Bool {
        available && timeout != .off
    }

    /// Coming back to the front: locks once the time in the background
    /// reached the timeout. A clock that went backwards locks too, so
    /// changing the phone's time cannot skip the lock.
    static func locksOnReturn(timeout: LockTimeout, available: Bool, backgroundedAt: Date?, now: Date) -> Bool {
        guard available, let limit = timeout.seconds, let since = backgroundedAt else { return false }
        let away = now.timeIntervalSince(since)
        return away < 0 || away >= limit
    }
}

enum AppLockCopy {
    static let section = "App lock"
    static let pickerLabel = "Lock SKIPTI"
    static let title = "SKIPTI is locked"
    static let detail = "Unlock with Face ID, Touch ID or your passcode."
    static let unlock = "Unlock"
    static let reason = "Unlock SKIPTI"
    static let failed = "Not unlocked. Try again."
    static let on = "SKIPTI locks when it starts, and when it has been in the background this long."
    static let offDetail = "App lock is off. Anyone with your unlocked phone can open SKIPTI."
    static let unavailable = "App lock needs a passcode on this phone. Set one in the phone's Settings."
    static let confirmTitle = "Turn off app lock?"
    static let confirmBody = "Anyone who picks up your unlocked phone could open SKIPTI and see your ledger."
    static let confirmOff = "Turn off"
    static let keepOn = "Keep it on"
}

/// D-225 (L67 to L70): the Security page's rows. The safe card holds App lock, the screen-privacy switch and Your
/// phones; the careful card ("Careful") holds Erase and Delete account, each drawn in the danger tone. `SettingsMap`
/// keeps the order; the page partitions it by `careful`. Android's twin is `SecurityRows` in AppLock.kt.
enum SecurityRows {
    static let careful: Set<SettingItem> = [.erase, .deleteAccount]
    /// App lock's summary when this phone has no lock to use.
    static let noPhoneLock = "Needs a phone lock"
    /// The Erase row's summary (the page it opens asks before anything goes).
    static let eraseSummary = "Asks first. Cannot be undone."
    /// The Delete account row's summary (deletion waits 7 days, `PhonesCopy.deleteGrace`).
    static let deleteAccountSummary = "A week to change your mind"

    /// App lock's summary: the chosen timeout's label, or `noPhoneLock` whatever the timeout when the phone cannot lock.
    static func appLockLine(timeout: LockTimeout, available: Bool) -> String {
        available ? timeout.label : noPhoneLock
    }

    /// The section's items split into the two cards, each in `SettingsMap`'s order.
    static func partition(_ items: [SettingItem]) -> (safe: [SettingItem], careful: [SettingItem]) {
        (items.filter { !careful.contains($0) }, items.filter { careful.contains($0) })
    }
}

/// The lock's state for the whole app.
@MainActor
final class AppLock: ObservableObject {
    static let shared = AppLock()

    @Published private(set) var locked = false
    @Published private(set) var failure: String?
    private var backgroundedAt: Date?
    private var prompting = false

    var timeout: LockTimeout {
        LockTimeout.parse(UserDefaults.standard.string(forKey: LockTimeout.storageKey))
    }

    /// Whether this phone can lock SKIPTI at all: it needs a passcode.
    static var available: Bool {
        LAContext().canEvaluatePolicy(.deviceOwnerAuthentication, error: nil)
    }

    func launch() {
        locked = AppLockRules.lockedAtLaunch(timeout: timeout, available: Self.available)
    }

    func wentToBackground(_ now: Date = Date()) {
        backgroundedAt = now
    }

    func cameToFront(_ now: Date = Date()) {
        if AppLockRules.locksOnReturn(
            timeout: timeout, available: Self.available, backgroundedAt: backgroundedAt, now: now
        ) {
            locked = true
        }
        backgroundedAt = nil
    }

    func set(_ timeout: LockTimeout) {
        UserDefaults.standard.set(timeout.rawValue, forKey: LockTimeout.storageKey)
    }

    /// One system prompt; a fresh context every time.
    func unlock() async {
        guard locked, !prompting else { return }
        prompting = true
        defer { prompting = false }
        let context = LAContext()
        do {
            if try await context.evaluatePolicy(.deviceOwnerAuthentication, localizedReason: AppLockCopy.reason) {
                locked = false
                failure = nil
            }
        } catch {
            // Cancelled or failed: stay locked, say so plainly.
            failure = AppLockCopy.failed
        }
    }
}

/// Over everything while locked.
struct AppLockScreen: View {
    @ObservedObject var lock: AppLock

    var body: some View {
        VStack(spacing: 16) {
            Spacer()
            // D-225 (Design 13; art.py 44): Lucide's lock at `lockGlyph` in textDim above the title at the lock title's
            // size, on the art ground (Android draws the same).
            LucideIcon(glyph: .lock, size: ThemeArt.m("lockGlyph"))
                .foregroundStyle(Tokens.textDim)
                .accessibilityHidden(true)
            Text(verbatim: AppLockCopy.title)
                .font(TypeRole.display(TypeScale.lockTitle))
                .foregroundStyle(Tokens.text)
            Text(verbatim: AppLockCopy.detail)
                .font(TypeRole.body())
                .foregroundStyle(Tokens.textDim)
                .multilineTextAlignment(.center)
            if let failure = lock.failure {
                Text(verbatim: failure).font(TypeRole.body(14)).foregroundStyle(Tokens.owed)
            }
            Spacer()
            Button { Task { await lock.unlock() } } label: { Text(verbatim: AppLockCopy.unlock) }
                .buttonStyle(PrimaryButtonStyle())
        }
        .padding(24)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .auroraBackground()
        .task { await lock.unlock() }
    }
}

/// What the app switcher sees while SKIPTI is not in front: no ledger.
struct PrivacyCover: View {
    var body: some View {
        Text(verbatim: "SKIPTI")
            .font(TypeRole.display(28))
            .foregroundStyle(Tokens.textDim)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(Tokens.bg)
            .accessibilityHidden(true)
    }
}

/// D-225 fix wave (review M6): what a pick on the App lock page does. A timeout is written at once; Off from a
/// timeout asks first; Off while already Off asks nothing and returns (it used to raise the question again).
enum AppLockPick: Equatable {
    case write, ask, close

    static func of(_ choice: LockTimeout, chosen: LockTimeout) -> AppLockPick {
        guard choice == .off else { return .write }
        return chosen == .off ? .close : .ask
    }
}

/// D-225 (Design 4): Security → App lock, a choice page of the six timeouts (the stored one checked) with the on or off
/// caption under the card. Every choice but Off writes at once and returns; Off stays on the page and asks first (D-224's
/// warning as the question opens), writing only from "Turn off", then returns. A phone that cannot lock shows only the
/// unavailable caption. Android's twin is `AppLockPage` in AppLock.kt.
struct AppLockPage: View {
    @AppStorage(LockTimeout.storageKey) private var stored = LockTimeout.standard.rawValue
    @State private var confirmingOff = false
    @Environment(\.dismiss) private var dismiss
    private let available = AppLock.available

    var body: some View {
        let chosen = LockTimeout.parse(stored)
        ChoicePage(
            title: AppLockCopy.section,
            options: available ? LockTimeout.allCases.map { ChoiceOption(value: $0, label: $0.label) } : [],
            chosen: chosen,
            caption: available ? (chosen == .off ? AppLockCopy.offDetail : AppLockCopy.on) : AppLockCopy.unavailable,
            stays: { $0 == .off },
            onPick: { choice in
                switch AppLockPick.of(choice, chosen: chosen) {
                case .write: stored = choice.rawValue
                case .ask: confirmingOff = true
                case .close: dismiss()
                }
            }
        )
        .alert(AppLockCopy.confirmTitle, isPresented: $confirmingOff) {
            Button(AppLockCopy.confirmOff, role: .destructive) { stored = LockTimeout.off.rawValue; dismiss() }
            Button(AppLockCopy.keepOn, role: .cancel) {}
        } message: {
            Text(verbatim: AppLockCopy.confirmBody)
        }
        // D-224 (Design 3): the warning as the question opens.
        .hapticWarning(when: confirmingOff)
    }
}

// MARK: - D-164: the rest of SPEC §3.7

/// Screen privacy (the founder, 2026-09-25: a setting, off by default, so a
/// balance can still be screenshotted and sent to the group). iPhone gives
/// apps no way to block a screenshot, so on iOS the setting hides the
/// ledger while the screen is recorded, mirrored or shared, which iOS does
/// report, and says plainly what it cannot do.
enum ScreenPrivacy {
    static let storageKey = "privacy.hide-when-captured"

    /// Cover the ledger: only with the setting on, only while captured.
    static func covers(enabled: Bool, captured: Bool) -> Bool { enabled && captured }
}

enum PrivacyCopy {
    static let section = "Privacy"
    static let captureToggle = "Hide during screen recording"
    static let captureDetail =
        "Your ledger is hidden while the screen is recorded, mirrored or shared. iPhone does not let apps block screenshots."
    static func modified(_ kind: String) -> String {
        "This phone looks \(kind). Other apps may be able to read what SKIPTI stores and shows. SKIPTI still works; this is a warning, not a block."
    }
}

/// Whether the screen is being recorded, mirrored or shared right now.
@MainActor
final class CaptureWatch: ObservableObject {
    static let shared = CaptureWatch()
    @Published private(set) var captured = false
    private var observer: NSObjectProtocol?

    private init() {
        update()
        observer = NotificationCenter.default.addObserver(
            forName: UIScreen.capturedDidChangeNotification, object: nil, queue: .main
        ) { [weak self] _ in
            Task { @MainActor in self?.update() }
        }
    }

    private func update() {
        captured = UIApplication.shared.connectedScenes
            .compactMap { ($0 as? UIWindowScene)?.screen.isCaptured }
            .contains(true)
    }
}

/// SPEC §3.7: "Detect and warn, never block." Nothing is logged or sent.
/// A simulator runs on a Mac, where these paths exist, so it never reports.
enum DeviceIntegrity {
    static let jailbreakPaths = [
        "/Applications/Cydia.app", "/Applications/Sileo.app", "/Applications/Zebra.app",
        "/var/jb", "/private/var/lib/apt", "/usr/sbin/sshd", "/etc/apt", "/bin/bash",
    ]

    static func looksJailbroken(exists: (String) -> Bool) -> Bool {
        jailbreakPaths.contains(where: exists)
    }

    static var looksJailbroken: Bool {
        #if targetEnvironment(simulator)
        return false
        #else
        return looksJailbroken { FileManager.default.fileExists(atPath: $0) }
        #endif
    }
}

/// D-164: what goes on the clipboard from SKIPTI stays on this phone (no
/// Universal Clipboard) and expires. An invite link is a secret until it is
/// used, so it lasts two minutes: long enough to paste into a message.
enum Clipboard {
    static let lifetime: TimeInterval = 120

    static func copySecret(_ text: String, now: Date = Date()) {
        UIPasteboard.general.setItems(
            [[UIPasteboard.typeAutomatic: text]],
            options: [.localOnly: true, .expirationDate: now.addingTimeInterval(lifetime)]
        )
    }
}

/// D-225 (Design 4; L-D217-9): the screen-privacy row of Security's first card: the platform's true title, its honest
/// limit as the summary on as many lines as it needs, and the drawn switch a row gap clear of the words; the whole row
/// toggles it (one switch to VoiceOver). Android's twin is `PrivacyRow` in AppLock.kt.
struct PrivacyRow: View {
    @AppStorage(ScreenPrivacy.storageKey) private var hideWhenCaptured = false

    var body: some View {
        ListRow(title: PrivacyCopy.captureToggle, parts: [PrivacyCopy.captureDetail], summaryLines: 0,
                trailing: .toggle($hideWhenCaptured))
    }
}

/// D-225 (Design 4): on a phone that looks jailbroken, SPEC §3.7's warning in `watch`, in its own glass card at the top
/// of Security (art.py 41: 4.5 on every card ground). Security draws it only on such a phone. Android's twin is
/// `ModifiedPhoneWarning` in AppLock.kt.
struct ModifiedPhoneWarning: View {
    var body: some View {
        Text(verbatim: PrivacyCopy.modified("jailbroken"))
            .font(TypeRole.body(TypeScale.detail))
            .foregroundStyle(Tokens.watch)
            .fixedSize(horizontal: false, vertical: true)
            .glassCard()
    }
}

// MARK: - D-235 (C1, decision 106): the lock and the privacy cover in their own window

/// What the curtain shows: the lock screen while locked; the plain cover while SKIPTI is not in front (the app
/// switcher's snapshot) or while the screen is captured with the setting on; else nothing.
enum CurtainContent: Equatable {
    case lock, cover
}

/// The pure rules of the curtain (iOS audit H1). D-163 drew the lock and the cover as an overlay on the root view,
/// and every sheet, alert and full-screen cover the app presents sits ABOVE the root view: an open Add expense or an
/// expense's page stayed usable over the lock, the app switcher's snapshot showed it, an invite link opened while
/// locked presented its Join page over the lock, and VoiceOver could still reach the ledger under the overlay.
/// Android draws `AppLockScreen` instead of everything (AppRoot.kt). Here the curtain is its own window, above every
/// level the app presents at, and the app's other windows are hidden from accessibility while it is up.
enum LockCurtainRules {
    /// Above alerts: every presentation of the app (sheets, alerts, full-screen covers) lives in the main window at
    /// `.normal`, so nothing the person left open can sit over the curtain.
    static let level = UIWindow.Level.alert + 1

    /// `enabled`: the ledger exists and this is not a proof launch (they skip both, as D-163's overlay did).
    static func content(enabled: Bool, locked: Bool, active: Bool, captureCovers: Bool) -> CurtainContent? {
        guard enabled else { return nil }
        if locked { return .lock }
        if !active || captureCovers { return .cover }
        return nil
    }

    /// D-235 round 4 (shells review M1): a window made on a scene iOS has since disconnected (it may, in the background,
    /// and connect a new one) would never be drawn again: the curtain is made afresh on the scene in front.
    @MainActor
    static func needsNewWindow(_ current: UIWindow?, scene: UIWindowScene) -> Bool {
        guard let current else { return true }
        return current.windowScene !== scene
    }

    /// Puts `curtain` up or down over `others` (the app's other windows). Up: above every presentation, the only
    /// window VoiceOver can reach (`accessibilityViewIsModal` on it and its root, the others' elements hidden), and,
    /// for the lock, the key window with any typing in the others ended (a field left open under the lock must not
    /// keep the keyboard). Down: hidden, the others reachable again, and the main window key again.
    @MainActor
    static func apply(_ content: CurtainContent?, curtain: UIWindow, others: [UIWindow]) {
        let up = content != nil
        curtain.windowLevel = level
        curtain.accessibilityViewIsModal = up
        curtain.rootViewController?.view.accessibilityViewIsModal = up
        for window in others { window.accessibilityElementsHidden = up }
        // D-235 round 4 (shells review M10): for the cover too, so the app switcher's snapshot shows no keyboard or
        // QuickType bar (the price: a field left open loses its keyboard when SKIPTI leaves the front).
        if up { others.forEach { $0.endEditing(true) } }
        if up {
            curtain.isHidden = false
            if content == .lock { curtain.makeKey() }
        } else {
            curtain.isHidden = true
            if curtain.isKeyWindow { others.first { !$0.isHidden && $0.windowLevel == .normal }?.makeKey() }
        }
    }
}

/// The curtain window itself: made once, on the app's window scene, and shown or hidden by `update`. SkiptiApp drives
/// it from the lock, the scene phase and screen capture; the scene's own will-deactivate notice raises the cover at
/// once too, so the app switcher's snapshot never waits for SwiftUI's next update.
@MainActor
final class LockCurtain: ObservableObject {
    static let shared = LockCurtain()

    @Published private(set) var content: CurtainContent?
    /// A new identity each time the lock goes up, so the lock screen's automatic prompt runs again.
    @Published private(set) var raised = 0
    private var window: UIWindow?
    private var enabled = false
    private var observer: NSObjectProtocol?

    private init() {
        observer = NotificationCenter.default.addObserver(
            forName: UIScene.willDeactivateNotification, object: nil, queue: .main
        ) { _ in
            MainActor.assumeIsolated {
                let curtain = LockCurtain.shared
                if curtain.enabled, curtain.content == nil { curtain.show(.cover) }
            }
        }
    }

    func update(enabled: Bool, locked: Bool, active: Bool, captureCovers: Bool) {
        self.enabled = enabled
        show(LockCurtainRules.content(enabled: enabled, locked: locked, active: active, captureCovers: captureCovers))
    }

    private func show(_ next: CurtainContent?) {
        if next == .lock, content != .lock { raised += 1 }
        let changed = next != content
        content = next
        guard let scene = Self.scene() else { return }
        let curtain: UIWindow
        if LockCurtainRules.needsNewWindow(window, scene: scene) {
            window?.isHidden = true
            curtain = makeWindow(scene)
        } else {
            curtain = window!
        }
        guard changed || curtain.isHidden != (next == nil) else { return }
        LockCurtainRules.apply(next, curtain: curtain, others: scene.windows.filter { $0 !== curtain })
        UIAccessibility.post(notification: .screenChanged, argument: nil)
    }

    private static func scene() -> UIWindowScene? {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        return scenes.first { $0.activationState == .foregroundActive } ?? scenes.first
    }

    private func makeWindow(_ scene: UIWindowScene) -> UIWindow {
        let curtain = UIWindow(windowScene: scene)
        let host = UIHostingController(rootView: CurtainRoot())
        host.view.backgroundColor = .clear
        curtain.rootViewController = host
        curtain.windowLevel = LockCurtainRules.level
        curtain.isHidden = true
        Appearance.apply(AppearanceState.shared, to: curtain)
        window = curtain
        return curtain
    }
}

/// The curtain window's content: the lock screen or the cover, in the person's appearance.
private struct CurtainRoot: View {
    @ObservedObject private var curtain = LockCurtain.shared
    @ObservedObject private var lock = AppLock.shared

    var body: some View {
        Group {
            switch curtain.content {
            case .lock?: AppLockScreen(lock: lock).id(curtain.raised)
            case .cover?: PrivacyCover()
            case nil: Color.clear
            }
        }
        .appliesStoredAppearance()
    }
}
