import UIKit
import XCTest
@testable import Skipti

/// D-153: groups and "you" are records on iOS (core D-152), so they sync and
/// a restored device gets them back. The founder's decision (2026-09-24): one
/// "you" per account; a new device adopts it. Two vaults stand in for two
/// devices; "sync" is applying one's records to the other, exactly what a
/// pull does. Android's twin is GroupRecordsTest.
final class GroupRecordsTests: XCTestCase {
    private var dir: URL!

    override func setUpWithError() throws {
        dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("group-records-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: dir)
    }

    private func device(_ name: String) -> LedgerStore {
        LedgerStore(path: dir.appendingPathComponent("\(name).db").path)
    }

    /// A pull: every record of `from` applied to `to`, then the rebuild.
    private func sync(_ from: LedgerStore, into to: LedgerStore) throws {
        for record in try from.allRecords() {
            _ = try to.applyRecord(record)
        }
        try to.rebuildProjection()
    }

    private func groupOps(_ store: LedgerStore) throws -> [(RecordWire, GroupOpIn)] {
        try store.allRecords()
            .filter { $0.recordType == LedgerStore.Sync.groupOpType }
            .map { ($0, try ffiDecodeGroupOp(blob: $0.blob)) }
    }

    /// HLCs are milliseconds: a short pause makes "later" unambiguous.
    private func later() { Thread.sleep(forTimeInterval: 0.02) }

    // MARK: - Writes

    func testNamingYouAndCreatingAGroupWriteRecordsInTheirScopes() throws {
        let a = device("a")
        let me = try a.setYourName("Tim")
        let group = try a.createGroup(name: "Goa", settlementCurrency: "INR", people: ["Rahul"])
        let ops = try groupOps(a)
        let yous = ops.filter { if case .you = $0.1 { return true }; return false }
        XCTAssertEqual(yous.count, 2, "created, then named")
        XCTAssertTrue(yous.allSatisfy { $0.0.groupId == nil }, "you is PERSONAL scope, never a group's")
        XCTAssertEqual(yous.last?.1, .you(id: me.id, name: "Tim"))
        let creates = ops.filter { if case .create = $0.1 { return true }; return false }
        XCTAssertEqual(creates.count, 1)
        XCTAssertEqual(creates[0].0.groupId, group.group.id, "a group operation takes its group's scope")
        XCTAssertEqual(
            try a.loadGroups().map(\.name), ["Goa"], "the kv groups are rebuilt from the records"
        )
    }

    func testAddingAndRenamingWriteOnlyTheirOperations() throws {
        let a = device("a")
        try a.setYourName("Tim")
        let group = try a.createGroup(name: "Goa", settlementCurrency: "INR", people: ["Rahul"])
        let before = try groupOps(a).count
        let added = try a.addMember(groupID: group.group.id, name: "Priya")
        let priya = try XCTUnwrap(added.members.last)
        try a.renameMember(groupID: group.group.id, memberID: priya.id, name: "Priya S")
        let new = try groupOps(a).dropFirst(before).map(\.1)
        XCTAssertEqual(new, [
            .addMember(groupId: group.group.id, member: GroupOpMemberIn(id: priya.id, name: "Priya")),
            .renameMember(groupId: group.group.id, memberId: priya.id, name: "Priya S"),
        ])
    }

    // MARK: - Restore and adoption

    /// The D-151 gap closed: a device that has nothing gets the groups and
    /// "you" back, byte for byte.
    func testARestoredDeviceGetsItsGroupsAndYouBack() throws {
        let a = device("a")
        let me = try a.setYourName("Tim")
        let group = try a.createGroup(name: "Goa", settlementCurrency: "INR", people: ["Rahul", "Priya"])
        let restored = device("restored")
        try sync(a, into: restored)
        XCTAssertEqual(
            try restored.kvGet(key: LedgerStore.groupsKey), try a.kvGet(key: LedgerStore.groupsKey)
        )
        XCTAssertEqual(try restored.loadSelf(), LedgerStore.SelfDTO(id: me.id, name: "Tim"))
        let named = try XCTUnwrap(try restored.namedGroups().first)
        XCTAssertEqual(named, group)
        XCTAssertEqual(LedgerStore.label(for: me.id, in: named), "Tim (You)")
    }

    /// The common case: a second device already made its own "you" at first
    /// launch; on its first pull it adopts the account's (the earliest), and
    /// keeps its own as an alias for the merge.
    func testASecondDeviceAdoptsTheAccountsYou() throws {
        let a = device("a")
        let me = try a.setYourName("Tim")
        _ = try a.createGroup(name: "Goa", settlementCurrency: "INR", people: ["Rahul"])
        later()
        let b = device("b")
        let own = try b.ensureSelf()
        XCTAssertNotEqual(own.id, me.id)
        try sync(a, into: b)
        let adopted = try XCTUnwrap(try b.loadSelf())
        XCTAssertEqual(adopted.id, me.id)
        XCTAssertEqual(adopted.name, "Tim")
        XCTAssertEqual(adopted.aliases, [own.id])
        let made = try b.createGroup(name: "Paris", settlementCurrency: "EUR", people: [])
        XCTAssertEqual(made.selfId, me.id, "new groups are made under the account's you")
    }

    /// A group made on B under B's own "you" still loads after B adopts A's,
    /// and since D-154 it holds the account's one "you" (core merges the
    /// member), shown as "(You)". Negative: before either, `ffiNamedGroup`
    /// refused the group and the whole group list failed to load.
    func testAGroupMadeUnderAnotherYouStillLoadsAsYours() throws {
        let a = device("a")
        try a.setYourName("Tim")
        _ = try a.createGroup(name: "Goa", settlementCurrency: "INR", people: ["Rahul"])
        later()
        let b = device("b")
        let ownB = try b.setYourName("Tim")
        _ = try b.createGroup(name: "Paris", settlementCurrency: "EUR", people: ["Anna"])
        try sync(a, into: b)
        let groups = try b.namedGroups()
        XCTAssertEqual(groups.map(\.group.name), ["Goa", "Paris"])
        let paris = try XCTUnwrap(groups.last)
        let you = try XCTUnwrap(try b.loadSelf()).id
        XCTAssertNotEqual(you, ownB.id)
        XCTAssertEqual(paris.selfId, you, "D-154: the account's you, merged in")
        XCTAssertFalse(paris.group.members.contains(ownB.id))
        XCTAssertEqual(LedgerStore.label(for: you, in: paris), "Tim (You)")
    }

    /// D-154, the founder's merge: two "you"s are ONE person in what people
    /// see. B paid in its own group under its own "you" and settled with A's
    /// "you" (a payment to itself); after they meet, both devices show the
    /// expense paid by the account's "you" with the shares added, and the
    /// settlement between the two ids is gone. The records keep both ids.
    func testTwoYousAreOnePersonInTheLedger() throws {
        let a = device("a")
        let meA = try a.setYourName("Tim")
        _ = try a.createGroup(name: "Goa", settlementCurrency: "INR", people: ["Rahul"])
        later()
        let b = device("b")
        let ownB = try b.setYourName("Tim")
        let paris = try b.createGroup(name: "Paris", settlementCurrency: "EUR", people: ["Anna"])
        let anna = try XCTUnwrap(paris.members.last).id
        _ = try b.appendExpense(LedgerStore.ExpenseDTO(
            payer: ownB.id, paidMinor: 4000, currencyCode: "EUR", group: paris.group.id,
            conversion: nil,
            shares: [.init(id: ownB.id, amountMinor: 2000), .init(id: anna, amountMinor: 2000)],
            note: "Dinner", timestampMs: 1_000, category: nil, addedBy: ownB.id
        ))
        _ = try b.appendSettlement(LedgerStore.SettlementDTO(
            from: ownB.id, to: meA.id, amountMinor: 500, currencyCode: "EUR", group: nil
        ))
        try sync(a, into: b)
        try sync(b, into: a)
        for device in [a, b] {
            XCTAssertEqual(try device.loadExpenses().count, 1, "one expense, never a re-minted copy")
            let expense = try XCTUnwrap(try device.loadExpenses().first)
            XCTAssertEqual(expense.payer, meA.id)
            XCTAssertEqual(expense.shares.map(\.id), [meA.id, anna])
            XCTAssertEqual(expense.shares.map(\.amountMinor).reduce(0, +), 4000)
            XCTAssertEqual(try device.projectedExpenses().expenses.first?.addedBy, meA.id)
            XCTAssertEqual(try device.loadSettlements(), [], "a payment to yourself is not a payment")
        }
        XCTAssertEqual(
            Set(try b.allRecords().compactMap { r -> String? in
                guard r.recordType == LedgerStore.Sync.expenseType else { return nil }
                return try JSONDecoder().decode(LedgerStore.ExpenseDTO.self, from: r.blob).payer
            }),
            [ownB.id],
            "the record keeps what B wrote"
        )
    }

    // MARK: - Backfill and the union rule

    /// A vault from before D-153 kept groups and "you" only in kv. They get
    /// records (once), and no rebuild ever erases them.
    func testKvOnlyGroupsAreBackfilledOnceAndNeverErased() throws {
        let a = device("a")
        let me = LedgerStore.SelfDTO(id: String(repeating: "0", count: 31) + "1", name: "Tim")
        let good = LedgerStore.GroupDTO(
            id: String(repeating: "0", count: 31) + "9", name: "Goa", settlementCurrency: "INR",
            members: [me.id, String(repeating: "0", count: 31) + "2"],
            memberNames: [LedgerStore.MemberDTO(id: me.id, name: "Tim")]
        )
        // Core refuses a group name with a leading space: it cannot become a
        // record, so only the union rule keeps it.
        let damaged = LedgerStore.GroupDTO(
            id: String(repeating: "0", count: 31) + "8", name: " Old", settlementCurrency: "INR",
            members: [me.id]
        )
        try a.kvSet(key: LedgerStore.selfKey, value: try JSONEncoder().encode(me))
        try a.kvSet(key: LedgerStore.groupsKey, value: try JSONEncoder().encode([good, damaged]))

        try a.rebuildProjection()
        let ops = try groupOps(a).map(\.1)
        XCTAssertEqual(ops.count, 2, "one create for the good group, one you")
        XCTAssertTrue(ops.contains(.you(id: me.id, name: "Tim")))
        XCTAssertEqual(Set(try a.loadGroups().map(\.id)), [good.id, damaged.id], "nothing erased")

        try a.rebuildProjection()
        XCTAssertEqual(try groupOps(a).count, 2, "a second run mints nothing")
        XCTAssertEqual(try LedgerStore.Sync.backfillGroupRecords(a), 0)
    }

    /// A rebuild that cannot read the kv groups must not overwrite them.
    func testAnUnreadableGroupListIsNeverOverwritten() throws {
        let a = device("a")
        try a.kvSet(key: LedgerStore.groupsKey, value: Data("{not json".utf8))
        XCTAssertThrowsError(try a.rebuildProjection())
        XCTAssertEqual(try a.kvGet(key: LedgerStore.groupsKey), Data("{not json".utf8))
    }

    // MARK: - Convergence

    /// Two devices rename the same person offline; after both pull, both show
    /// the later rename (last writer wins), byte for byte.
    func testConcurrentRenamesConvergeOnBothDevices() throws {
        let a = device("a")
        try a.setYourName("Tim")
        let group = try a.createGroup(name: "Goa", settlementCurrency: "INR", people: ["Rahul"])
        let rahul = try XCTUnwrap(group.members.last).id
        let b = device("b")
        try sync(a, into: b)
        try a.renameMember(groupID: group.group.id, memberID: rahul, name: "Ravi")
        later()
        try b.renameMember(groupID: group.group.id, memberID: rahul, name: "Rahul K")
        try sync(a, into: b)
        try sync(b, into: a)
        XCTAssertEqual(try a.kvGet(key: LedgerStore.groupsKey), try b.kvGet(key: LedgerStore.groupsKey))
        XCTAssertEqual(try a.namedGroups().first?.members.last?.name, "Rahul K")
    }

    // MARK: - The diff, on its own

    func testTheDiffWritesOnlyWhatChanged() {
        let g = "0".repeated(31) + "9"
        func named(_ name: String, _ members: [(String, String)]) -> NamedGroupWire {
            NamedGroupWire(
                group: GroupWire(id: g, name: name, settlementCurrency: "INR", members: members.map(\.0)),
                selfId: members[0].0,
                members: members.map { MemberWire(id: $0.0, name: $0.1) }
            )
        }
        let tim = ("0".repeated(31) + "1", "Tim")
        let before = named("Goa", [tim, ("0".repeated(31) + "2", "Rahul")])
        let after = named("Goa 2026", [tim, ("0".repeated(31) + "2", "Rahul K"), ("0".repeated(31) + "3", "Priya")])
        XCTAssertEqual(LedgerStore.operations(from: [before], to: [after], created: [g]), [
            .renameGroup(groupId: g, name: "Goa 2026"),
            .renameMember(groupId: g, memberId: "0".repeated(31) + "2", name: "Rahul K"),
            .addMember(groupId: g, member: GroupOpMemberIn(id: "0".repeated(31) + "3", name: "Priya")),
        ])
        XCTAssertEqual(LedgerStore.operations(from: [after], to: [after], created: [g]), [], "no change, no op")
        // A group with no create record is written whole, so nothing is lost.
        guard case .create = LedgerStore.operations(from: [before], to: [after], created: []).first else {
            return XCTFail("an uncreated group is saved as a create")
        }
    }

    /// D-157: a claim ("this member is me") belongs to its group's log, like
    /// every group operation; only "you" is personal.
    func testAClaimTakesItsGroupsScope() throws {
        let g = "0".repeated(31) + "9"
        let claim = GroupOpIn.claim(
            groupId: g, memberId: "0".repeated(31) + "2", youId: "0".repeated(30) + "20",
            devices: [ClaimDeviceIn(deviceId: "0".repeated(30) + "30", agreementPublicHex: "ab".repeated(32))]
        )
        let record = try LedgerStore.Sync.groupOpRecord(claim, hlc: HlcStamp(millis: 1, counter: 0),
                                                        deviceId: "0".repeated(30) + "30")
        XCTAssertEqual(record.groupId, g)
        XCTAssertEqual(try ffiDecodeGroupOp(blob: record.blob), claim)
    }

    // MARK: - D-186: taking a person out of a group (twin of GroupMembersTest)

    private func idOf(_ g: NamedGroupWire, _ name: String) -> String {
        g.members.first { $0.name == name }!.id
    }

    /// You never; someone in an expense or payment never; someone in
    /// nothing, yes, and then they are gone from the people every screen
    /// offers while the records still hold them, here and on the next phone.
    func testOnlySomeoneWithNoHistoryCanBeTakenOut() throws {
        let a = device("a")
        let me = try a.setYourName("Tim")
        let goa = try a.createGroup(name: "Goa", settlementCurrency: "INR", people: ["Rahul", "Sam", "Priya"])
        let rahul = idOf(goa, "Rahul"), sam = idOf(goa, "Sam"), priya = idOf(goa, "Priya")
        _ = try a.appendExpense(LedgerStore.ExpenseDTO(
            payer: me.id, paidMinor: 2_000, currencyCode: "INR", group: goa.group.id, conversion: nil,
            shares: [.init(id: me.id, amountMinor: 1_000), .init(id: rahul, amountMinor: 1_000)]
        ))
        _ = try a.appendSettlement(LedgerStore.SettlementDTO(
            from: priya, to: me.id, amountMinor: 100, currencyCode: "INR", group: goa.group.id
        ))
        XCTAssertEqual(try a.removalRefusal(groupID: goa.group.id, memberID: me.id), .you)
        XCTAssertEqual(try a.removalRefusal(groupID: goa.group.id, memberID: rahul), .history)
        XCTAssertEqual(try a.removalRefusal(groupID: goa.group.id, memberID: priya), .history, "a payment is history too")
        XCTAssertNil(try a.removalRefusal(groupID: goa.group.id, memberID: sam))

        let after = try a.removeMember(groupID: goa.group.id, memberID: sam)
        XCTAssertEqual(after.members.map(\.name), ["Tim", "Rahul", "Priya"])
        let projected = try XCTUnwrap(LedgerStore.Sync.projectGroups(a).groups.first { $0.group.id == goa.group.id })
        XCTAssertTrue(projected.group.members.contains(sam), "the records still hold Sam")
        XCTAssertEqual(projected.removed, [sam])

        XCTAssertThrowsError(try a.removeMember(groupID: goa.group.id, memberID: rahul)) { error in
            XCTAssertEqual(error as? MemberCopy.Refused, MemberCopy.Refused(why: .history))
        }

        let b = device("b")
        try sync(a, into: b)
        XCTAssertEqual(try b.namedGroups().first?.members.map(\.name), ["Tim", "Rahul", "Priya"])
    }

    /// Someone who joined on their phone stays: taking them out would need
    /// the group's key rotated (SPEC §3), which is not built.
    func testSomeoneWhoJoinedStays() throws {
        let a = device("c")
        _ = try a.setYourName("Tim")
        let goa = try a.createGroup(name: "Goa", settlementCurrency: "INR", people: ["Rahul"])
        let rahul = idOf(goa, "Rahul")
        try LedgerStore.Sync.appendGroupOps(a, [.claim(
            groupId: goa.group.id, memberId: rahul, youId: "f".repeated(32),
            devices: [ClaimDeviceIn(deviceId: "e".repeated(32), agreementPublicHex: "11".repeated(32))]
        )], placing: .none)
        try LedgerStore.Sync.rebuildGroups(a)
        XCTAssertEqual(try a.removalRefusal(groupID: goa.group.id, memberID: rahul), .joined)
    }

    func testTheMemberWordsMatchAndroid() {
        XCTAssertEqual(MemberCopy.removeTitle("Sam", from: "Goa"), "Remove Sam from Goa?")
        XCTAssertEqual(MemberCopy.removeBody, "They are in no expense or payment here. You can add them again later.")
        XCTAssertEqual(MemberCopy.stays,
                       "Anyone in an expense or payment here, or who joined on their phone, stays in the group.")
        XCTAssertEqual(MemberCopy.addPeople, "Add people")
        XCTAssertEqual(MemberCopy.spokenRemove("Sam"), "Remove Sam")
        XCTAssertEqual([MemberCopy.Refusal.you, .joined, .history].map(MemberCopy.message), [
            "You cannot take yourself out of a group.",
            "They joined on their phone, so they stay in the group.",
            "They are in an expense or payment here, so they stay in the group.",
        ])
    }

    // MARK: D-187 group pictures (the twin of Android's GroupPicturesTest)

    private func jpeg(_ size: Int) -> Data {
        var d = Data(count: size)
        d[0] = 0xFF; d[1] = 0xD8; d[2] = 0xFF
        return d
    }

    /// An emoji, then a photo, then the letter back; the picture is a group
    /// record, so the next phone shows it too.
    func testAGroupCarriesItsPictureToEveryPhone() throws {
        let a = device("pa")
        try a.setYourName("Tim")
        let goa = try a.createGroup(name: "Goa", settlementCurrency: "INR", people: ["Rahul"]).group.id
        XCTAssertNil(try a.groupPictures()[goa])

        try a.setGroupPicture(groupID: goa, emoji: "🏖️")
        XCTAssertEqual(try a.groupPictures()[goa], PictureDTO(emoji: "🏖️"))
        let scopes = try groupOps(a).filter { if case .groupPicture = $0.1 { return true } else { return false } }
            .map(\.0.groupId)
        XCTAssertEqual(scopes, [goa], "a picture is its group's record, shared with the group")

        let photo = jpeg(1_000)
        try a.setGroupPicture(groupID: goa, jpeg: photo)
        XCTAssertEqual(try a.groupPictures()[goa], PictureDTO(photoHex: HexCodec.encode(photo)))

        let b = device("pb")
        try sync(a, into: b)
        XCTAssertEqual(try b.groupPictures()[goa], PictureDTO(photoHex: HexCodec.encode(photo)))

        try a.setGroupPicture(groupID: goa)
        XCTAssertNil(try a.groupPictures()[goa], "the letter back")
    }

    /// Negative: core takes a JPEG of at most 40,000 bytes and nothing else;
    /// a refused photo leaves the picture as it was.
    func testAPhotoTooBigOrNotAJpegIsRefused() throws {
        let a = device("pc")
        try a.setYourName("Tim")
        let goa = try a.createGroup(name: "Goa", settlementCurrency: "INR", people: ["Rahul"]).group.id
        try a.setGroupPicture(groupID: goa, emoji: "⛰️")
        for bad in [jpeg(GroupPictures.maxBytes + 1), Data([0x89, 0x50, 0x4E, 0x47])] {
            XCTAssertThrowsError(try a.setGroupPicture(groupID: goa, jpeg: bad), "core took \(bad.count) bytes") {
                XCTAssertTrue($0 is CoreError)
            }
            XCTAssertEqual(try a.groupPictures()[goa], PictureDTO(emoji: "⛰️"))
        }
        try a.setGroupPicture(groupID: goa, jpeg: jpeg(GroupPictures.maxBytes))
        XCTAssertEqual(try a.groupPictures()[goa]?.photoHex?.count, GroupPictures.maxBytes * 2)
    }

    /// A tall photo becomes its centre 3:2, 768 by 512, a JPEG that core
    /// takes: the top third red, the middle blue, the bottom green, so only
    /// the centre crop comes out blue at every corner.
    func testAPhotoIsMadeIntoItsCentre3By2() throws {
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        let tall = UIGraphicsImageRenderer(size: CGSize(width: 300, height: 900), format: format).image { ctx in
            UIColor.red.setFill(); ctx.fill(CGRect(x: 0, y: 0, width: 300, height: 300))
            UIColor.blue.setFill(); ctx.fill(CGRect(x: 0, y: 300, width: 300, height: 300))
            UIColor.green.setFill(); ctx.fill(CGRect(x: 0, y: 600, width: 300, height: 300))
        }
        let out = try XCTUnwrap(GroupPictures.prepare(tall))
        XCTAssertLessThanOrEqual(out.count, GroupPictures.maxBytes)
        XCTAssertEqual(Array(out.prefix(3)), [0xFF, 0xD8, 0xFF])
        let cg = try XCTUnwrap(UIImage(data: out)?.cgImage)
        XCTAssertEqual([cg.width, cg.height], [768, 512])
        let pixel = try rgba(cg)
        for (x, y) in [(8, 8), (759, 8), (8, 503), (759, 503), (384, 256)] {
            let (r, g, b) = pixel(x, y)
            XCTAssertTrue(r < 40 && g < 40 && b > 200, "blue at \(x),\(y): \(r) \(g) \(b)")
        }
        XCTAssertNil(GroupPictures.prepare(Data([1, 2, 3])))
    }

    /// A decoded image's pixels in an order this test chose (RGBA), so red
    /// can never pass for blue. (Reading the JPEG's own buffer could not
    /// tell RGBA from BGRA: D-187's sabotage row I4 found the first version
    /// of this test accepting a red top-crop as blue.)
    private func rgba(_ cg: CGImage) throws -> (Int, Int) -> (Int, Int, Int) {
        let w = cg.width, h = cg.height
        var buffer = [UInt8](repeating: 0, count: w * h * 4)
        let drawn: Bool = buffer.withUnsafeMutableBytes { raw in
            guard let ctx = CGContext(data: raw.baseAddress, width: w, height: h, bitsPerComponent: 8,
                                      bytesPerRow: w * 4, space: CGColorSpaceCreateDeviceRGB(),
                                      bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return false }
            ctx.draw(cg, in: CGRect(x: 0, y: 0, width: w, height: h))
            return true
        }
        XCTAssertTrue(drawn)
        let pixels = buffer
        return { x, y in
            let i = (y * w + x) * 4
            return (Int(pixels[i]), Int(pixels[i + 1]), Int(pixels[i + 2]))
        }
    }

    /// Negative, then the answer to it: the busiest photo there is (noise,
    /// from a pinned generator) cannot fit 64,000 bytes at 768, so it steps
    /// down and still comes out a 3:2 JPEG that core takes.
    func testABusyPhotoStepsDownRatherThanBeingRefused() throws {
        var seed: UInt32 = 187
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        let noise = UIGraphicsImageRenderer(size: CGSize(width: 900, height: 600), format: format).image { ctx in
            for y in stride(from: 0, to: 600, by: 3) {
                for x in stride(from: 0, to: 900, by: 3) {
                    seed = seed &* 1_664_525 &+ 1_013_904_223
                    UIColor(red: CGFloat(seed >> 24 & 255) / 255, green: CGFloat(seed >> 16 & 255) / 255,
                            blue: CGFloat(seed >> 8 & 255) / 255, alpha: 1).setFill()
                    ctx.fill(CGRect(x: x, y: y, width: 3, height: 3))
                }
            }
        }
        let big = UIGraphicsImageRenderer(size: CGSize(width: 768, height: 512), format: format).image { _ in
            noise.draw(in: CGRect(x: 0, y: 0, width: 768, height: 512))
        }
        XCTAssertGreaterThan(try XCTUnwrap(big.jpegData(compressionQuality: 0.4)).count, GroupPictures.maxBytes,
                             "the premise: at 768 it does not fit")
        let out = try XCTUnwrap(GroupPictures.prepare(noise))
        XCTAssertLessThanOrEqual(out.count, GroupPictures.maxBytes)
        let cg = try XCTUnwrap(UIImage(data: out)?.cgImage)
        XCTAssertLessThan(cg.width, 768)
        XCTAssertEqual(cg.height, cg.width * 2 / 3)
    }

    /// Goldens from .drafts/d187/golden.py: the largest centred 3:2.
    func testTheCoverCropIsTheCentre3By2() {
        let cases: [((Int, Int), [Int])] = [
            ((300, 900), [0, 350, 300, 200]), ((4032, 3024), [0, 168, 4032, 2688]),
            ((1920, 1080), [150, 0, 1620, 1080]), ((3, 2), [0, 0, 3, 2]), ((1000, 1000), [0, 167, 1000, 666]),
        ]
        for ((w, h), want) in cases {
            let c = GroupPictures.coverCrop(width: w, height: h)
            XCTAssertEqual([c.x, c.y, c.width, c.height], want, "\(w)x\(h)")
        }
    }

    /// The founder: words over a photo must not blend in. White over the
    /// gradient on a pure white photo (goldens from golden.py): 4.759:1 where
    /// words sit and under the chip; the top band's 1.834:1 is why nothing
    /// but the chip's own capsule sits there.
    func testWordsOverAPhotoClear4Point5To1() {
        XCTAssertEqual(PhotoCard.worstContrast(alpha: PhotoCard.textAlpha), 4.759, accuracy: 0.001)
        XCTAssertEqual(PhotoCard.worstContrast(alpha: PhotoCard.chipAlpha), 4.759, accuracy: 0.001)
        XCTAssertEqual(PhotoCard.worstContrast(alpha: PhotoCard.bottomAlpha), 9.233, accuracy: 0.001)
        XCTAssertEqual(PhotoCard.worstContrast(alpha: PhotoCard.topAlpha), 1.834, accuracy: 0.001)
        XCTAssertGreaterThanOrEqual(PhotoCard.bottomAlpha, PhotoCard.textAlpha, "darker toward the words, never lighter")
    }

    // MARK: D-189 Activity (the twin of Android's ActivityFeedTest)

    private func projected(_ id: String, group: String?, note: String?, addedBy: String, at: UInt64,
                           editedBy: String? = nil, editedAt: UInt64? = nil, deleted: Bool = false) -> ProjectedExpenseOut {
        ProjectedExpenseOut(
            id: id,
            expense: ExpenseInput(payer: addedBy, paidMinor: 4_800_00, currencyCode: "INR", shares: [], group: group, conversion: nil),
            note: note, category: nil, timestampMs: nil, addedBy: addedBy, addedAtMs: at, deleted: deleted,
            lastEditedAtMs: editedAt, lastEditedBy: editedBy, seen: [], moneyConflict: []
        )
    }

    /// Goldens by hand from the rules: one item per expense, its latest
    /// change, only when someone else made it; personal expenses never;
    /// newest first; a blank note reads "an expense".
    func testActivityIsTheLatestChangeSomeoneElseMade() {
        let (me, alias, rahul, priya, g) = ("m", "m2", "r", "p", "g")
        let feed = ActivityFeed.items([
            projected("e1", group: g, note: "Dinner", addedBy: rahul, at: 1_000),
            projected("e2", group: g, note: "Mine", addedBy: me, at: 2_000),
            projected("e3", group: g, note: "Hotel", addedBy: me, at: 1_500, editedBy: rahul, editedAt: 3_000),
            projected("e4", group: g, note: "Fixed", addedBy: rahul, at: 1_200, editedBy: me, editedAt: 4_000),
            projected("e5", group: g, note: "Taxi", addedBy: priya, at: 500, editedBy: priya, editedAt: 5_000, deleted: true),
            projected("e6", group: nil, note: "Personal", addedBy: rahul, at: 6_000),
            projected("e7", group: g, note: "Old me", addedBy: alias, at: 7_000),
            projected("e8", group: g, note: "  ", addedBy: rahul, at: 800),
        ], me: [me, alias])
        XCTAssertEqual(feed.map(\.expenseID), ["e5", "e3", "e1", "e8"])
        XCTAssertEqual(feed.map(\.kind), [.deleted, .changed, .added, .added])
        XCTAssertEqual(feed.map(\.by), [priya, rahul, rahul, rahul])
        XCTAssertEqual(feed.map(\.atMs), [5_000, 3_000, 1_000, 800])
        XCTAssertEqual(feed.map(\.title), ["Taxi", "Hotel", "Dinner", "an expense"])
        XCTAssertEqual(ActivityFeed.unread(feed, seenUpTo: 0), 4)
        XCTAssertEqual(ActivityFeed.unread(feed, seenUpTo: 1_000), 2)
        XCTAssertEqual(ActivityFeed.unread(feed, seenUpTo: 5_000), 0)
    }

    /// Negative: never more than 50, and the 50 are the newest.
    func testActivityKeepsTheNewestFifty() {
        let feed = ActivityFeed.items((1...60).map {
            projected("e\($0)", group: "g", note: "x", addedBy: "r", at: UInt64($0))
        }, me: ["m"])
        XCTAssertEqual(feed.count, 50)
        XCTAssertEqual(feed.first?.atMs, 60)
        XCTAssertEqual(feed.last?.atMs, 11)
    }

    /// The marker is this phone's, in the vault, and survives a reopen.
    func testActivitySeenUpToIsKeptInTheVault() throws {
        let a = device("act")
        XCTAssertEqual(a.activitySeenUpTo(), 0)
        try a.setActivitySeenUpTo(5_000)
        XCTAssertEqual(LedgerStore(path: a.path).activitySeenUpTo(), 5_000)
    }

    func testTheActivityWordsMatchAndroid() {
        XCTAssertEqual(ActivityFeed.title, "Activity")
        XCTAssertEqual(ActivityFeed.empty, "Nothing new yet. What others add, change or delete in your groups shows here.")
        XCTAssertEqual(ActivityFeed.line(.added, who: "Rahul", title: "Dinner"), "Rahul added Dinner")
        XCTAssertEqual(ActivityFeed.line(.changed, who: "Rahul", title: "Dinner"), "Rahul changed Dinner")
        XCTAssertEqual(ActivityFeed.line(.deleted, who: "Rahul", title: "Dinner"), "Rahul deleted Dinner")
        XCTAssertEqual(ActivityFeed.spokenBell(0), "Activity")
        XCTAssertEqual(ActivityFeed.spokenBell(3), "Activity, 3 new")
        XCTAssertEqual(ActivityFeed.someone, "Someone")
    }

    func testThePictureChoicesAndWordsMatchAndroid() {
        XCTAssertEqual(GroupPictures.emoji.count, 24)
        XCTAssertEqual(Set(GroupPictures.emoji).count, GroupPictures.emoji.count)
        XCTAssertEqual(Array(GroupPictures.emoji.prefix(3)), ["✈️", "🏖️", "⛰️"])
        XCTAssertEqual(GroupPictures.emoji.last, "❤️")
        XCTAssertEqual(GroupPictures.title, "Group picture")
        XCTAssertEqual(GroupPictures.choosePhoto, "Choose a photo")
        XCTAssertEqual(GroupPictures.useLetter, "Use the letter")
        XCTAssertEqual(GroupPictures.tooBig, "That photo could not be made small enough. Try another.")
        XCTAssertEqual(GroupPictures.spokenEmoji("🏖️"), "Use 🏖️")
    }

    // MARK: - D-229: one account on two phones writes what its groups can read (twin of GroupRecordsTest)

    private typealias F = FounderVault

    private func founder(_ name: String, pClaim: String?) throws -> LedgerStore {
        try FounderVault.make(device(name), pClaim: pClaim)
    }

    /// The last record of `type` this vault holds (its own newest write).
    private func last(_ store: LedgerStore, _ type: UInt8) throws -> RecordWire {
        try XCTUnwrap(store.allRecords().filter { $0.recordType == type }
            .max { ($0.hlcMillis, $0.hlcCounter) < ($1.hlcMillis, $1.hlcCounter) })
    }

    private func dinner(_ group: String?, payer: String = F.y) -> LedgerStore.ExpenseDTO {
        LedgerStore.ExpenseDTO(
            payer: payer, paidMinor: 1_200, currencyCode: "INR", group: group, conversion: nil,
            shares: [.init(id: F.y, amountMinor: 600), .init(id: F.c, amountMinor: 600)],
            note: "Dinner", timestampMs: 5_000, category: nil, addedBy: F.y)
    }

    /// Test 1: in Old (made on P under x; x is the place and y is unreadable there) the RECORD names x, the kv
    /// ledger keeps y, and a rebuild gives the same kv bytes.
    func testAnExpenseInAGroupMadeUnderTheOtherIdIsWrittenAsThePlace() throws {
        let p = try founder("p1", pClaim: F.x)
        XCTAssertEqual(try LedgerStore.Placing.load(p), LedgerStore.Placing(places: [F.old: F.x], mine: [F.y, F.x]))
        let list = try p.appendExpense(dinner(F.old))
        XCTAssertEqual(list.last, dinner(F.old), "the kv ledger keeps the merged you")
        let written = try JSONDecoder().decode(LedgerStore.ExpenseDTO.self, from: try last(p, LedgerStore.Sync.expenseType).blob)
        XCTAssertEqual(written.payer, F.x)
        XCTAssertEqual(written.shares, [.init(id: F.x, amountMinor: 600), .init(id: F.c, amountMinor: 600)])
        XCTAssertEqual(written.addedBy, F.x)
        XCTAssertEqual(written.note, "Dinner")
        let kv = try p.kvGet(key: LedgerStore.key)
        try p.rebuildProjection()
        XCTAssertEqual(try p.kvGet(key: LedgerStore.key), kv, "the rebuild reads x back as y: the same bytes")
        XCTAssertEqual(try p.loadExpenses().map(\.payer), [F.y])
    }

    /// D-237 (the Fable audit, A1): a place this account claimed in ANOTHER group is someone else's id here: when
    /// this phone writes as its place, a share for that person stays theirs. Without it the global set mapped her
    /// share onto this phone's place, into the log every member reads. (Core's read-side merge is per group the
    /// same way.) Twin: GroupRecordsTest.d237_a_place_claimed_in_another_group_is_not_written_as_this_accounts_place.
    func testD237APlaceClaimedInAnotherGroupIsNotWrittenAsThisAccountsPlace() throws {
        let carol = String(repeating: "c", count: 32)
        let placing = LedgerStore.Placing(places: [F.old: F.x], mine: [F.y, F.x, carol],
                                          claimed: [F.old: [F.x], F.new: [carol]])
        XCTAssertEqual(placing.mineIn(F.old), [F.y, F.x])
        XCTAssertEqual(placing.mineIn(F.new), [F.y, carol], "x, claimed only in Old, is left out of New the same way")
        XCTAssertEqual(placing.id(carol, group: F.old), carol, "Carol's id in Old, claimed by this account only in New")
        XCTAssertEqual(placing.id(F.y, group: F.old), F.x)
        var withCarol = dinner(F.old)
        withCarol.shares = [.init(id: F.y, amountMinor: 600), .init(id: carol, amountMinor: 600)]
        let w = try placing.expense(withCarol)
        XCTAssertEqual(w.payer, F.x)
        XCTAssertEqual(w.shares, [.init(id: F.x, amountMinor: 600), .init(id: carol, amountMinor: 600)], "Carol keeps her share")
        // For contrast, the old merge (no per-group claimed set): her share lands on x.
        let old = LedgerStore.Placing(places: [F.old: F.x], mine: [F.y, F.x, carol])
        XCTAssertEqual(try old.expense(withCarol).shares, [.init(id: F.x, amountMinor: 1_200)])
        // `of` reads the claimed set from core's aliases to "you" (round 2, review M7: a positive check, so the
        // write-side fix cannot quietly revert): an alias to you is a claimed place in its group; one to someone
        // else is not.
        let group = ProjectedGroupOut(
            group: GroupWire(id: F.old, name: "Old", settlementCurrency: "INR", members: [F.y, F.c]), members: [],
            created: "1000:0:" + F.y, claims: [], removed: [], picture: nil, profiles: [], held: [], place: F.x,
            youAs: F.x, unaliasedYous: [], placeLost: false)
        let projection = GroupsProjectionOut(
            you: YouOut(id: F.y, name: nil), otherYouIds: [F.x, carol], groups: [group], rejected: [], pending: [],
            contested: [], keys: [],
            aliases: [IdAlias(groupId: F.old, fromId: F.x, toId: F.y), IdAlias(groupId: F.new, fromId: carol, toId: F.y),
                      IdAlias(groupId: F.old, fromId: F.c, toId: F.x)],
            maximumSecurity: false, deviceNames: [], avatar: nil, settings: [])
        let built = LedgerStore.Placing.of(projection)
        XCTAssertEqual(built.claimed, [F.old: [F.x], F.new: [carol]])
        XCTAssertEqual(built.mineIn(F.old), [F.y, F.x])
        XCTAssertEqual(built.id(carol, group: F.old), carol)
        let p = try founder("d237", pClaim: F.x)
        XCTAssertEqual(try LedgerStore.Placing.load(p).claimed, [String: Set<String>](), "no claimed place on the founder's phone")
    }

    /// Test 2: in New (made under y) and in the personal ledger nothing is mapped.
    func testAnExpenseInAGroupThatReadsYouAndAPersonalOneAreUntouched() throws {
        let p = try founder("p2", pClaim: F.x)
        _ = try p.appendExpense(dinner(F.new))
        XCTAssertEqual(try JSONDecoder().decode(LedgerStore.ExpenseDTO.self,
                                                from: try last(p, LedgerStore.Sync.expenseType).blob), dinner(F.new))
        _ = try p.appendExpense(dinner(nil))
        XCTAssertEqual(try JSONDecoder().decode(LedgerStore.ExpenseDTO.self,
                                                from: try last(p, LedgerStore.Sync.expenseType).blob), dinner(nil))
    }

    /// Test 3: a payment from y in Old is recorded from x; one whose ends both become x is refused before anything
    /// is written.
    func testASettlementInOldIsWrittenFromThePlaceAndOneWithOneIdIsRefused() throws {
        let p = try founder("p3", pClaim: F.x)
        let paid = LedgerStore.SettlementDTO(from: F.y, to: F.c, amountMinor: 600, currencyCode: "INR", group: F.old)
        XCTAssertEqual(try p.appendSettlement(paid), [paid], "the kv ledger keeps y")
        let written = try JSONDecoder().decode(LedgerStore.SettlementDTO.self,
                                               from: try last(p, LedgerStore.Sync.settlementType).blob)
        XCTAssertEqual(written, LedgerStore.SettlementDTO(from: F.x, to: F.c, amountMinor: 600, currencyCode: "INR",
                                                          group: F.old))
        let before = try p.allRecords().count
        let toSelf = LedgerStore.SettlementDTO(from: F.y, to: F.x, amountMinor: 100, currencyCode: "INR", group: F.old)
        XCTAssertThrowsError(try p.appendSettlement(toSelf)) { error in
            XCTAssertEqual(error as? LedgerStore.Placing.SameEnds, LedgerStore.Placing.SameEnds())
        }
        XCTAssertEqual(try p.allRecords().count, before, "nothing written")
        XCTAssertEqual(try p.loadSettlements(), [paid], "nor the kv ledger")
    }

    /// Test 4: your name goes to Old under x and to New under y, and lands in both (nothing pending).
    func testYourNameIsWrittenUnderEachGroupsPlaceAndLands() throws {
        let p = try founder("p4", pClaim: F.x)
        let before = try groupOps(p).count
        try p.setYourName("Alan")
        let renames = try groupOps(p).dropFirst(before).map(\.1).filter {
            if case .renameMember = $0 { return true }
            return false
        }
        XCTAssertEqual(Set(renames), [
            .renameMember(groupId: F.old, memberId: F.x, name: "Alan"),
            .renameMember(groupId: F.new, memberId: F.y, name: "Alan"),
        ])
        let projected = try LedgerStore.Sync.projectGroups(p)
        XCTAssertEqual(projected.pending, [], "no rename waits for a member nobody will add")
        for g in [F.old, F.new] {
            let named = try XCTUnwrap(p.namedGroups().first { $0.group.id == g })
            XCTAssertEqual(named.members.first { $0.id == F.y }?.name, "Alan", g)
        }
    }

    /// Test 5: an edit of an Old expense: the editor and the new money as x; `seen` as it was.
    func testAnEditInOldIsWrittenAsThePlace() throws {
        let p = try founder("p5", pClaim: F.x)
        _ = try p.appendExpense(dinner(F.old))
        let e = try XCTUnwrap(p.projectedExpenses().expenses.first { $0.expense.group == F.old })
        // New money as an edit screen may hand it over, with no group of its own: the expense's group decides.
        let money = ExpenseInput(payer: F.y, paidMinor: 900, currencyCode: "INR",
                                 shares: [IdAmount(id: F.y, amountMinor: 450), IdAmount(id: F.c, amountMinor: 450)],
                                 group: nil, conversion: nil)
        try p.reviseExpense(ExpenseRevisionIn(target: e.id, seen: e.seen, editor: F.y, money: money, note: "Late dinner",
                                              category: nil, deleted: nil))
        let r = try ffiDecodeExpenseRevision(blob: try last(p, LedgerStore.Sync.revisionType).blob)
        XCTAssertEqual(r.editor, F.x)
        // The record carries no group in its money (its scope does): it decodes with none.
        XCTAssertEqual(r.money, ExpenseInput(payer: F.x, paidMinor: 900, currencyCode: "INR",
                                             shares: [IdAmount(id: F.x, amountMinor: 450), IdAmount(id: F.c, amountMinor: 450)],
                                             group: nil, conversion: nil))
        XCTAssertEqual(r.seen, e.seen)
        XCTAssertEqual(r.target, e.id)
        XCTAssertEqual(r.note, "Late dinner")
        XCTAssertEqual(try p.loadExpenses().first?.payer, F.y, "the kv ledger reads you")
    }

    /// Test 6: a removal's redistribution with a share to y in Old names x; the removed person stays as named.
    func testARedistributionInOldNamesThePlace() throws {
        let p = try founder("p6", pClaim: F.x)
        let moved = RedistributionInput(group: F.old, from: F.c, currencyCode: "INR",
                                        shares: [IdAmount(id: F.y, amountMinor: 600)])
        let records = try Removal.records(store: p, group: F.old, member: F.c, redistributions: [moved],
                                          placing: try LedgerStore.Placing.load(p))
        XCTAssertEqual(try ffiDecodeRedistribution(blob: records[0].blob),
                       RedistributionInput(group: F.old, from: F.c, currencyCode: "INR",
                                           shares: [IdAmount(id: F.x, amountMinor: 600)]))
        XCTAssertEqual(try ffiDecodeGroupOp(blob: records[1].blob), .removeMember(groupId: F.old, memberId: F.c))
        let unplaced = try Removal.records(store: p, group: F.old, member: F.c, redistributions: [moved], placing: .none)
        XCTAssertEqual(try ffiDecodeRedistribution(blob: unplaced[0].blob), moved, ".none maps nothing")
    }

    /// Test 7: the creator's claim names the place. Case (ii) (`{x, you x}` stands): nothing more in Old. Case (i)
    /// (no claim on x): `{Old, x, you y}`, which lands, so the next call writes nothing (the leak, ended). New: `{New,
    /// y, you y}` as before.
    func testTheOwnClaimNamesThePlaceAndLands() throws {
        let device = "e".repeated(32)
        func claims(_ store: LedgerStore) throws -> [String: GroupOpIn] {
            Dictionary(uniqueKeysWithValues: CloudRecords.ownClaims(try LedgerStore.Sync.projectGroups(store), device: device)
                .map { op -> (String, GroupOpIn) in
                    guard case .claim(let g, _, _, _) = op else { return ("?", op) }
                    return (g, op)
                })
        }
        let caseTwo = try founder("p7a", pClaim: F.x)
        XCTAssertEqual(try claims(caseTwo), [F.new: Invites.claim(group: F.new, member: F.y, you: F.y, device: device)])

        let caseOne = try founder("p7b", pClaim: nil)
        let first = try claims(caseOne)
        XCTAssertEqual(first, [
            F.old: Invites.claim(group: F.old, member: F.x, you: F.y, device: device),
            F.new: Invites.claim(group: F.new, member: F.y, you: F.y, device: device),
        ])
        try LedgerStore.Sync.appendGroupOps(caseOne, Array(first.values), placing: .none)
        XCTAssertEqual(try claims(caseOne), [:], "both landed: no claim is written again")
        let p = try LedgerStore.Sync.projectGroups(caseOne)
        XCTAssertEqual(p.pending, [])
        XCTAssertEqual(p.groups.first { $0.group.id == F.old }?.youAs, nil, "y reads as x in Old now")
    }

    /// Test 8: no account and one "you": nothing is mapped, and every builder writes what it wrote before D-229.
    func testWithOneYouEveryBuilderWritesAsBefore() throws {
        let a = device("solo")
        let me = try a.setYourName("Tim")
        let goa = try a.createGroup(name: "Goa", settlementCurrency: "INR", people: ["Rahul"])
        let g = goa.group.id
        let rahul = idOf(goa, "Rahul")
        let placing = try LedgerStore.Placing.load(a)
        XCTAssertEqual(placing.places, [:])
        let e = LedgerStore.ExpenseDTO(payer: me.id, paidMinor: 1_000, currencyCode: "INR", group: g, conversion: nil,
                                       shares: [.init(id: me.id, amountMinor: 500), .init(id: rahul, amountMinor: 500)],
                                       addedBy: me.id)
        XCTAssertEqual(try placing.expense(e), e)
        let s = LedgerStore.SettlementDTO(from: me.id, to: rahul, amountMinor: 100, currencyCode: "INR", group: g)
        XCTAssertEqual(try placing.settlement(s), s)
        let r = ExpenseRevisionIn(target: "t", seen: [], editor: me.id, money: e.asInput, note: nil, category: nil,
                                  deleted: nil)
        XCTAssertEqual(try placing.revision(r, group: g), r)
        let moved = RedistributionInput(group: g, from: rahul, currencyCode: "INR",
                                        shares: [IdAmount(id: me.id, amountMinor: 100)])
        XCTAssertEqual(try placing.redistribution(moved), moved)
        let rename = GroupOpIn.renameMember(groupId: g, memberId: me.id, name: "T")
        XCTAssertEqual(try placing.op(rename), rename)
        XCTAssertEqual(placing.lost, [])
        XCTAssertEqual(placing.id(me.id, group: g), me.id)
        _ = try a.appendExpense(e)
        XCTAssertEqual(try JSONDecoder().decode(LedgerStore.ExpenseDTO.self,
                                                from: try last(a, LedgerStore.Sync.expenseType).blob), e)
        _ = try a.appendSettlement(s)
        XCTAssertEqual(try JSONDecoder().decode(LedgerStore.SettlementDTO.self,
                                                from: try last(a, LedgerStore.Sync.settlementType).blob), s)
    }

    // MARK: - D-229 follow-up (twin of GroupRecordsTest)

    private func lostError(_ error: Error, _ what: String) {
        XCTAssertEqual(error as? LedgerStore.Placing.PlaceLost, LedgerStore.Placing.PlaceLost(), what)
    }

    /// Decision 45: account D's claim holds x, P's place in Old. Old is a read-only copy on P with its own one-line
    /// reason; every writer refuses it before anything is written; a push takes nothing of it up; your name is not
    /// written there; no alias is asked for it. New is untouched.
    func testAGroupWhereYourPlaceIsLostIsReadOnlyAndTakesNoRecord() throws {
        let p = try FounderVault.holdOld(founder("lost", pClaim: nil))
        let projected = try LedgerStore.Sync.projectGroups(p)
        XCTAssertEqual(projected.groups.filter(\.placeLost).map(\.group.id), [F.old], "core: Old is lost, New is not")
        XCTAssertEqual(try p.readOnlyChecked(), [F.old: .placeLost])
        XCTAssertEqual(p.readOnly(), [F.old: .placeLost])
        XCTAssertEqual(try p.kvGet(key: LedgerStore.readOnlyKey), nil, "never stored: read from the records")
        XCTAssertEqual(RolesCopy.placeLostBanner,
                       "You can't add to this group from this phone: someone else holds your place in it. "
                           + "Ask the group's owner to check People.")
        XCTAssertEqual(ReadOnlyBanner.title(mark: .placeLost, date: "1 Jan"), RolesCopy.placeLostBanner)
        XCTAssertNil(ReadOnlyBanner.body(mark: .placeLost, group: "Old"), "one line")

        let placing = try LedgerStore.Placing.load(p)
        XCTAssertEqual(placing.lost, [F.old])
        XCTAssertEqual(placing.refusal(group: F.old), LedgerStore.Placing.PlaceLost())
        XCTAssertNil(placing.refusal(group: F.new))
        XCTAssertNil(placing.refusal(group: nil))
        XCTAssertNil(LedgerStore.Placing.none.refusal(group: F.old), ".none refuses nothing (the backfills)")

        let records = try p.allRecords().count
        let kv = try p.kvGet(key: LedgerStore.key)
        let paid = LedgerStore.SettlementDTO(from: F.y, to: F.c, amountMinor: 600, currencyCode: "INR", group: F.old)
        XCTAssertThrowsError(try p.appendExpense(dinner(F.old))) { lostError($0, "an expense") }
        XCTAssertThrowsError(try p.appendExpenses([dinner(F.new), dinner(F.old)])) { lostError($0, "an import") }
        XCTAssertThrowsError(try p.appendSettlement(paid)) { lostError($0, "a payment") }
        XCTAssertThrowsError(try Removal.records(store: p, group: F.old, member: F.c, redistributions: [],
                                                 placing: placing)) { lostError($0, "a removal") }
        XCTAssertThrowsError(try LedgerStore.Sync.appendGroupOps(
            p, [.renameMember(groupId: F.new, memberId: F.c, name: "B"),
                .renameMember(groupId: F.old, memberId: F.c, name: "B")], placing: placing)) { lostError($0, "an op") }
        let edit = ExpenseRevisionIn(target: "t", seen: [], editor: F.y, money: nil, note: "x", category: nil,
                                     deleted: nil)
        XCTAssertThrowsError(try placing.revision(edit, group: F.old)) { lostError($0, "an edit") }
        XCTAssertThrowsError(try placing.redistribution(RedistributionInput(
            group: F.old, from: F.c, currencyCode: "INR", shares: [IdAmount(id: F.y, amountMinor: 1)]))) {
            lostError($0, "a redistribution")
        }
        // Fix wave M2: the group's name and picture refuse too (they carry no id to map; they only refuse).
        XCTAssertThrowsError(try p.renameGroup(groupID: F.old, name: "Renamed")) { lostError($0, "a group rename") }
        XCTAssertThrowsError(try p.setGroupPicture(groupID: F.old, emoji: "🍕")) { lostError($0, "a group picture") }
        XCTAssertEqual(try p.allRecords().count, records, "nothing written, New's rename included")
        XCTAssertEqual(try p.kvGet(key: LedgerStore.key), kv, "nor the kv ledger")
        // ...and still write in a group whose place is held.
        try p.renameGroup(groupID: F.new, name: "New name")
        try p.setGroupPicture(groupID: F.new, emoji: "🍕")
        XCTAssertEqual(try p.allRecords().count, records + 2, "New's name and picture are written")

        // Fix wave M3 (decision 52's second half): a lost place is still a membership, so the copy offers Export only;
        // "Remove from this phone" would erase records waiting to upload and the group would come straight back.
        XCTAssertFalse(ReadOnlyActions.offersForget(.placeLost))
        XCTAssertTrue(ReadOnlyActions.offersForget(LedgerStore.ReadOnly(kind: "removed", by: "Bo", at: Date())))
        XCTAssertTrue(ReadOnlyActions.offersForget(LedgerStore.ReadOnly(kind: "deleted", by: "Bo", at: Date())))
        let app = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("Skipti/App")
        let actions = try String(contentsOf: app.appendingPathComponent("GroupSettings.swift"), encoding: .utf8)
        let gate = try XCTUnwrap(actions.range(of: "if Self.offersForget(mark) {"), "the forget row is gated on the rule")
        let row = try XCTUnwrap(actions.range(of: "Text(verbatim: RolesCopy.forgetRow)"))
        XCTAssertLessThan(gate.lowerBound, row.lowerBound, "the gate comes before the row")
        XCTAssertEqual(actions.components(separatedBy: "RolesCopy.forgetRow").count, 2, "one forget row")
        let overview = try String(contentsOf: app.appendingPathComponent("OverviewScreen.swift"), encoding: .utf8)
        XCTAssertTrue(overview.contains("if let readOnly {\n                        ReadOnlyActions(store: store, group: named, mark: readOnly)"),
                      "the group page hands the copy its mark")

        // Your name: written in New, not in Old.
        try p.setYourName("Alan")
        let renamed = try groupOps(p).compactMap { pair -> String? in
            guard case .renameMember(let g, _, "Alan") = pair.1 else { return nil }
            return g
        }
        XCTAssertEqual(renamed, [F.new])

        // A record already waiting in Old stays on the phone; New's still go.
        _ = try p.applyRecord(try LedgerStore.Sync.expenseRecord(
            dinner(F.old), hlc: HlcStamp(millis: 4_000, counter: 0), deviceId: try LedgerStore.Sync.deviceId(p),
            placing: .none))
        let all = try p.allRecords()
        XCTAssertTrue(CloudRecords.pending(all, uploaded: []).contains { $0.groupId == F.old }, "not vacuous")
        let out = CloudRecords.outgoing(all, uploaded: [], readOnly: Set(p.readOnly().keys), refused: [])
        XCTAssertFalse(out.send.contains { $0.groupId == F.old }, "nothing of Old goes up")
        XCTAssertTrue(out.send.contains { $0.groupId == F.new })
        XCTAssertEqual(AliasShare.wanted(try LedgerStore.Sync.projectGroups(p), records: all,
                                         readOnly: Set(p.readOnly().keys), answered: []).filter { $0.groupId == F.old },
                       [], "no alias is asked for Old")
    }

    /// Decision 45, the other side: with P's place held (case ii) nothing is lost, nothing is read-only, and the
    /// writers write as test 1 says.
    func testWithYourPlaceHeldNoGroupIsReadOnly() throws {
        let p = try founder("held", pClaim: F.x)
        XCTAssertFalse(try LedgerStore.Sync.projectGroups(p).groups.contains { $0.placeLost })
        XCTAssertEqual(try p.readOnlyChecked(), [:])
        XCTAssertEqual(try LedgerStore.Placing.load(p).lost, [])
        _ = try p.appendExpense(dinner(F.old))
        XCTAssertEqual(try JSONDecoder().decode(LedgerStore.ExpenseDTO.self,
                                                from: try last(p, LedgerStore.Sync.expenseType).blob).payer, F.x)
        // A stored mark still reads as before, and a stored mark wins over a lost place.
        let removed = LedgerStore.ReadOnly(kind: "removed", by: "Bo", at: Date(timeIntervalSince1970: 1))
        try p.markReadOnly(F.new, removed)
        XCTAssertEqual(try p.readOnlyChecked(), [F.new: removed])
        let lost = try FounderVault.holdOld(founder("held2", pClaim: nil))
        func stored() throws -> [String: LedgerStore.ReadOnly] {
            try JSONDecoder().decode([String: LedgerStore.ReadOnly].self,
                                     from: try XCTUnwrap(lost.kvGet(key: LedgerStore.readOnlyKey)))
        }
        try lost.markReadOnly(F.new, removed)
        XCTAssertEqual(try lost.readOnlyChecked(), [F.old: .placeLost, F.new: removed])
        XCTAssertEqual(try stored(), [F.new: removed], "a mark never writes the lost place down")
        try lost.markReadOnly(F.old, removed)
        XCTAssertEqual(try lost.readOnlyChecked(), [F.old: removed, F.new: removed])
        try lost.clearReadOnly(F.old)
        XCTAssertEqual(try lost.readOnlyChecked(), [F.old: .placeLost, F.new: removed],
                       "clearing a mark never clears a lost place")
        try lost.clearReadOnly(F.new)
        XCTAssertEqual(try stored(), [:], "nor does a clear write it down")
    }

    /// T3 M1: the import (`appendExpenses`) writes as the place: its record is paid by x in Old, its kv row by y.
    func testAnImportInOldIsWrittenAsThePlace() throws {
        let p = try founder("import", pClaim: F.x)
        let list = try p.appendExpenses([dinner(F.old)])
        XCTAssertEqual(list.last, dinner(F.old), "the kv ledger keeps the merged you")
        let written = try JSONDecoder().decode(LedgerStore.ExpenseDTO.self,
                                               from: try last(p, LedgerStore.Sync.expenseType).blob)
        XCTAssertEqual(written.payer, F.x)
        XCTAssertEqual(written.shares, [.init(id: F.x, amountMinor: 600), .init(id: F.c, amountMinor: 600)])
        XCTAssertEqual(written.addedBy, F.x)
    }

    /// T3 M1: `Removal.remove` (a group only on this phone) writes the redistribution's share to y as x.
    @MainActor
    func testARemovalInOldIsWrittenAsThePlace() async throws {
        let p = try founder("remove", pClaim: F.x)
        let old = try XCTUnwrap(LedgerStore.Sync.projectGroups(p).groups.first { $0.group.id == F.old })
        let moved = RedistributionInput(group: F.old, from: F.c, currencyCode: "INR",
                                        shares: [IdAmount(id: F.y, amountMinor: 600)])
        try await Removal.remove(store: p, group: NamedGroupWire(group: old.group, selfId: F.y, members: []),
                                 member: MemberWire(id: F.c, name: "Bo"), you: nil, byName: "Al",
                                 redistributions: [moved], shared: false)
        XCTAssertEqual(try ffiDecodeRedistribution(blob: try last(p, LedgerStore.Sync.redistributionType).blob),
                       RedistributionInput(group: F.old, from: F.c, currencyCode: "INR",
                                           shares: [IdAmount(id: F.x, amountMinor: 600)]))
        XCTAssertEqual(try ffiDecodeGroupOp(blob: try last(p, LedgerStore.Sync.groupOpType).blob),
                       .removeMember(groupId: F.old, memberId: F.c))
    }

    /// T3 M2: `SameEnds` only when the mapping made the two ends one id (Android's condition): y to y in Old
    /// becomes x to x and is refused; c to c and x to x were one id already and pass as they are.
    func testASettlementIsRefusedOnlyWhenTheMappingJoinedItsEnds() throws {
        let placing = try LedgerStore.Placing.load(try founder("ends", pClaim: F.x))
        func s(_ from: String, _ to: String) -> LedgerStore.SettlementDTO {
            LedgerStore.SettlementDTO(from: from, to: to, amountMinor: 100, currencyCode: "INR", group: F.old)
        }
        XCTAssertThrowsError(try placing.settlement(s(F.y, F.y))) {
            XCTAssertEqual($0 as? LedgerStore.Placing.SameEnds, LedgerStore.Placing.SameEnds())
        }
        XCTAssertThrowsError(try placing.settlement(s(F.y, F.x)))
        XCTAssertEqual(try placing.settlement(s(F.c, F.c)), s(F.c, F.c))
        XCTAssertEqual(try placing.settlement(s(F.x, F.x)), s(F.x, F.x))
        XCTAssertEqual(try placing.settlement(s(F.y, F.c)), s(F.x, F.c))
    }
}

/// D-229: the founder's scenario as phone P of account A holds it (core's `d229_on_p`, the same records and stamps):
/// y = hex(1) (phone Q's "you", the account's earliest), x = hex(2) (P's), c = hex(3) (account C); Old = hex(9) made
/// on P under x, New = hex(8) made on Q under y; phones Q 0x51, P 0x50, C 0x53. Android's twin: GroupRecordsTest.
enum FounderVault {
    static func hex(_ n: UInt8) -> String { String(repeating: "0", count: 30) + String(format: "%02x", n) }
    static let y = hex(1), x = hex(2), c = hex(3), old = hex(9), new = hex(8)

    static func claim(_ group: String, _ member: String, you: String, dev: UInt8) -> GroupOpIn {
        .claim(groupId: group, memberId: member, youId: you,
               devices: [ClaimDeviceIn(deviceId: hex(dev), agreementPublicHex: String(repeating: "ab", count: 32))])
    }

    /// The group-scope and personal records, applied as a pull would, then the rebuild. `pClaim`: the `you` of P's
    /// claim on x in Old (x: case ii; y: the claim the fixed ownClaims writes, case i; nil: none yet).
    static func make(_ store: LedgerStore, pClaim: String?) throws -> LedgerStore {
        func m(_ id: String, _ name: String) -> GroupOpMemberIn { GroupOpMemberIn(id: id, name: name) }
        var ops: [(GroupOpIn, UInt64, UInt8)] = [
            (.you(id: y, name: "Al"), 1_000, 0x51),
            (.you(id: x, name: "Al"), 2_000, 0x50),
            (.create(groupId: old, name: "Old", settlementCurrency: "INR", members: [m(x, "Al"), m(c, "Bo")]), 2_100, 0x50),
            (.create(groupId: new, name: "New", settlementCurrency: "INR", members: [m(y, "Al"), m(c, "Bo")]), 3_000, 0x51),
            (claim(old, c, you: c, dev: 0x53), 2_300, 0x53),
            (claim(new, c, you: c, dev: 0x53), 3_100, 0x53),
        ]
        if let pClaim { ops.append((claim(old, x, you: pClaim, dev: 0x50), 2_200, 0x50)) }
        for (op, millis, dev) in ops {
            _ = try store.applyRecord(try LedgerStore.Sync.groupOpRecord(
                op, hlc: HlcStamp(millis: millis, counter: 0), deviceId: hex(dev)))
        }
        try store.rebuildProjection()
        return store
    }

    /// D-229 decision 45: account D (d = hex(4), phone 0x54) claims x in Old at 2_400 (decision 36's race seen from
    /// P), so P's place there is held by another account. Use with `pClaim: nil`.
    static let d = hex(4)

    static func holdOld(_ store: LedgerStore) throws -> LedgerStore {
        _ = try store.applyRecord(try LedgerStore.Sync.groupOpRecord(
            claim(old, x, you: d, dev: 0x54), hlc: HlcStamp(millis: 2_400, counter: 0), deviceId: hex(0x54)))
        try store.rebuildProjection()
        return store
    }
}

private extension String {
    func repeated(_ n: Int) -> String { String(repeating: self, count: n) }
}
