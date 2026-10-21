import XCTest
@testable import Skipti

/// §9.5's confirm UI, at the model level: ONE `ImportReviewModel` type
/// consumes plans from BOTH profiles, duplicates stay flagged-and-included,
/// refusals keep their spreadsheet lines, and apply hands the INCLUDED
/// subset to the atomic FFI merge. Mirrors ImportProofScreen's checks as
/// hard assertions.
final class ImportReviewTests: XCTestCase {
    private let ada = String(repeating: "a", count: 31) + "1"
    private let bob = String(repeating: "b", count: 31) + "2"

    private func makeSplitwisePlan(
        file: StaticString = #filePath, line: UInt = #line
    ) throws -> ImportPlan {
        let sheet = [
            "\u{FEFF}Date,Description,Category,Cost,Currency,Alice,Bob\r\n",
            "2026-01-15,Dinner, ,45.50,USD,45.50,-45.50\r\n",
            "2026-01-16,Tuk tuk,Transport,\"1,234.50\",INR,411.50,-411.50\r\n",
            "2026-01-15,Dinner, ,45.50,USD,45.50,-45.50\r\n",
            "Total balance,,,,USD,91.00,-91.00\r\n",
        ].joined()
        let options = SplitwiseOptions(
            dateFormat: .isoYmd,
            members: [
                MemberBinding(name: "Alice", participantId: ada),
                MemberBinding(name: "Bob", participantId: bob),
            ],
            group: nil
        )
        return try ffiPlanImport(
            csvText: sheet,
            profile: .splitwiseGroup(options: options),
            existing: LedgerSnapshot(groups: [], expenses: [], settlements: [], redistributions: [], profile: nil)
        )
    }

    private func makeBudgetBakersPlan() throws -> ImportPlan {
        let sheet = [
            "\u{FEFF}Account,Category,Currency,Amount,Account Currency,Income/Expense,"
                + "Refund,Refund Currency,Note,Date\r\n",
            "Checking,Salary,INR,500000,,Income,,,Monthly pay,2026-01-05 09:00:00\r\n",
            "Cash,Groceries,INR,-2547.50,,Expense,,,Corner shop,2026-01-15 13:30:00\r\n",
            "Cash,,XXX,10,,Garbage,,,?,2026-01-03 00:00:00\r\n",
        ].joined()
        let options = BudgetBakersOptions(
            selfParticipant: ada,
            group: nil
        )
        return try ffiPlanImport(
            csvText: sheet,
            profile: .budgetBakersWallet(options: options),
            existing: LedgerSnapshot(groups: [], expenses: [], settlements: [], redistributions: [], profile: nil)
        )
    }

    /// The design-smell probe from the founder's instruction: this test
    /// compiles only because NO profile parameter exists anywhere in the
    /// review path. The same model type consumes both plans and neither
    /// model can tell them apart: plan data only.
    func testOneModelTypeConsumesBothProfiles() throws {
        let swModel = ImportReviewModel(
            plan: try makeSplitwisePlan(), locale: AppLocale.current
        )
        let bbModel = ImportReviewModel(
            plan: try makeBudgetBakersPlan(), locale: AppLocale.current
        )
        XCTAssertEqual(swModel.proposals.count, 2)
        XCTAssertEqual(bbModel.proposals.count, 1)
        // Neither model exposes any provenance, asserted structurally by
        // consuming them identically below:
        for model in [swModel, bbModel] {
            XCTAssertTrue(model.totalsText.allSatisfy { !$0.isEmpty })
            XCTAssertTrue(model.rejections.allSatisfy { $0.lineNumber >= 2 })
        }
    }

    func testDuplicateIsFlaggedButStaysIncluded() throws {
        let model = ImportReviewModel(
            plan: try makeSplitwisePlan(), locale: AppLocale.current
        )

        XCTAssertEqual(model.duplicateFlaggedCount, 1)
        let flagged = model.proposals.first { $0.duplicateOf != nil }
        XCTAssertEqual(flagged?.included, true, "a flag never removes or deselects")
        guard case .earlierInFile(let origin)? = flagged?.duplicateOf else {
            return XCTFail("expected an in-file duplicate hit, got \(String(describing: flagged?.duplicateOf))")
        }
        XCTAssertEqual(origin, 0, "flags against the FIRST copy of the file")

        // And it is listed in REFUSED ROWS with spreadsheet line numbers
        // (header = line 1, so data begins at line 2):
        XCTAssertEqual(model.rejections.map(\.lineNumber), [3, 5])
        XCTAssertTrue(model.rejections[0].reason.contains("digits and one dot"))
    }

    func testExcludingAFlaggedRowFiltersItFromApplyOnly() throws {
        let model = ImportReviewModel(
            plan: try makeSplitwisePlan(), locale: AppLocale.current
        )
        model.setIncluded(false, forID: 1)
        XCTAssertEqual(model.includedCount, 1)

        model.apply(to: LedgerSnapshot(groups: [], expenses: [], settlements: [], redistributions: [], profile: nil))
        guard case .done(let count) = model.appliedState else {
            return XCTFail("apply should succeed: \(String(describing: model.appliedState))")
        }
        XCTAssertEqual(count, 1, "n−1 proposals create n−1 expenses")
    }

    func testApplyRefusalIsTypedAndAtomic() {
        let ada = self.ada, bob = self.bob
        var broken = ImportPlan(
            expenses: [], duplicates: [], rejections: [], totalsByCurrency: []
        )
        broken.expenses = [
            PortExpense(
                payer: ada, paidMinor: 500, currencyCode: "USD",
                group: nil, conversion: nil,
                shares: [ExpenseShare(id: ada, amountMinor: 499)],
                note: "under-funded share", timestampMs: 20_468 * 86_400_000,
                category: nil
            )
        ]
        let model = ImportReviewModel(plan: broken, locale: AppLocale.current)
        let empty = LedgerSnapshot(groups: [], expenses: [], settlements: [], redistributions: [], profile: nil)
        model.apply(to: empty)

        guard case .failed(let message) = model.appliedState else {
            return XCTFail("expected refusal, got \(String(describing: model.appliedState))")
        }
        XCTAssertTrue(message.contains("invalid"), "\(message)")
        // Atomicity is engine-side; from here the honest assertion is that
        // NOTHING came back: no merged snapshot exists to commit.
    }

    func testEmptySelectionRefusesToApply() throws {
        let model = ImportReviewModel(
            plan: try makeSplitwisePlan(), locale: AppLocale.current
        )
        for row in model.proposals { model.setIncluded(false, forID: row.id) }
        model.apply(to: LedgerSnapshot(groups: [], expenses: [], settlements: [], redistributions: [], profile: nil))
        XCTAssertEqual(model.appliedState, .failed(message: "Nothing selected to import."))
    }

    func testBudgetBakersTotalsRenderInReviewModel() throws {
        let model = ImportReviewModel(
            plan: try makeBudgetBakersPlan(), locale: AppLocale.current
        )
        XCTAssertEqual(model.totalsText.count, 1, "one currency on this statement")
        XCTAssertTrue(model.rejections.contains { $0.reason.contains("income record") })
    }

    /// D-228: a flagged row reads "Duplicate: <label>", shown and spoken, on both shells (the twin of Android's
    /// duplicate_kicker_reads_duplicate_colon, which reads this file). A source guard: the words live inline in the
    /// view, and the em dash guard cannot see "Duplicate: x" turn into "Duplicate x".
    func testTheDuplicateKickerReadsDuplicateColon() throws {
        let repo = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent()
        func source(_ path: String) throws -> String {
            try String(contentsOf: repo.appendingPathComponent(path), encoding: .utf8)
        }
        let review = try source("ios/Skipti/App/ImportReview.swift")
        XCTAssertTrue(review.contains(#"Text("Duplicate: \(d.label)")"#), "the row shows the kicker")
        XCTAssertTrue(review.contains(#"". Duplicate: \($0.label)""#), "the row speaks it")
        let android = "android/app/src/main/java/com/vardrlabs/skipti/"
        let flow = try source(android + "ImportFlow.kt")
        XCTAssertTrue(flow.contains(#"Type.kicker("Duplicate: ${it.label}")"#), "Android's ImportFlow shows the same words")
        XCTAssertTrue(flow.contains(#"". Duplicate: ${it.label}""#), "Android's ImportFlow speaks them")
        let proof = try source(android + "ImportScreen.kt")
        XCTAssertTrue(proof.contains(#"Type.kicker("Duplicate: ${d.label}")"#), "Android's ImportScreen shows them")
    }
}
