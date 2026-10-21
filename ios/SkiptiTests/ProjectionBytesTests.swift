import XCTest
@testable import Skipti

/// D-151: the kv ledger is written in ONE byte form. `rebuildProjection`
/// says two devices holding the same records produce identical projection
/// content; the sync round trip (`SyncE2ETests`) goes further and compares
/// BYTES after a restore. Swift's `JSONEncoder` promises no key order, and on
/// this toolchain the same value encoded twice in one process came out with
/// its keys in different orders ("kv bytes diverged at ledger/expenses", with
/// every value equal). That proof only runs with a relay, so it hid as a skip.
/// These tests need no relay: they rebuild from the record path and compare.
final class ProjectionBytesTests: XCTestCase {
    private var dir: URL!
    private let me = String(repeating: "0", count: 31) + "1"
    private let rahul = String(repeating: "0", count: 31) + "2"
    private let group = String(repeating: "0", count: 31) + "9"

    override func setUpWithError() throws {
        dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("projection-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: dir)
    }

    private func seededStore() throws -> LedgerStore {
        let s = LedgerStore(path: dir.appendingPathComponent("ledger.db").path)
        let base = LedgerStore.ExpenseDTO(
            payer: me, paidMinor: 90_000, currencyCode: "INR", group: group, conversion: nil,
            shares: [.init(id: me, amountMinor: 45_000), .init(id: rahul, amountMinor: 45_000)],
            note: "Dinner", timestampMs: 1_000, category: "food.dining", addedBy: me
        )
        var converted = base
        converted.paidMinor = 5_000
        converted.currencyCode = "USD"
        // D-235 (decision 103; wave A finding 2): core refuses a conversion that changes the amount until FX entry
        // decides which currency the shares are in, and the app no longer writes what core refuses, so the converted
        // fixture is a 1:1 rate (a conversion still travels through every byte this test pins).
        converted.conversion = .init(
            convertedMinor: 5_000, convertedCurrency: "INR",
            rateNum: 1, rateDen: 1, rateTimestampMs: 2_000
        )
        converted.shares = [.init(id: rahul, amountMinor: 5_000)]
        converted.note = "Hotel"
        for (i, e) in [base, converted, base].enumerated() {
            _ = try s.appendExpense(e, nowMs: 5_000 + UInt64(i))
        }
        _ = try s.appendSettlement(
            .init(from: rahul, to: me, amountMinor: 700, currencyCode: "INR", group: group),
            nowMs: 6_000
        )
        return s
    }

    /// The property the round trip proves, without the relay: the ledger
    /// rebuilt from its own records is the same bytes, every time.
    func testRebuildingFromRecordsGivesTheSameBytesEveryTime() throws {
        let s = try seededStore()
        try s.rebuildProjection()
        let expenses = try XCTUnwrap(try s.kvGet(key: LedgerStore.key))
        let settlements = try XCTUnwrap(try s.kvGet(key: LedgerStore.settlementsKey))
        for round in 1...20 {
            try s.rebuildProjection()
            XCTAssertEqual(try s.kvGet(key: LedgerStore.key), expenses, "expenses, rebuild \(round)")
            XCTAssertEqual(try s.kvGet(key: LedgerStore.settlementsKey), settlements, "settlements, rebuild \(round)")
        }
    }

    /// The bytes a write leaves are the bytes a rebuild leaves: adding an
    /// expense and then rebuilding changes nothing on disk.
    func testAnAppendAndARebuildWriteTheSameBytes() throws {
        let s = try seededStore()
        let written = try XCTUnwrap(try s.kvGet(key: LedgerStore.key))
        try s.rebuildProjection()
        XCTAssertEqual(try s.kvGet(key: LedgerStore.key), written)
    }

    /// The one byte form is sorted keys, so it is the same on every run and
    /// every device, whatever the encoder's own order.
    func testTheLedgerIsWrittenWithSortedKeys() throws {
        let s = try seededStore()
        let text = String(decoding: try XCTUnwrap(try s.kvGet(key: LedgerStore.key)), as: UTF8.self)
        let first = try XCTUnwrap(text.range(of: "},{\"addedBy\"").map { String(text[..<$0.lowerBound]) })
        let keys = ["\"addedBy\"", "\"category\"", "\"currencyCode\"", "\"group\"", "\"note\"",
                    "\"paidMinor\"", "\"payer\"", "\"shares\"", "\"timestampMs\""]
        let positions = try keys.map { key in
            try XCTUnwrap(first.range(of: key)?.lowerBound, "\(key) missing from \(first)")
        }
        XCTAssertEqual(positions, positions.sorted(), first)
    }

    // D-192: kept from RecordSyncTests (deleted with the relay sync); the
    // HLC state codec is the record layer Firestore sync builds on.
    /// D-122 #2's codec golden: the persisted HLC state is
    /// `u64BE(millis) ‖ u32BE(counter)` (12 bytes) and the counter lives
    /// in the LAST four. The magnitude is the point: below 2^32 the top
    /// four bytes of millis are ZERO, so the old prefix(4) misread
    /// returned 0 (coincidentally plausible) and NO sub-2^32 fixture
    /// could ever fail. 1_788_134_400_000 (~2026) is where production
    /// actually lives, and there the misread yields a constant 416.
    func testHlcStateCodecRoundTripsAtProductionMagnitude() {
        let millis: UInt64 = 1_788_134_400_000
        let counter: UInt32 = 5
        let state = LedgerStore.Sync.u64BE(millis) + LedgerStore.Sync.u32BE(counter)
        XCTAssertEqual(state.count, 12)
        let decoded = LedgerStore.Sync.decodeHlcState(state)
        XCTAssertEqual(decoded.millis, millis)
        XCTAssertEqual(decoded.counter, counter)
        // And the misread this test exists to forbid, named: the FIRST
        // four bytes of this state decode to 416: the wrong counter the
        // old code silently produced at production magnitude.
        XCTAssertNotEqual(decoded.counter, LedgerStore.Sync.u32FromBE(state.prefix(4)))
    }
}
