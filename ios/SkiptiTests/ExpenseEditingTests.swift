import XCTest
@testable import Skipti

/// D-142: what Edit, Delete, Undo and SPEC §6.1's amount question write
/// (`ExpenseEditing`), and the form an edit opens in. The goldens are
/// written by hand: amounts as digits from ISO 4217's exponents, the prompt
/// from SPEC §6.1's sentence and core's en-IN grouping ("₹12,34,567.89" in
/// core's own format test). Android's twin is ExpenseEditingTest.
final class ExpenseEditingTests: XCTestCase {
    private var dir: URL!
    private let me = String(repeating: "0", count: 31) + "1"
    private let rahul = String(repeating: "0", count: 31) + "2"
    private let group = String(repeating: "0", count: 31) + "9"
    private let dinnerID = "1000:0:" + String(repeating: "0", count: 31) + "1"
    private let editA = "1100:0:" + String(repeating: "0", count: 31) + "1"
    private let editB = "1200:0:" + String(repeating: "0", count: 31) + "2"

    override func setUpWithError() throws {
        dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("editing-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: dir)
    }

    private func money(
        _ paid: Int64, _ shares: [(String, Int64)], payer: String? = nil,
        conversion: ConversionInput? = nil
    ) -> ExpenseInput {
        ExpenseInput(
            payer: payer ?? me, paidMinor: paid, currencyCode: "INR",
            shares: shares.map { IdAmount(id: $0.0, amountMinor: $0.1) },
            group: group, conversion: conversion
        )
    }

    private func projected(
        _ m: ExpenseInput, note: String? = "Dinner", category: String? = nil,
        seen: [String] = [], conflict: [ExpenseInput] = []
    ) -> ProjectedExpenseOut {
        ProjectedExpenseOut(
            id: dinnerID, expense: m, note: note, category: category, timestampMs: 1_000,
            addedBy: me, addedAtMs: 1_000, deleted: false, lastEditedAtMs: nil,
            lastEditedBy: nil, seen: seen, moneyConflict: conflict
        )
    }

    // MARK: - The form an edit opens in

    func testAnEqualSplitOpensAsEqualWithTheStoredAmountAsDigits() throws {
        let p = projected(money(90_000, [(me, 45_000), (rahul, 45_000)]))
        let form = try ExpenseEditing.prefill(p)
        XCTAssertEqual(form.kind, .equal)
        XCTAssertEqual(form.amountText, "900.00")
        XCTAssertEqual(form.currency, "INR")
        XCTAssertEqual(form.note, "Dinner")
        XCTAssertEqual(form.payer, me)
        XCTAssertEqual(form.participants, [me, rahul])
        XCTAssertEqual(form.exactEntries, [:])
    }

    func testAnUnevenSplitOpensAsExactToTheMinorUnit() throws {
        let p = projected(money(90_005, [(me, 30_000), (rahul, 60_005)]))
        let form = try ExpenseEditing.prefill(p)
        XCTAssertEqual(form.kind, .exact)
        XCTAssertEqual(form.amountText, "900.05")
        XCTAssertEqual(form.exactEntries, [me: "300.00", rahul: "600.05"])
    }

    func testAPayerOnlyExpenseOpensAsPayerOnly() throws {
        let p = projected(money(90_000, [(rahul, 90_000)], payer: rahul))
        let form = try ExpenseEditing.prefill(p)
        XCTAssertEqual(form.kind, .payerOnly)
        XCTAssertEqual(form.payer, rahul)
    }

    // MARK: - What an edit writes

    func testAnUntouchedEditWritesNothingEvenWithTheSharesReordered() {
        let p = projected(money(90_000, [(me, 45_000), (rahul, 45_000)]), seen: [editA])
        let same = money(90_000, [(rahul, 45_000), (me, 45_000)])
        XCTAssertNil(ExpenseEditing.revision(
            editing: p, money: same, note: "Dinner", category: nil, editor: me
        ))
    }

    func testANoteEditCarriesOnlyTheNoteAndAClearedNoteIsWritten() throws {
        let m = money(90_000, [(me, 45_000), (rahul, 45_000)])
        let p = projected(m, seen: [editA])
        let taxi = try XCTUnwrap(ExpenseEditing.revision(
            editing: p, money: m, note: "Taxi", category: nil, editor: rahul
        ))
        XCTAssertEqual(taxi, ExpenseRevisionIn(
            target: dinnerID, seen: [editA], editor: rahul, money: nil, note: "Taxi",
            category: nil, deleted: nil
        ))
        let cleared = try XCTUnwrap(ExpenseEditing.revision(
            editing: p, money: m, note: "", category: nil, editor: rahul
        ))
        XCTAssertEqual(cleared.note, "", "a removed note is written as \"\", or the old one survives")
        XCTAssertNil(cleared.money)
    }

    func testANewAmountKeepsTheGroupAndLeavesTheOldConversionBehind() throws {
        let rate = ConversionInput(
            convertedMinor: 1_080, convertedCurrency: "USD", rateNum: 12, rateDen: 1_000,
            rateTimestampMs: 500
        )
        let p = projected(money(90_000, [(me, 45_000), (rahul, 45_000)], conversion: rate))
        var moved = money(120_000, [(me, 60_000), (rahul, 60_000)])
        moved.group = nil
        let edit = try XCTUnwrap(ExpenseEditing.revision(
            editing: p, money: moved, note: "Dinner", category: nil, editor: me
        ))
        XCTAssertEqual(edit.money?.group, group, "an edit never moves an expense between groups")
        XCTAssertNil(edit.money?.conversion, "the frozen conversion described the old amount")
        XCTAssertNil(edit.note)

        let resplit = try XCTUnwrap(ExpenseEditing.revision(
            editing: p, money: money(90_000, [(me, 30_000), (rahul, 60_000)]),
            note: "Dinner", category: nil, editor: me
        ))
        XCTAssertEqual(resplit.money?.conversion, rate, "same amount, same currency: the rate still holds")
    }

    // MARK: - SPEC §6.1's question

    func testTheConflictPromptIsSpecSixOnesSentence() {
        let a = money(120_000, [(me, 60_000), (rahul, 60_000)])
        let b = money(125_000, [(me, 62_500), (rahul, 62_500)])
        let p = projected(b, seen: [editA, editB], conflict: [a, b])
        XCTAssertEqual(
            ExpenseEditing.conflictPrompt(p, locale: .enIn),
            "Two devices set different amounts. Keep ₹1,200.00 or ₹1,250.00?"
        )
        XCTAssertEqual(ExpenseEditing.keepLabel(a, in: p, locale: .enIn), "Keep ₹1,200.00")
        XCTAssertNil(ExpenseEditing.conflictPrompt(projected(b), locale: .enIn))

        let otherPayer = money(120_000, [(me, 60_000), (rahul, 60_000)], payer: rahul)
        let samePrice = projected(a, conflict: [a, otherPayer])
        XCTAssertEqual(
            ExpenseEditing.conflictPrompt(samePrice, locale: .enIn),
            "Two devices changed who paid or how it is split. Keep which?",
            "never \"Keep ₹1,200.00 or ₹1,200.00?\""
        )
        XCTAssertEqual(ExpenseEditing.keepLabel(a, in: samePrice, locale: .enIn), "Keep this one")
    }

    // MARK: - Against a real vault

    private func seededStore() throws -> (LedgerStore, String) {
        let store = LedgerStore(path: dir.appendingPathComponent("ledger.db").path)
        _ = try store.appendExpense(LedgerStore.ExpenseDTO(
            payer: me, paidMinor: 120_000, currencyCode: "INR", group: group, conversion: nil,
            shares: [.init(id: me, amountMinor: 60_000), .init(id: rahul, amountMinor: 60_000)],
            note: "Dinner", timestampMs: 1_000, category: nil, addedBy: me
        ), nowMs: 5_000)
        return (store, try XCTUnwrap(store.projectedExpenses().expenses.first).id)
    }

    /// Two amount edits that did not see each other (empty `seen`, as two
    /// offline phones would write) are a conflict; keeping one ends it, in
    /// the projection AND in the balances' kv ledger.
    func testKeepingOneAmountEndsTheConflictInTheLedger() throws {
        let (store, id) = try seededStore()
        for (paid, at) in [(Int64(125_000), UInt64(6_000)), (Int64(130_000), UInt64(7_000))] {
            try store.reviseExpense(ExpenseRevisionIn(
                target: id, seen: [], editor: rahul,
                money: money(paid, [(me, paid / 2), (rahul, paid - paid / 2)]),
                note: nil, category: nil, deleted: nil
            ), nowMs: at)
        }
        let racing = try XCTUnwrap(store.projectedExpenses().expenses.first)
        XCTAssertEqual(racing.moneyConflict.map(\.paidMinor).sorted(), [125_000, 130_000])

        let keep = try XCTUnwrap(racing.moneyConflict.first { $0.paidMinor == 125_000 })
        try store.reviseExpense(ExpenseEditing.keep(keep, of: racing, editor: me), nowMs: 8_000)
        let settled = try XCTUnwrap(store.projectedExpenses().expenses.first)
        XCTAssertEqual(settled.moneyConflict, [], "the kept amount saw both edits")
        XCTAssertEqual(settled.expense.paidMinor, 125_000)
        XCTAssertEqual(try store.loadExpenses().map(\.paidMinor), [125_000])
    }

    func testDeleteThenUndoThroughTheHelpersRestoresTheExpense() throws {
        let (store, _) = try seededStore()
        let dinner = try XCTUnwrap(store.projectedExpenses().expenses.first)
        try store.reviseExpense(ExpenseEditing.delete(dinner, editor: me), nowMs: 6_000)
        XCTAssertEqual(try store.loadExpenses(), [])
        let gone = try XCTUnwrap(store.projectedExpenses().expenses.first)
        XCTAssertTrue(gone.deleted)
        try store.reviseExpense(ExpenseEditing.undo(gone, editor: me), nowMs: 7_000)
        XCTAssertEqual(try store.loadExpenses().map(\.note), ["Dinner"])
    }

    func testTheListCountsInTheRightNumber() {
        XCTAssertEqual(ExpenseListView.countLabel(1), "1 expense")
        XCTAssertEqual(ExpenseListView.countLabel(3), "3 expenses")
    }
}
