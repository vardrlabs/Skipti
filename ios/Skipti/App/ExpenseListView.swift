import SwiftUI

/// §5.3's Transaction list: the records BEHIND the balance line. An
/// expense could be added and never seen again; this screen shows the
/// vault's actual contents, newest first, with §5.7's attribution (who
/// paid) and §11.3's numeric role for every amount.
///
/// D-142: it reads core's projection (`projectedExpenses()`), so every row
/// has the expense's stable id, edits show as edited and deleted expenses
/// are gone. A tap opens the expense (`ExpenseDetailView`) with Edit and
/// Delete; a long press offers the same two without leaving the list (the
/// founder's choice, 2026-09-19). A delete is confirmed first, and Undo is
/// offered on the done toast (D-248: `ExpenseUndo`, as from every detail page).
/// D-181: the Expenses tab's words; Android's twin is `ExpenseCopy`.
enum ExpenseCopy {
    static let allGroups = "All groups"
    /// D-249 (U10): the no-group section and its chip (was "Personal"), still last.
    static let justMe = SpendingCopy.justMe
    /// D-249: a Just me row's payer is always you.
    static let you = "You"
    static let notInvolved = "not involved"
    /// "you lent ₹9,000.00" / "you owe ₹1,200.00" / "not involved".
    static func effect(_ minor: Int64, _ formatted: String) -> String {
        minor > 0 ? "you lent \(formatted)" : (minor < 0 ? "you owe \(formatted)" : notInvolved)
    }
    static func paidBy(_ who: String, _ formatted: String) -> String { "\(who) paid \(formatted)" }
}

/// D-181: the Expenses tab, grouped by group (the founder, 2026-09-29: "right
/// now it's all mixed up"). A section per group, the group with the newest
/// expense first; within a section, newest first; expenses in no group last,
/// as "Just me" (D-249). A chip narrows the tab to one group, or to Just me. Android's twin:
/// `ExpenseSections` in ExpenseListScreen.kt.
enum ExpenseSections {
    /// D-249: what a chip narrows the tab to.
    enum Filter: Hashable {
        case all
        case justMe
        case group(String)
    }

    static func build(_ expenses: [ProjectedExpenseOut], filter: Filter) -> [Section] {
        switch filter {
        case .all: return build(expenses, only: nil)
        case .group(let id): return build(expenses, only: id)
        case .justMe: return build(expenses, only: nil).filter { $0.groupID == nil }
        }
    }

    struct Section {
        let groupID: String?
        let expenses: [ProjectedExpenseOut]
    }

    static func newestFirst(_ a: ProjectedExpenseOut, _ b: ProjectedExpenseOut) -> Bool {
        (a.timestampMs ?? 0, a.note ?? "", a.id) > (b.timestampMs ?? 0, b.note ?? "", b.id)
    }

    static func build(_ expenses: [ProjectedExpenseOut], only group: String?) -> [Section] {
        var order: [String?] = []
        var byGroup: [String?: [ProjectedExpenseOut]] = [:]
        for e in expenses.sorted(by: newestFirst) where group == nil || e.expense.group == group {
            if byGroup[e.expense.group] == nil { order.append(e.expense.group) }
            byGroup[e.expense.group, default: []].append(e)
        }
        // Personal (no group) goes last, whatever its dates (a partition,
        // not a sort: Swift's sort is not stable).
        order = order.filter { $0 != nil } + order.filter { $0 == nil }
        return order.map { Section(groupID: $0, expenses: byGroup[$0] ?? []) }
    }
}

struct ExpenseListView: View {
    @State private var expenses: [ProjectedExpenseOut] = []
    /// D-181: the chip that narrows the tab to one group (D-249: or to Just me).
    @State private var filter: ExpenseSections.Filter = .all
    /// D-249 (decision 190): the Just me header opens Your spending (reachable with Home's card hidden).
    @State private var openSpending = false
    @State private var loadError: String?

    /// D-135: payer names come from the groups each record belongs to; you are
    /// "Name (You)". No fixture names on a real ledger.
    @State private var groups: [NamedGroupWire] = []

    /// D-142: the long-press menu's targets (D-248: a delete's Undo is the toast, `ExpenseUndo`).
    @State private var editing: ExpenseEditTarget?
    @State private var confirmingDelete: ProjectedExpenseOut?
    @State private var actionError: String?
    /// D-249: your name and picture for the Just me section's tile.
    @State private var myName: String?
    @State private var myAvatar: Avatar = .none

    private func name(_ id: String) -> String {
        for named in groups where named.members.contains(where: { $0.id == id }) {
            return LedgerStore.label(for: id, in: named)
        }
        return String(localized: "Unknown member")
    }

    /// The expense's OWN group, which need not be the one selected on Balance.
    private func group(of expense: ProjectedExpenseOut) -> NamedGroupWire? {
        groups.first { $0.group.id == expense.expense.group }
    }

    private var store: LedgerStore { LedgerStore.defaultStore() }

    /// Newest first; records without timestamps sink to the end. The id
    /// breaks ties, so two expenses with the same time and note keep their
    /// places between loads.
    private var sorted: [ProjectedExpenseOut] {
        expenses.sorted {
            ($0.timestampMs ?? 0, $0.note ?? "", $0.id) > ($1.timestampMs ?? 0, $1.note ?? "", $1.id)
        }
    }

    /// D-142: "EXPENSES · 1 RECORDS" said the same thing twice, in the wrong
    /// number. Singular for one.
    static func countLabel(_ count: Int) -> String {
        count == 1 ? String(localized: "1 expense") : String(localized: "\(count) expenses")
    }

    var body: some View {
        Group {
            if let loadError {
                VStack(spacing: 0) {
                    // The inset is the ornament's alone: Aurora and Vardr Labs keep the full-width state.
                    if TitleOrnament.shows(AppearanceState.shared.theme) { TitleOrnament(above: 0).padding(.horizontal, 16) }
                    // D-223 (L-D217-10): the drawn state, as the empty list.
                    EmptyState(title: LedgerCopy.loadError, detail: loadError)
                }
                .auroraBackground() // D-176
            } else if expenses.isEmpty {
                // §11.6's empty state, verbatim (A2), drawn (D-223: Lucide's inbox, no system view or font).
                VStack(spacing: 0) {
                    // The inset is the ornament's alone: Aurora and Vardr Labs keep the full-width state.
                    if TitleOrnament.shows(AppearanceState.shared.theme) { TitleOrnament(above: 0).padding(.horizontal, 16) }
                    EmptyState(title: EmptyCopy.expensesTitle, detail: EmptyCopy.expensesDetail)
                }
                .auroraBackground() // D-176
            } else {
                ScrollView {
                    VStack(alignment: .leading, spacing: 12) {
                        if TitleOrnament.shows(AppearanceState.shared.theme) { TitleOrnament(above: 16) }
                        if let actionError {
                            Text(verbatim: actionError)
                                .font(TypeRole.body())
                                .foregroundStyle(Tokens.owed)
                        }

                        chips
                        ForEach(ExpenseSections.build(expenses, filter: filter), id: \.groupID) { section in
                            sectionHeader(section)
                            VStack(spacing: 0) {
                                ForEach(Array(section.expenses.enumerated()), id: \.element.id) { index, expense in
                                    if index > 0 { Divider().overlay(Tokens.hairline) }
                                    NavigationLink {
                                        ExpenseDetailView(
                                            store: store,
                                            expenseID: expense.id,
                                            named: group(of: expense),
                                            onDeleted: { _ in load() }
                                        )
                                    } label: {
                                        // D-181: rows share their group's glass card.
                                        row(expense)
                                            .padding(.vertical, 12)
                                            .frame(maxWidth: .infinity, alignment: .leading)
                                            .contentShape(Rectangle())
                                    }
                                    .buttonStyle(.plain)
                                    .contextMenu { menu(expense) }
                                    // VoiceOver's actions rotor: the menu's two actions
                                    // without the long press.
                                    .accessibilityAction(named: Text("Edit")) { startEdit(expense) }
                                    .accessibilityAction(named: Text("Delete")) { confirmingDelete = expense }
                                    .accessibilityHint("Opens the expense. Touch and hold for Edit and Delete.")
                                    // Anchored to the row it deletes: iOS 26 and later
                                    // draw the dialog as a popover from its view, and
                                    // on the whole list it floated at the top (seen on
                                    // iOS 27).
                                    .confirmationDialog(
                                        "Delete this expense?",
                                        isPresented: Binding(
                                            get: { confirmingDelete?.id == expense.id },
                                            set: { if !$0 { confirmingDelete = nil } }
                                        ),
                                        titleVisibility: .visible
                                    ) {
                                        Button("Delete expense", role: .destructive) { delete(expense) }
                                        Button("Cancel", role: .cancel) {}
                                    } message: {
                                        Text(verbatim: ExpenseDeleteCopy.message(justMe: expense.expense.group == nil))
                                    }
                                    // D-224 (Design 3): the warning as this row's question opens.
                                    .hapticWarning(when: confirmingDelete?.id == expense.id)
                                }
                            }
                            .glassCard(padding: 14)
                        }
                    }
                    .padding(16)
                    .shellScrollTop() // D-205
                }
                .auroraBackground() // D-176
            }
        }
        .navigationTitle("Expenses")
        .navigationBarTitleDisplayMode(.large)
        .navigationDestination(isPresented: $openSpending) { SpendingScreen() }
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
        // D-178: read again when sync brings records.
        .task(id: SyncStatus.shared.revision) { load() }
    }

    /// D-181: All groups, then each group that has expenses.
    private var chips: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                chip(ExpenseCopy.allGroups, .all)
                // D-249: a Just me chip beside All groups once there is a Just me expense.
                if expenses.contains(where: { $0.expense.group == nil }) {
                    chip(ExpenseCopy.justMe, .justMe)
                }
                ForEach(groups.filter { g in expenses.contains { $0.expense.group == g.group.id } }, id: \.group.id) { g in
                    chip(g.group.name, .group(g.group.id))
                }
            }
        }
    }

    private func chip(_ title: String, _ value: ExpenseSections.Filter) -> some View {
        let on = filter == value
        return Button { filter = value } label: {
            Text(verbatim: title)
                .font(TypeRole.label(14))
                .padding(.horizontal, 14).padding(.vertical, 8)
                .themePill(on ? .selected : .unselected, onPage: true)
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(on ? .isSelected : [])
    }

    @ViewBuilder
    private func sectionHeader(_ section: ExpenseSections.Section) -> some View {
        let named = groups.first { $0.group.id == section.groupID }
        let header = HStack(spacing: 10) {
            if let named {
                GroupTile(group: named.group, size: 30)
            } else if section.groupID == nil {
                SpendingTile(name: myName, avatar: myAvatar, size: 30)
            }
            Text(verbatim: named?.group.name ?? ExpenseCopy.justMe)
                .font(TypeRole.name(17)).foregroundStyle(Tokens.text)
            Spacer()
            Text(verbatim: Self.countLabel(section.expenses.count))
                .font(TypeRole.body(TypeScale.groupCount)).foregroundStyle(Tokens.textDim)
            if section.groupID == nil { RowChevron(color: Tokens.textDim) }
        }
        .padding(.top, 6)
        .contentShape(Rectangle())
        if section.groupID == nil {
            // D-249 (decision 190): the Just me header opens Your spending.
            Button { openSpending = true } label: { header }
                .buttonStyle(.plain)
                .accessibilityElement(children: .combine)
                .accessibilityAddTraits(.isHeader)
                .accessibilityHint(Text(verbatim: SpendingCopy.hint))
        } else {
            header
                .accessibilityElement(children: .combine)
                .accessibilityAddTraits(.isHeader)
        }
    }

    /// The long-press menu: the detail page's two actions, in place.
    @ViewBuilder
    private func menu(_ expense: ProjectedExpenseOut) -> some View {
        Button {
            startEdit(expense)
        } label: {
            Label("Edit", systemImage: "pencil")
        }
        .disabled(!ExpenseDeleteCopy.canEdit(group: expense.expense.group, named: group(of: expense)))
        Button(role: .destructive) {
            confirmingDelete = expense
        } label: {
            Label("Delete", systemImage: "trash")
        }
    }

    private func startEdit(_ expense: ProjectedExpenseOut) {
        let named = group(of: expense)
        guard ExpenseDeleteCopy.canEdit(group: expense.expense.group, named: named) else { return }
        editing = ExpenseEditTarget(expense: expense, named: named, store: store)
    }

    // MARK: - Delete and Undo

    private func delete(_ expense: ProjectedExpenseOut) {
        do {
            try store.reviseExpense(ExpenseEditing.delete(expense, editor: group(of: expense)?.selfId))
            CloudSync.shared.ledgerChanged()
            // D-248 (U30.4): the Undo is the toast, as from every detail page.
            ExpenseUndo.offer(expense, store: store, editor: group(of: expense)?.selfId)
            actionError = nil
            load()
        } catch {
            actionError = "\(error)"
        }
    }

    /// §8.1's category as a row micro-label, resolved from the default
    /// tree. Unknown ids show nothing, never a guess.
    /// Visible to the test target: the label path IS the feature.
    static func categoryLabelForTesting(for id: String?) -> String? {
        categoryLabel(for: id)
    }

    private static func categoryLabel(for id: String?) -> String? {
        guard let id else { return nil }
        let tree = ffiCategoryTreeDefault()
        // An id the tree does not know (or a parent that is missing) is
        // BLANK, never a guess. §8.2's rule for labels too.
        guard let leaf = tree.categories.first(where: { $0.id == id }),
              leaf.parent != nil else { return nil }
        let parent = tree.categories.first { $0.id == leaf.parent }
        guard let parent else { return nil }
        return "\(parent.name)\(Separator.meta)\(leaf.name)"
    }

    private func row(_ projected: ProjectedExpenseOut) -> some View {
        let expense = projected.expense
        let amount = (try? ffiMoneyFormat(
            minorUnits: expense.paidMinor,
            currencyCode: expense.currencyCode,
            locale: AppLocale.current
        )) ?? Placeholder.none
        let spoken = (try? ffiMoneySpoken(
            minorUnits: expense.paidMinor,
            currencyCode: expense.currencyCode,
            locale: AppLocale.current
        )) ?? ""

        // D-181: what it was, who paid and when; on the right, what it does
        // to you (core's your_effect) above the whole amount.
        let viewer = group(of: projected)?.selfId
        let effect = viewer.flatMap { try? ffiYourEffect(expense: expense, viewer: $0) }
        let justMe = expense.group == nil
        // D-249: a Just me row says what it cost you ("spent ₹381.00"), core's own amount; a group's row, its effect.
        let effectText = justMe ? SpendingCopy.spentAmount(amount) : effect.map {
            ExpenseCopy.effect($0.amountMinor, (try? ffiMoneyFormat(
                minorUnits: Int64($0.amountMinor.magnitude), currencyCode: $0.currencyCode, locale: AppLocale.current
            )) ?? Placeholder.none)
        }
        let effectColour = justMe ? Tokens.textDim : ((effect?.amountMinor ?? 0) > 0 ? Tokens.owing
            : ((effect?.amountMinor ?? 0) < 0 ? Tokens.owed : Tokens.textDim))
        let payerName = justMe ? ExpenseCopy.you : name(expense.payer)
        let title = projected.note?.isEmpty == false ? projected.note! : (Self.categoryLabel(for: projected.category) ?? String(localized: "Expense"))
        // D-234 (L12-d): 8 between the words and the effect (the 8 minimum alone, as Android's row), the effect measured
        // first (Android measures it before the weighted words).
        return HStack(alignment: .top, spacing: 0) {
            VStack(alignment: .leading, spacing: 3) {
                Text(verbatim: title)
                    .font(TypeRole.name(15)).foregroundStyle(Tokens.text).lineLimit(2)
                Text(verbatim: ExpenseCopy.paidBy(payerName, amount))
                    .font(TypeRole.body(13)).foregroundStyle(Tokens.textDim)
                // One line of text, so it wraps as a sentence, not as columns.
                let detail = [
                    projected.timestampMs.map { Self.dateString($0, locale: AppLocale.current) },
                    projected.note?.isEmpty == false ? Self.categoryLabel(for: projected.category) : nil,
                ].compactMap { $0 }
                if !detail.isEmpty {
                    Separator.text(detail, size: 12)
                        .font(TypeRole.body(12)).foregroundStyle(Tokens.textFaint)
                }
                // D-142: SPEC §6.1's open question, flagged where it is seen.
                if !projected.moneyConflict.isEmpty {
                    Text("Choose a version")
                        .microLabelFont()
                        .foregroundStyle(Tokens.owed)
                }
            }
            Spacer(minLength: 8)
            if let effectText {
                Text(verbatim: effectText)
                    .font(TypeRole.numeric(14, weight: 600))
                    .foregroundStyle(effectColour)
                    .multilineTextAlignment(.trailing)
                    .lineLimit(2)
                    .layoutPriority(1)
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(
            "\(projected.note ?? "Expense"). Paid by \(payerName). \(spoken)."
                + (effectText.map { " \($0)." } ?? "")
                + (projected.moneyConflict.isEmpty ? "" : " Devices entered different versions; choose one.")
        )
    }

    /// Locale-driven via AppLocale (D-54): dates format in Swift as
    /// presentation, but never bypass the locale axis the way the
    /// hardcoded "d MMM yyyy" did.
    /// D-135 fixed "dMMMmy" (minute for month) here; D-144 moved every
    /// date into `AppDate` so the copies cannot drift again.
    static func dateString(_ ms: UInt64, locale: LocaleTag) -> String {
        AppDate.day(ms, locale: locale)
    }

    private func load() {
        let store = self.store
        DispatchQueue.global(qos: .userInitiated).async {
            do {
                // D-142: core's projection, deleted expenses left out. It runs
                // the one-time legacy backfill first, so a vault from before
                // records existed gets ids to edit by.
                let loaded = try store.projectedExpenses().expenses.filter { !$0.deleted }
                // A migrated vault still waiting for your name lists payers as
                // "Unknown member" until the name is given on Balance. That is
                // display only; nothing is written.
                let named: [NamedGroupWire]
                do {
                    named = try store.namedGroups()
                } catch LedgerStore.GroupStoreError.needsYourName {
                    named = []
                }
                let me = try? store.loadSelf()
                let avatar = (try? store.loadAvatar()) ?? .none
                DispatchQueue.main.async {
                    myName = me?.name
                    myAvatar = avatar
                    groups = named
                    expenses = loaded
                    loadError = nil
                }
            } catch {
                DispatchQueue.main.async { loadError = "\(error)" }
            }
        }
    }
}
