import XCTest
@testable import Skipti

/// §9.2's export files, proven at the platform boundary where §5.15 bit:
/// the shell writes REAL files, and the raw bytes come back before anything
/// else gets to interpret them.
final class ExporterTests: XCTestCase {
    private let csvText = "record_type,date_utc\r\nexpense,2026-01-15\r\n"
    private let jsonText = "{\"format\":\"skipti-ledger\",\"schema_version\":2}"

    func testCsvFileCarriesBomThenExactRfc4180Body() throws {
        let url = try Exporter.write(
            text: csvText, format: .csv, date: Exporter.dateFromStamp("2026-08-26")
        )
        let raw = try Data(contentsOf: url)
        // The whole point, byte by byte: EF BB BF, then the body untouched.
        XCTAssertEqual(Array(raw.prefix(3)), [0xEF, 0xBB, 0xBF])
        XCTAssertEqual(raw.dropFirst(3), Data(csvText.utf8))
        XCTAssertEqual(
            raw,
            Exporter.utf8Bom + Data(csvText.utf8),
            "file bytes == BOM + exact core output"
        )
        XCTAssertEqual(url.lastPathComponent, "skipti-export-2026-08-26.csv")
    }

    func testJsonFileTakesNoBomAndKeepsEnvelopeIdentityFirst() throws {
        let url = try Exporter.write(
            text: jsonText, format: .json, date: Exporter.dateFromStamp("2026-08-26")
        )
        let raw = try Data(contentsOf: url)
        XCTAssertEqual(raw, Data(jsonText.utf8), "JSON is identity bytes, no marker")
        XCTAssertFalse(raw.prefix(3).elementsEqual(Exporter.utf8Bom))
        XCTAssertEqual(url.lastPathComponent, "skipti-export-2026-08-26.json")
    }

    /// Negative: text that ALREADY carries a U+FEFF must not grow a second
    /// BOM: a doubled marker would corrupt the first field Excel reads.
    func testBomCarryingTextIsNotDoubled() {
        let bommed = "\u{FEFF}" + csvText
        let data = Exporter.fileData(for: bommed, format: .csv)
        XCTAssertEqual(data, Exporter.utf8Bom + Data(csvText.utf8))
        XCTAssertEqual(
            data.filter { $0 == 0xEF }.count, 1,
            "exactly one BOM marker in the file"
        )
    }

    /// Negative: the JSON path never grows a BOM even when handed BOM'd
    /// text: its identity is the envelope's first bytes.
    func testJsonPathNeverAddsBom() {
        let data = Exporter.fileData(for: "\u{FEFF}" + jsonText, format: .json)
        XCTAssertFalse(data.prefix(3).elementsEqual(Exporter.utf8Bom))
    }
}
