import XCTest
@testable import Skipti

/// Groups and people on iOS (D-135), proven against a real vault: fresh
/// installs carry nothing from testing, names and ids persist in step, your
/// name reaches every group, the founder's old fixture vault migrates without
/// rewriting an expense, and a corrupt group list is never overwritten.
final class GroupStoreTests: XCTestCase {
    private var dir: URL!

    override func setUpWithError() throws {
        dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("groups-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: dir)
    }

    private var path: String { dir.appendingPathComponent("ledger.db").path }
    private func store() -> LedgerStore { LedgerStore(path: path) }

    private func writeRaw(_ data: Data, key: String) throws {
        let vault = try VaultGate.open(path: path, keystore: KeychainKeystore())
        defer { try? vault.closeVault() }
        try vault.kvSet(key: key, value: data)
    }

    private func readRaw(key: String) throws -> Data? {
        let vault = try VaultGate.open(path: path, keystore: KeychainKeystore())
        defer { try? vault.closeVault() }
        return try vault.kvGet(key: key)
    }

    func testAFreshInstallHasNoGroupsAndNothingFromTesting() throws {
        let store = store()
        XCTAssertEqual(try store.loadGroups(), [])
        XCTAssertEqual(try store.namedGroups(), [])
        XCTAssertNil(try store.currentGroup())
        XCTAssertNil(try readRaw(key: LedgerStore.groupsKey), "reading must not seed")
        let me = try store.ensureSelf()
        XCTAssertNil(me.name, "no invented name")
        XCTAssertEqual(me.id.count, 32)
        XCTAssertNotEqual(me.id, LedgerStore.LegacyFixture.ada, "a new install is not the fixture Ada")
    }

    func testCreatingAGroupNeedsYourNameThenPersistsNamesAndSelection() throws {
        let store = store()
        XCTAssertThrowsError(try store.createGroup(name: "Goa", settlementCurrency: "INR", people: ["Rahul"])) {
            XCTAssertEqual($0 as? LedgerStore.GroupStoreError, .needsYourName)
        }
        XCTAssertEqual(try store.setYourName("  Tim  ").name, "Tim")
        let created = try store.createGroup(name: "Goa", settlementCurrency: "INR", people: ["Rahul", "", " Priya "])
        XCTAssertEqual(created.members.map(\.name), ["Tim", "Rahul", "Priya"], "blank rows are ignored")
        XCTAssertEqual(created.group.members, created.members.map(\.id))

        let reopened = LedgerStore(path: path)
        XCTAssertEqual(try reopened.namedGroups(), [created], "persisted exactly")
        XCTAssertEqual(try reopened.currentGroup()?.group.id, created.group.id, "the new group is selected")
        XCTAssertEqual(LedgerStore.label(for: created.selfId, in: created), "Tim (You)")
        XCTAssertEqual(LedgerStore.label(for: created.members[1].id, in: created), "Rahul")
    }

    /// D-172: a person added by mistake can be taken out, and removing a row
    /// from the middle takes exactly that name. Twin of Android's test.
    func testARemovedPersonRowIsNotInTheNewGroup() throws {
        var people = PeopleDraft()
        XCTAssertEqual(people.addLabel, "Add another person")
        people.rows[0].name = "Ann"
        people.add()
        people.rows[1].name = "Bob"
        people.add()
        people.rows[2].name = "Cy"
        let bob = people.rows[1].id
        people.remove(bob)
        XCTAssertEqual(people.names, ["Ann", "Cy"], "the middle row took its own name")
        people.add()
        XCTAssertFalse(people.rows.dropLast().map(\.id).contains(people.rows.last!.id), "ids are never reused")
        for row in people.rows { people.remove(row.id) }
        XCTAssertEqual(people.names, [])
        XCTAssertEqual(people.addLabel, "Add a person")

        var draft = PeopleDraft()
        draft.rows[0].name = "Rahul"
        draft.add()
        draft.rows[1].name = "Oops"
        draft.remove(draft.rows[1].id)
        let store = store()
        try store.setYourName("Tim")
        let created = try store.createGroup(name: "Goa", settlementCurrency: "INR", people: draft.names)
        XCTAssertEqual(created.members.map(\.name), ["Tim", "Rahul"])
    }

    /// D-174: the group list said "1 people". Android's twin is in GroupStoreTest.
    func testOnePersonIsSingular() {
        XCTAssertEqual(GroupCopy.people(1), "1 person")
        XCTAssertEqual(GroupCopy.people(2), "2 people")
        XCTAssertEqual(GroupCopy.people(0), "0 people")
    }

    func testAddingAndRenamingPeoplePersistAndDuplicatesAreRefused() throws {
        let store = store()
        try store.setYourName("Tim")
        let created = try store.createGroup(name: "Flat", settlementCurrency: "INR", people: ["Rahul", "Priya"])
        let rahul = created.members[1].id
        try store.addMember(groupID: created.group.id, name: "Amit")
        try store.renameMember(groupID: created.group.id, memberID: rahul, name: "Rahul K")
        XCTAssertThrowsError(try store.addMember(groupID: created.group.id, name: "priya"), "another member's name")
        XCTAssertThrowsError(try store.renameMember(groupID: created.group.id, memberID: rahul, name: "Amit"))

        let reloaded = try LedgerStore(path: path).namedGroups()[0]
        XCTAssertEqual(reloaded.members.map(\.name), ["Tim", "Rahul K", "Priya", "Amit"])
        XCTAssertEqual(reloaded.members[1].id, rahul, "a rename keeps the id")
    }

    func testRenamingYourselfCarriesIntoEveryGroup() throws {
        let store = store()
        try store.setYourName("Tim")
        try store.createGroup(name: "Goa", settlementCurrency: "INR", people: ["Rahul"])
        try store.createGroup(name: "Flat", settlementCurrency: "USD", people: ["Priya"])
        try store.setYourName("Timothy")
        let groups = try LedgerStore(path: path).namedGroups()
        XCTAssertEqual(groups.count, 2)
        for named in groups {
            XCTAssertEqual(named.members.first(where: { $0.id == named.selfId })?.name, "Timothy")
        }
    }

    func testAnOldFixtureVaultMigratesWithoutRewritingAnExpense() throws {
        // The shape the pre-D-135 seeding wrote: fixture ids, no names.
        let ada = LedgerStore.LegacyFixture.ada
        let ids = (1...4).map { String(repeating: "a", count: 31) + "\($0)" }
        let legacy = [LedgerStore.GroupDTO(id: "b" + String(repeating: "0", count: 31), name: "Goa Trip",
                                           settlementCurrency: "INR", members: ids)]
        try writeRaw(try JSONEncoder().encode(legacy), key: LedgerStore.groupsKey)
        let expensesBefore = try readRaw(key: LedgerStore.key)

        let store = store()
        XCTAssertEqual(try store.ensureSelf().id, ada, "the fixture Ada becomes you, so records keep their payer")
        XCTAssertThrowsError(try store.namedGroups()) {
            XCTAssertEqual($0 as? LedgerStore.GroupStoreError, .needsYourName)
        }
        try store.setYourName("Tim")
        let migrated = try store.namedGroups()[0]
        XCTAssertEqual(migrated.members.map(\.name), ["Tim", "Rahul", "Priya", "Amit"])
        XCTAssertEqual(migrated.group.members, ids, "ids and order untouched")
        XCTAssertNotNil(try store.loadGroups()[0].memberNames, "names are now stored")
        XCTAssertEqual(try readRaw(key: LedgerStore.key), expensesBefore, "no expense record rewritten")
    }

    /// Regression for the draft's `try? … ?? []`: a group list that cannot be
    /// read must stop `createGroup`, never be replaced by the new group alone.
    func testACorruptGroupListIsAnErrorAndCreatingAGroupNeverOverwritesIt() throws {
        let store = store()
        try store.setYourName("Tim")
        let corrupt = Data("not json".utf8)
        try writeRaw(corrupt, key: LedgerStore.groupsKey)
        XCTAssertThrowsError(try store.loadGroups())
        XCTAssertThrowsError(try store.createGroup(name: "New", settlementCurrency: "INR", people: []))
        XCTAssertEqual(try readRaw(key: LedgerStore.groupsKey), corrupt, "the unreadable list is left exactly as it was")
    }

    /// A group whose people never had names stored (the shape an older writer
    /// or an import leaves) gets distinct "Member N" placeholders, never one
    /// repeated "Member" that core's duplicate-name rule would refuse.
    func testAGroupWithUnnamedMembersGetsDistinctPlaceholderNames() throws {
        let ada = LedgerStore.LegacyFixture.ada
        let others = (1...2).map { String(repeating: "c", count: 31) + "\($0)" }
        let legacy = [LedgerStore.GroupDTO(id: "d" + String(repeating: "0", count: 31), name: "Flat",
                                           settlementCurrency: "INR", members: [ada] + others)]
        try writeRaw(try JSONEncoder().encode(legacy), key: LedgerStore.groupsKey)
        let store = store()
        try store.setYourName("Tim")
        let named = try store.namedGroups()[0]
        XCTAssertEqual(named.members.map(\.name), ["Tim", "Member 2", "Member 3"])
        XCTAssertEqual(named.group.members, [ada] + others, "ids and order untouched")
    }

    // MARK: - D-218: who can be invited

    /// The People screen offers an invite only to a member who is not you and whose
    /// place has no claim; a claim (the joiner's own, or any appended later) takes the
    /// member off the list. Real core, real vault.
    func testInvitableOffersOnlyUnclaimedPlacesAndNeverYou() throws {
        let store = store()
        try store.setYourName("Tim")
        let named = try store.createGroup(name: "Goa", settlementCurrency: "INR", people: ["Rahul", "Priya"])
        let rahul = try XCTUnwrap(named.members.first { $0.name == "Rahul" })
        func offered() throws -> [String] {
            try PeopleScreen.invitable(store, named: named, signedIn: true).map(\.name)
        }
        XCTAssertEqual(try PeopleScreen.invitable(store, named: named, signedIn: false), [], "signed out: nobody")
        XCTAssertEqual(try PeopleScreen.invitable(store, named: nil, signedIn: true), [], "no group: nobody")
        XCTAssertEqual(try offered(), ["Rahul", "Priya"], "everyone but you")
        XCTAssertFalse(try offered().contains("Tim"), "you are never offered")
        let device = try LedgerStore.Sync.deviceId(store)
        let rahulsAccount = String(repeating: "e", count: 32)
        try LedgerStore.Sync.appendGroupOps(store, [
            Invites.claim(group: named.group.id, member: rahul.id, you: rahulsAccount, device: device),
        ], placing: .none)
        XCTAssertEqual(try offered(), ["Priya"], "a claimed place is never offered")
        // Your own claim (the creator writes it) changes nothing: you were never offered.
        try LedgerStore.Sync.appendGroupOps(store, [
            Invites.claim(group: named.group.id, member: named.selfId, you: named.selfId, device: device),
        ], placing: .none)
        XCTAssertEqual(try offered(), ["Priya"])
    }

    // ---- D-225 Task 6: New group and Your name in list cards (Design 12) ----

    /// D-225 (Design 12): New group and Your name leave the system Form for the theme's list cards on its ground, as
    /// Android's: every field a `FieldRow`, Settles in a value row opening the currency list page (no quick Picker and no
    /// "All currencies" link), the clear cross Lucide's `x` in a 48 target (no SF symbol), each section's explanation a
    /// caption under its card, the action bars as before.
    func testNewGroupIsListCards() throws {
        let url = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("Skipti/App/GroupSheets.swift")
        let sheets = try String(contentsOf: url, encoding: .utf8)
        func count(_ needle: String) -> Int { sheets.components(separatedBy: needle).count - 1 }
        for gone in ["Form {", "Picker(", "Section {", "TextField(", "systemName: \"xmark.circle.fill\"", "Image(systemName:",
                     ".font(.system(", "CurrencyCopy.allCurrencies", "auroraFormBackground", "listRowInsets", "listRowSeparator"] {
            XCTAssertEqual(count(gone), 0, "GroupSheets.swift still has \(gone)")
        }
        let settles = "NavigationLink {\n                        CurrencyListScreen(selection: $currency)\n                    } label: {\n"
            + "                        ListRow(title: \"Settles in\", trailing: .value(currency, chevron: true))"
        XCTAssertEqual(count(settles), 1, "Settles in is a value row to the currency list page")
        XCTAssertEqual(count("LucideIcon(glyph: .x, size: ThemeArt.m(\"clearGlyph\"))"), 1, "the clear cross is Lucide's x")
        XCTAssertTrue(sheets.contains(".frame(width: ThemeArt.m(\"touchTarget\"), height: ThemeArt.m(\"touchTarget\"))"), "in a 48 target")
        XCTAssertEqual(count("FieldRow(\"Group name\", text: $groupName)"), 1)
        XCTAssertEqual(count("FieldRow(\"Your name\", text: $"), 2, "Your name on New group and on its own sheet")
        // D-248 (U29.2): and driven by the sheet's focus (Return adds a row).
        XCTAssertEqual(count("FieldRow(\"Name\", text: $row.name, spoken: \"Person \\(index + 1)\",\n"), 1, "a person's field, named to VoiceOver")
        for caption in ["A group settles in one currency, and it cannot be changed later.",
                        "You see yourself as \\\"Name (You)\\\". Others in the group see only the name.",
                        "Add people by name. They do not need SKIPTI; linking a name to their phone comes later.",
                        "You will see yourself as \\\"\\(preview) (You)\\\". People you share a group with see \\\"\\(preview)\\\"."] {
            XCTAssertEqual(count("CardCaption(\"\(caption)\")"), 1, caption)
        }
        XCTAssertEqual(count("ListCard {"), 4, "Your name; Group; You; People")
        XCTAssertEqual(count(".auroraBackground()"), 2)
        XCTAssertEqual(count(".formActionBar {"), 2, "Save name and Create group stay in the action bar")
        for label in ["Your name", "Group", "You", "People"] {
            XCTAssertEqual(count("header(\"\(label)\")"), 1, label)
        }
        XCTAssertTrue(sheets.contains("Button(people.addLabel) { withAnimation { people.add() } }\n                    .buttonStyle(SecondaryButtonStyle())"))
        // FieldRow speaks its `spoken` name when given ("Person 2"), else its placeholder (Android's `FieldRow(spoken:)`).
        let rows = try String(contentsOf: url.deletingLastPathComponent().appendingPathComponent("ListRows.swift"), encoding: .utf8)
        XCTAssertTrue(rows.contains(".keyboardType(keyboard)\n            .accessibilityLabel(Text(verbatim: spoken ?? placeholder))"))
    }

    /// D-227 (a): every tab stays alive on iOS (D-180), so a group made on Balance, or a name set there, reaches the
    /// other tabs only through the sync revision. Balance's two completions call this seam after their own read.
    @MainActor
    func testBalancesCompletionsTellEveryTabOnce() {
        let status = SyncStatus()
        let before = status.revision
        OverviewActions.changed(status)
        XCTAssertEqual(status.revision, before + 1, "exactly one bump")
        OverviewActions.changed(status)
        XCTAssertEqual(status.revision, before + 2)
    }

    /// D-227 (a), the Groups tab: a person added or renamed from a group's People screen shows on every tab, from
    /// the Groups tab's two People links as from the group page's three (the same seam after the screen's own read).
    func testEveryPeopleScreenCompletionTellsEveryTab() throws {
        let app = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("Skipti/App")
        for (file, expected) in [("GroupsScreen.swift", 2), ("OverviewScreen.swift", 3)] {
            let source = try String(contentsOf: app.appendingPathComponent(file), encoding: .utf8)
            let calls = source.components(separatedBy: "\n").filter { $0.contains("PeopleScreen(store: store, groupID:") }
            XCTAssertEqual(calls.count, expected, file)
            for call in calls {
                XCTAssertTrue(call.hasSuffix("OverviewActions.changed() }"), "\(file): \(call)")
            }
        }
    }
}
