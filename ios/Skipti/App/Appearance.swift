import Observation
import SwiftUI
import UIKit

/// D-212 (E1, ADR-0041, the founder, 2026-10-14): the person's theme. Aurora
/// is the default; Norse is always dark; Vardr Labs is the company's own look.
/// Stored in UserDefaults, not the vault, like the mode (ADR-0036): the first
/// screens draw before the vault is open, and it is not financial data.
enum AppTheme: String, CaseIterable, Identifiable {
    case aurora
    case norse
    case vardr

    static let storageKey = "appearance.theme"

    var id: Self { self }

    var label: String {
        switch self {
        case .aurora: return "Aurora"
        case .norse: return "Norse"
        case .vardr: return "Vardr Labs"
        }
    }

    /// A stored value this build does not know (or none) is Aurora.
    static func parse(_ raw: String?) -> AppTheme {
        raw.flatMap(AppTheme.init(rawValue:)) ?? .aurora
    }

    /// Norse has one set, dark (the founder's Look Book).
    var forcesDark: Bool { self == .norse }

    /// The Look Book's `--p-radius`.
    var cardRadius: CGFloat {
        switch self {
        case .aurora: return 20
        case .norse: return 3
        case .vardr: return 0
        }
    }

    /// Buttons never round more than the theme's cards (D-174's 14 in Aurora).
    var buttonRadius: CGFloat { min(14, cardRadius) }

    /// Frosted glass over the glow is Aurora's alone; the others draw solid cards.
    var hasGlass: Bool { self == .aurora }
}

/// D-144's choice of light or dark. D-212: Night is retired for now
/// (ADR-0036 amended, the founder, 2026-10-14); it returns in a later version.
enum AppearanceMode: String, CaseIterable, Identifiable {
    case system
    case light
    case dark

    static let storageKey = "appearance.mode"

    var id: Self { self }

    var label: String {
        switch self {
        case .system: return String(localized: "System")
        case .light: return String(localized: "Light")
        case .dark: return String(localized: "Dark")
        }
    }

    /// D-232 (decisions 73, 75; ADR-0042): nothing stored is a new install, which opens in Dark. A stored `night` (a
    /// build before D-212) reads as Dark, and so does anything else this build does not know. A stored choice wins.
    static func parse(_ raw: String?) -> AppearanceMode {
        guard let raw else { return .dark }
        if raw == "night" { return .dark }
        return AppearanceMode(rawValue: raw) ?? .dark
    }

    /// D-232 (decision 74): set once per install by `AppearanceDefault.settle`, at the first launch of a build with the
    /// dark default; from then on the launch migration never runs again.
    static let settledKey = "appearance.modeSettled"

    /// D-244 (fix wave, M3): set by `AppearanceDefault.settle` only when it stored `system` for an install that ran
    /// before and never chose; cleared by the first real mode tap (`SettingsSync.write`). While it stands, the stored
    /// mode is this phone's own default, not a choice, so the first sign-in never fills the account with it
    /// (`SettingsSync.gaps`). Per phone (`SettingsSync.perPhone`), never synced.
    static let migratedKey = "appearance.modeMigrated"

    /// D-232 (decision 74): what the launch stores before the mode is first read. An install that ran before (its vault
    /// file exists) and never chose a mode followed the phone, and keeps following it: `system`. A brand-new install has
    /// no vault yet, so nothing is stored and it reads Dark. A stored word is never touched. nil: store nothing.
    static func migrate(stored: String?, vaultExists: Bool) -> String? {
        stored == nil && vaultExists ? AppearanceMode.system.rawValue : nil
    }

    /// The window's style for this mode under `theme`: Norse is always dark.
    func interfaceStyle(theme: AppTheme) -> UIUserInterfaceStyle {
        if theme.forcesDark { return .dark }
        switch self {
        case .system: return .unspecified
        case .light: return .light
        case .dark: return .dark
        }
    }
}

/// The five token sets (ADR-0041): a theme by its effective mode.
enum ThemeSet: Int, CaseIterable {
    case auroraLight, auroraDark, norse, vardrLight, vardrDark

    static func resolve(_ theme: AppTheme, dark: Bool) -> ThemeSet {
        switch theme {
        case .aurora: return dark ? .auroraDark : .auroraLight
        case .norse: return .norse
        case .vardr: return dark ? .vardrDark : .vardrLight
        }
    }

    var isDark: Bool { self != .auroraLight && self != .vardrLight }

    var theme: AppTheme {
        switch self {
        case .auroraLight, .auroraDark: return .aurora
        case .norse: return .norse
        case .vardrLight, .vardrDark: return .vardr
        }
    }
}

/// D-212: SKIPTI's own text size, -2 to +3 steps from the phone's.
enum TextSize {
    static let storageKey = "appearance.textSize"
    static let steps = -2...3
    static let ladder: [UIContentSizeCategory] = [
        .extraSmall, .small, .medium, .large, .extraLarge, .extraExtraLarge, .extraExtraExtraLarge,
        .accessibilityMedium, .accessibilityLarge, .accessibilityExtraLarge,
        .accessibilityExtraExtraLarge, .accessibilityExtraExtraExtraLarge,
    ]

    /// A stored step outside the range (or none) is the phone's own size.
    static func parse(_ raw: Int?) -> Int {
        guard let raw, steps.contains(raw) else { return 0 }
        return raw
    }

    /// The phone's category moved by `step`, clamped to the ladder's ends.
    static func category(phone: UIContentSizeCategory, step: Int) -> UIContentSizeCategory {
        let base = ladder.firstIndex(of: phone) ?? 3 // .large
        return ladder[min(max(base + step, 0), ladder.count - 1)]
    }

    static func accessibilityValue(_ step: Int) -> String {
        switch step {
        case 0: return "Your phone's size"
        case -1: return "1 step smaller"
        case 1: return "1 step larger"
        case let s where s < 0: return "\(-s) steps smaller"
        default: return "\(step) steps larger"
        }
    }
}

/// D-216 (Design 3): the person's app icon choice, stored beside the theme in UserDefaults. Match theme (the default)
/// follows the theme; the other three hold one icon whatever the theme. The stored words are permanent from D-216.
enum AppIconChoice: String, CaseIterable {
    case match
    case aurora
    case norse
    case vardr

    static let storageKey = "appearance.icon"

    /// A stored value this build does not know (or none) is Match theme. Case matters: the words are stored lower case.
    static func parse(_ raw: String?) -> AppIconChoice {
        raw.flatMap(AppIconChoice.init(rawValue:)) ?? .match
    }
}

/// D-216 (Design 1): the three icons, one per theme. Aurora is the primary icon (no alternate name), so nothing changes
/// for anyone on Aurora; the other two are the asset catalog's alternate icon sets, named in the build settings.
enum AppIcon: String, CaseIterable {
    case aurora
    case norse
    case vardr

    /// The icon a theme gives.
    static func of(_ theme: AppTheme) -> AppIcon {
        switch theme {
        case .aurora: return .aurora
        case .norse: return .norse
        case .vardr: return .vardr
        }
    }

    /// The name `setAlternateIconName` takes and `alternateIconName` reads; nil is the primary icon.
    var alternateName: String? {
        switch self {
        case .aurora: return nil
        case .norse: return "AppIcon-Norse"
        case .vardr: return "AppIcon-Vardr"
        }
    }

    /// The icon the phone shows, from `alternateIconName`: a name this build does not know reads Aurora.
    init(alternateName: String?) {
        self = AppIcon.allCases.first { $0.alternateName == alternateName } ?? .aurora
    }

    /// The theme the icon belongs to; its row reads the theme's label.
    var theme: AppTheme {
        switch self {
        case .aurora: return .aurora
        case .norse: return .norse
        case .vardr: return .vardr
        }
    }

    var label: String { theme.label }

    /// D-216 (Design 5): the picker's preview, the icon's own 1024 "Any" image at 144 px (Assets.xcassets, written by
    /// brand/make_icon_resources.sh; Android's `icon_preview_<theme>` is the same file byte for byte).
    var previewAsset: String {
        switch self {
        case .aurora: return "IconPreview-Aurora"
        case .norse: return "IconPreview-Norse"
        case .vardr: return "IconPreview-Vardr"
        }
    }
}

/// D-216 (Design 3 to 5): the pure decisions behind the App icon picker and the switcher (Android's twin is
/// `AppIconPlan` in Appearance.kt; both are pinned by one golden table).
enum AppIconPlan {
    /// D-234 (A2, decision 80): how long the App icon rows take to open or close: the switch's own travel when art may
    /// move (`MotionClock.momentTicks`), nil (a snap) when Less motion or Reduce Motion holds it still (D-239, decision
    /// 137: power saving never stills a moment).
    /// Android's twin is `AppIconPlan.expandMillis`.
    static func expandSeconds(moves: Bool) -> Double? {
        moves ? Double(ThemeArt.m("switchTravel")) : nil
    }

    /// The icon wanted: Match theme gives the theme's icon, any other choice gives itself.
    static func target(_ choice: AppIconChoice, theme: AppTheme) -> AppIcon {
        switch choice {
        case .match: return AppIcon.of(theme)
        case .aurora: return .aurora
        case .norse: return .norse
        case .vardr: return .vardr
        }
    }

    /// The icon to set when the person leaves Appearance, or nil when the phone already shows it (no alert then).
    static func change(target: AppIcon, current: AppIcon) -> AppIcon? {
        target == current ? nil : target
    }

    /// The row that carries the check: the icon wanted (with Match theme on, the theme's row).
    static func checked(_ choice: AppIconChoice, theme: AppTheme) -> AppIcon {
        target(choice, theme: theme)
    }

    /// What a tap on an icon row stores: that icon, so Match theme turns off.
    static func tap(_ icon: AppIcon) -> AppIconChoice {
        switch icon {
        case .aurora: return .aurora
        case .norse: return .norse
        case .vardr: return .vardr
        }
    }

    /// What the Match theme switch stores: on, Match theme; off, the theme's icon as an explicit choice (nothing
    /// visible changes).
    static func matchSwitched(on: Bool, theme: AppTheme) -> AppIconChoice {
        on ? .match : tap(AppIcon.of(theme))
    }
}

/// D-216 (Design 5): the picker's words, verbatim on both phones (Android adds one sentence to the caption; its
/// AppearanceTest reads this enum and compares).
enum AppIconCopy {
    static let title = "App icon"
    static let match = "Match theme"
    static let caption = "Your icon changes when you leave this page."

    /// D-234 (A2): the header row's value, the choice stored: Match theme, or the icon's theme label. No string literal
    /// here: Android's AppearanceTest reads every `case .x: return "..."` in this enum as a spoken name.
    static func choice(_ choice: AppIconChoice) -> String {
        switch choice {
        case .match: return AppIconCopy.match
        case .aurora: return AppIcon.aurora.label
        case .norse: return AppIcon.norse.label
        case .vardr: return AppIcon.vardr.label
        }
    }

    /// Each icon row's spoken name (the preview tile itself is decorative).
    static func spoken(_ icon: AppIcon) -> String {
        switch icon {
        case .aurora: return "Aurora icon"
        case .norse: return "Norse icon"
        case .vardr: return "Vardr Labs icon"
        }
    }
}

/// D-216 (Design 4): the one place the app changes its icon, called only when the person leaves the Appearance page
/// (`SettingsSectionScreen`'s `.onDisappear`): never at launch, never on a theme tap, never in the background. iOS shows
/// its own alert on every change and has no public way to suppress it, so a visit asks for at most one change, as the
/// answer to what the person just did there. It does nothing unless the phone can change icons and the app is in front
/// (the call needs it), and nothing when the phone already shows the icon wanted (no call, so no alert). A failure
/// shows nothing; the next leave tries again. D-244: and an arrival from the account, at the front moment only
/// (`applyArrived`). Android's twin is `AppIconSwitcher` in Appearance.kt.
@MainActor
enum AppIconSwitcher {
    static func applyIfNeeded(choice: AppIconChoice, theme: AppTheme) {
        let app = UIApplication.shared
        guard app.supportsAlternateIcons, app.applicationState == .active else { return }
        let current = AppIcon(alternateName: app.alternateIconName)
        guard let change = AppIconPlan.change(target: AppIconPlan.target(choice, theme: theme), current: current) else { return }
        app.setAlternateIconName(change.alternateName, completionHandler: nil)
    }

    /// D-244 (ADR-0043; ADR-0041's App icon section amended): a theme or icon choice that arrived from the account is
    /// never applied at arrival. It waits for the front moment (`SettingsSync.frontMoment`: SKIPTI in front and unlocked,
    /// with the arrival's pending mark), the one other caller; never in the background, never at a launch with nothing
    /// pending. True when it ran (the mark may go); false when SKIPTI is not active, so the next front moment tries.
    static func applyArrived(choice: AppIconChoice, theme: AppTheme) -> Bool {
        let active = UIApplication.shared.applicationState == .active
        if active { applyIfNeeded(choice: choice, theme: theme) }
        return active
    }
}

/// The theme rides the trait system as Night did (D-144), so every token in
/// `Tokens` resolves it the way it resolves light and dark, and sheets, alerts
/// and UIKit chrome follow without a second code path.
struct ThemeTrait: UITraitDefinition {
    static let defaultValue = AppTheme.aurora
    static let affectsColorAppearance = true
    static let name = "SkiptiTheme"
}

/// D-144's lesson: a window trait change alone does not reach SwiftUI.
/// Bridging the trait into the environment, and setting it at the root, makes
/// every SwiftUI colour re-resolve on a theme switch.
struct ThemeEnvironmentKey: EnvironmentKey {
    static let defaultValue = AppTheme.aurora
}

extension ThemeEnvironmentKey: UITraitBridgedEnvironmentKey {
    static func read(from traitCollection: UITraitCollection) -> AppTheme {
        traitCollection[ThemeTrait.self]
    }

    static func write(to mutableTraits: inout UIMutableTraits, value: AppTheme) {
        mutableTraits[ThemeTrait.self] = value
    }
}

extension EnvironmentValues {
    var appTheme: AppTheme {
        get { self[ThemeEnvironmentKey.self] }
        set { self[ThemeEnvironmentKey.self] = newValue }
    }
}

/// The live appearance. `@Observable`: `TypeRole` reads it inside every body
/// that draws text, so a theme or text-size change re-evaluates exactly those
/// bodies (as `GroupPictureStore.shared` does for pictures). Written only on
/// the main thread, by `AppearanceApplier`; tests make their own instance.
/// `@unchecked Sendable`: a background render may read it while main writes; that race is benign (a frame
/// drawn with the old value, then redrawn), because only the main thread ever writes.
@Observable
final class AppearanceState: @unchecked Sendable {
    static let shared = AppearanceState.stored()

    var theme: AppTheme = .aurora
    var mode: AppearanceMode = .system
    var textStep = 0
    var phoneCategory: UIContentSizeCategory = .large
    /// D-231: the Less motion switch as the person sees it (stored, or the low-memory default until set). Read by
    /// `MotionSetting.reducedNow()`; views read `\.motionReduced`.
    var lessMotion = false

    var contentSizeCategory: UIContentSizeCategory {
        TextSize.category(phone: phoneCategory, step: textStep)
    }

    /// Read before the first frame, so a Norse phone never flashes Aurora.
    /// D-231: `defaults` and `lowRam` are the test seam; `shared` passes the app's own.
    static func stored(_ defaults: UserDefaults = .standard, lowRam: Bool = MotionSetting.lowRamAtLaunch) -> AppearanceState {
        let state = AppearanceState()
        state.theme = AppTheme.parse(defaults.string(forKey: AppTheme.storageKey))
        state.mode = AppearanceMode.parse(defaults.string(forKey: AppearanceMode.storageKey))
        state.textStep = TextSize.parse(defaults.object(forKey: TextSize.storageKey) as? Int)
        state.lessMotion = MotionSetting.parse(stored: defaults.object(forKey: MotionSetting.storageKey) as? Bool, lowRam: lowRam)
        // `shared` can be first touched by a background render, where assumeIsolated would trap;
        // the root applier refreshes `phoneCategory` on appear anyway.
        state.phoneCategory = Thread.isMainThread ? MainActor.assumeIsolated { UIApplication.shared.preferredContentSizeCategory } : .large
        return state
    }
}

enum Appearance {
    /// Applies `state` to every window of the app.
    @MainActor
    static func apply(_ state: AppearanceState) {
        for scene in UIApplication.shared.connectedScenes {
            guard let windowScene = scene as? UIWindowScene else { continue }
            for window in windowScene.windows { apply(state, to: window) }
        }
    }

    /// D-172: System REMOVES the style override (setting `.unspecified` is
    /// itself an override and resolved as light). Step 0 removes the text
    /// size override the same way, so the phone's own size keeps following.
    @MainActor
    static func apply(_ state: AppearanceState, to window: UIWindow) {
        let style = state.mode.interfaceStyle(theme: state.theme)
        if style == .unspecified {
            window.traitOverrides.remove(UITraitUserInterfaceStyle.self)
        } else {
            window.traitOverrides.userInterfaceStyle = style
        }
        window.traitOverrides[ThemeTrait.self] = state.theme
        if state.textStep == 0 {
            window.traitOverrides.remove(UITraitPreferredContentSizeCategory.self)
        } else {
            window.traitOverrides.preferredContentSizeCategory = state.contentSizeCategory
        }
        // System controls (toggles, sliders, pickers) take the theme's accent,
        // not the asset catalog's Aurora one.
        window.tintColor = Tokens.uiColor("accent")
        TitleStyle.apply(state, to: window)
    }
}

/// Applies the stored appearance when the root appears, and again whenever a
/// setting changes (the Appearance page writes the same keys) or the phone's
/// own text size changes. D-244: an arrival from the account writes the same keys (`SettingsSync.applyFromAccount`), so
/// it shows at once on every window, without a relaunch.
struct AppearanceApplier: ViewModifier {
    @AppStorage(AppTheme.storageKey) private var themeRaw = AppTheme.aurora.rawValue
    /// D-232: nil until the person chooses (or the launch migration stores `system`); `AppearanceMode.parse` decides.
    @AppStorage(AppearanceMode.storageKey) private var modeRaw: String?
    @AppStorage(TextSize.storageKey) private var textStep = 0
    /// D-231: the Less motion switch as stored (nil until the person sets it).
    @AppStorage(MotionSetting.storageKey) private var lessMotionStored: Bool?
    /// D-231 (decision 71): the phone's Reduce Motion, read here alone; the root hands every view `\.motionReduced`.
    @Environment(\.accessibilityReduceMotion) private var systemReduceMotion
    /// D-214 (F1): the bars' styling is made per scheme (iOS 17 to 25's back chevron is a raster in the colour of the
    /// moment), so it is made again when the phone flips light and dark while the mode is System.
    @Environment(\.colorScheme) private var colorScheme

    func body(content: Content) -> some View {
        content
            .environment(\.appTheme, AppTheme.parse(themeRaw))
            // D-215: a Text that sets no font takes the theme's body face, not the system's. `TypeRole.body()` reads
            // the observable appearance, so a theme or text-size change re-evaluates this body.
            .font(TypeRole.body())
            .tint(Tokens.accent)
            .environment(\.motionReduced, MotionSetting.reduced(system: systemReduceMotion, lessMotion: lessMotion))
            .onAppear { sync() }
            .onChange(of: themeRaw) { sync() }
            .onChange(of: modeRaw) { sync() }
            .onChange(of: textStep) { sync() }
            .onChange(of: lessMotionStored) { sync() }
            .onChange(of: colorScheme) { sync() }
            .onReceive(NotificationCenter.default.publisher(for: UIContentSizeCategory.didChangeNotification)) { _ in
                sync()
            }
    }

    /// D-231: the switch as the person sees it: stored, or on by itself on a low-memory phone until set.
    private var lessMotion: Bool { MotionSetting.parse(stored: lessMotionStored, lowRam: MotionSetting.lowRamAtLaunch) }

    @MainActor
    private func sync() {
        let state = AppearanceState.shared
        state.theme = AppTheme.parse(themeRaw)
        state.mode = AppearanceMode.parse(modeRaw)
        state.textStep = TextSize.parse(textStep)
        state.lessMotion = lessMotion
        state.phoneCategory = UIApplication.shared.preferredContentSizeCategory
        Appearance.apply(state)
    }
}

extension View {
    func appliesStoredAppearance() -> some View { modifier(AppearanceApplier()) }
}

/// D-232 (decisions 73, 74; ADR-0042): a new install opens in Dark; an install that ran before and never chose keeps
/// following the phone. `settle()` is the FIRST statement of `SkiptiApp.init` (AppearanceTests holds it there), before
/// Firebase, the emulator sign-in and any read of the mode, and it runs once per install: the settled mark stops it.
enum AppearanceDefault {
    /// The vault the launch opens (`SkiptiApp.loadGate`, `LedgerStore.defaultStore()`). Only whether the file exists is
    /// asked; nothing reads it, and nothing here creates the folder.
    static func defaultVaultPath() -> String {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("ledger", isDirectory: true)
            .appendingPathComponent("ledger-vault.db").path
    }

    /// Once per install: stores what `AppearanceMode.migrate` asks for (`system` for an install that ran before and
    /// never chose) with the migrated mark (D-244 M3: not a choice, so it never fills the account), then the mark. A brand-new install stores only the mark, so its mode stays nil and reads Dark, and
    /// its later launches (when the vault exists) change nothing.
    static func settle(_ defaults: UserDefaults = .standard, vaultPath: String = AppearanceDefault.defaultVaultPath()) {
        guard !defaults.bool(forKey: AppearanceMode.settledKey) else { return }
        if let value = AppearanceMode.migrate(stored: defaults.string(forKey: AppearanceMode.storageKey),
                                              vaultExists: FileManager.default.fileExists(atPath: vaultPath)) {
            defaults.set(value, forKey: AppearanceMode.storageKey)
            defaults.set(true, forKey: AppearanceMode.migratedKey)
        }
        defaults.set(true, forKey: AppearanceMode.settledKey)
    }
}
