import SwiftUI
import XCTest
@testable import Skipti

/// D-223 (Home, the Look Book's "Your ledgers" and "Lately"): the pure decisions and the copy. The goldens are
/// `.drafts/d223/art.out` sections 35 and 36 (worked in Python by `.drafts/d223/art.py`), typed here from that output,
/// never read back from the Swift. Android's twin is HomePartsTest.
final class HomePartsTests: XCTestCase {
    private func person(_ name: String, _ code: String, _ net: Int64) -> PersonNetOut {
        PersonNetOut(name: name, currencyCode: code, netMinor: net, groupIds: ["g"])
    }

    /// art.out 36, cases A to F, with a stub formatter so the words are pinned apart from core's money format.
    func testWhoOwesGoldens() {
        let stub: (Int64, String) -> String = { minor, _ in "\(minor)" }
        let cases: [(String, [PersonNetOut], String, [String])] = [
            ("A the Look Book", [person("Meera", "INR", 180000), person("Sam", "INR", -56000)], "INR",
             ["Meera owes you 180000", "you owe Sam 56000"]),
            ("B you owe first", [person("Sam", "INR", -90000), person("Meera", "INR", 20000)], "INR",
             ["You owe Sam 90000", "Meera owes you 20000"]),
            ("C three people, two shown",
             [person("Asha", "INR", 50000), person("Ravi", "INR", 40000), person("Sam", "INR", 1000)], "INR",
             ["Asha owes you 50000", "Ravi owes you 40000"]),
            ("D another currency skipped", [person("Lea", "EUR", 9000), person("Meera", "INR", 180000)], "INR",
             ["Meera owes you 180000"]),
            ("E one person", [person("Meera", "INR", -2500)], "INR", ["You owe Meera 2500"]),
            // The negative: nobody in the hero's currency, so the line is not drawn.
            ("F nobody in the currency", [person("Lea", "EUR", 9000)], "INR", []),
        ]
        for (name, people, currency, expected) in cases {
            let parts = WhoOwes.parts(people, currency: currency, format: stub)
            XCTAssertEqual(parts, expected, name)
            XCTAssertLessThanOrEqual(parts.count, 2, name)
        }
        XCTAssertEqual(WhoOwes.parts([], currency: "INR", format: stub), [])
        XCTAssertEqual(WhoOwes.parts([person("Meera", "INR", 100)], currency: nil, format: stub), [], "no hero currency")
    }

    /// art.out 35: ms since the change to words; a negative delta reads as now.
    func testRelativeWhenGoldens() {
        let now: UInt64 = 1_800_000_000_000
        let rows: [(Int64, RelativeWhen)] = [
            (-5000, .now), (0, .now), (59_999, .now), (60_000, .minutes(1)), (119_999, .minutes(1)),
            (120_000, .minutes(2)), (3_599_999, .minutes(59)), (3_600_000, .hours(1)), (86_399_999, .hours(23)),
            (86_400_000, .days(1)), (172_799_999, .days(1)), (172_800_000, .days(2)), (604_799_999, .days(6)),
            (604_800_000, .date),
        ]
        XCTAssertEqual(rows.count, 14)
        for (delta, expected) in rows {
            let at = UInt64(Int64(now) - delta)
            XCTAssertEqual(RelativeWhen.of(atMs: at, nowMs: now), expected, "\(delta)")
        }
        let words: [(RelativeWhen, String)] = [
            (.now, "now"), (.minutes(1), "1 min"), (.minutes(59), "59 min"), (.hours(1), "1 h"), (.hours(23), "23 h"),
            (.days(1), "1 day"), (.days(2), "2 days"), (.days(6), "6 days"),
        ]
        for (when, text) in words { XCTAssertEqual(RelativeWhen.text(when), text) }
        XCTAssertNil(RelativeWhen.text(.date), "the date branch is the day and month, printed by the view")
        // The words a view prints: a recent change in words, an old one as a non-empty day and month.
        XCTAssertEqual(RelativeWhen.words(atMs: now - 120_000, nowMs: now), "2 min")
        XCTAssertFalse(RelativeWhen.words(atMs: now - 604_800_000, nowMs: now).isEmpty)
    }

    func testLedgerLine() {
        XCTAssertEqual(LedgerCards.line([CurrencyAmountOut(currencyCode: "INR", amountMinor: 180000)]), .owed(180000, "INR"))
        XCTAssertEqual(LedgerCards.line([CurrencyAmountOut(currencyCode: "INR", amountMinor: -56000),
                                         CurrencyAmountOut(currencyCode: "EUR", amountMinor: 100)]),
                       .youOwe(56000, "INR"), "the first net only, as a magnitude")
        XCTAssertEqual(LedgerCards.line([]), .settled)
        XCTAssertEqual(LedgerLine.owed(1, "INR").currency, "INR")
        XCTAssertEqual(LedgerLine.youOwe(1, "EUR").currency, "EUR")
        XCTAssertNil(LedgerLine.settled.currency)
    }

    func testLedgerMeterBlock() {
        let blocks = [
            SettledProgressOut(currencyCode: "INR", settledMinor: 72, outstandingMinor: 28, basisPoints: 7200, settled: false),
            SettledProgressOut(currencyCode: "EUR", settledMinor: 50, outstandingMinor: 50, basisPoints: 5000, settled: false),
        ]
        XCTAssertEqual(LedgerCards.basisPoints(blocks, currency: "EUR"), 5000)
        XCTAssertEqual(LedgerCards.basisPoints(blocks, currency: "USD"), 7200, "absent: the first block's")
        XCTAssertEqual(LedgerCards.basisPoints(blocks, currency: nil), 7200, "settled line: the first block's")
        XCTAssertNil(LedgerCards.basisPoints([], currency: "INR"), "no blocks, no meter")
    }

    func testLedgerGridRows() {
        XCTAssertEqual(LedgerGrid.rows(count: 0, columns: 2), [])
        XCTAssertEqual(LedgerGrid.rows(count: 1, columns: 2), [[0]])
        XCTAssertEqual(LedgerGrid.rows(count: 3, columns: 2), [[0, 1], [2]])
        XCTAssertEqual(LedgerGrid.rows(count: 4, columns: 2), [[0, 1], [2, 3]])
        XCTAssertEqual(LedgerGrid.rows(count: 3, columns: 1), [[0], [1], [2]])
        XCTAssertEqual(LedgerGrid.columns(accessibilitySize: false), 2)
        XCTAssertEqual(LedgerGrid.columns(accessibilitySize: true), 1)
    }

    private func item(_ id: String, _ at: UInt64) -> ActivityFeed.Item {
        ActivityFeed.Item(expenseID: id, group: "g", kind: .added, by: "p", atMs: at, title: "t", amountMinor: 100, currency: "INR")
    }

    private func moved(_ id: String, _ at: UInt64) -> ActivityFeed.Moved {
        ActivityFeed.Moved(id: id, group: "g", atMs: at, from: "p", currency: "INR", amountMinor: 100, to: ["q"])
    }

    func testLatelyPick() {
        XCTAssertEqual(Lately.count, 3)
        let five = (1...5).map { ActivityFeed.Entry.expense(item("e\($0)", UInt64(100 - $0))) }
        XCTAssertEqual(Lately.pick(five).map(\.id), ["e1", "e2", "e3"])
        XCTAssertEqual(Lately.pick(Array(five.prefix(2))).map(\.id), ["e1", "e2"])
        XCTAssertEqual(Lately.pick([]).map(\.id), [])
    }

    /// The rule ActivityScreen used before the lift, re-derived by hand: newest first, ties by id descending.
    func testActivityEntriesNewestFirst() {
        let items = [item("a", 10), item("c", 30), item("b", 20)]
        let movedOnes = [moved("moved-x", 25), moved("moved-y", 20)]
        // By hand: 30 c; 25 moved-x; 20 ties between "moved-y" and "b": "moved-y" > "b", so moved-y first; 10 a.
        XCTAssertEqual(ActivityFeed.entries(items, movedOnes).map(\.id), ["c", "moved-x", "moved-y", "b", "a"])
        XCTAssertEqual(ActivityFeed.entries(items, movedOnes).map(\.atMs), [30, 25, 20, 20, 10])
        XCTAssertEqual(ActivityFeed.entries([], []).map(\.id), [])
        let many = (0..<60).map { item("e\($0)", UInt64($0)) }
        XCTAssertEqual(ActivityFeed.entries(many, []).count, ActivityFeed.limit)
    }

    /// Global Constraints' copy, verbatim (Android's parity test reads these from this file).
    func testHomeCopyIsVerbatim() {
        XCTAssertEqual(LedgerCopy.title, "Your ledgers")
        XCTAssertEqual(LedgerCopy.owed, "Owed")
        XCTAssertEqual(LedgerCopy.youOwe, "You owe")
        XCTAssertEqual(LedgerCopy.settledPercent(72), "72% settled")
        XCTAssertEqual(LedgerCopy.loadError, "Could not load the ledger")
        XCTAssertEqual(LatelyCopy.title, "Lately")
        XCTAssertEqual(OverviewCopy.owesYou("Meera", "₹1,800.00"), "Meera owes you ₹1,800.00")
        XCTAssertEqual(OverviewCopy.youOwe("Sam", "₹560.00", first: true), "You owe Sam ₹560.00")
        XCTAssertEqual(OverviewCopy.youOwe("Sam", "₹560.00", first: false), "you owe Sam ₹560.00")
        XCTAssertEqual(RelativeWhen.text(.now), "now")
        XCTAssertEqual(RelativeWhen.text(.minutes(5)), "5 min")
        XCTAssertEqual(RelativeWhen.text(.hours(5)), "5 h")
        XCTAssertEqual(RelativeWhen.text(.days(1)), "1 day")
        XCTAssertEqual(RelativeWhen.text(.days(5)), "5 days")
        XCTAssertEqual(OverviewCopy.settledShort, "Settled", "kept")
        let all = [LedgerCopy.title, LedgerCopy.owed, LedgerCopy.youOwe, LedgerCopy.settledPercent(1), LedgerCopy.loadError,
                   LatelyCopy.title, OverviewCopy.owesYou("a", "b"), OverviewCopy.youOwe("a", "b", first: true)]
        for text in all { XCTAssertFalse(text.contains("\u{2014}"), "no em dash: \(text)") } // em-dash-ok: the test asserts this dash is absent
    }

    // ---- D-223 Task 3: the views, guarded at their sources ----

    private func appSource(_ file: String) throws -> String {
        let url = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("Skipti/App/\(file)")
        return try String(contentsOf: url, encoding: .utf8)
    }

    /// The text after the first `from`, up to the first `to` after it.
    private func slice(_ text: String, from: String, to: String) throws -> String {
        let after = try XCTUnwrap(text.components(separatedBy: from).dropFirst().first, "anchor \(from)")
        return try XCTUnwrap(after.components(separatedBy: to).first, "anchor \(to)")
    }

    private func count(_ text: String, _ needle: String) -> Int { text.components(separatedBy: needle).count - 1 }

    /// Design 1: the greeting, the hero, "Your ledgers" and its grid, Lately, then People.
    func testHomeOrder() throws {
        let body = try slice(try appSource("OverviewScreen.swift"), from: "struct OverviewScreen: View {",
                             to: "private func loadUsername()")
        var last = body.startIndex
        for anchor in ["HomeGreeting(", "hero(overview)", "section(LedgerCopy.title)", "LedgerGrid(", "LatelyCard(",
                       "section(OverviewCopy.people)"] {
            XCTAssertEqual(count(body, anchor), 1, "once: \(anchor)")
            guard let found = body.range(of: anchor, range: last..<body.endIndex) else {
                XCTFail("out of order, or missing: \(anchor)")
                return
            }
            last = found.upperBound
        }
    }

    /// L55: "By group" and its horizontal scroller are gone (and the halo with them).
    func testHomeHasNoScroller() throws {
        let overview = try appSource("OverviewScreen.swift")
        XCTAssertFalse(overview.contains("ScrollView(.horizontal"), "a horizontal scroller on Home")
        XCTAssertFalse(overview.contains("groupCard("), "the old group card")
        XCTAssertFalse(overview.contains("OverviewCopy.byGroup"), "By group")
        XCTAssertFalse(overview.contains("\"By group\""), "By group's copy")
    }

    /// Design 2 (Q4 a): the who-owes line under the amount, at the hero line's size in the hero's dim, joined by the
    /// theme's separator; Settle up stays.
    func testTheHeroNamesWhoOwes() throws {
        let hero = try slice(try appSource("OverviewScreen.swift"), from: "private func hero(", to: "private func personRow(")
        XCTAssertEqual(count(hero, "WhoOwes.parts("), 1)
        XCTAssertTrue(hero.contains("Separator.text("), "joined by the theme's separator")
        XCTAssertTrue(hero.contains("size: TypeScale.heroLine)"), "the separator at the line's size")
        XCTAssertTrue(hero.contains("Tokens.onHero.opacity(0.82)"), "the hero's dim")
        XCTAssertTrue(hero.contains("OverviewCopy.settleUp"), "Settle up stays")
        let who = try slice(hero, from: "WhoOwes.parts(", to: "FlowChips(")
        XCTAssertTrue(who.contains(".font(TypeRole.body(TypeScale.heroLine))"), "the line's size")
        XCTAssertTrue(who.contains(".lineLimit(2)"), "at most two lines")
    }

    /// L52, L54: the Look Book's card: the tile and name in a row, the line, the small meter, on the glass card; the
    /// grid's gap and rows. No shell formatting of money.
    func testTheLedgerCardIsTheLookBooks() throws {
        let parts = try appSource("HomeParts.swift")
        let card = try slice(parts, from: "struct LedgerCard<", to: "struct LatelyCard:")
        for needle in ["ThemeArt.m(\"ledgerTile\")", "ThemeArt.m(\"ledgerTileGap\")", "ThemeArt.m(\"ledgerCardGap\")",
                       "TypeRole.name(TypeScale.ledgerName)", "SettledMeter(", ".glassCard()", "LedgerCards.line(",
                       "LedgerCards.basisPoints(", "TypeRole.numeric(TypeScale.detail, weight: 600)"] {
            XCTAssertTrue(card.contains(needle), "LedgerCard: \(needle)")
        }
        XCTAssertEqual(count(card, "SettledMeter("), 1)
        XCTAssertTrue(card.contains("big: false"), "the small meter")
        XCTAssertFalse(card.contains("big: true"), "never the big meter")
        let grid = try slice(parts, from: "struct LedgerGrid: View {", to: "struct LedgerCard<")
        XCTAssertTrue(grid.contains("ThemeArt.m(\"ledgerGridGap\")"), "the grid's gap")
        XCTAssertTrue(grid.contains("LedgerGrid.rows("), "the grid's rows")
        XCTAssertTrue(grid.contains("LedgerGrid.columns(accessibilitySize: dynamicTypeSize.isAccessibilitySize)"))
        XCTAssertTrue(grid.contains(".frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)"),
                      "cards in a row share its height")
        XCTAssertFalse(parts.contains("NumberFormatter"), "money is formatted by core")
        XCTAssertFalse(parts.contains("String(format:"), "money is formatted by core")
    }

    /// L53: Lately's rows: the soft initials avatar in the person shape, the meta line through the theme's separator,
    /// the words of `RelativeWhen`; the card's gap and pick; Home shows it only when it has entries.
    func testLatelyRowsAreSoftAndShared() throws {
        let parts = try appSource("HomeParts.swift")
        let row = try slice(parts, from: "struct LatelyRow: View {", to: "struct EmptyState: View {")
        for needle in ["PersonAvatar(", "fill: .soft", "ThemeArt.m(\"latelyAvatar\")", "ThemeArt.m(\"latelyAvatarGap\")",
                       "Separator.text(", "RelativeWhen.", "TypeRole.name(TypeScale.feedLine)"] {
            XCTAssertTrue(row.contains(needle), "LatelyRow: \(needle)")
        }
        let card = try slice(parts, from: "struct LatelyCard: View {", to: "struct LatelyRow: View {")
        for needle in ["ThemeArt.m(\"latelyRowGap\")", "Lately.pick(", "LatelyCopy.title", "LatelyCopy.seeAll", ".glassCard()"] {
            XCTAssertTrue(card.contains(needle), "LatelyCard: \(needle)")
        }
        let overview = try appSource("OverviewScreen.swift")
        XCTAssertEqual(count(overview, "LatelyCard("), 1)
        let lines = overview.components(separatedBy: "\n")
        let at = try XCTUnwrap(lines.firstIndex { $0.contains("LatelyCard(") })
        let guardLine = lines[at - 1].trimmingCharacters(in: .whitespaces)
        XCTAssertTrue(guardLine.hasPrefix("if !") && guardLine.hasSuffix(".isEmpty {"),
                      "Lately is shown only when it has entries: \(guardLine)")
    }

    /// Home's modifiers that hang on its always-present container (the ZStack, CLAUDE.md 10.1): the text between the
    /// container's title mode and its task, outside every branch a sync can flip.
    private func homeContainerModifiers() throws -> String {
        try slice(try appSource("OverviewScreen.swift"), from: ".navigationBarTitleDisplayMode(.large)\n",
                  to: ".task(id: SyncStatus.shared.revision)")
    }

    /// M1 (CLAUDE.md 10.1): "See all" is a Button that sets Home's own state; the push hangs on the always-present
    /// container, so emptying the feed (which hides the card) never pops Activity. Twin: Android's
    /// see_all_opens_the_whole_ledgers_activity (the shell's route). D21: the link takes `accent`, the in-card link token.
    func testSeeAllPushesFromHomesContainer() throws {
        let card = try slice(try appSource("HomeParts.swift"), from: "struct LatelyCard: View {", to: "struct LatelyRow: View {")
        XCTAssertFalse(card.contains("NavigationLink"), "no push inside a card that can vanish")
        XCTAssertFalse(card.contains("ActivityScreen("), "the card does not own the destination")
        XCTAssertTrue(card.contains("let onSeeAll: () -> Void"))
        XCTAssertTrue(card.contains("Button { onSeeAll() } label: {"), "See all is a Button")
        let link = try slice(card, from: "Text(verbatim: LatelyCopy.seeAll)", to: ".buttonStyle(.plain)")
        XCTAssertTrue(link.contains(".foregroundStyle(Tokens.accent)"), "D21: the in-card link token: \(link)")
        XCTAssertFalse(link.contains("groundAccent"), "D21: not the page-ground token")
        XCTAssertTrue(link.contains(".frame(minHeight: 44)"), "a 44 pt target")
        let overview = try appSource("OverviewScreen.swift")
        XCTAssertTrue(overview.contains("@State private var seeAll = false"))
        XCTAssertTrue(overview.contains("onSeeAll: { seeAll = true }"), "the card sets Home's state")
        XCTAssertEqual(count(overview, "ActivityScreen(store: store, group: nil)"), 1)
        XCTAssertTrue(try homeContainerModifiers().contains("""
        .navigationDestination(isPresented: $seeAll) {
                    ActivityScreen(store: store, group: nil)
                }
        """), "the whole-ledger Activity hangs on the container")
    }

    /// M2: a Lately row opens its expense through Home's state, pushed from the container; Home only reads the feed,
    /// never marks it seen (Activity does). Twins: Android's the_lately_rows_open_the_expense and
    /// lately_rows_are_soft_and_shared's seen check.
    func testLatelyOpensFromHomesContainerAndNeverMarksSeen() throws {
        let card = try slice(try appSource("HomeParts.swift"), from: "struct LatelyCard: View {", to: "struct LatelyRow: View {")
        XCTAssertTrue(card.contains("if let open = LatelyOpen.of(entry, feed: feed) {"), "the rule decides")
        XCTAssertTrue(card.contains("Button { onOpen(open) } label: { LatelyRow(entry: entry, feed: feed, nowMs: nowMs) }"))
        let overview = try appSource("OverviewScreen.swift")
        XCTAssertTrue(overview.contains("{ latelyOpen = $0 }"), "the row's callback sets Home's state")
        XCTAssertTrue(try homeContainerModifiers().contains("""
        .navigationDestination(item: $latelyOpen) { open in
                    ExpenseDetailView(store: store, expenseID: open.expenseID, named: open.named)
                }
        """), "the expense hangs on the container")
        XCTAssertFalse(overview.contains("setActivitySeenUpTo"), "Home never marks the feed seen")
        XCTAssertFalse(try appSource("HomeParts.swift").contains("setActivitySeenUpTo"), "Lately never marks the feed seen")
    }

    /// M2: the rule a Lately row opens by. A live expense in a group this phone holds opens (its id and group); a
    /// deletion, an expense in a group this phone no longer holds, and a moved balance do not. Twin: Android's
    /// `item.kind != ActivityFeed.Kind.DELETED && named != null`.
    func testLatelyOpenRule() {
        let goa = NamedGroupWire(group: GroupWire(id: "g", name: "Goa", settlementCurrency: "INR", members: ["m"]),
                                 selfId: "m", members: [MemberWire(id: "m", name: "Tim")])
        var feed = ActivityFeed.Loaded()
        feed.groups = ["g": goa]
        func entry(_ kind: ActivityFeed.Kind, group: String = "g") -> ActivityFeed.Entry {
            .expense(ActivityFeed.Item(expenseID: "e1", group: group, kind: kind, by: "p", atMs: 1, title: "Taxi",
                                       amountMinor: 1_000, currency: "INR"))
        }
        XCTAssertEqual(LatelyOpen.of(entry(.added), feed: feed), LatelyOpen(expenseID: "e1", named: goa))
        XCTAssertEqual(LatelyOpen.of(entry(.changed), feed: feed)?.expenseID, "e1")
        XCTAssertEqual(LatelyOpen.of(entry(.changed), feed: feed)?.named, goa)
        XCTAssertNil(LatelyOpen.of(entry(.deleted), feed: feed), "a deletion has nothing to open")
        XCTAssertNil(LatelyOpen.of(entry(.added, group: "gone"), feed: feed), "a group this phone does not hold")
        XCTAssertNil(LatelyOpen.of(.moved(moved("moved-1", 1)), feed: feed), "a moved balance is not an expense")
    }

    /// M2: a ledger card is one VoiceOver element in core's spoken money (SPEC section 11.7), never the formatted
    /// string; the percent is basis points over 100, floored; no meter, no percent. Android's
    /// a_ledger_card_speaks_in_cores_words, the same strings.
    func testALedgerCardSpeaksInCoresWords() throws {
        let spoken = try ffiMoneySpoken(minorUnits: 180_000, currencyCode: "INR", locale: AppLocale.current)
        XCTAssertTrue(!spoken.isEmpty && !spoken.contains("1,800"), "core says something: \(spoken)")
        XCTAssertEqual(LedgerCard<EmptyView>.spoken(name: "Goa trip", line: .owed(180_000, "INR"), basisPoints: 7_299),
                       "Goa trip, Owed \(spoken), 72% settled")
        XCTAssertEqual(LedgerCard<EmptyView>.spoken(name: "Goa trip", line: .youOwe(180_000, "INR"), basisPoints: nil),
                       "Goa trip, You owe \(spoken)")
        XCTAssertEqual(LedgerCard<EmptyView>.spoken(name: "Flat 4B", line: .settled, basisPoints: 10_000),
                       "Flat 4B, Settled, 100% settled")
    }

    /// Design 6: Activity's rows say when through `RelativeWhen`, and the screen reads the shared load.
    func testActivityUsesRelativeWhen() throws {
        let feed = try appSource("ActivityFeed.swift")
        XCTAssertFalse(feed.contains(".formatted(.relative"), "the system's relative words")
        let screen = try slice(feed, from: "struct ActivityScreen: View {", to: "\n}\n")
        XCTAssertTrue(screen.contains("ActivityFeed.load("), "the shared read")
        XCTAssertEqual(count(screen, "RelativeWhen.words("), 2, "both kinds of row")
    }

    /// Views-extra 2 (CLAUDE.md 10.1): Home is the launch path, so a vault read that fails is never "nothing yet".
    /// The shared read throws; Home's load takes it inside its own catch; Activity says it could not load.
    func testNoVaultErrorIsSwallowedOnHome() throws {
        let feed = try appSource("ActivityFeed.swift")
        let load = try slice(feed, from: "static func load(_ store: LedgerStore, group: String?)", to: "extension LedgerStore {")
        XCTAssertTrue(load.hasPrefix(" throws -> Loaded"), "the shared read throws")
        XCTAssertFalse(load.contains("try?"), "a vault read swallowed in the shared read")
        let screen = try slice(feed, from: "struct ActivityScreen: View {", to: "\n}\n")
        XCTAssertTrue(screen.contains("LedgerCopy.loadError"), "Activity says it could not load")
        let homeLoad = try slice(try appSource("OverviewScreen.swift"), from: "    private func load() {", to: "    private func hero(")
        let inDo = try slice(homeLoad, from: "do {", to: "} catch")
        XCTAssertTrue(inDo.contains("try ActivityFeed.load(store, group: nil)"), "Lately is read inside Home's own catch")
        // Android's condition: no read (so no self record written) on a phone with no group.
        XCTAssertTrue(inDo.contains("let feed = home.named.isEmpty ? ActivityFeed.Loaded() : try ActivityFeed.load(store, group: nil)"),
                      "Lately is read only with groups")
    }

    /// The negative behind the guard: a group list that cannot be read makes the shared read throw, never empty.
    func testAnUnreadableVaultIsAnErrorNotAnEmptyFeed() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("home-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        let path = dir.appendingPathComponent("ledger.db").path
        let store = LedgerStore(path: path)
        try store.setYourName("Tim")
        _ = try store.createGroup(name: "Goa", settlementCurrency: "INR", people: ["Asha"])
        let vault = try VaultGate.open(path: path, keystore: KeychainKeystore())
        try vault.kvSet(key: LedgerStore.groupsKey, value: Data("not json".utf8))
        try vault.closeVault()
        XCTAssertThrowsError(try ActivityFeed.load(store, group: nil), "an unreadable group list is an error")
    }

    /// L-D217-10: Expenses' empty and error states are drawn: Lucide's inbox, the title in the name role, no system view.
    func testExpensesEmptyIsDrawn() throws {
        let list = try appSource("ExpenseListView.swift")
        XCTAssertFalse(list.contains("ContentUnavailableView"), "the system's empty view")
        XCTAssertEqual(count(list, "EmptyState("), 2, "empty and error")
        let empty = try slice(try appSource("HomeParts.swift"), from: "struct EmptyState: View {", to: "\n}\n")
        XCTAssertTrue(empty.contains("LucideIcon(glyph: .inbox, size: ThemeArt.m(\"emptyIcon\"))"), "the inbox at 44")
        XCTAssertTrue(empty.contains("TypeRole.name(TypeScale.emptyTitle)"), "the title")
        XCTAssertTrue(empty.contains(".multilineTextAlignment(.center)"), "centred")
        // A2: SPEC section 11.6's words, split into title and detail.
        XCTAssertEqual(EmptyCopy.expensesTitle + ". " + EmptyCopy.expensesDetail,
                       "Nothing here yet. Add your first expense, or import from another app.")
        XCTAssertTrue(list.contains("EmptyState(title: EmptyCopy.expensesTitle, detail: EmptyCopy.expensesDetail)"))
        XCTAssertTrue(list.contains("EmptyState(title: LedgerCopy.loadError, detail: loadError)"))
    }

    /// L-D217-1: the plain Groups card's stack spacing is the art value both shells read.
    func testGroupsCardSpacing() throws {
        let plain = try slice(try appSource("GroupsScreen.swift"), from: "private func plainCard(", to: ".glassCard()")
        XCTAssertTrue(plain.contains("VStack(alignment: .leading, spacing: ThemeArt.m(\"groupsCardSpacing\"))"))
        XCTAssertFalse(plain.contains("VStack(alignment: .leading, spacing: 12)"))
    }

    /// K09 / Q3 a: a person avatar can take the soft fill (accentSoft ground, accent initial); nil keeps the tone.
    func testPersonAvatarSoftFill() throws {
        let avatar = try slice(try appSource("RemovalFlow.swift"), from: "struct PersonAvatar: View {", to: "struct RequestCardView: View {")
        XCTAssertTrue(avatar.contains("var fill: AvatarFill? = nil"))
        XCTAssertTrue(avatar.contains("switch fill {"), "branches on fill")
        let soft = try slice(avatar, from: "case .soft?:", to: "\n")
        XCTAssertTrue(soft.contains("Tokens.accentSoft") && soft.contains("Tokens.accent)"), "soft: \(soft)")
        let none = try slice(avatar, from: "case nil:", to: "}")
        XCTAssertTrue(none.contains("GroupTone.person(name)"), "nil keeps the tone")
    }

    /// Views-extra 1 (WORKLOG decision 19): an older change prints its day and month on the app's date axis, not the
    /// phone's. Fixed instant 2026-10-16 12:00 UTC (1792152000000 ms) in UTC. Derived by hand from CLDR's dMMM
    /// availableFormats: en (en-US) "MMM d" gives "Oct 16"; en-IN inherits en-001's "d MMM" and gives "16 Oct". The same
    /// strings are the Android twin's golden.
    func testDayMonthIsOnTheAppLocaleAxis() {
        let at: UInt64 = 1_792_152_000_000
        let utc = TimeZone(identifier: "UTC")!
        XCTAssertEqual(RelativeWhen.dayMonth(atMs: at, locale: .enUs, timeZone: utc), "Oct 16")
        XCTAssertEqual(RelativeWhen.dayMonth(atMs: at, locale: .enIn, timeZone: utc), "16 Oct")
        // A change older than six days prints it; a newer one never does.
        let later = at + 30 * 86_400_000
        XCTAssertEqual(RelativeWhen.words(atMs: at, nowMs: later, locale: .enIn, timeZone: utc), "16 Oct")
        XCTAssertEqual(RelativeWhen.words(atMs: at, nowMs: at + 60_000, locale: .enIn, timeZone: utc), "1 min")
    }

    /// Design 4: Home's one read returns each group's settled blocks: a group with an expense has its currency's
    /// block; a group with none has no block, so its card draws no meter.
    func testHomeLoadsEachGroupsProgress() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("home-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        let store = LedgerStore(path: dir.appendingPathComponent("ledger.db").path)
        let me = try store.setYourName("Tim")
        let goa = try store.createGroup(name: "Goa", settlementCurrency: "INR", people: ["Asha"])
        let flat = try store.createGroup(name: "Flat", settlementCurrency: "EUR", people: ["Lea"])
        let asha = try XCTUnwrap(goa.members.first { $0.name == "Asha" })
        _ = try store.appendExpense(LedgerStore.ExpenseDTO(
            payer: me.id, paidMinor: 10_000, currencyCode: "INR", group: goa.group.id, conversion: nil,
            shares: [.init(id: me.id, amountMinor: 5_000), .init(id: asha.id, amountMinor: 5_000)]))
        // M3: a third group in the same currency, with a different split, so a read that mixed groups would differ.
        let trip = try store.createGroup(name: "Trip", settlementCurrency: "INR", people: ["Ravi"])
        let ravi = try XCTUnwrap(trip.members.first { $0.name == "Ravi" })
        _ = try store.appendExpense(LedgerStore.ExpenseDTO(
            payer: ravi.id, paidMinor: 9_000, currencyCode: "INR", group: trip.group.id, conversion: nil,
            shares: [.init(id: me.id, amountMinor: 9_000)]))
        let home = try HomeOverview.load(store)
        // M3: each card's blocks are the group page's own (its read, its settlement currency), group by group.
        for g in [goa, flat, trip] {
            XCTAssertEqual(home.progress[g.group.id], try Self.groupPageBlocks(store, g.group.id), g.group.name)
        }
        XCTAssertNotEqual(home.progress[goa.group.id], home.progress[trip.group.id], "the groups are read apart")
        let blocks = try XCTUnwrap(home.progress[goa.group.id])
        XCTAssertEqual(blocks.map(\.currencyCode), ["INR"])
        XCTAssertEqual(LedgerCards.basisPoints(blocks, currency: "INR"), blocks.first?.basisPoints)
        XCTAssertEqual(home.progress[flat.group.id], [], "no expense, no block, no meter")
        XCTAssertNil(LedgerCards.basisPoints(home.progress[flat.group.id] ?? [], currency: nil))
        XCTAssertEqual(LedgerCards.line(home.overview.groups.first { $0.groupId == goa.group.id }?.nets ?? []),
                       .owed(5_000, "INR"), "Asha owes Tim half")
    }

    /// The group page's settled blocks, read as `GroupPageScreen.load` reads them (OverviewScreen.swift: its group's
    /// expenses, every settlement and redistribution, core's progress, the group's settlement currency first). Android
    /// calls the page's own `GroupProgress.progress`; iOS's page reads inline, so the read is restated here.
    static func groupPageBlocks(_ store: LedgerStore, _ groupID: String) throws -> [SettledProgressOut] {
        let n = try XCTUnwrap(try store.namedGroups().first { $0.group.id == groupID })
        let all = try store.loadExpenses().filter { $0.group == groupID }
        return SettledSummary.blocks(
            try ffiSettledProgress(expenses: all.map(\.asInput), settlements: try store.loadSettlements().map(\.asInput),
                                   redistributions: try store.loadRedistributions(), group: groupID),
            settlementCurrency: n.group.settlementCurrency)
    }

    /// M3: Home hands core every expense and core scopes by group (one rule, core's: it parses the ids), as Android's
    /// HomeOverview does; no shell-side string filter.
    func testHomeProgressIsCoresOneRule() throws {
        let load = try slice(try appSource("OverviewScreen.swift"), from: "enum HomeOverview {", to: "struct OverviewScreen: View {")
        XCTAssertTrue(load.contains("try ffiSettledProgress(expenses: expenses.map(\\.asInput),"), "every expense to core")
        XCTAssertFalse(load.contains(".filter {"), "no shell-side filter")
        XCTAssertTrue(load.contains("group: id)"), "core scopes by the group's id")
    }

    /// Views-extra 3: a zero net is settled on a card and skipped by the who-owes line (the next person is then first).
    func testZeroNetsAreSettledAndSkipped() {
        XCTAssertEqual(LedgerCards.line([CurrencyAmountOut(currencyCode: "INR", amountMinor: 0)]), .settled)
        let stub: (Int64, String) -> String = { minor, _ in "\(minor)" }
        XCTAssertEqual(WhoOwes.parts([person("Meera", "INR", 0), person("Sam", "INR", -500)], currency: "INR", format: stub),
                       ["You owe Sam 500"])
    }

    /// D-223 follow-up (R4): Lately's "3 min" words must not go stale while Home is kept alive. Home owns the clock
    /// (`nowMs`, set by every `load()`), hands it down to the card and rows (a row never reads the clock in its body),
    /// and re-reads when it comes back on screen after the first appearance, as Android's `HomeKeep` re-read does.
    func testLatelyWordsRefreshWhenHomeReturns() throws {
        let home = try slice(try appSource("OverviewScreen.swift"), from: "struct OverviewScreen: View {",
                             to: "private func loadUsername()")
        XCTAssertTrue(home.contains("@State private var nowMs: UInt64 = RelativeWhen.nowMs"), "Home owns the clock")
        XCTAssertTrue(home.contains("LatelyCard(feed: lately, nowMs: nowMs,"), "the clock goes to the card")
        let load = try slice(try appSource("OverviewScreen.swift"), from: "    private func load() {\n        do {",
                             to: "        loaded = true")
        XCTAssertTrue(load.contains("nowMs = RelativeWhen.nowMs"), "every read takes a fresh now")
        let onAppear = try slice(home, from: ".onAppear {", to: "\n        }")
        XCTAssertTrue(onAppear.contains("load()"), "Home re-reads when it comes back: \(onAppear)")
        XCTAssertTrue(onAppear.contains("returned") || onAppear.contains("appearedOnce"), "the first appearance is the task's")
        let parts = try appSource("HomeParts.swift")
        let card = try slice(parts, from: "struct LatelyCard: View {", to: "struct LatelyRow: View {")
        XCTAssertTrue(card.contains("let nowMs: UInt64"))
        let row = try slice(parts, from: "struct LatelyRow: View {", to: "struct EmptyState: View {")
        XCTAssertTrue(row.contains("let nowMs: UInt64"))
        XCTAssertFalse(row.contains("RelativeWhen.nowMs"), "a row never reads the clock itself")
    }
}
