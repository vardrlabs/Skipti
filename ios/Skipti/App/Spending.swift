import SwiftUI

// D-249 (U10, decisions 163 to 166, 187 to 193): "Just me" and "All my spending". A Just me expense is the ordinary
// Expense record in the personal scope (no group, you paid, shares [you: total]); every amount here is core's
// (`ffiSpending`, `ffiMoneyFormat`), and the shell only passes the phone's month bounds in ms. Android's twin is
// Spending.kt; its parity tests read this file's copy.

/// The words of Home's Your spending section, the page, the Ledger row and the Just me pieces elsewhere. Android's twin is
/// `SpendingCopy`.
enum SpendingCopy {
    static let title = "Your spending"
    static let justMe = "Just me"
    static let all = "All my spending"
    static let thisMonth = "This month"
    static func more(_ n: Int) -> String { "+\(n) more" }
    static let empty = "Nothing yet this month"
    /// The card to VoiceOver: "Your spending, Just me, four thousand ... this month".
    static func spoken(view: String, amount: String?) -> String {
        guard let amount else { return "\(title), \(view), \(empty)" }
        return "\(title), \(view), \(amount) this month"
    }
    static let hint = "Opens your spending"
    static let allNote = "Your share of group expenses, plus Just me."
    static func groupMeta(_ group: String) -> String { "\(group), your share" }
    static let prev = "Previous month"
    static let next = "Next month"
    static let spent = "Spent"
    static let chart = "Chart"
    static let table = "Table"
    static let columns = ["Category", "Spent", "Share"]
    static let expenses = "Expenses"
    static let emptyTitle = "Nothing here yet"
    /// Decision 188: no account or sync word on a screen a no-account person sees (GUARANTEES #5).
    static let emptyDetail = "Things you pay for yourself go here. Nobody owes anybody."
    static func emptyMonth(_ month: String) -> String { "Nothing in \(month)" }
    static let export = "Export Just me"
    /// You > Appearance (decision 163).
    static let homeRow = "Spending on Home"
    static let homeSummary = "This month, under your ledgers"
    /// Add expense with Just me chosen.
    static let onlyYouNothingToSplit = "Only you. Nothing to split."
    /// Decision 193: the Ledger page's meta lines.
    static let onlyYou = "Only you"
    static func since(_ monthYear: String) -> String { "Since \(monthYear)" }
    /// The Expenses tab's right column for a Just me row (was "not involved").
    static func spentAmount(_ amount: String) -> String { "spent \(amount)" }
    /// Expense detail's delete confirmation for a Just me expense.
    static let deleteMessage = "It leaves Just me. You can undo it right after."
    /// A Wallet by BudgetBakers file's two targets.
    static let importIntoJustMe = "Import into Just me"
    static let importAsGroup = "Import as a new group"
}

/// Decision 165: the hero with no group yet. Android's twin is `NoGroupHeroCopy`.
enum NoGroupHeroCopy {
    static let kicker = "No groups yet"
    static let title = "Splitting with someone?"
    static let line = "Make a group for a trip, a flat or a dinner."
    static let create = "Create a group"
    static let join = "Join"
    static let importLink = "Import"
}

/// Which hero Home draws: the balance across your groups, or (no group yet) the one that asks. Android's twin:
/// `HomeHero.kind`.
enum HomeHero: Equatable {
    case balance, noGroup

    static func kind(groupCount: Int) -> HomeHero { groupCount == 0 ? .noGroup : .balance }
}

/// Decision 189: the card's switch and "Spending on Home" are this phone's own (UserDefaults), never synced settings
/// (SettingsSync's list is an allow-list and names neither). Android's twin keeps the same keys in its preferences.
enum SpendingPrefs {
    static let viewKey = "spending.view"
    static let onHomeKey = "spending.onHome"
    static let justMeRaw = "justMe"
    static let allRaw = "all"

    /// Anything but "all" reads as Just me (the default).
    static func view(_ raw: String?) -> SpendingView { raw == allRaw ? .all : .justMe }
    static func raw(_ view: SpendingView) -> String { view == .all ? allRaw : justMeRaw }
    /// On until the person turns it off.
    static func onHome(_ stored: Bool?) -> Bool { stored ?? true }

    static func readView(_ defaults: UserDefaults = .standard) -> SpendingView { view(defaults.string(forKey: viewKey)) }
    static func write(_ view: SpendingView, _ defaults: UserDefaults = .standard) { defaults.set(raw(view), forKey: viewKey) }
    static func readOnHome(_ defaults: UserDefaults = .standard) -> Bool { onHome(defaults.object(forKey: onHomeKey) as? Bool) }

    /// Decision 206: the view the page opens in. From the Expenses tab's Just me header it is always Just me, and
    /// opening it there writes nothing (the card keeps the remembered view); from the card it is the remembered one.
    /// A tap on the page's switch is remembered as anywhere else.
    static func pageStart(fromJustMeHeader: Bool, stored: String?) -> SpendingView {
        fromJustMeHeader ? .justMe : view(stored)
    }
}

extension SpendingView {
    var label: String { self == .all ? SpendingCopy.all : SpendingCopy.justMe }
}

/// A calendar month in the phone's own time zone (Gregorian), its bounds in ms for core ([from, until)), and its words.
/// The month names are fixed English, as every other word in the app, so both phones print the same.
struct SpendingMonth: Hashable {
    let year: Int
    /// 1 to 12.
    let month: Int

    static let names = ["January", "February", "March", "April", "May", "June", "July", "August", "September", "October",
                        "November", "December"]

    static func calendar(_ timeZone: TimeZone) -> Calendar {
        var c = Calendar(identifier: .gregorian)
        c.timeZone = timeZone
        return c
    }

    static func containing(ms: UInt64, timeZone: TimeZone = .current) -> SpendingMonth {
        let parts = calendar(timeZone).dateComponents([.year, .month], from: Date(timeIntervalSince1970: Double(ms) / 1000))
        return SpendingMonth(year: parts.year ?? 1970, month: parts.month ?? 1)
    }

    static func current(timeZone: TimeZone = .current) -> SpendingMonth { containing(ms: RelativeWhen.nowMs, timeZone: timeZone) }

    func adding(_ months: Int) -> SpendingMonth {
        let index = year * 12 + (month - 1) + months
        let y = index >= 0 ? index / 12 : (index - 11) / 12
        return SpendingMonth(year: y, month: index - y * 12 + 1)
    }

    /// The first instant of the month and of the next, in ms (a month before 1970 starts at 0).
    func bounds(timeZone: TimeZone = .current) -> (fromMs: UInt64, untilMs: UInt64) {
        func start(_ m: SpendingMonth) -> UInt64 {
            let date = Self.calendar(timeZone).date(from: DateComponents(year: m.year, month: m.month, day: 1)) ?? Date(timeIntervalSince1970: 0)
            return UInt64(max(0, (date.timeIntervalSince1970 * 1000).rounded()))
        }
        return (start(self), start(adding(1)))
    }

    /// "October".
    var name: String { Self.names[(month - 1 + 12) % 12] }
    /// "October 2026".
    var label: String { "\(name) \(year)" }

    static func < (a: SpendingMonth, b: SpendingMonth) -> Bool { (a.year, a.month) < (b.year, b.month) }
}

/// Decision 193: a ledger's meta line on the Ledger page. A group: "Since {Month Year}" from its create record's time
/// (core's projection `created`, `millis:counter:device`) and its people; Just me: "Only you".
enum LedgerMeta {
    /// The create record's ms, read from core's `created` identity; nil when it does not parse.
    static func sinceMs(created: String) -> UInt64? {
        guard let first = created.split(separator: ":").first else { return nil }
        return UInt64(first)
    }

    static func groupParts(sinceMs: UInt64?, people: Int, timeZone: TimeZone = .current) -> [String] {
        let since = sinceMs.map { SpendingCopy.since(SpendingMonth.containing(ms: $0, timeZone: timeZone).label) }
        return [since, GroupCopy.people(people)].compactMap { $0 }
    }

    static let justMeParts = [SpendingCopy.onlyYou]
}

/// One read of Your spending for a month and a view: core's `ffiSpending` over the live expenses (core's projection,
/// deletions out), and whether the view has anything in any month (the page's "Nothing here yet").
enum SpendingLoad {
    struct Loaded: Equatable {
        var out: SpendingOut
        var hasAny: Bool
        /// Group id to its name, for "{group}, your share".
        var groupNames: [String: String]
    }

    /// The live expenses as core's spending inputs: the row keeps the leaf, the slice counts under its parent (as the
    /// group page's `ffiGroupStats`).
    static func inputs(_ live: [ProjectedExpenseOut], tree: CategoryTree) -> [SpendingExpenseIn] {
        live.filter { !$0.deleted }.map {
            SpendingExpenseIn(id: $0.id, expense: $0.expense, note: $0.note, category: $0.category,
                              sliceCategory: CategoryLevels.parent(of: $0.category, in: tree),
                              timestampMs: $0.timestampMs, addedAtMs: $0.addedAtMs)
        }
    }

    /// Decision 205: who you are in each group (its `selfId`, as the Balance tab reads it), so All counts a group
    /// expense's share only when it is yours in THAT group. One entry per group, in order; Just me needs none.
    static func groups(named: [NamedGroupWire]) -> [SpendingGroupIn] {
        var out: [SpendingGroupIn] = []
        for g in named where !out.contains(where: { $0.groupId == g.group.id }) {
            out.append(SpendingGroupIn(groupId: g.group.id, viewer: g.selfId))
        }
        return out
    }

    static func load(_ store: LedgerStore, month: SpendingMonth, view: SpendingView,
                     timeZone: TimeZone = .current) throws -> Loaded {
        let live = try store.projectedExpenses().expenses
        let named: [NamedGroupWire]
        do {
            named = try store.namedGroups()
        } catch LedgerStore.GroupStoreError.needsYourName {
            named = []
        }
        let inputs = inputs(live, tree: CategoryLevels.tree)
        let yours = groups(named: named)
        let bounds = month.bounds(timeZone: timeZone)
        let out = try ffiSpending(expenses: inputs, groups: yours, fromMs: bounds.fromMs, untilMs: bounds.untilMs, view: view)
        let ever = out.rows.isEmpty
            ? !(try ffiSpending(expenses: inputs, groups: yours, fromMs: 0, untilMs: UInt64.max, view: view)).rows.isEmpty
            : true
        return Loaded(out: out, hasAny: ever,
                      groupNames: Dictionary(named.map { ($0.group.id, $0.group.name) }, uniquingKeysWith: { a, _ in a }))
    }
}

/// The page on screen, per tab: the + opens Add expense on Just me from it (plan section 3).
@MainActor
final class SpendingPresence {
    static let shared = SpendingPresence()
    private(set) var tabs: Set<HomeTab> = []

    func shown(_ tab: HomeTab?) { if let tab { tabs.insert(tab) } }
    func gone(_ tab: HomeTab?) { if let tab { tabs.remove(tab) } }
    func isShown(on tab: HomeTab) -> Bool { tabs.contains(tab) }
}

// MARK: - Views

/// Decision 164: the spending tile: ᛁ (Isa) in Vardr Runes Carved in Norse, on the person tile's diamond; your picture,
/// or your initial, elsewhere (Vardr Runes is Norse-only).
struct SpendingTile: View {
    static let rune = "\u{16C1}"
    let name: String?
    let avatar: Avatar
    var size: CGFloat = ThemeArt.m("ledgerTile")

    var body: some View {
        let theme = AppearanceState.shared.theme
        if theme == .norse {
            let kind = TileShape.of(theme, person: true)
            let side = TileClip.drawnSide(kind, size: size)
            Text(verbatim: Self.rune)
                .font(TypeRole.rune(size * 0.62))
                .foregroundStyle(Tokens.accent)
                .frame(width: side, height: side)
                .background(Tokens.accentSoft, in: TileClip.shape(kind, size: size))
                .overlay { TileClip.edge(kind) }
                .frame(width: size, height: size)
                .accessibilityHidden(true)
        } else {
            // Your picture, your initial, or the person glyph, on the soft fill (the accent is the account badge's alone).
            AvatarView(avatar: avatar, name: name, size: size)
        }
    }
}

/// The Just me / All my spending switch (one stored choice, card and page): the chosen half filled in the accent, the
/// other in the dim, on the theme's pill. `compact` is the card's; the page's takes the full width.
struct SpendingSwitch: View {
    @Binding var view: SpendingView
    var compact = false

    var body: some View {
        let shape = ThemeArt.pillShape(AppearanceState.shared.theme)
        HStack(spacing: 0) {
            ForEach([SpendingView.justMe, .all], id: \.self) { option in
                let chosen = option == view
                Button { view = option } label: {
                    Text(verbatim: option.label)
                        .font(TypeRole.name(compact ? TypeScale.footnote : TypeScale.segment))
                        .foregroundStyle(chosen ? Tokens.onAccent : Tokens.textDim)
                        .lineLimit(1)
                        .minimumScaleFactor(0.8)
                        .padding(.horizontal, compact ? 10 : 8)
                        .padding(.vertical, compact ? 6 : 10)
                        .frame(maxWidth: compact ? nil : .infinity)
                        .background { if chosen { shape.fill(Tokens.accent) } }
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(chosen ? .isSelected : [])
            }
        }
        .padding(3)
        .background(Tokens.surfaceRaised, in: shape)
    }
}

/// The thin stacked bar of one currency's slices (core's basis points), in the slice tones; an empty track with none.
struct SpendingBar: View {
    let slices: [CategoryShareOut]
    var height: CGFloat = 6

    var body: some View {
        GeometryReader { geo in
            let gaps = CGFloat(max(slices.count - 1, 0)) * 2
            ZStack(alignment: .leading) {
                // The track, drawn with no slices too (the empty card's bar).
                Tokens.ringTrack
                HStack(spacing: 2) {
                    ForEach(Array(slices.enumerated()), id: \.offset) { i, c in
                        sliceColour(i).frame(width: max(2, (geo.size.width - gaps) * CGFloat(c.basisPoints) / 10_000))
                    }
                }
            }
        }
        .frame(height: height)
        .clipShape(ThemeArt.meterShape(AppearanceState.shared.theme))
        .accessibilityHidden(true)
    }
}

/// Home's Your spending card (decision 163): the tile and the switch, "This month", the largest currency's total with
/// "+{n} more", a chevron, and the thin category bar. A tap opens the page (`onOpen`; the push hangs on Home's
/// always-present container, CLAUDE.md 10.1).
struct SpendingCard: View {
    let loaded: SpendingLoad.Loaded?
    @Binding var view: SpendingView
    let name: String?
    let avatar: Avatar
    let onOpen: () -> Void

    var body: some View {
        let first = loaded?.out.currencies.first
        let more = max((loaded?.out.currencies.count ?? 0) - 1, 0)
        VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .center) {
                SpendingTile(name: name, avatar: avatar)
                    .onTapGesture(perform: onOpen)
                Spacer(minLength: 8)
                SpendingSwitch(view: $view, compact: true)
            }
            Button(action: onOpen) {
                VStack(alignment: .leading, spacing: 10) {
                    HStack(alignment: .center, spacing: 8) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(verbatim: SpendingCopy.thisMonth).microLabelFont().foregroundStyle(Tokens.textDim)
                            if let first {
                                HStack(alignment: .firstTextBaseline, spacing: 8) {
                                    Text(verbatim: RolesMoney.format(first.totalMinor, first.currencyCode) ?? Placeholder.none)
                                        .font(TypeRole.numeric(TypeScale.displayName + 4, weight: 700))
                                        .foregroundStyle(Tokens.text)
                                        .lineLimit(1).minimumScaleFactor(0.6)
                                    if more > 0 {
                                        Text(verbatim: SpendingCopy.more(more))
                                            .font(TypeRole.body(TypeScale.detail)).foregroundStyle(Tokens.textDim)
                                    }
                                }
                            } else {
                                Text(verbatim: SpendingCopy.empty)
                                    .font(TypeRole.body(TypeScale.prose)).foregroundStyle(Tokens.textDim)
                            }
                        }
                        Spacer(minLength: 0)
                        RowChevron(color: Tokens.textDim)
                    }
                    SpendingBar(slices: first?.categories ?? [])
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(Text(verbatim: SpendingCopy.spoken(
                view: view.label, amount: first.map { RolesMoney.spoken($0.totalMinor, $0.currencyCode) })))
            .accessibilityHint(Text(verbatim: SpendingCopy.hint))
            .accessibilityAddTraits(.isButton)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .glassCard()
    }
}

/// The page "Your spending": the switch, the month, Spent per currency (never converted), where the money went (Chart or
/// Table, the largest currency's slices), and the month's expenses newest first. Reached from Home's card and from the
/// Expenses tab's Just me header (decision 190). The + opens Add expense on Just me from here.
struct SpendingScreen: View {
    /// Decision 206: true when opened from the Expenses tab's Just me header.
    var fromJustMeHeader = false
    @AppStorage(SpendingPrefs.viewKey) private var viewRaw = SpendingPrefs.justMeRaw
    /// The view this visit opened in until the switch is tapped (decision 206); nil reads the remembered one.
    @State private var opened: SpendingView?
    @State private var month = SpendingMonth.current()
    @State private var loaded: SpendingLoad.Loaded?
    @State private var error: String?
    @State private var table = false
    @State private var exporting = false
    @Environment(\.shellTab) private var tab

    private var store: LedgerStore { LedgerStore.defaultStore() }
    private var view: Binding<SpendingView> {
        Binding(get: { opened ?? SpendingPrefs.pageStart(fromJustMeHeader: fromJustMeHeader, stored: viewRaw) },
                set: { opened = $0; viewRaw = SpendingPrefs.raw($0) })
    }
    private var isCurrent: Bool { month == SpendingMonth.current() }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                SpendingSwitch(view: view)
                if view.wrappedValue == .all {
                    Text(verbatim: SpendingCopy.allNote).font(TypeRole.body(TypeScale.detail)).foregroundStyle(Tokens.textDim)
                }
                monthSwitcher
                if let error {
                    Text(verbatim: error).font(TypeRole.body()).foregroundStyle(Tokens.owed).glassCard()
                } else if let loaded {
                    if loaded.out.rows.isEmpty {
                        emptyCard(loaded)
                    } else {
                        totalCard(loaded)
                        if let first = loaded.out.currencies.first, !first.categories.isEmpty { whereCard(first) }
                        Text(verbatim: SpendingCopy.expenses).sectionLabel().foregroundStyle(Tokens.text)
                        expensesCard(loaded)
                    }
                }
            }
            .padding(16)
        }
        .auroraBackground()
        .navigationTitle(SpendingCopy.title)
        .navigationBarTitleDisplayMode(.inline)
        .keepsShellBar()
        .toolbar {
            if view.wrappedValue == .justMe {
                ToolbarItem(placement: .topBarTrailing) {
                    Menu {
                        Button(SpendingCopy.export) { exporting = true }
                    } label: {
                        LucideIcon(glyph: .download, size: 20).foregroundStyle(Tokens.accent)
                    }
                    .barButtonText()
                    .barTint()
                    .accessibilityLabel(Text(verbatim: SpendingCopy.export))
                }
            }
        }
        .navigationDestination(isPresented: $exporting) { ExportScreen(store: store, justMe: true) }
        .task(id: "\(SyncStatus.shared.revision)|\(SpendingPrefs.raw(view.wrappedValue))|\(month.year)-\(month.month)") { load() }
        .onAppear { SpendingPresence.shared.shown(tab) }
        .onDisappear { SpendingPresence.shared.gone(tab) }
    }

    private func load() {
        do {
            loaded = try SpendingLoad.load(store, month: month, view: view.wrappedValue)
            error = nil
        } catch {
            self.error = String(localized: "Could not load the ledger. \(String(describing: error))")
        }
    }

    private var monthSwitcher: some View {
        HStack {
            monthButton(.chevronLeft, SpendingCopy.prev, enabled: true) { month = month.adding(-1) }
            Spacer()
            Text(verbatim: month.label).font(TypeRole.name(TypeScale.inlineTitle)).foregroundStyle(Tokens.text)
            Spacer()
            monthButton(.chevronRight, SpendingCopy.next, enabled: !isCurrent) { month = month.adding(1) }
        }
    }

    private func monthButton(_ glyph: LucideGlyph, _ spoken: String, enabled: Bool, _ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            LucideIcon(glyph: glyph, size: 18)
                .foregroundStyle(Tokens.textDim)
                .frame(width: 36, height: 36)
                .background(Tokens.surface, in: ThemeArt.controlShape(AppearanceState.shared.theme))
                .frame(width: 44, height: 44)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
        .opacity(enabled ? 1 : ButtonLook.disabledAlpha)
        .accessibilityLabel(Text(verbatim: spoken))
    }

    private func totalCard(_ l: SpendingLoad.Loaded) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(verbatim: isCurrent ? SpendingCopy.thisMonth : month.label).sectionLabel().foregroundStyle(Tokens.text)
            Text(verbatim: SpendingCopy.spent).font(TypeRole.body(TypeScale.detail)).foregroundStyle(Tokens.textDim)
            if let first = l.out.currencies.first {
                Text(verbatim: RolesMoney.format(first.totalMinor, first.currencyCode) ?? Placeholder.none)
                    .font(TypeRole.numeric(TypeScale.largeTitle, weight: 700, relativeTo: .largeTitle))
                    .foregroundStyle(Tokens.text)
                    .lineLimit(1).minimumScaleFactor(0.6)
                    .accessibilityLabel(Text(verbatim: RolesMoney.spoken(first.totalMinor, first.currencyCode)))
            }
            let others = l.out.currencies.dropFirst()
            if !others.isEmpty {
                FlowLayout(spacing: 8) {
                    ForEach(Array(others.enumerated()), id: \.offset) { _, c in
                        Text(verbatim: RolesMoney.format(c.totalMinor, c.currencyCode) ?? Placeholder.none)
                            .font(TypeRole.numeric(TypeScale.pill, weight: 600))
                            .foregroundStyle(Tokens.text)
                            .padding(.horizontal, 10).padding(.vertical, 5)
                            .background(Tokens.surfaceRaised, in: ThemeArt.pillShape(AppearanceState.shared.theme))
                    }
                }
                .padding(.top, 4)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .glassCard()
    }

    private func whereCard(_ c: CurrencySpendingOut) -> some View {
        let tree = CategoryLevels.tree
        let tableOn = Binding(get: { table }, set: { table = $0 })
        return VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text(verbatim: OverviewCopy.whereItWent).sectionLabel().foregroundStyle(Tokens.text)
                Spacer(minLength: 8)
                ChartTableSwitch(table: tableOn)
            }
            if table {
                HStack {
                    Text(verbatim: SpendingCopy.columns[0]).frame(maxWidth: .infinity, alignment: .leading)
                    Text(verbatim: SpendingCopy.columns[1])
                    Text(verbatim: SpendingCopy.columns[2]).frame(width: 52, alignment: .trailing)
                }
                .microLabelFont(12).foregroundStyle(Tokens.textDim)
                ForEach(Array(c.categories.enumerated()), id: \.offset) { i, s in
                    HStack(spacing: 8) {
                        ThemeArt.swatchShape(AppearanceState.shared.theme).fill(sliceColour(i)).frame(width: 8, height: 8)
                        Text(verbatim: CategoryLevels.name(s.category, in: tree)).font(TypeRole.body(TypeScale.detail))
                            .foregroundStyle(Tokens.text).frame(maxWidth: .infinity, alignment: .leading)
                        Text(verbatim: RolesMoney.format(s.amountMinor, c.currencyCode) ?? Placeholder.none)
                            .font(TypeRole.numeric(TypeScale.detail, weight: 600)).foregroundStyle(Tokens.text)
                        Text(verbatim: OverviewCopy.percent(s.basisPoints)).font(TypeRole.numeric(TypeScale.detail))
                            .foregroundStyle(Tokens.textDim).frame(width: 52, alignment: .trailing)
                    }
                    .accessibilityElement(children: .combine)
                }
            } else {
                ForEach(Array(c.categories.enumerated()), id: \.offset) { i, s in
                    VStack(alignment: .leading, spacing: 6) {
                        HStack {
                            Text(verbatim: CategoryLevels.name(s.category, in: tree)).font(TypeRole.name(TypeScale.detail))
                                .foregroundStyle(Tokens.text)
                            Spacer()
                            Text(verbatim: OverviewCopy.percent(s.basisPoints)).font(TypeRole.numeric(TypeScale.detail, weight: 600))
                                .foregroundStyle(Tokens.text)
                        }
                        GeometryReader { geo in
                            sliceColour(i).frame(width: max(2, geo.size.width * CGFloat(s.basisPoints) / 10_000))
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .background(Tokens.ringTrack)
                        }
                        .frame(height: 5)
                        .clipShape(ThemeArt.meterShape(AppearanceState.shared.theme))
                        .accessibilityHidden(true)
                    }
                    .accessibilityElement(children: .combine)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .glassCard()
    }

    private func expensesCard(_ l: SpendingLoad.Loaded) -> some View {
        let tree = CategoryLevels.tree
        return VStack(spacing: 0) {
            ForEach(Array(l.out.rows.enumerated()), id: \.element.id) { i, row in
                if i > 0 { Divider().overlay(Tokens.hairline) }
                NavigationLink {
                    ExpenseDetailView(store: store, expenseID: row.id, named: named(row.group))
                } label: {
                    SpendingRow(row: row, tree: tree, groupNames: l.groupNames)
                }
                .buttonStyle(.plain)
                .accessibilityHint(Text(verbatim: OverviewCopy.openExpense))
            }
        }
        .glassCard(padding: 14)
    }

    private func named(_ group: String?) -> NamedGroupWire? {
        guard let group else { return nil }
        return (try? store.namedGroups())?.first { $0.group.id == group }
    }

    private func emptyCard(_ l: SpendingLoad.Loaded) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            if l.hasAny {
                Text(verbatim: SpendingCopy.emptyMonth(month.name)).font(TypeRole.name(TypeScale.emptyTitle)).foregroundStyle(Tokens.text)
            } else {
                Text(verbatim: SpendingCopy.emptyTitle).font(TypeRole.name(TypeScale.emptyTitle)).foregroundStyle(Tokens.text)
                Text(verbatim: SpendingCopy.emptyDetail).font(TypeRole.body()).foregroundStyle(Tokens.textDim)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .glassCard()
    }
}

/// One expense of the page: what it was ("{group}, your share" under it for a group's in All), the day and the
/// category, and the amount that counts (core's: the whole for Just me, your share for a group's).
struct SpendingRow: View {
    let row: SpendingRowOut
    let tree: CategoryTree
    let groupNames: [String: String]

    var body: some View {
        let leaf = row.category.map { CategoryLevels.name($0, in: tree) }
        let title = (row.note?.isEmpty == false ? row.note : nil) ?? leaf ?? String(localized: "Expense")
        HStack(alignment: .top, spacing: 8) {
            VStack(alignment: .leading, spacing: 2) {
                Text(verbatim: title).font(TypeRole.name(15)).foregroundStyle(Tokens.text).lineLimit(2)
                if let group = row.group {
                    Text(verbatim: groupNames[group].map(SpendingCopy.groupMeta) ?? OverviewCopy.yourShare)
                        .font(TypeRole.body(12)).foregroundStyle(Tokens.textDim)
                }
                Separator.text([AppDate.dayMonth(row.dateMs), leaf].compactMap { $0 }, size: 12)
                    .font(TypeRole.body(12)).foregroundStyle(Tokens.textFaint)
            }
            Spacer(minLength: 8)
            Text(verbatim: RolesMoney.format(row.amountMinor, row.currencyCode) ?? Placeholder.none)
                .font(TypeRole.numeric(15, weight: 600)).foregroundStyle(Tokens.text)
                .layoutPriority(1)
        }
        .padding(.vertical, 10)
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
    }
}

/// "Chart" / "Table" on the where card (SPEC 8.4: every chart has a table view).
struct ChartTableSwitch: View {
    @Binding var table: Bool

    var body: some View {
        let shape = ThemeArt.pillShape(AppearanceState.shared.theme)
        HStack(spacing: 0) {
            ForEach([false, true], id: \.self) { isTable in
                let chosen = isTable == table
                Button { table = isTable } label: {
                    Text(verbatim: isTable ? SpendingCopy.table : SpendingCopy.chart)
                        .font(TypeRole.name(12))
                        .foregroundStyle(chosen ? Tokens.onAccent : Tokens.textDim)
                        .padding(.horizontal, 10).padding(.vertical, 5)
                        .background { if chosen { shape.fill(Tokens.accent) } }
                        .frame(minHeight: 32)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(chosen ? .isSelected : [])
            }
        }
        .padding(2)
        .background(Tokens.surfaceRaised, in: shape)
    }
}

/// You > Appearance's "Spending on Home" (decision 163): the chart tile, the title over the summary, the switch; on until
/// turned off; this phone's own (decision 189), so it writes UserDefaults directly, never through SettingsSync.
/// Android's twin is `SpendingHomeRow`.
struct SpendingHomeRow: View {
    @AppStorage(SpendingPrefs.onHomeKey) private var on = true

    var body: some View {
        ListCard {
            // Two lines: the approved words do not fit one on the narrowest phones (the width tables, decision 82).
            ListRow(glyph: .chartPie, title: SpendingCopy.homeRow, parts: [SpendingCopy.homeSummary], summaryLines: 2,
                    trailing: .toggle($on))
        }
    }
}

/// Decision 193: a ledger Add expense can file under.
enum LedgerChoice: Hashable {
    case justMe
    case group(String)
}

/// The Ledger page's rows: Just me first ("Only you"), then each writable group with its tile and "Since {Month Year} ·
/// {n} people", each in the theme's card, the chosen one checked. A pick writes through `onPick` and returns.
struct LedgerChoicePage: View {
    struct Option: Identifiable {
        let choice: LedgerChoice
        let title: String
        let parts: [String]
        let group: GroupWire?
        var id: LedgerChoice { choice }
    }

    let options: [Option]
    let chosen: LedgerChoice
    let name: String?
    let avatar: Avatar
    let onPick: (LedgerChoice) -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        ScrollView {
            // 14 apart: Norse's cards glow 6 outside their edge (D-243), so 10 let two glows meet.
            VStack(alignment: .leading, spacing: 14) {
                ForEach(options) { option in
                    Button {
                        onPick(option.choice)
                        dismiss()
                    } label: {
                        HStack(spacing: ThemeArt.m("rowGap")) {
                            if let group = option.group {
                                GroupTile(group: group, size: ThemeArt.m("ledgerTile"))
                            } else {
                                SpendingTile(name: name, avatar: avatar)
                            }
                            VStack(alignment: .leading, spacing: 3) {
                                Text(verbatim: option.title).font(TypeRole.name(TypeScale.ledgerName)).foregroundStyle(Tokens.text)
                                    .lineLimit(1)
                                Separator.text(option.parts, size: TypeScale.detail)
                                    .font(TypeRole.body(TypeScale.detail)).foregroundStyle(Tokens.textDim)
                                    .lineLimit(1)
                            }
                            .frame(maxWidth: .infinity, alignment: .leading)
                            if option.choice == chosen {
                                LucideIcon(glyph: .check, size: 20).foregroundStyle(Tokens.accent)
                            }
                        }
                        .frame(minHeight: 52)
                        .contentShape(Rectangle())
                        .glassCard(padding: 14)
                    }
                    .buttonStyle(.plain)
                    .accessibilityElement(children: .ignore)
                    .accessibilityLabel(Text(verbatim: YouRows.spoken(title: option.title, parts: option.parts)))
                    .accessibilityAddTraits(option.choice == chosen ? [.isSelected, .isButton] : .isButton)
                }
            }
            .padding(.horizontal, 16)
            .padding(.top, CurrencyListScreen.topPadding)
            .padding(.bottom, 16)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .auroraBackground()
        .navigationTitle(Text(verbatim: AddSheetCopy.ledger))
        .navigationBarTitleDisplayMode(.inline)
    }
}

/// Decision 193: the Ledger page's options from what Add expense holds: Just me, then each writable group with its meta.
enum LedgerOptions {
    static func build(groups: [NamedGroupWire], created: [String: String], timeZone: TimeZone = .current) -> [LedgerChoicePage.Option] {
        [LedgerChoicePage.Option(choice: .justMe, title: SpendingCopy.justMe, parts: LedgerMeta.justMeParts, group: nil)]
            + groups.map { g in
                LedgerChoicePage.Option(
                    choice: .group(g.group.id), title: g.group.name,
                    parts: LedgerMeta.groupParts(sinceMs: created[g.group.id].flatMap(LedgerMeta.sinceMs(created:)),
                                                 people: g.group.members.count, timeZone: timeZone),
                    group: g.group)
            }
    }
}

/// A Just me expense as Add expense writes it: you paid, the whole is your share, no group (the personal scope).
enum JustMeExpense {
    static func dto(you: String, totalMinor: Int64, currency: String, note: String?, category: String?,
                    nowMs: UInt64 = UInt64(Date().timeIntervalSince1970 * 1000)) -> LedgerStore.ExpenseDTO {
        LedgerStore.ExpenseDTO(
            payer: you, paidMinor: totalMinor, currencyCode: currency, group: nil, conversion: nil,
            shares: [.init(id: you, amountMinor: totalMinor)],
            note: note, timestampMs: nowMs, category: category, addedBy: you)
    }

    /// An edit's new money: the stored payer keeps the whole (an edit never moves an expense between ledgers).
    static func money(payer: String, totalMinor: Int64, currency: String) -> ExpenseInput {
        ExpenseInput(payer: payer, paidMinor: totalMinor, currencyCode: currency,
                     shares: [IdAmount(id: payer, amountMinor: totalMinor)], group: nil, conversion: nil)
    }
}
