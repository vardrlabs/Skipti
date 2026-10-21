import SwiftUI
import UniformTypeIdentifiers

/// The vault→`LedgerSnapshot` bridge the export screen needs: one coherent
/// state assembled from what the app actually persists (D-68's snapshot
/// shape), then handed to the real FFI exports. Nothing here formats money
/// or invents a serializer. Exporter writes what core produced.
extension LedgerStore {
    func snapshot() throws -> LedgerSnapshot {
        // D-135: never seed while exporting; an empty ledger exports empty.
        let groups = try loadGroups().map { g in
            PortGroup(
                id: g.id,
                name: g.name,
                settlementCurrency: g.settlementCurrency,
                members: g.members
            )
        }
        let expenses = try loadExpenses().map { e in
            PortExpense(
                payer: e.payer,
                paidMinor: e.paidMinor,
                currencyCode: e.currencyCode,
                group: e.group,
                conversion: e.conversion.map { c in
                    PortConversion(
                        convertedMinor: c.convertedMinor,
                        convertedCurrency: c.convertedCurrency,
                        rateNum: c.rateNum,
                        rateDen: c.rateDen,
                        rateTimestampMs: c.rateTimestampMs
                    )
                },
                shares: e.shares.map { s in
                    ExpenseShare(id: s.id, amountMinor: s.amountMinor)
                },
                note: e.note,
                timestampMs: e.timestampMs,
                category: e.category,
            )
        }
        let settlements = try loadSettlements().map { s in
            PortSettlement(
                from: s.from,
                to: s.to,
                amountMinor: s.amountMinor,
                currencyCode: s.currencyCode,
                group: s.group
            )
        }
        // D-210: the balances moved off removed people travel in the export.
        let redistributions = try loadRedistributions().map { r in
            PortRedistribution(
                from: r.from, currencyCode: r.currencyCode, group: r.group,
                shares: r.shares.map { ExpenseShare(id: $0.id, amountMinor: $0.amountMinor) }
            )
        }
        // D-211: your name and picture travel in the JSON export (GUARANTEES #4).
        // The records give it; a phone that has not migrated its kept picture
        // yet (Sync.migrateAvatarOnce) has it in kv only. The kv read is
        // strict (a vault that cannot be read fails the export as before).
        let kept = try loadAvatar()
        let picture = Self.exportable(Self.exportedPicture(projected: { try Sync.projectGroups(self).avatar }, kept: kept))
        let name = try loadSelf()?.name
        let parts = picture.recordParts
        let profile = (name == nil && picture == .none)
            ? nil : PortProfile(name: name, emoji: parts.emoji, photoHex: parts.photoHex)
        return LedgerSnapshot(
            groups: groups, expenses: expenses, settlements: settlements, redistributions: redistributions,
            profile: profile
        )
    }

    /// D-211: the picture the export carries: the records' when they give
    /// one, else the kv copy. A projection that fails never fails the export
    /// (GUARANTEES #4: everything else still goes out): the kv copy, which
    /// the last good rebuild wrote, stands in for it.
    static func exportedPicture(projected: () throws -> GroupPictureOut?, kept: Avatar) -> Avatar {
        do {
            if let recorded = try projected() { return Avatar(emoji: recorded.emoji, photoHex: recorded.photoHex) }
        } catch {}
        return kept
    }

    /// The picture as the export carries it: one core would refuse (an old
    /// kept photo over 64,000 bytes, or not a JPEG, before the migration made
    /// it a record) is left out, so the file is still written with everything
    /// else (final review M1, GUARANTEES #4). Core's own check, the one the
    /// export runs.
    static func exportable(_ picture: Avatar) -> Avatar {
        let parts = picture.recordParts
        return (try? ffiEncodeGroupOp(op: .avatar(emoji: parts.emoji, photoHex: parts.photoHex))) == nil ? .none : picture
    }

    /// D-211: the picture of an imported file, written as a record only when
    /// the file has one and this phone has none (an import never replaces the
    /// picture already here). Returns true when it wrote one. The name is the
    /// phone's own and is not imported. A picture core refuses (not a JPEG,
    /// over 64,000 bytes) is skipped: the rest of the import stands.
    @discardableResult
    func importProfile(_ profile: PortProfile?) throws -> Bool {
        guard let profile, profile.emoji != nil || profile.photoHex != nil else { return false }
        // A picture kept before pictures were records counts as "has one".
        try Sync.migrateAvatarOnce(self)
        guard try Sync.projectGroups(self).avatar == nil else { return false }
        do {
            try Sync.appendGroupOps(self, [.avatar(emoji: profile.emoji, photoHex: profile.photoHex)], placing: .none)
        } catch {
            return false
        }
        try Sync.rebuildGroups(self)
        return true
    }

    /// D-210: one group's slice of the ledger, by core (a removed person's
    /// read-only copy exports only that group).
    func snapshot(group: String) throws -> LedgerSnapshot {
        ffiSnapshotForGroup(snapshot: try snapshot(), group: group)
    }
}

/// §9.4's gate: export writes PLAINTEXT finances outside every guarantee
/// the app makes, so the warning comes FIRST and the write refuses to run
/// without an explicit acknowledgement. The sentence is §9.4's own, word
/// for word. §11.6's register is terse and never understates either.
@Observable
final class ExportFlowModel {
    /// VERBATIM from SPEC §9.4. Pinned by test; changing this string is a
    /// spec change, not a copy edit.
    static let warningText =
        "This file is not encrypted. Anything you share it with can read it."

    enum Format {
        case csv
        case json

        var exporterFormat: Exporter.Format {
            switch self {
            case .csv: return .csv
            case .json: return .json
            }
        }
    }

    enum FlowError: Error, Equatable {
        /// The negative case, pinned: NO file may be written before the
        /// warning has been acknowledged in this session.
        case notAcknowledged
    }

    private(set) var acknowledged = false
    private(set) var exportedURL: URL?
    private(set) var failureText: String?

    /// The warning's only exit: seeing it and choosing to continue.
    func acknowledge() {
        acknowledged = true
    }

    /// The write. Structurally refuses an unacknowledged session: there is
    /// no parameter, default, or flag that lowers this gate.
    @discardableResult
    func export(format: Format, snapshot: LedgerSnapshot) throws -> URL {
        guard acknowledged else { throw FlowError.notAcknowledged }
        do {
            let text: String
            switch format {
            case .csv: text = try ffiExportLedgerCsv(snapshot: snapshot)
            case .json: text = try ffiExportLedgerJson(snapshot: snapshot)
            }
            let url = try Exporter.write(text: text, format: format.exporterFormat)
            exportedURL = url
            failureText = nil
            return url
        } catch {
            failureText = "Export failed: \(error)"
            throw error
        }
    }

    /// Proof/test seam: an UNacknowledged model must refuse with the typed
    /// error and leave nothing written. Any other outcome fails the check.
    static func gateHolds(_ model: ExportFlowModel) -> Bool {
        if model.acknowledged { return false } // gate pre-opened: broken
        do {
            _ = try model.export(
                format: .csv,
                snapshot: LedgerSnapshot(groups: [], expenses: [], settlements: [], redistributions: [], profile: nil)
            )
            return false // wrote without acknowledgement: gate broken
        } catch FlowError.notAcknowledged {
            return model.exportedURL == nil
        } catch {
            return false // wrong failure mode: the gate is not what refused
        }
    }
}

/// The user-facing export screen. Warning step is the WHOLE first screen.
/// §9.4 gives it the space, not a footnote.
struct ExportScreen: View {
    let store: LedgerStore
    /// D-210: one group's export (a read-only copy exports only that group);
    /// nil is the whole ledger.
    var group: String? = nil
    @State private var model = ExportFlowModel()
    @State private var snapshot: LedgerSnapshot?
    @State private var loadFailed = false
    /// D-144: an EMPTY ledger is not an unreadable one. It used to share
    /// `loadFailed`, so a new person's first look at Export said "The ledger
    /// could not be read."
    @State private var isEmpty = false
    @State private var shareURL: URL?
    /// D-235 (C4, iOS M1): the format last written, so Share… writes the file again after the sheet removed it.
    @State private var lastFormat: ExportFlowModel.Format?

    var body: some View {
        Group {
            if model.exportedURL == nil && shareURL == nil {
                warning
            } else {
                done
            }
        }
        // D-235 look F1: the page fills the screen in both states. `done` hugs its three lines, so the ground was drawn
        // only behind them, a band in the middle of a black page (since D-176); `warning` filled only through its Spacer.
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .auroraBackground() // D-176
        // D-217: the screen titles itself, so every entry has it (from Delete account and a copy's export the bar
        // used to be untitled).
        .navigationTitle("Export")
        .navigationBarTitleDisplayMode(.inline)
        // D-235 (C4, iOS M1): and when the page closes, whatever the sheet did.
        .onDisappear { _ = try? Exporter.removeExports() }
        .sheet(item: $shareURL) { url in
            // D-235 (C4, iOS M1): the plaintext file leaves tmp once the sheet is done with it.
            ActivityShareSheet(url: url, onFinish: { ExportFiles.done(with: url) })
                // The app never routes the kit or an export into Photos
                // itself (§3.4/§9.4); the sheet excludes the Photos target
                // outright so the easiest path is also not offered.
                .presentationDetents([.medium, .large])
        }
    }

    private var warning: some View {
        VStack(alignment: .leading, spacing: 24) {
            Text("Export")
                .sectionLabel()
                .foregroundStyle(Tokens.textDim)

            Text(ExportFlowModel.warningText)
                .font(TypeRole.body())
                .foregroundStyle(Tokens.text)
                .accessibilityLabel(ExportFlowModel.warningText)

            if group == nil {
                Text("Your whole ledger leaves the encrypted store in one file.")
                    .font(TypeRole.body())
                    .foregroundStyle(Tokens.textDim)
            }

            if loadFailed {
                Text("The ledger could not be read.")
                    .font(TypeRole.body())
                    .foregroundStyle(Tokens.owed)
            } else if isEmpty {
                Text("Nothing to export yet: no expenses or settlements.")
                    .font(TypeRole.body())
                    .foregroundStyle(Tokens.textDim)
            }

            Spacer()

            HStack(spacing: 12) {
                Button("CSV") {
                    export(.csv)
                }
                .buttonStyle(PrimaryButtonStyle())
                .disabled(snapshot == nil)
                .accessibilityHint(
                    "Writes \(ExportFlowModel.warningText)"
                )

                Button("JSON backup") {
                    export(.json)
                }
                .buttonStyle(PrimaryButtonStyle())
                .disabled(snapshot == nil)
                .accessibilityHint(
                    "Writes \(ExportFlowModel.warningText)"
                )
            }

            if let failure = model.failureText {
                Text(failure)
                    .font(TypeRole.body())
                    .foregroundStyle(Tokens.owed)
            }
        }
        .padding(16)
        .task { loadSnapshot() }
    }

    private var done: some View {
        VStack(alignment: .leading, spacing: 20) {
            Text("Exported.")
                .font(TypeRole.body())
                .foregroundStyle(Tokens.text)
            if let url = model.exportedURL {
                Text(url.lastPathComponent)
                    .font(TypeRole.numeric(13))
                    .foregroundStyle(Tokens.textDim)
                Button("Share…") { shareAgain(url) }
                    .buttonStyle(SecondaryButtonStyle())
            }
        }
        .padding(16)
    }

    /// The file went when the last share finished: written again (same name, same bytes) before the sheet opens.
    private func shareAgain(_ url: URL) {
        if FileManager.default.fileExists(atPath: url.path) { shareURL = url } else if let lastFormat { export(lastFormat) }
    }

    private func export(_ format: ExportFlowModel.Format) {
        guard let snapshot else { return }
        lastFormat = format
        model.acknowledge()
        do {
            let url = try model.export(format: format, snapshot: snapshot)
            shareURL = url
            // D-224 (Design 7): the page shows the file; only the success haptic, through the app's gate.
            DoneCenter.shared.success()
        } catch {
            // failureText already carries the message; stay on the warning.
        }
    }

    private func loadSnapshot() {
        DispatchQueue.global(qos: .userInitiated).async {
            do {
                let s = try group.map { try store.snapshot(group: $0) } ?? store.snapshot()
                DispatchQueue.main.async {
                    snapshot = s
                    isEmpty = s.expenses.isEmpty && s.settlements.isEmpty && s.redistributions.isEmpty
                }
            } catch {
                DispatchQueue.main.async { loadFailed = true }
            }
        }
    }
}

/// D-235 (C4, iOS M1): what happens to an export file once the share sheet is done with it.
enum ExportFiles {
    static func done(with url: URL) {
        try? FileManager.default.removeItem(at: url)
    }
}

extension URL: Identifiable {
    public var id: String { absoluteString }
}

/// UIActivityViewController bridged for the share step. Photos is EXCLUDED:
/// §3.4's "never screenshotted into a photo library by the app itself"
/// applies to exports a fortiori: the app offers no route that lands
/// plaintext finances in the camera roll.
struct ActivityShareSheet: UIViewControllerRepresentable {
    let url: URL
    /// D-235 (C4, iOS M1): the sheet is done with the file (shared, or closed without sharing).
    var onFinish: () -> Void = {}

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(
            activityItems: [url],
            applicationActivities: nil
        ).then {
            $0.excludedActivityTypes = [.saveToCameraRoll]
            $0.completionWithItemsHandler = { _, _, _, _ in onFinish() }
        }
    }

    func updateUIViewController(_ vc: UIActivityViewController, context: Context) {}
}

private extension UIActivityViewController {
    func then(_ apply: (UIActivityViewController) -> Void) -> UIActivityViewController {
        apply(self)
        return self
    }
}
