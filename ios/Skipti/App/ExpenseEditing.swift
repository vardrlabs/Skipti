import Foundation

/// D-142: what Edit, Delete, Undo and the amount-conflict prompt WRITE, kept
/// out of the views so tests reach it. SPEC §5.7: an edit is a new version
/// and a delete a tombstone, both through D-141's revision records. SPEC
/// §6.1: an amount conflict is put to the person, never resolved silently.
///
/// Every revision carries the `seen` of the projection it was made from, so
/// core can tell an edit that saw another from one that raced it. No money
/// arithmetic happens here (ADR-0002): amounts come from core as digits,
/// shares from `ffiSplitCompute`, and the only comparisons are between
/// core's own outputs.
enum ExpenseEditing {
    /// The Add form's starting state for an edit.
    struct Prefill: Equatable {
        var amountText: String
        var currency: String
        var note: String
        var categoryID: String?
        var payer: String
        var participants: Set<String>
        var kind: AddExpenseScreen.SplitKind
        /// Each person's stored share as digits; filled only for `.exact`.
        var exactEntries: [String: String]
    }

    /// The form a stored expense opens in. The split TYPE is not stored,
    /// only the shares it produced, so it is re-derived: if core's payer-only
    /// or equal split over the same people gives exactly these shares, the
    /// form opens as that type, so a changed amount re-splits the way the
    /// person expects; anything else opens as Exact with each stored share,
    /// which reproduces it to the minor unit.
    static func prefill(_ p: ProjectedExpenseOut) throws -> Prefill {
        let e = p.expense
        let roster = e.shares.map(\.id).sorted()
        let stored = normalised(e.shares)
        func reproduces(_ spec: SplitSpecInput) -> Bool {
            guard let computed = try? ffiSplitCompute(
                totalMinor: e.paidMinor, currencyCode: e.currencyCode, members: roster, spec: spec
            ) else { return false }
            return normalised(computed.map { IdAmount(id: $0.id, amountMinor: $0.minorUnits) }) == stored
        }
        var kind = AddExpenseScreen.SplitKind.exact
        var exact: [String: String] = [:]
        if reproduces(.payerOnly(payer: e.payer)) {
            kind = .payerOnly
        } else if reproduces(.equal) {
            kind = .equal
        } else {
            for share in e.shares {
                exact[share.id] = try ffiMoneyInputDigits(
                    minorUnits: share.amountMinor, currencyCode: e.currencyCode
                )
            }
        }
        return Prefill(
            amountText: try ffiMoneyInputDigits(minorUnits: e.paidMinor, currencyCode: e.currencyCode),
            currency: e.currencyCode,
            note: p.note ?? "",
            categoryID: p.category,
            payer: e.payer,
            participants: Set(roster),
            kind: kind,
            exactEntries: exact
        )
    }

    /// The revision an edit writes: only the fields that changed (SPEC §6.1's
    /// per-field registers, so a note left alone never overwrites a note
    /// another device changed meanwhile), or nil when nothing did.
    static func revision(
        editing p: ProjectedExpenseOut,
        money: ExpenseInput,
        note: String,
        category: String?,
        editor: String?
    ) -> ExpenseRevisionIn? {
        var newMoney = money
        // An edit never moves an expense between groups (D-141).
        newMoney.group = p.expense.group
        // A frozen conversion describes one amount in one currency (§5.2); a
        // new amount or currency leaves it behind.
        let sameAmount = newMoney.paidMinor == p.expense.paidMinor
            && newMoney.currencyCode == p.expense.currencyCode
        newMoney.conversion = sameAmount ? p.expense.conversion : nil
        let moneyChanged = !sameMoney(newMoney, p.expense)
        let newNote: String? = note.isEmpty ? nil : note
        let noteChanged = newNote != p.note
        let categoryChanged = category != p.category
        guard moneyChanged || noteChanged || categoryChanged else { return nil }
        return ExpenseRevisionIn(
            target: p.id,
            seen: p.seen,
            editor: editor,
            money: moneyChanged ? newMoney : nil,
            // "" clears (core's rule): a removed note must be WRITTEN, or the
            // old one would survive the edit.
            note: noteChanged ? (newNote ?? "") : nil,
            category: categoryChanged ? (category ?? "") : nil,
            deleted: nil
        )
    }

    /// The tombstone (SPEC §5.7): the expense leaves balances and the list,
    /// and stays in the records, recoverable.
    static func delete(_ p: ProjectedExpenseOut, editor: String?) -> ExpenseRevisionIn {
        ExpenseRevisionIn(
            target: p.id, seen: p.seen, editor: editor,
            money: nil, note: nil, category: nil, deleted: true
        )
    }

    /// Undo: `deleted` is a register, so bringing an expense back is one more
    /// revision, written from the projection that holds the delete.
    static func undo(_ p: ProjectedExpenseOut, editor: String?) -> ExpenseRevisionIn {
        ExpenseRevisionIn(
            target: p.id, seen: p.seen, editor: editor,
            money: nil, note: nil, category: nil, deleted: false
        )
    }

    /// SPEC §6.1: keeping one amount is a money edit that saw EVERY competing
    /// edit, so it is the only head left and the conflict ends on every
    /// device that receives it.
    static func keep(
        _ choice: ExpenseInput, of p: ProjectedExpenseOut, editor: String?
    ) -> ExpenseRevisionIn {
        var money = choice
        money.group = p.expense.group
        return ExpenseRevisionIn(
            target: p.id, seen: p.seen, editor: editor,
            money: money, note: nil, category: nil, deleted: nil
        )
    }

    /// SPEC §6.1's sentence, verbatim for two devices: "Two devices set
    /// different amounts. Keep ₹1,200 or ₹1,250?" Amounts come from core.
    /// When the competing versions share an amount (a different payer or
    /// split), naming amounts would ask "₹1,200 or ₹1,200?", so it says so.
    static func conflictPrompt(_ p: ProjectedExpenseOut, locale: LocaleTag) -> String? {
        let options = p.moneyConflict
        guard options.count >= 2 else { return nil }
        let who = options.count == 2
            ? String(localized: "Two devices")
            : String(localized: "\(options.count) devices")
        let amounts = options.map { amount($0, locale: locale) }
        guard Set(amounts).count == amounts.count, let last = amounts.last else {
            return String(localized: "\(who) changed who paid or how it is split. Keep which?")
        }
        let list = amounts.dropLast().joined(separator: ", ")
        return String(localized: "\(who) set different amounts. Keep \(list) or \(last)?")
    }

    /// The label on one option's button: its amount, unless the amounts
    /// cannot tell the options apart.
    static func keepLabel(_ option: ExpenseInput, in p: ProjectedExpenseOut, locale: LocaleTag) -> String {
        let amounts = p.moneyConflict.map { amount($0, locale: locale) }
        guard Set(amounts).count == amounts.count else { return String(localized: "Keep this one") }
        return String(localized: "Keep \(amount(option, locale: locale))")
    }

    static func amount(_ money: ExpenseInput, locale: LocaleTag) -> String {
        (try? ffiMoneyFormat(
            minorUnits: money.paidMinor, currencyCode: money.currencyCode, locale: locale
        )) ?? Placeholder.none
    }

    /// Shares compared as a set of (person, amount): the same split in a
    /// different order is the same split.
    private static func normalised(_ shares: [IdAmount]) -> [IdAmount] {
        shares.sorted { ($0.id, $0.amountMinor) < ($1.id, $1.amountMinor) }
    }

    private static func sameMoney(_ a: ExpenseInput, _ b: ExpenseInput) -> Bool {
        a.payer == b.payer && a.paidMinor == b.paidMinor && a.currencyCode == b.currencyCode
            && a.conversion == b.conversion && normalised(a.shares) == normalised(b.shares)
    }
}

/// D-142: an expense opened for editing, with its OWN group (names, and who
/// "you" are there), which need not be the group selected on Balance, and
/// the store it lives in.
struct ExpenseEditTarget: Identifiable {
    let expense: ProjectedExpenseOut
    let named: NamedGroupWire
    let store: LedgerStore
    var id: String { expense.id }
}
