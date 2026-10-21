import SwiftUI

/// D-128: iOS's product shell, the twin of Android's `HomeShell`.
///
/// Until this unit the post-onboarding root was `SmokeScreenView`, a
/// developer menu of NavigationLinks, so a person finishing the Recovery
/// Kit landed on "Skipti smoke" and a list of vault round-trip checks. The
/// founder saw exactly that on the simulator. Android has had a designed
/// shell since D-113; D-126 recorded iOS's absence as the largest product
/// gap on the platform.
///
/// THE SHAPE, twinned rather than reinvented (Android `AppRoot.kt`,
/// `HomeTab`). D-179 (the founder's canvas, 2026-09-26): Balance · Groups ·
/// a raised + · Expenses · Settle up, with the same Lucide drawings, on
/// SKIPTI's own glass bar (`SkiptiTabBar`); the system bar is hidden. The
/// + opens Add as a sheet. The account surface is still NOT a tab (D-126):
/// it is a glyph in every tab's toolbar that pushes `AccountHubScreen`.
///
/// - Each tab owns its own NavigationStack, so pushing Account from Balance
///   never leaks a back button into Expenses: tabs are roots, exactly
///   Android's "roots, not a back stack" rule.
/// - A save on Add closes the sheet on Balance so the recorded result is
///   visible, matching Android.
enum HomeTab: String, CaseIterable, Identifiable {
    case balance
    case expenses
    case groups
    case settle

    var id: Self { self }

    /// D-179 (the founder's canvas, 2026-09-26), reordered by the founder on
    /// 2026-09-29 (D-180): Balance · Expenses · the raised + · Groups ·
    /// Settle up. Android's `HomeTab` labels,
    /// verbatim; the D-179 tests pin both.
    var label: String {
        switch self {
        case .balance: return "Balance"
        case .groups: return "Groups"
        case .expenses: return "Expenses"
        case .settle: return "Settle up"
        }
    }

    /// The + between Groups and Expenses is not a tab: it opens Add.
    static let addLabel = "Add an expense"

    var glyph: LucideGlyph {
        switch self {
        case .balance: return .wallet
        case .groups: return .users
        case .expenses: return .receipt
        case .settle: return .circleCheck
        }
    }

    /// D-213: Norse's tab icons (the spec's glyph table).
    var rune: String {
        switch self {
        case .balance: return Runes.balance
        case .expenses: return Runes.expenses
        case .groups: return Runes.groups
        case .settle: return Runes.settle
        }
    }
}

struct HomeShell: View {
    /// SceneStorage, so the selected tab survives the system reclaiming the
    /// app in the background (Android's `rememberSaveable`).
    @SceneStorage("home.tab") private var selectedRaw = HomeTab.balance.rawValue

    private var selection: Binding<HomeTab> {
        Binding(
            get: { HomeTab(rawValue: selectedRaw) ?? .balance },
            set: { selectedRaw = $0.rawValue }
        )
    }

    /// Bumped after joining, so every tab reads the ledger again.
    @State private var generation = 0
    /// D-205: bumped to rebuild one tab's stack at its root (a re-tap).
    @State private var roots: [HomeTab: Int] = [:]
    /// D-179: a proof launch may open on a given tab.
    var startOn: HomeTab?

    /// D-179: the + opens Add as a sheet, a fresh form every time (D-172's
    /// complaint, a saved expense still on the form, cannot come back).
    @State private var adding = false
    /// D-245 (U9): the welcome's "Start a group" opens New group over Balance.
    @State private var startingGroup = false
    /// D-248 (U30.6): the group the welcome's sheet made, opened once it has gone.
    @State private var startedGroupID: String?
    /// D-180: where the bar shows (see ShellChrome).
    @State private var chrome = ShellChrome()
    @Environment(\.motionReduced) private var reduceMotion

    var body: some View {
        // D-180: a ZStack of the four stacks, one shown at a time, instead of
        // a TabView. With the system bar hidden, TabView's UIKit container
        // never passed the custom bar's inset to a tab's scroll views: the
        // last rows sat under the bar and could not scroll clear of it.
        // Every stack stays alive, so each tab keeps its place.
        ZStack {
            NavigationStack {
                // D-180: every group at once; a group opens its page.
                OverviewScreen(root: roots[.balance, default: 0]) { selectedRaw = HomeTab.settle.rawValue }
                    .shellRoot(.balance, chrome)
                    .safeAreaInset(edge: .top, spacing: 0) { banner }
                    // D-178: pull to sync (the founder, 2026-09-29).
                    .refreshable { await SyncStatus.shared.syncNow() }
                    .toolbar { accountItem }
            }
            .id(roots[.balance, default: 0])
            .environment(\.shellTab, .balance)
            .tabPage(selection.wrappedValue == .balance)

            NavigationStack {
                GroupsScreen(store: LedgerStore.defaultStore(), onOpen: {
                    selectedRaw = HomeTab.balance.rawValue
                    // Every screen reads the chosen group again.
                    SyncStatus.shared.changed()
                }, onCreated: { id in
                    // D-248 (U30.6): the new group's page on Home's stack, from Home's root (Back lands on Home).
                    selectedRaw = HomeTab.balance.rawValue
                    roots[.balance, default: 0] += 1
                    // For the rebuilt Home alone (review M7): the old one, still on screen this turn, leaves it.
                    GroupOpening.shared.request(id, root: roots[.balance, default: 0])
                })
                .shellRoot(.groups, chrome)
                .safeAreaInset(edge: .top, spacing: 0) { banner }
                // D-178: pull to sync (the founder, 2026-09-29).
                .refreshable { await SyncStatus.shared.syncNow() }
                .toolbar { accountItem }
            }
            .id(roots[.groups, default: 0])
            .environment(\.shellTab, .groups)
            .tabPage(selection.wrappedValue == .groups)

            NavigationStack {
                ExpenseListView()
                    .shellRoot(.expenses, chrome)
                    .safeAreaInset(edge: .top, spacing: 0) { banner }
                    // D-178: pull to sync (the founder, 2026-09-29).
                    .refreshable { await SyncStatus.shared.syncNow() }
                    .toolbar { accountItem }
            }
            .id(roots[.expenses, default: 0])
            .environment(\.shellTab, .expenses)
            .tabPage(selection.wrappedValue == .expenses)

            NavigationStack {
                // D-182: every group at once (the founder's canvas).
                SettleAllScreen()
                    .shellRoot(.settle, chrome)
                    .safeAreaInset(edge: .top, spacing: 0) { banner }
                    // D-178: pull to sync (the founder, 2026-09-29).
                    .refreshable { await SyncStatus.shared.syncNow() }
                    .toolbar { accountItem }
            }
            .id(roots[.settle, default: 0])
            .environment(\.shellTab, .settle)
            .tabPage(selection.wrappedValue == .settle)
        }
        .id(generation)
        // D-224 (Design 6, GUARANTEES #2): a big done moment dims the tab content and draws its check over it, both
        // BEFORE the bar's overlay, so the bar and the + are never dimmed or covered; neither takes a touch.
        .modifier(DoneDim(center: DoneCenter.shared))
        .overlay { DoneOverlay(center: DoneCenter.shared) }
        .tint(Tokens.accent)
        // D-179: SKIPTI's own bar, with the raised +.
        .environment(\.shellChrome, chrome)
        // D-210: a request card's "Settle up" opens that tab from a page pushed over another.
        .environment(\.openSettleTab, { selectedRaw = HomeTab.settle.rawValue })
        // D-180: over the stacks, not an inset of them (see ShellChrome);
        // hidden on a pushed page unless that page keeps it.
        .overlay(alignment: .bottom) {
            if chrome.showsBar(on: selection.wrappedValue) {
                SkiptiTabBar(selection: selection, onTap: tapped) { adding = true }
                    .transition(reduceMotion ? .identity : .move(edge: .bottom).combined(with: .opacity))
            }
        }
        .animation(reduceMotion ? nil : .easeInOut(duration: 0.2), value: chrome.showsBar(on: selection.wrappedValue))
        // D-224 (Design 5): the small done moment, clear of the bar and the + (never under them, never dimmed).
        .overlay(alignment: .bottom) {
            DoneToastHost(center: DoneCenter.shared, barShown: chrome.showsBar(on: selection.wrappedValue))
        }
        .sheet(isPresented: $adding) {
            NavigationStack {
                // D-249: the + over Your spending opens on Just me.
                AddExpenseScreen(onSaved: {
                    adding = false
                    selectedRaw = HomeTab.balance.rawValue
                    // Balance, under the sheet, reads the new expense.
                    SyncStatus.shared.changed()
                }, startOnJustMe: SpendingPresence.shared.isShown(on: selection.wrappedValue))
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("Cancel") { adding = false }.barButtonText().barTint()
                    }
                }
            }
        }
        // D-245 (U9): New group from the welcome's last screen, over Balance at its root.
        .sheet(isPresented: $startingGroup, onDismiss: {
            // D-248 (U30.6): the new group's page, once the sheet has gone.
            if let id = startedGroupID { startedGroupID = nil; GroupOpening.shared.request(id, root: roots[.balance, default: 0]) }
        }) {
            NavigationStack {
                let store = LedgerStore.defaultStore()
                CreateGroupSheet(store: store, needsYourName: (try? store.ensureSelf())?.name == nil) { created in
                    startedGroupID = created.group.id
                    startingGroup = false
                    OverviewActions.changed()
                }
            }
        }
        .onReceive(NotificationCenter.default.publisher(for: .skiptiStartGroup)) { _ in
            selectedRaw = HomeTab.balance.rawValue
            roots[.balance, default: 0] += 1
            startingGroup = true
        }
        // D-187: group pictures, read again on every sync.
        .task(id: SyncStatus.shared.revision) {
            GroupPictureStore.shared.load(LedgerStore.defaultStore())
        }
        .onAppear { if let startOn { selectedRaw = startOn.rawValue } }
        // U27: a deleted group's page closes with its settings: the tab that pushed them goes back to its root.
        .onReceive(NotificationCenter.default.publisher(for: .skiptiShowTabRoot)) { note in
            guard let tab = note.object as? HomeTab else { return }
            if reduceMotion {
                roots[tab, default: 0] += 1
            } else {
                withAnimation(.easeInOut(duration: 0.2)) { roots[tab, default: 0] += 1 }
            }
        }
        // D-166: after an import, every tab reads the ledger again, on
        // Balance, where the new group is now selected.
        .onReceive(NotificationCenter.default.publisher(for: .skiptiLedgerImported)) { _ in
            selectedRaw = HomeTab.balance.rawValue
            generation += 1
        }
    }

    /// D-205: a tap on the bar (TabTap): switch, back to the root, or to the top.
    private func tapped(_ tab: HomeTab) {
        switch TabTap.decide(selected: selection.wrappedValue, tapped: tab, covered: chrome.covered) {
        case .switchTo(let next):
            selectedRaw = next.rawValue
            // D-224 (Design 3): a tab switch taps lightly; a re-tap (to the root, to the top) does not.
            Haptics.play(.light)
        case .popToRoot(let shown):
            if reduceMotion {
                roots[shown, default: 0] += 1
            } else {
                withAnimation(.easeInOut(duration: 0.2)) { roots[shown, default: 0] += 1 }
            }
        case .scrollToTop(let shown):
            chrome.scrollTop[shown, default: 0] += 1
        }
    }

    /// The slot above every tab's content, under its navigation bar (D-160
    /// put invites here). Empty since D-192 removed the relay's banners; the
    /// Firebase invites (plan unit 5) come back here.
    private var banner: some View { Color.clear.frame(height: 0) }

    /// D-140: the account glyph on EVERY tab (the founder, 2026-09-19: "for
    /// convenience"), not only Balance. D-139: on iOS 27 it is PINNED, so it
    /// can never move into the overflow menu however the bar fills; earlier
    /// systems keep .topBarTrailing. One gate around the whole body, the
    /// shape Apple's iOS 27 toolbar reference prescribes.
    @ToolbarContentBuilder
    private var accountItem: some ToolbarContent {
        // D-192: the D-189 bell is gone (push notifications replace it).
        if #available(iOS 27, *) {
            ToolbarItem(placement: .topBarPinnedTrailing) { accountLink.barTint() }
        } else {
            ToolbarItem(placement: .topBarTrailing) { accountLink.barTint() }
        }
    }

    /// GUARANTEES #5: a glyph, not a prompt. A user with no account is never
    /// told to get one.
    private var accountLink: some View {
        NavigationLink {
            AccountHubScreen(vaultPath: LedgerStore.defaultStore().path)
        } label: {
            // D-145: your picture (or initials) once you have one.
            ProfileBadge(store: LedgerStore.defaultStore(), size: 30, original: true)
        }
        // D-144: the page it opens is Settings (appearance, account, your data).
        .accessibilityLabel("Settings")
    }
}

private extension View {
    /// D-180: one tab of the shell: shown, or kept alive but hidden,
    /// untappable and silent to VoiceOver.
    func tabPage(_ shown: Bool) -> some View {
        opacity(shown ? 1 : 0)
            .allowsHitTesting(shown)
            .accessibilityHidden(!shown)
            .zIndex(shown ? 1 : 0)
            // D-214: a tab kept alive but hidden does not tick its art.
            .environment(\.artVisible, shown)
    }
}
