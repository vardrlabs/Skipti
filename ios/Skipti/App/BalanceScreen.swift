import SwiftUI

#if DEBUG
// D-260 (G-13, decision 269): a proof surface (`--balance`, `--balance-vault`) and its fixture, Debug builds only.
/// The fixture ledger shared by the balance and settle screens: the §11.4
/// example PLUS two extra expenses that create a mixed-direction ledger:
/// ada owes Priya ₹520 net (the settle-up direction) while Rahul (₹2,100)
/// and Amit (₹400) owe ada. Real core computation, fixture inputs.
enum Fixtures {
    static let ada = String(repeating: "a", count: 31) + "1"
    static let rahul = String(repeating: "a", count: 31) + "2"
    static let priya = String(repeating: "a", count: 31) + "3"
    static let amit = String(repeating: "a", count: 31) + "4"

    static let names: [String: String] = [
        ada: "You", rahul: "Rahul", priya: "Priya", amit: "Amit",
    ]

    /// Expenses BELONG to the group (§5.3): every fixture record carries
    /// the group id.
    /// The fixture rate is 3 days old on purpose: the staleness indicator
    /// (§5.2/§11.2 WATCH) must be verifiable on screen, not just in tests.
    static func rateTimestampMs(nowMs: UInt64) -> UInt64 {
        nowMs - 3 * 24 * 60 * 60 * 1000
    }

    static func dto(
        groupID: String,
        nowMs: UInt64 = UInt64(Date().timeIntervalSince1970 * 1000)
    ) -> [LedgerStore.ExpenseDTO] {
        [
            // ₹4,280.00 owed to ada (§11.4's example).
            .init(
                payer: ada,
                paidMinor: 428_000,
                currencyCode: "INR",
                group: groupID,
                conversion: nil,
                shares: [
                    .init(id: rahul, amountMinor: 210_000),
                    .init(id: priya, amountMinor: 148_000),
                    .init(id: amit, amountMinor: 70_000),
                ],
                note: "Dinner in Goa",
                timestampMs: nowMs - 2 * 60 * 60 * 1000
            ),
            // Amit covers ada's ₹300 → his net debt drops to ₹400.
            .init(
                payer: amit,
                paidMinor: 30_000,
                currencyCode: "INR",
                group: groupID,
                conversion: nil,
                shares: [.init(id: ada, amountMinor: 30_000)],
                note: "Amit covered the taxi",
                timestampMs: nowMs - 26 * 60 * 60 * 1000
            ),
            // Priya covers ada's ₹2,000 → flips her net: ada owes HER
            // ₹520. This is the settle-up direction.
            .init(
                payer: priya,
                paidMinor: 200_000,
                currencyCode: "INR",
                group: groupID,
                conversion: nil,
                shares: [.init(id: ada, amountMinor: 200_000)],
                note: "Priya covered the hotel",
                timestampMs: nowMs - 2 * 24 * 60 * 60 * 1000
            ),
            // A second CURRENCY: rahul paid $50.00 for ada's share in the
            // group's dollar expense. The balance screen must render one
            // line per currency (§11.4), never filter to one (D-50).
            // Entered in USD, converted AT ENTRY to the group's
            // settlement currency (§5.2): rate 83.25 INR/USD frozen into
            // the record with its own timestamp. $50 → ₹4,162.50.
            .init(
                payer: rahul,
                paidMinor: 5_000,
                currencyCode: "USD",
                group: groupID,
                conversion: .init(
                    convertedMinor: 416_250,
                    convertedCurrency: "INR",
                    rateNum: 8_325,
                    rateDen: 100,
                    rateTimestampMs: rateTimestampMs(nowMs: nowMs)
                ),
                shares: [.init(id: ada, amountMinor: 416_250)],
                note: "Rahul paid the USD hotel",
                timestampMs: nowMs - 3 * 24 * 60 * 60 * 1000
            ),
        ]
    }
}

/// §11.4's balance screen. Two data sources, one rendering path:
///  - `.fixture`: the §11.4 worked example computed directly: the headless
///    verification path behind the `--balance` launch argument.
///  - `.vault`: write the ledger to the skrin vault, CLOSE, REOPEN, read
///    back, and render from what was persisted, the loop closed:
///    vault → core → UI.
///
/// Both compute through `ffiBalanceLine`, whose entries are in the VIEWER's
/// frame of reference (positive = money comes to you), so §11.4's worked
/// example renders +2,100 / +1,480 / +700: D-47's golden, not a negation
/// in a view.
struct BalanceScreen: View {
    enum Source {
        case fixture
        case vault
    }

    let source: Source

    @State private var lines: [BalanceLineOut] = []
    /// D-135: the selected group with names; empty and name-first states.
    @State private var named: NamedGroupWire?
    @State private var noGroups = false
    @State private var needsYourName = false
    @State private var picking = false
    @State private var creating = false
    @State private var askingName = false
    private var group: GroupWire? { named?.group }
    @State private var stalenessDays: Int?
    @State private var loadError: String?
    @State private var loadCompleted = false

    /// D-53 diagnostics: a load that neither completes nor errors is a
    /// silent spinner, indistinguishable from a slow disk. The trail names
    /// the last step reached; the watchdog surfaces it after 10 s.
    final class LoadTrail {
        private let lock = NSLock()
        private var entries: [(UInt64, String)] = []
        private let start = UInt64(Date().timeIntervalSince1970 * 1000)

        func step(_ name: String) {
            let now = UInt64(Date().timeIntervalSince1970 * 1000)
            lock.lock()
            entries.append((now - start, name))
            lock.unlock()
        }

        func dump() -> String {
            lock.lock()
            defer { lock.unlock() }
            return entries.map { "+\($0.0)ms \($0.1)" }.joined(separator: " | ")
        }
    }

    var body: some View {
        Group {
            if source == .vault && needsYourName {
                emptyState(
                    title: String(localized: "Add your name"),
                    detail: String(localized: "This ledger has a group from before names existed. Add your name once; you will see yourself as \"Name (You)\".")
                ) {
                    Button("Add your name") { askingName = true }
                        .buttonStyle(PrimaryButtonStyle())
                }
            } else if source == .vault && noGroups {
                emptyState(
                    title: String(localized: "Nothing here yet"),
                    detail: String(localized: "Create a group, join one you were invited to, or import from another app.")
                ) {
                    Button("Create a group") { creating = true }
                        .buttonStyle(PrimaryButtonStyle())
                    // D-172: joining was only reachable from inside a group
                    // (the founder, 2026-09-26). After joining, the shell
                    // reads the ledger again, as after an import.
                    NavigationLink {
                        JoinPasteScreen(store: LedgerStore.defaultStore()) { _ in
                            NotificationCenter.default.post(name: .skiptiLedgerImported, object: nil)
                        }
                    } label: {
                        Text(verbatim: InviteCopy.joinTitle)
                    }
                    .buttonStyle(SecondaryButtonStyle())
                    // D-166: the sentence above promised an import with
                    // nothing to tap.
                    NavigationLink(ImportCopy.entry) { ImportFlowScreen() }
                        .buttonStyle(SecondaryButtonStyle())
                }
            } else if lines.isEmpty && !loadCompleted {
                Text("…")
                    .font(TypeRole.body())
                    .foregroundStyle(Tokens.textFaint)
                    .background(Tokens.bg)
            } else {
                ScrollView {
                    VStack(alignment: .leading, spacing: 16) {
                        if source == .vault, let named {
                            groupHeader(named)
                            if lines.isEmpty {
                                Text("Nothing here yet. Add your first expense.")
                                    .font(TypeRole.body())
                                    .foregroundStyle(Tokens.textDim)
                            }
                        }
                        // §5.2: a frozen rate older than its 24 h cache
                        // window shows the staleness indicator: the
                        // converted amounts may be off, and the user must
                        // know (§11.6's copy, WATCH colour per §11.2).
                        if let days = stalenessDays {
                            Text(days == 1
                                ? "Rate from 1 day ago\(Separator.meta)converted amounts may be off"
                                : "Rate from \(days) days ago\(Separator.meta)converted amounts may be off")
                                .microLabelFont()
                                .foregroundStyle(Tokens.owed)
                                .accessibilityLabel(
                                    "Rate from \(days == 1 ? "1 day" : "\(days) days") ago. Converted amounts may be off."
                                )
                        }
                        // §11.4: ONE LINE PER CURRENCY, biggest net first.
                        // §5.2: the group's settlement currency is named in
                        // the micro-label, along with the group itself.
                        ForEach(lines, id: \.currencyCode) { line in
                            Text(header(line))
                            .sectionLabel()
                            .foregroundStyle(Tokens.textDim)
                            .accessibilityLabel(spokenHeader(line))

                            BalanceLineView(
                                line: line,
                                names: lineNames,
                                viewerID: viewerID
                            )
                            .padding(.vertical, 2)
                            .glassCard() // D-176
                        }
                    }
                    .padding(16)
                }
                .auroraBackground() // D-176
            }
            if let loadError {
                ContentUnavailableView(
                    "Could not load the ledger",
                    systemImage: "exclamationmark.triangle",
                    description: Text(loadError)
                )
                .auroraBackground() // D-176
            }
        }
        .navigationTitle("Balance")
        // D-178: read again when sync brings records.
        .task(id: SyncStatus.shared.revision) { load() }
        .sheet(isPresented: $picking) {
            NavigationStack { GroupPicker(store: store) { reload() } }
        }
        .sheet(isPresented: $creating) {
            NavigationStack {
                CreateGroupSheet(store: store, needsYourName: (try? store.ensureSelf())?.name == nil) { _ in
                    creating = false
                    reload()
                }
            }
        }
        .sheet(isPresented: $askingName) {
            NavigationStack {
                YourNameSheet(store: store) {
                    askingName = false
                    reload()
                }
            }
        }
    }

    private func load() {
        switch source {
        case .fixture:
            self.lines = Self.computeLines(
                from: Fixtures.dto(groupID: Fixtures.ada).map(\.asInput),
                settlements: [],
                redistributions: [],
                group: Fixtures.ada,
                viewer: Fixtures.ada
            )
        case .vault:
            let trail = LoadTrail()
            trail.step("dispatched")
            DispatchQueue.global(qos: .userInitiated).async {
                do {
                    trail.step("creating store")
                    let dir = FileManager.default
                        .urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
                        .appendingPathComponent("ledger", isDirectory: true)
                    try FileManager.default.createDirectory(
                        at: dir, withIntermediateDirectories: true
                    )
                    let store = LedgerStore(
                        path: dir.appendingPathComponent("ledger-vault.db").path
                    )
                    trail.step("opening groups vault")
                    // D-135: no seeding. No groups shows the empty state; a
                    // migrated vault waiting for your name asks for it.
                    let current: NamedGroupWire?
                    do {
                        current = try store.currentGroup()
                    } catch LedgerStore.GroupStoreError.needsYourName {
                        DispatchQueue.main.async {
                            self.needsYourName = true
                            self.loadCompleted = true
                        }
                        return
                    }
                    trail.step("group loaded (\(current == nil ? "none" : "one"))")
                    let nowMs = UInt64(Date().timeIntervalSince1970 * 1000)
                    trail.step("opening expenses vault")
                    let expenses = try store.loadExpenses()
                        .filter { $0.group == current?.group.id }
                        .map(\.asInput)
                    trail.step("expenses loaded (\(expenses.count))")
                    let oldestRate = expenses.compactMap { $0.conversion?.rateTimestampMs }.min()
                    trail.step("computing lines")
                    // D-135: settlements count on Balance too, as on Android
                    // since D-111. `settlements: []` left a settled debt on
                    // Balance after Settle had removed it.
                    let settlements = try store.loadSettlements().map(\.asInput)
                    let redistributions = try store.loadRedistributions()
                    let removed = try current.map { try store.removedIds(group: $0.group.id) } ?? []
                    let lines = current.map {
                        Self.computeLines(
                            from: expenses, settlements: settlements, redistributions: redistributions,
                            removed: removed, group: $0.group.id, viewer: $0.selfId
                        )
                    } ?? []
                    trail.step("lines computed (\(lines.count))")
                    DispatchQueue.main.async {
                        self.named = current
                        self.noGroups = current == nil
                        self.needsYourName = false
                        self.stalenessDays = oldestRate.map {
                            Int((nowMs - $0) / (24 * 60 * 60 * 1000))
                        }
                        self.lines = lines
                        self.loadCompleted = true
                        trail.step("state set")
                    }
                } catch {
                    DispatchQueue.main.async {
                        trail.step("error: \(error)")
                        self.loadError = "\(error). Trail: \(trail.dump())"
                        self.loadCompleted = true
                    }
                }
            }
            // The watchdog: a load that neither completes nor errors within
            // 10 s surfaces its trail instead of spinning forever.
            DispatchQueue.main.asyncAfter(deadline: .now() + 10) { [weak trail] in
                guard let trail else { return }
                if !loadCompleted {
                    loadError =
                        "Load timed out after 10 s. Trail: \(trail.dump())"
                    loadCompleted = true
                }
            }
        }
    }

    /// D-135: the ledger vault, as the other screens open it.
    private var store: LedgerStore { LedgerStore.defaultStore() }

    private var viewerID: String { source == .fixture ? Fixtures.ada : (named?.selfId ?? "") }

    private var lineNames: [String: String] {
        guard source == .vault, let named else { return Fixtures.names }
        return Dictionary(uniqueKeysWithValues: named.members.map {
            ($0.id, LedgerStore.label(for: $0.id, in: named))
        })
    }

    private func reload() {
        lines = []
        loadCompleted = false
        load()
    }

    /// The selected group; tapping opens the group list (founder decision:
    /// many groups, with a list).
    private func groupHeader(_ named: NamedGroupWire) -> some View {
        Button {
            picking = true
        } label: {
            HStack(spacing: 6) {
                Text(verbatim: named.group.name)
                Image(systemName: "chevron.down")
                    .font(.caption)
            }
        }
        .buttonStyle(SecondaryButtonStyle())
        .accessibilityLabel("Group: \(named.group.name). Change group")
    }

    private func emptyState<Action: View>(
        title: String,
        detail: String,
        @ViewBuilder action: () -> Action
    ) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(verbatim: title)
                .font(TypeRole.body(20))
                .foregroundStyle(Tokens.text)
            Text(verbatim: detail)
                .font(TypeRole.body())
                .foregroundStyle(Tokens.textDim)
            action()
        }
        .padding(16)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .auroraBackground() // D-176
    }

    /// ALL currencies the ledger contains, biggest absolute net first.
    /// D-50: the UI discards nothing. A USD expense renders a USD line.
    private func header(_ line: BalanceLineOut) -> String {
        let name = source == .fixture ? "Goa trip" : (group?.name ?? "")
        let count = source == .fixture ? 4 : (group?.members.count ?? 0)
        return "\(name)\(Separator.meta)\(count == 1 ? "1 member" : "\(count) members")\(Separator.meta)settles in \(line.currencyCode)"
    }

    private func spokenHeader(_ line: BalanceLineOut) -> String {
        let count = source == .fixture ? 4 : (group?.members.count ?? 0)
        let name = source == .fixture ? "Goa trip" : (group?.name ?? "")
        return "\(name), \(count) members, settled in \(line.currencyCode)"
    }

    private static func computeLines(
        from expenses: [ExpenseInput],
        settlements: [SettlementInput],
        redistributions: [RedistributionInput],
        removed: [String] = [],
        group: String?,
        viewer: String
    ) -> [BalanceLineOut] {
        let lines = (try? ffiBalanceLine(
            expenses: expenses,
            settlements: settlements,
            redistributions: redistributions,
            removed: removed, viewer: viewer,
            group: group
        )) ?? []
        return lines.sorted {
            ($0.viewerNetMinor.magnitude, $0.currencyCode)
                > ($1.viewerNetMinor.magnitude, $1.currencyCode)
        }
    }
}
#endif
