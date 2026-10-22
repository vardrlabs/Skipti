import FirebaseFirestore
import XCTest
@testable import Skipti

/// D-191: the Firebase account. Android's twin is AccountTest.
final class AccountTests: XCTestCase {
    private func tempStore() -> LedgerStore {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("account-\(UUID().uuidString)", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return LedgerStore(path: dir.appendingPathComponent("vault.db").path)
    }

    func testFirebaseIsConfiguredInCodeForSkipti26() {
        let options = FirebaseConfig.options
        XCTAssertEqual(options.projectID, "skipti-26")
        XCTAssertEqual(options.googleAppID, "1:403030719865:ios:26a313b36c35afcdcd43a8")
        XCTAssertEqual(options.gcmSenderID, "403030719865", "the sender id is the project number")
        XCTAssertEqual(options.bundleID, Bundle.main.bundleIdentifier)
        XCTAssertTrue(options.apiKey?.hasPrefix("AIza") == true)
    }

    func testOneAccountPerPhone() {
        XCTAssertTrue(Account.owns(stored: nil, incoming: "u1"))
        XCTAssertTrue(Account.owns(stored: "u1", incoming: "u1"))
        XCTAssertFalse(Account.owns(stored: "u1", incoming: "u2"))
    }

    /// The old relay's owner names an account that no longer exists: it must
    /// not lock a tester out of the Firebase account.
    func testOnlyTheFirebaseOwnerCounts() throws {
        let store = tempStore()
        try AccountOwner.remember("relay-subject", store)
        XCTAssertNil(Account.storedOwner(store))
        try store.kvSet(key: Account.ownerKey, value: Data("u1".utf8))
        XCTAssertEqual(Account.storedOwner(store), "u1")
    }

    /// Apple returns the hashed nonce inside its token and Firebase checks it
    /// against the raw one, so the request must carry SHA-256 in lowercase
    /// hex. FIPS 180-2's "abc" vector, not read back from the implementation.
    func testAppleNonceIsSha256Hex() {
        XCTAssertEqual(
            AppleSignIn.sha256Hex("abc"),
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        )
    }

    /// The iOS client is a public client: PKCE, and never a client secret.
    func testGoogleTokenRequestIsPkceWithNoSecret() throws {
        let body = try XCTUnwrap(GoogleSignIn.tokenRequestBody(
            code: "c/1", verifier: "v", clientID: FirebaseConfig.iosClientID
        ))
        let fields = Dictionary(uniqueKeysWithValues: body.split(separator: "&").map { pair -> (String, String) in
            let parts = pair.split(separator: "=", maxSplits: 1).map(String.init)
            return (parts[0], parts[1].removingPercentEncoding ?? "")
        })
        XCTAssertEqual(fields, [
            "grant_type": "authorization_code",
            "code": "c/1",
            "code_verifier": "v",
            "client_id": FirebaseConfig.iosClientID,
            "redirect_uri": "com.googleusercontent.apps.403030719865-pmgfvj4eo4l73bao8lqblqmpera4augj:/oauth2redirect",
        ])
        XCTAssertNil(GoogleSignIn.tokenRequestBody(code: "c", verifier: "v", clientID: "not-a-client"))
    }

    // MARK: - D-193: the profile and this phone in the cloud

    /// The rules' own lists, read from `firebase/firestore.rules` (the
    /// simulator runs on this Mac, so the repo is readable from the test).
    private func rulesLists() throws -> (profile: [String], deviceCreate: [String], deviceUpdate: [String]) {
        let rulesURL = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("firebase/firestore.rules")
        let rules = try String(contentsOf: rulesURL, encoding: .utf8)
        func list(after marker: String, call: String) throws -> [String] {
            let start = try XCTUnwrap(rules.range(of: marker), marker).upperBound
            let open = try XCTUnwrap(rules.range(of: call + "([", range: start..<rules.endIndex)).upperBound
            let close = try XCTUnwrap(rules.range(of: "])", range: open..<rules.endIndex)).lowerBound
            return rules[open..<close].split(separator: ",").map {
                $0.trimmingCharacters(in: .whitespacesAndNewlines).trimmingCharacters(in: CharacterSet(charactersIn: "'"))
            }
        }
        return (try list(after: "match /users/{uid} {", call: "hasOnly"),
                try list(after: "match /devices/{deviceId} {", call: "hasOnly"),
                try list(after: "match /devices/{deviceId} {", call: "changesOnly"))
    }

    /// A field the rules do not allow would make every write fail; a field
    /// the rules allow but the app does not send is a hole nobody tested.
    func testProfileAndDeviceFieldsAreExactlyTheRules() throws {
        let rules = try rulesLists()
        XCTAssertEqual(CloudProfile.profileFields, rules.profile)
        XCTAssertEqual(CloudProfile.deviceCreateFields, rules.deviceCreate)
        XCTAssertEqual(CloudProfile.deviceUpdateFields, rules.deviceUpdate)
        XCTAssertEqual(Set(CloudProfile.profileCreate(participantId: "p", name: "Priya").keys), Set(rules.profile))
        XCTAssertEqual(Set(CloudProfile.profileCreate(participantId: "p").keys), Set(rules.profile).subtracting(["name"]))
        XCTAssertEqual(Set(CloudProfile.profileUpdate().keys), ["updatedAt"])
        XCTAssertEqual(Set(CloudProfile.profileUpdate(name: "Priya").keys), ["updatedAt", "name"])
        // D-197: the rules' 1 to 60 characters.
        XCTAssertNil(CloudProfile.cloudName("   "))
        XCTAssertEqual(CloudProfile.cloudName(" Priya "), "Priya")
        XCTAssertEqual(CloudProfile.cloudName(String(repeating: "x", count: 70))?.count, 60)
        // D-196: the push address is written on its own, never at registration.
        XCTAssertEqual(Set(CloudProfile.deviceCreate(name: "iPhone", appVersion: "0.4.0").keys),
                       Set(rules.deviceCreate).subtracting(["fcmToken"]))
        XCTAssertEqual(Set(CloudProfile.deviceUpdate(name: "iPhone", appVersion: "0.4.0").keys),
                       Set(rules.deviceUpdate).subtracting(["fcmToken"]))
        XCTAssertEqual(Set(CloudProfile.tokenUpdate("t").keys), ["fcmToken", "lastSeen"])
        XCTAssertEqual(CloudProfile.tokenUpdate("t")["fcmToken"] as? String, "t")
        XCTAssertNotNil(CloudProfile.tokenUpdate(nil)["fcmToken"])
        XCTAssertNil(CloudProfile.tokenUpdate(nil)["fcmToken"] as? String, "removal deletes the field")
        XCTAssertEqual(CloudProfile.deviceCreate(name: "iPhone", appVersion: "0.4.0")["platform"] as? String, "ios")
    }

    /// D-222 (L58): You's phone count: the account's devices not marked signed out; the words for one and many.
    func testThePhoneCountLeavesOutSignedOutPhones() {
        XCTAssertEqual(PhonesCopy.count(1), "1 phone")
        XCTAssertEqual(PhonesCopy.count(3), "3 phones")
        let docs: [(id: String, data: [String: Any])] = [
            ("a", ["name": "Pixel", "platform": "android"]),
            ("b", ["name": "iPhone", "signedOut": true]),
            ("c", ["platform": "ios", "signedOut": false]),
        ]
        XCTAssertEqual(Phones.activeCount(docs), 2)
        XCTAssertEqual(Phones.activeCount([]), 0)
    }

    /// One id per phone: made once, kept, 32 lowercase hex (the rules refuse
    /// any other device document id).
    func testThePhonesIdIsMadeOnceAndIs32Hex() throws {
        let store = tempStore()
        let id = try CloudProfile.deviceId(store)
        XCTAssertNotNil(id.range(of: "^[0-9a-f]{32}$", options: .regularExpression), id)
        XCTAssertEqual(try CloudProfile.deviceId(store), id)
        XCTAssertNotEqual(try CloudProfile.deviceId(tempStore()), id)
    }

    // MARK: - D-194: the personal ledger as Firestore documents

    private func sampleRecord(group: String? = nil, device: String = String(repeating: "b", count: 32)) -> RecordWire {
        RecordWire(
            version: 1, recordType: 1, groupId: group, epoch: 0,
            hlcMillis: 1_790_000_000_123, hlcCounter: 7, hlcDeviceId: device, authorDeviceId: device,
            nonceSession: Data([0x45, 0x58, 0x50, 0x01]), flags: 0, blob: Data(#"{"paidMinor":120000}"#.utf8)
        )
    }

    func testARecordSurvivesTheTripThroughADocument() throws {
        let record = sampleRecord()
        let document = CloudRecords.document(record, uid: "u1")
        XCTAssertEqual(document["authorUid"] as? String, "u1")
        XCTAssertEqual(CloudRecords.record(document, groupId: nil), record)
        XCTAssertEqual(CloudRecords.documentId(record), "1790000000123-7-" + String(repeating: "b", count: 32))
    }

    /// The document's fields are exactly the rules' `validRecord` list (bar
    /// `notice`, which group records carry from unit 6).
    func testARecordDocumentHasOnlyTheRulesFields() throws {
        let rules = try String(contentsOf: URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("firebase/firestore.rules"), encoding: .utf8)
        let start = try XCTUnwrap(rules.range(of: "function validRecord() {")).upperBound
        let open = try XCTUnwrap(rules.range(of: "hasOnly([", range: start..<rules.endIndex)).upperBound
        let close = try XCTUnwrap(rules.range(of: "])", range: open..<rules.endIndex)).lowerBound
        let allowed = rules[open..<close].split(separator: ",").map {
            $0.trimmingCharacters(in: .whitespacesAndNewlines).trimmingCharacters(in: CharacterSet(charactersIn: "'"))
        }
        XCTAssertEqual(CloudRecords.fields, allowed)
        XCTAssertEqual(Set(CloudRecords.document(sampleRecord(), uid: "u1").keys), Set(allowed).subtracting(["notice"]))
    }

    /// Only records the cloud lacks go up, oldest first, personal and group
    /// alike; a record the rules would refuse stays on the phone.
    func testOnlyPendingRecordsGoUp() {
        let mine = sampleRecord()
        var older = sampleRecord()
        older.hlcMillis -= 1
        var grouped = sampleRecord(group: String(repeating: "c", count: 32))
        grouped.hlcCounter = 8
        let fixture = sampleRecord(device: "device-1")
        var oddGroup = sampleRecord(group: "group-1")
        oddGroup.hlcCounter = 10
        var sent = sampleRecord()
        sent.hlcCounter = 9
        let pending = CloudRecords.pending([mine, grouped, fixture, oddGroup, sent, older],
                                           uploaded: [CloudRecords.documentId(sent)])
        XCTAssertEqual(pending, [older, mine, grouped])
    }

    /// A group goes up with exactly the fields the rules let a creator write,
    /// this account alone in it, and a name the rules accept.
    func testAGroupGoesUpWithOnlyItsCreator() throws {
        let rules = try String(contentsOf: URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("firebase/firestore.rules"), encoding: .utf8)
        let start = try XCTUnwrap(rules.range(of: "match /groups/{groupId} {")).upperBound
        let open = try XCTUnwrap(rules.range(of: "hasOnly([", range: start..<rules.endIndex)).upperBound
        let close = try XCTUnwrap(rules.range(of: "])", range: open..<rules.endIndex)).lowerBound
        let allowed = rules[open..<close].split(separator: ",").map {
            $0.trimmingCharacters(in: .whitespacesAndNewlines).trimmingCharacters(in: CharacterSet(charactersIn: "'"))
        }
        XCTAssertEqual(CloudRecords.groupFields, allowed)
        let group = CloudRecords.groupDocument(name: "  Goa trip ", uid: "u1")
        XCTAssertEqual(Set(group.keys), Set(allowed).subtracting(["emoji"]))
        XCTAssertEqual(group["memberUids"] as? [String], ["u1"])
        XCTAssertEqual(group["createdBy"] as? String, "u1")
        XCTAssertEqual(group["name"] as? String, "Goa trip")
        XCTAssertEqual(CloudRecords.groupDocument(name: " ", uid: "u1")["name"] as? String, "Group")
        XCTAssertEqual((CloudRecords.groupDocument(name: String(repeating: "x", count: 90), uid: "u1")["name"] as? String)?.count, 80)
    }

    func testAMalformedDocumentIsNotARecord() {
        let good = CloudRecords.document(sampleRecord(), uid: "u1")
        var noHlc = good
        noHlc["hlc"] = nil
        var badDevice = good
        badDevice["deviceId"] = "not-hex"
        var bigCounter = good
        bigCounter["hlc"] = ["millis": Int64(1), "counter": Int64(UInt32.max) + 1, "device": String(repeating: "b", count: 32)]
        var badType = good
        badType["type"] = "300"
        for document in [noHlc, badDevice, bigCounter, badType] {
            XCTAssertNil(CloudRecords.record(document, groupId: nil))
        }
    }

    // MARK: - D-195: what a group's log may bring in

    func testAGroupsLogBringsInOnlyThatGroup() throws {
        let group = String(repeating: "c", count: 32), other = String(repeating: "e", count: 32)
        let me = String(repeating: "a", count: 32), them = String(repeating: "b", count: 32)
        func record(_ type: UInt8, _ blob: Data, device: String = them, scope: String? = nil) -> RecordWire {
            RecordWire(version: 1, recordType: type, groupId: scope ?? group, epoch: 0, hlcMillis: 1, hlcCounter: 0,
                       hlcDeviceId: device, authorDeviceId: device, nonceSession: Data(), flags: 0, blob: blob)
        }
        func expense(_ g: String) throws -> Data {
            try JSONEncoder().encode(LedgerStore.ExpenseDTO(
                payer: me, paidMinor: 100, currencyCode: "USD", group: g, conversion: nil,
                shares: [.init(id: me, amountMinor: 100)], note: nil, timestampMs: nil, category: nil, addedBy: nil))
        }
        func refusal(_ r: RecordWire) -> CloudRecords.Refusal? {
            CloudRecords.refusal(r, from: group, thisDevice: me)
        }
        XCTAssertNil(refusal(record(1, try expense(group))))
        XCTAssertNil(refusal(record(3, try ffiEncodeGroupOp(op: .renameGroup(groupId: group, name: "Goa")))))
        XCTAssertEqual(refusal(record(1, try expense(other))), .wrongGroup, "another group's money")
        XCTAssertEqual(refusal(record(3, try ffiEncodeGroupOp(op: .renameGroup(groupId: other, name: "x")))), .wrongGroup)
        XCTAssertEqual(refusal(record(3, try ffiEncodeGroupOp(op: .you(id: them, name: nil)))), .personal, "your own you")
        XCTAssertEqual(refusal(record(1, try expense(group), device: me)), .thisDevice, "a record claiming to be this phone's")
        XCTAssertEqual(refusal(record(1, try expense(group), scope: other)), .wrongScope)
        XCTAssertEqual(refusal(record(9, Data())), .unknownType)
        XCTAssertEqual(refusal(record(1, Data("x".utf8))), .unreadable)
    }

    // MARK: - D-195: invites

    func testAnInviteLinkCarriesOnlyItsToken() {
        let token = String(repeating: "A", count: 42) + "_"
        let link = InviteLink.make(token: token)
        XCTAssertEqual(link, "https://skipti.vardrlabs.com/join#" + token)
        XCTAssertEqual(InviteLink.token("  \(link)\n"), token)
        for bad in ["http://skipti.vardrlabs.com/join#" + token, "https://evil.example/join#" + token,
                    "https://skipti.vardrlabs.com/join#" + token + "A", "https://skipti.vardrlabs.com/join#short",
                    "https://skipti.vardrlabs.com/join/x#" + token, "https://skipti.vardrlabs.com/join#" + token + "/"] {
            XCTAssertNil(InviteLink.token(bad), bad)
        }
    }

    /// Firebase's callable protocol: {"result": …} or {"error": {"status"}}.
    func testAFunctionsReplyIsItsResultOrItsRefusal() throws {
        XCTAssertEqual(try CloudFunctions.decode(Data(#"{"result":{"token":"t"}}"#.utf8))["token"] as? String, "t")
        XCTAssertThrowsError(try CloudFunctions.decode(Data(#"{"error":{"status":"PERMISSION_DENIED","message":"no"}}"#.utf8))) {
            XCTAssertEqual($0 as? CloudFunctions.Failure, .refused("PERMISSION_DENIED"))
        }
        XCTAssertThrowsError(try CloudFunctions.decode(Data("<html>".utf8))) {
            XCTAssertEqual($0 as? CloudFunctions.Failure, .offline)
        }
        XCTAssertEqual(InviteCopy.failure(CloudFunctions.Failure.refused("FAILED_PRECONDITION")), InviteCopy.used)
        XCTAssertEqual(InviteCopy.failure(CloudFunctions.Failure.refused("NOT_FOUND")), InviteCopy.used)
        XCTAssertEqual(InviteCopy.failure(CloudFunctions.Failure.refused("PERMISSION_DENIED")), InviteCopy.notMember)
        XCTAssertEqual(InviteCopy.failure(CloudFunctions.Failure.signedOut), InviteCopy.needsAccount)
        XCTAssertEqual(InviteCopy.failure(CloudFunctions.Failure.offline), InviteCopy.offline)
    }

    /// D-218: inviting a person into a place someone already holds is refused by the
    /// cloud with `already-exists`; the phone says so in the cloud's own words. Only the
    /// invite calls read it that way: the username path still says "taken".
    func testAHeldPlaceHasItsOwnRefusalAndTheUsernamePathIsUntouched() {
        XCTAssertEqual(InviteCopy.held, "Someone has already joined as this person.")
        XCTAssertEqual(InviteCopy.failure(CloudFunctions.Failure.refused("ALREADY_EXISTS")), InviteCopy.held)
        XCTAssertEqual(People.inviteFailure(CloudFunctions.Failure.refused("ALREADY_EXISTS")), InviteCopy.held)
        // Every other status reads as before, through People.failure.
        XCTAssertEqual(People.inviteFailure(CloudFunctions.Failure.refused("FAILED_PRECONDITION")), PeopleCopy.alreadyIn)
        XCTAssertEqual(People.inviteFailure(CloudFunctions.Failure.refused("NOT_FOUND")), PeopleCopy.noMatch)
        XCTAssertEqual(People.inviteFailure(CloudFunctions.Failure.refused("RESOURCE_EXHAUSTED")), PeopleCopy.limit)
        XCTAssertEqual(People.inviteFailure(CloudFunctions.Failure.refused("PERMISSION_DENIED")), InviteCopy.notMember)
        XCTAssertEqual(People.inviteFailure(CloudFunctions.Failure.offline), InviteCopy.offline)
        // The negative: the username path is untouched.
        XCTAssertEqual(People.failure(CloudFunctions.Failure.refused("ALREADY_EXISTS")), PeopleCopy.usernameTaken)
        XCTAssertNotEqual(PeopleCopy.usernameTaken, InviteCopy.held)
    }

    /// D-218 fix (T3-m1): every failure path of `People.invite` and `People.inviteKnown` maps through
    /// `People.inviteFailure`, never `People.failure` (which reads ALREADY_EXISTS as "taken"). Only a
    /// FindPerson invite can receive the refusal in practice, but nothing else proved the call sites, so
    /// the source is pinned: the next bare `catch` after each call site (FindPerson's own, or `run`'s
    /// for the AddPerson calls) must use `inviteFailure`. Twin: AccountTest.
    func testEveryInviteCallSiteMapsItsFailureThroughInviteFailure() throws {
        let url = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("Skipti/App/People.swift")
        let source = try String(contentsOf: url, encoding: .utf8)
        var sites = 0
        for call in ["People.invite(", "People.inviteKnown("] {
            var from = source.startIndex
            while let hit = source.range(of: call, range: from..<source.endIndex) {
                sites += 1
                let rest = source[hit.upperBound...]
                let catchRange = try XCTUnwrap(rest.range(of: #"\}\s*catch\s*\{"#, options: .regularExpression),
                                               "a bare catch after \(call)")
                let handler = rest[catchRange.upperBound...].prefix(while: { $0 != "}" })
                XCTAssertTrue(handler.contains("People.inviteFailure("), "\(call) failure handler: \(handler)")
                XCTAssertFalse(handler.contains("People.failure("), "\(call) failure handler: \(handler)")
                from = hit.upperBound
            }
        }
        XCTAssertEqual(sites, 3, "invite twice (FindPerson, AddPerson) and inviteKnown once")
    }

    /// D-218 fix: a held place stays held until its person is removed, so the invite screen offers no
    /// "Try again" for it; every other failure still does. The screen reads `canRetry` (source pinned).
    func testAHeldPlaceIsNotRetryable() throws {
        XCTAssertFalse(InviteCopy.canRetry(CloudFunctions.Failure.refused("ALREADY_EXISTS")))
        for failure in [CloudFunctions.Failure.refused("FAILED_PRECONDITION"), .refused("PERMISSION_DENIED"),
                        .refused("INTERNAL"), .offline, .signedOut] {
            XCTAssertTrue(InviteCopy.canRetry(failure), "\(failure)")
        }
        let url = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("Skipti/App/Invites.swift")
        let source = try String(contentsOf: url, encoding: .utf8)
        XCTAssertTrue(source.contains("retry: InviteCopy.canRetry(error))"), "the invite screen records whether to offer a retry")
        XCTAssertTrue(source.contains("if retry {"), "and offers Try again only then")
    }

    /// D-229 decision 52: acceptInvite refuses someone who already holds a place in the invite's group with
    /// `already-exists` and `details.member == true`; the join sheet says "You're already in this group." Every other
    /// `already-exists` (the held place, no details) reads as before. Twin: AccountTest.
    func testAnInviteIntoAGroupYouAreInHasItsOwnWords() throws {
        func refusal(_ json: String) -> CloudFunctions.Failure? {
            var caught: CloudFunctions.Failure?
            XCTAssertThrowsError(try CloudFunctions.decode(Data(json.utf8))) { caught = $0 as? CloudFunctions.Failure }
            return caught
        }
        XCTAssertEqual(InviteCopy.alreadyMember, "You're already in this group.")
        XCTAssertEqual(refusal(#"{"error":{"status":"ALREADY_EXISTS","message":"You're already in this group.","details":{"member":true}}}"#),
                       .alreadyMember)
        // The negatives: the held place (no details), a false or non-boolean flag, another status with the flag.
        XCTAssertEqual(refusal(#"{"error":{"status":"ALREADY_EXISTS","message":"held"}}"#), .refused("ALREADY_EXISTS"))
        XCTAssertEqual(refusal(#"{"error":{"status":"ALREADY_EXISTS","details":{"member":false}}}"#), .refused("ALREADY_EXISTS"))
        XCTAssertEqual(refusal(#"{"error":{"status":"ALREADY_EXISTS","details":{"member":"true"}}}"#), .refused("ALREADY_EXISTS"))
        XCTAssertEqual(refusal(#"{"error":{"status":"ALREADY_EXISTS","details":"member"}}"#), .refused("ALREADY_EXISTS"))
        XCTAssertEqual(refusal(#"{"error":{"status":"PERMISSION_DENIED","details":{"member":true}}}"#), .refused("PERMISSION_DENIED"))

        XCTAssertEqual(InviteCopy.failure(CloudFunctions.Failure.alreadyMember), InviteCopy.alreadyMember)
        XCTAssertEqual(InviteCopy.failure(CloudFunctions.Failure.refused("ALREADY_EXISTS")), InviteCopy.held)
        XCTAssertNotEqual(InviteCopy.alreadyMember, InviteCopy.held)
        XCTAssertFalse(InviteCopy.canRetry(CloudFunctions.Failure.alreadyMember), "being in the group does not pass")
    }

    /// Joining writes a claim core accepts: the named member, this account's
    /// "you", this phone.
    func testJoiningClaimsTheNamedPerson() throws {
        let group = String(repeating: "c", count: 32), member = String(repeating: "d", count: 32)
        let you = String(repeating: "a", count: 32), device = String(repeating: "b", count: 32)
        let blob = try ffiEncodeGroupOp(op: Invites.claim(group: group, member: member, you: you, device: device))
        guard case .claim(let g, let m, let y, let devices) = try ffiDecodeGroupOp(blob: blob) else {
            return XCTFail("not a claim")
        }
        XCTAssertEqual([g, m, y], [group, member, you])
        XCTAssertEqual(devices.map(\.deviceId), [device])
    }

    /// D-230: joining sends the held place the server marks: the account's "you" (`p.you`, the earliest) on the
    /// invite's member, built through the group's own record builder, with the call's data carrying it beside the
    /// token. A1 (second form): a rejoin also sends every place a claim of this account holds in the group, first,
    /// with that claim's id. A5: at most 11, the new place last (a vault with no "you": `testTheJoinSendsTheAccountsYou`).
    func testJoiningSendsTheHeldPlace() throws {
        let store = tempStore()
        let group = String(repeating: "c", count: 32), member = String(repeating: "d", count: 32)
        _ = try store.setYourName("Al")
        let you = try XCTUnwrap(try LedgerStore.Sync.projectGroups(store).you?.id)
        XCTAssertEqual(you, try store.ensureSelf().id)
        let first = try Invites.heldRecords(store: store, group: group, member: member, nowMs: 4_000_000_000_000)
        XCTAssertEqual(first.count, 1, "a first join: the new place alone")
        let r = try XCTUnwrap(first.first)
        XCTAssertEqual(r.groupId, group)
        XCTAssertEqual(r.hlcMillis, 4_000_000_000_000, "stamped at nowMs")
        XCTAssertEqual(r.recordType, LedgerStore.Sync.groupOpType)
        guard case .heldPlace(let g, let m, let y) = try ffiDecodeGroupOp(blob: r.blob) else { return XCTFail("not a held place") }
        XCTAssertEqual([g, m, y], [group, member, you])
        let data = Invites.acceptData(token: "t", records: first)
        XCTAssertEqual(data["token"] as? String, "t")
        XCTAssertEqual(data.keys.sorted(), ["records", "token"])
        XCTAssertEqual((data["records"] as? [[String: Any]])?.compactMap { $0["id"] as? String },
                       [CloudRecords.documentId(r)])
        XCTAssertEqual((data["records"] as? [[String: Any]])?.first?["type"] as? String,
                       String(LedgerStore.Sync.groupOpType))
        XCTAssertEqual(Invites.acceptData(token: "t", records: []).keys.sorted(), ["token"])
        XCTAssertFalse(try store.allRecords().contains { CloudRecords.heldType($0) }, "never put in the vault")

        // A1: a rejoin. Tim's group R; this account claimed `old` (6), was removed from it, and is invited to `new` (7).
        let r2 = String(repeating: "e", count: 32), tim = String(repeating: "4", count: 32)
        let old = String(repeating: "6", count: 32), new = String(repeating: "7", count: 32)
        let device = try LedgerStore.Sync.deviceId(store)
        try LedgerStore.Sync.appendGroupOps(store, [
            .create(groupId: r2, name: "R", settlementCurrency: "USD", members: [
                GroupOpMemberIn(id: tim, name: "Tim"), GroupOpMemberIn(id: old, name: "Al"),
                GroupOpMemberIn(id: new, name: "Al again")]),
            Invites.claim(group: r2, member: old, you: you, device: device),
            .removeMember(groupId: r2, memberId: old),
        ], placing: .none)
        let rejoin = try Invites.heldRecords(store: store, group: r2, member: new, nowMs: 4_000_000_100_000)
        XCTAssertEqual(try rejoin.map { try ffiDecodeGroupOp(blob: $0.blob) }, [
            .heldPlace(groupId: r2, memberId: old, youId: you), .heldPlace(groupId: r2, memberId: new, youId: you),
        ], "the old place first, then the new one")
        if rejoin.count == 2 {
            XCTAssertTrue((rejoin[0].hlcMillis, rejoin[0].hlcCounter) < (rejoin[1].hlcMillis, rejoin[1].hlcCounter),
                          "stamped in that order")
        }
        XCTAssertEqual((Invites.acceptData(token: "t", records: rejoin)["records"] as? [[String: Any]])?.count, 2)
        // Another group's claims never ride along.
        XCTAssertEqual(try Invites.heldRecords(store: store, group: group, member: member).count, 1)

        // A5: twelve places this account's claims hold in group K: ten ride along (member order), the new one last.
        let k = String(repeating: "f", count: 32)
        let held = (1...12).map { String(format: "%032x", 0x100 + $0) }
        let joining = String(repeating: "9", count: 32)
        try LedgerStore.Sync.appendGroupOps(store, [
            .create(groupId: k, name: "K", settlementCurrency: "USD",
                    members: [GroupOpMemberIn(id: tim, name: "Tim")] + held.map { GroupOpMemberIn(id: $0, name: "P" + $0.suffix(2)) }
                        + [GroupOpMemberIn(id: joining, name: "New")]),
        ] + held.map { Invites.claim(group: k, member: $0, you: you, device: device) }, placing: .none)
        let capped = try Invites.heldRecords(store: store, group: k, member: joining)
        XCTAssertEqual(capped.count, Invites.joinMax)
        XCTAssertEqual(Invites.joinMax, 11)
        XCTAssertEqual(try capped.map { r -> String in
            guard case .heldPlace(_, let m, _) = try ffiDecodeGroupOp(blob: r.blob) else { return "" }
            return m
        }, Array(held.prefix(10)) + [joining], "the first ten in member order, the new place last")
        XCTAssertNil(HeldRule.check(Invites.acceptData(token: "t", records: capped)["records"] as? [[String: Any]],
                                    max: Invites.joinMax, place: (k, joining)))
        XCTAssertFalse(try store.allRecords().contains { CloudRecords.heldType($0) }, "never put in the vault")
    }

    private func decodedHeld(_ records: [RecordWire]) throws -> [GroupOpIn] {
        try records.map { try ffiDecodeGroupOp(blob: $0.blob) }
    }

    /// A store whose account has two ids: this phone's (`me`, minted by `setYourName`) and an earlier one from
    /// another phone (`first`), which is the account's "you" (the projection's, the earliest). The other phone's
    /// record is put as a pull puts it, before the pull's rebuild: on iOS a rebuild adopts the account's "you" as
    /// this phone's self (D-153), so only in this window does `ensureSelf()` still give this phone's own id, which is
    /// the case that tells `p.you` from `ensureSelf()`.
    private func twoPhoneStore() throws -> (store: LedgerStore, me: String, first: String) {
        let store = tempStore()
        _ = try store.setYourName("Al")
        let me = try store.ensureSelf().id
        let first = String(repeating: "1", count: 32)
        _ = try store.applyRecord(try LedgerStore.Sync.groupOpRecord(
            .you(id: first, name: "Al"), hlc: HlcStamp(millis: 1_000, counter: 0), deviceId: String(repeating: "9", count: 32)))
        return (store, me, first)
    }

    /// D-230 item 2 (A5): the new place's record names the account's "you" (the projection's, its earliest id),
    /// never this phone's own id when they differ. A brand-new install (no "you" yet) mints it first, as the join's
    /// claim does right after, so its first join carries its mark: exactly one record, naming the minted "you".
    /// Twin of Android's d230_the_join_sends_the_accounts_you_and_nothing_without_one (as amended).
    func testTheJoinSendsTheAccountsYou() throws {
        let group = String(repeating: "c", count: 32), member = String(repeating: "d", count: 32)
        let empty = tempStore()
        XCTAssertNil(try LedgerStore.Sync.projectGroups(empty).you, "a brand-new install: no you yet")
        XCTAssertNil(try empty.loadSelf())
        let one = try Invites.heldRecords(store: empty, group: group, member: member, nowMs: 4_000_000_000_000)
        let minted = try XCTUnwrap(try empty.loadSelf()?.id, "the join minted the you")
        XCTAssertEqual(try decodedHeld(one), [.heldPlace(groupId: group, memberId: member, youId: minted)],
                       "exactly one record, naming the minted you")
        XCTAssertEqual(try LedgerStore.Sync.projectGroups(empty).you?.id, minted, "the claim after the join names it too")
        XCTAssertEqual(Invites.acceptData(token: "t", records: one).keys.sorted(), ["records", "token"])
        XCTAssertNil(HeldRule.check(Invites.acceptData(token: "t", records: one)["records"] as? [[String: Any]],
                                    max: Invites.joinMax, place: (group, member)))

        // A two-phone account: this phone's id is not the account's "you".
        let (store, me, first) = try twoPhoneStore()
        XCTAssertEqual(try LedgerStore.Sync.projectGroups(store).you?.id, first)
        XCTAssertNotEqual(me, first, "the case bites")
        XCTAssertEqual(try store.ensureSelf().id, me, "this phone's self is still its own id")
        XCTAssertEqual(try decodedHeld(try Invites.heldRecords(store: store, group: group, member: member)),
                       [.heldPlace(groupId: group, memberId: member, youId: first)])
    }

    /// D-230 item 4 (A8): a claim of this account on the invite's own member under its "you" is that very pair: one
    /// record for it, never two (the server refuses a (group, member, you) twice, and the whole call with it); a
    /// claim on that member under another of the account's ids is its own record, before the new place. Twin of
    /// Android's join triple test.
    func testTheJoinSendsTheInvitesPlaceOncePerId() throws {
        let (store, me, first) = try twoPhoneStore()
        let g = String(repeating: "c", count: 32), m = String(repeating: "d", count: 32)
        let tim = String(repeating: "4", count: 32)
        let device = try LedgerStore.Sync.deviceId(store)
        try LedgerStore.Sync.appendGroupOps(store, [
            .create(groupId: g, name: "Trip", settlementCurrency: "USD", members: [
                GroupOpMemberIn(id: tim, name: "Tim"), GroupOpMemberIn(id: m, name: "Al")]),
            Invites.claim(group: g, member: m, you: first, device: device),
            Invites.claim(group: g, member: m, you: me, device: device),
        ], placing: .none)
        let records = try Invites.heldRecords(store: store, group: g, member: m, nowMs: 4_000_000_000_000)
        XCTAssertEqual(try decodedHeld(records), [
            .heldPlace(groupId: g, memberId: m, youId: me), .heldPlace(groupId: g, memberId: m, youId: first),
        ], "the other id's record, then the new place once")
        XCTAssertNil(HeldRule.check(Invites.acceptData(token: "t", records: records)["records"] as? [[String: Any]],
                                    max: Invites.joinMax, place: (g, m)))
    }

    /// D-230 item 3 (review M5): the join's records are built one place at a time: an old pair that cannot be built
    /// is left out and never takes the new place's record with it; the new place's record is required (without it,
    /// none: the join sends the token alone). All at once, one bad pair would throw and drop them all.
    func testAnOldPlaceThatCannotBuildNeverDropsTheNewOne() throws {
        let store = tempStore()
        _ = try store.setYourName("Al")
        let you = try store.ensureSelf().id
        let g = String(repeating: "c", count: 32)
        let bad = PlaceShare.Held(groupId: g, memberId: "not a member", youId: you)
        let good = PlaceShare.Held(groupId: g, memberId: String(repeating: "6", count: 32), youId: you)
        let place = PlaceShare.Held(groupId: g, memberId: String(repeating: "7", count: 32), youId: you)
        XCTAssertThrowsError(try PlaceShare.records(store: store, places: [bad, good, place]), "all at once: none")
        XCTAssertEqual(try decodedHeld(Invites.joinBuilt(store: store, places: [bad, good, place])), [
            .heldPlace(groupId: g, memberId: good.memberId, youId: you),
            .heldPlace(groupId: g, memberId: place.memberId, youId: you),
        ], "the bad old pair is left out, the rest go")
        let badPlace = PlaceShare.Held(groupId: g, memberId: "not a member", youId: you)
        XCTAssertEqual(Invites.joinBuilt(store: store, places: [good, badPlace]), [], "no new place: none")
        XCTAssertEqual(Invites.joinBuilt(store: store, places: []), [])
    }

    /// D-230 item 1 (review I1): peekInvite's answer carries the invite's place into the offer (an older Function's
    /// answer: none); the join sends the token alone with no offer, an offer without a place, or held places that
    /// cannot be built, and never throws over them; `accept` sends what `joinRecords` gives and the join sheet passes
    /// its offer (source pinned: both go to the network). Twin of Android's
    /// d230_the_offer_carries_the_place_and_the_join_never_waits_on_it.
    func testTheOfferCarriesThePlace() throws {
        let group = String(repeating: "c", count: 32), member = String(repeating: "d", count: 32)
        let offer = try Invites.offer(from: ["groupName": "Trip", "memberName": "Al", "inviterName": "Bo",
                                             "groupId": group, "memberId": member])
        XCTAssertEqual(offer, Invites.Offer(groupName: "Trip", memberName: "Al", inviterName: "Bo", groupId: group,
                                            memberId: member))
        let older = try Invites.offer(from: ["groupName": "Trip", "memberName": "Al"])
        XCTAssertEqual(older, Invites.Offer(groupName: "Trip", memberName: "Al", inviterName: nil))
        XCTAssertNil(older.groupId)
        XCTAssertNil(older.memberId)
        XCTAssertThrowsError(try Invites.offer(from: ["memberName": "Al"])) {
            XCTAssertEqual($0 as? CloudFunctions.Failure, .offline)
        }

        let store = tempStore()
        _ = try store.setYourName("Al")
        let you = try store.ensureSelf().id
        let sent = Invites.joinRecords(offer: offer, store: store, nowMs: 4_000_000_000_000)
        XCTAssertEqual(try decodedHeld(sent), [.heldPlace(groupId: group, memberId: member, youId: you)],
                       "the offer's place: sent")
        XCTAssertEqual(Invites.acceptData(token: "t", records: Invites.joinRecords(offer: nil, store: store)).keys.sorted(),
                       ["token"], "no offer: the token alone")
        XCTAssertEqual(Invites.joinRecords(offer: older, store: store), [], "an older Function's offer")
        XCTAssertEqual(Invites.joinRecords(offer: Invites.Offer(groupName: "Trip", memberName: "Al", inviterName: nil,
                                                                groupId: group), store: store), [], "no member: none")
        XCTAssertEqual(Invites.joinRecords(offer: Invites.Offer(groupName: "Trip", memberName: "Al", inviterName: nil,
                                                                memberId: member), store: store), [], "no group: none")
        // A group id that is not hex: the record cannot be built; the join sends the token alone and never throws.
        let bad = Invites.Offer(groupName: "Trip", memberName: "Al", inviterName: nil, groupId: "not a group",
                                memberId: member)
        XCTAssertThrowsError(try PlaceShare.records(store: store, places: [
            PlaceShare.Held(groupId: "not a group", memberId: member, youId: you)]), "the build itself fails")
        XCTAssertEqual(Invites.joinRecords(offer: bad, store: store), [])
        XCTAssertEqual(Invites.acceptData(token: "t", records: Invites.joinRecords(offer: bad, store: store)).keys.sorted(),
                       ["token"])
        // F1 (closing review): a build that THROWS (a vault that will not open: the projection, the vault read and the
        // mint all go through it): `heldRecords` throws, `joinRecords` gives none, and the call data is the token alone.
        let broken = LedgerStore(path: "/dev/null/no-vault-here/vault.db")
        XCTAssertThrowsError(try Invites.heldRecords(store: broken, group: group, member: member), "the build throws")
        XCTAssertEqual(Invites.joinRecords(offer: offer, store: broken), [], "and the join never waits on it")
        XCTAssertEqual(Invites.acceptData(token: "t", records: Invites.joinRecords(offer: offer, store: broken)).keys.sorted(),
                       ["token"])

        // Source pins: `peek` parses through `offer(from:)`, `accept` sends `joinRecords`' records, the sheet passes
        // its offer.
        let url = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("Skipti/App/Invites.swift")
        let source = try String(contentsOf: url, encoding: .utf8)
        let peekParts = source.components(separatedBy: "static func peek(link: String) async throws -> Offer {")
        XCTAssertEqual(peekParts.count, 2)
        let peek = peekParts.last!.components(separatedBy: "\n    }\n")[0]
        XCTAssertTrue(peek.contains("return try offer(from: try await CloudFunctions.call(\"peekInvite\""), peek)
        let acceptParts = source.components(separatedBy: "static func accept(link: String, offer: Offer?, store: LedgerStore)")
        XCTAssertEqual(acceptParts.count, 2)
        let accept = acceptParts.last!.components(separatedBy: "guard let group =")[0]
        XCTAssertTrue(accept.contains("let records = joinRecords(offer: offer, store: store)"), accept)
        XCTAssertTrue(accept.contains("CloudFunctions.call(\"acceptInvite\", acceptData(token: token, records: records))"),
                      accept)
        let joinParts = source.components(separatedBy: "private func join(_ offer: Invites.Offer) async {")
        XCTAssertEqual(joinParts.count, 2)
        let join = joinParts.last!.components(separatedBy: "\n    }\n")[0]
        XCTAssertTrue(join.contains("try await Invites.accept(link: link, offer: offer, store: store)"), join)
    }

    /// The creator claims their own place once; a joiner's claim (written when
    /// joining) counts as theirs, so nothing more is written.
    func testTheCreatorClaimsTheirOwnPlaceOnce() throws {
        let store = tempStore()
        _ = try store.setYourName("Al")
        let group = try store.createGroup(name: "Goa trip", settlementCurrency: "USD", people: ["Bo"])
        let device = try LedgerStore.Sync.deviceId(store)
        let first = CloudRecords.ownClaims(try LedgerStore.Sync.projectGroups(store), device: device)
        XCTAssertEqual(first.count, 1)
        guard case .claim(let g, let m, let y, _)? = first.first else { return XCTFail("not a claim") }
        XCTAssertEqual([g, m, y], [group.group.id, group.selfId, group.selfId])
        try LedgerStore.Sync.appendGroupOps(store, first, placing: .none)
        XCTAssertEqual(CloudRecords.ownClaims(try LedgerStore.Sync.projectGroups(store), device: device), [])
    }

    // MARK: - D-196: push

    private let me32 = String(repeating: "d", count: 32)
    private let group32 = String(repeating: "c", count: 32)

    private func expenseRecord(type: UInt8 = 1, group: String?, device: String, blob: Data = Data("{}".utf8)) -> RecordWire {
        RecordWire(
            version: 1, recordType: type, groupId: group, epoch: 0,
            hlcMillis: 1_790_000_000_123, hlcCounter: 7, hlcDeviceId: device, authorDeviceId: device,
            nonceSession: Data([0x45, 0x58, 0x50, 0x01]), flags: 0, blob: blob
        )
    }

    private func pushFixture(note: String? = "Dinner") -> ([String: ProjectedExpenseOut], String) {
        let id = "1790000000123:7:\(me32)"
        let expense = ProjectedExpenseOut(
            id: id,
            expense: ExpenseInput(payer: "a1", paidMinor: 120_000, currencyCode: "INR", shares: [], group: group32, conversion: nil),
            note: note, category: nil, timestampMs: nil, addedBy: "a1", addedAtMs: 1, deleted: false,
            lastEditedAtMs: nil, lastEditedBy: nil, seen: [], moneyConflict: []
        )
        return ([id: expense], id)
    }

    /// Goldens by hand: what, the note as the title, and core's amount. D-257: the phone names nobody (the Function
    /// adds the author's name as the server holds it) and writes no line of its own.
    func testANewGroupExpenseCarriesItsPushLine() {
        let (expenses, id) = pushFixture()
        let n = PushNotice.notice(expenseRecord(group: group32, device: me32), thisDevice: me32,
                                  expenses: expenses, locale: .enIn)
        XCTAssertEqual(n, PushNotice.Notice(expense: id, kind: .added, title: "Dinner", amount: "₹1,200.00"))
        let document = CloudRecords.document(expenseRecord(group: group32, device: me32), uid: "u1", notice: n)
        XCTAssertEqual(document["notice"] as? [String: String],
                       ["expense": id, "kind": "added", "title": "Dinner", "amount": "₹1,200.00"])
    }

    func testAnEditAndADeleteCarryTheirPushLines() throws {
        let (expenses, id) = pushFixture(note: nil)
        let edit = try ffiEncodeExpenseRevision(revision: ExpenseRevisionIn(
            target: id, seen: [], editor: "a1", money: nil, note: "Lunch", category: nil, deleted: nil))
        let delete = try ffiEncodeExpenseRevision(revision: ExpenseRevisionIn(
            target: id, seen: [], editor: "a1", money: nil, note: nil, category: nil, deleted: true))
        XCTAssertEqual(PushNotice.notice(expenseRecord(type: 5, group: group32, device: me32, blob: edit), thisDevice: me32,
                                         expenses: expenses, locale: .enIn),
                       PushNotice.Notice(expense: id, kind: .changed, title: "an expense", amount: "₹1,200.00"))
        let deleted = PushNotice.notice(expenseRecord(type: 5, group: group32, device: me32, blob: delete), thisDevice: me32,
                                        expenses: expenses, locale: .enIn)
        XCTAssertEqual(deleted, PushNotice.Notice(expense: id, kind: .deleted, title: "an expense", amount: nil))
        XCTAssertEqual(deleted.map(PushNotice.field), ["expense": id, "kind": "deleted", "title": "an expense"],
                       "a delete carries no amount (the rules refuse one)")
    }

    /// Negative: no line for another phone's record, a personal record, a
    /// record that is not an expense, an expense this phone does not hold,
    /// or a group the expense is not in; a long note is shortened.
    func testOnlyThisPhonesGroupExpenseChangesCarryAPushLine() throws {
        let (expenses, id) = pushFixture()
        let other = String(repeating: "e", count: 32)
        func line(_ r: RecordWire, _ e: [String: ProjectedExpenseOut] = [:]) -> PushNotice.Notice? {
            PushNotice.notice(r, thisDevice: me32, expenses: e.isEmpty ? expenses : e, locale: .enIn)
        }
        XCTAssertNil(line(expenseRecord(group: group32, device: other)))
        // Another phone's change to an expense this phone holds (D-196's first
        // sabotage of this guard stayed green without it: the line above names
        // an expense the fixture does not hold).
        let theirDelete = try ffiEncodeExpenseRevision(revision: ExpenseRevisionIn(
            target: id, seen: [], editor: "b1", money: nil, note: nil, category: nil, deleted: true))
        XCTAssertNil(line(expenseRecord(type: 5, group: group32, device: other, blob: theirDelete)))
        XCTAssertNotNil(line(expenseRecord(type: 5, group: group32, device: me32, blob: theirDelete)))
        XCTAssertNil(line(expenseRecord(group: nil, device: me32)))
        XCTAssertNil(line(expenseRecord(type: 3, group: group32, device: me32)))
        XCTAssertNil(line(expenseRecord(type: 2, group: group32, device: me32)))
        XCTAssertNil(line(expenseRecord(type: 5, group: group32, device: me32, blob: Data("junk".utf8))))
        XCTAssertNil(line(expenseRecord(group: String(repeating: "f", count: 32), device: me32)))
        var unknown = expenseRecord(group: group32, device: me32)
        unknown.hlcCounter = 8
        XCTAssertNil(line(unknown))
        XCTAssertNil(CloudRecords.document(expenseRecord(group: nil, device: me32), uid: "u1",
                                           notice: .init(expense: "1:2:\(me32)", kind: .added, title: "t", amount: nil))["notice"],
                     "a personal record never carries one")
        let long = pushFixture(note: String(repeating: "x", count: 300))
        let n = PushNotice.notice(expenseRecord(group: group32, device: me32), thisDevice: me32,
                                  expenses: long.0, locale: .enIn)
        XCTAssertEqual(n?.title, String(repeating: "x", count: 59) + "…")
        let lines = pushFixture(note: "Dinner\nwith Bob")
        XCTAssertEqual(PushNotice.notice(expenseRecord(group: group32, device: me32), thisDevice: me32,
                                         expenses: lines.0, locale: .enIn)?.title, "Dinner with Bob")
    }

    /// D-257: a title is one line, at most 60 UTF-16 units (the rules count them so and take 61), cut at a whole code
    /// point. Goldens by hand (re-derived in Python, .drafts/fable/pass-051/d257/goldens.py); Android's twin is
    /// AccountTest.a_push_title_is_one_line_cut_at_a_whole_character.
    func testAPushTitleIsOneLineCutAtAWholeCharacter() {
        let emoji = "\u{1F600}"
        let x = { (n: Int) in String(repeating: "x", count: n) }
        let e = { (n: Int) in String(repeating: emoji, count: n) }
        XCTAssertEqual(PushNotice.title("Dinner\nwith Bob"), "Dinner with Bob")
        XCTAssertEqual(PushNotice.title("  Dinner\r\n\r\nwith\u{2028}Bob\t "), "Dinner with Bob")
        XCTAssertEqual(PushNotice.title("Din\u{85}ner\u{0}x"), "Din ner x")
        XCTAssertEqual(PushNotice.title(nil), "an expense")
        XCTAssertEqual(PushNotice.title(" \n\t "), "an expense")
        XCTAssertEqual(PushNotice.title(x(60)), x(60), "60 units stay whole")
        XCTAssertEqual(PushNotice.title(x(61)), x(59) + "…")
        XCTAssertEqual(PushNotice.title(e(30)), e(30), "30 emoji are 60 units")
        XCTAssertEqual(PushNotice.title(e(31)), e(29) + "…")
        XCTAssertEqual(PushNotice.title("x" + e(40)), "x" + e(29) + "…")
        XCTAssertEqual(PushNotice.title("xx" + e(40)), "xx" + e(28) + "…", "never half an emoji")
        XCTAssertEqual(PushNotice.title("Caf\u{E9} \u{20B9} " + x(70)), "Caf\u{E9} \u{20B9} " + x(52) + "…")
        for note in [x(300), e(300), "x" + e(300), "xx" + e(300), "a\nb" + x(100)] {
            let title = PushNotice.title(note)
            XCTAssertLessThanOrEqual(title.utf16.count, 61, "the rules' bound")
            XCTAssertNil(title.rangeOfCharacter(from: .controlCharacters), "one line")
        }
        XCTAssertEqual(PushNotice.oneLine("\u{20B9}1,200.00"), "\u{20B9}1,200.00")
    }

    /// A tap opens only an expense named in the shapes the rules allow.
    func testATappedPushOpensOnlyAWellFormedExpense() {
        let expense = "1790000000123:7:\(me32)"
        XCTAssertEqual(PushRoute.target(["kind": "record", "groupId": group32, "expense": expense]),
                       PushRoute.Target(groupId: group32, expense: expense))
        XCTAssertNil(PushRoute.target(["kind": "invite", "groupId": group32, "expense": expense]))
        XCTAssertNil(PushRoute.target(["kind": "record", "groupId": "g1", "expense": expense]))
        XCTAssertNil(PushRoute.target(["kind": "record", "groupId": group32, "expense": "1:2:\(me32.uppercased())"]))
        XCTAssertNil(PushRoute.target(["kind": "record", "groupId": group32, "expense": "1:2:\(me32):9"]))
        XCTAssertNil(PushRoute.target(["kind": "record", "groupId": group32]))
        XCTAssertNil(PushRoute.target([:]))
    }

    /// A group's Recent activity lists that group only.
    func testRecentActivityListsOneGroup() {
        func e(_ id: String, _ group: String?) -> ProjectedExpenseOut {
            ProjectedExpenseOut(
                id: id, expense: ExpenseInput(payer: "r", paidMinor: 1, currencyCode: "INR", shares: [], group: group, conversion: nil),
                note: id, category: nil, timestampMs: nil, addedBy: "r", addedAtMs: 1, deleted: false,
                lastEditedAtMs: nil, lastEditedBy: nil, seen: [], moneyConflict: [])
        }
        let all = [e("x1", "g1"), e("x2", "g2"), e("x3", nil)]
        XCTAssertEqual(ActivityFeed.items(all, me: ["m"], only: "g2").map(\.expenseID), ["x2"])
        XCTAssertEqual(Set(ActivityFeed.items(all, me: ["m"]).map(\.expenseID)), ["x1", "x2"])
        XCTAssertEqual(ActivityFeed.groupTitle, "Recent activity")
        XCTAssertEqual(ActivityFeed.groupEmpty, "Nothing new yet. What others add, change or delete in this group shows here.")
    }

    /// FCM never starts on its own (GUARANTEES #5), and FCM swizzles nothing.
    func testFcmNeverStartsOnItsOwn() {
        XCTAssertEqual(Bundle.main.object(forInfoDictionaryKey: "FirebaseMessagingAutoInitEnabled") as? Bool, false)
        XCTAssertEqual(Bundle.main.object(forInfoDictionaryKey: "FirebaseAppDelegateProxyEnabled") as? Bool, false)
    }

    func testThePushWordsMatchAndroid() {
        XCTAssertEqual(PushCopy.title, "Notifications")
        XCTAssertEqual(PushCopy.explainOn, "You hear when someone adds, changes or deletes an expense in your groups.")
        XCTAssertEqual(PushCopy.explainAsk, "Hear when someone adds, changes or deletes an expense in your groups.")
        XCTAssertEqual(PushCopy.explainDenied, "Notifications for SKIPTI are off in your phone's settings.")
        XCTAssertEqual(PushCopy.explainSyncOff, "Notifications come with sync. Turn sync on to hear about your groups.")
        XCTAssertEqual(PushCopy.turnOn, "Turn on notifications")
        XCTAssertEqual(PushCopy.openSettings, "Open phone settings")
        XCTAssertEqual(PushCopy.fetching, "Bringing in this expense…")
    }

    // MARK: - D-197: finding people

    /// sha256("priya@example.com"), derived with Python's hashlib, as in
    /// firebase/tests/people.test.mjs: the phone and the cloud agree.
    private let priya = "6bdb7e961d54ddc243ec721e6e211ac0a179b19a9ce2ad52a418198e146e69fe"

    func testAnEmailFingerprintIsTheCloudsOwn() {
        XCTAssertEqual(EmailFingerprint.hash(" Priya@Example.COM "), priya)
        XCTAssertNotEqual(EmailFingerprint.hash("priya+x@example.com"), priya)
        XCTAssertTrue(EmailFingerprint.looksLikeEmail("priya@example.com"))
        XCTAssertFalse(EmailFingerprint.looksLikeEmail("priya@example"))
        XCTAssertFalse(EmailFingerprint.looksLikeEmail("pri ya@example.com"))
    }

    /// A search box holds a username or a whole email, nothing else.
    func testASearchIsAUsernameOrAWholeEmail() {
        XCTAssertEqual(PeopleQuery.parse(" @Priya_92 "), .username("priya_92"))
        XCTAssertEqual(PeopleQuery.parse("Priya@Example.com"), .email("Priya@Example.com"))
        XCTAssertNil(PeopleQuery.parse("pr"))
        XCTAssertNil(PeopleQuery.parse("priya@"))
        XCTAssertNil(PeopleQuery.parse("pri ya"))
        XCTAssertNil(PeopleQuery.parse(String(repeating: "a", count: 21)))
        XCTAssertNil(PeopleQuery.parse(""))
    }

    /// The cloud's result, read: malformed people dropped, a bad fingerprint
    /// ignored; a result reads as name, else @handle, else the contact's name.
    func testAResultReadsAsTheirNameHandleOrContact() {
        let found = People.decode(["people": [
            ["uid": "u1", "name": "Priya", "username": "priya", "matched": priya],
            ["uid": "u2", "name": NSNull(), "username": NSNull(), "matched": "not-a-hash"],
            ["name": "No uid"],
        ]])
        XCTAssertEqual(found, [
            FoundPerson(uid: "u1", name: "Priya", username: "priya", matched: priya),
            FoundPerson(uid: "u2", name: nil, username: nil, matched: nil),
        ])
        let contacts = [priya: "Priya Rao"]
        XCTAssertEqual(found[0].title(contacts: contacts), "Priya")
        XCTAssertEqual(found[0].detail(contacts: contacts), "@priya · In your contacts as Priya Rao")
        XCTAssertEqual(FoundPerson(uid: "u3", name: nil, username: "pr", matched: nil).title(), "@pr")
        XCTAssertEqual(FoundPerson(uid: "u4", name: nil, username: nil, matched: priya).title(contacts: contacts), "Priya Rao")
        XCTAssertEqual(found[1].title(), "Someone on SKIPTI")
        XCTAssertNil(found[1].detail())
        XCTAssertEqual(People.decode([:]), [])
    }

    /// Contacts: one fingerprint per email, the first name kept; no email, no entry.
    func testContactsBecomeFingerprintsWithTheirNames() {
        let byHash = ContactBook.byHash([
            .init(name: "Priya Rao", email: "Priya@Example.com"),
            .init(name: "Priya (work)", email: "priya@example.com "),
            .init(name: "", email: "bo@example.com"),
            .init(name: "Broken", email: "not an email"),
        ])
        XCTAssertEqual(byHash.count, 2)
        XCTAssertEqual(byHash[priya], "Priya Rao")
        XCTAssertEqual(byHash[EmailFingerprint.hash("bo@example.com")], "bo@example.com")
    }

    /// A username the phone refuses at once (the rule and its goldens are in
    /// UsernameRulesTests; here the copy each problem gets).
    func testUsernamesAreRefusedHereAsTheCloudWould() throws {
        XCTAssertNil(People.usernameProblem("@Priya_92"))
        XCTAssertEqual(People.usernameProblem("pr"), PeopleCopy.usernameShape)
        XCTAssertEqual(People.usernameProblem("pri ya"), PeopleCopy.usernameShape)
        XCTAssertEqual(People.usernameProblem("Support"), PeopleCopy.usernameReserved)
    }

    /// An invitation is read only in the shape inviteUser writes; an invite
    /// push is recognised by its kind.
    func testAnInvitationIsReadOnlyInItsOwnShape() {
        let token = String(repeating: "A", count: 43)
        let good: [String: Any] = ["groupName": "Goa trip", "fromName": "Al", "memberName": "Priya", "token": token]
        let item = IncomingInvites.item(id: "i1", data: good)
        XCTAssertEqual(item?.link, "https://skipti.vardrlabs.com/join#" + token)
        XCTAssertEqual(item?.fromName, "Al")
        var bad = good
        bad["token"] = "short"
        XCTAssertNil(IncomingInvites.item(id: "i2", data: bad))
        bad = good
        bad["groupName"] = ""
        XCTAssertNil(IncomingInvites.item(id: "i3", data: bad))
        XCTAssertNotNil(IncomingInvites.item(id: "i4", data: good.filter { $0.key != "fromName" }))
        XCTAssertTrue(PushRoute.isInvite(["kind": "invite"]))
        XCTAssertFalse(PushRoute.isInvite(["kind": "record"]))
        XCTAssertNil(PushRoute.target(["kind": "invite"]))
    }

    /// D-208: an invitation to a group already on this phone is not offered
    /// (its Join could only say "already used"); the others are, and so is an
    /// invitation in the older shape that names no group.
    func testAnInvitationToAGroupYouAreInIsNotOffered() {
        let token = String(repeating: "A", count: 43)
        let goa = String(repeating: "c", count: 32), flat = String(repeating: "f", count: 32)
        let shape: [String: Any] = ["groupName": "Goa trip", "memberName": "Priya", "token": token]
        var a = shape; a["groupId"] = goa
        var b = shape; b["groupId"] = flat
        let items = [IncomingInvites.item(id: "a", data: a), IncomingInvites.item(id: "b", data: b),
                     IncomingInvites.item(id: "old", data: shape)].compactMap { $0 }
        XCTAssertEqual(items.first?.groupId, goa)
        XCTAssertEqual(IncomingInvites.waiting(items, joined: [goa]).map(\.id), ["b", "old"])
        XCTAssertEqual(IncomingInvites.waiting(items, joined: []).map(\.id), ["a", "b", "old"])
    }

    func testThePeopleWordsMatchAndroid() {
        XCTAssertEqual(PeopleCopy.findableToggle, "Let people find me by my username or email")
        XCTAssertEqual(PeopleCopy.findableOn, "People who know your username or email can find you and invite you to their groups.")
        XCTAssertEqual(PeopleCopy.findableOff, "Nobody can find you. You can still join a group with an invite link.")
        XCTAssertEqual(PeopleCopy.usernameHelp, "3 to 20 letters, numbers or _. People can find you by it.")
        XCTAssertEqual(PeopleCopy.contactsDetail,
            "Each email in your contacts is turned into a fingerprint on this phone. SKIPTI's cloud compares the fingerprints and keeps none of them.")
        XCTAssertEqual(PeopleCopy.noMatch, "Nobody on SKIPTI matches. They may have switched off being found; an invite link always works.")
        XCTAssertEqual(PeopleCopy.invited("Priya"), "Invited. Priya gets a notification and the invite in SKIPTI.")
        XCTAssertEqual(PeopleCopy.invitedYou("Al", "Goa trip"), "Al invited you to Goa trip")
        XCTAssertEqual(PeopleCopy.invitedYou(nil, "Goa trip"), "You are invited to Goa trip")
        XCTAssertEqual(PeopleCopy.findOnSkipti("Priya"), "Find Priya on SKIPTI")
        XCTAssertEqual(PeopleCopy.notAQuery, "Type a username (3 to 20 letters, numbers or _) or a whole email address.")
    }

    // MARK: - D-198: your phones and deleting your account

    /// D-225 (Design 4): the Your phones row's summary on Security: the active phones' names, this phone first, joined
    /// with ", "; signed-out phones left out; none left is nil, never an empty summary (Android's AccountTest pins it).
    func testThePhonesSummaryNamesTheActivePhonesThisOneFirst() {
        func row(_ id: String, _ name: String, signedOut: Bool = false, isThis: Bool = false) -> Phones.Row {
            Phones.Row(id: id, name: name, platform: "ios", lastSeenMs: nil, signedOut: signedOut, isThis: isThis)
        }
        let this = row("a", "iPhone 17", isThis: true)
        let other = row("b", "Moto edge 70")
        let old = row("c", "Old phone", signedOut: true)
        XCTAssertEqual(PhonesSummary.line(rows: [this, other, old]), "iPhone 17, Moto edge 70")
        XCTAssertEqual(PhonesSummary.line(rows: [other, old, this]), "iPhone 17, Moto edge 70", "this phone first")
        XCTAssertEqual(PhonesSummary.line(rows: [old]), nil, "only signed-out phones: no summary")
        XCTAssertNil(PhonesSummary.line(rows: []))
    }

    /// D-225 follow-up (R2, the re-look): Your phones is never silently empty. A listen that fails (no snapshot) is
    /// `.failed`, which the page draws as the cloud surfaces' error line in `owed` with "Try again" (a new listener);
    /// it used to drop the event and show nothing. A listen that works lists its rows, or "No phones yet." when there are
    /// none. Android's `Phones.listing` and words, word for word.
    func testAFailedPhonesListenSaysSoWithTryAgain() throws {
        let this = String(repeating: "a", count: 32)
        XCTAssertEqual(Phones.listing(nil, thisDevice: this), .failed, "a failed listen is said")
        XCTAssertEqual(Phones.listing([], thisDevice: this), .listed([]), "an empty listen is No phones yet.")
        let docs: [(id: String, data: [String: Any])] = [(id: this, data: ["name": "iPhone", "platform": "ios"])]
        XCTAssertEqual(Phones.listing(docs, thisDevice: this), .listed(Phones.rows(docs, thisDevice: this)))
        XCTAssertEqual(PhonesCopy.loadFailed, "Could not load your phones. Check your connection and try again.")
        XCTAssertEqual(PhonesCopy.tryAgain, "Try again")

        let app = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
        let source = try String(contentsOf: app.appendingPathComponent("Skipti/App/Phones.swift"), encoding: .utf8)
        let screen = try XCTUnwrap(source.components(separatedBy: "struct PhonesScreen: View {").last?
            .components(separatedBy: "\nstruct DeleteAccountScreen").first)
        XCTAssertTrue(screen.contains("""
                        case .failed:
                            // D-225 follow-up (R2): a listen that failed says so, never an empty page; Try again listens again.
                            Text(verbatim: PhonesCopy.loadFailed).font(TypeRole.body()).foregroundStyle(Tokens.owed)
                            Button { listenAgain() } label: { Text(verbatim: PhonesCopy.tryAgain) }
                                .buttonStyle(SecondaryButtonStyle())
                        case .listed(let rows) where rows.isEmpty:
                            Text(verbatim: PhonesCopy.empty)
        """), "the failed state's line and button, then the empty card")
        XCTAssertTrue(screen.contains(
            "let next = Phones.listing(snapshot?.documents.map { ($0.documentID, $0.data()) }, thisDevice: this)"), "every event, errors too")
        XCTAssertFalse(screen.contains("guard let docs = snapshot?.documents else { return }"), "no event dropped")
        XCTAssertTrue(screen.contains("""
            private func listenAgain() {
                    listener?.remove()
                    listener = nil
                    listing = .waiting
                    start()
                }
            """), "Try again starts a new listener")

        // Android's words (Phones.kt), read from its source.
        let kotlin = try String(contentsOf: app.appendingPathComponent("../android/app/src/main/java/com/vardrlabs/skipti/Phones.kt"), encoding: .utf8)
        XCTAssertTrue(kotlin.contains("const val LOAD_FAILED = \"\(PhonesCopy.loadFailed)\""), "Android's line")
        XCTAssertTrue(kotlin.contains("const val TRY_AGAIN = \"\(PhonesCopy.tryAgain)\""), "Android's button")
    }

    func testPhonesListThisPhoneFirstThenTheMostRecentlySeen() {
        let this = String(repeating: "a", count: 32)
        let rows = Phones.rows([
            (id: "p1", data: ["name": "Pixel 8", "platform": "android", "lastSeen": Timestamp(seconds: 100, nanoseconds: 0)]),
            (id: this, data: ["name": "iPhone", "platform": "ios", "lastSeen": Timestamp(seconds: 50, nanoseconds: 0)]),
            (id: "p2", data: ["name": "", "platform": "ios", "lastSeen": Timestamp(seconds: 200, nanoseconds: 0), "signedOut": true]),
        ], thisDevice: this)
        XCTAssertEqual(rows.map(\.id), [this, "p2", "p1"])
        XCTAssertEqual(rows.map(\.name), ["iPhone", "iPhone", "Pixel 8"], "a phone with no name reads as its kind")
        XCTAssertEqual(rows.map(\.isThis), [true, false, false])
        XCTAssertEqual(rows[1].signedOut, true)
        XCTAssertEqual(Phones.status(rows[0]) { _ in "x" }, "This phone")
        XCTAssertEqual(Phones.status(rows[1]) { _ in "x" }, "Signed out")
        XCTAssertEqual(Phones.status(rows[2]) { ms in "\(ms)" }, "Last seen 100000")
        XCTAssertEqual(Phones.platformWord("web"), "")
    }

    /// "Signed out from another phone" only when another phone asked: this
    /// phone's own Sign out everywhere or deletion request signs it out quietly.
    @MainActor func testTheRemoteNoticeIsOnlyForAnotherPhonesRequest() async {
        struct Refused: Error {}
        let remote = RemoteSignOut()
        var signOuts = 0
        remote.arrived(signedIn: false) { signOuts += 1 }
        XCTAssertEqual(signOuts, 0, "no account, nothing to sign out")
        XCTAssertFalse(remote.notice)
        XCTAssertEqual(remote.signOuts, 0, "open screens are told only of a real sign-out")
        remote.arrived(signedIn: true) { signOuts += 1 }
        XCTAssertEqual(signOuts, 1)
        XCTAssertTrue(remote.notice, "another phone asked")
        XCTAssertEqual(remote.signOuts, 1)
        remote.notice = false
        await remote.quietly { remote.arrived(signedIn: true) { signOuts += 1 } }
        XCTAssertEqual(signOuts, 2, "still signed out")
        XCTAssertFalse(remote.notice, "this phone asked")
        XCTAssertEqual(remote.signOuts, 2, "its own sign-out still closes the account's screens")
        XCTAssertFalse(remote.ownRequest)
        _ = try? await remote.quietly { throw Refused() }
        XCTAssertFalse(remote.ownRequest, "a refused request ends too")
    }

    /// A flag set after this phone signed in signs it out; one from before it
    /// signed in again does not (its entry is made fresh instead).
    func testOnlyASignOutSinceThisSignInCounts() {
        let signedInAt: Int64 = 1_790_000_000_000
        func flag(_ ms: Int64?) -> [String: Any] {
            var d: [String: Any] = ["signedOut": true]
            if let ms { d["signedOutAt"] = CloudRecords.timestamp(ms) }
            return d
        }
        XCTAssertTrue(Phones.signedOutSince(flag(signedInAt + 1), authTimeMs: signedInAt))
        XCTAssertTrue(Phones.signedOutSince(flag(signedInAt), authTimeMs: signedInAt))
        XCTAssertFalse(Phones.signedOutSince(flag(signedInAt - 1), authTimeMs: signedInAt))
        XCTAssertTrue(Phones.signedOutSince(flag(nil), authTimeMs: signedInAt), "no time: take it as now")
        XCTAssertFalse(Phones.signedOutSince(["signedOut": false], authTimeMs: signedInAt))
        XCTAssertFalse(Phones.signedOutSince(nil, authTimeMs: signedInAt))
        XCTAssertTrue(PushRoute.isDevice(["kind": "device"]))
        XCTAssertFalse(PushRoute.isDevice(["kind": "invite"]))
    }

    func testThePhonesWordsMatchAndroid() {
        XCTAssertEqual(PhonesCopy.title, "Your phones")
        XCTAssertEqual(PhonesCopy.confirmBody, "It stops syncing and signs itself out the next time it goes online. Its ledger stays on it.")
        XCTAssertEqual(PhonesCopy.everywhereBody,
            "Every phone on your account, this one too, is signed out. Each keeps its ledger. Use this if a phone is lost or stolen.")
        XCTAssertEqual(PhonesCopy.remoteBody, "This phone was signed out of your account from another phone. Your ledger stays here.")
        XCTAssertEqual(PhonesCopy.deleteIntro,
            "Deleting your account removes it from SKIPTI's cloud: your profile, username, phones and personal records. You leave every group; what you added to a group stays for its other members. Groups where you are the only member are deleted.")
        XCTAssertEqual(PhonesCopy.deleteGrace,
            "Deletion happens 7 days after you ask. To keep your account, sign in before then and tap Keep my account. After that it cannot be undone.")
        XCTAssertEqual(PhonesCopy.deleteDone("9 October 2026"),
            "Your account will be deleted on 9 October 2026. To keep it, sign in before then and tap Keep my account.")
        XCTAssertEqual(PhonesCopy.keep, "Keep my account")
    }

    /// D-211: the hosted XCTest run launches the real app on a simulator that
    /// may be signed in to a real account, so a test host starts no cloud work.
    func testATestHostStartsNoCloudWork() {
        let host = ["XCTestConfigurationFilePath": "/tmp/x.xctestconfiguration"]
        XCTAssertTrue(LaunchWork.isTestHost(environment: host))
        XCTAssertFalse(LaunchWork.startsCloud(environment: host))
        // The real app: no such variable.
        XCTAssertFalse(LaunchWork.isTestHost(environment: [:]))
        XCTAssertTrue(LaunchWork.startsCloud(environment: [:]))
        XCTAssertTrue(LaunchWork.startsCloud(environment: ["HOME": "/x", "TMPDIR": "/y"]))
        // This very process is a test host, by the real environment.
        XCTAssertFalse(LaunchWork.startsCloud())
    }

    // ---- D-225 Task 4: the Erase page and Your data (source guards) ----

    /// D-225 (Design 4): the Erase page says what goes (`EraseCopy.detail`), then today's button, whose alert and warning
    /// haptic are unchanged (D-127: never automatic); the bar names the page and the card draws no title of its own.
    func testTheEraseScreenKeepsItsConfirmation() throws {
        let owner = try s2Source("AccountOwner.swift")
        let screen = try s2Slice(owner, from: "struct EraseScreen: View {", to: "\n}\n")
        for needle in ["Text(verbatim: EraseCopy.detail)", "EraseLedgerButton()", ".glassCard()", ".padding(20)",
                       ".auroraBackground()", ".navigationTitle(Text(verbatim: EraseCopy.title))",
                       ".navigationBarTitleDisplayMode(.inline)"] {
            XCTAssertTrue(screen.contains(needle), "EraseScreen: \(needle)")
        }
        s2InOrder(screen, ["Text(verbatim: EraseCopy.detail)", "EraseLedgerButton()"])
        for banned in ["LedgerErase", "TypeRole.display("] {
            XCTAssertFalse(screen.contains(banned), "EraseScreen: \(banned)")
        }
        XCTAssertEqual(s2Count(screen, "Text(verbatim: EraseCopy.title)"), 1, "the title is the bar's alone")
        let button = try s2Slice(owner, from: "struct EraseLedgerButton: View {", to: "\n}\n")
        for needle in ["Button(role: .destructive) { confirming = true }", ".alert(EraseCopy.confirmTitle, isPresented: $confirming) {",
                       "Button(EraseCopy.confirm, role: .destructive) { erase() }", "Button(\"Cancel\", role: .cancel) {}",
                       "Text(verbatim: EraseCopy.confirmBody)", ".hapticWarning(when: confirming)"] {
            XCTAssertTrue(button.contains(needle), "EraseLedgerButton: \(needle)")
        }
        XCTAssertEqual(EraseCopy.title, "Erase this phone")
        XCTAssertEqual(EraseCopy.button, "Erase this phone's ledger")
        XCTAssertEqual(EraseCopy.confirmTitle, "Erase this phone's ledger?")
    }

    /// D-225 fix wave (the look's unexplained blank "Your phones"): a section page knows whether the phone is signed in
    /// on its first frame, so the account rows (Your phones, Delete account) are there from the start and never slide in
    /// under a finger once the async read lands; `reload()` still reads it again.
    func testASectionPageKnowsTheAccountOnItsFirstFrame() throws {
        let hub = try s2Source("AccountHubScreen.swift")
        let page = try s2Slice(hub, from: "struct SettingsSectionScreen: View {", to: "    var body: some View {")
        XCTAssertTrue(page.contains("@State private var model = accountHubModel(signedIn: Account.shared.isSignedIn, syncOn: true, approval: Approval.shared.state)"), page)
        XCTAssertFalse(page.contains("state: .noAccount, syncOn: true, showsAccountControls: false"), "not signed out until read")
    }

    /// D-225 (Design 5; L74): Your data is one list card: Default currency (its code, opening the currency list page:
    /// the quick menu and the "All currencies" button went), Export (GUARANTEES #4: one tap from here) and Import, each
    /// explained on two lines, with the currency caption under the card.
    func testYourDataIsOneListCard() throws {
        XCTAssertEqual(SettingsMap.items(.data, signedIn: false), [.currency, .export, .importData])
        let hub = try s2Source("AccountHubScreen.swift")
        let page = try s2Slice(hub, from: "private var yourDataPage: some View {", to: "\n    }\n")
        XCTAssertEqual(s2Count(page, "ListCard {"), 1)
        // D-225 fix wave (review M5): the items follow the real account state, so a signed-in data item would show.
        // D-254: every signed-in state (waiting and not approved too) is the account's (`showsAccountControls`).
        s2InOrder(page, ["ListCard {", "ForEach(SettingsMap.items(.data, signedIn: model.showsAccountControls), id: \\.self) { item in dataRow(item) }",
                         "CardCaption(CurrencyCopy.detail)"])
        XCTAssertFalse(hub.contains("signedIn: false)"), "no page hard-codes signed out")
        let rows = try s2Slice(hub, from: "private func dataRow(_ item: SettingItem) -> some View {", to: "\n    }\n")
        let currency = try s2Slice(rows, from: "case .currency:", to: "case .export:")
        // D-244 (ADR-0043), amended on purpose: the choice goes through the one write of a synced setting.
        for needle in ["CurrencyListScreen(selection: Binding(get: { DefaultCurrency.current(stored: currencyRaw) }, set: { SettingsSync.set(DefaultCurrency.storageKey, $0) }),",
                       "suggested: DefaultCurrency.suggestion(stored: currencyRaw))",
                       "ListRow(title: CurrencyCopy.label, trailing: .value(DefaultCurrency.current(stored: currencyRaw), chevron: true))"] {
            XCTAssertTrue(currency.contains(needle), "Default currency: \(needle)")
        }
        let export = try s2Slice(rows, from: "case .export:", to: "case .importData:")
        XCTAssertTrue(export.contains("NavigationLink { ExportScreen(store: LedgerStore(path: vaultPath)) }"), export)
        XCTAssertTrue(export.contains("ListRow(title: YourDataCopy.export, parts: [YourDataCopy.exportDetail], summaryLines: 2, trailing: .chevron)"), export)
        let importing = try s2Slice(rows, from: "case .importData:", to: "default:")
        XCTAssertTrue(importing.contains("NavigationLink { ImportFlowScreen() }"), importing)
        XCTAssertTrue(importing.contains("ListRow(title: ImportCopy.entry, parts: [ImportCopy.entryDetail], summaryLines: 3, trailing: .chevron)"), importing) // D-234 decision 100
        for banned in ["Picker(", "CurrencyCopy.allCurrencies", "card {", ".glassCard(", "SecondaryButtonStyle"] {
            XCTAssertFalse(page.contains(banned) || rows.contains(banned), "Your data: \(banned)")
        }
        XCTAssertFalse(try s2Source("CurrencyChoice.swift").contains("struct CurrencySettings"), "the old card is gone")
        XCTAssertEqual(YourDataCopy.export, "Export your data")
        XCTAssertEqual(YourDataCopy.exportDetail, "Your whole ledger in one file: CSV to read, or a JSON backup to restore.")
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

// MARK: - D-235 wave C: the iOS shell's audit fixes (C1 to C4)

final class D235ShellTests: XCTestCase {
    private let group = String(repeating: "c", count: 32)
    private let me = String(repeating: "a", count: 32)
    private let them = String(repeating: "b", count: 32)
    private let device = String(repeating: "d", count: 32)

    private func tempDir(_ name: String) -> URL {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("\(name)-\(UUID().uuidString)", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    private func tempStore() -> LedgerStore {
        LedgerStore(path: tempDir("d235").appendingPathComponent("vault.db").path)
    }

    private func record(_ type: UInt8, _ blob: Data, scope: String? = nil, version: UInt16 = 1) -> RecordWire {
        RecordWire(version: version, recordType: type, groupId: scope, epoch: 0, hlcMillis: 1_790_000_000_000, hlcCounter: 0,
                   hlcDeviceId: device, authorDeviceId: device, nonceSession: Data([0, 0, 0, 1]), flags: 0, blob: blob)
    }

    private func expense(paid: Int64, shares: [(String, Int64)], group g: String?, currency: String = "USD") throws -> Data {
        try JSONEncoder().encode(LedgerStore.ExpenseDTO(
            payer: me, paidMinor: paid, currencyCode: currency, group: g, conversion: nil,
            shares: shares.map { .init(id: $0.0, amountMinor: $0.1) }, note: nil, timestampMs: nil, category: nil, addedBy: nil))
    }

    private func settlement(from: String, to: String, amount: Int64, group g: String?) throws -> Data {
        try JSONEncoder().encode(LedgerStore.SettlementDTO(from: from, to: to, amountMinor: amount, currencyCode: "USD", group: g))
    }

    // ---- C1 (decision 106): the lock and the cover in their own window ----

    /// The curtain's rules: the lock while locked (whatever the phase), the cover away from the front or while captured
    /// with the setting on, nothing in front and unlocked, and nothing at all before the ledger exists or in a proof.
    func testTheCurtainShowsTheLockWhileLockedAndTheCoverWhileAway() {
        XCTAssertEqual(LockCurtainRules.content(enabled: true, locked: true, active: true, captureCovers: false), .lock)
        XCTAssertEqual(LockCurtainRules.content(enabled: true, locked: true, active: false, captureCovers: true), .lock)
        XCTAssertEqual(LockCurtainRules.content(enabled: true, locked: false, active: false, captureCovers: false), .cover)
        XCTAssertEqual(LockCurtainRules.content(enabled: true, locked: false, active: true, captureCovers: true), .cover)
        XCTAssertNil(LockCurtainRules.content(enabled: true, locked: false, active: true, captureCovers: false))
        XCTAssertNil(LockCurtainRules.content(enabled: false, locked: true, active: false, captureCovers: true),
                     "before the ledger exists, or in a proof launch")
    }

    /// The curtain is its own window above every presentation (sheets and alerts live in the main window at .normal):
    /// up, it is the only window VoiceOver reaches; down, it is hidden and the ledger is reachable again.
    @MainActor
    func testTheCurtainWindowIsAboveEverySheetAndAloneForVoiceOver() {
        XCTAssertGreaterThan(LockCurtainRules.level, UIWindow.Level.alert)
        XCTAssertGreaterThan(LockCurtainRules.level, UIWindow.Level.normal)
        let main = UIWindow(frame: CGRect(x: 0, y: 0, width: 10, height: 10))
        let curtain = UIWindow(frame: main.frame)
        curtain.rootViewController = UIViewController()
        curtain.isHidden = true
        for content in [CurtainContent.lock, .cover] {
            LockCurtainRules.apply(content, curtain: curtain, others: [main])
            XCTAssertFalse(curtain.isHidden, "\(content): up")
            XCTAssertEqual(curtain.windowLevel, LockCurtainRules.level)
            XCTAssertTrue(curtain.accessibilityViewIsModal, "\(content): VoiceOver stays in the curtain")
            XCTAssertTrue(curtain.rootViewController?.view.accessibilityViewIsModal == true)
            XCTAssertTrue(main.accessibilityElementsHidden, "\(content): the ledger is out of VoiceOver's reach")
            LockCurtainRules.apply(nil, curtain: curtain, others: [main])
            XCTAssertTrue(curtain.isHidden, "down")
            XCTAssertFalse(main.accessibilityElementsHidden, "the ledger reachable again")
            XCTAssertFalse(curtain.accessibilityViewIsModal)
        }
    }

    /// The shell draws the lock and the cover only through the curtain (no overlay a sheet can sit above); the
    /// curtain follows the scene handler too; an invite link and a tapped push are held across the lock (the lock
    /// taking their sheet down never clears them: L2).
    func testTheShellDrawsTheLockOnlyInTheCurtainAndHoldsLinksAcrossIt() throws {
        let app = try s2Source("SkiptiApp.swift")
        let body = try s2Slice(app, from: "var body: some Scene {", to: "    /// D-235 (C1): what the curtain is drawn from.")
        for banned in ["AppLockScreen(", "PrivacyCover(", ".overlay {", ".sheet(item: $openedInvite)"] {
            XCTAssertFalse(body.contains(banned), "the scene body: \(banned)")
        }
        for needle in [".onChange(of: curtainInputs, initial: true) { _, _ in updateCurtain() }",
                       "if phase == .active { lock.cameToFront() }\n                    }\n                    // D-235: the curtain from the state this handler just set (cameToFront may have locked).\n                    updateCurtain()",
                       "get: { held || verificationArg ? nil : openedInvite },\n                    set: { if $0 == nil && !held { openedInvite = nil } }",
                       "set: { if !held { push.opened = $0 } }",
                       "set: { if $0 == nil && !held { push.openedGroup = nil } }",
                       "set: { if !held { push.openedPhones = $0 } }",
                       "set: { if !held { push.openedInvitations = $0 } }"] {
            XCTAssertTrue(body.contains(needle), "the scene body: \(needle)")
        }
        let lock = try s2Source("AppLock.swift")
        XCTAssertTrue(lock.contains("case .lock?: AppLockScreen(lock: lock).id(curtain.raised)"))
        // Round 4 (M2): held until the lock's first decision, then until the unlock; no sheet gates on the lock alone.
        XCTAssertTrue(app.contains("private var held: Bool { onboardingComplete != true || lock.locked }"))
        XCTAssertEqual(s2Count(body, "lock.locked"), 1, "only `held` itself reads the lock: every held sheet reads `held`")
        XCTAssertTrue(lock.contains("forName: UIScene.willDeactivateNotification"), "the cover goes up before the snapshot")
    }

    // ---- C2 (decision 107): a reinstall never restores the account silently ----

    /// The first launch of an install signs out a session only when no vault exists yet (an earlier install left it
    /// in the keychain); an update from a build before the marker (a vault already there) keeps it; a later launch,
    /// or no session, does nothing.
    func testTheFirstLaunchOfANewInstallSignsOutALeftBehindSession() {
        // Round 5 (decision 116): the first launch of a new install marks the sign-out; it runs at the first configure.
        XCTAssertTrue(InstallSession.pendsSignOut(markerPresent: false, vaultExists: false))
        XCTAssertFalse(InstallSession.pendsSignOut(markerPresent: false, vaultExists: true), "an update")
        XCTAssertFalse(InstallSession.pendsSignOut(markerPresent: true, vaultExists: false), "launched before")

        let suite = "d235-install-\(UUID().uuidString)"
        let defaults = try! XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let vault = tempDir("install").appendingPathComponent("ledger-vault.db").path
        InstallSession.settle(defaults, vaultPath: vault)
        XCTAssertTrue(InstallSession.pendingSignOut(defaults), "a new install: a sign-out waits for the first configure")
        XCTAssertTrue(defaults.bool(forKey: InstallSession.markerKey), "the marker is set")
        InstallSession.clearPendingSignOut(defaults)
        InstallSession.settle(defaults, vaultPath: vault)
        XCTAssertFalse(InstallSession.pendingSignOut(defaults), "once per install")

        defaults.removeObject(forKey: InstallSession.markerKey)
        FileManager.default.createFile(atPath: vault, contents: Data([1]))
        InstallSession.settle(defaults, vaultPath: vault)
        XCTAssertFalse(InstallSession.pendingSignOut(defaults), "an update with its vault keeps its session")
        XCTAssertTrue(defaults.bool(forKey: InstallSession.markerKey))
    }

    /// Right after configure: a session is signed out when a new install marked it, or when the vault does not own it
    /// (no owner: a fresh vault or a restore; another owner: the one-account rule); a vault read error signs nobody out.
    func testASessionTheVaultDoesNotOwnIsSignedOutOnceItOpens() {
        XCTAssertFalse(InstallSession.signsOutAfterConfigure(pending: false, currentUid: "u1", owner: .read("u1")))
        XCTAssertTrue(InstallSession.signsOutAfterConfigure(pending: false, currentUid: "u1", owner: .read(nil)))
        XCTAssertTrue(InstallSession.signsOutAfterConfigure(pending: false, currentUid: "u1", owner: .read("u2")))
        XCTAssertTrue(InstallSession.signsOutAfterConfigure(pending: true, currentUid: "u1", owner: .read("u1")),
                      "a new install's first configure signs out whatever the keychain kept")
        XCTAssertTrue(InstallSession.signsOutAfterConfigure(pending: true, currentUid: "u1", owner: .failed))
        XCTAssertFalse(InstallSession.signsOutAfterConfigure(pending: true, currentUid: nil, owner: .read(nil)), "already out")
        XCTAssertFalse(InstallSession.signsOutAfterConfigure(pending: false, currentUid: nil, owner: .read(nil)))
        // Round 4 (M4): a vault that could not be read signs nobody out (the cloud gate fails closed instead).
        XCTAssertFalse(InstallSession.signsOutAfterConfigure(pending: false, currentUid: "u1", owner: .failed))
        XCTAssertEqual(InstallSession.owned(uid: "u1", owner: "u1"), "u1")
        XCTAssertNil(InstallSession.owned(uid: "u1", owner: nil))
        XCTAssertNil(InstallSession.owned(uid: "u1", owner: "u2"))
        XCTAssertNil(InstallSession.owned(uid: nil, owner: "u1"))
    }

    /// The launch order (D-232's settle stays first; decision 116): no configure in init, only the install marker; once
    /// the vault opened, configure only with an owner; inside the gate, the sign-out rule right after configure and
    /// before the account attaches (nothing syncs before it); a sign-in tap configures before it signs in.
    func testTheInstallCheckRunsBeforeAnythingCanSync() throws {
        let app = try s2Source("SkiptiApp.swift")
        let initBody = try s2Slice(app, from: "    init() {\n", to: "\n    }\n")
        s2InOrder(initBody, ["AppearanceDefault.settle()", "if LaunchWork.startsCloud() { InstallSession.settle() }", "#if DEBUG"])
        for banned in ["FirebaseConfig.configure()", "FirebaseGate.configure(", "Auth.auth()"] {
            XCTAssertFalse(initBody.contains(banned), "init: \(banned)")
        }
        let gate = try s2Slice(app, from: "private func loadGate(retry: Bool = false) {", to: "\n    }\n}\n")
        s2InOrder(gate, ["let launch = VaultRecovery.launch(store)", "return Account.ownerRead(store)",
                         "let signedInMark = Account.signedInMark(store)", "let deleteOwed = Push.deleteOwed(store)",
                         "if LaunchWork.startsCloud() {", "LaunchFirebase.run(",
                         "LaunchFirebase.plan(ownerRead, signedIn: signedInMark, deleteOwed: deleteOwed)",
                         "active: scenePhase == .active", "FirebaseGate.configure(owner: ownerRead, attach: attach)",
                         "settle: { Push.shared.settleOwed() }", "sync: { CloudSync.shared.appIsActive(true) })",
                         "onboardingComplete = true"])
        let config = try s2Source("FirebaseConfig.swift")
        let configure = try s2Slice(config, from: "static func configure(owner: InstallSession.OwnerRead, attach: Bool = true) {",
                                    to: "\n    }\n")
        s2InOrder(configure, ["FirebaseConfig.configure()", "Auth.auth().useEmulator(", "InstallSession.signsOutAfterConfigure(",
                              "try? Auth.auth().signOut()", "InstallSession.clearPendingSignOut()", "Account.shared.attachToFirebase()"])
        let account = try s2Source("Account.swift")
        let signIn = try s2Slice(account, from: "    func signIn(", to: "\n    }\n")
        s2InOrder(signIn, ["FirebaseGate.configure(owner: Self.ownerRead(store))", "Auth.auth().signIn(with: credential)"])
        let screen = try s2Source("SignInScreen.swift")
        XCTAssertEqual(s2Count(screen, "FirebaseGate.configure(owner: Account.ownerRead(.defaultStore()))"), 2, "both taps")
        let sync = try s2Source("CloudSync.swift")
        let register = try s2Slice(sync, from: "    func registerPhone() {", to: "\n    }\n")
        // D-254: ownership is checked inside the approval gate now (`Approval.gate` asks `InstallSession.owned`).
        XCTAssertTrue(register.contains("Approval.gate(uid: uid, owner: Account.storedOwner(store), approval: Approval.cached(store, uid: uid)) != nil"),
                      "no registration for a session the vault does not own, nor for an account not approved")
    }

    /// Decision 116: a launch configures Firebase only when the vault names its owner; no owner, or a vault that could
    /// not be read, makes no Firebase call. Every direct Firebase call in the app sits behind the gate.
    func testANoAccountLaunchNeverConfiguresFirebase() throws {
        XCTAssertTrue(LaunchFirebase.configuresAtLaunch(.read("u1"), signedIn: true), "an install signed in")
        XCTAssertTrue(LaunchFirebase.configuresAtLaunch(.read("u1"), signedIn: nil), "an install from before the mark")
        XCTAssertFalse(LaunchFirebase.configuresAtLaunch(.read("u1"), signedIn: false),
                       "decision 117: signed out since, the owner key kept: no configure")
        XCTAssertFalse(LaunchFirebase.configuresAtLaunch(.read(nil), signedIn: true), "no account")
        XCTAssertFalse(LaunchFirebase.configuresAtLaunch(.read(""), signedIn: true))
        XCTAssertFalse(LaunchFirebase.configuresAtLaunch(.failed, signedIn: true))
        // The mark: set by a sign-in, cleared by every sign-out (here, and when the session ends elsewhere).
        let store = tempStore()
        XCTAssertNil(Account.signedInMark(store), "never written")
        Account.setSignedInMark(store, true)
        XCTAssertEqual(Account.signedInMark(store), true)
        Account.setSignedInMark(store, false)
        XCTAssertEqual(Account.signedInMark(store), false)
        let account = try s2Source("Account.swift")
        let signIn = try s2Slice(account, from: "    func signIn(", to: "\n    }\n")
        s2InOrder(signIn, ["uid = incoming", "Self.setSignedInMark(store, true)", "CloudSync.shared.appIsActive(true)"])
        let signOut = try s2Slice(account, from: "    func signOut() {", to: "\n    }\n")
        XCTAssertTrue(signOut.contains("Self.setSignedInMark(.defaultStore(), false)"))
        let attach = try s2Slice(account, from: "    func attachToFirebase() {", to: "\n    }\n")
        // D-254: the same branch also stops the approval listener (`Approval.shared.signedOut()`).
        s2InOrder(attach, ["if user == nil {", "Account.setSignedInMark(.defaultStore(), false)"])
        let app = try s2Source("SkiptiApp.swift")
        XCTAssertTrue(app.contains("LaunchFirebase.plan(ownerRead, signedIn: signedInMark, deleteOwed: deleteOwed)"))
        XCTAssertTrue(app.contains("if configuresAtLaunch(owner, signedIn: signedIn) { return .account }"))
        // The only configure is the gate's (plus the gate's own call from Cloud.db, reached only with an account).
        let dir = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().appendingPathComponent("Skipti/App")
        var configures = 0
        for file in try FileManager.default.contentsOfDirectory(atPath: dir.path) where file.hasSuffix(".swift") {
            let text = try String(contentsOf: dir.appendingPathComponent(file), encoding: .utf8)
            configures += s2Count(text, "FirebaseConfig.configure()")
            if file != "FirebaseConfig.swift" { XCTAssertFalse(text.contains("FirebaseApp.configure"), file) }
            // Auth is reached only through the gate, after a configure (sign-in, the gate itself, the emulator sign-in).
            if !["FirebaseConfig.swift", "Account.swift", "SkiptiApp.swift"].contains(file) {
                XCTAssertFalse(text.contains("Auth.auth()"), "\(file) reaches Auth outside the gate")
            }
        }
        XCTAssertEqual(configures, 1, "FirebaseGate.configure is the one caller")
        let initBody = try s2Slice(account, from: "    private init() {", to: "\n    }\n")
        XCTAssertTrue(initBody.contains("if FirebaseGate.accountAttaches { attachToFirebase() }"))
        XCTAssertFalse(initBody.contains("Auth.auth()"))
        let push = try s2Source("Push.swift")
        let start = try s2Slice(push, from: "    func start() {", to: "\n    }\n")
        XCTAssertTrue(start.contains("guard !Cloud.emulator, FirebaseGate.isConfigured else { return }"))
        let stop = try s2Slice(push, from: "func stop(inCloud: Bool, store: LedgerStore = .defaultStore()) async {", to: "\n    }\n")
        s2InOrder(stop, ["guard FirebaseGate.isConfigured else { return }", "Messaging.messaging().isAutoInitEnabled = false"])
    }

    // ---- C3: ingest ----

    /// Core's money rules at ingest: a group's expense or settlement whose money is wrong never comes in (`.invalid`),
    /// in a group's log and in the account's own; a well-formed one does.
    func testAnExpenseOrASettlementWithBadMoneyIsRefusedAtIngest() throws {
        func refusal(_ r: RecordWire) -> CloudRecords.Refusal? { CloudRecords.refusal(r, from: group, thisDevice: me) }
        let good = try expense(paid: 100, shares: [(me, 60), (them, 40)], group: group)
        XCTAssertNil(refusal(record(1, good, scope: group)))
        XCTAssertEqual(refusal(record(1, try expense(paid: 100, shares: [(me, 60), (them, 30)], group: group), scope: group)),
                       .invalid, "shares that do not add up")
        XCTAssertEqual(refusal(record(1, try expense(paid: 100, shares: [(me, 120), (them, -20)], group: group), scope: group)),
                       .invalid, "a negative share")
        XCTAssertEqual(refusal(record(1, try expense(paid: 100, shares: [], group: group), scope: group)), .invalid, "no shares")
        XCTAssertEqual(refusal(record(1, try expense(paid: 100, shares: [(me, 100)], group: group, currency: "XQQ"), scope: group)),
                       .invalid, "an unknown currency")
        XCTAssertEqual(refusal(record(1, try expense(paid: 2_000_000_000_000_000, shares: [(me, 2_000_000_000_000_000)], group: group),
                                      scope: group)), .invalid, "past the bound")
        XCTAssertNil(refusal(record(2, try settlement(from: me, to: them, amount: 50, group: group), scope: group)))
        XCTAssertEqual(refusal(record(2, try settlement(from: me, to: me, amount: 50, group: group), scope: group)), .invalid)
        XCTAssertEqual(refusal(record(2, try settlement(from: me, to: them, amount: 0, group: group), scope: group)), .invalid)
    }

    /// Decision 111 (round 2, Android's twin): the money check runs on GROUP logs only. The account's own log holds
    /// only its phones' records, checked when written; a 0.4.0 phone's personal record that would fail the group rules
    /// (a negative Adjustment share) still comes in.
    func testAPersonalRecordThatWouldFailTheGroupRulesIsStillAbsorbed() throws {
        let old = try expense(paid: 100, shares: [(me, 120), (them, -20)], group: nil)
        XCTAssertNil(CloudRecords.personalRefusal(record(1, old)))
        XCTAssertNil(CloudRecords.personalRefusal(record(2, try settlement(from: them, to: them, amount: 5, group: nil))))
        XCTAssertEqual(CloudRecords.refusal(record(1, try expense(paid: 100, shares: [(me, 120), (them, -20)], group: group),
                                                   scope: group), from: group, thisDevice: me), .invalid, "a group's is refused")
        let store = tempStore()
        _ = try store.ensureSelf()
        var doc = CloudRecords.document(record(1, old), uid: "u1")
        doc["createdAt"] = Timestamp(seconds: 1_790_000_200, nanoseconds: 0)
        XCTAssertEqual(try CloudRecords.absorb([doc], groupId: nil, store: store).count, 1, "absorbed")
        XCTAssertEqual(CloudRecords.cursor(store, nil), 1_790_000_200_000)
    }

    /// Core C1: a money edit in a group decodes in that group's scope and comes in (it used to come back personal and
    /// be refused as wrong-group); one whose new money is wrong does not.
    func testAGroupMoneyEditComesInAndABadOneDoesNot() throws {
        func refusal(_ r: RecordWire) -> CloudRecords.Refusal? { CloudRecords.refusal(r, from: group, thisDevice: me) }
        let target = "1790000000000:0:" + them
        func edit(_ shares: [(String, Int64)]) throws -> Data {
            try ffiEncodeExpenseRevision(revision: ExpenseRevisionIn(
                target: target, seen: [], editor: them,
                money: ExpenseInput(payer: me, paidMinor: 100, currencyCode: "USD",
                                    shares: shares.map { IdAmount(id: $0.0, amountMinor: $0.1) }, group: group, conversion: nil),
                note: nil, category: nil, deleted: nil))
        }
        XCTAssertNil(refusal(record(5, try edit([(me, 50), (them, 50)]), scope: group)), "a group's money edit comes in")
        let noteOnly = try ffiEncodeExpenseRevision(revision: ExpenseRevisionIn(
            target: target, seen: [], editor: them, money: nil, note: "Lunch", category: nil, deleted: nil))
        XCTAssertNil(refusal(record(5, noteOnly, scope: group)))
        // Bad money cannot even be encoded by core, so the bytes are made from a good edit's with a share changed.
        var bad = try edit([(me, 50), (them, 50)])
        let good = Data("50".utf8), worse = Data("49".utf8)
        let at = try XCTUnwrap(bad.range(of: good), "the share is in the bytes")
        bad.replaceSubrange(at, with: worse)
        XCTAssertNotEqual(refusal(record(5, bad, scope: group)), nil, "shares that no longer add up")
        XCTAssertTrue(try XCTUnwrap(try? CloudSync_source()).contains("ffiDecodeExpenseRevision(blob: record.blob, scope: group)"))
    }

    private func CloudSync_source() throws -> String { try s2Source("CloudSync.swift") }

    /// Wave A L5 (merge blocker): a document whose record version is not 1 is not a record: it is skipped, nothing
    /// reaches the vault, and the collection's cursor still moves past it (a vault refusal used to throw out of
    /// `absorb` before the cursor was saved, and that group's sync stalled for good).
    func testADocumentOfAnotherVersionIsSkippedAndTheCursorMovesPastIt() throws {
        let personal = record(1, try expense(paid: 100, shares: [(me, 100)], group: nil))
        var newer = personal
        newer.version = 2
        newer.hlcCounter = 1
        let good = CloudRecords.document(personal, uid: "u1")
        XCTAssertEqual(CloudRecords.record(good, groupId: nil), personal)
        var doc = CloudRecords.document(newer, uid: "u1")
        XCTAssertNil(CloudRecords.record(doc, groupId: nil), "version 2 is not a record")
        var zero = good
        zero["payload"] = (good["payload"] as? [String: Any]).map { p in var p = p; p["version"] = Int64(0); return p }
        XCTAssertNil(CloudRecords.record(zero, groupId: nil), "version 0 neither")
        // Round 2: nor a header the vault cannot store (a nonce that is not 4 bytes, an unknown type).
        var shortNonce = good
        shortNonce["payload"] = (good["payload"] as? [String: Any]).map { p in var p = p; p["nonce"] = Data([1, 2, 3]); return p }
        XCTAssertNil(CloudRecords.record(shortNonce, groupId: nil))
        var unknown = good
        unknown["type"] = "7"
        XCTAssertNil(CloudRecords.record(unknown, groupId: nil))
        var known = good
        known["type"] = "6"
        XCTAssertNotNil(CloudRecords.record(known, groupId: nil), "the last known type still is one")

        let store = tempStore()
        _ = try store.ensureSelf()
        let stamp = Timestamp(seconds: 1_790_000_123, nanoseconds: 456_000_000)
        doc["createdAt"] = stamp
        XCTAssertEqual(try CloudRecords.absorb([doc], groupId: group, store: store), [], "nothing came in, nothing threw")
        XCTAssertEqual(CloudRecords.cursor(store, group), CloudRecords.millis(stamp), "the cursor moved past it")
        XCTAssertFalse(try store.allRecords().contains { $0.version != 1 })
    }

    /// The one-time reset for the money edits refused since D-195: it runs once, even on a phone that ran D-210's.
    func testTheGroupCursorsStartAgainOnceForTheRefusedEdits() throws {
        let store = tempStore()
        try CloudRecords.saveGroupsUp(store, [group])
        try store.kvSet(key: CloudRecords.groupCursorsResetKey, value: Data("1".utf8))
        try CloudRecords.saveCursor(store, group, 5_000)
        try CloudRecords.saveCursor(store, nil, 7_000)
        XCTAssertTrue(try CloudRecords.resetGroupCursorsOnce(store), "D-210's ran before; this one has not")
        XCTAssertEqual(CloudRecords.cursor(store, group), 0)
        XCTAssertEqual(CloudRecords.cursor(store, nil), 7_000, "the personal cursor stays")
        try CloudRecords.saveCursor(store, group, 9_000)
        XCTAssertFalse(try CloudRecords.resetGroupCursorsOnce(store), "once")
        XCTAssertEqual(CloudRecords.cursor(store, group), 9_000)
        XCTAssertEqual(CloudRecords.groupCursorResets, ["sync/migrations/group-cursors-d210", "sync/migrations/group-cursors-d235"])
    }

    /// Decision 103 on the write side: Add expense, Settle, the import and Edit expense never write what every phone
    /// refuses at ingest. A record that breaks a rule throws core's error (the screens' existing save-error state) and
    /// nothing is written; a good one is.
    func testTheAppNeverWritesAnExpenseOrASettlementItWouldRefuse() throws {
        let store = tempStore()
        let you = try store.ensureSelf().id
        func dto(_ shares: [(String, Int64)]) -> LedgerStore.ExpenseDTO {
            LedgerStore.ExpenseDTO(payer: you, paidMinor: 100, currencyCode: "USD", group: nil, conversion: nil,
                                   shares: shares.map { .init(id: $0.0, amountMinor: $0.1) }, note: nil, timestampMs: nil,
                                   category: nil, addedBy: you)
        }
        let before = try store.allRecords().count
        XCTAssertThrowsError(try store.appendExpense(dto([(you, 90)]))) { XCTAssertTrue($0 is CoreError, "\($0)") }
        XCTAssertThrowsError(try store.appendExpense(dto([(you, 120), (them, -20)])))
        XCTAssertThrowsError(try store.appendExpenses([dto([(you, 100)]), dto([])]), "one bad record: the import writes none")
        XCTAssertThrowsError(try store.appendSettlement(LedgerStore.SettlementDTO(
            from: you, to: you, amountMinor: 10, currencyCode: "USD", group: nil)))
        XCTAssertThrowsError(try store.appendSettlement(LedgerStore.SettlementDTO(
            from: you, to: them, amountMinor: 0, currencyCode: "USD", group: nil)))
        XCTAssertEqual(try store.allRecords().count, before, "nothing was written")
        XCTAssertEqual(try store.loadExpenses(), [])
        XCTAssertEqual(try store.loadSettlements(), [])
        // The good ones are written.
        // Decision 208: a personal (Just me) expense is yours alone; one shared with someone is refused.
        XCTAssertThrowsError(try store.appendExpense(dto([(you, 60), (them, 40)])))
        XCTAssertEqual(try store.appendExpense(dto([(you, 100)])).count, 1)
        XCTAssertEqual(try store.appendSettlement(LedgerStore.SettlementDTO(
            from: you, to: them, amountMinor: 10, currencyCode: "USD", group: nil)).count, 1)
        // Edit expense: core's encode refuses bad new money, so the edit is not written either.
        let id = try XCTUnwrap(try store.projectedExpenses().expenses.first?.id)
        let records = try store.allRecords().count
        XCTAssertThrowsError(try store.reviseExpense(ExpenseRevisionIn(
            target: id, seen: [], editor: you,
            money: ExpenseInput(payer: you, paidMinor: 100, currencyCode: "USD", shares: [IdAmount(id: you, amountMinor: 70)],
                                group: nil, conversion: nil), note: nil, category: nil, deleted: nil)))
        XCTAssertEqual(try store.allRecords().count, records, "the refused edit wrote nothing")
    }

    // ---- Round 2 (wave B review I2): one refused record never stops the sync ----

    private func firestoreError(_ code: FirestoreErrorCode.Code) -> NSError {
        NSError(domain: FirestoreErrorDomain, code: code.rawValue)
    }

    /// The rule: written, or found on the server, is up; refused by the rules (permission denied, invalid argument)
    /// while the server says it is not there is refused for good; no answer from the server, or any other error, may
    /// pass and stops the push for a retry.
    func testARecordTheRulesRefuseIsSetAsideAndATransientErrorRetries() {
        XCTAssertEqual(CloudSync.uploadOutcome(writeError: nil, onServer: .there, inGroup: true), .uploaded)
        XCTAssertEqual(CloudSync.uploadOutcome(writeError: firestoreError(.permissionDenied), onServer: .there, inGroup: true), .uploaded,
                       "already there (records are never overwritten)")
        XCTAssertEqual(CloudSync.uploadOutcome(writeError: firestoreError(.permissionDenied), onServer: .notThere, inGroup: true), .refused)
        XCTAssertEqual(CloudSync.uploadOutcome(writeError: firestoreError(.invalidArgument), onServer: .notThere, inGroup: true), .refused)
        XCTAssertEqual(CloudSync.uploadOutcome(writeError: firestoreError(.permissionDenied), onServer: .noAnswer, inGroup: true), .offline,
                       "the server did not answer: may pass")
        XCTAssertEqual(CloudSync.uploadOutcome(writeError: firestoreError(.unavailable), onServer: .notThere, inGroup: true), .offline)
        XCTAssertEqual(CloudSync.uploadOutcome(writeError: firestoreError(.unauthenticated), onServer: .notThere, inGroup: true), .offline)
        XCTAssertEqual(CloudSync.uploadOutcome(writeError: NSError(domain: NSURLErrorDomain, code: -1009), onServer: .notThere, inGroup: true), .offline)
        XCTAssertEqual(CloudSync.uploadOutcome(writeError: firestoreError(.permissionDenied), onServer: .notThere, inGroup: false), .refused,
                       "a personal record the server does not hold is refused as before")
    }

    /// A batch with one refused record: the others go up, the refused one is set aside (the push goes on to the rest of
    /// its work, the shares and the pull's own listeners unaffected); a transient error stops at that record, and it
    /// and the ones after it wait for the next push.
    @MainActor
    func testOneRefusedRecordNeverStopsTheOthers() async throws {
        let records = (0..<3).map { i -> RecordWire in
            var r = record(1, Data())
            r.hlcCounter = UInt32(i)
            return r
        }
        let ids = records.map(CloudRecords.documentId)
        var tried: [String] = []
        let refusedOne = await CloudSync.uploadOneByOne(records, write: { r in
            tried.append(CloudRecords.documentId(r))
            if r.hlcCounter == 1 { throw self.firestoreError(.permissionDenied) }
        }, onServer: { _ in .notThere })
        XCTAssertEqual(refusedOne.uploaded, [ids[0], ids[2]], "the others went up")
        XCTAssertEqual(refusedOne.written, [ids[0], ids[2]])
        XCTAssertEqual(refusedOne.refused, [ids[1]])
        XCTAssertFalse(refusedOne.offline, "the push goes on")
        XCTAssertEqual(tried, ids)

        tried = []
        let transient = await CloudSync.uploadOneByOne(records, write: { r in
            tried.append(CloudRecords.documentId(r))
            if r.hlcCounter == 1 { throw self.firestoreError(.unavailable) }
        }, onServer: { _ in .noAnswer })
        XCTAssertEqual(transient.uploaded, [ids[0]])
        XCTAssertEqual(transient.refused, [], "nothing set aside: it is tried again")
        XCTAssertTrue(transient.offline, "the push stops")
        XCTAssertEqual(tried, [ids[0], ids[1]], "the records after it wait")

        // Parked: not sent again until it is due (decision 114).
        let store = tempStore()
        let now: Int64 = 1_790_000_000_000
        try CloudRecords.park(store, [ids[1]], nowMs: now)
        XCTAssertEqual(CloudRecords.heldParked(store, nowMs: now + 1), [ids[1]])
        let mine = records.map { r -> RecordWire in var r = r; r.authorDeviceId = device; r.hlcDeviceId = device; return r }
        let out = CloudRecords.outgoing(mine, uploaded: [], readOnly: [], refused: CloudRecords.heldParked(store, nowMs: now + 1))
        XCTAssertEqual(out.send.map(CloudRecords.documentId), [ids[0], ids[2]])
        let push = try s2Slice(try CloudSync_source(), from: "static func pushAll(", to: "\n    enum GroupState")
        XCTAssertTrue(push.contains("refused: refusedBefore.union(uploadRefused)"), "pushAll leaves them out")
        XCTAssertTrue(push.contains("let uploadRefused = CloudRecords.heldParked(store, nowMs: CloudRecords.nowMs())"))
        XCTAssertTrue(push.contains("try? CloudRecords.park(store, Set(one.refused), nowMs: CloudRecords.nowMs())"))
        XCTAssertTrue(push.contains("try? CloudRecords.unpark(store, uploaded.subtracting(uploadedBefore))"))
        XCTAssertTrue(push.contains("offline = one.offline"))
    }

    // ---- D-235 look F3: an open expense reads again when sync brings a change ----

    /// The detail's load is keyed on the same change signal the list reads (`SyncStatus.shared.revision`), so a remote
    /// edit (or delete) reaches an open expense; a plain `.task` ran once and kept the old amount.
    func testTheExpenseDetailReadsAgainOnEverySyncChange() throws {
        let detail = try s2Source("ExpenseDetailView.swift")
        let body = try s2Slice(detail, from: "struct ExpenseDetailView", to: "    /// The expense's group is a read-only copy")
        XCTAssertTrue(body.contains(".task(id: SyncStatus.shared.revision) { load() }"), "the detail follows sync's changes")
        XCTAssertFalse(body.contains(".task { load() }"), "no load that runs once")
        let list = try s2Source("ExpenseListView.swift")
        XCTAssertTrue(list.contains(".task(id: SyncStatus.shared.revision) { load() }"), "the list's own signal")
    }

    // ---- D-235 look F4: a record for a group this account was removed from never stops the others ----

    /// The rules refuse a removed member both the write and the existence read (a records read needs membership, or
    /// `former` plus a document that exists), so the read's refusal is an answer, not silence: a group's record is
    /// parked (decision 114) and the push goes on. Only a write refused by permission counts; a network, unavailable or
    /// unauthenticated error, an invalid argument, or a personal record still stops the push for a retry.
    func testAGroupRecordTheServerWillNotEvenShowIsParkedAndANetworkErrorStillStops() {
        XCTAssertEqual(CloudSync.uploadOutcome(writeError: firestoreError(.permissionDenied), onServer: .readRefused, inGroup: true),
                       .refused, "removed from the group: write and read both refused")
        XCTAssertEqual(CloudSync.uploadOutcome(writeError: firestoreError(.permissionDenied), onServer: .readRefused, inGroup: false),
                       .offline, "a personal record refused both ways is auth trouble: it stops")
        XCTAssertEqual(CloudSync.uploadOutcome(writeError: firestoreError(.invalidArgument), onServer: .readRefused, inGroup: true),
                       .offline, "only a permission refusal of the write pairs with a refused read")
        XCTAssertEqual(CloudSync.uploadOutcome(writeError: firestoreError(.unavailable), onServer: .readRefused, inGroup: true), .offline)
        XCTAssertEqual(CloudSync.uploadOutcome(writeError: firestoreError(.unauthenticated), onServer: .readRefused, inGroup: true),
                       .offline)
        XCTAssertEqual(CloudSync.uploadOutcome(writeError: NSError(domain: NSURLErrorDomain, code: -1009), onServer: .readRefused,
                                               inGroup: true), .offline)
        XCTAssertEqual(CloudSync.uploadOutcome(writeError: firestoreError(.permissionDenied), onServer: .noAnswer, inGroup: true), .offline)
        XCTAssertTrue(CloudSync.isPermissionDenied(firestoreError(.permissionDenied)))
        XCTAssertFalse(CloudSync.isPermissionDenied(firestoreError(.invalidArgument)))
        XCTAssertFalse(CloudSync.isPermissionDenied(firestoreError(.unauthenticated)))
        XCTAssertFalse(CloudSync.isPermissionDenied(NSError(domain: NSURLErrorDomain, code: FirestoreErrorCode.permissionDenied.rawValue)))
    }

    /// The existence read's answer: a document, none, a permission refusal, or no answer; and signed out (the rules then
    /// refuse every read too) nothing is asked and it is no answer, so a sign-out mid-push never parks anything.
    func testTheExistenceReadMapsEachAnswer() async {
        var asked = 0
        let there = await CloudSync.serverCopy(signedIn: true) { asked += 1; return true }
        XCTAssertEqual(there, .there)
        let notThere = await CloudSync.serverCopy(signedIn: true) { asked += 1; return false }
        XCTAssertEqual(notThere, .notThere)
        let denied = await CloudSync.serverCopy(signedIn: true) { asked += 1; throw self.firestoreError(.permissionDenied) }
        XCTAssertEqual(denied, .readRefused)
        let unavailable = await CloudSync.serverCopy(signedIn: true) { asked += 1; throw self.firestoreError(.unavailable) }
        XCTAssertEqual(unavailable, .noAnswer)
        let network = await CloudSync.serverCopy(signedIn: true) { asked += 1; throw NSError(domain: NSURLErrorDomain, code: -1009) }
        XCTAssertEqual(network, .noAnswer)
        XCTAssertEqual(asked, 5)
        let signedOut = await CloudSync.serverCopy(signedIn: false) { asked += 1; throw self.firestoreError(.permissionDenied) }
        XCTAssertEqual(signedOut, .noAnswer, "signed out: never a refused read")
        XCTAssertEqual(asked, 5, "signed out: nothing asked")
    }

    /// One push holding a record for a group this account was removed from and records for a group it is still in:
    /// the removed group's record is parked, the others still go up, and the push goes on. A network error stops the
    /// push as before, even with the read refused.
    @MainActor
    func testARemovedGroupsRecordIsParkedAndAnotherGroupsRecordStillGoesUp() async throws {
        let removed = String(repeating: "a", count: 32), kept = String(repeating: "b", count: 32)
        let records = [(removed, 0), (kept, 1), (kept, 2)].map { g, i -> RecordWire in
            var r = record(1, Data(), scope: g)
            r.hlcCounter = UInt32(i)
            return r
        }
        let ids = records.map(CloudRecords.documentId)
        let push = await CloudSync.uploadOneByOne(records, write: { r in
            if r.groupId == removed { throw self.firestoreError(.permissionDenied) }
        }, onServer: { r in
            await CloudSync.serverCopy(signedIn: true) {
                if r.groupId == removed { throw self.firestoreError(.permissionDenied) }
                return false
            }
        })
        XCTAssertEqual(push.refused, [ids[0]], "the removed group's record is parked")
        XCTAssertEqual(push.uploaded, [ids[1], ids[2]], "the group it is still in gets its records")
        XCTAssertEqual(push.written, [ids[1], ids[2]])
        XCTAssertFalse(push.offline, "the push goes on")

        var tried: [String] = []
        let network = await CloudSync.uploadOneByOne(records, write: { r in
            tried.append(CloudRecords.documentId(r))
            throw NSError(domain: NSURLErrorDomain, code: -1009)
        }, onServer: { _ in .readRefused })
        XCTAssertEqual(network.refused, [], "nothing parked offline")
        XCTAssertTrue(network.offline, "a network error still stops the push")
        XCTAssertEqual(tried, [ids[0]], "the records after it wait")

        // A personal record refused both ways stops the push too.
        let personal = await CloudSync.uploadOneByOne([record(1, Data())], write: { _ in throw self.firestoreError(.permissionDenied) },
                                                      onServer: { _ in .readRefused })
        XCTAssertEqual(personal.refused, [])
        XCTAssertTrue(personal.offline)

        // pushAll asks the server through serverCopy, only while signed in as the pushing account.
        let source = try s2Slice(try CloudSync_source(), from: "static func pushAll(", to: "\n    enum GroupState")
        XCTAssertTrue(source.contains("}, onServer: { record in"))
        XCTAssertTrue(source.contains("await serverCopy(signedIn: Account.shared.uid == uid) {"))
        let one = try s2Slice(try CloudSync_source(), from: "static func uploadOneByOne(", to: "\n    enum GroupState")
        XCTAssertTrue(one.contains("inGroup: record.groupId != nil"))
    }

    // ---- Round 3 (decision 110 a): the note field stops at core's bound ----

    /// Core counts a note in Unicode scalars (Rust's `chars()`) and refuses more than 1,000; the field cuts at the last
    /// whole character within that, never splitting an emoji; at or under the bound nothing changes.
    func testTheNoteFieldStopsAtCoresBound() throws {
        XCTAssertEqual(NoteLimit.maxScalars, 1_000)
        let atBound = String(repeating: "x", count: 1_000)
        XCTAssertEqual(NoteLimit.capped(atBound), atBound)
        XCTAssertEqual(NoteLimit.capped(atBound + "y"), atBound, "one past is cut")
        XCTAssertEqual(NoteLimit.capped("Dinner"), "Dinner")
        XCTAssertEqual(NoteLimit.capped(String(repeating: "\u{1F600}", count: 1_001)).unicodeScalars.count, 1_000)
        let family = "\u{1F468}\u{200D}\u{1F469}\u{200D}\u{1F467}"  // one character, five scalars
        XCTAssertEqual(NoteLimit.capped(String(repeating: "x", count: 998) + family), String(repeating: "x", count: 998),
                       "the emoji is not split")
        XCTAssertLessThanOrEqual(NoteLimit.capped(String(repeating: family, count: 300)).unicodeScalars.count, 1_000)
        let screen = try s2Source("AddExpenseScreen.swift")
        s2InOrder(screen, ["FieldRow(AddSheetCopy.whatFor, text: $note)", ".onChange(of: note) { _, typed in",
                           "let capped = NoteLimit.capped(typed)", "if capped != typed { note = capped }"])
        XCTAssertEqual(s2Count(screen, "NoteLimit.capped(typed)"), 1)
    }

    /// Decision 112: the bound is core's own constant, read from the Rust source, so the two can never drift.
    func testTheNoteBoundIsCoresConstant() throws {
        let check = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent().appendingPathComponent("core/src/straumr/check.rs")
        let rust = try String(contentsOf: check, encoding: .utf8)
        let line = try XCTUnwrap(rust.components(separatedBy: "\n").first { $0.hasPrefix("pub const TEXT_MAX_CHARS: usize = ") })
        let value = try XCTUnwrap(Int(line.replacingOccurrences(of: "pub const TEXT_MAX_CHARS: usize = ", with: "")
            .replacingOccurrences(of: ";", with: "").replacingOccurrences(of: "_", with: "")))
        XCTAssertEqual(NoteLimit.maxScalars, value)
        XCTAssertTrue(rust.contains("text.chars().count() > TEXT_MAX_CHARS"), "core counts scalars, as NoteLimit does")
    }

    /// Decision 112: the store refuses a note past the bound on a new expense, an import and an edit, into the
    /// save-error state (core's error), writing nothing; at the bound it is written.
    func testTheStoreNeverWritesANotePastTheBound() throws {
        let store = tempStore()
        let you = try store.ensureSelf().id
        func dto(_ note: String) -> LedgerStore.ExpenseDTO {
            LedgerStore.ExpenseDTO(payer: you, paidMinor: 100, currencyCode: "USD", group: nil, conversion: nil,
                                   shares: [.init(id: you, amountMinor: 100)], note: note, timestampMs: nil, category: nil,
                                   addedBy: you)
        }
        let long = String(repeating: "x", count: 1_001), atBound = String(repeating: "x", count: 1_000)
        let before = try store.allRecords().count
        XCTAssertThrowsError(try store.appendExpense(dto(long))) { XCTAssertTrue($0 is CoreError, "\($0)") }
        XCTAssertThrowsError(try store.appendExpenses([dto("ok"), dto(long)]))
        XCTAssertEqual(try store.allRecords().count, before, "nothing written")
        XCTAssertEqual(try store.appendExpense(dto(atBound)).count, 1, "at the bound it is written")
        let id = try XCTUnwrap(try store.projectedExpenses().expenses.first?.id)
        let records = try store.allRecords().count
        XCTAssertThrowsError(try store.reviseExpense(ExpenseRevisionIn(
            target: id, seen: [], editor: you, money: nil, note: long, category: nil, deleted: nil)))
        XCTAssertEqual(try store.allRecords().count, records, "the refused edit wrote nothing")
        XCTAssertNoThrow(try store.reviseExpense(ExpenseRevisionIn(
            target: id, seen: [], editor: you, money: nil, note: "Lunch", category: nil, deleted: nil)))
    }

    // ---- Round 4 (shells review I1, I2, M1, M2, M4, M5, M8, M10; decision 114) ----

    /// I1: sign-out and sync off remove what may exist, not only what this process registered (Android's
    /// `sign_out_removes_what_may_exist_not_only_what_this_process_registered`).
    func testSignOutRemovesWhatMayExistNotOnlyWhatThisProcessRegistered() throws {
        // The audit's case: registered in an earlier process, this launch could not register (offline).
        XCTAssertEqual(PushCleanup.plan(inCloud: true, uid: "u1", inMemory: false, registeredFor: "u1", wasRunning: false,
                                        deleteOwed: false), .init(clearCloud: true, deleteAtFcm: true))
        // Sync off the same way, and a delete still owed is tried again.
        XCTAssertEqual(PushCleanup.plan(inCloud: false, uid: "u1", inMemory: false, registeredFor: nil, wasRunning: false,
                                        deleteOwed: true), .init(clearCloud: false, deleteAtFcm: true))
        // Another account's mark is never cleared from this account's document.
        XCTAssertEqual(PushCleanup.plan(inCloud: true, uid: "u2", inMemory: false, registeredFor: "u1", wasRunning: false,
                                        deleteOwed: false), .init(clearCloud: false, deleteAtFcm: true))
        // Nothing registered, nothing running, nothing owed: no call to FCM at all (no account contacts nobody).
        XCTAssertEqual(PushCleanup.plan(inCloud: true, uid: nil, inMemory: false, registeredFor: nil, wasRunning: false,
                                        deleteOwed: false), .init(clearCloud: false, deleteAtFcm: false))
        let push = try s2Source("Push.swift")
        let save = try s2Slice(push, from: "func saveToken() {", to: "\n    }\n")
        XCTAssertTrue(save.contains("kvSet(key: Self.registeredKey, value: Data(uid.utf8))"), "the mark is kept in the vault")
        let stop = try s2Slice(push, from: "func stop(inCloud: Bool, store: LedgerStore = .defaultStore()) async {", to: "\n    }\n")
        s2InOrder(stop, ["PushCleanup.plan(", "registeredFor: Self.mark(store, Self.registeredKey)",
                         "Bounded.run(seconds: Self.cloudTimeout", "if plan.deleteAtFcm { await deleteAtFcm(store) }"])
        let sync = try s2Source("CloudSync.swift")
        XCTAssertTrue(sync.contains("if LaunchWork.startsCloud(), Account.shared.uid == nil { Push.shared.settleOwed() }"))
    }

    /// D-235 final wave (I-1): a sign-out on a launch that could not register (offline, push never ran) still stops
    /// APNs delivering to this app at once: whenever the plan deletes at FCM, not only when push ran in this process.
    @MainActor
    func testAnOfflineSignOutStopsDeliveryAtOnce() async throws {
        guard !FirebaseGate.isConfigured else { return XCTFail("the test host must never reach FCM") }
        let saved = Push.unregisterRemote
        defer { Push.unregisterRemote = saved }
        var unregistered = 0
        Push.unregisterRemote = { unregistered += 1 }
        // The review's case: a token saved by an earlier launch, this launch offline (push not running).
        let registered = tempStore()
        try registered.kvSet(key: Push.registeredKey, value: Data("u1".utf8))
        await Push.shared.stop(inCloud: true, store: registered)
        XCTAssertEqual(unregistered, 1, "APNs stops delivering, online or not")
        // A delete still owed alone (sync off later).
        let owed = tempStore()
        try owed.kvSet(key: Push.deleteOwedKey, value: Data("1".utf8))
        await Push.shared.stop(inCloud: false, store: owed)
        XCTAssertEqual(unregistered, 2)
        // Nothing registered, owed or running: nothing to stop, the registration is left alone.
        await Push.shared.stop(inCloud: true, store: tempStore())
        XCTAssertEqual(unregistered, 2)
    }

    /// D-235 final wave (I-1): a signed-out install that still owes its token delete configures Firebase at launch only
    /// to settle it (no account attached, no sync); with nothing owed it makes no Firebase call (decision 117 holds).
    @MainActor
    func testALaunchWithADeleteOwedSettlesItAndStaysQuiet() throws {
        XCTAssertEqual(LaunchFirebase.plan(.read("u1"), signedIn: false, deleteOwed: true), .settleOwed)
        XCTAssertEqual(LaunchFirebase.plan(.read("u1"), signedIn: false, deleteOwed: false), .none, "nothing owed: no Firebase")
        XCTAssertEqual(LaunchFirebase.plan(.read("u1"), signedIn: true, deleteOwed: true), .account, "signed in: the account's launch")
        XCTAssertEqual(LaunchFirebase.plan(.read("u1"), signedIn: nil, deleteOwed: false), .account)
        XCTAssertEqual(LaunchFirebase.plan(.read(nil), signedIn: nil, deleteOwed: true), .none, "no owner: never had an account")
        XCTAssertEqual(LaunchFirebase.plan(.read(""), signedIn: false, deleteOwed: true), .none)
        XCTAssertEqual(LaunchFirebase.plan(.failed, signedIn: false, deleteOwed: true), .none, "an unreadable vault: fail closed")
        XCTAssertEqual(LaunchFirebase.plan(.read(nil), signedIn: nil, deleteOwed: false), .none)
        func run(_ plan: LaunchFirebase.Plan, active: Bool) -> [String] {
            var did: [String] = []
            LaunchFirebase.run(plan, active: active, configure: { did.append($0 ? "configure+account" : "configure") },
                               settle: { did.append("settle") }, sync: { did.append("sync") })
            return did
        }
        XCTAssertEqual(run(.settleOwed, active: true), ["configure", "settle"], "no account attached, no sync")
        XCTAssertEqual(run(.settleOwed, active: false), ["configure"], "the next return to the front settles it")
        XCTAssertEqual(run(.account, active: true), ["configure+account", "sync"])
        XCTAssertEqual(run(.account, active: false), ["configure+account"])
        XCTAssertEqual(run(.none, active: true), [])
        // The launch's read of the owed mark.
        let store = tempStore()
        XCTAssertFalse(Push.deleteOwed(store))
        try store.kvSet(key: Push.deleteOwedKey, value: Data("1".utf8))
        XCTAssertTrue(Push.deleteOwed(store))
        try store.kvSet(key: Push.deleteOwedKey, value: Data())
        XCTAssertFalse(Push.deleteOwed(store), "cleared")
        // The gate attaches the account only when asked, and the account attaches only through it.
        let config = try s2Source("FirebaseConfig.swift")
        let configure = try s2Slice(config, from: "static func configure(owner: InstallSession.OwnerRead, attach: Bool = true) {",
                                    to: "\n    }\n")
        s2InOrder(configure, ["InstallSession.clearPendingSignOut()", "guard attach else { return }", "accountAttaches = true",
                              "Account.shared.attachToFirebase()"])
        let account = try s2Source("Account.swift")
        let attach = try s2Slice(account, from: "    func attachToFirebase() {", to: "\n    }\n")
        XCTAssertTrue(attach.contains("guard listener == nil, FirebaseGate.accountAttaches, let auth = FirebaseGate.auth"))
        let push = try s2Source("Push.swift")
        let settle = try s2Slice(push, from: "    func settleOwed() {", to: "\n    }\n")
        s2InOrder(settle, ["guard !Cloud.emulator, !running, !settling, FirebaseGate.isConfigured", "guard Self.deleteOwed(store)",
                           "settling = true", "await deleteAtFcm(store)", "settling = false"])
        XCTAssertFalse(FirebaseGate.accountAttaches, "the test host attaches no account")
    }

    /// Decision 118 (twin parity): SafeImage's bounds are core's, read from groups.rs, not copied by hand (Android's
    /// `safe_image_bounds_are_cores`).
    func testSafeImageBoundsAreCores() throws {
        let rust = try String(contentsOf: URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent().appendingPathComponent("core/src/straumr/groups.rs"), encoding: .utf8)
        func constant(_ name: String) throws -> Int {
            let prefix = "pub const \(name): u32 = "
            let line = try XCTUnwrap(rust.components(separatedBy: "\n").first { $0.hasPrefix(prefix) }, "no \(name)")
            let expr = line.dropFirst(prefix.count).trimmingCharacters(in: CharacterSet(charactersIn: "; "))
            return try expr.split(separator: "*").map { try XCTUnwrap(Int($0.trimmingCharacters(in: .whitespaces)), name) }
                .reduce(1, *)
        }
        XCTAssertEqual(SafeImage.photo, SafeImage.Caps(maxSide: try constant("PICTURE_MAX_SIDE"),
                                                       maxPixels: try constant("PICTURE_MAX_PIXELS")))
        XCTAssertEqual(SafeImage.preview, SafeImage.Caps(maxSide: try constant("THUMB_MAX_SIDE"),
                                                         maxPixels: try constant("THUMB_MAX_PIXELS")))
        XCTAssertEqual(SafeImage.photo.maxPixels, 2_097_152, "re-derived: 2048 x 1024")
    }

    /// I1: a call that never answers is given up on in bounded time (offline, Firestore writes never complete).
    @MainActor
    func testACloudCallThatNeverAnswersIsBounded() async {
        let started = Date()
        let hung = await Bounded.run(seconds: 0.2) { await withCheckedContinuation { (_: CheckedContinuation<Void, Never>) in } }
        XCTAssertFalse(hung)
        XCTAssertLessThan(Date().timeIntervalSince(started), 2, "the caller went on")
        let ok = await Bounded.run(seconds: 2) {}
        XCTAssertTrue(ok)
        struct Refused: Error {}
        let threw = await Bounded.run(seconds: 2) { throw Refused() }
        XCTAssertFalse(threw)
        let answer = await Bounded.value(seconds: 2, otherwise: 0) { 7 }
        XCTAssertEqual(answer, 7)
    }

    /// I1: making a group in the cloud never hangs ("Making an invite" spun for good offline).
    @MainActor
    func testEnsuringAGroupNeverHangs() async {
        let never: @MainActor () async throws -> Void = { await withCheckedContinuation { (_: CheckedContinuation<Void, Never>) in } }
        let neverRead: @MainActor () async -> CloudSync.GroupState = {
            await withCheckedContinuation { (_: CheckedContinuation<Void, Never>) in }
            return .member
        }
        struct Refused: Error {}
        let started = Date()
        let offline = await CloudSync.decideGroup(create: never, read: neverRead, createSeconds: 0.2, readSeconds: 0.2)
        XCTAssertEqual(offline, .offline)
        XCTAssertLessThan(Date().timeIntervalSince(started), 3)
        let created = await CloudSync.decideGroup(create: {}, read: { .notMember })
        XCTAssertEqual(created, .member, "created")
        let theirs = await CloudSync.decideGroup(create: { throw Refused() }, read: { .notMember }, createSeconds: 1, readSeconds: 1)
        XCTAssertEqual(theirs, .notMember, "someone else's")
        let there = await CloudSync.decideGroup(create: { throw Refused() }, read: { .member }, createSeconds: 1, readSeconds: 1)
        XCTAssertEqual(there, .member, "already there")
    }

    /// I2: eight writers mint stamps at one wall time from one vault: every stamp is new (without the lock two read one
    /// state and mint one stamp twice; a record's identity is its stamp, so the second replaced the first).
    func testConcurrentWritersNeverMintTheSameStamp() throws {
        let store = tempStore()
        _ = try LedgerStore.Sync.deviceId(store)
        let now: UInt64 = 4_000_000_000_000
        let lock = NSLock()
        var stamps: [String] = []
        var errors: [Error] = []
        DispatchQueue.concurrentPerform(iterations: 8) { _ in
            for i in 0..<25 {
                do {
                    let s = try LedgerStore.Sync.nextHlc(store, nowMs: now)
                    lock.lock(); stamps.append("\(s.millis):\(s.counter)"); lock.unlock()
                    if i % 5 == 0 { try LedgerStore.Sync.receiveHlc(store, millis: now, counter: 0, nowMs: now) }
                } catch {
                    lock.lock(); errors.append(error); lock.unlock()
                }
            }
        }
        XCTAssertTrue(errors.isEmpty, "no writer failed: \(errors)")
        XCTAssertEqual(stamps.count, 200)
        XCTAssertEqual(Set(stamps).count, 200, "every stamp is new")
    }

    /// M1: the curtain is made afresh when its scene is not the scene in front.
    @MainActor
    func testTheCurtainIsMadeAgainOnANewScene() throws {
        let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
        XCTAssertTrue(LockCurtainRules.needsNewWindow(nil, scene: scene))
        XCTAssertFalse(LockCurtainRules.needsNewWindow(UIWindow(windowScene: scene), scene: scene))
        XCTAssertTrue(LockCurtainRules.needsNewWindow(UIWindow(frame: .zero), scene: scene), "a window of no scene (gone)")
    }

    /// M10: the keyboard goes before the cover too, so the app switcher's snapshot shows no keyboard; down, nothing.
    @MainActor
    func testTheKeyboardGoesBeforeTheCurtainComesUp() {
        final class Recording: UIWindow {
            var ended = 0
            override func endEditing(_ force: Bool) -> Bool { ended += 1; return true }
        }
        let curtain = UIWindow(frame: .zero)
        for content in [CurtainContent.cover, .lock] {
            let main = Recording(frame: .zero)
            LockCurtainRules.apply(content, curtain: curtain, others: [main])
            XCTAssertEqual(main.ended, 1, "\(content)")
            LockCurtainRules.apply(nil, curtain: curtain, others: [main])
            XCTAssertEqual(main.ended, 1, "down ends nothing")
        }
    }

    /// M4: a vault that cannot be read is an error for the owner read (never "no owner"), and nil for the cloud gates.
    func testAnUnreadableVaultNeverReadsAsNoOwner() throws {
        let dir = tempDir("owner")
        let path = dir.appendingPathComponent("vault.db").path
        FileManager.default.createFile(atPath: path, contents: Data(repeating: 7, count: 4096))
        let broken = LedgerStore(path: path)
        XCTAssertThrowsError(try Account.readOwner(broken))
        XCTAssertNil(Account.storedOwner(broken), "the gates fail closed")
        let good = tempStore()
        XCTAssertNil(try Account.readOwner(good))
        try good.kvSet(key: Account.ownerKey, value: Data("u1".utf8))
        XCTAssertEqual(try Account.readOwner(good), "u1")
        let account = try s2Source("Account.swift")
        let signIn = try s2Slice(account, from: "    func signIn(", to: "\n    }\n")
        XCTAssertTrue(signIn.contains("owner = try Self.readOwner(store)"), "sign-in never writes over an unread owner")
        XCTAssertFalse(signIn.contains("storedOwner("))
    }

    /// Decision 114 (M5): a parked record comes back once a day, at most three times, then stays parked; one that goes
    /// up is parked no more; a clock that went back (a fast clock fixed) makes it due.
    func testAParkedUploadIsRetriedOnceADayThreeTimesThenStays() throws {
        let store = tempStore()
        let day = CloudRecords.parkRetryAfterMs
        var now: Int64 = 1_790_000_000_000
        try CloudRecords.park(store, ["r"], nowMs: now)
        XCTAssertEqual(try CloudRecords.parked(store)["r"], .init(refusals: 1, lastMs: now))
        XCTAssertEqual(CloudRecords.heldParked(store, nowMs: now + day - 1), ["r"], "not before a day")
        for refusal in 2...4 {
            now += day
            XCTAssertEqual(CloudRecords.heldParked(store, nowMs: now), [], "retry \(refusal - 1) is due")
            try CloudRecords.park(store, ["r"], nowMs: now)
            XCTAssertEqual(try CloudRecords.parked(store)["r"]?.refusals, refusal)
        }
        XCTAssertEqual(CloudRecords.heldParked(store, nowMs: now + 30 * day), ["r"], "after three retries it stays parked")
        XCTAssertFalse(CloudRecords.due(.init(refusals: 4, lastMs: 0), nowMs: now), "for good")
        XCTAssertTrue(CloudRecords.due(.init(refusals: 1, lastMs: now), nowMs: now - 1), "the clock went back")
        try CloudRecords.park(store, ["s"], nowMs: now)
        try CloudRecords.unpark(store, ["s"])
        XCTAssertNil(try CloudRecords.parked(store)["s"], "up at last")
        XCTAssertNotNil(try CloudRecords.parked(store)["r"])
        // A bare id (round 2's format) reads as one refusal at time 0: due.
        try store.kvSet(key: CloudRecords.uploadRefusedKey, value: Data("old".utf8))
        XCTAssertEqual(try CloudRecords.parked(store)["old"], .init(refusals: 1, lastMs: 0))
        XCTAssertEqual(CloudRecords.heldParked(store, nowMs: now), [])
    }

    /// Decision 122: a group's page shows its quiet line while any of this phone's records in THAT group is parked (due
    /// again or not), not for a record parked in another group or a personal one, not with none, and not once it went
    /// up. Real vault, real group records.
    func testAGroupWithAParkedChangeSaysSoInOneQuietLine() throws {
        let store = tempStore()
        _ = try store.setYourName("Asha")
        let goa = try store.createGroup(name: "Goa", settlementCurrency: "INR", people: ["Sam"]).group.id
        let oslo = try store.createGroup(name: "Oslo", settlementCurrency: "NOK", people: ["Kari"]).group.id
        let all = try store.allRecords()
        let inGoa = try XCTUnwrap(all.first { $0.groupId == goa }.map(CloudRecords.documentId))
        let inOslo = try XCTUnwrap(all.first { $0.groupId == oslo }.map(CloudRecords.documentId))
        XCTAssertFalse(try CloudRecords.hasParked(store, group: goa), "nothing parked: no line")
        XCTAssertFalse(try CloudRecords.hasParked(store, group: oslo))

        let now: Int64 = 1_790_000_000_000
        try CloudRecords.park(store, [inGoa], nowMs: now)
        XCTAssertTrue(try CloudRecords.hasParked(store, group: goa), "parked in Goa: Goa's page says so")
        XCTAssertFalse(try CloudRecords.hasParked(store, group: oslo), "not Oslo's")
        // Due again or exhausted, it is still parked: the line stays.
        for _ in 0..<4 { try CloudRecords.park(store, [inGoa], nowMs: now) }
        XCTAssertTrue(try CloudRecords.hasParked(store, group: goa))
        // A record parked outside any group (or one this phone does not hold) shows on no group.
        let personal = CloudRecords.documentId(record(1, Data("{}".utf8)))
        XCTAssertFalse(CloudRecords.parkedIn(group: oslo, records: all + [record(1, Data("{}".utf8))], parked: [personal, "1-0-x"]))
        XCTAssertTrue(CloudRecords.parkedIn(group: oslo, records: all, parked: [inOslo]))
        // Up at last: the line goes.
        XCTAssertTrue(try CloudRecords.unpark(store, [inGoa]))
        XCTAssertFalse(try CloudRecords.hasParked(store, group: goa))
        XCTAssertFalse(try CloudRecords.unpark(store, [inGoa]), "nothing left to let go")

        // The copy (Android's parity test reads it from here) and the page's wiring.
        XCTAssertEqual(OverviewCopy.couldNotSync, "One change could not sync. It is still on this phone.")
        let page = try s2Slice(try s2Source("OverviewScreen.swift"), from: "struct GroupPageScreen: View {", to: "\n}\n")
        XCTAssertTrue(page.contains("parkedHere = try CloudRecords.hasParked(store, group: groupID)"))
        XCTAssertTrue(page.contains("if parkedHere {\n" + String(repeating: " ", count: 24) + "Text(verbatim: OverviewCopy.couldNotSync)\n"
            + String(repeating: " ", count: 28) + ".font(TypeRole.body(TypeScale.detail)).foregroundStyle(Tokens.textDim)\n"),
                      "one quiet line, the header's meta style")
        let push = try s2Slice(try CloudSync_source(), from: "static func pushAll(", to: "\n    enum GroupState")
        XCTAssertTrue(push.contains("if (try? CloudRecords.unpark(store, uploaded.subtracting(uploadedBefore))) == true { SyncStatus.shared.changed() }"),
                      "a page open on the group drops the line when the record goes up")
    }

    /// M8: a stored picture decodes with its size read first and downsampled; one declaring a huge frame is refused
    /// unread, never decoded at full size.
    func testAStoredPictureDecodesSafely() throws {
        func jpeg(_ w: Int, _ h: Int) -> Data {
            let format = UIGraphicsImageRendererFormat()
            format.scale = 1
            return UIGraphicsImageRenderer(size: CGSize(width: w, height: h), format: format).jpegData(withCompressionQuality: 0.8) { c in
                UIColor.orange.setFill(); c.fill(CGRect(x: 0, y: 0, width: w, height: h))
            }
        }
        let small = try XCTUnwrap(SafeImage.decode(jpeg(120, 80)))
        XCTAssertEqual(small.cgImage?.width, 120)
        let wide = try XCTUnwrap(SafeImage.decode(jpeg(2_048, 600), maxPixel: 512))
        XCTAssertLessThanOrEqual(max(wide.cgImage?.width ?? .max, wide.cgImage?.height ?? .max), 512, "downsampled")
        XCTAssertNil(SafeImage.decode(jpeg(2_049, 100)), "decision 118: a real picture past core's side is refused")
        XCTAssertNil(SafeImage.decode(Data("not a picture".utf8)))
        // A real JPEG whose frame header now declares 65,535 by 65,535.
        var huge = jpeg(16, 16)
        let sof = try XCTUnwrap((0..<(huge.count - 9)).first { huge[$0] == 0xFF && huge[$0 + 1] == 0xC0 })
        for offset in [sof + 5, sof + 6, sof + 7, sof + 8] { huge[offset] = 0xFF }
        XCTAssertNil(SafeImage.decode(huge), "refused unread")
        // Decision 118: core's bounds, at the edge and one past (Android's `safe_image_bounds` rows).
        XCTAssertTrue(SafeImage.fits(width: 2_048, height: 1_024), "exactly the pixel cap")
        XCTAssertTrue(SafeImage.fits(width: 1_024, height: 2_048))
        XCTAssertFalse(SafeImage.fits(width: 2_048, height: 1_025), "one pixel past the cap")
        XCTAssertFalse(SafeImage.fits(width: 2_049, height: 10), "a side past the bound")
        XCTAssertFalse(SafeImage.fits(width: 7_000, height: 7_000))
        XCTAssertFalse(SafeImage.fits(width: 0, height: 512))
        XCTAssertTrue(SafeImage.fits(width: 512, height: 512, caps: SafeImage.preview))
        XCTAssertFalse(SafeImage.fits(width: 513, height: 100, caps: SafeImage.preview))
        XCTAssertFalse(SafeImage.fits(width: 512, height: 513, caps: SafeImage.preview))
        for (file, banned) in [("GroupPictures.swift", "let image = UIImage(data: bytes)"), ("Profile.swift", "if let image = UIImage(data: data)"),
                               ("Profile.swift", "return UIImage(data: data) != nil"), ("Profile.swift", "let image = UIImage(data: data) {")] {
            XCTAssertFalse(try s2Source(file).contains(banned), "\(file): \(banned)")
        }
    }

    // ---- C4 ----

    /// M1: the export files leave tmp: every `skipti-export-*` there goes, nothing else does.
    func testTheExportFilesAreRemovedAndNothingElse() throws {
        let dir = tempDir("exports")
        for name in ["skipti-export-2026-10-19.csv", "skipti-export-2026-10-19.json", "other.csv"] {
            FileManager.default.createFile(atPath: dir.appendingPathComponent(name).path, contents: Data([1]))
        }
        XCTAssertEqual(try Exporter.removeExports(in: dir), 2)
        XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: dir.path), ["other.csv"])
        XCTAssertEqual(try Exporter.removeExports(in: dir), 0, "nothing left to remove")
        let shared = dir.appendingPathComponent("skipti-export-2026-10-20.csv")
        FileManager.default.createFile(atPath: shared.path, contents: Data([1]))
        ExportFiles.done(with: shared)
        XCTAssertFalse(FileManager.default.fileExists(atPath: shared.path), "gone once the share sheet is done with it")
        let flow = try s2Source("ExportFlow.swift")
        XCTAssertTrue(flow.contains("ActivityShareSheet(url: url, onFinish: { ExportFiles.done(with: url) })"))
        XCTAssertTrue(flow.contains("$0.completionWithItemsHandler = { _, _, _, _ in onFinish() }"))
        XCTAssertTrue(flow.contains(".onDisappear { _ = try? Exporter.removeExports() }"))
    }

    /// M1: the erase takes a plaintext export left in tmp too, so "Everything SKIPTI kept on this phone is gone" is true.
    func testTheEraseTakesAnExportLeftInTmp() throws {
        final class Keys: SkiptiKeystore {
            var destroyed = false
            func provision(key: Data) throws {}
            func load() throws -> Data? { nil }
            func destroy() throws { destroyed = true }
        }
        let dir = tempDir("erase")
        let vault = dir.appendingPathComponent("vault.db").path
        FileManager.default.createFile(atPath: vault, contents: Data([1]))
        let tmp = tempDir("erase-tmp")
        let export = tmp.appendingPathComponent("skipti-export-2026-10-19.json")
        FileManager.default.createFile(atPath: export.path, contents: Data("{}".utf8))
        let keys = Keys()
        try LedgerErase.erase(vaultPath: vault, keystore: keys, exports: tmp)
        XCTAssertFalse(FileManager.default.fileExists(atPath: export.path), "the export went")
        XCTAssertFalse(FileManager.default.fileExists(atPath: vault))
        XCTAssertTrue(keys.destroyed)
        XCTAssertEqual(EraseCopy.doneBody,
                       "Everything SKIPTI kept on this phone is gone. Your account, if you have one, is not deleted.")
    }

    /// M2: a mark another writer saved while a push was suspended stays (the push adds its own to the marks as stored
    /// then); the old save of the push's stale copy dropped it.
    func testAPushKeepsTheMarksAListenerSavedMeanwhile() throws {
        let store = tempStore()
        try CloudRecords.saveUploaded(store, ["a"])
        let pushCopy = try CloudRecords.uploaded(store)          // the push reads its copy, then awaits
        try CloudRecords.addUploaded(store, ["x"])                // a listener's absorb runs in the gap
        try CloudRecords.addUploaded(store, pushCopy.union(["b"]).subtracting(pushCopy))
        XCTAssertEqual(try CloudRecords.uploaded(store), ["a", "x", "b"])
        try CloudRecords.saveGroupsUp(store, [group])
        try CloudRecords.addGroupsUp(store, [them])
        XCTAssertEqual(try CloudRecords.groupsUp(store), [group, them])
        try CloudRecords.addAdoptRefused(store, ["r1"])
        try CloudRecords.addAdoptRefused(store, ["r2"])
        XCTAssertEqual(try CloudRecords.adoptRefused(store), ["r1", "r2"])
        let push = try s2Slice(try CloudSync_source(), from: "static func pushAll(", to: "\n    enum GroupState")
        XCTAssertFalse(push.contains("saveUploaded("), "pushAll only adds")
        XCTAssertFalse(push.contains("saveGroupsUp("))
        XCTAssertFalse(push.contains("saveAdoptRefused("))
    }

    /// L3: one registration at a time: a second caller for the same account awaits the one in flight.
    @MainActor
    func testOneRegistrationAtATime() async {
        let flight = SingleFlight<Int>()
        var runs = 0
        var release: CheckedContinuation<Void, Never>?
        // The first registration is held open until the second caller has joined it (deterministic: no timing).
        let first = Task { @MainActor in
            await flight.run("u1") {
                runs += 1
                await withCheckedContinuation { release = $0 }
                return runs
            }
        }
        for _ in 0..<1_000 where release == nil { await Task.yield() }
        XCTAssertTrue(flight.isRunning("u1"))
        let second = Task { @MainActor in await flight.run("u1") { runs += 1; return 99 } }
        for _ in 0..<200 { await Task.yield() }
        release?.resume()
        let one = await first.value
        let two = await second.value
        XCTAssertEqual(runs, 1, "the second awaited the first")
        XCTAssertEqual(one, 1)
        XCTAssertEqual(two, 1, "and got its answer")
        XCTAssertFalse(flight.isRunning("u1"))
        let again = await flight.run("u1") { runs += 1; return runs }
        XCTAssertEqual(again, 2, "a later call runs again")
        // Nothing but CloudSync registers the profile.
        let dir = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().appendingPathComponent("Skipti/App")
        for file in try! FileManager.default.contentsOfDirectory(atPath: dir.path) where file.hasSuffix(".swift") && file != "CloudSync.swift" {
            let text = try! String(contentsOf: dir.appendingPathComponent(file), encoding: .utf8)
            XCTAssertFalse(text.contains("CloudProfile.register("), "\(file) registers beside CloudSync")
        }
    }

    /// L1 (decision 108): the proof launch arguments and surfaces are Debug builds only (kept there: never deleted),
    /// local networking is Debug only, and the dead development signing key is gone.
    func testProofSurfacesAndLocalNetworkingAreDebugOnly() throws {
        let app = try s2Source("SkiptiApp.swift")
        let verification = try s2Slice(app, from: "private var verificationArg: Bool {", to: "\n    }\n")
        s2InOrder(verification, ["#if DEBUG", "\"--two-account-proof\"", "\"--settings-emulator-proof\"", "#else", "return false", "#endif"])
        let root = try s2Slice(app, from: "private var root: some View {", to: "\n    }\n")
        s2InOrder(root, ["#if DEBUG", "AuthEmulatorProofScreen()", "HomeShell()", "#else", "HomeShell()", "#endif"])
        for (file, type) in [("Account.swift", "struct AuthEmulatorProofScreen"), ("Account.swift", "struct SyncEmulatorProofScreen"),
                             ("Account.swift", "struct TwoAccountProofScreen"), ("Account.swift", "struct SettingsEmulatorProofScreen"),
                             ("GroupsScreen.swift", "struct GroupsProofScreen"),
                             ("ExpenseDetailView.swift", "struct ExpenseConflictProofScreen"),
                             ("ImportProofScreen.swift", "struct ImportProofScreen"), ("ExportProofScreen.swift", "struct ExportProofScreen"),
                             ("SmokeScreenView.swift", "struct SmokeScreenView"), ("VaultSmoke.swift", "enum VaultSmoke"),
                             ("SkiptiApp.swift", "private var proofRoot: some View")] {
            XCTAssertTrue(Self.insideDebug(try s2Source(file), type), "\(file): \(type) is Debug only")
        }
        // The helper is not vacuous.
        XCTAssertFalse(Self.insideDebug("struct A {}\n#if DEBUG\nstruct B {}\n#endif\n", "struct A"))
        XCTAssertTrue(Self.insideDebug("struct A {}\n#if DEBUG\nstruct B {}\n#endif\n", "struct B"))
        // This test host is a Debug build: local networking is on here, and only the Debug configuration defines it.
        let ats = try XCTUnwrap(Bundle.main.object(forInfoDictionaryKey: "NSAppTransportSecurity") as? [String: Any])
        XCTAssertEqual(ats["NSAllowsLocalNetworking"] as? Bool, true)
        let ios = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
        let plist = try String(contentsOf: ios.appendingPathComponent("Skipti/Info.plist"), encoding: .utf8)
        s2InOrder(plist, ["#if SKIPTI_LOCAL_NETWORKING", "<key>NSAppTransportSecurity</key>", "<key>NSAllowsLocalNetworking</key>", "#endif"])
        XCTAssertEqual(s2Count(plist, "NSAllowsLocalNetworking"), 1)
        let project = try String(contentsOf: ios.appendingPathComponent("Skipti.xcodeproj/project.pbxproj"), encoding: .utf8)
        let debug = try s2Slice(project, from: "XC0011 /* Debug */ = {", to: "name = Debug;")
        let release = try s2Slice(project, from: "XC0012 /* Release */ = {", to: "name = Release;")
        XCTAssertTrue(debug.contains("INFOPLIST_PREPROCESSOR_DEFINITIONS = \"SKIPTI_LOCAL_NETWORKING=1\";"))
        XCTAssertTrue(debug.contains("INFOPLIST_PREPROCESS = YES;") && release.contains("INFOPLIST_PREPROCESS = YES;"))
        XCTAssertFalse(release.contains("SKIPTI_LOCAL_NETWORKING"), "Release keeps App Transport Security whole")
        let fx = try s2Source("FxRates.swift")
        for gone in ["devSign", "PrivateKey", "import CryptoKit"] { XCTAssertFalse(fx.contains(gone), "FxRates: \(gone)") }
    }

    /// True when the first line containing `needle` lies between a `#if DEBUG` and its `#endif` (no nesting in these files).
    static func insideDebug(_ text: String, _ needle: String) -> Bool {
        var depth = 0
        for line in text.components(separatedBy: "\n") {
            let trimmed = line.trimmingCharacters(in: .whitespaces)
            if trimmed.hasPrefix("#if") { depth += trimmed == "#if DEBUG" ? 1 : 0 }
            if trimmed == "#endif", depth > 0 { depth -= 1 }
            if line.contains(needle) { return depth > 0 }
        }
        return false
    }
}
