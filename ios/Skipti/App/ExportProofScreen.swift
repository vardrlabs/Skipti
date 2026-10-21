import SwiftUI

#if DEBUG
// D-235 (C4, iOS L1, decision 108): a proof surface, Debug builds only.
/// Headless verification (--export-proof): §9.2's export formats written to
/// REAL files by the shell, read back as RAW BYTES, and checked at the
/// platform boundary, where §5.15's lesson bites. NOT a user
/// flow; the user-facing export screen arrives with §9.4's warning.
struct ExportProofScreen: View {
    @State private var result: ExportProofScreen.Result?

    var body: some View {
        List {
            Section("Export file writing: bytes on disk") {
                row("CSV: EF BB BF then exact RFC 4180 body", check(result?.csvBomPrefix))
                row("CSV re-read parses identically (BOM tolerated)", check(result?.csvRoundTrips))
                row("JSON: NO BOM (envelope identity stays first bytes)", check(result?.jsonNoBom))
                row("JSON re-imports byte-identically", check(result?.jsonRoundTrips))
                row("double-BOM handed in is stripped, never doubled", check(result?.noDoubleBom))
                row("filename: fixed prefix + date, no ledger names", check(result?.nameShape))
                if let result {
                    Section {
                        Text(result.passed ? "EXPORT BYTES PROVEN" : "PROOF FAILED")
                            .microLabelFont()
                            .foregroundStyle(result.passed ? Tokens.owing : Tokens.owed)
                    }
                }
            }
        }
        .navigationTitle("Export proof")
        .task { run() }
    }

    private func row(_ label: String, _ ok: Bool?) -> some View {
        HStack {
            Text(label).font(TypeRole.body())
            Spacer()
            Image(systemName: ok == true ? "checkmark.circle.fill" : "xmark.circle")
                .foregroundStyle(ok == true ? Tokens.owing : Tokens.owed)
        }
    }

    private func check(_ ok: Bool?) -> Bool { ok == true }

    private func run() {
        DispatchQueue.global(qos: .userInitiated).async {
            let r = Self.prove()
            DispatchQueue.main.async { result = r }
        }
    }

    struct Result {
        var csvBomPrefix = false
        var csvRoundTrips = false
        var jsonNoBom = false
        var jsonRoundTrips = false
        var noDoubleBom = false
        var nameShape = false

        var passed: Bool {
            csvBomPrefix && csvRoundTrips && jsonNoBom && jsonRoundTrips
                && noDoubleBom && nameShape
        }
    }

    /// A tiny real ledger through the real FFI exports.
    static func sampleSnapshot() -> LedgerSnapshot {
        let ada = String(repeating: "a", count: 31) + "1"
        let expense = PortExpense(
            payer: ada,
            paidMinor: 4550,
            currencyCode: "USD",
            group: nil,
            conversion: nil,
            shares: [ExpenseShare(id: ada, amountMinor: 4550)],
            note: "Dinner, \"seaside\"",
            timestampMs: 20_468 * 86_400_000,
            category: nil,
        )
        return LedgerSnapshot(
            groups: [], expenses: [expense], settlements: [], redistributions: [], profile: nil
        )
    }

    static func prove() -> Result {
        var r = Result()
        let snapshot = sampleSnapshot()
        guard
            let csv = try? ffiExportLedgerCsv(snapshot: snapshot),
            let json = try? ffiExportLedgerJson(snapshot: snapshot)
        else { return r }

        // CSV: BOM prefix then the EXACT RFC 4180 body core produced.
        let csvData = Exporter.fileData(for: csv, format: .csv)
        guard csvData.prefix(3) == Exporter.utf8Bom else { return r }
        guard Data(csv.utf8) == csvData.dropFirst(3) else { return r }
        r.csvBomPrefix = true

        // The written FILE re-reads as text whose leading U+FEFF Foundation
        // will eat: parse_csv tolerates both shapes (D-68), so the file
        // Excel opens and the string the app lifts agree.
        // One fixed date for both writes, so the name check never straddles midnight UTC (D-215 fix wave, P1-m1).
        let fixed = "2026-08-26"
        do {
            let url = try Exporter.write(
                text: csv, format: .csv,
                date: Exporter.dateFromStamp(fixed)
            )
            let raw = try Data(contentsOf: url)
            guard raw == csvData else { return r }
            if let text = String(data: raw, encoding: .utf8) {
                let lifted = text.hasPrefix("\u{FEFF}") ? String(text.dropFirst()) : text
                r.csvRoundTrips = (lifted == csv)
            }
            r.nameShape = url.lastPathComponent == "skipti-export-\(fixed).csv"
        } catch { return r }

        // JSON: identity bytes, no BOM anywhere.
        let jsonData = Exporter.fileData(for: json, format: .json)
        guard jsonData == Data(json.utf8), jsonData.prefix(3) != Exporter.utf8Bom else { return r }
        guard json.hasPrefix("{\"format\":\"skipti-ledger\",\"schema_version\":4,") else { return r }
        r.jsonNoBom = true

        do {
            let url = try Exporter.write(text: json, format: .json, date: Exporter.dateFromStamp(fixed))
            let raw = try Data(contentsOf: url)
            guard raw == jsonData,
                  let restored = try? ffiImportLedgerJson(text: String(decoding: raw, as: UTF8.self)),
                  restored == snapshot
            else { return r }
            r.jsonRoundTrips = true
            r.nameShape = r.nameShape && url.lastPathComponent == "skipti-export-\(fixed).json"
        } catch { return r }

        // A caller handing back BOM'd text (old export re-exported) must
        // never grow a second marker:
        let bommed = "\u{FEFF}" + csv
        let again = Exporter.fileData(for: bommed, format: .csv)
        r.noDoubleBom = again == csvData
        return r
    }

}
#endif
