import XCTest
@testable import Skipti

/// Which groups' records the phone listens to (CloudSync's groups listener).
/// Found on the emulators (2026-10-14, Android; iOS has the same flaw): the
/// owner's phone creates a group, the groups listing fires at once with this
/// phone's own pending write, and a records listener attached then is refused
/// by the server (the group is not there yet: "Null value error" in isMember).
/// Firestore ends a refused listener for good; the phone ignored the error and
/// kept the dead registration, so it never heard the group's other members
/// again. Twins: GroupListenersTest.kt.
final class GroupListenersTests: XCTestCase {

    private final class Fake {
        var started: [(String, Int)] = []
        var stopped: [Int] = []
        // The registration is the token itself, so a test reads which one stopped.
        lazy var listeners = GroupListeners<Int>(
            start: { [unowned self] id, token in started.append((id, token)); return token },
            stop: { [unowned self] in stopped.append($0) })
    }

    private typealias Listed = GroupListeners<Int>.Listed

    private func ids(_ pairs: [(String, Int)]) -> [String] { pairs.map(\.0) }

    func testAGroupNotYetOnTheServerIsListenedToOnceTheServerHasIt() {
        let f = Fake()
        // The listing carries this phone's own create, not yet on the server.
        f.listeners.update([Listed(id: "g", confirmed: false)])
        XCTAssertTrue(f.started.isEmpty, "no listener before the server holds the group")
        XCTAssertFalse(f.listeners.contains("g"))
        // The server confirmed it.
        f.listeners.update([Listed(id: "g", confirmed: true)])
        XCTAssertEqual(ids(f.started), ["g"])
        XCTAssertTrue(f.listeners.contains("g"))
    }

    func testAPendingChangeToAGroupAlreadyListenedToKeepsItsListener() {
        let f = Fake()
        f.listeners.update([Listed(id: "g", confirmed: true)])
        // A rename from this phone: the listing shows a pending write again.
        f.listeners.update([Listed(id: "g", confirmed: false)])
        XCTAssertEqual(ids(f.started), ["g"])
        XCTAssertTrue(f.stopped.isEmpty)
        XCTAssertTrue(f.listeners.contains("g"))
    }

    func testAListenerThatFailedIsLetGoAndStartedAgainByTheNextListing() {
        let f = Fake()
        f.listeners.update([Listed(id: "g", confirmed: true)])
        let first = f.started[0].1
        f.listeners.failed("g", token: first)
        XCTAssertFalse(f.listeners.contains("g"), "a failed listener is not kept")
        XCTAssertEqual(f.stopped, [first])
        f.listeners.update([Listed(id: "g", confirmed: true)])
        XCTAssertEqual(ids(f.started), ["g", "g"])
        XCTAssertNotEqual(f.started[1].1, first)
        XCTAssertTrue(f.listeners.contains("g"))
    }

    func testALateFailureOfAnOldListenerLeavesTheNewOne() {
        let f = Fake()
        f.listeners.update([Listed(id: "g", confirmed: true)])
        let first = f.started[0].1
        f.listeners.failed("g", token: first)
        f.listeners.update([Listed(id: "g", confirmed: true)])
        // The first registration reports again: the second is untouched.
        f.listeners.failed("g", token: first)
        XCTAssertTrue(f.listeners.contains("g"))
        XCTAssertEqual(f.stopped, [first])
    }

    func testAGroupThatLeftTheListIsLetGo() {
        let f = Fake()
        f.listeners.update([Listed(id: "g", confirmed: true), Listed(id: "h", confirmed: true)])
        let g = f.started[0].1, h = f.started[1].1
        f.listeners.update([Listed(id: "h", confirmed: true)])
        XCTAssertFalse(f.listeners.contains("g"))
        XCTAssertTrue(f.listeners.contains("h"))
        XCTAssertEqual(f.stopped, [g])
        f.listeners.clear()
        XCTAssertEqual(f.stopped, [g, h])
        XCTAssertFalse(f.listeners.contains("h"))
    }
}
