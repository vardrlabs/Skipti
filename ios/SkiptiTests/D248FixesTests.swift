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

    /// Core's typed parser through the bindings: a subset of `.drafts/d248/typed_goldens.py`'s table (derived there with
    /// exact Fractions, never read out of core), so the shell reads exactly what core's own test pins.
    func testTypedAmountsReadOneCommaAsTheDecimalMark() {
        let goldens: [(String, String, Int64?)] = [
            ("12,50", "EUR", 1_250), (",5", "EUR", 50), ("1,005", "KWD", 1_005), (" 7,10 ", "USD", 710),
            ("12.50", "EUR", 1_250), ("1005", "JPY", 1_005),
            ("1,234,567", "INR", nil), ("1,234.56", "USD", nil), ("1.234,56", "EUR", nil), ("12,505", "EUR", nil),
            ("1,0005", "KWD", nil), ("10,5", "JPY", nil), ("1,234", "INR", nil), (",", "EUR", nil), ("", "EUR", nil),
        ]
        for (text, code, want) in goldens {
            XCTAssertEqual(try? ffiMoneyParseTyped(text: text, currencyCode: code), want, "\(code) '\(text)'")
            XCTAssertEqual(AmountEntry.minor(text, currency: code), want, "\(code) '\(text)'")
        }
        // Import's strict parser is untouched: a comma never reads there.
        XCTAssertThrowsError(try ffiMoneyParseMinor(digits: "12,50", currencyCode: "EUR"))
    }

    /// "Enter an amount like {example}.": the example in the currency's own decimals (core's entry form of 1,250 minor
    /// units) and the phone's decimal mark.
    func testANonAmountIsRefusedInWordsWithTheLocalesMark() {
        XCTAssertEqual(AmountEntry.mark(en), ".")
        XCTAssertEqual(AmountEntry.mark(de), ",")
        XCTAssertEqual(AmountEntry.mark(Locale(identifier: "fr_FR")), ",")
        XCTAssertEqual(AmountEntry.mark(Locale(identifier: "en_IN")), ".")
        XCTAssertEqual(AmountEntry.likeWords(currency: "INR", locale: en), "Enter an amount like 12.50.")
        XCTAssertEqual(AmountEntry.likeWords(currency: "EUR", locale: de), "Enter an amount like 12,50.")
        XCTAssertEqual(AmountEntry.example(currency: "JPY", locale: de), "1250", "no decimals in yen")
        XCTAssertEqual(AmountEntry.example(currency: "KWD", locale: de), "1,250", "three in dinars")
        XCTAssertNil(AmountEntry.example(currency: "XYZ", locale: en), "no example is made up")
        XCTAssertEqual(AmountEntry.likeWords(currency: "XYZ", locale: en), "Enter an amount.")
        XCTAssertEqual(try AmountEntry.parse("12,50", currency: "EUR", locale: de), 1_250)
        XCTAssertThrowsError(try AmountEntry.parse("12,5,0", currency: "EUR", locale: de)) { error in
            XCTAssertEqual(error as? AmountEntry.Refusal, AmountEntry.Refusal(words: "Enter an amount like 12,50."))
        }
        XCTAssertThrowsError(try AmountEntry.parse("10,5", currency: "JPY", locale: en)) { error in
            XCTAssertEqual(error as? AmountEntry.Refusal, AmountEntry.Refusal(words: "Enter an amount like 1250."))
        }
        // Too large keeps core's own error, which SplitWords says ("That amount is too large.").
        XCTAssertThrowsError(try AmountEntry.parse("99999999999999999999", currency: "INR", locale: en)) { error in
            guard case CoreError.Overflow = error else { return XCTFail("\(error)") }
        }
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
        XCTAssertTrue(try appSource("SettleAll.swift").contains("guard let minor = AmountEntry.minor(text, currency: currency)"))
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
        XCTAssertEqual(try spec(.itemised, lines: [("900", [me])], tax: "10,50", tip: "5"),
                       .itemised(lines: [LineInput(amountMinor: 90_000, participants: [me])], taxMinor: 1_050, tipMinor: 500))
        // A blank exact row: named at Save, nothing while the live line counts.
        XCTAssertThrowsError(try spec(.exact, exact: [me: "500"])) { error in
            XCTAssertEqual(error as? AmountEntry.Refusal, AmountEntry.Refusal(words: "Enter an amount for Rahul."))
        }
        XCTAssertEqual(try spec(.exact, exact: [me: "500"], blanksAsZero: true),
                       .exact(entries: [IdAmount(id: me, amountMinor: 50_000), IdAmount(id: rahul, amountMinor: 0)]))
        XCTAssertEqual(try spec(.exact, exact: [me: "500,25", rahul: "1.5"]),
                       .exact(entries: [IdAmount(id: me, amountMinor: 50_025), IdAmount(id: rahul, amountMinor: 150)]))
        // What is typed and not an amount: AmountEntry's words, never core's raw error.
        XCTAssertThrowsError(try spec(.exact, exact: [me: "5x", rahul: "1"], locale: de)) { error in
            XCTAssertEqual(error as? AmountEntry.Refusal, AmountEntry.Refusal(words: "Enter an amount like 12,50."))
        }
        XCTAssertThrowsError(try spec(.itemised, lines: [("900", [me])], tax: "1,2,3")) { error in
            XCTAssertEqual(error as? AmountEntry.Refusal, AmountEntry.Refusal(words: "Enter an amount like 12.50."))
        }
        // An adjustment left blank is none; an item left blank is 0, and core says so in words.
        XCTAssertEqual(try spec(.adjustment, adjustment: [me: "-2,50"]),
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
        XCTAssertTrue(add.contains("if AmountFocus.focusesOnOpen(editing: editing != nil, already: focusedOnce) {"))
    }

    // MARK: - U30.1 tab-proof words

    func testNoMessageSendsThePersonToATab() throws {
        var sites = 0
        for (name, text) in try appSources() {
            XCTAssertFalse(text.contains("on Balance first"), name)
            sites += text.components(separatedBy: "\"Add your name first.\"").count - 1
            sites += text.components(separatedBy: "\"Create a group first.\"").count - 1
        }
        // ImportFlow's (older), Settle up's two, Add expense's two.
        XCTAssertEqual(sites, 5)
    }

    // MARK: - U30.4 the delete's Undo where it happened

    @MainActor
    func testTheDeleteUndoIsTheToastWhereverTheDetailOpened() throws {
        let store = LedgerStore(path: dir.appendingPathComponent("ledger.db").path)
        _ = try store.appendExpense(LedgerStore.ExpenseDTO(
            payer: me, paidMinor: 120_000, currencyCode: "INR", group: group, conversion: nil,
            shares: [.init(id: me, amountMinor: 60_000), .init(id: rahul, amountMinor: 60_000)],
            note: "Dinner", timestampMs: 1_000, category: nil, addedBy: me
        ), nowMs: 5_000)
        let dinner = try XCTUnwrap(store.projectedExpenses().expenses.first)
        try store.reviseExpense(ExpenseEditing.delete(dinner, editor: me), nowMs: 6_000)
        XCTAssertEqual(try store.loadExpenses(), [])

        let center = DoneCenter(gate: SuccessGate(), haptic: { _, _ in }, announce: { _ in })
        ExpenseUndo.offer(dinner, store: store, editor: me, center: center, screenReader: false)
        guard case .small(let line) = center.current else { return XCTFail("a toast") }
        XCTAssertEqual(line, DoneLine(title: "Expense deleted", parts: ["Dinner"], action: "Undo", actionSeconds: 5))
        center.act(center.serial)
        XCTAssertEqual(try store.loadExpenses().map(\.note), ["Dinner"], "Undo restored it")
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
    }

    // MARK: - U30.6 a new group opens

    @MainActor
    func testANewGroupOpensItsPageOnHomeOnce() throws {
        let opening = GroupOpening()
        XCTAssertNil(opening.take())
        opening.request("g1")
        XCTAssertEqual(opening.pending, "g1")
        XCTAssertEqual(opening.take(), "g1")
        XCTAssertNil(opening.take(), "once")
        // Every create path opens it once its sheet has gone; Home pushes it on its own stack.
        let overview = try appSource("OverviewScreen.swift")
        XCTAssertTrue(overview.contains(".navigationDestination(item: $openedGroup) { opened in\n            GroupPageScreen(groupID: opened.id)"))
        XCTAssertTrue(overview.contains("if let id = createdID { createdID = nil; openedGroup = OpenedGroup(id: id) }"))
        XCTAssertTrue(overview.contains("if let id = GroupOpening.shared.take() { openedGroup = OpenedGroup(id: id) }"))
        let groups = try appSource("GroupsScreen.swift")
        XCTAssertTrue(groups.contains("if let id = createdID { createdID = nil; onCreated(id) }"))
        let shell = try appSource("HomeShell.swift")
        XCTAssertTrue(shell.contains("roots[.balance, default: 0] += 1\n"))
        XCTAssertTrue(shell.contains("DispatchQueue.main.async { GroupOpening.shared.request(id) }"))
        XCTAssertTrue(shell.contains("if let id = startedGroupID { startedGroupID = nil; GroupOpening.shared.request(id) }"))
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
            AddSheetCopy.splitType, AddSheetCopy.paidBy, AddSheetCopy.group,
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
        // The cut the founder saw was a live bar's stale title width (the look: the same page entered again was whole):
        // every live bar has its title measured again in the new font.
        let art = try appSource("ThemeArt.swift")
        XCTAssertTrue(art.contains("bar.titleTextAttributes = inline\n            remeasureTitle(of: bar)"))
        XCTAssertTrue(art.contains("if let label = sub as? UILabel, (label.attributedText?.string ?? label.text) == title {"))
        XCTAssertTrue(art.contains("v.invalidateIntrinsicContentSize()"))
        let bar = UINavigationBar(frame: CGRect(x: 0, y: 0, width: 375, height: 44))
        bar.items = [UINavigationItem(title: "Appearance")]
        TitleStyle.remeasureTitle(of: bar)
        XCTAssertEqual(bar.topItem?.title, "Appearance", "the title is kept")
        // Large titles are untouched: they scale past the inline cap.
        XCTAssertGreaterThan(TitleStyle.font(large: true, theme: .norse, category: .accessibilityLarge).pointSize, TitleStyle.inlineCap(.norse))
    }
}
