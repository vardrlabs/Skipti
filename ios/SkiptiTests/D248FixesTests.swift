import SwiftUI
import XCTest
@testable import Skipti

/// D-248: the small bugs and friction (U29.2 to U31). Each item's decision, its words and its negative. Android's twins
/// are in the stage 2 JVM tests.
final class D248FixesTests: XCTestCase {
    private var dir: URL!
    private let me = String(repeating: "0", count: 31) + "1"
    private let rahul = String(repeating: "0", count: 31) + "2"
    private let group = String(repeating: "0", count: 31) + "9"
    private let en = Locale(identifier: "en_US")
    private let de = Locale(identifier: "de_DE")

    override func setUpWithError() throws {
        dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("d248-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: dir)
    }

    private func appSource(_ file: String) throws -> String {
        let url = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("Skipti/App/\(file)")
        return try String(contentsOf: url, encoding: .utf8)
    }

    private func appSources() throws -> [(name: String, text: String)] {
        let folder = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("Skipti/App")
        return try FileManager.default.contentsOfDirectory(atPath: folder.path).filter { $0.hasSuffix(".swift") }.sorted()
            .map { ($0, try String(contentsOf: folder.appendingPathComponent($0), encoding: .utf8)) }
    }

    /// The text after the first `from`, up to the first `to` after it.
    private func slice(_ text: String, from: String, to: String) throws -> String {
        let after = try XCTUnwrap(text.components(separatedBy: from).dropFirst().first, "anchor \(from)")
        return try XCTUnwrap(after.components(separatedBy: to).first, "anchor \(to)")
    }

    // MARK: - U30.2 the typed-amount parser (decision 184)

    /// Core's typed parser through the bindings, given the phone's mark (decision 203): a subset of
    /// `.drafts/d248/typed_goldens.py`'s table (derived there with exact Fractions, never read out of core), so the shell
    /// reads exactly what core's own test pins. `AmountEntry` passes the mark of the locale it is given.
    func testTypedAmountsReadACommaOnlyOnACommaPhone() {
        let goldens: [(String, String, String, Int64?)] = [
            ("12,50", "EUR", ",", 1_250), (",5", "EUR", ",", 50), ("1,005", "KWD", ",", 1_005), ("1,234", "KWD", ",", 1_234),
            ("12.50", "EUR", ",", 1_250), ("1.05", "KWD", ",", 1_050), ("1234", "JPY", ",", 1_234),
            ("1.234", "KWD", ",", nil), ("1.234", "EUR", ",", nil), ("10,5", "JPY", ",", nil), ("1,234", "INR", ",", nil),
            ("1.234,56", "EUR", ",", nil), ("1,0005", "KWD", ",", nil), (",", "EUR", ",", nil),
            ("12.50", "EUR", ".", 1_250), ("1.234", "KWD", ".", 1_234), ("1005", "JPY", ".", 1_005), (" 7.10 ", "USD", ".", 710),
            ("1,500", "KWD", ".", nil), ("1,234", "KWD", ".", nil), ("12,50", "EUR", ".", nil), ("1,234,567", "INR", ".", nil),
            ("1,234.56", "USD", ".", nil), ("2,00,000", "INR", ".", nil), ("", "EUR", ".", nil),
        ]
        for (text, code, mark, want) in goldens {
            XCTAssertEqual(try? ffiMoneyParseTyped(text: text, currencyCode: code, decimalMark: mark), want, "\(code) '\(text)' \(mark)")
            XCTAssertEqual(AmountEntry.minor(text, currency: code, locale: mark == "," ? de : en), want, "\(code) '\(text)' \(mark)")
        }
        // A mark that is neither is refused, never guessed.
        XCTAssertNil(try? ffiMoneyParseTyped(text: "12.50", currencyCode: "EUR", decimalMark: "\u{066B}"))
        // Import's strict parser is untouched: a comma never reads there.
        XCTAssertThrowsError(try ffiMoneyParseMinor(digits: "12,50", currencyCode: "EUR"))
    }

    /// "Enter an amount like {example}.": the example in the currency's own decimals and the phone's decimal mark, both
    /// from core (`ffiMoneyTypedDigits`, review M3); every field starts from core's entry form in that mark (review M6),
    /// which the parser reads back on that phone.
    func testANonAmountIsRefusedInWordsWithTheLocalesMark() throws {
        XCTAssertEqual(AmountEntry.mark(en), ".")
        XCTAssertEqual(AmountEntry.mark(de), ",")
        XCTAssertEqual(AmountEntry.mark(Locale(identifier: "fr_FR")), ",")
        XCTAssertEqual(AmountEntry.mark(Locale(identifier: "en_IN")), ".")
        XCTAssertEqual(AmountEntry.likeWords(currency: "INR", locale: en), "Enter an amount like 12.50.")
        XCTAssertEqual(AmountEntry.likeWords(currency: "EUR", locale: de), "Enter an amount like 12,50.")
        XCTAssertEqual(AmountEntry.example(currency: "JPY", locale: de), "1250", "no decimals in yen")
        XCTAssertEqual(AmountEntry.example(currency: "KWD", locale: de), "1,250", "three in dinars")
        XCTAssertEqual(AmountEntry.example(currency: "KWD", locale: en), "1.250")
        XCTAssertNil(AmountEntry.example(currency: "XYZ", locale: en), "no example is made up")
        XCTAssertEqual(AmountEntry.likeWords(currency: "XYZ", locale: en), "Enter an amount.")
        XCTAssertEqual(try AmountEntry.parse("12,50", currency: "EUR", locale: de), 1_250)
        XCTAssertThrowsError(try AmountEntry.parse("12,5,0", currency: "EUR", locale: de)) { error in
            XCTAssertEqual(error as? AmountEntry.Refusal, AmountEntry.Refusal(words: "Enter an amount like 12,50."))
        }
        XCTAssertThrowsError(try AmountEntry.parse("10,5", currency: "JPY", locale: en)) { error in
            XCTAssertEqual(error as? AmountEntry.Refusal, AmountEntry.Refusal(words: "Enter an amount like 1250."))
        }
        // I1: an English "1,500" in dinars is refused in words, never saved as 1.500 KD.
        XCTAssertThrowsError(try AmountEntry.parse("1,500", currency: "KWD", locale: en)) { error in
            XCTAssertEqual(error as? AmountEntry.Refusal, AmountEntry.Refusal(words: "Enter an amount like 1.250."))
        }
        // Too large keeps core's own error, which SplitWords says ("That amount is too large.").
        XCTAssertThrowsError(try AmountEntry.parse("99999999999999999999", currency: "INR", locale: en)) { error in
            guard case CoreError.Overflow = error else { return XCTFail("\(error)") }
        }
        // The prefills: in the phone's mark, and read back on that phone (a KWD "1.005" on a ',' phone would be refused).
        for (locale, want) in [(de, "1,005"), (en, "1.005")] {
            XCTAssertEqual(AmountEntry.digits(1_005, currency: "KWD", locale: locale), want)
            XCTAssertEqual(OtherAmount.prefill(debt: 1_005, currency: "KWD", locale: locale), want)
            XCTAssertEqual(AmountEntry.minor(want, currency: "KWD", locale: locale), 1_005)
        }
        let store = LedgerStore(path: dir.appendingPathComponent("ledger.db").path)
        _ = try store.appendExpense(LedgerStore.ExpenseDTO(
            payer: me, paidMinor: 1_005, currencyCode: "KWD", group: group, conversion: nil,
            shares: [.init(id: me, amountMinor: 1_000), .init(id: rahul, amountMinor: 5)],
            note: "Tea", timestampMs: 1_000, category: nil, addedBy: me
        ), nowMs: 5_000)
        let tea = try XCTUnwrap(store.projectedExpenses().expenses.first)
        let edit = try ExpenseEditing.prefill(tea, locale: de)
        XCTAssertEqual(edit.amountText, "1,005")
        XCTAssertEqual(edit.exactEntries, [me: "1,000", rahul: "0,005"])
        XCTAssertEqual(try AmountEntry.parse(edit.amountText, currency: "KWD", locale: de), 1_005, "Edit saves what it opened with")
        XCTAssertEqual(try ExpenseEditing.prefill(tea, locale: en).amountText, "1.005")
        // The example and the prefills are core's strings: nothing in the shell swaps a mark into an amount.
        let entry = try slice(try appSource("AddExpenseScreen.swift"), from: "enum AmountEntry {", to: "\n}\n")
        XCTAssertFalse(entry.contains("replacingOccurrences"))
        XCTAssertTrue(entry.contains("try? ffiMoneyTypedDigits(minorUnits: minor, currencyCode: currency, decimalMark: mark(locale))"))
    }

    /// Every typed-amount site reads through the typed parser; the strict one is left to import and the fixed sample.
    func testEveryEntryFieldUsesTheTypedParser() throws {
        for (name, text) in try appSources() where text.contains("ffiMoneyParseMinor(") {
            XCTAssertEqual(name, "AccountHubScreen.swift", "\(name) parses an amount strictly")
        }
        let sample = try slice(try appSource("AccountHubScreen.swift"), from: "enum SampleLine {", to: "\n}\n")
        XCTAssertTrue(sample.contains("ffiMoneyParseMinor(digits: wholeAmount"), "the fixed \"48\", not a typed field")
        let add = try appSource("AddExpenseScreen.swift")
        XCTAssertTrue(add.contains("let total = try AmountEntry.parse(amountText, currency: currency)"), "Save")
        XCTAssertEqual(add.components(separatedBy: "AmountEntry.minor(amountText, currency: currency)").count, 3,
                       "the summary and the split's live line")
        XCTAssertTrue(try appSource("SettleAll.swift").contains("guard let minor = AmountEntry.minor(text, currency: currency, locale: locale)"))
        XCTAssertTrue(try appSource("RemovalFlow.swift").contains("guard let minor = AmountEntry.minor(typed[id] ?? \"\", currency: currency)"))
    }

    // MARK: - U30.3 blanks

    private func spec(_ kind: AddExpenseScreen.SplitKind, exact: [String: String] = [:], adjustment: [String: String] = [:],
                      lines: [(amount: String, who: [String])] = [], tax: String = "", tip: String = "",
                      blanksAsZero: Bool = false, locale: Locale? = nil) throws -> SplitSpecInput {
        try AmountEntry.spec(kind: kind, roster: [me, rahul], payer: me, currency: "INR", blanksAsZero: blanksAsZero,
                             name: { [me: "Asha (You)", rahul: "Rahul"][$0] ?? "" }, exact: exact, percentage: [:],
                             shares: [:], adjustment: adjustment, lines: lines, tax: tax, tip: tip, locale: locale ?? en)
    }

    /// Tax and tip left blank are 0 (they are optional); a blank exact row at Save is named; an item left blank is 0, so
    /// core's own words say it ("Every item needs an amount above zero.").
    func testBlankTaxAndTipAreZeroAndABlankExactRowIsNamed() throws {
        let itemised = try spec(.itemised, lines: [("900", [me, rahul])], tax: "", tip: "  ")
        XCTAssertEqual(itemised, .itemised(lines: [LineInput(amountMinor: 90_000, participants: [me, rahul])], taxMinor: 0, tipMinor: 0))
        // The whole itemised expense saves through core with them blank.
        let shares = try ffiSplitCompute(totalMinor: 90_000, currencyCode: "INR", members: [me, rahul], spec: itemised)
        XCTAssertEqual(shares.map(\.minorUnits).reduce(0, +), 90_000)
        XCTAssertEqual(try spec(.itemised, lines: [("900", [me])], tax: "10,50", tip: "5", locale: de),
                       .itemised(lines: [LineInput(amountMinor: 90_000, participants: [me])], taxMinor: 1_050, tipMinor: 500))
        // A blank exact row: named at Save, nothing while the live line counts.
        XCTAssertThrowsError(try spec(.exact, exact: [me: "500"])) { error in
            XCTAssertEqual(error as? AmountEntry.Refusal, AmountEntry.Refusal(words: "Enter an amount for Rahul."))
        }
        XCTAssertEqual(try spec(.exact, exact: [me: "500"], blanksAsZero: true),
                       .exact(entries: [IdAmount(id: me, amountMinor: 50_000), IdAmount(id: rahul, amountMinor: 0)]))
        XCTAssertEqual(try spec(.exact, exact: [me: "500,25", rahul: "1.5"], locale: de),
                       .exact(entries: [IdAmount(id: me, amountMinor: 50_025), IdAmount(id: rahul, amountMinor: 150)]))
        // What is typed and not an amount: AmountEntry's words, never core's raw error.
        XCTAssertThrowsError(try spec(.exact, exact: [me: "5x", rahul: "1"], locale: de)) { error in
            XCTAssertEqual(error as? AmountEntry.Refusal, AmountEntry.Refusal(words: "Enter an amount like 12,50."))
        }
        XCTAssertThrowsError(try spec(.itemised, lines: [("900", [me])], tax: "1,2,3")) { error in
            XCTAssertEqual(error as? AmountEntry.Refusal, AmountEntry.Refusal(words: "Enter an amount like 12.50."))
        }
        // Decision 203: on a '.' phone the same comma is refused in words, never read.
        XCTAssertThrowsError(try spec(.itemised, lines: [("900", [me])], tax: "10,50")) { error in
            XCTAssertEqual(error as? AmountEntry.Refusal, AmountEntry.Refusal(words: "Enter an amount like 12.50."))
        }
        // An adjustment left blank is none; an item left blank is 0, and core says so in words.
        XCTAssertEqual(try spec(.adjustment, adjustment: [me: "-2,50"], locale: de),
                       .adjustment(entries: [IdAmount(id: me, amountMinor: -250), IdAmount(id: rahul, amountMinor: 0)]))
        let blankLine = try spec(.itemised, lines: [("", [me])])
        XCTAssertThrowsError(try ffiSplitCompute(totalMinor: 100, currencyCode: "INR", members: [me, rahul], spec: blankLine)) { error in
            XCTAssertEqual(SplitWords.message(try! XCTUnwrap(error as? CoreError), currency: "INR"),
                           "Every item needs an amount above zero.")
        }
        // Save shows a refusal's words (the form's catch).
        let add = try appSource("AddExpenseScreen.swift")
        XCTAssertTrue(add.contains("} catch let refusal as AmountEntry.Refusal {\n            error = refusal.words"))
    }

    // MARK: - U29.2 Return adds a person row

    func testReturnOnTheLastNamedRowAddsARowAndAnEmptyOneEndsEditing() throws {
        var draft = PeopleDraft()
        XCTAssertNil(draft.afterReturn(on: 0), "an empty last row ends editing")
        XCTAssertEqual(draft.rows.count, 1, "and adds nothing")
        draft.rows[0].name = "Rahul"
        XCTAssertEqual(draft.afterReturn(on: 0), 1, "a named last row adds a row and moves to it")
        XCTAssertEqual(draft.rows.map(\.id), [0, 1])
        XCTAssertEqual(draft.afterReturn(on: 0), 1, "a row above moves down, adding nothing")
        XCTAssertEqual(draft.rows.count, 2)
        draft.rows[1].name = "   "
        XCTAssertNil(draft.afterReturn(on: 1), "blank is empty")
        XCTAssertNil(draft.afterReturn(on: 42), "a row that has gone")
        // The sheet wires it: Return reads "Next" on every person row, and the new row takes the keyboard.
        let sheet = try appSource("GroupSheets.swift")
        XCTAssertTrue(sheet.contains("form: FieldFocus(id: row.id, focus: $focusedRow) { returned(on: row.id) }"))
        XCTAssertTrue(sheet.contains("guard let next = people.afterReturn(on: id) else { focusedRow = nil; return }"))
        let rows = try appSource("ListRows.swift")
        XCTAssertTrue(rows.contains(".focused(form.focus, equals: form.id)\n                .submitLabel(.next)\n                .onSubmit(form.onReturn)"))
    }

    // MARK: - U29.3 the amount is focused on a new expense

    func testTheAmountTakesTheKeyboardOnANewExpenseOnly() throws {
        XCTAssertTrue(AmountFocus.focusesOnOpen(editing: false, already: false))
        XCTAssertFalse(AmountFocus.focusesOnOpen(editing: true, already: false), "never on Edit")
        XCTAssertFalse(AmountFocus.focusesOnOpen(editing: false, already: true), "once (not back from a choice page)")
        let add = try appSource("AddExpenseScreen.swift")
        XCTAssertTrue(add.contains(".focused($amountFocused)"))
        // Sabotage pin (D-248 iOS lane, row R3b): the decision alone survived dropping the line that sets the focus. The
        // block whole: once decided, it is marked and the field takes the keyboard.
        XCTAssertTrue(add.contains("if AmountFocus.focusesOnOpen(editing: editing != nil, already: focusedOnce) {\n"
            + "                focusedOnce = true\n                amountFocused = true\n            }"))
    }

    // MARK: - U30.1 tab-proof words

    func testNoMessageSendsThePersonToATab() throws {
        var sites = 0
        for (name, text) in try appSources() {
            XCTAssertFalse(text.contains("on Balance first"), name)
            sites += text.components(separatedBy: "\"Add your name first.\"").count - 1
            sites += text.components(separatedBy: "\"Create a group first.\"").count - 1
        }
        // ImportFlow's (older), Settle up's two, Add expense's "Create a group first." (D-249: Add expense's "Add your name
        // first." went: a group waiting on your name no longer stops a Just me expense).
        XCTAssertEqual(sites, 4)
    }

    // MARK: - U30.4 the delete's Undo where it happened

    @MainActor
    func testTheDeleteUndoIsTheToastWhereverTheDetailOpened() throws {
        let store = LedgerStore(path: dir.appendingPathComponent("ledger.db").path)
        _ = try store.appendExpense(LedgerStore.ExpenseDTO(
            payer: me, paidMinor: 120_000, currencyCode: "INR", group: group, conversion: nil,
            shares: [.init(id: me, amountMinor: 70_000), .init(id: rahul, amountMinor: 50_000)],
            note: "Dinner", timestampMs: 1_000, category: "food.dining", addedBy: me
        ), nowMs: 5_000)
        let dinner = try XCTUnwrap(store.projectedExpenses().expenses.first)
        XCTAssertEqual(dinner.category, "food.dining")
        try store.reviseExpense(ExpenseEditing.delete(dinner, editor: me), nowMs: 6_000)
        XCTAssertEqual(try store.loadExpenses(), [])

        let center = DoneCenter(gate: SuccessGate(), haptic: { _, _ in }, announce: { _ in })
        ExpenseUndo.offer(dinner, store: store, editor: me, center: center, screenReader: false)
        guard case .small(let line) = center.current else { return XCTFail("a toast") }
        XCTAssertEqual(line, DoneLine(title: "Expense deleted", parts: ["Dinner"], action: "Undo", actionSeconds: 5))
        center.act(center.serial)
        // Review M5: the whole expense comes back as it was (amount, payer, shares, category, date, note), not only its note.
        let restored = try XCTUnwrap(try store.projectedExpenses().expenses.first { $0.id == dinner.id }, "Undo restored it")
        XCTAssertEqual(restored.expense.paidMinor, dinner.expense.paidMinor)
        XCTAssertEqual(restored.expense.currencyCode, dinner.expense.currencyCode)
        XCTAssertEqual(restored.expense.payer, dinner.expense.payer)
        XCTAssertEqual(restored.expense.shares, dinner.expense.shares)
        XCTAssertEqual(restored.expense.group, dinner.expense.group)
        XCTAssertEqual(restored.timestampMs, dinner.timestampMs)
        XCTAssertEqual(restored.category, dinner.category)
        XCTAssertEqual(restored.note, dinner.note)
        XCTAssertEqual(try store.loadExpenses().count, 1)
        guard case .small(let after) = center.current else { return XCTFail("the toast") }
        XCTAssertNotEqual(after.title, DoneCopy.notRestored, "a written Undo says nothing more")
        // Review M2: an Undo that cannot write says so ("Not restored."), as a settle's Undo says "Not recorded.". Here
        // the expense is not in this vault, so the store refuses the revision.
        let elsewhere = LedgerStore(path: dir.appendingPathComponent("other.db").path)
        ExpenseUndo.offer(dinner, store: elsewhere, editor: me, center: center, screenReader: false)
        center.act(center.serial)
        guard case .small(let failed) = center.current else { return XCTFail("a toast") }
        XCTAssertEqual(failed, DoneLine(title: "Not restored.", parts: []))
        XCTAssertEqual(DoneCopy.notRestored, "Not restored.")
        // A screen reader gets the hold's longer window; a note-less expense shows the title alone.
        guard case .small(let long) = ExpenseUndo.moment(dinner, seconds: SettleHold.window(screenReader: true)) else { return XCTFail() }
        XCTAssertEqual(long.actionSeconds, 12)

        // Every detail page offers it (the group page, Lately and Activity pass no callback), and so does the list's
        // long press; the list's foot row has gone.
        let detail = try slice(try appSource("ExpenseDetailView.swift"), from: "    private func delete() {", to: "\n    }\n")
        XCTAssertTrue(detail.contains("ExpenseUndo.offer(e, store: store, editor: named?.selfId)"))
        let list = try appSource("ExpenseListView.swift")
        XCTAssertTrue(list.contains("ExpenseUndo.offer(expense, store: store, editor: group(of: expense)?.selfId)"))
        XCTAssertFalse(list.contains("undoBar"))
        XCTAssertFalse(list.contains("undoable"))
        // Sabotage pin (D-248 iOS lane, row R10): a foot Undo row under a new name survived the two names above. The
        // list's one Undo is the toast's offer: it calls ExpenseUndo once and draws no Undo of its own.
        XCTAssertEqual(list.components(separatedBy: "ExpenseUndo.").count - 1, 1, "the list's only Undo is the toast")
        XCTAssertFalse(list.contains("Button(\"Undo\")"), "no Undo button on the list")
        XCTAssertFalse(list.contains("DoneCopy.undo"), "no Undo label on the list")
    }

    // MARK: - U30.6 a new group opens

    @MainActor
    func testANewGroupOpensItsPageOnHomeOnce() throws {
        let opening = GroupOpening()
        XCTAssertNil(opening.take(root: 0))
        opening.request("g1", root: 2)
        // Review M7: the request is for the rebuilt Home (generation 2). The Home being torn down (1) sees it first and
        // leaves it, whatever order SwiftUI runs them in; the new Home takes it, once.
        XCTAssertNil(opening.take(root: 1), "the old Home leaves it")
        XCTAssertEqual(opening.pending, GroupOpening.Request(groupID: "g1", root: 2), "and it is still there")
        XCTAssertEqual(opening.take(root: 2), "g1")
        XCTAssertNil(opening.take(root: 2), "once")
        // Every create path opens it once its sheet has gone; Home pushes it on its own stack.
        let overview = try appSource("OverviewScreen.swift")
        XCTAssertTrue(overview.contains(".navigationDestination(item: $openedGroup) { opened in\n            GroupPageScreen(groupID: opened.id)"))
        XCTAssertTrue(overview.contains("if let id = createdID { createdID = nil; openedGroup = OpenedGroup(id: id) }"))
        XCTAssertTrue(overview.contains("if let id = GroupOpening.shared.take(root: root) { openedGroup = OpenedGroup(id: id) }"))
        let groups = try appSource("GroupsScreen.swift")
        XCTAssertTrue(groups.contains("if let id = createdID { createdID = nil; onCreated(id) }"))
        let shell = try appSource("HomeShell.swift")
        // Groups' create: Home's stack is rebuilt and the request names the new Home, in the same turn (no run-loop wait).
        XCTAssertTrue(shell.contains("roots[.balance, default: 0] += 1\n                    // For the rebuilt Home alone (review M7): the old one, still on screen this turn, leaves it.\n                    GroupOpening.shared.request(id, root: roots[.balance, default: 0])"))
        XCTAssertFalse(shell.contains("DispatchQueue.main.async { GroupOpening"))
        XCTAssertTrue(shell.contains("OverviewScreen(root: roots[.balance, default: 0]) {"))
        XCTAssertTrue(shell.contains("if let id = startedGroupID { startedGroupID = nil; GroupOpening.shared.request(id, root: roots[.balance, default: 0]) }"))
        // The first group's big moment is still posted by the sheet (the window's overlay draws it over the page).
        XCTAssertTrue(try appSource("GroupSheets.swift").contains("DoneCenter.shared.post(DoneMoments.groupCreated("))
    }

    // MARK: - D-247 residue (e): PlaceLost's words are the twins'

    func testAPlaceLostRefusalReadsAsTheBannersLine() {
        let error: Error = LedgerStore.Placing.PlaceLost()
        let banner = "You can't add to this group from this phone: someone else holds your place in it. Ask the group's owner to check People."
        XCTAssertEqual("\(error)", banner, "what a failure line prints (Android's PlaceLost message)")
        XCTAssertEqual(error.localizedDescription, banner)
        XCTAssertEqual(RolesCopy.placeLostBanner, banner)
    }

    // MARK: - U31 the inline title cap (decision 186)

    /// Every fixed title a pushed screen draws inline, from the app's own words (a copy change is measured). Left out, as
    /// the person's own data the ellipsis is for: a group's, a person's or an expense's name.
    private var pushedTitles: [String] {
        SettingsSection.allCases.map(\.title) + [
            "About", "Licences", YouCopy.title, EraseCopy.title, ActivityFeed.title, ActivityFeed.groupTitle,
            CurrencyCopy.allCurrencies, "Export", GroupPictures.title, "Groups", "People", RolesCopy.groupSettingsTitle,
            InviteCopy.joinTitle, ImportCopy.title, ImportCopy.reviewTitle, "Profile", PeopleCopy.invitations,
            AddPersonCopy.title, PhonesCopy.title, PhonesCopy.deleteTitle, RolesCopy.redistributeTitle, UsernameCopy.title,
            UsernameCopy.change, "Expense", "Sign in", CategoryCopy.category, CategoryCopy.subcategory,
            AddSheetCopy.splitType, AddSheetCopy.paidBy, AddSheetCopy.ledger, SpendingCopy.title,
        ]
    }

    private func titleWidth(_ title: String, theme: AppTheme, category: UIContentSizeCategory) -> CGFloat {
        NSAttributedString(string: title, attributes: TitleStyle.attributes(large: false, theme: theme, category: category))
            .size().width
    }

    /// Each pushed title fits a 375 pt iPhone's bar beside the back button (`TitleWidth.room`) in every theme at every
    /// category the text-size steps can reach, and the cap never shrinks a title below the system's 17 at the default.
    @MainActor
    func testEveryPushedTitleFitsBesideTheBackButtonAtEveryTextStep() throws {
        var measured = 0
        for theme in AppTheme.allCases {
            XCTAssertGreaterThanOrEqual(TitleStyle.inlineCap(theme), TypeScale.inlineTitle, "\(theme): never below 17")
            for category in TextSize.ladder {
                for title in pushedTitles {
                    let drawn = titleWidth(title, theme: theme, category: category)
                    XCTAssertLessThanOrEqual(drawn, TitleWidth.room, "\(theme) \(category.rawValue) '\(title)' is \(drawn) wide")
                    measured += 1
                }
            }
        }
        XCTAssertGreaterThan(measured, 1_000, "the table is not empty")
        // The negative: uncapped (the system's scaling) the longest title does not fit at the largest step; and the cap
        // holds 2 points of slack under the room in the widest theme.
        let biggest = try XCTUnwrap(TextSize.ladder.last)
        let base = TypeRole.base(TypeRole.face(.display, theme: .norse), size: TypeScale.inlineTitle, weight: TitleStyle.weight(.norse), tabular: false)
        let uncapped = UIFontMetrics(forTextStyle: .headline).scaledFont(for: base, compatibleWith: UITraitCollection(preferredContentSizeCategory: biggest))
        let longest = SettingsSection.profileAccount.title
        let wide = NSAttributedString(string: longest, attributes: [.font: uncapped, .kern: uncapped.pointSize * TitleStyle.tracking(.norse)])
            .size().width
        XCTAssertGreaterThan(wide, TitleWidth.room, "uncapped '\(longest)' in Norse at \(biggest.rawValue): \(wide)")
        XCTAssertLessThanOrEqual(titleWidth(longest, theme: .norse, category: biggest), TitleWidth.room - 2)
        // Large titles are untouched: they scale past the inline cap.
        XCTAssertGreaterThan(TitleStyle.font(large: true, theme: .norse, category: .accessibilityLarge).pointSize, TitleStyle.inlineCap(.norse))
    }

    /// Decision 202 (revised, second wave): one inline cap on both shells, 20, the most iOS 26 and later draw an inline
    /// title at (measured on iOS 27: 22 asked, 20 drawn).
    func testTheInlineCapIsTheTwinsOne() throws {
        for theme in AppTheme.allCases { XCTAssertEqual(TitleStyle.inlineCap(theme), 20, "\(theme)") }
        let biggest = try XCTUnwrap(TextSize.ladder.last)
        XCTAssertEqual(TitleStyle.font(large: false, theme: .norse, category: biggest).pointSize, 20)
        let android = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("android/app/src/main/java/com/vardrlabs/skipti/SkiptiBar.kt")
        let bar = try String(contentsOf: android, encoding: .utf8)
        XCTAssertTrue(bar.contains("const val INLINE_TITLE_CAP = 20\n"), "Android's cap")
        XCTAssertTrue(bar.contains("const val PILL_TEXT_CAP = 20f\n"), "Android's pill text stops where iOS's bar button does")
        XCTAssertTrue(bar.contains("const val BAR_TEXT_FLOOR = 17f\n"), "Android's bar text starts where iOS's does")
    }

    /// Decision 202 (revised, second wave): bar text, the inline title and a bar button's, is asked for between 17 and 20
    /// at every text step, iOS 26's own range (measured: 15 asked at XS draws 17, 22 asked draws 20), so iOS 17 to 25 draw
    /// the same and the width tables measure what UIKit draws. The negatives: the system's own scaling goes under 17 at
    /// the smallest step and over 20 at the largest, so the clamp is doing the work at both ends.
    func testBarTextIsAskedFor17To20AtEveryTextStep() throws {
        let smallest = try XCTUnwrap(TextSize.ladder.first)
        let biggest = try XCTUnwrap(TextSize.ladder.last)
        for theme in AppTheme.allCases {
            for category in TextSize.ladder {
                let title = TitleStyle.font(large: false, theme: theme, category: category).pointSize
                let button = try XCTUnwrap(TitleStyle.barButtonAttributes(theme: theme, category: category)[.font] as? UIFont).pointSize
                for (what, size) in [("title", title), ("button", button)] {
                    XCTAssertGreaterThanOrEqual(size, 17, "\(theme) \(category.rawValue) \(what)")
                    XCTAssertLessThanOrEqual(size, 20, "\(theme) \(category.rawValue) \(what)")
                }
                let kern = try XCTUnwrap(TitleStyle.attributes(large: false, theme: theme, category: category)[.kern] as? CGFloat)
                XCTAssertEqual(kern, title * TitleStyle.tracking(theme), accuracy: 0.0001, "the tracking follows the drawn size")
            }
            XCTAssertEqual(TitleStyle.font(large: false, theme: theme, category: smallest).pointSize, 17, "\(theme)")
            XCTAssertEqual(TitleStyle.font(large: false, theme: theme, category: biggest).pointSize, 20, "\(theme)")
            XCTAssertEqual((TitleStyle.barButtonAttributes(theme: theme, category: smallest)[.font] as? UIFont)?.pointSize, 17)
            XCTAssertEqual((TitleStyle.barButtonAttributes(theme: theme, category: biggest)[.font] as? UIFont)?.pointSize, 20)
        }
        XCTAssertEqual(TitleWidth.floor, 17)
        XCTAssertEqual(TitleStyle.barButtonCap, 20)
        let base = TypeRole.base(TypeRole.face(.display, theme: .norse), size: TypeScale.inlineTitle, weight: TitleStyle.weight(.norse), tabular: false)
        let system = { (category: UIContentSizeCategory) in
            UIFontMetrics(forTextStyle: .headline).scaledFont(for: base, compatibleWith: UITraitCollection(preferredContentSizeCategory: category)).pointSize
        }
        XCTAssertLessThan(system(smallest), 17, "the system's own size at the smallest step")
        XCTAssertGreaterThan(system(biggest), 20, "the system's own size at the largest step")
        // Large titles are not bar text: they keep the system's scaling.
        XCTAssertGreaterThan(TitleStyle.font(large: true, theme: .norse, category: biggest).pointSize, 20)
    }

    // MARK: - Review I3: sheet titles beside their pill

    /// Every fixed title a sheet's bar draws, with the pill in its corner (nil: no pill; the title has the bar). Left
    /// out, as the person's own data the ellipsis is for: a group's, a person's or an expense's name ("Remove {name}").
    private var sheetTitles: [(title: String, pill: String?)] {
        [
            ("Your name", "Cancel"), ("New group", "Cancel"), ("Add expense", "Cancel"), ("Edit expense", "Cancel"),
            (UsernameCopy.title, UsernameCopy.cancel), (UsernameCopy.change, UsernameCopy.cancel), (AddPersonCopy.title, "Done"),
            ("Expense", "Cancel"), ("Expense", "Close"), (InviteCopy.joinTitle, "Cancel"), (PeopleCopy.invitations, "Close"),
            (PhonesCopy.title, "Close"), (GroupPictures.title, GroupPictures.cancel), (SettleAllCopy.howMuchPaid, nil),
        ]
    }

    /// Each sheet title fits beside its pill (`TitleWidth.sheetRoom`, the pill as wide as its text makes it at that
    /// step) on a 375 pt iPhone, in every theme at every text step, with 2 points of slack, at the size this app asks
    /// for (17 to 20, what iOS 26 draws; the real-bar test below checks UIKit draws it). The negative: with the bar button's text uncapped (iOS 17 to 25
    /// drew what it was given) the Cancel pill leaves "Pick your username" no room at the largest step.
    @MainActor
    func testEverySheetTitleClearsItsPillAtEveryTextStep() throws {
        var measured = 0
        for theme in AppTheme.allCases {
            for category in TextSize.ladder {
                for (title, pill) in sheetTitles {
                    let room = pill.map { TitleWidth.sheetRoom(pill: TitleWidth.pill($0, theme: theme, category: category)) }
                        ?? TitleWidth.phone - 2 * TitleWidth.edge
                    // The title in its own attributes: 17 to 20 (TitleStyle.barText), what UIKit draws.
                    let attributes = TitleStyle.attributes(large: false, theme: theme, category: category)
                    let drawn = NSAttributedString(string: title, attributes: attributes).size().width
                    XCTAssertLessThanOrEqual(drawn, room - 2, "\(theme) \(category.rawValue) '\(title)' is \(drawn) in \(room)")
                    measured += 1
                }
            }
        }
        XCTAssertEqual(measured, AppTheme.allCases.count * TextSize.ladder.count * sheetTitles.count)
        XCTAssertGreaterThan(measured, 400, "the table is not empty")
        let biggest = try XCTUnwrap(TextSize.ladder.last)
        XCTAssertEqual((TitleStyle.barButtonAttributes(theme: .norse, category: biggest)[.font] as? UIFont)?.pointSize, TitleStyle.barButtonCap)
        let base = TypeRole.base(TypeRole.face(.body, theme: .norse), size: 17, weight: 400, tabular: false)
        let uncapped = UIFontMetrics(forTextStyle: .body).scaledFont(for: base, compatibleWith: UITraitCollection(preferredContentSizeCategory: biggest))
        let wide = ceil(NSAttributedString(string: "Cancel", attributes: [.font: uncapped]).size().width) + 2 * TitleWidth.pillPadding
        XCTAssertLessThan(TitleWidth.sheetRoom(pill: wide), titleWidth(UsernameCopy.title, theme: .norse, category: biggest),
                          "uncapped, the pill is \(wide) wide")
    }

    /// The bar a sheet title is measured against is UIKit's own: a real navigation bar on a 375 pt window, styled by the
    /// app's own `Appearance.apply` at every theme and text step, lays every sheet title out whole, clear of its pill,
    /// and the pill as wide as `TitleWidth.pill` says. The negative: the same harness sees a title that is too long cut.
    @MainActor
    func testASheetBarIsLaidOutAsTheTableSays() throws {
        let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
        let window = UIWindow(windowScene: scene)
        window.frame = CGRect(x: 0, y: 0, width: TitleWidth.phone, height: 812)
        defer {
            window.isHidden = true
            TitleStyle.apply(AppearanceState.shared, to: window)
        }
        func layOut(_ title: String, pill: String, theme: AppTheme, step: Int) throws -> (title: UILabel, pill: UILabel) {
            let root = UIViewController()
            root.navigationItem.title = title
            root.navigationItem.leftBarButtonItem = UIBarButtonItem(title: pill, style: .plain, target: nil, action: nil)
            let nav = UINavigationController(rootViewController: root)
            window.rootViewController = nav
            window.isHidden = false
            let state = AppearanceState()
            state.theme = theme
            state.phoneCategory = .large
            state.textStep = step
            Appearance.apply(state, to: window)
            nav.view.layoutIfNeeded()
            nav.navigationBar.layoutIfNeeded()
            var labels: [UILabel] = []
            func walk(_ view: UIView) {
                for sub in view.subviews {
                    if let label = sub as? UILabel { labels.append(label) }
                    walk(sub)
                }
            }
            walk(nav.navigationBar)
            let text = { (l: UILabel) in l.attributedText?.string ?? l.text ?? "" }
            return (try XCTUnwrap(labels.first { text($0) == title }, title), try XCTUnwrap(labels.first { text($0) == pill }, pill))
        }
        let steps = (0..<TextSize.ladder.count).map { $0 - 3 } // .large is the ladder's 4th
        var laidOut = 0
        for theme in AppTheme.allCases {
            for step in steps {
                let category = TextSize.category(phone: .large, step: step)
                // A new theme or text size reaches a bar's button on the next turn: let it land before measuring.
                _ = try layOut("Settle", pill: "Cancel", theme: theme, step: step)
                RunLoop.main.run(until: Date().addingTimeInterval(0.05))
                for (title, pill) in sheetTitles {
                    guard let pill else { continue }
                    let (label, button) = try layOut(title, pill: pill, theme: theme, step: step)
                    let frame = label.convert(label.bounds, to: nil)
                    let pillFrame = button.convert(button.bounds, to: nil).insetBy(dx: -TitleWidth.pillPadding, dy: 0)
                    let at = "\(theme) \(category.rawValue) '\(title)'"
                    XCTAssertGreaterThanOrEqual(frame.width, label.intrinsicContentSize.width - 0.5, "\(at) is cut")
                    XCTAssertGreaterThanOrEqual(frame.minX, pillFrame.maxX + TitleWidth.pillGap - 0.5, "\(at) runs into the pill")
                    XCTAssertLessThanOrEqual(frame.maxX, TitleWidth.phone - TitleWidth.edge + 0.5, at)
                    XCTAssertEqual(pillFrame.minX, TitleWidth.edge, accuracy: 0.5, "\(at): the pill's place")
                    // The table's pill is UIKit's, at every step iOS 26 draws (it stops the text at 20 too).
                    XCTAssertEqual(pillFrame.width, TitleWidth.pill(pill, theme: theme, category: category), accuracy: 1, "\(at): \(pill)")
                    XCTAssertLessThanOrEqual(button.font.pointSize, TitleStyle.barButtonCap, at)
                    XCTAssertGreaterThanOrEqual(button.font.pointSize, TitleWidth.floor, at)
                    // Second wave: the title is drawn at the size the tables measure (17 to 20), not one UIKit chose.
                    let drawnFont = (label.attributedText?.length ?? 0) > 0 ? label.attributedText?.attribute(.font, at: 0, effectiveRange: nil) as? UIFont : label.font
                    XCTAssertEqual(try XCTUnwrap(drawnFont, at).pointSize, TitleStyle.font(large: false, theme: theme, category: category).pointSize,
                                   accuracy: 0.01, "\(at): the title's size")
                    laidOut += 1
                }
            }
        }
        XCTAssertGreaterThan(laidOut, 300)
        // The negative: what does not fit is cut, and this harness sees it.
        let (long, _) = try layOut("Pick your username and then one more long word", pill: "Cancel", theme: .norse, step: 8)
        XCTAssertLessThan(long.convert(long.bounds, to: nil).width, long.intrinsicContentSize.width - 10)
    }

    // MARK: - Decision 204: bar buttons as SwiftUI draws them

    /// A sheet's bar as the app draws it: SwiftUI `content` in a NavigationStack, presented as a sheet over a plain root
    /// on a 375 pt window, under the app's root font (`AppearanceApplier`'s `.font(TypeRole.body())`, pinned below) with
    /// the app's appearance applied for `theme` at text `step`. Returns the title label and the Cancel/Done/Close glass
    /// pill (the leading glass view in the bar).
    @MainActor
    private func hostedSheet(_ content: some View, title: String, theme: AppTheme, step: Int)
        throws -> (title: UILabel, pill: CGRect, window: UIWindow) {
        let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
        let shared = AppearanceState.shared
        shared.theme = theme
        shared.phoneCategory = .large
        shared.textStep = step
        let window = UIWindow(windowScene: scene)
        window.frame = CGRect(x: 0, y: 0, width: TitleWidth.phone, height: 812)
        Appearance.apply(shared, to: window)
        window.rootViewController = UIViewController()
        window.isHidden = false
        let host = UIHostingController(rootView: NavigationStack { content }.font(TypeRole.body()))
        window.rootViewController?.present(host, animated: false)
        RunLoop.main.run(until: Date().addingTimeInterval(0.5))
        var label: UILabel?
        var glass: [CGRect] = []
        func walk(_ view: UIView) {
            let frame = view.convert(view.bounds, to: nil)
            if let l = view as? UILabel, (l.attributedText?.string ?? l.text) == title, frame.minY < 160 { label = l }
            if String(describing: type(of: view)).contains("GlassInteraction"), frame.minY < 160 { glass.append(frame) }
            for sub in view.subviews { walk(sub) }
        }
        walk(window)
        let pill = try XCTUnwrap(glass.filter { $0.minX < TitleWidth.phone / 2 }.min { $0.minX < $1.minX }, "\(theme) \(step) '\(title)': no pill")
        return (try XCTUnwrap(label, "\(theme) \(step) '\(title)'"), pill, window)
    }

    @MainActor
    private func closeHosted(_ window: UIWindow) {
        window.rootViewController?.dismiss(animated: false)
        window.isHidden = true
    }

    /// Where UIKit puts a sheet's title beside a pill `pill` wide (TitleWidth's rule, measured on iOS 27): centred when
    /// that clears the pill by `pillGap`, else just clear of it.
    private func expectedTitleMinX(width: CGFloat, pill: CGFloat) -> CGFloat {
        let centred = (TitleWidth.phone - width) / 2
        let least = TitleWidth.edge + pill + TitleWidth.pillGap
        return min(max(centred, least), TitleWidth.phone - TitleWidth.edge - width)
    }

    /// The app's own New group sheet (CreateGroupSheet), as SwiftUI draws it, in every theme at the phone's size and at
    /// the largest step: the Cancel pill is as wide as the bar button's 17 to 20 font makes it (`TitleWidth.pill`),
    /// the title is whole, drawn at the tables' size, and centred where the table says (centred on the screen at both
    /// steps here). Before decision 204 the pill took the root's uncapped body font: about 171 pt at the largest step
    /// and the title pushed off centre (the look); removing `.barButtonText()` from this sheet's Cancel turns it red.
    @MainActor
    func testTheNewGroupSheetIsDrawnAsTheTableSays() throws {
        let shared = AppearanceState.shared
        let saved = (shared.theme, shared.phoneCategory, shared.textStep)
        defer { (shared.theme, shared.phoneCategory, shared.textStep) = saved }
        let store = LedgerStore(path: dir.appendingPathComponent("ledger.db").path)
        for theme in AppTheme.allCases {
            for step in [0, 8] {
                let category = TextSize.category(phone: .large, step: step)
                closeHosted(try hostedSheet(Color.clear.navigationTitle("Settle").toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") {}.barButtonText() } },
                                            title: "Settle", theme: theme, step: step).window)
                let (label, pill, window) = try hostedSheet(CreateGroupSheet(store: store, needsYourName: false) { _ in },
                                                            title: "New group", theme: theme, step: step)
                defer { closeHosted(window) }
                let at = "\(theme) \(category.rawValue)"
                let frame = label.convert(label.bounds, to: nil)
                XCTAssertEqual(pill.minX, TitleWidth.edge, accuracy: 0.5, "\(at): the pill's place")
                XCTAssertEqual(pill.width, TitleWidth.pill("Cancel", theme: theme, category: category), accuracy: 1.5, "\(at): the pill")
                XCTAssertGreaterThanOrEqual(frame.width, label.intrinsicContentSize.width - 0.5, "\(at): cut")
                XCTAssertEqual(frame.minX, expectedTitleMinX(width: label.intrinsicContentSize.width, pill: pill.width), accuracy: 1, "\(at): the title's place")
                XCTAssertEqual(frame.midX, TitleWidth.phone / 2, accuracy: 1, "\(at): centred")
                let font = (label.attributedText?.length ?? 0) > 0 ? label.attributedText?.attribute(.font, at: 0, effectiveRange: nil) as? UIFont : label.font
                XCTAssertEqual(try XCTUnwrap(font).pointSize, TitleStyle.font(large: false, theme: theme, category: category).pointSize, accuracy: 0.01, at)
            }
        }
    }

    /// Every sheet title in the table, in a bar SwiftUI draws (the app's root font, the button through
    /// `.barButtonText()`), in every theme at the largest step: whole, clear of the pill by `pillGap`, inside `edge` of
    /// the far side, placed by the table's rule, and the pill as wide as `TitleWidth.pill`. The negative: the same bar
    /// with a plain Button (the root's font, as before decision 204) draws the pill far wider than the table.
    @MainActor
    func testEverySheetTitleIsDrawnAsTheTableSaysInSwiftUI() throws {
        let shared = AppearanceState.shared
        let saved = (shared.theme, shared.phoneCategory, shared.textStep)
        defer { (shared.theme, shared.phoneCategory, shared.textStep) = saved }
        let biggest = try XCTUnwrap(TextSize.ladder.last)
        var drawn = 0
        for theme in AppTheme.allCases {
            // A new theme or text size reaches a bar's button on the next turn (as in the UIKit-bar test): let it land.
            closeHosted(try hostedSheet(Color.clear.navigationTitle("Settle").toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") {}.barButtonText() } },
                                        title: "Settle", theme: theme, step: 8).window)
            for (title, pill) in sheetTitles {
                guard let pill else { continue }
                let bar = Color.clear.navigationTitle(title).navigationBarTitleDisplayMode(.inline)
                    .toolbar { ToolbarItem(placement: .cancellationAction) { Button(pill) {}.barButtonText().barTint() } }
                let (label, glass, window) = try hostedSheet(bar, title: title, theme: theme, step: 8)
                defer { closeHosted(window) }
                let at = "\(theme) '\(title)' beside \(pill)"
                let frame = label.convert(label.bounds, to: nil)
                XCTAssertEqual(glass.width, TitleWidth.pill(pill, theme: theme, category: biggest), accuracy: 1.5, "\(at): the pill")
                XCTAssertGreaterThanOrEqual(frame.width, label.intrinsicContentSize.width - 0.5, "\(at): cut")
                XCTAssertGreaterThanOrEqual(frame.minX, glass.maxX + TitleWidth.pillGap - 0.5, "\(at): runs into the pill")
                XCTAssertLessThanOrEqual(frame.maxX, TitleWidth.phone - TitleWidth.edge + 0.5, at)
                XCTAssertEqual(frame.minX, expectedTitleMinX(width: label.intrinsicContentSize.width, pill: glass.width), accuracy: 1, "\(at): placed")
                drawn += 1
            }
        }
        XCTAssertEqual(drawn, AppTheme.allCases.count * sheetTitles.filter { $0.pill != nil }.count)
        // The negative: a plain Button takes the root's body font, uncapped.
        let plain = Color.clear.navigationTitle("New group").navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") {}.barTint() } }
        let (_, wide, window) = try hostedSheet(plain, title: "New group", theme: .norse, step: 8)
        closeHosted(window)
        XCTAssertGreaterThan(wide.width, TitleWidth.pill("Cancel", theme: .norse, category: biggest) + 40, "plain: \(wide.width)")
    }

    /// The scanner the guard below runs: every `ToolbarItem` block in `source` whose content is a text Button or a
    /// NavigationLink, and does not carry `.barButtonText()`, by its first line.
    private func barButtonsWithoutTheFont(_ source: String) -> [String] {
        var missing: [String] = []
        var rest = source[...]
        while let start = rest.range(of: "ToolbarItem(") {
            guard let open = rest[start.upperBound...].firstIndex(of: "{") else { break }
            var depth = 0
            var end = open
            for i in rest[open...].indices {
                if rest[i] == "{" { depth += 1 }
                if rest[i] == "}" { depth -= 1; if depth == 0 { end = i; break } }
            }
            let block = rest[open...end]
            if (block.contains("Button(") || block.contains("NavigationLink(")) && !block.contains(".barButtonText()") {
                missing.append(String(block.prefix(80)))
            }
            rest = rest[rest.index(after: end)...]
        }
        return missing
    }

    /// Decision 204: every text toolbar Button and link in the app goes through `.barButtonText()` (the image-only
    /// avatar link does not need it), and the root font the hosted tests mirror is the app's. Negative: the scanner
    /// flags a toolbar Button without it.
    func testEveryTextBarButtonTakesTheBarButtonFont() throws {
        let folder = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().appendingPathComponent("Skipti/App")
        let files = try FileManager.default.contentsOfDirectory(atPath: folder.path).filter { $0.hasSuffix(".swift") }
        var items = 0
        for file in files {
            let source = try String(contentsOf: folder.appendingPathComponent(file), encoding: .utf8)
            items += source.components(separatedBy: "ToolbarItem(").count - 1
            XCTAssertEqual(barButtonsWithoutTheFont(source), [], file)
        }
        XCTAssertGreaterThanOrEqual(items, 20, "the scan found the toolbars")
        XCTAssertFalse(try appSource("ThemeArt.swift").contains("func barTint() -> some View { tint(Tokens.groundAccent).barButtonText"),
                       "never inside barTint (it wraps the avatar)")
        XCTAssertTrue(try appSource("Appearance.swift").contains(".font(TypeRole.body())\n            .tint(Tokens.accent)"),
                      "the root font the hosted tests mirror")
        // The negatives: the scanner sees a missing one, and passes one that has it.
        let bad = "x.toolbar {\n ToolbarItem(placement: .cancellationAction) { Button(\"Cancel\") { dismiss() }.barTint() }\n}"
        XCTAssertEqual(barButtonsWithoutTheFont(bad).count, 1)
        XCTAssertEqual(barButtonsWithoutTheFont(bad.replacingOccurrences(of: "}.barTint()", with: "}.barButtonText().barTint()")).count, 0)
        XCTAssertEqual(barButtonsWithoutTheFont("ToolbarItem(placement: .topBarTrailing) { accountLink.barTint() }").count, 0)
    }

    // MARK: - U31 cause 1: a live bar measures its title again (review M4)

    /// The cut the founder saw was a live bar's stale title width (the look: the same page entered again was whole). A
    /// live bar whose title label still has a stale width (40 pt, set here as the look found it) is measured whole the
    /// moment `TitleStyle.remeasureTitle` runs (with its body removed the label keeps the stale width: sabotage row S9),
    /// and `TitleStyle.apply` calls it for every live bar (a source pin: see below).
    @MainActor
    func testApplyMeasuresALiveBarsTitleAgain() throws {
        let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
        let window = UIWindow(windowScene: scene)
        window.frame = CGRect(x: 0, y: 0, width: TitleWidth.phone, height: 812)
        defer {
            window.isHidden = true
            TitleStyle.apply(AppearanceState.shared, to: window)
        }
        let root = UIViewController()
        root.navigationItem.title = "Settings"
        let nav = UINavigationController(rootViewController: root)
        let page = UIViewController()
        page.navigationItem.title = "Appearance"
        nav.pushViewController(page, animated: false)
        window.rootViewController = nav
        window.isHidden = false
        let state = AppearanceState()
        state.phoneCategory = .large
        Appearance.apply(state, to: window)
        nav.view.layoutIfNeeded()
        nav.navigationBar.layoutIfNeeded()
        func titleLabel() throws -> UILabel {
            var found: UILabel?
            func walk(_ view: UIView) {
                for sub in view.subviews {
                    if let label = sub as? UILabel, (label.attributedText?.string ?? label.text) == "Appearance" { found = label }
                    walk(sub)
                }
            }
            walk(nav.navigationBar)
            return try XCTUnwrap(found)
        }
        // remeasureTitle itself: a label left at a stale width is measured whole at once.
        let stale = try titleLabel()
        stale.frame.size.width = 40
        XCTAssertEqual(stale.frame.width, 40, "stale, as the look found it")
        TitleStyle.remeasureTitle(of: nav.navigationBar)
        XCTAssertTrue(try titleLabel() === stale, "the same label")
        XCTAssertEqual(stale.frame.width, stale.intrinsicContentSize.width, accuracy: 0.5, "remeasured at once, whole")
        // Through the app's own path: a theme changed on Appearance (the text size left alone) reaches a live bar whose
        // title is stale, and the label is whole when `Appearance.apply` returns. (A bar in a test window is laid out
        // again by UIKit on an appearance change by itself, so the founder's stale width, which a SwiftUI-hosted bar kept,
        // does not reproduce here: that `apply` calls the remeasure for every live bar is pinned in its source below,
        // a pin, not a behaviour proof; stage 1's look is the proof.)
        stale.frame.size.width = 40
        state.theme = .norse
        Appearance.apply(state, to: window)
        let label = try titleLabel()
        XCTAssertEqual(label.frame.width, label.intrinsicContentSize.width, accuracy: 0.5, "measured again at once, whole")
        // And once more on the next turn, when the new font has reached it.
        RunLoop.main.run(until: Date().addingTimeInterval(0.1))
        let next = try titleLabel()
        XCTAssertEqual(next.frame.width, next.intrinsicContentSize.width, accuracy: 0.5, "whole in the new font")
        XCTAssertEqual(next.font.fontName, "CormorantSC-Bold")
        let art = try appSource("ThemeArt.swift")
        XCTAssertTrue(art.contains("bar.titleTextAttributes = inline\n            remeasureTitle(of: bar)"), "apply remeasures every live bar")
    }
}
