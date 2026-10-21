import FirebaseFirestore
import UIKit
import XCTest
@testable import Skipti

/// D-211: your name and picture on your phones and in your groups. Android's
/// twin is ProfileSyncTest.
final class ProfileSyncTests: XCTestCase {
    private let me = String(repeating: "a", count: 32)
    private let device = String(repeating: "b", count: 32)
    private let other = String(repeating: "d", count: 32)
    private let gA = String(repeating: "1", count: 32)
    private let gB = String(repeating: "2", count: 32)
    private let gC = String(repeating: "3", count: 32)
    private let tim = String(repeating: "4", count: 32)
    private let bo = String(repeating: "5", count: 32)
    private let placeX = String(repeating: "6", count: 32)
    private let cy = String(repeating: "7", count: 32)
    private let jpegHead = "ffd8ffe0"

    private func tempStore() -> LedgerStore {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("profilesync-\(UUID().uuidString)", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return LedgerStore(path: dir.appendingPathComponent("vault.db").path)
    }

    private func rec(_ op: GroupOpIn, group: String?, device: String? = nil, millis: UInt64 = 1) throws -> RecordWire {
        let d = device ?? other
        return RecordWire(
            version: 1, recordType: LedgerStore.Sync.groupOpType, groupId: group, epoch: 0, hlcMillis: millis,
            hlcCounter: 0, hlcDeviceId: d, authorDeviceId: d, nonceSession: Data(), flags: 0,
            blob: try ffiEncodeGroupOp(op: op))
    }

    private func profileOp(_ group: String, _ member: String, name: String = "Al") -> GroupOpIn {
        .memberProfile(groupId: group, memberId: member, name: name, emoji: "🦊", thumbHex: nil)
    }

    /// A vault that holds: this account's you; group A it made (it is the
    /// place); group B made by Tim, which it joined as `placeX`; group C it
    /// was taken out of.
    private func fixture() throws -> LedgerStore {
        let store = tempStore()
        func members(_ ids: [(String, String)]) -> [GroupOpMemberIn] { ids.map { GroupOpMemberIn(id: $0.0, name: $0.1) } }
        try LedgerStore.Sync.appendGroupOps(store, [
            .you(id: me, name: "Al"),
            .create(groupId: gA, name: "Goa", settlementCurrency: "USD", members: members([(me, "Al"), (bo, "Bo")])),
            .create(groupId: gB, name: "Trip", settlementCurrency: "USD", members: members([(tim, "Tim"), (placeX, "Al on Tim's list")])),
            Invites.claim(group: gB, member: placeX, you: me, device: device),
            .create(groupId: gC, name: "Old", settlementCurrency: "USD", members: members([(me, "Al"), (cy, "Cy")])),
            .removeMember(groupId: gC, memberId: me),
        ], placing: .none)
        return store
    }

    private func key(_ places: [ProfileShare.Place]) -> Set<String> { Set(places.map { $0.groupId + "/" + $0.memberId }) }

    // MARK: - What a group's log may bring in

    func testAnUnmarkedMemberProfileIsRefusedAndAMarkedOneComesIn() throws {
        let theirs = try rec(profileOp(gA, bo), group: gA)
        func refusal(_ r: RecordWire, profile: Bool = false, from group: String? = nil) -> CloudRecords.Refusal? {
            CloudRecords.refusal(r, profile: profile, from: group ?? gA, thisDevice: device)
        }
        XCTAssertEqual(refusal(theirs), .unverifiedProfile, "a phone wrote it, not the Function")
        XCTAssertNil(refusal(theirs, profile: true))
        XCTAssertEqual(refusal(try rec(profileOp(gB, bo), group: gA), profile: true), .wrongGroup, "marked, but another group's")
        // This phone built it and never kept it: it comes back marked, and only marked.
        let mine = try rec(profileOp(gA, me), group: gA, device: device)
        XCTAssertNil(refusal(mine, profile: true), "this phone's own, marked")
        XCTAssertEqual(refusal(mine), .thisDevice, "claiming to be this phone's, unmarked")
        // The mark does not excuse another kind of record from this phone.
        let renamed = try rec(.renameMember(groupId: gA, memberId: bo, name: "X"), group: gA, device: device)
        XCTAssertEqual(refusal(renamed, profile: true), .thisDevice)
    }

    func testAnAvatarInAGroupLogIsRefusedAsPersonal() throws {
        let avatar = try rec(.avatar(emoji: "🦊", photoHex: nil), group: gA)
        XCTAssertEqual(CloudRecords.refusal(avatar, from: gA, thisDevice: device), .personal)
        XCTAssertEqual(CloudRecords.refusal(avatar, profile: true, from: gA, thisDevice: device), .personal,
                       "the mark makes no difference to a personal operation")
    }

    /// D-244 (ADR-0043): a setting is personal: written with no group, refused from a group's log (marked or not), at
    /// home in the account's own. Twin: ProfileSyncTest.a_setting_is_personal.
    func testASettingIsPersonal() throws {
        let op = GroupOpIn.setting(key: "appearance.theme", value: "norse")
        XCTAssertNil(LedgerStore.Sync.scope(of: op))
        XCTAssertNil(try LedgerStore.Sync.groupOpRecord(op, hlc: HlcStamp(millis: 1000, counter: 0), deviceId: device).groupId)
        let inGroup = try rec(op, group: gA)
        XCTAssertEqual(CloudRecords.refusal(inGroup, from: gA, thisDevice: device), .personal)
        XCTAssertEqual(CloudRecords.refusal(inGroup, profile: true, from: gA, thisDevice: device), .personal,
                       "the mark makes no difference to a personal operation")
        XCTAssertNil(CloudRecords.personalRefusal(try rec(op, group: nil)))
    }

    func testAMemberProfileInThePersonalLogIsRefused() throws {
        let profile = try rec(profileOp(gA, me), group: nil)
        XCTAssertEqual(CloudRecords.personalRefusal(profile), .wrongScope)
        XCTAssertNil(CloudRecords.personalRefusal(try rec(.avatar(emoji: "🦊", photoHex: nil), group: nil)))
        XCTAssertNil(CloudRecords.personalRefusal(try rec(.you(id: me, name: "Al"), group: nil)))
        // Through absorb: a personal document carrying one brings nothing in, marked or not.
        var document = CloudRecords.document(profile, uid: "u")
        document["createdAt"] = Timestamp(seconds: 1, nanoseconds: 0)
        document["profile"] = true
        let store = tempStore()
        XCTAssertEqual(try CloudRecords.absorb([document], groupId: nil, store: store).count, 0)
        XCTAssertEqual(try store.allRecords().count, 0)
    }

    // MARK: - The places

    func testThePlacesAreTheClaimedOnesAndTheCreatorsOwn() throws {
        let store = try fixture()
        let p = try LedgerStore.Sync.projectGroups(store)
        XCTAssertEqual(key(ProfileShare.places(p, readOnly: [], known: [:])), [gA + "/" + me, gB + "/" + placeX],
                       "the creator's own id, the claimed person; none for the group it was removed from")
        XCTAssertEqual(key(ProfileShare.places(p, readOnly: [gA], known: [:])), [gB + "/" + placeX], "a read-only copy takes nothing")
        // No account's "you" yet: no places.
        XCTAssertEqual(ProfileShare.places(try LedgerStore.Sync.projectGroups(tempStore()), readOnly: [], known: [:]).count, 0)
    }

    /// D-227 (Design 7): the place a group's cloud says is this account's wins; without one, `placeFor` over every
    /// id of the account. Twins: ProfileSyncTest.kt.
    func testTheCloudsPlaceForYouIsTheOneShared() throws {
        let store = try fixture()
        let p = try LedgerStore.Sync.projectGroups(store)
        let q = String(repeating: "8", count: 32)
        XCTAssertEqual(key(ProfileShare.places(p, readOnly: [], known: [gA: [q]])), [gA + "/" + q, gB + "/" + placeX],
                       "the cloud's place for A; B is not known, so the claimed place")
        XCTAssertEqual(key(ProfileShare.places(p, readOnly: [], known: [gB: [q, placeX]])), [gA + "/" + me, gB + "/" + placeX],
                       "several: the one the claim picks, when listed")
        XCTAssertEqual(key(ProfileShare.places(p, readOnly: [], known: [gA: []])), [gA + "/" + me, gB + "/" + placeX],
                       "an empty list is not known")
        XCTAssertEqual(key(ProfileShare.places(p, readOnly: [], known: [gC: [me]])), [gA + "/" + me, gB + "/" + placeX],
                       "a group the account was taken out of stays out")
        XCTAssertEqual(key(ProfileShare.places(p, readOnly: [gA], known: [gA: [q]])), [gB + "/" + placeX], "a copy stays out")
    }

    // MARK: - The records

    func testTheRecordsAreOnePerPlaceAndNeverInTheVault() throws {
        let store = try fixture()
        let before = try store.allRecords().count
        let places = ProfileShare.places(try LedgerStore.Sync.projectGroups(store), readOnly: [], known: [:])
        let records = try ProfileShare.records(store: store, places: places, name: "Al", emoji: "🦊", thumbHex: nil)
        XCTAssertEqual(records.count, places.count)
        XCTAssertEqual(try store.allRecords().count, before, "never put into the vault")
        for (record, place) in zip(records, places) {
            XCTAssertEqual(record.recordType, LedgerStore.Sync.groupOpType)
            XCTAssertEqual(record.groupId, place.groupId, "group scope")
            guard case .memberProfile(let g, let m, let name, let emoji, let thumb) = try ffiDecodeGroupOp(blob: record.blob)
            else { return XCTFail("not a profile") }
            XCTAssertEqual([g, m, name, emoji ?? "-", thumb ?? "-"], [place.groupId, place.memberId, "Al", "🦊", "-"])
        }
        XCTAssertEqual(Set(records.map(CloudRecords.documentId)).count, records.count, "each its own identity")
        // The call's data: the D-210 record shape, one entry per record.
        let data = ProfileShare.callData(records)
        let sent = try XCTUnwrap(data["records"] as? [[String: Any]])
        XCTAssertEqual(sent.map { $0["id"] as? String }, records.map(CloudRecords.documentId))
        XCTAssertEqual(sent.first?["type"] as? String, "3")
        XCTAssertEqual(sent.first?["deviceId"] as? String, records.first?.authorDeviceId)
        XCTAssertEqual((sent.first?["payload"] as? [String: Any])?["blob"] as? String, records.first?.blob.base64EncodedString())
        // A name or preview core refuses (an emoji with a photo) builds nothing.
        XCTAssertThrowsError(try ProfileShare.records(store: store, places: places, name: "Al", emoji: "🦊", thumbHex: jpegHead))
    }

    func testTheCloudsAnswersDecideWhatTheJobDoes() {
        XCTAssertEqual(ProfileShare.answer(nil), .done)
        XCTAssertEqual(ProfileShare.answer(CloudFunctions.Failure.refused("FAILED_PRECONDITION")), .nameChanged)
        XCTAssertEqual(ProfileShare.answer(CloudFunctions.Failure.refused("INVALID_ARGUMENT")), .done)
        XCTAssertEqual(ProfileShare.answer(CloudFunctions.Failure.refused("UNAVAILABLE")), .later)
        XCTAssertEqual(ProfileShare.answer(CloudFunctions.Failure.refused("PERMISSION_DENIED")), .later)
        XCTAssertEqual(ProfileShare.answer(CloudFunctions.Failure.offline), .later)
        XCTAssertEqual(ProfileShare.answer(CloudFunctions.Failure.signedOut), .later)
    }

    // MARK: - The picture as a record

    func testTheMigrationWritesOneAvatarRecordOnce() throws {
        func avatarRecords(_ store: LedgerStore) throws -> Int {
            try store.allRecords().filter {
                if case .avatar? = try? ffiDecodeGroupOp(blob: $0.blob) { return true }
                return false
            }.count
        }
        let store = tempStore()
        try store.kvSet(key: LedgerStore.avatarKey, value: try Avatar.emoji("🦊").encoded())
        XCTAssertTrue(try LedgerStore.Sync.migrateAvatarOnce(store))
        XCTAssertFalse(try LedgerStore.Sync.migrateAvatarOnce(store), "the second run writes nothing")
        XCTAssertEqual(try avatarRecords(store), 1)
        XCTAssertEqual(try LedgerStore.Sync.projectGroups(store).avatar, GroupPictureOut(emoji: "🦊", photoHex: nil))
        // A rebuild never wipes it, and writes no second record.
        try store.rebuildProjection()
        XCTAssertEqual(try store.loadAvatar(), .emoji("🦊"))
        XCTAssertEqual(try avatarRecords(store), 1)
        // No picture kept: the flag is set and nothing is written.
        let empty = tempStore()
        XCTAssertFalse(try LedgerStore.Sync.migrateAvatarOnce(empty))
        XCTAssertEqual(try avatarRecords(empty), 0)
        XCTAssertNotNil(try empty.kvGet(key: LedgerStore.Sync.avatarRecordKey))
    }

    func testARebuildBeforeTheMigrationKeepsAKeptPicture() throws {
        let store = tempStore()
        let jpeg = Data([0xFF, 0xD8, 0xFF, 0xE0, 1, 2, 3])
        try store.kvSet(key: LedgerStore.avatarKey, value: try Avatar.photo(jpeg).encoded())
        // The flag is not set yet and the records hold no picture: kv stays.
        let p = try LedgerStore.Sync.projectGroups(store)
        XCTAssertNil(p.avatar)
        try store.kvSet(key: LedgerStore.Sync.avatarRecordKey, value: Data())
        try LedgerStore.Sync.rebuildGroups(store)
        XCTAssertEqual(try store.loadAvatar(), .photo(jpeg), "migrated by the rebuild, never wiped")
    }

    /// D-211 fix 6: a kept photo above the cap that cannot be stepped down
    /// (not an image), so the migration throws and the rebuild ignores it. The
    /// rebuild's kv guard must then leave the picture alone and the flag unset.
    func testARebuildNeverWipesAKeptPictureItCouldNotMigrate() throws {
        let store = tempStore()
        let unreadable = Data([0xFF, 0xD8, 0xFF, 0xE0]) + Data(repeating: 7, count: ProfileRules.photoMaxBytes + 1)
        try store.kvSet(key: LedgerStore.avatarKey, value: try Avatar.photo(unreadable).encoded())
        XCTAssertThrowsError(try LedgerStore.Sync.migrateAvatarOnce(store), "it cannot be stepped down")
        XCTAssertNil(try store.kvGet(key: LedgerStore.Sync.avatarRecordKey))
        try LedgerStore.Sync.rebuildGroups(store)
        XCTAssertEqual(try store.loadAvatar(), .photo(unreadable), "never wiped")
        let flag = try store.kvGet(key: LedgerStore.Sync.avatarRecordKey)
        XCTAssertTrue(flag == nil || flag?.isEmpty == true, "the migration has still not run")
    }

    func testAPictureReachesAnotherPhoneAndClearingReachesItToo() throws {
        let a = tempStore(), b = tempStore()
        let jpeg = Data([0xFF, 0xD8, 0xFF, 0xE0, 9, 8, 7])
        try a.saveAvatar(.photo(jpeg))
        XCTAssertEqual(try a.loadAvatar(), .photo(jpeg))
        _ = try CloudRecords.apply(try a.allRecords(), store: b)
        try b.rebuildProjection()
        XCTAssertEqual(try b.loadAvatar(), .photo(jpeg), "the other phone shows it, from the records")
        try a.saveAvatar(.none)
        _ = try CloudRecords.apply(try a.allRecords(), store: b)
        try b.rebuildProjection()
        XCTAssertEqual(try b.loadAvatar(), .none)
        // Core refuses a photo that is not a JPEG: nothing is written.
        XCTAssertThrowsError(try a.saveAvatar(.photo(Data([1, 2, 3]))))
        XCTAssertEqual(try a.loadAvatar(), .none)
    }

    func testEveryOfferedEmojiIsAnAcceptedPicture() throws {
        for emoji in ProfileRules.emojiChoices {
            XCTAssertNoThrow(try ffiEncodeGroupOp(op: .avatar(emoji: emoji, photoHex: nil)), emoji)
        }
    }

    // MARK: - The preview

    func testTheThumbIsAtMost12000BytesAnd120Pixels() throws {
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        let big = UIGraphicsImageRenderer(size: CGSize(width: 2000, height: 1500), format: format).image { context in
            for stripe in 0..<150 {
                UIColor(hue: CGFloat(stripe) / 150, saturation: 0.8, brightness: 0.9, alpha: 1).setFill()
                context.fill(CGRect(x: 0, y: stripe * 10, width: 2000, height: 10))
            }
        }
        let jpeg = try XCTUnwrap(big.jpegData(compressionQuality: 0.9))
        XCTAssertGreaterThan(jpeg.count, 12_000, "the input is large")
        let thumb = try XCTUnwrap(ProfileThumb.make(from: jpeg))
        XCTAssertLessThanOrEqual(thumb.count, 12_000)
        let cg = try XCTUnwrap(UIImage(data: thumb)?.cgImage)
        XCTAssertEqual([cg.width, cg.height], [120, 120])
        XCTAssertEqual(Array(thumb.prefix(3)), [0xFF, 0xD8, 0xFF], "core takes it as a JPEG")
        XCTAssertNil(ProfileThumb.make(from: Data("not an image".utf8)))
        // The share: a photo becomes a preview, an emoji stays an emoji, nothing stays nothing.
        let hex = HexCodec.encode(jpeg)
        XCTAssertEqual(ProfileShare.preview(of: GroupPictureOut(emoji: "🦊", photoHex: nil)).emoji, "🦊")
        let preview = ProfileShare.preview(of: GroupPictureOut(emoji: nil, photoHex: hex))
        XCTAssertNil(preview.emoji)
        XCTAssertEqual(try HexCodec.decode(try XCTUnwrap(preview.thumbHex)), thumb)
        XCTAssertNil(ProfileShare.preview(of: nil).thumbHex)
        XCTAssertNil(ProfileShare.preview(of: GroupPictureOut(emoji: nil, photoHex: "ffd8ff")).thumbHex, "unreadable: name only")
    }

    func testAStoredPhotoStepsDownUntilItFits() throws {
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        let source = UIGraphicsImageRenderer(size: CGSize(width: 600, height: 400), format: format).image { context in
            UIColor.systemTeal.setFill()
            context.fill(CGRect(x: 0, y: 0, width: 600, height: 400))
        }
        let stored = try XCTUnwrap(ProfileRules.storedPhoto(from: try XCTUnwrap(source.jpegData(compressionQuality: 0.9))))
        XCTAssertLessThanOrEqual(stored.count, ProfileRules.photoMaxBytes)
        let cg = try XCTUnwrap(UIImage(data: stored)?.cgImage)
        XCTAssertEqual([cg.width, cg.height], [256, 256])
    }

    // MARK: - The job's flags

    func testTheBackfillRequestsOnce() throws {
        let store = tempStore()
        XCTAssertFalse(ProfileShare.isPending(store))
        XCTAssertTrue(try ProfileShare.backfillOnce(store), "the first sync start asks")
        XCTAssertTrue(ProfileShare.isPending(store))
        try ProfileShare.setPending(store, false)
        XCTAssertFalse(try ProfileShare.backfillOnce(store), "never again on this install")
        XCTAssertFalse(ProfileShare.isPending(store))
    }

    // MARK: - A joiner's own profile

    func testAJoinersOwnProfileShowsUnderTheirOwnRow() throws {
        let store = try fixture()
        _ = try store.ensureSelf()
        // The profile the Function wrote for the place this account claimed in B, back through B's log.
        let records = try ProfileShare.records(
            store: store, places: [(gB, placeX)], name: "Al Real", emoji: "🦊", thumbHex: nil)
        let before = try store.allRecords().count
        var unmarked = CloudRecords.document(records[0], uid: "u")
        unmarked["createdAt"] = Timestamp(seconds: 1, nanoseconds: 0)
        XCTAssertEqual(try CloudRecords.absorb([unmarked], groupId: gB, store: store).count, 0, "unmarked: refused")
        var marked = unmarked
        marked["profile"] = true
        XCTAssertEqual(try CloudRecords.absorb([marked], groupId: gB, store: store).count, 1,
                       "marked, from this very phone: it comes in")
        XCTAssertEqual(try store.allRecords().count, before + 1)
        let group = try XCTUnwrap(try LedgerStore.Sync.projectGroups(store).groups.first { $0.group.id == gB })
        XCTAssertEqual(group.profiles.map(\.memberId), [me], "under this account's shown id")
        XCTAssertEqual(group.profiles.first?.emoji, "🦊")
        let named = try XCTUnwrap(try store.namedGroups().first { $0.group.id == gB })
        XCTAssertEqual(named.members.first { $0.id == me }?.name, "Al Real", "their own name, not the typed one")
        XCTAssertEqual(try store.memberPreviews()[gB]?[me], .emoji("🦊"))
        // A name only is a profile with no picture.
        XCTAssertNil(try store.memberPreviews()[gA])
    }

    // MARK: - The job (Fix 1): never loops, one pass per trigger

    private let gD = String(repeating: "8", count: 32)
    private let placeY = String(repeating: "9", count: 32)
    private var sent: [[String]] = []
    private var answers: [ProfileShare.Answer] = []
    private var ensured = 0
    private var nameOutcome = ProfileShare.NameOutcome.ok
    private var during: (() -> Void)?
    private var suspends = true
    /// What each sent record carried (the final review): name, emoji, thumb.
    private var shared: [(name: String, emoji: String?, thumbHex: String?)] = []
    /// The test's own vault: the fakes answer it only, so a run of any other
    /// vault (the host app's, another test's) never counts here.
    private var ownPath = ""
    private var ownDevice = ""
    /// Decision 40: the waits a failed call's timed retries asked for; they fire only when a test says so (else the
    /// timer ends at once), and `duringWait` runs inside the wait.
    private var waited: [TimeInterval] = []
    private var fireRetries = false
    private var duringWait: (() -> Void)?

    @MainActor
    private func fakeTheCloud() {
        sent = []
        shared = []
        answers = []
        ensured = 0
        nameOutcome = .ok
        during = nil
        suspends = true
        waited = []
        fireRetries = false
        duringWait = nil
        ProfileShare.retryWait = { [unowned self] seconds in
            waited.append(seconds)
            if let hook = duringWait {
                duringWait = nil
                hook()
            }
            await Task.yield()
            return fireRetries
        }
        ProfileShare.account = { [unowned self] store in store.path == ownPath ? "uid-test" : nil }
        ProfileShare.ensure = { [unowned self] _, _ in
            ensured += 1
            return nameOutcome
        }
        ProfileShare.send = { [unowned self] data in
            let records = (data["records"] as? [[String: Any]]) ?? []
            guard records.first?["deviceId"] as? String == ownDevice else { return .done }
            sent.append(records.compactMap { $0["id"] as? String })
            for record in records {
                guard let payload = record["payload"] as? [String: Any], let text = payload["blob"] as? String,
                      let blob = Data(base64Encoded: text),
                      case .memberProfile(_, _, let name, let emoji, let thumbHex)? = try? ffiDecodeGroupOp(blob: blob)
                else { continue }
                shared.append((name, emoji, thumbHex))
            }
            if let hook = during {
                during = nil
                hook()
            }
            // A real call suspends; a test may also run it straight through.
            if suspends { await Task.yield() }
            return answers.isEmpty ? .done : answers.removeFirst()
        }
    }

    @MainActor
    private func restoreTheCloud() {
        ProfileShare.send = ProfileShare.realSend
        ProfileShare.ensure = ProfileShare.realEnsure
        ProfileShare.account = ProfileShare.realAccount
        ProfileShare.retryWait = ProfileShare.realRetryWait
    }

    /// Lets every task the job started finish: the test vault's runs are
    /// counted (`ProfileShare.inFlight`), and part of a pass runs off the
    /// main actor (M6), so a fixed wait alone is not enough.
    @MainActor
    private func settle() async {
        for _ in 0..<50 { await Task.yield() }
        try? await Task.sleep(nanoseconds: 50_000_000)
        for _ in 0..<500 where ProfileShare.inFlight(ownPath) > 0 {
            try? await Task.sleep(nanoseconds: 10_000_000)
        }
        for _ in 0..<50 { await Task.yield() }
    }

    /// The fixture, with this account's name in `ledger/self` and the
    /// backfill already done (so a sync start only runs what is pending).
    @MainActor
    private func jobFixture() throws -> LedgerStore {
        let store = try fixture()
        try own(store)
        try store.rebuildProjection()
        XCTAssertEqual(try store.loadSelf()?.name, "Al")
        try store.kvSet(key: ProfileShare.backfilledKey, value: Data("1".utf8))
        return store
    }

    /// Also marks the vault caught up (its personal listener's first
    /// snapshot is in), as the app does once it has its own records.
    @MainActor
    private func own(_ store: LedgerStore) throws {
        ownPath = store.path
        ownDevice = try LedgerStore.Sync.deviceId(store)
        ProfileShare.markCaughtUp(store)
    }

    /// The share's records as the group's listener brings them back: marked.
    private func returning(_ ids: [String], built records: [RecordWire]) -> [String: [[String: Any]]] {
        var byGroup: [String: [[String: Any]]] = [:]
        for (i, record) in records.enumerated() where ids.contains(CloudRecords.documentId(record)) {
            var document = CloudRecords.document(record, uid: "uid-test")
            document["createdAt"] = Timestamp(seconds: Int64(100 + i), nanoseconds: 0)
            document["profile"] = true
            byGroup[record.groupId ?? "", default: []].append(document)
        }
        return byGroup
    }

    @MainActor
    func testARetryAfterAFailureResendsTheSameRecords() async throws {
        fakeTheCloud()
        defer { restoreTheCloud() }
        let store = try jobFixture()
        answers = [.later]
        await ProfileShare.request(store: store).value
        XCTAssertEqual(sent.count, 1)
        XCTAssertEqual(sent.first?.count, 2, "one record per place")
        XCTAssertTrue(ProfileShare.isPending(store), "a failure keeps it for the next sync start")
        await ProfileShare.syncStarted(store: store).value
        XCTAssertEqual(sent.count, 2)
        guard sent.count == 2 else { return }
        XCTAssertEqual(sent[1], sent[0], "the same records, the same ids")
        XCTAssertFalse(ProfileShare.isPending(store))
        await settle()
        XCTAssertEqual(sent.count, 2, "and nothing more")
    }

    @MainActor
    func testAFailedPreconditionRebuildsOnce() async throws {
        fakeTheCloud()
        defer { restoreTheCloud() }
        let store = try jobFixture()
        answers = [.nameChanged, .done]
        await ProfileShare.request(store: store).value
        XCTAssertEqual(sent.count, 2, "the name ensured again, then one more call")
        guard sent.count == 2 else { return }
        XCTAssertTrue(Set(sent[0]).isDisjoint(with: sent[1]), "rebuilt: new records")
        XCTAssertEqual(ensured, 2)
        XCTAssertFalse(ProfileShare.isPending(store))
        // Refused twice: the second refusal is the answer, never a third call.
        answers = [.nameChanged, .nameChanged]
        await ProfileShare.request(store: store).value
        XCTAssertEqual(sent.count, 4)
        XCTAssertFalse(ProfileShare.isPending(store))
        await settle()
        XCTAssertEqual(sent.count, 4)
    }

    @MainActor
    func testMoreThanFiftyPlacesGoInChunksOfFifty() async throws {
        fakeTheCloud()
        defer { restoreTheCloud() }
        let store = tempStore()
        let groups = (0..<51).map { String(format: "%032x", 0xA000 + $0) }
        try LedgerStore.Sync.appendGroupOps(store, [.you(id: me, name: "Al")] + groups.map {
            .create(groupId: $0, name: "G", settlementCurrency: "USD",
                    members: [GroupOpMemberIn(id: me, name: "Al"), GroupOpMemberIn(id: bo, name: "Bo")])
        }, placing: .none)
        try store.rebuildProjection()
        try own(store)
        try store.kvSet(key: ProfileShare.backfilledKey, value: Data("1".utf8))
        await ProfileShare.request(store: store).value
        XCTAssertEqual(sent.map(\.count), [50, 1])
        XCTAssertEqual(Set(sent.flatMap { $0 }).count, 51)
        XCTAssertFalse(ProfileShare.isPending(store))
    }

    /// The loop the review found: a share writes new marked records, they come
    /// back through the group's listener, and that must never share again,
    /// even while a joined group has not arrived (it never may: deleted).
    @MainActor
    func testTheShareNeverRunsAgainOnItsOwnReturningRecords() async throws {
        fakeTheCloud()
        defer { restoreTheCloud() }
        let store = try jobFixture()
        // Joined D (claimed placeY); D's create is here but the addMember
        // that makes placeY has not arrived (and may never: D is deleted).
        let timsPhone = tempStore()
        try LedgerStore.Sync.appendGroupOps(timsPhone, [.create(
            groupId: gD, name: "Ski", settlementCurrency: "USD", members: [GroupOpMemberIn(id: tim, name: "Tim")])], placing: .none)
        _ = try CloudRecords.apply(try timsPhone.allRecords(), store: store)
        try LedgerStore.Sync.appendGroupOps(store, [Invites.claim(group: gD, member: placeY, you: me, device: device)], placing: .none)
        try store.rebuildProjection()
        XCTAssertEqual(try store.waitingGroups().map(\.id), [gD], "the group is here, its place is not")
        await ProfileShare.joined(group: gD, store: store).value
        XCTAssertEqual(sent.count, 1)
        XCTAssertEqual(ProfileShare.waiting(store), [gD])
        XCTAssertFalse(ProfileShare.isPending(store), "every place found was shared to, whatever is waiting")
        // Build what was sent (the same path) to feed it back marked.
        let places = ProfileShare.places(try LedgerStore.Sync.projectGroups(store), readOnly: [], known: [:])
        let copies = try ProfileShare.records(store: store, places: places, name: "Al", emoji: nil, thumbHex: nil)
        for (group, documents) in returning(copies.map(CloudRecords.documentId), built: copies) {
            CloudSync.shared.receive(documents, groupId: group, store: store, fromCache: false)
        }
        XCTAssertEqual(try LedgerStore.Sync.projectGroups(store).groups.first { $0.group.id == gA }?.profiles.count, 1,
                       "they came in")
        XCTAssertNil(ProfileShare.arrived(groupId: gA, store: store), "a group that is not waiting triggers nothing")
        await settle()
        XCTAssertEqual(sent.count, 1, "no share on its own returning records")
        XCTAssertEqual(ProfileShare.waiting(store), [gD])
    }

    @MainActor
    func testAWaitingGroupTriggersOneShareWhenItArrives() async throws {
        fakeTheCloud()
        defer { restoreTheCloud() }
        let store = try jobFixture()
        try LedgerStore.Sync.appendGroupOps(store, [Invites.claim(group: gD, member: placeY, you: me, device: device)], placing: .none)
        await ProfileShare.joined(group: gD, store: store).value
        XCTAssertEqual(sent.count, 1)
        XCTAssertEqual(ProfileShare.waiting(store), [gD])
        // Something else of D's arrives first, before the place: nothing yet.
        // Then Tim's create that names placeY.
        // Tim's phone writes it (a real record, its own device).
        let timsPhone = tempStore()
        try LedgerStore.Sync.appendGroupOps(timsPhone, [.create(groupId: gD, name: "Ski", settlementCurrency: "USD", members: [
            GroupOpMemberIn(id: tim, name: "Tim"), GroupOpMemberIn(id: placeY, name: "Al on Tim's list"),
        ])], placing: .none)
        let create = try XCTUnwrap(try timsPhone.allRecords().first { $0.groupId == gD })
        var document = CloudRecords.document(create, uid: "tim")
        document["createdAt"] = Timestamp(seconds: 50, nanoseconds: 0)
        let before = try store.allRecords().count
        CloudSync.shared.receive([document], groupId: gD, store: store, fromCache: false)
        XCTAssertEqual(try store.allRecords().count, before + 1, "the create came in")
        XCTAssertTrue(ProfileShare.waiting(store).isEmpty,
                      "out of the set at once, before the share runs: a second arrival cannot ask again")
        XCTAssertTrue(ProfileShare.places(try LedgerStore.Sync.projectGroups(store), readOnly: [], known: [:])
            .contains { $0.groupId == gD }, "the place is here now")
        await settle()
        XCTAssertEqual(sent.count, 2, "exactly one share when it arrives")
        XCTAssertEqual(sent.last?.count, 3, "to every place, D's now among them")
        XCTAssertTrue(ProfileShare.waiting(store).isEmpty)
        XCTAssertFalse(ProfileShare.isPending(store))
        // D's later records (its own profile coming back among them) trigger nothing.
        try LedgerStore.Sync.appendGroupOps(timsPhone, [.renameMember(groupId: gD, memberId: tim, name: "Timo")], placing: .none)
        let more = try XCTUnwrap(try timsPhone.allRecords().last { $0.groupId == gD && $0 != create })
        var later = CloudRecords.document(more, uid: "tim")
        later["createdAt"] = Timestamp(seconds: 60, nanoseconds: 0)
        let count = try store.allRecords().count
        CloudSync.shared.receive([later], groupId: gD, store: store, fromCache: false)
        XCTAssertEqual(try store.allRecords().count, count + 1, "it came in")
        await settle()
        XCTAssertEqual(sent.count, 2)
    }

    @MainActor
    func testAWaitingGroupThatIsDeletedLeavesTheSet() async throws {
        fakeTheCloud()
        defer { restoreTheCloud() }
        let store = try jobFixture()
        let gE = String(repeating: "e", count: 32), gF = String(repeating: "f", count: 32)
        for g in [gD, gE, gF] { await ProfileShare.joined(group: g, store: store).value }
        XCTAssertEqual(ProfileShare.waiting(store), [gD, gE, gF])
        try store.markReadOnly(gD, LedgerStore.ReadOnly(kind: "deleted", by: "Tim", at: Date()))
        XCTAssertEqual(ProfileShare.waiting(store), [gE, gF], "deleted")
        try store.markReadOnly(gE, LedgerStore.ReadOnly(kind: "removed", by: "Tim", at: Date()))
        XCTAssertEqual(ProfileShare.waiting(store), [gF], "removed")
        try store.forgetGroup(gF)
        XCTAssertTrue(ProfileShare.waiting(store).isEmpty, "forgotten on this phone")
    }

    @MainActor
    func testATriggerDuringARunCausesExactlyOneMorePass() async throws {
        fakeTheCloud()
        defer { restoreTheCloud() }
        for suspending in [true, false] {
            sent = []
            suspends = suspending
            let store = try jobFixture()
            var inner: [Task<Void, Never>] = []
            // Two triggers while the first call is out: one more pass, not two.
            during = { inner = [ProfileShare.request(store: store), ProfileShare.request(store: store)] }
            await ProfileShare.request(store: store).value
            for task in inner { await task.value }
            await settle()
            XCTAssertEqual(sent.count, 2, "suspending: \(suspending)")
            XCTAssertFalse(ProfileShare.isPending(store))
        }
    }

    @MainActor
    func testARefusedNameKeepsTheSharePendingWithoutARetry() async throws {
        fakeTheCloud()
        defer { restoreTheCloud() }
        let store = try jobFixture()
        nameOutcome = .refused
        await ProfileShare.request(store: store).value
        await settle()
        XCTAssertEqual(ensured, 1, "no immediate retry")
        XCTAssertEqual(sent.count, 0)
        XCTAssertTrue(ProfileShare.isPending(store))
    }

    func testANameIsCountedAsTheRulesCountIt() {
        XCTAssertEqual(ProfileShare.shareable(" Al "), "Al")
        XCTAssertNil(ProfileShare.shareable("  "))
        XCTAssertNil(ProfileShare.shareable(nil))
        XCTAssertNotNil(ProfileShare.shareable(String(repeating: "x", count: 60)))
        XCTAssertNil(ProfileShare.shareable(String(repeating: "x", count: 61)))
        // An emoji is two UTF-16 units: 30 fit, 31 do not.
        XCTAssertNotNil(ProfileShare.shareable(String(repeating: "🦊", count: 30)))
        XCTAssertNil(ProfileShare.shareable(String(repeating: "🦊", count: 31)))
        // The account's name is cut at a whole character within 60 units.
        XCTAssertEqual(CloudProfile.cloudName(String(repeating: "🦊", count: 40)), String(repeating: "🦊", count: 30))
        XCTAssertEqual(CloudProfile.cloudName("x" + String(repeating: "🦊", count: 40))?.utf16.count, 59)
        // The rules refusing the name is not "offline".
        let denied = NSError(domain: FirestoreErrorDomain, code: FirestoreErrorCode.permissionDenied.rawValue)
        XCTAssertEqual(ProfileShare.nameOutcome(denied), .refused)
        XCTAssertEqual(ProfileShare.nameOutcome(NSError(domain: FirestoreErrorDomain,
                                                        code: FirestoreErrorCode.unavailable.rawValue)), .later)
        XCTAssertEqual(ProfileShare.nameOutcome(CloudProfile.NameError.noProfile), .later)
    }

    func testAnExportNeverFailsBecauseTheProfileCannotBeProjected() {
        struct Broken: Error {}
        XCTAssertEqual(LedgerStore.exportedPicture(projected: { throw Broken() }, kept: .emoji("🐼")), .emoji("🐼"),
                       "the kv copy stands in")
        XCTAssertEqual(LedgerStore.exportedPicture(projected: { GroupPictureOut(emoji: "🦊", photoHex: nil) },
                                                   kept: .emoji("🐼")), .emoji("🦊"), "the records win")
        XCTAssertEqual(LedgerStore.exportedPicture(projected: { nil }, kept: .emoji("🐼")), .emoji("🐼"),
                       "not migrated yet: kv")
    }

    // MARK: - The migration never brings back a cleared picture

    func testTheMigrationNeverBringsBackAClearedPicture() throws {
        // Another phone of this account cleared the picture; this phone, not
        // migrated yet, still holds the old one in kv.
        let elsewhere = tempStore()
        try LedgerStore.Sync.appendGroupOps(elsewhere, [.avatar(emoji: nil, photoHex: nil)], placing: .none)
        let store = tempStore()
        try store.kvSet(key: LedgerStore.avatarKey, value: try Avatar.emoji("🦊").encoded())
        _ = try CloudRecords.apply(try elsewhere.allRecords(), store: store)
        let before = try store.allRecords().count
        XCTAssertFalse(try LedgerStore.Sync.migrateAvatarOnce(store), "a picture record exists: nothing written")
        XCTAssertEqual(try store.allRecords().count, before)
        try store.rebuildProjection()
        XCTAssertEqual(try store.loadAvatar(), .none, "the clearing stands")
    }

    // MARK: - The export

    func testAnExportCarriesTheProfileAndTheImportBringsThePictureBack() throws {
        let a = tempStore()
        XCTAssertNil(try a.snapshot().profile, "no name and no picture: no profile")
        _ = try a.setYourName("Al")
        let jpeg = Data([0xFF, 0xD8, 0xFF, 0xE0, 4, 5, 6])
        try a.saveAvatar(.photo(jpeg))
        let profile = try XCTUnwrap(try a.snapshot().profile)
        XCTAssertEqual(profile, PortProfile(name: "Al", emoji: nil, photoHex: HexCodec.encode(jpeg)))
        let text = try ffiExportLedgerJson(snapshot: try a.snapshot())
        let back = try XCTUnwrap(try ffiImportLedgerJson(text: text).profile)
        XCTAssertEqual(back, profile, "through the file and back")

        // A phone with no picture takes it, as a record.
        let fresh = tempStore()
        XCTAssertTrue(try fresh.importProfile(back))
        XCTAssertEqual(try fresh.loadAvatar(), .photo(jpeg))
        XCTAssertNil(try fresh.loadSelf()?.name, "the name is the phone's own")
        // A phone that has one keeps it, and writes nothing.
        let has = tempStore()
        try has.saveAvatar(.emoji("🦊"))
        let count = try has.allRecords().count
        XCTAssertFalse(try has.importProfile(back))
        XCTAssertEqual(try has.loadAvatar(), .emoji("🦊"))
        XCTAssertEqual(try has.allRecords().count, count)
        // A picture kept from before pictures were records counts as one.
        let kept = tempStore()
        try kept.kvSet(key: LedgerStore.avatarKey, value: try Avatar.emoji("🐼").encoded())
        XCTAssertFalse(try kept.importProfile(back))
        XCTAssertEqual(try kept.loadAvatar(), .emoji("🐼"))
        // Nothing to bring: nothing written. A name alone brings nothing.
        XCTAssertFalse(try tempStore().importProfile(nil))
        XCTAssertFalse(try tempStore().importProfile(PortProfile(name: "Al", emoji: nil, photoHex: nil)))
    }

    // MARK: - D-211 Task 5: the screens' pure rules

    func testRenameIsAbsentForAJoinedPerson() {
        let previews: [String: [String: Avatar]] = [
            gA: [tim: .emoji("🦊"), bo: .none],
        ]
        // A person with a profile here: absent, whether they have a picture or only a name.
        XCTAssertFalse(MemberPreviews.mayRename(previews, group: gA, member: tim))
        XCTAssertFalse(MemberPreviews.mayRename(previews, group: gA, member: bo))
        // Someone who never joined stays renamable, and so does a person's id in another group.
        XCTAssertTrue(MemberPreviews.mayRename(previews, group: gA, member: cy))
        XCTAssertTrue(MemberPreviews.mayRename(previews, group: gB, member: tim))
        XCTAssertTrue(MemberPreviews.mayRename([:], group: gA, member: tim))
    }

    func testAPreviewIsTheGroupsOwnElseTheFirst() {
        let fox = Avatar.emoji("🦊"), panda = Avatar.emoji("🐼")
        let previews: [String: [String: Avatar]] = [
            gA: [tim: .none],          // a name only: no picture to draw
            gB: [placeX: fox],
            gC: [bo: panda],
        ]
        let candidates: [(group: String, member: String)] = [(gA, tim), (gB, placeX), (gC, bo)]
        // The group the card is about, when it has a picture.
        XCTAssertEqual(MemberPreviews.pick(previews, candidates: candidates, preferred: gC), panda)
        XCTAssertEqual(MemberPreviews.pick(previews, candidates: candidates, preferred: gB), fox)
        // Else the first group, in order, that has one (gA has none).
        XCTAssertEqual(MemberPreviews.pick(previews, candidates: candidates, preferred: gA), fox)
        XCTAssertEqual(MemberPreviews.pick(previews, candidates: candidates, preferred: nil), fox)
        XCTAssertEqual(MemberPreviews.pick(previews, candidates: candidates, preferred: "none"), fox)
        // The order is the caller's: the same people, the other way round.
        XCTAssertEqual(MemberPreviews.pick(previews, candidates: Array(candidates.reversed()), preferred: nil), panda)
        // No group has a picture, or no candidates: nothing to draw.
        XCTAssertEqual(MemberPreviews.pick(previews, candidates: [(gA, tim)], preferred: gA), .none)
        XCTAssertEqual(MemberPreviews.pick(previews, candidates: [], preferred: gA), .none)
        XCTAssertEqual(MemberPreviews.pick([:], candidates: candidates, preferred: nil), .none)
    }

    func testTheFootnotesSwitchOnTheAccount() {
        XCTAssertEqual(ProfileCopy.footnotes(signedIn: true),
                       ["Your picture syncs to your phones. Your groups see a small preview.",
                        "Earlier pictures stay in your private history until you delete your account."])
        XCTAssertEqual(ProfileCopy.footnotes(signedIn: false), ["Your name and picture stay on this phone."])
        // No account, no cloud words (GUARANTEES #5).
        let offline = ProfileCopy.footnotes(signedIn: false).joined(separator: " ").lowercased()
        for word in ["sync", "group", "account", "cloud"] { XCTAssertFalse(offline.contains(word), word) }
    }

    func testARecentPersonCarriesThePreviewOfTheFirstGroupThatHasOne() throws {
        func g(_ id: String, _ name: String, _ members: [(String, String)]) -> NamedGroupWire {
            NamedGroupWire(group: GroupWire(id: id, name: name, settlementCurrency: "INR", members: members.map { $0.0 }),
                           selfId: members[0].0, members: members.map { MemberWire(id: $0.0, name: $0.1) })
        }
        func c(_ member: String, _ you: String) -> ClaimOut { ClaimOut(memberId: member, youId: you, devices: []) }
        let groups = [g("goa", "Goa", [("gy", "Me"), ("gr", "Rahul")]),
                      g("flat", "Flat", [("fy", "Me"), ("fr", "Rahul")]),
                      g("trip", "Trip", [("ty", "Me")])]
        let claims = ["goa": [c("gr", "p1")], "flat": [c("fr", "p1")]]
        // Goa has no picture for Rahul (a name only); Flat has one.
        let previews: [String: [String: Avatar]] = ["goa": ["gr": .none], "flat": ["fr": .emoji("🦊")]]
        let all = Recents.people(groups: groups, claims: claims, groupId: "trip", previews: previews)
        let rahul = try XCTUnwrap(all.first)
        XCTAssertEqual(rahul.name, "Rahul")
        XCTAssertEqual(rahul.preview, .emoji("🦊"))
        // No previews: initials, as before.
        XCTAssertEqual(try XCTUnwrap(Recents.people(groups: groups, claims: claims, groupId: "trip").first).preview, .none)
    }

    func testAFailedPreviewReadKeepsThePeopleAndTheLastPreviews() {
        struct Boom: Error {}
        let last: [String: [String: Avatar]] = [gA: [tim: .emoji("🦊")]]
        // A read that throws: the last good previews stay, and the caller is never handed an error.
        XCTAssertEqual(MemberPreviews.reloaded(last: last, read: { throw Boom() }), last)
        // A read that works replaces them, an empty answer included (a cleared picture clears).
        let fresh: [String: [String: Avatar]] = [gA: [tim: .emoji("🐼")]]
        XCTAssertEqual(MemberPreviews.reloaded(last: last, read: { fresh }), fresh)
        XCTAssertEqual(MemberPreviews.reloaded(last: last, read: { [:] }), [:])
        // Nothing known yet and a failed read: nothing, so initials.
        XCTAssertEqual(MemberPreviews.reloaded(last: [:], read: { throw Boom() }), [:])
    }

    func testThePictureViewerIsOnlyForAPhotoThatDecodes() throws {
        let image = UIGraphicsImageRenderer(size: CGSize(width: 8, height: 8)).image { ctx in
            UIColor.red.setFill(); ctx.fill(CGRect(x: 0, y: 0, width: 8, height: 8))
        }
        let jpeg = try XCTUnwrap(image.jpegData(compressionQuality: 0.8))
        XCTAssertTrue(PictureViewing.canView(.photo(jpeg)))
        // A photo whose bytes are not an image behaves like no picture.
        XCTAssertFalse(PictureViewing.canView(.photo(Data([0x01, 0x02, 0x03]))))
        XCTAssertFalse(PictureViewing.canView(.emoji("🦊")))
        XCTAssertFalse(PictureViewing.canView(.none))
    }

    // MARK: - The final review's fixes

    /// I1(a): a phone updating to this build has its picture in kv only. The
    /// backfill's pass must share that picture, not "none".
    @MainActor
    func testTheShareMigratesAKeptPictureBeforeItReadsThePicture() async throws {
        fakeTheCloud()
        defer { restoreTheCloud() }
        let store = try jobFixture()
        try store.kvSet(key: LedgerStore.Sync.avatarRecordKey, value: Data())
        try store.kvSet(key: ProfileShare.backfilledKey, value: Data())
        try store.kvSet(key: LedgerStore.avatarKey, value: try Avatar.emoji("🦊").encoded())
        XCTAssertNil(try LedgerStore.Sync.projectGroups(store).avatar, "not migrated yet")
        await ProfileShare.syncStarted(store: store).value
        XCTAssertEqual(sent.count, 1)
        XCTAssertEqual(shared.map(\.emoji), ["🦊", "🦊"], "the kept picture, in every place")
        XCTAssertEqual(try LedgerStore.Sync.projectGroups(store).avatar?.emoji, "🦊", "and it is a record now")
    }

    /// I1(a): a migration that writes a record (from a rebuild, outside the
    /// job) asks for a share; one that writes nothing asks for nothing.
    @MainActor
    func testAMigrationThatWritesARecordAsksForAShare() async throws {
        fakeTheCloud()
        defer { restoreTheCloud() }
        let store = try jobFixture()
        try store.kvSet(key: LedgerStore.Sync.avatarRecordKey, value: Data())
        try store.kvSet(key: LedgerStore.avatarKey, value: try Avatar.emoji("🦊").encoded())
        XCTAssertFalse(ProfileShare.isPending(store))
        try LedgerStore.Sync.rebuildGroups(store)
        await settle()
        XCTAssertEqual(sent.count, 1, "the migration asked for a share")
        XCTAssertEqual(shared.map(\.emoji), ["🦊", "🦊"])
        XCTAssertFalse(ProfileShare.isPending(store))
        try LedgerStore.Sync.rebuildGroups(store)
        await settle()
        XCTAssertEqual(sent.count, 1, "nothing migrated: nothing asked")
    }

    /// I1(b): a phone that was offline shares only after the personal
    /// listener's first snapshot is in: its name was changed on another phone
    /// meanwhile, and a share built before would carry the old one with a
    /// fresh stamp that beats the right one in every group.
    @MainActor
    func testTheShareWaitsForTheFirstPersonalSnapshot() async throws {
        fakeTheCloud()
        defer { restoreTheCloud() }
        let store = try jobFixture()
        ProfileShare.listening(store: store)
        await ProfileShare.request(store: store).value
        await settle()
        XCTAssertEqual(sent.count, 0, "nothing before the personal snapshot")
        XCTAssertTrue(ProfileShare.isPending(store))
        let later = UInt64(Date().timeIntervalSince1970 * 1000) + 60_000
        // Another phone of this account renamed it while this one was offline.
        let otherPhone = tempStore()
        try LedgerStore.Sync.appendGroupOps(otherPhone, [.you(id: me, name: "Alfie")], placing: .none, nowMs: later)
        let rename = try XCTUnwrap(try otherPhone.allRecords().first)
        var document = CloudRecords.document(rename, uid: "uid-test")
        document["createdAt"] = Timestamp(seconds: 40, nanoseconds: 0)
        CloudSync.shared.receive([document], groupId: nil, store: store, fromCache: false)
        XCTAssertEqual(try store.loadSelf()?.name, "Alfie", "the snapshot is in")
        await settle()
        XCTAssertEqual(sent.count, 1, "one share, once caught up")
        XCTAssertEqual(shared.map(\.name), ["Alfie", "Alfie"], "with the name from the snapshot")
        XCTAssertFalse(ProfileShare.isPending(store))
        // A later personal snapshot starts nothing.
        CloudSync.shared.receive([], groupId: nil, store: store, fromCache: false)
        await settle()
        XCTAssertEqual(sent.count, 1)
    }

    /// I1(c): the migration on a second phone, before that phone has the
    /// account's newer picture, never undoes it: it is stamped low.
    func testAMigratedPictureNeverBeatsANewerOneFromAnotherPhone() throws {
        let elsewhere = tempStore()
        let yesterday = UInt64(Date().timeIntervalSince1970 * 1000) - 86_400_000
        try LedgerStore.Sync.appendGroupOps(elsewhere, [.avatar(emoji: "🐼", photoHex: nil)], placing: .none, nowMs: yesterday)
        let store = tempStore()
        try store.kvSet(key: LedgerStore.avatarKey, value: try Avatar.emoji("🦊").encoded())
        XCTAssertTrue(try LedgerStore.Sync.migrateAvatarOnce(store), "no picture record here yet: it migrates")
        let migrated = try XCTUnwrap(try store.allRecords().first {
            if case .avatar? = try? ffiDecodeGroupOp(blob: $0.blob) { return true }
            return false
        })
        XCTAssertEqual(migrated.hlcMillis, 1)
        XCTAssertEqual(migrated.hlcCounter, 0)
        XCTAssertEqual(migrated.hlcDeviceId, try LedgerStore.Sync.deviceId(store))
        XCTAssertEqual(try LedgerStore.Sync.projectGroups(store).avatar?.emoji, "🦊", "alone, it is the picture")
        _ = try CloudRecords.apply(try elsewhere.allRecords(), store: store)
        try store.rebuildProjection()
        XCTAssertEqual(try store.loadAvatar(), .emoji("🐼"), "the picture set elsewhere stands")
        _ = try CloudRecords.apply([migrated], store: elsewhere)
        try elsewhere.rebuildProjection()
        XCTAssertEqual(try elsewhere.loadAvatar(), .emoji("🐼"), "and the migrated one changes nothing there")
    }

    /// I2 (founder decision 5): at the first sign-in only the latest picture
    /// goes up; the earlier ones are marked uploaded without being sent.
    func testOnlyTheLatestPictureGoesUpAtTheFirstSignIn() throws {
        let store = tempStore()
        _ = try store.setYourName("Al")
        for emoji in ["🦊", "🐼", "🌿"] { try store.saveAvatar(.emoji(emoji)) }
        let all = try store.allRecords()
        func avatarIds(_ emoji: String) -> Set<String> {
            Set(all.filter {
                if case .avatar(let e, _)? = try? ffiDecodeGroupOp(blob: $0.blob) { return e == emoji }
                return false
            }.map(CloudRecords.documentId))
        }
        let older = avatarIds("🦊").union(avatarIds("🐼"))
        XCTAssertEqual(older.count, 2)
        let out = CloudRecords.outgoing(all, uploaded: [], readOnly: [], refused: [])
        XCTAssertEqual(out.superseded, older, "the earlier pictures are marked, never sent")
        let sentIds = Set(out.send.map(CloudRecords.documentId))
        XCTAssertTrue(avatarIds("🌿").isSubset(of: sentIds), "the latest goes up")
        XCTAssertTrue(sentIds.isDisjoint(with: older))
        XCTAssertEqual(sentIds.count, all.count - 2, "everything else goes up as before")
        // The latest already up: an earlier one still waiting never goes.
        let again = CloudRecords.outgoing(all, uploaded: avatarIds("🌿"), readOnly: [], refused: [])
        XCTAssertEqual(again.superseded, older)
        XCTAssertTrue(Set(again.send.map(CloudRecords.documentId)).isDisjoint(with: older))
        // One picture: it goes up.
        let one = tempStore()
        try one.saveAvatar(.emoji("🦊"))
        XCTAssertEqual(CloudRecords.outgoing(try one.allRecords(), uploaded: [], readOnly: [], refused: []).superseded, [])
    }

    /// Fix 3 item 1: the hosted run never has a real account. The simulator
    /// can be a clone of one signed in to production, and a vault marked
    /// caught up (as these tests do) would let a pass reach it. The tests run
    /// inside the host, so the guard is live here.
    @MainActor
    func testATestHostHasNoRealAccount() throws {
        XCTAssertTrue(LaunchWork.isTestHost(), "this process is the host")
        let store = tempStore()
        XCTAssertTrue(CloudSync.syncEnabled(store), "sync is on when unset")
        _ = ProfileShare.markCaughtUp(store)
        // A signed-in-looking state, sync on: a host still has no account.
        XCTAssertNil(LaunchWork.signedInAccount(uid: "uid-prod", syncOn: true, owner: "uid-prod"))
        // The same rule outside a host answers the account (not vacuous).
        XCTAssertEqual(LaunchWork.signedInAccount(uid: "uid-prod", syncOn: true, owner: "uid-prod", environment: [:]), "uid-prod")
        XCTAssertNil(LaunchWork.signedInAccount(uid: "uid-prod", syncOn: false, owner: "uid-prod", environment: [:]))
        XCTAssertNil(LaunchWork.signedInAccount(uid: nil, syncOn: true, owner: "uid-prod", environment: [:]))
        // D-235 (C2, decision 107): a session the vault does not own syncs nothing (no owner: a reinstall's fresh
        // vault; another owner: the one-account rule).
        XCTAssertNil(LaunchWork.signedInAccount(uid: "uid-prod", syncOn: true, owner: nil, environment: [:]))
        XCTAssertNil(LaunchWork.signedInAccount(uid: "uid-prod", syncOn: true, owner: "uid-other", environment: [:]))
        // And the real seam, on a caught-up vault, answers nothing.
        XCTAssertNil(ProfileShare.realAccount(store))
    }

    /// Fix 3 item 1: the same for the sync engine's own gate.
    @MainActor
    func testATestHostIsNeverReadyToSync() throws {
        XCTAssertNil(LaunchWork.signedInAccount(uid: "uid-prod", syncOn: true, owner: "uid-prod"), "no account in a host")
        XCTAssertNil(CloudSync.shared.ready(uid: "uid-prod"), "not ready to sync in a host, even signed in")
        XCTAssertNil(CloudSync.shared.ready(uid: Account.shared.uid), "and not with the real account state")
    }

    /// Fix 3 item 2 (M-A): the first personal snapshot opens the share only
    /// when the server sent it. The cache is memory only, so a cache snapshot
    /// is empty and says nothing of the account's own records.
    @MainActor
    func testACacheSnapshotNeverOpensTheShare() async throws {
        fakeTheCloud()
        defer { restoreTheCloud() }
        let store = try jobFixture()
        ProfileShare.listening(store: store)
        await ProfileShare.request(store: store).value
        CloudSync.shared.receive([], groupId: nil, store: store, fromCache: true)
        await settle()
        XCTAssertEqual(sent.count, 0, "a cache snapshot opens nothing")
        XCTAssertTrue(ProfileShare.isPending(store))
        CloudSync.shared.receive([], groupId: nil, store: store, fromCache: true)
        await settle()
        XCTAssertEqual(sent.count, 0, "nor a second one")
        CloudSync.shared.receive([], groupId: nil, store: store, fromCache: false)
        await settle()
        XCTAssertEqual(sent.count, 1, "the server's snapshot opens it")
        XCTAssertFalse(ProfileShare.isPending(store))
    }

    /// Fix 3 item 3 (M-B): a phone never writes a profile: one found in the
    /// vault as pending (its uploaded mark lost) is marked, never sent.
    func testOutgoingNeverSendsAMemberProfile() throws {
        let store = tempStore()
        _ = try store.setYourName("Al")
        try store.saveAvatar(.emoji("🦊"))
        let profile = try XCTUnwrap(try ProfileShare.records(
            store: store, places: [(gA, me)], name: "Al", emoji: "🦊", thumbHex: nil).first)
        XCTAssertTrue(CloudRecords.profileType(profile))
        let all = try store.allRecords() + [profile]
        XCTAssertTrue(CloudRecords.pending(all, uploaded: []).contains { CloudRecords.profileType($0) },
                      "pending would take it (the test is not vacuous)")
        let out = CloudRecords.outgoing(all, uploaded: [], readOnly: [], refused: [])
        XCTAssertFalse(out.send.contains { CloudRecords.profileType($0) }, "a profile is never sent")
        XCTAssertTrue(out.superseded.contains(CloudRecords.documentId(profile)), "it is marked uploaded instead")
        XCTAssertFalse(out.send.isEmpty, "everything else still goes")
        // Already marked: nothing to mark again.
        let again = CloudRecords.outgoing(all, uploaded: [CloudRecords.documentId(profile)], readOnly: [], refused: [])
        XCTAssertFalse(again.send.contains { CloudRecords.profileType($0) })
        XCTAssertFalse(again.superseded.contains(CloudRecords.documentId(profile)))
    }

    /// M1 (GUARANTEES #4): a kept picture core would refuse (an old photo over
    /// 64,000 bytes, not migrated yet) never fails the export: it goes
    /// without the picture.
    func testAnExportLeavesOutAPictureCoreWouldRefuse() throws {
        let a = tempStore()
        _ = try a.setYourName("Al")
        try a.kvSet(key: LedgerStore.Sync.avatarRecordKey, value: Data())
        let big = Data([0xFF, 0xD8, 0xFF, 0xE0]) + Data(count: 70_000)
        try a.kvSet(key: LedgerStore.avatarKey, value: try Avatar.photo(big).encoded())
        let snapshot = try a.snapshot()
        XCTAssertNoThrow(try ffiExportLedgerJson(snapshot: snapshot), "core refuses a file with that picture")
        XCTAssertEqual(snapshot.profile, PortProfile(name: "Al", emoji: nil, photoHex: nil))
        // One core takes still goes out.
        try a.kvSet(key: LedgerStore.avatarKey, value: try Avatar.emoji("🦊").encoded())
        XCTAssertEqual(try a.snapshot().profile, PortProfile(name: "Al", emoji: "🦊", photoHex: nil))
    }

    /// M4: a name the vault cannot read is not "no name": the share waits.
    @MainActor
    func testANameThatCannotBeReadKeepsTheSharePending() async throws {
        fakeTheCloud()
        defer { restoreTheCloud() }
        let store = try jobFixture()
        try store.kvSet(key: LedgerStore.selfKey, value: Data("not json".utf8))
        await ProfileShare.request(store: store).value
        XCTAssertEqual(ensured, 0)
        XCTAssertEqual(sent.count, 0)
        XCTAssertTrue(ProfileShare.isPending(store), "a failed read waits for the next try")
        // No name at all: nothing to share, and the share is done.
        let bare = tempStore()
        try own(bare)
        try bare.kvSet(key: ProfileShare.backfilledKey, value: Data("1".utf8))
        await ProfileShare.request(store: bare).value
        XCTAssertEqual(sent.count, 0)
        XCTAssertFalse(ProfileShare.isPending(bare))
    }

    /// D-227 follow-up E (look F3's twin): a share left for later because the phone was offline (the name's server
    /// read failed) runs again when the personal listener hears the server after speaking from the cache, not at the
    /// next sync start; a server snapshot with no cache one before it shares nothing. Twin: ProfileSyncTest.kt
    /// `a_share_left_for_later_runs_when_the_personal_listener_hears_the_server_again`.
    @MainActor
    func testAShareLeftForLaterRunsWhenThePersonalListenerHearsTheServerAgain() async throws {
        fakeTheCloud()
        defer { restoreTheCloud() }
        let store = try jobFixture()
        nameOutcome = .later
        await ProfileShare.request(store: store).value
        XCTAssertEqual(sent.count, 0)
        XCTAssertTrue(ProfileShare.isPending(store), "offline: it waits")
        XCTAssertEqual(waited, [], "a name that waits is no failed call: no timer")
        nameOutcome = .ok
        XCTAssertNil(ProfileShare.afterReceive(groupId: nil, fresh: [], store: store, fromCache: false),
                     "a server snapshot that is no reconnect")
        await settle()
        XCTAssertEqual(sent.count, 0)
        XCTAssertNil(ProfileShare.afterReceive(groupId: nil, fresh: [], store: store, fromCache: true),
                     "the listener speaks from the cache")
        let back = ProfileShare.afterReceive(groupId: nil, fresh: [], store: store, fromCache: false)
        XCTAssertNotNil(back, "the server again: the share runs")
        await back?.value
        await settle()
        XCTAssertEqual(sent.count, 1)
        XCTAssertFalse(ProfileShare.isPending(store))
        XCTAssertNil(ProfileShare.afterReceive(groupId: nil, fresh: [], store: store, fromCache: false), "once")
        // A new listener (listening) forgets the cache snapshot: its first server one is the sync start.
        XCTAssertNil(ProfileShare.afterReceive(groupId: nil, fresh: [], store: store, fromCache: true))
        ProfileShare.listening(store: store)
        ProfileShare.markCaughtUp(store)
        XCTAssertNil(ProfileShare.afterReceive(groupId: nil, fresh: [], store: store, fromCache: false))
        await settle()
        XCTAssertEqual(sent.count, 1, "nothing pending, nothing sent")
        // The app's listener goes through it: a cache snapshot, then the server's, shares what waits.
        nameOutcome = .later
        await ProfileShare.request(store: store).value
        nameOutcome = .ok
        CloudSync.shared.receive([], groupId: nil, store: store, fromCache: true)
        CloudSync.shared.receive([], groupId: nil, store: store, fromCache: false)
        await settle()
        XCTAssertEqual(sent.count, 2, "receive hands the reconnect on")
        XCTAssertFalse(ProfileShare.isPending(store))
    }

    /// D-227 follow-up C, decision 40 (re-look R2): a share whose call failed is tried again on decision 38's schedule
    /// (`RoleAsking.retryDelay`: 15, 60, 300 s, then the next sync start), cancelled by a successful share or a sign-out
    /// (`listening`). Twin: ProfileSyncTest.kt `a_failed_share_call_is_retried_after_15_then_60_then_300_seconds`.
    @MainActor
    func testAFailedShareCallIsRetriedAfter15Then60Then300Seconds() async throws {
        fakeTheCloud()
        defer { restoreTheCloud() }
        let store = try jobFixture()
        fireRetries = true
        // A name that waits is no failed call: no timer.
        nameOutcome = .later
        await ProfileShare.request(store: store).value
        await settle()
        XCTAssertEqual(waited, [])
        XCTAssertEqual(sent.count, 0)
        nameOutcome = .ok
        // A failed call, then its 15 s retry answers.
        answers = [.later, .done]
        await ProfileShare.request(store: store).value
        await settle()
        XCTAssertEqual(waited, [15])
        XCTAssertEqual(sent.count, 2, "the call and its retry")
        XCTAssertFalse(ProfileShare.isPending(store), "the retry shared")
        // The success started the count again: three timed retries, then nothing until the next sync start.
        waited = []
        answers = [.later, .later, .later, .later]
        await ProfileShare.request(store: store).value
        await settle()
        XCTAssertEqual(waited, [15, 60, 300], "three timed retries")
        XCTAssertEqual(sent.count, 6, "the call and three more")
        XCTAssertTrue(ProfileShare.isPending(store))
        waited = []
        answers = [.later]
        await ProfileShare.request(store: store).value
        await settle()
        XCTAssertEqual(waited, [], "the three are spent: the next sync start asks")
        XCTAssertEqual(sent.count, 7)
        // A new listener starts the count again, and a sign-out during the wait cancels the retry (a listener caught
        // up again before the timer fires does not bring it back).
        ProfileShare.listening(store: store)
        ProfileShare.markCaughtUp(store)
        answers = [.later]
        duringWait = {
            ProfileShare.listening(store: store)
            ProfileShare.markCaughtUp(store)
        }
        await ProfileShare.request(store: store).value
        await settle()
        XCTAssertEqual(waited, [15])
        XCTAssertEqual(sent.count, 8, "no call after the sign-out")
        XCTAssertTrue(ProfileShare.isPending(store))
    }

    /// M5: Android's without_an_account_nothing_is_sent_and_the_share_waits.
    @MainActor
    func testWithoutAnAccountNothingIsSentAndTheShareWaits() async throws {
        fakeTheCloud()
        defer { restoreTheCloud() }
        let store = try jobFixture()
        ProfileShare.account = { _ in nil }
        await ProfileShare.request(store: store).value
        await settle()
        XCTAssertEqual(ensured, 0)
        XCTAssertEqual(sent.count, 0)
        XCTAssertTrue(ProfileShare.isPending(store), "it waits for a sign-in")
    }

    // MARK: - D-229 (Phase B): a place alias comes in only from shareAliases (twin of ProfileSyncTest)

    private typealias F = FounderVault

    private func aliasOp(_ group: String = FounderVault.old) -> GroupOpIn {
        .placeAlias(groupId: group, memberId: FounderVault.x, youId: FounderVault.y)
    }

    /// Test 9: unmarked, refused `unverifiedAlias`; marked, taken; in the personal log, refused; a marked one from
    /// this phone (it built it, the Function wrote it), taken; never sent by a push.
    func testAPlaceAliasComesInOnlyMarkedAndIsNeverSent() throws {
        func refusal(_ r: RecordWire, alias: Bool = false, from group: String = FounderVault.old) -> CloudRecords.Refusal? {
            CloudRecords.refusal(r, alias: alias, from: group, thisDevice: device)
        }
        let theirs = try rec(aliasOp(), group: F.old)
        XCTAssertEqual(refusal(theirs), .unverifiedAlias, "a phone wrote it, not the Function")
        XCTAssertNil(refusal(theirs, alias: true))
        XCTAssertEqual(refusal(try rec(aliasOp(F.new), group: F.old), alias: true), .wrongGroup, "marked, another group's")
        XCTAssertEqual(refusal(theirs, alias: true, from: F.new), .wrongScope)
        XCTAssertEqual(CloudRecords.personalRefusal(try rec(aliasOp(), group: nil)), .wrongScope, "the personal log")
        XCTAssertNil(CloudRecords.personalRefusal(try rec(.you(id: F.y, name: nil), group: nil)))
        let mine = try rec(aliasOp(), group: F.old, device: device)
        XCTAssertNil(refusal(mine, alias: true), "this phone's own, marked")
        XCTAssertEqual(refusal(mine), .thisDevice, "claiming to be this phone's, unmarked")
        // The alias mark excuses no other kind of record from this phone, and no other mark excuses an alias.
        let renamed = try rec(.renameMember(groupId: F.old, memberId: F.c, name: "X"), group: F.old, device: device)
        XCTAssertEqual(refusal(renamed, alias: true), .thisDevice)
        XCTAssertEqual(CloudRecords.refusal(theirs, removal: true, profile: true, from: F.old, thisDevice: device),
                       .unverifiedAlias)

        let store = tempStore()
        _ = try store.setYourName("Al")
        let built = try AliasShare.records(store: store, aliases: [AliasShare.Alias(groupId: F.old, memberId: F.x, youId: F.y)])
        XCTAssertEqual(built.count, 1)
        XCTAssertEqual(built[0].groupId, F.old, "the group's scope")
        XCTAssertEqual(try ffiDecodeGroupOp(blob: built[0].blob), aliasOp())
        XCTAssertFalse(try store.allRecords().contains { CloudRecords.aliasType($0) }, "never put in the vault")
        let all = try store.allRecords() + built
        XCTAssertTrue(CloudRecords.pending(all, uploaded: []).contains { CloudRecords.aliasType($0) },
                      "pending would take it (the test is not vacuous)")
        let out = CloudRecords.outgoing(all, uploaded: [], readOnly: [], refused: [])
        XCTAssertFalse(out.send.contains { CloudRecords.aliasType($0) }, "an alias is never sent")
        XCTAssertTrue(out.superseded.contains(CloudRecords.documentId(built[0])))
        XCTAssertFalse(out.send.isEmpty, "everything else still goes")
    }

    /// Test 10: which aliases are wanted. Old (place x, y unreadable) with a record naming y wants {Old, x, y};
    /// without one, nothing; an answered pair is not asked again; a read-only group is skipped. New (x unreadable
    /// there) is named by no record under x: nothing.
    func testTheAliasesWantedAreTheUsedUnreadableIdsNotYetAnswered() throws {
        let p = try FounderVault.make(tempStore(), pClaim: F.x)
        func wanted(answered: Set<String> = [], readOnly: Set<String> = []) throws -> [AliasShare.Alias] {
            AliasShare.wanted(try LedgerStore.Sync.projectGroups(p), records: try p.allRecords(), readOnly: readOnly,
                              answered: answered)
        }
        XCTAssertEqual(try wanted(), [], "no record names y in Old yet")
        // Written after D-229, an expense in Old names x: it needs no alias.
        _ = try p.appendExpense(LedgerStore.ExpenseDTO(
            payer: F.y, paidMinor: 1_200, currencyCode: "INR", group: F.old, conversion: nil,
            shares: [.init(id: F.y, amountMinor: 600), .init(id: F.c, amountMinor: 600)], addedBy: F.y))
        XCTAssertEqual(try wanted(), [], "a placed write names x")
        // One written before D-229 (as this phone wrote it then): paid by y in Old.
        _ = try p.applyRecord(try LedgerStore.Sync.expenseRecord(
            LedgerStore.ExpenseDTO(payer: F.y, paidMinor: 800, currencyCode: "INR", group: F.old, conversion: nil,
                                   shares: [.init(id: F.y, amountMinor: 400), .init(id: F.c, amountMinor: 400)]),
            hlc: HlcStamp(millis: 4_000, counter: 0), deviceId: F.hex(0x50), placing: .none))
        let want = AliasShare.Alias(groupId: F.old, memberId: F.x, youId: F.y)
        XCTAssertEqual(try wanted(), [want])
        XCTAssertEqual(want.key, F.old + ":" + F.y)
        XCTAssertEqual(try wanted(answered: [want.key]), [], "answered once, never asked again")
        XCTAssertEqual(try wanted(readOnly: [F.old]), [], "a read-only copy takes nothing")
        // A settlement end names an id too (here x in New, where x is unreadable).
        _ = try p.applyRecord(try LedgerStore.Sync.settlementRecord(
            LedgerStore.SettlementDTO(from: F.c, to: F.x, amountMinor: 100, currencyCode: "INR", group: F.new),
            hlc: HlcStamp(millis: 4_100, counter: 0), deviceId: F.hex(0x50), placing: .none))
        XCTAssertEqual(try wanted(), [want, AliasShare.Alias(groupId: F.new, memberId: F.y, youId: F.x)])
        XCTAssertEqual(AliasShare.named(try p.allRecords(), group: F.new), [F.c, F.x], "New's own records only")
    }

    /// The job: one call for what is wanted in groups that are up; the reply's ids are remembered as answered; a
    /// failed call remembers nothing; an answered pair is never sent again.
    @MainActor
    func testTheAliasJobRemembersWhatTheCloudAnswered() async throws {
        let p = try FounderVault.make(tempStore(), pClaim: F.x)
        _ = try p.applyRecord(try LedgerStore.Sync.expenseRecord(
            LedgerStore.ExpenseDTO(payer: F.y, paidMinor: 800, currencyCode: "INR", group: F.old, conversion: nil,
                                   shares: [.init(id: F.y, amountMinor: 400), .init(id: F.c, amountMinor: 400)]),
            hlc: HlcStamp(millis: 4_000, counter: 0), deviceId: F.hex(0x50), placing: .none))
        var calls: [[[String: Any]]] = []
        var fail = true
        AliasShare.send = { data in
            let records = data["records"] as? [[String: Any]] ?? []
            calls.append(records)
            if fail { throw CloudFunctions.Failure.offline }
            return ["shared": records.compactMap { $0["id"] as? String }, "skipped": [String]()]
        }
        defer { AliasShare.send = AliasShare.realSend }
        let nothingUp = await AliasShare.share(store: p)
        XCTAssertEqual(nothingUp, .done)
        XCTAssertEqual(calls.count, 0, "Old is not up yet")
        try CloudRecords.saveGroupsUp(p, [F.old, F.new])
        let failed = await AliasShare.share(store: p)
        XCTAssertEqual(failed, .later)
        XCTAssertEqual(calls.count, 1)
        XCTAssertEqual(AliasShare.answered(p), [], "a failed call remembers nothing")
        fail = false
        let shared = await AliasShare.share(store: p)
        XCTAssertEqual(shared, .done)
        XCTAssertEqual(calls.count, 2)
        // Never indexed: a red here must not crash the host.
        XCTAssertEqual(calls.last?.count, 1, "one record: {Old, x, y}")
        XCTAssertEqual(calls.last?.first?["type"] as? String, "3")
        XCTAssertEqual(AliasShare.answered(p), [F.old + ":" + F.y])
        _ = await AliasShare.share(store: p)
        XCTAssertEqual(calls.count, 2, "answered: never asked again")
        // resource-exhausted (the daily quota, decision 48): transient, nothing remembered, asked again later.
        try AliasShare.saveAnswered(p, [])
        AliasShare.send = { data in
            calls.append(data["records"] as? [[String: Any]] ?? [])
            throw CloudFunctions.Failure.refused("RESOURCE_EXHAUSTED")
        }
        let quota = await AliasShare.share(store: p)
        XCTAssertEqual(quota, .later)
        XCTAssertEqual(calls.count, 3)
        XCTAssertEqual(AliasShare.answered(p), [], "the quota answers nothing: asked again on a later push")
        // invalid-argument: the call was wrong, and asking again would not change it: its pairs are remembered, and
        // the run is done (one answer on both phones, Android's).
        AliasShare.send = { data in
            calls.append(data["records"] as? [[String: Any]] ?? [])
            throw CloudFunctions.Failure.refused("INVALID_ARGUMENT")
        }
        let refused = await AliasShare.share(store: p)
        XCTAssertEqual(refused, .done)
        XCTAssertEqual(calls.count, 4)
        XCTAssertEqual(AliasShare.answered(p), [F.old + ":" + F.y])
        XCTAssertFalse(try p.allRecords().contains { CloudRecords.aliasType($0) }, "never in the vault")
        XCTAssertEqual(AliasShare.answer(CloudFunctions.Failure.refused("INVALID_ARGUMENT")), .refused)
        XCTAssertEqual(AliasShare.answer(CloudFunctions.Failure.refused("RESOURCE_EXHAUSTED")), .later)
        XCTAssertEqual(AliasShare.answer(CloudFunctions.Failure.refused("INTERNAL")), .later)
        XCTAssertEqual(AliasShare.answer(CloudFunctions.Failure.offline), .later)
        XCTAssertEqual(AliasShare.answer(nil), .done)
    }

    /// T4 M: the records are built one group at a time: a group with a bad alias (core refuses a member that is
    /// the id itself) is skipped, and every other group's still go, each record with its own alias.
    func testOneBadAliasSkipsItsGroupOnly() throws {
        let store = tempStore()
        _ = try store.setYourName("Al")
        let bad = AliasShare.Alias(groupId: F.old, memberId: F.x, youId: F.x)
        let fine = AliasShare.Alias(groupId: F.old, memberId: F.x, youId: F.y)
        let other = AliasShare.Alias(groupId: F.new, memberId: F.y, youId: F.x)
        XCTAssertThrowsError(try AliasShare.records(store: store, aliases: [bad, fine, other]), "all at once: none")
        let built = AliasShare.recordsByGroup(store: store, aliases: [bad, fine, other])
        XCTAssertEqual(built.map { $0.alias }, [other], "Old (one bad alias) is skipped, New goes")
        XCTAssertEqual(try built.map { try ffiDecodeGroupOp(blob: $0.record.blob) },
                       [.placeAlias(groupId: F.new, memberId: F.y, youId: F.x)])
        let both = AliasShare.recordsByGroup(store: store, aliases: [fine, other])
        XCTAssertEqual(both.map { $0.alias }, [fine, other])
        XCTAssertEqual(both.map { $0.record.groupId }, [F.old, F.new])
    }

    /// T3 M3 (T4 I1's twin): a push with nothing pending still asks for the aliases.
    @MainActor
    func testAPushWithNothingPendingStillSharesTheAliases() async throws {
        let p = try FounderVault.make(tempStore(), pClaim: F.x)
        _ = try p.applyRecord(try LedgerStore.Sync.expenseRecord(
            LedgerStore.ExpenseDTO(payer: F.y, paidMinor: 800, currencyCode: "INR", group: F.old, conversion: nil,
                                   shares: [.init(id: F.y, amountMinor: 400), .init(id: F.c, amountMinor: 400)]),
            hlc: HlcStamp(millis: 4_000, counter: 0), deviceId: F.hex(0x50), placing: .none))
        // The claims a push writes first, written now, and everything marked up: nothing waits.
        let device = try LedgerStore.Sync.deviceId(p)
        try LedgerStore.Sync.appendGroupOps(p, CloudRecords.ownClaims(try LedgerStore.Sync.projectGroups(p), device: device),
                                            placing: .none)
        try CloudRecords.saveUploaded(p, Set(try p.allRecords().map(CloudRecords.documentId)))
        try CloudRecords.saveGroupsUp(p, [F.old, F.new])
        XCTAssertEqual(CloudRecords.outgoing(try p.allRecords(), uploaded: try CloudRecords.uploaded(p), readOnly: [],
                                             refused: []).send.count, 0, "nothing pending")
        var calls = 0
        AliasShare.send = { data in
            calls += 1
            let records = data["records"] as? [[String: Any]] ?? []
            return ["shared": records.compactMap { $0["id"] as? String }, "skipped": [String]()]
        }
        defer { AliasShare.send = AliasShare.realSend }
        let sent = await CloudSync.pushAll(uid: "u", store: p)
        XCTAssertEqual(sent, 0)
        XCTAssertEqual(calls, 1, "the aliases went on a push that sent nothing")
        XCTAssertEqual(AliasShare.answered(p), [F.old + ":" + F.y])
    }

    // MARK: - D-230: held places (twin of ProfileSyncTest)

    private func heldOp(_ group: String = FounderVault.old, member: String = FounderVault.x,
                        you: String = FounderVault.y) -> GroupOpIn {
        .heldPlace(groupId: group, memberId: member, youId: you)
    }

    /// A held place as the group's listener hands it over: the Function's document, marked `held: true` unless
    /// `held` says otherwise.
    private func heldDocument(_ op: GroupOpIn, group: String, held: Bool = true, millis: UInt64 = 5_000) throws
        -> [String: Any] {
        let record = try LedgerStore.Sync.groupOpRecord(op, hlc: HlcStamp(millis: millis, counter: 0), deviceId: other)
        var document = CloudRecords.document(record, uid: "u")
        document["createdAt"] = Timestamp(seconds: 5, nanoseconds: 0)
        if held { document["held"] = true }
        return document
    }

    /// `PlaceShare.wanted` over `store`'s projection and records, as `share` reads them.
    private func wantedIn(_ store: LedgerStore, readOnly: Set<String> = [], answered: Set<String> = []) throws
        -> [PlaceShare.Held] {
        PlaceShare.wanted(try LedgerStore.Sync.projectGroups(store), records: try store.allRecords(), readOnly: readOnly,
                          answered: answered)
    }

    /// A held place comes in only marked, never from a personal log, never sent; this phone's own comes back marked.
    func testAHeldPlaceComesInOnlyMarkedAndIsNeverSent() throws {
        func refusal(_ r: RecordWire, held: Bool = false, alias: Bool = false,
                     from group: String = FounderVault.old) -> CloudRecords.Refusal? {
            CloudRecords.refusal(r, alias: alias, held: held, from: group, thisDevice: device)
        }
        let theirs = try rec(heldOp(), group: F.old)
        XCTAssertEqual(refusal(theirs), .unverifiedPlace, "a phone wrote it, not the Function")
        XCTAssertNil(refusal(theirs, held: true))
        XCTAssertEqual(refusal(theirs, alias: true), .unverifiedPlace, "an alias's mark is not a held place's")
        XCTAssertEqual(CloudRecords.refusal(theirs, removal: true, profile: true, alias: true, from: F.old,
                                            thisDevice: device), .unverifiedPlace, "no other mark either")
        XCTAssertEqual(refusal(try rec(aliasOp(), group: F.old), held: true), .unverifiedAlias, "nor the reverse")
        XCTAssertEqual(refusal(try rec(heldOp(F.new), group: F.old), held: true), .wrongGroup)
        XCTAssertEqual(refusal(theirs, held: true, from: F.new), .wrongScope)
        XCTAssertEqual(CloudRecords.personalRefusal(try rec(heldOp(), group: nil)), .wrongScope, "the personal log")
        let mine = try rec(heldOp(), group: F.old, device: device)
        XCTAssertNil(refusal(mine, held: true), "this phone's own, marked")
        XCTAssertEqual(refusal(mine), .thisDevice, "claiming to be this phone's, unmarked")
        let renamed = try rec(.renameMember(groupId: F.old, memberId: F.c, name: "X"), group: F.old, device: device)
        XCTAssertEqual(refusal(renamed, held: true), .thisDevice, "the held mark excuses nothing else")
        XCTAssertTrue(CloudRecords.heldType(theirs))
        XCTAssertFalse(CloudRecords.heldType(try rec(aliasOp(), group: F.old)))

        let store = tempStore()
        _ = try store.setYourName("Al")
        let built = try PlaceShare.records(store: store, places: [PlaceShare.Held(groupId: F.old, memberId: F.x, youId: F.y)])
        XCTAssertEqual(built.count, 1)
        XCTAssertEqual(built[0].groupId, F.old, "the group's scope")
        XCTAssertEqual(try ffiDecodeGroupOp(blob: built[0].blob), heldOp())
        XCTAssertFalse(try store.allRecords().contains { CloudRecords.heldType($0) }, "never put in the vault")
        let all = try store.allRecords() + built
        XCTAssertTrue(CloudRecords.pending(all, uploaded: []).contains { CloudRecords.heldType($0) },
                      "pending would take it (the test is not vacuous)")
        let out = CloudRecords.outgoing(all, uploaded: [], readOnly: [], refused: [])
        XCTAssertFalse(out.send.contains { CloudRecords.heldType($0) }, "a held place is never sent")
        XCTAssertTrue(out.superseded.contains(CloudRecords.documentId(built[0])))
        XCTAssertFalse(out.send.isEmpty, "everything else still goes")
    }

    /// Which places are wanted: per group with a place that is not read-only, the places this account's claims hold
    /// (A1, with the claim's id) and (place, you), less what `held` lists; an answered key is not asked again; a held
    /// place that came back marked is not asked again.
    func testThePlacesWantedAreTheUnmarkedOnes() throws {
        let store = try FounderVault.make(tempStore(), pClaim: F.x)
        let p = try LedgerStore.Sync.projectGroups(store)
        XCTAssertEqual(p.you?.id, F.y, "the account's earliest you")
        // P's own claim in Old ({x, you x}), then Old's place under the account's you, then New's.
        let claimedOld = PlaceShare.Held(groupId: F.old, memberId: F.x, youId: F.x)
        let oldHeld = PlaceShare.Held(groupId: F.old, memberId: F.x, youId: F.y)
        let newHeld = PlaceShare.Held(groupId: F.new, memberId: F.y, youId: F.y)
        let want = PlaceShare.wanted(p, records: try store.allRecords(), readOnly: [], answered: [])
        XCTAssertEqual(want.map(\.key), [F.old + ":" + F.x + ":" + F.x, F.old + ":" + F.x + ":" + F.y,
                                         F.new + ":" + F.y + ":" + F.y])
        XCTAssertEqual(want, [claimedOld, oldHeld, newHeld])
        XCTAssertEqual(try wantedIn(store, readOnly: [F.old]), [newHeld], "a read-only copy takes nothing")
        XCTAssertEqual(try wantedIn(store, answered: [oldHeld.key]), [claimedOld, newHeld], "answered once")
        XCTAssertEqual(try wantedIn(store, answered: Set(want.map(\.key))), [])
        // Unmarked, the held place is refused at the door and still wanted.
        XCTAssertEqual(try CloudRecords.absorb([try heldDocument(heldOp(), group: F.old, held: false)], groupId: F.old,
                                               store: store).count, 0)
        XCTAssertEqual(try wantedIn(store, readOnly: [], answered: []), want)
        // Marked (the Function's word, back through Old's log): the projection lists it, and Old is not asked again.
        XCTAssertEqual(try CloudRecords.absorb([try heldDocument(heldOp(), group: F.old)], groupId: F.old,
                                               store: store).count, 1)
        let marked = try LedgerStore.Sync.projectGroups(store)
        XCTAssertEqual(marked.groups.first { $0.group.id == F.old }?.held, [HeldPlaceOut(memberId: F.x, youId: F.y)])
        XCTAssertEqual(try wantedIn(store, answered: [claimedOld.key]), [newHeld])
        // A10 M1: P's own claim {x, you x}, void in core and still in the vault, is NOT asked: x is already held by one
        // of this account's ids (y), so the account never re-marks it under its other id and the mark never flips.
        XCTAssertEqual(try wantedIn(store), [newHeld], "Old wants nothing once marked for the account")
        // C, which this account was taken out of (its raw creator place, no claim), asks nothing; no "you", nothing.
        let f = try fixture()
        XCTAssertEqual(Set(try wantedIn(f).map(\.key)),
                       [gA + ":" + me + ":" + me, gB + ":" + placeX + ":" + me])
        XCTAssertEqual(try wantedIn(tempStore(), readOnly: [], answered: []), [])
        // A1: a rejoin. In Tim's group R this account claimed bo, was removed from it, and now holds cy: the old
        // claimed place is wanted too (first, member order), until a mark lists it.
        let gR = String(repeating: "8", count: 32)
        let rejoin = try fixture()
        try LedgerStore.Sync.appendGroupOps(rejoin, [
            .create(groupId: gR, name: "R", settlementCurrency: "USD", members: [
                GroupOpMemberIn(id: tim, name: "Tim"), GroupOpMemberIn(id: bo, name: "Al"),
                GroupOpMemberIn(id: cy, name: "Al again")]),
            Invites.claim(group: gR, member: bo, you: me, device: device),
            .removeMember(groupId: gR, memberId: bo),
            Invites.claim(group: gR, member: cy, you: me, device: device),
        ], placing: .none)
        let oldR = PlaceShare.Held(groupId: gR, memberId: bo, youId: me)
        let newR = PlaceShare.Held(groupId: gR, memberId: cy, youId: me)
        func inR() throws -> [PlaceShare.Held] {
            try wantedIn(rejoin, readOnly: [], answered: []).filter { $0.groupId == gR }
        }
        XCTAssertEqual(try inR(), [oldR, newR], "the old place first, then the new one (its claim and place once)")
        XCTAssertEqual(oldR.key, gR + ":" + bo + ":" + me)
        XCTAssertEqual(try wantedIn(rejoin, readOnly: [], answered: [oldR.key])
            .filter { $0.groupId == gR }, [newR], "answered once")
        XCTAssertEqual(try wantedIn(rejoin, readOnly: [gR], answered: [])
            .filter { $0.groupId == gR }, [], "a read-only copy takes nothing")
        // The join's two marks, the old place first (as acceptInvite writes them): nothing is asked again.
        XCTAssertEqual(try CloudRecords.absorb([
            try heldDocument(heldOp(gR, member: bo, you: me), group: gR, millis: 9_000_000_000_000),
            try heldDocument(heldOp(gR, member: cy, you: me), group: gR, millis: 9_000_000_000_001),
        ], groupId: gR, store: rejoin).count, 2)
        let both = try LedgerStore.Sync.projectGroups(rejoin).groups.first { $0.group.id == gR }
        XCTAssertEqual(both?.held, [HeldPlaceOut(memberId: bo, youId: me), HeldPlaceOut(memberId: cy, youId: me)])
        XCTAssertEqual(both?.place, cy)
        XCTAssertEqual(try inR(), [], "marked: neither place is asked again")
    }

    /// The job remembers what the cloud answered, asks again later on a failure or the quota, and is done on
    /// invalid-argument: the AliasShare contract.
    @MainActor
    func testThePlaceJobRemembersWhatTheCloudAnswered() async throws {
        let store = try FounderVault.make(tempStore(), pClaim: F.x)
        let all: Set<String> = [F.old + ":" + F.x + ":" + F.x, F.old + ":" + F.x + ":" + F.y, F.new + ":" + F.y + ":" + F.y]
        var sent: [[String: Any]] = []
        PlaceShare.send = { data in
            sent.append(data)
            let ids = (data["records"] as? [[String: Any]] ?? []).compactMap { $0["id"] as? String }
            return ["shared": Array(ids.prefix(1)), "skipped": Array(ids.dropFirst())]
        }
        defer { PlaceShare.send = PlaceShare.realSend }
        let nothingUp = await PlaceShare.share(store: store)
        XCTAssertEqual(nothingUp, .done)
        XCTAssertEqual(sent.count, 0, "no group is up yet")
        try CloudRecords.saveGroupsUp(store, [F.old, F.new])
        let first = await PlaceShare.share(store: store)
        XCTAssertEqual(first, .done)
        XCTAssertEqual(sent.count, 1)
        // Never indexed: a red here must not crash the host.
        XCTAssertEqual((sent.last?["records"] as? [[String: Any]])?.count, 3, "one record per (group, place, id)")
        XCTAssertEqual(PlaceShare.answered(store), all, "shared and skipped both answer")
        let again = await PlaceShare.share(store: store)
        XCTAssertEqual(again, .done, "nothing left")
        XCTAssertEqual(sent.count, 1, "asked once")
        // resource-exhausted (the places quota): nothing remembered, asked again at the next push.
        try PlaceShare.saveAnswered(store, [])
        PlaceShare.send = { _ in throw CloudFunctions.Failure.refused("RESOURCE_EXHAUSTED") }
        let quota = await PlaceShare.share(store: store)
        XCTAssertEqual(quota, .later)
        XCTAssertEqual(PlaceShare.answered(store), [])
        PlaceShare.send = { _ in throw CloudFunctions.Failure.offline }
        let offline = await PlaceShare.share(store: store)
        XCTAssertEqual(offline, .later)
        XCTAssertEqual(PlaceShare.answered(store), [])
        // invalid-argument: the call itself was wrong: every place it carried is remembered, the run is done.
        PlaceShare.send = { _ in throw CloudFunctions.Failure.refused("INVALID_ARGUMENT") }
        let refused = await PlaceShare.share(store: store)
        XCTAssertEqual(refused, .done)
        XCTAssertEqual(PlaceShare.answered(store), all, "a refused call is an answer")
        XCTAssertEqual(PlaceShare.answer(CloudFunctions.Failure.refused("INVALID_ARGUMENT")), .refused)
        XCTAssertEqual(PlaceShare.answer(CloudFunctions.Failure.refused("RESOURCE_EXHAUSTED")), .later)
        XCTAssertFalse(try store.allRecords().contains { CloudRecords.heldType($0) }, "never in the vault")
    }

    /// A6 (I-B) and A7: a group with no place still wants its claimed places. Vector 1: one id claimed on two present
    /// members (decision 44's denial: core lists both claims, no place): `wanted` itself lists both, but core reads
    /// that group as `place_lost`, so it is a read-only copy (`store.readOnly()`) and `share` never asks for it
    /// (decision 45); asserted below. Vector 2 (I-B), the one `share` reaches: a rejoin whose new place's mark
    /// failed while the old one's came: core voids the phone's claim on the new place (the mark gives this account's
    /// id to the old one) and lists it under `contested`, not `claims`; the vault still holds it, so it is wanted.
    /// Android's twin: d230_a_group_with_no_place_still_wants_its_claimed_place.
    @MainActor
    func testAGroupWithNoPlaceStillWantsItsClaimedPlace() async throws {
        let gU = String(repeating: "c", count: 32)
        let split = try fixture()
        try LedgerStore.Sync.appendGroupOps(split, [
            .create(groupId: gU, name: "U", settlementCurrency: "USD", members: [
                GroupOpMemberIn(id: tim, name: "Tim"), GroupOpMemberIn(id: bo, name: "Al"),
                GroupOpMemberIn(id: cy, name: "Al too")]),
            Invites.claim(group: gU, member: bo, you: me, device: device),
            Invites.claim(group: gU, member: cy, you: me, device: device),
        ], placing: .none)
        let u = try XCTUnwrap(try LedgerStore.Sync.projectGroups(split).groups.first { $0.group.id == gU })
        XCTAssertNil(u.place, "a denial: no place")
        XCTAssertEqual(split.readOnly()[gU], .placeLost, "place lost: a read-only copy, which share skips")
        let both = [PlaceShare.Held(groupId: gU, memberId: bo, youId: me), PlaceShare.Held(groupId: gU, memberId: cy, youId: me)]
        XCTAssertEqual(try wantedIn(split).filter { $0.groupId == gU }, both)
        XCTAssertEqual(try wantedIn(split, readOnly: [gU]).filter { $0.groupId == gU }, [], "a read-only copy takes nothing")
        XCTAssertEqual(try wantedIn(split, answered: [both[0].key]).filter { $0.groupId == gU }, [both[1]])
        // The group's member order, wherever a claim came from: a vault claim on Tim (U's first member), which core
        // does not list, comes first (Android's order; the join's cap keeps the first ten of it).
        let onTim = try LedgerStore.Sync.groupOpRecord(Invites.claim(group: gU, member: tim, you: me, device: device),
                                                       hlc: HlcStamp(millis: 9_000_000_000_000, counter: 0), deviceId: device)
        XCTAssertEqual(PlaceShare.claimed(try LedgerStore.Sync.projectGroups(split), u, records: try split.allRecords() + [onTim]),
                       [PlaceShare.Held(groupId: gU, memberId: tim, youId: me)] + both)

        let gT = String(repeating: "9", count: 32)
        let lost = try fixture()
        try LedgerStore.Sync.appendGroupOps(lost, [
            .create(groupId: gT, name: "T", settlementCurrency: "USD", members: [
                GroupOpMemberIn(id: tim, name: "Tim"), GroupOpMemberIn(id: bo, name: "Al"),
                GroupOpMemberIn(id: cy, name: "Al again")]),
            Invites.claim(group: gT, member: bo, you: me, device: device),
            .removeMember(groupId: gT, memberId: bo),
            Invites.claim(group: gT, member: cy, you: me, device: device),
        ], placing: .none)
        XCTAssertEqual(try CloudRecords.absorb([
            try heldDocument(heldOp(gT, member: bo, you: me), group: gT, millis: 9_000_000_000_000),
        ], groupId: gT, store: lost).count, 1)
        let t = try XCTUnwrap(try LedgerStore.Sync.projectGroups(lost).groups.first { $0.group.id == gT })
        XCTAssertNil(t.place, "no place: the phone's claim on cy is void")
        XCTAssertEqual(t.held, [HeldPlaceOut(memberId: bo, youId: me)])
        XCTAssertFalse(t.claims.contains { $0.memberId == cy }, "core does not list the void claim (the vault does)")
        XCTAssertEqual(try wantedIn(lost).filter { $0.groupId == gT }, [PlaceShare.Held(groupId: gT, memberId: cy, youId: me)],
                       "the new place is wanted without a place")
        // Core's claims alone give nothing here (the old place is held): the vault's records are what find it.
        XCTAssertEqual(PlaceShare.claimed(try LedgerStore.Sync.projectGroups(lost), t, records: []), [],
                       "without the vault's records, nothing")
        XCTAssertNil(lost.readOnly()[gT], "not read-only: share reaches this group")

        // M-b (closing review): through `share` itself (the `send` seam): vector 1 (read-only) sends nothing, vector 2
        // sends exactly (cy, me).
        var sent: [[[String: Any]]] = []
        PlaceShare.send = { data in
            let records = data["records"] as? [[String: Any]] ?? []
            sent.append(records)
            return ["shared": records.compactMap { $0["id"] as? String }]
        }
        defer { PlaceShare.send = PlaceShare.realSend }
        try CloudRecords.saveGroupsUp(split, [gU])
        let splitAnswer = await PlaceShare.share(store: split)
        XCTAssertEqual(splitAnswer, .done)
        XCTAssertEqual(sent.count, 0, "a read-only copy (place lost): nothing is sent")
        try CloudRecords.saveGroupsUp(lost, [gT])
        let lostAnswer = await PlaceShare.share(store: lost)
        XCTAssertEqual(lostAnswer, .done)
        XCTAssertEqual(sent.count, 1)
        let ops = try (sent.first ?? []).map { r -> GroupOpIn in
            let blob = try XCTUnwrap(Data(base64Encoded: try XCTUnwrap((r["payload"] as? [String: Any])?["blob"] as? String)))
            return try ffiDecodeGroupOp(blob: blob)
        }
        XCTAssertEqual(ops, [.heldPlace(groupId: gT, memberId: cy, youId: me)], "the new place, asked for")
    }

    /// A5 and item 6: what the phone builds is what the server accepts. `HeldRule` (the server's checkHeldRecords:
    /// the record shape, type "3", the blob's keys, 32-hex ids, no record id or (group, member, you) twice, the count,
    /// and for a join at least one record for the invite's place) run over each `wanted` vector's call and the join's.
    func testTheWantedPlacesPassTheServersDuplicateRule() throws {
        func call(_ store: LedgerStore, _ places: [PlaceShare.Held]) throws -> [[String: Any]]? {
            PlaceShare.callData(try PlaceShare.records(store: store, places: places))["records"] as? [[String: Any]]
        }
        func check(_ store: LedgerStore, _ label: String) throws {
            let want = try wantedIn(store, readOnly: [], answered: [])
            XCTAssertNil(HeldRule.check(try call(store, want), max: PlaceShare.callMax), label)
        }
        let founder = try FounderVault.make(tempStore(), pClaim: F.x)
        let wantFounder = try wantedIn(founder, readOnly: [], answered: [])
        XCTAssertEqual(wantFounder.count, 3, "not vacuous: one member, two ids, is two records")
        try check(founder, "founder")
        let records = try call(founder, wantFounder)
        XCTAssertEqual(HeldRule.check(records, max: PlaceShare.callMax, triple: false), "records",
                       "one member, two ids: the old pair rule would refuse it")
        // The rule bites: a triple twice, an id that is not lowercase hex (core refuses to build one, so the wire is
        // edited), a list over the count, a record id twice, another type.
        XCTAssertEqual(HeldRule.check(try call(founder, [wantFounder[0], wantFounder[0]]), max: PlaceShare.callMax),
                       "records", "a triple twice")
        var upper = try XCTUnwrap(records)
        var payload = try XCTUnwrap(upper[0]["payload"] as? [String: Any])
        var op = try XCTUnwrap(try JSONSerialization.jsonObject(
            with: try XCTUnwrap(Data(base64Encoded: try XCTUnwrap(payload["blob"] as? String)))) as? [String: Any])
        op["you"] = (op["you"] as? String)?.uppercased().replacingOccurrences(of: "0", with: "A")
        payload["blob"] = try JSONSerialization.data(withJSONObject: op).base64EncodedString()
        upper[0]["payload"] = payload
        XCTAssertEqual(HeldRule.check(upper, max: PlaceShare.callMax), "records", "an id not in lowercase hex")
        XCTAssertEqual(HeldRule.check(records, max: 2), "records", "over the count")
        var twice = try XCTUnwrap(records)
        twice[1]["hlc"] = twice[0]["hlc"]
        twice[1]["id"] = twice[0]["id"]
        XCTAssertEqual(HeldRule.check(twice, max: PlaceShare.callMax), "records", "a record id twice")
        var typed = try XCTUnwrap(records)
        typed[0]["type"] = "4"
        XCTAssertEqual(HeldRule.check(typed, max: PlaceShare.callMax), "records", "another type")
        XCTAssertEqual(HeldRule.check([], max: PlaceShare.callMax), "records", "none")
        // M-a: the length caps and the safe-integer range (recordShape's), each alone.
        var longBlob = try XCTUnwrap(records)
        var lb = try XCTUnwrap(longBlob[0]["payload"] as? [String: Any])
        lb["blob"] = String(repeating: "A", count: 65_540)
        longBlob[0]["payload"] = lb
        XCTAssertEqual(HeldRule.check(longBlob, max: PlaceShare.callMax), "records", "a blob over 65536")
        var longNonce = try XCTUnwrap(records)
        var ln = try XCTUnwrap(longNonce[0]["payload"] as? [String: Any])
        ln["nonce"] = String(repeating: "A", count: 68)
        longNonce[0]["payload"] = ln
        XCTAssertEqual(HeldRule.check(longNonce, max: PlaceShare.callMax), "records", "a nonce over 64")
        var unsafe = try XCTUnwrap(records)
        var uh = try XCTUnwrap(unsafe[0]["hlc"] as? [String: Any])
        let big: Int64 = 9_007_199_254_740_992
        uh["millis"] = big
        unsafe[0]["hlc"] = uh
        unsafe[0]["id"] = "\(big)-\(uh["counter"] as? Int64 ?? -1)-\(uh["device"] as? String ?? "")"
        XCTAssertEqual(HeldRule.check(unsafe, max: PlaceShare.callMax), "records", "millis past the safe integers")
        var unsafeFlags = try XCTUnwrap(records)
        var uf = try XCTUnwrap(unsafeFlags[0]["payload"] as? [String: Any])
        uf["flags"] = big
        unsafeFlags[0]["payload"] = uf
        XCTAssertEqual(HeldRule.check(unsafeFlags, max: PlaceShare.callMax), "records", "flags past the safe integers")
        let both = try FounderVault.make(tempStore(), pClaim: F.y)
        try check(both, "claim and place agree")
        try check(try fixture(), "fixture")
        // The join's records, decoded back to their triples: the claimed pair and the new place.
        let join = try Invites.heldRecords(store: founder, group: F.old, member: F.c)
        let decoded = try join.map { r -> PlaceShare.Held in
            guard case .heldPlace(let g, let m, let y) = try ffiDecodeGroupOp(blob: r.blob) else {
                throw CloudFunctions.Failure.offline
            }
            return PlaceShare.Held(groupId: g, memberId: m, youId: y)
        }
        XCTAssertEqual(decoded, [PlaceShare.Held(groupId: F.old, memberId: F.x, youId: F.x),
                                 PlaceShare.Held(groupId: F.old, memberId: F.c, youId: F.y)], "P's claim, then the new place")
        let joinData = Invites.acceptData(token: "t", records: join)["records"] as? [[String: Any]]
        XCTAssertNil(HeldRule.check(joinData, max: Invites.joinMax, place: (F.old, F.c)), "join")
        XCTAssertEqual(HeldRule.check(Array(joinData?.prefix(1) ?? []), max: Invites.joinMax, place: (F.old, F.c)),
                       "records", "a join with no record for the invite's place")
        // M-a: with the invite's place, every record is in the invite's group (acceptInvite refuses another group's).
        let elsewhere = Invites.acceptData(token: "t", records: join + (try PlaceShare.records(
            store: founder, places: [PlaceShare.Held(groupId: F.new, memberId: F.y, youId: F.y)])))["records"] as? [[String: Any]]
        XCTAssertNil(HeldRule.check(elsewhere, max: Invites.joinMax), "the record itself is well formed")
        XCTAssertEqual(HeldRule.check(elsewhere, max: Invites.joinMax, place: (F.old, F.c)), "records",
                       "a join carrying another group's record")
    }

    /// Item 5 (review M2): more than fifty wanted places go in calls of at most fifty: 51 go as [50, 1], each call
    /// under the server's rule, and all 51 are answered.
    @MainActor
    func testFiftyOneWantedPlacesGoAsFiftyThenOne() async throws {
        let store = tempStore()
        let groups = (0..<51).map { String(format: "%032x", 0xB000 + $0) }
        try LedgerStore.Sync.appendGroupOps(store, [.you(id: me, name: "Al")] + groups.map {
            .create(groupId: $0, name: "G", settlementCurrency: "USD",
                    members: [GroupOpMemberIn(id: me, name: "Al"), GroupOpMemberIn(id: bo, name: "Bo")])
        }, placing: .none)
        try store.rebuildProjection()
        XCTAssertEqual(try wantedIn(store).count, 51, "one place per group")
        try CloudRecords.saveGroupsUp(store, Set(groups))
        var sizes: [Int] = []
        var rule: [String?] = []
        PlaceShare.send = { data in
            let records = data["records"] as? [[String: Any]]
            sizes.append(records?.count ?? -1)
            rule.append(HeldRule.check(records, max: PlaceShare.callMax))
            return ["shared": (records ?? []).compactMap { $0["id"] as? String }]
        }
        defer { PlaceShare.send = PlaceShare.realSend }
        let answer = await PlaceShare.share(store: store)
        XCTAssertEqual(answer, .done)
        XCTAssertEqual(sizes, [50, 1])
        XCTAssertEqual(rule, [nil, nil], "each call passes the server's rule")
        XCTAssertEqual(PlaceShare.answered(store).count, 51)
    }

    /// A push with nothing pending shares the places after the aliases (source pinned, both branches).
    func testAPushSharesThePlacesAfterTheAliases() throws {
        let url = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("Skipti/App/CloudSync.swift")
        let source = try String(contentsOf: url, encoding: .utf8)
        let nothingParts = source.components(separatedBy: "guard !pending.isEmpty else {")
        XCTAssertEqual(nothingParts.count, 2)
        let nothing = nothingParts.last!.components(separatedBy: "return 0")[0]
        XCTAssertTrue(nothing.contains("await AliasShare.share(store: store)"))
        XCTAssertTrue(nothing.contains("await PlaceShare.share(store: store)"))
        if let alias = nothing.range(of: "AliasShare"), let place = nothing.range(of: "PlaceShare") {
            XCTAssertLessThan(alias.lowerBound, place.lowerBound, "the aliases first")
        }
        let afterParts = source.components(separatedBy: "if !offline { await AliasShare.share(store: store) }")
        XCTAssertEqual(afterParts.count, 2)
        let after = afterParts.last!.components(separatedBy: "return count")[0]
        XCTAssertTrue(after.contains("if !offline { await PlaceShare.share(store: store) }"))
    }
}

/// D-230 (A5, A8; item 6): the server's `groups.checkHeldRecords` (firebase/functions/groups.js) read line for line
/// over the call's records (`Removal.payload`'s shape): 1 to `max` records; each the record shape (`recordShape`: hlc
/// millis and counter not negative, a 32-hex device, id = millis-counter-device, a 32-hex author, a base64 blob of at
/// most 65536 and nonce of at most 64 characters, version, epoch and flags, every integer a safe one), no record id twice, type "3"; the blob a `heldPlace` with exactly the
/// keys op, group, member and you, three 32-lowercase-hex ids; no (group, member, you) twice (`triple: false`: the old
/// (group, member) rule). `place`: acceptInvite's own requirements, every record in the invite's group and at least
/// one for the invite's (group, member). nil when the server would take the list, "records" when it refuses it. Android's twin: HeldRule in
/// ProfileSyncTest.kt.
enum HeldRule {
    static func hex32(_ s: String) -> Bool { s.count == 32 && s.allSatisfy { "0123456789abcdef".contains($0) } }

    /// recordShape's caps on the payload's base64 strings.
    static let maxBlob = 65_536, maxNonce = 64
    /// JavaScript's safe integers (`Number.isSafeInteger`).
    static let safe: Int64 = 9_007_199_254_740_991

    private static func int(_ any: Any?) -> Int64? {
        let v: Int64
        if let i = any as? Int64 { v = i } else if let i = any as? Int { v = Int64(i) } else { return nil }
        return (-safe...safe).contains(v) ? v : nil
    }

    /// A non-empty base64 string of at most `max` characters (the server's `b64`).
    private static func b64(_ any: Any?, _ max: Int) -> Bool {
        guard let s = any as? String, !s.isEmpty, s.count <= max else { return false }
        return s.range(of: "^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$", options: .regularExpression) != nil
    }

    static func check(_ records: [[String: Any]]?, max: Int, triple: Bool = true,
                      place: (group: String, member: String)? = nil) -> String? {
        guard let records, records.count >= 1, records.count <= max else { return "records" }
        var ids = Set<String>(), seen = Set<String>(), placed = false
        for r in records {
            guard let hlc = r["hlc"] as? [String: Any], let millis = int(hlc["millis"]), let counter = int(hlc["counter"]),
                  let device = hlc["device"] as? String, millis >= 0, counter >= 0, hex32(device),
                  let id = r["id"] as? String, id == "\(millis)-\(counter)-\(device)",
                  let author = r["deviceId"] as? String, hex32(author),
                  let payload = r["payload"] as? [String: Any],
                  let blob = payload["blob"] as? String, b64(blob, maxBlob), b64(payload["nonce"], maxNonce),
                  int(payload["version"]) != nil, int(payload["epoch"]) != nil, int(payload["flags"]) != nil,
                  ids.insert(id).inserted, r["type"] as? String == "3",
                  let data = Data(base64Encoded: blob),
                  let op = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
                  op["op"] as? String == "heldPlace",
                  Set(op.keys).isSubset(of: ["op", "group", "member", "you"]),
                  let g = op["group"] as? String, let m = op["member"] as? String, let y = op["you"] as? String,
                  [g, m, y].allSatisfy(hex32),
                  seen.insert(triple ? g + ":" + m + ":" + y : g + ":" + m).inserted
            else { return "records" }
            if let place {
                guard g == place.group else { return "records" }
                if m == place.member { placed = true }
            }
        }
        return place == nil || placed ? nil : "records"
    }
}
