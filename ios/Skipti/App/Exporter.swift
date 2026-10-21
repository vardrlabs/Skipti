import Foundation

/// §9.2's file writer: the shell half of the export story.
///
/// WHY THE BOM LIVES HERE AND NOWHERE ELSE (incident §5.15): a leading
/// U+FEFF cannot survive the UniFFI→Swift lift at all. Foundation's
/// `String(bytes:encoding:)` consumes it as an encoding signature, so core's
/// `write_csv` is pure RFC 4180 by design. §9.2 still wants "UTF-8 with BOM
/// for Excel", so the FILE gets its BOM at write time, in the shell, where
/// bytes are bytes. The JSON envelope takes NO BOM: its identity is `{"format":
//! "skipti-ledger"…` as the first bytes of the document.
///
/// Filenames follow §9.3's rule (written for PDFs, true for everything that
/// leaves the app): a fixed prefix plus the date, never derived from a group
/// or ledger name: filenames leak into cloud backups and screen shares.
enum Exporter {
    enum Format {
        /// RFC 4180 text from `ffiExportLedgerCsv`. UTF-8 WITH BOM (§9.2).
        case csv
        /// The lossless envelope from `ffiExportLedgerJson`. UTF-8, no BOM.
        case json

        var fileExtension: String {
            switch self {
            case .csv: return "csv"
            case .json: return "json"
            }
        }

        var includesBom: Bool {
            switch self {
            case .csv: return true
            case .json: return false
            }
        }
    }

    static let utf8Bom = Data([0xEF, 0xBB, 0xBF])

    /// The exact bytes a written file must contain. Total and testable:
    /// `assertWrittenBytes` is what the proof screen and the unit suite
    /// both drive, so the shell cannot drift from §5.15's finding.
    static func fileData(for text: String, format: Format) -> Data {
        // A leading U+FEFF in INCOMING text is contamination (a previous
        // export read back as text), never content, for ANY format. JSON's
        // whole identity is its envelope opening bytes; a stray marker there
        // would sit in front of `{"format":…`. Strip one, add one only for
        // the CSV file (§9.2 Excel):
        let stripped = text.hasPrefix("\u{FEFF}") ? text.dropFirst() : text[...]
        let body = Data(stripped.utf8)
        return format.includesBom ? utf8Bom + body : body
    }

    /// Writes the export to a fresh file and returns its URL. The file is
    /// PLAINTEXT (§9.4's warning is the caller's obligation to show first:
    /// this function does not and cannot un-write that fact).
    static func write(text: String, format: Format, date: Date = Date(), justMe: Bool = false) throws -> URL {
        let name = justMe ? justMeName(date, format: format) : "\(filePrefix)\(Self.filenameStamp(date)).\(format.fileExtension)"
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(name)
        try Self.fileData(for: text, format: format).write(to: url, options: .atomic)
        return url
    }

    /// Every export file's name starts with this (`write`).
    static let filePrefix = "skipti-export-"
    /// D-249 (U10): "Export Just me" writes `skipti-just-me-YYYY-MM-DD.csv` (or .json): fixed text and the day it was
    /// made, never a name (SPEC 9.3). Decision 207: the phone's own date (Gregorian, in `timeZone`), not UTC, and the
    /// day, not a month (the file holds every Just me month). Android's twin is `Exporter.justMeName`.
    static let justMePrefix = "skipti-just-me-"

    static func justMeName(_ date: Date, format: Format, timeZone: TimeZone = .current) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.calendar = Calendar(identifier: .gregorian)
        formatter.timeZone = timeZone
        formatter.dateFormat = "yyyy-MM-dd"
        return "\(justMePrefix)\(formatter.string(from: date)).\(format.fileExtension)"
    }

    /// D-235 (C4, iOS M1): the plaintext export files in `directory` (the app's tmp): removed once the share sheet is
    /// done with one, when the export page closes, at launch, and by the erase (whose copy says everything SKIPTI kept
    /// is gone). iOS purges tmp only when it chooses, often days later. Returns how many went; a file that will not go
    /// throws (the erase then says it failed rather than claim the file is gone).
    @discardableResult
    static func removeExports(in directory: URL = FileManager.default.temporaryDirectory) throws -> Int {
        let names = (try? FileManager.default.contentsOfDirectory(atPath: directory.path)) ?? []
        var removed = 0
        for name in names where name.hasPrefix(filePrefix) || name.hasPrefix(justMePrefix) {
            try FileManager.default.removeItem(at: directory.appendingPathComponent(name))
            removed += 1
        }
        return removed
    }

    static func dateFromStamp(_ stamp: String) -> Date {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyy-MM-dd"
        formatter.timeZone = TimeZone(secondsFromGMT: 0)
        return formatter.date(from: stamp) ?? Date()
    }

    /// UTC-fixed both ways (see `dateFromStamp`): a filename must not
    /// depend on which side of a local midnight the device sits on.
    static func filenameStamp(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone(secondsFromGMT: 0)
        formatter.dateFormat = "yyyy-MM-dd"
        return formatter.string(from: date)
    }
}
