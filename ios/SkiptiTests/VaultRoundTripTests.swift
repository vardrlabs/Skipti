import XCTest
@testable import Skipti

/// The smoke as an automated test: same proof as the screen, assertable in
/// CI. Runs on the iOS Simulator (whose Keychain is real); a device run is
/// pending hardware + paid-account access: see BUILD-LOG §4.4.
final class VaultRoundTripTests: XCTestCase {
    func testFullStackSmoke() throws {
        let r = VaultSmoke.run()
        XCTAssertEqual(r.version, skiptiCoreVersion())
        XCTAssertFalse(r.version.isEmpty, "version must cross the FFI")
        XCTAssertTrue(r.recordRoundTrip, "record+kv round-trip failed: \(r.detail)")
        XCTAssertTrue(
            r.wrongKeyRejected,
            "a wrong key must surface WrongKey and never re-key: \(r.detail)"
        )
        XCTAssertTrue(
            r.originalKeyStillOpens,
            "the original key must still open after the wrong-key attempt: \(r.detail)"
        )
        XCTAssertEqual(r.detail, "")
    }
}
