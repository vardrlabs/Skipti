import XCTest
@testable import Skipti

/// The §9 portage surface over the real FFI: a populated ledger (group,
/// settlement, converted expense with its frozen FX rate) exports to the
/// versioned JSON envelope and re-imports byte-identically; a Splitwise
/// sheet plans as a dry run with duplicates flagged and hostile rows
/// rejected by name. Mirrors core/tests/ledger_port.rs through the boundary.
final class LedgerPortTests: XCTestCase {
    private let ada = String(repeating: "a", count: 31) + "1"
    /// §8.1 category ids for the fixture: the Villa carries Groceries, the
    /// JPY snack stays uncategorised: both shapes exercised on re-open.
    private let villaCategory = "11111111111111111111111111111111"
    private let jpyCategory: String? = nil
    private let bob = String(repeating: "b", count: 31) + "2"
    private let carol = String(repeating: "c", count: 31) + "3"
    private let goa = String(repeating: "d", count: 31) + "4"

    private func populatedSnapshot() -> LedgerSnapshot {
        let villa = PortExpense(
            payer: ada,
            paidMinor: 420_000,
            currencyCode: "INR",
            group: goa,
            conversion: PortConversion(
                convertedMinor: 5_000,
                convertedCurrency: "USD",
                rateNum: 1,
                rateDen: 84,
                rateTimestampMs: 1_768_171_200_000
            ),
            shares: [
                ExpenseShare(id: ada, amountMinor: 140_000),
                ExpenseShare(id: bob, amountMinor: 140_000),
                ExpenseShare(id: carol, amountMinor: 140_000),
            ],
            note: "Villa, first \"half\"",
            timestampMs: 20_459 * 86_400_000,
            category: villaCategory
        )
        let jpy = PortExpense(
            payer: bob,
            paidMinor: 3_800,
            currencyCode: "JPY",
            group: goa,
            conversion: nil,
            shares: [
                ExpenseShare(id: ada, amountMinor: 1_900),
                ExpenseShare(id: bob, amountMinor: 1_900),
            ],
            note: nil,
            timestampMs: 20_460 * 86_400_000 + 7_200_000,
            category: jpyCategory
        )
        return LedgerSnapshot(
            groups: [PortGroup(
                id: goa,
                name: "Goa Trip",
                settlementCurrency: "INR",
                members: [ada, bob, carol]
            )],
            expenses: [villa, jpy],
            settlements: [PortSettlement(
                from: bob, to: ada, amountMinor: 150_000,
                currencyCode: "INR", group: goa
            )],
            redistributions: [],
            profile: nil
        )
    }

    private func bindings() -> [MemberBinding] {
        [
            MemberBinding(name: "Alice", participantId: ada),
            MemberBinding(name: "Bob", participantId: bob),
        ]
    }

    private let header =
        "\u{FEFF}Date,Description,Category,Cost,Currency,Alice,Bob\r\n"

    func testExportImportRoundTripIsByteIdentical() throws {
        let snapshot = populatedSnapshot()
        let json1 = try ffiExportLedgerJson(snapshot: snapshot)
        XCTAssertTrue(json1.hasPrefix("{\"format\":\"skipti-ledger\",\"schema_version\":4,"))

        let restored = try ffiImportLedgerJson(text: json1)
        XCTAssertEqual(restored, snapshot)

        let json2 = try ffiExportLedgerJson(snapshot: restored)
        XCTAssertEqual(json1, json2)
    }

    func testNewerSchemaRefusesAsAVersionError() throws {
        let json = try ffiExportLedgerJson(snapshot: populatedSnapshot())
        let future = json.replacingOccurrences(of: "\"schema_version\":4", with: "\"schema_version\":9")
        XCTAssertThrowsError(try ffiImportLedgerJson(text: future)) { error in
            guard case let PortError.UnsupportedSchemaVersion(found, supported) = error else {
                return XCTFail("wrong error: \(error)")
            }
            XCTAssertEqual(found, 9)
            XCTAssertEqual(supported, 4)
        }
    }

    func testSplitwiseDryRunPlansDuplicatesFlagsAndRejectsHostileRows() throws {
        // BOM + CRLF like a real export; row 3 carries a quoted grouping
        // separator; row 4 has no payer at all; row 5 is the summary row.
        let csv = header +
            "2026-01-15,\"Dinner, seaside\",Food,45.50,USD,22.75,-22.75\r\n" +
            "2026-01-16,Tuk tuk,Transport,\"1,234.50\",INR,411.50,-411.50\r\n" +
            "2026-01-17,No payer,,30.00,USD,,\r\n" +
            "Total balance,,,,USD,22.75,-22.75\r\n"

        let options = SplitwiseOptions(
            dateFormat: .isoYmd,
            members: bindings(),
            group: goa
        )
        let plan = try ffiPlanImport(
            csvText: csv,
            profile: .splitwiseGroup(options: options),
            existing: populatedSnapshot()
        )

        // Exactly one faithful proposal. Alice paid everything and her cell
        // (-22.75) encodes net-paid, so she still owes half: owes are
        // reconstructed as payer_owe = cost − |payer cell|.
        XCTAssertEqual(plan.expenses.count, 1)
        let e = try XCTUnwrap(plan.expenses.first)
        XCTAssertEqual(e.paidMinor, 4_550)
        XCTAssertEqual(e.payer, ada)
        XCTAssertEqual(e.shares.map(\.amountMinor), [2_275, 2_275])
        XCTAssertEqual(e.shares.first?.id, ada)
        XCTAssertEqual(e.note, "Dinner, seaside (Food)")
        XCTAssertEqual(e.timestampMs, 20_468 * 86_400_000)

        // Hostile rows named, spreadsheet rows preserved:
        XCTAssertEqual(plan.rejections.count, 3)
        XCTAssertTrue(plan.rejections[0].reason.contains("digits and one dot"))
        XCTAssertEqual(plan.rejections[0].rowNumber, 3)
        XCTAssertTrue(plan.rejections[1].reason.contains("no payer"))
        XCTAssertTrue(plan.rejections[2].reason.contains("summary row"))

        // Reconciliation total for the accepted set:
        XCTAssertEqual(plan.totalsByCurrency.first?.costMinor, 4_550)
        // Nothing duplicates yet (different day/amount than the fixture):
        XCTAssertTrue(plan.duplicates.isEmpty)

        // Applying is all-or-nothing and canonical:
        let merged = try ffiApplyImportPlan(existing: populatedSnapshot(), plan: plan)
        XCTAssertEqual(merged.expenses.count, 3)
        let json = try ffiExportLedgerJson(snapshot: merged)
        XCTAssertEqual(try ffiImportLedgerJson(text: json), merged)
    }

    func testSplitwiseDuplicateIsFlaggedAgainstTheExistingLedger() throws {
        let csv = header + "2026-01-07,,,3800,JPY,1900,-1900\r\n"
        let options = SplitwiseOptions(dateFormat: .isoYmd, members: bindings(), group: nil)
        let plan = try ffiPlanImport(
            csvText: csv,
            profile: .splitwiseGroup(options: options),
            existing: populatedSnapshot()
        )
        XCTAssertEqual(plan.expenses.count, 1, "a flag never removes a proposal")
        XCTAssertEqual(plan.duplicates.count, 1)
        guard case let .existing(index)? = plan.duplicates.first?.against else {
            return XCTFail("expected an existing-ledger hit")
        }
        XCTAssertEqual(Int(index), 1, "the JPY expense of the fixture ledger")
    }

    // MARK: The second profile: Wallet by BudgetBakers (statement shape)

    private let walletHeader =
        "\u{FEFF}Account,Category,Currency,Amount,Account Currency,Income/Expense," +
        "Refund,Refund Currency,Note,Date\r\n"

    func testBudgetBakersStatementPlansThroughTheSameEngine() throws {
        // Row 2 is income (not representable), row 3 is the golden expense
        // with time-of-day kept, row 4 an unknown type. Same 10-column map
        // as core/tests/ledger_port.rs.
        let csv = walletHeader +
            "Checking,Salary,INR,500000,,Income,,,Monthly pay,2026-01-05 09:00:00\r\n" +
            "Cash,Groceries,INR,-2547.50,,Expense,,,Corner shop,2026-01-15 13:30:00\r\n" +
            "Cash,,XXX,10,,Garbage,,,?,2026-01-03 00:00:00\r\n"

        let options = BudgetBakersOptions(selfParticipant: ada, group: goa)
        let plan = try ffiPlanImport(
            csvText: csv,
            profile: .budgetBakersWallet(options: options),
            existing: populatedSnapshot()
        )

        // Income and the trap rejected by name; data rows begin at row 2:
        XCTAssertEqual(plan.rejections.count, 2)
        XCTAssertTrue(plan.rejections[0].reason.contains("income record"))
        XCTAssertEqual(plan.rejections[0].rowNumber, 2)
        XCTAssertTrue(plan.rejections[1].reason.contains("unknown record type"))

        // Exact paise, single share on the importing user, time preserved:
        XCTAssertEqual(plan.expenses.count, 1)
        let e = try XCTUnwrap(plan.expenses.first)
        XCTAssertEqual(e.paidMinor, 254_750)
        XCTAssertEqual(e.payer, ada)
        XCTAssertEqual(e.shares.map(\.amountMinor), [254_750])
        XCTAssertEqual(e.note, "Corner shop (Groceries)")
        XCTAssertEqual(e.timestampMs, 20_468 * 86_400_000 + 48_600_000)

        // Reconciliation total and the shared all-or-nothing apply:
        XCTAssertEqual(plan.totalsByCurrency.first?.costMinor, 254_750)
        let merged = try ffiApplyImportPlan(existing: populatedSnapshot(), plan: plan)
        XCTAssertEqual(merged.expenses.count, 3)
    }

    func testBudgetBakersDuplicateIsFlaggedAndSeparatorsStayRefusedAtTheBoundary() throws {
        // The fixture ledger already holds the 3800-JPY expense of
        // 2026-01-07 with NO note; the statement re-export must also carry
        // an empty note for amount+day+merchant to match:
        let csv = walletHeader + "Cash,,JPY,-3800,,Expense,,,,2026-01-07 21:14:00\r\n"
        let options = BudgetBakersOptions(selfParticipant: ada, group: nil)
        let plan = try ffiPlanImport(
            csvText: csv,
            profile: .budgetBakersWallet(options: options),
            existing: populatedSnapshot()
        )
        XCTAssertEqual(plan.expenses.count, 1, "a flag never removes a proposal")
        XCTAssertEqual(plan.duplicates.count, 1, "same amount+day+merchant as the fixture")

        // Ivy's importer strips thousands separators silently; SKIPTI's
        // refusal must survive the FFI boundary as a typed rejection:
        let sep = walletHeader + "A,,USD,\"1,234.00\",,Expense,,,,2026-01-08 08:00:00\r\n"
        let refused = try ffiPlanImport(
            csvText: sep,
            profile: .budgetBakersWallet(options: options),
            existing: populatedSnapshot()
        )
        XCTAssertTrue(refused.rejections[0].reason.contains("digits and one dot"))
    }

    func testFlatCsvViewRendersExactDecimals() throws {
        let csv = try ffiExportLedgerCsv(snapshot: populatedSnapshot())
        // The text opens with the header directly: a leading U+FEFF cannot
        // cross the UniFFI→Swift boundary at all (Foundation's
        // String(bytes:encoding:) consumes it as an encoding signature,
        // incident §5.15), so the BOM is prepended by the shell when the
        // file is written, never carried inside the string.
        XCTAssertTrue(csv.hasPrefix("record_type,date_utc"))
        XCTAssertTrue(csv.contains(",1500.00,"), "settlement in exact paise")
        XCTAssertTrue(csv.contains(",3800,"), "JPY renders without any decimal point")
    }
}
