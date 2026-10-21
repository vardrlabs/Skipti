import SwiftUI

/// D-182: Settle up's words, one place; Android's twin is `SettleAllCopy` in
/// SettleAll.kt. The legal sentence (§11.6) stays verbatim.
enum SettleAllCopy {
    static let intro = "The fewest payments that clear everything."
    static let explainer = "Marks this as paid here. It does not move money."
    static let nothing = "Nothing to settle yet. Add an expense with the + below."
    /// D-234 (L7, decision 87): nothing to settle because every group here is a read-only copy.
    static let nothingHere = "Nothing to settle from this phone. Your groups here can't take new records."

    /// The empty line: no groups at all, or only groups this phone can't record in.
    static func empty(groupCount: Int) -> String { groupCount == 0 ? nothing : nothingHere }
    static let recording = "Recording…"
    static let fastest = "Fastest plan"
    /// "Rahul pays you" / "You pay Ana".
    static func sentence(youOwe: Bool, other: String) -> String {
        youOwe ? "You pay \(other)" : "\(other) pays you"
    }
    /// "Mark as paid" when they pay you; "I paid Ana" when you pay.
    static func action(youOwe: Bool, other: String) -> String {
        youOwe ? "I paid \(other)" : "Mark as paid"
    }
    static func settled(_ group: String) -> String { "\(group) is all settled" }
    static let settledDetail = "Nobody owes anybody. Nice."
    static func recorded(_ other: String) -> String {
        "Settled with \(other). Recorded in your ledger. No money moved."
    }
}

/// D-182: one group's part of Settle up, framed by that group's own self.
struct SettleSection {
    let named: NamedGroupWire
    let lines: [BalanceLineOut]
    /// Nobody in the group owes you, and you owe nobody in it.
    var settled: Bool { lines.allSatisfy { $0.entries.isEmpty } }
}

enum SettleAll {
    /// Every group, each with core's lines for this device's self in it.
    /// The same read the screen makes; Android's twin is `SettleAll.sections`.
    static func sections(_ store: LedgerStore) throws -> [SettleSection] {
        // D-210: a read-only copy has nothing to settle in (nothing can be recorded in it).
        let copies = Set(try store.readOnlyChecked().keys)
        let named = try store.namedGroups().filter { !copies.contains($0.group.id) }
        let expenses = try store.loadExpenses().map(\.asInput)
        let settlements = try store.loadSettlements().map(\.asInput)
        let redistributions = try store.loadRedistributions()
        let removed = try store.removedPeople()
        return try named.map { n in
            SettleSection(named: n, lines: try ffiBalanceLine(
                expenses: expenses, settlements: settlements, redistributions: redistributions,
                removed: removed[n.group.id]?.map(\.id) ?? [], viewer: n.selfId, group: n.group.id
            ))
        }
    }

    /// D-221 (Q17 a): records one payment: section 5.8's settlement record in the ledger (never a flag, never money),
    /// then sync, fire and forget (section 10.3: saved locally first; the push starts on the main actor, where
    /// CloudSync lives). Settle up's cards and the group page's next card both record through this one function, off
    /// the main thread. Android's twin is `SettleAll.record`.
    static func record(store: LedgerStore, entry: ShareOut, currency: String, named: NamedGroupWire) throws {
        _ = try store.appendSettlement(settlement(for: entry, currency: currency, in: named))
        // D-224 (Design 7): one post here covers Settle up's cards and the group's next card. A removed person keeps
        // their name (the history's), and the amount is core's.
        let other = LedgerStore.label(for: entry.id, in: named,
                                      history: (try? store.historyNames(group: named.group.id)) ?? [:])
        let amount = DoneMoments.amount(abs(entry.minorUnits), currency)
        Task { @MainActor in
            CloudSync.shared.ledgerChanged()
            DoneCenter.shared.post(DoneMoments.line(DoneCopy.settledWith(other), amount))
        }
    }

    /// D-221 (S1): one card's payment, for the latch: the group, the currency, the other person and the amount (a new
    /// amount with the same person is a new card).
    static func key(named: NamedGroupWire, currency: String, entry: ShareOut) -> String {
        "\(named.group.id)/\(currency)/\(entry.id)/\(entry.minorUnits)"
    }

    /// Every card's key, as the screen shows them.
    static func keys(_ sections: [SettleSection]) -> [String] {
        sections.flatMap { s in s.lines.flatMap { line in line.entries.map { key(named: s.named, currency: line.currencyCode, entry: $0) } } }
    }

    /// The settlement a card records: between this device's self and the
    /// other person, in the direction the viewer-framed entry says (negative
    /// means you owe them). Android's twin is `SettleUi.settlementFor`.
    static func settlement(for entry: ShareOut, currency: String, in named: NamedGroupWire) -> LedgerStore.SettlementDTO {
        let youOwe = entry.minorUnits < 0
        return LedgerStore.SettlementDTO(
            from: youOwe ? named.selfId : entry.id,
            to: youOwe ? entry.id : named.selfId,
            amountMinor: abs(entry.minorUnits),
            currencyCode: currency,
            group: named.group.id
        )
    }
}

/// D-182: Settle up across every group (the founder's canvas): a section per
/// group, a card per person you owe or who owes you, and "all settled" for a
/// group with nothing left. Recording is §5.8's path, unchanged: a settlement
/// record in the ledger, never a flag, and never money. `SettleScreen` stays
/// for the `--settle` proofs.
struct SettleAllScreen: View {
    @State private var sections: [SettleSection] = []
    /// D-221 (S1): the next card's latch on Settle up's cards: a second tap, on this card or another, records nothing
    /// while one records; the reload that drops the card lets go (iOS reloads in the same main-thread turn).
    @State private var latch = SettleLatch<String>()
    @State private var confirmation: String?
    @State private var error: String?
    @State private var loaded = false
    /// D-234 (L7): every group, read-only copies too (Settle up lists only the ones that can be recorded in).
    @State private var groupCount = 0
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    private var store: LedgerStore { LedgerStore.defaultStore() }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                if TitleOrnament.shows(AppearanceState.shared.theme) { TitleOrnament(above: 16) }
                VStack(alignment: .leading, spacing: 4) {
                    Text(verbatim: SettleAllCopy.intro)
                    // §11.6, verbatim: the sentence that keeps SKIPTI a notebook.
                    Text(verbatim: SettleAllCopy.explainer)
                }
                .font(TypeRole.body(TypeScale.settleIntro))
                .foregroundStyle(Tokens.textDim)
                if let confirmation {
                    Text(verbatim: confirmation).font(TypeRole.body()).foregroundStyle(Tokens.owing)
                }
                if let error {
                    Text(verbatim: error).font(TypeRole.body()).foregroundStyle(Tokens.owed)
                }
                if loaded && sections.isEmpty && error == nil {
                    Text(verbatim: SettleAllCopy.empty(groupCount: groupCount)).font(TypeRole.body()).foregroundStyle(Tokens.textDim).glassCard()
                }
                ForEach(sections, id: \.named.group.id) { section in
                    sectionView(section)
                }
            }
            .padding(16)
            .shellScrollTop() // D-205
        }
        .auroraBackground()
        .navigationTitle("Settle up")
        .navigationBarTitleDisplayMode(.large)
        .task(id: SyncStatus.shared.revision) { load() }
    }

    @ViewBuilder
    private func sectionView(_ section: SettleSection) -> some View {
        let named = section.named
        HStack(spacing: 10) {
            GroupTile(group: named.group, size: 30)
            Text(verbatim: named.group.name).font(TypeRole.name(17)).foregroundStyle(Tokens.text)
        }
        .padding(.top, 6)
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isHeader)
        if section.settled {
            HStack(spacing: 12) {
                LucideIcon(glyph: .circleCheck, size: 26).foregroundStyle(Tokens.owing)
                VStack(alignment: .leading, spacing: 2) {
                    Text(verbatim: SettleAllCopy.settled(named.group.name))
                        .font(TypeRole.label(15)).foregroundStyle(Tokens.owing)
                    Text(verbatim: SettleAllCopy.settledDetail).font(TypeRole.body(13)).foregroundStyle(Tokens.textDim)
                }
            }
            .padding(14)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Tokens.owingSoft, in: ThemeArt.panelShape(AppearanceState.shared.theme, radius: 18, continuous: true))
            .accessibilityElement(children: .combine)
        } else {
            let debts = section.lines.flatMap { line in line.entries.map { (line.currencyCode, $0) } }
            ForEach(Array(debts.enumerated()), id: \.offset) { _, pair in
                debtCard(pair.1, currency: pair.0, named: named)
            }
            plan(section.lines, named: named)
        }
    }

    private func debtCard(_ entry: ShareOut, currency: String, named: NamedGroupWire) -> some View {
        let youOwe = entry.minorUnits < 0
        let other = LedgerStore.label(for: entry.id, in: named)
        let amount = abs(entry.minorUnits)
        let formatted = (try? ffiMoneyFormat(minorUnits: amount, currencyCode: currency, locale: AppLocale.current)) ?? Placeholder.none
        let spoken = (try? ffiMoneySpoken(minorUnits: amount, currencyCode: currency, locale: AppLocale.current)) ?? ""
        let key = SettleAll.key(named: named, currency: currency, entry: entry)
        // D-213 (fix wave 2): the shared avatar, so it follows the theme's person shape.
        let avatar = PersonAvatar(name: other, size: 44)
        let sentence = Text(verbatim: SettleAllCopy.sentence(youOwe: youOwe, other: other))
            .font(TypeRole.body(16)).foregroundStyle(Tokens.text)
        let money = Text(verbatim: formatted)
            .font(TypeRole.numeric(19, weight: 600))
            .foregroundStyle(youOwe ? Tokens.owed : Tokens.owing)
            .lineLimit(1).minimumScaleFactor(0.6)
        return VStack(alignment: .leading, spacing: 14) {
            Group {
                // §11.7: at accessibility sizes the amount gets its own line.
                if dynamicTypeSize.isAccessibilitySize {
                    VStack(alignment: .leading, spacing: 8) { avatar; sentence; money }
                } else {
                    HStack(spacing: 12) { avatar; sentence; Spacer(minLength: 8); money }
                }
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(Text(verbatim: "\(SettleAllCopy.sentence(youOwe: youOwe, other: other)). \(spoken)."))
            if latch.settling(key) {
                Text(verbatim: SettleAllCopy.recording).font(TypeRole.body()).foregroundStyle(Tokens.textDim)
            } else {
                Button {
                    record(entry, currency: currency, named: named, key: key)
                } label: {
                    Text(verbatim: SettleAllCopy.action(youOwe: youOwe, other: other))
                }
                .buttonStyle(PrimaryButtonStyle())
                // D-221: one payment at a time; another card's Settle reads as disabled while one records.
                .disabled(latch.busy)
                .accessibilityHint(Text(verbatim: SettleAllCopy.explainer))
            }
        }
        .glassCard()
    }

    @ViewBuilder
    private func plan(_ lines: [BalanceLineOut], named: NamedGroupWire) -> some View {
        let transfers = lines.flatMap { line in line.transfers.map { (line.currencyCode, $0) } }
        if !transfers.isEmpty {
            VStack(alignment: .leading, spacing: 6) {
                Text(verbatim: SettleAllCopy.fastest).sectionLabel().foregroundStyle(Tokens.textDim)
                ForEach(Array(transfers.enumerated()), id: \.offset) { _, pair in
                    let (currency, t) = pair
                    let from = t.from == named.selfId ? "you" : LedgerStore.label(for: t.from, in: named)
                    let to = t.to == named.selfId ? "you" : LedgerStore.label(for: t.to, in: named)
                    let verb = t.from == named.selfId ? "pay" : "pays"
                    let amount = (try? ffiMoneyFormat(minorUnits: t.amountMinor, currencyCode: currency,
                                                      locale: AppLocale.current)) ?? Placeholder.none
                    Text(verbatim: "\(from.prefix(1).uppercased() + from.dropFirst()) \(verb) \(to) \(amount)")
                        .font(TypeRole.body(14)).foregroundStyle(Tokens.textDim)
                }
            }
            .padding(.horizontal, 4)
        }
    }

    private func record(_ entry: ShareOut, currency: String, named: NamedGroupWire, key: String) {
        guard latch.begin(key) else { return }
        let other = LedgerStore.label(for: entry.id, in: named)
        let store = self.store
        DispatchQueue.global(qos: .userInitiated).async {
            do {
                // D-221: the one record function (the group page's next card records through it too).
                try SettleAll.record(store: store, entry: entry, currency: currency, named: named)
                DispatchQueue.main.async {
                    confirmation = SettleAllCopy.recorded(other)
                    load()
                    SyncStatus.shared.changed()
                }
            } catch {
                DispatchQueue.main.async {
                    latch.failed()
                    self.error = "Could not record the settlement. \(String(describing: error))"
                }
            }
        }
    }

    private func load() {
        do {
            sections = try SettleAll.sections(store)
            groupCount = try store.namedGroups().count
            // The record landed and the reload no longer shows its card: the latch lets go.
            latch.moved(to: SettleAll.keys(sections))
            error = nil
        } catch LedgerStore.GroupStoreError.needsYourName {
            error = String(localized: "Add your name on Balance first.")
        } catch {
            self.error = "Could not load the ledger. \(String(describing: error))"
        }
        loaded = true
    }
}
