import SwiftUI

/// §9.5's confirm UI over ONE plan type.
///
/// Deliberately PROFILE-BLIND: `ImportPlan` carries no provenance field and
/// this screen needs none. The source format (Splitwise group sheet vs
/// BudgetBakers statement) is chosen BEFORE planning because it describes
/// the file being read, not the result of reading it: a plan through either
/// profile lands here identically. If this screen ever needed a profile
/// parameter to render or commit a plan, the engine boundary would have
/// leaked provenance into results.
///
/// §9.5's obligations, enforced by shape rather than discipline:
/// 1. Nothing exists until Apply: this view holds ONLY the plan and the
///    existing snapshot; creation goes through `ffiApplyImportPlan`, whose
///    merge validates the whole result and refuses atomically.
/// 2. Duplicates are FLAGGED, never removed: flagged rows ship included,
///    WITH the flag visible; unchecking is the human decision the flag
///    exists to inform.
/// 3. Rejections list their SPREADSHEET line numbers, matching what the
///    user sees when they open the export themselves.
@Observable
final class ImportReviewModel {
    /// One proposed expense. Inclusion is the user's per-row call; a
    /// duplicate arrives included, with its flag showing.
    struct Proposal: Identifiable {
        let id: Int // index into the ORIGINAL plan's expenses
        let amountText: String
        let noteText: String?
        let dateText: String?
        let currencyCode: String
        let paidMinor: Int64
        let duplicateOf: DuplicateTarget?
        var included: Bool = true
    }

    enum DuplicateTarget {
        case existing(index: Int)
        case earlierInFile(index: Int)

        var label: String {
            switch self {
            case .existing(let i): return "matches imported expense \(i + 1)"
            case .earlierInFile(let i): return "matches proposal #\(i + 1) from this file"
            }
        }
    }

    struct RejectionRow: Identifiable {
        let id: UInt32 // spreadsheet line = stable identity
        let lineNumber: UInt32
        let reason: String
    }

    enum AppliedState: Equatable {
        case reviewing
        case done(expenseCount: Int)
        case failed(message: String)
    }

    private(set) var proposals: [Proposal] = []
    private(set) var rejections: [RejectionRow] = []
    private(set) var totalsText: [String] = []
    private(set) var appliedState: AppliedState = .reviewing

    /// Captured at init so exclusions filter the ORIGINAL plan while index
    /// space stays intact; the engine never learns a row was hidden.
    private let sourceExpenses: [PortExpense]

    var includedCount: Int { proposals.filter(\.included).count }
    var duplicateFlaggedCount: Int { proposals.filter { $0.duplicateOf != nil }.count }
    var hasIncludedExpenses: Bool { includedCount > 0 }

    /// `names` (D-166): participant id → name, so a refusal reads "Tim"
    /// where core wrote an id.
    init(plan: ImportPlan, locale: LocaleTag, names: [String: String] = [:]) {
        sourceExpenses = plan.expenses
        proposals = plan.expenses.enumerated().map { index, expense in
            let amount = (try? ffiMoneyFormat(
                minorUnits: expense.paidMinor,
                currencyCode: expense.currencyCode,
                locale: locale
            )) ?? Placeholder.none
            let date = expense.timestampMs.map {
                Self.dateString($0, locale: locale)
            }
            let duplicate = plan.duplicates.first { $0.expenseIndex == UInt32(index) }
                .map { hit -> DuplicateTarget in
                    switch hit.against {
                    case .existing(let i): return .existing(index: Int(i))
                    case .inFile(let i): return .earlierInFile(index: Int(i))
                    @unknown default: return .earlierInFile(index: 0)
                    }
                }
            return Proposal(
                id: index,
                amountText: amount,
                noteText: expense.note,
                dateText: date,
                currencyCode: expense.currencyCode,
                paidMinor: expense.paidMinor,
                duplicateOf: duplicate
            )
        }
        rejections = plan.rejections.map {
            RejectionRow(id: $0.rowNumber, lineNumber: $0.rowNumber, reason: Self.naming($0.reason, names))
        }
        totalsText = plan.totalsByCurrency.compactMap { total in
            (try? ffiMoneyFormat(
                minorUnits: total.costMinor,
                currencyCode: total.currencyCode,
                locale: locale
            )).map { "\($0) \(total.currencyCode)" }
        }
    }

    func setIncluded(_ value: Bool, forID id: Int) {
        guard let idx = proposals.firstIndex(where: { $0.id == id }) else { return }
        proposals[idx].included = value
    }

    /// THE ACT. Explicit; the only path out of review. Merges the INCLUDED
    /// proposals as one unit through the FFI, which validates the entire
    /// merged ledger and refuses atomically. Excluded proposals are left
    /// out of the handed-over plan: deciding is this layer's whole job.
    func apply(to existing: LedgerSnapshot, save: (([PortExpense]) throws -> Void)? = nil) {
        guard hasIncludedExpenses else {
            appliedState = .failed(message: "Nothing selected to import.")
            return
        }
        var filtered = ImportPlan(
            expenses: [],
            duplicates: [],
            rejections: [],
            totalsByCurrency: []
        )
        filtered.expenses = proposals.filter(\.included).map { sourceExpenses[$0.id] }
        do {
            let merged = try ffiApplyImportPlan(existing: existing, plan: filtered)
            // D-166: written only after core accepted the whole merge.
            if let save {
                do {
                    try save(filtered.expenses)
                } catch {
                    appliedState = .failed(message: "The import could not be saved: \(error)")
                    return
                }
                appliedState = .done(expenseCount: filtered.expenses.count)
            } else {
                appliedState = .done(expenseCount: merged.expenses.count)
            }
        } catch {
            appliedState = .failed(message: Self.describe(error))
        }
    }

    /// §11.6 register: what happened, nothing else.
    static func describe(_ error: Error) -> String {
        if let port = error as? PortError, case .InvalidLedger(let m) = port {
            return "The merged ledger would be invalid: \(m)"
        }
        return "The import was refused."
    }

    /// D-144: through `AppDate` (this copy said "dMMMmy", minute for month).
    /// D-166: in UTC. The importers read a file's dates as UTC wall times, so
    /// local time showed a Splitwise row dated 20 Sep as Sep 19 in the US
    /// (seen on the simulator). The file's own date is what the person checks.
    static func dateString(_ ms: UInt64, locale: LocaleTag) -> String {
        AppDate.day(ms, locale: locale, timeZone: TimeZone(identifier: "UTC")!)
    }

    static func naming(_ reason: String, _ names: [String: String]) -> String {
        names.reduce(reason) { $0.replacingOccurrences(of: $1.key, with: $1.value) }
    }
}

/// The user-facing screen. Renders any ImportPlan identically (§9.5).
struct ImportConfirmScreen: View {
    let existing: LedgerSnapshot
    let plan: ImportPlan
    /// D-166: where applied expenses are written, and what happens after.
    /// Without it (the proof surface) nothing is written.
    var commit: Commit?
    @State private var model: ImportReviewModel?

    struct Commit {
        let groupName: String
        let names: [String: String]
        let save: ([PortExpense]) throws -> Void
        let finish: () -> Void
    }

    var body: some View {
        Group {
            if let model {
                review(model)
            } else {
                Text("Preparing import…")
                    .font(TypeRole.body())
                    .foregroundStyle(Tokens.textDim)
            }
        }
        .auroraBackground() // D-176
        .navigationTitle(Text(verbatim: ImportCopy.reviewTitle))
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            if model == nil {
                model = ImportReviewModel(plan: plan, locale: AppLocale.current, names: commit?.names ?? [:])
            }
        }
    }

    private func review(_ model: ImportReviewModel) -> some View {
        List {
            Section {
                // Reconciliation preview: cross-check against the source
                // app's own totals BEFORE anything exists.
                ForEach(Array(model.totalsText.enumerated()), id: \.offset) { _, t in
                    Text("Total \(t)")
                        .font(TypeRole.numeric(13))
                        .foregroundStyle(Tokens.textDim)
                }
                Text("Nothing is created yet")
                    .microLabelFont()
                    .foregroundStyle(Tokens.textFaint)
            }
            Section {
                ForEach(model.proposals) { p in
                    proposalRow(p)
                }
            } header: {
                sectionTitle("What will be created")
            }
            if !model.rejections.isEmpty {
                Section {
                    ForEach(model.rejections) { r in
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Line \(r.lineNumber)")
                                .font(TypeRole.numeric(12))
                                .foregroundStyle(Tokens.textFaint)
                            Text(r.reason)
                                .font(TypeRole.body())
                                .foregroundStyle(Tokens.textDim)
                        }
                        .accessibilityElement(children: .combine)
                        .accessibilityLabel("Line \(r.lineNumber) refused: \(r.reason)")
                    }
                } header: {
                    sectionTitle("Refused rows")
                }
            }
            Section {
                switch model.appliedState {
                case .reviewing:
                    Text("Applying writes all selected expenses at once.")
                        .font(TypeRole.body(14))
                        .foregroundStyle(Tokens.textDim)
                case .done(let count):
                    if let commit {
                        Label(ImportCopy.done(count, group: commit.groupName), systemImage: "checkmark.circle")
                            .font(TypeRole.body())
                            .foregroundStyle(Tokens.owing)
                    } else {
                        Label("\(count) expenses now in the ledger", systemImage: "checkmark.circle")
                            .font(TypeRole.body())
                            .foregroundStyle(Tokens.owing)
                    }
                case .failed(let message):
                    Text(message).font(TypeRole.body()).foregroundStyle(Tokens.owed)
                }
            }
        }
        // D-139: under the list, not in it (iOS 26+ clipped the row to a
        // pill), and only while there is something to apply.
        .auroraFormBackground() // D-176
        .formActionBar {
            if case .reviewing = model.appliedState {
                Button(
                    !model.hasIncludedExpenses ? "Select at least one expense"
                        : model.includedCount == 1 ? "Import 1 expense" : "Import \(model.includedCount) expenses"
                ) {
                    model.apply(to: existing, save: commit?.save)
                    // D-224 (Design 7): the page shows what was imported; only the success haptic, through the app's gate.
                    if case .done = model.appliedState { DoneCenter.shared.success() }
                }
                .buttonStyle(PrimaryButtonStyle())
                .disabled(!model.hasIncludedExpenses)
                .accessibilityHint("Creates all selected expenses as one unit, or nothing.")
            } else if case .done = model.appliedState, let commit {
                Button(ImportCopy.showGroup) { commit.finish() }
                    .buttonStyle(PrimaryButtonStyle())
            }
        }
    }

    /// D-166: the app's section header style (these were system headers).
    private func sectionTitle(_ text: String) -> some View {
        Text(verbatim: text).sectionLabel().foregroundStyle(Tokens.textDim)
    }

    @ViewBuilder
    private func proposalRow(_ p: ImportReviewModel.Proposal) -> some View {
        // D-166: the whole row includes or leaves out the expense. It held a
        // Toggle inside a row whose children were hidden from accessibility,
        // and on iOS 27 the toggle took no taps (seen on the simulator; the
        // screen had only been reached through a proof argument before).
        Button {
            model?.setIncluded(!p.included, forID: p.id)
        } label: {
            HStack(alignment: .firstTextBaseline, spacing: 12) {
                Image(systemName: p.included ? "checkmark.circle.fill" : "circle")
                    .font(.system(size: 22))
                    .foregroundStyle(p.included ? Tokens.accent : Tokens.textFaint)
                VStack(alignment: .leading, spacing: 4) {
                    Text(p.noteText ?? "Untitled")
                        .font(TypeRole.body())
                        .foregroundStyle(p.included ? Tokens.text : Tokens.textFaint)
                    if let d = p.duplicateOf {
                        // The flag travels WITH the row: excluded ≠ unflagged.
                        Text("Duplicate: \(d.label)")
                            .microLabelFont()
                            .foregroundStyle(Tokens.owed)
                    }
                    HStack(spacing: 8) {
                        if let date = p.dateText {
                            Text(date)
                                .font(TypeRole.numeric(12))
                                .foregroundStyle(Tokens.textFaint)
                        }
                        Text("\(p.amountText) \(p.currencyCode)")
                            .font(TypeRole.numeric(15, weight: 500))
                            .foregroundStyle(p.included ? Tokens.text : Tokens.textFaint)
                            .lineLimit(1)
                            .minimumScaleFactor(0.5)
                    }
                }
                Spacer(minLength: 0)
            }
            .contentShape(Rectangle())
        }
        // Applied is applied: the rows stop changing.
        .disabled(model?.appliedState != .reviewing)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(
            "Proposal \(p.id + 1), \(p.noteText ?? "untitled"), \(Placeholder.spoken(p.amountText)) \(p.currencyCode)"
                + (p.dateText.map { ", \($0)" } ?? "")
                + (p.duplicateOf.map { ". Duplicate: \($0.label)" } ?? "")
        )
        .accessibilityValue(p.included ? "Included" : "Left out")
        .accessibilityHint("Double-tap to include or leave out.")
        .accessibilityAddTraits(.isButton)
    }
}
