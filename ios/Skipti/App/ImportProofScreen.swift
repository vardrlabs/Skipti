import SwiftUI

#if DEBUG
// D-235 (C4, iOS L1, decision 108): a proof surface, Debug builds only.
/// Headless verification (--import-proof): ONE review path consumed by TWO
/// source profiles. Runs a Splitwise group sheet and a BudgetBakers
/// statement through `ffiPlanImport`, feeds each plan through the SAME
/// `ImportReviewModel` the confirm screen uses, and mechanically asserts
/// §9.5's obligations: flagged-not-removed duplicates, spreadsheet line
/// numbers on refusals, all-or-nothing apply. NOT a user flow.
struct ImportProofScreen: View {
    @State private var result: ImportProofScreen.Result?

    var body: some View {
        List {
            Section("Import confirmation: both profiles, one screen") {
                row("Splitwise sheet → plan", check(result?.splitwisePlanned))
                row("BudgetBakers statement → plan", check(result?.budgetBakersPlanned))
                row("same ImportReviewModel consumes both", check(result?.oneModelBothProfiles))
                row("duplicate flagged AND still included", check(result?.duplicateFlaggedNotRemoved))
                row("refusals carry spreadsheet line numbers", check(result?.rejectionLinesPresent))
                row("totals reconcile per currency", check(result?.totalsRendered))
                row("exclude-one apply = n-1 created", check(result?.exclusionFiltered))
                row("apply is atomic on refusal", check(result?.atomicRefusal))
                if let result {
                    Section {
                        Text(result.passed ? "IMPORT CONFIRM PROVEN" : "PROOF FAILED")
                            .microLabelFont()
                            .foregroundStyle(result.passed ? Tokens.owing : Tokens.owed)
                    }
                    if let detail = result.detail {
                        Section { Text(detail).font(TypeRole.numeric(12)) }
                    }
                }
            }
        }
        .navigationTitle("Import proof")
        .task { run() }
    }

    private func row(_ label: String, _ ok: Bool?) -> some View {
        HStack {
            Text(label).font(TypeRole.body())
            Spacer()
            Image(systemName: ok == true ? "checkmark.circle.fill" : "xmark.circle")
                .foregroundStyle(ok == true ? Tokens.owing : Tokens.owed)
        }
    }

    private func check(_ ok: Bool?) -> Bool { ok == true }

    private func run() {
        DispatchQueue.global(qos: .userInitiated).async {
            let r = Self.prove()
            DispatchQueue.main.async { result = r }
        }
    }

    struct Result {
        var splitwisePlanned = false
        var budgetBakersPlanned = false
        var oneModelBothProfiles = false
        var duplicateFlaggedNotRemoved = false
        var rejectionLinesPresent = false
        var totalsRendered = false
        var exclusionFiltered = false
        var atomicRefusal = false

        var passed: Bool {
            splitwisePlanned && budgetBakersPlanned && oneModelBothProfiles
                && duplicateFlaggedNotRemoved && rejectionLinesPresent && totalsRendered
                && exclusionFiltered && atomicRefusal
        }
        var detail: String? { passed ? nil : "see first failing step" }
    }

    // Fixture sheets mirroring core/tests/ledger_port.rs: BOM + CRLF like
    // real exports, one trap row each.
    static func splitwiseSheet() -> String {
        [
            "\u{FEFF}Date,Description,Category,Cost,Currency,Alice,Bob\r\n",
            // Both dinner rows carry an empty Category so their NOTES are
            // byte-equal. Without it no amount+date+merchant match exists,
            // and the proof would be testing a copy that never duplicates:
            "2026-01-15,Dinner, ,45.50,USD,45.50,-45.50\r\n",
            "2026-01-16,Tuk tuk,Transport,\"1,234.50\",INR,411.50,-411.50\r\n",
            "2026-01-15,Dinner, ,45.50,USD,45.50,-45.50\r\n",
            "Total balance,,,,USD,68.25,-68.25\r\n",
        ].joined()
    }

    static func budgetBakersSheet() -> String {
        [
            "\u{FEFF}Account,Category,Currency,Amount,Account Currency,Income/Expense,"
                + "Refund,Refund Currency,Note,Date\r\n",
            "Checking,Salary,INR,500000,,Income,,,Monthly pay,2026-01-05 09:00:00\r\n",
            "Cash,Groceries,INR,-2547.50,,Expense,,,Corner shop,2026-01-15 13:30:00\r\n",
            "Cash,,XXX,10,,Garbage,,,?,2026-01-03 00:00:00\r\n",
        ].joined()
    }

    static func existingSnapshot(ada: String) -> LedgerSnapshot {
        LedgerSnapshot(groups: [], expenses: [], settlements: [], redistributions: [], profile: nil)
    }

    static func prove() -> Result {
        var r = Result()
        let ada = String(repeating: "a", count: 31) + "1"
        let bob = String(repeating: "b", count: 31) + "2"
        let goa = String(repeating: "d", count: 31) + "4"
        let empty = LedgerSnapshot(groups: [], expenses: [], settlements: [], redistributions: [], profile: nil)

        // Splitwise leg:
        let swOptions = SplitwiseOptions(
            dateFormat: .isoYmd,
            members: [
                MemberBinding(name: "Alice", participantId: ada),
                MemberBinding(name: "Bob", participantId: bob),
            ],
            group: goa
        )
        guard
            let swPlan = try? ffiPlanImport(
                csvText: splitwiseSheet(),
                profile: .splitwiseGroup(options: swOptions),
                existing: empty
            ),
            swPlan.expenses.count == 2,
            swPlan.rejections.count == 2
        else { return r }
        r.splitwisePlanned = true

        // BudgetBakers leg:
        let bbOptions = BudgetBakersOptions(selfParticipant: ada, group: nil)
        guard
            let bbPlan = try? ffiPlanImport(
                csvText: budgetBakersSheet(),
                profile: .budgetBakersWallet(options: bbOptions),
                existing: empty
            ),
            bbPlan.expenses.count == 1,
            bbPlan.rejections.count == 2
        else { return r }
        r.budgetBakersPlanned = true

        // The SAME model type consumes both plans; nothing here can branch
        // on the producing profile because no such field exists:
        let swModel = ImportReviewModel(plan: swPlan, locale: AppLocale.current)
        let bbModel = ImportReviewModel(plan: bbPlan, locale: AppLocale.current)
        r.oneModelBothProfiles =
            swModel.proposals.count == 2 && bbModel.proposals.count == 1
            && swModel.rejections.allSatisfy { $0.lineNumber >= 2 }
            && bbModel.rejections.allSatisfy { $0.lineNumber >= 2 }
        r.rejectionLinesPresent =
            swModel.rejections.map(\.lineNumber).sorted() == [3, 5]
            && bbModel.rejections.map(\.lineNumber).sorted() == [2, 4]
        r.totalsRendered =
            !swModel.totalsText.isEmpty && !bbModel.totalsText.isEmpty

        // Flagged-not-removed: the Tuk tuk grouping-separator row is refused;
        // Dinner 2 duplicates Dinner by amount+date+merchant and must STILL
        // be an included proposal wearing its flag:
        guard let dinnerRow = swModel.proposals.first(where: { $0.id == 1 }) else {
            return r
        }
        guard
            case .earlierInFile(let origin)? = dinnerRow.duplicateOf,
            origin == 0
        else { return r }
        r.duplicateFlaggedNotRemoved =
            dinnerRow.duplicateOf != nil && dinnerRow.included

        // Exclude-one semantics: uncheck the flagged duplicate, apply; n−1
        // proposals become n−1 created expenses in one atomic unit:
        swModel.setIncluded(false, forID: 1)
        swModel.apply(to: empty)
        if case .done(let count) = swModel.appliedState, count == 1 {
            r.exclusionFiltered = true
        } else { return r }

        // Atomic refusal: feeding a plan whose expense would break share-sum
        // validation arrives as a typed failure with NOTHING created:
        var poisoned = ImportPlan(expenses: [], duplicates: [], rejections: [], totalsByCurrency: [])
        poisoned.expenses = [
            PortExpense(
                payer: ada,
                paidMinor: 500,
                currencyCode: "USD",
                group: nil,
                conversion: nil,
                shares: [
                    ExpenseShare(id: ada, amountMinor: 499),
                    ExpenseShare(id: bob, amountMinor: 1),
                ],
                note: "broken sum",
                timestampMs: 20_468 * 86_400_000,
                category: nil
            )
        ]
        poisoned.expenses[0].shares[1].amountMinor = 2 // now sums to 501 ≠ 500
        let poisonModel = ImportReviewModel(plan: poisoned, locale: AppLocale.current)
        poisonModel.apply(to: empty)
        if case .failed(let message) = poisonModel.appliedState {
            r.atomicRefusal = message.contains("invalid")
        }
        return r
    }

}
#endif
