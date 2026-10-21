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
    /// D-247 (decision 168, D-221 a): you owe nobody and nobody owes you, but others in the group still owe each other.
    static func youSettled(_ group: String) -> String { "You're settled in \(group)" }
    static let youSettledDetail = "You don't owe anything here."
    /// D-247 (decision 167): a card's other amount, its sheet, and the cap (decision 183: never more than the debt).
    static let anotherAmount = "Another amount"
    static let howMuchPaid = "How much was paid?"
    static let settle = "Settle"
    static let cancel = "Cancel"
    /// When they owe you: "That is more than Ana owes."; when you owe them: "That is more than you owe Ana."
    static func moreThanDebt(youOwe: Bool, other: String) -> String {
        youOwe ? "That is more than you owe \(other)." : "That is more than \(other) owes."
    }
    static func recorded(_ other: String) -> String {
        "Settled with \(other). Recorded in your ledger. No money moved."
    }
}

/// D-182: one group's part of Settle up, framed by that group's own self.
struct SettleSection {
    let named: NamedGroupWire
    let lines: [BalanceLineOut]
    /// D-247 (decision 168): core's settled progress for the whole group (`ffiSettledProgress`), what says whether
    /// everybody is square.
    var progress: [SettledProgressOut] = []
    /// Nobody in the group owes you, and you owe nobody in it.
    var settled: Bool { lines.allSatisfy { $0.entries.isEmpty } }
    var state: SettleState { SettleState.of(youSquare: settled, progress: progress) }
}

/// D-247 (decision 168, D-221 a): what a group's section says. "All settled" only when core says the whole group is;
/// you square while others still owe each other is its own words. Android's twin is `SettleState` in SettleAll.kt.
enum SettleState: Equatable {
    /// You owe someone here, or someone owes you: the cards.
    case open
    /// Core's `allSettled` (every currency's block settled), or a group with nothing in it yet (no block: nobody owes).
    case allSettled
    /// You are square, others are not: "You're settled in {group}".
    case youSettled

    static func of(youSquare: Bool, progress: [SettledProgressOut]) -> SettleState {
        guard youSquare else { return .open }
        return progress.isEmpty || SettledSummary.allSettled(progress) ? .allSettled : .youSettled
    }
}

enum SettleAll {
    /// Every group, each with core's lines for this device's self in it.
    /// The same read the screen makes; Android's twin is `SettleAll.sections`. D-247: `held`, a settle waiting out its
    /// Undo window (`SettleHold`), counts as recorded, so the screen shows what the write will leave (core's sums, never
    /// the shell's).
    static func sections(_ store: LedgerStore, held: [LedgerStore.SettlementDTO] = []) throws -> [SettleSection] {
        // D-210: a read-only copy has nothing to settle in (nothing can be recorded in it).
        let copies = Set(try store.readOnlyChecked().keys)
        let named = try store.namedGroups().filter { !copies.contains($0.group.id) }
        let expenses = try store.loadExpenses().map(\.asInput)
        let settlements = try store.loadSettlements().map(\.asInput) + held.map(\.asInput)
        let redistributions = try store.loadRedistributions()
        let removed = try store.removedPeople()
        return try named.map { n in
            SettleSection(named: n, lines: try ffiBalanceLine(
                expenses: expenses, settlements: settlements, redistributions: redistributions,
                removed: removed[n.group.id]?.map(\.id) ?? [], viewer: n.selfId, group: n.group.id
            ), progress: try ffiSettledProgress(
                expenses: expenses, settlements: settlements, redistributions: redistributions, group: n.group.id))
        }
    }

    /// D-221 (Q17 a), D-247: records one payment: section 5.8's settlement record in the ledger (never a flag, never
    /// money). The ONE append: `SettleHold` writes through it when a settle's Undo window ends (Settle up's cards, their
    /// other amounts and the group page's next card). Core checks it first (`appendSettlement`, decision 103). Android's
    /// twin is `SettleAll.record`.
    static func record(store: LedgerStore, settlement: LedgerStore.SettlementDTO) throws {
        _ = try store.appendSettlement(settlement)
    }

    /// A whole card's debt, written at once (no hold).
    static func record(store: LedgerStore, entry: ShareOut, currency: String, named: NamedGroupWire) throws {
        try record(store: store, settlement: settlement(for: entry, currency: currency, in: named))
    }

    /// D-221 (S1): one card's payment, for the latch: the group, the currency, the other person and the amount (a new
    /// amount with the same person is a new card).
    static func key(named: NamedGroupWire, currency: String, entry: ShareOut) -> String {
        "\(named.group.id)/\(currency)/\(entry.id)/\(entry.minorUnits)"
    }

    /// D-250: a card's identity on the screen: the group, the currency and the other person (never its place in the list,
    /// never its amount: a partial settle keeps the card and rolls the amount). Android's twin is `SettleAll.cardId`.
    static func cardID(group: String, currency: String, entry: ShareOut) -> String {
        "\(group)/\(currency)/\(entry.id)"
    }

    /// D-250: what Settle up shows, for RowsMotion: each group's state and its cards' identities.
    static func shape(_ sections: [SettleSection]) -> [String] {
        sections.flatMap { s in
            ["\(s.named.group.id):\(s.state)"] + s.lines.flatMap { line in
                line.entries.map { cardID(group: s.named.group.id, currency: line.currencyCode, entry: $0) }
            }
        }
    }

    /// Every card's key, as the screen shows them.
    static func keys(_ sections: [SettleSection]) -> [String] {
        sections.flatMap { s in s.lines.flatMap { line in line.entries.map { key(named: s.named, currency: line.currencyCode, entry: $0) } } }
    }

    /// The settlement a card records: between this device's self and the
    /// other person, in the direction the viewer-framed entry says (negative
    /// means you owe them). Android's twin is `SettleUi.settlementFor`. D-247:
    /// `amount`, another amount (`OtherAmount.check` keeps it within the debt);
    /// nil is the whole debt.
    static func settlement(for entry: ShareOut, currency: String, in named: NamedGroupWire,
                           amount: Int64? = nil) -> LedgerStore.SettlementDTO {
        let youOwe = entry.minorUnits < 0
        return LedgerStore.SettlementDTO(
            from: youOwe ? named.selfId : entry.id,
            to: youOwe ? entry.id : named.selfId,
            amountMinor: amount ?? abs(entry.minorUnits),
            currencyCode: currency,
            group: named.group.id
        )
    }

    /// D-247 fix wave (review M3): the card a sheet's other amount is for, as the screen reads it NOW: the same group,
    /// currency and person, owing the same way. Nil when that card is gone (settled meanwhile, or the debt turned the
    /// other way). Android's twin is `SettleAll.liveEntry`.
    static func liveEntry(_ sections: [SettleSection], group: String, currency: String, person: String,
                          youOwe: Bool) -> ShareOut? {
        sections.first { $0.named.group.id == group }?.lines.first { $0.currencyCode == currency }?
            .entries.first { $0.id == person && ($0.minorUnits < 0) == youOwe && $0.minorUnits != 0 }
    }
}

/// D-247 (decisions 167, 183): a card's "Another amount": what the field starts with and what it may hold. Every amount
/// is core's: the field is parsed by `ffiMoneyParseTyped` (D-248: a decimal comma reads) and checked by `ffiCheckSettlement`; the shell only compares
/// two of core's minor-unit numbers (never adds or subtracts them). Android's twin is `OtherAmount` in SettleAll.kt.
enum OtherAmount {
    /// What the field says about itself.
    enum Verdict: Equatable {
        /// Settle stays disabled, with nothing said: empty, zero, or not an amount yet.
        case incomplete
        /// More than the debt: refused in words, Settle disabled (a partial never flips who owes whom).
        case tooMuch(String)
        /// D-248 (U30.2): something typed that is not an amount: `AmountEntry`'s words, said at a Settle tap or once the
        /// field has rested (`wordsPause`), never on the first keystroke (fix wave, review M1); nothing is recorded.
        case notAnAmount(String)
        /// Settle records this many minor units.
        case settles(Int64)
    }

    /// D-248 fix wave (review M1): how long the field rests before its not-an-amount words show (a lone '.' or ',' is
    /// the start of "0,5", not a mistake). Android's twin is `OtherAmount.WORDS_PAUSE_MS`.
    static let wordsPause: Duration = .seconds(1)

    /// The whole debt as the field takes it back (core's entry form in the phone's mark: "450.00", "450,00" on a ','
    /// phone; no symbol, no grouping).
    static func prefill(debt: Int64, currency: String, locale: Locale = .autoupdatingCurrent) -> String {
        AmountEntry.digits(debt, currency: currency, locale: locale) ?? ""
    }

    /// D-247 fix wave (review M3): the cap again at the Settle tap, against the card's LIVE debt (`live`, from
    /// `SettleAll.liveEntry`; nil, the card has gone): a sync that shrank the debt while the sheet was open refuses in
    /// the cap's words, so a partial never flips who owes whom (decision 183). Android's twin is `OtherAmount.atSettle`.
    static func atSettle(_ minor: Int64, live: ShareOut?, youOwe: Bool, other: String) -> Verdict {
        guard let live, minor <= abs(live.minorUnits) else {
            return .tooMuch(SettleAllCopy.moreThanDebt(youOwe: youOwe, other: other))
        }
        return .settles(minor)
    }

    /// `text` against the debt (`debt`, the card's amount, always above 0). `check` is core's settlement check of the
    /// amount (`SettleAll.settlement(..., amount:)`); a refusal reads as incomplete.
    static func verdict(_ text: String, debt: Int64, currency: String, youOwe: Bool, other: String,
                        locale: Locale = .autoupdatingCurrent, check: (Int64) throws -> Void) -> Verdict {
        if AmountEntry.isBlank(text) { return .incomplete }
        guard let minor = AmountEntry.minor(text, currency: currency, locale: locale) else {
            return .notAnAmount(AmountEntry.likeWords(currency: currency, locale: locale))
        }
        guard minor > 0 else { return .incomplete }
        if minor > debt { return .tooMuch(SettleAllCopy.moreThanDebt(youOwe: youOwe, other: other)) }
        guard (try? check(minor)) != nil else { return .incomplete }
        return .settles(minor)
    }
}

/// D-247: the card a sheet's other amount is for (the sheet's item).
struct OtherAmountDraft: Identifiable, Equatable {
    let key: String
    let entry: ShareOut
    let currency: String
    let named: NamedGroupWire
    let other: String
    var id: String { key }
    var youOwe: Bool { entry.minorUnits < 0 }
    var debt: Int64 { abs(entry.minorUnits) }

    static func == (a: OtherAmountDraft, b: OtherAmountDraft) -> Bool { a.key == b.key }
}

/// D-247 (decision 182, calls.md U29.1 route 3): a settle is held for `seconds` before anything is written, so Undo can
/// take it back with nothing to reverse. One at a time. It lands (`commit`) when its time is up, at the next Settle tap
/// (any card, any screen), when Settle up or the group page goes away, or when SKIPTI leaves the front (SkiptiApp's
/// scene phase). While held, the screens count it as recorded (`held`), so the card reads settled at once and a partial
/// leaves its remainder. The write is on the main actor, in one turn with the hold's end, so no read ever counts it
/// twice or not at all. Android's twin is `SettleHold` in SettleAll.kt.
@Observable @MainActor
final class SettleHold {
    static let shared = SettleHold()

    struct Pending: Equatable {
        let id: Int
        let settlement: LedgerStore.SettlementDTO
        let other: String
        let path: String
    }

    /// The settle waiting out its window, if any.
    private(set) var pending: Pending?
    /// Goes up whenever what the screens count changes (held, undone, written, failed): they read again.
    private(set) var revision = 0
    /// The last write that failed, in words (both screens show it: Settle up's error line and the group page's).
    /// D-247 fix wave (I1): only the person's next Settle tap clears it, BEFORE that tap lands the settle already held,
    /// so a failure of that landing is kept and shown; a write that succeeds never clears it.
    private(set) var failure: String?

    @ObservationIgnored private let center: DoneCenter
    /// The window in seconds; nil (the app) reads it per hold from `window(screenReader:)`.
    @ObservationIgnored private let seconds: Double?
    @ObservationIgnored private let screenReader: @MainActor () -> Bool
    @ObservationIgnored private let write: (LedgerStore, LedgerStore.SettlementDTO) throws -> Void
    @ObservationIgnored private let written: @MainActor () -> Void
    @ObservationIgnored private var nextId = 0
    @ObservationIgnored private var timer: Task<Void, Never>?
    @ObservationIgnored private var toastSerial: Int?

    init(center: DoneCenter? = nil, seconds: Double? = nil,
         screenReader: @escaping @MainActor () -> Bool = { UIAccessibility.isVoiceOverRunning },
         write: @escaping (LedgerStore, LedgerStore.SettlementDTO) throws -> Void = { try SettleAll.record(store: $0, settlement: $1) },
         written: @escaping @MainActor () -> Void = SettleHold.liveWritten) {
        self.center = center ?? .shared
        self.seconds = seconds
        self.screenReader = screenReader
        self.write = write
        self.written = written
    }

    /// D-247 fix wave (decision 201): the window with VoiceOver running, long enough to hear the toast and reach its
    /// Undo (WCAG 2.2.1's "enough time").
    nonisolated static let screenReaderSeconds = 12.0

    /// D-247 fix wave (decision 201): how long a settle is held, and its Undo toast shown: `DoneCenter.actionSeconds`
    /// (5), or `screenReaderSeconds` (12) while a screen reader runs. Android's twin is `SettleHold.window`.
    nonisolated static func window(screenReader: Bool) -> Double {
        screenReader ? screenReaderSeconds : DoneCenter.actionSeconds
    }

    /// Section 10.3: saved locally first, then the push (fire and forget), and every screen reads again.
    static func liveWritten() {
        CloudSync.shared.ledgerChanged()
        SyncStatus.shared.changed()
    }

    /// What the screens add to the ledger's settlements while a settle is held.
    var held: [LedgerStore.SettlementDTO] { pending.map { [$0.settlement] } ?? [] }

    /// Holds `settlement` (to `other`, by name) for the window: the write's own check runs first
    /// (`LedgerStore.checkSettlement`: this account's place, then core's rules; CLAUDE.md 10: every write path; a
    /// refusal throws and nothing is held, nothing is landed), a settle already held lands first, then the toast offers
    /// Undo. D-247 fix wave (I1): the old failure clears before that landing, so the landing's own failure stays.
    func hold(_ settlement: LedgerStore.SettlementDTO, other: String, store: LedgerStore) throws {
        try store.checkSettlement(settlement)
        failure = nil
        commit()
        nextId += 1
        let id = nextId
        pending = Pending(id: id, settlement: settlement, other: other, path: store.path)
        revision += 1
        let seconds = self.seconds ?? SettleHold.window(screenReader: screenReader())
        center.post(DoneMoments.line(DoneCopy.settledWith(other), action: DoneCopy.undo, seconds: seconds),
                    onAction: { [weak self] in self?.undo(id) })
        toastSerial = center.serial
        timer?.cancel()
        timer = Task { [weak self] in
            try? await Task.sleep(for: .seconds(seconds))
            guard !Task.isCancelled else { return }
            self?.commit(ifStill: id)
        }
    }

    /// Undo: the held settle numbered `id` is dropped and nothing is written ("Not recorded."). A settle already written
    /// (or a newer one) is left alone.
    func undo(_ id: Int) {
        guard pending?.id == id else { return }
        timer?.cancel()
        timer = nil
        pending = nil
        revision += 1
        center.post(DoneMoments.line(DoneCopy.notRecorded))
    }

    /// The window ended for `id`: it lands, unless it was undone or already landed.
    func commit(ifStill id: Int) {
        guard pending?.id == id else { return }
        commit()
    }

    /// The held settle lands now (written once), and its Undo goes with the toast. Nothing held: nothing happens.
    /// True when a held settle was written now (the removal's Settle first works its payments out again then).
    @discardableResult
    func commit() -> Bool {
        guard let p = pending else { return false }
        timer?.cancel()
        timer = nil
        pending = nil
        if let toastSerial { center.clear(ifStill: toastSerial) }
        toastSerial = nil
        do {
            try write(LedgerStore(path: p.path), p.settlement)
            revision += 1
            written()
            return true
        } catch {
            failure = "Could not record the settlement. \(String(describing: error))"
            revision += 1
            return false
        }
    }
}

extension View {
    /// D-247: a screen that shows held settles (Settle up, a group's page) lands the held one when it goes away: popped
    /// or pushed over (`onDisappear`), or its tab hidden (`artVisible`, tabs stay alive).
    func landsHeldSettle(visible: Bool) -> some View {
        onDisappear { SettleHold.shared.commit() }
            .onChange(of: visible) { _, shown in if !shown { SettleHold.shared.commit() } }
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
    @State private var error: String?
    @State private var loaded = false
    /// D-234 (L7): every group, read-only copies too (Settle up lists only the ones that can be recorded in).
    @State private var groupCount = 0
    /// D-247: the card whose "Another amount" sheet is open (owned here, on the always-present ScrollView).
    @State private var otherAmount: OtherAmountDraft?
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @Environment(\.artVisible) private var artVisible

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
                // D-247: the toast says a settle is done (with its Undo); a write that failed says so here (the group page
                // shows the same line).
                if let error = error ?? SettleHold.shared.failure {
                    Text(verbatim: error).font(TypeRole.body()).foregroundStyle(Tokens.owed)
                        .transition(.reveal)
                }
                if loaded && sections.isEmpty && error == nil {
                    Text(verbatim: SettleAllCopy.empty(groupCount: groupCount)).font(TypeRole.body()).foregroundStyle(Tokens.textDim).glassCard()
                }
                ForEach(sections, id: \.named.group.id) { section in
                    sectionView(section)
                }
            }
            // D-250: a settled card leaves (it fades, the cards below close the gap) and an Undo brings it back; a group
            // turning settled crossfades to its panel; the error line rises in.
            .rowsMotion(SettleAll.shape(sections))
            .reveals(error ?? SettleHold.shared.failure)
            .padding(16)
            .shellScrollTop() // D-205
        }
        .auroraBackground()
        .navigationTitle("Settle up")
        .navigationBarTitleDisplayMode(.large)
        .task(id: SyncStatus.shared.revision) { load() }
        // D-247: a settle held, undone, written or failed changes what the cards count.
        .onChange(of: SettleHold.shared.revision) { load() }
        .landsHeldSettle(visible: artVisible)
        .sheet(item: $otherAmount) { draft in
            OtherAmountSheet(draft: draft, onSettle: { minor in
                // D-247 fix wave (review M3): the cap against the live card, not the one the sheet opened on.
                let live = SettleAll.liveEntry(sections, group: draft.named.group.id, currency: draft.currency,
                                               person: draft.entry.id, youOwe: draft.youOwe)
                switch OtherAmount.atSettle(minor, live: live, youOwe: draft.youOwe, other: draft.other) {
                case .settles(let amount):
                    guard let live else { return nil }
                    otherAmount = nil
                    settle(live, currency: draft.currency, named: draft.named,
                           key: SettleAll.key(named: draft.named, currency: draft.currency, entry: live), amount: amount)
                    return nil
                case .tooMuch(let words), .notAnAmount(let words): return words
                case .incomplete: return nil
                }
            }, onCancel: { otherAmount = nil })
        }
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
        if section.state != .open {
            // D-247 (decision 168): "all settled" only when core says the whole group is.
            let all = section.state == .allSettled
            HStack(spacing: 12) {
                LucideIcon(glyph: .circleCheck, size: 26).foregroundStyle(Tokens.owing)
                VStack(alignment: .leading, spacing: 2) {
                    Text(verbatim: all ? SettleAllCopy.settled(named.group.name) : SettleAllCopy.youSettled(named.group.name))
                        .font(TypeRole.label(15)).foregroundStyle(Tokens.owing)
                    Text(verbatim: all ? SettleAllCopy.settledDetail : SettleAllCopy.youSettledDetail)
                        .font(TypeRole.body(13)).foregroundStyle(Tokens.textDim)
                }
            }
            .padding(14)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Tokens.owingSoft, in: ThemeArt.panelShape(AppearanceState.shared.theme, radius: 18, continuous: true))
            .accessibilityElement(children: .combine)
        } else {
            // D-250: keyed by the card's person and currency (`SettleAll.cardID`), never its place: the card that
            // leaves is the one settled, and a partial keeps its card while the amount rolls.
            let debts = section.lines.flatMap { line in
                line.entries.map { (id: SettleAll.cardID(group: named.group.id, currency: line.currencyCode, entry: $0),
                                    currency: line.currencyCode, entry: $0) }
            }
            ForEach(debts, id: \.id) { debt in
                debtCard(debt.entry, currency: debt.currency, named: named)
                    .transition(.cardLeaves)
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
            // D-250: another amount leaves the remainder: it rolls down.
            .moneyChange(entry.minorUnits)
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
                    settle(entry, currency: currency, named: named, key: key)
                } label: {
                    Text(verbatim: SettleAllCopy.action(youOwe: youOwe, other: other))
                }
                .buttonStyle(PrimaryButtonStyle())
                // D-221: one payment at a time; another card's Settle reads as disabled while one records.
                .disabled(latch.busy)
                .accessibilityHint(Text(verbatim: SettleAllCopy.explainer))
                // D-247 (decision 167): the quiet link under the button, the in-card link's look.
                Button {
                    otherAmount = OtherAmountDraft(key: key, entry: entry, currency: currency, named: named, other: other)
                } label: {
                    Text(verbatim: SettleAllCopy.anotherAmount)
                        .font(TypeRole.body(TypeScale.detail))
                        .foregroundStyle(Tokens.accent)
                        .frame(maxWidth: .infinity, minHeight: 44)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .disabled(latch.busy)
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

    /// D-247 (decision 182): the card's settle, the whole debt or another amount, held for Undo (`SettleHold`, the
    /// one write path; the group page's next card holds through it too). The reload in the same turn counts it.
    private func settle(_ entry: ShareOut, currency: String, named: NamedGroupWire, key: String, amount: Int64? = nil) {
        guard latch.begin(key) else { return }
        // A removed person keeps their name (the history's).
        let other = LedgerStore.label(for: entry.id, in: named,
                                      history: (try? store.historyNames(group: named.group.id)) ?? [:])
        do {
            try SettleHold.shared.hold(SettleAll.settlement(for: entry, currency: currency, in: named, amount: amount),
                                       other: other, store: store)
            error = nil
            load()
        } catch {
            latch.failed()
            self.error = "Could not record the settlement. \(String(describing: error))"
        }
    }

    private func load() {
        do {
            sections = try SettleAll.sections(store, held: SettleHold.shared.held)
            groupCount = try store.namedGroups().count
            // The record landed and the reload no longer shows its card: the latch lets go.
            latch.moved(to: SettleAll.keys(sections))
            #if DEBUG
            if let draft = SettleLook.draft(sections) { otherAmount = draft } // D-247: the look's surface only
            #endif
            error = nil
        } catch LedgerStore.GroupStoreError.needsYourName {
            error = String(localized: "Add your name first.")
        } catch {
            self.error = "Could not load the ledger. \(String(describing: error))"
        }
        loaded = true
    }
}

/// D-247 (decision 167): "How much was paid?": the amount field (focused, the whole debt in core's entry form), section
/// 11.6's sentence, Settle (disabled until the field holds an amount within the debt) and Cancel. Writes nothing itself:
/// Settle hands the amount back to the card, which holds it like any settle. Android's twin is `OtherAmountSheet`.
struct OtherAmountSheet: View {
    let draft: OtherAmountDraft
    /// Settle with this many minor units: nil, taken (the sheet closes); words, refused at the tap (the card's live
    /// debt is less now, D-247 fix wave): shown as the cap's line, the sheet stays.
    let onSettle: (Int64) -> String?
    let onCancel: () -> Void
    @State private var text: String
    @State private var refused: String?
    /// The field has rested since its last change (or Settle was tapped): its not-an-amount words may show.
    @State private var rested = false
    @FocusState private var focused: Bool

    init(draft: OtherAmountDraft, onSettle: @escaping (Int64) -> String?, onCancel: @escaping () -> Void) {
        self.draft = draft
        self.onSettle = onSettle
        self.onCancel = onCancel
        _text = State(initialValue: OtherAmount.prefill(debt: draft.debt, currency: draft.currency))
        #if DEBUG
        if SettleLook.step == "toomuch" { _text = State(initialValue: "40.00") } // D-247: the look's surface only
        #endif
    }

    private var verdict: OtherAmount.Verdict {
        OtherAmount.verdict(text, debt: draft.debt, currency: draft.currency, youOwe: draft.youOwe, other: draft.other) { minor in
            try ffiCheckSettlement(settlement: SettleAll.settlement(
                for: draft.entry, currency: draft.currency, in: draft.named, amount: minor).asInput)
        }
    }

    var body: some View {
        let verdict = self.verdict
        let cap = OtherAmountSheet.capWords(verdict, refused: refused, rested: rested)
        NavigationStack {
            VStack(spacing: 20) {
                VStack(spacing: 10) {
                    Text(verbatim: SettleAllCopy.sentence(youOwe: draft.youOwe, other: draft.other))
                        .font(TypeRole.body(TypeScale.detail))
                        .foregroundStyle(Tokens.textDim)
                    TextField(text: $text, prompt: Text(verbatim: "0").foregroundStyle(Tokens.textFaint)) {
                        Text(verbatim: SettleAllCopy.howMuchPaid)
                    }
                    .keyboardType(.decimalPad)
                    .multilineTextAlignment(.center)
                    .font(TypeRole.numeric(40, weight: 700))
                    .foregroundStyle(Tokens.text)
                    .tint(Tokens.accent)
                    .minimumScaleFactor(0.5)
                    .focused($focused)
                    .accessibilityLabel(Text(verbatim: SettleAllCopy.howMuchPaid))
                    Text(verbatim: draft.currency)
                        .font(TypeRole.label(13))
                        .padding(.horizontal, 12)
                        .padding(.vertical, 4)
                        .themePill(.accent, onPage: false)
                    if let words = cap {
                        Text(verbatim: words).font(TypeRole.body(TypeScale.detail)).foregroundStyle(Tokens.owed)
                            .transition(.reveal)
                    }
                    // Section 11.6, verbatim.
                    Text(verbatim: SettleAllCopy.explainer)
                        .font(TypeRole.body(TypeScale.detail))
                        .foregroundStyle(Tokens.textDim)
                        .multilineTextAlignment(.center)
                        .fixedSize(horizontal: false, vertical: true)
                }
                // D-250: the cap's words rise in.
                .reveals(cap)
                .frame(maxWidth: .infinity)
                .glassCard()
                VStack(spacing: 10) {
                    Button {
                        if case .settles(let minor) = verdict { refused = onSettle(minor) }
                        if case .notAnAmount = verdict { rested = true }
                    } label: { Text(verbatim: SettleAllCopy.settle) }
                        .buttonStyle(PrimaryButtonStyle())
                        .disabled(!OtherAmountSheet.tappable(verdict) || refused != nil)
                    Button(action: onCancel) { Text(verbatim: SettleAllCopy.cancel) }
                        .buttonStyle(SecondaryButtonStyle())
                }
                Spacer(minLength: 0)
            }
            .padding(20)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .auroraBackground()
            .navigationTitle(SettleAllCopy.howMuchPaid)
            .navigationBarTitleDisplayMode(.inline)
        }
        .onAppear { focused = true }
        // A refusal at the tap was about what the field said then.
        .onChange(of: text) { refused = nil }
        // Review M1: the not-an-amount words wait for the field to rest.
        .task(id: text) {
            rested = false
            try? await Task.sleep(for: OtherAmount.wordsPause)
            if !Task.isCancelled { rested = true }
        }
    }

    /// The cap's line: the field's own (more than the debt the sheet opened on; not an amount, once `rested`), else the
    /// tap's refusal.
    static func capWords(_ verdict: OtherAmount.Verdict, refused: String?, rested: Bool) -> String? {
        if case .tooMuch(let words) = verdict { return words }
        if case .notAnAmount(let words) = verdict, rested { return words }
        return refused
    }

    /// Settle can be tapped for an amount it settles, and for one that is not an amount (the tap says why, at once).
    static func tappable(_ verdict: OtherAmount.Verdict) -> Bool {
        if case .notAnAmount = verdict { return true }
        return settles(verdict)
    }

    static func settles(_ verdict: OtherAmount.Verdict) -> Bool {
        if case .settles = verdict { return true }
        return false
    }
}
