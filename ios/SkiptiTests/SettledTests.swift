import SwiftUI
import XCTest
@testable import Skipti

/// D-221 (E4): the Settled card's blocks and percent, the group's next card, the milestones and the celebration's
/// decisions. Core's own numbers are tested in ffi.rs (settled.out); these order, pick and decide. Android's twin is
/// SettledTest.
final class SettledTests: XCTestCase {
    private func block(_ code: String, settled: Bool = false, bp: UInt32 = 0) -> SettledProgressOut {
        SettledProgressOut(currencyCode: code, settledMinor: 0, outstandingMinor: settled ? 0 : 100, basisPoints: bp, settled: settled)
    }

    private func line(_ code: String, _ entries: [(String, Int64)], transfers: Int = 0) -> BalanceLineOut {
        BalanceLineOut(currencyCode: code, viewerNetMinor: entries.reduce(0) { $0 + $1.1 },
                       entries: entries.map { ShareOut(id: $0.0, minorUnits: $0.1) },
                       transfers: (0..<transfers).map { TransferOut(from: "f\($0)", to: "t\($0)", amountMinor: 100) })
    }

    private let a = String(repeating: "a", count: 32), b = String(repeating: "b", count: 32), c = String(repeating: "c", count: 32)

    func testBlocksPutTheSettlementCurrencyFirstThenByCode() {
        let progress = [block("USD"), block("EUR"), block("INR")]
        XCTAssertEqual(SettledSummary.blocks(progress, settlementCurrency: "INR").map(\.currencyCode), ["INR", "EUR", "USD"])
        XCTAssertEqual(SettledSummary.blocks(progress, settlementCurrency: "USD").map(\.currencyCode), ["USD", "EUR", "INR"])
        XCTAssertEqual(SettledSummary.blocks(progress, settlementCurrency: "GBP").map(\.currencyCode), ["EUR", "INR", "USD"])
        XCTAssertEqual(SettledSummary.blocks(progress, settlementCurrency: nil).map(\.currencyCode), ["EUR", "INR", "USD"])
        XCTAssertEqual(SettledSummary.blocks([], settlementCurrency: "INR"), [])
    }

    func testAllSettledNeedsABlockAndEverySettled() {
        XCTAssertFalse(SettledSummary.allSettled([]), "a group with nothing in it is not 'all settled'")
        XCTAssertTrue(SettledSummary.allSettled([block("INR", settled: true)]))
        XCTAssertTrue(SettledSummary.allSettled([block("INR", settled: true), block("USD", settled: true)]))
        XCTAssertFalse(SettledSummary.allSettled([block("INR", settled: true), block("USD")]))
        XCTAssertFalse(SettledSummary.allSettled([block("INR")]))
    }

    /// The percent is floored (settled.out case A: 2631 basis points reads 26%).
    func testThePercentIsFloored() {
        XCTAssertEqual(SettledSummary.percent(2631), 26)
        XCTAssertEqual(SettledSummary.percent(10000), 100)
        XCTAssertEqual(SettledSummary.percent(9999), 99)
        XCTAssertEqual(SettledSummary.percent(7272), 72)
        XCTAssertEqual(SettledSummary.percent(0), 0)
    }

    /// The viewer's largest open entry across the lines, by size; ties by currency code, then id.
    func testTheNextPaymentIsTheLargestOpenEntry() {
        let lines = [line("INR", [(b, 20000), (a, -56000)]), line("USD", [(c, 3000)])]
        let pick = GroupNext.pick(lines: lines)
        XCTAssertEqual(pick?.currencyCode, "INR")
        XCTAssertEqual(pick?.entry, ShareOut(id: a, minorUnits: -56000))
        XCTAssertEqual(pick?.youOwe, true)
        // Size decides first: a later code and a later id still win when larger.
        XCTAssertEqual(GroupNext.pick(lines: [line("INR", [(a, 1000)]), line("USD", [(b, -9000)])])?.entry, ShareOut(id: b, minorUnits: -9000))
        // A larger entry in another currency wins.
        XCTAssertEqual(GroupNext.pick(lines: lines + [line("EUR", [(c, 60000)])])?.entry, ShareOut(id: c, minorUnits: 60000))
        XCTAssertEqual(GroupNext.pick(lines: lines + [line("EUR", [(c, 60000)])])?.youOwe, false)
        // Ties: the lower currency code, then the lower id.
        let tie = [line("USD", [(a, 5000)]), line("EUR", [(b, -5000)])]
        XCTAssertEqual(GroupNext.pick(lines: tie)?.currencyCode, "EUR")
        let sameCode = [line("INR", [(b, 5000), (a, -5000)])]
        XCTAssertEqual(GroupNext.pick(lines: sameCode)?.entry.id, a)
        XCTAssertEqual(GroupNext.pick(lines: [line("INR", [(b, -5000), (a, 5000)])])?.entry.id, a)
        // Nothing open: nil.
        XCTAssertNil(GroupNext.pick(lines: []))
        XCTAssertNil(GroupNext.pick(lines: [line("INR", [])]))
        XCTAssertNil(GroupNext.pick(lines: [line("INR", [(a, 0)])]))
    }

    /// S2 (WORKLOG decision 17): the muted line counts the group's open PAIRWISE debts, the model the pick and Settle up
    /// read: every member's frame, each nonzero entry once per pair and currency (a debt shows in both people's frames,
    /// with opposite signs); never the plan's transfers. "The last one" exactly at one.
    func testTheRemainingPaymentsCountTheGroupsPairs() {
        // a owes you 900 and c owes you 450 (your frame); c owes a 450 (a's frame); c's frame repeats all three.
        let you = String(repeating: "d", count: 32)
        let frames: [String: [BalanceLineOut]] = [
            you: [line("INR", [(a, 90000), (c, 45000)], transfers: 1)],
            a: [line("INR", [(you, -90000), (c, 45000)], transfers: 1)],
            c: [line("INR", [(you, -45000), (a, -45000)], transfers: 1)],
        ]
        XCTAssertEqual(GroupNext.remaining(frames: frames), 3, "three open pairs, whatever the plan says (one transfer)")
        XCTAssertEqual(GroupNext.remaining(frames: [you: frames[you]!]), 2, "your frame alone sees your two")
        // Each currency is its own debt; a zero entry is not open.
        XCTAssertEqual(GroupNext.remaining(frames: [you: [line("INR", [(a, 100)]), line("USD", [(a, -100)])], a: []]), 2)
        XCTAssertEqual(GroupNext.remaining(frames: [you: [line("INR", [(a, 0)])]]), 0)
        XCTAssertEqual(GroupNext.remaining(frames: [:]), 0)
        // Read through core per person: one read per distinct member or removed person.
        var reads: [String] = []
        XCTAssertEqual(GroupNext.remaining(members: [you, a, a], removed: [c, a]) { v in reads.append(v); return frames[v] ?? [] }, 3)
        XCTAssertEqual(reads.sorted(), [you, a, c].sorted())
        XCTAssertEqual(GroupNext.remainingLine(1), "The last one")
        XCTAssertEqual(GroupNext.remainingLine(2), "2 payments to go")
        XCTAssertEqual(GroupNext.remainingLine(5), "5 payments to go")
    }

    /// The ten cases of .drafts/d221/milestones.out, inputs built as the generator builds them, outputs re-typed.
    func testTheMilestonesAreTheGoldens() {
        let day: UInt64 = 86_400_000, t0: UInt64 = 1_772_409_600_000, year = 365 * day, window = 14 * day
        let base = (0..<100).map { MilestoneExpense(addedAtMs: t0 + UInt64($0) * day / 4, dateMs: t0 + UInt64($0) * day / 4) }
        let both = (0..<100).map { i in
            MilestoneExpense(addedAtMs: t0 + year - day / 2 + UInt64(i), dateMs: i == 0 ? t0 : t0 + year)
        }
        let hundred = Milestones.Shown(milestone: .hundred, reachedMs: 1_774_548_000_000, sinceMs: 1_772_409_600_000)
        let yearOn = Milestones.Shown(milestone: .year, reachedMs: 1_803_945_600_000, sinceMs: 1_772_409_600_000)
        let cases: [(String, [MilestoneExpense], UInt64, Milestones.Shown?)] = [
            ("99 expenses, a day after the 99th", Array(base.prefix(99)), base[98].addedAtMs + day, nil),
            ("100th just added", base, base[99].addedAtMs, hundred),
            ("13 days 23 h after the 100th", base, base[99].addedAtMs + window - 3_600_000, hundred),
            ("14 days after the 100th (gone)", base, base[99].addedAtMs + window, nil),
            ("a minute before the 100th was added", base, base[99].addedAtMs - 60_000, nil),
            ("one expense, a year on", [MilestoneExpense(addedAtMs: t0, dateMs: t0)], t0 + year, yearOn),
            ("one expense, a year minus a minute", [MilestoneExpense(addedAtMs: t0, dateMs: t0)], t0 + year - 60_000, nil),
            ("backdated first expense: dated a year ago, added today",
             [MilestoneExpense(addedAtMs: t0 + year, dateMs: t0)], t0 + year + day, yearOn),
            ("both in window: the later reached wins", both, t0 + year + day, yearOn),
            ("no expenses", [], t0, nil),
        ]
        for (name, expenses, now, expected) in cases {
            XCTAssertEqual(Milestones.current(expenses, now: now), expected, name)
        }
        XCTAssertEqual(SettledCopy.hundred, "100 expenses together")
        XCTAssertEqual(SettledCopy.year, "A year together")
    }

    /// Plays only when a loaded group goes from not all settled to all settled, and never for a copy.
    func testTheCelebrationPlaysOnlyOnTheChange() {
        XCTAssertTrue(Celebration.plays(before: false, after: true, isCopy: false))
        XCTAssertFalse(Celebration.plays(before: nil, after: true, isCopy: false), "the first load never plays")
        XCTAssertFalse(Celebration.plays(before: false, after: true, isCopy: true), "a copy never plays")
        XCTAssertFalse(Celebration.plays(before: true, after: true, isCopy: false), "already settled")
        XCTAssertFalse(Celebration.plays(before: false, after: false, isCopy: false))
        XCTAssertFalse(Celebration.plays(before: true, after: false, isCopy: false))
        XCTAssertTrue(Celebration.showsBurst(reduceMotion: false))
        XCTAssertFalse(Celebration.showsBurst(reduceMotion: true))
    }

    /// S8: the shell remembers each group's settled state as its page last saw it: a group settled while its page was
    /// away plays when the page is back (a reopened page included); the first sight this run never plays.
    func testTheShellRemembersEachGroupsSettledState() {
        let memory = SettledMemory()
        XCTAssertNil(memory.see("goa", settled: false), "the first sight this run")
        XCTAssertNil(memory.see("flat", settled: true), "another group is its own")
        // Settled in Settle up while the page was away; the page opened again sees it.
        let before = memory.see("goa", settled: true)
        XCTAssertEqual(before, false)
        XCTAssertTrue(Celebration.plays(before: before, after: true, isCopy: false))
        XCTAssertEqual(memory.see("goa", settled: true), true, "seen: no second play")
        XCTAssertEqual(memory.see("flat", settled: false), true)
    }

    /// S8: a waiting play is kept only while the group is still all settled; a later load that finds it unsettled
    /// (another phone added an expense before the page was seen) or a copy clears it.
    func testAStalePendingPlayIsCleared() {
        let cases: [(was: Bool, before: Bool?, after: Bool, isCopy: Bool, pending: Bool)] = [
            (false, false, true, false, true),   // the change: a new play
            (true, true, true, false, true),     // still settled, not yet seen: keep waiting
            (true, true, false, false, false),   // unsettled again before it was seen: cleared
            (true, false, false, false, false),
            (true, true, true, true, false),     // now a copy: cleared
            (false, nil, true, false, false),    // the first load never plays
            (false, true, true, false, false),
        ]
        for c in cases {
            XCTAssertEqual(Celebration.pending(was: c.was, before: c.before, after: c.after, isCopy: c.isCopy), c.pending, "\(c)")
        }
    }

    /// The copy, verbatim on both phones (no em dashes).
    func testTheSettledCopy() {
        XCTAssertEqual(SettledCopy.label, "Settled")
        XCTAssertEqual(SettledCopy.stillToSettle("\u{20B9}2,360.00"), "\u{20B9}2,360.00 still to settle")
        XCTAssertEqual(SettledCopy.nothingLeft, "Nothing left to settle")
        XCTAssertEqual(SettledCopy.since("2 Mar 2026"), "Since 2 Mar 2026. Rare, and only once.")
        XCTAssertEqual(SettledCopy.lastOne, "The last one")
        XCTAssertEqual(SettledCopy.paymentsToGo(3), "3 payments to go")
        XCTAssertEqual(SettledCopy.settle, "Settle")
        XCTAssertEqual(SettledCopy.settling, "Settling")
    }

    // MARK: - D-221 Task 6: the group page

    private func appSource(_ file: String) throws -> String {
        let url = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("Skipti/App/\(file)")
        return try String(contentsOf: url, encoding: .utf8)
    }

    /// The text between the first `from` and the next `to` after it.
    private func slice(_ text: String, from: String, to: String) throws -> String {
        let after = try XCTUnwrap(text.components(separatedBy: from).dropFirst().first, "anchor \(from)")
        return try XCTUnwrap(after.components(separatedBy: to).first, "anchor \(to)")
    }

    private func count(_ text: String, _ needle: String) -> Int { text.components(separatedBy: needle).count - 1 }

    private func pageBody() throws -> String {
        let page = try slice(try appSource("OverviewScreen.swift"), from: "struct GroupPageScreen: View {", to: "private func openReview(")
        return try slice(page, from: "var body: some View {", to: "private func openReview(")
    }

    /// Q16 a: the header is bare (no card, no tone tint); the tile is the 48 pt group tile; both names (the photo
    /// band's and the tile row's) take the display name size.
    func testTheGroupHeaderIsBare() throws {
        let overview = try appSource("OverviewScreen.swift")
        let header = try slice(overview, from: "private func header(", to: "private func photoBand(")
        XCTAssertFalse(header.contains(".glassCard("), "the header is not a card")
        XCTAssertFalse(header.contains("tone.fill"), "the header takes no tone tint")
        XCTAssertFalse(header.contains("statsRow("), "the stats moved out of the header")
        let row = try slice(overview, from: "private func tileRow(", to: "private func statsRow(")
        XCTAssertEqual(count(row, "GroupTile(group: n.group, size: ThemeArt.m(\"groupTile\"))"), 1)
        XCTAssertEqual(count(row, ".font(TypeRole.display(TypeScale.displayName, weight: 700))"), 1, "the tile row's name")
        let band = try slice(overview, from: "private func photoBand(", to: "private func tileRow(")
        XCTAssertEqual(count(band, ".font(TypeRole.display(TypeScale.displayName, weight: 700))"), 1, "the photo band's name")
        XCTAssertFalse(overview.contains("TypeRole.display(26"), "a literal 26 point name")
        XCTAssertEqual(ThemeArt.m("groupTile"), 48)
    }

    /// D-234 (L9, the D-230 re-look): a read-only group's header picture is not faded: the tile and the photo stop taking
    /// a tap (`allowsHitTesting`) instead of being disabled buttons (SwiftUI fades a disabled plain button's label), and
    /// a copy's picture is not a button to VoiceOver, as on Android (which drops the click and keeps the colour).
    func testAReadOnlyGroupsPictureIsNotFaded() throws {
        let overview = try appSource("OverviewScreen.swift")
        XCTAssertFalse(overview.contains(".disabled(isCopy)"), "never a disabled picture button")
        let band = try slice(overview, from: "private func photoBand(", to: "private func tileRow(")
        let row = try slice(overview, from: "private func tileRow(", to: "private func statsRow(")
        for (name, part) in [("photo", band), ("tile", row)] {
            XCTAssertEqual(count(part, ".allowsHitTesting(!isCopy)"), 1, "\(name): no tap on a copy")
            XCTAssertEqual(count(part, ".accessibilityHidden(isCopy)"), 1, "\(name): not a button on a copy")
        }
    }

    /// D-234 (decision 91): Groups' "Join a group" ends as Balance's does: every tab reads the ledger again from its root
    /// (`.skiptiLedgerImported`), so the join pages never stay on the Groups stack.
    func testAJoinFromGroupsReturnsEveryTabToItsRoot() throws {
        let groups = try appSource("GroupsScreen.swift")
        let join = try slice(groups, from: "JoinPasteScreen(store: store) { _ in", to: "} label: {")
        XCTAssertTrue(join.contains("NotificationCenter.default.post(name: .skiptiLedgerImported, object: nil)"), join)
        XCTAssertFalse(join.contains("onOpen()"), join)
    }

    /// D-234 (L10, decision 95): the settle celebration's two lines sit on a card (the theme's own: glass, Norse's
    /// notched panel, Vardr Labs' frame), 24 in from the page's sides, so they never read on top of the page's words
    /// (the D-231 look: "Nobody owes anybody" on "Where the money went"). Still placed at `messageTop`.
    func testTheCelebrationsWordsSitOnACard() throws {
        let settled = try appSource("Settled.swift")
        let overlay = try slice(settled, from: "TimelineView(ArtSchedule(paused: play.still)) { timeline in", to: ".allowsHitTesting(false)")
        XCTAssertTrue(overlay.contains(".frame(maxWidth: .infinity)\n                    .foregroundStyle(Tokens.text)\n                    .multilineTextAlignment(.center)\n                    .glassCard()\n                    .padding(.horizontal, 24)"), overlay)
        XCTAssertTrue(overlay.contains("y: geo.size.height * CelebrationTiming.messageTop"))
    }

    /// D-234 (L12-d): an expense row keeps 8 between its words and what it does to you, as Android's does (the HStack's
    /// 12 on both sides of an 8 minimum made 32), and the right-hand amount is measured first, as Android measures it;
    /// the group's card pads 14 on both phones.
    func testAnExpenseRowsGapIsAndroids() throws {
        let list = try appSource("ExpenseListView.swift")
        let row = try slice(list, from: "    private func row(_ projected: ProjectedExpenseOut) -> some View {", to: ".accessibilityElement(children: .ignore)")
        XCTAssertTrue(row.contains("return HStack(alignment: .top, spacing: 0) {"), row)
        XCTAssertTrue(row.contains("Spacer(minLength: 8)"))
        XCTAssertTrue(row.contains(".lineLimit(2)\n                    .layoutPriority(1)"))
        XCTAssertEqual(count(list, ".glassCard(padding: 14)"), 1)
    }

    /// Design 7: the bare header, the Settled card, the stats as a row, the milestone, the next card, then the
    /// request cards and everything below as before.
    func testTheGroupPageOrder() throws {
        let body = try pageBody()
        var last = body.startIndex
        for anchor in ["header(named)", "SettledCard(", "statsRow(", "MilestoneCard(", "GroupNextCard(", "ForEach(cards)"] {
            XCTAssertEqual(count(body, anchor), 1, "one \(anchor)")
            guard let found = body.range(of: anchor) else { continue }
            XCTAssertGreaterThan(found.lowerBound, last, "\(anchor) is out of order")
            last = found.lowerBound
        }
        // The card shows only when core returned a block; the milestone only when one shows.
        XCTAssertEqual(count(body, "if !progress.isEmpty { SettledCard(blocks: progress) }"), 1)
        XCTAssertEqual(count(body, "if let milestone { MilestoneCard(shown: milestone) }"), 1)
    }

    /// L42: the meter is C2's meter shape, the art table's heights (Vardr Labs thinner), Norse's segmented track, and
    /// the meter gradient.
    func testTheMeterIsTheThemesMeter() throws {
        let settled = try appSource("Settled.swift")
        let meter = try slice(settled, from: "struct SettledMeter: View {", to: "\n}\n")
        XCTAssertTrue(meter.contains("ThemeArt.meterShape("), "C2's meter shape")
        XCTAssertFalse(meter.contains("Capsule()"), "a capsule of its own")
        for key in ["meterBig", "meterBigVardr", "meterSmall", "meterSmallVardr", "meterSegment", "meterGap", "norseMeterGap"] {
            XCTAssertTrue(meter.contains("\"\(key)\""), "reads \(key)")
        }
        for token in ["Tokens.meterStart", "Tokens.meterEnd", "Tokens.ringTrack"] {
            XCTAssertTrue(meter.contains(token), "draws \(token)")
        }
        XCTAssertTrue(meter.contains(".accessibilityHidden(true)"), "decorative: the percent carries it")
        XCTAssertEqual(AppTheme.allCases.map { SettledMeter.height($0, big: true) }, [10, 10, 7])
        XCTAssertEqual(AppTheme.allCases.map { SettledMeter.height($0, big: false) }, [6, 6, 4])
        XCTAssertEqual(SettledMeter.fraction(0), 0)
        XCTAssertEqual(SettledMeter.fraction(2631), 0.2631, accuracy: 1e-9)
        XCTAssertEqual(SettledMeter.fraction(10000), 1)
        XCTAssertEqual(SettledMeter.fraction(12000), 1, "never past the end")
    }

    /// Money strings come from core: the card formats with `ffiMoneyFormat`, never a formatter of its own.
    func testTheSettledCardPrintsCoreMoney() throws {
        let card = try slice(try appSource("Settled.swift"), from: "struct SettledCard: View {", to: "\n}\n")
        XCTAssertEqual(count(card, "ffiMoneyFormat("), 1)
        XCTAssertFalse(card.contains("NumberFormatter"))
        XCTAssertFalse(card.contains("String(format"))
        XCTAssertTrue(card.contains("SettledMeter(fraction: SettledMeter.fraction(b.basisPoints), big: true)"), "the big meter")
        XCTAssertTrue(card.contains("TypeRole.numeric(TypeScale.settledPercent, weight: 700)"), "the percent's face")
        XCTAssertTrue(card.contains("SettledSummary.percent(b.basisPoints)"), "the floored percent")
        let next = try slice(try appSource("Settled.swift"), from: "struct GroupNextCard: View {", to: "\n}\n")
        XCTAssertEqual(count(next, "ffiMoneyFormat("), 1, "the next card's amount")
        XCTAssertFalse(next.contains("NumberFormatter"))
    }

    /// Q17 a: the next card records through Settle up's own function, and Settle up calls it too; nothing else
    /// appends a settlement; never on a copy; the latch holds "Settling" until the reloaded page moves on.
    func testTheNextCardRecordsLikeSettleUp() throws {
        let settled = try appSource("Settled.swift")
        let next = try slice(settled, from: "struct GroupNextCard: View {", to: "\n}\n")
        XCTAssertEqual(count(next, "SettleAll.record(store: store, entry: pick.entry, currency: pick.currencyCode, named: named)"), 1)
        XCTAssertFalse(settled.contains("appendSettlement("), "the card appends a settlement of its own")
        XCTAssertTrue(next.contains("guard latch.begin(pick) else { return }"), "a second tap records nothing")
        XCTAssertEqual(count(next, "latch.failed()"), 1, "a failure lets go")
        XCTAssertEqual(count(next, ".onChange(of: pick) { latch.moved(to: [pick]) }"), 1, "the page moving on lets go (M3)")
        XCTAssertTrue(next.contains(".disabled(latch.settling(pick))"))
        XCTAssertTrue(next.contains("SettleAllCopy.sentence(youOwe: pick.youOwe, other: other)"))
        XCTAssertTrue(next.contains("Text(verbatim: SettleAllCopy.explainer)"), "the section 11.6 sentence")
        XCTAssertTrue(next.contains("PrimaryButtonStyle(compact: true)"))
        let all = try appSource("SettleAll.swift")
        let screen = try slice(all, from: "struct SettleAllScreen: View {", to: "\n}\n")
        XCTAssertEqual(count(screen, "SettleAll.record(store: store, entry: entry, currency: currency, named: named)"), 1)
        XCTAssertFalse(screen.contains("appendSettlement("), "Settle up appends outside the shared function")
        // S1: Settle up holds the same latch: a second tap records nothing; the reload moving on lets go.
        XCTAssertEqual(count(screen, "guard latch.begin(key) else { return }"), 1, "Settle up's second tap")
        XCTAssertEqual(count(screen, "latch.moved(to: SettleAll.keys(sections))"), 1, "Settle up lets go once the reload moved on")
        XCTAssertEqual(count(screen, ".disabled(latch.busy)"), 1, "one at a time: another card's Settle reads as disabled")
        XCTAssertFalse(screen.contains("settlingKey"), "the old flag, cleared before the reload")
        XCTAssertTrue(screen.contains("let key = SettleAll.key(named: named, currency: currency, entry: entry)"))
        let shared = try slice(all, from: "enum SettleAll {", to: "\n}\n")
        XCTAssertEqual(count(shared, "appendSettlement("), 1, "the one append")
        let body = try pageBody()
        XCTAssertEqual(count(body, "if !isCopy, let pick = GroupNext.pick(lines: lines) {"), 1, "never for a copy")
        // S2: the count is the group's pairwise debts, read with the page (every member's and removed person's frame),
        // never the plan.
        XCTAssertEqual(count(body, "remaining: remaining,"), 1)
        let page = try groupPage()
        XCTAssertEqual(count(page, "remaining = try GroupNext.remaining(members: n.members.map(\\.id), removed: removed) { try line($0) }"), 1)
        let model = try slice(settled, from: "enum GroupNext {", to: "\n}\n")
        XCTAssertFalse(model.contains("transfers"), "the plan's transfers are another model")
    }

    /// D-221 follow-up (twin of Android's the_removed_people_who_still_owe_each_other_are_counted): the count reads every
    /// member's AND every removed person's frame. Worked by hand: you paid 100.00 split with Ana (Ana owes you 50.00);
    /// Xavi, since removed, paid 200.00 split with Yara, also removed (Yara owes Xavi 100.00, both nets nonzero, so
    /// D-210 keeps them in the pairwise view); Zed was removed at zero and adds nothing. Open pairs: 2 (members alone: 1).
    func testTheRemovedPeopleWhoStillOweEachOtherAreCounted() throws {
        let (me, ana, xavi, yara, zed, group) = (String(repeating: "1", count: 32), String(repeating: "2", count: 32),
                                                 String(repeating: "3", count: 32), String(repeating: "4", count: 32),
                                                 String(repeating: "5", count: 32), String(repeating: "9", count: 32))
        let expenses = [
            ExpenseInput(payer: me, paidMinor: 10_000, currencyCode: "INR",
                         shares: [IdAmount(id: me, amountMinor: 5_000), IdAmount(id: ana, amountMinor: 5_000)], group: group, conversion: nil),
            ExpenseInput(payer: xavi, paidMinor: 20_000, currencyCode: "INR",
                         shares: [IdAmount(id: xavi, amountMinor: 10_000), IdAmount(id: yara, amountMinor: 10_000)], group: group, conversion: nil),
        ]
        let removed = [xavi, yara, zed]
        func line(_ viewer: String) throws -> [BalanceLineOut] {
            try ffiBalanceLine(expenses: expenses, settlements: [], redistributions: [], removed: removed, viewer: viewer, group: group)
        }
        XCTAssertEqual(try line(yara).first?.entries, [ShareOut(id: xavi, minorUnits: -10_000)], "core keeps the removed pair open")
        XCTAssertEqual(try line(me).first?.entries, [ShareOut(id: ana, minorUnits: 5_000)], "your frame sees Ana only")
        XCTAssertEqual(GroupNext.pick(lines: try line(me)), GroupNext.Pick(currencyCode: "INR", entry: ShareOut(id: ana, minorUnits: 5_000)))
        let pairs = try GroupNext.remaining(members: [me, ana], removed: removed) { try line($0) }
        XCTAssertEqual(pairs, 2, "Ana to you, and Yara to Xavi")
        XCTAssertEqual(GroupNext.remainingLine(pairs), "2 payments to go")
        XCTAssertEqual(try GroupNext.remaining(members: [me, ana], removed: []) { try line($0) }, 1, "members alone miss the removed pair")
    }

    /// The Task 7 review's race: "Settling" holds from the tap until the reloaded page shows another pick; a second
    /// tap meanwhile records nothing; only a failure lets go.
    func testTheLatchHoldsUntilThePageMovesOn() {
        let p1 = GroupNext.Pick(currencyCode: "INR", entry: ShareOut(id: a, minorUnits: -56000))
        let p2 = GroupNext.Pick(currencyCode: "INR", entry: ShareOut(id: b, minorUnits: 20000))
        var latch = SettleLatch<GroupNext.Pick>()
        XCTAssertFalse(latch.settling(p1))
        XCTAssertFalse(latch.busy)
        XCTAssertTrue(latch.begin(p1))
        XCTAssertTrue(latch.settling(p1))
        XCTAssertTrue(latch.busy, "every Settle reads as disabled")
        XCTAssertFalse(latch.begin(p1), "a second tap while settling")
        XCTAssertFalse(latch.begin(p2), "nor another payment while one records (Settle up's other cards)")
        XCTAssertTrue(latch.settling(p1), "still settling")
        XCTAssertFalse(latch.settling(p2), "the reloaded page's next pick is not settling")
        latch.moved(to: [p1, p2])
        XCTAssertTrue(latch.settling(p1), "a reload that still offers the payment keeps the latch")
        latch.moved(to: [p2])
        XCTAssertNil(latch.inFlight, "the page moved on: it lets go")
        XCTAssertFalse(latch.busy)
        XCTAssertTrue(latch.begin(p2), "the next pick can be settled")
        latch.failed()
        XCTAssertFalse(latch.settling(p2), "a failure lets go")
        XCTAssertTrue(latch.begin(p2), "and the same pick can be tried again")
        // M3: the pick moves on and the identical payment comes back later: it is not "Settling".
        latch.moved(to: [p1])
        XCTAssertFalse(latch.settling(p2))
        latch.moved(to: [p2])
        XCTAssertFalse(latch.settling(p2), "an identical pick returning later is a fresh one")
        XCTAssertTrue(latch.begin(p2))
    }

    /// S2's golden on the groups proof's Flat 4B (you paid electricity 2,700.00 three ways, Meera groceries 1,350.00
    /// three ways; worked by hand): your frame is Kiran +900.00 and Meera +450.00, and Kiran also owes Meera 450.00. No
    /// directed loop (Kiran -> you, Kiran -> Meera, Meera -> you), so D-218 cancels nothing. The plan is ONE transfer
    /// (Kiran pays you 1,350.00), which is how the first build said "The last one"; the pairwise view has three.
    func testFlat4BNextCardIsPairwise() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("flat4b-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        let store = LedgerStore(path: dir.appendingPathComponent("ledger.db").path)
        try GroupsProof.seed(store)
        let named = try XCTUnwrap(try store.namedGroups().first { $0.group.name == "Flat 4B" })
        let id = { (name: String) in named.members.first { $0.name == name }!.id }
        let (kiran, meera) = (id("Kiran"), id("Meera"))
        let removed = try store.removedIds(group: named.group.id)
        func line(_ viewer: String) throws -> [BalanceLineOut] {
            try ffiBalanceLine(expenses: try store.loadExpenses().map(\.asInput), settlements: try store.loadSettlements().map(\.asInput),
                               redistributions: try store.loadRedistributions(), removed: removed, viewer: viewer, group: named.group.id)
        }
        func remaining() throws -> Int {
            try GroupNext.remaining(members: named.members.map(\.id), removed: removed) { try line($0) }
        }
        XCTAssertEqual(try line(named.selfId).first?.transfers.count, 1, "the plan: one transfer")

        let first = try XCTUnwrap(GroupNext.pick(lines: try line(named.selfId)))
        XCTAssertEqual(first, GroupNext.Pick(currencyCode: "INR", entry: ShareOut(id: kiran, minorUnits: 90_000)))
        XCTAssertEqual(try remaining(), 3)
        XCTAssertEqual(GroupNext.remainingLine(try remaining()), "3 payments to go")
        try SettleAll.record(store: store, entry: first.entry, currency: first.currencyCode, named: named)

        let second = try XCTUnwrap(GroupNext.pick(lines: try line(named.selfId)))
        XCTAssertEqual(second, GroupNext.Pick(currencyCode: "INR", entry: ShareOut(id: meera, minorUnits: 45_000)))
        XCTAssertEqual(GroupNext.remainingLine(try remaining()), "2 payments to go")
        try SettleAll.record(store: store, entry: second.entry, currency: second.currencyCode, named: named)

        XCTAssertNil(GroupNext.pick(lines: try line(named.selfId)), "you are square: no card")
        XCTAssertEqual(try remaining(), 1, "Kiran still owes Meera")
        XCTAssertEqual(try line(kiran).first?.entries, [ShareOut(id: meera, minorUnits: -45_000)])
    }

    /// S1: Settle up's cards share the latch: two taps on one card record one settlement, a tap on another card while
    /// one records records nothing, and the reload (the payment gone) lets go.
    func testSettleUpsSecondTapRecordsNothing() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("settle-latch-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        let store = LedgerStore(path: dir.appendingPathComponent("ledger.db").path)
        try GroupsProof.seed(store)
        let flat = try XCTUnwrap(try SettleAll.sections(store).first { $0.named.group.name == "Flat 4B" })
        let debts = flat.lines.flatMap { line in line.entries.map { (line.currencyCode, $0) } }
        XCTAssertEqual(debts.count, 2)
        var latch = SettleLatch<String>()
        let keys = debts.map { SettleAll.key(named: flat.named, currency: $0.0, entry: $0.1) }
        XCTAssertEqual(Set(keys).count, 2, "one key per card")
        for _ in 0..<2 where latch.begin(keys[0]) {
            try SettleAll.record(store: store, entry: debts[0].1, currency: debts[0].0, named: flat.named)
        }
        // One at a time: a second, different Settle while one records records nothing, and its button reads as disabled.
        XCTAssertTrue(latch.busy, "another card's Settle reads as disabled")
        XCTAssertFalse(latch.settling(keys[1]), "it is not the card recording")
        for _ in 0..<2 where latch.begin(keys[1]) {
            try SettleAll.record(store: store, entry: debts[1].1, currency: debts[1].0, named: flat.named)
        }
        XCTAssertFalse(latch.begin(keys[1]), "another card while one records")
        XCTAssertEqual(try store.loadSettlements().count, 1, "one settlement for the taps")
        let only = try XCTUnwrap(try store.loadSettlements().first)
        XCTAssertTrue([only.from, only.to].contains(debts[0].1.id), "only the first card's payment")
        XCTAssertFalse([only.from, only.to].contains(debts[1].1.id))
        let after = try SettleAll.sections(store)
        XCTAssertFalse(SettleAll.keys(after).contains(keys[0]), "the recorded card is gone")
        XCTAssertTrue(SettleAll.keys(after).contains(keys[1]))
        latch.moved(to: SettleAll.keys(after))
        XCTAssertNil(latch.inFlight, "the reload moved on")
        XCTAssertTrue(latch.begin(keys[1]))
        // The key carries the amount: the same person and currency with a new amount is a new card.
        let entry = debts[0].1
        XCTAssertNotEqual(SettleAll.key(named: flat.named, currency: "INR", entry: entry),
                          SettleAll.key(named: flat.named, currency: "INR", entry: ShareOut(id: entry.id, minorUnits: entry.minorUnits + 1)))
    }

    /// The real path on a real vault: you owe Ana 32.00 in Paris; two taps record one settlement; core then reads
    /// the group settled, and the next card has nothing left to pick.
    func testASecondTapWhileSettlingRecordsNothing() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("settled-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        let store = LedgerStore(path: dir.appendingPathComponent("ledger.db").path)
        _ = try store.setYourName("Prithvi")
        let named = try store.createGroup(name: "Paris", settlementCurrency: "EUR", people: ["Ana"])
        let ana = try XCTUnwrap(named.members.first { $0.id != named.selfId }).id
        _ = try store.appendExpense(LedgerStore.ExpenseDTO(
            payer: ana, paidMinor: 6_400, currencyCode: "EUR", group: named.group.id, conversion: nil,
            shares: [.init(id: named.selfId, amountMinor: 3_200), .init(id: ana, amountMinor: 3_200)], note: "Museum passes",
            timestampMs: 1_772_409_600_000, category: nil, addedBy: ana))
        func lines() throws -> [BalanceLineOut] {
            try ffiBalanceLine(expenses: try store.loadExpenses().map(\.asInput), settlements: try store.loadSettlements().map(\.asInput),
                               redistributions: try store.loadRedistributions(), removed: [], viewer: named.selfId, group: named.group.id)
        }
        func progress() throws -> [SettledProgressOut] {
            try ffiSettledProgress(expenses: try store.loadExpenses().map(\.asInput), settlements: try store.loadSettlements().map(\.asInput),
                                   redistributions: try store.loadRedistributions(), group: named.group.id)
        }
        XCTAssertEqual(try progress(), [SettledProgressOut(currencyCode: "EUR", settledMinor: 0, outstandingMinor: 3_200, basisPoints: 0, settled: false)])
        let pick = try XCTUnwrap(GroupNext.pick(lines: try lines()))
        XCTAssertEqual(pick, GroupNext.Pick(currencyCode: "EUR", entry: ShareOut(id: ana, minorUnits: -3_200)))
        XCTAssertEqual(GroupNext.remainingLine(try GroupNext.remaining(members: named.members.map(\.id), removed: []) { v in
            try ffiBalanceLine(expenses: try store.loadExpenses().map(\.asInput), settlements: [], redistributions: [],
                               removed: [], viewer: v, group: named.group.id)
        }), "The last one")
        var latch = SettleLatch<GroupNext.Pick>()
        for _ in 0..<2 where latch.begin(pick) {
            try SettleAll.record(store: store, entry: pick.entry, currency: pick.currencyCode, named: named)
        }
        let recorded = try store.loadSettlements()
        XCTAssertEqual(recorded.count, 1, "one settlement for two taps")
        XCTAssertEqual(recorded.first?.from, named.selfId)
        XCTAssertEqual(recorded.first?.to, ana)
        XCTAssertEqual(recorded.first?.amountMinor, 3_200)
        XCTAssertEqual(try progress(), [SettledProgressOut(currencyCode: "EUR", settledMinor: 3_200, outstandingMinor: 0, basisPoints: 10_000, settled: true)])
        XCTAssertNil(GroupNext.pick(lines: try lines()), "nothing left to pick")
    }

    /// L44: the seal is the theme's tile (Aurora a circle, Norse the diamond with its edge, Vardr Labs a square) at
    /// the art table's 44, the number in the accent on accentSoft.
    func testTheSealIsTheThemesTile() throws {
        let card = try slice(try appSource("Settled.swift"), from: "struct MilestoneCard: View {", to: "\n}\n")
        XCTAssertTrue(card.contains("SealStyle.clip(AppearanceState.shared.theme)"))
        XCTAssertTrue(card.contains("TileClip.shape(kind, size: side)"))
        XCTAssertTrue(card.contains("TileClip.drawnSide(kind, size: side)"))
        XCTAssertTrue(card.contains("TileClip.edge(kind)"))
        XCTAssertTrue(card.contains("ThemeArt.m(\"seal\")"))
        XCTAssertFalse(card.contains("Circle()"), "a circle of its own")
        XCTAssertTrue(card.contains(".background(Tokens.accentSoft, in: shape)"))
        XCTAssertTrue(card.contains("TypeRole.numeric(TypeScale.sealNumber, weight: 700)"))
        XCTAssertTrue(card.contains("SettledCopy.since(AppDate.day(shown.sinceMs))"), "the first expense's date")
        XCTAssertEqual(AppTheme.allCases.map(SealStyle.clip), [.circle, .diamond, .square])
        XCTAssertEqual(ThemeArt.m("seal"), 44)
        XCTAssertEqual(MilestoneWords.number(.hundred), "100")
        XCTAssertEqual(MilestoneWords.number(.year), "1")
        XCTAssertEqual(MilestoneWords.title(.hundred), SettledCopy.hundred)
        XCTAssertEqual(MilestoneWords.title(.year), SettledCopy.year)
    }

    // MARK: - D-221 fix wave (S3, S4, S5)

    /// S3 (SPEC 11.7): the compact primary ("Do it", "Settle") is drawn at its label's size but takes a 44 pt touch,
    /// in both looks (filled and Vardr Labs' outlined), with the whole 44 hit-testable.
    @MainActor
    func testTheCompactButtonIsA44PointTarget() throws {
        let saved = AppearanceState.shared.theme
        defer { AppearanceState.shared.theme = saved }
        for theme in AppTheme.allCases {
            AppearanceState.shared.theme = theme
            let host = UIHostingController(rootView: Button {} label: { Text(verbatim: "Do it") }
                .buttonStyle(PrimaryButtonStyle(compact: true)))
            let size = host.sizeThatFits(in: CGSize(width: 400, height: CGFloat.greatestFiniteMagnitude))
            XCTAssertGreaterThanOrEqual(size.height, 44, "\(theme): the compact button's touch height")
            XCTAssertLessThan(size.width, 200, "\(theme): compact keeps to its label")
        }
        let tokens = try appSource("DesignTokens.swift")
        let body = try slice(tokens, from: "private struct PrimaryButtonBody: View {", to: "\n}\n")
        XCTAssertEqual(count(body, ".compactTouch(compact)"), 2, "both looks take the 44 pt touch")
        XCTAssertTrue(tokens.contains("frame(minHeight: compact ? PrimaryButtonStyle.minTouch : nil).contentShape(Rectangle())"),
                      "the whole 44 takes the touch")
        XCTAssertEqual(PrimaryButtonStyle.minTouch, 44)
    }

    /// S4 (SPEC 11.7: explicit accessibility labels on every money view): the Settled card's blocks and the next card's
    /// words read core's spoken money, never the printed string; the next card's button stays its own element.
    func testTheMoneyViewsAreSpoken() throws {
        let spoken = try ffiMoneySpoken(minorUnits: 236_000, currencyCode: "INR", locale: .enIn)
        XCTAssertFalse(spoken.contains("\u{20B9}"), "core's spoken form has no sign")
        XCTAssertEqual(SettledSpoken.block(head: "Settled", percent: 26, left: SettledCopy.stillToSettle(spoken)),
                       "Settled, 26%, \(spoken) still to settle")
        XCTAssertEqual(SettledSpoken.block(head: "USD", percent: 100, left: SettledCopy.nothingLeft), "USD, 100%, Nothing left to settle")
        XCTAssertEqual(SettledSpoken.next(sentence: "Kiran pays you", amount: spoken, remaining: "3 payments to go"),
                       "Kiran pays you \(spoken). 3 payments to go. Marks this as paid here. It does not move money.")
        let settled = try appSource("Settled.swift")
        let card = try slice(settled, from: "struct SettledCard: View {", to: "\n}\n")
        XCTAssertEqual(count(card, "ffiMoneySpoken(minorUnits: b.outstandingMinor, currencyCode: b.currencyCode, locale: AppLocale.current)"), 1)
        XCTAssertEqual(count(card, ".accessibilityLabel(Text(verbatim: SettledSpoken.block("), 1)
        XCTAssertFalse(card.contains(".accessibilityElement(children: .combine)"), "a combined block reads the printed money")
        let next = try slice(settled, from: "struct GroupNextCard: View {", to: "\n}\n")
        XCTAssertEqual(count(next, "ffiMoneySpoken(minorUnits: abs(pick.entry.minorUnits), currencyCode: pick.currencyCode,"), 1)
        XCTAssertEqual(count(next, ".accessibilityLabel(Text(verbatim: SettledSpoken.next("), 1)
        XCTAssertFalse(next.contains(".accessibilityElement(children: .combine)"), "the button stays its own element")
        let you = try slice(try appSource("YouProgress.swift"), from: "struct NextStepCard: View {", to: "\n}\n")
        XCTAssertEqual(count(you, ".accessibilityElement(children: .combine)"), 1, "You's words, one element")
        XCTAssertFalse(you.contains("        .glassCard()\n        .accessibilityElement(children: .combine)"), "not the whole card")
    }

    /// S5: Norse's diamond is drawn at size x sqrt 2; the header tile and the seal are laid out by that drawn side, so
    /// the tip sits on the page margin and the spacing is measured from it. Other shapes are laid out by their size.
    @MainActor
    func testTheDiamondIsLaidOutByItsDrawnSide() throws {
        let saved = AppearanceState.shared.theme
        defer { AppearanceState.shared.theme = saved }
        AppearanceState.shared.theme = .norse
        let group = GroupWire(id: String(repeating: "e", count: 32), name: "Goa trip", settlementCurrency: "INR", members: [])
        func width(_ shape: TileShape) -> CGFloat {
            UIHostingController(rootView: GroupTile(group: group, size: 48).tileFootprint(shape, size: 48))
                .sizeThatFits(in: CGSize(width: 400, height: 400)).width
        }
        // Laid out on the pixel grid (1/3 pt): 67.88 lands on 68.0.
        XCTAssertEqual(width(.diamond), 48 * 2.0.squareRoot(), accuracy: 0.34)
        XCTAssertEqual(width(.circle), 48, accuracy: 0.01)
        XCTAssertEqual(width(.square), 48, accuracy: 0.01)
        let row = try slice(try appSource("OverviewScreen.swift"), from: "private func tileRow(", to: "private func statsRow(")
        XCTAssertEqual(count(row, ".tileFootprint(TileShape.of(AppearanceState.shared.theme, person: false), size: ThemeArt.m(\"groupTile\"))"), 1,
                       "the header tile")
        let seal = try slice(try appSource("Settled.swift"), from: "struct MilestoneCard: View {", to: "\n}\n")
        XCTAssertEqual(count(seal, ".tileFootprint(kind, size: side)"), 1, "the seal")
    }

    /// S12: the bar's account badge is a bitmap in its original colours (the iOS 26 bar's glass re-tinted the drawn
    /// accent: measured #027156 for Aurora light's #0B7A5F); the look reads the token itself (.drafts/d221/fix/ios).
    @MainActor
    func testTheBarBadgeIsDrawnInItsOriginalColours() throws {
        let shell = try appSource("HomeShell.swift")
        XCTAssertEqual(count(shell, "ProfileBadge(store: LedgerStore.defaultStore(), size: 30, original: true)"), 1)
        let badge = try slice(try appSource("Profile.swift"), from: "struct ProfileBadge: View {", to: "\n}\n")
        XCTAssertEqual(count(badge, "Image(uiImage: rendered).renderingMode(.original)"), 1)
        XCTAssertEqual(count(badge, "rendered = BarBadge.render(face, scheme: scheme, scale: displayScale)"), 1)
        let image = try XCTUnwrap(BarBadge.render(Color.red.frame(width: 30, height: 30), scheme: .dark, scale: 3))
        XCTAssertEqual(image.size, CGSize(width: 30, height: 30))
        XCTAssertEqual(image.scale, 3)
    }

    // MARK: - D-221 Task 8: the celebration

    /// Settled.swift's celebration section (the overlay, its dim, its timeline and its announcement).
    private func celebrationSource() throws -> String {
        let settled = try appSource("Settled.swift")
        let anchor = "// MARK: - D-221 Task 8: the celebration"
        XCTAssertEqual(count(settled, anchor), 1, "the celebration's section")
        return try XCTUnwrap(settled.components(separatedBy: anchor).last)
    }

    private func overlaySource() throws -> String {
        try slice(try appSource("Settled.swift"), from: "struct CelebrationOverlay: View {", to: "\n}\n")
    }

    private func groupPage() throws -> String {
        try slice(try appSource("OverviewScreen.swift"), from: "struct GroupPageScreen: View {", to: "\n}\n\n")
    }

    /// GUARANTEES #2: the celebration never takes a touch. The overlay turns hit testing off and holds no control,
    /// gesture or presentation; the page draws it in its own overlay (never a sheet, cover, alert or dialog), and the
    /// dim is an opacity on the content, never a scrim.
    func testTheCelebrationNeverTakesATouch() throws {
        let overlay = try overlaySource()
        XCTAssertEqual(count(overlay, ".allowsHitTesting(false)"), 1, "the overlay takes no touch")
        let section = try celebrationSource()
        for banned in [".sheet(", ".fullScreenCover(", ".alert(", ".confirmationDialog(", ".popover(", "Button(", "Link(",
                       ".onTapGesture", ".gesture(", ".simultaneousGesture(", ".highPriorityGesture(", ".allowsHitTesting(true)",
                       "NavigationLink"] {
            XCTAssertFalse(section.contains(banned), "the celebration holds \(banned)")
        }
        let dim = try slice(section, from: "struct CelebrationDim: ViewModifier {", to: "\n}\n")
        XCTAssertTrue(dim.contains("content.opacity("), "the dim is the content's own opacity")
        XCTAssertFalse(dim.contains("allowsHitTesting") || dim.contains(".overlay") || dim.contains("disabled"), "the dim blocks nothing")

        let page = try groupPage()
        XCTAssertEqual(count(page, "CelebrationOverlay("), 1, "one overlay")
        XCTAssertEqual(count(page, ".overlay {\n            if let celebration, let named {\n                CelebrationOverlay(play: celebration, groupName: named.group.name)"),
                       1, "drawn in the page's own overlay")
        for banned in [".fullScreenCover(", ".alert(", ".confirmationDialog(", ".popover("] {
            XCTAssertFalse(page.contains(banned), "the group page presents \(banned)")
        }
        XCTAssertEqual(count(page, ".sheet("), 2, "the picture and the review sheets only")
        XCTAssertEqual(count(page, "            .padding(16)\n            .modifier(CelebrationDim(play: celebration))\n        }"), 1,
                       "the content stack dims, inside the scroll view (the art behind does not)")
    }

    /// L45: the burst is the motion engine's (its canvas on the shared grid, its drawing), only when motion is allowed;
    /// the words keep the art's grid too; the page ends the play after `CelebrationTiming.total`.
    func testTheBurstIsTheEngines() throws {
        let overlay = try overlaySource()
        let gate = "if !play.still && Celebration.showsBurst(reduceMotion: reduceMotion) {"
        XCTAssertEqual(count(overlay, gate), 1, "the burst only when motion is allowed")
        let burst = try slice(overlay, from: gate, to: "TimelineView(")
        XCTAssertEqual(count(burst, "ArtCanvas(still: 0, moment: true, symbols: { ArtDraw.burstSymbols(theme) }) { ctx, size, t in"), 1,
                       "D-239 (decision 137): the burst is a moment: it ticks under power saving")
        XCTAssertEqual(count(burst, "ArtDraw.burst(&ctx, size, t - play.startedAt, theme: theme)"), 1, "elapsed is t - startedAt")
        XCTAssertEqual(count(overlay, "ArtCanvas("), 1, "one canvas")
        XCTAssertEqual(count(overlay, "ArtDraw.burst("), 1, "one burst")
        XCTAssertEqual(count(overlay, "TimelineView(ArtSchedule(paused: play.still)) { timeline in"), 1, "the words on the art's grid")
        let dim = try slice(try celebrationSource(), from: "struct CelebrationDim: ViewModifier {", to: "\n}\n")
        XCTAssertEqual(count(dim, "TimelineView(ArtSchedule(paused: play.map(\\.still) ?? true)) { timeline in"), 1, "the dim on the art's grid")
        let page = try groupPage()
        XCTAssertEqual(count(page, ".task(id: celebration) {"), 1)
        let end = try slice(page, from: ".task(id: celebration) {", to: "\n        }\n")
        XCTAssertTrue(end.contains("try await Task.sleep(for: .seconds(CelebrationTiming.total))\n                celebration = nil"),
                      "the play is removed after CelebrationTiming.total")
        // A play's clock: elapsed from the reference-date seconds it started at; a still play has none.
        let moving = CelebrationPlay(startedAt: 800_000_000, still: false)
        XCTAssertEqual(moving.elapsed(at: Date(timeIntervalSinceReferenceDate: 800_000_001.5)), 1.5, accuracy: 1e-6)
        XCTAssertEqual(CelebrationPlay(startedAt: 800_000_000, still: true).elapsed(at: Date(timeIntervalSinceReferenceDate: 800_000_003)), 0)
    }

    /// Design 12, Android's CelebrationTimeline (worked by hand): moving, the content dims linearly to 0.22 over 0.3 s
    /// and back over the last 0.3 s; the words fade in linearly over 0.4 s rising from 10 pt below, and undo it over
    /// the last 0.4 s. Still: dimmed and shown at once for 3.6 s, then gone.
    func testTheCelebrationTimeline() {
        let dims: [(Double, Double)] = [(-1, 1), (0, 1), (0.15, 0.61), (0.3, 0.22), (1.8, 0.22), (3.3, 0.22), (3.45, 0.61), (3.6, 1), (5, 1)]
        for (t, d) in dims { XCTAssertEqual(CelebrationTimeline.dim(elapsed: t, still: false), d, accuracy: 1e-9, "dim at \(t)") }
        let words: [(Double, Double, Double)] = [(0, 0, 10), (0.2, 0.5, 5), (0.4, 1, 0), (1.8, 1, 0), (3.4, 0.5, 5), (3.6, 0, 10), (4, 0, 10)]
        for (t, a, r) in words {
            let m = CelebrationTimeline.message(elapsed: t, still: false)
            XCTAssertEqual(m.alpha, a, accuracy: 1e-9, "words' alpha at \(t)")
            XCTAssertEqual(m.rise, r, accuracy: 1e-9, "words' rise at \(t)")
        }
        for t in [0, 3.59] {
            XCTAssertEqual(CelebrationTimeline.dim(elapsed: t, still: true), 0.22, "still: dimmed at once")
            XCTAssertEqual(CelebrationTimeline.message(elapsed: t, still: true), .init(alpha: 1, rise: 0), "still: shown at once, no rise")
        }
        XCTAssertEqual(CelebrationTimeline.dim(elapsed: 3.6, still: true), 1)
        XCTAssertEqual(CelebrationTimeline.message(elapsed: 3.6, still: true), .init(alpha: 0, rise: 0))
    }

    /// VoiceOver hears the two lines once, as a queued announcement when the overlay appears; the words on screen are
    /// hidden from it (no second reading, no focus on a view that vanishes). The words are drawn in `text` (textDim
    /// fails 4.5 over the dimmed page, art.py 23) in the agreed roles, 24 pt from the sides, 6 apart.
    func testTheCelebrationIsAnnounced() throws {
        XCTAssertEqual(Celebration.announcement("Goa trip"), "Goa trip is all settled. Nobody owes anybody. Nice.")
        let section = try celebrationSource()
        XCTAssertEqual(count(section, "UIAccessibility.post(notification: .announcement, argument:"), 1, "posted from one place")
        let announce = try slice(section, from: "static func announce(_ group: String) {", to: "\n    }\n")
        XCTAssertTrue(announce.contains("UIAccessibility.post(notification: .announcement, argument:"))
        XCTAssertTrue(announce.contains("announcement(group)"))
        XCTAssertTrue(announce.contains(".accessibilitySpeechQueueAnnouncement: true"), "queued behind what VoiceOver is saying")
        let words = try slice(section, from: "static func announcement(_ group: String) -> String {", to: "\n")
        XCTAssertTrue(words.contains("SettleAllCopy.settled(group)") && words.contains("SettleAllCopy.settledDetail"))
        let overlay = try overlaySource()
        // D-224 T5: the overlay's appearance also clears a toast and plays the success (Design 9); it still says it once.
        XCTAssertEqual(count(overlay, ".onAppear {"), 1, "once per play: the overlay appears once")
        XCTAssertEqual(count(try slice(overlay, from: ".onAppear {", to: "\n        }"), "Celebration.announce(groupName)"), 1,
                       "said as the overlay appears")
        XCTAssertEqual(count(overlay, "Celebration.announce("), 1, "and only there")
        XCTAssertEqual(count(overlay, ".accessibilityHidden(true)"), 1)
        XCTAssertEqual(count(overlay, "Text(verbatim: SettleAllCopy.settled(groupName))\n                            .font(TypeRole.display(TypeScale.displayName, weight: 700))"), 1)
        XCTAssertEqual(count(overlay, "Text(verbatim: SettleAllCopy.settledDetail)\n                            .font(TypeRole.body(TypeScale.detail))"), 1)
        XCTAssertEqual(count(overlay, ".foregroundStyle(Tokens.text)"), 1, "both lines in text")
        XCTAssertFalse(overlay.contains("textDim"))
        XCTAssertEqual(count(overlay, "VStack(spacing: 6) {"), 1)
        XCTAssertEqual(count(overlay, ".padding(.horizontal, 24)"), 1)
        XCTAssertEqual(count(overlay, "y: geo.size.height * CelebrationTiming.messageTop"), 1, "centred at messageTop of the page")
        XCTAssertFalse(section.contains("print(") || section.contains("Logger") || section.contains("os_log"), "nothing logged (GUARANTEES #1)")
    }

    /// Design 12: the load only decides (a pending play from `Celebration.plays`, then remembers); the play starts from
    /// the page being seen (on screen in its stack, its tab shown, the app active), never inside `load()`. Memory only.
    func testThePlayWaitsUntilVisible() throws {
        let page = try groupPage()
        let load = try slice(page, from: "    private func load() {", to: "    private func header(")
        XCTAssertEqual(count(load, "let after = SettledSummary.allSettled(progress)"), 1)
        // S8: the per-group memory decides, and a later unsettled load clears a waiting play.
        XCTAssertEqual(count(load, "let before = SettledMemory.shared.see(groupID, settled: after)"), 1)
        XCTAssertEqual(count(load, "celebrationPending = Celebration.pending(was: celebrationPending, before: before, after: after, isCopy: loaded.readOnly != nil)"), 1)
        XCTAssertFalse(page.contains("settledBefore"), "the page's own memory would forget on a pop")
        for banned in ["CelebrationPlay(", "celebration = ", "playIfSeen("] {
            XCTAssertFalse(load.contains(banned), "load() starts the play: \(banned)")
        }
        XCTAssertEqual(count(page, "CelebrationPlay("), 1, "one start site")
        let start = try slice(page, from: "private func playIfSeen() {", to: "\n    }\n")
        XCTAssertTrue(start.contains("guard celebrationPending, appeared, artVisible, phase == .active else { return }"))
        XCTAssertTrue(start.contains("celebrationPending = false"))
        XCTAssertTrue(start.contains("CelebrationPlay(startedAt: Date().timeIntervalSinceReferenceDate,"))
        XCTAssertTrue(start.contains("still: !Celebration.showsBurst(reduceMotion: reduceMotion))"))
        for anchor in ["@Environment(\\.artVisible) private var artVisible", ".onAppear { appeared = true; playIfSeen() }",
                       ".onDisappear { appeared = false; celebration = nil }",
                       ".onChange(of: artVisible) { if !artVisible { celebration = nil }; playIfSeen() }",
                       ".onChange(of: phase) { if phase != .active { celebration = nil }; playIfSeen() }",
                       ".onChange(of: celebrationPending) { playIfSeen() }", "@State private var celebrationPending = false",
                       "@State private var celebration: CelebrationPlay?"] {
            XCTAssertEqual(count(page, anchor), 1, anchor)
        }
        XCTAssertFalse(page.contains("@SceneStorage") || page.contains("@AppStorage") || page.contains("UserDefaults"), "nothing stored")
    }
}
