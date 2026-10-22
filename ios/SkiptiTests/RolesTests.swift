import FirebaseFirestore
import XCTest
@testable import Skipti

final class RolesTests: XCTestCase {
    private let o = "1" + String(repeating: "0", count: 31), a = "2" + String(repeating: "0", count: 31)
    private let m = "3" + String(repeating: "0", count: 31)

    func testARoleComesFromTheCloudsList() {
        let roles = RoleRules.Roles(owner: o, admins: [a])
        XCTAssertEqual(RoleRules.role(of: o, in: roles), .owner)
        XCTAssertEqual(RoleRules.role(of: a, in: roles), .admin)
        XCTAssertEqual(RoleRules.role(of: m, in: roles), .member)
        XCTAssertEqual(RoleRules.role(of: nil, in: roles), .unjoined)
    }

    func testASheetOffersWhatTheViewerMayDo() {
        // The founder's rules (2026-10-13); twins: RolesTest.kt.
        typealias O = RoleRules.Offer
        XCTAssertEqual(RoleRules.offer(viewer: .owner, target: .member, isSelf: false),
                       O(remove: true, ask: false, askSelf: false, makeAdmin: true, removeAdmin: false, makeOwner: true))
        XCTAssertEqual(RoleRules.offer(viewer: .owner, target: .admin, isSelf: false),
                       O(remove: true, ask: false, askSelf: false, makeAdmin: false, removeAdmin: true, makeOwner: true))
        XCTAssertEqual(RoleRules.offer(viewer: .owner, target: .unjoined, isSelf: false),
                       O(remove: true, ask: false, askSelf: false, makeAdmin: false, removeAdmin: false, makeOwner: false))
        XCTAssertEqual(RoleRules.offer(viewer: .admin, target: .member, isSelf: false),
                       O(remove: true, ask: false, askSelf: false, makeAdmin: true, removeAdmin: false, makeOwner: false))
        XCTAssertEqual(RoleRules.offer(viewer: .admin, target: .admin, isSelf: false),
                       O(remove: false, ask: true, askSelf: false, makeAdmin: false, removeAdmin: false, makeOwner: false))
        XCTAssertEqual(RoleRules.offer(viewer: .admin, target: .owner, isSelf: false),
                       O(remove: false, ask: false, askSelf: false, makeAdmin: false, removeAdmin: false, makeOwner: false))
        XCTAssertEqual(RoleRules.offer(viewer: .member, target: .member, isSelf: false),
                       O(remove: false, ask: true, askSelf: false, makeAdmin: false, removeAdmin: false, makeOwner: false))
        XCTAssertEqual(RoleRules.offer(viewer: .member, target: .member, isSelf: true),
                       O(remove: false, ask: false, askSelf: true, makeAdmin: false, removeAdmin: false, makeOwner: false))
        XCTAssertEqual(RoleRules.offer(viewer: .owner, target: .owner, isSelf: true),
                       O(remove: false, ask: false, askSelf: false, makeAdmin: false, removeAdmin: false, makeOwner: false))
        XCTAssertEqual(RoleRules.offer(viewer: .admin, target: .admin, isSelf: true),
                       O(remove: false, ask: false, askSelf: true, makeAdmin: false, removeAdmin: false, makeOwner: false))
        XCTAssertTrue(RoleRules.canDelete(.owner))
        XCTAssertFalse(RoleRules.canDelete(.admin))
    }

    func testARequestCardShowsToWhoeverItConcerns() {
        XCTAssertEqual(RoleRules.requestCard(viewer: .owner, person: .member, viewerIsAsker: false, viewerIsPerson: false), .decide)
        XCTAssertEqual(RoleRules.requestCard(viewer: .admin, person: .member, viewerIsAsker: true, viewerIsPerson: false), .decide)
        XCTAssertEqual(RoleRules.requestCard(viewer: .member, person: .member, viewerIsAsker: true, viewerIsPerson: false), .asker)
        XCTAssertEqual(RoleRules.requestCard(viewer: .member, person: .member, viewerIsAsker: false, viewerIsPerson: true), .person)
        XCTAssertEqual(RoleRules.requestCard(viewer: .admin, person: .admin, viewerIsAsker: false, viewerIsPerson: true), .person)
        XCTAssertNil(RoleRules.requestCard(viewer: .member, person: .member, viewerIsAsker: false, viewerIsPerson: false))
    }

    func testANoticeIsReadOnlyInItsOwnShape() {
        let g = String(repeating: "c", count: 32)
        let good: [String: Any] = ["kind": "removed", "groupId": g, "groupName": "Goa trip", "byName": "Prithvi"]
        XCTAssertEqual(Notices.item(id: "n1", data: good)?.kind, .removed)
        XCTAssertNil(Notices.item(id: "n1", data: good.merging(["kind": "hacked"]) { $1 }))
        XCTAssertNil(Notices.item(id: "n1", data: good.merging(["groupId": "x"]) { $1 }))
        XCTAssertNil(Notices.item(id: "n1", data: good.merging(["groupName": 4]) { $1 }))
        XCTAssertTrue(PushRoute.isGroup(["kind": "group", "groupId": g]))
        XCTAssertFalse(PushRoute.isGroup(["kind": "group", "groupId": "x"]))
    }

    func testOnlyAFunctionWrittenRemovalComesIn() throws {
        let group = String(repeating: "c", count: 32), me = String(repeating: "a", count: 32)
        let them = String(repeating: "b", count: 32), sam = String(repeating: "d", count: 32)
        func record(_ type: UInt8, _ blob: Data) -> RecordWire {
            RecordWire(version: 1, recordType: type, groupId: group, epoch: 0, hlcMillis: 1, hlcCounter: 0,
                       hlcDeviceId: them, authorDeviceId: them, nonceSession: Data(), flags: 0, blob: blob)
        }
        let removal = record(3, try ffiEncodeGroupOp(op: .removeMember(groupId: group, memberId: sam)))
        let moved = record(6, try ffiEncodeRedistribution(r: RedistributionInput(
            group: group, from: sam, currencyCode: "INR", shares: [IdAmount(id: me, amountMinor: -500)])))
        let rename = record(3, try ffiEncodeGroupOp(op: .renameGroup(groupId: group, name: "Goa")))
        func refusal(_ r: RecordWire, _ removal: Bool) -> CloudRecords.Refusal? {
            CloudRecords.refusal(r, removal: removal, from: group, thisDevice: me)
        }
        XCTAssertNil(refusal(removal, true))
        XCTAssertNil(refusal(moved, true))
        XCTAssertEqual(refusal(removal, false), .unverifiedRemoval, "a phone wrote it, not the Function")
        XCTAssertEqual(refusal(moved, false), .unverifiedRemoval)
        XCTAssertNil(refusal(rename, false), "other operations need no mark")
    }

    func testTheWordsMatchAndroid() {
        XCTAssertEqual(RolesCopy.askNote(name: "Sam"), "Only the owner or an admin can remove someone. Sam, the owner and the admins will see your request.")
        XCTAssertEqual(RolesCopy.makeAdminTitle("Asha"), "Make Asha an admin?")
        XCTAssertEqual(RolesCopy.makeOwnerBody("Asha", group: "Goa trip"), "Asha will own Goa trip: names admins, removes anyone, can delete the group. You become an admin. Only Asha can undo this.")
        XCTAssertEqual(RolesCopy.moved(from: "Sam", amount: "₹1,000.00", to: ["Prithvi", "Rahul", "Asha"]), "Sam's ₹1,000.00 moved to Prithvi, Rahul and Asha")
        XCTAssertEqual(RolesCopy.moved(from: "Sam", amount: "₹5.00", to: ["Nina"]), "Sam's ₹5.00 moved to Nina")
        XCTAssertEqual(RolesCopy.moved(from: "Sam", amount: "₹5.00", to: ["A", "B"]), "Sam's ₹5.00 moved to A and B")
    }
    // MARK: - The ledger side of D-210 (not in the brief's list)

    private func tempStore() throws -> LedgerStore {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("roles-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return LedgerStore(path: dir.appendingPathComponent("v.db").path)
    }

    private func movedRecord(group: String, from: String, to: String, amount: Int64, millis: UInt64,
                             device: String = String(repeating: "b", count: 32)) throws -> RecordWire {
        RecordWire(
            version: 1, recordType: LedgerStore.Sync.redistributionType, groupId: group, epoch: 0,
            hlcMillis: millis, hlcCounter: 0, hlcDeviceId: device, authorDeviceId: device,
            nonceSession: Data([0x52, 0x44, 0x53, 0x01]), flags: 0,
            blob: try ffiEncodeRedistribution(r: RedistributionInput(
                group: group, from: from, currencyCode: "INR", shares: [IdAmount(id: to, amountMinor: amount)]))
        )
    }

    func testRedistributionsProjectInHlcOrderWhateverTheArrivalOrder() throws {
        let store = try tempStore()
        let g = String(repeating: "c", count: 32), a = String(repeating: "a", count: 32)
        let sam = String(repeating: "d", count: 32), kit = String(repeating: "e", count: 32)
        let late = try movedRecord(group: g, from: kit, to: a, amount: 300, millis: 2_000)
        let early = try movedRecord(group: g, from: sam, to: a, amount: -500, millis: 1_000)
        _ = try CloudRecords.apply([late, early], store: store)
        try store.rebuildProjection()
        let list = try store.loadRedistributions()
        XCTAssertEqual(list.map(\.from), [sam, kit], "oldest HLC first, on every phone")
        XCTAssertEqual(list.first?.shares, [IdAmount(id: a, amountMinor: -500)])
        // A record core cannot decode is skipped, not fatal.
        var broken = early
        broken.hlcMillis = 3_000
        broken.blob = Data([1, 2, 3])
        _ = try CloudRecords.apply([broken], store: store)
        try store.rebuildProjection()
        XCTAssertEqual(try store.loadRedistributions().count, 2)
    }

    func testAReadOnlyCopyIsMarkedAndForgettingErasesOnlyThatGroup() throws {
        let store = try tempStore()
        let g1 = String(repeating: "c", count: 32), g2 = String(repeating: "f", count: 32)
        let a = String(repeating: "a", count: 32), sam = String(repeating: "d", count: 32)
        _ = try CloudRecords.apply([
            try movedRecord(group: g1, from: sam, to: a, amount: -500, millis: 1_000),
            try movedRecord(group: g2, from: sam, to: a, amount: -700, millis: 2_000),
        ], store: store)
        try store.rebuildProjection()
        XCTAssertEqual(store.readOnly(), [:])
        let when = Date(timeIntervalSince1970: 1_800_000_000)
        try store.markReadOnly(g1, LedgerStore.ReadOnly(kind: "removed", by: "Prithvi", at: when))
        try store.markReadOnly(g2, LedgerStore.ReadOnly(kind: "deleted", by: "Asha", at: when))
        XCTAssertEqual(store.readOnly()[g1], LedgerStore.ReadOnly(kind: "removed", by: "Prithvi", at: when))
        try store.kvSet(key: CloudRecords.cursorKey(g1), value: Data("123".utf8))
        try store.forgetGroup(g1)
        XCTAssertEqual(try store.allRecords().compactMap(\.groupId), [g2], "the other group's records stay")
        XCTAssertEqual(try store.loadRedistributions().map(\.group), [g2])
        XCTAssertEqual(Set(store.readOnly().keys), [g2])
        XCTAssertEqual(CloudRecords.cursor(store, g1), 0)
        try store.clearReadOnly(g2)
        XCTAssertEqual(store.readOnly(), [:])
    }

    // MARK: - Fix 1 (review of Task 5)

    /// A joiner's phone after the group removed them: Tim's phone (A) made
    /// Goa and Pune with Rahul in each; Rahul's phone (B) claimed both
    /// places; the removeMember Function then wrote Goa's removal of Rahul's
    /// place, and a redistribution of what Rahul owed there onto Sam.
    private struct Removed {
        let a: LedgerStore, b: LedgerStore
        let goa: String, pune: String, me: String, tim: String, rahul: String, sam: String
    }

    private func removedJoiner() throws -> Removed {
        let a = try tempStore(), b = try tempStore()
        let timSelf = try a.setYourName("Tim")
        let goa = try a.createGroup(name: "Goa", settlementCurrency: "INR", people: ["Rahul", "Sam"])
        let pune = try a.createGroup(name: "Pune", settlementCurrency: "INR", people: ["Rahul"])
        let rahul = goa.members.first { $0.name == "Rahul" }!.id
        let sam = goa.members.first { $0.name == "Sam" }!.id
        let rahulPune = pune.members.first { $0.name == "Rahul" }!.id
        // Tim paid 1,000 in Goa, half of it Rahul's: Rahul owes 500.
        _ = try a.appendExpense(LedgerStore.ExpenseDTO(
            payer: timSelf.id, paidMinor: 1_000, currencyCode: "INR", group: goa.group.id, conversion: nil,
            shares: [.init(id: timSelf.id, amountMinor: 500), .init(id: rahul, amountMinor: 500)]
        ))
        let me = try b.setYourName("Rahul")
        // Only the groups' records travel; Tim's own "you" is his account's.
        for record in try a.allRecords() where record.groupId != nil { _ = try b.applyRecord(record) }
        let device = ClaimDeviceIn(deviceId: String(repeating: "e", count: 32),
                                   agreementPublicHex: String(repeating: "11", count: 32))
        try LedgerStore.Sync.appendGroupOps(b, [
            .claim(groupId: goa.group.id, memberId: rahul, youId: me.id, devices: [device]),
            .claim(groupId: pune.group.id, memberId: rahulPune, youId: me.id, devices: [device]),
        ], placing: .none)
        try b.rebuildProjection()
        XCTAssertEqual(try b.namedGroups().map(\.group.id), [goa.group.id, pune.group.id], "joined both")
        // What the Function writes, a minute later, from Tim's side.
        let later = UInt64(Date().timeIntervalSince1970 * 1000) + 60_000
        let timDevice = try LedgerStore.Sync.deviceId(a)
        _ = try b.applyRecord(try LedgerStore.Sync.groupOpRecord(
            .removeMember(groupId: goa.group.id, memberId: rahul),
            hlc: HlcStamp(millis: later, counter: 0), deviceId: timDevice))
        _ = try b.applyRecord(try movedRecord(group: goa.group.id, from: rahul, to: sam, amount: -500,
                                              millis: later, device: timDevice).with(counter: 1))
        try b.rebuildProjection()
        return Removed(a: a, b: b, goa: goa.group.id, pune: pune.group.id, me: me.id, tim: timSelf.id,
                       rahul: rahul, sam: sam)
    }

    func testARemovedJoinersLedgerStillLoads() throws {
        let r = try removedJoiner()
        let groups = try r.b.namedGroups()
        XCTAssertEqual(groups.map(\.group.id), [r.goa, r.pune], "every group, the read-only one too")
        let goa = try XCTUnwrap(groups.first { $0.group.id == r.goa })
        XCTAssertEqual(goa.selfId, r.me)
        XCTAssertTrue(goa.members.contains { $0.id == r.me }, "you stay in your own copy")
        XCTAssertFalse(goa.members.contains { $0.id == r.rahul }, "as one person, not two")
        // The projection still lists this account's own place as removed; only
        // the screens' list keeps "you" (as one person, above).
        let projected = try XCTUnwrap(LedgerStore.Sync.projectGroups(r.b).groups.first { $0.group.id == r.goa })
        XCTAssertEqual(projected.removed, [r.me])
    }

    /// D-210 last wave: removed, then invited back as a NEW place and claimed.
    /// This phone still remembers the old place as one of its own (kv self
    /// aliases) although its claim is no longer in the log: the old, removed
    /// place is not a second person, only the new place stays.
    func testARemovedAccountThatRejoinsHasOnlyItsNewPlace() throws {
        let r = try removedJoiner()
        let c = try tempStore()
        let me = try c.setYourName("Rahul")
        for record in try r.a.allRecords() where record.groupId != nil { _ = try c.applyRecord(record) }
        try c.kvSet(key: LedgerStore.selfKey, value: try JSONEncoder().encode(
            LedgerStore.SelfDTO(id: me.id, name: "Rahul", aliases: [r.rahul])))
        let timDevice = try LedgerStore.Sync.deviceId(r.a)
        let rahul2 = String(repeating: "7", count: 32)
        let later = UInt64(Date().timeIntervalSince1970 * 1000) + 60_000
        _ = try c.applyRecord(try LedgerStore.Sync.groupOpRecord(
            .removeMember(groupId: r.goa, memberId: r.rahul),
            hlc: HlcStamp(millis: later, counter: 0), deviceId: timDevice))
        _ = try c.applyRecord(try LedgerStore.Sync.groupOpRecord(
            .addMember(groupId: r.goa, member: GroupOpMemberIn(id: rahul2, name: "Rahul")),
            hlc: HlcStamp(millis: later, counter: 1), deviceId: timDevice))
        try LedgerStore.Sync.appendGroupOps(c, [
            Invites.claim(group: r.goa, member: rahul2, you: me.id, device: String(repeating: "e", count: 32)),
        ], placing: .none)
        try c.rebuildProjection()
        let goa = try XCTUnwrap(try c.namedGroups().first { $0.group.id == r.goa })
        XCTAssertEqual(goa.selfId, me.id)
        XCTAssertTrue(goa.members.contains { $0.id == me.id }, "the new place, read as you")
        XCTAssertFalse(goa.members.contains { $0.id == r.rahul }, "the old removed place is dropped, no second Rahul")
        XCTAssertEqual(goa.members.filter { $0.name == "Rahul" || $0.id == me.id }.count, 1)
        // D-211 final review (M8): the profile share goes to the new place; the
        // old removed place merged into you never drops the group.
        let places = ProfileShare.places(try LedgerStore.Sync.projectGroups(c), readOnly: Set(c.readOnly().keys), known: [:])
        XCTAssertEqual(places.first { $0.groupId == r.goa }?.memberId, rahul2, "the new place")
    }

    func testTheRemovedPersonsCopyShowsThemAtZero() throws {
        let r = try removedJoiner()
        let moved = try r.b.loadRedistributions()
        XCTAssertEqual(moved, [RedistributionInput(group: r.goa, from: r.me, currencyCode: "INR",
                                                   shares: [IdAmount(id: r.sam, amountMinor: -500)])],
                       "the place they had reads as them")
        let lines = try ffiBalancesCompute(
            expenses: try r.b.loadExpenses().filter { $0.group == r.goa }.map(\.asInput),
            settlements: try r.b.loadSettlements().filter { $0.group == r.goa }.map(\.asInput),
            redistributions: moved
        )
        let inr = try XCTUnwrap(lines.first { $0.currencyCode == "INR" })
        func net(_ id: String) -> Int64 { inr.entries.first { $0.id == id }?.minorUnits ?? 0 }
        XCTAssertEqual(net(r.me), 0, "no old balance plus an opposite entry")
        XCTAssertEqual(net(r.rahul), 0)
        XCTAssertEqual(net(r.tim), 500)
        XCTAssertEqual(net(r.sam), -500)
    }

    // MARK: - D-230 F1 (decision 67): the history names a place this phone merged

    private func inr(_ minor: Int64) throws -> String { try XCTUnwrap(RolesMoney.format(minor, "INR")) }

    /// The group's "moved" history lines as the activity list reads them.
    private func movedLines(_ store: LedgerStore, _ group: String) throws -> [String] {
        let names = try store.historyNames(group: group)
        return ActivityFeed.moved(try store.allRecords(), only: group).compactMap { ActivityFeed.movedLine($0, names: names) }
    }

    /// F1 (a): the removed joiner's own copy. The redistribution keeps the raw
    /// old place, which this phone merged into you: it reads as your own name,
    /// not "Unknown member" (before any rejoin). Twin: RolesTest.kt.
    func testTheRemovedJoinersOwnCopyNamesItsOldPlace() throws {
        let r = try removedJoiner()
        XCTAssertTrue(try LedgerStore.Sync.projectGroups(r.b).otherYouIds.contains(r.rahul), "the old place is merged into you")
        XCTAssertEqual(try r.b.historyNames(group: r.goa)[r.rahul], "Rahul")
        XCTAssertEqual(try movedLines(r.b, r.goa), [RolesCopy.moved(from: "Rahul", amount: try inr(500), to: ["Sam"])])
    }

    /// F1 (b): after a rejoin (a new place, claimed by the same account) the
    /// old place stays merged and the line still names the account.
    func testARejoinersOwnCopyStillNamesItsOldPlace() throws {
        let r = try removedJoiner()
        let rahul2 = String(repeating: "7", count: 32)
        let later = UInt64(Date().timeIntervalSince1970 * 1000) + 61_000
        _ = try r.b.applyRecord(try LedgerStore.Sync.groupOpRecord(
            .addMember(groupId: r.goa, member: GroupOpMemberIn(id: rahul2, name: "Rahul")),
            hlc: HlcStamp(millis: later, counter: 0), deviceId: try LedgerStore.Sync.deviceId(r.a)))
        try LedgerStore.Sync.appendGroupOps(r.b, [
            Invites.claim(group: r.goa, member: rahul2, you: r.me, device: String(repeating: "e", count: 32)),
        ], placing: .none)
        try r.b.rebuildProjection()
        let p = try LedgerStore.Sync.projectGroups(r.b)
        XCTAssertTrue(p.otherYouIds.contains(rahul2), "rejoined: the new place is yours")
        XCTAssertTrue(p.otherYouIds.contains(r.rahul), "the old place stays merged")
        XCTAssertEqual(try r.b.historyNames(group: r.goa)[r.rahul], "Rahul")
        XCTAssertEqual(try movedLines(r.b, r.goa), [RolesCopy.moved(from: "Rahul", amount: try inr(500), to: ["Sam"])])
    }

    /// F1 (c): on Tim's phone, a share to Rahul's account "you" (read through
    /// the alias of Rahul's claim in Pune) is named as Rahul's place, so
    /// "moved to" keeps it instead of dropping it.
    func testAnAliasedYouIsNamedInMovedTo() throws {
        let r = try removedJoiner()
        for record in try r.b.allRecords() where record.groupId == r.pune { _ = try r.a.applyRecord(record) }
        let later = UInt64(Date().timeIntervalSince1970 * 1000) + 61_000
        _ = try r.a.applyRecord(try movedRecord(group: r.pune, from: r.tim, to: r.me, amount: -300, millis: later))
        try r.a.rebuildProjection()
        let p = try LedgerStore.Sync.projectGroups(r.a)
        XCTAssertTrue(p.aliases.contains { $0.groupId == r.pune && $0.fromId == r.me }, "Rahul's claim is an alias here")
        XCTAssertFalse(p.otherYouIds.contains(r.me), "not this account's")
        XCTAssertEqual(try r.a.historyNames(group: r.pune)[r.me], "Rahul")
        XCTAssertEqual(try movedLines(r.a, r.pune), [RolesCopy.moved(from: "Tim", amount: try inr(300), to: ["Rahul"])])
    }

    /// F1 (d), negative: an id neither merged nor aliased stays unnamed:
    /// "Unknown member" as `from`, left out of "moved to". An alias of another
    /// group, or merged ids with no "you" name, name nothing.
    func testAnIdNeitherMergedNorAliasedStaysUnknown() throws {
        let r = try removedJoiner()
        let stranger = String(repeating: "9", count: 32)
        let names = try r.b.historyNames(group: r.goa)
        XCTAssertNil(names[stranger])
        let fromStranger = ActivityFeed.Moved(id: "x", group: r.goa, atMs: 1, from: stranger, currency: "INR", amountMinor: 200, to: [r.sam])
        XCTAssertEqual(ActivityFeed.movedLine(fromStranger, names: names),
                       RolesCopy.moved(from: "Unknown member", amount: try inr(200), to: ["Sam"]))
        let toStranger = ActivityFeed.Moved(id: "y", group: r.goa, atMs: 1, from: r.sam, currency: "INR", amountMinor: 200, to: [stranger, r.tim])
        XCTAssertEqual(ActivityFeed.movedLine(toStranger, names: names),
                       RolesCopy.moved(from: "Sam", amount: try inr(200), to: ["Tim"]))
        let other = LedgerStore.namingMergedPlaces(
            ["t": "Asha"], you: "you", otherYouIds: ["old"],
            aliases: [IdAlias(groupId: "other", fromId: "f", toId: "t")], group: "g")
        XCTAssertEqual(other, ["t": "Asha"], "another group's alias, and a you with no name, fill nothing")
    }

    /// F1 (e), negative: neither loop overwrites a name the history already has.
    func testAMergedPlaceNeverOverwritesAName() {
        let names = ["you": "Rahul", "old": "Rahul (old)", "f": "Kit", "t": "Asha"]
        let out = LedgerStore.namingMergedPlaces(
            names, you: "you", otherYouIds: ["old"], aliases: [IdAlias(groupId: "g", fromId: "f", toId: "t")], group: "g")
        XCTAssertEqual(out["old"], "Rahul (old)", "a merged id keeps its own name")
        XCTAssertEqual(out["f"], "Kit", "an aliased id keeps its own name")
        XCTAssertEqual(out, names)
    }

    /// F1 (f), negative (review M2): with no "you" the ledger merges nothing,
    /// so an applied alias of this group names nothing either.
    func testWithNoYouAnAliasNamesNothing() {
        let out = LedgerStore.namingMergedPlaces(
            ["t": "Asha"], you: nil, otherYouIds: ["old"], aliases: [IdAlias(groupId: "g", fromId: "f", toId: "t")], group: "g")
        XCTAssertNil(out["f"], "an alias with no you stays unnamed")
        XCTAssertEqual(out, ["t": "Asha"])
    }

    func testOnlyARemovalOrADeletionStopsAGroupSyncing() {
        XCTAssertTrue(Notices.Kind.removed.endsSync)
        XCTAssertTrue(Notices.Kind.deleted.endsSync)
        XCTAssertFalse(Notices.Kind.role.endsSync, "a new role keeps the group syncing")
        XCTAssertFalse(Notices.Kind.declined.endsSync)
        XCTAssertFalse(Notices.Kind.request.endsSync)
    }

    func testACatchUpThatCannotApplyItsRecordsFails() throws {
        let g = String(repeating: "c", count: 32), sam = String(repeating: "d", count: 32)
        let record = try movedRecord(group: g, from: sam, to: String(repeating: "a", count: 32),
                                     amount: -500, millis: 1_000)
        var document = CloudRecords.document(record, uid: "u")
        document["createdAt"] = Timestamp(seconds: 1, nanoseconds: 0)
        document["removal"] = true
        let good = try tempStore()
        XCTAssertEqual(try CloudRecords.absorb([document], groupId: g, store: good).count, 1)
        // A vault that will not open: the catch-up says so, the notice stays.
        let broken = LedgerStore(path: FileManager.default.temporaryDirectory
            .appendingPathComponent("missing-\(UUID().uuidString)/none/v.db").path)
        XCTAssertThrowsError(try CloudRecords.absorb([document], groupId: g, store: broken))
    }

    /// A rebuild that threw after the records were applied: the next snapshot
    /// finds them present (nothing fresh) and must still rebuild.
    func testARebuildThatFailedIsRetriedWhenTheRecordsComeAgain() throws {
        let g = String(repeating: "c", count: 32), sam = String(repeating: "d", count: 32)
        let record = try movedRecord(group: g, from: sam, to: String(repeating: "a", count: 32),
                                     amount: -500, millis: 1_000)
        var document = CloudRecords.document(record, uid: "u")
        document["createdAt"] = Timestamp(seconds: 1, nanoseconds: 0)
        document["removal"] = true
        let store = try tempStore()
        // Applied, projection never rebuilt (as after a failed rebuild).
        XCTAssertEqual(try CloudRecords.apply([record], store: store).count, 1)
        XCTAssertEqual(try store.loadRedistributions().count, 0, "the screens' ledger is stale")
        let fresh = try CloudRecords.absorb([document], groupId: g, store: store)
        XCTAssertTrue(fresh.isEmpty, "nothing new: the record was already in")
        XCTAssertEqual(try store.loadRedistributions().count, 1, "rebuilt anyway")
    }

    func testAnUnreadableReadOnlyListIsAnErrorNeverOverwritten() throws {
        let store = try tempStore()
        let g = String(repeating: "c", count: 32)
        let mark = LedgerStore.ReadOnly(kind: "removed", by: "Tim", at: Date(timeIntervalSince1970: 1_800_000_000))
        XCTAssertEqual(try store.readOnlyChecked(), [:])
        try store.markReadOnly(g, mark)
        XCTAssertEqual(try store.readOnlyChecked(), [g: mark])
        try store.kvSet(key: LedgerStore.readOnlyKey, value: Data("not json".utf8))
        XCTAssertThrowsError(try store.readOnlyChecked())
        XCTAssertEqual(store.readOnly(), [:], "pushAll's tolerant read")
        XCTAssertThrowsError(try store.markReadOnly(String(repeating: "f", count: 32), mark))
        XCTAssertThrowsError(try store.clearReadOnly(g))
        XCTAssertEqual(try store.kvGet(key: LedgerStore.readOnlyKey), Data("not json".utf8), "left as it was")
    }

    func testRolesAreAskedForOncePerLaunchAndOnEveryChange() {
        XCTAssertTrue(GroupRoles.needsAsking(seen: nil, now: "o|a"), "first sight, cached or not")
        XCTAssertFalse(GroupRoles.needsAsking(seen: "o|a", now: "o|a"))
        XCTAssertTrue(GroupRoles.needsAsking(seen: "o|a", now: "o|"))
    }

    // D-227 (Design 6): RoleAsking. Twins: RolesTest.kt.
    private let groupA = String(repeating: "c", count: 32), groupB = String(repeating: "d", count: 32)

    func testRoleAskingNeverAsksForAnUnconfirmedSnapshot() {
        var asking = RoleAsking()
        XCTAssertFalse(asking.shouldAsk(groupA, signature: "o|a", confirmed: false), "a pending write is not the server's answer")
        XCTAssertFalse(asking.shouldAsk(groupA, signature: "o|a", confirmed: false))
        XCTAssertTrue(asking.shouldAsk(groupA, signature: "o|a", confirmed: true), "the confirmed snapshot asks")
    }

    func testRoleAskingAsksOnceWhileInFlight() {
        var asking = RoleAsking()
        XCTAssertTrue(asking.shouldAsk(groupA, signature: "o|a", confirmed: true))
        XCTAssertFalse(asking.shouldAsk(groupA, signature: "o|a", confirmed: true), "never twice at once")
        XCTAssertFalse(asking.shouldAsk(groupA, signature: "o|b", confirmed: true), "not even for a new signature")
        XCTAssertTrue(asking.shouldAsk(groupB, signature: "o|a", confirmed: true), "another group asks on its own")
    }

    func testRoleAskingAsksAgainAfterAFailedRefresh() {
        var asking = RoleAsking()
        XCTAssertTrue(asking.shouldAsk(groupA, signature: "o|a", confirmed: true))
        asking.finished(groupA, signature: "o|a", ok: false)
        XCTAssertTrue(asking.shouldAsk(groupA, signature: "o|a", confirmed: true), "not found or offline: ask again")
    }

    func testRoleAskingKeepsASignatureOnlyAfterASuccessfulRefresh() {
        var asking = RoleAsking()
        XCTAssertTrue(asking.shouldAsk(groupA, signature: "o|a", confirmed: true))
        asking.finished(groupA, signature: "o|a", ok: true)
        XCTAssertFalse(asking.shouldAsk(groupA, signature: "o|a", confirmed: true), "the same roles are not asked again")
        XCTAssertTrue(asking.shouldAsk(groupA, signature: "o|", confirmed: true), "changed roles are")
    }

    func testRoleAskingForgetsAGroupThatLeft() {
        var asking = RoleAsking()
        for g in [groupA, groupB] {
            XCTAssertTrue(asking.shouldAsk(g, signature: "o|a", confirmed: true))
            asking.finished(g, signature: "o|a", ok: true)
        }
        asking.keep(only: [groupB])
        XCTAssertTrue(asking.shouldAsk(groupA, signature: "o|a", confirmed: true), "A left: seen fresh when it comes back")
        XCTAssertFalse(asking.shouldAsk(groupB, signature: "o|a", confirmed: true), "B stayed")
        asking.finished(groupA, signature: "o|a", ok: true)
        asking.clear()
        XCTAssertTrue(asking.shouldAsk(groupB, signature: "o|a", confirmed: true), "clear: every group is asked again")
    }

    // D-227 Decision 35: a late answer is ignored, and a change seen while asking is asked once more. Twins: RolesTest.kt.
    func testRoleAskingIgnoresALateAnswerAfterClear() {
        var asking = RoleAsking()
        XCTAssertTrue(asking.shouldAsk(groupA, signature: "o|a", confirmed: true))
        asking.clear()
        XCTAssertNil(asking.finished(groupA, signature: "o|a", ok: true), "nothing is in flight after clear")
        XCTAssertTrue(asking.shouldAsk(groupA, signature: "o|a", confirmed: true), "the late answer kept nothing")
    }

    func testRoleAskingIgnoresALateAnswerForAGroupThatLeft() {
        var asking = RoleAsking()
        XCTAssertTrue(asking.shouldAsk(groupA, signature: "o|a", confirmed: true))
        asking.keep(only: [groupB])
        XCTAssertNil(asking.finished(groupA, signature: "o|a", ok: true))
        XCTAssertTrue(asking.shouldAsk(groupA, signature: "o|a", confirmed: true), "A came back: asked fresh")
    }

    func testRoleAskingAsksOnceMoreForAChangeSeenWhileAsking() throws {
        var asking = RoleAsking()
        XCTAssertTrue(asking.shouldAsk(groupA, signature: "o|a", confirmed: true))
        XCTAssertFalse(asking.shouldAsk(groupA, signature: "o|", confirmed: true), "in flight: held as pending")
        XCTAssertFalse(asking.shouldAsk(groupA, signature: "o|b", confirmed: false), "an unconfirmed one is not held")
        let next = asking.finished(groupA, signature: "o|a", ok: true)
        XCTAssertEqual(next, "o|", "the change seen while asking")
        XCTAssertTrue(asking.shouldAsk(groupA, signature: try XCTUnwrap(next), confirmed: true), "asked again with it")
        XCTAssertNil(asking.finished(groupA, signature: "o|", ok: true), "once")
        XCTAssertFalse(asking.shouldAsk(groupA, signature: "o|", confirmed: true))
        // A failed answer hands the change back too.
        XCTAssertTrue(asking.shouldAsk(groupB, signature: "o|a", confirmed: true))
        XCTAssertFalse(asking.shouldAsk(groupB, signature: "o|c", confirmed: true))
        XCTAssertEqual(asking.finished(groupB, signature: "o|a", ok: false), "o|c")
    }

    func testRoleAskingDoesNotAskAgainForThePendingSignatureJustAnswered() {
        var asking = RoleAsking()
        XCTAssertTrue(asking.shouldAsk(groupA, signature: "o|a", confirmed: true))
        XCTAssertFalse(asking.shouldAsk(groupA, signature: "o|a", confirmed: true))
        XCTAssertNil(asking.finished(groupA, signature: "o|a", ok: true), "the same signature: nothing more to ask")
    }

    // D-227 (Design 8): an answer that does not place the caller is asked again. Twins: RolesTest.kt.
    func testRoleAskingAsksAgainWhenTheAnswerDoesNotPlaceYou() {
        let empty = RoleRules.Roles(owner: o, admins: [], places: RoleRules.Places(owner: [], admins: [], you: []))
        let placed = RoleRules.Roles(owner: o, admins: [], places: RoleRules.Places(owner: [b], admins: [], you: [b]))
        XCTAssertFalse(RoleAsking.ok(empty), "the group's first records are not up yet")
        XCTAssertTrue(RoleAsking.ok(placed))
        XCTAssertTrue(RoleAsking.ok(RoleRules.Roles(owner: o, admins: [])), "a server before D-227")
        XCTAssertFalse(RoleAsking.ok(nil), "offline or refused")
        var asking = RoleAsking()
        XCTAssertTrue(asking.shouldAsk(groupA, signature: "o|", confirmed: true))
        asking.finished(groupA, signature: "o|", ok: RoleAsking.ok(empty))
        XCTAssertTrue(asking.shouldAsk(groupA, signature: "o|", confirmed: true), "asked again on the next snapshot")
    }

    // D-227 (Design 8): after a push, the groups to ask again. Twins: RolesTest.kt.
    func testRolesAreAskedAgainAfterAPushOnlyWhereYouAreNotPlacedYet() {
        let g = (1...5).map { String(repeating: String($0), count: 32) }
        let cached: [String: RoleRules.Roles] = [
            g[0]: RoleRules.Roles(owner: o, admins: [], places: RoleRules.Places(owner: [b], admins: [], you: [b])),
            g[1]: RoleRules.Roles(owner: o, admins: [], places: RoleRules.Places(owner: [], admins: [], you: [])),
            g[2]: RoleRules.Roles(owner: o, admins: []),
        ]
        XCTAssertEqual(CloudRecords.rolesAfterPush(Set(g[0...3])) { cached[$0] }, [g[1], g[2], g[3]],
                       "placed: no; an empty you, no places, nothing cached: yes")
        XCTAssertEqual(CloudRecords.rolesAfterPush([]) { cached[$0] }, [], "nothing went up")
    }

    // D-227 (Design 5): the cloud's places, read strictly. Twins: RolesTest.kt.
    private let b = "b" + String(repeating: "0", count: 31), x = "e" + String(repeating: "0", count: 31)

    func testTheCloudsPlacesAreReadStrictly() throws {
        let places = RoleRules.Places(owner: [b], admins: [x], you: [b])
        XCTAssertEqual(GroupRoles.parse(["owner": o, "admins": [a], "places": ["owner": [b], "admins": [x], "you": [b]]]),
                       RoleRules.Roles(owner: o, admins: [a], places: places))
        XCTAssertEqual(GroupRoles.parse(["owner": o, "admins": [a]]), RoleRules.Roles(owner: o, admins: [a]), "an old server")
        XCTAssertEqual(GroupRoles.parse(["owner": o, "admins": [], "places": ["owner": [], "admins": [], "you": []]]),
                       RoleRules.Roles(owner: o, admins: [], places: RoleRules.Places(owner: [], admins: [], you: [])))
        XCTAssertNil(GroupRoles.parse(["owner": o, "admins": [], "places": ["owner": [b], "admins": [], "you": ["zz"]]]),
                     "a bad entry refuses the whole answer")
        XCTAssertNil(GroupRoles.parse(["owner": o, "admins": [], "places": ["owner": [b.uppercased()], "admins": [], "you": []]]))
        XCTAssertNil(GroupRoles.parse(["owner": o, "admins": [], "places": ["owner": [b], "admins": [7], "you": []]]))
        XCTAssertNil(GroupRoles.parse(["owner": o, "admins": [], "places": "b"]), "places that are not places")
        // A cache written before D-227 still reads, with no places; a new one keeps them.
        let old = try JSONDecoder().decode(RoleRules.Roles.self, from: Data(#"{"owner":"\#(o)","admins":["\#(a)"]}"#.utf8))
        XCTAssertEqual(old, RoleRules.Roles(owner: o, admins: [a]))
        let new = RoleRules.Roles(owner: o, admins: [a], places: places)
        XCTAssertEqual(try JSONDecoder().decode(RoleRules.Roles.self, from: try LedgerStore.kvEncoder().encode(new)), new)
    }

    /// A cache whose places cannot be read keeps its owner and admins, without places (Android's `GroupRoles.decode`).
    func testACachedListWithUnreadablePlacesKeepsItsOwnerAndAdmins() throws {
        for junk in [#""x""#, #"{"owner":[1],"admins":[],"you":[]}"#] {
            let text = #"{"owner":"\#(o)","admins":["\#(a)"],"places":"# + junk + "}"
            XCTAssertEqual(try JSONDecoder().decode(RoleRules.Roles.self, from: Data(text.utf8)),
                           RoleRules.Roles(owner: o, admins: [a]), text)
        }
        XCTAssertEqual(try JSONDecoder().decode(RoleRules.Roles.self, from: Data(#"{"admins":[]}"#.utf8)),
                       RoleRules.Roles(owner: nil, admins: []))
        // D-227 fix wave, M-g: a missing list reads as none, as Android's decode reads it (was: the places, or the whole
        // entry, dropped).
        XCTAssertEqual(try JSONDecoder().decode(RoleRules.Roles.self, from: Data(#"{"owner":"\#(o)"}"#.utf8)),
                       RoleRules.Roles(owner: o, admins: []))
        XCTAssertEqual(try JSONDecoder().decode(RoleRules.Roles.self,
                                                from: Data(#"{"owner":"\#(o)","admins":[],"places":{"owner":["\#(b)"]}}"#.utf8)),
                       RoleRules.Roles(owner: o, admins: [], places: RoleRules.Places(owner: [b], admins: [], you: [])))
        XCTAssertThrowsError(try JSONDecoder().decode(RoleRules.Roles.self, from: Data("not json".utf8)))
    }

    // D-227 (Design 5 and 6): roles and your own row by place. Twins: RolesTest.kt.

    /// The report's case: the group knows the creator as B, its account's participant id is A.
    func testTheCreatorIsTheOwnerByPlaceWhenTheirParticipantIdDiffers() {
        let dee = pid(30), deeYou = pid(31)
        let members = [MemberWire(id: b, name: "Cy"), MemberWire(id: dee, name: "Dee")]
        let claims = [dee: deeYou]
        let roles = RoleRules.Roles(owner: a, admins: [], places: RoleRules.Places(owner: [b], admins: [], you: [b]))
        let rows = PeopleRoles.rows(members: members, selfId: b, viewerYou: b, claims: claims, roles: roles,
                                    yourPlaces: roles.places?.you)
        XCTAssertEqual(rows.map(\.role), [.owner, .member])
        XCTAssertEqual(rows.map(\.place), [b, dee])
        XCTAssertEqual(PeopleRoles.viewerRole(rows), .owner)
        XCTAssertEqual(rows[0].offer.askSelf, false, "the owner does not ask to be removed")
        XCTAssertEqual(rows[1].offer, RoleRules.Offer(remove: true, ask: false, askSelf: false, makeAdmin: true,
                                                      removeAdmin: false, makeOwner: true), "the owner's offers")
        // The same answer without places (a server before D-227): the bug as it was.
        let legacy = PeopleRoles.rows(members: members, selfId: b, viewerYou: b, claims: claims,
                                      roles: RoleRules.Roles(owner: a, admins: []))
        XCTAssertEqual(legacy.map(\.role), [.member, .member])
    }

    /// The founder's case: the group was made under x, the phone's "you" is now y.
    func testYourOwnRowTakesThePlaceTheCloudSaysIsYours() {
        let y = pid(40), dee = pid(41)
        let members = [MemberWire(id: y, name: "Prithvi"), MemberWire(id: dee, name: "Dee")]
        let roles = RoleRules.Roles(owner: pid(42), admins: [], places: RoleRules.Places(owner: [x], admins: [], you: [x]))
        let rows = PeopleRoles.rows(members: members, selfId: y, viewerYou: y, claims: [dee: pid(43)], roles: roles,
                                    yourPlaces: [x], otherYouIds: [x])
        XCTAssertEqual(rows[0].role, .owner)
        XCTAssertEqual(rows[0].place, x, "every call about you names x")
        XCTAssertEqual(rows[0].member.id, y, "the row keeps the id the screens know it by")
        XCTAssertEqual(rows[1].offer.makeOwner, true)
        // Several places: the one the account's claim picks, if listed, else the first in order.
        let p1 = pid(44), p2 = pid(45)
        XCTAssertEqual(PeopleRoles.selfPlace(yourPlaces: [p1, p2], selfId: y, yous: [y], claims: [p2: y]), p2)
        XCTAssertEqual(PeopleRoles.selfPlace(yourPlaces: [p2, p1], selfId: y, yous: [y], claims: [:]), p1)
        XCTAssertEqual(PeopleRoles.selfPlace(yourPlaces: [], selfId: y, yous: [y], claims: [:]), y, "none listed: placeFor")
    }

    /// Not known from the cloud: `placeFor` with every id of the account, not only this phone's "you".
    func testYourOwnPlaceFallsBackToEveryIdOfTheAccount() {
        let y = pid(50), other = pid(51), place = pid(52), sam = pid(53)
        let members = [MemberWire(id: y, name: "Rahul"), MemberWire(id: sam, name: "Sam")]
        let claims = [place: other]
        let rows = PeopleRoles.rows(members: members, selfId: y, viewerYou: y, claims: claims,
                                    roles: RoleRules.Roles(owner: sam, admins: []), yourPlaces: nil, otherYouIds: [other])
        XCTAssertEqual(rows[0].place, place, "the place another phone of the account claimed")
        XCTAssertEqual(PeopleRoles.selfPlace(yourPlaces: nil, selfId: y, yous: [y, other], claims: claims), place)
        XCTAssertEqual(PeopleRoles.selfPlace(yourPlaces: nil, selfId: y, yous: [y], claims: claims), y)
    }

    /// Others' view of a joiner whose claim id is not its participant id.
    func testAJoinerIsAnAdminByPlace() {
        let creator = pid(60), joined = pid(61), claimYou = pid(62), participant = pid(63), never = pid(64)
        let members = [MemberWire(id: creator, name: "Cy"), MemberWire(id: joined, name: "Dee"), MemberWire(id: never, name: "Eve")]
        let claims = [joined: claimYou]
        let byPlace = RoleRules.Roles(owner: pid(65), admins: [participant],
                                      places: RoleRules.Places(owner: [creator], admins: [joined], you: [creator]))
        let rows = PeopleRoles.rows(members: members, selfId: creator, viewerYou: creator, claims: claims, roles: byPlace,
                                    yourPlaces: [creator])
        XCTAssertEqual(rows.map(\.role), [.owner, .admin, .unjoined])
        XCTAssertEqual(rows[1].offer.removeAdmin, true)
        // No places: the account rule, unchanged.
        let legacy = RoleRules.Roles(owner: pid(65), admins: [participant])
        XCTAssertEqual(PeopleRoles.rows(members: members, selfId: creator, viewerYou: creator, claims: claims, roles: legacy)
            .map(\.role), [.member, .member, .unjoined])
        // A row in a role list is joined, whatever its claim; the rule by place, alone.
        XCTAssertEqual(RoleRules.role(of: nil, place: joined, in: byPlace), .admin)
        XCTAssertEqual(RoleRules.role(of: nil, place: never, in: byPlace), .unjoined)
        XCTAssertEqual(RoleRules.role(of: claimYou, place: never, in: byPlace), .member)
        XCTAssertEqual(RoleRules.role(of: participant, place: never, in: legacy), .admin, "no places: by account")
    }

    /// An ask about your own row names no "you": the cloud knows the caller.
    func testAnAskAboutYourselfSendsNoYou() {
        let y = pid(70), sam = pid(71), samYou = pid(72)
        let rows = PeopleRoles.rows(members: [MemberWire(id: y, name: "Al"), MemberWire(id: sam, name: "Sam")], selfId: y,
                                    viewerYou: y, claims: [sam: samYou], roles: RoleRules.Roles(owner: pid(73), admins: []))
        XCTAssertEqual(rows.map(\.you), [y, samYou])
        XCTAssertEqual(rows.map(\.askYou), [nil, samYou])
    }

    // D-227 fix wave, I1: an owner the cloud cannot place yet (`places.owner` empty, its create not up) reads by the
    // account rule; the admins stay by place. Twins: RolesTest.kt.
    func testAnOwnerTheCloudCannotPlaceYetReadsByTheAccountRule() {
        let dee = pid(80), deeYou = pid(81)
        let members = [MemberWire(id: a, name: "Cy"), MemberWire(id: dee, name: "Dee")]
        let empty = RoleRules.Roles(owner: a, admins: [], places: RoleRules.Places(owner: [], admins: [], you: []))
        let rows = PeopleRoles.rows(members: members, selfId: a, viewerYou: a, claims: [dee: deeYou], roles: empty,
                                    yourPlaces: empty.places?.you)
        XCTAssertEqual(rows.map(\.role), [.owner, .member], "the self row is the owner")
        XCTAssertEqual(rows[0].offer.askSelf, false, "the owner does not ask to be removed")
        XCTAssertEqual(rows[1].offer, RoleRules.Offer(remove: true, ask: false, askSelf: false, makeAdmin: true,
                                                      removeAdmin: false, makeOwner: true), "the owner's offers")
        // An owner placed: the place decides, never the account (a row whose claim is A, at a place that is not B).
        let placed = RoleRules.Roles(owner: a, admins: [], places: RoleRules.Places(owner: [b], admins: [], you: [b]))
        XCTAssertEqual(RoleRules.role(of: a, place: dee, in: placed), .member, "by place, no fallback")
        // The admins stay by place while the owner falls back.
        let adminByAccount = RoleRules.Roles(owner: a, admins: [deeYou], places: RoleRules.Places(owner: [], admins: [], you: []))
        XCTAssertEqual(RoleRules.role(of: deeYou, place: dee, in: adminByAccount), .member, "admins by place only")
        // D-227 follow-up F (Android's I1 vectors): the fallback names only the legacy owner, and never a row with no account.
        XCTAssertEqual(RoleRules.role(of: deeYou, place: dee, in: empty), .member, "owner empty, not the legacy owner: a member")
        let noOwner = RoleRules.Roles(owner: nil, admins: [], places: RoleRules.Places(owner: [], admins: [], you: []))
        XCTAssertEqual(RoleRules.role(of: nil, place: dee, in: noOwner), .unjoined, "owner empty, no account: unjoined")
    }

    // D-227 fix wave, M-f: a row in a role list by place with no claim on this phone has no account for a role call to
    // name (the server takes the place itself only from its creator), so it offers no role action. Twins: RolesTest.kt.
    func testARoleRowWithNoClaimHereOffersNoRoleAction() {
        let cy = pid(90), dee = pid(91), eve = pid(92), eveYou = pid(93)
        let members = [MemberWire(id: cy, name: "Cy"), MemberWire(id: dee, name: "Dee"), MemberWire(id: eve, name: "Eve")]
        let roles = RoleRules.Roles(owner: pid(94), admins: [],
                                    places: RoleRules.Places(owner: [cy], admins: [dee, eve], you: [cy]))
        let rows = PeopleRoles.rows(members: members, selfId: cy, viewerYou: cy, claims: [eve: eveYou], roles: roles,
                                    yourPlaces: [cy])
        XCTAssertEqual(rows.map(\.role), [.owner, .admin, .admin])
        XCTAssertEqual(rows[1].offer, RoleRules.Offer(remove: true, ask: false, askSelf: false, makeAdmin: false,
                                                      removeAdmin: false, makeOwner: false), "no claim: removal only")
        XCTAssertEqual(rows[2].offer, RoleRules.Offer(remove: true, ask: false, askSelf: false, makeAdmin: false,
                                                      removeAdmin: true, makeOwner: true), "a claim: the owner's offers")
    }

    // D-227 fix wave, decision 38: a failed ask is asked again on a timer, through `shouldAsk`. Twins: RolesTest.kt.
    func testAFailedRoleAskIsRetriedAfter15Then60Then300Seconds() {
        XCTAssertEqual((0...3).map(RoleAsking.retryDelay), [15, 60, 300, nil], "by the timed retries already made")
        var asking = RoleAsking()
        XCTAssertTrue(asking.shouldAsk(groupA, signature: "o|", confirmed: true))
        asking.finished(groupA, signature: "o|", ok: false)
        var seconds: [TimeInterval] = []
        while let timer = asking.retryAfter(groupA, signature: "o|") {
            seconds.append(timer.seconds)
            XCTAssertTrue(asking.due(groupA, token: timer.token), "the timer is the group's own")
            XCTAssertTrue(asking.shouldAsk(groupA, signature: "o|", confirmed: true), "it asks through shouldAsk")
            asking.finished(groupA, signature: "o|", ok: false)
        }
        XCTAssertEqual(seconds, [15, 60, 300], "three retries per signature")
        XCTAssertTrue(asking.shouldAsk(groupA, signature: "o|", confirmed: true), "then the next snapshot asks")
        asking.finished(groupA, signature: "o|", ok: false)
        XCTAssertNil(asking.retryAfter(groupA, signature: "o|"), "and sets no timer")
        // A new signature has its own three.
        XCTAssertTrue(asking.shouldAsk(groupA, signature: "o|a", confirmed: true))
        asking.finished(groupA, signature: "o|a", ok: false)
        XCTAssertEqual(asking.retryAfter(groupA, signature: "o|a")?.seconds, 15)
    }

    func testARoleRetryIsCancelledByClearBySuccessByALaterTimerAndWhenTheGroupLeaves() throws {
        var asking = RoleAsking()
        XCTAssertTrue(asking.shouldAsk(groupA, signature: "o|", confirmed: true))
        asking.finished(groupA, signature: "o|", ok: false)
        let first = try XCTUnwrap(asking.retryAfter(groupA, signature: "o|"))
        asking.clear()
        XCTAssertFalse(asking.due(groupA, token: first.token), "clear cancels it")
        XCTAssertTrue(asking.shouldAsk(groupA, signature: "o|", confirmed: true))
        asking.finished(groupA, signature: "o|", ok: false)
        let second = try XCTUnwrap(asking.retryAfter(groupA, signature: "o|"))
        XCTAssertEqual(second.seconds, 15, "clear starts the count again")
        XCTAssertTrue(asking.shouldAsk(groupA, signature: "o|", confirmed: true), "a snapshot asks meanwhile")
        asking.finished(groupA, signature: "o|", ok: true)
        XCTAssertFalse(asking.due(groupA, token: second.token), "a successful ask cancels it")
        XCTAssertEqual(asking.retryAfter(groupA, signature: "o|")?.seconds, 15, "and its count")
        // A later timer replaces an earlier one; a timer firing while an ask is in flight never asks twice at once.
        XCTAssertTrue(asking.shouldAsk(groupB, signature: "o|", confirmed: true))
        asking.finished(groupB, signature: "o|", ok: false)
        let third = try XCTUnwrap(asking.retryAfter(groupB, signature: "o|"))
        XCTAssertTrue(asking.shouldAsk(groupB, signature: "o|", confirmed: true), "a snapshot asks")
        XCTAssertTrue(asking.due(groupB, token: third.token))
        XCTAssertFalse(asking.shouldAsk(groupB, signature: "o|", confirmed: true), "in flight: not twice at once")
        XCTAssertNil(asking.finished(groupB, signature: "o|", ok: false))
        let fourth = try XCTUnwrap(asking.retryAfter(groupB, signature: "o|"))
        XCTAssertEqual(fourth.seconds, 60)
        XCTAssertFalse(asking.due(groupB, token: third.token), "a later timer replaces it")
        XCTAssertTrue(asking.due(groupB, token: fourth.token))
        asking.keep(only: [groupA])
        XCTAssertFalse(asking.due(groupB, token: fourth.token), "the group left")
    }

    // D-227 follow-up A (decision 35(a)): only a failed answer that counted (the group was in flight for that
    // signature) arms a timer; a late one, after `clear()` or after the group left, arms nothing. Twins: RolesTest.kt.
    func testOnlyAFailedAnswerThatCountedArmsARetry() throws {
        var asking = RoleAsking()
        XCTAssertTrue(asking.shouldAsk(groupA, signature: "o|", confirmed: true))
        asking.clear()
        XCTAssertNil(asking.finished(groupA, signature: "o|", ok: false))
        XCTAssertNil(asking.failed(groupA, signature: "o|"), "failed answer after clear: no timer")
        XCTAssertTrue(asking.shouldAsk(groupA, signature: "o|", confirmed: true))
        asking.keep(only: [groupB])
        XCTAssertNil(asking.finished(groupA, signature: "o|", ok: false))
        XCTAssertNil(asking.failed(groupA, signature: "o|"), "failed answer after keep(only:) dropped the group: no timer")
        XCTAssertTrue(asking.shouldAsk(groupA, signature: "o|", confirmed: true))
        XCTAssertNil(asking.finished(groupA, signature: "o|", ok: false))
        let timer = try XCTUnwrap(asking.failed(groupA, signature: "o|"), "failed answer while in flight: a timer")
        XCTAssertEqual(timer.seconds, 15)
        XCTAssertTrue(asking.due(groupA, token: timer.token))
        XCTAssertNil(asking.failed(groupA, signature: "o|"), "one answer arms once")
        // An answer that hands back a newer signature asks that at once and arms nothing.
        XCTAssertTrue(asking.shouldAsk(groupB, signature: "o|", confirmed: true))
        XCTAssertFalse(asking.shouldAsk(groupB, signature: "o|b", confirmed: true))
        XCTAssertEqual(asking.finished(groupB, signature: "o|", ok: false), "o|b")
        XCTAssertNil(asking.failed(groupB, signature: "o|"))
        // A successful answer arms nothing either.
        XCTAssertTrue(asking.shouldAsk(groupB, signature: "o|b", confirmed: true))
        asking.finished(groupB, signature: "o|b", ok: true)
        XCTAssertNil(asking.failed(groupB, signature: "o|b"))
        // An old signature's answer while a newer one is in flight (after clear) counts for nothing.
        var old = RoleAsking()
        XCTAssertTrue(old.shouldAsk(groupA, signature: "o|a", confirmed: true))
        old.clear()
        XCTAssertTrue(old.shouldAsk(groupA, signature: "o|b", confirmed: true))
        XCTAssertNil(old.finished(groupA, signature: "o|a", ok: false))
        XCTAssertNil(old.failed(groupA, signature: "o|a"), "not the signature in flight: no timer")
        XCTAssertFalse(old.shouldAsk(groupA, signature: "o|b", confirmed: true), "o|b is still in flight")
        XCTAssertNil(old.finished(groupA, signature: "o|b", ok: false))
        XCTAssertEqual(old.failed(groupA, signature: "o|b")?.seconds, 15)
    }

    // D-227 follow-up B (re-review M-1): a timer for an older signature does not ask while a newer one is current
    // (`due` also checks the signature). Twins: RolesTest.kt.
    func testARetryForAnOlderSignatureIsNotDueOnceANewerOneIsCurrent() throws {
        var asking = RoleAsking()
        XCTAssertTrue(asking.shouldAsk(groupA, signature: "o|", confirmed: true))
        asking.finished(groupA, signature: "o|", ok: false)
        let old = try XCTUnwrap(asking.failed(groupA, signature: "o|"))
        XCTAssertTrue(asking.shouldAsk(groupA, signature: "o|", confirmed: true), "the same signature again")
        XCTAssertTrue(asking.due(groupA, token: old.token), "the timer's own signature is current: due")
        asking.finished(groupA, signature: "o|", ok: false)
        let again = try XCTUnwrap(asking.failed(groupA, signature: "o|"))
        XCTAssertTrue(asking.shouldAsk(groupA, signature: "o|a", confirmed: true), "a newer signature is asked")
        XCTAssertFalse(asking.due(groupA, token: again.token), "the old signature's timer is not due")
        // Seen while in flight, it is current too.
        var h = RoleAsking()
        XCTAssertTrue(h.shouldAsk(groupA, signature: "o|a", confirmed: true))
        h.finished(groupA, signature: "o|a", ok: false)
        let t0 = try XCTUnwrap(h.failed(groupA, signature: "o|a"))
        XCTAssertTrue(h.shouldAsk(groupA, signature: "o|a", confirmed: true))
        XCTAssertFalse(h.shouldAsk(groupA, signature: "o|b", confirmed: true), "in flight: kept for later")
        XCTAssertFalse(h.due(groupA, token: t0.token))
        // An unconfirmed snapshot says nothing of what is current.
        var b = RoleAsking()
        XCTAssertTrue(b.shouldAsk(groupB, signature: "o|", confirmed: true))
        b.finished(groupB, signature: "o|", ok: false)
        let t = try XCTUnwrap(b.failed(groupB, signature: "o|"))
        XCTAssertFalse(b.shouldAsk(groupB, signature: "o|z", confirmed: false))
        XCTAssertTrue(b.due(groupB, token: t.token), "pending writes: still due")
    }

    // D-227 fix wave, F4: a role change the cloud confirmed shows at once, by place, so the open sheet reads it.
    func testAConfirmedRoleChangeShowsAtOnceByPlace() {
        let cy = pid(100), dee = pid(101), eve = pid(102)
        let roles = RoleRules.Roles(owner: pid(103), admins: [], places: RoleRules.Places(owner: [cy], admins: [eve], you: [cy]))
        XCTAssertEqual(RoleRules.confirmed(roles, place: dee, change: .admin(on: true)).places,
                       RoleRules.Places(owner: [cy], admins: [dee, eve], you: [cy]))
        let twice = RoleRules.confirmed(RoleRules.confirmed(roles, place: dee, change: .admin(on: true)), place: dee,
                                        change: .admin(on: true))
        XCTAssertEqual(twice.places?.admins, [dee, eve], "once")
        XCTAssertEqual(RoleRules.confirmed(roles, place: eve, change: .admin(on: false)).places,
                       RoleRules.Places(owner: [cy], admins: [], you: [cy]))
        XCTAssertEqual(RoleRules.confirmed(roles, place: eve, change: .owner).places,
                       RoleRules.Places(owner: [eve], admins: [cy], you: [cy]), "the old owner becomes an admin")
        let legacy = RoleRules.Roles(owner: o, admins: [a])
        XCTAssertEqual(RoleRules.confirmed(legacy, place: dee, change: .admin(on: true)), legacy, "no places: the refresh says")
        let rows = PeopleRoles.rows(
            members: [MemberWire(id: cy, name: "Cy"), MemberWire(id: dee, name: "Dee")], selfId: cy, viewerYou: cy,
            claims: [dee: pid(104)], roles: RoleRules.confirmed(roles, place: dee, change: .admin(on: true)), yourPlaces: [cy])
        XCTAssertEqual(rows[1].role, .admin)
        XCTAssertEqual(rows[1].offer.removeAdmin, true)
        XCTAssertEqual(rows[1].offer.makeAdmin, false)
    }

    /// The open person sheet reads its row from the screen's rows (live), and both role calls apply the confirmed change.
    func testTheOpenPersonSheetReadsItsRowLive() throws {
        let picker = try s2Source("GroupPicker.swift")
        let sheet = try s2Slice(picker, from: ".sheet(item: $personSheet) { row in", to: "viewerName:")
        XCTAssertTrue(sheet.contains("row: liveRow(row)"), sheet)
        XCTAssertTrue(picker.contains("rows.first { $0.id == row.id } ?? row"))
        let roles = try s2Source("Roles.swift")
        XCTAssertEqual(s2Count(roles, "try keep(groupID, RoleRules.confirmed("), 2, "setAdmin and makeOwner")
    }

    /// D-227 follow-up D (look F2's twin, re-look R1): a group that reaches the cloud while its People page is open turns
    /// the page into the cloud's layout at once: the push says the screens changed as soon as the group is up, whether
    /// or not a share or a record follows. Twin: RolesTest.kt `a_group_that_reaches_the_cloud_reads_its_screens_again`.
    func testAGroupThatReachesTheCloudReadsItsScreensAgain() throws {
        let sync = try s2Source("CloudSync.swift")
        let push = try s2Slice(sync, from: "static func pushAll(", to: "enum GroupState")
        let gained = try XCTUnwrap(push.range(of: "let gainedGroups = groupsUp.subtracting(upBefore)"))
        let bump = try XCTUnwrap(push.range(of: "if !gainedGroups.isEmpty { SyncStatus.shared.changed() }"),
                                 "the push bumps the revision once a group is up")
        XCTAssertTrue(bump.lowerBound > gained.upperBound)
        let records = try XCTUnwrap(push.range(of: "for chunk in stride(from: 0, to: pending.count, by: 100)"))
        XCTAssertTrue(bump.upperBound < records.lowerBound, "before its records go up")
        let people = try s2Slice(try s2Source("GroupPicker.swift"), from: "struct PeopleScreen", to: "private func load() {")
        XCTAssertTrue(people.contains(".task(id: SyncStatus.shared.revision) { load() }"),
                      "People reads the group again on every revision")
    }

    @MainActor
    func testForgettingAGroupForgetsItsRolesInMemory() throws {
        let store = try tempStore()
        let g = String(repeating: "c", count: 32), other = String(repeating: "f", count: 32)
        GroupRoles.shared.setForTest(g, RoleRules.Roles(owner: o, admins: [a]))
        GroupRoles.shared.setForTest(other, RoleRules.Roles(owner: o, admins: []))
        defer { GroupRoles.shared.setForTest(other, nil) }
        try store.forgetGroup(g)
        XCTAssertNil(GroupRoles.shared.roles[g])
        XCTAssertNotNil(GroupRoles.shared.roles[other])
    }

    func testAMoveToNobodyHasNoDanglingTo() {
        XCTAssertEqual(RolesCopy.moved(from: "Sam", amount: "₹5.00", to: []), "Sam's ₹5.00 moved")
    }

    func testTheCloudsRolesAreReadStrictlyAndFailuresHaveWords() {
        let o = String(repeating: "1", count: 32), a = String(repeating: "2", count: 32)
        XCTAssertEqual(GroupRoles.parse(["owner": o, "admins": [a]]), RoleRules.Roles(owner: o, admins: [a]))
        XCTAssertEqual(GroupRoles.parse(["owner": NSNull(), "admins": []]), RoleRules.Roles(owner: nil, admins: []))
        XCTAssertNil(GroupRoles.parse(["owner": "x", "admins": []]))
        XCTAssertNil(GroupRoles.parse(["owner": o, "admins": ["zz"]]))
        XCTAssertEqual(RolesCopy.failure(CloudFunctions.Failure.refused("PERMISSION_DENIED")), "Only the owner or an admin can do that.")
        XCTAssertEqual(RolesCopy.failure(CloudFunctions.Failure.refused("FAILED_PRECONDITION")), "That changed on another phone. Try again.")
        XCTAssertEqual(RolesCopy.failure(CloudFunctions.Failure.refused("NOT_FOUND")), "This group no longer exists.")
        XCTAssertEqual(RolesCopy.failure(CloudFunctions.Failure.offline), InviteCopy.offline)
    }

    // MARK: - Task 6: the removal's pure parts

    private func pid(_ n: Int) -> String { String(repeating: "0", count: 31) + String(n, radix: 16) }

    private func line(_ code: String, net: Int64, _ entries: [ShareOut]) -> BalanceLineOut {
        BalanceLineOut(currencyCode: code, viewerNetMinor: net, entries: entries, transfers: [])
    }

    func testThePlanIsTheMembersNonzeroNetPerCurrency() {
        let sam = pid(4)
        let lines = [
            line("INR", net: -100_000, [ShareOut(id: pid(1), minorUnits: -70_000), ShareOut(id: pid(2), minorUnits: -30_000)]),
            line("USD", net: 500, [ShareOut(id: pid(3), minorUnits: 500)]),
            line("EUR", net: 0, []),
        ]
        XCTAssertEqual(Removal.plan(lines: lines, member: sam), ["INR": -100_000, "USD": 500])
        XCTAssertEqual(Removal.plan(lines: [line("EUR", net: 0, [])], member: sam), [:], "settled: nothing to decide")
    }

    func testSettleFirstRecordsEveryPairFromTheMembersSide() {
        let sam = pid(4), g = String(repeating: "c", count: 32)
        let lines = [
            line("INR", net: -100_000, [ShareOut(id: pid(1), minorUnits: -70_000), ShareOut(id: pid(2), minorUnits: -30_000)]),
            line("USD", net: 500, [ShareOut(id: pid(3), minorUnits: 500)]),
        ]
        XCTAssertEqual(Removal.settleFirst(lines: lines, member: sam, group: g), [
            .init(from: sam, to: pid(1), amountMinor: 70_000, currencyCode: "INR", group: g),
            .init(from: sam, to: pid(2), amountMinor: 30_000, currencyCode: "INR", group: g),
            .init(from: pid(3), to: sam, amountMinor: 500, currencyCode: "USD", group: g),
        ], "Sam pays what Sam owes; Asha pays what Sam is owed")
        XCTAssertEqual(Removal.settleFirst(lines: [line("INR", net: 0, [])], member: sam, group: g), [])
    }

    func testThePlanAndTheSettlementsClearARealBalance() throws {
        let store = try tempStore()
        let me = try store.setYourName("Prithvi")
        let group = try store.createGroup(name: "Goa", settlementCurrency: "INR", people: ["Sam", "Rahul"])
        let sam = group.members.first { $0.name == "Sam" }!.id, rahul = group.members.first { $0.name == "Rahul" }!.id
        // Prithvi paid 1,000, split three ways: Sam and Rahul each owe 333.33 or 333.34 to Prithvi.
        let shares = try ffiRedistributeEqual(balanceMinor: 100_000, members: [me.id, sam, rahul])
        _ = try store.appendExpense(LedgerStore.ExpenseDTO(
            payer: me.id, paidMinor: 100_000, currencyCode: "INR", group: group.group.id, conversion: nil,
            shares: shares.map { .init(id: $0.id, amountMinor: $0.amountMinor) }))
        func lines() throws -> [BalanceLineOut] {
            try ffiBalanceLine(expenses: try store.loadExpenses().map(\.asInput),
                               settlements: try store.loadSettlements().map(\.asInput),
                               redistributions: try store.loadRedistributions(), removed: [], viewer: sam, group: group.group.id)
        }
        let before = Removal.plan(lines: try lines(), member: sam)
        XCTAssertEqual(before.keys.sorted(), ["INR"])
        XCTAssertLessThan(before["INR"]!, 0, "Sam owes")
        for s in Removal.settleFirst(lines: try lines(), member: sam, group: group.group.id) { _ = try store.appendSettlement(s) }
        XCTAssertEqual(Removal.plan(lines: try lines(), member: sam), [:], "the payments leave Sam at zero")
    }

    func testRemovalRecordsAreOneRedistributionPerCurrencyThenTheRemoval() throws {
        let store = try tempStore()
        let g = String(repeating: "c", count: 32), sam = String(repeating: "d", count: 32), a = String(repeating: "a", count: 32)
        let moved = RedistributionInput(group: g, from: sam, currencyCode: "INR", shares: [IdAmount(id: a, amountMinor: -500)])
        let records = try Removal.records(store: store, group: g, member: sam, redistributions: [moved], placing: .none)
        XCTAssertEqual(records.map(\.recordType), [6, 3])
        let device = try LedgerStore.Sync.deviceId(store)
        XCTAssertTrue(records.allSatisfy { $0.groupId == g && $0.hlcDeviceId == device && $0.authorDeviceId == device && $0.flags == 0 })
        XCTAssertEqual(records[0].nonceSession, Data([0x52, 0x45, 0x44, 0x01]), "RED\u{1}")
        XCTAssertEqual(try ffiDecodeRedistribution(blob: records[0].blob), moved)
        guard case .removeMember(let gid, let mid) = try ffiDecodeGroupOp(blob: records[1].blob) else { return XCTFail("not a removal") }
        XCTAssertEqual([gid, mid], [g, sam])
        XCTAssertTrue((records[0].hlcMillis, records[0].hlcCounter) < (records[1].hlcMillis, records[1].hlcCounter),
                      "each stamp is later than the one before")
        // No balance to move: only the removal.
        XCTAssertEqual(try Removal.records(store: store, group: g, member: sam, redistributions: [], placing: .none).map(\.recordType), [3])
    }

    func testThePayloadIsTheCloudContractsShape() throws {
        let g = String(repeating: "c", count: 32), sam = String(repeating: "d", count: 32), dev = String(repeating: "b", count: 32)
        let op = RecordWire(version: 1, recordType: 3, groupId: g, epoch: 0, hlcMillis: 1_800_000_000_123, hlcCounter: 7,
                            hlcDeviceId: dev, authorDeviceId: dev, nonceSession: Data([0x47, 0x52, 0x50, 0x01]), flags: 0,
                            blob: try ffiEncodeGroupOp(op: .removeMember(groupId: g, memberId: sam)))
        let json = Removal.payload([op])
        XCTAssertEqual(json.count, 1)
        let r = json[0]
        XCTAssertEqual(r["id"] as? String, "1800000000123-7-\(dev)")
        XCTAssertEqual(r["type"] as? String, "3")
        XCTAssertEqual(r["deviceId"] as? String, dev)
        let hlc = try XCTUnwrap(r["hlc"] as? [String: Any])
        XCTAssertEqual(hlc["millis"] as? Int64, 1_800_000_000_123)
        XCTAssertEqual(hlc["counter"] as? Int64, 7)
        XCTAssertEqual(hlc["device"] as? String, dev)
        let payload = try XCTUnwrap(r["payload"] as? [String: Any])
        XCTAssertEqual(payload["version"] as? Int64, 1)
        XCTAssertEqual(payload["epoch"] as? Int64, 0)
        XCTAssertEqual(payload["flags"] as? Int64, 0)
        XCTAssertEqual(payload["nonce"] as? String, Data([0x47, 0x52, 0x50, 0x01]).base64EncodedString())
        let blob = try XCTUnwrap(Data(base64Encoded: try XCTUnwrap(payload["blob"] as? String)))
        let decoded = try JSONSerialization.jsonObject(with: blob) as? [String: Any]
        XCTAssertEqual(decoded?["op"] as? String, "removeMember")
        XCTAssertEqual(decoded?["group"] as? String, g)
        XCTAssertEqual(decoded?["member"] as? String, sam)
        // And it travels as JSON.
        XCTAssertNoThrow(try JSONSerialization.data(withJSONObject: ["records": json]))
    }

    func testRedistributingEquallyIsCoresSplitWithTheBalancesSign() {
        let sam = pid(4)
        var m = RedistributeModel(from: sam, currency: "INR", balance: -100_000, candidates: [pid(3), pid(1), pid(2)])
        XCTAssertEqual(m.ticked, [pid(1), pid(2), pid(3)], "everyone is ticked to start")
        XCTAssertEqual(m.shares(), [IdAmount(id: pid(1), amountMinor: -33_334), IdAmount(id: pid(2), amountMinor: -33_333),
                                    IdAmount(id: pid(3), amountMinor: -33_333)], "leftover to the lowest id, owed to nobody")
        XCTAssertEqual(m.status, .splitEqually)
        XCTAssertTrue(m.ok)
        m.toggle(pid(3))
        XCTAssertEqual(m.shares(), [IdAmount(id: pid(1), amountMinor: -50_000), IdAmount(id: pid(2), amountMinor: -50_000)])
        m.toggle(pid(1)); m.toggle(pid(2))
        XCTAssertEqual(m.status, .tickOne)
        XCTAssertFalse(m.ok)
        XCTAssertEqual(m.shares(), [])
        // What is owed to the person is shared out with a plus sign.
        let owed = RedistributeModel(from: sam, currency: "INR", balance: 1_000, candidates: [pid(1), pid(2), pid(3)])
        XCTAssertEqual(owed.shares().map(\.amountMinor), [334, 333, 333])
    }

    func testTypedAmountsMustAddUpExactly() {
        let sam = pid(4)
        var m = RedistributeModel(from: sam, currency: "INR", balance: -100_000, candidates: [pid(1), pid(2), pid(3)])
        m.startEditing()
        XCTAssertTrue(m.custom)
        XCTAssertEqual(m.typed, [pid(1): "333.34", pid(2): "333.33", pid(3): "333.33"], "the equal split, to edit")
        XCTAssertEqual(m.status, .addsUp)
        m.typed[pid(2)] = ""
        XCTAssertEqual(m.status, .typeAmounts, "nothing typed for a ticked person")
        XCTAssertFalse(m.ok)
        m.typed = [pid(1): "500.00", pid(2): "300.00", pid(3): "150.00"]
        XCTAssertEqual(m.status, .left(5_000))
        XCTAssertFalse(m.ok, "50.00 still to assign")
        m.typed[pid(3)] = "200.00"
        XCTAssertEqual(m.status, .addsUp)
        XCTAssertTrue(m.ok)
        XCTAssertEqual(m.shares(), [IdAmount(id: pid(1), amountMinor: -50_000), IdAmount(id: pid(2), amountMinor: -30_000),
                                    IdAmount(id: pid(3), amountMinor: -20_000)], "the balance's sign, applied to what was typed")
        m.typed[pid(3)] = "250.00"
        XCTAssertEqual(m.status, .tooMuch(5_000))
        XCTAssertFalse(m.ok)
        m.typed[pid(3)] = "abc"
        XCTAssertEqual(m.status, .typeAmounts, "an amount that is not one")
        m.typed[pid(3)] = "0"
        XCTAssertEqual(m.status, .typeAmounts, "a ticked person takes something")
        m.typed[pid(3)] = "200.00"
        m.toggle(pid(3))
        XCTAssertEqual(m.status, .left(20_000), "an unticked person's typed amount does not count")
        // Back to equal: typed text is dropped.
        m.stopEditing()
        XCTAssertFalse(m.custom)
        XCTAssertEqual(m.status, .splitEqually)
    }

    func testThePeopleSheetFollowsTheRolesOfEachRow() {
        let roles = RoleRules.Roles(owner: o, admins: [a])
        let members = [
            MemberWire(id: pid(1), name: "Prithvi"), MemberWire(id: pid(2), name: "Rahul"),
            MemberWire(id: pid(3), name: "Asha"), MemberWire(id: pid(5), name: "Nina"),
        ]
        let claims = [pid(1): o, pid(2): a, pid(3): m]
        let rows = PeopleRoles.rows(members: members, selfId: pid(1), viewerYou: o, claims: claims, roles: roles)
        XCTAssertEqual(rows.map(\.role), [.owner, .admin, .member, .unjoined])
        XCTAssertEqual(rows.map(\.isSelf), [true, false, false, false])
        XCTAssertEqual(rows.map(\.you), [o, a, m, nil])
        XCTAssertEqual(rows[1].offer.removeAdmin, true, "the owner may take admin away")
        XCTAssertEqual(rows[2].offer.makeAdmin, true)
        XCTAssertEqual(rows[3].offer.makeAdmin, false, "someone who has not joined cannot be an admin")
        XCTAssertEqual(rows[3].offer.remove, true)
        XCTAssertEqual(PeopleRoles.viewerRole(rows), .owner)
        // The same group, from Asha's phone: a member asks, never removes.
        let asha = PeopleRoles.rows(members: members, selfId: pid(3), viewerYou: m, claims: claims, roles: roles)
        XCTAssertEqual(PeopleRoles.viewerRole(asha), .member)
        XCTAssertEqual(asha[0].offer, RoleRules.Offer(remove: false, ask: false, askSelf: false, makeAdmin: false, removeAdmin: false, makeOwner: false), "the owner cannot be asked about")
        XCTAssertEqual(asha[2].offer.askSelf, true)
        XCTAssertEqual(asha[3].offer.ask, true)
        // Roles not known yet (offline, first launch): everyone is a plain member.
        let unknown = PeopleRoles.rows(members: members, selfId: pid(1), viewerYou: o, claims: claims, roles: nil)
        XCTAssertEqual(unknown.map(\.role), [.member, .member, .member, .unjoined])
    }

    /// The bug found on devices: a member who joined through an invite sees
    /// their own row under their account's "you" id (the projection aliases
    /// the place they claimed to it), but the cloud knows the person by the
    /// place. Every role or removal call about the viewer names the place.
    func testASelfRowIsCalledByThePlaceTheAccountClaimed() {
        let you = pid(13), place = pid(12), sam = pid(4), samYou = pid(10), samPlace = pid(11)
        let claims = [place: you, samPlace: samYou]
        // The joiner: members hold the you id for themself, the places for the rest.
        XCTAssertEqual(PeopleRoles.placeFor(rowId: you, selfId: you, yous: [you], claims: claims), place)
        // Another device of the same account wrote another "you" id: still the claimed place.
        XCTAssertEqual(PeopleRoles.placeFor(rowId: pid(14), selfId: pid(14), yous: [you, pid(14)], claims: claims), place)
        // The creator has no claim: the place IS the you id.
        XCTAssertEqual(PeopleRoles.placeFor(rowId: you, selfId: you, yous: [you], claims: [:]), you)
        XCTAssertEqual(PeopleRoles.placeFor(rowId: you, selfId: you, yous: [you], claims: [samPlace: samYou]), you,
                       "someone else's claim is not mine")
        // A row that is already the claimed place stays.
        XCTAssertEqual(PeopleRoles.placeFor(rowId: place, selfId: place, yous: [you], claims: claims), place)
        // Everyone else is unchanged, claimed or not.
        XCTAssertEqual(PeopleRoles.placeFor(rowId: samPlace, selfId: you, yous: [you], claims: claims), samPlace)
        XCTAssertEqual(PeopleRoles.placeFor(rowId: sam, selfId: you, yous: [you], claims: claims), sam)

        let members = [MemberWire(id: you, name: "Rahul"), MemberWire(id: samPlace, name: "Sam"), MemberWire(id: sam, name: "Nina")]
        let rows = PeopleRoles.rows(members: members, selfId: you, viewerYou: you, claims: claims,
                                    roles: RoleRules.Roles(owner: samYou, admins: []))
        XCTAssertEqual(rows.map(\.place), [place, samPlace, sam])
        XCTAssertEqual(rows.map(\.member.id), [you, samPlace, sam], "the row keeps the id the screens know it by")
        // A request about the joiner names the place: the joiner's card and "Request sent" find the row.
        let aboutMe = [request("r", asker: "Sam", member: place, name: "Rahul")]
        XCTAssertEqual(RequestCards.cards(items: aboutMe, rows: rows).map(\.kind), [.person])
        XCTAssertTrue(RequestCards.asked(items: aboutMe, member: rows[0].place))
    }

    func testAFailureAboutAPersonDoesNotSayTheGroupIsGone() {
        let gone = CloudFunctions.Failure.refused("NOT_FOUND")
        XCTAssertEqual(RolesCopy.personGone, "That person is not in this group any more.")
        XCTAssertEqual(RolesCopy.failure(gone, person: true), "That person is not in this group any more.")
        XCTAssertEqual(RolesCopy.failure(gone), "This group no longer exists.", "a group-level call keeps its words")
        XCTAssertEqual(RolesCopy.failure(CloudFunctions.Failure.refused("PERMISSION_DENIED"), person: true), RolesCopy.notAllowed)
        XCTAssertEqual(RolesCopy.failure(CloudFunctions.Failure.offline, person: true), InviteCopy.offline)
    }

    /// The bug found on devices: a group page's Recent rows named a person
    /// removed from the group "Unknown member", because the named group drops
    /// removed people. The history names (`historyNames`) keep them.
    func testARemovedPayerIsNamedFromTheHistory() {
        let a = pid(1), sam = pid(4), gone = pid(7)
        let named = NamedGroupWire(
            group: GroupWire(id: String(repeating: "c", count: 32), name: "Goa", settlementCurrency: "INR", members: [a, sam]),
            selfId: a, members: [MemberWire(id: a, name: "Rahul"), MemberWire(id: sam, name: "Sam")])
        let history = [a: "Rahul", sam: "Sam", gone: "Nina"]
        XCTAssertEqual(LedgerStore.label(for: gone, in: named, history: history), "Nina")
        XCTAssertEqual(LedgerStore.label(for: gone, in: named), "Unknown member", "the group alone cannot name them")
        XCTAssertEqual(LedgerStore.label(for: gone, in: named, history: [:]), "Unknown member")
        // Who is still here reads as before: "(You)" for the viewer.
        XCTAssertEqual(LedgerStore.label(for: a, in: named, history: history), "Rahul (You)")
        XCTAssertEqual(LedgerStore.label(for: sam, in: named, history: history), "Sam")
    }

    /// D-210 last wave: the names a group page's Who owes whom card reads.
    func testTheBalanceLineNamesARemovedPersonFromTheHistory() {
        let a = pid(1), sam = pid(4), gone = pid(7)
        let named = NamedGroupWire(
            group: GroupWire(id: String(repeating: "c", count: 32), name: "Goa", settlementCurrency: "INR", members: [a, sam]),
            selfId: a, members: [MemberWire(id: a, name: "Rahul"), MemberWire(id: sam, name: "Sam")])
        let names = LedgerStore.balanceNames(named, history: [a: "Rahul", sam: "Sam", gone: "Nina"])
        XCTAssertEqual(names[gone], "Nina", "a removed person who still owes is named, not Unknown member")
        XCTAssertEqual(names[a], "Rahul (You)")
        XCTAssertEqual(names[sam], "Sam")
        XCTAssertEqual(LedgerStore.balanceNames(named, history: [:])[gone], nil, "no name anywhere: the card says Unknown member")
    }

    func testASelfRequestIsWordedInTheFirstPerson() {
        let sam = pid(4)
        let own = request("a", asker: "Sam", member: sam)
        let other = request("b", asker: "Asha", member: sam)
        XCTAssertEqual(RequestCards.title(RequestCardItem(item: own, kind: .person)), "You asked to be removed")
        XCTAssertEqual(RolesCopy.cardWait, "The owner or an admin will decide.", "the body under it")
        XCTAssertEqual(RequestCards.title(RequestCardItem(item: own, kind: .decide)), "Sam asked to be removed")
        XCTAssertEqual(RequestCards.title(RequestCardItem(item: own, kind: .ownerDecides)), "Sam asked to be removed")
        // Someone else asking keeps the old words.
        XCTAssertEqual(RequestCards.title(RequestCardItem(item: other, kind: .person)), "Asha asked to remove you")
        XCTAssertEqual(RequestCards.title(RequestCardItem(item: other, kind: .decide)), "Asha asked to remove Sam")
        XCTAssertEqual(RequestCards.title(RequestCardItem(item: other, kind: .asker)), "You asked to remove Sam")
    }

    private func request(_ id: String, asker: String, member: String?, name: String? = "Sam") -> Notices.Item {
        Notices.Item(id: id, kind: .request, groupId: String(repeating: "c", count: 32), groupName: "Goa",
                     byName: asker, memberId: member, memberName: name)
    }

    func testRequestCardsAreShapedForTheViewerAndSkipTheGone() {
        let g = String(repeating: "c", count: 32)
        let members = [MemberWire(id: pid(1), name: "Prithvi"), MemberWire(id: pid(3), name: "Asha"), MemberWire(id: pid(4), name: "Sam")]
        let open = [request("a", asker: "Asha", member: pid(4)), request("b", asker: "Asha", member: pid(9)),
                    request("c", asker: "Asha", member: nil), request("d", asker: "Asha", member: pid(4), name: nil),
                    Notices.Item(id: "e", kind: .removed, groupId: g, groupName: "Goa", byName: "Asha", memberId: pid(4), memberName: "Sam")]
        func kinds(_ me: String, _ you: String?, admins: [String] = []) -> [RoleRules.RequestCard] {
            let rows = PeopleRoles.rows(members: members, selfId: me, viewerYou: you, claims: [pid(1): o, pid(3): m],
                                        roles: RoleRules.Roles(owner: o, admins: admins))
            return RequestCards.cards(items: open, rows: rows).map(\.kind)
        }
        XCTAssertEqual(kinds(pid(1), o), [.decide], "only the request about someone here, and only a request")
        XCTAssertEqual(kinds(pid(3), m), [.asker], "a member holding the notice who is not the person asked")
        XCTAssertEqual(kinds(pid(4), nil), [.person], "the person named")
        XCTAssertEqual(kinds(pid(3), m, admins: [m]), [.decide], "an admin decides on a member")
    }

    /// Review fix C1: a request naming an admin is decided by the owner alone.
    func testARequestNamingAnAdminIsTheOwnersAlone() {
        XCTAssertEqual(RoleRules.requestCard(viewer: .admin, person: .admin, viewerIsAsker: false, viewerIsPerson: false), .ownerDecides)
        XCTAssertEqual(RoleRules.requestCard(viewer: .owner, person: .admin, viewerIsAsker: false, viewerIsPerson: false), .decide)
        XCTAssertEqual(RoleRules.requestCard(viewer: .admin, person: .unjoined, viewerIsAsker: false, viewerIsPerson: false), .decide)
        let a2 = "4" + String(repeating: "0", count: 31)
        let members = [MemberWire(id: pid(1), name: "Prithvi"), MemberWire(id: pid(2), name: "Rahul"),
                       MemberWire(id: pid(3), name: "Asha"), MemberWire(id: pid(4), name: "Sam")]
        let claims = [pid(1): o, pid(2): a, pid(3): m, pid(4): a2]
        let roles = RoleRules.Roles(owner: o, admins: [a, a2])
        func rows(_ me: String, _ you: String) -> [PersonRow] {
            PeopleRoles.rows(members: members, selfId: me, viewerYou: you, claims: claims, roles: roles)
        }
        let aboutSam = [request("r", asker: "Asha", member: pid(4))]
        // Rahul, an admin: Asha's request about Sam, another admin, is not his to decide.
        XCTAssertEqual(RequestCards.cards(items: aboutSam, rows: rows(pid(2), a)).map(\.kind), [.ownerDecides])
        XCTAssertFalse(RequestCards.mayRemove(rows: rows(pid(2), a), member: pid(4)), "no Continue to remove for an admin")
        XCTAssertTrue(RequestCards.mayRemove(rows: rows(pid(2), a), member: pid(3)), "an admin removes a member")
        // Prithvi, the owner, decides it.
        XCTAssertEqual(RequestCards.cards(items: aboutSam, rows: rows(pid(1), o)).map(\.kind), [.decide])
        XCTAssertTrue(RequestCards.mayRemove(rows: rows(pid(1), o), member: pid(4)))
        XCTAssertFalse(RequestCards.mayRemove(rows: rows(pid(1), o), member: pid(1)), "nobody removes the owner")
        XCTAssertFalse(RequestCards.mayRemove(rows: rows(pid(1), o), member: pid(9)), "nobody who is not here")
        // Asha asked; Sam is the person.
        XCTAssertEqual(RequestCards.cards(items: aboutSam, rows: rows(pid(3), m)).map(\.kind), [.asker])
        XCTAssertEqual(RequestCards.cards(items: aboutSam, rows: rows(pid(4), a2)).map(\.kind), [.person])
        XCTAssertEqual(RolesCopy.cardOwnerWill, "The owner will decide.")
    }

    func testRequestSentComesFromTheOpenRequest() {
        let open = [request("r", asker: "Asha", member: pid(4))]
        XCTAssertTrue(RequestCards.asked(items: open, member: pid(4)))
        XCTAssertFalse(RequestCards.asked(items: open, member: pid(3)))
        XCTAssertFalse(RequestCards.asked(items: [Notices.Item(id: "x", kind: .declined, groupId: String(repeating: "c", count: 32),
                                                               groupName: "Goa", byName: "Tim", memberId: pid(4))], member: pid(4)),
                       "a decided request is no longer open")
    }

    /// D-213: in Norse the currencies of that line are joined by the rune separator, not the middle dot.
    func testTheSheetsBalanceUsesNorsesSeparator() throws {
        let state = AppearanceState.shared
        let before = state.theme
        defer { state.theme = before }
        state.theme = .norse
        let inr = try XCTUnwrap(RolesMoney.format(20_000, "INR")), usd = try XCTUnwrap(RolesMoney.format(500, "USD"))
        XCTAssertEqual(RolesCopy.balanceHere("Asha", plan: ["USD": 500, "INR": -20_000]),
                       "Asha owes \(inr) \u{16EB} Asha is owed \(usd) here")
    }

    /// Review fixes Minor 6 and 7: the sheet's balance ends with "here"; an
    /// amount core cannot format hides its line (no stand-in glyph).
    func testTheSheetsBalanceSaysHereAndHidesWhatCoreCannotFormat() throws {
        let inr = try XCTUnwrap(RolesMoney.format(20_000, "INR")), usd = try XCTUnwrap(RolesMoney.format(500, "USD"))
        XCTAssertEqual(RolesCopy.balanceHere("Asha", plan: ["INR": -20_000]), "Asha owes \(inr) here")
        XCTAssertEqual(RolesCopy.balanceHere("Asha", plan: ["USD": 500, "INR": -20_000]),
                       "Asha owes \(inr) · Asha is owed \(usd) here")
        XCTAssertNil(RolesCopy.balanceHere("Asha", plan: [:]), "settled: nothing")
        XCTAssertNil(RolesMoney.format(100, "zz"), "core refuses a code that is not one")
        XCTAssertNil(RolesCopy.balanceHere("Asha", plan: ["zz": -100]))
        XCTAssertEqual(RolesCopy.balanceHere("Asha", plan: ["zz": -100, "INR": -20_000]), "Asha owes \(inr) here")
        XCTAssertNil(GroupSettings.stillOpen(nets: ["zz": [pid(1): 100]], names: [pid(1): "Prithvi"]))
        let line = ActivityFeed.Moved(id: "m", group: "g", atMs: 1, from: pid(4), currency: "zz", amountMinor: 100, to: [pid(1)])
        XCTAssertNil(ActivityFeed.movedLine(line, names: [pid(4): "Sam", pid(1): "Prithvi"]), "the line is not shown")
    }

    /// Review fix I1: the payments are appended once, whatever fails after.
    @MainActor
    func testSettleFirstNeverRecordsThePaymentsTwice() async throws {
        struct Down: Error {}
        var appended: [Int] = [], removals = 0, syncs = 0
        var failAppendAt: Int? = 1, failRemove = true
        var p = SettleFirstProgress(total: 3)
        let append: (Int) throws -> Void = { i in
            if i == failAppendAt { failAppendAt = nil; throw Down() }
            appended.append(i)
        }
        let sync: () async -> Bool = { syncs += 1; return true }
        let remove: () async throws -> Void = { removals += 1; if failRemove { throw Down() } }
        // A vault write fails on the second payment.
        do { try await SettleFirstProgress.run(&p, append: append, sync: sync, remove: remove); XCTFail("should throw") } catch {}
        XCTAssertEqual(appended, [0])
        XCTAssertFalse(p.recorded)
        XCTAssertEqual(removals, 0, "no removal while a payment is missing")
        // The next tap appends the rest only; the removal fails (offline).
        do { try await SettleFirstProgress.run(&p, append: append, sync: sync, remove: remove); XCTFail("should throw") } catch {}
        XCTAssertEqual(appended, [0, 1, 2], "the first payment is not appended again")
        XCTAssertTrue(p.recorded)
        XCTAssertFalse(p.removed)
        XCTAssertEqual(removals, 1)
        // Recorded: a tap retries the removal alone.
        failRemove = false
        try await SettleFirstProgress.run(&p, append: append, sync: sync, remove: remove)
        XCTAssertEqual(appended, [0, 1, 2], "never the same payments twice")
        XCTAssertEqual(removals, 2)
        XCTAssertTrue(p.removed)
        XCTAssertEqual(syncs, 2, "the payments reach the cloud before each removal")
        try await SettleFirstProgress.run(&p, append: append, sync: sync, remove: remove)
        XCTAssertEqual(removals, 2, "done is done")
    }

    /// Review fix I2: a history line names the person who was removed.
    @MainActor
    func testAHistoryLineNamesTheRemovedPerson() async throws {
        let store = try tempStore()
        let me = try store.setYourName("Prithvi")
        let group = try store.createGroup(name: "Goa", settlementCurrency: "INR", people: ["Sam", "Rahul"])
        let sam = group.members.first { $0.name == "Sam" }!, rahul = group.members.first { $0.name == "Rahul" }!
        _ = try store.appendExpense(LedgerStore.ExpenseDTO(
            payer: me.id, paidMinor: 90_000, currencyCode: "INR", group: group.group.id, conversion: nil,
            shares: [me.id, sam.id, rahul.id].map { .init(id: $0, amountMinor: 30_000) }))
        let shares = try ffiRedistributeEqual(balanceMinor: -30_000, members: [me.id, rahul.id])
        try await Removal.remove(
            store: store, group: group, member: sam, you: nil, byName: "Prithvi",
            redistributions: [RedistributionInput(group: group.group.id, from: sam.id, currencyCode: "INR", shares: shares)],
            shared: false)
        XCTAssertFalse(try store.namedGroups().first!.members.contains { $0.id == sam.id }, "Sam is out")
        let moved = ActivityFeed.moved(try store.allRecords(), only: group.group.id)
        XCTAssertEqual(moved.count, 1)
        let to = shares.map { $0.id == me.id ? "Prithvi" : "Rahul" }
        XCTAssertEqual(ActivityFeed.movedLine(moved[0], names: try store.historyNames(group: group.group.id)),
                       RolesCopy.moved(from: "Sam", amount: try XCTUnwrap(RolesMoney.format(30_000, "INR")), to: to))
    }

    /// D-234 (L8, the D-230 look's F2): an expense row names its author from the group's history when the author was
    /// removed ("Sam added Taxi", not "Someone added Taxi"); a current member by the group; an id nobody ever named stays
    /// "Someone". `ActivityFeed.load` reads the history names of every group in the feed. Android's twin:
    /// RolesTest.an_expense_row_names_a_removed_author.
    @MainActor
    func testAnExpenseRowNamesARemovedAuthor() async throws {
        let store = try tempStore()
        let me = try store.setYourName("Prithvi")
        let group = try store.createGroup(name: "Goa", settlementCurrency: "INR", people: ["Sam", "Rahul"])
        let sam = group.members.first { $0.name == "Sam" }!, rahul = group.members.first { $0.name == "Rahul" }!
        try await Removal.remove(
            store: store, group: group, member: sam, you: nil, byName: "Prithvi",
            redistributions: [], shared: false)
        XCTAssertFalse(try store.namedGroups().first!.members.contains { $0.id == sam.id }, "Sam is out")
        let g = group.group.id
        func item(by: String) -> ActivityFeed.Item {
            ActivityFeed.Item(expenseID: "e-\(by)", group: g, kind: .added, by: by, atMs: 1, title: "Taxi", amountMinor: 100, currency: "INR")
        }
        let groups = Dictionary(uniqueKeysWithValues: try store.namedGroups().map { ($0.group.id, $0) })
        let names = [g: try store.historyNames(group: g)]
        XCTAssertEqual(ActivityFeed.who(item(by: sam.id), groups: groups, names: names), "Sam", "the removed author")
        XCTAssertEqual(ActivityFeed.who(item(by: rahul.id), groups: groups, names: names), "Rahul", "a current member")
        XCTAssertEqual(ActivityFeed.who(item(by: String(repeating: "f", count: 32)), groups: groups, names: names), "Someone")
        XCTAssertEqual(ActivityFeed.who(item(by: sam.id), groups: groups, names: [:]), "Someone", "without the history, as before")
        _ = me
        // Source: every place an expense row is worded goes through `who`, and `load` reads every feed group's history.
        let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().appendingPathComponent("Skipti/App")
        let feed = try String(contentsOf: root.appendingPathComponent("ActivityFeed.swift"), encoding: .utf8)
        let home = try String(contentsOf: root.appendingPathComponent("HomeParts.swift"), encoding: .utf8)
        XCTAssertTrue(feed.contains("for g in Set(out.items.map(\\.group)) where out.names[g] == nil { out.names[g] = try store.historyNames(group: g) }"))
        XCTAssertEqual(feed.components(separatedBy: "ActivityFeed.who(item, groups: feed.groups, names: feed.names)").count, 2)
        XCTAssertEqual(home.components(separatedBy: "ActivityFeed.who(item, groups: feed.groups, names: feed.names)").count, 2)
        XCTAssertFalse(feed.contains("$0.id == item.by }?.name } ?? ActivityFeed.someone"), "no row words it from current members alone")
        XCTAssertFalse(home.contains("$0.id == item.by }?.name } ?? ActivityFeed.someone"))
    }

    /// Review fix Minor 2: this phone called removeMember, the Function wrote
    /// the records, and the local save failed: they come in from the cloud.
    func testThisPhonesOwnRemovalComesInWhenItsCopyWasLost() throws {
        let store = try tempStore()
        _ = try store.setYourName("Prithvi")
        let group = try store.createGroup(name: "Goa", settlementCurrency: "INR", people: ["Sam"])
        let g = group.group.id, sam = group.members.first { $0.name == "Sam" }!.id
        let device = try LedgerStore.Sync.deviceId(store)
        let records = try Removal.records(store: store, group: g, member: sam, redistributions: [
            RedistributionInput(group: g, from: sam, currencyCode: "INR", shares: [IdAmount(id: group.selfId, amountMinor: -100)])], placing: .none)
        XCTAssertEqual(records.map(\.recordType), [6, 3])
        XCTAssertNil(CloudRecords.refusal(records[1], removal: true, from: g, thisDevice: device), "the Function wrote it")
        XCTAssertNil(CloudRecords.refusal(records[0], removal: true, from: g, thisDevice: device))
        XCTAssertEqual(CloudRecords.refusal(records[1], removal: false, from: g, thisDevice: device), .thisDevice)
        let expense = RecordWire(version: 1, recordType: 1, groupId: g, epoch: 0, hlcMillis: 1, hlcCounter: 0,
                                 hlcDeviceId: device, authorDeviceId: device, nonceSession: Data(), flags: 0,
                                 blob: try JSONEncoder().encode(LedgerStore.ExpenseDTO(
                                     payer: group.selfId, paidMinor: 100, currencyCode: "INR", group: g, conversion: nil,
                                     shares: [.init(id: group.selfId, amountMinor: 100)])))
        XCTAssertEqual(CloudRecords.refusal(expense, removal: true, from: g, thisDevice: device), .thisDevice,
                       "the mark lets in only a removal or a redistribution")
        let documents = records.map { r -> [String: Any] in
            var d = CloudRecords.document(r, uid: "u")
            d["createdAt"] = Timestamp(seconds: 1, nanoseconds: 0)
            d["removal"] = true
            return d
        }
        XCTAssertEqual(try CloudRecords.absorb(documents, groupId: g, store: store).count, 2)
        XCTAssertFalse(try store.namedGroups().first!.members.contains { $0.id == sam }, "Sam is out on this phone too")
        XCTAssertEqual(try CloudRecords.absorb(documents, groupId: g, store: store).count, 0, "applied once")
        XCTAssertEqual(try store.allRecords().filter { [3, 6].contains($0.recordType) && $0.hlcDeviceId == device
            && records.map(CloudRecords.documentId).contains(CloudRecords.documentId($0)) }.count, 2)
    }

    func testTheNewWordsMatchAndroid() {
        XCTAssertEqual(RolesCopy.rename, "Rename")
        XCTAssertEqual(RolesCopy.ofTotal("₹1,000.00", "₹1,000.00"), "₹1,000.00 of ₹1,000.00")
        XCTAssertEqual(RolesCopy.rowBalance(-20_000, "₹200.00"), "owes ₹200.00")
        XCTAssertEqual(RolesCopy.rowBalance(70_000, "₹700.00"), "owed ₹700.00")
        XCTAssertEqual(RolesCopy.rowBalance(0, "₹0.00"), "settled")
        XCTAssertEqual(RolesCopy.takes("₹333.34"), "takes ₹333.34")
        XCTAssertEqual(RolesCopy.rowNow("owes ₹200.00"), "owes ₹200.00 now")
        XCTAssertTrue(RolesCopy.alreadyAsked(CloudFunctions.Failure.refused("ALREADY_EXISTS")), "reads as Request sent")
        XCTAssertFalse(RolesCopy.alreadyAsked(CloudFunctions.Failure.refused("PERMISSION_DENIED")))
        XCTAssertFalse(RolesCopy.alreadyAsked(CloudFunctions.Failure.offline))
    }

    // MARK: - Group settings, history and the read-only copy

    func testStillOpenNamesEveryOpenBalanceByCurrencyLargestFirst() {
        let names = [pid(1): "Prithvi", pid(2): "Rahul", pid(3): "Asha", pid(4): "Sam", pid(5): "Nina"]
        let nets: [String: [String: Int64]] = [
            "USD": [pid(3): 500],
            "INR": [pid(1): 70_000, pid(2): 50_000, pid(3): -20_000, pid(4): -100_000, pid(5): 0],
        ]
        func f(_ n: Int64, _ c: String) -> String { RolesMoney.format(n, c)! }
        XCTAssertEqual(GroupSettings.stillOpen(nets: nets, names: names), [
            "Sam owes \(f(100_000, "INR"))", "Prithvi is owed \(f(70_000, "INR"))", "Rahul is owed \(f(50_000, "INR"))",
            "Asha owes \(f(20_000, "INR"))", "Asha is owed \(f(500, "USD"))",
        ].joined(separator: " · "), "joined with a middle dot; Nina is settled and left out")
        XCTAssertNil(GroupSettings.stillOpen(nets: ["INR": [pid(1): 0]], names: names))
        XCTAssertNil(GroupSettings.stillOpen(nets: [:], names: names))
    }

    /// What group settings offers (twin of RolesTest.kt): a copy offers
    /// nothing that changes the group; in the cloud only the owner deletes it;
    /// a group only on this phone is anyone's to delete.
    func testGroupSettingsOffersNothingOnACopy() {
        func a(_ name: Bool, _ picture: Bool, _ delete: Bool) -> GroupSettings.Actions {
            GroupSettings.Actions(name: name, picture: picture, delete: delete)
        }
        let none = a(false, false, false)
        XCTAssertEqual(GroupSettings.actions(readOnly: true, isOwner: true, shared: true), none)
        XCTAssertEqual(GroupSettings.actions(readOnly: true, isOwner: false, shared: true), none)
        XCTAssertEqual(GroupSettings.actions(readOnly: true, isOwner: true, shared: false), none)
        XCTAssertEqual(GroupSettings.actions(readOnly: true, isOwner: false, shared: false), none)
        XCTAssertEqual(GroupSettings.actions(readOnly: false, isOwner: true, shared: true), a(true, true, true))
        XCTAssertEqual(GroupSettings.actions(readOnly: false, isOwner: false, shared: true), a(true, true, false))
        XCTAssertEqual(GroupSettings.actions(readOnly: false, isOwner: true, shared: false), a(true, true, true))
        XCTAssertEqual(GroupSettings.actions(readOnly: false, isOwner: false, shared: false), a(true, true, true))
    }

    func testARedistributionIsAHistoryLineWithTheGroupsNames() throws {
        let g = String(repeating: "c", count: 32), other = String(repeating: "e", count: 32)
        func moved(_ group: String, _ blob: Data, millis: UInt64) -> RecordWire {
            RecordWire(version: 1, recordType: 6, groupId: group, epoch: 0, hlcMillis: millis, hlcCounter: 0,
                       hlcDeviceId: String(repeating: "b", count: 32), authorDeviceId: String(repeating: "b", count: 32),
                       nonceSession: Removal.redistributionSession, flags: 0, blob: blob)
        }
        let sam = pid(4)
        let blob = try ffiEncodeRedistribution(r: RedistributionInput(group: g, from: sam, currencyCode: "INR", shares: [
            IdAmount(id: pid(1), amountMinor: -33_334), IdAmount(id: pid(2), amountMinor: -33_333), IdAmount(id: pid(3), amountMinor: -33_333)]))
        let elsewhere = try ffiEncodeRedistribution(r: RedistributionInput(group: other, from: sam, currencyCode: "INR", shares: [
            IdAmount(id: pid(1), amountMinor: -100)]))
        let records = [moved(g, blob, millis: 2_000), moved(g, Data([1, 2]), millis: 3_000), moved(other, elsewhere, millis: 4_000),
                       RecordWire(version: 1, recordType: 2, groupId: g, epoch: 0, hlcMillis: 5_000, hlcCounter: 0,
                                  hlcDeviceId: String(repeating: "b", count: 32), authorDeviceId: String(repeating: "b", count: 32),
                                  nonceSession: Data(), flags: 0, blob: blob)]
        let all = ActivityFeed.moved(records)
        XCTAssertEqual(all.map(\.group), [g, other], "an unreadable record and a payment are not history lines")
        let mine = ActivityFeed.moved(records, only: g)
        XCTAssertEqual(mine.count, 1)
        XCTAssertEqual(mine[0].amountMinor, 100_000, "the shares' sum")
        XCTAssertEqual(mine[0].atMs, 2_000)
        let names = [pid(1): "Prithvi", pid(2): "Rahul", pid(3): "Asha", sam: "Sam"]
        XCTAssertEqual(ActivityFeed.movedLine(mine[0], names: names),
                       "Sam's \(RolesMoney.format(100_000, "INR")!) moved to Prithvi, Rahul and Asha")
        XCTAssertEqual(ActivityFeed.movedLine(mine[0], names: [sam: "Sam"]),
                       "Sam's \(RolesMoney.format(100_000, "INR")!) moved", "nobody the phone can name: no dangling to")
    }

    func testAGroupIsRenamedByAnOperationAndNeverToNothing() throws {
        let store = try tempStore()
        _ = try store.setYourName("Prithvi")
        let g = try store.createGroup(name: "Goa", settlementCurrency: "INR", people: ["Sam"]).group.id
        try store.renameGroup(groupID: g, name: "  Goa trip  ")
        XCTAssertEqual(try store.namedGroups().first?.group.name, "Goa trip", "trimmed")
        XCTAssertTrue(try store.allRecords().contains { $0.recordType == 3 && (try? ffiDecodeGroupOp(blob: $0.blob)).map {
            if case .renameGroup(_, let n) = $0 { return n == "Goa trip" } else { return false } } == true })
        XCTAssertThrowsError(try store.renameGroup(groupID: g, name: "   "))
        XCTAssertEqual(try store.namedGroups().first?.group.name, "Goa trip", "left as it was")
    }

    @MainActor
    func testALocalRemovalMovesTheBalanceAndTakesThePersonOut() async throws {
        let store = try tempStore()
        let me = try store.setYourName("Prithvi")
        let group = try store.createGroup(name: "Goa", settlementCurrency: "INR", people: ["Sam", "Rahul"])
        let sam = group.members.first { $0.name == "Sam" }!, rahul = group.members.first { $0.name == "Rahul" }!
        // Prithvi paid 900, split three ways: Sam owes 300.
        _ = try store.appendExpense(LedgerStore.ExpenseDTO(
            payer: me.id, paidMinor: 90_000, currencyCode: "INR", group: group.group.id, conversion: nil,
            shares: [me.id, sam.id, rahul.id].map { .init(id: $0, amountMinor: 30_000) }))
        func balances(_ viewer: String) throws -> Int64 {
            try ffiBalanceLine(expenses: try store.loadExpenses().map(\.asInput), settlements: try store.loadSettlements().map(\.asInput),
                               redistributions: try store.loadRedistributions(), removed: [], viewer: viewer, group: group.group.id).first?.viewerNetMinor ?? 0
        }
        XCTAssertEqual(try balances(sam.id), -30_000)
        XCTAssertEqual(try balances(me.id), 60_000)
        let shares = try ffiRedistributeEqual(balanceMinor: -30_000, members: [me.id, rahul.id])
        try await Removal.remove(
            store: store, group: group, member: sam, you: nil, byName: "Prithvi",
            redistributions: [RedistributionInput(group: group.group.id, from: sam.id, currencyCode: "INR", shares: shares)],
            shared: false)
        XCTAssertEqual(try balances(sam.id), 0, "Sam's goes to zero")
        XCTAssertEqual(try balances(me.id), 45_000, "and the others take it: 600 less 150")
        XCTAssertEqual(try store.namedGroups().first?.members.map(\.name), ["Prithvi", "Rahul"])
        XCTAssertEqual(try CloudRecords.uploaded(store).count, 0, "a group only here marks nothing uploaded")
    }

    func testRemovalRecordsAppliedForACloudGroupAreMarkedUploaded() throws {
        let store = try tempStore()
        _ = try store.setYourName("Prithvi")
        let group = try store.createGroup(name: "Goa", settlementCurrency: "INR", people: ["Sam"])
        let sam = group.members.first { $0.name == "Sam" }!
        let records = try Removal.records(store: store, group: group.group.id, member: sam.id, redistributions: [], placing: .none)
        try Removal.applyLocally(records, store: store, uploaded: true)
        XCTAssertEqual(try CloudRecords.uploaded(store), Set(records.map(CloudRecords.documentId)),
                       "pushAll never sends what the Function already wrote")
        XCTAssertEqual(CloudRecords.pending(try store.allRecords(), uploaded: try CloudRecords.uploaded(store))
            .filter { $0.recordType == 3 && $0.groupId == group.group.id }.filter { r in
                records.contains { CloudRecords.documentId($0) == CloudRecords.documentId(r) } }.count, 0)
        XCTAssertEqual(try store.namedGroups().first?.members.map(\.name), ["Prithvi"])
    }
    // MARK: - Fix 2 (the look): L1 to L5 and minors

    /// L1: the delete confirm names every open balance; you are "you".
    func testStillOpenReadsYouAndEveryoneElse() throws {
        let names = [pid(1): "Prithvi", pid(5): "Nina"]
        let f = try XCTUnwrap(RolesMoney.format(50_001, "INR")), g = try XCTUnwrap(RolesMoney.format(2_000, "INR"))
        XCTAssertEqual(GroupSettings.stillOpen(nets: ["INR": [pid(1): 50_001, pid(5): -50_001]], names: names, viewer: pid(1)),
                       "you are owed \(f) · Nina owes \(f)")
        XCTAssertEqual(GroupSettings.stillOpen(nets: ["INR": [pid(1): -2_000, pid(5): 2_000]], names: names, viewer: pid(1)),
                       "you owe \(g) · Nina is owed \(g)")
        XCTAssertEqual(GroupSettings.stillOpen(nets: ["INR": [pid(1): -2_000, pid(5): 2_000]], names: names),
                       "Prithvi owes \(g) · Nina is owed \(g)", "no viewer: named")
    }

    /// L1, from a real ledger: a redistribution moved Sam's balance, and the
    /// line reads what is open after it (the look showed nothing).
    @MainActor
    func testStillOpenFromTheRealLedgerAfterARedistribution() async throws {
        let store = try tempStore()
        let me = try store.setYourName("Prithvi")
        let group = try store.createGroup(name: "Goa", settlementCurrency: "INR", people: ["Sam", "Rahul"])
        let sam = group.members.first { $0.name == "Sam" }!, rahul = group.members.first { $0.name == "Rahul" }!
        _ = try store.appendExpense(LedgerStore.ExpenseDTO(
            payer: me.id, paidMinor: 90_000, currencyCode: "INR", group: group.group.id, conversion: nil,
            shares: [me.id, sam.id, rahul.id].map { .init(id: $0, amountMinor: 30_000) }))
        func parts(_ line: String?) -> [String] { (line ?? "").components(separatedBy: " · ").filter { !$0.isEmpty }.sorted() }
        let c300 = try XCTUnwrap(RolesMoney.format(30_000, "INR")), c600 = try XCTUnwrap(RolesMoney.format(60_000, "INR"))
        XCTAssertEqual(parts(try GroupSettings.stillOpen(store: store, group: group.group.id, viewer: me.id)),
                       ["Rahul owes \(c300)", "Sam owes \(c300)", "you are owed \(c600)"])
        let shares = try ffiRedistributeEqual(balanceMinor: -30_000, members: [me.id, rahul.id])
        try await Removal.remove(
            store: store, group: group, member: sam, you: nil, byName: "Prithvi",
            redistributions: [RedistributionInput(group: group.group.id, from: sam.id, currencyCode: "INR", shares: shares)],
            shared: false)
        let c450 = try XCTUnwrap(RolesMoney.format(45_000, "INR"))
        XCTAssertEqual(parts(try GroupSettings.stillOpen(store: store, group: group.group.id, viewer: me.id)),
                       ["Rahul owes \(c450)", "you are owed \(c450)"], "Sam is out and at zero; the others took it")
        XCTAssertNil(try GroupSettings.stillOpen(store: store, group: String(repeating: "9", count: 32), viewer: me.id),
                     "a group with nothing in it: nothing open")
    }

    /// L2: what the review and the confirm say about someone who never joined.
    func testAnUnjoinedPersonIsToldNothingIsSentToThem() {
        XCTAssertEqual(RolesCopy.removalBody(name: "Sam", joined: true, noHistory: false),
                       "Sam will get a notification and keeps a read-only copy of the group's history.")
        XCTAssertEqual(RolesCopy.removalBody(name: "Sam", joined: false, noHistory: true),
                       "They are in no expense or payment here. You can add them again later.")
        XCTAssertEqual(RolesCopy.removalBody(name: "Sam", joined: false, noHistory: false),
                       "Sam has not joined, so nothing is sent to them.")
        XCTAssertEqual(RolesCopy.reviewBody(asker: "Asha", name: "Sam", joined: true, noHistory: false),
                       "Asha asked. Sam will get a notification and keeps a read-only copy of the group's history.")
        XCTAssertEqual(RolesCopy.reviewBody(asker: "Asha", name: "Sam", joined: false, noHistory: false),
                       "Asha asked. Sam has not joined, so nothing is sent to them.")
        XCTAssertEqual(RolesCopy.reviewBody(asker: "Asha", name: "Sam", joined: false, noHistory: true),
                       "Asha asked. They are in no expense or payment here. You can add them again later.")
        XCTAssertEqual(RolesCopy.askNote(name: "Sam", joined: false),
                       "Only the owner or an admin can remove someone. The owner and the admins will see your request.")
        XCTAssertEqual(RolesCopy.askNote(name: "Sam", joined: true),
                       "Only the owner or an admin can remove someone. Sam, the owner and the admins will see your request.")
    }

    /// L3: a request card and the review sheet say what the person owes.
    func testARequestCardAndTheReviewSayWhatThePersonOwes() throws {
        let inr = try XCTUnwrap(RolesMoney.format(100_000, "INR"))
        XCTAssertEqual(RolesCopy.cardBalance("Sam", plan: ["INR": -100_000]), "Sam owes \(inr) here.")
        XCTAssertEqual(RolesCopy.cardBalance("Sam", plan: ["INR": 100_000]), "Sam is owed \(inr) here.")
        XCTAssertNil(RolesCopy.cardBalance("Sam", plan: [:]), "settled: no line")
        let banner = try XCTUnwrap(RolesCopy.reviewBanner("Sam", plan: ["INR": -100_000]))
        XCTAssertEqual(banner.title, "Sam owes \(inr) here")
        XCTAssertEqual(banner.sub, "You'll choose next: settle first, or move it to others.")
        XCTAssertNil(RolesCopy.reviewBanner("Sam", plan: [:]))
    }

    /// L4: the phone that deleted the group says "You deleted". D-252: "You deleted {group}." (GroupEndCopy), the same
    /// words on the account's other phones (its notice now reaches them).
    func testTheDeletingPhoneSaysYouDeleted() throws {
        let day = "October 13, 2026", at = Date(timeIntervalSince1970: 1_800_000_000)
        var mine = LedgerStore.ReadOnly(kind: "deleted", by: "Prithvi", at: at)
        mine.byYou = true
        XCTAssertEqual(ReadOnlyBanner.title(mark: mine, date: day, group: "Goa"), "You deleted Goa.")
        XCTAssertEqual(ReadOnlyBanner.title(mark: LedgerStore.ReadOnly(kind: "deleted", by: "Asha", at: at), date: day, group: "Goa"),
                       "Asha deleted this group on October 13, 2026", "someone else's delete reads as it did")
        XCTAssertEqual(ReadOnlyBanner.title(mark: LedgerStore.ReadOnly(kind: "removed", by: "Asha", at: at), date: day, group: "Goa"),
                       "Asha removed you on October 13, 2026")
        // A mark stored before the field existed still reads, and a new one round-trips.
        let old = Data(#"{"kind":"deleted","by":"Asha","at":0}"#.utf8)
        XCTAssertNil(try JSONDecoder().decode(LedgerStore.ReadOnly.self, from: old).byYou)
        XCTAssertEqual(try JSONDecoder().decode(LedgerStore.ReadOnly.self, from: JSONEncoder().encode(mine)), mine)
    }

    /// L5: a copy's People shows nobody's role and offers nothing.
    func testACopysPeopleOfferNothingAndShowNoRoles() {
        let roles = RoleRules.Roles(owner: o, admins: [a])
        let members = [MemberWire(id: pid(1), name: "Prithvi"), MemberWire(id: pid(2), name: "Rahul"), MemberWire(id: pid(3), name: "Asha")]
        let claims = [pid(1): o, pid(2): a, pid(3): m]
        let live = PeopleRoles.rows(members: members, selfId: pid(3), viewerYou: m, claims: claims, roles: roles)
        XCTAssertEqual(live.map(\.role), [.owner, .admin, .member])
        let copy = PeopleRoles.rows(members: members, selfId: pid(3), viewerYou: m, claims: claims, roles: roles, readOnly: true)
        XCTAssertEqual(copy.map(\.role), [.member, .member, .member], "no Owner or Admin pill in a copy")
        let none = RoleRules.Offer(remove: false, ask: false, askSelf: false, makeAdmin: false, removeAdmin: false, makeOwner: false)
        XCTAssertEqual(copy.map(\.offer), [none, none, none])
        XCTAssertEqual(copy.map(\.you), live.map(\.you), "who is who still reads")
    }

    /// Minor 1: Redistribute goes once any payment is recorded.
    func testRedistributeIsOverOnceAPaymentIsRecorded() async throws {
        struct Down: Error {}
        var p = SettleFirstProgress(total: 2)
        XCTAssertFalse(p.started)
        do {
            try await SettleFirstProgress.run(&p, append: { i in if i == 1 { throw Down() } }, sync: { true }, remove: {})
            XCTFail("should throw")
        } catch {}
        XCTAssertEqual(p.appended, 1)
        XCTAssertFalse(p.recorded, "only partly recorded")
        XCTAssertTrue(p.started, "but the balance has already moved")
    }

    /// Minor 2: a payment core cannot write down is never recorded.
    func testNoPaymentIsRecordedThatCoreCannotFormat() {
        func pay(_ code: String) -> LedgerStore.SettlementDTO {
            .init(from: pid(1), to: pid(2), amountMinor: 100, currencyCode: code, group: pid(9))
        }
        XCTAssertTrue(Removal.canFormat([pay("INR"), pay("USD")]))
        XCTAssertFalse(Removal.canFormat([pay("INR"), pay("zz")]))
        XCTAssertTrue(Removal.canFormat([]))
    }

    /// The Functions count UTF-16 units; twin: a_wire_name_never_ends_in_half_an_emoji.
    func testAWireNameNeverEndsInHalfAnEmoji() {
        let a79 = String(repeating: "a", count: 79)
        XCTAssertEqual(RoleRules.wireName(a79 + "\u{1F600}"), a79)
        let a80 = String(repeating: "a", count: 80)
        XCTAssertEqual(RoleRules.wireName(a80), a80)
        XCTAssertEqual(RoleRules.wireName(String(repeating: "a", count: 90)), a80)
        let a78 = String(repeating: "a", count: 78)
        XCTAssertEqual(RoleRules.wireName(a78 + "\u{1F600}" + "b"), a78 + "\u{1F600}")
        XCTAssertEqual(RoleRules.wireName("  Ana  "), "Ana")
    }

    /// D-225 fix wave (the controller's F3 decision, look F3, review M8): a tile in a row's 30 slot is drawn inside it in
    /// every theme, so Norse's diamond (drawn at size x sqrt 2) keeps Aurora's gap to the chevron instead of touching it.
    func testATileInARowSlotIsDrawnInsideIt() {
        for shape in [TileShape.rounded, .circle, .diamond, .square] {
            let size = TileClip.sizeInSlot(shape, slot: 30)
            XCTAssertEqual(TileClip.drawnSide(shape, size: size), 30, accuracy: 1e-9, "\(shape) fills the slot")
            if shape == .diamond {
                XCTAssertEqual(size, 30 / 2.0.squareRoot(), accuracy: 1e-9)
            } else {
                XCTAssertEqual(size, 30, "\(shape) as before")
            }
        }
    }

    /// D-225 (Designs 7, 8; L-D217-1, L-D217-8, A1): Group settings and People left the system Form (its sections stayed
    /// rounded in Norse and Vardr Labs) for list cards on the theme's ground. Every confirmation is where it was.
    func testGroupSettingsAndPeopleLeftTheForm() throws {
        let settings = try s2Slice(try s2Source("GroupSettings.swift"), from: "struct GroupSettingsScreen: View {", to: "\n}\n")
        for banned in ["Form {", "Section {", "auroraFormBackground", "private func row(", "AnyView(Text("] {
            XCTAssertFalse(settings.contains(banned), "Group settings: \(banned)")
        }
        // Fix wave (look F3): the picture tile drawn inside its 30 slot and laid out by that drawn side.
        let tile = try s2Slice(settings, from: "private func pictureTile(_ group: GroupWire) -> some View {", to: "\n    }\n")
        for needle in ["let size = TileClip.sizeInSlot(shape, slot: 30)", "return GroupTile(group: group, size: size).tileFootprint(shape, size: size)"] {
            XCTAssertTrue(tile.contains(needle), "the picture tile: \(needle)")
        }
        XCTAssertEqual(s2Count(settings, "ListCard {"), 2, "the settings card and the danger card")
        s2InOrder(settings, [
            "ScrollView {", "ListCard {", "if actions.name {",
            "ListRow(title: RolesCopy.settingsName, trailing: .value(named.group.name, chevron: true))",
            "if actions.picture {",
            "ListRow(title: RolesCopy.settingsPicture,", "trailing: .piece(AnyView(pictureTile(named.group)), chevron: true))",
            "ListRow(title: RolesCopy.settingsSettlesIn, trailing: .value(named.group.settlementCurrency, chevron: false))",
            "if actions.delete {", "ListCard {", "Button { startDelete(named) } label: {",
            "ListRow(title: RolesCopy.deleteGroup, parts: people.shared ? [RolesCopy.deleteGroupSub] : [],", "tone: .danger)",
            ".auroraBackground()",
        ])
        for needle in [".sheet(isPresented: $choosingPicture)", ".sheet(item: $deleteConfirm)", ".hapticWarning(when: deleteConfirm != nil)",
                       ".alert(RolesCopy.settingsName, isPresented: $renaming)", "isPresented: $confirmingLocalDelete",
                       "Button(RolesCopy.deleteLocalConfirm, role: .destructive) { deleteHere() }",
                       ".hapticWarning(when: confirmingLocalDelete)", ".disabled(busy)"] {
            XCTAssertTrue(settings.contains(needle), "Group settings keeps: \(needle)")
        }
        let people = try s2Slice(try s2Source("GroupPicker.swift"), from: "struct PeopleScreen: View {", to: "\n}\n")
        for banned in ["Form {", "Section {", "auroraFormBackground", "xmark", "systemName:", ".listRow", "TextField("] {
            XCTAssertFalse(people.contains(banned), "People: \(banned)")
        }
        XCTAssertEqual(s2Count(people, "ListCard {"), 2, "the members and the invites")
        s2InOrder(people, [
            "ScrollView {", ".requestCardSurface()", "header(\"People\")", "ListCard {", "memberRow(member, named)",
            "CardCaption(MemberCopy.stays)", "header(InviteCopy.section)", "ListCard {",
            "ListRow(title: InviteCopy.invite(member.name), trailing: .chevron)", "CardCaption(InviteCopy.sectionFooter)",
            "Button(AddPersonCopy.title) { adding = true }\n                        .buttonStyle(PrimaryButtonStyle())",
            ".auroraBackground()",
        ])
        let row = try s2Slice(people, from: "private func memberRow(_ member: MemberWire, _ named: NamedGroupWire) -> some View {", to: "\n    }\n")
        // Fix wave (review M7): the rename field tells VoiceOver it is a new name (Android's `spoken` the same).
        for needle in ["FieldRow(\"Name\", text: $editText, spoken: \"New name\")", ".onSubmit { rename(member.id) }",
                       "memberLine(member, named, avatar: true, role: row.role, chevron: true)",
                       "LucideIcon(glyph: .x, size: ThemeArt.m(\"clearGlyph\"))", ".foregroundStyle(Tokens.textFaint)",
                       ".frame(width: ThemeArt.m(\"touchTarget\"), height: ThemeArt.m(\"touchTarget\"))",
                       ".accessibilityLabel(MemberCopy.spokenRemove(member.name))", ".accessibilityHint(\"Rename\")"] {
            XCTAssertTrue(row.contains(needle), "a member row: \(needle)")
        }
        let line = try s2Slice(people, from: "private func memberLine(", to: "\n    }\n")
        for needle in ["PersonAvatar(name: member.name, size: 36, preview: preview(of: member.id))", ".font(TypeRole.name(16))",
                       "if let role { RolePill(role: role) }", "if chevron { RowChevron(color: Tokens.textDim) }",
                       ".padding(.vertical, ThemeArt.m(\"rowPadV\"))", ".frame(minHeight: ThemeArt.m(\"touchTarget\"))"] {
            XCTAssertTrue(line.contains(needle), "a member line: \(needle)")
        }
        for needle in [".sheet(isPresented: $adding)", ".sheet(item: $personSheet)", ".sheet(item: $review)",
                       "Button(MemberCopy.remove, role: .destructive)", ".hapticWarning(when: removing != nil)"] {
            XCTAssertTrue(people.contains(needle), "People keeps: \(needle)")
        }
    }
}

/// D-210 Task 8, Fix 2 (the look on devices). Twins of GroupFixesTest.kt.
final class GroupFixesTests: XCTestCase {
    private var dir: URL!

    override func setUpWithError() throws {
        dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("group-fixes-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: dir)
    }

    private func device(_ name: String) -> LedgerStore {
        LedgerStore(path: dir.appendingPathComponent("\(name).db").path)
    }

    /// A phone that holds Goa's records (made by someone else) but whose own
    /// place in Goa no create or addMember record names yet: the invitee who
    /// joined before the owner's addMember reached the cloud.
    private func phoneWithAGroupItIsNotIn() throws -> (LedgerStore, flat: String, goa: String) {
        let owner = device("owner")
        _ = try owner.setYourName("Tim")
        let goa = try owner.createGroup(name: "Goa", settlementCurrency: "INR", people: ["Rahul"])
        let invitee = device("invitee")
        _ = try invitee.setYourName("Asha")
        let flat = try invitee.createGroup(name: "Flat", settlementCurrency: "INR", people: ["Meera"])
        for record in try owner.allRecords() where record.groupId == goa.group.id {
            _ = try invitee.applyRecord(record)
        }
        try invitee.rebuildProjection()
        return (invitee, flat.group.id, goa.group.id)
    }

    /// One group whose place for this account is missing never fails the
    /// others: it is marked waiting, with its name, and reads and writes work
    /// around it.
    func testAGroupWhoseOwnPlaceIsMissingWaitsAndTheOthersLoad() throws {
        let (phone, flat, goa) = try phoneWithAGroupItIsNotIn()
        XCTAssertEqual(try phone.namedGroups().map(\.group.id), [flat])
        XCTAssertEqual(try phone.waitingGroups(), [LedgerStore.WaitingGroup(id: goa, name: "Goa")])
        try phone.addMember(groupID: flat, name: "Sam")
        XCTAssertEqual(try phone.namedGroups().first?.members.map(\.name), ["Asha", "Meera", "Sam"])
        XCTAssertEqual(try phone.waitingGroups().map(\.id), [goa])
    }

    func testNothingWaitsWhenEveryGroupHasYourPlace() throws {
        let a = device("plain")
        _ = try a.setYourName("Tim")
        _ = try a.createGroup(name: "Goa", settlementCurrency: "INR", people: ["Rahul"])
        XCTAssertEqual(try a.namedGroups().count, 1)
        XCTAssertTrue(try a.waitingGroups().isEmpty)
    }

    /// The waiting copy's words, identical on both shells (twin: the_waiting_words_match_ios).
    func testTheWaitingWordsMatchAndroid() {
        XCTAssertEqual(GroupsCopy.waiting, "Waiting for this group to finish arriving.")
    }

    /// Every way of adding a person tells the sync once the operation is
    /// saved, and not when the add is refused.
    func testAddingAPersonTellsTheSyncOnceItIsSaved() throws {
        let a = device("adder")
        _ = try a.setYourName("Tim")
        let goa = try a.createGroup(name: "Goa", settlementCurrency: "INR", people: ["Rahul"])
        var synced = 0
        let (named, member) = try XCTUnwrap(try AddPerson.add(store: a, groupID: goa.group.id, name: "Sam") { _ in synced += 1 })
        XCTAssertEqual(member.name, "Sam")
        XCTAssertTrue(named.members.contains { $0.id == member.id })
        XCTAssertEqual(synced, 1)
        XCTAssertThrowsError(try AddPerson.add(store: a, groupID: goa.group.id, name: "   ") { _ in synced += 1 })
        XCTAssertEqual(synced, 1, "a refused add syncs nothing")
    }

    /// D-211 (found on devices): a new group went up only at the next
    /// unrelated write. Creating one tells the sync once it is all saved (the
    /// name, the group, its picture), and not when anything is refused.
    /// Twin: creating_a_group_pushes_it_at_once.
    func testCreatingAGroupPushesItAtOnce() throws {
        let a = device("creator")
        var synced = 0
        let created = try CreateGroup.create(
            store: a, yourName: "Tim", name: "Goa", currency: "INR", people: ["Rahul"],
            emoji: "\u{1F98A}", photo: nil
        ) { synced += 1 }
        XCTAssertEqual(synced, 1)
        XCTAssertEqual(created.group.name, "Goa")
        XCTAssertEqual(try a.namedGroups().map(\.group.id), [created.group.id])
        XCTAssertThrowsError(try CreateGroup.create(
            store: a, yourName: nil, name: "   ", currency: "INR", people: [], emoji: nil, photo: nil
        ) { synced += 1 })
        XCTAssertEqual(synced, 1, "a refused create syncs nothing")
        XCTAssertEqual(try a.namedGroups().count, 1)
    }

    /// A retried delete whose first try deleted in the cloud gets NOT_FOUND:
    /// that is "already deleted", and the copy is marked.
    func testADeleteThatFindsTheGroupAlreadyGoneStillMarksTheCopy() async throws {
        var marked = 0
        try await GroupDelete.run(call: { throw CloudFunctions.Failure.refused("NOT_FOUND") }, mark: { marked += 1 })
        XCTAssertEqual(marked, 1, "already deleted: marked read-only")
        try await GroupDelete.run(call: {}, mark: { marked += 1 })
        XCTAssertEqual(marked, 2, "the ordinary path marks once")
        for failure in [CloudFunctions.Failure.refused("PERMISSION_DENIED"), .refused("FAILED_PRECONDITION"), .offline] {
            do {
                try await GroupDelete.run(call: { throw failure }, mark: { marked += 1 })
                XCTFail("must rethrow \(failure)")
            } catch let e as CloudFunctions.Failure {
                XCTAssertEqual(e, failure)
            }
        }
        XCTAssertEqual(marked, 2)
        XCTAssertFalse(GroupDelete.alreadyGone(CloudFunctions.Failure.refused("PERMISSION_DENIED")))
        XCTAssertTrue(GroupDelete.alreadyGone(CloudFunctions.Failure.refused("NOT_FOUND")))
        // A mark that fails is its own error, for the screen's words.
        struct Boom: Error {}
        do {
            try await GroupDelete.run(call: {}, mark: { throw Boom() })
            XCTFail("a failed mark is reported")
        } catch let e as GroupDelete.MarkFailed {
            XCTAssertTrue(e.underlying is Boom)
        }
    }
}

/// D-210's final fix wave B, both shells (twin: FinalFixesTest.kt, same
/// inputs, same expectations).
final class FinalFixesTests: XCTestCase {
    private var dir: URL!

    override func setUpWithError() throws {
        dir = FileManager.default.temporaryDirectory.appendingPathComponent("final-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: dir)
    }

    private func device(_ name: String) -> LedgerStore {
        LedgerStore(path: dir.appendingPathComponent("\(name).db").path)
    }

    private let g1 = String(repeating: "a", count: 32), g2 = String(repeating: "b", count: 32)
    private let uid = "uid-asha"

    // MARK: B1: a removed person at zero is on no card

    /// Asha paid 100 for Sam, Sam paid 100 for Prithvi, Sam is removed at
    /// zero: Home loads, no card names Sam, and Prithvi owes Asha 100.
    @MainActor
    func testAZeroNetRemovedPersonLeavesHomeAndEveryCard() async throws {
        let store = device("home")
        let me = try store.setYourName("Prithvi")
        let group = try store.createGroup(name: "Goa", settlementCurrency: "INR", people: ["Asha", "Sam"])
        let asha = group.members.first { $0.name == "Asha" }!, sam = group.members.first { $0.name == "Sam" }!
        _ = try store.appendExpense(LedgerStore.ExpenseDTO(
            payer: asha.id, paidMinor: 10_000, currencyCode: "INR", group: group.group.id, conversion: nil,
            shares: [.init(id: sam.id, amountMinor: 10_000)]))
        _ = try store.appendExpense(LedgerStore.ExpenseDTO(
            payer: sam.id, paidMinor: 10_000, currencyCode: "INR", group: group.group.id, conversion: nil,
            shares: [.init(id: me.id, amountMinor: 10_000)]))
        try await Removal.remove(store: store, group: group, member: sam, you: nil, byName: "Prithvi",
                                 redistributions: [], shared: false)
        XCTAssertEqual(try store.removedPeople()[group.group.id], [MemberWire(id: sam.id, name: "Sam")])
        let home = try HomeOverview.load(store)
        XCTAssertEqual(home.overview.people.map(\.name), ["Asha"], "Sam is on no card")
        XCTAssertEqual(home.overview.people.first?.netMinor, -10_000)
        // D-223 (M3): the same read carries each group's settled progress, one entry per group, the same blocks the
        // group's page reads (Android's FinalFixesTest twin).
        XCTAssertEqual(Set(home.progress.keys), [group.group.id])
        XCTAssertEqual(home.progress[group.group.id], try HomePartsTests.groupPageBlocks(store, group.group.id))
        XCTAssertEqual(home.progress[group.group.id]?.map(\.currencyCode), ["INR"], "D-223: the card's meter block")
        let mine = try Removal.lines(store: store, group: group.group.id, member: me.id)
        XCTAssertEqual(mine.first?.entries.map(\.id), [asha.id])
        XCTAssertEqual(mine.first?.entries.map(\.minorUnits), [-10_000])
    }

    // MARK: B2: a role change names the place and the account

    func testARoleChangeNamesThePlaceAndTheAccount() {
        let place = String(repeating: "4", count: 32), you = String(repeating: "5", count: 32)
        let on = GroupRoles.setAdminData(groupID: g1, member: place, you: you, on: true, byName: "Prithvi", groupName: "Goa")
        XCTAssertEqual(on["member"] as? String, place)
        XCTAssertEqual(on["you"] as? String, you)
        XCTAssertEqual(on["on"] as? Bool, true)
        XCTAssertEqual(on["groupId"] as? String, g1)
        XCTAssertEqual(on["groupName"] as? String, "Goa")
        let owner = GroupRoles.makeOwnerData(groupID: g1, member: place, you: you, byName: "Prithvi", groupName: "Goa")
        XCTAssertEqual(owner["member"] as? String, place)
        XCTAssertEqual(owner["you"] as? String, you)
        XCTAssertNil(owner["on"])
    }

    // MARK: B3: removed and deleted notices stay in the cloud; each phone keeps its own list

    private func notice(_ id: String, _ kind: Notices.Kind) -> Notices.Item {
        Notices.Item(id: id, kind: kind, groupId: g1, groupName: "Goa", byName: "Tim")
    }

    func testARemovalOrADeletionIsHandledOncePerPhoneAndStaysInTheCloud() throws {
        XCTAssertTrue(Notices.Kind.removed.keptInCloud)
        XCTAssertTrue(Notices.Kind.deleted.keptInCloud)
        XCTAssertFalse(Notices.Kind.role.keptInCloud)
        XCTAssertFalse(Notices.Kind.declined.keptInCloud)
        XCTAssertFalse(Notices.Kind.request.keptInCloud)
        let items = [notice("r1", .request), notice("x1", .removed), notice("x2", .removed),
                     notice("d1", .deleted), notice("o1", .role), notice("c1", .declined)]
        XCTAssertEqual(Notices.toHandle(items, handled: []).map(\.id), ["x1", "x2", "d1", "o1", "c1"])
        XCTAssertEqual(Notices.toHandle(items, handled: ["x1", "d1", "o1"]).map(\.id), ["x2", "o1", "c1"],
                       "only a removal or a deletion is skipped once handled; a role is dismissed in the cloud")
        let store = device("notices")
        _ = try store.setYourName("Asha")
        XCTAssertEqual(Notices.handled(store), [])
        try Notices.markHandled(store, "x1")
        try Notices.markHandled(store, "d1")
        try Notices.markHandled(store, "x1")
        XCTAssertEqual(Notices.handled(store), ["x1", "d1"])
        XCTAssertEqual(Notices.toHandle(items, handled: Notices.handled(store)).map(\.id), ["x2", "o1", "c1"])
    }

    // MARK: B4: group cursors start again once, for this build

    func testEveryGroupsCursorStartsAgainOnceAndThePersonalOneStays() throws {
        let store = device("cursors")
        _ = try store.setYourName("Asha")
        let goa = try store.createGroup(name: "Goa", settlementCurrency: "INR", people: ["Sam"])
        try CloudRecords.saveGroupsUp(store, [g2])
        try CloudRecords.saveCursor(store, goa.group.id, 1_000)
        try CloudRecords.saveCursor(store, g2, 2_000)
        try CloudRecords.saveCursor(store, nil, 3_000)
        XCTAssertTrue(try CloudRecords.resetGroupCursorsOnce(store))
        XCTAssertEqual(CloudRecords.cursor(store, goa.group.id), 0)
        XCTAssertEqual(CloudRecords.cursor(store, g2), 0)
        XCTAssertEqual(CloudRecords.cursor(store, nil), 3_000, "the personal records were never refused")
        try CloudRecords.saveCursor(store, goa.group.id, 4_000)
        XCTAssertFalse(try CloudRecords.resetGroupCursorsOnce(store), "once per install of this build")
        XCTAssertEqual(CloudRecords.cursor(store, goa.group.id), 4_000)
    }

    // MARK: B5: removals made before a group was shared go up through adoptRemovals

    @MainActor
    func testRemovalsMadeBeforeSharingGoUpOneUnitAtATime() async throws {
        let store = device("adopt")
        let me = try store.setYourName("Prithvi")
        let group = try store.createGroup(name: "Goa", settlementCurrency: "INR", people: ["Sam", "Rahul"])
        let sam = group.members.first { $0.name == "Sam" }!, rahul = group.members.first { $0.name == "Rahul" }!
        _ = try store.appendExpense(LedgerStore.ExpenseDTO(
            payer: me.id, paidMinor: 90_000, currencyCode: "INR", group: group.group.id, conversion: nil,
            shares: [me.id, sam.id, rahul.id].map { .init(id: $0, amountMinor: 30_000) }))
        try await Removal.remove(
            store: store, group: group, member: sam, you: nil, byName: "Prithvi",
            redistributions: [RedistributionInput(group: group.group.id, from: sam.id, currencyCode: "INR",
                                                  shares: [IdAmount(id: me.id, amountMinor: -30_000)])],
            shared: false)
        let named = try XCTUnwrap(try store.namedGroups().first)
        try await Removal.remove(store: store, group: named, member: named.members.first { $0.id == rahul.id }!,
                                 you: nil, byName: "Prithvi",
                                 redistributions: [RedistributionInput(group: group.group.id, from: rahul.id, currencyCode: "INR",
                                                                       shares: [IdAmount(id: me.id, amountMinor: -30_000)])],
                                 shared: false)
        let pending = CloudRecords.pending(try store.allRecords(), uploaded: try CloudRecords.uploaded(store))
        let split = CloudRecords.adoptionUnits(pending)
        XCTAssertFalse(split.direct.contains(where: CloudRecords.removalType), "no removal is written directly")
        XCTAssertEqual(split.direct.count + split.units.flatMap { $0 }.count, pending.count, "nothing is lost")
        XCTAssertEqual(split.units.count, 2, "one unit per person")
        func shape(_ unit: [RecordWire]) -> [String] {
            unit.map { r in
                if r.recordType == LedgerStore.Sync.redistributionType { return "6:" + ((try? ffiDecodeRedistribution(blob: r.blob))?.from ?? "?") }
                if case .removeMember(_, let m)? = try? ffiDecodeGroupOp(blob: r.blob) { return "3:" + m }
                return "?"
            }
        }
        XCTAssertEqual(shape(split.units[0]), ["6:" + sam.id, "3:" + sam.id])
        XCTAssertEqual(shape(split.units[1]), ["6:" + rahul.id, "3:" + rahul.id])
        let data = CloudRecords.adoptData(groupId: group.group.id, unit: split.units[0])
        XCTAssertEqual(data["groupId"] as? String, group.group.id)
        XCTAssertEqual((data["records"] as? [[String: Any]])?.compactMap { $0["id"] as? String },
                       split.units[0].map(CloudRecords.documentId))
        XCTAssertTrue(JSONSerialization.isValidJSONObject(data))
        XCTAssertEqual(CloudRecords.adoptOutcome(nil), .done)
        XCTAssertEqual(CloudRecords.adoptOutcome(CloudFunctions.Failure.refused("ALREADY_EXISTS")), .done)
        for status in ["FAILED_PRECONDITION", "PERMISSION_DENIED", "NOT_FOUND", "INVALID_ARGUMENT"] {
            XCTAssertEqual(CloudRecords.adoptOutcome(CloudFunctions.Failure.refused(status)), .refused, status)
        }
        XCTAssertEqual(CloudRecords.adoptOutcome(CloudFunctions.Failure.offline), .retry)
        XCTAssertEqual(CloudRecords.adoptOutcome(CloudFunctions.Failure.refused("UNAVAILABLE")), .retry)
        // Refused once (others joined): kept on this phone, never sent directly.
        try CloudRecords.saveAdoptRefused(store, Set(split.units[0].map(CloudRecords.documentId)))
        let after = CloudRecords.adoptionUnits(pending, refused: try CloudRecords.adoptRefused(store))
        XCTAssertEqual(after.units.map(shape), [shape(split.units[1])])
        XCTAssertFalse(after.direct.contains(where: CloudRecords.removalType))
    }

    // MARK: B6: the removal's bound, a member again, magnitudes, Settle first's sync

    private func stamp(_ seconds: Int64) -> Timestamp { Timestamp(seconds: seconds, nanoseconds: 0) }

    func testARemovalIsBoundedByTheServersTimeAndIgnoredForAMemberAgain() {
        let former: [String: Any] = ["memberUids": ["uid-tim"], "former": [uid: ["at": stamp(100), "by": "uid-tim"]]]
        var item = notice("x1", .removed)
        item.atStamp = stamp(105)
        item.removedStamp = stamp(100)
        XCTAssertEqual(Notices.removalBound(item, uid: uid, group: .found(former)), .upTo(stamp(100)), "the notice's own stamp")
        item.removedStamp = nil
        XCTAssertEqual(Notices.removalBound(item, uid: uid, group: .found(former)), .upTo(stamp(100)),
                       "a notice written after the removal is past it: the group's own time")
        item.atStamp = stamp(99)
        XCTAssertEqual(Notices.removalBound(item, uid: uid, group: .found(former)), .upTo(stamp(99)))
        let again: [String: Any] = ["memberUids": ["uid-tim", uid], "former": [:] as [String: Any]]
        item.removedStamp = stamp(100)
        XCTAssertEqual(Notices.removalBound(item, uid: uid, group: .found(again)), .memberAgain)
        XCTAssertEqual(Notices.removalBound(item, uid: uid, group: .missing), .gone)
        XCTAssertEqual(Notices.removalBound(item, uid: uid, group: .refused), .gone)
        item.removedStamp = nil
        item.atStamp = nil
        XCTAssertEqual(Notices.removalBound(item, uid: uid, group: .found(former)), .upTo(stamp(100)))
    }

    /// D-210 M1: a failed read of the group is "refused" (so the removal
    /// goes on as gone) only for permission-denied and not-found; every
    /// other code is transient, and the notice waits. The same table is
    /// pinned on Android (FinalFixesTest).
    func testOnlyPermissionDeniedAndNotFoundMakeAGroupReadRefused() {
        let table: [(Int, String, Bool)] = [
            (1, "cancelled", false), (2, "unknown", false), (3, "invalid-argument", false),
            (4, "deadline-exceeded", false), (5, "not-found", true), (6, "already-exists", false),
            (7, "permission-denied", true), (8, "resource-exhausted", false), (9, "failed-precondition", false),
            (10, "aborted", false), (11, "out-of-range", false), (12, "unimplemented", false),
            (13, "internal", false), (14, "unavailable", false), (15, "data-loss", false),
            (16, "unauthenticated", false),
        ]
        for (raw, name, refused) in table {
            let code = FirestoreErrorCode.Code(rawValue: raw)
            XCTAssertNotNil(code, name)
            switch Notices.groupRead(forFirestoreCode: code) {
            case .refused?: XCTAssertTrue(refused, "\(name) is transient: the notice waits")
            case nil: XCTAssertFalse(refused, "\(name) is the server's own answer")
            default: XCTFail("\(name): neither refused nor transient")
            }
        }
        XCTAssertNil(Notices.groupRead(forFirestoreCode: nil), "a code nobody knows says nothing about the group")
    }

    func testAMagnitudeNeverTraps() {
        XCTAssertEqual(RedistributeModel.magnitude(Int64.min), Int64.max)
        XCTAssertEqual(RedistributeModel.magnitude(-5), 5)
        XCTAssertEqual(RedistributeModel.magnitude(7), 7)
        XCTAssertEqual(RedistributeModel.magnitude(0), 0)
    }

    @MainActor
    func testSettleFirstRemovesEvenWhenThePaymentsDidNotGoUpAndSaysSo() async throws {
        var progress = SettleFirstProgress(total: 1)
        var removed = 0
        try await SettleFirstProgress.run(&progress, append: { _ in }, sync: { false }, remove: { removed += 1 })
        XCTAssertEqual(removed, 1, "the removal still goes ahead")
        XCTAssertEqual(progress.uploaded, false, "and the progress says the payments are still waiting")
        var ok = SettleFirstProgress(total: 1)
        try await SettleFirstProgress.run(&ok, append: { _ in }, sync: { true }, remove: {})
        XCTAssertEqual(ok.uploaded, true)
    }
}

private extension RecordWire {
    func with(counter: UInt32) -> RecordWire {
        var copy = self
        copy.hlcCounter = counter
        return copy
    }
}

// D-225 Task 4: source guards over the app's files (no new test file; each file keeps its own helpers).
fileprivate func s2Source(_ file: String) throws -> String {
    let url = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
        .appendingPathComponent("Skipti/App/\(file)")
    return try String(contentsOf: url, encoding: .utf8)
}

/// The text between the first `from` and the next `to` after it; a missing anchor fails the test, never crashes.
fileprivate func s2Slice(_ text: String, from: String, to: String) throws -> String {
    let after = try XCTUnwrap(text.components(separatedBy: from).dropFirst().first, "anchor \(from)")
    return try XCTUnwrap(after.components(separatedBy: to).first, "anchor \(to)")
}

fileprivate func s2Count(_ text: String, _ needle: String) -> Int { text.components(separatedBy: needle).count - 1 }

/// Each needle found, in this order.
fileprivate func s2InOrder(_ text: String, _ needles: [String], file: StaticString = #filePath, line: UInt = #line) {
    var from = text.startIndex
    for needle in needles {
        guard let at = text.range(of: needle, range: from..<text.endIndex) else {
            XCTFail("not found in order: \(needle)", file: file, line: line)
            return
        }
        from = at.upperBound
    }
}

// MARK: - D-252: a deleted group ends on every phone (decision 218)

/// D-252: the deleting account's other phones, a group that left the account's listing, and a write into a read-only
/// copy. Android's twin is GroupEndTest.
final class GroupEndTests: XCTestCase {
    private var dir: URL!
    private let g = String(repeating: "c", count: 32)
    private let at = Date(timeIntervalSince1970: 1_800_000_000)

    override func setUpWithError() throws {
        dir = FileManager.default.temporaryDirectory.appendingPathComponent("groupend-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: dir)
    }

    private func device(_ name: String) -> LedgerStore {
        LedgerStore(path: dir.appendingPathComponent("\(name).db").path)
    }

    private func appSource(_ file: String) throws -> String {
        let url = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("Skipti/App/\(file)")
        return try String(contentsOf: url, encoding: .utf8)
    }

    private func notice(byUid: String?, id: String = "d1") -> Notices.Item {
        Notices.Item(id: id, kind: .deleted, groupId: g, groupName: "Goa", byName: "Prithvi", byUid: byUid, at: at)
    }

    // MARK: The copy table (Android's GroupEndCopy reads these lines)

    func testTheCopyTableIsTheTwinsWords() {
        XCTAssertEqual(GroupEndCopy.noLongerShared, "This group is no longer shared with you.")
        XCTAssertEqual(GroupEndCopy.youDeleted("Goa"), "You deleted Goa.")
        XCTAssertEqual(GroupEndCopy.refusedVanished, "This group is no longer shared with you. Nothing new can be added.")
        XCTAssertEqual(GroupEndCopy.refusedYouDeleted, "You deleted this group. Nothing new can be added.")
        XCTAssertEqual(GroupEndCopy.refusedDeleted, "This group was deleted. Nothing new can be added.")
        XCTAssertEqual(GroupEndCopy.refusedRemoved, "You were removed from this group. Nothing new can be added.")
        XCTAssertEqual(LedgerStore.ReadOnly.vanishedKind, "vanished", "stored in the vault: never renamed")
    }

    /// The banner: a vanished group says it neutrally; a group this account deleted says "You deleted {group}." on every
    /// one of its phones; someone else's delete and a removal read as they did (D-210).
    func testABannerSaysHowTheGroupEnded() {
        let day = "October 13, 2026"
        let vanished = GroupGone.mark(at: at)
        XCTAssertEqual(vanished, LedgerStore.ReadOnly(kind: "vanished", by: "", at: at))
        XCTAssertEqual(ReadOnlyBanner.title(mark: vanished, date: day, group: "Goa"), "This group is no longer shared with you.")
        XCTAssertEqual(ReadOnlyBanner.body(mark: vanished, group: "Goa"), "This is your copy. Nothing new can be added.")
        XCTAssertTrue(ReadOnlyActions.offersForget(vanished), "export and remove from this phone stay")
        let mine = LedgerStore.ReadOnly(kind: "deleted", by: "Prithvi", at: at, byYou: true)
        XCTAssertEqual(ReadOnlyBanner.title(mark: mine, date: day, group: "Goa"), "You deleted Goa.")
        XCTAssertEqual(ReadOnlyBanner.title(mark: LedgerStore.ReadOnly(kind: "deleted", by: "Asha", at: at), date: day, group: "Goa"),
                       "Asha deleted this group on October 13, 2026")
        XCTAssertEqual(ReadOnlyBanner.title(mark: LedgerStore.ReadOnly(kind: "removed", by: "Asha", at: at), date: day, group: "Goa"),
                       "Asha removed you on October 13, 2026")
        XCTAssertEqual(ReadOnlyBanner.title(mark: .placeLost, date: day, group: "Goa"), RolesCopy.placeLostBanner)
    }

    // MARK: 1. The deleting account's own notice

    func testADeletionNoticeNamesItsDeleter() {
        let base: [String: Any] = ["kind": "deleted", "groupId": g, "groupName": "Goa", "byName": "Prithvi"]
        XCTAssertNil(Notices.item(id: "d1", data: base)?.byUid, "a notice written before D-252 names nobody")
        XCTAssertEqual(Notices.item(id: "d1", data: base.merging(["byUid": "ow"]) { $1 })?.byUid, "ow")
        XCTAssertNil(Notices.item(id: "d1", data: base.merging(["byUid": 7]) { $1 }), "not text: refused")
        XCTAssertNil(Notices.item(id: "d1", data: base.merging(["byUid": ""]) { $1 }), "empty: refused")
    }

    /// The deleting account's other phone reads "You deleted"; another member's reads the deleter's name; the deleting
    /// phone (already marked) and a phone that handled it once keep their mark; a vanished copy says more.
    func testADeletionNoticeMarksOnceAndSaysYouDeletedOnTheDeletersOtherPhones() {
        let fresh = Notices.deletedMark(notice(byUid: "ow"), uid: "ow", existing: nil)
        XCTAssertEqual(fresh, LedgerStore.ReadOnly(kind: "deleted", by: "Prithvi", at: at, byYou: true))
        XCTAssertEqual(Notices.deletedMark(notice(byUid: "ow"), uid: "me", existing: nil),
                       LedgerStore.ReadOnly(kind: "deleted", by: "Prithvi", at: at), "another member: the deleter's name")
        XCTAssertEqual(Notices.deletedMark(notice(byUid: nil), uid: "ow", existing: nil)?.byYou, nil, "an old notice: never yours")
        XCTAssertEqual(Notices.deletedMark(notice(byUid: "ow"), uid: nil, existing: nil)?.byYou, nil, "signed out: never yours")
        XCTAssertEqual(Notices.deletedMark(notice(byUid: ""), uid: "", existing: nil)?.byYou, nil, "an empty id matches nothing")
        // Idempotent: the deleting phone's own mark (its date, "You deleted") stands; so does a mark a first pass wrote.
        let own = LedgerStore.ReadOnly(kind: "deleted", by: "Prithvi", at: at.addingTimeInterval(-5), byYou: true)
        XCTAssertNil(Notices.deletedMark(notice(byUid: "ow"), uid: "ow", existing: own))
        XCTAssertNil(Notices.deletedMark(notice(byUid: "ow"), uid: "me", existing: LedgerStore.ReadOnly(kind: "deleted", by: "Prithvi", at: at)))
        // A vanished copy (or a lost place) is told what happened.
        XCTAssertEqual(Notices.deletedMark(notice(byUid: "ow"), uid: "ow", existing: GroupGone.mark(at: at)), fresh)
        XCTAssertEqual(Notices.deletedMark(notice(byUid: "ow"), uid: "ow", existing: .placeLost), fresh)
    }

    // MARK: 2. A group that left the listing

    func testTheOneReadDecides() {
        XCTAssertEqual(GroupGone.decide(.refused), .readOnly)
        XCTAssertEqual(GroupGone.decide(.notFound), .readOnly)
        XCTAssertEqual(GroupGone.decide(.networkError), .retry, "no answer changes nothing")
        XCTAssertEqual(GroupGone.decide(.exists), .keep)
        XCTAssertEqual(GroupGone.decide(.former), .readOnly, "removed, the notice lost: the copy ends")
        XCTAssertEqual(GroupGone.decide(.notMember), .readOnly)
        XCTAssertEqual(GroupGone.read(.missing, uid: "me"), .notFound)
        XCTAssertEqual(GroupGone.read(.refused, uid: "me"), .refused)
        XCTAssertEqual(GroupGone.read(nil, uid: "me"), .networkError)
        // The Firestore codes as the removal read maps them (pinned in RolesTests): only these two are the server's word.
        XCTAssertEqual(GroupGone.read(Notices.groupRead(forFirestoreCode: .permissionDenied), uid: "me"), .refused)
        XCTAssertEqual(GroupGone.read(Notices.groupRead(forFirestoreCode: .notFound), uid: "me"), .refused)
        for code: FirestoreErrorCode.Code in [.unavailable, .deadlineExceeded, .unauthenticated, .cancelled, .internal] {
            XCTAssertEqual(GroupGone.decide(GroupGone.read(Notices.groupRead(forFirestoreCode: code), uid: "me")), .retry, "\(code)")
        }
    }

    /// D-252 fix wave (M2): a document that is there is "still shared" only for a member. The rules let a removed
    /// account read its group (`former`, firestore.rules), so a removal whose notice was lost reads `found`: before
    /// this, that kept the copy writable for good.
    func testAGroupThatIsThereIsSharedOnlyWithAMember() {
        let removedAt = Timestamp(seconds: 1_760_000_000, nanoseconds: 0)
        let member: [String: Any] = ["memberUids": ["ow", "me"], "name": "Goa"]
        XCTAssertEqual(GroupGone.read(.found(member), uid: "me"), .exists)
        XCTAssertEqual(GroupGone.decide(GroupGone.read(.found(member), uid: "me")), .keep)
        let removed: [String: Any] = ["memberUids": ["ow"], "former": ["me": ["at": removedAt, "by": "ow"]]]
        XCTAssertEqual(GroupGone.read(.found(removed), uid: "me"), .former)
        XCTAssertEqual(GroupGone.decide(GroupGone.read(.found(removed), uid: "me")), .readOnly, "removed, the notice lost")
        XCTAssertEqual(GroupGone.read(.found(removed), uid: "ow"), .exists, "the owner is still in it")
        XCTAssertEqual(GroupGone.read(.found(["memberUids": ["ow"]]), uid: "me"), .notMember)
        XCTAssertEqual(GroupGone.read(.found([:]), uid: "me"), .notMember, "no member list: not shared")
        XCTAssertEqual(GroupGone.read(.found(member), uid: ""), .notMember, "no account: never a member")
        XCTAssertEqual(GroupGone.read(.found(["memberUids": ["ow"], "former": ["": ["at": removedAt]]]), uid: ""), .notMember)
        // Rejoined: `former` cleared, a member again (acceptInvite): shared.
        XCTAssertEqual(GroupGone.read(.found(["memberUids": ["ow", "me"], "former": [String: Any]()]), uid: "me"), .exists)
    }

    /// D-252 fix wave (M3): a listing like the last one checked is passed by (no vault read, no main-thread work); a
    /// new group, a lost one or a confirmation is a change; nil (a new session, a failed read) always checks.
    func testAListingLikeTheLastCheckedIsPassedBy() {
        let last = ["a": true, "b": false]
        XCTAssertTrue(GroupGone.unchanged(["a": true, "b": false], last: last))
        XCTAssertFalse(GroupGone.unchanged(["a": true, "b": true], last: last), "a confirmation can lift a mark")
        XCTAssertFalse(GroupGone.unchanged(["a": true], last: last), "a group left the listing")
        XCTAssertFalse(GroupGone.unchanged(["a": true, "b": false, "c": true], last: last), "a group joined it")
        XCTAssertFalse(GroupGone.unchanged(last, last: nil), "the session's first listing, or one that must run again")
        XCTAssertFalse(GroupGone.unchanged([:], last: nil), "even an empty first listing is checked (the sweep)")
        XCTAssertTrue(GroupGone.unchanged([:], last: [:]))
    }

    /// Which held groups are asked about: the session's first listing sweeps every one in the cloud from this phone that
    /// the listing lacks (0.5.0's victims); never a group only on this phone (asking would end it wrongly), a listed
    /// one, a copy, one a notice explains, or one asked already this session.
    func testWhichHeldGroupsAreAskedAbout() {
        let a = "a" + String(repeating: "0", count: 31), b = "b" + String(repeating: "0", count: 31)
        let c = "c" + String(repeating: "0", count: 31), d = "d" + String(repeating: "0", count: 31)
        let e = "e" + String(repeating: "0", count: 31), f = "f" + String(repeating: "0", count: 31)
        let held: Set<String> = [a, b, c, d, e, f]
        let up: Set<String> = [a, b, c, d, e]  // f never left this phone
        XCTAssertEqual(GroupGone.toCheck(held: held, groupsUp: up, listed: [], copies: [], noticed: [], asked: []), [a, b, c, d, e],
                       "the sweep: every held group in the cloud that the listing lacks")
        XCTAssertEqual(GroupGone.toCheck(held: held, groupsUp: up, listed: [a], copies: [b], noticed: [c], asked: [d]), [e])
        XCTAssertEqual(GroupGone.toCheck(held: [f], groupsUp: [], listed: [], copies: [], noticed: [], asked: []), [],
                       "a phone-only group is never asked about")
        XCTAssertEqual(GroupGone.toCheck(held: [], groupsUp: up, listed: [], copies: [], noticed: [], asked: []), [],
                       "a group no longer held (removed from this phone) is not asked about")
    }

    func testAVanishedMarkNeverMakesARemovalOrADeletionVaguer() {
        XCTAssertTrue(GroupGone.marks(over: nil))
        XCTAssertTrue(GroupGone.marks(over: .placeLost))
        XCTAssertFalse(GroupGone.marks(over: LedgerStore.ReadOnly(kind: "removed", by: "Asha", at: at)))
        XCTAssertFalse(GroupGone.marks(over: LedgerStore.ReadOnly(kind: "deleted", by: "Asha", at: at)))
        XCTAssertFalse(GroupGone.marks(over: GroupGone.mark(at: at)), "already vanished")
        // Listed again (confirmed by the server): only a vanished mark lifts.
        XCTAssertTrue(GroupGone.lifts(GroupGone.mark(at: at), listedConfirmed: true))
        XCTAssertFalse(GroupGone.lifts(GroupGone.mark(at: at), listedConfirmed: false), "a pending write is not the server")
        XCTAssertFalse(GroupGone.lifts(LedgerStore.ReadOnly(kind: "removed", by: "Asha", at: at), listedConfirmed: true))
        XCTAssertFalse(GroupGone.lifts(LedgerStore.ReadOnly(kind: "deleted", by: "Asha", at: at), listedConfirmed: true))
        XCTAssertFalse(GroupGone.lifts(nil, listedConfirmed: true))
    }

    // MARK: 3. A write into a read-only copy is refused before it is saved

    func testAWriteToAReadOnlyCopyIsRefusedWithWordsBeforeAnythingIsWritten() throws {
        let store = device("copy")
        let me = try store.setYourName("Tim")
        let goa = try store.createGroup(name: "Goa", settlementCurrency: "INR", people: ["Rahul"])
        let lisbon = try store.createGroup(name: "Lisbon", settlementCurrency: "INR", people: ["Rahul"])
        let rahul = try XCTUnwrap(goa.members.first { $0.name == "Rahul" }).id
        func dinner(_ group: NamedGroupWire) throws -> LedgerStore.ExpenseDTO {
            let r = try XCTUnwrap(group.members.first { $0.name == "Rahul" }).id
            return LedgerStore.ExpenseDTO(payer: me.id, paidMinor: 1_000, currencyCode: "INR", group: group.group.id, conversion: nil,
                                          shares: [.init(id: me.id, amountMinor: 500), .init(id: r, amountMinor: 500)],
                                          note: "Dinner", timestampMs: 5_000, category: nil, addedBy: me.id)
        }
        _ = try store.appendExpense(try dinner(goa))
        let target = try XCTUnwrap(try store.projectedExpenses().expenses.first).id
        try store.markReadOnly(goa.group.id, GroupGone.mark(at: at))
        let records = try store.allRecords().count
        let kv = try store.kvGet(key: LedgerStore.key)
        let settled = try store.kvGet(key: LedgerStore.settlementsKey)
        func refused(_ error: Error, _ what: String, _ words: String = GroupEndCopy.refusedVanished) {
            XCTAssertEqual(error as? LedgerStore.Placing.ReadOnlyCopy, LedgerStore.Placing.ReadOnlyCopy(mark: GroupGone.mark(at: at)), what)
            XCTAssertEqual("\(error)", words, "\(what): the words a failure line prints")
        }
        XCTAssertThrowsError(try store.appendExpense(try dinner(goa))) { refused($0, "an expense") }
        XCTAssertThrowsError(try store.appendExpenses([try dinner(lisbon), try dinner(goa)])) { refused($0, "an import") }
        let pay = LedgerStore.SettlementDTO(from: me.id, to: rahul, amountMinor: 100, currencyCode: "INR", group: goa.group.id)
        XCTAssertThrowsError(try store.checkSettlement(pay)) { refused($0, "a settle's hold") }
        XCTAssertThrowsError(try store.appendSettlement(pay)) { refused($0, "a payment") }
        let edit = ExpenseRevisionIn(target: target, seen: [], editor: me.id, money: nil, note: "x", category: nil, deleted: nil)
        XCTAssertThrowsError(try store.reviseExpense(edit)) { refused($0, "an edit") }
        XCTAssertThrowsError(try LedgerStore.Sync.appendGroupOps(
            store, [.renameMember(groupId: goa.group.id, memberId: rahul, name: "R")], placing: try LedgerStore.Placing.load(store))) {
            refused($0, "an op")
        }
        XCTAssertThrowsError(try LedgerStore.Placing.load(store).redistribution(RedistributionInput(
            group: goa.group.id, from: rahul, currencyCode: "INR", shares: [IdAmount(id: me.id, amountMinor: 1)]))) {
            refused($0, "a redistribution")
        }
        XCTAssertEqual(try store.allRecords().count, records, "nothing written: no record waits to be parked")
        XCTAssertEqual(try store.kvGet(key: LedgerStore.key), kv)
        XCTAssertEqual(try store.kvGet(key: LedgerStore.settlementsKey), settled)
        // Negatives: another group, Just me and the backfills' `.none` write as before.
        _ = try store.appendExpense(try dinner(lisbon))
        XCTAssertEqual(try store.allRecords().count, records + 1)
        XCTAssertNil(try LedgerStore.Placing.load(store).copyRefusal(group: lisbon.group.id))
        XCTAssertNil(try LedgerStore.Placing.load(store).copyRefusal(group: nil))
        XCTAssertNil(LedgerStore.Placing.none.copyRefusal(group: goa.group.id), ".none refuses nothing")
        // Each mark's words.
        try store.markReadOnly(goa.group.id, LedgerStore.ReadOnly(kind: "deleted", by: "Tim", at: at, byYou: true))
        XCTAssertThrowsError(try store.appendExpense(try dinner(goa))) { XCTAssertEqual("\($0)", GroupEndCopy.refusedYouDeleted) }
        try store.markReadOnly(goa.group.id, LedgerStore.ReadOnly(kind: "deleted", by: "Asha", at: at))
        XCTAssertThrowsError(try store.appendExpense(try dinner(goa))) { XCTAssertEqual($0.localizedDescription, GroupEndCopy.refusedDeleted) }
        try store.markReadOnly(goa.group.id, LedgerStore.ReadOnly(kind: "removed", by: "Asha", at: at))
        XCTAssertThrowsError(try store.appendExpense(try dinner(goa))) { XCTAssertEqual("\($0)", GroupEndCopy.refusedRemoved) }
        // Shared again (an invite accepted clears the mark): it takes records again.
        try store.clearReadOnly(goa.group.id)
        _ = try store.appendExpense(try dinner(goa))
        XCTAssertEqual(try store.allRecords().count, records + 2)
    }

    /// A name change passes a read-only copy by (it keeps its names), and still renames every group still shared: the
    /// twin of Android's `a_new_name_passes_a_read_only_copy_by` (D-252 stage 3; before it, the copy's Placing refused
    /// the rename and the whole name change failed).
    func testANewNamePassesAReadOnlyCopyBy() throws {
        let store = device("rename")
        let me = try store.setYourName("Tim")
        let goa = try store.createGroup(name: "Goa", settlementCurrency: "INR", people: ["Rahul"])
        let lisbon = try store.createGroup(name: "Lisbon", settlementCurrency: "INR", people: ["Rahul"])
        try store.markReadOnly(goa.group.id, LedgerStore.ReadOnly(kind: "deleted", by: "Tim", at: at, byYou: true))
        try store.setYourName("Timothy")
        func nameIn(_ group: String) throws -> String? {
            try XCTUnwrap(try store.namedGroups().first { $0.group.id == group }).members.first { $0.id == me.id }?.name
        }
        XCTAssertEqual(try nameIn(lisbon.group.id), "Timothy")
        XCTAssertEqual(try nameIn(goa.group.id), "Tim", "the copy keeps its names")
        let renames = try store.allRecords().filter { $0.recordType == LedgerStore.Sync.groupOpType }.compactMap { record -> String? in
            guard case .renameMember(let groupId, _, _)? = try? ffiDecodeGroupOp(blob: record.blob) else { return nil }
            return groupId
        }
        XCTAssertEqual(renames, [lisbon.group.id], "one rename, in the group still shared")
    }

    // MARK: The sync layer's wiring (pure rules above; these pin where they run)

    func testTheSyncLayerAsksOnlyFromTheServerAndNeverDeletesAnything() throws {
        let cloud = try appSource("CloudSync.swift")
        XCTAssertTrue(cloud.contains("let fromCache = snapshot.metadata.isFromCache"))
        XCTAssertTrue(cloud.contains("if !fromCache { checkVanished(listed: groups.map { ($0.0, $0.2) }, uid: uid, store: store) }"),
                      "a listing from the cache asks nothing")
        let vanished = try XCTUnwrap(cloud.components(separatedBy: "private func checkVanished(").dropFirst().first?
            .components(separatedBy: "\n    }\n").first)
        let check = vanished + (try XCTUnwrap(cloud.components(separatedBy: "private func askVanished(").dropFirst().first?
            .components(separatedBy: "\n    }\n").first))
        XCTAssertTrue(check.contains("GroupGone.decide(GroupGone.read(await Self.groupRead(groupId), uid: uid))"),
                      "the one read, the removal's own, for this account (M2)")
        // D-252 fix wave (M3): a listing like the last checked is passed by before anything is read, and the vault is
        // read off the main thread: nothing before the detached read touches the store.
        let head = try XCTUnwrap(vanished.components(separatedBy: "Task { @MainActor in").first)
        XCTAssertTrue(head.contains("guard !GroupGone.unchanged(listing, last: vanishListing) else { return }\n        vanishListing = listing"))
        XCTAssertFalse(head.contains("store."), "no vault work on the main thread for a snapshot")
        XCTAssertTrue(vanished.contains("let seen = await Task.detached(priority: .utility) { GroupGone.see(store) }.value"))
        XCTAssertEqual(vanished.components(separatedBy: "sync.vanishListing = nil").count - 1, 2, "a failed read or lift checks again")
        XCTAssertTrue(check.contains("sync.vanishAsked.remove(groupId)\n                sync.vanishListing = nil\n                return"),
                      "no answer: the next listing asks again")
        XCTAssertTrue(check.contains("guard GroupGone.marks(over: try store.readOnlyChecked()[groupId]) else { return }"))
        XCTAssertTrue(check.contains("try store.markReadOnly(groupId, GroupGone.mark(at: Date()))"))
        XCTAssertTrue(check.contains("guard sync.groupsListener != nil, Account.shared.uid == uid else { return }"), "never after sign-out")
        XCTAssertTrue(check.contains("guard !sync.listedNow.contains(groupId) else { return }"), "listed again meanwhile: nothing")
        for forbidden in ["forgetGroup", "forgetInVault", "kvSet(", "removeItem"] {
            XCTAssertFalse(check.contains(forbidden), "the check never deletes local data: \(forbidden)")
        }
        // D-235/D-244 style count: the group read is the removal's (`groupRead`); D-252 adds no new server read. D-254
        // fix wave (M1) adds one: `serverApproval`, the account's approval read before a permission refusal is parked.
        // D-258 (G-26) adds one: `serverPhone`, this phone's own entry, read after it, before parking too.
        XCTAssertEqual(cloud.components(separatedBy: "getDocument(source: .server)").count - 1, 5,
                       "ensureGroup's existence read, the record existence read, groupRead (removal and D-252 share it), serverApproval, serverPhone")
        XCTAssertTrue(cloud.contains("vanishAsked = []\n        listedNow = []\n        vanishListing = nil"), "a new session sweeps again")
        let handle = try XCTUnwrap(cloud.components(separatedBy: "                case .deleted:").dropFirst().first?
            .components(separatedBy: "                case .role, .declined:").first)
        XCTAssertTrue(handle.contains("if let mark = Notices.deletedMark(item, uid: Account.shared.uid, existing: try store.readOnlyChecked()[item.groupId]) {"))
    }
}
