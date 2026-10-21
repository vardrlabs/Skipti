import XCTest
@testable import Skipti

/// D-166 (SPEC §9.5): the import flow's steps against the real core and a
/// throwaway vault. Android's twin is ImportFlowTest.
final class ImportFlowTests: XCTestCase {
    private let splitwise = ImportProofScreen.splitwiseSheet()
    private let wallet = ImportProofScreen.budgetBakersSheet()

    private func tempStore() throws -> LedgerStore {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("import-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return LedgerStore(path: dir.appendingPathComponent("v.db").path)
    }

    func testTheFileIsReadAsTextWithoutItsMarkAndWithinALimit() throws {
        XCTAssertEqual(try ImportSetup.text(from: Data("\u{FEFF}a,b\r\n".utf8)), "a,b\r\n")
        XCTAssertEqual(try ImportSetup.text(from: Data("a,b".utf8)), "a,b")
        XCTAssertThrowsError(try ImportSetup.text(from: Data([0xFF, 0xFE, 0xFD]))) {
            XCTAssertEqual($0 as? ImportSetup.ReadError, .notText)
        }
        XCTAssertThrowsError(try ImportSetup.text(from: Data(count: ImportSetup.maxBytes + 1))) {
            XCTAssertEqual($0 as? ImportSetup.ReadError, .tooLarge)
        }
    }

    func testTheSheetsPeopleComeFromCoresReadingOfTheHeader() throws {
        XCTAssertEqual(try ImportSetup.people(csv: splitwise), ["Alice", "Bob"])
        // A wallet statement is not a Splitwise group sheet.
        XCTAssertThrowsError(try ImportSetup.people(csv: wallet)) { error in
            guard case PortError.SheetStructure = error else { return XCTFail("\(error)") }
        }
    }

    func testTheGuessesAreOnlyGuesses() {
        XCTAssertEqual(ImportSetup.guessYou(people: ["Alice", "Bob"], yourName: "bob"), "Bob")
        XCTAssertEqual(ImportSetup.guessYou(people: ["Alice", "Bob"], yourName: " ALICE "), "Alice")
        XCTAssertNil(ImportSetup.guessYou(people: ["Alice", "Bob"], yourName: nil))
        XCTAssertNil(ImportSetup.guessYou(people: ["Alice", "Bob"], yourName: "Carol"))

        XCTAssertEqual(ImportSetup.groupName(fromFileName: "goa_trip-2026.csv", source: .splitwise), "Goa trip")
        XCTAssertEqual(
            ImportSetup.groupName(fromFileName: "tahoe_trip_2026-09-21_export.csv", source: .splitwise), "Tahoe trip"
        )
        XCTAssertEqual(ImportSetup.groupName(fromFileName: "Export 2.csv", source: .budgetBakers), "Wallet")
        XCTAssertEqual(ImportSetup.groupName(fromFileName: "___.csv", source: .splitwise), "Splitwise import")
        XCTAssertEqual(ImportSetup.groupName(fromFileName: "-.csv", source: .budgetBakers), "Wallet")

        XCTAssertEqual(ImportSetup.mostCommon(["INR", "USD", "USD"]), "USD")
        XCTAssertEqual(ImportSetup.mostCommon(["EUR", "USD"]), "EUR", "a tie goes to the first seen")
        XCTAssertNil(ImportSetup.mostCommon([]))
        // The sheet's two accepted rows are USD dinners (its INR row is refused).
        XCTAssertEqual(
            ImportSetup.likelyCurrency(csv: splitwise, source: .splitwise, people: ["Alice", "Bob"], fallback: "CHF"),
            "USD"
        )
        XCTAssertEqual(
            ImportSetup.likelyCurrency(csv: "not,a\n1,2\n", source: .splitwise, people: [], fallback: "CHF"),
            "CHF"
        )
        XCTAssertEqual(ImportSetup.likelyCurrency(csv: wallet, source: .budgetBakers, people: [], fallback: "CHF"), "INR")
    }

    /// Goldens computed in Python from the calendar, not from the code.
    func testAFilesDateKeepsItsCalendarDayOnThisPhone() {
        let ny = TimeZone(identifier: "America/New_York")!
        let kolkata = TimeZone(identifier: "Asia/Kolkata")!
        let midnight: UInt64 = 1_789_862_400_000 // 2026-09-20T00:00Z, a Splitwise date
        XCTAssertEqual(ImportSetup.localWallTime(midnight, in: ny), 1_789_920_000_000, "noon, 20 Sep, New York")
        XCTAssertEqual(ImportSetup.localWallTime(midnight, in: kolkata), 1_789_885_800_000, "noon, 20 Sep, Kolkata")
        let wall: UInt64 = 1_768_483_800_000 // 2026-01-15 13:30, a wallet row
        XCTAssertEqual(ImportSetup.localWallTime(wall, in: ny), 1_768_501_800_000, "13:30 in New York (EST)")
        XCTAssertEqual(ImportSetup.localWallTime(wall, in: TimeZone(identifier: "UTC")!), wall)
        // The review shows the file's own date wherever the phone is.
        XCTAssertTrue(ImportReviewModel.dateString(midnight, locale: .enUs).contains("20"))
        XCTAssertFalse(ImportReviewModel.dateString(midnight, locale: .enUs).contains("19"))
    }

    func testARefusalNamesPeopleNotIds() {
        let a = String(repeating: "a", count: 32), b = String(repeating: "b", count: 32)
        XCTAssertEqual(
            ImportReviewModel.naming("2 payers recorded (\(a), \(b))", [a: "Tim", b: "Asha"]),
            "2 payers recorded (Tim, Asha)"
        )
        XCTAssertEqual(ImportReviewModel.naming("blank cost", [a: "Tim"]), "blank cost")
    }

    func testASplitwiseImportWritesNothingUntilCommitThenAllOfIt() throws {
        let store = try tempStore()
        let job = try ImportJob.prepare(
            store: store, csv: splitwise, source: .splitwise, groupName: "Tahoe",
            currency: "USD", people: ["Alice", "Bob"], you: "Alice", typedName: ""
        )
        // Planned, checked by core, and still nothing saved.
        XCTAssertEqual(job.plan.expenses.count, 2)
        // The plan was checked against the ledger plus the unsaved group. (Core
        // checks only a group id's shape today: BUILD-LOG §4, D-166.)
        XCTAssertTrue(job.existing.groups.contains { $0.id == job.group.group.id })
        _ = try ffiApplyImportPlan(existing: job.existing, plan: job.plan)
        XCTAssertTrue(try store.loadGroups().isEmpty)
        XCTAssertTrue(try store.loadExpenses().isEmpty)
        XCTAssertEqual(job.newName, "Alice", "the chosen column becomes your name when you have none")

        let before = try store.allRecords().count
        try job.commit(job.plan.expenses, store: store)

        let groups = try store.namedGroups()
        XCTAssertEqual(groups.map(\.group.name), ["Tahoe"])
        let group = try XCTUnwrap(groups.first)
        XCTAssertEqual(Set(group.members.map(\.name)), ["Alice", "Bob"])
        XCTAssertEqual(try store.ensureSelf().name, "Alice")
        XCTAssertEqual(try store.currentGroup()?.group.id, group.group.id)
        let expenses = try store.loadExpenses()
        XCTAssertEqual(expenses.count, 2)
        // Saved in this phone's time zone on the file's calendar day.
        let planned = job.plan.expenses.compactMap(\.timestampMs).map {
            AppDate.day($0, locale: .enUs, timeZone: TimeZone(identifier: "UTC")!)
        }
        XCTAssertEqual(expenses.compactMap(\.timestampMs).map { AppDate.day($0, locale: .enUs) }, planned)
        for expense in expenses {
            XCTAssertEqual(expense.group, group.group.id)
            XCTAssertTrue(group.group.members.contains(expense.payer))
            XCTAssertEqual(expense.addedBy, group.selfId)
        }
        // Each expense is also its own sync record. Counted by type: the group
        // and your name write records too, and a rebuild's one-time legacy
        // backfill would re-mint missing ones (sabotage run 1 found both).
        let expenseRecords = try store.allRecords().filter { $0.recordType == LedgerStore.Sync.expenseType }
        XCTAssertEqual(expenseRecords.count, 2)
        XCTAssertGreaterThan(try store.allRecords().count, before)
        try store.rebuildProjection()
        XCTAssertEqual(try store.loadExpenses().count, 2)
    }

    func testAWalletImportIsYoursAloneAndNeedsAName() throws {
        let store = try tempStore()
        XCTAssertThrowsError(try ImportJob.prepare(
            store: store, csv: wallet, source: .budgetBakers, groupName: "Wallet",
            currency: "INR", people: [], you: nil, typedName: "  "
        )) { XCTAssertEqual($0 as? LedgerStore.GroupStoreError, .needsYourName) }

        let job = try ImportJob.prepare(
            store: store, csv: wallet, source: .budgetBakers, groupName: "Wallet",
            currency: "INR", people: [], you: nil, typedName: "Tim"
        )
        XCTAssertEqual(job.plan.expenses.map(\.paidMinor), [254_750], "the one expense row; income and garbage refused")
        try job.commit(job.plan.expenses, store: store)
        let group = try XCTUnwrap(store.namedGroups().first)
        XCTAssertEqual(group.members.map(\.name), ["Tim"])
        XCTAssertEqual(try store.loadExpenses().map(\.payer), [group.selfId])
    }
}
