import XCTest
@testable import Skipti

/// D-249 (U10): Just me and All my spending on iOS. Core's money goldens are core's (`.drafts/d248/goldens.py`, pinned in
/// core's own tests); these pin the shell's half: the month bounds it passes, the words, the local switches, Add
/// expense's Just me record, the export's name, the Wallet import into Just me, and the Ledger page's meta. Android's
/// twin is SpendingTest.kt.
final class SpendingTests: XCTestCase {
    private func tempStore() throws -> LedgerStore {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("spending-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return LedgerStore(path: dir.appendingPathComponent("v.db").path)
    }

    private func appSource(_ file: String) throws -> String {
        let url = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("Skipti/App/\(file)")
        return try String(contentsOf: url, encoding: .utf8)
    }

    private func appSources() throws -> [String: String] {
        let app = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("Skipti/App")
        var out: [String: String] = [:]
        for file in try FileManager.default.contentsOfDirectory(at: app, includingPropertiesForKeys: nil)
            where file.pathExtension == "swift" {
            out[file.lastPathComponent] = try String(contentsOf: file, encoding: .utf8)
        }
        return out
    }

    // MARK: - The month the shell passes core

    /// The phone's month bounds in ms, [first instant, next month's first instant). Goldens from Python's zoneinfo
    /// (`.drafts/d248/goldens.py` for Kolkata; the Los Angeles lines by the same `datetime(..., tzinfo=...)`): the 23:30
    /// expense on 31 October (1793469600000) is inside October, 00:00 on 1 November (1793471400000) is not.
    func testMonthBoundsAreThePhonesOwnMidnights() throws {
        let kolkata = try XCTUnwrap(TimeZone(identifier: "Asia/Kolkata"))
        let october = SpendingMonth(year: 2026, month: 10)
        XCTAssertEqual(october.bounds(timeZone: kolkata).fromMs, 1_790_793_000_000)
        XCTAssertEqual(october.bounds(timeZone: kolkata).untilMs, 1_793_471_400_000)
        XCTAssertEqual(SpendingMonth.containing(ms: 1_793_469_600_000, timeZone: kolkata), october, "23:30 on the 31st")
        XCTAssertEqual(SpendingMonth.containing(ms: 1_793_471_400_000, timeZone: kolkata), SpendingMonth(year: 2026, month: 11),
                       "00:00 on the 1st is the next month")
        let la = try XCTUnwrap(TimeZone(identifier: "America/Los_Angeles"))
        XCTAssertEqual(october.bounds(timeZone: la).fromMs, 1_790_838_000_000)
        XCTAssertEqual(SpendingMonth(year: 2026, month: 12).bounds(timeZone: la).untilMs, 1_798_790_400_000, "across the year")
        // The negative: the same month in another zone is another window.
        XCTAssertNotEqual(october.bounds(timeZone: la).fromMs, october.bounds(timeZone: kolkata).fromMs)
    }

    func testMonthsStepAndReadInWords() {
        let october = SpendingMonth(year: 2026, month: 10)
        XCTAssertEqual(october.label, "October 2026")
        XCTAssertEqual(october.name, "October")
        XCTAssertEqual(october.adding(3), SpendingMonth(year: 2027, month: 1))
        XCTAssertEqual(october.adding(-10), SpendingMonth(year: 2025, month: 12))
        XCTAssertEqual(SpendingMonth(year: 2026, month: 1).adding(-1).label, "December 2025")
        XCTAssertTrue(october < october.adding(1))
        XCTAssertFalse(october < october)
    }

    // MARK: - The words

    func testTheWordsAreTheApprovedOnes() {
        XCTAssertEqual(SpendingCopy.title, "Your spending")
        XCTAssertEqual(SpendingCopy.justMe, "Just me")
        XCTAssertEqual(SpendingCopy.all, "All my spending")
        XCTAssertEqual(SpendingCopy.thisMonth, "This month")
        XCTAssertEqual(SpendingCopy.more(1), "+1 more")
        XCTAssertEqual(SpendingCopy.empty, "Nothing yet this month")
        XCTAssertEqual(SpendingCopy.spoken(view: "Just me", amount: "four rupees"), "Your spending, Just me, four rupees this month")
        XCTAssertEqual(SpendingCopy.spoken(view: "All my spending", amount: nil), "Your spending, All my spending, Nothing yet this month")
        XCTAssertEqual(SpendingCopy.hint, "Opens your spending")
        XCTAssertEqual(SpendingCopy.allNote, "Your share of group expenses, plus Just me.")
        XCTAssertEqual(SpendingCopy.groupMeta("Goa trip"), "Goa trip, your share")
        XCTAssertEqual(SpendingCopy.columns, ["Category", "Spent", "Share"])
        XCTAssertEqual(SpendingCopy.emptyTitle, "Nothing here yet")
        XCTAssertEqual(SpendingCopy.emptyDetail, "Things you pay for yourself go here. Nobody owes anybody.")
        XCTAssertFalse(SpendingCopy.emptyDetail.lowercased().contains("sync"), "decision 188: no sync word")
        XCTAssertEqual(SpendingCopy.emptyMonth("October"), "Nothing in October")
        XCTAssertEqual(SpendingCopy.export, "Export Just me")
        XCTAssertEqual(SpendingCopy.homeRow, "Spending on Home")
        XCTAssertEqual(SpendingCopy.homeSummary, "This month, under your ledgers")
        XCTAssertEqual(SpendingCopy.onlyYouNothingToSplit, "Only you. Nothing to split.")
        XCTAssertEqual(SpendingCopy.spentAmount("₹381.00"), "spent ₹381.00")
        XCTAssertEqual(SpendingCopy.deleteMessage, "It leaves Just me. You can undo it right after.")
        XCTAssertEqual(SpendingCopy.importIntoJustMe, "Import into Just me")
        XCTAssertEqual(SpendingCopy.importAsGroup, "Import as a new group")
        XCTAssertEqual(NoGroupHeroCopy.kicker, "No groups yet")
        XCTAssertEqual(NoGroupHeroCopy.title, "Splitting with someone?")
        XCTAssertEqual(NoGroupHeroCopy.line, "Make a group for a trip, a flat or a dinner.")
        XCTAssertEqual([NoGroupHeroCopy.create, NoGroupHeroCopy.join, NoGroupHeroCopy.importLink], ["Create a group", "Join", "Import"])
        XCTAssertEqual(AddSheetCopy.ledger, "Ledger")
        XCTAssertEqual(ExpenseCopy.justMe, "Just me")
        XCTAssertEqual(WelcomeCopy.doneLine, "Start a group for a trip, a flat or a dinner, or keep your own spending in Just me.")
    }

    /// The deleted strings are gone from the app (plan section 3): the two "belong to a group" lines, the Expenses tab's
    /// "Personal", Home's old empty state. Scanned in every app source.
    func testTheDeletedStringsAreGone() throws {
        let sources = try appSources()
        XCTAssertGreaterThan(sources.count, 40)
        for gone in ["Expenses belong to a group", "You can't add to your groups from this phone",
                     "static let personal = \"Personal\"", "Create a group, join one you were invited to, or import from another app.",
                     "in a new group with just you"] {
            // The fixture BalanceScreen (a launch-arg proof surface, never a person's Home) keeps its own empty words.
            for (file, text) in sources where !(file == "BalanceScreen.swift" && gone.hasPrefix("Create a group, join")) {
                XCTAssertFalse(text.contains(gone), "\(file) still says \(gone)")
            }
        }
    }

    // MARK: - Home

    func testTheHeroAsksOnlyWithNoGroup() {
        XCTAssertEqual(HomeHero.kind(groupCount: 0), .noGroup)
        XCTAssertEqual(HomeHero.kind(groupCount: 1), .balance)
        XCTAssertEqual(HomeHero.kind(groupCount: 4), .balance)
    }

    /// Spending on Home and the switch are this phone's (decision 189): on and Just me until changed, remembered, and
    /// never in the synced settings' list.
    func testTheSwitchesAreRememberedOnThisPhoneOnly() throws {
        let suite = "spending-\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        XCTAssertEqual(SpendingPrefs.readView(defaults), .justMe, "Just me until chosen")
        XCTAssertTrue(SpendingPrefs.readOnHome(defaults), "on until turned off")
        SpendingPrefs.write(.all, defaults)
        XCTAssertEqual(SpendingPrefs.readView(defaults), .all)
        XCTAssertEqual(defaults.string(forKey: SpendingPrefs.viewKey), "all")
        SpendingPrefs.write(.justMe, defaults)
        XCTAssertEqual(SpendingPrefs.readView(defaults), .justMe)
        defaults.set(false, forKey: SpendingPrefs.onHomeKey)
        XCTAssertFalse(SpendingPrefs.readOnHome(defaults), "off hides the card")
        XCTAssertEqual(SpendingPrefs.view("anything else"), .justMe)
        let synced = SettingsSync.synced.map(\.key)
        XCTAssertFalse(synced.contains(SpendingPrefs.viewKey))
        XCTAssertFalse(synced.contains(SpendingPrefs.onHomeKey))
        // Spending on Home writes UserDefaults itself, never through SettingsSync.
        let row = try appSource("Spending.swift")
        let body = try XCTUnwrap(row.range(of: "struct SpendingHomeRow: View {")).upperBound
        let slice = String(row[body...].prefix(600))
        XCTAssertTrue(slice.contains("@AppStorage(SpendingPrefs.onHomeKey) private var on = true"))
        XCTAssertFalse(slice.contains("SettingsSync"))
        // The card's switch remembers a tap through the same key (sabotage J6: a setter that dropped it survived; the
        // page's setter is pinned by the decision 206 test).
        let home = try appSource("OverviewScreen.swift")
        XCTAssertTrue(home.contains("Binding(get: { SpendingPrefs.view(spendingViewRaw) }, set: { spendingViewRaw = SpendingPrefs.raw($0) })"))
    }

    /// Decision 206: the page opened from the Expenses tab's Just me header always starts in Just me, whatever is
    /// remembered, and opening it writes nothing (the card keeps its view); from the card it opens in the remembered one.
    func testThePageFromTheJustMeHeaderStartsInJustMe() throws {
        XCTAssertEqual(SpendingPrefs.pageStart(fromJustMeHeader: true, stored: "all"), .justMe)
        XCTAssertEqual(SpendingPrefs.pageStart(fromJustMeHeader: true, stored: nil), .justMe)
        // Negative: from the card the remembered All stays All.
        XCTAssertEqual(SpendingPrefs.pageStart(fromJustMeHeader: false, stored: "all"), .all)
        XCTAssertEqual(SpendingPrefs.pageStart(fromJustMeHeader: false, stored: "justMe"), .justMe)
        let list = try appSource("ExpenseListView.swift")
        XCTAssertTrue(list.contains("navigationDestination(isPresented: $openSpending) { SpendingScreen(fromJustMeHeader: true) }"))
        let home = try appSource("OverviewScreen.swift")
        XCTAssertTrue(home.contains("navigationDestination(isPresented: $openSpending) { SpendingScreen() }"))
        // The page reads the start without writing it; only the switch's setter writes.
        let page = try appSource("Spending.swift")
        XCTAssertTrue(page.contains("get: { opened ?? SpendingPrefs.pageStart(fromJustMeHeader: fromJustMeHeader, stored: viewRaw) }"))
        XCTAssertTrue(page.contains("set: { opened = $0; viewRaw = SpendingPrefs.raw($0) }"))
    }

    /// The card is drawn only while Spending on Home is on, and after the ledgers; the no-group hero replaced the old
    /// empty state.
    func testHomeDrawsTheCardUnderTheSwitchAndTheHeroWithNoGroup() throws {
        let home = try appSource("OverviewScreen.swift")
        XCTAssertTrue(home.contains("if loaded && spendingOnHome {\n                            section(SpendingCopy.title)"))
        XCTAssertTrue(home.contains("@AppStorage(SpendingPrefs.onHomeKey) private var spendingOnHome = true"))
        let ledgers = try XCTUnwrap(home.range(of: "section(LedgerCopy.title)")).lowerBound
        let spending = try XCTUnwrap(home.range(of: "section(SpendingCopy.title)")).lowerBound
        XCTAssertLessThan(ledgers, spending, "Your spending sits under Your ledgers")
        XCTAssertTrue(home.contains("HomeHero.kind(groupCount: groups.count) == .noGroup {\n                            noGroupHero"))
        XCTAssertFalse(home.contains("loaded && groups.isEmpty && error == nil {\n                empty("))
    }

    func testTheCardsSpokenWordsComeFromCore() {
        XCTAssertEqual(SpendingView.justMe.label, "Just me")
        XCTAssertEqual(SpendingView.all.label, "All my spending")
    }

    // MARK: - Add expense, Just me

    func testTheLedgerRowAndItsDefaults() {
        XCTAssertEqual(AddExpenseRows.ledgerRow(editing: false, writableCount: 1), .chooser, "Just me plus one group is a choice")
        XCTAssertEqual(AddExpenseRows.ledgerRow(editing: false, writableCount: 3), .chooser)
        XCTAssertEqual(AddExpenseRows.ledgerRow(editing: false, writableCount: 0), .plain, "Just me alone")
        XCTAssertEqual(AddExpenseRows.ledgerRow(editing: true, writableCount: 3), .plain, "an edit never moves ledgers")
        XCTAssertTrue(AddExpenseRows.startsOnJustMe(fromSpending: false, writableCount: 0), "no group: Just me")
        XCTAssertTrue(AddExpenseRows.startsOnJustMe(fromSpending: true, writableCount: 2), "from Your spending")
        XCTAssertFalse(AddExpenseRows.startsOnJustMe(fromSpending: false, writableCount: 2), "the shown group, as before")
        XCTAssertFalse(AddExpenseRows.showsSplit(justMe: true), "Just me hides the split")
        XCTAssertTrue(AddExpenseRows.showsSplit(justMe: false))
        let screen = (try? appSource("AddExpenseScreen.swift")) ?? ""
        XCTAssertTrue(screen.contains("if AddExpenseRows.showsSplit(justMe: justMe) { splitSection }"))
        XCTAssertTrue(screen.contains("private var splitSection: some View {\n                header(AddSheetCopy.split)"))
    }

    /// Add expense with Just me writes a personal-scope Expense record: no group, you paid, shares [you: total], checked
    /// by core first (a refused amount writes nothing).
    func testAJustMeExpenseIsAPersonalRecordOfYoursAlone() throws {
        let store = try tempStore()
        let me = try store.ensureSelf()
        let dto = JustMeExpense.dto(you: me.id, totalMinor: 124_000, currency: "INR", note: "Big Basket order",
                                    category: nil, nowMs: 1_791_174_600_000)
        XCTAssertNil(dto.group)
        XCTAssertEqual(dto.payer, me.id)
        XCTAssertEqual(dto.shares, [.init(id: me.id, amountMinor: 124_000)])
        XCTAssertEqual(dto.addedBy, me.id)
        _ = try store.appendExpense(dto)
        let records = try store.allRecords().filter { $0.recordType == LedgerStore.Sync.expenseType }
        XCTAssertEqual(records.count, 1)
        XCTAssertNil(records[0].groupId, "the personal scope")
        let projected = try XCTUnwrap(store.projectedExpenses().expenses.first)
        XCTAssertNil(projected.expense.group)
        XCTAssertEqual(projected.expense.shares, [IdAmount(id: me.id, amountMinor: 124_000)])
        // The negative: core refuses a negative amount before anything is written.
        XCTAssertThrowsError(try store.appendExpense(JustMeExpense.dto(you: me.id, totalMinor: -1, currency: "INR",
                                                                     note: nil, category: nil)))
        XCTAssertEqual(try store.allRecords().filter { $0.recordType == LedgerStore.Sync.expenseType }.count, 1)
        // An edit's money keeps the stored payer as the whole share, and no group.
        let money = JustMeExpense.money(payer: me.id, totalMinor: 9_900, currency: "INR")
        XCTAssertNil(money.group)
        XCTAssertEqual(money.shares, [IdAmount(id: me.id, amountMinor: 9_900)])
        XCTAssertNoThrow(try ffiCheckExpense(expense: money))
    }

    /// Your spending reads the store through core: Just me counts in full, All adds only your share of a group expense
    /// (you paid ₹900.00 for two: ₹450.00 counts). Totals worked by hand: 1,240.00 and 1,240.00 + 450.00.
    func testYourSpendingReadsJustMeAndYourShare() throws {
        let store = try tempStore()
        try store.setYourName("Sam")
        let me = try store.ensureSelf()
        let now = RelativeWhen.nowMs
        _ = try store.appendExpense(JustMeExpense.dto(you: me.id, totalMinor: 124_000, currency: "INR", note: "Groceries",
                                                     category: nil, nowMs: now))
        let group = try store.createGroup(name: "Flat 4B", settlementCurrency: "INR", people: ["Meera"])
        let shares = try ffiSplitCompute(totalMinor: 90_000, currencyCode: "INR", members: group.group.members, spec: .equal)
        _ = try store.appendExpense(LedgerStore.ExpenseDTO(
            payer: group.selfId, paidMinor: 90_000, currencyCode: "INR", group: group.group.id, conversion: nil,
            shares: shares.map { .init(id: $0.id, amountMinor: $0.minorUnits) }, note: "Weekly groceries",
            timestampMs: now, category: nil, addedBy: group.selfId))
        let month = SpendingMonth.current()
        let justMe = try SpendingLoad.load(store, month: month, view: .justMe)
        XCTAssertEqual(justMe.out.currencies.map(\.totalMinor), [124_000])
        XCTAssertEqual(justMe.out.rows.count, 1)
        XCTAssertTrue(justMe.hasAny)
        let all = try SpendingLoad.load(store, month: month, view: .all)
        XCTAssertEqual(all.out.currencies.map(\.totalMinor), [169_000])
        XCTAssertEqual(Set(all.out.rows.map(\.amountMinor)), [124_000, 45_000])
        XCTAssertEqual(all.groupNames[group.group.id], "Flat 4B")
        // Another month is empty, and says so as a month (there is spending elsewhere).
        let last = try SpendingLoad.load(store, month: month.adding(-1), view: .justMe)
        XCTAssertTrue(last.out.rows.isEmpty)
        XCTAssertTrue(last.hasAny)
        // A store with nothing at all: "Nothing here yet".
        let empty = try SpendingLoad.load(try tempStore(), month: month, view: .justMe)
        XCTAssertFalse(empty.hasAny)
    }

    /// Decision 205: each group is read with its own "you", never a list of your ids matched in every group (an id
    /// that is you in G can be someone else in H: the D-237 A1 shape). Core's golden pins the counting.
    func testEachGroupKeepsItsOwnYou() {
        let g = NamedGroupWire(group: GroupWire(id: "g", name: "G", settlementCurrency: "INR", members: ["a", "c"]),
                               selfId: "a", members: [])
        let h = NamedGroupWire(group: GroupWire(id: "h", name: "H", settlementCurrency: "INR", members: ["a", "d"]),
                               selfId: "d", members: [])
        XCTAssertEqual(SpendingLoad.groups(named: [g, h]),
                       [SpendingGroupIn(groupId: "g", viewer: "a"), SpendingGroupIn(groupId: "h", viewer: "d")])
        // Negative: "a" is not you in H, and a group listed twice keeps its first "you" (core refuses a repeat).
        XCTAssertFalse(SpendingLoad.groups(named: [g, h]).contains(SpendingGroupIn(groupId: "h", viewer: "a")))
        XCTAssertEqual(SpendingLoad.groups(named: [g, g]).count, 1)
        XCTAssertEqual(SpendingLoad.groups(named: []), [])
    }

    // MARK: - The Ledger page (decision 193)

    func testTheLedgerRowsCarryTheirMeta() throws {
        let kolkata = try XCTUnwrap(TimeZone(identifier: "Asia/Kolkata"))
        XCTAssertEqual(LedgerMeta.sinceMs(created: "1791174600000:3:0011223344556677"), 1_791_174_600_000)
        XCTAssertNil(LedgerMeta.sinceMs(created: "not a stamp"))
        XCTAssertEqual(LedgerMeta.groupParts(sinceMs: 1_791_174_600_000, people: 3, timeZone: kolkata),
                       ["Since October 2026", "3 people"])
        XCTAssertEqual(LedgerMeta.groupParts(sinceMs: nil, people: 1, timeZone: kolkata), ["1 person"])
        XCTAssertEqual(LedgerMeta.justMeParts, ["Only you"])
        let g = NamedGroupWire(group: GroupWire(id: "g", name: "Goa trip", settlementCurrency: "INR", members: ["a", "b"]),
                               selfId: "a", members: [])
        let options = LedgerOptions.build(groups: [g], created: ["g": "1793469600000:0:aa"], timeZone: kolkata)
        XCTAssertEqual(options.map(\.title), ["Just me", "Goa trip"], "Just me first")
        XCTAssertEqual(options.map(\.choice), [.justMe, .group("g")])
        XCTAssertEqual(options[1].parts, ["Since October 2026", "2 people"])
        XCTAssertEqual(options[0].parts, ["Only you"])
    }

    // MARK: - Export Just me

    func testTheJustMeExportIsNamedByMonthAndCleanedUp() throws {
        let date = Date(timeIntervalSince1970: 1_790_835_120) // 2026-10-01T06:12:00Z
        XCTAssertEqual(Exporter.justMeName(date, format: .csv), "skipti-just-me-2026-10.csv")
        XCTAssertEqual(Exporter.justMeName(date, format: .json), "skipti-just-me-2026-10.json")
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("exp-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        try Data("x".utf8).write(to: dir.appendingPathComponent("skipti-just-me-2026-10.csv"))
        try Data("x".utf8).write(to: dir.appendingPathComponent("keep.csv"))
        XCTAssertEqual(try Exporter.removeExports(in: dir), 1)
        XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: dir.path), ["keep.csv"])
        // The personal slice: group-less expenses only.
        let me = String(repeating: "a", count: 32), g = String(repeating: "b", count: 32)
        func port(_ group: String?) -> PortExpense {
            PortExpense(payer: me, paidMinor: 100, currencyCode: "INR", group: group, conversion: nil,
                        shares: [ExpenseShare(id: me, amountMinor: 100)], note: nil, timestampMs: nil, category: nil)
        }
        let slice = ffiSnapshotPersonal(snapshot: LedgerSnapshot(
            groups: [PortGroup(id: g, name: "G", settlementCurrency: "INR", members: [me])],
            expenses: [port(nil), port(g)], settlements: [], redistributions: [], profile: nil))
        XCTAssertEqual(slice.expenses.map(\.group), [nil])
        XCTAssertTrue(slice.groups.isEmpty)
    }

    // MARK: - Wallet into Just me

    func testAWalletFileGoesIntoJustMeWithNoGroup() throws {
        let store = try tempStore()
        XCTAssertEqual(ImportTarget.effective(.justMe, source: .budgetBakers), .justMe)
        XCTAssertEqual(ImportTarget.effective(.justMe, source: .splitwise), .newGroup, "Splitwise stays a group")
        XCTAssertEqual(ImportTarget.allCases.map(\.title), ["Import into Just me", "Import as a new group"])
        let job = try ImportJob.prepareJustMe(store: store, csv: ImportProofScreen.budgetBakersSheet())
        XCTAssertNil(job.group)
        XCTAssertEqual(job.ledgerName, "Just me")
        XCTAssertFalse(job.plan.expenses.isEmpty)
        XCTAssertTrue(job.plan.expenses.allSatisfy { $0.group == nil })
        try job.commit(job.plan.expenses, store: store)
        XCTAssertTrue(try store.loadGroups().isEmpty, "no group is made")
        let me = try store.ensureSelf()
        let saved = try store.loadExpenses()
        XCTAssertEqual(saved.count, job.plan.expenses.count)
        XCTAssertTrue(saved.allSatisfy { $0.group == nil && $0.payer == me.id })
        let records = try store.allRecords().filter { $0.recordType == LedgerStore.Sync.expenseType }
        XCTAssertTrue(records.allSatisfy { $0.groupId == nil })
    }

    // MARK: - Delete and edit

    func testJustMeDeletesInItsOwnWordsAndEdits() {
        XCTAssertEqual(ExpenseDeleteCopy.message(justMe: true), "It leaves Just me. You can undo it right after.")
        XCTAssertEqual(ExpenseDeleteCopy.message(justMe: false), "It leaves your balances. You can undo it right after.")
        XCTAssertTrue(ExpenseDeleteCopy.canEdit(group: nil, named: nil), "Just me edits")
        XCTAssertFalse(ExpenseDeleteCopy.canEdit(group: "g", named: nil), "a group this phone no longer holds does not")
    }

    func testTheExpensesTabNarrowsToJustMe() {
        let me = String(repeating: "a", count: 32)
        func e(_ id: String, _ group: String?, _ at: UInt64) -> ProjectedExpenseOut {
            ProjectedExpenseOut(id: id, expense: ExpenseInput(payer: me, paidMinor: 100, currencyCode: "INR",
                                                               shares: [IdAmount(id: me, amountMinor: 100)], group: group, conversion: nil),
                                note: nil, category: nil, timestampMs: at, addedBy: nil, addedAtMs: at, deleted: false,
                                lastEditedAtMs: nil, lastEditedBy: nil, seen: [], moneyConflict: [])
        }
        let list = [e("1", nil, 3), e("2", "g", 2), e("3", nil, 1)]
        XCTAssertEqual(ExpenseSections.build(list, filter: .justMe).map(\.groupID), [nil])
        XCTAssertEqual(ExpenseSections.build(list, filter: .justMe).first?.expenses.map(\.id), ["1", "3"])
        XCTAssertEqual(ExpenseSections.build(list, filter: .all).map(\.groupID), ["g", nil], "Just me still last")
        XCTAssertEqual(ExpenseSections.build(list, filter: .group("g")).map(\.groupID), ["g"])
    }

    // MARK: - Sabotage pins (D-249 iOS lane, .drafts/d249/sab-ios)

    /// Export Just me on the shell's own path: the model hands the flag to `Exporter.write`, so the file is
    /// `skipti-just-me-YYYY-MM` (the whole ledger keeps `skipti-export-`), and the page reads core's personal slice, never
    /// the whole snapshot (a group expense would leave in a "Just me" file).
    func testExportJustMeWritesTheJustMeFileOfThePersonalSlice() throws {
        let model = ExportFlowModel()
        model.acknowledge()
        let empty = LedgerSnapshot(groups: [], expenses: [], settlements: [], redistributions: [], profile: nil)
        let justMe = try model.export(format: .csv, snapshot: empty, justMe: true)
        defer { try? FileManager.default.removeItem(at: justMe) }
        XCTAssertTrue(justMe.lastPathComponent.hasPrefix(Exporter.justMePrefix), justMe.lastPathComponent)
        // Negative: the whole ledger's export keeps its own name.
        let whole = try model.export(format: .csv, snapshot: empty)
        defer { try? FileManager.default.removeItem(at: whole) }
        XCTAssertTrue(whole.lastPathComponent.hasPrefix(Exporter.filePrefix), whole.lastPathComponent)
        XCTAssertFalse(whole.lastPathComponent.hasPrefix(Exporter.justMePrefix))
        // The page's snapshot for Export Just me is core's personal slice (`ffiSnapshotPersonal`, pinned above by its
        // own test), taken once, and the whole or one group's snapshot only otherwise.
        let page = try appSource("ExportFlow.swift")
        XCTAssertTrue(page.contains("let s = justMe ? ffiSnapshotPersonal(snapshot: try store.snapshot())\n"
                                    + "                    : try group.map { try store.snapshot(group: $0) } ?? store.snapshot()"), page)
        XCTAssertEqual(page.components(separatedBy: "ffiSnapshotPersonal(").count - 1, 1)
        XCTAssertTrue(try appSource("Spending.swift").contains("ExportScreen(store: store, justMe: true)"), "the page's export item")
    }

    /// Spending on Home draws the number of summary lines the width tables measure (decision 82): the app row and
    /// DoneMomentsTests' row say the same, two.
    func testSpendingOnHomeDrawsTheLinesTheWidthTableMeasures() throws {
        let app = try appSource("Spending.swift")
        let table = try String(contentsOf: URL(fileURLWithPath: #filePath).deletingLastPathComponent()
            .appendingPathComponent("DoneMomentsTests.swift"), encoding: .utf8)
        func lines(_ text: String, _ pattern: String) throws -> Int? {
            let re = try NSRegularExpression(pattern: pattern)
            guard let m = re.firstMatch(in: text, range: NSRange(text.startIndex..., in: text)),
                  let r = Range(m.range(at: 1), in: text) else { return nil }
            return Int(text[r])
        }
        let drawn = try XCTUnwrap(lines(app, #"parts: \[SpendingCopy\.homeSummary\], summaryLines: (\d+),"#))
        let measured = try XCTUnwrap(lines(table, #"label: "Spending on Home", parts: \[SpendingCopy\.homeSummary\], lines: (\d+),"#))
        XCTAssertEqual(drawn, measured, "the row draws what the width table measures")
        XCTAssertEqual(drawn, 2)
    }
}
