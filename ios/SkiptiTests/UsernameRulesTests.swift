import XCTest
@testable import Skipti

/// C2: the phone refuses a username as the cloud would (the same list, the
/// same goldens, re-derived by .drafts/c2/golden.py from the spec's words).
final class UsernameRulesTests: XCTestCase {
    static let allowed = ["classic", "peacock", "grape", "assam", "method", "prithvi_saha", "kshitij", "nazir",
                          "therapist", "badminton", "pineapple", "spicy", "bobsled", "tunnel", "dallas", "brandi",
                          "sunni_muslim", "team_rocket", "me_and_you", "supportive_sam", "pakistani", "hello", "@Priya_92"]
    static let refused = ["scunthorpe", "f_u_c_k", "fuuuck", "b1tch", "dick69", "support", "supp0rt", "tech_support",
                          "skipti_fan", "x_spic_99", "n4zi", "sh1t", "bullsh1t", "booooobs", "help", "x1488x", "asss",
                          "vardr_labs", "SKIPTI"]
    static let badShape = ["me", "ab", "pri ya", String(repeating: "a", count: 21)]

    func testTheBundledListIsTheCloudsFile() throws {
        let repo = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent().appendingPathComponent("firebase/functions/blocklist/usernames.json")
        let bundled = try XCTUnwrap(Bundle.main.url(forResource: "usernames", withExtension: "json"))
        XCTAssertEqual(try Data(contentsOf: bundled), try Data(contentsOf: repo))
        XCTAssertNotNil(UsernameRules.list)
    }

    func testTheGoldens() throws {
        let list = try XCTUnwrap(UsernameRules.list)
        for ok in Self.allowed { XCTAssertNil(UsernameRules.problem(ok, list: list), ok) }
        for no in Self.refused { XCTAssertEqual(UsernameRules.problem(no, list: list), .unavailable, no) }
        for bad in Self.badShape { XCTAssertEqual(UsernameRules.problem(bad, list: list), .shape, bad) }
    }

    func testATermsLettersMayRepeatButNeverShrink() {
        XCTAssertEqual(UsernameRules.pattern("boobs"), "b+o{2,}b+s+")
        XCTAssertEqual(UsernameRules.pattern("aai_ghal"), "a{2,}i+g+h+a+l+")
    }

    func testTheAccountsUsernameState() {
        XCTAssertNil(UsernameState.from(nil))
        XCTAssertEqual(UsernameState.from([:]), UsernameState.none)
        XCTAssertEqual(UsernameState.from(["username": "al"]), .canChange("al"))
        XCTAssertEqual(UsernameState.from(["username": "al", "usernameChanged": false]), .canChange("al"))
        XCTAssertEqual(UsernameState.from(["username": "al", "usernameChanged": true]), .final("al"))
        XCTAssertEqual(UsernameState.from(["username": ""]), UsernameState.none)
    }

    func testAStaleCheckNeverEnablesSave() {
        // The cloud said "alice" is free, but the field now holds "al".
        XCTAssertNil(UsernameStatus.acceptedHandle(.available("alice"), text: "al"))
        XCTAssertEqual(UsernameStatus.acceptedHandle(.available("alice"), text: "@Alice "), "alice")
        XCTAssertEqual(UsernameStatus.acceptedHandle(.available("alice"), text: "alice"), "alice")
        for status: UsernameStatus in [.checking, .taken("alice"), .hint, .notAvailable, .offline, .limit] {
            XCTAssertNil(UsernameStatus.acceptedHandle(status, text: "alice"), "\(status)")
        }
    }

    func testAKnownUsernameIsNeverForgottenOnAFailedRead() {
        XCTAssertNil(UsernameState.merge(known: .canChange("x"), read: .canChange("x"), signedIn: false))
        XCTAssertNil(UsernameState.merge(known: .canChange("x"), read: nil, signedIn: false))
        XCTAssertEqual(UsernameState.merge(known: .canChange("x"), read: nil, signedIn: true), .canChange("x"))
        XCTAssertEqual(UsernameState.merge(known: UsernameState.none, read: nil, signedIn: true), UsernameState.none)
        XCTAssertEqual(UsernameState.merge(known: nil, read: UsernameState.none, signedIn: true), UsernameState.none)
        XCTAssertEqual(UsernameState.merge(known: UsernameState.none, read: .final("x"), signedIn: true), .final("x"))
        XCTAssertNil(UsernameState.merge(known: nil, read: nil, signedIn: true))
    }

    func testAHandleIsTrimmedOfNewlinesAsTheCloudDoes() {
        XCTAssertEqual(UsernameRules.handle("@Al\n"), "al")
        XCTAssertEqual(UsernameRules.handle("\t al_7 \r\n"), "al_7")
        XCTAssertEqual(UsernameRules.handle(" @Priya "), "priya")
    }

    /// The repository's own file, parsed and compiled here (not the bundle's).
    static func repoList() throws -> UsernameRules.Compiled {
        let url = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent().appendingPathComponent("firebase/functions/blocklist/usernames.json")
        let parsed = try JSONDecoder().decode(UsernameRules.List.self, from: Data(contentsOf: url))
        return try XCTUnwrap(UsernameRules.Compiled(parsed))
    }

    func testTheLocalStatusTakesTheListItIsGiven() throws {
        let list = try Self.repoList()
        XCTAssertEqual(UsernameStatus.local("sh1t", list: list), .notAvailable)
        XCTAssertEqual(UsernameStatus.local("ab", list: list), .hint)
        XCTAssertEqual(UsernameStatus.local("ab"), .hint)
        XCTAssertNil(UsernameStatus.local("bobsled", list: list), "free: only the cloud can say taken")
        // Without a list only the shape is known: the cloud decides the rest.
        XCTAssertNil(UsernameStatus.local("sh1t", list: nil))
    }

    func testTheCompiledListKeepsEveryTerm() throws {
        let url = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent().appendingPathComponent("firebase/functions/blocklist/usernames.json")
        let parsed = try JSONDecoder().decode(UsernameRules.List.self, from: Data(contentsOf: url))
        let compiled = try Self.repoList()
        XCTAssertEqual(compiled.reserved.count, parsed.reserved.count)
        XCTAssertEqual(compiled.anywhere.count, parsed.anywhere.count)
        XCTAssertEqual(compiled.whole.count, parsed.whole.count)
        for no in Self.refused { XCTAssertEqual(UsernameRules.problem(no, list: compiled), .unavailable, no) }
        for ok in Self.allowed { XCTAssertNil(UsernameRules.problem(ok, list: compiled), ok) }
    }

    func testTheChangeScreenNeverOffersTheCurrentHandle() {
        XCTAssertEqual(UsernameRules.changeSuggestions(["prithvi", "prithvisaha", "prithvi_saha"], current: "prithvi"),
                       ["prithvisaha", "prithvi_saha"])
        XCTAssertEqual(UsernameRules.changeSuggestions(["al26", "its_al"], current: "bo"), ["al26", "its_al"])
        XCTAssertEqual(UsernameRules.changeSuggestions([], current: "bo"), [])
    }

    func testTheDailyLimitIsSaidAsTheLimitNotAsOffline() {
        let limit = CloudFunctions.Failure.refused("RESOURCE_EXHAUSTED")
        XCTAssertEqual(UsernameStatus.failedCheck(limit), .limit)
        XCTAssertEqual(UsernameStatus.failedCheck(CloudFunctions.Failure.offline), .offline)
        XCTAssertEqual(UsernameStatus.failedCheck(CloudFunctions.Failure.refused("UNAUTHENTICATED")), .offline)
        XCTAssertEqual(UsernameSaveFailure.from(limit), .message(PeopleCopy.limit))
        XCTAssertEqual(UsernameSaveFailure.from(CloudFunctions.Failure.offline), .message(UsernameCopy.offline))
        XCTAssertEqual(UsernameSaveFailure.from(CloudFunctions.Failure.signedOut), .message(UsernameCopy.offline))
        XCTAssertEqual(UsernameSaveFailure.from(CloudFunctions.Failure.refused("ALREADY_EXISTS")), .taken)
        XCTAssertEqual(UsernameSaveFailure.from(CloudFunctions.Failure.refused("INVALID_ARGUMENT")), .notAvailable)
        XCTAssertEqual(UsernameSaveFailure.from(CloudFunctions.Failure.refused("FAILED_PRECONDITION")), .used)
        XCTAssertNotEqual(PeopleCopy.limit, UsernameCopy.offline)
    }

    func testLaterIsOfferedOnlyAfterASaveFailedForAReasonTheFieldCannotFix() {
        XCTAssertEqual(UsernameCopy.later, "Later")
        XCTAssertFalse(UsernameSaveFailure.offersLater(nil), "never before a save failed")
        XCTAssertFalse(UsernameSaveFailure.offersLater(.taken), "the field says what to do")
        XCTAssertFalse(UsernameSaveFailure.offersLater(.notAvailable), "the field says what to do")
        XCTAssertTrue(UsernameSaveFailure.offersLater(.message(UsernameCopy.offline)))
        XCTAssertTrue(UsernameSaveFailure.offersLater(.message(PeopleCopy.limit)))
        XCTAssertTrue(UsernameSaveFailure.offersLater(.used))
    }

    func testTheStepCanSaveWhenOnlyTheLiveCheckFailed() throws {
        let list = try Self.repoList()
        XCTAssertEqual(UsernameStatus.canSave(status: .available("alice"), text: "@Alice ", list: list), "alice")
        XCTAssertEqual(UsernameStatus.canSave(status: .offline, text: " @Bo_7", list: list), "bo_7")
        XCTAssertEqual(UsernameStatus.canSave(status: .limit, text: "bo_7", list: list), "bo_7")
        XCTAssertNil(UsernameStatus.canSave(status: .offline, text: "sh1t", list: list), "blocked on the phone")
        XCTAssertNil(UsernameStatus.canSave(status: .limit, text: "ab", list: list), "the wrong shape")
        for status: UsernameStatus in [.hint, .checking, .taken("bo_7"), .notAvailable] {
            XCTAssertNil(UsernameStatus.canSave(status: status, text: "bo_7", list: list), "\(status)")
        }
        XCTAssertNil(UsernameStatus.canSave(status: .available("alice"), text: "al", list: list), "an older text's answer")
    }

    func testASignedOutAccountNeverSeesTheStep() {
        XCTAssertEqual(SignInStep.decide(signedIn: false, needed: true), .form)
        XCTAssertEqual(SignInStep.decide(signedIn: false, needed: false), .form)
        XCTAssertEqual(SignInStep.decide(signedIn: false, needed: nil), .form)
        XCTAssertEqual(SignInStep.decide(signedIn: true, needed: nil), .waiting)
        XCTAssertEqual(SignInStep.decide(signedIn: true, needed: true), .step)
        XCTAssertEqual(SignInStep.decide(signedIn: true, needed: false), .confirmation)
    }

    func testOnlyAnAccountWithoutAUsernameGetsTheStep() {
        XCTAssertFalse(UsernameState.needsStep(nil))
        XCTAssertTrue(UsernameState.needsStep(UsernameState.none))
        XCTAssertFalse(UsernameState.needsStep(.canChange("x")))
        XCTAssertFalse(UsernameState.needsStep(.final("x")))
    }
}
