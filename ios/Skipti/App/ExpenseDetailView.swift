import SwiftUI

/// D-142: one expense, opened from the Expenses list (the founder,
/// 2026-09-19: tap a row for its detail with Edit and Delete). It shows the
/// whole split and SPEC §5.7's "added by" and "last edited by" with a
/// timestamp ("it ends arguments"). Edit opens the Add form filled in, as a
/// sheet; Delete asks first, and the toast then offers Undo (D-248). When two devices
/// set different amounts, SPEC §6.1's question is the first thing on the
/// page, with one button per version.
///
/// Everything is read from core's projection of the record path
/// (`projectedExpenses()`), never from the kv ledger, so the page shows
/// exactly what every device converges on.
struct ExpenseDetailView: View {
    let store: LedgerStore
    let expenseID: String
    /// The expense's own group: its people's names, and who "you" are there.
    /// Nil when this vault no longer holds the group; Edit is then off, and
    /// Delete still works.
    let named: NamedGroupWire?
    /// Called after a confirmed delete, before this page closes, so the
    /// opener can read again (D-248: the Undo is the toast, `ExpenseUndo`).
    var onDeleted: ((ProjectedExpenseOut) -> Void)?

    @Environment(\.dismiss) private var dismiss
    @State private var expense: ProjectedExpenseOut?
    @State private var loadError: String?
    @State private var editing: ExpenseEditTarget?
    @State private var confirmingDelete = false
    @State private var actionError: String?
    /// The group's history names (`historyNames`): people removed from it too.
    @State private var history: [String: String] = [:]
    /// D-249: your ids, so a Just me expense reads "You" (it has no group to name you in).
    @State private var yourIds: Set<String> = []

    var body: some View {
        Group {
            if let expense, isCopy(expense) {
                // D-210: in a read-only copy it is only read; nothing to edit or delete.
                ScrollView { content(expense) }
            } else if let expense {
                ScrollView { content(expense) }
                    .formActionBar { actions(expense) }
            } else {
                ContentUnavailableView(
                    "This expense is not in your ledger",
                    systemImage: "tray",
                    description: Text(verbatim: loadError ?? "")
                )
            }
        }
        .auroraBackground() // D-176
        .navigationTitle(expense?.note ?? String(localized: "Expense"))
        .navigationBarTitleDisplayMode(.inline)
        .sheet(item: $editing) { target in
            NavigationStack {
                AddExpenseScreen(onSaved: { editing = nil; load() }, editing: target)
                    .toolbar {
                        ToolbarItem(placement: .cancellationAction) {
                            Button("Cancel") { editing = nil }.barButtonText().barTint()
                        }
                    }
            }
        }
        // D-235 look F3: read again on every change sync brings (the list's signal), so an open expense shows a remote
        // edit, or that it was deleted, without leaving the page. Android's twin keys its load on `SyncStatus.revision`.
        .task(id: SyncStatus.shared.revision) { load() }
    }

    /// The expense's group is a read-only copy on this phone. An unreadable
    /// mark counts as a copy: nothing is offered that could not be undone.
    private func isCopy(_ e: ProjectedExpenseOut) -> Bool {
        guard let group = e.expense.group else { return false }
        guard let copies = try? store.readOnlyChecked() else { return true }
        return copies[group] != nil
    }

    // MARK: - Content

    private func content(_ e: ProjectedExpenseOut) -> some View {
        VStack(alignment: .leading, spacing: 16) {
            if let prompt = ExpenseEditing.conflictPrompt(e, locale: AppLocale.current) {
                conflictCard(e, prompt: prompt)
            }

            VStack(alignment: .leading, spacing: 6) {
                if let category = ExpenseListView.categoryLabelForTesting(for: e.category) {
                    micro(category, ground: true)
                }
                Text(verbatim: ExpenseEditing.amount(e.expense, locale: AppLocale.current))
                    .font(TypeRole.numeric(34, weight: 500, relativeTo: .largeTitle))
                    .foregroundStyle(Tokens.text)
                    .lineLimit(1)
                    .minimumScaleFactor(0.5)
                    .accessibilityLabel(spoken(e.expense))
                HStack(alignment: .firstTextBaseline) {
                    // D-249: a Just me expense names its ledger, not a payer (it is always you).
                    micro(e.expense.group == nil ? SpendingCopy.justMe : String(localized: "Paid by \(name(e.expense.payer))"), ground: true)
                    Spacer()
                    if let ts = e.timestampMs {
                        Text(verbatim: ExpenseListView.dateString(ts, locale: AppLocale.current))
                            .font(TypeRole.numeric(12))
                            .foregroundStyle(Tokens.groundFaint)
                    }
                }
            }

            // D-249: Just me has nothing to split.
            if e.expense.group != nil {
                splitCard(e.expense)
            } else {
                micro(SpendingCopy.onlyYouNothingToSplit, ground: true)
            }

            VStack(alignment: .leading, spacing: 4) {
                // Only a creation that names its author says who added it: a
                // pre-D-141 expense's record was minted by the one-time
                // backfill, so its time would be the backfill's, not the add.
                if let addedBy = e.addedBy {
                    attribution(String(localized: "Added by \(name(addedBy))"), at: e.addedAtMs)
                }
                if let editedAt = e.lastEditedAtMs {
                    if let editor = e.lastEditedBy {
                        attribution(String(localized: "Last edited by \(name(editor))"), at: editedAt)
                    } else {
                        attribution(String(localized: "Last edited"), at: editedAt)
                    }
                }
            }

            if let actionError {
                Text(verbatim: actionError)
                    .font(TypeRole.body())
                    .foregroundStyle(Tokens.owed)
            }

            micro(String(localized: "Recorded in your ledger. It does not move money."), ground: true)
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func splitCard(_ money: ExpenseInput) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            sectionMicro(String(localized: "Split"))
            ForEach(money.shares, id: \.id) { share in
                HStack(alignment: .firstTextBaseline) {
                    Text(verbatim: name(share.id))
                        .font(TypeRole.body())
                        .foregroundStyle(Tokens.text)
                    Spacer()
                    Text(verbatim: format(share.amountMinor, money.currencyCode))
                        .font(TypeRole.numeric(15))
                        .foregroundStyle(Tokens.text)
                }
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(
                    "\(name(share.id)), \(spokenMinor(share.amountMinor, money.currencyCode))"
                )
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Tokens.surface, in: ThemeArt.panelShape(AppearanceState.shared.theme, radius: 12, continuous: true))
    }

    /// SPEC §6.1: the one conflict put to the person. Each competing version
    /// is shown whole (amount, payer, split), because two versions can share
    /// an amount and differ in who paid.
    private func conflictCard(_ e: ProjectedExpenseOut, prompt: String) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(verbatim: prompt)
                .font(TypeRole.body(17))
                .foregroundStyle(Tokens.text)
            ForEach(Array(e.moneyConflict.enumerated()), id: \.offset) { _, option in
                VStack(alignment: .leading, spacing: 6) {
                    Text(verbatim: ExpenseEditing.amount(option, locale: AppLocale.current))
                        .font(TypeRole.numeric(20, weight: 500))
                        .foregroundStyle(Tokens.text)
                        .accessibilityLabel(spoken(option))
                    micro(String(localized: "Paid by \(name(option.payer))"))
                    ForEach(option.shares, id: \.id) { share in
                        HStack {
                            Text(verbatim: name(share.id)).font(TypeRole.body(14))
                            Spacer()
                            Text(verbatim: format(share.amountMinor, option.currencyCode))
                                .font(TypeRole.numeric(14))
                        }
                        .font(TypeRole.body(14))
                        .foregroundStyle(Tokens.textDim)
                    }
                    Button(ExpenseEditing.keepLabel(option, in: e, locale: AppLocale.current)) {
                        keep(option, of: e)
                    }
                    .buttonStyle(SecondaryButtonStyle())
                    .padding(.top, 4)
                }
                .padding(12)
                .overlay(ThemeArt.panelShape(AppearanceState.shared.theme, radius: 10).stroke(Tokens.hairline, lineWidth: 2).clipShape(ThemeArt.panelShape(AppearanceState.shared.theme, radius: 10)))
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Tokens.surfaceRaised, in: ThemeArt.panelShape(AppearanceState.shared.theme, radius: 12, continuous: true))
    }

    private func actions(_ e: ProjectedExpenseOut) -> some View {
        HStack(spacing: 12) {
            Button("Delete") { confirmingDelete = true }
                .buttonStyle(SecondaryButtonStyle())
                .accessibilityHint("Asks you to confirm. You can undo it right after.")
                // Anchored to the button: iOS 26 and later draw the dialog as
                // a popover from its view (on the page it floated at the top).
                .confirmationDialog(
                    "Delete this expense?",
                    isPresented: $confirmingDelete,
                    titleVisibility: .visible
                ) {
                    Button("Delete expense", role: .destructive) { delete() }
                    Button("Cancel", role: .cancel) {}
                } message: {
                    // D-249: a Just me expense was never in a balance.
                    Text(verbatim: ExpenseDeleteCopy.message(justMe: e.expense.group == nil))
                }
                // D-224 (Design 3): the warning as the question opens.
                .hapticWarning(when: confirmingDelete)
            // D-249: a Just me expense edits too (it has no group by design); a group's needs its group here.
            Button("Edit") {
                if ExpenseDeleteCopy.canEdit(group: e.expense.group, named: named) {
                    editing = ExpenseEditTarget(expense: e, named: named, store: store)
                }
            }
            .buttonStyle(PrimaryButtonStyle())
            .disabled(!ExpenseDeleteCopy.canEdit(group: e.expense.group, named: named))
            .accessibilityHint("Opens this expense in the form, filled in.")
        }
    }

    // MARK: - Actions

    private func load() {
        do {
            let all = try store.projectedExpenses().expenses
            if let found = all.first(where: { $0.id == expenseID && !$0.deleted }) {
                history = found.expense.group.flatMap { try? store.historyNames(group: $0) } ?? [:]
                let me = try? store.loadSelf()
                yourIds = Set([me?.id].compactMap { $0 } + (me?.aliases ?? []))
                expense = found
                loadError = nil
            } else {
                expense = nil
                loadError = String(localized: "It was deleted, possibly on another device.")
            }
        } catch {
            expense = nil
            loadError = "\(error)"
        }
    }

    private func delete() {
        guard let e = expense else { return }
        do {
            try store.reviseExpense(ExpenseEditing.delete(e, editor: named?.selfId))
            CloudSync.shared.ledgerChanged()
            // D-248 (U30.4): the Undo the dialog promised, here, whoever opened this page.
            ExpenseUndo.offer(e, store: store, editor: named?.selfId)
            onDeleted?(e)
            dismiss()
        } catch {
            actionError = "\(error)"
        }
    }

    private func keep(_ option: ExpenseInput, of e: ProjectedExpenseOut) {
        do {
            try store.reviseExpense(ExpenseEditing.keep(option, of: e, editor: named?.selfId))
            CloudSync.shared.ledgerChanged()
            load()
        } catch {
            actionError = "\(error)"
        }
    }

    // MARK: - Pieces

    private func name(_ id: String) -> String {
        if expense?.expense.group == nil, yourIds.contains(id) { return ExpenseCopy.you }
        guard let named else { return history[id] ?? String(localized: "Unknown member") }
        return LedgerStore.label(for: id, in: named, history: history)
    }

    /// `ground`: the text sits directly on the page ground (D-214), not on a card.
    private func micro(_ text: String, ground: Bool = false) -> some View {
        Text(verbatim: text)
            .microLabelFont()
            .foregroundStyle(ground ? Tokens.groundFaint : Tokens.textFaint)
            .accessibilityLabel(text)
    }

    /// D-213: a section label (it names the rows that follow); `micro` stays for the category, the payer and the
    /// sentence, which carry data or are not headings.
    private func sectionMicro(_ text: String) -> some View {
        Text(verbatim: text)
            .sectionLabel()
            .foregroundStyle(Tokens.textDim)
            .accessibilityLabel(text)
    }

    private func attribution(_ who: String, at ms: UInt64) -> some View {
        Separator.text([who, Self.stamp(ms, locale: AppLocale.current)], size: TypeScale.detail)
            .font(TypeRole.body(TypeScale.detail))
            .foregroundStyle(Tokens.textDim)
    }

    private func format(_ minor: Int64, _ code: String) -> String {
        (try? ffiMoneyFormat(minorUnits: minor, currencyCode: code, locale: AppLocale.current)) ?? Placeholder.none
    }

    private func spoken(_ money: ExpenseInput) -> String {
        spokenMinor(money.paidMinor, money.currencyCode)
    }

    private func spokenMinor(_ minor: Int64, _ code: String) -> String {
        (try? ffiMoneySpoken(minorUnits: minor, currencyCode: code, locale: AppLocale.current)) ?? ""
    }

    /// §5.7's timestamp: day, month, year and time, in the app's locale
    /// (dates are presentation and format in Swift, D-54).
    static func stamp(_ ms: UInt64, locale: LocaleTag) -> String {
        AppDate.stamp(ms, locale: locale)
    }
}

#if DEBUG
// D-235 (C4, iOS L1, decision 108): a proof surface, Debug builds only.
/// D-142's proof surface (`--expense-conflict`; a test entry point, not a
/// user flow). One device cannot produce an amount conflict, because each
/// edit it makes has seen the one before. This seeds a THROWAWAY vault in
/// the temporary directory with two amount edits that did not see each
/// other, then shows the real detail page over it. The person's own ledger
/// is never opened for writing.
struct ExpenseConflictProofScreen: View {
    @State private var seeded: ExpenseEditTarget?
    @State private var error: String?

    var body: some View {
        Group {
            if let seeded {
                ExpenseDetailView(store: seeded.store, expenseID: seeded.id, named: seeded.named)
            } else {
                Text(verbatim: error ?? "…")
                    .font(TypeRole.body())
                    .foregroundStyle(Tokens.textDim)
            }
        }
        .task { seed() }
    }

    private func seed() {
        guard seeded == nil else { return }
        do {
            let dir = FileManager.default.temporaryDirectory
                .appendingPathComponent("conflict-proof-\(UUID().uuidString)", isDirectory: true)
            try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
            let store = LedgerStore(path: dir.appendingPathComponent("ledger.db").path)
            let asha = String(repeating: "0", count: 31) + "1"
            let ravi = String(repeating: "0", count: 31) + "2"
            let group = String(repeating: "0", count: 31) + "9"
            let named = NamedGroupWire(
                group: GroupWire(id: group, name: "Goa", settlementCurrency: "INR", members: [asha, ravi]),
                selfId: asha,
                members: [MemberWire(id: asha, name: "Asha"), MemberWire(id: ravi, name: "Ravi")]
            )
            func money(_ minor: Int64, payer: String) throws -> ExpenseInput {
                let shares = try ffiSplitCompute(
                    totalMinor: minor, currencyCode: "INR", members: [asha, ravi], spec: .equal
                )
                return ExpenseInput(
                    payer: payer, paidMinor: minor, currencyCode: "INR",
                    shares: shares.map { IdAmount(id: $0.id, amountMinor: $0.minorUnits) },
                    group: group, conversion: nil
                )
            }
            let first = try money(120_000, payer: asha)
            _ = try store.appendExpense(LedgerStore.ExpenseDTO(
                payer: asha, paidMinor: first.paidMinor, currencyCode: "INR", group: group,
                conversion: nil,
                shares: first.shares.map { .init(id: $0.id, amountMinor: $0.amountMinor) },
                note: "Dinner", timestampMs: UInt64(Date().timeIntervalSince1970 * 1000),
                category: nil, addedBy: asha
            ))
            let id = try onlyExpense(store.projectedExpenses().expenses).id
            // Two edits that did not see each other: empty `seen` on both.
            for (minor, editor) in [(125_000 as Int64, asha), (130_000 as Int64, ravi)] {
                try store.reviseExpense(ExpenseRevisionIn(
                    target: id, seen: [], editor: editor, money: try money(minor, payer: asha),
                    note: nil, category: nil, deleted: nil
                ))
            }
            let projected = try onlyExpense(store.projectedExpenses().expenses)
            seeded = ExpenseEditTarget(expense: projected, named: named, store: store)
        } catch {
            self.error = "\(error)"
        }
    }

    private func onlyExpense(_ list: [ProjectedExpenseOut]) throws -> ProjectedExpenseOut {
        guard let first = list.first else { throw LedgerStore.LoadError.unknownExpense }
        return first
    }
}
#endif

/// D-249: the delete confirmation's words and whether Edit is offered, for a group's expense and a Just me one (the
/// list's long press and the detail page say the same). Android's twin is `ExpenseDeleteCopy`.
enum ExpenseDeleteCopy {
    static let groupMessage = "It leaves your balances. You can undo it right after."

    static func message(justMe: Bool) -> String { justMe ? SpendingCopy.deleteMessage : groupMessage }

    /// A Just me expense always; a group's only while this phone holds the group.
    static func canEdit(group: String?, named: NamedGroupWire?) -> Bool { group == nil || named != nil }
}

/// D-248 (U30.4): a deleted expense's Undo comes to where the delete happened: the done toast ("Expense deleted", its
/// description) with Undo, wherever the detail was opened (the group page, Lately, Activity, a notification, the
/// Expenses list) and for the list's long-press delete. It stays for the hold's window (`SettleHold.window`: longer with
/// a screen reader). Undo writes the restoring revision from the projection that holds the delete, then every screen
/// reads again. The Expenses list's foot row went (one way to undo, everywhere). Android's twin is `ExpenseUndo`.
@MainActor
enum ExpenseUndo {
    static func moment(_ deleted: ProjectedExpenseOut, seconds: Double) -> DoneMoment {
        DoneMoments.line(DoneCopy.expenseDeleted, parts: [deleted.note], action: DoneCopy.undo, seconds: seconds)
    }

    static func offer(_ deleted: ProjectedExpenseOut, store: LedgerStore, editor: String?, center: DoneCenter? = nil,
                      screenReader: Bool? = nil) {
        let screenReader = screenReader ?? UIAccessibility.isVoiceOverRunning
        let center = center ?? .shared
        center.post(moment(deleted, seconds: SettleHold.window(screenReader: screenReader)), onAction: {
            // Review M2: a failed write is said, never silent.
            if !undo(deleted, store: store, editor: editor) { center.post(DoneMoments.line(DoneCopy.notRestored)) }
        })
    }

    /// Written from the projection that HOLDS the delete, so the Undo has seen it. False when it could not be written
    /// (`offer` then posts "Not restored.").
    @discardableResult
    static func undo(_ deleted: ProjectedExpenseOut, store: LedgerStore, editor: String?) -> Bool {
        do {
            let current = try store.projectedExpenses().expenses.first { $0.id == deleted.id } ?? deleted
            try store.reviseExpense(ExpenseEditing.undo(current, editor: editor))
            CloudSync.shared.ledgerChanged()
            SyncStatus.shared.changed()
            return true
        } catch {
            return false
        }
    }
}
