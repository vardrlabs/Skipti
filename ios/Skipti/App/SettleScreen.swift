import SwiftUI

/// §5.8's settle-up screen, where the product's legal boundary lives.
/// SKIPTI records that a debt was settled; it never moves money, never
/// holds funds, never links an account (ADR-0008, §13.2). Every string on
/// this screen passes §11.6's copy rules and §14.6's claims filter; the
/// explainer is §11.6's own sentence, verbatim.
///
/// Amounts come from the FFI (ffiMoneyFormat / ffiMoneySpoken); the debts
/// come from `ffiBalanceLine`: one SECTION per currency the ledger
/// contains (D-50), each settled in its own currency. Recording goes
/// through the straumr record path (LedgerStore.appendSettlement), never
/// a UI-local flag.
struct SettleScreen: View {
    /// `.fixture` computes in memory from the fixture (headless render
    /// checks); `.vault` runs the persisted loop: the path that ships.
    enum Source {
        case fixture
        case vault
    }

    let source: Source

    @State private var lines: [BalanceLineOut] = []
    @State private var settlements: [LedgerStore.SettlementDTO] = []
    /// D-135: the selected group, with names and this device's self.
    @State private var named: NamedGroupWire?
    @State private var expenseInputs: [ExpenseInput] = []
    @State private var redistributions: [RedistributionInput] = []
    /// D-210: the group's removed people (core takes one at zero off the cards).
    @State private var removed: [String] = []
    private var group: GroupWire? { named?.group }
    @State private var stalenessDays: Int?
    @State private var settlingKey: String?
    @State private var confirmation: String?
    @State private var loadError: String?
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    /// D-135: on a real ledger the viewer is this device's self, and names come
    /// from the selected group ("Name (You)" for you). `.fixture` keeps the
    /// fixture ledger for the headless --settle proof, and only there.
    private var viewer: String { source == .fixture ? Fixtures.ada : (named?.selfId ?? "") }

    private func name(_ id: String) -> String {
        if source == .fixture { return Fixtures.names[id] ?? id }
        guard let named else { return String(localized: "Unknown member") }
        return LedgerStore.label(for: id, in: named)
    }

    /// Sentence-position name: the viewer is "you", never "You".
    private func you(_ id: String) -> String {
        id == viewer ? "you" : name(id)
    }

    private func format(_ minor: Int64, code: String) -> String {
        (try? ffiMoneyFormat(minorUnits: minor, currencyCode: code, locale: AppLocale.current)) ?? Placeholder.none
    }

    private func spoken(_ minor: Int64, code: String) -> String {
        (try? ffiMoneySpoken(minorUnits: minor, currencyCode: code, locale: AppLocale.current)) ?? ""
    }

    private var store: LedgerStore {
        let dir = FileManager.default
            .urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("ledger", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return LedgerStore(path: dir.appendingPathComponent("ledger-vault.db").path)
    }

    var body: some View {
        Group {
            if let loadError {
                ContentUnavailableView(
                    "Could not load the ledger",
                    systemImage: "exclamationmark.triangle",
                    description: Text(loadError)
                )
                .auroraBackground() // D-176
            } else {
                ScrollView {
                    VStack(alignment: .leading, spacing: 16) {
                        // §11.6, verbatim: the sentence that keeps SKIPTI a
                        // notebook instead of a regulated entity.
                        Text("Marks this as paid here. It does not move money.")
                            .font(TypeRole.body())
                            .foregroundStyle(Tokens.textDim)
                            .accessibilityLabel(
                                "Marks this as paid here. It does not move money."
                            )

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

                        if let confirmation {
                            Text(confirmation)
                                .font(TypeRole.body())
                                .foregroundStyle(Tokens.owing)
                                .accessibilityLabel(confirmation)
                        }

                        // One section per currency (D-50). §5.2: a group
                        // settles in ITS currency, named in the header.
                        ForEach(lines, id: \.currencyCode) { line in
                            Text("Settle in \(line.currencyCode)\(Separator.meta)\(group?.name ?? "")")
                                .sectionLabel()
                                .foregroundStyle(Tokens.textDim)
                                .padding(.top, 8)
                            ForEach(line.entries, id: \.id) { entry in
                                debtRow(entry, currency: line.currencyCode)
                                    .padding(.vertical, 14)
                                    .padding(.horizontal, 16)
                                    .frame(maxWidth: .infinity, alignment: .leading)
                                    .background(Tokens.surface, in: ThemeArt.panelShape(AppearanceState.shared.theme, radius: 12, continuous: true))
                            }
                            planSection(line)
                        }
                    }
                    .padding(16)
                }
                .auroraBackground() // D-176
            }
        }
        .navigationTitle("Settle up")
        // D-178: read again when sync brings records.
        .task(id: SyncStatus.shared.revision) { load() }
    }

    private func planSection(_ line: BalanceLineOut) -> some View {
        Group {
            if !line.transfers.isEmpty {
                Text("Fastest plan")
                    .sectionLabel()
                    .foregroundStyle(Tokens.textDim)
                    .padding(.top, 8)
                ForEach(Array(line.transfers.enumerated()), id: \.offset) { _, t in
                    // §11.6: sentence case, terse, declarative. The
                    // visible copy and the spoken label say the SAME
                    // words. The uppercase micro-label role belongs to
                    // the section header above.
                    // "pays" vs "pay": the verb agrees with its subject,
                    // and the viewer's subject is "you".
                    let verb = t.from == viewer ? "pay" : "pays"
                    let leg = Text("\(you(t.from)) \(verb) \(you(t.to))")
                        .font(TypeRole.body())
                        .foregroundStyle(Tokens.textDim)
                    let amount = Text(
                        (try? ffiMoneyFormat(
                            minorUnits: t.amountMinor,
                            currencyCode: line.currencyCode,
                            locale: AppLocale.current
                        )) ?? Placeholder.none
                    )
                    .font(TypeRole.numeric())
                    .foregroundStyle(Tokens.text)
                    .lineLimit(1)
                    .minimumScaleFactor(0.5)

                    Group {
                        if dynamicTypeSize.isAccessibilitySize {
                            VStack(alignment: .leading, spacing: 2) { leg; amount }
                        } else {
                            HStack(spacing: 6) { leg; amount }
                        }
                    }
                    .accessibilityElement(children: .ignore)
                    .accessibilityLabel(
                        "\(you(t.from)) pays \(you(t.to)). \(spoken(t.amountMinor, code: line.currencyCode))."
                    )
                }
            }
        }
    }

    @ViewBuilder
    private func debtRow(_ entry: ShareOut, currency: String) -> some View {
        let youOwe = entry.minorUnits < 0
        let amount = abs(entry.minorUnits)
        let other = name(entry.id)
        let isSettling = settlingKey == "\(currency)/\(entry.id)"

        let meta = VStack(alignment: .leading, spacing: 4) {
            Text(youOwe ? "You owe \(other)" : "\(other) owes you")
                .microLabelFont()
                .foregroundStyle(Tokens.textDim)
            Text(format(amount, code: currency))
                .font(TypeRole.numeric(17, weight: 500))
                .foregroundStyle(Tokens.text)
                .lineLimit(1)
                // A number never wraps mid-digit (§11.3): it gets its own
                // full-width line at accessibility sizes instead.
                .minimumScaleFactor(0.5)
                .accessibilityLabel(
                    youOwe
                        ? "You owe \(other). \(spoken(amount, code: currency))."
                        : "\(other) owes you. \(spoken(amount, code: currency))."
                )
        }

        let action = Group {
            if isSettling {
                // §11.6: buttons keep their verb. EACH branch its own
                // progression. Settle -> Settling; Mark received ->
                // Receiving. Never one verb morphing into the other's.
                Text(youOwe ? "Settling…" : "Receiving…")
                    .font(TypeRole.body())
                    .foregroundStyle(Tokens.textDim)
            } else {
                Button(youOwe ? "Settle" : "Mark received") {
                    record(entry, currency: currency)
                }
                .buttonStyle(PrimaryButtonStyle())
                .accessibilityLabel(
                    youOwe
                        ? "Settle debt to \(other). \(spoken(amount, code: currency))."
                        : "Mark received from \(other). \(spoken(amount, code: currency))."
                )
                .accessibilityHint("Marks this as paid here. It does not move money.")
            }
        }

        // §11.7 names this screen as where layouts break: at accessibility
        // sizes the action drops BELOW the amount (full width, no mid-word
        // clipping), never squeezed beside it.
        if dynamicTypeSize.isAccessibilitySize {
            VStack(alignment: .leading, spacing: 12) {
                meta
                action.frame(maxWidth: .infinity)
            }
        } else {
            HStack(alignment: .center) {
                meta
                Spacer(minLength: 12)
                action
            }
        }
    }

    /// §11.6: the button keeps its verb. Settle produces Settling produces
    /// Settled. The settled state is the row LEAVING the plan: the ledger,
    /// recomputed by core, no longer carries the debt.
    private func record(_ entry: ShareOut, currency: String) {
        let key = "\(currency)/\(entry.id)"
        settlingKey = key
        let youOwe = entry.minorUnits < 0
        let amount = abs(entry.minorUnits)
        let settlement = LedgerStore.SettlementDTO(
            from: youOwe ? viewer : entry.id,
            to: youOwe ? entry.id : viewer,
            amountMinor: amount,
            currencyCode: currency,
            group: group?.id
        )
        DispatchQueue.global(qos: .userInitiated).async {
            do {
                let persisted = try store.appendSettlement(settlement)
                // §10.3: fire-and-forget. The settlement is SAVED locally
                // whether or not the relay is reachable.
                CloudSync.shared.ledgerChanged()
                DispatchQueue.main.async {
                    settlements = persisted
                    // D-135: recompute from the ledger that was loaded. This used
                    // the FIXTURES, which made every post-settle line wrong on a
                    // real ledger.
                    let expenses = source == .fixture
                        ? Fixtures.dto(groupID: Fixtures.ada).map(\.asInput)
                        : expenseInputs
                    lines = (try? ffiBalanceLine(
                        expenses: expenses,
                        settlements: settlements.map(\.asInput),
                        redistributions: source == .fixture ? [] : redistributions,
                        removed: source == .fixture ? [] : removed, viewer: viewer,
                        group: group?.id
                    )) ?? []
                    settlingKey = nil
                    confirmation = SettleAllCopy.recorded(name(entry.id))
                }
            } catch {
                DispatchQueue.main.async {
                    settlingKey = nil
                    loadError = "\(error)"
                }
            }
        }
    }

    private func load() {
        if source == .fixture {
            let expenses = Fixtures.dto(groupID: Fixtures.ada).map(\.asInput)
            lines = (try? ffiBalanceLine(
                expenses: expenses,
                settlements: [],
                redistributions: [],
                removed: [], viewer: viewer,
                group: Fixtures.ada
            )) ?? []
            stalenessDays = 3
            return
        }
        DispatchQueue.global(qos: .userInitiated).async {
            do {
                // D-135: no seeding, no fixtures, no debug prints (the two
                // `[settle-debug]` prints logged group ids on every load).
                let current = try store.currentGroup()
                let expenses = try store.loadExpenses().map(\.asInput)
                let loadedSettlements = try store.loadSettlements()
                let loadedRedistributions = try store.loadRedistributions()
                let loadedRemoved = try current.map { try store.removedIds(group: $0.group.id) } ?? []
                let computed: [BalanceLineOut] = current.flatMap { named in
                    try? ffiBalanceLine(
                        expenses: expenses,
                        settlements: loadedSettlements.map(\.asInput),
                        redistributions: loadedRedistributions,
                        removed: loadedRemoved, viewer: named.selfId,
                        group: named.group.id
                    )
                } ?? []
                DispatchQueue.main.async {
                    named = current
                    expenseInputs = expenses
                    settlements = loadedSettlements
                    redistributions = loadedRedistributions
                    removed = loadedRemoved
                    lines = computed
                }
            } catch LedgerStore.GroupStoreError.needsYourName {
                DispatchQueue.main.async {
                    loadError = String(localized: "Add your name on Balance first.")
                }
            } catch {
                DispatchQueue.main.async { loadError = "\(error)" }
            }
        }
    }
}
