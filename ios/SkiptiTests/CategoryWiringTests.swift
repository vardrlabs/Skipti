import XCTest
@testable import Skipti

/// §8 wiring, end to end at the model level: a category chosen on the add
/// screen persists on the record, survives close/reopen, round-trips
/// through the §9.2 envelope (schema v2), and renders as the list's
/// micro-label via the SAME default-tree lookup the screen uses.
final class CategoryWiringTests: XCTestCase {
    /// D-225 (Design 11): Add expense's Group row. A new expense with more than one group offers the chooser; one
    /// group or any edit (an edit stays in its own group, D-142) shows it plainly; no group, no row (Android's
    /// AddExpenseTest pins the same).
    func testTheGroupRowOffersAChooserOnlyToANewExpenseWithAChoice() {
        XCTAssertEqual(AddExpenseRows.groupRow(editing: true, groupCount: 2), .plain, "an edit never moves group")
        XCTAssertEqual(AddExpenseRows.groupRow(editing: true, groupCount: 1), .plain)
        XCTAssertEqual(AddExpenseRows.groupRow(editing: false, groupCount: 2), .chooser)
        XCTAssertEqual(AddExpenseRows.groupRow(editing: false, groupCount: 5), .chooser)
        XCTAssertEqual(AddExpenseRows.groupRow(editing: false, groupCount: 1), .plain)
        XCTAssertEqual(AddExpenseRows.groupRow(editing: false, groupCount: 0), .none)
    }

    /// D-234 (L7, decision 87): Add expense's line when no group can take a new expense: no groups at all, or only groups
    /// this phone can't add to (the D-229 look's lost-place group); none when one can. Android's twin:
    /// AddExpenseTest.add_expenses_empty_line_says_why.
    func testAddExpensesEmptyLineSaysWhy() {
        XCTAssertEqual(AddExpenseRows.emptyLine(groupCount: 0, writableCount: 0), "Expenses belong to a group. Make one on Groups first.")
        XCTAssertEqual(AddExpenseRows.emptyLine(groupCount: 1, writableCount: 0),
                       "You can't add to your groups from this phone. Make a new group on Groups to add one.")
        XCTAssertEqual(AddExpenseRows.emptyLine(groupCount: 3, writableCount: 0), AddExpenseRows.noneWritable)
        XCTAssertNil(AddExpenseRows.emptyLine(groupCount: 1, writableCount: 1))
        XCTAssertNil(AddExpenseRows.emptyLine(groupCount: 4, writableCount: 2))
    }

    /// D-135: an expense row shows day, month and year. The template carried a
    /// stray "m" (minute) from D-54, so rows read "Sep 16, 2026 at 24".
    func testExpenseDatesShowDayMonthAndYearOnly() {
        let ms: UInt64 = 1_789_615_440_000 // 2026-09-17T03:24:00Z
        for locale in [LocaleTag.enUs, LocaleTag.enIn] {
            let text = ExpenseListView.dateString(ms, locale: locale)
            XCTAssertTrue(text.contains("2026"), text)
            XCTAssertTrue(text.contains("Sep"), text)
            XCTAssertFalse(text.contains(":"), "no time of day: \(text)")
            XCTAssertFalse(text.contains(" at "), "no time of day: \(text)")
            XCTAssertFalse(text.contains("24"), "the minute must not appear: \(text)")
        }
    }

    private let groceriesId = String(repeating: "1", count: 32)

    private func tempStore() throws -> LedgerStore {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("catwiring-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return LedgerStore(path: dir.appendingPathComponent("v.db").path)
    }

    private func dto(category: String?) -> LedgerStore.ExpenseDTO {
        .init(
            payer: String(repeating: "a", count: 31) + "1",
            paidMinor: 42_000,
            currencyCode: "INR",
            group: nil,
            conversion: nil,
            shares: [.init(id: String(repeating: "a", count: 31) + "1", amountMinor: 42_000)],
            note: "weekly veg",
            timestampMs: 20_468 * 86_400_000,
            category: category
        )
    }

    /// Assign → append → CLOSE → reopen → the category is ON the record.
    func testAssignedCategorySurvivesTheVaultLoop() throws {
        let store = try tempStore()
        _ = try store.appendExpense(dto(category: groceriesId))

        let reloaded = try store.loadExpenses()
        XCTAssertEqual(reloaded.count, 1)
        XCTAssertEqual(reloaded[0].category, groceriesId)

        // And a record written before categories decodes as nil, the
        /// tolerance the add-screen and list both rely on:
        let legacyJSON = """
        [{"payer":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa1","paidMinor":1000,\
        "currencyCode":"INR","group":null,"shares":[],\
        "note":"old","timestampMs":0}]
        """
        let vault = try VaultGate.open(path: store.path, keystore: KeychainKeystore())
        defer { try? vault.closeVault() }
        try vault.kvSet(key: "ledger/expenses", value: Data(legacyJSON.utf8))
        let mixed = try store.loadExpenses()
        XCTAssertEqual(mixed.count, 1)
        XCTAssertNil(mixed[0].category)
    }

    /// §9.2: the category rides the envelope (schema v2 on) losslessly.
    func testCategoryRoundTripsThroughTheJsonEnvelope() throws {
        let store = try tempStore()
        _ = try store.appendExpense(dto(category: groceriesId))
        let snapshot = try store.snapshot()

        let json = try ffiExportLedgerJson(snapshot: snapshot)
        XCTAssertTrue(
            json.hasPrefix("{\"format\":\"skipti-ledger\",\"schema_version\":4,"),
            "category-bearing exports are the current schema (v4)"
        )
        let restored = try ffiImportLedgerJson(text: json)
        XCTAssertEqual(restored, snapshot, "lossless including the category")
        XCTAssertEqual(restored.expenses.first?.category, groceriesId)
    }

    /// The list's label path: id → "Parent · Child" through the default
    /// tree, as core names them (D-174: no longer uppercased); unknown ids show nothing.
    func testCategoryLabelResolvesAndUnknownIdsShowNothing() throws {
        let tree = ffiCategoryTreeDefault()
        let leaf = try XCTUnwrap(
            tree.categories.first { $0.parent != nil && !$0.archived && $0.name == "Groceries" }
        )
        let label = ExpenseListView.categoryLabelForTesting(for: leaf.id)
        XCTAssertEqual(label, "Food & Drink · Groceries")
        XCTAssertNil(ExpenseListView.categoryLabelForTesting(for: nil))
        XCTAssertNil(
            ExpenseListView.categoryLabelForTesting(for: String(repeating: "9", count: 32)),
            "an id the tree does not know is blank, never a guess"
        )
    }

    /// §8.2 live in the app path: the pipeline classifies through the
    /// FFI deterministically. Application to a record stays EXPLICIT.
    /// §8.2 proposes; the user's confirm is what writes.
    func testAutoCategoriseClassifiesThroughTheFfiPipeline() throws {
        let tree = ffiCategoryTreeDefault()
        let table = ffiMerchantTableBundled()
        let ctx = RuleContext(
            merchant: "Swiggy dinner",
            amountMinor: 45_000,
            currency: "INR",
            dayOfMonth: 12,
            direction: "expense",
            account: nil,
            source: nil
        )
        let assigned = try ffiAutoCategorise(tree: tree, table: table, rules: [], context: ctx)
        XCTAssertNotNil(assigned, "swiggy is in the bundled table")
        XCTAssertEqual(
            assigned,
            try ffiAutoCategorise(tree: tree, table: table, rules: [], context: ctx),
            "the same merchant classifies identically, every run"
        )
    }

    // ---- D-225 Task 6: Add expense in list cards (Design 11, A1, A6, A8) ----

    private func t6Source(_ path: String) throws -> String {
        let url = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent(path)
        return try String(contentsOf: url, encoding: .utf8)
    }

    private func t6Count(_ text: String, _ needle: String) -> Int { text.components(separatedBy: needle).count - 1 }

    /// D-225 (Design 11, A1): Add and Edit expense are the theme's list cards on its ground, never a system Form (its
    /// sections stayed rounded in Norse and Vardr Labs). No system Picker, Menu or Toggle: every choice is a value row
    /// opening its choice page, every person a drawn switch row, every field a `FieldRow`; the currency pill opens the
    /// currency list page at once; the action bar is unchanged (GUARANTEES #2: the default path is the amount, then Add
    /// expense).
    func testAddExpenseIsListCards() throws {
        let screen = try t6Source("Skipti/App/AddExpenseScreen.swift")
        // A call broken over lines reads as one line here.
        let flat = screen.replacingOccurrences(of: #",\s*\n\s*"#, with: ", ", options: .regularExpression)
        for gone in ["Form {", "Picker(", "Menu {", "Toggle(", "auroraFormBackground", "LabeledContent(", "listRowBackground",
                     "listRowSeparator", "CurrencyCopy.allCurrencies", "TextField(\"Amount for", "TextField(AddSheetCopy.whatFor"] {
            XCTAssertEqual(t6Count(screen, gone), 0, "AddExpenseScreen.swift still has \(gone)")
        }
        // The five choice pages, each opened from its value row.
        for title in ["CategoryCopy.category", "CategoryCopy.subcategory", "AddSheetCopy.group", "AddSheetCopy.splitType",
                      "AddSheetCopy.paidBy"] {
            XCTAssertEqual(t6Count(flat, "ChoicePage(title: \(title),"), 1, "a choice page for \(title)")
            XCTAssertEqual(t6Count(flat, "ListRow(title: \(title), trailing: .value("), title == "AddSheetCopy.group" ? 2 : 1,
                           "the value row for \(title)")
        }
        XCTAssertEqual(t6Count(screen, "NavigationLink {"), 5, "every chooser row pushes its page")
        XCTAssertTrue(screen.contains("switch AddExpenseRows.groupRow(editing: editing != nil, groupCount: groups.count) {"))
        // The fields, each a FieldRow with today's placeholder, the share fields in the one share face.
        for field in ["FieldRow(AddSheetCopy.whatFor, text: $note)", "FieldRow(\"Amount for \\(m.name)\"",
                      "FieldRow(\"Percent for \\(m.name)\"", "FieldRow(\"Shares for \\(m.name)\"",
                      "FieldRow(\"Adjustment for \\(m.name)\"", "FieldRow(\"Line amount\"", "FieldRow(\"Tax\"",
                      "FieldRow(\"Tip\""] {
            XCTAssertEqual(t6Count(screen, field), 1, field)
        }
        XCTAssertEqual(t6Count(screen, "font: shareFont)"), 7, "every share field in the share face")
        XCTAssertEqual(t6Count(screen, "TypeRole.numeric(TypeScale.shareInput)"), 1, "one share face")
        // People in or out, and an item line's people, as drawn switch rows.
        XCTAssertTrue(screen.contains("ListRow(title: member.name, trailing: .toggle(Binding("))
        XCTAssertTrue(screen.contains("ListRow(title: \"Line for \\(m.name)\", trailing: .toggle(lineMemberBinding(line.id, member: m.id)))"))
        XCTAssertTrue(screen.contains("Button(\"Add line\") { itemLines.append(ItemLineDraft()) }\n            .buttonStyle(SecondaryButtonStyle())"))
        // The pill opens the currency list page itself.
        XCTAssertTrue(screen.contains("Button { listingCurrencies = true } label: {"))
        XCTAssertTrue(screen.contains(".navigationDestination(isPresented: $listingCurrencies) {\n            CurrencyListScreen(selection: $currency)"))
        // The page: list cards on the ground, the keyboard put away by a scroll, the action bar as before.
        for kept in ["ScrollView {", ".auroraBackground()", ".scrollDismissesKeyboard(.interactively)", ".formActionBar {",
                     "Button(editing == nil ? \"Add expense\" : \"Save changes\") { save() }",
                     ".disabled(amountText.isEmpty || saved || named == nil)", "header(AddSheetCopy.split)"] {
            XCTAssertEqual(t6Count(screen, kept), 1, kept)
        }
        XCTAssertEqual(t6Count(screen, "ListCard {"), 4, "what and where, the split, an item line, tax and tip")
    }

    /// D-225 (A6, A8, the founder 2026-10-17): the four hints under the split card, one table on both phones: each kind's
    /// line in the kicker style, the same words as Android's `AddSheetCopy` (read from the Kotlin), no dash in any.
    func testTheAddExpenseHintsAreOneTable() throws {
        let table: [(AddExpenseScreen.SplitKind, String?)] = [
            (.equal, nil), (.exact, nil), (.itemised, nil),
            (.percentage, "Percentages must add up to 100"),
            (.shares, "WEIGHTS, NOT AMOUNTS: 2 ADULTS + 1 CHILD AS 2:2:1"),
            (.adjustment, "Equal split, plus or minus each adjustment"),
            (.payerOnly, "One person paid, nobody owes: logged in the group"),
        ]
        XCTAssertEqual(table.count, AddExpenseScreen.SplitKind.allCases.count, "every kind is in the table")
        let kotlin = try t6Source("../android/app/src/main/java/com/vardrlabs/skipti/AddExpenseScreen.kt")
        // Fix wave (review M3): each kind's line is pinned to its kind on Android too, read from the branches of its
        // `AddSheetCopy.hint(kind)` and the constant each one names (a swapped pair used to pass).
        let androidTable = try XCTUnwrap(kotlin.components(separatedBy: "fun hint(kind: AddExpenseUi.SplitDraft): String? = when (kind) {")
            .dropFirst().first?.components(separatedBy: "\n    }\n").first, "Android's hint table")
        func androidHint(_ kind: AddExpenseScreen.SplitKind) -> String?? {
            let name = String(describing: kind)
            let draft = name.prefix(1).uppercased() + name.dropFirst()
            for line in androidTable.components(separatedBy: "\n") {
                guard let arrow = line.range(of: " -> ") else { continue }
                let kinds = line[..<arrow.lowerBound].components(separatedBy: ",").map {
                    $0.replacingOccurrences(of: "is ", with: "").replacingOccurrences(of: "AddExpenseUi.SplitDraft.", with: "")
                        .trimmingCharacters(in: .whitespaces)
                }
                guard kinds.contains(draft) else { continue }
                let value = line[arrow.upperBound...].trimmingCharacters(in: .whitespaces)
                if value == "null" { return .some(nil) }
                return kotlin.components(separatedBy: "const val \(value) = \"").dropFirst().first?.components(separatedBy: "\"").first
            }
            return nil
        }
        for (kind, hint) in table {
            XCTAssertEqual(AddSheetCopy.hint(kind), hint, kind.rawValue)
            XCTAssertEqual(androidHint(kind), .some(hint), "Android's hint for \(kind.rawValue)")
            if let hint {
                XCTAssertFalse(hint.contains("\u{2014}") || hint.contains("\u{2013}"), "no dash: \(hint)") // em-dash-ok: the test asserts this dash is absent
            }
        }
        for (swift, kotlinLine) in [(AddSheetCopy.split, "const val SPLIT = \"Split\""),
                                    (AddSheetCopy.splitType, "const val SPLIT_TYPE = \"Split type\""),
                                    (AddSheetCopy.paidBy, "const val PAID_BY = \"Paid by\"")] {
            XCTAssertTrue(kotlinLine.hasSuffix("\"\(swift)\""), swift)
            XCTAssertTrue(kotlin.contains(kotlinLine), kotlinLine)
        }
        let screen = try t6Source("Skipti/App/AddExpenseScreen.swift")
        XCTAssertTrue(screen.contains("if let hint = AddSheetCopy.hint(kind) {\n                    Text(verbatim: hint)\n                        .microLabelFont()"),
                      "the screen draws the table's line in the kicker style")
    }
}
