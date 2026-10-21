import Foundation
import Observation
import SwiftUI
import UIKit

// D-224 (E3, "done moments and haptics"): the models, the copy and the one door to the phone's haptics. A done moment is
// small (a toast that passes) or big (the self-drawing check, for the once-only moments). Android's twin is
// DoneMoments.kt; its DoneMomentsTest reads this file's copy. Values come from .drafts/d224/art.py and motion.py
// (ThemeArt, ThemeMotion). Nothing here is logged (GUARANTEES #1), and nothing waits: a new moment replaces the one
// showing (GUARANTEES #2).

/// The three haptics SKIPTI plays: a light tap when a tab switches, success when something is done, warning when a
/// destructive confirmation opens.
enum HapticKind: CaseIterable, Equatable {
    case light
    case success
    case warning
}

/// D-224 (L48): the Haptics switch on Appearance, stored with the other appearance preferences. On unless the person
/// turned it off (the phone's own System Haptics setting stays the master: iOS silences the generators when it is off).
enum HapticsSetting {
    static let storageKey = "appearance.haptics"

    /// Nothing stored (never touched) is on.
    static func parse(_ raw: Bool?) -> Bool { raw ?? true }
}

/// D-224: the one door to the phone's haptics. Nothing else in the app touches a feedback generator (a source guard in
/// DoneMomentsTests holds it).
enum Haptics {
    /// Whether `kind` plays with the switch at `enabled`: the switch silences all three. (Reduce Motion never does.)
    static func plays(_ kind: HapticKind, enabled: Bool) -> Bool { enabled }

    @MainActor
    static func play(_ kind: HapticKind) {
        let enabled = HapticsSetting.parse(UserDefaults.standard.object(forKey: HapticsSetting.storageKey) as? Bool)
        guard plays(kind, enabled: enabled) else { return }
        switch kind {
        case .light: UIImpactFeedbackGenerator(style: .light).impactOccurred()
        case .success: UINotificationFeedbackGenerator().notificationOccurred(.success)
        case .warning: UINotificationFeedbackGenerator().notificationOccurred(.warning)
        }
    }
}

/// A small moment's line: the title, then its parts (joined on screen with the theme's separator). D-247: `action`, the
/// label of the one thing the toast offers (a held settle's "Undo"); what it does is the centre's (`DoneCenter.act`).
struct DoneLine: Equatable {
    let title: String
    let parts: [String]
    var action: String? = nil
    /// D-247 fix wave (decision 201): how long a line with an action stays (the hold's window: 12 s with a screen
    /// reader, else `DoneCenter.actionSeconds`).
    var actionSeconds: Double = DoneCenter.actionSeconds
}

/// The once-only moments the big check marks.
enum BigKind: Equatable {
    case firstGroup
    case signedIn
    case joined
    case erased
}

struct BigMoment: Equatable {
    let kind: BigKind
    let title: String
    let parts: [String]
}

enum DoneMoment: Equatable {
    case small(DoneLine)
    case big(BigMoment)

    var title: String {
        switch self {
        case .small(let line): return line.title
        case .big(let moment): return moment.title
        }
    }

    var parts: [String] {
        switch self {
        case .small(let line): return line.parts
        case .big(let moment): return moment.parts
        }
    }

    /// A big moment: the check (and, posted to the shell, the dim and the overlay); a small one: the toast.
    var isBig: Bool {
        if case .big = self { return true }
        return false
    }
}

/// D-224: the done moments' words, both shells verbatim (Android's DoneMomentsTest parses these). Parts: an expense
/// `[description, amount]` (the amount from core's `ffiMoneyFormat`, never built here); a username `["@handle"]`; a
/// settlement `[amount]`; a group `[name]`; the first group `[name] + GroupsCopy.detailParts(...)`.
enum DoneCopy {
    static let expenseAdded = "Expense added"
    static let expenseSaved = "Expense saved"
    static let groupCreated = "Group created"
    static func personRemoved(_ name: String) -> String { "\(name) removed" }
    static let groupPictureSaved = "Group picture saved"
    static let groupPictureRemoved = "Group picture removed"
    static let pictureSaved = "Picture saved"
    static let pictureRemoved = "Picture removed"
    static let nameSaved = "Name saved"
    static let usernameSaved = "Username saved"
    static let signedOut = "Signed out"
    /// D-247 (decision 182): a settle held for Undo (written when the toast's time is up).
    static func settledWith(_ name: String) -> String { "Settled with \(name). No money moved." }
    /// D-247: the held settle's toast action, and the toast that says Undo wrote nothing.
    static let undo = "Undo"
    static let notRecorded = "Not recorded."
    /// D-248 (U30.4): an expense deleted, its Undo on the toast wherever the delete happened.
    static let expenseDeleted = "Expense deleted"
    /// D-248 fix wave (review M2): the toast when that Undo could not write the expense back (as `notRecorded` says a
    /// settle's Undo wrote nothing).
    static let notRestored = "Not restored."
    /// D-247 fix wave (decision 201): what a screen reader hears after a toast that offers an action, so the action is
    /// named and its time said (`seconds`, whole seconds as digits).
    static func actionSpoken(_ label: String, seconds: String) -> String { "\(label) is available for \(seconds) seconds." }
    static let groupNameSaved = "Group name saved"
    /// U27 (the founder's words, 2026-10-20): a group deleted, here or for everyone.
    static let groupDeleted = "Group deleted"
    /// The Haptics row on Appearance (L48).
    static let hapticsTitle = "Haptics"
    /// D-234 (decision 83): fits the switch row on a 360 dp phone in every theme (T1's width test).
    static let hapticsSummary = "A tap when it is done"

    /// What a screen reader says: the title and the parts joined with a comma (`SpokenLine`). The separator is never
    /// read.
    static func spoken(title: String, parts: [String]) -> String { SpokenLine.join([title] + parts) }

    /// D-247 fix wave (decision 201): a line with an action ends with the action named (`actionSpoken`).
    static func spoken(_ line: DoneLine) -> String {
        let words = spoken(title: line.title, parts: line.parts)
        guard let action = line.action else { return words }
        let seconds = String(Int(line.actionSeconds.rounded()))
        return words + " " + actionSpoken(action, seconds: seconds)
    }

    static func spoken(_ moment: DoneMoment) -> String {
        if case .small(let line) = moment { return spoken(line) }
        return spoken(title: moment.title, parts: moment.parts)
    }
}

/// The spoken form's joining, kept out of `DoneCopy` so that enum holds only words: pieces joined with ", ", and a
/// separator inside a piece (Norse's ᛫ or the middle dot, as in a category label) read as a comma too.
private enum SpokenLine {
    static let comma = ", "

    static func join(_ pieces: [String]) -> String { pieces.map(unseparated).joined(separator: comma) }

    static func unseparated(_ text: String) -> String {
        var out = text
        for sep in [Runes.separator, "\u{00B7}"] {
            out = out.replacingOccurrences(of: " \(sep) ", with: comma).replacingOccurrences(of: sep, with: " ")
        }
        return out
    }
}

/// D-224 (Design 7): which moments are big.
enum DoneMoments {
    /// A group made through create: big (the overlay's check) only when it is the vault's first named group, read
    /// AFTER the create (`namedCountAfter` 1); a later group, or a count that could not be read (0), is small. Never big
    /// without proof.
    static func groupCreated(namedCountAfter: Int, name: String, detailParts: [String]) -> DoneMoment {
        if namedCountAfter == 1 {
            return .big(BigMoment(kind: .firstGroup, title: DoneCopy.groupCreated, parts: [name] + detailParts))
        }
        return .small(DoneLine(title: DoneCopy.groupCreated, parts: [name]))
    }

    /// D-224 T5 (Design 7): an everyday moment (the toast): `title`, one of `DoneCopy`'s words, then the parts that are
    /// there. A missing or blank part is left out, never shown empty, so a note-less expense reads "Expense added ᛫
    /// 2,400". Android's twin is `DoneMoments.line`.
    static func line(_ title: String, _ parts: String?...) -> DoneMoment {
        .small(DoneLine(title: title, parts: kept(parts)))
    }

    /// The parts that are there: a missing or blank one is left out.
    private static func kept(_ parts: [String?]) -> [String] {
        parts.compactMap { part in
            guard let kept = part?.trimmingCharacters(in: .whitespacesAndNewlines), !kept.isEmpty else { return nil }
            return kept
        }
    }

    /// D-247: an everyday moment that offers one action (`label`), shown for `seconds` (the hold's window).
    static func line(_ title: String, parts: [String?] = [], action label: String,
                     seconds: Double = DoneCenter.actionSeconds) -> DoneMoment {
        .small(DoneLine(title: title, parts: kept(parts), action: label, actionSeconds: seconds))
    }

    /// The toast's time on screen: `ToastMotion.total`, or, with an action, the line's `actionSeconds` (the action's
    /// whole window, so it never goes before what it offers does).
    static func toastSeconds(_ line: DoneLine) -> Double {
        line.action == nil ? ToastMotion.total : line.actionSeconds
    }

    /// An expense added (or, `edited`, saved): its description and core's amount (the new amount on an edit).
    static func expense(edited: Bool, note: String?, paidMinor: Int64, currency: String) -> DoneMoment {
        line(edited ? DoneCopy.expenseSaved : DoneCopy.expenseAdded, note, amount(paidMinor, currency))
    }

    /// A moment's amount, from core's `ffiMoneyFormat` in the app's locale (ADR-0002: never built here); nil, and so
    /// left out of the line, when core cannot say.
    static func amount(_ minor: Int64, _ currency: String) -> String? {
        try? ffiMoneyFormat(minorUnits: minor, currencyCode: currency, locale: AppLocale.current)
    }
}

/// D-224 T5 (Design 3): the warning's latch, pure: whether a warning plays now, and whether it has played for this
/// opening. It plays as a confirmation opens (`show` turns true) and not again while that one stays open; closing it
/// re-arms the latch. Android's twin is `WarningLatch`.
enum WarningLatch {
    static func next(show: Bool, played: Bool) -> (play: Bool, played: Bool) {
        if !show { return (false, false) }
        if played { return (false, true) }
        return (true, true)
    }
}

/// D-224 T5 (Design 3): the warning haptic when a destructive or irreversible confirmation OPENS, never on its final
/// tap. `show` is the flag that presents it (a dialog's), or true for a confirming screen, which warns when it appears
/// (a new appearance is a new opening). Applied through `.hapticWarning(when:)` beside the confirmation. Android's twin
/// is `HapticWarning(show)`.
struct HapticWarning: ViewModifier {
    let show: Bool
    @State private var played = false

    func body(content: Content) -> some View {
        content.onChange(of: show, initial: true) { _, now in
            let step = WarningLatch.next(show: now, played: played)
            played = step.played
            if step.play { Haptics.play(.warning) }
        }
    }
}

extension View {
    /// D-224 T5 (Design 3): plays the warning haptic as the confirmation `show` presents opens.
    func hapticWarning(when show: Bool) -> some View { modifier(HapticWarning(show: show)) }
}

/// D-224 (Design 5, .drafts/d224/art.py section 39): the toast's bottom edge, in points above the tab bar row's bottom
/// when the bar shows (clear of the tallest + face), else above the safe area.
enum ToastPlace {
    static func bottom(barShown: Bool) -> CGFloat {
        barShown ? ThemeArt.m("toastClearOfBar") : ThemeArt.m("toastInset")
    }
}


// MARK: - D-224 Task 3: the centre, the views and their hosts

/// D-224 T5 (sites-extra 9): the app's one success gate. Every centre (the shell's, and a screen's own such as the
/// add-person sheet's) and every screen's check measure against it, so two successes inside `HapticGate.window` play
/// once, whichever centre posted them (Android's gate lives in its one `Haptics.play`). A test makes its own.
@MainActor
final class SuccessGate {
    static let shared = SuccessGate()

    /// When the last success haptic played (or will play, for a big moment's check still landing).
    private(set) var lastSuccess: Double?

    /// Whether a success at `at` plays; when it does, it becomes the last success.
    func admits(at: Double) -> Bool {
        guard HapticGate.allows(lastSuccess: lastSuccess, now: at) else { return false }
        lastSuccess = at
        return true
    }
}

/// D-224 (Design 4): the moment showing, if any. `post` replaces whatever shows (no queue, so nothing ever waits) and
/// records when it started on the art's clock (reference-date seconds); `clear` removes it. Each post takes a new
/// `serial`, so a host can tell a replacement from the moment it started with, even when the two read the same. `post`
/// plays the moment's success haptic (Design 3: a small one at once, a big one when its check lands) and posts the
/// announcement, once. Every success haptic in the app goes through `success`, the ONE place the gate is applied
/// (HapticGate: a success within a second of the last is skipped). Nothing is logged (GUARANTEES #1).
@Observable @MainActor
final class DoneCenter {
    static let shared = DoneCenter()

    private(set) var current: DoneMoment?
    private(set) var startedAt: Double = 0
    private(set) var serial = 0
    /// D-224 fix wave (F1, D24): a big moment posted from a sheet that is still closing waits for it: nothing draws and
    /// no haptic plays until `begin` starts its clock, so the check is seen drawing from its first frame and its success
    /// plays only when it can be seen. A small moment never waits.
    private(set) var held = false
    /// D-224 fix wave (F4): this big moment replaced a big moment still showing, so the page's dim holds at `dimTo`
    /// through its fade in rather than coming back up for the swap (`DoneDimLevel`).
    private(set) var dimHolds = false
    /// A big moment is showing: posted and not waiting.
    var showsBig: Bool { current?.isBig == true && !held }

    /// What a host's task follows: a new post, or a waiting moment that begins.
    struct Key: Hashable {
        let serial: Int
        let held: Bool
    }

    var key: Key { Key(serial: serial, held: held) }

    /// The longest a big moment waits for its sheet (seconds): a floor under a disappearance that never comes. A sheet
    /// closes in well under it, so the sheet's own `begin` is the start in practice.
    static let holdLimit = 2.0
    /// The success gate this centre plays through: the app's one (`SuccessGate.shared`, nil given) unless a test gives
    /// its own.
    @ObservationIgnored let gate: SuccessGate
    /// When the last success haptic played through this centre's gate (or will play).
    var lastSuccess: Double? { gate.lastSuccess }
    /// Plays a haptic kind after a delay in seconds (`playHaptic`; a test records instead).
    @ObservationIgnored private let haptic: @MainActor (HapticKind, Double) -> Void
    /// Says the moment to VoiceOver (`speak`; a test records instead).
    @ObservationIgnored private let announce: @MainActor (String) -> Void
    /// D-247: what the showing toast's action does (`post(_:onAction:)`); nil when it offers none.
    @ObservationIgnored private var onAction: (@MainActor () -> Void)?
    /// D-247 (decision 182): how long a toast that offers an action stays, the hold's window (`SettleHold`).
    nonisolated static let actionSeconds = 5.0

    init(gate: SuccessGate? = nil,
         haptic: @escaping @MainActor (HapticKind, Double) -> Void = DoneCenter.playHaptic,
         announce: @escaping @MainActor (String) -> Void = DoneCenter.speak) {
        self.gate = gate ?? .shared
        self.haptic = haptic
        self.announce = announce
    }

    /// Shows `moment` from `now`, replacing any moment showing. `still` is the motion's still mode (Reduce Motion or Low
    /// Power, read from the phone when nil): a big moment's haptic then plays at once, as its check is drawn whole.
    /// `held` (a big moment posted from a sheet that is closing): it waits for `begin`, its haptic and its announcement
    /// with it (D-224 follow-up, M1: VoiceOver can cut words queued while a sheet goes; Android speaks once its overlay
    /// composes, after the route has gone). A moment replaced before it begins is never said.
    func post(_ moment: DoneMoment, now: Double = Date().timeIntervalSinceReferenceDate, still: Bool? = nil,
              held: Bool = false, onAction: (@MainActor () -> Void)? = nil) {
        let waits = held && moment.isBig
        dimHolds = moment.isBig && !waits && showsBig
        self.onAction = onAction
        current = moment
        startedAt = now
        serial += 1
        self.held = waits
        if !waits {
            success(after: DoneHapticTiming.delay(big: moment.isBig, still: still ?? DoneCenter.motionStill()), now: now)
            announce(DoneCopy.spoken(moment))
        }
    }

    /// Starts the big moment that waits, from `now` (the sheet it was posted from has gone): its clock starts, so its
    /// check draws from the first frame, and its success is set for when the check lands. Nothing waits (or, `serial`
    /// given, a newer moment has replaced that one): nothing happens.
    func begin(now: Double = Date().timeIntervalSinceReferenceDate, still: Bool? = nil, ifStill serial: Int? = nil) {
        guard held, serial == nil || serial == self.serial else { return }
        held = false
        startedAt = now
        success(after: DoneHapticTiming.delay(big: true, still: still ?? DoneCenter.motionStill()), now: now)
        if let current { announce(DoneCopy.spoken(current)) }
    }

    /// A success haptic `delay` seconds after `now`, unless the gate skips it (measured at when it would play).
    func success(after delay: Double = 0, now: Double = Date().timeIntervalSinceReferenceDate) {
        guard gate.admits(at: now + delay) else { return }
        haptic(.success, delay)
    }

    func clear() {
        current = nil
        held = false
        dimHolds = false
        onAction = nil
    }

    /// D-247: the showing toast's action, once: it runs only for the moment numbered `serial` (a newer post, or a clear,
    /// makes an old tap do nothing).
    func act(_ serial: Int) {
        guard serial == self.serial, case .small(let line) = current, line.action != nil, let run = onAction else { return }
        onAction = nil
        run()
    }

    /// D-224 (Design 9), D-247: a celebration starting clears the toast, unless the toast offers an action (a held
    /// settle's Undo stays reachable for its whole window).
    func giveWay() {
        if case .small(let line) = current, line.action != nil { return }
        clear()
    }

    /// Clears the moment numbered `serial` only if it is still the one showing: a newer moment is left alone.
    func clear(ifStill serial: Int) {
        if self.serial == serial { clear() }
    }

    /// Seconds left of a moment lasting `total` that started at `startedAt`, at `now` (never below 0).
    nonisolated static func remaining(total: Double, startedAt: Double, now: Double) -> Double {
        max(0, total - (now - startedAt))
    }

    /// A host's end of the moment showing: it waits out the rest of `total`, then clears that moment if it still shows.
    /// A newer post changes `serial`, which cancels the host's task (`.task(id:)`), and the newer moment's task ends it.
    func expire(after total: Double) async {
        let serial = self.serial
        let left = DoneCenter.remaining(total: total, startedAt: startedAt, now: Date().timeIntervalSinceReferenceDate)
        try? await Task.sleep(for: .seconds(left))
        guard !Task.isCancelled else { return }
        clear(ifStill: serial)
    }

    /// The phone's haptic, at once or after `delay` seconds (through the one door, `Haptics.play`).
    static func playHaptic(_ kind: HapticKind, after delay: Double) {
        guard delay > 0 else {
            Haptics.play(kind)
            return
        }
        Task { @MainActor in
            try? await Task.sleep(for: .seconds(delay))
            Haptics.play(kind)
        }
    }

    /// The announcement, queued behind whatever VoiceOver is saying (a closing sheet's own words) rather than cutting it.
    static func speak(_ text: String) {
        UIAccessibility.post(notification: .announcement, argument: NSAttributedString(
            string: text, attributes: [.accessibilitySpeechQueueAnnouncement: true]))
    }

    /// The motion's still mode as the phone holds it now: a moment's rule (D-239, decision 137: Reduce Motion or Less
    /// motion; power saving never stills a moment).
    static func motionStill() -> Bool {
        !MotionClock.momentTicks(reduceMotion: MotionSetting.reducedNow(), active: true)
    }
}

/// D-224 (L49, Design 6): the big done check, in D-221's ring box: a circle of `doneCircleRadius` stroked in `accent`
/// from 12 o'clock clockwise over its well (`accentSoft`; Norse's dark gold), the tick through `DoneCheck.tick`, the
/// pop; Norse adds E4's rune ring with every rune lit, turning. Every frame is `CheckMotion.pose` of the moment's
/// elapsed time on the art's grid; still (Reduce Motion, Less motion, the app not active; D-239: never power saving) it is the end state. Two
/// presentations: on a screen the person leaves with a button (signed in, joined, erased), where the check starts as it
/// appears and plays its own success haptic when it lands; over the shell's tab content (`DoneOverlay`, the first group),
/// where the centre gives its start and plays the haptic. Decorative: the words beside it carry the meaning.
struct DoneCheck: View {
    enum Presentation: Equatable {
        case screen
        case overlay
    }

    let presentation: Presentation
    /// When the moment started (reference-date seconds): the centre's, over the shell; nil on a screen (it starts there).
    let startedAt: Double?

    init(_ presentation: Presentation, startedAt: Double? = nil) {
        self.presentation = presentation
        self.startedAt = startedAt
    }

    @Environment(\.motionReduced) private var reduceMotion
    @Environment(\.scenePhase) private var phase
    /// When this check first appeared (a screen's start).
    @State private var appeared = Date().timeIntervalSinceReferenceDate
    /// The start whose pop is over: in Aurora and Vardr Labs nothing moves after it, so the grid pauses on the end state.
    /// D-224 fix wave (M2, F3): kept per start, so a big moment that replaces one draws its check again.
    @State private var landedFor: Double?
    /// The start a screen's check has buzzed for (D24: once per moment, never again when the screen appears again).
    @State private var buzzedFor: Double?

    var body: some View {
        let theme = AppearanceState.shared.theme
        let tick = MotionClock.momentTicks(reduceMotion: reduceMotion, active: phase == .active)
        let artTick = MotionClock.artTicks(reduceMotion: reduceMotion, active: phase == .active)
        let start = startedAt ?? appeared
        let moving = DoneCheck.moving(tick: tick, artTick: artTick, theme: theme, start: start, landedFor: landedFor)
        let box = ThemeArt.m("doneCheck")
        let radius = ThemeArt.m("doneCircleRadius")
        let well = theme == .norse ? ThemeArt.color("norseCheckFill") : Tokens.accentSoft
        TimelineView(ArtSchedule(paused: !moving)) { timeline in
            let pose = CheckMotion.pose(MomentClock.elapsed(tick: moving, now: timeline.date.timeIntervalSinceReferenceDate,
                                                            startedAt: start,
                                                            still: tick ? CheckMotion.landedElapsed : CheckMotion.stillElapsed))
            ZStack {
                if theme == .norse {
                    RuneRing(done: 1, total: 1)
                        .rotationEffect(.degrees(pose.degrees))
                }
                ZStack {
                    Circle()
                        .fill(well)
                        .frame(width: radius * 2, height: radius * 2)
                    Circle()
                        .trim(from: 0, to: pose.circle)
                        .stroke(Tokens.accent, lineWidth: ThemeArt.m("doneCircleStroke"))
                        .rotationEffect(.degrees(-90))
                        .frame(width: radius * 2, height: radius * 2)
                    DoneCheck.tickPath(side: box)
                        .trim(from: 0, to: pose.tick)
                        .stroke(Tokens.accent, style: StrokeStyle(lineWidth: ThemeArt.m("doneTickStroke"), lineCap: .round,
                                                                  lineJoin: .round))
                }
                .frame(width: box, height: box)
                .scaleEffect(pose.scale)
            }
            .frame(width: ThemeArt.m("ringBox"), height: ThemeArt.m("ringBox"))
        }
        .accessibilityHidden(true)
        .allowsHitTesting(false)
        .task(id: start) {
            // A screen's success haptic: when the check lands, at once when still (through the centre's gate), once per
            // moment. Over the shell the centre plays it.
            if DoneCheck.buzzes(presentation, start: start, buzzedFor: buzzedFor) {
                let lands = start + DoneHapticTiming.delay(big: true, still: !tick)
                try? await Task.sleep(for: .seconds(max(0, lands - Date().timeIntervalSinceReferenceDate)))
                guard !Task.isCancelled else { return }
                buzzedFor = start
                DoneCenter.shared.success()
            }
            let end = start + CheckMotion.popAt + CheckMotion.popDuration
            try? await Task.sleep(for: .seconds(max(0, end - Date().timeIntervalSinceReferenceDate)))
            guard !Task.isCancelled else { return }
            landedFor = start
        }
    }

    /// Whether the check's grid ticks: while ticking at all, until the pop of the moment that started at `start` is over
    /// (Norse's ring keeps turning while the art does: D-239 review I1, power saving pauses it). A landing kept for an
    /// earlier start does not count.
    static func moving(tick: Bool, artTick: Bool, theme: AppTheme, start: Double, landedFor: Double?) -> Bool {
        tick && !(landedFor == start && (theme != .norse || !artTick))
    }

    /// Whether this check plays the success for the moment that started at `start`: a screen's does, once; the overlay's
    /// never (the centre plays it as the moment begins).
    static func buzzes(_ presentation: Presentation, start: Double, buzzedFor: Double?) -> Bool {
        presentation == .screen && buzzedFor != start
    }

    /// The tick through `DoneCheck.tick`, drawn in a box `side` points square (the points are in the 104 box).
    static func tickPath(side: CGFloat) -> Path {
        let unit = side / ThemeArt.m("doneCheck")
        var path = Path()
        for (i, point) in DoneCheck.tick.enumerated() {
            let at = CGPoint(x: point.0 * unit, y: point.1 * unit)
            if i == 0 { path.move(to: at) } else { path.addLine(to: at) }
        }
        return path
    }
}

/// D-224 (L50, Design 5): the small done toast: the owing dot holding Lucide's check, then the title and the parts
/// joined by the theme's separator (Norse's gold rune), at most two lines; on the surface with a hairline edge in the
/// theme's panel shape (Aurora rounded, Norse notched, Vardr Labs square), its shadow below. The screen less
/// `toastInset` a side. Hidden from VoiceOver (the centre announced it) and from touches: everything under it works.
struct DoneToast: View {
    let title: String
    let parts: [String]
    /// D-247: the one action the toast offers (its label), and what a tap on it does.
    var action: String? = nil
    var onAction: () -> Void = {}

    var body: some View {
        let shape = ThemeArt.panelShape(AppearanceState.shared.theme, radius: ThemeArt.m("toastRadius"))
        let dot = ThemeArt.m("toastDot")
        HStack(spacing: ThemeArt.m("toastGap")) {
            HStack(spacing: ThemeArt.m("toastGap")) {
                LucideIcon(glyph: .check, size: ThemeArt.m("toastGlyph"), stroke: ThemeArt.m("toastCheckStroke"))
                    .foregroundStyle(Tokens.onAccent)
                    .frame(width: dot, height: dot)
                    .background(Tokens.owing, in: Circle())
                Separator.text([title] + parts, size: TypeScale.toast)
                    .font(TypeRole.name(TypeScale.toast))
                    .foregroundStyle(Tokens.text)
                    .lineLimit(2)
                    .truncationMode(.tail)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            // The words never take a touch, and VoiceOver heard them from the centre.
            .accessibilityHidden(true)
            .allowsHitTesting(false)
            if let action { DoneToastAction(label: action, run: onAction) }
        }
        .padding(.vertical, ThemeArt.m("toastPadV"))
        .padding(.horizontal, ThemeArt.m("toastPadH"))
        // D-247 fix wave (review M2): the panel itself never takes a touch, so for the whole window what lies under it
        // still works; only `DoneToastAction` does (Android's twin passes every touch but the button too).
        .background { shape.fill(Tokens.surface).allowsHitTesting(false) }
        .overlay { shape.stroke(Tokens.hairline, lineWidth: 1).allowsHitTesting(false) }
        .compositingGroup()
        .shadow(color: .black.opacity(Double(ThemeArt.m("toastShadowAlpha"))), radius: ThemeArt.m("toastShadowRadius"),
                y: ThemeArt.m("toastShadowY"))
        .padding(.horizontal, ThemeArt.m("toastInset"))
    }
}

/// D-247 (decision 182): the toast's one action, the in-card link's look (`accent` on the toast's surface, the toast's
/// face), at least 44 points to touch. The only control in this file; it never waits on anyone: the moment ends on its
/// clock whether or not it is tapped (GUARANTEES #2). Android's twin is `DoneToastAction`.
struct DoneToastAction: View {
    let label: String
    let run: () -> Void

    var body: some View {
        Button(action: run) {
            Text(verbatim: label)
                .font(TypeRole.label(TypeScale.toast))
                .foregroundStyle(Tokens.accent)
                .frame(minWidth: 44, minHeight: 44)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

/// D-224 (Design 5): where a centre's small moment shows: its bottom edge `ToastPlace.bottom(barShown:)` above the
/// safe area's bottom (the tab bar row's bottom when the bar shows). Each frame is `ToastMotion.pose` of the moment's
/// elapsed time on the art's grid; it ends the moment after `ToastMotion.total`. Always present (an empty clear layer
/// when nothing shows), so its task always runs; it never takes a touch.
struct DoneToastHost: View {
    let center: DoneCenter
    var barShown = false

    @Environment(\.motionReduced) private var reduceMotion
    @Environment(\.scenePhase) private var phase

    var body: some View {
        let tick = MotionClock.momentTicks(reduceMotion: reduceMotion, active: phase == .active)
        Color.clear
            .allowsHitTesting(false)
            .overlay(alignment: .bottom) {
                if case .small(let line) = center.current {
                    let serial = center.serial
                    TimelineView(ArtSchedule(paused: !tick)) { timeline in
                        let elapsed = MomentClock.elapsed(tick: tick, now: timeline.date.timeIntervalSinceReferenceDate,
                                                          startedAt: center.startedAt, still: 0)
                        if let pose = ToastMotion.pose(elapsed: elapsed, still: !tick, total: DoneMoments.toastSeconds(line)) {
                            DoneToast(title: line.title, parts: line.parts, action: line.action) { center.act(serial) }
                                .opacity(pose.opacity)
                                .offset(y: pose.dy)
                        }
                    }
                    .padding(.bottom, ToastPlace.bottom(barShown: barShown))
                }
            }
            .task(id: center.serial) {
                if case .small(let line) = center.current { await center.expire(after: DoneMoments.toastSeconds(line)) }
            }
    }
}

/// D-224 (Design 6): the shell's tab content under a big moment: its opacity on the art's grid (E4's timeline: down to
/// `CelebrationTiming.dimTo` and back), paused when no big moment shows. Only an opacity, on the content alone: the tab
/// bar and the + are never dimmed, and nothing is blocked.
struct DoneDim: ViewModifier {
    let center: DoneCenter

    @Environment(\.motionReduced) private var reduceMotion
    @Environment(\.scenePhase) private var phase

    func body(content: Content) -> some View {
        let tick = MotionClock.momentTicks(reduceMotion: reduceMotion, active: phase == .active)
        let big = center.showsBig
        TimelineView(ArtSchedule(paused: !big || !tick)) { timeline in
            content.opacity(big ? DoneDimLevel.opacity(
                elapsed: MomentClock.elapsed(tick: tick, now: timeline.date.timeIntervalSinceReferenceDate,
                                             startedAt: center.startedAt, still: 0),
                still: !tick, holds: center.dimHolds) : 1)
        }
    }
}

/// D-224 (Design 6): a big moment over the shell's tab content (the first group): the check and its words centred at
/// `CelebrationTiming.messageTop` of the content's height, for `CelebrationTiming.total` (E4's timing, so the two big
/// moments feel like one family); the words in `text` (over the dimmed page). It never takes a touch, the bar is drawn
/// over it, and VoiceOver heard the centre's announcement. Always present, so its task always runs.
struct DoneOverlay: View {
    let center: DoneCenter

    @Environment(\.motionReduced) private var reduceMotion
    @Environment(\.scenePhase) private var phase

    var body: some View {
        let tick = MotionClock.momentTicks(reduceMotion: reduceMotion, active: phase == .active)
        GeometryReader { geo in
            if case .big(let moment) = center.current, !center.held {
                TimelineView(ArtSchedule(paused: !tick)) { timeline in
                    let words = CelebrationTimeline.message(
                        elapsed: MomentClock.elapsed(tick: tick, now: timeline.date.timeIntervalSinceReferenceDate,
                                                     startedAt: center.startedAt, still: 0),
                        still: !tick)
                    // The check and its words fade and rise together on E4's message curve (Android's twin, one column).
                    VStack(spacing: 6) {
                        DoneCheck(.overlay, startedAt: center.startedAt)
                        Text(verbatim: moment.title)
                            .font(TypeRole.display(TypeScale.displayName, weight: 700))
                        if !moment.parts.isEmpty {
                            Separator.text(moment.parts, size: TypeScale.detail)
                                .font(TypeRole.body(TypeScale.detail))
                        }
                    }
                    .foregroundStyle(Tokens.text)
                    .multilineTextAlignment(.center)
                    .opacity(words.alpha)
                    .offset(y: words.rise)
                    .padding(.horizontal, 24)
                    .frame(width: geo.size.width)
                    .position(x: geo.size.width / 2, y: geo.size.height * CelebrationTiming.messageTop)
                }
            }
        }
        .allowsHitTesting(false)
        .accessibilityHidden(true)
        .task(id: center.key) {
            guard center.current?.isBig == true else { return }
            if center.held {
                // F1: the sheet it was posted from begins it as it goes; this is only the floor under that.
                let serial = center.serial
                try? await Task.sleep(for: .seconds(DoneCenter.holdLimit))
                guard !Task.isCancelled else { return }
                center.begin(ifStill: serial)
            } else {
                await center.expire(after: CelebrationTiming.total)
            }
        }
    }
}


/// D-224 fix wave (F4): the page's opacity under a big moment: E4's timeline, except that a big moment replacing one still
/// showing (`holds`) keeps the page at `dimTo` through its fade in, so the swap never shows the page bright for a frame.
/// It still comes back up at its end. Android's twin is its own dim's hold.
enum DoneDimLevel {
    static func opacity(elapsed: Double, still: Bool, holds: Bool) -> Double {
        if holds && elapsed < CelebrationTiming.dimIn { return CelebrationTiming.dimTo }
        return CelebrationTimeline.dim(elapsed: elapsed, still: still)
    }
}
