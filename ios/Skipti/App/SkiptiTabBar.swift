import SwiftUI

/// D-179: SKIPTI's own tab bar (the founder's canvas, 2026-09-26): Balance ·
/// Expenses · a raised + · Groups · Settle up (D-180). The selected
/// tab pops (a spring scale and a mark behind its glyph; no motion
/// under Reduce Motion). The + is not a tab: it opens Add as a sheet.
/// D-213: each theme's icons, active mark and + (ThemeArt.swift).
/// D-219: the ground is frosted glass in Aurora only (Norse and Vardr Labs: the solid surface), a hairline on top in
/// every theme, Aurora's active mark is a filled accent pill, and the + sits in one footprint in every theme.
/// Android's twin is `SkiptiTabBar` in AppRoot.kt.
struct SkiptiTabBar: View {
    @Binding var selection: HomeTab
    /// D-205: the shell decides what a tap does (TabTap).
    var onTap: (HomeTab) -> Void
    var onAdd: () -> Void


    @Environment(\.motionReduced) private var reduceMotion
    @Environment(\.dynamicTypeSize) private var typeSize
    @Environment(\.colorScheme) private var colorScheme

    /// Past this size the labels would clip; the name moves into the
    /// accessibility label (Android's 1.15x rule, twinned).
    private var showsLabels: Bool { typeSize < .accessibility1 }

    var body: some View {
        HStack(alignment: .bottom, spacing: 0) {
            // D-180: Expenses and Groups swapped (the founder, 2026-09-29).
            item(.balance)
            item(.expenses)
            addButton
            item(.groups)
            item(.settle)
        }
        .padding(.horizontal, 6)
        .padding(.top, 6)
        .background {
            ground
                .overlay(alignment: .top) { Rectangle().fill(Tokens.hairline).frame(height: 1) }
                .ignoresSafeArea(edges: .bottom)
        }
    }

    /// D-219 (L15): the bar's ground per theme. Glass keeps the material under the surface film; solid is the surface.
    @ViewBuilder
    private var ground: some View {
        switch TabBarGround.of(AppearanceState.shared.theme) {
        case .glass:
            Rectangle().fill(.ultraThinMaterial).overlay(Tokens.surface.opacity(TabBarGround.filmAlpha))
        case .solid:
            Rectangle().fill(Tokens.surface)
        }
    }

    /// D-219 (L13): the active glyph's colour: onAccent on Aurora's filled pill, the gold on Norse, the ring on Vardr Labs.
    private func activeTint(_ theme: AppTheme) -> Color {
        switch TabIndicator.activeGlyph(theme) {
        case .onAccent: return Tokens.onAccent
        case .accent: return Tokens.accent
        case .ring: return Tokens.ring
        }
    }

    private func item(_ tab: HomeTab) -> some View {
        let on = selection == tab
        let theme = AppearanceState.shared.theme
        let indicator = TabIndicator.of(theme)
        return Button {
            onTap(tab)
        } label: {
            VStack(spacing: 3) {
                icon(tab, theme: theme)
                    .foregroundStyle(on ? activeTint(theme) : Tokens.textDim)
                    .frame(width: 52, height: 30)
                    .background { mark(indicator, on: on) }
                    .scaleEffect(on && !reduceMotion ? 1.08 : 1)
                    .animation(reduceMotion ? nil : .spring(response: 0.32, dampingFraction: 0.55), value: on)
                if showsLabels {
                    Text(verbatim: tab.label)
                        .font(TypeRole.tabLabel(11))
                        .foregroundStyle(on ? Tokens.text : Tokens.textDim)
                        .lineLimit(1)
                }
            }
            .frame(maxWidth: .infinity, minHeight: 50)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Text(verbatim: tab.label))
        .accessibilityAddTraits(on ? .isSelected : [])
    }

    /// D-213: Lucide in Aurora and Vardr Labs, the tab's rune in Norse (Vardr Runes Carved).
    @ViewBuilder
    private func icon(_ tab: HomeTab, theme: AppTheme) -> some View {
        if TabIndicator.drawsRunes(theme) {
            Text(verbatim: tab.rune).font(TypeRole.rune(ThemeArt.m("tabRune"))).accessibilityHidden(true)
                .offset(y: ThemeArt.tabRuneInkShift)
        } else {
            LucideIcon(glyph: tab.glyph, size: 22)
        }
    }

    /// D-213: Aurora's accent pill (D-219: filled accent); Norse's 2 pt gold underline; Vardr Labs' square outline in the ring colour.
    @ViewBuilder
    private func mark(_ indicator: TabIndicator, on: Bool) -> some View {
        switch indicator {
        case .pill:
            Capsule().fill(on ? Tokens.accent : Color.clear)
        case .underline:
            if on { VStack(spacing: 0) { Spacer(minLength: 0); Rectangle().fill(Tokens.accent).frame(height: 2) } }
        case .outline:
            if on {
                Rectangle().strokeBorder(Tokens.ring.opacity(TabIndicator.outlineAlpha(currentSet)), lineWidth: 1)
            }
        }
    }

    private var currentSet: ThemeSet {
        ThemeSet.resolve(AppearanceState.shared.theme, dark: colorScheme == .dark)
    }

    private var addButton: some View {
        Button(action: onAdd) {
            AddButtonFace(art: AddButtonArt.of(AppearanceState.shared.theme))
        }
        .buttonStyle(RaisedAddStyle(reduceMotion: reduceMotion, art: AddButtonArt.of(AppearanceState.shared.theme)))
        .offset(y: -AddButtonGeometry.lift)
        .frame(maxWidth: .infinity)
        // D-219 fix wave (I-1): in front of the neighbour tabs, so a face that overflows its 64 footprint wins its taps.
        .zIndex(1)
        .accessibilityLabel(Text(verbatim: HomeTab.addLabel))
    }
}

/// The + springs down a little under the finger.
private struct RaisedAddStyle: ButtonStyle {
    let reduceMotion: Bool
    let art: AddButtonArt

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            // D-214: the hex bolt turns 30 degrees while pressed (Vardr Labs) on its own spring, scoped to the turn
            // (the inner animation governs the rotation; the press's scale keeps its spring below); never under Reduce Motion.
            .rotationEffect(.degrees(AddButtonMotion.turn(art: art, pressed: configuration.isPressed, reduceMotion: reduceMotion)))
            .animation(reduceMotion ? nil : .spring(response: AddButtonMotion.turnResponse, dampingFraction: AddButtonMotion.turnDamping),
                       value: configuration.isPressed)
            .scaleEffect(configuration.isPressed && !reduceMotion ? 0.9 : 1)
            .animation(reduceMotion ? nil : .spring(response: 0.25, dampingFraction: 0.5), value: configuration.isPressed)
    }
}

// MARK: - D-180: where the bar shows, and the room it takes

/// The bar sits over each tab's NavigationStack, and neither a TabView's nor a
/// ZStack's inset ever reached the scroll views inside a stack (seen on the
/// iOS 27 simulator: the last rows could not scroll clear of the bar, and
/// padding the stack from outside changed nothing; padding the scroll view
/// itself did). So a tab's ROOT reserves the room itself, a page pushed on
/// top hides the bar (iOS's own pattern for detail pages, and a pushed page's
/// bottom buttons can then never sit under it), and a group's page keeps it,
/// as the founder's canvas draws it.
@Observable @MainActor
final class ShellChrome {
    /// Tabs whose root has a page pushed over it.
    var covered: Set<HomeTab> = []
    /// Pushed pages that keep the bar, per tab.
    var pinned: [HomeTab: Int] = [:]
    /// D-205: bumped when a tab's root is tapped again, to scroll it to the top.
    var scrollTop: [HomeTab: Int] = [:]

    func showsBar(on tab: HomeTab) -> Bool {
        !covered.contains(tab) || (pinned[tab] ?? 0) > 0
    }

    /// D-234 (L6): each tab root's live instance. When the shell rebuilds its stacks (a join or an import bumps
    /// HomeShell's `generation`; a re-tap rebuilds one stack) SwiftUI calls the NEW root's `onAppear` before the OLD
    /// root's `onDisappear`; the old root's late call is ignored, so it can never cover its tab.
    @ObservationIgnored private var liveRoot: [HomeTab: UUID] = [:]

    func rootAppeared(_ tab: HomeTab, token: UUID) {
        liveRoot[tab] = token
        covered.remove(tab)
    }

    func rootDisappeared(_ tab: HomeTab, token: UUID) {
        guard liveRoot[tab] == token else { return }
        covered.insert(tab)
    }
}

/// D-205 (the founder, 2026-10-13): what a tap on a tab does. UIKit's own
/// tab bar rule, which SKIPTI's custom bar (D-179) had lost: the tab you are
/// in goes back to its first screen, and there a tap scrolls to the top.
/// `covered` is ShellChrome's set of tabs with a page pushed over the root.
/// Android's twin is TabTap in TabBar.kt.
enum TabTap: Equatable {
    case switchTo(HomeTab)
    case popToRoot(HomeTab)
    case scrollToTop(HomeTab)

    static func decide(selected: HomeTab, tapped: HomeTab, covered: Set<HomeTab>) -> TabTap {
        guard tapped == selected else { return .switchTo(tapped) }
        return covered.contains(tapped) ? .popToRoot(tapped) : .scrollToTop(tapped)
    }
}

/// The room a page keeps clear above the bar's own safe area: the items'
/// height and the raised +'s overhang.
enum ShellBar {
    static let reserved: CGFloat = 72
    /// D-205: the id a root's scroll content carries, for scrolling to the top.
    static let top = "shell.top"
}

private struct ShellTabKey: EnvironmentKey { static let defaultValue: HomeTab? = nil }
private struct ShellChromeKey: EnvironmentKey { static let defaultValue: ShellChrome? = nil }

extension EnvironmentValues {
    var shellTab: HomeTab? {
        get { self[ShellTabKey.self] }
        set { self[ShellTabKey.self] = newValue }
    }
    var shellChrome: ShellChrome? {
        get { self[ShellChromeKey.self] }
        set { self[ShellChromeKey.self] = newValue }
    }
}

private struct ShellRoot: ViewModifier {
    let tab: HomeTab
    let chrome: ShellChrome
    /// D-234 (L6): this root instance's token (`ShellChrome.rootAppeared`).
    @State private var token = UUID()

    func body(content: Content) -> some View {
        content
            .safeAreaPadding(.bottom, ShellBar.reserved)
            .onAppear { chrome.rootAppeared(tab, token: token) }
            .onDisappear { chrome.rootDisappeared(tab, token: token) }
    }
}

private struct KeepsShellBar: ViewModifier {
    @Environment(\.shellTab) private var tab
    @Environment(\.shellChrome) private var chrome

    func body(content: Content) -> some View {
        content
            .safeAreaPadding(.bottom, chrome == nil ? 0 : ShellBar.reserved)
            .onAppear { if let tab, let chrome { chrome.pinned[tab, default: 0] += 1 } }
            .onDisappear { if let tab, let chrome { chrome.pinned[tab, default: 1] -= 1 } }
    }
}

/// D-205: a tab root's scroll content: back to the top when its tab is
/// tapped again at the root (ShellChrome.scrollTop).
private struct ShellScrollTop: ViewModifier {
    @Environment(\.shellTab) private var tab
    @Environment(\.shellChrome) private var chrome
    @Environment(\.motionReduced) private var reduceMotion

    private var signal: Int { tab.flatMap { chrome?.scrollTop[$0] } ?? 0 }

    func body(content: Content) -> some View {
        ScrollViewReader { proxy in
            content
                .id(ShellBar.top)
                .onChange(of: signal) {
                    if reduceMotion {
                        proxy.scrollTo(ShellBar.top, anchor: .top)
                    } else {
                        withAnimation(.easeInOut(duration: 0.3)) { proxy.scrollTo(ShellBar.top, anchor: .top) }
                    }
                }
        }
    }
}

extension View {
    /// D-205: the root's scroll content scrolls to the top on a re-tap.
    func shellScrollTop() -> some View { modifier(ShellScrollTop()) }

    /// A tab's root page: it keeps room for the bar.
    func shellRoot(_ tab: HomeTab, _ chrome: ShellChrome) -> some View {
        modifier(ShellRoot(tab: tab, chrome: chrome))
    }

    /// A pushed page that keeps the bar under it (a group's page).
    func keepsShellBar() -> some View { modifier(KeepsShellBar()) }
}
