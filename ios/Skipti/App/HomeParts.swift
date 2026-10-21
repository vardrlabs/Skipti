import Foundation
import SwiftUI

// D-223 (Home, the Look Book's "Your ledgers" and "Lately"): the models and the copy Home's views read. Each is a pure
// decision so both shells can pin it with the same goldens (.drafts/d223/art.py sections 35 and 36). Android's twin is
// HomeParts.kt; its parity test reads this file's copy.

/// The hero's who-owes line (Q4 a): the first two people in the hero's currency, in core's order (largest first, D-180),
/// each phrased by sign. A positive net owes you. Amounts are the magnitude, formatted by core through `format`.
/// Empty when nobody is in that currency: the line is then not drawn.
enum WhoOwes {
    static let limit = 2

    static func parts(_ people: [PersonNetOut], currency: String?, format: (Int64, String) -> String) -> [String] {
        var out: [String] = []
        for p in people where p.currencyCode == currency && p.netMinor != 0 {
            let amount = format(abs(p.netMinor), p.currencyCode)
            if p.netMinor > 0 {
                out.append(OverviewCopy.owesYou(p.name, amount))
            } else {
                out.append(OverviewCopy.youOwe(p.name, amount, first: out.isEmpty))
            }
            if out.count == limit { break }
        }
        return out
    }
}

/// When a change happened, in words both shells say the same way (DECIDED for the founder: the two system relative
/// formatters word the same moment differently). Older than six days is the day and month, printed by the view.
enum RelativeWhen: Equatable {
    case now
    case minutes(Int)
    case hours(Int)
    case days(Int)
    case date

    /// A negative delta (a change stamped after this phone's clock) reads as now.
    static func of(atMs: UInt64, nowMs: UInt64) -> RelativeWhen {
        let d: UInt64 = atMs >= nowMs ? 0 : nowMs - atMs
        if d < 60_000 { return .now }
        if d < 3_600_000 { return .minutes(Int(d / 60_000)) }
        if d < 86_400_000 { return .hours(Int(d / 3_600_000)) }
        if d < 7 * 86_400_000 { return .days(Int(d / 86_400_000)) }
        return .date
    }

    /// The words; nil for `.date` (the day and month, `dayMonth`).
    static func text(_ when: RelativeWhen) -> String? {
        switch when {
        case .now: return "now"
        case .minutes(let n): return "\(n) min"
        case .hours(let n): return "\(n) h"
        case .days(let n): return n == 1 ? "1 day" : "\(n) days"
        case .date: return nil
        }
    }

    /// The day and month from the CLDR skeleton "dMMM" on the app's one date axis (WORKLOG decision 19: `AppLocale`,
    /// en-US "Oct 16", en-IN "16 Oct"), through `AppDate` like every other date the app shows.
    static func dayMonth(atMs: UInt64, locale: LocaleTag = AppLocale.current, timeZone: TimeZone = .current) -> String {
        AppDate.dayMonth(atMs, locale: locale, timeZone: timeZone)
    }

    /// What a row prints: the words, or the day and month for an older change.
    static func words(atMs: UInt64, nowMs: UInt64, locale: LocaleTag = AppLocale.current,
                      timeZone: TimeZone = .current) -> String {
        text(of(atMs: atMs, nowMs: nowMs)) ?? dayMonth(atMs: atMs, locale: locale, timeZone: timeZone)
    }

    /// This phone's clock in ms, for the rows' "when".
    static var nowMs: UInt64 { UInt64(max(0, Date().timeIntervalSince1970) * 1000) }
}

/// A ledger card's line (L54): "Owed ₹X", "You owe ₹X" or "Settled". The amount is a magnitude in minor units.
enum LedgerLine: Equatable {
    case owed(Int64, String)
    case youOwe(Int64, String)
    case settled

    /// The line's currency, which picks the meter's block; nil when settled.
    var currency: String? {
        switch self {
        case .owed(_, let code), .youOwe(_, let code): return code
        case .settled: return nil
        }
    }
}

enum LedgerCards {
    /// The group's first net (core's order) decides the line; no net, or a zero one, is settled.
    static func line(_ nets: [CurrencyAmountOut]) -> LedgerLine {
        guard let first = nets.first else { return .settled }
        if first.amountMinor > 0 { return .owed(first.amountMinor, first.currencyCode) }
        if first.amountMinor < 0 { return .youOwe(abs(first.amountMinor), first.currencyCode) }
        return .settled
    }

    /// The meter's basis points: the block in the line's currency, else the first block (the settlement currency's,
    /// E4 Design 2). Nil with no blocks: a group with no expense or settlement draws no meter (DECIDED for the founder).
    static func basisPoints(_ blocks: [SettledProgressOut], currency: String?) -> UInt32? {
        (blocks.first { $0.currencyCode == currency } ?? blocks.first)?.basisPoints
    }
}


/// Home's Lately card (L53): the newest entries of D-189's feed. Hidden when empty; Home never marks the feed seen.
enum Lately {
    static let count = 3

    static func pick(_ entries: [ActivityFeed.Entry]) -> [ActivityFeed.Entry] { Array(entries.prefix(count)) }
}

enum LedgerCopy {
    static let title = "Your ledgers"
    static let owed = "Owed"
    static let youOwe = "You owe"
    /// The card's spoken percent, n = basis points / 100, floored.
    static func settledPercent(_ n: Int) -> String { "\(n)% settled" }
    static let loadError = "Could not load the ledger"
}

enum LatelyCopy {
    static let title = "Lately"
    /// A1: the whole-ledger Activity screen (orphaned since D-196) is reached from Lately's header.
    static let seeAll = "See all"
}

/// A2: Expenses' empty state is SPEC section 11.6's line verbatim, split into the drawn state's title and detail.
enum EmptyCopy {
    static let expensesTitle = "Nothing here yet"
    static let expensesDetail = "Add your first expense, or import from another app."
}

extension OverviewCopy {
    /// The who-owes line's parts.
    static func owesYou(_ name: String, _ amount: String) -> String { "\(name) owes you \(amount)" }
    static func youOwe(_ name: String, _ amount: String, first: Bool) -> String {
        first ? "You owe \(name) \(amount)" : "you owe \(name) \(amount)"
    }
}

// MARK: - D-223 Task 3: the views

/// The ledger grid (L52): the cards in order, two columns (one at accessibility text sizes), `ledgerGridGap` between
/// columns and rows. Cards in a row share its height; an odd last card takes one column, not the full width. It lays
/// out any ordered list (Design 9: unit H prepends "Just me" without a change here). Android's twin is `LedgerGrid`
/// in HomeParts.kt.
struct LedgerGrid: View {
    let count: Int
    /// The card at an index, in the list's order.
    let card: (Int) -> AnyView
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    /// Two columns; one at accessibility text sizes.
    static func columns(accessibilitySize: Bool) -> Int { accessibilitySize ? 1 : 2 }

    /// The cards' indexes chunked into rows, in order; an odd last card stands alone in its row.
    static func rows(count: Int, columns: Int) -> [[Int]] {
        let width = max(columns, 1)
        return stride(from: 0, to: count, by: width).map { Array($0 ..< min($0 + width, count)) }
    }

    var body: some View {
        let gap = ThemeArt.m("ledgerGridGap")
        let columns = LedgerGrid.columns(accessibilitySize: dynamicTypeSize.isAccessibilitySize)
        Grid(horizontalSpacing: gap, verticalSpacing: gap) {
            ForEach(LedgerGrid.rows(count: count, columns: columns), id: \.self) { row in
                GridRow {
                    ForEach(row, id: \.self) { i in
                        card(i).frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
                    }
                    // The empty column keeps an odd last card at one column's width; it takes no height.
                    ForEach(row.count ..< columns, id: \.self) { _ in
                        Color.clear.frame(maxWidth: .infinity).gridCellUnsizedAxes(.vertical)
                    }
                }
            }
        }
    }
}

/// One ledger card (L54): the tile (a slot, at `ledgerTile`) and the name in a row, the line ("Owed ₹X", "You owe ₹X",
/// "Settled"), and the small settled meter when the group has blocks, on the glass card. The whole card is one
/// accessibility element. Android's twin is `LedgerCard` in HomeParts.kt.
struct LedgerCard<Tile: View>: View {
    let name: String
    /// The group's nets, core's order (`OverviewOut.groups`).
    let nets: [CurrencyAmountOut]
    /// E4's settled blocks for the group, settlement currency first.
    let blocks: [SettledProgressOut]
    /// The tile at the size it is given.
    @ViewBuilder let tile: (CGFloat) -> Tile

    var body: some View {
        let line = LedgerCards.line(nets)
        let basisPoints = LedgerCards.basisPoints(blocks, currency: line.currency)
        VStack(alignment: .leading, spacing: ThemeArt.m("ledgerCardGap")) {
            HStack(spacing: ThemeArt.m("ledgerTileGap")) {
                tile(ThemeArt.m("ledgerTile"))
                Text(verbatim: name)
                    .font(TypeRole.name(TypeScale.ledgerName))
                    .foregroundStyle(Tokens.text)
                    .lineLimit(1)
                    .truncationMode(.tail)
            }
            Text(Self.words(line))
                .font(TypeRole.body(TypeScale.detail))
                .foregroundStyle(Tokens.textDim)
                .lineLimit(2)
                .fixedSize(horizontal: false, vertical: true)
            if let basisPoints {
                SettledMeter(fraction: SettledMeter.fraction(basisPoints), big: false)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .glassCard()
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text(verbatim: Self.spoken(name: name, line: line, basisPoints: basisPoints)))
    }

    /// The line's words: "Owed " or "You owe " in the line's dim, then core's amount in the numeric face at 600 in the
    /// money colour (D-180: `owing` is money coming to you); "Settled" alone in the dim.
    static func words(_ line: LedgerLine) -> AttributedString {
        func amount(_ minor: Int64, _ code: String, _ colour: Color) -> AttributedString {
            var out = AttributedString(RolesMoney.format(minor, code) ?? "")
            out.font = TypeRole.numeric(TypeScale.detail, weight: 600)
            out.foregroundColor = colour
            return out
        }
        switch line {
        case .owed(let minor, let code): return AttributedString(LedgerCopy.owed + " ") + amount(minor, code, Tokens.owing)
        case .youOwe(let minor, let code): return AttributedString(LedgerCopy.youOwe + " ") + amount(minor, code, Tokens.owed)
        case .settled: return AttributedString(OverviewCopy.settledShort)
        }
    }

    /// "Goa trip, Owed one thousand eight hundred rupees, 72% settled": core's spoken money (SPEC section 11.7).
    static func spoken(name: String, line: LedgerLine, basisPoints: UInt32?) -> String {
        var parts = [name]
        switch line {
        case .owed(let minor, let code): parts.append(LedgerCopy.owed + " " + RolesMoney.spoken(minor, code))
        case .youOwe(let minor, let code): parts.append(LedgerCopy.youOwe + " " + RolesMoney.spoken(minor, code))
        case .settled: parts.append(OverviewCopy.settledShort)
        }
        if let basisPoints { parts.append(LedgerCopy.settledPercent(SettledSummary.percent(basisPoints))) }
        return parts.joined(separator: ", ")
    }
}

/// Home's Lately card (L53, A1): "Lately" (the section label, inside the card) with "See all", which opens the
/// whole-ledger Activity screen, then the newest three rows, `latelyRowGap` apart, no dividers. Home shows it only
/// when it has entries, and never marks the feed seen. "See all" and a row that can open its expense say so through
/// `onSeeAll` and `onOpen`; the screen owns both pushes (their destinations hang on Home's always-present container,
/// CLAUDE.md 10.1: this card vanishes when the feed empties, and a push inside it would pop).
struct LatelyCard: View {
    let feed: ActivityFeed.Loaded
    /// The clock the rows' "3 min" words are read against: Home's, set on every read (R4), never taken in a body.
    let nowMs: UInt64
    let onSeeAll: () -> Void
    let onOpen: (LatelyOpen) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: ThemeArt.m("latelyRowGap")) {
            HStack(alignment: .center, spacing: 8) {
                Text(verbatim: LatelyCopy.title).sectionLabel().foregroundStyle(Tokens.text)
                Spacer(minLength: 8)
                // D21: the link sits on the card's surface, so it takes `accent`, as every in-card link does.
                Button { onSeeAll() } label: {
                    Text(verbatim: LatelyCopy.seeAll)
                        .font(TypeRole.body(TypeScale.detail))
                        .foregroundStyle(Tokens.accent)
                        .frame(minHeight: 44)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
            ForEach(Lately.pick(feed.entries)) { entry in
                if let open = LatelyOpen.of(entry, feed: feed) {
                    Button { onOpen(open) } label: { LatelyRow(entry: entry, feed: feed, nowMs: nowMs) }
                        .buttonStyle(.plain)
                        .accessibilityHint(Text(verbatim: OverviewCopy.openExpense))
                } else {
                    LatelyRow(entry: entry, feed: feed, nowMs: nowMs)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .glassCard()
    }
}

/// An expense a Lately row opens: one not deleted, in a group this phone still holds.
struct LatelyOpen: Hashable, Identifiable {
    let expenseID: String
    let named: NamedGroupWire
    var id: String { expenseID }

    static func of(_ entry: ActivityFeed.Entry, feed: ActivityFeed.Loaded) -> LatelyOpen? {
        guard case .expense(let item) = entry, item.kind != .deleted, let named = feed.groups[item.group] else { return nil }
        return LatelyOpen(expenseID: item.expenseID, named: named)
    }
}

/// One Lately row: the soft initials avatar in the theme's person shape, then what happened in the name role and
/// "group, amount, when" through the theme's separator. A deletion's words are dim; a moved balance has no amount.
struct LatelyRow: View {
    let entry: ActivityFeed.Entry
    let feed: ActivityFeed.Loaded
    let nowMs: UInt64

    var body: some View {
        let words = LatelyRow.words(entry, feed: feed, nowMs: nowMs)
        HStack(alignment: .center, spacing: ThemeArt.m("latelyAvatarGap")) {
            PersonAvatar(name: words.who, size: ThemeArt.m("latelyAvatar"), fill: .soft)
            VStack(alignment: .leading, spacing: 2) {
                Text(verbatim: words.line)
                    .font(TypeRole.name(TypeScale.feedLine))
                    .foregroundStyle(words.dim ? Tokens.textDim : Tokens.text)
                Separator.text(words.meta, size: TypeScale.detail)
                    .font(TypeRole.body(TypeScale.detail))
                    .foregroundStyle(Tokens.textDim)
            }
            Spacer(minLength: 0)
        }
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
    }

    /// Who, what and the meta line's parts, as Activity words them (`ActivityFeed.line`, `movedLine`).
    static func words(_ entry: ActivityFeed.Entry, feed: ActivityFeed.Loaded, nowMs: UInt64)
        -> (who: String, line: String, dim: Bool, meta: [String]) {
        switch entry {
        case .expense(let item):
            let named = feed.groups[item.group]
            let who = ActivityFeed.who(item, groups: feed.groups, names: feed.names)
            let meta = [named?.group.name, RolesMoney.format(item.amountMinor, item.currency),
                        RelativeWhen.words(atMs: item.atMs, nowMs: nowMs)]
            return (who, ActivityFeed.line(item.kind, who: who, title: item.title), item.kind == .deleted,
                    meta.compactMap { $0 }.filter { !$0.isEmpty })
        case .moved(let m):
            let who = feed.names[m.group]?[m.from] ?? LedgerStore.unknownName
            let meta = [feed.groups[m.group]?.group.name, RelativeWhen.words(atMs: m.atMs, nowMs: nowMs)]
            return (who, feed.movedLines[m.id] ?? "", false, meta.compactMap { $0 }.filter { !$0.isEmpty })
        }
    }
}

/// The drawn empty state (L-D217-10): Lucide's inbox at `emptyIcon` in the dim, 12 under it the title in the name
/// role at `emptyTitle`, 4 under that the detail in the body face in the dim (Design 7's gaps); centred, padding 16,
/// in the space under the bar. Android's twin is `EmptyState` in HomeParts.kt.
struct EmptyState: View {
    let title: String
    let detail: String

    var body: some View {
        VStack(spacing: 0) {
            LucideIcon(glyph: .inbox, size: ThemeArt.m("emptyIcon"))
                .foregroundStyle(Tokens.textDim)
                .accessibilityHidden(true)
            Text(verbatim: title)
                .font(TypeRole.name(TypeScale.emptyTitle))
                .foregroundStyle(Tokens.text)
                .multilineTextAlignment(.center)
                .padding(.top, 12)
            Text(verbatim: detail)
                .font(TypeRole.body())
                .foregroundStyle(Tokens.textDim)
                .multilineTextAlignment(.center)
                .padding(.top, 4)
        }
        .padding(16)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}
