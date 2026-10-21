import SwiftUI

/// D-221 (E4): the group page's Settled card, its next card, its milestones and the celebration. Core measures the
/// money (`ffiSettledProgress`); these only order, pick and decide. Android's twin is Settled.kt; its SettledTest
/// reads this file's copy.
enum SettledCopy {
    static let label = "Settled"
    static func stillToSettle(_ amount: String) -> String { "\(amount) still to settle" }
    static let nothingLeft = "Nothing left to settle"
    static let hundred = "100 expenses together"
    static let year = "A year together"
    static func since(_ date: String) -> String { "Since \(date). Rare, and only once." }
    /// D-247 (decision 168): the fewest-payments plan's length, "1 payment to go" at one.
    static func paymentsToGo(_ n: Int) -> String { n == 1 ? "1 payment to go" : "\(n) payments to go" }
    static let settle = "Settle"
    static let settling = "Settling"
}

enum SettledSummary {
    /// One block per currency: the group's settlement currency first, then by code. No FX: amounts in different
    /// currencies are never added.
    static func blocks(_ progress: [SettledProgressOut], settlementCurrency: String?) -> [SettledProgressOut] {
        progress.sorted { a, b in
            let (aFirst, bFirst) = (a.currencyCode == settlementCurrency, b.currencyCode == settlementCurrency)
            if aFirst != bFirst { return aFirst }
            return a.currencyCode < b.currencyCode
        }
    }

    /// A group is all settled when core returned at least one block and every block is settled.
    static func allSettled(_ progress: [SettledProgressOut]) -> Bool {
        !progress.isEmpty && progress.allSatisfy(\.settled)
    }

    /// The whole percent, floored (not money: the shell prints it with "%").
    static func percent(_ basisPoints: UInt32) -> Int { Int(basisPoints / 100) }
}

/// The group's next payment for the viewer.
enum GroupNext {
    struct Pick: Equatable {
        let currencyCode: String
        let entry: ShareOut
        /// The viewer pays (entries are viewer-framed: negative means you owe).
        var youOwe: Bool { entry.minorUnits < 0 }
    }

    /// The viewer's largest open entry across the group's lines (by size; ties by currency code, then id).
    static func pick(lines: [BalanceLineOut]) -> Pick? {
        let open = lines.flatMap { line in
            line.entries.filter { $0.minorUnits != 0 }.map { Pick(currencyCode: line.currencyCode, entry: $0) }
        }
        return open.min { a, b in
            let (ma, mb) = (a.entry.minorUnits.magnitude, b.entry.minorUnits.magnitude)
            if ma != mb { return ma > mb }
            if a.currencyCode != b.currencyCode { return a.currencyCode < b.currencyCode }
            return a.entry.id < b.entry.id
        }
    }

    /// D-247 (decision 168, D-221 b; overturns D-221 S2's pairwise count): the payments still to go are the
    /// fewest-payments plan Settle up shows (core's `transfers`, section 5.8's plan), one list per currency. Counting the
    /// plan's items is not money arithmetic: every amount stays core's. Android's `GroupNext.planCount` is the same.
    static func planCount(lines: [BalanceLineOut]) -> Int { lines.flatMap(\.transfers).count }

    /// The muted line under the sentence: "1 payment to go", or "N payments to go".
    static func remainingLine(_ n: Int) -> String { SettledCopy.paymentsToGo(n) }
}

/// D-221 (S4, SPEC 11.7: explicit accessibility labels on every money view): what VoiceOver reads for a Settled block
/// and the next card's words, the money in core's spoken form (`ffiMoneySpoken`), never the printed string. Kept out of
/// `SettledCopy`, whose strings Android reads; Android's twin is `SettledSpeech` in Settled.kt.
enum SettledSpoken {
    /// "Settled, 26%, two thousand three hundred and sixty rupees still to settle" (the currency code heads it when
    /// the card has more than one block). Android's `SettledSpeech.block` reads the same.
    static func block(head: String, percent: Int, left: String) -> String { "\(head), \(percent)%, \(left)" }

    /// "Kiran pays you nine hundred rupees. 3 payments to go. Marks this as paid here. It does not move money."
    static func next(sentence: String, amount: String, remaining: String) -> String {
        "\(sentence) \(amount). \(remaining). \(SettleAllCopy.explainer)"
    }
}

/// The spec's two milestones, never about money.
enum Milestone: Equatable {
    case hundred, year
}

/// One live expense of the group, as the milestones read it: when it was added, and its own date
/// (`timestampMs ?? addedAtMs`).
struct MilestoneExpense: Equatable {
    let addedAtMs: UInt64
    let dateMs: UInt64
}

enum Milestones {
    static let dayMs: UInt64 = 86_400_000
    /// A milestone shows for 14 days from the moment it was reached (end exclusive), never before.
    static let windowMs: UInt64 = 14 * dayMs
    static let yearMs: UInt64 = 365 * dayMs
    static let hundredAt = 100

    struct Shown: Equatable {
        let milestone: Milestone
        let reachedMs: UInt64
        /// The first expense's own date (the card's "Since 2 Mar 2026").
        let sinceMs: UInt64
    }

    /// The milestone the group page shows now: the hundredth expense added, or a year since the first expense's own
    /// date; when both show, the later reached wins (goldens: .drafts/d221/milestones.out).
    static func current(_ expenses: [MilestoneExpense], now: UInt64) -> Shown? {
        guard let since = expenses.map(\.dateMs).min() else { return nil }
        var reached: [(Milestone, UInt64)] = []
        let added = expenses.map(\.addedAtMs).sorted()
        if added.count >= hundredAt { reached.append((.hundred, added[hundredAt - 1])) }
        reached.append((.year, since + yearMs))
        // The first reached at the latest moment (`max` keeps the first of equals, as the loop it replaces did).
        let best = reached.filter { $0.1 <= now && now < $0.1 + windowMs }.max { $0.1 < $1.1 }
        return best.map { Shown(milestone: $0.0, reachedMs: $0.1, sinceMs: since) }
    }
}

enum Celebration {
    /// Plays when a group the page has already loaded goes from not all settled to all settled; never on the first
    /// load (`before` nil) and never for a read-only copy.
    static func plays(before: Bool?, after: Bool, isCopy: Bool) -> Bool {
        before == false && after && !isCopy
    }

    /// The particles fly only when motion is allowed (Reduce Motion or Less motion keeps the dim and the words). D-239
    /// (decision 137): the celebration is a moment, so power saving never stills it.
    static func showsBurst(reduceMotion: Bool) -> Bool { !reduceMotion }

    /// D-221 (S8): whether a play waits for the page after a load: a new play when `plays`; a waiting one is kept only
    /// while the group is still all settled and not a copy (a later load that finds it unsettled clears it).
    static func pending(was: Bool, before: Bool?, after: Bool, isCopy: Bool) -> Bool {
        plays(before: before, after: after, isCopy: isCopy) || (was && after && !isCopy)
    }
}

/// D-221 (S8, the celebration twin decisions): the shell's memory of each group's settled state as its page last saw
/// it, keyed by group id, in memory only. A group settled while its page was off screen (Settle up, another phone)
/// plays when the page is next on screen, a page popped and opened again included; after a relaunch nothing is
/// remembered, so nothing plays on a first load. Read and written on the main thread (the page's load). Android's
/// twin is the per-group map in AppRoot.kt.
final class SettledMemory: @unchecked Sendable {
    static let shared = SettledMemory()
    private var seen: [String: Bool] = [:]

    /// Remembers `settled` for `group` and returns what was remembered before (nil the first time this run).
    func see(_ group: String, settled: Bool) -> Bool? {
        defer { seen[group] = settled }
        return seen[group]
    }
}


/// D-221 (L44): the seal's number and the milestone's title. Kept out of `SettledCopy`, whose strings Android reads.
enum MilestoneWords {
    static func number(_ m: Milestone) -> String {
        switch m {
        case .hundred: return "100"
        case .year: return "1"
        }
    }

    static func title(_ m: Milestone) -> String {
        switch m {
        case .hundred: return SettledCopy.hundred
        case .year: return SettledCopy.year
        }
    }
}

/// D-221 (the Task 7 review, S1): the latch on a Settle button, the next card's (keyed by its pick) and Settle up's
/// (keyed by `SettleAll.key`). A tap holds the payment it records; "Settling" shows, and every other tap records
/// nothing, this payment or another, until the reloaded page no longer offers it (`moved`) or the record failed. One
/// payment at a time: while one records, every other Settle button is disabled (`busy`). A payment that later comes
/// back identical is a fresh one. Android's twin is `SettleLatch` in Settled.kt.
struct SettleLatch<Key: Equatable>: Equatable {
    private(set) var inFlight: Key?

    /// A payment is being recorded: every Settle button reads as disabled.
    var busy: Bool { inFlight != nil }

    /// A tap on `key`: true, and the latch holds it, unless a payment is already being recorded.
    mutating func begin(_ key: Key) -> Bool {
        guard inFlight == nil else { return false }
        inFlight = key
        return true
    }

    func settling(_ key: Key) -> Bool { inFlight == key }

    /// The record failed: the payment can be tried again.
    mutating func failed() { inFlight = nil }

    /// The page reloaded and now offers `offered`: once the payment in flight is not among them, the latch lets go.
    mutating func moved(to offered: [Key]) {
        if let key = inFlight, !offered.contains(key) { inFlight = nil }
    }
}

// MARK: - D-221 Task 6: the views

/// D-221 (L42): the settled meter: the track in `ringTrack` (Norse's segmented, `meterSegment` of track and `meterGap`
/// of `norseMeterGap`, repeating from the left), the fill the meter gradient across the filled width, both in C2's
/// meter shape (Aurora a capsule, Norse and Vardr Labs square). Decorative: the percent carries it. Android's twin is
/// `SettledMeter` in Settled.kt.
struct SettledMeter: View {
    /// A drawing ratio of core's basis points, never money.
    let fraction: Double
    var big = true

    /// art.py 19: big 10, small 6; Vardr Labs draws thinner (7 and 4).
    static func height(_ theme: AppTheme, big: Bool) -> CGFloat {
        switch (big, theme == .vardr) {
        case (true, true): return ThemeArt.m("meterBigVardr")
        case (true, false): return ThemeArt.m("meterBig")
        case (false, true): return ThemeArt.m("meterSmallVardr")
        case (false, false): return ThemeArt.m("meterSmall")
        }
    }

    static func fraction(_ basisPoints: UInt32) -> Double { Double(min(basisPoints, 10_000)) / 10_000 }

    var body: some View {
        let theme = AppearanceState.shared.theme
        let shape = ThemeArt.meterShape(theme)
        GeometryReader { geo in
            ZStack(alignment: .leading) {
                if theme == .norse {
                    Canvas { context, size in
                        context.fill(Path(CGRect(origin: .zero, size: size)), with: .color(ThemeArt.color("norseMeterGap")))
                        let segment = ThemeArt.m("meterSegment")
                        let step = segment + ThemeArt.m("meterGap")
                        var x: CGFloat = 0
                        while x < size.width {
                            context.fill(Path(CGRect(x: x, y: 0, width: min(segment, size.width - x), height: size.height)),
                                         with: .color(Tokens.ringTrack))
                            x += step
                        }
                    }
                } else {
                    Tokens.ringTrack
                }
                if fraction > 0 {
                    LinearGradient(colors: [Tokens.meterStart, Tokens.meterEnd], startPoint: .leading, endPoint: .trailing)
                        .frame(width: geo.size.width * min(fraction, 1))
                        .clipShape(shape)
                }
            }
        }
        .frame(height: Self.height(theme, big: big))
        .clipShape(shape)
        .accessibilityHidden(true)
    }
}

/// D-221 (L43, Design 2, 6): the group's Settled card: "Settled" (Norse's mark) and core's percent on one baseline, the
/// big meter, then what is still to settle in core's money. One block per currency (the settlement currency first);
/// with more than one, "Settled" heads the card once and each block's row names its currency in the kicker face.
/// Android's twin is `SettledCard`.
struct SettledCard: View {
    let blocks: [SettledProgressOut]

    var body: some View {
        let many = blocks.count > 1
        VStack(alignment: .leading, spacing: 8) {
            if many { Text(verbatim: SettledCopy.label).sectionLabel().foregroundStyle(Tokens.text) }
            ForEach(blocks, id: \.currencyCode) { b in
                let left = b.settled ? SettledCopy.nothingLeft : SettledCopy.stillToSettle(
                    (try? ffiMoneyFormat(minorUnits: b.outstandingMinor, currencyCode: b.currencyCode, locale: AppLocale.current)) ?? "")
                let spoken = b.settled ? SettledCopy.nothingLeft : SettledCopy.stillToSettle(
                    (try? ffiMoneySpoken(minorUnits: b.outstandingMinor, currencyCode: b.currencyCode, locale: AppLocale.current)) ?? "")
                VStack(alignment: .leading, spacing: 8) {
                    HStack(alignment: .firstTextBaseline) {
                        if many {
                            Text(verbatim: b.currencyCode).microLabelFont().foregroundStyle(Tokens.textDim)
                        } else {
                            Text(verbatim: SettledCopy.label).sectionLabel().foregroundStyle(Tokens.text)
                        }
                        Spacer(minLength: 8)
                        Text(verbatim: "\(SettledSummary.percent(b.basisPoints))%")
                            .font(TypeRole.numeric(TypeScale.settledPercent, weight: 700))
                            .foregroundStyle(Tokens.text)
                            .lineLimit(1)
                    }
                    SettledMeter(fraction: SettledMeter.fraction(b.basisPoints), big: true)
                    Text(verbatim: left).font(TypeRole.body(TypeScale.detail)).foregroundStyle(Tokens.textDim)
                }
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(Text(verbatim: SettledSpoken.block(
                    head: many ? b.currencyCode : SettledCopy.label, percent: SettledSummary.percent(b.basisPoints), left: spoken)))
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .glassCard()
    }
}

/// D-221 (L44, Design 11): the milestone card: the theme's seal (the tile's own shape, drawn side and edge, at `seal`)
/// with the number in the accent, the title, and "Since <the first expense's date>. Rare, and only once." Android's
/// twin is `MilestoneCard`.
struct MilestoneCard: View {
    let shown: Milestones.Shown

    var body: some View {
        let kind = SealStyle.clip(AppearanceState.shared.theme)
        let side = ThemeArt.m("seal")
        let shape = TileClip.shape(kind, size: side)
        let drawn = TileClip.drawnSide(kind, size: side)
        HStack(spacing: 12) {
            Text(verbatim: MilestoneWords.number(shown.milestone))
                .font(TypeRole.numeric(TypeScale.sealNumber, weight: 700))
                .foregroundStyle(Tokens.accent)
                .lineLimit(1)
                .frame(width: drawn, height: drawn)
                .background(Tokens.accentSoft, in: shape)
                .clipShape(shape)
                .overlay { TileClip.edge(kind) }
                .tileFootprint(kind, size: side)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text(verbatim: MilestoneWords.title(shown.milestone)).font(TypeRole.name(16)).foregroundStyle(Tokens.text)
                Text(verbatim: SettledCopy.since(AppDate.day(shown.sinceMs)))
                    .font(TypeRole.body(TypeScale.detail)).foregroundStyle(Tokens.textDim)
            }
            Spacer(minLength: 0)
        }
        .glassCard()
        .accessibilityElement(children: .combine)
    }
}

/// D-221 (L41, Q17 a, Design 8): the group's next card: the viewer's largest open payment ("Sam pays you ₹560.00"),
/// the muted count of the plan's payments (`GroupNext.planCount`, D-247), section 11.6's sentence, and "Settle" ("Settling" while it records). It records
/// through Settle up's own hold (`SettleHold`, D-247: written after the Undo window), then the page reloads. The latch keeps "Settling" until the
/// reloaded page picks another payment, so a second tap never records twice. Never shown for a read-only copy (the
/// page decides). Android's twin is `GroupNextCard`.
struct GroupNextCard: View {
    let store: LedgerStore
    let named: NamedGroupWire
    let history: [String: String]
    let pick: GroupNext.Pick
    let remaining: Int
    /// The record landed: the page reloads (its pick moves on).
    let onRecorded: () -> Void
    /// The record failed: the page's error line says why.
    let onError: (String) -> Void

    @State private var latch = SettleLatch<GroupNext.Pick>()

    var body: some View {
        let large = AppearanceState.shared.contentSizeCategory.isAccessibilityCategory
        let layout = large
            ? AnyLayout(VStackLayout(alignment: .leading, spacing: 12))
            : AnyLayout(HStackLayout(alignment: .center, spacing: 12))
        let other = LedgerStore.label(for: pick.entry.id, in: named, history: history)
        let amount = (try? ffiMoneyFormat(minorUnits: abs(pick.entry.minorUnits), currencyCode: pick.currencyCode,
                                          locale: AppLocale.current)) ?? ""
        let spoken = (try? ffiMoneySpoken(minorUnits: abs(pick.entry.minorUnits), currencyCode: pick.currencyCode,
                                          locale: AppLocale.current)) ?? ""
        let settling = latch.settling(pick)
        layout {
            VStack(alignment: .leading, spacing: 4) {
                Text(verbatim: "\(SettleAllCopy.sentence(youOwe: pick.youOwe, other: other)) \(amount)")
                    .font(TypeRole.name(16))
                    .foregroundStyle(Tokens.text)
                Group {
                    Text(verbatim: GroupNext.remainingLine(remaining))
                    // Section 11.6, verbatim: the line SKIPTI prints wherever a payment is recorded.
                    Text(verbatim: SettleAllCopy.explainer)
                }
                .font(TypeRole.body(TypeScale.detail))
                .foregroundStyle(Tokens.textDim)
                .fixedSize(horizontal: false, vertical: true)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            // S4: the words are one element in spoken money; the button stays its own (M7).
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(Text(verbatim: SettledSpoken.next(
                sentence: SettleAllCopy.sentence(youOwe: pick.youOwe, other: other), amount: spoken,
                remaining: GroupNext.remainingLine(remaining))))
            Button {
                guard latch.begin(pick) else { return }
                // D-247 (decision 182): held for Undo, as Settle up's cards are (`SettleHold`, the one write path).
                do {
                    try SettleHold.shared.hold(SettleAll.settlement(for: pick.entry, currency: pick.currencyCode, in: named),
                                               other: other, store: store)
                    onRecorded()
                } catch {
                    latch.failed()
                    onError("Could not record the settlement. \(String(describing: error))")
                }
            } label: {
                Text(verbatim: settling ? SettledCopy.settling : SettledCopy.settle)
                    .frame(maxWidth: large ? .infinity : nil)
            }
            .buttonStyle(PrimaryButtonStyle(compact: true))
            .disabled(latch.settling(pick))
        }
        .onChange(of: pick) { latch.moved(to: [pick]) }
        .glassCard()
    }
}

// MARK: - D-221 Task 8: the celebration

/// D-221 (Design 12): one play of the celebration: the reference-date seconds it started at (the burst draws at
/// `t - startedAt`) and whether it plays still (Reduce Motion or Less motion when it started: no particles, no motion; D-239: never power saving).
struct CelebrationPlay: Hashable {
    let startedAt: Double
    let still: Bool

    /// Seconds since the play started, at `date`; a still play has no clock (its one frame never changes).
    func elapsed(at date: Date) -> Double { still ? 0 : date.timeIntervalSinceReferenceDate - startedAt }
}

/// D-221 (Design 12): the celebration's timeline, linear, the twin of Android's `CelebrationTimeline`. Moving: the
/// page's content goes from 1 to `dimTo` over `dimIn` and back over the last `dimIn`; the words fade in over
/// `messageIn` rising `messageRise` points to their place, and undo it over the last `messageIn`. Still: dimmed and
/// shown at once for `total`, then gone.
enum CelebrationTimeline {
    /// The words' opacity and how far below their place they are, in points.
    struct MessagePose: Equatable { let alpha: Double; let rise: Double }

    /// The opacity of the page's content (1 is undimmed).
    static func dim(elapsed: Double, still: Bool) -> Double {
        let total = CelebrationTiming.total, to = CelebrationTiming.dimTo
        if elapsed >= total { return 1 }
        if still { return to }
        let ramp = min(max(min(elapsed, total - elapsed), 0) / CelebrationTiming.dimIn, 1)
        return 1 - (1 - to) * ramp
    }

    static func message(elapsed: Double, still: Bool) -> MessagePose {
        let total = CelebrationTiming.total
        if still { return elapsed < total ? MessagePose(alpha: 1, rise: 0) : MessagePose(alpha: 0, rise: 0) }
        let shown = min(max(min(elapsed, total - elapsed), 0) / CelebrationTiming.messageIn, 1)
        return MessagePose(alpha: shown, rise: CelebrationTiming.messageRise * (1 - shown))
    }
}

extension Celebration {
    /// What VoiceOver hears, once per play: the two lines.
    static func announcement(_ group: String) -> String { "\(SettleAllCopy.settled(group)). \(SettleAllCopy.settledDetail)" }

    /// Posted when the overlay appears (once per play), queued behind whatever VoiceOver is saying (the Settle tap's
    /// own feedback) rather than cutting it off. Nothing is logged (GUARANTEES #1).
    static func announce(_ group: String) {
        UIAccessibility.post(notification: .announcement, argument: NSAttributedString(
            string: announcement(group), attributes: [.accessibilitySpeechQueueAnnouncement: true]))
    }
}

/// The page's content under a play: its opacity on the art's 30 fps grid (ArtSchedule), paused when there is no play
/// or it plays still. The same view tree with or without a play, so a play starting or ending never rebuilds the
/// page's content (its cards keep their state). Only an opacity: it blocks no touch.
struct CelebrationDim: ViewModifier {
    let play: CelebrationPlay?

    func body(content: Content) -> some View {
        TimelineView(ArtSchedule(paused: play.map(\.still) ?? true)) { timeline in
            content.opacity(play.map { CelebrationTimeline.dim(elapsed: $0.elapsed(at: timeline.date), still: $0.still) } ?? 1)
        }
    }
}

/// D-221 (L45, Design 12): the settled celebration over the group's page: the burst through the motion engine (only
/// when `Celebration.showsBurst`) and the two lines, centred at `messageTop` of the page. GUARANTEES #2: it never takes
/// a touch (hit testing off; no control, gesture, sheet, cover, alert or dialog), so the + and every control work
/// through it. VoiceOver hears it once, as an announcement; the words on screen are hidden from it. Android's twin is
/// `CelebrationOverlay` in Settled.kt.
struct CelebrationOverlay: View {
    let play: CelebrationPlay
    let groupName: String
    @Environment(\.motionReduced) private var reduceMotion

    var body: some View {
        let theme = AppearanceState.shared.theme
        GeometryReader { geo in
            ZStack {
                if !play.still && Celebration.showsBurst(reduceMotion: reduceMotion) {
                    ArtCanvas(still: 0, moment: true, symbols: { ArtDraw.burstSymbols(theme) }) { ctx, size, t in
                        ArtDraw.burst(&ctx, size, t - play.startedAt, theme: theme)
                    }
                }
                TimelineView(ArtSchedule(paused: play.still)) { timeline in
                    let words = CelebrationTimeline.message(elapsed: play.elapsed(at: timeline.date), still: play.still)
                    VStack(spacing: 6) {
                        Text(verbatim: SettleAllCopy.settled(groupName))
                            .font(TypeRole.display(TypeScale.displayName, weight: 700))
                        Text(verbatim: SettleAllCopy.settledDetail)
                            .font(TypeRole.body(TypeScale.detail))
                    }
                    // D-234 (L10, decision 95): on the theme's card, so the words never read on the page's own words.
                    .frame(maxWidth: .infinity)
                    .foregroundStyle(Tokens.text)
                    .multilineTextAlignment(.center)
                    .glassCard()
                    .padding(.horizontal, 24)
                    .frame(width: geo.size.width)
                    .opacity(words.alpha)
                    .offset(y: words.rise)
                    .position(x: geo.size.width / 2, y: geo.size.height * CelebrationTiming.messageTop)
                }
            }
        }
        .allowsHitTesting(false)
        .accessibilityHidden(true)
        // D-224 (Design 9): as it starts, a settle toast posted a moment before gives way, and the success plays through
        // the app's gate (the settle's own success, a moment before, keeps it to one).
        .onAppear {
            // D-247: a toast that carries an action (a held settle's Undo) stays reachable.
            DoneCenter.shared.giveWay()
            DoneCenter.shared.success()
            Celebration.announce(groupName)
        }
    }
}
