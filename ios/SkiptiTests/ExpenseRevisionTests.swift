import XCTest
@testable import Skipti

/// Editing and deleting expenses on iOS (D-141; SPEC §5.7, §6.1), against a
/// real vault through core's one projection: an added expense gets a stable
/// id and "added by"; an edit is a new record that changes the ledger and says
/// who edited it; a delete leaves the ledger and Undo brings it back; and,
/// the trap this unit found, the legacy backfill never re-mints an edited row
/// as a duplicate expense. Android's twin is ExpenseRevisionTest.
final class ExpenseRevisionTests: XCTestCase {
    private var dir: URL!
    private let me = String(repeating: "0", count: 31) + "1"
    private let rahul = String(repeating: "0", count: 31) + "2"
    private let group = String(repeating: "0", count: 31) + "9"

    override func setUpWithError() throws {
        dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("revisions-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: dir)
    }

    private func store() -> LedgerStore {
        let s = LedgerStore(path: dir.appendingPathComponent("ledger.db").path)
        // Decision 208: the vault holds the group its expenses are written into.
        try! HeldLedgers.group(s, id: group, members: [me, rahul])
        return s
    }

    /// The expense and revision records (decision 208: the held group's own backfilled record is not counted).
    private func moneyRecords(_ s: LedgerStore) throws -> Int {
        try s.allRecords().filter { $0.recordType == LedgerStore.Sync.expenseType || $0.recordType == LedgerStore.Sync.revisionType }.count
    }

    private func expense(_ amount: Int64, note: String) -> LedgerStore.ExpenseDTO {
        LedgerStore.ExpenseDTO(
            payer: me, paidMinor: amount, currencyCode: "INR", group: group, conversion: nil,
            shares: [
                .init(id: me, amountMinor: amount / 2),
                .init(id: rahul, amountMinor: amount - amount / 2),
            ],
            note: note, timestampMs: 1_000, category: nil, addedBy: me
        )
    }

    private func edit(
        _ id: String, seen: [String] = [], money: ExpenseInput? = nil,
        note: String? = nil, deleted: Bool? = nil
    ) -> ExpenseRevisionIn {
        ExpenseRevisionIn(
            target: id, seen: seen, editor: rahul, money: money, note: note,
            category: nil, deleted: deleted
        )
    }

    private func onlyExpense(_ store: LedgerStore) throws -> ProjectedExpenseOut {
        let all = try store.projectedExpenses().expenses
        XCTAssertEqual(all.count, 1)
        return try XCTUnwrap(all.first)
    }

    func testAnAddedExpenseHasAStableIdAndWhoAddedIt() throws {
        let s = store()
        _ = try s.appendExpense(expense(900, note: "dinner"), nowMs: 5_000)
        let e = try onlyExpense(s)
        let record = try XCTUnwrap(try s.allRecords().first)
        XCTAssertEqual(e.id, LedgerStore.Sync.recordIdentity(record))
        XCTAssertEqual(e.addedBy, me)
        XCTAssertNil(e.lastEditedAtMs)
        XCTAssertEqual(try s.loadExpenses(), [expense(900, note: "dinner")],
                       "the kv ledger reads exactly as it did before D-141")
    }

    func testANoteEditChangesTheLedgerAndSaysWhoEditedIt() throws {
        let s = store()
        _ = try s.appendExpense(expense(900, note: "dinner"), nowMs: 5_000)
        let id = try onlyExpense(s).id
        try s.reviseExpense(edit(id, note: "taxi"), nowMs: 6_000)
        XCTAssertEqual(try s.loadExpenses().map(\.note), ["taxi"])
        let e = try onlyExpense(s)
        XCTAssertEqual(e.lastEditedBy, rahul)
        XCTAssertEqual(e.lastEditedAtMs, 6_000)
        XCTAssertEqual(e.seen.count, 1)
        XCTAssertEqual(try moneyRecords(s), 2, "the edit is a new record; the original stays")
    }

    func testAMoneyEditMovesTheAmountAndTheShares() throws {
        let s = store()
        _ = try s.appendExpense(expense(900, note: "dinner"), nowMs: 5_000)
        let id = try onlyExpense(s).id
        try s.reviseExpense(edit(id, money: expense(1200, note: "").asInput), nowMs: 6_000)
        let row = try XCTUnwrap(try s.loadExpenses().first)
        XCTAssertEqual(row.paidMinor, 1200)
        XCTAssertEqual(row.shares.map(\.amountMinor), [600, 600])
        XCTAssertEqual(row.group, group, "an edit never moves an expense between groups")
        XCTAssertEqual(row.note, "dinner", "only the money changed")
    }

    func testDeleteLeavesTheLedgerAndUndoBringsItBack() throws {
        let s = store()
        _ = try s.appendExpense(expense(900, note: "dinner"), nowMs: 5_000)
        let id = try onlyExpense(s).id
        try s.reviseExpense(edit(id, deleted: true), nowMs: 6_000)
        XCTAssertEqual(try s.loadExpenses(), [], "a deleted expense leaves balances and the list")
        let deleted = try onlyExpense(s)
        XCTAssertTrue(deleted.deleted, "but it is still in the projection, recoverable")
        try s.reviseExpense(edit(id, seen: deleted.seen, deleted: false), nowMs: 7_000)
        XCTAssertEqual(try s.loadExpenses(), [expense(900, note: "dinner")])
    }

    /// The trap D-141 found: after an edit the kv row no longer equals its
    /// creation record, and the D-121 backfill (which mints records for kv
    /// rows it cannot find) would have minted the edited row as a brand-new
    /// expense on the next pull. It now runs once per vault.
    func testTheBackfillNeverReMintsAnEditedExpense() throws {
        let s = store()
        _ = try s.appendExpense(expense(900, note: "dinner"), nowMs: 5_000)
        let id = try onlyExpense(s).id
        try s.reviseExpense(edit(id, note: "taxi"), nowMs: 6_000)
        XCTAssertEqual(try LedgerStore.Sync.backfillLegacyRecords(s), 0)
        XCTAssertEqual(try moneyRecords(s), 2)
        try s.rebuildProjection()
        XCTAssertEqual(try s.loadExpenses().map(\.note), ["taxi"], "still ONE expense")
    }

    func testRevisingAnExpenseThisVaultDoesNotHoldIsRefused() throws {
        let s = store()
        _ = try s.appendExpense(expense(900, note: "dinner"), nowMs: 5_000)
        let stranger = "1:0:" + String(repeating: "0", count: 31) + "7"
        XCTAssertThrowsError(try s.reviseExpense(edit(stranger, note: "x"))) { error in
            guard case LedgerStore.LoadError.unknownExpense = error else {
                return XCTFail("expected unknownExpense, got \(error)")
            }
        }
        XCTAssertEqual(try moneyRecords(s), 1, "nothing was written")
    }
}
