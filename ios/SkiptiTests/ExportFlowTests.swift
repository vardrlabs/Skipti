import XCTest
@testable import Skipti

/// §9.4's warning gate, at the model level: the sentence is the SPEC's own
/// word for word, nothing is written before acknowledgement, and the
/// acknowledgement cannot be smuggled in.
final class ExportFlowTests: XCTestCase {
    /// §9.4 VERBATIM: the strongest possible pin on export copy.
    func testWarningMatchesTheSpecWordForWord() {
        XCTAssertEqual(
            ExportFlowModel.warningText,
            "This file is not encrypted. Anything you share it with can read it."
        )
    }

    /// Negative: an unacknowledged session refuses with the typed error and
    /// writes NOTHING: no file exists afterwards.
    func testUnacknowledgedExportRefusesAndWritesNothing() throws {
        let model = ExportFlowModel()
        let snapshot = LedgerSnapshot(groups: [], expenses: [], settlements: [], redistributions: [], profile: nil)

        XCTAssertThrowsError(try model.export(format: .csv, snapshot: snapshot)) {
            XCTAssertEqual($0 as? ExportFlowModel.FlowError, .notAcknowledged)
        }
        XCTAssertNil(model.exportedURL, "no file may exist before acknowledgement")
        XCTAssertTrue(ExportFlowModel.gateHolds(model))
    }

    /// Positive: after the explicit acknowledgement the write happens, and
    /// the file it writes is the §9.2 CSV this project already pins.
    func testAcknowledgedExportWritesTheFile() throws {
        let model = ExportFlowModel()
        let ada = String(repeating: "a", count: 31) + "1"
        var snapshot = LedgerSnapshot(groups: [], expenses: [], settlements: [], redistributions: [], profile: nil)
        snapshot.expenses = [
            PortExpense(
                payer: ada, paidMinor: 1234, currencyCode: "USD",
                group: nil, conversion: nil,
                shares: [ExpenseShare(id: ada, amountMinor: 1234)],
                note: "gate proof", timestampMs: 20_468 * 86_400_000,
                category: nil
            )
        ]

        model.acknowledge()
        let url = try model.export(format: .csv, snapshot: snapshot)
        let raw = try Data(contentsOf: url)
        XCTAssertEqual(Array(raw.prefix(3)), [0xEF, 0xBB, 0xBF], "the §9.2 BOM, via Exporter")
        // The file holds the header AND the expense row (175 bytes here);
        // the pinned shape is the header opening the RFC 4180 body:
        XCTAssertEqual(
            raw.dropFirst(3).prefix(36),
            Data("record_type,date_utc,currency,amount".utf8)
        )
    }

    /// The gate cannot be lowered by acknowledging a DIFFERENT model:
    /// state lives per session, asserted structurally.
    func testFreshModelIsUnacknowledged() {
        let fresh = ExportFlowModel()
        XCTAssertFalse(fresh.acknowledged)
        XCTAssertFalse(ExportFlowModel.gateHolds(ExportFlowModel.mutatingAck()))
    }

    /// The vault→snapshot bridge produces state core accepts and round-
    /// trips: seeded store → snapshot → JSON export → import equals seed.
    func testLedgerStoreSnapshotRoundTripsThroughCore() throws {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("exportflow-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let store = LedgerStore(path: dir.appendingPathComponent("v.db").path)

        // D-135: export no longer seeds a group, so the test stores a real one
        // through the group store. Before D-135 `groups.count == 1` held only
        // because `snapshot()` seeded the fixture "Goa Trip"; the "Trip" group
        // this test created was never stored at all.
        try store.setYourName("Ada")
        let named = try store.createGroup(name: "Trip", settlementCurrency: "INR", people: ["Bob"])
        let group = named.group
        let ada = named.selfId
        let bob = named.members[1].id
        _ = try store.appendExpense(
            .init(
                payer: ada, paidMinor: 420_000, currencyCode: "INR",
                group: group.id, conversion: nil,
                shares: [
                    .init(id: ada, amountMinor: 210_000),
                    .init(id: bob, amountMinor: 210_000),
                ],
                note: "Villa", timestampMs: 20_459 * 86_400_000
            )
        )
        _ = try store.appendSettlement(
            .init(
                from: bob, to: ada, amountMinor: 150_000,
                currencyCode: "INR", group: group.id
            )
        )

        let snapshot = try store.snapshot()
        XCTAssertEqual(snapshot.expenses.count, 1)
        XCTAssertEqual(snapshot.settlements.count, 1)
        XCTAssertEqual(snapshot.groups.map(\.id), [group.id], "the stored group, and nothing seeded")

        // Core accepts it and the lossless envelope agrees:
        let json = try ffiExportLedgerJson(snapshot: snapshot)
        let restored = try ffiImportLedgerJson(text: json)
        XCTAssertEqual(restored, snapshot)
    }

    /// D-135: exporting an empty ledger exports it empty and writes nothing.
    /// Before D-135, `snapshot()` seeded the fixture "Goa Trip" into the vault.
    func testExportingAnEmptyLedgerSeedsNothing() throws {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("export-empty-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        let path = dir.appendingPathComponent("v.db").path
        let snapshot = try LedgerStore(path: path).snapshot()
        XCTAssertEqual(snapshot.groups.count, 0)
        XCTAssertEqual(snapshot.expenses.count, 0)
        let vault = try VaultGate.open(path: path, keystore: KeychainKeystore())
        defer { try? vault.closeVault() }
        XCTAssertNil(try vault.kvGet(key: LedgerStore.groupsKey), "exporting must not write a group")
    }

    /// The --export-proof screen's own check, run by the suite (P1). D-211 bumped
    /// the schema to 4 and left the proof pinning 3: the screen read PROOF FAILED
    /// on its JSON rows while every gate stayed green, because nothing ran
    /// `prove()`. Each row is asserted on its own so a failure names it.
    func testTheExportProofPasses() throws {
        let r = ExportProofScreen.prove()
        XCTAssertTrue(r.csvBomPrefix, "CSV: EF BB BF then the exact RFC 4180 body")
        XCTAssertTrue(r.csvRoundTrips, "CSV re-read parses identically")
        XCTAssertTrue(
            r.jsonNoBom,
            "JSON: no BOM and the envelope prefix. A schema bump must update the prefix literal in ExportProofScreen.prove()"
        )
        XCTAssertTrue(r.jsonRoundTrips, "JSON re-imports byte-identically")
        XCTAssertTrue(r.noDoubleBom, "a double BOM is stripped, never doubled")
        XCTAssertTrue(r.nameShape, "filename: fixed prefix plus date")
        XCTAssertTrue(r.passed, "every row together: Result.passed")
        // D-215 fix wave (P1-m1): both writes use one fixed date, so the name check cannot straddle midnight UTC.
        let screen = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
            .deletingLastPathComponent().appendingPathComponent("Skipti/App/ExportProofScreen.swift")
        let text = try String(contentsOf: screen, encoding: .utf8)
        XCTAssertFalse(text.contains("Date()"), "ExportProofScreen.prove() reads the clock")
        XCTAssertTrue(text.contains("let url = try Exporter.write(text: json, format: .json, date: Exporter.dateFromStamp(fixed))"))
    }

    /// D-235 look F1: the page fills the screen before it draws its ground, so the "Exported." state (three lines and a
    /// button, no Spacer) never sits on a band of the ground with the bare black window above and below it.
    func testTheExportPageFillsTheScreenInBothStates() throws {
        let screen = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
            .deletingLastPathComponent().appendingPathComponent("Skipti/App/ExportFlow.swift")
        let text = try String(contentsOf: screen, encoding: .utf8)
        let body = try XCTUnwrap(text.components(separatedBy: "struct ExportScreen: View {").dropFirst().first?
            .components(separatedBy: "private var warning: some View").first)
        let fill = try XCTUnwrap(body.range(of: ".frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)"),
                                 "the page is framed to fill")
        let ground = try XCTUnwrap(body.range(of: ".auroraBackground()"))
        let states = try XCTUnwrap(body.range(of: "                done\n            }\n        }"))
        XCTAssertLessThan(states.upperBound, fill.lowerBound, "framed after both states")
        XCTAssertLessThan(fill.upperBound, ground.lowerBound, "framed before the ground is drawn")
    }
}

extension ExportFlowModel {
    /// A model whose acknowledgement was already flipped: the state the
    /// structural test must reject.
    static func mutatingAck() -> ExportFlowModel {
        let m = ExportFlowModel()
        m.acknowledge()
        return m
    }
}
