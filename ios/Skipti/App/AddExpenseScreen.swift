import SwiftUI

/// D-183: the Add sheet's words (the founder's canvas); Android's twin is
/// `AddSheetCopy` in AddExpenseScreen.kt.
/// D-235 round 3 (decision 110 a): core's free-text bound (`check::TEXT_MAX_CHARS`, counted in Unicode scalars as
/// Rust's `chars()` counts them) on the note field of Add and Edit expense. A longer note is cut at the last whole
/// character (grapheme) that keeps it within the bound, so an emoji is never split. Android's twin caps the same field.
enum NoteLimit {
    static let maxScalars = 1_000

    static func capped(_ text: String) -> String {
        guard text.unicodeScalars.count > maxScalars else { return text }
        var out = ""
        var scalars = 0
        for character in text {
            let n = character.unicodeScalars.count
            if scalars + n > maxScalars { break }
            out.append(character)
            scalars += n
        }
        return out
    }

    /// Within core's bound (nil passes).
    static func fits(_ note: String?) -> Bool { (note?.unicodeScalars.count ?? 0) <= maxScalars }

    /// The store's refusal (decision 112): core's own wording for the same rule, into the save-error state.
    static func check(_ note: String?) throws {
        guard fits(note) else { throw CoreError.InvalidInput("a text longer than \(maxScalars) characters") }
    }
}

enum AddSheetCopy {
    static let howMuch = "How much?"
    static let whatFor = "What was it for?"
    static let group = "Group"
    // D-225 T6 (Design 11): the split card's label and its two choice rows (the choice pages' titles), Android's words.
    static let split = "Split"
    static let splitType = "Split type"
    static let paidBy = "Paid by"

    /// D-225 T6 (A6, A8, the founder 2026-10-17): the four hints under the split card, in the kicker style, one table on
    /// both phones (CategoryWiringTests reads Android's AddSheetCopy for the same four). No dashes.
    static let sharesHint = "WEIGHTS, NOT AMOUNTS: 2 ADULTS + 1 CHILD AS 2:2:1"
    static let payerOnlyHint = "One person paid, nobody owes: logged in the group"
    static let percentageHint = "Percentages must add up to 100"
    static let adjustmentHint = "Equal split, plus or minus each adjustment"

    /// The hint a split kind shows under the split card; nil for the kinds that need none (Equal, Exact, Itemised).
    static func hint(_ kind: AddExpenseScreen.SplitKind) -> String? {
        switch kind {
        case .percentage: return percentageHint
        case .shares: return sharesHint
        case .adjustment: return adjustmentHint
        case .payerOnly: return payerOnlyHint
        case .equal, .exact, .itemised: return nil
        }
    }

    /// The live line under the split: what this expense does for you.
    static func summary(effect: Int64, formatted: String) -> String {
        effect > 0 ? "You’ll get back \(formatted)"
            : (effect < 0 ? "You’ll owe \(formatted)" : "Nothing changes for you")
    }
    /// Core's effect of the expense as it stands (ffiYourEffect), for you;
    /// nil when core cannot say.
    static func summary(_ expense: ExpenseInput, viewer: String, locale: LocaleTag = AppLocale.current) -> String? {
        guard let effect = try? ffiYourEffect(expense: expense, viewer: viewer),
              let formatted = try? ffiMoneyFormat(
                  minorUnits: abs(effect.amountMinor), currencyCode: effect.currencyCode, locale: locale
              ) else { return nil }
        return summary(effect: effect.amountMinor, formatted: formatted)
    }
}

/// D-225 (Design 11): the Group row on Add and Edit expense. A new expense with more than one group to file it under
/// gets a chooser (a value with a chevron, opening the choice page); a new expense with one group, or any edit (an edit
/// stays in its own group, D-142), shows the group as a plain value; no group, no row. Android's twin is
/// `AddExpenseRows` in AddExpenseScreen.kt.
enum AddExpenseRows {
    enum GroupRow: Equatable {
        case chooser, plain, none
    }

    static func groupRow(editing: Bool, groupCount: Int) -> GroupRow {
        if editing { return .plain }
        if groupCount > 1 { return .chooser }
        return groupCount == 1 ? .plain : .none
    }

    /// D-234 (L7, decision 87): the line a new expense shows when no group can take it: no groups at all, or only groups
    /// this phone can't add to (read-only copies); nil when one can. Android's twin is `AddExpenseRows.emptyLine`.
    static let noGroups = "Expenses belong to a group. Make one on Groups first."
    static let noneWritable = "You can't add to your groups from this phone. Make a new group on Groups to add one."

    static func emptyLine(groupCount: Int, writableCount: Int) -> String? {
        if writableCount > 0 { return nil }
        return groupCount == 0 ? noGroups : noneWritable
    }
}

/// D-184: why a split cannot be saved, in words, with core's amounts. A
/// tester read "expected=3000000, got=2500000" (core's message, in minor
/// units) when four exact amounts came to ₹25,000 of ₹30,000. Android's twin
/// is `SplitWords` in AddExpenseScreen.kt.
enum SplitWords {
    /// The words for a split core refused; nil for an error that is not
    /// about the split.
    static func message(_ e: CoreError, currency: String, locale: LocaleTag = AppLocale.current) -> String? {
        func money(_ minor: Int64) -> String {
            (try? ffiMoneyFormat(minorUnits: minor, currencyCode: currency, locale: locale)) ?? Placeholder.none
        }
        switch e {
        case let .ExactSumMismatch(expected, got): return sums("amounts", expected, got, money)
        case let .TotalMismatch(expected, got): return sums("items", expected, got, money)
        case let .PercentMismatch(gotBps):
            return "The percentages add up to \(percent(gotBps)). They need to make 100%."
        case .EmptyParticipants: return "Choose at least one person to split with."
        case .DuplicateParticipant: return "Someone is in this split twice."
        case .UnknownParticipant: return "Someone in this split is not in the group."
        case .NonPositiveWeight: return "Every share and percentage needs to be more than zero."
        case .NegativeTotal: return "The amount cannot be below zero."
        case .DeltasExceedTotal: return "The adjustments come to more than the whole expense."
        case .LineAmountNonPositive: return "Every item needs an amount above zero."
        case .EmptyLineAssignment: return "Every item needs at least one person."
        case .Overflow: return "That amount is too large."
        default: return nil
        }
    }

    /// The words while the split does not add up yet, for the live line;
    /// nil for anything else (those wait for Save).
    static func live(_ e: Error, currency: String, locale: LocaleTag = AppLocale.current) -> String? {
        guard let e = e as? CoreError else { return nil }
        switch e {
        case .ExactSumMismatch, .TotalMismatch, .PercentMismatch:
            return message(e, currency: currency, locale: locale)
        default: return nil
        }
    }

    private static func sums(_ what: String, _ expected: Int64, _ got: Int64, _ money: (Int64) -> String) -> String {
        got < expected
            ? "The \(what) add up to \(money(got)) of \(money(expected)). \(money(expected - got)) is still to assign."
            : "The \(what) add up to \(money(got)), \(money(got - expected)) more than the \(money(expected)) spent."
    }

    /// Basis points as a percentage: 9000 -> "90%", 9050 -> "90.5%". Whole
    /// numbers only; no float (ADR-0002).
    static func percent(_ bps: UInt64) -> String {
        let whole = bps / 100
        let rest = bps % 100
        if rest == 0 { return "\(whole)%" }
        if rest % 10 == 0 { return "\(whole).\(rest / 10)%" }
        return "\(whole)." + (rest < 10 ? "0" : "") + "\(rest)%"
    }
}

/// §5.3/§5.4's add-expense core loop: a Transaction with a Split. Amounts
/// enter as DECIMAL STRINGS and become integer minor units through
/// `ffiMoneyParseMinor`, never through a float (ADR-0002). Split types
/// are core's full SplitSpec enum (D-42); shares are COMPUTED by core
/// (`ffiSplitCompute`) and persisted through the vault loop, so the
/// balance line recomputes from what was persisted.
struct AddExpenseScreen: View {
    @Environment(\.dismiss) private var dismiss

    /// D-128: what happens after a successful save. Inside the iOS tab
    /// shell this screen is a tab ROOT, where `dismiss()` does nothing:
    /// the saved expense would sit behind a form that never went away.
    /// The shell passes a hook that switches to Balance, matching Android.
    /// Arg-mounted (`--add`) it stays nil and the old dismiss applies.
    var onSaved: (() -> Void)?

    /// D-142: set when this form EDITS a stored expense (from the Expenses
    /// list or its detail page) instead of adding one. It opens filled in,
    /// in the expense's own group, and saving writes one revision holding
    /// only what changed (`ExpenseEditing.revision`), or nothing at all.
    let editing: ExpenseEditTarget?

    init(onSaved: (() -> Void)? = nil, editing: ExpenseEditTarget? = nil) {
        self.onSaved = onSaved
        self.editing = editing
    }

    enum SplitKind: String, CaseIterable, Identifiable {
        case equal = "Equal"
        case exact = "Exact"
        case percentage = "Percentage"
        case shares = "Shares"
        case adjustment = "Adjustment"
        case itemised = "Itemised"
        case payerOnly = "Payer only"
        var id: String { rawValue }
    }

    struct ItemLineDraft: Identifiable {
        let id = UUID()
        var amount = ""
        var who: Set<String> = []
    }

    static let promotedCurrencies = ["INR", "USD", "EUR", "GBP", "JPY", "AED", "SGD", "AUD", "CAD"]

    @State private var amountText = ""
    /// D-165: a fresh form starts in the default currency; a group's own
    /// settlement currency replaces it once the group loads.
    @State private var currency = DefaultCurrency.current
    @State private var note = ""
    @State private var kind: SplitKind = .equal
    @State private var participants: Set<String> = []
    /// D-135: the selected group's people and who paid. No fixture people.
    @State private var named: NamedGroupWire?
    @State private var payer: String = ""
    @State private var exactEntries: [String: String] = [:]
    @State private var percentageEntries: [String: String] = [:]
    @State private var shareEntries: [String: String] = [:]
    @State private var adjustmentEntries: [String: String] = [:]
    @State private var itemLines: [ItemLineDraft] = []
    @State private var taxText = ""
    @State private var tipText = ""
    @State private var error: String?
    @State private var saved = false
    /// D-142: an edit is filled in once, not again on every appearance.
    @State private var prefilled = false
    /// §8.1's category: picked from the default tree's assignable leaves.
    /// Stored as the leaf ID on the record; the list resolves the name.
    @State private var selectedCategoryID: String?
    /// D-181: the first of the two steps; its sub-categories follow.
    @State private var categoryParent: String?
    private let categoryTree = CategoryLevels.tree
    /// D-183: every group, for the Group picker inside the sheet.
    @State private var groups: [NamedGroupWire] = []
    /// D-234 (L7): every group, read-only copies too: whether "none can take it" or "there are none".
    @State private var groupCount = 0
    /// D-225 T6 (Design 11): the currency list page the pill opens (its "Common" section holds the promoted codes, so
    /// the quick menu went).
    @State private var listingCurrencies = false

    private var title: String {
        editing == nil ? String(localized: "Add expense") : String(localized: "Edit expense")
    }

    private var saveHint: String {
        editing == nil
            ? String(localized: "Records this expense in your ledger. It does not move money.")
            : String(localized: "Records this change in your ledger. The earlier version is kept.")
    }

    private var members: [(id: String, name: String)] {
        guard let named else { return [] }
        return named.members.map { ($0.id, LedgerStore.label(for: $0.id, in: named)) }
    }

    var body: some View {
        // D-225 T6 (Design 11, A1): the theme's list cards on its ground, as Android's, not a system Form (its sections
        // stayed rounded in Norse and Vardr Labs). Every choice is a value row opening its choice page. GUARANTEES #2:
        // the default path is unchanged (the amount, then Add expense: you paid, everyone in, Equal).
        ScrollView {
            VStack(alignment: .leading, spacing: 10) {
                if named == nil, error == nil, let line = AddExpenseRows.emptyLine(groupCount: groupCount, writableCount: groups.count) {
                    Text(verbatim: line)
                        .font(TypeRole.body())
                        .foregroundStyle(Tokens.textDim)
                }
                amountBlock
                // What it was for, its category in two steps (D-181: the category, then only its own sub-categories;
                // only a sub-category is assigned, SPEC 8.1), and the group (D-183: what it was, then where it goes).
                ListCard {
                    FieldRow(AddSheetCopy.whatFor, text: $note)
                        // D-235 round 3 (decision 110 a): the field stops at core's bound, so Add and Edit expense
                        // can never hold a note core refuses.
                        .onChange(of: note) { _, typed in
                            let capped = NoteLimit.capped(typed)
                            if capped != typed { note = capped }
                        }
                    NavigationLink {
                        ChoicePage(title: CategoryCopy.category, options: categoryOptions, chosen: categoryParent) { categoryParent = $0 }
                    } label: {
                        ListRow(title: CategoryCopy.category,
                                trailing: .value(categoryParent.map { CategoryLevels.name($0, in: categoryTree) } ?? CategoryCopy.none, chevron: true))
                    }
                    .buttonStyle(.plain)
                    if let categoryParent {
                        // Leaves only: no "Choose one" option (picking it would do nothing), as Android's page.
                        NavigationLink {
                            ChoicePage(title: CategoryCopy.subcategory,
                                       options: CategoryLevels.leaves(of: categoryParent, in: categoryTree).map { ChoiceOption(value: $0.id, label: $0.name) },
                                       chosen: selectedCategoryID) { selectedCategoryID = $0 }
                        } label: {
                            ListRow(title: CategoryCopy.subcategory,
                                    trailing: .value(selectedCategoryID.map { CategoryLevels.name($0, in: categoryTree) } ?? CategoryCopy.choose, chevron: true))
                        }
                        .buttonStyle(.plain)
                    }
                    groupRow
                }
                if let categoryParent, selectedCategoryID == nil {
                    CardCaption(CategoryCopy.hint(CategoryLevels.name(categoryParent, in: categoryTree)))
                }

                header(AddSheetCopy.split)
                // The kind and who paid (choice pages), one switch row per person (everyone in by default), then the
                // kind's own fields, only for people in the split, in the group's order. Core computes the shares.
                ListCard {
                    NavigationLink {
                        ChoicePage(title: AddSheetCopy.splitType, options: SplitKind.allCases.map { ChoiceOption(value: $0, label: $0.rawValue) },
                                   chosen: kind) { kind = $0 }
                    } label: {
                        ListRow(title: AddSheetCopy.splitType, trailing: .value(kind.rawValue, chevron: true))
                    }
                    .buttonStyle(.plain)
                    memberToggles
                    kindFields
                }
                // A6, A8: the kind's hint in the kicker style, from the one table.
                if let hint = AddSheetCopy.hint(kind) {
                    Text(verbatim: hint)
                        .microLabelFont()
                        .foregroundStyle(Tokens.textDim)
                }
                if kind == .itemised { itemisedCards }

                // D-183: what this expense does for you, as it stands (core's).
                if let summaryLine {
                    Text(verbatim: summaryLine)
                        .font(TypeRole.body(TypeScale.prose))
                        .foregroundStyle(Tokens.textDim)
                        .frame(maxWidth: .infinity, alignment: .center)
                        .multilineTextAlignment(.center)
                }
                if let splitProblem {
                    Text(verbatim: splitProblem)
                        .font(TypeRole.body(TypeScale.prose))
                        .foregroundStyle(Tokens.owed)
                        .frame(maxWidth: .infinity, alignment: .center)
                        .multilineTextAlignment(.center)
                }

                // The live line already says it; Save's refusal does not repeat it.
                if let error, error != splitProblem {
                    Text(verbatim: error)
                        .font(TypeRole.body())
                        .foregroundStyle(Tokens.owed)
                        .accessibilityLabel(Text(verbatim: "Could not save. \(error)"))
                }

                Text("Recorded in your ledger. It does not move money.")
                    .microLabelFont()
                    .foregroundStyle(Tokens.textDim)
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 16)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .auroraBackground()
        // D-183: the decimal pad has no Done key; a scroll puts it away, so
        // the summary line under the split can be read.
        .scrollDismissesKeyboard(.interactively)
        // D-128: the Add tab's one action. D-139: under the form, not in it.
        .formActionBar {
            // D-224 (Design 8): no saved-label phase: the toast says it, and the sheet closes at once.
            Button(editing == nil ? "Add expense" : "Save changes") { save() }
                .buttonStyle(PrimaryButtonStyle())
                .disabled(amountText.isEmpty || saved || named == nil)
                .accessibilityHint(saveHint)
        }
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(.large)
        .navigationDestination(isPresented: $listingCurrencies) {
            CurrencyListScreen(selection: $currency)
        }
        .onChange(of: categoryParent) { _, parent in
            selectedCategoryID = CategoryLevels.keep(selectedCategoryID, under: parent, in: categoryTree)
        }
        .task { loadGroup() }
    }

    /// D-183 (the founder's canvas): the amount first, big and centred, its currency on a pill. D-225 T6 (Design 11):
    /// the pill opens the currency list page at once, as Android's does.
    private var amountBlock: some View {
        VStack(spacing: 6) {
            Text(verbatim: AddSheetCopy.howMuch)
                .font(TypeRole.body(14))
                .foregroundStyle(Tokens.textDim)
            TextField("0", text: $amountText)
                .keyboardType(.decimalPad)
                .multilineTextAlignment(.center)
                .font(TypeRole.numeric(48, weight: 700))
                .foregroundStyle(Tokens.text)
                .minimumScaleFactor(0.5)
                .accessibilityLabel("Amount")
            Button { listingCurrencies = true } label: {
                Text(verbatim: currency)
                    .font(TypeRole.label(13))
                    .padding(.horizontal, 12)
                    .padding(.vertical, 4)
                    .themePill(.accent, onPage: true)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(Text(verbatim: "Currency, \(currency)"))
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 8)
    }

    /// The category page's options: None first, then the default tree's categories.
    private var categoryOptions: [ChoiceOption<String?>] {
        [ChoiceOption(value: nil, label: CategoryCopy.none)]
            + CategoryLevels.parents(categoryTree).map { ChoiceOption(value: $0.id, label: $0.name) }
    }

    /// D-135: the selected group. You pay and everyone takes part by default.
    private func loadGroup() {
        // D-142: an edit opens in the expense's OWN group, filled in once.
        if let editing {
            named = editing.named
            if !prefilled { applyPrefill(editing) }
            return
        }
        let store = LedgerStore.defaultStore()
        do {
            // D-183: the sheet can file it under any group. D-210: not a
            // read-only copy, which takes nothing new.
            let copies = Set(try store.readOnlyChecked().keys)
            let all = try store.namedGroups()
            groupCount = all.count
            groups = all.filter { !copies.contains($0.group.id) }
            let shown = try store.currentGroup()
            let current = shown.flatMap { s in groups.first { $0.group.id == s.group.id } } ?? groups.first
            // Filled in once: coming back from All currencies runs this again,
            // and must keep the group, people and currency already chosen.
            if named == nil, let current {
                named = current
                payer = current.selfId
                if participants.isEmpty { participants = Set(current.group.members) }
                // A fresh form starts in the group's settlement currency (it
                // started in USD for an INR group, seen on the simulator).
                if amountText.isEmpty { currency = current.group.settlementCurrency }
            }
        } catch LedgerStore.GroupStoreError.needsYourName {
            error = String(localized: "Add your name on Balance first.")
        } catch {
            self.error = "\(error)"
        }
    }

    /// D-183: the Group row (D-225 T6, Design 11: `AddExpenseRows.groupRow`). A new expense with more than one group
    /// opens the group page (the shown group first); one group, or an edit (it stays in its own, D-142), shows it plainly.
    @ViewBuilder
    private var groupRow: some View {
        if let named {
            switch AddExpenseRows.groupRow(editing: editing != nil, groupCount: groups.count) {
            case .chooser:
                NavigationLink {
                    ChoicePage(title: AddSheetCopy.group, options: groups.map { ChoiceOption(value: $0.group.id, label: $0.group.name) },
                               chosen: named.group.id) { choose($0) }
                } label: {
                    ListRow(title: AddSheetCopy.group, trailing: .value(named.group.name, chevron: true))
                }
                .buttonStyle(.plain)
            case .plain:
                ListRow(title: AddSheetCopy.group, trailing: .value(named.group.name, chevron: false))
            case .none:
                EmptyView()
            }
        }
    }

    /// D-183: another group: its people, you paying, everyone in, and its
    /// currency unless one was picked for this expense.
    private func choose(_ id: String) {
        guard let next = groups.first(where: { $0.group.id == id }), next.group.id != named?.group.id else { return }
        let previousCurrency = named?.group.settlementCurrency
        named = next
        payer = next.selfId
        participants = Set(next.group.members)
        exactEntries = [:]
        percentageEntries = [:]
        shareEntries = [:]
        adjustmentEntries = [:]
        itemLines = []
        if amountText.isEmpty || currency == previousCurrency { currency = next.group.settlementCurrency }
    }

    /// D-183: the summary line, from core: the split as it stands, then
    /// ffiYourEffect. Nil while the form cannot be computed yet.
    private var summaryLine: String? {
        guard let named, !payer.isEmpty, !participants.isEmpty,
              let total = try? ffiMoneyParseMinor(digits: amountText, currencyCode: currency), total > 0
        else { return nil }
        let roster = participants.sorted()
        guard let spec = try? buildSpec(roster: roster),
              let computed = try? ffiSplitCompute(totalMinor: total, currencyCode: currency, members: roster, spec: spec)
        else { return nil }
        let expense = ExpenseInput(
            payer: payer, paidMinor: total, currencyCode: currency,
            shares: computed.map { IdAmount(id: $0.id, amountMinor: $0.minorUnits) },
            group: named.group.id, conversion: nil
        )
        return AddSheetCopy.summary(expense, viewer: named.selfId)
    }

    /// D-184: while the split does not add up, how far off it is (core's
    /// refusal, in words). Nil when it adds up or cannot be computed yet.
    private var splitProblem: String? {
        guard !participants.isEmpty,
              let total = try? ffiMoneyParseMinor(digits: amountText, currencyCode: currency), total > 0
        else { return nil }
        let roster = participants.sorted()
        guard let spec = try? buildSpec(roster: roster, blanksAsZero: true) else { return nil }
        do {
            _ = try ffiSplitCompute(totalMinor: total, currencyCode: currency, members: roster, spec: spec)
            return nil
        } catch {
            return SplitWords.live(error, currency: currency)
        }
    }

    /// A section label on the page ground: the theme's mark in `textDim`, as every section label (D-225 decision 26).
    private func header(_ text: String) -> some View {
        Text(verbatim: text)
            .sectionLabel()
            .foregroundStyle(Tokens.textDim)
            .accessibilityAddTraits(.isHeader)
    }

    /// The share fields' face (D-217: `TypeScale.shareInput`), one place for every field of the split.
    private var shareFont: Font { TypeRole.numeric(TypeScale.shareInput) }

    /// Paid by (a choice page of the group's people), then one switch row per person: in the split or not.
    @ViewBuilder
    private var memberToggles: some View {
        if !members.isEmpty {
            NavigationLink {
                ChoicePage(title: AddSheetCopy.paidBy, options: members.map { ChoiceOption(value: $0.id, label: $0.name) },
                           chosen: payer) { payer = $0 }
            } label: {
                ListRow(title: AddSheetCopy.paidBy, trailing: .value(members.first { $0.id == payer }?.name ?? "", chevron: true))
            }
            .buttonStyle(.plain)
        }
        ForEach(members, id: \.id) { member in
            ListRow(title: member.name, trailing: .toggle(Binding(
                get: { participants.contains(member.id) },
                set: { on in
                    if on {
                        participants.insert(member.id)
                    } else {
                        participants.remove(member.id)
                    }
                }
            )))
        }
    }

    /// The kind's own fields as rows of the split card, one per person in the split, with today's placeholders and
    /// keyboards. Itemised's lines follow the card (`itemisedCards`).
    @ViewBuilder
    private var kindFields: some View {
        switch kind {
        case .equal, .payerOnly, .itemised:
            EmptyView()
        case .exact:
            ForEach(members, id: \.id) { m in
                if participants.contains(m.id) {
                    FieldRow("Amount for \(m.name)", text: entryBinding($exactEntries, m.id), keyboard: .decimalPad, font: shareFont)
                }
            }
        case .percentage:
            ForEach(members, id: \.id) { m in
                if participants.contains(m.id) {
                    FieldRow("Percent for \(m.name)", text: entryBinding($percentageEntries, m.id), keyboard: .numberPad, font: shareFont)
                }
            }
        case .shares:
            ForEach(members, id: \.id) { m in
                if participants.contains(m.id) {
                    FieldRow("Shares for \(m.name)", text: entryBinding($shareEntries, m.id), keyboard: .numberPad, font: shareFont)
                }
            }
        case .adjustment:
            ForEach(members, id: \.id) { m in
                if participants.contains(m.id) {
                    FieldRow("Adjustment for \(m.name)", text: entryBinding($adjustmentEntries, m.id),
                             keyboard: .numbersAndPunctuation, font: shareFont)
                }
            }
        }
    }

    /// Itemised: a list card per line (its amount, then a switch row per person), "Add line" (secondary), and the Tax
    /// and Tip card. Android's twin is `SplitKindExtras`.
    @ViewBuilder
    private var itemisedCards: some View {
        ForEach(itemLines) { line in
            ListCard {
                FieldRow("Line amount", text: lineAmountBinding(line.id), keyboard: .decimalPad, font: shareFont)
                ForEach(members, id: \.id) { m in
                    ListRow(title: "Line for \(m.name)", trailing: .toggle(lineMemberBinding(line.id, member: m.id)))
                }
            }
        }
        Button("Add line") { itemLines.append(ItemLineDraft()) }
            .buttonStyle(SecondaryButtonStyle())
        ListCard {
            FieldRow("Tax", text: $taxText, keyboard: .decimalPad, font: shareFont)
            FieldRow("Tip", text: $tipText, keyboard: .decimalPad, font: shareFont)
        }
    }

    private func entryBinding(
        _ dictionary: Binding<[String: String]>,
        _ id: String
    ) -> Binding<String> {
        Binding(
            get: { dictionary.wrappedValue[id] ?? "" },
            set: { dictionary.wrappedValue[id] = $0 }
        )
    }

    private func lineAmountBinding(_ id: UUID) -> Binding<String> {
        Binding(
            get: { itemLines.first { $0.id == id }?.amount ?? "" },
            set: { value in
                if let index = itemLines.firstIndex(where: { $0.id == id }) {
                    itemLines[index].amount = value
                }
            }
        )
    }

    private func lineMemberBinding(_ id: UUID, member: String) -> Binding<Bool> {
        Binding(
            get: { itemLines.first { $0.id == id }?.who.contains(member) ?? false },
            set: { on in
                if let index = itemLines.firstIndex(where: { $0.id == id }) {
                    if on {
                        itemLines[index].who.insert(member)
                    } else {
                        itemLines[index].who.remove(member)
                    }
                }
            }
        )
    }

    /// The whole point: decimal STRING → core → integer minor units; spec →
    /// core → computed shares; expense → vault → persisted. No arithmetic
    /// happens in this file.
    private func save() {
        error = nil
        do {
            let total = try ffiMoneyParseMinor(digits: amountText, currencyCode: currency)
            guard total > 0 else {
                throw CoreError.InvalidInput("the amount must be positive")
            }
            let roster = participants.sorted()
            let spec = try buildSpec(roster: roster)
            let computed = try ffiSplitCompute(
                totalMinor: total,
                currencyCode: currency,
                members: roster,
                spec: spec
            )
            if let editing {
                try saveEdit(editing, total: total, computed: computed)
                return
            }
            let dir = FileManager.default
                .urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
                .appendingPathComponent("ledger", isDirectory: true)
            try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
            let store = LedgerStore(path: dir.appendingPathComponent("ledger-vault.db").path)
            guard let named, !payer.isEmpty else {
                throw CoreError.InvalidInput(String(localized: "Create a group on Balance first."))
            }
            let expense = LedgerStore.ExpenseDTO(
                payer: payer,
                paidMinor: total,
                currencyCode: currency,
                group: named.group.id,
                conversion: nil,
                shares: computed.map { .init(id: $0.id, amountMinor: $0.minorUnits) },
                note: note.isEmpty ? nil : note,
                timestampMs: UInt64(Date().timeIntervalSince1970 * 1000),
                category: selectedCategoryID,
                // D-141: §5.7's "added by".
                addedBy: named.selfId
            )
            _ = try store.appendExpense(expense)
            // §10.3: fire-and-forget. The expense is SAVED locally whether
            // or not the relay is reachable; sync never blocks this flow.
            CloudSync.shared.ledgerChanged()
            saved = true
            // D-224 (Design 8, GUARANTEES #2): the toast, then the sheet closes at once (the 0.6 s hold is gone).
            DoneCenter.shared.post(DoneMoments.expense(edited: false, note: note, paidMinor: total, currency: currency))
            if let onSaved { onSaved() } else { dismiss() }
        } catch let e as CoreError {
            error = SplitWords.message(e, currency: currency) ?? "\(e)"
        } catch {
            self.error = "\(error)"
        }
    }

    /// D-142: the stored expense as the form's starting state.
    private func applyPrefill(_ target: ExpenseEditTarget) {
        prefilled = true
        do {
            let form = try ExpenseEditing.prefill(target.expense)
            amountText = form.amountText
            currency = form.currency
            note = form.note
            selectedCategoryID = form.categoryID
            categoryParent = CategoryLevels.parent(of: form.categoryID, in: categoryTree)
            payer = form.payer
            participants = form.participants
            kind = form.kind
            exactEntries = form.exactEntries
        } catch {
            self.error = "\(error)"
        }
    }

    /// D-142: an edit writes ONE revision with only what changed, or nothing
    /// when nothing did. The date and the group stay the creation's.
    private func saveEdit(_ target: ExpenseEditTarget, total: Int64, computed: [ShareOut]) throws {
        let money = ExpenseInput(
            payer: payer,
            paidMinor: total,
            currencyCode: currency,
            shares: computed.map { IdAmount(id: $0.id, amountMinor: $0.minorUnits) },
            group: target.expense.expense.group,
            conversion: nil
        )
        if let revision = ExpenseEditing.revision(
            editing: target.expense, money: money, note: note,
            category: selectedCategoryID, editor: target.named.selfId
        ) {
            try target.store.reviseExpense(revision)
            CloudSync.shared.ledgerChanged()
        }
        saved = true
        // D-224 (Design 8, GUARANTEES #2): "Expense saved" with the new amount, then the sheet closes at once.
        DoneCenter.shared.post(DoneMoments.expense(edited: true, note: note, paidMinor: total, currency: currency))
        if let onSaved { onSaved() } else { dismiss() }
    }

    /// `blanksAsZero` (D-184) is the live line's: an exact amount not typed
    /// yet counts as nothing, so the line shows while the amounts are being
    /// filled in. Save passes false and still wants each one.
    private func buildSpec(roster: [String], blanksAsZero: Bool = false) throws -> SplitSpecInput {
        func minor(_ text: String?) throws -> Int64 {
            if blanksAsZero, (text ?? "").trimmingCharacters(in: .whitespaces).isEmpty { return 0 }
            return try ffiMoneyParseMinor(digits: text ?? "", currencyCode: currency)
        }
        switch kind {
        case .equal:
            return .equal
        case .payerOnly:
            return .payerOnly(payer: payer)
        case .exact:
            return .exact(entries: try roster.map { id in
                IdAmount(id: id, amountMinor: try minor(exactEntries[id]))
            })
        case .percentage:
            // Whole percents ("25") → basis points (2500). No floats; the
            // sum-to-100% rule is core's (D-03).
            return .percentage(entries: try roster.map { id in
                let text = percentageEntries[id] ?? ""
                guard let percent = Int(text), percent >= 0 else {
                    throw CoreError.InvalidInput(
                        "percentage must be a whole number of percent"
                    )
                }
                return IdBps(id: id, bps: UInt64(percent) * 100)
            })
        case .shares:
            return .shares(entries: roster.map { id in
                IdWeight(id: id, weight: UInt64(shareEntries[id] ?? "0") ?? 0)
            })
        case .adjustment:
            return .adjustment(entries: try roster.map { id in
                IdAmount(id: id, amountMinor: try minor(adjustmentEntries[id]))
            })
        case .itemised:
            return .itemised(
                lines: try itemLines.map { draft in
                    LineInput(
                        amountMinor: try minor(draft.amount),
                        participants: draft.who.sorted()
                    )
                },
                taxMinor: try minor(taxText),
                tipMinor: try minor(tipText)
            )
        }
    }
}
