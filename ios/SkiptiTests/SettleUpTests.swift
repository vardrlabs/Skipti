import XCTest
@testable import Skipti

/// D-247 (U29, decisions 167 to 170, 182, 183): Settle up's other amount and its cap, the 5 s hold behind Undo, the
/// settled words, and the creator fall-back. Android's twin is SettleUpTest.kt.
@MainActor
final class SettleUpTests: XCTestCase {
    private func tempStore(_ tag: String) throws -> (LedgerStore, URL) {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("\(tag)-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return (LedgerStore(path: dir.appendingPathComponent("ledger.db").path), dir)
    }

    private func appSource(_ file: String) throws -> String {
        let url = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("Skipti/App/\(file)")
        return try String(contentsOf: url, encoding: .utf8)
    }

    private func count(_ text: String, _ needle: String) -> Int { text.components(separatedBy: needle).count - 1 }

    /// A centre that plays and says nothing, on its own gate.
    private func quietCenter() -> DoneCenter { DoneCenter(gate: SuccessGate(), haptic: { _, _ in }, announce: { _ in }) }

    /// Paris from the groups proof's shape: Ana paid 64.00 split with you, so you owe Ana 32.00.
    private func paris(_ store: LedgerStore) throws -> (NamedGroupWire, String) {
        _ = try store.setYourName("Prithvi")
        let named = try store.createGroup(name: "Paris", settlementCurrency: "EUR", people: ["Ana"])
        let ana = try XCTUnwrap(named.members.first { $0.id != named.selfId }).id
        _ = try store.appendExpense(LedgerStore.ExpenseDTO(
            payer: ana, paidMinor: 6_400, currencyCode: "EUR", group: named.group.id, conversion: nil,
            shares: [.init(id: named.selfId, amountMinor: 3_200), .init(id: ana, amountMinor: 3_200)], note: "Museum passes",
            timestampMs: 1_772_409_600_000, category: nil, addedBy: ana))
        return (named, ana)
    }

    // MARK: - The copy

    /// The words, verbatim on both phones (Android's SettleUpTest reads the same table), none with an em or en dash.
    func testTheCopyTable() {
        let table: [(String, String)] = [
            (SettleAllCopy.anotherAmount, "Another amount"),
            (SettleAllCopy.howMuchPaid, "How much was paid?"),
            (SettleAllCopy.settle, "Settle"),
            (SettleAllCopy.cancel, "Cancel"),
            (SettleAllCopy.moreThanDebt(youOwe: false, other: "Ana"), "That is more than Ana owes."),
            (SettleAllCopy.moreThanDebt(youOwe: true, other: "Ana"), "That is more than you owe Ana."),
            (SettleAllCopy.explainer, "Marks this as paid here. It does not move money."),
            (SettleAllCopy.youSettled("Flat 4B"), "You're settled in Flat 4B"),
            (SettleAllCopy.youSettledDetail, "You don't owe anything here."),
            (SettleAllCopy.settled("Flat 4B"), "Flat 4B is all settled"),
            (SettleAllCopy.settledDetail, "Nobody owes anybody. Nice."),
            (DoneCopy.settledWith("Ana"), "Settled with Ana. No money moved."),
            (DoneCopy.undo, "Undo"),
            (DoneCopy.notRecorded, "Not recorded."),
            (SettledCopy.paymentsToGo(1), "1 payment to go"),
            (SettledCopy.paymentsToGo(4), "4 payments to go"),
        ]
        for (got, want) in table {
            XCTAssertEqual(got, want)
            XCTAssertFalse(got.contains("\u{2014}") || got.contains("\u{2013}"), got) // em-dash-ok: asserts the dashes are absent
        }
    }

    // MARK: - Another amount (decisions 167, 183)

    /// The field starts at the whole debt in core's entry form; it settles anything above 0 up to the debt; more is
    /// refused in words (both directions); empty, zero and what is not an amount keep Settle disabled with nothing said.
    func testTheOtherAmountIsCappedAtTheDebt() {
        XCTAssertEqual(OtherAmount.prefill(debt: 45_000, currency: "INR"), "450.00")
        XCTAssertEqual(OtherAmount.prefill(debt: 1_005, currency: "JPY"), "1005")
        let ok: (Int64) throws -> Void = { _ in }
        func verdict(_ text: String, youOwe: Bool = false) -> OtherAmount.Verdict {
            OtherAmount.verdict(text, debt: 45_000, currency: "INR", youOwe: youOwe, other: "Ana", check: ok)
        }
        XCTAssertEqual(verdict("450.00"), .settles(45_000), "the whole debt")
        XCTAssertEqual(verdict("450"), .settles(45_000))
        XCTAssertEqual(verdict("200"), .settles(20_000), "a partial")
        XCTAssertEqual(verdict(" 0.01 "), .settles(1), "the least")
        // The cap (decision 183): a partial never flips who owes whom.
        XCTAssertEqual(verdict("450.01"), .tooMuch("That is more than Ana owes."))
        XCTAssertEqual(verdict("451", youOwe: true), .tooMuch("That is more than you owe Ana."))
        XCTAssertEqual(verdict("99999999"), .tooMuch("That is more than Ana owes."))
        // Settle disabled, nothing said.
        for text in ["", "0", "0.00", "abc", "1.2.3", "-5", "₹10"] {
            XCTAssertEqual(verdict(text), .incomplete, "'\(text)'")
        }
        // Core's check refusing reads as incomplete (never settles).
        XCTAssertEqual(OtherAmount.verdict("10", debt: 45_000, currency: "INR", youOwe: false, other: "Ana") { _ in
            throw CoreError.InvalidInput("no")
        }, .incomplete)
        XCTAssertFalse(OtherAmountSheet.settles(.incomplete))
        XCTAssertFalse(OtherAmountSheet.settles(.tooMuch("x")))
        XCTAssertTrue(OtherAmountSheet.settles(.settles(1)))
    }

    /// A partial settle, through the hold, on a real vault: while held the card shows the remainder (core's lines with
    /// the held settlement counted); once written, the store holds exactly the partial and the card the remainder.
    func testAPartialLeavesTheRemainderOnTheCard() throws {
        let (store, dir) = try tempStore("settle-partial")
        defer { try? FileManager.default.removeItem(at: dir) }
        let (named, ana) = try paris(store)
        let hold = SettleHold(center: quietCenter(), seconds: 60, written: {})
        let section = try XCTUnwrap(try SettleAll.sections(store).first)
        let entry = try XCTUnwrap(section.lines.first?.entries.first)
        XCTAssertEqual(entry, ShareOut(id: ana, minorUnits: -3_200), "you owe Ana 32.00")
        guard case .settles(let minor) = OtherAmount.verdict("12.00", debt: 3_200, currency: "EUR", youOwe: true, other: "Ana",
                                                              check: { _ in }) else { return XCTFail("12.00 settles") }
        let partial = SettleAll.settlement(for: entry, currency: "EUR", in: named, amount: minor)
        XCTAssertEqual(partial.amountMinor, 1_200)
        XCTAssertEqual(partial.from, named.selfId, "you pay")
        try hold.hold(partial, other: "Ana", store: store)
        XCTAssertEqual(try store.loadSettlements().count, 0, "nothing written while held")
        let held = try XCTUnwrap(try SettleAll.sections(store, held: hold.held).first)
        XCTAssertEqual(held.lines.first?.entries, [ShareOut(id: ana, minorUnits: -2_000)], "the remainder, 20.00, at once")
        XCTAssertEqual(held.state, .open, "the card stays")
        hold.commit()
        XCTAssertEqual(try store.loadSettlements(), [partial], "the partial, written once")
        let after = try XCTUnwrap(try SettleAll.sections(store, held: hold.held).first)
        XCTAssertEqual(after.lines.first?.entries, [ShareOut(id: ana, minorUnits: -2_000)])
    }

    // MARK: - The hold and Undo (decision 182)

    /// Undo writes nothing: the write count stays 0, the store stays empty, the card comes back, "Not recorded." shows.
    func testUndoWritesNothing() throws {
        let (store, dir) = try tempStore("settle-undo")
        defer { try? FileManager.default.removeItem(at: dir) }
        let (named, ana) = try paris(store)
        let center = quietCenter()
        var writes = 0, written = 0
        let hold = SettleHold(center: center, seconds: 60, write: { _, _ in writes += 1 }, written: { written += 1 })
        let s = SettleAll.settlement(for: ShareOut(id: ana, minorUnits: -3_200), currency: "EUR", in: named)
        try hold.hold(s, other: "Ana", store: store)
        XCTAssertEqual(center.current, DoneMoments.line("Settled with Ana. No money moved.", action: "Undo", seconds: 60))
        XCTAssertEqual(try SettleAll.sections(store, held: hold.held).first?.state, .allSettled, "shown as settled at once")
        center.act(center.serial)
        XCTAssertNil(hold.pending)
        XCTAssertEqual(center.current, DoneMoments.line("Not recorded."))
        XCTAssertEqual(try SettleAll.sections(store, held: hold.held).first?.lines.first?.entries,
                       [ShareOut(id: ana, minorUnits: -3_200)], "the card is back")
        hold.commit()
        XCTAssertEqual(writes, 0, "Undo wrote nothing, and nothing is left to land")
        XCTAssertEqual(written, 0)
        XCTAssertEqual(try store.loadSettlements().count, 0)
    }

    /// The window ending writes once (the real store), the Undo toast goes, and a late Undo does nothing.
    func testTheTimeoutWritesOnce() async throws {
        let (store, dir) = try tempStore("settle-timeout")
        defer { try? FileManager.default.removeItem(at: dir) }
        let (named, ana) = try paris(store)
        let center = quietCenter()
        var writes = 0
        let hold = SettleHold(center: center, seconds: 0.2, write: { store, s in writes += 1; try SettleAll.record(store: store, settlement: s) },
                              written: {})
        let s = SettleAll.settlement(for: ShareOut(id: ana, minorUnits: -3_200), currency: "EUR", in: named)
        try hold.hold(s, other: "Ana", store: store)
        let serial = center.serial
        try await Task.sleep(for: .seconds(0.8))
        XCTAssertEqual(writes, 1, "written once, when the window ended")
        XCTAssertNil(hold.pending)
        XCTAssertNil(center.current, "its Undo went with it")
        XCTAssertEqual(try store.loadSettlements(), [s])
        center.act(serial)
        hold.undo(1)
        hold.commit()
        XCTAssertEqual(writes, 1, "a late Undo or another landing does nothing")
        XCTAssertEqual(try store.loadSettlements().count, 1)
    }

    /// Leaving the front lands it (SkiptiApp's scene phase calls `commit`), once; so does the screen going away.
    func testLeavingTheFrontLandsTheHeldSettle() throws {
        let (store, dir) = try tempStore("settle-front")
        defer { try? FileManager.default.removeItem(at: dir) }
        let (named, ana) = try paris(store)
        var writes: [LedgerStore.SettlementDTO] = []
        let hold = SettleHold(center: quietCenter(), seconds: 60, write: { _, s in writes.append(s) }, written: {})
        let s = SettleAll.settlement(for: ShareOut(id: ana, minorUnits: -3_200), currency: "EUR", in: named)
        try hold.hold(s, other: "Ana", store: store)
        XCTAssertEqual(writes, [], "nothing before")
        hold.commit()
        hold.commit()
        XCTAssertEqual(writes, [s], "once")
        let app = try appSource("SkiptiApp.swift")
        XCTAssertEqual(count(app, "if phase != .active { SettleHold.shared.commit() }"), 1, "the scene phase lands it")
        let all = try appSource("SettleAll.swift")
        XCTAssertTrue(all.contains("onDisappear { SettleHold.shared.commit() }"))
        XCTAssertTrue(all.contains(".onChange(of: visible) { _, shown in if !shown { SettleHold.shared.commit() } }"))
        XCTAssertEqual(count(all, ".landsHeldSettle(visible: artVisible)"), 1, "Settle up")
        XCTAssertEqual(count(try appSource("OverviewScreen.swift"), ".landsHeldSettle(visible: artVisible)"), 1, "the group page")
    }

    /// The next Settle tap lands the one held (one at a time), and the new one is held in its place.
    func testTheNextSettleLandsTheHeldOne() throws {
        let (store, dir) = try tempStore("settle-next")
        defer { try? FileManager.default.removeItem(at: dir) }
        let (named, ana) = try paris(store)
        var writes: [LedgerStore.SettlementDTO] = []
        let hold = SettleHold(center: quietCenter(), seconds: 60, write: { _, s in writes.append(s) }, written: {})
        let first = SettleAll.settlement(for: ShareOut(id: ana, minorUnits: -3_200), currency: "EUR", in: named, amount: 1_000)
        let second = SettleAll.settlement(for: ShareOut(id: ana, minorUnits: -2_200), currency: "EUR", in: named, amount: 500)
        try hold.hold(first, other: "Ana", store: store)
        try hold.hold(second, other: "Ana", store: store)
        XCTAssertEqual(writes, [first], "the first landed at the second tap")
        XCTAssertEqual(hold.held, [second], "one held at a time")
        hold.undo(1)
        XCTAssertEqual(hold.held, [second], "the first's Undo no longer reaches anything")
        hold.undo(2)
        XCTAssertEqual(writes, [first], "the second was undone: never written")
    }

    /// CLAUDE.md 10: core checks a settle before it is held; a refusal holds nothing, writes nothing, posts nothing.
    func testCoreRefusesBeforeAnythingIsHeld() throws {
        let (store, dir) = try tempStore("settle-check")
        defer { try? FileManager.default.removeItem(at: dir) }
        let (named, ana) = try paris(store)
        let center = quietCenter()
        var writes = 0
        let hold = SettleHold(center: center, seconds: 60, write: { _, _ in writes += 1 }, written: {})
        let zero = SettleAll.settlement(for: ShareOut(id: ana, minorUnits: -3_200), currency: "EUR", in: named, amount: 0)
        XCTAssertThrowsError(try hold.hold(zero, other: "Ana", store: store))
        XCTAssertNil(hold.pending)
        XCTAssertNil(center.current)
        hold.commit()
        XCTAssertEqual(writes, 0)
    }

    /// A write that fails says so and leaves nothing held.
    func testAFailedWriteSaysSo() throws {
        let (store, dir) = try tempStore("settle-fail")
        defer { try? FileManager.default.removeItem(at: dir) }
        let (named, ana) = try paris(store)
        var written = 0
        let hold = SettleHold(center: quietCenter(), seconds: 60, write: { _, _ in throw CoreError.InvalidInput("disk") },
                              written: { written += 1 })
        try hold.hold(SettleAll.settlement(for: ShareOut(id: ana, minorUnits: -3_200), currency: "EUR", in: named), other: "Ana", store: store)
        let before = hold.revision
        hold.commit()
        XCTAssertNil(hold.pending)
        XCTAssertTrue(hold.failure?.hasPrefix("Could not record the settlement.") == true)
        XCTAssertEqual(written, 0, "no push for what was not written")
        XCTAssertGreaterThan(hold.revision, before, "the screens read again: the card comes back")
    }

    /// Both settle paths hold through the one hold, and the one append is `SettleAll.record`; both screens count the
    /// held settle and read again when it changes.
    func testBothScreensHoldAndCountTheHeldSettle() throws {
        let all = try appSource("SettleAll.swift")
        let screen = try XCTUnwrap(all.components(separatedBy: "struct SettleAllScreen: View {").last)
        XCTAssertTrue(screen.contains("sections = try SettleAll.sections(store, held: SettleHold.shared.held)"))
        XCTAssertTrue(screen.contains(".onChange(of: SettleHold.shared.revision) { load() }"))
        XCTAssertTrue(screen.contains(".sheet(item: $otherAmount)"), "the sheet hangs on the always-present ScrollView")
        let page = try appSource("OverviewScreen.swift")
        XCTAssertTrue(page.contains("let settlements = try store.loadSettlements().map(\\.asInput) + SettleHold.shared.held.map(\\.asInput)"))
        XCTAssertTrue(page.contains(".onChange(of: SettleHold.shared.revision) { load() }"))
        let hold = try XCTUnwrap(all.components(separatedBy: "final class SettleHold {").last)
        XCTAssertTrue(hold.contains("try store.checkSettlement(settlement)\n        failure = nil\n        commit()"),
                      "the write's own check, the old failure cleared, then the held one lands")
        XCTAssertTrue(all.contains("{ try SettleAll.record(store: $0, settlement: $1) }"), "the live write is the one append")
    }

    // MARK: - D-247 fix wave: one check, a kept failure, the removal, the live cap, the screen reader's window

    /// I1 (a): the landing at the next Settle tap fails: its words stay (the new settle is held, the failure is not wiped
    /// by the tap that caused it); the person's next tap clears it before its own landing, which succeeds.
    func testAFailedLandingAtTheNextSettleKeepsItsWords() throws {
        let (store, dir) = try tempStore("settle-kept")
        defer { try? FileManager.default.removeItem(at: dir) }
        let (named, ana) = try paris(store)
        var calls = 0
        let hold = SettleHold(center: quietCenter(), seconds: 60, write: { _, _ in
            calls += 1
            if calls == 1 { throw CoreError.InvalidInput("disk") }
        }, written: {})
        let first = SettleAll.settlement(for: ShareOut(id: ana, minorUnits: -3_200), currency: "EUR", in: named, amount: 1_000)
        let second = SettleAll.settlement(for: ShareOut(id: ana, minorUnits: -3_200), currency: "EUR", in: named, amount: 500)
        try hold.hold(first, other: "Ana", store: store)
        XCTAssertNil(hold.failure)
        try hold.hold(second, other: "Ana", store: store)
        XCTAssertEqual(calls, 1, "the first landed (and failed) at the second tap")
        XCTAssertEqual(hold.held, [second], "the second is held")
        XCTAssertTrue(hold.failure?.hasPrefix("Could not record the settlement.") == true, "the first's failure is kept")
        try hold.hold(first, other: "Ana", store: store)
        XCTAssertEqual(calls, 2)
        XCTAssertNil(hold.failure, "the next tap cleared it; its own landing wrote")
    }

    /// I1 (c): the hold runs the write's own check (`LedgerStore.checkSettlement`: the place, then core): a group whose
    /// place this phone lost, and a payment whose two ends become one id, are refused at the tap: nothing held, nothing
    /// posted, nothing written; core's bare check alone would have let both through.
    func testTheHoldRunsTheWritesOwnCheck() throws {
        typealias F = FounderVault
        let (lostStore, lostDir) = try tempStore("settle-lost")
        defer { try? FileManager.default.removeItem(at: lostDir) }
        let lost = try F.holdOld(F.make(lostStore, pClaim: nil))
        let paid = LedgerStore.SettlementDTO(from: F.y, to: F.c, amountMinor: 600, currencyCode: "INR", group: F.old)
        XCTAssertNoThrow(try ffiCheckSettlement(settlement: paid.asInput), "not vacuous: core alone takes it")
        let (sameStore, sameDir) = try tempStore("settle-same")
        defer { try? FileManager.default.removeItem(at: sameDir) }
        let same = try F.make(sameStore, pClaim: F.x)
        let toSelf = LedgerStore.SettlementDTO(from: F.y, to: F.x, amountMinor: 100, currencyCode: "INR", group: F.old)
        XCTAssertNoThrow(try ffiCheckSettlement(settlement: toSelf.asInput), "not vacuous: core alone takes it")
        for (store, s, refusal) in [(lost, paid, "lost"), (same, toSelf, "same")] {
            let center = quietCenter()
            var writes = 0
            let hold = SettleHold(center: center, seconds: 60, write: { _, _ in writes += 1 }, written: {})
            XCTAssertThrowsError(try hold.hold(s, other: "Bo", store: store)) { error in
                if refusal == "lost" {
                    XCTAssertEqual(error as? LedgerStore.Placing.PlaceLost, LedgerStore.Placing.PlaceLost())
                } else {
                    XCTAssertEqual(error as? LedgerStore.Placing.SameEnds, LedgerStore.Placing.SameEnds())
                }
            }
            XCTAssertNil(hold.pending, refusal)
            XCTAssertNil(center.current, "no toast: \(refusal)")
            hold.commit()
            XCTAssertEqual(writes, 0, refusal)
        }
        // The append runs the same check (one function, so the hold and the write cannot disagree).
        let ledger = try appSource("LedgerStore.swift")
        XCTAssertEqual(count(ledger, "let placing = try checkSettlement(settlement)"), 1, "appendSettlement's check")
        XCTAssertEqual(count(ledger, "try ffiCheckSettlement(settlement: try placing.settlement(settlement).asInput)"), 1,
                       "the check lives in checkSettlement alone")
    }

    /// I1 (b): the group page shows a held settle's failure in words, as Settle up does (source: the line is the page's
    /// own error line).
    func testTheGroupPageSaysAHeldSettleFailed() throws {
        XCTAssertEqual(count(try appSource("OverviewScreen.swift"), "if let error = error ?? SettleHold.shared.failure {"), 1)
        XCTAssertEqual(count(try appSource("SettleAll.swift"), "if let error = error ?? SettleHold.shared.failure {"), 1)
    }

    /// I2: a settle held for Undo, then the removal of the same person: the removal's read lands the hold first, and
    /// Settle first's payments, worked out before the hold landed, are worked out again: the debt is recorded once.
    func testAHeldSettleThenSettleFirstRecordsTheDebtOnce() throws {
        let (store, dir) = try tempStore("settle-removal")
        defer { try? FileManager.default.removeItem(at: dir) }
        let (named, ana) = try paris(store)
        let group = named.group.id
        let hold = SettleHold(center: quietCenter(), seconds: 60, written: {})
        // What Settle first showed before the settle was held: you pay Ana 32.00.
        let shown = Removal.settleFirst(lines: try Removal.lines(store: store, group: group, member: ana), member: ana, group: group)
        XCTAssertEqual(shown, [LedgerStore.SettlementDTO(from: named.selfId, to: ana, amountMinor: 3_200, currencyCode: "EUR", group: group)])
        let held = SettleAll.settlement(for: ShareOut(id: ana, minorUnits: -3_200), currency: "EUR", in: named)
        try hold.hold(held, other: "Ana", store: store)
        let pay = try Removal.settleFirstLanding(store: store, shown: shown, member: ana, group: group, started: false, hold: hold)
        XCTAssertEqual(pay, [], "the held settle landed: nothing left to settle first")
        for s in pay { _ = try store.appendSettlement(s) }
        XCTAssertEqual(try store.loadSettlements(), [held], "the debt recorded once")
        XCTAssertEqual(Removal.plan(lines: try Removal.lines(store: store, group: group, member: ana), member: ana), [:],
                       "Ana is square: never paid twice")
        // Nothing held: the payments shown are the payments recorded.
        XCTAssertEqual(try Removal.settleFirstLanding(store: store, shown: shown, member: ana, group: group, started: false,
                                                     hold: hold), shown)

        // The flow's read lands the hold too, before it reads the balance.
        let (store2, dir2) = try tempStore("settle-removal-load")
        defer { try? FileManager.default.removeItem(at: dir2) }
        let (named2, ana2) = try paris(store2)
        let hold2 = SettleHold(center: quietCenter(), seconds: 60, written: {})
        try hold2.hold(SettleAll.settlement(for: ShareOut(id: ana2, minorUnits: -3_200), currency: "EUR", in: named2, amount: 1_200),
                       other: "Ana", store: store2)
        let lines = try Removal.linesLandingHeld(store: store2, group: named2.group.id, member: ana2, hold: hold2)
        XCTAssertNil(hold2.pending, "landed")
        XCTAssertEqual(Removal.plan(lines: lines, member: ana2), ["EUR": 2_000], "the balance after the held 12.00")
        XCTAssertEqual(Removal.settleFirst(lines: lines, member: ana2, group: named2.group.id).map(\.amountMinor), [2_000])
        // Both of the flow's reads, and Settle first's tap, go through them.
        let flow = try appSource("RemovalFlow.swift")
        XCTAssertEqual(count(flow, "try? Removal.linesLandingHeld(store: context.store, group: group, member: member)"), 1, "the review")
        XCTAssertEqual(count(flow, "lines = try Removal.linesLandingHeld(store: context.store, group: group, member: member)"), 1, "the start")
        XCTAssertEqual(count(flow, "pay = try Removal.settleFirstLanding("), 1, "Settle first")
        XCTAssertEqual(count(flow, "append: { i in _ = try context.store.appendSettlement(pay[i]) }"), 1)
    }

    /// Review M3: Settle on the sheet checks the cap against the card's LIVE debt: a payment that arrived meanwhile and
    /// shrank it refuses in the cap's words; the card gone (or turned the other way) refuses too; within it settles.
    func testTheCapIsCheckedAgainstTheLiveDebtAtSettle() throws {
        let (store, dir) = try tempStore("settle-live-cap")
        defer { try? FileManager.default.removeItem(at: dir) }
        let (named, ana) = try paris(store)
        let group = named.group.id
        func live() throws -> ShareOut? {
            SettleAll.liveEntry(try SettleAll.sections(store), group: group, currency: "EUR", person: ana, youOwe: true)
        }
        XCTAssertEqual(try live(), ShareOut(id: ana, minorUnits: -3_200), "the sheet opened on 32.00")
        XCTAssertEqual(OtherAmount.verdict("25.00", debt: 3_200, currency: "EUR", youOwe: true, other: "Ana", check: { _ in }),
                       .settles(2_500), "the field, against the debt it opened on")
        // A payment of 12.00 arrives (a sync) while the sheet is open.
        _ = try store.appendSettlement(SettleAll.settlement(for: ShareOut(id: ana, minorUnits: -3_200), currency: "EUR",
                                                            in: named, amount: 1_200))
        let shrunk = try live()
        XCTAssertEqual(shrunk, ShareOut(id: ana, minorUnits: -2_000))
        XCTAssertEqual(OtherAmount.atSettle(2_500, live: shrunk, youOwe: true, other: "Ana"),
                       .tooMuch("That is more than you owe Ana."), "refused at the tap, in the cap's words")
        XCTAssertEqual(OtherAmount.atSettle(2_000, live: shrunk, youOwe: true, other: "Ana"), .settles(2_000))
        XCTAssertEqual(OtherAmount.atSettle(1, live: nil, youOwe: false, other: "Ana"), .tooMuch("That is more than Ana owes."),
                       "the card is gone")
        XCTAssertNil(SettleAll.liveEntry(try SettleAll.sections(store), group: group, currency: "EUR", person: ana, youOwe: false),
                     "the other way round is not the same card")
        XCTAssertNil(SettleAll.liveEntry(try SettleAll.sections(store), group: group, currency: "INR", person: ana, youOwe: true))
        // The sheet shows the tap's refusal as the cap's line; the field's own line comes first.
        XCTAssertEqual(OtherAmountSheet.capWords(.settles(1), refused: "r"), "r")
        XCTAssertEqual(OtherAmountSheet.capWords(.tooMuch("t"), refused: "r"), "t")
        XCTAssertNil(OtherAmountSheet.capWords(.settles(1), refused: nil))
        let all = try appSource("SettleAll.swift")
        XCTAssertEqual(count(all, "switch OtherAmount.atSettle(minor, live: live, youOwe: draft.youOwe, other: draft.other) {"), 1)
        XCTAssertEqual(count(all, "if case .settles(let minor) = verdict { refused = onSettle(minor) }"), 1)
    }

    /// Decision 201: with VoiceOver running the hold lasts 12 s instead of 5, the toast stays as long, and the
    /// announcement names the Undo and its time.
    func testTheHoldIsLongerWithAScreenReader() throws {
        XCTAssertEqual(SettleHold.window(screenReader: false), 5)
        XCTAssertEqual(SettleHold.window(screenReader: false), DoneCenter.actionSeconds)
        XCTAssertEqual(SettleHold.window(screenReader: true), 12)
        let (store, dir) = try tempStore("settle-reader")
        defer { try? FileManager.default.removeItem(at: dir) }
        let (named, ana) = try paris(store)
        let s = SettleAll.settlement(for: ShareOut(id: ana, minorUnits: -3_200), currency: "EUR", in: named)
        for (reader, seconds) in [(true, 12.0), (false, 5.0)] {
            var said: [String] = []
            let center = DoneCenter(gate: SuccessGate(), haptic: { _, _ in }, announce: { said.append($0) })
            let hold = SettleHold(center: center, screenReader: { reader }, write: { _, _ in }, written: {})
            try hold.hold(s, other: "Ana", store: store)
            guard case .small(let line) = center.current else { return XCTFail("a toast") }
            XCTAssertEqual(line.actionSeconds, seconds)
            XCTAssertEqual(DoneMoments.toastSeconds(line), seconds, "the toast lasts the hold's window")
            XCTAssertEqual(said, ["Settled with Ana. No money moved. Undo is available for \(Int(seconds)) seconds."])
            hold.undo(1)
        }
        XCTAssertEqual(DoneCopy.spoken(DoneLine(title: "Name saved", parts: [])), "Name saved", "no action: nothing added")
        XCTAssertEqual(DoneCopy.actionSpoken("Undo", seconds: "12"), "Undo is available for 12 seconds.")
        let all = try appSource("SettleAll.swift")
        XCTAssertEqual(count(all, "screenReader: @escaping @MainActor () -> Bool = { UIAccessibility.isVoiceOverRunning }"), 1)
        XCTAssertEqual(count(all, "let seconds = self.seconds ?? SettleHold.window(screenReader: screenReader())"), 1)
    }

    // MARK: - The settled words and the count (decision 168)

    private func block(_ code: String, settled: Bool) -> SettledProgressOut {
        SettledProgressOut(currencyCode: code, settledMinor: settled ? 100 : 0, outstandingMinor: settled ? 0 : 100,
                           basisPoints: settled ? 10_000 : 0, settled: settled)
    }

    /// "All settled" only from core's allSettled; you square while others owe is its own words; you owing is the cards.
    func testAllSettledOnlyWhenCoreSaysSo() {
        XCTAssertEqual(SettleState.of(youSquare: false, progress: [block("INR", settled: false)]), .open)
        XCTAssertEqual(SettleState.of(youSquare: false, progress: [block("INR", settled: true)]), .open, "your lines decide the cards")
        XCTAssertEqual(SettleState.of(youSquare: true, progress: [block("INR", settled: true)]), .allSettled)
        XCTAssertEqual(SettleState.of(youSquare: true, progress: [block("INR", settled: true), block("USD", settled: true)]), .allSettled)
        XCTAssertEqual(SettleState.of(youSquare: true, progress: [block("INR", settled: false)]), .youSettled)
        XCTAssertEqual(SettleState.of(youSquare: true, progress: [block("INR", settled: true), block("USD", settled: false)]), .youSettled,
                       "one currency still open is not all settled")
        XCTAssertEqual(SettleState.of(youSquare: true, progress: []), .allSettled, "nothing recorded yet: nobody owes")
        let all = try? appSource("SettleAll.swift")
        XCTAssertTrue(all?.contains("SettleAllCopy.youSettled(named.group.name)") == true, "the screen shows the you-only words")
    }

    /// D-247 sabotage pin (row I11): each settled state draws its own two lines on the branch of core's state: "all
    /// settled" with "Nobody owes anybody. Nice.", "You're settled in" with "You don't owe anything here." (never the
    /// all-settled detail under the you-only title). Android's twin is each_settled_state_draws_its_own_words.
    func testEachSettledStateDrawsItsOwnWords() throws {
        let all = try appSource("SettleAll.swift")
        let section = try XCTUnwrap(all.components(separatedBy: "private func sectionView(_ section: SettleSection) -> some View {")
            .last?.components(separatedBy: "private func debtCard(").first)
        XCTAssertEqual(count(section, "let all = section.state == .allSettled"), 1)
        XCTAssertEqual(count(section, "Text(verbatim: all ? SettleAllCopy.settled(named.group.name) : SettleAllCopy.youSettled(named.group.name))"),
                       1, "the title")
        XCTAssertEqual(count(section, "Text(verbatim: all ? SettleAllCopy.settledDetail : SettleAllCopy.youSettledDetail)"), 1, "the detail")
        XCTAssertEqual(count(section, "SettleAllCopy.settledDetail"), 1, "the all-settled detail only on its own branch")
    }

    /// D-247 sabotage pin (row I3): the sheet's Settle stays disabled until the field holds an amount within the debt (the
    /// verdict settles) and while a tap's refusal stands; a tap settles only a `.settles` verdict. Android's twin is
    /// the_sheets_settle_waits_for_an_amount_within_the_debt.
    func testTheSheetsSettleWaitsForAnAmountWithinTheDebt() throws {
        let sheet = try XCTUnwrap(try appSource("SettleAll.swift").components(separatedBy: "struct OtherAmountSheet: View {").last)
        XCTAssertEqual(count(sheet, ".disabled(!OtherAmountSheet.settles(verdict) || refused != nil)"), 1)
        XCTAssertEqual(count(sheet, ".disabled("), 1, "Settle is the sheet's one disabled control")
        XCTAssertEqual(count(sheet, "if case .settles(let minor) = verdict { refused = onSettle(minor) }"), 1)
        XCTAssertFalse(OtherAmountSheet.settles(.incomplete), "empty, zero, not an amount")
        XCTAssertFalse(OtherAmountSheet.settles(.tooMuch(SettleAllCopy.moreThanDebt(youOwe: false, other: "Ana"))), "more than owed")
        XCTAssertTrue(OtherAmountSheet.settles(.settles(1)))
    }

    // MARK: - The creator fall-back (decision 170)

    /// A group this phone created reads as yours to run until the cloud's roles arrive: the owner's options, never "Ask to
    /// be removed". A group made elsewhere, or once the roles are known, reads by the cloud's answer.
    func testTheCreatorFallBackIsOnlyForThisPhonesGroupUntilRolesArrive() {
        let me = String(repeating: "1", count: 32), ana = String(repeating: "2", count: 32)
        let members = [MemberWire(id: me, name: "Prithvi"), MemberWire(id: ana, name: "Ana")]
        let claims = [ana: String(repeating: "a", count: 32)]
        func rows(_ roles: RoleRules.Roles?, createdHere: Bool) -> [PersonRow] {
            PeopleRoles.rows(members: members, selfId: me, viewerYou: me, claims: claims, roles: roles, createdHere: createdHere)
        }
        let fallBack = rows(nil, createdHere: true)
        XCTAssertEqual(PeopleRoles.viewerRole(fallBack), .owner)
        let mine = fallBack.first { $0.isSelf }!, hers = fallBack.first { !$0.isSelf }!
        XCTAssertFalse(mine.offer.askSelf, "never 'Ask to be removed'")
        XCTAssertTrue(hers.offer.remove, "the owner's remove")
        XCTAssertTrue(hers.offer.makeAdmin)
        XCTAssertTrue(RoleRules.canDelete(PeopleRoles.viewerRole(fallBack)))
        // Not this phone's group: a member until the roles say otherwise.
        let joined = rows(nil, createdHere: false)
        XCTAssertEqual(PeopleRoles.viewerRole(joined), .member)
        XCTAssertTrue(joined.first { $0.isSelf }!.offer.askSelf)
        // Roles known: the cloud wins, whoever created it.
        let theirs = RoleRules.Roles(owner: claims[ana], admins: [])
        let known = rows(theirs, createdHere: true)
        XCTAssertEqual(PeopleRoles.viewerRole(known), .member, "the cloud says Ana owns it now")
        XCTAssertTrue(known.first { $0.isSelf }!.offer.askSelf)
        XCTAssertFalse(CreatorFallBack.applies(roles: theirs, createdHere: true))
        XCTAssertFalse(CreatorFallBack.applies(roles: nil, createdHere: false))
        XCTAssertTrue(CreatorFallBack.applies(roles: nil, createdHere: true))
        // A copy offers nothing, fall-back or not.
        let copy = PeopleRoles.rows(members: members, selfId: me, viewerYou: me, claims: claims, roles: nil, readOnly: true,
                                    createdHere: true)
        XCTAssertFalse(copy.contains { $0.offer.remove || $0.offer.askSelf })
    }

    /// "This phone created it" is a `.create` record authored by THIS device: a group created here is in; a group whose
    /// create came from another device (a joined group) is not; a vault with no records holds none.
    func testCreatedHereReadsOnlyThisDevicesCreates() throws {
        let (store, dir) = try tempStore("created-here")
        defer { try? FileManager.default.removeItem(at: dir) }
        XCTAssertEqual(try LedgerStore.Sync.createdHere(store), [], "an empty vault")
        _ = try store.setYourName("Prithvi")
        let made = try store.createGroup(name: "Goa trip", settlementCurrency: "INR", people: ["Rahul"])
        let other = String(repeating: "e", count: 32)
        let joined = String(repeating: "9", count: 32)
        let record = try LedgerStore.Sync.groupOpRecord(
            .create(groupId: joined, name: "Tim's flat", settlementCurrency: "INR",
                    members: [GroupOpMemberIn(id: String(repeating: "7", count: 32), name: "Tim")]),
            hlc: HlcStamp(millis: 1, counter: 0), deviceId: other)
        let vault = try VaultGate.open(path: store.path, keystore: KeychainKeystore())
        try vault.putRecord(record: record)
        try vault.closeVault()
        let here = try LedgerStore.Sync.createdHere(store)
        XCTAssertEqual(here, [made.group.id], "only the group this device created")
        XCTAssertFalse(here.contains(joined), "another device's create")
        XCTAssertTrue(try GroupPeople.load(store: store, groupID: made.group.id).createdHere)
        XCTAssertFalse(try GroupPeople.load(store: store, groupID: joined).createdHere)
    }

    /// Display only: the three screens that offer role actions pass the fall-back's input, and nothing else changes
    /// (the calls still go to the Functions).
    func testTheFallBackReachesEveryRolesScreen() throws {
        for file in ["OverviewScreen.swift", "GroupPicker.swift", "GroupSettings.swift"] {
            XCTAssertEqual(count(try appSource(file), "createdHere: people.createdHere"), 1, file)
        }
    }
}
