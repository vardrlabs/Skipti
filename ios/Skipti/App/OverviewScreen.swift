import SwiftUI

/// D-217: the hero's first line is a kicker; in Vardr Labs it takes steel (the accent), as the Look Book's hero does.
enum HeroKicker {
    static func usesSteel(_ theme: AppTheme) -> Bool { theme == .vardr }
}

/// D-180: the Balance tab's words, one place; Android's twin is
/// `OverviewCopy` in OverviewTab.kt and the D-180 tests pin both.
enum OverviewCopy {
    /// Decision 122 (the founder): a group with a change of this phone's the cloud refused (parked). One quiet line, no
    /// count, no button. Android's twin is `OverviewCopy.COULD_NOT_SYNC`.
    static let couldNotSync = "One change could not sync. It is still on this phone."
    static let across = "Across all your groups"
    static let getBack = "You'll get back"
    static let owe = "You owe"
    static let settled = "You're all settled up"
    static let settledDetail = "Nobody owes anybody. Nice."
    static let people = "People"
    static let settleUp = "Settle up"
    static func groups(_ n: Int) -> String { n == 1 ? "1 group" : "\(n) groups" }
    /// "You get back ₹900" / "You owe €32": a currency beside the headline.
    static func chip(_ minor: Int64, _ formatted: String) -> String {
        minor > 0 ? "You get back \(formatted)" : "You owe \(formatted)"
    }
    static func verb(_ minor: Int64) -> String { minor > 0 ? "owes you" : "you owe" }
    // A group's page.
    static let totalSpent = "Total spent"
    static let yourShare = "Your share"
    static let whereItWent = "Where the money went"
    static let whoOwes = "Who owes whom"
    static let recent = "Recent"
    static let noCategory = "No category"
    static let nothingYet = "Nothing here yet. Add your first expense with the + below."
    /// D-220 (L25): the line's parts; the group page's tile row joins them through `Separator.text`. The white line
    /// over a photo keeps `settlesIn` (a white separator).
    static func settlesInParts(people: Int, currency: String) -> [String] { [GroupCopy.people(people), "settles in \(currency)"] }
    static func settlesIn(people: Int, currency: String) -> String {
        settlesInParts(people: people, currency: currency).joined(separator: Separator.meta)
    }
    static func paid(_ who: String, _ formatted: String) -> String { "\(who) paid \(formatted)" }
    /// Basis points as a whole percent for the legend ("46%"); "<1%" for a
    /// slice that rounds to nothing.
    static func percent(_ bps: UInt32) -> String {
        let whole = (bps + 50) / 100
        return whole == 0 ? "<1%" : "\(whole)%"
    }
}

/// D-180: the category tree's two levels (SPEC §8.1: parents are headers,
/// only leaves are assigned). A group's page groups by parent; D-181's
/// two-step picker uses the same answers. Android's twin: `CategoryLevels`.
enum CategoryLevels {
    static var tree: CategoryTree { ffiCategoryTreeDefault() }

    /// The parent of a leaf id; nil for an unknown id or a parent itself.
    static func parent(of leaf: String?, in tree: CategoryTree) -> String? {
        guard let leaf, let c = tree.categories.first(where: { $0.id == leaf }) else { return nil }
        return c.parent
    }

    static func name(_ id: String?, in tree: CategoryTree) -> String {
        guard let id, let c = tree.categories.first(where: { $0.id == id }) else { return OverviewCopy.noCategory }
        return c.name
    }

    /// The parents, unarchived, in the tree's order.
    static func parents(_ tree: CategoryTree) -> [(id: String, name: String)] {
        tree.categories.filter { $0.parent == nil && !$0.archived }.map { ($0.id, $0.name) }
    }

    /// D-181: a sub-category survives a change of category only if it
    /// belongs to the new one.
    static func keep(_ leaf: String?, under parent: String?, in tree: CategoryTree) -> String? {
        guard let parent, self.parent(of: leaf, in: tree) == parent else { return nil }
        return leaf
    }

    /// One parent's unarchived leaves, in the tree's order.
    static func leaves(of parent: String?, in tree: CategoryTree) -> [(id: String, name: String)] {
        guard let parent else { return [] }
        return tree.categories.filter { $0.parent == parent && !$0.archived }.map { ($0.id, $0.name) }
    }
}

/// D-181: the two-step picker's words; Android's twin is `CategoryCopy`.
enum CategoryCopy {
    static let category = "Category"
    static let subcategory = "Sub-category"
    static let none = "None"
    static let choose = "Choose one"
    static func hint(_ parent: String) -> String { "Choose one to file it under \(parent)." }
}

/// A slice colour for "Where the money went": the tones, in order.
private func sliceColour(_ index: Int) -> Color {
    let tones: [GroupTone] = [.coral, .blue, .teal, .amber, .sky, .pink]
    return tones[index % tones.count].letter
}

private func money(_ minor: Int64, _ code: String) -> String {
    (try? ffiMoneyFormat(minorUnits: minor, currencyCode: code, locale: AppLocale.current)) ?? Placeholder.none
}

/// D-180: Balance across every group (the founder's canvas): what you get back
/// or owe, one total per currency and never converted; the people behind it;
/// and each group, which opens its page.
/// Home's one read: every group with its viewer, its people and (D-210)
/// the people removed from it, named, so a removed person at zero is taken
/// out of the cards and one who still owes is named. The screen and the
/// tests read Home through this. Android's twin is `HomeOverview.load`.
/// D-223: it also returns each group's settled blocks (E4's `ffiSettledProgress`, settlement currency first), read
/// from the same records, for the ledger cards' meters. Every expense goes to core, which scopes by the group's id (one
/// rule, core's, as Android's twin and the group page's blocks).
enum HomeOverview {
    static func load(_ store: LedgerStore) throws
        -> (named: [NamedGroupWire], overview: OverviewOut, progress: [String: [SettledProgressOut]]) {
        let named = try store.namedGroups()
        let removed = try store.removedPeople()
        let expenses = try store.loadExpenses()
        let settlements = try store.loadSettlements().map(\.asInput)
        let redistributions = try store.loadRedistributions()
        let overview = try ffiOverview(
            groups: named.map {
                OverviewGroupIn(groupId: $0.group.id, viewer: $0.selfId, members: $0.members,
                                removed: removed[$0.group.id] ?? [])
            },
            expenses: expenses.map(\.asInput),
            settlements: settlements,
            redistributions: redistributions
        )
        var progress: [String: [SettledProgressOut]] = [:]
        for n in named {
            let id = n.group.id
            progress[id] = SettledSummary.blocks(
                try ffiSettledProgress(expenses: expenses.map(\.asInput),
                                       settlements: settlements, redistributions: redistributions, group: id),
                settlementCurrency: n.group.settlementCurrency)
        }
        return (named, overview, progress)
    }
}

/// D-227 (a): every tab's stack stays alive on iOS (D-180), so the others read again only when the sync revision moves.
/// The completions in this file call it after their own read where another tab shows what changed: Balance's new group
/// and your name, and the group page's People (a person added or renamed: Groups' count, the names on Expenses and
/// Settle up). Android composes only the selected tab, which reads on every visit, so it has no twin.
@MainActor
enum OverviewActions {
    static func changed(_ status: SyncStatus = .shared) { status.changed() }
}

/// D-248 (U30.6, decision 185): a group just created opens its page, pushed on Home's stack, so Back lands on Home (which
/// lists it); the next thing a person does is add people or an expense there. Every create path asks once its sheet has
/// gone (New group from Home, from Groups, and the welcome's Start a group); Home opens it (`OverviewScreen`). The first
/// group's big moment is the window's overlay, so it still plays over the page. Android's twin opens the group's route.
@MainActor @Observable
final class GroupOpening {
    static let shared = GroupOpening()
    private(set) var pending: String?

    func request(_ groupID: String) { pending = groupID }

    /// The group to open, once.
    func take() -> String? {
        defer { pending = nil }
        return pending
    }
}

/// The group page Home pushes for `GroupOpening`.
struct OpenedGroup: Hashable, Identifiable {
    let id: String
}

struct OverviewScreen: View {
    var onSettle: () -> Void

    @State private var groups: [NamedGroupWire] = []
    @State private var overview: OverviewOut?
    /// D-223: each group's settled blocks (the cards' meters) and Lately's read, both from `load()`.
    @State private var progress: [String: [SettledProgressOut]] = [:]
    @State private var lately = ActivityFeed.Loaded()
    /// R4: the clock Lately's relative words are read against. Every `load()` takes a fresh one, so a kept-alive Home
    /// that comes back on screen shows the current minutes (Android's `HomeKeep` re-reads the same way).
    @State private var nowMs: UInt64 = RelativeWhen.nowMs
    /// R4: false until Home's first appearance, which the `.task` below already reads for; later ones re-read.
    @State private var appearedOnce = false
    /// The expense a Lately row opened; its destination hangs on the always-present container (CLAUDE.md 10.1).
    @State private var latelyOpen: LatelyOpen?
    /// "See all" (A1): the whole-ledger Activity, pushed from the same container (the card can vanish, M1).
    @State private var seeAll = false
    /// D-248 (U30.6): the group just created, pushed from the same container.
    @State private var openedGroup: OpenedGroup?
    /// The group Home's own New group made, opened once its sheet has gone.
    @State private var createdID: String?
    @State private var needsYourName = false
    @State private var askingName = false
    @State private var creating = false
    @State private var loaded = false
    @State private var error: String?
    /// C2: nil until read; `.none` shows the card that asks for a username.
    @State private var usernameState: UsernameState?
    /// C2: the card's step, owned here and presented from the outer Group:
    /// the cards sit in branches a sync can flip (empty Home becomes the
    /// list), and a sheet on a card was torn down with it (the D-208 class).
    @State private var pickingUsername = false

    private var store: LedgerStore { LedgerStore.defaultStore() }

    var body: some View {
        // A container, not a Group: a Group hands its modifiers (the sheets
        // below) to whichever branch shows, so a branch flip tore them down.
        ZStack {
            if needsYourName {
                empty(title: String(localized: "Add your name"),
                      detail: String(localized: "This ledger has a group from before names existed. Add your name once; you will see yourself as \"Name (You)\".")) {
                    Button("Add your name") { askingName = true }.buttonStyle(PrimaryButtonStyle())
                }
            } else if loaded && groups.isEmpty && error == nil {
                empty(title: String(localized: "Nothing here yet"),
                      detail: String(localized: "Create a group, join one you were invited to, or import from another app.")) {
                    Button("Create a group") { creating = true }.buttonStyle(PrimaryButtonStyle())
                    NavigationLink {
                        JoinPasteScreen(store: store) { _ in
                            NotificationCenter.default.post(name: .skiptiLedgerImported, object: nil)
                        }
                    } label: { Text(verbatim: InviteCopy.joinTitle) }
                        .buttonStyle(SecondaryButtonStyle())
                    NavigationLink(ImportCopy.entry) { ImportFlowScreen() }
                        .buttonStyle(SecondaryButtonStyle())
                    // C2: an account signed in before usernames were asked,
                    // with no groups yet.
                    if usernameState == UsernameState.none {
                        UsernameCard { pickingUsername = true }
                            .padding(.top, 8)
                    }
                }
            } else {
                ScrollView {
                    VStack(alignment: .leading, spacing: 14) {
                        if TitleOrnament.shows(AppearanceState.shared.theme) { TitleOrnament(above: 16) }
                        // D-206 (the founder's item 5): a greeting by name and time of day.
                        HomeGreeting(store: store)
                        // C2: an account signed in before usernames were asked.
                        if usernameState == UsernameState.none {
                            UsernameCard { pickingUsername = true }
                        }
                        if let overview { hero(overview) }
                        // D-223 (Design 1, L52): "Your ledgers" and the grid; the old per-group scroller went (L55).
                        if !groups.isEmpty {
                            section(LedgerCopy.title)
                            LedgerGrid(count: groups.count) { i in AnyView(ledgerCard(groups[i])) }
                        }
                        // D-223 (L53): the newest three of the feed; hidden when there is none.
                        if !lately.entries.isEmpty {
                            LatelyCard(feed: lately, nowMs: nowMs, onSeeAll: { seeAll = true }) { latelyOpen = $0 }
                        }
                        if let people = overview?.people, !people.isEmpty {
                            section(OverviewCopy.people)
                            VStack(spacing: 0) {
                                ForEach(Array(people.enumerated()), id: \.offset) { i, p in
                                    if i > 0 { Divider().overlay(Tokens.hairline) }
                                    personRow(p)
                                }
                            }
                            .glassCard(padding: 14)
                        }
                        if let error {
                            Text(verbatim: error).font(TypeRole.body()).foregroundStyle(Tokens.owed).glassCard()
                        }
                    }
                    .padding(16)
                    .shellScrollTop() // D-205
                }
                .auroraBackground()
            }
        }
        .navigationTitle("Balance")
        .navigationBarTitleDisplayMode(.large)
        .navigationDestination(item: $latelyOpen) { open in
            ExpenseDetailView(store: store, expenseID: open.expenseID, named: open.named)
        }
        .navigationDestination(isPresented: $seeAll) {
            ActivityScreen(store: store, group: nil)
        }
        .navigationDestination(item: $openedGroup) { opened in
            GroupPageScreen(groupID: opened.id)
        }
        // D-248 (U30.6): a group made elsewhere (Groups, the welcome) opens here, on Home's stack.
        .onChange(of: GroupOpening.shared.pending, initial: true) { _, _ in
            if let id = GroupOpening.shared.take() { openedGroup = OpenedGroup(id: id) }
        }
        .task(id: SyncStatus.shared.revision) {
            load()
            await loadUsername()
        }
        .onAppear {
            // R4: back from a pushed screen or another tab: read again, so Lately's words are current.
            if appearedOnce { load() }
            appearedOnce = true
        }
        .sheet(isPresented: $creating, onDismiss: {
            // D-248 (U30.6): the new group's page, once the sheet has gone.
            if let id = createdID { createdID = nil; openedGroup = OpenedGroup(id: id) }
        }) {
            NavigationStack {
                CreateGroupSheet(store: store, needsYourName: (try? store.ensureSelf())?.name == nil) { created in
                    createdID = created.group.id
                    creating = false
                    load()
                    OverviewActions.changed()
                }
            }
        }
        .usernamePickSheet(isPresented: $pickingUsername, store: store) { Task { await loadUsername() } }
        .sheet(isPresented: $askingName) {
            NavigationStack {
                YourNameSheet(store: store) {
                    askingName = false
                    load()
                    OverviewActions.changed()
                }
            }
        }
    }

    /// Runs on every sync revision: once the account has a username it never
    /// loses one (C2: changed, never removed), so the cloud is not asked again.
    /// A failed read (nil) keeps what was known (UsernameState.merge).
    private func loadUsername() async {
        guard Account.shared.isSignedIn else { usernameState = nil; return }
        if let known = usernameState, known != UsernameState.none { return }
        let read = await People.accountUsername()
        usernameState = UsernameState.merge(known: usernameState, read: read, signedIn: Account.shared.isSignedIn)
    }

    private func load() {
        do {
            let home = try HomeOverview.load(store)
            // Views-extra 2: Lately is read here, inside Home's own catch: a failed read is the error line (the last
            // good Home stays, as Android's), never an empty feed. Home only reads it; Activity marks it seen. Only
            // with groups (Android's condition): every entry is a group's, and the read reaches `ensureSelf`, which
            // would write the self record on a fresh phone.
            let feed = home.named.isEmpty ? ActivityFeed.Loaded() : try ActivityFeed.load(store, group: nil)
            overview = home.overview
            groups = home.named
            progress = home.progress
            lately = feed
            nowMs = RelativeWhen.nowMs
            needsYourName = false
            error = nil
        } catch LedgerStore.GroupStoreError.needsYourName {
            needsYourName = true
        } catch {
            self.error = String(localized: "Could not load the ledger. \(String(describing: error))")
        }
        loaded = true
    }

    private func hero(_ o: OverviewOut) -> some View {
        let first = o.totals.first
        return VStack(alignment: .leading, spacing: 14) {
            Text(verbatim: OverviewCopy.across)
                .microLabelFont(TypeScale.dateLine)
                .foregroundStyle(HeroKicker.usesSteel(AppearanceState.shared.theme) ? Tokens.accent : Tokens.onHero.opacity(0.82))
            if let first {
                VStack(alignment: .leading, spacing: 2) {
                    Text(verbatim: first.amountMinor > 0 ? OverviewCopy.getBack : OverviewCopy.owe)
                        .font(TypeRole.body(TypeScale.heroLine)).foregroundStyle(Tokens.onHero.opacity(0.82))
                    Text(verbatim: money(Int64(first.amountMinor.magnitude), first.currencyCode))
                        .font(TypeRole.numeric(TypeScale.heroAmount, weight: 700, relativeTo: .largeTitle)).foregroundStyle(Tokens.onHero)
                        .minimumScaleFactor(0.6).lineLimit(1)
                    // D-223 (Design 2, Q4 a): who it is, the first two people in the hero's currency, largest first.
                    let who = WhoOwes.parts(o.people, currency: first.currencyCode, format: { money($0, $1) })
                    if !who.isEmpty {
                        Separator.text(who, size: TypeScale.heroLine)
                            .font(TypeRole.body(TypeScale.heroLine)).foregroundStyle(Tokens.onHero.opacity(0.82))
                            .lineLimit(2)
                    }
                }
            } else {
                VStack(alignment: .leading, spacing: 2) {
                    Text(verbatim: OverviewCopy.settled).font(TypeRole.display(28, weight: 700)).foregroundStyle(Tokens.onHero)
                    Text(verbatim: OverviewCopy.settledDetail).font(TypeRole.body(TypeScale.heroLine)).foregroundStyle(Tokens.onHero.opacity(0.82))
                }
            }
            FlowChips(items: o.totals.dropFirst().map {
                OverviewCopy.chip($0.amountMinor, money(Int64($0.amountMinor.magnitude), $0.currencyCode))
            } + [OverviewCopy.groups(groups.count)])
            if first != nil {
                Button(OverviewCopy.settleUp, action: onSettle)
                    .buttonStyle(HeroButtonStyle())
            }
        }
        .padding(20)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background { HeroArt().clipShape(ThemeArt.cardShape(AppearanceState.shared.theme)) }
        // D-243 (T7): Norse's edge glow, outside the clipped art.
        .norseEdgeGlow()
    }

    private func personRow(_ p: PersonNetOut) -> some View {
        let names = p.groupIds.compactMap { id in groups.first { $0.group.id == id }?.group.name }
        let colour = p.netMinor > 0 ? Tokens.owing : Tokens.owed
        return HStack(spacing: 12) {
            // D-213 (fix wave 2): the shared avatar, so it follows the theme's person shape.
            PersonAvatar(name: p.name, size: 40)
            VStack(alignment: .leading, spacing: 2) {
                Text(verbatim: p.name).font(TypeRole.name(16)).foregroundStyle(Tokens.text)
                Text(verbatim: names.joined(separator: ", ")).font(TypeRole.body(13)).foregroundStyle(Tokens.textDim)
            }
            Spacer(minLength: 8)
            VStack(alignment: .trailing, spacing: 2) {
                Text(verbatim: OverviewCopy.verb(p.netMinor)).font(TypeRole.label(12)).foregroundStyle(colour)
                Text(verbatim: money(Int64(p.netMinor.magnitude), p.currencyCode))
                    .font(TypeRole.numeric(17, weight: 600)).foregroundStyle(colour)
            }
        }
        .padding(.vertical, 10)
        .accessibilityElement(children: .combine)
    }

    /// D-223 (L54): a group's ledger card; the whole card opens the group's page.
    private func ledgerCard(_ named: NamedGroupWire) -> some View {
        NavigationLink {
            GroupPageScreen(groupID: named.group.id)
        } label: {
            LedgerCard(name: named.group.name,
                       nets: overview?.groups.first { $0.groupId == named.group.id }?.nets ?? [],
                       blocks: progress[named.group.id] ?? []) { size in
                GroupTile(group: named.group, size: size)
            }
        }
        .buttonStyle(.plain)
        .accessibilityHint(Text(verbatim: GroupsCopy.openHint))
    }

    private func section(_ text: String) -> some View {
        Text(verbatim: text).sectionLabel().foregroundStyle(Tokens.text).padding(.top, 4)
    }

    private func empty<A: View>(title: String, detail: String, @ViewBuilder action: () -> A) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            if TitleOrnament.shows(AppearanceState.shared.theme) { TitleOrnament(above: 16) }
            Text(verbatim: title).font(TypeRole.name(TypeScale.emptyTitle)).foregroundStyle(Tokens.text)
            Text(verbatim: detail).font(TypeRole.body()).foregroundStyle(Tokens.textDim)
            action()
        }
        .padding(16)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .auroraBackground()
    }
}

extension OverviewCopy {
    static let settledShort = "Settled"
    static let openExpense = "Opens the expense, to edit or delete it"
}

extension GroupTone {
    /// A person's avatar tone: the sum of the name's Unicode scalars
    /// (lower-cased) modulo six, so the same name looks the same on both
    /// shells and on every launch (Swift's `hashValue` changes per launch).
    static func person(_ name: String) -> GroupTone {
        let sum = name.lowercased().unicodeScalars.reduce(0) { $0 &+ Int($1.value) }
        return allCases[sum % allCases.count]
    }
}

/// The hero's button: an `onHero` fill with the hero ground's own colour for the words.
private struct HeroButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        HeroButtonBody(configuration: configuration)
    }
}

/// D-212: drawn in a View (as `PrimaryButtonBody`), so the `AppearanceState`
/// reads in `TypeRole` and the radius are tracked and a theme change redraws it.
private struct HeroButtonBody: View {
    let configuration: ButtonStyle.Configuration

    var body: some View {
        let theme = AppearanceState.shared.theme
        if ButtonLook.of(theme) == .outlined {
            // D-220 (L24): Vardr Labs' hero button is outlined like its main button: transparent, a 1 pt onHero
            // border, the words onHero, uppercase at .08 em, square; pressed, an onHero film at the chips' opacity
            // (art.py section 14 measures words and film).
            configuration.label
                .font(TypeRole.label(16))
                .textCase(ButtonLook.labelCase(theme, keepsCase: false))
                .tracking(ButtonLook.tracking(16))
                .frame(maxWidth: .infinity)
                .padding(.vertical, 12)
                .contentShape(Rectangle())
                .foregroundStyle(Tokens.onHero)
                .background(Tokens.onHero.opacity(configuration.isPressed ? Aurora.heroChipOpacity : 0))
                .overlay(Rectangle().strokeBorder(Tokens.onHero, lineWidth: 1))
        } else {
            configuration.label
                .font(TypeRole.label(16))
                .frame(maxWidth: .infinity)
                .padding(.vertical, 12)
                .background(Tokens.onHero.opacity(configuration.isPressed ? 0.85 : 1), in: ThemeArt.controlShape(theme))
                .foregroundStyle(Tokens.heroStart)
        }
    }
}

/// Chips that wrap onto the next line instead of clipping.
private struct FlowChips: View {
    let items: [String]
    var body: some View {
        FlowLayout(spacing: 8) {
            ForEach(Array(items.enumerated()), id: \.offset) { _, item in
                Text(verbatim: item)
                    .font(TypeRole.label(13))
                    .padding(.horizontal, 12).padding(.vertical, 6)
                    .themePill(.hero)
            }
        }
    }
}

/// D-180: one group's page: its tone, three numbers, where the money went, who
/// owes whom (the balance lines, rate staleness included), and the newest
/// expenses. Opening it makes it the group Add, Expenses and Settle up use.
struct GroupPageScreen: View {
    let groupID: String

    @State private var named: NamedGroupWire?
    @State private var stats: GroupStatsOut?
    @State private var lines: [BalanceLineOut] = []
    /// D-221 (S2): the group's open pairwise payments (every member's frame), the next card's muted count.
    @State private var remaining = 0
    @State private var recent: [ProjectedExpenseOut] = []
    /// D-221: core's settled progress, one block per currency (the settlement currency first), and the milestone
    /// shown now, both read with the page.
    @State private var progress: [SettledProgressOut] = []
    @State private var milestone: Milestones.Shown?
    /// D-221 (Design 12, S8): a play waiting for the page to be seen, the play on screen, and whether the page is on
    /// screen in its stack. What the last load saw is `SettledMemory`'s (per group, memory only: nothing is stored, so
    /// the app after it was closed never plays on a first load).
    @State private var celebrationPending = false
    @State private var celebration: CelebrationPlay?
    @State private var appeared = false
    @Environment(\.artVisible) private var artVisible
    @Environment(\.scenePhase) private var phase
    @Environment(\.motionReduced) private var reduceMotion
    @State private var error: String?
    /// D-187: the group's picture, chosen from its tile.
    @State private var choosingPicture = false
    @State private var pictureError: String?
    /// D-210: the cloud, roles and requests of this group; a request's review
    /// sheet is owned here and hangs on the always-present ScrollView.
    @State private var people = GroupPeople()
    @State private var review: RemovalTarget?
    @State private var cardBusy = false
    @ObservedObject private var roles = GroupRoles.shared
    @ObservedObject private var notices = Notices.shared
    @Environment(\.openSettleTab) private var openSettle
    @Environment(\.dismiss) private var dismiss

    private var store: LedgerStore { LedgerStore.defaultStore() }

    /// D-210: a copy of a group this account was removed from, or that was
    /// deleted: nothing can be added to it.
    private var readOnly: LedgerStore.ReadOnly? { people.readOnly }

    /// A read of the group failed (the screen says so): whether it is a copy
    /// is not known, so nothing that changes it is offered (fail closed). The
    /// banner and the copy's own actions need the mark itself.
    @State private var loadFailed = false
    /// Every name the group's history needs (`historyNames`): removed people too.
    @State private var historyNames: [String: String] = [:]
    /// Decision 122: a record of this group the cloud refused is parked on this phone (`CloudRecords.hasParked`).
    @State private var parkedHere = false
    private var isCopy: Bool { readOnly != nil || loadFailed }
    /// What a refreshed card line needs: what each request's person owes.
    @State private var cardBalances: [String: String] = [:]

    private var viewerName: String { named?.members.first { $0.id == named?.selfId }?.name ?? "" }

    private var rows: [PersonRow] {
        guard let named else { return [] }
        return PeopleRoles.rows(
            members: named.members, selfId: named.selfId, viewerYou: people.viewerYou, claims: people.claims,
            roles: roles.roles[groupID], yourPlaces: roles.roles[groupID]?.places?.you, otherYouIds: people.otherYouIds,
            createdHere: people.createdHere)
    }

    private var cards: [RequestCardItem] {
        guard named != nil, people.shared, !isCopy else { return [] }
        return RequestCards.cards(items: notices.requests(in: groupID), rows: rows)
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                if let named {
                    if let readOnly { ReadOnlyBanner(group: named.group.name, mark: readOnly) }
                    // D-221 (Q16 a, Design 7): the bare header, the Settled card, the stats as a row, the milestone, the
                    // next payment, then the request cards and everything below as before.
                    header(named)
                    if let pictureError {
                        Text(verbatim: pictureError).font(TypeRole.body()).foregroundStyle(Tokens.owed)
                    }
                    // Decision 122: one quiet line under the header, as the header's own meta line.
                    if parkedHere {
                        Text(verbatim: OverviewCopy.couldNotSync)
                            .font(TypeRole.body(TypeScale.detail)).foregroundStyle(Tokens.textDim)
                    }
                    if !progress.isEmpty { SettledCard(blocks: progress) }
                    statsRow(named, lines.first)
                    if let milestone { MilestoneCard(shown: milestone) }
                    if !isCopy, let pick = GroupNext.pick(lines: lines) {
                        GroupNextCard(
                            store: store, named: named, history: historyNames, pick: pick,
                            remaining: remaining,
                            onRecorded: {
                                error = nil
                                load()
                                SyncStatus.shared.changed()
                            },
                            onError: { error = $0 })
                    }
                    ForEach(cards) { card in
                        RequestCardView(
                            card: card, canSettleUp: lines.contains { $0.viewerNetMinor != 0 }, balance: cardBalances[card.item.id], busy: cardBusy,
                            onDecline: { decline(card.item, named) },
                            onReview: { openReview(card.item, named) },
                            onSettle: openSettle)
                            .requestCardSurface()
                    }
                    if let stats, !stats.categories.isEmpty { categories(stats) }
                    if !lines.isEmpty {
                        Text(verbatim: OverviewCopy.whoOwes).sectionLabel().foregroundStyle(Tokens.text)
                        ForEach(lines, id: \.currencyCode) { line in
                            BalanceLineView(line: line, names: LedgerStore.balanceNames(named, history: historyNames), viewerID: named.selfId).glassCard()
                        }
                    }
                    Text(verbatim: OverviewCopy.recent).sectionLabel().foregroundStyle(Tokens.text)
                    if recent.isEmpty {
                        Text(verbatim: OverviewCopy.nothingYet).font(TypeRole.body()).foregroundStyle(Tokens.textDim).glassCard()
                    } else {
                        VStack(spacing: 0) {
                            ForEach(Array(recent.enumerated()), id: \.offset) { i, e in
                                if i > 0 { Divider().overlay(Tokens.hairline) }
                                if isCopy {
                                    recentRow(e, named, chevron: false)
                                } else {
                                    NavigationLink {
                                        ExpenseDetailView(store: store, expenseID: e.id, named: named)
                                    } label: {
                                        recentRow(e, named)
                                    }
                                    .buttonStyle(.plain)
                                    .accessibilityHint(Text(verbatim: OverviewCopy.openExpense))
                                }
                            }
                        }
                        .glassCard(padding: 14)
                    }
                    // D-196: what the others did here (D-189's list, for this group).
                    NavigationLink {
                        ActivityScreen(store: store, group: groupID)
                    } label: { Text(verbatim: ActivityFeed.groupTitle) }
                        .buttonStyle(SecondaryButtonStyle())
                    if let readOnly {
                        ReadOnlyActions(store: store, group: named, mark: readOnly) { dismiss() }
                    } else if !loadFailed {
                        NavigationLink {
                            PeopleScreen(store: store, groupID: groupID) { load(); OverviewActions.changed() }
                        } label: { Text(verbatim: GroupsCopy.people(named.group.name)) }
                            .buttonStyle(SecondaryButtonStyle())
                        // D-210: the name, picture and currency, and deleting the group.
                        NavigationLink {
                            GroupSettingsScreen(store: store, groupID: groupID) { dismiss() }
                        } label: { Text(verbatim: RolesCopy.groupSettingsTitle) }
                            .buttonStyle(SecondaryButtonStyle())
                    }
                }
                // D-247 fix wave (I1): a held settle whose write failed (its window ended, the page went, SKIPTI left
                // the front) says so here too, as on Settle up.
                if let error = error ?? SettleHold.shared.failure {
                    Text(verbatim: error).font(TypeRole.body()).foregroundStyle(Tokens.owed).glassCard()
                }
            }
            .padding(16)
            .modifier(CelebrationDim(play: celebration))
        }
        // D-221 (Design 12): the celebration over the page, in its own overlay; it never takes a touch (GUARANTEES #2).
        .overlay {
            if let celebration, let named {
                CelebrationOverlay(play: celebration, groupName: named.group.name).id(celebration)
            }
        }
        .auroraBackground()
        .navigationTitle(named?.group.name ?? "")
        .navigationBarTitleDisplayMode(.inline)
        // D-180: the canvas keeps the bar under a group's page.
        .keepsShellBar()
        .task(id: SyncStatus.shared.revision) { load() }
        // D-247: a settle held, undone or written from the next card; leaving the page lands a held one.
        .onChange(of: SettleHold.shared.revision) { load() }
        .landsHeldSettle(visible: artVisible)
        // D-221 (Design 12): a play waits until the page is seen, then runs for CelebrationTiming.total; leaving the
        // page (pushed over, its tab hidden) ends it.
        .onAppear { appeared = true; playIfSeen() }
        .onDisappear { appeared = false; celebration = nil }
        .onChange(of: artVisible) { if !artVisible { celebration = nil }; playIfSeen() }
        // S8: the app leaving the foreground ends a play (Android ends it on pause).
        .onChange(of: phase) { if phase != .active { celebration = nil }; playIfSeen() }
        .onChange(of: celebrationPending) { playIfSeen() }
        .task(id: celebration) {
            guard celebration != nil else { return }
            do {
                try await Task.sleep(for: .seconds(CelebrationTiming.total))
                celebration = nil
            } catch {}
        }
        // A request coming or going changes the card's balance line.
        .task(id: notices.requests(in: groupID).map(\.id)) {
            cardBalances = RequestCards.balanceLines(store: store, group: groupID, items: notices.requests(in: groupID))
        }
        .sheet(isPresented: $choosingPicture) {
            GroupPictureSheet(onChosen: { emoji, jpeg in setPicture(emoji, jpeg) }, onError: { pictureError = $0 })
        }
        .sheet(item: $review) { target in
            if let named {
                RemovalSheet(
                    store: store, named: named, target: target, shared: people.shared,
                    onFinished: { review = nil; load() },
                    onClose: { review = nil })
            }
        }
    }

    /// D-221 (Design 12): a pending play starts once the page is seen: on screen in its stack, its tab shown, the app
    /// active. Still under Reduce Motion or Less motion (D-239: power saving does not still it).
    private func playIfSeen() {
        guard celebrationPending, appeared, artVisible, phase == .active else { return }
        celebrationPending = false
        celebration = CelebrationPlay(startedAt: Date().timeIntervalSinceReferenceDate,
                                      still: !Celebration.showsBurst(reduceMotion: reduceMotion))
    }

    private func openReview(_ item: Notices.Item, _ named: NamedGroupWire) {
        guard let id = item.memberId, let row = rows.first(where: { $0.place == id }) else { return }
        review = RemovalTarget(member: row.member, you: row.you, place: row.place,
                               mayRemove: RequestCards.mayRemove(rows: rows, member: id), request: item)
    }

    private func decline(_ item: Notices.Item, _ named: NamedGroupWire) {
        cardBusy = true
        Task {
            defer { cardBusy = false }
            do {
                try await RoleCalls.decline(item, group: named, byName: viewerName)
                error = nil
            } catch {
                self.error = RolesCopy.failure(error, person: true)
            }
        }
    }

    /// D-187: writes the picture as a group record and pushes it.
    private func setPicture(_ emoji: String?, _ jpeg: Data?) {
        do {
            try store.setGroupPicture(groupID: groupID, emoji: emoji, jpeg: jpeg)
            GroupPictureStore.shared.load(store)
            pictureError = nil
            CloudSync.shared.ledgerChanged()
            // D-224 (Design 7): "Use letter" (no emoji, no photo) removes the picture.
            DoneCenter.shared.post(DoneMoments.line(
                emoji == nil && jpeg == nil ? DoneCopy.groupPictureRemoved : DoneCopy.groupPictureSaved))
        } catch {
            pictureError = GroupPictures.tooBig
        }
    }

    private func load() {
        // The roles this phone last heard, offline too.
        roles.load(groupID)
        do {
            // D-210: read strictly, before anything else: a copy is never the
            // group Add and Settle up work on, and an unreadable mark shows
            // nothing rather than a page that could be added to.
            let loaded = try GroupPeople.load(store: store, groupID: groupID)
            if loaded.readOnly == nil { try store.selectGroup(id: groupID) }
            guard let n = try store.namedGroups().first(where: { $0.group.id == groupID }) else { return }
            let all = try store.loadExpenses().filter { $0.group == groupID }
            // D-247: a settle held for Undo counts as recorded (the next card moves on, the meter moves).
            let settlements = try store.loadSettlements().map(\.asInput) + SettleHold.shared.held.map(\.asInput)
            let redistributions = try store.loadRedistributions()
            let tree = CategoryLevels.tree
            stats = try ffiGroupStats(
                expenses: all.map {
                    StatsExpenseIn(expense: $0.asInput,
                                   category: CategoryLevels.parent(of: $0.category, in: tree))
                },
                viewer: n.selfId
            )
            let removed = try store.removedIds(group: groupID)
            func line(_ viewer: String) throws -> [BalanceLineOut] {
                try ffiBalanceLine(expenses: all.map(\.asInput), settlements: settlements, redistributions: redistributions,
                                   removed: removed, viewer: viewer, group: groupID)
            }
            lines = try line(n.selfId)
                .sorted { ($0.viewerNetMinor.magnitude, $0.currencyCode) > ($1.viewerNetMinor.magnitude, $1.currencyCode) }
            // D-221 (S2): the pick reads your frame. D-247 (decision 168): the count is the plan's length (core's
            // fewest payments, the plan Settle up shows).
            remaining = GroupNext.planCount(lines: lines)
            // D-180: core's projection (edits applied, deletions out), so a
            // row opens the expense itself, to read, edit or delete.
            let live = try store.projectedExpenses().expenses.filter { !$0.deleted && $0.expense.group == groupID }
            recent = Array(live
                .sorted { ($0.timestampMs ?? $0.addedAtMs) > ($1.timestampMs ?? $1.addedAtMs) }
                .prefix(5))
            // D-221 (Design 1, 11): core measures the money; the milestones read the live expenses' two dates.
            progress = SettledSummary.blocks(
                try ffiSettledProgress(expenses: all.map(\.asInput), settlements: settlements,
                                       redistributions: redistributions, group: groupID),
                settlementCurrency: n.group.settlementCurrency)
            // D-221 (Design 12): the load only decides; the page plays it when seen (playIfSeen), never from here.
            let after = SettledSummary.allSettled(progress)
            let before = SettledMemory.shared.see(groupID, settled: after)
            celebrationPending = Celebration.pending(was: celebrationPending, before: before, after: after, isCopy: loaded.readOnly != nil)
            milestone = Milestones.current(
                live.map { MilestoneExpense(addedAtMs: $0.addedAtMs, dateMs: $0.timestampMs ?? $0.addedAtMs) },
                now: UInt64(Date().timeIntervalSince1970 * 1000))
            historyNames = try store.historyNames(group: groupID)
            parkedHere = try CloudRecords.hasParked(store, group: groupID)
            people = loaded
            named = n
            cardBalances = RequestCards.balanceLines(store: store, group: groupID, items: notices.requests(in: groupID))
            loadFailed = false
            error = nil
        } catch {
            loadFailed = true
            // The last good page stays (an open sheet hangs on it); the
            // error says the reload failed.
            self.error = String(localized: "Could not load the group. \(String(describing: error))")
        }
    }

    /// D-221 (Q16 a, the founder): the header is bare, on the page itself: the photo band when the group has a photo,
    /// else the tile row. No card and no tone tint; the stats are a row of their own under the Settled card.
    @ViewBuilder
    private func header(_ n: NamedGroupWire) -> some View {
        if let photo = GroupPictureStore.shared.byId[n.group.id]?.photoHex.flatMap(GroupPictureStore.shared.image) {
            photoBand(n, photo)
        } else {
            tileRow(n)
        }
    }

    /// D-187 (the founder): the group's photo edge to edge across the top of
    /// the page (D-221: the header is bare), the name white over `PhotoCard`'s
    /// gradient; tapping the photo changes it.
    private func photoBand(_ n: NamedGroupWire, _ photo: UIImage) -> some View {
        ZStack(alignment: .bottomLeading) {
            Button { choosingPicture = true } label: {
                PhotoBackdrop(photo: photo).frame(maxWidth: .infinity).frame(height: PhotoCard.headerHeight)
            }
            .buttonStyle(.plain)
            // D-234 (L9): a copy's picture takes no tap and is not a button; never disabled (SwiftUI fades its label).
            .allowsHitTesting(!isCopy)
            .accessibilityLabel(Text(verbatim: GroupPictures.title))
            .accessibilityHidden(isCopy)
            VStack(alignment: .leading, spacing: 2) {
                Text(verbatim: n.group.name).font(TypeRole.display(TypeScale.displayName, weight: 700)).foregroundStyle(.white)
                Text(verbatim: OverviewCopy.settlesIn(people: n.members.count, currency: n.group.settlementCurrency))
                    .font(TypeRole.body(TypeScale.detail)).foregroundStyle(.white)
            }
            .padding(14)
            .allowsHitTesting(false)
        }
        .overlay(alignment: .topTrailing) {
            if !isCopy {
                NavigationLink {
                    PeopleScreen(store: store, groupID: groupID) { load(); OverviewActions.changed() }
                } label: {
                    Image(uiImage: LucideGlyph.userPlus.templateImage())
                        .renderingMode(.template)
                        .frame(width: 40, height: 40)
                        .themePill(.photo)
                        .padding(8)
                }
                .accessibilityLabel(Text(verbatim: MemberCopy.addPeople))
            }
        }
        .photoFrame(radius: 18)
    }

    private func tileRow(_ n: NamedGroupWire) -> some View {
        HStack(spacing: 14) {
            // D-187: the tile chooses the group's picture.
            // D-221 (S5): laid out by its drawn side (Norse's diamond overflowed the margin and crowded the name).
            Button { choosingPicture = true } label: {
                GroupTile(group: n.group, size: ThemeArt.m("groupTile"))
                    .tileFootprint(TileShape.of(AppearanceState.shared.theme, person: false), size: ThemeArt.m("groupTile"))
            }
                .buttonStyle(.plain)
                // D-234 (L9): a copy's tile takes no tap and is not a button; never disabled (SwiftUI fades its label).
                .allowsHitTesting(!isCopy)
                .accessibilityLabel(Text(verbatim: GroupPictures.title))
                .accessibilityHidden(isCopy)
            VStack(alignment: .leading, spacing: 2) {
                Text(verbatim: n.group.name).font(TypeRole.display(TypeScale.displayName, weight: 700)).foregroundStyle(Tokens.text)
                // D-220 (L25): the line's separator is Norse's gold rune, as on every body-drawn meta line.
                Separator.text(OverviewCopy.settlesInParts(people: n.members.count, currency: n.group.settlementCurrency), size: TypeScale.detail)
                    .font(TypeRole.body(TypeScale.detail)).foregroundStyle(Tokens.textDim)
            }
            Spacer(minLength: 0)
            // D-186 (the founder): add people right from the group.
            if !isCopy {
                NavigationLink {
                    PeopleScreen(store: store, groupID: groupID) { load(); OverviewActions.changed() }
                } label: {
                    Image(uiImage: LucideGlyph.userPlus.templateImage())
                        .renderingMode(.template)
                        .foregroundStyle(Tokens.accent)
                        .frame(width: 44, height: 44)
                        .contentShape(Rectangle())
                }
                .accessibilityLabel(Text(verbatim: MemberCopy.addPeople))
            }
        }
    }

    private func statsRow(_ n: NamedGroupWire, _ net: BalanceLineOut?) -> some View {
        HStack(spacing: 8) {
            stat(OverviewCopy.totalSpent, stats?.spent.first.map { money($0.amountMinor, $0.currencyCode) } ?? money(0, n.group.settlementCurrency), Tokens.text)
            stat(OverviewCopy.yourShare, stats?.yourShare.first.map { money($0.amountMinor, $0.currencyCode) } ?? money(0, n.group.settlementCurrency), Tokens.text)
            if let net, net.viewerNetMinor != 0 {
                stat(net.viewerNetMinor > 0 ? OverviewCopy.getBack : OverviewCopy.owe,
                     money(Int64(net.viewerNetMinor.magnitude), net.currencyCode),
                     net.viewerNetMinor > 0 ? Tokens.owing : Tokens.owed)
            } else {
                stat(OverviewCopy.settledShort, Placeholder.none, Tokens.textDim)
            }
        }
    }

    private func stat(_ label: String, _ value: String, _ colour: Color) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(verbatim: label).font(TypeRole.body(12)).foregroundStyle(Tokens.textDim).lineLimit(1).minimumScaleFactor(0.8)
            Text(verbatim: value).font(TypeRole.numeric(TypeScale.statValue, weight: 600)).foregroundStyle(colour).lineLimit(1).minimumScaleFactor(0.6)
                // D-228 decision 43: the placeholder value (the Settled tile, a money fallback) is spoken as "none".
                .accessibilityLabel(Text(verbatim: Placeholder.spoken(value)))
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(10)
        .background(Tokens.surface.opacity(0.8), in: ThemeArt.panelShape(AppearanceState.shared.theme, radius: 14))
        .accessibilityElement(children: .combine)
    }

    private func categories(_ s: GroupStatsOut) -> some View {
        let tree = CategoryLevels.tree
        return VStack(alignment: .leading, spacing: 10) {
            Text(verbatim: OverviewCopy.whereItWent).sectionLabel().foregroundStyle(Tokens.text)
            GeometryReader { geo in
                HStack(spacing: 2) {
                    ForEach(Array(s.categories.enumerated()), id: \.offset) { i, c in
                        sliceColour(i).frame(width: max(2, (geo.size.width - CGFloat(s.categories.count - 1) * 2) * CGFloat(c.basisPoints) / 10_000))
                    }
                }
            }
            .frame(height: 10)
            .clipShape(ThemeArt.meterShape(AppearanceState.shared.theme))
            .accessibilityHidden(true)
            FlowLayout(spacing: 12) {
                ForEach(Array(s.categories.enumerated()), id: \.offset) { i, c in
                    HStack(spacing: 6) {
                        ThemeArt.swatchShape(AppearanceState.shared.theme).fill(sliceColour(i)).frame(width: 8, height: 8)
                        Text(verbatim: CategoryLevels.name(c.category, in: tree)).foregroundStyle(Tokens.textDim)
                        Text(verbatim: OverviewCopy.percent(c.basisPoints)).foregroundStyle(Tokens.text)
                    }
                    .font(TypeRole.body(13))
                    .accessibilityElement(children: .combine)
                }
            }
        }
        .glassCard()
    }

    private func recentRow(_ e: ProjectedExpenseOut, _ n: NamedGroupWire, chevron: Bool = true) -> some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                Text(verbatim: (e.note?.isEmpty == false ? e.note : nil) ?? CategoryLevels.name(e.category, in: CategoryLevels.tree))
                    .font(TypeRole.name(15)).foregroundStyle(Tokens.text).lineLimit(1)
                Text(verbatim: OverviewCopy.paid(LedgerStore.label(for: e.expense.payer, in: n, history: historyNames),
                                                 money(e.expense.paidMinor, e.expense.currencyCode)))
                    .font(TypeRole.body(13)).foregroundStyle(Tokens.textDim)
            }
            Spacer()
            if chevron {
                RowChevron()
            }
        }
        .contentShape(Rectangle())
        .padding(.vertical, 9)
        .accessibilityElement(children: .combine)
    }
}
